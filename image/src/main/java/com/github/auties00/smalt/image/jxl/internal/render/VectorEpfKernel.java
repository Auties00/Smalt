/* Ports jxl-oxide 0.12.6 (crates/jxl-render: the generic edge-preserving filter). Copyright Wonwoo Choi and the
   jxl-oxide contributors; MIT License. Modified for Smalt. */
package com.github.auties00.smalt.image.jxl.internal.render;

import com.github.auties00.smalt.internal.simd.Simd;
import jdk.incubator.vector.FloatVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

/**
 * Implements {@link EpfKernel} with the Vector API, eight samples of a row at a time where
 * {@link Simd#WIDE} holds and four otherwise.
 *
 * <p>Every lane performs the operations of {@link ScalarEpfKernel} in the same order, so the
 * output is bit-identical. The distances of the filter steps (five differences per channel, or
 * one) are computed in straight-line code, and the running sums of the taps live in the scratch
 * array between taps, so no vector is carried across a loop iteration; other distance shapes and
 * the samples left over at the end of a row go through the scalar kernel.
 */
final class VectorEpfKernel implements EpfKernel {
    /**
     * The species: eight floats where the platform has 256-bit vectors, four otherwise.
     */
    private static final VectorSpecies<Float> FLOATS = Simd.WIDE ? FloatVector.SPECIES_256 : FloatVector.SPECIES_128;

    /**
     * The exact vector class of {@link #FLOATS}.
     */
    private static final Class<FloatVector> FLOAT_VECTOR = vectorClass(FLOATS);

    /**
     * The number of lanes.
     */
    private static final int LANES = FLOATS.length();

    /**
     * Zero in every lane.
     */
    private static final FloatVector ZERO = FloatVector.broadcast(FLOATS, 0.0f);

    /**
     * One in every lane.
     */
    private static final FloatVector ONE = FloatVector.broadcast(FLOATS, 1.0f);

    /**
     * Creates the kernel.
     */
    VectorEpfKernel() {
    }

    /**
     * Returns the vector class of a species.
     *
     * @param species the vector shape
     * @param <E> the lane type
     * @param <V> the vector class
     * @return the class
     */
    @SuppressWarnings("unchecked")
    private static <E, V> Class<V> vectorClass(VectorSpecies<E> species) {
        return (Class<V>) species.vectorType();
    }

    @Override
    public void filterRow(float[] in0, float[] in1, float[] in2, float[] out0, float[] out1, float[] out2,
                          int outputRow, int inputRow, int width, float[] negInvSigma, int[] kernelOffsets,
                          int[] distOffsets, float scale0, float scale1, float scale2, float[] scratch) {
        var vectorWidth = distOffsets.length == 5 || distOffsets.length == 1 ? width - width % LANES : 0;
        if (distOffsets.length == 5) {
            rowPlus(in0, in1, in2, out0, out1, out2, outputRow, inputRow, vectorWidth, negInvSigma, kernelOffsets,
                    scale0, scale1, scale2, scratch, distOffsets[0], distOffsets[1], distOffsets[2], distOffsets[3],
                    distOffsets[4]);
        } else if (distOffsets.length == 1) {
            rowSingle(in0, in1, in2, out0, out1, out2, outputRow, inputRow, vectorWidth, negInvSigma, kernelOffsets,
                    scale0, scale1, scale2, scratch, distOffsets[0]);
        }
        ScalarEpfKernel.filterSamples(in0, in1, in2, out0, out1, out2, outputRow, inputRow, vectorWidth, width,
                negInvSigma, kernelOffsets, distOffsets, scale0, scale1, scale2);
    }

    /**
     * Filters whole vectors of a row whose tap distances sum five absolute differences per channel.
     *
     * @param in0 the padded X plane
     * @param in1 the padded Y plane
     * @param in2 the padded B plane
     * @param out0 the output X plane
     * @param out1 the output Y plane
     * @param out2 the output B plane
     * @param outputRow the index of the row's first sample in the output planes
     * @param inputRow the index of the row's first sample in the padded planes
     * @param width the number of samples to filter, a multiple of the lane count
     * @param negInvSigma the weight slope of each sample, or NaN for a sample left unchanged
     * @param kernelOffsets the offsets of the filter taps
     * @param scale0 the distance weight of X
     * @param scale1 the distance weight of Y
     * @param scale2 the distance weight of B
     * @param scratch the running sums
     * @param d0 the offset of the first distance sample
     * @param d1 the offset of the second distance sample
     * @param d2 the offset of the third distance sample
     * @param d3 the offset of the fourth distance sample
     * @param d4 the offset of the fifth distance sample
     */
    private static void rowPlus(float[] in0, float[] in1, float[] in2, float[] out0, float[] out1, float[] out2,
                    int outputRow, int inputRow, int width, float[] negInvSigma, int[] kernelOffsets,
                    float scale0, float scale1, float scale2, float[] scratch, int d0, int d1, int d2, int d3, int d4) {
        var sc0 = FLOAT_VECTOR.cast(FloatVector.broadcast(FLOATS, scale0));
        var sc1 = FLOAT_VECTOR.cast(FloatVector.broadcast(FLOATS, scale1));
        var sc2 = FLOAT_VECTOR.cast(FloatVector.broadcast(FLOATS, scale2));
        for (var x = 0; x < width; x += LANES) {
            var center = inputRow + x;
            var slope = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, negInvSigma, x));
            ONE.intoArray(scratch, 0);
            FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, in0, center)).intoArray(scratch, LANES);
            FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, in1, center)).intoArray(scratch, 2 * LANES);
            FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, in2, center)).intoArray(scratch, 3 * LANES);
            for (var kernelOffset : kernelOffsets) {
                var tap = center + kernelOffset;
                var acc0 = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, in0, tap + d0))
                        .lanewise(VectorOperators.SUB,
                                FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, in0, center + d0)))
                        .lanewise(VectorOperators.ABS)
                        .lanewise(VectorOperators.ADD, FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, in0, tap + d1))
                        .lanewise(VectorOperators.SUB,
                                FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, in0, center + d1)))
                        .lanewise(VectorOperators.ABS))
                        .lanewise(VectorOperators.ADD, FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, in0, tap + d2))
                        .lanewise(VectorOperators.SUB,
                                FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, in0, center + d2)))
                        .lanewise(VectorOperators.ABS))
                        .lanewise(VectorOperators.ADD, FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, in0, tap + d3))
                        .lanewise(VectorOperators.SUB,
                                FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, in0, center + d3)))
                        .lanewise(VectorOperators.ABS))
                        .lanewise(VectorOperators.ADD, FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, in0, tap + d4))
                        .lanewise(VectorOperators.SUB,
                                FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, in0, center + d4)))
                        .lanewise(VectorOperators.ABS));
                var acc1 = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, in1, tap + d0))
                        .lanewise(VectorOperators.SUB,
                                FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, in1, center + d0)))
                        .lanewise(VectorOperators.ABS)
                        .lanewise(VectorOperators.ADD, FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, in1, tap + d1))
                        .lanewise(VectorOperators.SUB,
                                FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, in1, center + d1)))
                        .lanewise(VectorOperators.ABS))
                        .lanewise(VectorOperators.ADD, FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, in1, tap + d2))
                        .lanewise(VectorOperators.SUB,
                                FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, in1, center + d2)))
                        .lanewise(VectorOperators.ABS))
                        .lanewise(VectorOperators.ADD, FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, in1, tap + d3))
                        .lanewise(VectorOperators.SUB,
                                FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, in1, center + d3)))
                        .lanewise(VectorOperators.ABS))
                        .lanewise(VectorOperators.ADD, FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, in1, tap + d4))
                        .lanewise(VectorOperators.SUB,
                                FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, in1, center + d4)))
                        .lanewise(VectorOperators.ABS));
                var acc2 = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, in2, tap + d0))
                        .lanewise(VectorOperators.SUB,
                                FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, in2, center + d0)))
                        .lanewise(VectorOperators.ABS)
                        .lanewise(VectorOperators.ADD, FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, in2, tap + d1))
                        .lanewise(VectorOperators.SUB,
                                FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, in2, center + d1)))
                        .lanewise(VectorOperators.ABS))
                        .lanewise(VectorOperators.ADD, FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, in2, tap + d2))
                        .lanewise(VectorOperators.SUB,
                                FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, in2, center + d2)))
                        .lanewise(VectorOperators.ABS))
                        .lanewise(VectorOperators.ADD, FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, in2, tap + d3))
                        .lanewise(VectorOperators.SUB,
                                FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, in2, center + d3)))
                        .lanewise(VectorOperators.ABS))
                        .lanewise(VectorOperators.ADD, FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, in2, tap + d4))
                        .lanewise(VectorOperators.SUB,
                                FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, in2, center + d4)))
                        .lanewise(VectorOperators.ABS));
                var distance = ZERO.lanewise(VectorOperators.ADD, FLOAT_VECTOR.cast(sc0).lanewise(VectorOperators.MUL, acc0))
                        .lanewise(VectorOperators.ADD, FLOAT_VECTOR.cast(sc1).lanewise(VectorOperators.MUL, acc1))
                        .lanewise(VectorOperators.ADD, FLOAT_VECTOR.cast(sc2).lanewise(VectorOperators.MUL, acc2));
                var weight = ONE.lanewise(VectorOperators.ADD, distance.lanewise(VectorOperators.MUL, slope))
                        .lanewise(VectorOperators.MAX, ZERO);
                FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, scratch, 0)).lanewise(VectorOperators.ADD, weight)
                        .intoArray(scratch, 0);
                var t0 = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, in0, tap));
                FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, scratch, LANES))
                        .lanewise(VectorOperators.ADD, weight.lanewise(VectorOperators.MUL, t0))
                        .intoArray(scratch, LANES);
                var t1 = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, in1, tap));
                FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, scratch, 2 * LANES))
                        .lanewise(VectorOperators.ADD, weight.lanewise(VectorOperators.MUL, t1))
                        .intoArray(scratch, 2 * LANES);
                var t2 = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, in2, tap));
                FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, scratch, 3 * LANES))
                        .lanewise(VectorOperators.ADD, weight.lanewise(VectorOperators.MUL, t2))
                        .intoArray(scratch, 3 * LANES);
            }
            var skip = FLOAT_VECTOR.cast(slope).compare(VectorOperators.NE, slope);
            var weights = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, scratch, 0));
            var o = outputRow + x;
            FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, scratch, LANES)).lanewise(VectorOperators.DIV, weights)
                    .blend(FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, in0, center)), skip).intoArray(out0, o);
            FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, scratch, 2 * LANES)).lanewise(VectorOperators.DIV, weights)
                    .blend(FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, in1, center)), skip).intoArray(out1, o);
            FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, scratch, 3 * LANES)).lanewise(VectorOperators.DIV, weights)
                    .blend(FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, in2, center)), skip).intoArray(out2, o);
        }
    }

    /**
     * Filters whole vectors of a row whose tap distances take one absolute difference per channel.
     *
     * @param in0 the padded X plane
     * @param in1 the padded Y plane
     * @param in2 the padded B plane
     * @param out0 the output X plane
     * @param out1 the output Y plane
     * @param out2 the output B plane
     * @param outputRow the index of the row's first sample in the output planes
     * @param inputRow the index of the row's first sample in the padded planes
     * @param width the number of samples to filter, a multiple of the lane count
     * @param negInvSigma the weight slope of each sample, or NaN for a sample left unchanged
     * @param kernelOffsets the offsets of the filter taps
     * @param scale0 the distance weight of X
     * @param scale1 the distance weight of Y
     * @param scale2 the distance weight of B
     * @param scratch the running sums
     * @param d0 the offset of the distance sample
     */
    private static void rowSingle(float[] in0, float[] in1, float[] in2, float[] out0, float[] out1, float[] out2,
                    int outputRow, int inputRow, int width, float[] negInvSigma, int[] kernelOffsets,
                    float scale0, float scale1, float scale2, float[] scratch, int d0) {
        var sc0 = FLOAT_VECTOR.cast(FloatVector.broadcast(FLOATS, scale0));
        var sc1 = FLOAT_VECTOR.cast(FloatVector.broadcast(FLOATS, scale1));
        var sc2 = FLOAT_VECTOR.cast(FloatVector.broadcast(FLOATS, scale2));
        for (var x = 0; x < width; x += LANES) {
            var center = inputRow + x;
            var slope = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, negInvSigma, x));
            ONE.intoArray(scratch, 0);
            FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, in0, center)).intoArray(scratch, LANES);
            FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, in1, center)).intoArray(scratch, 2 * LANES);
            FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, in2, center)).intoArray(scratch, 3 * LANES);
            for (var kernelOffset : kernelOffsets) {
                var tap = center + kernelOffset;
                var acc0 = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, in0, tap + d0))
                        .lanewise(VectorOperators.SUB,
                                FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, in0, center + d0)))
                        .lanewise(VectorOperators.ABS);
                var acc1 = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, in1, tap + d0))
                        .lanewise(VectorOperators.SUB,
                                FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, in1, center + d0)))
                        .lanewise(VectorOperators.ABS);
                var acc2 = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, in2, tap + d0))
                        .lanewise(VectorOperators.SUB,
                                FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, in2, center + d0)))
                        .lanewise(VectorOperators.ABS);
                var distance = ZERO.lanewise(VectorOperators.ADD, FLOAT_VECTOR.cast(sc0).lanewise(VectorOperators.MUL, acc0))
                        .lanewise(VectorOperators.ADD, FLOAT_VECTOR.cast(sc1).lanewise(VectorOperators.MUL, acc1))
                        .lanewise(VectorOperators.ADD, FLOAT_VECTOR.cast(sc2).lanewise(VectorOperators.MUL, acc2));
                var weight = ONE.lanewise(VectorOperators.ADD, distance.lanewise(VectorOperators.MUL, slope))
                        .lanewise(VectorOperators.MAX, ZERO);
                FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, scratch, 0)).lanewise(VectorOperators.ADD, weight)
                        .intoArray(scratch, 0);
                var t0 = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, in0, tap));
                FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, scratch, LANES))
                        .lanewise(VectorOperators.ADD, weight.lanewise(VectorOperators.MUL, t0))
                        .intoArray(scratch, LANES);
                var t1 = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, in1, tap));
                FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, scratch, 2 * LANES))
                        .lanewise(VectorOperators.ADD, weight.lanewise(VectorOperators.MUL, t1))
                        .intoArray(scratch, 2 * LANES);
                var t2 = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, in2, tap));
                FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, scratch, 3 * LANES))
                        .lanewise(VectorOperators.ADD, weight.lanewise(VectorOperators.MUL, t2))
                        .intoArray(scratch, 3 * LANES);
            }
            var skip = FLOAT_VECTOR.cast(slope).compare(VectorOperators.NE, slope);
            var weights = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, scratch, 0));
            var o = outputRow + x;
            FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, scratch, LANES)).lanewise(VectorOperators.DIV, weights)
                    .blend(FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, in0, center)), skip).intoArray(out0, o);
            FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, scratch, 2 * LANES)).lanewise(VectorOperators.DIV, weights)
                    .blend(FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, in1, center)), skip).intoArray(out1, o);
            FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, scratch, 3 * LANES)).lanewise(VectorOperators.DIV, weights)
                    .blend(FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, in2, center)), skip).intoArray(out2, o);
        }
    }
}
