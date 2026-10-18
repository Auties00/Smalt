package com.github.auties00.smalt.image.ops;

import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the bit depth conversions of {@link ImageOps} and the operations that keep 16-bit
 * pictures at their depth (crop, orientation, opacity), checked against the 8-bit operations
 * through the lossless widening.
 */
class BitDepthTest {
    @ParameterizedTest(name = "{0}-bit")
    @ValueSource(ints = {9, 10, 12, 16})
    @DisplayName("reduces every Y'CbCr sample value by rounding to nearest, clamped to 255")
    void narrowsEverySample(int depth) {
        var max = (1 << depth) - 1;
        var width = 256;
        var height = (max + width) / width;
        var picture = Yuv16Picture.allocate(width, height, ChromaFormat.MONOCHROME, depth, false, ColorInfo.BT709_LIMITED);
        for (var v = 0; v <= max; v++) {
            picture.y()[v] = (short) v;
        }
        var narrow = ImageOps.toEightBit(picture);
        var shift = depth - 8;
        for (var v = 0; v <= max; v++) {
            var expected = Math.min((v + (1 << (shift - 1))) >> shift, 255);
            assertEquals(expected, narrow.y()[v] & 0xFF, "sample " + v);
        }
        assertEquals(ColorInfo.BT709_LIMITED, narrow.color());
        assertEquals(ChromaFormat.MONOCHROME, narrow.chromaFormat());
    }

    @Test
    @DisplayName("reduces every 16-bit RGBA sample as (v * 255 + 32767) / 65535")
    void narrowsEveryRgbaSample() {
        var picture = Rgba16Picture.allocate(128, 128, true);
        for (var v = 0; v < 65536; v++) {
            picture.pixels()[v] = (short) v;
        }
        var narrow = ImageOps.toEightBit(picture);
        for (var v = 0; v < 65536; v++) {
            assertEquals((v * 255 + 32767) / 65535, narrow.pixels()[v] & 0xFF, "sample " + v);
        }
        assertTrue(narrow.hasAlpha());
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(ChromaFormat.class)
    @DisplayName("widens 8-bit pictures losslessly and keeps opaque alpha opaque")
    void widensLosslessly(ChromaFormat format) {
        var random = new SplittableRandom(format.ordinal());
        var picture = Pictures.randomYuv(random, 37, 23, format, true, ColorInfo.BT601_LIMITED);
        for (var depth : new int[]{8, 10, 12, 16}) {
            var wide = ImageOps.toBitDepth(picture, depth);
            assertEquals(depth, wide.bitDepth());
            assertEquals((picture.y()[0] & 0xFF) << (depth - 8), wide.y()[0] & 0xFFFF);
            assertSamePlanes(picture, ImageOps.toEightBit(wide));
        }
        var opaque = YuvPicture.allocate(4, 4, format, true, ColorInfo.JPEG);
        assertEquals(1023, ImageOps.toBitDepth(opaque, 10).alpha()[0]);
        assertTrue(ImageOps.isOpaque(ImageOps.toBitDepth(opaque, 10)));
    }

    @Test
    @DisplayName("converts between high bit depths, losslessly upwards and rounding downwards")
    void convertsBetweenDepths() {
        var picture = Yuv16Picture.allocate(4, 2, ChromaFormat.YUV420, 10, true, ColorInfo.BT709_LIMITED);
        picture.y()[0] = 1023;
        picture.y()[1] = 513;
        picture.alpha()[0] = 512;
        var wide = ImageOps.toBitDepth(picture, 12);
        assertEquals(4092, wide.y()[0]);
        assertEquals(2052, wide.y()[1]);
        assertEquals(Math.round(512 * 4095 / 1023.0), wide.alpha()[0]);
        var back = ImageOps.toBitDepth(wide, 10);
        assertArrayEquals(picture.y(), back.y());
        assertArrayEquals(picture.u(), back.u());
        assertArrayEquals(picture.alpha(), back.alpha());
        assertSame(picture, ImageOps.toBitDepth(picture, 10));
        assertEquals(1023, ImageOps.toBitDepth(Yuv16Picture.allocate(2, 2, ChromaFormat.YUV420, 12, false,
                ColorInfo.JPEG).toBuilder().luma(new short[]{4095, 4095, 4095, 4095}, 2).build(), 10).y()[0]);
        assertThrows(IllegalArgumentException.class, () -> ImageOps.toBitDepth(picture, 17));
    }

    @Test
    @DisplayName("widens packed 8-bit pictures losslessly")
    void widensRgba() {
        var picture = Pictures.randomRgba(new SplittableRandom(7), 19, 11, true);
        var wide = ImageOps.toSixteenBit(picture);
        assertEquals((picture.pixels()[0] & 0xFF) * 257, wide.pixels()[0] & 0xFFFF);
        assertArrayEquals(Pictures.tight(picture), Pictures.tight(ImageOps.toEightBit(wide)));
        assertSame(picture, ImageOps.toEightBit((Picture) picture));
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Orientation.class)
    @DisplayName("orients 16-bit pictures exactly like their 8-bit counterparts")
    void orients(Orientation orientation) {
        var random = new SplittableRandom(orientation.ordinal());
        for (var format : ChromaFormat.values()) {
            var picture = Pictures.randomYuv(random, 21, 13, format, true, ColorInfo.BT709_LIMITED);
            var expected = ImageOps.toBitDepth((YuvPicture) ImageOps.orient(picture, orientation), 10);
            var actual = (Yuv16Picture) ImageOps.orient(ImageOps.toBitDepth(picture, 10), orientation);
            assertSamePlanes(expected, actual);
        }
        var rgba = Pictures.randomRgba(random, 21, 13, true);
        var expected = ImageOps.toSixteenBit((RgbaPicture) ImageOps.orient(rgba, orientation));
        var actual = (Rgba16Picture) ImageOps.orient(ImageOps.toSixteenBit(rgba), orientation);
        assertEquals(expected.width(), actual.width());
        assertArrayEquals(expected.pixels(), actual.pixels());
    }

    @Test
    @DisplayName("crops 16-bit pictures exactly like their 8-bit counterparts")
    void crops() {
        var random = new SplittableRandom(3);
        for (var format : ChromaFormat.values()) {
            var picture = Pictures.randomYuv(random, 21, 13, format, true, ColorInfo.BT709_LIMITED);
            var expected = ImageOps.toBitDepth((YuvPicture) ImageOps.crop(picture, 3, 5, 11, 7), 12);
            var actual = (Yuv16Picture) ImageOps.crop(ImageOps.toBitDepth(picture, 12), 3, 5, 11, 7);
            assertSamePlanes(expected, actual);
        }
        var rgba = Pictures.randomRgba(random, 21, 13, false);
        var expected = ImageOps.toSixteenBit((RgbaPicture) ImageOps.crop(rgba, 1, 2, 5, 6));
        var actual = (Rgba16Picture) ImageOps.crop(ImageOps.toSixteenBit(rgba), 1, 2, 5, 6);
        assertArrayEquals(expected.pixels(), actual.pixels());
    }

    @Test
    @DisplayName("converts, resizes and flattens 16-bit pictures through their 8-bit reduction")
    void reducesForOtherOperations() {
        var random = new SplittableRandom(11);
        var picture = Pictures.randomYuv(random, 24, 16, ChromaFormat.YUV420, true, ColorInfo.BT601_LIMITED);
        var wide = ImageOps.toBitDepth(picture, 10);
        assertArrayEquals(Pictures.tight(ImageOps.toRgba(picture)), Pictures.tight(ImageOps.toRgba(wide)));
        assertSamePlanes(ImageOps.toYuv(picture, ChromaFormat.YUV444, ColorInfo.JPEG),
                ImageOps.toYuv(wide, ChromaFormat.YUV444, ColorInfo.JPEG));
        assertSamePlanes((YuvPicture) ImageOps.resize(picture, 12, 8, ResizeFilter.LANCZOS3),
                (YuvPicture) ImageOps.resize(wide, 12, 8, ResizeFilter.LANCZOS3));
        assertSamePlanes((YuvPicture) ImageOps.flatten(picture, 0x336699),
                (YuvPicture) ImageOps.flatten(wide, 0x336699));
    }

    @Test
    @DisplayName("scans the alpha of 16-bit pictures at their own maximum")
    void opacity() {
        var picture = Yuv16Picture.allocate(3, 3, ChromaFormat.YUV420, 12, true, ColorInfo.JPEG);
        assertTrue(ImageOps.isOpaque(picture));
        picture.alpha()[8] = 4094;
        assertFalse(ImageOps.isOpaque(picture));
        var rgba = Rgba16Picture.allocate(2, 2, true);
        assertFalse(ImageOps.isOpaque(rgba));
        java.util.Arrays.fill(rgba.pixels(), (short) 0xFFFF);
        assertTrue(ImageOps.isOpaque(rgba));
    }

    private static void assertSamePlanes(YuvPicture expected, YuvPicture actual) {
        assertEquals(expected.width(), actual.width());
        assertEquals(expected.height(), actual.height());
        assertEquals(expected.chromaFormat(), actual.chromaFormat());
        assertArrayEquals(Pictures.raw(expected), Pictures.raw(actual));
    }

    private static void assertSamePlanes(Yuv16Picture expected, Yuv16Picture actual) {
        assertEquals(expected.width(), actual.width());
        assertEquals(expected.height(), actual.height());
        assertEquals(expected.chromaFormat(), actual.chromaFormat());
        assertEquals(expected.bitDepth(), actual.bitDepth());
        assertPlane(expected.y(), expected.yStride(), actual.y(), actual.yStride(), expected.width(), expected.height());
        if (expected.chromaFormat().hasChroma()) {
            assertPlane(expected.u(), expected.uvStride(), actual.u(), actual.uvStride(), expected.chromaWidth(),
                    expected.chromaHeight());
            assertPlane(expected.v(), expected.uvStride(), actual.v(), actual.uvStride(), expected.chromaWidth(),
                    expected.chromaHeight());
        }
        assertEquals(expected.hasAlpha(), actual.hasAlpha());
        if (expected.hasAlpha()) {
            assertPlane(expected.alpha(), expected.alphaStride(), actual.alpha(), actual.alphaStride(), expected.width(),
                    expected.height());
        }
    }

    private static void assertPlane(short[] expected, int expectedStride, short[] actual, int actualStride, int width,
                                    int height) {
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                assertEquals(expected[y * expectedStride + x], actual[y * actualStride + x], "sample " + x + "," + y);
            }
        }
    }
}
