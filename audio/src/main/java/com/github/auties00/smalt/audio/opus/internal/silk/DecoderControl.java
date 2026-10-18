/* Ports libopus 1.5.2 (silk/structs.h). Copyright Skype Limited; BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.audio.opus.internal.silk;

import static com.github.auties00.smalt.audio.opus.internal.silk.Define.LTP_ORDER;
import static com.github.auties00.smalt.audio.opus.internal.silk.Define.MAX_LPC_ORDER;
import static com.github.auties00.smalt.audio.opus.internal.silk.Define.MAX_NB_SUBFR;

/**
 * Holds the dequantized parameters of one decoded SILK frame.
 *
 * @implNote This implementation keeps the libopus field names.
 */
public final class DecoderControl {
    /**
     * The pitch lag of each subframe.
     */
    public final int[] pitchL = new int[MAX_NB_SUBFR];

    /**
     * The gain of each subframe, in Q16.
     */
    public final int[] Gains_Q16 = new int[MAX_NB_SUBFR];

    /**
     * The LPC coefficients in Q12: the interpolated set for the first half of the frame and the
     * final set for the second half.
     */
    public final short[][] PredCoef_Q12 = new short[2][MAX_LPC_ORDER];

    /**
     * The LTP coefficients of every subframe, in Q14.
     */
    public final short[] LTPCoef_Q14 = new short[LTP_ORDER * MAX_NB_SUBFR];

    /**
     * The LTP scaling, in Q14.
     */
    public int LTP_scale_Q14;

    /**
     * Creates zeroed parameters.
     */
    public DecoderControl() {
    }
}
