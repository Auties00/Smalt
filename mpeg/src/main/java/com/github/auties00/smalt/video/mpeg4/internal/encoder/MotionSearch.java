package com.github.auties00.smalt.video.mpeg4.internal.encoder;

import com.github.auties00.smalt.video.mpeg4.internal.Frame;
import com.github.auties00.smalt.video.mpeg4.internal.Mpeg4Dsp;
import com.github.auties00.smalt.video.mpeg4.internal.MotionCompensator;

/**
 * Searches the motion vector of a luminance block of a source picture in a reference picture: candidate vectors (among
 * them those of an exhaustive search of reduced resolution {@link MotionPyramid}s), a coarse to fine search over whole
 * samples, then refinement to half and quarter samples, minimizing the sum of absolute differences plus an estimate of
 * the vector's cost in bits.
 *
 * <p>Vectors are in half samples, or in quarter samples when {@link #quarter} is set. The sub-sample predictions are
 * formed with the decoder's {@link MotionCompensator}, so the cost of a vector is that of the prediction a decoder
 * forms. An instance holds scratch buffers and is used by one thread at a time; arguments are not checked.
 */
public final class MotionSearch {
    /**
     * The block predictions of the sub-sample refinement.
     */
    private final byte[] prediction = new byte[16 * 16];

    /**
     * The second prediction of a bidirectional block.
     */
    private final byte[] second = new byte[16 * 16];

    /**
     * The decoder's motion compensation.
     */
    private final MotionCompensator compensator = new MotionCompensator();

    /**
     * Whether vectors are in quarter samples.
     */
    private boolean quarter;

    /**
     * The rounding control of the predictions.
     */
    private int rounding;

    /**
     * The cost of one bit of a vector difference in sum of absolute differences units.
     */
    private int lambda;

    /**
     * The smallest and largest vector components allowed, in vector units: {@code minX, maxX, minY, maxY}.
     */
    private final int[] bounds = new int[4];

    /**
     * The best vector found by the last search, horizontal component.
     */
    public int bestX;

    /**
     * The best vector found by the last search, vertical component.
     */
    public int bestY;

    /**
     * The sum of absolute differences of the best vector found by the last search.
     */
    public int bestSad;

    /**
     * The cost of the best vector found by the last search: its sum of absolute differences plus its vector cost.
     */
    public int bestCost;

    /**
     * Constructs a search.
     */
    public MotionSearch() {
        compensator.setDsp(Mpeg4Dsp.select());
    }

    /**
     * Sets the vector precision, prediction rounding and vector cost of the searches that follow.
     *
     * @param quarter whether vectors are in quarter samples
     * @param rounding the rounding control, 0 or 1
     * @param lambda the cost of one bit of a vector difference, at least 0
     */
    public void configure(boolean quarter, int rounding, int lambda) {
        this.quarter = quarter;
        this.rounding = rounding;
        this.lambda = lambda;
    }

    /**
     * Sets the vector range of the searches that follow.
     *
     * @param minX the smallest horizontal component
     * @param maxX the largest horizontal component
     * @param minY the smallest vertical component
     * @param maxY the largest vertical component
     */
    public void bounds(int minX, int maxX, int minY, int maxY) {
        bounds[0] = minX;
        bounds[1] = maxX;
        bounds[2] = minY;
        bounds[3] = maxY;
    }

    /**
     * {@return the compensator of this search, for predictions outside the search}
     */
    public MotionCompensator compensator() {
        return compensator;
    }

    /**
     * The largest number of start candidates {@link #pyramidCandidates} returns.
     */
    public static final int PYRAMID_CANDIDATES = 3;

    /**
     * The sums of absolute differences of the quarter resolution search, sized for the largest range searched so far.
     */
    private int[] coarseSads = new int[0];

    /**
     * Writes start candidates for the search of a macroblock found on the pyramids of the source and the reference.
     *
     * <p>An exhaustive search of the quarter resolution level over the search range picks the {@value
     * #PYRAMID_CANDIDATES} best displacements that are not neighbors of a better one (a textured picture often holds
     * several near matches, and the true one need not be the best at that resolution, where it may lie between two
     * samples); each is refined by two samples on the half resolution level and by one sample at full resolution.
     * Every candidate lies within the vector range set by {@link #bounds} and its block within the pictures' coded
     * area extended by one sample.
     *
     * @param sourcePyramid the pyramid of the source picture
     * @param refPyramid the pyramid of the reference picture
     * @param source the source picture
     * @param ref the reference picture
     * @param x the macroblock's left column
     * @param y the macroblock's top row
     * @param range the largest whole-sample vector component searched
     * @param out receives the candidates as {@code x, y} pairs in vector units
     * @param offset the index of the first value written
     * @return the number of candidates written, from 1 to {@value #PYRAMID_CANDIDATES}
     */
    public int pyramidCandidates(MotionPyramid sourcePyramid, MotionPyramid refPyramid, Frame source, Frame ref, int x,
                                 int y, int range, int[] out, int offset) {
        var shift = quarter ? 2 : 1;
        var minX = ceilDiv(bounds[0], 1 << shift);
        var maxX = bounds[1] >> shift;
        var minY = ceilDiv(bounds[2], 1 << shift);
        var maxY = bounds[3] >> shift;
        var qx = x >> 2;
        var qy = y >> 2;
        var qStride = sourcePyramid.quarterWidth;
        var qRange = (range + 3) >> 2;
        var qMinX = Math.max(Math.max(-qRange, ceilDiv(minX, 4)), -qx);
        var qMaxX = Math.min(Math.min(qRange, maxX >> 2), sourcePyramid.quarterWidth - 4 - qx);
        var qMinY = Math.max(Math.max(-qRange, ceilDiv(minY, 4)), -qy);
        var qMaxY = Math.min(Math.min(qRange, maxY >> 2), sourcePyramid.quarterHeight - 4 - qy);
        var columns = qMaxX - qMinX + 1;
        var rows = qMaxY - qMinY + 1;
        if (columns <= 0 || rows <= 0) {
            out[offset] = clamp(0, minX, maxX) << shift;
            out[offset + 1] = clamp(0, minY, maxY) << shift;
            return 1;
        }
        var side = 2 * qRange + 1;
        if (coarseSads.length < side * side) {
            coarseSads = new int[side * side];
        }
        var sads = coarseSads;
        var sourceOffset = qy * qStride + qx;
        for (var dy = qMinY; dy <= qMaxY; dy++) {
            for (var dx = qMinX; dx <= qMaxX; dx++) {
                sads[(dy - qMinY) * columns + dx - qMinX] = MotionPyramid.sad(sourcePyramid.quarter, sourceOffset,
                        refPyramid.quarter, sourceOffset + dy * qStride + dx, qStride, 4, Integer.MAX_VALUE);
            }
        }
        var count = 0;
        while (count < PYRAMID_CANDIDATES) {
            var best = Integer.MAX_VALUE;
            var bestDx = 0;
            var bestDy = 0;
            for (var row = 0; row < rows; row++) {
                for (var col = 0; col < columns; col++) {
                    var sad = sads[row * columns + col];
                    var dx = col + qMinX;
                    var dy = row + qMinY;
                    if (sad < best || sad == best && sad != Integer.MAX_VALUE
                            && Math.abs(dx) + Math.abs(dy) < Math.abs(bestDx) + Math.abs(bestDy)) {
                        best = sad;
                        bestDx = dx;
                        bestDy = dy;
                    }
                }
            }
            if (best == Integer.MAX_VALUE) {
                break;
            }
            for (var row = Math.max(0, bestDy - qMinY - 1); row <= Math.min(rows - 1, bestDy - qMinY + 1); row++) {
                for (var col = Math.max(0, bestDx - qMinX - 1); col <= Math.min(columns - 1, bestDx - qMinX + 1);
                     col++) {
                    sads[row * columns + col] = Integer.MAX_VALUE;
                }
            }
            var refined = refineCandidate(sourcePyramid, refPyramid, source, ref, x, y, 2 * bestDx, 2 * bestDy, minX,
                    maxX, minY, maxY);
            out[offset + 2 * count] = (refined >> 16) << shift;
            out[offset + 2 * count + 1] = ((short) refined) << shift;
            count++;
        }
        return count;
    }

    /**
     * Refines a half resolution displacement by two samples on the half resolution level, then by one sample at full
     * resolution.
     *
     * @param sourcePyramid the pyramid of the source picture
     * @param refPyramid the pyramid of the reference picture
     * @param source the source picture
     * @param ref the reference picture
     * @param x the macroblock's left column
     * @param y the macroblock's top row
     * @param centerX the horizontal displacement in half resolution samples
     * @param centerY the vertical displacement in half resolution samples
     * @param minX the smallest horizontal whole-sample vector component
     * @param maxX the largest horizontal whole-sample vector component
     * @param minY the smallest vertical whole-sample vector component
     * @param maxY the largest vertical whole-sample vector component
     * @return the whole-sample displacement, the horizontal component in the upper and the vertical in the lower 16
     *         bits
     */
    private static int refineCandidate(MotionPyramid sourcePyramid, MotionPyramid refPyramid, Frame source, Frame ref,
                                       int x, int y, int centerX, int centerY, int minX, int maxX, int minY,
                                       int maxY) {
        var hx = x >> 1;
        var hy = y >> 1;
        var hStride = sourcePyramid.halfWidth;
        var hMinX = Math.max(ceilDiv(minX, 2), -hx);
        var hMaxX = Math.min(maxX >> 1, sourcePyramid.halfWidth - 8 - hx);
        var hMinY = Math.max(ceilDiv(minY, 2), -hy);
        var hMaxY = Math.min(maxY >> 1, sourcePyramid.halfHeight - 8 - hy);
        var halfOffset = hy * hStride + hx;
        var bestX = clamp(centerX, hMinX, hMaxX);
        var bestY = clamp(centerY, hMinY, hMaxY);
        var best = Integer.MAX_VALUE;
        for (var dy = Math.max(hMinY, centerY - 2); dy <= Math.min(hMaxY, centerY + 2); dy++) {
            for (var dx = Math.max(hMinX, centerX - 2); dx <= Math.min(hMaxX, centerX + 2); dx++) {
                var sad = MotionPyramid.sad(sourcePyramid.half, halfOffset, refPyramid.half,
                        halfOffset + dy * hStride + dx, hStride, 8, best);
                if (sad < best) {
                    best = sad;
                    bestX = dx;
                    bestY = dy;
                }
            }
        }
        var stride = source.lumaStride;
        var sourceOffset = source.lumaOrigin + y * stride + x;
        var fullMinX = Math.max(minX, -x - 1);
        var fullMaxX = Math.min(maxX, source.mbWidth * 16 - 15 - x);
        var fullMinY = Math.max(minY, -y - 1);
        var fullMaxY = Math.min(maxY, source.mbHeight * 16 - 15 - y);
        var fx = clamp(2 * bestX, fullMinX, fullMaxX);
        var fy = clamp(2 * bestY, fullMinY, fullMaxY);
        var centerFx = fx;
        var centerFy = fy;
        best = Integer.MAX_VALUE;
        for (var dy = Math.max(fullMinY, centerFy - 1); dy <= Math.min(fullMaxY, centerFy + 1); dy++) {
            for (var dx = Math.max(fullMinX, centerFx - 1); dx <= Math.min(fullMaxX, centerFx + 1); dx++) {
                var sad = sad(source.y, sourceOffset, stride, ref.y, ref.lumaOrigin + (y + dy) * ref.lumaStride + x + dx,
                        ref.lumaStride, 16);
                if (sad < best) {
                    best = sad;
                    fx = dx;
                    fy = dy;
                }
            }
        }
        return fx << 16 | fy & 0xFFFF;
    }

    /**
     * Searches the vector of a block.
     *
     * @param source the source picture
     * @param ref the reference picture
     * @param x the block's left column
     * @param y the block's top row
     * @param size the block size, 8 or 16
     * @param candidates the start candidates as {@code x, y} pairs in vector units
     * @param candidateCount the number of candidates
     * @param predX the horizontal component of the vector predictor, for the vector cost
     * @param predY the vertical component of the vector predictor
     * @param range the largest whole-sample distance the coarse search covers around the best candidate
     */
    public void search(Frame source, Frame ref, int x, int y, int size, int[] candidates, int candidateCount,
                       int predX, int predY, int range) {
        var shift = quarter ? 2 : 1;
        var stride = source.lumaStride;
        var sourceOffset = source.lumaOrigin + y * stride + x;
        var minX = Math.max(ceilDiv(bounds[0], 1 << shift), -x - Frame.LUMA_BORDER + 1);
        var maxX = Math.min(bounds[1] >> shift, source.mbWidth * 16 + Frame.LUMA_BORDER - size - 1 - x);
        var minY = Math.max(ceilDiv(bounds[2], 1 << shift), -y - Frame.LUMA_BORDER + 1);
        var maxY = Math.min(bounds[3] >> shift, source.mbHeight * 16 + Frame.LUMA_BORDER - size - 1 - y);
        var bestFx = clamp(0, minX, maxX);
        var bestFy = clamp(0, minY, maxY);
        var best = fullCost(source, ref, sourceOffset, x, y, size, bestFx, bestFy, predX, predY, shift);
        for (var i = 0; i < candidateCount; i++) {
            var fx = clamp(candidates[2 * i] >> shift, minX, maxX);
            var fy = clamp(candidates[2 * i + 1] >> shift, minY, maxY);
            if (fx == bestFx && fy == bestFy) {
                continue;
            }
            var cost = fullCost(source, ref, sourceOffset, x, y, size, fx, fy, predX, predY, shift);
            if (cost < best) {
                best = cost;
                bestFx = fx;
                bestFy = fy;
            }
        }
        var step = Integer.highestOneBit(Math.max(1, range));
        for (; step >= 1; step >>= 1) {
            var centerX = bestFx;
            var centerY = bestFy;
            for (var dy = -step; dy <= step; dy += step) {
                for (var dx = -step; dx <= step; dx += step) {
                    var fx = centerX + dx;
                    var fy = centerY + dy;
                    if ((dx | dy) == 0 || fx < minX || fx > maxX || fy < minY || fy > maxY) {
                        continue;
                    }
                    var cost = fullCost(source, ref, sourceOffset, x, y, size, fx, fy, predX, predY, shift);
                    if (cost < best) {
                        best = cost;
                        bestFx = fx;
                        bestFy = fy;
                    }
                }
            }
        }
        for (var iteration = 0; iteration < 32; iteration++) {
            var centerX = bestFx;
            var centerY = bestFy;
            for (var k = 0; k < 4; k++) {
                var fx = centerX + (k == 0 ? -1 : k == 1 ? 1 : 0);
                var fy = centerY + (k == 2 ? -1 : k == 3 ? 1 : 0);
                if (fx < minX || fx > maxX || fy < minY || fy > maxY) {
                    continue;
                }
                var cost = fullCost(source, ref, sourceOffset, x, y, size, fx, fy, predX, predY, shift);
                if (cost < best) {
                    best = cost;
                    bestFx = fx;
                    bestFy = fy;
                }
            }
            if (bestFx == centerX && bestFy == centerY) {
                break;
            }
        }
        bestX = bestFx << shift;
        bestY = bestFy << shift;
        bestCost = best;
        bestSad = best - vectorCost(bestX - predX, bestY - predY);
        refine(source, ref, x, y, size, predX, predY, quarter ? 2 : 1);
        if (quarter) {
            refine(source, ref, x, y, size, predX, predY, 1);
        }
    }

    /**
     * Refines the best vector to its eight neighbors at a sub-sample step, keeping it when none costs less.
     *
     * @param source the source picture
     * @param ref the reference picture
     * @param x the block's left column
     * @param y the block's top row
     * @param size the block size
     * @param predX the horizontal predictor
     * @param predY the vertical predictor
     * @param step the step in vector units
     */
    public void refine(Frame source, Frame ref, int x, int y, int size, int predX, int predY, int step) {
        var centerX = bestX;
        var centerY = bestY;
        for (var dy = -step; dy <= step; dy += step) {
            for (var dx = -step; dx <= step; dx += step) {
                var vx = centerX + dx;
                var vy = centerY + dy;
                if ((dx | dy) == 0 || vx < bounds[0] || vx > bounds[1] || vy < bounds[2] || vy > bounds[3]) {
                    continue;
                }
                var sad = predictedSad(source, ref, x, y, size, vx, vy);
                var cost = sad + vectorCost(vx - predX, vy - predY);
                if (cost < bestCost) {
                    bestCost = cost;
                    bestSad = sad;
                    bestX = vx;
                    bestY = vy;
                }
            }
        }
    }

    /**
     * Evaluates one vector, making it the best when it costs less than the best so far.
     *
     * @param source the source picture
     * @param ref the reference picture
     * @param x the block's left column
     * @param y the block's top row
     * @param size the block size
     * @param vx the horizontal component
     * @param vy the vertical component
     * @param predX the horizontal predictor
     * @param predY the vertical predictor
     */
    public void consider(Frame source, Frame ref, int x, int y, int size, int vx, int vy, int predX, int predY) {
        if (vx < bounds[0] || vx > bounds[1] || vy < bounds[2] || vy > bounds[3]) {
            return;
        }
        var sad = predictedSad(source, ref, x, y, size, vx, vy);
        var cost = sad + vectorCost(vx - predX, vy - predY);
        if (cost < bestCost) {
            bestCost = cost;
            bestSad = sad;
            bestX = vx;
            bestY = vy;
        }
    }

    /**
     * Resets the best vector before a sequence of {@link #consider} calls.
     */
    public void clearBest() {
        bestCost = Integer.MAX_VALUE;
        bestSad = Integer.MAX_VALUE;
        bestX = 0;
        bestY = 0;
    }

    /**
     * Returns the sum of absolute differences between a source block and its prediction with a vector.
     *
     * @param source the source picture
     * @param ref the reference picture
     * @param x the block's left column
     * @param y the block's top row
     * @param size the block size, 8 or 16
     * @param vx the horizontal component
     * @param vy the vertical component
     * @return the sum
     */
    public int predictedSad(Frame source, Frame ref, int x, int y, int size, int vx, int vy) {
        compensator.luma(ref, prediction, 0, 16, x, y, size, vx, vy, quarter, rounding);
        return sad(source.y, source.lumaOrigin + y * source.lumaStride + x, source.lumaStride, prediction, 0, 16,
                size);
    }

    /**
     * Returns the sum of absolute differences between a 16x16 source block and the average of two predictions.
     *
     * @param source the source picture
     * @param forward the forward reference
     * @param backward the backward reference
     * @param x the block's left column
     * @param y the block's top row
     * @param forwardVectors the forward vectors of the four 8x8 blocks as {@code x, y} pairs
     * @param backwardVectors the backward vectors of the four 8x8 blocks
     * @param at the index of block 0's horizontal component in both vector arrays
     * @param fourVectors whether each 8x8 block has its own vector, or the vectors of block 0 apply to the whole
     * macroblock
     * @return the sum
     */
    public int bidirectionalSad(Frame source, Frame forward, Frame backward, int x, int y, int[] forwardVectors,
                                int[] backwardVectors, int at, boolean fourVectors) {
        if (fourVectors) {
            for (var block = 0; block < 4; block++) {
                var offset = (block >> 1) * 8 * 16 + (block & 1) * 8;
                var bx = x + (block & 1) * 8;
                var by = y + (block >> 1) * 8;
                compensator.luma(forward, prediction, offset, 16, bx, by, 8, forwardVectors[at + 2 * block],
                        forwardVectors[at + 2 * block + 1], quarter, 0);
                compensator.luma(backward, second, offset, 16, bx, by, 8, backwardVectors[at + 2 * block],
                        backwardVectors[at + 2 * block + 1], quarter, 0);
            }
        } else {
            compensator.luma(forward, prediction, 0, 16, x, y, 16, forwardVectors[at], forwardVectors[at + 1],
                    quarter, 0);
            compensator.luma(backward, second, 0, 16, x, y, 16, backwardVectors[at], backwardVectors[at + 1],
                    quarter, 0);
        }
        compensator.average(second, 0, 16, prediction, 0, 16, 16, 16);
        return sad(source.y, source.lumaOrigin + y * source.lumaStride + x, source.lumaStride, prediction, 0, 16, 16);
    }

    /**
     * Returns the estimated cost of a vector difference: the bits of its two components times the bit cost.
     *
     * @param dx the horizontal difference in vector units
     * @param dy the vertical difference in vector units
     * @return the cost
     */
    public int vectorCost(int dx, int dy) {
        return lambda * (componentBits(dx) + componentBits(dy));
    }

    /**
     * Estimates the bits of one vector difference component.
     *
     * @param d the difference
     * @return the estimate
     */
    private static int componentBits(int d) {
        if (d == 0) {
            return 1;
        }
        var magnitude = Math.abs(d);
        return 2 * (31 - Integer.numberOfLeadingZeros(magnitude)) + 3;
    }

    /**
     * Returns the cost of a whole-sample vector.
     *
     * @param source the source picture
     * @param ref the reference picture
     * @param sourceOffset the index of the source block's top-left sample
     * @param x the block's left column
     * @param y the block's top row
     * @param size the block size
     * @param fx the horizontal component in whole samples
     * @param fy the vertical component in whole samples
     * @param predX the horizontal predictor in vector units
     * @param predY the vertical predictor in vector units
     * @param shift the base-2 logarithm of the vector units per sample
     * @return the cost
     */
    private int fullCost(Frame source, Frame ref, int sourceOffset, int x, int y, int size, int fx, int fy, int predX,
                         int predY, int shift) {
        var stride = ref.lumaStride;
        var sad = sad(source.y, sourceOffset, source.lumaStride, ref.y, ref.lumaOrigin + (y + fy) * stride + x + fx,
                stride, size);
        return sad + vectorCost((fx << shift) - predX, (fy << shift) - predY);
    }

    /**
     * Returns the sum of absolute differences of two square blocks.
     *
     * @param a the first plane
     * @param aOffset the index of the first block's top-left sample
     * @param aStride the first plane's row stride
     * @param b the second plane
     * @param bOffset the index of the second block's top-left sample
     * @param bStride the second plane's row stride
     * @param size the block size
     * @return the sum
     */
    public static int sad(byte[] a, int aOffset, int aStride, byte[] b, int bOffset, int bStride, int size) {
        var sum = 0;
        for (var row = 0; row < size; row++) {
            var ai = aOffset + row * aStride;
            var bi = bOffset + row * bStride;
            for (var col = 0; col < size; col++) {
                sum += Math.abs((a[ai + col] & 0xFF) - (b[bi + col] & 0xFF));
            }
        }
        return sum;
    }

    /**
     * Divides rounding toward positive infinity.
     *
     * @param value the dividend
     * @param divisor the divisor, positive
     * @return the quotient
     */
    private static int ceilDiv(int value, int divisor) {
        return -Math.floorDiv(-value, divisor);
    }

    /**
     * Clamps a value.
     *
     * @param value the component to clamp
     * @param low the lower bound
     * @param high the upper bound
     * @return the clamped value
     */
    private static int clamp(int value, int low, int high) {
        return value < low ? low : Math.min(value, high);
    }
}
