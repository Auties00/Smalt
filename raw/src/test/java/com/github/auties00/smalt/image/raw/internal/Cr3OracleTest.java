package com.github.auties00.smalt.image.raw.internal;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.raw.RawSamples;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Conformance;
import com.github.auties00.smalt.testkit.Skip;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the CR3 decoder against Adobe DNG Converter as a black-box oracle: for every CC0 CR3 sample (lossless
 * and C-RAW, codec versions 1 and 2, one and two tiles, a dual pixel file) the decoded photosites must equal, every
 * one of them, those of the uncompressed DNG file the converter writes, and the color filter pattern, the image
 * area, the black levels, the as-shot white balance and the white level must be the converter's. The converter
 * writes a white level of its own, larger than the camera's specular level, for some cameras; for those the
 * camera's level must lie below the converter's. The DNG files are read here directly (the dual pixel file holds
 * two samples per photosite, the second being the image of one photodiode of each pair). The oracle files are the
 * ones {@code tools/testdata/raw-oracle.py} writes into {@code <conformance cache>/raw-oracle}
 * ({@code -Dsmalt.test.rawOracle} overrides the directory); the tests are skipped without them.
 */
class Cr3OracleTest {
    private static final Path ORACLE = Path.of(System.getProperty("smalt.test.rawOracle",
            Conformance.directory().resolve("raw-oracle").toString()));

    // the full-range level the converter writes instead of the camera's specular white level
    private static final double CONVERTER_FULL_RANGE = 16384;

    static Stream<Arguments> samples() {
        return Arrays.stream(RawSamples.values())
                .filter(sample -> sample.fileName().endsWith(".cr3"))
                .map(Arguments::of);
    }

    /**
     * The raw image of a DNG file the converter wrote.
     */
    private record Converted(int width, int height, int samplesPerPixel, short[] samples, int[] active, double[] crop,
                             double[] black, double white, double[] neutral, String cfa) {
    }

    private static Converted converted(RawSamples sample) {
        var name = sample.fileName();
        var dng = ORACLE.resolve(name.substring(0, name.lastIndexOf('.')) + ".dng");
        Skip.unless(Files.isRegularFile(dng), "data:raw-oracle", "no oracle file " + dng + "; run tools/testdata/raw-oracle.py");
        try (var source = MediaSource.of(dng)) {
            var reader = TiffReader.open(source, 0, MediaLimits.DEFAULT);
            var first = reader.directory(reader.firstDirectory());
            Ifd raw = null;
            for (var offset : first.longs(330)) {
                var ifd = reader.directory(offset);
                if (ifd.intValue(254, 1) == 0 && ifd.intValue(262, 0) == 32803) {
                    raw = ifd;
                }
            }
            assertTrue(raw != null, "no raw image in " + dng);
            assertEquals(1, raw.intValue(259, 1), "the oracle must be uncompressed");
            var width = raw.intValue(256, 0);
            var height = raw.intValue(257, 0);
            var spp = raw.intValue(277, 1);
            var samples = new short[width * height * spp];
            var offsets = raw.longs(273);
            var counts = raw.longs(279);
            var at = 0;
            for (var strip = 0; strip < offsets.length; strip++) {
                var bytes = reader.bytes(offsets[strip], counts[strip]);
                for (var i = 0; i + 1 < bytes.length && at < samples.length; i += 2) {
                    samples[at++] = (short) (reader.little() ? (bytes[i] & 0xFF) | (bytes[i + 1] & 0xFF) << 8
                            : (bytes[i] & 0xFF) << 8 | (bytes[i + 1] & 0xFF));
                }
            }
            var active = Arrays.stream(raw.longs(50829)).mapToInt(v -> (int) v).toArray();
            var pattern = raw.bytes(33422);
            var cfa = new StringBuilder();
            for (var color : pattern) {
                cfa.append("RGB".charAt(color));
            }
            return new Converted(width, height, spp, samples, active,
                    new double[]{raw.doubles(50719)[0], raw.doubles(50719)[1], raw.doubles(50720)[0], raw.doubles(50720)[1]},
                    raw.doubles(50714), raw.doubles(50717)[0], first.doubles(50728), cfa.toString());
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("samples")
    @DisplayName("the decoded photosites equal those Adobe DNG Converter stores for the sample")
    void photosites(RawSamples sample) {
        var oracle = converted(sample);
        SensorPlane decoded;
        try (var source = MediaSource.of(sample.fetch())) {
            var file = RawParser.parse(source, MediaLimits.DEFAULT);
            assertTrue(file.sensor != null, file.unsupported);
            var sensor = file.sensor;
            decoded = new SensorPlane(sensor.width, sensor.height, 1, false, MediaLimits.DEFAULT);
            sensor.reader.read(decoded, MediaLimits.DEFAULT);
        }
        assertEquals(oracle.width(), decoded.width);
        assertEquals(oracle.height(), decoded.height);
        var spp = oracle.samplesPerPixel();
        var mismatches = 0L;
        var first = -1;
        for (var i = 0; i < decoded.integers.length; i++) {
            if (decoded.integers[i] != oracle.samples()[i * spp]) {
                if (first < 0) {
                    first = i;
                }
                mismatches++;
            }
        }
        assertEquals(0, mismatches, "photosites differ from the oracle, first at row " + first / decoded.width
                + " column " + first % decoded.width);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("samples")
    @DisplayName("the color filter pattern, image area, levels and white balance are the converter's")
    void levels(RawSamples sample) {
        var theirs = converted(sample);
        try (var source = MediaSource.of(sample.fetch())) {
            var ours = RawParser.parse(source, MediaLimits.DEFAULT);
            var sensor = ours.sensor;
            assertEquals(theirs.cfa(), sensor.cfa.toString(), "color filter pattern");
            var area = sensor.active();
            var crop = sensor.defaultCrop;
            var active = theirs.active();
            var left = active[1] + (int) theirs.crop()[0];
            var top = active[0] + (int) theirs.crop()[1];
            assertArrayEquals(new int[]{top, left, top + (int) theirs.crop()[3], left + (int) theirs.crop()[2]},
                    new int[]{area[0] + (int) crop[1], area[1] + (int) crop[0], area[0] + (int) (crop[1] + crop[3]),
                            area[1] + (int) (crop[0] + crop[2])}, "image area");
            assertTrue(sensor.black != null, "black levels");
            // the converter measures the black level on the masked area of some cameras: within one unit of the camera's
            for (var cell = 0; cell < 4; cell++) {
                var row = (cell >> 1) + area[0] - active[0];
                var column = (cell & 1) + area[1] - active[1];
                var level = theirs.black().length < 4 ? theirs.black()[0]
                        : theirs.black()[((row & 1) * 2 + (column & 1)) * theirs.samplesPerPixel()];
                assertEquals(level, sensor.black[cell], 1.0, "black level of cell " + cell);
            }
            assertArrayEquals(theirs.neutral(), ours.color.asShotNeutral, 1e-5, "as-shot neutral");
            assertTrue(sensor.white != null, "white level");
            if (theirs.white() == CONVERTER_FULL_RANGE || sample == RawSamples.CR3_EOS_1D_X_MARK_III) {
                assertTrue(sensor.white[0] < theirs.white(), "white level " + sensor.white[0] + " not below the converter's");
            } else {
                assertEquals(theirs.white(), sensor.white[0], "white level");
            }
        }
    }
}
