package com.github.auties00.smalt.image.jpeg.internal;

import com.github.auties00.smalt.internal.simd.Simd;

/**
 * Converts rows of packed RGBA pixels to full-range BT.601 Y'CbCr samples, the color space of
 * JFIF.
 *
 * <p>With {@code r}, {@code g}, {@code b} the 8-bit samples:
 * {@snippet :
 * Y  = (19595 * r + 38470 * g +  7471 * b + 32768) >> 16
 * Cb = (-11059 * r - 21709 * g + 32768 * b + (128 << 16) + 32767) >> 16
 * Cr = (32768 * r - 27439 * g -  5329 * b + (128 << 16) + 32767) >> 16
 * }
 * The constants are libjpeg's {@code FIX(0.29900)}, {@code FIX(0.58700)} and so on with 16
 * fraction bits, so the output is bit-exact with libjpeg-turbo's encoder ({@code rgb_ycc_convert}).
 * The alpha samples are ignored. Implementations must produce exactly the samples of
 * {@link ScalarColorKernel}.
 */
public interface ColorKernel {
    /**
     * The implementation selected for this JVM: the vector implementation when
     * {@link Simd#ENABLED}, the scalar one otherwise.
     */
    ColorKernel INSTANCE = Simd.ENABLED ? VectorKernels.color() : new ScalarColorKernel();

    /**
     * Converts one row to Y, Cb and Cr.
     *
     * @param rgba the packed pixels
     * @param offset the index of the first pixel's red sample
     * @param count the number of pixels
     * @param y the destination luma samples, starting at index 0
     * @param cb the destination Cb samples, starting at index 0
     * @param cr the destination Cr samples, starting at index 0
     */
    void toYcc(byte[] rgba, int offset, int count, byte[] y, byte[] cb, byte[] cr);

    /**
     * Converts one row to luma only.
     *
     * @param rgba the packed pixels
     * @param offset the index of the first pixel's red sample
     * @param count the number of pixels
     * @param y the destination luma samples, starting at index 0
     */
    void toGray(byte[] rgba, int offset, int count, byte[] y);
}
