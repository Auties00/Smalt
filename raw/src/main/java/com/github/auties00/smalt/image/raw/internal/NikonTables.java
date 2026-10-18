package com.github.auties00.smalt.image.raw.internal;

/**
 * Holds the Huffman tables of Nikon's NEF compression (TIFF compression 34713), in the form of a
 * JPEG {@code DHT} segment: sixteen code counts by code length, then the values in code order.
 *
 * <p>The low four bits of a value are the difference category, the bit length of the difference
 * that follows the code, as in a JPEG DC table. The high four bits are the number of low bits the
 * stream leaves out of the difference; only the tables used after a lossy file's split row have
 * any. The value {@link #UNDERIVED} marks a code that canonical code order reserves but whose value
 * no sample file has shown; the decoder rejects a stream that uses one.
 *
 * <p>The tables are, in order: 12-bit lossy, 12-bit lossy after the split row, 12-bit lossless,
 * 14-bit lossy, 14-bit lossy after the split row and 14-bit lossless.
 *
 * @implNote This implementation's tables are derived from data: CC0 sample files of the
 * raw.pixls.us archive and the sensor values Adobe DNG Converter (14.0 and 7.4, run as a black box
 * writing uncompressed DNG files) stores for them. With the stored values every photosite's
 * difference is known, so aligning the bit stream on them recovers each code (the raw module's
 * test-scope {@code NikonTableDerivation} re-runs the derivation). Codes no sample uses are
 * {@link #UNDERIVED}: two codes of table 0 and the last code of tables 1, 3 and 4. The categories
 * still missing from those tables are larger than any difference a lossy file's value range has
 * produced in the samples.
 */
final class NikonTables {
    /**
     * The value of a code whose meaning no sample file has shown.
     */
    static final int UNDERIVED = -1;

    /**
     * The tables.
     */
    static final int[][] TABLES = {
            {0, 1, 5, 1, 1, 1, 1, 1, 2, 0, 0, 0, 0, 0, 0, 0,
                    5, 4, 3, 6, 2, 7, 1, 0, 8, 9, UNDERIVED, 10, UNDERIVED},
            {0, 1, 5, 1, 1, 1, 1, 2, 0, 0, 0, 0, 0, 0, 0, 0,
                    0x39, 0x5A, 0x38, 0x27, 0x16, 5, 4, 3, 2, 1, 0, UNDERIVED},
            {0, 1, 4, 2, 3, 1, 2, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                    5, 4, 6, 3, 7, 2, 8, 1, 9, 0, 10, 11, 12},
            {0, 1, 4, 3, 1, 1, 1, 1, 2, 0, 0, 0, 0, 0, 0, 0,
                    5, 6, 4, 7, 8, 3, 9, 2, 1, 0, 10, 11, 12, UNDERIVED},
            {0, 1, 5, 1, 1, 1, 1, 1, 1, 2, 0, 0, 0, 0, 0, 0,
                    8, 0x5C, 0x4B, 0x3A, 0x29, 7, 6, 5, 4, 3, 2, 1, 0, UNDERIVED},
            {0, 1, 4, 2, 2, 3, 1, 2, 0, 0, 0, 0, 0, 0, 0, 0,
                    7, 6, 8, 5, 9, 4, 10, 3, 11, 12, 2, 0, 1, 13, 14}
    };

    /**
     * Prevents instantiation.
     */
    private NikonTables() {
    }
}
