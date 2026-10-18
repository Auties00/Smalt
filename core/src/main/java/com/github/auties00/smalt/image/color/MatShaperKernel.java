package com.github.auties00.smalt.image.color;

import com.github.auties00.smalt.internal.simd.Simd;

/**
 * Computes the 8-bit matrix-shaper transform of up to {@link #BLOCK} pixels.
 *
 * <p>Each pixel reads its R, G and B samples from the first three samples at its source index,
 * maps them through the input tables, applies the fixed-point matrix
 * {@snippet :
 * l = clamp((m[3k] * r + m[3k + 1] * g + m[3k + 2] * b + o[k] + 0x2000) >> 14, 0, 16384)
 * }
 * for each output {@code k}, and writes the output tables' samples to the first three samples at
 * its destination index. Integer arithmetic wraps on overflow, as in Little CMS.
 *
 * <p>Implementations are stateless and thread-safe, and return identical results.
 */
sealed interface MatShaperKernel permits ScalarMatShaperKernel, VectorMatShaperKernel {
    /**
     * The largest number of pixels per call.
     */
    int BLOCK = 64;

    /**
     * The number of {@code int}s of scratch space a call needs.
     */
    int SCRATCH = 6 * BLOCK;

    /**
     * The implementation in use: the vector one when {@link Simd#ENABLED}, the scalar one
     * otherwise.
     */
    MatShaperKernel INSTANCE = Simd.ENABLED ? VectorMatShaperKernel.create() : new ScalarMatShaperKernel();

    /**
     * Transforms up to {@link #BLOCK} pixels.
     *
     * @param src       the source samples
     * @param srcOffset the index of the first source pixel
     * @param srcStride the samples between consecutive source pixels
     * @param dst       the destination samples
     * @param dstOffset the index of the first destination pixel
     * @param dstStride the samples between consecutive destination pixels
     * @param count     the number of pixels, at most {@link #BLOCK}
     * @param tables    the tables and coefficients
     * @param scratch   at least {@link #SCRATCH} {@code int}s of scratch space
     */
    void apply(byte[] src, int srcOffset, int srcStride, byte[] dst, int dstOffset, int dstStride, int count,
               Tables tables, int[] scratch);

    /**
     * Holds the tables and coefficients of one matrix-shaper transform.
     *
     * @param red         the 1.14 linearized value of each red sample
     * @param green       the 1.14 linearized value of each green sample
     * @param blue        the 1.14 linearized value of each blue sample
     * @param matrix      the 1.14 matrix in row-major order
     * @param offset      the 1.14 offsets
     * @param redOut      the output red sample of each clamped 1.14 value
     * @param greenOut    the output green sample of each clamped 1.14 value
     * @param blueOut     the output blue sample of each clamped 1.14 value
     */
    record Tables(int[] red, int[] green, int[] blue, int[] matrix, int[] offset, byte[] redOut, byte[] greenOut,
                  byte[] blueOut) {
    }
}
