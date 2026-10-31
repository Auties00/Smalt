/* Ports VVdeC 3.3.0 (source/Lib/DecoderLib/HLSyntaxReader.cpp). Copyright (c) 2018-2026, Fraunhofer-Gesellschaft
 * zur Foerderung der angewandten Forschung e.V. & The VVdeC Authors; Clear BSD License. Modified for Smalt. */
package com.github.auties00.smalt.video.vvc.internal;

import com.github.auties00.smalt.video.h264.internal.H264BitReader;

import java.util.Arrays;

/**
 * Holds the 28 scaling matrices of a scaling list APS (ITU-T H.266 7.3.2.20, 7.4.3.20).
 *
 * <p>Matrix {@code id} is 2x2 for ids 0 and 1, 4x4 for ids 2 to 7 and 8x8 for ids 8 to 27, stored in raster
 * order; ids 14 to 27 also have a DC value.
 */
public final class VvcScalingList {
    /**
     * Constructs an empty list, which {@link #parse} fills.
     */
    public VvcScalingList() {
    }

    /**
     * The coefficients of each matrix in raster order.
     */
    public final int[][] coeff = new int[28][];

    /**
     * The DC value of each matrix with an id of 14 or more.
     */
    public final int[] dc = new int[28];

    /**
     * {@return the side of matrix {@code id}}
     *
     * @param id the matrix identifier
     */
    static int matrixSize(int id) {
        return id < 2 ? 2 : id < 8 ? 4 : 8;
    }

    /**
     * Parses {@code scaling_list_data( )}.
     *
     * @param r the reader
     * @param chromaPresent {@code aps_chroma_present_flag}
     * @return the matrices
     */
    static VvcScalingList parse(H264BitReader r, boolean chromaPresent) {
        var list = new VvcScalingList();
        for (var id = 0; id < 28; id++) {
            var size = matrixSize(id);
            var luma = id % 3 == 2 || id == 27;
            var copy = true;
            var pred = false;
            var delta = 0;
            if (chromaPresent || luma) {
                copy = r.readFlag();
                if (!copy) {
                    pred = r.readFlag();
                }
                if ((copy || pred) && id != 0 && id != 2 && id != 8) {
                    var maxDelta = id < 2 ? id : id < 8 ? id - 2 : id - 8;
                    delta = r.readUe(maxDelta, "scaling_list_pred_id_delta");
                }
            }
            var refId = id - delta;
            var matrix = new int[size * size];
            int dcPred;
            if (!copy && !pred) {
                Arrays.fill(matrix, 8);
                dcPred = 8;
            } else if (delta == 0) {
                Arrays.fill(matrix, 16);
                dcPred = 16;
            } else {
                var ref = list.coeff[refId];
                System.arraycopy(ref, 0, matrix, 0, matrix.length);
                dcPred = refId > 13 ? list.dc[refId] : ref[0];
            }
            list.coeff[id] = matrix;
            if (copy) {
                if (id > 13) {
                    list.dc[id] = dcPred;
                }
                continue;
            }
            var next = 0;
            if (id > 13) {
                next = r.readSe(-128, 127, "scaling_list_dc_coef");
                list.dc[id] = (dcPred + next) & 255;
            }
            var log2 = Integer.numberOfTrailingZeros(size);
            var scan = VvcScan.diagonal(log2, log2);
            for (var i = 0; i < size * size; i++) {
                var pos = scan[i];
                var x = pos & (size - 1);
                var y = pos >> log2;
                if (!(id > 25 && x >= 4 && y >= 4)) {
                    next += r.readSe(-128, 127, "scaling_list_delta_coef");
                }
                matrix[pos] = (matrix[pos] + next) & 255;
            }
        }
        return list;
    }
}
