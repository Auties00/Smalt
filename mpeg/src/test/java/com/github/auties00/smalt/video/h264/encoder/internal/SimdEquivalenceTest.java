package com.github.auties00.smalt.video.h264.encoder.internal;

import com.github.auties00.smalt.internal.simd.Simd;
import com.github.auties00.smalt.video.h264.internal.H264Tables;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.RepetitionInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Checks every Vector API kernel against its scalar twin on randomized input, including extreme
 * sample values that stress intermediate ranges.
 */
class SimdEquivalenceTest {
    private static final Random RANDOM = new Random(0x264);

    @BeforeAll
    static void requireVectors() {
        assumeTrue(Simd.ENABLED, "the Vector API is not enabled");
    }

    private static byte[] samples(int length, int mode) {
        var array = new byte[length];
        for (var i = 0; i < length; i++) {
            array[i] = switch (mode) {
                case 0 -> (byte) RANDOM.nextInt(256);
                case 1 -> (byte) (RANDOM.nextBoolean() ? 0 : 255);
                default -> (byte) (128 + RANDOM.nextInt(9) - 4);
            };
        }
        return array;
    }

    @Nested
    @DisplayName("pixel kernels")
    class Pixels {
        private final PixelKernels scalar = new PixelKernels();
        private final PixelKernels vector = VectorKernelsHolder.pixels();

        @ParameterizedTest(name = "{0}x{1}")
        @CsvSource({"16,16", "16,8", "8,16", "8,8", "8,4", "4,8", "4,4", "16,4"})
        @DisplayName("SAD, SSD, SATD and averages match the scalar kernels")
        void metrics(int width, int height) {
            for (var trial = 0; trial < 200; trial++) {
                var mode = trial % 3;
                var a = samples(64 * 40, mode);
                var b = samples(64 * 40, trial % 2 == 0 ? mode : 0);
                var aOffset = RANDOM.nextInt(64);
                var bOffset = RANDOM.nextInt(64);
                var aStride = 40 + RANDOM.nextInt(20);
                var bStride = 40 + RANDOM.nextInt(20);
                assertEquals(scalar.sad(a, aOffset, aStride, b, bOffset, bStride, width, height),
                        vector.sad(a, aOffset, aStride, b, bOffset, bStride, width, height));
                assertEquals(scalar.ssd(a, aOffset, aStride, b, bOffset, bStride, width, height),
                        vector.ssd(a, aOffset, aStride, b, bOffset, bStride, width, height));
                assertEquals(scalar.satd(a, aOffset, aStride, b, bOffset, bStride, width, height),
                        vector.satd(a, aOffset, aStride, b, bOffset, bStride, width, height));
                assertEquals(scalar.sumAndSquares(a, aOffset, aStride, width, height),
                        vector.sumAndSquares(a, aOffset, aStride, width, height));
                var expected = new byte[16 * 16];
                var actual = new byte[16 * 16];
                scalar.average(a, aOffset, aStride, b, bOffset, bStride, expected, 0, 16, width, height);
                vector.average(a, aOffset, aStride, b, bOffset, bStride, actual, 0, 16, width, height);
                assertArrayEquals(expected, actual);
            }
        }

        @ParameterizedTest(name = "{0}x{1}")
        @CsvSource({"16,1", "16,3", "16,15", "8,1", "8,5", "8,7"})
        @DisplayName("averages of odd row counts match the scalar kernel")
        void oddAverages(int width, int height) {
            for (var trial = 0; trial < 50; trial++) {
                var a = samples(64 * 40, trial % 3);
                var b = samples(64 * 40, RANDOM.nextInt(3));
                var aOffset = RANDOM.nextInt(64);
                var bOffset = RANDOM.nextInt(64);
                var expected = new byte[16 * 16];
                var actual = new byte[16 * 16];
                scalar.average(a, aOffset, 40, b, bOffset, 41, expected, 0, 16, width, height);
                vector.average(a, aOffset, 40, b, bOffset, 41, actual, 0, 16, width, height);
                assertArrayEquals(expected, actual);
            }
        }

        @RepeatedTest(200)
        @DisplayName("fused DC, vertical and horizontal intra SATD matches the scalar kernel")
        void intraSatd(RepetitionInfo repetition) {
            var src = samples(40 * 24, repetition.getCurrentRepetition() % 3);
            var offset = 2 * 40 + 3;
            var dc = RANDOM.nextInt(256);
            var top = RANDOM.nextBoolean();
            var left = RANDOM.nextBoolean();
            assertEquals(scalar.intraSatd8x8(src, offset, 40, dc, top, left),
                    vector.intraSatd8x8(src, offset, 40, dc, top, left));
        }

        @ParameterizedTest(name = "width {0}")
        @CsvSource({"8", "16", "40", "13"})
        @DisplayName("two-to-one downscaling matches the scalar kernel")
        void downscale(int width) {
            var src = samples(96 * 12, RANDOM.nextInt(3));
            var expected = new byte[64 * 6];
            var actual = new byte[64 * 6];
            scalar.downscale(src, 5, 96, expected, 2, 64, width, 5);
            vector.downscale(src, 5, 96, actual, 2, 64, width, 5);
            assertArrayEquals(expected, actual);
        }

        @RepeatedTest(200)
        @DisplayName("Hadamard coefficients give the SATD of any 4x4 prediction by linearity")
        void hadamardLinearity(RepetitionInfo repetition) {
            var src = samples(16, repetition.getCurrentRepetition() % 3);
            var pred = samples(16, RANDOM.nextInt(3));
            var hs = new int[16];
            var hp = new int[16];
            PixelKernels.hadamard4x4(src, 0, 4, hs);
            PixelKernels.hadamard4x4(pred, 0, 4, hp);
            var sum = 0;
            for (var i = 0; i < 16; i++) {
                sum += Math.abs(hs[i] - hp[i]);
            }
            assertEquals(PixelKernels.satd4x4(src, 0, 4, pred, 0, 4), sum);
            assertEquals(scalar.satd(src, 0, 4, pred, 0, 4, 4, 4), sum >> 1);
        }

        @RepeatedTest(200)
        @DisplayName("packed quadrant SATDs match the scalar kernels and the block metric")
        void quadrants(RepetitionInfo repetition) {
            var mode = repetition.getCurrentRepetition() % 3;
            var a = samples(64 * 40, mode);
            var b = samples(64 * 40, RANDOM.nextInt(3));
            var aOffset = RANDOM.nextInt(64);
            var bOffset = RANDOM.nextInt(64);
            var stride = 40 + RANDOM.nextInt(20);
            var scratch = new short[PixelKernels.SATD_SCRATCH];
            var satd = vector.satdQuadrants(a, aOffset, 40, b, bOffset, stride, scratch);
            assertEquals(scalar.satdQuadrants(a, aOffset, 40, b, bOffset, stride, scratch), satd);
            var satdSum = 0L;
            for (var quadrant = 0; quadrant < 4; quadrant++) {
                satdSum += (satd >>> (16 * quadrant)) & 0xFFFF;
            }
            assertEquals(scalar.satd(a, aOffset, 40, b, bOffset, stride, 16, 16), satdSum >> 1);
        }

        @RepeatedTest(300)
        @DisplayName("SATDs against the average of two predictions match averaging then measuring")
        void averagedSatd(RepetitionInfo repetition) {
            var mode = repetition.getCurrentRepetition() % 3;
            var src = samples(64 * 40, mode);
            var a = samples(64 * 40, RANDOM.nextInt(3));
            var b = samples(64 * 40, mode == 1 ? 1 : RANDOM.nextInt(3));
            var srcOffset = RANDOM.nextInt(64);
            var aOffset = RANDOM.nextInt(64);
            var bOffset = RANDOM.nextInt(64);
            var srcStride = 40 + RANDOM.nextInt(20);
            var stride = 40 + RANDOM.nextInt(20);
            var average = new byte[16 * 16];
            scalar.average(a, aOffset, stride, b, bOffset, stride, average, 0, 16, 16, 16);
            var scratch = new short[PixelKernels.SATD_SCRATCH];
            var expected = scalar.satdQuadrants(src, srcOffset, srcStride, average, 0, 16, scratch);
            assertEquals(expected, scalar.satdAverageQuadrants(src, srcOffset, srcStride, a, aOffset, b, bOffset, stride,
                    scratch));
            assertEquals(expected, vector.satdAverageQuadrants(src, srcOffset, srcStride, a, aOffset, b, bOffset, stride,
                    scratch));
            for (var height = 8; height <= 16; height += 8) {
                var plain = scalar.satd(src, srcOffset, srcStride, average, 0, 16, 16, height);
                assertEquals(plain, scalar.satdAverage16(src, srcOffset, srcStride, a, aOffset, b, bOffset, stride,
                        height, scratch));
                assertEquals(plain, vector.satdAverage16(src, srcOffset, srcStride, a, aOffset, b, bOffset, stride,
                        height, scratch));
            }
        }

        @Test
        @DisplayName("quadrant SATDs of the largest differences fill their 16-bit fields exactly")
        void extremeQuadrants() {
            var white = new byte[64 * 40];
            Arrays.fill(white, (byte) 255);
            var checker = new byte[64 * 40];
            for (var i = 0; i < checker.length; i++) {
                checker[i] = (byte) (((i % 40) + (i / 40)) % 2 == 0 ? 0 : 255);
            }
            var black = new byte[64 * 40];
            var scratch = new short[PixelKernels.SATD_SCRATCH];
            for (var pair : new byte[][][]{{white, black}, {black, white}, {checker, black}, {white, checker}}) {
                assertEquals(scalar.satdQuadrants(pair[0], 1, 40, pair[1], 2, 40, scratch),
                        vector.satdQuadrants(pair[0], 1, 40, pair[1], 2, 40, scratch));
                assertEquals(scalar.satdAverageQuadrants(pair[0], 1, 40, pair[1], 2, pair[1], 3, 40, scratch),
                        vector.satdAverageQuadrants(pair[0], 1, 40, pair[1], 2, pair[1], 3, 40, scratch));
                assertEquals(scalar.satdAverage16(pair[0], 1, 40, pair[1], 2, pair[1], 3, 40, 16, scratch),
                        vector.satdAverage16(pair[0], 1, 40, pair[1], 2, pair[1], 3, 40, 16, scratch));
            }
        }

        @ParameterizedTest(name = "{0}x{0}")
        @CsvSource({"16", "8"})
        @DisplayName("plane prediction matches the scalar kernel over the whole parameter range")
        void planePrediction(int size) {
            var half = size / 2;
            var scale = size == 16 ? 5 : 34;
            var center = half - 1;
            for (var trial = 0; trial < 500; trial++) {
                // Neighbors as the standard reads them: top[0] and left[0] are the corner sample.
                var top = samples(size + 1, trial % 3);
                var left = samples(size + 1, (trial / 3) % 3);
                left[0] = top[0];
                var h = 0;
                var v = 0;
                for (var i = 0; i < half; i++) {
                    h += (i + 1) * ((top[half + 1 + i] & 0xFF) - (top[half - 1 - i] & 0xFF));
                    v += (i + 1) * ((left[half + 1 + i] & 0xFF) - (left[half - 1 - i] & 0xFF));
                }
                var a = 16 * ((left[size] & 0xFF) + (top[size] & 0xFF));
                var b = (scale * h + 32) >> 6;
                var c = (scale * v + 32) >> 6;
                var stride = size + RANDOM.nextInt(8);
                var expected = new byte[stride * size + 4];
                var actual = expected.clone();
                scalar.planePrediction(expected, 3, stride, size, a, b, c, center);
                vector.planePrediction(actual, 3, stride, size, a, b, c, center);
                assertArrayEquals(expected, actual, "a=" + a + " b=" + b + " c=" + c);
            }
        }
    }

    @Nested
    @DisplayName("interpolation kernels")
    class Interpolation {
        private final InterpolationKernels scalar = new InterpolationKernels();
        private final InterpolationKernels vector = VectorKernelsHolder.interpolation();

        @RepeatedTest(50)
        @DisplayName("half-sample planes match the scalar kernels")
        void halfPel() {
            var stride = 96;
            var full = samples(stride * 12, RANDOM.nextInt(3));
            var count = 1 + RANDOM.nextInt(stride - 12);
            var start = 5 * stride + 4;
            var h1 = new byte[full.length];
            var v1 = new byte[full.length];
            var c1 = new byte[full.length];
            var h2 = new byte[full.length];
            var v2 = new byte[full.length];
            var c2 = new byte[full.length];
            scalar.halfPelRow(full, start, count, stride, h1, v1, c1, new short[stride + 8]);
            vector.halfPelRow(full, start, count, stride, h2, v2, c2, new short[stride + 8]);
            assertArrayEquals(h1, h2);
            assertArrayEquals(v1, v2);
            assertArrayEquals(c1, c2);
        }

        // "+" columns filter vertically to the largest intermediate (10710), "-" columns to the
        // smallest (-2550); the center filter over "+-++-+" and "-+--+-" overflows 16 bits before
        // clipping, the saturating path of the vector kernel.
        @ParameterizedTest(name = "{0}")
        @CsvSource({"+-++-+", "-+--+-", "++-+-+", "+-+-+-"})
        @DisplayName("half-sample planes match the scalar kernels on extreme intermediates")
        void halfPelExtremes(String pattern) {
            var stride = 96;
            var full = new byte[stride * 12];
            var high = new int[]{255, 0, 255, 255, 0, 255};
            var low = new int[]{0, 255, 0, 0, 255, 0};
            for (var x = 0; x < stride; x++) {
                var column = pattern.charAt(x % pattern.length()) == '+' ? high : low;
                for (var y = 0; y < 12; y++) {
                    full[y * stride + x] = (byte) column[(y + 3) % 6];
                }
            }
            for (var shift = 0; shift < 6; shift++) {
                var start = 5 * stride + 4 + shift;
                var h1 = new byte[full.length];
                var v1 = new byte[full.length];
                var c1 = new byte[full.length];
                var h2 = new byte[full.length];
                var v2 = new byte[full.length];
                var c2 = new byte[full.length];
                scalar.halfPelRow(full, start, 64, stride, h1, v1, c1, new short[stride + 8]);
                vector.halfPelRow(full, start, 64, stride, h2, v2, c2, new short[stride + 8]);
                assertArrayEquals(h1, h2);
                assertArrayEquals(v1, v2);
                assertArrayEquals(c1, c2);
            }
        }
    }

    @Nested
    @DisplayName("transform kernels")
    class Transform {
        private final TransformKernels scalar = new TransformKernels();
        private final TransformKernels vector = VectorKernelsHolder.transform();

        @RepeatedTest(100)
        @DisplayName("macroblock forward transform and quantization match the scalar kernels")
        void forwardQuantizeLuma() {
            var mode = RANDOM.nextInt(3);
            var src = samples(40 * 24, mode);
            var pred = samples(40 * 24, RANDOM.nextInt(3));
            var qp = RANDOM.nextInt(52);
            var bias = ResidualCoder.bias(15 + qp / 6, RANDOM.nextBoolean());
            var limit = RANDOM.nextBoolean() ? 2063 : 16383;
            var expectedLevels = new int[256];
            var actualLevels = new int[256];
            var expectedCounts = new int[16];
            var actualCounts = new int[16];
            scalar.forwardQuantizeLuma(src, 3, 40, pred, 5, 40, qp, bias, limit, expectedLevels, expectedCounts);
            vector.forwardQuantizeLuma(src, 3, 40, pred, 5, 40, qp, bias, limit, actualLevels, actualCounts);
            assertArrayEquals(expectedLevels, actualLevels, "qp " + qp);
            assertArrayEquals(expectedCounts, actualCounts, "qp " + qp);
        }

        @RepeatedTest(200)
        @DisplayName("level masks match the scalar kernel and the levels of the full quantization")
        void quantizeLumaMasks(RepetitionInfo repetition) {
            var src = samples(40 * 24, RANDOM.nextInt(3));
            var pred = samples(40 * 24, RANDOM.nextInt(3));
            var qp = repetition.getCurrentRepetition() < 52 ? repetition.getCurrentRepetition() : RANDOM.nextInt(52);
            var expected = new int[16];
            var actual = new int[16];
            var expectedSigns = new int[16];
            var actualSigns = new int[16];
            var expectedComplete = scalar.quantizeLumaMasks(src, 3, 40, pred, 5, 40, qp, expected, expectedSigns);
            assertEquals(expectedComplete, vector.quantizeLumaMasks(src, 3, 40, pred, 5, 40, qp, actual, actualSigns),
                    "qp " + qp);
            var levels = new int[256];
            var counts = new int[16];
            scalar.forwardQuantizeLuma(src, 3, 40, pred, 5, 40, qp, ResidualCoder.bias(15 + qp / 6, false), 2063,
                    levels, counts);
            var bands = 0;
            var stopped = false;
            while (bands < 4 && !stopped) {
                for (var column = 0; column < 4; column++) {
                    stopped |= (expected[H264Tables.BLOCK_RASTER[bands * 4 + column]] >>> 16) != 0;
                }
                bands++;
            }
            assertEquals(!stopped, expectedComplete, "qp " + qp);
            for (var blk = 0; blk < 16; blk++) {
                if (H264Tables.BLOCK_Y[blk] >= bands) {
                    continue;
                }
                assertEquals(expected[blk], actual[blk], "qp " + qp + " block " + blk);
                assertEquals(expectedSigns[blk], actualSigns[blk], "qp " + qp + " block " + blk);
                var nonZero = 0;
                var big = 0;
                var negative = 0;
                for (var r = 0; r < 16; r++) {
                    var level = levels[blk * 16 + r];
                    nonZero |= (level != 0 ? 1 : 0) << r;
                    big |= (Math.abs(level) > 1 ? 1 : 0) << r;
                    negative |= (level < 0 ? 1 : 0) << r;
                }
                assertEquals(nonZero | (big << 16), expected[blk], "qp " + qp + " block " + blk);
                assertEquals(negative, expectedSigns[blk], "qp " + qp + " block " + blk);
            }
        }

        @RepeatedTest(200)
        @DisplayName("chroma AC masks and DC coefficients match the scalar kernel and the full quantization")
        void quantizeChromaMasks(RepetitionInfo repetition) {
            var srcU = samples(40 * 24, RANDOM.nextInt(3));
            var srcV = samples(40 * 24, RANDOM.nextInt(3));
            var predU = samples(40 * 24, RANDOM.nextInt(3));
            var predV = samples(40 * 24, RANDOM.nextInt(3));
            var qp = repetition.getCurrentRepetition() < 52 ? repetition.getCurrentRepetition() : RANDOM.nextInt(52);
            var expected = new int[8];
            var actual = new int[8];
            var expectedDc = new int[8];
            var actualDc = new int[8];
            var complete = scalar.quantizeChromaMasks(srcU, srcV, 3, 40, predU, predV, 5, 40, qp, expected, expectedDc);
            assertEquals(complete, vector.quantizeChromaMasks(srcU, srcV, 3, 40, predU, predV, 5, 40, qp, actual,
                    actualDc), "qp " + qp);
            var levels = new int[128];
            var counts = new int[8];
            var dc = new int[8];
            scalar.forwardQuantizeChroma(srcU, srcV, 3, 40, predU, predV, 5, 40, qp,
                    ResidualCoder.bias(15 + qp / 6, false), 2063, levels, counts, dc);
            var stopped = false;
            for (var band = 0; band < 4 && !stopped; band++) {
                for (var column = 0; column < 2; column++) {
                    var index = (band >> 1) * 4 + (band & 1) * 2 + column;
                    assertEquals(expected[index], actual[index], "qp " + qp + " block " + index);
                    assertEquals(expectedDc[index], actualDc[index], "qp " + qp + " block " + index);
                    assertEquals(dc[index], expectedDc[index], "qp " + qp + " block " + index);
                    var nonZero = 0;
                    var big = 0;
                    for (var r = 1; r < 16; r++) {
                        var level = Math.abs(levels[index * 16 + r]);
                        nonZero |= (level != 0 ? 1 : 0) << r;
                        big |= (level > 1 ? 1 : 0) << r;
                    }
                    assertEquals(nonZero | (big << 16), expected[index], "qp " + qp + " block " + index);
                    stopped |= big != 0;
                }
            }
            assertEquals(!stopped, complete, "qp " + qp);
        }

        @RepeatedTest(100)
        @DisplayName("macroblock forward transform with unquantized DC matches the scalar kernels")
        void forwardQuantizeLumaAc() {
            var src = samples(40 * 24, RANDOM.nextInt(3));
            var pred = samples(40 * 24, RANDOM.nextInt(3));
            var qp = RANDOM.nextInt(52);
            var bias = ResidualCoder.bias(15 + qp / 6, RANDOM.nextBoolean());
            var limit = RANDOM.nextBoolean() ? 2063 : 16383;
            var expectedLevels = new int[256];
            var actualLevels = new int[256];
            var expectedCounts = new int[16];
            var actualCounts = new int[16];
            var expectedDc = new int[16];
            var actualDc = new int[16];
            scalar.forwardQuantizeLumaAc(src, 3, 40, pred, 5, 40, qp, bias, limit, expectedLevels, expectedCounts,
                    expectedDc);
            vector.forwardQuantizeLumaAc(src, 3, 40, pred, 5, 40, qp, bias, limit, actualLevels, actualCounts,
                    actualDc);
            assertArrayEquals(expectedLevels, actualLevels, "qp " + qp);
            assertArrayEquals(expectedCounts, actualCounts, "qp " + qp);
            assertArrayEquals(expectedDc, actualDc, "qp " + qp);
        }

        @RepeatedTest(100)
        @DisplayName("chroma forward transform and AC quantization match the scalar kernels")
        void forwardQuantizeChroma() {
            var srcU = samples(24 * 12, RANDOM.nextInt(3));
            var srcV = samples(24 * 12, RANDOM.nextInt(3));
            var predU = samples(24 * 12, RANDOM.nextInt(3));
            var predV = samples(24 * 12, RANDOM.nextInt(3));
            var qp = RANDOM.nextInt(52);
            var bias = ResidualCoder.bias(15 + qp / 6, RANDOM.nextBoolean());
            var limit = RANDOM.nextBoolean() ? 2063 : 16383;
            var expectedLevels = new int[128];
            var actualLevels = new int[128];
            var expectedCounts = new int[8];
            var actualCounts = new int[8];
            var expectedDc = new int[8];
            var actualDc = new int[8];
            scalar.forwardQuantizeChroma(srcU, srcV, 2, 24, predU, predV, 7, 24, qp, bias, limit, expectedLevels,
                    expectedCounts, expectedDc);
            vector.forwardQuantizeChroma(srcU, srcV, 2, 24, predU, predV, 7, 24, qp, bias, limit, actualLevels,
                    actualCounts, actualDc);
            assertArrayEquals(expectedLevels, actualLevels, "qp " + qp);
            assertArrayEquals(expectedCounts, actualCounts, "qp " + qp);
            assertArrayEquals(expectedDc, actualDc, "qp " + qp);
        }

        @RepeatedTest(50)
        @DisplayName("dequantization matches the scalar kernels")
        void dequantize() {
            var levels = ints(16, 300);
            var qp = RANDOM.nextInt(52);
            var start = RANDOM.nextInt(2);
            var expected = new int[16];
            var actual = new int[16];
            scalar.dequantize4x4(levels, 0, start, qp, expected, 0);
            vector.dequantize4x4(levels, 0, start, qp, actual, 0);
            assertArrayEquals(expected, actual);
        }

        @Test
        @DisplayName("fused kernels equal the separate transform, quantization and reconstruction steps")
        void fused() {
            for (var trial = 0; trial < 500; trial++) {
                var src = samples(16 * 8, trial % 3);
                var pred = samples(16 * 8, (trial / 3) % 3);
                var qp = trial % 52;
                var shift = 15 + qp / 6;
                var bias = ResidualCoder.bias(shift, trial % 2 == 0);
                var coef = new int[16];
                var expected = new int[16];
                var actual = new int[16];
                scalar.forward4x4(src, 1, 16, pred, 2, 16, coef, 0);
                var expectedCount = scalar.quantize4x4(coef, 0, Tables.QUANT4[qp % 6], bias, shift, 2063, 0, expected, 0);
                var actualCount = scalar.forwardQuantize4x4(src, 1, 16, pred, 2, 16, Tables.QUANT4[qp % 6], bias, shift,
                        2063, actual, 0);
                assertArrayEquals(expected, actual);
                assertEquals(expectedCount, actualCount);
                var dc = new int[1];
                var acLevels = new int[16];
                var acCount = scalar.forwardQuantizeAc4x4(src, 1, 16, pred, 2, 16, Tables.QUANT4[qp % 6], bias, shift,
                        2063, acLevels, 0, dc, 0);
                assertEquals(coef[0], dc[0]);
                var acExpected = copy(expected);
                scalar.quantize4x4(coef, 0, Tables.QUANT4[qp % 6], bias, shift, 2063, 1, acExpected, 0);
                acExpected[0] = 0;
                assertArrayEquals(acExpected, acLevels);
                assertEquals(expectedCount - (expected[0] != 0 ? 1 : 0), acCount);
                var reference = Arrays.copyOf(pred, pred.length);
                var fused = Arrays.copyOf(pred, pred.length);
                var dequantized = new int[16];
                scalar.dequantize4x4(expected, 0, 0, qp, dequantized, 0);
                scalar.inverse4x4Add(dequantized, 0, reference, 2, 16);
                scalar.dequantizeInverseAdd4x4(expected, 0, 0, 0, qp, fused, 2, 16);
                assertArrayEquals(reference, fused);
            }
        }
    }

    static int[] ints(int length, int bound) {
        var array = new int[length];
        for (var i = 0; i < length; i++) {
            array[i] = RANDOM.nextInt(2 * bound + 1) - bound;
        }
        return array;
    }

    static int[] copy(int[] array) {
        return Arrays.copyOf(array, array.length);
    }
}
