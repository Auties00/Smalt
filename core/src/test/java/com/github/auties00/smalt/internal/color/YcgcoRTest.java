package com.github.auties00.smalt.internal.color;

import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.image.MatrixCoefficients;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the integer YCgCo-R transform of ITU-T H.273 equations 58 to 65.
 */
class YcgcoRTest {
    @ParameterizedTest(name = "{0} at {1} bits")
    @CsvSource({"YCGCO_RE, 10", "YCGCO_RO, 9", "YCGCO_RE, 8", "YCGCO_RO, 12", "YCGCO_RE, 16"})
    @DisplayName("round-trips every R'G'B' triple exactly and keeps the samples in range")
    void lossless(MatrixCoefficients matrix, int depth) {
        var rgbDepth = YcgcoR.rgbDepth(matrix, depth);
        var max = (1 << rgbDepth) - 1;
        var limit = (1 << depth) - 1;
        var step = Math.max(1, max / 255);
        var codes = new int[3];
        var rgb = new int[3];
        for (var r = 0; r <= max; r += step) {
            for (var g = 0; g <= max; g += step) {
                for (var b = 0; b <= max; b += step) {
                    YcgcoR.forward(r, g, b, depth, codes);
                    for (var code : codes) {
                        assertTrue(code >= 0 && code <= limit);
                    }
                    YcgcoR.inverse(codes[0], codes[1], codes[2], depth, rgbDepth, rgb);
                    if (rgb[0] != r || rgb[1] != g || rgb[2] != b) {
                        assertArrayEquals(new int[]{r, g, b}, rgb);
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("matches equations 58 to 61 worked by hand")
    void golden() {
        var codes = new int[3];
        // Co = 255 - 0 = 255, t = 0 + (255 >> 1) = 127, Cg = 0 - 127 = -127, Y = 127 + (-127 >> 1) = 63
        YcgcoR.forward(255, 0, 0, 10, codes);
        assertArrayEquals(new int[]{63, 512 - 127, 512 + 255}, codes);
        YcgcoR.forward(0, 0, 0, 10, codes);
        assertArrayEquals(new int[]{0, 512, 512}, codes);
        YcgcoR.forward(255, 255, 255, 10, codes);
        assertArrayEquals(new int[]{255, 512, 512}, codes);
        // Co = 10 - 200 = -190, t = 200 + (-95) = 105, Cg = 50 - 105 = -55, Y = 105 + (-28) = 77
        YcgcoR.forward(10, 50, 200, 9, codes);
        assertArrayEquals(new int[]{77, 256 - 55, 256 - 190}, codes);
    }

    @Test
    @DisplayName("quantizes R'G'B' signals as equations 27 to 29 and 33 to 35")
    void quantization() {
        assertEquals(10, YcgcoR.rgbDepth(MatrixCoefficients.YCGCO_RE, 12));
        assertEquals(11, YcgcoR.rgbDepth(MatrixCoefficients.YCGCO_RO, 12));
        assertEquals(255, YcgcoR.toSample(1, ColorRange.FULL, 8));
        assertEquals(128, YcgcoR.toSample(0.5, ColorRange.FULL, 8));
        assertEquals(940, YcgcoR.toSample(1, ColorRange.LIMITED, 10));
        assertEquals(64, YcgcoR.toSample(0, ColorRange.LIMITED, 10));
        assertEquals(0, YcgcoR.toSample(-0.2, ColorRange.LIMITED, 10));
        assertEquals(63, YcgcoR.toSample(2, ColorRange.FULL, 6));
        assertEquals(59, YcgcoR.toSample(1, ColorRange.LIMITED, 6));
        for (var range : ColorRange.values()) {
            for (var depth = 6; depth <= 14; depth++) {
                for (var code = 0; code < 1 << depth; code += 7) {
                    var signal = YcgcoR.toSignal(code, range, depth);
                    if (signal >= 0 && signal <= 1) {
                        assertEquals(code, YcgcoR.toSample(signal, range, depth));
                    }
                }
            }
        }
        assertTrue(YcgcoR.applies(MatrixCoefficients.YCGCO_RE));
        assertTrue(YcgcoR.applies(MatrixCoefficients.YCGCO_RO));
        assertFalse(YcgcoR.applies(MatrixCoefficients.YCGCO));
    }

    @Test
    @DisplayName("clips the inverse of out-of-range samples")
    void clipping() {
        var rgb = new int[3];
        YcgcoR.inverse(1023, 1023, 1023, 10, 8, rgb);
        for (var value : rgb) {
            assertTrue(value >= 0 && value <= 255);
        }
        YcgcoR.inverse(0, 0, 0, 10, 8, rgb);
        for (var value : rgb) {
            assertTrue(value >= 0 && value <= 255);
        }
    }
}
