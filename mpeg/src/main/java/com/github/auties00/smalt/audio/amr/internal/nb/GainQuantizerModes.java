/* Ports opencore-amr 0.1.6 (qgain475.cpp, qgain795.cpp). Copyright PacketVideo; Apache License 2.0. Modified for
   Smalt. */
package com.github.auties00.smalt.audio.amr.internal.nb;

import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.add;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.divS;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.lExtractHi;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.lExtractLo;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.lMult;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.lShl;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.lShr;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.lSub;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.mac32;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.mac3216;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.mpy3216;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.mult;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.round;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.shl;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.shr;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.shrR;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.sub;
import static com.github.auties00.smalt.audio.amr.internal.nb.Constants.MR475_VQ_SIZE;
import static com.github.auties00.smalt.audio.amr.internal.nb.Constants.NB_QUA_CODE;
import static com.github.auties00.smalt.audio.amr.internal.nb.GainQuantizer.GAIN_COD;
import static com.github.auties00.smalt.audio.amr.internal.nb.GainQuantizer.GAIN_PIT;
import static com.github.auties00.smalt.audio.amr.internal.nb.GainQuantizer.SF0_GAIN_COD;
import static com.github.auties00.smalt.audio.amr.internal.nb.GainQuantizer.SF0_GAIN_PIT;

/**
 * Quantizes the gains of the 4.75 kbit/s mode (one vector index for two subframes) and of the
 * 7.95 kbit/s mode (scalar pitch gain with a three-candidate joint search and the adaptive
 * smoothing of the codebook gain) for {@link GainQuantizer}.
 */
final class GainQuantizerModes {
    /**
     * The smallest quantized energy, Q10, log2 of 0.0251189.
     */
    private static final short MIN_QUA_ENER = -5443;

    /**
     * The smallest quantized energy of the 12.2 kbit/s predictor, Q10.
     */
    private static final short MIN_QUA_ENER_MR122 = -32768;

    /**
     * The largest quantized energy, Q10, log2 of 7.8125.
     */
    private static final short MAX_QUA_ENER = 3037;

    /**
     * The largest quantized energy of the 12.2 kbit/s predictor, Q10.
     */
    private static final short MAX_QUA_ENER_MR122 = 18284;

    /**
     * The quantizer whose state and quantized energies these quantizers use.
     */
    private final GainQuantizer quantizer;

    /**
     * The scaled energy coefficients of the search, up to two subframes.
     */
    private final short[] coeff = new short[10];

    /**
     * The low halves of {@link #coeff}.
     */
    private final short[] coeffLo = new short[10];

    /**
     * The exponents of the search.
     */
    private final short[] expMax = new short[10];

    /**
     * The exponents of the 7.95 kbit/s modified search.
     */
    private final short[] expCoeffMod = new short[5];

    /**
     * The predictor outputs.
     */
    private final short[] pred = new short[4];

    /**
     * The pitch gain candidates of the 7.95 kbit/s mode.
     */
    private final short[] gPitchCand = new short[3];

    /**
     * The pitch gain candidate indices of the 7.95 kbit/s mode.
     */
    private final short[] gPitchCind = new short[3];

    /**
     * The unfiltered energy fractions of the 7.95 kbit/s mode.
     */
    private final short[] fracEn = new short[4];

    /**
     * The unfiltered energy exponents of the 7.95 kbit/s mode.
     */
    private final short[] expEn = new short[4];

    /**
     * The exponent output of {@link FixedMath#sqrtLExp(int, short[], int, Flag)}.
     */
    private final short[] sqrtExp = new short[1];

    /**
     * Creates the quantizers of a {@link GainQuantizer}.
     *
     * @param quantizer the owner
     */
    GainQuantizerModes(GainQuantizer quantizer) {
        this.quantizer = quantizer;
    }

    /**
     * Stores the gains of one subframe of a 4.75 kbit/s codeword and updates the predictor
     * ({@code MR475_quant_store_results}).
     *
     * @param pred the predictor
     * @param table the table index of the subframe's gains
     * @param gcode0 the predicted codebook gain, Q14
     * @param expGcode0 the predicted codebook gain exponent
     * @param gains receives the gains
     * @param pitIndex the index of the pitch gain in {@code gains}
     * @param codIndex the index of the codebook gain in {@code gains}
     * @param overflow the saturation flag
     */
    private static void mr475QuantStoreResults(GcPred pred, int table, short gcode0, short expGcode0, short[] gains,
                                               int pitIndex, int codIndex, Flag overflow) {
        var p = table;
        gains[pitIndex] = GainTables.TABLE_GAIN_MR475[p++];
        var gCode = GainTables.TABLE_GAIN_MR475[p];
        var lTmp = (gCode * gcode0) << 1;
        var tmp = (short) (10 - expGcode0);
        lTmp = lShr(lTmp, tmp);
        gains[codIndex] = (short) (lTmp >> 16);
        var log = FixedMath.log2(gCode);
        var exp = (short) (log >> 16);
        var frac = (short) log;
        exp -= 12;
        tmp = shrR(frac, (short) 5, overflow);
        var quaEnerMR122 = (short) (exp << 10);
        quaEnerMR122 = (short) (tmp + quaEnerMR122);
        lTmp = mpy3216(exp, frac, (short) 24660, overflow);
        lTmp = lTmp << 13;
        var quaEner = (short) ((lTmp + 0x00008000) >> 16);
        pred.update(quaEnerMR122, quaEner);
    }

    /**
     * Updates the unquantized-gain predictor with the optimal codebook gain of the first
     * subframe of a pair ({@code MR475_update_unq_pred}).
     *
     * @param pred the predictor
     * @param expGcode0 the predicted codebook gain exponent
     * @param fracGcode0 the predicted codebook gain fraction
     * @param codGainExp the optimal codebook gain exponent
     * @param codGainFrac the optimal codebook gain fraction
     * @param overflow the saturation flag
     */
    void mr475UpdateUnqPred(GcPred pred, short expGcode0, short fracGcode0, short codGainExp, short codGainFrac,
                            Flag overflow) {
        short quaEner;
        short quaEnerMR122;
        if (codGainFrac <= 0) {
            quaEner = MIN_QUA_ENER;
            quaEnerMR122 = MIN_QUA_ENER_MR122;
        } else {
            fracGcode0 = (short) FixedMath.pow2((short) 14, fracGcode0, overflow);
            if (codGainFrac >= fracGcode0) {
                codGainFrac >>= 1;
                codGainExp += 1;
            }
            var frac = divS(codGainFrac, fracGcode0);
            var tmp = (short) (codGainExp - expGcode0);
            tmp -= 1;
            var log = FixedMath.log2(frac);
            var exp = (short) (log >> 16);
            frac = (short) log;
            exp += tmp;
            quaEnerMR122 = shrR(frac, (short) 5, overflow);
            tmp = (short) (exp << 10);
            quaEnerMR122 += tmp;
            if (quaEnerMR122 > MAX_QUA_ENER_MR122) {
                quaEner = MAX_QUA_ENER;
                quaEnerMR122 = MAX_QUA_ENER_MR122;
            } else {
                var lTmp = mpy3216(exp, frac, (short) 24660, overflow);
                lTmp = lShl(lTmp, (short) 13);
                quaEner = round(lTmp, overflow);
            }
        }
        pred.update(quaEnerMR122, quaEner);
    }

    /**
     * Quantizes the gains of a pair of subframes jointly ({@code MR475_gain_quant}).
     *
     * @param pred the predictor fed by the quantized gains
     * @param sf1CodeNosharp the codevector of the second subframe without sharpening
     * @param sf1ExpGcode0 the predicted codebook gain exponent of the second subframe
     * @param sf1FracGcode0 the predicted codebook gain fraction of the second subframe
     * @param sf1ExpCoeff the energy coefficient exponents of the second subframe
     * @param sf1FracCoeff the energy coefficient fractions of the second subframe
     * @param sf1ExpTargetEn the target energy exponent of the second subframe
     * @param sf1FracTargetEn the target energy fraction of the second subframe
     * @param gpLimit the pitch gain limit
     * @param gains receives the gains of both subframes
     * @param overflow the saturation flag
     * @return the index
     */
    short mr475GainQuant(GcPred pred, short[] sf1CodeNosharp, short sf1ExpGcode0, short sf1FracGcode0,
                         short[] sf1ExpCoeff, short[] sf1FracCoeff, short sf1ExpTargetEn, short sf1FracTargetEn,
                         short gpLimit, short[] gains, Flag overflow) {
        var q = quantizer;
        var sf0ExpGcode0 = q.sf0ExpGcode0;
        var sf0ExpCoeff = q.sf0ExpCoeff;
        var sf0FracCoeff = q.sf0FracCoeff;
        var sf0ExpTargetEn = q.sf0ExpTargetEn;
        var sf0FracTargetEn = q.sf0FracTargetEn;
        var sf0Gcode0 = (short) FixedMath.pow2((short) 14, q.sf0FracGcode0, overflow);
        var sf1Gcode0 = (short) FixedMath.pow2((short) 14, sf1FracGcode0, overflow);
        var exp = (short) (sf0ExpGcode0 - 11);
        expMax[0] = (short) (sf0ExpCoeff[0] - 13);
        expMax[1] = (short) (sf0ExpCoeff[1] - 14);
        expMax[2] = (short) (sf0ExpCoeff[2] + (15 + (exp << 1)));
        expMax[3] = (short) (sf0ExpCoeff[3] + exp);
        expMax[4] = (short) (sf0ExpCoeff[4] + (1 + exp));
        exp = (short) (sf1ExpGcode0 - 11);
        expMax[5] = (short) (sf1ExpCoeff[0] - 13);
        expMax[6] = (short) (sf1ExpCoeff[1] - 14);
        expMax[7] = (short) (sf1ExpCoeff[2] + (15 + (exp << 1)));
        expMax[8] = (short) (sf1ExpCoeff[3] + exp);
        expMax[9] = (short) (sf1ExpCoeff[4] + (1 + exp));
        exp = (short) (sf0ExpTargetEn - sf1ExpTargetEn);
        if (exp > 0) {
            sf1FracTargetEn >>= exp;
        } else {
            sf0FracTargetEn = (short) (sf0FracTargetEn >> -exp);
        }
        exp = 0;
        var tmp = shrR(sf1FracTargetEn, (short) 1, overflow);
        if (tmp > sf0FracTargetEn) {
            exp = 1;
        } else {
            tmp = (short) ((sf0FracTargetEn + 3) >> 2);
            if (tmp > sf1FracTargetEn) {
                exp = -1;
            }
        }
        for (var i = 0; i < 5; i++) {
            expMax[i] += exp;
        }
        exp = expMax[0];
        for (var i = 9; i > 0; i--) {
            if (expMax[i] > exp) {
                exp = expMax[i];
            }
        }
        exp++;
        for (var i = 0; i < 10; i++) {
            tmp = (short) (exp - expMax[i]);
            var lTmp = (i < 5 ? sf0FracCoeff[i] : sf1FracCoeff[i - 5]) << 16;
            lTmp = lShr(lTmp, tmp);
            coeff[i] = (short) (lTmp >> 16);
            coeffLo[i] = (short) ((lTmp >> 1) - ((lTmp >> 16) << 15));
        }
        var distMin = Integer.MAX_VALUE;
        short index = 0;
        var table = GainTables.TABLE_GAIN_MR475;
        var p = 0;
        for (var i = 0; i < MR475_VQ_SIZE; i++) {
            var gPitch = table[p++];
            var gCode = table[p++];
            gCode = (short) ((gCode * sf0Gcode0) >> 15);
            var g2Pitch = (short) ((gPitch * gPitch) >> 15);
            var g2Code = (short) ((gCode * gCode) >> 15);
            var gPitCod = (short) ((gCode * gPitch) >> 15);
            var lTmp = mpy3216(coeff[0], coeffLo[0], g2Pitch, overflow)
                    + mpy3216(coeff[1], coeffLo[1], gPitch, overflow)
                    + mpy3216(coeff[2], coeffLo[2], g2Code, overflow)
                    + mpy3216(coeff[3], coeffLo[3], gCode, overflow)
                    + mpy3216(coeff[4], coeffLo[4], gPitCod, overflow);
            tmp = (short) (gPitch - gpLimit);
            gPitch = table[p++];
            gCode = table[p++];
            if (tmp <= 0 && gPitch <= gpLimit) {
                gCode = (short) ((gCode * sf1Gcode0) >> 15);
                g2Pitch = (short) ((gPitch * gPitch) >> 15);
                g2Code = (short) ((gCode * gCode) >> 15);
                gPitCod = (short) ((gCode * gPitch) >> 15);
                lTmp += mpy3216(coeff[5], coeffLo[5], g2Pitch, overflow)
                        + mpy3216(coeff[6], coeffLo[6], gPitch, overflow)
                        + mpy3216(coeff[7], coeffLo[7], g2Code, overflow)
                        + mpy3216(coeff[8], coeffLo[8], gCode, overflow)
                        + mpy3216(coeff[9], coeffLo[9], gPitCod, overflow);
                if (lTmp < distMin) {
                    distMin = lTmp;
                    index = (short) i;
                }
            }
        }
        var tableIndex = index << 2;
        mr475QuantStoreResults(pred, tableIndex, sf0Gcode0, sf0ExpGcode0, gains, SF0_GAIN_PIT, SF0_GAIN_COD, overflow);
        pred.gcPred(Mode.MR475, sf1CodeNosharp, 0, this.pred, 0, overflow);
        sf1ExpGcode0 = this.pred[0];
        sf1FracGcode0 = this.pred[1];
        sf1Gcode0 = (short) FixedMath.pow2((short) 14, sf1FracGcode0, overflow);
        tableIndex += 2;
        mr475QuantStoreResults(pred, tableIndex, sf1Gcode0, sf1ExpGcode0, gains, GAIN_PIT, GAIN_COD, overflow);
        return index;
    }

    /**
     * Quantizes the gains of a 7.95 kbit/s subframe ({@code MR795_gain_quant}).
     *
     * @param res the LP residual
     * @param exc the excitation buffer
     * @param excOffset the first excitation sample of the subframe
     * @param code the codevector
     * @param fracCoeff the energy coefficient fractions
     * @param expCoeff the energy coefficient exponents
     * @param expCodeEn the innovation energy exponent
     * @param fracCodeEn the innovation energy fraction
     * @param expGcode0 the predicted codebook gain exponent
     * @param fracGcode0 the predicted codebook gain fraction
     * @param codGainFrac the optimal codebook gain fraction
     * @param codGainExp the optimal codebook gain exponent
     * @param gpLimit the pitch gain limit
     * @param gains the gains holder
     * @param ana receives the pitch and codebook gain indices
     * @param anaOffset the next free parameter
     * @param overflow the saturation flag
     * @return the next free parameter
     */
    int mr795GainQuant(short[] res, short[] exc, int excOffset, short[] code, short[] fracCoeff, short[] expCoeff,
                       short expCodeEn, short fracCodeEn, short expGcode0, short fracGcode0, short codGainFrac,
                       short codGainExp, short gpLimit, short[] gains, short[] ana, int anaOffset, Flag overflow) {
        var gainPitIndex = GainQuantizer.qGainPitch(Mode.MR795, gpLimit, gains, gPitchCand, gPitchCind, overflow);
        var gcode0 = (short) FixedMath.pow2((short) 14, fracGcode0, overflow);
        var indices = mr795GainCodeQuant3(expGcode0, gcode0, fracCoeff, expCoeff, gains, overflow);
        gainPitIndex = (short) (indices >> 16);
        var gainCodIndex = (short) indices;
        var ltpg = GainQuantizer.calcUnfiltEnergies(res, exc, excOffset, code, gains[GAIN_PIT], fracEn, expEn, overflow);
        var alpha = quantizer.gainAdapt(ltpg, gains[GAIN_COD], overflow);
        if (fracEn[0] != 0 && alpha > 0) {
            fracEn[3] = fracCodeEn;
            expEn[3] = expCodeEn;
            var exp = (short) (sub(codGainExp, expGcode0, overflow) + 10);
            var gainCodUnq = shl(codGainFrac, exp);
            gainCodIndex = mr795GainCodeQuantMod(gains[GAIN_PIT], expGcode0, gcode0, alpha, gainCodUnq, gains,
                    overflow);
        }
        ana[anaOffset++] = gainPitIndex;
        ana[anaOffset++] = gainCodIndex;
        return anaOffset;
    }

    /**
     * Searches the codebook gain jointly with the three pitch gain candidates
     * ({@code MR795_gain_code_quant3}).
     *
     * @param expGcode0 the predicted codebook gain exponent
     * @param gcode0 the predicted codebook gain, Q14
     * @param fracCoeff the energy coefficient fractions
     * @param expCoeff the energy coefficient exponents
     * @param gains receives the quantized gains
     * @param overflow the saturation flag
     * @return the pitch gain index in the high half and the codebook gain index in the low half
     */
    private int mr795GainCodeQuant3(short expGcode0, short gcode0, short[] fracCoeff, short[] expCoeff, short[] gains,
                                    Flag overflow) {
        var expCode = (short) (expGcode0 - 10);
        expMax[0] = (short) (expCoeff[0] - 13);
        expMax[1] = (short) (expCoeff[1] - 14);
        expMax[2] = (short) (expCoeff[2] + shl(expCode, (short) 1) + 15);
        expMax[3] = (short) (expCoeff[3] + expCode);
        expMax[4] = (short) (expCoeff[4] + (expCode + 1));
        var eMax = expMax[0];
        for (var i = 1; i < 5; i++) {
            if (expMax[i] > eMax) {
                eMax = expMax[i];
            }
        }
        eMax = add(eMax, (short) 1, overflow);
        for (var i = 0; i < 5; i++) {
            var j = (short) (eMax - expMax[i]);
            var lTmp = fracCoeff[i] << 16;
            lTmp = lShr(lTmp, j);
            coeff[i] = lExtractHi(lTmp);
            coeffLo[i] = lExtractLo(lTmp);
        }
        var distMin = Integer.MAX_VALUE;
        var codInd = 0;
        var pitInd = 0;
        var table = GainTables.QUA_GAIN_CODE;
        for (var j = 0; j < 3; j++) {
            var gPitch = gPitchCand[j];
            var g2Pitch = mult(gPitch, gPitch, overflow);
            var lTmp0 = mpy3216(coeff[0], coeffLo[0], g2Pitch, overflow);
            lTmp0 = mac3216(lTmp0, coeff[1], coeffLo[1], gPitch, overflow);
            var p = 0;
            for (var i = 0; i < NB_QUA_CODE; i++) {
                var gCode = table[p];
                p += 3;
                gCode = mult(gCode, gcode0, overflow);
                var lTmp = lMult(gCode, gCode, overflow);
                var g2CodeH = lExtractHi(lTmp);
                var g2CodeL = lExtractLo(lTmp);
                lTmp = lMult(gCode, gPitch, overflow);
                var gPitCodH = lExtractHi(lTmp);
                var gPitCodL = lExtractLo(lTmp);
                lTmp = mac32(lTmp0, coeff[2], coeffLo[2], g2CodeH, g2CodeL, overflow);
                lTmp = mac3216(lTmp, coeff[3], coeffLo[3], gCode, overflow);
                lTmp = mac32(lTmp, coeff[4], coeffLo[4], gPitCodH, gPitCodL, overflow);
                if (lTmp < distMin) {
                    distMin = lTmp;
                    codInd = i;
                    pitInd = j;
                }
            }
        }
        var p = (codInd << 2) - codInd;
        var gCode = table[p++];
        quantizer.quaEner[0] = table[p++];
        quantizer.quaEner[1] = table[p];
        var lTmp = lMult(gCode, gcode0, overflow);
        lTmp = lShr(lTmp, (short) (9 - expGcode0));
        gains[GAIN_COD] = (short) (lTmp >> 16);
        gains[GAIN_PIT] = gPitchCand[pitInd];
        return (gPitchCind[pitInd] << 16) | (codInd & 0xffff);
    }

    /**
     * Searches the codebook gain again with the adaptive smoothing criterion
     * ({@code MR795_gain_code_quant_mod}).
     *
     * @param gainPit the quantized pitch gain
     * @param expGcode0 the predicted codebook gain exponent
     * @param gcode0 the predicted codebook gain, Q14
     * @param alpha the smoothing factor, Q15
     * @param gainCodUnq the unquantized codebook gain
     * @param gains the quantized codebook gain in and out
     * @param overflow the saturation flag
     * @return the codebook gain index
     */
    private short mr795GainCodeQuantMod(short gainPit, short expGcode0, short gcode0, short alpha, short gainCodUnq,
                                        short[] gains, Flag overflow) {
        var gainCode = shl(gains[GAIN_COD], (short) (10 - expGcode0));
        var g2Pitch = mult(gainPit, gainPit, overflow);
        var oneAlpha = add((short) (32767 - alpha), (short) 1, overflow);
        var lT1 = lMult(alpha, fracEn[1], overflow);
        lT1 = lShl(lT1, (short) 1);
        var tmp = (short) (lT1 >> 16);
        lT1 = lMult(tmp, g2Pitch, overflow);
        expCoeffMod[1] = (short) (expEn[1] - 15);
        tmp = (short) (lShl(lMult(alpha, fracEn[2], overflow), (short) 1) >> 16);
        coeff[2] = mult(tmp, gainPit, overflow);
        var exp = (short) (expGcode0 - 10);
        expCoeffMod[2] = add(expEn[2], exp, overflow);
        coeff[3] = (short) (lShl(lMult(alpha, fracEn[3], overflow), (short) 1) >> 16);
        exp = (short) (shl(expGcode0, (short) 1) - 7);
        expCoeffMod[3] = add(expEn[3], exp, overflow);
        coeff[4] = mult(oneAlpha, fracEn[3], overflow);
        expCoeffMod[4] = add(expCoeffMod[3], (short) 1, overflow);
        var lTmp = lMult(alpha, fracEn[0], overflow);
        var lT0 = FixedMath.sqrtLExp(lTmp, sqrtExp, 0, overflow);
        exp = (short) (sqrtExp[0] + 47);
        expCoeffMod[0] = (short) (expEn[0] - exp);
        var eMax = (short) (expCoeffMod[0] + 31);
        for (var i = 1; i <= 4; i++) {
            if (expCoeffMod[i] > eMax) {
                eMax = expCoeffMod[i];
            }
        }
        tmp = (short) (eMax - expCoeffMod[1]);
        lT1 = lShr(lT1, tmp);
        for (var i = 2; i <= 4; i++) {
            tmp = (short) (eMax - expCoeffMod[i]);
            lTmp = coeff[i] << 16;
            lTmp = lShr(lTmp, tmp);
            coeff[i] = lExtractHi(lTmp);
            coeffLo[i] = lExtractLo(lTmp);
        }
        exp = (short) (eMax - 31);
        tmp = (short) (exp - expCoeffMod[0]);
        lT0 = lShr(lT0, shr(tmp, (short) 1, overflow));
        if ((tmp & 0x1) != 0) {
            coeff[0] = lExtractHi(lT0);
            coeffLo[0] = lExtractLo(lT0);
            lT0 = mpy3216(coeff[0], coeffLo[0], (short) 23170, overflow);
        }
        var distMin = Integer.MAX_VALUE;
        short index = 0;
        var table = GainTables.QUA_GAIN_CODE;
        var p = 0;
        for (var i = 0; i < NB_QUA_CODE; i++) {
            var gCode = table[p];
            p += 3;
            gCode = mult(gCode, gcode0, overflow);
            if (gCode >= gainCode) {
                break;
            }
            lTmp = lMult(gCode, gCode, overflow);
            var g2CodeH = lExtractHi(lTmp);
            var g2CodeL = lExtractLo(lTmp);
            tmp = sub(gCode, gainCodUnq, overflow);
            lTmp = lMult(tmp, tmp, overflow);
            var d2CodeH = lExtractHi(lTmp);
            var d2CodeL = lExtractLo(lTmp);
            lTmp = mac3216(lT1, coeff[2], coeffLo[2], gCode, overflow);
            lTmp = mac32(lTmp, coeff[3], coeffLo[3], g2CodeH, g2CodeL, overflow);
            lTmp = FixedMath.sqrtLExp(lTmp, sqrtExp, 0, overflow);
            lTmp = lShr(lTmp, shr(sqrtExp[0], (short) 1, overflow));
            tmp = round(lSub(lTmp, lT0, overflow), overflow);
            lTmp = lMult(tmp, tmp, overflow);
            lTmp = mac32(lTmp, coeff[4], coeffLo[4], d2CodeH, d2CodeL, overflow);
            if (lTmp < distMin) {
                distMin = lTmp;
                index = (short) i;
            }
        }
        p = (index << 2) - index;
        var gCode = table[p++];
        quantizer.quaEner[0] = table[p++];
        quantizer.quaEner[1] = table[p];
        lTmp = lMult(gCode, gcode0, overflow);
        lTmp = lShr(lTmp, (short) (9 - expGcode0));
        gains[GAIN_COD] = (short) (lTmp >> 16);
        return index;
    }
}
