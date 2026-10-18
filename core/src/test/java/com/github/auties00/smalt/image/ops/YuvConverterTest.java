package com.github.auties00.smalt.image.ops;

import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ColorPrimaries;
import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.image.MatrixCoefficients;
import com.github.auties00.smalt.image.Picture;
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
import java.util.Random;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the reusing converter (every result equals {@code ImageOps.toYuv} of the same picture, later
 * pictures of a size reuse the first result, a new size or kind gets new pictures) and the direct
 * Y'CbCr conversion between matrices, which must stay within one code value of the conversion through
 * RGBA for colors inside the R'G'B' cube.
 */
class YuvConverterTest {
    private static final ColorInfo BT709 = ColorInfo.BT709_LIMITED;

    private static final List<MatrixCoefficients> WEIGHTED = List.of(MatrixCoefficients.BT601, MatrixCoefficients.BT709,
            MatrixCoefficients.FCC, MatrixCoefficients.SMPTE240M, MatrixCoefficients.BT2020_NCL, MatrixCoefficients.YCGCO);

    private static final List<ChromaFormat> LAYOUTS = List.of(ChromaFormat.YUV420, ChromaFormat.YUV422,
            ChromaFormat.YUV444);

    private static int seed;

    @Nested
    @DisplayName("reuse")
    class Reuse {
        static Stream<Arguments> inputs() {
            return Stream.of(
                    Arguments.of("BT.709 4:2:0", (Supplier<Picture>) () -> yuv(ChromaFormat.YUV420, false, BT709)),
                    Arguments.of("BT.709 4:2:0 with alpha", (Supplier<Picture>) () -> yuv(ChromaFormat.YUV420, true, BT709)),
                    Arguments.of("full-range BT.601 4:2:0", (Supplier<Picture>) () -> yuv(ChromaFormat.YUV420, false, ColorInfo.JPEG)),
                    Arguments.of("BT.601 4:4:4", (Supplier<Picture>) () -> yuv(ChromaFormat.YUV444, false,
                            ColorInfo.BT601_LIMITED)),
                    Arguments.of("BT.709 4:2:2", (Supplier<Picture>) () -> yuv(ChromaFormat.YUV422, false, BT709)),
                    Arguments.of("BT.709 4:4:4", (Supplier<Picture>) () -> yuv(ChromaFormat.YUV444, false, BT709)),
                    Arguments.of("monochrome", (Supplier<Picture>) () -> yuv(ChromaFormat.MONOCHROME, false, BT709)),
                    Arguments.of("RGBA", (Supplier<Picture>) () -> rgba(false)),
                    Arguments.of("RGBA with alpha", (Supplier<Picture>) () -> rgba(true)));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("inputs")
        @DisplayName("gives the samples of ImageOps.toYuv and reuses its picture for the next one of the size")
        void matchesImageOps(String name, Supplier<Picture> pictures) {
            var converter = YuvConverter.create(ChromaFormat.YUV420, ColorInfo.BT601_LIMITED);
            var first = pictures.get();
            var converted = converter.convert(first);
            assertSamePicture(ImageOps.toYuv(first, ChromaFormat.YUV420, ColorInfo.BT601_LIMITED), converted);
            var second = pictures.get();
            var again = converter.convert(second);
            assertSame(converted, again);
            assertSamePicture(ImageOps.toYuv(second, ChromaFormat.YUV420, ColorInfo.BT601_LIMITED), again);
        }

        @Test
        @DisplayName("gives the samples of ImageOps.toYuv with alpha-weighted downsampling")
        void downsampling() {
            var converter = YuvConverter.create(ChromaFormat.YUV420, ColorInfo.JPEG, ChromaDownsampling.ALPHA_WEIGHTED);
            assertEquals(ChromaDownsampling.ALPHA_WEIGHTED, converter.downsampling());
            var picture = rgba(true);
            assertSamePicture(ImageOps.toYuv(picture, ChromaFormat.YUV420, ColorInfo.JPEG,
                    ChromaDownsampling.ALPHA_WEIGHTED), converter.convert(picture));
        }

        @Test
        @DisplayName("returns a picture already in its layout and color unchanged")
        void passesThrough() {
            var converter = YuvConverter.create(ChromaFormat.YUV420, ColorInfo.BT601_LIMITED);
            assertEquals(ChromaFormat.YUV420, converter.chromaFormat());
            assertEquals(ColorInfo.BT601_LIMITED, converter.color());
            var picture = yuv(ChromaFormat.YUV420, false, ColorInfo.BT601_LIMITED);
            assertSame(picture, converter.convert(picture));
        }

        @Test
        @DisplayName("allocates new pictures for a new size")
        void newSize() {
            var converter = YuvConverter.create(ChromaFormat.YUV420, ColorInfo.BT601_LIMITED);
            var small = converter.convert(yuv(ChromaFormat.YUV420, false, BT709));
            var large = yuv(ChromaFormat.YUV420, false, BT709, 66, 40);
            var converted = converter.convert(large);
            assertNotSame(small, converted);
            assertEquals(66, converted.width());
            assertEquals(40, converted.height());
            assertEquals(37, small.width());
            assertSamePicture(ImageOps.toYuv(large, ChromaFormat.YUV420, ColorInfo.BT601_LIMITED), converted);
        }

        @Test
        @DisplayName("rejects null arguments and unsupported matrices")
        void rejects() {
            assertThrows(NullPointerException.class, () -> YuvConverter.create(null, BT709));
            assertThrows(NullPointerException.class, () -> YuvConverter.create(ChromaFormat.YUV420, null));
            assertThrows(NullPointerException.class,
                    () -> YuvConverter.create(ChromaFormat.YUV420, BT709, null));
            var converter = YuvConverter.create(ChromaFormat.YUV420, BT709);
            var ictcp = new ColorInfo(MatrixCoefficients.ICTCP, ColorRange.LIMITED, ColorPrimaries.BT2020,
                    TransferCharacteristics.UNSPECIFIED);
            assertThrows(com.github.auties00.smalt.MediaException.Unsupported.class,
                    () -> converter.convert(yuv(ChromaFormat.YUV420, false, ictcp)));
        }
    }

    @Nested
    @DisplayName("direct conversion between matrices")
    class Direct {
        static Stream<Arguments> pairs() {
            var descriptions = new ArrayList<ColorInfo>();
            for (var matrix : WEIGHTED) {
                for (var range : ColorRange.values()) {
                    descriptions.add(color(matrix, range));
                }
            }
            var pairs = new ArrayList<Arguments>();
            for (var format : LAYOUTS) {
                for (var from : descriptions) {
                    for (var to : descriptions) {
                        if (from.matrix() != to.matrix()) {
                            pairs.add(Arguments.of(format, from, to));
                        }
                    }
                }
            }
            return pairs.stream();
        }

        @ParameterizedTest(name = "{0} {1} -> {2}")
        @MethodSource("pairs")
        @DisplayName("stays within one code value of the conversion through RGBA for uniform colors")
        void uniform(ChromaFormat format, ColorInfo from, ColorInfo to) {
            var converter = YuvConverter.create(format, to);
            for (var argb : colors()) {
                var source = ImageOps.toYuv(filled(argb, 6, 4), format, from);
                var expected = ImageOps.toYuv(ImageOps.toRgba(source), format, to);
                var actual = converter.convert(source);
                var difference = Quality.maxDifference(Pictures.raw(expected), Pictures.raw(actual));
                assertTrue(difference <= 1, String.format("0x%06X differs by %d", argb, difference));
            }
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(value = ChromaFormat.class, names = {"YUV420", "YUV422", "YUV444"})
        @DisplayName("stays close to the conversion through RGBA on a smooth picture and keeps alpha")
        void smooth(ChromaFormat format) {
            var source = ImageOps.toYuv(Pictures.smoothRgba(41, 27, true), format, BT709);
            var expected = ImageOps.toYuv(ImageOps.toRgba(source), format, ColorInfo.JPEG);
            var actual = YuvConverter.create(format, ColorInfo.JPEG).convert(source);
            var psnr = Quality.psnr(Pictures.raw(expected), Pictures.raw(actual));
            assertTrue(psnr > 45, "psnr " + psnr);
            assertArrayEquals(Pictures.tight(source.alpha(), source.alphaStride(), 41, 27),
                    Pictures.tight(actual.alpha(), actual.alphaStride(), 41, 27));
        }

        @Test
        @DisplayName("treats an unspecified matrix as BT.601 and a BT.470 BG one as the same matrix")
        void aliases() {
            var source = ImageOps.toYuv(Pictures.smoothRgba(20, 12, false), ChromaFormat.YUV420, BT709);
            var bt601 = ImageOps.toYuv(source, ChromaFormat.YUV420, ColorInfo.BT601_LIMITED);
            var unspecified = ImageOps.toYuv(source, ChromaFormat.YUV420, color(MatrixCoefficients.UNSPECIFIED,
                    ColorRange.LIMITED));
            var bt470bg = ImageOps.toYuv(source, ChromaFormat.YUV420, color(MatrixCoefficients.BT470BG,
                    ColorRange.LIMITED));
            assertArrayEquals(Pictures.raw(bt601), Pictures.raw(unspecified));
            assertArrayEquals(Pictures.raw(bt601), Pictures.raw(bt470bg));
        }

        @Test
        @DisplayName("keeps the conversion through RGBA for the identity matrix")
        void identity() {
            var source = ImageOps.toYuv(Pictures.smoothRgba(20, 12, false), ChromaFormat.YUV444, BT709);
            var gbr = color(MatrixCoefficients.IDENTITY, ColorRange.FULL);
            assertArrayEquals(Pictures.raw(ImageOps.toYuv(ImageOps.toRgba(source), ChromaFormat.YUV444, gbr)),
                    Pictures.raw(ImageOps.toYuv(source, ChromaFormat.YUV444, gbr)));
        }

        @Test
        @DisplayName("reuses its result for a stream of pictures of one size")
        void reused() {
            var converter = YuvConverter.create(ChromaFormat.YUV420, ColorInfo.BT601_LIMITED);
            var first = converter.convert(yuv(ChromaFormat.YUV420, false, BT709));
            var second = yuv(ChromaFormat.YUV420, false, BT709);
            assertSame(first, converter.convert(second));
            assertSamePicture(ImageOps.toYuv(second, ChromaFormat.YUV420, ColorInfo.BT601_LIMITED), first);
        }
    }

    private static ColorInfo color(MatrixCoefficients matrix, ColorRange range) {
        return new ColorInfo(matrix, range, ColorPrimaries.UNSPECIFIED, TransferCharacteristics.UNSPECIFIED);
    }

    private static List<Integer> colors() {
        var colors = new ArrayList<Integer>();
        for (var r = 0; r <= 255; r += 51) {
            for (var g = 0; g <= 255; g += 51) {
                for (var b = 0; b <= 255; b += 51) {
                    colors.add(r << 16 | g << 8 | b);
                }
            }
        }
        var random = new Random(5);
        for (var i = 0; i < 64; i++) {
            colors.add(random.nextInt(1 << 24));
        }
        return colors;
    }

    private static RgbaPicture filled(int argb, int width, int height) {
        var picture = RgbaPicture.allocate(width, height, false);
        var pixels = picture.pixels();
        for (var row = 0; row < height; row++) {
            for (var column = 0; column < width; column++) {
                var offset = row * picture.stride() + column * 4;
                pixels[offset] = (byte) (argb >> 16);
                pixels[offset + 1] = (byte) (argb >> 8);
                pixels[offset + 2] = (byte) argb;
                pixels[offset + 3] = (byte) 0xFF;
            }
        }
        return picture;
    }

    private static YuvPicture yuv(ChromaFormat format, boolean alpha, ColorInfo color) {
        return yuv(format, alpha, color, 37, 23);
    }

    private static YuvPicture yuv(ChromaFormat format, boolean alpha, ColorInfo color, int width, int height) {
        var random = new Random(seed++);
        var picture = YuvPicture.allocate(width, height, format, alpha, color);
        random.nextBytes(picture.y());
        if (format.hasChroma()) {
            random.nextBytes(picture.u());
            random.nextBytes(picture.v());
        }
        if (alpha) {
            random.nextBytes(picture.alpha());
        }
        return picture;
    }

    private static RgbaPicture rgba(boolean alpha) {
        var random = new Random(seed++);
        var picture = RgbaPicture.allocate(37, 23, alpha);
        random.nextBytes(picture.pixels());
        return picture;
    }

    private static void assertSamePicture(YuvPicture expected, YuvPicture actual) {
        assertEquals(expected.width(), actual.width());
        assertEquals(expected.height(), actual.height());
        assertEquals(expected.chromaFormat(), actual.chromaFormat());
        assertEquals(expected.color(), actual.color());
        assertEquals(expected.hasAlpha(), actual.hasAlpha());
        assertArrayEquals(Pictures.raw(expected), Pictures.raw(actual));
    }
}
