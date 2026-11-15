/* Ports VVdeC 3.3.0 (source/Lib/DecoderLib/HLSyntaxReader.cpp). Copyright (c) 2018-2026, Fraunhofer-Gesellschaft
 * zur Foerderung der angewandten Forschung e.V. & The VVdeC Authors; Clear BSD License. Modified for Smalt. */
package com.github.auties00.smalt.video.vvc.internal;

import com.github.auties00.smalt.video.h264.internal.H264BitReader;

/**
 * Holds an explicit weighted prediction table ({@code pred_weight_table( )}, ITU-T H.266 7.3.8).
 *
 * <p>Arrays are indexed by list, reference index and component (Y, Cb, Cr).
 */
public final class VvcPredWeights {
    /**
     * Constructs an empty table, which the slice or picture header parser fills.
     */
    public VvcPredWeights() {
    }

    /**
     * {@code luma_log2_weight_denom} and {@code ChromaLog2WeightDenom}, by component.
     */
    public final int[] log2Denom = new int[3];
    /**
     * Whether each reference of each list has explicit weights for each component ({@code luma_weight_l0_flag} and the
     * like).
     */
    public final boolean[][][] present = new boolean[2][16][3];
    /**
     * The weight of each reference of each list for each component ({@code LumaWeightL0} and the like).
     */
    public final int[][][] weight = new int[2][16][3];
    /**
     * The offset of each reference of each list for each component, scaled to the bit depth.
     */
    public final int[][][] offset = new int[2][16][3];
    /**
     * {@code NumWeightsL0} and {@code NumWeightsL1}.
     */
    public final int[] numWeights = new int[2];

    /**
     * Parses the table.
     *
     * @param r the reader
     * @param sps the active sequence parameter set
     * @param pps the active picture parameter set
     * @param lists the reference picture lists of the header
     * @param numRefIdxActive the active reference counts of a slice header, or {@code null} in a picture header
     * @return the table
     */
    static VvcPredWeights parse(H264BitReader r, VvcSps sps, VvcPps pps, VvcRefPicList[] lists,
                                int[] numRefIdxActive) {
        var w = new VvcPredWeights();
        var chroma = sps.chromaFormatIdc != 0;
        w.log2Denom[0] = r.readUe(7, "luma_log2_weight_denom");
        if (chroma) {
            var c = w.log2Denom[0] + r.readSe(-7, 7, "delta_chroma_log2_weight_denom");
            w.log2Denom[1] = VvcSps.check(c, 0, 7, "ChromaLog2WeightDenom");
            w.log2Denom[2] = w.log2Denom[1];
        }
        for (var l = 0; l < 2; l++) {
            var entries = lists[l].numEntries;
            var count = numRefIdxActive == null ? 0 : numRefIdxActive[l];
            if (l == 0) {
                if (pps.wpInfoInPh) {
                    count = r.readUe(Math.min(15, entries), "num_l0_weights");
                }
            } else {
                if (!pps.weightedBipred || (pps.wpInfoInPh && entries == 0)) {
                    count = 0;
                } else if (pps.wpInfoInPh) {
                    count = r.readUe(Math.min(15, entries), "num_l1_weights");
                }
            }
            w.numWeights[l] = count;
            for (var i = 0; i < count; i++) {
                w.present[l][i][0] = r.readFlag();
            }
            if (chroma) {
                for (var i = 0; i < count; i++) {
                    var f = r.readFlag();
                    w.present[l][i][1] = f;
                    w.present[l][i][2] = f;
                }
            }
            for (var i = 0; i < count; i++) {
                w.weight[l][i][0] = 1 << w.log2Denom[0];
                if (w.present[l][i][0]) {
                    w.weight[l][i][0] += r.readSe(-128, 127, "delta_luma_weight");
                    w.offset[l][i][0] = r.readSe(-128, 127, "luma_offset");
                }
                for (var c = 1; c < 3; c++) {
                    w.weight[l][i][c] = 1 << w.log2Denom[c];
                    if (w.present[l][i][c]) {
                        w.weight[l][i][c] += r.readSe(-128, 127, "delta_chroma_weight");
                        var delta = r.readSe(-512, 508, "delta_chroma_offset");
                        w.offset[l][i][c] = Math.clamp(128 + delta - ((128 * w.weight[l][i][c]) >> w.log2Denom[c]),
                                -128, 127);
                    }
                }
            }
        }
        return w;
    }
}
