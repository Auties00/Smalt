package com.github.auties00.smalt.fixture;

import com.github.auties00.smalt.internal.simd.KernelLintSuppress;
import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.VectorSpecies;

// Fixture of the suppressions: one with a reason accepts its rule; a stale, a reasonless or an unknown one fails; a
// method carrying one is checked for the forbidden-apis bans, which accept only the rules it names.
final class VectorSuppressions {
    static final VectorSpecies<Integer> S = IntVector.SPECIES_128;
    @SuppressWarnings("unchecked")
    static final Class<IntVector> INTS = (Class<IntVector>) S.vectorType();

    @KernelLintSuppress(value = "exact-receiver", reason = "the fixture's accepted finding")
    static void accepted(int[] a) {
        IntVector.fromArray(S, a, 0).intoArray(a, 4);
    }

    @KernelLintSuppress(value = "carried-receiver", reason = "nothing here is carried")
    static void stale(int[] a) {
        a[0] = 1; // expect: stale-suppression
    }

    @KernelLintSuppress(value = "exact-receiver", reason = " ")
    static void reasonless(int[] a) {
        IntVector.fromArray(S, a, 0).intoArray(a, 4); // expect: bad-suppression, exact-receiver
    }

    @KernelLintSuppress(value = "no-such-rule", reason = "a typo")
    static void unknown(int[] a) {
        a[0] = 2; // expect: bad-suppression
    }

    @KernelLintSuppress(value = "vector-convenience", reason = "the fixture's accepted convenience call")
    static void convenience(int[] a) {
        var v = INTS.cast(IntVector.fromArray(S, a, 0));
        INTS.cast(v.add(v)).intoArray(a, 4);
    }

    @KernelLintSuppress(value = "exact-receiver", reason = "the fixture's accepted receivers")
    static void otherBans(int[] a, byte[] b) {
        IntVector.fromArray(S, a, 0).intoArray(a, 4);
        var v = INTS.cast(IntVector.fromArray(S, a, 0));
        INTS.cast(v.mul(v)).intoArray(a, 4); // expect: vector-convenience
        v.reinterpretAsBytes().intoArray(b, 0); // expect: vector-reinterpret
    }

    @KernelLintSuppress(value = {"vector-convenience", "vector-reinterpret"}, reason = "nothing here is banned")
    static void staleBans(int[] a) {
        INTS.cast(IntVector.fromArray(S, a, 0)).intoArray(a, 4); // expect: stale-suppression
    }

    @KernelLintSuppress(value = "vector-convenience", reason = "the fixture's accepted convenience call")
    static double math(int[] a) {
        var v = INTS.cast(IntVector.fromArray(S, a, 0));
        INTS.cast(v.sub(v)).intoArray(a, 4);
        return Math.sin(a[0]); // expect: strict-math
    }
}
