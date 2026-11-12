package com.github.auties00.smalt.image.raw.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.internal.io.ByteArrays;
import com.github.auties00.smalt.internal.limits.LimitChecks;

import java.util.Arrays;

/**
 * Decodes the compressed sensor data of Samsung's SRW files, in the three codings of the later
 * cameras.
 *
 * <p><b>Compression 32772</b> (NX mini, NX3000, NX3300) codes each photosite in raster order,
 * most significant bit first, without alignment. A prefix code gives a category {@code c} from 0
 * to 12, and {@code c} more bits give the difference from the prediction as in lossless JPEG. The
 * prediction is the photosite two columns to the left, or, in the first two columns, the one two
 * rows up, or 0 in the first two rows.
 *
 * <p><b>Compression 32770</b> at fewer bits than the photosites (NX300, NX30, NX2000, EK-GN120) and
 * <b>compression 32773</b> (NX1, NX500) code blocks of sixteen samples, 32-bit little-endian words
 * read most significant bit first. A block holds two runs of eight samples: of one column parity on
 * lines {@code y} and {@code y + 1} for 32770 (even columns in even coded rows, whose first line
 * is the coded row itself, and odd columns in odd coded rows, whose first line is the row above),
 * and of the two column parities on one line for 32773 (the column parity of the line's green
 * samples first). A row of blocks spans the width rounded up to 16 photosites; a 32770 row starts
 * where the offset table of the raw directory (tags {@code 0xA010} and {@code 0xA011}, little-endian
 * 32-bit offsets from the strip) says, and a 32773 row at the next multiple of 16 bytes.
 *
 * <p>A block starts with its prediction. In 32770 one bit chooses between the left prediction (0)
 * and the upper one (1). In 32773 a one bit keeps the mode of the previous block (the left
 * prediction in a row's first block) and a zero bit is followed by three bits of mode, which in the
 * first two rows must be the left prediction's. Four 2-bit codes follow, one for the width of each
 * run of four samples: 0 keeps the width, 1 adds one, 2 subtracts one and 3 takes the next four
 * bits, read after the four codes. A width starts each row
 * at 7 in the first two rows and 4 in the others. The samples then follow as two's complement
 * differences of the width of their run.
 *
 * <p>The left prediction of the eight samples of a run is the last sample of that run in the
 * previous block; in a row's first block it is 128 in 32770 and the header's value in 32773. The
 * upper predictions read coded row {@code y - 1} for the first run and {@code y - 2} for the second,
 * at the sample's index {@code j} in the run moved by the mode: mode 0 reads {@code j - 2}, 1 reads
 * {@code j - 1}, 2 averages {@code j - 1} and {@code j} rounding up, 3 reads {@code j}, 4 averages
 * {@code j} and {@code j + 1} rounding up, 5 reads {@code j + 1}, 6 reads {@code j + 2} and 7 is the
 * left prediction; 32770's upper prediction is mode 3. The rows of a run's color follow each other
 * as lines of one plane: an index before a coded row's start reads the end of the coded row of that
 * color before it, and one past its end reads the start of the next, the row being decoded included.
 * Coded rows before the first hold zeros, except that the second run of coded row 1 reads the second
 * run of coded row 0 (a prediction only 32770 can make there) and that, in 32773, the last sample of
 * the second run of the two rows before the first is 1024. Samples are limited to the bit depth, in the
 * rows the predictions read too.
 *
 * <p>A 32773 strip starts with a header of 16 bytes: the coding's letter ({@code K} lossless,
 * {@code E} quantized), the bit depth plus {@code 0x5F}, {@code 0x33 0x56}, the height and the width
 * as little-endian 16-bit values, a byte, the byte telling the codings apart (5 lossless, 0
 * quantized), two more bytes and the left prediction of a row's first block as a little-endian
 * 16-bit value. The letter and the bit depth here do not matter. The quantized coding adds a field
 * to every fourth block, before its prediction: 2 bits, of which 1 subtracts two from a quantizer
 * {@code q} that starts each row at 0, 2 adds two and 3 takes it from the next 12 bits. It adds a
 * bit after the prediction, which is always zero, and a sample is then the prediction plus
 * {@code (2 * q + 1) * d + q}.
 *
 * @implNote This implementation was derived without reference to any other decoder, from CC0 sample
 * files and the photosites Adobe DNG Converter, run as a black box, stores for them and for copies of
 * them with single fields changed (values the samples never use: quantizers and their changes,
 * prediction modes at the ends of rows and in the first rows, the limits of the bit depth, the header's
 * prediction), which {@code tools/testdata/srw-oracle.py} reproduces.
 */
final class SrwDecoder {
    /**
     * The bytes of the header of a 32773 strip.
     */
    static final int HEADER = 16;

    /**
     * The offset in a 32773 strip's header of the byte telling the codings apart.
     */
    static final int CODING_OFFSET = 9;

    /**
     * The value at {@link #CODING_OFFSET} of the quantized coding.
     */
    static final int CODING_QUANTIZED = 0;

    /**
     * The value at {@link #CODING_OFFSET} of the lossless coding.
     */
    static final int CODING_LOSSLESS = 5;

    /**
     * The bytes rows of 32773 are aligned to, from the start of the strip.
     */
    private static final int ROW_ALIGNMENT = 16;

    /**
     * The samples of a block.
     */
    private static final int BLOCK_COLUMNS = 16;

    /**
     * The samples of a run in a block.
     */
    private static final int RUN_SAMPLES = 8;

    /**
     * The left prediction of a row's first block in compression 32770.
     */
    private static final int INTERLEAVED_ROW_START = 128;

    /**
     * The offset in a 32773 strip's header of the left prediction of a row's first block, a
     * little-endian 16-bit value.
     */
    private static final int ROW_START_OFFSET = 12;

    /**
     * The mode of the left prediction.
     */
    private static final int MODE_LEFT = 7;

    /**
     * The mode reading the upper sample of the same index.
     */
    private static final int MODE_UP = 3;

    /**
     * The widths the first two rows start from.
     */
    private static final int FIRST_ROWS_WIDTH = 7;

    /**
     * The widths the other rows start from.
     */
    private static final int ROW_WIDTH = 4;

    /**
     * The coded rows the prediction of a block can read, the current one included.
     */
    private static final int HISTORY = 5;

    /**
     * What the last sample of the second run of the two coded rows before the first holds in 32773,
     * the value Adobe DNG Converter reads there whatever the file's depth and header.
     */
    private static final int BEFORE_FIRST_ROW = 1024;

    /**
     * The longest code of compression 32772.
     */
    private static final int PREFIX_BITS = 10;

    /**
     * The codes of compression 32772 as {@code code, bits}, by category.
     */
    private static final int[][] PREFIX_CODES = {
            {0b110100, 6}, {0b11011, 5}, {0b1111, 4}, {0b1100, 4}, {0b000, 3}, {0b10, 2}, {0b01, 2}, {0b001, 3},
            {0b1110, 4}, {0b1101010, 7}, {0b11010110, 8}, {0b110101110, 9}, {0b1101011110, 10}
    };

    /**
     * For every {@value #PREFIX_BITS} bits, the category of the code they start with above its
     * length in the low four bits, or 0 for the code no category uses.
     */
    private static final short[] PREFIX_LOOKUP = prefixLookup();

    /**
     * Prevents instantiation.
     */
    private SrwDecoder() {
        throw new AssertionError();
    }

    /**
     * {@return the lookup table of the codes of compression 32772}
     */
    private static short[] prefixLookup() {
        var lookup = new short[1 << PREFIX_BITS];
        for (var category = 0; category < PREFIX_CODES.length; category++) {
            var code = PREFIX_CODES[category][0];
            var length = PREFIX_CODES[category][1];
            var free = PREFIX_BITS - length;
            for (var rest = 0; rest < 1 << free; rest++) {
                lookup[code << free | rest] = (short) (category << 4 | length);
            }
        }
        return lookup;
    }

    /**
     * Decodes compression 32772.
     *
     * @param data the strip, from its first byte
     * @param depth the bit depth
     * @param plane the plane to fill
     * @throws MediaException.Malformed if the data holds the code no category uses
     */
    static void decodePrefixed(byte[] data, int depth, SensorPlane plane) {
        var bits = new MsbBits(data, 0, data.length);
        var largest = (1 << depth) - 1;
        var width = plane.width;
        var out = plane.integers;
        for (var y = 0; y < plane.height; y++) {
            var row = y * width;
            for (var x = 0; x < width; x++) {
                var entry = PREFIX_LOOKUP[bits.peek(PREFIX_BITS)];
                if (entry == 0) {
                    throw new MediaException.Malformed("an SRW photosite uses an unassigned code");
                }
                bits.skip(entry & 0xF);
                var category = entry >> 4;
                var difference = 0;
                if (category != 0) {
                    difference = bits.read(category);
                    if (difference < 1 << category - 1) {
                        difference -= (1 << category) - 1;
                    }
                }
                int prediction;
                if (x >= 2) {
                    prediction = out[row + x - 2] & 0xFFFF;
                } else {
                    prediction = y >= 2 ? out[row - 2 * width + x] & 0xFFFF : 0;
                }
                out[row + x] = (short) Math.clamp(prediction + difference, 0, largest);
            }
            if (bits.exhausted()) {
                return;
            }
        }
    }

    /**
     * Decodes compression 32770 at fewer bits than the photosites.
     *
     * @param data the strip, from its first byte
     * @param offsets the offset from the strip of each coded row
     * @param bits the bit depth
     * @param plane the plane to fill
     * @param limits the resource bounds
     * @throws MediaException.Malformed if a width leaves its range
     */
    static void decodeInterleaved(byte[] data, int[] offsets, int bits, SensorPlane plane, MediaLimits limits) {
        var state = new Blocks(INTERLEAVED_ROW_START, plane.width, bits, limits);
        var words = new WordBits(data);
        for (var y = 0; y < plane.height; y++) {
            var start = y < offsets.length ? offsets[y] : data.length;
            var end = y + 1 < offsets.length ? offsets[y + 1] : data.length;
            if (start < 0 || start >= data.length) {
                return;
            }
            words.seek(start, end < start || end > data.length ? data.length : end);
            state.row(words, y, false, false);
            var column = y & 1;
            var first = y - column;
            state.store(plane, 0, first, column);
            state.store(plane, 1, first + 1, column);
        }
    }

    /**
     * Decodes compression 32773.
     *
     * @param data the strip, from its first byte
     * @param quantized whether the coding is the quantized one
     * @param bits the bit depth
     * @param plane the plane to fill
     * @param limits the resource bounds
     * @throws MediaException.Malformed if a width leaves its range, a block's zero bit is not zero or
     * a block of the first two rows predicts from a row above
     */
    static void decodeModal(byte[] data, boolean quantized, int bits, SensorPlane plane, MediaLimits limits) {
        var start = data.length < HEADER ? 0 : ByteArrays.u16le(data, ROW_START_OFFSET);
        var state = new Blocks(start, plane.width, bits, limits);
        state.seedBeforeFirstRow();
        var words = new WordBits(data);
        words.seek(Math.min(HEADER, data.length), data.length);
        for (var y = 0; y < plane.height && words.hasData(); y++) {
            state.row(words, y, true, quantized);
            var column = y & 1;
            state.store(plane, 0, y, column);
            state.store(plane, 1, y, column ^ 1);
            words.align(ROW_ALIGNMENT);
        }
    }

    /**
     * Holds the samples of the last coded rows and the widths of the runs of the block codings.
     */
    private static final class Blocks {
        /**
         * The bit depth, to which samples are limited.
         */
        private final int depth;

        /**
         * The left prediction of a row's first block.
         */
        private final int rowStart;

        /**
         * The samples of a run in a coded row.
         */
        private final int samples;

        /**
         * The blocks of a coded row.
         */
        private final int blocks;

        /**
         * The width of each run of four samples.
         */
        private final int[] widths = new int[4];

        /**
         * The codes of the four widths.
         */
        private final int[] codes = new int[4];

        /**
         * The last sample of each run in the previous block.
         */
        private final int[] left = new int[2];

        /**
         * The coded rows, most recent first, each holding its first and second run, indexed by
         * sample. The first is the current one.
         */
        private final int[][][] rows;

        /**
         * Allocates the rows.
         *
         * @param rowStart the left prediction of a row's first block
         * @param width the photosites of a line
         * @param bits the bit depth
         * @param limits the resource bounds
         */
        Blocks(int rowStart, int width, int bits, MediaLimits limits) {
            this.depth = bits;
            this.rowStart = rowStart;
            blocks = (int) ((width + (long) BLOCK_COLUMNS - 1) / BLOCK_COLUMNS);
            samples = blocks * RUN_SAMPLES;
            LimitChecks.checkAllocation(limits, HISTORY * 2L * samples * Integer.BYTES);
            rows = new int[HISTORY][2][samples];
        }

        /**
         * Sets the samples of the rows before the first that a 32773 prediction past a row's start reads.
         */
        void seedBeforeFirstRow() {
            rows[0][1][samples - 1] = BEFORE_FIRST_ROW;
            rows[1][1][samples - 1] = BEFORE_FIRST_ROW;
        }

        /**
         * Decodes a coded row.
         *
         * @param bits the reader, at the start of the row
         * @param y the coded row
         * @param modal whether blocks hold modes, as in 32773
         * @param quantized whether the blocks use the quantized coding
         */
        void row(WordBits bits, int y, boolean modal, boolean quantized) {
            var recycled = rows[HISTORY - 1];
            System.arraycopy(rows, 0, rows, 1, HISTORY - 1);
            rows[0] = recycled;
            Arrays.fill(recycled[0], 0);
            Arrays.fill(recycled[1], 0);
            var start = y < 2 ? FIRST_ROWS_WIDTH : ROW_WIDTH;
            for (var index = 0; index < 4; index++) {
                widths[index] = start;
            }
            left[0] = rowStart;
            left[1] = rowStart;
            var mode = MODE_LEFT;
            var quantizer = 0;
            var step = 1;
            for (var block = 0; block < blocks; block++) {
                if (quantized && block % 4 == 0) {
                    switch (bits.read(2)) {
                        case 1 -> quantizer -= 2;
                        case 2 -> quantizer += 2;
                        case 3 -> quantizer = bits.read(12);
                        default -> {
                        }
                    }
                    step = 2 * quantizer + 1;
                }
                if (modal) {
                    if (bits.read(1) == 0) {
                        mode = bits.read(3);
                        if (y < 2 && mode != MODE_LEFT) {
                            throw new MediaException.Malformed("an SRW block of the first two rows predicts from a row above");
                        }
                    }
                } else {
                    mode = bits.read(1) == 0 ? MODE_LEFT : MODE_UP;
                }
                if (quantized && bits.read(1) != 0) {
                    throw new MediaException.Malformed("an SRW block's reserved bit is set");
                }
                widths(bits);
                run(bits, block, 0, rows[1][0], mode, step, quantizer);
                run(bits, block, 1, y == 1 ? rows[1][1] : rows[2][1], mode, step, quantizer);
                left[0] = rows[0][0][block * RUN_SAMPLES + RUN_SAMPLES - 1];
                left[1] = rows[0][1][block * RUN_SAMPLES + RUN_SAMPLES - 1];
            }
        }

        /**
         * Reads the width codes of a block and applies them.
         *
         * @param bits the reader
         */
        private void widths(WordBits bits) {
            for (var index = 0; index < 4; index++) {
                codes[index] = bits.read(2);
            }
            for (var index = 0; index < 4; index++) {
                var width = switch (codes[index]) {
                    case 0 -> widths[index];
                    case 1 -> widths[index] + 1;
                    case 2 -> widths[index] - 1;
                    default -> bits.read(4);
                };
                if (width < 0 || width > 16) {
                    throw new MediaException.Malformed("SRW difference width out of range: " + width);
                }
                widths[index] = width;
            }
        }

        /**
         * Decodes a run of a block.
         *
         * @param bits the reader
         * @param block the block's index in the row
         * @param run the run: 0 or 1
         * @param reference the coded row the upper predictions read
         * @param mode the prediction mode
         * @param step the quantizer's step
         * @param offset the quantizer's offset
         */
        private void run(WordBits bits, int block, int run, int[] reference, int mode, int step, int offset) {
            var out = rows[0][run];
            var before = rows[2 * run + 2][run];
            var largest = (1 << depth) - 1;
            var base = block * RUN_SAMPLES;
            for (var part = 0; part < 2; part++) {
                var width = widths[2 * run + part];
                for (var sample = 0; sample < RUN_SAMPLES / 2; sample++) {
                    var j = base + part * (RUN_SAMPLES / 2) + sample;
                    var prediction = switch (mode) {
                        case 0 -> at(reference, before, out, j - 2);
                        case 1 -> at(reference, before, out, j - 1);
                        case 2 -> (at(reference, before, out, j - 1) + reference[j] + 1) >> 1;
                        case 3 -> reference[j];
                        case 4 -> (reference[j] + at(reference, before, out, j + 1) + 1) >> 1;
                        case 5 -> at(reference, before, out, j + 1);
                        case 6 -> at(reference, before, out, j + 2);
                        default -> left[run];
                    };
                    out[j] = Math.clamp(prediction + step * bits.readSigned(width) + offset, 0, largest);
                }
            }
        }

        /**
         * {@return a sample of a coded row, an index past its ends reading the same run of the rows
         * before and after it, as lines of one plane}
         *
         * @param reference the coded row
         * @param before the coded row of the same color before it
         * @param after the coded row of the same color after it
         * @param j the index, at most two samples outside the row
         */
        private int at(int[] reference, int[] before, int[] after, int j) {
            if (j < 0) {
                return before[j + samples];
            }
            return j < samples ? reference[j] : after[j - samples];
        }

        /**
         * Stores a run of the current coded row in the plane, dropping the samples past the plane's
         * edges.
         *
         * @param plane the plane to fill
         * @param run the run: 0 or 1
         * @param line the plane's line
         * @param column the first column
         */
        void store(SensorPlane plane, int run, int line, int column) {
            if (line >= plane.height) {
                return;
            }
            var out = plane.integers;
            var row = line * plane.width;
            var source = rows[0][run];
            for (int j = 0, x = column; x < plane.width; j++, x += 2) {
                out[row + x] = (short) source[j];
            }
        }
    }

    /**
     * Reads bits from 32-bit little-endian words, most significant bit first, supplying zero bits past the
     * end of the data.
     */
    private static final class WordBits {
        /**
         * The data.
         */
        private final byte[] data;

        /**
         * The index of the next word.
         */
        private int position;

        /**
         * The index after the readable data.
         */
        private int end;

        /**
         * The buffered bits, in the low {@link #count} bits.
         */
        private long buffer;

        /**
         * The number of buffered bits.
         */
        private int count;

        /**
         * Constructs a reader.
         *
         * @param data the bytes to read
         */
        WordBits(byte[] data) {
            this.data = data;
        }

        /**
         * Moves the reader.
         *
         * @param start the index of the next word
         * @param end the index after the readable data
         */
        void seek(int start, int end) {
            this.position = start;
            this.end = end;
            this.count = 0;
            this.buffer = 0;
        }

        /**
         * Moves the reader to the next multiple of a number of bytes from the start of the data.
         *
         * @param alignment the alignment in bytes
         */
        void align(int alignment) {
            var read = (long) position * 8 - count;
            var aligned = (read + alignment * 8L - 1) / (alignment * 8L) * alignment;
            seek((int) Math.min(aligned, Integer.MAX_VALUE), end);
        }

        /**
         * {@return whether bits of the data remain to be read}
         */
        boolean hasData() {
            return (long) position * 8 - count < (long) end * 8;
        }

        /**
         * Reads bits.
         *
         * @param bits the number of bits, from 0 to 16
         * @return the value
         */
        int read(int bits) {
            if (count < bits) {
                var word = 0L;
                for (var index = 0; index < 4; index++) {
                    var at = position + index;
                    if (at >= 0 && at < end) {
                        word |= (data[at] & 0xFFL) << 8 * index;
                    }
                }
                position = position <= Integer.MAX_VALUE - 4 ? position + 4 : position;
                buffer = buffer << 32 | word;
                count += 32;
            }
            count -= bits;
            return (int) (buffer >>> count) & (1 << bits) - 1;
        }

        /**
         * Reads a two's complement value.
         *
         * @param bits the number of bits, from 0 to 16
         * @return the value
         */
        int readSigned(int bits) {
            var value = read(bits);
            return bits == 0 ? 0 : value << 32 - bits >> 32 - bits;
        }
    }
}
