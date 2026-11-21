package com.github.auties00.smalt.image.internal.zstd;

import com.github.auties00.smalt.image.internal.CompressionSamples;
import com.github.auties00.smalt.testkit.Oracle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;
import java.util.zip.DataFormatException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks {@link ZstdDecoder} against frames written by the zstd tool (tools/testdata/zstd/make-vectors.py, pinned by
 * the SHA-256 of their decoded content), the golden decompression tests of zstd 1.5.7, and, when zstd is on
 * {@code PATH}, frames it writes during the test.
 */
@DisplayName("Zstandard decoder")
class ZstdDecoderTest {
    static final Path DIRECTORY = Path.of("src", "test", "resources", "com", "github", "auties00", "smalt", "image",
            "internal", "zstd");

    record Vector(String name, int size, String sha256, byte[] frame) {
        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<Vector> frames() {
        return read(DIRECTORY.resolve("frames"));
    }

    static Stream<Vector> golden() {
        return read(DIRECTORY.resolve("golden"));
    }

    static Stream<Vector> read(Path directory) {
        try {
            return Files.readAllLines(directory.resolve(directory.getFileName() + ".tsv")).stream()
                    .filter(line -> !line.startsWith("#") && !line.isBlank())
                    .map(line -> line.split("\t"))
                    .map(parts -> new Vector(parts[0], Integer.parseInt(parts[1]), parts[2], bytes(directory.resolve(parts[0]))));
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    static byte[] bytes(Path path) {
        try {
            return Files.readAllBytes(path);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    static String sha256(byte[] data, int length) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            digest.update(data, 0, length);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }

    static byte[] decode(byte[] frame, int capacity) throws DataFormatException {
        var out = new byte[capacity];
        var produced = new ZstdDecoder().decompress(frame, 0, frame.length, out, 0, capacity);
        return Arrays.copyOf(out, produced);
    }

    @Test
    @DisplayName("the frames and golden files are the pinned ones")
    void pinned() {
        CompressionSamples.assertPinned(DIRECTORY.resolve("zstd.sha256"), DIRECTORY.resolve("frames"));
        CompressionSamples.assertPinned(DIRECTORY.resolve("golden.sha256"), DIRECTORY.resolve("golden"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("frames")
    @DisplayName("frames of the zstd tool decode to their pinned content")
    void frame(Vector vector) throws DataFormatException {
        var decoded = decode(vector.frame(), vector.size() + 100);
        assertEquals(vector.size(), decoded.length);
        assertEquals(vector.sha256(), sha256(decoded, decoded.length));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("golden")
    @DisplayName("the golden decompression tests of zstd decode to the content zstd gives")
    void goldenFrame(Vector vector) throws DataFormatException {
        var decoded = decode(vector.frame(), vector.size());
        assertEquals(vector.size(), decoded.length);
        assertEquals(vector.sha256(), sha256(decoded, decoded.length));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("names")
    @DisplayName("the golden decompression error tests of zstd are rejected")
    void goldenError(String name) {
        var frame = bytes(DIRECTORY.resolve("golden").resolve(name));
        assertThrows(DataFormatException.class, () -> decode(frame, 1 << 20));
    }

    static List<String> names() {
        return List.of("error-off0.bin.zst", "error-truncated_huff_state.zst", "error-zeroSeq_extraneous.zst");
    }

    @Test
    @DisplayName("a frame whose content checksum does not match is rejected after producing its content")
    void badChecksum() {
        var frame = bytes(DIRECTORY.resolve("frames").resolve("bad-checksum.zst"));
        var decoder = new ZstdDecoder();
        var out = new byte[10000];
        assertThrows(DataFormatException.class, () -> decoder.decompress(frame, 0, frame.length, out, 0, out.length));
        assertEquals(5000, decoder.produced());
    }

    @Test
    @DisplayName("an input that ends early is reported as truncated, invalid data is not")
    void truncation() {
        var frame = bytes(DIRECTORY.resolve("frames").resolve("text-l3.zst"));
        var decoder = new ZstdDecoder();
        var out = new byte[1 << 20];
        assertThrows(DataFormatException.class, () -> decoder.decompress(frame, 0, frame.length / 2, out, 0, out.length));
        assertTrue(decoder.truncated());
        var corrupt = frame.clone();
        corrupt[4] |= 0x08;
        assertThrows(DataFormatException.class, () -> decoder.decompress(corrupt, 0, corrupt.length, out, 0, out.length));
        assertFalse(decoder.truncated());
    }

    @Test
    @DisplayName("a frame needing a dictionary is rejected")
    void dictionary() {
        // a single-segment frame with a 1-byte dictionary id and a 1-byte content size
        var frame = new byte[]{0x28, (byte) 0xB5, 0x2F, (byte) 0xFD, 0x21, 0x07, 0x01, 0x09, 0x00, 0x00, 0x41};
        var failure = assertThrows(DataFormatException.class, () -> decode(frame, 16));
        assertTrue(failure.getMessage().contains("dictionary"), failure.getMessage());
    }

    @Nested
    @DisplayName("bounded output")
    class Bounded {
        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.image.internal.zstd.ZstdDecoderTest#frames")
        @DisplayName("an output smaller than the content receives its prefix")
        void prefix(Vector vector) throws DataFormatException {
            var full = decode(vector.frame(), vector.size());
            for (var size : new int[]{0, 1, vector.size() / 3, vector.size() / 2, vector.size() - 1}) {
                if (size < 0 || size > vector.size()) {
                    continue;
                }
                var part = decode(vector.frame(), size);
                assertArrayEquals(Arrays.copyOf(full, size), part, "output of " + size + " bytes");
            }
        }

        @Test
        @DisplayName("the output range offset is honored and bytes around it are untouched")
        void range() throws DataFormatException {
            var vector = frames().filter(v -> v.name().equals("text-l3.zst")).findFirst().orElseThrow();
            var out = new byte[vector.size() + 20];
            Arrays.fill(out, (byte) 0x5A);
            var produced = new ZstdDecoder().decompress(vector.frame(), 0, vector.frame().length, out, 10, vector.size());
            assertEquals(vector.size(), produced);
            assertEquals(vector.sha256(), sha256(Arrays.copyOfRange(out, 10, 10 + produced), produced));
            for (var i = 0; i < 10; i++) {
                assertEquals(0x5A, out[i]);
                assertEquals(0x5A, out[out.length - 1 - i]);
            }
        }
    }

    @Nested
    @DisplayName("damaged input")
    class Damaged {
        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.image.internal.zstd.ZstdDecoderTest#frames")
        @DisplayName("every truncation fails cleanly after producing a prefix of the content")
        void truncated(Vector vector) throws DataFormatException {
            var full = decode(vector.frame(), vector.size());
            var frame = vector.frame();
            var step = Math.max(1, frame.length / 97);
            var decoder = new ZstdDecoder();
            var out = new byte[vector.size()];
            for (var length = 0; length < frame.length; length += step) {
                Arrays.fill(out, (byte) 0);
                int produced;
                try {
                    produced = decoder.decompress(frame, 0, length, out, 0, out.length);
                } catch (DataFormatException expected) {
                    produced = decoder.produced();
                }
                assertArrayEquals(Arrays.copyOf(full, produced), Arrays.copyOf(out, produced),
                        "prefix of a frame cut to " + length + " bytes");
            }
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.image.internal.zstd.ZstdDecoderTest#frames")
        @DisplayName("random byte changes fail cleanly or decode within the output")
        void mutated(Vector vector) {
            var random = new Random(vector.name().hashCode());
            var decoder = new ZstdDecoder();
            var out = new byte[Math.max(16, vector.size())];
            for (var round = 0; round < 40 && vector.frame().length > 0; round++) {
                var frame = vector.frame().clone();
                for (var flips = 1 + random.nextInt(4); flips > 0; flips--) {
                    frame[random.nextInt(frame.length)] ^= (byte) (1 + random.nextInt(255));
                }
                try {
                    var produced = decoder.decompress(frame, 0, frame.length, out, 0, out.length);
                    assertTrue(produced <= out.length);
                } catch (DataFormatException expected) {
                    assertTrue(decoder.produced() <= out.length);
                }
            }
        }
    }

    @Nested
    @DisplayName("zstd oracle")
    class ZstdOracle {
        @Test
        @DisplayName("random inputs compressed by zstd at every level decode exactly")
        void levels() throws DataFormatException {
            Oracle.assume("zstd");
            var random = new Random(8878);
            for (var level = -5; level <= 19; level++) {
                var input = CompressionSamples.sample(random, 1 + random.nextInt(300000));
                var arguments = level < 0 ? List.of("-q", "-c", "--fast=" + -level) : List.of("-q", "-c", "-" + Math.max(1, level));
                var frame = Oracle.run("zstd", Duration.ofMinutes(1), input, arguments);
                assertArrayEquals(input, decode(frame, input.length), "level " + level);
            }
        }
    }
}
