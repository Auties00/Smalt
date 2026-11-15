package com.github.auties00.smalt.video.mpeg4.internal;

/**
 * Holds a straightforward quarter sample interpolation written from ISO/IEC 14496-2 7.6.2.2, which
 * mirrors the block boundary at every tap and interpolates separably (every row to the horizontal
 * fraction, then every column of that result to the vertical fraction), as the reference the
 * optimized kernels must match sample for sample.
 */
final class ReferenceQuarterPel {
    private ReferenceQuarterPel() {
    }

    static void quarterPel(byte[] src, int srcOffset, int srcStride, byte[] dst, int dstOffset, int dstStride,
                           int width, int height, int quarterX, int quarterY, int rounding, int[] scratch) {
        var cols = width + 1;
        var rows = height + 1;
        var block = new int[rows][cols];
        for (var j = 0; j < rows; j++) {
            for (var i = 0; i < cols; i++) {
                block[j][i] = src[srcOffset + j * srcStride + i] & 0xFF;
            }
        }
        // Horizontal pass over all rows + 1 rows, so the vertical pass has the row below the block.
        var rowsDone = new int[rows][width];
        for (var j = 0; j < rows; j++) {
            for (var i = 0; i < width; i++) {
                rowsDone[j][i] = interpolate(block[j], cols, i, quarterX, rounding);
            }
        }
        var column = new int[rows];
        for (var i = 0; i < width; i++) {
            for (var j = 0; j < rows; j++) {
                column[j] = rowsDone[j][i];
            }
            for (var j = 0; j < height; j++) {
                dst[dstOffset + j * dstStride + i] = (byte) interpolate(column, rows, j, quarterY, rounding);
            }
        }
    }

    /**
     * Returns the sample at quarter position {@code 4 * i + fraction} of a line of {@code length}
     * samples mirrored at both ends.
     */
    private static int interpolate(int[] line, int length, int i, int fraction, int rounding) {
        if (fraction == 0) {
            return line[i];
        }
        int[] taps = {160, -48, 24, -8};
        var sum = 0;
        for (var k = 0; k < 4; k++) {
            sum += taps[k] * (at(line, length, i - k) + at(line, length, i + 1 + k));
        }
        var half = clip(Math.floorDiv(sum + 128 - rounding, 256));
        return switch (fraction) {
            case 1 -> (line[i] + half + 1 - rounding) / 2;
            case 2 -> half;
            default -> (line[i + 1] + half + 1 - rounding) / 2;
        };
    }

    private static int at(int[] line, int length, int i) {
        return line[i < 0 ? -1 - i : i >= length ? 2 * length - 1 - i : i];
    }

    private static int clip(int value) {
        return value < 0 ? 0 : value > 255 ? 255 : value;
    }
}
