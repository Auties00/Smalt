/* Ports opencore-amr 0.1.6 (gc_pred.cpp). Copyright PacketVideo; Apache License 2.0. Modified for Smalt. */
package com.github.auties00.smalt.audio.amr.internal.nb;

import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.MAX_32;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.MIN_32;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.add;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.lAdd;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.lShl;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.lSub;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.normL;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.round;
import static com.github.auties00.smalt.audio.amr.internal.nb.Constants.L_SUBFR;

import java.util.Arrays;

/**
 * Predicts the fixed codebook gain of a subframe from the energy of the codevector and the
 * quantized energies of the four previous subframes (MA prediction, 3GPP TS 26.090 section 5.7).
 *
 * <p>An instance is the predictor state ({@code gc_predState}): the past quantized energies in
 * the {@code MR122} scale (20 log10) and in the scale of the other modes. It is not thread-safe.
 */
final class GcPred {
    /**
     * The number of predictor taps.
     */
    private static final int NPRED = 4;

    /**
     * The mean energy of {@code MR122}, Q17.
     */
    private static final int MEAN_ENER_MR122 = 783741;

    /**
     * The minimum past quantized energy of the other modes.
     */
    static final short MIN_ENERGY = -14336;

    /**
     * The minimum past quantized energy of {@code MR122}.
     */
    static final short MIN_ENERGY_MR122 = -2381;

    /**
     * Holds the MA prediction coefficients of the other modes, Q13.
     */
    private static final short[] PRED = {5571, 4751, 2785, 1556};

    /**
     * Holds the MA prediction coefficients of {@code MR122}, Q6.
     */
    private static final short[] PRED_MR122 = {44, 37, 22, 12};

    /**
     * Holds the past quantized energies of the other modes, Q10, newest first
     * ({@code past_qua_en}).
     */
    final short[] pastQuaEn = new short[NPRED];

    /**
     * Holds the past quantized energies of {@code MR122}, Q10, newest first
     * ({@code past_qua_en_MR122}).
     */
    final short[] pastQuaEnMR122 = new short[NPRED];

    /**
     * Creates a predictor in its reset state.
     */
    GcPred() {
        reset();
    }

    /**
     * Sets every past energy to its minimum ({@code gc_pred_reset}).
     */
    void reset() {
        Arrays.fill(pastQuaEn, MIN_ENERGY);
        Arrays.fill(pastQuaEnMR122, MIN_ENERGY_MR122);
    }

    /**
     * Computes the predicted fixed codebook gain of a subframe ({@code gc_pred}).
     *
     * <p>The results are written to {@code out}: at {@code outOffset} the exponent and at
     * {@code outOffset + 1} the fraction of the predicted gain (in {@link Mode#MR122}, of its
     * base-2 logarithm, Q11), and, in {@link Mode#MR795} only, at {@code outOffset + 2} the
     * exponent and at {@code outOffset + 3} the fraction of the codevector energy; in the other
     * modes those two entries are left untouched.
     *
     * @param mode the codec mode, one of the {@link Mode} constants
     * @param code the 40-sample innovative codevector, Q13
     * @param codeOffset the index of the first sample
     * @param out receives the results
     * @param outOffset the index of the first result
     * @param overflow the flag raised on saturation
     */
    void gcPred(int mode, short[] code, int codeOffset, short[] out, int outOffset, Flag overflow) {
        var enerCode = 0;
        for (var i = 0; i < L_SUBFR; i++) {
            var tmp = code[codeOffset + i];
            enerCode += (tmp * tmp) >> 3;
        }
        enerCode <<= 4;
        if ((enerCode >> 31) != 0) {
            enerCode = MAX_32;
        }
        if (mode == Mode.MR122) {
            enerCode = (round(enerCode, overflow) * 26214) << 1;
            var log = FixedMath.log2(enerCode);
            var exp = (short) (log >> 16);
            var frac = (short) log;
            var temp1 = (exp - 30) << 16;
            enerCode = temp1 + (frac << 1);
            var ener = MEAN_ENER_MR122;
            for (var i = 0; i < NPRED; i++) {
                temp1 = (pastQuaEnMR122[i] * PRED_MR122[i]) << 1;
                ener = lAdd(ener, temp1, overflow);
            }
            temp1 = lSub(ener, enerCode, overflow);
            var expGcode0 = (short) (temp1 >> 17);
            out[outOffset] = expGcode0;
            var temp2 = expGcode0 << 15;
            temp1 >>= 2;
            out[outOffset + 1] = (short) (temp1 - temp2);
        } else {
            var expCode = normL(enerCode);
            enerCode = lShl(enerCode, expCode);
            var log = FixedMath.log2Norm(enerCode, expCode);
            var exp = (short) (log >> 16);
            var frac = (short) log;
            var temp2 = (exp * -24660) << 1;
            var tmp = (frac * -24660) >> 15;
            if ((tmp & 0x00010000) != 0) {
                tmp = tmp | 0xffff0000;
            }
            tmp = tmp << 1;
            tmp = lAdd(tmp, temp2, overflow);
            if (mode == Mode.MR102) {
                tmp = lAdd(tmp, 16678 << 7, overflow);
            } else if (mode == Mode.MR795) {
                out[outOffset + 3] = (short) (enerCode >> 16);
                out[outOffset + 2] = (short) (-11 - expCode);
                tmp = lAdd(tmp, 17062 << 7, overflow);
            } else if (mode == Mode.MR74) {
                tmp = lAdd(tmp, 32588 << 6, overflow);
            } else if (mode == Mode.MR67) {
                tmp = lAdd(tmp, 32268 << 6, overflow);
            } else {
                tmp = lAdd(tmp, 16678 << 7, overflow);
            }
            if (tmp > 0x001fffff) {
                overflow.value = true;
                tmp = MAX_32;
            } else if (tmp < -2097152) {
                overflow.value = true;
                tmp = MIN_32;
            } else {
                tmp = tmp << 10;
            }
            for (var i = 0; i < NPRED; i++) {
                temp2 = (PRED[i] * pastQuaEn[i]) << 1;
                tmp = lAdd(tmp, temp2, overflow);
            }
            var gcode0 = (short) (tmp >> 16);
            if (mode == Mode.MR74) {
                tmp = (gcode0 * 5439) << 1;
            } else {
                tmp = (gcode0 * 5443) << 1;
            }
            if (tmp < 0) {
                tmp = ~((~tmp) >> 8);
            } else {
                tmp = tmp >> 8;
            }
            var expGcode0 = (short) (tmp >> 16);
            out[outOffset] = expGcode0;
            int temp1;
            if (tmp < 0) {
                temp1 = ~((~tmp) >> 1);
            } else {
                temp1 = tmp >> 1;
            }
            temp2 = expGcode0 << 15;
            out[outOffset + 1] = (short) lSub(temp1, temp2, overflow);
        }
    }

    /**
     * Shifts a new pair of quantized energies into the history ({@code gc_pred_update}).
     *
     * @param quaEnerMR122 the quantized energy in the {@code MR122} scale, Q10
     * @param quaEner the quantized energy in the scale of the other modes, Q10
     */
    void update(short quaEnerMR122, short quaEner) {
        pastQuaEn[3] = pastQuaEn[2];
        pastQuaEnMR122[3] = pastQuaEnMR122[2];
        pastQuaEn[2] = pastQuaEn[1];
        pastQuaEnMR122[2] = pastQuaEnMR122[1];
        pastQuaEn[1] = pastQuaEn[0];
        pastQuaEnMR122[1] = pastQuaEnMR122[0];
        pastQuaEnMR122[0] = quaEnerMR122;
        pastQuaEn[0] = quaEner;
    }

    /**
     * Returns the averages of the past quantized energies, each limited below to its minimum
     * ({@code gc_pred_average_limited}).
     *
     * @param overflow the flag raised on saturation
     * @return the {@code MR122} average (upper 16 bits) and the average of the other modes
     * (lower 16 bits)
     */
    int averageLimited(Flag overflow) {
        short average = 0;
        for (var i = 0; i < NPRED; i++) {
            average = add(average, pastQuaEnMR122[i], overflow);
        }
        average = quarter(average);
        if (average < MIN_ENERGY_MR122) {
            average = MIN_ENERGY_MR122;
        }
        var enerAvgMR122 = average;
        average = 0;
        for (var i = 0; i < NPRED; i++) {
            average = add(average, pastQuaEn[i], overflow);
        }
        average = quarter(average);
        if (average < MIN_ENERGY) {
            average = MIN_ENERGY;
        }
        return (enerAvgMR122 << 16) | (average & 0xffff);
    }

    /**
     * Divides a sum by 4 the way the reference does, forcing the two top bits of a negative
     * result.
     *
     * @param sum the 32-bit sum to divide
     * @return the quarter
     */
    private static short quarter(short sum) {
        if (sum < 0) {
            return (short) ((sum >> 2) | 0xc000);
        }
        return (short) (sum >> 2);
    }
}
