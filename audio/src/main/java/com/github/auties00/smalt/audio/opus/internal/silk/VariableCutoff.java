/* Ports libopus 1.5.2 (silk/HP_variable_cutoff.c, silk/LP_variable_cutoff.c). Copyright Skype Limited; BSD 3-Clause
   License. Modified for Smalt. */
package com.github.auties00.smalt.audio.opus.internal.silk;

import static com.github.auties00.smalt.audio.opus.internal.silk.Define.*;
import static com.github.auties00.smalt.audio.opus.internal.silk.SigProcFix.*;

/**
 * Adapts the SILK encoder's input filters: the high-pass cutoff that tracks the low end of the
 * pitch range, and the variable low-pass filter that smooths internal bandwidth switches.
 */
final class VariableCutoff {
    private VariableCutoff() {
        throw new AssertionError();
    }

    /**
     * Updates the adaptive high-pass cutoff smoother of the first channel from its pitch
     * statistics ({@code silk_HP_variable_cutoff}).
     *
     * @param psEncC1 the state of the first encoder channel
     */
    static void hpVariableCutoff(EncoderState psEncC1) {
        int quality_Q15;
        int pitch_freq_Hz_Q16, pitch_freq_log_Q7, delta_freq_Q7;

        if (psEncC1.prevSignalType == TYPE_VOICED) {
            pitch_freq_Hz_Q16 = ((psEncC1.fs_kHz * 1000) << 16) / psEncC1.prevLag;
            pitch_freq_log_Q7 = LinLog.lin2log(pitch_freq_Hz_Q16) - (16 << 7);

            quality_Q15 = psEncC1.input_quality_bands_Q15[0];
            pitch_freq_log_Q7 = smlawb(pitch_freq_log_Q7, smulwb((-quality_Q15) << 2, quality_Q15),
                    pitch_freq_log_Q7 - (LinLog.lin2log(fixConst(TuningParameters.VARIABLE_HP_MIN_CUTOFF_HZ, 16)) - (16 << 7)));

            delta_freq_Q7 = pitch_freq_log_Q7 - (psEncC1.variable_HP_smth1_Q15 >> 8);
            if (delta_freq_Q7 < 0) {
                delta_freq_Q7 = delta_freq_Q7 * 3;
            }

            delta_freq_Q7 = limit(delta_freq_Q7, -fixConst(TuningParameters.VARIABLE_HP_MAX_DELTA_FREQ, 7),
                    fixConst(TuningParameters.VARIABLE_HP_MAX_DELTA_FREQ, 7));

            psEncC1.variable_HP_smth1_Q15 = smlawb(psEncC1.variable_HP_smth1_Q15,
                    smulbb(psEncC1.speech_activity_Q8, delta_freq_Q7), fixConst(TuningParameters.VARIABLE_HP_SMTH_COEF1, 16));

            psEncC1.variable_HP_smth1_Q15 = limit(psEncC1.variable_HP_smth1_Q15,
                    LinLog.lin2log(TuningParameters.VARIABLE_HP_MIN_CUTOFF_HZ) << 8,
                    LinLog.lin2log(TuningParameters.VARIABLE_HP_MAX_CUTOFF_HZ) << 8);
        }
    }

    /**
     * Interpolates the taps of the transition low-pass filter ({@code silk_LP_interpolate_filter_taps}).
     *
     * @param B_Q28   receives the numerator taps
     * @param A_Q28   receives the denominator taps
     * @param ind     the index of the lower filter
     * @param fac_Q16 the interpolation factor
     */
    private static void interpolateFilterTaps(int[] B_Q28, int[] A_Q28, int ind, int fac_Q16) {
        int nb, na;
        var b = Tables.silk_Transition_LP_B_Q28;
        var a = Tables.silk_Transition_LP_A_Q28;
        if (ind < TRANSITION_INT_NUM - 1) {
            if (fac_Q16 > 0) {
                if (fac_Q16 < 32768) {
                    for (nb = 0; nb < TRANSITION_NB; nb++) {
                        B_Q28[nb] = smlawb(b[ind][nb], b[ind + 1][nb] - b[ind][nb], fac_Q16);
                    }
                    for (na = 0; na < TRANSITION_NA; na++) {
                        A_Q28[na] = smlawb(a[ind][na], a[ind + 1][na] - a[ind][na], fac_Q16);
                    }
                } else {
                    for (nb = 0; nb < TRANSITION_NB; nb++) {
                        B_Q28[nb] = smlawb(b[ind + 1][nb], b[ind + 1][nb] - b[ind][nb], fac_Q16 - (1 << 16));
                    }
                    for (na = 0; na < TRANSITION_NA; na++) {
                        A_Q28[na] = smlawb(a[ind + 1][na], a[ind + 1][na] - a[ind][na], fac_Q16 - (1 << 16));
                    }
                }
            } else {
                System.arraycopy(b[ind], 0, B_Q28, 0, TRANSITION_NB);
                System.arraycopy(a[ind], 0, A_Q28, 0, TRANSITION_NA);
            }
        } else {
            System.arraycopy(b[TRANSITION_INT_NUM - 1], 0, B_Q28, 0, TRANSITION_NB);
            System.arraycopy(a[TRANSITION_INT_NUM - 1], 0, A_Q28, 0, TRANSITION_NA);
        }
    }

    /**
     * Low-pass filters a frame with a cutoff that moves between elliptic filters during a
     * bandwidth transition ({@code silk_LP_variable_cutoff}).
     *
     * @param psLP         the filter state; inactive when {@code mode} is zero
     * @param frame        the frame, filtered in place
     * @param frameOff     the index of the first sample
     * @param frame_length the number of samples
     */
    static void lpVariableCutoff(LpState psLP, short[] frame, int frameOff, int frame_length) {
        var B_Q28 = new int[TRANSITION_NB];
        var A_Q28 = new int[TRANSITION_NA];
        int fac_Q16;
        int ind;

        if (psLP.mode != 0) {
            fac_Q16 = (TRANSITION_FRAMES - psLP.transition_frame_no) << (16 - 6);
            ind = fac_Q16 >> 16;
            fac_Q16 -= ind << 16;

            interpolateFilterTaps(B_Q28, A_Q28, ind, fac_Q16);

            psLP.transition_frame_no = limit(psLP.transition_frame_no + psLP.mode, 0, TRANSITION_FRAMES);

            BiquadAlt.biquadAltStride1(frame, frameOff, B_Q28, A_Q28, psLP.In_LP_State, frame, frameOff, frame_length);
        }
    }
}
