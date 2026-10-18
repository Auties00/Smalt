package com.github.auties00.smalt.video.hevc.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Checks the Vector API kernels against their scalar twins, and the scalar interpolation against a
 * direct evaluation of the specification's equations with edge clamping, on random planes, every
 * prediction block size, every fractional position and every supported bit depth.
 */
@DisplayName("HEVC DSP kernels")
class HevcDspTest {
    private static final HevcDsp SCALAR = new ScalarHevcDsp();
    private static final HevcDsp VECTOR = VectorHevcDsp.create();
    private static final int STRIDE = 96;
    private static final int ROWS = 96;
    private static final int[] LUMA_SIZES = {4, 8, 12, 16, 24, 32, 48, 64};
    private static final int[] CHROMA_SIZES = {2, 4, 6, 8, 12, 16, 24, 32};
    private static final int[] DEPTHS = {8, 10, 12};

    private static short[] plane(Random random, int bitDepth) {
        var plane = new short[STRIDE * ROWS];
        var max = (1 << bitDepth) - 1;
        for (var i = 0; i < plane.length; i++) {
            // Mix noise with runs of extremes so the filters hit their largest sums.
            var pick = random.nextInt(8);
            plane[i] = (short) (pick == 0 ? 0 : pick == 1 ? max : random.nextInt(max + 1));
        }
        return plane;
    }

    // ITU-T H.265 equations 8-228 to 8-231 evaluated directly from the clamped reference samples,
    // with the range extension shifts that only differ above 12 bits.
    private static int[] referenceLuma(short[] src, int x0, int y0, int w, int h, int xFrac, int yFrac, int bitDepth) {
        var shift1 = Math.min(4, bitDepth - 8);
        var out = new int[w * h];
        for (var y = 0; y < h; y++) {
            for (var x = 0; x < w; x++) {
                int value;
                if (xFrac == 0 && yFrac == 0) {
                    value = (src[(y0 + y) * STRIDE + x0 + x] & 0xFFFF) << Math.max(2, 14 - bitDepth);
                } else if (yFrac == 0) {
                    value = tap8(src, (y0 + y) * STRIDE + x0 + x - 3, 1, xFrac) >> shift1;
                } else if (xFrac == 0) {
                    value = tap8(src, (y0 + y - 3) * STRIDE + x0 + x, STRIDE, yFrac) >> shift1;
                } else {
                    var sum = 0;
                    for (var k = 0; k < 8; k++) {
                        var row = tap8(src, (y0 + y - 3 + k) * STRIDE + x0 + x - 3, 1, xFrac) >> shift1;
                        sum += HevcDsp.LUMA_FILTER[yFrac * 8 + k] * row;
                    }
                    value = sum >> 6;
                }
                out[y * w + x] = value;
            }
        }
        return out;
    }

    private static int tap8(short[] src, int origin, int step, int frac) {
        var sum = 0;
        for (var k = 0; k < 8; k++) {
            sum += HevcDsp.LUMA_FILTER[frac * 8 + k] * (src[origin + k * step] & 0xFFFF);
        }
        return sum;
    }

    // The 16-bit prediction followed by 1 when every value fit 16 bits and 0 otherwise.
    private static short[] lumaOf(HevcDsp dsp, short[] src, int x0, int y0, int w, int h, int xFrac, int yFrac,
                                  int bitDepth) {
        var out = new short[w * h + 1];
        var fits = dsp.lumaMc(src, y0 * STRIDE + x0, STRIDE, out, w, h, xFrac, yFrac, bitDepth,
                new short[HevcDsp.SCRATCH_SIZE]);
        out[w * h] = (short) (fits ? 1 : 0);
        return out;
    }

    private static short[] narrowed(int[] values) {
        var out = new short[values.length + 1];
        var fits = true;
        for (var i = 0; i < values.length; i++) {
            out[i] = (short) values[i];
            fits &= values[i] == out[i];
        }
        out[values.length] = (short) (fits ? 1 : 0);
        return out;
    }

    private static short[] chromaOf(HevcDsp dsp, short[] src, int x0, int y0, int w, int h, int xFrac, int yFrac,
                                    int bitDepth) {
        var out = new short[w * h + 1];
        var fits = dsp.chromaMc(src, y0 * STRIDE + x0, STRIDE, out, w, h, xFrac, yFrac, bitDepth,
                new short[HevcDsp.SCRATCH_SIZE]);
        out[w * h] = (short) (fits ? 1 : 0);
        return out;
    }

    @Nested
    @DisplayName("interpolation")
    class Interpolation {
        @ParameterizedTest(name = "{0}-bit")
        @ValueSource(ints = {8, 10, 12})
        @DisplayName("scalar luma interpolation follows the specification's equations in 16 bits")
        void lumaReference(int bitDepth) {
            var random = new Random(bitDepth);
            var src = plane(random, bitDepth);
            for (var size : LUMA_SIZES) {
                for (var fraction = 0; fraction < 16; fraction++) {
                    var x0 = 4 + random.nextInt(STRIDE - 72);
                    var y0 = 4 + random.nextInt(ROWS - 72);
                    assertArrayEquals(narrowed(referenceLuma(src, x0, y0, size, size, fraction & 3, fraction >> 2,
                                    bitDepth)), lumaOf(SCALAR, src, x0, y0, size, size, fraction & 3, fraction >> 2,
                                    bitDepth), size + "x" + size + " fraction " + fraction);
                }
            }
        }

        @ParameterizedTest(name = "{0}-bit")
        @ValueSource(ints = {8, 12, 14, 16})
        @DisplayName("32-bit luma interpolation follows the specification's equations")
        void wideLumaReference(int bitDepth) {
            var random = new Random(bitDepth);
            var src = plane(random, bitDepth);
            for (var size : LUMA_SIZES) {
                for (var fraction = 0; fraction < 16; fraction++) {
                    var x0 = 4 + random.nextInt(STRIDE - 72);
                    var y0 = 4 + random.nextInt(ROWS - 72);
                    var out = new int[size * size];
                    ScalarHevcDsp.wideLumaMc(src, y0 * STRIDE + x0, STRIDE, out, size, size, fraction & 3,
                            fraction >> 2, bitDepth, new int[HevcDsp.SCRATCH_SIZE]);
                    assertArrayEquals(referenceLuma(src, x0, y0, size, size, fraction & 3, fraction >> 2, bitDepth),
                            out, size + "x" + size + " fraction " + fraction);
                }
            }
        }

        @Test
        @DisplayName("a half-sample luma block whose values leave 16 bits is reported")
        void lumaOverflow() {
            // Rows whose horizontal sums are largest under the positive vertical taps and smallest
            // under the negative ones give the largest half-sample value, 33150 before narrowing.
            var src = new short[STRIDE * ROWS];
            for (var y = 0; y < ROWS; y++) {
                for (var x = 0; x < STRIDE; x++) {
                    var positiveX = x % 8 == 1 || x % 8 == 3 || x % 8 == 4 || x % 8 == 6;
                    var positiveY = y % 8 == 1 || y % 8 == 3 || y % 8 == 4 || y % 8 == 6;
                    src[y * STRIDE + x] = (short) (positiveX == positiveY ? 255 : 0);
                }
            }
            for (var dsp : new HevcDsp[]{SCALAR, VECTOR}) {
                var out = lumaOf(dsp, src, 8 + 3, 8 + 3, 8, 8, 2, 2, 8);
                assertEquals(0, out[64], "overflow reported");
            }
            assertArrayEquals(narrowed(referenceLuma(src, 11, 11, 8, 8, 2, 2, 8)), lumaOf(SCALAR, src, 11, 11, 8, 8, 2,
                    2, 8));
        }

        @ParameterizedTest(name = "seed {0}")
        @ValueSource(ints = {1, 2, 3, 4})
        @DisplayName("vector luma interpolation equals scalar for every fraction, size and bit depth")
        void luma(int seed) {
            var random = new Random(seed);
            for (var bitDepth : DEPTHS) {
                var src = plane(random, bitDepth);
                for (var w : LUMA_SIZES) {
                    for (var h : LUMA_SIZES) {
                        for (var fraction = 0; fraction < 16; fraction++) {
                            var x0 = 4 + random.nextInt(STRIDE - 72);
                            var y0 = 4 + random.nextInt(ROWS - 72);
                            assertArrayEquals(lumaOf(SCALAR, src, x0, y0, w, h, fraction & 3, fraction >> 2, bitDepth),
                                    lumaOf(VECTOR, src, x0, y0, w, h, fraction & 3, fraction >> 2, bitDepth),
                                    bitDepth + "-bit " + w + "x" + h + " fraction " + fraction);
                        }
                    }
                }
            }
        }

        @ParameterizedTest(name = "seed {0}")
        @ValueSource(ints = {1, 2, 3, 4})
        @DisplayName("vector chroma interpolation equals scalar for every fraction, size and bit depth")
        void chroma(int seed) {
            var random = new Random(seed);
            for (var bitDepth : DEPTHS) {
                var src = plane(random, bitDepth);
                for (var w : CHROMA_SIZES) {
                    for (var h : CHROMA_SIZES) {
                        for (var fraction = 0; fraction < 64; fraction++) {
                            var x0 = 2 + random.nextInt(STRIDE - 40);
                            var y0 = 2 + random.nextInt(ROWS - 40);
                            assertArrayEquals(chromaOf(SCALAR, src, x0, y0, w, h, fraction & 7, fraction >> 3, bitDepth),
                                    chromaOf(VECTOR, src, x0, y0, w, h, fraction & 7, fraction >> 3, bitDepth),
                                    bitDepth + "-bit " + w + "x" + h + " fraction " + fraction);
                        }
                    }
                }
            }
        }
    }

    @Nested
    @DisplayName("weighted prediction and output")
    class Output {
        @ParameterizedTest(name = "seed {0}")
        @ValueSource(ints = {1, 2, 3})
        @DisplayName("vector default and explicit weighted prediction equal scalar, including out-of-range sums")
        void put(int seed) {
            var random = new Random(seed);
            for (var bitDepth : DEPTHS) {
                for (var w : LUMA_SIZES) {
                    for (var h : CHROMA_SIZES) {
                        var p0 = new short[w * h];
                        var p1 = new short[w * h];
                        for (var i = 0; i < p0.length; i++) {
                            // The 16-bit intermediate range, extremes included.
                            var pick = random.nextInt(8);
                            p0[i] = (short) (pick == 0 ? 32767 : pick == 1 ? -16830 : random.nextInt(49598) - 16830);
                            pick = random.nextInt(8);
                            p1[i] = (short) (pick == 0 ? 32767 : pick == 1 ? -16830 : random.nextInt(49598) - 16830);
                        }
                        var expected = new short[STRIDE * 40];
                        var actual = new short[STRIDE * 40];
                        SCALAR.putUni(expected, STRIDE + 3, STRIDE, p0, w, h, bitDepth);
                        VECTOR.putUni(actual, STRIDE + 3, STRIDE, p0, w, h, bitDepth);
                        assertArrayEquals(expected, actual, "uni " + bitDepth + "-bit " + w + "x" + h);
                        SCALAR.putBi(expected, 2, STRIDE, p0, p1, w, h, bitDepth);
                        VECTOR.putBi(actual, 2, STRIDE, p0, p1, w, h, bitDepth);
                        assertArrayEquals(expected, actual, "bi " + bitDepth + "-bit " + w + "x" + h);
                        var weight0 = random.nextInt(256) - 128;
                        var weight1 = random.nextInt(256) - 128;
                        var offset = (random.nextInt(256) - 128) << (bitDepth - 8);
                        var log2Wd = 14 - bitDepth + random.nextInt(8);
                        SCALAR.putWeightedUni(expected, 5, STRIDE, p1, w, h, bitDepth, weight0, offset, log2Wd);
                        VECTOR.putWeightedUni(actual, 5, STRIDE, p1, w, h, bitDepth, weight0, offset, log2Wd);
                        assertArrayEquals(expected, actual, "weighted uni " + bitDepth + "-bit " + w + "x" + h);
                        SCALAR.putWeightedBi(expected, 7, STRIDE, p0, p1, w, h, bitDepth, weight0, weight1, 2 * offset,
                                log2Wd);
                        VECTOR.putWeightedBi(actual, 7, STRIDE, p0, p1, w, h, bitDepth, weight0, weight1, 2 * offset,
                                log2Wd);
                        assertArrayEquals(expected, actual, "weighted bi " + bitDepth + "-bit " + w + "x" + h);
                    }
                }
            }
        }

        @ParameterizedTest(name = "{0}-bit")
        @ValueSource(ints = {14, 16})
        @DisplayName("32-bit default weighted prediction above 12 bits uses the range extension shifts")
        void deepPut(int bitDepth) {
            var random = new Random(bitDepth);
            var max = (1 << bitDepth) - 1;
            var p0 = new int[16 * 4];
            var p1 = new int[16 * 4];
            for (var i = 0; i < p0.length; i++) {
                p0[i] = random.nextInt(1 << 18) - (1 << 15);
                p1[i] = random.nextInt(1 << 18) - (1 << 15);
            }
            var uni = new short[16 * 4];
            var bi = new short[16 * 4];
            ScalarHevcDsp.widePutUni(uni, 0, 16, p0, 16, 4, bitDepth);
            ScalarHevcDsp.widePutBi(bi, 0, 16, p0, p1, 16, 4, bitDepth);
            for (var i = 0; i < p0.length; i++) {
                // shift1 = Max(2, 14 - bitDepth) and shift2 = Max(3, 15 - bitDepth), equations 8-262 to 8-264
                assertEquals(Math.clamp((p0[i] + 2) >> 2, 0, max), uni[i] & 0xFFFF, "uni " + i);
                assertEquals(Math.clamp((p0[i] + p1[i] + 4) >> 3, 0, max), bi[i] & 0xFFFF, "bi " + i);
            }
        }

        @ParameterizedTest(name = "{0}-bit")
        @ValueSource(ints = {8, 10, 12})
        @DisplayName("vector residual addition equals scalar for every block size, clipping included")
        void addResidual(int bitDepth) {
            var random = new Random(bitDepth);
            var max = (1 << bitDepth) - 1;
            for (var size = 4; size <= 32; size <<= 1) {
                var residual = new int[size * size];
                for (var i = 0; i < residual.length; i++) {
                    residual[i] = random.nextInt(4 * max + 1) - 2 * max;
                }
                var expected = plane(random, bitDepth);
                var actual = expected.clone();
                SCALAR.addResidual(expected, 3 * STRIDE + 5, STRIDE, residual, size, max);
                VECTOR.addResidual(actual, 3 * STRIDE + 5, STRIDE, residual, size, max);
                assertArrayEquals(expected, actual, "size " + size);
            }
        }
    }

    @Nested
    @DisplayName("sample adaptive offset")
    class SampleAdaptiveOffset {
        @ParameterizedTest(name = "{0}-bit")
        @ValueSource(ints = {8, 10, 12})
        @DisplayName("vector edge offsets equal scalar for every class and width")
        void edge(int bitDepth) {
            var random = new Random(bitDepth);
            var src = plane(random, bitDepth);
            var max = (1 << bitDepth) - 1;
            var range = 1 << (Math.min(bitDepth, 10) - 5);
            int[][] deltas = {{-1, 1}, {-STRIDE, STRIDE}, {-STRIDE - 1, STRIDE + 1}, {-STRIDE + 1, STRIDE - 1}};
            for (var delta : deltas) {
                for (var w = 1; w <= 70; w += 3) {
                    int[] lut = {random.nextInt(range), random.nextInt(range), 0, -random.nextInt(range),
                            -random.nextInt(range)};
                    var expected = new short[src.length];
                    var actual = new short[src.length];
                    SCALAR.saoEdge(expected, src, STRIDE + 1, STRIDE, w, 20, delta[0], delta[1], lut, max);
                    VECTOR.saoEdge(actual, src, STRIDE + 1, STRIDE, w, 20, delta[0], delta[1], lut, max);
                    assertArrayEquals(expected, actual, "delta " + delta[0] + " width " + w);
                }
            }
        }

        @ParameterizedTest(name = "{0}-bit")
        @ValueSource(ints = {8, 10, 12})
        @DisplayName("vector band offsets equal scalar for every band position, including the wrap")
        void band(int bitDepth) {
            var random = new Random(bitDepth);
            var src = plane(random, bitDepth);
            var max = (1 << bitDepth) - 1;
            var range = 1 << (Math.min(bitDepth, 10) - 5);
            for (var position = 0; position < 32; position++) {
                var w = 1 + random.nextInt(70);
                int[] offsets = new int[4];
                for (var k = 0; k < 4; k++) {
                    offsets[k] = random.nextInt(2 * range) - range;
                }
                var expected = new short[src.length];
                var actual = new short[src.length];
                SCALAR.saoBand(expected, src, 3, STRIDE, w, 30, position, bitDepth - 5, offsets[0], offsets[1],
                        offsets[2], offsets[3], max);
                VECTOR.saoBand(actual, src, 3, STRIDE, w, 30, position, bitDepth - 5, offsets[0], offsets[1],
                        offsets[2], offsets[3], max);
                assertArrayEquals(expected, actual, "position " + position + " width " + w);
            }
        }
    }

    private static byte[] bytes(short[] plane) {
        var out = new byte[plane.length + HevcFrame.PADDING];
        for (var i = 0; i < plane.length; i++) {
            out[i] = (byte) plane[i];
        }
        return out;
    }

    private static short[] widened(byte[] plane) {
        var out = new short[plane.length];
        for (var i = 0; i < plane.length; i++) {
            out[i] = (short) (plane[i] & 0xFF);
        }
        return out;
    }

    private static short[] lumaOf(HevcDsp dsp, byte[] src, int x0, int y0, int w, int h, int xFrac, int yFrac) {
        var out = new short[w * h + 1];
        var fits = dsp.lumaMc(src, y0 * STRIDE + x0, STRIDE, out, w, h, xFrac, yFrac, new short[HevcDsp.SCRATCH_SIZE]);
        out[w * h] = (short) (fits ? 1 : 0);
        return out;
    }

    private static short[] chromaOf(HevcDsp dsp, byte[] src, int x0, int y0, int w, int h, int xFrac, int yFrac) {
        var out = new short[w * h + 1];
        var fits = dsp.chromaMc(src, y0 * STRIDE + x0, STRIDE, out, w, h, xFrac, yFrac,
                new short[HevcDsp.SCRATCH_SIZE]);
        out[w * h] = (short) (fits ? 1 : 0);
        return out;
    }

    @Nested
    @DisplayName("8-bit planes")
    class EightBit {
        @ParameterizedTest(name = "seed {0}")
        @ValueSource(ints = {1, 2, 3})
        @DisplayName("luma interpolation of 8-bit planes equals the 16-bit kernels, scalar and vector")
        void luma(int seed) {
            var random = new Random(seed);
            var wide = plane(random, 8);
            var src = bytes(wide);
            for (var w : LUMA_SIZES) {
                for (var h : LUMA_SIZES) {
                    for (var fraction = 0; fraction < 16; fraction++) {
                        var x0 = 4 + random.nextInt(STRIDE - 72);
                        var y0 = 4 + random.nextInt(ROWS - 72);
                        var expected = lumaOf(SCALAR, wide, x0, y0, w, h, fraction & 3, fraction >> 2, 8);
                        var label = w + "x" + h + " fraction " + fraction;
                        assertArrayEquals(expected, lumaOf(SCALAR, src, x0, y0, w, h, fraction & 3, fraction >> 2),
                                "scalar " + label);
                        assertArrayEquals(expected, lumaOf(VECTOR, src, x0, y0, w, h, fraction & 3, fraction >> 2),
                                "vector " + label);
                    }
                }
            }
        }

        @Test
        @DisplayName("an 8-bit half-sample luma block whose values leave 16 bits is reported")
        void lumaOverflow() {
            var wide = new short[STRIDE * ROWS];
            for (var y = 0; y < ROWS; y++) {
                for (var x = 0; x < STRIDE; x++) {
                    var positiveX = x % 8 == 1 || x % 8 == 3 || x % 8 == 4 || x % 8 == 6;
                    var positiveY = y % 8 == 1 || y % 8 == 3 || y % 8 == 4 || y % 8 == 6;
                    wide[y * STRIDE + x] = (short) (positiveX == positiveY ? 255 : 0);
                }
            }
            var src = bytes(wide);
            for (var dsp : new HevcDsp[]{SCALAR, VECTOR}) {
                assertEquals(0, lumaOf(dsp, src, 11, 11, 8, 8, 2, 2)[64], "overflow reported");
            }
        }

        @ParameterizedTest(name = "seed {0}")
        @ValueSource(ints = {1, 2, 3})
        @DisplayName("chroma interpolation of 8-bit planes equals the 16-bit kernels, scalar and vector")
        void chroma(int seed) {
            var random = new Random(seed);
            var wide = plane(random, 8);
            var src = bytes(wide);
            for (var w : CHROMA_SIZES) {
                for (var h : CHROMA_SIZES) {
                    for (var fraction = 0; fraction < 64; fraction++) {
                        var x0 = 2 + random.nextInt(STRIDE - 40);
                        var y0 = 2 + random.nextInt(ROWS - 40);
                        var expected = chromaOf(SCALAR, wide, x0, y0, w, h, fraction & 7, fraction >> 3, 8);
                        var label = w + "x" + h + " fraction " + fraction;
                        assertArrayEquals(expected, chromaOf(SCALAR, src, x0, y0, w, h, fraction & 7, fraction >> 3),
                                "scalar " + label);
                        assertArrayEquals(expected, chromaOf(VECTOR, src, x0, y0, w, h, fraction & 7, fraction >> 3),
                                "vector " + label);
                    }
                }
            }
        }

        @ParameterizedTest(name = "seed {0}")
        @ValueSource(ints = {1, 2, 3})
        @DisplayName("weighted prediction into 8-bit planes equals the 16-bit kernels, scalar and vector")
        void put(int seed) {
            var random = new Random(seed);
            for (var w : LUMA_SIZES) {
                for (var h : CHROMA_SIZES) {
                    var p0 = new short[w * h];
                    var p1 = new short[w * h];
                    for (var i = 0; i < p0.length; i++) {
                        var pick = random.nextInt(8);
                        p0[i] = (short) (pick == 0 ? 32767 : pick == 1 ? -16830 : random.nextInt(49598) - 16830);
                        pick = random.nextInt(8);
                        p1[i] = (short) (pick == 0 ? 32767 : pick == 1 ? -16830 : random.nextInt(49598) - 16830);
                    }
                    var label = w + "x" + h;
                    var expected = new short[STRIDE * 40];
                    var scalar = new byte[STRIDE * 40];
                    var vector = new byte[STRIDE * 40];
                    SCALAR.putUni(expected, STRIDE + 3, STRIDE, p0, w, h, 8);
                    SCALAR.putUni(scalar, STRIDE + 3, STRIDE, p0, w, h);
                    VECTOR.putUni(vector, STRIDE + 3, STRIDE, p0, w, h);
                    assertArrayEquals(expected, widened(scalar), "scalar uni " + label);
                    assertArrayEquals(scalar, vector, "vector uni " + label);
                    SCALAR.putBi(expected, 2, STRIDE, p0, p1, w, h, 8);
                    SCALAR.putBi(scalar, 2, STRIDE, p0, p1, w, h);
                    VECTOR.putBi(vector, 2, STRIDE, p0, p1, w, h);
                    assertArrayEquals(expected, widened(scalar), "scalar bi " + label);
                    assertArrayEquals(scalar, vector, "vector bi " + label);
                    var weight0 = random.nextInt(256) - 128;
                    var weight1 = random.nextInt(256) - 128;
                    var offset = random.nextInt(256) - 128;
                    var log2Wd = 6 + random.nextInt(8);
                    SCALAR.putWeightedUni(expected, 5, STRIDE, p1, w, h, 8, weight0, offset, log2Wd);
                    SCALAR.putWeightedUni(scalar, 5, STRIDE, p1, w, h, weight0, offset, log2Wd);
                    VECTOR.putWeightedUni(vector, 5, STRIDE, p1, w, h, weight0, offset, log2Wd);
                    assertArrayEquals(expected, widened(scalar), "scalar weighted uni " + label);
                    assertArrayEquals(scalar, vector, "vector weighted uni " + label);
                    SCALAR.putWeightedBi(expected, 7, STRIDE, p0, p1, w, h, 8, weight0, weight1, 2 * offset, log2Wd);
                    SCALAR.putWeightedBi(scalar, 7, STRIDE, p0, p1, w, h, weight0, weight1, 2 * offset, log2Wd);
                    VECTOR.putWeightedBi(vector, 7, STRIDE, p0, p1, w, h, weight0, weight1, 2 * offset, log2Wd);
                    assertArrayEquals(expected, widened(scalar), "scalar weighted bi " + label);
                    assertArrayEquals(scalar, vector, "vector weighted bi " + label);
                }
            }
        }

        @Test
        @DisplayName("residual addition to 8-bit planes equals the 16-bit kernels, scalar and vector")
        void addResidual() {
            var random = new Random(8);
            for (var size = 4; size <= 32; size <<= 1) {
                var residual = new int[size * size];
                for (var i = 0; i < residual.length; i++) {
                    residual[i] = random.nextInt(4 * 255 + 1) - 2 * 255;
                }
                var expected = plane(random, 8);
                var scalar = bytes(expected);
                var vector = scalar.clone();
                SCALAR.addResidual(expected, 3 * STRIDE + 5, STRIDE, residual, size, 255);
                SCALAR.addResidual(scalar, 3 * STRIDE + 5, STRIDE, residual, size);
                VECTOR.addResidual(vector, 3 * STRIDE + 5, STRIDE, residual, size);
                assertArrayEquals(bytes(expected), scalar, "scalar size " + size);
                assertArrayEquals(scalar, vector, "vector size " + size);
            }
        }

        @Test
        @DisplayName("adding one value to an 8-bit block equals adding a residual of that value, scalar and vector")
        void addConstant() {
            var random = new Random(9);
            for (var size = 4; size <= 32; size <<= 1) {
                for (var value : new int[]{-700, -255, -17, -1, 1, 3, 90, 256, 700}) {
                    var residual = new int[size * size];
                    java.util.Arrays.fill(residual, value);
                    var expected = bytes(plane(random, 8));
                    var scalar = expected.clone();
                    var vector = expected.clone();
                    SCALAR.addResidual(expected, 3 * STRIDE + 5, STRIDE, residual, size);
                    SCALAR.addConstant(scalar, 3 * STRIDE + 5, STRIDE, size, value);
                    VECTOR.addConstant(vector, 3 * STRIDE + 5, STRIDE, size, value);
                    assertArrayEquals(expected, scalar, "scalar size " + size + " value " + value);
                    assertArrayEquals(expected, vector, "vector size " + size + " value " + value);
                }
            }
        }

        @ParameterizedTest(name = "seed {0}")
        @ValueSource(ints = {1, 2, 3, 4})
        @DisplayName("vector luma deblocking of 8-bit horizontal edges equals scalar for every flag combination")
        void deblockLuma(int seed) {
            var random = new Random(seed);
            for (var trial = 0; trial < 400; trial++) {
                var plane = new byte[STRIDE * 12 + HevcFrame.PADDING];
                var level = random.nextInt(256);
                for (var i = 0; i < plane.length; i++) {
                    // Smooth areas with noise trigger the strong and normal filters, extremes the clipping.
                    var pick = random.nextInt(16);
                    plane[i] = (byte) (pick == 0 ? 0 : pick == 1 ? 255 : Math.clamp(level + random.nextInt(9) - 4, 0, 255));
                }
                var params = new short[32];
                for (var i = 0; i < 16; i++) {
                    params[i] = (short) random.nextInt(25);
                    params[16 + i] = (short) random.nextInt(64);
                }
                var scalar = plane.clone();
                var vector = plane.clone();
                SCALAR.deblockLuma(scalar, 5 * STRIDE + 3, STRIDE, params);
                VECTOR.deblockLuma(vector, 5 * STRIDE + 3, STRIDE, params);
                assertArrayEquals(scalar, vector, "trial " + trial);
            }
        }

        @ParameterizedTest(name = "seed {0}")
        @ValueSource(ints = {1, 2, 3})
        @DisplayName("vector luma deblocking of 8-bit vertical edges equals scalar and the horizontal kernel transposed")
        void deblockLumaVertical(int seed) {
            var random = new Random(seed);
            for (var trial = 0; trial < 400; trial++) {
                var plane = new byte[STRIDE * 20 + HevcFrame.PADDING];
                var level = random.nextInt(256);
                for (var i = 0; i < plane.length; i++) {
                    var pick = random.nextInt(16);
                    plane[i] = (byte) (pick == 0 ? 0 : pick == 1 ? 255 : Math.clamp(level + random.nextInt(9) - 4, 0, 255));
                }
                var params = new short[32];
                for (var i = 0; i < 16; i++) {
                    params[i] = (short) random.nextInt(25);
                    params[16 + i] = (short) random.nextInt(64);
                }
                var origin = 2 * STRIDE + 8 + random.nextInt(STRIDE - 16);
                var scalar = plane.clone();
                var vector = plane.clone();
                SCALAR.deblockLumaVertical(scalar, origin, STRIDE, params, new byte[HevcDsp.DEBLOCK_SCRATCH]);
                VECTOR.deblockLumaVertical(vector, origin, STRIDE, params, new byte[HevcDsp.DEBLOCK_SCRATCH]);
                assertArrayEquals(scalar, vector, "trial " + trial);
                var columns = new byte[8 * 16 + HevcFrame.PADDING];
                for (var line = 0; line < 16; line++) {
                    for (var k = 0; k < 8; k++) {
                        columns[k * 16 + line] = plane[origin - 4 + k + line * STRIDE];
                    }
                }
                SCALAR.deblockLuma(columns, 4 * 16, 16, params);
                for (var line = 0; line < 16; line++) {
                    for (var k = 0; k < 8; k++) {
                        assertEquals(columns[k * 16 + line], scalar[origin - 4 + k + line * STRIDE],
                                "trial " + trial + " line " + line + " sample " + k);
                    }
                }
            }
        }

        @Test
        @DisplayName("edge and band offsets of 8-bit planes equal the 16-bit kernels, scalar and vector")
        void sao() {
            var random = new Random(5);
            var wide = plane(random, 8);
            var src = bytes(wide);
            int[][] deltas = {{-1, 1}, {-STRIDE, STRIDE}, {-STRIDE - 1, STRIDE + 1}, {-STRIDE + 1, STRIDE - 1}};
            for (var delta : deltas) {
                for (var w = 1; w <= 70; w += 3) {
                    int[] lut = {random.nextInt(8), random.nextInt(8), 0, -random.nextInt(8), -random.nextInt(8)};
                    var expected = new short[wide.length];
                    var scalar = new byte[src.length];
                    var vector = new byte[src.length];
                    SCALAR.saoEdge(expected, wide, STRIDE + 1, STRIDE, w, 20, delta[0], delta[1], lut, 255);
                    SCALAR.saoEdge(scalar, src, STRIDE + 1, STRIDE, w, 20, delta[0], delta[1], lut);
                    VECTOR.saoEdge(vector, src, STRIDE + 1, STRIDE, w, 20, delta[0], delta[1], lut);
                    assertArrayEquals(bytes(expected), scalar, "scalar edge " + delta[0] + " width " + w);
                    assertArrayEquals(scalar, vector, "vector edge " + delta[0] + " width " + w);
                }
            }
            for (var position = 0; position < 32; position++) {
                var w = 1 + random.nextInt(70);
                var o = new int[4];
                for (var k = 0; k < 4; k++) {
                    o[k] = random.nextInt(16) - 8;
                }
                var expected = new short[wide.length];
                var scalar = new byte[src.length];
                var vector = new byte[src.length];
                SCALAR.saoBand(expected, wide, 3, STRIDE, w, 30, position, 3, o[0], o[1], o[2], o[3], 255);
                SCALAR.saoBand(scalar, src, 3, STRIDE, w, 30, position, o[0], o[1], o[2], o[3]);
                VECTOR.saoBand(vector, src, 3, STRIDE, w, 30, position, o[0], o[1], o[2], o[3]);
                assertArrayEquals(bytes(expected), scalar, "scalar band " + position + " width " + w);
                assertArrayEquals(scalar, vector, "vector band " + position + " width " + w);
            }
        }
    }
}
