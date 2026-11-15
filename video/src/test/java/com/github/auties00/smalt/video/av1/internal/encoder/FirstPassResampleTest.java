package com.github.auties00.smalt.video.av1.internal.encoder;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Covers the resampling of the first-pass luma across a size change of the good-quality lookahead. */
class FirstPassResampleTest {
    @Test
    @DisplayName("keeps a flat plane flat at every size")
    void flat() {
        var plane = new short[40 * 24];
        java.util.Arrays.fill(plane, (short) 117);
        for (var size : new int[][]{{20, 12}, {80, 48}, {33, 7}, {1, 1}, {40, 24}}) {
            var out = FirstPass.resample(plane, 40, 24, size[0], size[1]);
            assertEquals(size[0] * size[1], out.length);
            for (var v : out) {
                assertEquals(117, v);
            }
        }
    }

    @Test
    @DisplayName("halves a horizontal ramp to the averages of sample pairs, and returns the same plane at its size")
    void ramp() {
        var width = 16;
        var plane = new short[width * 2];
        for (var y = 0; y < 2; y++) {
            for (var x = 0; x < width; x++) {
                plane[y * width + x] = (short) (x * 8);
            }
        }
        var half = FirstPass.resample(plane, width, 2, width / 2, 1);
        for (var x = 0; x < width / 2; x++) {
            // the center of output sample x lies between input samples 2x and 2x + 1
            assertEquals(2 * x * 8 + 4, half[x], "sample " + x);
        }
        assertArrayEquals(plane, FirstPass.resample(plane, width, 2, width, 2));
    }

    @Test
    @DisplayName("measures a scaled copy of a picture as well predicted from the resampled previous one")
    void statistics() {
        var w = 128;
        var h = 96;
        var big = new short[w * h];
        for (var y = 0; y < h; y++) {
            for (var x = 0; x < w; x++) {
                big[y * w + x] = (short) (128 + 60 * Math.sin(x / 7.0) * Math.cos(y / 9.0));
            }
        }
        var small = FirstPass.resample(big, w, h, 96, 64);
        var previous = FirstPass.resample(big, w, h, 96, 64);
        var stats = FirstPass.measure(small, previous, null, 96, 64, 1);
        assertTrue(stats.pcntInter > 0.9, "inter share " + stats.pcntInter);
    }
}
