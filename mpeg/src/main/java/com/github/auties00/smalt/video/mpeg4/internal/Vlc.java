package com.github.auties00.smalt.video.mpeg4.internal;

import java.util.Arrays;

/**
 * Decodes one variable length code table with a two-level lookup.
 *
 * <p>The first level is indexed by the next {@code rootBits} bits of the stream. Its entries hold
 * either a decoded symbol and its code length, or a link to a second-level table indexed by the
 * bits that follow, sized for the longest code sharing that prefix. A bit string that starts no
 * code decodes to {@link #INVALID} without consuming anything. The codes stay available, in the
 * order they were given, to the encoders that write the table ({@link #size()}, {@link #symbol(int)},
 * {@link #code(int)} and {@link #length(int)}).
 *
 * <p>Instances are immutable and thread-safe once built.
 */
public final class Vlc {
    /**
     * The value {@link #decode(BitReader)} returns for a bit string that starts no code.
     */
    public static final int INVALID = Integer.MIN_VALUE;

    /**
     * The number of bits that index the first level.
     */
    private final int rootBits;

    /**
     * The lookup entries: a symbol entry is {@code symbol << 5 | length} with a length from 1 to
     * 31, a link entry is {@code -(offset << 5 | bits)} pointing at a second-level table of
     * {@code 1 << bits} entries at {@code offset}, and zero marks an invalid prefix.
     */
    private final int[] entries;

    /**
     * The symbol of each code, in the order given.
     */
    private final int[] symbols;

    /**
     * The bits of each code, right-aligned.
     */
    private final int[] codes;

    /**
     * The length of each code in bits.
     */
    private final int[] lengths;

    /**
     * Builds a table from code strings.
     *
     * @param rootBits the number of bits of the first level, from 1 to 16
     * @param codes    the codes as strings of {@code '0'} and {@code '1'}, spaces ignored, each at
     *                 most 32 bits long, together prefix-free
     * @param symbols  the symbol of each code, from 0 to {@code 2^26 - 1}
     * @throws IllegalArgumentException if the codes are not prefix-free or a symbol is out of
     *                                  range
     */
    public Vlc(int rootBits, String[] codes, int[] symbols) {
        if (codes.length != symbols.length) {
            throw new IllegalArgumentException("codes and symbols differ in length");
        }
        this.rootBits = rootBits;
        var count = codes.length;
        var values = new long[count];
        var lengths = new int[count];
        this.symbols = symbols.clone();
        this.codes = new int[count];
        this.lengths = lengths;
        for (var i = 0; i < count; i++) {
            var code = codes[i].replace(" ", "");
            if (code.isEmpty() || code.length() > 32 || symbols[i] < 0 || symbols[i] >= 1 << 26) {
                throw new IllegalArgumentException("bad code " + codes[i]);
            }
            values[i] = Long.parseLong(code, 2);
            lengths[i] = code.length();
            this.codes[i] = (int) values[i];
        }
        var root = new int[1 << rootBits];
        var second = new int[0];
        var secondSize = 0;
        var subBits = new int[1 << rootBits];
        for (var i = 0; i < count; i++) {
            if (lengths[i] > rootBits) {
                var prefix = (int) (values[i] >>> (lengths[i] - rootBits));
                subBits[prefix] = Math.max(subBits[prefix], lengths[i] - rootBits);
            }
        }
        var subOffset = new int[1 << rootBits];
        for (var prefix = 0; prefix < root.length; prefix++) {
            if (subBits[prefix] > 0) {
                subOffset[prefix] = (1 << rootBits) + secondSize;
                secondSize += 1 << subBits[prefix];
                root[prefix] = -((subOffset[prefix] << 5) | subBits[prefix]);
            }
        }
        second = new int[secondSize];
        for (var i = 0; i < count; i++) {
            var entry = (symbols[i] << 5) | lengths[i];
            if (lengths[i] <= rootBits) {
                var shift = rootBits - lengths[i];
                var first = (int) (values[i] << shift);
                for (var j = 0; j < 1 << shift; j++) {
                    if (root[first + j] != 0) {
                        throw new IllegalArgumentException("codes are not prefix-free at " + codes[i]);
                    }
                    root[first + j] = entry;
                }
            } else {
                var prefix = (int) (values[i] >>> (lengths[i] - rootBits));
                var bits = subBits[prefix];
                var rest = lengths[i] - rootBits;
                var shift = bits - rest;
                var low = (int) (values[i] & ((1L << rest) - 1)) << shift;
                var base = subOffset[prefix] - (1 << rootBits);
                var subEntry = (symbols[i] << 5) | rest;
                for (var j = 0; j < 1 << shift; j++) {
                    if (second[base + low + j] != 0) {
                        throw new IllegalArgumentException("codes are not prefix-free at " + codes[i]);
                    }
                    second[base + low + j] = subEntry;
                }
            }
        }
        var all = Arrays.copyOf(root, root.length + second.length);
        System.arraycopy(second, 0, all, root.length, second.length);
        this.entries = all;
    }

    /**
     * {@return the number of codes of the table}
     */
    public int size() {
        return symbols.length;
    }

    /**
     * Returns the symbol of a code.
     *
     * @param index the code index, in the order the codes were given
     * @return the symbol
     */
    public int symbol(int index) {
        return symbols[index];
    }

    /**
     * Returns the bits of a code.
     *
     * @param index the code index, in the order the codes were given
     * @return the code, right-aligned in {@link #length(int)} bits
     */
    public int code(int index) {
        return codes[index];
    }

    /**
     * Returns the length of a code.
     *
     * @param index the code index, in the order the codes were given
     * @return the length in bits, from 1 to 32
     */
    public int length(int index) {
        return lengths[index];
    }

    /**
     * Returns the table entries: the root entries first, then the second-level entries.
     *
     * @return the entries, shared with the table
     */
    int[] entries() {
        return entries;
    }

    /**
     * Returns the number of bits the root entries are indexed with.
     *
     * @return the root width in bits
     */
    int rootBits() {
        return rootBits;
    }

    /**
     * Decodes one symbol and consumes its code.
     *
     * @param reader the bit reader
     * @return the symbol, or {@link #INVALID} when the next bits start no code
     */
    public int decode(BitReader reader) {
        var entry = entries[reader.peek(rootBits)];
        if (entry > 0) {
            reader.skip(entry & 31);
            return entry >>> 5;
        }
        if (entry == 0) {
            return INVALID;
        }
        var link = -entry;
        var bits = link & 31;
        var offset = link >>> 5;
        var sub = entries[offset + (reader.peek(rootBits + bits) & ((1 << bits) - 1))];
        if (sub == 0) {
            return INVALID;
        }
        reader.skip(rootBits + (sub & 31));
        return sub >>> 5;
    }
}
