package com.github.auties00.smalt.image.ops;

import com.github.auties00.smalt.internal.simd.Simd;

/**
 * Converts rows of pixels between packed RGBA and planar Y'CbCr.
 *
 * <p>Every method processes one row (or one pair of rows) of a picture, so the caller controls
 * the traversal and the chroma row selection. The arithmetic is the fixed-point arithmetic of
 * {@link ColorMatrix}; implementations produce bit-identical output.
 */
sealed interface ColorKernel permits ScalarColorKernel, VectorColorKernel {
    /**
     * Selects the chroma sample at the pixel's own column: 4:4:4, or chroma already upsampled.
     */
    int CHROMA_FULL = 0;

    /**
     * Selects the chroma sample at column {@code x >> 1}: nearest-neighbor horizontal
     * upsampling.
     */
    int CHROMA_NEAREST = 1;

    /**
     * Selects libjpeg's {@code h2v1} fancy (triangle) horizontal upsampling of one chroma row:
     * even pixels are {@code (3 * c[k] + c[k - 1] + 1) >> 2} and odd pixels
     * {@code (3 * c[k] + c[k + 1] + 2) >> 2}, where {@code k = x >> 1} and indices outside the
     * row are clamped to its ends.
     */
    int CHROMA_FANCY_H = 2;

    /**
     * Selects libjpeg's {@code h2v2} fancy (triangle) upsampling from the nearer and the farther
     * chroma row: with the column sums {@code s[k] = 3 * near[k] + far[k]}, even pixels are
     * {@code (3 * s[k] + s[k - 1] + 8) >> 4} and odd pixels {@code (3 * s[k] + s[k + 1] + 7) >> 4},
     * where {@code k = x >> 1} and indices outside the row are clamped to its ends.
     */
    int CHROMA_FANCY_HV = 3;

    /**
     * The implementation selected for this JVM: the vector implementation when
     * {@link Simd#ENABLED}, the scalar one otherwise.
     */
    ColorKernel INSTANCE = Simd.ENABLED ? VectorColorKernel.create() : new ScalarColorKernel();

    /**
     * Converts one row of Y'CbCr samples to packed RGBA.
     *
     * @param y      the luma plane
     * @param yOff   the index of the row's first luma sample
     * @param u      the Cb plane
     * @param v      the Cr plane
     * @param cOff   the index of the first sample of the (nearer) chroma row, in both chroma planes
     * @param farOff the index of the first sample of the farther chroma row, used only by
     *               {@link #CHROMA_FANCY_HV}
     * @param alpha  the alpha plane, or {@code null} for opaque output
     * @param aOff   the index of the row's first alpha sample
     * @param dst    the RGBA destination
     * @param dOff   the index of the row's first destination byte
     * @param width  the number of pixels; positive
     * @param mode   the chroma sampling mode, one of the {@code CHROMA_*} constants
     * @param m      the conversion coefficients
     */
    void yuvToRgba(byte[] y, int yOff, byte[] u, byte[] v, int cOff, int farOff, byte[] alpha, int aOff,
                   byte[] dst, int dOff, int width, int mode, ColorMatrix m);

    /**
     * Converts one row of luma samples to gray packed RGBA.
     *
     * @param y     the luma plane
     * @param yOff  the index of the row's first luma sample
     * @param alpha the alpha plane, or {@code null} for opaque output
     * @param aOff  the index of the row's first alpha sample
     * @param dst   the RGBA destination
     * @param dOff  the index of the row's first destination byte
     * @param width the number of pixels; positive
     * @param m     the conversion coefficients, whose range selects the luma expansion
     */
    void grayToRgba(byte[] y, int yOff, byte[] alpha, int aOff, byte[] dst, int dOff, int width, ColorMatrix m);

    /**
     * Computes the luma, and optionally copies the alpha, of one row of packed RGBA pixels.
     *
     * @param src   the RGBA source
     * @param sOff  the index of the row's first source byte
     * @param width the number of pixels; positive
     * @param y     the luma destination
     * @param yOff  the index of the row's first luma sample
     * @param alpha the alpha destination, or {@code null} to drop alpha
     * @param aOff  the index of the row's first alpha sample
     * @param m     the conversion coefficients
     */
    void rgbaToLuma(byte[] src, int sOff, int width, byte[] y, int yOff, byte[] alpha, int aOff, ColorMatrix m);

    /**
     * Computes one row of chroma samples from one or two rows of packed RGBA pixels, averaging
     * each block of pixels a chroma sample covers.
     *
     * <p>The average is exact: the color differences of the block's summed R, G and B are
     * rounded once. A block that extends past the right edge (odd width) or the bottom edge (the
     * caller passes the last row twice) reuses its available pixels, which averages exactly the
     * pixels it covers.
     *
     * @param src   the RGBA source
     * @param off0  the index of the first source byte of the upper row
     * @param off1  the index of the first source byte of the lower row; ignored unless
     *              {@code pairY}
     * @param width the number of pixels per row; positive
     * @param pairX whether a chroma sample covers two columns
     * @param pairY whether a chroma sample covers two rows
     * @param u     the Cb destination
     * @param v     the Cr destination
     * @param cOff  the index of the row's first chroma sample in both destinations
     * @param m     the conversion coefficients
     */
    void rgbaToChroma(byte[] src, int off0, int off1, int width, boolean pairX, boolean pairY,
                      byte[] u, byte[] v, int cOff, ColorMatrix m);
}
