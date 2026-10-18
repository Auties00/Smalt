package com.github.auties00.smalt.image.ops;

import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks compositing against a double-precision evaluation of the W3C formulas, and its
 * documented special cases: opacity, transparency, clipping, linear light, 16-bit and watermark
 * placement.
 */
@DisplayName("compositing")
class CompositingTest {
    private static RgbaPicture solid(int width, int height, int argb, boolean alpha) {
        var picture = RgbaPicture.allocate(width, height, alpha);
        for (var i = 0; i < width * height; i++) {
            picture.pixels()[4 * i] = (byte) (argb >>> 16);
            picture.pixels()[4 * i + 1] = (byte) (argb >>> 8);
            picture.pixels()[4 * i + 2] = (byte) argb;
            picture.pixels()[4 * i + 3] = (byte) (argb >>> 24);
        }
        return picture;
    }

    private static double reference(BlendMode mode, double cb, double cs) {
        return switch (mode) {
            case NORMAL -> cs;
            case MULTIPLY -> cb * cs;
            case SCREEN -> cb + cs - cb * cs;
            case OVERLAY -> cb <= 0.5 ? cs * 2 * cb : cs + (2 * cb - 1) - cs * (2 * cb - 1);
            case DARKEN -> Math.min(cb, cs);
            case LIGHTEN -> Math.max(cb, cs);
            case COLOR_DODGE -> cb == 0 ? 0 : cs == 1 ? 1 : Math.min(1, cb / (1 - cs));
            case COLOR_BURN -> cb == 1 ? 1 : cs == 0 ? 0 : 1 - Math.min(1, (1 - cb) / cs);
            case HARD_LIGHT -> cs <= 0.5 ? cb * 2 * cs : cb + (2 * cs - 1) - cb * (2 * cs - 1);
            case SOFT_LIGHT -> {
                if (cs <= 0.5) {
                    yield cb - (1 - 2 * cs) * cb * (1 - cb);
                }
                var d = cb <= 0.25 ? ((16 * cb - 12) * cb + 4) * cb : Math.sqrt(cb);
                yield cb + (2 * cs - 1) * (d - cb);
            }
            case DIFFERENCE -> Math.abs(cb - cs);
            case EXCLUSION -> cb + cs - 2 * cb * cs;
            case ADD -> Math.min(1, cb + cs);
            case SUBTRACT -> Math.max(0, cb - cs);
        };
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(BlendMode.class)
    @DisplayName("matches the W3C formulas within one step")
    void formulas(BlendMode mode) {
        var random = new SplittableRandom(mode.ordinal() + 11);
        var base = Pictures.randomRgba(random, 23, 9, true);
        var overlay = Pictures.randomRgba(random, 23, 9, true);
        var opacity = 0.8;
        var result = (RgbaPicture) ImageCompositing.composite(base, overlay, 0, 0, CompositeOptions.builder().mode(mode).opacity(opacity).build());
        for (var y = 0; y < 9; y++) {
            for (var x = 0; x < 23; x++) {
                var b = y * base.stride() + 4 * x;
                var s = y * overlay.stride() + 4 * x;
                var ab = (base.pixels()[b + 3] & 0xFF) / 255.0;
                var as = (overlay.pixels()[s + 3] & 0xFF) / 255.0 * opacity;
                var ao = as + ab * (1 - as);
                var o = y * result.stride() + 4 * x;
                assertEquals(ao * 255, result.pixels()[o + 3] & 0xFF, 0.51);
                if (ao * 255 < 8) {
                    continue;
                }
                for (var c = 0; c < 3; c++) {
                    var cb = (base.pixels()[b + c] & 0xFF) / 255.0;
                    var cs = (overlay.pixels()[s + c] & 0xFF) / 255.0;
                    var mixed = (1 - ab) * cs + ab * reference(mode, cb, cs);
                    var expected = (as * mixed + ab * (1 - as) * cb) / ao * 255;
                    // the color is rounded with alpha rounded too, so low alphas amplify float rounding
                    assertEquals(expected, result.pixels()[o + c] & 0xFF, 0.51 + 64 / (ao * 255), mode + " at " + x + "," + y);
                }
            }
        }
    }

    @Nested
    @DisplayName("special cases")
    class Cases {
        @Test
        @DisplayName("an opaque overlay at full opacity replaces the backdrop exactly")
        void opaqueOverlay() {
            var base = Pictures.randomRgba(new SplittableRandom(1), 10, 10, false);
            var overlay = Pictures.randomRgba(new SplittableRandom(2), 4, 3, false);
            var result = (RgbaPicture) ImageCompositing.composite(base, overlay, 5, 6);
            assertFalse(result.hasAlpha());
            for (var y = 0; y < 3; y++) {
                for (var x = 0; x < 4; x++) {
                    for (var c = 0; c < 4; c++) {
                        assertEquals(overlay.pixels()[y * overlay.stride() + 4 * x + c],
                                result.pixels()[(y + 6) * result.stride() + 4 * (x + 5) + c]);
                    }
                }
            }
            assertEquals(base.pixels()[0], result.pixels()[0]);
        }

        @Test
        @DisplayName("zero opacity, a transparent overlay or no overlap leave the backdrop unchanged")
        void unchanged() {
            var base = Pictures.randomRgba(new SplittableRandom(3), 12, 8, true);
            var expected = Pictures.tight(base);
            var overlay = solid(5, 5, 0xFF336699, true);
            assertArrayEquals(expected, Pictures.tight((RgbaPicture) ImageCompositing.composite(base, overlay, 2, 2,
                    CompositeOptions.builder().opacity(0).build())));
            assertArrayEquals(expected, Pictures.tight((RgbaPicture) ImageCompositing.composite(base, solid(5, 5, 0x00FF0000, true), 2, 2)));
            assertArrayEquals(expected, Pictures.tight((RgbaPicture) ImageCompositing.composite(base, overlay, -5, 0)));
            assertArrayEquals(expected, Pictures.tight((RgbaPicture) ImageCompositing.composite(base, overlay, 12, 0)));
        }

        @Test
        @DisplayName("keeps the overlay's color over a transparent backdrop")
        void straightColor() {
            var base = solid(4, 4, 0x00000000, true);
            var overlay = solid(4, 4, 0x80FF8000, true);
            var result = (RgbaPicture) ImageCompositing.composite(base, overlay, 0, 0);
            assertEquals(0xFF, result.pixels()[0] & 0xFF);
            assertEquals(0x80, result.pixels()[1] & 0xFF);
            assertEquals(0x80, result.pixels()[3] & 0xFF);
        }

        @Test
        @DisplayName("blends in linear light when asked")
        void linearLight() {
            var base = solid(2, 2, 0xFF000000, false);
            var overlay = solid(2, 2, 0x80FFFFFF, true);
            var gamma = (RgbaPicture) ImageCompositing.composite(base, overlay, 0, 0);
            var linear = (RgbaPicture) ImageCompositing.composite(base, overlay, 0, 0,
                    CompositeOptions.builder().linearLight(true).build());
            assertEquals(128, gamma.pixels()[0] & 0xFF);
            assertEquals(188, linear.pixels()[0] & 0xFF);
        }

        @Test
        @DisplayName("computes in 16 bits when either picture is 16-bit, and converts Y'CbCr")
        void depths() {
            var base = ImageOps.toYuv(Pictures.patternRgba(16, 16, false), ChromaFormat.YUV420, ColorInfo.JPEG);
            var deep = ImageOps.toSixteenBit(Pictures.patternRgba(8, 8, true));
            var result = ImageCompositing.composite(base, deep, 4, 4, CompositeOptions.of(BlendMode.MULTIPLY));
            assertInstanceOf(Rgba16Picture.class, result);
            assertEquals(16, result.width());
            assertFalse(result.hasAlpha());
            assertInstanceOf(RgbaPicture.class, ImageCompositing.composite(base, Pictures.patternRgba(8, 8, true), 0, 0));
        }
    }

    @Nested
    @DisplayName("watermark")
    class Watermark {
        @ParameterizedTest(name = "{0}")
        @EnumSource(Anchor.class)
        @DisplayName("places the mark at its anchor with the margins")
        void placement(Anchor anchor) {
            var base = solid(100, 60, 0xFF000000, false);
            var mark = solid(10, 6, 0xFFFFFFFF, false);
            var options = WatermarkOptions.builder().anchor(anchor).margins(5, 3).opacity(1).build();
            var result = (RgbaPicture) ImageCompositing.watermark(base, mark, options);
            var expectedX = anchor.x(100, 10, 5);
            var expectedY = anchor.y(60, 6, 3);
            var whites = 0;
            for (var y = 0; y < 60; y++) {
                for (var x = 0; x < 100; x++) {
                    var white = (result.pixels()[4 * (y * 100 + x)] & 0xFF) == 255;
                    var inside = x >= expectedX && x < expectedX + 10 && y >= expectedY && y < expectedY + 6;
                    assertEquals(inside, white, anchor + " at " + x + "," + y);
                    whites += white ? 1 : 0;
                }
            }
            assertEquals(60, whites);
        }

        @Test
        @DisplayName("scales the mark relative to the picture and applies the opacity")
        void scaleAndOpacity() {
            var base = solid(200, 100, 0xFF000000, false);
            var mark = solid(50, 10, 0xFFFFFFFF, false);
            var result = (RgbaPicture) ImageCompositing.watermark(base, mark,
                    WatermarkOptions.builder().anchor(Anchor.CENTER).scale(0.5).opacity(0.5).build());
            assertEquals(128, result.pixels()[4 * (50 * 200 + 100)] & 0xFF);
            assertEquals(0, result.pixels()[4 * (50 * 200 + 40)] & 0xFF);
            assertEquals(128, result.pixels()[4 * (50 * 200 + 55)] & 0xFF);
        }

        @Test
        @DisplayName("options validate their ranges")
        void options() {
            assertThrows(IllegalArgumentException.class, () -> CompositeOptions.builder().opacity(1.5).build());
            assertThrows(IllegalArgumentException.class, () -> WatermarkOptions.builder().scale(-0.1).build());
            assertThrows(IllegalArgumentException.class, () -> WatermarkOptions.builder().margins(-1, 0).build());
            var options = WatermarkOptions.builder().anchor(Anchor.TOP).opacity(0.3).build();
            assertEquals(options, options.toBuilder().build());
            assertEquals(0.3, options.composite().opacity());
            assertTrue(options.toString().contains("TOP"));
        }
    }
}
