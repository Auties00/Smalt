package com.github.auties00.smalt.capture.internal.convert;

import com.github.auties00.smalt.internal.simd.Simd;

/**
 * Converts rows of captured pixels to planar 4:2:0 Y'CbCr.
 *
 * <p>Every method converts one row, or the two rows a row of 4:2:0 chroma covers, so the caller
 * controls the traversal, the strides and the cropping. Averages of two samples round half up,
 * {@code (a + b + 1) >> 1}, as {@link com.github.auties00.smalt.image.ops.ImageOps} does when it
 * halves a chroma plane; the R'G'B' conversions follow {@link YuvCoefficients}, so their output is
 * bit-identical to {@code ImageOps.toYuv} of the same pixels. The implementations produce identical
 * bytes: {@link ScalarConversionKernel} is the reference, {@link VectorConversionKernel} uses the
 * Vector API. Arguments are not checked; values outside the documented ranges give unspecified
 * results.
 */
public sealed interface ConversionKernel permits ScalarConversionKernel, VectorConversionKernel {
    /**
     * The implementation of this JVM: the vector one when {@link Simd#ENABLED}, else the scalar one.
     */
    ConversionKernel INSTANCE = Simd.ENABLED ? VectorConversionKernel.create() : new ScalarConversionKernel();

    /**
     * Computes the luma of one row of 4-byte R'G'B' pixels.
     *
     * @param src the pixels
     * @param off the index of the row's first byte
     * @param width the number of pixels
     * @param layout the positions of R, G and B in a pixel
     * @param coefficients the conversion to apply
     * @param y the luma plane
     * @param yOff the index of the row's first luma sample
     */
    void rgbToLuma(byte[] src, int off, int width, RgbLayout layout, YuvCoefficients coefficients,
                   byte[] y, int yOff);

    /**
     * Computes one row of 4:2:0 chroma from two rows of 4-byte R'G'B' pixels, each sample from the
     * sums over the 2x2 block it covers.
     *
     * @param src the pixels of both rows
     * @param off0 the index of the upper row's first byte
     * @param off1 the index of the lower row's first byte
     * @param pairs the number of chroma samples, half the number of pixels per row
     * @param layout the positions of R, G and B in a pixel
     * @param coefficients the conversion to apply
     * @param u the Cb plane
     * @param v the Cr plane
     * @param cOff the index of the first chroma sample in both planes
     */
    void rgbToChroma(byte[] src, int off0, int off1, int pairs, RgbLayout layout, YuvCoefficients coefficients,
                     byte[] u, byte[] v, int cOff);

    /**
     * Extracts the luma of one row of packed 4:2:2 macropixels ({@code Y0 Cb Y1 Cr} and its
     * permutations).
     *
     * @param src the packed samples
     * @param off the index of the row's first byte
     * @param width the number of pixels, even
     * @param lumaIndex the byte position of the first luma sample in a macropixel, 0 or 1
     * @param y the luma plane
     * @param yOff the index of the row's first luma sample
     */
    void packedLuma(byte[] src, int off, int width, int lumaIndex, byte[] y, int yOff);

    /**
     * Averages the chroma of two rows of packed 4:2:2 macropixels into one row of 4:2:0 chroma.
     *
     * @param src the packed samples of both rows
     * @param off0 the index of the upper row's first byte
     * @param off1 the index of the lower row's first byte
     * @param pairs the number of macropixels per row, which is the number of chroma samples
     * @param cbIndex the byte position of Cb in a macropixel
     * @param crIndex the byte position of Cr in a macropixel
     * @param u the Cb plane
     * @param v the Cr plane
     * @param cOff the index of the first chroma sample in both planes
     */
    void packedChroma(byte[] src, int off0, int off1, int pairs, int cbIndex, int crIndex,
                      byte[] u, byte[] v, int cOff);

    /**
     * Splits one row of interleaved sample pairs, such as the chroma row of NV12.
     *
     * @param src the interleaved samples
     * @param off the index of the row's first byte
     * @param count the number of pairs
     * @param first the destination of the first sample of every pair
     * @param second the destination of the second sample of every pair
     * @param dOff the index of the first sample in both destinations
     */
    void deinterleave(byte[] src, int off, int count, byte[] first, byte[] second, int dOff);

    /**
     * Averages two rows sample by sample.
     *
     * @param a the upper row's samples
     * @param aOff the index of the upper row's first sample
     * @param b the lower row's samples, which may be {@code a}
     * @param bOff the index of the lower row's first sample
     * @param count the number of samples
     * @param dst the destination
     * @param dOff the index of the first destination sample
     */
    void averageRows(byte[] a, int aOff, byte[] b, int bOff, int count, byte[] dst, int dOff);
}
