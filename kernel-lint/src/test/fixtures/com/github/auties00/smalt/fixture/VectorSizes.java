package com.github.auties00.smalt.fixture;

import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

// Fixture of the kernel-size rule; the oversized method is written by the test ({@code VectorSizesLarge}).
final class VectorSizes {
    static final VectorSpecies<Integer> S = IntVector.SPECIES_128;
    @SuppressWarnings("unchecked")
    static final Class<IntVector> INTS = (Class<IntVector>) S.vectorType();

    static void small(int[] a) {
        var v = INTS.cast(IntVector.fromArray(S, a, 0));
        v.lanewise(VectorOperators.ADD, v).intoArray(a, 4);
    }
}
