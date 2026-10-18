package com.github.auties00.smalt.audio.ac3.internal;

/**
 * Computes the CRC-16 of AC-3 and E-AC-3 sync frames (ATSC A/52 section 7.10.1), generator
 * polynomial {@code x^16 + x^15 + x^2 + 1}, register reset to zero, no final inversion.
 *
 * <p>A region whose last two bytes hold the CRC of the bytes before them checks to zero. The
 * encoder also needs {@link #prefix(int, int)}: the value of a CRC word placed at the start of a
 * region that makes the whole region check to zero, which is how {@code crc1} is defined.
 *
 * @implNote This implementation shifts eight bytes per step with eight tables (slicing by eight):
 * the register after eight bytes is the sum of the contributions of each byte, the byte followed by
 * seven, six, five, four, three, two, one and no zero bytes, with the register folded into the first
 * two bytes.
 */
public final class Ac3Crc {
    /**
     * The generator polynomial without its {@code x^16} term.
     */
    private static final int POLYNOMIAL = 0x8005;

    /**
     * The register update for every byte value.
     */
    private static final int[] TABLE = table();

    /**
     * The register after each byte value followed by one zero byte.
     */
    private static final int[] TABLE_1 = shifted(TABLE);

    /**
     * The register after each byte value followed by two zero bytes.
     */
    private static final int[] TABLE_2 = shifted(TABLE_1);

    /**
     * The register after each byte value followed by three zero bytes.
     */
    private static final int[] TABLE_3 = shifted(TABLE_2);

    /**
     * The register after each byte value followed by four zero bytes.
     */
    private static final int[] TABLE_4 = shifted(TABLE_3);

    /**
     * The register after each byte value followed by five zero bytes.
     */
    private static final int[] TABLE_5 = shifted(TABLE_4);

    /**
     * The register after each byte value followed by six zero bytes.
     */
    private static final int[] TABLE_6 = shifted(TABLE_5);

    /**
     * The register after each byte value followed by seven zero bytes.
     */
    private static final int[] TABLE_7 = shifted(TABLE_6);

    /**
     * Prevents instantiation.
     */
    private Ac3Crc() {
        throw new AssertionError();
    }

    /**
     * Builds the byte-wise update table.
     *
     * @return the table
     */
    private static int[] table() {
        var table = new int[256];
        for (var value = 0; value < 256; value++) {
            var crc = value << 8;
            for (var bit = 0; bit < 8; bit++) {
                crc = (crc & 0x8000) != 0 ? (crc << 1) ^ POLYNOMIAL : crc << 1;
            }
            table[value] = crc & 0xFFFF;
        }
        return table;
    }

    /**
     * Shifts every entry of a table by one zero byte.
     *
     * @param table the registers after each byte value and some zero bytes
     * @return the registers after one more zero byte
     */
    private static int[] shifted(int[] table) {
        var next = new int[256];
        for (var value = 0; value < 256; value++) {
            var crc = table[value];
            next[value] = ((crc << 8) ^ TABLE[crc >>> 8]) & 0xFFFF;
        }
        return next;
    }

    /**
     * Computes the CRC of a byte range.
     *
     * @param data   the bytes
     * @param offset the first byte
     * @param length the number of bytes
     * @return the 16-bit register after shifting the range in
     */
    public static int compute(byte[] data, int offset, int length) {
        return update(0, data, offset, length);
    }

    /**
     * Continues a CRC over a byte range.
     *
     * @param crc    the register before the range
     * @param data   the bytes
     * @param offset the first byte
     * @param length the number of bytes
     * @return the 16-bit register after shifting the range in
     */
    public static int update(int crc, byte[] data, int offset, int length) {
        var i = offset;
        var end = offset + length;
        for (; i + 8 <= end; i += 8) {
            crc = TABLE_7[((crc >>> 8) ^ data[i]) & 0xFF] ^ TABLE_6[(crc ^ data[i + 1]) & 0xFF]
                    ^ TABLE_5[data[i + 2] & 0xFF] ^ TABLE_4[data[i + 3] & 0xFF] ^ TABLE_3[data[i + 4] & 0xFF]
                    ^ TABLE_2[data[i + 5] & 0xFF] ^ TABLE_1[data[i + 6] & 0xFF] ^ TABLE[data[i + 7] & 0xFF];
        }
        for (; i + 4 <= end; i += 4) {
            crc = TABLE_3[((crc >>> 8) ^ data[i]) & 0xFF] ^ TABLE_2[(crc ^ data[i + 1]) & 0xFF]
                    ^ TABLE_1[data[i + 2] & 0xFF] ^ TABLE[data[i + 3] & 0xFF];
        }
        for (; i < end; i++) {
            crc = ((crc << 8) ^ TABLE[((crc >>> 8) ^ data[i]) & 0xFF]) & 0xFFFF;
        }
        return crc;
    }

    /**
     * Returns the CRC word to store in the first two bytes of a region so that the region checks
     * to zero.
     *
     * <p>With {@code r} the CRC of the region computed with those two bytes zero, the word is
     * {@code r * x^-(8 * length)} modulo the generator: shifting the word through the rest of the
     * region multiplies it by {@code x^(8 * length)}, which then cancels {@code r}.
     *
     * @param residue the CRC of the region with the word zeroed
     * @param length  the length of the region in bytes, the word included
     * @return the word
     */
    public static int prefix(int residue, int length) {
        // x^-1 = x^15 + x^14 + x, since x * (x^15 + x^14 + x) = 1 modulo the generator
        var factor = power(0xC002, 8L * length);
        return multiply(residue, factor);
    }

    /**
     * Raises a polynomial to a power modulo the generator.
     *
     * @param base     the polynomial
     * @param exponent the power
     * @return {@code base^exponent} modulo the generator
     */
    private static int power(int base, long exponent) {
        var result = 1;
        var square = base;
        while (exponent > 0) {
            if ((exponent & 1) != 0) {
                result = multiply(result, square);
            }
            square = multiply(square, square);
            exponent >>>= 1;
        }
        return result;
    }

    /**
     * Multiplies two polynomials modulo the generator.
     *
     * @param a the first polynomial, of degree below 16
     * @param b the second polynomial, of degree below 16
     * @return the product modulo the generator
     */
    private static int multiply(int a, int b) {
        var result = 0;
        for (var bit = 15; bit >= 0; bit--) {
            result = (result & 0x8000) != 0 ? ((result << 1) ^ POLYNOMIAL) & 0xFFFF : (result << 1) & 0xFFFF;
            if (((b >>> bit) & 1) != 0) {
                result ^= a;
            }
        }
        return result;
    }
}
