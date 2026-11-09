package com.github.auties00.smalt.image.ops;

import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.Yuv16Picture;
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
import java.util.SplittableRandom;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers resampling at 16 bits and in linear light: a double-precision reference applying the same
 * taps, agreement with the 8-bit path, the precision of a 65536-level ramp, premultiplied alpha,
 * the linear-light checkerboard, ffmpeg's scaler on 10 and 16-bit planes, and the result types.
 */
@DisplayName("16-bit and linear-light resampling")
class HighDepthResizeTest {
    static Rgba16Picture randomRgba16(SplittableRandom random, int width, int height, boolean alpha) {
        var stride = 4 * width + 4 * random.nextInt(3);
        var pixels = new short[stride * height];
        for (var i = 0; i < pixels.length; i++) {
            pixels[i] = (short) random.nextInt(65536);
        }
        if (!alpha) {
            for (var y = 0; y < height; y++) {
                for (var x = 0; x < width; x++) {
                    pixels[y * stride + 4 * x + 3] = (short) 0xFFFF;
                }
            }
        }
        return Rgba16Picture.of(width, height, stride, pixels, alpha);
    }

    static Yuv16Picture randomYuv16(SplittableRandom random, int width, int height, ChromaFormat format, int depth,
                                    boolean alpha, ColorInfo color) {
        var max = 1 << depth;
        var yStride = width + random.nextInt(3);
        var y = samples(random, yStride * height, max);
        var builder = Yuv16Picture.builder().size(width, height).chromaFormat(format).bitDepth(depth).color(color)
                .luma(y, yStride);
        if (format.hasChroma()) {
            var uvStride = format.chromaWidth(width) + random.nextInt(3);
            var chroma = uvStride * format.chromaHeight(height);
            builder.chroma(samples(random, chroma, max), samples(random, chroma, max), uvStride);
        }
        if (alpha) {
            var aStride = width + random.nextInt(3);
            builder.alpha(samples(random, aStride * height, max), aStride);
        }
        return builder.build();
    }

    private static short[] samples(SplittableRandom random, int length, int bound) {
        var array = new short[length];
        for (var i = 0; i < length; i++) {
            array[i] = (short) random.nextInt(bound);
        }
        return array;
    }

    /** Applies the 24-bit taps in double precision, separably, with no intermediate rounding. */
    static double[] reference(short[] src, int stride, int channels, int inWidth, int inHeight, int outWidth,
                              int outHeight, ResizeFilter filter, int channel, boolean premultiplied) {
        var horizontal = ResampleWeights.compute(filter, inWidth, outWidth, 0, inWidth, 1, DoubleResampler.WEIGHT_BITS);
        var vertical = ResampleWeights.compute(filter, inHeight, outHeight, 0, inHeight, 1, DoubleResampler.WEIGHT_BITS);
        var one = (double) (1 << DoubleResampler.WEIGHT_BITS);
        var out = new double[outWidth * outHeight];
        for (var oy = 0; oy < outHeight; oy++) {
            for (var ox = 0; ox < outWidth; ox++) {
                double color = 0;
                double alpha = 0;
                for (var j = 0; j < vertical.counts()[oy]; j++) {
                    var wy = vertical.weights()[oy * vertical.stride() + j] / one;
                    var y = vertical.starts()[oy] + j;
                    for (var i = 0; i < horizontal.counts()[ox]; i++) {
                        var wx = horizontal.weights()[ox * horizontal.stride() + i] / one;
                        var x = horizontal.starts()[ox] + i;
                        var value = src[y * stride + channels * x + channel] & 0xFFFF;
                        var a = premultiplied ? src[y * stride + channels * x + 3] & 0xFFFF : 1;
                        color += wx * wy * value * a;
                        alpha += wx * wy * a;
                    }
                }
                out[oy * outWidth + ox] = premultiplied ? (alpha <= 0 ? 0 : color / alpha) : color;
            }
        }
        return out;
    }

    static Stream<Arguments> referenceCases() {
        var arguments = new ArrayList<Arguments>();
        for (var filter : ResizeTest.FILTERED) {
            arguments.add(Arguments.of(filter, 90, 70, 31, 23));
            arguments.add(Arguments.of(filter, 31, 23, 90, 70));
            arguments.add(Arguments.of(filter, 1000, 6, 7, 2));
            arguments.add(Arguments.of(filter, 37, 41, 37, 13));
            arguments.add(Arguments.of(filter, 1, 1, 3, 2));
            arguments.add(Arguments.of(filter, 7, 3, 1, 1));
            arguments.add(Arguments.of(filter, 2, 9, 5, 1));
        }
        return arguments.stream();
    }

    @Nested
    @DisplayName("double-precision reference")
    class Reference {
        @ParameterizedTest(name = "{0} {1}x{2} -> {3}x{4}")
        @MethodSource("com.github.auties00.smalt.image.ops.HighDepthResizeTest#referenceCases")
        @DisplayName("16-bit planes stay within half a level of exact arithmetic")
        void plane(ResizeFilter filter, int inWidth, int inHeight, int outWidth, int outHeight) {
            var random = new SplittableRandom(inWidth * 31L + outWidth);
            var picture = randomYuv16(random, inWidth, inHeight, ChromaFormat.MONOCHROME, 16, false, ColorInfo.JPEG);
            var resized = (Yuv16Picture) ImageOps.resize(picture, outWidth, outHeight, filter);
            assertEquals(16, resized.bitDepth());
            var expected = reference(picture.y(), picture.yStride(), 1, inWidth, inHeight, outWidth, outHeight, filter, 0,
                    false);
            for (var y = 0; y < outHeight; y++) {
                for (var x = 0; x < outWidth; x++) {
                    var clamped = Math.clamp(expected[y * outWidth + x], 0, 65535);
                    assertEquals(clamped, resized.y()[y * resized.yStride() + x] & 0xFFFF, 0.51, x + "," + y);
                }
            }
        }

        @ParameterizedTest(name = "{0} {1}x{2} -> {3}x{4}")
        @MethodSource("com.github.auties00.smalt.image.ops.HighDepthResizeTest#referenceCases")
        @DisplayName("10-bit planes clamp to their own range and stay within half a level")
        void tenBit(ResizeFilter filter, int inWidth, int inHeight, int outWidth, int outHeight) {
            var random = new SplittableRandom(inHeight * 17L + outHeight);
            var picture = randomYuv16(random, inWidth, inHeight, ChromaFormat.MONOCHROME, 10, true, ColorInfo.BT709_LIMITED);
            var resized = (Yuv16Picture) ImageOps.resize(picture, outWidth, outHeight, filter);
            var luma = reference(picture.y(), picture.yStride(), 1, inWidth, inHeight, outWidth, outHeight, filter, 0, false);
            var alpha = reference(picture.alpha(), picture.alphaStride(), 1, inWidth, inHeight, outWidth, outHeight, filter, 0,
                    false);
            for (var y = 0; y < outHeight; y++) {
                for (var x = 0; x < outWidth; x++) {
                    assertEquals(Math.clamp(luma[y * outWidth + x], 0, 1023), resized.y()[y * resized.yStride() + x], 0.51);
                    assertEquals(Math.clamp(alpha[y * outWidth + x], 0, 1023), resized.alpha()[y * resized.alphaStride() + x],
                            0.51);
                }
            }
        }

        @ParameterizedTest(name = "{0} {1}x{2} -> {3}x{4}")
        @MethodSource("com.github.auties00.smalt.image.ops.HighDepthResizeTest#referenceCases")
        @DisplayName("premultiplied 16-bit RGBA stays within half a level of exact arithmetic")
        void premultiplied(ResizeFilter filter, int inWidth, int inHeight, int outWidth, int outHeight) {
            var random = new SplittableRandom(inWidth * 7L + inHeight);
            var picture = randomRgba16(random, inWidth, inHeight, true);
            var resized = (Rgba16Picture) ImageOps.resize(picture, outWidth, outHeight, filter);
            var alpha = reference(picture.pixels(), picture.stride(), 4, inWidth, inHeight, outWidth, outHeight, filter, 3,
                    false);
            for (var c = 0; c < 3; c++) {
                var color = reference(picture.pixels(), picture.stride(), 4, inWidth, inHeight, outWidth, outHeight, filter, c,
                        true);
                for (var y = 0; y < outHeight; y++) {
                    for (var x = 0; x < outWidth; x++) {
                        var a = alpha[y * outWidth + x];
                        var actual = resized.pixels()[y * resized.stride() + 4 * x + c] & 0xFFFF;
                        if (c == 0) {
                            assertEquals(Math.clamp(a, 0, 65535), resized.pixels()[y * resized.stride() + 4 * x + 3] & 0xFFFF,
                                    0.51, "alpha at " + x + "," + y);
                        }
                        if (a < 1) {
                            // negative lobes can leave an alpha too close to zero for a stable division
                            continue;
                        }
                        assertEquals(Math.clamp(color[y * outWidth + x], 0, 65535), actual, 0.51,
                                "channel " + c + " at " + x + "," + y);
                    }
                }
            }
        }

        @Test
        @DisplayName("a banded 12-bit 1080p resize matches the reference everywhere")
        void banded() {
            var random = new SplittableRandom(77);
            var picture = randomYuv16(random, 1920, 1080, ChromaFormat.YUV420, 12, false, ColorInfo.BT709_LIMITED);
            var resized = (Yuv16Picture) ImageOps.resize(picture, 1280, 720, ResizeFilter.LANCZOS3);
            var expected = reference(picture.u(), picture.uvStride(), 1, 960, 540, 640, 360, ResizeFilter.LANCZOS3, 0, false);
            for (var i = 0; i < expected.length; i++) {
                var actual = resized.u()[(i / 640) * resized.uvStride() + i % 640];
                assertEquals(Math.clamp(expected[i], 0, 4095), actual, 0.51, "chroma sample " + i);
            }
            for (var i = 0; i < 3; i++) {
                var again = (Yuv16Picture) ImageOps.resize(picture, 1280, 720, ResizeFilter.LANCZOS3);
                assertArrayEquals(resized.y(), again.y());
                assertArrayEquals(resized.u(), again.u());
            }
        }
    }

    @Nested
    @DisplayName("agreement with the 8-bit path")
    class EightBit {
        @ParameterizedTest(name = "{0}")
        @EnumSource(ResizeFilter.class)
        @DisplayName("reducing the 16-bit result of a widened picture is within one level of the 8-bit result")
        void widened(ResizeFilter filter) {
            var random = new SplittableRandom(filter.ordinal());
            for (var size : new int[][]{{57, 33, 20, 41}, {64, 48, 32, 24}, {20, 15, 47, 39}}) {
                var rgba = Pictures.randomRgba(random, size[0], size[1], true);
                var opaque = Pictures.randomRgba(random, size[0], size[1], false);
                var yuv = Pictures.randomYuv(random, size[0], size[1], ChromaFormat.YUV420, true, ColorInfo.BT601_LIMITED);
                assertWithinOne(ImageOps.resize(rgba, size[2], size[3], filter),
                        ImageOps.toEightBit(ImageOps.resize(ImageOps.toSixteenBit(rgba), size[2], size[3], filter)));
                assertWithinOne(ImageOps.resize(opaque, size[2], size[3], filter),
                        ImageOps.toEightBit(ImageOps.resize(ImageOps.toSixteenBit(opaque), size[2], size[3], filter)));
                var deep = ImageOps.toBitDepth(yuv, 10);
                assertWithinOne(ImageOps.resize(yuv, size[2], size[3], filter),
                        ImageOps.toEightBit(ImageOps.resize(deep, size[2], size[3], filter)));
            }
        }

        static void assertWithinOne(Picture expected, Picture actual) {
            switch (expected) {
                case RgbaPicture rgba -> {
                    var other = assertInstanceOf(RgbaPicture.class, actual);
                    assertEquals(rgba.hasAlpha(), other.hasAlpha());
                    var a = Pictures.tight(rgba);
                    var b = Pictures.tight(other);
                    for (var i = 0; i < a.length; i++) {
                        assertEquals(a[i] & 0xFF, b[i] & 0xFF, 1, "sample " + i);
                    }
                }
                case YuvPicture yuv -> {
                    var a = Pictures.raw(yuv);
                    var b = Pictures.raw(assertInstanceOf(YuvPicture.class, actual));
                    assertEquals(a.length, b.length);
                    for (var i = 0; i < a.length; i++) {
                        assertEquals(a[i] & 0xFF, b[i] & 0xFF, 1, "sample " + i);
                    }
                }
                default -> throw new IllegalArgumentException(expected.toString());
            }
        }
    }

    @Nested
    @DisplayName("precision")
    class Precision {
        /** Returns the 65536-level ramp {@code 4x + y} over 16384 columns and 4 rows. */
        static Yuv16Picture ramp() {
            var ramp = Yuv16Picture.allocate(16384, 4, ChromaFormat.MONOCHROME, 16, false, ColorInfo.JPEG);
            for (var y = 0; y < 4; y++) {
                for (var x = 0; x < 16384; x++) {
                    ramp.y()[y * 16384 + x] = (short) (4 * x + y);
                }
            }
            return ramp;
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(ResizeFilter.class)
        @DisplayName("a 65536-level ramp stays monotonic and keeps every level when reduced and enlarged")
        void ramp(ResizeFilter filter) {
            var ramp = ramp();
            var reduced = (Yuv16Picture) ImageOps.resize(ramp, 12000, 4, filter);
            var margin = 8;
            var oneLevel = 0;
            for (var y = 0; y < 4; y++) {
                for (var x = 1; x < 12000; x++) {
                    var previous = reduced.y()[y * 12000 + x - 1] & 0xFFFF;
                    var current = reduced.y()[y * 12000 + x] & 0xFFFF;
                    assertTrue(current >= previous, "decreases at " + x + "," + y);
                    if (x > margin && x < 12000 - margin) {
                        assertTrue(current > previous, "plateau at " + x + "," + y + ": " + current);
                        if (y > 0 && filter != ResizeFilter.MITCHELL) {
                            // the rows differ by exactly one level, which only a 16-bit path keeps
                            // (the unchanged height is the identity for every filter but Mitchell's,
                            // which does not interpolate); values on a half level may round apart by 0 or 2
                            var difference = current - (reduced.y()[(y - 1) * 12000 + x] & 0xFFFF);
                            assertTrue(difference >= 0 && difference <= 2, x + "," + y + ": " + difference);
                            oneLevel += difference == 1 ? 1 : 0;
                        }
                    }
                }
            }
            assertTrue(filter == ResizeFilter.MITCHELL || oneLevel >= 0.99 * 3 * (12000 - 2 * margin - 1),
                    oneLevel + " rows one level apart");
            // the four rows end in flat replicated edges, where the filters with negative lobes ring
            var positive = switch (filter) {
                case NEAREST, BOX, BILINEAR, AREA -> true;
                default -> false;
            };
            var enlarged = (Yuv16Picture) ImageOps.resize(ramp, 16384, 13, filter);
            var levels = new boolean[65536];
            for (var x = 0; x < 16384; x++) {
                for (var y = 0; y < 13; y++) {
                    var current = enlarged.y()[y * 16384 + x] & 0xFFFF;
                    levels[current] = true;
                    if (y > 0 && positive) {
                        assertTrue(current >= (enlarged.y()[(y - 1) * 16384 + x] & 0xFFFF), "decreases at " + x + "," + y);
                    }
                }
            }
            var distinct = 0;
            for (var level : levels) {
                distinct += level ? 1 : 0;
            }
            assertTrue(distinct >= (positive ? 65536 : 65000), "only " + distinct + " levels");
        }
    }

    @Nested
    @DisplayName("alpha")
    class Alpha {
        @ParameterizedTest(name = "{0}")
        @EnumSource(value = ResizeFilter.class, mode = EnumSource.Mode.EXCLUDE, names = "NEAREST")
        @DisplayName("transparent color never bleeds into visible 16-bit pixels")
        void noBleeding(ResizeFilter filter) {
            var picture = Rgba16Picture.allocate(64, 48, true);
            for (var y = 0; y < 48; y++) {
                for (var x = 0; x < 64; x++) {
                    var index = 4 * (y * 64 + x);
                    var inside = x >= 17 && x < 45 && y >= 11 && y < 30;
                    picture.pixels()[index] = inside ? (short) 0xFFFF : 0;
                    picture.pixels()[index + 1] = inside ? 0 : (short) 0xFFFF;
                    picture.pixels()[index + 3] = inside ? (short) 0xFFFF : 0;
                }
            }
            for (var size : new int[][]{{21, 16}, {37, 29}, {100, 75}, {3, 2}}) {
                var resized = (Rgba16Picture) ImageOps.resize(picture, size[0], size[1], filter);
                for (var i = 0; i < size[0] * size[1]; i++) {
                    if ((resized.pixels()[4 * i + 3] & 0xFFFF) > 0) {
                        assertEquals(0xFFFF, resized.pixels()[4 * i] & 0xFFFF, "red at " + i);
                        assertEquals(0, resized.pixels()[4 * i + 1], "green at " + i);
                        assertEquals(0, resized.pixels()[4 * i + 2], "blue at " + i);
                    }
                }
            }
        }

        @ParameterizedTest(name = "{0} {1} -> {2}")
        @MethodSource("com.github.auties00.smalt.image.ops.ResizeTest#filtersAndSizes")
        @DisplayName("constant 16-bit pictures stay exactly constant")
        void constant(ResizeFilter filter, int in, int out) {
            var inHeight = Math.max(1, 1000 / Math.max(in, out));
            var outHeight = Math.max(1, inHeight * 3 / 2);
            for (var alpha : new boolean[]{false, true}) {
                var picture = Rgba16Picture.allocate(in, inHeight, alpha);
                for (var i = 0; i < in * inHeight; i++) {
                    picture.pixels()[4 * i] = (short) 51234;
                    picture.pixels()[4 * i + 1] = 17;
                    picture.pixels()[4 * i + 2] = (short) 65535;
                    picture.pixels()[4 * i + 3] = alpha ? (short) 4321 : (short) 0xFFFF;
                }
                var resized = (Rgba16Picture) ImageOps.resize(picture, out, outHeight, filter);
                for (var i = 0; i < out * outHeight; i++) {
                    assertEquals(51234, resized.pixels()[4 * i] & 0xFFFF);
                    assertEquals(17, resized.pixels()[4 * i + 1] & 0xFFFF);
                    assertEquals(65535, resized.pixels()[4 * i + 2] & 0xFFFF);
                    assertEquals(alpha ? 4321 : 65535, resized.pixels()[4 * i + 3] & 0xFFFF);
                }
            }
            var yuv = Yuv16Picture.allocate(in, inHeight, ChromaFormat.YUV420, 10, true, ColorInfo.BT709_LIMITED);
            java.util.Arrays.fill(yuv.y(), (short) 940);
            java.util.Arrays.fill(yuv.u(), (short) 64);
            java.util.Arrays.fill(yuv.v(), (short) 960);
            java.util.Arrays.fill(yuv.alpha(), (short) 1);
            var resized = (Yuv16Picture) ImageOps.resize(yuv, out, outHeight, filter);
            for (var sample : resized.y()) {
                assertEquals(940, sample);
            }
            for (var sample : resized.u()) {
                assertEquals(64, sample);
            }
            for (var sample : resized.v()) {
                assertEquals(960, sample);
            }
            for (var sample : resized.alpha()) {
                assertEquals(1, sample);
            }
        }

        @Test
        @DisplayName("huge premultiplied vertical reductions accumulate in chunks exactly like the row ring")
        void chunked() {
            var random = new SplittableRandom(10);
            var wide = randomRgba16(random, 2000, 4000, true);
            var narrow = (Rgba16Picture) ImageOps.crop(wide, 0, 0, 24, 4000);
            for (var filter : new ResizeFilter[]{ResizeFilter.BILINEAR, ResizeFilter.AREA}) {
                var chunked = (Rgba16Picture) ImageOps.resize(wide, 2000, 3, filter);
                var ring = (Rgba16Picture) ImageOps.resize(narrow, 24, 3, filter);
                for (var y = 0; y < 3; y++) {
                    for (var i = 0; i < 4 * 24; i++) {
                        assertEquals(ring.pixels()[y * ring.stride() + i], chunked.pixels()[y * chunked.stride() + i],
                                filter + " row " + y + " sample " + i);
                    }
                }
            }
        }

        @Test
        @DisplayName("pictures without alpha get opaque alpha, nearest copies samples exactly")
        void opaqueAndNearest() {
            var random = new SplittableRandom(6);
            var picture = randomRgba16(random, 30, 20, true);
            var opaque = Rgba16Picture.of(30, 20, picture.stride(), picture.pixels(), false);
            for (var filter : ResizeFilter.values()) {
                var resized = (Rgba16Picture) ImageOps.resize(opaque, 17, 9, filter);
                for (var i = 0; i < 17 * 9; i++) {
                    assertEquals((short) 0xFFFF, resized.pixels()[4 * i + 3]);
                }
            }
            var nearest = (Rgba16Picture) ImageOps.resize(picture, 15, 10, ResizeFilter.NEAREST);
            for (var y = 0; y < 10; y++) {
                for (var x = 0; x < 15; x++) {
                    for (var c = 0; c < 4; c++) {
                        assertEquals(picture.pixels()[(2 * y + 1) * picture.stride() + 4 * (2 * x + 1) + c],
                                nearest.pixels()[y * nearest.stride() + 4 * x + c]);
                    }
                }
            }
        }
    }

    @Nested
    @DisplayName("linear light")
    class LinearLight {
        static final ResizeOptions LINEAR = ResizeOptions.builder().filter(ResizeFilter.AREA).linearLight(true).build();

        static RgbaPicture checkerboard(int size) {
            var picture = RgbaPicture.allocate(size, size, false);
            for (var y = 0; y < size; y++) {
                for (var x = 0; x < size; x++) {
                    var value = ((x ^ y) & 1) == 0 ? (byte) 0 : (byte) 255;
                    var index = 4 * (y * size + x);
                    picture.pixels()[index] = value;
                    picture.pixels()[index + 1] = value;
                    picture.pixels()[index + 2] = value;
                    picture.pixels()[index + 3] = (byte) 255;
                }
            }
            return picture;
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(value = ResizeFilter.class, mode = EnumSource.Mode.EXCLUDE, names = "NEAREST")
        @DisplayName("a black and white checkerboard halves to sRGB 188 in linear light and 128 otherwise")
        void checkerboard(ResizeFilter filter) {
            var board = checkerboard(64);
            var options = ResizeOptions.builder().filter(filter).linearLight(true).build();
            var linear = (RgbaPicture) ImageOps.resize(board, 32, 32, options);
            var gamma = (RgbaPicture) ImageOps.resize(board, 32, 32, filter);
            // taps folded onto the edge pixels break the pairing of black and white samples at the
            // borders of the wider filters, so the interior is compared
            var margin = 1 + filter.doubleSupport();
            for (var y = margin; y < 32 - margin; y++) {
                for (var x = margin; x < 32 - margin; x++) {
                    for (var c = 0; c < 3; c++) {
                        assertEquals(188, linear.pixels()[y * linear.stride() + 4 * x + c] & 0xFF, x + "," + y);
                        assertEquals(128, gamma.pixels()[y * gamma.stride() + 4 * x + c] & 0xFF, 1, x + "," + y);
                    }
                }
            }
            var deep = (Rgba16Picture) ImageOps.resize(ImageOps.toSixteenBit(board), 32, 32, options);
            var expected = (int) Math.round(65535 * (1.055 * Math.pow(0.5, 1 / 2.4) - 0.055));
            assertEquals(48192, expected);
            for (var y = margin; y < 32 - margin; y++) {
                for (var x = margin; x < 32 - margin; x++) {
                    assertEquals(expected, deep.pixels()[y * deep.stride() + 4 * x] & 0xFFFF, 1, x + "," + y);
                }
            }
        }

        @Test
        @DisplayName("planar pictures resample in linear light and keep their layout and color")
        void planar() {
            var board = ImageOps.toYuv(checkerboard(64), ChromaFormat.YUV444, ColorInfo.JPEG);
            var linear = (YuvPicture) ImageOps.resize(board, 32, 32, LINEAR);
            assertEquals(ChromaFormat.YUV444, linear.chromaFormat());
            assertEquals(ColorInfo.JPEG, linear.color());
            for (var i = 0; i < 32 * 32; i++) {
                assertEquals(188, linear.y()[i] & 0xFF, 1);
                assertEquals(128, linear.u()[i] & 0xFF, 1);
            }
            var deep = ImageOps.toYuv16(checkerboard(64), ChromaFormat.YUV420, ColorInfo.BT709_LIMITED, 10);
            var resized = (Yuv16Picture) ImageOps.resize(deep, 32, 32, LINEAR);
            assertEquals(10, resized.bitDepth());
            assertEquals(ChromaFormat.YUV420, resized.chromaFormat());
            // sRGB 188 in limited-range 10-bit luma: (16 + 219 * 0.7354) * 4
            for (var sample : resized.y()) {
                assertEquals((16 + 219 * 0.73536) * 4, sample, 2);
            }
        }

        @Test
        @DisplayName("planar pictures with alpha keep the chroma of visible pixels next to transparent ones")
        void planarAlpha() {
            // red is visible up to column 4; the box halving makes output column 2 half-covered red and column 3
            // transparent, and both share one 4:2:0 chroma sample
            var rgba = RgbaPicture.allocate(16, 4, true);
            for (var y = 0; y < 4; y++) {
                for (var x = 0; x < 16; x++) {
                    var p = y * rgba.stride() + 4 * x;
                    var visible = x < 5;
                    rgba.pixels()[p] = (byte) (visible ? 255 : 0);
                    rgba.pixels()[p + 1] = (byte) (visible ? 0 : 255);
                    rgba.pixels()[p + 3] = (byte) (visible ? 255 : 0);
                }
            }
            var red = RgbaPicture.allocate(2, 2, false);
            for (var i = 0; i < 4; i++) {
                red.pixels()[4 * i] = (byte) 255;
                red.pixels()[4 * i + 3] = (byte) 255;
            }
            var options = ResizeOptions.builder().filter(ResizeFilter.BOX).linearLight(true).build();
            var yuv = ImageOps.toYuv(rgba, ChromaFormat.YUV420, ColorInfo.JPEG, ChromaDownsampling.ALPHA_WEIGHTED);
            var resized = (YuvPicture) ImageOps.resize(yuv, 8, 2, options);
            var expected = ImageOps.toYuv(red, ChromaFormat.YUV420, ColorInfo.JPEG);
            assertEquals(128, resized.alpha()[2] & 0xFF, 1);
            assertEquals(0, resized.alpha()[3] & 0xFF);
            assertEquals(expected.u()[0] & 0xFF, resized.u()[1] & 0xFF, 1);
            assertEquals(expected.v()[0] & 0xFF, resized.v()[1] & 0xFF, 1);
            var deep = ImageOps.toYuv16(rgba, ChromaFormat.YUV420, ColorInfo.BT709_LIMITED, 10,
                    ChromaDownsampling.ALPHA_WEIGHTED);
            var deepResized = (Yuv16Picture) ImageOps.resize(deep, 8, 2, options);
            var deepExpected = ImageOps.toYuv16(red, ChromaFormat.YUV420, ColorInfo.BT709_LIMITED, 10);
            assertEquals(deepExpected.u()[0], deepResized.u()[1], 4);
            assertEquals(deepExpected.v()[0], deepResized.v()[1], 4);
        }

        @Test
        @DisplayName("16-bit RGBA in linear light matches a double-precision reference")
        void reference16() {
            var random = new SplittableRandom(5);
            for (var alpha : new boolean[]{false, true}) {
                var picture = randomRgba16(random, 61, 47, alpha);
                for (var filter : new ResizeFilter[]{ResizeFilter.LANCZOS3, ResizeFilter.MITCHELL, ResizeFilter.AREA}) {
                    var options = ResizeOptions.builder().filter(filter).linearLight(true).build();
                    var resized = (Rgba16Picture) ImageOps.resize(picture, 23, 31, options);
                    var horizontal = ResampleWeights.compute(filter, 61, 23, 0, 61, 1, DoubleResampler.WEIGHT_BITS);
                    var vertical = ResampleWeights.compute(filter, 47, 31, 0, 47, 1, DoubleResampler.WEIGHT_BITS);
                    var one = (double) (1 << DoubleResampler.WEIGHT_BITS);
                    var exact = 0;
                    var compared = 0;
                    for (var oy = 0; oy < 31; oy++) {
                        for (var ox = 0; ox < 23; ox++) {
                            var sums = new double[4];
                            for (var j = 0; j < vertical.counts()[oy]; j++) {
                                for (var i = 0; i < horizontal.counts()[ox]; i++) {
                                    var w = vertical.weights()[oy * vertical.stride() + j] / one
                                            * horizontal.weights()[ox * horizontal.stride() + i] / one;
                                    var p = (vertical.starts()[oy] + j) * picture.stride() + 4 * (horizontal.starts()[ox] + i);
                                    var a = alpha ? picture.pixels()[p + 3] & 0xFFFF : 1;
                                    for (var c = 0; c < 3; c++) {
                                        sums[c] += w * a * Srgb.decode((picture.pixels()[p + c] & 0xFFFF) / 65535.0);
                                    }
                                    sums[3] += w * a;
                                }
                            }
                            if (alpha) {
                                assertEquals(Math.clamp(sums[3], 0, 65535),
                                        resized.pixels()[oy * resized.stride() + 4 * ox + 3] & 0xFFFF, 0.51);
                                if (sums[3] < 64) {
                                    // negative lobes can leave an alpha too close to zero for a stable division
                                    continue;
                                }
                            }
                            for (var c = 0; c < 3; c++) {
                                var expected = (int) Math.round(65535 * Srgb.encode(Math.clamp(sums[c] / sums[3], 0, 1)));
                                var actual = resized.pixels()[oy * resized.stride() + 4 * ox + c] & 0xFFFF;
                                assertEquals(expected, actual, 1, filter + " " + ox + "," + oy);
                                compared++;
                                if (expected == actual) {
                                    exact++;
                                }
                            }
                        }
                    }
                    assertTrue(exact > 0.99 * compared, filter + ": " + exact + " of " + compared + " exact");
                }
            }
        }

        @Test
        @DisplayName("8-bit RGBA in linear light matches a double-precision reference")
        void reference() {
            var random = new SplittableRandom(21);
            for (var alpha : new boolean[]{false, true}) {
                var picture = Pictures.randomRgba(random, 61, 47, alpha);
                for (var filter : new ResizeFilter[]{ResizeFilter.LANCZOS3, ResizeFilter.BICUBIC, ResizeFilter.AREA}) {
                    var options = ResizeOptions.builder().filter(filter).linearLight(true).build();
                    var resized = (RgbaPicture) ImageOps.resize(picture, 23, 31, options);
                    var horizontal = ResampleWeights.compute(filter, 61, 23, 0, 61, 1, DoubleResampler.WEIGHT_BITS);
                    var vertical = ResampleWeights.compute(filter, 47, 31, 0, 47, 1, DoubleResampler.WEIGHT_BITS);
                    var one = (double) (1 << DoubleResampler.WEIGHT_BITS);
                    var exact = 0;
                    for (var oy = 0; oy < 31; oy++) {
                        for (var ox = 0; ox < 23; ox++) {
                            var sums = new double[4];
                            for (var j = 0; j < vertical.counts()[oy]; j++) {
                                for (var i = 0; i < horizontal.counts()[ox]; i++) {
                                    var w = vertical.weights()[oy * vertical.stride() + j] / one
                                            * horizontal.weights()[ox * horizontal.stride() + i] / one;
                                    var p = (vertical.starts()[oy] + j) * picture.stride() + 4 * (horizontal.starts()[ox] + i);
                                    var a = alpha ? picture.pixels()[p + 3] & 0xFF : 1;
                                    for (var c = 0; c < 3; c++) {
                                        sums[c] += w * a * Srgb.decode((picture.pixels()[p + c] & 0xFF) / 255.0);
                                    }
                                    sums[3] += w * a;
                                }
                            }
                            if (alpha && sums[3] < 8) {
                                continue;
                            }
                            for (var c = 0; c < 3; c++) {
                                var expected = (int) Math.round(255 * Srgb.encode(Math.clamp(sums[c] / sums[3], 0, 1)));
                                var actual = resized.pixels()[oy * resized.stride() + 4 * ox + c] & 0xFF;
                                assertEquals(expected, actual, 1, filter + " " + ox + "," + oy);
                                if (expected == actual) {
                                    exact++;
                                }
                            }
                        }
                    }
                    assertTrue(exact > 0.98 * 3 * 23 * 31 * (alpha ? 0.5 : 1), filter + ": " + exact + " exact");
                }
            }
        }

        @Test
        @DisplayName("planar pictures whose matrix needs the transfer function resample directly and in linear light")
        void transferMatrix() {
            var color = new ColorInfo(com.github.auties00.smalt.image.MatrixCoefficients.ICTCP,
                    com.github.auties00.smalt.image.ColorRange.LIMITED, com.github.auties00.smalt.image.ColorPrimaries.BT2020,
                    com.github.auties00.smalt.image.TransferCharacteristics.PQ);
            var deep = randomYuv16(new SplittableRandom(8), 20, 12, ChromaFormat.YUV420, 10, false, color);
            var eight = ImageOps.toEightBit(deep);
            assertEquals(10, ((Yuv16Picture) ImageOps.resize(deep, 10, 6, ResizeFilter.LANCZOS3)).bitDepth());
            assertInstanceOf(YuvPicture.class, ImageOps.resize(eight, 10, 6, ResizeFilter.LANCZOS3));
            var linear = ResizeOptions.builder().linearLight(true).build();
            var resized = (Yuv16Picture) ImageOps.resize(deep, 10, 6, linear);
            assertEquals(color, resized.color());
            assertEquals(10, resized.bitDepth());
            assertEquals(color, ((YuvPicture) ImageOps.resize(eight, 10, 6, linear)).color());
            assertEquals(8, ImageOps.fitWithin(deep, 8, linear).width());
        }

        @Test
        @DisplayName("nearest ignores linear light and opaque pictures stay opaque")
        void nearest() {
            var picture = Pictures.randomRgba(new SplittableRandom(3), 40, 30, false);
            var options = ResizeOptions.builder().filter(ResizeFilter.NEAREST).linearLight(true).build();
            assertArrayEquals(Pictures.tight((RgbaPicture) ImageOps.resize(picture, 13, 17, ResizeFilter.NEAREST)),
                    Pictures.tight((RgbaPicture) ImageOps.resize(picture, 13, 17, options)));
            var linear = (RgbaPicture) ImageOps.resize(picture, 13, 17, LINEAR);
            for (var i = 0; i < 13 * 17; i++) {
                assertEquals((byte) 255, linear.pixels()[4 * i + 3]);
            }
        }
    }

    @Nested
    @DisplayName("against ffmpeg")
    class Ffmpeg {
        static byte[] littleEndian(short[] plane, int stride, int width, int height) {
            var out = new byte[2 * width * height];
            for (var y = 0; y < height; y++) {
                for (var x = 0; x < width; x++) {
                    var value = plane[y * stride + x];
                    out[2 * (y * width + x)] = (byte) value;
                    out[2 * (y * width + x) + 1] = (byte) (value >> 8);
                }
            }
            return out;
        }

        static short[] fromLittleEndian(byte[] raw, int offset, int count) {
            var out = new short[count];
            for (var i = 0; i < count; i++) {
                out[i] = (short) ((raw[offset + 2 * i] & 0xFF) | (raw[offset + 2 * i + 1] & 0xFF) << 8);
            }
            return out;
        }

        static Stream<Arguments> cases() {
            var arguments = new ArrayList<Arguments>();
            for (var filter : ResizeTest.FILTERED) {
                if (ResizeTest.Ffmpeg.flags(filter) == null) {
                    continue;
                }
                arguments.add(Arguments.of(filter, 640, 480, 214, 160));
                arguments.add(Arguments.of(filter, 400, 300, 148, 212));
                if (filter != ResizeFilter.AREA) {
                    arguments.add(Arguments.of(filter, 160, 120, 400, 300));
                }
            }
            return arguments.stream();
        }

        @ParameterizedTest(name = "{0} {1}x{2} -> {3}x{4}")
        @MethodSource("cases")
        @DisplayName("16-bit gray planes reach at least 60 dB against ffmpeg's scaler")
        void gray16(ResizeFilter filter, int inWidth, int inHeight, int outWidth, int outHeight) {
            var raw = Pictures.testsrc(inWidth, inHeight, "gray16le");
            var plane = fromLittleEndian(raw, 0, inWidth * inHeight);
            var picture = Yuv16Picture.builder().size(inWidth, inHeight).chromaFormat(ChromaFormat.MONOCHROME).bitDepth(16)
                    .color(ColorInfo.JPEG).luma(plane, inWidth).build();
            var expected = fromLittleEndian(Pictures.ffmpeg(raw, "gray16le", inWidth, inHeight,
                    "scale=" + outWidth + ":" + outHeight + ":" + ResizeTest.Ffmpeg.flags(filter), "gray16le"), 0,
                    outWidth * outHeight);
            var resized = (Yuv16Picture) ImageOps.resize(picture, outWidth, outHeight, filter);
            var actual = fromLittleEndian(littleEndian(resized.y(), resized.yStride(), outWidth, outHeight), 0,
                    outWidth * outHeight);
            var psnr = Quality.psnr(expected, actual, 65535);
            assertTrue(psnr >= 60, "PSNR " + psnr);
        }

        @ParameterizedTest(name = "{0} {1}x{2} -> {3}x{4}")
        @MethodSource("cases")
        @DisplayName("10-bit 4:2:0 pictures reach at least 50 dB against ffmpeg's scaler")
        void yuv420p10(ResizeFilter filter, int inWidth, int inHeight, int outWidth, int outHeight) {
            var raw = Pictures.testsrc(inWidth, inHeight, "yuv420p10le");
            var lumaCount = inWidth * inHeight;
            var chromaCount = (inWidth / 2) * (inHeight / 2);
            var picture = Yuv16Picture.builder().size(inWidth, inHeight).chromaFormat(ChromaFormat.YUV420).bitDepth(10)
                    .color(ColorInfo.BT601_LIMITED).luma(fromLittleEndian(raw, 0, lumaCount), inWidth)
                    .chroma(fromLittleEndian(raw, 2 * lumaCount, chromaCount),
                            fromLittleEndian(raw, 2 * (lumaCount + chromaCount), chromaCount), inWidth / 2)
                    .build();
            var expected = fromLittleEndian(Pictures.ffmpeg(raw, "yuv420p10le", inWidth, inHeight, "scale=" + outWidth + ":"
                    + outHeight + ":" + ResizeTest.Ffmpeg.flags(filter)
                    + ":in_h_chr_pos=128:in_v_chr_pos=128:out_h_chr_pos=128:out_v_chr_pos=128", "yuv420p10le"), 0,
                    outWidth * outHeight * 3 / 2);
            var resized = (Yuv16Picture) ImageOps.resize(picture, outWidth, outHeight, filter);
            var actual = new short[expected.length];
            var outChroma = (outWidth / 2) * (outHeight / 2);
            System.arraycopy(fromLittleEndian(littleEndian(resized.y(), resized.yStride(), outWidth, outHeight), 0,
                    outWidth * outHeight), 0, actual, 0, outWidth * outHeight);
            System.arraycopy(fromLittleEndian(littleEndian(resized.u(), resized.uvStride(), outWidth / 2, outHeight / 2), 0,
                    outChroma), 0, actual, outWidth * outHeight, outChroma);
            System.arraycopy(fromLittleEndian(littleEndian(resized.v(), resized.uvStride(), outWidth / 2, outHeight / 2), 0,
                    outChroma), 0, actual, outWidth * outHeight + outChroma, outChroma);
            var psnr = Quality.psnr(expected, actual, 1023);
            assertTrue(psnr >= 50, "PSNR " + psnr);
        }
    }

    @Nested
    @DisplayName("results")
    class Results {
        @Test
        @DisplayName("every resampling operation keeps 16 bits, and the size of the input returns it")
        void depthKept() {
            var random = new SplittableRandom(5);
            var rgba = randomRgba16(random, 50, 40, true);
            var yuv = randomYuv16(random, 50, 40, ChromaFormat.YUV422, 12, false, ColorInfo.BT709_LIMITED);
            assertSame(rgba, ImageOps.resize(rgba, 50, 40, ResizeFilter.LANCZOS3));
            assertSame(yuv, ImageOps.resize(yuv, 50, 40, ResizeOptions.defaults()));
            var resized = assertInstanceOf(Yuv16Picture.class, ImageOps.resize(yuv, 31, 17, ResizeFilter.MITCHELL));
            assertEquals(12, resized.bitDepth());
            assertEquals(ChromaFormat.YUV422, resized.chromaFormat());
            assertEquals(ColorInfo.BT709_LIMITED, resized.color());
            assertEquals(16, resized.chromaWidth());
            assertInstanceOf(Rgba16Picture.class, ImageOps.fitWithin(rgba, 20, ResizeFilter.HAMMING));
            assertInstanceOf(Yuv16Picture.class, ImageOps.coverSquare(yuv, 25, ResizeFilter.LANCZOS4));
            assertInstanceOf(Yuv16Picture.class, ImageOps.smartCrop(yuv, 20, 20, SmartCropStrategy.ENTROPY,
                    ResizeFilter.LANCZOS2));
            var fitted = assertInstanceOf(Rgba16Picture.class, ImageOps.fitInto(yuv, 64, 64, ResizeFilter.BOX, 0x80102030));
            assertEquals(64, fitted.width());
            // the background, widened as v * 257, fills the letterbox above the picture
            assertEquals(0x10 * 257, fitted.pixels()[0] & 0xFFFF);
            assertEquals(0x80 * 257, fitted.pixels()[3] & 0xFFFF);
            assertInstanceOf(RgbaPicture.class, ImageOps.fitInto(ImageOps.toEightBit(rgba), 64, 64, ResizeFilter.BOX, 0));
        }

        @Test
        @DisplayName("options are immutable values with builder round trips")
        void options() {
            var options = ResizeOptions.builder().filter(ResizeFilter.MITCHELL).linearLight(true).build();
            assertEquals(ResizeFilter.MITCHELL, options.filter());
            assertTrue(options.linearLight());
            assertEquals(options, options.toBuilder().build());
            assertEquals(ResizeOptions.of(ResizeFilter.LANCZOS3), ResizeOptions.defaults());
            assertEquals(ResizeFilter.LANCZOS3, ResizeOptions.defaults().filter());
            assertTrue(!ResizeOptions.defaults().linearLight());
            org.junit.jupiter.api.Assertions.assertThrows(NullPointerException.class,
                    () -> ResizeOptions.builder().filter(null));
        }
    }
}
