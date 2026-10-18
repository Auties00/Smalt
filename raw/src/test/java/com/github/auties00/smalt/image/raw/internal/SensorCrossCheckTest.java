package com.github.auties00.smalt.image.raw.internal;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.raw.RawSamples;
import com.github.auties00.smalt.io.MediaSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Checks decoders against each other on files that hold the same sensor data: the uncompressed,
 * lossless JPEG and vendor-format versions of one Canon EOS 5D Mark III capture, the DNG files
 * written from the CR2 file by Adobe DNG Converter.
 */
class SensorCrossCheckTest {
    static SensorPlane read(RawSamples sample) {
        try (var source = MediaSource.of(sample.fetch())) {
            var file = RawParser.parse(source, MediaLimits.DEFAULT);
            assertNotNull(file.sensor, file.unsupported);
            var sensor = file.sensor;
            var plane = new SensorPlane(sensor.width, sensor.height, sensor.samplesPerPixel, sensor.floating,
                    MediaLimits.DEFAULT);
            sensor.reader.read(plane, MediaLimits.DEFAULT);
            return plane;
        }
    }

    static void assertSamePlane(SensorPlane expected, SensorPlane actual) {
        assertEquals(expected.width, actual.width, "width");
        assertEquals(expected.height, actual.height, "height");
        assertEquals(expected.samplesPerPixel, actual.samplesPerPixel, "samples per pixel");
        var mismatches = 0;
        var first = -1;
        for (var index = 0; index < expected.integers.length; index++) {
            if (expected.integers[index] != actual.integers[index]) {
                if (first < 0) {
                    first = index;
                }
                mismatches++;
            }
        }
        assertEquals(0, mismatches, "samples differ, first at " + first);
    }

    @Test
    @DisplayName("the lossless JPEG DNG decodes to the samples of the uncompressed DNG")
    void losslessJpegDng() {
        assertSamePlane(read(RawSamples.ADC_5D3_UNCOMPRESSED), read(RawSamples.ADC_5D3_LOSSLESS));
    }

    @Test
    @DisplayName("the CR2 file decodes to the samples Adobe DNG Converter stored from it")
    void cr2() {
        assertSamePlane(read(RawSamples.ADC_5D3_UNCOMPRESSED), read(RawSamples.CR2_5D3));
    }
}
