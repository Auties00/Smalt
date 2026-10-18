/* Ports libopus 1.5.2 (silk/VAD.c). Copyright Skype Limited; BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.audio.opus.internal.silk;

import static com.github.auties00.smalt.audio.opus.internal.silk.Define.*;
import static com.github.auties00.smalt.audio.opus.internal.silk.SigProcFix.*;

/**
 * Estimates the speech activity, the per-band input quality and the spectral tilt of the SILK
 * encoder input.
 */
final class Vad {
    /**
     * The weighting factors of the tilt measure ({@code tiltWeights}).
     */
    private static final int[] TILT_WEIGHTS = {30000, 6000, -12000, -12000};

    /**
     * The four-band decomposition of the current frame, arranged as in the reference: it needs
     * {@code 5 / 4} of the frame length.
     */
    private final short[] x = new short[MAX_FRAME_LENGTH + (MAX_FRAME_LENGTH >> 2)];

    /**
     * The energy of each band.
     */
    private final int[] xnrg = new int[VAD_N_BANDS];

    /**
     * The energy-to-noise ratio of each band in Q8.
     */
    private final int[] nrgToNoiseRatio_Q8 = new int[VAD_N_BANDS];

    /**
     * The start of each band in {@link #x}.
     */
    private final int[] xOffset = new int[VAD_N_BANDS];

    /**
     * Creates the detector scratch.
     */
    Vad() {
    }

    /**
     * Initializes a detector state ({@code silk_VAD_Init}).
     *
     * @param psSilk_VAD the state to reset
     * @return {@code 0}
     */
    static int init(VadState psSilk_VAD) {
        int b;
        psSilk_VAD.clear();
        for (b = 0; b < VAD_N_BANDS; b++) {
            psSilk_VAD.NoiseLevelBias[b] = Math.max(VAD_NOISE_LEVELS_BIAS / (b + 1), 1);
        }
        for (b = 0; b < VAD_N_BANDS; b++) {
            psSilk_VAD.NL[b] = 100 * psSilk_VAD.NoiseLevelBias[b];
            psSilk_VAD.inv_NL[b] = Integer.MAX_VALUE / psSilk_VAD.NL[b];
        }
        psSilk_VAD.counter = 15;
        for (b = 0; b < VAD_N_BANDS; b++) {
            psSilk_VAD.NrgRatioSmth_Q8[b] = 100 * 256;
        }
        return 0;
    }

    /**
     * Computes the speech activity level of a frame and updates the input-quality and tilt
     * estimates of the encoder ({@code silk_VAD_GetSA_Q8_c}).
     *
     * @param psEncC the encoder state; {@code speech_activity_Q8}, {@code input_tilt_Q15} and
     *               {@code input_quality_bands_Q15} are written
     * @param pIn    the input
     * @param inOff  the index of the first input sample
     * @return {@code 0}
     */
    int getSaQ8(EncoderState psEncC, short[] pIn, int inOff) {
        int SA_Q15, pSNR_dB_Q7, input_tilt;
        int decimated_framelength1, decimated_framelength2;
        int decimated_framelength;
        int dec_subframe_length, dec_subframe_offset, SNR_Q7, i, b, s;
        int sumSquared, smooth_coef_Q16;
        short HPstateTmp;
        int speech_nrg, x_tmp;
        var psSilk_VAD = psEncC.sVAD;
        var X = x;
        var Xnrg = xnrg;
        var NrgToNoiseRatio_Q8 = nrgToNoiseRatio_Q8;
        var X_offset = xOffset;

        decimated_framelength1 = psEncC.frame_length >> 1;
        decimated_framelength2 = psEncC.frame_length >> 2;
        decimated_framelength = psEncC.frame_length >> 3;
        X_offset[0] = 0;
        X_offset[1] = decimated_framelength + decimated_framelength2;
        X_offset[2] = X_offset[1] + decimated_framelength;
        X_offset[3] = X_offset[2] + decimated_framelength2;

        AnaFiltBank1.anaFiltBank1(pIn, inOff, psSilk_VAD.AnaState, X, 0, X, X_offset[3], psEncC.frame_length);
        AnaFiltBank1.anaFiltBank1(X, 0, psSilk_VAD.AnaState1, X, 0, X, X_offset[2], decimated_framelength1);
        AnaFiltBank1.anaFiltBank1(X, 0, psSilk_VAD.AnaState2, X, 0, X, X_offset[1], decimated_framelength2);

        X[decimated_framelength - 1] = (short) (X[decimated_framelength - 1] >> 1);
        HPstateTmp = X[decimated_framelength - 1];
        for (i = decimated_framelength - 1; i > 0; i--) {
            X[i - 1] = (short) (X[i - 1] >> 1);
            X[i] -= X[i - 1];
        }
        X[0] -= psSilk_VAD.HPstate;
        psSilk_VAD.HPstate = HPstateTmp;

        for (b = 0; b < VAD_N_BANDS; b++) {
            decimated_framelength = psEncC.frame_length >> Math.min(VAD_N_BANDS - b, VAD_N_BANDS - 1);
            dec_subframe_length = decimated_framelength >> VAD_INTERNAL_SUBFRAMES_LOG2;
            dec_subframe_offset = 0;
            Xnrg[b] = psSilk_VAD.XnrgSubfr[b];
            sumSquared = 0;
            for (s = 0; s < VAD_INTERNAL_SUBFRAMES; s++) {
                sumSquared = 0;
                for (i = 0; i < dec_subframe_length; i++) {
                    x_tmp = X[X_offset[b] + i + dec_subframe_offset] >> 3;
                    sumSquared = smlabb(sumSquared, x_tmp, x_tmp);
                }
                if (s < VAD_INTERNAL_SUBFRAMES - 1) {
                    Xnrg[b] = addPosSat32(Xnrg[b], sumSquared);
                } else {
                    Xnrg[b] = addPosSat32(Xnrg[b], sumSquared >> 1);
                }
                dec_subframe_offset += dec_subframe_length;
            }
            psSilk_VAD.XnrgSubfr[b] = sumSquared;
        }

        getNoiseLevels(Xnrg, psSilk_VAD);

        sumSquared = 0;
        input_tilt = 0;
        for (b = 0; b < VAD_N_BANDS; b++) {
            speech_nrg = Xnrg[b] - psSilk_VAD.NL[b];
            if (speech_nrg > 0) {
                if ((Xnrg[b] & 0xFF800000) == 0) {
                    NrgToNoiseRatio_Q8[b] = (Xnrg[b] << 8) / (psSilk_VAD.NL[b] + 1);
                } else {
                    NrgToNoiseRatio_Q8[b] = Xnrg[b] / ((psSilk_VAD.NL[b] >> 8) + 1);
                }

                SNR_Q7 = LinLog.lin2log(NrgToNoiseRatio_Q8[b]) - 8 * 128;

                sumSquared = smlabb(sumSquared, SNR_Q7, SNR_Q7);

                if (speech_nrg < (1 << 20)) {
                    SNR_Q7 = smulwb(sqrtApprox(speech_nrg) << 6, SNR_Q7);
                }
                input_tilt = smlawb(input_tilt, TILT_WEIGHTS[b], SNR_Q7);
            } else {
                NrgToNoiseRatio_Q8[b] = 256;
            }
        }

        sumSquared = sumSquared / VAD_N_BANDS;

        pSNR_dB_Q7 = (short) (3 * sqrtApprox(sumSquared));

        SA_Q15 = SigmQ15.sigmQ15(smulwb(VAD_SNR_FACTOR_Q16, pSNR_dB_Q7) - VAD_NEGATIVE_OFFSET_Q5);

        psEncC.input_tilt_Q15 = (SigmQ15.sigmQ15(input_tilt) - 16384) << 1;

        speech_nrg = 0;
        for (b = 0; b < VAD_N_BANDS; b++) {
            speech_nrg += (b + 1) * ((Xnrg[b] - psSilk_VAD.NL[b]) >> 4);
        }

        if (psEncC.frame_length == 20 * psEncC.fs_kHz) {
            speech_nrg = speech_nrg >> 1;
        }
        if (speech_nrg <= 0) {
            SA_Q15 = SA_Q15 >> 1;
        } else if (speech_nrg < 16384) {
            speech_nrg = speech_nrg << 16;
            speech_nrg = sqrtApprox(speech_nrg);
            SA_Q15 = smulwb(32768 + speech_nrg, SA_Q15);
        }

        psEncC.speech_activity_Q8 = Math.min(SA_Q15 >> 7, 0xFF);

        smooth_coef_Q16 = smulwb(VAD_SNR_SMOOTH_COEF_Q18, smulwb(SA_Q15, SA_Q15));

        if (psEncC.frame_length == 10 * psEncC.fs_kHz) {
            smooth_coef_Q16 >>= 1;
        }

        for (b = 0; b < VAD_N_BANDS; b++) {
            psSilk_VAD.NrgRatioSmth_Q8[b] = smlawb(psSilk_VAD.NrgRatioSmth_Q8[b],
                    NrgToNoiseRatio_Q8[b] - psSilk_VAD.NrgRatioSmth_Q8[b], smooth_coef_Q16);

            SNR_Q7 = 3 * (LinLog.lin2log(psSilk_VAD.NrgRatioSmth_Q8[b]) - 8 * 128);
            psEncC.input_quality_bands_Q15[b] = SigmQ15.sigmQ15((SNR_Q7 - 16 * 128) >> 4);
        }
        return 0;
    }

    /**
     * Updates the noise level estimate of every band ({@code silk_VAD_GetNoiseLevels}).
     *
     * @param pX         the band energies
     * @param psSilk_VAD the detector state
     */
    private static void getNoiseLevels(int[] pX, VadState psSilk_VAD) {
        int k;
        int nl, nrg, inv_nrg;
        int coef, min_coef;

        if (psSilk_VAD.counter < 1000) {
            min_coef = Short.MAX_VALUE / ((psSilk_VAD.counter >> 4) + 1);
            psSilk_VAD.counter++;
        } else {
            min_coef = 0;
        }

        for (k = 0; k < VAD_N_BANDS; k++) {
            nl = psSilk_VAD.NL[k];
            nrg = addPosSat32(pX[k], psSilk_VAD.NoiseLevelBias[k]);
            inv_nrg = Integer.MAX_VALUE / nrg;
            if (nrg > (nl << 3)) {
                coef = VAD_NOISE_LEVEL_SMOOTH_COEF_Q16 >> 3;
            } else if (nrg < nl) {
                coef = VAD_NOISE_LEVEL_SMOOTH_COEF_Q16;
            } else {
                coef = smulwb(smulww(inv_nrg, nl), VAD_NOISE_LEVEL_SMOOTH_COEF_Q16 << 1);
            }
            coef = Math.max(coef, min_coef);
            psSilk_VAD.inv_NL[k] = smlawb(psSilk_VAD.inv_NL[k], inv_nrg - psSilk_VAD.inv_NL[k], coef);
            nl = Integer.MAX_VALUE / psSilk_VAD.inv_NL[k];
            nl = Math.min(nl, 0x00FFFFFF);
            psSilk_VAD.NL[k] = nl;
        }
    }
}
