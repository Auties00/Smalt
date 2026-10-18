/* Ports libopus 1.5.2 (silk/float/noise_shape_analysis_FLP.c). Copyright Skype Limited; BSD 3-Clause License.
   Modified for Smalt. */
package com.github.auties00.smalt.audio.opus.internal.silk;

import static com.github.auties00.smalt.audio.opus.internal.silk.Define.*;
import static com.github.auties00.smalt.audio.opus.internal.silk.TuningParameters.*;

/**
 * Computes the noise-shaping filters, the initial quantization gains, the quantization offset
 * type and the low-frequency, tilt and harmonic shaping parameters of a SILK frame.
 */
final class NoiseShapeAnalysisFlp {
    private NoiseShapeAnalysisFlp() {
        throw new AssertionError();
    }

    /**
     * Returns the gain that gives warped filter coefficients a zero-mean log frequency response on
     * a non-warped scale ({@code warped_gain}).
     *
     * @param coefs   the warped coefficients
     * @param coefOff the index of the first coefficient
     * @param lambda  the warping coefficient
     * @param order   the filter order
     * @return the gain
     */
    private static float warpedGain(float[] coefs, int coefOff, float lambda, int order) {
        int i;
        float gain;
        lambda = -lambda;
        gain = coefs[coefOff + order - 1];
        for (i = order - 2; i >= 0; i--) {
            gain = lambda * gain + coefs[coefOff + i];
        }
        return 1.0f / (1.0f - lambda * gain);
    }

    /**
     * Converts warped coefficients to monic pseudo-warped coefficients and limits their amplitude
     * by bandwidth expansion of the true coefficients ({@code warped_true2monic_coefs}).
     *
     * @param coefs   the coefficients, converted in place
     * @param coefOff the index of the first coefficient
     * @param lambda  the warping coefficient
     * @param limit   the maximum absolute value
     * @param order   the filter order
     */
    private static void warpedTrue2MonicCoefs(float[] coefs, int coefOff, float lambda, float limit, int order) {
        int i, iter, ind = 0;
        float tmp, maxabs, chirp, gain;

        for (i = order - 1; i > 0; i--) {
            coefs[coefOff + i - 1] -= lambda * coefs[coefOff + i];
        }
        gain = (1.0f - lambda * lambda) / (1.0f + lambda * coefs[coefOff]);
        for (i = 0; i < order; i++) {
            coefs[coefOff + i] *= gain;
        }

        for (iter = 0; iter < 10; iter++) {
            maxabs = -1.0f;
            for (i = 0; i < order; i++) {
                tmp = Math.abs(coefs[coefOff + i]);
                if (tmp > maxabs) {
                    maxabs = tmp;
                    ind = i;
                }
            }
            if (maxabs <= limit) {
                return;
            }

            for (i = 1; i < order; i++) {
                coefs[coefOff + i - 1] += lambda * coefs[coefOff + i];
            }
            gain = 1.0f / gain;
            for (i = 0; i < order; i++) {
                coefs[coefOff + i] *= gain;
            }

            chirp = 0.99f - (0.8f + 0.1f * iter) * (maxabs - limit) / (maxabs * (ind + 1));
            SigProcFlp.bwexpanderFlp(coefs, coefOff, order, chirp);

            for (i = order - 1; i > 0; i--) {
                coefs[coefOff + i - 1] -= lambda * coefs[coefOff + i];
            }
            gain = (1.0f - lambda * lambda) / (1.0f + lambda * coefs[coefOff]);
            for (i = 0; i < order; i++) {
                coefs[coefOff + i] *= gain;
            }
        }
    }

    /**
     * Limits the amplitude of filter coefficients by bandwidth expansion ({@code limit_coefs}).
     *
     * @param coefs   the coefficients, limited in place
     * @param coefOff the index of the first coefficient
     * @param limit   the maximum absolute value
     * @param order   the filter order
     */
    private static void limitCoefs(float[] coefs, int coefOff, float limit, int order) {
        int i, iter, ind = 0;
        float tmp, maxabs, chirp;

        for (iter = 0; iter < 10; iter++) {
            maxabs = -1.0f;
            for (i = 0; i < order; i++) {
                tmp = Math.abs(coefs[coefOff + i]);
                if (tmp > maxabs) {
                    maxabs = tmp;
                    ind = i;
                }
            }
            if (maxabs <= limit) {
                return;
            }

            chirp = 0.99f - (0.8f + 0.1f * iter) * (maxabs - limit) / (maxabs * (ind + 1));
            SigProcFlp.bwexpanderFlp(coefs, coefOff, order, chirp);
        }
    }

    /**
     * Computes the noise-shaping coefficients and initial gains of a frame
     * ({@code silk_noise_shape_analysis_FLP}).
     *
     * @param psEnc        the channel state
     * @param psEncCtrl    the frame control
     * @param pitch_res    the LPC residual from the pitch analysis
     * @param pitchResOff  the index of the first residual sample of the frame
     * @param x            the analysis buffer
     * @param xOff         the index of the first sample of the frame to encode
     */
    static void noiseShapeAnalysis(EncoderStateFlp psEnc, EncoderControlFlp psEncCtrl, float[] pitch_res,
                                   int pitchResOff, float[] x, int xOff) {
        var psShapeSt = psEnc.sShape;
        var sCmn = psEnc.sCmn;
        var s = psEnc.scratch;
        int k, nSamples, nSegs;
        float SNR_adj_dB, HarmShapeGain, Tilt;
        float nrg, log_energy, log_energy_prev, energy_variation;
        float BWExp, gain_mult, gain_add, strength, b, warping;
        var x_windowed = s.x_windowed;
        var auto_corr = s.shape_auto_corr;
        var rc = s.shape_rc;
        var all_corr = s.shape_all_corr;

        var x_ptr = xOff - sCmn.la_shape;

        SNR_adj_dB = sCmn.SNR_dB_Q7 * (1 / 128.0f);

        psEncCtrl.input_quality = 0.5f * (sCmn.input_quality_bands_Q15[0] + sCmn.input_quality_bands_Q15[1]) * (1.0f / 32768.0f);

        psEncCtrl.coding_quality = SigProcFlp.sigmoid(0.25f * (SNR_adj_dB - 20.0f));

        if (sCmn.useCBR == 0) {
            b = 1.0f - sCmn.speech_activity_Q8 * (1.0f / 256.0f);
            SNR_adj_dB -= BG_SNR_DECR_dB * psEncCtrl.coding_quality * (0.5f + 0.5f * psEncCtrl.input_quality) * b * b;
        }

        if (sCmn.indices.signalType == TYPE_VOICED) {
            SNR_adj_dB += HARM_SNR_INCR_dB * psEnc.LTPCorr;
        } else {
            SNR_adj_dB += (-0.4f * sCmn.SNR_dB_Q7 * (1 / 128.0f) + 6.0f) * (1.0f - psEncCtrl.input_quality);
        }

        if (sCmn.indices.signalType == TYPE_VOICED) {
            sCmn.indices.quantOffsetType = 0;
        } else {
            nSamples = 2 * sCmn.fs_kHz;
            energy_variation = 0.0f;
            log_energy_prev = 0.0f;
            var pitch_res_ptr = pitchResOff;
            nSegs = SigProcFix.smulbb(SUB_FRAME_LENGTH_MS, sCmn.nb_subfr) / 2;
            for (k = 0; k < nSegs; k++) {
                nrg = (float) nSamples + (float) SigProcFlp.energyFlp(pitch_res, pitch_res_ptr, nSamples);
                log_energy = SigProcFlp.log2(nrg);
                if (k > 0) {
                    energy_variation += Math.abs(log_energy - log_energy_prev);
                }
                log_energy_prev = log_energy;
                pitch_res_ptr += nSamples;
            }

            if (energy_variation > ENERGY_VARIATION_THRESHOLD_QNT_OFFSET * (nSegs - 1)) {
                sCmn.indices.quantOffsetType = 0;
            } else {
                sCmn.indices.quantOffsetType = 1;
            }
        }

        strength = FIND_PITCH_WHITE_NOISE_FRACTION * psEncCtrl.predGain;
        BWExp = BANDWIDTH_EXPANSION / (1.0f + strength * strength);

        warping = (float) sCmn.warping_Q16 / 65536.0f + 0.01f * psEncCtrl.coding_quality;

        for (k = 0; k < sCmn.nb_subfr; k++) {
            int shift, slope_part, flat_part;
            var win = k * SHAPE_LPC_WIN_MAX;
            flat_part = sCmn.fs_kHz * 3;
            slope_part = (sCmn.shapeWinLength - flat_part) / 2;

            SigProcFlp.applySineWindowFlp(x_windowed, win, x, x_ptr, 1, slope_part);
            shift = slope_part;
            System.arraycopy(x, x_ptr + shift, x_windowed, win + shift, flat_part);
            shift += flat_part;
            SigProcFlp.applySineWindowFlp(x_windowed, win + shift, x, x_ptr + shift, 2, slope_part);

            x_ptr += sCmn.subfr_length;
        }

        if (sCmn.warping_Q16 > 0) {
            if (sCmn.nb_subfr == MAX_NB_SUBFR) {
                SilkFlpKernels.INSTANCE.warpedAutocorrelation4(all_corr, MAX_SHAPE_LPC_ORDER + 1, x_windowed,
                        SHAPE_LPC_WIN_MAX, warping, sCmn.shapeWinLength, sCmn.shapingLPCOrder, s.warpedScratch);
            } else {
                for (k = 0; k < sCmn.nb_subfr; k++) {
                    SigProcFlp.warpedAutocorrelationFlp(auto_corr, x_windowed, k * SHAPE_LPC_WIN_MAX, warping,
                            sCmn.shapeWinLength, sCmn.shapingLPCOrder);
                    System.arraycopy(auto_corr, 0, all_corr, k * (MAX_SHAPE_LPC_ORDER + 1), sCmn.shapingLPCOrder + 1);
                }
            }
        }

        for (k = 0; k < sCmn.nb_subfr; k++) {
            if (sCmn.warping_Q16 > 0) {
                System.arraycopy(all_corr, k * (MAX_SHAPE_LPC_ORDER + 1), auto_corr, 0, sCmn.shapingLPCOrder + 1);
            } else {
                SigProcFlp.autocorrelationFlp(auto_corr, 0, x_windowed, k * SHAPE_LPC_WIN_MAX, sCmn.shapeWinLength,
                        sCmn.shapingLPCOrder + 1);
            }

            auto_corr[0] += auto_corr[0] * SHAPE_WHITE_NOISE_FRACTION + 1.0f;

            nrg = SigProcFlp.schurFlp(rc, auto_corr, sCmn.shapingLPCOrder, s);
            SigProcFlp.k2aFlp(psEncCtrl.AR, k * MAX_SHAPE_LPC_ORDER, rc, sCmn.shapingLPCOrder);
            psEncCtrl.Gains[k] = (float) Math.sqrt(nrg);

            if (sCmn.warping_Q16 > 0) {
                psEncCtrl.Gains[k] *= warpedGain(psEncCtrl.AR, k * MAX_SHAPE_LPC_ORDER, warping, sCmn.shapingLPCOrder);
            }

            SigProcFlp.bwexpanderFlp(psEncCtrl.AR, k * MAX_SHAPE_LPC_ORDER, sCmn.shapingLPCOrder, BWExp);

            if (sCmn.warping_Q16 > 0) {
                warpedTrue2MonicCoefs(psEncCtrl.AR, k * MAX_SHAPE_LPC_ORDER, warping, 3.999f, sCmn.shapingLPCOrder);
            } else {
                limitCoefs(psEncCtrl.AR, k * MAX_SHAPE_LPC_ORDER, 3.999f, sCmn.shapingLPCOrder);
            }
        }

        gain_mult = (float) StrictMath.pow(2.0f, -0.16f * SNR_adj_dB);
        gain_add = (float) StrictMath.pow(2.0f, 0.16f * MIN_QGAIN_DB);
        for (k = 0; k < sCmn.nb_subfr; k++) {
            psEncCtrl.Gains[k] *= gain_mult;
            psEncCtrl.Gains[k] += gain_add;
        }

        strength = LOW_FREQ_SHAPING * (1.0f + LOW_QUALITY_LOW_FREQ_SHAPING_DECR * (sCmn.input_quality_bands_Q15[0] * (1.0f / 32768.0f) - 1.0f));
        strength *= sCmn.speech_activity_Q8 * (1.0f / 256.0f);
        if (sCmn.indices.signalType == TYPE_VOICED) {
            for (k = 0; k < sCmn.nb_subfr; k++) {
                b = 0.2f / sCmn.fs_kHz + 3.0f / psEncCtrl.pitchL[k];
                psEncCtrl.LF_MA_shp[k] = -1.0f + b;
                psEncCtrl.LF_AR_shp[k] = 1.0f - b - b * strength;
            }
            Tilt = -HP_NOISE_COEF
                    - (1 - HP_NOISE_COEF) * HARM_HP_NOISE_COEF * sCmn.speech_activity_Q8 * (1.0f / 256.0f);
        } else {
            b = 1.3f / sCmn.fs_kHz;
            psEncCtrl.LF_MA_shp[0] = -1.0f + b;
            psEncCtrl.LF_AR_shp[0] = 1.0f - b - b * strength * 0.6f;
            for (k = 1; k < sCmn.nb_subfr; k++) {
                psEncCtrl.LF_MA_shp[k] = psEncCtrl.LF_MA_shp[0];
                psEncCtrl.LF_AR_shp[k] = psEncCtrl.LF_AR_shp[0];
            }
            Tilt = -HP_NOISE_COEF;
        }

        if (USE_HARM_SHAPING != 0 && sCmn.indices.signalType == TYPE_VOICED) {
            HarmShapeGain = HARMONIC_SHAPING;

            HarmShapeGain += HIGH_RATE_OR_LOW_QUALITY_HARMONIC_SHAPING
                    * (1.0f - (1.0f - psEncCtrl.coding_quality) * psEncCtrl.input_quality);

            HarmShapeGain *= (float) Math.sqrt(psEnc.LTPCorr);
        } else {
            HarmShapeGain = 0.0f;
        }

        for (k = 0; k < sCmn.nb_subfr; k++) {
            psShapeSt.HarmShapeGain_smth += SUBFR_SMTH_COEF * (HarmShapeGain - psShapeSt.HarmShapeGain_smth);
            psEncCtrl.HarmShapeGain[k] = psShapeSt.HarmShapeGain_smth;
            psShapeSt.Tilt_smth += SUBFR_SMTH_COEF * (Tilt - psShapeSt.Tilt_smth);
            psEncCtrl.Tilt[k] = psShapeSt.Tilt_smth;
        }
    }
}
