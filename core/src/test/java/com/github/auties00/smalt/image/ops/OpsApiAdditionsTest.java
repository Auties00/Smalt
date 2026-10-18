package com.github.auties00.smalt.image.ops;

import com.github.auties00.smalt.image.RgbaPicture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the names added next to the existing image operations API: the quantize preset and
 * accessor, the watermark blending settings, {@link Region#fitsIn(int, int)} and
 * {@link ImageOps#crop(com.github.auties00.smalt.image.Picture, Region)}.
 */
@DisplayName("Image operations API additions")
class OpsApiAdditionsTest {
    @Nested
    @DisplayName("QuantizeOptions")
    class Quantize {
        @Test
        @DisplayName("ofColors equals the builder's colors setting and colors() reads it")
        void ofColors() {
            assertEquals(QuantizeOptions.builder().colors(16).build(), QuantizeOptions.ofColors(16));
            assertEquals(16, QuantizeOptions.ofColors(16).colors());
            assertEquals(256, QuantizeOptions.defaults().colors());
            assertThrows(IllegalArgumentException.class, () -> QuantizeOptions.ofColors(1));
            assertThrows(IllegalArgumentException.class, () -> QuantizeOptions.ofColors(257));
        }
    }

    @Nested
    @DisplayName("WatermarkOptions")
    class Watermark {
        @Test
        @DisplayName("mode and linearLight read the blending")
        void accessors() {
            var defaults = WatermarkOptions.defaults();
            assertEquals(BlendMode.NORMAL, defaults.mode());
            assertFalse(defaults.linearLight());
            var custom = WatermarkOptions.builder().mode(BlendMode.MULTIPLY).linearLight(true).build();
            assertEquals(BlendMode.MULTIPLY, custom.mode());
            assertTrue(custom.linearLight());
        }

        @Test
        @DisplayName("Builder.mode and linearLight keep the opacity whatever the setter order")
        void orderIndependent() {
            var first = WatermarkOptions.builder().opacity(0.25).mode(BlendMode.SCREEN).linearLight(true).build();
            var second = WatermarkOptions.builder().linearLight(true).mode(BlendMode.SCREEN).opacity(0.25).build();
            assertEquals(first, second);
            assertEquals(0.25, first.opacity());
            assertEquals(0.25, first.composite().opacity());
            assertEquals(BlendMode.SCREEN, first.mode());
            assertTrue(first.linearLight());
            assertThrows(NullPointerException.class, () -> WatermarkOptions.builder().mode(null));
        }
    }

    @Nested
    @DisplayName("Region")
    class Regions {
        @ParameterizedTest
        @CsvSource({
                "0, 0, 10, 10, 10, 10, true",
                "5, 5, 5, 5, 10, 10, true",
                "5, 5, 6, 5, 10, 10, false",
                "5, 5, 5, 6, 10, 10, false",
                "0, 0, 1, 1, 0, 0, false",
                "2147483647, 0, 1, 1, 2147483647, 1, false"
        })
        @DisplayName("fitsIn tests containment without overflow")
        void fitsIn(int x, int y, int width, int height, int pictureWidth, int pictureHeight, boolean fits) {
            assertEquals(fits, new Region(x, y, width, height).fitsIn(pictureWidth, pictureHeight));
        }
    }

    @Nested
    @DisplayName("ImageOps.crop(Picture, Region)")
    class Crop {
        @Test
        @DisplayName("crops exactly as the coordinate overload")
        void matchesCoordinates() {
            var picture = Pictures.patternRgba(17, 11, true);
            var byRegion = (RgbaPicture) ImageOps.crop(picture, new Region(3, 2, 9, 5));
            var byCoordinates = (RgbaPicture) ImageOps.crop(picture, 3, 2, 9, 5);
            assertEquals(byCoordinates.width(), byRegion.width());
            assertEquals(byCoordinates.height(), byRegion.height());
            assertArrayEquals(byCoordinates.pixels(), byRegion.pixels());
            assertSame(picture, ImageOps.crop(picture, new Region(0, 0, 17, 11)));
            assertThrows(IllegalArgumentException.class, () -> ImageOps.crop(picture, new Region(10, 0, 8, 1)));
        }
    }
}
