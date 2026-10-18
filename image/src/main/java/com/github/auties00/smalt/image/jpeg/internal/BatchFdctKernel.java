package com.github.auties00.smalt.image.jpeg.internal;

/**
 * Computes the accurate integer forward DCT of {@link #LANES} horizontally adjacent blocks of one
 * component at once and quantizes the coefficients.
 *
 * <p>The blocks are read side by side from the sample rows: row {@code r} of block {@code b}
 * starts at {@code offset + r * stride + 8 * b}, so all {@link #LANES} blocks must lie in the
 * sample array. The quantized coefficients are written interleaved, as {@link BatchIdctKernel}
 * reads them: coefficient {@code k} (natural order) of block {@code b} at index
 * {@code k * LANES + b} of the output. For every block the result equals
 * {@link ScalarFdctKernel#transform}'s coefficients and nonzero mask for the same samples.
 *
 * @implNote This implementation interleaves the blocks so that a vector lane holds one block: both
 *           passes of the separable transform and the quantization then operate lane-wise on
 *           whole rows or columns of coefficients, and only the sample rows are transposed once,
 *           as bytes, when they are loaded.
 */
sealed interface BatchFdctKernel permits ScalarBatchFdctKernel, VectorBatchFdctKernel {
    /**
     * The number of blocks of a batch.
     */
    int LANES = 8;

    /**
     * The length of a batch's coefficient array: 64 coefficients of {@link #LANES} blocks.
     */
    int BATCH_LENGTH = 64 * LANES;

    /**
     * The length of the scratch array a batch needs.
     */
    int SCRATCH_LENGTH = 64 * LANES;

    /**
     * Transforms and quantizes a batch of blocks.
     *
     * @param samples  the source samples
     * @param offset   the index of the first block's top-left sample
     * @param stride   the distance between sample rows
     * @param divisors the quantization table
     * @param output   the {@link #BATCH_LENGTH} interleaved quantized coefficients, written
     * @param masks    the natural-order nonzero masks of the {@link #LANES} blocks, written: bit
     *                 {@code n} of {@code masks[b]} is set when coefficient {@code n} of block
     *                 {@code b} is nonzero
     * @param scratch  a scratch array of at least {@link #SCRATCH_LENGTH} bytes owned by the
     *                 calling thread
     */
    void transform(byte[] samples, int offset, int stride, QuantDivisors divisors, int[] output, long[] masks,
                   byte[] scratch);
}
