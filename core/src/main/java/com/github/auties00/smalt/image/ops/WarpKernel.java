package com.github.auties00.smalt.image.ops;

import com.github.auties00.smalt.internal.simd.Simd;

/**
 * Runs the interpolation loops of {@link Warp} on 8-bit pictures: the premultiplied weighted sums
 * of the taps of a run of output pixels, and their division back to straight RGBA.
 *
 * <p>The source is a padded array of packed pixels, one {@code int} per pixel with red in the low
 * byte and alpha in the high byte, so no tap ever falls outside it. For every output pixel
 * {@code i} of a run, {@code base[i]} is the index of its top-left tap, and the caller supplies one
 * weight per tap column and per tap row. A tap with weight {@code w = wx * wy} and alpha {@code a}
 * adds {@code w * a} to the alpha sum and {@code w * a * c} to each color sum, in that order of
 * single-precision operations, tap rows outermost; both implementations return bit-identical
 * results.
 */
sealed interface WarpKernel permits ScalarWarpKernel, VectorWarpKernel {
    /**
     * The implementation selected for this JVM: the vector implementation when
     * {@link Simd#ENABLED}, the scalar one otherwise.
     */
    WarpKernel INSTANCE = Simd.ENABLED ? VectorWarpKernel.create() : new ScalarWarpKernel();

    /**
     * Accumulates one row of two taps for every pixel of a run.
     *
     * @param src    the padded packed pixels
     * @param offset the offset of the tap row from each pixel's base index
     * @param base   the index of each pixel's top-left tap
     * @param w0     the weight of the first tap column of each pixel
     * @param w1     the weight of the second tap column
     * @param wy     the weight of this tap row
     * @param red    the red sums, updated
     * @param green  the green sums, updated
     * @param blue   the blue sums, updated
     * @param alpha  the alpha sums, updated
     * @param count  the number of pixels
     */
    void accumulate2(int[] src, int offset, int[] base, float[] w0, float[] w1, float[] wy, float[] red, float[] green,
                     float[] blue, float[] alpha, int count);

    /**
     * Accumulates one row of four taps for every pixel of a run.
     *
     * @param src    the padded packed pixels
     * @param offset the offset of the tap row from each pixel's base index
     * @param base   the index of each pixel's top-left tap
     * @param w0     the weight of the first tap column of each pixel
     * @param w1     the weight of the second tap column
     * @param w2     the weight of the third tap column
     * @param w3     the weight of the fourth tap column
     * @param wy     the weight of this tap row
     * @param red    the red sums, updated
     * @param green  the green sums, updated
     * @param blue   the blue sums, updated
     * @param alpha  the alpha sums, updated
     * @param count  the number of pixels
     */
    void accumulate4(int[] src, int offset, int[] base, float[] w0, float[] w1, float[] w2, float[] w3, float[] wy,
                     float[] red, float[] green, float[] blue, float[] alpha, int count);

    /**
     * Writes the accumulated sums of a run as straight 8-bit RGBA pixels.
     *
     * <p>The alpha of a pixel is {@code (int) (alpha + 0.5)}; a pixel whose alpha is not positive
     * becomes transparent black, and the others take each color sum times {@code 1 / alpha},
     * rounded to nearest and clamped to {@code 0..255}, with alpha clamped to {@code 255}.
     *
     * @param red       the red sums
     * @param green     the green sums
     * @param blue      the blue sums
     * @param alpha     the alpha sums
     * @param count     the number of pixels
     * @param dst       the RGBA destination
     * @param dstOffset the index of the first destination byte
     */
    void resolve(float[] red, float[] green, float[] blue, float[] alpha, int count, byte[] dst, int dstOffset);
}
