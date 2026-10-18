/* Ports libopus 1.5.2 (silk/stereo_MS_to_LR.c). Copyright Skype Limited; BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.audio.opus.internal.silk;

import static com.github.auties00.smalt.audio.opus.internal.silk.Define.STEREO_INTERP_LEN_MS;
import static com.github.auties00.smalt.audio.opus.internal.silk.SigProcFix.rshiftRound;
import static com.github.auties00.smalt.audio.opus.internal.silk.SigProcFix.sat16;
import static com.github.auties00.smalt.audio.opus.internal.silk.SigProcFix.smlawb;
import static com.github.auties00.smalt.audio.opus.internal.silk.SigProcFix.smulbb;

/**
 * Converts the adaptive mid/side representation back to left/right stereo.
 */
public final class StereoMsToLr {
    private StereoMsToLr() {
        throw new AssertionError();
    }

    /**
     * Adds the interpolated prediction to the side signal and converts mid/side to left/right
     * in place ({@code silk_stereo_MS_to_LR}).
     *
     * <p>Both channels start with two samples of history, which are replaced from the state, and
     * the last two samples of each channel are saved into the state for the next call.
     *
     * @param state        the stereo decoder state
     * @param x1           the mid signal, {@code frame_length + 2} samples; becomes the left
     *                     signal
     * @param x1Off        the index of the first sample of {@code x1}
     * @param x2           the side signal, {@code frame_length + 2} samples; becomes the right
     *                     signal
     * @param x2Off        the index of the first sample of {@code x2}
     * @param pred_Q13     the two predictors in Q13
     * @param fs_kHz       the sampling rate in kHz
     * @param frame_length the number of samples
     */
    public static void stereoMsToLr(StereoDecState state, short[] x1, int x1Off, short[] x2, int x2Off, int[] pred_Q13,
                                    int fs_kHz, int frame_length) {
        x1[x1Off] = state.sMid[0];
        x1[x1Off + 1] = state.sMid[1];
        x2[x2Off] = state.sSide[0];
        x2[x2Off + 1] = state.sSide[1];
        state.sMid[0] = x1[x1Off + frame_length];
        state.sMid[1] = x1[x1Off + frame_length + 1];
        state.sSide[0] = x2[x2Off + frame_length];
        state.sSide[1] = x2[x2Off + frame_length + 1];
        int pred0_Q13 = state.pred_prev_Q13[0];
        int pred1_Q13 = state.pred_prev_Q13[1];
        var denom_Q16 = (1 << 16) / (STEREO_INTERP_LEN_MS * fs_kHz);
        var delta0_Q13 = rshiftRound(smulbb(pred_Q13[0] - state.pred_prev_Q13[0], denom_Q16), 16);
        var delta1_Q13 = rshiftRound(smulbb(pred_Q13[1] - state.pred_prev_Q13[1], denom_Q16), 16);
        for (var n = 0; n < STEREO_INTERP_LEN_MS * fs_kHz; n++) {
            pred0_Q13 += delta0_Q13;
            pred1_Q13 += delta1_Q13;
            var sum = (x1[x1Off + n] + x1[x1Off + n + 2] + (x1[x1Off + n + 1] << 1)) << 9;
            sum = smlawb(x2[x2Off + n + 1] << 8, sum, pred0_Q13);
            sum = smlawb(sum, x1[x1Off + n + 1] << 11, pred1_Q13);
            x2[x2Off + n + 1] = (short) sat16(rshiftRound(sum, 8));
        }
        pred0_Q13 = pred_Q13[0];
        pred1_Q13 = pred_Q13[1];
        for (var n = STEREO_INTERP_LEN_MS * fs_kHz; n < frame_length; n++) {
            var sum = (x1[x1Off + n] + x1[x1Off + n + 2] + (x1[x1Off + n + 1] << 1)) << 9;
            sum = smlawb(x2[x2Off + n + 1] << 8, sum, pred0_Q13);
            sum = smlawb(sum, x1[x1Off + n + 1] << 11, pred1_Q13);
            x2[x2Off + n + 1] = (short) sat16(rshiftRound(sum, 8));
        }
        state.pred_prev_Q13[0] = (short) pred_Q13[0];
        state.pred_prev_Q13[1] = (short) pred_Q13[1];
        for (var n = 0; n < frame_length; n++) {
            var sum = x1[x1Off + n + 1] + x2[x2Off + n + 1];
            var diff = x1[x1Off + n + 1] - x2[x2Off + n + 1];
            x1[x1Off + n + 1] = (short) sat16(sum);
            x2[x2Off + n + 1] = (short) sat16(diff);
        }
    }
}
