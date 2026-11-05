package com.github.auties00.smalt.fixture;

import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

// Fixture of the carried-receiver rule: a vector live at a loop head must be cast again before a call.
final class VectorLoops {
    static final VectorSpecies<Integer> S = IntVector.SPECIES_128;
    @SuppressWarnings("unchecked")
    static final Class<IntVector> INTS = (Class<IntVector>) S.vectorType();

    static int accumulator(int[] a) {
        var sum = INTS.cast(IntVector.zero(S));
        for (var i = 0; i < a.length; i += S.length()) {
            var x = INTS.cast(IntVector.fromArray(S, a, i));
            sum = sum.lanewise(VectorOperators.ADD, x); // expect: carried-receiver
        }
        return sum.reduceLanes(VectorOperators.ADD); // expect: carried-receiver
    }

    static int recast(int[] a) {
        var sum = INTS.cast(IntVector.zero(S));
        for (var i = 0; i < a.length; i += S.length()) {
            var x = INTS.cast(IntVector.fromArray(S, a, i));
            sum = INTS.cast(sum).lanewise(VectorOperators.ADD, x);
        }
        return INTS.cast(sum).reduceLanes(VectorOperators.ADD);
    }

    static void invariant(int[] a) {
        var k = INTS.cast(IntVector.broadcast(S, 7));
        for (var i = 0; i < a.length; i += S.length()) {
            INTS.cast(IntVector.fromArray(S, a, i)).lanewise(VectorOperators.ADD, k).intoArray(a, i);
            k.lanewise(VectorOperators.MUL, k).intoArray(a, i); // expect: carried-receiver
        }
    }

    static void bodyOnly(int[] a) {
        for (var i = 0; i < a.length; i += S.length()) {
            var x = INTS.cast(IntVector.fromArray(S, a, i));
            x.lanewise(VectorOperators.ADD, x).intoArray(a, i);
        }
    }

    static void switchLoop(int[] a, int[] kinds) {
        var k = INTS.cast(IntVector.broadcast(S, 3));
        for (var i = 0; i < kinds.length; i++) {
            switch (kinds[i]) {
                case 0 -> INTS.cast(IntVector.fromArray(S, a, 0)).intoArray(a, 4);
                case 1 -> k.intoArray(a, 8); // expect: carried-receiver
                default -> {
                }
            }
        }
    }

    static void handlerInLoop(int[] a) {
        for (var i = 0; i < 4; i++) {
            try {
                INTS.cast(IntVector.fromArray(S, a, i)).intoArray(a, 0);
            } catch (IndexOutOfBoundsException exception) {
                INTS.cast(IntVector.zero(S)).intoArray(a, 0);
            }
        }
    }
}
