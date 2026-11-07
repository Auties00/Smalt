package com.github.auties00.smalt.image.raw;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Writes Fujifilm RAF files for the tests: files whose sensor data is coded the way compressed RAF files code it
 * (lossless or lossy, Bayer or X-Trans, 12 to 16 bits) or packed as uncompressed 12-bit samples, with a TIFF sensor
 * block, and files of the bare sensor words of
 * older models, described by their records; uncompressed files may carry a second image, as the SuperCCD SR and EXR
 * models do. The coder mirrors the coding the decoder documents; it is written
 * independently of the decoder, from that description.
 */
public final class RafWriter {
    /** The X-Trans pattern of the X-T2 and later models in stored coordinates, row by row. */
    public static final int[] XTRANS = {1, 1, 0, 1, 1, 2, 1, 1, 2, 1, 1, 0, 2, 0, 1, 0, 2, 1, 1, 1, 2, 1, 1, 0, 1, 1, 0,
            1, 1, 2, 0, 2, 1, 2, 0, 1};

    /** The width of the image in pixels. */
    public int width = 96;

    /** The height of the image in pixels. */
    public int height = 24;

    /** The bits of a sample, from 12 to 16 for compressed files. */
    public int bits = 14;

    /** Whether the sensor is X-Trans (otherwise Bayer RGGB). */
    public boolean xtrans;

    /** The width of a coded block, a multiple of 6. */
    public int blockWidth = 768;

    /** The samples, row by row, {@code width * height} of them. */
    public short[] samples;

    /** The black level of every photosite. */
    public int black = 256;

    /** The as-shot white balance levels as green, red and blue. */
    public int[] balance = {302, 512, 410};

    /**
     * The quantization levels a lossy stream cycles through, block by block and group by group, or {@code null} for
     * a lossless stream.
     */
    public int[] levels;

    /** The samples a decoder reconstructs from the last stream written: the samples themselves when lossless. */
    public short[] reconstructed;

    /**
     * The samples of a second image, stored like the first at the header offset 128 with its own copy of the
     * records, or {@code null} for none: {@code width * height} samples for {@link #packed12}, the stored words for
     * {@link #legacy}.
     */
    public short[] second;

    /** The camera's output width and height of record {@code 0x0112}, or {@code null} for none. */
    public int[] output;

    /**
     * The outputs of the sensor of a {@link #legacy} file: above 1, the stored grid is written as that many vertical
     * stripes of equal width, each whole, one after the other, the header offsets 112 and 116 stating the bytes of a
     * stripe's row and of a stripe, and record {@code 0x0122} marks the outputs.
     */
    public int stripes = 1;

    /** The model name of the header, for files of a single output. */
    public String model = "RafWriter";

    /** Whether the {@link #second} image of a {@link #legacy} file is written row by row between the first's rows. */
    public boolean interleaved;

    /**
     * Fills the samples with smooth gradients, edges and noise, and a few photosites at the extremes so that the
     * coder escapes.
     *
     * @param seed the seed of the noise
     * @return this writer
     */
    public RafWriter scene(long seed) {
        samples = new short[width * height];
        var random = new java.util.Random(seed);
        var maximum = (1 << bits) - 1;
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                double value = maximum * (0.1 + 0.3 * x / width + 0.2 * y / height);
                if (x > width / 2) {
                    value += maximum * 0.25;
                }
                value += random.nextGaussian() * maximum * 0.01;
                if (random.nextInt(97) == 0) {
                    value = random.nextBoolean() ? maximum : 0;
                }
                samples[y * width + x] = (short) Math.clamp(Math.round(value), 0, maximum);
            }
        }
        return this;
    }

    /**
     * Writes a compressed file.
     *
     * @return the file
     */
    public byte[] compressed() {
        var stream = stream();
        var records = records(true);
        var black = new int[xtrans ? 36 : 4];
        Arrays.fill(black, this.black);
        var block = sensorBlock(stream, black, null);
        return file(records, block, levels == null ? 2 : 3);
    }

    /**
     * Writes an uncompressed Bayer file of 12-bit samples in rows of whole 32-bit words: packed least significant bit
     * first, or most significant bit first in a stream of little-endian words.
     *
     * @param msbFirst whether the samples are packed most significant bit first in little-endian words
     * @param layout the record {@code 0x0130}, or {@code null} for none
     * @return the file
     */
    public byte[] packed12(boolean msbFirst, byte[] layout) {
        var words = (width * 12 + 31) / 32;
        var records = new ByteArrayOutputStream();
        var count = 0;
        count += record(records, 0x100, pair(height, width));
        if (output != null) {
            count += record(records, 0x112, pair(output[0], output[1]));
        }
        if (layout != null) {
            count += record(records, 0x130, layout);
        }
        var list = ByteBuffer.allocate(4 + records.size()).putInt(count).put(records.toByteArray()).array();
        var black = new int[4];
        Arrays.fill(black, this.black);
        var block = sensorBlock(packed(samples, msbFirst, words), black, new int[]{4, words, msbFirst ? 1 : 0});
        var other = second != null
                ? sensorBlock(packed(second, msbFirst, words), black, new int[]{4, words, msbFirst ? 1 : 0}) : null;
        return file(list, block, 0, other != null ? list : null, other);
    }

    // packs 12-bit samples in rows of whole 32-bit words
    private byte[] packed(short[] values, boolean msbFirst, int words) {
        var data = new byte[words * 4 * height];
        for (var y = 0; y < height; y++) {
            var row = y * words * 4;
            for (var x = 0; x < width; x++) {
                var value = values[y * width + x] & 0xFFF;
                for (var bit = 0; bit < 12; bit++) {
                    var set = msbFirst ? (value >> (11 - bit) & 1) != 0 : (value >> bit & 1) != 0;
                    if (!set) {
                        continue;
                    }
                    var position = x * 12 + bit;
                    if (msbFirst) {
                        // bit 0 of the stream is the top bit of the first little-endian word
                        var word = position / 32;
                        var within = 31 - position % 32;
                        data[row + word * 4 + within / 8] |= (byte) (1 << (within % 8));
                    } else {
                        data[row + position / 8] |= (byte) (1 << (position % 8));
                    }
                }
            }
        }
        return data;
    }

    /**
     * Codes the samples as a compressed stream: header, block table, the quantization levels of a lossy stream and
     * the blocks; fills {@link #reconstructed}.
     *
     * @return the stream
     */
    public byte[] stream() {
        var blocks = (width + blockWidth - 1) / blockWidth;
        var groups = (height + 5) / 6;
        reconstructed = samples.clone();
        var coded = new byte[blocks][];
        for (var block = 0; block < blocks; block++) {
            coded[block] = new BlockCoder(block * blockWidth).code(groups);
        }
        var head = (16 + 4 * blocks + 15) & ~15;
        var table = levels == null ? 0 : (groups + 15) & ~15;
        var size = head + blocks * table;
        for (var block : coded) {
            size += block.length;
        }
        var out = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN);
        out.put((byte) 'I').put((byte) 'S').put((byte) (levels == null ? 1 : 0)).put((byte) (xtrans ? 16 : 0))
                .put((byte) bits);
        out.putShort((short) height).putShort((short) (blocks * blockWidth)).putShort((short) width)
                .putShort((short) blockWidth).put((byte) blocks).putShort((short) groups);
        for (var block : coded) {
            out.putInt(block.length);
        }
        out.position(head);
        for (var block = 0; block < blocks && levels != null; block++) {
            for (var group = 0; group < groups; group++) {
                out.put((byte) level(block, group));
            }
            out.position(head + (block + 1) * table);
        }
        for (var block : coded) {
            out.put(block);
        }
        return out.array();
    }

    private int level(int block, int group) {
        return levels[(block * 7 + group) % levels.length];
    }

    /**
     * Writes a file of bare sensor words.
     *
     * @param words the words, {@code rows * columns} of them, row by row as stored
     * @param rows the stored rows
     * @param columns the stored columns
     * @param bigEndian whether the words are big-endian
     * @param layout the first byte of the layout record {@code 0x0130}
     * @param area the image area records {@code 0x0120} and {@code 0x0121} (top, left, height, width), or
     * {@code null} for none
     * @param crop the crop records {@code 0x0110} and {@code 0x0111} (top, left, height, width), or {@code null}
     * @return the file
     */
    public byte[] legacy(short[] words, int rows, int columns, boolean bigEndian, int layout, int[] area, int[] crop) {
        var records = new ByteArrayOutputStream();
        var count = 0;
        count += record(records, 0x100, pair(rows, columns));
        if (crop != null) {
            count += record(records, 0x110, pair(crop[0], crop[1]));
            count += record(records, 0x111, pair(crop[2], crop[3]));
        }
        if (area != null) {
            count += record(records, 0x120, pair(area[0], area[1]));
            count += record(records, 0x121, pair(area[2], area[3]));
        }
        if (area != null || stripes > 1) {
            count += record(records, 0x122, stripes > 1 ? new byte[]{0, 1, 0, 0, 0, 0, 0, 1} : new byte[8]);
        }
        if (output != null) {
            count += record(records, 0x112, pair(output[0], output[1]));
        }
        count += record(records, 0x130, new byte[]{(byte) layout, 0, 0, 0});
        var levels = ByteBuffer.allocate(16);
        for (var index = 0; index < 8; index++) {
            levels.putShort((short) black);
        }
        count += record(records, 0x4000, levels.array());
        count += record(records, 0x2FF0, ByteBuffer.allocate(16).putShort((short) balance[0])
                .putShort((short) balance[1]).putShort((short) balance[0]).putShort((short) balance[2]).array());
        var list = ByteBuffer.allocate(4 + records.size()).putInt(count).put(records.toByteArray()).array();
        var first = bare(striped(words, rows, columns), bigEndian);
        var other = second != null ? bare(striped(second, rows, columns), bigEndian) : null;
        if (other != null && interleaved) {
            return interleaved(list, first, other, columns * 2);
        }
        var file = file(list, first, 0, other != null ? list : null, other);
        if (stripes > 1) {
            ByteBuffer.wrap(file).putInt(112, columns / stripes * 2).putInt(116, columns / stripes * 2 * rows);
        }
        return file;
    }

    // the two images' rows alternate in one block, the header offsets 112 and 116 stating the row and its stride
    private byte[] interleaved(byte[] records, byte[] first, byte[] other, int row) {
        var both = new byte[first.length + other.length];
        for (var y = 0; y < first.length / row; y++) {
            System.arraycopy(first, y * row, both, 2 * y * row, row);
            System.arraycopy(other, y * row, both, (2 * y + 1) * row, row);
        }
        var file = file(records, both, 0);
        var header = ByteBuffer.wrap(file);
        var sensor = header.getInt(100);
        header.putInt(104, first.length).putInt(112, row).putInt(116, 2 * row);
        header.putInt(120, 160).putInt(124, records.length).putInt(128, sensor + row).putInt(132, other.length);
        return file;
    }

    // the stored grid as the vertical stripes of the sensor's outputs, one after the other
    private short[] striped(short[] words, int rows, int columns) {
        if (stripes == 1) {
            return words;
        }
        var width = columns / stripes;
        var out = new short[words.length];
        for (var stripe = 0; stripe < stripes; stripe++) {
            for (var row = 0; row < rows; row++) {
                System.arraycopy(words, row * columns + stripe * width, out, (stripe * rows + row) * width, width);
            }
        }
        return out;
    }

    private static byte[] bare(short[] words, boolean bigEndian) {
        var data = ByteBuffer.allocate(words.length * 2).order(bigEndian ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN);
        for (var word : words) {
            data.putShort(word);
        }
        return data.array();
    }

    private byte[] file(byte[] records, byte[] sensor, int compression) {
        return file(records, sensor, compression, null, null);
    }

    // the second record list and sensor block follow the first ones, placed by the header offsets 120 to 136
    private byte[] file(byte[] records, byte[] sensor, int compression, byte[] secondRecords, byte[] secondSensor) {
        var header = 160;
        var extra = secondSensor != null ? secondRecords.length + secondSensor.length : 0;
        var out = ByteBuffer.allocate(header + records.length + sensor.length + extra).order(ByteOrder.BIG_ENDIAN);
        out.put("FUJIFILMCCD-RAW 0201FF129502".getBytes(StandardCharsets.ISO_8859_1));
        var name = Arrays.copyOf(model.getBytes(StandardCharsets.ISO_8859_1), 32);
        out.put(name);
        out.put("0100".getBytes(StandardCharsets.ISO_8859_1));
        out.position(84);
        out.putInt(0).putInt(0).putInt(header).putInt(records.length).putInt(header + records.length)
                .putInt(sensor.length).putInt(compression);
        if (secondSensor != null) {
            var at = header + records.length + sensor.length;
            out.position(120);
            out.putInt(at).putInt(secondRecords.length).putInt(at + secondRecords.length).putInt(secondSensor.length);
        }
        out.position(header);
        out.put(records).put(sensor);
        if (secondSensor != null) {
            out.put(secondRecords).put(secondSensor);
        }
        return out.array();
    }

    private byte[] records(boolean withPattern) {
        var records = new ByteArrayOutputStream();
        var count = 0;
        count += record(records, 0x100, pair(height, width));
        count += record(records, 0x110, pair(2, 4));
        count += record(records, 0x111, pair(height - 4, width - 8));
        if (xtrans && withPattern) {
            var pattern = new byte[36];
            for (var index = 0; index < 36; index++) {
                pattern[index] = (byte) XTRANS[35 - index];
            }
            count += record(records, 0x131, pattern);
        }
        return ByteBuffer.allocate(4 + records.size()).putInt(count).put(records.toByteArray()).array();
    }

    // packing: the values of 0xF004 (word bytes), 0xF005 (words per row) and 0xF006 (bit order), or null for none
    private byte[] sensorBlock(byte[] data, int[] blackLevels, int[] packing) {
        // a little-endian TIFF structure: one directory holding the private directory 0xF000
        var entries = packing == null ? 7 : 10;
        var sub = 8 + 2 + 12 + 4;
        var subSize = 2 + 12 * entries + 4;
        var blackAt = sub + subSize;
        var balanceAt = blackAt + 4 * blackLevels.length;
        var dataAt = (balanceAt + 12 + 15) & ~15;
        var out = ByteBuffer.allocate(dataAt + data.length).order(ByteOrder.LITTLE_ENDIAN);
        out.put((byte) 'I').put((byte) 'I').putShort((short) 42).putInt(8);
        out.putShort((short) 1);
        entry(out, 0xF000, 13, 1, sub);
        out.putInt(0);
        out.position(sub);
        out.putShort((short) entries);
        entry(out, 0xF001, 4, 1, width);
        entry(out, 0xF002, 4, 1, height);
        entry(out, 0xF003, 4, 1, bits);
        if (packing != null) {
            entry(out, 0xF004, 4, 1, packing[0]);
            entry(out, 0xF005, 4, 1, packing[1]);
            entry(out, 0xF006, 4, 1, packing[2]);
        }
        entry(out, 0xF007, 4, 1, dataAt);
        entry(out, 0xF008, 4, 1, data.length);
        entry(out, 0xF00A, 4, blackLevels.length, blackAt);
        entry(out, 0xF00D, 4, 3, balanceAt);
        out.putInt(0);
        out.position(blackAt);
        for (var level : blackLevels) {
            out.putInt(level);
        }
        out.putInt(balance[0]).putInt(balance[1]).putInt(balance[2]);
        out.position(dataAt);
        out.put(data);
        return out.array();
    }

    private static void entry(ByteBuffer out, int tag, int type, int count, int value) {
        out.putShort((short) tag).putShort((short) type).putInt(count).putInt(value);
    }

    private static int record(ByteArrayOutputStream out, int tag, byte[] value) {
        out.writeBytes(ByteBuffer.allocate(4).putShort((short) tag).putShort((short) value.length).array());
        out.writeBytes(value);
        return 1;
    }

    private static byte[] pair(int first, int second) {
        return ByteBuffer.allocate(4).putShort((short) first).putShort((short) second).array();
    }

    /**
     * Codes one block.
     */
    private final class BlockCoder {
        private static final int[][] PASSES = {{2, 7}, {8, 15}, {3, 9}, {10, 16}, {4, 11}, {12, 17}};
        private final int x0;
        private final int slots;
        private final int maximum = (1 << bits) - 1;
        private final int[][] lines;
        private final int[][] rowOf;
        private final int[][] columnOf;
        private final int[][] sums = new int[4][6 * 41];
        private final int[][] counts = new int[4][6 * 41];
        private final int[] familyNear = {-1, -1, -1, -1};
        private final int[] classNear = new int[4];
        private final int[] classFamily = new int[4];
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private long accumulator;
        private int pending;
        private int group;

        BlockCoder(int x0) {
            this.x0 = x0;
            this.slots = xtrans ? blockWidth / 6 * 4 : blockWidth / 2;
            this.lines = new int[18][slots + 2];
            this.rowOf = new int[18][slots];
            this.columnOf = new int[18][slots];
            for (var line : rowOf) {
                Arrays.fill(line, -1);
            }
            for (var row = 0; row < 6; row++) {
                for (var column = 0; column < blockWidth; column++) {
                    int color;
                    if (xtrans) {
                        color = XTRANS[row % 6 * 6 + (x0 + column) % 6];
                    } else {
                        color = (row & 1) == 0 ? ((column & 1) == 0 ? 0 : 1) : ((column & 1) == 0 ? 1 : 2);
                    }
                    var line = color == 0 ? 2 + row / 2 : color == 1 ? 7 + row : 15 + row / 2;
                    var slot = xtrans ? column / 6 * 4 + (2 * (column % 6) + 1) / 3 : column / 2;
                    rowOf[line][slot] = row;
                    columnOf[line][slot] = column;
                }
            }
        }

        byte[] code(int groups) {
            for (group = 0; group < groups; group++) {
                var level = levels == null ? 0 : level(x0 / blockWidth, group);
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
                    var first = PASSES[pass][0];
                    var second = PASSES[pass][1];
                    var set = pass % 3 * 82;
                    for (var line : PASSES[pass]) {
                        lines[line][0] = lines[line - 1][1];
                        lines[line][slots + 1] = lines[line - 1][slots];
                    }
                    var even = 0;
                    var odd = 1;
                    while (even < slots || odd < slots) {
                        if (even < slots) {
                            even(first, even, set);
                            even(second, even, set);
                            even += 2;
                        }
                        if (even > 8 || even >= slots) {
                            if (odd < slots) {
                                odd(first, odd, set + 41);
                                odd(second, odd, set + 41);
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

        private int value(int line, int slot, int prediction) {
            var y = group * 6 + rowOf[line][slot];
            var x = x0 + columnOf[line][slot];
            return y < height && x < width ? samples[y * width + x] & 0xFFFF : Math.clamp(prediction, 0, maximum);
        }

        private void keep(int line, int slot, int value) {
            var y = group * 6 + rowOf[line][slot];
            var x = x0 + columnOf[line][slot];
            if (y < height && x < width) {
                reconstructed[y * width + x] = (short) value;
            }
        }

        private void even(int line, int slot, int set) {
            var current = lines[line];
            var above = lines[line - 1];
            var i = slot + 1;
            int b = above[i], c = above[i - 1], d = above[i + 1], f = lines[line - 2][i];
            int dc = Math.abs(b - c), dd = Math.abs(b - d), df = Math.abs(b - f);
            var prediction = dc > dd && dc > df ? (f + d + 2 * b) >> 2 : dd > dc && dd > df ? (f + c + 2 * b) >> 2
                    : (c + 2 * b + d) >> 2;
            if (rowOf[line][slot] < 0) {
                current[i] = prediction;
                return;
            }
            current[i] = sample(set, b - f, c - b, prediction, value(line, slot, prediction));
            keep(line, slot, current[i]);
        }

        private void odd(int line, int slot, int set) {
            var current = lines[line];
            var above = lines[line - 1];
            var i = slot + 1;
            int a = current[i - 1], g = current[i + 1], b = above[i], c = above[i - 1], d = above[i + 1];
            var prediction = (b > c && b > d) || (b < c && b < d) ? (a + g + 2 * b) >> 2 : (a + g) >> 1;
            current[i] = sample(set, b - c, c - a, prediction, value(line, slot, prediction));
            keep(line, slot, current[i]);
        }

        private int range(int near) {
            return (maximum + 2 * near) / (2 * near + 1) + 1;
        }

        // codes a sample within its activity class's error bound (JPEG-LS near-lossless coding) and returns the
        // value a decoder reconstructs
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
            var context = 9 * q(first, near) + q(second, near);
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
            var signed = error * sign;
            if (near == 0) {
                return (prediction + signed) & maximum;
            }
            var reconstruction = prediction + signed * step;
            if (reconstruction < -near) {
                reconstruction += range * step;
            } else if (reconstruction > maximum + near) {
                reconstruction -= range * step;
            }
            return Math.clamp(reconstruction, 0, maximum);
        }

        private void residual(int[] sums, int[] counts, int index, int near, int error) {
            var k = 0;
            while ((counts[index] << k) < sums[index]) {
                k++;
            }
            var quantized = 32 - Integer.numberOfLeadingZeros(range(near) - 1);
            var escape = 4 * bits - quantized - 1;
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

        private void put(int value, int count) {
            for (var bit = count - 1; bit >= 0; bit--) {
                accumulator = (accumulator << 1) | (bit < 32 ? (value >>> bit) & 1 : 0);
                if (++pending == 8) {
                    out.write((int) accumulator & 0xFF);
                    pending = 0;
                    accumulator = 0;
                }
            }
        }

        private static int q(int gradient, int near) {
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
