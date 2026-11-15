package com.github.auties00.smalt.image.raw.internal;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Conformance;
import com.github.auties00.smalt.testkit.Skip;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Compares the photosites of CC0 Phase One IIQ samples with those Adobe DNG Converter stores, in the windows of the
 * {@code <stem>.windows} files {@code tools/testdata/raw-oracle.py} keeps (four corners, the center and the first and
 * last rows) in {@code <conformance cache>/raw-oracle} ({@code -Dsmalt.test.rawOracle} overrides it); the tests skip
 * without them. The converter's image starts one row above or one column left of the block's on some backs.
 *
 * <p>Each sample's class states the share of photosites that must be within one unit and the mean difference allowed:
 * the backs whose rule was derived exactly, the IQ180 and the binned (Sensor+) files of the other backs with several
 * outputs included, leave only the defective photosites, which the converter repairs with its own weights; the other
 * files of the backs with four outputs differ by the converter's seam correction, which this decoder lacks (the
 * {@code TODO} of {@link IiqCalibration}). The limits pin what the decoder reaches.
 */
class IiqOracleTest {
    private static final Path ORACLE = Path.of(System.getProperty("smalt.test.rawOracle",
            Conformance.directory().resolve("raw-oracle").toString()));

    /**
     * A sample.
     *
     * @param name the vector name
     * @param down the rows the converter's image starts below the block's (may be negative)
     * @param right the columns it starts right of the block's
     * @param offset what the converter adds to the photosites
     * @param scale what the converter multiplies the photosites by
     * @param within the share of photosites within one unit
     * @param mean the mean absolute difference allowed
     */
    record Sample(String name, int down, int right, int offset, double scale, double within, double mean) {
        Sample(String name, int down, int right, double within, double mean) {
            this(name, down, right, 0, 1, within, mean);
        }

        @Override
        public String toString() {
            return name;
        }
    }

    /**
     * The IQ180's photosites: Adobe DNG Converter stores them over a black level of 1028, scaled so that the back's
     * white level of 64000 reaches 65535.
     */
    private static final int IQ180_OFFSET = 1028;
    private static final double IQ180_SCALE = (65535 - 1028) / 64000.0;

    private static final double EXACT = 0.995, EXACT_MEAN = 1.5;

    /**
     * The samples Adobe DNG Converter reads.
     */
    private static final List<Sample> SAMPLES = List.of(
            new Sample("rawpixls-iiq-p20-cf051544.iiq", 0, 0, EXACT, EXACT_MEAN),
            new Sample("rawpixls-iiq-p20-cf051545.iiq", 0, 0, EXACT, EXACT_MEAN),
            new Sample("rawpixls-iiq-p20-cf051552.tif", 0, 0, EXACT, EXACT_MEAN),
            new Sample("rawpixls-iiq-p20-cf051553.tif", 0, 0, EXACT, EXACT_MEAN),
            new Sample("rawpixls-iiq-p25plus-cf028662.iiq", -1, 0, EXACT, EXACT_MEAN),
            new Sample("rawpixls-iiq-p25plus-cf028663.iiq", -1, 0, EXACT, EXACT_MEAN),
            new Sample("rawpixls-iiq-p25-cf082161.tif", -1, 0, EXACT, EXACT_MEAN),
            new Sample("rawpixls-iiq-p25-cf082166.tif", -1, 0, EXACT, EXACT_MEAN),
            new Sample("rawpixls-iiq-h25-it8.tif", -1, 0, EXACT, EXACT_MEAN),
            new Sample("rawpixls-iiq-h25-outdoor.iiq", -1, 0, EXACT, EXACT_MEAN),
            new Sample("rawpixls-iiq-iq150-cf006803.iiq", 0, 0, EXACT, EXACT_MEAN),
            new Sample("rawpixls-iiq-iq3-100mp-l.iiq", 0, 0, EXACT, EXACT_MEAN),
            new Sample("rawpixls-iiq-iq3-100mp-l16.iiq", 0, 0, EXACT, EXACT_MEAN),
            new Sample("rawpixls-iiq-iq3-100mp-trichromatic.iiq", 0, 0, EXACT, EXACT_MEAN),
            new Sample("rawpixls-iiq-iq4-150mp-l14.iiq", 0, 0, EXACT, EXACT_MEAN),
            new Sample("rawpixls-iiq-iq4-150mp-l16.iiq", 0, 0, EXACT, EXACT_MEAN),
            new Sample("rawpixls-iiq-iq4-150mp-l16-ex.iiq", 0, 0, EXACT, EXACT_MEAN),
            new Sample("rawpixls-iiq-credo40-0001.iiq", 0, 0, 0.60, 2.5),
            new Sample("rawpixls-iiq-credo40-0008.iiq", 0, 0, 0.48, 6),
            new Sample("rawpixls-iiq-credo40-cf003914.iiq", 0, 0, 0.57, 2.5),
            new Sample("rawpixls-iiq-p40plus-cf100602.iiq", 0, 0, 0.37, 8),
            new Sample("rawpixls-iiq-iq140-l.tif", 0, 0, 0.30, 7.5),
            new Sample("rawpixls-iiq-iq180-9561.iiq", 0, 0, IQ180_OFFSET, IQ180_SCALE, EXACT, EXACT_MEAN),
            new Sample("rawpixls-iiq-iq180-9565.iiq", 0, 0, IQ180_OFFSET, IQ180_SCALE, 0.96, 6),
            new Sample("rawpixls-iiq-p30-cf098736.tif", 0, -1, EXACT, EXACT_MEAN),
            new Sample("rawpixls-iiq-p45plus-cf125838.iiq", 0, -1, EXACT, EXACT_MEAN),
            new Sample("rawpixls-iiq-p45plus-cf125839.iiq", 0, -1, EXACT, EXACT_MEAN),
            new Sample("rawpixls-iiq-p45-3809.tif", 0, -1, EXACT, EXACT_MEAN),
            new Sample("rawpixls-iiq-p45-3810.tif", 0, -1, EXACT, EXACT_MEAN),
            new Sample("rawpixls-iiq-p65plus-cf027310.iiq", 0, 0, 0.44, 8),
            new Sample("rawpixls-iiq-p65plus-cf027311.iiq", 0, 0, 0.50, 5),
            new Sample("rawpixls-iiq-p65plus-cf027312.iiq", 0, 0, EXACT, EXACT_MEAN),
            new Sample("rawpixls-iiq-p65plus-cf027313.iiq", 0, 0, EXACT, EXACT_MEAN),
            new Sample("rawpixls-iiq-p65plus-cf028114.iiq", 0, 0, 0.40, 5),
            new Sample("rawpixls-iiq-p40plus-cf100585.iiq", 0, 0, EXACT, EXACT_MEAN),
            new Sample("rawpixls-iiq-iq140-sensorplus-l.tif", 0, 0, EXACT, EXACT_MEAN));

    static Stream<Sample> samples() {
        return SAMPLES.stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("samples")
    @DisplayName("the decoded photosites are as close to the converter's as this back's class states")
    void photosites(Sample sample) throws IOException {
        var stem = sample.name().substring(0, sample.name().lastIndexOf('.'));
        var windows = ORACLE.resolve(stem + ".windows");
        var path = Conformance.fetchListed(sample.name());
        Skip.unless(Files.isRegularFile(windows), "data:raw-oracle",
                "no oracle file " + windows + "; run tools/testdata/raw-oracle.py");
        var limits = MediaLimits.builder().maxPixels(1L << 30).maxBytes(1L << 33).build();
        var file = RawParser.parse(MediaSource.of(path), limits);
        var sensor = file.sensor;
        assertNotNull(sensor, file.unsupported);
        var plane = SensorPlane.of(sensor, limits);
        sensor.reader.read(plane, limits);
        var data = ByteBuffer.wrap(Files.readAllBytes(windows)).order(ByteOrder.LITTLE_ENDIAN);
        data.position(12);
        data.getInt();
        var height = data.getInt();
        var band = data.getInt();
        var count = data.getInt();
        data.position(data.position() + 32 + (height + band - 1) / band * 4 * 8);
        long total = 0, close = 0, sum = 0;
        for (var w = 0; w < count; w++) {
            int left = data.getInt(), top = data.getInt(), width = data.getInt(), rows = data.getInt();
            for (var y = 0; y < rows; y++) {
                for (var x = 0; x < width; x++) {
                    var expected = Math.rint(((data.getShort() & 0xFFFF) - sample.offset()) / sample.scale());
                    var row = sensor.activeArea[0] + sample.down() + top + y;
                    var column = sensor.activeArea[1] + sample.right() + left + x;
                    if (row < 0 || row >= plane.height || column < 0 || column >= plane.width) {
                        continue;
                    }
                    var difference = (long) Math.abs(expected - (plane.integers[row * plane.width + column] & 0xFFFF));
                    total++;
                    sum += difference;
                    if (difference <= 1) {
                        close++;
                    }
                }
            }
        }
        assertTrue(total > 0);
        var share = close / (double) total;
        assertTrue(share >= sample.within(), sample + ": " + share + " of photosites within one unit");
        assertTrue(sum / (double) total <= sample.mean(), sample + ": mean difference " + sum / (double) total);
        assertEquals(0, sensor.black[0] == 0 ? 0 : sensor.black[0] - 1024, sample.toString());
    }
}
