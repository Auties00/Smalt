package com.github.auties00.smalt.fixture;

import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

// Fixture of the loop-receiver rule: an instance kernel must not read its receiver after a vector loop.
final class VectorTails {
    static final VectorSpecies<Integer> S = IntVector.SPECIES_128;
    @SuppressWarnings("unchecked")
    static final Class<IntVector> INTS = (Class<IntVector>) S.vectorType();
    static final Tail STATIC_TAIL = new Tail();

    private final Tail tail = new Tail();
    // not a constant, so that reading it loads the receiver
    private final int bias;

    VectorTails(int bias) {
        this.bias = bias;
    }

    static final class Tail {
        void add(int[] a, int from) {
            for (var i = from; i < a.length; i++) {
                a[i]++;
            }
        }

        static void addStatic(int[] a, int from) {
            for (var i = from; i < a.length; i++) {
                a[i]++;
            }
        }
    }

    void fieldTail(int[] a) {
        var i = 0;
        for (; i + S.length() <= a.length; i += S.length()) {
            var x = INTS.cast(IntVector.fromArray(S, a, i));
            x.lanewise(VectorOperators.ADD, x).intoArray(a, i);
        }
        tail.add(a, i); // expect: loop-receiver
    }

    void staticFieldTail(int[] a) {
        var i = 0;
        for (; i + S.length() <= a.length; i += S.length()) {
            var x = INTS.cast(IntVector.fromArray(S, a, i));
            x.lanewise(VectorOperators.ADD, x).intoArray(a, i);
        }
        STATIC_TAIL.add(a, i);
    }

    void staticTail(int[] a) {
        var i = 0;
        for (; i + S.length() <= a.length; i += S.length()) {
            var x = INTS.cast(IntVector.fromArray(S, a, i));
            x.lanewise(VectorOperators.ADD, x).intoArray(a, i);
        }
        Tail.addStatic(a, i);
    }

    void readBeforeLoop(int[] a) {
        var b = INTS.cast(IntVector.broadcast(S, bias));
        for (var i = 0; i + S.length() <= a.length; i += S.length()) {
            var x = INTS.cast(IntVector.fromArray(S, a, i));
            x.lanewise(VectorOperators.ADD, b).intoArray(a, i);
        }
    }

    void readInOuterLoop(int[][] rows) {
        for (var row : rows) {
            var b = INTS.cast(IntVector.broadcast(S, bias)); // expect: loop-receiver
            for (var i = 0; i + S.length() <= row.length; i += S.length()) {
                var x = INTS.cast(IntVector.fromArray(S, row, i));
                x.lanewise(VectorOperators.ADD, b).intoArray(row, i);
            }
        }
    }

    void scalarLoopTail(int[] a) {
        var x = INTS.cast(IntVector.fromArray(S, a, 0));
        x.lanewise(VectorOperators.ADD, x).intoArray(a, 0);
        for (var i = S.length(); i < a.length; i++) {
            a[i] += bias;
        }
        tail.add(a, a.length);
    }
}
