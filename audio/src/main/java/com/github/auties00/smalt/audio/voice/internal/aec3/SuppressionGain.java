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
 * Computes the gain of the residual echo suppressor: per bin of the lowest band, the lowest gain
 * over the capture channels that makes the residual echo inaudible under the near-end signal and
 * the noise, smoothed over time, and one gain for the upper bands. A dominant near-end detector
 * switches to a more transparent tuning when the near end clearly dominates the echo.
 *
 * @implNote This implementation ports {@code SuppressionGain}, {@code DominantNearendDetector}
 * and {@code MovingAverage} of WebRTC's {@code modules/audio_processing/aec3} with the default
 * configuration and the suppressor tuning of an {@link Aec3Config}. The initial state only
 * matters to the low-frequency smoothing and the dominant near-end detection, which the default
 * configuration applies during the initial state too, so it is not tracked.
 */
final class SuppressionGain {
    /**
     * The masking thresholds of the normal tuning.
     */
    private final float[][] normalParams = gainParameters(0.3f, 0.4f, 0.3f, 0.07f, 0.1f, 0.3f);

    /**
     * The masking thresholds of the near-end tuning.
     */
    private final float[][] nearendParams = gainParameters(1.09f, 1.1f, 0.3f, 0.1f, 0.3f, 0.3f);

    /**
     * The largest gain increase per block of the near-end tuning.
     */
    private static final float NEAREND_MAX_INC_FACTOR = 2.0f;

    /**
     * The largest low-frequency gain decrease per block of the near-end tuning.
     */
    private static final float NEAREND_MAX_DEC_FACTOR_LF = 0.25f;

    /**
     * The band energy above which the anti-howling gain of the upper bands applies, per sample.
     */
    private static final float ANTI_HOWLING_ACTIVATION_THRESHOLD = 400.f;

    /**
     * The tunings.
     */
    private final Aec3Config config;

    /**
     * The number of capture channels.
     */
    private final int numChannels;

    /**
     * The gain of the previous block.
     */
    private final float[] lastGain = new float[FFT_LENGTH_BY_2_PLUS_1];

    /**
     * The smoothed near-end spectrum of the previous block, per channel.
     */
    private final float[][] lastNearend;

    /**
     * The weighted residual echo of the previous block, per channel.
     */
    private final float[][] lastEcho;

    /**
     * The smoothed render block energy of the low-noise detector.
     */
    private float averageRenderPower = 32768.f * 32768.f;

    /**
     * The past near-end spectra of the moving average of every channel.
     */
    private final float[][] nearendMemory;

    /**
     * The slot of the oldest past near-end spectrum.
     */
    private final int[] nearendMemoryIndex;

    /**
     * Whether the near end dominates.
     */
    private boolean nearendState;

    /**
     * The dominant near-end trigger counter of every channel.
     */
    private final int[] triggerCounters;

    /**
     * The dominant near-end hold counter of every channel.
     */
    private final int[] holdCounters;

    /**
     * The scratch smoothed near-end spectrum.
     */
    private final float[] nearend = new float[FFT_LENGTH_BY_2_PLUS_1];

    /**
     * The scratch weighted residual echo.
     */
    private final float[] weightedResidualEcho = new float[FFT_LENGTH_BY_2_PLUS_1];

    /**
     * The scratch lower gain bound.
     */
    private final float[] minGain = new float[FFT_LENGTH_BY_2_PLUS_1];

    /**
     * The scratch upper gain bound.
     */
    private final float[] maxGain = new float[FFT_LENGTH_BY_2_PLUS_1];

    /**
     * The scratch gain of one channel.
     */
    private final float[] g = new float[FFT_LENGTH_BY_2_PLUS_1];

    /**
     * The gain of the upper bands of the last block.
     */
    private float highBandsGain = 1.f;

    /**
     * Creates a gain computer.
     *
     * @param config the tunings
     * @param numChannels the number of capture channels
     */
    SuppressionGain(Aec3Config config, int numChannels) {
        this.config = config;
        this.numChannels = numChannels;
        this.lastNearend = new float[numChannels][FFT_LENGTH_BY_2_PLUS_1];
        this.lastEcho = new float[numChannels][FFT_LENGTH_BY_2_PLUS_1];
        this.nearendMemory = new float[numChannels][(Aec3.NEAREND_AVERAGE_BLOCKS - 1) * FFT_LENGTH_BY_2_PLUS_1];
        this.nearendMemoryIndex = new int[numChannels];
        this.triggerCounters = new int[numChannels];
        this.holdCounters = new int[numChannels];
        Arrays.fill(lastGain, 1.f);
    }

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

    boolean isDominantNearend() {
        return nearendState;
    }

    /**
     * {@return the gain of the upper bands computed by the last {@code getGain}}
     */
    float highBandsGain() {
        return highBandsGain;
    }

    /**
     * Computes the gains of a block.
     *
     * @param nearendSpectrum the near-end power spectra, per capture channel
     * @param echoSpectrum the echo power spectra the upper-band gain bound looks at, per channel
     * @param residualEchoSpectrum the residual echo power spectra, per channel
     * @param residualEchoUnbounded the residual echo power spectra with the unbounded ERLE
     * @param comfortNoiseSpectrum the comfort noise power spectra, per channel
     * @param renderSignalAnalyzer the narrow-band detector
     * @param saturatedEcho whether the echo saturates the capture signal
     * @param renderBuffer the render buffer, whose current block is used
     * @param clockDrift whether clock drift was detected
     * @param lowBandGain receives the 65 gains of the lowest band
     */
    void getGain(float[][] nearendSpectrum, float[][] echoSpectrum, float[][] residualEchoSpectrum,
                 float[][] residualEchoUnbounded, float[][] comfortNoiseSpectrum,
                 RenderSignalAnalyzer renderSignalAnalyzer, boolean saturatedEcho, RenderBuffer renderBuffer,
                 boolean clockDrift, float[] lowBandGain) {
        updateDominantNearend(nearendSpectrum, residualEchoUnbounded, comfortNoiseSpectrum);
        var lowNoiseRender = detectLowNoiseRender(renderBuffer);
        lowerBandGain(lowNoiseRender, saturatedEcho, nearendSpectrum, residualEchoSpectrum, comfortNoiseSpectrum,
                clockDrift, lowBandGain);
        highBandsGain = upperBandsGain(echoSpectrum, comfortNoiseSpectrum, renderSignalAnalyzer.narrowPeakBand(),
                saturatedEcho, renderBuffer, lowBandGain);
    }

    private void updateDominantNearend(float[][] nearendSpectrum, float[][] residualEchoSpectrum,
                                       float[][] comfortNoiseSpectrum) {
        nearendState = false;
        for (var ch = 0; ch < numChannels; ch++) {
            var neSum = lowFrequencyEnergy(nearendSpectrum[ch]);
            var echoSum = lowFrequencyEnergy(residualEchoSpectrum[ch]);
            var noiseSum = lowFrequencyEnergy(comfortNoiseSpectrum[ch]);
            if (echoSum < Aec3.DOMINANT_ENR_THRESHOLD * neSum && neSum > Aec3.DOMINANT_SNR_THRESHOLD * noiseSum) {
                if (++triggerCounters[ch] >= Aec3.DOMINANT_TRIGGER_THRESHOLD) {
                    holdCounters[ch] = Aec3.DOMINANT_HOLD_DURATION;
                    triggerCounters[ch] = Aec3.DOMINANT_TRIGGER_THRESHOLD;
                }
            } else {
                triggerCounters[ch] = Math.max(0, triggerCounters[ch] - 1);
            }
            if (echoSum > Aec3.DOMINANT_ENR_EXIT_THRESHOLD * neSum && echoSum > Aec3.DOMINANT_SNR_THRESHOLD * noiseSum) {
                holdCounters[ch] = 0;
            }
            holdCounters[ch] = Math.max(0, holdCounters[ch] - 1);
            nearendState = nearendState || holdCounters[ch] > 0;
        }
    }

    private static float lowFrequencyEnergy(float[] spectrum) {
        var sum = 0.f;
        for (var k = 1; k < 16; k++) {
            sum = sum + spectrum[k];
        }
        return sum;
    }

    private boolean detectLowNoiseRender(RenderBuffer renderBuffer) {
        var blocks = renderBuffer.blocks;
        var x2Sum = 0.f;
        var x2Max = 0.f;
        for (var ch = 0; ch < renderBuffer.numChannels; ch++) {
            var offset = renderBuffer.blockOffset(0, 0, ch);
            for (var i = 0; i < BLOCK_SIZE; i++) {
                var x2 = blocks[offset + i] * blocks[offset + i];
                x2Sum += x2;
                x2Max = FloatMath.max(x2Max, x2);
            }
        }
        x2Sum = x2Sum / renderBuffer.numChannels;
        var threshold = 50.f * 50.f * 64.f;
        var lowNoiseRender = averageRenderPower < threshold && x2Max < 3 * averageRenderPower;
        averageRenderPower = averageRenderPower * 0.9f + x2Sum * 0.1f;
        return lowNoiseRender;
    }

    private void lowerBandGain(boolean lowNoiseRender, boolean saturatedEcho, float[][] suppressorInput,
                               float[][] residualEcho, float[][] comfortNoise, boolean clockDrift, float[] gain) {
        Arrays.fill(gain, 1.f);
        var maxIncFactor = nearendState ? NEAREND_MAX_INC_FACTOR : config.normalMaxIncFactor();
        for (var k = 0; k < FFT_LENGTH_BY_2_PLUS_1; ++k) {
            maxGain[k] = FloatMath.min(FloatMath.max(lastGain[k] * maxIncFactor, Aec3.FLOOR_FIRST_INCREASE), 1.f);
        }
        for (var ch = 0; ch < numChannels; ch++) {
            movingAverage(ch, suppressorInput[ch]);
            weightEchoForAudibility(residualEcho[ch]);
            getMinGain(ch, lowNoiseRender, saturatedEcho);
            gainToNoAudibleEcho(comfortNoise[0]);
            for (var k = 0; k < FFT_LENGTH_BY_2_PLUS_1; ++k) {
                g[k] = FloatMath.max(FloatMath.min(g[k], maxGain[k]), minGain[k]);
                gain[k] = FloatMath.min(gain[k], g[k]);
            }
            System.arraycopy(nearend, 0, lastNearend[ch], 0, FFT_LENGTH_BY_2_PLUS_1);
            System.arraycopy(weightedResidualEcho, 0, lastEcho[ch], 0, FFT_LENGTH_BY_2_PLUS_1);
        }
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
     * Computes the gain of the upper bands ({@code UpperBandsGain}).
     *
     * @param echoSpectrum the echo power spectra, per capture channel
     * @param comfortNoiseSpectrum the comfort noise power spectra, per channel
     * @param narrowPeakBand the bin of the strong narrow-band render peak, or {@code -1}
     * @param saturatedEcho whether the echo saturates the capture signal
     * @param renderBuffer the render buffer
     * @param lowBandGain the gains of the lowest band
     * @return the gain
     */
    private float upperBandsGain(float[][] echoSpectrum, float[][] comfortNoiseSpectrum, int narrowPeakBand,
                                 boolean saturatedEcho, RenderBuffer renderBuffer, float[] lowBandGain) {
        var numBands = renderBuffer.numBands;
        if (numBands == 1) {
            return 1.f;
        }
        var numRenderChannels = renderBuffer.numChannels;
        if (narrowPeakBand >= 0 && narrowPeakBand > FFT_LENGTH_BY_2_PLUS_1 - 10) {
            return 0.001f;
        }
        var gainBelow8Khz = lowBandGain[FFT_LENGTH_BY_2 / 2];
        for (var k = FFT_LENGTH_BY_2 / 2 + 1; k < FFT_LENGTH_BY_2_PLUS_1; k++) {
            if (lowBandGain[k] < gainBelow8Khz) {
                gainBelow8Khz = lowBandGain[k];
            }
        }
        if (saturatedEcho) {
            return FloatMath.min(0.001f, gainBelow8Khz);
        }
        var blocks = renderBuffer.blocks;
        var lowBandEnergy = 0.f;
        for (var ch = 0; ch < numRenderChannels; ++ch) {
            var channelEnergy = sumOfSquares(blocks, renderBuffer.blockOffset(0, 0, ch));
            lowBandEnergy = FloatMath.max(lowBandEnergy, channelEnergy);
        }
        var highBandEnergy = 0.f;
        for (var k = 1; k < numBands; ++k) {
            for (var ch = 0; ch < numRenderChannels; ++ch) {
                var energy = sumOfSquares(blocks, renderBuffer.blockOffset(0, k, ch));
                highBandEnergy = FloatMath.max(highBandEnergy, energy);
            }
        }
        float antiHowlingGain;
        var activationThreshold = BLOCK_SIZE * ANTI_HOWLING_ACTIVATION_THRESHOLD;
        if (highBandEnergy < FloatMath.max(lowBandEnergy, activationThreshold)) {
            antiHowlingGain = 1.f;
        } else {
            antiHowlingGain = 1.f * (float) Math.sqrt(lowBandEnergy / highBandEnergy);
        }
        var gainBound = 1.f;
        if (!nearendState) {
            for (var ch = 0; ch < numChannels; ++ch) {
                var echoSum = lowFrequencyEnergy(echoSpectrum[ch]);
                var noiseSum = lowFrequencyEnergy(comfortNoiseSpectrum[ch]);
                if (echoSum > 1.f * noiseSum) {
                    gainBound = 1.f;
                    break;
                }
            }
        }
        return FloatMath.min(FloatMath.min(gainBelow8Khz, antiHowlingGain), gainBound);
    }

    private static float sumOfSquares(float[] x, int offset) {
        var sum = 0.f;
        for (var i = 0; i < BLOCK_SIZE; i++) {
            sum = sum + x[offset + i] * x[offset + i];
        }
        return sum;
    }

    private void movingAverage(int ch, float[] input) {
        var memory = nearendMemory[ch];
        System.arraycopy(input, 0, nearend, 0, FFT_LENGTH_BY_2_PLUS_1);
        for (var m = 0; m < memory.length; m += FFT_LENGTH_BY_2_PLUS_1) {
            for (var k = 0; k < FFT_LENGTH_BY_2_PLUS_1; k++) {
                nearend[k] = memory[m + k] + nearend[k];
            }
        }
        var scaling = 1.0f / (float) Aec3.NEAREND_AVERAGE_BLOCKS;
        for (var k = 0; k < FFT_LENGTH_BY_2_PLUS_1; k++) {
            nearend[k] *= scaling;
        }
        System.arraycopy(input, 0, memory, nearendMemoryIndex[ch] * FFT_LENGTH_BY_2_PLUS_1, FFT_LENGTH_BY_2_PLUS_1);
        nearendMemoryIndex[ch] = (nearendMemoryIndex[ch] + 1) % (Aec3.NEAREND_AVERAGE_BLOCKS - 1);
    }

    private void weightEchoForAudibility(float[] echo) {
        var threshold = Aec3.FLOOR_POWER * Aec3.AUDIBILITY_THRESHOLD;
        var normalizer = 1.f / (threshold - Aec3.FLOOR_POWER);
        weigh(threshold, normalizer, 0, 3, echo);
        weigh(threshold, normalizer, 3, 7, echo);
        weigh(threshold, normalizer, 7, FFT_LENGTH_BY_2_PLUS_1, echo);
    }

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

    private void getMinGain(int ch, boolean lowNoiseRender, boolean saturatedEcho) {
        if (!saturatedEcho) {
            var minEchoPower = lowNoiseRender ? Aec3.LOW_RENDER_LIMIT : Aec3.NORMAL_RENDER_LIMIT;
            for (var k = 0; k < FFT_LENGTH_BY_2_PLUS_1; ++k) {
                minGain[k] = weightedResidualEcho[k] > 0.f ? minEchoPower / weightedResidualEcho[k] : 1.f;
                minGain[k] = FloatMath.min(minGain[k], 1.f);
            }
            var dec = nearendState ? NEAREND_MAX_DEC_FACTOR_LF : config.normalMaxDecFactorLf();
            var lastNearendCh = lastNearend[ch];
            var lastEchoCh = lastEcho[ch];
            for (var k = 0; k <= Aec3.LAST_LF_SMOOTHING_BAND; ++k) {
                if (lastNearendCh[k] > lastEchoCh[k] || k <= Aec3.LAST_PERMANENT_LF_SMOOTHING_BAND) {
                    minGain[k] = FloatMath.max(minGain[k], lastGain[k] * dec);
                    minGain[k] = FloatMath.min(minGain[k], 1.f);
                }
            }
        } else {
            Arrays.fill(minGain, 0.f);
        }
    }

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
