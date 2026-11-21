/* Ports SVT-JPEG-XS 0.9.0 (WeightTable.c). Copyright 2024 Intel Corporation; BSD-2-Clause-Patent.
   Modified for Smalt. */
package com.github.auties00.smalt.image.jxs.internal;

import java.util.Arrays;

/**
 * Chooses the band gains and priorities of the weights table an encoder writes ({@code WGT}).
 *
 * <p>Three-component pictures (and four-component ones, whose fourth component is weighted like the first) start from
 * the default tables of ISO/IEC 21122-1 for 5 horizontal and up to 2 vertical levels, reduced to the decomposition at
 * hand; other component counts weight every component like the first one of the 4:4:4 table, and components without
 * decomposition like the finest band.
 */
public final class JxsWeights {
    private static final int NE = -1;

    private static final int[] G444_0_5 = {3, 2, 2, 2, 1, 1, 2, 1, 1, 1, 0, 0, 1, 0, 0, 1, 0, 0};
    private static final int[] G444_1_5 = {4, 2, 2, 3, 2, 2, 2, 1, 1, 2, 1, 1, 1, 0, 0, 1, 0, 0, 1, 0, 0, 1, 0, 0};
    private static final int[] G444_2_5 = {4, 3, 3, 3, 2, 2, 3, 2, 2, 2, 1, 1, 2, 1, 1, 2, 1, 1, 1, 0, 0, 1, 0, 0, 1,
            0, 0, 1, 0, 0};
    private static final int[] P444_0_5 = {6, 8, 7, 1, 4, 5, 12, 14, 15, 0, 2, 3, 9, 11, 10, 13, 16, 17};
    private static final int[] P444_1_5 = {21, 1, 0, 15, 19, 18, 5, 9, 8, 14, 17, 16, 2, 4, 3, 7, 13, 11, 6, 12, 10,
            20, 23, 22};
    private static final int[] P444_2_5 = {12, 15, 14, 3, 11, 10, 24, 26, 27, 0, 4, 5, 18, 21, 20, 19, 23, 22, 13, 16,
            17, 2, 9, 6, 1, 7, 8, 25, 28, 29};
    private static final int[] G422_0_5 = {2, 2, 2, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0};
    private static final int[] G422_1_5 = {2, 2, 2, 2, 2, 2, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};
    private static final int[] G422_2_5 = {3, 3, 3, 2, 2, 2, 2, 2, 2, 1, 1, 1, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0};
    private static final int[] P422_0_5 = {8, 7, 6, 5, 3, 4, 13, 12, 14, 1, 0, 2, 9, 11, 10, 16, 15, 17};
    private static final int[] P422_1_5 = {0, 1, 2, 18, 19, 20, 6, 7, 8, 15, 16, 17, 3, 4, 5, 10, 12, 14, 9, 11, 13,
            21, 22, 23};
    private static final int[] P422_2_5 = {14, 13, 12, 9, 11, 10, 25, 24, 26, 0, 1, 2, 19, 20, 18, 23, 22, 21, 17, 15,
            16, 4, 8, 5, 3, 6, 7, 28, 27, 29};
    private static final int[][] G420 = {
            null,
            {1, 1, 1, 1, 0, 0, 1, NE, NE, 0, NE, NE},
            {1, 1, 1, 1, 0, 0, 1, 0, 0, 1, NE, NE, 0, NE, NE},
            {2, 1, 1, 1, 1, 1, 1, 0, 0, 1, 0, 0, 1, NE, NE, 0, NE, NE},
            {2, 2, 2, 2, 1, 1, 1, 1, 1, 1, 0, 0, 1, 0, 0, 1, NE, NE, 0, NE, NE},
            {3, 3, 3, 2, 2, 2, 2, 1, 1, 1, 1, 1, 1, 0, 0, 1, 0, 0, 1, NE, NE, 0, NE, NE}
    };
    private static final int[][] P420 = {
            null,
            {2, 5, 4, 6, 1, 0, 7, NE, NE, 3, NE, NE},
            {4, 7, 6, 8, 1, 0, 10, 3, 2, 9, NE, NE, 5, NE, NE},
            {11, 3, 2, 6, 9, 8, 10, 1, 0, 13, 5, 4, 12, NE, NE, 7, NE, NE},
            {7, 12, 11, 14, 3, 2, 6, 10, 9, 13, 1, 0, 16, 5, 4, 15, NE, NE, 8, NE, NE},
            {9, 16, 15, 7, 13, 12, 17, 3, 2, 6, 11, 10, 14, 1, 0, 19, 5, 4, 18, NE, NE, 8, NE, NE}
    };
    private static final int[][] G420_2 = {
            null, null,
            {2, 1, 1, 1, 1, 1, 1, NE, NE, 0, NE, NE, 1, 1, 1, 1, 1, 1, 0, 0, 0},
            {2, 2, 2, 2, 1, 1, 1, 1, 1, 1, NE, NE, 0, NE, NE, 1, 1, 1, 1, 1, 1, 0, 0, 0},
            {3, 2, 2, 2, 2, 2, 2, 1, 1, 1, 1, 1, 1, NE, NE, 0, NE, NE, 1, 1, 1, 1, 1, 1, 0, 0, 0},
            {3, 3, 3, 3, 2, 2, 2, 2, 2, 2, 1, 1, 1, 1, 1, 1, NE, NE, 0, NE, NE, 1, 1, 1, 1, 1, 1, 0, 0, 0}
    };
    private static final int[][] P420_2 = {
            null, null,
            {10, 4, 3, 2, 9, 8, 1, NE, NE, 0, NE, NE, 16, 14, 12, 15, 13, 11, 7, 6, 5},
            {5, 12, 11, 13, 4, 3, 2, 10, 9, 1, NE, NE, 0, NE, NE, 19, 17, 15, 18, 16, 14, 8, 7, 6},
            {22, 6, 5, 7, 14, 13, 15, 4, 3, 2, 12, 11, 1, NE, NE, 0, NE, NE, 21, 19, 17, 20, 18, 16, 10, 9, 8},
            {0, 13, 12, 25, 7, 6, 8, 17, 16, 18, 5, 4, 3, 15, 14, 2, NE, NE, 1, NE, NE, 24, 22, 20, 23, 21, 19, 11, 10, 9}
    };

    private JxsWeights() {
        throw new AssertionError();
    }

    /**
     * Returns the gains and priorities of every band that exists, in global band order.
     *
     * @param layout the picture layout
     * @return the gains at index 0 and the priorities at index 1
     */
    public static int[][] of(JxsLayout layout) {
        var nlx = layout.levelsX[0];
        var nly = layout.decomposed > 0 ? layout.levelsY[0] : 0;
        int[] gains = null;
        int[] priorities = null;
        if (layout.decomposed >= 3 && layout.decomposed <= 4 && layout.components - layout.decomposed == 0
                && nlx <= 5) {
            var subsampledX = layout.componentWidth[1] < layout.componentWidth[0];
            var subsampledY = layout.componentHeight[1] < layout.componentHeight[0];
            var same = layout.componentWidth[1] == layout.componentWidth[2]
                    && layout.componentHeight[1] == layout.componentHeight[2];
            var fourthFull = layout.components < 4 || layout.componentWidth[3] == layout.componentWidth[0]
                    && layout.componentHeight[3] == layout.componentHeight[0];
            int[][] base = null;
            if (same && fourthFull) {
                if (subsampledX && subsampledY && layout.components == 3 && nly >= 1) {
                    base = nly == 1 ? new int[][]{G420[nlx], P420[nlx]} : new int[][]{G420_2[nlx], P420_2[nlx]};
                } else if (subsampledX && !subsampledY) {
                    base = reduced(nly == 0 ? G422_0_5 : nly == 1 ? G422_1_5 : G422_2_5,
                            nly == 0 ? P422_0_5 : nly == 1 ? P422_1_5 : P422_2_5, nlx);
                } else if (!subsampledX && !subsampledY) {
                    base = reduced(nly == 0 ? G444_0_5 : nly == 1 ? G444_1_5 : G444_2_5,
                            nly == 0 ? P444_0_5 : nly == 1 ? P444_1_5 : P444_2_5, nlx);
                }
            }
            if (base != null && base[0] != null) {
                if (layout.components == 4) {
                    base = withAlpha(base);
                }
                if (base[0].length == layout.globalBands) {
                    gains = new int[layout.existingBands];
                    priorities = new int[layout.existingBands];
                    var index = 0;
                    for (var b = 0; b < layout.globalBands; b++) {
                        if (layout.globalComponent[b] >= 0) {
                            gains[index] = Math.max(base[0][b], 0);
                            priorities[index] = base[1][b];
                            index++;
                        }
                    }
                    priorities = ranks(priorities);
                }
            }
        }
        if (gains == null) {
            var generic = generic(layout);
            gains = generic[0];
            priorities = generic[1];
        }
        return new int[][]{gains, priorities};
    }

    /**
     * Reduces a 3-component table of 5 horizontal levels to {@code nlx} levels ({@code reduce_422_h}).
     */
    private static int[][] reduced(int[] gains, int[] priorities, int nlx) {
        var g = gains.clone();
        var p = priorities.clone();
        var size = g.length;
        var h = 5;
        while (h > nlx) {
            h--;
            if (h != 1) {
                size -= 3;
                System.arraycopy(g, 3, g, 0, size);
                System.arraycopy(p, 3, p, 0, size);
            } else {
                size -= 3;
            }
        }
        return new int[][]{Arrays.copyOf(g, size), Arrays.copyOf(p, size)};
    }

    /**
     * Extends a 3-component table to a fourth component weighted like the first, refined last
     * ({@code weight_table_extend_4comp_alpha_last}).
     */
    private static int[][] withAlpha(int[][] base) {
        var bands = base[0].length / 3;
        var g = new int[bands * 4];
        var p = new int[bands * 4];
        for (var level = 0; level < bands; level++) {
            for (var c = 0; c < 3; c++) {
                g[4 * level + c] = base[0][3 * level + c];
                p[4 * level + c] = base[1][3 * level + c];
            }
            var rank = 0;
            for (var j = 0; j < bands; j++) {
                if (base[1][3 * j] < base[1][3 * level]) {
                    rank++;
                }
            }
            g[4 * level + 3] = base[0][3 * level];
            p[4 * level + 3] = 3 * bands + rank;
        }
        return new int[][]{g, p};
    }

    /**
     * Weights every decomposed component with the luma column of the 4:4:4 table and every component without
     * decomposition like the finest luma band.
     */
    private static int[][] generic(JxsLayout layout) {
        var nly = layout.decomposed > 0 ? layout.levelsY[0] : 0;
        var nlx = layout.decomposed > 0 ? layout.levelsX[0] : 0;
        var base = reduced(nly == 0 ? G444_0_5 : nly == 1 ? G444_1_5 : G444_2_5,
                nly == 0 ? P444_0_5 : nly == 1 ? P444_1_5 : P444_2_5, Math.min(Math.max(nlx, nly), 5));
        var lumaBands = base[0].length / 3;
        var gains = new int[layout.existingBands];
        var keys = new long[layout.existingBands];
        var index = 0;
        for (var g = 0; g < layout.globalBands; g++) {
            var c = layout.globalComponent[g];
            if (c < 0) {
                continue;
            }
            var b = layout.globalBand[g];
            int gain;
            int priority;
            if (c < layout.decomposed) {
                var level = Math.min(b, lumaBands - 1);
                gain = base[0][3 * level] + Math.max(0, nlx - 5);
                priority = base[1][3 * level];
            } else {
                gain = 0;
                priority = base[1][3 * (lumaBands - 1)];
            }
            gains[index] = gain;
            keys[index] = (long) priority << 32 | (long) c << 16 | index;
            index++;
        }
        var order = keys.clone();
        Arrays.sort(order);
        var priorities = new int[layout.existingBands];
        for (var rank = 0; rank < order.length; rank++) {
            priorities[(int) (order[rank] & 0xFFFF)] = rank;
        }
        return new int[][]{gains, priorities};
    }

    /**
     * Replaces priorities by their ranks, 0 to {@code n - 1}, keeping their order.
     */
    private static int[] ranks(int[] priorities) {
        var keys = new long[priorities.length];
        for (var i = 0; i < priorities.length; i++) {
            keys[i] = (long) priorities[i] << 32 | i;
        }
        Arrays.sort(keys);
        var out = new int[priorities.length];
        for (var rank = 0; rank < keys.length; rank++) {
            out[(int) keys[rank]] = rank;
        }
        return out;
    }
}
