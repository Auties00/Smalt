package com.github.auties00.smalt.image.raw.internal;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.raw.RawSamples;
import com.github.auties00.smalt.io.MediaSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the decoding of a Nikon small raw image (NEFCompression 8, the CC0 D810 sample) against the DNG file Adobe
 * DNG Converter 18.0, run as a black box ({@code -u}), writes for it: a linear raw image of the same 3680x2456 pixels
 * whose stored red, green and blue samples, row by row as little-endian 16-bit words, have the SHA-256 below, with a
 * 65536-entry linearization table, no black level, a white level of 65535, the maker note's red and blue multipliers
 * (2.160156 and 1.183594) as the analog balance and a neutral as-shot white balance. The digest, the sample values and
 * the table entries are the converter's, read from its file.
 */
class NikonSmallRawOracleTest {
    private static final String SAMPLES_SHA256 = "3f70a1847defb6878ba14e529497717902d88c300ae424d3437a46dea3ca12a1";

    // the converter's linearization table at every 4096th entry, then its last entry
    private static final int[] TABLE = {0, 722, 1573, 2676, 4137, 6040, 8445, 11385, 14870, 18891, 23432, 28489, 34090,
            40326, 47388, 55612, 65535};

    private static RawFile parse() {
        try (var source = MediaSource.of(RawSamples.NEF_D810_SMALL_RAW.path())) {
            var file = RawParser.parse(source, MediaLimits.DEFAULT);
            assertNull(file.unsupported, file.unsupported);
            assertNotNull(file.sensor);
            return file;
        }
    }

    @Test
    @DisplayName("the stored red, green and blue samples equal the converter's")
    void samples() throws NoSuchAlgorithmException {
        SensorPlane plane;
        try (var source = MediaSource.of(RawSamples.NEF_D810_SMALL_RAW.path())) {
            var sensor = RawParser.parse(source, MediaLimits.DEFAULT).sensor;
            assertEquals(3680, sensor.width);
            assertEquals(2456, sensor.height);
            assertEquals(3, sensor.samplesPerPixel);
            assertNull(sensor.cfa);
            plane = new SensorPlane(sensor.width, sensor.height, 3, false, MediaLimits.DEFAULT);
            sensor.reader.read(plane, MediaLimits.DEFAULT);
        }
        var samples = plane.integers;
        assertArrayEquals(new short[]{9668, 8468, 7597, 9572, 8372, 7501, 9750, 8323, 7273, 9686, 8259, 7209},
                Arrays.copyOf(samples, 12), "the first four pixels");
        var center = (1228 * 3680 + 1840) * 3;
        assertArrayEquals(new short[]{32071, 30391, 28154}, Arrays.copyOfRange(samples, center, center + 3));
        var bytes = new byte[samples.length * 2];
        for (var index = 0; index < samples.length; index++) {
            bytes[2 * index] = (byte) samples[index];
            bytes[2 * index + 1] = (byte) (samples[index] >>> 8);
        }
        assertEquals(SAMPLES_SHA256, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
    }

    @Test
    @DisplayName("the levels, linearization and white balance are the converter's")
    void levels() {
        var file = parse();
        var sensor = file.sensor;
        assertNull(sensor.black);
        assertArrayEquals(new double[]{65535, 65535, 65535}, sensor.white);
        assertEquals(65536, sensor.linearization.length);
        for (var index = 0; index < TABLE.length; index++) {
            var entry = Math.min(index * 4096, 65535);
            // the converter's table is round(65535 f(i / 65535)) of the decoder's polynomial at all but 33 entries,
            // rounding ties where the two differ by one
            assertEquals(TABLE[index], sensor.linearization[entry], 1, "entry " + entry);
        }
        for (var index = 1; index < sensor.linearization.length; index++) {
            assertTrue(sensor.linearization[index] >= sensor.linearization[index - 1], "the table is monotonic");
        }
        assertArrayEquals(new double[]{2.160156, 1, 1.183594}, file.color.analogBalance, 1e-6);
        assertArrayEquals(new double[]{1, 1, 1}, file.color.asShotNeutral);
    }
}
