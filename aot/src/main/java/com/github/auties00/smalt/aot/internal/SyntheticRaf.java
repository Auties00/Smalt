package com.github.auties00.smalt.aot.internal;

import com.github.auties00.smalt.image.RgbaPicture;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Writes the Fujifilm RAF files the camera raw training workloads decode, deterministically, with the sensor data
 * derived from a synthetic picture: lossless and lossy compressed files of a Bayer and of an X-Trans mosaic, and a
 * file of the bare sensor words of an older SuperCCD model, stored transposed.
 *
 * <p>The compressed sensor data is coded the way the decoder of {@code smalt-raw} documents: blocks of 768 columns,
 * six-row groups of eighteen lines, and a JPEG-LS-style context coder, near-lossless in four activity classes when
 * lossy.
 */
final class SyntheticRaf {
    /**
     * The X-Trans pattern of the compressed files, row by row (codes 0 red, 1 green, 2 blue).
     */
    private static final int[] XTRANS = {1, 1, 0, 1, 1, 2, 1, 1, 2, 1, 1, 0, 2, 0, 1, 0, 2, 1, 1, 1, 2, 1, 1, 0, 1, 1, 0,
            1, 1, 2, 0, 2, 1, 2, 0, 1};

    /**
     * The quantization levels a lossy file cycles through, group by group.
     */
    private static final int[] LEVELS = {1, 3, 4, 2, 0, 6};

    /**
     * The coded line pairs of the six passes of a group.
     */
    private static final int[][] PASSES = {{2, 7}, {8, 15}, {3, 9}, {10, 16}, {4, 11}, {12, 17}};

    /**
     * The width of a coded block.
     */
    private static final int BLOCK = 768;

    /**
     * The bits of a sample.
     */
    private static final int BITS = 14;

    /**
     * Prevents instantiation.
     */
    private SyntheticRaf() {
        throw new AssertionError();
    }

    /**
     * Writes a compressed RAF file of a 14-bit mosaic of a picture.
     *
     * @param picture the picture, whose height is a multiple of 6
     * @param xtrans {@code true} for an X-Trans mosaic, {@code false} for an RGGB one
     * @param lossy {@code true} for near-lossless coding with the levels of {@link #LEVELS}, {@code false} for
     * lossless coding
     * @return the file
     */
    static byte[] compressed(RgbaPicture picture, boolean xtrans, boolean lossy) {
        var width = picture.width();
        var height = picture.height();
        var samples = new int[width * height];
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                samples[y * width + x] = sample(picture, x, y, color(xtrans, y, x));
            }
        }
        var blocks = (width + BLOCK - 1) / BLOCK;
        var groups = (height + 5) / 6;
        var coded = new byte[blocks][];
        for (var block = 0; block < blocks; block++) {
            coded[block] = new Coder(samples, width, height, block * BLOCK, xtrans, lossy).code(groups);
        }
        var head = (16 + 4 * blocks + 15) & ~15;
        var table = lossy ? (groups + 15) & ~15 : 0;
        var size = head + blocks * table;
        for (var block : coded) {
            size += block.length;
        }
        var stream = ByteBuffer.allocate(size);
        stream.put((byte) 'I').put((byte) 'S').put((byte) (lossy ? 0 : 1)).put((byte) (xtrans ? 16 : 0))
                .put((byte) BITS).putShort((short) height).putShort((short) (blocks * BLOCK)).putShort((short) width)
                .putShort((short) BLOCK).put((byte) blocks).putShort((short) groups);
        for (var block : coded) {
            stream.putInt(block.length);
        }
        for (var block = 0; block < blocks && lossy; block++) {
            stream.position(head + block * table);
            for (var group = 0; group < groups; group++) {
                stream.put((byte) LEVELS[group % LEVELS.length]);
            }
        }
        stream.position(head + blocks * table);
        for (var block : coded) {
            stream.put(block);
        }
        var records = new ByteArrayOutputStream();
        var count = record(records, 0x100, pair(height, width));
        count += record(records, 0x110, pair(0, 0));
        count += record(records, 0x111, pair(height, width));
        if (xtrans) {
            var pattern = new byte[36];
            for (var index = 0; index < 36; index++) {
                pattern[index] = (byte) XTRANS[35 - index];
            }
            count += record(records, 0x131, pattern);
        }
        return file(count, records.toByteArray(), sensorBlock(stream.array(), width, height, xtrans ? 36 : 4),
                lossy ? 3 : 2);
    }

    /**
     * Writes a RAF file of the bare 14-bit sensor words of a transposed SuperCCD SR sensor: a staggered mosaic of a
     * picture of the 2x4 filter array, stored column by column, and the second image of its low-sensitivity
     * photodiodes, which read a sixteenth of the light and hold the highlights the first image clips.
     *
     * @param picture the picture, whose width and height are even
     * @return the file
     */
    static byte[] superCcd(RgbaPicture picture) {
        var width = picture.width();
        var height = picture.height();
        var words = ByteBuffer.allocate(width * height * 2).order(ByteOrder.LITTLE_ENDIAN);
        var second = ByteBuffer.allocate(width * height * 2).order(ByteOrder.LITTLE_ENDIAN);
        // stored row x holds column x of the picture
        for (var x = 0; x < width; x++) {
            for (var y = 0; y < height; y++) {
                var row = y & 1;
                var column = x & 3;
                var color = row == 0 ? new int[]{0, 1, 2, 1}[column] : new int[]{2, 1, 0, 1}[column];
                // the picture's light, doubled, clips in the first image above a 14-bit white
                var light = 2 * (sample(picture, x, y, color) - 256);
                words.putShort((short) Math.min(16383, 256 + light));
                second.putShort((short) (256 + light / 16));
            }
        }
        var records = new ByteArrayOutputStream();
        var count = record(records, 0x100, pair(width, height));
        count += record(records, 0x120, pair(0, 0));
        count += record(records, 0x121, pair(width / 2, 2 * height));
        count += record(records, 0x122, new byte[8]);
        count += record(records, 0x130, new byte[]{(byte) 0x83, (byte) 0x82, (byte) 0x81, (byte) 0x80});
        count += record(records, 0x2FF0, ByteBuffer.allocate(16).putShort((short) 320).putShort((short) 520)
                .putShort((short) 320).putShort((short) 350).array());
        count += record(records, 0x4000, ByteBuffer.allocate(16).putShort((short) 256).array());
        return file(count, records.toByteArray(), words.array(), 0, second.array());
    }

    /**
     * Returns the color of a photosite of the compressed mosaic.
     *
     * @param xtrans whether the mosaic is X-Trans
     * @param row the row of the photosite
     * @param column the column of the photosite
     * @return the color code
     */
    private static int color(boolean xtrans, int row, int column) {
        if (xtrans) {
            return XTRANS[row % 6 * 6 + column % 6];
        }
        return (row & 1) == 0 ? ((column & 1) == 0 ? 0 : 1) : ((column & 1) == 0 ? 1 : 2);
    }

    /**
     * Returns a 14-bit sample of a picture's channel at a pixel, squared to a linear-light value above a black
     * level of 256.
     *
     * @param picture the source picture
     * @param x the column
     * @param y the row
     * @param channel the index of the red, green or blue channel
     * @return the sample
     */
    private static int sample(RgbaPicture picture, int x, int y, int channel) {
        var value = (picture.pixels()[y * picture.stride() + x * 4 + channel] & 0xFF) / 255.0;
        return 256 + (int) Math.round(value * value * 12000);
    }

    /**
     * Assembles a RAF file: the 160-byte header, the record list and the sensor data.
     *
     * @param count the number of records
     * @param records the encoded records
     * @param sensor the sensor data
     * @param compression the compression of the header
     * @return the file
     */
    private static byte[] file(int count, byte[] records, byte[] sensor, int compression) {
        return file(count, records, sensor, compression, null);
    }

    /**
     * Assembles a RAF file: the 160-byte header, the record list and the sensor data, and the second image of a
     * sensor of two images per photosite (the same record list again and its sensor data, placed by the header
     * offsets 120 to 136).
     *
     * @param count the number of records
     * @param records the encoded records
     * @param sensor the sensor data
     * @param compression the compression of the header
     * @param second the sensor data of the second image, or {@code null} for none
     * @return the file
     */
    private static byte[] file(int count, byte[] records, byte[] sensor, int compression, byte[] second) {
        var header = 160;
        var list = 4 + records.length;
        var extra = second != null ? list + second.length : 0;
        var out = ByteBuffer.allocate(header + list + sensor.length + extra);
        out.put("FUJIFILMCCD-RAW 0201FF129502".getBytes(StandardCharsets.ISO_8859_1));
        out.put(Arrays.copyOf("SYNTHETIC".getBytes(StandardCharsets.ISO_8859_1), 32));
        out.put("0100".getBytes(StandardCharsets.ISO_8859_1));
        out.position(84);
        out.putInt(0).putInt(0).putInt(header).putInt(list).putInt(header + list).putInt(sensor.length)
                .putInt(compression);
        if (second != null) {
            var at = header + list + sensor.length;
            out.position(120);
            out.putInt(at).putInt(list).putInt(at + list).putInt(second.length);
        }
        out.position(header);
        out.putInt(count).put(records).put(sensor);
        if (second != null) {
            out.putInt(count).put(records).put(second);
        }
        return out.array();
    }

    /**
     * Wraps sensor data in the little-endian TIFF structure of a RAF sensor block, with its private directory.
     *
     * @param data the sensor data
     * @param width the width in pixels
     * @param height the height in pixels
     * @param blacks the number of black levels, one per photosite of the filter array
     * @return the sensor block
     */
    private static byte[] sensorBlock(byte[] data, int width, int height, int blacks) {
        var entries = 7;
        var sub = 26;
        var blackAt = sub + 2 + 12 * entries + 4;
        var balanceAt = blackAt + 4 * blacks;
        var dataAt = (balanceAt + 12 + 15) & ~15;
        var out = ByteBuffer.allocate(dataAt + data.length).order(ByteOrder.LITTLE_ENDIAN);
        out.put((byte) 'I').put((byte) 'I').putShort((short) 42).putInt(8).putShort((short) 1);
        entry(out, 0xF000, 13, 1, sub);
        out.putInt(0);
        out.position(sub);
        out.putShort((short) entries);
        entry(out, 0xF001, 4, 1, width);
        entry(out, 0xF002, 4, 1, height);
        entry(out, 0xF003, 4, 1, BITS);
        entry(out, 0xF007, 4, 1, dataAt);
        entry(out, 0xF008, 4, 1, data.length);
        entry(out, 0xF00A, 4, blacks, blackAt);
        entry(out, 0xF00D, 4, 3, balanceAt);
        out.putInt(0);
        out.position(blackAt);
        for (var index = 0; index < blacks; index++) {
            out.putInt(256);
        }
        out.putInt(302).putInt(512).putInt(410);
        out.position(dataAt);
        out.put(data);
        return out.array();
    }

    /**
     * Writes a TIFF directory entry.
     *
     * @param out the destination
     * @param tag the TIFF tag
     * @param type the field type
     * @param count the value count
     * @param value the value or offset
     */
    private static void entry(ByteBuffer out, int tag, int type, int count, int value) {
        out.putShort((short) tag).putShort((short) type).putInt(count).putInt(value);
    }

    /**
     * Appends a record.
     *
     * @param out the encoded records
     * @param tag the record tag
     * @param value the record value
     * @return 1, the number of records appended
     */
    private static int record(ByteArrayOutputStream out, int tag, byte[] value) {
        out.writeBytes(ByteBuffer.allocate(4).putShort((short) tag).putShort((short) value.length).array());
        out.writeBytes(value);
        return 1;
    }

    /**
     * {@return two big-endian 16-bit values}
     *
     * @param first the first value
     * @param second the second value
     */
    private static byte[] pair(int first, int second) {
        return ByteBuffer.allocate(4).putShort((short) first).putShort((short) second).array();
    }

    /**
     * Codes one block of a compressed file. Instances are not thread-safe.
     */
    private static final class Coder {
        private final int[] samples;
        private final int width;
        private final int height;
        private final int x0;
        private final boolean lossy;
        private final int slots;
        private final int maximum = (1 << BITS) - 1;
        private final int[][] lines;
        private final int[][] rowOf;
        private final int[][] columnOf;
        private final int[][] sums = new int[4][6 * 41];
        private final int[][] counts = new int[4][6 * 41];
        private final int[] familyNear = {-1, -1, -1, -1};
        private final int[] classNear = new int[4];
        private final int[] classFamily = new int[4];
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private int accumulator;
        private int pending;
        private int group;

        /**
         * Constructs the coder of a block.
         *
         * @param samples the mosaic samples, row by row
         * @param width the width of the mosaic
         * @param height the height of the mosaic
         * @param x0 the first column of the block
         * @param xtrans whether the mosaic is X-Trans
         * @param lossy whether the coding is near-lossless
         */
        Coder(int[] samples, int width, int height, int x0, boolean xtrans, boolean lossy) {
            this.samples = samples;
            this.width = width;
            this.height = height;
            this.x0 = x0;
            this.lossy = lossy;
            this.slots = xtrans ? BLOCK / 6 * 4 : BLOCK / 2;
            this.lines = new int[18][slots + 2];
            this.rowOf = new int[18][slots];
            this.columnOf = new int[18][slots];
            for (var line : rowOf) {
                Arrays.fill(line, -1);
            }
            for (var row = 0; row < 6; row++) {
                for (var column = 0; column < BLOCK; column++) {
                    var color = color(xtrans, row, x0 + column);
                    var line = color == 0 ? 2 + row / 2 : color == 1 ? 7 + row : 15 + row / 2;
                    var slot = xtrans ? column / 6 * 4 + (2 * (column % 6) + 1) / 3 : column / 2;
                    rowOf[line][slot] = row;
                    columnOf[line][slot] = column;
                }
            }
        }

        /**
         * Codes the block.
         *
         * @param groups the number of six-row groups
         * @return the coded block, padded to 16 bytes
         */
        byte[] code(int groups) {
            for (group = 0; group < groups; group++) {
                var level = lossy ? LEVELS[group % LEVELS.length] : 0;
                classNear[0] = 0;
                classNear[1] = Math.min(1, level);
                classNear[2] = Math.min(2, level);
                classNear[3] = level;
                for (var c = 0; c < 4; c++) {
                    var top = c;
                    while (top < 3 && classNear[top + 1] == classNear[c]) {
                        top++;
                    }
                    classFamily[c] = top;
                }
                if (group > 0) {
                    for (var copy : new int[][]{{3, 0}, {4, 1}, {11, 5}, {12, 6}, {16, 13}, {17, 14}}) {
                        System.arraycopy(lines[copy[0]], 0, lines[copy[1]], 0, slots + 2);
                    }
                }
                for (var pass = 0; pass < 6; pass++) {
                    for (var line : PASSES[pass]) {
                        lines[line][0] = lines[line - 1][1];
                        lines[line][slots + 1] = lines[line - 1][slots];
                    }
                    var set = pass % 3 * 82;
                    var even = 0;
                    var odd = 1;
                    while (even < slots || odd < slots) {
                        if (even < slots) {
                            even(PASSES[pass][0], even, set);
                            even(PASSES[pass][1], even, set);
                            even += 2;
                        }
                        if (even > 8 || even >= slots) {
                            if (odd < slots) {
                                odd(PASSES[pass][0], odd, set + 41);
                                odd(PASSES[pass][1], odd, set + 41);
                            }
                            odd += 2;
                        }
                    }
                }
            }
            put(0, 7);
            while (out.size() % 16 != 0) {
                out.write(0);
            }
            return out.toByteArray();
        }

        /**
         * Returns the sample a slot codes, or the prediction for a slot outside the mosaic.
         *
         * @param line the index of the line, from 2 to 17
         * @param slot the index of the slot
         * @param prediction the value predicted for the slot
         * @return the sample
         */
        private int value(int line, int slot, int prediction) {
            var y = group * 6 + rowOf[line][slot];
            var x = x0 + columnOf[line][slot];
            return y < height && x < width ? samples[y * width + x] : Math.clamp(prediction, 0, maximum);
        }

        /**
         * Codes, or interpolates, an even slot.
         *
         * @param line the index of the line, from 2 to 17
         * @param slot the even slot
         * @param set the offset of the context set
         */
        private void even(int line, int slot, int set) {
            var current = lines[line];
            var above = lines[line - 1];
            var i = slot + 1;
            int b = above[i];
            int c = above[i - 1];
            int d = above[i + 1];
            int f = lines[line - 2][i];
            int dc = Math.abs(b - c);
            int dd = Math.abs(b - d);
            int df = Math.abs(b - f);
            var prediction = dc > dd && dc > df ? (f + d + 2 * b) >> 2 : dd > dc && dd > df ? (f + c + 2 * b) >> 2
                    : (c + 2 * b + d) >> 2;
            if (rowOf[line][slot] < 0) {
                current[i] = prediction;
                return;
            }
            current[i] = sample(set, b - f, c - b, prediction, value(line, slot, prediction));
        }

        /**
         * Codes an odd slot.
         *
         * @param line the index of the line, from 2 to 17
         * @param slot the odd slot
         * @param set the offset of the context set
         */
        private void odd(int line, int slot, int set) {
            var current = lines[line];
            var above = lines[line - 1];
            var i = slot + 1;
            int a = current[i - 1];
            int g = current[i + 1];
            int b = above[i];
            int c = above[i - 1];
            int d = above[i + 1];
            var prediction = (b > c && b > d) || (b < c && b < d) ? (a + g + 2 * b) >> 2 : (a + g) >> 1;
            current[i] = sample(set, b - c, c - a, prediction, value(line, slot, prediction));
        }

        /**
         * {@return the number of quantized values of an error bound}
         *
         * @param near the error bound
         */
        private int range(int near) {
            return (maximum + 2 * near) / (2 * near + 1) + 1;
        }

        /**
         * Codes a sample within the error bound of its activity class.
         *
         * @param set the offset of the context set
         * @param first the first context gradient
         * @param second the second context gradient
         * @param prediction the value predicted for the sample
         * @param value the sample
         * @return the value a decoder reconstructs
         */
        private int sample(int set, int first, int second, int prediction, int value) {
            var activity = Math.abs(first) + Math.abs(second);
            var activityClass = activity <= 5 ? 0 : Math.min(activity - 5, 3);
            var near = classNear[activityClass];
            var family = classFamily[activityClass];
            if (familyNear[family] != near) {
                Arrays.fill(sums[family], Math.max(2, (range(near) + 32) >> 6));
                Arrays.fill(counts[family], 1);
                familyNear[family] = near;
            }
            var context = 9 * quantize(first, near) + quantize(second, near);
            var sign = context < 0 ? -1 : 1;
            var step = 2 * near + 1;
            var difference = (value - prediction) * sign;
            var error = difference > 0 ? (difference + near) / step : -((near - difference) / step);
            var range = range(near);
            if (near == 0) {
                var half = (maximum + 1) / 2;
                error = ((error + half) & maximum) - half;
            } else {
                if (error < 0) {
                    error += range;
                }
                if (error >= (range + 1) / 2) {
                    error -= range;
                }
            }
            residual(sums[family], counts[family], set + Math.abs(context), near, error);
            if (near == 0) {
                return (prediction + error * sign) & maximum;
            }
            var reconstruction = prediction + error * sign * step;
            if (reconstruction < -near) {
                reconstruction += range * step;
            } else if (reconstruction > maximum + near) {
                reconstruction -= range * step;
            }
            return Math.clamp(reconstruction, 0, maximum);
        }

        /**
         * Codes a residual and updates its context.
         *
         * @param sums the error sums of the class's statistics
         * @param counts the counts of the class's statistics
         * @param index the index of the context
         * @param near the error bound
         * @param error the quantized residual
         */
        private void residual(int[] sums, int[] counts, int index, int near, int error) {
            var k = 0;
            while ((counts[index] << k) < sums[index]) {
                k++;
            }
            var quantized = 32 - Integer.numberOfLeadingZeros(range(near) - 1);
            var escape = 4 * BITS - quantized - 1;
            var mapped = error >= 0 ? 2 * error : -2 * error - 1;
            if ((mapped >>> k) < escape) {
                put(0, mapped >>> k);
                put(1, 1);
                put(mapped & ((1 << k) - 1), k);
            } else {
                put(0, escape);
                put(1, 1);
                put(mapped - 1, quantized);
            }
            sums[index] += Math.abs(error);
            if (counts[index] == 64) {
                sums[index] >>= 1;
                counts[index] >>= 1;
            }
            counts[index]++;
        }

        /**
         * Writes bits, most significant first.
         *
         * @param value the bits
         * @param count the number of bits; above 31 only for a value of 0
         */
        private void put(int value, int count) {
            for (var bit = count - 1; bit >= 0; bit--) {
                accumulator = (accumulator << 1) | (bit < 32 ? (value >>> bit) & 1 : 0);
                if (++pending == 8) {
                    out.write(accumulator & 0xFF);
                    pending = 0;
                    accumulator = 0;
                }
            }
        }

        /**
         * {@return the region of a gradient, from -4 to 4, with the thresholds of an error bound}
         *
         * @param gradient the difference of two neighboring samples
         * @param near the error bound
         */
        private static int quantize(int gradient, int near) {
            if (gradient <= -(276 + 7 * near)) {
                return -4;
            }
            if (gradient <= -(67 + 5 * near)) {
                return -3;
            }
            if (gradient <= -(18 + 3 * near)) {
                return -2;
            }
            if (gradient < -near) {
                return -1;
            }
            if (gradient <= near) {
                return 0;
            }
            if (gradient < 18 + 3 * near) {
                return 1;
            }
            return gradient < 67 + 5 * near ? 2 : gradient < 276 + 7 * near ? 3 : 4;
        }
    }
}
