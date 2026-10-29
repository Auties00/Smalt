package com.github.auties00.smalt.container.mp4;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.container.ContainerProbe;
import com.github.auties00.smalt.container.DemuxerOptions;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.metadata.MediaTags;
import com.github.auties00.smalt.metadata.StandardTag;
import com.github.auties00.smalt.metadata.TagEditor;
import com.github.auties00.smalt.metadata.TagWriteOptions;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.container.DemuxerAsserts;
import com.github.auties00.smalt.testkit.io.IoVariant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the compressed boxes of ISO/IEC 14496-12 clause 8.20: a file whose movie box ({@code !mov}), movie
 * fragments ({@code !mof}) and segment indexes ({@code !six}) are replaced by their gzip-compressed forms, every
 * offset left counting in the file as it was before the compression, as the clause has them, demuxes to the same
 * packets as the original, under every I/O behavior, with base data offsets relative to the fragment or explicit;
 * its tags are read and written, the rewrite storing the movie uncompressed and moving the explicit offsets of the
 * fragments after it; damaged streams are malformed and a box that decompresses past the byte limit exceeds it.
 */
@DisplayName("MP4 compressed boxes")
class Mp4CompressedMovieTest {
    private static final Path SAMPLES = Path.of("src", "test", "resources", "samples");

    /** The compressed form of each box type. */
    private static final Map<String, String> COMPRESSED = Map.of("moov", "!mov", "moof", "!mof", "sidx", "!six",
            "ssix", "!ssx");

    private static byte[] original() {
        return Fixtures.read(Mp4Fixtures.h264Aac(true, List.of(), "mp4"));
    }

    /** A fragmented file whose track fragments state explicit base data offsets (ffmpeg's default). */
    private static byte[] explicitFragments() {
        var file = Fixtures.read(Mp4Fixtures.h264Aac(true, List.of("-movflags", "frag_keyframe+empty_moov"), "mp4"));
        assertTrue(explicitBaseOffsets(file), "the fixture states explicit base data offsets");
        return file;
    }

    private static byte[] sample(String name) {
        try {
            return Files.readAllBytes(SAMPLES.resolve(name));
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /** Returns the offset, size and type of every top-level box. */
    private static List<Object[]> boxes(byte[] file) {
        var boxes = new ArrayList<Object[]>();
        var position = 0;
        while (position + 8 <= file.length) {
            var size = ByteBuffer.wrap(file, position, 4).getInt();
            boxes.add(new Object[]{position, size, new String(file, position + 4, 4, StandardCharsets.ISO_8859_1)});
            position += size;
        }
        return boxes;
    }

    /** Returns the offset and size of the first top-level box of a type. */
    private static int[] find(byte[] file, String type) {
        for (var box : boxes(file)) {
            if (box[2].equals(type)) {
                return new int[]{(int) box[0], (int) box[1]};
            }
        }
        throw new AssertionError("no " + type + " box");
    }

    private static byte[] gzip(byte[] data, int from, int length) {
        var out = new ByteArrayOutputStream();
        try (var zip = new GZIPOutputStream(out)) {
            zip.write(data, from, length);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        return out.toByteArray();
    }

    private static byte[] box(String type, byte[] payload) {
        return ByteBuffer.allocate(8 + payload.length).putInt(8 + payload.length)
                .put(type.getBytes(StandardCharsets.ISO_8859_1)).put(payload).array();
    }

    /**
     * Replaces every top-level box of the given types by its compressed form; the offsets the file stores keep
     * counting in the original file, which is the file with those boxes uncompressed.
     */
    private static byte[] compress(byte[] file, Set<String> types) {
        var out = new ByteArrayOutputStream();
        for (var box : boxes(file)) {
            var offset = (int) box[0];
            var size = (int) box[1];
            if (types.contains((String) box[2])) {
                out.writeBytes(box(COMPRESSED.get((String) box[2]), gzip(file, offset + 8, size - 8)));
            } else {
                out.write(file, offset, size);
            }
        }
        return out.toByteArray();
    }

    /**
     * Inserts a box after the movie box of a fragmented file, moving the explicit base data offsets of its track
     * fragments past it.
     */
    private static byte[] insertAfterMovie(byte[] file, byte[] inserted) {
        var moov = find(file, "moov");
        var at = moov[0] + moov[1];
        var out = Arrays.copyOf(file, file.length + inserted.length);
        System.arraycopy(file, at, out, at + inserted.length, file.length - at);
        System.arraycopy(inserted, 0, out, at, inserted.length);
        for (var box : boxes(out)) {
            if (box[2].equals("moof")) {
                forEachTfhd(out, (int) box[0], (int) box[1], (tfhd, flags) -> {
                    if ((flags & 1) != 0) {
                        var buffer = ByteBuffer.wrap(out);
                        buffer.putLong(tfhd + 16, buffer.getLong(tfhd + 16) + inserted.length);
                    }
                });
            }
        }
        return out;
    }

    private interface TfhdVisitor {
        void visit(int tfhd, int flags);
    }

    private static void forEachTfhd(byte[] file, int moof, int size, TfhdVisitor visitor) {
        var position = moof + 8;
        while (position + 8 <= moof + size) {
            var child = ByteBuffer.wrap(file, position, 4).getInt();
            if (new String(file, position + 4, 4, StandardCharsets.ISO_8859_1).equals("traf")) {
                var inner = position + 8;
                while (inner + 8 <= position + child) {
                    var length = ByteBuffer.wrap(file, inner, 4).getInt();
                    if (new String(file, inner + 4, 4, StandardCharsets.ISO_8859_1).equals("tfhd")) {
                        visitor.visit(inner, ByteBuffer.wrap(file, inner + 8, 4).getInt() & 0xFFFFFF);
                    }
                    inner += length;
                }
            }
            position += child;
        }
    }

    private static boolean explicitBaseOffsets(byte[] file) {
        var found = new boolean[1];
        for (var box : boxes(file)) {
            if (box[2].equals("moof")) {
                forEachTfhd(file, (int) box[0], (int) box[1], (tfhd, flags) -> found[0] |= (flags & 1) != 0);
            }
        }
        return found[0];
    }

    private static void assertSamePackets(ContainerProbe.Demuxed expected, ContainerProbe.Demuxed actual) {
        assertEquals(expected.tracks().size(), actual.tracks().size());
        assertEquals(expected.order().size(), actual.order().size());
        assertFalse(expected.order().isEmpty());
        for (var i = 0; i < expected.order().size(); i++) {
            var want = expected.order().get(i);
            var got = actual.order().get(i);
            assertEquals(want.trackId(), got.trackId(), "packet " + i);
            assertEquals(want.dts(), got.dts(), "packet " + i);
            assertEquals(want.pts(), got.pts(), "packet " + i);
            assertEquals(want.keyframe(), got.keyframe(), "packet " + i);
            assertTrue(Arrays.equals(want.data(), got.data()), "packet " + i);
        }
    }

    private static ContainerProbe.Demuxed demux(byte[] file) {
        return ContainerProbe.demuxAll(new Mp4Demuxer(MediaSource.of(file)));
    }

    @ParameterizedTest(name = "fast start {0}")
    @ValueSource(booleans = {false, true})
    @DisplayName("a compressed movie box demuxes like the movie box it replaces")
    void samePackets(boolean fastStart) {
        var file = fastStart ? Fixtures.read(Mp4Fixtures.h264Aac(true, List.of("-movflags", "+faststart"), "mp4"))
                : original();
        var compressed = compress(file, Set.of("moov"));
        assertSamePackets(demux(file), demux(compressed));
        assertEquals(fastStart, new Mp4Demuxer(MediaSource.of(compressed)).isFastStart());
    }

    static Stream<Arguments> fragmentCases() {
        // a segment index the demuxer ignores, whose compressed form still moves the offsets after it
        var index = new byte[400];
        for (var i = 0; i < index.length; i++) {
            index[i] = (byte) (i % 7);
        }
        return Stream.of(
                Arguments.of("relative offsets, compressed fragments", (Fixture) () -> sample("mp4/fragmented.mp4"),
                        Set.of("moof")),
                Arguments.of("relative offsets, compressed movie and fragments",
                        (Fixture) () -> sample("mp4/fragmented.mp4"), Set.of("moov", "moof")),
                Arguments.of("explicit offsets, compressed fragments", (Fixture) Mp4CompressedMovieTest::explicitFragments,
                        Set.of("moof")),
                Arguments.of("explicit offsets, compressed movie", (Fixture) Mp4CompressedMovieTest::explicitFragments,
                        Set.of("moov")),
                Arguments.of("explicit offsets, compressed movie and fragments",
                        (Fixture) Mp4CompressedMovieTest::explicitFragments, Set.of("moov", "moof")),
                Arguments.of("explicit offsets, compressed movie, segment index and fragments",
                        (Fixture) () -> insertAfterMovie(explicitFragments(), box("sidx", index)),
                        Set.of("moov", "sidx", "moof")));
    }

    private interface Fixture {
        byte[] bytes();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fragmentCases")
    @DisplayName("compressed movie fragments demux like the fragments they replace, under every I/O behavior")
    void fragments(String name, Fixture fixture, Set<String> types) {
        var file = fixture.bytes();
        var compressed = compress(file, types);
        for (var type : types) {
            find(compressed, COMPRESSED.get(type));
        }
        var expected = demux(file);
        assertSamePackets(expected, demux(compressed));
        for (var variant : List.of(IoVariant.READ_ONCE, IoVariant.READ_ONCE_UNKNOWN_LENGTH, IoVariant.UNKNOWN_LENGTH)) {
            try (var source = variant.open(compressed)) {
                assertSamePackets(expected, ContainerProbe.demuxAll(new Mp4Demuxer(source)));
            }
        }
        DemuxerAsserts.of("mp4/" + name.replace(' ', '-').replace(",", ""), compressed).noDump().run();
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("behaviorCases")
    @DisplayName("compressed samples dump like their originals under every I/O behavior")
    void behaviors(String sample, Set<String> types) {
        DemuxerAsserts.of(sample, compress(sample(sample), types)).run();
    }

    static Stream<Arguments> behaviorCases() {
        return Stream.of(
                Arguments.of("mp4/faststart.mp4", Set.of("moov")),
                Arguments.of("mp4/moov-last.mp4", Set.of("moov")),
                Arguments.of("mp4/fragmented.mp4", Set.of("moov", "moof")));
    }

    @ParameterizedTest(name = "fast start {0}")
    @ValueSource(booleans = {false, true})
    @DisplayName("tags of a compressed movie are read and written, the movie stored uncompressed")
    void tags(boolean fastStart) {
        var file = fastStart ? Fixtures.read(Mp4Fixtures.h264Aac(true, List.of("-movflags", "+faststart",
                "-metadata", "artist=Smalt"), "mp4")) : Fixtures.read(Mp4Fixtures.h264Aac(true,
                List.of("-metadata", "artist=Smalt"), "mp4"));
        var compressed = compress(file, Set.of("moov"));
        assertEquals(TagEditor.read(MediaSource.of(file)), TagEditor.read(MediaSource.of(compressed)));
        assertRewritten(file, compressed);
    }

    /** Writes new tags into a compressed file and checks the movie is stored uncompressed with the same packets. */
    private static void assertRewritten(byte[] original, byte[] compressed) {
        var edited = MediaTags.builder().set(StandardTag.TITLE, "compressed").set(StandardTag.ARTIST, "Smalt").build();
        var written = TagEditor.write(compressed, edited, TagWriteOptions.builder().build());
        find(written, "moov");
        assertThrows(AssertionError.class, () -> find(written, "!mov"));
        var reread = TagEditor.read(MediaSource.of(written));
        assertEquals("compressed", reread.get(StandardTag.TITLE).orElseThrow());
        assertEquals("Smalt", reread.get(StandardTag.ARTIST).orElseThrow());
        assertSamePackets(demux(original), demux(written));
    }

    @Test
    @DisplayName("writing the tags of a compressed movie moves the explicit offsets of the fragments after it")
    void tagsMoveFragments() {
        var file = explicitFragments();
        assertRewritten(file, compress(file, Set.of("moov")));
    }

    @Test
    @DisplayName("writing tags into the free space after a compressed movie moves the explicit offsets back")
    void tagsInFreeSpace() {
        var file = insertAfterMovie(explicitFragments(), box("free", new byte[64 << 10]));
        var compressed = compress(file, Set.of("moov"));
        var written = TagEditor.write(compressed, MediaTags.builder().set(StandardTag.TITLE, "compressed").build(),
                TagWriteOptions.builder().build());
        assertEquals(compressed.length, written.length, "the movie fits the free space");
        assertSamePackets(demux(file), demux(written));
    }

    @Test
    @DisplayName("tags are written into a file with compressed fragments whose offsets are relative")
    void tagsRelativeFragments() {
        var file = sample("mp4/fragmented.mp4");
        var compressed = compress(file, Set.of("moof"));
        var written = TagEditor.write(compressed, MediaTags.builder().set(StandardTag.TITLE, "fragments").build(),
                TagWriteOptions.builder().build());
        find(written, "!mof");
        assertEquals("fragments", TagEditor.read(MediaSource.of(written)).get(StandardTag.TITLE).orElseThrow());
        assertSamePackets(demux(file), demux(written));
    }

    @Test
    @DisplayName("writing tags that would move compressed fragments with explicit offsets is unsupported")
    void tagsExplicitFragments() {
        var compressed = compress(explicitFragments(), Set.of("moof"));
        var failure = assertThrows(MediaException.Unsupported.class, () -> TagEditor.write(compressed,
                MediaTags.builder().set(StandardTag.TITLE, "x".repeat(4096)).build(), TagWriteOptions.builder().build()));
        assertEquals("mp4 compressed movie fragments", failure.format());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"moov", "moof", "sidx"})
    @DisplayName("a damaged gzip stream is malformed")
    void damaged(String type) {
        var file = type.equals("sidx") ? insertAfterMovie(explicitFragments(), box("sidx", new byte[64]))
                : type.equals("moof") ? explicitFragments() : original();
        var box = find(file, type);
        var payload = gzip(file, box[0] + 8, box[1] - 8);
        payload[0] = 0;
        var damaged = new ByteArrayOutputStream();
        damaged.write(file, 0, box[0]);
        damaged.writeBytes(box(COMPRESSED.get(type), payload));
        damaged.write(file, box[0] + box[1], file.length - box[0] - box[1]);
        var bytes = damaged.toByteArray();
        assertThrows(MediaException.Malformed.class, () -> demux(bytes));
        if (type.equals("moov")) {
            assertThrows(MediaException.Malformed.class, () -> TagEditor.read(MediaSource.of(bytes)));
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"moov", "moof"})
    @DisplayName("a box that decompresses past the byte limit exceeds it")
    void limit(String type) {
        // a quarter megabyte of zeros compresses into a few hundred bytes
        var file = type.equals("moof") ? explicitFragments() : original();
        var box = find(file, type);
        var bomb = new ByteArrayOutputStream();
        bomb.write(file, 0, box[0]);
        bomb.writeBytes(box(COMPRESSED.get(type), gzip(new byte[256 << 10], 0, 256 << 10)));
        bomb.write(file, box[0] + box[1], file.length - box[0] - box[1]);
        var bytes = bomb.toByteArray();
        var options = DemuxerOptions.builder().limits(MediaLimits.builder().maxBytes(64 << 10).build()).build();
        assertThrows(MediaException.LimitExceeded.class, () -> ContainerProbe.demuxAll(
                new Mp4Demuxer(MediaSource.of(bytes), options)));
    }

    @Test
    @DisplayName("a compressed segment index that runs past the end of the file is ignored")
    void truncatedIndex() {
        var file = sample("mp4/fragmented.mp4");
        var compressed = compress(file, Set.of("moof"));
        var tail = box("!six", gzip(new byte[32], 0, 32));
        var truncated = Arrays.copyOf(compressed, compressed.length + tail.length - 4);
        System.arraycopy(tail, 0, truncated, compressed.length, tail.length - 4);
        assertSamePackets(demux(file), demux(truncated));
    }
}
