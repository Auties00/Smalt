/* Ports libopus 1.5.2 (silk/sigm_Q15.c). Copyright Skype Limited; BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.audio.opus.internal.silk;

import static com.github.auties00.smalt.audio.opus.internal.silk.SigProcFix.smulbb;

/**
 * Approximates the sigmoid function with linear interpolation of small tables.
 */
public final class SigmQ15 {
    /**
     * The slope of each interpolation segment, in Q10.
     */
    private static final int[] sigm_LUT_slope_Q10 = {237, 153, 73, 30, 12, 7};

    /**
     * The sigmoid at the non-negative integers 0 to 5, in Q15.
     */
    private static final int[] sigm_LUT_pos_Q15 = {16384, 23955, 28861, 31213, 32178, 32548};

    /**
     * The sigmoid at the non-positive integers 0 to -5, in Q15.
     */
    private static final int[] sigm_LUT_neg_Q15 = {16384, 8812, 3906, 1554, 589, 219};

    private SigmQ15() {
        throw new AssertionError();
    }

    /**
     * Approximates {@code 1 / (1 + exp(-x))} ({@code silk_sigm_Q15}).
     *
     * @param in_Q5 the argument in Q5
     * @return the sigmoid in Q15, clipped to {@code [0, 32767]}
     */
    public static int sigmQ15(int in_Q5) {
        int ind;
        if (in_Q5 < 0) {
            in_Q5 = -in_Q5;
            if (in_Q5 >= 6 * 32) {
                return 0;
            } else {
                ind = in_Q5 >> 5;
                return sigm_LUT_neg_Q15[ind] - smulbb(sigm_LUT_slope_Q10[ind], in_Q5 & 0x1F);
            }
        } else {
            if (in_Q5 >= 6 * 32) {
                return 32767;
            } else {
                ind = in_Q5 >> 5;
                return sigm_LUT_pos_Q15[ind] + smulbb(sigm_LUT_slope_Q10[ind], in_Q5 & 0x1F);
            }
        }
    }
}
