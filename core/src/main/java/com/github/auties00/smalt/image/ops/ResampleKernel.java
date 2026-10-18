package com.github.auties00.smalt.image.ops;

import com.github.auties00.smalt.internal.simd.Simd;

/**
 * Runs the inner loops of separable resampling: the vertical pass over whole rows, the
 * horizontal pass over a block of rows, the lane (de)interleaving between them, and the alpha
 * premultiplication around them.
 *
 * <p>All arithmetic is integer and exact, so every implementation produces identical output. The
 * vertical pass multiplies source rows by per-output-row weights. The horizontal pass works on a
 * block of {@code lanes} rows whose values are interleaved lane by lane (value {@code x} of row
 * {@code r} at index {@code x * lanes + r}), so one vector holds the same column of every row of
 * the block and each tap is a single multiply-add with a broadcast weight; the weights are laid
 * out as described by {@link ResampleWeights}.
 */
sealed interface ResampleKernel permits ScalarResampleKernel, VectorResampleKernel {
    /**
     * The implementation selected for this JVM: the vector implementation when
     * {@link Simd#ENABLED}, the scalar one otherwise.
     */
    ResampleKernel INSTANCE = Simd.ENABLED ? VectorResampleKernel.create() : new ScalarResampleKernel();

    /**
     * Returns the implementation one resampling operation computes with.
     *
     * <p>The implementations differ in {@link #lanes()}, which sizes the intermediate blocks of an
     * operation, so an operation selects its implementation once and uses it throughout. Every
     * implementation produces the same output.
     *
     * @return {@link #INSTANCE}
     */
    static ResampleKernel select() {
        return INSTANCE;
    }

    /**
     * Returns the number of rows the horizontal pass processes together, the {@code lanes}
     * argument of the block methods.
     *
     * @return the block height; positive
     */
    int lanes();

    /**
     * Filters rows of 8-bit samples vertically into one row of intermediate values.
     *
     * <p>Computes {@code dst[x] = (sum(weights[wOff + k] * src[offsets[k] + x]) + half) >> shift}
     * for {@code x < length} and {@code k < taps}, with {@code half = 1 << (shift - 1)}.
     *
     * @param src the source samples
     * @param offsets the index of the first sample of each tap row
     * @param weights the fixed-point tap weights
     * @param wOff the index of the first weight
     * @param taps the number of tap rows; positive
     * @param length the number of samples per row; positive
     * @param dst the destination row, at least {@code length} long
     * @param shift the right shift applied to each sum; positive
     */
    void vertical(byte[] src, int[] offsets, int[] weights, int wOff, int taps, int length, int[] dst, int shift);

    /**
     * Filters rows of 16-bit samples vertically into one row of intermediate values, optionally
     * accumulating over several calls.
     *
     * <p>Computes {@code s[x] = sum(weights[wOff + k] * src[offsets[k] + x])} for
     * {@code x < length} and {@code k < taps}; stores {@code s[x]} (or adds it to {@code dst[x]}
     * when {@code accumulate}), and then, when {@code shift} is positive, replaces each
     * {@code dst[x]} by {@code (dst[x] + (1 << (shift - 1))) >> shift}.
     *
     * @param src the source samples, read unsigned
     * @param offsets the index of the first sample of each tap row
     * @param weights the fixed-point tap weights
     * @param wOff the index of the first weight
     * @param taps the number of tap rows; positive
     * @param length the number of samples per row; positive
     * @param dst the destination row, at least {@code length} long
     * @param accumulate whether to add to the destination instead of overwriting it
     * @param shift the final right shift, or {@code 0} to leave the sums unshifted
     */
    void verticalWide(char[] src, int[] offsets, int[] weights, int wOff, int taps, int length, int[] dst,
                      boolean accumulate, int shift);

    /**
     * Interleaves one channel of a block of rows lane by lane.
     *
     * <p>Writes {@code rows[r][offset + x * pixelStride]} to {@code dst[x * lanes + r]} for
     * {@code r < rowCount} and {@code x < length}; lanes from {@code rowCount} to {@code lanes}
     * keep their previous values.
     *
     * @param rows the rows of the block
     * @param rowCount the number of rows to read; at most {@code lanes}
     * @param offset the index of the channel's first value in every row
     * @param pixelStride the distance between consecutive values of the channel
     * @param length the number of values per row
     * @param lanes the number of lanes of the block
     * @param dst the interleaved destination, at least {@code length * lanes} long
     */
    void gatherRows(int[][] rows, int rowCount, int offset, int pixelStride, int length, int lanes, int[] dst);

    /**
     * Filters a lane-interleaved block of rows horizontally into lane-interleaved 8-bit samples.
     *
     * <p>For every lane {@code r < lanes} and {@code i < count}, computes
     * {@code dst[i * lanes + r] = clamp((sum(weights[i * stride + k] * src[(starts[i] + k) * lanes
     * + r]) + half) >> shift)} over {@code k < counts[i]}.
     *
     * @param src the lane-interleaved intermediate values
     * @param lanes the number of lanes; {@link #lanes()} for the vector implementation
     * @param starts the first tap of each output sample
     * @param counts the number of taps of each output sample
     * @param weights the fixed-point tap weights, {@code stride} per output sample
     * @param stride the number of weights per output sample
     * @param count the number of output samples
     * @param dst the lane-interleaved destination, at least {@code count * lanes + 8} long
     * @param shift the right shift applied to each sum; positive
     */
    void horizontalRows(int[] src, int lanes, int[] starts, int[] counts, int[] weights, int stride, int count,
                        byte[] dst, int shift);

    /**
     * Filters a lane-interleaved block of rows horizontally into unshifted, unclamped sums.
     *
     * <p>For every lane {@code r < lanes} and {@code i < count}, computes
     * {@code dst[i * lanes + r] = sum(weights[i * stride + k] * src[(starts[i] + k) * lanes + r])}
     * over {@code k < counts[i]}.
     *
     * @param src the lane-interleaved intermediate values
     * @param lanes the number of lanes; {@link #lanes()} for the vector implementation
     * @param starts the first tap of each output sample
     * @param counts the number of taps of each output sample
     * @param weights the fixed-point tap weights, {@code stride} per output sample
     * @param stride the number of weights per output sample
     * @param count the number of output samples
     * @param dst the lane-interleaved destination, at least {@code count * lanes} long
     */
    void horizontalRowsWide(int[] src, int lanes, int[] starts, int[] counts, int[] weights, int stride, int count, int[] dst);

    /**
     * Joins four planar rows of 8-bit samples into one row of packed RGBA pixels.
     *
     * <p>Writes {@code planes[pOff + c * width + x]} to {@code dst[dOff + 4 * x + c]}; with
     * {@code opaque}, the alpha plane is not read and every alpha is {@code 255}.
     *
     * @param planes the planar samples: red, green, blue, then alpha, {@code width} each
     * @param pOff the index of the first red sample
     * @param width the number of pixels
     * @param opaque whether to write opaque alpha instead of the fourth plane
     * @param dst the RGBA destination
     * @param dOff the index of the first output byte
     */
    void interleave(byte[] planes, int pOff, int width, boolean opaque, byte[] dst, int dOff);

    /**
     * Premultiplies one row of packed RGBA pixels into four planar 16-bit rows.
     *
     * <p>Writes {@code r * a}, {@code g * a}, {@code b * a} and {@code a * 255} of pixel
     * {@code x} to {@code dst[dOff + x]}, {@code dst[dOff + width + x]},
     * {@code dst[dOff + 2 * width + x]} and {@code dst[dOff + 3 * width + x]}: every channel is
     * then linear in the same unit, {@code 1 / 255} of a premultiplied 8-bit value.
     *
     * @param src the RGBA source
     * @param sOff the index of the row's first byte
     * @param width the number of pixels
     * @param dst the planar destination
     * @param dOff the index of the first red value
     */
    void premultiply(byte[] src, int sOff, int width, char[] dst, int dOff);

    /**
     * Divides planar premultiplied sums by their alpha and interleaves them into packed 8-bit
     * RGBA with exact rounding.
     *
     * <p>With {@code R}, {@code G}, {@code B}, {@code A} the sums of pixel {@code x} (in the
     * unit of {@link #premultiply} times {@code scale}), writes
     * {@code clamp(round(255 * R / A))} and likewise for green and blue, and
     * {@code clamp(round(A / (255 * scale)))} as alpha, where {@code round} rounds half up. A
     * pixel whose alpha sum is not positive becomes transparent black.
     *
     * @param src the planar sums
     * @param sOff the index of the first red sum; the other planes follow every {@code width} values
     * @param width the number of pixels
     * @param scale the fixed-point unit of the sums; positive
     * @param dst the RGBA destination
     * @param dOff the index of the first output byte
     */
    void unpremultiply(int[] src, int sOff, int width, int scale, byte[] dst, int dOff);

    /**
     * Writes a lane-interleaved block of 8-bit samples back to rows.
     *
     * <p>Writes {@code src[i * lanes + r]} to {@code dst[dOff + r * dStride + i]} for
     * {@code r < rowCount} and {@code i < count}. Blocks of 8 lanes are transposed 8x8 samples
     * at a time as eight 64-bit words.
     *
     * @param src the lane-interleaved samples
     * @param lanes the number of lanes
     * @param count the number of samples per row
     * @param rowCount the number of rows to write; at most {@code lanes}
     * @param dst the destination
     * @param dOff the index of the first row's first sample
     * @param dStride the destination row stride
     */
    static void scatterRows(byte[] src, int lanes, int count, int rowCount, byte[] dst, int dOff, int dStride) {
        var i = 0;
        if (lanes == 8 && rowCount == 8) {
            for (; i + 8 <= count; i += 8) {
                ScalarTransformKernel.transposeTile(src, i * 8, 8, dst, dOff + i, dStride);
            }
        }
        for (; i < count; i++) {
            for (var r = 0; r < rowCount; r++) {
                dst[dOff + r * dStride + i] = src[i * lanes + r];
            }
        }
    }
}
