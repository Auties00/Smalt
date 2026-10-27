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

import java.io.ByteArrayOutputStream;
import java.time.Duration;
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
 * Checks the Hasselblad 3FR and FFF parser and its predictor-8 lossless JPEG decoder on synthetic files encoded
 * with the layout the CC0 samples show: pairs of Huffman-coded categories followed by their extra bits, packed in
 * 32-bit little-endian words, category 16 followed by 16 unused bits; and the levels of the signed and the FFF
 * files. The CC0 samples themselves are checked against Adobe DNG Converter in {@link VendorOracleTest}.
 */
class HasselbladParserTest {
    private static final String FUZZ_TARGET = "com.github.auties00.smalt.image.raw.fuzz.RawFuzzTest";

    // the Huffman table every CC0 sample but one carries: code counts by length, then the categories
    private static final int[] COUNTS = {0, 2, 1, 4, 2, 3, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0};
    private static final int[] CATEGORIES = {6, 7, 5, 4, 8, 9, 10, 3, 11, 1, 2, 12, 0, 13, 14, 15, 16};

    /**
     * An encoder of predictor-8 streams.
     */
    static final class Stream {
        private long buffer;
        private int count;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final int[] code = new int[17];
        private final int[] length = new int[17];

        Stream() {
            var next = 0;
            var index = 0;
            for (var bits = 1; bits <= 16; bits++) {
                for (var k = 0; k < COUNTS[bits - 1]; k++) {
                    code[CATEGORIES[index]] = next++;
                    length[CATEGORIES[index]] = bits;
                    index++;
                }
                next <<= 1;
            }
        }

        private void put(int value, int bits) {
            for (var bit = bits - 1; bit >= 0; bit--) {
                buffer = buffer << 1 | (value >>> bit & 1);
                if (++count == 8) {
                    bytes.write((int) buffer & 0xFF);
                    count = 0;
                    buffer = 0;
                }
            }
        }

        private static int category(int difference) {
            if (difference == 32768) {
                return 16;
            }
            var magnitude = Math.abs(difference);
            return magnitude == 0 ? 0 : 32 - Integer.numberOfLeadingZeros(magnitude);
        }

        private void extra(int difference, int category) {
            if (category == 16) {
                put(0xFFFF, 16);
            } else if (category > 0) {
                put(difference < 0 ? difference + (1 << category) - 1 : difference, category);
            }
        }

        // encodes photosites of up to 16 bits, row by row, into the stream's entropy-coded data
        static byte[] encode(int[] photosites, int width, int height, int predictor, int components) {
            var stream = new Stream();
            var differences = new int[photosites.length];
            for (var y = 0; y < height; y++) {
                for (var x = 0; x < width; x++) {
                    var predicted = x < 2 ? 32768 : photosites[y * width + x - 2];
                    var difference = (photosites[y * width + x] - predicted) & 0xFFFF;
                    differences[y * width + x] = difference > 32768 ? difference - 65536 : difference;
                }
            }
            for (var index = 0; index < differences.length; index += 2) {
                var first = category(differences[index]);
                var second = category(differences[index + 1]);
                stream.put(stream.code[first], stream.length[first]);
                stream.put(stream.code[second], stream.length[second]);
                stream.extra(differences[index], first);
                stream.extra(differences[index + 1], second);
            }
            stream.put(0, (8 - stream.count) % 8);
            while (stream.bytes.size() % 4 != 0) {
                stream.bytes.write(0);
            }
            var entropy = stream.bytes.toByteArray();
            for (var at = 0; at < entropy.length; at += 4) {
                for (var k = 0; k < 2; k++) {
                    var swap = entropy[at + k];
                    entropy[at + k] = entropy[at + 3 - k];
                    entropy[at + 3 - k] = swap;
                }
            }
            var out = new ByteArrayOutputStream();
            out.writeBytes(new byte[]{(byte) 0xFF, (byte) 0xD8});
            out.writeBytes(new byte[]{(byte) 0xFF, (byte) 0xC3, 0, (byte) (8 + 3 * components), 16,
                    (byte) (height >> 8), (byte) height, (byte) (width >> 8), (byte) width, (byte) components});
            for (var c = 0; c < components; c++) {
                out.writeBytes(new byte[]{(byte) c, 0x11, 0});
            }
            out.write(0xFF);
            out.writeBytes(new byte[]{(byte) 0xFF, (byte) 0xC4, 0, (byte) (2 + 17 + CATEGORIES.length), 0});
            for (var count : COUNTS) {
                out.write(count);
            }
            for (var category : CATEGORIES) {
                out.write(category);
            }
            out.writeBytes(new byte[]{(byte) 0xFF, (byte) 0xDA, 0, (byte) (6 + 2 * components), (byte) components});
            for (var c = 0; c < components; c++) {
                out.writeBytes(new byte[]{(byte) c, 0});
            }
            out.writeBytes(new byte[]{(byte) predictor, 0, 0});
            out.writeBytes(entropy);
            out.writeBytes(new byte[]{(byte) 0xFF, (byte) 0xD9});
            return out.toByteArray();
        }
    }

    /**
     * A synthetic 3FR file.
     */
    static final class File {
        int width = 40;
        int height = 12;
        int predictor = HasselbladJpeg.PREDICTOR;
        int components = 1;
        int white = 65535;
        int black = -1;
        int[] photosites;

        File photosites(long seed) {
            var random = new SplittableRandom(seed);
            photosites = new int[width * height];
            for (var index = 0; index < photosites.length; index++) {
                // mostly small steps, with jumps across the whole range, which need category 16
                photosites[index] = switch (random.nextInt(8)) {
                    case 0 -> random.nextInt(65536);
                    case 1 -> random.nextBoolean() ? 0 : 65535;
                    default -> 20000 + random.nextInt(-300, 300);
                };
            }
            return this;
        }

        byte[] strip() {
            return Stream.encode(photosites, width, height, predictor, components);
        }

        byte[] write() {
            var writer = new VendorTiffWriter();
            writer.make = "Hasselblad";
            writer.model = "Hasselblad H3D";
            writer.width = width;
            writer.height = height;
            writer.bits = 16;
            writer.compression = 7;
            writer.strip = strip();
            writer.rawEntries.add(writer.shorts(50717, white));
            writer.rawEntries.add(writer.shorts(50719, 4, 2));
            writer.rawEntries.add(writer.shorts(50720, width - 8, height - 4));
            if (black >= 0) {
                writer.rawEntries.add(writer.longs(50714, black));
            }
            return writer.write();
        }
    }

    private static RawFile parse(byte[] data) {
        return RawParser.parse(MediaSource.of(data), MediaLimits.DEFAULT);
    }

    private static int[] read(RawFile file) {
        assertNotNull(file.sensor, file.unsupported);
        var plane = SensorPlane.of(file.sensor, MediaLimits.DEFAULT);
        file.sensor.reader.read(plane, MediaLimits.DEFAULT);
        var out = new int[plane.width * plane.height];
        for (var index = 0; index < out.length; index++) {
            out[index] = plane.integers[index] & 0xFFFF;
        }
        return out;
    }

    @ParameterizedTest(name = "seed {0}")
    @ValueSource(longs = {1, 2, 3, 4})
    @DisplayName("a predictor-8 stream decodes to the photosites it encodes, jumps of category 16 included")
    void roundTrip(long seed) {
        var synthetic = new File().photosites(seed);
        var file = parse(synthetic.write());
        assertEquals(RawFormat.FFF, file.format);
        assertEquals(HasselbladParser.COMPRESSION, file.sensor.compression);
        assertArrayEquals(synthetic.photosites, read(file));
        assertEquals("RGGB", file.sensor.cfa.toString());
        assertArrayEquals(new double[]{65535}, file.sensor.white);
        assertNull(file.sensor.black);
        assertArrayEquals(new double[]{4, 2, 32, 8}, file.sensor.defaultCrop);
    }

    @Test
    @DisplayName("a frame of 2 photosites per row and one of a single row decode")
    void smallest() {
        for (var size : new int[][]{{2, 1}, {2, 5}, {6, 1}}) {
            var synthetic = new File();
            synthetic.width = size[0];
            synthetic.height = size[1];
            synthetic.photosites(size[0] * 31L + size[1]);
            var data = synthetic.strip();
            var jpeg = new HasselbladJpeg(data, 0, data.length);
            var out = new short[size[0] * size[1]];
            jpeg.decode(data, data.length, false, out, size[0], size[1]);
            for (var index = 0; index < out.length; index++) {
                assertEquals(synthetic.photosites[index], out[index] & 0xFFFF);
            }
        }
    }

    @Test
    @DisplayName("the signed photosites of a file whose white level is 32767 are stored plus 1024, the black level")
    void signed() {
        var synthetic = new File();
        synthetic.white = 32767;
        synthetic.photosites = new int[synthetic.width * synthetic.height];
        var random = new SplittableRandom(9);
        var expected = new int[synthetic.photosites.length];
        for (var index = 0; index < expected.length; index++) {
            var value = index == 0 ? -1500 : index == 1 ? 32767 : random.nextInt(-40, 3000);
            synthetic.photosites[index] = value & 0xFFFF;
            expected[index] = Math.max(0, value + HasselbladJpeg.SIGNED_OFFSET);
        }
        var file = parse(synthetic.write());
        assertArrayEquals(expected, read(file));
        assertArrayEquals(new double[]{HasselbladJpeg.SIGNED_OFFSET}, file.sensor.black);
        assertArrayEquals(new double[]{32767 + HasselbladJpeg.SIGNED_OFFSET}, file.sensor.white);
    }

    @Test
    @DisplayName("a file whose raw directory states a black level gets the converter's white level of 62914")
    void processed() {
        var synthetic = new File().photosites(5);
        synthetic.black = 256;
        var file = parse(synthetic.write());
        assertArrayEquals(synthetic.photosites, read(file));
        assertArrayEquals(new double[]{256}, file.sensor.black);
        assertArrayEquals(new double[]{62914}, file.sensor.white);
    }

    @Test
    @DisplayName("a truncated stream decodes its missing photosites from zero bits without failing")
    void truncated() {
        var synthetic = new File().photosites(6);
        var data = synthetic.write();
        var cut = Arrays.copyOf(data, data.length - synthetic.strip().length / 2);
        var file = parse(cut);
        var photosites = read(file);
        assertEquals(synthetic.photosites[0], photosites[0]);
        assertEquals(synthetic.width * synthetic.height, photosites.length);
    }

    @Test
    @DisplayName("frames of two components or an odd width are rejected, other predictors are not this variant")
    void rejected() {
        var two = new File().photosites(7);
        two.components = 2;
        var data = two.strip();
        assertThrows(MediaException.Unsupported.class, () -> new HasselbladJpeg(data, 0, data.length));
        var odd = new File();
        odd.width = 3;
        odd.height = 1;
        odd.photosites = new int[]{1, 2, 3, 4};
        var oddData = Stream.encode(odd.photosites, 4, 1, HasselbladJpeg.PREDICTOR, 1);
        oddData[10] = 3;
        assertThrows(MediaException.Malformed.class, () -> new HasselbladJpeg(oddData, 0, oddData.length));
        var standard = new File().photosites(8);
        standard.predictor = 1;
        var standardData = standard.strip();
        assertFalse(HasselbladJpeg.matches(standardData, 0, standardData.length));
        var variant = new File().photosites(8).strip();
        assertTrue(HasselbladJpeg.matches(variant, 0, variant.length));
        assertThrows(MediaException.Malformed.class, () -> new HasselbladJpeg(new byte[]{1, 2, 3, 4}, 0, 4));
    }

    @Test
    @DisplayName("a frame whose size differs from the raw directory's is rejected")
    void mismatched() {
        var synthetic = new File().photosites(10);
        var writer = new VendorTiffWriter();
        writer.make = "Hasselblad";
        writer.width = synthetic.width + 2;
        writer.height = synthetic.height;
        writer.bits = 16;
        writer.compression = 7;
        writer.strip = synthetic.strip();
        assertThrows(MediaException.Malformed.class, () -> parse(writer.write()));
    }

    @Test
    @DisplayName("damaged synthetic files only raise MediaException, in bounded time")
    void fuzz() {
        var original = new File().photosites(11).write();
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
    @DisplayName("writes the seeds of the Hasselblad fuzz target")
    void fuzzSeeds() {
        FuzzSeeds.write(FUZZ_TARGET, "fff", "valid-predictor-8.3fr", new File().photosites(1).write());
        var processed = new File().photosites(2);
        processed.black = 256;
        FuzzSeeds.write(FUZZ_TARGET, "fff", "valid-black-level.fff", processed.write());
    }
}
