/* Ports libopus 1.5.2 (silk/structs.h). Copyright Skype Limited; BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.audio.opus.internal.silk;

import java.util.Arrays;

/**
 * Holds the state of the stereo decoder: the previous predictors and the filter memories.
 *
 * @implNote This implementation keeps the libopus field names.
 */
public final class StereoDecState {
    /**
     * The previous predictors in Q13.
     */
    public final short[] pred_prev_Q13 = new short[2];

    /**
     * The mid signal memory.
     */
    public final short[] sMid = new short[2];

    /**
     * The side signal memory.
     */
    public final short[] sSide = new short[2];

    /**
     * Creates a zeroed state.
     */
    public StereoDecState() {
    }

    /**
     * Zeroes every field, like {@code silk_memset} of the structure.
     */
    public void clear() {
        Arrays.fill(pred_prev_Q13, (short) 0);
        Arrays.fill(sMid, (short) 0);
        Arrays.fill(sSide, (short) 0);
    }
}
