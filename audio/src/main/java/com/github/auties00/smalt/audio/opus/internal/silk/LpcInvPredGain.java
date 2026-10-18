/* Ports libopus 1.5.2 (silk/LPC_inv_pred_gain.c). Copyright Skype Limited; BSD 3-Clause License. Modified for Smalt.
   */
package com.github.auties00.smalt.audio.opus.internal.silk;

import static com.github.auties00.smalt.audio.opus.internal.silk.Define.MAX_PREDICTION_POWER_GAIN;
import static com.github.auties00.smalt.audio.opus.internal.silk.Define.SILK_MAX_ORDER_LPC;
import static com.github.auties00.smalt.audio.opus.internal.silk.SigProcFix.fixConst;
import static com.github.auties00.smalt.audio.opus.internal.silk.SigProcFix.inverse32VarQ;
import static com.github.auties00.smalt.audio.opus.internal.silk.SigProcFix.rshiftRound64;
import static com.github.auties00.smalt.audio.opus.internal.silk.SigProcFix.smmul;
import static com.github.auties00.smalt.audio.opus.internal.silk.SigProcFix.subSat32;

/**
 * Computes the inverse prediction gain of an LPC filter and tests its stability.
 */
public final class LpcInvPredGain {
    /**
     * The Q-domain of the internal coefficients.
     */
    private static final int QA = 24;

    /**
     * The largest reflection coefficient magnitude considered stable, in QA.
     */
    private static final int A_LIMIT = fixConst(0.99975, QA);

    /**
     * The smallest accepted inverse prediction gain, in Q30.
     */
    private static final int MIN_INV_GAIN_Q30 = fixConst(1.0f / MAX_PREDICTION_POWER_GAIN, 30);

    private LpcInvPredGain() {
        throw new AssertionError();
    }

    /**
     * Returns {@code (a32 * b32) >> q} rounded ({@code MUL32_FRAC_Q}).
     *
     * @param a32 the multiplicand
     * @param b32 the multiplier
     * @param q   the shift
     * @return the rounded product
     */
    private static int mul32FracQ(int a32, int b32, int q) {
        return (int) rshiftRound64((long) a32 * b32, q);
    }

    /**
     * Computes the inverse prediction gain of coefficients in QA, destroying them
     * ({@code LPC_inverse_pred_gain_QA_c}).
     *
     * @param A_QA  the prediction coefficients in QA; overwritten
     * @param order the prediction order
     * @return the inverse prediction gain in Q30, or zero when the filter is unstable
     */
    private static int lpcInversePredGainQA(int[] A_QA, int order) {
        var invGain_Q30 = 1 << 30;
        int k;
        for (k = order - 1; k > 0; k--) {
            if (A_QA[k] > A_LIMIT || A_QA[k] < -A_LIMIT) {
                return 0;
            }
            var rc_Q31 = -(A_QA[k] << (31 - QA));
            var rc_mult1_Q30 = (1 << 30) - smmul(rc_Q31, rc_Q31);
            invGain_Q30 = smmul(invGain_Q30, rc_mult1_Q30) << 2;
            if (invGain_Q30 < MIN_INV_GAIN_Q30) {
                return 0;
            }
            var mult2Q = 32 - Integer.numberOfLeadingZeros(Math.abs(rc_mult1_Q30));
            var rc_mult2 = inverse32VarQ(rc_mult1_Q30, mult2Q + 30);
            for (var n = 0; n < (k + 1) >> 1; n++) {
                var tmp1 = A_QA[n];
                var tmp2 = A_QA[k - n - 1];
                var tmp64 = rshiftRound64((long) subSat32(tmp1, mul32FracQ(tmp2, rc_Q31, 31)) * rc_mult2, mult2Q);
                if (tmp64 > Integer.MAX_VALUE || tmp64 < Integer.MIN_VALUE) {
                    return 0;
                }
                A_QA[n] = (int) tmp64;
                tmp64 = rshiftRound64((long) subSat32(tmp2, mul32FracQ(tmp1, rc_Q31, 31)) * rc_mult2, mult2Q);
                if (tmp64 > Integer.MAX_VALUE || tmp64 < Integer.MIN_VALUE) {
                    return 0;
                }
                A_QA[k - n - 1] = (int) tmp64;
            }
        }
        if (A_QA[k] > A_LIMIT || A_QA[k] < -A_LIMIT) {
            return 0;
        }
        var rc_Q31 = -(A_QA[0] << (31 - QA));
        var rc_mult1_Q30 = (1 << 30) - smmul(rc_Q31, rc_Q31);
        invGain_Q30 = smmul(invGain_Q30, rc_mult1_Q30) << 2;
        if (invGain_Q30 < MIN_INV_GAIN_Q30) {
            return 0;
        }
        return invGain_Q30;
    }

    /**
     * Computes the inverse prediction gain of Q12 coefficients and tests their stability
     * ({@code silk_LPC_inverse_pred_gain_c}).
     *
     * @param A_Q12 the prediction coefficients in Q12
     * @param order the prediction order
     * @return the inverse prediction gain in Q30, or zero when the filter is unstable
     */
    public static int lpcInversePredGain(short[] A_Q12, int order) {
        return lpcInversePredGain(A_Q12, 0, order);
    }

    /**
     * Computes the inverse prediction gain of Q12 coefficients starting at an offset
     * ({@code silk_LPC_inverse_pred_gain_c}).
     *
     * @param A_Q12 the array holding the prediction coefficients in Q12
     * @param off   the index of the first coefficient
     * @param order the prediction order
     * @return the inverse prediction gain in Q30, or zero when the filter is unstable
     */
    public static int lpcInversePredGain(short[] A_Q12, int off, int order) {
        return lpcInversePredGain(A_Q12, off, order, new int[SILK_MAX_ORDER_LPC]);
    }

    /**
     * Computes the inverse prediction gain of Q12 coefficients using a caller-provided work array
     * ({@code silk_LPC_inverse_pred_gain_c}).
     *
     * @param A_Q12   the array holding the prediction coefficients in Q12
     * @param off     the index of the first coefficient
     * @param order   the prediction order
     * @param Atmp_QA a work array of at least {@link Define#SILK_MAX_ORDER_LPC} elements, overwritten
     * @return the inverse prediction gain in Q30, or zero when the filter is unstable
     */
    public static int lpcInversePredGain(short[] A_Q12, int off, int order, int[] Atmp_QA) {
        var DC_resp = 0;
        for (var k = 0; k < order; k++) {
            DC_resp += A_Q12[off + k];
            Atmp_QA[k] = A_Q12[off + k] << (QA - 12);
        }
        if (DC_resp >= 4096) {
            return 0;
        }
        return lpcInversePredGainQA(Atmp_QA, order);
    }
}
