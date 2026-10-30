package com.github.auties00.smalt.video.h263.internal;

/**
 * Forms the motion compensated predictions of ITU-T H.263: the bilinear half sample interpolation of 6.1.2 over a
 * region whose edges extend outward (D.1), and the overlapped block motion compensation of F.3 and Q.5.
 */
final class Prediction {
    /**
     * The weights of the current block's vector (Figure F.2).
     */
    private static final int[] H0 = {
            4, 5, 5, 5, 5, 5, 5, 4,
            5, 5, 5, 5, 5, 5, 5, 5,
            5, 5, 6, 6, 6, 6, 5, 5,
            5, 5, 6, 6, 6, 6, 5, 5,
            5, 5, 6, 6, 6, 6, 5, 5,
            5, 5, 6, 6, 6, 6, 5, 5,
            5, 5, 5, 5, 5, 5, 5, 5,
            4, 5, 5, 5, 5, 5, 5, 4
    };

    /**
     * The weights of the vector of the block above or below (Figure F.3).
     */
    private static final int[] H1 = {
            2, 2, 2, 2, 2, 2, 2, 2,
            1, 1, 2, 2, 2, 2, 1, 1,
            1, 1, 1, 1, 1, 1, 1, 1,
            1, 1, 1, 1, 1, 1, 1, 1,
            1, 1, 1, 1, 1, 1, 1, 1,
            1, 1, 1, 1, 1, 1, 1, 1,
            1, 1, 2, 2, 2, 2, 1, 1,
            2, 2, 2, 2, 2, 2, 2, 2
    };

    /**
     * The weights of the vector of the block left or right (Figure F.4).
     */
    private static final int[] H2 = {
            2, 1, 1, 1, 1, 1, 1, 2,
            2, 2, 1, 1, 1, 1, 2, 2,
            2, 2, 1, 1, 1, 1, 2, 2,
            2, 2, 1, 1, 1, 1, 2, 2,
            2, 2, 1, 1, 1, 1, 2, 2,
            2, 2, 1, 1, 1, 1, 2, 2,
            2, 2, 1, 1, 1, 1, 2, 2,
            2, 1, 1, 1, 1, 1, 1, 2
    };

    /**
     * The half sample chrominance position of each sixteenth sample position (Table F.1).
     */
    private static final int[] SIXTEENTH = {0, 0, 0, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 2, 2};

    /**
     * Prevents instantiation.
     */
    private Prediction() {
    }

    /**
     * Predicts a block with one vector, interpolating half sample positions (6.1.2) and extending the edges of the
     * clip region outward (D.1).
     *
     * @param plane the reference plane
     * @param stride its row stride
     * @param clip the region samples are taken from, as left, top, right and bottom inclusive
     * @param x the column of the block
     * @param y the row of the block
     * @param width the block width
     * @param height the block height
     * @param vectorX the horizontal vector in half samples
     * @param vectorY the vertical vector in half samples
     * @param rounding the rounding control RCONTROL
     * @param out the prediction
     * @param outOffset the index of the prediction's first sample
     * @param outStride the prediction row stride
     */
    static void block(byte[] plane, int stride, int[] clip, int x, int y, int width, int height, int vectorX,
                      int vectorY, int rounding, int[] out, int outOffset, int outStride) {
        var left = x + (vectorX >> 1);
        var top = y + (vectorY >> 1);
        var halfX = vectorX & 1;
        var halfY = vectorY & 1;
        var inside = left >= clip[0] && top >= clip[1] && left + width + halfX - 1 <= clip[2]
                && top + height + halfY - 1 <= clip[3];
        for (var row = 0; row < height; row++) {
            var o = outOffset + row * outStride;
            var sy0 = top + row;
            var sy1 = sy0 + halfY;
            if (!inside) {
                sy0 = clamp(sy0, clip[1], clip[3]);
                sy1 = clamp(sy1, clip[1], clip[3]);
            }
            var r0 = sy0 * stride;
            var r1 = sy1 * stride;
            for (var column = 0; column < width; column++) {
                var sx0 = left + column;
                var sx1 = sx0 + halfX;
                if (!inside) {
                    sx0 = clamp(sx0, clip[0], clip[2]);
                    sx1 = clamp(sx1, clip[0], clip[2]);
                }
                var a = plane[r0 + sx0] & 0xFF;
                int value;
                if (halfX == 0 && halfY == 0) {
                    value = a;
                } else if (halfY == 0) {
                    value = (a + (plane[r0 + sx1] & 0xFF) + 1 - rounding) >> 1;
                } else if (halfX == 0) {
                    value = (a + (plane[r1 + sx0] & 0xFF) + 1 - rounding) >> 1;
                } else {
                    value = (a + (plane[r0 + sx1] & 0xFF) + (plane[r1 + sx0] & 0xFF) + (plane[r1 + sx1] & 0xFF) + 2
                            - rounding) >> 2;
                }
                out[o + column] = value;
            }
        }
    }

    /**
     * Combines three predictions of a luminance block with the overlapped motion compensation weights (F.3), or
     * their enlarged versions for 16 by 16 blocks (Q.5).
     *
     * @param current the prediction with the block's own vector
     * @param above the prediction with the vector of the block above
     * @param below the prediction with the vector of the block below
     * @param left the prediction with the vector of the block to the left
     * @param right the prediction with the vector of the block to the right
     * @param size the block size: 8, or 16 in the Reduced-Resolution Update mode
     * @param out the combined prediction, {@code size} samples per row
     */
    static void overlap(int[] current, int[] above, int[] below, int[] left, int[] right, int size, int[] out) {
        var shift = size == 16 ? 1 : 0;
        var half = size >> 1;
        for (var row = 0; row < size; row++) {
            var vertical = row < half ? above : below;
            for (var column = 0; column < size; column++) {
                var horizontal = column < half ? left : right;
                var at = row * size + column;
                var weight = (row >> shift) * 8 + (column >> shift);
                out[at] = (current[at] * H0[weight] + vertical[at] * H1[weight] + horizontal[at] * H2[weight] + 4)
                        >> 3;
            }
        }
    }

    /**
     * Derives the chrominance vector component of a macroblock with one vector (Table 18).
     *
     * @param luma the luminance component in half samples
     * @return the chrominance component in half samples
     */
    static int chroma(int luma) {
        var magnitude = Math.abs(luma);
        var result = (magnitude >> 2) * 2 + ((magnitude & 3) == 0 ? 0 : 1);
        return luma < 0 ? -result : result;
    }

    /**
     * Derives the chrominance vector component of four luminance vectors (Table F.1).
     *
     * @param sum the sum of the four luminance components in half samples
     * @return the chrominance component in half samples
     */
    static int chromaOfFour(int sum) {
        var magnitude = Math.abs(sum);
        var result = (magnitude >> 4) * 2 + SIXTEENTH[magnitude & 15];
        return sum < 0 ? -result : result;
    }

    /**
     * Clamps a coordinate into a range.
     *
     * @param value the coordinate
     * @param low the lowest value
     * @param high the highest value
     * @return the clamped coordinate
     */
    static int clamp(int value, int low, int high) {
        return value < low ? low : Math.min(value, high);
    }
}
