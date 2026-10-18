package com.github.auties00.smalt.audio.voice.internal.aec3;

import com.github.auties00.smalt.audio.voice.internal.FloatMath;

import java.util.Arrays;

import static com.github.auties00.smalt.audio.voice.internal.aec3.Aec3.FFT_LENGTH_BY_2_PLUS_1;

/**
 * Estimates the power spectrum of the echo left after the linear filter: the linear estimate
 * divided by the ERLE when the filter is usable, otherwise the render power at the echo path
 * delay times the echo path gain, in both cases plus the modeled reverberation.
 *
 * @implNote This implementation ports WebRTC's
 * {@code modules/audio_processing/aec3/residual_echo_estimator.cc} for one render and one
 * capture channel with the default configuration (no field trials, no stationarity
 * properties).
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
     * The number of blocks the noise floor of each bin has been held.
     */
    private final int[] x2NoiseFloorCounter = new int[FFT_LENGTH_BY_2_PLUS_1];

    /**
     * The reverberation model of the echo.
     */
    private final ReverbModel echoReverb = new ReverbModel();

    /**
     * The scratch buffer of the echo generating render power.
     */
    private final float[] x2 = new float[FFT_LENGTH_BY_2_PLUS_1];

    /**
     * Creates an estimator.
     */
    ResidualEchoEstimator() {
        Arrays.fill(x2NoiseFloorCounter, Aec3.NOISE_FLOOR_HOLD);
        Arrays.fill(x2NoiseFloor, Aec3.MIN_NOISE_FLOOR_POWER);
    }

    /**
     * Estimates the residual echo power.
     *
     * @param aecState        the canceller state
     * @param renderBuffer    the render rings
     * @param s2Linear        the power spectrum of the linear echo estimate
     * @param y2              the capture power spectrum
     * @param dominantNearend whether the near end dominates
     * @param r2              receives the residual echo power
     * @param r2Unbounded     receives the residual echo power computed with the unbounded ERLE
     */
    void estimate(AecState aecState, RenderBuffer renderBuffer, float[] s2Linear, float[] y2, boolean dominantNearend,
                  float[] r2, float[] r2Unbounded) {
        updateRenderNoisePower(renderBuffer);
        if (aecState.usableLinearEstimate()) {
            if (aecState.saturatedEcho()) {
                System.arraycopy(y2, 0, r2, 0, FFT_LENGTH_BY_2_PLUS_1);
                System.arraycopy(y2, 0, r2Unbounded, 0, FFT_LENGTH_BY_2_PLUS_1);
            } else {
                var onsetCompensated = !dominantNearend;
                linearEstimate(s2Linear, aecState.erle(onsetCompensated), r2);
                linearEstimate(s2Linear, aecState.erleUnbounded(), r2Unbounded);
            }
            var firstReverbPartition = aecState.filterLengthBlocks() + 1;
            echoReverb.updateReverb(renderBuffer.spectra, renderBuffer.spectrumOffset(firstReverbPartition),
                    aecState.reverbFrequencyResponse(), aecState.reverbDecay());
            addReverb(r2);
            addReverb(r2Unbounded);
        } else {
            var echoPathGain = echoPathGain(aecState);
            if (aecState.saturatedEcho()) {
                System.arraycopy(y2, 0, r2, 0, FFT_LENGTH_BY_2_PLUS_1);
                System.arraycopy(y2, 0, r2Unbounded, 0, FFT_LENGTH_BY_2_PLUS_1);
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
                for (var k = 0; k < FFT_LENGTH_BY_2_PLUS_1; ++k) {
                    r2[k] = x2[k] * echoPathGain;
                }
                for (var k = 0; k < FFT_LENGTH_BY_2_PLUS_1; ++k) {
                    r2Unbounded[k] = x2[k] * echoPathGain;
                }
            }
            if (!aecState.transparentModeActive()) {
                var firstReverbPartition = aecState.minDirectPathFilterDelay() + 1;
                echoReverb.updateReverbNoFreqShaping(renderBuffer.spectra, renderBuffer.spectrumOffset(firstReverbPartition),
                        echoPathGain(aecState), aecState.reverbDecay());
                addReverb(r2);
                addReverb(r2Unbounded);
            }
        }
    }

    /**
     * Divides the linear echo power by the ERLE.
     *
     * @param s2Linear the linear echo power
     * @param erle     the ERLE
     * @param r2       receives the residual echo power
     */
    private static void linearEstimate(float[] s2Linear, float[] erle, float[] r2) {
        for (var k = 0; k < FFT_LENGTH_BY_2_PLUS_1; ++k) {
            r2[k] = s2Linear[k] / erle[k];
        }
    }

    /**
     * Computes the largest render power around the direct path delay, one block before to one
     * block after.
     *
     * @param renderBuffer      the render rings
     * @param filterDelayBlocks the direct path delay in blocks
     */
    private void echoGeneratingPower(RenderBuffer renderBuffer, int filterDelayBlocks) {
        var windowStart = Math.max(0, filterDelayBlocks - Aec3.RENDER_PRE_WINDOW_SIZE);
        var windowEnd = filterDelayBlocks + Aec3.RENDER_POST_WINDOW_SIZE;
        var idxStart = RenderBuffer.offsetIndex(renderBuffer.spectrumRead, windowStart);
        var idxStop = RenderBuffer.offsetIndex(renderBuffer.spectrumRead, windowEnd + 1);
        Arrays.fill(x2, 0.f);
        var spectra = renderBuffer.spectra;
        for (var k = idxStart; k != idxStop; k = RenderBuffer.incIndex(k)) {
            var o = k * FFT_LENGTH_BY_2_PLUS_1;
            for (var j = 0; j < FFT_LENGTH_BY_2_PLUS_1; ++j) {
                x2[j] = FloatMath.max(x2[j], spectra[o + j]);
            }
        }
    }

    /**
     * Updates the render noise floor with the latest render power.
     *
     * @param renderBuffer the render rings
     */
    private void updateRenderNoisePower(RenderBuffer renderBuffer) {
        var renderPower = renderBuffer.spectra;
        var o = renderBuffer.spectrumOffset(0);
        for (var k = 0; k < FFT_LENGTH_BY_2_PLUS_1; ++k) {
            if (renderPower[o + k] < x2NoiseFloor[k]) {
                x2NoiseFloor[k] = renderPower[o + k];
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

    /**
     * Adds the modeled reverberation to a residual echo power.
     *
     * @param r2 the residual echo power
     */
    private void addReverb(float[] r2) {
        var reverbPower = echoReverb.reverb;
        for (var k = 0; k < FFT_LENGTH_BY_2_PLUS_1; ++k) {
            r2[k] += reverbPower[k];
        }
    }

    /**
     * Returns the echo path power gain: the squared transparent-mode gain in transparent mode,
     * otherwise the squared default gain.
     *
     * @param aecState the canceller state
     * @return the power gain
     */
    private static float echoPathGain(AecState aecState) {
        var gainAmplitude = aecState.transparentModeActive() ? TRANSPARENT_MODE_GAIN : Aec3.EP_DEFAULT_GAIN;
        return gainAmplitude * gainAmplitude;
    }
}
