package com.github.auties00.smalt.image.ops;

import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ColorPrimaries;
import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.image.MatrixCoefficients;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.TransferCharacteristics;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.internal.color.TransferFunction;
import com.github.auties00.smalt.internal.color.YcbcrModel;
import com.github.auties00.smalt.internal.color.YcgcoR;
import com.github.auties00.smalt.testkit.Oracle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the conversions of the matrices that need the transfer function, the primaries or integer arithmetic:
 * golden samples from the ITU-T H.273 equations, round trips at 8, 10 and 12 bits, the integer YCgCo-R matrices,
 * chroma subsampling, the 8-bit and reusable-buffer paths, and zimg (through ffmpeg's zscale filter) as a black-box
 * oracle.
 */
class CicpColorTest {
    private static final int WIDTH = 48;
    private static final int HEIGHT = 12;

    private static final List<MatrixCoefficients> MODEL_MATRICES = List.of(MatrixCoefficients.SMPTE2085,
            MatrixCoefficients.BT2020_CL, MatrixCoefficients.CHROMA_DERIVED_NCL, MatrixCoefficients.CHROMA_DERIVED_CL,
            MatrixCoefficients.ICTCP, MatrixCoefficients.IPT_C2);

    private static ColorInfo color(MatrixCoefficients matrix, ColorRange range) {
        return switch (matrix) {
            case SMPTE2085 -> new ColorInfo(matrix, range, ColorPrimaries.SMPTE428, TransferCharacteristics.SMPTE428);
            case ICTCP, IPT_C2 -> new ColorInfo(matrix, range, ColorPrimaries.BT2020, TransferCharacteristics.PQ);
            case CHROMA_DERIVED_NCL, CHROMA_DERIVED_CL -> new ColorInfo(matrix, range, ColorPrimaries.SMPTE432,
                    TransferCharacteristics.BT709);
            default -> new ColorInfo(matrix, range, ColorPrimaries.BT2020, TransferCharacteristics.BT2020_10);
        };
    }

    static Stream<Arguments> descriptions() {
        var list = new ArrayList<Arguments>();
        for (var matrix : MODEL_MATRICES) {
            for (var range : ColorRange.values()) {
                list.add(Arguments.of(color(matrix, range)));
            }
        }
        list.add(Arguments.of(new ColorInfo(MatrixCoefficients.ICTCP, ColorRange.LIMITED, ColorPrimaries.BT2020,
                TransferCharacteristics.HLG)));
        list.add(Arguments.of(new ColorInfo(MatrixCoefficients.BT2020_CL, ColorRange.LIMITED, ColorPrimaries.BT2020,
                TransferCharacteristics.PQ)));
        return list.stream();
    }

    // Color bars at 100% and 75%, a gray ramp and random colors, in 16-bit R'G'B'.
    private static Rgba16Picture pattern(boolean alpha) {
        return pattern(alpha, false);
    }

    // The constant-luminance and cone-space matrices mix linear light, so with PQ or HLG the quantization error of a
    // pixel's bright channels, in linear light, swamps a much darker channel: a PQ signal 0.06 lower is about half the
    // light. The moderate pattern keeps the channels of each color within 0.04 of each other in the signal.
    private static Rgba16Picture pattern(boolean alpha, boolean moderate) {
        var picture = Rgba16Picture.allocate(WIDTH, HEIGHT, alpha);
        var pixels = picture.pixels();
        var random = new SplittableRandom(5);
        int[][] bars = {{1, 1, 1}, {1, 1, 0}, {0, 1, 1}, {0, 1, 0}, {1, 0, 1}, {1, 0, 0}, {0, 0, 1}, {0, 0, 0}};
        for (var y = 0; y < HEIGHT; y++) {
            for (var x = 0; x < WIDTH; x++) {
                var o = y * picture.stride() + 4 * x;
                int r;
                int g;
                int b;
                if (moderate && y >= 4) {
                    var base = 6554 + random.nextInt(52428);
                    r = base + random.nextInt(2622);
                    g = base + random.nextInt(2622);
                    b = base + random.nextInt(2622);
                } else if (moderate && y < 2) {
                    var bar = bars[x * 8 / WIDTH];
                    var level = y == 0 ? 3277 : 1966;
                    r = 29491 + bar[0] * level;
                    g = 29491 + bar[1] * level;
                    b = 29491 + bar[2] * level;
                } else if (y < 2) {
                    var bar = bars[x * 8 / WIDTH];
                    var level = y == 0 ? 65535 : 49151;
                    r = bar[0] * level;
                    g = bar[1] * level;
                    b = bar[2] * level;
                } else if (y < 4) {
                    r = g = b = x * 65535 / (WIDTH - 1);
                } else {
                    r = random.nextInt(65536);
                    g = random.nextInt(65536);
                    b = random.nextInt(65536);
                }
                pixels[o] = (short) r;
                pixels[o + 1] = (short) g;
                pixels[o + 2] = (short) b;
                pixels[o + 3] = (short) (alpha ? random.nextInt(65536) : 65535);
            }
        }
        return picture;
    }

    private static RgbaPicture pattern8(boolean alpha) {
        return ImageOps.toEightBit(pattern(alpha));
    }

    private static boolean mixesLinearLight(ColorInfo color) {
        var hdr = color.transfer() == TransferCharacteristics.PQ || color.transfer() == TransferCharacteristics.HLG;
        var matrix = color.matrix();
        return hdr && (matrix == MatrixCoefficients.BT2020_CL || matrix == MatrixCoefficients.CHROMA_DERIVED_CL
                || matrix == MatrixCoefficients.ICTCP || matrix == MatrixCoefficients.IPT_C2);
    }

    @Nested
    @DisplayName("golden samples")
    class Golden {
        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.image.ops.CicpColorTest#descriptions")
        @DisplayName("4:4:4 samples are the H.273 equations quantized with one rounding")
        void samples(ColorInfo color) {
            var rgba = pattern(false);
            var model = YcbcrModel.of(color);
            for (var depth : new int[]{10, 12, 16}) {
                var yuv = ImageOps.toYuv16(rgba, ChromaFormat.YUV444, color, depth);
                var max = (1 << depth) - 1;
                var e = new double[3];
                for (var y = 0; y < HEIGHT; y++) {
                    for (var x = 0; x < WIDTH; x++) {
                        var o = y * rgba.stride() + 4 * x;
                        model.toYcbcr((rgba.pixels()[o] & 0xFFFF) / 65535.0, (rgba.pixels()[o + 1] & 0xFFFF) / 65535.0,
                                (rgba.pixels()[o + 2] & 0xFFFF) / 65535.0, e);
                        short[][] planes = {yuv.y(), yuv.u(), yuv.v()};
                        for (var c = 0; c < 3; c++) {
                            var code = e[c] * model.scale(c, color.range(), depth) + model.offset(c, color.range(), depth);
                            var expected = (int) Math.round(Math.clamp(code, 0, max));
                            var stride = c == 0 ? yuv.yStride() : yuv.uvStride();
                            assertEquals(expected, planes[c][y * stride + x] & 0xFFFF, color + " c" + c + " at " + x);
                        }
                    }
                }
            }
        }

        @Test
        @DisplayName("BT.2020 constant-luminance bars hit the ends of the 12-bit chroma range")
        void bars() {
            var color = color(MatrixCoefficients.BT2020_CL, ColorRange.LIMITED);
            var yuv = ImageOps.toYuv16(pattern(false), ChromaFormat.YUV444, color, 12);
            // white, yellow, cyan, green, magenta, red, blue, black
            int[] cb = {2048, 256, -1, -1, -1, -1, 3840, 2048};
            int[] cr = {2048, -1, 256, -1, -1, 3840, -1, 2048};
            for (var bar = 0; bar < 8; bar++) {
                var x = bar * WIDTH / 8 + 1;
                if (cb[bar] >= 0) {
                    assertEquals(cb[bar], yuv.u()[x] & 0xFFFF, "bar " + bar);
                }
                if (cr[bar] >= 0) {
                    assertEquals(cr[bar], yuv.v()[x] & 0xFFFF, "bar " + bar);
                }
            }
            assertEquals(3760, yuv.y()[1] & 0xFFFF);
            assertEquals(256, yuv.y()[7 * WIDTH / 8 + 1] & 0xFFFF);
        }

        @Test
        @DisplayName("8-bit pictures convert like 16-bit ones at depth 8")
        void eightBit() {
            for (var matrix : MODEL_MATRICES) {
                var color = color(matrix, ColorRange.LIMITED);
                var rgba = pattern8(true);
                var narrow = ImageOps.toYuv(rgba, ChromaFormat.YUV420, color);
                var wide = ImageOps.toYuv16(rgba, ChromaFormat.YUV420, color, 8);
                assertArrayEquals(Pictures.raw(ImageOps.toEightBit(wide)), Pictures.raw(narrow), matrix.toString());
                assertArrayEquals(Pictures.tight(ImageOps.toRgba(wide)), Pictures.tight(ImageOps.toRgba(narrow)));
            }
        }
    }

    @Nested
    @DisplayName("round trips")
    class RoundTrips {
        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.image.ops.CicpColorTest#descriptions")
        @DisplayName("8-bit R'G'B' through 10 and 12-bit Y'CbCr returns within one code value")
        void deep(ColorInfo color) {
            var rgba = ImageOps.toEightBit(pattern(true, mixesLinearLight(color)));
            for (var depth : new int[]{10, 12}) {
                var back = ImageOps.toRgba(ImageOps.toYuv16(rgba, ChromaFormat.YUV444, color, depth));
                assertTrue(maxDifference(rgba, back) <= 1, color + " at " + depth + ": " + maxDifference(rgba, back));
            }
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.image.ops.CicpColorTest#descriptions")
        @DisplayName("saturated colors keep their linear light within a fraction of the pixel's brightest channel")
        void saturated(ColorInfo color) {
            var rgba = pattern(false);
            var tf = TransferFunction.of(color.transfer());
            // a chroma step in limited range is 1 / 224 of the signal, which the steepest decoding functions here
            // (SMPTE ST 428-1, PQ) turn into a few percent of the brightest channel at 10 bits and a quarter of that
            // at 12; errors of a wrong coefficient are an order of magnitude larger
            var bounds = new double[]{0.08, 0.02};
            var depths = new int[]{10, 12};
            for (var d = 0; d < 2; d++) {
                var back = ImageOps.toRgba16(ImageOps.toYuv16(rgba, ChromaFormat.YUV444, color, depths[d]));
                var worst = 0.0;
                for (var i = 0; i < WIDTH * HEIGHT; i++) {
                    var in = new double[3];
                    var peak = 1e-4;
                    for (var c = 0; c < 3; c++) {
                        in[c] = tf.decode((rgba.pixels()[4 * i + c] & 0xFFFF) / 65535.0);
                        peak = Math.max(peak, in[c]);
                    }
                    for (var c = 0; c < 3; c++) {
                        var out = tf.decode((back.pixels()[4 * i + c] & 0xFFFF) / 65535.0);
                        worst = Math.max(worst, Math.abs(out - in[c]) / peak);
                    }
                }
                assertTrue(worst <= bounds[d], color + " at " + depths[d] + ": " + worst);
            }
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.image.ops.CicpColorTest#descriptions")
        @DisplayName("R'G'B' of a depth through Y'CbCr of the same depth stays within the quantization of the matrix")
        void sameDepth(ColorInfo color) {
            var source = pattern(false, mixesLinearLight(color));
            for (var depth : new int[]{8, 10, 12}) {
                var shift = 16 - depth;
                var max = (1 << depth) - 1;
                var grid = Rgba16Picture.allocate(WIDTH, HEIGHT, false);
                for (var i = 0; i < source.pixels().length; i++) {
                    var code = ((source.pixels()[i] & 0xFFFF) >> shift);
                    grid.pixels()[i] = (short) Math.round(code * 65535.0 / max);
                }
                var back = ImageOps.toRgba16(ImageOps.toYuv16(grid, ChromaFormat.YUV444, color, depth));
                var worst = 0L;
                for (var i = 0; i < grid.pixels().length; i++) {
                    var a = Math.round((grid.pixels()[i] & 0xFFFF) * max / 65535.0);
                    var b = Math.round((back.pixels()[i] & 0xFFFF) * max / 65535.0);
                    worst = Math.max(worst, Math.abs(a - b));
                }
                // a color difference step is 1 / 224 of the signal range in limited range and the inverse
                // matrices multiply it by up to 2.03 (SMPTE ST 2085 D'Z), so half a step of chroma error plus half a
                // step of luma error reach two code values at the depth of the samples
                var constant = color.matrix() == MatrixCoefficients.BT2020_CL
                        || color.matrix() == MatrixCoefficients.CHROMA_DERIVED_CL;
                var tolerance = depth == 8 && mixesLinearLight(color) ? 4 : constant ? 3 : 2;
                assertTrue(worst <= tolerance, color + " at " + depth + ": " + worst);
            }
        }

        @Test
        @DisplayName("YCgCo-Re and YCgCo-Ro are lossless for 8-bit R'G'B' at 10 and 9 bits")
        void ycgcoLossless() {
            var rgba = pattern8(true);
            for (var range : ColorRange.values()) {
                var re = new ColorInfo(MatrixCoefficients.YCGCO_RE, ColorRange.FULL, ColorPrimaries.BT709,
                        TransferCharacteristics.SRGB);
                var ro = re.withMatrix(MatrixCoefficients.YCGCO_RO);
                assertArrayEquals(Pictures.tight(rgba), Pictures.tight(ImageOps.toRgba(ImageOps.toYuv16(rgba,
                        ChromaFormat.YUV444, re, 10))));
                assertArrayEquals(Pictures.tight(rgba), Pictures.tight(ImageOps.toRgba(ImageOps.toYuv16(rgba,
                        ChromaFormat.YUV444, ro, 9))));
                var limited = re.withRange(range);
                var back = ImageOps.toRgba(ImageOps.toYuv16(rgba, ChromaFormat.YUV444, limited, 12));
                assertTrue(maxDifference(rgba, back) <= 1, range + ": " + maxDifference(rgba, back));
            }
        }

        @Test
        @DisplayName("YCgCo-Re samples are the integer equations of the quantized R'G'B'")
        void ycgcoSamples() {
            var rgba = pattern8(false);
            var color = new ColorInfo(MatrixCoefficients.YCGCO_RE, ColorRange.LIMITED, ColorPrimaries.BT709,
                    TransferCharacteristics.SRGB);
            var yuv = ImageOps.toYuv16(rgba, ChromaFormat.YUV444, color, 12);
            var codes = new int[3];
            for (var i = 0; i < WIDTH * HEIGHT; i++) {
                var p = rgba.pixels();
                var r = YcgcoR.toSample((p[4 * i] & 0xFF) / 255.0, ColorRange.LIMITED, 10);
                var g = YcgcoR.toSample((p[4 * i + 1] & 0xFF) / 255.0, ColorRange.LIMITED, 10);
                var b = YcgcoR.toSample((p[4 * i + 2] & 0xFF) / 255.0, ColorRange.LIMITED, 10);
                YcgcoR.forward(r, g, b, 12, codes);
                assertEquals(codes[0], yuv.y()[i] & 0xFFFF);
                assertEquals(codes[1], yuv.u()[i] & 0xFFFF);
                assertEquals(codes[2], yuv.v()[i] & 0xFFFF);
            }
            // 8-bit limited R'G'B' 16 and 235 are 64 and 940 at the 10-bit R'G'B' depth of 12-bit YCgCo-Re
            assertEquals(64, YcgcoR.toSample(0, ColorRange.LIMITED, 10));
            assertEquals(940, YcgcoR.toSample(1, ColorRange.LIMITED, 10));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.image.ops.CicpColorTest#descriptions")
        @DisplayName("converting to BT.709 applies both models to unclamped R'G'B' with one rounding")
        void betweenMatrices(ColorInfo color) {
            var yuv = ImageOps.toYuv16(pattern(false), ChromaFormat.YUV444, color, 12);
            var bt709 = ColorInfo.BT709_LIMITED;
            var direct = ImageOps.toYuv16(yuv, ChromaFormat.YUV444, bt709, 12);
            var source = YcbcrModel.of(color);
            var target = YcbcrModel.of(bt709);
            short[][] in = {yuv.y(), yuv.u(), yuv.v()};
            short[][] out = {direct.y(), direct.u(), direct.v()};
            var e = new double[3];
            var rgb = new double[3];
            for (var i = 0; i < WIDTH * HEIGHT; i++) {
                for (var c = 0; c < 3; c++) {
                    e[c] = ((in[c][i] & 0xFFFF) - source.offset(c, color.range(), 12)) / source.scale(c, color.range(), 12);
                }
                source.toRgb(e[0], e[1], e[2], rgb);
                target.toYcbcr(rgb[0], rgb[1], rgb[2], e);
                for (var c = 0; c < 3; c++) {
                    var code = e[c] * target.scale(c, ColorRange.LIMITED, 12) + target.offset(c, ColorRange.LIMITED, 12);
                    assertEquals(Math.round(Math.clamp(code, 0, 4095)), out[c][i] & 0xFFFF, color + " c" + c);
                }
            }
            var viaRgb = ImageOps.toYuv16(ImageOps.toRgba16(yuv), ChromaFormat.YUV444, bt709, 12);
            // clamping to the R'G'B' cube on the way changes only what the quantization of the source left outside it
            if (!mixesLinearLight(color)) {
                assertTrue(maxDifference(direct, viaRgb) <= 2, color.toString());
            }
            var back = ImageOps.toYuv16(direct, ChromaFormat.YUV444, color, 12);
            assertEquals(color, back.color());
        }
    }

    @Nested
    @DisplayName("layouts")
    class Layouts {
        @Test
        @DisplayName("subsampled chroma averages the per-pixel color differences, weighted by alpha when asked")
        void subsampling() {
            var rgba = pattern(true);
            for (var matrix : List.of(MatrixCoefficients.BT2020_CL, MatrixCoefficients.ICTCP,
                    MatrixCoefficients.YCGCO_RE)) {
                var color = matrix == MatrixCoefficients.YCGCO_RE ? new ColorInfo(matrix, ColorRange.FULL,
                        ColorPrimaries.BT709, TransferCharacteristics.SRGB) : color(matrix, ColorRange.LIMITED);
                var full = ImageOps.toYuv16(rgba, ChromaFormat.YUV444, color, 10);
                for (var weighted : new boolean[]{false, true}) {
                    var downsampling = weighted ? ChromaDownsampling.ALPHA_WEIGHTED : ChromaDownsampling.AVERAGE;
                    var half = ImageOps.toYuv16(rgba, ChromaFormat.YUV420, color, 10, downsampling);
                    assertArrayEquals(full.y(), half.y());
                    for (var cy = 0; cy < HEIGHT / 2; cy++) {
                        for (var cx = 0; cx < WIDTH / 2; cx++) {
                            double sumU = 0;
                            double sumV = 0;
                            double total = 0;
                            for (var j = 0; j < 2; j++) {
                                for (var i = 0; i < 2; i++) {
                                    var x = 2 * cx + i;
                                    var y = 2 * cy + j;
                                    var w = weighted ? rgba.pixels()[y * rgba.stride() + 4 * x + 3] & 0xFFFF : 1;
                                    sumU += w * unrounded(rgba, color, 1, x, y);
                                    sumV += w * unrounded(rgba, color, 2, x, y);
                                    total += w;
                                }
                            }
                            assertEquals(Math.round(sumU / total), half.u()[cy * half.uvStride() + cx] & 0xFFFF,
                                    matrix + " " + weighted);
                            assertEquals(Math.round(sumV / total), half.v()[cy * half.uvStride() + cx] & 0xFFFF);
                        }
                    }
                }
            }
        }

        private static double unrounded(Rgba16Picture rgba, ColorInfo color, int component, int x, int y) {
            var o = y * rgba.stride() + 4 * x;
            var r = (rgba.pixels()[o] & 0xFFFF) / 65535.0;
            var g = (rgba.pixels()[o + 1] & 0xFFFF) / 65535.0;
            var b = (rgba.pixels()[o + 2] & 0xFFFF) / 65535.0;
            if (YcgcoR.applies(color.matrix())) {
                var codes = new int[3];
                var depth = YcgcoR.rgbDepth(color.matrix(), 10);
                YcgcoR.forward(YcgcoR.toSample(r, color.range(), depth), YcgcoR.toSample(g, color.range(), depth),
                        YcgcoR.toSample(b, color.range(), depth), 10, codes);
                return codes[component];
            }
            var model = YcbcrModel.of(color);
            var e = new double[3];
            model.toYcbcr(r, g, b, e);
            return e[component] * model.scale(component, color.range(), 10) + model.offset(component, color.range(), 10);
        }

        @Test
        @DisplayName("monochrome pictures keep the luma of 4:4:4 and decode as gray")
        void monochrome() {
            for (var matrix : MODEL_MATRICES) {
                var color = color(matrix, ColorRange.LIMITED);
                var rgba = pattern(false);
                var gray = ImageOps.toYuv16(rgba, ChromaFormat.MONOCHROME, color, 10);
                assertArrayEquals(ImageOps.toYuv16(rgba, ChromaFormat.YUV444, color, 10).y(), gray.y());
                var decoded = ImageOps.toRgba16(gray);
                var withChroma = ImageOps.toYuv16(gray, ChromaFormat.YUV444, color, 10);
                assertArrayEquals(gray.y(), withChroma.y());
                if (matrix != MatrixCoefficients.SMPTE2085) {
                    for (var i = 0; i < WIDTH * HEIGHT; i++) {
                        var p = decoded.pixels();
                        assertTrue(Math.abs((p[4 * i] & 0xFFFF) - (p[4 * i + 1] & 0xFFFF)) <= 1, matrix.toString());
                        assertTrue(Math.abs((p[4 * i + 2] & 0xFFFF) - (p[4 * i + 1] & 0xFFFF)) <= 1);
                    }
                }
            }
        }

        @Test
        @DisplayName("an unchanged description returns the input and a depth change of YCgCo-R requantizes R'G'B'")
        void depthChange() {
            var color = new ColorInfo(MatrixCoefficients.YCGCO_RE, ColorRange.LIMITED, ColorPrimaries.BT709,
                    TransferCharacteristics.SRGB);
            var ten = ImageOps.toYuv16(pattern8(false), ChromaFormat.YUV444, color, 10);
            assertSame(ten, ImageOps.toYuv16(ten, ChromaFormat.YUV444, color, 10));
            // 8-bit limited R'G'B' becomes 10-bit R'G'B' exactly, which a shift of the Y'CgCo samples does not give
            for (var twelve : List.of(ImageOps.toYuv16(ten, ChromaFormat.YUV444, color, 12),
                    ImageOps.toBitDepth(ten, 12))) {
                assertEquals(0, maxDifference(ImageOps.toRgba(ten), ImageOps.toRgba(twelve)));
                assertEquals(0, maxDifference(ten, ImageOps.toBitDepth(twelve, 10)));
            }
        }

        @Test
        @DisplayName("toBitDepth and toEightBit requantize the R'G'B' of YCgCo-Re and YCgCo-Ro pictures")
        void ycgcoDepth() {
            var rgba = pattern8(false);
            for (var matrix : List.of(MatrixCoefficients.YCGCO_RE, MatrixCoefficients.YCGCO_RO)) {
                var color = new ColorInfo(matrix, ColorRange.FULL, ColorPrimaries.BT709, TransferCharacteristics.SRGB);
                // full range requantizes R'G'B' as round(v * (2^n - 1) / 255), so both paths round once
                var eight = ImageOps.toYuv(rgba, ChromaFormat.YUV444, color);
                assertArrayEquals(Pictures.raw(eight), Pictures.raw(ImageOps.toEightBit(
                        ImageOps.toYuv16(rgba, ChromaFormat.YUV444, color, 10))), matrix.toString());
                var widened = ImageOps.toBitDepth(eight, 10);
                assertEquals(10, widened.bitDepth());
                assertEquals(color, widened.color());
                assertEquals(0, maxDifference(ImageOps.toRgba(eight), ImageOps.toRgba(widened)), matrix.toString());
                assertArrayEquals(Pictures.raw(eight), Pictures.raw(ImageOps.toEightBit(widened)), matrix.toString());
                var translucent = ImageOps.toYuv16(pattern8(true), ChromaFormat.YUV444, color, 12);
                var reduced = ImageOps.toEightBit(translucent);
                assertTrue(reduced.hasAlpha());
                assertEquals(0, maxDifference(ImageOps.toRgba(ImageOps.toYuv(pattern8(true), ChromaFormat.YUV444,
                        color)), ImageOps.toRgba(reduced)), matrix.toString());
            }
        }

        @Test
        @DisplayName("quantizing a deep YCgCo-R picture converts it from its own depth")
        void ycgcoQuantize() {
            var source = RgbaPicture.allocate(WIDTH, HEIGHT, false);
            int[] colors = {0x135799, 0xA9CB0D, 0x2E4F61, 0xF1E2D3, 0x0B1C2D, 0x7A8B9C, 0x456789, 0xFEDCBA};
            for (var i = 0; i < WIDTH * HEIGHT; i++) {
                var rgb = colors[i % colors.length];
                source.pixels()[4 * i] = (byte) (rgb >>> 16);
                source.pixels()[4 * i + 1] = (byte) (rgb >>> 8);
                source.pixels()[4 * i + 2] = (byte) rgb;
                source.pixels()[4 * i + 3] = (byte) 255;
            }
            var color = new ColorInfo(MatrixCoefficients.YCGCO_RE, ColorRange.FULL, ColorPrimaries.BT709,
                    TransferCharacteristics.SRGB);
            // eight colors fit the palette, which then holds them exactly
            var quantized = ImageQuantization.quantize(ImageOps.toYuv16(source, ChromaFormat.YUV444, color, 10));
            assertEquals(0, maxDifference(source, quantized.toRgba()));
        }

        @Test
        @DisplayName("a depth change of subsampled YCgCo-R converts every pixel with the chroma sample that covers it")
        void ycgcoSubsampledDepth() {
            // 4:2:0 YCgCo-Re samples whose 2x2 blocks share one color difference and differ in luma by gray offsets,
            // so every pixel decodes inside the R'G'B' cube
            var color = new ColorInfo(MatrixCoefficients.YCGCO_RE, ColorRange.LIMITED, ColorPrimaries.BT709,
                    TransferCharacteristics.SRGB);
            var random = new SplittableRandom(11);
            var y = new short[WIDTH * HEIGHT];
            var cw = WIDTH / 2;
            var u = new short[cw * (HEIGHT / 2)];
            var v = new short[u.length];
            var codes = new int[3];
            for (var j = 0; j < HEIGHT / 2; j++) {
                for (var i = 0; i < cw; i++) {
                    YcgcoR.forward(64 + random.nextInt(128), 64 + random.nextInt(128), 64 + random.nextInt(128), 10,
                            codes);
                    u[j * cw + i] = (short) codes[1];
                    v[j * cw + i] = (short) codes[2];
                    for (var dy = 0; dy < 2; dy++) {
                        for (var dx = 0; dx < 2; dx++) {
                            y[(2 * j + dy) * WIDTH + 2 * i + dx] = (short) (codes[0] + random.nextInt(41) - 20);
                        }
                    }
                }
            }
            var ten = Yuv16Picture.builder().size(WIDTH, HEIGHT).chromaFormat(ChromaFormat.YUV420).bitDepth(10)
                    .color(color).luma(y, WIDTH).chroma(u, v, cw).build();
            var twelve = ImageOps.toBitDepth(ten, 12);
            for (var i = 0; i < u.length; i++) {
                // Co scales exactly; Cg = G - (B + (Co >> 1)) gains at most 2 from the floor of an odd Co
                assertEquals(4 * v[i], twelve.v()[i]);
                assertTrue(Math.abs(4 * u[i] - twelve.u()[i]) <= 2, "Cg " + i + ": " + 4 * u[i] + " vs " + twelve.u()[i]);
            }
            for (var i = 0; i < y.length; i++) {
                // Y = t + (Cg >> 1) gains the floors of an odd Co and an odd Cg
                assertTrue(Math.abs(4 * y[i] - twelve.y()[i]) <= 3, "Y " + i);
            }
        }

        @Test
        @DisplayName("a reusable converter writes the same samples into the pictures it keeps")
        void converter() {
            var color = color(MatrixCoefficients.ICTCP, ColorRange.LIMITED);
            var converter = YuvConverter.create(ChromaFormat.YUV420, color);
            var first = converter.convert(pattern8(true));
            assertArrayEquals(Pictures.raw(ImageOps.toYuv(pattern8(true), ChromaFormat.YUV420, color)),
                    Pictures.raw(first));
            var second = converter.convert(pattern8(false));
            assertInstanceOf(YuvPicture.class, second);
            var bt709 = converter.convert(ImageOps.toYuv(pattern8(false), ChromaFormat.YUV444,
                    ColorInfo.BT709_LIMITED));
            assertEquals(color, bt709.color());
        }

        @Test
        @DisplayName("flattening, linear-light resizing and alpha keep working")
        void operations() {
            var color = color(MatrixCoefficients.BT2020_CL, ColorRange.LIMITED);
            var yuv = ImageOps.toYuv(pattern8(true), ChromaFormat.YUV420, color);
            var flat = (YuvPicture) ImageOps.flatten(yuv, 0xFF0000);
            assertFalse(flat.hasAlpha());
            var red = ImageOps.toYuv(solid(0xFF0000), ChromaFormat.YUV444, color);
            var transparent = ImageOps.toYuv(solidTransparent(), ChromaFormat.YUV444, color);
            var flattened = (YuvPicture) ImageOps.flatten(transparent, 0xFF0000);
            assertEquals(red.y()[0], flattened.y()[0]);
            assertEquals(red.u()[0], flattened.u()[0]);
            assertEquals(red.v()[0], flattened.v()[0]);
            var linear = ResizeOptions.builder().linearLight(true).build();
            var resized = ImageOps.resize(ImageOps.toYuv16(pattern(false), ChromaFormat.YUV420, color, 10), 24, 6,
                    linear);
            assertEquals(color, ((Yuv16Picture) resized).color());
            assertEquals(255, ImageOps.toRgba(yuv).pixels()[0] & 0xFF);
        }

        private static RgbaPicture solid(int rgb) {
            var picture = RgbaPicture.allocate(2, 2, false);
            for (var i = 0; i < 4; i++) {
                picture.pixels()[4 * i] = (byte) (rgb >> 16);
                picture.pixels()[4 * i + 1] = (byte) (rgb >> 8);
                picture.pixels()[4 * i + 2] = (byte) rgb;
                picture.pixels()[4 * i + 3] = (byte) 255;
            }
            return picture;
        }

        private static RgbaPicture solidTransparent() {
            var picture = RgbaPicture.allocate(2, 2, true);
            for (var i = 0; i < 4; i++) {
                picture.pixels()[4 * i + 1] = (byte) 200;
            }
            return picture;
        }
    }

    @Nested
    @DisplayName("robustness")
    class Robustness {
        private static final List<MatrixCoefficients> ALL_MATRICES = List.of(MatrixCoefficients.SMPTE2085,
                MatrixCoefficients.BT2020_CL, MatrixCoefficients.CHROMA_DERIVED_NCL,
                MatrixCoefficients.CHROMA_DERIVED_CL, MatrixCoefficients.ICTCP, MatrixCoefficients.IPT_C2,
                MatrixCoefficients.YCGCO_RE, MatrixCoefficients.YCGCO_RO);

        private static final List<TransferCharacteristics> TRANSFERS = List.of(TransferCharacteristics.PQ,
                TransferCharacteristics.HLG, TransferCharacteristics.LOG100, TransferCharacteristics.LOG316,
                TransferCharacteristics.SMPTE428, TransferCharacteristics.BT1361, TransferCharacteristics.IEC61966_2_4,
                TransferCharacteristics.LINEAR, TransferCharacteristics.UNSPECIFIED);

        // Codes over the whole sample range, far outside the R'G'B' cube for most of them, with the corners of the
        // code cube in the first pixels.
        private static void fill(short[] plane, int max, SplittableRandom random, int corner) {
            for (var i = 0; i < plane.length; i++) {
                plane[i] = (short) (i < 8 ? ((i >> corner) & 1) * max : random.nextInt(max + 1));
            }
        }

        @Test
        @DisplayName("arbitrary codes of odd-sized pictures in every layout convert and agree across depths")
        void arbitraryCodes() {
            var random = new SplittableRandom(17);
            var formats = ChromaFormat.values();
            for (var matrix : ALL_MATRICES) {
                for (var transfer : TRANSFERS) {
                    for (var range : ColorRange.values()) {
                        var color = new ColorInfo(matrix, range, ColorPrimaries.BT2020, transfer);
                        var format = formats[random.nextInt(formats.length)];
                        var yuv = Yuv16Picture.allocate(7, 5, format, 10, true, color);
                        fill(yuv.y(), 1023, random, 0);
                        if (format.hasChroma()) {
                            fill(yuv.u(), 1023, random, 1);
                            fill(yuv.v(), 1023, random, 2);
                        }
                        fill(yuv.alpha(), 1023, random, 0);
                        var wide = ImageOps.toRgba16(yuv);
                        var narrow = ImageOps.toRgba(yuv);
                        assertTrue(maxDifference(ImageOps.toEightBit(wide), narrow) <= 1, color + " " + format);
                        for (var target : formats) {
                            var back = ImageOps.toYuv16(wide, target, color, 12);
                            assertEquals(color, back.color());
                            var bt709 = ImageOps.toYuv16(yuv, target, ColorInfo.BT709_LIMITED, 10,
                                    ChromaDownsampling.ALPHA_WEIGHTED);
                            assertEquals(target, bt709.chromaFormat());
                            var eight = ImageOps.toYuv(ImageOps.toEightBit(yuv), target, color);
                            assertEquals(color, eight.color());
                        }
                    }
                }
            }
        }

        @Test
        @DisplayName("chroma is reconstructed like the fixed-point matrices reconstruct it, in every layout and mode")
        void upsamplingMatchesFixedPoint() {
            // The chromaticity-derived weights of the BT.709 primaries are within 5e-5 of the BT.709 matrix, so the
            // two conversions of the same samples differ only by that, unless their chroma reconstruction differs.
            var random = new SplittableRandom(29);
            var derived = new ColorInfo(MatrixCoefficients.CHROMA_DERIVED_NCL, ColorRange.LIMITED, ColorPrimaries.BT709,
                    TransferCharacteristics.BT709);
            for (var format : ChromaFormat.values()) {
                var yuv = Yuv16Picture.allocate(23, 11, format, 10, false, derived);
                for (var i = 0; i < yuv.y().length; i++) {
                    yuv.y()[i] = (short) (64 + random.nextInt(877));
                }
                if (format.hasChroma()) {
                    for (var i = 0; i < yuv.u().length; i++) {
                        yuv.u()[i] = (short) (64 + random.nextInt(897));
                        yuv.v()[i] = (short) (64 + random.nextInt(897));
                    }
                }
                var bt709 = yuv.toBuilder().color(ColorInfo.BT709_LIMITED.withPrimaries(ColorPrimaries.BT709)
                        .withTransfer(TransferCharacteristics.BT709)).build();
                for (var upsampling : ChromaUpsampling.values()) {
                    var actual = ImageOps.toRgba16(yuv, upsampling);
                    var expected = ImageOps.toRgba16(bt709, upsampling);
                    for (var i = 0; i < actual.pixels().length; i++) {
                        assertTrue(Math.abs((actual.pixels()[i] & 0xFFFF) - (expected.pixels()[i] & 0xFFFF)) <= 16,
                                format + " " + upsampling + " sample " + i);
                    }
                }
            }
        }

        @Test
        @DisplayName("pictures split into parallel bands convert like their rows converted alone")
        void bands() {
            var random = new SplittableRandom(23);
            var rgba = Rgba16Picture.allocate(256, 96, true);
            for (var i = 0; i < rgba.pixels().length; i++) {
                rgba.pixels()[i] = (short) random.nextInt(65536);
            }
            for (var matrix : ALL_MATRICES) {
                var color = matrix == MatrixCoefficients.YCGCO_RE || matrix == MatrixCoefficients.YCGCO_RO
                        ? new ColorInfo(matrix, ColorRange.LIMITED, ColorPrimaries.BT709, TransferCharacteristics.BT709)
                        : color(matrix, ColorRange.LIMITED);
                var whole = ImageOps.toYuv16(rgba, ChromaFormat.YUV420, color, 10, ChromaDownsampling.ALPHA_WEIGHTED);
                for (var pair = 0; pair < 48; pair++) {
                    var rows = ImageOps.toYuv16((Rgba16Picture) ImageOps.crop(rgba, 0, 2 * pair, 256, 2),
                            ChromaFormat.YUV420, color, 10, ChromaDownsampling.ALPHA_WEIGHTED);
                    for (var x = 0; x < 256; x++) {
                        assertEquals(whole.y()[2 * pair * whole.yStride() + x], rows.y()[x], matrix + " luma");
                        assertEquals(whole.y()[(2 * pair + 1) * whole.yStride() + x], rows.y()[rows.yStride() + x]);
                    }
                    for (var x = 0; x < 128; x++) {
                        assertEquals(whole.u()[pair * whole.uvStride() + x], rows.u()[x], matrix + " Cb");
                        assertEquals(whole.v()[pair * whole.uvStride() + x], rows.v()[x], matrix + " Cr");
                    }
                }
                var horizontal = ImageOps.toYuv16(rgba, ChromaFormat.YUV422, color, 10);
                var decoded = ImageOps.toRgba16(horizontal);
                for (var row = 0; row < 96; row += 7) {
                    var alone = ImageOps.toRgba16(ImageOps.crop(horizontal, 0, row, 256, 1));
                    for (var x = 0; x < 4 * 256; x++) {
                        assertEquals(decoded.pixels()[row * decoded.stride() + x], alone.pixels()[x], matrix + " RGB");
                    }
                }
            }
        }
    }

    @Nested
    @DisplayName("zimg oracle")
    class Zimg {
        // zimg converts gamma-encoded R'G'B' to linear light with the display (BT.1886) function before a
        // constant-luminance or ICtCp matrix, where H.273 inverts the transfer function itself; those matrices are
        // therefore fed linear light, computed here with the published inverse functions, and read back as linear
        // light. zimg rejects constant luminance with PQ and applies the HLG system gamma, so those combinations have
        // no oracle and are covered by the golden tests.
        static Stream<Arguments> oracleDescriptions() {
            return Stream.of(
                    Arguments.of(MatrixCoefficients.BT2020_CL, TransferCharacteristics.BT2020_10, ColorPrimaries.BT2020),
                    Arguments.of(MatrixCoefficients.CHROMA_DERIVED_CL, TransferCharacteristics.BT709,
                            ColorPrimaries.SMPTE432),
                    Arguments.of(MatrixCoefficients.CHROMA_DERIVED_CL, TransferCharacteristics.BT709,
                            ColorPrimaries.FILM),
                    Arguments.of(MatrixCoefficients.CHROMA_DERIVED_NCL, TransferCharacteristics.BT709,
                            ColorPrimaries.BT709),
                    Arguments.of(MatrixCoefficients.CHROMA_DERIVED_NCL, TransferCharacteristics.BT709,
                            ColorPrimaries.SMPTE432),
                    Arguments.of(MatrixCoefficients.ICTCP, TransferCharacteristics.PQ, ColorPrimaries.BT2020));
        }

        private static boolean linearInput(MatrixCoefficients matrix) {
            return matrix != MatrixCoefficients.CHROMA_DERIVED_NCL;
        }

        private static String name(MatrixCoefficients matrix) {
            return switch (matrix) {
                case BT2020_CL -> "2020_cl";
                case CHROMA_DERIVED_CL -> "chroma-derived-c";
                case CHROMA_DERIVED_NCL -> "chroma-derived-nc";
                case ICTCP -> "ictcp";
                default -> throw new IllegalArgumentException(matrix.toString());
            };
        }

        private static String name(TransferCharacteristics transfer) {
            return switch (transfer) {
                case BT2020_10 -> "bt2020-10";
                case BT709 -> "bt709";
                case PQ -> "smpte2084";
                default -> throw new IllegalArgumentException(transfer.toString());
            };
        }

        private static String name(ColorPrimaries primaries) {
            return switch (primaries) {
                case BT2020 -> "2020";
                case BT709 -> "709";
                case SMPTE432 -> "smpte432";
                case FILM -> "film";
                default -> throw new IllegalArgumentException(primaries.toString());
            };
        }

        private static String zscale(MatrixCoefficients matrix, TransferCharacteristics transfer,
                                     ColorPrimaries primaries, boolean toYuv) {
            var p = name(primaries);
            var linear = linearInput(matrix);
            var yuvSide = "matrix%s=" + name(matrix) + ":range%s=limited:transfer%s=" + name(transfer)
                    + ":primaries%s=" + p;
            var rgbSide = "matrix%s=gbr:range%s=full:transfer%s=" + (linear ? "linear" : name(transfer))
                    + ":primaries%s=" + p;
            var in = (toYuv ? rgbSide : yuvSide).replace("%s", "in");
            var out = (toYuv ? yuvSide : rgbSide).replace("%s", "");
            var rgbFormat = linear ? "gbrpf32le" : "gbrp16le";
            return "zscale=" + in + ":" + out + ":dither=none:agamma=0:npl=10000,format="
                    + (toYuv ? "yuv444p12le" : rgbFormat);
        }

        // The published functions, written out independently of the library: the BT.709 and BT.2020 opto-electronic
        // function and the PQ electro-optical function, with linear 1 at 10000 candelas per square meter.
        private static double toLinear(TransferCharacteristics transfer, double v) {
            if (transfer == TransferCharacteristics.PQ) {
                var p = Math.pow(v, 32 / 2523.0);
                return Math.pow(Math.max(p - 107 / 128.0, 0) / (2413 / 128.0 - 2392 / 128.0 * p), 8192 / 1305.0);
            }
            var alpha = 1.099296826809442;
            var beta = 0.018053968510807;
            return v < 4.5 * beta ? v / 4.5 : Math.pow((v + alpha - 1) / alpha, 1 / 0.45);
        }

        private static double toSignal(TransferCharacteristics transfer, double l) {
            if (transfer == TransferCharacteristics.PQ) {
                var p = Math.pow(Math.max(l, 0), 1305 / 8192.0);
                return Math.pow((107 / 128.0 + 2413 / 128.0 * p) / (1 + 2392 / 128.0 * p), 2523 / 32.0);
            }
            var alpha = 1.099296826809442;
            var beta = 0.018053968510807;
            return l < beta ? 4.5 * l : alpha * Math.pow(l, 0.45) - (alpha - 1);
        }

        private static byte[] input(Rgba16Picture rgba, MatrixCoefficients matrix, TransferCharacteristics transfer) {
            var n = WIDTH * HEIGHT;
            var linear = linearInput(matrix);
            var buffer = ByteBuffer.allocate((linear ? 12 : 6) * n).order(ByteOrder.LITTLE_ENDIAN);
            for (var channel : new int[]{1, 2, 0}) {
                for (var i = 0; i < n; i++) {
                    var sample = rgba.pixels()[4 * i + channel];
                    if (linear) {
                        buffer.putFloat((float) toLinear(transfer, (sample & 0xFFFF) / 65535.0));
                    } else {
                        buffer.putShort(sample);
                    }
                }
            }
            return buffer.array();
        }

        @ParameterizedTest(name = "{0} {1} {2}")
        @MethodSource("oracleDescriptions")
        @DisplayName("R'G'B' to 12-bit Y'CbCr matches zimg within one code value")
        void forward(MatrixCoefficients matrix, TransferCharacteristics transfer, ColorPrimaries primaries) {
            Oracle.assume(Oracle.Tool.FFMPEG);
            var color = new ColorInfo(matrix, ColorRange.LIMITED, primaries, transfer);
            var rgba = pattern(false);
            var expected = Pictures.ffmpeg(input(rgba, matrix, transfer), linearInput(matrix) ? "gbrpf32le"
                    : "gbrp16le", WIDTH, HEIGHT, zscale(matrix, transfer, primaries, true), "yuv444p12le");
            var actual = ImageOps.toYuv16(rgba, ChromaFormat.YUV444, color, 12);
            var samples = shorts(expected);
            var worst = 0;
            short[][] planes = {actual.y(), actual.u(), actual.v()};
            for (var c = 0; c < 3; c++) {
                for (var i = 0; i < WIDTH * HEIGHT; i++) {
                    worst = Math.max(worst, Math.abs((planes[c][i] & 0xFFFF) - samples[c * WIDTH * HEIGHT + i]));
                }
            }
            assertTrue(worst <= 1, color + ": " + worst);
        }

        @ParameterizedTest(name = "{0} {1} {2}")
        @MethodSource("oracleDescriptions")
        @DisplayName("12-bit Y'CbCr to R'G'B' matches zimg within half a 12-bit step or 0.2% of the linear light")
        void inverse(MatrixCoefficients matrix, TransferCharacteristics transfer, ColorPrimaries primaries) {
            Oracle.assume(Oracle.Tool.FFMPEG);
            var color = new ColorInfo(matrix, ColorRange.LIMITED, primaries, transfer);
            var yuvRaw = Pictures.ffmpeg(input(pattern(false), matrix, transfer), linearInput(matrix) ? "gbrpf32le"
                    : "gbrp16le", WIDTH, HEIGHT, zscale(matrix, transfer, primaries, true), "yuv444p12le");
            var samples = shorts(yuvRaw);
            var n = WIDTH * HEIGHT;
            var y = new short[n];
            var u = new short[n];
            var v = new short[n];
            for (var i = 0; i < n; i++) {
                y[i] = (short) samples[i];
                u[i] = (short) samples[n + i];
                v[i] = (short) samples[2 * n + i];
            }
            var yuv = Yuv16Picture.builder().size(WIDTH, HEIGHT).chromaFormat(ChromaFormat.YUV444).bitDepth(12)
                    .color(color).luma(y, WIDTH).chroma(u, v, WIDTH).build();
            var linear = linearInput(matrix);
            var raw = Pictures.ffmpeg(yuvRaw, "yuv444p12le", WIDTH, HEIGHT, zscale(matrix, transfer, primaries, false),
                    linear ? "gbrpf32le" : "gbrp16le");
            var buffer = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
            var actual = ImageOps.toRgba16(yuv);
            var worst = 0.0;
            int[] planeOf = {2, 0, 1};
            for (var i = 0; i < n; i++) {
                var peak = 1e-4;
                var reference = new double[3];
                for (var c = 0; c < 3; c++) {
                    var index = planeOf[c] * n + i;
                    reference[c] = linear ? Math.max(buffer.getFloat(4 * index), 0)
                            : (buffer.getShort(2 * index) & 0xFFFF) / 65535.0;
                    peak = Math.max(peak, reference[c]);
                }
                for (var c = 0; c < 3; c++) {
                    var signal = (actual.pixels()[4 * i + c] & 0xFFFF) / 65535.0;
                    // linear light is compared relative to the pixel's brightest channel, since the steep start of
                    // the transfer function makes the signal of a near-black channel next to a bright one arbitrary
                    var error = linear ? Math.abs(toLinear(transfer, signal) - Math.min(reference[c],
                            toLinear(transfer, 1))) / peak : Math.abs(signal - reference[c]) * 4095;
                    worst = Math.max(worst, error);
                }
            }
            assertTrue(worst <= (linear ? 2e-3 : 0.5), color + ": " + worst);
        }

        private static int[] shorts(byte[] raw) {
            var buffer = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
            var out = new int[raw.length / 2];
            for (var i = 0; i < out.length; i++) {
                out[i] = buffer.getShort() & 0xFFFF;
            }
            return out;
        }

        @Test
        @DisplayName("the oracle itself distinguishes the constant-luminance matrix from BT.2020 non-constant")
        void oracleSensitivity() {
            Oracle.assume(Oracle.Tool.FFMPEG);
            var rgba = pattern(false);
            var matrix = MatrixCoefficients.BT2020_CL;
            var transfer = TransferCharacteristics.BT2020_10;
            var cl = Pictures.ffmpeg(input(rgba, matrix, transfer), "gbrpf32le", WIDTH, HEIGHT,
                    zscale(matrix, transfer, ColorPrimaries.BT2020, true), "yuv444p12le");
            var ncl = ImageOps.toYuv16(rgba, ChromaFormat.YUV444, new ColorInfo(MatrixCoefficients.BT2020_NCL,
                    ColorRange.LIMITED, ColorPrimaries.BT2020, transfer), 12);
            var samples = shorts(cl);
            var worst = 0;
            for (var i = 0; i < WIDTH * HEIGHT; i++) {
                worst = Math.max(worst, Math.abs((ncl.u()[i] & 0xFFFF) - samples[WIDTH * HEIGHT + i]));
            }
            assertTrue(worst > 16, "the constant-luminance chroma should differ: " + worst);
        }
    }

    private static int maxDifference(RgbaPicture a, RgbaPicture b) {
        var worst = 0;
        for (var y = 0; y < a.height(); y++) {
            for (var x = 0; x < 4 * a.width(); x++) {
                worst = Math.max(worst, Math.abs((a.pixels()[y * a.stride() + x] & 0xFF)
                        - (b.pixels()[y * b.stride() + x] & 0xFF)));
            }
        }
        return worst;
    }

    private static int maxDifference(Yuv16Picture a, Yuv16Picture b) {
        var worst = 0;
        short[][] left = {a.y(), a.u(), a.v()};
        short[][] right = {b.y(), b.u(), b.v()};
        for (var c = 0; c < 3; c++) {
            for (var i = 0; i < left[c].length; i++) {
                worst = Math.max(worst, Math.abs((left[c][i] & 0xFFFF) - (right[c][i] & 0xFFFF)));
            }
        }
        return worst;
    }
}
