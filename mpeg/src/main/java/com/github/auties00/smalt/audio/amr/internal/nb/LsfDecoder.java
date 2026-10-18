/* Ports opencore-amr 0.1.6 (d_plsf.cpp, d_plsf_3.cpp, d_plsf_5.cpp, int_lsf.cpp). Copyright PacketVideo; Apache
   License 2.0. Modified for Smalt. */
package com.github.auties00.smalt.audio.amr.internal.nb;

import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.add;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.mult;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.negate;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.shl;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.sub;
import static com.github.auties00.smalt.audio.amr.internal.nb.Constants.DICO1_SIZE;
import static com.github.auties00.smalt.audio.amr.internal.nb.Constants.DICO2_SIZE;
import static com.github.auties00.smalt.audio.amr.internal.nb.Constants.DICO3_SIZE;
import static com.github.auties00.smalt.audio.amr.internal.nb.Constants.LSF_GAP;
import static com.github.auties00.smalt.audio.amr.internal.nb.Constants.LSP_PRED_FAC_MR122;
import static com.github.auties00.smalt.audio.amr.internal.nb.Constants.M;
import static com.github.auties00.smalt.audio.amr.internal.nb.Constants.MR515_3_SIZE;
import static com.github.auties00.smalt.audio.amr.internal.nb.Constants.MR795_1_SIZE;

/**
 * Decodes the quantized line spectral frequencies of AMR-NB frames: the split vector
 * quantizers with moving average prediction of the 3-split modes and of {@code MR122}, and their
 * extrapolation for lost frames ({@code D_plsfState}).
 *
 * <p>Indices that would address beyond a codebook are limited to its last vector, as the
 * reference does.
 */
final class LsfDecoder {
    /**
     * The weight of the past LSFs in the extrapolation of a lost 3-split frame, Q15.
     */
    private static final short ALPHA_3 = 29491;

    /**
     * The weight of the mean LSFs in the extrapolation of a lost 3-split frame, Q15.
     */
    private static final short ONE_ALPHA_3 = 3277;

    /**
     * The weight of the past LSFs in the extrapolation of a lost {@code MR122} frame, Q15.
     */
    private static final int ALPHA_5 = 31128;

    /**
     * The weight of the mean LSFs in the extrapolation of a lost {@code MR122} frame, Q15.
     */
    private static final int ONE_ALPHA_5 = 1639;

    /**
     * The past quantized prediction residual.
     */
    final short[] pastRQ = new short[M];

    /**
     * The past dequantized LSFs.
     */
    final short[] pastLsfQ = new short[M];

    /**
     * The first residual subvector.
     */
    private final short[] lsf1R = new short[M];

    /**
     * The second residual subvector ({@code MR122}).
     */
    private final short[] lsf2R = new short[M];

    /**
     * The first dequantized LSF vector.
     */
    private final short[] lsf1Q = new short[M];

    /**
     * The second dequantized LSF vector ({@code MR122}).
     */
    private final short[] lsf2Q = new short[M];

    /**
     * Creates the state, reset.
     */
    LsfDecoder() {
        reset();
    }

    /**
     * Resets the state: no residual, mean LSFs ({@code D_plsf_reset}).
     */
    void reset() {
        for (var i = 0; i < M; i++) {
            pastRQ[i] = 0;
        }
        System.arraycopy(Lsf5Tables.MEAN_LSF_5, 0, pastLsfQ, 0, M);
    }

    /**
     * Sets the past residual from the comfort noise initialization index ({@code Init_D_plsf_3}).
     *
     * @param index the index, 0 to 7
     */
    void init3(short index) {
        System.arraycopy(Lsf3Tables.PAST_RQ_INIT, index * M, pastRQ, 0, M);
    }

    /**
     * Decodes the 3-split quantized LSFs of every mode but {@code MR122}, or extrapolates them
     * for a lost frame, and converts them to LSPs ({@code D_plsf_3}).
     *
     * @param mode the mode, {@link Mode#MRDTX} for comfort noise
     * @param bfi nonzero for a lost frame
     * @param indice the 3 indices
     * @param indiceOffset the index of the first index
     * @param lsp1Q receives the 10 LSPs
     * @param lsp1QOffset the index of the first LSP
     * @param overflow the flag raised on saturation
     */
    void decode3(int mode, int bfi, short[] indice, int indiceOffset, short[] lsp1Q, int lsp1QOffset, Flag overflow) {
        var mean = Lsf3Tables.MEAN_LSF_3;
        var predFac = Lsf3Tables.PRED_FAC_3;
        if (bfi != 0) {
            for (var i = 0; i < M; i++) {
                var temp = mult(pastLsfQ[i], ALPHA_3, overflow);
                var index = mult(mean[i], ONE_ALPHA_3, overflow);
                lsf1Q[i] = add(index, temp, overflow);
            }
            if (mode != Mode.MRDTX) {
                for (var i = 0; i < M; i++) {
                    var temp = mult(pastRQ[i], predFac[i], overflow);
                    temp = add(mean[i], temp, overflow);
                    pastRQ[i] = sub(lsf1Q[i], temp, overflow);
                }
            } else {
                for (var i = 0; i < M; i++) {
                    var temp = add(mean[i], pastRQ[i], overflow);
                    pastRQ[i] = sub(lsf1Q[i], temp, overflow);
                }
            }
        } else {
            short[] cb1;
            short[] cb3;
            int limit1;
            int limit3;
            var cb2 = Lsf3Tables.DICO2_LSF_3;
            var limit2 = (DICO2_SIZE - 1) * 3;
            if (mode == Mode.MR475 || mode == Mode.MR515) {
                cb1 = Lsf3Tables.DICO1_LSF_3;
                cb3 = Lsf3Tables.MR515_3_LSF;
                limit1 = (DICO1_SIZE - 1) * 3;
                limit3 = (MR515_3_SIZE - 1) * 4;
            } else if (mode == Mode.MR795) {
                cb1 = Lsf3ExtTables.MR795_1_LSF;
                cb3 = Lsf3ExtTables.DICO3_LSF_3;
                limit1 = (MR795_1_SIZE - 1) * 3;
                limit3 = (DICO3_SIZE - 1) * 4;
            } else {
                cb1 = Lsf3Tables.DICO1_LSF_3;
                cb3 = Lsf3ExtTables.DICO3_LSF_3;
                limit1 = (DICO1_SIZE - 1) * 3;
                limit3 = (DICO3_SIZE - 1) * 4;
            }
            var index = indice[indiceOffset];
            var temp = (short) (index + (index << 1));
            if (temp > limit1) {
                temp = (short) limit1;
            }
            lsf1R[0] = cb1[temp];
            lsf1R[1] = cb1[temp + 1];
            lsf1R[2] = cb1[temp + 2];
            index = indice[indiceOffset + 1];
            if (mode == Mode.MR475 || mode == Mode.MR515) {
                index = (short) (index << 1);
            }
            temp = (short) (index + (index << 1));
            if (temp > limit2) {
                temp = (short) limit2;
            }
            lsf1R[3] = cb2[temp];
            lsf1R[4] = cb2[temp + 1];
            lsf1R[5] = cb2[temp + 2];
            index = indice[indiceOffset + 2];
            temp = (short) (index << 2);
            if (temp > limit3) {
                temp = (short) limit3;
            }
            lsf1R[6] = cb3[temp];
            lsf1R[7] = cb3[temp + 1];
            lsf1R[8] = cb3[temp + 2];
            lsf1R[9] = cb3[temp + 3];
            if (mode != Mode.MRDTX) {
                for (var i = 0; i < M; i++) {
                    var value = mult(pastRQ[i], predFac[i], overflow);
                    value = add(mean[i], value, overflow);
                    lsf1Q[i] = add(lsf1R[i], value, overflow);
                    pastRQ[i] = lsf1R[i];
                }
            } else {
                for (var i = 0; i < M; i++) {
                    var value = add(mean[i], pastRQ[i], overflow);
                    lsf1Q[i] = add(lsf1R[i], value, overflow);
                    pastRQ[i] = lsf1R[i];
                }
            }
        }
        Lsf.reorderLsf(lsf1Q, 0, LSF_GAP, M);
        System.arraycopy(lsf1Q, 0, pastLsfQ, 0, M);
        Lsf.lsfLsp(lsf1Q, 0, lsp1Q, lsp1QOffset, M);
    }

    /**
     * Decodes the two split-quantized LSF vectors of {@code MR122}, or extrapolates them for a
     * lost frame, and converts them to LSPs ({@code D_plsf_5}).
     *
     * @param bfi nonzero for a lost frame
     * @param indice the 5 indices
     * @param indiceOffset the index of the first index
     * @param lsp1Q receives the 10 LSPs of the second subframe
     * @param lsp1QOffset the index of the first of them
     * @param lsp2Q receives the 10 LSPs of the fourth subframe
     * @param lsp2QOffset the index of the first of them
     * @param overflow the flag raised on saturation
     */
    void decode5(int bfi, short[] indice, int indiceOffset, short[] lsp1Q, int lsp1QOffset, short[] lsp2Q, int lsp2QOffset,
                 Flag overflow) {
        var mean = Lsf5Tables.MEAN_LSF_5;
        if (bfi != 0) {
            for (var i = 0; i < M; i++) {
                var temp = (short) ((pastLsfQ[i] * ALPHA_5) >> 15);
                var sign = (short) ((mean[i] * ONE_ALPHA_5) >> 15);
                lsf1Q[i] = add(sign, temp, overflow);
                lsf2Q[i] = lsf1Q[i];
                temp = (short) ((pastRQ[i] * LSP_PRED_FAC_MR122) >> 15);
                temp = add(mean[i], temp, overflow);
                pastRQ[i] = sub(lsf2Q[i], temp, overflow);
            }
        } else {
            var temp = shl(indice[indiceOffset], (short) 2);
            var dico = Lsf5Tables.DICO1_LSF_5;
            lsf1R[0] = dico[temp];
            lsf1R[1] = dico[temp + 1];
            lsf2R[0] = dico[temp + 2];
            lsf2R[1] = dico[temp + 3];
            temp = shl(indice[indiceOffset + 1], (short) 2);
            dico = Lsf5Tables.DICO2_LSF_5;
            lsf1R[2] = dico[temp];
            lsf1R[3] = dico[temp + 1];
            lsf2R[2] = dico[temp + 2];
            lsf2R[3] = dico[temp + 3];
            var sign = (short) (indice[indiceOffset + 2] & 1);
            var i3 = (short) (indice[indiceOffset + 2] >> 1);
            temp = shl(i3, (short) 2);
            dico = Lsf5Tables.DICO3_LSF_5;
            if (sign == 0) {
                lsf1R[4] = dico[temp];
                lsf1R[5] = dico[temp + 1];
                lsf2R[4] = dico[temp + 2];
                lsf2R[5] = dico[temp + 3];
            } else {
                lsf1R[4] = negate(dico[temp]);
                lsf1R[5] = negate(dico[temp + 1]);
                lsf2R[4] = negate(dico[temp + 2]);
                lsf2R[5] = negate(dico[temp + 3]);
            }
            temp = shl(indice[indiceOffset + 3], (short) 2);
            dico = Lsf5Tables.DICO4_LSF_5;
            lsf1R[6] = dico[temp];
            lsf1R[7] = dico[temp + 1];
            lsf2R[6] = dico[temp + 2];
            lsf2R[7] = dico[temp + 3];
            temp = shl(indice[indiceOffset + 4], (short) 2);
            dico = Lsf5Tables.DICO5_LSF_5;
            lsf1R[8] = dico[temp];
            lsf1R[9] = dico[temp + 1];
            lsf2R[8] = dico[temp + 2];
            lsf2R[9] = dico[temp + 3];
            for (var i = 0; i < M; i++) {
                var value = mult(pastRQ[i], LSP_PRED_FAC_MR122, overflow);
                value = add(mean[i], value, overflow);
                lsf1Q[i] = add(lsf1R[i], value, overflow);
                lsf2Q[i] = add(lsf2R[i], value, overflow);
                pastRQ[i] = lsf2R[i];
            }
        }
        Lsf.reorderLsf(lsf1Q, 0, LSF_GAP, M);
        Lsf.reorderLsf(lsf2Q, 0, LSF_GAP, M);
        System.arraycopy(lsf2Q, 0, pastLsfQ, 0, M);
        Lsf.lsfLsp(lsf1Q, 0, lsp1Q, lsp1QOffset, M);
        Lsf.lsfLsp(lsf2Q, 0, lsp2Q, lsp2QOffset, M);
    }

    /**
     * Interpolates the LSFs of a subframe between the previous and the current frame
     * ({@code Int_lsf}).
     *
     * @param lsfOld the LSFs of the previous frame
     * @param lsfNew the LSFs of the current frame
     * @param iSubfr the first sample of the subframe: 0, 40, 80 or 120
     * @param lsfOut receives the interpolated LSFs
     * @param overflow the flag raised on saturation
     */
    static void interpolate(short[] lsfOld, short[] lsfNew, int iSubfr, short[] lsfOut, Flag overflow) {
        if (iSubfr == 0) {
            for (var i = M - 1; i >= 0; i--) {
                var temp1 = (short) (lsfOld[i] >> 2);
                var temp2 = (short) (lsfNew[i] >> 2);
                lsfOut[i] = add((short) (lsfOld[i] - temp1), temp2, overflow);
            }
        } else if (iSubfr == 40) {
            for (var i = M - 1; i >= 0; i--) {
                lsfOut[i] = (short) ((lsfOld[i] >> 1) + (lsfNew[i] >> 1));
            }
        } else if (iSubfr == 80) {
            for (var i = M - 1; i >= 0; i--) {
                var temp1 = (short) (lsfOld[i] >> 2);
                var temp2 = (short) (lsfNew[i] >> 2);
                lsfOut[i] = add(temp1, (short) (lsfNew[i] - temp2), overflow);
            }
        } else if (iSubfr == 120) {
            System.arraycopy(lsfNew, 0, lsfOut, 0, M);
        }
    }
}
