package com.github.auties00.smalt.video.hevc.internal;

/**
 * Holds the scan order arrays of ITU-T H.265 6.5.3 to 6.5.5: the up-right diagonal, horizontal
 * and vertical scans of square blocks of 1x1 to 32x32 positions.
 *
 * <p>Each entry packs a position as {@code (y << 8) | x}.
 */
public final class ScanTables {
    /**
     * The up-right diagonal scans, indexed by {@code log2(blkSize)} from 0 to 5.
     */
    public static final int[][] DIAGONAL = new int[6][];

    /**
     * The horizontal scans, indexed by {@code log2(blkSize)} from 0 to 5.
     */
    public static final int[][] HORIZONTAL = new int[6][];

    /**
     * The vertical scans, indexed by {@code log2(blkSize)} from 0 to 5.
     */
    public static final int[][] VERTICAL = new int[6][];

    /**
     * The scans indexed {@code [scanIdx][log2(blkSize)]}: 0 diagonal, 1 horizontal, 2 vertical.
     */
    public static final int[][][] BY_INDEX = {DIAGONAL, HORIZONTAL, VERTICAL};

    /**
     * The inverse of {@link #BY_INDEX} for blocks of up to 8 by 8 positions: the scan index of
     * each position, indexed {@code [scanIdx][log2Size][(y << 3) | x]}.
     */
    public static final int[][][] INDEX_OF = new int[3][4][64];

    static {
        for (var log2 = 0; log2 < 6; log2++) {
            var size = 1 << log2;
            var diagonal = new int[size * size];
            var i = 0;
            var x = 0;
            var y = 0;
            while (i < size * size) {
                while (y >= 0) {
                    if (x < size && y < size) {
                        diagonal[i++] = (y << 8) | x;
                    }
                    y--;
                    x++;
                }
                y = x;
                x = 0;
            }
            DIAGONAL[log2] = diagonal;
            var horizontal = new int[size * size];
            var vertical = new int[size * size];
            for (var n = 0; n < size * size; n++) {
                horizontal[n] = ((n / size) << 8) | (n % size);
                vertical[n] = ((n % size) << 8) | (n / size);
            }
            HORIZONTAL[log2] = horizontal;
            VERTICAL[log2] = vertical;
        }
        for (var scan = 0; scan < 3; scan++) {
            for (var log2 = 0; log2 < 4; log2++) {
                var order = BY_INDEX[scan][log2];
                for (var n = 0; n < order.length; n++) {
                    INDEX_OF[scan][log2][((order[n] >> 8) << 3) | (order[n] & 0xFF)] = n;
                }
            }
        }
    }

    /**
     * Prevents instantiation.
     */
    private ScanTables() {
        throw new AssertionError();
    }
}
