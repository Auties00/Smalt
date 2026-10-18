package com.github.auties00.smalt.video.av1.internal.encoder;

import com.github.auties00.smalt.internal.task.ParallelBands;
import com.github.auties00.smalt.video.av1.internal.CdefDsp;
import com.github.auties00.smalt.video.av1.internal.DequantTables;
import com.github.auties00.smalt.video.av1.internal.SequenceHeader;

/**
 * Picks the CDEF strengths of a frame and the strength index of each 64x64 block.
 *
 * <p>Every 8x8 block the frame codes residual for is filtered with each candidate strength of its
 * plane, on the deblocked reconstruction, and the squared error against the source is summed per
 * 64x64 block. A greedy joint search then picks up to eight luma and chroma strength pairs that
 * minimize the total error with each 64x64 block taking its best pair, the number of pairs chosen
 * by rate-distortion cost, the per-block index and the header strengths included. With the
 * reduced search the candidates are a subset of the strengths; without a search the strengths come
 * from a model of the quantizer.
 *
 * @implNote This implementation ports libaom's {@code av1_cdef_search} with
 * {@code joint_strength_search_dual} and {@code search_one_dual}
 * ({@code av1/encoder/pickcdef.c}), measuring the plain squared error, and
 * {@code av1_pick_cdef_from_qp} for the model. The filtering emulates the decoder's
 * {@code dav1d_cdef_brow}: each 8x8 block reads unfiltered neighbors, so every candidate
 * filters a private copy of the block and its two-sample border.
 */
final class CdefSearch {
    /**
     * The primary strengths of each search level, from the narrowest ({@code priconv_lvl5},
     * {@code priconv_lvl4}, {@code priconv_lvl2} twice, {@code priconv_lvl1}, then every strength).
     */
    private static final int[][] PRIMARY = {
            {0, 5}, {0, 11}, {0, 2, 4, 8, 14}, {0, 2, 4, 8, 14}, {0, 1, 2, 3, 5, 7, 10, 13},
            {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15}
    };

    /**
     * The secondary strength codes of each search level ({@code secconv_lvl5},
     * {@code secconv_lvl3} twice, then every code).
     */
    private static final int[][] SECONDARY = {
            {0}, {0, 2}, {0, 2}, {0, 1, 2, 3}, {0, 1, 2, 3}, {0, 1, 2, 3}
    };

    /**
     * The chroma direction of each luma direction for 4:2:2.
     */
    private static final int[] UV_DIR_422 = {7, 0, 2, 4, 5, 6, 6, 6};

    /**
     * Prevents instantiation.
     */
    private CdefSearch() {
        throw new AssertionError();
    }

    /**
     * Picks the frame's CDEF strengths and indices, leaving them in its header, its loop filter
     * masks and the frame.
     *
     * @param fe the frame, with its reconstruction deblocked
     * @param level the search level, 0 for the quantizer model, 1 (two strengths) to 6 (all 64)
     */
    static void pick(FilterTarget fe, int level) {
        var hdr = fe.header();
        var f = fe.context();
        var mono = fe.layout() == SequenceHeader.LAYOUT_I400;
        hdr.cdefDamping = 3 + (hdr.quantYac >> 6);
        if (level == 0) {
            var strengths = model(fe);
            hdr.cdefNBits = 0;
            hdr.cdefYStrength[0] = strengths >> 8;
            hdr.cdefUvStrength[0] = mono ? 0 : strengths & 0xFF;
            for (var sby = 0; sby < fe.sbRows(); sby++) {
                for (var sbx = 0; sbx < fe.sbCols(); sbx++) {
                    setIndex(fe, sbx, sby, 0);
                }
            }
            return;
        }
        var candidates = strengths(level);
        var ns = candidates.length;
        var nFb = fe.sbCols() * fe.sbRows();
        var mse = new long[2][nFb * ns];
        var coded = new boolean[nFb];
        for (var sby = 0; sby < fe.sbRows(); sby++) {
            for (var sbx = 0; sbx < fe.sbCols(); sbx++) {
                coded[sby * fe.sbCols() + sbx] = index(fe, sbx, sby) != -1;
            }
        }
        var rows = fe.sbRows();
        var bands = !fe.parallel() ? 1 : ParallelBands.bands(rows, (long) fe.width() * fe.height() * ns);
        var workers = new Worker[bands];
        ParallelBands.run(bands, band -> {
            var w = new Worker(fe, candidates);
            workers[band] = w;
            var last = ParallelBands.start(band + 1, bands, rows);
            for (var sby = ParallelBands.start(band, bands, rows); sby < last; sby++) {
                for (var sbx = 0; sbx < fe.sbCols(); sbx++) {
                    var fb = sby * fe.sbCols() + sbx;
                    if (coded[fb]) {
                        w.measure(sbx, sby, mse[0], mse[1], fb * ns);
                    }
                }
            }
        });
        var list = new int[nFb];
        var count = 0;
        for (var fb = 0; fb < nFb; fb++) {
            if (coded[fb]) {
                list[count++] = fb;
            }
        }
        var bestLev0 = new int[8];
        var bestLev1 = new int[8];
        var lev0 = new int[8];
        var lev1 = new int[8];
        var bestBits = 0;
        var bestCost = Long.MAX_VALUE;
        var planes = mono ? 1 : 2;
        for (var bits = 0; bits <= 3; bits++) {
            var nb = 1 << bits;
            var total = jointSearch(lev0, lev1, nb, mse, list, count, ns, mono);
            long totalBits = (long) count * bits + (long) nb * 6 * planes;
            var cost = RdCost.rdcost(fe.rdmult(), totalBits << RdCost.PROB_COST_SHIFT, total << 4);
            if (cost < bestCost) {
                bestCost = cost;
                bestBits = bits;
                System.arraycopy(lev0, 0, bestLev0, 0, nb);
                System.arraycopy(lev1, 0, bestLev1, 0, nb);
            }
        }
        var nb = 1 << bestBits;
        hdr.cdefNBits = bestBits;
        for (var i = 0; i < nb; i++) {
            hdr.cdefYStrength[i] = candidates[bestLev0[i]];
            hdr.cdefUvStrength[i] = mono ? 0 : candidates[bestLev1[i]];
        }
        for (var fb = 0; fb < nFb; fb++) {
            if (!coded[fb]) {
                continue;
            }
            var best = 0;
            var bestErr = Long.MAX_VALUE;
            for (var i = 0; i < nb; i++) {
                var err = mse[0][fb * ns + bestLev0[i]] + (mono ? 0 : mse[1][fb * ns + bestLev1[i]]);
                if (err < bestErr) {
                    bestErr = err;
                    best = i;
                }
            }
            setIndex(fe, fb % fe.sbCols(), fb / fe.sbCols(), best);
        }
    }

    /**
     * Returns the strengths a search level tries, primary times four plus the secondary code.
     *
     * @param level the level, 1 (two strengths, {@code CDEF_FAST_SEARCH_LVL5}) to 6 (all 64,
     * {@code CDEF_FULL_SEARCH})
     * @return the strengths
     */
    private static int[] strengths(int level) {
        var primary = PRIMARY[level - 1];
        var secondary = SECONDARY[level - 1];
        var s = new int[primary.length * secondary.length];
        var n = 0;
        for (var p : primary) {
            for (var q : secondary) {
                s[n++] = p * 4 + q;
            }
        }
        return s;
    }

    /**
     * Returns the CDEF index of a 64x64 block as its loop filter mask holds it.
     *
     * @param fe the frame
     * @param sbx the block column
     * @param sby the block row
     * @return the index, or {@code -1} when the block codes no residual
     */
    private static int index(FilterTarget fe, int sbx, int sby) {
        var f = fe.context();
        var mask = f.lfMask[(sby >> 1) * f.sb128w + (sbx >> 1)];
        return mask.cdefIdx[(sbx & 1) + ((sby & 1) << 1)];
    }

    /**
     * Sets the CDEF index of a 64x64 block that codes residual, in its loop filter mask and for
     * the bitstream.
     *
     * @param fe the frame
     * @param sbx the block column
     * @param sby the block row
     * @param idx the index
     */
    private static void setIndex(FilterTarget fe, int sbx, int sby, int idx) {
        var f = fe.context();
        var mask = f.lfMask[(sby >> 1) * f.sb128w + (sbx >> 1)];
        var i = (sbx & 1) + ((sby & 1) << 1);
        if (mask.cdefIdx[i] != -1) {
            mask.cdefIdx[i] = (byte) idx;
            fe.setCdefIndex(sby * fe.sbCols() + sbx, idx);
        }
    }

    /**
     * Returns the strengths the quantizer implies ({@code av1_pick_cdef_from_qp} for key frames).
     *
     * @param fe the frame
     * @return the luma strength in the high byte and the chroma strength in the low byte
     */
    private static int model(FilterTarget fe) {
        var q = DequantTables.DQ[fe.hbd() * 512 + fe.header().quantYac * 2 + 1] >> (fe.bitDepth() - 8);
        float fq = q;
        var yf1 = Math.clamp(Math.round(fq * fq * 0.0000033731974f + fq * 0.008070594f + 0.0187634f), 0, 15);
        var yf2 = Math.clamp(Math.round(fq * fq * 0.0000029167343f + fq * 0.0027798624f + 0.0079405f), 0, 3);
        var uvf1 = Math.clamp(Math.round(fq * fq * -0.0000130790995f + fq * 0.012892405f - 0.00748388f), 0, 15);
        var uvf2 = Math.clamp(Math.round(fq * fq * 0.0000032651783f + fq * 0.00035520183f + 0.00228092f), 0, 3);
        return (yf1 * 4 + yf2) << 8 | (uvf1 * 4 + uvf2);
    }

    /**
     * Picks the strength pairs of one pair count greedily, then refines them
     * ({@code joint_strength_search_dual}).
     *
     * @param lev0 receives the luma strength index of each pair
     * @param lev1 receives the chroma strength index of each pair
     * @param nb the number of pairs
     * @param mse the error of each block and candidate, luma then chroma
     * @param list the blocks that code residual
     * @param count the number of such blocks
     * @param ns the number of candidates
     * @param mono whether the frame has no chroma
     * @return the total error
     */
    private static long jointSearch(int[] lev0, int[] lev1, int nb, long[][] mse, int[] list, int count, int ns,
                                    boolean mono) {
        var best = Long.MAX_VALUE;
        for (var i = 0; i < nb; i++) {
            best = searchOne(lev0, lev1, i, mse, list, count, ns, mono);
        }
        for (var i = 0; i < 4 * nb; i++) {
            for (var j = 0; j < nb - 1; j++) {
                lev0[j] = lev0[j + 1];
                lev1[j] = lev1[j + 1];
            }
            best = searchOne(lev0, lev1, nb - 1, mse, list, count, ns, mono);
        }
        return best;
    }

    /**
     * Adds the strength pair that most lowers the total error given the first {@code nb} pairs
     * ({@code search_one_dual}).
     *
     * @param lev0 the luma strength indices
     * @param lev1 the chroma strength indices
     * @param nb the number of pairs already chosen
     * @param mse the errors
     * @param list the blocks that code residual
     * @param count the number of such blocks
     * @param ns the number of candidates
     * @param mono whether the frame has no chroma
     * @return the total error with the new pair
     */
    private static long searchOne(int[] lev0, int[] lev1, int nb, long[][] mse, int[] list, int count, int ns,
                                  boolean mono) {
        var nc = mono ? 1 : ns;
        var total = new long[ns * nc];
        var m0 = mse[0];
        var m1 = mse[1];
        for (var n = 0; n < count; n++) {
            var o = list[n] * ns;
            var bestMse = Long.MAX_VALUE;
            for (var gi = 0; gi < nb; gi++) {
                var cur = m0[o + lev0[gi]] + (mono ? 0 : m1[o + lev1[gi]]);
                bestMse = Math.min(bestMse, cur);
            }
            for (var j = 0; j < ns; j++) {
                var a = m0[o + j];
                var row = j * nc;
                for (var k = 0; k < nc; k++) {
                    var cur = a + (mono ? 0 : m1[o + k]);
                    total[row + k] += Math.min(cur, bestMse);
                }
            }
        }
        var best = Long.MAX_VALUE;
        var bj = 0;
        var bk = 0;
        for (var j = 0; j < ns; j++) {
            for (var k = 0; k < nc; k++) {
                if (total[j * nc + k] < best) {
                    best = total[j * nc + k];
                    bj = j;
                    bk = k;
                }
            }
        }
        lev0[nb] = bj;
        lev1[nb] = bk;
        return best;
    }

    /**
     * Measures the error of every candidate strength on 64x64 blocks of one band of rows.
     */
    private static final class Worker {
        /**
         * The frame.
         */
        private final FilterTarget fe;

        /**
         * The candidate strengths.
         */
        private final int[] candidates;

        /**
         * The block being filtered with a two-sample border on every side.
         */
        private final short[] block = new short[12 * 12];

        /**
         * The left columns of the block being filtered.
         */
        private final short[] left = new short[16];

        /**
         * The kernel scratch.
         */
        private final short[] scratch = new short[CdefDsp.SCRATCH_SHORTS];

        /**
         * The direction search scratch.
         */
        private final int[] variance = new int[CdefDsp.DIR_SCRATCH_INTS];

        /**
         * Creates a worker.
         *
         * @param fe the frame
         * @param candidates the candidate strengths
         */
        Worker(FilterTarget fe, int[] candidates) {
            this.fe = fe;
            this.candidates = candidates;
        }

        /**
         * Adds the error of every candidate on one 64x64 block to the error tables.
         *
         * @param sbx the block column
         * @param sby the block row
         * @param mse0 the luma errors
         * @param mse1 the chroma errors
         * @param base the index of the block's first candidate
         */
        void measure(int sbx, int sby, long[] mse0, long[] mse1, int base) {
            var f = fe.context();
            var cur = f.cur;
            var dsp = f.cdef;
            var bdMin8 = fe.bitDepth() - 8;
            var damping = fe.header().cdefDamping + bdMin8;
            var mono = fe.layout() == SequenceHeader.LAYOUT_I400;
            var ssHor = fe.ssHor();
            var ssVer = fe.ssVer();
            var uvLayout = SequenceHeader.LAYOUT_I444 - fe.layout();
            var yStride = cur.stride[0];
            var uvStride = cur.stride[1];
            var mask = f.lfMask[(sby >> 1) * f.sb128w + (sbx >> 1)];
            var ns = candidates.length;
            for (var by = sby * 16; by < Math.min(sby * 16 + 16, f.bh); by += 2) {
                var byIdx = (by & 30) >> 1;
                var noskip = mask.noskipMask[byIdx * 2 + 1] << 16 | mask.noskipMask[byIdx * 2];
                for (var bx = sbx * 16; bx < Math.min(sbx * 16 + 16, f.bw); bx += 2) {
                    if ((noskip & (3 << (bx & 30))) == 0) {
                        continue;
                    }
                    var edges = (by > 0 ? CdefDsp.HAVE_TOP : 0) | (by + 2 < f.bh ? CdefDsp.HAVE_BOTTOM : 0)
                            | (bx > 0 ? CdefDsp.HAVE_LEFT : 0) | (bx + 2 < f.bw ? CdefDsp.HAVE_RIGHT : 0);
                    var off = by * 4 * yStride + bx * 4;
                    var dir = dsp.dir(cur.data[0], off, yStride, variance, fe.bdMax());
                    var var0 = variance[0];
                    for (var s = 0; s < ns; s++) {
                        var lvl = candidates[s];
                        var pri = (lvl >> 2) << bdMin8;
                        var sec = lvl & 3;
                        sec += sec == 3 ? 1 : 0;
                        sec <<= bdMin8;
                        var adjPri = pri != 0 ? adjustStrength(pri, var0) : 0;
                        mse0[base + s] += filterError(0, CdefDsp.LAYOUT_8X8, off, yStride, 8, 8, adjPri, sec,
                                pri != 0 ? dir : 0, damping, edges, bx * 4, by * 4);
                        if (!mono) {
                            var uvOff = ((by * 4) >> ssVer) * uvStride + ((bx * 4) >> ssHor);
                            var uvDir = pri != 0 ? (fe.layout() == SequenceHeader.LAYOUT_I422 ? UV_DIR_422[dir] : dir) : 0;
                            long e = 0;
                            for (var pl = 1; pl <= 2; pl++) {
                                e += filterError(pl, uvLayout, uvOff, uvStride, 8 >> ssHor, 8 >> ssVer, pri, sec,
                                        uvDir, damping - 1, edges, (bx * 4) >> ssHor, (by * 4) >> ssVer);
                            }
                            mse1[base + s] += e;
                        }
                    }
                }
            }
        }

        /**
         * Filters a private copy of one block with a strength and returns its squared error
         * against the source, at the 8-bit scale; the reconstruction is only read, so bands of
         * rows may run at once.
         *
         * @param plane the plane index: 0 luma, 1 U, 2 V
         * @param layout the kernel layout
         * @param off the offset of the block in the plane
         * @param stride the plane stride
         * @param w the block width
         * @param h the block height
         * @param pri the primary strength, scaled
         * @param sec the secondary strength, scaled
         * @param dir the direction
         * @param damping the damping, scaled
         * @param edges the edge flags
         * @param x the block's column in samples
         * @param y the block's row in samples
         * @return the squared error
         */
        private long filterError(int plane, int layout, int off, int stride, int w, int h, int pri, int sec, int dir,
                                 int damping, int edges, int x, int y) {
            var cur = fe.context().cur;
            var data = cur.data[plane];
            var src = fe.sourcePicture().data[plane];
            var pw = (fe.width() + (plane == 0 ? 0 : fe.ssHor())) >> (plane == 0 ? 0 : fe.ssHor());
            var ph = (fe.height() + (plane == 0 ? 0 : fe.ssVer())) >> (plane == 0 ? 0 : fe.ssVer());
            var visW = Math.min(w, pw - x);
            var visH = Math.min(h, ph - y);
            if (visW <= 0 || visH <= 0) {
                return 0;
            }
            if (pri == 0 && sec == 0) {
                return sse(data, off, stride, src, off, stride, visW, visH);
            }
            var bs = w + 4;
            var top = (edges & CdefDsp.HAVE_TOP) != 0 ? 0 : 2;
            var bottom = (edges & CdefDsp.HAVE_BOTTOM) != 0 ? h + 4 : h + 2;
            var x0 = (edges & CdefDsp.HAVE_LEFT) != 0 ? 0 : 2;
            var x1 = (edges & CdefDsp.HAVE_RIGHT) != 0 ? w + 4 : w + 2;
            for (var r = top; r < bottom; r++) {
                System.arraycopy(data, off + (r - 2) * stride + x0 - 2, block, r * bs + x0, x1 - x0);
            }
            if ((edges & CdefDsp.HAVE_LEFT) != 0) {
                for (var r = 0; r < h; r++) {
                    left[2 * r] = block[(r + 2) * bs];
                    left[2 * r + 1] = block[(r + 2) * bs + 1];
                }
            }
            fe.context().cdef.filter(layout, block, 2 * bs + 2, bs, left, 0, block, 2, block, (h + 2) * bs + 2, pri, sec, dir,
                    damping, edges, scratch, fe.bdMax());
            return sse(block, 2 * bs + 2, bs, src, off, stride, visW, visH);
        }

        /**
         * Returns the squared error of a block against the source, at the 8-bit scale.
         *
         * @param a the filtered samples
         * @param aOff the offset of the block in {@code a}
         * @param aStride the stride of {@code a}
         * @param b the source plane
         * @param bOff the offset of the block in the source
         * @param bStride the source stride
         * @param w the visible width
         * @param h the visible height
         * @return the squared error
         */
        private long sse(short[] a, int aOff, int aStride, short[] b, int bOff, int bStride, int w, int h) {
            long sum = 0;
            for (var r = 0; r < h; r++) {
                var oa = aOff + r * aStride;
                var ob = bOff + r * bStride;
                for (var c = 0; c < w; c++) {
                    var d = a[oa + c] - b[ob + c];
                    sum += d * d;
                }
            }
            return sum >> fe.distShift();
        }

        /**
         * Returns the primary strength adjusted for the block's directional variance
         * ({@code adjust_strength}).
         *
         * @param strength the primary strength
         * @param var the variance
         * @return the adjusted strength
         */
        private static int adjustStrength(int strength, int var) {
            if (var == 0) {
                return 0;
            }
            var v = var >>> 6;
            var i = v != 0 ? Math.min(31 - Integer.numberOfLeadingZeros(v), 12) : 0;
            return (strength * (4 + i) + 8) >> 4;
        }
    }
}
