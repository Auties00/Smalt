/* Ports vo-amrwbenc 0.1.3 (q_gain2.c, voicefac.c). Copyright VisualOn; Apache License 2.0. Modified for Smalt. */
package com.github.auties00.smalt.audio.amr.internal.wbenc;

import com.github.auties00.smalt.audio.amr.internal.wbcommon.WbCodecTables;

import static com.github.auties00.smalt.audio.amr.internal.wbenc.WbBasicOps.*;

/**
 * Jointly quantizes the pitch and code gains of an AMR-WB subframe with the 6-bit or 7-bit
 * codebook and the moving-average prediction of the code gain energy, and computes the voicing
 * factor of a subframe.
 *
 * <p>An instance holds the scratch coefficients and the results of the last quantization
 * ({@link #gainPit()}, {@link #gainCode()}); an instance is not thread-safe.
 *
 * @implNote This implementation computes with the saturating arithmetic of the 3GPP TS 26.173 fixed-point reference.
 */
final class WbGainQuantizer {
    /**
     * The mean energy of the innovation in dB.
     */
    private static final int MEAN_ENER = 30;

    /**
     * The number of entries of the 6-bit codebook and of the window of the 7-bit codebook.
     */
    private static final int RANGE = 64;

    /**
     * The mantissas of the five correlation coefficients of the error criterion.
     */
    private final short[] coeff = new short[5];

    /**
     * The low halves of the five correlation coefficients.
     */
    private final short[] coeffLo = new short[5];

    /**
     * The exponents of the five correlation coefficients.
     */
    private final short[] expCoeff = new short[5];

    /**
     * The exponents of the five terms of the error criterion.
     */
    private final short[] expMax = new short[5];

    /**
     * The quantized pitch gain of the last quantization in Q14.
     */
    private short gainPit;

    /**
     * The quantized code gain of the last quantization in Q16.
     */
    private int gainCode;

    /**
     * Creates the quantizer with its scratch coefficients.
     */
    WbGainQuantizer() {
    }

    /**
     * Initializes the memory of the energy predictor ({@code Init_Q_gain2}).
     *
     * @param mem the 4 past quantized energies, set to -14 dB in Q10
     */
    static void init(short[] mem) {
        for (var i = 0; i < 4; i++) {
            mem[i] = -14336;
        }
    }

    /**
     * Returns the quantized pitch gain of the last quantization.
     *
     * @return the pitch gain in Q14
     */
    short gainPit() {
        return gainPit;
    }

    /**
     * Returns the quantized code gain of the last quantization.
     *
     * @return the code gain in Q16
     */
    int gainCode() {
        return gainCode;
    }

    /**
     * Quantizes the pitch and code gains of a subframe ({@code Q_gain2}); the results are
     * available from {@link #gainPit()} and {@link #gainCode()}.
     *
     * @param xn the target vector
     * @param y1 the filtered adaptive codebook vector
     * @param qXn the scaling of the target
     * @param y2 the filtered fixed codebook vector
     * @param code the fixed codebook vector
     * @param gCoeff the correlations computed by {@link WbPitch#gPitch(short[], short[], short[], int)}
     * @param length the subframe length
     * @param nbits the number of bits, 6 or 7
     * @param gainPitIn the unquantized pitch gain, which bounds the 7-bit search
     * @param gpClip whether the pitch gain must be limited to avoid instability
     * @param mem the 4 past quantized energies, updated
     * @return the codebook index
     */
    int quantize(short[] xn, short[] y1, int qXn, short[] y2, short[] code, short[] gCoeff, int length, int nbits,
                 short gainPitIn, boolean gpClip, short[] mem) {
        short[] table;
        int minInd;
        int size;
        if (nbits == 6) {
            table = WbCodecTables.GAIN_6B;
            minInd = 0;
            size = RANGE;
            if (gpClip) {
                size = size - 16;
            }
        } else {
            table = WbCodecTables.GAIN_7B;
            var p = RANGE;
            var j = 128 - RANGE;
            if (gpClip) {
                j = j - 27;
            }
            minInd = 0;
            for (var i = 0; i < j; i++, p += 2) {
                if (gainPitIn > table[p]) {
                    minInd = minInd + 1;
                }
            }
            size = RANGE;
        }
        coeff[0] = gCoeff[0];
        expCoeff[0] = gCoeff[1];
        coeff[1] = negate(gCoeff[2]);
        expCoeff[1] = add(gCoeff[3], 1);
        var packed = dotProduct12(y2, 0, y2, 0, length);
        coeff[2] = extractH(value(packed));
        expCoeff[2] = add(sub(exponent(packed), 18), shl(qXn, 1));
        packed = dotProduct12(xn, 0, y2, 0, length);
        coeff[3] = extractH(lNegate(value(packed)));
        expCoeff[3] = add(sub(exponent(packed), 9 - 1), qXn);
        packed = dotProduct12(y1, 0, y2, 0, length);
        coeff[4] = extractH(value(packed));
        expCoeff[4] = add(sub(exponent(packed), 9 - 1), qXn);
        packed = dotProduct12(code, 0, code, 0, length);
        var tmp = value(packed);
        var expCode = sub(exponent(packed), 18 + 6 + 31);
        var log = log2(tmp);
        var exp = (short) (log >> 16);
        var frac = (short) log;
        exp = add(exp, expCode);
        tmp = mpy3216(exp, frac, -24660);
        tmp = lMac(tmp, MEAN_ENER, 8192);
        tmp = lShl(tmp, 10);
        for (var k = 0; k < 4; k++) {
            tmp = lMac(tmp, WbCodecTables.PRED[k], mem[k]);
        }
        var gcode0 = extractH(tmp);
        tmp = lMult(gcode0, 5443);
        tmp = lShr(tmp, 8);
        var expGcode0 = hi(tmp);
        frac = lo(tmp);
        gcode0 = (short) pow2(14, frac);
        expGcode0 = sub(expGcode0, 14);
        expCode = add(expGcode0, 4);
        expMax[0] = sub(expCoeff[0], 13);
        expMax[1] = sub(expCoeff[1], 14);
        expMax[2] = add(expCoeff[2], add(15, shl(expCode, 1)));
        expMax[3] = add(expCoeff[3], expCode);
        expMax[4] = add(expCoeff[4], add(1, expCode));
        var eMax = expMax[0];
        for (var i = 1; i < 5; i++) {
            if (expMax[i] > eMax) {
                eMax = expMax[i];
            }
        }
        for (var i = 0; i < 5; i++) {
            var j = add(sub(eMax, expMax[i]), 2);
            tmp = coeff[i] << 16;
            tmp = lShr(tmp, j);
            coeff[i] = hi(tmp);
            coeffLo[i] = shr(lo(tmp), 3);
        }
        var distMin = MAX_32;
        var p = minInd << 1;
        var index = 0;
        for (var i = 0; i < size; i++) {
            var gPitch = table[p++];
            var gCode = table[p++];
            gCode = multR(gCode, gcode0);
            var g2Pitch = multR(gPitch, gPitch);
            var gPitCod = multR(gCode, gPitch);
            tmp = lMult(gCode, gCode);
            var g2Code = hi(tmp);
            var g2CodeLo = lo(tmp);
            tmp = lMult(coeff[2], g2CodeLo);
            tmp = lShr(tmp, 3);
            tmp = lMac(tmp, coeffLo[0], g2Pitch);
            tmp = lMac(tmp, coeffLo[1], gPitch);
            tmp = lMac(tmp, coeffLo[2], g2Code);
            tmp = lMac(tmp, coeffLo[3], gCode);
            tmp = lMac(tmp, coeffLo[4], gPitCod);
            tmp = lShr(tmp, 12);
            tmp = lMac(tmp, coeff[0], g2Pitch);
            tmp = lMac(tmp, coeff[1], gPitch);
            tmp = lMac(tmp, coeff[2], g2Code);
            tmp = lMac(tmp, coeff[3], gCode);
            tmp = lMac(tmp, coeff[4], gPitCod);
            if (lSub(tmp, distMin) < 0) {
                distMin = tmp;
                index = i;
            }
        }
        index = add(index, minInd);
        p = index + index;
        gainPit = table[p++];
        var gCode = table[p];
        tmp = lMult(gCode, gcode0);
        tmp = lShl(tmp, add(expGcode0, 4));
        gainCode = tmp;
        log = log2(gCode);
        exp = (short) (log >> 16);
        frac = (short) log;
        exp = sub(exp, 11);
        tmp = mpy3216(exp, frac, 24660);
        var quaEner = (short) lShr(tmp, 3);
        mem[3] = mem[2];
        mem[2] = mem[1];
        mem[1] = mem[0];
        mem[0] = quaEner;
        return index;
    }

    /**
     * Computes the voicing factor of a subframe from the energies of its adaptive and fixed
     * contributions ({@code voice_factor}).
     *
     * @param exc the adaptive codebook excitation
     * @param qExc the scaling of the excitation
     * @param gainPit the pitch gain in Q14
     * @param code the fixed codebook vector in Q9
     * @param gainCode the code gain in Q0
     * @param length the subframe length
     * @return the voicing factor in Q15, from -1 (unvoiced) to 1 (voiced)
     */
    static short voiceFactor(short[] exc, int qExc, int gainPit, short[] code, short gainCode, int length) {
        var packed = dotProduct12(exc, 0, exc, 0, length);
        var ener1 = extractH(value(packed));
        var exp1 = exponent(packed);
        exp1 = sub(exp1, add(qExc, qExc));
        var tmp32 = lMult(gainPit, gainPit);
        var exp = normL(tmp32);
        var tmp = extractH(lShl(tmp32, exp));
        ener1 = mult(ener1, tmp);
        exp1 = sub(sub(exp1, exp), 10);
        packed = dotProduct12(code, 0, code, 0, length);
        var ener2 = extractH(value(packed));
        var exp2 = exponent(packed);
        exp = normS(gainCode);
        tmp = shl(gainCode, exp);
        tmp = mult(tmp, tmp);
        ener2 = mult(ener2, tmp);
        exp2 = sub(exp2, add(exp, exp));
        var i = sub(exp1, exp2);
        if (i >= 0) {
            ener1 = shr(ener1, 1);
            ener2 = shr(ener2, add(i, 1));
        } else {
            ener1 = shr(ener1, sub(1, i));
            ener2 = shr(ener2, 1);
        }
        tmp = sub(ener1, ener2);
        ener1 = add(add(ener1, ener2), 1);
        if (tmp >= 0) {
            tmp = divS(tmp, ener1);
        } else {
            tmp = negate(divS(negate(tmp), ener1));
        }
        return tmp;
    }
}
