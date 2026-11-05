package com.github.auties00.smalt.image.color;

/**
 * Applies a linked color transform to interleaved floating-point samples.
 *
 * <p>Samples are in the encoding {@link ColorTransform#transform(float[], int, int, float[], int, int, int)}
 * documents: device values nominally {@code 0..1}, L*a*b* and XYZ in their natural units. The layout rules are those
 * of {@link ColorEngine}.
 *
 * <p>Implementations are immutable and thread-safe.
 */
sealed interface FloatEngine permits PipelineFloatEngine, IdentityEngine, HdrEngine, HdrTargetEngine {
    /**
     * Transforms floating-point pixels.
     *
     * @param src the source samples
     * @param srcOffset the index of the first source pixel
     * @param srcStride the samples between consecutive source pixels
     * @param dst the destination samples
     * @param dstOffset the index of the first destination pixel
     * @param dstStride the samples between consecutive destination pixels
     * @param count the number of pixels
     */
    void apply(float[] src, int srcOffset, int srcStride, float[] dst, int dstOffset, int dstStride, int count);
}
