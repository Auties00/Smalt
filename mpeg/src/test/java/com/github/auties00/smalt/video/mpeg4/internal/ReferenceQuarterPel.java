package com.github.auties00.smalt.video.mpeg4.internal;

/**
 * Holds the first, straightforward quarter sample interpolation, which computes the half sample
 * planes with explicit block boundary mirroring at every tap, as the reference the optimized
 * kernels must match sample for sample.
 */
final class ReferenceQuarterPel {
    private ReferenceQuarterPel() {
    }

    static void quarterPel(byte[] src, int srcOffset, int srcStride, byte[] dst, int dstOffset, int dstStride,
                           int width, int height, int quarterX, int quarterY, int rounding, int[] scratch) {
        if ((quarterX | quarterY) == 0) {
            for (var row = 0; row < height; row++) {
                System.arraycopy(src, srcOffset + row * srcStride, dst, dstOffset + row * dstStride, width);
            }
            return;
        }
        // Plane 0 holds the integer samples, plane 1 the horizontal halves, plane 2 the vertical
        // halves and plane 3 the center halves, each (width + 1) by (height + 1), row stride 24.
        var plane = 24 * 24;
        var cols = width + 1;
        var rows = height + 1;
        var round = 16 - rounding;
        for (var j = 0; j < rows; j++) {
            var s = srcOffset + j * srcStride;
            var o = j * 24;
            for (var i = 0; i < cols; i++) {
                scratch[o + i] = src[s + i] & 0xFF;
            }
        }
        var needH = quarterX != 0;
        var needV = quarterY != 0;
        if (needH) {
            for (var j = 0; j < rows; j++) {
                var o = j * 24;
                for (var i = 0; i < width; i++) {
                    var sum = 20 * (at(scratch, o, i, cols) + at(scratch, o, i + 1, cols))
                            - 6 * (at(scratch, o, i - 1, cols) + at(scratch, o, i + 2, cols))
                            + 3 * (at(scratch, o, i - 2, cols) + at(scratch, o, i + 3, cols))
                            - (at(scratch, o, i - 3, cols) + at(scratch, o, i + 4, cols));
                    scratch[plane + o + i] = clip((sum + round) >> 5);
                }
            }
        }
        if (needV) {
            for (var i = 0; i < cols; i++) {
                for (var j = 0; j < height; j++) {
                    var sum = 20 * (column(scratch, 0, i, j, rows) + column(scratch, 0, i, j + 1, rows))
                            - 6 * (column(scratch, 0, i, j - 1, rows) + column(scratch, 0, i, j + 2, rows))
                            + 3 * (column(scratch, 0, i, j - 2, rows) + column(scratch, 0, i, j + 3, rows))
                            - (column(scratch, 0, i, j - 3, rows) + column(scratch, 0, i, j + 4, rows));
                    scratch[2 * plane + j * 24 + i] = clip((sum + round) >> 5);
                }
            }
            if (needH) {
                for (var i = 0; i < width; i++) {
                    for (var j = 0; j < height; j++) {
                        var sum = 20 * (column(scratch, plane, i, j, rows) + column(scratch, plane, i, j + 1, rows))
                                - 6 * (column(scratch, plane, i, j - 1, rows) + column(scratch, plane, i, j + 2, rows))
                                + 3 * (column(scratch, plane, i, j - 2, rows) + column(scratch, plane, i, j + 3, rows))
                                - (column(scratch, plane, i, j - 3, rows) + column(scratch, plane, i, j + 4, rows));
                        scratch[3 * plane + j * 24 + i] = clip((sum + round) >> 5);
                    }
                }
            }
        }
        var fx = quarterX & 1;
        var fy = quarterY & 1;
        var hx = quarterX >> 1;
        var hy = quarterY >> 1;
        for (var j = 0; j < height; j++) {
            var d = dstOffset + j * dstStride;
            for (var i = 0; i < width; i++) {
                var a = grid(scratch, plane, 2 * i + hx, 2 * j + hy);
                int value;
                if (fx == 0 && fy == 0) {
                    value = a;
                } else if (fy == 0) {
                    value = (a + grid(scratch, plane, 2 * i + hx + 1, 2 * j + hy) + 1 - rounding) >> 1;
                } else if (fx == 0) {
                    value = (a + grid(scratch, plane, 2 * i + hx, 2 * j + hy + 1) + 1 - rounding) >> 1;
                } else {
                    value = (a + grid(scratch, plane, 2 * i + hx + 1, 2 * j + hy)
                            + grid(scratch, plane, 2 * i + hx, 2 * j + hy + 1)
                            + grid(scratch, plane, 2 * i + hx + 1, 2 * j + hy + 1) + 2 - rounding) >> 2;
                }
                dst[d + i] = (byte) value;
            }
        }
    }

    private static int grid(int[] scratch, int plane, int x, int y) {
        return scratch[((x & 1) + 2 * (y & 1)) * plane + (y >> 1) * 24 + (x >> 1)];
    }

    private static int at(int[] scratch, int row, int i, int cols) {
        var index = i < 0 ? -1 - i : i >= cols ? 2 * cols - 1 - i : i;
        return scratch[row + index];
    }

    private static int column(int[] scratch, int base, int i, int j, int rows) {
        var index = j < 0 ? -1 - j : j >= rows ? 2 * rows - 1 - j : j;
        return scratch[base + index * 24 + i];
    }

    private static int clip(int value) {
        return value < 0 ? 0 : value > 255 ? 255 : value;
    }
}
