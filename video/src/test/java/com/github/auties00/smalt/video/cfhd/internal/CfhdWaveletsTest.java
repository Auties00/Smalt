package com.github.auties00.smalt.video.cfhd.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Checks the inverse temporal wavelet against the arithmetic of GoPro's InvertTemporalQuant16s: its vector loop over
 * the largest multiple of 40 columns and its scalar tail differ in the phase of the 8-bit halftone and in saturation.
 */
@DisplayName("CineForm inverse wavelets")
class CfhdWaveletsTest {
    @Test
    @DisplayName("adds the 8-bit halftone in the phase of each loop of GoPro's temporal inverse")
    void halftone() {
        var width = 48;
        var height = 3;
        var low = new short[width * height];
        var high = new short[width * height];
        java.util.Arrays.fill(low, (short) 9);
        java.util.Arrays.fill(high, (short) 2);
        var first = new short[low.length];
        var second = new short[low.length];
        CfhdWavelets.temporal(low, high, width, height, 8, first, second);
        for (var row = 0; row < height; row++) {
            for (var column = 0; column < width; column++) {
                var even = (column + row & 1) == 0;
                // 9 + 2 = 11 halves to 5, and to 6 with the halftone
                var expected = column < 40 == even ? 6 : 5;
                assertEquals(expected, second[row * width + column], "second at " + column + "," + row);
                assertEquals(3, first[row * width + column], "first at " + column + "," + row);
            }
        }
        CfhdWavelets.temporal(low, high, width, height, 10, first, second);
        for (var value : second) {
            assertEquals(5, value);
        }
    }

    @Test
    @DisplayName("saturates the sums of the vector loop but not those of the scalar tail")
    void overflow() {
        var width = 41;
        var low = new short[width];
        var high = new short[width];
        java.util.Arrays.fill(low, Short.MAX_VALUE);
        java.util.Arrays.fill(high, (short) 1000);
        var first = new short[width];
        var second = new short[width];
        CfhdWavelets.temporal(low, high, width, 1, 10, first, second);
        assertEquals(Short.MAX_VALUE >> 1, second[0]);
        assertEquals((short) ((Short.MAX_VALUE + 1000) >> 1), second[40]);
    }
}
