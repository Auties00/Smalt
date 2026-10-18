/* Ports libopus 1.5.2 (silk/float/process_gains_FLP.c). Copyright Skype Limited; BSD 3-Clause License. Modified for
   Smalt. */
package com.github.auties00.smalt.audio.opus.internal.silk;

import static com.github.auties00.smalt.audio.opus.internal.silk.Define.*;
import static com.github.auties00.smalt.audio.opus.internal.silk.TuningParameters.*;

/**
 * Limits and quantizes the gains of a SILK frame and sets the quantizer offset type and the
 * rate/distortion trade-off.
 */
final class ProcessGainsFlp {
    private ProcessGainsFlp() {
        throw new AssertionError();
    }

    /**
     * Processes the gains of a frame ({@code silk_process_gains_FLP}).
     *
     * @param psEnc      the channel state
     * @param psEncCtrl  the frame control
     * @param condCoding the type of conditional coding
     */
    static void processGains(EncoderStateFlp psEnc, EncoderControlFlp psEncCtrl, int condCoding) {
        var psShapeSt = psEnc.sShape;
        var sCmn = psEnc.sCmn;
        int k;
        var pGains_Q16 = psEnc.scratch.pGains_Q16;
        float s, InvMaxSqrVal, gain, quant_offset;

        if (sCmn.indices.signalType == TYPE_VOICED) {
            s = 1.0f - 0.5f * SigProcFlp.sigmoid(0.25f * (psEncCtrl.LTPredCodGain - 12.0f));
            for (k = 0; k < sCmn.nb_subfr; k++) {
                psEncCtrl.Gains[k] *= s;
            }
        }

        InvMaxSqrVal = (float) (StrictMath.pow(2.0f, 0.33f * (21.0f - sCmn.SNR_dB_Q7 * (1 / 128.0f))) / sCmn.subfr_length);

        for (k = 0; k < sCmn.nb_subfr; k++) {
            gain = psEncCtrl.Gains[k];
            gain = (float) Math.sqrt(gain * gain + psEncCtrl.ResNrg[k] * InvMaxSqrVal);
            psEncCtrl.Gains[k] = gain < 32767.0f ? gain : 32767.0f;
        }

        for (k = 0; k < sCmn.nb_subfr; k++) {
            pGains_Q16[k] = (int) (psEncCtrl.Gains[k] * 65536.0f);
        }

        System.arraycopy(pGains_Q16, 0, psEncCtrl.GainsUnq_Q16, 0, sCmn.nb_subfr);
        psEncCtrl.lastGainIndexPrev = psShapeSt.LastGainIndex;

        psShapeSt.LastGainIndex = GainQuant.gainsQuant(sCmn.indices.GainsIndices, pGains_Q16,
                psShapeSt.LastGainIndex, condCoding == CODE_CONDITIONALLY ? 1 : 0, sCmn.nb_subfr);

        for (k = 0; k < sCmn.nb_subfr; k++) {
            psEncCtrl.Gains[k] = pGains_Q16[k] / 65536.0f;
        }

        if (sCmn.indices.signalType == TYPE_VOICED) {
            if (psEncCtrl.LTPredCodGain + sCmn.input_tilt_Q15 * (1.0f / 32768.0f) > 1.0f) {
                sCmn.indices.quantOffsetType = 0;
            } else {
                sCmn.indices.quantOffsetType = 1;
            }
        }

        quant_offset = Tables.silk_Quantization_Offsets_Q10[sCmn.indices.signalType >> 1][sCmn.indices.quantOffsetType] / 1024.0f;
        psEncCtrl.Lambda = LAMBDA_OFFSET
                + LAMBDA_DELAYED_DECISIONS * sCmn.nStatesDelayedDecision
                + LAMBDA_SPEECH_ACT * sCmn.speech_activity_Q8 * (1.0f / 256.0f)
                + LAMBDA_INPUT_QUALITY * psEncCtrl.input_quality
                + LAMBDA_CODING_QUALITY * psEncCtrl.coding_quality
                + LAMBDA_QUANT_OFFSET * quant_offset;
    }
}
