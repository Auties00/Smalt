/* Ports libvpx (vp9/decoder/vp9_decodemv.c, vp9/common/vp9_mvref_common.h). Copyright (c) 2010, The WebM Project
   authors; BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.video.vp9.internal;

import com.github.auties00.smalt.MediaException;

import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.BLOCK_4X4;
import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.BLOCK_4X8;
import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.BLOCK_8X4;
import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.BLOCK_8X8;
import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.COMPOUND_REFERENCE;
import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.DC_PRED;
import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.GOLDEN_FRAME;
import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.ALTREF_FRAME;
import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.INTRA_FRAME;
import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.LAST_FRAME;
import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.NEARESTMV;
import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.NEARMV;
import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.NEWMV;
import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.NO_REF_FRAME;
import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.REFERENCE_MODE_SELECT;
import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.SEG_LVL_REF_FRAME;
import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.SEG_LVL_SKIP;
import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.SWITCHABLE;
import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.SWITCHABLE_FILTERS;
import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.TX_MODE_SELECT;
import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.ZEROMV;

/**
 * Reads the mode information of VP9 blocks: segment, skip flag, transform size, prediction
 * modes, references and motion vectors, including the candidate motion vector search.
 *
 * <p>A mode reader belongs to one {@link Vp9TileDecoder} and reads the block the tile decoder
 * set up, through the tile decoder's arithmetic decoder and into its counts.
 *
 * @implNote This implementation follows libvpx's {@code vp9/decoder/vp9_decodemv.c}
 * ({@code vp9_read_mode_info}, {@code dec_find_mv_refs},
 * {@code append_sub8x8_mvs_for_idx}) and {@code vp9/common/vp9_mvref_common.h},
 * BSD-3.
 */
final class Vp9ModeReader {
    /**
     * The margin, in eighths of a sample, by which candidate motion vectors may point past the
     * frame edge ({@code MV_BORDER}).
     */
    private static final int MV_BORDER = 16 << 3;

    /**
     * The packed vector libvpx initializes unused second sub-8x8 candidates with.
     */
    private static final int INVALID_MV = 0x80008000;

    /**
     * The frame decoder.
     */
    private final Vp9FrameDecoder fd;

    /**
     * The tile decoder whose blocks this reader reads.
     */
    private final Vp9TileDecoder td;

    /**
     * The candidate list of the last motion vector search.
     */
    private final int[] mvList = new int[2];

    /**
     * The reference vectors new motion vectors are coded against, per reference.
     */
    private final int[] bestRefMvs = new int[2];

    /**
     * The nearest or near vectors of the current sub-8x8 block, per reference.
     */
    private final int[] bestSub8x8 = new int[2];

    /**
     * The vectors assigned to the current block or sub-block, per reference.
     */
    private final int[] assigned = new int[2];

    /**
     * The number of candidate vectors the current search has found.
     */
    private int found;

    /**
     * Creates a mode reader.
     *
     * @param fd the frame decoder
     * @param td the tile decoder
     */
    Vp9ModeReader(Vp9FrameDecoder fd, Vp9TileDecoder td) {
        this.fd = fd;
        this.td = td;
    }

    /**
     * Reads the mode information of the tile decoder's current block and records its motion
     * vectors for the next frame ({@code vp9_read_mode_info}).
     *
     * @param miRow the block's 8x8 unit row
     * @param miCol the block's 8x8 unit column
     * @param xMis the number of the block's unit columns inside the frame
     * @param yMis the number of the block's unit rows inside the frame
     * @throws MediaException.Malformed if the block's modes are invalid
     */
    void read(int miRow, int miCol, int xMis, int yMis) {
        var mi = td.mi;
        if (fd.intraOnlyFrame()) {
            readIntraFrameModeInfo(mi, miRow, miCol, xMis, yMis);
            return;
        }
        readInterFrameModeInfo(mi, miRow, miCol, xMis, yMis);
        var mvs = fd.curFrame.mvs;
        var miCols = fd.miCols;
        for (var h = 0; h < yMis; h++) {
            var index = ((miRow + h) * miCols + miCol) * 4;
            for (var w = 0; w < xMis; w++, index += 4) {
                mvs[index] = mi.ref0;
                mvs[index + 1] = mi.ref1;
                mvs[index + 2] = mi.mv0;
                mvs[index + 3] = mi.mv1;
            }
        }
    }

    /**
     * Returns whether a segment feature is active for a segment ({@code segfeature_active}).
     *
     * @param segmentId the segment
     * @param feature the segment feature, from {@code SEG_LVL_ALT_Q} to {@code SEG_LVL_SKIP}
     * @return {@code true} when segmentation is enabled and the segment has the feature
     */
    private boolean segFeatureActive(int segmentId, int feature) {
        return fd.segEnabled && (fd.segFeatureMask[segmentId] & (1 << feature)) != 0;
    }

    /**
     * Reads the mode information of a block of a key frame or intra-only frame
     * ({@code read_intra_frame_mode_info}).
     *
     * @param mi the block
     * @param miRow the block's 8x8 unit row
     * @param miCol the block's 8x8 unit column
     * @param xMis the number of the block's unit columns inside the frame
     * @param yMis the number of the block's unit rows inside the frame
     */
    private void readIntraFrameModeInfo(ModeInfo mi, int miRow, int miCol, int xMis, int yMis) {
        var r = td.reader;
        var miOffset = miRow * fd.miCols + miCol;
        mi.segmentId = readIntraSegmentId(miOffset, xMis, yMis);
        mi.skip = readSkip(mi.segmentId);
        mi.txSize = readTxSize(mi, true);
        mi.ref0 = INTRA_FRAME;
        mi.ref1 = NO_REF_FRAME;
        var probs = Vp9ProbTables.KF_Y_MODE_PROBS;
        var sub = mi.subModes;
        switch (mi.sbType) {
            case BLOCK_4X4 -> {
                for (var i = 0; i < 4; i++) {
                    sub[i] = r.readTree(Vp9Trees.INTRA_MODE, probs, kfModeOffset(mi, i));
                }
                mi.mode = sub[3];
            }
            case BLOCK_4X8 -> {
                sub[0] = sub[2] = r.readTree(Vp9Trees.INTRA_MODE, probs, kfModeOffset(mi, 0));
                sub[1] = sub[3] = mi.mode = r.readTree(Vp9Trees.INTRA_MODE, probs, kfModeOffset(mi, 1));
            }
            case BLOCK_8X4 -> {
                sub[0] = sub[1] = r.readTree(Vp9Trees.INTRA_MODE, probs, kfModeOffset(mi, 0));
                sub[2] = sub[3] = mi.mode = r.readTree(Vp9Trees.INTRA_MODE, probs, kfModeOffset(mi, 2));
            }
            default -> mi.mode = r.readTree(Vp9Trees.INTRA_MODE, probs, kfModeOffset(mi, 0));
        }
        mi.uvMode = r.readTree(Vp9Trees.INTRA_MODE, Vp9ProbTables.KF_UV_MODE_PROBS, mi.mode * 9);
    }

    /**
     * Returns the offset of the key frame luma mode probabilities of a sub-block, from the
     * modes above and to the left of it ({@code get_y_mode_probs}).
     *
     * @param mi the block
     * @param block the sub-block, from 0 to 3
     * @return the offset in {@link Vp9ProbTables#KF_Y_MODE_PROBS}
     */
    private int kfModeOffset(ModeInfo mi, int block) {
        int above;
        if (block == 0 || block == 1) {
            var aboveMi = td.aboveMi;
            above = aboveMi == null || aboveMi.isInter() ? DC_PRED : aboveMi.yMode(block + 2);
        } else {
            above = mi.subModes[block - 2];
        }
        int left;
        if (block == 0 || block == 2) {
            var leftMi = td.leftMi;
            left = leftMi == null || leftMi.isInter() ? DC_PRED : leftMi.yMode(block + 1);
        } else {
            left = mi.subModes[block - 1];
        }
        return (above * 10 + left) * 9;
    }

    /**
     * Reads the segment of an intra frame block ({@code read_intra_segment_id}).
     *
     * @param miOffset the index of the block's first unit
     * @param xMis the number of the block's unit columns inside the frame
     * @param yMis the number of the block's unit rows inside the frame
     * @return the segment
     */
    private int readIntraSegmentId(int miOffset, int xMis, int yMis) {
        if (!fd.segEnabled) {
            return 0;
        }
        if (!fd.segUpdateMap) {
            copySegmentId(miOffset, xMis, yMis);
            return 0;
        }
        var segmentId = td.reader.readTree(Vp9Trees.SEGMENT, fd.segTreeProbs, 0);
        setSegmentId(miOffset, xMis, yMis, segmentId);
        return segmentId;
    }

    /**
     * Reads the segment of an inter frame block ({@code read_inter_segment_id}).
     *
     * @param mi the block
     * @param miOffset the index of the block's first unit
     * @param xMis the number of the block's unit columns inside the frame
     * @param yMis the number of the block's unit rows inside the frame
     * @return the segment
     */
    private int readInterSegmentId(ModeInfo mi, int miOffset, int xMis, int yMis) {
        if (!fd.segEnabled) {
            return 0;
        }
        var last = fd.lastSegMap;
        var miCols = fd.miCols;
        var predicted = Integer.MAX_VALUE;
        for (var y = 0; y < yMis; y++) {
            for (var x = 0; x < xMis; x++) {
                predicted = Math.min(predicted, last[miOffset + y * miCols + x]);
            }
        }
        if (!fd.segUpdateMap) {
            copySegmentId(miOffset, xMis, yMis);
            return predicted;
        }
        int segmentId;
        var r = td.reader;
        if (fd.segTemporalUpdate) {
            var above = td.aboveMi != null ? td.aboveMi.segIdPredicted : 0;
            var left = td.leftMi != null ? td.leftMi.segIdPredicted : 0;
            mi.segIdPredicted = r.readBool(fd.segPredProbs[above + left]);
            segmentId = mi.segIdPredicted != 0 ? predicted : r.readTree(Vp9Trees.SEGMENT, fd.segTreeProbs, 0);
        } else {
            segmentId = r.readTree(Vp9Trees.SEGMENT, fd.segTreeProbs, 0);
        }
        setSegmentId(miOffset, xMis, yMis, segmentId);
        return segmentId;
    }

    /**
     * Copies the previous segmentation map into the current one over a block
     * ({@code copy_segment_id}).
     *
     * @param miOffset the index of the block's first unit
     * @param xMis the number of the block's unit columns inside the frame
     * @param yMis the number of the block's unit rows inside the frame
     */
    private void copySegmentId(int miOffset, int xMis, int yMis) {
        var miCols = fd.miCols;
        for (var y = 0; y < yMis; y++) {
            System.arraycopy(fd.lastSegMap, miOffset + y * miCols, fd.currentSegMap, miOffset + y * miCols, xMis);
        }
    }

    /**
     * Writes a segment into the current segmentation map over a block ({@code set_segment_id}).
     *
     * @param miOffset the index of the block's first unit
     * @param xMis the number of the block's unit columns inside the frame
     * @param yMis the number of the block's unit rows inside the frame
     * @param segmentId the segment
     */
    private void setSegmentId(int miOffset, int xMis, int yMis, int segmentId) {
        var miCols = fd.miCols;
        for (var y = 0; y < yMis; y++) {
            var start = miOffset + y * miCols;
            for (var x = 0; x < xMis; x++) {
                fd.currentSegMap[start + x] = (byte) segmentId;
            }
        }
    }

    /**
     * Reads the skip flag of a block ({@code read_skip}).
     *
     * @param segmentId the block's segment
     * @return {@code 1} when the block has no residual
     */
    private int readSkip(int segmentId) {
        if (segFeatureActive(segmentId, SEG_LVL_SKIP)) {
            return 1;
        }
        var ctx = (td.aboveMi != null ? td.aboveMi.skip : 0) + (td.leftMi != null ? td.leftMi.skip : 0);
        var skip = td.reader.readBool(fd.fc.probs[Vp9FrameContext.SKIP + ctx]);
        td.counts.counts[Vp9FrameCounts.SKIP + ctx * 2 + skip]++;
        return skip;
    }

    /**
     * Reads or derives the luma transform size of a block ({@code read_tx_size}).
     *
     * @param mi the block
     * @param allowSelect whether the block codes its transform size when the frame lets blocks
     * select it
     * @return the transform size
     */
    private int readTxSize(ModeInfo mi, boolean allowSelect) {
        var maxTxSize = Vp9Constants.MAX_TX_SIZE[mi.sbType];
        if (allowSelect && fd.txMode == TX_MODE_SELECT && mi.sbType >= BLOCK_8X8) {
            var ctx = Vp9PredContexts.txSize(td.aboveMi, td.leftMi, maxTxSize);
            int probs;
            int counts;
            switch (maxTxSize) {
                case 1 -> {
                    probs = Vp9FrameContext.TX_8X8 + ctx;
                    counts = Vp9FrameCounts.TX_8X8 + ctx * 2;
                }
                case 2 -> {
                    probs = Vp9FrameContext.TX_16X16 + ctx * 2;
                    counts = Vp9FrameCounts.TX_16X16 + ctx * 3;
                }
                default -> {
                    probs = Vp9FrameContext.TX_32X32 + ctx * 3;
                    counts = Vp9FrameCounts.TX_32X32 + ctx * 4;
                }
            }
            var r = td.reader;
            var p = fd.fc.probs;
            var txSize = r.readBool(p[probs]);
            if (txSize != 0 && maxTxSize >= 2) {
                txSize += r.readBool(p[probs + 1]);
                if (txSize != 1 && maxTxSize >= 3) {
                    txSize += r.readBool(p[probs + 2]);
                }
            }
            td.counts.counts[counts + txSize]++;
            return txSize;
        }
        return Math.min(maxTxSize, Vp9Constants.TX_MODE_TO_BIGGEST[fd.txMode]);
    }

    /**
     * Reads the mode information of a block of an inter frame ({@code read_inter_frame_mode_info}).
     *
     * @param mi the block
     * @param miRow the block's 8x8 unit row
     * @param miCol the block's 8x8 unit column
     * @param xMis the number of the block's unit columns inside the frame
     * @param yMis the number of the block's unit rows inside the frame
     */
    private void readInterFrameModeInfo(ModeInfo mi, int miRow, int miCol, int xMis, int yMis) {
        mi.segmentId = readInterSegmentId(mi, miRow * fd.miCols + miCol, xMis, yMis);
        mi.skip = readSkip(mi.segmentId);
        var inter = readIsInterBlock(mi.segmentId);
        mi.txSize = readTxSize(mi, mi.skip == 0 || !inter);
        if (inter) {
            readInterBlockModeInfo(mi, miRow, miCol);
        } else {
            readIntraBlockModeInfo(mi);
        }
    }

    /**
     * Reads whether a block of an inter frame is inter predicted ({@code read_is_inter_block}).
     *
     * @param segmentId the block's segment
     * @return {@code true} for an inter block
     */
    private boolean readIsInterBlock(int segmentId) {
        if (segFeatureActive(segmentId, SEG_LVL_REF_FRAME)) {
            return fd.segFeatureData[segmentId * 4 + SEG_LVL_REF_FRAME] != INTRA_FRAME;
        }
        var ctx = Vp9PredContexts.intraInter(td.aboveMi, td.leftMi);
        var inter = td.reader.readBool(fd.fc.probs[Vp9FrameContext.INTRA_INTER + ctx]);
        td.counts.counts[Vp9FrameCounts.INTRA_INTER + ctx * 2 + inter]++;
        return inter != 0;
    }

    /**
     * Reads the modes of an intra block of an inter frame ({@code read_intra_block_mode_info}).
     *
     * @param mi the block
     */
    private void readIntraBlockModeInfo(ModeInfo mi) {
        var sub = mi.subModes;
        switch (mi.sbType) {
            case BLOCK_4X4 -> {
                for (var i = 0; i < 4; i++) {
                    sub[i] = readIntraModeY(0);
                }
                mi.mode = sub[3];
            }
            case BLOCK_4X8 -> {
                sub[0] = sub[2] = readIntraModeY(0);
                sub[1] = sub[3] = mi.mode = readIntraModeY(0);
            }
            case BLOCK_8X4 -> {
                sub[0] = sub[1] = readIntraModeY(0);
                sub[2] = sub[3] = mi.mode = readIntraModeY(0);
            }
            default -> mi.mode = readIntraModeY(Vp9Constants.SIZE_GROUP[mi.sbType]);
        }
        var uvMode = td.reader.readTree(Vp9Trees.INTRA_MODE, fd.fc.probs, Vp9FrameContext.UV_MODE + mi.mode * 9);
        td.counts.counts[Vp9FrameCounts.UV_MODE + mi.mode * 10 + uvMode]++;
        mi.uvMode = uvMode;
        mi.interpFilter = SWITCHABLE_FILTERS;
        mi.ref0 = INTRA_FRAME;
        mi.ref1 = NO_REF_FRAME;
    }

    /**
     * Reads a luma intra mode of an inter frame ({@code read_intra_mode_y}).
     *
     * @param sizeGroup the block's size group
     * @return the mode
     */
    private int readIntraModeY(int sizeGroup) {
        var mode = td.reader.readTree(Vp9Trees.INTRA_MODE, fd.fc.probs, Vp9FrameContext.Y_MODE + sizeGroup * 9);
        td.counts.counts[Vp9FrameCounts.Y_MODE + sizeGroup * 10 + mode]++;
        return mode;
    }

    /**
     * Reads an inter mode ({@code read_inter_mode}).
     *
     * @param ctx the inter mode context
     * @return the mode, from {@link Vp9Constants#NEARESTMV} to {@link Vp9Constants#NEWMV}
     */
    private int readInterMode(int ctx) {
        var mode = td.reader.readTree(Vp9Trees.INTER_MODE, fd.fc.probs, Vp9FrameContext.INTER_MODE + ctx * 3);
        td.counts.counts[Vp9FrameCounts.INTER_MODE + ctx * 4 + mode]++;
        return NEARESTMV + mode;
    }

    /**
     * Reads the references of an inter block ({@code read_ref_frames}).
     *
     * @param mi the block
     * @param segmentId the block's segment
     */
    private void readRefFrames(ModeInfo mi, int segmentId) {
        if (segFeatureActive(segmentId, SEG_LVL_REF_FRAME)) {
            mi.ref0 = fd.segFeatureData[segmentId * 4 + SEG_LVL_REF_FRAME];
            mi.ref1 = NO_REF_FRAME;
            return;
        }
        var r = td.reader;
        var p = fd.fc.probs;
        var c = td.counts.counts;
        var mode = fd.referenceMode;
        if (mode == REFERENCE_MODE_SELECT) {
            var ctx = Vp9PredContexts.referenceMode(td.aboveMi, td.leftMi, fd.compFixedRef);
            mode = r.readBool(p[Vp9FrameContext.COMP_INTER + ctx]);
            c[Vp9FrameCounts.COMP_INTER + ctx * 2 + mode]++;
        }
        if (mode == COMPOUND_REFERENCE) {
            var idx = fd.refFrameSignBias[fd.compFixedRef];
            var ctx = Vp9PredContexts.compRef(td.aboveMi, td.leftMi, idx, fd.compVarRef[0], fd.compVarRef[1],
                    fd.compFixedRef);
            var bit = r.readBool(p[Vp9FrameContext.COMP_REF + ctx]);
            c[Vp9FrameCounts.COMP_REF + ctx * 2 + bit]++;
            if (idx == 0) {
                mi.ref0 = fd.compFixedRef;
                mi.ref1 = fd.compVarRef[bit];
            } else {
                mi.ref1 = fd.compFixedRef;
                mi.ref0 = fd.compVarRef[bit];
            }
        } else {
            var ctx0 = Vp9PredContexts.singleRefP1(td.aboveMi, td.leftMi);
            var bit0 = r.readBool(p[Vp9FrameContext.SINGLE_REF + ctx0 * 2]);
            c[Vp9FrameCounts.SINGLE_REF + ctx0 * 4 + bit0]++;
            if (bit0 != 0) {
                var ctx1 = Vp9PredContexts.singleRefP2(td.aboveMi, td.leftMi);
                var bit1 = r.readBool(p[Vp9FrameContext.SINGLE_REF + ctx1 * 2 + 1]);
                c[Vp9FrameCounts.SINGLE_REF + ctx1 * 4 + 2 + bit1]++;
                mi.ref0 = bit1 != 0 ? ALTREF_FRAME : GOLDEN_FRAME;
            } else {
                mi.ref0 = LAST_FRAME;
            }
            mi.ref1 = NO_REF_FRAME;
        }
    }

    /**
     * Reads the references, modes, filter and motion vectors of an inter block
     * ({@code read_inter_block_mode_info}).
     *
     * @param mi the block
     * @param miRow the block's 8x8 unit row
     * @param miCol the block's 8x8 unit column
     * @throws MediaException.Malformed if a motion vector is out of range or a segment skips a
     * block smaller than 8x8
     */
    private void readInterBlockModeInfo(ModeInfo mi, int miRow, int miCol) {
        var bsize = mi.sbType;
        var allowHp = fd.allowHighPrecisionMv;
        bestRefMvs[0] = 0;
        bestRefMvs[1] = 0;
        readRefFrames(mi, mi.segmentId);
        var compound = mi.hasSecondRef();
        var refs = compound ? 2 : 1;
        var searchBase = bsize * 16;
        var interModeCtx = modeContext(searchBase, miRow, miCol);
        if (segFeatureActive(mi.segmentId, SEG_LVL_SKIP)) {
            mi.mode = ZEROMV;
            if (bsize < BLOCK_8X8) {
                throw new MediaException.Malformed("invalid vp9 segment skip on a block smaller than 8x8");
            }
        } else if (bsize >= BLOCK_8X8) {
            mi.mode = readInterMode(interModeCtx);
        }
        if (fd.interpFilter == SWITCHABLE) {
            var ctx = Vp9PredContexts.switchableInterp(td.aboveMi, td.leftMi);
            var filter = td.reader.readTree(Vp9Trees.SWITCHABLE_INTERP, fd.fc.probs,
                    Vp9FrameContext.SWITCHABLE_INTERP + ctx * 2);
            td.counts.counts[Vp9FrameCounts.SWITCHABLE_INTERP + ctx * 3 + filter]++;
            mi.interpFilter = filter;
        } else {
            mi.interpFilter = fd.interpFilter;
        }
        if (bsize < BLOCK_8X8) {
            var num4w = 1 << td.bmodeBlocksWl;
            var num4h = 1 << td.bmodeBlocksHl;
            var gotMvRefsForNew = false;
            bestSub8x8[1] = INVALID_MV;
            var bMode = NEARESTMV;
            for (var idy = 0; idy < 2; idy += num4h) {
                for (var idx = 0; idx < 2; idx += num4w) {
                    var j = idy * 2 + idx;
                    bMode = readInterMode(interModeCtx);
                    if (bMode == NEARESTMV || bMode == NEARMV) {
                        for (var ref = 0; ref < refs; ref++) {
                            bestSub8x8[ref] = appendSub8x8Mv(mi, searchBase, bMode, j, ref, miRow, miCol);
                        }
                    } else if (bMode == NEWMV && !gotMvRefsForNew) {
                        for (var ref = 0; ref < refs; ref++) {
                            findMvRefs(NEWMV, ref == 0 ? mi.ref0 : mi.ref1, searchBase, miRow, miCol, -1);
                            bestRefMvs[ref] = lowerMvPrecision(mvList[0], allowHp);
                            gotMvRefsForNew = true;
                        }
                    }
                    assignMv(bMode, bestRefMvs, bestSub8x8, compound, allowHp);
                    mi.subMv0[j] = assigned[0];
                    mi.subMv1[j] = assigned[1];
                    if (num4h == 2) {
                        mi.subMv0[j + 2] = assigned[0];
                        mi.subMv1[j + 2] = assigned[1];
                    }
                    if (num4w == 2) {
                        mi.subMv0[j + 1] = assigned[0];
                        mi.subMv1[j + 1] = assigned[1];
                    }
                }
            }
            mi.mode = bMode;
            mi.mv0 = mi.subMv0[3];
            mi.mv1 = mi.subMv1[3];
        } else {
            if (mi.mode != ZEROMV) {
                for (var ref = 0; ref < refs; ref++) {
                    var count = findMvRefs(mi.mode, ref == 0 ? mi.ref0 : mi.ref1, searchBase, miRow, miCol, -1);
                    bestRefMvs[ref] = lowerMvPrecision(mvList[count - 1], allowHp);
                }
            }
            assigned[1] = mi.mv1;
            assignMv(mi.mode, bestRefMvs, bestRefMvs, compound, allowHp);
            mi.mv0 = assigned[0];
            mi.mv1 = assigned[1];
        }
    }

    /**
     * Computes the motion vectors of a mode into {@link #assigned} ({@code assign_mv}).
     *
     * @param mode the inter mode
     * @param refMvs the vectors new motion vectors are coded against, per reference
     * @param nearNearest the nearest or near vectors, per reference
     * @param compound whether the block has two references
     * @param allowHp whether the frame allows high precision vectors
     * @throws MediaException.Malformed if a new vector is out of range
     */
    private void assignMv(int mode, int[] refMvs, int[] nearNearest, boolean compound, boolean allowHp) {
        switch (mode) {
            case NEWMV -> {
                var valid = true;
                for (var i = 0; i < (compound ? 2 : 1); i++) {
                    var mv = readMv(refMvs[i], allowHp);
                    assigned[i] = mv;
                    var row = mv >> 16;
                    var col = (short) mv;
                    valid &= row > -16384 && row < 16383 && col > -16384 && col < 16383;
                }
                if (!valid) {
                    throw new MediaException.Malformed("vp9 motion vector out of range");
                }
            }
            case NEARMV, NEARESTMV -> {
                assigned[0] = nearNearest[0];
                assigned[1] = nearNearest[1];
            }
            default -> {
                assigned[0] = 0;
                assigned[1] = 0;
            }
        }
    }

    /**
     * Reads a motion vector coded against a reference vector ({@code read_mv}).
     *
     * @param ref the packed reference vector
     * @param allowHp whether the frame allows high precision vectors
     * @return the packed vector
     */
    private int readMv(int ref, boolean allowHp) {
        var r = td.reader;
        var joint = r.readTree(Vp9Trees.MV_JOINT, fd.fc.probs, Vp9FrameContext.MV_JOINTS);
        var refRow = ref >> 16;
        var refCol = (short) ref;
        var useHp = allowHp && Math.abs(refRow) < 64 && Math.abs(refCol) < 64;
        var diffRow = 0;
        var diffCol = 0;
        if (joint == 2 || joint == 3) {
            diffRow = readMvComponent(0, useHp);
        }
        if (joint == 1 || joint == 3) {
            diffCol = readMvComponent(1, useHp);
        }
        td.counts.countMv(diffRow, diffCol);
        return ModeInfo.packMv(refRow + diffRow, refCol + diffCol);
    }

    /**
     * Reads one nonzero motion vector component ({@code read_mv_component}).
     *
     * @param component 0 for the vertical component, 1 for the horizontal one
     * @param useHp whether the component codes its eighth-sample bit
     * @return the component
     */
    private int readMvComponent(int component, boolean useHp) {
        var r = td.reader;
        var p = fd.fc.probs;
        var sign = r.readBool(p[Vp9FrameContext.MV_SIGN + component]);
        var mvClass = r.readTree(Vp9Trees.MV_CLASS, p, Vp9FrameContext.MV_CLASSES + component * 10);
        var class0 = mvClass == 0;
        int d;
        int mag;
        if (class0) {
            d = r.readBool(p[Vp9FrameContext.MV_CLASS0 + component]);
            mag = 0;
        } else {
            d = 0;
            for (var i = 0; i < mvClass; i++) {
                d |= r.readBool(p[Vp9FrameContext.MV_BITS + component * 10 + i]) << i;
            }
            mag = 2 << (mvClass + 2);
        }
        var fr = r.readTree(Vp9Trees.MV_FP, p, class0 ? Vp9FrameContext.MV_CLASS0_FP + (component * 2 + d) * 3
                : Vp9FrameContext.MV_FP + component * 3);
        var hp = useHp ? r.readBool(p[class0 ? Vp9FrameContext.MV_CLASS0_HP + component : Vp9FrameContext.MV_HP + component])
                : 1;
        mag += ((d << 3) | (fr << 1) | hp) + 1;
        return sign != 0 ? -mag : mag;
    }

    /**
     * Rounds an odd motion vector component towards zero when high precision is not used
     * ({@code lower_mv_precision}).
     *
     * @param mv the packed vector
     * @param allowHp whether the frame allows high precision vectors
     * @return the packed vector
     */
    private static int lowerMvPrecision(int mv, boolean allowHp) {
        var row = mv >> 16;
        var col = (short) mv;
        var useHp = allowHp && Math.abs(row) < 64 && Math.abs(col) < 64;
        if (!useHp) {
            if ((row & 1) != 0) {
                row += row > 0 ? -1 : 1;
            }
            if ((col & 1) != 0) {
                col += col > 0 ? -1 : 1;
            }
        }
        return ModeInfo.packMv(row, col);
    }

    /**
     * Returns whether a candidate position lies inside the tile's columns and the frame's rows
     * ({@code is_inside}).
     *
     * @param miRow the current block's unit row
     * @param miCol the current block's unit column
     * @param row the candidate's row offset
     * @param col the candidate's column offset
     * @return {@code true} when the candidate exists
     */
    private boolean isInside(int miRow, int miCol, int row, int col) {
        return !(miRow + row < 0 || miCol + col < td.miColStart || miRow + row >= fd.miRows
                || miCol + col >= td.miColEnd);
    }

    /**
     * Returns the inter mode context of a block from the modes of its two nearest candidates
     * ({@code get_mode_context}).
     *
     * @param searchBase the offset of the block size's candidates in
     * {@link Vp9Constants#MV_REF_BLOCKS}
     * @param miRow the block's unit row
     * @param miCol the block's unit column
     * @return the context, from 0 to 6
     */
    private int modeContext(int searchBase, int miRow, int miCol) {
        var counter = 0;
        for (var i = 0; i < 2; i++) {
            var row = Vp9Constants.MV_REF_BLOCKS[searchBase + i * 2];
            var col = Vp9Constants.MV_REF_BLOCKS[searchBase + i * 2 + 1];
            if (isInside(miRow, miCol, row, col)) {
                var candidate = fd.miGrid[(miRow + row) * fd.miCols + miCol + col];
                counter += Vp9Constants.MODE_2_COUNTER[candidate.mode];
            }
        }
        return Vp9Constants.COUNTER_TO_CONTEXT[counter];
    }

    /**
     * Returns a candidate's vector, negated when the candidate's reference lies on the other
     * side in time of the searched reference ({@code scale_mv}).
     *
     * @param mv the packed vector
     * @param candidateRef the candidate's reference
     * @param refFrame the searched reference
     * @return the packed vector
     */
    private int signedMv(int mv, int candidateRef, int refFrame) {
        if (fd.refFrameSignBias[candidateRef] != fd.refFrameSignBias[refFrame]) {
            return ModeInfo.packMv(-(mv >> 16), -(short) mv);
        }
        return mv;
    }

    /**
     * Adds a vector to the candidate list unless it repeats the first one, and returns whether
     * the search is complete ({@code ADD_MV_REF_LIST_EB}).
     *
     * @param mv the packed vector
     * @param earlyBreak whether the first vector completes the search
     * @return {@code true} when the search is complete
     */
    private boolean add(int mv, boolean earlyBreak) {
        if (found != 0) {
            if (mv != mvList[0]) {
                mvList[found] = mv;
                found++;
                return true;
            }
            return false;
        }
        mvList[found++] = mv;
        return earlyBreak;
    }

    /**
     * Searches the neighborhood and the previous frame for the candidate motion vectors of a
     * reference, leaving them in {@link #mvList} ({@code dec_find_mv_refs}).
     *
     * @param mode the inter mode, which decides whether one candidate suffices
     * @param refFrame the reference
     * @param searchBase the offset of the block size's candidates in
     * {@link Vp9Constants#MV_REF_BLOCKS}
     * @param miRow the block's unit row
     * @param miCol the block's unit column
     * @param block the sub-8x8 block whose nearest candidates use sub-block vectors, or
     * {@code -1}
     * @return the number of candidates the caller uses
     */
    private int findMvRefs(int mode, int refFrame, int searchBase, int miRow, int miCol, int block) {
        mvList[0] = 0;
        mvList[1] = 0;
        found = 0;
        var differentRefFound = false;
        var earlyBreak = mode != NEARMV;
        var grid = fd.miGrid;
        var miCols = fd.miCols;
        var prevMvs = fd.usePrevFrameMvs ? fd.prevFrame.mvs : null;
        var prevIndex = (miRow * miCols + miCol) * 4;
        var done = false;
        var i = 0;
        if (block >= 0) {
            for (; i < 2 && !done; i++) {
                var row = Vp9Constants.MV_REF_BLOCKS[searchBase + i * 2];
                var col = Vp9Constants.MV_REF_BLOCKS[searchBase + i * 2 + 1];
                if (isInside(miRow, miCol, row, col)) {
                    var candidate = grid[(miRow + row) * miCols + miCol + col];
                    differentRefFound = true;
                    if (candidate.ref0 == refFrame) {
                        done = add(subBlockMv(candidate, 0, col, block), earlyBreak);
                    } else if (candidate.ref1 == refFrame) {
                        done = add(subBlockMv(candidate, 1, col, block), earlyBreak);
                    }
                }
            }
            if (done) {
                return finish();
            }
            i = 2;
        }
        for (; i < 8; i++) {
            var row = Vp9Constants.MV_REF_BLOCKS[searchBase + i * 2];
            var col = Vp9Constants.MV_REF_BLOCKS[searchBase + i * 2 + 1];
            if (isInside(miRow, miCol, row, col)) {
                var candidate = grid[(miRow + row) * miCols + miCol + col];
                differentRefFound = true;
                if (candidate.ref0 == refFrame) {
                    if (add(candidate.mv0, earlyBreak)) {
                        return finish();
                    }
                } else if (candidate.ref1 == refFrame) {
                    if (add(candidate.mv1, earlyBreak)) {
                        return finish();
                    }
                }
            }
        }
        if (prevMvs != null) {
            if (prevMvs[prevIndex] == refFrame) {
                if (add(prevMvs[prevIndex + 2], earlyBreak)) {
                    return finish();
                }
            } else if (prevMvs[prevIndex + 1] == refFrame) {
                if (add(prevMvs[prevIndex + 3], earlyBreak)) {
                    return finish();
                }
            }
        }
        if (differentRefFound) {
            for (i = 0; i < 8; i++) {
                var row = Vp9Constants.MV_REF_BLOCKS[searchBase + i * 2];
                var col = Vp9Constants.MV_REF_BLOCKS[searchBase + i * 2 + 1];
                if (isInside(miRow, miCol, row, col)) {
                    var candidate = grid[(miRow + row) * miCols + miCol + col];
                    if (candidate.isInter()) {
                        if (candidate.ref0 != refFrame
                                && add(signedMv(candidate.mv0, candidate.ref0, refFrame), earlyBreak)) {
                            return finish();
                        }
                        if (candidate.hasSecondRef() && candidate.ref1 != refFrame && candidate.mv1 != candidate.mv0
                                && add(signedMv(candidate.mv1, candidate.ref1, refFrame), earlyBreak)) {
                            return finish();
                        }
                    }
                }
            }
        }
        if (prevMvs != null) {
            var prevRef0 = prevMvs[prevIndex];
            var prevRef1 = prevMvs[prevIndex + 1];
            var prevMv0 = prevMvs[prevIndex + 2];
            var prevMv1 = prevMvs[prevIndex + 3];
            if (prevRef0 != refFrame && prevRef0 > INTRA_FRAME) {
                if (add(signedMv(prevMv0, prevRef0, refFrame), earlyBreak)) {
                    return finish();
                }
            }
            if (prevRef1 > INTRA_FRAME && prevRef1 != refFrame && prevMv1 != prevMv0) {
                if (add(signedMv(prevMv1, prevRef1, refFrame), earlyBreak)) {
                    return finish();
                }
            }
        }
        found = mode == NEARMV ? 2 : 1;
        return finish();
    }

    /**
     * Clamps the candidates found so far so that they point at most 16 samples past the frame
     * edge, and returns their number ({@code clamp_mv_ref} at {@code Done}).
     *
     * @return the number of candidates
     */
    private int finish() {
        var minCol = td.mbToLeftEdge - MV_BORDER;
        var maxCol = td.mbToRightEdge + MV_BORDER;
        var minRow = td.mbToTopEdge - MV_BORDER;
        var maxRow = td.mbToBottomEdge + MV_BORDER;
        for (var i = 0; i < found; i++) {
            var mv = mvList[i];
            var row = (short) Vp9TileDecoder.clamp(mv >> 16, minRow, maxRow);
            var col = (short) Vp9TileDecoder.clamp((short) mv, minCol, maxCol);
            mvList[i] = ModeInfo.packMv(row, col);
        }
        return found;
    }

    /**
     * Returns the vector of a candidate for a sub-8x8 search: the vector of the candidate's
     * sub-block nearest to the current sub-block when the candidate is itself split
     * ({@code get_sub_block_mv}).
     *
     * @param candidate the candidate block
     * @param which 0 for the first reference, 1 for the second
     * @param searchCol the candidate's column offset, 0 when it lies above
     * @param block the current sub-block
     * @return the packed vector
     */
    private static int subBlockMv(ModeInfo candidate, int which, int searchCol, int block) {
        if (block >= 0 && candidate.sbType < BLOCK_8X8) {
            var sub = Vp9Constants.IDX_N_COLUMN_TO_SUBBLOCK[block * 2 + (searchCol == 0 ? 1 : 0)];
            return which == 0 ? candidate.subMv0[sub] : candidate.subMv1[sub];
        }
        return which == 0 ? candidate.mv0 : candidate.mv1;
    }

    /**
     * Returns the nearest or near vector of a sub-8x8 block ({@code append_sub8x8_mvs_for_idx}).
     *
     * @param mi the block
     * @param searchBase the offset of the block size's candidates
     * @param bMode the sub-block's mode, nearest or near
     * @param block the sub-block
     * @param ref the reference, 0 or 1
     * @param miRow the block's unit row
     * @param miCol the block's unit column
     * @return the packed vector
     */
    private int appendSub8x8Mv(ModeInfo mi, int searchBase, int bMode, int block, int ref, int miRow, int miCol) {
        var refFrame = ref == 0 ? mi.ref0 : mi.ref1;
        var sub = ref == 0 ? mi.subMv0 : mi.subMv1;
        switch (block) {
            case 0 -> {
                var count = findMvRefs(bMode, refFrame, searchBase, miRow, miCol, block);
                return mvList[count - 1];
            }
            case 1, 2 -> {
                if (bMode == NEARESTMV) {
                    return sub[0];
                }
                findMvRefs(bMode, refFrame, searchBase, miRow, miCol, block);
                for (var n = 0; n < 2; n++) {
                    if (sub[0] != mvList[n]) {
                        return mvList[n];
                    }
                }
                return 0;
            }
            default -> {
                if (bMode == NEARESTMV) {
                    return sub[2];
                }
                if (sub[2] != sub[1]) {
                    return sub[1];
                }
                if (sub[2] != sub[0]) {
                    return sub[0];
                }
                findMvRefs(bMode, refFrame, searchBase, miRow, miCol, block);
                for (var n = 0; n < 2; n++) {
                    if (sub[2] != mvList[n]) {
                        return mvList[n];
                    }
                }
                return 0;
            }
        }
    }
}
