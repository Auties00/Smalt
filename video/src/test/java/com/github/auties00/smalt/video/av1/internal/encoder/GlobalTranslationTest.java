package com.github.auties00.smalt.video.av1.internal.encoder;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Checks that the dominant translation estimate recovers the shift between two copies of a
 * textured picture, on a reference with the padded border the encoder gives it.
 */
class GlobalTranslationTest {
    private static final int WIDTH = 320;

    private static final int HEIGHT = 192;

    private static final int BORDER = 160;

    private static double lattice(int y, int x) {
        var h = (y * 73856093) ^ (x * 19349663);
        h ^= h >>> 13;
        h *= 0x5bd1e995;
        h ^= h >>> 15;
        return (h & 0xFFFF) / 65535.0;
    }

    private static double value(int y, int x, int spacing) {
        var gy = Math.floorDiv(y, spacing);
        var gx = Math.floorDiv(x, spacing);
        var fy = (y - gy * spacing) / (double) spacing;
        var fx = (x - gx * spacing) / (double) spacing;
        fy = fy * fy * (3 - 2 * fy);
        fx = fx * fx * (3 - 2 * fx);
        var top = lattice(gy, gx) * (1 - fx) + lattice(gy, gx + 1) * fx;
        var bottom = lattice(gy + 1, gx) * (1 - fx) + lattice(gy + 1, gx + 1) * fx;
        return top * (1 - fy) + bottom * fy;
    }

    private static int texture(int y, int x) {
        var v = 255 * (0.5 * value(y, x, 96) + 0.3 * value(y, x, 40) + 0.2 * value(y, x, 16));
        return (int) Math.clamp(v + (int) (lattice(y * 7 + 3, x * 5 + 1) * 4) - 1, 0, 255);
    }

    @ParameterizedTest(name = "row {0} column {1}")
    @CsvSource({"0,0", "-9,23", "14,-31", "40,64", "-3,1"})
    @DisplayName("the estimate recovers the displacement between a picture and its reference")
    void recoversShift(int rows, int cols) {
        var stride = WIDTH + 2 * BORDER;
        var ref = new short[stride * (HEIGHT + 2 * BORDER)];
        for (var y = 0; y < HEIGHT + 2 * BORDER; y++) {
            for (var x = 0; x < stride; x++) {
                ref[y * stride + x] = (short) texture(y - BORDER, x - BORDER);
            }
        }
        var cur = new short[WIDTH * HEIGHT];
        for (var y = 0; y < HEIGHT; y++) {
            for (var x = 0; x < WIDTH; x++) {
                cur[y * WIDTH + x] = (short) texture(y + rows, x + cols);
            }
        }
        var origin = BORDER * stride + BORDER;
        var estimate = GlobalTranslation.estimate(new GlobalTranslation.Pyramid(cur, 0, WIDTH, WIDTH, HEIGHT),
                new GlobalTranslation.Pyramid(ref, origin, stride, WIDTH, HEIGHT), cur, 0, WIDTH, ref, origin, stride,
                WIDTH, HEIGHT, 120);
        assertEquals(rows, estimate >> 16, "rows");
        assertEquals(cols, (short) estimate, "columns");
    }
}
