package com.github.auties00.smalt.image.jxl.internal.color;

import com.github.auties00.smalt.image.jxl.internal.header.ColorEncoding;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Checks the transfer functions of the JPEG XL color conversion: the rational approximation of
 * the sRGB curve against the exact curve, and the handling of negative values.
 */
class ColorConverterTest {
    @Test
    @DisplayName("the sRGB approximation stays within 1e-6 of the exact curve")
    void srgb() {
        for (var i = 0; i <= 100_000; i++) {
            var linear = i / 100_000.0;
            var exact = linear <= 0.0031308 ? 12.92 * linear : 1.055 * Math.pow(linear, 1 / 2.4) - 0.055;
            assertEquals(exact, ColorConverter.srgbFromLinear((float) linear), 1e-6, "at " + linear);
        }
    }

    @Test
    @DisplayName("the fast power of the transfer curves stays within 3e-6 relative error of the exact power")
    void fastPow() {
        for (var exponent : new float[]{0.45f, 1 / 2.6f, 1 / 2.2f}) {
            for (var i = 1; i <= 20_000; i++) {
                var base = i / 10_000.0f;
                var exact = Math.pow(base, exponent);
                assertEquals(exact, ColorConverter.fastPow(base, exponent), exact * 3e-6, base + "^" + exponent);
            }
        }
    }

    @Test
    @DisplayName("the vector sRGB kernel matches the scalar kernel bit for bit")
    void kernels() {
        var random = new java.util.Random(11);
        for (var length : new int[]{0, 1, 7, 8, 9, 63, 1000}) {
            var samples = new float[length];
            for (var i = 0; i < length; i++) {
                samples[i] = switch (random.nextInt(12)) {
                    case 0 -> -0.0f;
                    case 1 -> Float.NaN;
                    case 2 -> 0.0031308f;
                    case 3 -> 7.5f;
                    default -> random.nextFloat() * 2.2f - 0.6f;
                };
            }
            var expected = samples.clone();
            var actual = samples.clone();
            new ScalarSrgbKernel().encode(expected, 0, length);
            new VectorSrgbKernel().encode(actual, 0, length);
            for (var i = 0; i < length; i++) {
                assertEquals(Float.floatToIntBits(expected[i]), Float.floatToIntBits(actual[i]), "sample " + i);
            }
        }
    }

    @Test
    @DisplayName("mirrors negative values through the sRGB curve")
    void negative() {
        var srgb = ColorEncoding.SRGB;
        assertEquals(-ColorConverter.fromLinear(0.25f, srgb, 255), ColorConverter.fromLinear(-0.25f, srgb, 255));
        assertEquals(0.0f, ColorConverter.fromLinear(0.0f, srgb, 255));
    }
}
