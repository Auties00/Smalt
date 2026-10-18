package com.github.auties00.smalt.image.jxl.internal.render;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Checks that the Vector API edge-preserving filter kernel returns the scalar kernel's samples bit
 * for bit, on rows of every length around the vector width, with the taps of the three filter
 * steps, samples left unchanged, and extreme values.
 */
class EpfKernelTest {
    @ParameterizedTest(name = "width {0}, step {1}")
    @CsvSource({"1, 0", "7, 0", "8, 0", "9, 1", "15, 2", "16, 0", "33, 1", "64, 2", "200, 0", "203, 1"})
    @DisplayName("the vector kernel matches the scalar kernel bit for bit")
    void equivalence(int width, int step) {
        var random = new Random(width * 31L + step);
        var paddedWidth = width + 6;
        var planes = new float[3][];
        for (var c = 0; c < 3; c++) {
            planes[c] = new float[paddedWidth * 7];
            for (var i = 0; i < planes[c].length; i++) {
                planes[c][i] = switch (random.nextInt(20)) {
                    case 0 -> 0.0f;
                    case 1 -> -0.0f;
                    case 2 -> 1e30f;
                    default -> random.nextFloat() * 2 - 0.5f;
                };
            }
        }
        var taps = step == 0
                ? new int[]{-2 * paddedWidth, -paddedWidth - 1, -paddedWidth, -paddedWidth + 1, -2, -1, 1, 2,
                paddedWidth - 1, paddedWidth, paddedWidth + 1, 2 * paddedWidth}
                : new int[]{-paddedWidth, -1, 1, paddedWidth};
        var dist = step == 2 ? new int[]{0} : new int[]{-1, 1, 0, -paddedWidth, paddedWidth};
        var slopes = new float[width];
        for (var x = 0; x < width; x++) {
            slopes[x] = random.nextInt(6) == 0 ? Float.NaN : -0.1f - random.nextFloat() * 40;
        }
        var row = 3 * paddedWidth + 3;
        var expected = new float[3][width];
        var actual = new float[3][width];
        new ScalarEpfKernel().filterRow(planes[0], planes[1], planes[2], expected[0], expected[1], expected[2], 0, row,
                width, slopes, taps, dist, 0.38f, 1.0f, 0.71f, new float[EpfKernel.SCRATCH]);
        new VectorEpfKernel().filterRow(planes[0], planes[1], planes[2], actual[0], actual[1], actual[2], 0, row,
                width, slopes, taps, dist, 0.38f, 1.0f, 0.71f, new float[EpfKernel.SCRATCH]);
        for (var c = 0; c < 3; c++) {
            for (var x = 0; x < width; x++) {
                assertEquals(Float.floatToRawIntBits(expected[c][x]), Float.floatToRawIntBits(actual[c][x]),
                        "channel " + c + " sample " + x);
            }
        }
    }
}
