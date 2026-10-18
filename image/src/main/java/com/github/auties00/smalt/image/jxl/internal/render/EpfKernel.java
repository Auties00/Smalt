/* Ports jxl-oxide 0.12.6 (crates/jxl-render: the edge-preserving filter). Copyright Wonwoo Choi and the jxl-oxide
   contributors; MIT License. Modified for Smalt. */
package com.github.auties00.smalt.image.jxl.internal.render;

import com.github.auties00.smalt.internal.simd.Simd;

/**
 * Filters one row of a step of the JPEG XL edge-preserving filter (ISO/IEC 18181-1 section J.3).
 *
 * <p>Each output sample is the weighted mean of the sample and of its filter taps in the X, Y and
 * B planes, a tap's weight falling linearly with the distance between the neighborhoods of the
 * sample and of the tap. The planes are padded by {@link RestorationFilters#pad}, so every offset
 * stays inside them.
 *
 * <p>Implementations return bit-identical samples for every input: each performs, per sample, the
 * same floating-point operations in the same order.
 */
interface EpfKernel {
    /**
     * The implementation selected for this JVM: {@link VectorEpfKernel} when the Vector API is
     * usable ({@link Simd#ENABLED}), else {@link ScalarEpfKernel}.
     */
    EpfKernel INSTANCE = Simd.ENABLED ? VectorHolder.create() : new ScalarEpfKernel();

    /**
     * The number of floats {@link #filterRow} needs in its scratch array.
     */
    int SCRATCH = 64;

    /**
     * Filters one row.
     *
     * @param in0 the padded X plane
     * @param in1 the padded Y plane
     * @param in2 the padded B plane
     * @param out0 the output X plane
     * @param out1 the output Y plane
     * @param out2 the output B plane
     * @param outputRow the index of the row's first sample in the output planes
     * @param inputRow the index of the row's first sample in the padded planes
     * @param width the row length
     * @param negInvSigma the weight slope of each sample of the row, or NaN for a sample the filter
     * leaves unchanged
     * @param kernelOffsets the offsets of the filter taps in the padded planes
     * @param distOffsets the offsets of the samples whose absolute differences make a tap's
     * distance
     * @param scale0 the distance weight of X
     * @param scale1 the distance weight of Y
     * @param scale2 the distance weight of B
     * @param scratch a work array of at least {@link #SCRATCH} floats
     */
    void filterRow(float[] in0, float[] in1, float[] in2, float[] out0, float[] out1, float[] out2, int outputRow,
                   int inputRow, int width, float[] negInvSigma, int[] kernelOffsets, int[] distOffsets, float scale0,
                   float scale1, float scale2, float[] scratch);

    /**
     * Creates the Vector API kernel in a class of its own, so that the vector types are linked only
     * when it is used.
     */
    final class VectorHolder {
        /**
         * Prevents instantiation.
         */
        private VectorHolder() {
            throw new AssertionError();
        }

        /**
         * Creates the Vector API kernel.
         *
         * @return the kernel
         */
        static EpfKernel create() {
            return new VectorEpfKernel();
        }
    }
}
