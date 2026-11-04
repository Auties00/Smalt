/* Ports libvpx 1.17.0 (vp8/common/findnearmv.c, vp8_find_near_mvs). Copyright (c) 2010 The WebM project authors; BSD
   3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.video.vp8.internal;

/**
 * Finds the reference motion vectors of a macroblock from its above, left and above-left
 * neighbors.
 *
 * <p>Arguments are not checked; values outside the documented ranges give unspecified results.
 */
final class NearMvs {
    private NearMvs() {
        throw new AssertionError();
    }

    /**
     * Finds the best, nearest and near vectors of a macroblock and its neighbor counts
     * ({@code vp8_find_near_mvs}), without clamping them, for references that all have the same
     * sign bias and neighbors none of which is split.
     *
     * @param mbRef the reference frame of each macroblock, {@link Vp8FrameDecoder#INTRA_FRAME} for an
     * intra macroblock, by mode info index
     * @param mbMv the packed vector ({@link Mv}) of each macroblock, by mode info index
     * @param idx the mode info index of the macroblock, whose above, left and above-left neighbors
     * exist in the arrays
     * @param stride the mode info row stride
     * @param counts receives the neighbor counts: zero, nearest, near and split, the last always 0
     * @param result receives the packed best, nearest and near vectors
     */
    static void find(byte[] mbRef, int[] mbMv, int idx, int stride, int[] counts, int[] result) {
        find(mbRef, mbMv, null, idx, stride, 0, 0, counts, result);
    }

    /**
     * Finds the best, nearest and near vectors of a macroblock and its neighbor counts
     * ({@code vp8_find_near_mvs}), without clamping them: a neighbor's vector is negated when its
     * reference's sign bias differs from the macroblock's.
     *
     * @param mbRef the reference frame of each macroblock, {@link Vp8FrameDecoder#INTRA_FRAME} for an
     * intra macroblock, by mode info index
     * @param mbMv the packed vector ({@link Mv}) of each macroblock, by mode info index
     * @param mbMode the luma mode of each macroblock, by mode info index, or {@code null} when no
     * macroblock is split
     * @param idx the mode info index of the macroblock, whose above, left and above-left neighbors
     * exist in the arrays
     * @param stride the mode info row stride
     * @param biasMask the sign bias of each reference frame, bit {@code r} for reference {@code r}
     * @param bias the sign bias of the macroblock's reference, 0 or 1
     * @param counts receives the neighbor counts: zero, nearest, near and split
     * @param result receives the packed best, nearest and near vectors
     */
    static void find(byte[] mbRef, int[] mbMv, byte[] mbMode, int idx, int stride, int biasMask, int bias,
                     int[] counts, int[] result) {
        var above = idx - stride;
        var left = idx - 1;
        var aboveLeft = above - 1;
        var cnt = counts;
        cnt[0] = cnt[1] = cnt[2] = cnt[3] = 0;
        var mv0 = 0;
        var mv1 = 0;
        var mv2 = 0;
        var mv3 = 0;
        var n = 0;
        if (mbRef[above] != Vp8FrameDecoder.INTRA_FRAME) {
            var mv = biased(mbMv[above], mbRef[above], biasMask, bias);
            if (mv != 0) {
                mv1 = mv;
                n = 1;
            }
            cnt[n] += 2;
        }
        if (mbRef[left] != Vp8FrameDecoder.INTRA_FRAME) {
            var mv = biased(mbMv[left], mbRef[left], biasMask, bias);
            if (mv != 0) {
                var current = n == 0 ? mv0 : mv1;
                if (mv != current) {
                    n++;
                    if (n == 1) {
                        mv1 = mv;
                    } else {
                        mv2 = mv;
                    }
                }
                cnt[n] += 2;
            } else {
                cnt[0] += 2;
            }
        }
        if (mbRef[aboveLeft] != Vp8FrameDecoder.INTRA_FRAME) {
            var mv = biased(mbMv[aboveLeft], mbRef[aboveLeft], biasMask, bias);
            if (mv != 0) {
                var current = n == 0 ? mv0 : n == 1 ? mv1 : mv2;
                if (mv != current) {
                    n++;
                    if (n == 1) {
                        mv1 = mv;
                    } else if (n == 2) {
                        mv2 = mv;
                    } else {
                        mv3 = mv;
                    }
                }
                cnt[n] += 1;
            } else {
                cnt[0] += 1;
            }
        }
        var lastMv = n == 0 ? mv0 : n == 1 ? mv1 : n == 2 ? mv2 : mv3;
        if (cnt[3] != 0 && lastMv == mv1) {
            cnt[1] += 1;
        }
        cnt[3] = mbMode == null ? 0
                : ((mbMode[above] == Vp8FrameDecoder.SPLITMV ? 1 : 0) + (mbMode[left] == Vp8FrameDecoder.SPLITMV ? 1 : 0))
                * 2 + (mbMode[aboveLeft] == Vp8FrameDecoder.SPLITMV ? 1 : 0);
        if (cnt[2] > cnt[1]) {
            var t = cnt[1];
            cnt[1] = cnt[2];
            cnt[2] = t;
            var tm = mv1;
            mv1 = mv2;
            mv2 = tm;
        }
        if (cnt[1] >= cnt[0]) {
            mv0 = mv1;
        }
        result[0] = mv0;
        result[1] = mv1;
        result[2] = mv2;
    }

    /**
     * Returns a neighbor's vector, negated when its reference's sign bias differs from the
     * macroblock's ({@code mv_bias}).
     *
     * @param mv the neighbor's packed vector
     * @param ref the neighbor's reference frame
     * @param biasMask the sign bias of each reference frame, bit {@code r} for reference {@code r}
     * @param bias the sign bias of the macroblock's reference
     * @return the packed vector
     */
    static int biased(int mv, int ref, int biasMask, int bias) {
        if (((biasMask >> ref) & 1) != bias) {
            return Mv.pack(-Mv.row(mv), -Mv.col(mv));
        }
        return mv;
    }
}
