package com.github.auties00.smalt.image.raw.internal;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.raw.RawSamples;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Conformance;
import com.github.auties00.smalt.testkit.Skip;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Checks, against the photosites Adobe DNG Converter stores for them, the files of the formats this package's
 * samples did not cover: the sRAW and mRAW files of 16 Canon bodies, Panasonic raw format versions 6 (12-bit data),
 * 7 and 8 (in a grid of tiles), with their levels and white balance, and copies of CC0 samples with a field changed to
 * the values no sample has: Pentax files without their maker note's Huffman table, and a Panasonic version 8 file
 * with a ceiling, cell origins and shifts. The oracle files are the summaries {@code tools/testdata/raw-oracle.py}
 * writes into {@code <conformance cache>/raw-oracle} ({@code -Dsmalt.test.rawOracle} overrides the directory); the
 * tests are skipped without them.
 */
class SampleGapsOracleTest {
    private static final Path ORACLE = Path.of(System.getProperty("smalt.test.rawOracle",
            Conformance.directory().resolve("raw-oracle").toString()));

    // the S1R II's and S5's high-resolution files hold more than the default 128 Mi photosites
    private static final MediaLimits LIMITS = MediaLimits.builder().maxPixels(1L << 28).maxBytes(3L << 30).build();

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = RawSamples.class, names = {"CR2_1D4_MRAW", "CR2_1D4_SRAW", "CR2_1DX2_MRAW",
            "CR2_1DX2_SRAW", "CR2_50D_MRAW", "CR2_50D_SRAW", "CR2_5D2_MRAW", "CR2_5D2_SRAW", "CR2_5D3_MRAW",
            "CR2_5D4_MRAW", "CR2_5D4_SRAW", "CR2_5DSR_MRAW", "CR2_5DSR_SRAW", "CR2_5DS_MRAW", "CR2_5DS_SRAW",
            "CR2_60D_MRAW", "CR2_60D_SRAW", "CR2_6D2_MRAW", "CR2_6D2_SRAW", "CR2_6D_MRAW", "CR2_6D_SRAW",
            "CR2_70D_MRAW", "CR2_70D_SRAW", "CR2_7D2_MRAW", "CR2_7D2_SRAW", "CR2_80D_MRAW", "CR2_80D_SRAW",
            "PEF_K10D", "PEF_K2000", "PEF_K70", "RW2_GH5M2", "RW2_GH7", "RW2_S1RM2", "RW2_S1RM2_HIGH_RES",
            "RW2_S5_HIGH_RES", "RW2_S5_V7", "RW2_S9"})
    @DisplayName("the decoder reproduces the photosites the converter stores for the sample")
    void sample(RawSamples sample) {
        var name = sample.fileName();
        var summary = Summary.read(oracle(name.substring(0, name.lastIndexOf('.'))));
        check(summary, read(sample.fetch()));
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = RawSamples.class, names = {"RW2_GH5M2", "RW2_GH7", "RW2_S1RM2", "RW2_S1RM2_HIGH_RES",
            "RW2_S5_HIGH_RES", "RW2_S5_V7", "RW2_S9"})
    @DisplayName("the black and white levels and the white balance of the Panasonic files equal the converter's")
    void levels(RawSamples sample) {
        var name = sample.fileName();
        var path = ORACLE.resolve(name.substring(0, name.lastIndexOf('.')) + ".levels");
        Skip.unless(Files.isRegularFile(path), "data:raw-oracle", "no oracle file " + path
                + "; run tools/testdata/raw-oracle.py");
        var levels = VendorOracleTest.Levels.read(path);
        try (var source = MediaSource.of(sample.fetch())) {
            var file = RawParser.parse(source, LIMITS);
            assertNotNull(file.sensor, file.unsupported);
            var sensor = file.sensor;
            var cfa = levels.cfa();
            for (var row = 0; row < cfa[1]; row++) {
                for (var column = 0; column < cfa[0]; column++) {
                    var color = cfa[2 + row * cfa[0] + column];
                    for (var r = 0; r < 2; r++) {
                        for (var c = 0; c < 2; c++) {
                            if (sensor.cfa.color(r, c).ordinal() == color) {
                                assertEquals(levels.black(row, column), sensor.black[r * sensor.blackColumns + c],
                                        "the black level of color " + color);
                            }
                        }
                    }
                }
            }
            assertArrayEquals(levels.white(), sensor.white, "the white level");
            var neutral = levels.neutral();
            var ours = file.color.asShotNeutral;
            assertNotNull(ours, "a white balance");
            assertArrayEquals(new double[]{neutral[0] / neutral[1], 1, neutral[2] / neutral[1]},
                    new double[]{ours[0] / ours[1], 1, ours[2] / ours[1]}, 1e-4, "the white balance");
        }
    }

    static Stream<String> copies() {
        return Stream.of("patched-pentax-k10d-no-table", "patched-pentax-k2000-no-table",
                "patched-pentax-k-70-no-table", "patched-panasonic-dc-s5m2-ceiling-origins",
                "patched-panasonic-dc-s5m2-shifts",
                "patched-panasonic-dc-s5m2-long-shifts");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("copies")
    @DisplayName("a copy of a sample with a field set to a value no sample has decodes to the converter's photosites")
    void copy(String name) {
        var summary = Summary.read(oracle(name));
        check(summary, read(patched(name)));
    }

    // the copies of raw-oracle.py's PATCHED_SAMPLES: the offsets and bytes it writes
    static byte[] patched(String name) {
        return switch (name) {
            case "patched-pentax-k10d-no-table" -> change(RawSamples.PEF_K10D, 1688, 0x7F, 0xFF);
            case "patched-pentax-k2000-no-table" -> change(RawSamples.PEF_K2000, 1796, 0x7F, 0xFF);
            case "patched-pentax-k-70-no-table" -> change(RawSamples.PEF_K70, 1910, 0xFF, 0x7F);
            case "patched-panasonic-dc-s5m2-ceiling-origins" -> {
                var data = bytes(RawSamples.RW2_S5M2);
                var buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
                for (var k = 0; k < 6; k++) {
                    buffer.putInt(798 + 4 * k, 2 << 16);
                    buffer.putInt(824 + 4 * k, 100);
                }
                buffer.putShort(406, (short) 4000);
                buffer.putShort(418, (short) 1000);
                buffer.putShort(430, (short) 2000);
                buffer.putShort(442, (short) 3000);
                buffer.putShort(454, (short) 60000);
                yield data;
            }
            case "patched-panasonic-dc-s5m2-shifts" -> shifts(0, 0, 1, 2, 3, 4, 5, 6, 7, 3, 2, 1, 0, 0, 2, 3, 4);
            case "patched-panasonic-dc-s5m2-long-shifts" -> shifts(0, 0, 0, 0, 0, 7, 6, 0, 0, 15, 0, 6, 7, 8, 9, 0, 16);
            default -> throw new IllegalArgumentException(name);
        };
    }

    private static byte[] shifts(int... shifts) {
        var data = bytes(RawSamples.RW2_S5M2);
        var buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        for (var k = 0; k < shifts.length; k++) {
            buffer.putShort(920 + 2 * k, (short) shifts[k]);
        }
        return data;
    }

    private static byte[] change(RawSamples sample, int offset, int first, int second) {
        var data = bytes(sample);
        data[offset] = (byte) first;
        data[offset + 1] = (byte) second;
        return data;
    }

    private static byte[] bytes(RawSamples sample) {
        try {
            return Files.readAllBytes(sample.fetch());
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static Path oracle(String stem) {
        var path = ORACLE.resolve(stem + ".photosites");
        Skip.unless(Files.isRegularFile(path), "data:raw-oracle", "no oracle file " + path
                + "; run tools/testdata/raw-oracle.py");
        return path;
    }

    private static SensorPlane read(Path path) {
        try {
            return read(Files.readAllBytes(path));
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static SensorPlane read(byte[] data) {
        try (var source = MediaSource.of(data)) {
            var file = RawParser.parse(source, LIMITS);
            assertNotNull(file.sensor, file.unsupported);
            var plane = new SensorPlane(file.sensor.width, file.sensor.height, file.sensor.samplesPerPixel,
                    file.sensor.floating, LIMITS);
            file.sensor.reader.read(plane, LIMITS);
            return plane;
        }
    }

    // the summary must match the decoded plane at an offset of at most eight photosites each way
    private static void check(Summary summary, SensorPlane plane) {
        assertEquals(summary.samples(), plane.samplesPerPixel, "samples per pixel");
        for (var y = 0; y <= Math.min(8, plane.height - summary.height()); y++) {
            for (var x = 0; x <= Math.min(8, plane.width - summary.width()); x++) {
                if (summary.matches(plane, x, y)) {
                    return;
                }
            }
        }
        throw new AssertionError("the converter's photosites match the decoded ones at no offset");
    }

    /**
     * A summary of the stored image of a DNG file: its size, and for each sample of a pixel the SHA-256 of its values
     * (little-endian 16-bit words, row by row) and its values in the first row.
     *
     * @param width the width
     * @param height the height
     * @param samples the samples of a pixel
     * @param digests the digest of each sample
     * @param rows the first row of each sample
     */
    record Summary(int width, int height, int samples, List<String> digests, List<short[]> rows) {
        static Summary read(Path path) {
            List<String> lines;
            try {
                lines = Files.readAllLines(path);
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
            var values = new HashMap<String, String>();
            for (var line : lines) {
                if (!line.isBlank() && !line.startsWith("#")) {
                    var space = line.indexOf(' ');
                    var key = line.substring(0, space);
                    var rest = line.substring(space + 1);
                    if (key.equals("digest") || key.equals("row")) {
                        key = key + rest.substring(0, rest.indexOf(' '));
                        rest = rest.substring(rest.indexOf(' ') + 1);
                    }
                    values.put(key, rest);
                }
            }
            var width = Integer.parseInt(values.get("width"));
            var samples = Integer.parseInt(values.get("samples"));
            var digests = new java.util.ArrayList<String>();
            var rows = new java.util.ArrayList<short[]>();
            for (var sample = 0; sample < samples; sample++) {
                digests.add(values.get("digest" + sample));
                var bytes = HexFormat.of().parseHex(values.get("row" + sample));
                var row = new short[width];
                for (var x = 0; x < width; x++) {
                    row[x] = (short) ((bytes[2 * x] & 0xFF) | (bytes[2 * x + 1] & 0xFF) << 8);
                }
                rows.add(row);
            }
            return new Summary(width, Integer.parseInt(values.get("height")), samples, digests, rows);
        }

        boolean matches(SensorPlane plane, int x, int y) {
            var spp = samples;
            for (var sample = 0; sample < spp; sample++) {
                var row = rows.get(sample);
                for (var column = 0; column < width; column++) {
                    if (plane.integers[(y * plane.width + x + column) * spp + sample] != row[column]) {
                        return false;
                    }
                }
            }
            for (var sample = 0; sample < spp; sample++) {
                MessageDigest sha;
                try {
                    sha = MessageDigest.getInstance("SHA-256");
                } catch (NoSuchAlgorithmException exception) {
                    throw new AssertionError(exception);
                }
                var line = ByteBuffer.allocate(width * 2).order(ByteOrder.LITTLE_ENDIAN);
                for (var r = 0; r < height; r++) {
                    line.clear();
                    for (var column = 0; column < width; column++) {
                        line.putShort(plane.integers[((y + r) * plane.width + x + column) * spp + sample]);
                    }
                    sha.update(line.array());
                }
                if (!digests.get(sample).equals(HexFormat.of().formatHex(sha.digest()))) {
                    return false;
                }
            }
            return true;
        }
    }
}
