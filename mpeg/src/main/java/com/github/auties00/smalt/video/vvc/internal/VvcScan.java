/* Ports VVdeC 3.3.0 (source/Lib/CommonLib/Rom.cpp: the scan orders). Copyright (c) 2018-2026,
 * Fraunhofer-Gesellschaft zur Foerderung der angewandten Forschung e.V. & The VVdeC Authors; Clear BSD License.
 * Modified for Smalt. */
package com.github.auties00.smalt.video.vvc.internal;

/**
 * Holds the up-right diagonal scan orders of ITU-T H.266 6.5.3 for block sizes from 1x1 to 64x64.
 */
final class VvcScan {
    /**
     * The scans indexed by {@code log2Width * 7 + log2Height}; each entry is {@code y * width + x}.
     */
    private static final int[][] DIAGONAL = new int[49][];

    static {
        for (var lw = 0; lw <= 6; lw++) {
            for (var lh = 0; lh <= 6; lh++) {
                var w = 1 << lw;
                var h = 1 << lh;
                var scan = new int[w * h];
                var i = 0;
                var x = 0;
                var y = 0;
                while (i < w * h) {
                    while (y >= 0) {
                        if (x < w && y < h) {
                            scan[i++] = y * w + x;
                        }
                        y--;
                        x++;
                    }
                    y = x;
                    x = 0;
                }
                DIAGONAL[lw * 7 + lh] = scan;
            }
        }
    }

    /**
     * Prevents instantiation.
     */
    private VvcScan() {
    }

    /**
     * Returns the diagonal scan of a block.
     *
     * @param log2Width the block width log2, from 0 to 6
     * @param log2Height the block height log2, from 0 to 6
     * @return the positions in scan order, each {@code y * width + x}; shared, never modified
     */
    static int[] diagonal(int log2Width, int log2Height) {
        return DIAGONAL[log2Width * 7 + log2Height];
    }
}
