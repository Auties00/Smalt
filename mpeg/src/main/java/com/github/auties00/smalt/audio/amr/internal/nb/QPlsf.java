/* Ports opencore-amr 0.1.6 (q_plsf.cpp, q_plsf_3.cpp, q_plsf_5.cpp). Copyright PacketVideo; Apache License 2.0.
   Modified for Smalt. */
package com.github.auties00.smalt.audio.amr.internal.nb;

import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.MAX_32;
import static com.github.auties00.smalt.audio.amr.internal.nb.Constants.DICO1_5_SIZE;
import static com.github.auties00.smalt.audio.amr.internal.nb.Constants.DICO1_SIZE;
import static com.github.auties00.smalt.audio.amr.internal.nb.Constants.DICO2_5_SIZE;
import static com.github.auties00.smalt.audio.amr.internal.nb.Constants.DICO2_SIZE;
import static com.github.auties00.smalt.audio.amr.internal.nb.Constants.DICO3_5_SIZE;
import static com.github.auties00.smalt.audio.amr.internal.nb.Constants.DICO3_SIZE;
import static com.github.auties00.smalt.audio.amr.internal.nb.Constants.DICO4_5_SIZE;
import static com.github.auties00.smalt.audio.amr.internal.nb.Constants.DICO5_5_SIZE;
import static com.github.auties00.smalt.audio.amr.internal.nb.Constants.LSF_GAP;
import static com.github.auties00.smalt.audio.amr.internal.nb.Constants.LSP_PRED_FAC_MR122;
import static com.github.auties00.smalt.audio.amr.internal.nb.Constants.M;
import static com.github.auties00.smalt.audio.amr.internal.nb.Constants.MR515_3_SIZE;
import static com.github.auties00.smalt.audio.amr.internal.nb.Constants.MR795_1_SIZE;
import static com.github.auties00.smalt.audio.amr.internal.nb.Constants.PAST_RQ_INIT_SIZE;

import java.util.Arrays;

/**
 * Quantizes LSPs with the predictive split vector quantizers of AMR-NB: the 3-split quantizer
 * of every mode but {@code MR122} ({@link #qPlsf3}) and the split-matrix quantizer of
 * {@code MR122}, which codes two LSP vectors per frame ({@link #qPlsf5}).
 *
 * <p>An instance is the quantizer state ({@code Q_plsfState}): the prediction residual of the
 * previous frame, plus scratch buffers so quantization allocates nothing. It is not thread-safe.
 */
final class QPlsf {
    /**
     * Holds the quantized prediction residual of the previous frame ({@code past_rq}).
     */
    final short[] pastRq = new short[M];

    /**
     * Holds the LSFs of the first vector.
     */
    private final short[] lsf1 = new short[M];

    /**
     * Holds the LSFs of the second vector.
     */
    private final short[] lsf2 = new short[M];

    /**
     * Holds the weights of the first vector.
     */
    private final short[] wf1 = new short[M];

    /**
     * Holds the weights of the second vector.
     */
    private final short[] wf2 = new short[M];

    /**
     * Holds the predicted LSFs.
     */
    private final short[] lsfP = new short[M];

    /**
     * Holds the prediction residual of the first vector.
     */
    private final short[] lsfR1 = new short[M];

    /**
     * Holds the prediction residual of the second vector.
     */
    private final short[] lsfR2 = new short[M];

    /**
     * Holds the quantized LSFs of the first vector.
     */
    private final short[] lsf1Q = new short[M];

    /**
     * Holds the quantized LSFs of the second vector.
     */
    private final short[] lsf2Q = new short[M];

    /**
     * Holds a candidate residual during the comfort noise initialization search.
     */
    private final short[] tempR1 = new short[M];

    /**
     * Holds a candidate prediction during the comfort noise initialization search.
     */
    private final short[] tempP = new short[M];

    /**
     * Creates a quantizer in its reset state.
     */
    QPlsf() {
    }

    /**
     * Clears the prediction residual ({@code Q_plsf_reset}).
     */
    void reset() {
        Arrays.fill(pastRq, (short) 0);
    }

    /**
     * Searches a codebook of 4-value entries ({@code Vq_subvec4}) and replaces the residual by
     * the chosen entry.
     *
     * @param lsfR1 the residual, replaced by the quantized residual
     * @param lsfOffset the index of its first value
     * @param dico the codebook
     * @param wf1 the weights
     * @param wfOffset the index of the first weight
     * @param dicoSize the number of entries
     * @return the chosen index
     */
    private static short vqSubvec4(short[] lsfR1, int lsfOffset, short[] dico, short[] wf1, int wfOffset, int dicoSize) {
        var dico0 = 0;
        short index = 0;
        var distMin = MAX_32;
        var r0 = lsfR1[lsfOffset];
        var r1 = lsfR1[lsfOffset + 1];
        var r2 = lsfR1[lsfOffset + 2];
        var r3 = lsfR1[lsfOffset + 3];
        var w0 = wf1[wfOffset];
        var w1 = wf1[wfOffset + 1];
        var w2 = wf1[wfOffset + 2];
        var w3 = wf1[wfOffset + 3];
        for (var i = 0; i < dicoSize; i++) {
            var temp = (short) (r0 - dico[dico0++]);
            temp = (short) ((w0 * temp) >> 15);
            var dist = temp * temp;
            temp = (short) (r1 - dico[dico0++]);
            temp = (short) ((w1 * temp) >> 15);
            dist += temp * temp;
            temp = (short) (r2 - dico[dico0++]);
            temp = (short) ((w2 * temp) >> 15);
            dist += temp * temp;
            temp = (short) (r3 - dico[dico0++]);
            temp = (short) ((w3 * temp) >> 15);
            dist += temp * temp;
            if (dist < distMin) {
                distMin = dist;
                index = (short) i;
            }
        }
        var p = index << 2;
        lsfR1[lsfOffset] = dico[p];
        lsfR1[lsfOffset + 1] = dico[p + 1];
        lsfR1[lsfOffset + 2] = dico[p + 2];
        lsfR1[lsfOffset + 3] = dico[p + 3];
        return index;
    }

    /**
     * Searches a codebook of 3-value entries ({@code Vq_subvec3}) and replaces the residual by
     * the chosen entry.
     *
     * @param lsfR1 the residual, replaced by the quantized residual
     * @param lsfOffset the index of its first value
     * @param dico the codebook
     * @param wf1 the weights
     * @param wfOffset the index of the first weight
     * @param dicoSize the number of entries searched
     * @param useHalf whether only every other entry is searched
     * @return the chosen index, counted in searched entries
     */
    private static short vqSubvec3(short[] lsfR1, int lsfOffset, short[] dico, short[] wf1, int wfOffset,
                                   int dicoSize, boolean useHalf) {
        var dico0 = 0;
        var skip = useHalf ? 3 : 0;
        short index = 0;
        var distMin = MAX_32;
        var r0 = lsfR1[lsfOffset];
        var r1 = lsfR1[lsfOffset + 1];
        var r2 = lsfR1[lsfOffset + 2];
        var w0 = wf1[wfOffset];
        var w1 = wf1[wfOffset + 1];
        var w2 = wf1[wfOffset + 2];
        for (var i = 0; i < dicoSize; i++) {
            var temp = (short) (r0 - dico[dico0++]);
            temp = (short) ((w0 * temp) >> 15);
            var dist = temp * temp;
            temp = (short) (r1 - dico[dico0++]);
            temp = (short) ((w1 * temp) >> 15);
            dist += temp * temp;
            temp = (short) (r2 - dico[dico0++]);
            temp = (short) ((w2 * temp) >> 15);
            dist += temp * temp;
            if (dist < distMin) {
                distMin = dist;
                index = (short) i;
            }
            dico0 += skip;
        }
        var p = 3 * index;
        if (useHalf) {
            p += 3 * index;
        }
        lsfR1[lsfOffset] = dico[p];
        lsfR1[lsfOffset + 1] = dico[p + 1];
        lsfR1[lsfOffset + 2] = dico[p + 2];
        return index;
    }

    /**
     * Quantizes one LSP vector with the 3-split quantizer ({@code Q_plsf_3}).
     *
     * <p>In {@link Mode#MRDTX} the prediction residual is first reinitialized from the
     * {@link Lsf3Tables#PAST_RQ_INIT} vector closest to the LSFs, and the index of that vector is
     * returned.
     *
     * @param mode the codec mode, one of the {@link Mode} constants
     * @param lsp1 the 10 unquantized LSPs
     * @param lsp1Offset the index of the first LSP
     * @param lsp1Q receives the 10 quantized LSPs
     * @param lsp1QOffset the index of the first quantized LSP
     * @param indice receives the 3 codebook indices
     * @param indiceOffset the index of the first codebook index
     * @return in {@link Mode#MRDTX}, the index of the initial residual vector chosen; otherwise 0
     */
    short qPlsf3(int mode, short[] lsp1, int lsp1Offset, short[] lsp1Q, int lsp1QOffset, short[] indice, int indiceOffset) {
        short predInitI = 0;
        Lsf.lspLsf(lsp1, lsp1Offset, lsf1, 0, M);
        Lsf.lsfWt(lsf1, 0, wf1, 0);
        if (mode != Mode.MRDTX) {
            for (var i = 0; i < M; i++) {
                var temp = (short) ((pastRq[i] * Lsf3Tables.PRED_FAC_3[i]) >> 15);
                lsfP[i] = (short) (Lsf3Tables.MEAN_LSF_3[i] + temp);
                lsfR1[i] = (short) (lsf1[i] - lsfP[i]);
            }
        } else {
            var minPredInitErr = 0x7fffffff;
            for (var j = 0; j < PAST_RQ_INIT_SIZE; j++) {
                var predInitErr = 0;
                for (var i = 0; i < M; i++) {
                    tempP[i] = (short) (Lsf3Tables.MEAN_LSF_3[i] + Lsf3Tables.PAST_RQ_INIT[j * M + i]);
                    tempR1[i] = (short) (lsf1[i] - tempP[i]);
                    var temp = tempR1[i] * tempR1[i];
                    predInitErr = predInitErr + (temp << 1);
                }
                if (predInitErr < minPredInitErr) {
                    minPredInitErr = predInitErr;
                    System.arraycopy(tempR1, 0, lsfR1, 0, M);
                    System.arraycopy(tempP, 0, lsfP, 0, M);
                    System.arraycopy(Lsf3Tables.PAST_RQ_INIT, j * M, pastRq, 0, M);
                    predInitI = (short) j;
                }
            }
        }
        if (mode == Mode.MR475 || mode == Mode.MR515) {
            indice[indiceOffset] = vqSubvec3(lsfR1, 0, Lsf3Tables.DICO1_LSF_3, wf1, 0, DICO1_SIZE, false);
            indice[indiceOffset + 1] = vqSubvec3(lsfR1, 3, Lsf3Tables.DICO2_LSF_3, wf1, 3, DICO2_SIZE / 2, true);
            indice[indiceOffset + 2] = vqSubvec4(lsfR1, 6, Lsf3Tables.MR515_3_LSF, wf1, 6, MR515_3_SIZE);
        } else if (mode == Mode.MR795) {
            indice[indiceOffset] = vqSubvec3(lsfR1, 0, Lsf3ExtTables.MR795_1_LSF, wf1, 0, MR795_1_SIZE, false);
            indice[indiceOffset + 1] = vqSubvec3(lsfR1, 3, Lsf3Tables.DICO2_LSF_3, wf1, 3, DICO2_SIZE, false);
            indice[indiceOffset + 2] = vqSubvec4(lsfR1, 6, Lsf3ExtTables.DICO3_LSF_3, wf1, 6, DICO3_SIZE);
        } else {
            indice[indiceOffset] = vqSubvec3(lsfR1, 0, Lsf3Tables.DICO1_LSF_3, wf1, 0, DICO1_SIZE, false);
            indice[indiceOffset + 1] = vqSubvec3(lsfR1, 3, Lsf3Tables.DICO2_LSF_3, wf1, 3, DICO2_SIZE, false);
            indice[indiceOffset + 2] = vqSubvec4(lsfR1, 6, Lsf3ExtTables.DICO3_LSF_3, wf1, 6, DICO3_SIZE);
        }
        for (var i = 0; i < M; i++) {
            lsf1Q[i] = (short) (lsfR1[i] + lsfP[i]);
            pastRq[i] = lsfR1[i];
        }
        Lsf.reorderLsf(lsf1Q, 0, LSF_GAP, M);
        Lsf.lsfLsp(lsf1Q, 0, lsp1Q, lsp1QOffset, M);
        return predInitI;
    }

    /**
     * Searches a codebook of 2x2 entries jointly for the two vectors ({@code Vq_subvec}) and
     * replaces both residuals by the chosen entry.
     *
     * @param lsfR1 the residual of the first vector
     * @param lsfR2 the residual of the second vector
     * @param offset the index of the first value in both residuals and weight vectors
     * @param dico the codebook
     * @param dicoSize the number of entries
     * @return the chosen index
     */
    private short vqSubvec(short[] lsfR1, short[] lsfR2, int offset, short[] dico, int dicoSize) {
        short index = 0;
        var distMin = MAX_32;
        var w10 = wf1[offset];
        var w11 = wf1[offset + 1];
        var w20 = wf2[offset];
        var w21 = wf2[offset + 1];
        var aux1 = lsfR1[offset] * w10;
        var aux2 = lsfR1[offset + 1] * w11;
        var aux3 = lsfR2[offset] * w20;
        var aux4 = lsfR2[offset + 1] * w21;
        var p = 0;
        for (var i = 0; i < dicoSize; i++) {
            var temp = (short) ((aux1 - w10 * dico[p++]) >> 15);
            var dist = temp * temp;
            if (dist >= distMin) {
                p += 3;
                continue;
            }
            temp = (short) ((aux2 - w11 * dico[p++]) >> 15);
            dist += temp * temp;
            if (dist >= distMin) {
                p += 2;
                continue;
            }
            temp = (short) ((aux3 - w20 * dico[p++]) >> 15);
            dist += temp * temp;
            if (dist >= distMin) {
                p += 1;
                continue;
            }
            temp = (short) ((aux4 - w21 * dico[p++]) >> 15);
            dist += temp * temp;
            if (dist < distMin) {
                distMin = dist;
                index = (short) i;
            }
        }
        p = index << 2;
        lsfR1[offset] = dico[p];
        lsfR1[offset + 1] = dico[p + 1];
        lsfR2[offset] = dico[p + 2];
        lsfR2[offset + 1] = dico[p + 3];
        return index;
    }

    /**
     * Searches a signed codebook of 2x2 entries jointly for the two vectors
     * ({@code Vq_subvec_s}), trying every entry with both signs, and replaces both residuals by
     * the chosen signed entry.
     *
     * @param lsfR1 the residual of the first vector
     * @param lsfR2 the residual of the second vector
     * @param offset the index of the first value in both residuals and weight vectors
     * @param dico the codebook
     * @param dicoSize the number of entries
     * @return twice the chosen index, plus 1 for a negated entry
     */
    private short vqSubvecS(short[] lsfR1, short[] lsfR2, int offset, short[] dico, int dicoSize) {
        short index = 0;
        var sign = false;
        var distMin = MAX_32;
        var r10 = lsfR1[offset];
        var r11 = lsfR1[offset + 1];
        var r20 = lsfR2[offset];
        var r21 = lsfR2[offset + 1];
        var w10 = wf1[offset];
        var w11 = wf1[offset + 1];
        var w20 = wf2[offset];
        var w21 = wf2[offset + 1];
        var p = 0;
        for (var i = 0; i < dicoSize; i++) {
            var temp = dico[p++];
            var temp1 = (short) (r10 - temp);
            var temp2 = (short) (r10 + temp);
            temp1 = (short) ((w10 * temp1) >> 15);
            temp2 = (short) ((w10 * temp2) >> 15);
            var dist1 = temp1 * temp1;
            var dist2 = temp2 * temp2;
            temp = dico[p++];
            temp1 = (short) (r11 - temp);
            temp2 = (short) (r11 + temp);
            temp1 = (short) ((w11 * temp1) >> 15);
            temp2 = (short) ((w11 * temp2) >> 15);
            dist1 += temp1 * temp1;
            dist2 += temp2 * temp2;
            if (dist1 >= distMin && dist2 >= distMin) {
                p += 2;
                continue;
            }
            temp = dico[p++];
            temp1 = (short) (r20 - temp);
            temp2 = (short) (r20 + temp);
            temp1 = (short) ((w20 * temp1) >> 15);
            temp2 = (short) ((w20 * temp2) >> 15);
            dist1 += temp1 * temp1;
            dist2 += temp2 * temp2;
            temp = dico[p++];
            temp1 = (short) (r21 - temp);
            temp2 = (short) (r21 + temp);
            temp1 = (short) ((w21 * temp1) >> 15);
            temp2 = (short) ((w21 * temp2) >> 15);
            dist1 += temp1 * temp1;
            dist2 += temp2 * temp2;
            if (dist1 < distMin) {
                distMin = dist1;
                index = (short) i;
                sign = false;
            }
            if (dist2 < distMin) {
                distMin = dist2;
                index = (short) i;
                sign = true;
            }
        }
        p = index << 2;
        index = (short) (index << 1);
        if (sign) {
            lsfR1[offset] = (short) -dico[p];
            lsfR1[offset + 1] = (short) -dico[p + 1];
            lsfR2[offset] = (short) -dico[p + 2];
            lsfR2[offset + 1] = (short) -dico[p + 3];
            index += 1;
        } else {
            lsfR1[offset] = dico[p];
            lsfR1[offset + 1] = dico[p + 1];
            lsfR2[offset] = dico[p + 2];
            lsfR2[offset + 1] = dico[p + 3];
        }
        return index;
    }

    /**
     * Quantizes the two LSP vectors of an {@code MR122} frame with the split-matrix quantizer
     * ({@code Q_plsf_5}).
     *
     * @param lsp1 the 10 unquantized LSPs of subframe 2
     * @param lsp1Offset the index of the first LSP
     * @param lsp2 the 10 unquantized LSPs of subframe 4
     * @param lsp2Offset the index of the first LSP
     * @param lsp1Q receives the 10 quantized LSPs of subframe 2
     * @param lsp1QOffset the index of the first quantized LSP
     * @param lsp2Q receives the 10 quantized LSPs of subframe 4
     * @param lsp2QOffset the index of the first quantized LSP
     * @param indice receives the 5 codebook indices
     * @param indiceOffset the index of the first codebook index
     */
    void qPlsf5(short[] lsp1, int lsp1Offset, short[] lsp2, int lsp2Offset, short[] lsp1Q, int lsp1QOffset,
                short[] lsp2Q, int lsp2QOffset, short[] indice, int indiceOffset) {
        Lsf.lspLsf(lsp1, lsp1Offset, lsf1, 0, M);
        Lsf.lspLsf(lsp2, lsp2Offset, lsf2, 0, M);
        Lsf.lsfWt(lsf1, 0, wf1, 0);
        Lsf.lsfWt(lsf2, 0, wf2, 0);
        for (var i = 0; i < M; i++) {
            lsfP[i] = (short) (Lsf5Tables.MEAN_LSF_5[i] + ((pastRq[i] * LSP_PRED_FAC_MR122) >> 15));
            lsfR1[i] = (short) (lsf1[i] - lsfP[i]);
            lsfR2[i] = (short) (lsf2[i] - lsfP[i]);
        }
        indice[indiceOffset] = vqSubvec(lsfR1, lsfR2, 0, Lsf5Tables.DICO1_LSF_5, DICO1_5_SIZE);
        indice[indiceOffset + 1] = vqSubvec(lsfR1, lsfR2, 2, Lsf5Tables.DICO2_LSF_5, DICO2_5_SIZE);
        indice[indiceOffset + 2] = vqSubvecS(lsfR1, lsfR2, 4, Lsf5Tables.DICO3_LSF_5, DICO3_5_SIZE);
        indice[indiceOffset + 3] = vqSubvec(lsfR1, lsfR2, 6, Lsf5Tables.DICO4_LSF_5, DICO4_5_SIZE);
        indice[indiceOffset + 4] = vqSubvec(lsfR1, lsfR2, 8, Lsf5Tables.DICO5_LSF_5, DICO5_5_SIZE);
        for (var i = 0; i < M; i++) {
            lsf1Q[i] = (short) (lsfR1[i] + lsfP[i]);
            lsf2Q[i] = (short) (lsfR2[i] + lsfP[i]);
            pastRq[i] = lsfR2[i];
        }
        Lsf.reorderLsf(lsf1Q, 0, LSF_GAP, M);
        Lsf.reorderLsf(lsf2Q, 0, LSF_GAP, M);
        Lsf.lsfLsp(lsf1Q, 0, lsp1Q, lsp1QOffset, M);
        Lsf.lsfLsp(lsf2Q, 0, lsp2Q, lsp2QOffset, M);
    }
}
