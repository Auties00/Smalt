/* Ports webrtc-audio-processing 2.1 (modules/audio_processing/aec3/residual_echo_estimator.cc). Copyright (c) 2011,
   The WebRTC project authors; BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.audio.voice.internal.aec3;

import com.github.auties00.smalt.audio.voice.internal.FloatMath;

import java.util.Arrays;

import static com.github.auties00.smalt.audio.voice.internal.aec3.Aec3.FFT_LENGTH_BY_2_PLUS_1;

/**
 * Estimates the power spectrum of the echo left after the linear filter in every capture channel:
 * the linear estimate divided by the ERLE when the filters are usable, otherwise the render power
 * at the echo path delay times the echo path gain, in both cases plus the modeled reverberation.
 * The render power of several channels is their sum.
 *
 * @implNote This implementation ports WebRTC's
 * {@code modules/audio_processing/aec3/residual_echo_estimator.cc} with the default
 * configuration (no field trials, no stationarity properties).
 */
final class ResidualEchoEstimator {
    /**
     * The echo path gain amplitude in transparent mode.
     */
    private static final float TRANSPARENT_MODE_GAIN = 0.01f;

    /**
     * The render noise floor per bin.
     */
    private final float[] x2NoiseFloor = new float[FFT_LENGTH_BY_2_PLUS_1];

    /**
     * The number of blocks every bin of the noise floor has been held.
     */
    private final int[] x2NoiseFloorCounter = new int[FFT_LENGTH_BY_2_PLUS_1];

    /**
     * The reverberation model of the echo.
     */
    private final ReverbModel echoReverb = new ReverbModel();

    /**
     * The scratch echo generating render power.
     */
    private final float[] x2 = new float[FFT_LENGTH_BY_2_PLUS_1];

    /**
     * The scratch render power of several channels.
     */
    private final float[] renderPower = new float[FFT_LENGTH_BY_2_PLUS_1];

    /**
     * The scratch summed render power of one slot.
     */
    private final float[] slotPower = new float[FFT_LENGTH_BY_2_PLUS_1];

    /**
     * Creates an estimator.
     */
    ResidualEchoEstimator() {
        Arrays.fill(x2NoiseFloorCounter, Aec3.NOISE_FLOOR_HOLD);
        Arrays.fill(x2NoiseFloor, Aec3.MIN_NOISE_FLOOR_POWER);
    }

    /**
     * Estimates the residual echo of every capture channel.
     *
     * @param aecState the canceller state
     * @param renderBuffer the render buffer
     * @param s2Linear the power spectra of the linear echo estimates
     * @param y2 the power spectra of the capture signal
     * @param dominantNearend whether the near end dominates
     * @param r2 receives the residual echo power spectra
     * @param r2Unbounded receives the residual echo power spectra with the unbounded ERLE
     * @param numChannels the number of capture channels
     */
    void estimate(AecState aecState, RenderBuffer renderBuffer, float[][] s2Linear, float[][] y2, boolean dominantNearend,
                  float[][] r2, float[][] r2Unbounded, int numChannels) {
        updateRenderNoisePower(renderBuffer);
        if (aecState.usableLinearEstimate()) {
            if (aecState.saturatedEcho()) {
                for (var ch = 0; ch < numChannels; ch++) {
                    System.arraycopy(y2[ch], 0, r2[ch], 0, FFT_LENGTH_BY_2_PLUS_1);
                    System.arraycopy(y2[ch], 0, r2Unbounded[ch], 0, FFT_LENGTH_BY_2_PLUS_1);
                }
            } else {
                var onsetCompensated = !dominantNearend;
                for (var ch = 0; ch < numChannels; ch++) {
                    linearEstimate(s2Linear[ch], aecState.erle(ch, onsetCompensated), r2[ch]);
                }
                for (var ch = 0; ch < numChannels; ch++) {
                    linearEstimate(s2Linear[ch], aecState.erleUnbounded(ch), r2Unbounded[ch]);
                }
            }
            var firstReverbPartition = aecState.filterLengthBlocks() + 1;
            var power = renderPower(renderBuffer, firstReverbPartition);
            echoReverb.updateReverb(power, powerOffset(renderBuffer, firstReverbPartition),
                    aecState.reverbFrequencyResponse(), aecState.reverbDecay());
            addReverb(r2, numChannels);
            addReverb(r2Unbounded, numChannels);
        } else {
            var echoPathGain = echoPathGain(aecState);
            if (aecState.saturatedEcho()) {
                for (var ch = 0; ch < numChannels; ch++) {
                    System.arraycopy(y2[ch], 0, r2[ch], 0, FFT_LENGTH_BY_2_PLUS_1);
                    System.arraycopy(y2[ch], 0, r2Unbounded[ch], 0, FFT_LENGTH_BY_2_PLUS_1);
                }
            } else {
                echoGeneratingPower(renderBuffer, aecState.minDirectPathFilterDelay());
                for (var k = 0; k < FFT_LENGTH_BY_2_PLUS_1; ++k) {
                    if (Aec3.NOISE_GATE_POWER > x2[k]) {
                        x2[k] = FloatMath.max(0.f, x2[k] - Aec3.NOISE_GATE_SLOPE * (Aec3.NOISE_GATE_POWER - x2[k]));
                    }
                }
                for (var k = 0; k < FFT_LENGTH_BY_2_PLUS_1; ++k) {
                    x2[k] -= Aec3.STATIONARY_GATE_SLOPE * x2NoiseFloor[k];
                    x2[k] = FloatMath.max(0.f, x2[k]);
                }
                for (var ch = 0; ch < numChannels; ch++) {
                    for (var k = 0; k < FFT_LENGTH_BY_2_PLUS_1; ++k) {
                        r2[ch][k] = x2[k] * echoPathGain;
                    }
                }
                for (var ch = 0; ch < numChannels; ch++) {
                    for (var k = 0; k < FFT_LENGTH_BY_2_PLUS_1; ++k) {
                        r2Unbounded[ch][k] = x2[k] * echoPathGain;
                    }
                }
            }
            if (!aecState.transparentModeActive()) {
                var firstReverbPartition = aecState.minDirectPathFilterDelay() + 1;
                var power = renderPower(renderBuffer, firstReverbPartition);
                echoReverb.updateReverbNoFreqShaping(power, powerOffset(renderBuffer, firstReverbPartition),
                        echoPathGain(aecState), aecState.reverbDecay());
                addReverb(r2, numChannels);
                addReverb(r2Unbounded, numChannels);
            }
        }
    }

    /**
     * Returns the array holding the render power of a spectrum slot: the spectra themselves with
     * one channel, the summed power otherwise.
     *
     * @param renderBuffer the render buffer
     * @param bufferOffset the spectrum relative to the current one
     * @return the array; read from {@link #powerOffset(RenderBuffer, int)}
     */
    private float[] renderPower(RenderBuffer renderBuffer, int bufferOffset) {
        if (renderBuffer.numChannels == 1) {
            return renderBuffer.spectra;
        }
        renderBuffer.channelSum(bufferOffset, renderPower);
        return renderPower;
    }

    /**
     * Returns the index of the render power of a spectrum slot in the array of
     * {@link #renderPower(RenderBuffer, int)}.
     *
     * @param renderBuffer the render buffer
     * @param bufferOffset the spectrum relative to the current one
     * @return the index
     */
    private static int powerOffset(RenderBuffer renderBuffer, int bufferOffset) {
        return renderBuffer.numChannels == 1 ? renderBuffer.spectrumOffset(bufferOffset, 0) : 0;
    }

    private static void linearEstimate(float[] s2Linear, float[] erle, float[] r2) {
        for (var k = 0; k < FFT_LENGTH_BY_2_PLUS_1; ++k) {
            r2[k] = s2Linear[k] / erle[k];
        }
    }

    private void echoGeneratingPower(RenderBuffer renderBuffer, int filterDelayBlocks) {
        var windowStart = Math.max(0, filterDelayBlocks - Aec3.RENDER_PRE_WINDOW_SIZE);
        var windowEnd = filterDelayBlocks + Aec3.RENDER_POST_WINDOW_SIZE;
        var idxStart = RenderBuffer.offsetIndex(renderBuffer.spectrumRead, windowStart);
        var idxStop = RenderBuffer.offsetIndex(renderBuffer.spectrumRead, windowEnd + 1);
        Arrays.fill(x2, 0.f);
        var spectra = renderBuffer.spectra;
        var numRenderChannels = renderBuffer.numChannels;
        if (numRenderChannels == 1) {
            for (var k = idxStart; k != idxStop; k = RenderBuffer.incIndex(k)) {
                var o = k * FFT_LENGTH_BY_2_PLUS_1;
                for (var j = 0; j < FFT_LENGTH_BY_2_PLUS_1; ++j) {
                    x2[j] = FloatMath.max(x2[j], spectra[o + j]);
                }
            }
        } else {
            for (var k = idxStart; k != idxStop; k = RenderBuffer.incIndex(k)) {
                Arrays.fill(slotPower, 0.f);
                for (var ch = 0; ch < numRenderChannels; ch++) {
                    var o = (k * numRenderChannels + ch) * FFT_LENGTH_BY_2_PLUS_1;
                    for (var j = 0; j < FFT_LENGTH_BY_2_PLUS_1; ++j) {
                        slotPower[j] += spectra[o + j];
                    }
                }
                for (var j = 0; j < FFT_LENGTH_BY_2_PLUS_1; ++j) {
                    x2[j] = FloatMath.max(x2[j], slotPower[j]);
                }
            }
        }
    }

    private void updateRenderNoisePower(RenderBuffer renderBuffer) {
        var power = renderPower(renderBuffer, 0);
        var o = powerOffset(renderBuffer, 0);
        for (var k = 0; k < FFT_LENGTH_BY_2_PLUS_1; ++k) {
            if (power[o + k] < x2NoiseFloor[k]) {
                x2NoiseFloor[k] = power[o + k];
                x2NoiseFloorCounter[k] = 0;
            } else {
                if (x2NoiseFloorCounter[k] >= Aec3.NOISE_FLOOR_HOLD) {
                    x2NoiseFloor[k] = FloatMath.max(x2NoiseFloor[k] * 1.1f, Aec3.MIN_NOISE_FLOOR_POWER);
                } else {
                    ++x2NoiseFloorCounter[k];
                }
            }
        }
    }

    private void addReverb(float[][] r2, int numChannels) {
        var reverbPower = echoReverb.reverb;
        for (var ch = 0; ch < numChannels; ch++) {
            var r = r2[ch];
            for (var k = 0; k < FFT_LENGTH_BY_2_PLUS_1; ++k) {
                r[k] += reverbPower[k];
            }
        }
    }

    private static float echoPathGain(AecState aecState) {
        var gainAmplitude = aecState.transparentModeActive() ? TRANSPARENT_MODE_GAIN : Aec3.EP_DEFAULT_GAIN;
        return gainAmplitude * gainAmplitude;
    }
}
