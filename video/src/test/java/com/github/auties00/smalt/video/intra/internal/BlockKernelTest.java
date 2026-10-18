package com.github.auties00.smalt.video.intra.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the 8x8 block kernels of the intra codecs: the vector kernel against its scalar twin on
 * random input, the transforms against the defining double-precision sums, and the inverse
 * transform against the IDCT accuracy qualification of SMPTE RDD 36 Annex A (the IEEE Std
 * 1180-1990 procedure with quarter-integer coefficients and fractional outputs).
 */
@DisplayName("intra block kernels")
class BlockKernelTest {
    static final BlockKernel SCALAR = new ScalarBlockKernel();
    static final BlockKernel VECTOR = VectorBlockKernels.vector();

    static float[] random(Random random, float range) {
        var batch = new float[BlockKernel.BATCH_LENGTH];
        for (var i = 0; i < batch.length; i++) {
            batch[i] = (random.nextFloat() * 2 - 1) * range;
        }
        return batch;
    }

    @Nested
    @DisplayName("vector and scalar implementations")
    class Equivalence {
        @Test
        @DisplayName("transform bit-identically")
        void transforms() {
            var random = new Random(1);
            for (var round = 0; round < 2000; round++) {
                var input = random(random, round % 2 == 0 ? 2048 : 1e6f);
                var a = input.clone();
                var b = input.clone();
                SCALAR.inverse(a);
                VECTOR.inverse(b);
                assertArrayEquals(a, b);
                SCALAR.forward(a);
                VECTOR.forward(b);
                assertArrayEquals(a, b);
            }
        }

        @Test
        @DisplayName("convert and quantize bit-identically")
        void conversions() {
            var random = new Random(2);
            var outA = new int[BlockKernel.BATCH_LENGTH];
            var outB = new int[BlockKernel.BATCH_LENGTH];
            for (var round = 0; round < 2000; round++) {
                var input = random(random, 600);
                if (round % 7 == 0) {
                    input[3] = Float.NaN;
                    input[9] = Float.POSITIVE_INFINITY;
                    input[17] = -0f;
                }
                var scale = 0.5f + random.nextFloat() * 8;
                SCALAR.toSamples(input, scale, 512.5f, 1023.5f, outA);
                VECTOR.toSamples(input, scale, 512.5f, 1023.5f, outB);
                assertArrayEquals(outA, outB);
                var weights = random(random, 1);
                SCALAR.quantize(input, weights, 0.4f, outA);
                VECTOR.quantize(input, weights, 0.4f, outB);
                assertArrayEquals(outA, outB);
            }
        }
    }

    @Nested
    @DisplayName("block loading")
    class BlockLoading {
        @Test
        @DisplayName("matches the definition in both kernels, for every lane count")
        void loads() {
            var random = new Random(9);
            var planes = new short[3][4096];
            for (var plane : planes) {
                for (var i = 0; i < plane.length; i++) {
                    plane[i] = (short) random.nextInt();
                }
            }
            var scratch = new float[64];
            for (var lanes = 1; lanes <= BlockKernel.LANES; lanes++) {
                var sources = new short[BlockKernel.LANES][];
                var offsets = new int[BlockKernel.LANES];
                var strides = new int[BlockKernel.LANES];
                for (var lane = 0; lane < lanes; lane++) {
                    sources[lane] = planes[lane % 3];
                    strides[lane] = 8 + random.nextInt(64);
                    offsets[lane] = random.nextInt(4096 - 8 - 7 * strides[lane]);
                }
                var a = new float[BlockKernel.BATCH_LENGTH];
                var b = new float[BlockKernel.BATCH_LENGTH];
                java.util.Arrays.fill(a, Float.NaN);
                java.util.Arrays.fill(b, Float.NaN);
                SCALAR.loadBlocks(sources, offsets, strides, lanes, 512f, 0.25f, a, scratch);
                VECTOR.loadBlocks(sources, offsets, strides, lanes, 512f, 0.25f, b, scratch);
                assertArrayEquals(a, b);
                for (var lane = 0; lane < BlockKernel.LANES; lane++) {
                    for (var row = 0; row < 8; row++) {
                        for (var column = 0; column < 8; column++) {
                            var expected = lane >= lanes ? 0f
                                    : ((sources[lane][offsets[lane] + row * strides[lane] + column] & 0xFFFF) - 512f) * 0.25f;
                            assertEquals(expected, a[(row * 8 + column) * BlockKernel.LANES + lane]);
                        }
                    }
                }
            }
        }
    }

    @Nested
    @DisplayName("masked quantization")
    class MaskedQuantization {
        @Test
        @DisplayName("matches the magnitudes of the plain quantizer, the limit and the non-zero masks, in both kernels")
        void masked() {
            var random = new Random(5);
            var plain = new int[BlockKernel.BATCH_LENGTH];
            var outA = new int[BlockKernel.BATCH_LENGTH];
            var outB = new int[BlockKernel.BATCH_LENGTH];
            var masksA = new int[64];
            var masksB = new int[64];
            for (var round = 0; round < 2000; round++) {
                var input = random(random, 600);
                if (round % 7 == 0) {
                    input[3] = Float.NaN;
                    input[9] = Float.POSITIVE_INFINITY;
                    input[17] = -0f;
                }
                var weights = random(random, 1);
                for (var i = 0; i < weights.length; i++) {
                    weights[i] = Math.abs(weights[i]);
                }
                var limit = round % 3 == 0 ? 3 : 1 << 20;
                SCALAR.quantize(input, weights, 0.4f, plain);
                SCALAR.quantizeMasked(input, weights, 0.4f, limit, outA, masksA);
                VECTOR.quantizeMasked(input, weights, 0.4f, limit, outB, masksB);
                assertArrayEquals(outA, outB);
                assertArrayEquals(masksA, masksB);
                for (var i = 0; i < plain.length; i++) {
                    assertEquals(Math.min(Math.abs(plain[i]), limit), outA[i]);
                    assertEquals(outA[i] != 0, (masksA[i >>> 3] >>> (i & 7) & 1) != 0);
                }
            }
        }
    }

    @Nested
    @DisplayName("transforms")
    class Transforms {
        @Test
        @DisplayName("match the defining sums and invert each other")
        void definition() {
            var random = new Random(3);
            var batch = random(random, 255);
            var original = batch.clone();
            SCALAR.forward(batch);
            for (var lane = 0; lane < BlockKernel.LANES; lane++) {
                for (var v = 0; v < 8; v++) {
                    for (var u = 0; u < 8; u++) {
                        var sum = 0.0;
                        for (var y = 0; y < 8; y++) {
                            for (var x = 0; x < 8; x++) {
                                sum += original[(y * 8 + x) * 8 + lane] * Math.cos((2 * x + 1) * u * Math.PI / 16)
                                        * Math.cos((2 * y + 1) * v * Math.PI / 16);
                            }
                        }
                        var expected = 0.25 * (u == 0 ? Math.sqrt(0.5) : 1) * (v == 0 ? Math.sqrt(0.5) : 1) * sum;
                        assertEquals(expected, batch[(v * 8 + u) * 8 + lane], 1e-2);
                    }
                }
            }
            SCALAR.inverse(batch);
            for (var i = 0; i < batch.length; i++) {
                assertEquals(original[i], batch[i], 1e-3);
            }
        }

        @ParameterizedTest(name = "L={0} H={1} sign={2}")
        @CsvSource({"2048, 2047, 1", "40, 40, 1", "2400, 2400, 1", "2048, 2047, -1", "40, 40, -1", "2400, 2400, -1"})
        @DisplayName("meet the RDD 36 Annex A accuracy bounds")
        void accuracy(int low, int high, int sign) {
            var generator = new Ieee1180Random();
            var peak = new double[64];
            var squares = new double[64];
            var means = new double[64];
            var blocks = 10_000;
            var batch = new float[BlockKernel.BATCH_LENGTH];
            var reference = new double[64];
            for (var block = 0; block < blocks; block++) {
                var pixels = new double[64];
                for (var i = 0; i < 64; i++) {
                    pixels[i] = sign * generator.next(low, high) / 8.0;
                }
                var coefficients = forward(pixels);
                for (var i = 0; i < 64; i++) {
                    var rounded = Math.round(coefficients[i] * 4) / 4.0;
                    coefficients[i] = Math.clamp(rounded, -2048, 2047.75);
                    batch[i * 8] = (float) coefficients[i];
                }
                var exact = inverse(coefficients);
                for (var i = 0; i < 64; i++) {
                    reference[i] = Math.clamp(exact[i], -256, 256);
                }
                SCALAR.inverse(batch);
                for (var i = 0; i < 64; i++) {
                    var test = Math.clamp((double) batch[i * 8], -256, 256);
                    var error = test - reference[i];
                    peak[i] = Math.max(peak[i], Math.abs(error));
                    squares[i] += error * error;
                    means[i] += error;
                }
                java.util.Arrays.fill(batch, 0f);
            }
            var overallSquares = 0.0;
            var overallMean = 0.0;
            for (var i = 0; i < 64; i++) {
                assertTrue(peak[i] <= 0.15, "peak error " + peak[i]);
                assertTrue(squares[i] / blocks <= 0.002, "pixel mean square error " + squares[i] / blocks);
                assertTrue(Math.abs(means[i] / blocks) <= 0.0015, "pixel mean error " + means[i] / blocks);
                overallSquares += squares[i];
                overallMean += means[i];
            }
            assertTrue(overallSquares / (64.0 * blocks) <= 0.001, "overall mean square error");
            assertTrue(Math.abs(overallMean / (64.0 * blocks)) <= 0.00015, "overall mean error");
        }

        static final double[][] BASIS = new double[8][8];

        static {
            for (var k = 0; k < 8; k++) {
                for (var n = 0; n < 8; n++) {
                    BASIS[k][n] = (k == 0 ? Math.sqrt(0.125) : 0.5) * Math.cos((2 * n + 1) * k * Math.PI / 16);
                }
            }
        }

        /** Computes the orthonormal 2-D DCT in double precision, separably. */
        static double[] forward(double[] pixels) {
            var rows = new double[64];
            for (var y = 0; y < 8; y++) {
                for (var u = 0; u < 8; u++) {
                    var sum = 0.0;
                    for (var x = 0; x < 8; x++) {
                        sum += BASIS[u][x] * pixels[y * 8 + x];
                    }
                    rows[y * 8 + u] = sum;
                }
            }
            var out = new double[64];
            for (var u = 0; u < 8; u++) {
                for (var v = 0; v < 8; v++) {
                    var sum = 0.0;
                    for (var y = 0; y < 8; y++) {
                        sum += BASIS[v][y] * rows[y * 8 + u];
                    }
                    out[v * 8 + u] = sum;
                }
            }
            return out;
        }

        /** Computes the orthonormal 2-D inverse DCT in double precision, separably. */
        static double[] inverse(double[] coefficients) {
            var rows = new double[64];
            for (var v = 0; v < 8; v++) {
                for (var x = 0; x < 8; x++) {
                    var sum = 0.0;
                    for (var u = 0; u < 8; u++) {
                        sum += BASIS[u][x] * coefficients[v * 8 + u];
                    }
                    rows[v * 8 + x] = sum;
                }
            }
            var out = new double[64];
            for (var x = 0; x < 8; x++) {
                for (var y = 0; y < 8; y++) {
                    var sum = 0.0;
                    for (var v = 0; v < 8; v++) {
                        sum += BASIS[v][y] * rows[v * 8 + x];
                    }
                    out[y * 8 + x] = sum;
                }
            }
            return out;
        }
    }

    /** The random number generator of the appendix of IEEE Std 1180-1990. */
    static final class Ieee1180Random {
        private long state = 1;

        int next(int low, int high) {
            state = (state * 1103515245L + 12345L) & 0xFFFFFFFFL;
            var i = state & 0x7FFFFFFEL;
            var x = (double) i / 0x7FFFFFFF;
            x *= low + high + 1;
            return (int) x - low;
        }
    }
}
