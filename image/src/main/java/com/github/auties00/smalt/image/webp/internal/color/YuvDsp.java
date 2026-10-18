package com.github.auties00.smalt.image.webp.internal.color;

import com.github.auties00.smalt.internal.simd.Simd;

/**
 * Provides the per-pixel color conversion kernels of WebP lossy coding: limited-range BT.601
 * Y'CbCr to RGB with libwebp's 14-bit fixed-point formulas, and RGB to luma.
 *
 * @implSpec
 * Implementations must produce exactly the output of {@link ScalarYuvDsp}, the reference.
 */
public sealed interface YuvDsp permits ScalarYuvDsp, VectorYuvDsp {
    /**
     * The implementation selected for this JVM: vectorized when {@link Simd#ENABLED}, scalar
     * otherwise.
     */
    YuvDsp INSTANCE = Simd.ENABLED ? VectorYuvDsp.create() : new ScalarYuvDsp();

    /**
     * Converts one row of luma and per-pixel chroma to RGBA with opaque alpha.
     *
     * @param y      the luma samples
     * @param yPos   the index of the first luma sample
     * @param u      the Cb value of every pixel, from 0 to 255
     * @param v      the Cr value of every pixel, from 0 to 255
     * @param dst    the RGBA destination
     * @param dstPos the index of the first byte written
     * @param count  the number of pixels; {@code u} and {@code v} are read from index 0
     */
    void yuvToRgbaRow(byte[] y, int yPos, int[] u, int[] v, byte[] dst, int dstPos, int count);

    /**
     * Converts one row of RGBA pixels to limited-range BT.601 luma, rounding to nearest.
     *
     * @param rgba    the RGBA samples
     * @param rgbaPos the index of the first sample
     * @param y       the luma destination
     * @param yPos    the index of the first luma sample written
     * @param count   the number of pixels
     */
    void rgbaToYRow(byte[] rgba, int rgbaPos, byte[] y, int yPos, int count);
}
