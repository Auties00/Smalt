package com.github.auties00.smalt.image.ops;

import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ColorPrimaries;
import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.image.MatrixCoefficients;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.TransferCharacteristics;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.SplittableRandom;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the 16-bit color conversions (toRgba16, toYuv16, and the 8-bit conversions of 16-bit
 * pictures) against an independent double-precision implementation of the ITU-T H.273 equations
 * and against zimg (ffmpeg's zscale filter) as a black box, their agreement with the 8-bit
 * kernels, and the 16-bit flatten, pad, fit and watermark
 * operations.
 */
@DisplayName("16-bit color conversions and compositing")
class HighDepthColorTest {
    private static ColorInfo color(MatrixCoefficients matrix, ColorRange range) {
        return new ColorInfo(matrix, range, ColorPrimaries.BT709, TransferCharacteristics.BT709);
    }

    static Stream<Arguments> colors() {
        var arguments = new ArrayList<Arguments>();
        for (var matrix : new MatrixCoefficients[]{MatrixCoefficients.BT601, MatrixCoefficients.BT709,
                MatrixCoefficients.BT2020_NCL, MatrixCoefficients.YCGCO, MatrixCoefficients.IDENTITY}) {
            for (var range : ColorRange.values()) {
                arguments.add(Arguments.of(matrix, range));
            }
        }
        return arguments.stream();
    }

    /** The luma weights kr, kb of the matrices with a luma/color-difference form. */
    private static double[] weights(MatrixCoefficients matrix) {
        return switch (matrix) {
            case BT601 -> new double[]{0.299, 0.114};
            case BT709 -> new double[]{0.2126, 0.0722};
            default -> throw new IllegalArgumentException(matrix.toString());
        };
    }

    /** Quantizes a normalized luma (or full-swing component) to depth {@code d} as H.273 does. */
    private static double quantizeLuma(double e, int depth, ColorRange range) {
        return range == ColorRange.FULL ? ((1 << depth) - 1) * e : (219 * e + 16) * (1 << (depth - 8));
    }

    /** Quantizes a normalized color difference in [-0.5, 0.5] to depth {@code d} as H.273 does. */
    private static double quantizeChroma(double e, int depth, ColorRange range) {
        return range == ColorRange.FULL ? ((1 << depth) - 1) * e + (1 << (depth - 1)) : (224 * e + 128) * (1 << (depth - 8));
    }

    @Nested
    @DisplayName("R'G'B' to Y'CbCr")
    class Forward {
        @ParameterizedTest(name = "{0} {1}")
        @MethodSource("com.github.auties00.smalt.image.ops.HighDepthColorTest#colors")
        @DisplayName("16-bit RGBA to 10-bit 4:4:4 matches the H.273 equations")
        void equations(MatrixCoefficients matrix, ColorRange range) {
            if (matrix != MatrixCoefficients.BT601 && matrix != MatrixCoefficients.BT709) {
                return;
            }
            var random = new SplittableRandom(matrix.ordinal() * 2L + range.ordinal());
            var picture = HighDepthResizeTest.randomRgba16(random, 64, 32, false);
            var yuv = ImageOps.toYuv16(picture, ChromaFormat.YUV444, color(matrix, range), 10);
            var k = weights(matrix);
            var exact = 0;
            for (var y = 0; y < 32; y++) {
                for (var x = 0; x < 64; x++) {
                    var p = y * picture.stride() + 4 * x;
                    var r = (picture.pixels()[p] & 0xFFFF) / 65535.0;
                    var g = (picture.pixels()[p + 1] & 0xFFFF) / 65535.0;
                    var b = (picture.pixels()[p + 2] & 0xFFFF) / 65535.0;
                    var luma = k[0] * r + (1 - k[0] - k[1]) * g + k[1] * b;
                    var pb = (b - luma) / (2 * (1 - k[1]));
                    var pr = (r - luma) / (2 * (1 - k[0]));
                    var expected = new double[]{quantizeLuma(luma, 10, range), quantizeChroma(pb, 10, range),
                            quantizeChroma(pr, 10, range)};
                    var actual = new int[]{yuv.y()[y * yuv.yStride() + x], yuv.u()[y * yuv.uvStride() + x],
                            yuv.v()[y * yuv.uvStride() + x]};
                    for (var c = 0; c < 3; c++) {
                        var rounded = (int) Math.clamp(Math.round(expected[c]), 0, 1023);
                        assertEquals(rounded, actual[c], 1, "component " + c + " at " + x + "," + y);
                        exact += rounded == actual[c] ? 1 : 0;
                    }
                }
            }
            assertTrue(exact >= 0.995 * 3 * 64 * 32, exact + " exact");
        }

        @Test
        @DisplayName("4:2:0 chroma is the forward transform of the exact average of the covered pixels")
        void average() {
            var random = new SplittableRandom(3);
            var picture = HighDepthResizeTest.randomRgba16(random, 31, 17, false);
            var color = color(MatrixCoefficients.BT709, ColorRange.LIMITED);
            var yuv = ImageOps.toYuv16(picture, ChromaFormat.YUV420, color, 12);
            assertEquals(16, yuv.chromaWidth());
            var k = weights(MatrixCoefficients.BT709);
            for (var cy = 0; cy < yuv.chromaHeight(); cy++) {
                for (var cx = 0; cx < yuv.chromaWidth(); cx++) {
                    var sums = new double[3];
                    var count = 0;
                    for (var y = 2 * cy; y < Math.min(2 * cy + 2, 17); y++) {
                        for (var x = 2 * cx; x < Math.min(2 * cx + 2, 31); x++) {
                            for (var c = 0; c < 3; c++) {
                                sums[c] += (picture.pixels()[y * picture.stride() + 4 * x + c] & 0xFFFF) / 65535.0;
                            }
                            count++;
                        }
                    }
                    var r = sums[0] / count;
                    var g = sums[1] / count;
                    var b = sums[2] / count;
                    var luma = k[0] * r + (1 - k[0] - k[1]) * g + k[1] * b;
                    var pb = (b - luma) / (2 * (1 - k[1]));
                    assertEquals(quantizeChroma(pb, 12, ColorRange.LIMITED), yuv.u()[cy * yuv.uvStride() + cx], 0.51,
                            cx + "," + cy);
                }
            }
        }

        @Test
        @DisplayName("alpha-weighted chroma ignores the color of transparent pixels; alpha becomes a plane")
        void alphaWeighted() {
            var picture = Rgba16Picture.allocate(4, 2, true);
            for (var i = 0; i < 8; i++) {
                var visible = i % 4 == 0;
                picture.pixels()[4 * i] = visible ? (short) 0xFFFF : 0;
                picture.pixels()[4 * i + 1] = visible ? 0 : (short) 0xFFFF;
                picture.pixels()[4 * i + 3] = visible ? (short) 0xFFFF : 0;
            }
            var color = color(MatrixCoefficients.BT709, ColorRange.FULL);
            var weighted = ImageOps.toYuv16(picture, ChromaFormat.YUV420, color, 16, ChromaDownsampling.ALPHA_WEIGHTED);
            var red = ImageOps.toYuv16(Rgba16Picture.of(1, 1, 4, new short[]{(short) 0xFFFF, 0, 0, (short) 0xFFFF}, false),
                    ChromaFormat.YUV444, color, 16);
            assertEquals(red.u()[0], weighted.u()[0]);
            assertEquals(red.v()[0], weighted.v()[0]);
            assertEquals(65535, weighted.alpha()[0] & 0xFFFF);
            assertEquals(0, weighted.alpha()[1]);
            var plain = ImageOps.toYuv16(picture, ChromaFormat.YUV420, color, 16);
            assertTrue(Math.abs((plain.v()[0] & 0xFFFF) - (red.v()[0] & 0xFFFF)) > 10000);
        }

        @ParameterizedTest(name = "{0} {1}")
        @MethodSource("com.github.auties00.smalt.image.ops.HighDepthColorTest#colors")
        @DisplayName("16-bit RGBA to 8-bit Y'CbCr is within one level of the 8-bit conversion of the reduced picture")
        void toEightBit(MatrixCoefficients matrix, ColorRange range) {
            var random = new SplittableRandom(matrix.ordinal() * 5L + range.ordinal());
            var picture = HighDepthResizeTest.randomRgba16(random, 33, 21, true);
            for (var format : ChromaFormat.values()) {
                var color = color(matrix, range);
                var deep = ImageOps.toYuv(picture, format, color);
                var reduced = ImageOps.toYuv(ImageOps.toEightBit(picture), format, color);
                var a = Pictures.raw(deep);
                var b = Pictures.raw(reduced);
                for (var i = 0; i < a.length; i++) {
                    // two roundings (the reduction, then the conversion) against one
                    assertEquals(b[i] & 0xFF, a[i] & 0xFF, 2, format + " sample " + i);
                }
            }
        }
    }

    @Nested
    @DisplayName("Y'CbCr to R'G'B'")
    class Inverse {
        @ParameterizedTest(name = "{0} {1}")
        @MethodSource("com.github.auties00.smalt.image.ops.HighDepthColorTest#colors")
        @DisplayName("10-bit 4:4:4 to 16-bit RGBA matches the H.273 equations")
        void equations(MatrixCoefficients matrix, ColorRange range) {
            if (matrix != MatrixCoefficients.BT601 && matrix != MatrixCoefficients.BT709) {
                return;
            }
            var random = new SplittableRandom(matrix.ordinal() * 3L + range.ordinal());
            var picture = HighDepthResizeTest.randomYuv16(random, 40, 20, ChromaFormat.YUV444, 10, false, color(matrix, range));
            var rgba = ImageOps.toRgba16(picture);
            var k = weights(matrix);
            for (var y = 0; y < 20; y++) {
                for (var x = 0; x < 40; x++) {
                    double luma;
                    double pb;
                    double pr;
                    var yy = picture.y()[y * picture.yStride() + x];
                    var cb = picture.u()[y * picture.uvStride() + x];
                    var cr = picture.v()[y * picture.uvStride() + x];
                    if (range == ColorRange.FULL) {
                        luma = yy / 1023.0;
                        pb = (cb - 512) / 1023.0;
                        pr = (cr - 512) / 1023.0;
                    } else {
                        luma = (yy / 4.0 - 16) / 219;
                        pb = (cb / 4.0 - 128) / 224;
                        pr = (cr / 4.0 - 128) / 224;
                    }
                    var r = luma + 2 * (1 - k[0]) * pr;
                    var b = luma + 2 * (1 - k[1]) * pb;
                    var g = (luma - k[0] * r - k[1] * b) / (1 - k[0] - k[1]);
                    var expected = new double[]{r, g, b};
                    for (var c = 0; c < 3; c++) {
                        var value = Math.clamp(Math.round(65535 * expected[c]), 0, 65535);
                        assertEquals(value, rgba.pixels()[y * rgba.stride() + 4 * x + c] & 0xFFFF, 2,
                                "channel " + c + " at " + x + "," + y);
                    }
                    assertEquals((short) 0xFFFF, rgba.pixels()[y * rgba.stride() + 4 * x + 3]);
                }
            }
        }

        @Test
        @DisplayName("full-range samples scale by 2^d - 1 and limited-range ones by shifting")
        void quantization() {
            var full = color(MatrixCoefficients.BT709, ColorRange.FULL);
            var gray = Yuv16Picture.allocate(1, 1, ChromaFormat.MONOCHROME, 10, false, full);
            gray.y()[0] = 511;
            assertEquals(Math.round(511 * 65535 / 1023.0), ImageOps.toRgba16(gray).pixels()[0] & 0xFFFF);
            gray.y()[0] = 1023;
            assertEquals(65535, ImageOps.toRgba16(gray).pixels()[0] & 0xFFFF);
            var limited = Yuv16Picture.allocate(1, 1, ChromaFormat.YUV444, 12, true, ColorInfo.BT709_LIMITED);
            limited.y()[0] = 235 << 4;
            limited.u()[0] = 128 << 4;
            limited.v()[0] = 128 << 4;
            limited.alpha()[0] = 2047;
            var white = ImageOps.toRgba16(limited);
            assertArrayEquals(new short[]{(short) 0xFFFF, (short) 0xFFFF, (short) 0xFFFF, (short) Math.round(2047 * 65535 / 4095.0)},
                    Arrays.copyOf(white.pixels(), 4));
            limited.y()[0] = 16 << 4;
            assertEquals(0, ImageOps.toRgba16(limited).pixels()[1]);
        }

        @ParameterizedTest(name = "{0} {1}")
        @MethodSource("com.github.auties00.smalt.image.ops.HighDepthColorTest#colors")
        @DisplayName("an 8-bit picture converted at 16 bits reduces to within one level of the 8-bit kernel")
        void eightBitAgreement(MatrixCoefficients matrix, ColorRange range) {
            var random = new SplittableRandom(matrix.ordinal() * 7L + range.ordinal());
            for (var format : ChromaFormat.values()) {
                for (var upsampling : ChromaUpsampling.values()) {
                    var yuv = Pictures.randomYuv(random, 37, 23, format, true, color(matrix, range));
                    var expected = Pictures.tight(ImageOps.toRgba(yuv, upsampling));
                    var deep = ImageOps.toRgba16(yuv, upsampling);
                    var actual = Pictures.tight(ImageOps.toEightBit(deep));
                    // the 8-bit kernel rounds the interpolated chroma before the matrix, which a color
                    // difference coefficient of up to 2.1 amplifies
                    var tolerance = upsampling == ChromaUpsampling.TRIANGLE && format.shiftX() > 0 ? 2 : 1;
                    for (var i = 0; i < expected.length; i++) {
                        assertEquals(expected[i] & 0xFF, actual[i] & 0xFF, tolerance, format + " " + upsampling + " sample " + i);
                    }
                    var direct = Pictures.tight(ImageOps.toRgba(ImageOps.toBitDepth(yuv, 8), upsampling));
                    for (var i = 0; i < expected.length; i++) {
                        assertEquals(expected[i] & 0xFF, direct[i] & 0xFF, tolerance, format + " " + upsampling + " 8-bit sample " + i);
                    }
                }
            }
        }

        @Test
        @DisplayName("an 8-bit conversion of a 16-bit picture rounds once from the full-precision values")
        void singleRounding() {
            var random = new SplittableRandom(13);
            var yuv = HighDepthResizeTest.randomYuv16(random, 50, 30, ChromaFormat.YUV420, 10, true, ColorInfo.BT709_LIMITED);
            var deep = ImageOps.toRgba16(yuv);
            var eight = ImageOps.toRgba(yuv);
            assertTrue(eight.hasAlpha());
            for (var i = 0; i < 50 * 30 * 4; i++) {
                var reference = (deep.pixels()[i] & 0xFFFF) * 255 / 65535.0;
                assertEquals(reference, eight.pixels()[i] & 0xFF, 0.51, "sample " + i);
            }
        }

        @Test
        @DisplayName("round trips through 16-bit Y'CbCr stay within a few levels at 16 bits")
        void roundTrip() {
            var random = new SplittableRandom(14);
            var picture = HighDepthResizeTest.randomRgba16(random, 40, 30, true);
            for (var color : new ColorInfo[]{color(MatrixCoefficients.BT709, ColorRange.FULL),
                    color(MatrixCoefficients.BT2020_NCL, ColorRange.FULL), color(MatrixCoefficients.IDENTITY, ColorRange.FULL)}) {
                var back = ImageOps.toRgba16(ImageOps.toYuv16(picture, ChromaFormat.YUV444, color, 16));
                for (var y = 0; y < 30; y++) {
                    for (var x = 0; x < 40 * 4; x++) {
                        assertEquals(picture.pixels()[y * picture.stride() + x] & 0xFFFF, back.pixels()[y * back.stride() + x] & 0xFFFF,
                                3, color.matrix() + " sample " + x + "," + y);
                    }
                }
            }
        }
    }

    @Nested
    @DisplayName("Y'CbCr to Y'CbCr")
    class Planar {
        @Test
        @DisplayName("identical descriptions return the input; a depth change only shifts")
        void identity() {
            var random = new SplittableRandom(15);
            var yuv = HighDepthResizeTest.randomYuv16(random, 20, 10, ChromaFormat.YUV422, 10, false, ColorInfo.BT709_LIMITED);
            assertSame(yuv, ImageOps.toYuv16(yuv, ChromaFormat.YUV422, ColorInfo.BT709_LIMITED, 10));
            var twelve = ImageOps.toYuv16(yuv, ChromaFormat.YUV422, ColorInfo.BT709_LIMITED, 12);
            assertArrayEquals(ImageOps.toBitDepth(yuv, 12).y(), twelve.y());
            assertThrows(IllegalArgumentException.class, () -> ImageOps.toYuv16(yuv, ChromaFormat.YUV422, ColorInfo.JPEG, 7));
            assertThrows(IllegalArgumentException.class, () -> ImageOps.toYuv16(yuv, ChromaFormat.YUV422, ColorInfo.JPEG, 17));
        }

        @ParameterizedTest(name = "{0} -> {1}")
        @MethodSource("com.github.auties00.smalt.image.ops.HighDepthColorTest#layouts")
        @DisplayName("layout changes of widened pictures reduce to within one level of the 8-bit plane conversion")
        void layouts(ChromaFormat from, ChromaFormat to) {
            var random = new SplittableRandom(from.ordinal() * 4L + to.ordinal());
            for (var downsampling : ChromaDownsampling.values()) {
                var yuv = Pictures.randomYuv(random, 33, 19, from, true, ColorInfo.BT601_LIMITED);
                var expected = ImageOps.toYuv(yuv, to, ColorInfo.JPEG, downsampling);
                var deep = ImageOps.toYuv16(ImageOps.toBitDepth(yuv, 10), to, ColorInfo.JPEG, 10, downsampling);
                assertEquals(to, deep.chromaFormat());
                var actual = ImageOps.toEightBit(deep);
                var a = Pictures.raw(expected);
                var b = Pictures.raw(actual);
                assertEquals(a.length, b.length);
                for (var i = 0; i < a.length; i++) {
                    // the full-range target maps 10 bits by 1023 / 255 where the reduction shifts
                    assertEquals(a[i] & 0xFF, b[i] & 0xFF, 2, downsampling + " sample " + i);
                }
            }
        }

        @Test
        @DisplayName("a full-range depth change keeps the extremes and rescales by 2^d - 1")
        void fullRangeDepth() {
            var yuv = Yuv16Picture.allocate(3, 1, ChromaFormat.YUV444, 8, false, ColorInfo.JPEG);
            yuv.y()[0] = 255;
            yuv.y()[1] = 0;
            yuv.y()[2] = 100;
            Arrays.fill(yuv.u(), (short) 128);
            Arrays.fill(yuv.v(), (short) 128);
            yuv.v()[2] = (short) 255;
            var ten = ImageOps.toYuv16(yuv, ChromaFormat.YUV444, ColorInfo.JPEG, 10);
            assertArrayEquals(new short[]{1023, 0, (short) Math.round(100 * 1023 / 255.0)}, Arrays.copyOf(ten.y(), 3));
            assertEquals(512, ten.u()[0]);
            // H.273 full-range chroma: 1023 * (255 - 128) / 255 + 512, not the top code value
            assertEquals(512, ten.v()[0]);
            assertEquals(Math.round(1023 * 127 / 255.0 + 512), ten.v()[2]);
            // the same mapping as a layout change, which always went through the H.273 quantization
            var viaLayout = ImageOps.toYuv16(yuv, ChromaFormat.MONOCHROME, ColorInfo.JPEG, 10);
            assertArrayEquals(Arrays.copyOf(viaLayout.y(), 3), Arrays.copyOf(ten.y(), 3));
            var white = ImageOps.toRgba16(ten);
            for (var c = 0; c < 3; c++) {
                assertEquals(65535, white.pixels()[c] & 0xFFFF);
                assertEquals(0, white.pixels()[4 + c]);
            }
            var back = Yuv16Picture.allocate(2, 1, ChromaFormat.YUV444, 10, false, ColorInfo.JPEG);
            back.y()[0] = 1000;
            back.y()[1] = 1023;
            var eight = ImageOps.toYuv(back, ChromaFormat.YUV444, ColorInfo.JPEG);
            assertEquals(Math.round(1000 * 255 / 1023.0), eight.y()[0] & 0xFF);
            assertEquals(255, eight.y()[1] & 0xFF);
            var limited = HighDepthResizeTest.randomYuv16(new SplittableRandom(20), 9, 5, ChromaFormat.YUV420, 12, true,
                    ColorInfo.BT709_LIMITED);
            var narrowed = ImageOps.toYuv16(limited, ChromaFormat.YUV420, ColorInfo.BT709_LIMITED, 10);
            var shifted = ImageOps.toBitDepth(limited, 10);
            assertArrayEquals(shifted.y(), narrowed.y());
            assertArrayEquals(shifted.u(), narrowed.u());
            assertArrayEquals(shifted.alpha(), narrowed.alpha());
        }

        @Test
        @DisplayName("a range change maps the limited extremes onto the full extremes exactly")
        void range() {
            var yuv = Yuv16Picture.allocate(2, 1, ChromaFormat.YUV444, 10, false, ColorInfo.BT709_LIMITED);
            yuv.y()[0] = 64;
            yuv.y()[1] = 940;
            yuv.u()[0] = 64;
            yuv.u()[1] = 512;
            yuv.v()[0] = 960;
            yuv.v()[1] = 512;
            var full = ImageOps.toYuv16(yuv, ChromaFormat.YUV444, color(MatrixCoefficients.BT709, ColorRange.FULL), 10);
            assertArrayEquals(new short[]{0, 1023}, Arrays.copyOf(full.y(), 2));
            assertEquals(512, full.u()[1]);
            assertEquals(0, full.u()[0], 1);
            assertEquals(1023, full.v()[0], 1);
        }

        @Test
        @DisplayName("a matrix change converts through unclamped R'G'B' with one rounding")
        void matrix() {
            var random = new SplittableRandom(16);
            var yuv = HighDepthResizeTest.randomYuv16(random, 24, 12, ChromaFormat.YUV444, 10, false, ColorInfo.BT601_LIMITED);
            var target = color(MatrixCoefficients.BT709, ColorRange.LIMITED);
            var converted = ImageOps.toYuv16(yuv, ChromaFormat.YUV444, target, 12);
            var from = weights(MatrixCoefficients.BT601);
            var to = weights(MatrixCoefficients.BT709);
            for (var i = 0; i < 24 * 12; i++) {
                var x = i % 24;
                var y = i / 24;
                var luma = (yuv.y()[y * yuv.yStride() + x] / 4.0 - 16) / 219;
                var pb = (yuv.u()[y * yuv.uvStride() + x] / 4.0 - 128) / 224;
                var pr = (yuv.v()[y * yuv.uvStride() + x] / 4.0 - 128) / 224;
                var r = luma + 2 * (1 - from[0]) * pr;
                var b = luma + 2 * (1 - from[1]) * pb;
                var g = (luma - from[0] * r - from[1] * b) / (1 - from[0] - from[1]);
                var targetLuma = to[0] * r + (1 - to[0] - to[1]) * g + to[1] * b;
                var targetPb = (b - targetLuma) / (2 * (1 - to[1]));
                assertEquals(Math.clamp(quantizeLuma(targetLuma, 12, ColorRange.LIMITED), 0, 4095),
                        converted.y()[y * converted.yStride() + x], 0.6, "luma " + i);
                assertEquals(Math.clamp(quantizeChroma(targetPb, 12, ColorRange.LIMITED), 0, 4095),
                        converted.u()[y * converted.uvStride() + x], 0.6, "cb " + i);
            }
        }
    }

    @Nested
    @DisplayName("invalid arguments")
    class Invalid {
        @Test
        @DisplayName("matrices that need the transfer function or the primaries convert at every depth")
        void transferMatrices() {
            var ictcp = new ColorInfo(MatrixCoefficients.ICTCP, ColorRange.LIMITED, ColorPrimaries.BT2020,
                    TransferCharacteristics.PQ);
            var constant = new ColorInfo(MatrixCoefficients.BT2020_CL, ColorRange.LIMITED, ColorPrimaries.BT2020,
                    TransferCharacteristics.BT2020_10);
            var random = new SplittableRandom(21);
            var deep = HighDepthResizeTest.randomYuv16(random, 8, 6, ChromaFormat.YUV420, 10, false, ictcp);
            assertEquals(8, ImageOps.toRgba16(deep).width());
            assertEquals(6, ImageOps.toRgba(deep).height());
            var rgba = HighDepthResizeTest.randomRgba16(random, 8, 6, false);
            assertEquals(constant, ImageOps.toYuv16(rgba, ChromaFormat.YUV420, constant, 10).color());
            assertEquals(ColorInfo.BT709_LIMITED, ImageOps.toYuv16(deep, ChromaFormat.YUV444, ColorInfo.BT709_LIMITED,
                    10).color());
            assertEquals(constant, ImageOps.toYuv(rgba, ChromaFormat.YUV420, constant).color());
            // a change of depth alone stays a shift of the limited-range samples
            var twelve = ImageOps.toYuv16(deep, ChromaFormat.YUV420, ictcp, 12);
            assertEquals(12, twelve.bitDepth());
            assertEquals((deep.y()[0] & 0xFFFF) << 2, twelve.y()[0] & 0xFFFF);
        }

        @Test
        @DisplayName("null arguments and depths outside 8 to 16 are rejected")
        void arguments() {
            var rgba = HighDepthResizeTest.randomRgba16(new SplittableRandom(22), 4, 4, true);
            assertThrows(NullPointerException.class, () -> ImageOps.toRgba16(null));
            assertThrows(NullPointerException.class, () -> ImageOps.toRgba16(rgba, null));
            assertThrows(NullPointerException.class, () -> ImageOps.toYuv16(null, ChromaFormat.YUV420, ColorInfo.JPEG, 10));
            assertThrows(NullPointerException.class, () -> ImageOps.toYuv16(rgba, null, ColorInfo.JPEG, 10));
            assertThrows(NullPointerException.class, () -> ImageOps.toYuv16(rgba, ChromaFormat.YUV420, null, 10));
            assertThrows(NullPointerException.class,
                    () -> ImageOps.toYuv16(rgba, ChromaFormat.YUV420, ColorInfo.JPEG, 10, null));
            assertThrows(IllegalArgumentException.class, () -> ImageOps.toYuv16(rgba, ChromaFormat.YUV420, ColorInfo.JPEG, 7));
            assertThrows(NullPointerException.class, () -> ImageOps.smartCrop(rgba, 2, 2, SmartCropStrategy.ENTROPY,
                    (ResizeOptions) null));
            assertThrows(NullPointerException.class, () -> ImageOps.smartCrop(rgba, 2, 2, SmartCropStrategy.ENTROPY,
                    (ResizeFilter) null));
            assertThrows(NullPointerException.class, () -> ResizeOptions.of(null));
            assertThrows(IllegalArgumentException.class, () -> ImageOps.resize(rgba, 0, 2, ResizeOptions.defaults()));
            assertSame(rgba, ImageOps.toRgba16(rgba));
        }
    }

    static Stream<Arguments> layouts() {
        var arguments = new ArrayList<Arguments>();
        for (var from : ChromaFormat.values()) {
            for (var to : ChromaFormat.values()) {
                if (from != to) {
                    arguments.add(Arguments.of(from, to));
                }
            }
        }
        return arguments.stream();
    }

    @Nested
    @DisplayName("compositing")
    class Compositing {
        @Test
        @DisplayName("flattening 16-bit RGBA follows the 16-bit formula exactly")
        void flattenRgba16() {
            var random = new SplittableRandom(17);
            var picture = HighDepthResizeTest.randomRgba16(random, 23, 19, true);
            var flat = assertInstanceOf(Rgba16Picture.class, ImageOps.flatten(picture, 0x2080FF));
            assertFalse(flat.hasAlpha());
            var background = new long[]{0x20 * 257, 0x80 * 257, 0xFF * 257};
            for (var y = 0; y < 19; y++) {
                for (var x = 0; x < 23; x++) {
                    var p = y * picture.stride() + 4 * x;
                    long a = picture.pixels()[p + 3] & 0xFFFF;
                    for (var c = 0; c < 3; c++) {
                        long v = picture.pixels()[p + c] & 0xFFFF;
                        var expected = (2 * (v * a + background[c] * (65535 - a)) + 65535) / (2 * 65535);
                        assertEquals(expected, flat.pixels()[y * flat.stride() + 4 * x + c] & 0xFFFF);
                    }
                    assertEquals((short) 0xFFFF, flat.pixels()[y * flat.stride() + 4 * x + 3]);
                }
            }
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(ChromaFormat.class)
        @DisplayName("flattening widened pictures reduces to within one level of flattening at 8 bits")
        void flattenAgreement(ChromaFormat format) {
            var random = new SplittableRandom(format.ordinal());
            var rgba = Pictures.randomRgba(random, 21, 15, true);
            EightBit.assertWithinOne(ImageOps.flatten(rgba, 0x336699),
                    ImageOps.toEightBit(ImageOps.flatten(ImageOps.toSixteenBit(rgba), 0x336699)));
            for (var color : new ColorInfo[]{ColorInfo.BT709_LIMITED, ColorInfo.JPEG}) {
                var yuv = Pictures.randomYuv(random, 21, 15, format, true, color);
                for (var depth : new int[]{10, 12, 16}) {
                    var deep = ImageOps.flatten(ImageOps.toBitDepth(yuv, depth), 0xC08040);
                    var flat = assertInstanceOf(Yuv16Picture.class, deep);
                    assertEquals(depth, flat.bitDepth());
                    assertFalse(flat.hasAlpha());
                    EightBit.assertWithinOne(ImageOps.flatten(yuv, 0xC08040), ImageOps.toEightBit(deep));
                }
            }
        }

        @Test
        @DisplayName("an opaque alpha plane keeps the samples; a transparent one gives the background at the depth")
        void flattenExtremes() {
            var yuv = Yuv16Picture.allocate(4, 4, ChromaFormat.YUV420, 10, true, ColorInfo.BT709_LIMITED);
            Arrays.fill(yuv.y(), (short) 700);
            Arrays.fill(yuv.u(), (short) 300);
            Arrays.fill(yuv.v(), (short) 800);
            Arrays.fill(yuv.alpha(), (short) 1023);
            var opaque = (Yuv16Picture) ImageOps.flatten(yuv, 0xFFFFFF);
            assertEquals(700, opaque.y()[5]);
            assertEquals(300, opaque.u()[1]);
            Arrays.fill(yuv.alpha(), (short) 0);
            var background = (Yuv16Picture) ImageOps.flatten(yuv, 0xFFFFFF);
            assertEquals(940, background.y()[0]);
            assertEquals(512, background.u()[0]);
            assertEquals(512, background.v()[3]);
        }

        @Test
        @DisplayName("padding keeps 16 bits and widens the background as v * 257")
        void pad() {
            var random = new SplittableRandom(18);
            var picture = HighDepthResizeTest.randomRgba16(random, 5, 3, false);
            var padded = assertInstanceOf(Rgba16Picture.class, ImageOps.pad(picture, 9, 6, 2, 1, 0x80112233));
            assertTrue(padded.hasAlpha());
            assertArrayEquals(new short[]{0x11 * 257, 0x22 * 257, 0x33 * 257, (short) (0x80 * 257)},
                    Arrays.copyOf(padded.pixels(), 4));
            for (var y = 0; y < 3; y++) {
                for (var x = 0; x < 5; x++) {
                    for (var c = 0; c < 3; c++) {
                        assertEquals(picture.pixels()[y * picture.stride() + 4 * x + c],
                                padded.pixels()[(y + 1) * padded.stride() + 4 * (x + 2) + c]);
                    }
                    assertEquals((short) 0xFFFF, padded.pixels()[(y + 1) * padded.stride() + 4 * (x + 2) + 3]);
                }
            }
            var yuv = HighDepthResizeTest.randomYuv16(random, 6, 4, ChromaFormat.YUV420, 10, false, ColorInfo.BT709_LIMITED);
            var fromPlanar = assertInstanceOf(Rgba16Picture.class, ImageOps.pad(yuv, 8, 8, 1, 1, 0));
            var rgba = ImageOps.toRgba16(yuv);
            assertEquals(rgba.pixels()[4], fromPlanar.pixels()[fromPlanar.stride() + 8]);
            assertInstanceOf(RgbaPicture.class, ImageOps.pad(ImageOps.toEightBit(picture), 9, 6, 2, 1, 0));
        }

        @Test
        @DisplayName("a 16-bit watermark is resized at its depth before it is composited")
        void watermark() {
            var random = new SplittableRandom(19);
            var base = HighDepthResizeTest.randomRgba16(random, 80, 60, false);
            var mark = HighDepthResizeTest.randomRgba16(random, 41, 23, true);
            var options = WatermarkOptions.builder().scale(0.5).filter(ResizeFilter.LANCZOS3).build();
            var marked = assertInstanceOf(Rgba16Picture.class, ImageCompositing.watermark(base, mark, options));
            var resized = ImageOps.resize(mark, 40, (int) Math.round(40.0 * 23 / 41), ResizeFilter.LANCZOS3);
            assertInstanceOf(Rgba16Picture.class, resized);
            var x = options.anchor().x(80, resized.width(), options.marginX());
            var y = options.anchor().y(60, resized.height(), options.marginY());
            var expected = (Rgba16Picture) ImageCompositing.composite(base, resized, x, y, options.composite());
            assertArrayEquals(expected.pixels(), marked.pixels());
        }
    }

    /** Reuses the within-one comparison of the resampling tests. */
    private static final class EightBit {
        static void assertWithinOne(Picture expected, Picture actual) {
            HighDepthResizeTest.EightBit.assertWithinOne(expected, actual);
        }
    }

    static Stream<Arguments> zimgCases() {
        var arguments = new ArrayList<Arguments>();
        for (var matrix : new MatrixCoefficients[]{MatrixCoefficients.BT601, MatrixCoefficients.BT709,
                MatrixCoefficients.BT2020_NCL}) {
            for (var range : new ColorRange[]{ColorRange.LIMITED, ColorRange.FULL}) {
                for (var depth : new int[]{10, 12, 16}) {
                    arguments.add(Arguments.of(matrix, range, depth));
                }
            }
        }
        return arguments.stream();
    }

    @Nested
    @DisplayName("against zimg")
    class Zimg {
        private static final int WIDTH = 64;
        private static final int HEIGHT = 32;

        private static String zscale(MatrixCoefficients matrix, ColorRange range, boolean toYuv, int depth) {
            var name = switch (matrix) {
                case BT601 -> "470bg";
                case BT709 -> "709";
                case BT2020_NCL -> "2020_ncl";
                default -> throw new IllegalArgumentException(matrix.toString());
            };
            var yuv = "matrix%s=" + name + ":range%s=" + (range == ColorRange.FULL ? "full" : "limited");
            var rgb = "matrix%s=gbr:range%s=full";
            var in = (toYuv ? rgb : yuv).replace("%s", "in");
            var out = (toYuv ? yuv : rgb).replace("%s", "");
            return "zscale=" + in + ":" + out + ":dither=none,format=" + (toYuv ? "yuv444p" + depth + "le" : "gbrp16le");
        }

        private static byte[] littleEndian(short[]... planes) {
            var buffer = java.nio.ByteBuffer.allocate(2 * planes.length * planes[0].length)
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN);
            for (var plane : planes) {
                for (var sample : plane) {
                    buffer.putShort(sample);
                }
            }
            return buffer.array();
        }

        private static int sample(byte[] raw, int index) {
            return (raw[2 * index] & 0xFF) | (raw[2 * index + 1] & 0xFF) << 8;
        }

        @ParameterizedTest(name = "{0} {1} {2}-bit")
        @MethodSource("com.github.auties00.smalt.image.ops.HighDepthColorTest#zimgCases")
        @DisplayName("16-bit RGBA to 4:4:4 Y'CbCr matches zimg within one code value")
        void forward(MatrixCoefficients matrix, ColorRange range, int depth) {
            var random = new SplittableRandom(depth * 7L + matrix.ordinal());
            var rgba = Rgba16Picture.allocate(WIDTH, HEIGHT, false);
            var planes = new short[3][WIDTH * HEIGHT];
            for (var i = 0; i < WIDTH * HEIGHT; i++) {
                for (var c = 0; c < 3; c++) {
                    rgba.pixels()[4 * i + c] = (short) random.nextInt(65536);
                }
                rgba.pixels()[4 * i + 3] = (short) 0xFFFF;
                planes[0][i] = rgba.pixels()[4 * i + 1];
                planes[1][i] = rgba.pixels()[4 * i + 2];
                planes[2][i] = rgba.pixels()[4 * i];
            }
            var format = "yuv444p" + depth + "le";
            var expected = Pictures.ffmpeg(littleEndian(planes), "gbrp16le", WIDTH, HEIGHT,
                    zscale(matrix, range, true, depth), format);
            var actual = ImageOps.toYuv16(rgba, ChromaFormat.YUV444, color(matrix, range), depth);
            short[][] result = {actual.y(), actual.u(), actual.v()};
            for (var c = 0; c < 3; c++) {
                for (var i = 0; i < WIDTH * HEIGHT; i++) {
                    assertEquals(sample(expected, c * WIDTH * HEIGHT + i), result[c][i] & 0xFFFF, 1,
                            "plane " + c + " sample " + i);
                }
            }
        }

        @ParameterizedTest(name = "{0} {1} {2}-bit")
        @MethodSource("com.github.auties00.smalt.image.ops.HighDepthColorTest#zimgCases")
        @DisplayName("4:4:4 Y'CbCr to 16-bit RGBA matches zimg within one level")
        void inverse(MatrixCoefficients matrix, ColorRange range, int depth) {
            var random = new SplittableRandom(depth * 11L + range.ordinal());
            var planes = new short[3][WIDTH * HEIGHT];
            for (var plane : planes) {
                for (var i = 0; i < plane.length; i++) {
                    plane[i] = (short) random.nextInt(1 << depth);
                }
            }
            var yuv = Yuv16Picture.builder().size(WIDTH, HEIGHT).chromaFormat(ChromaFormat.YUV444).bitDepth(depth)
                    .color(color(matrix, range)).luma(planes[0], WIDTH).chroma(planes[1], planes[2], WIDTH).build();
            var expected = Pictures.ffmpeg(littleEndian(planes), "yuv444p" + depth + "le", WIDTH, HEIGHT,
                    zscale(matrix, range, false, depth), "gbrp16le");
            var actual = ImageOps.toRgba16(yuv);
            int[] planeOf = {2, 0, 1};
            for (var i = 0; i < WIDTH * HEIGHT; i++) {
                for (var c = 0; c < 3; c++) {
                    assertEquals(sample(expected, planeOf[c] * WIDTH * HEIGHT + i), actual.pixels()[4 * i + c] & 0xFFFF,
                            1, "channel " + c + " pixel " + i);
                }
            }
        }
    }
}
