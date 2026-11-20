/* Ports OpenH264 (codec/encoder/core/src/svc_motion_estimate.cpp: the structure of the motion search), Copyright (c)
   2013, Cisco Systems, BSD 2-Clause License; and JM, the ITU-T / ISO/IEC H.264 reference software
   (app/lencod/me_umhex.c: the UMHexagonS search patterns), Copyright ITU-T and ISO/IEC; royalty-free license with
   notice. Modified for Smalt. */
package com.github.auties00.smalt.video.h264.encoder.internal;

import java.util.Arrays;

/**
 * Searches a reference picture for the motion vector of one block that minimizes distortion plus
 * the weighted cost of coding the vector.
 *
 * <p>The search starts from a set of predictor candidates evaluated with SAD at full-sample
 * positions, refines the best with a small diamond, a hexagon, or an uneven multi-hexagon pattern,
 * and finally refines at half- and quarter-sample precision with the Hadamard SATD. The cost of a
 * vector is its distortion plus {@code lambda * bits(mv - mvp)}, where the bits are those of the
 * Exp-Golomb code of the difference. Vectors are confined to a window that keeps every
 * interpolation read inside the reference padding and inside the level's vertical range.
 *
 * <p>When caching is enabled, the fractional refinement computes the unhalved SATDs of the four
 * 8x8 quadrants of the whole macroblock together and remembers them, per reference and vector,
 * until the next {@link #startMacroblock()}: every block made of whole quadrants (16x16, 16x8,
 * 8x16 and 8x8) then reads the SATD of a vector another partition of the macroblock already
 * evaluated from the cache, since the unhalved SATD of a union of quadrants is the sum of theirs.
 * The results are the same as without the cache.
 *
 * @implNote This implementation follows the predictive search structure of the OpenH264 encoder
 * and the hexagon and uneven multi-hexagon patterns of the JM reference encoder (UMHexagonS).
 */
final class MotionSearch {
    /**
     * The distance in samples a block may reach beyond the coded picture.
     */
    private static final int MARGIN = 24;

    /**
     * The fractional plane read first for each quarter-sample phase {@code fy * 4 + fx}: 0 full,
     * 1 horizontal half, 2 vertical half, 3 center half.
     */
    private static final int[] PLANE_A = {0, 0, 1, 0, 0, 1, 1, 1, 2, 2, 3, 3, 0, 2, 3, 2};

    /**
     * The horizontal offset of the first plane's sample for each phase.
     */
    private static final int[] DX_A = {0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1};

    /**
     * The vertical offset of the first plane's sample for each phase.
     */
    private static final int[] DY_A = {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0};

    /**
     * The fractional plane averaged with the first for each phase, {@code -1} when none.
     */
    private static final int[] PLANE_B = {-1, 1, -1, 1, 2, 2, 3, 2, -1, 3, -1, 2, 2, 1, 1, 1};

    /**
     * The horizontal offset of the second plane's sample for each phase.
     */
    private static final int[] DX_B = {0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0, 0};

    /**
     * The vertical offset of the second plane's sample for each phase.
     */
    private static final int[] DY_B = {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1};

    /**
     * The large hexagon pattern.
     */
    private static final int[] HEX_X = {-2, -1, 1, 2, 1, -1};

    /**
     * The large hexagon pattern.
     */
    private static final int[] HEX_Y = {0, -2, -2, 0, 2, 2};

    /**
     * The small diamond pattern.
     */
    private static final int[] DIAMOND_X = {-1, 0, 1, 0};

    /**
     * The small diamond pattern.
     */
    private static final int[] DIAMOND_Y = {0, -1, 0, 1};

    /**
     * The eight neighbors of a position, used for fractional refinement.
     */
    private static final int[] SQUARE_X = {-1, 0, 1, -1, 1, -1, 0, 1};

    /**
     * The eight neighbors of a position, used for fractional refinement.
     */
    private static final int[] SQUARE_Y = {-1, -1, -1, 0, 0, 1, 1, 1};

    /**
     * The sixteen points of the multi-hexagon grid at unit scale.
     */
    private static final int[] UMH_X = {-4, -4, -4, -4, -4, 4, 4, 4, 4, 4, -2, 2, -2, 2, 0, 0};

    /**
     * The sixteen points of the multi-hexagon grid at unit scale.
     */
    private static final int[] UMH_Y = {-2, -1, 0, 1, 2, -2, -1, 0, 1, 2, -3, -3, 3, 3, -4, 4};

    /**
     * The number of bits of the signed Exp-Golomb code of each difference, offset by
     * {@link #MV_COST_OFFSET}.
     */
    private static final byte[] MV_BITS;

    /**
     * The offset of difference zero in {@link #MV_BITS}.
     */
    private static final int MV_COST_OFFSET = 1 << 14;

    static {
        MV_BITS = new byte[2 * MV_COST_OFFSET + 1];
        for (var i = 0; i < MV_BITS.length; i++) {
            MV_BITS[i] = (byte) seBits(i - MV_COST_OFFSET);
        }
    }

    /**
     * The half-width of the grid of full-sample positions whose evaluation is remembered.
     */
    private static final int GRID_RADIUS = 40;

    /**
     * The width of the grid of remembered positions.
     */
    private static final int GRID = 2 * GRID_RADIUS + 1;

    /**
     * The binary logarithm of the number of entries of the quadrant cache.
     */
    private static final int CACHE_BITS = 11;

    /**
     * The number of entries of the quadrant cache.
     */
    private static final int CACHE_SIZE = 1 << CACHE_BITS;

    /**
     * The pixel kernels.
     */
    private PixelKernels pixels;

    /**
     * Whether quadrant distortions are cached across the partitions of a macroblock.
     */
    private final boolean caching;

    /**
     * The keys of the quadrant cache: reference and vector.
     */
    private final long[] cacheKeys = new long[CACHE_SIZE];

    /**
     * The packed quadrant distortions of the quadrant cache.
     */
    private final long[] cacheValues = new long[CACHE_SIZE];

    /**
     * The macroblock stamp of each quadrant cache entry; entries with another stamp are empty.
     */
    private final int[] cacheStamps = new int[CACHE_SIZE];

    /**
     * The stamp of the current macroblock.
     */
    private int cacheStamp;

    /**
     * The quadrants of the macroblock the current block covers, as a bit set in the order of
     * {@link PixelKernels#satdQuadrants}, or {@code 0} when the block is not made of whole
     * quadrants or caching is disabled.
     */
    private int quadrants;

    /**
     * Whether the current fractional refinement can evaluate a vector twice, which makes a
     * whole-macroblock search look up the quadrant cache.
     */
    private boolean repeatsVectors;

    /**
     * The index of the current reference picture.
     */
    private int refIndex;

    /**
     * The index of the macroblock's top-left source sample.
     */
    private int srcMacroblock;

    /**
     * The macroblock's horizontal position in luma samples.
     */
    private int macroblockX;

    /**
     * The macroblock's vertical position in luma samples.
     */
    private int macroblockY;

    /**
     * The stamp of the search that last evaluated each full-sample position of the grid around
     * the predictor.
     */
    private final short[] visited = new short[GRID * GRID];

    /**
     * The quarter-sample prediction whose SATD is being measured, when it is the rounded-up
     * average of two interpolated planes and the block is narrower than 16 samples: up to 16 rows
     * of 8 samples.
     */
    private final byte[] prediction = new byte[16 * 8];

    /**
     * The scratch space of the SATD kernels over 16-sample rows.
     */
    private final short[] hadamardRows = new short[PixelKernels.SATD_SCRATCH];

    /**
     * The stamp of the current search.
     */
    private short stamp;

    /**
     * The horizontal full-sample vector at the grid's first column.
     */
    private int gridX;

    /**
     * The vertical full-sample vector at the grid's first row.
     */
    private int gridY;

    /**
     * The source samples.
     */
    private byte[] src;

    /**
     * The index of the block's top-left source sample.
     */
    private int srcOffset;

    /**
     * The source row stride.
     */
    private int srcStride;

    /**
     * The reference picture.
     */
    private Frame ref;

    /**
     * The block's horizontal position in the picture, in luma samples.
     */
    private int blockX;

    /**
     * The block's vertical position in the picture, in luma samples.
     */
    private int blockY;

    /**
     * The block width.
     */
    private int width;

    /**
     * The block height.
     */
    private int height;

    /**
     * The horizontal component of the predictor, in quarter samples.
     */
    private int mvpX;

    /**
     * The vertical component of the predictor, in quarter samples.
     */
    private int mvpY;

    /**
     * The weight of one bit in distortion units, scaled by 256.
     */
    private int lambda;

    /**
     * The smallest allowed horizontal vector, in quarter samples.
     */
    private int minX;

    /**
     * The largest allowed horizontal vector, in quarter samples.
     */
    private int maxX;

    /**
     * The largest horizontal vector a caller allows, in quarter samples.
     */
    private int limitX = Integer.MAX_VALUE;

    /**
     * The smallest allowed vertical vector, in quarter samples.
     */
    private int minY;

    /**
     * The largest allowed vertical vector, in quarter samples.
     */
    private int maxY;

    /**
     * The horizontal component of the best vector found.
     */
    int bestX;

    /**
     * The vertical component of the best vector found.
     */
    int bestY;

    /**
     * The cost of the best vector found.
     */
    int bestCost;

    /**
     * The distortion part of {@link #bestCost}.
     */
    int bestDistortion;

    /**
     * Constructs a search.
     *
     * @param pixels the pixel kernels
     * @param caching whether to cache quadrant distortions across the partitions of a macroblock
     */
    MotionSearch(PixelKernels pixels, boolean caching) {
        this.pixels = pixels;
        this.caching = caching;
    }

    /**
     * Replaces the kernels the search measures with, between two pictures.
     *
     * @param pixels the pixel kernels
     */
    void useKernels(PixelKernels pixels) {
        this.pixels = pixels;
    }

    /**
     * Sets the largest horizontal vector of the searches of the next macroblocks.
     *
     * @param limit the limit in quarter samples, or {@link Integer#MAX_VALUE} for none
     */
    void setHorizontalLimit(int limit) {
        this.limitX = limit;
    }

    /**
     * Rounds a horizontal candidate vector to full samples; under a caller's horizontal limit the
     * result is kept inside the window, rounding down past the limit, so that a candidate always
     * yields a position the search may evaluate.
     *
     * @param mx the horizontal vector in quarter samples, inside the window
     * @return the horizontal vector in full samples
     */
    int roundX(int mx) {
        var rounded = (mx + 2) >> 2;
        if (limitX != Integer.MAX_VALUE && rounded << 2 > maxX) {
            rounded = Math.floorDiv(maxX, 4);
        }
        return rounded;
    }

    /**
     * Forgets the cached distortions of the previous macroblock.
     */
    void startMacroblock() {
        if (++cacheStamp == 0) {
            Arrays.fill(cacheStamps, 0);
            cacheStamp = 1;
        }
    }

    /**
     * Returns the number of bits of the signed Exp-Golomb code of a value.
     *
     * @param value the signed value
     * @return the code length
     */
    static int seBits(int value) {
        var code = value > 0 ? 2L * value - 1 : -2L * value;
        return 2 * (63 - Long.numberOfLeadingZeros(code + 1)) + 1;
    }

    /**
     * Returns the number of bits of the Exp-Golomb code of a motion vector difference.
     *
     * @param difference the difference in quarter samples
     * @return the code length
     */
    static int mvBits(int difference) {
        var index = difference + MV_COST_OFFSET;
        return index >= 0 && index < MV_BITS.length ? MV_BITS[index] : seBits(difference);
    }

    /**
     * Prepares a search for one block.
     *
     * @param src the source luma samples
     * @param srcOffset the index of the block's top-left source sample
     * @param srcStride the source row stride
     * @param ref the reference picture, padded and interpolated
     * @param refIndex the reference index of {@code ref}
     * @param blockX the block's horizontal position in luma samples
     * @param blockY the block's vertical position in luma samples
     * @param width the block width
     * @param height the block height
     * @param mvp the packed motion vector predictor
     * @param lambda the weight of one bit in distortion units, scaled by 256
     * @param maxMvY the level's vertical vector limit in quarter samples
     */
    void setup(byte[] src, int srcOffset, int srcStride, Frame ref, int refIndex, int blockX, int blockY, int width,
               int height, int mvp, int lambda, int maxMvY) {
        this.src = src;
        this.srcOffset = srcOffset;
        this.srcStride = srcStride;
        this.ref = ref;
        this.blockX = blockX;
        this.blockY = blockY;
        this.width = width;
        this.height = height;
        this.mvpX = MotionPredictor.x(mvp);
        this.mvpY = MotionPredictor.y(mvp);
        this.lambda = lambda;
        var mbX = blockX & ~15;
        var mbY = blockY & ~15;
        this.refIndex = refIndex;
        this.macroblockX = mbX;
        this.macroblockY = mbY;
        this.srcMacroblock = srcOffset - (blockY - mbY) * srcStride - (blockX - mbX);
        this.quadrants = caching ? quadrantMask(blockX - mbX, blockY - mbY, width, height) : 0;
        this.minX = Math.max(-2048 * 4, (-MARGIN - mbX) * 4);
        this.maxX = Math.min(Math.min(2047 * 4, (ref.width + MARGIN - 16 - mbX) * 4), limitX);
        this.minY = Math.max(-maxMvY, (-MARGIN - mbY) * 4);
        this.maxY = Math.min(maxMvY, (ref.height + MARGIN - 16 - mbY) * 4);
        this.bestCost = Integer.MAX_VALUE;
        this.gridX = (mvpX >> 2) - GRID_RADIUS;
        this.gridY = (mvpY >> 2) - GRID_RADIUS;
        if (++stamp == 0) {
            Arrays.fill(visited, (short) 0);
            stamp = 1;
        }
    }

    /**
     * Returns the cost of coding a vector relative to the predictor.
     *
     * @param mx the horizontal component in quarter samples
     * @param my the vertical component in quarter samples
     * @return the weighted bit cost
     */
    int vectorCost(int mx, int my) {
        return (lambda * (mvBits(mx - mvpX) + mvBits(my - mvpY)) + 128) >> 8;
    }

    /**
     * Returns whether a vector lies inside the search window.
     *
     * @param mx the horizontal component in quarter samples
     * @param my the vertical component in quarter samples
     * @return whether the vector is allowed
     */
    boolean inside(int mx, int my) {
        return mx >= minX && mx <= maxX && my >= minY && my <= maxY;
    }

    /**
     * Clamps a packed vector into the search window.
     *
     * @param packed the packed vector
     * @return the clamped packed vector
     */
    int clamp(int packed) {
        return MotionPredictor.pack(Math.clamp(MotionPredictor.x(packed), minX, maxX),
                Math.clamp(MotionPredictor.y(packed), minY, maxY));
    }

    /**
     * Evaluates a full-sample position with SAD and keeps it when it improves the best cost.
     *
     * <p>A position already evaluated by the current search is not evaluated again: its cost
     * cannot be below the best cost, which already accounts for it.
     *
     * @param ix the horizontal vector in full samples
     * @param iy the vertical vector in full samples
     * @return whether the position became the best
     */
    boolean tryInteger(int ix, int iy) {
        var mx = ix << 2;
        var my = iy << 2;
        if (!inside(mx, my)) {
            return false;
        }
        var gx = ix - gridX;
        var gy = iy - gridY;
        if (gx >= 0 && gx < GRID && gy >= 0 && gy < GRID) {
            var cell = gy * GRID + gx;
            if (visited[cell] == stamp) {
                return false;
            }
            visited[cell] = stamp;
        }
        var offset = ref.lumaOrigin + (blockY + iy) * ref.lumaStride + blockX + ix;
        var sad = pixels.sad(src, srcOffset, srcStride, ref.y, offset, ref.lumaStride, width, height);
        var cost = sad + vectorCost(mx, my);
        if (cost < bestCost) {
            bestCost = cost;
            bestDistortion = sad;
            bestX = mx;
            bestY = my;
            return true;
        }
        return false;
    }

    /**
     * Runs the integer search from the best candidate.
     *
     * @param method the pattern: 0 small diamond, 1 hexagon, 2 uneven multi-hexagon
     * @param range the search radius in full samples
     */
    void searchInteger(int method, int range) {
        var centerX = bestX >> 2;
        var centerY = bestY >> 2;
        var limitX0 = centerX - range;
        var limitX1 = centerX + range;
        var limitY0 = centerY - range;
        var limitY1 = centerY + range;
        if (method == 2) {
            for (var i = 1; i <= range; i += i < 4 ? 1 : 2) {
                tryIntegerBounded(centerX + i, centerY, limitX0, limitX1, limitY0, limitY1);
                tryIntegerBounded(centerX - i, centerY, limitX0, limitX1, limitY0, limitY1);
                if (i <= range / 2) {
                    tryIntegerBounded(centerX, centerY + i, limitX0, limitX1, limitY0, limitY1);
                    tryIntegerBounded(centerX, centerY - i, limitX0, limitX1, limitY0, limitY1);
                }
            }
            var gridX = bestX >> 2;
            var gridY = bestY >> 2;
            for (var scale = 1; scale * 4 <= range; scale <<= 1) {
                for (var p = 0; p < 16; p++) {
                    tryIntegerBounded(gridX + UMH_X[p] * scale, gridY + UMH_Y[p] * scale, limitX0, limitX1, limitY0,
                            limitY1);
                }
            }
        }
        if (method >= 1) {
            for (var step = 0; step < range; step++) {
                var cx = bestX >> 2;
                var cy = bestY >> 2;
                var moved = false;
                for (var p = 0; p < 6; p++) {
                    moved |= tryIntegerBounded(cx + HEX_X[p], cy + HEX_Y[p], limitX0, limitX1, limitY0, limitY1);
                }
                if (!moved) {
                    break;
                }
            }
        }
        for (var step = 0; step < range; step++) {
            var cx = bestX >> 2;
            var cy = bestY >> 2;
            var moved = false;
            for (var p = 0; p < 4; p++) {
                moved |= tryIntegerBounded(cx + DIAMOND_X[p], cy + DIAMOND_Y[p], limitX0, limitX1, limitY0, limitY1);
            }
            if (!moved) {
                break;
            }
        }
    }

    /**
     * Evaluates a full-sample position if it lies within the search range.
     *
     * @param ix the horizontal vector in full samples
     * @param iy the vertical vector in full samples
     * @param limitX0 the smallest horizontal vector of the range
     * @param limitX1 the largest horizontal vector of the range
     * @param limitY0 the smallest vertical vector of the range
     * @param limitY1 the largest vertical vector of the range
     * @return whether the position became the best
     */
    private boolean tryIntegerBounded(int ix, int iy, int limitX0, int limitX1, int limitY0, int limitY1) {
        if (ix < limitX0 || ix > limitX1 || iy < limitY0 || iy > limitY1) {
            return false;
        }
        return tryInteger(ix, iy);
    }

    /**
     * Refines the best vector at half- and quarter-sample precision with SATD costs.
     *
     * <p>Level 0 tries the four half-sample then the four quarter-sample diamond neighbors
     * once; level 1 tries the eight half-sample neighbors then the quarter-sample diamond; level
     * 2 tries the eight neighbors at both precisions; level 3 repeats the eight-neighbor
     * refinement at both precisions until it stops improving (at most twice each).
     *
     * @param level the refinement level, from 0 to 3
     */
    void refineSubpel(int level) {
        repeatsVectors = level >= 3;
        var baseCost = satdCost(bestX, bestY);
        bestDistortion = baseCost - vectorCost(bestX, bestY);
        bestCost = baseCost;
        switch (level) {
            case 0 -> {
                refineStep(2, 4);
                refineStep(1, 4);
            }
            case 1 -> {
                refineStep(2, 8);
                refineStep(1, 4);
            }
            case 2 -> {
                refineStep(2, 8);
                refineStep(1, 8);
            }
            default -> {
                if (refineStep(2, 8)) {
                    refineStep(2, 8);
                }
                if (refineStep(1, 8)) {
                    refineStep(1, 8);
                }
            }
        }
    }

    /**
     * Evaluates the neighbors of the best vector at a fractional step.
     *
     * @param step the step in quarter samples
     * @param points 4 for the diamond neighbors, 8 for all neighbors
     * @return whether the best vector moved
     */
    private boolean refineStep(int step, int points) {
        var cx = bestX;
        var cy = bestY;
        for (var p = 0; p < points; p++) {
            var mx = cx + (points == 4 ? DIAMOND_X[p] : SQUARE_X[p]) * step;
            var my = cy + (points == 4 ? DIAMOND_Y[p] : SQUARE_Y[p]) * step;
            if (!inside(mx, my)) {
                continue;
            }
            var cost = satdCost(mx, my);
            if (cost < bestCost) {
                bestCost = cost;
                bestDistortion = cost - vectorCost(mx, my);
                bestX = mx;
                bestY = my;
            }
        }
        return bestX != cx || bestY != cy;
    }

    /**
     * Returns the SATD cost of a quarter-sample vector.
     *
     * @param mx the horizontal component in quarter samples
     * @param my the vertical component in quarter samples
     * @return the SATD plus the weighted vector cost
     */
    int satdCost(int mx, int my) {
        return satd(mx, my) + vectorCost(mx, my);
    }

    /**
     * Returns the SATD of the prediction at a quarter-sample vector, without vector cost.
     *
     * @param mx the horizontal component in quarter samples
     * @param my the vertical component in quarter samples
     * @return the SATD
     */
    int satd(int mx, int my) {
        if (quadrants != 0) {
            return sumQuadrants(cachedSatd(mx, my), quadrants) >> 1;
        }
        var phase = (my & 3) * 4 + (mx & 3);
        var stride = ref.lumaStride;
        var base = ref.lumaOrigin + (blockY + (my >> 2)) * stride + blockX + (mx >> 2);
        var planeA = plane(ref, PLANE_A[phase]);
        var offsetA = base + DY_A[phase] * stride + DX_A[phase];
        var b = PLANE_B[phase];
        if (b < 0) {
            return pixels.satd(src, srcOffset, srcStride, planeA, offsetA, stride, width, height);
        }
        var offsetB = base + DY_B[phase] * stride + DX_B[phase];
        if (width == 16) {
            return pixels.satdAverage16(src, srcOffset, srcStride, planeA, offsetA, plane(ref, b), offsetB, stride,
                    height, hadamardRows);
        }
        pixels.average(planeA, offsetA, stride, plane(ref, b), offsetB, stride, prediction, 0, 8, width, height);
        return pixels.satd(src, srcOffset, srcStride, prediction, 0, 8, width, height);
    }

    /**
     * Returns the bit set of the macroblock quadrants a block covers.
     *
     * @param x the block's horizontal offset in the macroblock
     * @param y the block's vertical offset in the macroblock
     * @param width the block width
     * @param height the block height
     * @return the quadrants, or {@code 0} when the block is not made of whole quadrants
     */
    private static int quadrantMask(int x, int y, int width, int height) {
        if (((x | y) & 7) != 0 || (width != 8 && width != 16) || (height != 8 && height != 16)) {
            return 0;
        }
        var mask = 0;
        for (var qy = y >> 3; qy < (y + height) >> 3; qy++) {
            for (var qx = x >> 3; qx < (x + width) >> 3; qx++) {
                mask |= 1 << (qy * 2 + qx);
            }
        }
        return mask;
    }

    /**
     * Returns the sum of the packed 16-bit quadrant values selected by a bit set.
     *
     * @param packed the packed quadrant values
     * @param quadrants the bit set of quadrants to add
     * @return the sum
     */
    private static int sumQuadrants(long packed, int quadrants) {
        var sum = 0;
        for (var quadrant = 0; quadrant < 4; quadrant++) {
            if ((quadrants & (1 << quadrant)) != 0) {
                sum += (int) (packed >>> (16 * quadrant)) & 0xFFFF;
            }
        }
        return sum;
    }

    /**
     * Returns the cache slot of a key: the cache is direct mapped, so a key owns one slot and
     * evicts whatever other key occupied it.
     *
     * @param key the packed reference index and motion vector
     * @return the slot index
     */
    private static int slot(long key) {
        return (int) ((key * 0x9E3779B97F4A7C15L) >>> (64 - CACHE_BITS));
    }

    /**
     * Returns whether a slot holds a key's entry for the current macroblock.
     *
     * @param index the slot
     * @param key the packed reference index and motion vector
     * @return whether the entry is present
     */
    private boolean cached(int index, long key) {
        return cacheStamps[index] == cacheStamp && cacheKeys[index] == key;
    }

    /**
     * Returns the packed unhalved quadrant SATDs of the macroblock at a quarter-sample vector,
     * from the cache or computed and cached.
     *
     * <p>A search over the whole macroblock runs before the searches of its parts and, below
     * refinement level 3, evaluates each vector once, so it then only stores into the cache
     * without looking it up.
     *
     * @param mx the horizontal vector in quarter samples
     * @param my the vertical vector in quarter samples
     * @return the packed quadrant SATDs
     */
    private long cachedSatd(int mx, int my) {
        var key = ((long) refIndex << 40) | ((long) (mx & 0xFFFFF) << 20) | (my & 0xFFFFF);
        var index = slot(key);
        if ((quadrants != 15 || repeatsVectors) && cached(index, key)) {
            return cacheValues[index];
        }
        var phase = (my & 3) * 4 + (mx & 3);
        var stride = ref.lumaStride;
        var base = ref.lumaOrigin + (macroblockY + (my >> 2)) * stride + macroblockX + (mx >> 2);
        var planeA = plane(ref, PLANE_A[phase]);
        var offsetA = base + DY_A[phase] * stride + DX_A[phase];
        var b = PLANE_B[phase];
        long packed;
        if (b < 0) {
            packed = pixels.satdQuadrants(src, srcMacroblock, srcStride, planeA, offsetA, stride, hadamardRows);
        } else {
            packed = pixels.satdAverageQuadrants(src, srcMacroblock, srcStride, planeA, offsetA, plane(ref, b),
                    base + DY_B[phase] * stride + DX_B[phase], stride, hadamardRows);
        }
        store(index, key, packed);
        return packed;
    }

    /**
     * Stores a cache entry in its slot.
     *
     * @param index the slot returned by {@link #slot(long)}
     * @param key the packed reference index and motion vector
     * @param packed the packed quadrant values
     */
    private void store(int index, long key, long packed) {
        cacheKeys[index] = key;
        cacheValues[index] = packed;
        cacheStamps[index] = cacheStamp;
    }

    /**
     * Writes the luma prediction of a block at a quarter-sample vector (clause 8.4.2.2.1).
     *
     * @param ref the reference picture, padded and interpolated
     * @param blockX the block's horizontal position in luma samples
     * @param blockY the block's vertical position in luma samples
     * @param width the block width
     * @param height the block height
     * @param mx the horizontal vector in quarter samples
     * @param my the vertical vector in quarter samples
     * @param dst the destination samples
     * @param dstOffset the index of the destination's top-left sample
     * @param dstStride the destination row stride
     */
    void predict(Frame ref, int blockX, int blockY, int width, int height, int mx, int my, byte[] dst, int dstOffset,
                 int dstStride) {
        var phase = (my & 3) * 4 + (mx & 3);
        var stride = ref.lumaStride;
        var base = ref.lumaOrigin + (blockY + (my >> 2)) * stride + blockX + (mx >> 2);
        var planeA = plane(ref, PLANE_A[phase]);
        var offsetA = base + DY_A[phase] * stride + DX_A[phase];
        var b = PLANE_B[phase];
        if (b < 0) {
            pixels.copy(planeA, offsetA, stride, dst, dstOffset, dstStride, width, height);
        } else {
            pixels.average(planeA, offsetA, stride, plane(ref, b), base + DY_B[phase] * stride + DX_B[phase], stride,
                    dst, dstOffset, dstStride, width, height);
        }
    }

    /**
     * Returns one of a reference picture's luma planes.
     *
     * @param ref the reference picture
     * @param index 0 full, 1 horizontal half, 2 vertical half, 3 center half
     * @return the plane
     */
    private static byte[] plane(Frame ref, int index) {
        return switch (index) {
            case 0 -> ref.y;
            case 1 -> ref.halfH;
            case 2 -> ref.halfV;
            default -> ref.halfC;
        };
    }
}
