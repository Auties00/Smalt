package com.github.auties00.smalt.image;

import com.github.auties00.smalt.image.color.ColorProfile;
import com.github.auties00.smalt.image.ops.ImageOps;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("PixelDensity")
class PixelDensityTest {
    @Test
    @DisplayName("builds each unit through its factory and compares by value")
    void factories() {
        assertEquals(new PixelDensity(PixelDensity.Unit.DOTS_PER_INCH, 300, 300), PixelDensity.ofDotsPerInch(300));
        assertEquals(new PixelDensity(PixelDensity.Unit.DOTS_PER_INCH, 300, 150),
                PixelDensity.ofDotsPerInch(300, 150));
        assertEquals(PixelDensity.Unit.DOTS_PER_CENTIMETER, PixelDensity.ofDotsPerCentimeter(1, 2).unit());
        assertEquals(PixelDensity.Unit.DOTS_PER_METER, PixelDensity.ofDotsPerMeter(1, 2).unit());
        assertEquals(PixelDensity.Unit.ASPECT_RATIO, PixelDensity.ofAspectRatio(1, 2).unit());
        assertNotEquals(PixelDensity.ofDotsPerInch(72), PixelDensity.ofDotsPerCentimeter(72, 72));
        assertTrue(PixelDensity.ofDotsPerMeter(1, 1).isPhysical());
        assertFalse(PixelDensity.ofAspectRatio(1, 1).isPhysical());
    }

    @ParameterizedTest(name = "{0} x {1}")
    @CsvSource({"0, 1", "1, 0", "-1, 1", "1, -0.5", "NaN, 1", "1, NaN", "Infinity, 1", "1, Infinity"})
    @DisplayName("rejects a value that is not positive and finite")
    void invalid(double horizontal, double vertical) {
        assertThrows(IllegalArgumentException.class, () -> PixelDensity.ofDotsPerInch(horizontal, vertical));
        assertThrows(IllegalArgumentException.class, () -> PixelDensity.ofAspectRatio(horizontal, vertical));
    }

    @Test
    @DisplayName("rejects a null unit")
    void nullUnit() {
        assertThrows(NullPointerException.class, () -> new PixelDensity(null, 1, 1));
        assertThrows(NullPointerException.class, () -> PixelDensity.ofDotsPerInch(1).to(null));
    }

    @Test
    @DisplayName("converts between the physical units with an inch of exactly 2.54 centimeters")
    void conversions() {
        var dpi = PixelDensity.ofDotsPerInch(300, 72);
        var perCentimeter = dpi.to(PixelDensity.Unit.DOTS_PER_CENTIMETER);
        assertEquals(300 / 2.54, perCentimeter.horizontal(), 1e-12);
        assertEquals(72 / 2.54, perCentimeter.vertical(), 1e-12);
        var perMeter = dpi.to(PixelDensity.Unit.DOTS_PER_METER);
        assertEquals(1_500_000.0 / 127, perMeter.horizontal());
        assertEquals(2834.645669291339, perMeter.vertical(), 1e-9);
        assertEquals(300, perMeter.to(PixelDensity.Unit.DOTS_PER_INCH).horizontal(), 1e-9);
        assertEquals(72, perCentimeter.to(PixelDensity.Unit.DOTS_PER_INCH).vertical(), 1e-9);
        assertEquals(PixelDensity.ofDotsPerMeter(4000, 100), PixelDensity.ofDotsPerCentimeter(40, 1)
                .to(PixelDensity.Unit.DOTS_PER_METER));
        assertSame(dpi, dpi.to(PixelDensity.Unit.DOTS_PER_INCH));
        assertEquals(PixelDensity.ofAspectRatio(300, 72), dpi.to(PixelDensity.Unit.ASPECT_RATIO));
    }

    @ParameterizedTest
    @EnumSource(value = PixelDensity.Unit.class, names = "ASPECT_RATIO", mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("refuses to give an aspect ratio a physical unit")
    void aspectRatioHasNoSize(PixelDensity.Unit unit) {
        var aspect = PixelDensity.ofAspectRatio(1, 2);
        assertThrows(IllegalArgumentException.class, () -> aspect.to(unit));
        assertSame(aspect, aspect.to(PixelDensity.Unit.ASPECT_RATIO));
    }

    @Test
    @DisplayName("reports the pixel aspect ratio as the pixel width over its height")
    void pixelAspectRatio() {
        assertEquals(2, PixelDensity.ofAspectRatio(1, 2).pixelAspectRatio());
        assertEquals(0.5, PixelDensity.ofDotsPerInch(300, 150).pixelAspectRatio());
        assertEquals(1, PixelDensity.ofDotsPerMeter(2835, 2835).pixelAspectRatio());
    }

    @Test
    @DisplayName("is kept by the sRGB conversion of a decoded image")
    void keptByToSrgb() {
        var picture = RgbaPicture.allocate(2, 2, false);
        var density = Optional.of(PixelDensity.ofDotsPerCentimeter(40, 41));
        var image = new DecodedImage(picture, ImageFormat.PNG, Orientation.NORMAL, 2, 2, Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.of(ColorProfile.adobeRgb()), density);
        var converted = ImageOps.toSrgb(image);
        assertNotEquals(image, converted);
        assertEquals(density, converted.density());
    }
}
