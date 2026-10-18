/* Ports libopus 1.5.2 (silk/NLSF2A.c). Copyright Skype Limited; BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.audio.opus.internal.silk;

import static com.github.auties00.smalt.audio.opus.internal.silk.Define.MAX_LPC_STABILIZE_ITERATIONS;
import static com.github.auties00.smalt.audio.opus.internal.silk.Define.SILK_MAX_ORDER_LPC;
import static com.github.auties00.smalt.audio.opus.internal.silk.SigProcFix.rshiftRound;
import static com.github.auties00.smalt.audio.opus.internal.silk.SigProcFix.rshiftRound64;

/**
 * Converts normalized line spectral frequencies to LPC whitening filter coefficients.
 *
 * <p>A piecewise linear approximation maps each LSF to its cosine, so the result is not the
 * exact LPC filter of the LSFs, but this conversion and {@code silk_A2NLSF} are accurate inverses
 * of each other.
 */
public final class Nlsf2A {
    /**
     * The Q-domain of the intermediate polynomials.
     */
    private static final int QA = 16;

    /**
     * The order of the cosines of a 16th-order filter, found to maximize numerical accuracy.
     */
    private static final byte[] ORDERING16 = {0, 15, 8, 7, 4, 11, 12, 3, 2, 13, 10, 5, 6, 9, 14, 1};

    /**
     * The order of the cosines of a 10th-order filter, found to maximize numerical accuracy.
     */
    private static final byte[] ORDERING10 = {0, 9, 6, 3, 4, 5, 8, 1, 2, 7};

    private Nlsf2A() {
        throw new AssertionError();
    }

    /**
     * Computes the coefficients of one of the two symmetric polynomials
     * ({@code silk_NLSF2A_find_poly}).
     *
     * @param out     receives {@code dd + 1} coefficients in QA
     * @param cLSF    the interleaved {@code 2 * cos(LSF)} values in QA
     * @param cLSFOff the index of the first value used
     * @param dd      the polynomial order, half the filter order
     */
    private static void findPoly(int[] out, int[] cLSF, int cLSFOff, int dd) {
        out[0] = 1 << QA;
        out[1] = -cLSF[cLSFOff];
        for (var k = 1; k < dd; k++) {
            var ftmp = cLSF[cLSFOff + 2 * k];
            out[k + 1] = (out[k - 1] << 1) - (int) rshiftRound64((long) ftmp * out[k], QA);
            for (var n = k; n > 1; n--) {
                out[n] += out[n - 2] - (int) rshiftRound64((long) ftmp * out[n - 1], QA);
            }
            out[1] -= ftmp;
        }
    }

    /**
     * Computes monic whitening filter coefficients from normalized line spectral frequencies
     * ({@code silk_NLSF2A}).
     *
     * <p>The coefficients are bandwidth-expanded until the filter is stable, for at most
     * {@link Define#MAX_LPC_STABILIZE_ITERATIONS} iterations.
     *
     * @param a_Q12 receives {@code d} coefficients in Q12
     * @param NLSF  the normalized LSFs in Q15, each in {@code [0, 32767]}
     * @param d     the filter order, 10 or 16
     */
    public static void nlsf2a(short[] a_Q12, short[] NLSF, int d) {
        nlsf2a(a_Q12, NLSF, d, new Scratch());
    }

    /**
     * Holds the work arrays of {@link #nlsf2a(short[], short[], int, Scratch)} so repeated
     * conversions allocate nothing.
     */
    public static final class Scratch {
        /**
         * The interleaved {@code 2 * cos(LSF)} values in QA.
         */
        final int[] cos_LSF_QA = new int[SILK_MAX_ORDER_LPC];

        /**
         * The even polynomial.
         */
        final int[] P = new int[SILK_MAX_ORDER_LPC / 2 + 1];

        /**
         * The odd polynomial.
         */
        final int[] Q = new int[SILK_MAX_ORDER_LPC / 2 + 1];

        /**
         * The 32-bit filter coefficients in QA + 1.
         */
        final int[] a32_QA1 = new int[SILK_MAX_ORDER_LPC];

        /**
         * The work array of the stability test.
         */
        final int[] invGain = new int[SILK_MAX_ORDER_LPC];

        /**
         * Creates the work arrays.
         */
        public Scratch() {
        }
    }

    /**
     * Computes monic whitening filter coefficients from normalized line spectral frequencies
     * using caller-provided work arrays ({@code silk_NLSF2A}).
     *
     * @param a_Q12   receives {@code d} coefficients in Q12
     * @param NLSF    the normalized LSFs in Q15, each in {@code [0, 32767]}
     * @param d       the filter order, 10 or 16
     * @param scratch the work arrays, overwritten
     */
    public static void nlsf2a(short[] a_Q12, short[] NLSF, int d, Scratch scratch) {
        var cos_LSF_QA = scratch.cos_LSF_QA;
        var P = scratch.P;
        var Q = scratch.Q;
        var a32_QA1 = scratch.a32_QA1;
        var ordering = d == 16 ? ORDERING16 : ORDERING10;
        for (var k = 0; k < d; k++) {
            var f_int = NLSF[k] >> (15 - 7);
            var f_frac = NLSF[k] - (f_int << (15 - 7));
            var cos_val = (int) Tables.silk_LSFCosTab_FIX_Q12[f_int];
            var delta = Tables.silk_LSFCosTab_FIX_Q12[f_int + 1] - cos_val;
            cos_LSF_QA[ordering[k]] = rshiftRound((cos_val << 8) + delta * f_frac, 20 - QA);
        }
        var dd = d >> 1;
        findPoly(P, cos_LSF_QA, 0, dd);
        findPoly(Q, cos_LSF_QA, 1, dd);
        for (var k = 0; k < dd; k++) {
            var Ptmp = P[k + 1] + P[k];
            var Qtmp = Q[k + 1] - Q[k];
            a32_QA1[k] = -Qtmp - Ptmp;
            a32_QA1[d - k - 1] = Qtmp - Ptmp;
        }
        LpcFit.lpcFit(a_Q12, a32_QA1, 12, QA + 1, d);
        for (var i = 0; LpcInvPredGain.lpcInversePredGain(a_Q12, 0, d, scratch.invGain) == 0 && i < MAX_LPC_STABILIZE_ITERATIONS; i++) {
            Bwexpander.bwexpander32(a32_QA1, d, 65536 - (2 << i));
            for (var k = 0; k < d; k++) {
                a_Q12[k] = (short) rshiftRound(a32_QA1[k], QA + 1 - 12);
            }
        }
    }
}
