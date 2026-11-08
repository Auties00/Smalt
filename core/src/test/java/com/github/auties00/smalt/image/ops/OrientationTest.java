package com.github.auties00.smalt.image.ops;

import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.YuvPicture;
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

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the eight EXIF orientation transforms against a naive per-pixel mapping written from
 * the EXIF definitions, their inverses, and ffmpeg's transpose, hflip and vflip filters.
 */
@DisplayName("Orientation")
class OrientationTest {
    static final int[][] SIZES = {{1, 1}, {1, 9}, {9, 1}, {13, 7}, {7, 13}, {16, 16}, {17, 33}, {65, 64}, {130, 67}};

    /** Returns where source pixel (x, y) of a w x h picture lands. */
    static int[] target(Orientation orientation, int x, int y, int w, int h) {
        return switch (orientation) {
            case NORMAL -> new int[]{x, y};
            case FLIP_HORIZONTAL -> new int[]{w - 1 - x, y};
            case ROTATE_180 -> new int[]{w - 1 - x, h - 1 - y};
            case FLIP_VERTICAL -> new int[]{x, h - 1 - y};
            case TRANSPOSE -> new int[]{y, x};
            case ROTATE_90 -> new int[]{h - 1 - y, x};
            case TRANSVERSE -> new int[]{h - 1 - y, w - 1 - x};
            case ROTATE_270 -> new int[]{y, w - 1 - x};
        };
    }

    static Orientation inverse(Orientation orientation) {
        return switch (orientation) {
            case ROTATE_90 -> Orientation.ROTATE_270;
            case ROTATE_270 -> Orientation.ROTATE_90;
            default -> orientation;
        };
    }

    /** Applies a transform to one plane naively. */
    static byte[] naive(byte[] plane, int stride, int w, int h, int bytes, Orientation orientation) {
        var swap = orientation.swapsDimensions();
        var outWidth = swap ? h : w;
        var out = new byte[w * h * bytes];
        for (var y = 0; y < h; y++) {
            for (var x = 0; x < w; x++) {
                var t = target(orientation, x, y, w, h);
                System.arraycopy(plane, y * stride + bytes * x, out, bytes * (t[1] * outWidth + t[0]), bytes);
            }
        }
        return out;
    }

    static Stream<Arguments> orientationsAndSizes() {
        var arguments = new ArrayList<Arguments>();
        for (var orientation : Orientation.values()) {
            for (var size : SIZES) {
                arguments.add(Arguments.of(orientation, size[0], size[1]));
            }
        }
        return arguments.stream();
    }

    @Nested
    @DisplayName("naive reference")
    class Naive {
        @ParameterizedTest(name = "{0} {1}x{2}")
        @MethodSource("com.github.auties00.smalt.image.ops.OrientationTest#orientationsAndSizes")
        @DisplayName("RGBA pictures move every pixel where EXIF says")
        void rgba(Orientation orientation, int width, int height) {
            var picture = Pictures.randomRgba(new SplittableRandom(width * 31L + height), width, height, true);
            var oriented = (RgbaPicture) ImageOps.orient(picture, orientation);
            var swap = orientation.swapsDimensions();
            assertEquals(swap ? height : width, oriented.width());
            assertEquals(swap ? width : height, oriented.height());
            assertTrue(oriented.hasAlpha());
            assertArrayEquals(naive(picture.pixels(), picture.stride(), width, height, 4, orientation), Pictures.tight(oriented));
        }

        @ParameterizedTest(name = "{0} {1}x{2}")
        @MethodSource("com.github.auties00.smalt.image.ops.OrientationTest#orientationsAndSizes")
        @DisplayName("planar pictures transform every plane, in every layout")
        void yuv(Orientation orientation, int width, int height) {
            for (var format : ChromaFormat.values()) {
                var picture = Pictures.randomYuv(new SplittableRandom(width * 7L + height), width, height, format, true, ColorInfo.JPEG);
                var oriented = (YuvPicture) ImageOps.orient(picture, orientation);
                var swap = orientation.swapsDimensions();
                var expectedFormat = swap && (format == ChromaFormat.YUV422 || format == ChromaFormat.YUV440)
                        ? ChromaFormat.YUV444 : format;
                assertEquals(expectedFormat, oriented.chromaFormat(), format.name());
                var outWidth = swap ? height : width;
                var outHeight = swap ? width : height;
                assertArrayEquals(naive(picture.y(), picture.yStride(), width, height, 1, orientation),
                        Pictures.tight(oriented.y(), oriented.yStride(), outWidth, outHeight), format + " luma");
                assertArrayEquals(naive(picture.alpha(), picture.alphaStride(), width, height, 1, orientation),
                        Pictures.tight(oriented.alpha(), oriented.alphaStride(), outWidth, outHeight), format + " alpha");
                if (!format.hasChroma()) {
                    continue;
                }
                byte[] u;
                int uStride;
                int chromaWidth;
                int chromaHeight;
                if (expectedFormat != format) {
                    chromaWidth = width;
                    chromaHeight = height;
                    uStride = width;
                    u = new byte[width * height];
                    for (var y = 0; y < height; y++) {
                        for (var x = 0; x < width; x++) {
                            u[y * width + x] = picture.u()[(y >> format.shiftY()) * picture.uvStride()
                                    + (x >> format.shiftX())];
                        }
                    }
                } else {
                    chromaWidth = picture.chromaWidth();
                    chromaHeight = picture.chromaHeight();
                    uStride = picture.uvStride();
                    u = picture.u();
                }
                assertArrayEquals(naive(u, uStride, chromaWidth, chromaHeight, 1, orientation),
                        Pictures.tight(oriented.u(), oriented.uvStride(), oriented.chromaWidth(), oriented.chromaHeight()),
                        format + " chroma");
            }
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(Orientation.class)
        @DisplayName("applying a transform then its inverse restores the picture")
        void inverse(Orientation orientation) {
            var random = new SplittableRandom(orientation.ordinal());
            for (var size : SIZES) {
                var rgba = Pictures.randomRgba(random, size[0], size[1], false);
                var back = (RgbaPicture) ImageOps.orient(ImageOps.orient(rgba, orientation), OrientationTest.inverse(orientation));
                assertArrayEquals(Pictures.tight(rgba), Pictures.tight(back));
                for (var format : ChromaFormat.values()) {
                    var yuv = Pictures.randomYuv(random, size[0], size[1], format, true, ColorInfo.BT709_LIMITED);
                    var restored = (YuvPicture) ImageOps.orient(ImageOps.orient(yuv, orientation), OrientationTest.inverse(orientation));
                    if (restored.chromaFormat() != format) {
                        // the 4:4:4 detour of a 4:2:2 or 4:4:0 picture replicated chroma, so averaging restores it
                        restored = ImageOps.toYuv(restored, format, yuv.color());
                    }
                    assertArrayEquals(Pictures.raw(yuv), Pictures.raw(restored), format + " " + size[0] + "x" + size[1]);
                }
            }
        }

        @Test
        @DisplayName("NORMAL returns the input")
        void normal() {
            var picture = Pictures.randomRgba(new SplittableRandom(1), 3, 2, false);
            assertSame(picture, ImageOps.orient(picture, Orientation.NORMAL));
        }

        @Test
        @DisplayName("four quarter turns compose to the identity")
        void fourTurns() {
            var picture = Pictures.randomYuv(new SplittableRandom(2), 37, 21, ChromaFormat.YUV420, false, ColorInfo.JPEG);
            var turned = ImageOps.orient(picture, Orientation.ROTATE_90);
            turned = ImageOps.orient(turned, Orientation.ROTATE_90);
            turned = ImageOps.orient(turned, Orientation.ROTATE_90);
            turned = ImageOps.orient(turned, Orientation.ROTATE_90);
            assertArrayEquals(Pictures.raw(picture), Pictures.raw((YuvPicture) turned));
            var twice = ImageOps.orient(ImageOps.orient(picture, Orientation.ROTATE_90), Orientation.ROTATE_90);
            assertArrayEquals(Pictures.raw((YuvPicture) ImageOps.orient(picture, Orientation.ROTATE_180)), Pictures.raw((YuvPicture) twice));
        }
    }

    @Nested
    @DisplayName("against ffmpeg")
    class Ffmpeg {
        static String filter(Orientation orientation) {
            return switch (orientation) {
                case NORMAL -> "null";
                case FLIP_HORIZONTAL -> "hflip";
                case ROTATE_180 -> "hflip,vflip";
                case FLIP_VERTICAL -> "vflip";
                case TRANSPOSE -> "transpose=cclock_flip";
                case ROTATE_90 -> "transpose=clock";
                case TRANSVERSE -> "transpose=clock_flip";
                case ROTATE_270 -> "transpose=cclock";
            };
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(Orientation.class)
        @DisplayName("matches ffmpeg exactly on RGBA, gray, 4:2:0 and 4:4:4 at odd sizes")
        void matches(Orientation orientation) {
            var width = 67;
            var height = 45;
            var rgbaRaw = Pictures.testsrc(width, height, "rgba");
            var rgba = RgbaPicture.of(width, height, 4 * width, rgbaRaw, false);
            assertArrayEquals(Pictures.ffmpeg(rgbaRaw, "rgba", width, height, filter(orientation), "rgba"),
                    Pictures.tight((RgbaPicture) ImageOps.orient(rgba, orientation)), "rgba");
            for (var format : new ChromaFormat[]{ChromaFormat.MONOCHROME, ChromaFormat.YUV420, ChromaFormat.YUV444}) {
                var pixelFormat = Pictures.pixelFormat(format, false);
                var raw = Pictures.testsrc(width, height, pixelFormat);
                var yuv = Pictures.fromRaw(raw, width, height, format, false, ColorInfo.BT601_LIMITED);
                assertArrayEquals(Pictures.ffmpeg(raw, pixelFormat, width, height, filter(orientation), pixelFormat),
                        Pictures.raw((YuvPicture) ImageOps.orient(yuv, orientation)), pixelFormat);
            }
        }
    }
}
