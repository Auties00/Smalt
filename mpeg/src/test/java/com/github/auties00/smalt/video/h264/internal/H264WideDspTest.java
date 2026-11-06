package com.github.auties00.smalt.video.h264.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/**
 * Checks the Vector API kernels of pictures deeper than 8 bits against their scalar twins on
 * random planes of every bit depth from 9 to 14, every block size and every fractional position,
 * including planes whose samples sit at the ends of their range.
 */
@DisplayName("H.264 high bit depth DSP kernels")
class H264WideDspTest {
    private static final H264WideDsp SCALAR = new ScalarH264WideDsp();
    private static final H264WideDsp VECTOR = new VectorH264WideDsp();
    private static final int STRIDE = 64;

    private static short[] randomPlane(Random random, int size, int depth) {
        var plane = new short[size];
        var max = (1 << depth) - 1;
        var mode = random.nextInt(3);
        for (var i = 0; i < size; i++) {
            plane[i] = (short) switch (mode) {
                case 0 -> random.nextInt(max + 1);
                case 1 -> random.nextBoolean() ? max : 0;
                default -> Math.clamp((long) ((max / 2) + (max / 2.2) * Math.sin(i * 0.09)), 0, max);
            };
        }
        return plane;
    }

    private static short[] randomBlock(Random random, int size) {
        var block = new short[size];
        for (var i = 0; i < size; i++) {
            block[i] = (short) random.nextInt(1 << 16);
        }
        return block;
    }

    @ParameterizedTest(name = "{0} bits")
    @ValueSource(ints = {9, 10, 11, 12, 13, 14})
    @DisplayName("vector luma interpolation equals scalar for every fraction and size")
    void luma(int depth) {
        var random = new Random(depth);
        var max = (1 << depth) - 1;
        for (var trial = 0; trial < 4; trial++) {
            var src = randomPlane(random, STRIDE * 40, depth);
            for (var width : new int[]{4, 8, 16}) {
                for (var height : new int[]{4, 8, 16}) {
                    for (var fraction = 0; fraction < 16; fraction++) {
                        var expected = randomBlock(random, STRIDE * 20);
                        var actual = expected.clone();
                        var offset = 4 * STRIDE + 8 + random.nextInt(8);
                        SCALAR.lumaMc(src, offset, STRIDE, expected, STRIDE + 3, STRIDE, width, height, fraction & 3,
                                fraction >> 2, new int[H264WideDsp.LUMA_SCRATCH_INTS],
                                new short[H264WideDsp.LUMA_SCRATCH_SHORTS], max);
                        VECTOR.lumaMc(src, offset, STRIDE, actual, STRIDE + 3, STRIDE, width, height, fraction & 3,
                                fraction >> 2, new int[H264WideDsp.LUMA_SCRATCH_INTS],
                                new short[H264WideDsp.LUMA_SCRATCH_SHORTS], max);
                        assertArrayEquals(expected, actual, depth + "-bit luma " + width + "x" + height + " fraction "
                                + fraction);
                    }
                }
            }
        }
    }

    @ParameterizedTest(name = "{0} bits")
    @ValueSource(ints = {9, 10, 12, 14})
    @DisplayName("vector two-plane chroma interpolation equals scalar for every fraction and size")
    void chroma(int depth) {
        var random = new Random(100 + depth);
        var cb = randomPlane(random, STRIDE * 24, depth);
        var cr = randomPlane(random, STRIDE * 24, depth);
        for (var width : new int[]{2, 4, 8}) {
            for (var height : new int[]{2, 4, 8}) {
                for (var fraction = 0; fraction < 64; fraction++) {
                    var expectedCb = randomBlock(random, STRIDE * 12);
                    var expectedCr = randomBlock(random, STRIDE * 12);
                    var actualCb = expectedCb.clone();
                    var actualCr = expectedCr.clone();
                    var offset = 2 * STRIDE + 4 + random.nextInt(8);
                    SCALAR.chromaMc(cb, cr, offset, STRIDE, expectedCb, expectedCr, STRIDE + 1, STRIDE, width, height,
                            fraction & 7, fraction >> 3);
                    VECTOR.chromaMc(cb, cr, offset, STRIDE, actualCb, actualCr, STRIDE + 1, STRIDE, width, height,
                            fraction & 7, fraction >> 3);
                    assertArrayEquals(expectedCb, actualCb, "cb " + width + "x" + height + " fraction " + fraction);
                    assertArrayEquals(expectedCr, actualCr, "cr " + width + "x" + height + " fraction " + fraction);
                }
            }
        }
    }

    @ParameterizedTest(name = "{0} bits")
    @ValueSource(ints = {9, 14})
    @DisplayName("vector averages equal scalar for every block width")
    void averages(int depth) {
        var random = new Random(200 + depth);
        for (var width : new int[]{2, 4, 8, 16}) {
            for (var height : new int[]{2, 4, 8, 16}) {
                var a = randomPlane(random, STRIDE * 20, depth);
                var b = randomPlane(random, STRIDE * 20, depth);
                var expected = randomBlock(random, STRIDE * 20);
                var actual = expected.clone();
                SCALAR.averageBlocks(a, 3, STRIDE, b, 5, STRIDE, expected, 7, STRIDE, width, height);
                VECTOR.averageBlocks(a, 3, STRIDE, b, 5, STRIDE, actual, 7, STRIDE, width, height);
                assertArrayEquals(expected, actual, "averageBlocks " + width + "x" + height);
                var expectedInPlace = a.clone();
                var actualInPlace = a.clone();
                SCALAR.average(expectedInPlace, 9, STRIDE, b, 2, STRIDE, width, height);
                VECTOR.average(actualInPlace, 9, STRIDE, b, 2, STRIDE, width, height);
                assertArrayEquals(expectedInPlace, actualInPlace, "average " + width + "x" + height);
            }
        }
    }
}
