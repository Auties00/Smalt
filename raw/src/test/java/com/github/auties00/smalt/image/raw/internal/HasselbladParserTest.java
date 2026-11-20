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
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.time.Duration;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
        boolean little = true;
        // the maker note's GAIN block, or null for no maker note
        byte[] calibration;
        // whether the maker note carries the software version of Hasselblad's FFF files
        boolean software;
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
            writer.little = little;
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
            if (calibration != null) {
                writer.makerNote = offset -> {
                    var entries = new ArrayList<VendorTiffWriter.Entry>();
                    entries.add(VendorTiffWriter.undefined(HasselbladCalibration.TAG, calibration));
                    if (software) {
                        entries.add(VendorTiffWriter.ascii(HasselbladCalibration.SOFTWARE_TAG, "3.4.3 Mac"));
                    }
                    return VendorTiffWriter.directory(entries, offset, writer.order());
                };
            }
            return writer.write();
        }
    }

    // a GAIN block of 512 bytes, little-endian whatever the file's byte order, as the CC0 samples carry it
    static byte[] gain(int version, int shift, int red, int green, int blue) {
        var block = ByteBuffer.allocate(512).order(ByteOrder.LITTLE_ENDIAN);
        block.put(new byte[]{'G', 'A', 'I', 'N'});
        block.putShort(32, (short) version);
        block.putShort(44, (short) 8384).putShort(46, (short) 6304);
        block.putShort(80, (short) shift);
        block.putShort(82, (short) red).putShort(84, (short) green).putShort(86, (short) blue);
        return block.array();
    }

    // the calibrated value of a photosite, by the rule derived from Adobe DNG Converter, written independently of
    // HasselbladCalibration
    private static int calibrated(int value, int black, double gain) {
        return calibrated(value, black, 62914, Math.min(13107, Math.max(1028, (int) Math.rint(black * 65535.0 / 62914))),
                gain);
    }

    private static int calibrated(int value, int black, int white, int newBlack, double gain) {
        var encoded = Math.max(0, Math.min(65535,
                Math.floor((value - black) * (65535.0 - newBlack) / (white - black) + newBlack + 0.5)));
        // the converter's single-precision product on values normalized to 65535
        float normalizedBlack = (float) (newBlack / 65535.0);
        float range = 1f - normalizedBlack;
        float reciprocal = 1f / range;
        float x = (float) encoded * (1f / 65535f);
        float linear = (x * reciprocal - (reciprocal - 1f)) * (float) gain;
        float y = (linear * range + normalizedBlack) * 65535f;
        return (int) Math.max(0, Math.min(65535, Math.rint(y)));
    }

    // the gain index of a photosite of the red, green, green, blue filter array: 0 red, 1 green, 2 blue
    private static int color(int index, int width) {
        var row = index / width;
        var column = index % width;
        return row % 2 == 0 ? (column % 2 == 0 ? 0 : 1) : (column % 2 == 0 ? 1 : 2);
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

    @ParameterizedTest(name = "black {0}")
    @ValueSource(ints = {256, 4096})
    @DisplayName("a 3FR file's GAIN block scales each color's photosites by its gain, re-encoded to the converter's "
            + "black level and to white 65535")
    void calibrated(int black) {
        var synthetic = new File().photosites(5);
        synthetic.black = black;
        synthetic.calibration = gain(40, 14, 16469, 16825, 16836);
        var file = parse(synthetic.write());
        assertArrayEquals(new double[]{black == 256 ? 1028 : 4267}, file.sensor.black);
        assertArrayEquals(new double[]{65535}, file.sensor.white);
        double[] gains = {16469 / 16384.0, 16825 / 16384.0, 16836 / 16384.0};
        var expected = new int[synthetic.photosites.length];
        for (var index = 0; index < expected.length; index++) {
            expected[index] = calibrated(synthetic.photosites[index], black, gains[color(index, synthetic.width)]);
        }
        assertArrayEquals(expected, read(file));
        // one fractional bit less doubles every gain
        synthetic.calibration = gain(41, 13, 16469, 16825, 16836);
        var doubled = read(parse(synthetic.write()));
        for (var index = 0; index < expected.length; index++) {
            expected[index] = calibrated(synthetic.photosites[index], black, 2 * gains[color(index, synthetic.width)]);
        }
        assertArrayEquals(expected, doubled);
    }

    @Test
    @DisplayName("the calibration is not applied to Hasselblad software's files, other block versions, zero gains, "
            + "damaged blocks, or a file without a black level")
    void notCalibrated() {
        var synthetic = new File().photosites(6);
        synthetic.black = 256;
        synthetic.calibration = gain(40, 14, 16469, 16384, 16836);
        synthetic.software = true;
        assertUncalibrated(synthetic);
        synthetic.software = false;
        synthetic.little = false;
        assertUncalibrated(synthetic);
        synthetic.little = true;
        var damaged = gain(40, 14, 16469, 16384, 16836);
        damaged[0] = 'g';
        for (var block : new byte[][]{gain(30, 14, 16836, 16384, 16791), gain(20, 14, 17380, 16744, 16384),
                gain(40, 14, 16469, 0, 16836), gain(40, 17, 16469, 16384, 16836), damaged,
                Arrays.copyOf(gain(40, 14, 16469, 16384, 16836), 80)}) {
            synthetic.calibration = block;
            assertUncalibrated(synthetic);
        }
        var unprocessed = new File().photosites(7);
        unprocessed.calibration = gain(40, 14, 16469, 16384, 16836);
        var file = parse(unprocessed.write());
        assertNull(file.sensor.black);
        assertArrayEquals(unprocessed.photosites, read(file));
    }

    private static void assertUncalibrated(File synthetic) {
        var file = parse(synthetic.write());
        assertArrayEquals(new double[]{synthetic.black}, file.sensor.black);
        assertArrayEquals(new double[]{62914}, file.sensor.white);
        assertArrayEquals(synthetic.photosites, read(file));
    }

    @ParameterizedTest(name = "black {0}, gain {1}, value {3}")
    @CsvSource({
            // black, gain, color, photosite, the converter's photosite (X1D, X1D II and CFV 100C samples)
            "256, 16469, 0, 0, 763", "256, 16469, 0, 1000, 1798", "256, 16469, 0, 25006, 26640",
            "256, 16469, 0, 57911, 60692", "256, 16469, 0, 62849, 65535", "256, 16384, 1, 100, 867",
            "256, 16384, 1, 10000, 11060", "256, 16384, 1, 62619, 65231", "256, 16836, 2, 300, 1074",
            "256, 16836, 2, 44912, 48270", "256, 16836, 2, 57596, 61689", "256, 16825, 1, 24994, 27182",
            "256, 17265, 2, 4000, 5089", "256, 17265, 2, 27149, 30204", "4096, 16467, 0, 3554, 3699",
            "4096, 16467, 0, 45001, 47092", "4096, 16384, 1, 4000, 4167", "4096, 16384, 1, 58000, 60416",
            "4096, 16777, 2, 24999, 26563", "4096, 16777, 2, 62897, 65535",
            // values whose exact product is within 0.004 of one half, where the converter's single-precision
            // rounding decides
            "256, 16469, 0, 5218, 6162", "256, 16469, 0, 9898, 11006", "256, 16469, 0, 11958, 13138",
            "256, 16836, 2, 2104, 2984", "256, 16836, 2, 10202, 11551", "256, 16836, 2, 14322, 15908",
            "256, 16825, 1, 5362, 6426", "256, 16825, 1, 12255, 13714", "256, 16825, 1, 15575, 17223",
            "256, 17265, 2, 3372, 4408", "256, 17265, 2, 5793, 7034", "256, 17265, 2, 9423, 10974",
            "4096, 16467, 0, 4001, 4168", "4096, 16467, 0, 9686, 10120", "4096, 16467, 0, 17266, 18056",
            "4096, 16777, 2, 4476, 4673", "4096, 16777, 2, 13241, 14022", "4096, 16777, 2, 31812, 33830"})
    @DisplayName("the calibration tables hold the photosites Adobe DNG Converter wrote for the CC0 samples")
    void converterValues(int black, int gain, int color, int value, int expected) {
        var gains = new double[]{1, 1, 1};
        gains[color] = gain / 16384.0;
        var calibration = new HasselbladCalibration(black, gains);
        assertEquals(expected, calibration.table(color)[value] & 0xFFFF);
    }

    @ParameterizedTest(name = "black {0}, gains {2} {3} {4} >> {1}")
    @CsvSource({
            // black, fraction width, red, green and blue gains, then the SHA-256 of the 65536 little-endian u16
            // photosites Adobe DNG Converter 18.0 wrote for each raw value in the red, green and blue positions of
            // a copy of the CC0 X1D II sample (rawpixls-hasselblad-x1d-ii-50c-16bit-4-3.3fr) whose photosites
            // cover every 16-bit value in each position, its raw directory's black level and GAIN header changed
            "256, 14, 16384, 16825, 17265, 3b95be454396b541a03162a9c802d1cadc85a97ac9658274d18844e94062a391, "
                    + "880f2b484bdb67b1699336e63e9ecd5d1676fae30b037a797d4b09e5dc25de22, "
                    + "935f4ee39a7e827713d34184edfe312c3d24b83b6cbe3532654d417939e477fe",
            "4096, 14, 16467, 15000, 16777, ee63a1dd028ae6f35eae33d9c8dd44b75a3553426bb0e07396a92ce6cf88e5eb, "
                    + "73d28209da3adef525832be884b77687ae0479ff0addff6a3128ce37ad01778a, "
                    + "3a3224909a1ad87f626b5513530a4cdf250c7a23b07428290a7ccf538abff5a8",
            "1500, 14, 12345, 23456, 16385, 149061d189bec6e3239d72274d5bf0a4dff64d720acc9091f61d3dd1084d2f93, "
                    + "cec982b45d592154d2ceeea674daeaa744fc44d0d769708692cd97ea39c99ca5, "
                    + "c0e154f178a70e4e12c9e90fc62656664d67ad8a8290a2f76aa86e8fac75279d",
            "10000, 14, 30001, 16383, 19999, 5b6d4cefdb5fba174f13ebe787cf86c04659565299cb7e28460396e17762cdd0, "
                    + "f298d73f98af31685fcc574d8b77fdf426a3a24fefeb70fb6fee654fe780aa01, "
                    + "50037e190d4023b500aa548c2505ea71748450d7a5f8d34f284bdc1b1089e9d6",
            "0, 15, 40000, 8000, 16384, 11f3ebc7e95d620f4b3ec2dd4c976191e662d9eaafde68d6b80ca0db0f44cac6, "
                    + "86699dbde0ab4cbd452019458059a432b8fa3e940cf0b9720e9e41b8c79b2982, "
                    + "ca6b2fdf0ab3d4bb2235046fb34b8f1cf5fbf35caecf910c70d60d2efec8cea2",
            "30000, 14, 17000, 65535, 1, 563088a3be19363683404d9b402ea26e873d3e54962fbcd0172c91580383ad05, "
                    + "18f0cd5f3bbf5f949550ae1809268695ad60f160917f47fe26a7ea6dbd82b9b3, "
                    + "a91abd464fdad5ff1d446f4723945666d472d31f990145e6068104fb7c65b80a"})
    @DisplayName("the calibration tables equal, for every 16-bit photosite, the ones Adobe DNG Converter applies")
    void converterTables(int black, int shift, int red, int green, int blue, String redDigest, String greenDigest,
                         String blueDigest) throws NoSuchAlgorithmException {
        var calibration = HasselbladCalibration.parse(gain(40, shift, red, green, blue), black);
        assertNotNull(calibration);
        var digests = new String[]{redDigest, greenDigest, blueDigest};
        for (var color = 0; color < 3; color++) {
            var bytes = ByteBuffer.allocate(2 * 65536).order(ByteOrder.LITTLE_ENDIAN);
            for (var value : calibration.table(color)) {
                bytes.putShort(value);
            }
            assertEquals(digests[color], HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(bytes.array())), "the table of color " + color);
        }
    }

    @ParameterizedTest(name = "black {0}")
    @ValueSource(ints = {0, 256, 1024, 4096, 30000})
    @DisplayName("a unit gain leaves every photosite at its re-encoding to the converter's black and white levels")
    void unitGain(int black) {
        var calibration = new HasselbladCalibration(black, new double[]{1, 1, 1});
        var table = calibration.table(1);
        var scale = (65535.0 - calibration.black) / (62914 - black);
        for (var value = 0; value < table.length; value++) {
            var encoded = (int) Math.max(0, Math.min(65535,
                    Math.floor((value - black) * scale + calibration.black + 0.5)));
            assertEquals(encoded, table[value] & 0xFFFF, "the photosite " + value);
        }
    }

    /**
     * The calibration block of a CCD camera: a grid of cells over the sensor's center and a record per row.
     */
    record Ccd(int width, int height, int cellWidth, int cellHeight, int columns, int rows, int[] cells,
               short[] records) {
        static final int HEADER = 512;

        Ccd(int width, int height, int cellWidth, int cellHeight, int columns, int rows) {
            this(width, height, cellWidth, cellHeight, columns, rows, new int[4 * columns * rows],
                    new short[10 * height]);
            Arrays.fill(cells, 8000);
        }

        Ccd cell(int column, int row, int red, int green1, int green2, int blue) {
            var at = 4 * (row * columns + column);
            cells[at] = red;
            cells[at + 1] = green1;
            cells[at + 2] = green2;
            cells[at + 3] = blue;
            return this;
        }

        Ccd record(int row, int aboveEven, int belowEven, int aboveOdd, int belowOdd) {
            records[10 * row] = (short) aboveEven;
            records[10 * row + 1] = (short) belowEven;
            records[10 * row + 2] = (short) aboveOdd;
            records[10 * row + 3] = (short) belowOdd;
            return this;
        }

        int grid() {
            return HEADER + 20 * height;
        }

        byte[] block(int version, int shift, int red, int green, int blue) {
            var block = ByteBuffer.allocate(grid() + 32 + 2 * cells.length).order(ByteOrder.LITTLE_ENDIAN);
            block.put(new byte[]{'G', 'A', 'I', 'N'});
            block.putShort(32, (short) version);
            block.putShort(44, (short) width).putShort(46, (short) height);
            block.putShort(80, (short) shift);
            block.putShort(82, (short) red).putShort(84, (short) green).putShort(86, (short) blue);
            block.putInt(144, grid());
            block.position(HEADER);
            for (var value : records) {
                block.putShort(value);
            }
            block.putShort((short) 1).putShort((short) cellWidth).putShort((short) cellHeight)
                    .putShort((short) columns).putShort((short) rows);
            block.position(grid() + 32);
            for (var value : cells) {
                block.putShort((short) value);
            }
            return block.array();
        }

        // the bilinear interpolation of values at the cells' centers, held beyond the first and last
        private double interpolate(double[] nodes, double x, double y) {
            var fx = Math.max(0, Math.min(columns - 1, (x - ((width - columns * cellWidth) / 2.0 + (cellWidth - 1) / 2.0))
                    / cellWidth));
            var fy = Math.max(0, Math.min(rows - 1, (y - ((height - rows * cellHeight) / 2.0 + (cellHeight - 1) / 2.0))
                    / cellHeight));
            var i = Math.min((int) fx, columns - 2);
            var j = Math.min((int) fy, rows - 2);
            var tx = fx - i;
            var ty = fy - j;
            return nodes[j * columns + i] * (1 - tx) * (1 - ty) + nodes[j * columns + i + 1] * tx * (1 - ty)
                    + nodes[(j + 1) * columns + i] * (1 - tx) * ty + nodes[(j + 1) * columns + i + 1] * tx * ty;
        }

        // the corrected value of a photosite relative to the black level, by the rule derived from Adobe DNG
        // Converter, written independently of HasselbladFlatField and in double precision
        double corrected(int x, int y, double value, double up, double down) {
            var position = (y & 1) * 2 + (x & 1);
            var nodes = new double[columns * rows];
            var reciprocals = new double[nodes.length];
            for (var cell = 0; cell < nodes.length; cell++) {
                var mean = (cells[4 * cell] + cells[4 * cell + 1] + cells[4 * cell + 2] + cells[4 * cell + 3]) / 4.0;
                nodes[cell] = mean / cells[4 * cell + position];
                reciprocals[cell] = 1 / nodes[cell];
            }
            var flat = interpolate(nodes, x, y) * interpolate(reciprocals, (width - 1) / 2.0, (height - 1) / 2.0);
            var parity = x & 1;
            return (value + records[10 * y + 2 * parity] / 16384.0 * up + records[10 * y + 2 * parity + 1] / 16384.0
                    * down) * flat;
        }

        HasselbladFlatField.Table table(boolean signed) {
            return table(signed, new int[0]);
        }

        HasselbladFlatField.Table table(boolean signed, int[] defects) {
            var block = block(signed ? 30 : 20, 14, 16384, 16384, 16384);
            var field = HasselbladFlatField.parse(Arrays.copyOf(block, HasselbladFlatField.HEADER),
                    Arrays.copyOfRange(block, grid(), grid() + 32), 0, block.length, width, height, signed);
            assertNotNull(field);
            return HasselbladFlatField.Table.of(field.withDefects(defects), Arrays.copyOfRange(block, HEADER,
                    block.length), 20 * height + 32);
        }
    }

    // the CCD calibration Adobe DNG Converter 18.0 applied to a copy of the CC0 H3D sample
    // (rawpixls-hasselblad-h3d-16bit-4-3.3fr) whose photosites, grid and row records were replaced: photosites
    // 10000 but rows 96 to 107 and 1996 to 2005, 10000 + (37x + 101y) % 5000, and the square from (3600, 2700) to
    // (3699, 2799), 20000 + 50 (x - 3600) + 30 (y - 2700)
    private static final Ccd CONVERTER_COPY = new Ccd(7262, 5456, 50, 50, 144, 108)
            .cell(72, 54, 4000, 8000, 8000, 8000).cell(10, 20, 8000, 6000, 9000, 7000)
            .cell(143, 107, 5000, 7000, 7500, 9000).cell(0, 0, 9000, 8000, 8000, 6000)
            .cell(71, 53, 8000, 8000, 6500, 8000)
            .record(100, -300, -200, -100, -50).record(101, -400, -100, 0, -250).record(2000, 100, 50, -500, -20)
            .record(2001, -1000, 30, 200, -700);

    @ParameterizedTest(name = "({0}, {1})")
    @CsvSource({
            // column, row, photosite, the photosites above and below, the converter's photosite
            "100, 100, 13800, 13699, 13901, 12101", "101, 100, 13837, 13736, 13938, 14367",
            "1000, 101, 12201, 12100, 12302, 11817", "1001, 101, 12238, 12137, 12339, 12628",
            "5000, 100, 10100, 14999, 10201, 8781", "5001, 101, 10238, 10137, 10339, 10564",
            "2000, 2000, 11000, 10899, 11101, 10048", "2001, 2000, 11037, 10936, 11138, 11203",
            "4002, 2001, 10175, 10074, 10276, 9568", "4003, 2001, 10212, 10111, 10313, 10370",
            "7000, 2000, 11000, 10899, 11101, 10048", "7001, 2001, 11138, 11037, 11239, 11311",
            "3655, 2752, 24310, 24280, 24340, 22356", "3656, 2753, 24390, 24360, 24420, 21377",
            "3600, 2700, 20000, 10000, 20030, 17386", "3601, 2701, 20080, 20050, 20110, 20173",
            "3699, 2799, 27920, 27890, 10000, 29227", "3650, 2760, 24300, 24270, 24330, 34475",
            "3610, 2705, 20650, 20620, 20680, 23700", "3605, 2702, 20310, 20280, 20340, 20307",
            "555, 1052, 10000, 10000, 10000, 13048", "556, 1053, 10000, 10000, 10000, 8357",
            "530, 1030, 10000, 10000, 10000, 8899", "581, 1077, 10000, 10000, 10000, 10667",
            "7206, 5403, 10000, 10000, 10000, 9489", "7240, 5430, 10000, 10000, 10000, 12898",
            "7180, 5380, 10000, 10000, 10000, 10088", "30, 30, 10000, 10000, 10000, 7794",
            "55, 52, 10000, 10000, 10000, 10153", "56, 53, 10000, 10000, 10000, 9682",
            "21, 17, 10000, 10000, 10000, 13537", "3630, 2727, 22310, 22280, 22340, 22618",
            "3631, 2728, 22390, 22360, 22420, 22438"})
    @DisplayName("the CCD calibration gives the photosites Adobe DNG Converter wrote for a copy of the H3D sample "
            + "with changed grid cells and row records")
    void ccdConverterValues(int x, int y, int value, int up, int down, int expected) {
        assertEquals(expected, (int) Math.rint(ConverterCopy.TABLE.correct(x, y, value, up, down)));
        assertEquals(expected, CONVERTER_COPY.corrected(x, y, value, up, down), 1, "the double-precision model");
    }

    private static final class ConverterCopy {
        static final HasselbladFlatField.Table TABLE = CONVERTER_COPY.table(false);
    }

    @ParameterizedTest(name = "version {0}")
    @ValueSource(ints = {20, 30})
    @DisplayName("a CCD camera's 3FR file is corrected for the charge between vertical neighbors and by its flat "
            + "field, then, for version 30, re-encoded with the header's gains")
    void ccd(int version) {
        var signed = version == 30;
        var synthetic = new File();
        synthetic.white = signed ? 32767 : 65535;
        var random = new SplittableRandom(version);
        synthetic.photosites = new int[synthetic.width * synthetic.height];
        for (var index = 0; index < synthetic.photosites.length; index++) {
            synthetic.photosites[index] = signed ? random.nextInt(-20, 30000) & 0xFFFF : random.nextInt(0, 60000);
        }
        var ccd = new Ccd(synthetic.width, synthetic.height, 8, 4, 4, 2).cell(0, 0, 7000, 8000, 8100, 9000)
                .cell(3, 1, 9000, 7600, 8000, 6000).cell(1, 1, 8000, 8000, 8000, 5000);
        for (var row = 0; row < synthetic.height; row++) {
            ccd.record(row, -200 - 10 * row, -150 + 7 * row, 40 - row, -400);
        }
        synthetic.calibration = ccd.block(version, 14, 16836, 16384, 16791);
        var file = parse(synthetic.write());
        var black = signed ? HasselbladJpeg.SIGNED_OFFSET : 0;
        var width = synthetic.width;
        var height = synthetic.height;
        var stored = new int[width * height];
        for (var index = 0; index < stored.length; index++) {
            stored[index] = signed ? Math.max(0, (short) synthetic.photosites[index] + black) : synthetic.photosites[index];
        }
        double[] gains = {16836 / 16384.0, 1, 16791 / 16384.0};
        var expected = new int[stored.length];
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                // photosites below the black level count as at it
                var up = Math.max(0, stored[(y == 0 ? 1 : y - 1) * width + x] - black);
                var down = Math.max(0, stored[(y == height - 1 ? height - 2 : y + 1) * width + x] - black);
                var value = (int) Math.max(0, Math.min(65535,
                        Math.rint(ccd.corrected(x, y, Math.max(0, stored[y * width + x] - black), up, down)) + black));
                expected[y * width + x] = signed
                        ? calibrated(value, black, black + 31456, 1028, gains[color(y * width + x, width)]) : value;
            }
        }
        // the model computes in double precision, the converter's rule in single precision: they round values
        // close to one half apart
        var actual = read(file);
        var exact = 0;
        for (var index = 0; index < expected.length; index++) {
            var difference = Math.abs(actual[index] - expected[index]);
            assertTrue(difference <= (signed ? 2 : 1), "the photosite " + index + " differs by " + difference);
            exact += difference == 0 ? 1 : 0;
        }
        assertTrue(exact >= expected.length * 0.99, exact + " of " + expected.length + " photosites are the model's");
        if (signed) {
            assertArrayEquals(new double[]{1028}, file.sensor.black);
            assertArrayEquals(new double[]{65535}, file.sensor.white);
        } else {
            assertNull(file.sensor.black);
            assertArrayEquals(new double[]{62914}, file.sensor.white);
        }
        // a grid of equal values is no flat field, and records of zero no correction
        var neutral = new Ccd(width, height, 8, 4, 4, 2);
        synthetic.calibration = neutral.block(version, 14, 16384, 16384, 16384);
        var plain = read(parse(synthetic.write()));
        for (var index = 0; index < stored.length; index++) {
            expected[index] = signed
                    ? calibrated(Math.max(black, stored[index]), black, black + 31456, 1028, 1) : stored[index];
        }
        assertArrayEquals(expected, plain);
    }

    @Test
    @DisplayName("a CCD block whose version, signedness, sensor size, grid or values do not fit is not applied")
    void ccdRejected() {
        var synthetic = new File().photosites(11);
        var width = synthetic.width;
        var height = synthetic.height;
        var ccd = new Ccd(width, height, 8, 4, 4, 2).cell(0, 0, 7000, 8000, 8100, 9000);
        var block = ccd.block(20, 14, 16384, 16384, 16384);
        // a version 20 block in a signed file, a version 30 block in an unsigned one
        assertNull(HasselbladFlatField.parse(Arrays.copyOf(block, 148), Arrays.copyOfRange(block, ccd.grid(),
                ccd.grid() + 32), 0, block.length, width, height, true));
        var other = ccd.block(30, 14, 16384, 16384, 16384);
        assertNull(HasselbladFlatField.parse(Arrays.copyOf(other, 148), Arrays.copyOfRange(other, ccd.grid(),
                ccd.grid() + 32), 0, other.length, width, height, false));
        // another sensor size, a grid past the block, a grid larger than the sensor
        assertNull(HasselbladFlatField.parse(Arrays.copyOf(block, 148), Arrays.copyOfRange(block, ccd.grid(),
                ccd.grid() + 32), 0, block.length, width + 2, height, false));
        assertNull(HasselbladFlatField.parse(Arrays.copyOf(block, 148), Arrays.copyOfRange(block, ccd.grid(),
                ccd.grid() + 32), 0, block.length - 1, width, height, false));
        var large = new Ccd(width, height, 8, 4, 6, 2).block(20, 14, 16384, 16384, 16384);
        assertNull(HasselbladFlatField.parse(Arrays.copyOf(large, 148), Arrays.copyOfRange(large, ccd.grid(),
                ccd.grid() + 32), 0, large.length, width, height, false));
        assertNotNull(HasselbladFlatField.parse(Arrays.copyOf(block, 148), Arrays.copyOfRange(block, ccd.grid(),
                ccd.grid() + 32), 0, block.length, width, height, false));
        // a zero grid value leaves the photosites as stored, as does a block of another size in a file
        synthetic.calibration = new Ccd(width, height, 8, 4, 4, 2).cell(1, 0, 0, 8000, 8000, 8000)
                .block(20, 14, 16384, 16384, 16384);
        var file = parse(synthetic.write());
        assertArrayEquals(synthetic.photosites, read(file));
        synthetic.calibration = new Ccd(width + 2, height, 8, 4, 4, 2).block(20, 14, 16384, 16384, 16384);
        file = parse(synthetic.write());
        assertArrayEquals(synthetic.photosites, read(file));
        assertArrayEquals(new double[]{65535}, file.sensor.white);
    }

    // the photosite at a column and row of the synthetic image whose copy of the CC0 H3D sample (uncompressed
    // photosites, a grid of equal values and records of zero) Adobe DNG Converter 18.0 converted for the
    // converterColumns test
    private static int hashed(int x, int y) {
        var h = x * 374761393 + y * 668265263;
        h = (h ^ (h >>> 13)) * 1274126177;
        h ^= h >>> 16;
        return 16000 + (h >>> 1) % 8001;
    }

    @ParameterizedTest(name = "column {0}")
    @CsvSource({
            "1824, 7f3b8eb8f3e13c726fe9fe6612741bff45cfb996e0cc23f5d8a78f6941d62e58, 17, '19532,19580,19902,23181,"
                    + "16968,20179,19024,19140,20374,22636,22290,21805,21321,21350,22515,20614,19695,20827,19578,"
                    + "20651,23108,18793,22191,22004,24013,20534,21262,21595,19893,20014,19690,20406,20595,23292,"
                    + "20525,18374,19589,18717,17938,20006,21320,20375,19935,18806,19181,18482,20007,19872'",
            "3884, 2e33afff4e4f4572f5f9e212f6d87e392d5d555f86e1a691e261f80628d0f898, 17, '21281,20987,19502,19932,"
                    + "22288,20609,22341,23092,21766,21374,21598,21177,22261,17740,19350,19464,19682,20093,19123,"
                    + "19228,20670,19500,19924,18913,22114,20950,18531,19566,21269,21378,20551,19683,18621,19274,"
                    + "22418,21429,19448,19645,19197,20583,21033,20794,18543,20922,20219,20445,20540,21872'",
            "4065, 5487a5ce19951fd34a83eb4f9d3fcca91d111c2d974ee46d890a03dfbd17b4d5, 16, '22001,21175,19708,18427,"
                    + "20273,20470,22925,20852,19041,19255,20035,20024,21186,21379,20345,19696,18408,20147,19051,"
                    + "19214,19323,19141,18424,18905,18084,19761,19911,21064,21675,20924,20910,20758,19900,17860,"
                    + "18586,19335,17855,19225,19334,18830,20349,23180,21842,18665,18337,17980,17637,20654'",
            "4135, dda739bd90049ef506b9d90202396d99c1160cd9208cb9ccc3c1110cf02186a9, 16, '18875,21344,20297,19758,"
                    + "18578,18029,20016,21107,21595,20948,21968,21337,20419,20798,18285,17634,19676,19976,19647,"
                    + "18881,18423,18582,19820,18496,18840,21096,21101,21392,21129,20885,17149,18309,20318,21018,"
                    + "18743,18939,18469,21145,21614,20643,21784,20018,21049,22316,21996,21508,20108,18155'"})
    @DisplayName("a defective column's red, blue and green photosites are the converter's")
    void converterColumns(int column, String colorDigest, int firstGreen, String greens) throws NoSuchAlgorithmException {
        // the converter's output covers rows 16 to 5435 of the sensor's 5456; a band of 18 columns from an even
        // one, which keeps the filter array's phase, holds every neighbor the interpolation reads
        var height = 5456;
        var width = 18;
        var left = (column - 8) & ~1;
        var local = column - left;
        var plane = new SensorPlane(width, height, 1, false, MediaLimits.DEFAULT);
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                plane.integers[y * width + x] = (short) hashed(left + x, y);
            }
        }
        HasselbladColumns.interpolate(plane, new int[]{local});
        var colors = ByteBuffer.allocate(2 * 2710).order(ByteOrder.LITTLE_ENDIAN);
        for (var y = 16; y <= 5435; y++) {
            if (((y + column) & 1) == 0) {
                colors.putShort(plane.integers[y * width + local]);
            }
        }
        assertEquals(colorDigest, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(colors.array())),
                "the red or blue photosites");
        var expected = Arrays.stream(greens.split(",")).mapToInt(Integer::parseInt).toArray();
        for (var index = 0; index < expected.length; index++) {
            assertEquals(expected[index], plane.integers[(firstGreen + 2 * index) * width + local] & 0xFFFF,
                    "the green photosite of row " + (firstGreen + 2 * index));
        }
        // the other columns are unchanged
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                if (x != local) {
                    assertEquals(hashed(left + x, y), plane.integers[y * width + x] & 0xFFFF);
                }
            }
        }
    }

    @Test
    @DisplayName("defective columns near the plane's edges take the mean of their nearest usable photosites of the "
            + "same color")
    void clusteredColumns() {
        var width = 24;
        var height = 12;
        var plane = new SensorPlane(width, height, 1, false, MediaLimits.DEFAULT);
        // red, the greens and blue each hold a constant, the listed columns hold noise; the greens of the two kinds
        // share one, since a clustered green takes the mean of both kinds around it
        int[] constants = {1000, 2000, 2000, 4000};
        var random = new SplittableRandom(3);
        int[] listed = {0, 23};
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                var column = x;
                var noisy = Arrays.stream(listed).anyMatch(entry -> entry == column);
                plane.integers[y * width + x] = (short) (noisy ? random.nextInt(0, 65536)
                        : constants[(y & 1) * 2 + (x & 1)]);
            }
        }
        HasselbladColumns.interpolate(plane, listed);
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                assertEquals(constants[(y & 1) * 2 + (x & 1)], plane.integers[y * width + x] & 0xFFFF,
                        "the photosite at " + x + ", " + y);
            }
        }
    }

    @Test
    @DisplayName("defective columns two columns apart are interpolated one after the other in the list's order, as "
            + "the converter does")
    void sequentialColumns() throws NoSuchAlgorithmException {
        // Adobe DNG Converter 18.0 on a copy of the CC0 H3D sample with the hashed photosites, a grid of equal
        // values, records of zero and a list of columns 3002 and 3000, in that order
        var height = 5456;
        var width = 20;
        var left = 2992;
        var plane = new SensorPlane(width, height, 1, false, MediaLimits.DEFAULT);
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                plane.integers[y * width + x] = (short) hashed(left + x, y);
            }
        }
        var reversed = new SensorPlane(width, height, 1, false, MediaLimits.DEFAULT);
        System.arraycopy(plane.integers, 0, reversed.integers, 0, plane.integers.length);
        HasselbladColumns.interpolate(plane, new int[]{3002 - left, 3000 - left});
        assertEquals("a7181fe238aa9c95ab71a06a25b057d5b89d49df9bf60db4f918b29906e7c0d3", columnDigest(plane, 3002 - left),
                "the column 3002");
        assertEquals("7b786f1929f5a6dfbdccd1003d93d977bcbfa092f9ffea127b7772a240fca7d1", columnDigest(plane, 3000 - left),
                "the column 3000");
        // the other order reads the other column before it is interpolated
        HasselbladColumns.interpolate(reversed, new int[]{3000 - left, 3002 - left});
        assertFalse(Arrays.equals(plane.integers, reversed.integers), "the order changes the columns");
    }

    // the SHA-256 of the little-endian photosites of rows 16 to 5435 of a column, those the converter's output covers
    private static String columnDigest(SensorPlane plane, int column) throws NoSuchAlgorithmException {
        var bytes = ByteBuffer.allocate(2 * 5420).order(ByteOrder.LITTLE_ENDIAN);
        for (var y = 16; y < 5436; y++) {
            bytes.putShort(plane.integers[y * plane.width + column]);
        }
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.array()));
    }

    @ParameterizedTest(name = "width {0}, {1} defective columns")
    @CsvSource({
            // measured on copies of the H3D (7262) and H4D (9044) samples with a steep grid and as many listed
            // columns: the counts on either side of each change of width
            "7262, 0, 291, 0", "7262, 1, 291, 4", "7262, 2, 243, 8", "7262, 4, 243, 16", "7262, 6, 243, 24",
            "7262, 7, 208, 28", "7262, 11, 208, 44", "7262, 12, 166, 48", "7262, 15, 166, 60", "7262, 16, 135, 64",
            "7262, 19, 135, 76", "7262, 20, 112, 80", "7262, 22, 112, 88", "7262, 23, 88, 92", "7262, 24, 88, 96",
            "7262, 25, 72, 100", "7262, 26, 72, 104", "7262, 27, 56, 108", "7262, 28, 56, 112", "7262, 29, 40, 116",
            "7262, 30, 40, 120", "7262, 31, 32, 124", "7262, 33, 32, 132", "7262, 80, 32, 320",
            "9044, 0, 292, 0", "9044, 1, 292, 4", "9044, 2, 245, 8", "9044, 6, 245, 24", "9044, 7, 206, 28",
            "9044, 11, 206, 44", "9044, 12, 168, 48", "9044, 15, 168, 60", "9044, 16, 135, 64", "9044, 19, 135, 76",
            "9044, 20, 112, 80", "9044, 22, 112, 88",
            "296, 0, 296, 0", "297, 0, 149, 0", "100, 30, 34, 100"})
    @DisplayName("the flat field's tiles are as wide and their buffers reach as far left as the converter's")
    void tiles(int width, int defects, int tileWidth, int margin) {
        var columns = new int[defects];
        for (var index = 0; index < defects; index++) {
            columns[index] = 10 + 2 * index;
        }
        var field = new Ccd(width, 4, 2, 2, 2, 2).table(false, columns).field();
        assertEquals(tileWidth, HasselbladFlatField.Table.tileWidth(field), "the tiles' width");
        assertEquals(margin, HasselbladFlatField.Table.margin(field), "the buffers' margin");
    }

    @ParameterizedTest(name = "column {0}")
    @CsvSource({"0, 0", "1, 1", "242, 0", "243, 227", "244, 228", "485, 227", "486, 470", "1218, 1200", "1219, 1199"})
    @DisplayName("a tile's rows interpolate the flat field from the first column of their parity in its buffer")
    void interpolationStart(int column, int start) {
        assertEquals(start, HasselbladFlatField.Table.interpolationStart(column, 243, 16));
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
            // the grid's cells, the list of defective columns, the first of the 64 rows and the SHA-256 of the
            // converter's photosites of those rows, columns 20 to 7243, little-endian
            "steep, '3884,4065,4135,1824', 2000, d7b7990623e39468d4bff84e46ec85cdddca52b0c67a0e6ace8f2c5ec09700da",
            "steep, '', 2000, eaf5d6726bfeb380156fe7a397c3999002ce5fea5cc44fe9732641b0398c2d04",
            // rows holding photosites whose gain the map's origin rounds one unit of the last place the other way
            "steep, '', 100, 81ba2d89fd2ee1feb4df15675a2bfd55d68e9a20d4e6768741269ba51bc92815",
            "steep, '', 2330, 998d00695dc2f9fc5779f2cc27db255f8ccede517e5f85c307a788e0c87a1c46",
            // eight listed columns: tiles of 208 photosites whose buffers reach 32 columns left
            "steep, '301,512,723,934,1145,1356,1567,1778', 2000, "
                    + "3e64142b08e9632dce087cb45ed29b9b40691a8f732d2a21fe8b0d7890fe683c",
            "steep, '301,512,723,934,1145,1356,1567,1778', 2330, "
                    + "d340ff96b7e42e5be1dcf2fafbe41c6b62f17acbc95c946a1a917e5a25975d3d",
            "smooth, '3884,4065,4135,1824', 2060, d8bce713ec637b50c1148cbae1849e9139517e7bbb35dd1fd12902afd0460f12"})
    @DisplayName("the CCD calibration gives the photosites Adobe DNG Converter wrote for copies of the H3D sample "
            + "with a synthetic grid, in its tiles and with its defective columns")
    void converterTiles(String grid, String list, int first, String digest) throws NoSuchAlgorithmException {
        // copies of the CC0 H3D sample (7262 x 5456, cells of 50 x 50 in 144 x 108) with hashed photosites, the
        // grid below, records of zero and the list of defective columns given; the steep grid changes by up to
        // half a cell's value from one cell to the next, which exposes the interpolation's single-precision rounding
        var ccd = new Ccd(7262, 5456, 50, 50, 144, 108);
        for (var row = 0; row < 108; row++) {
            for (var column = 0; column < 144; column++) {
                var cell = new int[4];
                for (var position = 0; position < 4; position++) {
                    cell[position] = "steep".equals(grid) ? 6000 + (column * 37 + row * 53 + position * 101) % 4000
                            : 7000 + 40 * position + 3 * column + 2 * row;
                }
                ccd.cell(column, row, cell[0], cell[1], cell[2], cell[3]);
            }
        }
        var defects = list.isEmpty() ? new int[0] : Arrays.stream(list.split(",")).mapToInt(Integer::parseInt).toArray();
        var table = ccd.table(false, defects);
        var plane = new SensorPlane(7262, 5456, 1, false, MediaLimits.DEFAULT);
        for (var y = 0; y < 5456; y++) {
            for (var x = 0; x < 7262; x++) {
                plane.integers[y * 7262 + x] = (short) (1000 + Math.floorMod(x * 2654435761L + y * 40503L, 59000));
            }
        }
        table.calibrate(plane, 0, defects);
        var bytes = ByteBuffer.allocate(2 * 64 * 7224).order(ByteOrder.LITTLE_ENDIAN);
        for (var y = first; y < first + 64; y++) {
            for (var x = 20; x < 7244; x++) {
                bytes.putShort(plane.integers[y * 7262 + x]);
            }
        }
        assertEquals(digest, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.array())));
    }

    @Test
    @DisplayName("a version 30 file's saturated photosites, stored as 0, are white and correct their vertical "
            + "neighbors as the normalized white level")
    void saturated() {
        var width = 8;
        var height = 6;
        var ccd = new Ccd(width, height, 4, 2, 2, 2);
        // the photosites of row 3 take -0.05 of the one above, those of row 1 0.02 of the one below
        ccd.record(3, -819, 0, -819, 0).record(1, 0, 328, 0, 328);
        var table = ccd.table(true);
        var black = HasselbladJpeg.SIGNED_OFFSET;
        var plane = new SensorPlane(width, height, 1, false, MediaLimits.DEFAULT);
        Arrays.fill(plane.integers, (short) (black + 20000));
        plane.integers[2 * width + 3] = 0;
        table.calibrate(plane, black, new int[0]);
        assertEquals(65535, plane.integers[2 * width + 3] & 0xFFFF, "the saturated photosite");
        assertEquals(black + Math.rint((20000 / 65535f - 819 / 16384f) * 65535f), plane.integers[3 * width + 3] & 0xFFFF,
                1, "the photosite below it");
        assertEquals(black + Math.rint((20000 / 65535f + 328 / 16384f) * 65535f), plane.integers[width + 3] & 0xFFFF,
                1, "the photosite above it");
        assertEquals(black + 20000, plane.integers[4 * width + 2] & 0xFFFF, 1, "a photosite of a row without records");
    }

    @Test
    @DisplayName("the defective columns a CCD block lists are interpolated after its calibration; entries of other "
            + "kinds, columns past the sensor and lists that overlap the row records are ignored")
    void ccdDefects() {
        var synthetic = new File().photosites(5);
        var width = synthetic.width;
        var height = synthetic.height;
        var ccd = new Ccd(width, height, 8, 4, 4, 2);
        var plain = ccd.block(20, 14, 16384, 16384, 16384);
        synthetic.calibration = plain;
        var stored = read(parse(synthetic.write()));
        assertArrayEquals(synthetic.photosites, stored);
        // columns 10 (kind 6) and 21 (kind 7), 10 again, 30 of kind 5 and 45 past the sensor
        var listed = withDefects(plain, 400, new int[][]{{10, 6}, {21, 7}, {10, 6}, {30, 5}, {45, 6}});
        synthetic.calibration = listed;
        var file = parse(synthetic.write());
        var decoded = read(file);
        var plane = new SensorPlane(width, height, 1, false, MediaLimits.DEFAULT);
        for (var index = 0; index < stored.length; index++) {
            plane.integers[index] = (short) stored[index];
        }
        HasselbladColumns.interpolate(plane, new int[]{10, 21});
        var changed = 0;
        for (var index = 0; index < stored.length; index++) {
            assertEquals(plane.integers[index] & 0xFFFF, decoded[index], "the photosite " + index);
            var x = index % width;
            if (x != 10 && x != 21) {
                assertEquals(stored[index], decoded[index], "the photosite " + index);
            } else {
                changed += stored[index] != decoded[index] ? 1 : 0;
            }
        }
        assertTrue(changed > height, changed + " photosites of the listed columns changed");
        assertArrayEquals(new int[]{10, 21}, HasselbladFlatField.defects(Arrays.copyOfRange(listed, 402, 442), 5, width));
        // a list whose entries reach the row records
        synthetic.calibration = withDefects(plain, 490, new int[][]{{10, 6}, {21, 6}, {22, 6}});
        assertArrayEquals(stored, read(parse(synthetic.write())));
        // a list inside the header
        synthetic.calibration = withDefects(plain, 100, new int[][]{{10, 6}});
        assertArrayEquals(stored, read(parse(synthetic.write())));
    }

    // a copy of a CCD block whose list of defective columns, at an offset, holds entries of a column and a kind
    private static byte[] withDefects(byte[] block, int offset, int[][] entries) {
        var copy = ByteBuffer.wrap(block.clone()).order(ByteOrder.LITTLE_ENDIAN);
        copy.putInt(HasselbladFlatField.DEFECTS, offset);
        if (offset >= HasselbladFlatField.HEADER) {
            copy.putShort(offset, (short) entries.length);
            for (var index = 0; index < entries.length; index++) {
                var at = offset + 2 + HasselbladFlatField.DEFECT * index;
                if (at + HasselbladFlatField.DEFECT <= Ccd.HEADER) {
                    copy.putShort(at, (short) entries[index][0]).putShort(at + 2, (short) entries[index][1]);
                }
            }
        }
        return copy.array();
    }

    /**
     * The version 13 calibration block of a CCD camera back: a record of 30 bytes per row, each holding the
     * coefficient of the neighbor above, a field the converter ignores, the gain, another ignored field and the
     * coefficient of the neighbor below, for the even and then the odd columns, and five ignored fields.
     */
    record Rows(int width, int height, short[] records, int[][] defects) {
        static final int RECORDS = 1024;
        static final int DEFECTS = 512;

        Rows(int width, int height) {
            this(width, height, new short[15 * height], new int[0][]);
            for (var row = 0; row < height; row++) {
                record(row, 0, 16384, 0, 0, 16384, 0);
            }
        }

        Rows record(int row, int aboveEven, int gainEven, int belowEven, int aboveOdd, int gainOdd, int belowOdd) {
            short[] values = {(short) aboveEven, 77, (short) gainEven, 0, (short) belowEven, (short) aboveOdd, 77,
                    (short) gainOdd, 0, (short) belowOdd, 0, 0, 0, 0, -574};
            System.arraycopy(values, 0, records, 15 * row, 15);
            return this;
        }

        Rows withDefects(int[][] entries) {
            return new Rows(width, height, records, entries);
        }

        byte[] block() {
            return block(13, width, height, 30, RECORDS);
        }

        byte[] block(int version, int blockWidth, int blockHeight, int stride, int start) {
            var block = ByteBuffer.allocate(RECORDS + 30 * height).order(ByteOrder.LITTLE_ENDIAN);
            block.put(new byte[]{'G', 'A', 'I', 'N'});
            block.putShort(32, (short) version);
            block.putShort(44, (short) blockWidth).putShort(46, (short) blockHeight);
            block.putShort(60, (short) stride);
            block.putInt(96, start);
            block.putInt(HasselbladFlatField.DEFECTS, DEFECTS);
            block.putShort(DEFECTS, (short) defects.length);
            for (var index = 0; index < defects.length; index++) {
                block.putShort(DEFECTS + 2 + 8 * index, (short) defects[index][0])
                        .putShort(DEFECTS + 4 + 8 * index, (short) defects[index][1]);
            }
            block.position(RECORDS);
            for (var value : records) {
                block.putShort(value);
            }
            return block.array();
        }

        // the gain of a photosite, by the rule derived from Adobe DNG Converter, written independently of
        // HasselbladRowCalibration: a green photosite takes the mean of the gains of the red and blue photosites
        // beside it in its row and below it in its column
        double gain(int x, int y) {
            var parity = x & 1;
            if (((x + y) & 1) == 0) {
                return records[15 * y + 5 * parity + 2];
            }
            var other = y + 1 < height ? y + 1 : y - 1;
            return (records[15 * y + 5 * (1 - parity) + 2] + records[15 * other + 5 * parity + 2]) / 2.0;
        }

        // the corrected photosite in double precision, before rounding
        double corrected(int x, int y, int value, int up, int down) {
            var parity = x & 1;
            return (gain(x, y) * value + records[15 * y + 5 * parity] * up + records[15 * y + 5 * parity + 4] * down)
                    / 16384.0;
        }
    }

    @ParameterizedTest(name = "signed {0}")
    @ValueSource(booleans = {false, true})
    @DisplayName("a CCD camera back's 3FR file is corrected by the gains and the vertical neighbors of its rows, "
            + "to black level 0 and 96% of the stated white level")
    void rowCalibration(boolean signed) {
        var synthetic = new File();
        synthetic.white = signed ? 32767 : 65535;
        var width = synthetic.width;
        var height = synthetic.height;
        var random = new SplittableRandom(signed ? 13 : 31);
        synthetic.photosites = new int[width * height];
        for (var index = 0; index < synthetic.photosites.length; index++) {
            // the words of a signed file above 32767 are highlights, as those of an unsigned one
            synthetic.photosites[index] = random.nextInt(8) == 0 ? random.nextInt(65536) : random.nextInt(0, 30000);
        }
        var rows = new Rows(width, height);
        for (var row = 0; row < height; row++) {
            rows.record(row, -350 - 13 * row, 17049 + 11 * row, -371 + 5 * row, -306 + row, 16919 - 17 * row,
                    -296 - 3 * row);
        }
        synthetic.calibration = rows.block();
        var file = parse(synthetic.write());
        assertArrayEquals(new double[]{0}, file.sensor.black);
        assertArrayEquals(new double[]{signed ? 31456 : 62914}, file.sensor.white);
        var actual = read(file);
        var exact = 0;
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                var up = synthetic.photosites[(y == 0 ? 1 : y - 1) * width + x];
                var down = synthetic.photosites[(y == height - 1 ? height - 2 : y + 1) * width + x];
                var expected = (int) Math.max(0, Math.min(65535,
                        Math.rint(rows.corrected(x, y, synthetic.photosites[y * width + x], up, down))));
                // the model computes in double precision, the converter's rule in single precision
                var difference = Math.abs(actual[y * width + x] - expected);
                assertTrue(difference <= 1, "the photosite (" + x + ", " + y + ") differs by " + difference);
                exact += difference == 0 ? 1 : 0;
            }
        }
        assertTrue(exact >= width * height * 0.99, exact + " photosites are the model's");
        // unit gains and records of zero leave the 16-bit words
        synthetic.calibration = new Rows(width, height).block();
        assertArrayEquals(synthetic.photosites, read(parse(synthetic.write())));
    }

    @ParameterizedTest(name = "{0}, {1}, {2}")
    @CsvSource({
            // the photosite, the photosites above and below, twice the gain, the coefficients of the neighbors
            // above and below, the converter's photosite; photosites of the CFV sample that the double-precision
            // rule rounds the other way
            "674, 1244, 1232, 34032, -356, -352, 646", "12536, 22336, 22752, 34168, -390, -387, 12002",
            "1122, 2186, 2180, 33984, -344, -340, 1072", "15684, 24050, 24144, 34140, -382, -381, 15218",
            "302, 150, 164, 34110, -517, -513, 304", "700, 416, 384, 34048, -304, -304, 712",
            "536, 356, 396, 34024, -306, -306, 542", "5426, 9374, 9310, 34058, -361, -360, 5229",
            "9984, 4438, 4686, 34053, -519, -519, 10086", "9254, 5820, 5928, 34123, -295, -294, 9426"})
    @DisplayName("the row calibration's single-precision arithmetic gives the photosites Adobe DNG Converter wrote "
            + "for the near ties of a CFV file")
    void rowConverterValues(int value, int up, int down, int gains, int above, int below, int expected) {
        var corrected = HasselbladRowCalibration.Table.correct(value / 65535f, up / 65535f, down / 65535f,
                (float) (gains / 2.0 / 16384), above / 16384f, below / 16384f);
        assertEquals(expected, (int) Math.rint(corrected));
        // six are exact ties in double precision, which the double-precision rule rounds up
        assertNotEquals(expected, (int) Math.floor((gains / 2.0 * value + above * up + below * down) / 16384 + 0.5),
                "the double-precision rule");
    }

    @Test
    @DisplayName("a green photosite's gain is the mean of the gains beside and below it, above it on the last row")
    void rowGains() {
        var rows = new Rows(4, 3).record(0, 0, 17000, 0, 0, 16000, 0).record(1, 0, 15000, 0, 0, 18001, 0)
                .record(2, 0, 16384, 0, 0, 20000, 0);
        var table = HasselbladRowCalibration.Table.of(Arrays.copyOfRange(rows.block(), Rows.RECORDS,
                Rows.RECORDS + 90), 30, 3);
        float[] expected = {17000, (17000 + 18001) / 2f, (18001 + 16384) / 2f, 18001, 16384, (16384 + 18001) / 2f};
        for (var index = 0; index < expected.length; index++) {
            assertEquals(expected[index] / 16384, table.gains()[index], 0, "the gain " + index);
        }
    }

    @Test
    @DisplayName("a version 13 block whose version, sensor size, record size or records do not fit is not applied")
    void rowRejected() {
        var synthetic = new File().photosites(17);
        var width = synthetic.width;
        var height = synthetic.height;
        var rows = new Rows(width, height).record(3, -400, 17000, -300, -200, 16000, -100);
        var block = rows.block();
        var header = Arrays.copyOf(block, HasselbladRowCalibration.HEADER);
        assertNotNull(HasselbladRowCalibration.parse(header, 0, block.length, width, height, false));
        assertNull(HasselbladRowCalibration.parse(header, 0, block.length - 1, width, height, false));
        assertNull(HasselbladRowCalibration.parse(header, 0, block.length, width + 2, height, false));
        for (var other : new byte[][]{rows.block(20, width, height, 30, Rows.RECORDS),
                rows.block(13, width, height - 2, 30, Rows.RECORDS), rows.block(13, width, height, 19, Rows.RECORDS),
                rows.block(13, width, height, 30, 100), rows.block(13, width, height, 30, Integer.MAX_VALUE)}) {
            assertNull(HasselbladRowCalibration.parse(Arrays.copyOf(other, HasselbladRowCalibration.HEADER), 0,
                    other.length, width, height, false));
            synthetic.calibration = other;
            assertArrayEquals(synthetic.photosites, read(parse(synthetic.write())));
        }
        synthetic.calibration = block;
        assertFalse(Arrays.equals(synthetic.photosites, read(parse(synthetic.write()))), "the fitting block");
    }

    @Test
    @DisplayName("the defective columns a version 13 block lists are interpolated after its calibration")
    void rowDefects() {
        var synthetic = new File().photosites(19);
        var width = synthetic.width;
        var height = synthetic.height;
        var rows = new Rows(width, height);
        for (var row = 0; row < height; row++) {
            rows.record(row, -380, 17072, -382, -296, 16899, -295);
        }
        synthetic.calibration = rows.block();
        var corrected = read(parse(synthetic.write()));
        synthetic.calibration = rows.withDefects(new int[][]{{11, 6}, {30, 7}, {11, 6}, {20, 5}, {50, 6}}).block();
        var decoded = read(parse(synthetic.write()));
        var plane = new SensorPlane(width, height, 1, false, MediaLimits.DEFAULT);
        for (var index = 0; index < corrected.length; index++) {
            plane.integers[index] = (short) corrected[index];
        }
        HasselbladColumns.interpolate(plane, new int[]{11, 30});
        var changed = 0;
        for (var index = 0; index < corrected.length; index++) {
            assertEquals(plane.integers[index] & 0xFFFF, decoded[index], "the photosite " + index);
            var x = index % width;
            if (x == 11 || x == 30) {
                changed += corrected[index] != decoded[index] ? 1 : 0;
            }
        }
        assertTrue(changed > height, changed + " photosites of the listed columns changed");
    }

    @Test
    @DisplayName("the row calibration of a plane split into parallel bands reads every neighbor as stored")
    void rowBands() {
        // large enough for several bands wherever helper threads exist, so the rows at the bands' edges read the
        // stored neighbors of the next band and not its corrected ones
        var width = 1536;
        var height = 1024;
        var random = new SplittableRandom(23);
        var rows = new Rows(width, height);
        for (var row = 0; row < height; row++) {
            rows.record(row, random.nextInt(-700, 0), random.nextInt(14000, 19000), random.nextInt(-700, 0),
                    random.nextInt(-700, 0), random.nextInt(14000, 19000), random.nextInt(-700, 0));
        }
        var table = HasselbladRowCalibration.Table.of(Arrays.copyOfRange(rows.block(), Rows.RECORDS,
                Rows.RECORDS + 30 * height), 30, height);
        var plane = new SensorPlane(width, height, 1, false, MediaLimits.DEFAULT);
        for (var index = 0; index < plane.integers.length; index++) {
            plane.integers[index] = (short) random.nextInt(65536);
        }
        var stored = plane.integers.clone();
        var black = HasselbladJpeg.SIGNED_OFFSET;
        table.correct(plane, black);
        for (var y = 0; y < height; y++) {
            var up = (y == 0 ? 1 : y - 1) * width;
            var down = (y == height - 1 ? height - 2 : y + 1) * width;
            for (var x = 0; x < width; x++) {
                var index = 2 * y + (x & 1);
                var expected = (int) Math.rint(HasselbladRowCalibration.Table.correct(
                        ((stored[y * width + x] - black) & 0xFFFF) * (1f / 65535f),
                        ((stored[up + x] - black) & 0xFFFF) * (1f / 65535f),
                        ((stored[down + x] - black) & 0xFFFF) * (1f / 65535f),
                        table.gains()[index], table.above()[index], table.below()[index]));
                assertEquals(expected, plane.integers[y * width + x] & 0xFFFF, "the photosite (" + x + ", " + y + ")");
            }
        }
    }

    @Test
    @DisplayName("a calibration block whose records or grid end past the end of the file is not applied")
    void calibrationPastEnd() {
        var synthetic = new File().photosites(29);
        synthetic.white = 32767;
        var width = synthetic.width;
        var height = synthetic.height;
        var rows = new Rows(width, height);
        for (var row = 0; row < height; row++) {
            rows.record(row, -380, 17072, -382, -296, 16899, -295);
        }
        var block = rows.block();
        synthetic.calibration = new byte[block.length];
        var file = relocated(synthetic.write(), block, block.length);
        assertArrayEquals(new double[]{0}, parse(file).sensor.black, "the whole block");
        var truncated = parse(relocated(synthetic.write(), block, block.length - 30 * height / 2));
        assertArrayEquals(new double[]{HasselbladJpeg.SIGNED_OFFSET}, truncated.sensor.black);
        assertArrayEquals(new double[]{32767 + HasselbladJpeg.SIGNED_OFFSET}, truncated.sensor.white);
        var words = read(truncated);
        for (var index = 0; index < words.length; index++) {
            assertEquals((synthetic.photosites[index] + HasselbladJpeg.SIGNED_OFFSET) & 0xFFFF, words[index],
                    "the photosite " + index);
        }

        var unsigned = new File().photosites(31);
        var ccd = new Ccd(unsigned.width, unsigned.height, 8, 4, 4, 2).cell(1, 1, 8000, 8000, 8000, 5000);
        var grid = ccd.block(20, 14, 16384, 16384, 16384);
        unsigned.calibration = new byte[grid.length];
        assertArrayEquals(new double[]{62914}, parse(relocated(unsigned.write(), grid, grid.length)).sensor.white,
                "the whole block");
        var cut = parse(relocated(unsigned.write(), grid, grid.length - 2));
        assertArrayEquals(new double[]{65535}, cut.sensor.white);
        assertArrayEquals(unsigned.photosites, read(cut));
    }

    // moves a file's GAIN block, whose maker note entry the file holds for a placeholder of the block's length, to
    // the end of the file, keeping its first bytes only
    private static byte[] relocated(byte[] file, byte[] block, int kept) {
        var entry = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
                .putShort((short) HasselbladCalibration.TAG).putShort((short) 7).putInt(block.length).array();
        var at = -1;
        for (var index = 0; at < 0 && index + 12 <= file.length; index++) {
            if (Arrays.equals(file, index, index + 8, entry, 0, 8)) {
                at = index;
            }
        }
        assertTrue(at >= 0, "the GAIN entry");
        var out = Arrays.copyOf(file, file.length + kept);
        ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN).putInt(at + 8, file.length);
        System.arraycopy(block, 0, out, file.length, kept);
        return out;
    }

    @Test
    @DisplayName("a GAIN block header is read by its version, fraction width and gains, and rejected otherwise")
    void header() {
        var calibration = HasselbladCalibration.parse(gain(41, 14, 16783, 16384, 16466), 256);
        assertNotNull(calibration);
        assertArrayEquals(new double[]{16783 / 16384.0, 1, 16466 / 16384.0}, calibration.gains);
        assertEquals(1028, calibration.black);
        assertEquals(4267, HasselbladCalibration.parse(gain(40, 14, 16467, 16384, 16777), 4096).black);
        // the converter's black levels of copies of the X1D II sample with a raw black level of 0, 1500, 10000,
        // 12584 and 30000: 1028 to 13107
        assertEquals(1028, HasselbladCalibration.parse(gain(40, 14, 16384, 16384, 16384), 0).black);
        assertEquals(1562, HasselbladCalibration.parse(gain(40, 14, 16384, 16384, 16384), 1500).black);
        assertEquals(10417, HasselbladCalibration.parse(gain(40, 14, 16384, 16384, 16384), 10000).black);
        assertEquals(13107, HasselbladCalibration.parse(gain(40, 14, 16384, 16384, 16384), 12584).black);
        assertEquals(13107, HasselbladCalibration.parse(gain(40, 14, 16384, 16384, 16384), 30000).black);
        assertNull(HasselbladCalibration.parse(new byte[40], 256));
        assertNull(HasselbladCalibration.parse(gain(30, 14, 16836, 16384, 16791), 1024));
        assertNull(HasselbladCalibration.parse(gain(40, 14, 0, 16384, 16791), 256));
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
    @DisplayName("the signed photosites of a file whose white level is 32767 are stored plus 1024, the black level, "
            + "modulo 65536")
    void signed() {
        var synthetic = new File();
        synthetic.white = 32767;
        synthetic.photosites = new int[synthetic.width * synthetic.height];
        var random = new SplittableRandom(9);
        var expected = new int[synthetic.photosites.length];
        for (var index = 0; index < expected.length; index++) {
            var value = switch (index) {
                case 0 -> -1500;
                case 1 -> 32767;
                case 2 -> -1024;
                case 3 -> -32768;
                case 4 -> 31743;
                case 5 -> 31744;
                default -> random.nextInt(-40, 3000);
            };
            synthetic.photosites[index] = value & 0xFFFF;
            expected[index] = (value + HasselbladJpeg.SIGNED_OFFSET) & 0xFFFF;
        }
        // -1500 wraps to 65060 and -1024 to 0; -32768, 32767 and 31744 are stored above 32767
        assertArrayEquals(new int[]{65060, 33791, 0, 33792, 32767, 32768}, Arrays.copyOf(expected, 6));
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
        var originals = new byte[][]{new File().photosites(11).write(), rowSeed(false).write(), rowSeed(true).write()};
        var random = new SplittableRandom(23);
        assertTimeoutPreemptively(Duration.ofSeconds(60), () -> {
            for (var round = 0; round < 900; round++) {
                var bytes = originals[round % originals.length].clone();
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
        var calibrated = new File().photosites(3);
        calibrated.black = 256;
        calibrated.calibration = gain(40, 14, 16469, 16384, 16836);
        FuzzSeeds.write(FUZZ_TARGET, "fff", "valid-calibrated.3fr", calibrated.write());
        FuzzSeeds.write(FUZZ_TARGET, "fff", "valid-ccd.3fr", ccdSeed(20).write());
        FuzzSeeds.write(FUZZ_TARGET, "fff", "valid-ccd-signed.3fr", ccdSeed(30).write());
        FuzzSeeds.write(FUZZ_TARGET, "fff", "valid-ccd-defects.3fr", ccdDefectsSeed().write());
        FuzzSeeds.write(FUZZ_TARGET, "fff", "valid-ccd-rows.3fr", rowSeed(false).write());
        FuzzSeeds.write(FUZZ_TARGET, "fff", "valid-ccd-rows-signed.3fr", rowSeed(true).write());
    }

    // a CCD camera back's 3FR file whose version 13 calibration block changes every photosite and lists two
    // defective columns
    static File rowSeed(boolean signed) {
        var file = new File().photosites(signed ? 43 : 41);
        if (signed) {
            file.white = 32767;
        }
        var rows = new Rows(file.width, file.height);
        for (var row = 0; row < file.height; row++) {
            rows.record(row, -370, 17049 + row, -360, -300, 16919 - row, -290);
        }
        file.calibration = rows.withDefects(new int[][]{{9, 6}, {26, 7}}).block();
        return file;
    }

    // a CCD camera's 3FR file whose calibration block changes every photosite
    static File ccdDefectsSeed() {
        var file = ccdSeed(20);
        file.calibration = withDefects(file.calibration, 400, new int[][]{{10, 6}, {21, 7}, {39, 6}});
        return file;
    }

    static File ccdSeed(int version) {
        var file = new File().photosites(version);
        if (version == 30) {
            file.white = 32767;
        }
        var ccd = new Ccd(file.width, file.height, 8, 4, 4, 2).cell(0, 0, 7000, 8000, 8100, 9000)
                .cell(2, 1, 8000, 9000, 8000, 6000);
        for (var row = 0; row < file.height; row++) {
            ccd.record(row, -150, -100 - row, -60, 20);
        }
        file.calibration = ccd.block(version, 14, 16836, 16384, 16791);
        return file;
    }
}
