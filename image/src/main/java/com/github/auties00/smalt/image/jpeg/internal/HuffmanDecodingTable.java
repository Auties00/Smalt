package com.github.auties00.smalt.image.jpeg.internal;

import com.github.auties00.smalt.MediaException;

/**
 * Decodes the symbols of one Huffman table with lookahead tables.
 *
 * <p>The next {@link #LOOKAHEAD} bits of the stream index {@link #lookup}, which yields the code
 * length and symbol of every code no longer than the lookahead; longer codes fall back to the
 * bit-serial procedure of T.81 Figure F.16 in {@link #decodeSlow(long)}. A second table,
 * {@link #fast}, additionally resolves the magnitude bits that follow the most frequent codes:
 * for an AC table it holds the run length and the dequantizable coefficient value of every code
 * whose code and magnitude bits together fit in the lookahead, and for a DC table the DC
 * difference.
 *
 * <p>Entries of {@link #lookup} are {@code (length << 8) | symbol}. Entries of {@link #fast} are
 * {@code (value << 16) | (run << 4) | totalLength} for AC tables and
 * {@code (value << 16) | totalLength} for DC tables, where {@code totalLength} counts the code and
 * the magnitude bits. A zero entry means the fast path does not apply.
 *
 * @implNote This implementation follows libjpeg-turbo's {@code jpeg_make_d_derived_tbl} and
 * {@code jpeg_huff_decode} (jdhuff.c), including its validation (oversubscribed code lengths are
 * rejected, DC symbols above 15 are rejected) and its handling of an invalid code (17 bits are
 * consumed and symbol 0 is returned). The combined value table follows the idea of stb_image's
 * {@code fast_ac}.
 */
public final class HuffmanDecodingTable {
    /**
     * The number of bits resolved by one table lookup.
     */
    public static final int LOOKAHEAD = 10;

    /**
     * The code length reported for an invalid code, which consumes 17 bits.
     */
    public static final int INVALID_LENGTH = 17;

    /**
     * The code length and symbol of every lookahead value, or zero for longer codes.
     */
    final int[] lookup;

    /**
     * The fully decoded run and value of every lookahead value whose code and magnitude bits fit,
     * or zero.
     */
    final int[] fast;

    /**
     * The largest code of each length, or {@code -1} when no code has that length; index 17 holds
     * a sentinel that terminates the bit-serial search.
     */
    private final int[] maxCode;

    /**
     * The offset from a code to its symbol index, per length.
     */
    private final int[] valueOffset;

    /**
     * The symbols in code order.
     */
    private final int[] values;

    /**
     * Whether this is a DC table.
     */
    private final boolean dc;

    /**
     * Builds the decoding tables of a Huffman table.
     *
     * @param spec the table as coded in its DHT segment
     * @param dc   whether the table decodes DC differences, which restricts its symbols to 0..15
     * @throws MediaException.Malformed if the code lengths oversubscribe the code space, or a DC
     *                                  table holds a symbol above 15
     */
    public HuffmanDecodingTable(HuffmanSpec spec, boolean dc) {
        this.dc = dc;
        var codes = spec.codes();
        var lengths = spec.lengths();
        this.values = spec.values();
        if (dc) {
            for (var value : values) {
                if (value > 15) {
                    throw new MediaException.Malformed("DC Huffman table holds symbol " + value + ", above 15");
                }
            }
        }
        this.maxCode = new int[18];
        this.valueOffset = new int[18];
        var p = 0;
        for (var length = 1; length <= 16; length++) {
            var count = spec.count(length);
            if (count != 0) {
                valueOffset[length] = p - codes[p];
                p += count;
                maxCode[length] = codes[p - 1];
            } else {
                maxCode[length] = -1;
            }
        }
        maxCode[17] = Integer.MAX_VALUE;
        this.lookup = new int[1 << LOOKAHEAD];
        this.fast = new int[1 << LOOKAHEAD];
        for (var i = 0; i < codes.length; i++) {
            var length = lengths[i];
            if (length > LOOKAHEAD) {
                break;
            }
            var symbol = values[i];
            var first = codes[i] << (LOOKAHEAD - length);
            var span = 1 << (LOOKAHEAD - length);
            for (var j = 0; j < span; j++) {
                var bits = first + j;
                lookup[bits] = (length << 8) | symbol;
                fast[bits] = fastEntry(bits, length, symbol);
            }
        }
    }

    /**
     * Computes the {@link #fast} entry of one lookahead value.
     *
     * @param bits   the lookahead value
     * @param length the length of the code it starts with
     * @param symbol the symbol of that code
     * @return the entry, or zero when the magnitude bits do not fit in the lookahead
     */
    private int fastEntry(int bits, int length, int symbol) {
        var size = dc ? symbol : symbol & 15;
        var run = dc ? 0 : symbol >>> 4;
        if (!dc && size == 0) {
            return 0;
        }
        var total = length + size;
        if (total > LOOKAHEAD) {
            return 0;
        }
        var value = 0;
        if (size != 0) {
            var magnitude = (bits >>> (LOOKAHEAD - total)) & ((1 << size) - 1);
            value = extend(magnitude, size);
        }
        return (value << 16) | (run << 4) | total;
    }

    /**
     * Extends a magnitude-coded value to its signed value (T.81 Figure F.12).
     *
     * @param bits the received magnitude bits
     * @param size the number of magnitude bits, from 1 to 16
     * @return the signed value
     */
    public static int extend(int bits, int size) {
        return bits < (1 << (size - 1)) ? bits - (1 << size) + 1 : bits;
    }

    /**
     * Decodes a code longer than the lookahead, or an invalid code.
     *
     * @param cache the bit cache, left-aligned, holding at least 17 valid bits
     * @return {@code (length << 8) | symbol}; an invalid code yields length
     *         {@link #INVALID_LENGTH} and symbol 0
     */
    public int decodeSlow(long cache) {
        var length = LOOKAHEAD + 1;
        var code = (int) (cache >>> (64 - length));
        while (code > maxCode[length]) {
            length++;
            code = (int) (cache >>> (64 - length));
        }
        var index = code + valueOffset[length];
        if (length > 16 || index < 0 || index >= values.length) {
            return INVALID_LENGTH << 8;
        }
        return (length << 8) | values[index];
    }

    /**
     * Decodes one symbol from a left-aligned cache.
     *
     * @param cache the bit cache, left-aligned, holding at least 17 valid bits
     * @return {@code (length << 8) | symbol}, as {@link #decodeSlow(long)}
     */
    public int decode(long cache) {
        var entry = lookup[(int) (cache >>> (64 - LOOKAHEAD))];
        return entry != 0 ? entry : decodeSlow(cache);
    }
}
