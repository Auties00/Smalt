package com.github.auties00.smalt.transcode;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.container.AttachedPicture;
import com.github.auties00.smalt.container.Attachment;
import com.github.auties00.smalt.container.Chapter;
import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.container.Demuxer;
import com.github.auties00.smalt.container.Timecode;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.DataTrack;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Track;
import com.github.auties00.smalt.track.TrackDisposition;
import com.github.auties00.smalt.track.VideoTrack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Presents the demuxers of several inputs as one, interleaving their packets by time.
 *
 * <p>The tracks of input {@code i} follow those of the inputs before it, renumbered from 1 in that order; the
 * global tags, chapters, attachments and pictures are the first input's. {@link #read()} returns the packet with the
 * earliest decoding time (its presentation time when it has none) among the next packet of every input that has a
 * selected track, so each input's packets keep their order. The demuxer seeks when every input does.
 *
 * <p>An instance is not thread-safe.
 */
final class MergedDemuxer implements SegmentedDemuxer {
    private final List<Demuxer> inputs;
    private final List<Track> tracks;

    /**
     * The input of each track, by merged identifier.
     */
    private final Map<Integer, Integer> inputOf = new HashMap<>();

    /**
     * The input's own identifier of each track, by merged identifier.
     */
    private final Map<Integer, Integer> originalOf = new HashMap<>();

    /**
     * The merged identifier of each track, by input then the input's identifier.
     */
    private final List<Map<Integer, Integer>> mergedOf = new ArrayList<>();

    /**
     * The next packet of each input, or {@code null} when not read yet or the input ended.
     */
    private final Packet[] next;

    /**
     * Whether each input ended.
     */
    private final boolean[] ended;

    /**
     * Whether each input has a selected track and is read.
     */
    private final boolean[] read;

    /**
     * The last time of each input in microseconds, which a packet without timestamps keeps.
     */
    private final long[] lastMicros;

    /**
     * Constructs a demuxer over the inputs' demuxers, which it owns.
     *
     * @param inputs the demuxers, each at its start
     */
    MergedDemuxer(List<Demuxer> inputs) {
        this.inputs = List.copyOf(inputs);
        var merged = new ArrayList<Track>();
        for (var index = 0; index < this.inputs.size(); index++) {
            var ids = new HashMap<Integer, Integer>();
            for (var track : this.inputs.get(index).tracks()) {
                var id = merged.size() + 1;
                merged.add(withId(track, id));
                inputOf.put(id, index);
                originalOf.put(id, track.id());
                ids.put(track.id(), id);
            }
            mergedOf.add(ids);
            if (this.inputs.get(index) instanceof SegmentedDemuxer segmented) {
                var input = index;
                segmented.segmentListener((trackId, track, startMicros) -> forward(input, trackId, track, startMicros));
            }
        }
        this.tracks = List.copyOf(merged);
        this.next = new Packet[this.inputs.size()];
        this.ended = new boolean[this.inputs.size()];
        this.read = new boolean[this.inputs.size()];
        Arrays.fill(read, true);
        this.lastMicros = new long[this.inputs.size()];
    }

    private SegmentedDemuxer.Listener listener;

    /**
     * Forwards the start of a later file of an input's concatenation.
     *
     * @param input the index of the input
     * @param trackId the input's track identifier
     * @param track the file's track
     * @param startMicros the start of the file
     */
    private void forward(int input, int trackId, Track track, long startMicros) {
        if (listener != null) {
            var id = mergedOf.get(input).get(trackId);
            listener.segmentStarted(id, withId(track, id), startMicros);
        }
    }

    /**
     * {@return a track with another identifier}
     *
     * @param track the track to renumber
     * @param id the identifier
     */
    private static Track withId(Track track, int id) {
        return switch (track) {
            case VideoTrack video -> video.toBuilder().id(id).build();
            case AudioTrack audio -> audio.toBuilder().id(id).build();
            case DataTrack data -> data.toBuilder().id(id).build();
        };
    }

    /**
     * Reads only the inputs that have a selected track.
     *
     * @param trackIds the merged identifiers of the selected tracks
     */
    void select(Set<Integer> trackIds) {
        Arrays.fill(read, false);
        for (var id : trackIds) {
            var input = inputOf.get(id);
            if (input != null) {
                read[input] = true;
            }
        }
    }

    /**
     * {@return the input a track comes from}
     *
     * @param trackId the merged identifier
     */
    int inputOf(int trackId) {
        return inputOf.getOrDefault(trackId, 0);
    }

    /**
     * {@return the input's own identifier of a track}
     *
     * @param trackId the merged identifier
     */
    int originalId(int trackId) {
        return originalOf.getOrDefault(trackId, trackId);
    }

    /**
     * {@return the merged identifier of an input's track, or {@code -1} when the input has none of that identifier}
     *
     * @param input the index of the input
     * @param trackId the input's identifier
     */
    int mergedId(int input, int trackId) {
        return mergedOf.get(input).getOrDefault(trackId, -1);
    }

    /**
     * {@return the demuxer of an input}
     *
     * @param input the index of the input
     */
    Demuxer input(int input) {
        return inputs.get(input);
    }

    /**
     * {@return the number of inputs}
     */
    int inputCount() {
        return inputs.size();
    }

    @Override
    public void segmentListener(SegmentedDemuxer.Listener listener) {
        this.listener = listener;
    }

    @Override
    public ContainerFormat format() {
        return inputs.getFirst().format();
    }

    @Override
    public String formatName() {
        return inputs.getFirst().formatName();
    }

    @Override
    public List<Track> tracks() {
        return tracks;
    }

    @Override
    public Map<String, String> metadata() {
        return inputs.getFirst().metadata();
    }

    @Override
    public Map<String, String> trackMetadata(int trackId) {
        var input = inputOf.get(trackId);
        return input == null ? Map.of() : inputs.get(input).trackMetadata(originalOf.get(trackId));
    }

    @Override
    public Set<TrackDisposition> trackDisposition(int trackId) {
        var input = inputOf.get(trackId);
        return input == null ? Set.of() : inputs.get(input).trackDisposition(originalOf.get(trackId));
    }

    @Override
    public List<Chapter> chapters() {
        return inputs.getFirst().chapters();
    }

    @Override
    public List<Attachment> attachments() {
        return inputs.getFirst().attachments();
    }

    @Override
    public Optional<Timecode> timecode() {
        return inputs.getFirst().timecode();
    }

    @Override
    public List<AttachedPicture> attachedPictures() {
        return inputs.getFirst().attachedPictures();
    }

    /**
     * {@inheritDoc}
     *
     * <p>The longest duration of the inputs, or {@code -1} when none declares one.
     */
    @Override
    public long durationMicros() {
        var longest = -1L;
        for (var input : inputs) {
            longest = Math.max(longest, input.durationMicros());
        }
        return longest;
    }

    @Override
    public Packet read() {
        while (true) {
            var best = -1;
            var bestMicros = Long.MAX_VALUE;
            for (var index = 0; index < inputs.size(); index++) {
                if (!read[index] || ended[index]) {
                    continue;
                }
                if (next[index] == null) {
                    next[index] = inputs.get(index).read();
                    if (next[index] == null) {
                        ended[index] = true;
                        continue;
                    }
                }
                var micros = timeOf(index, next[index]);
                if (micros < bestMicros) {
                    bestMicros = micros;
                    best = index;
                }
            }
            if (best < 0) {
                return null;
            }
            var packet = next[best];
            next[best] = null;
            var id = mergedOf.get(best).get(packet.trackId());
            if (id != null) {
                return packet.withTrackId(id);
            }
        }
    }

    /**
     * {@return the time a packet is interleaved at, in microseconds}
     *
     * @param input the packet's input
     * @param packet the next packet of the input
     */
    private long timeOf(int input, Packet packet) {
        var time = packet.dts() != Packet.NO_TIMESTAMP ? packet.dts() : packet.pts();
        var id = mergedOf.get(input).get(packet.trackId());
        if (time == Packet.NO_TIMESTAMP || id == null) {
            return lastMicros[input];
        }
        var micros = tracks.get(id - 1).timeBase().toMicros(time);
        lastMicros[input] = micros;
        return micros;
    }

    @Override
    public boolean isSeekable() {
        for (var input : inputs) {
            if (!input.isSeekable()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void seek(long micros) {
        if (!isSeekable()) {
            throw new MediaException.Unsupported("seek", "an input of the transcode cannot seek");
        }
        for (var index = 0; index < inputs.size(); index++) {
            inputs.get(index).seek(micros);
            next[index] = null;
            ended[index] = false;
        }
    }

    @Override
    public void close() {
        RuntimeException failure = null;
        for (var demuxer : inputs) {
            try {
                demuxer.close();
            } catch (RuntimeException exception) {
                if (failure == null) {
                    failure = exception;
                } else {
                    failure.addSuppressed(exception);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }
}
