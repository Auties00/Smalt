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

import java.util.SplittableRandom;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("tone and color adjustments")
class AdjustmentOpsTest {
    private static final RgbaPicture PICTURE = Pictures.randomRgba(new SplittableRandom(7), 29, 19, true);

    private static byte[] tight(Picture picture) {
        return Pictures.tight((RgbaPicture) picture);
    }

    @Nested
    @DisplayName("identities")
    class Identities {
        @Test
        @DisplayName("neutral parameters leave the picture unchanged")
        void neutral() {
            var expected = tight(PICTURE);
            assertArrayEquals(expected, tight(ImageAdjustments.brightness(PICTURE, 1)));
            assertArrayEquals(expected, tight(ImageAdjustments.contrast(PICTURE, 1)));
            assertArrayEquals(expected, tight(ImageAdjustments.saturation(PICTURE, 1)));
            assertArrayEquals(expected, tight(ImageAdjustments.gamma(PICTURE, 1)));
            assertArrayEquals(expected, tight(ImageAdjustments.levels(PICTURE, 0, 255, 1, 0, 255)));
            assertArrayEquals(expected, tight(ImageAdjustments.curves(PICTURE, ToneCurve.identity())));
            assertArrayEquals(expected, tight(ImageAdjustments.tint(PICTURE, 0xFFFFFF, 1)));
            assertArrayEquals(expected, tight(ImageAdjustments.tint(PICTURE, 0x123456, 0)));
            assertArrayEquals(expected, tight(ImageAdjustments.invert(ImageAdjustments.invert(PICTURE))));
        }

        @Test
        @DisplayName("16-bit inversion and curves are exact")
        void sixteenBit() {
            var deep = ImageOps.toSixteenBit(PICTURE);
            var twice = (Rgba16Picture) ImageAdjustments.invert(ImageAdjustments.invert(deep));
            assertArrayEquals(deep.pixels(), twice.pixels());
            var curved = (Rgba16Picture) ImageAdjustments.curves(deep, ToneCurve.identity());
            assertArrayEquals(deep.pixels(), curved.pixels());
        }
    }

    @Nested
    @DisplayName("grayscale")
    class Gray {
        @ParameterizedTest(name = "{0}")
        @EnumSource(GrayscaleMode.class)
        @DisplayName("gives equal channels, keeps grays and alpha")
        void channels(GrayscaleMode mode) {
            var gray = (RgbaPicture) ImageAdjustments.grayscale(PICTURE, mode);
            var source = tight(PICTURE);
            var out = tight(gray);
            for (var i = 0; i < out.length; i += 4) {
                assertEquals(out[i], out[i + 1]);
                assertEquals(out[i], out[i + 2]);
                assertEquals(source[i + 3], out[i + 3]);
            }
            var flat = RgbaPicture.allocate(1, 256, false);
            for (var v = 0; v < 256; v++) {
                flat.pixels()[4 * v] = (byte) v;
                flat.pixels()[4 * v + 1] = (byte) v;
                flat.pixels()[4 * v + 2] = (byte) v;
            }
            var mapped = (RgbaPicture) ImageAdjustments.grayscale(flat, mode);
            for (var v = 0; v < 256; v++) {
                assertEquals(v, mapped.pixels()[4 * v] & 0xFF, "gray " + v);
            }
        }

        @Test
        @DisplayName("luminance weighs green most and is brighter than gamma-encoded luma for saturated colors")
        void luminance() {
            var red = RgbaPicture.allocate(1, 1, false);
            red.pixels()[0] = (byte) 255;
            var linear = ((RgbaPicture) ImageAdjustments.grayscale(red, GrayscaleMode.LUMINANCE)).pixels()[0] & 0xFF;
            var luma = ((RgbaPicture) ImageAdjustments.grayscale(red, GrayscaleMode.BT709)).pixels()[0] & 0xFF;
            assertEquals(127, linear);
            assertEquals(54, luma);
        }
    }

    @Nested
    @DisplayName("tones")
    class Tones {
        @Test
        @DisplayName("levels clamp and stretch the input range")
        void levels() {
            var ramp = RgbaPicture.allocate(256, 1, false);
            for (var v = 0; v < 256; v++) {
                ramp.pixels()[4 * v] = (byte) v;
            }
            var stretched = (RgbaPicture) ImageAdjustments.levels(ramp, 50, 150, 1, 10, 210);
            assertEquals(10, stretched.pixels()[0] & 0xFF);
            assertEquals(10, stretched.pixels()[4 * 50] & 0xFF);
            assertEquals(110, stretched.pixels()[4 * 100] & 0xFF);
            assertEquals(210, stretched.pixels()[4 * 255] & 0xFF);
            assertThrows(IllegalArgumentException.class, () -> ImageAdjustments.levels(ramp, 100, 100, 1, 0, 255));
        }

        @Test
        @DisplayName("curves through points are monotone between increasing points")
        void curves() {
            var curve = ToneCurve.through(0, 0, 0.25, 0.4, 0.5, 0.5, 1, 1);
            var previous = -1.0;
            for (var i = 0; i <= 1000; i++) {
                var y = curve.apply(i / 1000.0);
                assertTrue(y >= previous);
                previous = y;
            }
            assertEquals(0.4, curve.apply(0.25), 1e-12);
            assertThrows(IllegalArgumentException.class, () -> ToneCurve.through(0, 0, 0, 1));
            assertThrows(IllegalArgumentException.class, () -> ToneCurve.through(0, 0, 1.5, 1));
        }

        @Test
        @DisplayName("threshold, sepia and colorize produce their documented values")
        void effects() {
            var pixel = RgbaPicture.allocate(1, 1, false);
            pixel.pixels()[0] = (byte) 200;
            pixel.pixels()[1] = (byte) 100;
            pixel.pixels()[2] = (byte) 50;
            var luma = (19595 * 200 + 38470 * 100 + 7471 * 50 + 32768) >> 16;
            assertEquals(255, ((RgbaPicture) ImageAdjustments.threshold(pixel, luma)).pixels()[0] & 0xFF);
            assertEquals(0, ((RgbaPicture) ImageAdjustments.threshold(pixel, luma + 1)).pixels()[0] & 0xFF);
            var sepia = (RgbaPicture) ImageAdjustments.sepia(pixel);
            assertEquals(Math.min(255, (int) Math.round(0.393 * 200 + 0.769 * 100 + 0.189 * 50)), sepia.pixels()[0] & 0xFF);
            assertEquals((int) Math.round(0.272 * 200 + 0.534 * 100 + 0.131 * 50), sepia.pixels()[2] & 0xFF, 1);
            var duotone = (RgbaPicture) ImageAdjustments.colorize(pixel, 0x000000, 0xFF0000);
            assertEquals(luma, duotone.pixels()[0] & 0xFF);
            assertEquals(0, duotone.pixels()[1]);
        }

        @Test
        @DisplayName("autoContrast keeps hue and autoLevels stretches each channel")
        void automatic() {
            var dull = (RgbaPicture) ImageAdjustments.levels(Pictures.patternRgba(64, 32, false), 0, 255, 1, 60, 180);
            var leveled = (RgbaPicture) ImageAdjustments.autoLevels(dull, 0);
            var min = 255;
            var max = 0;
            for (var i = 0; i < 64 * 32; i++) {
                min = Math.min(min, leveled.pixels()[4 * i] & 0xFF);
                max = Math.max(max, leveled.pixels()[4 * i] & 0xFF);
            }
            assertEquals(0, min);
            assertEquals(255, max);
            assertThrows(IllegalArgumentException.class, () -> ImageAdjustments.autoContrast(dull, 60));
        }
    }

    @Test
    @DisplayName("every adjustment converts Y'CbCr inputs and keeps 16-bit depth")
    void representations() {
        var yuv = ImageOps.toYuv(Pictures.patternRgba(18, 12, false), ChromaFormat.YUV420, ColorInfo.JPEG);
        var deep = ImageOps.toSixteenBit(Pictures.patternRgba(18, 12, true));
        UnaryOperator<Picture>[] operations = new UnaryOperator[]{
                p -> ImageAdjustments.brightness((Picture) p, 1.2),
                p -> ImageAdjustments.contrast((Picture) p, 0.7),
                p -> ImageAdjustments.saturation((Picture) p, 1.5),
                p -> ImageAdjustments.gamma((Picture) p, 2.2),
                p -> ImageAdjustments.levels((Picture) p, 10, 240, 0.8, 5, 250),
                p -> ImageAdjustments.autoLevels((Picture) p, 1),
                p -> ImageAdjustments.autoContrast((Picture) p, 1),
                p -> ImageAdjustments.grayscale((Picture) p, GrayscaleMode.LUMINANCE),
                p -> ImageAdjustments.sepia((Picture) p),
                p -> ImageAdjustments.colorize((Picture) p, 0x102030, 0xF0E0D0),
                p -> ImageAdjustments.tint((Picture) p, 0xFF8040, 0.5),
                p -> ImageAdjustments.invert((Picture) p),
                p -> ImageAdjustments.threshold((Picture) p, 128)};
        for (var operation : operations) {
            assertInstanceOf(RgbaPicture.class, operation.apply(yuv));
            var result = operation.apply(deep);
            assertInstanceOf(Rgba16Picture.class, result);
            var alpha = ((Rgba16Picture) result).pixels();
            for (var i = 3; i < alpha.length; i += 4) {
                assertEquals(deep.pixels()[i], alpha[i]);
            }
        }
    }
}
