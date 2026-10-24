package com.github.auties00.smalt.video.av1.internal;

import com.github.auties00.smalt.MediaLimits;

/**
 * Exposes the decoder's frame setup and in-loop filters to the AV1 encoder, so the encoder's
 * reconstruction runs through exactly the code a decoder runs.
 *
 * <p>The encoder fills a {@link FrameContext} with its headers, the reconstruction picture and the
 * per-block loop filter masks, CDEF indices and transform edges the decoder would record while
 * parsing, then filters it one superblock row at a time.
 */
public final class EncoderBridge {
    /**
     * Prevents instantiation.
     */
    private EncoderBridge() {
        throw new AssertionError();
    }

    /**
     * Prepares a frame context for a frame: selects the DSP kernels, sizes the loop filter,
     * CDEF and restoration buffers, derives the loop filter levels, the dequantizers and, for
     * inter frames, the motion vector reference state.
     *
     * @param f the frame context with its headers, geometry, pictures and references set
     * @param limits the allocation limits
     */
    public static void initFrame(FrameContext f, MediaLimits limits) {
        FrameDecoder.init(f, limits);
    }

    /**
     * Applies the deblocking filter, CDEF and loop restoration to one superblock row of a fully
     * reconstructed frame.
     *
     * @param t a task context whose {@link TaskContext#f} is the frame
     * @param f the frame context
     * @param sby the superblock row
     */
    public static void filterSbrow(TaskContext t, FrameContext f, int sby) {
        FrameDecoder.filterSbrow(t, f, sby);
    }

    /**
     * Applies the deblocking filter to one superblock row and saves the lines CDEF and loop
     * restoration read across row boundaries.
     *
     * @param f the frame context
     * @param sby the superblock row
     */
    public static void deblockSbrow(FrameContext f, int sby) {
        FrameDecoder.deblockSbrow(f, sby);
    }

    /**
     * Reconstructs an intra block with the decoder's prediction and inverse transforms, in the
     * decoder's order; the coefficients of every transform block come from the task context's
     * {@link TaskContext#coefSource}.
     *
     * @param t the task context, positioned at the block
     * @param bs the block size
     * @param intraEdgeFlags the block's intra edge flags
     * @param b the block, with its modes, palette and transform size set
     */
    public static void reconIntra(TaskContext t, int bs, int intraEdgeFlags, Av1Block b) {
        Recon.reconBIntra(t, bs, intraEdgeFlags, b);
    }

    /**
     * Saves the bottom row of the superblock row just reconstructed, which the intra prediction of
     * the next superblock row reads.
     *
     * @param t the task context, positioned at the superblock row
     */
    public static void backupIpredEdge(TaskContext t) {
        TileDecoder.backupIpredEdge(t);
    }

    /**
     * Saves the bottom row of a range of columns of the superblock row being reconstructed, which
     * the intra prediction of the superblocks below reads as soon as the range is reconstructed.
     *
     * @param t the task context, positioned at the superblock row
     * @param xStart the first column, in 4-sample units
     * @param xEnd the column after the last, in 4-sample units
     */
    public static void backupIpredEdge(TaskContext t, int xStart, int xEnd) {
        TileDecoder.backupIpredEdge(t, xStart, xEnd);
    }

    /**
     * Records the luma palette of a block as the neighbor palette of the blocks below and to the
     * right.
     *
     * @param t the task context
     * @param bx4 the column within the above context
     * @param by4 the row within the left context
     * @param bw4 the block width in 4-sample units
     * @param bh4 the block height in 4-sample units
     */
    public static void copyPalBlockY(TaskContext t, int bx4, int by4, int bw4, int bh4) {
        Palette.copyPalBlockY(t, bx4, by4, bw4, bh4);
    }

    /**
     * Records the chroma palettes of a block as the neighbor palettes of the blocks below and to
     * the right.
     *
     * @param t the task context
     * @param bx4 the column within the above context
     * @param by4 the row within the left context
     * @param bw4 the block width in 4-sample units
     * @param bh4 the block height in 4-sample units
     */
    public static void copyPalBlockUv(TaskContext t, int bx4, int by4, int bw4, int bh4) {
        Palette.copyPalBlockUv(t, bx4, by4, bw4, bh4);
    }

    /**
     * Applies CDEF to one deblocked superblock row.
     *
     * @param t a task context whose {@link TaskContext#f} is the frame
     * @param f the frame context
     * @param sby the superblock row
     */
    public static void cdefSbrow(TaskContext t, FrameContext f, int sby) {
        CdefApply.filterSbrow(t, f, sby);
    }

    /**
     * Sets up the super-resolution upscaling of a frame whose coded picture is narrower than its
     * upscaled picture: the horizontal step and initial position of each plane, as a decoder
     * derives them.
     *
     * @param f the frame, with its coded picture in {@link FrameContext#cur} and its upscaled
     * picture in {@link FrameContext#srCur}
     */
    public static void setupSuperres(FrameContext f) {
        var cur = f.cur;
        var sr = f.srCur;
        var ssHor = cur.layout != SequenceHeader.LAYOUT_I444 ? 1 : 0;
        var inCw = (cur.w + ssHor) >> ssHor;
        var outCw = (sr.w + ssHor) >> ssHor;
        f.resizeStep[0] = Av1Context.scaleFac(cur.w, sr.w);
        f.resizeStep[1] = Av1Context.scaleFac(inCw, outCw);
        f.resizeStart[0] = Av1Context.upscaleX0(cur.w, sr.w, f.resizeStep[0]);
        f.resizeStart[1] = Av1Context.upscaleX0(inCw, outCw, f.resizeStep[1]);
    }

    /**
     * Upscales the whole filtered coded picture of a frame into its upscaled picture with the
     * decoder's super-resolution filter.
     *
     * @param f the frame, set up by {@link #setupSuperres}, with the in-loop filters before
     * super-resolution applied
     */
    public static void superres(FrameContext f) {
        var cur = f.cur;
        var sr = f.srCur;
        var planes = cur.layout != SequenceHeader.LAYOUT_I400 ? 3 : 1;
        for (var pl = 0; pl < planes; pl++) {
            var ssVer = pl != 0 && cur.layout == SequenceHeader.LAYOUT_I420 ? 1 : 0;
            var ssHor = pl != 0 && cur.layout != SequenceHeader.LAYOUT_I444 ? 1 : 0;
            var dstStride = sr.stride[pl != 0 ? 1 : 0];
            var srcStride = cur.stride[pl != 0 ? 1 : 0];
            var dstW = (sr.w + ssHor) >> ssHor;
            var srcW = (4 * f.bw + ssHor) >> ssHor;
            var h = (cur.h + ssVer) >> ssVer;
            f.mc.resize(sr.data[pl], 0, dstStride, cur.data[pl], 0, srcStride, dstW, h, srcW,
                    f.resizeStep[pl != 0 ? 1 : 0], f.resizeStart[pl != 0 ? 1 : 0], f.bitdepthMax);
        }
    }

    /**
     * Predicts one plane of a single-reference block with the decoder's motion compensation,
     * scaled when the reference has another size than the frame.
     *
     * @param t the task context, whose frame context holds the current picture and the scale
     * factors of the references
     * @param dst the destination plane
     * @param dstOff the offset of the block in the destination
     * @param dstStride the destination stride
     * @param bw4 the block width in 4-sample luma units
     * @param bh4 the block height in 4-sample luma units
     * @param bx the block column in 4-sample luma units
     * @param by the block row in 4-sample luma units
     * @param pl the plane
     * @param mv the packed motion vector
     * @param refp the reference picture
     * @param refidx the decoder reference index, for the scale factors
     * @param filter2d the interpolation filter
     */
    public static void predictSingle(TaskContext t, short[] dst, int dstOff, int dstStride, int bw4, int bh4, int bx,
                                     int by, int pl, int mv, Av1Picture refp, int refidx, int filter2d) {
        Recon.mc(t, dst, null, dstOff, dstStride, bw4, bh4, bx, by, pl, mv, refp, refidx, filter2d);
    }

    /**
     * Fills a picture of the frame's size with the zero-motion prediction of a reference of
     * another size: every plane predicted in 8x8 luma blocks with the decoder's scaled motion
     * compensation and the regular filter.
     *
     * @param t a task context whose frame context holds the current picture and the scale factors
     * of the references
     * @param dst the picture to fill, of the frame's size and layout
     * @param ref the reference picture
     * @param refidx the decoder reference index of the reference, for the scale factors
     */
    public static void resample(TaskContext t, Av1Picture dst, Av1Picture ref, int refidx) {
        var layout = dst.layout;
        var ssHor = layout == SequenceHeader.LAYOUT_I420 || layout == SequenceHeader.LAYOUT_I422 ? 1 : 0;
        var ssVer = layout == SequenceHeader.LAYOUT_I420 ? 1 : 0;
        var planes = layout == SequenceHeader.LAYOUT_I400 ? 1 : 3;
        var w4 = (dst.w + 7) >> 3 << 1;
        var h4 = (dst.h + 7) >> 3 << 1;
        for (var pl = 0; pl < planes; pl++) {
            var ssx = pl != 0 ? ssHor : 0;
            var ssy = pl != 0 ? ssVer : 0;
            var stride = dst.stride[pl != 0 ? 1 : 0];
            for (var by = 0; by < h4; by += 2) {
                for (var bx = 0; bx < w4; bx += 2) {
                    var off = ((by * 4) >> ssy) * stride + ((bx * 4) >> ssx);
                    Recon.mc(t, dst.data[pl], null, off, stride, 2, 2, bx, by, pl, 0, ref, refidx, 0);
                }
            }
        }
    }

    /**
     * Predicts every plane of a compound block of at least 8x8 luma samples into the frame's
     * picture with the decoder's compound prediction, in the picture's chroma layout.
     *
     * @param t the task context, positioned at the block, with the frame's references
     * @param bs the block size
     * @param b the block, with its references, motion vectors, filter and compound type set
     * @param dst the offset of the block's top-left luma sample
     * @param uvdstoff the offset of the block's top-left chroma sample
     */
    public static void predictCompound(TaskContext t, int bs, Av1Block b, int dst, int uvdstoff) {
        var bw4 = Tables.BLOCK_DIMENSIONS[bs * 4];
        var bh4 = Tables.BLOCK_DIMENSIONS[bs * 4 + 1];
        var layout = t.f.cur.layout;
        var ssHor = layout == SequenceHeader.LAYOUT_I420 || layout == SequenceHeader.LAYOUT_I422 ? 1 : 0;
        var ssVer = layout == SequenceHeader.LAYOUT_I420 ? 1 : 0;
        var chroma = layout != SequenceHeader.LAYOUT_I400;
        Recon.predictCompound(t, bs, b, dst, uvdstoff, chroma, chroma ? SequenceHeader.LAYOUT_I444 - layout : 0,
                (bw4 + ssHor) >> ssHor, (bh4 + ssVer) >> ssVer);
    }

    /**
     * Returns the compound flag context of a block ({@code get_comp_ctx}).
     *
     * @param t the task context with the block's above and left contexts
     * @param by4 the row within the left context
     * @param bx4 the column within the above context
     * @param haveTop whether the top neighbor is available
     * @param haveLeft whether the left neighbor is available
     * @return the context
     */
    public static int compContext(TaskContext t, int by4, int bx4, boolean haveTop, boolean haveLeft) {
        return BlockDecoder.compContext(t, by4, bx4, haveTop, haveLeft);
    }

    /**
     * Returns the compound direction context of a block ({@code get_comp_dir_ctx}).
     *
     * @param t the task context with the block's above and left contexts
     * @param by4 the row within the left context
     * @param bx4 the column within the above context
     * @param haveTop whether the top neighbor is available
     * @param haveLeft whether the left neighbor is available
     * @return the context
     */
    public static int compDirContext(TaskContext t, int by4, int bx4, boolean haveTop, boolean haveLeft) {
        return BlockDecoder.compDirContext(t, by4, bx4, haveTop, haveLeft);
    }

    /**
     * Returns the interpolation filter context of a block's first filter direction
     * ({@code get_filter_ctx}).
     *
     * @param t the task context with the block's above and left contexts
     * @param comp 1 for a compound block, else 0
     * @param ref the block's first reference
     * @param by4 the row within the left context
     * @param bx4 the column within the above context
     * @return the context, 0 to 7
     */
    public static int filterContext(TaskContext t, int comp, int ref, int by4, int bx4) {
        return BlockDecoder.filterContext(t, comp, 0, ref, by4, bx4);
    }

    /**
     * Applies loop restoration to one superblock row of a CDEF-filtered frame.
     *
     * @param t a task context whose {@link TaskContext#f} is the frame
     * @param f the frame context
     * @param sby the superblock row
     */
    public static void lrSbrow(TaskContext t, FrameContext f, int sby) {
        LrApply.sbrow(t, f, sby);
    }

    /**
     * Returns whether the motion mode of a single-reference block is coded and whether a local
     * warp is allowed for it, collecting the block's warp samples ({@code has_overlappable_candidates}
     * and {@code find_matching_ref}).
     *
     * @param t the task context positioned at the block
     * @param bs the block size
     * @param edgeFlags the block's intra edge flags
     * @param ref the block's reference
     * @return 0 when the block codes no motion mode, 1 when it codes the overlapped motion flag
     * only, 3 when it codes the motion mode with the local warp allowed
     */
    public static int motionModeKind(TaskContext t, int bs, int edgeFlags, int ref) {
        var f = t.f;
        var hdr = f.frameHdr;
        var ts = t.ts;
        var bw4 = Tables.BLOCK_DIMENSIONS[bs * 4];
        var bh4 = Tables.BLOCK_DIMENSIONS[bs * 4 + 1];
        if (!hdr.switchableMotionMode || Math.min(bw4, bh4) < 2) {
            return 0;
        }
        var w4 = Math.min(bw4, f.bw - t.bx);
        var h4 = Math.min(bh4, f.bh - t.by);
        var haveTop = t.by > ts.rowStart;
        var haveLeft = t.bx > ts.colStart;
        var bx4 = t.bx & 31;
        var by4 = t.by & 31;
        if (!BlockDecoder.hasOverlappable(t, by4, bx4, w4, h4, haveTop, haveLeft)) {
            return 0;
        }
        BlockDecoder.matchingRefs(t, edgeFlags, bw4, bh4, w4, h4, haveLeft, haveTop, ref);
        var allowWarp = f.svcScale[ref * 2] == 0 && !hdr.forceIntegerMv && hdr.warpMotion
                && (t.warpMasks[0] | t.warpMasks[1]) != 0;
        return allowWarp ? 3 : 1;
    }

    /**
     * Derives the local warp model of a block from its samples, which {@link #motionModeKind}
     * collected.
     *
     * @param t the task context positioned at the block
     * @param bs the block size
     * @param mv the block's packed motion vector
     * @return {@code true} when the model warps the prediction, {@code false} when it is a plain
     * translation
     */
    public static boolean deriveWarp(TaskContext t, int bs, int mv) {
        BlockDecoder.warpmv(t, Tables.BLOCK_DIMENSIONS[bs * 4], Tables.BLOCK_DIMENSIONS[bs * 4 + 1], mv);
        return t.warpmv.type > WarpedMotionParams.TRANSLATION;
    }

    /**
     * Predicts a block of a plane with the local warp model {@link #deriveWarp} derived, 8x8
     * samples at a time ({@code warp_affine} of dav1d).
     *
     * @param t the task context positioned at the block
     * @param dst the destination plane
     * @param dstOff the destination offset
     * @param dstride the destination stride
     * @param bs the block size
     * @param pl the plane
     * @param refp the reference picture
     */
    public static void predictWarp(TaskContext t, short[] dst, int dstOff, int dstride, int bs, int pl,
                                   Av1Picture refp) {
        Recon.warpAffine(t, dst, null, dstOff, dstride, bs, pl, refp, t.warpmv);
    }
}
