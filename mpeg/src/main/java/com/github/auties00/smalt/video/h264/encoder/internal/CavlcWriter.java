package com.github.auties00.smalt.video.h264.encoder.internal;

import com.github.auties00.smalt.internal.bits.MsbBitWriter;
import com.github.auties00.smalt.video.h264.internal.H264Tables;

/**
 * Writes the macroblock layer of a slice with CAVLC entropy coding (clauses 7.3.5 and 9.2).
 *
 * <p>Skipped macroblocks of P slices accumulate into {@code mb_skip_run}, written before the next
 * coded macroblock or at the end of the slice. The writer is also used to count the exact bits of
 * a candidate during rate-distortion decisions.
 */
final class CavlcWriter {
    /**
     * The largest level magnitude CAVLC can represent in the Baseline and Main profiles, where
     * {@code level_prefix} may not exceed 15.
     */
    static final int MAX_LEVEL = 2063;

    /**
     * The levels of the block being written, in reverse scan order of the non-zero entries.
     */
    private final int[] nonZero = new int[64];

    /**
     * The zero-run preceding each non-zero level of {@link #nonZero}.
     */
    private final int[] runs = new int[64];

    /**
     * Constructs a writer.
     */
    CavlcWriter() {
    }

    /**
     * Writes one coded macroblock: {@code macroblock_layer()}.
     *
     * @param out the slice data writer
     * @param pi the picture tables
     * @param ctx the macroblock's position and neighbors
     * @param mb the macroblock
     * @param pSlice whether the slice is a P slice
     * @param refCount the number of active reference indices
     * @param qpDelta the {@code mb_qp_delta} to write when the syntax carries one
     * @param transform8x8Mode whether the picture parameter set enables the 8x8 transform
     */
    void writeMacroblock(MsbBitWriter out, PictureInfo pi, MbContext ctx, Macroblock mb, boolean pSlice, int refCount,
                         int qpDelta, boolean transform8x8Mode) {
        var type = mb.type;
        var intraOffset = pSlice ? 5 : 0;
        switch (type) {
            case MbType.I_PCM -> {
                out.writeUe(intraOffset + 25);
                return;
            }
            case MbType.I4x4, MbType.I8x8 -> {
                out.writeUe(intraOffset);
                if (transform8x8Mode) {
                    out.writeBit(type == MbType.I8x8 ? 1 : 0);
                }
                writeIntraModes(out, pi, ctx, mb);
                out.writeUe(mb.chromaMode);
            }
            case MbType.I16x16 -> {
                var chroma = mb.cbp >> 4;
                out.writeUe(intraOffset + 1 + mb.intra16x16Mode + 4 * chroma + ((mb.cbp & 15) != 0 ? 12 : 0));
                out.writeUe(mb.chromaMode);
            }
            case MbType.P16x16 -> {
                out.writeUe(0);
                writeRef(out, mb.refs[0], refCount);
                out.writeSe(mb.mvdX[0]);
                out.writeSe(mb.mvdY[0]);
            }
            case MbType.P16x8 -> {
                out.writeUe(1);
                writeRef(out, mb.refs[0], refCount);
                writeRef(out, mb.refs[2], refCount);
                out.writeSe(mb.mvdX[0]);
                out.writeSe(mb.mvdY[0]);
                out.writeSe(mb.mvdX[8]);
                out.writeSe(mb.mvdY[8]);
            }
            case MbType.P8x16 -> {
                out.writeUe(2);
                writeRef(out, mb.refs[0], refCount);
                writeRef(out, mb.refs[1], refCount);
                out.writeSe(mb.mvdX[0]);
                out.writeSe(mb.mvdY[0]);
                out.writeSe(mb.mvdX[2]);
                out.writeSe(mb.mvdY[2]);
            }
            default -> {
                out.writeUe(3);
                for (var i = 0; i < 4; i++) {
                    out.writeUe(mb.subTypes[i]);
                }
                for (var i = 0; i < 4; i++) {
                    writeRef(out, mb.refs[i], refCount);
                }
                for (var i = 0; i < 4; i++) {
                    writeSubMvds(out, mb, i);
                }
            }
        }
        if (type != MbType.I16x16) {
            var code = MbType.isIntra(type) ? Tables.CBP_INTRA_TO_CODE[mb.cbp] : Tables.CBP_INTER_TO_CODE[mb.cbp];
            out.writeUe(code);
            if ((mb.cbp & 15) != 0 && transform8x8Mode && !MbType.isIntra(type) && allSub8x8(mb)) {
                out.writeBit(mb.transform8x8 ? 1 : 0);
            }
        }
        if (mb.cbp != 0 || type == MbType.I16x16) {
            out.writeSe(qpDelta);
            writeResidual(out, pi, ctx, mb);
        }
    }

    /**
     * Returns whether a macroblock has no partition smaller than 8x8, the condition for
     * signaling {@code transform_size_8x8_flag} in inter macroblocks.
     *
     * @param mb the macroblock
     * @return whether the 8x8 transform may be chosen
     */
    static boolean allSub8x8(Macroblock mb) {
        if (mb.type != MbType.P8x8) {
            return true;
        }
        for (var i = 0; i < 4; i++) {
            if (mb.subTypes[i] != MbType.SUB_8x8) {
                return false;
            }
        }
        return true;
    }

    /**
     * Writes the sample bytes of an {@code I_PCM} macroblock after its {@code mb_type}.
     *
     * @param out the slice data writer
     * @param samples the 384 samples: 256 luma then 64 Cb then 64 Cr
     */
    void writePcmSamples(MsbBitWriter out, byte[] samples) {
        out.alignWithZeros();
        out.writeBytes(samples, 0, 384);
    }

    /**
     * Writes a reference index, {@code te(v)} with the active range: nothing for one reference, one
     * inverted bit for two, {@code ue(v)} beyond.
     *
     * @param out the slice data writer
     * @param ref the reference index
     * @param refCount the number of active reference indices
     */
    static void writeRef(MsbBitWriter out, int ref, int refCount) {
        if (refCount == 2) {
            out.writeBit(ref ^ 1);
        } else if (refCount > 2) {
            out.writeUe(ref);
        }
    }

    /**
     * Writes the motion vector differences of one sub-macroblock.
     *
     * @param out the slice data writer
     * @param mb the macroblock
     * @param sub the sub-macroblock index
     */
    private static void writeSubMvds(MsbBitWriter out, Macroblock mb, int sub) {
        var base = (sub >> 1) * 8 + (sub & 1) * 2;
        switch (mb.subTypes[sub]) {
            case MbType.SUB_8x8 -> {
                out.writeSe(mb.mvdX[base]);
                out.writeSe(mb.mvdY[base]);
            }
            case MbType.SUB_8x4 -> {
                out.writeSe(mb.mvdX[base]);
                out.writeSe(mb.mvdY[base]);
                out.writeSe(mb.mvdX[base + 4]);
                out.writeSe(mb.mvdY[base + 4]);
            }
            case MbType.SUB_4x8 -> {
                out.writeSe(mb.mvdX[base]);
                out.writeSe(mb.mvdY[base]);
                out.writeSe(mb.mvdX[base + 1]);
                out.writeSe(mb.mvdY[base + 1]);
            }
            default -> {
                out.writeSe(mb.mvdX[base]);
                out.writeSe(mb.mvdY[base]);
                out.writeSe(mb.mvdX[base + 1]);
                out.writeSe(mb.mvdY[base + 1]);
                out.writeSe(mb.mvdX[base + 4]);
                out.writeSe(mb.mvdY[base + 4]);
                out.writeSe(mb.mvdX[base + 5]);
                out.writeSe(mb.mvdY[base + 5]);
            }
        }
    }

    /**
     * Writes the intra prediction modes of an {@code I_NxN} macroblock.
     *
     * @param out the slice data writer
     * @param pi the picture tables
     * @param ctx the macroblock's position and neighbors
     * @param mb the macroblock
     */
    private static void writeIntraModes(MsbBitWriter out, PictureInfo pi, MbContext ctx, Macroblock mb) {
        if (mb.type == MbType.I8x8) {
            for (var b8 = 0; b8 < 4; b8++) {
                var predicted = Neighbors.predictedIntraMode(pi, ctx, mb, (b8 & 1) * 2, (b8 >> 1) * 2);
                writeMode(out, mb.intraModes[b8], predicted);
            }
        } else {
            for (var blk = 0; blk < 16; blk++) {
                var predicted = Neighbors.predictedIntraMode(pi, ctx, mb, H264Tables.BLOCK_X[blk], H264Tables.BLOCK_Y[blk]);
                writeMode(out, mb.intraModes[blk], predicted);
            }
        }
    }

    /**
     * Writes {@code prev_intra_pred_mode_flag} and {@code rem_intra_pred_mode}.
     *
     * @param out the slice data writer
     * @param mode the actual mode
     * @param predicted the predicted mode
     */
    private static void writeMode(MsbBitWriter out, int mode, int predicted) {
        if (mode == predicted) {
            out.writeBit(1);
        } else {
            out.write(mode < predicted ? mode : mode - 1, 4);
        }
    }

    /**
     * Writes {@code residual()} of a macroblock.
     *
     * @param out the slice data writer
     * @param pi the picture tables
     * @param ctx the macroblock's position and neighbors
     * @param mb the macroblock
     */
    private void writeResidual(MsbBitWriter out, PictureInfo pi, MbContext ctx, Macroblock mb) {
        if (mb.type == MbType.I16x16) {
            writeBlock(out, mb.lumaDc, 0, H264Tables.ZIGZAG_4X4, 0, 16, Neighbors.lumaNc(pi, ctx, mb, 0, 0));
        }
        for (var b8 = 0; b8 < 4; b8++) {
            if ((mb.cbp & (1 << b8)) == 0) {
                continue;
            }
            for (var i = 0; i < 4; i++) {
                var blk = b8 * 4 + i;
                var nC = Neighbors.lumaNc(pi, ctx, mb, H264Tables.BLOCK_X[blk], H264Tables.BLOCK_Y[blk]);
                if (mb.type == MbType.I16x16) {
                    writeBlock(out, mb.luma, blk * 16, H264Tables.ZIGZAG_4X4, 1, 15, nC);
                } else {
                    writeBlock(out, mb.luma, blk * 16, H264Tables.ZIGZAG_4X4, 0, 16, nC);
                }
            }
        }
        var chroma = mb.cbp >> 4;
        if (chroma != 0) {
            writeBlock(out, mb.chromaDc, 0, H264Tables.CHROMA_DC_SCAN, 0, 4, -1);
            writeBlock(out, mb.chromaDc, 4, H264Tables.CHROMA_DC_SCAN, 0, 4, -1);
        }
        if (chroma == 2) {
            for (var c = 0; c < 2; c++) {
                for (var blk = 0; blk < 4; blk++) {
                    var nC = Neighbors.chromaNc(pi, ctx, mb, c, blk & 1, blk >> 1);
                    writeBlock(out, mb.chroma, (c * 4 + blk) * 16, H264Tables.ZIGZAG_4X4, 1, 15, nC);
                }
            }
        }
    }

    /**
     * Writes one {@code residual_block_cavlc()} (clause 7.3.5.3.2).
     *
     * @param out the slice data writer
     * @param levels the levels in raster order
     * @param offset the index of the block's first level
     * @param scan the scan table mapping scan positions to raster indices
     * @param first the first scan position coded, {@code 1} for AC blocks
     * @param count {@code maxNumCoeff}, the number of levels coded
     * @param nC the predicted number of non-zero coefficients, {@code -1} for chroma DC
     * @return the number of non-zero levels, {@code TotalCoeff}
     */
    int writeBlock(MsbBitWriter out, int[] levels, int offset, byte[] scan, int first, int count, int nC) {
        var total = 0;
        var run = 0;
        var totalZeros = 0;
        var seenNonZero = false;
        for (var i = count - 1; i >= 0; i--) {
            var level = levels[offset + scan[first + i]];
            if (level != 0) {
                if (total > 0) {
                    runs[total - 1] = run;
                }
                nonZero[total++] = level;
                run = 0;
                seenNonZero = true;
            } else if (seenNonZero) {
                run++;
                totalZeros++;
            }
        }
        if (total > 0) {
            runs[total - 1] = run;
        }
        var trailingOnes = 0;
        while (trailingOnes < total && trailingOnes < 3 && Math.abs(nonZero[trailingOnes]) == 1) {
            trailingOnes++;
        }
        var token = CavlcTables.COEFF_TOKEN[(CavlcTables.coeffTokenTable(nC) * 17 + total) * 4 + trailingOnes];
        out.write(token & 0xFFFF, token >>> 16);
        if (total == 0) {
            return 0;
        }
        var suffixLength = total > 10 && trailingOnes < 3 ? 1 : 0;
        for (var i = 0; i < total; i++) {
            var level = nonZero[i];
            if (i < trailingOnes) {
                out.writeBit(level < 0 ? 1 : 0);
                continue;
            }
            var levelCode = level > 0 ? 2 * level - 2 : -2 * level - 1;
            if (i == trailingOnes && trailingOnes < 3) {
                levelCode -= 2;
            }
            writeLevel(out, levelCode, suffixLength);
            if (suffixLength == 0) {
                suffixLength = 1;
            }
            if (Math.abs(level) > (3 << (suffixLength - 1)) && suffixLength < 6) {
                suffixLength++;
            }
        }
        if (total < count) {
            int code;
            if (count == 4) {
                code = CavlcTables.TOTAL_ZEROS_CHROMA_DC[total * 4 + totalZeros];
            } else {
                code = CavlcTables.TOTAL_ZEROS[total * 16 + totalZeros];
            }
            out.write(code & 0xFFFF, code >>> 16);
        }
        var zerosLeft = totalZeros;
        for (var i = 0; i < total - 1 && zerosLeft > 0; i++) {
            var runBefore = runs[i];
            var code = CavlcTables.RUN_BEFORE[Math.min(zerosLeft, 7) * 15 + runBefore];
            out.write(code & 0xFFFF, code >>> 16);
            zerosLeft -= runBefore;
        }
        return total;
    }

    /**
     * Writes {@code level_prefix} and {@code level_suffix} for one level code.
     *
     * @param out the slice data writer
     * @param levelCode the level code
     * @param suffixLength the current suffix length
     */
    private static void writeLevel(MsbBitWriter out, int levelCode, int suffixLength) {
        if (suffixLength == 0) {
            if (levelCode < 14) {
                out.write(1, levelCode + 1);
            } else if (levelCode < 30) {
                out.write(1, 15);
                out.write(levelCode - 14, 4);
            } else {
                out.write(1, 16);
                out.write(levelCode - 30, 12);
            }
        } else {
            var prefix = levelCode >> suffixLength;
            if (prefix < 15) {
                out.write(1, prefix + 1);
                out.write(levelCode & ((1 << suffixLength) - 1), suffixLength);
            } else {
                out.write(1, 16);
                out.write(levelCode - (15 << suffixLength), 12);
            }
        }
    }
}
