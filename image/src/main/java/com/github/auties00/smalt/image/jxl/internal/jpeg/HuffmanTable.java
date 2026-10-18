/* Ports jxl-oxide 0.12.6 (crates/jxl-jbr: the Huffman table builder). Copyright Wonwoo Choi and the jxl-oxide
   contributors; MIT License. Modified for Smalt. */
package com.github.auties00.smalt.image.jxl.internal.jpeg;

/**
 * Maps the symbols of a JPEG Huffman table to their canonical codes, for writing.
 *
 * <p>The codes are assigned in the order of the table's values, shortest first, as JPEG decoders
 * assign them; the reserved last value of the stored table gets no code.
 */
final class HuffmanTable {
    /**
     * The table of a slot no {@code DHT} segment defined: every lookup fails.
     */
    static final HuffmanTable EMPTY = new HuffmanTable(new int[256], new int[256]);

    /**
     * The code length of each symbol, or 0 when it has no code.
     */
    private final int[] lengths;

    /**
     * The code of each symbol, right-aligned.
     */
    private final int[] codes;

    /**
     * Creates a table.
     *
     * @param lengths the code length of each symbol
     * @param codes the code of each symbol
     */
    private HuffmanTable(int[] lengths, int[] codes) {
        this.lengths = lengths;
        this.codes = codes;
    }

    /**
     * Builds the table of a stored Huffman code.
     *
     * @param counts the number of codes of each length 0 to 16, including the reserved code
     * @param values the symbols in code order, including the reserved last symbol
     * @return the table
     */
    static HuffmanTable build(int[] counts, int[] values) {
        var symbolLengths = new int[values.length];
        var position = 0;
        for (var length = 0; length < counts.length && position < values.length; length++) {
            for (var n = 0; n < counts[length] && position < values.length; n++) {
                symbolLengths[position++] = length;
            }
        }
        var lengths = new int[256];
        var codes = new int[256];
        var used = values.length - 1;
        if (used <= 0) {
            return new HuffmanTable(lengths, codes);
        }
        var code = 0L;
        var previous = symbolLengths[0];
        for (var index = 0; index < used; index++) {
            var length = symbolLengths[index];
            if (length != previous) {
                code <<= length - previous;
                previous = length;
            }
            var value = values[index];
            lengths[value] = length;
            codes[value] = (int) code;
            code++;
        }
        return new HuffmanTable(lengths, codes);
    }

    /**
     * Returns the code length of a symbol.
     *
     * @param symbol the symbol, 0 to 255
     * @return the length, 1 to 16
     * @throws com.github.auties00.smalt.MediaException.Malformed if the symbol has no code
     */
    int length(int symbol) {
        var length = lengths[symbol & 0xFF];
        if (length == 0) {
            throw JbrdHeader.malformed("symbol " + symbol + " missing from a Huffman table");
        }
        return length;
    }

    /**
     * Returns the code of a symbol.
     *
     * @param symbol the symbol, 0 to 255, whose length {@link #length(int)} checked
     * @return the code, right-aligned
     */
    int code(int symbol) {
        return codes[symbol & 0xFF];
    }
}
