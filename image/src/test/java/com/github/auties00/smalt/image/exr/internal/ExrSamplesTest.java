package com.github.auties00.smalt.image.exr.internal;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Compares the samples of every OpenEXR vector of tools/testdata/behavior/make-exr-vectors.py with those the
 * reference OpenEXR library 3.5.2 decodes from it (the .f32 files next to the vectors), sample for sample: every
 * compression method, pixel type, tiling and level mode, multi-part and deep files.
 */
@DisplayName("OpenEXR samples against the reference library")
class ExrSamplesTest {
    private static final String ROOT = "/com/github/auties00/smalt/image/exr/";

    static byte[] resource(String name) {
        try (var input = ExrSamplesTest.class.getResourceAsStream(ROOT + name)) {
            assertNotNull(input, name);
            return input.readAllBytes();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    static float[] reference(String name) {
        var buffer = ByteBuffer.wrap(resource(name + ".f32")).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
        var values = new float[buffer.remaining()];
        buffer.get(values);
        return values;
    }

    private static MediaLimits limits() {
        return ImageDecoderOptions.defaults().limits();
    }

    /** Decodes the levels a vector's reference holds, in offset table order, and compares them. */
    private static void compare(String name, boolean allLevels) {
        var file = ExrFile.read(resource(name + ".exr"), limits());
        var expected = reference(name);
        var position = 0;
        for (var part = 0; part < file.parts().size(); part++) {
            var header = file.parts().get(part);
            var levels = new java.util.ArrayList<int[]>();
            if (!allLevels || header.levelMode() == ExrHeader.ONE_LEVEL) {
                levels.add(new int[]{0, 0});
            } else if (header.levelMode() == ExrHeader.MIPMAP_LEVELS) {
                for (var level = 0; level < header.levelsX(); level++) {
                    levels.add(new int[]{level, level});
                }
            } else {
                for (var ly = 0; ly < header.levelsY(); ly++) {
                    for (var lx = 0; lx < header.levelsX(); lx++) {
                        levels.add(new int[]{lx, ly});
                    }
                }
            }
            for (var level : levels) {
                var planes = ExrSamples.read(file, part, level[0], level[1], limits());
                for (var channel = 0; channel < header.channels().size(); channel++) {
                    var plane = planes.planes()[channel];
                    var rounded = 0;
                    for (var index = 0; index < plane.length; index++) {
                        var want = expected[position + index];
                        var got = plane[index];
                        // DWA's lossy transforms run vectorized in the reference library, whose rounding may differ
                        // from the scalar transforms by one half unit in a few samples, which its table to linear
                        // values can widen to a few units
                        if (name.contains("dwa") && Float.floatToIntBits(want) != Float.floatToIntBits(got)
                                && Math.abs(Float.floatToFloat16(want) - Float.floatToFloat16(got)) <= 4) {
                            rounded++;
                            continue;
                        }
                        if (Float.floatToIntBits(want) != Float.floatToIntBits(got)) {
                            fail(name + " part " + part + " level " + level[0] + "," + level[1] + " channel "
                                    + header.channels().get(channel).name() + " sample " + index + ": expected "
                                    + want + " got " + got);
                        }
                    }
                    assertTrue(rounded <= plane.length / 100, name + " channel " + header.channels().get(channel)
                            .name() + ": " + rounded + " samples off by one half unit");
                    position += plane.length;
                }
            }
        }
        assertEquals(expected.length, position, name + ": sample count");
    }

    @ParameterizedTest
    @ValueSource(strings = {"scanline-none", "scanline-rle", "scanline-zips", "scanline-zip", "scanline-piz",
            "scanline-pxr24", "scanline-b44", "scanline-b44a", "scanline-dwaa", "scanline-dwab", "float-piz",
            "float-pxr24", "float-dwab", "float-zip", "tiled-dwaa", "multipart", "layers-zips",
            "luminance-chroma-piz", "windows-decreasing", "gray-b44-linear"})
    @DisplayName("decodes the samples OpenEXR decodes from files it wrote")
    void againstReference(String name) {
        compare(name, false);
    }

    @ParameterizedTest
    @ValueSource(strings = {"scanline-htj2k256", "scanline-htj2k32", "scanline-zstd", "float-htj2k256", "float-zstd",
            "tiled-htj2k32", "tiled-zstd", "luminance-chroma-htj2k256", "luminance-chroma-zstd",
            "luminance-chroma-negative-htj2k32", "luminance-chroma-negative-zstd", "windows-decreasing-zstd",
            "windows-decreasing-htj2k32", "smooth-htj2k256", "scanline-lj2k", "tiled-lj2k"})
    @DisplayName("decodes the lossless HTJ2K and ZSTD files of OpenEXR 3.5 bit for bit")
    void openExr35(String name) {
        compare(name, false);
    }

    @ParameterizedTest
    @ValueSource(strings = {"smooth-lj2k", "float-lj2k", "float-bright-lj2k"})
    @DisplayName("decodes lossy LJ2K channels within the rounding of the wavelet and its others bit for bit")
    void lossy(String name) {
        var data = resource(name + ".exr");
        var file = ExrFile.read(data, limits());
        var header = file.parts().getFirst();
        assertEquals(12, header.compression());
        // the chunks hold codestreams, not the uncompressed fallback of chunks that code larger
        var at = file.chunk(0, 0);
        assertEquals(0x484C, (data[at + 8] & 0xFF) << 8 | data[at + 9] & 0xFF);
        var planes = ExrSamples.read(file, 0, 0, 0, limits());
        var expected = reference(name);
        var position = 0;
        for (var channel = 0; channel < header.channels().size(); channel++) {
            var info = header.channels().get(channel);
            var plane = planes.planes()[channel];
            var color = List.of("B", "G", "R").contains(info.name());
            var exact = 0;
            var error = 0.0;
            for (var index = 0; index < plane.length; index++) {
                var want = expected[position + index];
                var got = plane[index];
                error += Math.abs(want - got) / Math.max(1, Math.abs(want));
                if (Float.floatToIntBits(want) == Float.floatToIntBits(got)) {
                    exact++;
                    continue;
                }
                if (!color) {
                    fail(name + " lossless channel " + info.name() + " sample " + index + ": expected " + want
                            + " got " + got);
                }
                // the transfer function makes the coding error relative: a fraction of the magnitude, at least a
                // fraction of 1
                var tolerance = 2e-3f * Math.max(1, Math.abs(want));
                assertTrue(Math.abs(want - got) <= tolerance, name + " channel " + info.name() + " sample " + index
                        + ": expected " + want + " got " + got);
            }
            // a half sample rounds away most of the arithmetic differences of the float wavelets, a float one keeps
            // them, so float channels are bounded by their mean error instead
            if (color && info.bytes() == 2) {
                assertTrue(exact >= plane.length / 2, name + " channel " + info.name() + ": only " + exact + " of "
                        + plane.length + " samples as the reference decodes them");
            } else if (color) {
                var mean = error / plane.length;
                assertTrue(mean <= 2e-4, name + " channel " + info.name() + ": mean relative error " + mean);
            }
            position += plane.length;
        }
        assertEquals(expected.length, position);
    }

    @ParameterizedTest
    @ValueSource(strings = {"tiled-mipmap-zip", "tiled-mipmap-up-none", "tiled-ripmap-zip"})
    @DisplayName("decodes every mipmap and ripmap level, rounded down and up")
    void levels(String name) {
        compare(name, true);
    }

    @ParameterizedTest
    @ValueSource(strings = {"deep-scanline-zips", "deep-scanline-zstd"})
    @DisplayName("flattens deep samples front to back")
    void deep(String name) {
        var file = ExrFile.read(resource(name + ".exr"), limits());
        var planes = ExrSamples.read(file, 0, 0, 0, limits());
        var expected = reference(name);
        var position = 0;
        for (var channel = 0; channel < planes.planes().length; channel++) {
            for (var value : planes.planes()[channel]) {
                assertEquals(expected[position], value, 1e-6f, "channel " + channel + " sample " + position);
                position++;
            }
        }
        assertEquals(expected.length, position);
    }

    @Test
    @DisplayName("rebuilds an offset table whose entries were lost")
    void brokenOffsets() {
        var data = resource("scanline-zip.exr");
        var file = ExrFile.read(data, limits());
        var expected = ExrSamples.read(file, 0, 0, 0, limits());
        var broken = data.clone();
        var count = (int) file.parts().getFirst().chunkCount();
        var tableStart = (int) offsetOfTable(data, count);
        java.util.Arrays.fill(broken, tableStart, tableStart + 8 * count, (byte) 0);
        var repaired = ExrSamples.read(ExrFile.read(broken, limits()), 0, 0, 0, limits());
        for (var channel = 0; channel < expected.planes().length; channel++) {
            assertArrayEquals(expected.planes()[channel], repaired.planes()[channel]);
        }
    }

    /** {@return the offset of the offset table: the smallest chunk offset minus the table size} */
    private static long offsetOfTable(byte[] data, int count) {
        for (var at = 8; at + 8 * count <= data.length; at++) {
            if (data[at - 1] == 0 && ExrHeader.i64(data, at) == at + 8L * count) {
                return at;
            }
        }
        throw new AssertionError("no offset table");
    }

    @Test
    @DisplayName("the vectors are the files pinned in exr.sha256")
    void pinned() throws NoSuchAlgorithmException {
        var manifest = new String(resource("exr.sha256"), java.nio.charset.StandardCharsets.US_ASCII);
        // a checkout may turn the manifest's line ends into CR LF
        var lines = manifest.strip().lines().map(String::strip).toList();
        assertTrue(lines.size() >= 40);
        for (var line : lines) {
            var parts = line.split("  ");
            var digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(resource(parts[1])));
            assertEquals(parts[0], digest, parts[1]);
        }
    }

    private static String hash(short[] table) throws NoSuchAlgorithmException {
        var bytes = new byte[table.length * 2];
        for (var index = 0; index < table.length; index++) {
            bytes[2 * index] = (byte) table[index];
            bytes[2 * index + 1] = (byte) (table[index] >> 8);
        }
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    @Test
    @DisplayName("builds the B44 and DWA tables OpenEXR ships")
    void tables() throws NoSuchAlgorithmException {
        // SHA-256 of exrcore_logTable (src/lib/OpenEXRCore/internal_b44_table.c) and dwaCompressorToLinear
        // (src/lib/OpenEXRCore/dwaLookups.h) of OpenEXR 3.3.5, little-endian
        assertEquals("92de2787a44544c6e4d54f942f38cf50453b342f179189dbaeb514743fc9109f", hash(ExrCodecs.logTable()));
        assertEquals("72e80880a49b9428073cf87e2b181a6da74cfac9f8f7f4c6e459f0ac1f0225a5", hash(ExrDwa.toLinearTable()));
    }
}
