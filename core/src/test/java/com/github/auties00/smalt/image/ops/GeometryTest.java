package com.github.auties00.smalt.image.ops;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.testkit.Quality;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Arrays;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers crop, pad, flatten, the opacity scan, the pipeline compositions (fit within, fit into,
 * cover square) and argument validation.
 */
@DisplayName("Geometry and compositing")
class GeometryTest {
    static int pixel(RgbaPicture picture, int x, int y) {
        var i = y * picture.stride() + 4 * x;
        var p = picture.pixels();
        return (p[i + 3] & 0xFF) << 24 | (p[i] & 0xFF) << 16 | (p[i + 1] & 0xFF) << 8 | (p[i + 2] & 0xFF);
    }

    @Nested
    @DisplayName("crop")
    class Crop {
        @Test
        @DisplayName("copies RGBA rectangles exactly, including ones touching every border")
        void rgba() {
            var picture = Pictures.randomRgba(new SplittableRandom(1), 13, 9, true);
            for (var rect : new int[][]{{0, 0, 13, 1}, {0, 0, 1, 9}, {12, 8, 1, 1}, {3, 2, 10, 7}, {5, 4, 3, 2}, {0, 0, 12, 9}}) {
                var cropped = (RgbaPicture) ImageOps.crop(picture, rect[0], rect[1], rect[2], rect[3]);
                assertEquals(rect[2], cropped.width());
                assertEquals(rect[3], cropped.height());
                for (var y = 0; y < rect[3]; y++) {
                    for (var x = 0; x < rect[2]; x++) {
                        assertEquals(pixel(picture, rect[0] + x, rect[1] + y), pixel(cropped, x, y));
                    }
                }
            }
            assertSame(picture, ImageOps.crop(picture, 0, 0, 13, 9));
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(ChromaFormat.class)
        @DisplayName("crops luma exactly and takes the chroma sample covering each block's first pixel")
        void yuv(ChromaFormat format) {
            var picture = Pictures.randomYuv(new SplittableRandom(2), 15, 11, format, true, ColorInfo.JPEG);
            for (var rect : new int[][]{{1, 1, 5, 3}, {0, 0, 15, 1}, {14, 10, 1, 1}, {3, 5, 12, 6}, {2, 2, 4, 4}, {1, 0, 14, 11}}) {
                var cropped = (YuvPicture) ImageOps.crop(picture, rect[0], rect[1], rect[2], rect[3]);
                assertEquals(format, cropped.chromaFormat());
                for (var y = 0; y < rect[3]; y++) {
                    for (var x = 0; x < rect[2]; x++) {
                        assertEquals(picture.y()[(rect[1] + y) * picture.yStride() + rect[0] + x], cropped.y()[y * cropped.yStride() + x]);
                        assertEquals(picture.alpha()[(rect[1] + y) * picture.alphaStride() + rect[0] + x],
                                cropped.alpha()[y * cropped.alphaStride() + x]);
                    }
                }
                if (!format.hasChroma()) {
                    continue;
                }
                for (var cy = 0; cy < cropped.chromaHeight(); cy++) {
                    for (var cx = 0; cx < cropped.chromaWidth(); cx++) {
                        var sourceX = (rect[0] + (cx << format.shiftX())) >> format.shiftX();
                        var sourceY = (rect[1] + (cy << format.shiftY())) >> format.shiftY();
                        assertEquals(picture.u()[sourceY * picture.uvStride() + sourceX], cropped.u()[cy * cropped.uvStride() + cx]);
                        assertEquals(picture.v()[sourceY * picture.uvStride() + sourceX], cropped.v()[cy * cropped.uvStride() + cx]);
                    }
                }
            }
        }

        @ParameterizedTest(name = "{0},{1} {2}x{3}")
        @CsvSource({"-1, 0, 2, 2", "0, -1, 2, 2", "0, 0, 0, 2", "0, 0, 2, 0", "4, 0, 2, 2", "0, 3, 2, 2", "1, 1, 4, 4",
                "2147483647, 0, 1, 1"})
        @DisplayName("rejects empty rectangles and rectangles outside the picture")
        void invalid(int x, int y, int width, int height) {
            var picture = Pictures.randomRgba(new SplittableRandom(3), 5, 4, false);
            assertThrows(IllegalArgumentException.class, () -> ImageOps.crop(picture, x, y, width, height));
        }
    }

    @Nested
    @DisplayName("pad")
    class Pad {
        @Test
        @DisplayName("places the picture and fills the rest, clipping negative and overflowing offsets")
        void placement() {
            var picture = Pictures.randomRgba(new SplittableRandom(4), 5, 3, true);
            var background = 0x80102030;
            for (var offset : new int[][]{{0, 0}, {2, 1}, {-2, -1}, {4, 2}, {-5, 0}, {6, 4}, {-4, -2}, {Integer.MIN_VALUE, 0}, {0, Integer.MAX_VALUE}}) {
                var padded = (RgbaPicture) ImageOps.pad(picture, 6, 4, offset[0], offset[1], background);
                assertTrue(padded.hasAlpha());
                for (var y = 0; y < 4; y++) {
                    for (var x = 0; x < 6; x++) {
                        var sx = (long) x - offset[0];
                        var sy = (long) y - offset[1];
                        var inside = sx >= 0 && sx < 5 && sy >= 0 && sy < 3;
                        var expected = inside ? pixel(picture, (int) sx, (int) sy) : background;
                        assertEquals(expected, pixel(padded, x, y), "offset " + Arrays.toString(offset) + " at " + x + "," + y);
                    }
                }
            }
        }

        @Test
        @DisplayName("reports alpha only when the background or the picture has transparency")
        void alphaFlag() {
            var opaque = Pictures.randomRgba(new SplittableRandom(5), 4, 4, false);
            assertFalse(ImageOps.pad(opaque, 8, 8, 2, 2, 0xFFFFFFFF).hasAlpha());
            assertTrue(ImageOps.pad(opaque, 8, 8, 2, 2, 0x00000000).hasAlpha());
            var transparent = Pictures.randomRgba(new SplittableRandom(5), 4, 4, true);
            assertTrue(ImageOps.pad(transparent, 4, 4, 0, 0, 0xFF000000).hasAlpha());
        }

        @Test
        @DisplayName("writes opaque alpha for pictures that report none, whatever their alpha bytes hold")
        void opaqueAlphaBytes() {
            var random = Pictures.randomRgba(new SplittableRandom(6), 3, 3, true);
            var flagged = RgbaPicture.of(3, 3, random.stride(), random.pixels(), false);
            var padded = (RgbaPicture) ImageOps.pad(flagged, 5, 5, 1, 1, 0xFFFFFFFF);
            for (var i = 0; i < 25; i++) {
                assertEquals((byte) 255, padded.pixels()[4 * i + 3]);
            }
        }

        @Test
        @DisplayName("converts planar pictures to RGBA first")
        void planar() {
            var yuv = Pictures.randomYuv(new SplittableRandom(7), 4, 4, ChromaFormat.YUV420, false, ColorInfo.JPEG);
            var padded = (RgbaPicture) ImageOps.pad(yuv, 6, 6, 1, 1, 0xFF000000);
            var rgba = ImageOps.toRgba(yuv);
            for (var y = 0; y < 4; y++) {
                for (var x = 0; x < 4; x++) {
                    assertEquals(pixel(rgba, x, y), pixel(padded, x + 1, y + 1));
                }
            }
        }
    }

    @Nested
    @DisplayName("flatten")
    class Flatten {
        @Test
        @DisplayName("the division by 255 is exactly rounded for every product")
        void divide255() {
            for (var t = 0; t <= 65025; t++) {
                assertEquals((int) Math.floor(t / 255.0 + 0.5), ScalarPixelKernel.divide255(t), "t = " + t);
            }
        }

        @Test
        @DisplayName("composites RGBA over the background with exact rounding")
        void rgba() {
            var picture = Pictures.randomRgba(new SplittableRandom(8), 23, 7, true);
            var background = 0xF7F7F7;
            var flat = (RgbaPicture) ImageOps.flatten(picture, 0x12000000 | background);
            assertFalse(flat.hasAlpha());
            for (var y = 0; y < 7; y++) {
                for (var x = 0; x < 23; x++) {
                    var source = pixel(picture, x, y);
                    var a = source >>> 24;
                    var expected = 0xFF000000;
                    for (var shift = 16; shift >= 0; shift -= 8) {
                        var c = (source >>> shift) & 0xFF;
                        var k = (background >>> shift) & 0xFF;
                        expected |= (int) Math.round((c * a + k * (255 - a)) / 255.0) << shift;
                    }
                    assertEquals(expected, pixel(flat, x, y));
                }
            }
        }

        @Test
        @DisplayName("returns pictures without alpha unchanged")
        void opaque() {
            var picture = Pictures.randomRgba(new SplittableRandom(9), 3, 3, false);
            assertSame(picture, ImageOps.flatten(picture, 0));
            var yuv = Pictures.randomYuv(new SplittableRandom(9), 3, 3, ChromaFormat.YUV420, false, ColorInfo.JPEG);
            assertSame(yuv, ImageOps.flatten(yuv, 0));
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(value = ChromaFormat.class, names = {"YUV420", "YUV422", "YUV444"})
        @DisplayName("compositing in Y'CbCr matches compositing in RGB")
        void planarMatchesRgb(ChromaFormat format) {
            var yuv = ImageOps.toYuv(Pictures.smoothRgba(64, 40, true), format, ColorInfo.JPEG);
            var direct = (YuvPicture) ImageOps.flatten(yuv, 0xF7F7F7);
            assertFalse(direct.hasAlpha());
            assertEquals(format, direct.chromaFormat());
            // both sides upsample chroma once; they differ only where alpha varies inside a chroma block
            var expected = Pictures.tight((RgbaPicture) ImageOps.flatten(ImageOps.toRgba(yuv), 0xF7F7F7));
            var actual = Pictures.tight(ImageOps.toRgba(direct));
            var psnr = Quality.psnr(expected, actual);
            assertTrue(psnr > 45, "PSNR " + psnr);
            if (format == ChromaFormat.YUV444) {
                assertTrue(Quality.maxDifference(expected, actual) <= 2, "max difference " + Quality.maxDifference(expected, actual));
            }
        }

        @Test
        @DisplayName("fully transparent planar pictures become the background color")
        void transparentPlanar() {
            var yuv = YuvPicture.allocate(5, 3, ChromaFormat.YUV420, true, ColorInfo.BT709_LIMITED);
            Arrays.fill(yuv.alpha(), (byte) 0);
            Arrays.fill(yuv.y(), (byte) 200);
            var flat = (YuvPicture) ImageOps.flatten(yuv, 0xF7F7F7);
            var background = ImageOps.toYuv(RgbaPicture.of(1, 1, 4, new byte[]{(byte) 247, (byte) 247, (byte) 247, (byte) 255}, false),
                    ChromaFormat.YUV444, ColorInfo.BT709_LIMITED);
            for (var sample : flat.y()) {
                assertEquals(background.y()[0], sample);
            }
            for (var sample : flat.u()) {
                assertEquals(background.u()[0], sample);
            }
        }
    }

    @Nested
    @DisplayName("opacity")
    class Opacity {
        @Test
        @DisplayName("scans alpha only when a picture reports it")
        void scan() {
            var opaque = Pictures.randomRgba(new SplittableRandom(10), 37, 5, false);
            assertTrue(ImageOps.isOpaque(opaque));
            var flagged = RgbaPicture.of(37, 5, opaque.stride(), opaque.pixels(), true);
            assertTrue(ImageOps.isOpaque(flagged));
            opaque.pixels()[4 * 36 + 3 + 4 * opaque.stride() / 4 * 0] = (byte) 254;
            assertFalse(ImageOps.isOpaque(flagged));
            assertTrue(ImageOps.isOpaque(RgbaPicture.of(37, 5, opaque.stride(), opaque.pixels(), false)));
            var yuv = YuvPicture.allocate(33, 3, ChromaFormat.YUV420, true, ColorInfo.JPEG);
            assertTrue(ImageOps.isOpaque(yuv));
            yuv.alpha()[98] = 0;
            assertFalse(ImageOps.isOpaque(yuv));
        }
    }

    @Nested
    @DisplayName("compositions")
    class Compositions {
        @ParameterizedTest(name = "{0}x{1} within {2} -> {3}x{4}")
        @CsvSource({"4000, 3000, 1600, 1600, 1200", "3000, 4000, 1600, 1200, 1600", "1601, 1, 1600, 1600, 1", "5000, 2, 1600, 1600, 1",
                "2, 5000, 100, 1, 100", "1000, 999, 100, 100, 100", "1000, 995, 100, 100, 100", "1000, 994, 100, 100, 99",
                "641, 480, 640, 640, 479", "2560, 2560, 2560, 2560, 2560"})
        @DisplayName("fitWithin keeps the aspect, rounds to nearest and never upscales")
        void fitWithin(int width, int height, int maxEdge, int expectedWidth, int expectedHeight) {
            var picture = RgbaPicture.allocate(width, height, false);
            var fitted = ImageOps.fitWithin(picture, maxEdge, ResizeFilter.AREA);
            assertEquals(expectedWidth, fitted.width());
            assertEquals(expectedHeight, fitted.height());
            if (width <= maxEdge && height <= maxEdge) {
                assertSame(picture, fitted);
            }
        }

        @Test
        @DisplayName("fitInto centers the fitted picture on a transparent sticker canvas")
        void fitInto() {
            var wide = Pictures.patternRgba(1000, 500, false);
            var sticker = (RgbaPicture) ImageOps.fitInto(wide, 512, 512, ResizeFilter.LANCZOS3, 0);
            assertEquals(512, sticker.width());
            assertEquals(512, sticker.height());
            assertTrue(sticker.hasAlpha());
            assertEquals(0, pixel(sticker, 100, 127));
            assertEquals(0xFF, pixel(sticker, 100, 128) >>> 24);
            assertEquals(0xFF, pixel(sticker, 100, 383) >>> 24);
            assertEquals(0, pixel(sticker, 100, 384));
            var small = Pictures.patternRgba(100, 100, true);
            var enlarged = (RgbaPicture) ImageOps.fitInto(small, 512, 512, ResizeFilter.BICUBIC, 0);
            assertEquals(512, enlarged.width());
            var thin = RgbaPicture.allocate(1, 1000, false);
            var line = (RgbaPicture) ImageOps.fitInto(thin, 512, 512, ResizeFilter.AREA, 0x00000000);
            assertEquals(0xFF000000, pixel(line, 255, 0));
            assertEquals(0, pixel(line, 254, 0));
            assertEquals(0, pixel(line, 256, 0));
            var yuv = Pictures.randomYuv(new SplittableRandom(11), 30, 20, ChromaFormat.YUV420, true, ColorInfo.JPEG);
            var fromPlanar = ImageOps.fitInto(yuv, 64, 64, ResizeFilter.AREA, 0);
            assertEquals(64, fromPlanar.height());
        }

        @Test
        @DisplayName("coverSquare crops the centered square and resizes it")
        void coverSquare() {
            var picture = Pictures.patternRgba(300, 200, false);
            var square = (RgbaPicture) ImageOps.coverSquare(picture, 100, ResizeFilter.AREA);
            assertEquals(100, square.width());
            assertEquals(100, square.height());
            var twoStep = (RgbaPicture) ImageOps.resize(ImageOps.crop(picture, 50, 0, 200, 200), 100, 100, ResizeFilter.AREA);
            assertArrayEquals(Pictures.tight(twoStep), Pictures.tight(square));
            var same = (RgbaPicture) ImageOps.coverSquare(picture, 200, ResizeFilter.LANCZOS3);
            assertArrayEquals(Pictures.tight((RgbaPicture) ImageOps.crop(picture, 50, 0, 200, 200)), Pictures.tight(same));
            var squareInput = Pictures.patternRgba(64, 64, false);
            assertSame(squareInput, ImageOps.coverSquare(squareInput, 64, ResizeFilter.BILINEAR));
            var enlarged = ImageOps.coverSquare(Pictures.patternRgba(90, 120, true), 640, ResizeFilter.LANCZOS3);
            assertEquals(640, enlarged.width());
        }

        @Test
        @DisplayName("coverSquare resamples planar chroma at its exact position for odd offsets")
        void coverSquarePlanar() {
            for (var width : new int[]{301, 303}) {
                var rgba = Pictures.smoothRgba(width, 200, false);
                var yuv = ImageOps.toYuv(rgba, ChromaFormat.YUV420, ColorInfo.JPEG);
                var square = (YuvPicture) ImageOps.coverSquare(yuv, 192, ResizeFilter.LANCZOS3);
                assertEquals(ChromaFormat.YUV420, square.chromaFormat());
                assertEquals(96, square.chromaWidth());
                var reference = ImageOps.toYuv(ImageOps.coverSquare(rgba, 192, ResizeFilter.LANCZOS3), ChromaFormat.YUV420, ColorInfo.JPEG);
                var psnr = Quality.psnr(Pictures.raw(reference), Pictures.raw(square));
                assertTrue(psnr > 45, "width " + width + " PSNR " + psnr);
            }
        }
    }

    @Nested
    @DisplayName("validation")
    class Validation {
        final RgbaPicture picture = Pictures.randomRgba(new SplittableRandom(12), 4, 4, false);

        @Test
        @DisplayName("null arguments are rejected")
        void nulls() {
            assertThrows(NullPointerException.class, () -> ImageOps.toRgba(null));
            assertThrows(NullPointerException.class, () -> ImageOps.toRgba(picture, null));
            assertThrows(NullPointerException.class, () -> ImageOps.toYuv(picture, null, ColorInfo.JPEG));
            assertThrows(NullPointerException.class, () -> ImageOps.toYuv(picture, ChromaFormat.YUV420, null));
            assertThrows(NullPointerException.class, () -> ImageOps.resize(picture, 2, 2, (ResizeFilter) null));
            assertThrows(NullPointerException.class, () -> ImageOps.resize(picture, 2, 2, (ResizeOptions) null));
            assertThrows(NullPointerException.class, () -> ImageOps.resize(null, 2, 2, ResizeFilter.AREA));
            assertThrows(NullPointerException.class, () -> ImageOps.orient(picture, null));
            assertThrows(NullPointerException.class, () -> ImageOps.crop(null, 0, 0, 1, 1));
            assertThrows(NullPointerException.class, () -> ImageOps.pad(null, 1, 1, 0, 0, 0));
            assertThrows(NullPointerException.class, () -> ImageOps.flatten(null, 0));
            assertThrows(NullPointerException.class, () -> ImageOps.isOpaque(null));
            assertThrows(NullPointerException.class, () -> ImageOps.fitWithin(picture, 2, (ResizeFilter) null));
            assertThrows(NullPointerException.class, () -> ImageOps.fitWithin(picture, 2, (ResizeOptions) null));
            assertThrows(NullPointerException.class, () -> ImageOps.fitInto(picture, 2, 2, (ResizeFilter) null, 0));
            assertThrows(NullPointerException.class, () -> ImageOps.fitInto(picture, 2, 2, (ResizeOptions) null, 0));
            assertThrows(NullPointerException.class, () -> ImageOps.coverSquare(picture, 2, (ResizeFilter) null));
            assertThrows(NullPointerException.class, () -> ImageOps.coverSquare(picture, 2, (ResizeOptions) null));
        }

        @Test
        @DisplayName("non-positive sizes are rejected")
        void sizes() {
            assertThrows(IllegalArgumentException.class, () -> ImageOps.resize(picture, 0, 2, ResizeFilter.AREA));
            assertThrows(IllegalArgumentException.class, () -> ImageOps.resize(picture, 2, -1, ResizeFilter.AREA));
            assertThrows(IllegalArgumentException.class, () -> ImageOps.pad(picture, 0, 1, 0, 0, 0));
            assertThrows(IllegalArgumentException.class, () -> ImageOps.fitWithin(picture, 0, ResizeFilter.AREA));
            assertThrows(IllegalArgumentException.class, () -> ImageOps.fitInto(picture, 1, 0, ResizeFilter.AREA, 0));
            assertThrows(IllegalArgumentException.class, () -> ImageOps.coverSquare(picture, 0, ResizeFilter.AREA));
        }

        @Test
        @DisplayName("outputs beyond the default limits raise LimitExceeded before allocating")
        void limits() {
            assertThrows(MediaException.LimitExceeded.class, () -> ImageOps.resize(picture, 16385, 2, ResizeFilter.AREA));
            assertThrows(MediaException.LimitExceeded.class, () -> ImageOps.resize(picture, 16384, 16384, ResizeFilter.AREA));
            assertThrows(MediaException.LimitExceeded.class, () -> ImageOps.pad(picture, 20000, 1, 0, 0, 0));
            assertThrows(MediaException.LimitExceeded.class, () -> ImageOps.fitInto(picture, 1, 100000, ResizeFilter.AREA, 0));
            assertThrows(MediaException.LimitExceeded.class, () -> ImageOps.coverSquare(picture, 17000, ResizeFilter.AREA));
            var tall = RgbaPicture.allocate(1, 16384, false);
            var wide = RgbaPicture.allocate(16384, 1, false);
            assertEquals(16384, ImageOps.orient(tall, Orientation.ROTATE_90).width());
            assertEquals(16384, ImageOps.resize(wide, 16384, 2, ResizeFilter.NEAREST).width());
        }

        @Test
        @DisplayName("inputs are never modified")
        void inputsUntouched() {
            var rgba = Pictures.randomRgba(new SplittableRandom(13), 20, 10, true);
            var yuv = Pictures.randomYuv(new SplittableRandom(13), 20, 10, ChromaFormat.YUV420, true, ColorInfo.JPEG);
            var rgbaCopy = rgba.pixels().clone();
            var yuvCopy = Pictures.raw(yuv);
            for (Picture input : new Picture[]{rgba, yuv}) {
                ImageOps.toRgba(input);
                ImageOps.toYuv(input, ChromaFormat.YUV422, ColorInfo.BT709_LIMITED);
                for (var filter : ResizeFilter.values()) {
                    ImageOps.resize(input, 7, 13, filter);
                    ImageOps.coverSquare(input, 9, filter);
                    ImageOps.fitInto(input, 9, 9, filter, 0);
                }
                for (var orientation : Orientation.values()) {
                    ImageOps.orient(input, orientation);
                }
                ImageOps.crop(input, 1, 1, 5, 5);
                ImageOps.pad(input, 30, 30, -3, 4, 0x80FFFFFF);
                ImageOps.flatten(input, 0xF7F7F7);
                ImageOps.isOpaque(input);
            }
            assertArrayEquals(rgbaCopy, rgba.pixels());
            assertArrayEquals(yuvCopy, Pictures.raw(yuv));
        }
    }
}
