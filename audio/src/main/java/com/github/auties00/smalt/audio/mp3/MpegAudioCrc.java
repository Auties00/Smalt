package com.github.auties00.smalt.audio.mp3;

import com.github.auties00.smalt.internal.header.Crc16;

/**
 * Computes the CRC-16 of MPEG audio frames (ISO/IEC 11172-3 clause 2.4.3.1): generator polynomial
 * {@code x^16 + x^15 + x^2 + 1}, register preset to all ones, bits fed most significant first, no
 * final inversion.
 *
 * <p>Arguments are not checked; values outside the documented ranges give unspecified results.
 */
final class MpegAudioCrc {
    /**
     * The generator polynomial without its {@code x^16} term.
     */
    private static final int POLYNOMIAL = 0x8005;

    private MpegAudioCrc() {
    }

    /**
     * Feeds the low bits of a value into a CRC register.
     *
     * @param crc the register, 16 bits
     * @param value the bits, the first one fed being bit {@code bits - 1}
     * @param bits the number of bits, from 0 to 32
     * @return the new register
     */
    static int update(int crc, int value, int bits) {
        for (var bit = bits - 1; bit >= 0; bit--) {
            var feedback = (crc >> 15 ^ value >> bit) & 1;
            crc = (crc << 1 & 0xFFFF) ^ (feedback != 0 ? POLYNOMIAL : 0);
        }
        return crc;
    }

    /**
     * Feeds a run of bits of a byte array into a CRC register.
     *
     * @param crc the register, 16 bits
     * @param data the bytes
     * @param offset the index of the byte holding the first bit, which is its most significant one
     * @param bits the number of bits, non-negative; they must lie inside {@code data}
     * @return the new register
     */
    static int update(int crc, byte[] data, int offset, int bits) {
        var whole = bits >> 3;
        crc = Crc16.crc16(crc, data, offset, whole);
        var rest = bits & 7;
        return rest == 0 ? crc : update(crc, (data[offset + whole] & 0xFF) >> (8 - rest), rest);
    }

    /**
     * Returns the CRC-16 of a frame: the last 16 bits of its header followed by the protected bits
     * after the CRC word.
     *
     * @param data the bytes holding the frame
     * @param frame the index of the frame header
     * @param bits the number of protected bits after the CRC word, which must lie inside {@code data}
     * @return the CRC, 16 bits
     */
    static int frame(byte[] data, int frame, int bits) {
        var crc = update(0xFFFF, data, frame + 2, 16);
        return update(crc, data, frame + 6, bits);
    }
}
