package com.github.auties00.smalt.image.tiff.internal;

import java.util.Arrays;

/**
 * Decodes CCITT bilevel fax data: Modified Huffman run lengths (TIFF compression 2, and the
 * word-aligned variant 32771), ITU-T T.4 Group 3 one- and two-dimensional coding (compression 3)
 * and ITU-T T.6 Group 4 coding (compression 4).
 *
 * <p>The output has one bit per pixel, most significant bit first, rows padded to a byte;
 * white runs are 0 bits and black runs 1 bits, whatever the photometric interpretation (as libtiff
 * decodes them). Group 3 rows may start with an end-of-line code, byte-aligned when the fill-bits
 * option is set, followed in two-dimensional mode by a tag bit choosing the row's coding; a row
 * without one is decoded as it comes. Decoding stops at the return-to-control sequence or the
 * end-of-facsimile-block code, at the end of the input, or at an invalid code, leaving the rows
 * not decoded white, as libtiff does with damaged fax data. Uncompressed mode (the T.4 and T.6
 * extension) is not supported and stops decoding the same way.
 *
 * <p>An instance reuses its buffers and is not thread-safe.
 *
 * @implNote This implementation decodes each code with one table lookup on the next 13 bits; the
 * code tables are those of ITU-T T.4 tables 1 to 3 (terminating, make-up and extended make-up
 * codes) and 4 (two-dimensional mode codes). The handling of end-of-line codes, fill bits and the
 * two-dimensional changing-element search follows libtiff's tif_fax3.c.
 */
public final class CcittFaxDecoder {
    /**
     * The number of bits every lookup reads.
     */
    private static final int LOOKUP_BITS = 13;

    /**
     * The run value marking an end-of-line code in the run tables.
     */
    private static final int EOL_RUN = 0x7FFF;

    /**
     * The white run table, indexed by the next 13 bits: {@code length << 16 | run}, or zero for
     * an invalid code.
     */
    private static final int[] WHITE = new int[1 << LOOKUP_BITS];

    /**
     * The black run table, laid out as {@link #WHITE}.
     */
    private static final int[] BLACK = new int[1 << LOOKUP_BITS];

    /**
     * The two-dimensional mode table, indexed by the next 7 bits: {@code length << 8 | mode}, or
     * zero when the bits start an end-of-line or extension code.
     */
    private static final int[] MODES = new int[1 << 7];

    /**
     * The pass mode.
     */
    private static final int PASS = 1;

    /**
     * The horizontal mode.
     */
    private static final int HORIZONTAL = 2;

    /**
     * The vertical modes are {@code VERTICAL + offset}, offsets -3 to 3.
     */
    private static final int VERTICAL = 10;

    /**
     * White terminating codes for runs 0 to 63 (ITU-T T.4 table 2).
     */
    static final String[] WHITE_TERMINATING = {
            "00110101", "000111", "0111", "1000", "1011", "1100", "1110", "1111",
            "10011", "10100", "00111", "01000", "001000", "000011", "110100", "110101",
            "101010", "101011", "0100111", "0001100", "0001000", "0010111", "0000011", "0000100",
            "0101000", "0101011", "0010011", "0100100", "0011000", "00000010", "00000011", "00011010",
            "00011011", "00010010", "00010011", "00010100", "00010101", "00010110", "00010111", "00101000",
            "00101001", "00101010", "00101011", "00101100", "00101101", "00000100", "00000101", "00001010",
            "00001011", "01010010", "01010011", "01010100", "01010101", "00100100", "00100101", "01011000",
            "01011001", "01011010", "01011011", "01001010", "01001011", "00110010", "00110011", "00110100"
    };

    /**
     * White make-up codes for runs 64 to 1728 in steps of 64 (ITU-T T.4 table 3).
     */
    static final String[] WHITE_MAKEUP = {
            "11011", "10010", "010111", "0110111", "00110110", "00110111", "01100100", "01100101",
            "01101000", "01100111", "011001100", "011001101", "011010010", "011010011", "011010100", "011010101",
            "011010110", "011010111", "011011000", "011011001", "011011010", "011011011", "010011000", "010011001",
            "010011010", "011000", "010011011"
    };

    /**
     * Black terminating codes for runs 0 to 63 (ITU-T T.4 table 2).
     */
    static final String[] BLACK_TERMINATING = {
            "0000110111", "010", "11", "10", "011", "0011", "0010", "00011",
            "000101", "000100", "0000100", "0000101", "0000111", "00000100", "00000111", "000011000",
            "0000010111", "0000011000", "0000001000", "00001100111", "00001101000", "00001101100", "00000110111", "00000101000",
            "00000010111", "00000011000", "000011001010", "000011001011", "000011001100", "000011001101", "000001101000", "000001101001",
            "000001101010", "000001101011", "000011010010", "000011010011", "000011010100", "000011010101", "000011010110", "000011010111",
            "000001101100", "000001101101", "000011011010", "000011011011", "000001010100", "000001010101", "000001010110", "000001010111",
            "000001100100", "000001100101", "000001010010", "000001010011", "000000100100", "000000110111", "000000111000", "000000100111",
            "000000101000", "000001011000", "000001011001", "000000101011", "000000101100", "000001011010", "000001100110", "000001100111"
    };

    /**
     * Black make-up codes for runs 64 to 1728 in steps of 64 (ITU-T T.4 table 3).
     */
    static final String[] BLACK_MAKEUP = {
            "0000001111", "000011001000", "000011001001", "000001011011", "000000110011", "000000110100", "000000110101",
            "0000001101100", "0000001101101", "0000001001010", "0000001001011", "0000001001100", "0000001001101",
            "0000001110010", "0000001110011", "0000001110100", "0000001110101", "0000001110110", "0000001110111",
            "0000001010010", "0000001010011", "0000001010100", "0000001010101", "0000001011010", "0000001011011",
            "0000001100100", "0000001100101"
    };

    /**
     * Extended make-up codes shared by both colors, for runs 1792 to 2560 in steps of 64 (ITU-T
     * T.4 table 3).
     */
    static final String[] EXTENDED_MAKEUP = {
            "00000001000", "00000001100", "00000001101", "000000010010", "000000010011", "000000010100", "000000010101",
            "000000010110", "000000010111", "000000011100", "000000011101", "000000011110", "000000011111"
    };

    static {
        for (var color = 0; color < 2; color++) {
            var table = color == 0 ? WHITE : BLACK;
            var terminating = color == 0 ? WHITE_TERMINATING : BLACK_TERMINATING;
            var makeup = color == 0 ? WHITE_MAKEUP : BLACK_MAKEUP;
            for (var run = 0; run < terminating.length; run++) {
                register(table, terminating[run], run);
            }
            for (var index = 0; index < makeup.length; index++) {
                register(table, makeup[index], (index + 1) * 64);
            }
            for (var index = 0; index < EXTENDED_MAKEUP.length; index++) {
                register(table, EXTENDED_MAKEUP[index], 1792 + index * 64);
            }
            register(table, "000000000001", EOL_RUN);
        }
        registerMode("0001", PASS);
        registerMode("001", HORIZONTAL);
        registerMode("1", VERTICAL);
        registerMode("011", VERTICAL + 1);
        registerMode("000011", VERTICAL + 2);
        registerMode("0000011", VERTICAL + 3);
        registerMode("010", VERTICAL - 1);
        registerMode("000010", VERTICAL - 2);
        registerMode("0000010", VERTICAL - 3);
    }

    /**
     * The coded bytes being decoded.
     */
    private byte[] data;

    /**
     * The index of the first coded byte.
     */
    private int start;

    /**
     * The number of coded bits.
     */
    private long totalBits;

    /**
     * The position of the next bit, from the first coded byte.
     */
    private long position;

    /**
     * The changing elements of the reference row, terminated by two width sentinels.
     */
    private int[] reference = new int[16];

    /**
     * The number of changing elements in {@link #reference}, sentinels excluded.
     */
    private int referenceCount;

    /**
     * The changing elements of the row being decoded.
     */
    private int[] current = new int[16];

    /**
     * The number of changing elements in {@link #current}.
     */
    private int currentCount;

    /**
     * Constructs a decoder.
     */
    public CcittFaxDecoder() {
    }

    /**
     * Adds one run code to a run table.
     *
     * @param table the lookup table, indexed by the next code bits, updated
     * @param code the code bits
     * @param run the run length, or {@link #EOL_RUN}
     * @throws IllegalStateException if the code collides with one already registered
     */
    private static void register(int[] table, String code, int run) {
        var length = code.length();
        var value = Integer.parseInt(code, 2) << (LOOKUP_BITS - length);
        var count = 1 << (LOOKUP_BITS - length);
        for (var i = 0; i < count; i++) {
            if (table[value + i] != 0) {
                throw new IllegalStateException("CCITT code " + code + " collides");
            }
            table[value + i] = length << 16 | run;
        }
    }

    /**
     * Adds one mode code to the mode table.
     *
     * @param code the code bits
     * @param mode the mode constant the code selects
     * @throws IllegalStateException if the code collides with one already registered
     */
    private static void registerMode(String code, int mode) {
        var length = code.length();
        var value = Integer.parseInt(code, 2) << (7 - length);
        var count = 1 << (7 - length);
        for (var i = 0; i < count; i++) {
            if (MODES[value + i] != 0) {
                throw new IllegalStateException("CCITT mode " + code + " collides");
            }
            MODES[value + i] = length << 8 | mode;
        }
    }

    /**
     * Decodes one chunk of fax data.
     *
     * @param input       the coded bytes, most significant bit first
     * @param inputOffset the index of the first coded byte
     * @param inputLength the number of coded bytes
     * @param output      the destination rows, which must be zero on entry
     * @param outOffset   the index of the first row
     * @param rowBytes    the length of one row, at least {@code (width + 7) / 8}
     * @param width       the number of pixels per row
     * @param rows        the number of rows wanted
     * @param compression 2 (Modified Huffman), 3 (T.4), 4 (T.6) or 32771 (word-aligned Modified
     *                    Huffman)
     * @param options     the T4Options (compression 3) or T6Options (compression 4) value
     * @return the number of rows decoded
     */
    public int decode(byte[] input, int inputOffset, int inputLength, byte[] output, int outOffset, int rowBytes,
                      int width, int rows, int compression, long options) {
        this.data = input;
        this.start = inputOffset;
        this.totalBits = (long) inputLength * 8;
        this.position = 0;
        ensure(width + 4);
        referenceCount = 0;
        resetReference(width);
        var twoDimensional = compression == 4 || (compression == 3 && (options & 1) != 0);
        for (var row = 0; row < rows; row++) {
            var rowOffset = outOffset + row * rowBytes;
            var useTwoDimensional = compression == 4;
            if (compression == 3) {
                var eol = skipEndOfLine();
                if (eol < 0) {
                    return row;
                }
                if (twoDimensional) {
                    if (position >= totalBits) {
                        return row;
                    }
                    useTwoDimensional = readBit() == 0;
                }
            }
            var completed = useTwoDimensional ? decodeTwoDimensional(output, rowOffset, width)
                    : decodeOneDimensional(output, rowOffset, width);
            if (!completed) {
                return row;
            }
            swapRows(width);
            if (compression == 2) {
                position = (position + 7) & ~7L;
            } else if (compression == 32771) {
                position = (position + 15) & ~15L;
            }
        }
        return rows;
    }

    /**
     * Skips an end-of-line code (and the fill bits before it) when one starts at the current
     * position.
     *
     * @return {@code 1} when an end-of-line code was skipped, {@code 0} when none is there, and
     *         {@code -1} at the end of the data (the end of the input, or two consecutive
     *         end-of-line codes of the return-to-control sequence)
     */
    private int skipEndOfLine() {
        if (position >= totalBits) {
            return -1;
        }
        if (peek(11) != 0) {
            return 0;
        }
        var zeros = 0;
        while (position < totalBits && peek(1) == 0) {
            position++;
            zeros++;
        }
        if (position >= totalBits) {
            return -1;
        }
        position++;
        if (zeros >= 11 && peek(11) == 0 && peek(12) == 1) {
            return -1;
        }
        return 1;
    }

    /**
     * Decodes one row coded one-dimensionally.
     *
     * @param output    the destination rows
     * @param rowOffset the index of the row
     * @param width     the number of pixels per row
     * @return whether the row decoded completely
     */
    private boolean decodeOneDimensional(byte[] output, int rowOffset, int width) {
        currentCount = 0;
        var a0 = 0;
        var color = 0;
        while (a0 < width) {
            var run = readRun(color);
            if (run < 0) {
                return false;
            }
            var a1 = Math.min(width, a0 + run);
            if (color == 1) {
                fill(output, rowOffset, a0, a1);
            }
            a0 = a1;
            if (a0 < width) {
                addChange(a0);
            }
            color ^= 1;
        }
        return true;
    }

    /**
     * Decodes one row coded two-dimensionally against the reference row.
     *
     * @param output    the destination rows
     * @param rowOffset the index of the row
     * @param width     the number of pixels per row
     * @return whether the row decoded completely
     */
    private boolean decodeTwoDimensional(byte[] output, int rowOffset, int width) {
        currentCount = 0;
        var a0 = -1;
        var color = 0;
        var index = 0;
        while (a0 < width) {
            while (index > 0 && reference[index - 1] > a0) {
                index--;
            }
            while (reference[index] <= a0 || (index & 1) != color) {
                index++;
            }
            var b1 = reference[index];
            var b2 = reference[index + 1];
            if (position >= totalBits) {
                return false;
            }
            var entry = MODES[(int) peek(7)];
            if (entry == 0) {
                return false;
            }
            position += entry >>> 8;
            var mode = entry & 0xFF;
            if (mode == PASS) {
                if (color == 1) {
                    fill(output, rowOffset, Math.max(a0, 0), b2);
                }
                a0 = b2;
            } else if (mode == HORIZONTAL) {
                var first = readRun(color);
                if (first < 0) {
                    return false;
                }
                var second = readRun(color ^ 1);
                if (second < 0) {
                    return false;
                }
                var from = Math.max(a0, 0);
                var a1 = Math.min(width, from + first);
                var a2 = Math.min(width, a1 + second);
                if (color == 1) {
                    fill(output, rowOffset, from, a1);
                } else {
                    fill(output, rowOffset, a1, a2);
                }
                if (a1 < width) {
                    addChange(a1);
                }
                if (a2 < width) {
                    addChange(a2);
                }
                a0 = a2;
            } else {
                var a1 = b1 + mode - VERTICAL;
                if (a1 < 0 || a1 > width || a1 < a0) {
                    return false;
                }
                if (color == 1) {
                    fill(output, rowOffset, Math.max(a0, 0), a1);
                }
                if (a1 < width) {
                    addChange(a1);
                }
                a0 = a1;
                color ^= 1;
            }
        }
        return true;
    }

    /**
     * Reads one run length: any make-up codes followed by a terminating code.
     *
     * @param color 0 for a white run, 1 for a black run
     * @return the run length, or {@code -1} at an invalid code, an end-of-line code or the end
     *         of the data
     */
    private int readRun(int color) {
        var table = color == 0 ? WHITE : BLACK;
        var total = 0;
        while (true) {
            if (position >= totalBits) {
                return -1;
            }
            var entry = table[(int) peek(LOOKUP_BITS)];
            if (entry == 0) {
                return -1;
            }
            var run = entry & 0xFFFF;
            if (run == EOL_RUN) {
                return -1;
            }
            position += entry >>> 16;
            total += run;
            if (run < 64) {
                return total;
            }
            if (total > 1 << 20) {
                return -1;
            }
        }
    }

    /**
     * Records a changing element of the current row.
     *
     * @param at the pixel where the color changes
     */
    private void addChange(int at) {
        if (currentCount == current.length - 2) {
            current = Arrays.copyOf(current, current.length * 2);
        }
        current[currentCount++] = at;
    }

    /**
     * Makes the current row the reference for the next one.
     *
     * @param width the number of pixels per row
     */
    private void swapRows(int width) {
        var swap = reference;
        reference = current;
        current = swap;
        referenceCount = currentCount;
        if (reference.length < referenceCount + 3) {
            reference = Arrays.copyOf(reference, referenceCount + 3);
        }
        if (current.length < reference.length) {
            current = new int[reference.length];
        }
        reference[referenceCount] = width;
        reference[referenceCount + 1] = width;
        reference[referenceCount + 2] = width;
    }

    /**
     * Sets the reference row to an imaginary all-white row.
     *
     * @param width the number of pixels per row
     */
    private void resetReference(int width) {
        referenceCount = 0;
        reference[0] = width;
        reference[1] = width;
        reference[2] = width;
    }

    /**
     * Grows the changing-element arrays to hold a row of a width.
     *
     * @param capacity the number of elements needed
     */
    private void ensure(int capacity) {
        if (reference.length < capacity) {
            reference = new int[capacity];
        }
        if (current.length < capacity) {
            current = new int[capacity];
        }
    }

    /**
     * Sets the bits of a black run.
     *
     * @param output    the destination rows
     * @param rowOffset the index of the row
     * @param from      the first pixel of the run
     * @param to        the pixel after the run
     */
    private static void fill(byte[] output, int rowOffset, int from, int to) {
        if (from >= to) {
            return;
        }
        var first = from >>> 3;
        var last = (to - 1) >>> 3;
        var headMask = 0xFF >>> (from & 7);
        var tailMask = 0xFF << (7 - ((to - 1) & 7)) & 0xFF;
        if (first == last) {
            output[rowOffset + first] |= (byte) (headMask & tailMask);
            return;
        }
        output[rowOffset + first] |= (byte) headMask;
        for (var index = first + 1; index < last; index++) {
            output[rowOffset + index] = (byte) 0xFF;
        }
        output[rowOffset + last] |= (byte) tailMask;
    }

    /**
     * Returns the next bits without consuming them, padding past the end of the data with zeros.
     *
     * @param count the number of bits, from 1 to 32
     * @return the bits, most significant first
     */
    private long peek(int count) {
        var result = 0L;
        var at = position;
        var remaining = count;
        while (remaining > 0) {
            var byteIndex = at >>> 3;
            var bit = (int) (at & 7);
            var available = 8 - bit;
            var take = Math.min(available, remaining);
            var value = byteIndex < (totalBits >>> 3) ? data[start + (int) byteIndex] & 0xFF : 0;
            var bits = (value >>> (available - take)) & ((1 << take) - 1);
            result = (result << take) | bits;
            remaining -= take;
            at += take;
        }
        return result;
    }

    /**
     * Reads one bit.
     *
     * @return the bit
     */
    private int readBit() {
        var bit = (int) peek(1);
        position++;
        return bit;
    }
}
