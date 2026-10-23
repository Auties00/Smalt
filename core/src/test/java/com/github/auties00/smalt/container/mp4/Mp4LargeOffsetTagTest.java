package com.github.auties00.smalt.container.mp4;

import com.github.auties00.smalt.container.Chapter;
import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.metadata.MediaTags;
import com.github.auties00.smalt.metadata.StandardTag;
import com.github.auties00.smalt.metadata.TagEditor;
import com.github.auties00.smalt.metadata.TagWriteOptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static com.github.auties00.smalt.container.mp4.Mp4Builder.avc1;
import static com.github.auties00.smalt.container.mp4.Mp4Builder.box;
import static com.github.auties00.smalt.container.mp4.Mp4Builder.co64;
import static com.github.auties00.smalt.container.mp4.Mp4Builder.concat;
import static com.github.auties00.smalt.container.mp4.Mp4Builder.ftyp;
import static com.github.auties00.smalt.container.mp4.Mp4Builder.fourCc;
import static com.github.auties00.smalt.container.mp4.Mp4Builder.mvhd;
import static com.github.auties00.smalt.container.mp4.Mp4Builder.table;
import static com.github.auties00.smalt.container.mp4.Mp4Builder.trak;
import static com.github.auties00.smalt.container.mp4.Mp4Builder.u32;
import static com.github.auties00.smalt.container.mp4.Mp4Builder.u64;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Edits the tags of MP4 files of more than 4 GiB held as sparse sources (zeros everywhere but the boxes and the
 * samples), checking that chunk offsets a shift takes past 32 bits turn {@code stco} into {@code co64}, that a
 * chapter track placed past 4 GiB gets a 64-bit offset, and that every sample is still found at its new offset.
 */
@DisplayName("MP4 tag edits past 4 GiB")
class Mp4LargeOffsetTagTest {
    private static final int SAMPLE = 64;

    /** A source of zeros overlaid with segments. */
    static final class SparseSource implements MediaSource {
        private final TreeMap<Long, byte[]> segments;
        private final long size;

        SparseSource(Map<Long, byte[]> segments, long size) {
            this.segments = new TreeMap<>(segments);
            this.size = size;
        }

        @Override
        public long size() {
            return size;
        }

        @Override
        public int read(long position, byte[] buffer, int offset, int length) {
            if (length == 0) {
                return 0;
            }
            if (position >= size) {
                return -1;
            }
            var count = (int) Math.min(length, size - position);
            Arrays.fill(buffer, offset, offset + count, (byte) 0);
            var floor = segments.floorKey(position);
            var from = floor != null ? floor : position;
            for (var entry : segments.subMap(from, true, position + count, false).entrySet()) {
                var start = entry.getKey();
                var data = entry.getValue();
                var overlapStart = Math.max(start, position);
                var overlapEnd = Math.min(start + data.length, position + count);
                if (overlapStart < overlapEnd) {
                    System.arraycopy(data, (int) (overlapStart - start), buffer, offset + (int) (overlapStart - position),
                            (int) (overlapEnd - overlapStart));
                }
            }
            return count;
        }

        byte[] bytes(long position, int length) {
            var out = new byte[length];
            readFully(position, out, 0, length);
            return out;
        }

        @Override
        public void close() {
        }
    }

    /** A seekable sink that keeps only the nonzero bytes it receives. */
    static final class SparseSink implements MediaSink {
        private final TreeMap<Long, byte[]> segments = new TreeMap<>();
        private long position;
        private long size;

        @Override
        public long position() {
            return position;
        }

        @Override
        public void position(long position) {
            this.position = position;
        }

        @Override
        public boolean isSeekable() {
            return true;
        }

        @Override
        public void write(ByteBuffer data) {
            var bytes = new byte[data.remaining()];
            data.get(bytes);
            var first = 0;
            while (first < bytes.length && bytes[first] == 0) {
                first++;
            }
            var last = bytes.length;
            while (last > first && bytes[last - 1] == 0) {
                last--;
            }
            if (first < last) {
                segments.put(position + first, Arrays.copyOfRange(bytes, first, last));
            }
            position += bytes.length;
            size = Math.max(size, position);
        }

        SparseSource toSource() {
            return new SparseSource(segments, size);
        }

        @Override
        public void close() {
        }
    }

    record Box(String type, long offset, long size, int header) {
    }

    static List<Box> topLevel(SparseSource source) {
        var result = new ArrayList<Box>();
        var position = 0L;
        while (source.size() - position >= 8) {
            var header = source.bytes(position, (int) Math.min(16, source.size() - position));
            var size = read32(header, 0);
            var length = 8;
            if (size == 1) {
                size = (read32(header, 8) << 32) | read32(header, 12);
                length = 16;
            }
            result.add(new Box(new String(header, 4, 4, StandardCharsets.ISO_8859_1), position, size, length));
            position += size;
        }
        return result;
    }

    static long read32(byte[] data, int offset) {
        return ((data[offset] & 0xFFL) << 24) | ((data[offset + 1] & 0xFF) << 16) | ((data[offset + 2] & 0xFF) << 8)
                | (data[offset + 3] & 0xFF);
    }

    /** Finds the boxes of a type anywhere below the containers a {@code moov} holds. */
    static void find(byte[] data, int start, int end, String type, List<int[]> found) {
        var position = start;
        while (end - position >= 8) {
            var size = (int) read32(data, position);
            if (size < 8 || size > end - position) {
                return;
            }
            var name = new String(data, position + 4, 4, StandardCharsets.ISO_8859_1);
            if (name.equals(type)) {
                found.add(new int[]{position, size});
            }
            if (List.of("trak", "mdia", "minf", "stbl", "udta").contains(name)) {
                find(data, position + 8, position + size, type, found);
            }
            position += size;
        }
    }

    static List<int[]> find(byte[] moov, String type) {
        var found = new ArrayList<int[]>();
        find(moov, 8, moov.length, type, found);
        return found;
    }

    static List<Long> offsets(byte[] moov, int[] table) {
        var wide = new String(moov, table[0] + 4, 4, StandardCharsets.ISO_8859_1).equals("co64");
        var count = (int) read32(moov, table[0] + 12);
        var result = new ArrayList<Long>();
        for (var index = 0; index < count; index++) {
            var at = table[0] + 16 + index * (wide ? 8 : 4);
            result.add(wide ? (read32(moov, at) << 32) | read32(moov, at + 4) : read32(moov, at));
        }
        return result;
    }

    static byte[] sample(int index) {
        var data = new byte[SAMPLE];
        for (var at = 0; at < SAMPLE; at++) {
            data[at] = (byte) (index * 31 + at + 1);
        }
        return data;
    }

    static byte[] moovOf(SparseSource source) {
        var moov = topLevel(source).stream().filter(box -> box.type().equals("moov")).findFirst().orElseThrow();
        return source.bytes(moov.offset(), (int) moov.size());
    }

    static void assertSamples(SparseSource output, int count) {
        try (var demuxer = Containers.openDemuxer(output)) {
            var video = new ArrayList<byte[]>();
            for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                if (packet.trackId() == 1) {
                    video.add(packet.data());
                }
            }
            assertEquals(count, video.size());
            for (var index = 0; index < count; index++) {
                assertArrayEquals(sample(index), video.get(index), "sample " + index);
            }
        }
    }

    @Test
    @DisplayName("promotes stco to co64 when a growing moov pushes chunks past 4 GiB")
    void promotes() {
        var probe = concat(ftyp(), movie(table("stco", 0, 1, 0, 0, 0)));
        var mdatStart = (long) probe.length;
        long[] chunks = {mdatStart + 8, 0xFFFFF000L, 0xFFFFFF40L};
        var moovBox = movie(table("stco", 0, 1, chunks));
        var header = concat(ftyp(), moovBox);
        assertEquals(mdatStart, header.length);
        var end = chunks[2] + SAMPLE;
        var segments = new TreeMap<Long, byte[]>();
        segments.put(0L, concat(header, u32(end - mdatStart), fourCc("mdat"), sample(0)));
        segments.put(chunks[1], sample(1));
        segments.put(chunks[2], sample(2));
        var source = new SparseSource(segments, end);
        assertSamples(source, 3);

        var sink = new SparseSink();
        var tags = MediaTags.builder().set(StandardTag.TITLE, "Large").set(StandardTag.COMMENT, "c".repeat(20_000)).build();
        TagEditor.write(source, sink, tags, TagWriteOptions.defaults());
        var output = sink.toSource();
        var delta = output.size() - source.size();
        assertTrue(delta > 20_000, "the moov grew: " + delta);
        var moov = moovOf(output);
        assertTrue(find(moov, "stco").isEmpty(), "stco remains");
        var co64 = find(moov, "co64");
        assertEquals(1, co64.size());
        assertEquals(List.of(chunks[0] + delta, chunks[1] + delta, chunks[2] + delta), offsets(moov, co64.getFirst()));
        assertTrue(chunks[1] + delta > 0xFFFFFFFFL);
        assertSamples(output, 3);
        assertEquals("Large", TagEditor.read(output).get(StandardTag.TITLE).orElseThrow());
        assertEquals("c".repeat(20_000), TagEditor.read(output).get(StandardTag.COMMENT).orElseThrow());
    }

    @Test
    @DisplayName("keeps 32-bit offsets when the shifted chunks still fit")
    void staysNarrow() {
        var probe = concat(ftyp(), movie(table("stco", 0, 1, 0, 0)));
        var mdatStart = (long) probe.length;
        long[] chunks = {mdatStart + 8, 0xFFFF0000L};
        var header = concat(ftyp(), movie(table("stco", 0, 1, chunks)));
        var end = chunks[1] + SAMPLE;
        var segments = new TreeMap<Long, byte[]>();
        segments.put(0L, concat(header, u32(end - mdatStart), fourCc("mdat"), sample(0)));
        segments.put(chunks[1], sample(1));
        var source = new SparseSource(segments, end);
        var sink = new SparseSink();
        TagEditor.write(source, sink, MediaTags.builder().set(StandardTag.COMMENT, "c".repeat(2_000)).build(),
                TagWriteOptions.defaults());
        var output = sink.toSource();
        var delta = output.size() - source.size();
        var moov = moovOf(output);
        assertTrue(find(moov, "co64").isEmpty());
        assertEquals(List.of(chunks[0] + delta, chunks[1] + delta), offsets(moov, find(moov, "stco").getFirst()));
        assertSamples(output, 2);
    }

    @Test
    @DisplayName("gives a chapter track placed past 4 GiB a 64-bit chunk offset")
    void chapterTrackPastFourGiB() {
        var mdatStart = (long) ftyp().length;
        long[] chunks = {mdatStart + 16, 0x100000000L, 0x100000100L};
        var end = chunks[2] + SAMPLE;
        var moovBox = movie(co64(chunks));
        var segments = new TreeMap<Long, byte[]>();
        segments.put(0L, concat(ftyp(), u32(1), fourCc("mdat"), u64(end - mdatStart), sample(0)));
        segments.put(chunks[1], sample(1));
        segments.put(chunks[2], concat(sample(2), moovBox));
        var source = new SparseSource(segments, end + moovBox.length);
        assertSamples(source, 3);

        var chapters = List.of(Chapter.of(0, 1_000_000, "First"), Chapter.of(1_000_000, 3_000_000, "Second"));
        var sink = new SparseSink();
        TagEditor.write(source, sink, MediaTags.builder().set(StandardTag.TITLE, "Chapters").chapters(chapters).build(),
                TagWriteOptions.defaults());
        var output = sink.toSource();
        var boxes = topLevel(output);
        var moovIndex = boxes.indexOf(boxes.stream().filter(box -> box.type().equals("moov")).findFirst().orElseThrow());
        var chapterData = boxes.get(moovIndex - 1);
        assertEquals("mdat", chapterData.type());
        assertEquals(end, chapterData.offset(), "the chapter samples replace nothing and follow the media");
        var moov = moovOf(output);
        var tables = find(moov, "co64");
        assertEquals(2, tables.size());
        assertEquals(List.of(chapterData.offset() + 8), offsets(moov, tables.get(1)));
        assertTrue(find(moov, "stco").isEmpty());
        assertSamples(output, 3);

        var chpl = find(moov, "chpl").getFirst();
        var moovOffset = boxes.get(moovIndex).offset();
        var hidden = new TreeMap<Long, byte[]>();
        hidden.put(moovOffset + chpl[0] + 4, "free".getBytes(StandardCharsets.ISO_8859_1));
        var overlaid = new TreeMap<>(sink.segments);
        overlaid.putAll(hidden);
        var trackOnly = new SparseSource(overlaid, output.size());
        assertEquals(chapters, TagEditor.read(trackOnly).chapters());
        try (var demuxer = Containers.openDemuxer(trackOnly)) {
            assertEquals(chapters, demuxer.chapters());
        }
    }

    /** A moov with one video track of 64-byte samples, one per chunk, whose chunk offsets the table holds. */
    static byte[] movie(byte[] chunkOffsets) {
        var samples = (int) read32(chunkOffsets, 12);
        return box("moov", mvhd(1000, 1000L * samples), trak(1, "vide", 1000, 1000L * samples, avc1(),
                table("stts", 0, 2, samples, 1000), table("stsc", 0, 3, 1, 1, 1), Mp4Builder.stszCount(SAMPLE, samples),
                chunkOffsets));
    }
}
