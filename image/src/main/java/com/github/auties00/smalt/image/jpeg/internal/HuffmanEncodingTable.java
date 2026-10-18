package com.github.auties00.smalt.image.jpeg.internal;

/**
 * Maps the symbols of one Huffman table to their codes, for the encoder.
 *
 * <p>A symbol absent from the table has code length zero.
 */
public final class HuffmanEncodingTable {
    /**
     * The code of each symbol, right-aligned.
     */
    final int[] codes = new int[256];

    /**
     * The code length of each symbol, zero when the symbol has no code.
     */
    final int[] lengths = new int[256];

    /**
     * The table as it is written in the DHT segment.
     */
    final HuffmanSpec spec;

    /**
     * Derives the codes of a table (T.81 Annex C).
     *
     * @param spec the table
     * @throws com.github.auties00.smalt.MediaException.Malformed if the table is not a valid
     *                                                                  prefix code
     */
    public HuffmanEncodingTable(HuffmanSpec spec) {
        this.spec = spec;
        var codes = spec.codes();
        var lengths = spec.lengths();
        for (var i = 0; i < codes.length; i++) {
            var symbol = spec.value(i);
            this.codes[symbol] = codes[i];
            this.lengths[symbol] = lengths[i];
        }
    }

    /**
     * Builds the table that codes the given symbol frequencies with the fewest bits, limited to
     * 16-bit codes and never assigning the all-ones code (T.81 Annex K.2).
     *
     * <p>Symbols with a zero frequency get no code.
     *
     * @implNote This implementation is a port of libjpeg-turbo's {@code jpeg_gen_optimal_table}
     * (jchuff.c): Huffman's algorithm over the nonzero frequencies plus a reserved pseudo-symbol
     * 256 (which keeps the all-ones code unused), ties broken toward the larger symbol, then the
     * code-length limiting procedure of Figure K.3.
     * @param frequencies the frequency of each symbol 0 to 255; not modified
     * @return the table
     */
    public static HuffmanSpec optimal(long[] frequencies) {
        var freq = new long[257];
        System.arraycopy(frequencies, 0, freq, 0, 256);
        freq[256] = 1;
        var bits = new int[34];
        var bitPosition = new int[33];
        var codeSize = new int[257];
        var nonzeroIndex = new int[257];
        var others = new int[257];
        java.util.Arrays.fill(others, -1);
        var symbols = 0;
        for (var i = 0; i < 257; i++) {
            if (freq[i] != 0) {
                nonzeroIndex[symbols] = i;
                freq[symbols] = freq[i];
                symbols++;
            }
        }
        while (true) {
            var c1 = -1;
            var c2 = -1;
            var v = Long.MAX_VALUE - 1;
            var v2 = Long.MAX_VALUE - 1;
            for (var i = 0; i < symbols; i++) {
                if (freq[i] <= v2) {
                    if (freq[i] <= v) {
                        c2 = c1;
                        v2 = v;
                        v = freq[i];
                        c1 = i;
                    } else {
                        v2 = freq[i];
                        c2 = i;
                    }
                }
            }
            if (c2 < 0) {
                break;
            }
            freq[c1] += freq[c2];
            freq[c2] = Long.MAX_VALUE;
            codeSize[c1]++;
            while (others[c1] >= 0) {
                c1 = others[c1];
                codeSize[c1]++;
            }
            others[c1] = c2;
            codeSize[c2]++;
            while (others[c2] >= 0) {
                c2 = others[c2];
                codeSize[c2]++;
            }
        }
        for (var i = 0; i < symbols; i++) {
            if (codeSize[i] > 32) {
                throw new IllegalStateException("Huffman code length overflow");
            }
            bits[codeSize[i]]++;
        }
        var p = 0;
        for (var i = 1; i <= 32; i++) {
            bitPosition[i] = p;
            p += bits[i];
        }
        var i = 32;
        for (; i > 16; i--) {
            while (bits[i] > 0) {
                var j = i - 2;
                while (bits[j] == 0) {
                    j--;
                }
                bits[i] -= 2;
                bits[i - 1]++;
                bits[j + 1] += 2;
                bits[j]--;
            }
        }
        while (bits[i] == 0) {
            i--;
        }
        bits[i]--;
        var counts = new int[17];
        var total = 0;
        for (var length = 1; length <= 16; length++) {
            counts[length] = bits[length];
            total += bits[length];
        }
        var values = new int[total];
        for (var s = 0; s < symbols - 1; s++) {
            values[bitPosition[codeSize[s]]] = nonzeroIndex[s];
            bitPosition[codeSize[s]]++;
        }
        return new HuffmanSpec(counts, values);
    }
}
