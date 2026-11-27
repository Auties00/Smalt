package com.github.auties00.smalt.container.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.container.Demuxer;
import com.github.auties00.smalt.container.DemuxerOptions;
import com.github.auties00.smalt.container.ts.SampleAesKey;
import com.github.auties00.smalt.container.ts.SampleAesKeys;
import com.github.auties00.smalt.container.ts.TsDemuxer;
import com.github.auties00.smalt.container.ts.TsDemuxerOptions;
import com.github.auties00.smalt.io.DataReferenceResolver;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Track;
import com.github.auties00.smalt.track.TrackKind;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/**
 * Reads the key frames an HLS I-frame playlist of transport stream segments addresses: each byte range, behind the
 * program tables of the playlist's {@code EXT-X-MAP} when it has one, is a transport stream of its own whose video
 * packets are its key frame (the first video access unit of the range: a segmenter may end a range after the
 * transport stream packet that opens the next frame), so that the continuity counters that jump between ranges
 * damage nothing. Without an {@code EXT-X-MAP} a media resource begins with its initialization section (RFC 8216
 * clause 4.3.3.6): a range that starts later in its resource is read behind the program tables at the resource's
 * start, the transport stream packets of the program association table, the program map tables it names and the
 * other tables of PIDs below {@code 0x20} that open the resource. The tracks are the video tracks of the first
 * range; the timestamps of every range keep their place on the PES timeline of the
 * first, whose earliest presentation time is zero.
 *
 * <p>Instances are not thread-safe.
 */
public final class TsKeyFrames implements Demuxer {
    /**
     * The 33-bit wrap of PES timestamps.
     */
    private static final long WRAP = 1L << 33;

    /**
     * The size of a transport stream packet.
     */
    private static final int PACKET = 188;

    /**
     * The most packets of leading program tables read before a range.
     */
    private static final int MAX_TABLES = 64;

    private final SegmentSource.Part tables;
    private final List<SegmentSource.Part> ranges;
    private final DataReferenceResolver resolver;
    private final DemuxerOptions options;

    /**
     * The {@code SAMPLE-AES} key of each range, {@code null} for a clear one.
     */
    private final SampleAesKey[] keys;

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
     * @param keys the {@code SAMPLE-AES} key of each range, {@code null} for a clear one, or {@code null} when every
     * range is clear
     * @throws MediaException.Malformed if the first range is not a transport stream
     * @throws MediaException.Unsupported if a file cannot be resolved
     * @throws MediaException.Io if reading fails
     */
    public TsKeyFrames(SegmentSource.Part tables, List<SegmentSource.Part> ranges, DataReferenceResolver resolver,
                       DemuxerOptions options, List<SampleAesKey> keys) {
        this.tables = tables;
        this.ranges = List.copyOf(ranges);
        this.resolver = resolver;
        this.options = options;
        this.keys = keys == null ? new SampleAesKey[this.ranges.size()] : keys.toArray(SampleAesKey[]::new);
        if (this.keys.length != this.ranges.size()) {
            throw new IllegalArgumentException(this.keys.length + " keys for " + this.ranges.size() + " ranges");
        }
        var origin = 0L;
        if (!this.ranges.isEmpty()) {
            try (var source = source(0); var demuxer = new TsDemuxer(source, demuxerOptions(0))) {
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
        var range = ranges.get(index);
        var leading = tables != null ? tables : leadingTables(range);
        var parts = leading == null ? List.of(range) : List.of(leading, range);
        return new SegmentSource(parts, resolver, options.limits());
    }

    /**
     * Returns the program tables that open the resource of a range that starts after them, its initialization
     * section when the playlist has no {@code EXT-X-MAP}.
     *
     * @param range the part of the resource the range reads
     * @return the part of the leading table packets, or {@code null} when the range starts its resource, is encrypted
     * whole or the resource opens with no table
     */
    private SegmentSource.Part leadingTables(SegmentSource.Part range) {
        if (range.offset() < PACKET || range.key() != null) {
            return null;
        }
        var head = new byte[(int) Math.min(range.offset(), MAX_TABLES * PACKET)];
        int read;
        try (var source = new SegmentSource(List.of(new SegmentSource.Part(range.uri(), 0, head.length, null, null)),
                resolver, options.limits())) {
            read = 0;
            while (read < head.length) {
                var count = source.read(read, head, read, head.length - read);
                if (count <= 0) {
                    break;
                }
                read += count;
            }
        }
        var programMaps = new HashSet<Integer>();
        var length = 0;
        while (length + PACKET <= read && head[length] == 0x47) {
            var pid = (head[length + 1] & 0x1F) << 8 | head[length + 2] & 0xFF;
            if (pid >= 0x20 && !programMaps.contains(pid)) {
                break;
            }
            if (pid == 0 && (head[length + 1] & 0x40) != 0) {
                programMaps.addAll(programMapPids(head, length));
            }
            length += PACKET;
        }
        return length == 0 ? null : new SegmentSource.Part(range.uri(), 0, length, null, null);
    }

    /**
     * Returns the program map table PIDs of a program association table that starts in one transport stream packet.
     *
     * @param data the packets
     * @param packet the offset of the packet, whose payload starts a section
     * @return the PIDs, empty when the section does not fit the packet
     */
    private static List<Integer> programMapPids(byte[] data, int packet) {
        var position = packet + 4;
        var control = (data[packet + 3] >> 4) & 0x03;
        if (control == 2) {
            return List.of();
        }
        if (control == 3) {
            position += 1 + (data[position] & 0xFF);
        }
        var end = packet + PACKET;
        if (position >= end) {
            return List.of();
        }
        position += 1 + (data[position] & 0xFF);
        if (position + 8 > end || data[position] != 0) {
            return List.of();
        }
        var sectionEnd = position + 3 + (((data[position + 1] & 0x0F) << 8) | (data[position + 2] & 0xFF)) - 4;
        var pids = new ArrayList<Integer>();
        for (var entry = position + 8; entry + 4 <= Math.min(sectionEnd, end); entry += 4) {
            var number = (data[entry] & 0xFF) << 8 | data[entry + 1] & 0xFF;
            if (number != 0) {
                pids.add((data[entry + 2] & 0x1F) << 8 | data[entry + 3] & 0xFF);
            }
        }
        return pids;
    }

    /**
     * Returns the options of the demuxer of one range.
     *
     * @param index the index of the range
     * @return the limits and the range's {@code SAMPLE-AES} key
     */
    private TsDemuxerOptions demuxerOptions(int index) {
        var key = keys[index];
        return TsDemuxerOptions.builder().limits(options.limits())
                .sampleAesKeys(key == null ? SampleAesKeys.none() : SampleAesKeys.of(key)).build();
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
            try (var source = source(index); var demuxer = new TsDemuxer(source, demuxerOptions(index))) {
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
                    // the range holds one I-frame (RFC 8216 clause 4.3.3.6); a segmenter may end it after the
                    // packet that starts the next frame, which is not part of it
                    break;
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
