package com.github.auties00.smalt.image.ops;

import com.github.auties00.smalt.internal.simd.Simd;

/**
 * Runs the per-sample loops of the filters, adjustments and compositing: box-blur columns,
 * channel mixing, unsharp masking and blend modes.
 *
 * <p>Every method processes one row (or one run of samples) at a time, and both implementations
 * return bit-identical results for every input: integer methods compute exactly, and
 * floating-point methods perform the same single-precision operations in the same order (never a
 * fused multiply-add).
 */
sealed interface EffectKernel permits ScalarEffectKernel, VectorEffectKernel {
    /**
     * The implementation selected for this JVM: the vector implementation when
     * {@link Simd#ENABLED}, the scalar one otherwise.
     */
    EffectKernel INSTANCE = Simd.ENABLED ? VectorEffectKernel.create() : new ScalarEffectKernel();

    /**
     * Adds a multiple of a run of samples to an accumulator: {@code acc[i] += weight * src[i]}.
     *
     * @param src    the 8-bit samples
     * @param offset the index of the first sample
     * @param weight the multiplier, small enough for the sums to fit 32 bits
     * @param acc    the accumulators, from index {@code 0}
     * @param length the number of samples
     */
    void boxAccumulate(byte[] src, int offset, int weight, int[] acc, int length);

    /**
     * Advances the sliding sums of an extended box filter by one row and writes the filtered row.
     *
     * <p>For each {@code i}: {@code acc[i] += src[add + i] - src[subtract + i]}, then
     * {@code bulk = acc[i] * ww + (src[left + i] + src[right + i]) * fw} in unsigned 32-bit
     * arithmetic, and {@code dst[dstOffset + i] = (bulk + 2^23) >>> 24}.
     *
     * @param src       the 8-bit samples of the source rows
     * @param add       the index of the first sample of the row entering the window
     * @param subtract  the index of the first sample of the row leaving the window
     * @param left      the index of the first sample of the row at the window's far start
     * @param right     the index of the first sample of the row at the window's far end
     * @param acc       the sliding sums, from index {@code 0}
     * @param ww        the weight of the whole-sample part, in units of {@code 2^-24}
     * @param fw        the weight of each fractional end sample, in units of {@code 2^-24}
     * @param dst       the destination samples
     * @param dstOffset the index of the first destination sample
     * @param length    the number of samples
     */
    void boxRow(byte[] src, int add, int subtract, int left, int right, int[] acc, int ww, int fw, byte[] dst,
                int dstOffset, int length);

    /**
     * Mixes the color channels of a row of packed 8-bit RGBA pixels through a fixed-point matrix.
     *
     * <p>Each output channel {@code k} is
     * {@code clamp((m[4k] * r + m[4k + 1] * g + m[4k + 2] * b + m[4k + 3]) >> 16, 0, 255)} with
     * an arithmetic shift; alpha is copied.
     *
     * @param src       the RGBA source
     * @param srcOffset the index of the row's first source byte
     * @param width     the number of pixels
     * @param matrix    the twelve coefficients, in units of {@code 2^-16}; the fourth of each row
     *                  is an offset that includes the rounding term
     * @param dst       the RGBA destination
     * @param dstOffset the index of the row's first destination byte
     */
    void mixRow(byte[] src, int srcOffset, int width, int[] matrix, byte[] dst, int dstOffset);

    /**
     * Sharpens a run of 8-bit samples against their blurred copy.
     *
     * <p>For each sample, with {@code diff = original - blurred}: the result is
     * {@code clamp(original + diff * percent / 100, 0, 255)} (the quotient truncated towards
     * zero) when {@code |diff| > threshold}, and {@code original} otherwise.
     *
     * @param original       the original samples
     * @param originalOffset the index of the first original sample
     * @param blurred        the blurred samples
     * @param blurredOffset  the index of the first blurred sample
     * @param length         the number of samples
     * @param percent        the strength in percent, {@code 0..10000}
     * @param threshold      the smallest difference that is sharpened, exclusive
     * @param dst            the destination samples, overlapping neither input run
     * @param dstOffset      the index of the first destination sample
     */
    void unsharpRow(byte[] original, int originalOffset, byte[] blurred, int blurredOffset, int length, int percent,
                    int threshold, byte[] dst, int dstOffset);

    /**
     * Computes the median of each sample's 3x3 neighborhood in a row of packed 8-bit pixels.
     *
     * <p>For sample {@code i}, the neighborhood is the samples at {@code offset + i - 4},
     * {@code offset + i} and {@code offset + i + 4} (the same channel of the pixels to the left,
     * here and to the right) of each of the three rows; the caller pads the rows so that these
     * indices exist.
     *
     * @param above     the row above, padded
     * @param row       the row itself, padded
     * @param below     the row below, padded
     * @param offset    the index of the first sample in each padded row, at least {@code 4}
     * @param length    the number of samples
     * @param dst       the destination samples
     * @param dstOffset the index of the first destination sample
     */
    void median9Row(byte[] above, byte[] row, byte[] below, int offset, int length, byte[] dst, int dstOffset);

    /**
     * Composites a row of source pixels over a row of backdrop pixels with a blend mode, in
     * planar single-precision floats normalized to {@code [0, 1]} with straight alpha.
     *
     * <p>With {@code as = sa * opacity}, the blended color is
     * {@code cs' = (1 - ba) * cs + ba * B(cb, cs)} for the mode's function {@code B}; the result
     * alpha is {@code ao = as + ba * (1 - as)} and the result color
     * {@code (as * cs' + ba * (1 - as) * cb) / ao}, or {@code 0} when {@code ao} is {@code 0}
     * ({@link BlendMode#NORMAL} takes {@code cs' = cs}). The result replaces the backdrop
     * arrays, and the mixed colors {@code cs'} replace the source color arrays.
     *
     * @param mode    the blend mode
     * @param br      the backdrop red values, replaced by the result
     * @param bg      the backdrop green values, replaced by the result
     * @param bb      the backdrop blue values, replaced by the result
     * @param ba      the backdrop alpha values, replaced by the result
     * @param sr      the source red values, replaced by the mixed colors
     * @param sg      the source green values, replaced by the mixed colors
     * @param sb      the source blue values, replaced by the mixed colors
     * @param sa      the source alpha values
     * @param opacity the factor applied to the source alpha
     * @param length  the number of pixels
     */
    void blend(BlendMode mode, float[] br, float[] bg, float[] bb, float[] ba, float[] sr, float[] sg, float[] sb,
               float[] sa, float opacity, int length);
}
