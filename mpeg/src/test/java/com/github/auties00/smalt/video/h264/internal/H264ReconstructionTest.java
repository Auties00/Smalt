package com.github.auties00.smalt.video.h264.internal;

import com.github.auties00.smalt.internal.simd.Simd;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Checks intra prediction and the inverse transforms against literal transcriptions of the
 * equations of ITU-T H.264 clauses 8.3 and 8.5, on random neighbors, predictions and
 * coefficients.
 */
@DisplayName("H.264 reconstruction against the specification equations")
class H264ReconstructionTest {
    private static final int STRIDE = 64;
    private static final int ORIGIN = 8 * STRIDE + 8;

    private static int clip(int value) {
        return Math.clamp(value, 0, 255);
    }

    private static byte[] randomPlane(Random random) {
        var plane = new byte[STRIDE * 48];
        random.nextBytes(plane);
        return plane;
    }

    @Nested
    @DisplayName("intra prediction")
    class Intra {
        /** Returns p[x, y] relative to the block origin. */
        private static int p(byte[] plane, int x, int y) {
            return plane[ORIGIN + y * STRIDE + x] & 0xFF;
        }

        private static int predict4x4(byte[] plane, int mode, int x, int y) {
            return switch (mode) {
                case 3 -> x == 3 && y == 3
                        ? (p(plane, 6, -1) + 3 * p(plane, 7, -1) + 2) >> 2
                        : (p(plane, x + y, -1) + 2 * p(plane, x + y + 1, -1) + p(plane, x + y + 2, -1) + 2) >> 2;
                case 4 -> {
                    if (x > y) {
                        yield (p(plane, x - y - 2, -1) + 2 * p(plane, x - y - 1, -1) + p(plane, x - y, -1) + 2) >> 2;
                    } else if (x < y) {
                        yield (p(plane, -1, y - x - 2) + 2 * p(plane, -1, y - x - 1) + p(plane, -1, y - x) + 2) >> 2;
                    }
                    yield (p(plane, 0, -1) + 2 * p(plane, -1, -1) + p(plane, -1, 0) + 2) >> 2;
                }
                case 5 -> {
                    var z = 2 * x - y;
                    if (z >= 0 && (z & 1) == 0) {
                        yield (p(plane, x - (y >> 1) - 1, -1) + p(plane, x - (y >> 1), -1) + 1) >> 1;
                    } else if (z >= 0) {
                        yield (p(plane, x - (y >> 1) - 2, -1) + 2 * p(plane, x - (y >> 1) - 1, -1) + p(plane, x - (y >> 1), -1) + 2) >> 2;
                    } else if (z == -1) {
                        yield (p(plane, -1, 0) + 2 * p(plane, -1, -1) + p(plane, 0, -1) + 2) >> 2;
                    }
                    yield (p(plane, -1, y - 1) + 2 * p(plane, -1, y - 2) + p(plane, -1, y - 3) + 2) >> 2;
                }
                case 6 -> {
                    var z = 2 * y - x;
                    if (z >= 0 && (z & 1) == 0) {
                        yield (p(plane, -1, y - (x >> 1) - 1) + p(plane, -1, y - (x >> 1)) + 1) >> 1;
                    } else if (z >= 0) {
                        yield (p(plane, -1, y - (x >> 1) - 2) + 2 * p(plane, -1, y - (x >> 1) - 1) + p(plane, -1, y - (x >> 1)) + 2) >> 2;
                    } else if (z == -1) {
                        yield (p(plane, -1, 0) + 2 * p(plane, -1, -1) + p(plane, 0, -1) + 2) >> 2;
                    }
                    yield (p(plane, x - 1, -1) + 2 * p(plane, x - 2, -1) + p(plane, x - 3, -1) + 2) >> 2;
                }
                case 7 -> (y & 1) == 0
                        ? (p(plane, x + (y >> 1), -1) + p(plane, x + (y >> 1) + 1, -1) + 1) >> 1
                        : (p(plane, x + (y >> 1), -1) + 2 * p(plane, x + (y >> 1) + 1, -1) + p(plane, x + (y >> 1) + 2, -1) + 2) >> 2;
                case 8 -> {
                    var z = x + 2 * y;
                    if (z < 5 && (z & 1) == 0) {
                        yield (p(plane, -1, y + (x >> 1)) + p(plane, -1, y + (x >> 1) + 1) + 1) >> 1;
                    } else if (z < 5) {
                        yield (p(plane, -1, y + (x >> 1)) + 2 * p(plane, -1, y + (x >> 1) + 1) + p(plane, -1, y + (x >> 1) + 2) + 2) >> 2;
                    } else if (z == 5) {
                        yield (p(plane, -1, 2) + 3 * p(plane, -1, 3) + 2) >> 2;
                    }
                    yield p(plane, -1, 3);
                }
                case 0 -> p(plane, x, -1);
                case 1 -> p(plane, -1, y);
                default -> (p(plane, 0, -1) + p(plane, 1, -1) + p(plane, 2, -1) + p(plane, 3, -1)
                        + p(plane, -1, 0) + p(plane, -1, 1) + p(plane, -1, 2) + p(plane, -1, 3) + 4) >> 3;
            };
        }

        @ParameterizedTest(name = "mode {0}")
        @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6, 7, 8})
        @DisplayName("4x4 luma modes follow 8.3.1.2")
        void luma4x4(int mode) {
            var random = new Random(mode);
            var predictor = new IntraPredictor();
            for (var trial = 0; trial < 200; trial++) {
                var plane = randomPlane(random);
                var expected = new int[16];
                for (var y = 0; y < 4; y++) {
                    for (var x = 0; x < 4; x++) {
                        expected[y * 4 + x] = predict4x4(plane, mode, x, y);
                    }
                }
                predictor.predict4x4(mode, plane, ORIGIN, STRIDE, true, true, true, true);
                for (var i = 0; i < 16; i++) {
                    assertEquals(expected[i], p(plane, i & 3, i >> 2), "sample " + i);
                }
            }
        }

        @Test
        @DisplayName("4x4 prediction substitutes p[3, -1] for unavailable top-right samples")
        void topRightSubstitution() {
            var random = new Random(9);
            var predictor = new IntraPredictor();
            var plane = randomPlane(random);
            var copy = plane.clone();
            for (var x = 4; x < 8; x++) {
                copy[ORIGIN - STRIDE + x] = copy[ORIGIN - STRIDE + 3];
            }
            var expected = new int[16];
            for (var i = 0; i < 16; i++) {
                expected[i] = predict4x4(copy, 3, i & 3, i >> 2);
            }
            predictor.predict4x4(3, plane, ORIGIN, STRIDE, true, true, true, false);
            for (var i = 0; i < 16; i++) {
                assertEquals(expected[i], p(plane, i & 3, i >> 2));
            }
        }

        @Test
        @DisplayName("16x16 plane prediction follows 8.3.3.4")
        void lumaPlane() {
            var random = new Random(10);
            var predictor = new IntraPredictor();
            for (var trial = 0; trial < 200; trial++) {
                var plane = randomPlane(random);
                var h = 0;
                var v = 0;
                for (var i = 0; i < 8; i++) {
                    h += (i + 1) * (p(plane, 8 + i, -1) - p(plane, 6 - i, -1));
                    v += (i + 1) * (p(plane, -1, 8 + i) - p(plane, -1, 6 - i));
                }
                var a = 16 * (p(plane, -1, 15) + p(plane, 15, -1));
                var b = (5 * h + 32) >> 6;
                var c = (5 * v + 32) >> 6;
                var expected = new int[256];
                for (var y = 0; y < 16; y++) {
                    for (var x = 0; x < 16; x++) {
                        expected[y * 16 + x] = clip((a + b * (x - 7) + c * (y - 7) + 16) >> 5);
                    }
                }
                predictor.predict16x16(3, plane, ORIGIN, STRIDE, true, true, true);
                for (var i = 0; i < 256; i++) {
                    assertEquals(expected[i], p(plane, i & 15, i >> 4));
                }
            }
        }

        @Test
        @DisplayName("16x16 DC prediction uses the available neighbors or 128")
        void lumaDc() {
            var random = new Random(11);
            var predictor = new IntraPredictor();
            for (var availability = 0; availability < 4; availability++) {
                var hasLeft = (availability & 1) != 0;
                var hasTop = (availability & 2) != 0;
                var plane = randomPlane(random);
                var top = 0;
                var left = 0;
                for (var i = 0; i < 16; i++) {
                    top += p(plane, i, -1);
                    left += p(plane, -1, i);
                }
                var expected = hasLeft && hasTop ? (top + left + 16) >> 5 : hasLeft ? (left + 8) >> 4 : hasTop ? (top + 8) >> 4 : 128;
                predictor.predict16x16(2, plane, ORIGIN, STRIDE, hasLeft, hasTop, hasLeft && hasTop);
                for (var i = 0; i < 256; i++) {
                    assertEquals(expected, p(plane, i & 15, i >> 4));
                }
            }
        }

        @Test
        @DisplayName("chroma DC prediction follows 8.3.4.1 to 8.3.4.3 per 4x4 block")
        void chromaDc() {
            var random = new Random(12);
            var predictor = new IntraPredictor();
            for (var availability = 0; availability < 4; availability++) {
                var hasLeft = (availability & 1) != 0;
                var hasTop = (availability & 2) != 0;
                var plane = randomPlane(random);
                var expected = new int[4];
                for (var block = 0; block < 4; block++) {
                    var bx = (block & 1) * 4;
                    var by = (block >> 1) * 4;
                    var top = 0;
                    var left = 0;
                    for (var i = 0; i < 4; i++) {
                        top += p(plane, bx + i, -1);
                        left += p(plane, -1, by + i);
                    }
                    var both = (top + left + 4) >> 3;
                    var onlyTop = (top + 2) >> 2;
                    var onlyLeft = (left + 2) >> 2;
                    if (block == 0 || block == 3) {
                        expected[block] = hasLeft && hasTop ? both : hasLeft ? onlyLeft : hasTop ? onlyTop : 128;
                    } else if (block == 1) {
                        expected[block] = hasTop ? onlyTop : hasLeft ? onlyLeft : 128;
                    } else {
                        expected[block] = hasLeft ? onlyLeft : hasTop ? onlyTop : 128;
                    }
                }
                predictor.predictChroma(0, plane, ORIGIN, STRIDE, hasLeft, hasTop, hasLeft && hasTop);
                for (var y = 0; y < 8; y++) {
                    for (var x = 0; x < 8; x++) {
                        assertEquals(expected[(y >> 2) * 2 + (x >> 2)], p(plane, x, y), "sample " + x + "," + y);
                    }
                }
            }
        }

        @Test
        @DisplayName("chroma plane prediction follows 8.3.4.4 for 4:2:0")
        void chromaPlane() {
            var random = new Random(13);
            var predictor = new IntraPredictor();
            for (var trial = 0; trial < 200; trial++) {
                var plane = randomPlane(random);
                var h = 0;
                var v = 0;
                for (var i = 0; i < 4; i++) {
                    h += (i + 1) * (p(plane, 4 + i, -1) - p(plane, 2 - i, -1));
                    v += (i + 1) * (p(plane, -1, 4 + i) - p(plane, -1, 2 - i));
                }
                var a = 16 * (p(plane, -1, 7) + p(plane, 7, -1));
                var b = (34 * h + 32) >> 6;
                var c = (34 * v + 32) >> 6;
                var expected = new int[64];
                for (var y = 0; y < 8; y++) {
                    for (var x = 0; x < 8; x++) {
                        expected[y * 8 + x] = clip((a + b * (x - 3) + c * (y - 3) + 16) >> 5);
                    }
                }
                predictor.predictChroma(3, plane, ORIGIN, STRIDE, true, true, true);
                for (var i = 0; i < 64; i++) {
                    assertEquals(expected[i], p(plane, i & 7, i >> 3));
                }
            }
        }
    }

    @Nested
    @DisplayName("inverse transforms")
    class Transforms {
        private static int[][] transform4x4(int[][] d) {
            var f = new int[4][4];
            for (var i = 0; i < 4; i++) {
                var e0 = d[i][0] + d[i][2];
                var e1 = d[i][0] - d[i][2];
                var e2 = (d[i][1] >> 1) - d[i][3];
                var e3 = d[i][1] + (d[i][3] >> 1);
                f[i][0] = e0 + e3;
                f[i][1] = e1 + e2;
                f[i][2] = e1 - e2;
                f[i][3] = e0 - e3;
            }
            var r = new int[4][4];
            for (var j = 0; j < 4; j++) {
                var g0 = f[0][j] + f[2][j];
                var g1 = f[0][j] - f[2][j];
                var g2 = (f[1][j] >> 1) - f[3][j];
                var g3 = f[1][j] + (f[3][j] >> 1);
                r[0][j] = (g0 + g3 + 32) >> 6;
                r[1][j] = (g1 + g2 + 32) >> 6;
                r[2][j] = (g1 - g2 + 32) >> 6;
                r[3][j] = (g0 - g3 + 32) >> 6;
            }
            return r;
        }

        /** Applies the one-dimensional 8-point transform of equations 8-338 to 8-353. */
        private static int[] transform8(int[] d) {
            var e0 = d[0] + d[4];
            var e1 = -d[3] + d[5] - d[7] - (d[7] >> 1);
            var e2 = d[0] - d[4];
            var e3 = d[1] + d[7] - d[3] - (d[3] >> 1);
            var e4 = (d[2] >> 1) - d[6];
            var e5 = -d[1] + d[7] + d[5] + (d[5] >> 1);
            var e6 = d[2] + (d[6] >> 1);
            var e7 = d[3] + d[5] + d[1] + (d[1] >> 1);
            var f0 = e0 + e6;
            var f1 = e1 + (e7 >> 2);
            var f2 = e2 + e4;
            var f3 = e3 + (e5 >> 2);
            var f4 = e2 - e4;
            var f5 = (e3 >> 2) - e5;
            var f6 = e0 - e6;
            var f7 = e7 - (e1 >> 2);
            return new int[]{f0 + f7, f2 + f5, f4 + f3, f6 + f1, f6 - f1, f4 - f3, f2 - f5, f0 - f7};
        }

        private static int[][] transform8x8(int[][] d) {
            var rows = new int[8][];
            for (var i = 0; i < 8; i++) {
                rows[i] = transform8(d[i]);
            }
            var r = new int[8][8];
            for (var j = 0; j < 8; j++) {
                var column = new int[8];
                for (var i = 0; i < 8; i++) {
                    column[i] = rows[i][j];
                }
                var m = transform8(column);
                for (var i = 0; i < 8; i++) {
                    r[i][j] = (m[i] + 32) >> 6;
                }
            }
            return r;
        }

        @ParameterizedTest(name = "coefficient magnitude {0}")
        @ValueSource(ints = {16, 512, 4096, 32767})
        @DisplayName("4x4 and 8x8 transforms with prediction add match 8.5.12 and 8.5.13")
        void transforms(int magnitude) {
            var random = new Random(magnitude);
            for (var dsp : Simd.ENABLED ? new H264Dsp[]{new ScalarH264Dsp(), new VectorH264Dsp()}
                    : new H264Dsp[]{new ScalarH264Dsp()}) {
                for (var trial = 0; trial < 300; trial++) {
                    var size = trial % 2 == 0 ? 4 : 8;
                    var coefficients = new int[size][size];
                    var flat = new int[64];
                    for (var i = 0; i < size; i++) {
                        for (var j = 0; j < size; j++) {
                            var value = random.nextInt(4) == 0 ? random.nextInt(2 * magnitude + 1) - magnitude : 0;
                            coefficients[i][j] = value;
                            flat[i * size + j] = value;
                        }
                    }
                    var plane = randomPlane(random);
                    var prediction = plane.clone();
                    var residual = size == 4 ? transform4x4(coefficients) : transform8x8(coefficients);
                    if (size == 4) {
                        dsp.idct4x4Add(flat, 0, plane, ORIGIN, STRIDE);
                    } else {
                        dsp.idct8x8Add(flat, 0, plane, ORIGIN, STRIDE);
                    }
                    for (var y = 0; y < size; y++) {
                        for (var x = 0; x < size; x++) {
                            var index = ORIGIN + y * STRIDE + x;
                            assertEquals(clip((prediction[index] & 0xFF) + residual[y][x]), plane[index] & 0xFF,
                                    dsp.getClass().getSimpleName() + " " + size + "x" + size + " sample " + x + "," + y);
                        }
                    }
                    for (var value : flat) {
                        assertEquals(0, value, "coefficients are cleared");
                    }
                }
            }
        }
    }
}
