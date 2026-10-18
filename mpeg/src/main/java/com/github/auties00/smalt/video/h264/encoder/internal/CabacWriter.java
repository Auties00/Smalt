package com.github.auties00.smalt.video.h264.encoder.internal;

import com.github.auties00.smalt.video.h264.internal.H264Tables;

import static com.github.auties00.smalt.video.h264.internal.CabacTables.ABS_OFFSET;
import static com.github.auties00.smalt.video.h264.internal.CabacTables.CBF_OFFSET;
import static com.github.auties00.smalt.video.h264.internal.CabacTables.LAST_8X8;
import static com.github.auties00.smalt.video.h264.internal.CabacTables.LAST_OFFSET;
import static com.github.auties00.smalt.video.h264.internal.CabacTables.SIG_8X8;
import static com.github.auties00.smalt.video.h264.internal.CabacTables.SIG_OFFSET;

/**
 * Binarizes the macroblock-layer syntax elements for CABAC and selects their contexts (clauses
 * 9.3.2 and 9.3.3.1).
 *
 * <p>The writer drives a {@link CabacCoder}, so the same code both encodes a macroblock and
 * estimates its cost. Neighbor-dependent context increments read earlier blocks of the current
 * macroblock from the {@link Macroblock} and neighboring macroblocks from the
 * {@link PictureInfo}.
 */
final class CabacWriter {
    /**
     * The block category of the Intra_16x16 luma DC levels.
     */
    static final int CAT_LUMA_DC = 0;

    /**
     * The block category of the Intra_16x16 luma AC levels.
     */
    static final int CAT_LUMA_AC = 1;

    /**
     * The block category of 4x4 luma levels.
     */
    static final int CAT_LUMA_4x4 = 2;

    /**
     * The block category of chroma DC levels.
     */
    static final int CAT_CHROMA_DC = 3;

    /**
     * The block category of chroma AC levels.
     */
    static final int CAT_CHROMA_AC = 4;

    /**
     * The block category of 8x8 luma levels.
     */
    static final int CAT_LUMA_8x8 = 5;

    /**
     * Constructs a writer.
     */
    CabacWriter() {
    }

    /**
     * Codes {@code mb_skip_flag} of a P slice macroblock.
     *
     * @param coder the coder that encodes or estimates the bins
     * @param pi the picture tables
     * @param ctx the macroblock's position and neighbors
     * @param skip whether the macroblock is skipped
     */
    static void writeSkipFlag(CabacCoder coder, PictureInfo pi, MbContext ctx, boolean skip, boolean bSlice) {
        var inc = 0;
        if (ctx.availA && !MbType.isSkip(pi.type[ctx.addrA])) {
            inc++;
        }
        if (ctx.availB && !MbType.isSkip(pi.type[ctx.addrB])) {
            inc++;
        }
        coder.decision((bSlice ? 24 : 11) + inc, skip ? 1 : 0);
    }

    /**
     * Codes {@code end_of_slice_flag}.
     *
     * @param coder the coder that encodes or estimates the bins
     * @param last whether the macroblock is the last of the slice
     */
    static void writeEndOfSlice(CabacCoder coder, boolean last) {
        coder.terminate(last ? 1 : 0);
    }

    /**
     * Codes one macroblock's {@code macroblock_layer()} except the {@code I_PCM} samples.
     *
     * @param coder the coder that encodes or estimates the bins
     * @param pi the picture tables
     * @param ctx the macroblock's position and neighbors
     * @param mb the macroblock
     * @param pSlice whether the slice is a P slice
     * @param refCount the number of active reference indices
     * @param qpDelta the {@code mb_qp_delta} to code when the syntax carries one
     * @param prevQpDeltaNonZero whether the previous macroblock of the slice coded a non-zero
     * {@code mb_qp_delta}
     * @param transform8x8Mode whether the picture parameter set enables the 8x8 transform
     */
    static void writeMacroblock(CabacCoder coder, PictureInfo pi, MbContext ctx, Macroblock mb, boolean pSlice,
                                boolean bSlice, int refCount, int qpDelta, boolean prevQpDeltaNonZero,
                                boolean transform8x8Mode) {
        var type = mb.type;
        writeMbType(coder, pi, ctx, mb, pSlice, bSlice);
        if (type == MbType.I_PCM) {
            return;
        }
        if (type == MbType.I4x4 || type == MbType.I8x8) {
            if (transform8x8Mode) {
                writeTransform8x8(coder, pi, ctx, type == MbType.I8x8);
            }
            writeIntraModes(coder, pi, ctx, mb);
            writeChromaMode(coder, pi, ctx, mb.chromaMode);
        } else if (type == MbType.I16x16) {
            writeChromaMode(coder, pi, ctx, mb.chromaMode);
        } else {
            writeInterPrediction(coder, pi, ctx, mb, refCount);
        }
        if (type != MbType.I16x16) {
            writeCbp(coder, pi, ctx, mb.cbp);
            if ((mb.cbp & 15) != 0 && transform8x8Mode && !MbType.isIntra(type) && CavlcWriter.allSub8x8(mb)) {
                writeTransform8x8(coder, pi, ctx, mb.transform8x8);
            }
        }
        if (mb.cbp != 0 || type == MbType.I16x16) {
            writeQpDelta(coder, qpDelta, prevQpDeltaNonZero);
            writeResidual(coder, pi, ctx, mb);
        }
    }

    /**
     * Codes {@code mb_type}.
     *
     * @param coder the coder that encodes or estimates the bins
     * @param pi the picture tables
     * @param ctx the macroblock's position and neighbors
     * @param mb the macroblock
     * @param pSlice whether the slice is a P slice
     */
    private static void writeMbType(CabacCoder coder, PictureInfo pi, MbContext ctx, Macroblock mb, boolean pSlice,
                                    boolean bSlice) {
        var type = mb.type;
        int base;
        if (bSlice) {
            var inc = 0;
            if (ctx.availA && pi.type[ctx.addrA] != MbType.B_SKIP && pi.type[ctx.addrA] != MbType.B_DIRECT) {
                inc++;
            }
            if (ctx.availB && pi.type[ctx.addrB] != MbType.B_SKIP && pi.type[ctx.addrB] != MbType.B_DIRECT) {
                inc++;
            }
            switch (type) {
                case MbType.B_DIRECT -> {
                    coder.decision(27 + inc, 0);
                    return;
                }
                case MbType.B_L0 -> {
                    coder.decision(27 + inc, 1);
                    coder.decision(27 + 3, 0);
                    coder.decision(27 + 5, 0);
                    return;
                }
                case MbType.B_L1 -> {
                    coder.decision(27 + inc, 1);
                    coder.decision(27 + 3, 0);
                    coder.decision(27 + 5, 1);
                    return;
                }
                case MbType.B_BI -> {
                    coder.decision(27 + inc, 1);
                    coder.decision(27 + 3, 1);
                    coder.decision(27 + 4, 0);
                    coder.decision(27 + 5, 0);
                    coder.decision(27 + 5, 0);
                    coder.decision(27 + 5, 0);
                    return;
                }
                default -> {
                    coder.decision(27 + inc, 1);
                    coder.decision(27 + 3, 1);
                    coder.decision(27 + 4, 1);
                    coder.decision(27 + 5, 1);
                    coder.decision(27 + 5, 0);
                    coder.decision(27 + 5, 1);
                }
            }
            base = 32;
            if (type == MbType.I4x4 || type == MbType.I8x8) {
                coder.decision(32, 0);
                return;
            }
            coder.decision(32, 1);
        } else if (pSlice) {
            switch (type) {
                case MbType.P16x16 -> {
                    coder.decision(14, 0);
                    coder.decision(15, 0);
                    coder.decision(16, 0);
                    return;
                }
                case MbType.P16x8 -> {
                    coder.decision(14, 0);
                    coder.decision(15, 1);
                    coder.decision(17, 1);
                    return;
                }
                case MbType.P8x16 -> {
                    coder.decision(14, 0);
                    coder.decision(15, 1);
                    coder.decision(17, 0);
                    return;
                }
                case MbType.P8x8 -> {
                    coder.decision(14, 0);
                    coder.decision(15, 0);
                    coder.decision(16, 1);
                    return;
                }
                default -> coder.decision(14, 1);
            }
            base = 17;
            if (type == MbType.I4x4 || type == MbType.I8x8) {
                coder.decision(17, 0);
                return;
            }
            coder.decision(17, 1);
        } else {
            var inc = 0;
            if (ctx.availA && pi.type[ctx.addrA] != MbType.I4x4 && pi.type[ctx.addrA] != MbType.I8x8) {
                inc++;
            }
            if (ctx.availB && pi.type[ctx.addrB] != MbType.I4x4 && pi.type[ctx.addrB] != MbType.I8x8) {
                inc++;
            }
            if (type == MbType.I4x4 || type == MbType.I8x8) {
                coder.decision(3 + inc, 0);
                return;
            }
            coder.decision(3 + inc, 1);
            base = 3;
        }
        if (type == MbType.I_PCM) {
            coder.terminate(1);
            return;
        }
        coder.terminate(0);
        var chroma = mb.cbp >> 4;
        if (pSlice || bSlice) {
            var offset = bSlice ? 32 : 17;
            coder.decision(offset + 1, (mb.cbp & 15) != 0 ? 1 : 0);
            coder.decision(offset + 2, chroma != 0 ? 1 : 0);
            if (chroma != 0) {
                coder.decision(offset + 2, chroma == 2 ? 1 : 0);
            }
            coder.decision(offset + 3, mb.intra16x16Mode >> 1);
            coder.decision(offset + 3, mb.intra16x16Mode & 1);
        } else {
            coder.decision(base + 3, (mb.cbp & 15) != 0 ? 1 : 0);
            coder.decision(base + 4, chroma != 0 ? 1 : 0);
            if (chroma != 0) {
                coder.decision(base + 5, chroma == 2 ? 1 : 0);
            }
            coder.decision(base + 6, mb.intra16x16Mode >> 1);
            coder.decision(base + 7, mb.intra16x16Mode & 1);
        }
    }

    /**
     * Codes {@code transform_size_8x8_flag}.
     *
     * @param coder the coder that encodes or estimates the bins
     * @param pi the picture tables
     * @param ctx the macroblock's position and neighbors
     * @param flag the flag value
     */
    private static void writeTransform8x8(CabacCoder coder, PictureInfo pi, MbContext ctx, boolean flag) {
        var inc = 0;
        if (ctx.availA && pi.transform8x8[ctx.addrA]) {
            inc++;
        }
        if (ctx.availB && pi.transform8x8[ctx.addrB]) {
            inc++;
        }
        coder.decision(399 + inc, flag ? 1 : 0);
    }

    /**
     * Codes the intra prediction modes of an {@code I_NxN} macroblock.
     *
     * @param coder the coder that encodes or estimates the bins
     * @param pi the picture tables
     * @param ctx the macroblock's position and neighbors
     * @param mb the macroblock
     */
    private static void writeIntraModes(CabacCoder coder, PictureInfo pi, MbContext ctx, Macroblock mb) {
        if (mb.type == MbType.I8x8) {
            for (var b8 = 0; b8 < 4; b8++) {
                var predicted = Neighbors.predictedIntraMode(pi, ctx, mb, (b8 & 1) * 2, (b8 >> 1) * 2);
                writeIntraMode(coder, mb.intraModes[b8], predicted);
            }
        } else {
            for (var blk = 0; blk < 16; blk++) {
                var predicted = Neighbors.predictedIntraMode(pi, ctx, mb, H264Tables.BLOCK_X[blk], H264Tables.BLOCK_Y[blk]);
                writeIntraMode(coder, mb.intraModes[blk], predicted);
            }
        }
    }

    /**
     * Codes {@code prev_intra_pred_mode_flag} and {@code rem_intra_pred_mode}.
     *
     * @param coder the coder that encodes or estimates the bins
     * @param mode the actual mode
     * @param predicted the predicted mode
     */
    static void writeIntraMode(CabacCoder coder, int mode, int predicted) {
        if (mode == predicted) {
            coder.decision(68, 1);
            return;
        }
        coder.decision(68, 0);
        var rem = mode < predicted ? mode : mode - 1;
        coder.decision(69, rem & 1);
        coder.decision(69, (rem >> 1) & 1);
        coder.decision(69, (rem >> 2) & 1);
    }

    /**
     * Codes {@code intra_chroma_pred_mode}.
     *
     * @param coder the coder that encodes or estimates the bins
     * @param pi the picture tables
     * @param ctx the macroblock's position and neighbors
     * @param mode the chroma prediction mode
     */
    static void writeChromaMode(CabacCoder coder, PictureInfo pi, MbContext ctx, int mode) {
        var inc = 0;
        if (ctx.availA && chromaModeCondition(pi, ctx.addrA)) {
            inc++;
        }
        if (ctx.availB && chromaModeCondition(pi, ctx.addrB)) {
            inc++;
        }
        coder.decision(64 + inc, mode != 0 ? 1 : 0);
        if (mode != 0) {
            coder.decision(67, mode != 1 ? 1 : 0);
            if (mode != 1) {
                coder.decision(67, mode != 2 ? 1 : 0);
            }
        }
    }

    /**
     * Returns the {@code intra_chroma_pred_mode} context condition of a neighbor.
     *
     * @param pi the picture tables
     * @param address the neighbor's address
     * @return whether the neighbor is intra (not {@code I_PCM}) with a non-DC chroma mode
     */
    private static boolean chromaModeCondition(PictureInfo pi, int address) {
        var type = pi.type[address];
        return MbType.isIntra(type) && type != MbType.I_PCM && pi.chromaMode[address] != 0;
    }

    /**
     * Codes the reference indices and motion vector differences of an inter macroblock.
     *
     * @param coder the coder that encodes or estimates the bins
     * @param pi the picture tables
     * @param ctx the macroblock's position and neighbors
     * @param mb the macroblock
     * @param refCount the number of active reference indices
     */
    private static void writeInterPrediction(CabacCoder coder, PictureInfo pi, MbContext ctx, Macroblock mb,
                                             int refCount) {
        switch (mb.type) {
            case MbType.B_DIRECT -> {
            }
            case MbType.B_L0 -> writeMvd(coder, pi, ctx, mb, 0, 0, 0);
            case MbType.B_L1 -> writeMvd(coder, pi, ctx, mb, 0, 0, 1);
            case MbType.B_BI -> {
                writeMvd(coder, pi, ctx, mb, 0, 0, 0);
                writeMvd(coder, pi, ctx, mb, 0, 0, 1);
            }
            case MbType.P16x16 -> {
                if (refCount > 1) {
                    writeRef(coder, pi, ctx, mb, 0, 0);
                }
                writeMvd(coder, pi, ctx, mb, 0, 0);
            }
            case MbType.P16x8 -> {
                if (refCount > 1) {
                    writeRef(coder, pi, ctx, mb, 0, 0);
                    writeRef(coder, pi, ctx, mb, 0, 2);
                }
                writeMvd(coder, pi, ctx, mb, 0, 0);
                writeMvd(coder, pi, ctx, mb, 0, 2);
            }
            case MbType.P8x16 -> {
                if (refCount > 1) {
                    writeRef(coder, pi, ctx, mb, 0, 0);
                    writeRef(coder, pi, ctx, mb, 2, 0);
                }
                writeMvd(coder, pi, ctx, mb, 0, 0);
                writeMvd(coder, pi, ctx, mb, 2, 0);
            }
            default -> {
                for (var i = 0; i < 4; i++) {
                    writeSubType(coder, mb.subTypes[i]);
                }
                if (refCount > 1) {
                    for (var i = 0; i < 4; i++) {
                        writeRef(coder, pi, ctx, mb, (i & 1) * 2, (i >> 1) * 2);
                    }
                }
                for (var i = 0; i < 4; i++) {
                    var x4 = (i & 1) * 2;
                    var y4 = (i >> 1) * 2;
                    switch (mb.subTypes[i]) {
                        case MbType.SUB_8x8 -> writeMvd(coder, pi, ctx, mb, x4, y4);
                        case MbType.SUB_8x4 -> {
                            writeMvd(coder, pi, ctx, mb, x4, y4);
                            writeMvd(coder, pi, ctx, mb, x4, y4 + 1);
                        }
                        case MbType.SUB_4x8 -> {
                            writeMvd(coder, pi, ctx, mb, x4, y4);
                            writeMvd(coder, pi, ctx, mb, x4 + 1, y4);
                        }
                        default -> {
                            writeMvd(coder, pi, ctx, mb, x4, y4);
                            writeMvd(coder, pi, ctx, mb, x4 + 1, y4);
                            writeMvd(coder, pi, ctx, mb, x4, y4 + 1);
                            writeMvd(coder, pi, ctx, mb, x4 + 1, y4 + 1);
                        }
                    }
                }
            }
        }
    }

    /**
     * Codes {@code sub_mb_type} of a P slice.
     *
     * @param coder the coder that encodes or estimates the bins
     * @param subType the sub-macroblock type
     */
    private static void writeSubType(CabacCoder coder, int subType) {
        switch (subType) {
            case MbType.SUB_8x8 -> coder.decision(21, 1);
            case MbType.SUB_8x4 -> {
                coder.decision(21, 0);
                coder.decision(22, 0);
            }
            case MbType.SUB_4x8 -> {
                coder.decision(21, 0);
                coder.decision(22, 1);
                coder.decision(23, 1);
            }
            default -> {
                coder.decision(21, 0);
                coder.decision(22, 1);
                coder.decision(23, 0);
            }
        }
    }

    /**
     * Codes {@code ref_idx_l0} of the partition whose top-left 4x4 block is given.
     *
     * @param coder the coder that encodes or estimates the bins
     * @param pi the picture tables
     * @param ctx the macroblock's position and neighbors
     * @param mb the macroblock
     * @param x4 the partition's left column in 4x4 units
     * @param y4 the partition's top row in 4x4 units
     */
    private static void writeRef(CabacCoder coder, PictureInfo pi, MbContext ctx, Macroblock mb, int x4, int y4) {
        var inc = 0;
        if (x4 > 0) {
            inc += mb.refs[(y4 >> 1) * 2 + ((x4 - 1) >> 1)] > 0 ? 1 : 0;
        } else if (ctx.availA && refCondition(pi, ctx.addrA, (y4 >> 1) * 2 + 1)) {
            inc++;
        }
        if (y4 > 0) {
            inc += mb.refs[((y4 - 1) >> 1) * 2 + (x4 >> 1)] > 0 ? 2 : 0;
        } else if (ctx.availB && refCondition(pi, ctx.addrB, 2 + (x4 >> 1))) {
            inc += 2;
        }
        var ref = mb.refs[(y4 >> 1) * 2 + (x4 >> 1)];
        if (ref == 0) {
            coder.decision(54 + inc, 0);
            return;
        }
        coder.decision(54 + inc, 1);
        var ctxIdx = 58;
        for (var i = 1; i < ref; i++) {
            coder.decision(ctxIdx, 1);
            ctxIdx = 59;
        }
        coder.decision(ctxIdx, 0);
    }

    /**
     * Returns the {@code ref_idx_l0} context condition of a neighboring 8x8 quadrant.
     *
     * @param pi the picture tables
     * @param address the neighbor's address
     * @param quadrant the neighbor's 8x8 quadrant
     * @return whether the neighbor is a coded inter macroblock with a reference index above 0
     */
    private static boolean refCondition(PictureInfo pi, int address, int quadrant) {
        var type = pi.type[address];
        return !MbType.isSkip(type) && !MbType.isIntra(type) && pi.refs[address * 4 + quadrant] > 0;
    }

    /**
     * Codes both components of {@code mvd_l0} of the partition whose top-left 4x4 block is given.
     *
     * @param coder the coder that encodes or estimates the bins
     * @param pi the picture tables
     * @param ctx the macroblock's position and neighbors
     * @param mb the macroblock
     * @param x4 the partition's left column in 4x4 units
     * @param y4 the partition's top row in 4x4 units
     */
    private static void writeMvd(CabacCoder coder, PictureInfo pi, MbContext ctx, Macroblock mb, int x4, int y4) {
        writeMvd(coder, pi, ctx, mb, x4, y4, 0);
    }

    /**
     * Codes both components of {@code mvd_l0} or {@code mvd_l1} of the partition whose top-left
     * 4x4 block is given; the context sums the neighboring differences of the same list.
     *
     * @param coder the coder that encodes or estimates the bins
     * @param pi the picture tables
     * @param ctx the macroblock's position and neighbors
     * @param mb the macroblock
     * @param x4 the partition's left column in 4x4 units
     * @param y4 the partition's top row in 4x4 units
     * @param list the reference list, {@code 0} or {@code 1}
     */
    private static void writeMvd(CabacCoder coder, PictureInfo pi, MbContext ctx, Macroblock mb, int x4, int y4,
                                 int list) {
        var mbMvdX = list == 0 ? mb.mvdX : mb.mvdX1;
        var mbMvdY = list == 0 ? mb.mvdY : mb.mvdY1;
        var piMvdX = list == 0 ? pi.mvdX : pi.mvdX1;
        var piMvdY = list == 0 ? pi.mvdY : pi.mvdY1;
        int ax;
        int ay;
        int bx;
        int by;
        if (x4 > 0) {
            var i = y4 * 4 + x4 - 1;
            ax = Math.abs(mbMvdX[i]);
            ay = Math.abs(mbMvdY[i]);
        } else if (ctx.availA) {
            var i = ctx.addrA * 16 + y4 * 4 + 3;
            ax = piMvdX[i];
            ay = piMvdY[i];
        } else {
            ax = 0;
            ay = 0;
        }
        if (y4 > 0) {
            var i = (y4 - 1) * 4 + x4;
            bx = Math.abs(mbMvdX[i]);
            by = Math.abs(mbMvdY[i]);
        } else if (ctx.availB) {
            var i = ctx.addrB * 16 + 12 + x4;
            bx = piMvdX[i];
            by = piMvdY[i];
        } else {
            bx = 0;
            by = 0;
        }
        var i = y4 * 4 + x4;
        writeMvdComponent(coder, 40, ax + bx, mbMvdX[i]);
        writeMvdComponent(coder, 47, ay + by, mbMvdY[i]);
    }

    /**
     * Codes one {@code mvd_l0} component with the UEG3 binarization.
     *
     * @param coder the coder that encodes or estimates the bins
     * @param offset the context offset, 40 for horizontal and 47 for vertical
     * @param sum the sum of the neighbors' absolute differences
     * @param mvd the motion vector difference
     */
    static void writeMvdComponent(CabacCoder coder, int offset, int sum, int mvd) {
        var inc = sum < 3 ? 0 : sum > 32 ? 2 : 1;
        if (mvd == 0) {
            coder.decision(offset + inc, 0);
            return;
        }
        var abs = Math.abs(mvd);
        coder.decision(offset + inc, 1);
        var prefix = Math.min(abs, 9);
        var ctxIdx = offset + 3;
        for (var i = 1; i < prefix; i++) {
            coder.decision(ctxIdx, 1);
            if (ctxIdx < offset + 6) {
                ctxIdx++;
            }
        }
        if (abs < 9) {
            coder.decision(ctxIdx, 0);
        } else {
            writeExpGolombBypass(coder, abs - 9, 3);
        }
        coder.bypass(mvd < 0 ? 1 : 0);
    }

    /**
     * Codes the k-th order Exp-Golomb suffix of a UEGk binarization with bypass bins.
     *
     * @param coder the coder that encodes or estimates the bins
     * @param value the suffix value
     * @param k the order
     */
    private static void writeExpGolombBypass(CabacCoder coder, int value, int k) {
        while (value >= (1 << k)) {
            coder.bypass(1);
            value -= 1 << k;
            k++;
        }
        coder.bypass(0);
        if (k > 0) {
            coder.bypassBits(value, k);
        }
    }

    /**
     * Codes {@code coded_block_pattern}.
     *
     * @param coder the coder that encodes or estimates the bins
     * @param pi the picture tables
     * @param ctx the macroblock's position and neighbors
     * @param cbp the coded block pattern
     */
    private static void writeCbp(CabacCoder coder, PictureInfo pi, MbContext ctx, int cbp) {
        for (var b8 = 0; b8 < 4; b8++) {
            int condA;
            int condB;
            if ((b8 & 1) != 0) {
                condA = ((cbp >> (b8 - 1)) & 1) != 0 ? 0 : 1;
            } else {
                condA = lumaCbpCondition(pi, ctx.availA, ctx.addrA, b8 + 1);
            }
            if ((b8 & 2) != 0) {
                condB = ((cbp >> (b8 - 2)) & 1) != 0 ? 0 : 1;
            } else {
                condB = lumaCbpCondition(pi, ctx.availB, ctx.addrB, b8 + 2);
            }
            coder.decision(73 + condA + 2 * condB, (cbp >> b8) & 1);
        }
        var chroma = cbp >> 4;
        var inc = chromaCbpCondition(pi, ctx.availA, ctx.addrA, 1) + 2 * chromaCbpCondition(pi, ctx.availB, ctx.addrB, 1);
        coder.decision(77 + inc, chroma != 0 ? 1 : 0);
        if (chroma != 0) {
            inc = chromaCbpCondition(pi, ctx.availA, ctx.addrA, 2) + 2 * chromaCbpCondition(pi, ctx.availB, ctx.addrB, 2);
            coder.decision(81 + inc, chroma == 2 ? 1 : 0);
        }
    }

    /**
     * Returns the luma {@code coded_block_pattern} context condition of a neighboring 8x8 block.
     *
     * @param pi the picture tables
     * @param available whether the neighboring macroblock is available
     * @param address the neighbor's address
     * @param b8 the neighbor's 8x8 block index
     * @return {@code 0} or {@code 1}
     */
    private static int lumaCbpCondition(PictureInfo pi, boolean available, int address, int b8) {
        if (!available) {
            return 0;
        }
        var type = pi.type[address];
        if (type == MbType.I_PCM) {
            return 0;
        }
        if (MbType.isSkip(type)) {
            return 1;
        }
        return ((pi.cbp[address] >> b8) & 1) != 0 ? 0 : 1;
    }

    /**
     * Returns a chroma {@code coded_block_pattern} context condition of a neighbor.
     *
     * @param pi the picture tables
     * @param available whether the neighboring macroblock is available
     * @param address the neighbor's address
     * @param threshold {@code 1} for the first bin, {@code 2} for the second
     * @return {@code 0} or {@code 1}
     */
    private static int chromaCbpCondition(PictureInfo pi, boolean available, int address, int threshold) {
        if (!available) {
            return 0;
        }
        var type = pi.type[address];
        if (type == MbType.I_PCM) {
            return 1;
        }
        if (MbType.isSkip(type)) {
            return 0;
        }
        var chroma = pi.cbp[address] >> 4;
        return threshold == 1 ? (chroma != 0 ? 1 : 0) : (chroma == 2 ? 1 : 0);
    }

    /**
     * Codes {@code mb_qp_delta}.
     *
     * @param coder the coder that encodes or estimates the bins
     * @param qpDelta the quantizer difference
     * @param prevNonZero whether the previous macroblock of the slice coded a non-zero
     * {@code mb_qp_delta}
     */
    static void writeQpDelta(CabacCoder coder, int qpDelta, boolean prevNonZero) {
        var mapped = qpDelta > 0 ? 2 * qpDelta - 1 : -2 * qpDelta;
        if (mapped == 0) {
            coder.decision(60 + (prevNonZero ? 1 : 0), 0);
            return;
        }
        coder.decision(60 + (prevNonZero ? 1 : 0), 1);
        var ctxIdx = 62;
        for (var i = 1; i < mapped; i++) {
            coder.decision(ctxIdx, 1);
            ctxIdx = 63;
        }
        coder.decision(ctxIdx, 0);
    }

    /**
     * Codes {@code residual()} of a macroblock.
     *
     * @param coder the coder that encodes or estimates the bins
     * @param pi the picture tables
     * @param ctx the macroblock's position and neighbors
     * @param mb the macroblock
     */
    private static void writeResidual(CabacCoder coder, PictureInfo pi, MbContext ctx, Macroblock mb) {
        var intra = MbType.isIntra(mb.type);
        if (mb.type == MbType.I16x16) {
            var inc = lumaDcCondition(pi, ctx.availA, ctx.addrA) + 2 * lumaDcCondition(pi, ctx.availB, ctx.addrB);
            writeBlock(coder, mb.lumaDc, 0, H264Tables.ZIGZAG_4X4, 0, 16, CAT_LUMA_DC, inc, mb.lumaDcCount);
        }
        if (mb.transform8x8) {
            for (var b8 = 0; b8 < 4; b8++) {
                if ((mb.cbp & (1 << b8)) != 0) {
                    writeBlock(coder, mb.luma, b8 * 64, H264Tables.ZIGZAG_8X8, 0, 64, CAT_LUMA_8x8, -1, mb.lumaCount[b8 * 4]);
                }
            }
        } else {
            var ac = mb.type == MbType.I16x16;
            for (var b8 = 0; b8 < 4; b8++) {
                if ((mb.cbp & (1 << b8)) == 0) {
                    continue;
                }
                for (var i = 0; i < 4; i++) {
                    var blk = b8 * 4 + i;
                    var inc = lumaBlockCondition(pi, ctx, mb, intra, H264Tables.BLOCK_X[blk], H264Tables.BLOCK_Y[blk]);
                    if (ac) {
                        writeBlock(coder, mb.luma, blk * 16, H264Tables.ZIGZAG_4X4, 1, 15, CAT_LUMA_AC, inc, mb.lumaCount[blk]);
                    } else {
                        writeBlock(coder, mb.luma, blk * 16, H264Tables.ZIGZAG_4X4, 0, 16, CAT_LUMA_4x4, inc, mb.lumaCount[blk]);
                    }
                }
            }
        }
        var chroma = mb.cbp >> 4;
        if (chroma != 0) {
            for (var c = 0; c < 2; c++) {
                var inc = chromaDcCondition(pi, ctx.availA, ctx.addrA, intra, c)
                        + 2 * chromaDcCondition(pi, ctx.availB, ctx.addrB, intra, c);
                writeBlock(coder, mb.chromaDc, c * 4, H264Tables.CHROMA_DC_SCAN, 0, 4, CAT_CHROMA_DC, inc, mb.chromaDcCount[c]);
            }
        }
        if (chroma == 2) {
            for (var c = 0; c < 2; c++) {
                for (var blk = 0; blk < 4; blk++) {
                    var inc = chromaAcCondition(pi, ctx, mb, intra, c, blk & 1, blk >> 1);
                    writeBlock(coder, mb.chroma, (c * 4 + blk) * 16, H264Tables.ZIGZAG_4X4, 1, 15, CAT_CHROMA_AC, inc,
                            mb.chromaCount[c * 4 + blk]);
                }
            }
        }
    }

    /**
     * Returns the {@code coded_block_flag} condition of a neighbor for the Intra_16x16 DC block.
     *
     * @param pi the picture tables
     * @param available whether the neighboring macroblock is available
     * @param address the neighbor's address
     * @return {@code 0} or {@code 1}
     */
    private static int lumaDcCondition(PictureInfo pi, boolean available, int address) {
        if (!available) {
            return 1;
        }
        var type = pi.type[address];
        if (type == MbType.I_PCM) {
            return 1;
        }
        return type == MbType.I16x16 ? pi.dcFlags[address] & 1 : 0;
    }

    /**
     * Returns the {@code coded_block_flag} context increment of a luma 4x4 block.
     *
     * @param pi the picture tables
     * @param ctx the macroblock's position and neighbors
     * @param mb the current macroblock
     * @param intra whether the current macroblock is intra
     * @param x4 the block column
     * @param y4 the block row
     * @return the increment, from 0 to 3
     */
    static int lumaBlockCondition(PictureInfo pi, MbContext ctx, Macroblock mb, boolean intra, int x4, int y4) {
        int condA;
        int condB;
        if (x4 > 0) {
            condA = mb.lumaCount[H264Tables.BLOCK_RASTER[y4 * 4 + x4 - 1]] != 0 ? 1 : 0;
        } else {
            condA = externalLumaCondition(pi, ctx.availA, ctx.addrA, intra, y4 * 4 + 3);
        }
        if (y4 > 0) {
            condB = mb.lumaCount[H264Tables.BLOCK_RASTER[(y4 - 1) * 4 + x4]] != 0 ? 1 : 0;
        } else {
            condB = externalLumaCondition(pi, ctx.availB, ctx.addrB, intra, 12 + x4);
        }
        return condA + 2 * condB;
    }

    /**
     * Returns the {@code coded_block_flag} condition of a luma block in a neighboring
     * macroblock.
     *
     * @param pi the picture tables
     * @param available whether the neighboring macroblock is available
     * @param address the neighbor's address
     * @param intra whether the current macroblock is intra
     * @param raster the neighboring block's raster index
     * @return {@code 0} or {@code 1}
     */
    private static int externalLumaCondition(PictureInfo pi, boolean available, int address, boolean intra, int raster) {
        if (!available) {
            return intra ? 1 : 0;
        }
        var type = pi.type[address];
        if (type == MbType.I_PCM) {
            return 1;
        }
        return pi.totalCoeff[address * 16 + raster] != 0 ? 1 : 0;
    }

    /**
     * Returns the {@code coded_block_flag} condition of a neighbor for a chroma DC block.
     *
     * @param pi the picture tables
     * @param available whether the neighboring macroblock is available
     * @param address the neighbor's address
     * @param intra whether the current macroblock is intra
     * @param component {@code 0} for Cb, {@code 1} for Cr
     * @return {@code 0} or {@code 1}
     */
    private static int chromaDcCondition(PictureInfo pi, boolean available, int address, boolean intra, int component) {
        if (!available) {
            return intra ? 1 : 0;
        }
        var type = pi.type[address];
        if (type == MbType.I_PCM) {
            return 1;
        }
        if (MbType.isSkip(type) || (pi.cbp[address] >> 4) == 0) {
            return 0;
        }
        return (pi.dcFlags[address] >> (component + 1)) & 1;
    }

    /**
     * Returns the {@code coded_block_flag} context increment of a chroma AC block.
     *
     * @param pi the picture tables
     * @param ctx the macroblock's position and neighbors
     * @param mb the current macroblock
     * @param intra whether the current macroblock is intra
     * @param component {@code 0} for Cb, {@code 1} for Cr
     * @param cx the block column, 0 or 1
     * @param cy the block row, 0 or 1
     * @return the increment, from 0 to 3
     */
    static int chromaAcCondition(PictureInfo pi, MbContext ctx, Macroblock mb, boolean intra, int component,
                                 int cx, int cy) {
        var base = component * 4;
        int condA;
        int condB;
        if (cx > 0) {
            condA = mb.chromaCount[base + cy * 2] != 0 ? 1 : 0;
        } else {
            condA = externalChromaAcCondition(pi, ctx.availA, ctx.addrA, intra, base + cy * 2 + 1);
        }
        if (cy > 0) {
            condB = mb.chromaCount[base + cx] != 0 ? 1 : 0;
        } else {
            condB = externalChromaAcCondition(pi, ctx.availB, ctx.addrB, intra, base + 2 + cx);
        }
        return condA + 2 * condB;
    }

    /**
     * Returns the {@code coded_block_flag} condition of a chroma AC block in a neighboring
     * macroblock.
     *
     * @param pi the picture tables
     * @param available whether the neighboring macroblock is available
     * @param address the neighbor's address
     * @param intra whether the current macroblock is intra
     * @param index the neighboring block's index in the macroblock's chroma table
     * @return {@code 0} or {@code 1}
     */
    private static int externalChromaAcCondition(PictureInfo pi, boolean available, int address, boolean intra,
                                                 int index) {
        if (!available) {
            return intra ? 1 : 0;
        }
        var type = pi.type[address];
        if (type == MbType.I_PCM) {
            return 1;
        }
        if (MbType.isSkip(type) || (pi.cbp[address] >> 4) != 2) {
            return 0;
        }
        return pi.chromaTotalCoeff[address * 8 + index] != 0 ? 1 : 0;
    }

    /**
     * Codes one {@code residual_block_cabac()}: {@code coded_block_flag} (except for 8x8 blocks),
     * the significance map and the levels.
     *
     * @param coder the coder that encodes or estimates the bins
     * @param levels the levels in raster order
     * @param offset the index of the block's first level
     * @param scan the scan table mapping scan positions to raster indices
     * @param first the first scan position coded, {@code 1} for AC blocks
     * @param count {@code maxNumCoeff}
     * @param cat the {@code ctxBlockCat}
     * @param cbfInc the {@code coded_block_flag} context increment, ignored for 8x8 blocks
     * @param nonZero the number of non-zero levels
     */
    static void writeBlock(CabacCoder coder, int[] levels, int offset, byte[] scan, int first, int count, int cat,
                           int cbfInc, int nonZero) {
        if (cat != CAT_LUMA_8x8) {
            coder.decision(CBF_OFFSET[cat] + cbfInc, nonZero != 0 ? 1 : 0);
            if (nonZero == 0) {
                return;
            }
        }
        var last = count - 1;
        while (levels[offset + scan[first + last]] == 0) {
            last--;
        }
        var sigBase = SIG_OFFSET[cat];
        var lastBase = LAST_OFFSET[cat];
        var coded = 0;
        for (var i = 0; i < count - 1 && coded < nonZero; i++) {
            var significant = levels[offset + scan[first + i]] != 0;
            int sigCtx;
            int lastCtx;
            if (cat == CAT_LUMA_8x8) {
                sigCtx = sigBase + SIG_8X8[i];
                lastCtx = lastBase + LAST_8X8[i];
            } else if (cat == CAT_CHROMA_DC) {
                sigCtx = sigBase + Math.min(i, 2);
                lastCtx = lastBase + Math.min(i, 2);
            } else {
                sigCtx = sigBase + i;
                lastCtx = lastBase + i;
            }
            coder.decision(sigCtx, significant ? 1 : 0);
            if (significant) {
                coded++;
                coder.decision(lastCtx, i == last ? 1 : 0);
                if (i == last) {
                    break;
                }
            }
        }
        var absBase = ABS_OFFSET[cat];
        var gt1Limit = cat == CAT_CHROMA_DC ? 3 : 4;
        var eq1 = 0;
        var gt1 = 0;
        for (var i = last; i >= 0; i--) {
            var level = levels[offset + scan[first + i]];
            if (level == 0) {
                continue;
            }
            var abs = Math.abs(level) - 1;
            var ctx0 = absBase + (gt1 != 0 ? 0 : Math.min(4, 1 + eq1));
            if (abs == 0) {
                coder.decision(ctx0, 0);
                eq1++;
            } else {
                coder.decision(ctx0, 1);
                var ctxN = absBase + 5 + Math.min(gt1Limit, gt1);
                var prefix = Math.min(abs, 14);
                for (var k = 1; k < prefix; k++) {
                    coder.decision(ctxN, 1);
                }
                if (abs < 14) {
                    coder.decision(ctxN, 0);
                } else {
                    writeExpGolombBypass(coder, abs - 14, 0);
                }
                gt1++;
            }
            coder.bypass(level < 0 ? 1 : 0);
        }
    }
}
