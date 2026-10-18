/* Ports jxl-oxide 0.12.6 (crates/jxl-render: the generic edge-preserving filter). Copyright Wonwoo Choi and the
   jxl-oxide contributors; MIT License. Modified for Smalt. */
package com.github.auties00.smalt.image.jxl.internal.render;

/**
 * Implements {@link EpfKernel} one sample at a time; the reference implementation.
 */
final class ScalarEpfKernel implements EpfKernel {
    /**
     * Creates the kernel.
     */
    ScalarEpfKernel() {
    }

    @Override
    public void filterRow(float[] in0, float[] in1, float[] in2, float[] out0, float[] out1, float[] out2,
                          int outputRow, int inputRow, int width, float[] negInvSigma, int[] kernelOffsets,
                          int[] distOffsets, float scale0, float scale1, float scale2, float[] scratch) {
        filterSamples(in0, in1, in2, out0, out1, out2, outputRow, inputRow, 0, width, negInvSigma, kernelOffsets,
                distOffsets, scale0, scale1, scale2);
    }

    /**
     * Filters a range of samples of a row.
     *
     * @param in0 the padded X plane
     * @param in1 the padded Y plane
     * @param in2 the padded B plane
     * @param out0 the output X plane
     * @param out1 the output Y plane
     * @param out2 the output B plane
     * @param outputRow the index of the row's first sample in the output planes
     * @param inputRow the index of the row's first sample in the padded planes
     * @param from the first sample to filter
     * @param to the end of the range
     * @param negInvSigma the weight slope of each sample, or NaN for a sample left unchanged
     * @param kernelOffsets the offsets of the filter taps
     * @param distOffsets the offsets of the distance samples
     * @param scale0 the distance weight of X
     * @param scale1 the distance weight of Y
     * @param scale2 the distance weight of B
     */
    static void filterSamples(float[] in0, float[] in1, float[] in2, float[] out0, float[] out1, float[] out2,
                              int outputRow, int inputRow, int from, int to, float[] negInvSigma, int[] kernelOffsets,
                              int[] distOffsets, float scale0, float scale1, float scale2) {
        for (var x = from; x < to; x++) {
            var center = inputRow + x;
            var o = outputRow + x;
            var slope = negInvSigma[x];
            if (Float.isNaN(slope)) {
                out0[o] = in0[center];
                out1[o] = in1[center];
                out2[o] = in2[center];
                continue;
            }
            var sumWeights = 1.0f;
            var sum0 = in0[center];
            var sum1 = in1[center];
            var sum2 = in2[center];
            for (var kernelOffset : kernelOffsets) {
                var tap = center + kernelOffset;
                var acc0 = 0.0f;
                for (var distOffset : distOffsets) {
                    acc0 += Math.abs(in0[tap + distOffset] - in0[center + distOffset]);
                }
                var acc1 = 0.0f;
                for (var distOffset : distOffsets) {
                    acc1 += Math.abs(in1[tap + distOffset] - in1[center + distOffset]);
                }
                var acc2 = 0.0f;
                for (var distOffset : distOffsets) {
                    acc2 += Math.abs(in2[tap + distOffset] - in2[center + distOffset]);
                }
                var distance = 0.0f + scale0 * acc0;
                distance += scale1 * acc1;
                distance += scale2 * acc2;
                var weight = Math.max(1.0f + distance * slope, 0.0f);
                sumWeights += weight;
                sum0 += weight * in0[tap];
                sum1 += weight * in1[tap];
                sum2 += weight * in2[tap];
            }
            out0[o] = sum0 / sumWeights;
            out1[o] = sum1 / sumWeights;
            out2[o] = sum2 / sumWeights;
        }
    }
}
