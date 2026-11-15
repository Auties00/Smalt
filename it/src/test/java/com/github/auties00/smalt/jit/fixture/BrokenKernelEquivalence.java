package com.github.auties00.smalt.jit.fixture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/**
 * The equivalence test of {@link VectorBrokenKernel}, which fails on purpose: the class name keeps surefire
 * from running it, and the kernel guard's self-test ({@code KernelGuardSelfTest}) runs it.
 */
@DisplayName("broken fixture kernel")
class BrokenKernelEquivalence {
    private static int[] random(Random random, int length) {
        var values = new int[length];
        for (var i = 0; i < length; i++) {
            values[i] = random.nextInt(2001) - 1000;
        }
        return values;
    }

    @ParameterizedTest(name = "length {0}")
    @ValueSource(ints = {16, 37, 64, 129})
    @DisplayName("equals the scalar twin")
    void mix(int length) {
        var random = new Random(length);
        var a = random(random, length);
        var b = random(random, length);
        var expected = new int[length];
        var actual = new int[length];
        ScalarKernels.mix(a, b, expected, 0, length);
        VectorBrokenKernel.mix(a, b, actual, length);
        assertArrayEquals(expected, actual);
    }
}
