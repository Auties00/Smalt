package com.github.auties00.smalt.video.hevc.encoder.internal;

import com.github.auties00.smalt.video.hevc.internal.HevcDsp;

/**
 * Searches the motion vector of a prediction block in one reference picture: an integer search around the
 * start candidates, then half and quarter sample refinements with the normative interpolation of ITU-T H.265
 * 8.5.3.3.3.
 *
 * <p>The reference luma plane is a copy with {@link #PAD} samples repeated around each edge, which equals the
 * reference sample clamping of 8.5.3.3.3.1, so the searched vectors stay within that margin. Instances are
 * not thread-safe.
 */
final class MotionSearch {
    /**
     * The number of samples repeated around the edges of a padded reference plane.
     */
    static final int PAD = 96;

    /**
     * The largest motion vector component searched, in whole samples, so that a block and its interpolation
     * taps stay inside the padding.
     */
    static final int RANGE = PAD - 8;

    /**
     * The large hexagon of the integer search.
     */
    private static final int[] HEX_X = {-2, -1, 1, 2, 1, -1};

    /**
     * The large hexagon of the integer search.
     */
    private static final int[] HEX_Y = {0, -2, -2, 0, 2, 2};

    /**
     * The small diamond of the integer search.
     */
    private static final int[] DIAMOND_X = {-1, 0, 1, 0};

    /**
     * The small diamond of the integer search.
     */
    private static final int[] DIAMOND_Y = {0, -1, 0, 1};

    /**
     * The eight neighbors of a sub-sample refinement step.
     */
    private static final int[] SQUARE_X = {-1, 0, 1, -1, 1, -1, 0, 1};

    /**
     * The eight neighbors of a sub-sample refinement step.
     */
    private static final int[] SQUARE_Y = {-1, -1, -1, 0, 0, 1, 1, 1};

    /**
     * The interpolation kernels.
     */
    private final HevcDsp dsp;

    /**
     * The interpolated block, 14-bit intermediate samples.
     */
    private final short[] interpolated = new short[64 * 64];

    /**
     * The interpolated block in the sample domain.
     */
    private final short[] block = new short[64 * 64];

    /**
     * The interpolation scratch.
     */
    private final short[] scratch = new short[HevcDsp.SCRATCH_SIZE];

    /**
     * The SATD scratch.
     */
    private final int[] satdScratch = new int[64];

    /**
     * The motion vector cost multiplier, {@code sqrt(lambda)} in 1/65536 units per bit.
     */
    private long lambdaFixed;

    /**
     * The luma bit depth.
     */
    private int bitDepth;

    /**
     * Constructs a search.
     *
     * @param dsp the interpolation kernels
     */
    MotionSearch(HevcDsp dsp) {
        this.dsp = dsp;
    }

    /**
     * Sets the trade-off between prediction error and motion vector cost.
     *
     * @param sqrtLambda the cost of one bit in units of the prediction error
     * @param bitDepth the luma bit depth
     */
    void configure(double sqrtLambda, int bitDepth) {
        this.lambdaFixed = Math.round(sqrtLambda * 65536);
        this.bitDepth = bitDepth;
    }

    /**
     * Returns the padded copy of a luma plane.
     *
     * @param plane the luma samples
     * @param width the plane width
     * @param height the plane height
     * @param reuse an earlier padded plane of the same size to overwrite, or {@code null}
     * @return the padded plane, {@code width + 2 * PAD} samples per row
     */
    static short[] pad(short[] plane, int width, int height, short[] reuse) {
        var stride = width + 2 * PAD;
        var length = stride * (height + 2 * PAD);
        var padded = reuse != null && reuse.length == length ? reuse : new short[length];
        for (var y = 0; y < height; y++) {
            var row = (y + PAD) * stride;
            System.arraycopy(plane, y * width, padded, row + PAD, width);
            java.util.Arrays.fill(padded, row, row + PAD, plane[y * width]);
            java.util.Arrays.fill(padded, row + PAD + width, row + stride, plane[y * width + width - 1]);
        }
        for (var y = 0; y < PAD; y++) {
            System.arraycopy(padded, PAD * stride, padded, y * stride, stride);
            System.arraycopy(padded, (PAD + height - 1) * stride, padded, (PAD + height + y) * stride, stride);
        }
        return padded;
    }

    /**
     * Returns the number of bits of a motion vector difference component, approximately as Exp-Golomb.
     *
     * @param value the component, in quarter samples
     * @return the bits
     */
    static int componentBits(int value) {
        var temp = value <= 0 ? (-value << 1) + 1 : value << 1;
        var length = 1;
        while (temp != 1) {
            temp >>= 1;
            length += 2;
        }
        return length;
    }

    /**
     * Returns the cost of a motion vector against a predictor.
     *
     * @param mvX the horizontal vector, quarter samples
     * @param mvY the vertical vector, quarter samples
     * @param predX the horizontal predictor
     * @param predY the vertical predictor
     * @return the cost in prediction error units
     */
    int mvCost(int mvX, int mvY, int predX, int predY) {
        return (int) ((lambdaFixed * (componentBits(mvX - predX) + componentBits(mvY - predY)) + 32768) >> 16);
    }

    /**
     * Returns the cost of a number of bits.
     *
     * @param bits the number of bits
     * @return the cost in prediction error units
     */
    int bitCost(int bits) {
        return (int) ((lambdaFixed * bits + 32768) >> 16);
    }

    /**
     * Searches the best motion vector of a block.
     *
     * @param source the source luma plane
     * @param sourceStride its row stride
     * @param x0 the luma x of the block
     * @param y0 the luma y of the block
     * @param width the block width
     * @param height the block height
     * @param ref the padded reference luma plane
     * @param refStride its row stride
     * @param starts the start candidates, quarter-sample vectors packed as {@code (y << 16) | (x & 0xFFFF)}
     * @param startCount the number of start candidates
     * @param predX the horizontal predictor the cost is measured against, quarter samples
     * @param predY the vertical predictor
     * @param range the integer search range in samples
     * @param subpel 0 for whole samples, 1 for half samples, 2 for quarter samples
     * @param result receives the vector packed as {@code (y << 16) | (x & 0xFFFF)} at index 0 and its cost,
     * SATD plus vector cost, at index 1
     */
    void search(short[] source, int sourceStride, int x0, int y0, int width, int height, short[] ref, int refStride,
                int[] starts, int startCount, int predX, int predY, int range, int subpel, int[] result) {
        var origin = PAD * refStride + PAD;
        var srcOffset = y0 * sourceStride + x0;
        var bestX = 0;
        var bestY = 0;
        var bestCost = Integer.MAX_VALUE;
        for (var i = 0; i < startCount; i++) {
            var mv = starts[i];
            var ix = Math.clamp(((short) mv + 2) >> 2, -RANGE, RANGE);
            var iy = Math.clamp(((mv >> 16) + 2) >> 2, -RANGE, RANGE);
            var cost = integerCost(source, srcOffset, sourceStride, ref, origin, refStride, x0 + ix, y0 + iy, width,
                    height) + mvCost(ix << 2, iy << 2, predX, predY);
            if (cost < bestCost) {
                bestCost = cost;
                bestX = ix;
                bestY = iy;
            }
        }
        var startX = bestX;
        var startY = bestY;
        for (var step = 0; step < range; step++) {
            var centerX = bestX;
            var centerY = bestY;
            for (var k = 0; k < 6; k++) {
                var cx = centerX + HEX_X[k];
                var cy = centerY + HEX_Y[k];
                if (!inside(cx, cy, startX, startY, range)) {
                    continue;
                }
                var cost = integerCost(source, srcOffset, sourceStride, ref, origin, refStride, x0 + cx, y0 + cy, width,
                        height) + mvCost(cx << 2, cy << 2, predX, predY);
                if (cost < bestCost) {
                    bestCost = cost;
                    bestX = cx;
                    bestY = cy;
                }
            }
            if (bestX == centerX && bestY == centerY) {
                break;
            }
        }
        var centerX = bestX;
        var centerY = bestY;
        for (var k = 0; k < 4; k++) {
            var cx = centerX + DIAMOND_X[k];
            var cy = centerY + DIAMOND_Y[k];
            if (!inside(cx, cy, startX, startY, range)) {
                continue;
            }
            var cost = integerCost(source, srcOffset, sourceStride, ref, origin, refStride, x0 + cx, y0 + cy, width,
                    height) + mvCost(cx << 2, cy << 2, predX, predY);
            if (cost < bestCost) {
                bestCost = cost;
                bestX = cx;
                bestY = cy;
            }
        }
        var mvX = bestX << 2;
        var mvY = bestY << 2;
        var best = fractionalCost(source, srcOffset, sourceStride, ref, origin, refStride, x0, y0, width, height, mvX,
                mvY);
        best = best == Integer.MAX_VALUE ? Integer.MAX_VALUE / 2 : best + mvCost(mvX, mvY, predX, predY);
        for (var level = 0; level < subpel; level++) {
            var step = level == 0 ? 2 : 1;
            var cx = mvX;
            var cy = mvY;
            for (var k = 0; k < 8; k++) {
                var tx = cx + SQUARE_X[k] * step;
                var ty = cy + SQUARE_Y[k] * step;
                if ((tx >> 2) < -RANGE || (tx >> 2) >= RANGE || (ty >> 2) < -RANGE || (ty >> 2) >= RANGE) {
                    continue;
                }
                var cost = fractionalCost(source, srcOffset, sourceStride, ref, origin, refStride, x0, y0, width,
                        height, tx, ty);
                if (cost == Integer.MAX_VALUE) {
                    continue;
                }
                cost += mvCost(tx, ty, predX, predY);
                if (cost < best) {
                    best = cost;
                    mvX = tx;
                    mvY = ty;
                }
            }
        }
        result[0] = (mvY << 16) | (mvX & 0xFFFF);
        result[1] = best;
    }

    /**
     * Returns whether a whole-sample vector lies inside the padding and the search window.
     *
     * @param x the horizontal component
     * @param y the vertical component
     * @param startX the horizontal component of the window center
     * @param startY the vertical component of the window center
     * @param range the window radius
     * @return {@code true} when the vector may be tested
     */
    private static boolean inside(int x, int y, int startX, int startY, int range) {
        return x >= -RANGE && x <= RANGE && y >= -RANGE && y <= RANGE && Math.abs(x - startX) <= range
                && Math.abs(y - startY) <= range;
    }

    /**
     * Returns the SAD of a block against the reference at a whole-sample position.
     *
     * @param source the source plane
     * @param srcOffset the index of the block in {@code source}
     * @param sourceStride the source row stride
     * @param ref the padded reference plane
     * @param origin the index of the reference picture's top-left sample in {@code ref}
     * @param refStride the reference row stride
     * @param x the reference x
     * @param y the reference y
     * @param width the block width
     * @param height the block height
     * @return the SAD
     */
    private static int integerCost(short[] source, int srcOffset, int sourceStride, short[] ref, int origin,
                                   int refStride, int x, int y, int width, int height) {
        return Distortion.sad(source, srcOffset, sourceStride, ref, origin + y * refStride + x, refStride, width,
                height);
    }

    /**
     * Returns the SATD of a block against the interpolated reference at a quarter-sample vector.
     *
     * @param source the source plane
     * @param srcOffset the index of the block in {@code source}
     * @param sourceStride the source row stride
     * @param ref the padded reference plane
     * @param origin the index of the reference picture's top-left sample in {@code ref}
     * @param refStride the reference row stride
     * @param x0 the block x
     * @param y0 the block y
     * @param width the block width
     * @param height the block height
     * @param mvX the horizontal vector, quarter samples
     * @param mvY the vertical vector, quarter samples
     * @return the SATD, or {@link Integer#MAX_VALUE} when the interpolation does not fit 16 bits
     */
    int fractionalCost(short[] source, int srcOffset, int sourceStride, short[] ref, int origin, int refStride, int x0,
                       int y0, int width, int height, int mvX, int mvY) {
        if (!interpolate(ref, origin, refStride, x0, y0, width, height, mvX, mvY, block)) {
            return Integer.MAX_VALUE;
        }
        return Distortion.satd(source, srcOffset, sourceStride, block, 0, width, width, height, satdScratch);
    }

    /**
     * Interpolates the uni-directional prediction of a block, rounded to the sample domain.
     *
     * @param ref the padded reference plane
     * @param origin the index of the reference picture's top-left sample in {@code ref}
     * @param refStride the reference row stride
     * @param x0 the block x
     * @param y0 the block y
     * @param width the block width
     * @param height the block height
     * @param mvX the horizontal vector, quarter samples
     * @param mvY the vertical vector, quarter samples
     * @param out receives the samples, {@code width} per row
     * @return whether the interpolation fit 16 bits
     */
    boolean interpolate(short[] ref, int origin, int refStride, int x0, int y0, int width, int height, int mvX,
                        int mvY, short[] out) {
        var offset = origin + (y0 + (mvY >> 2)) * refStride + x0 + (mvX >> 2);
        if (!dsp.lumaMc(ref, offset, refStride, interpolated, width, height, mvX & 3, mvY & 3, bitDepth, scratch)) {
            return false;
        }
        var shift = 14 - bitDepth;
        var add = 1 << (shift - 1);
        var max = (1 << bitDepth) - 1;
        for (var i = 0; i < width * height; i++) {
            out[i] = (short) Math.clamp((interpolated[i] + add) >> shift, 0, max);
        }
        return true;
    }

    /**
     * Interpolates the 14-bit intermediate prediction of a block, as the bi-prediction averages them.
     *
     * @param ref the padded reference plane
     * @param origin the index of the reference picture's top-left sample in {@code ref}
     * @param refStride the reference row stride
     * @param x0 the block x
     * @param y0 the block y
     * @param width the block width
     * @param height the block height
     * @param mvX the horizontal vector, quarter samples
     * @param mvY the vertical vector, quarter samples
     * @param out receives the intermediate samples, {@code width} per row
     * @return whether the interpolation fit 16 bits
     */
    boolean intermediate(short[] ref, int origin, int refStride, int x0, int y0, int width, int height, int mvX,
                         int mvY, short[] out) {
        var offset = origin + (y0 + (mvY >> 2)) * refStride + x0 + (mvX >> 2);
        return dsp.lumaMc(ref, offset, refStride, out, width, height, mvX & 3, mvY & 3, bitDepth, scratch);
    }

    /**
     * Returns the SATD of a block against the average of two intermediate predictions.
     *
     * @param source the source plane
     * @param srcOffset the index of the block in {@code source}
     * @param sourceStride the source row stride
     * @param pred0 the first intermediate prediction
     * @param pred1 the second intermediate prediction
     * @param width the block width
     * @param height the block height
     * @return the SATD
     */
    int biCost(short[] source, int srcOffset, int sourceStride, short[] pred0, short[] pred1, int width, int height) {
        var shift = 15 - bitDepth;
        var add = 1 << (shift - 1);
        var max = (1 << bitDepth) - 1;
        for (var i = 0; i < width * height; i++) {
            block[i] = (short) Math.clamp((pred0[i] + pred1[i] + add) >> shift, 0, max);
        }
        return Distortion.satd(source, srcOffset, sourceStride, block, 0, width, width, height, satdScratch);
    }
}
