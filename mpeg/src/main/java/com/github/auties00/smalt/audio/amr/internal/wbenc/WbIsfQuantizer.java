/* Ports vo-amrwbenc 0.1.3 (qpisf_2s.c, qisf_ns.c). Copyright VisualOn; Apache License 2.0. Modified for Smalt. */
package com.github.auties00.smalt.audio.amr.internal.wbenc;

import com.github.auties00.smalt.audio.amr.internal.wbcommon.WbIsfCodebooks;
import com.github.auties00.smalt.audio.amr.internal.wbcommon.WbIsfSplitCodebooks;

/**
 * Quantizes the ISFs of the AMR-WB encoder: the split-multistage vector quantizer with moving
 * average prediction of the speech modes (46 bits, or 36 bits at 6.60 kbit/s) and the
 * memoryless quantizer of the comfort-noise (SID) frames.
 *
 * <p>An instance holds the scratch vectors of the search, so quantizing allocates nothing; an
 * instance is not thread-safe.
 *
 * @implNote This implementation computes with the saturating arithmetic of the 3GPP TS 26.173 fixed-point reference.
 * The dequantizers are ported only for the good-frame path the encoder uses ({@code bfi == 0}, {@code enc_dec == 0}).
 */
final class WbIsfQuantizer {
    /**
     * The LP order.
     */
    private static final int ORDER = 16;

    /**
     * The prediction factor 1/3 in Q15.
     */
    private static final int MU = 10923;

    /**
     * The smallest distance kept between consecutive quantized ISFs (50 Hz).
     */
    private static final int ISF_GAP = 128;

    /**
     * The largest number of first-stage survivors.
     */
    private static final int N_SURV_MAX = 4;

    /**
     * The mean-removed, prediction-removed ISFs being quantized.
     */
    private final short[] isf = new short[ORDER];

    /**
     * The residual of the first stage.
     */
    private final short[] stage2 = new short[ORDER];

    /**
     * The second-stage indices of the survivor under evaluation.
     */
    private final short[] tmpIndices = new short[5];

    /**
     * The first-stage survivors.
     */
    private final short[] survivors = new short[N_SURV_MAX];

    /**
     * The distances of the first-stage survivors.
     */
    private final int[] survivorDistances = new int[N_SURV_MAX];

    /**
     * The distance of the last {@link #subVq(short[], int, short[], int, int)} match.
     */
    private int minError;

    /**
     * Creates the quantizer with its scratch vectors.
     */
    WbIsfQuantizer() {
    }

    /**
     * Quantizes the ISFs with 46 bits ({@code Qpisf_2s_46b}).
     *
     * @param isf1 the 16 ISFs to quantize
     * @param isfQ the 16 quantized ISFs, which may be {@code isf1}
     * @param pastIsfq the 16 quantized prediction residuals of the previous frame, updated
     * @param indices the 7 indices
     * @param nbSurv the number of first-stage survivors, at most 4
     */
    void qpisf46b(short[] isf1, short[] isfQ, short[] pastIsfq, short[] indices, int nbSurv) {
        removeMeanAndPrediction(isf1, pastIsfq);
        vqStage1(isf, 0, WbIsfCodebooks.DICO1, 9, 256, nbSurv);
        var distance = WbBasicOps.MAX_32;
        for (var k = 0; k < nbSurv; k++) {
            for (var i = 0; i < 9; i++) {
                stage2[i] = WbBasicOps.sub(isf[i], WbIsfCodebooks.DICO1[i + survivors[k] * 9]);
            }
            tmpIndices[0] = subVq(stage2, 0, WbIsfSplitCodebooks.DICO21, 3, 64);
            var temp = minError;
            tmpIndices[1] = subVq(stage2, 3, WbIsfSplitCodebooks.DICO22, 3, 128);
            temp = WbBasicOps.lAdd(temp, minError);
            tmpIndices[2] = subVq(stage2, 6, WbIsfSplitCodebooks.DICO23, 3, 128);
            temp = WbBasicOps.lAdd(temp, minError);
            if (temp < distance) {
                distance = temp;
                indices[0] = survivors[k];
                for (var i = 0; i < 3; i++) {
                    indices[i + 2] = tmpIndices[i];
                }
            }
        }
        vqStage1(isf, 9, WbIsfCodebooks.DICO2, 7, 256, nbSurv);
        distance = WbBasicOps.MAX_32;
        for (var k = 0; k < nbSurv; k++) {
            for (var i = 0; i < 7; i++) {
                stage2[i] = WbBasicOps.sub(isf[9 + i], WbIsfCodebooks.DICO2[i + survivors[k] * 7]);
            }
            tmpIndices[0] = subVq(stage2, 0, WbIsfSplitCodebooks.DICO24, 3, 32);
            var temp = minError;
            tmpIndices[1] = subVq(stage2, 3, WbIsfSplitCodebooks.DICO25, 4, 32);
            temp = WbBasicOps.lAdd(temp, minError);
            if (temp < distance) {
                distance = temp;
                indices[1] = survivors[k];
                for (var i = 0; i < 2; i++) {
                    indices[i + 5] = tmpIndices[i];
                }
            }
        }
        dpisf46b(indices, isfQ, pastIsfq);
    }

    /**
     * Quantizes the ISFs with 36 bits ({@code Qpisf_2s_36b}).
     *
     * @param isf1 the 16 ISFs to quantize
     * @param isfQ the 16 quantized ISFs, which may be {@code isf1}
     * @param pastIsfq the 16 quantized prediction residuals of the previous frame, updated
     * @param indices the 5 indices
     * @param nbSurv the number of first-stage survivors, at most 4
     */
    void qpisf36b(short[] isf1, short[] isfQ, short[] pastIsfq, short[] indices, int nbSurv) {
        removeMeanAndPrediction(isf1, pastIsfq);
        vqStage1(isf, 0, WbIsfCodebooks.DICO1, 9, 256, nbSurv);
        var distance = WbBasicOps.MAX_32;
        for (var k = 0; k < nbSurv; k++) {
            for (var i = 0; i < 9; i++) {
                stage2[i] = WbBasicOps.sub(isf[i], WbIsfCodebooks.DICO1[i + survivors[k] * 9]);
            }
            tmpIndices[0] = subVq(stage2, 0, WbIsfSplitCodebooks.DICO21_36B, 5, 128);
            var temp = minError;
            tmpIndices[1] = subVq(stage2, 5, WbIsfSplitCodebooks.DICO22_36B, 4, 128);
            temp = WbBasicOps.lAdd(temp, minError);
            if (temp < distance) {
                distance = temp;
                indices[0] = survivors[k];
                for (var i = 0; i < 2; i++) {
                    indices[i + 2] = tmpIndices[i];
                }
            }
        }
        vqStage1(isf, 9, WbIsfCodebooks.DICO2, 7, 256, nbSurv);
        distance = WbBasicOps.MAX_32;
        for (var k = 0; k < nbSurv; k++) {
            for (var i = 0; i < 7; i++) {
                stage2[i] = WbBasicOps.sub(isf[9 + i], WbIsfCodebooks.DICO2[i + survivors[k] * 7]);
            }
            tmpIndices[0] = subVq(stage2, 0, WbIsfSplitCodebooks.DICO23_36B, 7, 64);
            var temp = minError;
            if (temp < distance) {
                distance = temp;
                indices[1] = survivors[k];
                indices[4] = tmpIndices[0];
            }
        }
        dpisf36b(indices, isfQ, pastIsfq);
    }

    /**
     * Removes the mean and the moving-average prediction from the ISFs into {@link #isf}.
     *
     * @param isf1 the ISFs
     * @param pastIsfq the quantized prediction residuals of the previous frame
     */
    private void removeMeanAndPrediction(short[] isf1, short[] pastIsfq) {
        for (var i = 0; i < ORDER; i++) {
            isf[i] = WbBasicOps.sub(isf1[i], WbIsfCodebooks.MEAN_ISF[i]);
            isf[i] = WbBasicOps.sub(isf[i], WbBasicOps.mult(MU, pastIsfq[i]));
        }
    }

    /**
     * Dequantizes 46-bit ISF indices of a good frame ({@code Dpisf_2s_46b} with
     * {@code bfi == 0}).
     *
     * @param indices the 7 indices
     * @param isfQ the 16 quantized ISFs
     * @param pastIsfq the quantized prediction residuals, updated
     */
    private static void dpisf46b(short[] indices, short[] isfQ, short[] pastIsfq) {
        for (var i = 0; i < 9; i++) {
            isfQ[i] = WbIsfCodebooks.DICO1[indices[0] * 9 + i];
        }
        for (var i = 0; i < 7; i++) {
            isfQ[i + 9] = WbIsfCodebooks.DICO2[indices[1] * 7 + i];
        }
        for (var i = 0; i < 3; i++) {
            isfQ[i] = WbBasicOps.add(isfQ[i], WbIsfSplitCodebooks.DICO21[indices[2] * 3 + i]);
            isfQ[i + 3] = WbBasicOps.add(isfQ[i + 3], WbIsfSplitCodebooks.DICO22[indices[3] * 3 + i]);
            isfQ[i + 6] = WbBasicOps.add(isfQ[i + 6], WbIsfSplitCodebooks.DICO23[indices[4] * 3 + i]);
            isfQ[i + 9] = WbBasicOps.add(isfQ[i + 9], WbIsfSplitCodebooks.DICO24[indices[5] * 3 + i]);
        }
        for (var i = 0; i < 4; i++) {
            isfQ[i + 12] = WbBasicOps.add(isfQ[i + 12], WbIsfSplitCodebooks.DICO25[indices[6] * 4 + i]);
        }
        addMeanAndPrediction(isfQ, pastIsfq);
    }

    /**
     * Dequantizes 36-bit ISF indices of a good frame ({@code Dpisf_2s_36b} with
     * {@code bfi == 0}).
     *
     * @param indices the 5 indices
     * @param isfQ the 16 quantized ISFs
     * @param pastIsfq the quantized prediction residuals, updated
     */
    private static void dpisf36b(short[] indices, short[] isfQ, short[] pastIsfq) {
        for (var i = 0; i < 9; i++) {
            isfQ[i] = WbIsfCodebooks.DICO1[indices[0] * 9 + i];
        }
        for (var i = 0; i < 7; i++) {
            isfQ[i + 9] = WbIsfCodebooks.DICO2[indices[1] * 7 + i];
        }
        for (var i = 0; i < 5; i++) {
            isfQ[i] = WbBasicOps.add(isfQ[i], WbIsfSplitCodebooks.DICO21_36B[indices[2] * 5 + i]);
        }
        for (var i = 0; i < 4; i++) {
            isfQ[i + 5] = WbBasicOps.add(isfQ[i + 5], WbIsfSplitCodebooks.DICO22_36B[indices[3] * 4 + i]);
        }
        for (var i = 0; i < 7; i++) {
            isfQ[i + 9] = WbBasicOps.add(isfQ[i + 9], WbIsfSplitCodebooks.DICO23_36B[indices[4] * 7 + i]);
        }
        addMeanAndPrediction(isfQ, pastIsfq);
    }

    /**
     * Adds the mean and the prediction back to dequantized residuals, stores the residuals as
     * the next prediction memory and enforces the minimum ISF spacing.
     *
     * @param isfQ the dequantized residuals, replaced by the quantized ISFs
     * @param pastIsfq the prediction memory, updated
     */
    private static void addMeanAndPrediction(short[] isfQ, short[] pastIsfq) {
        for (var i = 0; i < ORDER; i++) {
            var tmp = isfQ[i];
            isfQ[i] = WbBasicOps.add(tmp, WbIsfCodebooks.MEAN_ISF[i]);
            isfQ[i] = WbBasicOps.add(isfQ[i], WbBasicOps.mult(MU, pastIsfq[i]));
            pastIsfq[i] = tmp;
        }
        reorderIsf(isfQ, ISF_GAP, ORDER);
    }

    /**
     * Enforces a minimum distance between consecutive ISFs ({@code Reorder_isf}).
     *
     * @param isf the ISFs, adjusted in place
     * @param minDist the minimum distance
     * @param n the number of ISFs
     */
    static void reorderIsf(short[] isf, int minDist, int n) {
        var isfMin = (short) minDist;
        for (var i = 0; i < n - 1; i++) {
            if (isf[i] < isfMin) {
                isf[i] = isfMin;
            }
            isfMin = WbBasicOps.add(isf[i], minDist);
        }
    }

    /**
     * Finds the nearest codebook entry of a subvector, replaces the subvector with it and records
     * the distance in {@link #minError} ({@code Sub_VQ}).
     *
     * @param x the vector holding the subvector, replaced by the chosen entry
     * @param xOff the offset of the subvector
     * @param dico the codebook
     * @param dim the subvector dimension
     * @param dicoSize the number of codebook entries
     * @return the index of the nearest entry
     */
    short subVq(short[] x, int xOff, short[] dico, int dim, int dicoSize) {
        var distMin = WbBasicOps.MAX_32;
        var p = 0;
        var index = 0;
        for (var i = 0; i < dicoSize; i++) {
            var dist = 0;
            for (var j = 0; j < dim; j++) {
                var temp = WbBasicOps.sub(x[xOff + j], dico[p++]);
                dist = WbBasicOps.lMac(dist, temp, temp);
            }
            if (dist < distMin) {
                distMin = dist;
                index = i;
            }
        }
        minError = distMin;
        System.arraycopy(dico, index * dim, x, xOff, dim);
        return (short) index;
    }

    /**
     * Finds the {@code surv} nearest entries of the first-stage codebook into
     * {@link #survivors} ({@code VQ_stage1}).
     *
     * @param x the vector holding the subvector
     * @param xOff the offset of the subvector
     * @param dico the codebook
     * @param dim the subvector dimension
     * @param dicoSize the number of codebook entries
     * @param surv the number of survivors, at most 4
     */
    private void vqStage1(short[] x, int xOff, short[] dico, int dim, int dicoSize, int surv) {
        var distMin = survivorDistances;
        var index = survivors;
        for (var k = 0; k < N_SURV_MAX; k++) {
            distMin[k] = WbBasicOps.MAX_32;
            index[k] = (short) k;
        }
        var p = 0;
        for (var i = 0; i < dicoSize; i++) {
            var dist = 0;
            for (var j = 0; j < dim; j++) {
                var temp = WbBasicOps.sub(x[xOff + j], dico[p++]);
                dist = WbBasicOps.lMac(dist, temp, temp);
            }
            for (var k = 0; k < surv; k++) {
                if (dist < distMin[k]) {
                    for (var l = surv - 1; l > k; l--) {
                        distMin[l] = distMin[l - 1];
                        index[l] = index[l - 1];
                    }
                    distMin[k] = dist;
                    index[k] = (short) i;
                    break;
                }
            }
        }
    }

    /**
     * Quantizes the ISFs of a comfort-noise (SID) frame with 28 bits ({@code Qisf_ns}).
     *
     * @param isf1 the 16 ISFs to quantize
     * @param isfQ the 16 quantized ISFs, which may be {@code isf1}
     * @param indices the 5 indices
     */
    void qisfNs(short[] isf1, short[] isfQ, short[] indices) {
        for (var i = 0; i < ORDER; i++) {
            isfQ[i] = WbBasicOps.sub(isf1[i], WbIsfSplitCodebooks.MEAN_ISF_NOISE[i]);
        }
        indices[0] = subVq(isfQ, 0, WbIsfSplitCodebooks.DICO1_NOISE, 2, 64);
        indices[1] = subVq(isfQ, 2, WbIsfSplitCodebooks.DICO2_NOISE, 3, 64);
        indices[2] = subVq(isfQ, 5, WbIsfSplitCodebooks.DICO3_NOISE, 3, 64);
        indices[3] = subVq(isfQ, 8, WbIsfSplitCodebooks.DICO4_NOISE, 4, 32);
        indices[4] = subVq(isfQ, 12, WbIsfSplitCodebooks.DICO5_NOISE, 4, 32);
        disfNs(indices, isfQ);
    }

    /**
     * Dequantizes the ISFs of a comfort-noise frame ({@code Disf_ns}).
     *
     * @param indices the 5 indices
     * @param isfQ the 16 quantized ISFs
     */
    private static void disfNs(short[] indices, short[] isfQ) {
        System.arraycopy(WbIsfSplitCodebooks.DICO1_NOISE, indices[0] * 2, isfQ, 0, 2);
        System.arraycopy(WbIsfSplitCodebooks.DICO2_NOISE, indices[1] * 3, isfQ, 2, 3);
        System.arraycopy(WbIsfSplitCodebooks.DICO3_NOISE, indices[2] * 3, isfQ, 5, 3);
        System.arraycopy(WbIsfSplitCodebooks.DICO4_NOISE, indices[3] * 4, isfQ, 8, 4);
        System.arraycopy(WbIsfSplitCodebooks.DICO5_NOISE, indices[4] * 4, isfQ, 12, 4);
        for (var i = 0; i < ORDER; i++) {
            isfQ[i] = WbBasicOps.add(isfQ[i], WbIsfSplitCodebooks.MEAN_ISF_NOISE[i]);
        }
        reorderIsf(isfQ, ISF_GAP, ORDER);
    }
}
