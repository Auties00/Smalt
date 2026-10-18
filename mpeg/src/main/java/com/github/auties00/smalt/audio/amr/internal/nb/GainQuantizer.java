/* Ports opencore-amr 0.1.6 (gain_q.cpp, qua_gain.cpp, q_gain_c.cpp, q_gain_p.cpp, calc_en.cpp, g_adapt.cpp,
   g_code.cpp). Copyright PacketVideo; Apache License 2.0. Modified for Smalt. */
package com.github.auties00.smalt.audio.amr.internal.nb;

import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.abs;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.add;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.divS;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.fxpMac;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.lAdd;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.lComp;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.lExtractHi;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.lExtractLo;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.lMac;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.lMult;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.lShl;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.lShr;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.mpy3216;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.mult;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.negate;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.normL;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.round;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.shl;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.shr;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.shrR;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.sub;
import static com.github.auties00.smalt.audio.amr.internal.nb.Constants.L_SUBFR;
import static com.github.auties00.smalt.audio.amr.internal.nb.Constants.NB_QUA_CODE;
import static com.github.auties00.smalt.audio.amr.internal.nb.Constants.NB_QUA_PITCH;
import static com.github.auties00.smalt.audio.amr.internal.nb.Constants.VQ_SIZE_HIGHRATES;
import static com.github.auties00.smalt.audio.amr.internal.nb.Constants.VQ_SIZE_LOWRATES;

/**
 * Quantizes the pitch and codebook gains of the AMR-NB encoder ({@code gainQuant}): the joint
 * vector quantizers of the 5.15 to 10.2 kbit/s modes, the scalar quantizers of the 12.2 kbit/s
 * mode, the 7.95 kbit/s quantizer with its adaptive codebook gain smoothing, and the 4.75 kbit/s
 * quantizer that codes two subframes at once.
 *
 * <p>The gains of a subframe are passed in and out through a four-element holder: the pitch gain,
 * the codebook gain, and the pitch and codebook gains of the first subframe of a 4.75 kbit/s pair.
 */
final class GainQuantizer {
    /**
     * The index of the pitch gain in the gains holder.
     */
    static final int GAIN_PIT = 0;

    /**
     * The index of the codebook gain in the gains holder.
     */
    static final int GAIN_COD = 1;

    /**
     * The index of the first-subframe pitch gain of a 4.75 kbit/s pair in the gains holder.
     */
    static final int SF0_GAIN_PIT = 2;

    /**
     * The index of the first-subframe codebook gain of a 4.75 kbit/s pair in the gains holder.
     */
    static final int SF0_GAIN_COD = 3;

    /**
     * The lower LTP coding gain threshold of the gain adaptation, Q13.
     */
    private static final short LTP_GAIN_THR1 = 2721;

    /**
     * The upper LTP coding gain threshold of the gain adaptation, Q13.
     */
    private static final short LTP_GAIN_THR2 = 5443;

    /**
     * The size of the LTP coding gain history plus one.
     */
    private static final int LTPG_MEM_SIZE = 5;

    /**
     * The predicted codebook gain exponent of the first subframe of a 4.75 kbit/s pair.
     */
    short sf0ExpGcode0;

    /**
     * The predicted codebook gain fraction of the first subframe of a 4.75 kbit/s pair.
     */
    short sf0FracGcode0;

    /**
     * The target energy exponent of the first subframe of a 4.75 kbit/s pair.
     */
    short sf0ExpTargetEn;

    /**
     * The target energy fraction of the first subframe of a 4.75 kbit/s pair.
     */
    short sf0FracTargetEn;

    /**
     * The energy coefficient exponents of the first subframe of a 4.75 kbit/s pair.
     */
    final short[] sf0ExpCoeff = new short[5];

    /**
     * The energy coefficient fractions of the first subframe of a 4.75 kbit/s pair.
     */
    final short[] sf0FracCoeff = new short[5];

    /**
     * The index in the parameter array reserved for the joint 4.75 kbit/s gain index.
     */
    private int gainIndex;

    /**
     * The gain predictor fed by the quantized gains.
     */
    final GcPred gcPred = new GcPred();

    /**
     * The gain predictor fed by the unquantized gains of the 4.75 kbit/s mode.
     */
    final GcPred gcPredUnq = new GcPred();

    /**
     * The onset counter of the gain adaptation.
     */
    private short onset;

    /**
     * The previous output of the gain adaptation, Q15.
     */
    private short prevAlpha;

    /**
     * The previous codebook gain of the gain adaptation, Q1.
     */
    private short prevGc;

    /**
     * The LTP coding gain history of the gain adaptation, Q13; element 0 is the current value.
     */
    private final short[] ltpgMem = new short[LTPG_MEM_SIZE];

    /**
     * The predicted gain and innovation energy outputs of the gain predictor.
     */
    private final short[] pred = new short[4];

    /**
     * The energy coefficient fractions of the current subframe.
     */
    private final short[] fracCoeff = new short[5];

    /**
     * The energy coefficient exponents of the current subframe.
     */
    private final short[] expCoeff = new short[5];

    /**
     * The codebook gain fraction and exponent from the filtered energies.
     */
    private final short[] codGain = new short[2];

    /**
     * The quantized energies: the 12.2 kbit/s one and the other modes' one.
     */
    final short[] quaEner = new short[2];

    /**
     * The target energy exponent and fraction.
     */
    private final short[] targetEn = new short[2];

    /**
     * The scaled filtered codevector.
     */
    private final short[] scaledY2 = new short[L_SUBFR];

    /**
     * The scaled energy coefficients of the vector quantizer search.
     */
    private final short[] coeff = new short[5];

    /**
     * The low halves of {@link #coeff}.
     */
    private final short[] coeffLo = new short[5];

    /**
     * The exponents of the vector quantizer search.
     */
    private final short[] expMax = new short[5];

    /**
     * The 4.75 and 7.95 kbit/s quantizers.
     */
    private final GainQuantizerModes modes = new GainQuantizerModes(this);

    /**
     * Creates the quantizer in its reset state.
     */
    GainQuantizer() {
        reset();
    }

    /**
     * Resets the quantizer, its predictors and its gain adaptation.
     */
    void reset() {
        sf0ExpGcode0 = 0;
        sf0FracGcode0 = 0;
        sf0ExpTargetEn = 0;
        sf0FracTargetEn = 0;
        java.util.Arrays.fill(sf0ExpCoeff, (short) 0);
        java.util.Arrays.fill(sf0FracCoeff, (short) 0);
        gainIndex = -1;
        gcPred.reset();
        gcPredUnq.reset();
        onset = 0;
        prevAlpha = 0;
        prevGc = 0;
        java.util.Arrays.fill(ltpgMem, (short) 0);
    }

    /**
     * Returns the gain predictor fed by the quantized gains, which discontinuous transmission
     * reads and updates.
     *
     * @return the predictor
     */
    GcPred predictor() {
        return gcPred;
    }

    /**
     * Quantizes the gains of a subframe ({@code gainQuant}).
     *
     * @param mode the codec mode
     * @param res the LP residual
     * @param exc the excitation buffer
     * @param excOffset the first excitation sample of the subframe
     * @param code the codevector without pitch sharpening
     * @param xn the target
     * @param xn2 the target updated by the adaptive codebook
     * @param y1 the filtered adaptive codevector
     * @param y2 the filtered codevector
     * @param gCoeff the correlations of the pitch gain computation
     * @param evenSubframe whether the subframe is the first of a pair
     * @param gpLimit the pitch gain limit
     * @param gains the gains holder, see {@link #GAIN_PIT}
     * @param ana receives the gain indices
     * @param anaOffset the next free parameter
     * @param overflow the saturation flag
     * @return the next free parameter
     */
    int quant(int mode, short[] res, short[] exc, int excOffset, short[] code, short[] xn, short[] xn2, short[] y1,
              short[] y2, short[] gCoeff, boolean evenSubframe, short gpLimit, short[] gains, short[] ana,
              int anaOffset, Flag overflow) {
        if (mode == Mode.MR475) {
            if (evenSubframe) {
                gainIndex = anaOffset++;
                System.arraycopy(gcPred.pastQuaEn, 0, gcPredUnq.pastQuaEn, 0, 4);
                System.arraycopy(gcPred.pastQuaEnMR122, 0, gcPredUnq.pastQuaEnMR122, 0, 4);
                gcPredUnq.gcPred(mode, code, 0, pred, 0, overflow);
                sf0ExpGcode0 = pred[0];
                sf0FracGcode0 = pred[1];
                calcFiltEnergies(mode, xn, xn2, y1, y2, gCoeff, sf0FracCoeff, sf0ExpCoeff, overflow);
                var temp = (short) (codGain[1] + 1);
                gains[GAIN_COD] = shl(codGain[0], temp);
                calcTargetEnergy(xn, overflow);
                sf0ExpTargetEn = targetEn[0];
                sf0FracTargetEn = targetEn[1];
                modes.mr475UpdateUnqPred(gcPredUnq, sf0ExpGcode0, sf0FracGcode0, codGain[1], codGain[0], overflow);
            } else {
                gcPredUnq.gcPred(mode, code, 0, pred, 0, overflow);
                var expGcode0 = pred[0];
                var fracGcode0 = pred[1];
                calcFiltEnergies(mode, xn, xn2, y1, y2, gCoeff, fracCoeff, expCoeff, overflow);
                calcTargetEnergy(xn, overflow);
                ana[gainIndex] = modes.mr475GainQuant(gcPred, code, expGcode0, fracGcode0, expCoeff, fracCoeff,
                        targetEn[0], targetEn[1], gpLimit, gains, overflow);
            }
            return anaOffset;
        }
        gcPred.gcPred(mode, code, 0, pred, 0, overflow);
        var expGcode0 = pred[0];
        var fracGcode0 = pred[1];
        if (mode == Mode.MR122) {
            gains[GAIN_COD] = gCode(xn2, y2);
            ana[anaOffset++] = qGainCode(mode, expGcode0, fracGcode0, gains, overflow);
        } else {
            calcFiltEnergies(mode, xn, xn2, y1, y2, gCoeff, fracCoeff, expCoeff, overflow);
            if (mode == Mode.MR795) {
                anaOffset = modes.mr795GainQuant(res, exc, excOffset, code, fracCoeff, expCoeff, pred[2], pred[3],
                        expGcode0, fracGcode0, codGain[0], codGain[1], gpLimit, gains, ana, anaOffset, overflow);
            } else {
                ana[anaOffset++] = quaGain(mode, expGcode0, fracGcode0, gpLimit, gains, overflow);
            }
        }
        gcPred.update(quaEner[0], quaEner[1]);
        return anaOffset;
    }

    /**
     * Quantizes the pitch and codebook gains jointly ({@code Qua_gain}).
     *
     * @param mode the codec mode
     * @param expGcode0 the predicted codebook gain exponent
     * @param fracGcode0 the predicted codebook gain fraction
     * @param gpLimit the pitch gain limit
     * @param gains receives the quantized gains
     * @param overflow the saturation flag
     * @return the index
     */
    private short quaGain(int mode, short expGcode0, short fracGcode0, short gpLimit, short[] gains, Flag overflow) {
        short[] tableGain;
        int tableLen;
        if (mode == Mode.MR102 || mode == Mode.MR74 || mode == Mode.MR67) {
            tableLen = VQ_SIZE_HIGHRATES;
            tableGain = GainTables.TABLE_GAIN_HIGHRATES;
        } else {
            tableLen = VQ_SIZE_LOWRATES;
            tableGain = GainTables.TABLE_GAIN_LOWRATES;
        }
        var gcode0 = (short) FixedMath.pow2((short) 14, fracGcode0, overflow);
        var expCode = (short) (expGcode0 - 11);
        expMax[0] = (short) (expCoeff[0] - 13);
        expMax[1] = (short) (expCoeff[1] - 14);
        var temp = shl(expCode, (short) 1);
        temp += 15;
        expMax[2] = add(expCoeff[2], temp, overflow);
        expMax[3] = add(expCoeff[3], expCode, overflow);
        temp = (short) (expCode + 1);
        expMax[4] = add(expCoeff[4], temp, overflow);
        var eMax = expMax[0];
        for (var i = 1; i < 5; i++) {
            if (expMax[i] > eMax) {
                eMax = expMax[i];
            }
        }
        eMax++;
        for (var i = 0; i < 5; i++) {
            var j = (short) (eMax - expMax[i]);
            var lTmp = fracCoeff[i] << 16;
            lTmp = lShr(lTmp, j);
            coeff[i] = lExtractHi(lTmp);
            coeffLo[i] = lExtractLo(lTmp);
        }
        var distMin = Integer.MAX_VALUE;
        short index = 0;
        var p = 0;
        for (var i = 0; i < tableLen; i++) {
            var gPitch = tableGain[p++];
            var gCode = tableGain[p++];
            p += 2;
            if (gPitch <= gpLimit) {
                gCode = mult(gCode, gcode0, overflow);
                var g2Pitch = mult(gPitch, gPitch, overflow);
                var g2Code = mult(gCode, gCode, overflow);
                var gPitCod = mult(gCode, gPitch, overflow);
                var lTmp = mpy3216(coeff[0], coeffLo[0], g2Pitch, overflow);
                var lTmp2 = mpy3216(coeff[1], coeffLo[1], gPitch, overflow);
                lTmp = lAdd(lTmp, lTmp2, overflow);
                lTmp2 = mpy3216(coeff[2], coeffLo[2], g2Code, overflow);
                lTmp = lAdd(lTmp, lTmp2, overflow);
                lTmp2 = mpy3216(coeff[3], coeffLo[3], gCode, overflow);
                lTmp = lAdd(lTmp, lTmp2, overflow);
                lTmp2 = mpy3216(coeff[4], coeffLo[4], gPitCod, overflow);
                lTmp = lAdd(lTmp, lTmp2, overflow);
                if (lTmp < distMin) {
                    distMin = lTmp;
                    index = (short) i;
                }
            }
        }
        p = shl(index, (short) 2);
        gains[GAIN_PIT] = tableGain[p++];
        var gCode = tableGain[p++];
        quaEner[0] = tableGain[p++];
        quaEner[1] = tableGain[p];
        var lTmp = lMult(gCode, gcode0, overflow);
        temp = (short) (10 - expGcode0);
        lTmp = lShr(lTmp, temp);
        gains[GAIN_COD] = (short) (lTmp >> 16);
        return index;
    }

    /**
     * Quantizes the codebook gain with the scalar quantizer ({@code q_gain_code}).
     *
     * @param mode the codec mode
     * @param expGcode0 the predicted codebook gain exponent
     * @param fracGcode0 the predicted codebook gain fraction
     * @param gains the unquantized codebook gain in, the quantized gain out
     * @param overflow the saturation flag
     * @return the index
     */
    short qGainCode(int mode, short expGcode0, short fracGcode0, short[] gains, Flag overflow) {
        var table = GainTables.QUA_GAIN_CODE;
        var gQ0 = mode == Mode.MR122 ? (short) (gains[GAIN_COD] >> 1) : gains[GAIN_COD];
        var gcode0 = (short) FixedMath.pow2(expGcode0, fracGcode0, overflow);
        gcode0 = mode == Mode.MR122 ? shl(gcode0, (short) 4) : shl(gcode0, (short) 5);
        var p = 0;
        var errMin = (short) ((gcode0 * table[p++]) >> 15);
        errMin = (short) (gQ0 - errMin);
        if (errMin < 0) {
            errMin = (short) -errMin;
        }
        p += 2;
        short index = 0;
        for (var i = 1; i < NB_QUA_CODE; i++) {
            var err = (short) ((gcode0 * table[p++]) >> 15);
            err = (short) (gQ0 - err);
            if (err < 0) {
                err = (short) -err;
            }
            p += 2;
            if (err < errMin) {
                errMin = err;
                index = (short) i;
            }
        }
        p = index + (index << 1);
        var temp = (short) ((gcode0 * table[p++]) >> 15);
        gains[GAIN_COD] = mode == Mode.MR122 ? (short) (temp << 1) : temp;
        quaEner[0] = table[p++];
        quaEner[1] = table[p];
        return index;
    }

    /**
     * Quantizes the pitch gain with the scalar quantizer ({@code q_gain_pitch}); in the 7.95
     * kbit/s mode also returns the three candidates around the best index.
     *
     * @param mode the codec mode
     * @param gpLimit the pitch gain limit
     * @param gains the unquantized pitch gain in, the quantized gain out
     * @param gainCand receives the candidate gains, or {@code null}
     * @param gainCind receives the candidate indices, or {@code null}
     * @param overflow the saturation flag
     * @return the index
     */
    static short qGainPitch(int mode, short gpLimit, short[] gains, short[] gainCand, short[] gainCind,
                            Flag overflow) {
        var table = GainTables.QUA_GAIN_PITCH;
        var gain = gains[GAIN_PIT];
        var errMin = abs(sub(gain, table[0], overflow));
        short index = 0;
        for (var i = 1; i < NB_QUA_PITCH; i++) {
            if (table[i] <= gpLimit) {
                var err = abs(sub(gain, table[i], overflow));
                if (err < errMin) {
                    errMin = err;
                    index = (short) i;
                }
            }
        }
        if (mode == Mode.MR795) {
            int ii;
            if (index == 0) {
                ii = index;
            } else if (index == NB_QUA_PITCH - 1 || table[index + 1] > gpLimit) {
                ii = index - 2;
            } else {
                ii = index - 1;
            }
            for (var i = 0; i < 3; i++) {
                gainCind[i] = (short) ii;
                gainCand[i] = table[ii];
                ii++;
            }
            gains[GAIN_PIT] = table[index];
        } else if (mode == Mode.MR122) {
            gains[GAIN_PIT] = (short) (table[index] & 0xFFFC);
        } else {
            gains[GAIN_PIT] = table[index];
        }
        return index;
    }

    /**
     * Computes the optimal codebook gain of the 12.2 kbit/s mode ({@code G_code}).
     *
     * @param xn2 the target updated by the adaptive codebook
     * @param y2 the filtered codevector
     * @return the gain, Q1
     */
    static short gCode(short[] xn2, short[] y2) {
        var s = 0;
        for (var i = 0; i < L_SUBFR; i++) {
            s = fxpMac(xn2[i], y2[i] >> 1, s);
        }
        s <<= 1;
        var expXy = normL(s + 1);
        short xy;
        if (expXy < 17) {
            xy = (short) (s >> (17 - expXy));
        } else {
            xy = (short) (s << (expXy - 17));
        }
        if (xy <= 0) {
            return 0;
        }
        s = 0;
        for (var i = 0; i < L_SUBFR; i++) {
            var temp = (short) (y2[i] >> 1);
            s += (temp * temp) >> 2;
        }
        s <<= 3;
        var expYy = normL(s);
        short yy;
        if (expYy < 16) {
            yy = (short) (s >> (16 - expYy));
        } else {
            yy = (short) (s << (expYy - 16));
        }
        var gain = divS(xy, yy);
        var i = expXy + 5;
        i -= expYy;
        if (i > 1) {
            gain = (short) (gain >> (i - 1));
        } else {
            gain = (short) (gain << (1 - i));
        }
        return gain;
    }

    /**
     * Computes the energies and correlations of the filtered vectors the gain quantizers use
     * ({@code calc_filt_energies}), and in the 4.75 and 7.95 kbit/s modes the optimal codebook
     * gain into {@link #codGain}.
     *
     * @param mode the codec mode
     * @param xn the target
     * @param xn2 the target updated by the adaptive codebook
     * @param y1 the filtered adaptive codevector
     * @param y2 the filtered codevector
     * @param gCoeff the correlations of the pitch gain computation
     * @param fracCoeff receives the coefficient fractions
     * @param expCoeff receives the coefficient exponents
     * @param overflow the saturation flag
     */
    private void calcFiltEnergies(int mode, short[] xn, short[] xn2, short[] y1, short[] y2, short[] gCoeff,
                                  short[] fracCoeff, short[] expCoeff, Flag overflow) {
        fracCoeff[0] = gCoeff[0];
        expCoeff[0] = gCoeff[1];
        fracCoeff[1] = negate(gCoeff[2]);
        expCoeff[1] = (short) (gCoeff[3] + 1);
        var both = mode == Mode.MR795 || mode == Mode.MR475;
        var s1 = both ? 0 : 1;
        var s2 = s1;
        var s3 = s1;
        for (var i = 0; i < L_SUBFR; i++) {
            var tmp = (short) (y2[i] >> 3);
            scaledY2[i] = tmp;
            s1 = lMac(s1, tmp, tmp, overflow);
            s2 = lMac(s2, xn[i], tmp, overflow);
            s3 = lMac(s3, y1[i], tmp, overflow);
        }
        var exp = normL(s1);
        fracCoeff[2] = (short) (lShl(s1, exp) >> 16);
        expCoeff[2] = (short) (-3 - exp);
        exp = normL(s2);
        fracCoeff[3] = negate((short) (lShl(s2, exp) >> 16));
        expCoeff[3] = (short) (7 - exp);
        exp = normL(s3);
        fracCoeff[4] = (short) (lShl(s3, exp) >> 16);
        expCoeff[4] = (short) (7 - exp);
        if (both) {
            s1 = 0;
            for (var i = 0; i < L_SUBFR; i++) {
                s1 = fxpMac(xn2[i], scaledY2[i], s1);
            }
            s1 = s1 << 1;
            exp = normL(s1);
            var frac = (short) (lShl(s1, exp) >> 16);
            exp = (short) (6 - exp);
            if (frac <= 0) {
                codGain[0] = 0;
                codGain[1] = 0;
            } else {
                codGain[0] = divS(shr(frac, (short) 1, overflow), fracCoeff[2]);
                codGain[1] = (short) ((exp - expCoeff[2]) - 14);
            }
        }
    }

    /**
     * Computes the energy of the target ({@code calc_target_energy}) into {@link #targetEn}.
     *
     * @param xn the target
     * @param overflow the saturation flag
     */
    private void calcTargetEnergy(short[] xn, Flag overflow) {
        var s = 0;
        for (var i = 0; i < L_SUBFR; i++) {
            s = fxpMac(xn[i], xn[i], s);
        }
        if (s < 0) {
            overflow.value = true;
            s = Integer.MAX_VALUE;
        }
        var exp = normL(s);
        targetEn[1] = (short) (lShl(s, exp) >> 16);
        targetEn[0] = (short) (16 - exp);
    }

    /**
     * Computes the energies of the unfiltered residual, excitation and codevector and the LTP
     * coding gain ({@code calc_unfilt_energies}).
     *
     * @param res the LP residual
     * @param exc the excitation buffer
     * @param excOffset the first excitation sample
     * @param code the codevector
     * @param gainPit the quantized pitch gain
     * @param fracEn receives the 4 energy fractions
     * @param expEn receives the 4 energy exponents
     * @param overflow the saturation flag
     * @return the LTP coding gain, Q13
     */
    static short calcUnfiltEnergies(short[] res, short[] exc, int excOffset, short[] code, short gainPit, short[] fracEn,
                                    short[] expEn, Flag overflow) {
        var s1 = 0;
        var s2 = 0;
        var s3 = 0;
        var s4 = 0;
        for (var i = 0; i < L_SUBFR; i++) {
            var tmp1 = res[i];
            var tmp2 = exc[excOffset + i];
            s1 = fxpMac(tmp1, tmp1, s1);
            s2 = fxpMac(tmp2, tmp2, s2);
            s3 = fxpMac(tmp2, code[i], s3);
            var lTemp = lMult(tmp2, gainPit, overflow);
            lTemp = lShl(lTemp, (short) 1);
            tmp2 = sub(tmp1, round(lTemp, overflow), overflow);
            s4 = lMac(s4, tmp2, tmp2, overflow);
        }
        s1 = s1 << 1;
        s2 = s2 << 1;
        s3 = s3 << 1;
        if ((s1 & 0x80000000) != 0) {
            s1 = Integer.MAX_VALUE;
            overflow.value = true;
        }
        if (s1 < 400) {
            fracEn[0] = 0;
            expEn[0] = -15;
        } else {
            var exp = normL(s1);
            fracEn[0] = (short) (lShl(s1, exp) >> 16);
            expEn[0] = (short) (15 - exp);
        }
        if ((s2 & 0x80000000) != 0) {
            s2 = Integer.MAX_VALUE;
            overflow.value = true;
        }
        var exp = normL(s2);
        fracEn[1] = (short) (lShl(s2, exp) >> 16);
        expEn[1] = (short) (15 - exp);
        exp = normL(s3);
        fracEn[2] = (short) (lShl(s3, exp) >> 16);
        expEn[2] = (short) (2 - exp);
        exp = normL(s4);
        var ltpResEn = (short) (lShl(s4, exp) >> 16);
        exp = (short) (15 - exp);
        fracEn[3] = ltpResEn;
        expEn[3] = exp;
        if (ltpResEn > 0 && fracEn[0] != 0) {
            var predGain = divS(shr(fracEn[0], (short) 1, overflow), ltpResEn);
            exp = sub(exp, expEn[0], overflow);
            var lTemp = predGain << 16;
            lTemp = lShr(lTemp, (short) (exp + 3));
            var log = FixedMath.log2(lTemp);
            var ltpgExp = (short) (log >> 16);
            var ltpgFrac = (short) log;
            lTemp = lComp((short) (ltpgExp - 27), ltpgFrac, overflow);
            return round(lShl(lTemp, (short) 13), overflow);
        }
        return 0;
    }

    /**
     * Computes the adaptive codebook gain smoothing factor of the 7.95 kbit/s mode
     * ({@code gain_adapt}).
     *
     * @param ltpg the LTP coding gain, Q13
     * @param gainCod the quantized codebook gain, Q1
     * @param overflow the saturation flag
     * @return the factor, Q15
     */
    short gainAdapt(short ltpg, short gainCod, Flag overflow) {
        short adapt;
        if (ltpg <= LTP_GAIN_THR1) {
            adapt = 0;
        } else if (ltpg <= LTP_GAIN_THR2) {
            adapt = 1;
        } else {
            adapt = 2;
        }
        var tmp = shrR(gainCod, (short) 1, overflow);
        if (tmp > prevGc && gainCod > 200) {
            onset = 8;
        } else if (onset != 0) {
            onset--;
        }
        if (onset != 0 && adapt < 2) {
            adapt++;
        }
        ltpgMem[0] = ltpg;
        var filt = FixedMath.gmedN(ltpgMem, 0, 5);
        short result;
        if (adapt == 0) {
            if (filt > 5443) {
                result = 0;
            } else if (filt < 0) {
                result = 16384;
            } else {
                filt = shl(filt, (short) 2);
                result = mult((short) 24660, filt, overflow);
                result = (short) (16384 - result);
            }
        } else {
            result = 0;
        }
        if (prevAlpha == 0) {
            result = shr(result, (short) 1, overflow);
        }
        prevAlpha = result;
        prevGc = gainCod;
        for (var i = LTPG_MEM_SIZE - 1; i > 0; i--) {
            ltpgMem[i] = ltpgMem[i - 1];
        }
        return result;
    }
}
