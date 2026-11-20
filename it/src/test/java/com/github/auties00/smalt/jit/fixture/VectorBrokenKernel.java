package com.github.auties00.smalt.jit.fixture;

import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

/**
 * A kernel whose vector path computes {@code a * b - a} instead of {@code a * b + a}, at every vector width: the
 * kernel guard must report its equivalence test failing in every scenario.
 */
public final class VectorBrokenKernel {
    private static final VectorSpecies<Integer> INTS = IntVector.SPECIES_PREFERRED;
    @SuppressWarnings("unchecked")
    private static final Class<IntVector> INT_VECTOR = (Class<IntVector>) INTS.vectorType();

    private VectorBrokenKernel() {
    }

    /**
     * Computes {@code a * b + a} per element in the scalar tail, and {@code a * b - a} in the vector body.
     *
     * @param a the first operands
     * @param b the second operands
     * @param out receives the results
     * @param count the number of elements
     */
    public static void mix(int[] a, int[] b, int[] out, int count) {
        var i = 0;
        for (var bound = INTS.loopBound(count); i < bound; i += INTS.length()) {
            var x = INT_VECTOR.cast(IntVector.fromArray(INTS, a, i));
            var y = INT_VECTOR.cast(IntVector.fromArray(INTS, b, i));
            x.lanewise(VectorOperators.MUL, y).lanewise(VectorOperators.SUB, x).intoArray(out, i);
        }
        ScalarKernels.mix(a, b, out, i, count);
    }
}
