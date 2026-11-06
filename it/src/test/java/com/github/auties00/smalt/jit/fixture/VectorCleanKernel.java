package com.github.auties00.smalt.jit.fixture;

import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

/**
 * A kernel written to the rules (a constant species, per-shape operations, exact receivers): the kernel guard must
 * pass it in every scenario, and fail it when its intrinsics are disabled.
 */
public final class VectorCleanKernel {
    private static final VectorSpecies<Integer> INTS = IntVector.SPECIES_PREFERRED;
    @SuppressWarnings("unchecked")
    private static final Class<IntVector> INT_VECTOR = (Class<IntVector>) INTS.vectorType();

    private VectorCleanKernel() {
    }

    /**
     * Computes {@code a * b + a} per element.
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
            x.lanewise(VectorOperators.MUL, y).lanewise(VectorOperators.ADD, x).intoArray(out, i);
        }
        ScalarKernels.mix(a, b, out, i, count);
    }
}
