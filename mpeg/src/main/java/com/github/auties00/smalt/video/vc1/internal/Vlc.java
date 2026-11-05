package com.github.auties00.smalt.video.vc1.internal;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Decodes one variable length code table with multi-level lookup tables.
 *
 * <p>The table is built from packed entries (the code length in bits 27 to 31, the code in bits 0 to 26) indexed by
 * symbol. Each lookup level reads at most {@value #LEVEL_BITS} bits. Instances are immutable and thread-safe.
 */
final class Vlc {
    /**
     * The symbol returned for a code that is not in the table; no bits are consumed past the longest prefix tried.
     */
    static final int INVALID = -1;

    /**
     * The bits one lookup level reads at most.
     */
    private static final int LEVEL_BITS = 9;

    /**
     * The lookup tables, the root table first: a leaf entry holds the symbol plus 1 above bit 8 and the code bits it
     * consumes in bits 0 to 7; a link entry has bit 31 set, the offset of its table above bit 5 and that table's bits
     * in bits 0 to 4.
     */
    private final int[] table;

    /**
     * The bits the root table reads.
     */
    private final int rootBits;

    /**
     * Builds a decoder.
     *
     * @param packed the code of each symbol, a zero entry for a symbol without code
     */
    Vlc(int[] packed) {
        this(packed, 0);
    }

    /**
     * Builds a decoder whose symbols are offset from the array index.
     *
     * @param packed the code of each symbol, a zero entry for a symbol without code
     * @param firstSymbol the symbol of the first entry
     */
    Vlc(int[] packed, int firstSymbol) {
        var codes = new ArrayList<int[]>();
        var maxLength = 0;
        for (var i = 0; i < packed.length; i++) {
            var length = packed[i] >>> 27;
            if (length == 0) {
                continue;
            }
            codes.add(new int[]{packed[i] & 0x7FFFFFF, length, i + firstSymbol});
            maxLength = Math.max(maxLength, length);
        }
        rootBits = Math.min(LEVEL_BITS, maxLength);
        var out = new int[1 << rootBits];
        var size = new int[]{out.length};
        var holder = new int[][]{out};
        fill(holder, size, 0, rootBits, codes, 0);
        table = Arrays.copyOf(holder[0], size[0]);
    }

    /**
     * Fills one lookup table and the tables it links to.
     *
     * @param holder the array of every table, replaced when it grows
     * @param size the used length of the array
     * @param base the index of the table
     * @param bits the bits the table reads
     * @param codes the codes, each the code value, its length and its symbol, whose first {@code consumed} bits led to
     * this table
     * @param consumed the code bits the levels above read
     */
    private static void fill(int[][] holder, int[] size, int base, int bits, List<int[]> codes, int consumed) {
        var groups = new ArrayList<List<int[]>>();
        for (var i = 0; i < 1 << bits; i++) {
            groups.add(null);
        }
        for (var code : codes) {
            var value = code[0];
            var length = code[1];
            var remaining = length - consumed;
            if (remaining <= bits) {
                var prefix = (value & ((1 << remaining) - 1)) << (bits - remaining);
                for (var fill = 0; fill < 1 << (bits - remaining); fill++) {
                    holder[0][base + prefix + fill] = ((code[2] + 1) << 8) | remaining;
                }
            } else {
                var prefix = (value >>> (remaining - bits)) & ((1 << bits) - 1);
                var group = groups.get(prefix);
                if (group == null) {
                    group = new ArrayList<>();
                    groups.set(prefix, group);
                }
                group.add(code);
            }
        }
        for (var prefix = 0; prefix < 1 << bits; prefix++) {
            var group = groups.get(prefix);
            if (group == null) {
                continue;
            }
            var deepest = 0;
            for (var code : group) {
                deepest = Math.max(deepest, code[1] - consumed - bits);
            }
            var subBits = Math.min(LEVEL_BITS, deepest);
            var offset = size[0];
            size[0] += 1 << subBits;
            if (size[0] > holder[0].length) {
                holder[0] = Arrays.copyOf(holder[0], Math.max(size[0], holder[0].length * 2));
            }
            holder[0][base + prefix] = 0x80000000 | (offset << 5) | subBits;
            fill(holder, size, offset, subBits, group, consumed + bits);
        }
    }

    /**
     * Decodes one symbol.
     *
     * @param bits the reader
     * @return the symbol, or {@link #INVALID} for a code that is not in the table
     */
    int decode(Bits bits) {
        var width = rootBits;
        var base = 0;
        while (true) {
            var entry = table[base + bits.peek(width)];
            if (entry > 0) {
                bits.skip(entry & 0xFF);
                return (entry >>> 8) - 1;
            }
            if (entry == 0) {
                return INVALID;
            }
            bits.skip(width);
            base = (entry & 0x7FFFFFFF) >>> 5;
            width = entry & 31;
        }
    }
}
