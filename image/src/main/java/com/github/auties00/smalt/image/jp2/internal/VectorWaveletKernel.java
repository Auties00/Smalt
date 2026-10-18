package com.github.auties00.smalt.image.jp2.internal;

import jdk.incubator.vector.FloatVector;
import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

import static com.github.auties00.smalt.image.jp2.internal.ScalarWaveletKernel.ALPHA;
import static com.github.auties00.smalt.image.jp2.internal.ScalarWaveletKernel.BETA;
import static com.github.auties00.smalt.image.jp2.internal.ScalarWaveletKernel.DELTA;
import static com.github.auties00.smalt.image.jp2.internal.ScalarWaveletKernel.GAMMA;
import static com.github.auties00.smalt.image.jp2.internal.ScalarWaveletKernel.INVERSE_K;
import static com.github.auties00.smalt.image.jp2.internal.ScalarWaveletKernel.K;
import static com.github.auties00.smalt.image.jp2.internal.ScalarWaveletKernel.TWO_INVERSE_K;

/**
 * Lifts wavelet signals with the Vector API, the {@link WaveletKernel#LANES} signals of a position
 * in one 256-bit vector.
 *
 * <p>Every lane runs the arithmetic of {@link ScalarWaveletKernel} in the same order and single
 * precision, without fused multiply-add, so the results are bit-identical to it.
 *
 * @implNote This implementation follows the rules of the module's JIT guard: loads are cast
 * through constant classes, operations use the per-shape {@code lanewise} methods with vector
 * operands, and no vector crosses a loop iteration.
 */
final class VectorWaveletKernel implements WaveletKernel {
    /**
     * The shape of the integer signals.
     */
    private static final VectorSpecies<Integer> INTS = IntVector.SPECIES_256;

    /**
     * The shape of the floating-point signals.
     */
    private static final VectorSpecies<Float> FLOATS = FloatVector.SPECIES_256;

    /**
     * The concrete class of the integer vectors.
     */
    private static final Class<IntVector> INT_VECTOR = vectorClass(INTS);

    /**
     * The concrete class of the floating-point vectors.
     */
    private static final Class<FloatVector> FLOAT_VECTOR = vectorClass(FLOATS);

    /**
     * The rounding offset of the 5-3 update step.
     */
    private static final IntVector TWO = IntVector.broadcast(INTS, 2);

    /**
     * Constructs the kernels.
     */
    VectorWaveletKernel() {
    }

    /**
     * Returns the concrete vector class of a species.
     *
     * @param species the species
     * @param <E>     the element type
     * @param <V>     the vector type
     * @return the class
     */
    @SuppressWarnings("unchecked")
    private static <E, V> Class<V> vectorClass(VectorSpecies<E> species) {
        return (Class<V>) species.vectorType();
    }

    @Override
    public void inverse53(int[] buffer, int sn, int dn, int cas) {
        if (cas == 0) {
            if (dn <= 0 && sn <= 1) {
                return;
            }
            lowStep(buffer, 0, sn, dn, 1, true);
            highStep(buffer, 1, dn, sn, 0, true);
        } else {
            if (sn == 0 && dn == 1) {
                for (var lane = 0; lane < LANES; lane++) {
                    buffer[lane] /= 2;
                }
                return;
            }
            lowStep(buffer, 1, sn, dn, 0, true);
            highStep(buffer, 0, dn, sn, 1, true);
        }
    }

    @Override
    public void forward53(int[] buffer, int sn, int dn, int cas) {
        if (cas == 0) {
            if (sn + dn <= 1) {
                return;
            }
            highStep(buffer, 1, dn, sn, 0, false);
            lowStep(buffer, 0, sn, dn, 1, false);
        } else {
            if (sn + dn == 1) {
                for (var lane = 0; lane < LANES; lane++) {
                    buffer[lane] *= 2;
                }
                return;
            }
            highStep(buffer, 0, dn, sn, 1, false);
            lowStep(buffer, 1, sn, dn, 0, false);
        }
    }

    /**
     * Applies the 5-3 update step: each low-pass target loses (inverse) or gains (forward)
     * {@code (left + right + 2) >> 2} of its high-pass neighbors.
     *
     * @param buffer     the interleaved signals
     * @param first      the position of the first target
     * @param count      the number of targets
     * @param neighbors the number of high-pass coefficients
     * @param offset     the position of the first high-pass coefficient
     * @param inverse    whether the step is subtracted (inverse) rather than added (forward)
     */
    private static void lowStep(int[] buffer, int first, int count, int neighbors, int offset, boolean inverse) {
        if (inverse) {
            for (var i = 0; i < count; i++) {
                var target = (first + 2 * i) * LANES;
                var leftIndex = first == 0 ? Math.clamp(i - 1, 0, neighbors - 1) : Math.min(i, neighbors - 1);
                var rightIndex = first == 0 ? Math.min(i, neighbors - 1) : Math.min(i + 1, neighbors - 1);
                var a = INT_VECTOR.cast(IntVector.fromArray(INTS, buffer, (offset + 2 * leftIndex) * LANES));
                var b = INT_VECTOR.cast(IntVector.fromArray(INTS, buffer, (offset + 2 * rightIndex) * LANES));
                var t = INT_VECTOR.cast(IntVector.fromArray(INTS, buffer, target));
                var delta = a.lanewise(VectorOperators.ADD, b).lanewise(VectorOperators.ADD, TWO)
                        .lanewise(VectorOperators.ASHR, 2);
                t.lanewise(VectorOperators.SUB, delta).intoArray(buffer, target);
            }
        } else {
            for (var i = 0; i < count; i++) {
                var target = (first + 2 * i) * LANES;
                var leftIndex = first == 0 ? Math.clamp(i - 1, 0, neighbors - 1) : Math.min(i, neighbors - 1);
                var rightIndex = first == 0 ? Math.min(i, neighbors - 1) : Math.min(i + 1, neighbors - 1);
                var a = INT_VECTOR.cast(IntVector.fromArray(INTS, buffer, (offset + 2 * leftIndex) * LANES));
                var b = INT_VECTOR.cast(IntVector.fromArray(INTS, buffer, (offset + 2 * rightIndex) * LANES));
                var t = INT_VECTOR.cast(IntVector.fromArray(INTS, buffer, target));
                var delta = a.lanewise(VectorOperators.ADD, b).lanewise(VectorOperators.ADD, TWO)
                        .lanewise(VectorOperators.ASHR, 2);
                t.lanewise(VectorOperators.ADD, delta).intoArray(buffer, target);
            }
        }
    }

    /**
     * Applies the 5-3 predict step: each high-pass target gains (inverse) or loses (forward)
     * {@code (left + right) >> 1} of its low-pass neighbors.
     *
     * @param buffer     the interleaved signals
     * @param first      the position of the first target
     * @param count      the number of targets
     * @param neighbors the number of low-pass coefficients
     * @param offset     the position of the first low-pass coefficient
     * @param inverse    whether the step is added (inverse) rather than subtracted (forward)
     */
    private static void highStep(int[] buffer, int first, int count, int neighbors, int offset, boolean inverse) {
        if (inverse) {
            for (var i = 0; i < count; i++) {
                var target = (first + 2 * i) * LANES;
                var leftIndex = first == 1 ? Math.min(i, neighbors - 1) : Math.clamp(i - 1, 0, neighbors - 1);
                var rightIndex = first == 1 ? Math.min(i + 1, neighbors - 1) : Math.min(i, neighbors - 1);
                var a = INT_VECTOR.cast(IntVector.fromArray(INTS, buffer, (offset + 2 * leftIndex) * LANES));
                var b = INT_VECTOR.cast(IntVector.fromArray(INTS, buffer, (offset + 2 * rightIndex) * LANES));
                var t = INT_VECTOR.cast(IntVector.fromArray(INTS, buffer, target));
                var delta = a.lanewise(VectorOperators.ADD, b).lanewise(VectorOperators.ASHR, 1);
                t.lanewise(VectorOperators.ADD, delta).intoArray(buffer, target);
            }
        } else {
            for (var i = 0; i < count; i++) {
                var target = (first + 2 * i) * LANES;
                var leftIndex = first == 1 ? Math.min(i, neighbors - 1) : Math.clamp(i - 1, 0, neighbors - 1);
                var rightIndex = first == 1 ? Math.min(i + 1, neighbors - 1) : Math.min(i, neighbors - 1);
                var a = INT_VECTOR.cast(IntVector.fromArray(INTS, buffer, (offset + 2 * leftIndex) * LANES));
                var b = INT_VECTOR.cast(IntVector.fromArray(INTS, buffer, (offset + 2 * rightIndex) * LANES));
                var t = INT_VECTOR.cast(IntVector.fromArray(INTS, buffer, target));
                var delta = a.lanewise(VectorOperators.ADD, b).lanewise(VectorOperators.ASHR, 1);
                t.lanewise(VectorOperators.SUB, delta).intoArray(buffer, target);
            }
        }
    }

    @Override
    public void inverse97(float[] buffer, int sn, int dn, int cas) {
        int a;
        int b;
        if (cas == 0) {
            if (dn <= 0 && sn <= 1) {
                return;
            }
            a = 0;
            b = 1;
        } else {
            if (sn <= 0 && dn <= 1) {
                return;
            }
            a = 1;
            b = 0;
        }
        scale(buffer, a, sn, K);
        scale(buffer, b, dn, TWO_INVERSE_K);
        lift(buffer, a, sn, Math.min(sn, dn - a), -DELTA);
        lift(buffer, b, dn, Math.min(dn, sn - b), -GAMMA);
        lift(buffer, a, sn, Math.min(sn, dn - a), -BETA);
        lift(buffer, b, dn, Math.min(dn, sn - b), -ALPHA);
    }

    @Override
    public void forward97(float[] buffer, int sn, int dn, int cas) {
        if (sn + dn <= 1) {
            return;
        }
        var a = cas == 0 ? 0 : 1;
        var b = 1 - a;
        lift(buffer, b, dn, Math.min(dn, sn - b), ALPHA);
        lift(buffer, a, sn, Math.min(sn, dn - a), BETA);
        lift(buffer, b, dn, Math.min(dn, sn - b), GAMMA);
        lift(buffer, a, sn, Math.min(sn, dn - a), DELTA);
        scale(buffer, a, sn, INVERSE_K);
        scale(buffer, b, dn, K);
    }

    /**
     * Multiplies every other position by a constant.
     *
     * @param buffer the interleaved signals
     * @param first  the first position
     * @param count  the number of positions
     * @param factor the constant
     */
    private static void scale(float[] buffer, int first, int count, float factor) {
        for (var i = 0; i < count; i++) {
            var target = (first + 2 * i) * LANES;
            var t = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, buffer, target));
            t.lanewise(VectorOperators.MUL, FloatVector.broadcast(FLOATS, factor)).intoArray(buffer, target);
        }
    }

    /**
     * Applies one lifting step to every other position, as
     * {@code ScalarWaveletKernel.lift} does.
     *
     * @param buffer      the interleaved signals
     * @param first       the first target position
     * @param count       the number of targets
     * @param neighbored the number of leading targets with a right neighbor
     * @param factor      the lifting coefficient
     */
    private static void lift(float[] buffer, int first, int count, int neighbored, float factor) {
        var limit = Math.min(count, neighbored);
        for (var i = 0; i < limit; i++) {
            var position = first + 2 * i;
            var target = position * LANES;
            var left = (position == 0 ? 1 : position - 1) * LANES;
            var right = (position + 1) * LANES;
            var l = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, buffer, left));
            var r = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, buffer, right));
            var t = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, buffer, target));
            var step = l.lanewise(VectorOperators.ADD, r)
                    .lanewise(VectorOperators.MUL, FloatVector.broadcast(FLOATS, factor));
            t.lanewise(VectorOperators.ADD, step).intoArray(buffer, target);
        }
        if (neighbored >= 0 && neighbored < count) {
            var position = first + 2 * neighbored;
            var target = position * LANES;
            var left = (position == 0 ? 1 : position - 1) * LANES;
            var doubled = factor + factor;
            for (var lane = 0; lane < LANES; lane++) {
                buffer[target + lane] = buffer[target + lane] + buffer[left + lane] * doubled;
            }
        }
    }
}
