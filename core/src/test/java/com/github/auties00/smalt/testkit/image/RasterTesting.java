package com.github.auties00.smalt.testkit.image;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Scratch;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Consumer;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Shared helpers for the PNG, GIF and BMP tests: ffmpeg reference decodes, tar extraction,
 * picture comparison and the deterministic mutation harness.
 */
public final class RasterTesting {
    private RasterTesting() {
        throw new AssertionError();
    }

    /** Returns the tightly packed RGBA bytes of a picture. */
    public static byte[] tight(RgbaPicture picture) {
        var out = new byte[picture.width() * picture.height() * 4];
        for (var y = 0; y < picture.height(); y++) {
            System.arraycopy(picture.pixels(), y * picture.stride(), out, y * picture.width() * 4, picture.width() * 4);
        }
        return out;
    }

    /** Returns the tightly packed RGBA bytes of a picture that must be an 8-bit {@link RgbaPicture}. */
    public static byte[] tight(Picture picture) {
        return tight(org.junit.jupiter.api.Assertions.assertInstanceOf(RgbaPicture.class, picture));
    }

    /** Asserts two RGBA buffers are identical, reporting the first differing pixel. */
    public static void assertSameRgba(byte[] expected, byte[] actual, int width, String what) {
        assertEquals(expected.length, actual.length, what + ": buffer length");
        for (var i = 0; i < expected.length; i++) {
            if (expected[i] != actual[i]) {
                var pixel = i / 4;
                fail(what + ": first difference at pixel (" + pixel % width + "," + pixel / width + ") channel " + i % 4
                        + ": expected " + (expected[i] & 0xFF) + " got " + (actual[i] & 0xFF)
                        + " (expected pixel " + hex(expected, pixel * 4) + ", got " + hex(actual, pixel * 4) + ")");
            }
        }
    }

    /**
     * Asserts two RGBA buffers agree on every alpha value and on the color of every pixel that
     * is not fully transparent (the color of a fully transparent pixel is invisible).
     */
    public static void assertSameVisible(byte[] expected, byte[] actual, int width, String what) {
        assertEquals(expected.length, actual.length, what + ": buffer length");
        for (var i = 0; i < expected.length; i += 4) {
            var visible = expected[i + 3] != 0 || actual[i + 3] != 0;
            for (var c = visible ? 0 : 3; c < 4; c++) {
                if (expected[i + c] != actual[i + c]) {
                    var pixel = i / 4;
                    fail(what + ": first difference at pixel (" + pixel % width + "," + pixel / width + ") channel " + c
                            + " (expected pixel " + hex(expected, i) + ", got " + hex(actual, i) + ")");
                }
            }
        }
    }

    private static String hex(byte[] data, int offset) {
        return String.format("%02x%02x%02x%02x", data[offset], data[offset + 1], data[offset + 2], data[offset + 3]);
    }

    /** Decodes every frame of a file with ffmpeg to tightly packed RGBA, one frame per decoded frame. */
    public static byte[] ffmpegFrames(Path input, String pixelFormat) {
        return Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(5), List.of("-v", "error", "-i", input.toString(),
                "-map", "0:v:0", "-fps_mode", "passthrough", "-f", "rawvideo", "-pix_fmt", pixelFormat, "-"));
    }

    /** Decodes a file with ffmpeg to rgba64le and reduces every sample with (v * 255 + 32767) / 65535. */
    public static byte[] ffmpegReduced16(Path input) {
        var wide = ffmpegFrames(input, "rgba64le");
        var out = new byte[wide.length / 2];
        for (var i = 0; i < out.length; i++) {
            var value = (wide[i * 2] & 0xFF) | (wide[i * 2 + 1] & 0xFF) << 8;
            out[i] = (byte) ((value * 255 + 32767) / 65535);
        }
        return out;
    }

    /** Extracts the regular files of a gzip-compressed ustar archive into memory. */
    public static Map<String, byte[]> untarGz(Path archive) {
        try (InputStream input = new GZIPInputStream(Files.newInputStream(archive))) {
            var data = input.readAllBytes();
            var files = new LinkedHashMap<String, byte[]>();
            var offset = 0;
            while (offset + 512 <= data.length) {
                var nameEnd = offset;
                while (nameEnd < offset + 100 && data[nameEnd] != 0) {
                    nameEnd++;
                }
                if (nameEnd == offset) {
                    break;
                }
                var name = new String(data, offset, nameEnd - offset, StandardCharsets.US_ASCII);
                var sizeField = new String(data, offset + 124, 12, StandardCharsets.US_ASCII).replace("\0", "").trim();
                var size = sizeField.isEmpty() ? 0 : Integer.parseInt(sizeField, 8);
                var type = data[offset + 156];
                if (type == '0' || type == 0) {
                    var content = new byte[size];
                    System.arraycopy(data, offset + 512, content, 0, size);
                    files.put(name.substring(name.lastIndexOf('/') + 1), content);
                }
                offset += 512 + (size + 511) / 512 * 512;
            }
            return files;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /** Writes bytes to a file under target/raster-tests and returns the path. */
    public static Path write(String name, byte[] data) {
        try {
            var file = Scratch.directory("raster-tests").resolve(name);
            Files.write(file, data);
            return file;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /**
     * Returns the number of mutations per seed: the {@code raster.mutations} system property when
     * set (for longer fuzzing campaigns), the given default otherwise.
     */
    public static int mutationsPerSeed(int fallback) {
        return Integer.getInteger("raster.mutations", fallback);
    }

    /** Returns the time bound of a mutation run, scaled with {@link #mutationsPerSeed(int)}. */
    public static long mutationSeconds() {
        return Math.max(60, Integer.getInteger("raster.mutations", 0) / 10);
    }

    /** Counts of the outcomes of a mutation run. */
    public record MutationStats(int variants, int decoded, int rejected) {
    }

    /**
     * Runs a decoder over deterministic mutations of valid inputs: byte flips, truncations and
     * corruption of 32-bit and 16-bit length-like fields. Every decode must either succeed or
     * throw a {@link MediaException}; anything else fails the test.
     */
    public static MutationStats mutate(List<byte[]> seeds, int variantsPerSeed, long seed, Consumer<MediaSource> decoder) {
        return mutate(seeds, variantsPerSeed, seed, java.util.function.UnaryOperator.identity(), decoder);
    }

    /**
     * Runs {@link #mutate(List, int, long, Consumer)} with a repair step applied to every variant,
     * for example recomputing checksums so the mutation reaches the decoder past integrity checks.
     */
    public static MutationStats mutate(List<byte[]> seeds, int variantsPerSeed, long seed,
                                       java.util.function.UnaryOperator<byte[]> repair, Consumer<MediaSource> decoder) {
        var random = new Random(seed);
        var decoded = 0;
        var rejected = 0;
        var variants = 0;
        for (var original : seeds) {
            for (var v = 0; v < variantsPerSeed; v++) {
                var mutated = repair.apply(mutateOnce(original, random));
                variants++;
                try {
                    decoder.accept(MediaSource.of(mutated));
                    decoded++;
                } catch (MediaException expected) {
                    rejected++;
                } catch (Throwable unexpected) {
                    throw new AssertionError("variant " + v + " of a " + original.length + "-byte seed raised "
                            + unexpected, unexpected);
                }
            }
        }
        return new MutationStats(variants, decoded, rejected);
    }

    private static byte[] mutateOnce(byte[] original, Random random) {
        var data = original.clone();
        switch (random.nextInt(5)) {
            case 0 -> {
                var flips = 1 + random.nextInt(8);
                for (var i = 0; i < flips; i++) {
                    data[random.nextInt(data.length)] ^= (byte) (1 << random.nextInt(8));
                }
            }
            case 1 -> {
                return java.util.Arrays.copyOf(data, random.nextInt(data.length));
            }
            case 2 -> {
                var offset = random.nextInt(Math.max(1, data.length - 4));
                var value = switch (random.nextInt(4)) {
                    case 0 -> 0;
                    case 1 -> -1;
                    case 2 -> 0x7FFFFFFF;
                    default -> random.nextInt();
                };
                for (var i = 0; i < 4 && offset + i < data.length; i++) {
                    data[offset + i] = (byte) (value >>> (24 - 8 * i));
                }
            }
            case 3 -> {
                var offset = random.nextInt(Math.max(1, data.length - 2));
                var value = random.nextBoolean() ? 0xFFFF : random.nextInt(0x10000);
                data[offset] = (byte) value;
                if (offset + 1 < data.length) {
                    data[offset + 1] = (byte) (value >>> 8);
                }
            }
            default -> {
                var offset = random.nextInt(data.length);
                var length = Math.min(data.length - offset, 1 + random.nextInt(64));
                for (var i = 0; i < length; i++) {
                    data[offset + i] = (byte) random.nextInt(256);
                }
            }
        }
        return data;
    }

    /** Concatenates byte arrays. */
    public static byte[] concat(byte[]... parts) {
        var out = new ByteArrayOutputStream();
        for (var part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }
}
