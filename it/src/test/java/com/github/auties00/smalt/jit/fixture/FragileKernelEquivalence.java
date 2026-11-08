package com.github.auties00.smalt.jit.fixture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/**
 * The equivalence test of {@link VectorFragileKernel}, which the kernel guard runs to fail the kernel's
 * allocation.
 */
@DisplayName("fragile fixture kernel")
class FragileKernelEquivalence {
    private static int[] random(Random random, int length) {
        var values = new int[length];
        for (var i = 0; i < length; i++) {
            values[i] = random.nextInt(2001) - 1000;
        }
        return values;
    }

    @ParameterizedTest(name = "length {0}")
    @ValueSource(ints = {16, 37, 64})
    @DisplayName("equals the scalar twin at every shape")
    void add(int length) {
        var random = new Random(length);
        var a = random(random, length);
        var b = random(random, length);
        var expected = new int[length];
        ScalarKernels.add(a, b, expected);
        var actual = new int[length];
        VectorFragileKernel.add64(a, b, actual);
        assertArrayEquals(expected, actual);
        VectorFragileKernel.add128(a, b, actual);
        assertArrayEquals(expected, actual);
        VectorFragileKernel.add256(a, b, actual);
        assertArrayEquals(expected, actual);
    }
}
