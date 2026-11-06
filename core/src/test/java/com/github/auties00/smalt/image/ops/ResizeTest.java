package com.github.auties00.smalt.image.ops;

import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.RgbaPicture;
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
import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers resampling: the fixed-point taps, exactness properties (identity, constants, symmetry,
 * block averages), premultiplied alpha, extreme ratios, an independent floating-point reference,
 * and ffmpeg's scaler with the same kernels.
 */
@DisplayName("Resize")
class ResizeTest {
    static final List<ResizeFilter> FILTERED = List.of(ResizeFilter.BOX, ResizeFilter.BILINEAR, ResizeFilter.HAMMING,
            ResizeFilter.BICUBIC, ResizeFilter.MITCHELL, ResizeFilter.LANCZOS2, ResizeFilter.LANCZOS3, ResizeFilter.LANCZOS4,
            ResizeFilter.AREA);

    static final int[][] SIZE_PAIRS = {
            {1, 1}, {1, 7}, {7, 1}, {2, 3}, {3, 2}, {5, 4}, {4, 5}, {16, 7}, {7, 16}, {100, 33}, {33, 100}, {64, 32}, {63, 32},
            {1000, 1}, {1, 1000}, {37, 36}, {36, 37}, {600, 599}, {97, 256}
    };

    static Stream<Arguments> filtersAndSizes() {
        var arguments = new ArrayList<Arguments>();
        for (var filter : ResizeFilter.values()) {
            for (var pair : SIZE_PAIRS) {
                arguments.add(Arguments.of(filter, pair[0], pair[1]));
            }
        }
        return arguments.stream();
    }

    @Nested
    @DisplayName("fixed-point taps")
    class Taps {
        @ParameterizedTest(name = "{0} {1} -> {2}")
        @MethodSource("com.github.auties00.smalt.image.ops.ResizeTest#filtersAndSizes")
        @DisplayName("sum exactly to one and stay inside the source")
        void normalized(ResizeFilter filter, int in, int out) {
            for (var bits : new int[]{Resampler.FIRST_BITS, Resampler.SECOND_BITS, Resampler.WIDE_SECOND_BITS}) {
                var weights = ResampleWeights.compute(filter, in, out, 0, in, 1, bits);
                for (var i = 0; i < out; i++) {
                    var sum = 0L;
                    for (var k = 0; k < weights.stride(); k++) {
                        var weight = weights.weights()[i * weights.stride() + k];
                        if (k >= weights.counts()[i]) {
                            assertEquals(0, weight, "padding");
                        }
                        sum += weight;
                    }
                    assertEquals(1L << bits, sum, "output " + i);
                    assertTrue(weights.starts()[i] >= 0 && weights.starts()[i] + weights.counts()[i] <= in);
                }
            }
        }

        @ParameterizedTest(name = "{0} {1} -> {2}")
        @MethodSource("com.github.auties00.smalt.image.ops.ResizeTest#filtersAndSizes")
        @DisplayName("mirror exactly about the center of the axis")
        void mirrored(ResizeFilter filter, int in, int out) {
            if (filter == ResizeFilter.NEAREST) {
                return;
            }
            var weights = ResampleWeights.compute(filter, in, out, 0, in, 1, Resampler.SECOND_BITS);
            for (var i = 0; i < out; i++) {
                var j = out - 1 - i;
                var count = weights.counts()[i];
                assertEquals(count, weights.counts()[j]);
                assertEquals(in - weights.starts()[i] - count, weights.starts()[j], "start of " + j);
                for (var k = 0; k < count; k++) {
                    assertEquals(weights.weights()[i * weights.stride() + k],
                            weights.weights()[j * weights.stride() + count - 1 - k], "tap " + k + " of " + i);
                }
            }
        }

        @Test
        @DisplayName("area taps of a reduction by three are exact thirds")
        void areaThirds() {
            var weights = ResampleWeights.compute(ResizeFilter.AREA, 9, 3, 0, 9, 1, 22);
            for (var i = 0; i < 3; i++) {
                assertEquals(3, weights.counts()[i]);
                assertEquals(3 * i, weights.starts()[i]);
                var taps = Arrays.copyOfRange(weights.weights(), 3 * i, 3 * i + 3);
                assertEquals(1 << 22, taps[0] + taps[1] + taps[2]);
                for (var tap : taps) {
                    assertTrue(Math.abs(tap - (1 << 22) / 3.0) <= 1, Arrays.toString(taps));
                }
            }
        }

        @Test
        @DisplayName("catmull-rom and lanczos kernels have their textbook values")
        void kernels() {
            assertEquals(1, ResizeFilter.BICUBIC.kernel(0), 1e-12);
            assertEquals(0.5625, ResizeFilter.BICUBIC.kernel(0.5), 1e-12);
            assertEquals(-0.0625, ResizeFilter.BICUBIC.kernel(1.5), 1e-12);
            assertEquals(0, ResizeFilter.BICUBIC.kernel(1), 1e-12);
            assertEquals(0, ResizeFilter.BICUBIC.kernel(2), 1e-12);
            assertEquals(1, ResizeFilter.LANCZOS3.kernel(0), 1e-12);
            assertEquals(0, ResizeFilter.LANCZOS3.kernel(1), 1e-12);
            assertEquals(0.6079271, ResizeFilter.LANCZOS3.kernel(0.5), 1e-6);
            assertEquals(0, ResizeFilter.LANCZOS3.kernel(3), 1e-12);
            assertEquals(0.5, ResizeFilter.BILINEAR.kernel(0.5), 1e-12);
            assertEquals(1, ResizeFilter.BOX.kernel(0), 1e-12);
            assertEquals(1, ResizeFilter.BOX.kernel(0.4999), 1e-12);
            assertEquals(0.5, ResizeFilter.BOX.kernel(0.5), 1e-12);
            assertEquals(0, ResizeFilter.BOX.kernel(0.5001), 1e-12);
            assertEquals(1, ResizeFilter.HAMMING.kernel(0), 1e-12);
            // sinc(1/2) * (0.54 + 0.46 * cos(pi / 2)) = (2 / pi) * 0.54
            assertEquals(2 / Math.PI * 0.54, ResizeFilter.HAMMING.kernel(0.5), 1e-12);
            assertEquals(0, ResizeFilter.HAMMING.kernel(1), 1e-12);
            // Mitchell-Netravali with B = C = 1/3: k(0) = (6 - 2B) / 6 = 8/9, k(1) = B / 6 = 1/18, k(1.5) = -5/144
            assertEquals(8.0 / 9, ResizeFilter.MITCHELL.kernel(0), 1e-12);
            assertEquals(1.0 / 18, ResizeFilter.MITCHELL.kernel(1), 1e-12);
            assertEquals(1.0 / 18, ResizeFilter.MITCHELL.kernel(Math.nextDown(1.0)), 1e-9);
            assertEquals(0, ResizeFilter.MITCHELL.kernel(2), 1e-12);
            assertEquals(-5.0 / 144, ResizeFilter.MITCHELL.kernel(1.5), 1e-12);
            assertEquals(1, ResizeFilter.LANCZOS2.kernel(0), 1e-12);
            assertEquals(0, ResizeFilter.LANCZOS2.kernel(1), 1e-12);
            assertEquals(8 * Math.sin(Math.PI / 4) / (Math.PI * Math.PI), ResizeFilter.LANCZOS2.kernel(0.5), 1e-12);
            assertEquals(0, ResizeFilter.LANCZOS2.kernel(2), 1e-12);
            assertEquals(16 * Math.sin(Math.PI / 8) / (Math.PI * Math.PI), ResizeFilter.LANCZOS4.kernel(0.5), 1e-12);
            assertEquals(0, ResizeFilter.LANCZOS4.kernel(3), 1e-12);
            assertEquals(0, ResizeFilter.LANCZOS4.kernel(4), 1e-12);
            assertNotEquals(0, ResizeFilter.LANCZOS4.kernel(3.5), 1e-6);
        }

        @Test
        @DisplayName("box taps average whole blocks, and half-weight the samples exactly at the edge of the box")
        void boxTaps() {
            var reduce = ResampleWeights.compute(ResizeFilter.BOX, 9, 3, 0, 9, 1, 22);
            for (var i = 0; i < 3; i++) {
                assertEquals(3, reduce.counts()[i]);
                assertEquals(3 * i, reduce.starts()[i]);
            }
            // 2 -> 3: the middle output center lies exactly on the boundary between the two pixels,
            // half a pixel from both, and averages them
            var enlarge = ResampleWeights.compute(ResizeFilter.BOX, 2, 3, 0, 2, 1, 22);
            assertArrayEquals(new int[]{0, 0, 1}, enlarge.starts());
            assertArrayEquals(new int[]{1, 2, 1}, enlarge.counts());
            assertEquals(1 << 21, enlarge.weights()[enlarge.stride()]);
            assertEquals(1 << 21, enlarge.weights()[enlarge.stride() + 1]);
            // 5 -> 2: the inner edges of the two 2.5-pixel boxes fall on the center of sample 2,
            // which both outputs weight by half: 2/5, 2/5, 1/5 and its mirror
            var reduce5 = ResampleWeights.compute(ResizeFilter.BOX, 5, 2, 0, 5, 1, 22);
            assertArrayEquals(new int[]{0, 2}, reduce5.starts());
            assertArrayEquals(new int[]{3, 3}, reduce5.counts());
            assertEquals(0.4 * (1 << 22), reduce5.weights()[0], 1);
            assertEquals(0.4 * (1 << 22), reduce5.weights()[1], 1);
            assertEquals(0.2 * (1 << 22), reduce5.weights()[2], 1);
            assertEquals(0.2 * (1 << 22), reduce5.weights()[reduce5.stride()], 1);
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(value = ResizeFilter.class, mode = EnumSource.Mode.EXCLUDE, names = "NEAREST")
        @DisplayName("positive and negative tap sums stay within the fixed-point headroom of 1.55")
        void headroom(ResizeFilter filter) {
            // the 8-bit passes size their fractional bits so that 255 * 1.55 * 2^22 fits an int
            var worst = 0.0;
            for (var in = 1; in <= 64; in++) {
                for (var out : new int[]{1, 2, 3, 5, 7, 13, 31, 64, 97, 200}) {
                    var weights = ResampleWeights.compute(filter, in, out, 0, in, 1, Resampler.FIRST_BITS);
                    for (var i = 0; i < out; i++) {
                        long positive = 0;
                        long negative = 0;
                        for (var k = 0; k < weights.counts()[i]; k++) {
                            var weight = weights.weights()[i * weights.stride() + k];
                            if (weight > 0) {
                                positive += weight;
                            } else {
                                negative -= weight;
                            }
                        }
                        worst = Math.max(worst, Math.max(positive, negative) / (double) (1 << Resampler.FIRST_BITS));
                    }
                }
            }
            assertTrue(worst <= 1.55, filter + " reaches " + worst);
        }
    }

    @Nested
    @DisplayName("exactness")
    class Exactness {
        @Test
        @DisplayName("resizing to the same size returns the input")
        void identity() {
            var rgba = Pictures.randomRgba(new SplittableRandom(1), 9, 4, true);
            var yuv = Pictures.randomYuv(new SplittableRandom(1), 9, 4, ChromaFormat.YUV420, true, ColorInfo.JPEG);
            for (var filter : ResizeFilter.values()) {
                assertSame(rgba, ImageOps.resize(rgba, 9, 4, filter));
                assertSame(yuv, ImageOps.resize(yuv, 9, 4, filter));
            }
        }

        @ParameterizedTest(name = "{0} {1} -> {2}")
        @MethodSource("com.github.auties00.smalt.image.ops.ResizeTest#filtersAndSizes")
        @DisplayName("constant pictures stay exactly constant")
        void constant(ResizeFilter filter, int in, int out) {
            var inWidth = in;
            var inHeight = Math.max(1, 1000 / Math.max(in, out));
            var outHeight = Math.max(1, inHeight * 3 / 2);
            var opaque = constantRgba(inWidth, inHeight, 200, 17, 99, 255, false);
            assertConstant(ImageOps.resize(opaque, out, outHeight, filter), 200, 17, 99, 255);
            var translucent = constantRgba(inWidth, inHeight, 10, 250, 128, 77, true);
            assertConstant(ImageOps.resize(translucent, out, outHeight, filter), 10, 250, 128, 77);
            var transposed = constantRgba(inHeight, inWidth, 3, 4, 5, 6, true);
            assertConstant(ImageOps.resize(transposed, outHeight, out, filter), 3, 4, 5, 6);
            var yuv = YuvPicture.allocate(inWidth, inHeight, ChromaFormat.YUV420, true, ColorInfo.BT709_LIMITED);
            Arrays.fill(yuv.y(), (byte) 235);
            Arrays.fill(yuv.u(), (byte) 16);
            Arrays.fill(yuv.v(), (byte) 240);
            Arrays.fill(yuv.alpha(), (byte) 1);
            var resized = (YuvPicture) ImageOps.resize(yuv, out, outHeight, filter);
            assertAll(resized.y(), (byte) 235);
            assertAll(resized.u(), (byte) 16);
            assertAll(resized.v(), (byte) 240);
            assertAll(resized.alpha(), (byte) 1);
        }

        static RgbaPicture constantRgba(int width, int height, int r, int g, int b, int a, boolean alpha) {
            var picture = RgbaPicture.allocate(width, height, alpha);
            for (var i = 0; i < width * height; i++) {
                picture.pixels()[4 * i] = (byte) r;
                picture.pixels()[4 * i + 1] = (byte) g;
                picture.pixels()[4 * i + 2] = (byte) b;
                picture.pixels()[4 * i + 3] = (byte) a;
            }
            return picture;
        }

        static void assertConstant(Picture picture, int r, int g, int b, int a) {
            var rgba = (RgbaPicture) picture;
            for (var i = 0; i < rgba.width() * rgba.height(); i++) {
                var index = (i / rgba.width()) * rgba.stride() + 4 * (i % rgba.width());
                assertEquals(r, rgba.pixels()[index] & 0xFF, "red at " + i);
                assertEquals(g, rgba.pixels()[index + 1] & 0xFF, "green at " + i);
                assertEquals(b, rgba.pixels()[index + 2] & 0xFF, "blue at " + i);
                assertEquals(a, rgba.pixels()[index + 3] & 0xFF, "alpha at " + i);
            }
        }

        static void assertAll(byte[] plane, byte value) {
            for (var sample : plane) {
                assertEquals(value, sample);
            }
        }

        @Test
        @DisplayName("halving with the area filter yields the rounded 2x2 averages")
        void areaHalf() {
            var random = new SplittableRandom(3);
            var yuv = Pictures.randomYuv(random, 64, 48, ChromaFormat.MONOCHROME, false, ColorInfo.JPEG);
            var half = (YuvPicture) ImageOps.resize(yuv, 32, 24, ResizeFilter.AREA);
            for (var y = 0; y < 24; y++) {
                for (var x = 0; x < 32; x++) {
                    var sum = 0;
                    for (var dy = 0; dy < 2; dy++) {
                        for (var dx = 0; dx < 2; dx++) {
                            sum += yuv.y()[(2 * y + dy) * yuv.yStride() + 2 * x + dx] & 0xFF;
                        }
                    }
                    assertEquals((sum + 2) >> 2, half.y()[y * half.yStride() + x] & 0xFF, x + "," + y);
                }
            }
            var rgba = Pictures.randomRgba(random, 40, 20, false);
            var rgbaHalf = (RgbaPicture) ImageOps.resize(rgba, 20, 10, ResizeFilter.AREA);
            for (var y = 0; y < 10; y++) {
                for (var x = 0; x < 20; x++) {
                    for (var c = 0; c < 3; c++) {
                        var sum = 0;
                        for (var dy = 0; dy < 2; dy++) {
                            for (var dx = 0; dx < 2; dx++) {
                                sum += rgba.pixels()[(2 * y + dy) * rgba.stride() + 4 * (2 * x + dx) + c] & 0xFF;
                            }
                        }
                        assertEquals((sum + 2) >> 2, rgbaHalf.pixels()[y * rgbaHalf.stride() + 4 * x + c] & 0xFF);
                    }
                }
            }
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(value = ResizeFilter.class, mode = EnumSource.Mode.EXCLUDE, names = "NEAREST")
        @DisplayName("symmetric pictures resize to symmetric pictures")
        void symmetric(ResizeFilter filter) {
            var random = new SplittableRandom(4);
            for (var pair : new int[][]{{31, 13}, {30, 13}, {31, 12}, {12, 31}, {13, 30}, {20, 20}, {7, 3}, {101, 64}}) {
                var width = pair[0];
                var height = pair[0] + 3;
                var picture = YuvPicture.allocate(width, height, ChromaFormat.MONOCHROME, false, ColorInfo.JPEG);
                for (var y = 0; y < (height + 1) / 2; y++) {
                    for (var x = 0; x < (width + 1) / 2; x++) {
                        var value = (byte) random.nextInt(256);
                        picture.y()[y * width + x] = value;
                        picture.y()[y * width + width - 1 - x] = value;
                        picture.y()[(height - 1 - y) * width + x] = value;
                        picture.y()[(height - 1 - y) * width + width - 1 - x] = value;
                    }
                }
                var outWidth = pair[1];
                var outHeight = pair[1] + 2;
                var resized = (YuvPicture) ImageOps.resize(picture, outWidth, outHeight, filter);
                for (var y = 0; y < outHeight; y++) {
                    for (var x = 0; x < outWidth; x++) {
                        var value = resized.y()[y * outWidth + x];
                        assertEquals(value, resized.y()[y * outWidth + outWidth - 1 - x], width + "->" + outWidth + " at " + x + "," + y);
                        assertEquals(value, resized.y()[(outHeight - 1 - y) * outWidth + x], width + "->" + outWidth + " at " + x + "," + y);
                    }
                }
            }
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(value = ResizeFilter.class, mode = EnumSource.Mode.EXCLUDE, names = "NEAREST")
        @DisplayName("resampling preserves the mean of smooth pictures")
        void meanPreserved(ResizeFilter filter) {
            var picture = Pictures.patternRgba(240, 160, false);
            for (var size : new int[][]{{80, 53}, {61, 41}, {480, 320}, {241, 161}}) {
                var resized = (RgbaPicture) ImageOps.resize(picture, size[0], size[1], filter);
                for (var c = 0; c < 2; c++) {
                    var before = mean(picture, c);
                    var after = mean(resized, c);
                    assertEquals(before, after, 1.0, filter + " " + size[0] + "x" + size[1] + " channel " + c);
                }
            }
        }

        static double mean(RgbaPicture picture, int channel) {
            double sum = 0;
            for (var y = 0; y < picture.height(); y++) {
                for (var x = 0; x < picture.width(); x++) {
                    sum += picture.pixels()[y * picture.stride() + 4 * x + channel] & 0xFF;
                }
            }
            return sum / ((double) picture.width() * picture.height());
        }
    }

    @Nested
    @DisplayName("alpha")
    class Alpha {
        @ParameterizedTest(name = "{0}")
        @EnumSource(value = ResizeFilter.class, mode = EnumSource.Mode.EXCLUDE, names = "NEAREST")
        @DisplayName("transparent color never bleeds into visible pixels")
        void noBleeding(ResizeFilter filter) {
            var picture = RgbaPicture.allocate(64, 48, true);
            for (var y = 0; y < 48; y++) {
                for (var x = 0; x < 64; x++) {
                    var index = 4 * (y * 64 + x);
                    var inside = x >= 17 && x < 45 && y >= 11 && y < 30;
                    picture.pixels()[index] = inside ? (byte) 255 : 0;
                    picture.pixels()[index + 1] = inside ? 0 : (byte) 255;
                    picture.pixels()[index + 3] = inside ? (byte) 255 : 0;
                }
            }
            for (var size : new int[][]{{21, 16}, {37, 29}, {100, 75}, {3, 2}, {64, 17}}) {
                var resized = (RgbaPicture) ImageOps.resize(picture, size[0], size[1], filter);
                var partial = 0;
                for (var i = 0; i < size[0] * size[1]; i++) {
                    var alpha = resized.pixels()[4 * i + 3] & 0xFF;
                    if (alpha > 0) {
                        assertEquals(255, resized.pixels()[4 * i] & 0xFF, "red at " + i);
                        assertEquals(0, resized.pixels()[4 * i + 1] & 0xFF, "green at " + i);
                        assertEquals(0, resized.pixels()[4 * i + 2] & 0xFF, "blue at " + i);
                        if (alpha < 255) {
                            partial++;
                        }
                    }
                }
                // the area and box filters replicate pixels when enlarging, which makes no partial alpha
                assertTrue(partial > 0 || filter == ResizeFilter.AREA || filter == ResizeFilter.BOX,
                        "no semi-transparent pixels to check");
            }
        }

        @Test
        @DisplayName("pictures reporting alpha but fully opaque take the direct path with the same result")
        void opaqueWithAlpha() {
            var picture = Pictures.randomRgba(new SplittableRandom(5), 50, 30, false);
            var flagged = RgbaPicture.of(50, 30, picture.stride(), picture.pixels(), true);
            var direct = (RgbaPicture) ImageOps.resize(picture, 23, 41, ResizeFilter.LANCZOS3);
            var viaFlag = (RgbaPicture) ImageOps.resize(flagged, 23, 41, ResizeFilter.LANCZOS3);
            assertArrayEquals(Pictures.tight(direct), Pictures.tight(viaFlag));
            assertTrue(viaFlag.hasAlpha());
            assertFalse(direct.hasAlpha());
        }

        @Test
        @DisplayName("pictures without alpha get opaque alpha whatever their alpha bytes hold")
        void forcedOpaque() {
            var random = new SplittableRandom(6);
            var picture = Pictures.randomRgba(random, 30, 20, true);
            var opaque = RgbaPicture.of(30, 20, picture.stride(), picture.pixels(), false);
            for (var filter : ResizeFilter.values()) {
                var resized = (RgbaPicture) ImageOps.resize(opaque, 17, 9, filter);
                for (var i = 0; i < 17 * 9; i++) {
                    assertEquals((byte) 255, resized.pixels()[4 * i + 3]);
                }
            }
        }
    }

    @Nested
    @DisplayName("extreme ratios")
    class Extreme {
        @ParameterizedTest(name = "{0}")
        @EnumSource(ResizeFilter.class)
        @DisplayName("4000 -> 1, 1 -> 4000 and 3 -> 2 stay finite and bounded")
        void ratios(ResizeFilter filter) {
            var random = new SplittableRandom(7);
            var wide = Pictures.randomYuv(random, 4000, 3, ChromaFormat.YUV420, true, ColorInfo.JPEG);
            var one = (YuvPicture) ImageOps.resize(wide, 1, 1, filter);
            assertEquals(1, one.width());
            var tall = Pictures.randomRgba(random, 3, 4000, true);
            var line = (RgbaPicture) ImageOps.resize(tall, 1, 1, filter);
            assertEquals(1, line.height());
            var dot = Pictures.randomRgba(random, 1, 1, false);
            var stretched = (RgbaPicture) ImageOps.resize(dot, 4000, 2, filter);
            for (var i = 0; i < 8000; i++) {
                for (var c = 0; c < 3; c++) {
                    assertEquals(dot.pixels()[c], stretched.pixels()[4 * i + c]);
                }
            }
            var small = Pictures.randomRgba(random, 3, 3, true);
            assertEquals(2, ImageOps.resize(small, 2, 2, filter).width());
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(value = ResizeFilter.class, names = {"BILINEAR", "LANCZOS3", "AREA"})
        @DisplayName("huge premultiplied vertical reductions accumulate in chunks exactly like the row ring")
        void chunkedAccumulation(ResizeFilter filter) {
            // 2000 pixels per row make a ring of all 4000 taps exceed the 32 MB bound, so the
            // full-width picture takes the chunked path while a 24-pixel crop takes the ring
            var random = new SplittableRandom(10);
            var wide = Pictures.randomRgba(random, 2000, 4000, true);
            var narrow = (RgbaPicture) ImageOps.crop(wide, 0, 0, 24, 4000);
            var chunked = (RgbaPicture) ImageOps.resize(wide, 2000, 3, filter);
            var ring = (RgbaPicture) ImageOps.resize(narrow, 24, 3, filter);
            for (var y = 0; y < 3; y++) {
                for (var i = 0; i < 4 * 24; i++) {
                    assertEquals(ring.pixels()[y * ring.stride() + i], chunked.pixels()[y * chunked.stride() + i], "row " + y + " byte " + i);
                }
            }
        }

        @Test
        @DisplayName("reducing 4000 samples to one with the area filter averages them all")
        void areaMean() {
            var random = new SplittableRandom(8);
            var picture = Pictures.randomYuv(random, 4000, 1, ChromaFormat.MONOCHROME, false, ColorInfo.JPEG);
            long sum = 0;
            for (var x = 0; x < 4000; x++) {
                sum += picture.y()[x] & 0xFF;
            }
            var one = (YuvPicture) ImageOps.resize(picture, 1, 1, ResizeFilter.AREA);
            assertEquals(sum / 4000.0, one.y()[0] & 0xFF, 0.51);
        }
    }

    @Nested
    @DisplayName("floating-point reference")
    class Reference {
        static Stream<Arguments> cases() {
            var arguments = new ArrayList<Arguments>();
            for (var filter : FILTERED) {
                arguments.add(Arguments.of(filter, 90, 70, 31, 23));
                arguments.add(Arguments.of(filter, 31, 23, 90, 70));
                arguments.add(Arguments.of(filter, 1000, 6, 7, 2));
            }
            return arguments.stream();
        }

        /** Applies the same taps in double precision, separably, with no intermediate rounding. */
        static double[] reference(byte[] src, int stride, int channels, int inWidth, int inHeight, int outWidth, int outHeight,
                                  ResizeFilter filter, int channel, boolean premultiplied, boolean wide) {
            var horizontalBits = wide ? Resampler.WIDE_SECOND_BITS : Resampler.SECOND_BITS;
            var verticalBits = wide ? Resampler.WIDE_FIRST_BITS : Resampler.FIRST_BITS;
            var horizontal = ResampleWeights.compute(filter, inWidth, outWidth, 0, inWidth, 1, horizontalBits);
            var vertical = ResampleWeights.compute(filter, inHeight, outHeight, 0, inHeight, 1, verticalBits);
            var out = new double[outWidth * outHeight];
            for (var oy = 0; oy < outHeight; oy++) {
                for (var ox = 0; ox < outWidth; ox++) {
                    double color = 0;
                    double alpha = 0;
                    for (var j = 0; j < vertical.counts()[oy]; j++) {
                        var wy = vertical.weights()[oy * vertical.stride() + j] / (double) (1 << verticalBits);
                        var y = vertical.starts()[oy] + j;
                        for (var i = 0; i < horizontal.counts()[ox]; i++) {
                            var wx = horizontal.weights()[ox * horizontal.stride() + i] / (double) (1 << horizontalBits);
                            var x = horizontal.starts()[ox] + i;
                            var value = src[y * stride + channels * x + channel] & 0xFF;
                            var a = premultiplied ? src[y * stride + channels * x + 3] & 0xFF : 255;
                            color += wx * wy * value * a;
                            alpha += wx * wy * a;
                        }
                    }
                    out[oy * outWidth + ox] = premultiplied ? (alpha <= 0 ? 0 : color / alpha) : color / 255;
                }
            }
            return out;
        }

        @ParameterizedTest(name = "{0} {1}x{2} -> {3}x{4}")
        @MethodSource("cases")
        @DisplayName("fixed point stays within one level of exact arithmetic")
        void matches(ResizeFilter filter, int inWidth, int inHeight, int outWidth, int outHeight) {
            var random = new SplittableRandom(9);
            var plane = Pictures.randomYuv(random, inWidth, inHeight, ChromaFormat.MONOCHROME, false, ColorInfo.JPEG);
            var resized = (YuvPicture) ImageOps.resize(plane, outWidth, outHeight, filter);
            var expected = reference(plane.y(), plane.yStride(), 1, inWidth, inHeight, outWidth, outHeight, filter, 0, false, false);
            for (var i = 0; i < expected.length; i++) {
                var clamped = Math.max(0, Math.min(255, expected[i]));
                // the intermediate keeps 4 fractional bits, so up to about 0.05 of a level adds to rounding
                assertEquals(clamped, resized.y()[i] & 0xFF, 0.6, "sample " + i);
            }
            var rgba = Pictures.randomRgba(random, inWidth, inHeight, true);
            var resizedRgba = (RgbaPicture) ImageOps.resize(rgba, outWidth, outHeight, filter);
            for (var c = 0; c < 3; c++) {
                var color = reference(rgba.pixels(), rgba.stride(), 4, inWidth, inHeight, outWidth, outHeight, filter, c, true, true);
                var alpha = reference(rgba.pixels(), rgba.stride(), 4, inWidth, inHeight, outWidth, outHeight, filter, 3, false, true);
                for (var i = 0; i < color.length; i++) {
                    if (alpha[i] < 1) {
                        continue;
                    }
                    var clamped = Math.max(0, Math.min(255, color[i]));
                    // straight color divided by a small alpha amplifies the fixed-point error
                    var tolerance = 0.6 + 2.0 / alpha[i];
                    assertEquals(clamped, resizedRgba.pixels()[4 * i + c] & 0xFF, tolerance, "channel " + c + " pixel " + i);
                }
            }
        }
    }

    @Nested
    @DisplayName("against ffmpeg")
    class Ffmpeg {
        static Stream<Arguments> cases() {
            var arguments = new ArrayList<Arguments>();
            for (var filter : FILTERED) {
                if (flags(filter) == null) {
                    continue;
                }
                arguments.add(Arguments.of(filter, 640, 480, 213, 160));
                arguments.add(Arguments.of(filter, 640, 480, 320, 240));
                arguments.add(Arguments.of(filter, 400, 300, 147, 211));
                if (filter != ResizeFilter.AREA) {
                    arguments.add(Arguments.of(filter, 160, 120, 400, 300));
                    arguments.add(Arguments.of(filter, 150, 100, 151, 99));
                }
            }
            return arguments.stream();
        }

        /**
         * Returns the cases with even output sizes. swscale maps each chroma plane with its own
         * size ratio, which for an odd output size drifts by up to half a chroma sample from the
         * luma mapping this module keeps chroma registered to; with even sizes both agree.
         */
        static Stream<Arguments> evenCases() {
            var arguments = new ArrayList<Arguments>();
            for (var filter : FILTERED) {
                if (flags(filter) == null) {
                    continue;
                }
                arguments.add(Arguments.of(filter, 640, 480, 214, 160));
                arguments.add(Arguments.of(filter, 640, 480, 320, 240));
                arguments.add(Arguments.of(filter, 400, 300, 148, 212));
                if (filter != ResizeFilter.AREA) {
                    arguments.add(Arguments.of(filter, 160, 120, 400, 300));
                    arguments.add(Arguments.of(filter, 150, 100, 152, 98));
                }
            }
            return arguments.stream();
        }

        /** Returns the swscale flags of the same kernel, or {@code null} when swscale has none (box, Hamming). */
        static String flags(ResizeFilter filter) {
            return switch (filter) {
                case BILINEAR -> "flags=bilinear+accurate_rnd+full_chroma_int";
                case BICUBIC -> "flags=bicubic+accurate_rnd+full_chroma_int:param0=0:param1=0.5";
                case MITCHELL -> "flags=bicubic+accurate_rnd+full_chroma_int:param0=0.333333333333:param1=0.333333333333";
                case LANCZOS2 -> "flags=lanczos+accurate_rnd+full_chroma_int:param0=2";
                case LANCZOS3 -> "flags=lanczos+accurate_rnd+full_chroma_int:param0=3";
                case LANCZOS4 -> "flags=lanczos+accurate_rnd+full_chroma_int:param0=4";
                case AREA -> "flags=area+accurate_rnd+full_chroma_int";
                case NEAREST -> "flags=neighbor";
                case BOX, HAMMING -> null;
            };
        }

        @ParameterizedTest(name = "{0} {1}x{2} -> {3}x{4}")
        @MethodSource("cases")
        @DisplayName("gray planes reach at least 38 dB against ffmpeg's scaler")
        void gray(ResizeFilter filter, int inWidth, int inHeight, int outWidth, int outHeight) {
            var raw = Pictures.testsrc(inWidth, inHeight, "gray");
            var picture = Pictures.fromRaw(raw, inWidth, inHeight, ChromaFormat.MONOCHROME, false, ColorInfo.JPEG);
            var expected = Pictures.ffmpeg(raw, "gray", inWidth, inHeight,
                    "scale=" + outWidth + ":" + outHeight + ":" + flags(filter), "gray");
            var actual = Pictures.raw((YuvPicture) ImageOps.resize(picture, outWidth, outHeight, filter));
            var psnr = Quality.psnr(expected, actual);
            assertTrue(psnr >= 38, "PSNR " + psnr);
        }

        @ParameterizedTest(name = "{0} {1}x{2} -> {3}x{4}")
        @MethodSource("evenCases")
        @DisplayName("4:2:0 pictures reach at least 38 dB against ffmpeg's scaler")
        void yuv420(ResizeFilter filter, int inWidth, int inHeight, int outWidth, int outHeight) {
            var raw = Pictures.testsrc(inWidth, inHeight, "yuv420p");
            var picture = Pictures.fromRaw(raw, inWidth, inHeight, ChromaFormat.YUV420, false, ColorInfo.BT601_LIMITED);
            var expected = Pictures.ffmpeg(raw, "yuv420p", inWidth, inHeight, "scale=" + outWidth + ":" + outHeight + ":"
                    + flags(filter) + ":in_h_chr_pos=128:in_v_chr_pos=128:out_h_chr_pos=128:out_v_chr_pos=128", "yuv420p");
            var actual = Pictures.raw((YuvPicture) ImageOps.resize(picture, outWidth, outHeight, filter));
            var psnr = Quality.psnr(expected, actual);
            assertTrue(psnr >= 38, "PSNR " + psnr);
        }

        @ParameterizedTest(name = "{0} {1}x{2} -> {3}x{4}")
        @MethodSource("cases")
        @DisplayName("opaque RGBA pictures reach at least 38 dB against ffmpeg's scaler, channel by channel")
        void rgba(ResizeFilter filter, int inWidth, int inHeight, int outWidth, int outHeight) {
            var raw = Pictures.testsrc(inWidth, inHeight, "rgba");
            var picture = RgbaPicture.of(inWidth, inHeight, 4 * inWidth, raw, false);
            var actual = Pictures.tight((RgbaPicture) ImageOps.resize(picture, outWidth, outHeight, filter));
            // swscale filters packed RGB through an internal Y'CbCr conversion, so each channel is
            // scaled as its own gray plane, which applies exactly the kernel under comparison
            for (var channel = 0; channel < 3; channel++) {
                var plane = new byte[inWidth * inHeight];
                for (var i = 0; i < plane.length; i++) {
                    plane[i] = raw[4 * i + channel];
                }
                var expected = Pictures.ffmpeg(plane, "gray", inWidth, inHeight,
                        "scale=" + outWidth + ":" + outHeight + ":" + flags(filter), "gray");
                var ours = new byte[outWidth * outHeight];
                for (var i = 0; i < ours.length; i++) {
                    ours[i] = actual[4 * i + channel];
                }
                var psnr = Quality.psnr(expected, ours);
                assertTrue(psnr >= 38, "channel " + channel + " PSNR " + psnr);
            }
        }
    }
}
