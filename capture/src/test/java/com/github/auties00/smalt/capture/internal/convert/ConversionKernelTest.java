package com.github.auties00.smalt.capture.internal.convert;

import com.github.auties00.smalt.testkit.Skip;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ColorPrimaries;
import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.image.MatrixCoefficients;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.TransferCharacteristics;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.internal.simd.Simd;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.SplittableRandom;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Checks the capture conversion kernels: the scalar kernel bit for bit against {@link ImageOps#toYuv}
 * for every luma-weighted matrix, range and pixel layout, and the vector kernel byte for byte against
 * the scalar one on random rows of every length up to several vectors, at unaligned offsets.
 */
@DisplayName("capture conversion kernels")
class ConversionKernelTest {
    private static final List<RgbLayout> LAYOUTS = List.of(RgbLayout.BGRX, new RgbLayout(0, 1, 2),
            new RgbLayout(1, 2, 3), new RgbLayout(3, 2, 1), new RgbLayout(0, 2, 1), new RgbLayout(3, 0, 2));

    private static byte[] bytes(SplittableRandom random, int length) {
        var array = new byte[length];
        random.nextBytes(array);
        for (var i = 0; i < length; i++) {
            switch (random.nextInt(6)) {
                case 0 -> array[i] = 0;
                case 1 -> array[i] = (byte) 255;
                default -> {
                }
            }
        }
        return array;
    }

    static Stream<Arguments> colors() {
        return Stream.of(MatrixCoefficients.BT601, MatrixCoefficients.BT470BG, MatrixCoefficients.BT709,
                        MatrixCoefficients.FCC, MatrixCoefficients.SMPTE240M, MatrixCoefficients.BT2020_NCL)
                .flatMap(matrix -> Stream.of(ColorRange.LIMITED, ColorRange.FULL)
                        .flatMap(range -> LAYOUTS.stream().map(layout -> Arguments.of(
                                new ColorInfo(matrix, range, ColorPrimaries.BT709, TransferCharacteristics.BT709), layout))));
    }

    private static ConversionKernel[] kernels() {
        return Simd.ENABLED
                ? new ConversionKernel[]{new ScalarConversionKernel(), VectorConversionKernel.create()}
                : new ConversionKernel[]{new ScalarConversionKernel()};
    }

    @Nested
    @DisplayName("R'G'B' conversion")
    class Rgb {
        @ParameterizedTest(name = "{0}, {1}")
        @MethodSource("com.github.auties00.smalt.capture.internal.convert.ConversionKernelTest#colors")
        @DisplayName("matches ImageOps' 4:2:0 conversion bit for bit")
        void matchesImageOps(ColorInfo color, RgbLayout layout) {
            var width = 98;
            var height = 34;
            var random = new SplittableRandom(color.hashCode() * 31L + layout.hashCode());
            var pixels = bytes(random, 4 * width * height);
            var rgba = RgbaPicture.allocate(width, height, false);
            for (var i = 0; i < width * height; i++) {
                rgba.pixels()[4 * i] = pixels[4 * i + layout.rIndex()];
                rgba.pixels()[4 * i + 1] = pixels[4 * i + layout.gIndex()];
                rgba.pixels()[4 * i + 2] = pixels[4 * i + layout.bIndex()];
                rgba.pixels()[4 * i + 3] = (byte) 255;
            }
            var reference = ImageOps.toYuv(rgba, ChromaFormat.YUV420, color);
            var coefficients = YuvCoefficients.of(color);
            for (var kernel : kernels()) {
                var out = YuvPicture.allocate(width, height, ChromaFormat.YUV420, false, color);
                for (var row = 0; row < height; row += 2) {
                    var upper = row * 4 * width;
                    var lower = upper + 4 * width;
                    kernel.rgbToLuma(pixels, upper, width, layout, coefficients, out.y(), row * out.yStride());
                    kernel.rgbToLuma(pixels, lower, width, layout, coefficients, out.y(), (row + 1) * out.yStride());
                    kernel.rgbToChroma(pixels, upper, lower, width / 2, layout, coefficients, out.u(), out.v(),
                            row / 2 * out.uvStride());
                }
                assertPlanes(reference, out, kernel.getClass().getSimpleName());
            }
        }

        @Test
        @DisplayName("black, white and the primaries land on the BT.709 limited-range code values")
        void codeValues() {
            var kernel = new ScalarConversionKernel();
            int[][] colors = {{0, 0, 0, 16, 128, 128}, {255, 255, 255, 235, 128, 128}, {255, 0, 0, 63, 102, 240},
                    {0, 255, 0, 173, 42, 26}, {0, 0, 255, 32, 240, 118}};
            for (var color : colors) {
                var pixel = new byte[16];
                for (var i = 0; i < 4; i++) {
                    pixel[4 * i] = (byte) color[2];
                    pixel[4 * i + 1] = (byte) color[1];
                    pixel[4 * i + 2] = (byte) color[0];
                }
                var y = new byte[2];
                var u = new byte[1];
                var v = new byte[1];
                kernel.rgbToLuma(pixel, 0, 2, RgbLayout.BGRX, YuvCoefficients.BT709_LIMITED, y, 0);
                kernel.rgbToChroma(pixel, 0, 8, 1, RgbLayout.BGRX, YuvCoefficients.BT709_LIMITED, u, v, 0);
                assertEquals(color[3], y[0] & 0xFF, "luma of " + color[0] + "," + color[1] + "," + color[2]);
                assertEquals(color[4], u[0] & 0xFF, "cb of " + color[0] + "," + color[1] + "," + color[2]);
                assertEquals(color[5], v[0] & 0xFF, "cr of " + color[0] + "," + color[1] + "," + color[2]);
            }
        }

        @Test
        @DisplayName("BT.709 limited-range coefficients keep their fixed-point values")
        void bt709Coefficients() {
            var c = YuvCoefficients.BT709_LIMITED;
            assertArrayEquals(new int[]{11966, 40254, 4064, -6596, -22188, 28784, 28784, -26145, -2639},
                    new int[]{c.yr, c.yg, c.yb, c.ur, c.ug, c.ub, c.vr, c.vg, c.vb});
            assertEquals(16, c.black());
            assertEquals(ColorInfo.BT709_LIMITED, c.color());
        }

        @Test
        @DisplayName("an unspecified matrix gets the BT.601 coefficients")
        void unspecifiedIsBt601() {
            var unspecified = YuvCoefficients.of(new ColorInfo(MatrixCoefficients.UNSPECIFIED, ColorRange.LIMITED,
                    ColorPrimaries.BT709, TransferCharacteristics.BT709));
            var bt601 = YuvCoefficients.of(ColorInfo.BT601_LIMITED);
            assertArrayEquals(new int[]{bt601.yr, bt601.yg, bt601.yb, bt601.ur, bt601.ug, bt601.ub, bt601.vr, bt601.vg,
                    bt601.vb}, new int[]{unspecified.yr, unspecified.yg, unspecified.yb, unspecified.ur, unspecified.ug,
                    unspecified.ub, unspecified.vr, unspecified.vg, unspecified.vb});
        }

        @ParameterizedTest
        @EnumSource(value = MatrixCoefficients.class, names = {"IDENTITY", "YCGCO", "BT2020_CL", "ICTCP"})
        @DisplayName("matrices two luma weights do not define are rejected")
        void rejectsOtherMatrices(MatrixCoefficients matrix) {
            var color = new ColorInfo(matrix, ColorRange.LIMITED, ColorPrimaries.BT709, TransferCharacteristics.BT709);
            assertThrows(IllegalArgumentException.class, () -> YuvCoefficients.of(color));
        }

        private static void assertPlanes(YuvPicture expected, YuvPicture actual, String kernel) {
            var width = actual.width();
            var height = actual.height();
            for (var row = 0; row < height; row++) {
                for (var x = 0; x < width; x++) {
                    assertEquals(expected.y()[row * expected.yStride() + x], actual.y()[row * actual.yStride() + x],
                            kernel + " luma " + x + "," + row);
                }
            }
            for (var row = 0; row < height / 2; row++) {
                for (var x = 0; x < width / 2; x++) {
                    assertEquals(expected.u()[row * expected.uvStride() + x], actual.u()[row * actual.uvStride() + x],
                            kernel + " cb " + x + "," + row);
                    assertEquals(expected.v()[row * expected.uvStride() + x], actual.v()[row * actual.uvStride() + x],
                            kernel + " cr " + x + "," + row);
                }
            }
        }
    }

    @Nested
    @DisplayName("Y'CbCr rows")
    class Yuv {
        private final ScalarConversionKernel scalar = new ScalarConversionKernel();

        @Test
        @DisplayName("packed 4:2:2 rows split into luma and averaged chroma")
        void packed() {
            byte[] yuyv = {10, 100, 20, (byte) 200, 30, 101, 40, (byte) 201, 11, 102, 21, (byte) 202, 31, 104, 41, (byte) 205};
            var y = new byte[4];
            var u = new byte[2];
            var v = new byte[2];
            scalar.packedLuma(yuyv, 0, 4, 0, y, 0);
            scalar.packedChroma(yuyv, 0, 8, 2, 1, 3, u, v, 0);
            assertArrayEquals(new byte[]{10, 20, 30, 40}, y);
            assertArrayEquals(new byte[]{101, 103}, u);
            assertArrayEquals(new byte[]{(byte) 201, (byte) 203}, v);
        }

        @Test
        @DisplayName("interleaved pairs split and rows average rounding half up")
        void deinterleaveAndAverage() {
            var first = new byte[2];
            var second = new byte[2];
            scalar.deinterleave(new byte[]{1, 2, 3, 4}, 0, 2, first, second, 0);
            assertArrayEquals(new byte[]{1, 3}, first);
            assertArrayEquals(new byte[]{2, 4}, second);
            var average = new byte[3];
            scalar.averageRows(new byte[]{0, (byte) 255, 7}, 0, new byte[]{1, (byte) 254, 8}, 0, 3, average, 0);
            assertArrayEquals(new byte[]{1, (byte) 255, 8}, average);
        }
    }

    @Test
    @DisplayName("the ahead-of-time training round runs every kernel of this JVM")
    void trainingRound() {
        CaptureTrainingRound.round();
        CaptureTrainingRound.round();
    }

    @Nested
    @DisplayName("Vector API equals scalar")
    class Equivalence {
        private final ScalarConversionKernel scalar = new ScalarConversionKernel();

        private ConversionKernel vector() {
            Skip.unlessVector(Simd.ENABLED);
            return VectorConversionKernel.create();
        }

        @Test
        @DisplayName("packed luma for every width and luma position")
        void packedLuma() {
            var vector = vector();
            var random = new SplittableRandom(1);
            for (var width = 2; width <= 160; width += 2) {
                for (var lumaIndex = 0; lumaIndex <= 1; lumaIndex++) {
                    var src = bytes(random, 2 * width + 9);
                    var expected = new byte[width + 7];
                    var actual = new byte[width + 7];
                    scalar.packedLuma(src, 3, width, lumaIndex, expected, 2);
                    vector.packedLuma(src, 3, width, lumaIndex, actual, 2);
                    assertArrayEquals(expected, actual, "width " + width + " luma " + lumaIndex);
                }
            }
        }

        @Test
        @DisplayName("packed chroma for every width and chroma position")
        void packedChroma() {
            var vector = vector();
            var random = new SplittableRandom(2);
            int[][] positions = {{1, 3}, {3, 1}, {0, 2}, {2, 0}};
            for (var pairs = 1; pairs <= 80; pairs++) {
                for (var position : positions) {
                    var stride = 4 * pairs + 5;
                    var src = bytes(random, 2 * stride + 4);
                    var expectedU = new byte[pairs + 9];
                    var expectedV = new byte[pairs + 9];
                    var actualU = new byte[pairs + 9];
                    var actualV = new byte[pairs + 9];
                    scalar.packedChroma(src, 1, 1 + stride, pairs, position[0], position[1], expectedU, expectedV, 4);
                    vector.packedChroma(src, 1, 1 + stride, pairs, position[0], position[1], actualU, actualV, 4);
                    assertArrayEquals(expectedU, actualU, "cb, " + pairs + " pairs");
                    assertArrayEquals(expectedV, actualV, "cr, " + pairs + " pairs");
                }
            }
        }

        @Test
        @DisplayName("de-interleaving for every count")
        void deinterleave() {
            var vector = vector();
            var random = new SplittableRandom(3);
            for (var count = 1; count <= 120; count++) {
                var src = bytes(random, 2 * count + 3);
                var expectedFirst = new byte[count + 2];
                var expectedSecond = new byte[count + 2];
                var actualFirst = new byte[count + 2];
                var actualSecond = new byte[count + 2];
                scalar.deinterleave(src, 1, count, expectedFirst, expectedSecond, 1);
                vector.deinterleave(src, 1, count, actualFirst, actualSecond, 1);
                assertArrayEquals(expectedFirst, actualFirst, "first, count " + count);
                assertArrayEquals(expectedSecond, actualSecond, "second, count " + count);
            }
        }

        @Test
        @DisplayName("row averaging for every count, from one array and from two")
        void averageRows() {
            var vector = vector();
            var random = new SplittableRandom(4);
            for (var count = 1; count <= 150; count++) {
                var src = bytes(random, 2 * count + 11);
                var other = bytes(random, count + 5);
                var expected = new byte[count + 3];
                var actual = new byte[count + 3];
                scalar.averageRows(src, 2, src, count + 7, count, expected, 3);
                vector.averageRows(src, 2, src, count + 7, count, actual, 3);
                assertArrayEquals(expected, actual, "count " + count);
                scalar.averageRows(src, 1, other, 4, count, expected, 0);
                vector.averageRows(src, 1, other, 4, count, actual, 0);
                assertArrayEquals(expected, actual, "two arrays, count " + count);
            }
        }

        @Test
        @DisplayName("R'G'B' luma and chroma for every width, layout and matrix")
        void rgb() {
            var vector = vector();
            var random = new SplittableRandom(5);
            var coefficients = colors().map(arguments -> (ColorInfo) arguments.get()[0]).distinct()
                    .map(YuvCoefficients::of).toList();
            for (var width = 2; width <= 130; width += 2) {
                for (var layout : LAYOUTS) {
                    var c = coefficients.get(random.nextInt(coefficients.size()));
                    var stride = 4 * width + 12;
                    var src = bytes(random, 2 * stride + 8);
                    var expectedY = new byte[width + 5];
                    var actualY = new byte[width + 5];
                    scalar.rgbToLuma(src, 4, width, layout, c, expectedY, 1);
                    vector.rgbToLuma(src, 4, width, layout, c, actualY, 1);
                    assertArrayEquals(expectedY, actualY, "luma, width " + width + ", " + layout + ", " + c);
                    var pairs = width / 2;
                    var expectedU = new byte[pairs + 3];
                    var expectedV = new byte[pairs + 3];
                    var actualU = new byte[pairs + 3];
                    var actualV = new byte[pairs + 3];
                    scalar.rgbToChroma(src, 4, 4 + stride, pairs, layout, c, expectedU, expectedV, 2);
                    vector.rgbToChroma(src, 4, 4 + stride, pairs, layout, c, actualU, actualV, 2);
                    assertArrayEquals(expectedU, actualU, "cb, width " + width + ", " + layout + ", " + c);
                    assertArrayEquals(expectedV, actualV, "cr, width " + width + ", " + layout + ", " + c);
                }
            }
        }
    }
}
