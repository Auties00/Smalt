package com.github.auties00.smalt.audio.loudness;

import jdk.incubator.vector.FloatVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

/**
 * Creates the Vector API implementations of the loudness kernels.
 *
 * <p>This is the only class of the package that mentions {@code jdk.incubator.vector} types, and
 * it is loaded only when {@link com.github.auties00.smalt.internal.simd.Simd#ENABLED} is
 * {@code true}, so the package links on JVMs started without the incubator module.
 */
final class VectorLoudnessKernels {
    /**
     * Prevents instantiation.
     */
    private VectorLoudnessKernels() {
        throw new AssertionError();
    }

    /**
     * Returns the vector true-peak kernel.
     *
     * @return a new kernel
     */
    static TruePeakKernel truePeak() {
        return new VectorTruePeakKernel();
    }

    /**
     * Implements {@link TruePeakKernel} with one output per lane: consecutive outputs of a phase are
     * computed side by side, each lane performing exactly the fused multiply-adds of
     * {@link ScalarTruePeakKernel} in the same order, so the result is bit-identical.
     *
     * @implNote This implementation uses only the per-shape {@code lanewise} operations with vector
     * operands, casts every load, broadcast and zero vector through the exact vector class, carries
     * across loop back edges only accumulators that start from zero, and casts each accumulator
     * again wherever it receives a call: a vector carried around a loop reaches the compiler with
     * only its declared abstract type, so without the cast the call would be bound through a type
     * profile, which may be missing or shared by every vector shape of the program, and the vector
     * boxed.
     */
    static final class VectorTruePeakKernel implements TruePeakKernel {
        /**
         * The float species: the platform's preferred shape.
         */
        private static final VectorSpecies<Float> SPECIES = FloatVector.SPECIES_PREFERRED;

        /**
         * The exact class of the vectors of {@link #SPECIES}.
         */
        private static final Class<FloatVector> FLOAT_VECTOR = vectorClass(SPECIES);

        /**
         * The number of lanes of {@link #SPECIES}.
         */
        private static final int LANES = SPECIES.length();

        /**
         * Constructs the kernel.
         */
        VectorTruePeakKernel() {
        }

        /**
         * Returns the exact class of the vectors of a species.
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
        public float peak(float[] input, int count, float[] coefficients, int[] offsets, int phases, int taps) {
            var bound = count - count % LANES;
            var peak = FLOAT_VECTOR.cast(FloatVector.zero(SPECIES));
            for (var n = 0; n < bound; n += LANES) {
                for (var phase = 0; phase < phases; phase++) {
                    var base = phase * taps;
                    var acc = FLOAT_VECTOR.cast(FloatVector.zero(SPECIES));
                    for (var tap = 0; tap < taps; tap++) {
                        var samples = FLOAT_VECTOR.cast(FloatVector.fromArray(SPECIES, input, n + offsets[base + tap]));
                        var coefficient = FLOAT_VECTOR.cast(FloatVector.broadcast(SPECIES, coefficients[base + tap]));
                        acc = samples.lanewise(VectorOperators.FMA, coefficient, acc);
                    }
                    peak = FLOAT_VECTOR.cast(peak).lanewise(VectorOperators.MAX, FLOAT_VECTOR.cast(acc).lanewise(VectorOperators.ABS));
                }
            }
            var result = FLOAT_VECTOR.cast(peak).reduceLanes(VectorOperators.MAX);
            return Math.max(result, ScalarTruePeakKernel.peak(input, bound, count, coefficients, offsets, phases, taps));
        }
    }
}
