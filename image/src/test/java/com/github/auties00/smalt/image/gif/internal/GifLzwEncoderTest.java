package com.github.auties00.smalt.image.gif.internal;

import com.github.auties00.smalt.testkit.Skip;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Covers the GIF LZW encoder: decoding its output with an independent decoder, and byte identity
 * with giflib 5.2.2's {@code EGifPutLine}, pinned as SHA-256 hashes of giflib's image data for
 * deterministic inputs (generated with a small C driver over giflib's {@code egif_lib.c}). Setting
 * the {@code smalt.test.giflib} system property to that driver's executable also compares hundreds
 * of random inputs live.
 */
class GifLzwEncoderTest {
    /** Generates deterministic indices: noise, runs, a ramp or a single color, by kind. */
    static byte[] indices(int kind, int count, int bits, long seed) {
        var random = new Random(seed);
        var data = new byte[count];
        var colors = 1 << bits;
        for (var i = 0; i < count; i++) {
            data[i] = (byte) switch (kind) {
                case 0 -> random.nextInt(colors);
                case 1 -> (i / (1 + (int) Math.floorMod(seed, 17L))) % colors;
                case 2 -> (i * 7 / 3 + (random.nextInt(8) == 0 ? 1 : 0)) % colors;
                default -> 0;
            };
        }
        return data;
    }

    static byte[] encode(byte[] indices, int bits) {
        var out = new GifOutput(64);
        new GifLzwEncoder().encode(indices, 0, indices.length, Math.max(2, bits), out);
        return out.toByteArray();
    }

    /** Decodes the code size byte and sub-blocks written by the encoder, independently of the main decoder. */
    static byte[] decode(byte[] data, int count) {
        var minimum = data[0] & 0xFF;
        var packed = new java.io.ByteArrayOutputStream();
        var position = 1;
        while (data[position] != 0) {
            var length = data[position] & 0xFF;
            packed.write(data, position + 1, length);
            position += 1 + length;
        }
        assertEquals(data.length, position + 1, "block terminator ends the data");
        var bytes = packed.toByteArray();
        var clear = 1 << minimum;
        var prefixes = new int[4096];
        var suffixes = new int[4096];
        var lengths = new int[4096];
        var output = new byte[count];
        var written = 0;
        var width = minimum + 1;
        var next = clear + 2;
        var previous = -1;
        long bitBuffer = 0;
        var bitCount = 0;
        var offset = 0;
        for (var i = 0; i < clear; i++) {
            suffixes[i] = i;
            lengths[i] = 1;
        }
        while (true) {
            while (bitCount < width) {
                bitBuffer |= (long) (bytes[offset++] & 0xFF) << bitCount;
                bitCount += 8;
            }
            var code = (int) (bitBuffer & ((1 << width) - 1));
            bitBuffer >>>= width;
            bitCount -= width;
            if (code == clear) {
                width = minimum + 1;
                next = clear + 2;
                previous = -1;
                continue;
            }
            if (code == clear + 1) {
                break;
            }
            int first;
            if (code < next) {
                written += expand(code, prefixes, suffixes, lengths, output, written);
                first = output[written - lengths[code]] & 0xFF;
            } else {
                assertEquals(next, code, "KwKwK code");
                var start = written;
                written += expand(previous, prefixes, suffixes, lengths, output, written);
                first = output[start] & 0xFF;
                output[written++] = (byte) first;
            }
            if (previous >= 0 && next < 4096) {
                prefixes[next] = previous;
                suffixes[next] = first;
                lengths[next] = lengths[previous] + 1;
                next++;
                if (next == 1 << width && width < 12) {
                    width++;
                }
            }
            previous = code;
        }
        assertEquals(count, written, "decoded pixel count");
        return output;
    }

    private static int expand(int code, int[] prefixes, int[] suffixes, int[] lengths, byte[] output, int at) {
        var length = lengths[code];
        for (var i = length - 1; i >= 0; i--) {
            output[at + i] = (byte) suffixes[code];
            code = prefixes[code];
        }
        return length;
    }

    static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    /** Runs the giflib driver and returns the image data (code size byte through block terminator). */
    static byte[] giflib(Path driver, byte[] indices, int width, int height, int bits) {
        try {
            var directory = Files.createDirectories(Path.of("target", "lzw-tests"));
            var input = directory.resolve("in-" + Thread.currentThread().threadId() + ".raw");
            var output = directory.resolve("out-" + Thread.currentThread().threadId() + ".gif");
            Files.write(input, indices);
            var process = new ProcessBuilder(driver.toString(), input.toString(), Integer.toString(width),
                    Integer.toString(height), Integer.toString(bits), output.toString()).inheritIO().start();
            if (!process.waitFor(1, TimeUnit.MINUTES) || process.exitValue() != 0) {
                throw new IllegalStateException("giflib driver failed");
            }
            var gif = Files.readAllBytes(output);
            var start = 13 + 3 * (1 << bits) + 10;
            return Arrays.copyOfRange(gif, start, gif.length - 1);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    @Nested
    @DisplayName("round trip")
    class RoundTrip {
        @ParameterizedTest(name = "kind {0}, {1} indices of {2} bits")
        @CsvSource({"0, 1, 1", "3, 1, 8", "0, 2, 1", "0, 100, 2", "0, 5000, 8", "1, 100000, 8", "2, 70000, 4",
                "3, 300000, 8", "0, 200000, 1", "0, 200000, 3", "1, 4093, 2", "1, 4094, 2", "1, 4095, 2", "0, 9000, 8"})
        @DisplayName("an independent decoder recovers every index")
        void decodes(int kind, int count, int bits) {
            var input = indices(kind, count, bits, count * 31L + bits);
            assertArrayEquals(input, decode(encode(input, bits), count));
        }
    }

    @Nested
    @DisplayName("giflib identity")
    class Giflib {
        @ParameterizedTest(name = "kind {0}, {1}x{2}, {3} bits")
        @CsvSource({
                "0, 1, 1, 1, 699c1304256b6f0e045eba984543d11f7676216ae798cf6fa5f2c57a367f88ab",
                "0, 64, 64, 8, 30a79bc9388c4a2bf3815e772ea400f4647d3e3c2a87533962973976844683fe",
                "1, 640, 480, 8, 791a98d7441eda81c7dd877081e0f2ce0ec49e22a67b2fd7808a5f4d3cdfd73d",
                "2, 333, 211, 4, fffe2dda2413fb8bea3792e260aec04a44ccee3f149d9cec39971b27e59709fc",
                "0, 300, 300, 1, 81c1a69711721447acdbf35998105e99bb42973ec34b9690247256a02a52a479",
                "3, 1000, 1000, 8, 7808792d460809ca7a6e5a7e34f3f59f30f093b9a5ee514dcc222aea703a47d5",
                "0, 257, 129, 3, e6ced4545362ce05eea5242fb903850f912fa3feacad2cc6a968f80787654259"
        })
        @DisplayName("the image data matches giflib's pinned output")
        void pinned(int kind, int width, int height, int bits, String expected) {
            var input = indices(kind, width * height, bits, width * 131L + height);
            var actual = encode(input, bits);
            var driver = System.getProperty("smalt.test.giflib");
            if (driver != null) {
                assertArrayEquals(giflib(Path.of(driver), input, width, height, bits), actual);
            } else {
                assertEquals(expected, sha256(actual));
            }
        }

        @Test
        @DisplayName("hundreds of random inputs match giflib live")
        void live() {
            var driver = System.getProperty("smalt.test.giflib");
            Skip.unless(driver != null, "opt-in:giflib", "smalt.test.giflib is not set");
            var random = new Random(7);
            for (var i = 0; i < 300; i++) {
                var width = 1 + random.nextInt(400);
                var height = 1 + random.nextInt(200);
                var bits = 1 + random.nextInt(8);
                var input = indices(random.nextInt(4), width * height, bits, random.nextLong());
                assertArrayEquals(giflib(Path.of(driver), input, width, height, bits), encode(input, bits),
                        "case " + i + ": " + width + "x" + height + " at " + bits + " bits");
            }
        }
    }
}
