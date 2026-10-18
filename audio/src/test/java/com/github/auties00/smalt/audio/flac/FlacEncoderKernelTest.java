package com.github.auties00.smalt.audio.flac;

import com.github.auties00.smalt.internal.simd.Simd;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Checks the encoder kernels: the Vector API implementation against the scalar one bit for bit,
 * tails and every sample size included, and the scalar one against naive formulas.
 */
class FlacEncoderKernelTest {
    static int[] randomInts(SplittableRandom random, int length, int bits) {
        var result = new int[length];
        var smooth = random.nextBoolean();
        var value = 0L;
        for (var i = 0; i < length; i++) {
            if (bits == 32) {
                result[i] = random.nextInt();
            } else if (smooth) {
                value = Math.max(-(1L << (bits - 1)), Math.min((1L << (bits - 1)) - 1,
                        value + random.nextLong(-(1L << Math.max(0, bits - 4)), (1L << Math.max(0, bits - 4)) + 1)));
                result[i] = (int) value;
            } else {
                result[i] = random.nextInt(-(1 << (bits - 1)), 1 << (bits - 1));
            }
        }
        return result;
    }

    static FlacEncoderKernel vector() {
        Assumptions.assumeTrue(Simd.ENABLED, "Vector API disabled");
        return VectorFlacEncoderKernel.create();
    }

    @Nested
    @DisplayName("SIMD equivalence")
    class Equivalence {
        private final FlacEncoderKernel scalar = new ScalarFlacEncoderKernel();

        @ParameterizedTest(name = "length {0}")
        @ValueSource(ints = {0, 1, 3, 5, 7, 8, 15, 16, 17, 33, 64, 191, 1000, 4096, 4608})
        @DisplayName("windowing and autocorrelation agree bit for bit for every lag count")
        void autocorrelation(int length) {
            var vector = vector();
            var random = new SplittableRandom(length);
            for (var bits : new int[]{8, 16, 24, 32}) {
                var signal = randomInts(random, length + 3, bits);
                var window = new double[length + 3];
                for (var i = 0; i < window.length; i++) {
                    window[i] = random.nextDouble();
                }
                var expected = new double[length + FlacEncoderKernel.AUTOCORRELATION_PADDING];
                var actual = new double[expected.length];
                scalar.window(signal, 3, window, 1, expected, 0, length);
                vector.window(signal, 3, window, 1, actual, 0, length);
                assertArrayEquals(expected, actual);
                for (var lags = 1; lags <= Math.min(33, Math.max(1, length)); lags++) {
                    var a = new double[33 + FlacEncoderKernel.AUTOCORRELATION_PADDING];
                    var b = new double[a.length];
                    scalar.autocorrelation(expected, length, lags, a);
                    vector.autocorrelation(expected, length, lags, b);
                    assertArrayEquals(Arrays.copyOf(a, lags), Arrays.copyOf(b, lags), lags + " lags");
                }
            }
        }

        @ParameterizedTest(name = "length {0}")
        @ValueSource(ints = {5, 7, 8, 15, 16, 17, 33, 64, 191, 1000, 4096, 4608})
        @DisplayName("residuals, error sums, magnitude sums and mid/side agree bit for bit")
        void integerKernels(int length) {
            var vector = vector();
            var random = new SplittableRandom(length * 7L);
            for (var bits : new int[]{4, 8, 16, 17, 20, 21, 24, 27, 28, 31, 32}) {
                var signal = randomInts(random, length, bits);
                for (var order = 0; order <= 4; order++) {
                    if (bits + order > 32) {
                        continue;
                    }
                    var a = new int[length];
                    var b = new int[length];
                    scalar.fixedResidual(signal, length, order, a);
                    vector.fixedResidual(signal, length, order, b);
                    assertArrayEquals(a, b, "fixed order " + order + ", " + bits + " bits");
                }
                var ea = new long[5];
                var eb = new long[5];
                scalar.fixedErrors(signal, length, ea, bits);
                vector.fixedErrors(signal, length, eb, bits);
                assertArrayEquals(ea, eb, "fixed errors, " + bits + " bits");
                for (var order : new int[]{1, 2, 3, 8, 12, 31, 32}) {
                    if (order >= length) {
                        continue;
                    }
                    var coefficients = new int[32];
                    var precision = order % 2 == 0 ? 15 : Math.max(5, Math.min(15, 32 - bits - 5));
                    for (var j = 0; j < order; j++) {
                        coefficients[j] = random.nextInt(-(1 << (precision - 1)), 1 << (precision - 1));
                    }
                    var shift = random.nextInt(16);
                    var a = new int[length];
                    var b = new int[length];
                    var fits = scalar.lpcResidualWide(signal, length, coefficients, order, shift, a);
                    assertEquals(fits, vector.lpcResidualWide(signal, length, coefficients, order, shift, b));
                    if (fits) {
                        assertArrayEquals(a, b, "wide LPC order " + order);
                    }
                    if (bits + precision + 5 <= 31) {
                        scalar.lpcResidual(signal, length, coefficients, order, shift, a);
                        vector.lpcResidual(signal, length, coefficients, order, shift, b);
                        assertArrayEquals(a, b, "LPC order " + order);
                    }
                    for (var from : new int[]{0, order, length / 3}) {
                        assertEquals(scalar.absSum(a, from, length), vector.absSum(a, from, length));
                    }
                }
                if (bits < 32) {
                    var right = randomInts(random, length, bits);
                    int[] m1 = new int[length], s1 = new int[length], m2 = new int[length], s2 = new int[length];
                    scalar.midSide(signal, right, m1, s1, length);
                    vector.midSide(signal, right, m2, s2, length);
                    assertArrayEquals(m1, m2);
                    assertArrayEquals(s1, s2);
                }
            }
        }

        @ParameterizedTest(name = "{0} bits")
        @ValueSource(ints = {8, 16, 20, 24, 32})
        @DisplayName("encoders on either kernel write byte-identical streams at every level")
        void streams(int bits) {
            var vector = vector();
            for (var level = 0; level <= 8; level++) {
                var options = FlacEncoderOptions.ofCompressionLevel(level).toBuilder().bitsPerSample(bits)
                        .blockSize(2048).build();
                for (var shape : FlacEncoding.Shape.values()) {
                    var samples = FlacEncoding.signal(shape, 2, bits, 9_000, level * 31L + shape.ordinal());
                    var expected = new java.io.ByteArrayOutputStream();
                    var actual = new java.io.ByteArrayOutputStream();
                    var a = new FlacEncoder(options, scalar);
                    a.encode(samples, 0, 9_000, packet -> expected.writeBytes(packet.data()));
                    a.flush(packet -> expected.writeBytes(packet.data()));
                    var b = new FlacEncoder(options, vector);
                    b.encode(samples, 0, 9_000, packet -> actual.writeBytes(packet.data()));
                    b.flush(packet -> actual.writeBytes(packet.data()));
                    assertArrayEquals(expected.toByteArray(), actual.toByteArray(), "level " + level + ", " + shape);
                }
            }
        }

        @Test
        @DisplayName("magnitude sums of extreme residuals agree")
        void extremeSums() {
            var vector = vector();
            var residual = new int[1000];
            Arrays.fill(residual, Integer.MAX_VALUE);
            for (var i = 0; i < residual.length; i += 3) {
                residual[i] = -Integer.MAX_VALUE;
            }
            assertEquals(1000L * Integer.MAX_VALUE, scalar.absSum(residual, 0, 1000));
            assertEquals(scalar.absSum(residual, 0, 1000), vector.absSum(residual, 0, 1000));
        }
    }

    @Nested
    @DisplayName("scalar reference")
    class Reference {
        private final FlacEncoderKernel scalar = new ScalarFlacEncoderKernel();

        @Test
        @DisplayName("fixed residuals match the predictor polynomials and the error sums their magnitudes")
        void fixed() {
            var random = new SplittableRandom(3);
            var signal = randomInts(random, 500, 16);
            var errors = new long[5];
            scalar.fixedErrors(signal, 500, errors, 16);
            for (var order = 0; order <= 4; order++) {
                var residual = new int[500];
                scalar.fixedResidual(signal, 500, order, residual);
                var sum = 0L;
                for (var i = 4; i < 500; i++) {
                    long expected = switch (order) {
                        case 0 -> signal[i];
                        case 1 -> signal[i] - signal[i - 1];
                        case 2 -> signal[i] - 2L * signal[i - 1] + signal[i - 2];
                        case 3 -> signal[i] - 3L * signal[i - 1] + 3L * signal[i - 2] - signal[i - 3];
                        default -> signal[i] - 4L * signal[i - 1] + 6L * signal[i - 2] - 4L * signal[i - 3] + signal[i - 4];
                    };
                    assertEquals(expected, residual[i]);
                    sum += Math.abs(expected);
                }
                assertEquals(sum, errors[order]);
            }
        }

        @Test
        @DisplayName("wide error sums flag the orders whose residuals leave the 32-bit range")
        void wideErrors() {
            var signal = new int[]{Integer.MAX_VALUE, Integer.MIN_VALUE + 1, Integer.MAX_VALUE, Integer.MIN_VALUE + 1, Integer.MAX_VALUE, 0};
            var errors = new long[5];
            scalar.fixedErrors(signal, signal.length, errors, 32);
            assertNotEquals(Long.MAX_VALUE, errors[0]);
            for (var order = 1; order <= 4; order++) {
                assertEquals(Long.MAX_VALUE, errors[order], "order " + order);
            }
        }

        @Test
        @DisplayName("the 32-bit and 64-bit LPC residuals agree when no sum overflows, and the wide one rejects overflow")
        void lpc() {
            var random = new SplittableRandom(4);
            var signal = randomInts(random, 300, 16);
            int[] coefficients = {900, -400, 120, -33, 7};
            var a = new int[300];
            var b = new int[300];
            scalar.lpcResidual(signal, 300, coefficients, 5, 9, a);
            assertTrue(scalar.lpcResidualWide(signal, 300, coefficients, 5, 9, b));
            assertArrayEquals(a, b);
            var huge = new int[]{Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE};
            assertFalse(scalar.lpcResidualWide(huge, 3, new int[]{-16000, 0}, 2, 0, new int[3]));
        }
    }
}
