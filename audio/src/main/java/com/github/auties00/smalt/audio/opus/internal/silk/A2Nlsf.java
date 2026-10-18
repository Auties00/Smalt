/* Ports libopus 1.5.2 (silk/A2NLSF.c). Copyright Skype Limited; BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.audio.opus.internal.silk;

import static com.github.auties00.smalt.audio.opus.internal.silk.Define.*;
import static com.github.auties00.smalt.audio.opus.internal.silk.SigProcFix.*;

/**
 * Converts monic whitening filter coefficients to normalized line spectral frequencies by
 * locating the roots of the symmetric and antisymmetric polynomials on a piecewise-linear
 * cosine grid.
 *
 * <p>The conversion is the exact inverse of the shared {@code NLSF2A} approximation, and applies
 * progressively stronger bandwidth expansion when not every root is found.
 */
final class A2Nlsf {
    /**
     * The number of binary divisions of each root interval ({@code BIN_DIV_STEPS_A2NLSF_FIX}).
     */
    private static final int BIN_DIV_STEPS_A2NLSF_FIX = 3;

    /**
     * The number of bandwidth expansions tried before giving up ({@code MAX_ITERATIONS_A2NLSF_FIX}).
     */
    private static final int MAX_ITERATIONS_A2NLSF_FIX = 16;

    private A2Nlsf() {
        throw new AssertionError();
    }

    /**
     * Transforms a polynomial from the {@code cos(n f)} basis to the {@code cos(f)^n} basis
     * ({@code silk_A2NLSF_trans_poly}).
     *
     * @param p  the polynomial, transformed in place
     * @param dd the polynomial order
     */
    private static void transPoly(int[] p, int dd) {
        int k, n;
        for (k = 2; k <= dd; k++) {
            for (n = dd; n > k; n--) {
                p[n - 2] -= p[n];
            }
            p[k - 2] -= p[k] << 1;
        }
    }

    /**
     * Evaluates a polynomial ({@code silk_A2NLSF_eval_poly}).
     *
     * @param p  the polynomial in Q16
     * @param x  the evaluation point in Q12
     * @param dd the order
     * @return the value in Q16
     */
    private static int evalPoly(int[] p, int x, int dd) {
        int n;
        int x_Q16, y32;
        y32 = p[dd];
        x_Q16 = x << 4;
        if (8 == dd) {
            y32 = smlaww(p[7], y32, x_Q16);
            y32 = smlaww(p[6], y32, x_Q16);
            y32 = smlaww(p[5], y32, x_Q16);
            y32 = smlaww(p[4], y32, x_Q16);
            y32 = smlaww(p[3], y32, x_Q16);
            y32 = smlaww(p[2], y32, x_Q16);
            y32 = smlaww(p[1], y32, x_Q16);
            y32 = smlaww(p[0], y32, x_Q16);
        } else {
            for (n = dd - 1; n >= 0; n--) {
                y32 = smlaww(p[n], y32, x_Q16);
            }
        }
        return y32;
    }

    /**
     * Builds the even and odd polynomials of a filter and transforms them to the cosine basis
     * ({@code silk_A2NLSF_init}).
     *
     * @param a_Q16 the filter coefficients in Q16
     * @param P     receives the even polynomial
     * @param Q     receives the odd polynomial
     * @param dd    half the filter order
     */
    private static void init(int[] a_Q16, int[] P, int[] Q, int dd) {
        int k;
        P[dd] = 1 << 16;
        Q[dd] = 1 << 16;
        for (k = 0; k < dd; k++) {
            P[k] = -a_Q16[dd - k - 1] - a_Q16[dd + k];
            Q[k] = -a_Q16[dd - k - 1] + a_Q16[dd + k];
        }
        for (k = dd; k > 0; k--) {
            P[k - 1] -= P[k];
            Q[k - 1] += Q[k];
        }
        transPoly(P, dd);
        transPoly(Q, dd);
    }

    /**
     * Computes the NLSFs of a whitening filter ({@code silk_A2NLSF}).
     *
     * @param NLSF  receives {@code d} NLSFs in Q15
     * @param a_Q16 the monic whitening filter coefficients in Q16; bandwidth expanded in place
     *              when the roots cannot all be found
     * @param d     the filter order, even
     */
    static void a2nlsf(short[] NLSF, int[] a_Q16, int d) {
        a2nlsf(NLSF, a_Q16, d, new int[SILK_MAX_ORDER_LPC / 2 + 1], new int[SILK_MAX_ORDER_LPC / 2 + 1]);
    }

    /**
     * Computes the NLSFs of a whitening filter using caller-provided work arrays
     * ({@code silk_A2NLSF}).
     *
     * @param NLSF  receives {@code d} NLSFs in Q15
     * @param a_Q16 the monic whitening filter coefficients in Q16; bandwidth expanded in place
     *              when the roots cannot all be found
     * @param d     the filter order, even
     * @param P     a work array of {@code SILK_MAX_ORDER_LPC / 2 + 1} elements, overwritten
     * @param Q     a work array of {@code SILK_MAX_ORDER_LPC / 2 + 1} elements, overwritten
     */
    static void a2nlsf(short[] NLSF, int[] a_Q16, int d, int[] P, int[] Q) {
        int i, k, m, dd, root_ix, ffrac;
        int xlo, xhi, xmid;
        int ylo, yhi, ymid, thr;
        int nom, den;
        int[] p;

        dd = d >> 1;

        init(a_Q16, P, Q, dd);

        p = P;

        xlo = Tables.silk_LSFCosTab_FIX_Q12[0];
        ylo = evalPoly(p, xlo, dd);

        if (ylo < 0) {
            NLSF[0] = 0;
            p = Q;
            ylo = evalPoly(p, xlo, dd);
            root_ix = 1;
        } else {
            root_ix = 0;
        }
        k = 1;
        i = 0;
        thr = 0;
        while (true) {
            xhi = Tables.silk_LSFCosTab_FIX_Q12[k];
            yhi = evalPoly(p, xhi, dd);

            if ((ylo <= 0 && yhi >= thr) || (ylo >= 0 && yhi <= -thr)) {
                if (yhi == 0) {
                    thr = 1;
                } else {
                    thr = 0;
                }
                ffrac = -256;
                for (m = 0; m < BIN_DIV_STEPS_A2NLSF_FIX; m++) {
                    xmid = rshiftRound(xlo + xhi, 1);
                    ymid = evalPoly(p, xmid, dd);

                    if ((ylo <= 0 && ymid >= 0) || (ylo >= 0 && ymid <= 0)) {
                        xhi = xmid;
                        yhi = ymid;
                    } else {
                        xlo = xmid;
                        ylo = ymid;
                        ffrac = ffrac + (128 >> m);
                    }
                }

                if (Math.abs(ylo) < 65536) {
                    den = ylo - yhi;
                    nom = (ylo << (8 - BIN_DIV_STEPS_A2NLSF_FIX)) + (den >> 1);
                    if (den != 0) {
                        ffrac += nom / den;
                    }
                } else {
                    ffrac += ylo / ((ylo - yhi) >> (8 - BIN_DIV_STEPS_A2NLSF_FIX));
                }
                NLSF[root_ix] = (short) Math.min((k << 8) + ffrac, Short.MAX_VALUE);

                root_ix++;
                if (root_ix >= d) {
                    break;
                }

                p = (root_ix & 1) == 0 ? P : Q;

                xlo = Tables.silk_LSFCosTab_FIX_Q12[k - 1];
                ylo = (1 - (root_ix & 2)) << 12;
            } else {
                k++;
                xlo = xhi;
                ylo = yhi;
                thr = 0;

                if (k > LSF_COS_TAB_SZ_FIX) {
                    i++;
                    if (i > MAX_ITERATIONS_A2NLSF_FIX) {
                        NLSF[0] = (short) ((1 << 15) / (d + 1));
                        for (k = 1; k < d; k++) {
                            NLSF[k] = (short) (NLSF[k - 1] + NLSF[0]);
                        }
                        return;
                    }

                    Bwexpander.bwexpander32(a_Q16, d, 65536 - (1 << i));

                    init(a_Q16, P, Q, dd);
                    p = P;
                    xlo = Tables.silk_LSFCosTab_FIX_Q12[0];
                    ylo = evalPoly(p, xlo, dd);
                    if (ylo < 0) {
                        NLSF[0] = 0;
                        p = Q;
                        ylo = evalPoly(p, xlo, dd);
                        root_ix = 1;
                    } else {
                        root_ix = 0;
                    }
                    k = 1;
                }
            }
        }
    }
}
