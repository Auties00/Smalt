package com.github.auties00.smalt.image.internal.xz;

/**
 * Computes the CRC64 of the XZ format: the ECMA-182 polynomial, reflected, with an initial value and
 * final XOR of all ones.
 */
final class Crc64 {
    /** The table of the reflected polynomial {@code 0xC96C5795D7870F42}. */
    private static final long[] TABLE = new long[256];

    static {
        for (var n = 0; n < 256; n++) {
            long crc = n;
            for (var k = 0; k < 8; k++) {
                crc = (crc & 1) != 0 ? (crc >>> 1) ^ 0xC96C5795D7870F42L : crc >>> 1;
            }
            TABLE[n] = crc;
        }
    }

    private Crc64() {
        throw new AssertionError();
    }

    /**
     * Computes the CRC64 of bytes.
     *
     * @param data the bytes
     * @param offset the first byte
     * @param length the number of bytes
     * @return the CRC64
     */
    static long compute(byte[] data, int offset, int length) {
        var crc = -1L;
        for (var i = offset; i < offset + length; i++) {
            crc = TABLE[(int) ((crc ^ data[i]) & 0xFF)] ^ (crc >>> 8);
        }
        return ~crc;
    }
}
