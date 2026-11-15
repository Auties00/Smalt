package com.github.auties00.smalt.transcode;

import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.image.ops.Region;
import com.github.auties00.smalt.image.ops.ResizeFilter;
import com.github.auties00.smalt.internal.resize.SizePlan;
import com.github.auties00.smalt.track.Rational;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Checks that the per-picture operations agree with the image operations they combine: a crop in
 * displayed coordinates maps to the stored rectangle under every orientation, and keeping the
 * orientation as metadata yields the stored form of exactly the picture applying it would.
 */
class PictureOpsTest {
    static RgbaPicture pattern(int width, int height) {
        var picture = RgbaPicture.allocate(width, height, false);
        var pixels = picture.pixels();
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                var offset = y * picture.stride() + x * 4;
                pixels[offset] = (byte) x;
                pixels[offset + 1] = (byte) y;
                pixels[offset + 2] = (byte) (x * 7 + y * 13);
                pixels[offset + 3] = (byte) 255;
            }
        }
        return picture;
    }

    static void assertSamePixels(Picture expected, Picture actual) {
        var left = ImageOps.toRgba(expected);
        var right = ImageOps.toRgba(actual);
        assertEquals(left.width(), right.width(), "width");
        assertEquals(left.height(), right.height(), "height");
        for (var y = 0; y < left.height(); y++) {
            for (var x = 0; x < left.width() * 4; x++) {
                assertEquals(left.pixels()[y * left.stride() + x], right.pixels()[y * right.stride() + x], "at " + x / 4 + "," + y);
            }
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Orientation.class)
    @DisplayName("maps a displayed crop to the stored rectangle that displays it")
    void cropMapping(Orientation orientation) {
        var stored = pattern(40, 30);
        var displayed = ImageOps.orient(stored, orientation);
        var rect = PictureOps.toCoded(5, 3, 17, 11, orientation, displayed.width(), displayed.height());
        var croppedThenOriented = ImageOps.orient(ImageOps.crop(stored, rect.x(), rect.y(), rect.width(), rect.height()),
                orientation);
        assertSamePixels(ImageOps.crop(displayed, 5, 3, 17, 11), croppedThenOriented);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Orientation.class)
    @DisplayName("keeping the orientation as metadata stores exactly the picture applying it displays")
    void preserveMatchesApply(Orientation orientation) {
        var stored = pattern(48, 36);
        // an exact 2:1 area reduction commutes with every orientation, so the two paths must agree pixel for pixel
        var region = new Region(2, 4, orientation.swapsDimensions() ? 20 : 40, orientation.swapsDimensions() ? 40 : 20);
        PictureOps.SizePlanner sizing = (width, height) ->
                SizePlan.of(width, height, 10, 10, ScaleMode.FILL, false, 0, 0, false);
        var applied = PictureOps.plan(48, 36, orientation, true, region, sizing, ResizeFilter.AREA, -1, null, null);
        var preserved = PictureOps.plan(48, 36, orientation, false, region, sizing, ResizeFilter.AREA, -1, null, null);
        assertEquals(Orientation.NORMAL, applied.outputOrientation());
        assertEquals(orientation, preserved.outputOrientation());
        var upright = applied.apply(stored);
        assertEquals(10, upright.width());
        assertEquals(10, upright.height());
        assertSamePixels(upright, ImageOps.orient(preserved.apply(stored), orientation));
    }

    @Test
    @DisplayName("passes pictures through untouched when nothing is requested")
    void identity() {
        var picture = YuvPicture.allocate(64, 48, ChromaFormat.YUV420, false, ColorInfo.BT709_LIMITED);
        var ops = PictureOps.plan(64, 48, Orientation.NORMAL, true, null,
                (width, height) -> SizePlan.of(width, height, 0, 0, ScaleMode.FIT, false, 0, 0, true), ResizeFilter.BILINEAR,
                0xFFFFFF, null, null);
        assertEquals(64, ops.outputWidth());
        assertEquals(48, ops.outputHeight());
        assertEquals(Orientation.NORMAL, ops.outputOrientation());
        assertSame(picture, ops.apply(picture));
    }

    @Test
    @DisplayName("flattens transparency and converts the pixel format")
    void pixelFormat() {
        var transparent = RgbaPicture.allocate(8, 8, true);
        var ops = PictureOps.plan(8, 8, Orientation.NORMAL, true, null,
                (width, height) -> SizePlan.of(width, height, 0, 0, ScaleMode.FIT, false, 0, 0, false), ResizeFilter.BILINEAR,
                0x00FF00, ChromaFormat.YUV444, ColorInfo.BT601_LIMITED);
        var result = ops.apply(transparent);
        var yuv = assertInstanceOf(YuvPicture.class, result);
        assertEquals(ChromaFormat.YUV444, yuv.chromaFormat());
        assertEquals(ColorInfo.BT601_LIMITED, yuv.color());
        var rgba = ImageOps.toRgba(yuv);
        assertTrue((rgba.pixels()[1] & 0xFF) > 240, "green background");
        assertTrue((rgba.pixels()[0] & 0xFF) < 16, "no red");
    }

    @Test
    @DisplayName("rejects a crop outside the displayed picture")
    void outside() {
        assertThrows(IllegalArgumentException.class, () -> PictureOps.plan(40, 30, Orientation.ROTATE_90, true,
                new Region(0, 0, 40, 30), (width, height) -> SizePlan.of(width, height, 0, 0, ScaleMode.FIT, false, 0, 0, false),
                ResizeFilter.BILINEAR, -1, null, null));
    }

    static PictureOps.SizePlanner box(int width, int height, ScaleMode mode) {
        return (displayWidth, displayHeight) -> SizePlan.of(displayWidth, displayHeight, width, height, mode, false, 0, 0,
                false);
    }

    /**
     * Returns the displayed aspect ratio of a coded picture under a sample aspect ratio and an
     * orientation.
     */
    static double displayAspect(int codedWidth, int codedHeight, Rational sampleAspect, Orientation orientation) {
        var aspect = sampleAspect == null ? 1.0 : sampleAspect.toDouble();
        var width = codedWidth * aspect;
        double height = codedHeight;
        return orientation.swapsDimensions() ? height / width : width / height;
    }

    @ParameterizedTest(name = "{0} {1} kept {2}")
    @CsvSource({"NORMAL, FIT, 0, 0, 176, 144, 16/11", "NORMAL, EXACT, 88, 144, 88, 144, 32/11",
            "NORMAL, FIT, 88, 72, 88, 72, 16/11", "ROTATE_90, EXACT, 72, 176, 176, 72, 8/11",
            "ROTATE_90, FIT, 0, 0, 176, 144, 16/11", "NORMAL, FILL, 100, 100, 100, 100, 800/549"})
    @DisplayName("keeping non-square samples declares the ratio that keeps the displayed shape")
    void preserveAspect(Orientation orientation, ScaleMode mode, int boxWidth, int boxHeight, int width, int height,
                        String expected) {
        var sar = new Rational(16, 11);
        var ops = PictureOps.plan(176, 144, orientation, false, null, box(boxWidth, boxHeight, mode),
                ResizeFilter.BILINEAR, -1, null, null, sar, false);
        assertEquals(width, ops.outputWidth());
        assertEquals(height, ops.outputHeight());
        var parts = expected.split("/");
        var aspect = new Rational(Long.parseLong(parts[0]), Long.parseLong(parts[1]));
        assertEquals(aspect, ops.outputSampleAspect());
        if (mode != ScaleMode.FILL) {
            assertEquals(displayAspect(176, 144, sar, orientation), displayAspect(width, height, aspect, orientation), 1e-9);
        }
    }

    @ParameterizedTest(name = "{0} {1} applied {2}x{3}")
    @CsvSource({"NORMAL, FIT, 0, 0, 256, 144", "NORMAL, FIT, 128, 128, 128, 72", "ROTATE_90, FIT, 0, 0, 176, 99",
            "ROTATE_90, FIT, 128, 128, 128, 72"})
    @DisplayName("applying non-square samples resamples to square pixels before the box and declares none")
    void applyAspect(Orientation orientation, ScaleMode mode, int boxWidth, int boxHeight, int width, int height) {
        var ops = PictureOps.plan(176, 144, orientation, false, null, box(boxWidth, boxHeight, mode),
                ResizeFilter.BILINEAR, -1, null, null, new Rational(16, 11), true);
        assertEquals(width, ops.outputWidth());
        assertEquals(height, ops.outputHeight());
        assertNull(ops.outputSampleAspect());
        var result = ops.apply(pattern(176, 144));
        assertEquals(width, result.width());
        assertEquals(height, result.height());
    }

    @Test
    @DisplayName("applying stretches the pixels as a plain resize to the display size does")
    void appliedPixels() {
        var stored = pattern(40, 30);
        var ops = PictureOps.plan(40, 30, Orientation.NORMAL, true, null, box(0, 0, ScaleMode.FIT), ResizeFilter.AREA, -1,
                null, null, new Rational(3, 2), true);
        assertSamePixels(ImageOps.resize(stored, 60, 30, ResizeFilter.AREA), ops.apply(stored));
    }

    @Test
    @DisplayName("declares nothing for a stream without a ratio, and square samples change nothing")
    void noAspect() {
        var plain = PictureOps.plan(64, 48, Orientation.NORMAL, true, null, box(32, 32, ScaleMode.EXACT),
                ResizeFilter.BILINEAR, -1, null, null, null, true);
        assertNull(plain.outputSampleAspect());
        assertEquals(32, plain.outputWidth());
        var square = PictureOps.plan(64, 48, Orientation.NORMAL, true, null, box(0, 0, ScaleMode.FIT),
                ResizeFilter.BILINEAR, -1, null, null, new Rational(1, 1), true);
        assertEquals(64, square.outputWidth());
        assertNull(square.outputSampleAspect());
        var kept = PictureOps.plan(64, 48, Orientation.NORMAL, true, null, box(0, 0, ScaleMode.FIT),
                ResizeFilter.BILINEAR, -1, null, null, new Rational(1, 1), false);
        assertEquals(new Rational(1, 1), kept.outputSampleAspect());
    }

    @ParameterizedTest(name = "{0} {1}x{2}")
    @CsvSource({"EXACT, 32, 32", "FIT, 47, 0", "FILL, 40, 20"})
    @DisplayName("keeping square samples declares them square whatever the scaling")
    void squareKept(ScaleMode mode, int boxWidth, int boxHeight) {
        var ops = PictureOps.plan(64, 48, Orientation.ROTATE_90, false, null, (width, height) -> SizePlan.of(width,
                height, boxWidth, boxHeight, mode, false, 0, 0, true), ResizeFilter.BILINEAR, -1, null, null,
                new Rational(1, 1), false);
        assertEquals(new Rational(1, 1), ops.outputSampleAspect());
    }

    @Test
    @DisplayName("crops in stored samples before stretching")
    void cropThenStretch() {
        var ops = PictureOps.plan(176, 144, Orientation.NORMAL, true, new Region(0, 0, 88, 144), box(0, 0, ScaleMode.FIT),
                ResizeFilter.BILINEAR, -1, null, null, new Rational(16, 11), true);
        assertEquals(128, ops.outputWidth());
        assertEquals(144, ops.outputHeight());
    }
}
