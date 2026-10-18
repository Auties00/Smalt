/* Ports libopus 1.5.2 (silk/float/encode_frame_FLP.c). Copyright Skype Limited; BSD 3-Clause License. Modified for
   Smalt. */
package com.github.auties00.smalt.audio.opus.internal.silk;

import com.github.auties00.smalt.audio.opus.internal.celt.RangeEncoder;

import java.util.Arrays;

import static com.github.auties00.smalt.audio.opus.internal.silk.Define.*;
import static com.github.auties00.smalt.audio.opus.internal.silk.SigProcFix.*;
import static com.github.auties00.smalt.audio.opus.internal.silk.TuningParameters.*;

/**
 * Encodes one SILK frame with the float encoder: voice activity, analysis, quantization, LBRR,
 * and the rate-control loop that searches the gain multiplier meeting the bit budget.
 */
final class EncodeFrameFlp {
    private EncodeFrameFlp() {
        throw new AssertionError();
    }

    /**
     * Runs the voice activity detector and converts its decision into the frame's signal type
     * and the DTX and VAD flags ({@code silk_encode_do_VAD_FLP}).
     *
     * @param psEnc    the channel state
     * @param activity the voice activity decision of the Opus layer
     */
    static void encodeDoVad(EncoderStateFlp psEnc, int activity) {
        final var activity_threshold = fixConst((double) SPEECH_ACTIVITY_DTX_THRES, 8);
        var sCmn = psEnc.sCmn;

        psEnc.vad.getSaQ8(sCmn, sCmn.inputBuf, 1);
        if (activity == VAD_NO_ACTIVITY && sCmn.speech_activity_Q8 >= activity_threshold) {
            sCmn.speech_activity_Q8 = activity_threshold - 1;
        }

        if (sCmn.speech_activity_Q8 < activity_threshold) {
            sCmn.indices.signalType = TYPE_NO_VOICE_ACTIVITY;
            sCmn.noSpeechCounter++;
            if (sCmn.noSpeechCounter <= NB_SPEECH_FRAMES_BEFORE_DTX) {
                sCmn.inDTX = 0;
            } else if (sCmn.noSpeechCounter > MAX_CONSECUTIVE_DTX + NB_SPEECH_FRAMES_BEFORE_DTX) {
                sCmn.noSpeechCounter = NB_SPEECH_FRAMES_BEFORE_DTX;
                sCmn.inDTX = 0;
            }
            sCmn.VAD_flags[sCmn.nFramesEncoded] = 0;
        } else {
            sCmn.noSpeechCounter = 0;
            sCmn.inDTX = 0;
            sCmn.indices.signalType = TYPE_UNVOICED;
            sCmn.VAD_flags[sCmn.nFramesEncoded] = 1;
        }
    }

    /**
     * Encodes one frame ({@code silk_encode_frame_FLP}).
     *
     * @param psEnc      the channel state
     * @param pnBytesOut receives the number of payload bytes at index 0
     * @param psRangeEnc the range encoder, or {@code null} while prefilling
     * @param condCoding the type of conditional coding
     * @param maxBits    the maximum number of output bits, if positive
     * @param useCBR     nonzero to force constant-bitrate operation
     * @return {@code 0}
     */
    static int encodeFrame(EncoderStateFlp psEnc, int[] pnBytesOut, RangeEncoder psRangeEnc, int condCoding, int maxBits,
                           int useCBR) {
        var sEncCtrl = psEnc.sEncCtrl;
        var sCmn = psEnc.sCmn;
        var s = psEnc.scratch;
        int i, iter, maxIter, found_upper, found_lower, ret = 0;
        var res_pitch = s.res_pitch;
        var sRangeEnc_copy = s.sRangeEnc_copy;
        var sRangeEnc_copy2 = s.sRangeEnc_copy2;
        var sNSQ_copy = s.sNSQ_copy;
        var sNSQ_copy2 = s.sNSQ_copy2;
        int seed_copy, nBits, nBits_lower, nBits_upper, gainMult_lower, gainMult_upper;
        int gainsID, gainsID_lower, gainsID_upper;
        short gainMult_Q8;
        short ec_prevLagIndex_copy;
        int ec_prevSignalType_copy;
        byte LastGainIndex_copy2;
        var pGains_Q16 = s.pGains_Q16;
        var ec_buf_copy = s.ec_buf_copy;
        var gain_lock = s.gain_lock;
        var best_gain_mult = s.best_gain_mult;
        var best_sum = s.best_sum;
        int bits_margin;

        Arrays.fill(gain_lock, 0);

        bits_margin = useCBR != 0 ? 5 : maxBits / 4;
        LastGainIndex_copy2 = 0;
        nBits_lower = nBits_upper = gainMult_lower = gainMult_upper = 0;

        sCmn.indices.Seed = (byte) (sCmn.frameCounter++ & 3);

        var x_frame = sCmn.ltp_mem_length;
        var res_pitch_frame = sCmn.ltp_mem_length;

        VariableCutoff.lpVariableCutoff(sCmn.sLP, sCmn.inputBuf, 1, sCmn.frame_length);

        SigProcFlp.short2floatArray(psEnc.x_buf, x_frame + LA_SHAPE_MS * sCmn.fs_kHz, sCmn.inputBuf, 1, sCmn.frame_length);

        for (i = 0; i < 8; i++) {
            psEnc.x_buf[x_frame + LA_SHAPE_MS * sCmn.fs_kHz + i * (sCmn.frame_length >> 3)] += (1 - (i & 2)) * 1e-6f;
        }

        if (sCmn.prefillFlag == 0) {
            FindPitchLagsFlp.findPitchLags(psEnc, sEncCtrl, res_pitch, psEnc.x_buf, x_frame);

            NoiseShapeAnalysisFlp.noiseShapeAnalysis(psEnc, sEncCtrl, res_pitch, res_pitch_frame, psEnc.x_buf, x_frame);

            FindPredCoefsFlp.findPredCoefs(psEnc, sEncCtrl, res_pitch, res_pitch_frame, psEnc.x_buf, x_frame, condCoding);

            ProcessGainsFlp.processGains(psEnc, sEncCtrl, condCoding);

            lbrrEncode(psEnc, sEncCtrl, psEnc.x_buf, x_frame, condCoding);

            maxIter = 6;
            gainMult_Q8 = (short) fixConst(1, 8);
            found_lower = 0;
            found_upper = 0;
            gainsID = GainQuant.gainsID(sCmn.indices.GainsIndices, sCmn.nb_subfr);
            gainsID_lower = -1;
            gainsID_upper = -1;
            sRangeEnc_copy.copyFrom(psRangeEnc);
            sNSQ_copy.copyFrom(sCmn.sNSQ);
            seed_copy = sCmn.indices.Seed;
            ec_prevLagIndex_copy = sCmn.ec_prevLagIndex;
            ec_prevSignalType_copy = sCmn.ec_prevSignalType;
            for (iter = 0; ; iter++) {
                if (gainsID == gainsID_lower) {
                    nBits = nBits_lower;
                } else if (gainsID == gainsID_upper) {
                    nBits = nBits_upper;
                } else {
                    if (iter > 0) {
                        psRangeEnc.copyFrom(sRangeEnc_copy);
                        sCmn.sNSQ.copyFrom(sNSQ_copy);
                        sCmn.indices.Seed = (byte) seed_copy;
                        sCmn.ec_prevLagIndex = ec_prevLagIndex_copy;
                        sCmn.ec_prevSignalType = ec_prevSignalType_copy;
                    }

                    WrappersFlp.nsqWrapperFlp(psEnc, sEncCtrl, sCmn.indices, sCmn.sNSQ, sCmn.pulses, psEnc.x_buf, x_frame);

                    if (iter == maxIter && found_lower == 0) {
                        sRangeEnc_copy2.copyFrom(psRangeEnc);
                    }

                    psEnc.encodeIndices.encodeIndices(sCmn, psRangeEnc, sCmn.nFramesEncoded, 0, condCoding);

                    psEnc.encodePulses.encodePulses(psRangeEnc, sCmn.indices.signalType, sCmn.indices.quantOffsetType,
                            sCmn.pulses, sCmn.frame_length);

                    nBits = psRangeEnc.tell();

                    if (iter == maxIter && found_lower == 0 && nBits > maxBits) {
                        psRangeEnc.copyFrom(sRangeEnc_copy2);

                        psEnc.sShape.LastGainIndex = sEncCtrl.lastGainIndexPrev;
                        for (i = 0; i < sCmn.nb_subfr; i++) {
                            sCmn.indices.GainsIndices[i] = 4;
                        }
                        if (condCoding != CODE_CONDITIONALLY) {
                            sCmn.indices.GainsIndices[0] = sEncCtrl.lastGainIndexPrev;
                        }
                        sCmn.ec_prevLagIndex = ec_prevLagIndex_copy;
                        sCmn.ec_prevSignalType = ec_prevSignalType_copy;
                        for (i = 0; i < sCmn.frame_length; i++) {
                            sCmn.pulses[i] = 0;
                        }

                        psEnc.encodeIndices.encodeIndices(sCmn, psRangeEnc, sCmn.nFramesEncoded, 0, condCoding);

                        psEnc.encodePulses.encodePulses(psRangeEnc, sCmn.indices.signalType, sCmn.indices.quantOffsetType,
                                sCmn.pulses, sCmn.frame_length);

                        nBits = psRangeEnc.tell();
                    }

                    if (useCBR == 0 && iter == 0 && nBits <= maxBits) {
                        break;
                    }
                }

                if (iter == maxIter) {
                    if (found_lower != 0 && (gainsID == gainsID_lower || nBits > maxBits)) {
                        psRangeEnc.copyFrom(sRangeEnc_copy2);
                        System.arraycopy(ec_buf_copy, 0, psRangeEnc.buf, psRangeEnc.base, sRangeEnc_copy2.offs);
                        sCmn.sNSQ.copyFrom(sNSQ_copy2);
                        psEnc.sShape.LastGainIndex = LastGainIndex_copy2;
                    }
                    break;
                }

                if (nBits > maxBits) {
                    if (found_lower == 0 && iter >= 2) {
                        var lambda = sEncCtrl.Lambda * 1.5f;
                        sEncCtrl.Lambda = lambda > 1.5f ? lambda : 1.5f;
                        sCmn.indices.quantOffsetType = 0;
                        found_upper = 0;
                        gainsID_upper = -1;
                    } else {
                        found_upper = 1;
                        nBits_upper = nBits;
                        gainMult_upper = gainMult_Q8;
                        gainsID_upper = gainsID;
                    }
                } else if (nBits < maxBits - bits_margin) {
                    found_lower = 1;
                    nBits_lower = nBits;
                    gainMult_lower = gainMult_Q8;
                    if (gainsID != gainsID_lower) {
                        gainsID_lower = gainsID;
                        sRangeEnc_copy2.copyFrom(psRangeEnc);
                        System.arraycopy(psRangeEnc.buf, psRangeEnc.base, ec_buf_copy, 0, psRangeEnc.offs);
                        sNSQ_copy2.copyFrom(sCmn.sNSQ);
                        LastGainIndex_copy2 = psEnc.sShape.LastGainIndex;
                    }
                } else {
                    break;
                }

                if (found_lower == 0 && nBits > maxBits) {
                    int j;
                    for (i = 0; i < sCmn.nb_subfr; i++) {
                        var sum = 0;
                        for (j = i * sCmn.subfr_length; j < (i + 1) * sCmn.subfr_length; j++) {
                            sum += Math.abs(sCmn.pulses[j]);
                        }
                        if (iter == 0 || (sum < best_sum[i] && gain_lock[i] == 0)) {
                            best_sum[i] = sum;
                            best_gain_mult[i] = gainMult_Q8;
                        } else {
                            gain_lock[i] = 1;
                        }
                    }
                }
                if ((found_lower & found_upper) == 0) {
                    if (nBits > maxBits) {
                        gainMult_Q8 = (short) Math.min(1024, gainMult_Q8 * 3 / 2);
                    } else {
                        gainMult_Q8 = (short) Math.max(64, gainMult_Q8 * 4 / 5);
                    }
                } else {
                    gainMult_Q8 = (short) (gainMult_lower + ((gainMult_upper - gainMult_lower) * (maxBits - nBits_lower))
                            / (nBits_upper - nBits_lower));
                    if (gainMult_Q8 > gainMult_lower + ((gainMult_upper - gainMult_lower) >> 2)) {
                        gainMult_Q8 = (short) (gainMult_lower + ((gainMult_upper - gainMult_lower) >> 2));
                    } else if (gainMult_Q8 < gainMult_upper - ((gainMult_upper - gainMult_lower) >> 2)) {
                        gainMult_Q8 = (short) (gainMult_upper - ((gainMult_upper - gainMult_lower) >> 2));
                    }
                }

                for (i = 0; i < sCmn.nb_subfr; i++) {
                    short tmp;
                    if (gain_lock[i] != 0) {
                        tmp = best_gain_mult[i];
                    } else {
                        tmp = gainMult_Q8;
                    }
                    pGains_Q16[i] = lshiftSat32(smulwb(sEncCtrl.GainsUnq_Q16[i], tmp), 8);
                }

                psEnc.sShape.LastGainIndex = sEncCtrl.lastGainIndexPrev;
                psEnc.sShape.LastGainIndex = GainQuant.gainsQuant(sCmn.indices.GainsIndices, pGains_Q16,
                        psEnc.sShape.LastGainIndex, condCoding == CODE_CONDITIONALLY ? 1 : 0, sCmn.nb_subfr);

                gainsID = GainQuant.gainsID(sCmn.indices.GainsIndices, sCmn.nb_subfr);

                for (i = 0; i < sCmn.nb_subfr; i++) {
                    sEncCtrl.Gains[i] = pGains_Q16[i] / 65536.0f;
                }
            }
        }

        System.arraycopy(psEnc.x_buf, sCmn.frame_length, psEnc.x_buf, 0, sCmn.ltp_mem_length + LA_SHAPE_MS * sCmn.fs_kHz);

        if (sCmn.prefillFlag != 0) {
            pnBytesOut[0] = 0;
            return ret;
        }

        sCmn.prevLag = sEncCtrl.pitchL[sCmn.nb_subfr - 1];
        sCmn.prevSignalType = sCmn.indices.signalType;

        sCmn.first_frame_after_reset = 0;
        pnBytesOut[0] = (psRangeEnc.tell() + 7) >> 3;

        return ret;
    }

    /**
     * Encodes the low-bitrate redundancy of a frame by requantizing its excitation with larger
     * gains ({@code silk_LBRR_encode_FLP}).
     *
     * @param psEnc      the channel state
     * @param psEncCtrl  the frame control
     * @param xfw        the analysis buffer
     * @param xfwOff     the index of the first sample of the frame
     * @param condCoding the type of conditional coding used for the frame
     */
    private static void lbrrEncode(EncoderStateFlp psEnc, EncoderControlFlp psEncCtrl, float[] xfw, int xfwOff,
                                   int condCoding) {
        int k;
        var sCmn = psEnc.sCmn;
        var s = psEnc.scratch;
        var Gains_Q16 = s.Gains_Q16;
        var TempGains = s.TempGains;
        var psIndices_LBRR = sCmn.indices_LBRR[sCmn.nFramesEncoded];
        var sNSQ_LBRR = s.sNSQ_LBRR;

        if (sCmn.LBRR_enabled != 0 && sCmn.speech_activity_Q8 > fixConst((double) LBRR_SPEECH_ACTIVITY_THRES, 8)) {
            sCmn.LBRR_flags[sCmn.nFramesEncoded] = 1;

            sNSQ_LBRR.copyFrom(sCmn.sNSQ);
            psIndices_LBRR.copyFrom(sCmn.indices);

            System.arraycopy(psEncCtrl.Gains, 0, TempGains, 0, sCmn.nb_subfr);

            if (sCmn.nFramesEncoded == 0 || sCmn.LBRR_flags[sCmn.nFramesEncoded - 1] == 0) {
                sCmn.LBRRprevLastGainIndex = psEnc.sShape.LastGainIndex;

                psIndices_LBRR.GainsIndices[0] += (byte) sCmn.LBRR_GainIncreases;
                psIndices_LBRR.GainsIndices[0] = (byte) Math.min(psIndices_LBRR.GainsIndices[0], N_LEVELS_QGAIN - 1);
            }

            sCmn.LBRRprevLastGainIndex = GainQuant.gainsDequant(Gains_Q16, psIndices_LBRR.GainsIndices,
                    sCmn.LBRRprevLastGainIndex, condCoding == CODE_CONDITIONALLY ? 1 : 0, sCmn.nb_subfr);

            for (k = 0; k < sCmn.nb_subfr; k++) {
                psEncCtrl.Gains[k] = Gains_Q16[k] * (1.0f / 65536.0f);
            }

            WrappersFlp.nsqWrapperFlp(psEnc, psEncCtrl, psIndices_LBRR, sNSQ_LBRR, sCmn.pulses_LBRR[sCmn.nFramesEncoded],
                    xfw, xfwOff);

            System.arraycopy(TempGains, 0, psEncCtrl.Gains, 0, sCmn.nb_subfr);
        }
    }
}
