package com.github.auties00.smalt.image.raw.internal;

/**
 * Holds the Huffman tables of Canon's CRW compression, in the form of a JPEG {@code DHT} segment:
 * sixteen code counts by code length, then the values in code order.
 *
 * <p>Each of the three table sets a CRW file's decoder table selects has a DC table, whose values
 * are difference categories, and an AC table, whose values hold a run of zeros in their high four
 * bits and a category in their low four ({@code 0x00} ends a block, {@code 0xF0} is a run of 16
 * zeros); set {@code s} is at indices {@code 2s} and {@code 2s + 1}. The value {@link #UNDERIVED}
 * marks a code that canonical code order reserves but whose value no sample file has shown; the
 * decoder rejects a stream that uses one.
 *
 * @implNote This implementation's tables are derived from data: CC0 sample files of the
 * raw.pixls.us archive (set 0: EOS D60, 10D, 300D, Digital Rebel and PowerShot G6; set 1: EOS
 * D30, PowerShot G1 and G5; set 2: EOS 10D and 300D) and the sensor values Adobe DNG Converter 18.0,
 * run as a black box writing uncompressed DNG files, stores for them. With the stored values every
 * block's values are known, so aligning the bit stream on them recovers each code (the raw module's
 * test-scope {@code CrwTableDerivation} re-runs the derivation). The AC codes no sample uses are
 * {@link #UNDERIVED}: 36 of set 0, 113 of set 1 and 3 of set 2; each DC table lacks its last code.
 */
final class CrwTables {
    /**
     * The value of a code whose meaning no sample file has shown.
     */
    static final int UNDERIVED = -1;

    /**
     * The number of table sets.
     */
    static final int SETS = 3;

    /**
     * The tables.
     */
    static final int[][] TABLES = {
            {0, 1, 4, 2, 3, 2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                    4, 3, 5, 6, 2, 7, 1, 8, 9, 0, 10, UNDERIVED},
            {0, 2, 2, 2, 1, 4, 2, 1, 2, 5, 1, 1, 0, 0, 0, 144,
                    3, 4, 2, 5, 1, 6, 7, 8, 0x12, 0x13, 0x11, 0x14, 9, 0x15, 0x22, 0, 0x21, 0x16, 10, 0xF0, 0x23,
                    0x17, 0x24, 0x31, 0x32, 0x18, 0x19, 0x33, 0x25, 0x41, 0x34, 0x42, 0x35, 0x51, 0x36, 0x37, 0x38,
                    0x29, 0x79, 0x26, 0x1A, 0x39, 0x56, 0x57, 0x28, 0x27, 0x52, 0x55, 0x58, 0x43, 0x76, 0x59, 0x77,
                    0x54, 0x61, 0xF9, 0x71, UNDERIVED, 0x75, 0x96, 0x97, 0x49, UNDERIVED, 0x53, 0xD7, 0x74, 0xB6,
                    0x98, 0x47, 0x48, 0x95, UNDERIVED, UNDERIVED, 0x91, 0xFA, UNDERIVED, 0x68, 0xB5, 0xB9, 0xD6,
                    0xF7, 0xD8, 0x67, 0x46, 0x45, 0x94, 0x89, UNDERIVED, 0x81, UNDERIVED, 0xF6, 0xB4, 0x88, 0xB1,
                    0x2A, 0x44, 0x72, 0xD9, 0x87, 0x66, UNDERIVED, 0xF5, UNDERIVED, 0xA7, 0x73, 0xA9, UNDERIVED,
                    0x86, 0x62, 0xC7, 0x65, UNDERIVED, UNDERIVED, 0xA1, UNDERIVED, 0xD1, 0xE9, UNDERIVED, 0x92, 0x85,
                    0xA6, UNDERIVED, 0x93, 0xE8, 0xC1, 0xC6, UNDERIVED, 0x64, 0xE1, UNDERIVED, UNDERIVED, 0xE6, 0xB3,
                    0xF1, UNDERIVED, 0xA5, UNDERIVED, 0xB2, UNDERIVED, UNDERIVED, 0x84, 0xA4, 0x63, 0xE5, 0xC5, 0xF3,
                    0xD2, 0xC4, 0x82, UNDERIVED, UNDERIVED, UNDERIVED, UNDERIVED, UNDERIVED, 0x83, UNDERIVED, 0xA2,
                    UNDERIVED, UNDERIVED, 0xC2, 0xE2, UNDERIVED, UNDERIVED, UNDERIVED, UNDERIVED, UNDERIVED, UNDERIVED},
            {0, 2, 2, 3, 1, 1, 1, 2, 0, 0, 0, 0, 0, 0, 0, 0,
                    3, 2, 4, 1, 5, 0, 6, 7, 9, 8, 10, UNDERIVED},
            {0, 2, 2, 1, 4, 1, 4, 1, 3, 3, 1, 0, 0, 0, 0, 160,
                    2, 3, 1, 4, 5, 0x12, 0x11, 6, 0x13, 7, 8, 0x14, 0x22, 9, 0x21, 0, 0x23, 0x15, 0x31, 0x32, 10,
                    0x16, 0xF0, 0x24, 0x33, 0x41, 0x42, 0x19, 0x17, 0x25, 0x18, 0x51, 0x34, 0x43, 0x52, 0x29, 0x35,
                    0x61, 0x39, 0x71, 0x62, UNDERIVED, 0x53, 0x26, UNDERIVED, UNDERIVED, UNDERIVED, 0x81, 0x27, 0x91,
                    UNDERIVED, 0x55, 0x45, 0x28, 0x72, UNDERIVED, 0xA1, 0xB1, 0x44, 0x69, 0x54, 0x58, 0xD1,
                    UNDERIVED, UNDERIVED, 0xE1, 0xF1, UNDERIVED, 0x49, 0x47, 0x63, UNDERIVED, 0xF9, UNDERIVED,
                    UNDERIVED, UNDERIVED, UNDERIVED, UNDERIVED, UNDERIVED, UNDERIVED, UNDERIVED, UNDERIVED,
                    UNDERIVED, UNDERIVED, UNDERIVED, 0xC1, UNDERIVED, UNDERIVED, UNDERIVED, UNDERIVED, UNDERIVED,
                    UNDERIVED, UNDERIVED, UNDERIVED, UNDERIVED, UNDERIVED, UNDERIVED, UNDERIVED, UNDERIVED,
                    UNDERIVED, UNDERIVED, UNDERIVED, UNDERIVED, UNDERIVED, 0xD9, UNDERIVED, UNDERIVED, UNDERIVED,
                    0x48, 0x82, UNDERIVED, UNDERIVED, UNDERIVED, UNDERIVED, UNDERIVED, UNDERIVED, UNDERIVED,
                    UNDERIVED, UNDERIVED, UNDERIVED, UNDERIVED, UNDERIVED, UNDERIVED, UNDERIVED, UNDERIVED,
                    UNDERIVED, UNDERIVED, 0xE9, UNDERIVED, UNDERIVED, UNDERIVED, UNDERIVED, UNDERIVED, UNDERIVED,
                    UNDERIVED, UNDERIVED, UNDERIVED, UNDERIVED, UNDERIVED, UNDERIVED, UNDERIVED, UNDERIVED,
                    UNDERIVED, UNDERIVED, UNDERIVED, 0x64, UNDERIVED, UNDERIVED, UNDERIVED, UNDERIVED, UNDERIVED,
                    UNDERIVED, UNDERIVED, UNDERIVED, UNDERIVED, UNDERIVED, UNDERIVED, UNDERIVED, UNDERIVED,
                    UNDERIVED, UNDERIVED, UNDERIVED, UNDERIVED, UNDERIVED, UNDERIVED, UNDERIVED, UNDERIVED,
                    UNDERIVED, UNDERIVED, UNDERIVED, UNDERIVED, UNDERIVED, UNDERIVED, UNDERIVED, UNDERIVED,
                    UNDERIVED, UNDERIVED, UNDERIVED, UNDERIVED, UNDERIVED, UNDERIVED, UNDERIVED},
            {0, 0, 6, 3, 1, 1, 2, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                    6, 5, 7, 4, 8, 3, 9, 2, 0, 10, 1, 11, UNDERIVED},
            {0, 0, 6, 2, 1, 3, 3, 2, 5, 1, 2, 2, 8, 10, 0, 120,
                    4, 5, 3, 6, 2, 7, 1, 8, 9, 0x12, 0x13, 0x14, 0x11, 0x15, 10, 0x16, 0x17, 0xF0, 0, 0x22, 0x21,
                    0x18, 0x23, 0x19, 0x24, 0x32, 0x31, 0x25, 0x33, 0x38, 0x37, 0x34, 0x35, 0x36, 0x39, 0x79, 0x57,
                    0x58, 0x59, 0x28, 0x56, 0x78, 0x27, 0x41, 0x29, 0x77, 0x26, 0x42, 0x76, 0x99, 0x1A, 0x55, 0x98,
                    0x97, 0xF9, 0x48, 0x54, 0x96, 0x89, 0x47, 0xB7, 0x49, 0xFA, 0x75, 0x68, 0xB6, 0x67, 0x69, 0xB9,
                    0xB8, 0xD8, 0x52, 0xD7, 0x88, 0xB5, 0x74, 0x51, 0x46, 0xD9, 0xF8, 0x3A, 0xD6, 0x87, 0x45, 0x7A,
                    0x95, 0xD5, 0xF6, 0x86, 0xB4, 0xA9, 0x94, 0x53, 0x2A, 0xA8, 0x43, 0xF5, 0xF7, 0xD4, 0x66, 0xA7,
                    0x5A, 0x44, 0x8A, 0xC9, 0xE8, 0xC8, 0xE7, 0x9A, 0x6A, 0x73, 0x4A, 0x61, 0xC7, 0xF4, 0xC6, 0x65,
                    0xE9, 0x72, 0xE6, 0x71, 0x91, 0x93, 0xA6, 0xDA, 0x92, 0x85, 0x62, 0xF3, 0xC5, 0xB2, 0xA4, 0x84,
                    0xBA, 0x64, 0xA5, 0xB3, 0xD2, 0x81, 0xE5, 0xD3, 0xAA, 0xC4, 0xCA, 0xF2, 0xB1, 0xE4, 0xD1, 0x83,
                    0x63, 0xEA, 0xC3, 0xE2, 0x82, 0xF1, 0xA3, 0xC2, 0xA1, 0xC1, 0xE3, 0xA2, 0xE1, UNDERIVED,
                    UNDERIVED, UNDERIVED}
    };

    /**
     * Prevents instantiation.
     */
    private CrwTables() {
    }
}
