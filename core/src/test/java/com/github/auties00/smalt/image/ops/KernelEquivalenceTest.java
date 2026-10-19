package com.github.auties00.smalt.image.ops;

import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.image.MatrixCoefficients;
import com.github.auties00.smalt.internal.simd.Simd;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Checks that every Vector API kernel produces exactly the output of its scalar reference on
 * randomized rows of every length from 1 to beyond several vectors, so both the vector loops and
 * their scalar tails are exercised.
 */
@DisplayName("SIMD kernels")
class KernelEquivalenceTest {
    private static final List<MatrixCoefficients> MATRICES = List.of(MatrixCoefficients.BT601, MatrixCoefficients.BT709,
            MatrixCoefficients.BT2020_NCL, MatrixCoefficients.SMPTE240M, MatrixCoefficients.FCC, MatrixCoefficients.YCGCO,
            MatrixCoefficients.IDENTITY);

    @BeforeAll
    static void requireVectors() {
        assumeTrue(Simd.ENABLED, "the Vector API is not enabled");
    }

    private static byte[] bytes(SplittableRandom random, int length) {
        var array = new byte[length];
        random.nextBytes(array);
        return array;
    }

    /** Returns random bytes biased towards the extremes, where clamping and edge cases live. */
    private static byte[] extremeBytes(SplittableRandom random, int length) {
        var array = bytes(random, length);
        for (var i = 0; i < length; i++) {
            switch (random.nextInt(4)) {
                case 0 -> array[i] = 0;
                case 1 -> array[i] = (byte) 255;
                default -> {
                }
            }
        }
        return array;
    }

    @Nested
    @DisplayName("color conversion")
    class Color {
        private final ColorKernel vector = VectorColorKernel.create();
        private final ColorKernel scalar = new ScalarColorKernel();

        @ParameterizedTest(name = "mode {0}")
        @ValueSource(ints = {ColorKernel.CHROMA_FULL, ColorKernel.CHROMA_NEAREST, ColorKernel.CHROMA_FANCY_H, ColorKernel.CHROMA_FANCY_HV})
        @DisplayName("Y'CbCr rows to RGBA match for every mode, matrix, range and width")
        void yuvToRgba(int mode) {
            var random = new SplittableRandom(mode);
            for (var matrix : MATRICES) {
                for (var range : ColorRange.values()) {
                    var m = ColorMatrix.of(matrix, range);
                    for (var width = 1; width <= 90; width++) {
                        var chroma = mode == ColorKernel.CHROMA_FULL ? width : (width + 1) / 2;
                        var y = extremeBytes(random, width + 5);
                        var u = extremeBytes(random, 2 * chroma + 7);
                        var v = extremeBytes(random, 2 * chroma + 7);
                        var alpha = random.nextBoolean() ? bytes(random, width + 3) : null;
                        var expected = new byte[4 * width + 8];
                        var actual = new byte[4 * width + 8];
                        scalar.yuvToRgba(y, 2, u, v, 3, chroma + 5, alpha, 1, expected, 4, width, mode, m);
                        vector.yuvToRgba(y, 2, u, v, 3, chroma + 5, alpha, 1, actual, 4, width, mode, m);
                        assertArrayEquals(expected, actual, matrix + " " + range + " width " + width);
                    }
                }
            }
        }

        @Test
        @DisplayName("gray rows to RGBA match")
        void grayToRgba() {
            var random = new SplittableRandom(7);
            for (var range : ColorRange.values()) {
                var m = ColorMatrix.of(MatrixCoefficients.BT601, range);
                for (var width = 1; width <= 70; width++) {
                    var y = extremeBytes(random, width + 3);
                    var alpha = random.nextBoolean() ? bytes(random, width + 3) : null;
                    var expected = new byte[4 * width + 4];
                    var actual = new byte[4 * width + 4];
                    scalar.grayToRgba(y, 1, alpha, 2, expected, 4, width, m);
                    vector.grayToRgba(y, 1, alpha, 2, actual, 4, width, m);
                    assertArrayEquals(expected, actual, range + " width " + width);
                }
            }
        }

        @Test
        @DisplayName("RGBA rows to luma and alpha match")
        void rgbaToLuma() {
            var random = new SplittableRandom(11);
            for (var matrix : MATRICES) {
                for (var range : ColorRange.values()) {
                    var m = ColorMatrix.of(matrix, range);
                    for (var width = 1; width <= 70; width++) {
                        var src = extremeBytes(random, 4 * width + 12);
                        var expectedY = new byte[width + 9];
                        var actualY = new byte[width + 9];
                        var expectedA = new byte[width + 9];
                        var actualA = new byte[width + 9];
                        scalar.rgbaToLuma(src, 8, width, expectedY, 1, expectedA, 0, m);
                        vector.rgbaToLuma(src, 8, width, actualY, 1, actualA, 0, m);
                        assertArrayEquals(expectedY, actualY, matrix + " " + range + " width " + width);
                        assertArrayEquals(expectedA, actualA, matrix + " " + range + " width " + width);
                    }
                }
            }
        }

        @Test
        @DisplayName("RGBA rows to chroma match for every block shape")
        void rgbaToChroma() {
            var random = new SplittableRandom(13);
            for (var matrix : MATRICES) {
                for (var range : ColorRange.values()) {
                    var m = ColorMatrix.of(matrix, range);
                    for (var shape = 0; shape < 4; shape++) {
                        var pairX = (shape & 1) != 0;
                        var pairY = (shape & 2) != 0;
                        for (var width = 1; width <= 70; width++) {
                            var src = extremeBytes(random, 8 * width + 16);
                            var count = pairX ? (width + 1) / 2 : width;
                            var expectedU = new byte[count + 9];
                            var actualU = new byte[count + 9];
                            var expectedV = new byte[count + 9];
                            var actualV = new byte[count + 9];
                            scalar.rgbaToChroma(src, 4, 4 * width + 8, width, pairX, pairY, expectedU, expectedV, 1, m);
                            vector.rgbaToChroma(src, 4, 4 * width + 8, width, pairX, pairY, actualU, actualV, 1, m);
                            assertArrayEquals(expectedU, actualU, matrix + " " + range + " " + shape + " width " + width);
                            assertArrayEquals(expectedV, actualV, matrix + " " + range + " " + shape + " width " + width);
                        }
                    }
                }
            }
        }
    }

    @Nested
    @DisplayName("resampling")
    class Resample {
        private final ResampleKernel vector = VectorResampleKernel.create();
        private final ScalarResampleKernel scalar = new ScalarResampleKernel();

        @Test
        @DisplayName("vertical passes over 8-bit and 16-bit rows match")
        void vertical() {
            var random = new SplittableRandom(17);
            for (var length = 1; length <= 150; length++) {
                var taps = 1 + random.nextInt(20);
                var src = extremeBytes(random, length * taps + 40);
                var wide = new char[length * taps + 40];
                for (var i = 0; i < wide.length; i++) {
                    wide[i] = (char) (random.nextInt(4) == 0 ? 65025 : random.nextInt(65026));
                }
                var offsets = new int[taps];
                var weights = new int[taps + 2];
                for (var k = 0; k < taps; k++) {
                    offsets[k] = random.nextInt(src.length - length + 1);
                    weights[k + 2] = random.nextInt(1 << 19) - (1 << 17);
                }
                var expected = new int[length];
                var actual = new int[length];
                scalar.vertical(src, offsets, weights, 2, taps, length, expected, 18);
                vector.vertical(src, offsets, weights, 2, taps, length, actual, 18);
                assertArrayEquals(expected, actual, "length " + length);
                for (var k = 0; k < taps; k++) {
                    offsets[k] = random.nextInt(wide.length - length + 1);
                    weights[k + 2] = random.nextInt(1 << 11) - (1 << 9);
                }
                var accumulate = random.nextBoolean();
                var seed = new int[length];
                for (var i = 0; i < length; i++) {
                    seed[i] = random.nextInt(1 << 20) - (1 << 19);
                }
                var shift = random.nextBoolean() ? 14 : 0;
                expected = seed.clone();
                actual = seed.clone();
                scalar.verticalWide(wide, offsets, weights, 2, taps, length, expected, accumulate, shift);
                vector.verticalWide(wide, offsets, weights, 2, taps, length, actual, accumulate, shift);
                assertArrayEquals(expected, actual, "wide length " + length);
            }
        }

        @ParameterizedTest(name = "{0} taps")
        @ValueSource(ints = {1, 3, 4, 5, 8, 9, 16, 17, 40})
        @DisplayName("horizontal passes over lane-interleaved blocks match")
        void horizontal(int maxTaps) {
            var random = new SplittableRandom(maxTaps);
            var lanes = vector.lanes();
            var stride = maxTaps + random.nextInt(3);
            for (var count = 1; count <= 70; count++) {
                var inWidth = maxTaps + random.nextInt(200);
                var src = new int[inWidth * lanes];
                for (var i = 0; i < src.length; i++) {
                    src[i] = random.nextInt(6400) - 1000;
                }
                var starts = new int[count];
                var counts = new int[count];
                var weights = new int[count * stride];
                for (var i = 0; i < count; i++) {
                    counts[i] = 1 + random.nextInt(maxTaps);
                    starts[i] = random.nextInt(inWidth - counts[i] + 1);
                    for (var k = 0; k < counts[i]; k++) {
                        weights[i * stride + k] = random.nextInt(1 << 18) - (1 << 16);
                    }
                }
                var expected = new byte[count * lanes + 8];
                var actual = new byte[count * lanes + 8];
                scalar.horizontalRows(src, lanes, starts, counts, weights, stride, count, expected, 21);
                vector.horizontalRows(src, lanes, starts, counts, weights, stride, count, actual, 21);
                assertArrayEquals(Arrays.copyOf(expected, count * lanes), Arrays.copyOf(actual, count * lanes), "count " + count);
                var expectedWide = new int[count * lanes];
                var actualWide = new int[count * lanes];
                scalar.horizontalRowsWide(src, lanes, starts, counts, weights, stride, count, expectedWide);
                vector.horizontalRowsWide(src, lanes, starts, counts, weights, stride, count, actualWide);
                assertArrayEquals(expectedWide, actualWide, "wide count " + count);
            }
        }

        @Test
        @DisplayName("lane gathering, lane scattering and RGBA interleaving match their definitions")
        void interleaving() {
            var random = new SplittableRandom(19);
            var lanes = vector.lanes();
            for (var width = 1; width <= 90; width++) {
                var rowCount = 1 + random.nextInt(lanes);
                var rows = new int[lanes][4 * width];
                for (var row : rows) {
                    for (var i = 0; i < row.length; i++) {
                        row[i] = random.nextInt();
                    }
                }
                var channel = random.nextInt(4);
                var expected = new int[width * lanes];
                var actual = new int[width * lanes];
                scalar.gatherRows(rows, rowCount, channel, 4, width, lanes, expected);
                vector.gatherRows(rows, rowCount, channel, 4, width, lanes, actual);
                assertArrayEquals(expected, actual, "gather " + width);
                for (var r = 0; r < rowCount; r++) {
                    for (var x = 0; x < width; x++) {
                        assertEquals(rows[r][channel + 4 * x], actual[x * lanes + r]);
                    }
                }
                var block = bytes(random, width * lanes);
                var scattered = new byte[3 + rowCount * (width + 5)];
                ResampleKernel.scatterRows(block, lanes, width, rowCount, scattered, 3, width + 5);
                for (var r = 0; r < rowCount; r++) {
                    for (var x = 0; x < width; x++) {
                        assertEquals(block[x * lanes + r], scattered[3 + r * (width + 5) + x], "scatter " + width);
                    }
                }
                var planes = bytes(random, 4 * width + 5);
                for (var opaque : new boolean[]{false, true}) {
                    var expectedPixels = new byte[4 * width + 8];
                    var actualPixels = new byte[4 * width + 8];
                    scalar.interleave(planes, 5, width, opaque, expectedPixels, 4);
                    vector.interleave(planes, 5, width, opaque, actualPixels, 4);
                    assertArrayEquals(expectedPixels, actualPixels, "interleave " + width);
                }
            }
        }

        @Test
        @DisplayName("premultiplication and exact unpremultiplication match")
        void premultiply() {
            var random = new SplittableRandom(23);
            for (var width = 1; width <= 90; width++) {
                var src = extremeBytes(random, 4 * width + 8);
                var expected = new char[4 * width + 6];
                var actual = new char[4 * width + 6];
                scalar.premultiply(src, 4, width, expected, 2);
                vector.premultiply(src, 4, width, actual, 2);
                assertArrayEquals(expected, actual, "width " + width);
                var sums = new int[4 * width + 5];
                var scale = 1 << Resampler.WIDE_SECOND_BITS;
                for (var x = 0; x < width; x++) {
                    long alpha = switch (random.nextInt(5)) {
                        case 0 -> 0;
                        case 1 -> -random.nextInt(1000);
                        case 2 -> 1 + random.nextInt(50);
                        default -> random.nextLong(1, 800_000_000L);
                    };
                    sums[5 + 3 * width + x] = (int) alpha;
                    for (var c = 0; c < 3; c++) {
                        var value = switch (random.nextInt(4)) {
                            case 0 -> alpha;
                            case 1 -> random.nextLong(-200_000_000L, 200_000_000L);
                            default -> alpha <= 0 ? 0 : random.nextLong(0, alpha + 1);
                        };
                        sums[5 + c * width + x] = (int) value;
                    }
                }
                var expectedPixels = new byte[4 * width + 4];
                var actualPixels = new byte[4 * width + 4];
                scalar.unpremultiply(sums, 5, width, scale, expectedPixels, 4);
                vector.unpremultiply(sums, 5, width, scale, actualPixels, 4);
                assertArrayEquals(expectedPixels, actualPixels, "unpremultiply width " + width);
            }
        }

        @Test
        @DisplayName("exact quotients survive every alpha that can divide into an exact integer")
        void exactQuotients() {
            var scale = 1 << Resampler.WIDE_SECOND_BITS;
            var width = 64;
            var random = new SplittableRandom(29);
            for (var round = 0; round < 2000; round++) {
                var sums = new int[4 * width];
                for (var x = 0; x < width; x++) {
                    var alpha = random.nextInt(1, 900_000_000);
                    // choose colors whose quotient is exactly an integer or exactly half-way
                    var target = random.nextInt(0, 511);
                    var color = (long) target * alpha / 510;
                    sums[x] = (int) color;
                    sums[width + x] = (int) Math.min(Integer.MAX_VALUE, color + random.nextInt(-1, 2));
                    sums[2 * width + x] = (int) (color == 0 ? 0 : color - 1);
                    sums[3 * width + x] = alpha;
                }
                var expected = new byte[4 * width];
                var actual = new byte[4 * width];
                scalar.unpremultiply(sums, 0, width, scale, expected, 0);
                vector.unpremultiply(sums, 0, width, scale, actual, 0);
                assertArrayEquals(expected, actual);
            }
        }
    }

    @Nested
    @DisplayName("double-precision resampling")
    class DoubleResample {
        private final DoubleResampleKernel vector = VectorDoubleResampleKernel.create();
        private final ScalarDoubleResampleKernel scalar = new ScalarDoubleResampleKernel();

        private static double[] doubles(SplittableRandom random, int length) {
            var array = new double[length];
            for (var i = 0; i < length; i++) {
                array[i] = random.nextInt(4) == 0 ? 65535.0 * 65535.0 : random.nextDouble() * 70000 - 2000;
            }
            return array;
        }

        @Test
        @DisplayName("vertical passes match bit for bit, overwriting and accumulating")
        void vertical() {
            var random = new SplittableRandom(23);
            for (var length = 1; length <= 150; length++) {
                var taps = 1 + random.nextInt(20);
                var src = doubles(random, length * taps + 40);
                var offsets = new int[taps];
                var weights = new double[taps + 2];
                for (var k = 0; k < taps; k++) {
                    offsets[k] = random.nextInt(src.length - length + 1);
                    weights[k + 2] = random.nextDouble() * 1.4 - 0.3;
                }
                var accumulate = random.nextBoolean();
                var seed = doubles(random, length);
                var expected = seed.clone();
                var actual = seed.clone();
                scalar.vertical(src, offsets, weights, 2, taps, length, expected, accumulate);
                vector.vertical(src, offsets, weights, 2, taps, length, actual, accumulate);
                for (var i = 0; i < length; i++) {
                    assertEquals(Double.doubleToRawLongBits(expected[i]), Double.doubleToRawLongBits(actual[i]),
                            "length " + length + " value " + i);
                }
            }
        }

        @ParameterizedTest(name = "{0} taps")
        @ValueSource(ints = {1, 2, 3, 8, 9, 17, 40})
        @DisplayName("horizontal passes over lane-interleaved blocks match bit for bit")
        void horizontal(int maxTaps) {
            var random = new SplittableRandom(maxTaps * 3L);
            var lanes = vector.lanes();
            var stride = maxTaps + random.nextInt(3);
            for (var count = 1; count <= 70; count++) {
                var inWidth = maxTaps + random.nextInt(200);
                var src = doubles(random, inWidth * lanes);
                var starts = new int[count];
                var counts = new int[count];
                var weights = new double[count * stride];
                for (var i = 0; i < count; i++) {
                    counts[i] = 1 + random.nextInt(maxTaps);
                    starts[i] = random.nextInt(inWidth - counts[i] + 1);
                    for (var k = 0; k < counts[i]; k++) {
                        weights[i * stride + k] = random.nextDouble() * 1.4 - 0.3;
                    }
                }
                var expected = new double[count * lanes];
                var actual = new double[count * lanes];
                scalar.horizontal(src, lanes, starts, counts, weights, stride, count, expected);
                vector.horizontal(src, lanes, starts, counts, weights, stride, count, actual);
                for (var i = 0; i < expected.length; i++) {
                    assertEquals(Double.doubleToRawLongBits(expected[i]), Double.doubleToRawLongBits(actual[i]),
                            "count " + count + " value " + i);
                }
            }
        }
    }

    @Nested
    @DisplayName("orientation")
    class Transform {
        private final TransformKernel vector = VectorTransformKernel.create();
        private final TransformKernel scalar = new ScalarTransformKernel();

        @Test
        @DisplayName("8-bit and 32-bit transpositions match for every mirroring and size")
        void transpose() {
            var random = new SplittableRandom(31);
            var sizes = new int[]{1, 2, 3, 7, 8, 9, 15, 16, 17, 31, 33, 63, 64, 65, 100, 130};
            for (var width : sizes) {
                for (var height : sizes) {
                    for (var flags = 0; flags < 4; flags++) {
                        var reverseRows = (flags & 1) != 0;
                        var reverseColumns = (flags & 2) != 0;
                        var sStride = width + random.nextInt(4);
                        var src = bytes(random, sStride * height + 3);
                        var dStride = height + random.nextInt(4);
                        var expected = new byte[dStride * width + 5];
                        var actual = new byte[dStride * width + 5];
                        scalar.transpose8(src, 3, sStride, width, height, expected, 5, dStride, reverseRows, reverseColumns);
                        vector.transpose8(src, 3, sStride, width, height, actual, 5, dStride, reverseRows, reverseColumns);
                        assertArrayEquals(expected, actual, width + "x" + height + " flags " + flags);
                        var wideSrc = bytes(random, 4 * sStride * height + 4);
                        var wideExpected = new byte[4 * dStride * width + 4];
                        var wideActual = new byte[4 * dStride * width + 4];
                        scalar.transpose32(wideSrc, 4, 4 * sStride, width, height, wideExpected, 4, 4 * dStride, reverseRows, reverseColumns);
                        vector.transpose32(wideSrc, 4, 4 * sStride, width, height, wideActual, 4, 4 * dStride, reverseRows, reverseColumns);
                        assertArrayEquals(wideExpected, wideActual, "32-bit " + width + "x" + height + " flags " + flags);
                    }
                }
            }
        }

        @Test
        @DisplayName("row reversals match")
        void reverse() {
            var random = new SplittableRandom(37);
            for (var count = 1; count <= 150; count++) {
                var src = bytes(random, 4 * count + 8);
                var expected = new byte[4 * count + 8];
                var actual = new byte[4 * count + 8];
                scalar.reverse8(src, 3, expected, 2, count);
                vector.reverse8(src, 3, actual, 2, count);
                assertArrayEquals(expected, actual, "8-bit " + count);
                scalar.reverse32(src, 4, expected, 4, count);
                vector.reverse32(src, 4, actual, 4, count);
                assertArrayEquals(expected, actual, "32-bit " + count);
            }
        }
    }

    @Nested
    @DisplayName("alpha")
    class Pixel {
        private final PixelKernel vector = VectorPixelKernel.create();
        private final PixelKernel scalar = new ScalarPixelKernel();

        @Test
        @DisplayName("flattening matches")
        void flatten() {
            var random = new SplittableRandom(41);
            for (var width = 1; width <= 90; width++) {
                var src = extremeBytes(random, 4 * width + 8);
                var rgb = random.nextInt(1 << 24);
                var expected = new byte[4 * width + 4];
                var actual = new byte[4 * width + 4];
                scalar.flattenRgba(src, 4, width, expected, 4, rgb);
                vector.flattenRgba(src, 4, width, actual, 4, rgb);
                assertArrayEquals(expected, actual, "rgba " + width);
                var alpha = extremeBytes(random, width + 3);
                var background = random.nextInt(256);
                var expectedPlane = new byte[width + 9];
                var actualPlane = new byte[width + 9];
                scalar.flattenPlane(src, 1, alpha, 2, width, background, expectedPlane, 1);
                vector.flattenPlane(src, 1, alpha, 2, width, background, actualPlane, 1);
                assertArrayEquals(expectedPlane, actualPlane, "plane " + width);
            }
        }

        @Test
        @DisplayName("opacity scans agree, including a single transparent sample anywhere")
        void opacity() {
            var random = new SplittableRandom(43);
            for (var width = 1; width <= 130; width++) {
                var pixels = new byte[4 * width + 8];
                java.util.Arrays.fill(pixels, (byte) 255);
                var plane = new byte[width + 8];
                java.util.Arrays.fill(plane, (byte) 255);
                assertEquals(scalar.isOpaqueRgba(pixels, 4, width), vector.isOpaqueRgba(pixels, 4, width));
                assertEquals(scalar.isOpaquePlane(plane, 4, width), vector.isOpaquePlane(plane, 4, width));
                var position = random.nextInt(width);
                pixels[4 + 4 * position + 3] = (byte) random.nextInt(255);
                plane[4 + position] = (byte) random.nextInt(255);
                assertEquals(false, vector.isOpaqueRgba(pixels, 4, width), "rgba " + width);
                assertEquals(false, vector.isOpaquePlane(plane, 4, width), "plane " + width);
                assertEquals(scalar.isOpaqueRgba(pixels, 4, width), vector.isOpaqueRgba(pixels, 4, width));
                // a color byte of 0 must not be mistaken for transparency
                pixels[4 + 4 * position + 3] = (byte) 255;
                pixels[4 + 4 * position] = 0;
                assertEquals(true, vector.isOpaqueRgba(pixels, 4, width), "color " + width);
            }
        }
    }
}
