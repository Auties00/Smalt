/* Ports libwebp 1.6.0 (src/enc/filter_enc.c, src/dsp/ssim.c). Copyright (c) 2011 Google Inc.; BSD 3-Clause
   License. Modified for Smalt. */
package com.github.auties00.smalt.video.vp8.internal;

import com.github.auties00.smalt.internal.task.ParallelBands;

import java.util.Arrays;

/**
 * Chooses the loop filter strength of every segment of a key frame by filtering the inner edges of
 * each reconstructed macroblock at the levels around the segment's strength and keeping the level
 * whose structural similarity to the source is highest ({@code VP8StoreFilterStats} and
 * {@code VP8AdjustFilterStrength}).
 *
 * <p>The encoder hands over the unfiltered reconstruction of every macroblock in raster order
 * with {@link #store}; the reconstructions are kept until {@link #flush} folds them into the
 * per-segment similarity sums, in raster order for every sum, so the sums and the chosen levels
 * do not depend on the number of threads. Instances are not thread-safe.
 */
final class LoopFilterSearch {
    /**
     * The number of loop filter levels.
     */
    private static final int MAX_LF_LEVELS = 64;

    /**
     * The half size of the similarity window.
     */
    private static final int SSIM_KERNEL = 3;

    /**
     * The weights of the similarity window, which sum to 16.
     */
    private static final int[] SSIM_WEIGHT = {1, 2, 3, 4, 3, 2, 1};

    /**
     * The bytes of one stored reconstruction.
     */
    private static final int MB_BYTES = EncIterator.YUV_SIZE;

    /**
     * The encoder whose macroblocks are measured.
     */
    private final Vp8KeyFrameEncoder enc;

    /**
     * The segment of each macroblock.
     */
    private final byte[] mbSegment;

    /**
     * The type of each macroblock.
     */
    private final byte[] mbType;

    /**
     * Whether each macroblock has no coefficients.
     */
    private final byte[] mbSkip;

    /**
     * The loop filter sharpness of the configuration.
     */
    private final int sharpness;

    /**
     * Whether the simple filter is signaled.
     */
    private final boolean simple;

    /**
     * The similarity sums, {@code [segment][level]}.
     */
    private final double[] stats = new double[4 * MAX_LF_LEVELS];

    /**
     * The stored reconstructions, {@link #MB_BYTES} per macroblock from {@link #start}.
     */
    private final byte[] pending;

    /**
     * The number of macroblocks {@link #pending} holds.
     */
    private final int capacity;

    /**
     * The first macroblock not folded into {@link #stats}.
     */
    private int start;

    /**
     * Constructs a search.
     *
     * @param enc the encoder
     * @param mbSegment the segment of each macroblock
     * @param mbType the type of each macroblock
     * @param mbSkip whether each macroblock has no coefficients
     * @param sharpness the configured loop filter sharpness, from 0 to 7
     * @param simple whether the simple filter is signaled
     * @param capacity the number of macroblocks stored between two flushes, at least 1
     */
    LoopFilterSearch(Vp8KeyFrameEncoder enc, byte[] mbSegment, byte[] mbType, byte[] mbSkip, int sharpness,
                     boolean simple, int capacity) {
        this.enc = enc;
        this.mbSegment = mbSegment;
        this.mbType = mbType;
        this.mbSkip = mbSkip;
        this.sharpness = sharpness;
        this.simple = simple;
        this.capacity = capacity;
        this.pending = new byte[capacity * MB_BYTES];
    }

    /**
     * Clears the sums and the stored reconstructions ({@code VP8InitFilter}).
     */
    void reset() {
        Arrays.fill(stats, 0);
        start = 0;
    }

    /**
     * {@return the number of macroblocks that can be stored before a flush is due}
     */
    int capacity() {
        return capacity;
    }

    /**
     * {@return the first macroblock not folded into the sums yet}
     */
    int start() {
        return start;
    }

    /**
     * Stores the reconstruction of a macroblock; thread-safe for distinct macroblocks.
     *
     * @param mb the macroblock index, from {@link #start()} to {@code start() + capacity() - 1}
     * @param yuvOut the reconstruction work buffer
     */
    void store(int mb, byte[] yuvOut) {
        System.arraycopy(yuvOut, 0, pending, (mb - start) * MB_BYTES, MB_BYTES);
    }

    /**
     * Folds the stored macroblocks before {@code end} into the sums.
     *
     * @param end the macroblock after the last stored one
     * @param dqm the segment parameters, read for the strength and quantizer of each segment
     * @param numSegments the number of segments in use
     * @param maxThreads the most threads to run on, the calling thread included
     */
    void flush(int end, SegmentInfo[] dqm, int numSegments, int maxThreads) {
        if (end <= start) {
            return;
        }
        var tasks = new int[numSegments * (MAX_LF_LEVELS + 1)];
        var count = 0;
        for (var s = 0; s < numSegments; s++) {
            tasks[count++] = s * MAX_LF_LEVELS;
            var level0 = dqm[s].fstrength;
            var deltaMin = -dqm[s].quant;
            var deltaMax = dqm[s].quant;
            var step = deltaMax - deltaMin >= 4 ? 4 : 1;
            for (var d = deltaMin; d <= deltaMax; d += step) {
                var level = level0 + d;
                if (level > 0 && level < MAX_LF_LEVELS) {
                    tasks[count++] = s * MAX_LF_LEVELS + level;
                }
            }
        }
        var first = start;
        var taskCount = count;
        var bands = Math.min(maxThreads, ParallelBands.bands(taskCount, (long) taskCount * (end - first) * 8192));
        ParallelBands.run(bands, band -> {
            var it = new EncIterator(enc);
            var filtered = new byte[MB_BYTES];
            for (var t = ParallelBands.start(band, bands, taskCount); t < ParallelBands.start(band + 1, bands,
                    taskCount); t++) {
                var slot = tasks[t];
                var segment = slot / MAX_LF_LEVELS;
                var level = slot % MAX_LF_LEVELS;
                var sum = stats[slot];
                for (var mb = first; mb < end; mb++) {
                    if (mbSegment[mb] != segment || (mbType[mb] == 1 && mbSkip[mb] != 0)) {
                        continue;
                    }
                    it.seek(mb / enc.mbW, mb % enc.mbW);
                    it.importSamples(null);
                    var recon = (mb - first) * MB_BYTES;
                    if (level == 0) {
                        sum += mbSsim(it.yuvIn, pending, recon);
                    } else {
                        System.arraycopy(pending, recon, filtered, 0, MB_BYTES);
                        filterInnerEdges(filtered, level);
                        sum += mbSsim(it.yuvIn, filtered, 0);
                    }
                }
                stats[slot] = sum;
            }
        });
        start = end;
    }

    /**
     * Sets the filter strength of every segment to the level with the highest similarity sum, at
     * least 1e-5 relatively above the unfiltered sum, or 0.
     *
     * @param dqm the segment parameters to update
     */
    void choose(SegmentInfo[] dqm) {
        for (var s = 0; s < 4; s++) {
            var bestLevel = 0;
            var bestValue = 1.00001 * stats[s * MAX_LF_LEVELS];
            for (var i = 1; i < MAX_LF_LEVELS; i++) {
                var value = stats[s * MAX_LF_LEVELS + i];
                if (value > bestValue) {
                    bestValue = value;
                    bestLevel = i;
                }
            }
            dqm[s].fstrength = bestLevel;
        }
    }

    /**
     * Returns the interior limit of a filter level ({@code GetILevel}).
     *
     * @param sharpness the sharpness, from 0 to 7
     * @param level the filter level
     * @return the interior limit, at least 1
     */
    private static int interiorLevel(int sharpness, int level) {
        if (sharpness > 0) {
            level >>= sharpness > 4 ? 2 : 1;
            if (level > 9 - sharpness) {
                level = 9 - sharpness;
            }
        }
        return Math.max(level, 1);
    }

    /**
     * Filters the inner edges of a macroblock work buffer at one level ({@code DoFilter}).
     *
     * @param yuv the work buffer, filtered in place
     * @param level the filter level, from 1 to 63
     */
    private void filterInnerEdges(byte[] yuv, int level) {
        var ilevel = interiorLevel(sharpness, level);
        var limit = 2 * level + ilevel;
        var bps = EncIterator.BPS;
        if (simple) {
            for (var x = 4; x < 16; x += 4) {
                ScalarVp8Dsp.simpleEdge(yuv, EncIterator.Y_OFF + x, 1, bps, limit);
            }
            for (var y = 4; y < 16; y += 4) {
                ScalarVp8Dsp.simpleEdge(yuv, EncIterator.Y_OFF + y * bps, bps, 1, limit);
            }
            return;
        }
        var hevThreshold = level >= 40 ? 2 : level >= 15 ? 1 : 0;
        for (var x = 4; x < 16; x += 4) {
            ScalarVp8Dsp.subEdge(yuv, EncIterator.Y_OFF + x, 1, bps, 16, limit, ilevel, hevThreshold);
        }
        ScalarVp8Dsp.subEdge(yuv, EncIterator.U_OFF + 4, 1, bps, 8, limit, ilevel, hevThreshold);
        ScalarVp8Dsp.subEdge(yuv, EncIterator.V_OFF + 4, 1, bps, 8, limit, ilevel, hevThreshold);
        for (var y = 4; y < 16; y += 4) {
            ScalarVp8Dsp.subEdge(yuv, EncIterator.Y_OFF + y * bps, bps, 1, 16, limit, ilevel, hevThreshold);
        }
        ScalarVp8Dsp.subEdge(yuv, EncIterator.U_OFF + 4 * bps, bps, 1, 8, limit, ilevel, hevThreshold);
        ScalarVp8Dsp.subEdge(yuv, EncIterator.V_OFF + 4 * bps, bps, 1, 8, limit, ilevel, hevThreshold);
    }

    /**
     * Returns the summed similarity of the 10x10 inner luma windows and the 6x6 inner windows of
     * each chroma plane ({@code GetMBSSIM}).
     *
     * @param in the source work buffer
     * @param out the reconstruction
     * @param outOff the index of the reconstruction's work buffer in {@code out}
     * @return the sum
     */
    static double mbSsim(byte[] in, byte[] out, int outOff) {
        var sum = 0.0;
        for (var y = SSIM_KERNEL; y < 16 - SSIM_KERNEL; y++) {
            for (var x = SSIM_KERNEL; x < 16 - SSIM_KERNEL; x++) {
                sum += ssimClipped(in, EncIterator.Y_OFF, out, outOff + EncIterator.Y_OFF, x, y, 16, 16);
            }
        }
        for (var x = 1; x < 7; x++) {
            for (var y = 1; y < 7; y++) {
                sum += ssimClipped(in, EncIterator.U_OFF, out, outOff + EncIterator.U_OFF, x, y, 8, 8);
                sum += ssimClipped(in, EncIterator.V_OFF, out, outOff + EncIterator.V_OFF, x, y, 8, 8);
            }
        }
        return sum;
    }

    /**
     * Returns the similarity of two blocks in the window centered on a sample, clipped to the
     * blocks ({@code SSIMGetClipped_C}).
     *
     * @param a the first buffer, with rows of {@link EncIterator#BPS} bytes
     * @param aOff the index of the first block
     * @param b the second buffer, with rows of {@link EncIterator#BPS} bytes
     * @param bOff the index of the second block
     * @param xo the window center column
     * @param yo the window center row
     * @param w the block width
     * @param h the block height
     * @return the similarity, from 0 to 1
     */
    private static double ssimClipped(byte[] a, int aOff, byte[] b, int bOff, int xo, int yo, int w, int h) {
        var ymin = Math.max(yo - SSIM_KERNEL, 0);
        var ymax = Math.min(yo + SSIM_KERNEL, h - 1);
        var xmin = Math.max(xo - SSIM_KERNEL, 0);
        var xmax = Math.min(xo + SSIM_KERNEL, w - 1);
        long sw = 0;
        long xm = 0;
        long ym = 0;
        long xxm = 0;
        long xym = 0;
        long yym = 0;
        for (var y = ymin; y <= ymax; y++) {
            var ra = aOff + y * EncIterator.BPS;
            var rb = bOff + y * EncIterator.BPS;
            var wy = SSIM_WEIGHT[SSIM_KERNEL + y - yo];
            for (var x = xmin; x <= xmax; x++) {
                var weight = SSIM_WEIGHT[SSIM_KERNEL + x - xo] * wy;
                var s1 = a[ra + x] & 0xFF;
                var s2 = b[rb + x] & 0xFF;
                sw += weight;
                xm += weight * s1;
                ym += weight * s2;
                xxm += (long) weight * s1 * s1;
                xym += (long) weight * s1 * s2;
                yym += (long) weight * s2 * s2;
            }
        }
        return ssim(sw, xm, ym, xxm, xym, yym);
    }

    /**
     * Returns the similarity of accumulated window statistics ({@code SSIMCalculation}).
     *
     * @param n the sum of the weights
     * @param xm the weighted sum of the first samples
     * @param ym the weighted sum of the second samples
     * @param xxm the weighted sum of the squared first samples
     * @param xym the weighted sum of the sample products
     * @param yym the weighted sum of the squared second samples
     * @return the similarity, from 0 to 1, or 1 for a dark window
     */
    private static double ssim(long n, long xm, long ym, long xxm, long xym, long yym) {
        var w2 = n * n;
        var c1 = 20 * w2;
        var c2 = 60 * w2;
        var c3 = 64 * w2;
        var xmxm = xm * xm;
        var ymym = ym * ym;
        if (xmxm + ymym < c3) {
            return 1.0;
        }
        var xmym = xm * ym;
        var sxy = xym * n - xmym;
        var sxx = xxm * n - xmxm;
        var syy = yym * n - ymym;
        var numS = (2 * Math.max(sxy, 0) + c2) >>> 8;
        var denS = (sxx + syy + c2) >>> 8;
        var fnum = (2 * xmym + c1) * numS;
        var fden = (xmxm + ymym + c1) * denS;
        return (double) fnum / fden;
    }
}
