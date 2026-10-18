/* Ports libopus 1.5.2 (silk/interpolate.c). Copyright Skype Limited; BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.audio.opus.internal.silk;

import static com.github.auties00.smalt.audio.opus.internal.silk.SigProcFix.smulbb;

/**
 * Interpolates between two parameter vectors.
 */
public final class Interpolate {
    private Interpolate() {
        throw new AssertionError();
    }

    /**
     * Interpolates {@code x0 + (x1 - x0) * ifact_Q2 / 4} ({@code silk_interpolate}).
     *
     * @param xi       receives the interpolated vector
     * @param x0       the first vector
     * @param x1       the second vector
     * @param ifact_Q2 the weight of the second vector in Q2, in {@code [0, 4]}
     * @param d        the number of parameters
     */
    public static void interpolate(short[] xi, short[] x0, short[] x1, int ifact_Q2, int d) {
        for (var i = 0; i < d; i++) {
            xi[i] = (short) (x0[i] + (smulbb(x1[i] - x0[i], ifact_Q2) >> 2));
        }
    }
}
