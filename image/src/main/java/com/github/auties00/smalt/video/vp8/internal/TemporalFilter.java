/* Ports libvpx 1.15.1 (vp8/encoder/temporal_filter.c; vp8/encoder/mcomp.c: vp8_hex_search,
   vp8_find_best_sub_pixel_step_iteratively). Copyright (c) 2010 The WebM project authors; BSD 3-Clause License.
   Modified for Smalt. */
package com.github.auties00.smalt.video.vp8.internal;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.internal.task.ParallelBands;

/**
 * Builds the picture of a VP8 alternate reference by averaging the pictures around its source,
 * each macroblock weighted by how well it matches the source's after motion compensation, and
 * each sample by how close it is to the source's sample.
 *
 * <p>Macroblock rows are filtered on up to the given number of threads; every macroblock depends
 * only on the pictures, so the result does not depend on the threads. Instances are used by one
 * thread at a time.
 */
final class TemporalFilter {
    /**
     * The motion compensated error below which a macroblock takes the full weight.
     */
    private static final int THRESH_LOW = 10000;

    /**
     * The motion compensated error below which a macroblock takes half the weight.
     */
    private static final int THRESH_HIGH = 20000;

    /**
     * The motion search margin past the frame edge, in pixels: the 16-pixel source border less
     * the reach of the six-tap filter.
     */
    private static final int MARGIN = 16 - 5;

    /**
     * The offsets of the hexagon around the best point, libvpx's {@code hex} as row, column
     * pairs.
     */
    private static final int[] HEX = {-1, -2, 1, -2, 2, 0, 1, 2, -1, 2, -2, 0};

    /**
     * The three new points of each hexagon after a move, libvpx's {@code next_chkpts}, as
     * {@code [direction 6][point 3][row, column]}.
     */
    private static final int[] NEXT_CHECK_POINTS = {
            -2, 0, -1, -2, 1, -2,
            -1, -2, 1, -2, 2, 0,
            1, -2, 2, 0, 1, 2,
            2, 0, 1, 2, -1, 2,
            1, 2, -1, 2, -2, 0,
            -1, 2, -2, 0, -1, -2
    };

    /**
     * The four neighbors of the final small search, libvpx's {@code neighbors} as row, column
     * pairs.
     */
    private static final int[] NEIGHBORS = {0, -1, -1, 0, 1, 0, 0, 1};

    /**
     * The reciprocals of the sample weights, {@code 0x80000 / i}, libvpx's {@code fixed_divide}.
     */
    private static final int[] FIXED_DIVIDE = new int[512];

    static {
        for (var i = 1; i < 512; i++) {
            FIXED_DIVIDE[i] = 0x80000 / i;
        }
    }

    /**
     * The working state of one band of rows.
     */
    private static final class Band {
        /**
         * The weighted sums of each sample of a macroblock: 256 luma, then 64 U and 64 V.
         */
        final int[] accumulator = new int[384];

        /**
         * The weights of each sample of a macroblock.
         */
        final int[] count = new int[384];

        /**
         * The motion compensated prediction of a macroblock, laid out like {@link #accumulator}.
         */
        final byte[] predictor = new byte[384];

        /**
         * The scratch of the six-tap filter.
         */
        final byte[] mcScratch = new byte[Vp8Dsp.SCRATCH_SIZE];

        /**
         * The scratch of the sub-pixel variance.
         */
        final short[] subpelScratch = new short[RtDsp.SCRATCH_SIZE];

        /**
         * The motion vector found by the last search, packed in 1/8 pixel.
         */
        int mv;
    }

    /**
     * The number of macroblock columns.
     */
    private final int mbCols;

    /**
     * The number of macroblock rows.
     */
    private final int mbRows;

    /**
     * The filtered picture.
     */
    private final FrameBuffer output;

    /**
     * The prediction kernels.
     */
    private final Vp8Dsp dsp = Vp8Dsp.INSTANCE;

    /**
     * The block metric kernels.
     */
    private final RtDsp rtDsp = RtDsp.INSTANCE;

    /**
     * The state of each band.
     */
    private Band[] bands = {new Band()};

    /**
     * The filtering of one band of rows.
     */
    private final ParallelBands.Band body = this::band;

    /**
     * The pictures of the filtering in progress, in display order.
     */
    private FrameBuffer[] frames;

    /**
     * The number of pictures of the filtering in progress.
     */
    private int frameCount;

    /**
     * The index of the alternate reference's source in {@link #frames}.
     */
    private int center;

    /**
     * The strength of the filtering in progress.
     */
    private int strength;

    /**
     * The number of bands of the filtering in progress.
     */
    private int bandCount;

    /**
     * Creates a filter of a picture size.
     *
     * @param mbCols the number of macroblock columns
     * @param mbRows the number of macroblock rows
     * @param limits the allocation limits
     */
    TemporalFilter(int mbCols, int mbRows, MediaLimits limits) {
        this.mbCols = mbCols;
        this.mbRows = mbRows;
        this.output = new FrameBuffer(mbCols, mbRows, limits);
    }

    /**
     * Returns the range of pictures an alternate reference averages around its source
     * ({@code vp8_temporal_filter_prepare_c} with the centered blur).
     *
     * @param distance the distance of the source from the first picture available
     * @param available the number of pictures available from the first
     * @param maxFrames the most pictures averaged, the source included
     * @return the pictures before the source in the low 8 bits and the pictures after it in the
     * next 8 bits
     */
    static int plan(int distance, int available, int maxFrames) {
        var backward = distance;
        var forward = available - (distance + 1);
        if (forward > backward) {
            forward = backward;
        }
        if (backward > forward) {
            backward = forward;
        }
        if (forward > (maxFrames - 1) / 2) {
            forward = (maxFrames - 1) / 2;
        }
        if (backward > maxFrames / 2) {
            backward = maxFrames / 2;
        }
        return Math.max(backward, 0) | (Math.max(forward, 0) << 8);
    }

    /**
     * Filters pictures into the alternate reference picture ({@code vp8_temporal_filter_iterate_c}).
     *
     * @param frames the pictures in display order, with extended borders
     * @param count the number of pictures
     * @param center the index of the alternate reference's source
     * @param strength the filter strength, from 0 to 6
     * @param threads the most threads to use
     * @return the filtered picture, valid until the next call, with extended borders
     */
    FrameBuffer filter(FrameBuffer[] frames, int count, int center, int strength, int threads) {
        this.frames = frames;
        this.frameCount = count;
        this.center = center;
        this.strength = strength;
        bandCount = ParallelBands.enabled() ? Math.max(1, Math.min(threads, mbRows)) : 1;
        if (bands.length < bandCount) {
            var grown = java.util.Arrays.copyOf(bands, bandCount);
            for (var i = bands.length; i < bandCount; i++) {
                grown[i] = new Band();
            }
            bands = grown;
        }
        try {
            if (bandCount > 1) {
                ParallelBands.run(bandCount, body);
            } else {
                band(0);
            }
        } finally {
            this.frames = null;
        }
        for (var r = 0; r < mbRows; r++) {
            output.extendRowSides(r);
        }
        output.extendTopBottom();
        return output;
    }

    /**
     * Filters the rows of one band.
     *
     * @param band the index of the band, from zero
     */
    private void band(int band) {
        var state = bands[band];
        var end = ParallelBands.start(band + 1, bandCount, mbRows);
        for (var r = ParallelBands.start(band, bandCount, mbRows); r < end; r++) {
            for (var c = 0; c < mbCols; c++) {
                macroblock(state, r, c);
            }
        }
    }

    /**
     * Filters one macroblock: weights each picture's motion compensated match of the macroblock
     * and averages the samples into {@link #output}.
     *
     * @param state the band's state
     * @param mbRow the macroblock row
     * @param mbCol the macroblock column
     */
    private void macroblock(Band state, int mbRow, int mbCol) {
        var acc = state.accumulator;
        var cnt = state.count;
        var pred = state.predictor;
        java.util.Arrays.fill(acc, 0);
        java.util.Arrays.fill(cnt, 0);
        var source = frames[center];
        var yStride = source.yStride;
        var uvStride = source.uvStride;
        var yOff = source.yOrigin + mbRow * 16 * yStride + mbCol * 16;
        var uvOff = source.uvOrigin + mbRow * 8 * uvStride + mbCol * 8;
        var rowMin = -((mbRow * 16) + MARGIN);
        var rowMax = ((mbRows - 1 - mbRow) * 16) + MARGIN;
        var colMin = -((mbCol * 16) + MARGIN);
        var colMax = ((mbCols - 1 - mbCol) * 16) + MARGIN;
        for (var f = 0; f < frameCount; f++) {
            var frame = frames[f];
            int weight;
            var mv = 0;
            if (f == center) {
                weight = 2;
            } else {
                var err = findMatchingMb(state, source.y, frame.y, yOff, yStride, rowMin, rowMax, colMin, colMax);
                mv = state.mv;
                weight = err < THRESH_LOW ? 2 : err < THRESH_HIGH ? 1 : 0;
            }
            if (weight == 0) {
                continue;
            }
            var mvRow = Mv.row(mv);
            var mvCol = Mv.col(mv);
            dsp.sixtap(frame.y, yOff + (mvRow >> 3) * yStride + (mvCol >> 3), yStride, pred, 0, 16, 16, 16,
                    mvCol & 7, mvRow & 7, state.mcScratch);
            var cRow = mvRow >> 1;
            var cCol = mvCol >> 1;
            var cOff = uvOff + (cRow >> 3) * uvStride + (cCol >> 3);
            dsp.sixtap(frame.u, cOff, uvStride, pred, 256, 8, 8, 8, cCol & 7, cRow & 7, state.mcScratch);
            dsp.sixtap(frame.v, cOff, uvStride, pred, 320, 8, 8, 8, cCol & 7, cRow & 7, state.mcScratch);
            apply(source.y, yOff, yStride, pred, 0, 16, weight, acc, cnt);
            apply(source.u, uvOff, uvStride, pred, 256, 8, weight, acc, cnt);
            apply(source.v, uvOff, uvStride, pred, 320, 8, weight, acc, cnt);
        }
        var out = output;
        var dy = out.yOrigin + mbRow * 16 * out.yStride + mbCol * 16;
        for (int r = 0, k = 0; r < 16; r++) {
            var row = dy + r * out.yStride;
            for (var c = 0; c < 16; c++, k++) {
                out.y[row + c] = (byte) (((long) (acc[k] + (cnt[k] >> 1)) * FIXED_DIVIDE[cnt[k]]) >> 19);
            }
        }
        var duv = out.uvOrigin + mbRow * 8 * out.uvStride + mbCol * 8;
        for (int r = 0, k = 256; r < 8; r++) {
            var row = duv + r * out.uvStride;
            for (var c = 0; c < 8; c++, k++) {
                var m = k + 64;
                out.u[row + c] = (byte) (((long) (acc[k] + (cnt[k] >> 1)) * FIXED_DIVIDE[cnt[k]]) >> 19);
                out.v[row + c] = (byte) (((long) (acc[m] + (cnt[m] >> 1)) * FIXED_DIVIDE[cnt[m]]) >> 19);
            }
        }
    }

    /**
     * Adds a predicted block to the weighted sums ({@code vp8_temporal_filter_apply_c}).
     *
     * @param source the plane of the alternate reference's source
     * @param sourceOff the index of the block in it
     * @param stride its row stride
     * @param pred the prediction
     * @param predOff the index of the block in the prediction and the sums
     * @param size the block side, 16 or 8
     * @param weight the block's weight, 1 or 2
     * @param acc the weighted sums
     * @param cnt the weights
     */
    private void apply(byte[] source, int sourceOff, int stride, byte[] pred, int predOff, int size, int weight,
                       int[] acc, int[] cnt) {
        var rounding = strength > 0 ? 1 << (strength - 1) : 0;
        var k = predOff;
        for (var r = 0; r < size; r++) {
            var row = sourceOff + r * stride;
            for (var c = 0; c < size; c++, k++) {
                var pixel = pred[k] & 0xFF;
                var modifier = (source[row + c] & 0xFF) - pixel;
                modifier *= modifier;
                modifier *= 3;
                modifier += rounding;
                modifier >>= strength;
                if (modifier > 16) {
                    modifier = 16;
                }
                modifier = (16 - modifier) * weight;
                cnt[k] += modifier;
                acc[k] += modifier * pixel;
            }
        }
    }

    /**
     * Searches the best match of a macroblock of the source in another picture, by a hexagon
     * search and the iterative fractional refinement without vector costs
     * ({@code vp8_temporal_filter_find_matching_mb_c}).
     *
     * @param state the band's state, whose {@code mv} receives the vector in 1/8 pixel
     * @param source the luma plane of the alternate reference's source
     * @param frame the luma plane of the picture searched
     * @param off the index of the macroblock in both planes
     * @param stride the row stride of both
     * @param rowMin the smallest row of the search window, in full pixels
     * @param rowMax the largest row of the search window
     * @param colMin the smallest column of the search window
     * @param colMax the largest column of the search window
     * @return the variance of the match
     */
    private int findMatchingMb(Band state, byte[] source, byte[] frame, int off, int stride, int rowMin, int rowMax,
                               int colMin, int colMax) {
        var br = Math.clamp(0, rowMin, rowMax);
        var bc = Math.clamp(0, colMin, colMax);
        var bestSad = rtDsp.sad16(source, off, stride, frame, off + br * stride + bc, stride);
        var bestSite = -1;
        for (var i = 0; i < 6; i++) {
            var r = br + HEX[2 * i];
            var c = bc + HEX[2 * i + 1];
            if (c < colMin || c > colMax || r < rowMin || r > rowMax) {
                continue;
            }
            var sad = rtDsp.sad16(source, off, stride, frame, off + r * stride + c, stride);
            if (sad < bestSad) {
                bestSad = sad;
                bestSite = i;
            }
        }
        if (bestSite != -1) {
            br += HEX[2 * bestSite];
            bc += HEX[2 * bestSite + 1];
            var k = bestSite;
            for (var j = 1; j < 127; j++) {
                bestSite = -1;
                for (var i = 0; i < 3; i++) {
                    var r = br + NEXT_CHECK_POINTS[k * 6 + 2 * i];
                    var c = bc + NEXT_CHECK_POINTS[k * 6 + 2 * i + 1];
                    if (c < colMin || c > colMax || r < rowMin || r > rowMax) {
                        continue;
                    }
                    var sad = rtDsp.sad16(source, off, stride, frame, off + r * stride + c, stride);
                    if (sad < bestSad) {
                        bestSad = sad;
                        bestSite = i;
                    }
                }
                if (bestSite == -1) {
                    break;
                }
                br += NEXT_CHECK_POINTS[k * 6 + 2 * bestSite];
                bc += NEXT_CHECK_POINTS[k * 6 + 2 * bestSite + 1];
                k += 5 + bestSite;
                if (k >= 12) {
                    k -= 12;
                } else if (k >= 6) {
                    k -= 6;
                }
            }
        }
        for (var j = 0; j < 8; j++) {
            bestSite = -1;
            for (var i = 0; i < 4; i++) {
                var r = br + NEIGHBORS[2 * i];
                var c = bc + NEIGHBORS[2 * i + 1];
                if (c < colMin || c > colMax || r < rowMin || r > rowMax) {
                    continue;
                }
                var sad = rtDsp.sad16(source, off, stride, frame, off + r * stride + c, stride);
                if (sad < bestSad) {
                    bestSad = sad;
                    bestSite = i;
                }
            }
            if (bestSite == -1) {
                break;
            }
            br += NEIGHBORS[2 * bestSite];
            bc += NEIGHBORS[2 * bestSite + 1];
        }
        return subpel(state, source, frame, off, stride, br, bc, rowMin, rowMax, colMin, colMax);
    }

    /**
     * Refines a full-pixel match by half and quarter pixel steps, libvpx's
     * {@code vp8_find_best_sub_pixel_step_iteratively} without vector costs.
     *
     * @param state the band's state, whose {@code mv} receives the vector in 1/8 pixel
     * @param source the luma plane of the alternate reference's source
     * @param frame the luma plane of the picture searched
     * @param off the index of the macroblock in both planes
     * @param stride the row stride of both
     * @param row the full-pixel row of the match
     * @param col the full-pixel column of the match
     * @param rowMin the smallest row of the search window, in full pixels
     * @param rowMax the largest row of the search window
     * @param colMin the smallest column of the search window
     * @param colMax the largest column of the search window
     * @return the variance of the refined match
     */
    private int subpel(Band state, byte[] source, byte[] frame, int off, int stride, int row, int col, int rowMin,
                       int rowMax, int colMin, int colMax) {
        var minc = Math.max(colMin * 4, -((1 << 10) - 1));
        var maxc = Math.min(colMax * 4, (1 << 10) - 1);
        var minr = Math.max(rowMin * 4, -((1 << 10) - 1));
        var maxr = Math.min(rowMax * 4, (1 << 10) - 1);
        var br = row * 4;
        var bc = col * 4;
        long bestErr = RtDsp.variance256(rtDsp.variance16(frame, off + row * stride + col, stride, source, off, stride));
        var tr = br;
        var tc = bc;
        for (var pass = 0; pass < 2; pass++) {
            var step = pass == 0 ? 2 : 1;
            for (var iter = 1; iter < 4; iter++) {
                var left = score(state, source, frame, off, stride, tr, tc - step, minr, maxr, minc, maxc);
                if (left < bestErr) {
                    bestErr = left;
                    br = tr;
                    bc = tc - step;
                }
                var right = score(state, source, frame, off, stride, tr, tc + step, minr, maxr, minc, maxc);
                if (right < bestErr) {
                    bestErr = right;
                    br = tr;
                    bc = tc + step;
                }
                var up = score(state, source, frame, off, stride, tr - step, tc, minr, maxr, minc, maxc);
                if (up < bestErr) {
                    bestErr = up;
                    br = tr - step;
                    bc = tc;
                }
                var down = score(state, source, frame, off, stride, tr + step, tc, minr, maxr, minc, maxc);
                if (down < bestErr) {
                    bestErr = down;
                    br = tr + step;
                    bc = tc;
                }
                var dr = up < down ? -step : step;
                var dc = left < right ? -step : step;
                var diag = score(state, source, frame, off, stride, tr + dr, tc + dc, minr, maxr, minc, maxc);
                if (diag < bestErr) {
                    bestErr = diag;
                    br = tr + dr;
                    bc = tc + dc;
                }
                if (tr == br && tc == bc) {
                    break;
                }
                tr = br;
                tc = bc;
            }
        }
        state.mv = Mv.pack(br * 2, bc * 2);
        return (int) Math.min(bestErr, Integer.MAX_VALUE);
    }

    /**
     * Returns the variance of a fractional match, or {@link Long#MAX_VALUE} outside the window
     * (the {@code CHECK_BETTER} of {@code vp8_find_best_sub_pixel_step_iteratively}).
     *
     * @param state the band's state
     * @param source the luma plane of the alternate reference's source
     * @param frame the luma plane of the picture searched
     * @param off the index of the macroblock in both planes
     * @param stride the row stride of both
     * @param r the row in quarter pixels
     * @param c the column in quarter pixels
     * @param minr the smallest row of the window, in quarter pixels
     * @param maxr the largest row of the window
     * @param minc the smallest column of the window
     * @param maxc the largest column of the window
     * @return the variance, or {@link Long#MAX_VALUE}
     */
    private long score(Band state, byte[] source, byte[] frame, int off, int stride, int r, int c, int minr,
                       int maxr, int minc, int maxc) {
        if (c < minc || c > maxc || r < minr || r > maxr) {
            return Long.MAX_VALUE;
        }
        var refOff = off + (r >> 2) * stride + (c >> 2);
        var packed = rtDsp.subpelVariance16(frame, refOff, stride, (c & 3) << 1, (r & 3) << 1, source, off, stride,
                state.subpelScratch);
        return RtDsp.variance256(packed);
    }
}
