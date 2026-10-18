package com.github.auties00.smalt.audio.voice.internal.ns;

import com.github.auties00.smalt.audio.voice.internal.FloatMath;

/**
 * Holds the constants and fast approximations shared by the noise suppressor's estimators.
 *
 * @implNote This implementation copies {@code ns_common.h} and ports {@code fast_math.cc} of
 * WebRTC's {@code modules/audio_processing/ns}.
 */
final class NsConstants {
    /**
     * The analysis transform length.
     */
    static final int FFT_SIZE = 256;

    /**
     * The number of spectrum bins, {@code FFT_SIZE / 2 + 1}.
     */
    static final int BINS = FFT_SIZE / 2 + 1;

    /**
     * The number of samples of one 10 ms frame at 16 kHz.
     */
    static final int FRAME_SIZE = 160;

    /**
     * The number of samples the analysis window overlaps the previous frames by.
     */
    static final int OVERLAP_SIZE = FFT_SIZE - FRAME_SIZE;

    /**
     * The number of analyzed frames of the short start-up phase, during which a parametric
     * noise model is blended in.
     */
    static final int SHORT_STARTUP_PHASE_BLOCKS = 50;

    /**
     * The number of analyzed frames of the long start-up phase.
     */
    static final int LONG_STARTUP_PHASE_BLOCKS = 200;

    /**
     * The number of frames between two updates of the prior signal model.
     */
    static final int FEATURE_UPDATE_WINDOW_SIZE = 500;

    /**
     * The initial threshold of the likelihood-ratio feature.
     */
    static final float LTR_FEATURE_THR = 0.5f;

    /**
     * The histogram bin width of the likelihood-ratio feature.
     */
    static final float BIN_SIZE_LRT = 0.1f;

    /**
     * The histogram bin width of the spectral flatness feature.
     */
    static final float BIN_SIZE_SPEC_FLAT = 0.05f;

    /**
     * The histogram bin width of the spectral difference feature.
     */
    static final float BIN_SIZE_SPEC_DIFF = 0.1f;

    /**
     * The number of bins of each feature histogram.
     */
    static final int HISTOGRAM_SIZE = 1000;

    /**
     * Prevents instantiation.
     */
    private NsConstants() {
        throw new AssertionError();
    }

    /**
     * Returns the natural logarithm approximation {@code FastLog2f(x) * ln 2}.
     *
     * @param x a positive argument
     * @return the approximation of {@code ln(x)}
     */
    static float logApproximation(float x) {
        return FloatMath.fastLog2f(x) * 0.69314718056f;
    }

    /**
     * Returns the power approximation {@code powf(2, p * FastLog2f(x))}.
     *
     * @param x a positive base
     * @param p the exponent
     * @return the approximation of {@code x^p}
     */
    static float powApproximation(float x, float p) {
        return FloatMath.powf(2.f, p * FloatMath.fastLog2f(x));
    }

    /**
     * Returns the exponential approximation {@code PowApproximation(10, x * log10(e))}.
     *
     * @param x the argument
     * @return the approximation of {@code e^x}
     */
    static float expApproximation(float x) {
        return powApproximation(10.f, x * 0.4342944819f);
    }
}
