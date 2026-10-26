package com.github.auties00.smalt.container.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.container.Demuxer;
import com.github.auties00.smalt.container.DemuxerOptions;
import com.github.auties00.smalt.container.mp4.Mp4Demuxer;
import com.github.auties00.smalt.internal.io.SourceBytes;
import com.github.auties00.smalt.io.DataReferenceResolver;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.PacketFlag;
import com.github.auties00.smalt.track.PacketSideData;
import com.github.auties00.smalt.track.Track;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads the key frames an HLS I-frame playlist of fragmented MP4 segments addresses: each byte range holds a movie
 * fragment through the end of its first sample, which is the key frame. The tracks are those of the initialization
 * section; each range gives one packet, the first sample of the fragment's first track fragment, timed by its
 * {@code tfdt} and composition offset. Encrypted fragments are not decrypted.
 *
 * <p>Instances are not thread-safe.
 */
public final class FragmentKeyFrames implements Demuxer {
    /**
     * The flag of a sample that is not a sync sample.
     */
    private static final int NON_SYNC = 0x10000;

    private final List<SegmentSource.Part> ranges;
    private final DataReferenceResolver resolver;
    private final DemuxerOptions options;
    private final List<Track> tracks;

    /**
     * The index of the next range.
     */
    private int next;

    /**
     * Opens the key frames of an I-frame playlist.
     *
     * @param initialization the initialization section
     * @param ranges the byte range of each key frame, in order
     * @param resolver opens the files
     * @param options the limits
     * @throws MediaException.Malformed if the initialization section is malformed
     * @throws MediaException.Unsupported if a file cannot be resolved
     * @throws MediaException.Io if reading fails
     */
    public FragmentKeyFrames(SegmentSource.Part initialization, List<SegmentSource.Part> ranges,
                             DataReferenceResolver resolver, DemuxerOptions options) {
        this.ranges = List.copyOf(ranges);
        this.resolver = resolver;
        this.options = options;
        try (var source = new SegmentSource(List.of(initialization), resolver, options.limits());
             var demuxer = new Mp4Demuxer(source, options.toBuilder().noDataReferences().build())) {
            this.tracks = demuxer.tracks();
        }
    }

    @Override
    public ContainerFormat format() {
        return ContainerFormat.MP4;
    }

    @Override
    public List<Track> tracks() {
        return tracks;
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
     * @throws MediaException.Malformed if a range holds no movie fragment with a first sample
     */
    @Override
    public Packet read() {
        while (next < ranges.size()) {
            var range = ranges.get(next++);
            byte[] bytes;
            try (var source = new SegmentSource(List.of(range), resolver, options.limits())) {
                bytes = SourceBytes.readAll(source, options.limits(), false);
            }
            var packet = keyFrame(bytes);
            if (packet != null) {
                return packet;
            }
        }
        return null;
    }

    /**
     * Reads the first sample of the first movie fragment of a byte range.
     *
     * @param data the range
     * @return the packet, or {@code null} when its track is not in the initialization section
     * @throws MediaException.Malformed if the range holds no movie fragment with a first sample
     */
    private Packet keyFrame(byte[] data) {
        var moof = box(data, 0, data.length, "moof");
        if (moof < 0) {
            throw new MediaException.Malformed("an I-frame byte range without a movie fragment");
        }
        var moofEnd = moof + (int) u32(data, moof);
        var traf = box(data, moof + 8, moofEnd, "traf");
        if (traf < 0) {
            throw new MediaException.Malformed("an I-frame movie fragment without a track fragment");
        }
        var trafEnd = traf + (int) u32(data, traf);
        var tfhd = box(data, traf + 8, trafEnd, "tfhd");
        var tfdt = box(data, traf + 8, trafEnd, "tfdt");
        var trun = box(data, traf + 8, trafEnd, "trun");
        if (tfhd < 0 || trun < 0 || u32(data, tfhd) < 16 || u32(data, trun) < 16) {
            throw new MediaException.Malformed("an I-frame track fragment without tfhd and trun");
        }
        var tfhdFlags = (int) u32(data, tfhd + 8) & 0xFFFFFF;
        var trackId = (int) u32(data, tfhd + 12);
        var at = tfhd + 16;
        var base = (long) moof;
        if ((tfhdFlags & 0x01) != 0) {
            check(data, at, 8, tfhd);
            base = u32(data, at) << 32 | u32(data, at + 4);
            at += 8;
        }
        if ((tfhdFlags & 0x02) != 0) {
            at += 4;
        }
        var defaultDuration = 0L;
        var defaultSize = -1L;
        var defaultFlags = 0L;
        if ((tfhdFlags & 0x08) != 0) {
            check(data, at, 4, tfhd);
            defaultDuration = u32(data, at);
            at += 4;
        }
        if ((tfhdFlags & 0x10) != 0) {
            check(data, at, 4, tfhd);
            defaultSize = u32(data, at);
            at += 4;
        }
        if ((tfhdFlags & 0x20) != 0) {
            check(data, at, 4, tfhd);
            defaultFlags = u32(data, at);
        }
        var decode = 0L;
        if (tfdt >= 0) {
            var version = data[tfdt + 8];
            check(data, tfdt + 12, version == 1 ? 8 : 4, tfdt);
            decode = version == 1 ? u32(data, tfdt + 12) << 32 | u32(data, tfdt + 16) : u32(data, tfdt + 12);
        }
        var version = data[trun + 8];
        var flags = (int) u32(data, trun + 8) & 0xFFFFFF;
        at = trun + 16;
        var dataOffset = 0L;
        if ((flags & 0x01) != 0) {
            check(data, at, 4, trun);
            dataOffset = (int) u32(data, at);
            at += 4;
        }
        var sampleFlags = defaultFlags;
        if ((flags & 0x04) != 0) {
            check(data, at, 4, trun);
            sampleFlags = u32(data, at);
            at += 4;
        }
        var duration = defaultDuration;
        if ((flags & 0x100) != 0) {
            check(data, at, 4, trun);
            duration = u32(data, at);
            at += 4;
        }
        var size = defaultSize;
        if ((flags & 0x200) != 0) {
            check(data, at, 4, trun);
            size = u32(data, at);
            at += 4;
        }
        if ((flags & 0x400) != 0 && (flags & 0x04) == 0) {
            check(data, at, 4, trun);
            sampleFlags = u32(data, at);
            at += 4;
        }
        var offset = 0L;
        if ((flags & 0x800) != 0) {
            check(data, at, 4, trun);
            offset = version == 0 ? u32(data, at) : (int) u32(data, at);
        }
        var start = base == moof ? moof + dataOffset : dataOffset;
        if (size < 0 || start < 0 || start + size > data.length) {
            throw new MediaException.Malformed("an I-frame byte range that ends before its first sample");
        }
        if ((sampleFlags & NON_SYNC) != 0) {
            throw new MediaException.Malformed("an I-frame whose first sample is not a sync sample");
        }
        Track track = null;
        for (var candidate : tracks) {
            if (candidate.id() == trackId) {
                track = candidate;
            }
        }
        if (track == null) {
            return null;
        }
        var bytes = java.util.Arrays.copyOfRange(data, (int) start, (int) (start + size));
        return new Packet(trackId, bytes, decode + offset, decode, duration, Set.of(PacketFlag.KEY),
                PacketSideData.NONE);
    }

    /**
     * Rejects a field that lies past its box.
     *
     * @param data the bytes
     * @param at the offset of the field
     * @param length its length
     * @param box the offset of the box
     * @throws MediaException.Malformed if the field lies past the box
     */
    private static void check(byte[] data, int at, int length, int box) {
        if (at + length > box + u32(data, box) || at + length > data.length) {
            throw new MediaException.Malformed("an I-frame box field past its box");
        }
    }

    /**
     * Returns the offset of the first box of a type among siblings.
     *
     * @param data the bytes
     * @param from the offset of the first sibling
     * @param to the offset after the last
     * @param type the four-character type
     * @return the offset, or -1
     */
    private static int box(byte[] data, int from, int to, String type) {
        for (var at = from; at + 8 <= to; ) {
            var size = u32(data, at);
            if (size < 8 || at + size > to) {
                return -1;
            }
            if (data[at + 4] == type.charAt(0) && data[at + 5] == type.charAt(1) && data[at + 6] == type.charAt(2)
                    && data[at + 7] == type.charAt(3)) {
                return at;
            }
            at += (int) size;
        }
        return -1;
    }

    /**
     * Reads a big-endian unsigned 32-bit value.
     *
     * @param data the bytes
     * @param at the offset
     * @return the value
     */
    private static long u32(byte[] data, int at) {
        return (data[at] & 0xFFL) << 24 | (data[at + 1] & 0xFFL) << 16 | (data[at + 2] & 0xFFL) << 8
                | data[at + 3] & 0xFFL;
    }
}
