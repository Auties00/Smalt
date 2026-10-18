/* Ports libopus 1.5.2 (silk/float/find_pitch_lags_FLP.c). Copyright Skype Limited; BSD 3-Clause License. Modified for
   Smalt. */
package com.github.auties00.smalt.audio.opus.internal.silk;

import java.util.Arrays;

import static com.github.auties00.smalt.audio.opus.internal.silk.Define.*;
import static com.github.auties00.smalt.audio.opus.internal.silk.TuningParameters.*;

/**
 * Whitens the analysis buffer with a short LPC filter and runs the pitch estimator on the
 * residual, classifying the frame as voiced or unvoiced.
 */
final class FindPitchLagsFlp {
    private FindPitchLagsFlp() {
        throw new AssertionError();
    }

    /**
     * Finds the pitch lags of a frame ({@code silk_find_pitch_lags_FLP}).
     *
     * @param psEnc     the channel state
     * @param psEncCtrl the frame control; {@code predGain} and {@code pitchL} are written
     * @param res       receives the whitening residual of the whole analysis buffer
     * @param x         the analysis buffer
     * @param xOff      the index of the first sample of the frame to encode; {@code
     *                  ltp_mem_length} samples of history precede it
     */
    static void findPitchLags(EncoderStateFlp psEnc, EncoderControlFlp psEncCtrl, float[] res, float[] x, int xOff) {
        int buf_len;
        float thrhld, res_nrg;
        var s = psEnc.scratch;
        var auto_corr = s.pitch_auto_corr;
        var A = s.pitch_A;
        var refl_coef = s.pitch_refl_coef;
        var Wsig = s.Wsig;
        var sCmn = psEnc.sCmn;

        buf_len = sCmn.la_pitch + sCmn.frame_length + sCmn.ltp_mem_length;

        var x_buf = xOff - sCmn.ltp_mem_length;

        var x_buf_ptr = x_buf + buf_len - sCmn.pitch_LPC_win_length;
        var Wsig_ptr = 0;
        SigProcFlp.applySineWindowFlp(Wsig, Wsig_ptr, x, x_buf_ptr, 1, sCmn.la_pitch);

        Wsig_ptr += sCmn.la_pitch;
        x_buf_ptr += sCmn.la_pitch;
        System.arraycopy(x, x_buf_ptr, Wsig, Wsig_ptr, sCmn.pitch_LPC_win_length - (sCmn.la_pitch << 1));

        Wsig_ptr += sCmn.pitch_LPC_win_length - (sCmn.la_pitch << 1);
        x_buf_ptr += sCmn.pitch_LPC_win_length - (sCmn.la_pitch << 1);
        SigProcFlp.applySineWindowFlp(Wsig, Wsig_ptr, x, x_buf_ptr, 2, sCmn.la_pitch);

        SigProcFlp.autocorrelationFlp(auto_corr, 0, Wsig, 0, sCmn.pitch_LPC_win_length, sCmn.pitchEstimationLPCOrder + 1);

        auto_corr[0] += auto_corr[0] * FIND_PITCH_WHITE_NOISE_FRACTION + 1;

        res_nrg = SigProcFlp.schurFlp(refl_coef, auto_corr, sCmn.pitchEstimationLPCOrder, s);

        psEncCtrl.predGain = auto_corr[0] / (res_nrg > 1.0f ? res_nrg : 1.0f);

        SigProcFlp.k2aFlp(A, 0, refl_coef, sCmn.pitchEstimationLPCOrder);

        SigProcFlp.bwexpanderFlp(A, 0, sCmn.pitchEstimationLPCOrder, FIND_PITCH_BANDWIDTH_EXPANSION);

        SigProcFlp.lpcAnalysisFilterFlp(res, 0, A, 0, x, x_buf, buf_len, sCmn.pitchEstimationLPCOrder);

        if (sCmn.indices.signalType != TYPE_NO_VOICE_ACTIVITY && sCmn.first_frame_after_reset == 0) {
            thrhld = 0.6f;
            thrhld -= 0.004f * sCmn.pitchEstimationLPCOrder;
            thrhld -= 0.1f * sCmn.speech_activity_Q8 * (1.0f / 256.0f);
            thrhld -= 0.15f * (sCmn.prevSignalType >> 1);
            thrhld -= 0.1f * sCmn.input_tilt_Q15 * (1.0f / 32768.0f);

            s.ltpCorr[0] = psEnc.LTPCorr;
            var unvoiced = psEnc.pitchAnalysis.pitchAnalysisCore(res, 0, psEncCtrl.pitchL, sCmn.indices, s.ltpCorr,
                    sCmn.prevLag, sCmn.pitchEstimationThreshold_Q16 / 65536.0f, thrhld, sCmn.fs_kHz,
                    sCmn.pitchEstimationComplexity, sCmn.nb_subfr);
            psEnc.LTPCorr = s.ltpCorr[0];
            if (unvoiced == 0) {
                sCmn.indices.signalType = TYPE_VOICED;
            } else {
                sCmn.indices.signalType = TYPE_UNVOICED;
            }
        } else {
            Arrays.fill(psEncCtrl.pitchL, 0);
            sCmn.indices.lagIndex = 0;
            sCmn.indices.contourIndex = 0;
            psEnc.LTPCorr = 0;
        }
    }
}
