package com.github.auties00.smalt.audio.opus.internal.silk;

import jdk.incubator.vector.DoubleVector;
import jdk.incubator.vector.FloatVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

import java.util.Arrays;

import static com.github.auties00.smalt.audio.opus.internal.silk.Define.MAX_SHAPE_LPC_ORDER;

/**
 * Implements {@link SilkFlpKernels} with the Vector API; the output equals {@link ScalarSilkFlpKernels} bit for
 * bit.
 *
 * @implNote This implementation follows the kernel rules of {@link com.github.auties00.smalt.internal.simd.Simd Simd}
 * and vectorizes across output samples. The warped autocorrelation step is a method of its own, called once per sample
 * with arrays only, so no vector crosses the call.
 */
final class VectorSilkFlpKernels implements SilkFlpKernels {
    /**
     * The preferred float species of the platform.
     */
    private static final VectorSpecies<Float> SPECIES = FloatVector.SPECIES_PREFERRED;

    /**
     * The number of float lanes in {@link #SPECIES}.
     */
    private static final int LANES = SPECIES.length();

    /**
     * The exact class of the vectors of {@link #SPECIES}.
     */
    private static final Class<FloatVector> FLOAT_VECTOR = vectorClass(SPECIES);

    /**
     * Creates the vector kernels.
     */
    private VectorSilkFlpKernels() {
    }

    /**
     * Creates the vector kernels.
     *
     * @return the kernels
     */
    static SilkFlpKernels create() {
        return new VectorSilkFlpKernels();
    }

    /**
     * Returns the exact class of the vectors of a species.
     *
     * @param species the species whose vector class to return
     * @param <E> the element type
     * @param <V> the vector type
     * @return the class
     */
    @SuppressWarnings("unchecked")
    private static <E, V> Class<V> vectorClass(VectorSpecies<E> species) {
        return (Class<V>) species.vectorType();
    }

    @Override
    public void lpcAnalysisFilter(float[] rLpc, int rLpcOff, float[] coef, int coefOff, float[] s, int sOff, int length,
                                  int order) {
        var ix = order;
        for (; ix <= length - LANES; ix += LANES) {
            var sPtr = sOff + ix - 1;
            var pred = FLOAT_VECTOR.cast(FloatVector.fromArray(SPECIES, s, sPtr))
                    .lanewise(VectorOperators.MUL, FloatVector.broadcast(SPECIES, coef[coefOff]));
            for (var j = 1; j < order; j++) {
                pred = FLOAT_VECTOR.cast(pred).lanewise(VectorOperators.ADD, FLOAT_VECTOR.cast(FloatVector.fromArray(SPECIES, s, sPtr - j))
                        .lanewise(VectorOperators.MUL, FloatVector.broadcast(SPECIES, coef[coefOff + j])));
            }
            FLOAT_VECTOR.cast(FloatVector.fromArray(SPECIES, s, sPtr + 1)).lanewise(VectorOperators.SUB, pred)
                    .intoArray(rLpc, rLpcOff + ix);
        }
        for (; ix < length; ix++) {
            var sPtr = sOff + ix - 1;
            var pred = s[sPtr] * coef[coefOff];
            for (var j = 1; j < order; j++) {
                pred = pred + s[sPtr - j] * coef[coefOff + j];
            }
            rLpc[rLpcOff + ix] = s[sPtr + 1] - pred;
        }
    }

    /**
     * The four-lane double species, one lane per signal of the batched warped autocorrelation.
     */
    private static final VectorSpecies<Double> D4 = DoubleVector.SPECIES_256;

    /**
     * The exact class of the vectors of {@link #D4}.
     */
    private static final Class<DoubleVector> DOUBLE_VECTOR = vectorClass(D4);

    @Override
    public void warpedAutocorrelation4(float[] corr, int corrStride, float[] input, int inputStride, float warping,
                                       int length, int order, double[] scratch) {
        final var cOff = 4 * (MAX_SHAPE_LPC_ORDER + 1);
        final var inOff = 2 * cOff;
        Arrays.fill(scratch, 0, inOff, 0.0);
        for (var n = 0; n < length; n++) {
            scratch[inOff + 4 * n] = input[n];
            scratch[inOff + 4 * n + 1] = input[inputStride + n];
            scratch[inOff + 4 * n + 2] = input[2 * inputStride + n];
            scratch[inOff + 4 * n + 3] = input[3 * inputStride + n];
        }
        double w = warping;
        for (var n = 0; n < length; n++) {
            warpedStep(scratch, cOff, inOff + 4 * n, w, order);
        }
        for (var i = 0; i < order + 1; i++) {
            corr[i] = (float) scratch[cOff + i * 4];
            corr[corrStride + i] = (float) scratch[cOff + i * 4 + 1];
            corr[2 * corrStride + i] = (float) scratch[cOff + i * 4 + 2];
            corr[3 * corrStride + i] = (float) scratch[cOff + i * 4 + 3];
        }
    }

    /**
     * Runs one sample of the four interleaved warped autocorrelation recursions, one per lane.
     *
     * <p>After the first allpass section the first state holds the input sample, so every
     * correlation update multiplies by the input vector.
     *
     * @param s     the work buffer: the allpass states (four lanes per section) from index 0,
     *              the correlation accumulators (four lanes per lag) from {@code cOff}, and the
     *              interleaved input samples
     * @param cOff  the index of the correlation accumulators
     * @param inOff the index of the current sample's four lanes
     * @param w     the warping coefficient
     * @param order the correlation order, even
     */
    private static void warpedStep(double[] s, int cOff, int inOff, double w, int order) {
        var wV = DoubleVector.broadcast(D4, w);
        var tmp1 = DOUBLE_VECTOR.cast(DoubleVector.fromArray(D4, s, inOff));
        var s0 = tmp1;
        for (var i = 0; i < order; i += 2) {
            var p = i * 4;
            var si = DOUBLE_VECTOR.cast(DoubleVector.fromArray(D4, s, p));
            var si1 = DOUBLE_VECTOR.cast(DoubleVector.fromArray(D4, s, p + 4));
            var previous = DOUBLE_VECTOR.cast(tmp1);
            var tmp2 = si.lanewise(VectorOperators.ADD, si1.lanewise(VectorOperators.MUL, wV))
                    .lanewise(VectorOperators.SUB, previous.lanewise(VectorOperators.MUL, wV));
            previous.intoArray(s, p);
            DOUBLE_VECTOR.cast(DoubleVector.fromArray(D4, s, cOff + p))
                    .lanewise(VectorOperators.ADD, DOUBLE_VECTOR.cast(s0).lanewise(VectorOperators.MUL, previous))
                    .intoArray(s, cOff + p);
            var si2 = DOUBLE_VECTOR.cast(DoubleVector.fromArray(D4, s, p + 8));
            tmp1 = si1.lanewise(VectorOperators.ADD, si2.lanewise(VectorOperators.MUL, wV))
                    .lanewise(VectorOperators.SUB, tmp2.lanewise(VectorOperators.MUL, wV));
            tmp2.intoArray(s, p + 4);
            DOUBLE_VECTOR.cast(DoubleVector.fromArray(D4, s, cOff + p + 4))
                    .lanewise(VectorOperators.ADD, DOUBLE_VECTOR.cast(s0).lanewise(VectorOperators.MUL, tmp2))
                    .intoArray(s, cOff + p + 4);
        }
        var q = order * 4;
        var last = DOUBLE_VECTOR.cast(tmp1);
        last.intoArray(s, q);
        DOUBLE_VECTOR.cast(DoubleVector.fromArray(D4, s, cOff + q))
                .lanewise(VectorOperators.ADD, DOUBLE_VECTOR.cast(s0).lanewise(VectorOperators.MUL, last))
                .intoArray(s, cOff + q);
    }
}
