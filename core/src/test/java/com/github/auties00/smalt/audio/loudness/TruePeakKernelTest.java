package com.github.auties00.smalt.audio.loudness;

import com.github.auties00.smalt.internal.simd.Simd;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Checks that the vector true-peak kernel returns bit-identical results to its scalar twin, for
 * the filters of every oversampling factor and for block lengths around the vector width.
 */
@DisplayName("TruePeakKernel")
class TruePeakKernelTest {
    @ParameterizedTest(name = "{0} Hz")
    @ValueSource(ints = {44_100, 48_000, 96_000, 176_400})
    @DisplayName("vector and scalar kernels agree bit for bit")
    void equivalence(int rate) {
        assumeTrue(Simd.ENABLED, "the Vector API is not available");
        var filter = TruePeakMeter.filter(rate);
        var vector = VectorLoudnessKernels.truePeak();
        var scalar = new ScalarTruePeakKernel();
        var random = new Random(rate);
        for (var count : new int[]{0, 1, 3, 7, 8, 9, 15, 16, 17, 31, 64, 100, 1000, 4096}) {
            var input = new float[filter.history() + count];
            for (var trial = 0; trial < 20; trial++) {
                for (var index = 0; index < input.length; index++) {
                    input[index] = (float) (random.nextGaussian() * (trial % 3 == 0 ? 1e-3 : 0.5));
                }
                var expected = scalar.peak(input, count, filter.coefficients(), filter.offsets(), filter.phases(), filter.taps());
                var actual = vector.peak(input, count, filter.coefficients(), filter.offsets(), filter.phases(), filter.taps());
                assertEquals(Float.floatToRawIntBits(expected), Float.floatToRawIntBits(actual), "count " + count);
            }
        }
    }

    @Test
    @DisplayName("builds three 12-tap phases at 48 kHz and one 24-tap phase at 96 kHz, none at 192 kHz")
    void filters() {
        var four = TruePeakMeter.filter(48_000);
        assertEquals(3, four.phases());
        assertEquals(12, four.taps());
        assertEquals(12, four.history());
        var two = TruePeakMeter.filter(96_000);
        assertEquals(1, two.phases());
        assertEquals(24, two.taps());
        assertEquals(null, TruePeakMeter.filter(192_000));
    }
}
