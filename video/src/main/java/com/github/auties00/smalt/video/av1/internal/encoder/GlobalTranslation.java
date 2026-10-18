package com.github.auties00.smalt.video.av1.internal.encoder;

/**
 * Estimates the dominant translation between a picture and a reference, the full-sample vector
 * that minimizes the sum of absolute differences of the whole luma plane, so the motion searches
 * of blocks that have no neighbor with the camera's motion can start there.
 *
 * <p>The search is hierarchical: an exhaustive search on pictures reduced by 16 in each direction,
 * then refinements around the scaled result on pictures reduced by 4 and on the full-size pictures
 * compared on every fourth sample of every fourth row.
 */
final class GlobalTranslation {
    /**
     * The reduction of the coarsest level.
     */
    private static final int COARSE = 16;

    /**
     * The reduction of the middle level.
     */
    private static final int MIDDLE = 4;

    /**
     * The coarsest level's search radius in its samples.
     */
    private static final int COARSE_RADIUS = 8;

    /**
     * The refinement radius around the scaled result of each finer level.
     */
    private static final int REFINE_RADIUS = 3;

    /**
     * Prevents instantiation.
     */
    private GlobalTranslation() {
    }

    /**
     * A luma plane reduced to two levels.
     */
    static final class Pyramid {
        /**
         * The plane reduced by {@link #COARSE}.
         */
        short[] coarse;

        /**
         * The plane reduced by {@link #MIDDLE}.
         */
        short[] middle;

        /**
         * The width of the coarse plane.
         */
        int coarseWidth;

        /**
         * The height of the coarse plane.
         */
        int coarseHeight;

        /**
         * The width of the middle plane.
         */
        int middleWidth;

        /**
         * The height of the middle plane.
         */
        int middleHeight;

        /**
         * Builds the levels of a plane.
         *
         * @param plane the samples
         * @param origin the offset of the first visible sample
         * @param stride the row stride
         * @param width the visible width
         * @param height the visible height
         */
        Pyramid(short[] plane, int origin, int stride, int width, int height) {
            middleWidth = width / MIDDLE;
            middleHeight = height / MIDDLE;
            middle = reduce(plane, origin, stride, middleWidth, middleHeight, MIDDLE);
            coarseWidth = middleWidth / (COARSE / MIDDLE);
            coarseHeight = middleHeight / (COARSE / MIDDLE);
            coarse = reduce(middle, 0, middleWidth, coarseWidth, coarseHeight, COARSE / MIDDLE);
        }
    }

    /**
     * Averages blocks of samples.
     *
     * @param src the samples
     * @param origin the offset of the first sample
     * @param stride the row stride
     * @param w the width of the result
     * @param h the height of the result
     * @param n the block size
     * @return the reduced plane
     */
    private static short[] reduce(short[] src, int origin, int stride, int w, int h, int n) {
        var out = new short[w * h];
        var shift = Integer.numberOfTrailingZeros(n) * 2;
        var round = (1 << shift) >> 1;
        for (var y = 0; y < h; y++) {
            for (var x = 0; x < w; x++) {
                var sum = round;
                var base = origin + y * n * stride + x * n;
                for (var j = 0; j < n; j++) {
                    var o = base + j * stride;
                    for (var i = 0; i < n; i++) {
                        sum += src[o + i];
                    }
                }
                out[y * w + x] = (short) (sum >> shift);
            }
        }
        return out;
    }

    /**
     * Returns the mean absolute difference of two reduced planes with the second displaced, over
     * the samples both planes cover, scaled by the plane size so costs of different displacements
     * compare.
     *
     * @param a the first plane
     * @param b the second plane
     * @param w the width
     * @param h the height
     * @param dy the displacement rows
     * @param dx the displacement columns
     * @param step the distance between the compared rows and columns
     * @param bound the scaled sum from which the result no longer matters
     * @return the scaled sum
     */
    private static long sad(short[] a, short[] b, int w, int h, int dy, int dx, int step, long bound) {
        var x0 = Math.max(0, -dx);
        var x1 = Math.min(w, w - dx);
        var y0 = Math.max(0, -dy);
        var y1 = Math.min(h, h - dy);
        if (x1 - x0 < 4 || y1 - y0 < 4) {
            return Long.MAX_VALUE;
        }
        long sum = 0;
        long count = 0;
        var scale = (long) w * h / (step * step);
        for (var y = y0; y < y1; y += step) {
            var ra = y * w;
            var rb = (y + dy) * w + dx;
            for (var x = x0; x < x1; x += step) {
                sum += Math.abs(a[ra + x] - b[rb + x]);
            }
            count += (x1 - x0 + step - 1) / step;
            if (sum * scale / count >= bound && count * 4 > scale) {
                return Long.MAX_VALUE;
            }
        }
        return sum * scale / Math.max(1, count);
    }

    /**
     * Estimates the translation of a picture against a reference.
     *
     * @param cur the picture's levels
     * @param ref the reference's levels
     * @param curPlane the picture's full-size luma samples
     * @param curOrigin the offset of the picture's first visible sample
     * @param curStride the picture's stride
     * @param refPlane the reference's padded luma samples
     * @param refOrigin the offset of the reference's first visible sample
     * @param refStride the reference's stride
     * @param width the visible width
     * @param height the visible height
     * @param maxShift the largest displacement in samples the padding allows
     * @return the displacement packed as {@code row << 16 | (column & 0xFFFF)}, in samples
     */
    static int estimate(Pyramid cur, Pyramid ref, short[] curPlane, int curOrigin, int curStride, short[] refPlane,
                        int refOrigin, int refStride, int width, int height, int maxShift) {
        if (cur.coarseWidth < 8 || cur.coarseHeight < 8) {
            return 0;
        }
        var bestRow = 0;
        var bestCol = 0;
        var best = Long.MAX_VALUE;
        for (var dy = -COARSE_RADIUS; dy <= COARSE_RADIUS; dy++) {
            for (var dx = -COARSE_RADIUS; dx <= COARSE_RADIUS; dx++) {
                var cost = sad(cur.coarse, ref.coarse, cur.coarseWidth, cur.coarseHeight, dy, dx, 1, best);
                if (cost < best) {
                    best = cost;
                    bestRow = dy;
                    bestCol = dx;
                }
            }
        }
        var centerRow = bestRow * (COARSE / MIDDLE);
        var centerCol = bestCol * (COARSE / MIDDLE);
        best = Long.MAX_VALUE;
        for (var dy = centerRow - REFINE_RADIUS; dy <= centerRow + REFINE_RADIUS; dy++) {
            for (var dx = centerCol - REFINE_RADIUS; dx <= centerCol + REFINE_RADIUS; dx++) {
                var cost = sad(cur.middle, ref.middle, cur.middleWidth, cur.middleHeight, dy, dx, 2, best);
                if (cost < best) {
                    best = cost;
                    bestRow = dy;
                    bestCol = dx;
                }
            }
        }
        centerRow = bestRow * MIDDLE;
        centerCol = bestCol * MIDDLE;
        best = Long.MAX_VALUE;
        var outRow = centerRow;
        var outCol = centerCol;
        for (var dy = centerRow - REFINE_RADIUS; dy <= centerRow + REFINE_RADIUS; dy++) {
            for (var dx = centerCol - REFINE_RADIUS; dx <= centerCol + REFINE_RADIUS; dx++) {
                if (Math.abs(dy) > maxShift || Math.abs(dx) > maxShift) {
                    continue;
                }
                long sum = 0;
                for (var y = 0; y < height && sum < best; y += 4) {
                    var ro = curOrigin + y * curStride;
                    var rr = refOrigin + (y + dy) * refStride + dx;
                    for (var x = 0; x < width; x += 4) {
                        sum += Math.abs(curPlane[ro + x] - refPlane[rr + x]);
                    }
                }
                if (sum < best) {
                    best = sum;
                    outRow = dy;
                    outCol = dx;
                }
            }
        }
        return outRow << 16 | (outCol & 0xFFFF);
    }
}
