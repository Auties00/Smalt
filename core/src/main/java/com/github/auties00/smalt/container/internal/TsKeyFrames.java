package com.github.auties00.smalt.container.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.container.Demuxer;
import com.github.auties00.smalt.container.DemuxerOptions;
import com.github.auties00.smalt.container.ts.TsDemuxer;
import com.github.auties00.smalt.io.DataReferenceResolver;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Track;
import com.github.auties00.smalt.track.TrackKind;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reads the key frames an HLS I-frame playlist of transport stream segments addresses: each byte range, behind the
 * program tables of the playlist's {@code EXT-X-MAP} when it has one, is a transport stream of its own whose video
 * packets are its key frame, so that the continuity counters that jump between ranges damage nothing. The tracks
 * are the video tracks of the first range; the timestamps of every range keep their place on the PES timeline of the
 * first, whose earliest presentation time is zero.
 *
 * <p>Instances are not thread-safe.
 */
public final class TsKeyFrames implements Demuxer {
    /**
     * The 33-bit wrap of PES timestamps.
     */
    private static final long WRAP = 1L << 33;

    private final SegmentSource.Part tables;
    private final List<SegmentSource.Part> ranges;
    private final DataReferenceResolver resolver;
    private final DemuxerOptions options;
    private final List<Track> tracks = new ArrayList<>();

    /**
     * The PES timestamp the first range starts from.
     */
    private final long origin;

    /**
     * The index of the next range.
     */
    private int next;

    /**
     * The packets of the range being read.
     */
    private final ArrayList<Packet> pending = new ArrayList<>();

    /**
     * The index of the next pending packet.
     */
    private int cursor;

    /**
     * Opens the key frames of an I-frame playlist.
     *
     * @param tables the program tables that precede each range, or {@code null} when every range holds its own
     * @param ranges the byte range of each key frame, in order
     * @param resolver opens the files
     * @param options the limits, for the segment demuxers
     * @throws MediaException.Malformed if the first range is not a transport stream
     * @throws MediaException.Unsupported if a file cannot be resolved
     * @throws MediaException.Io if reading fails
     */
    public TsKeyFrames(SegmentSource.Part tables, List<SegmentSource.Part> ranges, DataReferenceResolver resolver,
                       DemuxerOptions options) {
        this.tables = tables;
        this.ranges = List.copyOf(ranges);
        this.resolver = resolver;
        this.options = options;
        var origin = 0L;
        if (!this.ranges.isEmpty()) {
            try (var source = source(0); var demuxer = new TsDemuxer(source, options)) {
                origin = demuxer.originTicks();
                for (var track : demuxer.tracks()) {
                    if (track.kind() == TrackKind.VIDEO) {
                        tracks.add(track);
                    }
                }
            }
        }
        this.origin = origin;
    }

    /**
     * Opens the source of one range, behind the program tables.
     *
     * @param index the index of the range
     * @return the source
     */
    private SegmentSource source(int index) {
        var parts = tables == null ? List.of(ranges.get(index)) : List.of(tables, ranges.get(index));
        return new SegmentSource(parts, resolver, options.limits());
    }

    /**
     * {@return the PES presentation time the first range starts from, in 90 kHz ticks}
     */
    public long originTicks() {
        return origin;
    }

    @Override
    public ContainerFormat format() {
        return ContainerFormat.MPEG_TS;
    }

    @Override
    public List<Track> tracks() {
        return List.copyOf(tracks);
    }

    @Override
    public Map<String, String> metadata() {
        return Map.of();
    }

    @Override
    public long durationMicros() {
        return -1;
    }

    /**
     * {@inheritDoc}
     *
     * @throws MediaException.Malformed if a range is not a transport stream
     */
    @Override
    public Packet read() {
        while (cursor >= pending.size()) {
            if (next >= ranges.size()) {
                return null;
            }
            pending.clear();
            cursor = 0;
            var index = next++;
            try (var source = source(index); var demuxer = new TsDemuxer(source, options)) {
                var shift = Math.floorMod(demuxer.originTicks() - origin, WRAP);
                if (shift >= WRAP / 2) {
                    shift -= WRAP;
                }
                for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                    if (!isVideo(packet.trackId())) {
                        continue;
                    }
                    // video tracks keep the 90 kHz time base, so the shift applies as it is
                    var pts = packet.pts() == Packet.NO_TIMESTAMP ? Packet.NO_TIMESTAMP : packet.pts() + shift;
                    var dts = packet.dts() == Packet.NO_TIMESTAMP ? Packet.NO_TIMESTAMP : packet.dts() + shift;
                    pending.add(packet.withTiming(pts, dts, packet.duration()));
                }
            }
        }
        return pending.get(cursor++);
    }

    /**
     * Returns whether a track is one of the video tracks of the first range.
     *
     * @param trackId the track identifier
     * @return {@code true} for a video track
     */
    private boolean isVideo(int trackId) {
        for (var track : tracks) {
            if (track.id() == trackId) {
                return true;
            }
        }
        return false;
    }
}
