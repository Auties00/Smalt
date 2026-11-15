package com.github.auties00.smalt.image.raw.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.raw.IiqWriter;
import com.github.auties00.smalt.image.raw.RawDecoder;
import com.github.auties00.smalt.image.raw.RawDecoderOptions;
import com.github.auties00.smalt.image.raw.RawFormat;
import com.github.auties00.smalt.image.raw.VendorTiffWriter;
import com.github.auties00.smalt.io.MediaSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the structure of Phase One IIQ files, the row coding and the calibration on synthetic files laid out as the
 * CC0 samples are; {@code IiqOracleTest} compares the samples with Adobe DNG Converter.
 */
class IiqParserTest {
    private static byte[] iiq(String signature) {
        var order = ByteOrder.LITTLE_ENDIAN;
        var header = ByteBuffer.allocate(16).order(order);
        header.put((byte) 'I').put((byte) 'I').putShort((short) 42).putInt(16);
        header.put(signature.getBytes(StandardCharsets.ISO_8859_1));
        var out = new ByteArrayOutputStream();
        out.writeBytes(header.array());
        out.writeBytes(VendorTiffWriter.directory(List.of(VendorTiffWriter.ascii(271, "Phase One"),
                VendorTiffWriter.ascii(272, "P40+")), 16, order));
        out.writeBytes(new byte[64]);
        return out.toByteArray();
    }

    private static int[] noise(int count, int low, int spread, long seed) {
        var random = new Random(seed);
        var values = new int[count];
        for (var index = 0; index < count; index++) {
            values[index] = low + random.nextInt(spread);
        }
        return values;
    }

    private static short[] decode(byte[] file) {
        var parsed = RawParser.parse(MediaSource.of(file), MediaLimits.DEFAULT);
        assertNotNull(parsed.sensor, parsed.unsupported);
        var plane = SensorPlane.of(parsed.sensor, MediaLimits.DEFAULT);
        parsed.sensor.reader.read(plane, MediaLimits.DEFAULT);
        return plane.integers;
    }

    @Test
    @DisplayName("a file whose block cannot be read is described as IIQ and rejected as unsupported")
    void unreadable() {
        var file = RawParser.parse(MediaSource.of(iiq("IIIICwaR")), MediaLimits.DEFAULT);
        assertEquals(RawFormat.IIQ, file.format);
        assertEquals("Phase One", file.make);
        assertEquals("P40+", file.model);
        assertTrue(file.unsupported != null && file.unsupported.contains("Phase One"), file.unsupported);
        assertFalse(RawDecoder.info(MediaSource.of(iiq("IIIICwaR"))).isDecodable());
        assertThrows(MediaException.Unsupported.class,
                () -> RawDecoder.decode(MediaSource.of(iiq("IIIICwaR")), RawDecoderOptions.defaults()));
    }

    @Test
    @DisplayName("a TIFF file without the whole signature is not taken for an IIQ file")
    void signature() {
        for (var signature : new String[]{"IIIICwaX", "IIIXCwaR", "MMMMCwaR"}) {
            var file = RawParser.parse(MediaSource.of(iiq(signature)), MediaLimits.DEFAULT);
            assertNotEquals(RawFormat.IIQ, file.format, signature);
        }
        assertFalse(IiqParser.matches("II*\0\u0010\0\0\0IIIICwa".getBytes(StandardCharsets.ISO_8859_1), 15));
    }

    @ParameterizedTest(name = "spread {0}")
    @ValueSource(ints = {4, 40, 400, 4000, 12000, 16000})
    @DisplayName("IIQ L decodes every width of the row coding to the coded numbers times four")
    void rowCoding(int spread) {
        var writer = new IiqWriter(80, 24);
        writer.black = 0;
        writer.keepsBlack = true;
        System.arraycopy(noise(80 * 24, 1000, spread, spread), 0, writer.numbers, 0, 80 * 24);
        var decoded = decode(writer.write());
        for (var index = 0; index < decoded.length; index++) {
            assertEquals(Math.min(65535, writer.numbers[index] * 4), decoded[index] & 0xFFFF, "photosite " + index);
        }
    }

    @ParameterizedTest(name = "width {0}")
    @ValueSource(ints = {41, 44, 46, 47})
    @DisplayName("the photosites after a row's last whole group are 16-bit values, as on the IQ150 and IQ4")
    void partialGroup(int width) {
        for (var compression : new int[]{3, 8}) {
            var writer = new IiqWriter(width, 12);
            writer.compression = compression;
            writer.black = 0;
            writer.keepsBlack = true;
            writer.imageWidth = width - writer.left;
            System.arraycopy(noise(width * 12, 500, 9000, width), 0, writer.numbers, 0, width * 12);
            for (var y = 0; y < 12; y++) {
                writer.numbers[y * width + width - 1] = y % 2 == 0 ? 16000 : 3;
            }
            var decoded = decode(writer.write());
            var multiplier = compression == 8 ? 1 : 4;
            for (var index = 0; index < decoded.length; index++) {
                assertEquals(writer.numbers[index] * multiplier, decoded[index] & 0xFFFF,
                        "compression " + compression + ", photosite " + index);
            }
        }
    }

    @Test
    @DisplayName("16-bit IIQ L takes the coded numbers as they are")
    void sixteenBit() {
        var writer = new IiqWriter(40, 16);
        writer.compression = 8;
        writer.black = 0;
        writer.keepsBlack = true;
        System.arraycopy(noise(40 * 16, 1000, 60000, 3), 0, writer.numbers, 0, 40 * 16);
        var decoded = decode(writer.write());
        for (var index = 0; index < decoded.length; index++) {
            assertEquals(writer.numbers[index], decoded[index] & 0xFFFF);
        }
    }

    @Test
    @DisplayName("tone-coded IIQ S expands its numbers through the square law, in multiples of 4")
    void toneCoded() {
        var writer = new IiqWriter(32, 16);
        writer.compression = 5;
        writer.black = 0;
        writer.keepsBlack = true;
        writer.numbers[0] = 64;
        writer.numbers[1] = 100;
        writer.numbers[2] = 127;
        writer.numbers[3] = 255;
        for (var index = 4; index < writer.numbers.length; index++) {
            writer.numbers[index] = writer.numbers[index % 4];
        }
        var decoded = decode(writer.write());
        // the values Adobe DNG Converter gives these numbers
        assertEquals(4128, decoded[0] & 0xFFFF);
        assertEquals(10080, decoded[1] & 0xFFFF);
        assertEquals(16256, decoded[2] & 0xFFFF);
        assertEquals(65532, decoded[3] & 0xFFFF);
    }

    @Test
    @DisplayName("uncompressed data holds 16-bit photosites")
    void uncompressed() {
        var writer = new IiqWriter(24, 16);
        writer.compression = 0;
        writer.black = 0;
        writer.keepsBlack = true;
        System.arraycopy(noise(24 * 16, 0, 65536, 9), 0, writer.numbers, 0, 24 * 16);
        var decoded = decode(writer.write());
        for (var index = 0; index < decoded.length; index++) {
            assertEquals(writer.numbers[index], decoded[index] & 0xFFFF);
        }
    }

    @Test
    @DisplayName("the backs without kept black lose the black level and each row's number")
    void rowBlack() {
        var writer = new IiqWriter(24, 16);
        java.util.Arrays.fill(writer.numbers, 1000);
        writer.rowBlack = new short[16];
        for (var y = 0; y < 16; y++) {
            writer.rowBlack[y] = (short) (y - 3);
        }
        var parsed = RawParser.parse(MediaSource.of(writer.write()), MediaLimits.DEFAULT);
        assertEquals(0, parsed.sensor.black[0]);
        var decoded = decode(writer.write());
        for (var y = 0; y < 16; y++) {
            assertEquals(4000 - 1024 + y - 3, decoded[y * 24] & 0xFFFF);
        }
    }

    @Test
    @DisplayName("grids scale every photosite of the image and the red and blue ones, the kept black included")
    void grids() {
        var writer = new IiqWriter(24, 12);
        writer.keepsBlack = true;
        java.util.Arrays.fill(writer.numbers, 2000);
        writer.levelGrid = 32768 + 3277;
        writer.redGrid = 32768 + 6554;
        writer.blueGrid = 32768 - 6554;
        var parsed = RawParser.parse(MediaSource.of(writer.write()), MediaLimits.DEFAULT);
        assertEquals(1024, parsed.sensor.black[0]);
        assertEquals("RGGB", parsed.sensor.cfa.toString().replaceAll("[^RGB]", "").substring(0, 4));
        var decoded = decode(writer.write());
        var level = (32768 + 3277) / 32768.0;
        var at = (writer.top * 24) + writer.left;
        assertEquals(Math.rint(8000 * level * (32768 + 6554) / 32768.0), decoded[at] & 0xFFFF, "red");
        assertEquals(Math.rint(8000 * level), decoded[at + 1] & 0xFFFF, "green");
        assertEquals(Math.rint(8000 * level), decoded[at + 24] & 0xFFFF, "green");
        assertEquals(Math.rint(8000 * level * (32768 - 6554) / 32768.0), decoded[at + 25] & 0xFFFF, "blue");
        assertEquals(8000, decoded[0] & 0xFFFF, "outside the image");
    }

    @Test
    @DisplayName("the defect records become a bad pixel opcode list of the points and columns")
    void defects() {
        var writer = new IiqWriter(32, 16);
        writer.keepsBlack = true;
        java.util.Arrays.fill(writer.numbers, 2000);
        writer.numbers[8 * 32 + 12] = 9000;
        writer.defects.add(new int[]{12, 8, 129});
        writer.numbers[5 * 32 + 20] = 9000;
        writer.defects.add(new int[]{20, 0, 131});
        writer.numbers[15 * 32 + 31] = 9000;
        writer.defects.add(new int[]{31, 15, 138});
        var parsed = RawParser.parse(MediaSource.of(writer.write()), MediaLimits.DEFAULT);
        assertNotNull(parsed.sensor.opcodes1);
        var plane = SensorPlane.of(parsed.sensor, MediaLimits.DEFAULT);
        parsed.sensor.reader.read(plane, MediaLimits.DEFAULT);
        Opcodes.applyToSensor(parsed.sensor, plane, parsed.sensor.opcodes1);
        assertEquals(8000, plane.integers[8 * 32 + 12] & 0xFFFF);
        // the repair of a column reads the column's own photosites, already repaired or not
        assertTrue(Math.abs((plane.integers[5 * 32 + 20] & 0xFFFF) - 8000) < 400);
        // Adobe DNG Converter leaves the records of kind 138 alone, even inside the image
        assertEquals(36000, plane.integers[15 * 32 + 31] & 0xFFFF);
    }

    /**
     * {@return the levels of a level table's four outputs, different per output and per shift}
     *
     * @param shift what tells the tables apart
     */
    private static int[][] levels(int shift) {
        return new int[][]{{1000 + shift, 4000, 16000 + 4 * shift}, {1100, 4100 + shift, 16500},
                {900, 3900, 15800 - shift}, {1050, 4050 + 2 * shift, 16200}};
    }

    private static byte[] levelTable(int[][] levels) {
        var buffer = ByteBuffer.allocate((levels.length * levels[0].length + 1) * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (var output : levels) {
            for (var level : output) {
                buffer.putInt(level);
            }
        }
        return buffer.putInt(1).array();
    }

    /**
     * {@return the gains of the four outputs: a version, then four floats per output, the gain of stage 0 in the
     * second and that of the other stages in the first}
     *
     * @param first the gains of the stages above 0
     * @param second the gains of stage 0
     */
    private static byte[] outputGains(double[] first, double[] second) {
        var buffer = ByteBuffer.allocate(84).order(ByteOrder.LITTLE_ENDIAN).putInt(84297);
        buffer.putFloat(0).putFloat(0).putFloat(0).putFloat(0);
        for (var output = 0; output < 4; output++) {
            buffer.putFloat((float) first[output]).putFloat((float) second[output]).putFloat(0).putFloat(0);
        }
        return buffer.array();
    }

    /**
     * {@return a file of four outputs whose photosites rise along each row}
     *
     * @param stage the gain stage
     */
    private static IiqWriter fourOutputs(int stage) {
        var writer = new IiqWriter(48, 16);
        writer.scalars.put(0x222, 8 + 16);
        writer.scalars.put(0x224, 4 + 4);
        writer.scalars.put(0x20B, stage);
        writer.scalars.put(0x21E, 1);
        for (var y = 0; y < 16; y++) {
            for (var x = 0; x < 48; x++) {
                writer.numbers[y * 48 + x] = 256 + 120 * (x % 24) + 37 * y;
            }
        }
        return writer;
    }

    /**
     * {@return the photosite a level table and an output gain make of a value: the gained value, rounded, times the
     * output's gain at it, interpolated between the ratios of the highest output's levels to its own and held beyond
     * them, rounded}
     */
    private static int expected(int[][] levels, double gain, int output, int value) {
        var scaled = Math.rint(value * gain);
        var knots = levels[output];
        var last = knots.length - 1;
        double ratio;
        if (scaled <= knots[0] || scaled >= knots[last]) {
            var index = scaled <= knots[0] ? 0 : last;
            ratio = target(levels, index) / (double) knots[index];
        } else {
            var low = 0;
            while (scaled >= knots[low + 1]) {
                low++;
            }
            var a = target(levels, low) / (double) knots[low];
            var b = target(levels, low + 1) / (double) knots[low + 1];
            ratio = a + (b - a) * (scaled - knots[low]) / (knots[low + 1] - knots[low]);
        }
        return (int) Math.min(65535, Math.max(0, Math.rint(scaled * ratio)));
    }

    private static int target(int[][] levels, int index) {
        var target = 0;
        for (var output : levels) {
            target = Math.max(target, output[index]);
        }
        return target;
    }

    @ParameterizedTest(name = "gain stage {0}")
    @ValueSource(ints = {0, 1, 2, 5})
    @DisplayName("four outputs map their levels to the highest output's through the table of their gain stage")
    void levelTables(int stage) {
        var writer = fourOutputs(stage);
        var base = levels(0);
        var stageZero = levels(-40);
        var stageOne = levels(60);
        writer.calibrations.add(java.util.Map.entry(0x41F, levelTable(base)));
        writer.calibrations.add(java.util.Map.entry(0x420, levelTable(stageZero)));
        writer.calibrations.add(java.util.Map.entry(0x421, levelTable(stageOne)));
        var first = new double[]{0, 0.01, 0.02, 0.03};
        var second = new double[]{0, 0.015, 0.025, 0.035};
        writer.calibrations.add(java.util.Map.entry(0x41E, outputGains(first, second)));
        var table = switch (stage) {
            case 0 -> stageZero;
            case 1 -> stageOne;
            case 2 -> base;
            default -> null;
        };
        var decoded = decode(writer.write());
        for (var y = 4; y < 12; y++) {
            for (var x = 8; x < 40; x++) {
                var output = (x >= 24 ? 1 : 0) + (y >= 8 ? 2 : 0);
                var gain = 1 + (float) (stage == 0 ? second : first)[output];
                var value = writer.numbers[y * 48 + x] * 4 - 1024;
                var want = table == null ? (int) Math.rint(value * gain) : expected(table, gain, output, value);
                assertEquals(want, decoded[y * 48 + x] & 0xFFFF, "stage " + stage + " at " + x + "," + y);
            }
        }
    }

    @Test
    @DisplayName("the level tables apply only in level mode 1")
    void levelMode() {
        var writer = fourOutputs(0);
        writer.scalars.put(0x21E, 0);
        writer.calibrations.add(java.util.Map.entry(0x420, levelTable(levels(-40))));
        var decoded = decode(writer.write());
        for (var x = 8; x < 40; x++) {
            assertEquals(writer.numbers[5 * 48 + x] * 4 - 1024, decoded[5 * 48 + x] & 0xFFFF);
        }
    }

    /**
     * {@return a response table of two levels whose outputs' gains differ}
     */
    private static byte[] response() {
        var buffer = ByteBuffer.allocate(11 * 4).order(ByteOrder.LITTLE_ENDIAN).putInt(2000).putInt(20000);
        for (var gain : new int[]{10000, 10000, 10200, 10100, 10400, 10300, 10600, 10500}) {
            buffer.putInt(gain);
        }
        return buffer.putInt(1).array();
    }

    @ParameterizedTest(name = "binned {0}")
    @ValueSource(booleans = {false, true})
    @DisplayName("the response table scales each output by its own gain at the level, binned (Sensor+) or not")
    void responseGains(boolean binned) {
        var writer = fourOutputs(0);
        writer.scalars.remove(0x21E);
        if (binned) {
            writer.scalars.put(0x227, 1);
        }
        for (var y = 0; y < 16; y++) {
            for (var x = 0; x < 48; x++) {
                writer.numbers[y * 48 + x] = 300 + 400 * (x % 24) + 50 * y;
            }
        }
        writer.calibrations.add(java.util.Map.entry(0x431, response()));
        var decoded = decode(writer.write());
        var gains = new int[][]{{10000, 10000}, {10200, 10100}, {10400, 10300}, {10600, 10500}};
        for (var y = 4; y < 12; y++) {
            for (var x = 8; x < 40; x++) {
                var output = (x >= 24 ? 1 : 0) + (y >= 8 ? 2 : 0);
                var value = writer.numbers[y * 48 + x] * 4 - 1024;
                var fraction = Math.clamp((value - 2000) / 18000.0, 0, 1);
                var gain = (gains[output][0] + (gains[output][1] - gains[output][0]) * fraction) / 10000;
                assertEquals(Math.rint(value * gain), decoded[y * 48 + x] & 0xFFFF, "at " + x + "," + y);
            }
        }
    }

    /**
     * {@return the ratio entry of the halves' gains: a version, two floats, the constants above one of the stages
     * above 0 and of stage 0, their slopes, the change per degree and the temperature it is measured from}
     */
    private static byte[] halfGain(double constant, double slope, double perDegree, double reference) {
        var buffer = ByteBuffer.allocate(36).order(ByteOrder.LITTLE_ENDIAN).putInt(84297).putFloat(0).putFloat(0);
        buffer.putFloat(0.5f).putFloat((float) constant).putFloat(1e-3f).putFloat((float) slope);
        return buffer.putFloat((float) perDegree).putFloat((float) reference).array();
    }

    @ParameterizedTest(name = "constant {0}")
    @ValueSource(doubles = {0.006, -0.035})
    @DisplayName("two outputs scale up one half by the ratio of their gains at the sensor's temperature, then add the polynomial")
    void halves(double constant) {
        var writer = new IiqWriter(48, 12);
        writer.scalars.put(0x222, 8 + 16);
        writer.scalars.put(0x20B, 0);
        writer.scalars.put(0x210, Float.floatToIntBits(20.5f));
        for (var y = 0; y < 12; y++) {
            for (var x = 0; x < 48; x++) {
                writer.numbers[y * 48 + x] = 400 + 500 * (x % 24) + 31 * y;
            }
        }
        var slope = 4e-8;
        var perDegree = 1.2e-4;
        writer.calibrations.add(java.util.Map.entry(0x419, halfGain(constant, slope, perDegree, 31.7)));
        var polynomial = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).putFloat(0).putFloat(0.01f)
                .putFloat(-9e-7f).putFloat(0).array();
        writer.calibrations.add(java.util.Map.entry(0x41A, polynomial));
        var decoded = decode(writer.write());
        for (var y = 4; y < 8; y++) {
            for (var x = 8; x < 40; x++) {
                double value = writer.numbers[y * 48 + x] * 4 - 1024;
                var ratio = 1 + (float) constant + (float) slope * value + (float) perDegree * (20.5f - 31.7f);
                var right = x >= 24;
                if (ratio >= 1 ? right : !right) {
                    value = ratio >= 1 ? value * ratio : value / ratio;
                }
                value = Math.rint(value);
                value = Math.rint(value + value * (0.01f + value * -9e-7f));
                assertEquals(value, decoded[y * 48 + x] & 0xFFFF, "at " + x + "," + y);
            }
        }
    }

    @Test
    @DisplayName("the IQ180's photosites saturate at a white level of 64000")
    void iq180White() {
        var writer = new IiqWriter(24, 16);
        writer.model = "IQ180";
        var parsed = RawParser.parse(MediaSource.of(writer.write()), MediaLimits.DEFAULT);
        assertEquals(64000, parsed.sensor.white[0]);
        writer.model = "IQ140";
        parsed = RawParser.parse(MediaSource.of(writer.write()), MediaLimits.DEFAULT);
        assertEquals(65535, parsed.sensor.white[0]);
    }

    @Test
    @DisplayName("decoding renders the image area, with the as-shot white balance of the block")
    void renders() {
        var writer = new IiqWriter(64, 40);
        writer.keepsBlack = true;
        System.arraycopy(noise(64 * 40, 1100, 400, 5), 0, writer.numbers, 0, 64 * 40);
        var info = RawDecoder.info(MediaSource.of(writer.write()));
        assertEquals(RawFormat.IIQ, info.format());
        assertTrue(info.isDecodable());
        var parsed = RawParser.parse(MediaSource.of(writer.write()), MediaLimits.DEFAULT);
        assertArrayEquals(new double[]{0.5, 1, 1 / 1.5}, parsed.color.asShotNeutral, 1e-6);
        var picture = RawDecoder.decode(MediaSource.of(writer.write()), RawDecoderOptions.defaults());
        assertEquals(48, picture.picture().width());
        assertEquals(32, picture.picture().height());
    }

    @ParameterizedTest(name = "compression {0}")
    @ValueSource(ints = {1, 2, 4, 6, 7, 9})
    @DisplayName("the IIQ S of the newer backs and unknown compressions stay unsupported")
    void unsupportedCompressions(int compression) {
        var writer = new IiqWriter(24, 16);
        writer.compression = compression;
        var bytes = writer.write();
        var file = RawParser.parse(MediaSource.of(bytes), MediaLimits.DEFAULT);
        assertTrue(file.unsupported.contains("compression " + compression), file.unsupported);
        assertEquals(RawFormat.IIQ, RawDecoder.info(MediaSource.of(bytes)).format());
        assertThrows(MediaException.Unsupported.class,
                () -> RawDecoder.decode(MediaSource.of(bytes), RawDecoderOptions.defaults()));
    }

    private static byte[] shorts(int count, java.util.function.IntUnaryOperator value) {
        var buffer = ByteBuffer.allocate(count * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (var index = 0; index < count; index++) {
            buffer.putShort((short) value.applyAsInt(index));
        }
        return buffer.array();
    }

    @Test
    @DisplayName("the black numbers per row and half and per column and half change at the outputs' seams")
    void blackTables() {
        var writer = new IiqWriter(48, 16);
        writer.scalars.put(0x222, 24);
        writer.scalars.put(0x224, 8);
        java.util.Arrays.fill(writer.numbers, 1500);
        writer.tables.put(0x223, shorts(16 * 2, index -> (index >> 1) * 3 - 7 + (index & 1) * 11));
        writer.tables.put(0x225, shorts(48 * 2, index -> (index >> 1) % 5 - 2 - (index & 1) * 13));
        var decoded = decode(writer.write());
        for (var y = 0; y < 16; y++) {
            for (var x = 0; x < 48; x++) {
                var row = y * 3 - 7 + (x >= 24 ? 11 : 0);
                var column = x % 5 - 2 - (y >= 8 ? 13 : 0);
                assertEquals(6000 - 1024 + row + column, decoded[y * 48 + x] & 0xFFFF, "at " + x + "," + y);
            }
        }
    }

    /**
     * {@return a grid entry}
     *
     * @param shape the rectangle's left, top, width and height, then the columns and rows between nodes
     * @param values the values of the nodes, row by row, over 32768
     */
    private static byte[] gridEntry(int[] shape, int[] values) {
        var buffer = ByteBuffer.allocate(16 + values.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (var number : shape) {
            buffer.putShort((short) number);
        }
        buffer.putInt(0);
        for (var value : values) {
            buffer.putShort((short) value);
        }
        return buffer.array();
    }

    /**
     * {@return a grid's gain at a photosite, or 1 outside its rectangle}
     */
    private static double gridGain(int[] shape, int[] values, int perNode, int lane, int x, int y) {
        if (x < shape[0] || y < shape[1] || x >= shape[0] + shape[2] || y >= shape[1] + shape[3]) {
            return 1;
        }
        var nodesX = (shape[2] + shape[4] - 1) / shape[4];
        var nodesY = (shape[3] + shape[5] - 1) / shape[5];
        var across = (x - shape[0]) / (double) shape[4];
        var down = (y - shape[1]) / (double) shape[5];
        var first = Math.min((int) across, nodesX - 1);
        var second = Math.min(first + 1, nodesX - 1);
        var above = Math.min((int) down, nodesY - 1);
        var below = Math.min(above + 1, nodesY - 1);
        var fractionX = across - first;
        var fractionY = down - above;
        java.util.function.BiFunction<Integer, Integer, Double> node =
                (row, column) -> values[(row * nodesX + column) * perNode + lane] / 32768.0;
        var a = node.apply(above, first) * (1 - fractionX) + node.apply(above, second) * fractionX;
        var b = node.apply(below, first) * (1 - fractionX) + node.apply(below, second) * fractionX;
        return a * (1 - fractionY) + b * fractionY;
    }

    @Test
    @DisplayName("grids interpolate their nodes bilinearly, a later rectangle overriding an earlier one, the colors by lane")
    void gridInterpolation() {
        var writer = new IiqWriter(48, 24);
        writer.keepsBlack = true;
        java.util.Arrays.fill(writer.numbers, 2000);
        int[] wide = {0, 0, 48, 24, 16, 8}, small = {20, 10, 10, 6, 5, 3}, colors = {0, 0, 48, 24, 24, 12};
        var wideValues = new int[9];
        for (var node = 0; node < 9; node++) {
            wideValues[node] = 32768 + 700 * node - (node % 3) * 1500;
        }
        int[] smallValues = {30000, 31000, 34000, 36000};
        var colorValues = new int[8];
        for (var node = 0; node < 4; node++) {
            colorValues[node * 2] = 32768 + 1100 * node;
            colorValues[node * 2 + 1] = 32768 - 900 * node;
        }
        writer.calibrations.add(java.util.Map.entry(0x410, gridEntry(wide, wideValues)));
        writer.calibrations.add(java.util.Map.entry(0x410, gridEntry(small, smallValues)));
        writer.calibrations.add(java.util.Map.entry(0x40B, gridEntry(colors, colorValues)));
        var decoded = decode(writer.write());
        for (var y = 0; y < 24; y++) {
            for (var x = 0; x < 48; x++) {
                double want = 8000;
                if (y >= writer.top && y < writer.top + writer.imageHeight) {
                    var inSmall = x >= 20 && x < 30 && y >= 10 && y < 16;
                    want *= inSmall ? gridGain(small, smallValues, 1, 0, x, y) : gridGain(wide, wideValues, 1, 0, x, y);
                    var red = ((y - writer.top) & 1) == 0 && ((x - writer.left) & 1) == 0;
                    var blue = ((y - writer.top) & 1) == 1 && ((x - writer.left) & 1) == 1;
                    if (red || blue) {
                        want *= gridGain(colors, colorValues, 2, red ? 0 : 1, x, y);
                    }
                }
                assertEquals(Math.rint(want), decoded[y * 48 + x] & 0xFFFF, 1, "at " + x + "," + y);
            }
        }
    }

    @ParameterizedTest(name = "kind {0}")
    @ValueSource(ints = {131, 137})
    @DisplayName("both kinds of column defect records repair the whole column")
    void columnDefects(int kind) {
        var writer = new IiqWriter(32, 16);
        writer.keepsBlack = true;
        java.util.Arrays.fill(writer.numbers, 2000);
        for (var y = 0; y < 16; y++) {
            writer.numbers[y * 32 + 18] = 9000;
        }
        writer.defects.add(new int[]{18, 3, kind});
        writer.defects.add(new int[]{40, 3, 129});
        var parsed = RawParser.parse(MediaSource.of(writer.write()), MediaLimits.DEFAULT);
        var plane = SensorPlane.of(parsed.sensor, MediaLimits.DEFAULT);
        parsed.sensor.reader.read(plane, MediaLimits.DEFAULT);
        assertEquals(36000, plane.integers[3 * 32 + 18] & 0xFFFF, "before the repair");
        Opcodes.applyToSensor(parsed.sensor, plane, parsed.sensor.opcodes1);
        for (var y = 0; y < 16; y++) {
            // the repair of a column also reads the column's own photosites, so it lands near the good ones
            assertTrue(Math.abs((plane.integers[y * 32 + 18] & 0xFFFF) - 8000) < 7000, "row " + y);
            assertEquals(8000, plane.integers[y * 32 + 16] & 0xFFFF, "row " + y);
        }
    }

    @Test
    @DisplayName("a sensor data length past the file's end reads the data the file holds")
    void dataLengthPastEnd() {
        var writer = new IiqWriter(40, 16);
        writer.keepsBlack = true;
        System.arraycopy(noise(40 * 16, 1000, 3000, 11), 0, writer.numbers, 0, 40 * 16);
        var valid = writer.write();
        var patched = valid.clone();
        var entry = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(0x10F).putInt(2).array();
        var at = -1;
        for (var index = 0; index + 16 <= patched.length && at < 0; index++) {
            if (java.util.Arrays.equals(patched, index, index + 8, entry, 0, 8)) {
                at = index;
            }
        }
        assertTrue(at > 0);
        ByteBuffer.wrap(patched).order(ByteOrder.LITTLE_ENDIAN).putInt(at + 8, 0x7FFFFFF0);
        assertArrayEquals(decode(valid), decode(patched));
    }

    @Test
    @DisplayName("truncated and corrupted files fail with a media exception or decode to something, never otherwise")
    void robustness() {
        var writer = new IiqWriter(64, 16);
        writer.levelGrid = 33000;
        writer.redGrid = 33000;
        writer.blueGrid = 32000;
        writer.defects.add(new int[]{10, 10, 129});
        System.arraycopy(noise(64 * 16, 1100, 400, 7), 0, writer.numbers, 0, 64 * 16);
        var valid = writer.write();
        var random = new Random(1);
        for (var trial = 0; trial < 400; trial++) {
            var copy = trial % 2 == 0 ? java.util.Arrays.copyOf(valid, random.nextInt(valid.length)) : valid.clone();
            if (trial % 2 == 1) {
                for (var flip = 0; flip < 8; flip++) {
                    copy[random.nextInt(copy.length)] = (byte) random.nextInt(256);
                }
            }
            try {
                RawDecoder.decode(MediaSource.of(copy), RawDecoderOptions.defaults());
            } catch (MediaException expected) {
                assertNotNull(expected.getMessage());
            }
        }
    }
}
