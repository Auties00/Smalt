package com.github.auties00.smalt.image.raw.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.raw.RawDecoder;
import com.github.auties00.smalt.image.raw.RawDecoderOptions;
import com.github.auties00.smalt.image.raw.RawFormat;
import com.github.auties00.smalt.image.raw.VendorTiffWriter;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.fuzz.FuzzSeeds;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the Mamiya MEF parser on synthetic files built from the layout of the ZD sample: 12-bit samples packed most
 * significant bit first, and a maker note whose next directory holds the white balance mode, its fine-tuning, the
 * table of multipliers they select from and the camera's multipliers. The table entries each mode and fine-tuning
 * select are those Adobe DNG Converter chose for copies of the ZD sample with those values; truncated and damaged
 * files are covered too.
 */
class MefParserTest {
    private static final String FUZZ_TARGET = "com.github.auties00.smalt.image.raw.fuzz.RawFuzzTest";

    static final class Mef {
        int width = 16;
        int height = 12;
        short[] samples;
        int mode;
        int fineTuning;
        long[] multipliers = {310, 256, 256, 348};
        boolean balance = true;

        Mef samples(long seed) {
            var random = new SplittableRandom(seed);
            samples = new short[width * height];
            for (var index = 0; index < samples.length; index++) {
                samples[index] = (short) random.nextInt(4001);
            }
            return this;
        }

        byte[] strip() {
            var rowBytes = width * 3 / 2;
            var data = new byte[rowBytes * height];
            for (var y = 0; y < height; y++) {
                for (var x = 0; x < width; x += 2) {
                    var a = samples[y * width + x] & 0xFFF;
                    var b = samples[y * width + x + 1] & 0xFFF;
                    var at = y * rowBytes + x / 2 * 3;
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
            writer.make = "Mamiya-OP Co.,Ltd.";
            writer.model = "MAMIYA ZD";
            writer.width = width;
            writer.height = height;
            writer.strip = strip();
            writer.makerNote = offset -> {
                var first = VendorTiffWriter.directory(List.of(VendorTiffWriter.ascii(0x81, "Mamiya")), offset,
                        writer.order());
                if (!balance) {
                    return first;
                }
                var entries = new ArrayList<VendorTiffWriter.Entry>();
                entries.add(writer.shorts(0xA2, mode));
                entries.add(writer.shorts(0xA3, fineTuning));
                entries.add(VendorTiffWriter.undefined(0xB2, table()));
                var camera = ByteBuffer.allocate(16);
                for (var value : multipliers) {
                    camera.putInt((int) value);
                }
                entries.add(VendorTiffWriter.undefined(0xB3, camera.array()));
                var second = VendorTiffWriter.directory(entries, offset + first.length, writer.order());
                // the maker note's directory links to the white balance directory as its next one
                ByteBuffer.wrap(first).putInt(2 + 12, (int) (offset + first.length));
                var note = Arrays.copyOf(first, first.length + second.length);
                System.arraycopy(second, 0, note, first.length, second.length);
                return note;
            };
            return writer.write();
        }
    }

    // the multipliers of table entry i: red 200 + i, greens 300 + 2 i, blue 400 - i, distinct for every entry
    static long[] entry(int index) {
        return new long[]{200 + index, 300 + 2 * index, 300 + 2 * index, 400 - index};
    }

    static byte[] table() {
        var table = ByteBuffer.allocate(136 * 16);
        for (var index = 0; index < 136; index++) {
            for (var value : entry(index)) {
                table.putInt((int) value);
            }
        }
        return table.array();
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
    @DisplayName("decodes the packed 12-bit samples exactly, with the ZD's levels, crop and white balance")
    void decodes() {
        var mef = new Mef().samples(1);
        var file = parse(mef.write());
        assertEquals(RawFormat.MEF, file.format);
        assertNotNull(file.sensor, file.unsupported);
        assertArrayEquals(mef.samples, decode(file));
        assertEquals("RGGB", file.sensor.cfa.toString());
        assertArrayEquals(new double[]{0}, file.sensor.black);
        assertArrayEquals(new double[]{4000}, file.sensor.white);
        assertArrayEquals(new double[]{4, 4, 8, 4}, file.sensor.defaultCrop);
        // mode 0 without fine-tuning selects entry 3; each ratio is the camera's green-to-color one times the entry's
        var selected = entry(3);
        assertArrayEquals(new double[]{256.0 / 310 * selected[1] / selected[0], 1, 256.0 / 348 * selected[1] / selected[3]},
                file.color.asShotNeutral, 1e-12);
    }

    @ParameterizedTest(name = "mode {0}, fine-tuning {1}: entry {2}")
    @CsvSource({"0, 0, 3", "0, 1, 4", "0, 2, 5", "0, 3, 6", "0, 4, 3", "0, 5, 2", "0, 6, 1", "0, 7, 0", "0, 8, 3",
            "0, 9, 4", "0, 13, 2", "0, 65535, 0", "0, 65534, 1", "0, 65533, 2", "0, 65532, 3", "1, 0, 10", "2, 0, 17",
            "3, 0, 24", "4, 0, 31", "5, 0, 38", "5, 2, 40", "6, 0, 45", "6, 7, 42", "8, 0, 59", "8, 7, 56", "9, 0, 3",
            "10, 0, 3", "12, 0, 3", "20, 0, 3", "25, 0, 3", "27, 0, 3", "28, 0, 63", "30, 0, 65", "35, 0, 70",
            "40, 0, 75", "40, 1, 75", "45, 0, 80", "50, 0, 85", "60, 0, 95", "63, 0, 98", "70, 0, 105", "100, 0, 135",
            "101, 0, 3", "65535, 0, 3"})
    @DisplayName("a white balance mode and fine-tuning select the table entry the converter selects for them")
    void selection(int mode, int fineTuning, int expected) {
        assertEquals(expected, MefParser.entry(mode, fineTuning));
        var mef = new Mef().samples(2);
        mef.mode = mode;
        mef.fineTuning = fineTuning;
        var neutral = parse(mef.write()).color.asShotNeutral;
        var selected = entry(expected);
        assertArrayEquals(new double[]{256.0 / 310 * selected[1] / selected[0], 1, 256.0 / 348 * selected[1] / selected[3]},
                neutral, 1e-12);
    }

    @Test
    @DisplayName("takes the camera's multipliers as unity when one is zero, and the mean of their greens")
    void multipliers() {
        var mef = new Mef().samples(3);
        mef.multipliers = new long[]{0, 256, 256, 348};
        var selected = entry(3);
        assertArrayEquals(new double[]{(double) selected[1] / selected[0], 1, (double) selected[1] / selected[3]},
                parse(mef.write()).color.asShotNeutral, 1e-12);
        mef.multipliers = new long[]{310, 512, 256, 348};
        assertArrayEquals(new double[]{384.0 / 310 * selected[1] / selected[0], 1, 384.0 / 348 * selected[1] / selected[3]},
                parse(mef.write()).color.asShotNeutral, 1e-12);
    }

    @Test
    @DisplayName("estimates the white balance of a file whose maker note has no white balance directory")
    void noBalance() {
        var mef = new Mef().samples(4);
        mef.balance = false;
        var file = parse(mef.write());
        assertNull(file.color.asShotNeutral);
        assertTrue(file.sensor.estimateBalance);
        assertArrayEquals(mef.samples, decode(file));
    }

    @Test
    @DisplayName("decodes a truncated file to its end")
    void truncated() {
        var mef = new Mef().samples(5);
        var bytes = mef.write();
        var decoded = decode(parse(Arrays.copyOf(bytes, bytes.length - 30)));
        var full = mef.width * (mef.height - 2);
        assertArrayEquals(Arrays.copyOf(mef.samples, full), Arrays.copyOf(decoded, full));
    }

    @Test
    @DisplayName("damaged synthetic files only raise MediaException, in bounded time")
    void fuzz() {
        var original = new Mef().samples(6).write();
        var random = new SplittableRandom(23);
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
    @DisplayName("writes the seeds of the MEF fuzz target")
    void fuzzSeeds() {
        FuzzSeeds.write(FUZZ_TARGET, "mef", "valid-synthetic.mef", new Mef().samples(1).write());
    }
}
