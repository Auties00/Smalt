package com.github.auties00.smalt.metadata;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.container.MuxSupport;
import com.github.auties00.smalt.container.wav.WavDemuxer;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Edits the tags of the WAV variants: big-endian RIFX files, and RIFF files that an edit takes past the 4 GiB a
 * RIFF size describes, which become RF64 files (EBU Tech 3306). The files past 4 GiB are sparse sources, zeros
 * everywhere but the headers and a few marked samples, written to a sink that keeps only nonzero bytes.
 */
@DisplayName("WAV variants")
class RiffVariantsTest {
    @Nested
    @DisplayName("RIFX")
    class Rifx {
        @Test
        @DisplayName("writes big-endian INFO and ID3 chunks the demuxer reads and keeps the samples")
        void tags(@TempDir Path directory) throws Exception {
            var rifx = toRifx(Fixtures.read(TagFixtures.audio("wav", List.of("-c:a", "pcm_s16le", "-bitexact"))));
            var file = directory.resolve("big.wav");
            Files.write(file, rifx);
            var before = MuxSupport.demux(rifx);
            assertEquals(Codec.PCM_S16BE, before.tracks().getFirst().codec());
            var same = Map.of(before.tracks().getFirst().id(), before.tracks().getFirst().id());
            var tags = MediaTags.builder().set(StandardTag.TITLE, "Big Endian").set(StandardTag.ARTIST, "Artist")
                    .set(StandardTag.CONDUCTOR, "Conductor")
                    .addPicture(TagFixtures.picture(com.github.auties00.smalt.container.AttachedPicture.Type.FRONT_COVER,
                            "front"))
                    .build();
            TagEditor.write(file, tags);
            var bytes = Files.readAllBytes(file);
            assertEquals("RIFX", new String(bytes, 0, 4, StandardCharsets.US_ASCII));
            var chunks = chunks(bytes, ByteOrder.BIG_ENDIAN);
            assertTrue(chunks.containsKey("LIST") && chunks.containsKey("id3 ") && chunks.containsKey("data"), chunks.toString());
            var info = infoEntries(bytes, chunks.get("LIST"), ByteOrder.BIG_ENDIAN);
            assertEquals("Big Endian", info.get("INAM"));
            assertEquals("Artist", info.get("IART"));
            var after = MuxSupport.demux(bytes);
            assertEquals("Big Endian", after.metadata().get("INAM"));
            MuxSupport.assertSamePayloads(before, after, same);
            var read = TagEditor.read(file);
            assertEquals("Big Endian", read.get(StandardTag.TITLE).orElseThrow());
            assertEquals("Conductor", read.get(StandardTag.CONDUCTOR).orElseThrow());
            assertEquals(1, read.pictures().size());
            assertEquals(TagWriteResult.IN_PLACE, TagEditor.write(file, read.toBuilder().set(StandardTag.TITLE, "B")
                    .build()));
            assertEquals("B", MuxSupport.demux(Files.readAllBytes(file)).metadata().get("INAM"));
            assertEquals(TagWriteResult.REWRITTEN, TagEditor.write(file, read.toBuilder()
                    .set(StandardTag.COMMENT, "c".repeat(20_000)).build(), TagWriteOptions.builder().inPlace(false).build()));
            var rewritten = Files.readAllBytes(file);
            chunks(rewritten, ByteOrder.BIG_ENDIAN);
            MuxSupport.assertSamePayloads(before, MuxSupport.demux(rewritten), same);
            assertEquals("c".repeat(20_000), TagEditor.read(file).get(StandardTag.COMMENT).orElseThrow());
        }

        @Test
        @DisplayName("rejects a RIFX file an edit would take past 4 GiB")
        void tooLarge() {
            var source = sparseWav(true, false, 0xFFFFF000L);
            var tags = MediaTags.builder().set(StandardTag.COMMENT, "c".repeat(8192)).build();
            assertThrows(MediaException.LimitExceeded.class, () -> TagEditor.write(source.toSource(), new SparseSink(),
                    tags, TagWriteOptions.defaults()));
        }
    }

    @Nested
    @DisplayName("RF64 promotion")
    class Promotion {
        @ParameterizedTest(name = "placeholder {0}")
        @ValueSource(booleans = {true, false})
        @DisplayName("turns a RIFF file the tags take past 4 GiB into an RF64 file with a ds64 chunk")
        void promotes(boolean placeholder) {
            var dataSize = 0xFFFFF000L;
            var input = sparseWav(false, placeholder, dataSize);
            var source = input.toSource();
            var tags = MediaTags.builder().set(StandardTag.TITLE, "Large").set(StandardTag.COMMENT, "c".repeat(8192))
                    .build();
            var sink = new SparseSink();
            TagEditor.write(source, sink, tags, TagWriteOptions.defaults());
            var output = sink.toSource();
            var head = read(output, 0, 12 + 36 + 24 + 8);
            assertEquals("RF64", new String(head, 0, 4, StandardCharsets.US_ASCII));
            assertEquals(0xFFFFFFFFL, u32le(head, 4));
            assertEquals("ds64", new String(head, 12, 4, StandardCharsets.US_ASCII));
            assertEquals(28, u32le(head, 16));
            assertEquals(output.size() - 8, u64le(head, 20));
            assertEquals(dataSize, u64le(head, 28));
            assertEquals(dataSize / 4, u64le(head, 36));
            assertEquals(0, u32le(head, 44));
            assertEquals("fmt ", new String(head, 48, 4, StandardCharsets.US_ASCII));
            var dataHeader = find(output, "data");
            assertEquals(0xFFFFFFFFL, u32le(read(output, dataHeader, 8), 4));
            for (var marker : input.markers().entrySet()) {
                var moved = marker.getKey() - input.dataStart() + dataHeader + 8;
                assertArrayEquals(marker.getValue(), read(output, moved, marker.getValue().length));
            }
            var read = TagEditor.read(output);
            assertEquals("Large", read.get(StandardTag.TITLE).orElseThrow());
            assertEquals("c".repeat(8192), read.get(StandardTag.COMMENT).orElseThrow());
            try (var demuxer = new WavDemuxer(output)) {
                var track = (AudioTrack) demuxer.tracks().getFirst();
                assertEquals(dataSize / 4, track.duration());
                assertEquals("Large", demuxer.metadata().get("INAM"));
                var first = demuxer.read();
                assertArrayEquals(input.markers().get(input.dataStart()), Arrays.copyOf(first.data(), 8));
            }
        }

        @Test
        @DisplayName("keeps a RIFF file that stays under 4 GiB a RIFF file")
        void staysRiff() {
            var input = sparseWav(false, true, 0xFFF00000L);
            var sink = new SparseSink();
            TagEditor.write(input.toSource(), sink, MediaTags.builder().set(StandardTag.TITLE, "Small").build(),
                    TagWriteOptions.defaults());
            var output = sink.toSource();
            var head = read(output, 0, 12);
            assertEquals("RIFF", new String(head, 0, 4, StandardCharsets.US_ASCII));
            assertEquals(output.size() - 8, u32le(head, 4));
            assertEquals("Small", TagEditor.read(output).get(StandardTag.TITLE).orElseThrow());
        }

        @Test
        @DisplayName("edits the tags of an RF64 file ffmpeg wrote, which ffprobe reads back")
        void rf64(@TempDir Path directory) {
            var file = TagFixtures.copy(TagFixtures.audio("wav", List.of("-c:a", "pcm_s16le", "-rf64", "always")),
                    directory);
            var before = TagFixtures.packetHash(file);
            TagEditor.write(file, MediaTags.builder().set(StandardTag.TITLE, "Sixty Four").set(StandardTag.LYRICIST, "L")
                    .build());
            assertEquals(before, TagFixtures.packetHash(file));
            assertEquals("Sixty Four", TagFixtures.ffprobeTags(file).get("title"));
            assertEquals("L", TagEditor.read(file).get(StandardTag.LYRICIST).orElseThrow());
        }
    }

    /** A sparse WAV file: its segments and the positions of the marked samples. */
    record SparseWav(TreeMap<Long, byte[]> segments, long size, long dataStart, Map<Long, byte[]> markers) {
        SparseSource toSource() {
            return new SparseSource(segments, size);
        }
    }

    /**
     * Builds a sparse 16-bit stereo PCM WAV or RIFX file: header, an optional 28-byte JUNK placeholder, fmt, and a
     * data chunk of zeros with three marked samples.
     */
    static SparseWav sparseWav(boolean bigEndian, boolean placeholder, long dataSize) {
        var order = bigEndian ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN;
        var header = ByteBuffer.allocate(12 + (placeholder ? 36 : 0) + 24 + 8).order(order);
        header.put((bigEndian ? "RIFX" : "RIFF").getBytes(StandardCharsets.US_ASCII));
        var total = header.capacity() + dataSize;
        header.putInt((int) (total - 8));
        header.put("WAVE".getBytes(StandardCharsets.US_ASCII));
        if (placeholder) {
            header.put("JUNK".getBytes(StandardCharsets.US_ASCII)).putInt(28).put(new byte[28]);
        }
        header.put("fmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16);
        header.putShort((short) 1).putShort((short) 2).putInt(48000).putInt(48000 * 4).putShort((short) 4)
                .putShort((short) 16);
        header.put("data".getBytes(StandardCharsets.US_ASCII)).putInt((int) dataSize);
        var segments = new TreeMap<Long, byte[]>();
        segments.put(0L, header.array());
        var dataStart = (long) header.capacity();
        var markers = new java.util.LinkedHashMap<Long, byte[]>();
        for (var offset : new long[]{0, dataSize / 2 & ~3L, dataSize - 8}) {
            var marker = new byte[]{1, 2, 3, 4, 5, 6, 7, (byte) (offset & 0x7F | 1)};
            markers.put(dataStart + offset, marker);
            segments.put(dataStart + offset, marker);
        }
        return new SparseWav(segments, total, dataStart, markers);
    }

    /** Converts a 16-bit little-endian PCM WAV file to a RIFX file. */
    static byte[] toRifx(byte[] wav) {
        var out = ByteBuffer.allocate(wav.length).order(ByteOrder.BIG_ENDIAN);
        out.put("RIFX".getBytes(StandardCharsets.US_ASCII)).putInt(0).put("WAVE".getBytes(StandardCharsets.US_ASCII));
        var in = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN);
        var position = 12;
        while (position + 8 <= wav.length) {
            var id = new String(wav, position, 4, StandardCharsets.US_ASCII);
            var size = in.getInt(position + 4);
            var body = Arrays.copyOfRange(wav, position + 8, position + 8 + size);
            if (id.equals("fmt ")) {
                var fields = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN);
                var swapped = ByteBuffer.allocate(body.length).order(ByteOrder.BIG_ENDIAN);
                swapped.putShort(fields.getShort(0)).putShort(fields.getShort(2)).putInt(fields.getInt(4))
                        .putInt(fields.getInt(8)).putShort(fields.getShort(12)).putShort(fields.getShort(14));
                swapped.put(Arrays.copyOfRange(body, 16, body.length));
                body = swapped.array();
            } else if (id.equals("data")) {
                for (var index = 0; index + 1 < body.length; index += 2) {
                    var low = body[index];
                    body[index] = body[index + 1];
                    body[index + 1] = low;
                }
            } else if (!id.equals("LIST")) {
                position += 8 + size + (size & 1);
                continue;
            } else {
                var list = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN);
                for (var at = 4; at + 8 <= body.length; ) {
                    var entry = list.getInt(at + 4);
                    ByteBuffer.wrap(body).order(ByteOrder.BIG_ENDIAN).putInt(at + 4, entry);
                    at += 8 + entry + (entry & 1);
                }
            }
            out.put(id.getBytes(StandardCharsets.US_ASCII)).putInt(body.length).put(body);
            if ((body.length & 1) != 0) {
                out.put((byte) 0);
            }
            position += 8 + size + (size & 1);
        }
        out.putInt(4, out.position() - 8);
        return Arrays.copyOf(out.array(), out.position());
    }

    /** Walks the chunks of a RIFF or RIFX file, checking that their sizes reach exactly the end of the file. */
    static Map<String, Integer> chunks(byte[] file, ByteOrder order) {
        var buffer = ByteBuffer.wrap(file).order(order);
        assertEquals(file.length - 8, Integer.toUnsignedLong(buffer.getInt(4)), "RIFF size");
        var result = new java.util.LinkedHashMap<String, Integer>();
        var position = 12;
        while (position + 8 <= file.length) {
            var size = buffer.getInt(position + 4);
            result.putIfAbsent(new String(file, position, 4, StandardCharsets.US_ASCII), position);
            position += 8 + size + (size & 1);
        }
        assertEquals(file.length, position, "the chunk sizes reach the end of the file");
        return result;
    }

    /** Decodes the entries of the LIST INFO chunk at an offset. */
    static Map<String, String> infoEntries(byte[] file, int list, ByteOrder order) {
        var buffer = ByteBuffer.wrap(file).order(order);
        var end = list + 8 + buffer.getInt(list + 4);
        var result = new java.util.HashMap<String, String>();
        for (var at = list + 12; at + 8 <= end; ) {
            var size = buffer.getInt(at + 4);
            var text = new String(file, at + 8, size, StandardCharsets.UTF_8);
            result.put(new String(file, at, 4, StandardCharsets.US_ASCII), text.substring(0, text.indexOf('\0')));
            at += 8 + size + (size & 1);
        }
        assertEquals(end, list + 8 + buffer.getInt(list + 4));
        return result;
    }

    static byte[] read(MediaSource source, long position, int length) {
        var bytes = new byte[length];
        source.readFully(position, bytes, 0, length);
        return bytes;
    }

    /** Finds the offset of the first top-level chunk of an identifier. */
    static long find(MediaSource source, String id) {
        var position = 12L;
        while (position + 8 <= source.size()) {
            var header = read(source, position, 8);
            if (new String(header, 0, 4, StandardCharsets.US_ASCII).equals(id)) {
                return position;
            }
            var size = u32le(header, 4);
            if (size == 0xFFFFFFFFL) {
                throw new AssertionError("no " + id + " chunk before an unsized chunk");
            }
            position += 8 + size + (size & 1);
        }
        throw new AssertionError("no " + id + " chunk");
    }

    static long u32le(byte[] data, int offset) {
        return Integer.toUnsignedLong(ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).getInt(offset));
    }

    static long u64le(byte[] data, int offset) {
        return ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).getLong(offset);
    }

    /** A source of zeros overlaid with segments. */
    static final class SparseSource implements MediaSource {
        private final TreeMap<Long, byte[]> segments;
        private final long size;

        SparseSource(TreeMap<Long, byte[]> segments, long size) {
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
}
