package com.github.auties00.smalt.fixture;

import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorShuffle;
import jdk.incubator.vector.VectorSpecies;

// Fixture of the vector-helper rule: no method takes or returns a vector, mask or shuffle, except the private helpers
// that build the class's constants.
final class VectorHelpers {
    static final VectorSpecies<Integer> S = IntVector.SPECIES_128;
    @SuppressWarnings("unchecked")
    static final Class<IntVector> INTS = (Class<IntVector>) S.vectorType();
    static final VectorMask<Integer> ODD = lanes(1);
    static final VectorShuffle<Integer> SWAP = swap();

    static IntVector twice(IntVector v) {
        return INTS.cast(v).lanewise(VectorOperators.ADD, v); // expect: vector-helper
    }

    static VectorMask<Integer> positive(int[] a) {
        return INTS.cast(IntVector.fromArray(S, a, 0)).compare(VectorOperators.GT, INTS.cast(IntVector.zero(S))); // expect: vector-helper
    }

    static void arrays(IntVector[] vectors, int[] a) {
        INTS.cast(vectors[0]).intoArray(a, 0); // expect: vector-helper
    }

    private static VectorMask<Integer> lanes(int bit) {
        return mask(bit);
    }

    private static VectorMask<Integer> mask(int bit) {
        var bits = new boolean[S.length()];
        for (var lane = 0; lane < bits.length; lane++) {
            bits[lane] = (lane & bit) != 0;
        }
        return VectorMask.fromArray(S, bits, 0);
    }

    private static VectorShuffle<Integer> swap() {
        return VectorShuffle.fromOp(S, lane -> lane ^ 1);
    }

    static void use(int[] a) {
        INTS.cast(IntVector.fromArray(S, a, 0)).blend(INTS.cast(IntVector.zero(S)), ODD).rearrange(SWAP).intoArray(a, 4);
    }
}
