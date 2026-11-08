/* Ports the check bit order of crc.c of the ISO/IEC 14496-5:2001 MPEG-4 Audio epTool (NTT DoCoMo, 1998); MPEG
 * reference software licence, see THIRD-PARTY-NOTICES. Modified for Smalt. */
package com.github.auties00.smalt.audio.aac.internal.ep;

/**
 * Computes the cyclic redundancy checks of the MPEG-4 audio error protection tool (ISO/IEC 14496-3 subclause
 * 1.8.4.5).
 *
 * <p>A check of {@code k} bits is the remainder of {@code M(x) x^k} divided by the generator polynomial of that
 * length, with the first bit of the protected data as the highest order coefficient of {@code M(x)} and a register
 * that starts at zero. The check bits are transmitted inverted, the lowest order coefficient first. Bits are held one
 * per byte, 0 or 1.
 */
final class EpCrc {
    /**
     * The check lengths the five-bit {@code class_crclen} and {@code header_crclen} fields select.
     */
    private static final int[] LENGTHS = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 24, 32};

    /**
     * The check lengths the three-bit in-band {@code class_crc_count} selects (ISO/IEC 14496-3 Table 1.56).
     */
    private static final int[] IN_BAND_LENGTHS = {0, 6, 8, 10, 12, 14, 16, 32};

    /**
     * Prevents instantiation.
     */
    private EpCrc() {
    }

    /**
     * Returns the check length a {@code class_crclen} or {@code header_crclen} value selects.
     *
     * @param code the five-bit code
     * @return the number of check bits, or -1 for the reserved codes above 18
     */
    static int lengthOf(int code) {
        return code < LENGTHS.length ? LENGTHS[code] : -1;
    }

    /**
     * Returns the check length an in-band {@code class_crc_count} selects.
     *
     * @param code the three-bit code
     * @return the number of check bits
     */
    static int inBandLengthOf(int code) {
        return IN_BAND_LENGTHS[code];
    }

    /**
     * Returns the generator polynomial of a check length, its leading term included.
     *
     * @param length the number of check bits, a length {@link #lengthOf(int)} returns
     * @return the polynomial, bit {@code i} holding the coefficient of {@code x^i}
     */
    private static long generator(int length) {
        return switch (length) {
            case 1 -> 0x3L;
            case 2 -> 0x7L;
            case 3 -> 0xBL;
            case 4 -> 0x1DL;
            case 5 -> 0x35L;
            case 6 -> 0x6FL;
            case 7 -> 0xC5L;
            case 8 -> 0x107L;
            case 9 -> 0x327L;
            case 10 -> 0x633L;
            case 11 -> 0xC1BL;
            case 12 -> 0x180FL;
            case 13 -> 0x30F5L;
            case 14 -> 0x602DL;
            case 15 -> 0xCCC5L;
            case 16 -> 0x11021L;
            case 24 -> 0x1800063L;
            case 32 -> 0x104C11DB7L;
            default -> throw new IllegalArgumentException("no CRC of " + length + " bits");
        };
    }

    /**
     * Computes the remainder of a run of bits.
     *
     * @param bits the bits, one per byte
     * @param offset the index of the first bit
     * @param count the number of bits
     * @param length the number of check bits, positive
     * @return the remainder, bit {@code i} holding the coefficient of {@code x^i}
     */
    static long remainder(byte[] bits, int offset, int count, int length) {
        var polynomial = generator(length);
        var top = 1L << length;
        var register = 0L;
        for (var i = 0; i < count + length; i++) {
            register = (register << 1) | (i < count ? bits[offset + i] : 0);
            if ((register & top) != 0) {
                register ^= polynomial;
            }
        }
        return register;
    }

    /**
     * Writes the transmitted check bits of a run of bits.
     *
     * @param bits the bits, one per byte
     * @param offset the index of the first protected bit
     * @param count the number of protected bits
     * @param length the number of check bits
     * @param out receives the check bits
     * @param outOffset the index of the first check bit
     */
    static void write(byte[] bits, int offset, int count, int length, byte[] out, int outOffset) {
        if (length == 0) {
            return;
        }
        var remainder = remainder(bits, offset, count, length);
        for (var i = 0; i < length; i++) {
            out[outOffset + i] = (byte) (((remainder >>> i) & 1) ^ 1);
        }
    }

    /**
     * Returns whether the check bits that follow a run of bits match it.
     *
     * @param bits the bits, one per byte, the check bits right after the protected ones
     * @param offset the index of the first protected bit
     * @param count the number of protected bits
     * @param length the number of check bits, 0 for an unchecked run
     * @return {@code true} when the check matches or the run carries none
     */
    static boolean matches(byte[] bits, int offset, int count, int length) {
        if (length == 0) {
            return true;
        }
        var remainder = remainder(bits, offset, count, length);
        for (var i = 0; i < length; i++) {
            if (bits[offset + count + i] != (byte) (((remainder >>> i) & 1) ^ 1)) {
                return false;
            }
        }
        return true;
    }
}
