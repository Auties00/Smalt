/* Ports opencore-amr 0.1.6 (get_amr_wb_bits.cpp). Copyright PacketVideo; Apache License 2.0. Modified for Smalt. */
package com.github.auties00.smalt.audio.amr.internal.wb;

import com.github.auties00.smalt.audio.amr.internal.wbcommon.WbBitOrder;

/**
 * Holds the bits of one AMR-WB frame in parameter order, one 16-bit word per bit, and reads the
 * parameters from them.
 *
 * <p>A word equal to {@link WbBitOrder#BIT_1} is a one, any other word a zero, as in the 3GPP serial
 * format. The buffer is as large as the largest frame, and reads past its end return zeros, so a
 * frame with fewer bits than its mode reads as zero-padded.
 */
final class WbBits {
    /**
     * The largest number of bits of a frame, those of the 23.85 kbit/s mode.
     */
    static final int MAX_BITS = 477;

    /**
     * The bits of the frame.
     */
    final short[] bits = new short[MAX_BITS];

    /**
     * The index of the next bit to read.
     */
    int position;

    /**
     * Creates an empty buffer.
     */
    WbBits() {
    }

    /**
     * Clears the buffer and rewinds it.
     */
    void clear() {
        java.util.Arrays.fill(bits, (short) 0);
        position = 0;
    }

    /**
     * Reads a parameter of several bits, most significant first.
     *
     * @param count the number of bits
     * @return the parameter
     */
    short parm(int count) {
        short value = 0;
        for (var i = 0; i < count; i++) {
            value = (short) ((value << 1) | parm1());
        }
        return value;
    }

    /**
     * Reads one bit.
     *
     * @return the bit
     */
    short parm1() {
        if (position >= MAX_BITS) {
            return 0;
        }
        return (short) (bits[position++] == WbBitOrder.BIT_1 ? 1 : 0);
    }
}
