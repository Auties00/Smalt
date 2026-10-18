package com.github.auties00.smalt.audio.flac;

import com.github.auties00.smalt.internal.simd.Simd;

/**
 * Performs the data-parallel stages of FLAC decoding: inter-channel decorrelation and the
 * conversion of integer samples to scaled floats.
 *
 * <p>Two implementations exist: {@link ScalarFlacKernel}, the reference, and
 * {@link VectorFlacKernel}, which uses the Vector API. Both produce bit-identical output, because
 * every operation is either exact integer arithmetic or a single correctly rounded
 * integer-to-float conversion followed by a multiplication by a power of two.
 */
interface FlacKernel {
    /**
     * The implementation selected for this JVM: the vector implementation when
     * {@link Simd#ENABLED}, the scalar one otherwise.
     */
    FlacKernel INSTANCE = Simd.ENABLED ? VectorFlacKernel.create() : new ScalarFlacKernel();

    /**
     * Converts integer samples to floats: {@code out[i] = in[i] * scale}.
     *
     * @param in    the integer samples
     * @param out   the destination
     * @param count the number of samples
     * @param scale the factor, a power of two
     */
    void toFloat(int[] in, float[] out, int count, float scale);

    /**
     * Undoes left/side stereo: {@code side[i] = left[i] - side[i]}, turning the side channel
     * into the right channel.
     *
     * @param left  the left channel
     * @param side  the side channel, overwritten with the right channel
     * @param count the number of samples
     */
    void leftSide(int[] left, int[] side, int count);

    /**
     * Undoes side/right stereo: {@code side[i] = side[i] + right[i]}, turning the side channel
     * into the left channel.
     *
     * @param side  the side channel, overwritten with the left channel
     * @param right the right channel
     * @param count the number of samples
     */
    void sideRight(int[] side, int[] right, int count);

    /**
     * Undoes mid/side stereo, turning the mid channel into the left channel and the side channel
     * into the right channel.
     *
     * <p>With {@code m = (mid << 1) | (side & 1)}, left is {@code (m + side) >> 1} and right is
     * {@code (m - side) >> 1}.
     *
     * @param mid   the mid channel, overwritten with the left channel
     * @param side  the side channel, overwritten with the right channel
     * @param count the number of samples
     */
    void midSide(int[] mid, int[] side, int count);
}
