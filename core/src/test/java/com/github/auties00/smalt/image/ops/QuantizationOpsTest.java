package com.github.auties00.smalt.image.ops;

import com.github.auties00.smalt.image.RgbaPicture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.HashSet;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("quantization")
class QuantizationOpsTest {
    @Test
    @DisplayName("is lossless when the colors fit the palette")
    void lossless() {
        var picture = RgbaPicture.allocate(16, 16, false);
        var random = new SplittableRandom(4);
        var colors = new int[40];
        for (var i = 0; i < colors.length; i++) {
            colors[i] = random.nextInt(1 << 24);
        }
        for (var i = 0; i < 256; i++) {
            var color = colors[random.nextInt(colors.length)];
            picture.pixels()[4 * i] = (byte) (color >>> 16);
            picture.pixels()[4 * i + 1] = (byte) (color >>> 8);
            picture.pixels()[4 * i + 2] = (byte) color;
            picture.pixels()[4 * i + 3] = (byte) 255;
        }
        var quantized = ImageQuantization.quantize(picture, QuantizeOptions.ofColors(64));
        assertTrue(quantized.paletteSize() <= 40);
        assertTrue(quantized.transparentIndex().isEmpty());
        assertArrayEquals(picture.pixels(), quantized.toRgba().pixels());
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Dithering.class)
    @DisplayName("reduces a photo-like picture to the requested palette size, for every dithering")
    void reduces(Dithering dithering) {
        var picture = Pictures.smoothRgba(64, 48, false);
        for (var method : QuantizeMethod.values()) {
            var options = QuantizeOptions.builder().colors(16).method(method).dithering(dithering).build();
            var quantized = ImageQuantization.quantize(picture, options);
            assertTrue(quantized.paletteSize() <= 16);
            var used = new HashSet<Integer>();
            for (var index : quantized.indices()) {
                used.add(index & 0xFF);
            }
            assertTrue(used.size() > 4);
            var restored = quantized.toRgba();
            double error = 0;
            for (var i = 0; i < picture.pixels().length; i++) {
                error += Math.abs((restored.pixels()[i] & 0xFF) - (picture.pixels()[i] & 0xFF));
            }
            assertTrue(error / picture.pixels().length < 20, method + "/" + dithering + " mean error " + error / picture.pixels().length);
            assertEquals(quantized, ImageQuantization.quantize(picture, options));
        }
    }

    @Test
    @DisplayName("gives transparent pixels their own entry by the alpha threshold")
    void transparency() {
        var picture = Pictures.patternRgba(20, 20, true);
        var quantized = ImageQuantization.quantize(picture, QuantizeOptions.builder().colors(8).alphaThreshold(100).build());
        var transparent = quantized.transparentIndex().orElseThrow();
        assertEquals(0, quantized.palette()[transparent]);
        for (var y = 0; y < 20; y++) {
            for (var x = 0; x < 20; x++) {
                var alpha = picture.pixels()[4 * (y * 20 + x) + 3] & 0xFF;
                assertEquals(alpha < 100, quantized.index(x, y) == transparent, "pixel " + x + "," + y);
            }
        }
        assertTrue(quantized.toRgba().hasAlpha());
    }

    @Test
    @DisplayName("handles fully transparent and 16-bit pictures and validates options")
    void edges() {
        var empty = RgbaPicture.allocate(3, 3, true);
        var quantized = ImageQuantization.quantize(empty);
        assertEquals(1, quantized.paletteSize());
        assertEquals(0, quantized.transparentIndex().orElseThrow());
        var deep = ImageQuantization.quantize(ImageOps.toSixteenBit(Pictures.patternRgba(8, 8, false)));
        assertFalse(deep.transparentIndex().isPresent());
        assertThrows(IllegalArgumentException.class, () -> QuantizeOptions.ofColors(1));
        assertThrows(IllegalArgumentException.class, () -> QuantizeOptions.ofColors(257));
        assertThrows(IllegalArgumentException.class, () -> QuantizeOptions.builder().alphaThreshold(0).build());
        assertThrows(IndexOutOfBoundsException.class, () -> deep.index(8, 0));
    }
}
