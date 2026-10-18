package com.github.auties00.smalt.video.intra.internal;

import java.util.Arrays;

/**
 * Decodes and encodes one prefix code (a variable-length code table) of up to 24-bit codewords
 * mapping to non-negative symbols.
 *
 * <p>Decoding looks the next {@link #primaryBits} bits up in a primary table; codewords longer than
 * that continue into a secondary table selected by their prefix, indexed by the following
 * {@link #secondaryBits} bits. Each entry packs a symbol and the length of its codeword; an entry
 * of zero marks a bit pattern no codeword starts with. Encoding maps a symbol back to its codeword
 * and length.
 *
 * <p>Instances are immutable after construction and thread-safe.
 */
public final class VlcTable {
    /**
     * The number of low bits of an entry that hold the codeword length.
     */
    private static final int LENGTH_BITS = 5;

    /**
     * The mask of the length field of an entry.
     */
    private static final int LENGTH_MASK = (1 << LENGTH_BITS) - 1;

    /**
     * The number of bits that index the primary table.
     */
    private final int primaryBits;

    /**
     * The number of bits that index a secondary table.
     */
    private final int secondaryBits;

    /**
     * The primary table: a symbol and length, zero for no codeword, or the bitwise complement of
     * the start of a secondary table.
     */
    private final int[] primary;

    /**
     * The secondary tables, back to back.
     */
    private final int[] secondary;

    /**
     * The codeword of every symbol, right-aligned, or {@code 0} for symbols without one.
     */
    private final int[] codes;

    /**
     * The codeword length of every symbol, or {@code 0} for symbols without one.
     */
    private final int[] lengths;

    /**
     * Builds the table of a prefix code.
     *
     * @param codes the codewords, right-aligned in their {@code lengths}
     * @param lengths the codeword lengths, from 1 to 24
     * @param symbols the symbol of every codeword, non-negative and below {@code 2^26}
     * @throws IllegalArgumentException if the codewords are not a prefix code or a length is out of
     * range
     */
    public VlcTable(int[] codes, int[] lengths, int[] symbols) {
        var maxLength = 0;
        var maxSymbol = 0;
        for (var i = 0; i < codes.length; i++) {
            if (lengths[i] < 1 || lengths[i] > 24) {
                throw new IllegalArgumentException("codeword length " + lengths[i]);
            }
            maxLength = Math.max(maxLength, lengths[i]);
            maxSymbol = Math.max(maxSymbol, symbols[i]);
        }
        this.primaryBits = Math.min(maxLength, 10);
        this.secondaryBits = maxLength - primaryBits;
        this.primary = new int[1 << primaryBits];
        var longPrefixes = new int[1 << primaryBits];
        Arrays.fill(longPrefixes, -1);
        var secondaryCount = 0;
        for (var i = 0; i < codes.length; i++) {
            if (lengths[i] > primaryBits) {
                var prefix = codes[i] >>> (lengths[i] - primaryBits);
                if (longPrefixes[prefix] < 0) {
                    longPrefixes[prefix] = secondaryCount++;
                }
            }
        }
        this.secondary = new int[secondaryCount << secondaryBits];
        this.codes = new int[maxSymbol + 1];
        this.lengths = new int[maxSymbol + 1];
        for (var i = 0; i < codes.length; i++) {
            var length = lengths[i];
            var entry = symbols[i] << LENGTH_BITS | length;
            this.codes[symbols[i]] = codes[i];
            this.lengths[symbols[i]] = length;
            if (length <= primaryBits) {
                var first = codes[i] << (primaryBits - length);
                var count = 1 << (primaryBits - length);
                for (var j = 0; j < count; j++) {
                    if (primary[first + j] != 0) {
                        throw new IllegalArgumentException("not a prefix code");
                    }
                    primary[first + j] = entry;
                }
            } else {
                var prefix = codes[i] >>> (length - primaryBits);
                var base = longPrefixes[prefix] << secondaryBits;
                primary[prefix] = ~base;
                var rest = length - primaryBits;
                var suffix = codes[i] & ((1 << rest) - 1);
                var first = suffix << (secondaryBits - rest);
                var count = 1 << (secondaryBits - rest);
                for (var j = 0; j < count; j++) {
                    if (secondary[base + first + j] != 0) {
                        throw new IllegalArgumentException("not a prefix code");
                    }
                    secondary[base + first + j] = entry;
                }
            }
        }
    }

    /**
     * Builds the table of a prefix code given as binary strings.
     *
     * @param codewords the codewords, each a string of {@code 0} and {@code 1} characters
     * @param symbols the symbol of every codeword
     * @return the table
     * @throws IllegalArgumentException if the codewords are not a prefix code
     */
    public static VlcTable of(String[] codewords, int[] symbols) {
        var codes = new int[codewords.length];
        var lengths = new int[codewords.length];
        for (var i = 0; i < codewords.length; i++) {
            codes[i] = Integer.parseInt(codewords[i], 2);
            lengths[i] = codewords[i].length();
        }
        return new VlcTable(codes, lengths, symbols);
    }

    /**
     * Looks up the entry of the codeword at the start of a bit window.
     *
     * @param window the next 64 bits of the stream, as {@link IntraBitReader#window()} returns them
     * @return the entry: the symbol in the bits above the low five, the codeword length in the low
     * five bits, or {@code 0} when no codeword starts with these bits
     */
    public int lookup(long window) {
        var entry = primary[(int) (window >>> (64 - primaryBits))];
        if (entry < 0) {
            var index = (int) ((window << primaryBits) >>> (64 - secondaryBits));
            entry = secondary[~entry + index];
        }
        return entry;
    }

    /**
     * Returns the symbol of an entry.
     *
     * @param entry an entry {@link #lookup(long)} returned
     * @return the symbol
     */
    public static int symbol(int entry) {
        return entry >>> LENGTH_BITS;
    }

    /**
     * Returns the codeword length of an entry.
     *
     * @param entry an entry {@link #lookup(long)} returned
     * @return the length in bits, {@code 0} for no codeword
     */
    public static int length(int entry) {
        return entry & LENGTH_MASK;
    }

    /**
     * Returns the codeword of a symbol.
     *
     * @param symbol the symbol to code, within the table
     * @return the codeword, right-aligned
     */
    public int code(int symbol) {
        return codes[symbol];
    }

    /**
     * Returns the codeword length of a symbol.
     *
     * @param symbol the symbol to code, within the table
     * @return the length in bits, {@code 0} when the symbol has no codeword
     */
    public int codeLength(int symbol) {
        return symbol < lengths.length ? lengths[symbol] : 0;
    }
}
