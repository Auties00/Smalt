/* Ports libvpx 1.17.0 (vp9/common/vp9_loopfilter.c). Copyright (c) 2010, The WebM Project authors; BSD 3-Clause
   License. Modified for Smalt. */
package com.github.auties00.smalt.video.vp9.internal;

/**
 * Holds the frame parameters the loop filter reads ({@code loop_filter_info_n} and the mode
 * information grid): the filter level of every segment, reference and mode, the thresholds of
 * every level, and the blocks of the frame.
 *
 * <p>The decoder and the encoder each own one and fill it before filtering a frame; the filters
 * of the frame only read it. Instances are not thread-safe.
 */
final class Vp9LoopFilterParams {
    /**
     * The filter level of each segment, reference and mode delta index, {@code [8][4][2]}.
     */
    final int[] levels = new int[8 * 4 * 2];

    /**
     * The edge limit of each filter level.
     */
    final int[] mblim = new int[64];

    /**
     * The interior limit of each filter level.
     */
    final int[] limit = new int[64];

    /**
     * The high edge variance threshold of each filter level.
     */
    final int[] hevThr = new int[64];

    /**
     * The mode information of the block covering each 8x8 unit, row-major with {@link #miCols}
     * columns.
     */
    ModeInfo[] miGrid = new ModeInfo[0];

    /**
     * The rows of 8x8 units of the frame.
     */
    int miRows;

    /**
     * The columns of 8x8 units of the frame.
     */
    int miCols;

    /**
     * The sharpness the limits were computed for, or -1 when they must be recomputed.
     */
    private int sharpness = -1;

    /**
     * Constructs the parameters of sharpness 0 and an empty frame.
     */
    Vp9LoopFilterParams() {
        for (var level = 0; level < 64; level++) {
            hevThr[level] = level >> 4;
        }
        setSharpness(0);
    }

    /**
     * Computes the limits of every level for a sharpness, unless they were computed for it
     * already ({@code update_sharpness}).
     *
     * @param sharpness the sharpness, from 0 to 7
     */
    void setSharpness(int sharpness) {
        if (this.sharpness == sharpness) {
            return;
        }
        for (var level = 0; level < 64; level++) {
            var limit = level >> ((sharpness > 0 ? 1 : 0) + (sharpness > 4 ? 1 : 0));
            if (sharpness > 0 && limit > 9 - sharpness) {
                limit = 9 - sharpness;
            }
            if (limit < 1) {
                limit = 1;
            }
            this.limit[level] = limit;
            mblim[level] = 2 * (level + 2) + limit;
        }
        this.sharpness = sharpness;
    }

    /**
     * Makes the next {@link #setSharpness(int)} recompute the limits, as a stream reset does.
     */
    void resetSharpness() {
        sharpness = -1;
    }

    /**
     * Sets the blocks of the frame.
     *
     * @param grid the mode information grid, at least {@code rows * cols} entries
     * @param rows the rows of 8x8 units
     * @param cols the columns of 8x8 units
     */
    void setGrid(ModeInfo[] grid, int rows, int cols) {
        miGrid = grid;
        miRows = rows;
        miCols = cols;
    }
}
