/* Ports opencore-amr 0.1.6 (dec_amr.cpp, lsp_avg.cpp, b_cn_cod.cpp). Copyright PacketVideo; Apache License 2.0.
   Modified for Smalt. */
package com.github.auties00.smalt.audio.amr.internal.nb;

import java.util.Arrays;

import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.MAX_16;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.MIN_16;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.add;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.lMac;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.lMsu;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.lMult;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.lShl;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.lShr;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.mult;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.round;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.shl;
import static com.github.auties00.smalt.audio.amr.internal.nb.Constants.L_FRAME;
import static com.github.auties00.smalt.audio.amr.internal.nb.Constants.L_FRAME_BY2;
import static com.github.auties00.smalt.audio.amr.internal.nb.Constants.L_INTERPOL;
import static com.github.auties00.smalt.audio.amr.internal.nb.Constants.L_SUBFR;
import static com.github.auties00.smalt.audio.amr.internal.nb.Constants.M;
import static com.github.auties00.smalt.audio.amr.internal.nb.Constants.MP1;
import static com.github.auties00.smalt.audio.amr.internal.nb.Constants.PIT_MAX;
import static com.github.auties00.smalt.audio.amr.internal.nb.Constants.PIT_MIN;
import static com.github.auties00.smalt.audio.amr.internal.nb.Constants.PIT_MIN_MR122;
import static com.github.auties00.smalt.audio.amr.internal.nb.Constants.SHARPMAX;
import static com.github.auties00.smalt.audio.amr.internal.nb.Constants.SHARPMIN;

/**
 * Decodes the parameters of one AMR-NB frame into 160 samples of synthesized speech, before
 * the postfilter ({@code Decoder_amrState}).
 *
 * <p>The decoder handles every mode, comfort noise and the bad frame handling of 3GPP TS 26.091:
 * a lost frame is reconstructed from the previous parameters with an attenuation driven by a
 * 7-level error state, and the stationary noise detector smooths the reconstruction in background
 * noise.
 *
 * @implNote This implementation never rescales the excitation after the synthesis filtering: the reference does so when
 * the saturation flag is set, but its synthesis filter saturates internally and never sets the flag.
 */
final class DecoderCore {

    /**
     * The scratch of the integer filters.
     */
    private final int[] work = new int[96];
    /**
     * The index of the current subframe's excitation in {@link #oldExc}.
     */
    private static final int EXC = PIT_MAX + L_INTERPOL;

    /**
     * The number of pitch gains and excitation energies kept.
     */
    private static final int HISTORY = 9;

    /**
     * The averaging factor of the LSP mean, Q15.
     */
    private static final short EXPCONST = 5243;

    /**
     * The past excitation followed by the excitation of the current subframe.
     */
    final short[] oldExc = new short[L_SUBFR + PIT_MAX + L_INTERPOL];

    /**
     * The LSPs of the previous frame.
     */
    final short[] lspOld = new short[M];

    /**
     * The memory of the synthesis filter.
     */
    final short[] memSyn = new short[M];

    /**
     * The pitch sharpening gain.
     */
    short sharp;

    /**
     * The integer lag of the previous subframe.
     */
    short oldT0;

    /**
     * Whether the previous frame was lost.
     */
    short prevBf;

    /**
     * Whether the previous frame was potentially degraded.
     */
    short prevPdf;

    /**
     * The error state, 0 (good) to 6.
     */
    short state;

    /**
     * The excitation energies of the last subframes.
     */
    final short[] excEnergyHist = new short[HISTORY];

    /**
     * The last received lag.
     */
    short t0LagBuff;

    /**
     * Whether the last frame was background noise.
     */
    short inBackgroundNoise;

    /**
     * The frames since the last voiced frame.
     */
    short voicedHangover;

    /**
     * The pitch gains of the last subframes.
     */
    final short[] ltpGainHistory = new short[HISTORY];

    /**
     * The background noise detector.
     */
    final BackgroundNoise background = new BackgroundNoise();

    /**
     * The seed of the no-data parameter generator.
     */
    short nodataSeed;

    /**
     * The codebook gain averaging.
     */
    final CodebookGainAverage cbGainAver = new CodebookGainAverage();

    /**
     * The smoothed LSFs ({@code lsp_avgState}).
     */
    final short[] lspMeanSave = new short[M];

    /**
     * The LSF decoder.
     */
    final LsfDecoder lsfState = new LsfDecoder();

    /**
     * The gain concealment.
     */
    final GainConcealment ecGains = new GainConcealment();

    /**
     * The gain predictor.
     */
    final GcPred predState = new GcPred();

    /**
     * The phase dispersion.
     */
    final PhaseDispersion phDisp = new PhaseDispersion();

    /**
     * The LP analysis tools.
     */
    final Lpc lpc = new Lpc();

    /**
     * The comfort noise decoder.
     */
    final DtxDecoder dtx = new DtxDecoder(lpc);

    /**
     * The saturation flag shared by the decoder, the postfilter and the high-pass filter.
     */
    final Flag overflow = new Flag();

    /**
     * The LSPs of the current frame, scratch.
     */
    private final short[] lspNew = new short[M];

    /**
     * The LSPs of the second subframe of {@code MR122}, scratch.
     */
    private final short[] lspMid = new short[M];

    /**
     * The LSFs of the previous frame, scratch.
     */
    private final short[] prevLsf = new short[M];

    /**
     * The interpolated LSFs of a subframe, scratch.
     */
    private final short[] lsfI = new short[M];

    /**
     * The innovation of a subframe, scratch.
     */
    private final short[] code = new short[L_SUBFR];

    /**
     * The pitch-enhanced excitation, scratch.
     */
    private final short[] excp = new short[L_SUBFR];

    /**
     * The enhanced excitation of a subframe, scratch.
     */
    private final short[] excEnhanced = new short[L_SUBFR];

    /**
     * The gain predictor outputs, scratch.
     */
    private final short[] predScratch = new short[4];

    /**
     * The decompressed {@code MR102} pulse positions, scratch.
     */
    private final short[] positions = new short[8];

    /**
     * The square-root exponent, scratch.
     */
    private final short[] exponent = new short[1];

    /**
     * Creates the decoder in its home state ({@code Decoder_amr_init}).
     */
    DecoderCore() {
        t0LagBuff = 40;
        reset(Mode.MR475);
    }

    /**
     * Resets the decoder; for {@link Mode#MRDTX}, the reset that precedes comfort noise keeps
     * the synthesis memory, the LSPs, the energy histories and the gain predictor
     * ({@code Decoder_amr_reset}).
     *
     * @param mode {@link Mode#MRDTX} for the comfort noise reset, any other mode for a full one
     */
    void reset(int mode) {
        Arrays.fill(oldExc, 0, PIT_MAX + L_INTERPOL, (short) 0);
        if (mode != Mode.MRDTX) {
            Arrays.fill(memSyn, (short) 0);
        }
        sharp = SHARPMIN;
        oldT0 = 40;
        overflow.value = false;
        if (mode != Mode.MRDTX) {
            System.arraycopy(CommonTables.LSP_INIT_DATA, 0, lspOld, 0, M);
        }
        prevBf = 0;
        prevPdf = 0;
        state = 0;
        t0LagBuff = 40;
        inBackgroundNoise = 0;
        voicedHangover = 0;
        if (mode != Mode.MRDTX) {
            Arrays.fill(excEnergyHist, (short) 0);
        }
        Arrays.fill(ltpGainHistory, (short) 0);
        cbGainAver.reset();
        if (mode != Mode.MRDTX) {
            System.arraycopy(Lsf5Tables.MEAN_LSF_5, 0, lspMeanSave, 0, M);
        }
        lsfState.reset();
        ecGains.resetPitch();
        ecGains.resetCode();
        if (mode != Mode.MRDTX) {
            predState.reset();
        }
        background.reset();
        nodataSeed = 21845;
        phDisp.reset();
        if (mode != Mode.MRDTX) {
            dtx.reset();
        }
    }

    /**
     * Updates the smoothed LSFs ({@code lsp_avg}).
     *
     * @param lsp the LSFs of the frame
     */
    private void averageLsp(short[] lsp) {
        for (var i = 0; i < M; i++) {
            var acc = lspMeanSave[i] << 16;
            acc = lMsu(acc, EXPCONST, lspMeanSave[i], overflow);
            acc = lMac(acc, EXPCONST, lsp[i], overflow);
            lspMeanSave[i] = round(acc, overflow);
        }
    }

    /**
     * Saturates twice a gain to 16 bits.
     *
     * @param value the gain
     * @return {@code 2 * value}, saturated
     */
    private static short doubled(short value) {
        var twice = value << 1;
        if (twice != (short) twice) {
            return value > 0 ? MAX_16 : MIN_16;
        }
        return (short) twice;
    }

    /**
     * Decodes one frame ({@code Decoder_amr}).
     *
     * @param mode the mode, 0 to 7
     * @param parm the frame parameters, at least 57; a lost frame's may be overwritten
     * @param frameType the RX frame type
     * @param synth receives the 160 synthesized samples
     * @param aT receives the 4 LP filters of the frame, 44 coefficients
     */
    void decode(int mode, short[] parm, int frameType, short[] synth, short[] aT) {
        var newDtxState = dtx.handle(frameType, overflow);
        if (newDtxState != DtxDecoder.SPEECH) {
            reset(Mode.MRDTX);
            dtx.decode(memSyn, lsfState, predState, cbGainAver, newDtxState, mode, parm, 0, synth, aT, overflow);
            Lsf.lsfLsp(lsfState.pastLsfQ, 0, lspOld, 0, M);
            averageLsp(lsfState.pastLsfQ);
            dtx.dtxGlobalState = newDtxState;
            return;
        }
        short bfi = 0;
        short pdfi = 0;
        if (frameType == FrameType.RX_SPEECH_BAD || frameType == FrameType.RX_NO_DATA
                || frameType == FrameType.RX_ONSET) {
            bfi = 1;
            if (frameType == FrameType.RX_NO_DATA || frameType == FrameType.RX_ONSET) {
                nodataSeed = DtxDecoder.buildComfortNoiseParameters(nodataSeed, mode, parm, overflow);
            }
        } else if (frameType == FrameType.RX_SPEECH_DEGRADED) {
            pdfi = 1;
        }
        if (bfi != 0) {
            state += 1;
        } else if (state == 6) {
            state = 5;
        } else {
            state = 0;
        }
        if (state > 6) {
            state = 6;
        }
        if (dtx.dtxGlobalState == DtxDecoder.DTX) {
            state = 5;
            prevBf = 0;
        } else if (dtx.dtxGlobalState == DtxDecoder.DTX_MUTE) {
            state = 5;
            prevBf = 1;
        }
        System.arraycopy(lsfState.pastLsfQ, 0, prevLsf, 0, M);
        var p = 0;
        if (mode != Mode.MR122) {
            lsfState.decode3(mode, bfi, parm, p, lspNew, 0, overflow);
            p += 3;
            lpc.intLpc1to3(lspOld, 0, lspNew, 0, aT, 0);
        } else {
            lsfState.decode5(bfi, parm, p, lspMid, 0, lspNew, 0, overflow);
            p += 5;
            lpc.intLpc1and3(lspOld, 0, lspMid, 0, lspNew, 0, aT, 0);
        }
        System.arraycopy(lspNew, 0, lspOld, 0, M);
        var az = 0;
        var evenSubfr = 0;
        short subfrNr = -1;
        short t0 = 0;
        short t0Frac = 0;
        short indexMr475 = 0;
        short gainPit = 0;
        short gainCode = 0;
        for (var iSubfr = 0; iSubfr < L_FRAME; iSubfr += L_SUBFR) {
            subfrNr += 1;
            evenSubfr = 1 - evenSubfr;
            var pitFlag = iSubfr;
            if (iSubfr == L_FRAME_BY2 && mode != Mode.MR475 && mode != Mode.MR515) {
                pitFlag = 0;
            }
            var index = parm[p++];
            if (mode != Mode.MR122) {
                var flag4 = mode == Mode.MR475 || mode == Mode.MR515 || mode == Mode.MR59 || mode == Mode.MR67;
                short deltaFrcLow = 5;
                short deltaFrcRange = 9;
                if (mode == Mode.MR795) {
                    deltaFrcLow = 10;
                    deltaFrcRange = 19;
                }
                var t0Min = (short) (oldT0 - deltaFrcLow);
                if (t0Min < PIT_MIN) {
                    t0Min = PIT_MIN;
                }
                var t0Max = (short) (t0Min + deltaFrcRange);
                if (t0Max > PIT_MAX) {
                    t0Max = PIT_MAX;
                    t0Min = (short) (t0Max - deltaFrcRange);
                }
                var lag = LagDecoder.decLag3(index, t0Min, t0Max, pitFlag, oldT0, flag4, overflow);
                t0 = (short) (lag >> 16);
                t0Frac = (short) lag;
                t0LagBuff = t0;
                if (bfi != 0) {
                    if (oldT0 < PIT_MAX) {
                        oldT0 += 1;
                    }
                    t0 = oldT0;
                    t0Frac = 0;
                    if (inBackgroundNoise != 0 && voicedHangover > 4
                            && (mode == Mode.MR475 || mode == Mode.MR515 || mode == Mode.MR59)) {
                        t0 = t0LagBuff;
                    }
                }
                Filters.predLt3or6(oldExc, EXC, t0, t0Frac, L_SUBFR, true, work);
            } else {
                var lag = LagDecoder.decLag6(index, (short) PIT_MIN_MR122, (short) PIT_MAX, pitFlag, t0);
                t0 = (short) (lag >> 16);
                t0Frac = (short) lag;
                if (!(bfi == 0 && (pitFlag == 0 || index < 61))) {
                    t0LagBuff = t0;
                    t0 = oldT0;
                    t0Frac = 0;
                }
                Filters.predLt3or6(oldExc, EXC, t0, t0Frac, L_SUBFR, false, work);
            }
            short pitSharp;
            if (mode == Mode.MR475 || mode == Mode.MR515) {
                index = parm[p++];
                var signs = parm[p++];
                FixedCodebookDecoder.decode2i40_9bits(subfrNr, signs, index, code, overflow);
                pitSharp = doubled(sharp);
            } else if (mode == Mode.MR59) {
                index = parm[p++];
                var signs = parm[p++];
                FixedCodebookDecoder.decode2i40_11bits(signs, index, code);
                pitSharp = doubled(sharp);
            } else if (mode == Mode.MR67) {
                index = parm[p++];
                var signs = parm[p++];
                FixedCodebookDecoder.decode3i40_14bits(signs, index, code);
                pitSharp = doubled(sharp);
            } else if (mode <= Mode.MR795) {
                index = parm[p++];
                var signs = parm[p++];
                FixedCodebookDecoder.decode4i40_17bits(signs, index, code);
                pitSharp = doubled(sharp);
            } else if (mode == Mode.MR102) {
                FixedCodebookDecoder.dec8i40_31bits(parm, p, code, positions, overflow);
                p += 7;
                pitSharp = doubled(sharp);
            } else {
                index = parm[p++];
                if (bfi != 0) {
                    gainPit = ecGains.pitch(state, overflow);
                } else {
                    gainPit = GainDecoder.decGainPitch(mode, index);
                }
                gainPit = ecGains.pitchUpdate(bfi, prevBf, gainPit, overflow);
                FixedCodebookDecoder.dec10i40_35bits(parm, p, code);
                p += 10;
                pitSharp = doubled(gainPit);
            }
            for (var i = t0; i < L_SUBFR; i++) {
                var temp = mult(code[i - t0], pitSharp, overflow);
                code[i] = add(code[i], temp, overflow);
            }
            if (mode == Mode.MR475) {
                if (evenSubfr != 0) {
                    indexMr475 = parm[p++];
                }
                if (bfi == 0) {
                    var gains = GainDecoder.decGain(predState, mode, indexMr475, code, evenSubfr, predScratch, overflow);
                    gainPit = (short) (gains >> 16);
                    gainCode = (short) gains;
                } else {
                    gainPit = ecGains.pitch(state, overflow);
                    gainCode = ecGains.code(predState, state, overflow);
                }
                gainPit = ecGains.pitchUpdate(bfi, prevBf, gainPit, overflow);
                gainCode = ecGains.codeUpdate(bfi, prevBf, gainCode, overflow);
                pitSharp = gainPit;
                if (pitSharp > SHARPMAX) {
                    pitSharp = SHARPMAX;
                }
            } else if (mode <= Mode.MR74 || mode == Mode.MR102) {
                index = parm[p++];
                if (bfi == 0) {
                    var gains = GainDecoder.decGain(predState, mode, index, code, evenSubfr, predScratch, overflow);
                    gainPit = (short) (gains >> 16);
                    gainCode = (short) gains;
                } else {
                    gainPit = ecGains.pitch(state, overflow);
                    gainCode = ecGains.code(predState, state, overflow);
                }
                gainPit = ecGains.pitchUpdate(bfi, prevBf, gainPit, overflow);
                gainCode = ecGains.codeUpdate(bfi, prevBf, gainCode, overflow);
                pitSharp = gainPit;
                if (pitSharp > SHARPMAX) {
                    pitSharp = SHARPMAX;
                }
                if (mode == Mode.MR102 && oldT0 > L_SUBFR + 5) {
                    pitSharp = (short) (pitSharp >> 2);
                }
            } else {
                index = parm[p++];
                if (mode == Mode.MR795) {
                    if (bfi != 0) {
                        gainPit = ecGains.pitch(state, overflow);
                    } else {
                        gainPit = GainDecoder.decGainPitch(mode, index);
                    }
                    gainPit = ecGains.pitchUpdate(bfi, prevBf, gainPit, overflow);
                    index = parm[p++];
                    if (bfi == 0) {
                        gainCode = GainDecoder.decGainCode(predState, mode, index, code, predScratch, overflow);
                    } else {
                        gainCode = ecGains.code(predState, state, overflow);
                    }
                    gainCode = ecGains.codeUpdate(bfi, prevBf, gainCode, overflow);
                    pitSharp = gainPit;
                    if (pitSharp > SHARPMAX) {
                        pitSharp = SHARPMAX;
                    }
                } else {
                    if (bfi == 0) {
                        gainCode = GainDecoder.decGainCode(predState, mode, index, code, predScratch, overflow);
                    } else {
                        gainCode = ecGains.code(predState, state, overflow);
                    }
                    gainCode = ecGains.codeUpdate(bfi, prevBf, gainCode, overflow);
                    pitSharp = gainPit;
                }
            }
            if (mode != Mode.MR475 || evenSubfr == 0) {
                sharp = gainPit;
                if (sharp > SHARPMAX) {
                    sharp = SHARPMAX;
                }
            }
            pitSharp = shl(pitSharp, (short) 1);
            if (pitSharp > 16384) {
                for (var i = 0; i < L_SUBFR; i++) {
                    var temp = mult(oldExc[EXC + i], pitSharp, overflow);
                    var product = lMult(temp, gainPit, overflow);
                    if (mode == Mode.MR122) {
                        product >>= 1;
                    }
                    excp[i] = round(product, overflow);
                }
            }
            if (bfi == 0) {
                System.arraycopy(ltpGainHistory, 1, ltpGainHistory, 0, 8);
                ltpGainHistory[8] = gainPit;
            }
            if ((prevBf != 0 || bfi != 0) && inBackgroundNoise != 0
                    && (mode == Mode.MR475 || mode == Mode.MR515 || mode == Mode.MR59)) {
                if (gainPit > 12288) {
                    gainPit = (short) (((gainPit - 12288) >> 1) + 12288);
                }
                if (gainPit > 14745) {
                    gainPit = 14745;
                }
            }
            LsfDecoder.interpolate(prevLsf, lsfState.pastLsfQ, iSubfr, lsfI, overflow);
            var gainCodeMix = cbGainAver.average(mode, gainCode, lsfI, lspMeanSave, bfi, prevBf, pdfi, prevPdf,
                    inBackgroundNoise, voicedHangover, overflow);
            if (mode > Mode.MR67 && mode != Mode.MR102) {
                gainCodeMix = gainCode;
            }
            short pitchFac;
            short tmpShift;
            if (mode <= Mode.MR102) {
                pitchFac = gainPit;
                tmpShift = 1;
            } else {
                pitchFac = (short) (gainPit >> 1);
                tmpShift = 2;
            }
            for (var i = 0; i < L_SUBFR; i++) {
                excEnhanced[i] = oldExc[EXC + i];
                var sum = lMult(oldExc[EXC + i], pitchFac, overflow);
                sum = lMac(sum, code[i], gainCode, overflow);
                sum = lShl(sum, tmpShift);
                oldExc[EXC + i] = round(sum, overflow);
            }
            phDisp.release();
            if ((mode == Mode.MR475 || mode == Mode.MR515 || mode == Mode.MR59) && voicedHangover > 3
                    && inBackgroundNoise != 0 && bfi != 0) {
                phDisp.lock();
            }
            phDisp.disperse(mode, excEnhanced, gainCodeMix, gainPit, code, pitchFac, tmpShift, overflow);
            var energy = 0;
            for (var i = 0; i < L_SUBFR; i++) {
                energy = lMac(energy, excEnhanced[i], excEnhanced[i], overflow);
            }
            energy >>= 1;
            energy = FixedMath.sqrtLExp(energy, exponent, 0, overflow);
            energy = lShr(energy, (short) ((exponent[0] >> 1) + 15));
            var excEnergy = (short) (energy >> 2);
            if ((mode == Mode.MR475 || mode == Mode.MR515 || mode == Mode.MR59) && voicedHangover > 5
                    && inBackgroundNoise != 0 && state < 4
                    && ((pdfi != 0 && prevPdf != 0) || bfi != 0 || prevBf != 0)) {
                short carefulFlag = 0;
                if (pdfi != 0 && bfi == 0) {
                    carefulFlag = 1;
                }
                BackgroundNoise.controlExcitation(excEnhanced, excEnergy, excEnergyHist, voicedHangover, prevBf,
                        carefulFlag, overflow);
            }
            if (!(inBackgroundNoise != 0 && (bfi != 0 || prevBf != 0) && state < 4)) {
                System.arraycopy(excEnergyHist, 1, excEnergyHist, 0, 8);
                excEnergyHist[8] = excEnergy;
            }
            if (pitSharp > 16384) {
                for (var i = 0; i < L_SUBFR; i++) {
                    excp[i] = add(excp[i], excEnhanced[i], overflow);
                }
                Agc.applyInstant(excEnhanced, excp, L_SUBFR, overflow);
                overflow.value = false;
                Filters.synFilt(aT, az, excp, 0, synth, iSubfr, L_SUBFR, memSyn, 0, false, overflow);
            } else {
                overflow.value = false;
                Filters.synFilt(aT, az, excEnhanced, 0, synth, iSubfr, L_SUBFR, memSyn, 0, false, overflow);
            }
            if (overflow.value) {
                for (var i = PIT_MAX + L_INTERPOL + L_SUBFR - 1; i >= 0; i--) {
                    oldExc[i] = (short) (oldExc[i] >> 2);
                }
                for (var i = L_SUBFR - 1; i >= 0; i--) {
                    excEnhanced[i] = (short) (excEnhanced[i] >> 2);
                }
                Filters.synFilt(aT, az, excEnhanced, 0, synth, iSubfr, L_SUBFR, memSyn, 0, true, overflow);
            } else {
                System.arraycopy(synth, iSubfr + L_SUBFR - M, memSyn, 0, M);
            }
            System.arraycopy(oldExc, L_SUBFR, oldExc, 0, PIT_MAX + L_INTERPOL);
            az += MP1;
            oldT0 = t0;
        }
        var detected = background.detect(ltpGainHistory, synth, 0, voicedHangover, overflow);
        inBackgroundNoise = (short) (detected >> 16);
        voicedHangover = (short) detected;
        dtx.activityUpdate(lsfState.pastLsfQ, synth, overflow);
        prevBf = bfi;
        prevPdf = pdfi;
        averageLsp(lsfState.pastLsfQ);
        dtx.dtxGlobalState = newDtxState;
    }
}
