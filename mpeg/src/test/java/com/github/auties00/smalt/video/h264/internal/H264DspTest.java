package com.github.auties00.smalt.video.h264.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Checks the Vector API kernels against their scalar twins on random planes, block sizes and
 * fractional positions, and the scalar kernels against direct evaluations of the standard's
 * formulas.
 */
@DisplayName("H.264 DSP kernels")
class H264DspTest {
    private static final ScalarH264Dsp SCALAR = new ScalarH264Dsp();
    private static final H264Dsp VECTOR = new VectorH264Dsp();
    private static final byte[] SCRATCH = new byte[H264Dsp.EDGE_SCRATCH];
    private static final int STRIDE = 64;
    private static final int[] SIZES = {4, 8, 16};

    private static byte[] randomPlane(Random random, int size, boolean smooth) {
        var plane = new byte[size];
        if (smooth) {
            for (var i = 0; i < size; i++) {
                plane[i] = (byte) (128 + 100 * Math.sin(i * 0.1 + random.nextInt(3)));
            }
        } else {
            random.nextBytes(plane);
        }
        return plane;
    }

    @Nested
    @DisplayName("motion compensation")
    class MotionCompensation {
        @ParameterizedTest(name = "seed {0}")
        @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8})
        @DisplayName("vector luma interpolation equals scalar for every fraction and size")
        void luma(int seed) {
            var random = new Random(seed);
            var src = randomPlane(random, STRIDE * 40, seed % 2 == 0);
            for (var width : SIZES) {
                for (var height : SIZES) {
                    for (var fraction = 0; fraction < 16; fraction++) {
                        var expected = new byte[STRIDE * 20];
                        var actual = new byte[STRIDE * 20];
                        random.nextBytes(expected);
                        System.arraycopy(expected, 0, actual, 0, expected.length);
                        var offset = 4 * STRIDE + 8 + random.nextInt(8);
                        SCALAR.lumaMc(src, offset, STRIDE, expected, STRIDE + 3, STRIDE, width, height, fraction & 3,
                                fraction >> 2, new short[H264Dsp.LUMA_SCRATCH_SHORTS], new byte[H264Dsp.LUMA_SCRATCH_BYTES]);
                        VECTOR.lumaMc(src, offset, STRIDE, actual, STRIDE + 3, STRIDE, width, height, fraction & 3,
                                fraction >> 2, new short[H264Dsp.LUMA_SCRATCH_SHORTS], new byte[H264Dsp.LUMA_SCRATCH_BYTES]);
                        assertArrayEquals(expected, actual, "luma " + width + "x" + height + " fraction " + fraction);
                    }
                }
            }
        }

        @ParameterizedTest(name = "seed {0}")
        @ValueSource(ints = {1, 2, 3, 4})
        @DisplayName("vector two-plane chroma interpolation equals scalar for every fraction and size")
        void chroma(int seed) {
            var random = new Random(seed);
            var cb = randomPlane(random, STRIDE * 24, false);
            var cr = randomPlane(random, STRIDE * 24, false);
            for (var width : new int[]{2, 4, 8}) {
                for (var height : new int[]{2, 4, 8}) {
                    for (var fraction = 0; fraction < 64; fraction++) {
                        var expectedCb = new byte[STRIDE * 12];
                        var expectedCr = new byte[STRIDE * 12];
                        var actualCb = new byte[STRIDE * 12];
                        var actualCr = new byte[STRIDE * 12];
                        random.nextBytes(expectedCb);
                        random.nextBytes(expectedCr);
                        System.arraycopy(expectedCb, 0, actualCb, 0, actualCb.length);
                        System.arraycopy(expectedCr, 0, actualCr, 0, actualCr.length);
                        var offset = 2 * STRIDE + 4 + random.nextInt(8);
                        SCALAR.chromaMc(cb, cr, offset, STRIDE, expectedCb, expectedCr, STRIDE + 1, STRIDE, width, height,
                                fraction & 7, fraction >> 3);
                        VECTOR.chromaMc(cb, cr, offset, STRIDE, actualCb, actualCr, STRIDE + 1, STRIDE, width, height,
                                fraction & 7, fraction >> 3);
                        assertArrayEquals(expectedCb, actualCb, "Cb " + width + "x" + height + " fraction " + fraction);
                        assertArrayEquals(expectedCr, actualCr, "Cr " + width + "x" + height + " fraction " + fraction);
                    }
                }
            }
        }

        @Test
        @DisplayName("scalar luma half samples follow the six-tap formula")
        void lumaFormula() {
            var random = new Random(9);
            var src = randomPlane(random, STRIDE * 30, false);
            var dst = new byte[16 * 16];
            SCALAR.lumaMc(src, 5 * STRIDE + 10, STRIDE, dst, 0, 16, 16, 16, 2, 0,
                    new short[H264Dsp.LUMA_SCRATCH_SHORTS], new byte[H264Dsp.LUMA_SCRATCH_BYTES]);
            for (var y = 0; y < 16; y++) {
                for (var x = 0; x < 16; x++) {
                    var i = 5 * STRIDE + 10 + y * STRIDE + x;
                    var sum = u(src[i - 2]) - 5 * u(src[i - 1]) + 20 * u(src[i]) + 20 * u(src[i + 1]) - 5 * u(src[i + 2]) + u(src[i + 3]);
                    assertEquals(Math.clamp((sum + 16) >> 5, 0, 255), u(dst[y * 16 + x]));
                }
            }
        }

        @ParameterizedTest(name = "seed {0}")
        @ValueSource(ints = {1, 2, 3})
        @DisplayName("vector averaging and weighting equal scalar")
        void weighting(int seed) {
            var random = new Random(seed);
            for (var width : new int[]{2, 4, 8, 16}) {
                var a = randomPlane(random, STRIDE * 16, false);
                var b = randomPlane(random, STRIDE * 16, false);
                var expected = a.clone();
                var actual = a.clone();
                SCALAR.average(expected, 3, STRIDE, b, 7, STRIDE, width, 8);
                VECTOR.average(actual, 3, STRIDE, b, 7, STRIDE, width, 8);
                assertArrayEquals(expected, actual, "average " + width);
                var c = randomPlane(random, STRIDE * 16, false);
                expected = c.clone();
                actual = c.clone();
                SCALAR.averageBlocks(a, 5, STRIDE, b, 9, STRIDE - 8, expected, 3, STRIDE, width, 8);
                VECTOR.averageBlocks(a, 5, STRIDE, b, 9, STRIDE - 8, actual, 3, STRIDE, width, 8);
                assertArrayEquals(expected, actual, "averageBlocks " + width);
                var reference = c.clone();
                SCALAR.average(reference, 3, STRIDE, b, 9, STRIDE - 8, width, 8);
                var copied = c.clone();
                for (var y = 0; y < 8; y++) {
                    System.arraycopy(a, 5 + y * STRIDE, copied, 3 + y * STRIDE, width);
                }
                SCALAR.average(copied, 3, STRIDE, b, 9, STRIDE - 8, width, 8);
                assertArrayEquals(copied, expected, "averageBlocks equals copy then average " + width);
                for (var trial = 0; trial < 50; trial++) {
                    var logWd = random.nextInt(8);
                    var w0 = random.nextInt(256) - 128;
                    var w1 = random.nextInt(256) - 128;
                    var offset = random.nextInt(256) - 128;
                    expected = a.clone();
                    actual = a.clone();
                    SCALAR.biWeight(expected, 3, STRIDE, b, 7, STRIDE, width, 8, logWd, w0, w1, offset);
                    VECTOR.biWeight(actual, 3, STRIDE, b, 7, STRIDE, width, 8, logWd, w0, w1, offset);
                    assertArrayEquals(expected, actual, "biWeight " + width);
                    expected = a.clone();
                    actual = a.clone();
                    SCALAR.weight(expected, 3, STRIDE, width, 8, logWd, w0, offset);
                    VECTOR.weight(actual, 3, STRIDE, width, 8, logWd, w0, offset);
                    assertArrayEquals(expected, actual, "weight " + width);
                }
            }
        }
    }

    @Nested
    @DisplayName("transforms")
    class Transforms {
        @Test
        @DisplayName("4x4 inverse transform of a DC-only block adds (dc + 32) >> 6")
        void dcOnly() {
            var random = new Random(4);
            for (var trial = 0; trial < 100; trial++) {
                var dc = random.nextInt(4000) - 2000;
                var coefficients = new int[16];
                coefficients[0] = dc;
                var full = randomPlane(random, 16, false);
                var fast = full.clone();
                SCALAR.idct4x4Add(coefficients.clone(), 0, full, 0, 4);
                var dcCoefficients = new int[16];
                dcCoefficients[0] = dc;
                SCALAR.idct4x4DcAdd(dcCoefficients, 0, fast, 0, 4);
                assertArrayEquals(full, fast);
            }
        }

        @Test
        @DisplayName("inverse transforms clear their coefficients")
        void clears() {
            var coefficients = new int[64];
            Arrays.fill(coefficients, 7);
            SCALAR.idct8x8Add(coefficients, 0, new byte[64], 0, 8);
            assertArrayEquals(new int[64], coefficients);
            Arrays.fill(coefficients, 0, 16, 5);
            SCALAR.idct4x4Add(coefficients, 0, new byte[16], 0, 4);
            assertArrayEquals(new int[64], coefficients);
        }

        @Test
        @DisplayName("vector inverse transforms equal scalar, including out-of-range coefficients")
        void vectorTransforms() {
            var random = new Random(11);
            for (var trial = 0; trial < 600; trial++) {
                var coefficients = new int[128];
                var range = trial % 3 == 0 ? 1 << 22 : 2048;
                for (var i = 0; i < 128; i++) {
                    coefficients[i] = random.nextInt(8) == 0 ? random.nextInt(range) - range / 2 : 0;
                }
                var plane = randomPlane(random, 16 * 16, false);
                for (var kernel = 0; kernel < 6; kernel++) {
                    var expected = plane.clone();
                    var actual = plane.clone();
                    var expectedCoefficients = coefficients.clone();
                    var actualCoefficients = coefficients.clone();
                    switch (kernel) {
                        case 0 -> {
                            SCALAR.idct8x8Add(expectedCoefficients, 0, expected, 17, 16);
                            VECTOR.idct8x8Add(actualCoefficients, 0, actual, 17, 16);
                        }
                        case 1 -> {
                            SCALAR.idct4x4Add(expectedCoefficients, 16, expected, 17, 16);
                            VECTOR.idct4x4Add(actualCoefficients, 16, actual, 17, 16);
                        }
                        case 2 -> {
                            SCALAR.idct4x4DcAdd(expectedCoefficients, 16, expected, 17, 16);
                            VECTOR.idct4x4DcAdd(actualCoefficients, 16, actual, 17, 16);
                        }
                        case 3 -> {
                            SCALAR.idct8x8DcAdd(expectedCoefficients, 0, expected, 17, 16);
                            VECTOR.idct8x8DcAdd(actualCoefficients, 0, actual, 17, 16);
                        }
                        case 4 -> {
                            SCALAR.chromaDcAdd(expectedCoefficients, 0, expected, 17, 16);
                            VECTOR.chromaDcAdd(actualCoefficients, 0, actual, 17, 16);
                        }
                        default -> {
                            SCALAR.chromaDcAdd(expectedCoefficients, 64, expected, 17, 16);
                            VECTOR.chromaDcAdd(actualCoefficients, 64, actual, 17, 16);
                        }
                    }
                    assertArrayEquals(expected, actual, "kernel " + kernel);
                    assertArrayEquals(expectedCoefficients, actualCoefficients, "coefficients of kernel " + kernel);
                }
            }
        }
    }

    @Nested
    @DisplayName("deblocking")
    class Deblocking {
        @ParameterizedTest(name = "seed {0}")
        @ValueSource(ints = {1, 2, 3, 4, 5})
        @DisplayName("vector luma edge filters equal scalar")
        void luma(int seed) {
            var random = new Random(seed);
            for (var trial = 0; trial < 400; trial++) {
                var plane = edgePlane(random);
                var alpha = random.nextInt(256);
                var beta = random.nextInt(19);
                var tc0 = 0;
                for (var i = 0; i < 4; i++) {
                    tc0 |= (random.nextInt(4) == 0 ? 0xFF : random.nextInt(26)) << (i * 8);
                }
                var offset = 8 * STRIDE + 24;
                var packed = tc0;
                check(plane, p -> SCALAR.lumaEdgeVertical(p, offset, STRIDE, alpha, beta, packed, SCRATCH),
                        p -> VECTOR.lumaEdgeVertical(p, offset, STRIDE, alpha, beta, packed, SCRATCH), "luma vertical");
                check(plane, p -> SCALAR.lumaEdgeHorizontal(p, offset, STRIDE, alpha, beta, packed),
                        p -> VECTOR.lumaEdgeHorizontal(p, offset, STRIDE, alpha, beta, packed), "luma horizontal");
                check(plane, p -> SCALAR.lumaEdgeVerticalStrong(p, offset, STRIDE, alpha, beta, SCRATCH),
                        p -> VECTOR.lumaEdgeVerticalStrong(p, offset, STRIDE, alpha, beta, SCRATCH), "luma vertical strong");
                check(plane, p -> SCALAR.lumaEdgeHorizontalStrong(p, offset, STRIDE, alpha, beta),
                        p -> VECTOR.lumaEdgeHorizontalStrong(p, offset, STRIDE, alpha, beta), "luma horizontal strong");
            }
        }

        @ParameterizedTest(name = "seed {0}")
        @ValueSource(ints = {1, 2, 3, 4, 5})
        @DisplayName("vector two-plane chroma edge filters equal scalar")
        void chroma(int seed) {
            var random = new Random(seed);
            for (var trial = 0; trial < 400; trial++) {
                var cb = edgePlane(random);
                var cr = edgePlane(random);
                var params = new int[8];
                for (var i = 0; i < 8; i += 4) {
                    for (var plane = 0; plane < 2; plane++) {
                        var zero = random.nextInt(8) == 0;
                        params[i + plane] = zero ? 0 : random.nextInt(256) | (random.nextInt(19) << 8);
                        var tc0 = 0;
                        for (var segment = 0; segment < 4; segment++) {
                            var value = switch (random.nextInt(5)) {
                                case 0 -> 0xFF;
                                case 1 -> H264Dsp.CHROMA_STRONG;
                                default -> random.nextInt(26);
                            };
                            tc0 |= value << (segment * 8);
                        }
                        params[i + 2 + plane] = zero ? -1 : tc0;
                    }
                }
                var offset = 8 * STRIDE + 24;
                var edges = 1 + random.nextInt(3);
                var at = random.nextBoolean() ? 0 : 4;
                check(cb, cr, (u, v) -> SCALAR.chromaEdgesVertical(u, v, offset, STRIDE, edges, params, SCRATCH),
                        (u, v) -> VECTOR.chromaEdgesVertical(u, v, offset, STRIDE, edges, params, SCRATCH), "chroma vertical");
                check(cb, cr, (u, v) -> SCALAR.chromaEdgeHorizontal(u, v, offset, STRIDE, params, at),
                        (u, v) -> VECTOR.chromaEdgeHorizontal(u, v, offset, STRIDE, params, at), "chroma horizontal");
            }
        }

        @ParameterizedTest(name = "seed {0}")
        @ValueSource(ints = {1, 2, 3})
        @DisplayName("consecutive vertical luma edges of a macroblock equal scalar")
        void consecutiveEdges(int seed) {
            var random = new Random(seed);
            for (var trial = 0; trial < 200; trial++) {
                var plane = edgePlane(random);
                var x = 20 + random.nextInt(8);
                var params = new int[4][3];
                for (var edge = 0; edge < 4; edge++) {
                    var tc0 = 0;
                    for (var i = 0; i < 4; i++) {
                        tc0 |= (random.nextInt(4) == 0 ? 0xFF : random.nextInt(26)) << (i * 8);
                    }
                    params[edge] = new int[]{random.nextInt(256), random.nextInt(19), tc0};
                }
                var strong = random.nextBoolean();
                java.util.function.BiConsumer<H264Dsp, byte[]> run = (dsp, p) -> {
                    for (var edge = 0; edge < 4; edge++) {
                        var offset = 8 * STRIDE + x + edge * 4;
                        if (edge == 0 && strong) {
                            dsp.lumaEdgeVerticalStrong(p, offset, STRIDE, params[0][0], params[0][1], SCRATCH);
                        } else {
                            dsp.lumaEdgeVertical(p, offset, STRIDE, params[edge][0], params[edge][1], params[edge][2], SCRATCH);
                        }
                    }
                };
                check(plane, p -> run.accept(SCALAR, p), p -> run.accept(VECTOR, p), "luma vertical edges");
            }
        }

        @Test
        @DisplayName("vertical edge filters stay within twelve samples of the edge")
        void verticalMargins() {
            var random = new Random(4);
            var stride = 24;
            for (var trial = 0; trial < 50; trial++) {
                var plane = new byte[15 * stride + 24];
                random.nextBytes(plane);
                var copy = plane.clone();
                SCALAR.lumaEdgeVertical(plane, 12, stride, 255, 18, 0x03030303, SCRATCH);
                VECTOR.lumaEdgeVertical(copy, 12, stride, 255, 18, 0x03030303, SCRATCH);
                assertArrayEquals(plane, copy);
                SCALAR.lumaEdgeVerticalStrong(plane, 12, stride, 255, 18, SCRATCH);
                VECTOR.lumaEdgeVerticalStrong(copy, 12, stride, 255, 18, SCRATCH);
                assertArrayEquals(plane, copy);
                var cb = new byte[7 * stride + 24];
                var cr = new byte[7 * stride + 24];
                random.nextBytes(cb);
                random.nextBytes(cr);
                var cbCopy = cb.clone();
                var crCopy = cr.clone();
                var params = new int[]{255 | 18 << 8, 255 | 18 << 8, 0x02020202, 0x40404040,
                        255 | 18 << 8, 255 | 18 << 8, 0x01010101, 0x02020202};
                SCALAR.chromaEdgesVertical(cb, cr, 10, stride, 3, params, SCRATCH);
                VECTOR.chromaEdgesVertical(cbCopy, crCopy, 10, stride, 3, params, SCRATCH);
                assertArrayEquals(cb, cbCopy);
                assertArrayEquals(cr, crCopy);
            }
        }

        @Test
        @DisplayName("a negative segment tC0 leaves its lines untouched")
        void skippedSegments() {
            var plane = edgePlane(new Random(9));
            var offset = 8 * STRIDE + 24;
            for (var dsp : new H264Dsp[]{SCALAR, VECTOR}) {
                var copy = plane.clone();
                dsp.lumaEdgeHorizontal(copy, offset, STRIDE, 255, 18, 0xFFFFFFFF);
                dsp.lumaEdgeVertical(copy, offset, STRIDE, 255, 18, 0xFFFFFFFF, SCRATCH);
                assertArrayEquals(plane, copy);
            }
        }

        private static byte[] edgePlane(Random random) {
            var plane = new byte[STRIDE * 32];
            var base = random.nextInt(200) + 20;
            for (var i = 0; i < plane.length; i++) {
                plane[i] = (byte) Math.clamp(base + random.nextInt(21) - 10 + (i % STRIDE > 20 ? random.nextInt(30) : 0), 0, 255);
            }
            return plane;
        }

        private void check(byte[] cb, byte[] cr, java.util.function.BiConsumer<byte[], byte[]> scalar,
                           java.util.function.BiConsumer<byte[], byte[]> vector, String name) {
            var expectedCb = cb.clone();
            var expectedCr = cr.clone();
            var actualCb = cb.clone();
            var actualCr = cr.clone();
            scalar.accept(expectedCb, expectedCr);
            vector.accept(actualCb, actualCr);
            assertArrayEquals(expectedCb, actualCb, name + " (Cb)");
            assertArrayEquals(expectedCr, actualCr, name + " (Cr)");
        }

        private void check(byte[] plane, java.util.function.Consumer<byte[]> scalar,
                           java.util.function.Consumer<byte[]> vector, String name) {
            var expected = plane.clone();
            var actual = plane.clone();
            scalar.accept(expected);
            vector.accept(actual);
            assertArrayEquals(expected, actual, name);
        }
    }

    private static int u(byte value) {
        return value & 0xFF;
    }
}
