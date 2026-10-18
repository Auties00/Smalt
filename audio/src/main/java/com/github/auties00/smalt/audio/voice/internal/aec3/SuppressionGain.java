/* Ports webrtc-audio-processing 2.1 (modules/audio_processing/aec3/suppression_gain.cc,
   dominant_nearend_detector.cc, moving_average.cc). Copyright (c) 2011, The WebRTC project authors; BSD 3-Clause
   License. Modified for Smalt. */
package com.github.auties00.smalt.audio.voice.internal.aec3;

import com.github.auties00.smalt.audio.voice.internal.FloatMath;

import java.util.Arrays;

import static com.github.auties00.smalt.audio.voice.internal.aec3.Aec3.BLOCK_SIZE;
import static com.github.auties00.smalt.audio.voice.internal.aec3.Aec3.FFT_LENGTH_BY_2;
import static com.github.auties00.smalt.audio.voice.internal.aec3.Aec3.FFT_LENGTH_BY_2_MINUS_1;
import static com.github.auties00.smalt.audio.voice.internal.aec3.Aec3.FFT_LENGTH_BY_2_PLUS_1;

/**
 * Computes the per-bin suppression gain that makes the residual echo inaudible under the near
 * end signal and the noise, limited in how fast it may rise and fall.
 *
 * <p>The thresholds are those of the normal tuning, or of the near-end tuning while the dominant
 * near-end detector holds; the result is the square root of a power gain.
 *
 * @implNote This implementation ports {@code SuppressionGain} of WebRTC's
 * {@code modules/audio_processing/aec3/suppression_gain.cc}, with
 * {@code DominantNearendDetector} ({@code dominant_nearend_detector.cc}) and
 * {@code MovingAverage} ({@code moving_average.cc}), for one capture channel and one band with
 * the default {@code suppressor} configuration. It omits the reference's initial-state flag, which
 * changes the gain only when {@code lf_smoothing_during_initial_phase} or
 * {@code dominant_nearend_detection.use_during_initial_phase} is off.
 */
final class SuppressionGain {
    /**
     * The masking thresholds of the normal tuning: transparent and suppressing echo-to-near-end
     * ratios and transparent echo-to-masker ratio, per bin.
     */
    private final float[][] normalParams = gainParameters(0.3f, 0.4f, 0.3f, 0.07f, 0.1f, 0.3f);

    /**
     * The masking thresholds of the near-end tuning, laid out like {@link #normalParams}.
     */
    private final float[][] nearendParams = gainParameters(1.09f, 1.1f, 0.3f, 0.1f, 0.3f, 0.3f);

    /**
     * The largest gain increase factor per block of both tunings.
     */
    private static final float MAX_INC_FACTOR = 2.0f;

    /**
     * The largest low-frequency gain decrease factor per block of both tunings.
     */
    private static final float MAX_DEC_FACTOR_LF = 0.25f;

    /**
     * The gain of the previous block.
     */
    private final float[] lastGain = new float[FFT_LENGTH_BY_2_PLUS_1];

    /**
     * The smoothed near-end power of the previous block.
     */
    private final float[] lastNearend = new float[FFT_LENGTH_BY_2_PLUS_1];

    /**
     * The weighted residual echo power of the previous block.
     */
    private final float[] lastEcho = new float[FFT_LENGTH_BY_2_PLUS_1];

    /**
     * The smoothed render power of the low-noise render detector.
     */
    private float averageRenderPower = 32768.f * 32768.f;

    /**
     * The past near-end powers of the moving average, three blocks.
     */
    private final float[] nearendMemory = new float[(Aec3.NEAREND_AVERAGE_BLOCKS - 1) * FFT_LENGTH_BY_2_PLUS_1];

    /**
     * The next slot of {@link #nearendMemory}.
     */
    private int nearendMemoryIndex;

    /**
     * Whether the near end dominates.
     */
    private boolean nearendState;

    /**
     * The trigger counter of the dominant near-end detector.
     */
    private int triggerCounter;

    /**
     * The hold counter of the dominant near-end detector.
     */
    private int holdCounter;

    /**
     * The scratch buffer of the smoothed near-end power.
     */
    private final float[] nearend = new float[FFT_LENGTH_BY_2_PLUS_1];

    /**
     * The scratch buffer of the weighted residual echo.
     */
    private final float[] weightedResidualEcho = new float[FFT_LENGTH_BY_2_PLUS_1];

    /**
     * The scratch buffer of the minimum gain.
     */
    private final float[] minGain = new float[FFT_LENGTH_BY_2_PLUS_1];

    /**
     * The scratch buffer of the maximum gain.
     */
    private final float[] maxGain = new float[FFT_LENGTH_BY_2_PLUS_1];

    /**
     * The scratch buffer of the unlimited gain.
     */
    private final float[] g = new float[FFT_LENGTH_BY_2_PLUS_1];

    /**
     * Creates a suppression gain in the initial state.
     */
    SuppressionGain() {
        Arrays.fill(lastGain, 1.f);
    }

    /**
     * Computes the masking thresholds of a tuning, interpolated between the low-frequency and the
     * high-frequency masks.
     *
     * @param lfEnrTransparent the low-frequency transparent echo-to-near-end ratio
     * @param lfEnrSuppress the low-frequency suppressing echo-to-near-end ratio
     * @param lfEmrTransparent the low-frequency transparent echo-to-masker ratio
     * @param hfEnrTransparent the high-frequency transparent echo-to-near-end ratio
     * @param hfEnrSuppress the high-frequency suppressing echo-to-near-end ratio
     * @param hfEmrTransparent the high-frequency transparent echo-to-masker ratio
     * @return the three threshold arrays: transparent ENR, suppressing ENR, transparent EMR
     */
    private static float[][] gainParameters(float lfEnrTransparent, float lfEnrSuppress, float lfEmrTransparent,
                                            float hfEnrTransparent, float hfEnrSuppress, float hfEmrTransparent) {
        var enrTransparent = new float[FFT_LENGTH_BY_2_PLUS_1];
        var enrSuppress = new float[FFT_LENGTH_BY_2_PLUS_1];
        var emrTransparent = new float[FFT_LENGTH_BY_2_PLUS_1];
        for (var k = 0; k < FFT_LENGTH_BY_2_PLUS_1; k++) {
            float a;
            if (k <= Aec3.LAST_LF_BAND) {
                a = 0.f;
            } else if (k < Aec3.FIRST_HF_BAND) {
                a = (k - Aec3.LAST_LF_BAND) / (float) (Aec3.FIRST_HF_BAND - Aec3.LAST_LF_BAND);
            } else {
                a = 1.f;
            }
            enrTransparent[k] = (1 - a) * lfEnrTransparent + a * hfEnrTransparent;
            enrSuppress[k] = (1 - a) * lfEnrSuppress + a * hfEnrSuppress;
            emrTransparent[k] = (1 - a) * lfEmrTransparent + a * hfEmrTransparent;
        }
        return new float[][]{enrTransparent, enrSuppress, emrTransparent};
    }

    /**
     * Returns whether the near end dominates.
     *
     * @return the dominant near-end state
     */
    boolean isDominantNearend() {
        return nearendState;
    }

    /**
     * Computes the suppression gain of a block.
     *
     * @param nearendSpectrum the near-end power spectrum
     * @param residualEchoSpectrum the residual echo power spectrum
     * @param residualEchoUnbounded the residual echo power spectrum with the unbounded ERLE
     * @param comfortNoiseSpectrum the comfort noise power spectrum
     * @param saturatedEcho whether the echo is saturated
     * @param render the render block ring
     * @param renderOffset the start of the render block aligned with the capture
     * @param clockDrift whether clock drift is detected
     * @param lowBandGain receives the gain
     */
    void getGain(float[] nearendSpectrum, float[] residualEchoSpectrum, float[] residualEchoUnbounded,
                 float[] comfortNoiseSpectrum, boolean saturatedEcho, float[] render, int renderOffset,
                 boolean clockDrift, float[] lowBandGain) {
        updateDominantNearend(nearendSpectrum, residualEchoUnbounded, comfortNoiseSpectrum);
        var lowNoiseRender = detectLowNoiseRender(render, renderOffset);
        lowerBandGain(lowNoiseRender, saturatedEcho, nearendSpectrum, residualEchoSpectrum, comfortNoiseSpectrum,
                clockDrift, lowBandGain);
    }

    /**
     * Updates the dominant near-end detector.
     *
     * @param nearendSpectrum the near-end power spectrum
     * @param residualEchoSpectrum the residual echo power spectrum
     * @param comfortNoiseSpectrum the comfort noise power spectrum
     */
    private void updateDominantNearend(float[] nearendSpectrum, float[] residualEchoSpectrum, float[] comfortNoiseSpectrum) {
        nearendState = false;
        var neSum = lowFrequencyEnergy(nearendSpectrum);
        var echoSum = lowFrequencyEnergy(residualEchoSpectrum);
        var noiseSum = lowFrequencyEnergy(comfortNoiseSpectrum);
        if (echoSum < Aec3.DOMINANT_ENR_THRESHOLD * neSum && neSum > Aec3.DOMINANT_SNR_THRESHOLD * noiseSum) {
            if (++triggerCounter >= Aec3.DOMINANT_TRIGGER_THRESHOLD) {
                holdCounter = Aec3.DOMINANT_HOLD_DURATION;
                triggerCounter = Aec3.DOMINANT_TRIGGER_THRESHOLD;
            }
        } else {
            triggerCounter = Math.max(0, triggerCounter - 1);
        }
        if (echoSum > Aec3.DOMINANT_ENR_EXIT_THRESHOLD * neSum && echoSum > Aec3.DOMINANT_SNR_THRESHOLD * noiseSum) {
            holdCounter = 0;
        }
        holdCounter = Math.max(0, holdCounter - 1);
        nearendState = holdCounter > 0;
    }

    /**
     * Returns the sum of bins 1 to 15 of a spectrum.
     *
     * @param spectrum the power spectrum, 65 bins
     * @return the low-frequency energy
     */
    private static float lowFrequencyEnergy(float[] spectrum) {
        var sum = 0.f;
        for (var k = 1; k < 16; k++) {
            sum = sum + spectrum[k];
        }
        return sum;
    }

    /**
     * Returns whether the render block is low-level noise, and updates the render power average.
     *
     * @param render the render block ring
     * @param offset the start of the block
     * @return whether the render signal is low-level noise
     */
    private boolean detectLowNoiseRender(float[] render, int offset) {
        var x2Sum = 0.f;
        var x2Max = 0.f;
        for (var i = 0; i < BLOCK_SIZE; i++) {
            var x2 = render[offset + i] * render[offset + i];
            x2Sum += x2;
            x2Max = FloatMath.max(x2Max, x2);
        }
        x2Sum = x2Sum / 1;
        var threshold = 50.f * 50.f * 64.f;
        var lowNoiseRender = averageRenderPower < threshold && x2Max < 3 * averageRenderPower;
        averageRenderPower = averageRenderPower * 0.9f + x2Sum * 0.1f;
        return lowNoiseRender;
    }

    /**
     * Computes the gain of the lower band.
     *
     * @param lowNoiseRender whether the render signal is low-level noise
     * @param saturatedEcho whether the echo is saturated
     * @param suppressorInput the near-end power spectrum
     * @param residualEcho the residual echo power spectrum
     * @param comfortNoise the comfort noise power spectrum
     * @param clockDrift whether clock drift is detected
     * @param gain receives the gain
     */
    private void lowerBandGain(boolean lowNoiseRender, boolean saturatedEcho, float[] suppressorInput,
                               float[] residualEcho, float[] comfortNoise, boolean clockDrift, float[] gain) {
        Arrays.fill(gain, 1.f);
        var maxIncFactor = MAX_INC_FACTOR;
        for (var k = 0; k < FFT_LENGTH_BY_2_PLUS_1; ++k) {
            maxGain[k] = FloatMath.min(FloatMath.max(lastGain[k] * maxIncFactor, Aec3.FLOOR_FIRST_INCREASE), 1.f);
        }
        movingAverage(suppressorInput);
        weightEchoForAudibility(residualEcho);
        getMinGain(lowNoiseRender, saturatedEcho);
        gainToNoAudibleEcho(comfortNoise);
        for (var k = 0; k < FFT_LENGTH_BY_2_PLUS_1; ++k) {
            g[k] = FloatMath.max(FloatMath.min(g[k], maxGain[k]), minGain[k]);
            gain[k] = FloatMath.min(gain[k], g[k]);
        }
        System.arraycopy(nearend, 0, lastNearend, 0, FFT_LENGTH_BY_2_PLUS_1);
        System.arraycopy(weightedResidualEcho, 0, lastEcho, 0, FFT_LENGTH_BY_2_PLUS_1);
        gain[0] = gain[1] = FloatMath.min(gain[1], gain[2]);
        if (!nearendState || clockDrift) {
            var firstBandToLimit = (64 * 2000) / 8000;
            var minUpperGain = gain[firstBandToLimit];
            for (var k = firstBandToLimit + 1; k < FFT_LENGTH_BY_2_PLUS_1; k++) {
                gain[k] = FloatMath.min(gain[k], minUpperGain);
            }
            gain[FFT_LENGTH_BY_2] = gain[FFT_LENGTH_BY_2_MINUS_1];
        }
        System.arraycopy(gain, 0, lastGain, 0, FFT_LENGTH_BY_2_PLUS_1);
        for (var k = 0; k < FFT_LENGTH_BY_2_PLUS_1; k++) {
            gain[k] = (float) Math.sqrt(gain[k]);
        }
    }

    /**
     * Averages the near-end power over the last four blocks into {@link #nearend}.
     *
     * @param input the near-end power of the current block
     */
    private void movingAverage(float[] input) {
        System.arraycopy(input, 0, nearend, 0, FFT_LENGTH_BY_2_PLUS_1);
        for (var m = 0; m < nearendMemory.length; m += FFT_LENGTH_BY_2_PLUS_1) {
            for (var k = 0; k < FFT_LENGTH_BY_2_PLUS_1; k++) {
                nearend[k] = nearendMemory[m + k] + nearend[k];
            }
        }
        var scaling = 1.0f / (float) Aec3.NEAREND_AVERAGE_BLOCKS;
        for (var k = 0; k < FFT_LENGTH_BY_2_PLUS_1; k++) {
            nearend[k] *= scaling;
        }
        System.arraycopy(input, 0, nearendMemory, nearendMemoryIndex * FFT_LENGTH_BY_2_PLUS_1, FFT_LENGTH_BY_2_PLUS_1);
        nearendMemoryIndex = (nearendMemoryIndex + 1) % (Aec3.NEAREND_AVERAGE_BLOCKS - 1);
    }

    /**
     * Attenuates residual echo near the audibility floor into {@link #weightedResidualEcho}.
     *
     * @param echo the residual echo power
     */
    private void weightEchoForAudibility(float[] echo) {
        var threshold = Aec3.FLOOR_POWER * Aec3.AUDIBILITY_THRESHOLD;
        var normalizer = 1.f / (threshold - Aec3.FLOOR_POWER);
        weigh(threshold, normalizer, 0, 3, echo);
        weigh(threshold, normalizer, 3, 7, echo);
        weigh(threshold, normalizer, 7, FFT_LENGTH_BY_2_PLUS_1, echo);
    }

    /**
     * Weighs the residual echo of a bin range.
     *
     * @param threshold the audibility threshold
     * @param normalizer {@code 1 / (threshold - floor)}
     * @param begin the first bin
     * @param end the bin after the last
     * @param echo the residual echo power
     */
    private void weigh(float threshold, float normalizer, int begin, int end, float[] echo) {
        for (var k = begin; k < end; ++k) {
            if (echo[k] < threshold) {
                var tmp = (threshold - echo[k]) * normalizer;
                weightedResidualEcho[k] = echo[k] * FloatMath.max(0.f, 1.f - tmp * tmp);
            } else {
                weightedResidualEcho[k] = echo[k];
            }
        }
    }

    /**
     * Computes the minimum gain into {@link #minGain}.
     *
     * @param lowNoiseRender whether the render signal is low-level noise
     * @param saturatedEcho whether the echo is saturated
     */
    private void getMinGain(boolean lowNoiseRender, boolean saturatedEcho) {
        if (!saturatedEcho) {
            var minEchoPower = lowNoiseRender ? Aec3.LOW_RENDER_LIMIT : Aec3.NORMAL_RENDER_LIMIT;
            for (var k = 0; k < FFT_LENGTH_BY_2_PLUS_1; ++k) {
                minGain[k] = weightedResidualEcho[k] > 0.f ? minEchoPower / weightedResidualEcho[k] : 1.f;
                minGain[k] = FloatMath.min(minGain[k], 1.f);
            }
            var dec = MAX_DEC_FACTOR_LF;
            for (var k = 0; k <= Aec3.LAST_LF_SMOOTHING_BAND; ++k) {
                if (lastNearend[k] > lastEcho[k] || k <= Aec3.LAST_PERMANENT_LF_SMOOTHING_BAND) {
                    minGain[k] = FloatMath.max(minGain[k], lastGain[k] * dec);
                    minGain[k] = FloatMath.min(minGain[k], 1.f);
                }
            }
        } else {
            Arrays.fill(minGain, 0.f);
        }
    }

    /**
     * Computes the gain that masks the residual echo into {@link #g}.
     *
     * @param masker the comfort noise power, which masks the echo
     */
    private void gainToNoAudibleEcho(float[] masker) {
        var p = nearendState ? nearendParams : normalParams;
        var enrTransparent = p[0];
        var enrSuppress = p[1];
        var emrTransparent = p[2];
        for (var k = 0; k < FFT_LENGTH_BY_2_PLUS_1; ++k) {
            var enr = weightedResidualEcho[k] / (nearend[k] + 1.f);
            var emr = weightedResidualEcho[k] / (masker[k] + 1.f);
            var gk = 1.0f;
            if (enr > enrTransparent[k] && emr > emrTransparent[k]) {
                gk = (enrSuppress[k] - enr) / (enrSuppress[k] - enrTransparent[k]);
                gk = FloatMath.max(gk, emrTransparent[k] / emr);
            }
            g[k] = gk;
        }
    }
}
