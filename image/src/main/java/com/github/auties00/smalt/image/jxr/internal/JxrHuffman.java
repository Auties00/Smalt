/* Ports jxrlib 1.1 (image/sys/adapthuff.c, image/decode/segdec.c: getHuff). Copyright (c) Microsoft Corporation;
   BSD 2-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.image.jxr.internal;

/**
 * Decodes the symbols of one adaptive variable-length code of JPEG XR: a set of fixed code tables of an alphabet,
 * between which the decoder switches as a discriminant of the decoded symbols drifts.
 *
 * <p>A decode table holds, for each 5-bit prefix, the symbol shifted left by 3 plus the code length, or a negative
 * entry pointing to a binary tree walked one bit at a time for longer codes.
 */
final class JxrHuffman {
    /**
     * The number of bits of the root lookup.
     */
    private static final int ROOT_BITS = 5;

    /**
     * The bits holding the code length in a root entry.
     */
    private static final int LENGTH_BITS = 3;

    /**
     * The bound on the magnitude of a discriminant.
     */
    private static final int LIMIT = 8 * 8;

    /**
     * The drift of the discriminant that switches tables.
     */
    private static final int THRESHOLD = 8;

    /**
     * The number of tables of each alphabet size.
     */
    private static final int[] TABLES = {0, 0, 0, 0, 1, 2, 4, 2, 2, 2, 0, 0, 5};

    /**
     * Whether an alphabet size keeps a second discriminant.
     */
    private static final boolean[] SECOND = {false, false, false, false, false, false, true, false, false, false,
            false, false, true};

    /**
     * The decode table of the alphabet of 4.
     */
    private static final short[] TABLE4 = {
            19, 19, 19, 19, 27, 27, 27, 27, 10, 10, 10, 10, 10, 10, 10, 10,
            1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
            0, 0, 0, 0, 0, 0, 0, 0};

    /**
     * The decode tables of the alphabet of 5.
     */
    private static final short[][] TABLE5 = {{
            28, 28, 36, 36, 19, 19, 19, 19, 10, 10, 10, 10, 10, 10, 10, 10,
            1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0}, {
            11, 11, 11, 11, 19, 19, 19, 19, 27, 27, 27, 27, 35, 35, 35, 35,
            1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0}};

    /**
     * The decode tables of the alphabet of 6.
     */
    private static final short[][] TABLE6 = {{
            13, 29, 44, 44, 19, 19, 19, 19, 34, 34, 34, 34, 34, 34, 34, 34,
            1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0}, {
            12, 12, 28, 28, 43, 43, 43, 43, 2, 2, 2, 2, 2, 2, 2, 2,
            18, 18, 18, 18, 18, 18, 18, 18, 34, 34, 34, 34, 34, 34, 34, 34,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0}, {
            4, 4, 12, 12, 43, 43, 43, 43, 18, 18, 18, 18, 18, 18, 18, 18,
            26, 26, 26, 26, 26, 26, 26, 26, 34, 34, 34, 34, 34, 34, 34, 34,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0}, {
            5, 13, 36, 36, 43, 43, 43, 43, 18, 18, 18, 18, 18, 18, 18, 18,
            25, 25, 25, 25, 25, 25, 25, 25, 25, 25, 25, 25, 25, 25, 25, 25,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0}};

    /**
     * The decode tables of the alphabet of 7.
     */
    private static final short[][] TABLE7 = {{
            45, 53, 36, 36, 27, 27, 27, 27, 2, 2, 2, 2, 2, 2, 2, 2,
            10, 10, 10, 10, 10, 10, 10, 10, 18, 18, 18, 18, 18, 18, 18, 18,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0}, {
            -32736, 37, 28, 28, 19, 19, 19, 19, 10, 10, 10, 10, 10, 10, 10, 10,
            1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
            5, 6, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0}};

    /**
     * The decode table of the alphabet of 8.
     */
    private static final short[] TABLE8 = {
            53, 21, 28, 28, 11, 11, 11, 11, 43, 43, 43, 43, 59, 59, 59, 59,
            2, 2, 2, 2, 2, 2, 2, 2, 34, 34, 34, 34, 34, 34, 34, 34,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};

    /**
     * The decode tables of the alphabet of 9.
     */
    private static final short[][] TABLE9 = {{
            13, 29, 37, 61, 20, 20, 68, 68, 3, 3, 3, 3, 51, 51, 51, 51,
            41, 41, 41, 41, 41, 41, 41, 41, 41, 41, 41, 41, 41, 41, 41, 41,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0}, {
            -32736, 53, 28, 28, 11, 11, 11, 11, 19, 19, 19, 19, 43, 43, 43, 43,
            1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
            -32734, 4, 7, 8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0}};

    /**
     * The decode tables of the alphabet of 12.
     */
    private static final short[][] TABLE12 = {{
            -32736, 5, 76, 76, 37, 53, 69, 85, 43, 43, 43, 43, 91, 91, 91, 91,
            57, 57, 57, 57, 57, 57, 57, 57, 57, 57, 57, 57, 57, 57, 57, 57,
            -32734, 1, 2, 3, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0}, {
            -32736, 85, 13, 53, 4, 4, 36, 36, 43, 43, 43, 43, 67, 67, 67, 67,
            75, 75, 75, 75, 91, 91, 91, 91, 58, 58, 58, 58, 58, 58, 58, 58,
            2, 3, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0}, {
            -32736, 37, 92, 92, 11, 11, 11, 11, 43, 43, 43, 43, 59, 59, 59, 59,
            67, 67, 67, 67, 75, 75, 75, 75, 2, 2, 2, 2, 2, 2, 2, 2,
            -32734, -32732, 2, 3, 6, 10, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0}, {
            -32736, 29, 37, 69, 3, 3, 3, 3, 43, 43, 43, 43, 59, 59, 59, 59,
            75, 75, 75, 75, 91, 91, 91, 91, 10, 10, 10, 10, 10, 10, 10, 10,
            -32734, 10, 2, 6, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0}, {
            -32736, 93, 28, 28, 60, 60, 76, 76, 3, 3, 3, 3, 43, 43, 43, 43,
            9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9,
            -32734, -32732, -32730, 2, 4, 8, 6, 10, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0}};

    /**
     * The discriminant changes of the alphabet of 5.
     */
    private static final int[] DELTA5 = {0, -1, 0, 1, 1};

    /**
     * The discriminant changes of the alphabet of 6, per table.
     */
    private static final int[] DELTA6 = {
            -1, 1, 1, 1, 0, 1,
            -2, 0, 0, 2, 0, 0,
            -1, -1, 0, 1, -2, 0};

    /**
     * The discriminant changes of the alphabet of 7.
     */
    private static final int[] DELTA7 = {1, 0, -1, -1, -1, -1, -1};

    /**
     * The discriminant changes of the alphabet of 9.
     */
    private static final int[] DELTA9 = {2, 2, 1, 1, -1, -2, -2, -2, -3};

    /**
     * The discriminant changes of the alphabet of 12, per table.
     */
    private static final int[] DELTA12 = {
            1, 1, 1, 1, 1, 0, 0, -1, 2, 1, 0, 0,
            2, 2, -1, -1, -1, 0, -2, -1, 0, 0, -2, -1,
            -1, 1, 0, 2, 0, 0, 0, 0, -2, 0, 1, 1,
            0, 1, 0, 1, -2, 0, -1, -1, -2, -1, -2, -2};

    /**
     * The number of symbols.
     */
    private final int symbols;

    /**
     * The index of the current table.
     */
    private int table;

    /**
     * The decode table of the current table.
     */
    private short[] decode;

    /**
     * The changes of the discriminant per symbol, or {@code null}.
     */
    private int[] delta;

    /**
     * The offset of the current table's changes in {@link #delta}.
     */
    private int deltaOffset;

    /**
     * The changes of the second discriminant per symbol, or {@code null}.
     */
    private int[] delta1;

    /**
     * The offset of the current table's second changes in {@link #delta1}.
     */
    private int delta1Offset;

    /**
     * Whether the code was initialized since the last reset.
     */
    private boolean initialized;

    /**
     * The discriminant.
     */
    private int discriminant;

    /**
     * The second discriminant.
     */
    private int discriminant1;

    /**
     * The discriminant above which the next table is taken.
     */
    private int upper;

    /**
     * The discriminant below which the previous table is taken.
     */
    private int lower;

    /**
     * Creates a code of an alphabet.
     *
     * @param symbols the alphabet size: 4, 5, 6, 7, 8, 9 or 12
     */
    JxrHuffman(int symbols) {
        this.symbols = symbols;
    }

    /**
     * Marks the code for reinitialization at the next {@link #adapt()}.
     */
    void reset() {
        initialized = false;
    }

    /**
     * Switches tables when the discriminant drifted past its bounds, and selects the current table.
     */
    void adapt() {
        if (!initialized) {
            initialized = true;
            discriminant = discriminant1 = 0;
            table = SECOND[symbols] ? 1 : 0;
        }
        var low = discriminant;
        var high = SECOND[symbols] ? discriminant1 : discriminant;
        var change = false;
        if (low < lower) {
            table--;
            change = true;
        } else if (high > upper) {
            table++;
            change = true;
        }
        if (change) {
            discriminant = 0;
            discriminant1 = 0;
        }
        discriminant = Math.clamp(discriminant, -LIMIT, LIMIT);
        discriminant1 = Math.clamp(discriminant1, -LIMIT, LIMIT);
        var t = table;
        lower = t == 0 ? Integer.MIN_VALUE : -THRESHOLD;
        upper = t == TABLES[symbols] - 1 ? 1 << 30 : THRESHOLD;
        delta = null;
        switch (symbols) {
            case 4 -> decode = TABLE4;
            case 5 -> {
                delta = DELTA5;
                deltaOffset = 0;
                decode = TABLE5[t];
            }
            case 6 -> {
                delta1 = DELTA6;
                delta1Offset = symbols * (t - (t + 1 == TABLES[symbols] ? 1 : 0));
                delta = DELTA6;
                deltaOffset = (t - 1 + (t == 0 ? 1 : 0)) * symbols;
                decode = TABLE6[t];
            }
            case 7 -> {
                delta = DELTA7;
                deltaOffset = 0;
                decode = TABLE7[t];
            }
            case 8 -> decode = TABLE8;
            case 9 -> {
                delta = DELTA9;
                deltaOffset = 0;
                decode = TABLE9[t];
            }
            default -> {
                delta1 = DELTA12;
                delta1Offset = symbols * (t - (t + 1 == TABLES[symbols] ? 1 : 0));
                delta = DELTA12;
                deltaOffset = (t - 1 + (t == 0 ? 1 : 0)) * symbols;
                decode = TABLE12[t];
            }
        }
    }

    /**
     * Decodes a symbol, walking the code tree past the root lookup when needed.
     *
     * @param bits the bit-stream
     * @return the symbol
     */
    int decode(JxrBits bits) {
        var entry = decode[bits.peek(ROOT_BITS)];
        bits.skip(entry < 0 ? ROOT_BITS : entry & ((1 << LENGTH_BITS) - 1));
        var symbol = entry >> LENGTH_BITS;
        if (symbol < 0) {
            symbol = entry;
            var steps = 0;
            while ((symbol = decode[symbol + 32768 + bits.bit()]) < 0) {
                if (++steps > decode.length) {
                    throw new com.github.auties00.smalt.MediaException.Malformed("invalid JPEG XR code");
                }
            }
        }
        return symbol;
    }

    /**
     * Decodes a symbol of a code whose root lookup holds every code.
     *
     * @param bits the bit-stream
     * @return the symbol
     */
    int decodeShort(JxrBits bits) {
        var entry = decode[bits.peek(ROOT_BITS)];
        bits.skip(entry & ((1 << LENGTH_BITS) - 1));
        return entry >> LENGTH_BITS;
    }

    /**
     * Adds the change of a decoded symbol to the discriminant.
     *
     * @param symbol the decoded symbol
     */
    void update(int symbol) {
        discriminant += delta[deltaOffset + symbol];
    }

    /**
     * Adds the changes of a decoded symbol to both discriminants.
     *
     * @param symbol the decoded symbol
     */
    void updateBoth(int symbol) {
        discriminant += delta[deltaOffset + symbol];
        discriminant1 += delta1[delta1Offset + symbol];
    }
}
