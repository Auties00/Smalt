package com.github.auties00.smalt.video.mpeg4.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the Vector API kernels against their scalar twins on random blocks and planes, and the
 * scalar inverse DCT against the IEEE 1180 accuracy test that ISO/IEC 14496-2 Annex A and ITU-T
 * H.263 Annex A require.
 */
@DisplayName("MPEG-4 Part 2 DSP kernels")
class Mpeg4DspTest {
    private static final ScalarMpeg4Dsp SCALAR = new ScalarMpeg4Dsp();
    private static final Mpeg4Dsp VECTOR = VectorMpeg4Dsp.create();
    private static final int STRIDE = 48;

    /** The first pass of the inverse DCT with no shortcut, the arithmetic of libjpeg's {@code jidctint}. */
    static void plainRows(short[] c, int[] out) {
        for (var row = 0; row < 8; row++) {
            var d0 = (int) c[row];
            var d1 = (int) c[8 + row];
            var d2 = (int) c[16 + row];
            var d3 = (int) c[24 + row];
            var d4 = (int) c[32 + row];
            var d5 = (int) c[40 + row];
            var d6 = (int) c[48 + row];
            var d7 = (int) c[56 + row];
            var o = row * 8;
            var z1 = (d2 + d6) * ScalarMpeg4Dsp.FIX_0_541196100;
            var tmp2 = z1 + d6 * -ScalarMpeg4Dsp.FIX_1_847759065;
            var tmp3 = z1 + d2 * ScalarMpeg4Dsp.FIX_0_765366865;
            var tmp0 = (d0 + d4) << ScalarMpeg4Dsp.CONST_BITS;
            var tmp1 = (d0 - d4) << ScalarMpeg4Dsp.CONST_BITS;
            var tmp10 = tmp0 + tmp3;
            var tmp13 = tmp0 - tmp3;
            var tmp11 = tmp1 + tmp2;
            var tmp12 = tmp1 - tmp2;
            z1 = d7 + d1;
            var z2 = d5 + d3;
            var z3 = d7 + d3;
            var z4 = d5 + d1;
            var z5 = (z3 + z4) * ScalarMpeg4Dsp.FIX_1_175875602;
            tmp0 = d7 * ScalarMpeg4Dsp.FIX_0_298631336;
            tmp1 = d5 * ScalarMpeg4Dsp.FIX_2_053119869;
            tmp2 = d3 * ScalarMpeg4Dsp.FIX_3_072711026;
            tmp3 = d1 * ScalarMpeg4Dsp.FIX_1_501321110;
            z1 = z1 * -ScalarMpeg4Dsp.FIX_0_899976223;
            z2 = z2 * -ScalarMpeg4Dsp.FIX_2_562915447;
            z3 = z3 * -ScalarMpeg4Dsp.FIX_1_961570560 + z5;
            z4 = z4 * -ScalarMpeg4Dsp.FIX_0_390180644 + z5;
            tmp0 += z1 + z3;
            tmp1 += z2 + z4;
            tmp2 += z2 + z3;
            tmp3 += z1 + z4;
            var round = 1 << (ScalarMpeg4Dsp.CONST_BITS - ScalarMpeg4Dsp.PASS1_BITS - 1);
            var shift = ScalarMpeg4Dsp.CONST_BITS - ScalarMpeg4Dsp.PASS1_BITS;
            if ((d1 | d2 | d3 | d4 | d5 | d6 | d7) == 0) {
                var dc = d0 << ScalarMpeg4Dsp.PASS1_BITS;
                for (var i = 0; i < 8; i++) {
                    out[o + i] = dc;
                }
                continue;
            }
            out[o] = (tmp10 + tmp3 + round) >> shift;
            out[o + 7] = (tmp10 - tmp3 + round) >> shift;
            out[o + 1] = (tmp11 + tmp2 + round) >> shift;
            out[o + 6] = (tmp11 - tmp2 + round) >> shift;
            out[o + 2] = (tmp12 + tmp1 + round) >> shift;
            out[o + 5] = (tmp12 - tmp1 + round) >> shift;
            out[o + 3] = (tmp13 + tmp0 + round) >> shift;
            out[o + 4] = (tmp13 - tmp0 + round) >> shift;
        }
    }

    static short[] block(Random random, int kind) {
        var block = new short[64];
        switch (kind % 4) {
            case 0 -> block[0] = (short) (random.nextInt(4096) - 2048);
            case 1 -> {
                for (var i = 0; i < 6; i++) {
                    block[random.nextInt(64)] = (short) (random.nextInt(200) - 100);
                }
            }
            case 2 -> {
                for (var i = 0; i < 64; i++) {
                    block[i] = (short) (random.nextInt(4096) - 2048);
                }
            }
            default -> {
                for (var i = 0; i < 64; i++) {
                    block[i] = (short) (random.nextBoolean() ? 2047 : -2048);
                }
            }
        }
        return block;
    }

    @Nested
    @DisplayName("vector against scalar")
    class Equivalence {
        @ParameterizedTest(name = "seed {0}")
        @ValueSource(ints = {1, 2, 3, 4})
        @DisplayName("inverse DCT store and add equal scalar and clear the block")
        void idct(int seed) {
            var random = new Random(seed);
            for (var round = 0; round < 2000; round++) {
                var coefficients = block(random, round);
                var expected = new byte[STRIDE * 10];
                random.nextBytes(expected);
                var actual = expected.clone();
                var a = coefficients.clone();
                var b = coefficients.clone();
                var add = round % 2 == 0;
                var offset = STRIDE + 3;
                if (add) {
                    SCALAR.idctAdd(a, 0, expected, offset, STRIDE, new int[64]);
                    VECTOR.idctAdd(b, 0, actual, offset, STRIDE, new int[64]);
                } else {
                    SCALAR.idctPut(a, 0, expected, offset, STRIDE, new int[64]);
                    VECTOR.idctPut(b, 0, actual, offset, STRIDE, new int[64]);
                }
                assertArrayEquals(expected, actual, "round " + round);
                assertArrayEquals(new short[64], b, "block cleared");
            }
        }

        @ParameterizedTest(name = "seed {0}")
        @ValueSource(ints = {1, 2, 3, 4})
        @DisplayName("the first pass with its shortcuts for sparse rows equals the plain first pass")
        void sparseRows(int seed) {
            var random = new Random(seed);
            for (var round = 0; round < 5000; round++) {
                var coefficients = new short[64];
                var columns = 1 + round % 8;
                var density = 1 + random.nextInt(4);
                for (var i = 0; i < 64; i++) {
                    if (i / 8 < columns && random.nextInt(density + 1) == 0) {
                        coefficients[i] = (short) (random.nextInt(4096) - 2048);
                    }
                }
                var expected = new int[64];
                plainRows(coefficients, expected);
                var actual = new int[64];
                var copy = coefficients.clone();
                var mask = ScalarMpeg4Dsp.rows(copy, 0, actual);
                assertArrayEquals(expected, actual, "round " + round);
                assertArrayEquals(new short[64], copy, "block cleared");
                var expectedMask = 0;
                for (var row = 0; row < 8; row++) {
                    for (var column = 0; column < 8; column++) {
                        if (coefficients[column * 8 + row] != 0) {
                            expectedMask |= 1 << row;
                        }
                    }
                }
                assertEquals(expectedMask, mask, "rows with coefficients");
            }
        }

        @ParameterizedTest(name = "seed {0}")
        @ValueSource(ints = {1, 2, 3, 4})
        @DisplayName("the shortcuts for empty and first-row-only blocks equal the full two pass transform")
        void sparseBlocks(int seed) {
            var random = new Random(seed);
            for (var round = 0; round < 3000; round++) {
                var coefficients = new short[64];
                var rows = round % 4;
                for (var row = 0; row < rows; row++) {
                    for (var column = 0; column < 8; column++) {
                        if (random.nextInt(3) == 0 || row == 0 && round % 8 == 0) {
                            coefficients[column * 8 + row] = (short) (random.nextInt(4096) - 2048);
                        }
                    }
                }
                if (round % 16 == 5) {
                    coefficients[random.nextInt(8) * 8] = (short) (random.nextInt(4096) - 2048);
                }
                var full = new int[64];
                ScalarMpeg4Dsp.rows(coefficients.clone(), 0, full);
                ScalarMpeg4Dsp.columns(full);
                var shortcut = new int[64];
                ScalarMpeg4Dsp.transform(coefficients.clone(), 0, shortcut);
                assertArrayEquals(full, shortcut, "round " + round);
                var expected = new byte[STRIDE * 10];
                random.nextBytes(expected);
                var actual = expected.clone();
                var reference = expected.clone();
                var add = round % 2 == 0;
                if (add) {
                    for (var row = 0; row < 8; row++) {
                        for (var column = 0; column < 8; column++) {
                            var at = STRIDE + 3 + row * STRIDE + column;
                            reference[at] = (byte) Math.max(0, Math.min(255, (reference[at] & 0xFF) + full[row * 8 + column]));
                        }
                    }
                    VECTOR.idctAdd(coefficients.clone(), 0, actual, STRIDE + 3, STRIDE, new int[64]);
                } else {
                    for (var row = 0; row < 8; row++) {
                        for (var column = 0; column < 8; column++) {
                            reference[STRIDE + 3 + row * STRIDE + column] = (byte) Math.max(0, Math.min(255, full[row * 8 + column]));
                        }
                    }
                    VECTOR.idctPut(coefficients.clone(), 0, actual, STRIDE + 3, STRIDE, new int[64]);
                }
                assertArrayEquals(reference, actual, "vector round " + round);
            }
        }

        @ParameterizedTest(name = "seed {0}")
        @ValueSource(ints = {1, 2, 3})
        @DisplayName("half sample interpolation and averaging equal scalar for every size and mode")
        void motion(int seed) {
            var random = new Random(seed);
            var src = new byte[STRIDE * 40];
            random.nextBytes(src);
            for (var width : new int[]{4, 8, 16}) {
                for (var mode = 0; mode < 8; mode++) {
                    var expected = new byte[STRIDE * 20];
                    random.nextBytes(expected);
                    var actual = expected.clone();
                    var offset = 2 * STRIDE + random.nextInt(16);
                    SCALAR.halfPel(src, offset, STRIDE, expected, STRIDE + 2, STRIDE, width, width, mode & 1,
                            (mode >> 1) & 1, mode >> 2);
                    VECTOR.halfPel(src, offset, STRIDE, actual, STRIDE + 2, STRIDE, width, width, mode & 1,
                            (mode >> 1) & 1, mode >> 2);
                    assertArrayEquals(expected, actual, "half sample " + width + " mode " + mode);
                    SCALAR.average(src, offset + 5, STRIDE, expected, 3, STRIDE, width, width);
                    VECTOR.average(src, offset + 5, STRIDE, actual, 3, STRIDE, width, width);
                    assertArrayEquals(expected, actual, "average " + width);
                }
            }
        }
    }

    @Nested
    @DisplayName("quarter sample interpolation")
    class QuarterSample {
        @ParameterizedTest(name = "seed {0}")
        @ValueSource(ints = {1, 2, 3, 4})
        @DisplayName("equals the reference for every fraction, block shape and rounding")
        void matchesReference(int seed) {
            var random = new Random(seed);
            var src = new byte[STRIDE * 40];
            if (seed % 2 == 0) {
                random.nextBytes(src);
            } else {
                for (var i = 0; i < src.length; i++) {
                    src[i] = (byte) (128 + 120 * Math.sin(i * 0.37 + seed));
                }
            }
            int[][] shapes = {{16, 16}, {8, 8}, {16, 8}};
            for (var shape : shapes) {
                for (var fraction = 0; fraction < 32; fraction++) {
                    var expected = new byte[STRIDE * 20];
                    random.nextBytes(expected);
                    var scalar = expected.clone();
                    var vector = expected.clone();
                    var offset = 3 * STRIDE + random.nextInt(16);
                    var quarterX = fraction & 3;
                    var quarterY = (fraction >> 2) & 3;
                    var rounding = fraction >> 4;
                    ReferenceQuarterPel.quarterPel(src, offset, STRIDE, expected, STRIDE + 1, STRIDE, shape[0], shape[1],
                            quarterX, quarterY, rounding, new int[Mpeg4Dsp.SCRATCH_INTS]);
                    SCALAR.quarterPel(src, offset, STRIDE, scalar, STRIDE + 1, STRIDE, shape[0], shape[1], quarterX,
                            quarterY, rounding, new int[Mpeg4Dsp.SCRATCH_INTS]);
                    VECTOR.quarterPel(src, offset, STRIDE, vector, STRIDE + 1, STRIDE, shape[0], shape[1], quarterX,
                            quarterY, rounding, new int[Mpeg4Dsp.SCRATCH_INTS]);
                    var where = shape[0] + "x" + shape[1] + " fraction " + fraction;
                    assertArrayEquals(expected, scalar, "scalar " + where);
                    assertArrayEquals(expected, vector, "vector " + where);
                }
            }
        }
    }

    @Nested
    @DisplayName("IEEE 1180")
    class Ieee1180 {
        @ParameterizedTest(name = "L={0} H={1} sign={2}")
        @CsvSource({"256,255,1", "5,5,1", "300,300,1", "256,255,-1", "5,5,-1", "300,300,-1"})
        @DisplayName("meets the accuracy bounds of the integer inverse DCT")
        void accuracy(int low, int high, int sign) {
            var generator = new Ieee1180Random();
            var cosine = new double[8][8];
            for (var u = 0; u < 8; u++) {
                for (var x = 0; x < 8; x++) {
                    cosine[u][x] = (u == 0 ? Math.sqrt(0.125) : 0.5) * Math.cos((2 * x + 1) * u * Math.PI / 16);
                }
            }
            var squared = new double[64];
            var errors = new double[64];
            var peak = 0;
            var blocks = 10_000;
            var work = new int[64];
            for (var n = 0; n < blocks; n++) {
                var pixels = new double[64];
                for (var i = 0; i < 64; i++) {
                    pixels[i] = sign * generator.next(low, high);
                }
                var coefficients = new short[64];
                var reference = new double[64];
                for (var v = 0; v < 8; v++) {
                    for (var u = 0; u < 8; u++) {
                        var sum = 0.0;
                        for (var y = 0; y < 8; y++) {
                            for (var x = 0; x < 8; x++) {
                                sum += cosine[v][y] * cosine[u][x] * pixels[y * 8 + x];
                            }
                        }
                        coefficients[u * 8 + v] = (short) Math.max(-2048, Math.min(2047, Math.round(sum)));
                    }
                }
                for (var y = 0; y < 8; y++) {
                    for (var x = 0; x < 8; x++) {
                        var sum = 0.0;
                        for (var v = 0; v < 8; v++) {
                            for (var u = 0; u < 8; u++) {
                                sum += cosine[v][y] * cosine[u][x] * coefficients[u * 8 + v];
                            }
                        }
                        reference[y * 8 + x] = Math.max(-256, Math.min(255, Math.round(sum)));
                    }
                }
                ScalarMpeg4Dsp.transform(coefficients, 0, work);
                for (var i = 0; i < 64; i++) {
                    var value = Math.max(-256, Math.min(255, work[i]));
                    var error = value - reference[i];
                    peak = Math.max(peak, (int) Math.abs(error));
                    errors[i] += error;
                    squared[i] += error * error;
                }
            }
            var worstSquared = 0.0;
            var worstMean = 0.0;
            var totalSquared = 0.0;
            var totalMean = 0.0;
            for (var i = 0; i < 64; i++) {
                worstSquared = Math.max(worstSquared, squared[i] / blocks);
                worstMean = Math.max(worstMean, Math.abs(errors[i]) / blocks);
                totalSquared += squared[i];
                totalMean += errors[i];
            }
            totalSquared /= 64.0 * blocks;
            totalMean = Math.abs(totalMean) / (64.0 * blocks);
            assertTrue(peak <= 1, "peak error " + peak);
            assertTrue(worstSquared <= 0.06, "peak mean square error " + worstSquared);
            assertTrue(totalSquared <= 0.02, "overall mean square error " + totalSquared);
            assertTrue(worstMean <= 0.015, "peak mean error " + worstMean);
            assertTrue(totalMean <= 0.0015, "overall mean error " + totalMean);
        }

        @Test
        @DisplayName("maps an all-zero block to zero")
        void zero() {
            var work = new int[64];
            ScalarMpeg4Dsp.transform(new short[64], 0, work);
            assertArrayEquals(new int[64], work);
        }
    }

    /**
     * The pseudo-random generator of IEEE 1180-1990.
     */
    private static final class Ieee1180Random {
        private long seed = 1;

        int next(int low, int high) {
            seed = (seed * 1103515245L + 12345L) & 0xFFFFFFFFL;
            var x = (double) (seed & 0x7FFFFFFFL) / 0x7FFFFFFFL;
            return (int) Math.floor(x * (low + high + 1)) - low;
        }
    }
}
