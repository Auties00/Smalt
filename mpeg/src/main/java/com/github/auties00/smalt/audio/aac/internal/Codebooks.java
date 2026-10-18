package com.github.auties00.smalt.audio.aac.internal;


/**
 * Describes the AAC Huffman codebooks and decodes their codewords with two-level lookup tables.
 *
 * <p>A lookup table is indexed by the next {@link #ROOT_BITS} bits of the stream. A leaf entry
 * holds the codeword length in its low five bits and the decoded payload above them; an entry
 * with the sign bit set points to a second-level table indexed by the bits that follow the root
 * prefix. Spectrum payloads pack the decoded values as five-bit two's-complement fields, first
 * value lowest, followed by a three-bit count of non-zero values; the scalefactor payload is
 * the codebook index, the coded difference plus 60.
 */
public final class Codebooks {
    /**
     * The number of bits that index the first-level tables.
     */
    public static final int ROOT_BITS = 10;

    /**
     * The codebook of zero bands, which code no spectral values.
     */
    public static final int ZERO_HCB = 0;

    /**
     * The escape codebook, the last codebook that codes spectral values.
     */
    public static final int ESC_HCB = 11;

    /**
     * The reserved codebook number.
     */
    public static final int RESERVED_HCB = 12;

    /**
     * The codebook of perceptual noise substitution bands.
     */
    public static final int NOISE_HCB = 13;

    /**
     * The codebook of out-of-phase intensity stereo bands.
     */
    public static final int INTENSITY_HCB2 = 14;

    /**
     * The codebook of in-phase intensity stereo bands.
     */
    public static final int INTENSITY_HCB = 15;

    /**
     * The value of an escape codebook component that announces an escape sequence.
     */
    public static final int ESC_FLAG = 16;

    /**
     * The number of values each spectrum codebook codes per codeword, indexed by codebook.
     */
    private static final int[] DIMENSION = {0, 4, 4, 4, 4, 2, 2, 2, 2, 2, 2, 2};

    /**
     * Whether each spectrum codebook codes magnitudes followed by sign bits, indexed by codebook.
     */
    private static final boolean[] UNSIGNED = {false, false, false, true, true, false, false, true, true, true, true, true};

    /**
     * The largest absolute value each spectrum codebook codes, indexed by codebook; codebook 11
     * escapes beyond it.
     */
    private static final int[] LARGEST_VALUE = {0, 1, 1, 2, 2, 4, 4, 7, 7, 12, 12, 16};

    /**
     * The decoding table of the scalefactor codebook.
     */
    private static final int[] SCALEFACTOR_TABLE = buildTable(HuffmanTables.SCALEFACTOR_CODES,
            HuffmanTables.SCALEFACTOR_LENGTHS, -1);

    /**
     * The decoding tables of the spectrum codebooks, indexed by codebook; index 0 is unused.
     */
    private static final int[][] SPECTRUM_TABLES = buildSpectrumTables();

    /**
     * Prevents instantiation.
     */
    private Codebooks() {
        throw new AssertionError();
    }

    /**
     * Returns whether a spectrum codebook codes magnitudes followed by sign bits.
     *
     * @param codebook the codebook, from 1 to 11
     * @return {@code true} for codebooks 3, 4 and 7 to 11
     */
    public static boolean isUnsigned(int codebook) {
        return UNSIGNED[codebook];
    }

    /**
     * Returns the decoding table of a spectrum codebook.
     *
     * @param codebook the codebook, from 1 to 11
     * @return the table; shared and not to be modified
     */
    public static int[] spectrumTable(int codebook) {
        return SPECTRUM_TABLES[codebook];
    }

    /**
     * Decodes one codeword and returns its lookup table entry.
     *
     * @param reader the bit source
     * @param table the decoding table
     * @return the leaf entry: the length in the low five bits, the payload above them
     */
    public static int decode(AacBitReader reader, int[] table) {
        reader.ensure(32);
        return decodeFast(reader, table);
    }

    /**
     * Decodes one codeword from bits the caller has ensured.
     *
     * @param reader the bit source, holding at least 19 cached bits
     * @param table the decoding table
     * @return the leaf entry: the length in the low five bits, the payload above them
     */
    public static int decodeFast(AacBitReader reader, int[] table) {
        var entry = table[reader.peekFast(ROOT_BITS)];
        if (entry < 0) {
            var subBits = entry & 31;
            var base = (entry & 0x7FFFFFFF) >>> 5;
            entry = table[base + (reader.peekFast(ROOT_BITS + subBits) & ((1 << subBits) - 1))];
        }
        reader.consumeFast(entry & 31);
        return entry;
    }

    /**
     * Decodes one scalefactor codeword.
     *
     * @param reader the bit source
     * @return the coded difference, from -60 to 60
     */
    public static int decodeScalefactor(AacBitReader reader) {
        return (decode(reader, SCALEFACTOR_TABLE) >>> 5) - 60;
    }

    /**
     * Builds the decoding tables of the eleven spectrum codebooks.
     *
     * @return the tables, indexed by codebook
     */
    private static int[][] buildSpectrumTables() {
        var tables = new int[12][];
        for (var codebook = 1; codebook <= 11; codebook++) {
            tables[codebook] = buildTable(HuffmanTables.SPECTRUM_CODES[codebook],
                    HuffmanTables.SPECTRUM_LENGTHS[codebook], codebook);
        }
        return tables;
    }

    /**
     * Computes the payload of a spectrum codebook symbol: its values and non-zero count.
     *
     * @param codebook the spectrum codebook, from 1 to 11
     * @param symbol the symbol index
     * @return the packed payload described on the class
     */
    private static int spectrumPayload(int codebook, int symbol) {
        var unsigned = UNSIGNED[codebook];
        int[] values;
        if (DIMENSION[codebook] == 4) {
            var offset = unsigned ? 0 : 1;
            values = new int[]{symbol / 27 - offset, symbol / 9 % 3 - offset, symbol / 3 % 3 - offset, symbol % 3 - offset};
        } else {
            var modulo = unsigned ? LARGEST_VALUE[codebook] + 1 : 2 * LARGEST_VALUE[codebook] + 1;
            var offset = unsigned ? 0 : LARGEST_VALUE[codebook];
            values = new int[]{symbol / modulo - offset, symbol % modulo - offset};
        }
        var payload = 0;
        var nonZero = 0;
        for (var i = 0; i < values.length; i++) {
            payload |= (values[i] & 31) << (5 * i);
            if (values[i] != 0) {
                nonZero++;
            }
        }
        return payload | nonZero << 20;
    }

    /**
     * Builds a two-level decoding table from a prefix-free codebook.
     *
     * @param codes the codewords, right-aligned
     * @param lengths the codeword lengths
     * @param codebook the spectrum codebook the table decodes, or {@code -1} for the
     * scalefactor codebook
     * @return the table described on the class
     */
    private static int[] buildTable(int[] codes, byte[] lengths, int codebook) {
        var rootSize = 1 << ROOT_BITS;
        var subBits = new int[rootSize];
        for (var symbol = 0; symbol < codes.length; symbol++) {
            var length = lengths[symbol];
            if (length > ROOT_BITS) {
                var prefix = codes[symbol] >>> (length - ROOT_BITS);
                subBits[prefix] = Math.max(subBits[prefix], length - ROOT_BITS);
            }
        }
        var size = rootSize;
        var base = new int[rootSize];
        for (var prefix = 0; prefix < rootSize; prefix++) {
            if (subBits[prefix] != 0) {
                base[prefix] = size;
                size += 1 << subBits[prefix];
            }
        }
        var table = new int[size];
        for (var prefix = 0; prefix < rootSize; prefix++) {
            if (subBits[prefix] != 0) {
                table[prefix] = Integer.MIN_VALUE | base[prefix] << 5 | subBits[prefix];
            }
        }
        for (var symbol = 0; symbol < codes.length; symbol++) {
            var length = lengths[symbol];
            var code = codes[symbol];
            var payload = codebook < 0 ? symbol : spectrumPayload(codebook, symbol);
            var entry = payload << 5 | length;
            if (length <= ROOT_BITS) {
                var first = code << (ROOT_BITS - length);
                var count = 1 << (ROOT_BITS - length);
                for (var i = 0; i < count; i++) {
                    table[first + i] = entry;
                }
            } else {
                var prefix = code >>> (length - ROOT_BITS);
                var extra = length - ROOT_BITS;
                var bits = subBits[prefix];
                var suffix = code & ((1 << extra) - 1);
                var first = base[prefix] + (suffix << (bits - extra));
                var count = 1 << (bits - extra);
                for (var i = 0; i < count; i++) {
                    table[first + i] = entry;
                }
            }
        }
        return table;
    }
}
