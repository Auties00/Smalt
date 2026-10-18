package com.github.auties00.smalt.image.ops;

import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the properties of the filters that do not need an oracle: preserved means and
 * constants, agreement between the exact and box Gaussians, premultiplied alpha, 16-bit paths
 * agreeing with 8-bit ones, and argument validation. Exactness against Pillow is in
 * {@link PillowOracleTest}.
 */
@DisplayName("filters")
class FilterOpsTest {
    private static double mean(RgbaPicture picture, int channel) {
        double sum = 0;
        for (var y = 0; y < picture.height(); y++) {
            for (var x = 0; x < picture.width(); x++) {
                sum += picture.pixels()[y * picture.stride() + 4 * x + channel] & 0xFF;
            }
        }
        return sum / ((double) picture.width() * picture.height());
    }

    private static RgbaPicture constant(int width, int height, int r, int g, int b) {
        var picture = RgbaPicture.allocate(width, height, false);
        for (var i = 0; i < width * height; i++) {
            picture.pixels()[4 * i] = (byte) r;
            picture.pixels()[4 * i + 1] = (byte) g;
            picture.pixels()[4 * i + 2] = (byte) b;
            picture.pixels()[4 * i + 3] = (byte) 255;
        }
        return picture;
    }

    @Nested
    @DisplayName("Gaussian blur")
    class Gaussian {
        @ParameterizedTest(name = "{0}")
        @EnumSource(BlurMethod.class)
        @DisplayName("keeps a constant picture constant")
        void constantStays(BlurMethod method) {
            var picture = constant(33, 21, 17, 128, 254);
            for (var sigma : new double[]{0.3, 1.5, 6, 25}) {
                var blurred = (RgbaPicture) ImageFilters.gaussianBlur(picture, sigma, method);
                assertArrayEquals(Pictures.tight(picture), Pictures.tight(blurred), "sigma " + sigma);
            }
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(BlurMethod.class)
        @DisplayName("preserves the mean of a periodic picture")
        void mean(BlurMethod method) {
            var picture = Pictures.smoothRgba(96, 80, false);
            var blurred = (RgbaPicture) ImageFilters.gaussianBlur(picture, 2.5, method);
            for (var c = 0; c < 3; c++) {
                assertEquals(FilterOpsTest.mean(picture, c), FilterOpsTest.mean(blurred, c), 1.0, "channel " + c);
            }
        }

        @Test
        @DisplayName("exact and box methods agree closely")
        void methodsAgree() {
            var picture = Pictures.smoothRgba(120, 90, false);
            for (var sigma : new double[]{1, 3, 8}) {
                var exact = Pictures.tight((RgbaPicture) ImageFilters.gaussianBlur(picture, sigma, BlurMethod.EXACT));
                var box = Pictures.tight((RgbaPicture) ImageFilters.gaussianBlur(picture, sigma, BlurMethod.BOX));
                double total = 0;
                for (var i = 0; i < exact.length; i++) {
                    total += Math.abs((exact[i] & 0xFF) - (box[i] & 0xFF));
                }
                assertTrue(total / exact.length < 1.5, "sigma " + sigma + ": mean difference " + total / exact.length);
            }
        }

        @Test
        @DisplayName("blurs transparent pictures in premultiplied alpha")
        void premultiplied() {
            // opaque blue next to fully transparent red: no red may appear in visible pixels
            var picture = RgbaPicture.allocate(40, 10, true);
            for (var y = 0; y < 10; y++) {
                for (var x = 0; x < 40; x++) {
                    var p = 4 * (y * 40 + x);
                    if (x < 20) {
                        picture.pixels()[p + 2] = (byte) 255;
                        picture.pixels()[p + 3] = (byte) 255;
                    } else {
                        picture.pixels()[p] = (byte) 255;
                    }
                }
            }
            for (var method : BlurMethod.values()) {
                var blurred = (RgbaPicture) ImageFilters.gaussianBlur(picture, 3, method);
                for (var i = 0; i < 400; i++) {
                    if ((blurred.pixels()[4 * i + 3] & 0xFF) > 8) {
                        assertTrue((blurred.pixels()[4 * i] & 0xFF) <= 2, method + ": red leaked into pixel " + i);
                    }
                }
            }
        }

        @Test
        @DisplayName("16-bit blurs agree with 8-bit ones")
        void sixteenBit() {
            var picture = Pictures.smoothRgba(50, 40, true);
            for (var method : new BlurMethod[]{BlurMethod.EXACT, BlurMethod.BOX}) {
                var deep = ImageFilters.gaussianBlur(ImageOps.toSixteenBit(picture), 2, method);
                assertInstanceOf(Rgba16Picture.class, deep);
                var shallow = Pictures.tight((RgbaPicture) ImageFilters.gaussianBlur(picture, 2, method));
                var reduced = Pictures.tight(ImageOps.toEightBit((Rgba16Picture) deep));
                for (var i = 0; i < shallow.length; i++) {
                    // the 8-bit box path premultiplies in 8 bits, as Pillow does: a color under alpha a
                    // is only known to within a few multiples of 255 / a after six rounded passes
                    var alpha = shallow[i | 3] & 0xFF;
                    var tolerance = i % 4 == 3 ? 2 : alpha == 0 ? 255 : 2 + 3 * 255 / alpha;
                    assertTrue(Math.abs((shallow[i] & 0xFF) - (reduced[i] & 0xFF)) <= tolerance,
                            method + " sample " + i + ": " + (shallow[i] & 0xFF) + " vs " + (reduced[i] & 0xFF)
                                    + ", alpha " + (shallow[i | 3] & 0xFF) + " vs " + (reduced[i | 3] & 0xFF));
                }
            }
        }

        @Test
        @DisplayName("converts Y'CbCr pictures and returns a copy for a zero deviation")
        void inputs() {
            var yuv = ImageOps.toYuv(Pictures.patternRgba(20, 16, false), ChromaFormat.YUV420, ColorInfo.JPEG);
            assertInstanceOf(RgbaPicture.class, ImageFilters.gaussianBlur(yuv, 1));
            var picture = Pictures.randomRgba(new SplittableRandom(1), 9, 7, true);
            assertArrayEquals(Pictures.tight(picture), Pictures.tight((RgbaPicture) ImageFilters.gaussianBlur(picture, 0)));
            assertThrows(IllegalArgumentException.class, () -> ImageFilters.gaussianBlur(picture, -1));
            assertThrows(IllegalArgumentException.class, () -> ImageFilters.gaussianBlur(picture, Double.NaN));
        }
    }

    @Nested
    @DisplayName("sharpening")
    class Sharpening {
        @Test
        @DisplayName("an unsharp mask with zero amount or a high threshold changes nothing")
        void neutral() {
            var picture = Pictures.patternRgba(30, 20, false);
            assertArrayEquals(Pictures.tight(picture), Pictures.tight((RgbaPicture) ImageFilters.unsharpMask(picture, 2, 0, 0)));
            assertArrayEquals(Pictures.tight(picture), Pictures.tight((RgbaPicture) ImageFilters.unsharpMask(picture, 2, 1.5, 255)));
        }

        @Test
        @DisplayName("an unsharp mask increases edge contrast and keeps alpha")
        void edges() {
            var picture = Pictures.patternRgba(40, 30, true);
            var sharpened = (RgbaPicture) ImageFilters.unsharpMask(picture, 1.5, 1, 0);
            double before = 0;
            double after = 0;
            for (var y = 0; y < 30; y++) {
                for (var x = 1; x < 40; x++) {
                    var p = y * 160 + 4 * x + 2;
                    before += Math.abs((picture.pixels()[p] & 0xFF) - (picture.pixels()[p - 4] & 0xFF));
                    after += Math.abs((sharpened.pixels()[p] & 0xFF) - (sharpened.pixels()[p - 4] & 0xFF));
                    assertEquals(picture.pixels()[p + 1], sharpened.pixels()[p + 1]);
                }
            }
            assertTrue(after > before);
            var deep = ImageFilters.unsharpMask(ImageOps.toSixteenBit(picture), 1.5, 1, 0);
            var reduced = Pictures.tight(ImageOps.toEightBit((Rgba16Picture) deep));
            var shallow = Pictures.tight(sharpened);
            for (var i = 0; i < shallow.length; i++) {
                assertTrue(Math.abs((shallow[i] & 0xFF) - (reduced[i] & 0xFF)) <= 3, "sample " + i);
            }
        }

        @Test
        @DisplayName("the identity kernel copies the picture")
        void identityKernel() {
            var picture = Pictures.randomRgba(new SplittableRandom(3), 17, 13, true);
            var identity = ConvolutionKernel.of(3, 3, 0, 0, 0, 0, 1, 0, 0, 0, 0);
            assertArrayEquals(Pictures.tight(picture), Pictures.tight((RgbaPicture) ImageFilters.convolve(picture, identity)));
            var deep = ImageOps.toSixteenBit(picture);
            var copy = (Rgba16Picture) ImageFilters.convolve(deep, identity);
            assertArrayEquals(deep.pixels(), copy.pixels());
        }

        @Test
        @DisplayName("kernels validate their shape")
        void kernels() {
            assertThrows(IllegalArgumentException.class, () -> ConvolutionKernel.of(2, 3, new float[6]));
            assertThrows(IllegalArgumentException.class, () -> ConvolutionKernel.of(3, 3, new float[8]));
            assertThrows(IllegalArgumentException.class, () -> ConvolutionKernel.of(1, 1, new float[]{1}, 0, 0));
            assertThrows(IllegalArgumentException.class, () -> ConvolutionKernel.of(1, 1, Float.NaN));
            assertEquals(ConvolutionKernel.sharpen(), ConvolutionKernel.sharpen());
            assertEquals(-2f, ConvolutionKernel.sharpen().weight(0, 0));
        }
    }

    @Nested
    @DisplayName("median")
    class MedianFilter {
        @Test
        @DisplayName("removes isolated impulses and keeps constants")
        void impulses() {
            var picture = constant(20, 20, 100, 100, 100);
            picture.pixels()[4 * (10 * 20 + 10)] = (byte) 255;
            picture.pixels()[4 * (5 * 20 + 3) + 1] = 0;
            var filtered = (RgbaPicture) ImageFilters.median(picture, 1);
            assertArrayEquals(Pictures.tight(constant(20, 20, 100, 100, 100)), Pictures.tight(filtered));
        }

        @ParameterizedTest(name = "radius {0}")
        @ValueSource(ints = {1, 2, 4})
        @DisplayName("16-bit and 8-bit medians agree")
        void sixteenBit(int radius) {
            var picture = Pictures.randomRgba(new SplittableRandom(radius), 19, 15, true);
            var shallow = Pictures.tight((RgbaPicture) ImageFilters.median(picture, radius));
            var deep = Pictures.tight(ImageOps.toEightBit((Rgba16Picture) ImageFilters.median(ImageOps.toSixteenBit(picture), radius)));
            assertArrayEquals(shallow, deep);
        }

        @Test
        @DisplayName("validates the radius")
        void radius() {
            var picture = constant(4, 4, 1, 2, 3);
            assertThrows(IllegalArgumentException.class, () -> ImageFilters.median(picture, 0));
            assertThrows(IllegalArgumentException.class, () -> ImageFilters.median(picture, ImageFilters.MAX_MEDIAN_RADIUS + 1));
        }
    }

    @Nested
    @DisplayName("extreme sizes")
    class Extremes {
        @ParameterizedTest(name = "{0}x{1}")
        @org.junit.jupiter.params.provider.CsvSource({"1,1", "1,9", "9,1", "2,2", "3,700"})
        @DisplayName("every filter handles tiny and thin pictures with huge radii")
        void tiny(int width, int height) {
            var picture = Pictures.randomRgba(new SplittableRandom(width * 31 + height), width, height, true);
            var deep = ImageOps.toSixteenBit(picture);
            for (Picture input : new Picture[]{picture, deep}) {
                for (var method : BlurMethod.values()) {
                    assertEquals(width, ImageFilters.gaussianBlur(input, 50, method).width());
                }
                assertEquals(height, ImageFilters.boxBlur(input, 100).height());
                assertEquals(width, ImageFilters.unsharpMask(input, 9, 2, 1).width());
                assertEquals(width, ImageFilters.median(input, 5).width());
                assertEquals(width, ImageFilters.convolve(input, ConvolutionKernel.of(9, 9, new float[81])).width());
            }
        }

        @Test
        @DisplayName("a constant 1-pixel-wide picture stays constant under every blur")
        void thinConstant() {
            var picture = constant(1, 50, 9, 99, 199);
            for (var method : BlurMethod.values()) {
                assertTrue(Arrays.equals(Pictures.tight(picture), Pictures.tight((RgbaPicture) ImageFilters.gaussianBlur(picture, 30, method))));
            }
        }
    }
}
