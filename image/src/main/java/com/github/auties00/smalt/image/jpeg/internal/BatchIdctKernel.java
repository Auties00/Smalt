package com.github.auties00.smalt.image.jpeg.internal;

import com.github.auties00.smalt.internal.simd.Simd;

/**
 * Computes the inverse DCT of up to {@link #LANES} horizontally adjacent blocks of one component
 * at once, at full size or reduced to 4x4 or 2x2 samples per block.
 *
 * <p>The blocks are held interleaved in one coefficient array of {@link #BATCH_LENGTH} values:
 * dequantized coefficient {@code k} (natural order) of block {@code b} is at index
 * {@code k * LANES + b}. The blocks' samples are written side by side along the output rows: row
 * {@code r} of block {@code b} starts at {@code offset + r * stride + b * size}, where
 * {@code size} is 8, 4 or 2. Only the first {@code count} blocks are written, and the whole
 * coefficient array is cleared on return.
 *
 * <p>Every method produces, for each block, exactly the samples of the per-block transforms:
 * {@link ScalarIdctKernel#idct8} for the full size and {@link ReducedIdct#idct4} and
 * {@link ReducedIdct#idct2} for the reduced sizes, for every input including inputs whose
 * intermediate arithmetic overflows 32 bits.
 *
 * @implNote This implementation interleaves the blocks so that a vector lane holds one block:
 *           both passes of the separable transform then operate lane-wise on whole rows or
 *           columns of coefficients and need no transposition, and one output row of all the
 *           blocks is contiguous, so the samples are packed and stored with a few wide stores.
 */
sealed interface BatchIdctKernel permits ScalarBatchIdctKernel, VectorBatchIdctKernel {
    /**
     * The number of blocks of a batch.
     */
    int LANES = 8;

    /**
     * The length of a batch's coefficient array: 64 coefficients of {@link #LANES} blocks.
     */
    int BATCH_LENGTH = 64 * LANES;

    /**
     * The implementation selected for this JVM: the vector implementation when
     * {@link Simd#ENABLED}, the scalar one otherwise.
     */
    BatchIdctKernel INSTANCE = Simd.ENABLED ? VectorKernels.batchIdct() : new ScalarBatchIdctKernel();

    /**
     * Returns whether a decoding operation transforms its blocks in batches with {@link #INSTANCE},
     * rather than one at a time with the scalar per-block transforms; both give the same samples.
     *
     * <p>Batches pay off with the Vector API kernel only, so this is {@link Simd#ENABLED}.
     *
     * @return {@code true} to transform in batches
     */
    static boolean batches() {
        return Simd.ENABLED;
    }

    /**
     * Transforms a batch of blocks into 8x8 samples each and clears the coefficients.
     *
     * @param coefficients the interleaved coefficients of the batch; zero on return
     * @param count        the number of blocks to write, from 1 to {@link #LANES}
     * @param output       the destination samples
     * @param offset       the index of the first block's top-left sample
     * @param stride       the distance between output rows
     */
    void idct8(int[] coefficients, int count, byte[] output, int offset, int stride);

    /**
     * Transforms a batch of blocks into 4x4 samples each and clears the coefficients.
     *
     * @param coefficients the interleaved coefficients of the batch; zero on return
     * @param count        the number of blocks to write, from 1 to {@link #LANES}
     * @param output       the destination samples
     * @param offset       the index of the first block's top-left sample
     * @param stride       the distance between output rows
     */
    void idct4(int[] coefficients, int count, byte[] output, int offset, int stride);

    /**
     * Transforms a batch of blocks into 2x2 samples each and clears the coefficients.
     *
     * @param coefficients the interleaved coefficients of the batch; zero on return
     * @param count        the number of blocks to write, from 1 to {@link #LANES}
     * @param output       the destination samples
     * @param offset       the index of the first block's top-left sample
     * @param stride       the distance between output rows
     */
    void idct2(int[] coefficients, int count, byte[] output, int offset, int stride);
}
