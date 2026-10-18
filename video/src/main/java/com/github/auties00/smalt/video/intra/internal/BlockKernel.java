package com.github.auties00.smalt.video.intra.internal;

import com.github.auties00.smalt.internal.simd.Simd;

/**
 * Transforms, converts and quantizes batches of eight 8x8 blocks for the DCT-based intra codecs
 * (ProRes and VC-3).
 *
 * <p>A batch holds {@link #LANES} blocks interleaved in one {@code float} array of
 * {@link #BATCH_LENGTH} values: value {@code p} (in raster order, {@code p = row * 8 + column}) of
 * block {@code b} is at index {@code p * LANES + b}, so that one vector lane carries one block and
 * both passes of the separable transforms operate lane-wise without transposition. A partial batch
 * leaves its unused lanes at zero, which transform to zero.
 *
 * <p>The transforms are the orthonormal 8x8 DCT of IEEE Std 1180-1990, the one ProRes (SMPTE
 * RDD 36 clause 7.4) and VC-3 (SMPTE ST 2019-1 clause 8.2.8) specify:
 * {@snippet :
 * f(x, y) = 1/4 * sum(u, v) C(u) C(v) F(u, v) cos((2x + 1) u pi / 16) cos((2y + 1) v pi / 16)
 * }
 * with {@code C(0) = 1 / sqrt(2)} and {@code C(n) = 1} otherwise, computed in single precision
 * with the even-odd decomposition of the 8-point transform.
 *
 * <p>Every implementation returns bit-identical results for every input: they perform the same
 * single-precision operations in the same order, lane by lane.
 *
 * @implNote This implementation computes the transforms in floating point rather than with a
 *           fixed-point approximation because the codecs need fractional precision at the output
 *           (ProRes reconstructs 10 and 12-bit samples from a 9-bit transform domain) and
 *           single-precision arithmetic meets the accuracy requirements of RDD 36 Annex A with a
 *           wide margin, while vectorizing as well as integer code.
 */
public sealed interface BlockKernel permits ScalarBlockKernel, VectorBlockKernel {
    /**
     * The number of blocks of a batch.
     */
    int LANES = 8;

    /**
     * The length of a batch: 64 values of {@link #LANES} blocks.
     */
    int BATCH_LENGTH = 64 * LANES;

    /**
     * The implementation selected for this JVM: the vector implementation when
     * {@link Simd#ENABLED}, the scalar one otherwise.
     */
    BlockKernel INSTANCE = Simd.ENABLED ? VectorBlockKernels.create() : new ScalarBlockKernel();

    /**
     * Replaces the DCT coefficients of a batch of blocks with their inverse transform.
     *
     * @param batch the interleaved coefficients, {@link #BATCH_LENGTH} values; the reconstructed
     *              samples on return
     */
    void inverse(float[] batch);

    /**
     * Replaces the samples of a batch of blocks with their forward transform.
     *
     * @param batch the interleaved samples, {@link #BATCH_LENGTH} values; the DCT coefficients on
     *              return
     */
    void forward(float[] batch);

    /**
     * Converts reconstructed values to integer samples:
     * {@code out[i] = (int) min(max(batch[i] * scale + offset, 0), limit)}.
     *
     * <p>With {@code offset} holding the level shift plus one half and {@code limit} the largest
     * sample plus one half, this rounds to nearest (halves up) and clamps to the sample range.
     *
     * @param batch  the interleaved values, {@link #BATCH_LENGTH} values
     * @param scale  the factor applied first
     * @param offset the term added next
     * @param limit  the upper clamp, applied before truncation
     * @param out    the interleaved integer samples, {@link #BATCH_LENGTH} values
     */
    void toSamples(float[] batch, float scale, float offset, float limit, int[] out);

    /**
     * Quantizes the DCT coefficients of a batch:
     * {@code out[i] = sign(batch[i]) * (int) (abs(batch[i]) * weights[i] + rounding)}.
     *
     * @param batch    the interleaved coefficients, {@link #BATCH_LENGTH} values
     * @param weights  the reciprocal quantizer step of every value, interleaved like the batch
     * @param rounding the rounding offset added to the scaled magnitude, from 0 to 0.5
     * @param out      the interleaved quantized levels, {@link #BATCH_LENGTH} values
     */
    void quantize(float[] batch, float[] weights, float rounding, int[] out);

    /**
     * Loads eight 8x8 blocks of samples into a batch, one block per lane:
     * {@code batch[(row * 8 + column) * LANES + lane] = ((sample & 0xFFFF) - subtract) * scale}, with
     * {@code sample} the unsigned 16-bit value of {@code sources[lane]} at
     * {@code offsets[lane] + row * strides[lane] + column}. Lanes from {@code lanes} on are set to zero.
     *
     * @param sources  the sample plane of every lane; entries from {@code lanes} on are not read
     * @param offsets  the index of the first sample of the block of every lane
     * @param strides  the distance in samples between the rows of the block of every lane
     * @param lanes    the number of blocks, from 1 to {@link #LANES}
     * @param subtract the value subtracted from every sample
     * @param scale    the factor applied after the subtraction
     * @param batch    the interleaved values, {@link #BATCH_LENGTH} values
     * @param scratch  a work array of 64 values the caller owns
     */
    void loadBlocks(short[][] sources, int[] offsets, int[] strides, int lanes, float subtract, float scale,
                    float[] batch, float[] scratch);

    /**
     * Quantizes the magnitudes of the DCT coefficients of a batch, clamps them to a limit and
     * reports which are non-zero:
     * {@code out[i] = min((int) (abs(batch[i]) * weights[i] + rounding), limit)}.
     *
     * <p>The sign of a level is the sign of its coefficient. {@code masks[p]} receives the
     * non-zero levels of position {@code p}, bit {@code l} set when {@code out[p * LANES + l]} is
     * not zero.
     *
     * @param batch    the interleaved coefficients, {@link #BATCH_LENGTH} values
     * @param weights  the reciprocal quantizer step of every value, interleaved like the batch
     * @param rounding the rounding offset added to the scaled magnitude, from 0 to 0.5
     * @param limit    the largest magnitude a level may take, not negative
     * @param out      the interleaved level magnitudes, {@link #BATCH_LENGTH} values
     * @param masks    receives one lane mask per position, 64 values
     */
    void quantizeMasked(float[] batch, float[] weights, float rounding, int limit, int[] out, int[] masks);
}
