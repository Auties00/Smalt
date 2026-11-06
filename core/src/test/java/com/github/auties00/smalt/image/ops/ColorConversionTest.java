package com.github.auties00.smalt.image.ops;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ColorPrimaries;
import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.image.MatrixCoefficients;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.TransferCharacteristics;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.testkit.Quality;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers RGBA and Y'CbCr conversion against an independent double-precision implementation of
 * the ITU-T H.273 equations, libjpeg's upsampling written out literally, and ffmpeg.
 */
@DisplayName("Color conversion")
class ColorConversionTest {
    static final List<MatrixCoefficients> SUPPORTED = List.of(MatrixCoefficients.BT601, MatrixCoefficients.BT709,
            MatrixCoefficients.BT470BG, MatrixCoefficients.SMPTE240M, MatrixCoefficients.BT2020_NCL, MatrixCoefficients.FCC,
            MatrixCoefficients.YCGCO, MatrixCoefficients.IDENTITY, MatrixCoefficients.UNSPECIFIED);

    static ColorInfo color(MatrixCoefficients matrix, ColorRange range) {
        return new ColorInfo(matrix, range, ColorPrimaries.BT709, TransferCharacteristics.BT709);
    }

    static Stream<Arguments> matricesAndRanges() {
        var arguments = new ArrayList<Arguments>();
        for (var matrix : SUPPORTED) {
            for (var range : ColorRange.values()) {
                arguments.add(Arguments.of(matrix, range));
            }
        }
        return arguments.stream();
    }

    /** The real-valued forward transform of H.273, written independently of the code under test. */
    static double[] forward(MatrixCoefficients matrix, ColorRange range, int r, int g, int b) {
        var full = range == ColorRange.FULL;
        double er = r / 255.0;
        double eg = g / 255.0;
        double eb = b / 255.0;
        double ey;
        double epb;
        double epr;
        switch (matrix) {
            case YCGCO -> {
                ey = 0.25 * er + 0.5 * eg + 0.25 * eb;
                epb = -0.25 * er + 0.5 * eg - 0.25 * eb;
                epr = 0.5 * er - 0.5 * eb;
                var scale = full ? 255 : 219;
                return new double[]{scale * ey + (full ? 0 : 16), scale * epb + 128, scale * epr + 128};
            }
            case IDENTITY -> {
                var scale = full ? 255 : 219;
                var offset = full ? 0 : 16;
                return new double[]{scale * eg + offset, scale * eb + offset, scale * er + offset};
            }
            default -> {
                double kr;
                double kb;
                switch (matrix) {
                    case BT709 -> {
                        kr = 0.2126;
                        kb = 0.0722;
                    }
                    case FCC -> {
                        kr = 0.30;
                        kb = 0.11;
                    }
                    case SMPTE240M -> {
                        kr = 0.212;
                        kb = 0.087;
                    }
                    case BT2020_NCL -> {
                        kr = 0.2627;
                        kb = 0.0593;
                    }
                    default -> {
                        kr = 0.299;
                        kb = 0.114;
                    }
                }
                ey = kr * er + (1 - kr - kb) * eg + kb * eb;
                epb = (eb - ey) / (2 * (1 - kb));
                epr = (er - ey) / (2 * (1 - kr));
                return full
                        ? new double[]{255 * ey, 255 * epb + 128, 255 * epr + 128}
                        : new double[]{219 * ey + 16, 224 * epb + 128, 224 * epr + 128};
            }
        }
    }

    /** Asserts that an integer is the rounding of a real, accepting either neighbor near a tie. */
    static void assertRounds(double real, int actual, String message) {
        var clamped = Math.max(0, Math.min(255, real));
        var fraction = clamped - Math.floor(clamped);
        if (Math.abs(fraction - 0.5) < 2e-3) {
            assertTrue(actual == (int) Math.floor(clamped) || actual == (int) Math.floor(clamped) + 1,
                    message + ": " + actual + " is not a rounding of " + real);
        } else {
            assertEquals((int) Math.round(clamped), actual, message + " (real " + real + ")");
        }
    }

    static RgbaPicture pixel(int r, int g, int b) {
        return RgbaPicture.of(1, 1, 4, new byte[]{(byte) r, (byte) g, (byte) b, (byte) 255}, false);
    }

    @Nested
    @DisplayName("exact values")
    class Exact {
        static final int[][] COLORS = {
                {0, 0, 0}, {255, 255, 255}, {255, 0, 0}, {0, 255, 0}, {0, 0, 255}, {0, 255, 255}, {255, 0, 255},
                {255, 255, 0}, {1, 1, 1}, {16, 16, 16}, {127, 127, 127}, {128, 128, 128}, {200, 200, 200}, {254, 254, 254},
                {235, 235, 235}, {12, 34, 56}
        };

        @ParameterizedTest(name = "{0} {1}")
        @MethodSource("com.github.auties00.smalt.image.ops.ColorConversionTest#matricesAndRanges")
        @DisplayName("RGB to Y'CbCr of pure colors and grays equals the H.273 equations")
        void forwardValues(MatrixCoefficients matrix, ColorRange range) {
            for (var color : COLORS) {
                var yuv = ImageOps.toYuv(pixel(color[0], color[1], color[2]), ChromaFormat.YUV444, color(matrix, range));
                var expected = forward(matrix, range, color[0], color[1], color[2]);
                var name = matrix + " " + range + " " + java.util.Arrays.toString(color);
                assertRounds(expected[0], yuv.y()[0] & 0xFF, name + " Y");
                assertRounds(expected[1], yuv.u()[0] & 0xFF, name + " Cb");
                assertRounds(expected[2], yuv.v()[0] & 0xFF, name + " Cr");
            }
        }

        @ParameterizedTest(name = "{0} {1}")
        @MethodSource("com.github.auties00.smalt.image.ops.ColorConversionTest#matricesAndRanges")
        @DisplayName("grays are exact in both directions")
        void grays(MatrixCoefficients matrix, ColorRange range) {
            var full = range == ColorRange.FULL;
            for (var v = 0; v < 256; v++) {
                var yuv = ImageOps.toYuv(pixel(v, v, v), ChromaFormat.YUV444, color(matrix, range));
                var expected = forward(matrix, range, v, v, v);
                assertRounds(expected[0], yuv.y()[0] & 0xFF, "gray " + v);
                if (matrix != MatrixCoefficients.IDENTITY) {
                    assertEquals(128, yuv.u()[0] & 0xFF);
                    assertEquals(128, yuv.v()[0] & 0xFF);
                }
                if (full) {
                    assertEquals(v, yuv.y()[0] & 0xFF, "full-range gray luma");
                    var back = ImageOps.toRgba(yuv);
                    assertArrayEquals(new byte[]{(byte) v, (byte) v, (byte) v, (byte) 255}, back.pixels(), "gray " + v);
                }
            }
            if (!full && matrix != MatrixCoefficients.IDENTITY) {
                for (var luma = 16; luma <= 235; luma++) {
                    var yuv = YuvPicture.allocate(1, 1, ChromaFormat.YUV444, false, color(matrix, range));
                    yuv.y()[0] = (byte) luma;
                    var rgb = ImageOps.toRgba(yuv).pixels();
                    var real = (luma - 16) * 255.0 / 219.0;
                    assertRounds(real, rgb[0] & 0xFF, "limited gray " + luma);
                    assertEquals(rgb[0], rgb[1]);
                    assertEquals(rgb[0], rgb[2]);
                }
            }
        }

        @ParameterizedTest(name = "{0} {1}")
        @MethodSource("com.github.auties00.smalt.image.ops.ColorConversionTest#matricesAndRanges")
        @DisplayName("Y'CbCr to RGB inverts the H.273 equations")
        void inverseValues(MatrixCoefficients matrix, ColorRange range) {
            var random = new SplittableRandom(matrix.ordinal() * 2L + range.ordinal());
            for (var i = 0; i < 400; i++) {
                var r = random.nextInt(256);
                var g = random.nextInt(256);
                var b = random.nextInt(256);
                var real = forward(matrix, range, r, g, b);
                var yuv = YuvPicture.allocate(1, 1, ChromaFormat.YUV444, false, color(matrix, range));
                yuv.y()[0] = (byte) Math.round(real[0]);
                yuv.u()[0] = (byte) Math.round(real[1]);
                yuv.v()[0] = (byte) Math.round(real[2]);
                var rgb = ImageOps.toRgba(yuv).pixels();
                var inverse = invert(matrix, range, yuv.y()[0] & 0xFF, yuv.u()[0] & 0xFF, yuv.v()[0] & 0xFF);
                for (var c = 0; c < 3; c++) {
                    assertRounds(inverse[c], rgb[c] & 0xFF, matrix + " " + range + " channel " + c);
                }
            }
        }

        /** Inverts {@link #forward} numerically with Cramer's rule on the unit basis. */
        private static double[] invert(MatrixCoefficients matrix, ColorRange range, int y, int u, int v) {
            var origin = forward(matrix, range, 0, 0, 0);
            var columns = new double[3][];
            columns[0] = forward(matrix, range, 255, 0, 0);
            columns[1] = forward(matrix, range, 0, 255, 0);
            columns[2] = forward(matrix, range, 0, 0, 255);
            var m = new double[3][3];
            for (var row = 0; row < 3; row++) {
                for (var column = 0; column < 3; column++) {
                    m[row][column] = (columns[column][row] - origin[row]) / 255.0;
                }
            }
            var target = new double[]{y - origin[0], u - origin[1], v - origin[2]};
            var determinant = det(m);
            var result = new double[3];
            for (var column = 0; column < 3; column++) {
                var replaced = new double[3][3];
                for (var row = 0; row < 3; row++) {
                    replaced[row] = m[row].clone();
                    replaced[row][column] = target[row];
                }
                result[column] = det(replaced) / determinant;
            }
            return result;
        }

        private static double det(double[][] m) {
            return m[0][0] * (m[1][1] * m[2][2] - m[1][2] * m[2][1])
                    - m[0][1] * (m[1][0] * m[2][2] - m[1][2] * m[2][0])
                    + m[0][2] * (m[1][0] * m[2][1] - m[1][1] * m[2][0]);
        }

        @Test
        @DisplayName("full-range BT.601 uses libjpeg-turbo's exact constants")
        void libjpegConstants() {
            var m = ColorMatrix.of(MatrixCoefficients.BT601, ColorRange.FULL);
            assertArrayEquals(new int[]{19595, 38470, 7471}, m.fy);
            assertArrayEquals(new int[]{-11059, -21709, 32768}, m.fu);
            assertArrayEquals(new int[]{32768, -27439, -5329}, m.fv);
            assertEquals(91881, m.ir[2]);
            assertEquals(-22554, m.ig[1]);
            assertEquals(-46802, m.ig[2]);
            assertEquals(116130, m.ib[1]);
        }

        @Test
        @DisplayName("unspecified matrices convert as BT.601 and every other matrix converts")
        void everyMatrix() {
            var picture = pixel(90, 160, 120);
            var bt601 = ImageOps.toYuv(picture, ChromaFormat.YUV444, color(MatrixCoefficients.BT601, ColorRange.LIMITED));
            var unspecified = ImageOps.toYuv(picture, ChromaFormat.YUV444, color(MatrixCoefficients.UNSPECIFIED, ColorRange.LIMITED));
            assertArrayEquals(Pictures.raw(bt601), Pictures.raw(unspecified));
            for (var matrix : MatrixCoefficients.values()) {
                var yuv = ImageOps.toYuv(picture, ChromaFormat.YUV420, color(matrix, ColorRange.FULL));
                assertEquals(matrix, yuv.color().matrix());
                var back = ImageOps.toRgba(yuv);
                assertEquals(1, back.width());
                assertEquals(160, back.pixels()[1] & 0xFF, 4, matrix.toString());
                var empty = YuvPicture.allocate(2, 2, ChromaFormat.YUV420, false, color(matrix, ColorRange.FULL));
                assertEquals(2, ImageOps.toRgba(empty).width());
            }
        }
    }

    @Nested
    @DisplayName("round trips")
    class RoundTrip {
        @ParameterizedTest(name = "{0} {1}")
        @MethodSource("com.github.auties00.smalt.image.ops.ColorConversionTest#matricesAndRanges")
        @DisplayName("RGBA -> 4:4:4 -> RGBA stays within 1 (full) or 2 (limited)")
        void rgbaYuvRgba(MatrixCoefficients matrix, ColorRange range) {
            var random = new SplittableRandom(99);
            var picture = Pictures.randomRgba(random, 301, 67, true);
            var yuv = ImageOps.toYuv(picture, ChromaFormat.YUV444, color(matrix, range));
            assertTrue(yuv.hasAlpha());
            var back = ImageOps.toRgba(yuv);
            var tolerance = range == ColorRange.FULL ? 1 : 2;
            if (matrix == MatrixCoefficients.YCGCO) {
                // 8-bit YCgCo has no spare bit for the lossless YCgCo-R lifting, so it can lose 2
                tolerance = 2;
            }
            assertTrue(Quality.maxDifference(Pictures.tight(picture), Pictures.tight(back)) <= tolerance,
                    "max difference " + Quality.maxDifference(Pictures.tight(picture), Pictures.tight(back)));
            if (matrix == MatrixCoefficients.IDENTITY && range == ColorRange.FULL) {
                assertArrayEquals(Pictures.tight(picture), Pictures.tight(back));
            }
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(value = ChromaFormat.class, names = {"YUV420", "YUV422"})
        @DisplayName("subsampling preserves flat color regions exactly")
        void flatRegions(ChromaFormat format) {
            var picture = RgbaPicture.allocate(9, 7, false);
            for (var i = 0; i < 9 * 7; i++) {
                picture.pixels()[4 * i] = (byte) 180;
                picture.pixels()[4 * i + 1] = (byte) 40;
                picture.pixels()[4 * i + 2] = (byte) 90;
                picture.pixels()[4 * i + 3] = (byte) 255;
            }
            var yuv = ImageOps.toYuv(picture, format, ColorInfo.JPEG);
            var reference = ImageOps.toYuv(pixel(180, 40, 90), ChromaFormat.YUV444, ColorInfo.JPEG);
            for (var i = 0; i < yuv.chromaWidth() * yuv.chromaHeight(); i++) {
                assertEquals(reference.u()[0], yuv.u()[i]);
                assertEquals(reference.v()[0], yuv.v()[i]);
            }
            for (var upsampling : ChromaUpsampling.values()) {
                var back = ImageOps.toRgba(yuv, upsampling);
                var single = ImageOps.toRgba(reference).pixels();
                for (var i = 0; i < 9 * 7; i++) {
                    for (var c = 0; c < 4; c++) {
                        assertEquals(single[c], back.pixels()[4 * i + c]);
                    }
                }
            }
        }
    }

    @Nested
    @DisplayName("chroma resampling")
    class Chroma {
        /** libjpeg-turbo's jdsample.c h2v1_fancy_upsample, transcribed with its edge cases. */
        static int[] h2v1(int[] in, int outWidth) {
            var n = in.length;
            var out = new int[2 * n];
            if (n == 1) {
                out[0] = in[0];
                out[1] = in[0];
            } else {
                out[0] = in[0];
                out[1] = (in[0] * 3 + in[1] + 2) >> 2;
                for (var i = 1; i < n - 1; i++) {
                    var value = in[i] * 3;
                    out[2 * i] = (value + in[i - 1] + 1) >> 2;
                    out[2 * i + 1] = (value + in[i + 1] + 2) >> 2;
                }
                out[2 * n - 2] = (in[n - 1] * 3 + in[n - 2] + 1) >> 2;
                out[2 * n - 1] = in[n - 1];
            }
            return java.util.Arrays.copyOf(out, outWidth);
        }

        /** libjpeg-turbo's jdsample.c h2v2_fancy_upsample for one output row. */
        static int[] h2v2(int[] near, int[] far, int outWidth) {
            var n = near.length;
            var sums = new int[n];
            for (var i = 0; i < n; i++) {
                sums[i] = near[i] * 3 + far[i];
            }
            var out = new int[2 * n];
            if (n == 1) {
                out[0] = (sums[0] * 4 + 8) >> 4;
                out[1] = (sums[0] * 4 + 7) >> 4;
            } else {
                out[0] = (sums[0] * 4 + 8) >> 4;
                out[1] = (sums[0] * 3 + sums[1] + 7) >> 4;
                for (var i = 1; i < n - 1; i++) {
                    out[2 * i] = (sums[i] * 3 + sums[i - 1] + 8) >> 4;
                    out[2 * i + 1] = (sums[i] * 3 + sums[i + 1] + 7) >> 4;
                }
                out[2 * n - 2] = (sums[n - 1] * 3 + sums[n - 2] + 8) >> 4;
                out[2 * n - 1] = (sums[n - 1] * 4 + 7) >> 4;
            }
            return java.util.Arrays.copyOf(out, outWidth);
        }

        static int[] row(byte[] plane, int offset, int length) {
            var out = new int[length];
            for (var i = 0; i < length; i++) {
                out[i] = plane[offset + i] & 0xFF;
            }
            return out;
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(value = ChromaFormat.class, names = {"YUV420", "YUV422"})
        @DisplayName("triangle upsampling reproduces libjpeg's fancy upsampling at every size, narrow planes replicated")
        void libjpegFancy(ChromaFormat format) {
            var random = new SplittableRandom(5);
            for (var width = 1; width <= 21; width++) {
                for (var height = 1; height <= 9; height++) {
                    var yuv = Pictures.randomYuv(random, width, height, format, false, ColorInfo.JPEG);
                    var rgba = ImageOps.toRgba(yuv);
                    var m = ColorMatrix.of(ColorInfo.JPEG);
                    for (var y = 0; y < height; y++) {
                        int[] cb;
                        int[] cr;
                        var cw = yuv.chromaWidth();
                        if (cw <= 2) {
                            // jdsample.c jinit_upsampler picks h2v1_upsample or h2v2_upsample, which replicate
                            cb = new int[width];
                            cr = new int[width];
                            for (var x = 0; x < width; x++) {
                                cb[x] = yuv.u()[(y >> format.shiftY()) * yuv.uvStride() + (x >> 1)] & 0xFF;
                                cr[x] = yuv.v()[(y >> format.shiftY()) * yuv.uvStride() + (x >> 1)] & 0xFF;
                            }
                        } else if (format == ChromaFormat.YUV422) {
                            cb = h2v1(row(yuv.u(), y * yuv.uvStride(), cw), width);
                            cr = h2v1(row(yuv.v(), y * yuv.uvStride(), cw), width);
                        } else {
                            var near = y >> 1;
                            var far = (y & 1) == 0 ? Math.max(near - 1, 0) : Math.min(near + 1, yuv.chromaHeight() - 1);
                            cb = h2v2(row(yuv.u(), near * yuv.uvStride(), cw), row(yuv.u(), far * yuv.uvStride(), cw), width);
                            cr = h2v2(row(yuv.v(), near * yuv.uvStride(), cw), row(yuv.v(), far * yuv.uvStride(), cw), width);
                        }
                        for (var x = 0; x < width; x++) {
                            var luma = yuv.y()[y * yuv.yStride() + x] & 0xFF;
                            // jdcolor.c: R = y + Cr_r_tab[cr], G = y + ((Cb_g + Cr_g) >> 16), B = y + Cb_b_tab[cb]
                            var red = luma + ((91881 * (cr[x] - 128) + 32768) >> 16);
                            var green = luma + ((-22554 * (cb[x] - 128) - 46802 * (cr[x] - 128) + 32768) >> 16);
                            var blue = luma + ((116130 * (cb[x] - 128) + 32768) >> 16);
                            var index = 4 * (y * width + x);
                            var message = width + "x" + height + " at " + x + "," + y;
                            assertEquals(Math.clamp(red, 0, 255), rgba.pixels()[index] & 0xFF, message);
                            assertEquals(Math.clamp(green, 0, 255), rgba.pixels()[index + 1] & 0xFF, message);
                            assertEquals(Math.clamp(blue, 0, 255), rgba.pixels()[index + 2] & 0xFF, message);
                        }
                    }
                    assertNotNull(m);
                }
            }
        }

        @Test
        @DisplayName("nearest upsampling replicates each chroma sample")
        void nearest() {
            var random = new SplittableRandom(6);
            var yuv = Pictures.randomYuv(random, 13, 9, ChromaFormat.YUV420, false, ColorInfo.BT709_LIMITED);
            var nearest = ImageOps.toRgba(yuv, ChromaUpsampling.NEAREST);
            var full = YuvPicture.allocate(13, 9, ChromaFormat.YUV444, false, ColorInfo.BT709_LIMITED);
            for (var y = 0; y < 9; y++) {
                for (var x = 0; x < 13; x++) {
                    full.y()[y * 13 + x] = yuv.y()[y * yuv.yStride() + x];
                    full.u()[y * 13 + x] = yuv.u()[(y >> 1) * yuv.uvStride() + (x >> 1)];
                    full.v()[y * 13 + x] = yuv.v()[(y >> 1) * yuv.uvStride() + (x >> 1)];
                }
            }
            assertArrayEquals(Pictures.tight(ImageOps.toRgba(full)), Pictures.tight(nearest));
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(value = ChromaFormat.class, names = {"YUV420", "YUV422"})
        @DisplayName("downsampling averages the covered pixels exactly")
        void boxAverage(ChromaFormat format) {
            var random = new SplittableRandom(8);
            for (var width = 1; width <= 11; width++) {
                for (var height = 1; height <= 5; height++) {
                    var picture = Pictures.randomRgba(random, width, height, false);
                    var info = color(MatrixCoefficients.BT709, ColorRange.LIMITED);
                    var yuv = ImageOps.toYuv(picture, format, info);
                    for (var cy = 0; cy < yuv.chromaHeight(); cy++) {
                        for (var cx = 0; cx < yuv.chromaWidth(); cx++) {
                            double r = 0;
                            double g = 0;
                            double b = 0;
                            var count = 0;
                            for (var y = cy << format.shiftY(); y < Math.min((cy + 1) << format.shiftY(), height); y++) {
                                for (var x = cx << format.shiftX(); x < Math.min((cx + 1) << format.shiftX(), width); x++) {
                                    var i = y * picture.stride() + 4 * x;
                                    r += picture.pixels()[i] & 0xFF;
                                    g += picture.pixels()[i + 1] & 0xFF;
                                    b += picture.pixels()[i + 2] & 0xFF;
                                    count++;
                                }
                            }
                            var real = forward(MatrixCoefficients.BT709, ColorRange.LIMITED, 0, 0, 0);
                            var average = forwardReal(r / count, g / count, b / count);
                            assertRounds(average[1], yuv.u()[cy * yuv.uvStride() + cx] & 0xFF, "cb " + cx + "," + cy);
                            assertRounds(average[2], yuv.v()[cy * yuv.uvStride() + cx] & 0xFF, "cr " + cx + "," + cy);
                            assertNotNull(real);
                        }
                    }
                }
            }
        }

        /** The BT.709 limited-range forward transform of fractional R'G'B' values. */
        private static double[] forwardReal(double r, double g, double b) {
            var ey = (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255;
            var epb = (b / 255 - ey) / 1.8556;
            var epr = (r / 255 - ey) / 1.5748;
            return new double[]{219 * ey + 16, 224 * epb + 128, 224 * epr + 128};
        }

        @Test
        @DisplayName("alpha-weighted averaging ignores the color of transparent pixels")
        void alphaWeighted() {
            var picture = RgbaPicture.allocate(2, 2, true);
            var pixels = picture.pixels();
            for (var i = 0; i < 4; i++) {
                pixels[4 * i + 1] = (byte) 255;
            }
            pixels[0] = (byte) 255;
            pixels[1] = 0;
            pixels[3] = (byte) 255;
            var weighted = ImageOps.toYuv(picture, ChromaFormat.YUV420, ColorInfo.JPEG, ChromaDownsampling.ALPHA_WEIGHTED);
            var red = ImageOps.toYuv(pixel(255, 0, 0), ChromaFormat.YUV444, ColorInfo.JPEG);
            assertEquals(red.u()[0], weighted.u()[0]);
            assertEquals(red.v()[0], weighted.v()[0]);
            var plain = ImageOps.toYuv(picture, ChromaFormat.YUV420, ColorInfo.JPEG, ChromaDownsampling.AVERAGE);
            assertNotEquals(red.v()[0], plain.v()[0]);
        }

        @Test
        @DisplayName("alpha-weighted averaging equals plain averaging on opaque pictures")
        void alphaWeightedOpaque() {
            var random = new SplittableRandom(12);
            for (var width = 1; width <= 9; width++) {
                var picture = Pictures.randomRgba(random, width, 5, false);
                var withAlpha = RgbaPicture.of(width, 5, picture.stride(), picture.pixels(), true);
                for (var format : List.of(ChromaFormat.YUV420, ChromaFormat.YUV422, ChromaFormat.YUV444)) {
                    var plain = ImageOps.toYuv(withAlpha, format, ColorInfo.BT601_LIMITED, ChromaDownsampling.AVERAGE);
                    var weighted = ImageOps.toYuv(withAlpha, format, ColorInfo.BT601_LIMITED, ChromaDownsampling.ALPHA_WEIGHTED);
                    assertArrayEquals(Pictures.raw(plain), Pictures.raw(weighted), format + " width " + width);
                }
            }
        }
    }

    @Nested
    @DisplayName("planar conversions")
    class Planar {
        @Test
        @DisplayName("a picture already in the requested layout and color is returned as is")
        void identity() {
            var yuv = Pictures.randomYuv(new SplittableRandom(1), 5, 5, ChromaFormat.YUV420, false, ColorInfo.BT709_LIMITED);
            assertSame(yuv, ImageOps.toYuv(yuv, ChromaFormat.YUV420, ColorInfo.BT709_LIMITED));
            var rgba = Pictures.randomRgba(new SplittableRandom(1), 3, 3, true);
            assertSame(rgba, ImageOps.toRgba(rgba));
        }

        @Test
        @DisplayName("4:4:4 to 4:2:0 averages chroma blocks and keeps luma and alpha")
        void downsample() {
            var yuv = Pictures.randomYuv(new SplittableRandom(2), 7, 5, ChromaFormat.YUV444, true, ColorInfo.JPEG);
            var converted = ImageOps.toYuv(yuv, ChromaFormat.YUV420, ColorInfo.JPEG);
            assertArrayEquals(Pictures.tight(yuv.y(), yuv.yStride(), 7, 5), Pictures.tight(converted.y(), converted.yStride(), 7, 5));
            assertArrayEquals(Pictures.tight(yuv.alpha(), yuv.alphaStride(), 7, 5), Pictures.tight(converted.alpha(), converted.alphaStride(), 7, 5));
            for (var cy = 0; cy < 3; cy++) {
                for (var cx = 0; cx < 4; cx++) {
                    var sum = 0;
                    var count = 0;
                    for (var y = 2 * cy; y < Math.min(2 * cy + 2, 5); y++) {
                        for (var x = 2 * cx; x < Math.min(2 * cx + 2, 7); x++) {
                            sum += yuv.u()[y * yuv.uvStride() + x] & 0xFF;
                            count++;
                        }
                    }
                    var expected = Math.floor(sum / (double) count + 0.5);
                    // edge blocks replicate the last row or column, which weights the average the same way
                    assertEquals((int) expected, converted.u()[cy * converted.uvStride() + cx] & 0xFF, cx + "," + cy);
                }
            }
        }

        @Test
        @DisplayName("a range change remaps samples between the ranges")
        void range() {
            var limited = YuvPicture.allocate(4, 1, ChromaFormat.YUV444, false, ColorInfo.BT601_LIMITED);
            var samples = new int[]{16, 235, 126, 0};
            var chroma = new int[]{16, 240, 128, 255};
            for (var i = 0; i < 4; i++) {
                limited.y()[i] = (byte) samples[i];
                limited.u()[i] = (byte) chroma[i];
                limited.v()[i] = (byte) chroma[i];
            }
            var full = ImageOps.toYuv(limited, ChromaFormat.YUV444, ColorInfo.BT601_LIMITED.withRange(ColorRange.FULL));
            assertArrayEquals(new byte[]{0, (byte) 255, (byte) 128, 0}, full.y());
            assertArrayEquals(new byte[]{0, (byte) 255, (byte) 128, (byte) 255}, full.u());
        }

        @ParameterizedTest(name = "{0} -> {1}")
        @MethodSource("com.github.auties00.smalt.image.ops.ColorConversionTest#formatPairs")
        @DisplayName("layout changes stay close to the conversion through RGBA")
        void throughRgba(ChromaFormat from, ChromaFormat to) {
            var rgba = Pictures.patternRgba(37, 23, false);
            var source = ImageOps.toYuv(rgba, from, ColorInfo.BT709_LIMITED);
            var direct = ImageOps.toYuv(source, to, ColorInfo.BT709_LIMITED);
            var viaRgba = ImageOps.toYuv(ImageOps.toRgba(source), to, ColorInfo.BT709_LIMITED);
            assertEquals(to, direct.chromaFormat());
            assertTrue(Quality.psnr(Pictures.raw(viaRgba), Pictures.raw(direct)) > 40, "psnr " + Quality.psnr(Pictures.raw(viaRgba), Pictures.raw(direct)));
        }

        @Test
        @DisplayName("a matrix change stays close to the conversion from the RGBA source")
        void matrix() {
            var rgba = Pictures.patternRgba(20, 10, true);
            var bt601 = ImageOps.toYuv(rgba, ChromaFormat.YUV444, ColorInfo.BT601_LIMITED);
            var bt709 = ImageOps.toYuv(bt601, ChromaFormat.YUV444, ColorInfo.BT709_LIMITED);
            var direct = ImageOps.toYuv(rgba, ChromaFormat.YUV444, ColorInfo.BT709_LIMITED);
            assertTrue(Quality.maxDifference(Pictures.raw(direct), Pictures.raw(bt709)) <= 2);
        }

        @Test
        @DisplayName("monochrome pictures become gray RGBA and neutral chroma")
        void monochrome() {
            var gray = Pictures.randomYuv(new SplittableRandom(3), 9, 4, ChromaFormat.MONOCHROME, false, ColorInfo.JPEG);
            var rgba = ImageOps.toRgba(gray);
            for (var i = 0; i < 36; i++) {
                var value = gray.y()[(i / 9) * gray.yStride() + i % 9];
                assertEquals(value, rgba.pixels()[4 * i]);
                assertEquals(value, rgba.pixels()[4 * i + 1]);
                assertEquals(value, rgba.pixels()[4 * i + 2]);
            }
            var color = ImageOps.toYuv(gray, ChromaFormat.YUV420, ColorInfo.JPEG);
            for (var sample : color.u()) {
                assertEquals((byte) 128, sample);
            }
        }
    }

    static Stream<Arguments> formatPairs() {
        var pairs = new ArrayList<Arguments>();
        for (var from : List.of(ChromaFormat.YUV420, ChromaFormat.YUV422, ChromaFormat.YUV444)) {
            for (var to : List.of(ChromaFormat.YUV420, ChromaFormat.YUV422, ChromaFormat.YUV444, ChromaFormat.MONOCHROME)) {
                if (from != to) {
                    pairs.add(Arguments.of(from, to));
                }
            }
        }
        return pairs.stream();
    }

    @Nested
    @DisplayName("against ffmpeg")
    class Ffmpeg {
        static Stream<Arguments> cases() {
            var arguments = new ArrayList<Arguments>();
            var matrices = List.of(MatrixCoefficients.BT601, MatrixCoefficients.BT709, MatrixCoefficients.BT2020_NCL,
                    MatrixCoefficients.SMPTE240M, MatrixCoefficients.FCC, MatrixCoefficients.BT470BG);
            for (var matrix : matrices) {
                for (var range : ColorRange.values()) {
                    for (var format : List.of(ChromaFormat.YUV444, ChromaFormat.YUV422, ChromaFormat.YUV420)) {
                        arguments.add(Arguments.of(matrix, range, format));
                    }
                }
            }
            return arguments.stream();
        }

        static String matrixName(MatrixCoefficients matrix) {
            return switch (matrix) {
                case BT709 -> "bt709";
                case BT2020_NCL -> "bt2020";
                case SMPTE240M -> "smpte240m";
                case FCC -> "fcc";
                case BT470BG -> "bt470";
                default -> "bt601";
            };
        }

        static String rangeName(ColorRange range) {
            return range == ColorRange.FULL ? "pc" : "tv";
        }

        @ParameterizedTest(name = "{0} {1} {2}")
        @MethodSource("cases")
        @DisplayName("RGBA to Y'CbCr is within 2 of ffmpeg's accurate conversion")
        void forward(MatrixCoefficients matrix, ColorRange range, ChromaFormat format) {
            // swscale maps an odd-sized chroma plane with a ratio slightly off 2:1, so subsampled
            // layouts are compared on even sizes; odd sizes are covered by the exact references
            var width = format == ChromaFormat.YUV444 ? 97 : 96;
            var height = format == ChromaFormat.YUV444 ? 61 : 60;
            var rgba = Pictures.patternRgba(width, height, false);
            // convert at full chroma resolution, then box-average chroma with centered siting
            var range0 = rangeName(range);
            var raw = Pictures.ffmpeg(Pictures.tight(rgba), "rgba", width, height,
                    "scale=flags=accurate_rnd+full_chroma_int:out_color_matrix=" + matrixName(matrix) + ":out_range=" + range0
                            + ",format=yuv444p,scale=flags=area+accurate_rnd:out_h_chr_pos=128:out_v_chr_pos=128:in_range="
                            + range0 + ":out_range=" + range0, Pictures.pixelFormat(format, false));
            var ours = ImageOps.toYuv(rgba, format, color(matrix, range));
            var difference = Quality.maxDifference(raw, Pictures.raw(ours));
            assertTrue(difference <= 2, "max difference " + difference);
        }

        @ParameterizedTest(name = "{0} {1} {2}")
        @MethodSource("cases")
        @DisplayName("Y'CbCr to RGBA is within 2 of ffmpeg's accurate conversion with bilinear chroma")
        void inverse(MatrixCoefficients matrix, ColorRange range, ChromaFormat format) {
            var width = format == ChromaFormat.YUV444 ? 97 : 96;
            var height = format == ChromaFormat.YUV444 ? 61 : 60;
            var yuv = ImageOps.toYuv(Pictures.patternRgba(width, height, false), format, color(matrix, range));
            // upsample chroma bilinearly with centered siting into 16 bits, then convert at full chroma
            // resolution (swscale's direct path to RGB cannot site chroma between rows)
            var range0 = rangeName(range);
            var raw = Pictures.ffmpeg(Pictures.raw(yuv), Pictures.pixelFormat(format, false), width, height,
                    "scale=flags=bilinear+accurate_rnd+full_chroma_int:in_h_chr_pos=128:in_v_chr_pos=128:in_range=" + range0
                            + ":out_range=" + range0 + ",format=yuv444p16le,scale=flags=accurate_rnd+full_chroma_int:in_color_matrix="
                            + matrixName(matrix) + ":in_range=" + range0, "rgba");
            var ours = ImageOps.toRgba(yuv);
            var difference = Quality.maxDifference(raw, Pictures.tight(ours));
            assertTrue(difference <= 2, "max difference " + difference);
        }

        @Test
        @DisplayName("identity (GBR) planes match ffmpeg's gbrp layout exactly")
        void identity() {
            var rgba = Pictures.patternRgba(33, 17, false);
            var raw = Pictures.ffmpeg(Pictures.tight(rgba), "rgba", 33, 17, "null", "gbrp");
            var ours = ImageOps.toYuv(rgba, ChromaFormat.YUV444, color(MatrixCoefficients.IDENTITY, ColorRange.FULL));
            assertArrayEquals(raw, Pictures.raw(ours));
        }
    }
}
