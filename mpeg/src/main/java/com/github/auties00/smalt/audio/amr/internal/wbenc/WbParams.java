/* Ports vo-amrwbenc 0.1.3 (bits.c). Copyright VisualOn; Apache License 2.0. Modified for Smalt. */
package com.github.auties00.smalt.audio.amr.internal.wbenc;

import com.github.auties00.smalt.audio.amr.internal.wbcommon.WbBitOrder;

/**
 * Collects the bits of an AMR-WB frame in parameter order (the order of TS 26.201 table A.1
 * through A.10), one 16-bit word per bit, as the reference encoder's serial format stores them.
 *
 * <p>A bit is stored as {@link WbBitOrder#BIT_1} or {@link WbBitOrder#BIT_0}. An instance is reused for every frame;
 * it is not thread-safe.
 */
final class WbParams {
    /**
     * The largest number of bits of a frame (the 23.85 kbit/s mode).
     */
    static final int NB_BITS_MAX = 477;

    /**
     * The bits written so far.
     */
    final short[] bits = new short[NB_BITS_MAX];

    /**
     * The number of bits written so far.
     */
    private int position;

    /**
     * Creates an empty collector.
     */
    WbParams() {
    }

    /**
     * Starts a new frame.
     */
    void rewind() {
        position = 0;
    }

    /**
     * Appends a parameter, most significant bit first ({@code Parm_serial}).
     *
     * @param value the parameter
     * @param count the number of bits
     */
    void put(int value, int count) {
        var v = (short) value;
        position += count;
        for (var i = 0; i < count; i++) {
            bits[--position] = (v & 1) == 0 ? WbBitOrder.BIT_0 : WbBitOrder.BIT_1;
            v = (short) (v >> 1);
        }
        position += count;
    }
}
