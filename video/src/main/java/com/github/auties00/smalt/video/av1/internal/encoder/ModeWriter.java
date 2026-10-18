package com.github.auties00.smalt.video.av1.internal.encoder;

import com.github.auties00.smalt.video.av1.internal.Av1Block;
import com.github.auties00.smalt.video.av1.internal.BlockContext;
import com.github.auties00.smalt.video.av1.internal.Cdf;
import com.github.auties00.smalt.video.av1.internal.EncoderBridge;
import com.github.auties00.smalt.video.av1.internal.FrameHeader;
import com.github.auties00.smalt.video.av1.internal.IpredDsp;
import com.github.auties00.smalt.video.av1.internal.RefMvs;
import com.github.auties00.smalt.video.av1.internal.Tables;
import com.github.auties00.smalt.video.av1.internal.TaskContext;

/**
 * Writes the mode information of AV1 blocks (AV1 specification sections 5.11.5 to 5.11.32) and
 * updates the above and left contexts the way the decoder updates them after reading a block.
 *
 * <p>The inter syntax covers single-reference and averaged bidirectional compound blocks with
 * translation motion and the frame's interpolation filter; the intra mode syntax
 * ({@link #writeIntraModes}) is shared by the real-time and the still picture encoders.
 *
 * @implNote This implementation mirrors the decoder's context derivations ({@code get_intra_ctx},
 * {@code get_tx_ctx}, the single reference contexts and {@code get_drl_context} of dav1d's
 * {@code env.h}); the syntax order follows libaom's {@code write_mb_modes_kf} and
 * {@code pack_inter_mode_mvs} ({@code av1/encoder/bitstream.c}).
 */
final class ModeWriter {
    /**
     * The new motion vector mode.
     */
    static final int NEWMV = 3;

    /**
     * The global motion vector mode.
     */
    static final int GLOBALMV = 2;

    /**
     * The near motion vector mode.
     */
    static final int NEARMV = 1;

    /**
     * The nearest motion vector mode.
     */
    static final int NEARESTMV = 0;

    /**
     * The compound mode predicting both references from their nearest vectors.
     */
    static final int NEAREST_NEARESTMV = 0;

    /**
     * The compound mode predicting both references from their near vectors.
     */
    static final int NEAR_NEARMV = 1;

    /**
     * The compound mode with the nearest vector for the first reference and a new one for the
     * second.
     */
    static final int NEAREST_NEWMV = 2;

    /**
     * The compound mode with a new vector for the first reference and the nearest for the second.
     */
    static final int NEW_NEARESTMV = 3;

    /**
     * The compound mode predicting both references at global motion.
     */
    static final int GLOBAL_GLOBALMV = 6;

    /**
     * The compound mode with new vectors for both references.
     */
    static final int NEW_NEWMV = 7;

    /**
     * The compound type of an averaged compound block ({@code COMPOUND_AVERAGE}).
     */
    static final int COMP_INTER_AVG = 2;

    /**
     * Prevents instantiation.
     */
    private ModeWriter() {
        throw new AssertionError();
    }

    /**
     * Returns the intra flag context of a block ({@code get_intra_ctx}).
     *
     * @param a the above context
     * @param l the left context
     * @param yb4 the row within the left context
     * @param xb4 the column within the above context
     * @param haveTop whether the top neighbor is available
     * @param haveLeft whether the left neighbor is available
     * @return the context
     */
    static int intraCtx(BlockContext a, BlockContext l, int yb4, int xb4, boolean haveTop, boolean haveLeft) {
        if (haveLeft) {
            if (haveTop) {
                var ctx = l.intra[yb4] + a.intra[xb4];
                return ctx + (ctx == 2 ? 1 : 0);
            }
            return l.intra[yb4] * 2;
        }
        return haveTop ? a.intra[xb4] * 2 : 0;
    }

    /**
     * Returns the intra transform size context of a block ({@code get_tx_ctx}).
     *
     * @param a the above context
     * @param l the left context
     * @param maxTx the largest transform size of the block
     * @param yb4 the row within the left context
     * @param xb4 the column within the above context
     * @return the context
     */
    static int txCtx(BlockContext a, BlockContext l, int maxTx, int yb4, int xb4) {
        return (l.txIntra[yb4] >= Tables.TX_LH[maxTx] ? 1 : 0) + (a.txIntra[xb4] >= Tables.TX_LW[maxTx] ? 1 : 0);
    }

    /**
     * Returns a reference context comparing the neighbors' use of two groups of references.
     *
     * @param a the above context
     * @param l the left context
     * @param yb4 the row within the left context
     * @param xb4 the column within the above context
     * @param haveTop whether the top neighbor is available
     * @param haveLeft whether the left neighbor is available
     * @param lo0 the first reference of the first group
     * @param hi0 the last reference of the first group
     * @param lo1 the first reference of the second group
     * @param hi1 the last reference of the second group
     * @return 1 when equal, 0 when the first group is rarer, 2 otherwise
     */
    static int refCtx(BlockContext a, BlockContext l, int yb4, int xb4, boolean haveTop, boolean haveLeft,
                      int lo0, int hi0, int lo1, int hi1) {
        var c0 = 0;
        var c1 = 0;
        if (haveTop && a.intra[xb4] == 0) {
            int r = a.ref[xb4];
            c0 += r >= lo0 && r <= hi0 ? 1 : 0;
            c1 += r >= lo1 && r <= hi1 ? 1 : 0;
            if (a.compType[xb4] != 0) {
                int r1 = a.ref[32 + xb4];
                c0 += r1 >= lo0 && r1 <= hi0 ? 1 : 0;
                c1 += r1 >= lo1 && r1 <= hi1 ? 1 : 0;
            }
        }
        if (haveLeft && l.intra[yb4] == 0) {
            int r = l.ref[yb4];
            c0 += r >= lo0 && r <= hi0 ? 1 : 0;
            c1 += r >= lo1 && r <= hi1 ? 1 : 0;
            if (l.compType[yb4] != 0) {
                int r1 = l.ref[32 + yb4];
                c0 += r1 >= lo0 && r1 <= hi0 ? 1 : 0;
                c1 += r1 >= lo1 && r1 <= hi1 ? 1 : 0;
            }
        }
        return c0 == c1 ? 1 : c0 < c1 ? 0 : 2;
    }

    /**
     * Returns the reference motion vector index context ({@code get_drl_context}).
     *
     * @param c the candidate list
     * @param refIdx the candidate index
     * @return the context
     */
    static int drlCtx(RefMvs.Candidates c, int refIdx) {
        if (c.weight[refIdx] >= 640) {
            return c.weight[refIdx + 1] < 640 ? 1 : 0;
        }
        return c.weight[refIdx + 1] < 640 ? 2 : 0;
    }

    /**
     * Returns the offset of the luma mode CDF of a block of an intra frame, chosen by the modes of
     * its above and left neighbors.
     *
     * @param a the above context
     * @param l the left context
     * @param bx4 the column within the above context
     * @param by4 the row within the left context
     * @return the CDF offset
     */
    static int kfYModeCdf(BlockContext a, BlockContext l, int bx4, int by4) {
        return Cdf.KFYM + (Tables.INTRA_MODE_CONTEXT[a.mode[bx4]] * 5 + Tables.INTRA_MODE_CONTEXT[l.mode[by4]]) * 16;
    }

    /**
     * Writes the intra modes of a block in syntax order ({@code intra_frame_mode_info} and
     * {@code intra_block_mode_info} up to the palette): the luma mode and its angle delta, then,
     * when the block has chroma, the chroma mode with its chroma-from-luma alphas or its angle
     * delta. The palette and filter intra syntax that follows is the caller's.
     *
     * @param ec the entropy coder
     * @param cdf the CDFs
     * @param yModeCdf the offset of the luma mode CDF: {@link #kfYModeCdf} in an intra frame, the
     * block size context's in an inter frame
     * @param bs the block size
     * @param b the block, with its luma mode and angle, and its chroma mode, angle and alphas when
     * it has chroma
     * @param hasChroma whether the block codes chroma
     * @param cfl whether the block may predict chroma from luma ({@code is_cfl_allowed})
     */
    static void writeIntraModes(RangeEncoder ec, char[] cdf, int yModeCdf, int bs, Av1Block b, boolean hasChroma,
                                boolean cfl) {
        var angles = Tables.BLOCK_DIMENSIONS[bs * 4 + 2] + Tables.BLOCK_DIMENSIONS[bs * 4 + 3] >= 2;
        ec.encodeSymbol(cdf, yModeCdf, b.yMode, 12);
        if (angles && b.yMode >= IpredDsp.VERT_PRED && b.yMode <= IpredDsp.VERT_LEFT_PRED) {
            ec.encodeSymbol(cdf, Cdf.ANGLE_DELTA + (b.yMode - IpredDsp.VERT_PRED) * 8, b.yAngle + 3, 6);
        }
        if (!hasChroma) {
            return;
        }
        var cflFlag = cfl ? 1 : 0;
        ec.encodeSymbol(cdf, Cdf.UV_MODE + (cflFlag * 13 + b.yMode) * 16, b.uvMode, 13 - (cflFlag ^ 1));
        if (b.uvMode == IpredDsp.CFL_PRED) {
            var au = b.cflAlpha[0];
            var av = b.cflAlpha[1];
            var signU = au == 0 ? 0 : au < 0 ? 1 : 2;
            var signV = av == 0 ? 0 : av < 0 ? 1 : 2;
            ec.encodeSymbol(cdf, Cdf.CFL_SIGN, signU * 3 + signV - 1, 7);
            if (signU != 0) {
                ec.encodeSymbol(cdf, Cdf.CFL_ALPHA + ((signU == 2 ? 3 : 0) + signV) * 16, Math.abs(au) - 1, 15);
            }
            if (signV != 0) {
                ec.encodeSymbol(cdf, Cdf.CFL_ALPHA + ((signV == 2 ? 3 : 0) + signU) * 16, Math.abs(av) - 1, 15);
            }
        } else if (angles && b.uvMode >= IpredDsp.VERT_PRED && b.uvMode <= IpredDsp.VERT_LEFT_PRED) {
            ec.encodeSymbol(cdf, Cdf.ANGLE_DELTA + (b.uvMode - IpredDsp.VERT_PRED) * 8, b.uvAngle + 3, 6);
        }
    }

    /**
     * Writes the transform size of an intra block coded with a switchable transform mode.
     *
     * @param ec the entropy coder
     * @param cdf the CDFs
     * @param a the above context
     * @param l the left context
     * @param bx4 the column within the above context
     * @param by4 the row within the left context
     * @param bs the block size
     * @param tx the chosen transform size
     */
    static void writeIntraTxSize(RangeEncoder ec, char[] cdf, BlockContext a, BlockContext l, int bx4, int by4,
                                 int bs, int tx) {
        var maxTx = Tables.MAX_TXFM_SIZE_FOR_BS[bs * 4];
        if (Tables.TX_MAX[maxTx] == 0) {
            return;
        }
        var depth = 0;
        for (var t = maxTx; t != tx; t = Tables.TX_SUB[t]) {
            depth++;
        }
        var tctx = txCtx(a, l, maxTx, by4, bx4);
        ec.encodeSymbol(cdf, Cdf.TXSZ + ((Tables.TX_MAX[maxTx] - 1) * 3 + tctx) * 4, depth,
                Math.min(Tables.TX_MAX[maxTx], 2));
    }

    /**
     * Returns the interpolation filter context of a single-reference block's horizontal filter
     * ({@code get_filter_ctx}).
     *
     * @param a the above context
     * @param l the left context
     * @param ref the block's decoder reference index
     * @param yb4 the row within the left context
     * @param xb4 the column within the above context
     * @return the context, 0 to 3
     */
    static int filterCtx(BlockContext a, BlockContext l, int ref, int yb4, int xb4) {
        var aFilter = a.ref[xb4] == ref || a.ref[32 + xb4] == ref ? a.filter[xb4] : 3;
        var lFilter = l.ref[yb4] == ref || l.ref[32 + yb4] == ref ? l.filter[yb4] : 3;
        if (aFilter == lFilter) {
            return aFilter;
        } else if (aFilter == 3) {
            return lFilter;
        } else if (lFilter == 3) {
            return aFilter;
        }
        return 3;
    }

    /**
     * Writes the single reference of an inter block.
     *
     * @param ec the entropy coder
     * @param cdf the CDFs
     * @param a the above context
     * @param l the left context
     * @param bx4 the column within the above context
     * @param by4 the row within the left context
     * @param haveTop whether the top neighbor is available
     * @param haveLeft whether the left neighbor is available
     * @param ref the reference, 0 for {@code LAST} to 6 for {@code ALTREF}
     */
    static void writeSingleRef(RangeEncoder ec, char[] cdf, BlockContext a, BlockContext l, int bx4, int by4,
                               boolean haveTop, boolean haveLeft, int ref) {
        var ctx1 = refCtx(a, l, by4, bx4, haveTop, haveLeft, 0, 3, 4, 6);
        var backward = ref >= 4;
        ec.encodeBool(cdf, Cdf.REF + ctx1 * 2, backward ? 1 : 0);
        if (backward) {
            var ctx2 = refCtx(a, l, by4, bx4, haveTop, haveLeft, 4, 5, 6, 6);
            ec.encodeBool(cdf, Cdf.REF + (3 + ctx2) * 2, ref == 6 ? 1 : 0);
            if (ref != 6) {
                var ctx3 = refCtx(a, l, by4, bx4, haveTop, haveLeft, 4, 4, 5, 5);
                ec.encodeBool(cdf, Cdf.REF + (15 + ctx3) * 2, ref - 4);
            }
        } else {
            var ctx2 = refCtx(a, l, by4, bx4, haveTop, haveLeft, 0, 1, 2, 3);
            var high = ref >= 2;
            ec.encodeBool(cdf, Cdf.REF + (6 + ctx2) * 2, high ? 1 : 0);
            if (high) {
                var ctx3 = refCtx(a, l, by4, bx4, haveTop, haveLeft, 2, 2, 3, 3);
                ec.encodeBool(cdf, Cdf.REF + (12 + ctx3) * 2, ref - 2);
            } else {
                var ctx3 = refCtx(a, l, by4, bx4, haveTop, haveLeft, 0, 0, 1, 1);
                ec.encodeBool(cdf, Cdf.REF + (9 + ctx3) * 2, ref);
            }
        }
    }

    /**
     * Writes the inter mode, reference index and motion vector of a single-reference block.
     *
     * @param ec the entropy coder
     * @param cdf the CDFs
     * @param hdr the frame header
     * @param c the block's candidate list
     * @param mode the inter mode
     * @param drlIdx the candidate index of a {@code NEARMV} or {@code NEWMV} block
     * @param mv the packed motion vector of a {@code NEWMV} block
     */
    static void writeInterMode(RangeEncoder ec, char[] cdf, FrameHeader hdr, RefMvs.Candidates c, int mode,
                               int drlIdx, int mv) {
        var ctx = c.ctx;
        var nMvs = c.count;
        ec.encodeBool(cdf, Cdf.NEWMV_MODE + (ctx & 7) * 2, mode == NEWMV ? 0 : 1);
        if (mode == NEWMV) {
            if (nMvs > 1) {
                ec.encodeBool(cdf, Cdf.DRL_BIT + drlCtx(c, 0) * 2, drlIdx > 0 ? 1 : 0);
                if (drlIdx > 0 && nMvs > 2) {
                    ec.encodeBool(cdf, Cdf.DRL_BIT + drlCtx(c, 1) * 2, drlIdx > 1 ? 1 : 0);
                }
            }
            var pred = newmvPredictor(hdr, c, drlIdx);
            var mvPrec = (hdr.hp ? 1 : 0) - (hdr.forceIntegerMv ? 1 : 0);
            writeMvResidual(ec, cdf, mv, pred, mvPrec);
            return;
        }
        ec.encodeBool(cdf, Cdf.GLOBALMV_MODE + ((ctx >> 3) & 1) * 2, mode == GLOBALMV ? 0 : 1);
        if (mode == GLOBALMV) {
            return;
        }
        ec.encodeBool(cdf, Cdf.REFMV_MODE + ((ctx >> 4) & 15) * 2, mode == NEARMV ? 1 : 0);
        if (mode == NEARMV && nMvs > 2) {
            ec.encodeBool(cdf, Cdf.DRL_BIT + drlCtx(c, 1) * 2, drlIdx > 1 ? 1 : 0);
            if (drlIdx > 1 && nMvs > 3) {
                ec.encodeBool(cdf, Cdf.DRL_BIT + drlCtx(c, 2) * 2, drlIdx > 2 ? 1 : 0);
            }
        }
    }

    /**
     * Writes the references of a bidirectional compound block: a forward reference, {@code LAST}
     * to {@code GOLDEN}, and a backward one, {@code BWDREF} to {@code ALTREF}.
     *
     * @param ec the entropy coder
     * @param cdf the CDFs
     * @param t the tile's task context, positioned at the block
     * @param bx4 the column within the above context
     * @param by4 the row within the left context
     * @param haveTop whether the top neighbor is available
     * @param haveLeft whether the left neighbor is available
     * @param ref0 the forward reference, 0 to 3
     * @param ref1 the backward reference, 4 to 6
     */
    static void writeCompoundRefs(RangeEncoder ec, char[] cdf, TaskContext t, int bx4, int by4, boolean haveTop,
                                  boolean haveLeft, int ref0, int ref1) {
        var a = t.a;
        var l = t.l;
        var dirCtx = EncoderBridge.compDirContext(t, by4, bx4, haveTop, haveLeft);
        ec.encodeBool(cdf, Cdf.COMP_DIR + dirCtx * 2, 1);
        var ctx1 = refCtx(a, l, by4, bx4, haveTop, haveLeft, 0, 1, 2, 3);
        var high = ref0 >= 2;
        ec.encodeBool(cdf, Cdf.COMP_FWD_REF + ctx1 * 2, high ? 1 : 0);
        if (high) {
            var ctx2 = refCtx(a, l, by4, bx4, haveTop, haveLeft, 2, 2, 3, 3);
            ec.encodeBool(cdf, Cdf.COMP_FWD_REF + (6 + ctx2) * 2, ref0 - 2);
        } else {
            var ctx2 = refCtx(a, l, by4, bx4, haveTop, haveLeft, 0, 0, 1, 1);
            ec.encodeBool(cdf, Cdf.COMP_FWD_REF + (3 + ctx2) * 2, ref0);
        }
        var ctx3 = refCtx(a, l, by4, bx4, haveTop, haveLeft, 4, 5, 6, 6);
        ec.encodeBool(cdf, Cdf.COMP_BWD_REF + ctx3 * 2, ref1 == 6 ? 1 : 0);
        if (ref1 != 6) {
            var ctx4 = refCtx(a, l, by4, bx4, haveTop, haveLeft, 4, 4, 5, 5);
            ec.encodeBool(cdf, Cdf.COMP_BWD_REF + (3 + ctx4) * 2, ref1 - 4);
        }
    }

    /**
     * Returns whether a compound mode codes a new vector for one reference.
     *
     * @param mode the compound mode
     * @param idx the reference, 0 or 1
     * @return {@code true} for a new vector
     */
    static boolean compoundNew(int mode, int idx) {
        return Tables.COMP_INTER_PRED_MODES[mode * 2 + idx] == NEWMV;
    }

    /**
     * Returns whether a compound mode uses the near vector of one of its references.
     *
     * @param mode the compound mode
     * @return {@code true} when a reference takes the near vector
     */
    static boolean compoundNear(int mode) {
        return Tables.COMP_INTER_PRED_MODES[mode * 2] == NEARMV || Tables.COMP_INTER_PRED_MODES[mode * 2 + 1] == NEARMV;
    }

    /**
     * Writes the compound mode, reference index and new motion vectors of a compound block.
     *
     * @param ec the entropy coder
     * @param cdf the CDFs
     * @param hdr the frame header
     * @param c the block's compound candidate list
     * @param mode the compound mode
     * @param drlIdx the candidate index
     * @param mv0 the packed motion vector of the first reference
     * @param mv1 the packed motion vector of the second reference
     */
    static void writeCompoundMode(RangeEncoder ec, char[] cdf, FrameHeader hdr, RefMvs.Candidates c, int mode,
                                  int drlIdx, int mv0, int mv1) {
        ec.encodeSymbol(cdf, Cdf.COMP_INTER_MODE + c.ctx * 8, mode, 7);
        var nMvs = c.count;
        if (mode == NEW_NEWMV) {
            if (nMvs > 1) {
                ec.encodeBool(cdf, Cdf.DRL_BIT + drlCtx(c, 0) * 2, drlIdx > 0 ? 1 : 0);
                if (drlIdx > 0 && nMvs > 2) {
                    ec.encodeBool(cdf, Cdf.DRL_BIT + drlCtx(c, 1) * 2, drlIdx > 1 ? 1 : 0);
                }
            }
        } else if (compoundNear(mode) && nMvs > 2) {
            ec.encodeBool(cdf, Cdf.DRL_BIT + drlCtx(c, 1) * 2, drlIdx > 1 ? 1 : 0);
            if (drlIdx > 1 && nMvs > 3) {
                ec.encodeBool(cdf, Cdf.DRL_BIT + drlCtx(c, 2) * 2, drlIdx > 2 ? 1 : 0);
            }
        }
        var mvPrec = (hdr.hp ? 1 : 0) - (hdr.forceIntegerMv ? 1 : 0);
        if (compoundNew(mode, 0)) {
            writeMvResidual(ec, cdf, mv0, c.mv0[drlIdx], mvPrec);
        }
        if (compoundNew(mode, 1)) {
            writeMvResidual(ec, cdf, mv1, c.mv1[drlIdx], mvPrec);
        }
    }

    /**
     * Returns the motion vector a {@code NEWMV} block's difference is coded against.
     *
     * @param hdr the frame header
     * @param c the candidate list
     * @param drlIdx the candidate index
     * @return the packed predictor
     */
    static int newmvPredictor(FrameHeader hdr, RefMvs.Candidates c, int drlIdx) {
        return c.count > 1 ? c.mv0[drlIdx] : RefMvs.fixMvPrecision(hdr, c.mv0[0]);
    }

    /**
     * Returns the motion vector of a {@code NEARESTMV} or {@code NEARMV} block.
     *
     * @param hdr the frame header
     * @param c the candidate list
     * @param drlIdx the candidate index
     * @return the packed motion vector
     */
    static int refMv(FrameHeader hdr, RefMvs.Candidates c, int drlIdx) {
        var mv = c.mv0[drlIdx];
        return drlIdx < 2 ? RefMvs.fixMvPrecision(hdr, mv) : mv;
    }

    /**
     * Writes a motion vector as its difference from a predictor.
     *
     * @param ec the entropy coder
     * @param cdf the CDFs
     * @param mv the packed motion vector
     * @param pred the packed predictor
     * @param mvPrec the precision: {@code -1} integer, 0 quarter sample, 1 eighth sample
     */
    static void writeMvResidual(RangeEncoder ec, char[] cdf, int mv, int pred, int mvPrec) {
        var dy = RefMvs.mvY(mv) - RefMvs.mvY(pred);
        var dx = RefMvs.mvX(mv) - RefMvs.mvX(pred);
        var joint = (dy != 0 ? 2 : 0) | (dx != 0 ? 1 : 0);
        ec.encodeSymbol(cdf, Cdf.MV_JOINT, joint, 3);
        if (dy != 0) {
            writeMvComponent(ec, cdf, 0, dy, mvPrec);
        }
        if (dx != 0) {
            writeMvComponent(ec, cdf, Cdf.MV_COMP_STRIDE, dx, mvPrec);
        }
    }

    /**
     * Writes one non-zero motion vector component difference.
     *
     * @param ec the entropy coder
     * @param cdf the CDFs
     * @param comp the offset of the component's CDFs
     * @param diff the difference in 1/8 samples
     * @param mvPrec the precision
     */
    private static void writeMvComponent(RangeEncoder ec, char[] cdf, int comp, int diff, int mvPrec) {
        var sign = diff < 0 ? 1 : 0;
        var mag = Math.abs(diff) - 1;
        var up = mag >> 3;
        var fp = (mag >> 1) & 3;
        var hp = mag & 1;
        var cl = up < 2 ? 0 : 31 - Integer.numberOfLeadingZeros(up);
        ec.encodeBool(cdf, comp + Cdf.MV_SIGN, sign);
        ec.encodeSymbol(cdf, comp + Cdf.MV_CLASSES, cl, 10);
        if (cl == 0) {
            ec.encodeBool(cdf, comp + Cdf.MV_CLASS0, up);
            if (mvPrec >= 0) {
                ec.encodeSymbol(cdf, comp + Cdf.MV_CLASS0_FP + up * 4, fp, 3);
                if (mvPrec > 0) {
                    ec.encodeBool(cdf, comp + Cdf.MV_CLASS0_HP, hp);
                }
            }
        } else {
            for (var n = 0; n < cl; n++) {
                ec.encodeBool(cdf, comp + Cdf.MV_CLASSN + n * 2, (up >> n) & 1);
            }
            if (mvPrec >= 0) {
                ec.encodeSymbol(cdf, comp + Cdf.MV_CLASSN_FP, fp, 3);
                if (mvPrec > 0) {
                    ec.encodeBool(cdf, comp + Cdf.MV_CLASSN_HP, hp);
                }
            }
        }
    }
}
