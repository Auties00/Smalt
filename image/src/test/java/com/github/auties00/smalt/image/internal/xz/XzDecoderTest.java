package com.github.auties00.smalt.image.internal.xz;

import com.github.auties00.smalt.image.internal.CompressionSamples;
import com.github.auties00.smalt.testkit.Oracle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
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
 * Checks {@link XzDecoder} against files written by the xz tool (tools/testdata/xz/make-vectors.py, pinned by the
 * SHA-256 of their decoded content) and, when xz is on {@code PATH}, files it writes during the test.
 */
@DisplayName("XZ decoder")
class XzDecoderTest {
    static final Path DIRECTORY = Path.of("src", "test", "resources", "com", "github", "auties00", "smalt", "image",
            "internal", "xz", "files");

    record Vector(String name, int size, String sha256, byte[] data) {
        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<Vector> files() {
        try {
            return Files.readAllLines(DIRECTORY.resolve("files.tsv")).stream()
                    .filter(line -> !line.startsWith("#") && !line.isBlank())
                    .map(line -> line.split("\t"))
                    .map(parts -> new Vector(parts[0], Integer.parseInt(parts[1]), parts[2], bytes(DIRECTORY.resolve(parts[0]))));
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

    static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }

    static byte[] decode(byte[] data, int capacity) throws DataFormatException {
        var out = new byte[capacity];
        var produced = new XzDecoder().decompress(data, 0, data.length, out, 0, capacity);
        return Arrays.copyOf(out, produced);
    }

    @Test
    @DisplayName("the files are the pinned ones")
    void pinned() {
        CompressionSamples.assertPinned(DIRECTORY.getParent().resolve("xz.sha256"), DIRECTORY);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("files")
    @DisplayName("files of the xz tool decode to their pinned content")
    void file(Vector vector) throws DataFormatException {
        var decoded = decode(vector.data(), vector.size() + 64);
        assertEquals(vector.size(), decoded.length);
        assertEquals(vector.sha256(), sha256(decoded));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"damaged-bad-check.xz", "damaged-bad-header-crc.xz", "damaged-bad-footer-magic.xz"})
    @DisplayName("files with a damaged check, header or footer are rejected")
    void damaged(String name) {
        var data = bytes(DIRECTORY.resolve(name));
        assertThrows(DataFormatException.class, () -> decode(data, 1 << 20));
    }

    @Test
    @DisplayName("an input that ends early keeps the bytes before the cut and is reported as truncated")
    void truncation() throws DataFormatException {
        var vector = files().filter(v -> v.name().equals("text-l3.xz") || v.name().equals("text-check-crc64.xz"))
                .findFirst().orElseThrow();
        var full = decode(vector.data(), vector.size());
        var decoder = new XzDecoder();
        var out = new byte[vector.size()];
        assertThrows(DataFormatException.class,
                () -> decoder.decompress(vector.data(), 0, vector.data().length / 2, out, 0, out.length));
        assertTrue(decoder.truncated());
        assertTrue(decoder.produced() > vector.size() / 8, "produced " + decoder.produced());
        assertArrayEquals(Arrays.copyOf(full, decoder.produced()), Arrays.copyOf(out, decoder.produced()));
        var corrupt = vector.data().clone();
        corrupt[6] = 1;
        assertThrows(DataFormatException.class, () -> decoder.decompress(corrupt, 0, corrupt.length, out, 0, out.length));
        assertFalse(decoder.truncated());
    }

    @Test
    @DisplayName("an unknown filter and data that is neither XZ nor LZMA are rejected")
    void unsupported() throws DataFormatException {
        var good = files().filter(v -> v.name().equals("text-check-crc32.xz")).findFirst().orElseThrow().data();
        var copy = good.clone();
        // the block header starts at 12: size byte, flags, the sizes the flags announce, then the filter id
        var at = 14;
        for (var flag : new int[]{0x40, 0x80}) {
            if ((copy[13] & flag) != 0) {
                while ((copy[at] & 0x80) != 0) {
                    at++;
                }
                at++;
            }
        }
        assertEquals(0x21, copy[at]);
        copy[at] = 0x31;
        var crc = new java.util.zip.CRC32();
        var headerSize = ((copy[12] & 0xFF) + 1) * 4;
        crc.update(copy, 12, headerSize - 4);
        var value = (int) crc.getValue();
        for (var i = 0; i < 4; i++) {
            copy[12 + headerSize - 4 + i] = (byte) (value >>> (8 * i));
        }
        var failure = assertThrows(DataFormatException.class, () -> decode(copy, 1 << 20));
        assertTrue(failure.getMessage().contains("filter"), failure.getMessage());
        assertThrows(DataFormatException.class, () -> decode("plain text, not compressed".getBytes(), 100));
        assertEquals(0, decode(new byte[0], 10).length);
    }

    @Nested
    @DisplayName("bounded output")
    class Bounded {
        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.image.internal.xz.XzDecoderTest#files")
        @DisplayName("an output smaller than the content receives its prefix")
        void prefix(Vector vector) throws DataFormatException {
            var full = decode(vector.data(), vector.size());
            for (var size : new int[]{0, 1, 7, vector.size() / 3, vector.size() / 2, vector.size() - 3, vector.size() - 1}) {
                if (size < 0 || size > vector.size()) {
                    continue;
                }
                assertArrayEquals(Arrays.copyOf(full, size), decode(vector.data(), size), "output of " + size + " bytes");
            }
        }
    }

    @Nested
    @DisplayName("damaged input")
    class Damaged {
        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.image.internal.xz.XzDecoderTest#files")
        @DisplayName("every truncation fails cleanly after producing a prefix of the content")
        void truncated(Vector vector) throws DataFormatException {
            var full = decode(vector.data(), vector.size());
            var data = vector.data();
            var step = Math.max(1, data.length / 89);
            var decoder = new XzDecoder();
            var out = new byte[vector.size()];
            for (var length = 0; length < data.length; length += step) {
                Arrays.fill(out, (byte) 0);
                int produced;
                try {
                    produced = decoder.decompress(data, 0, length, out, 0, out.length);
                } catch (DataFormatException expected) {
                    produced = decoder.produced();
                }
                assertArrayEquals(Arrays.copyOf(full, produced), Arrays.copyOf(out, produced),
                        "prefix of a file cut to " + length + " bytes");
            }
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.image.internal.xz.XzDecoderTest#files")
        @DisplayName("random byte changes fail cleanly or decode within the output")
        void mutated(Vector vector) {
            var random = new Random(vector.name().hashCode());
            var decoder = new XzDecoder();
            var out = new byte[Math.max(16, vector.size())];
            for (var round = 0; round < 40; round++) {
                var data = vector.data().clone();
                for (var flips = 1 + random.nextInt(4); flips > 0; flips--) {
                    data[random.nextInt(data.length)] ^= (byte) (1 + random.nextInt(255));
                }
                try {
                    assertTrue(decoder.decompress(data, 0, data.length, out, 0, out.length) <= out.length);
                } catch (DataFormatException expected) {
                    assertTrue(decoder.produced() <= out.length);
                }
            }
        }
    }

    @Nested
    @DisplayName("xz oracle")
    class XzOracle {
        @Test
        @DisplayName("random inputs compressed by xz with random presets and filters decode exactly")
        void random() throws DataFormatException {
            Oracle.assume("xz");
            var random = new Random(34925);
            // xz before 5.6 has no RISC-V filter (code-riscv.xz covers it), and 5.2 none for ARM64
            var help = new String(Oracle.run("xz", Duration.ofMinutes(1), new byte[0], List.of("--long-help")));
            var chains = Stream.of(List.<String>of(), List.of("--delta=dist=2"), List.of("--x86"), List.of("--arm64"),
                            List.of("--riscv"), List.of("--delta=dist=7", "--sparc"), List.of("--ia64"))
                    .filter(chain -> chain.stream().allMatch(option -> help.contains(option.split("=")[0])))
                    .toList();
            for (var round = 0; round < 14; round++) {
                var input = CompressionSamples.sample(random, 1 + random.nextInt(200000));
                var arguments = new ArrayList<>(List.of("-q", "-c", "--check=" + List.of("none", "crc32", "crc64", "sha256").get(round % 4)));
                if (round % 3 == 0) {
                    arguments.add("--block-size=" + (1000 + random.nextInt(50000)));
                }
                var chain = chains.get(round % chains.size());
                arguments.addAll(chain);
                var preset = "preset=" + random.nextInt(10) + (random.nextBoolean() ? "e" : "");
                arguments.add(chain.isEmpty() ? "-" + preset.substring(7) : "--lzma2=" + preset);
                var encoded = Oracle.run("xz", Duration.ofMinutes(1), input, arguments);
                assertArrayEquals(input, decode(encoded, input.length), String.join(" ", arguments));
            }
        }
    }
}
