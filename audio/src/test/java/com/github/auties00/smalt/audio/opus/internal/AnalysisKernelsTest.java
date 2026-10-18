package com.github.auties00.smalt.audio.opus.internal;

import com.github.auties00.smalt.internal.simd.Simd;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/**
 * Checks that the Vector API analysis kernel is bit-identical to the scalar reference, including
 * tiny bins (the zero branch of {@code fast_atan2f}), steep and flat angles, ties of the phase
 * rounding and huge or non-finite values that force the scalar fallback.
 */
class AnalysisKernelsTest {
    @ParameterizedTest(name = "seed {0}")
    @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8})
    @DisplayName("tonalityBins matches the scalar kernel bit for bit")
    void tonalityBins(int seed) {
        Assumptions.assumeTrue(Simd.ENABLED, "Vector API not enabled");
        var random = new Random(seed);
        var input = new float[4][240];
        var state = new float[3][240];
        for (var k = 0; k < 4; k++) {
            for (var i = 0; i < 240; i++) {
                input[k][i] = switch (random.nextInt(8)) {
                    case 0 -> 0f;
                    case 1 -> (float) random.nextGaussian() * 1e-10f;
                    case 2 -> seed == 8 && i == 77 ? Float.NaN : (float) random.nextGaussian() * 1e6f;
                    case 3 -> random.nextInt(5) - 2;
                    default -> (float) random.nextGaussian() * 100f;
                };
            }
        }
        for (var k = 0; k < 3; k++) {
            for (var i = 0; i < 240; i++) {
                state[k][i] = seed == 7 && i == 100 ? 1e30f : (float) random.nextGaussian();
            }
        }
        var scalarState = new float[][]{state[0].clone(), state[1].clone(), state[2].clone()};
        var vectorState = new float[][]{state[0].clone(), state[1].clone(), state[2].clone()};
        var scalarOut = new float[3][240];
        var vectorOut = new float[3][240];
        new ScalarAnalysisKernels().tonalityBins(input[0], input[1], input[2], input[3], scalarState[0], scalarState[1],
                scalarState[2], scalarOut[0], scalarOut[1], scalarOut[2]);
        VectorAnalysisKernels.create().tonalityBins(input[0], input[1], input[2], input[3], vectorState[0], vectorState[1],
                vectorState[2], vectorOut[0], vectorOut[1], vectorOut[2]);
        for (var k = 0; k < 3; k++) {
            assertArrayEquals(scalarState[k], vectorState[k]);
            assertArrayEquals(scalarOut[k], vectorOut[k]);
        }
    }
}
