/* Ports VVdeC 3.3.0 (source/Lib/CommonLib/Rom.cpp: initGeoTemplate, and InterpolationFilter.cpp:
 * xWeightedGeoBlk). Copyright (c) 2018-2026, Fraunhofer-Gesellschaft zur Foerderung der angewandten Forschung e.V. &
 * The VVdeC Authors; Clear BSD License. Modified for Smalt. */
package com.github.auties00.smalt.video.vvc.internal;

/**
 * Holds the geometric partitioning mode tables of ITU-T H.266 8.5.7: the angle and distance of each of the 64 split
 * directions and the precomputed blending weight masks, and blends the two partition predictions.
 */
final class VvcGpm {
    /**
     * The side of a blending weight mask: the largest block plus the largest displacement on both sides.
     */
    private static final int MASK_SIZE = 3 * (64 >> 3) * 2 + 64;
    /**
     * The mask index of each of the 32 angles, -1 for the angles that have none.
     */
    private static final int[] ANGLE_TO_MASK = {0, -1, 1, 2, 3, 4, -1, -1, 5, -1, -1, 4, 3, 2, 1, -1, 0, -1, 1, 2, 3, 4,
            -1, -1, 5, -1, -1, 4, 3, 2, 1, -1};
    /**
     * How each angle's mask is mirrored from its stored mask: 0 not at all, 1 horizontally, 2 vertically.
     */
    private static final int[] ANGLE_TO_MIRROR = {0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 1, 1, 1, 2, 2, 2, 2};
    /**
     * The displacement lookup {@code disLut} of each of the 32 angles.
     */
    static final int[] DIS = {8, 8, 8, 8, 4, 4, 2, 1, 0, -1, -2, -4, -4, -8, -8, -8, -8, -8, -8, -8, -4, -4, -2, -1, 0,
            1, 2, 4, 4, 8, 8, 8};
    /**
     * The angle index of each split direction.
     */
    static final int[] ANGLE = new int[64];
    /**
     * The distance index of each split direction.
     */
    static final int[] DISTANCE = new int[64];
    /**
     * The precomputed blending weight masks, by mask index.
     */
    private static final byte[][] WEIGHTS = new byte[6][MASK_SIZE * MASK_SIZE];
    /**
     * The weight mask offsets per split direction, log2 height minus 3, log2 width minus 3, and axis.
     */
    private static final int[][][][] OFFSET = new int[64][4][4][2];

    static {
        var mode = 0;
        for (var angle = 0; angle < 32; angle++) {
            for (var distance = 0; distance < 4; distance++) {
                if ((distance == 0 && angle >= 16)
                        || ((distance == 2 || distance == 0) && (ANGLE_TO_MASK[angle] == 0 || ANGLE_TO_MASK[angle] == 5))
                        || ANGLE_TO_MASK[angle] == -1) {
                    continue;
                }
                ANGLE[mode] = angle;
                DISTANCE[mode] = distance;
                mode++;
            }
        }
        for (var angle = 0; angle < 9; angle++) {
            if (ANGLE_TO_MASK[angle] == -1) {
                continue;
            }
            var disX = angle;
            var disY = (disX + 8) % 32;
            var rho = (short) ((DIS[disX] << 7) + (DIS[disY] << 7));
            var maskOffset = (2 * 64 - MASK_SIZE) >> 1;
            var index = 0;
            var weights = WEIGHTS[ANGLE_TO_MASK[angle]];
            for (var y = 0; y < MASK_SIZE; y++) {
                var lookUpY = (short) ((((y + maskOffset) << 1) + 1) * DIS[disY]);
                for (var x = 0; x < MASK_SIZE; x++, index++) {
                    var sxi = (short) (((x + maskOffset) << 1) + 1);
                    var weightIdx = (short) (sxi * DIS[disX] + lookUpY - rho);
                    weights[index] = (byte) Math.clamp((32 + weightIdx + 4) >> 3, 0, 8);
                }
            }
        }
        for (var h = 0; h < 4; h++) {
            var height = 1 << (h + 3);
            for (var w = 0; w < 4; w++) {
                var width = 1 << (w + 3);
                for (var split = 0; split < 64; split++) {
                    var angle = ANGLE[split];
                    var distance = DISTANCE[split];
                    var offsetX = (MASK_SIZE - width) >> 1;
                    var offsetY = (MASK_SIZE - height) >> 1;
                    if (distance > 0) {
                        if (angle % 16 == 8 || (angle % 16 != 0 && height >= width)) {
                            offsetY += angle < 16 ? (distance * height) >> 3 : -((distance * height) >> 3);
                        } else {
                            offsetX += angle < 16 ? (distance * width) >> 3 : -((distance * width) >> 3);
                        }
                    }
                    OFFSET[split][h][w][0] = offsetX;
                    OFFSET[split][h][w][1] = offsetY;
                }
            }
        }
    }

    /**
     * Prevents instantiation.
     */
    private VvcGpm() {
    }

    /**
     * Blends the two partition predictions of a component into the destination (8.5.7.2).
     *
     * @param dst the destination plane
     * @param dstPos the index of the block's top-left sample in the destination
     * @param dstStride the destination stride
     * @param p0 the first partition's prediction in 14-bit intermediate precision, row-major with stride {@code w}
     * @param p1 the second partition's prediction in the same layout
     * @param w the block width in component samples
     * @param h the block height in component samples
     * @param cuW the coding unit width in luma samples
     * @param cuH the coding unit height in luma samples
     * @param split the split direction
     * @param scaleX the horizontal subsampling shift of the component
     * @param scaleY the vertical subsampling shift of the component
     * @param bitDepth the sample bit depth
     */
    static void blend(short[] dst, int dstPos, int dstStride, int[] p0, int[] p1, int w, int h, int cuW, int cuH,
            int split, int scaleX, int scaleY, int bitDepth) {
        var shift = Math.max(2, 14 - bitDepth) + 3;
        var offset = (1 << (shift - 1)) + ((1 << 13) << 3);
        var maxVal = (1 << bitDepth) - 1;
        var angle = ANGLE[split];
        var hIdx = Integer.numberOfTrailingZeros(cuH) - 3;
        var wIdx = Integer.numberOfTrailingZeros(cuW) - 3;
        var off = OFFSET[split][hIdx][wIdx];
        var weights = WEIGHTS[ANGLE_TO_MASK[angle]];
        int stepX = 1 << scaleX;
        int stepY;
        int pos;
        var mirror = ANGLE_TO_MIRROR[angle];
        if (mirror == 2) {
            stepY = -((MASK_SIZE << scaleY) + cuW);
            pos = (MASK_SIZE - 1 - off[1]) * MASK_SIZE + off[0];
        } else if (mirror == 1) {
            stepX = -(1 << scaleX);
            stepY = (MASK_SIZE << scaleY) + cuW;
            pos = off[1] * MASK_SIZE + (MASK_SIZE - 1 - off[0]);
        } else {
            stepY = (MASK_SIZE << scaleY) - cuW;
            pos = off[1] * MASK_SIZE + off[0];
        }
        for (var y = 0; y < h; y++) {
            var row = dstPos + y * dstStride;
            for (var x = 0; x < w; x++) {
                int wt = weights[pos];
                var i = y * w + x;
                dst[row + x] = (short) Math.clamp((wt * p0[i] + (8 - wt) * p1[i] + offset) >> shift, 0, maxVal);
                pos += stepX;
            }
            pos += stepY;
        }
    }
}
