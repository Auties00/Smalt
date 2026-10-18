package com.github.auties00.smalt.image.color;

/**
 * Applies a linked color transform to interleaved samples of one depth.
 *
 * <p>Pixels are read {@code srcStride} samples apart and written {@code dstStride} samples
 * apart; each pixel reads the source color space's channels from its first samples and writes
 * the target color space's channels to its first samples, leaving any further samples of the
 * destination pixel (an alpha channel, for example) untouched. Source and destination may be the
 * same array at the same offset only when the strides are equal and the target has no more
 * channels than the source.
 *
 * <p>Implementations are immutable and thread-safe; each call allocates its own scratch space.
 */
sealed interface ColorEngine permits IdentityEngine, CurvesEngine, MatShaperEngine, PrelinEngine, ClutEngine,
        FloatShaperEngine, HdrEngine {
    /**
     * Transforms 8-bit pixels.
     *
     * @param src       the source samples
     * @param srcOffset the index of the first source pixel
     * @param srcStride the samples between consecutive source pixels
     * @param dst       the destination samples
     * @param dstOffset the index of the first destination pixel
     * @param dstStride the samples between consecutive destination pixels
     * @param count     the number of pixels
     */
    void apply(byte[] src, int srcOffset, int srcStride, byte[] dst, int dstOffset, int dstStride, int count);

    /**
     * Transforms 16-bit pixels, each sample an unsigned value in a {@code short}.
     *
     * @param src       the source samples
     * @param srcOffset the index of the first source pixel
     * @param srcStride the samples between consecutive source pixels
     * @param dst       the destination samples
     * @param dstOffset the index of the first destination pixel
     * @param dstStride the samples between consecutive destination pixels
     * @param count     the number of pixels
     */
    void apply(short[] src, int srcOffset, int srcStride, short[] dst, int dstOffset, int dstStride, int count);
}
