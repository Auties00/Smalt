package com.github.auties00.smalt.fixture;

import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

// Fixture of the exact-receiver rule: a call on a vector needs a receiver whose concrete class C2 knows.
final class VectorReceivers {
    static final VectorSpecies<Integer> S = IntVector.SPECIES_128;
    @SuppressWarnings("unchecked")
    static final Class<IntVector> INTS = (Class<IntVector>) S.vectorType();
    static final IntVector SEVEN = IntVector.broadcast(S, 7);

    static void loadReceiver(int[] a) {
        IntVector.fromArray(S, a, 0).intoArray(a, 4); // expect: exact-receiver
    }

    static void castReceiver(int[] a) {
        INTS.cast(IntVector.fromArray(S, a, 0)).intoArray(a, 4);
    }

    static void constantReceiver(int[] a) {
        SEVEN.intoArray(a, 0);
    }

    static void chained(int[] a) {
        var v = INTS.cast(IntVector.fromArray(S, a, 0));
        v.lanewise(VectorOperators.ADD, v).lanewise(VectorOperators.MUL, SEVEN).intoArray(a, 4);
    }

    static void conversion(int[] a) {
        var v = INTS.cast(IntVector.fromArray(S, a, 0));
        var w = (IntVector) v.convert(VectorOperators.I2S, 0).convert(VectorOperators.S2I, 0); // expect: exact-receiver
        w.intoArray(a, 4); // expect: exact-receiver
    }

    static void parameterReceiver(IntVector v, int[] a) {
        v.intoArray(a, 0); // expect: exact-receiver, vector-helper
    }

    static void join(int[] a, boolean f) {
        var v = INTS.cast(IntVector.fromArray(S, a, 0));
        IntVector w = f ? v : IntVector.fromArray(S, a, 4);
        w.intoArray(a, 0); // expect: exact-receiver
    }

    static void joinFlipped(int[] a, boolean f) {
        var v = INTS.cast(IntVector.fromArray(S, a, 0));
        IntVector w = f ? IntVector.fromArray(S, a, 4) : v;
        w.intoArray(a, 0); // expect: exact-receiver
    }

    static void ifElse(int[] a, boolean f) {
        IntVector w;
        if (f) {
            w = IntVector.fromArray(S, a, 4);
        } else {
            w = INTS.cast(IntVector.fromArray(S, a, 0));
        }
        w.intoArray(a, 0); // expect: exact-receiver
    }

    static void ifElseExact(int[] a, boolean f) {
        IntVector w;
        if (f) {
            w = INTS.cast(IntVector.fromArray(S, a, 4));
        } else {
            w = INTS.cast(IntVector.fromArray(S, a, 0));
        }
        w.intoArray(a, 0);
    }

    static int mask(int[] a) {
        var v = INTS.cast(IntVector.fromArray(S, a, 0));
        return v.compare(VectorOperators.LT, SEVEN).trueCount();
    }

    static void lambda(int[] a) {
        Runnable body = () -> IntVector.fromArray(S, a, 0).intoArray(a, 4); // expect: exact-receiver
        body.run();
    }
}
