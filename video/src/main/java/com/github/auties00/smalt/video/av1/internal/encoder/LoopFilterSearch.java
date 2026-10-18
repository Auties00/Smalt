package com.github.auties00.smalt.video.av1.internal.encoder;

import com.github.auties00.smalt.internal.task.ParallelBands;
import com.github.auties00.smalt.video.av1.internal.EncoderBridge;
import com.github.auties00.smalt.video.av1.internal.SequenceHeader;

import java.util.Arrays;

/**
 * Picks the deblocking filter levels of a frame: either the levels its quantizer
 * implies, or the levels whose deblocked reconstruction has the smallest squared error against the
 * source, found by a step-halving search of the luma levels of each edge direction and of each
 * chroma plane's level.
 *
 * <p>Every block of a frame without segmentation or level deltas filters with the frame's levels,
 * so a trial rewrites the levels of the frame's level cache, deblocks the whole reconstruction,
 * measures it and restores it.
 *
 * @implNote This implementation ports libaom's {@code search_filter_level} and the full-image
 * branch of {@code av1_pick_filter_level} ({@code av1/encoder/picklpf.c}), with the
 * sharpness bias that favors lower levels.
 */
final class LoopFilterSearch {
    /**
     * The number of samples a band restores at least when a trial restores the reconstruction in
     * parallel.
     */
    private static final int RESTORE_CHUNK = 1 << 14;

    /**
     * Prevents instantiation.
     */
    private LoopFilterSearch() {
        throw new AssertionError();
    }

    /**
     * Picks the frame's deblocking levels, leaving them in its header and level cache; the
     * reconstruction is left unfiltered.
     *
     * @param fe the frame, reconstructed
     * @param search whether the levels are searched instead of taken from the header
     */
    static void pick(FilterTarget fe, boolean search) {
        var hdr = fe.header();
        var y0 = hdr.loopfilterLevelY[0];
        var y1 = hdr.loopfilterLevelY[1];
        var u = hdr.loopfilterLevelU;
        var v = hdr.loopfilterLevelV;
        var mono = fe.layout() == SequenceHeader.LAYOUT_I400;
        if (search) {
            var cur = fe.context().cur;
            var planes = mono ? 1 : 3;
            var backup = new short[planes][];
            for (var p = 0; p < planes; p++) {
                backup[p] = cur.data[p].clone();
            }
            var trial = new Trial(fe, backup);
            var both = trial.level(0, 2, y0, y0, y1, 0);
            y0 = trial.level(0, 0, both, both, both, 0);
            y1 = trial.level(0, 1, both, y0, both, 0);
            if (y0 == 0 && y1 == 0) {
                u = 0;
                v = 0;
            } else if (!mono) {
                u = trial.level(1, 0, u, y0, y1, u);
                v = trial.level(2, 0, v, y0, y1, u);
            }
            for (var p = 0; p < planes; p++) {
                System.arraycopy(backup[p], 0, cur.data[p], 0, backup[p].length);
            }
        }
        if (y0 == 0 && y1 == 0) {
            u = 0;
            v = 0;
        }
        setLevels(fe, y0, y1, u, v);
    }

    /**
     * Sets the frame's levels in its header and level cache.
     *
     * @param fe the frame
     * @param y0 the luma level of vertical edges
     * @param y1 the luma level of horizontal edges
     * @param u the U level
     * @param v the V level
     */
    static void setLevels(FilterTarget fe, int y0, int y1, int u, int v) {
        var hdr = fe.header();
        hdr.loopfilterLevelY[0] = y0;
        hdr.loopfilterLevelY[1] = y1;
        hdr.loopfilterLevelU = u;
        hdr.loopfilterLevelV = v;
        fill(fe.context().lfLevel, y0, y1, u, v);
    }

    /**
     * Fills a level cache with one four-byte group of levels, by doubling copies.
     *
     * @param cache the level cache to fill
     * @param y0 the luma level of vertical edges
     * @param y1 the luma level of horizontal edges
     * @param u the U level
     * @param v the V level
     */
    private static void fill(byte[] cache, int y0, int y1, int u, int v) {
        var groups = cache.length & ~3;
        if (groups == 0) {
            return;
        }
        cache[0] = (byte) y0;
        cache[1] = (byte) y1;
        cache[2] = (byte) u;
        cache[3] = (byte) v;
        for (var filled = 4; filled < groups; filled <<= 1) {
            System.arraycopy(cache, 0, cache, filled, Math.min(filled, groups - filled));
        }
    }

    /**
     * Runs deblocking trials of one plane and direction.
     */
    private static final class Trial {
        /**
         * The frame.
         */
        private final FilterTarget fe;

        /**
         * The unfiltered reconstruction of each plane.
         */
        private final short[][] backup;

        /**
         * The squared error of each level of the current search, -1 when not measured.
         */
        private final long[] errors = new long[64];

        /**
         * Creates the trials of a frame.
         *
         * @param fe the frame
         * @param backup the unfiltered reconstruction
         */
        Trial(FilterTarget fe, short[][] backup) {
            this.fe = fe;
            this.backup = backup;
        }

        /**
         * Searches the level of one plane and direction, the other levels fixed
         * ({@code search_filter_level}).
         *
         * @param plane the plane index: 0 luma, 1 U, 2 V
         * @param dir for luma, 0 for vertical edges, 1 for horizontal edges, 2 for both
         * @param start the level the search starts from
         * @param y0 the luma level of vertical edges while searching another one
         * @param y1 the luma level of horizontal edges while searching another one
         * @param u the U level while searching V
         * @return the best level
         */
        int level(int plane, int dir, int start, int y0, int y1, int u) {
            Arrays.fill(errors, -1);
            var mid = Math.clamp(start, 0, 63);
            var step = mid < 16 ? 4 : mid / 4;
            var bestErr = error(plane, dir, mid, y0, y1, u);
            var best = mid;
            errors[mid] = bestErr;
            var direction = 0;
            while (step > 0) {
                var high = Math.min(mid + step, 63);
                var low = Math.max(mid - step, 0);
                var bias = (bestErr >> (15 - (mid / 8))) * step;
                if (direction <= 0 && low != mid) {
                    if (errors[low] < 0) {
                        errors[low] = error(plane, dir, low, y0, y1, u);
                    }
                    if (errors[low] < bestErr + bias) {
                        if (errors[low] < bestErr) {
                            bestErr = errors[low];
                        }
                        best = low;
                    }
                }
                if (direction >= 0 && high != mid) {
                    if (errors[high] < 0) {
                        errors[high] = error(plane, dir, high, y0, y1, u);
                    }
                    if (errors[high] < bestErr - bias) {
                        bestErr = errors[high];
                        best = high;
                    }
                }
                if (best == mid) {
                    step /= 2;
                    direction = 0;
                } else {
                    direction = best < mid ? -1 : 1;
                    mid = best;
                }
            }
            return best;
        }

        /**
         * Deblocks the reconstruction with one level and returns the plane's squared error.
         *
         * @param plane the plane index: 0 luma, 1 U, 2 V
         * @param dir the luma direction, 2 for both
         * @param level the level tried
         * @param y0 the other luma vertical level
         * @param y1 the other luma horizontal level
         * @param u the U level while trying V
         * @return the squared error of the plane
         */
        private long error(int plane, int dir, int level, int y0, int y1, int u) {
            int ly0;
            int ly1;
            int lu = 0;
            int lv = 0;
            if (plane == 0) {
                ly0 = dir == 1 ? y0 : level;
                ly1 = dir == 0 ? y1 : level;
            } else {
                ly0 = y0;
                ly1 = y1;
                if (plane == 1) {
                    lu = level;
                } else {
                    lu = u;
                    lv = level;
                }
            }
            if (ly0 == 0 && ly1 == 0 && plane == 0) {
                return sse(plane);
            }
            var hdr = fe.header();
            setLevels(fe, ly0, ly1, plane == 0 ? 0 : lu, plane == 0 ? 0 : lv);
            if (plane != 0) {
                fill(fe.context().lfLevel, 0, 0, lu, lv);
                hdr.loopfilterLevelY[0] = Math.max(ly0, 1);
                hdr.loopfilterLevelY[1] = Math.max(ly1, 1);
            }
            var f = fe.context();
            for (var sby = 0; sby < f.sbh; sby++) {
                EncoderBridge.deblockSbrow(f, sby);
            }
            var err = sse(plane);
            var cur = f.cur;
            var planes = backup.length;
            for (var p = 0; p < planes; p++) {
                restore(cur.data[p], backup[p]);
            }
            return err;
        }

        /**
         * Copies a saved plane back over the reconstruction, in parallel bands when the frame may
         * use several threads.
         *
         * @param dst the reconstruction's plane
         * @param src the saved plane
         */
        private void restore(short[] dst, short[] src) {
            var chunks = (dst.length + RESTORE_CHUNK - 1) / RESTORE_CHUNK;
            var bands = fe.parallel() ? ParallelBands.bands(chunks, dst.length) : 1;
            if (bands <= 1) {
                System.arraycopy(src, 0, dst, 0, dst.length);
                return;
            }
            ParallelBands.run(bands, band -> {
                var from = ParallelBands.start(band, bands, chunks) * RESTORE_CHUNK;
                var to = Math.min(dst.length, ParallelBands.start(band + 1, bands, chunks) * RESTORE_CHUNK);
                System.arraycopy(src, from, dst, from, to - from);
            });
        }

        /**
         * Returns the squared error of one plane of the reconstruction against the source, at the
         * 8-bit scale.
         *
         * @param plane the plane index: 0 luma, 1 U, 2 V
         * @return the squared error
         */
        private long sse(int plane) {
            var cur = fe.context().cur;
            var src = fe.sourcePicture();
            var ss = plane == 0 ? 0 : fe.ssHor();
            var sv = plane == 0 ? 0 : fe.ssVer();
            var w = (fe.width() + ss) >> ss;
            var h = (fe.height() + sv) >> sv;
            var stride = cur.stride[plane == 0 ? 0 : 1];
            var a = cur.data[plane];
            var b = src.data[plane];
            long sum = 0;
            if (fe.distShift() == 0) {
                var bands = fe.parallel() ? ParallelBands.bands(h, (long) w * h) : 1;
                if (bands <= 1) {
                    return EncDsp.select().planeSse(a, b, 0, stride, w, h);
                }
                var parts = new long[bands];
                ParallelBands.run(bands, band -> {
                    var from = ParallelBands.start(band, bands, h);
                    var to = ParallelBands.start(band + 1, bands, h);
                    parts[band] = EncDsp.select().planeSse(a, b, from * stride, stride, w, to - from);
                });
                var total = 0L;
                for (var part : parts) {
                    total += part;
                }
                return total;
            }
            for (var y = 0; y < h; y++) {
                var o = y * stride;
                for (var x = 0; x < w; x++) {
                    var d = a[o + x] - b[o + x];
                    sum += d * d;
                }
            }
            return sum >> fe.distShift();
        }
    }
}
