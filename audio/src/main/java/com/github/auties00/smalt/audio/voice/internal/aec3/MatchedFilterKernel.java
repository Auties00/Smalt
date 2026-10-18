package com.github.auties00.smalt.audio.voice.internal.aec3;

/**
 * Runs the NLMS core of one matched filter over the 16 decimated capture samples of a block.
 *
 * <p>For each capture sample {@code y[i]} the core computes the filter output
 * {@code s = sum h[k] x[start_i + k]}, where {@code start_0 = xStartIndex} and each sample
 * moves the start back by one, wrapping within the ring of
 * {@link RenderDelayBuffer#LOW_RATE_SIZE} samples (whose start is mirrored after its end, so
 * the 512 taps are always contiguous), and the render energy
 * {@code x2 = sum x[...]^2} over the 512 taps, adds {@code (y[i] - s)^2} to the error sum, and,
 * when {@code x2} exceeds the threshold and {@code y[i]} is not saturated, adds
 * {@code smoothing * (y[i] - s) / x2 * x[...]} to every tap. With accumulated-error computation
 * enabled it also stores, for every fourth tap {@code k}, the squared error of the partial output
 * over taps {@code 0..k} summed over the samples.
 */
interface MatchedFilterKernel {
    /**
     * Runs the core of one filter.
     *
     * @param xStartIndex             the render ring index of the first tap for the first
     *                                capture sample
     * @param x2SumThreshold          the render energy below which the filter is not adapted
     * @param smoothing               the NLMS step size
     * @param x                       the decimated render ring with its mirrored tail
     * @param y                       the 16 decimated capture samples
     * @param h                       the filter bank taps
     * @param hOffset                 the index of the filter's first tap
     * @param computeAccumulatedError whether the accumulated error is computed
     * @param accumulatedError        receives the accumulated error, 128 entries, when computed
     * @param result                  receives the error sum at index 0 and {@code 1} at index 1
     *                                when the filter was adapted, {@code 0} otherwise
     */
    void core(int xStartIndex, float x2SumThreshold, float smoothing, float[] x, float[] y, float[] h, int hOffset,
              boolean computeAccumulatedError, float[] accumulatedError, float[] result);
}
