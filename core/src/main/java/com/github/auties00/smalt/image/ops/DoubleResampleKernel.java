package com.github.auties00.smalt.image.ops;

import com.github.auties00.smalt.internal.simd.Simd;

/**
 * Runs the inner loops of separable resampling in double-precision floating point: the vertical
 * pass over whole rows and the horizontal pass over a lane-interleaved block of rows.
 *
 * <p>Every sum is formed in the same order by every implementation, tap by tap from the first, so
 * every implementation produces identical values. The horizontal pass works on a block of
 * {@code lanes} rows whose values are interleaved lane by lane (value {@code x} of row {@code r}
 * at index {@code x * lanes + r}); the weights are laid out as {@link ResampleWeights} lays out
 * its fixed-point ones.
 */
sealed interface DoubleResampleKernel permits ScalarDoubleResampleKernel, VectorDoubleResampleKernel {
    /**
     * The implementation selected for this JVM: the vector implementation when
     * {@link Simd#ENABLED}, the scalar one otherwise.
     */
    DoubleResampleKernel INSTANCE = Simd.ENABLED ? VectorDoubleResampleKernel.create() : new ScalarDoubleResampleKernel();

    /**
     * Returns the number of rows the horizontal pass processes together, the {@code lanes}
     * argument of {@link #horizontal}.
     *
     * @return the block height; positive
     */
    int lanes();

    /**
     * Filters rows vertically into one row, optionally accumulating over several calls.
     *
     * <p>Computes, for {@code x < length}, {@code s = accumulate ? dst[x] : 0} and then
     * {@code s += weights[wOff + k] * src[offsets[k] + x]} for {@code k} from {@code 0} to
     * {@code taps - 1}, and stores {@code s} in {@code dst[x]}.
     *
     * @param src the source rows
     * @param offsets the index of the first value of each tap row
     * @param weights the tap weights
     * @param wOff the index of the first weight
     * @param taps the number of tap rows; positive
     * @param length the number of values per row; positive
     * @param dst the destination row, at least {@code length} long
     * @param accumulate whether to add to the destination instead of overwriting it
     */
    void vertical(double[] src, int[] offsets, double[] weights, int wOff, int taps, int length, double[] dst,
                  boolean accumulate);

    /**
     * Filters a lane-interleaved block of rows horizontally.
     *
     * <p>For every lane {@code r < lanes} and {@code i < count}, computes {@code s = 0} and then
     * {@code s += weights[i * stride + k] * src[(starts[i] + k) * lanes + r]} for {@code k} from
     * {@code 0} to {@code counts[i] - 1}, and stores {@code s} in {@code dst[i * lanes + r]}.
     *
     * @param src the lane-interleaved values
     * @param lanes the number of lanes; {@link #lanes()} for the vector implementation
     * @param starts the first tap of each output sample
     * @param counts the number of taps of each output sample
     * @param weights the tap weights, {@code stride} per output sample
     * @param stride the number of weights per output sample
     * @param count the number of output samples
     * @param dst the lane-interleaved destination, at least {@code count * lanes} long
     */
    void horizontal(double[] src, int lanes, int[] starts, int[] counts, double[] weights, int stride, int count,
                    double[] dst);
}
