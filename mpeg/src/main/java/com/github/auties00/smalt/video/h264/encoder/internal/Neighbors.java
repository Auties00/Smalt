package com.github.auties00.smalt.video.h264.encoder.internal;

import com.github.auties00.smalt.video.h264.internal.H264Tables;

/**
 * Derives the values that the syntax predicts from neighboring blocks: the predicted intra
 * prediction mode (clause 8.3.1.1) and the predicted number of non-zero coefficients {@code nC}
 * of CAVLC (clause 9.2.1).
 *
 * <p>Blocks inside the current macroblock are read from the candidate {@link Macroblock};
 * blocks in neighboring macroblocks from the {@link PictureInfo}.
 */
final class Neighbors {
    private Neighbors() {
        throw new AssertionError();
    }

    /**
     * Returns the predicted Intra_4x4 or Intra_8x8 mode of the block whose top-left 4x4 block
     * sits at the given position.
     *
     * @param pi the picture tables
     * @param ctx the current macroblock's position and neighbors
     * @param mb the current macroblock, whose earlier blocks' modes are set
     * @param x4 the column of the block's top-left 4x4 block
     * @param y4 the row of the block's top-left 4x4 block
     * @return the predicted mode, {@code predIntra4x4PredMode} or {@code predIntra8x8PredMode}
     */
    static int predictedIntraMode(PictureInfo pi, MbContext ctx, Macroblock mb, int x4, int y4) {
        int modeA;
        int modeB;
        if (x4 > 0) {
            modeA = currentMode(mb, x4 - 1, y4);
        } else if (ctx.availA) {
            modeA = pi.intraModes[ctx.addrA * 16 + y4 * 4 + 3];
        } else {
            return 2;
        }
        if (y4 > 0) {
            modeB = currentMode(mb, x4, y4 - 1);
        } else if (ctx.availB) {
            modeB = pi.intraModes[ctx.addrB * 16 + 12 + x4];
        } else {
            return 2;
        }
        return Math.min(modeA, modeB);
    }

    /**
     * Returns the intra mode of a 4x4 block of the current macroblock.
     *
     * @param mb the current macroblock
     * @param x4 the block column
     * @param y4 the block row
     * @return the mode covering the block
     */
    private static int currentMode(Macroblock mb, int x4, int y4) {
        if (mb.type == MbType.I8x8) {
            return mb.intraModes[(y4 >> 1) * 2 + (x4 >> 1)];
        }
        return mb.intraModes[H264Tables.BLOCK_RASTER[y4 * 4 + x4]];
    }

    /**
     * Returns {@code nC} of a luma 4x4 block (clause 9.2.1).
     *
     * @param pi the picture tables
     * @param ctx the current macroblock's position and neighbors
     * @param mb the current macroblock, whose earlier blocks' counts are set
     * @param x4 the block column
     * @param y4 the block row
     * @return the predicted number of non-zero coefficients
     */
    static int lumaNc(PictureInfo pi, MbContext ctx, Macroblock mb, int x4, int y4) {
        int nA;
        int nB;
        boolean hasA;
        boolean hasB;
        if (x4 > 0) {
            nA = mb.lumaCount[H264Tables.BLOCK_RASTER[y4 * 4 + x4 - 1]];
            hasA = true;
        } else {
            hasA = ctx.availA;
            nA = hasA ? pi.totalCoeff[ctx.addrA * 16 + y4 * 4 + 3] : 0;
        }
        if (y4 > 0) {
            nB = mb.lumaCount[H264Tables.BLOCK_RASTER[(y4 - 1) * 4 + x4]];
            hasB = true;
        } else {
            hasB = ctx.availB;
            nB = hasB ? pi.totalCoeff[ctx.addrB * 16 + 12 + x4] : 0;
        }
        return combine(nA, hasA, nB, hasB);
    }

    /**
     * Returns {@code nC} of a chroma AC block (clause 9.2.1).
     *
     * @param pi the picture tables
     * @param ctx the current macroblock's position and neighbors
     * @param mb the current macroblock, whose earlier blocks' counts are set
     * @param component {@code 0} for Cb, {@code 1} for Cr
     * @param cx the block column, 0 or 1
     * @param cy the block row, 0 or 1
     * @return the predicted number of non-zero coefficients
     */
    static int chromaNc(PictureInfo pi, MbContext ctx, Macroblock mb, int component, int cx, int cy) {
        var base = component * 4;
        int nA;
        int nB;
        boolean hasA;
        boolean hasB;
        if (cx > 0) {
            nA = mb.chromaCount[base + cy * 2];
            hasA = true;
        } else {
            hasA = ctx.availA;
            nA = hasA ? pi.chromaTotalCoeff[ctx.addrA * 8 + base + cy * 2 + 1] : 0;
        }
        if (cy > 0) {
            nB = mb.chromaCount[base + cx];
            hasB = true;
        } else {
            hasB = ctx.availB;
            nB = hasB ? pi.chromaTotalCoeff[ctx.addrB * 8 + base + 2 + cx] : 0;
        }
        return combine(nA, hasA, nB, hasB);
    }

    /**
     * Combines the two neighbor counts into {@code nC}.
     *
     * @param nA the left count
     * @param hasA whether the left block is available
     * @param nB the upper count
     * @param hasB whether the upper block is available
     * @return {@code nC}
     */
    private static int combine(int nA, boolean hasA, int nB, boolean hasB) {
        if (hasA && hasB) {
            return (nA + nB + 1) >> 1;
        }
        if (hasA) {
            return nA;
        }
        return hasB ? nB : 0;
    }
}
