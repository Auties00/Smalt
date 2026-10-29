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
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the Samsung SRW parser on synthetic files built from the layouts the CC0 samples show (16-bit words,
 * 12-bit samples packed least or most significant bit first, the maker note's levels stored plus the key, the
 * valid sensor area and the white levels of the models), the variants it rejects, truncated and damaged files.
 */
class SrwParserTest {
    private static final String FUZZ_TARGET = "com.github.auties00.smalt.image.raw.fuzz.RawFuzzTest";

    // the maker note key of every CC0 sample
    private static final long[] KEY = {305, 72, 737, 456, 282, 307, 519, 724, 13, 505, 193};

    static final class Srw {
        int width = 16;
        int height = 8;
        int bits = 12;
        int compression = 32770;
        boolean little = true;
        Long byteOrder = 0L;
        String model = "NX1000";
        byte[] cfa = {1, 0, 2, 1};
        long[] black = {3, 2, 2, 3};
        long[] balance = {10480, 4096, 4096, 7664};
        long[] areas = {0, 0, 16, 8, 2, 2, 14, 6};
        short[] samples;
        int dropBytes;

        Srw samples(long seed) {
            var random = new SplittableRandom(seed);
            samples = new short[width * height];
            for (var index = 0; index < samples.length; index++) {
                samples[index] = (short) random.nextInt(1 << bits);
            }
            return this;
        }

        byte[] strip() {
            if (compression == 32769) {
                var buffer = ByteBuffer.allocate(samples.length * 2).order(ByteOrder.LITTLE_ENDIAN);
                for (var sample : samples) {
                    buffer.putShort(sample);
                }
                return buffer.array();
            }
            var data = new byte[samples.length / 2 * 3];
            var msb = byteOrder == null ? !little : byteOrder == 1;
            for (var pair = 0; pair < samples.length / 2; pair++) {
                var a = samples[2 * pair] & 0xFFF;
                var b = samples[2 * pair + 1] & 0xFFF;
                if (msb) {
                    data[3 * pair] = (byte) (a >> 4);
                    data[3 * pair + 1] = (byte) (((a & 0xF) << 4) | (b >> 8));
                    data[3 * pair + 2] = (byte) b;
                } else {
                    data[3 * pair] = (byte) a;
                    data[3 * pair + 1] = (byte) ((a >> 8) | ((b & 0xF) << 4));
                    data[3 * pair + 2] = (byte) (b >> 4);
                }
            }
            return data;
        }

        byte[] write() {
            var writer = new VendorTiffWriter();
            writer.little = little;
            writer.make = "SAMSUNG";
            writer.model = model;
            writer.width = width;
            writer.height = height;
            writer.bits = bits;
            writer.samplesPerPixel = 3;
            writer.compression = compression;
            // Samsung's raw directories state no photometric interpretation
            writer.photometric = 0;
            writer.cfa = cfa;
            var strip = strip();
            writer.strip = Arrays.copyOf(strip, strip.length - dropBytes);
            writer.makerNote = offset -> {
                var entries = new ArrayList<VendorTiffWriter.Entry>();
                if (byteOrder != null) {
                    entries.add(writer.longs(0x0040, byteOrder));
                }
                entries.add(writer.longs(0xA010, areas));
                entries.add(writer.longs(0xA020, KEY));
                var wb = new long[4];
                var levels = new long[4];
                for (var index = 0; index < 4; index++) {
                    wb[index] = balance[index] + KEY[index];
                    levels[index] = black[index] + KEY[index];
                }
                entries.add(writer.longs(0xA021, wb));
                entries.add(writer.slongs(0xA028, levels));
                return VendorTiffWriter.directory(entries, 0, writer.order());
            };
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

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"words", "packed-lsb-first", "packed-msb-first", "packed-big-endian-tagged"})
    @DisplayName("decodes the 16-bit words and both packings exactly")
    void layouts(String layout) {
        var srw = new Srw();
        switch (layout) {
            case "words" -> {
                srw.compression = 32769;
                srw.bits = 14;
                srw.little = false;
            }
            case "packed-msb-first" -> {
                srw.little = false;
                srw.byteOrder = null;
            }
            case "packed-big-endian-tagged" -> srw.byteOrder = 1L;
            default -> {
            }
        }
        var file = parse(srw.samples(layout.length()).write());
        assertEquals(RawFormat.SRW, file.format);
        assertNotNull(file.sensor, file.unsupported);
        assertArrayEquals(srw.samples, decode(file));
    }

    @Test
    @DisplayName("keeps the whole 16-bit word of a sample above the bit depth")
    void wholeWords() {
        var srw = new Srw();
        srw.compression = 32769;
        srw.bits = 14;
        srw.samples(9);
        srw.samples[3] = (short) 0xFFFF;
        assertArrayEquals(srw.samples, decode(parse(srw.write())));
    }

    @Test
    @DisplayName("reads the levels and the white balance less the key, in the color filter array's order")
    void levels() {
        var srw = new Srw().samples(1);
        srw.black = new long[]{3, 1, 2, 4};
        var file = parse(srw.write());
        // GRBG: the first green takes the first green level
        assertArrayEquals(new double[]{1, 3, 4, 2}, file.sensor.black);
        assertEquals(2, file.sensor.blackRows);
        assertEquals(2, file.sensor.blackColumns);
        assertArrayEquals(new double[]{4096.0 / 10480, 1, 4096.0 / 7664}, file.color.asShotNeutral, 1e-12);
        assertArrayEquals(new double[]{2, 2, 12, 4}, file.sensor.defaultCrop);
        assertArrayEquals(new double[]{3750}, file.sensor.white);
        assertFalse(file.sensor.estimateBalance);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"NX mini", "NX500", "NX300", "EX1", "WB2000", "NX1"})
    @DisplayName("takes the white level of the model")
    void whites(String model) {
        var srw = new Srw();
        srw.model = model;
        srw.bits = model.equals("EX1") || model.equals("WB2000") || model.equals("NX1") ? 14 : 12;
        if (srw.bits == 14) {
            srw.compression = 32769;
        }
        var expected = switch (model) {
            case "NX mini", "NX500" -> 4000;
            case "EX1" -> 15600;
            case "WB2000" -> 3700;
            case "NX1" -> 16100;
            default -> 4095;
        };
        assertArrayEquals(new double[]{expected}, parse(srw.samples(2).write()).sensor.white);
    }

    @Test
    @DisplayName("ignores a sensor area that does not fit the image")
    void areas() {
        var srw = new Srw();
        srw.areas = new long[]{0, 0, 16, 8, 2, 2, 18, 6};
        assertNull(parse(srw.samples(3).write()).sensor.defaultCrop);
    }

    @ParameterizedTest(name = "compression {0}")
    @ValueSource(ints = {32770, 32772, 32773})
    @DisplayName("rejects Samsung's compressions as unsupported")
    void compressed(int compression) {
        var srw = new Srw();
        srw.compression = compression;
        srw.dropBytes = compression == 32770 ? 10 : 0;
        var bytes = srw.samples(4).write();
        var info = RawDecoder.info(MediaSource.of(bytes));
        assertEquals(RawFormat.SRW, info.format());
        assertFalse(info.isDecodable());
        assertThrows(MediaException.Unsupported.class, () -> RawDecoder.decodeRaw(MediaSource.of(bytes)));
    }

    @Test
    @DisplayName("decodes a truncated file to its end")
    void truncated() {
        var srw = new Srw().samples(5);
        var bytes = srw.write();
        var decoded = decode(parse(Arrays.copyOf(bytes, bytes.length - 30)));
        var full = srw.width * (srw.height - 2);
        assertArrayEquals(Arrays.copyOf(srw.samples, full), Arrays.copyOf(decoded, full));
    }

    @Test
    @DisplayName("damaged synthetic files only raise MediaException, in bounded time")
    void fuzz() {
        var words = new Srw();
        words.compression = 32769;
        words.bits = 14;
        var originals = new byte[][]{new Srw().samples(6).write(), words.samples(7).write()};
        var random = new SplittableRandom(21);
        assertTimeoutPreemptively(Duration.ofSeconds(60), () -> {
            for (var round = 0; round < 600; round++) {
                var bytes = originals[round % 2].clone();
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
                    RawDecoder.decodeRaw(MediaSource.of(input), RawDecoderOptions.builder().limits(limits).build());
                    RawDecoder.decode(MediaSource.of(input), RawDecoderOptions.builder().limits(limits).build());
                } catch (MediaException expected) {
                    // a damaged file may be rejected
                }
            }
        });
    }

    @Test
    @DisplayName("renders a synthetic file")
    void renders() {
        var srw = new Srw();
        srw.width = 64;
        srw.height = 48;
        srw.areas = new long[]{0, 0, 64, 48, 4, 4, 60, 44};
        var picture = RawDecoder.decode(MediaSource.of(srw.samples(8).write())).picture();
        assertEquals(56, picture.width());
        assertEquals(40, picture.height());
        assertTrue(RawDecoder.info(MediaSource.of(srw.write())).isDecodable());
    }

    @Test
    @EnabledIfSystemProperty(named = "smalt.fuzz.writeSeeds", matches = "true",
            disabledReason = "[opt-in:fuzz-seeds] runs only with -Dsmalt.fuzz.writeSeeds=true")
    @DisplayName("writes the seeds of the SRW fuzz target")
    void fuzzSeeds() {
        FuzzSeeds.write(FUZZ_TARGET, "srw", "valid-packed-lsb-first.srw", new Srw().samples(1).write());
        var msb = new Srw();
        msb.little = false;
        msb.byteOrder = null;
        FuzzSeeds.write(FUZZ_TARGET, "srw", "valid-packed-msb-first.srw", msb.samples(2).write());
        var words = new Srw();
        words.compression = 32769;
        words.bits = 14;
        FuzzSeeds.write(FUZZ_TARGET, "srw", "valid-words.srw", words.samples(3).write());
    }
}
