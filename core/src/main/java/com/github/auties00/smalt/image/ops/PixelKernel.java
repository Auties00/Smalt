package com.github.auties00.smalt.image.ops;

import com.github.auties00.smalt.internal.simd.Simd;

/**
 * Runs the per-pixel loops of alpha handling: opacity scans and compositing over a background.
 *
 * <p>Compositing uses exact rounding: a straight-alpha sample {@code c} with alpha {@code a} over
 * a background {@code k} becomes {@code round((c * a + k * (255 - a)) / 255)}, computed as
 * {@code (t + 128 + ((t + 128) >> 8)) >> 8}, which equals the exactly rounded quotient for every
 * {@code t} in {@code 0..65025} (ties cannot occur because 255 is odd).
 */
sealed interface PixelKernel permits ScalarPixelKernel, VectorPixelKernel {
    /**
     * The implementation selected for this JVM: the vector implementation when
     * {@link Simd#ENABLED}, the scalar one otherwise.
     */
    PixelKernel INSTANCE = Simd.ENABLED ? VectorPixelKernel.create() : new ScalarPixelKernel();

    /**
     * Returns whether every alpha byte of one row of packed RGBA pixels is {@code 255}.
     *
     * @param pixels the RGBA samples
     * @param offset the index of the row's first byte
     * @param width  the number of pixels
     * @return whether the row is opaque
     */
    boolean isOpaqueRgba(byte[] pixels, int offset, int width);

    /**
     * Returns whether every sample of one row of a plane is {@code 255}.
     *
     * @param plane  the samples
     * @param offset the index of the row's first sample
     * @param width  the number of samples
     * @return whether the row is fully opaque
     */
    boolean isOpaquePlane(byte[] plane, int offset, int width);

    /**
     * Composites one row of straight-alpha RGBA pixels over an opaque color.
     *
     * @param src   the RGBA source
     * @param sOff  the index of the row's first source byte
     * @param width the number of pixels
     * @param dst   the RGBA destination, whose alpha becomes {@code 255}
     * @param dOff  the index of the row's first destination byte
     * @param rgb   the background as {@code 0xRRGGBB}
     */
    void flattenRgba(byte[] src, int sOff, int width, byte[] dst, int dOff, int rgb);

    /**
     * Composites one row of samples with a separate alpha row over a constant sample value.
     *
     * @param src        the samples
     * @param sOff       the index of the row's first sample
     * @param alpha      the alpha samples
     * @param aOff       the index of the row's first alpha sample
     * @param width      the number of samples
     * @param background the background sample value, {@code 0..255}
     * @param dst        the destination samples
     * @param dOff       the index of the row's first destination sample
     */
    void flattenPlane(byte[] src, int sOff, byte[] alpha, int aOff, int width, int background, byte[] dst, int dOff);
}
