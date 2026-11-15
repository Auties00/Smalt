package com.github.auties00.smalt.image.raw.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.jpeg.JpegEncoder;
import com.github.auties00.smalt.image.raw.RawDecoder;
import com.github.auties00.smalt.image.raw.RawDecoderOptions;
import com.github.auties00.smalt.image.raw.RawFormat;
import com.github.auties00.smalt.image.raw.VendorTiffWriter;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.fuzz.FuzzSeeds;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the Epson ERF parser on synthetic files built from the layout the CC0 samples show: ten 12-bit samples in
 * each 16-byte block, the black levels and the damaged JPEG preview of the maker note, truncated and damaged files.
 */
class ErfParserTest {
    private static final String FUZZ_TARGET = "com.github.auties00.smalt.image.raw.fuzz.RawFuzzTest";

    static final class Erf {
        int width = 30;
        int height = 6;
        long[] black = {61, 64, 63, 60};
        byte[] preview = jpeg();
        short[] samples;

        Erf samples(long seed) {
            var random = new SplittableRandom(seed);
            samples = new short[width * height];
            for (var index = 0; index < samples.length; index++) {
                samples[index] = (short) random.nextInt(1 << 12);
            }
            return this;
        }

        byte[] strip() {
            var rowBytes = (width + 9) / 10 * 16;
            var data = new byte[rowBytes * height];
            for (var y = 0; y < height; y++) {
                for (var x = 0; x < width; x += 2) {
                    var a = samples[y * width + x] & 0xFFF;
                    var b = samples[y * width + x + 1] & 0xFFF;
                    var at = y * rowBytes + x / 10 * 16 + x % 10 / 2 * 3;
                    data[at] = (byte) (a >> 4);
                    data[at + 1] = (byte) (((a & 0xF) << 4) | (b >> 8));
                    data[at + 2] = (byte) b;
                }
            }
            return data;
        }

        byte[] write() {
            var writer = new VendorTiffWriter();
            writer.little = false;
            writer.make = "SEIKO EPSON CORP.";
            writer.model = "R-D1";
            writer.width = width;
            writer.height = height;
            writer.compression = 32769;
            writer.strip = strip();
            writer.makerNote = offset -> {
                // the header, then a directory whose offsets are relative to the TIFF header
                var entries = new ArrayList<VendorTiffWriter.Entry>();
                entries.add(writer.longs(0x0401, black));
                if (preview != null) {
                    var damaged = preview.clone();
                    damaged[0] = (byte) 0xEE;
                    entries.add(VendorTiffWriter.undefined(0x0280, damaged));
                }
                var note = new ByteArrayOutputStream();
                note.writeBytes(new byte[]{'E', 'P', 'S', 'O', 'N', 0, 1, 0});
                note.writeBytes(VendorTiffWriter.directory(entries, offset + 8, writer.order()));
                return note.toByteArray();
            };
            return writer.write();
        }
    }

    // a small JPEG preview of 40 by 24 pixels
    static byte[] jpeg() {
        return JpegEncoder.encode(RgbaPicture.allocate(40, 24, false));
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
    @DisplayName("decodes the ten samples of each 16-byte block exactly, with the maker note's black levels")
    void decodes() {
        var erf = new Erf().samples(1);
        var file = parse(erf.write());
        assertEquals(RawFormat.ERF, file.format);
        assertNotNull(file.sensor, file.unsupported);
        assertArrayEquals(erf.samples, decode(file));
        assertArrayEquals(new double[]{61, 64, 63, 60}, file.sensor.black);
        assertArrayEquals(new double[]{4095}, file.sensor.white);
        assertTrue(file.sensor.estimateBalance);
        // the sensor is smaller than the camera's image: no crop
        assertNull(file.sensor.defaultCrop);
    }

    @Test
    @DisplayName("reads the maker note's preview, its damaged first byte as 0xFF")
    void preview() {
        var bytes = new Erf().samples(2).write();
        var info = RawDecoder.info(MediaSource.of(bytes));
        assertEquals(1, info.previews().size());
        assertEquals(40, info.previews().getFirst().width());
        assertEquals(24, info.previews().getFirst().height());
        var preview = RawDecoder.extractPreview(MediaSource.of(bytes)).orElseThrow();
        assertEquals(0xFF, preview[0] & 0xFF);
        assertEquals(0xD8, preview[1] & 0xFF);
    }

    @Test
    @DisplayName("decodes a truncated file to its end")
    void truncated() {
        var erf = new Erf().samples(3);
        var bytes = erf.write();
        var decoded = decode(parse(Arrays.copyOf(bytes, bytes.length - 40)));
        var full = erf.width * (erf.height - 1);
        assertArrayEquals(Arrays.copyOf(erf.samples, full), Arrays.copyOf(decoded, full));
    }

    @Test
    @DisplayName("damaged synthetic files only raise MediaException, in bounded time")
    void fuzz() {
        var original = new Erf().samples(4).write();
        var random = new SplittableRandom(22);
        assertTimeoutPreemptively(Duration.ofSeconds(60), () -> {
            for (var round = 0; round < 600; round++) {
                var bytes = original.clone();
                if (round % 3 == 0) {
                    bytes = Arrays.copyOf(bytes, 8 + random.nextInt(bytes.length - 8));
                } else {
                    for (var count = 0; count < 1 + random.nextInt(6); count++) {
                        bytes[4 + random.nextInt(bytes.length - 4)] = (byte) random.nextInt(256);
                    }
                }
                var input = bytes;
                var limits = MediaLimits.builder().maxPixels(1 << 20).build();
                try {
                    RawDecoder.info(MediaSource.of(input), limits);
                    RawDecoder.extractPreview(MediaSource.of(input), limits);
                    RawDecoder.decode(MediaSource.of(input), RawDecoderOptions.builder().limits(limits).build());
                } catch (MediaException expected) {
                    // a damaged file may be rejected
                }
            }
        });
    }

    @Test
    @EnabledIfSystemProperty(named = "smalt.fuzz.writeSeeds", matches = "true",
            disabledReason = "[opt-in:fuzz-seeds] runs only with -Dsmalt.fuzz.writeSeeds=true")
    @DisplayName("writes the seeds of the ERF fuzz target")
    void fuzzSeeds() {
        FuzzSeeds.write(FUZZ_TARGET, "erf", "valid-synthetic.erf", new Erf().samples(1).write());
    }
}
