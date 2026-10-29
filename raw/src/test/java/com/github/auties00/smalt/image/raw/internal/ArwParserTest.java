package com.github.auties00.smalt.image.raw.internal;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.raw.RawFormat;
import com.github.auties00.smalt.image.raw.VendorTiffWriter;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.fuzz.FuzzSeeds;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.Arrays;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the Sony ARW parser on synthetic files of compression 32767 built from the layout of the DSLR-A900 sample:
 * 12-bit samples packed in pairs into three bytes least significant bits first, with the A900's levels and image
 * area, and the rejection of the variable-length coding, which stores neither one nor one and a half bytes per
 * photosite.
 */
class ArwParserTest {
    private static final String FUZZ_TARGET = "com.github.auties00.smalt.image.raw.fuzz.RawFuzzTest";

    static final class Arw {
        int width = 20;
        int height = 10;
        String model = "DSLR-A900";
        short[] samples;
        byte[] strip;

        Arw samples(long seed) {
            var random = new SplittableRandom(seed);
            samples = new short[width * height];
            for (var index = 0; index < samples.length; index++) {
                samples[index] = (short) random.nextInt(4096);
            }
            strip = new byte[width * height * 3 / 2];
            for (var index = 0; index < samples.length; index += 2) {
                var a = samples[index];
                var b = samples[index + 1];
                var at = index / 2 * 3;
                strip[at] = (byte) a;
                strip[at + 1] = (byte) ((a >> 8) | (b << 4));
                strip[at + 2] = (byte) (b >> 4);
            }
            return this;
        }

        byte[] write() {
            var writer = new VendorTiffWriter();
            writer.make = "SONY";
            writer.model = model;
            writer.width = width;
            writer.height = height;
            writer.compression = 32767;
            writer.strip = strip;
            return writer.write();
        }
    }

    private static RawFile parse(byte[] bytes) {
        return RawParser.parse(MediaSource.of(bytes), MediaLimits.DEFAULT);
    }

    private static short[] decode(RawFile file) {
        var sensor = file.sensor;
        var plane = new SensorPlane(sensor.width, sensor.height, 1, false, MediaLimits.DEFAULT);
        sensor.reader.read(plane, MediaLimits.DEFAULT);
        return plane.integers;
    }

    @Test
    @DisplayName("decodes 12-bit samples packed least significant bits first exactly, with the 12-bit levels")
    void packed() {
        var arw = new Arw().samples(1);
        var file = parse(arw.write());
        assertEquals(RawFormat.ARW, file.format);
        assertNotNull(file.sensor, file.unsupported);
        assertEquals(12, file.sensor.bitsPerSample);
        assertEquals("uncompressed", file.sensor.compression);
        assertArrayEquals(arw.samples, decode(file));
        assertArrayEquals(new double[]{128}, file.sensor.black);
        assertArrayEquals(new double[]{4095}, file.sensor.white);
        assertTrue(file.sensor.estimateBalance);
        // the A900's image area does not fit this sensor and the file has no EXIF size: no crop
        assertNull(file.sensor.defaultCrop);
    }

    @Test
    @DisplayName("gives a full-size A900 file the converter's image area")
    void crop() {
        var arw = new Arw();
        arw.width = 6080;
        arw.height = 4048;
        arw.samples(2);
        var file = parse(arw.write());
        assertArrayEquals(new double[]{12, 8, 6048, 4032}, file.sensor.defaultCrop);
        arw.model = "DSLR-A850";
        assertNull(parse(arw.write()).sensor.defaultCrop);
    }

    @Test
    @DisplayName("decodes a truncated packed file to its end")
    void truncated() {
        var arw = new Arw().samples(3);
        var bytes = arw.write();
        var decoded = decode(parse(Arrays.copyOf(bytes, bytes.length - 40)));
        var full = arw.width * (arw.height - 2);
        assertArrayEquals(Arrays.copyOf(arw.samples, full), Arrays.copyOf(decoded, full));
    }

    @Test
    @DisplayName("rejects the variable-length coding, whose data is neither one nor one and a half bytes per "
            + "photosite, and keeps reading the one byte per photosite of cRAW")
    void codings() {
        var arw = new Arw().samples(4);
        arw.strip = Arrays.copyOf(arw.strip, arw.width * arw.height * 6 / 5);
        var file = parse(arw.write());
        assertNull(file.sensor);
        assertTrue(file.unsupported.contains("variable-length"), file.unsupported);
        arw.width = 32;
        arw.height = 6;
        arw.strip = new byte[arw.width * arw.height];
        assertEquals("Sony cRAW", parse(arw.write()).sensor.compression);
    }

    @Test
    @EnabledIfSystemProperty(named = "smalt.fuzz.writeSeeds", matches = "true",
            disabledReason = "[opt-in:fuzz-seeds] runs only with -Dsmalt.fuzz.writeSeeds=true")
    @DisplayName("writes the packed seed of the ARW fuzz target")
    void fuzzSeeds() {
        FuzzSeeds.write(FUZZ_TARGET, "arw", "valid-packed-12.arw", new Arw().samples(1).write());
    }
}
