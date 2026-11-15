package com.github.auties00.smalt.audio.dts.internal;

/**
 * The CRC-16 of the DTS headers (ETSI TS 102 114 Annex B): polynomial x^16 + x^12 + x^5 + 1, most significant bit
 * first, initialized to {@code 0xFFFF}.
 */
final class DtsCrc {
    /**
     * The table of the byte-at-a-time update.
     */
    private static final int[] TABLE = table();

    /**
     * Prevents instantiation.
     */
    private DtsCrc() {
    }

    /**
     * {@return the CRC of a run of bytes}
     *
     * @param data the bytes
     * @param offset the index of the first byte
     * @param length the number of bytes
     */
    static int compute(byte[] data, int offset, int length) {
        var crc = 0xFFFF;
        for (var i = offset; i < offset + length; i++) {
            crc = (crc << 8 ^ TABLE[(crc >>> 8 ^ data[i]) & 0xFF]) & 0xFFFF;
        }
        return crc;
    }

    /**
     * {@return whether a run of bytes ending with its stored CRC checks}
     *
     * @param data the bytes
     * @param offset the index of the first byte the CRC covers
     * @param length the number of bytes, the two CRC bytes included
     */
    static boolean check(byte[] data, int offset, int length) {
        return length >= 2 && compute(data, offset, length) == 0;
    }

    /**
     * Builds the CRC lookup table.
     *
     * @return the table
     */
    private static int[] table() {
        var table = new int[256];
        for (var i = 0; i < 256; i++) {
            var crc = i << 8;
            for (var bit = 0; bit < 8; bit++) {
                crc = (crc & 0x8000) != 0 ? crc << 1 ^ 0x1021 : crc << 1;
            }
            table[i] = crc & 0xFFFF;
        }
        return table;
    }
}
