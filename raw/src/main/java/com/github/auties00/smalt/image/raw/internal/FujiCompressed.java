package com.github.auties00.smalt.image.raw.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.internal.task.ParallelBands;

import java.util.Arrays;

/**
 * Decodes the compressed sensor data of Fujifilm RAF files, lossless and lossy, Bayer and X-Trans, of 12 to 16 bits.
 *
 * <p>The data starts with a 16-byte header of big-endian fields:
 * {@snippet :
 * 'I' 'S' | version (1 byte, 1 lossless, 0 lossy) | type (1 byte, 0 Bayer, 16 X-Trans) | bits (1 byte)
 * | height (2) | rounded width (2) | width (2) | block width (2) | blocks (1 byte) | groups (2)
 * }
 * followed by the 32-bit length of each block, padded to a multiple of 16 bytes, then, in a lossy stream, one
 * quantization level per group of each block (each block's levels padded to a multiple of 16 bytes), and the
 * blocks. A block codes a vertical strip of the image, {@code block width} columns wide (the last one padded to the
 * rounded width), as {@code groups} groups of six rows; blocks are coded independently.
 *
 * <p>A block keeps eighteen lines of samples: five red lines R0 to R4, eight green lines G0 to G7 and five blue
 * lines B0 to B4. Green line {@code Gk} holds the green photosites of row {@code k - 2} of the group; red and blue
 * lines {@code Rk} and {@code Bk} hold the red and blue photosites of rows {@code 2(k - 2)} and
 * {@code 2(k - 2) + 1}. A Bayer line has one slot per two columns, slot {@code x / 2}; an X-Trans line has four
 * slots per six columns, slot {@code 4(x / 6) + (2(x % 6) + 1) / 3}. A slot of an X-Trans line that holds no
 * photosite (always an even slot) takes the value its even prediction gives, and codes nothing. Each group first
 * copies R3, R4, G6, G7, B3 and B4 into R0, R1, G0, G1, B0 and B1, then codes six passes, one per row: (R2, G2),
 * (G3, B2), (R3, G4), (G5, B3), (R4, G6) and (G7, B4). Before a line is coded, its two border slots take the first
 * and last values of the line above it ({@code Rk-1}, {@code Gk-1} or {@code Bk-1}); lines start at zero.
 *
 * <p>A pass codes the even slots of its two lines in pairs, first line first, and, once the even position passes
 * 8, follows each even pair with the pair of odd slots: the odd samples lag the even ones by ten positions. With
 * {@code b}, {@code c} and {@code d} the slots above, above-left and above-right in the line above, {@code f} the
 * slot above in the line two above, {@code a} and {@code g} the slots left and right in the line itself:
 * <ul>
 * <li>an even sample is predicted as {@code (f + d + 2b) / 4} when {@code |b - c|} exceeds both {@code |b - d|}
 * and {@code |b - f|}, as {@code (f + c + 2b) / 4} when {@code |b - d|} exceeds both others, and as
 * {@code (c + 2b + d) / 4} otherwise, in the context of the gradients {@code b - f} and {@code c - b};</li>
 * <li>an odd sample is predicted as {@code (a + g + 2b) / 4} when {@code b} is strictly above or strictly below
 * both {@code c} and {@code d}, and as {@code (a + g) / 2} otherwise, in the context of the gradients
 * {@code b - c} and {@code c - a}.</li>
 * </ul>
 * The residual is coded in the manner of the regular mode of JPEG-LS (ITU-T T.87), near-lossless in a lossy
 * stream. The activity of a sample, the sum of the magnitudes of its two gradients, selects one of four classes:
 * class 0 up to 5, then classes 1, 2 and 3 for 6, 7 and from 8 on. With the group's level {@code L} (0 throughout a
 * lossless stream), the classes allow the errors 0, {@code min(1, L)}, {@code min(2, L)} and {@code L}. With the
 * class's error bound {@code n}, each gradient is quantized into nine regions with the JPEG-LS default thresholds
 * {@code 18 + 3n}, {@code 67 + 5n} and {@code 276 + 7n} and the zero region {@code [-n, n]}, a context of 41 is the
 * folded {@code 9 q1 + q2} (a negative context negates the residual), and the residual, in steps of
 * {@code 2n + 1}, is a limited-length Golomb-Rice code of 4 times the bit depth whose parameter follows the
 * context's error sum and count. Each pass index modulo 3 selects a set of contexts, kept apart for even and odd
 * samples, and each class keeps its own statistics; classes of the same error bound share those of the highest of
 * them, and a class's statistics start afresh when its error bound changes from one group to the next. A
 * reconstructed value wraps around the quantized range and is clamped to the bit depth.
 *
 * @implNote This implementation was derived without reference code: the header layout, the line structure and
 * every rule above were worked out from CC0 sample files against the sensor values Adobe DNG Converter writes for
 * them, lossless and lossy, which this decoder reproduces exactly; the context modeling is that of ITU-T T.87, with
 * its default thresholds.
 */
final class FujiCompressed {
    /**
     * The magic of the header, {@code 'I' 'S'}.
     */
    private static final int MAGIC = 0x4953;

    /**
     * The coded line pairs of the six passes, as line indices (R0 = 0, G0 = 5, B0 = 13).
     */
    private static final int[][] PASSES = {{2, 7}, {8, 15}, {3, 9}, {10, 16}, {4, 11}, {12, 17}};

    /**
     * The number of lines of a block.
     */
    private static final int LINES = 18;

    /**
     * The even position after which a pass starts coding odd samples.
     */
    private static final int LAG = 8;

    /**
     * The count at which a context's statistics are halved (JPEG-LS {@code RESET}).
     */
    private static final int RESET = 64;

    /**
     * The number of contexts of a set.
     */
    private static final int CONTEXTS = 41;

    /**
     * The number of activity classes, each with its own error bound and context statistics.
     */
    private static final int CLASSES = 4;

    /**
     * The largest activity (the sum of the magnitudes of a sample's two context gradients) of class 0.
     */
    private static final int FLAT = 5;

    /**
     * The bound on the bytes of one block, above any camera's.
     */
    private static final int MAX_BLOCK_BYTES = 1 << 28;

    /**
     * Prevents instantiation.
     */
    private FujiCompressed() {
        throw new AssertionError();
    }

    /**
     * The header of a compressed RAF stream.
     *
     * @param lossless whether the data is coded without loss
     * @param xtrans whether the sensor has an X-Trans color filter array
     * @param bits the bits of a sample, from 12 to 16
     * @param height the height in pixels
     * @param roundedWidth the width of all blocks together
     * @param width the width in pixels
     * @param blockWidth the width of a block
     * @param groups the number of six-row groups
     * @param offsets the absolute offset of each block
     * @param lengths the byte length of each block
     * @param levels the quantization level of each group of each block, by block; empty for a lossless stream
     */
    record Header(boolean lossless, boolean xtrans, int bits, int height, int roundedWidth, int width, int blockWidth,
                  int groups, long[] offsets, int[] lengths, byte[][] levels) {
    }

    /**
     * Reads the header of a compressed stream.
     *
     * @param reader any reader over the file
     * @param offset the absolute offset of the stream
     * @param length the length of the stream
     * @return the header
     * @throws MediaException.Malformed if the header is not a compressed RAF header or is inconsistent
     */
    static Header header(TiffReader reader, long offset, long length) {
        var head = new byte[16];
        if (length < head.length || reader.readAvailable(offset, head, 0, head.length) < head.length) {
            throw new MediaException.Malformed("truncated compressed RAF header");
        }
        if (u16(head, 0) != MAGIC) {
            throw new MediaException.Malformed("not a compressed RAF stream");
        }
        var version = head[2] & 0xFF;
        var type = head[3] & 0xFF;
        var bits = head[4] & 0xFF;
        var height = u16(head, 5);
        var roundedWidth = u16(head, 7);
        var width = u16(head, 9);
        var blockWidth = u16(head, 11);
        var blocks = head[13] & 0xFF;
        var groups = u16(head, 14);
        if (version > 1 || (type != 0 && type != 16) || bits < 12 || bits > 16 || height == 0 || width == 0
                || blockWidth == 0 || blocks == 0 || blockWidth % 6 != 0 || (long) blocks * blockWidth != roundedWidth
                || width > roundedWidth || (long) groups * 6 < height || groups > (height + 5) / 6 + 1) {
            throw new MediaException.Malformed("inconsistent compressed RAF header");
        }
        var table = new byte[4 * blocks];
        if (reader.readAvailable(offset + 16, table, 0, table.length) < table.length) {
            throw new MediaException.Malformed("truncated compressed RAF block table");
        }
        var at = offset + ((16 + table.length + 15) & ~15);
        var levels = new byte[version == 0 ? blocks : 0][];
        for (var block = 0; block < levels.length; block++) {
            levels[block] = new byte[groups];
            if (reader.readAvailable(at, levels[block], 0, groups) < groups) {
                throw new MediaException.Malformed("truncated compressed RAF quantization table");
            }
            at += (groups + 15) & ~15;
        }
        var offsets = new long[blocks];
        var lengths = new int[blocks];
        for (var block = 0; block < blocks; block++) {
            var size = ((long) u16(table, 4 * block) << 16) | u16(table, 4 * block + 2);
            if (size > MAX_BLOCK_BYTES || at + size > offset + length) {
                throw new MediaException.Malformed("compressed RAF block " + block + " lies outside the stream");
            }
            offsets[block] = at;
            lengths[block] = (int) size;
            at += size;
        }
        return new Header(version == 1, type == 16, bits, height, roundedWidth, width, blockWidth, groups, offsets,
                lengths, levels);
    }

    /**
     * Decodes the blocks of a stream into a plane.
     *
     * @param reader any reader over the file
     * @param header the stream header
     * @param cfa the color of each photosite of the filter array in stored coordinates, codes 0 to 2 for red, green
     * and blue, row by row
     * @param period the side of the filter array, 2 (Bayer) or 6 (X-Trans)
     * @param plane the plane to fill, at least the stream's width and height
     * @param limits the resource bounds
     * @throws MediaException.Malformed if the data is damaged
     * @throws MediaException.Unsupported if the filter array does not fit the coded line structure
     * @throws MediaException.LimitExceeded if a block exceeds the limits
     */
    static void decode(TiffReader reader, Header header, int[] cfa, int period, SensorPlane plane, MediaLimits limits) {
        var slots = header.xtrans() ? header.blockWidth() / 6 * 4 : header.blockWidth() / 2;
        var maps = new int[header.offsets().length][];
        for (var block = 0; block < maps.length; block++) {
            maps[block] = map(header, cfa, period, block * header.blockWidth(), slots);
        }
        for (var length : header.lengths()) {
            LimitChecks.checkAllocation(limits, length);
        }
        ParallelBands.run(maps.length, block -> {
            var data = new byte[header.lengths()[block]];
            var read = reader.readAvailable(header.offsets()[block], data, 0, data.length);
            if (read < data.length) {
                throw new MediaException.Malformed("truncated compressed RAF block " + block);
            }
            new Block(header, slots, maps[block], data, plane, block * header.blockWidth(),
                    header.lossless() ? null : header.levels()[block]).decode();
        });
    }

    /**
     * Maps every slot of every coded line of a block to the photosite it holds.
     *
     * @param header the stream header
     * @param cfa the filter array colors, row by row
     * @param period the side of the filter array
     * @param x0 the first column of the block
     * @param slots the slots of a line
     * @return for line {@code l} and slot {@code s}, at {@code l * slots + s}, the row within the group times the
     * block width plus the column within the block, or -1 for a slot that holds no photosite
     * @throws MediaException.Unsupported if two photosites share a slot or an odd slot is empty
     */
    private static int[] map(Header header, int[] cfa, int period, int x0, int slots) {
        var blockWidth = header.blockWidth();
        var map = new int[LINES * slots];
        Arrays.fill(map, -1);
        for (var row = 0; row < 6; row++) {
            for (var column = 0; column < blockWidth; column++) {
                var color = cfa[(row % period) * period + (x0 + column) % period];
                var line = switch (color) {
                    case 0 -> 2 + row / 2;
                    case 1 -> 7 + row;
                    case 2 -> 15 + row / 2;
                    default -> throw new MediaException.Unsupported("raf", "compressed RAF filter color " + color);
                };
                var slot = header.xtrans() ? column / 6 * 4 + (2 * (column % 6) + 1) / 3 : column / 2;
                var index = line * slots + slot;
                if (map[index] >= 0) {
                    throw new MediaException.Unsupported("raf", "compressed RAF filter array layout");
                }
                map[index] = row * blockWidth + column;
            }
        }
        for (var pass : PASSES) {
            for (var line : pass) {
                for (var slot = 1; slot < slots; slot += 2) {
                    if (map[line * slots + slot] < 0) {
                        throw new MediaException.Unsupported("raf", "compressed RAF filter array layout");
                    }
                }
            }
        }
        return map;
    }

    /**
     * Reads a big-endian 16-bit integer.
     *
     * @param data the bytes
     * @param offset the index of the first byte
     * @return the value
     */
    private static int u16(byte[] data, int offset) {
        return (data[offset] & 0xFF) << 8 | data[offset + 1] & 0xFF;
    }


    /**
     * The decoding state of one block. Instances are not thread-safe.
     */
    private static final class Block {
        private final int bits;
        private final int maximum;
        private final int slots;
        private final int stride;
        private final int[] map;
        private final int[][] lines;
        private final byte[] levels;
        private final int[][] sums = new int[CLASSES][6 * CONTEXTS];
        private final int[][] counts = new int[CLASSES][6 * CONTEXTS];
        private final int[] familyNear = {-1, -1, -1, -1};
        private final int[] classNear = new int[CLASSES];
        private final int[] classFamily = new int[CLASSES];
        private final int[] classRange = new int[CLASSES];
        private final int[] classBits = new int[CLASSES];
        private final byte[] data;
        private final SensorPlane plane;
        private final int x0;
        private final int blockWidth;
        private final int width;
        private final int height;
        private final int groups;
        private int position;
        private long cache;
        private int cached;

        /**
         * Constructs the state of a block.
         *
         * @param header the stream header
         * @param slots the slots of a line
         * @param map the slot map of {@link #map}
         * @param data the coded block
         * @param plane the plane to fill
         * @param x0 the first column of the block
         * @param levels the quantization level of each group, or {@code null} for a lossless stream
         */
        Block(Header header, int slots, int[] map, byte[] data, SensorPlane plane, int x0, byte[] levels) {
            this.bits = header.bits();
            this.maximum = (1 << bits) - 1;
            this.slots = slots;
            this.stride = slots + 2;
            this.map = map;
            this.lines = new int[LINES][stride];
            this.levels = levels;
            this.data = data;
            this.plane = plane;
            this.x0 = x0;
            this.blockWidth = header.blockWidth();
            this.width = header.width();
            this.height = header.height();
            this.groups = header.groups();
        }

        /**
         * Decodes the block into the plane.
         */
        void decode() {
            for (var group = 0; group < groups; group++) {
                quantizers(levels == null ? 0 : levels[group] & 0xFF);
                if (group > 0) {
                    System.arraycopy(lines[3], 0, lines[0], 0, stride);
                    System.arraycopy(lines[4], 0, lines[1], 0, stride);
                    System.arraycopy(lines[11], 0, lines[5], 0, stride);
                    System.arraycopy(lines[12], 0, lines[6], 0, stride);
                    System.arraycopy(lines[16], 0, lines[13], 0, stride);
                    System.arraycopy(lines[17], 0, lines[14], 0, stride);
                }
                for (var pass = 0; pass < PASSES.length; pass++) {
                    pass(PASSES[pass][0], PASSES[pass][1], pass % 3 * 2 * CONTEXTS);
                }
                if (position > data.length + 8) {
                    throw new MediaException.Malformed("compressed RAF block overruns its data");
                }
                store(group);
            }
        }

        /**
         * Sets the error bound of each activity class for a group: 0 for class 0, {@code min(1, level)} for class
         * 1, {@code min(2, level)} for class 2 and the level for class 3; classes of equal bound share the
         * context statistics of the highest of them.
         *
         * @param level the group's quantization level, 0 for lossless coding
         */
        private void quantizers(int level) {
            classNear[0] = 0;
            classNear[1] = Math.min(1, level);
            classNear[2] = Math.min(2, level);
            classNear[3] = level;
            for (var c = 0; c < CLASSES; c++) {
                var top = c;
                while (top + 1 < CLASSES && classNear[top + 1] == classNear[c]) {
                    top++;
                }
                classFamily[c] = top;
                // the number of quantized values and their bits (JPEG-LS RANGE and qbpp)
                classRange[c] = (maximum + 2 * classNear[c]) / (2 * classNear[c] + 1) + 1;
                classBits[c] = 32 - Integer.numberOfLeadingZeros(classRange[c] - 1);
            }
        }

        /**
         * Codes one pass of two lines.
         *
         * @param first the first line
         * @param second the second line
         * @param set the offset of the pass's even context set; the odd set follows it
         */
        private void pass(int first, int second, int set) {
            border(first);
            border(second);
            var even = 0;
            var odd = 1;
            while (even < slots || odd < slots) {
                if (even < slots) {
                    even(first, even, set);
                    even(second, even, set);
                    even += 2;
                }
                if (even > LAG || even >= slots) {
                    if (odd < slots) {
                        odd(first, odd, set + CONTEXTS);
                        odd(second, odd, set + CONTEXTS);
                    }
                    odd += 2;
                }
            }
        }

        /**
         * Sets the border slots of a line to the first and last values of the line above it.
         *
         * @param line the index of the line, from 2 to 17
         */
        private void border(int line) {
            var current = lines[line];
            var above = lines[line - 1];
            current[0] = above[1];
            current[slots + 1] = above[slots];
        }

        /**
         * Decodes, or interpolates, an even sample.
         *
         * @param line the index of the line, from 2 to 17
         * @param slot the even slot
         * @param set the offset of the context set
         */
        private void even(int line, int slot, int set) {
            var current = lines[line];
            var above = lines[line - 1];
            var index = slot + 1;
            var b = above[index];
            var c = above[index - 1];
            var d = above[index + 1];
            var f = lines[line - 2][index];
            var dc = Math.abs(b - c);
            var dd = Math.abs(b - d);
            var df = Math.abs(b - f);
            int prediction;
            if (dc > dd && dc > df) {
                prediction = (f + d + 2 * b) >> 2;
            } else if (dd > dc && dd > df) {
                prediction = (f + c + 2 * b) >> 2;
            } else {
                prediction = (c + 2 * b + d) >> 2;
            }
            if (map[line * slots + slot] < 0) {
                current[index] = prediction;
                return;
            }
            current[index] = sample(set, b - f, c - b, prediction);
        }

        /**
         * Decodes an odd sample.
         *
         * @param line the index of the line, from 2 to 17
         * @param slot the odd slot
         * @param set the offset of the context set
         */
        private void odd(int line, int slot, int set) {
            var current = lines[line];
            var above = lines[line - 1];
            var index = slot + 1;
            var a = current[index - 1];
            var g = current[index + 1];
            var b = above[index];
            var c = above[index - 1];
            var d = above[index + 1];
            var prediction = (b > c && b > d) || (b < c && b < d) ? (a + g + 2 * b) >> 2 : (a + g) >> 1;
            current[index] = sample(set, b - c, c - a, prediction);
        }

        /**
         * Decodes a sample: selects the activity class of its two context gradients, decodes the residual in its
         * context and reconstructs the value within the class's error bound.
         *
         * @param set the offset of the context set
         * @param first the first context gradient
         * @param second the second context gradient
         * @param prediction the value predicted for the sample
         * @return the sample
         */
        private int sample(int set, int first, int second, int prediction) {
            var activity = Math.abs(first) + Math.abs(second);
            var activityClass = activity <= FLAT ? 0 : Math.min(activity - FLAT, CLASSES - 1);
            var near = classNear[activityClass];
            var family = classFamily[activityClass];
            var range = classRange[activityClass];
            if (familyNear[family] != near) {
                // a family's statistics start afresh whenever its error bound changes
                Arrays.fill(sums[family], Math.max(2, (range + 32) >> 6));
                Arrays.fill(counts[family], 1);
                familyNear[family] = near;
            }
            var context = 9 * quantize(first, near) + quantize(second, near);
            var error = residual(sums[family], counts[family], set + Math.abs(context), classBits[activityClass]);
            if (context < 0) {
                error = -error;
            }
            if (near == 0) {
                return (prediction + error) & maximum;
            }
            var step = 2 * near + 1;
            var value = prediction + error * step;
            if (value < -near) {
                value += range * step;
            } else if (value > maximum + near) {
                value -= range * step;
            }
            return Math.clamp(value, 0, maximum);
        }

        /**
         * Decodes a residual and updates its context.
         *
         * @param sums the error sums of the family
         * @param counts the counts of the family
         * @param index the index of the context
         * @param quantized the bits of a quantized value (JPEG-LS {@code qbpp})
         * @return the residual, before the context's sign is applied
         */
        private int residual(int[] sums, int[] counts, int index, int quantized) {
            var sum = sums[index];
            var count = counts[index];
            var k = 0;
            while ((count << k) < sum) {
                k++;
            }
            var zeros = zeros();
            // the escape threshold of a code limited to 4 * bits bits (JPEG-LS LIMIT - qbpp - 1)
            int code;
            if (zeros >= 4 * bits - quantized - 1) {
                code = read(quantized) + 1;
            } else {
                code = (zeros << k) | read(k);
            }
            var error = (code & 1) != 0 ? -((code + 1) >> 1) : code >> 1;
            sums[index] = sum + Math.abs(error);
            if (count == RESET) {
                sums[index] >>= 1;
                counts[index] = count >> 1;
            }
            counts[index]++;
            return error;
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
            if (gradient < 67 + 5 * near) {
                return 2;
            }
            return gradient < 276 + 7 * near ? 3 : 4;
        }

        /**
         * Keeps at least 57 bits in the cache, reading zeros past the end of the data.
         */
        private void fill() {
            while (cached <= 56) {
                var value = position < data.length ? data[position] & 0xFF : 0;
                position++;
                cache |= (long) value << (56 - cached);
                cached += 8;
            }
        }

        /**
         * Reads the zero bits before the next one bit, and that bit.
         *
         * @return the number of zero bits, at most a little above the length of the longest code
         */
        private int zeros() {
            var zeros = 0;
            while (true) {
                fill();
                if (cache != 0) {
                    var leading = Long.numberOfLeadingZeros(cache);
                    cache <<= leading + 1;
                    cached -= leading + 1;
                    return zeros + leading;
                }
                zeros += cached;
                cache = 0;
                cached = 0;
                if (zeros > 4 * bits) {
                    return zeros;
                }
            }
        }

        /**
         * Reads bits, most significant first.
         *
         * @param count the number of bits, from 0 to 31
         * @return the value
         */
        private int read(int count) {
            if (count == 0) {
                return 0;
            }
            fill();
            var value = (int) (cache >>> (64 - count));
            cache <<= count;
            cached -= count;
            return value;
        }

        /**
         * Copies the photosites of a decoded group into the plane.
         *
         * @param group the index of the six-row group
         */
        private void store(int group) {
            var out = plane.integers;
            var planeWidth = plane.width;
            var top = group * 6;
            for (var line = 2; line < LINES; line++) {
                if (line == 5 || line == 6 || line == 13 || line == 14) {
                    continue;
                }
                var values = lines[line];
                for (var slot = 0; slot < slots; slot++) {
                    var place = map[line * slots + slot];
                    if (place < 0) {
                        continue;
                    }
                    var y = top + place / blockWidth;
                    var x = x0 + place % blockWidth;
                    if (y < height && x < width) {
                        out[y * planeWidth + x] = (short) values[slot + 1];
                    }
                }
            }
        }
    }
}
