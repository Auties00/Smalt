package com.github.auties00.smalt.image.ops;

import com.github.auties00.smalt.internal.simd.Simd;

/**
 * Moves pixels for the eight orientation transforms: quarter-turn transposition with optional
 * mirroring, and row reversal.
 *
 * <p>Pixels are either 8-bit samples of a plane or 32-bit packed RGBA pixels; both are moved
 * as opaque units.
 */
sealed interface TransformKernel permits ScalarTransformKernel, VectorTransformKernel {
    /**
     * The implementation selected for this JVM: the vector implementation when
     * {@link Simd#ENABLED}, the scalar one otherwise.
     */
    TransformKernel INSTANCE = Simd.ENABLED ? VectorTransformKernel.create() : new ScalarTransformKernel();

    /**
     * The side of the square blocks both transpositions walk, in bytes, so that the rows written
     * by one block fill whole cache lines.
     */
    int BLOCK = 64;

    /**
     * Transposes a plane of 8-bit samples, optionally mirroring the result.
     *
     * <p>Source sample {@code (x, y)} is written to destination row {@code reverseRows ? width -
     * 1 - x : x} and column {@code reverseColumns ? height - 1 - y : y}; the destination is
     * {@code height} samples wide and {@code width} rows tall.
     *
     * @param src            the source samples
     * @param sOff           the index of the source's first sample
     * @param sStride        the source row stride
     * @param width          the source width
     * @param height         the source height
     * @param dst            the destination samples
     * @param dOff           the index of the destination's first sample
     * @param dStride        the destination row stride
     * @param reverseRows    whether destination rows run from the last source column
     * @param reverseColumns whether destination columns run from the last source row
     */
    void transpose8(byte[] src, int sOff, int sStride, int width, int height, byte[] dst, int dOff, int dStride,
                    boolean reverseRows, boolean reverseColumns);

    /**
     * Transposes a picture of 32-bit pixels, optionally mirroring the result, with the mapping of
     * {@link #transpose8}.
     *
     * @param src            the source bytes
     * @param sOff           the index of the source's first byte
     * @param sStride        the source row stride in bytes
     * @param width          the source width in pixels
     * @param height         the source height in pixels
     * @param dst            the destination bytes
     * @param dOff           the index of the destination's first byte
     * @param dStride        the destination row stride in bytes
     * @param reverseRows    whether destination rows run from the last source column
     * @param reverseColumns whether destination columns run from the last source row
     */
    void transpose32(byte[] src, int sOff, int sStride, int width, int height, byte[] dst, int dOff, int dStride,
                     boolean reverseRows, boolean reverseColumns);

    /**
     * Copies a row of 8-bit samples in reverse order.
     *
     * @param src   the source samples
     * @param sOff  the index of the row's first sample
     * @param dst   the destination samples
     * @param dOff  the index of the destination row's first sample
     * @param count the number of samples
     */
    void reverse8(byte[] src, int sOff, byte[] dst, int dOff, int count);

    /**
     * Copies a row of 32-bit pixels in reverse pixel order.
     *
     * @param src   the source bytes
     * @param sOff  the index of the row's first byte
     * @param dst   the destination bytes
     * @param dOff  the index of the destination row's first byte
     * @param count the number of pixels
     */
    void reverse32(byte[] src, int sOff, byte[] dst, int dOff, int count);
}
