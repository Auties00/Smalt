package com.github.auties00.smalt.jit.fixture;

import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

/**
 * A kernel that breaks the rules on purpose: one helper, parameterized by the species, serves three vector shapes, so
 * the type profiles inside it see three vector classes. Compiled by C2 only ({@code -XX:-TieredCompilation}), the
 * helper's own compilation cannot intrinsify its operations and every call boxes its vectors; the kernel guard must
 * fail it there, every time.
 */
public final class VectorFragileKernel {
    private VectorFragileKernel() {
    }

    /**
     * Adds with 64-bit vectors.
     *
     * @param a the first operands
     * @param b the second operands
     * @param out receives the sums
     */
    public static void add64(int[] a, int[] b, int[] out) {
        add(IntVector.SPECIES_64, a, b, out);
    }

    /**
     * Adds with 128-bit vectors.
     *
     * @param a the first operands
     * @param b the second operands
     * @param out receives the sums
     */
    public static void add128(int[] a, int[] b, int[] out) {
        add(IntVector.SPECIES_128, a, b, out);
    }

    /**
     * Adds with 256-bit vectors.
     *
     * @param a the first operands
     * @param b the second operands
     * @param out receives the sums
     */
    public static void add256(int[] a, int[] b, int[] out) {
        add(IntVector.SPECIES_256, a, b, out);
    }

    private static void add(VectorSpecies<Integer> species, int[] a, int[] b, int[] out) {
        var i = 0;
        for (var bound = species.loopBound(out.length); i < bound; i += species.length()) {
            IntVector.fromArray(species, a, i).lanewise(VectorOperators.ADD, IntVector.fromArray(species, b, i))
                    .intoArray(out, i);
        }
        for (; i < out.length; i++) {
            out[i] = a[i] + b[i];
        }
    }
}
