package com.github.auties00.smalt.fixture;

import com.github.auties00.smalt.internal.simd.KernelLintSuppress;
import jdk.incubator.vector.IntVector;

// Fixture of the vector-confined ban in suppressed methods: a class not named Vector* may not name the Vector API.
final class ConfinedSuppressions {
    @KernelLintSuppress(value = "vector-confined", reason = "the fixture's accepted reference")
    static int accepted() {
        return IntVector.SPECIES_128.length();
    }

    @KernelLintSuppress(value = "strict-math", reason = "the fixture's accepted Math call")
    static double other(double x) {
        var lanes = IntVector.SPECIES_128.length(); // expect: vector-confined
        return Math.sin(x) * lanes;
    }

    static double unsuppressed(double x) {
        return IntVector.SPECIES_128.length() * Math.cos(x);
    }
}
