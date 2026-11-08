package com.github.auties00.smalt.fixture;

import com.github.auties00.smalt.internal.simd.Simd;
import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

// Fixture of the wide-species rule: 256-bit vectors and gathers only where Simd.WIDE holds.
final class VectorWidths {
    static final VectorSpecies<Integer> WIDE = IntVector.SPECIES_256;
    static final VectorSpecies<Integer> ADAPTIVE = Simd.WIDE ? IntVector.SPECIES_256 : IntVector.SPECIES_128;
    @SuppressWarnings("unchecked")
    static final Class<IntVector> WIDE_INTS = (Class<IntVector>) WIDE.vectorType();
    @SuppressWarnings("unchecked")
    static final Class<IntVector> INTS = (Class<IntVector>) ADAPTIVE.vectorType();

    public static void unguarded(int[] a) {
        WIDE_INTS.cast(IntVector.fromArray(WIDE, a, 0)).intoArray(a, 8); // expect: wide-species
    }

    public static void guarded(int[] a) {
        if (!Simd.WIDE) {
            return;
        }
        WIDE_INTS.cast(IntVector.fromArray(WIDE, a, 0)).intoArray(a, 8);
    }

    public static void adaptive(int[] a) {
        INTS.cast(IntVector.fromArray(ADAPTIVE, a, 0)).lanewise(VectorOperators.ADD, INTS.cast(IntVector.zero(ADAPTIVE)))
                .intoArray(a, 8);
    }

    public static void entry(int[] a) {
        if (Simd.WIDE) {
            helper(a);
        }
    }

    private static void helper(int[] a) {
        WIDE_INTS.cast(IntVector.fromArray(WIDE, a, 0)).intoArray(a, 8);
    }

    public static void gather(int[] a, int[] map) {
        INTS.cast(IntVector.fromArray(ADAPTIVE, a, 0, map, 0)).intoArray(a, 8); // expect: wide-species
    }
}
