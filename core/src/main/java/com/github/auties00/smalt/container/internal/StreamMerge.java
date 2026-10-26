package com.github.auties00.smalt.container.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.container.Demuxer;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.DataTrack;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.Track;
import com.github.auties00.smalt.track.TrackDisposition;
import com.github.auties00.smalt.track.VideoTrack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads the streams of an adaptive streaming presentation (the media playlists of an HLS master playlist, the
 * representations of a DASH manifest) as one demuxer: their tracks numbered from one in stream order, their packets
 * interleaved in decoding order, each stream's timestamps moved by its offset.
 *
 * <p>Instances are not thread-safe.
 */
public final class StreamMerge implements AutoCloseable {
    /**
     * One stream of the presentation.
     *
     * @param demuxer the demuxer of the stream, closed with the merge
     * @param offsetMicros the time added to the stream's timestamps, in microseconds
     * @param language the ISO 639-2 language its tracks take when they declare none, or {@code null}
     * @param title the title of its tracks, or {@code null}
     * @param dispositions the dispositions of its tracks
     * @param resources what is closed after the demuxer: the sources it reads
     */
    public record Stream(Demuxer demuxer, long offsetMicros, String language, String title,
                         Set<TrackDisposition> dispositions, List<AutoCloseable> resources) {
        /**
         * Copies the lists of the stream.
         */
        public Stream {
            dispositions = Set.copyOf(dispositions);
            resources = List.copyOf(resources);
        }
    }

    /**
     * The streams, in order.
     */
    private final List<Stream> streams;

    /**
     * The tracks, renumbered.
     */
    private final List<Track> tracks = new ArrayList<>();

    /**
     * The renumbered identifier of each track, by stream index and inner identifier.
     */
    private final Map<Long, Integer> ids = new HashMap<>();

    /**
     * The offset of each renumbered track, in its time base.
     */
    private final Map<Integer, Long> offsets = new HashMap<>();

    /**
     * The stream index of each renumbered track.
     */
    private final Map<Integer, Integer> streamOf = new HashMap<>();

    /**
     * The identifier each renumbered track has in its stream.
     */
    private final Map<Integer, Integer> innerIds = new HashMap<>();

    /**
     * The next packet of each stream, or {@code null} once it ended or before it was read.
     */
    private final Packet[] next;

    /**
     * The decoding time of each stream's next packet, in microseconds.
     */
    private final long[] times;

    /**
     * Whether each stream has ended.
     */
    private final boolean[] ended;

    /**
     * Whether the next packets must be read.
     */
    private boolean primed;

    /**
     * Merges streams.
     *
     * @param streams the streams, in order
     */
    public StreamMerge(List<Stream> streams) {
        this.streams = List.copyOf(streams);
        this.next = new Packet[this.streams.size()];
        this.times = new long[this.streams.size()];
        this.ended = new boolean[this.streams.size()];
        for (var index = 0; index < this.streams.size(); index++) {
            var stream = this.streams.get(index);
            for (var track : stream.demuxer().tracks()) {
                var id = tracks.size() + 1;
                var offset = Rational.MICROSECONDS.rescale(stream.offsetMicros(), track.timeBase());
                tracks.add(renumber(track, id, stream.language()));
                ids.put((long) index << 32 | (track.id() & 0xFFFFFFFFL), id);
                offsets.put(id, offset);
                streamOf.put(id, index);
                innerIds.put(id, track.id());
            }
        }
    }

    /**
     * Returns a track under another identifier, with a language when it declares none.
     *
     * @param track the track to renumber
     * @param id the identifier
     * @param language the ISO 639-2 language, or {@code null}
     * @return the track
     */
    private static Track renumber(Track track, int id, String language) {
        var keep = language == null || track.language().filter(code -> !code.equals("und")).isPresent();
        return switch (track) {
            case VideoTrack video -> keep ? video.toBuilder().id(id).build()
                    : video.toBuilder().id(id).language(language).build();
            case AudioTrack audio -> keep ? audio.toBuilder().id(id).build()
                    : audio.toBuilder().id(id).language(language).build();
            case DataTrack data -> keep ? data.toBuilder().id(id).build()
                    : data.toBuilder().id(id).language(language).build();
        };
    }

    /**
     * {@return the tracks of every stream, numbered from one}
     */
    public List<Track> tracks() {
        return List.copyOf(tracks);
    }

    /**
     * Returns the metadata of a track: its stream's title.
     *
     * @param trackId the renumbered track identifier
     * @return the metadata
     */
    public Map<String, String> trackMetadata(int trackId) {
        var index = streamOf.get(trackId);
        if (index == null) {
            return Map.of();
        }
        var stream = streams.get(index);
        var inner = new HashMap<>(stream.demuxer().trackMetadata(innerId(index, trackId)));
        if (stream.title() != null) {
            inner.put("title", stream.title());
        }
        return Map.copyOf(inner);
    }

    /**
     * Returns the dispositions of a track: its stream's and its own.
     *
     * @param trackId the renumbered track identifier
     * @return the dispositions
     */
    public Set<TrackDisposition> trackDisposition(int trackId) {
        var index = streamOf.get(trackId);
        if (index == null) {
            return Set.of();
        }
        var stream = streams.get(index);
        var all = new java.util.HashSet<>(stream.demuxer().trackDisposition(innerId(index, trackId)));
        all.addAll(stream.dispositions());
        return Set.copyOf(all);
    }

    /**
     * Returns the identifier a renumbered track has in its stream.
     *
     * @param index the stream index
     * @param trackId the renumbered identifier
     * @return the inner identifier
     */
    private int innerId(int index, int trackId) {
        return innerIds.getOrDefault(trackId, -1);
    }

    /**
     * {@return the duration of the longest stream, in microseconds, or -1 when none reports one}
     */
    public long durationMicros() {
        var duration = -1L;
        for (var stream : streams) {
            var inner = stream.demuxer().durationMicros();
            if (inner >= 0) {
                duration = Math.max(duration, inner + stream.offsetMicros());
            }
        }
        return duration;
    }

    /**
     * Reads the next packet in decoding order across the streams.
     *
     * @return the packet, with its renumbered track identifier and moved timestamps, or {@code null} at the end
     */
    public Packet read() {
        if (!primed) {
            primed = true;
            for (var index = 0; index < streams.size(); index++) {
                advance(index);
            }
        }
        var earliest = -1;
        for (var index = 0; index < streams.size(); index++) {
            if (next[index] != null && (earliest < 0 || times[index] < times[earliest])) {
                earliest = index;
            }
        }
        if (earliest < 0) {
            return null;
        }
        var packet = next[earliest];
        advance(earliest);
        return packet;
    }

    /**
     * Reads the next packet of a stream.
     *
     * @param index the stream index
     */
    private void advance(int index) {
        next[index] = null;
        if (ended[index]) {
            return;
        }
        var stream = streams.get(index);
        while (true) {
            var packet = stream.demuxer().read();
            if (packet == null) {
                ended[index] = true;
                return;
            }
            var id = ids.get((long) index << 32 | (packet.trackId() & 0xFFFFFFFFL));
            if (id == null) {
                continue;
            }
            var offset = offsets.get(id);
            var pts = packet.pts() == Packet.NO_TIMESTAMP ? Packet.NO_TIMESTAMP : packet.pts() + offset;
            var dts = packet.dts() == Packet.NO_TIMESTAMP ? Packet.NO_TIMESTAMP : packet.dts() + offset;
            var moved = offset == 0 ? packet.withTrackId(id) : packet.withTrackId(id).withTiming(pts, dts,
                    packet.duration());
            var time = dts != Packet.NO_TIMESTAMP ? dts : pts;
            if (time != Packet.NO_TIMESTAMP) {
                times[index] = tracks.get(id - 1).timeBase().toMicros(time);
            }
            next[index] = moved;
            return;
        }
    }

    /**
     * {@return whether every stream can seek}
     */
    public boolean isSeekable() {
        for (var stream : streams) {
            if (!stream.demuxer().isSeekable()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Seeks every stream.
     *
     * @param micros the presentation time
     * @throws MediaException.Unsupported if a stream cannot seek
     */
    public void seek(long micros) {
        for (var index = 0; index < streams.size(); index++) {
            var stream = streams.get(index);
            stream.demuxer().seek(micros - stream.offsetMicros());
            ended[index] = false;
            next[index] = null;
        }
        primed = false;
    }

    @Override
    public void close() {
        RuntimeException failure = null;
        for (var stream : streams) {
            try {
                stream.demuxer().close();
            } catch (RuntimeException exception) {
                failure = failure == null ? exception : failure;
            }
            for (var resource : stream.resources()) {
                try {
                    resource.close();
                } catch (Exception exception) {
                    if (failure == null) {
                        failure = exception instanceof RuntimeException runtime ? runtime
                                : new IllegalStateException(exception);
                    }
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }
}
