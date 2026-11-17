/* Ports the renewal of the bandwidth extension LSP prediction of the CELP decoder of the ISO/IEC 14496-3
   reference software as distributed with the ISO/IEC 23003-3 reference software (nec_cng_bws_lspvec_renew of
   nec_cng_lspvq.c in src_lpc), developed by Hironori Ito (NEC Corporation). ISO/IEC gives users of the MPEG-2
   AAC/MPEG-4 Audio standards free license to this software module or modifications thereof for use in hardware or
   software products claiming conformance to the MPEG-2 AAC/MPEG-4 Audio standards. Copyright (c) 1996.
   Modified for Smalt. */
package com.github.auties00.smalt.audio.celp.internal;

/**
 * The LSP decoding tools of the CELP decoder (ISO/IEC 14496-3 subclause 3.5.6): the narrowband and wideband two-stage
 * split vector quantizers, the stabilization of the decoded LSPs, the bandwidth extension of narrowband LSPs, and the
 * conversion of LSPs into LPC coefficients and reflection coefficients.
 *
 * <p>Narrowband and wideband LSPs are normalized to {@code [0, 1]}, where 1 is the Nyquist frequency; the bandwidth
 * extension tool works on LSPs in radians.
 */
final class Lsp {
    /**
     * The interframe prediction ratio of the second stage, {@code ratio_predict}.
     */
    private static final double RATIO_PREDICT = 0.5;

    /**
     * The smallest distance between narrowband LSPs after stabilization.
     */
    static final double NB_MIN_GAP = 2.0 / 256.0;

    /**
     * The smallest distance between wideband LSPs after stabilization.
     */
    static final double WB_MIN_GAP = 1.0 / 256.0;

    /**
     * {@code PAI} of the bandwidth extension tool, the value of pi the standard uses there.
     */
    private static final double PAI = 3.141592;

    /**
     * The smallest distance between bandwidth extension LSPs after stabilization, in radians.
     */
    private static final double BWS_MIN_GAP = 0.028;

    /**
     * Prevents instantiation.
     */
    private Lsp() {
    }

    /**
     * Decodes the ten narrowband LSPs of {@code lpc_indices[0]} to {@code [4]} (subclause 3.5.6.1.3.1), or the LSPs
     * of a SID frame, which carries only {@code [0]} to {@code [2]} and no interframe prediction.
     *
     * @param lpc the LPC indices
     * @param previous the LSPs of the previous frame, the prediction source
     * @param out the ten decoded LSPs, not yet stabilized
     * @param sid whether the indices are those of a SID frame
     */
    static void decodeNarrowband(int[] lpc, double[] previous, double[] out, boolean sid) {
        decodeHalf(CelpTables.NB_LSP1, CelpTables.NB_D, CelpTables.NB_PD, 0, lpc[0], lpc[1], lpc[2], 6,
                sid ? -1 : lpc[3], 5, !sid && lpc[4] == 1, previous, 0, out, 0);
    }

    /**
     * Decodes the twenty wideband LSPs of {@code lpc_indices[0]} to {@code [9]} (subclause 3.5.6.2.3.1), or the LSPs
     * of a SID frame, which carries {@code [0]} to {@code [3]}, {@code [5]} and {@code [6]} and no interframe
     * prediction.
     *
     * @param lpc the LPC indices
     * @param previous the LSPs of the previous frame, the prediction source
     * @param out the twenty decoded LSPs, not yet stabilized
     * @param sid whether the indices are those of a SID frame
     */
    static void decodeWideband(int[] lpc, double[] previous, double[] out, boolean sid) {
        decodeHalf(CelpTables.WB_LSP1, CelpTables.WB_D, CelpTables.WB_PD, 0, lpc[0], lpc[1], lpc[2], 6, lpc[3], 6,
                !sid && lpc[4] == 1, previous, 0, out, 0);
        decodeHalf(CelpTables.WB_LSP1, CelpTables.WB_D, CelpTables.WB_PD, 2, lpc[5], lpc[6], sid ? -1 : lpc[7], 6,
                sid ? -1 : lpc[8], 4, !sid && lpc[9] == 1, previous, 10, out, 10);
    }

    /**
     * Decodes ten LSPs from two first stage and two second stage split vectors of five.
     *
     * @param first the first stage code books
     * @param second the second stage code books without prediction
     * @param predicted the second stage code books with prediction
     * @param book the index of the first code book of the half in the code book arrays
     * @param index0 the first stage index of the lower split
     * @param index1 the first stage index of the upper split
     * @param index2 the second stage index of the lower split, its sign in bit {@code signBit2}, or -1 for none
     * @param signBit2 the sign bit of {@code index2}
     * @param index3 the second stage index of the upper split, or -1 for none
     * @param signBit3 the sign bit of {@code index3}
     * @param predict whether the second stage adds to the prediction from the previous LSPs
     * @param previous the LSPs of the previous frame
     * @param previousOffset the first LSP of the half in {@code previous}
     * @param out the decoded LSPs
     * @param outOffset the first LSP of the half in {@code out}
     */
    private static void decodeHalf(double[][] first, double[][] second, double[][] predicted, int book, int index0,
                                   int index1, int index2, int signBit2, int index3, int signBit3, boolean predict,
                                   double[] previous, int previousOffset, double[] out, int outOffset) {
        var lower = first[book];
        var upper = first[book + 1];
        for (var i = 0; i < 5; i++) {
            out[outOffset + i] = lower[index0 * 5 + i];
            out[outOffset + 5 + i] = upper[index1 * 5 + i];
        }
        if (predict) {
            for (var i = 0; i < 10; i++) {
                out[outOffset + i] = (1 - RATIO_PREDICT) * out[outOffset + i]
                        + RATIO_PREDICT * previous[previousOffset + i];
            }
        }
        var books = predict ? predicted : second;
        addSecondStage(books[book], index2, signBit2, out, outOffset);
        addSecondStage(books[book + 1], index3, signBit3, out, outOffset + 5);
    }

    /**
     * Adds or subtracts a second stage code vector of five, selected by the bits below the sign bit.
     */
    private static void addSecondStage(double[] book, int index, int signBit, double[] out, int offset) {
        if (index < 0) {
            return;
        }
        var row = (index & ((1 << signBit) - 1)) * 5;
        var sign = (index >> signBit) == 0 ? 1 : -1;
        for (var i = 0; i < 5; i++) {
            out[offset + i] += sign * book[row + i];
        }
    }

    /**
     * Arranges decoded LSPs in ascending order with at least a minimum distance between neighbors and from the band
     * edges (subclauses 3.5.6.1.3.2 and 3.5.6.2.4). The LSPs are sorted before the passes of the standard, as the
     * reference decoder does; without the sort a crossed pair keeps its order.
     *
     * @param lsp the LSPs, normalized
     * @param order the number of LSPs
     * @param minGap the minimum distance
     */
    static void stabilize(double[] lsp, int order, double minGap) {
        java.util.Arrays.sort(lsp, 0, order);
        for (var i = 0; i < order; i++) {
            if (lsp[i] < minGap) {
                lsp[i] = minGap;
            }
        }
        for (var i = 0; i < order - 1; i++) {
            if (lsp[i + 1] - lsp[i] < minGap) {
                lsp[i + 1] = lsp[i] + minGap;
            }
        }
        for (var i = 0; i < order; i++) {
            if (lsp[i] > 1 - minGap) {
                lsp[i] = 1 - minGap;
            }
        }
        for (var i = order - 1; i > 0; i--) {
            if (lsp[i] - lsp[i - 1] < minGap) {
                lsp[i - 1] = lsp[i] - minGap;
            }
        }
    }

    /**
     * Decodes the twenty LSPs of the bandwidth extension tool, in radians, from {@code lpc_indices[5]} to
     * {@code [10]} and the narrowband LSPs (subclause 3.5.6.3.3), and shifts the prediction residual buffer.
     *
     * @param lpc the LPC indices
     * @param sid whether the indices are those of a SID frame, which carries no {@code [9]} and {@code [10]}
     * @param narrowband the ten decoded narrowband LSPs, normalized
     * @param residual the prediction residuals of the current and the two previous frames, 20 each
     * @param out the twenty LSPs, stabilized
     */
    static void decodeBandwidth(int[] lpc, boolean sid, double[] narrowband, double[][] residual, double[] out) {
        for (var n = 2; n > 0; n--) {
            System.arraycopy(residual[n - 1], 0, residual[n], 0, 20);
        }
        var current = residual[0];
        var t0 = CelpTables.BWS_LSP[0];
        var t1 = CelpTables.BWS_LSP[1];
        for (var i = 0; i < 10; i++) {
            current[i] = t0[lpc[5] * 10 + i];
            current[10 + i] = t1[lpc[6] * 10 + i];
        }
        for (var i = 0; i < 5; i++) {
            current[i] += CelpTables.BWS_LSP[2][lpc[7] * 5 + i];
            current[5 + i] += CelpTables.BWS_LSP[3][lpc[8] * 5 + i];
            if (!sid) {
                current[10 + i] += CelpTables.BWS_LSP[4][lpc[9] * 5 + i];
                current[15 + i] += CelpTables.BWS_LSP[5][lpc[10] * 5 + i];
            }
        }
        for (var i = 0; i < 20; i++) {
            var value = 0.0;
            for (var n = 0; n < 3; n++) {
                value += CelpTables.BWS_MA_PREDICTION[i * 3 + n] * residual[n][i];
            }
            out[i] = value;
        }
        for (var i = 0; i < 10; i++) {
            out[i] += CelpTables.BWS_NW_PREDICTION[i] * narrowband[i] * Math.PI;
        }
        for (var i = 0; i < 20; i++) {
            if (out[i] < 0 || out[i] > PAI) {
                out[i] = 0.05 + PAI * i / 20;
            }
        }
        for (var i = 19; i > 0; i--) {
            for (var j = 0; j < i; j++) {
                if (out[j] + BWS_MIN_GAP > out[j + 1]) {
                    var middle = 0.5 * (out[j] + out[j + 1]);
                    out[j] = middle - 0.51 * BWS_MIN_GAP;
                    out[j + 1] = middle + 0.51 * BWS_MIN_GAP;
                }
            }
        }
    }

    /**
     * Renews the prediction residuals of the bandwidth extension LSPs in a non-active frame that carries no LSP
     * indices ({@code TX_flag} 0 or 3): the residual that the frame's LSPs imply, under the prediction from the two
     * previous residuals and the narrowband LSPs, becomes the newest one, as the reference decoder does.
     *
     * @param lsp the twenty LSPs of the frame, in radians
     * @param narrowband the ten narrowband LSPs of the frame, normalized
     * @param residual the prediction residuals of the previous and the two frames before, 20 each; on return those of
     * the current and the two previous frames
     */
    static void renewBandwidth(double[] lsp, double[] narrowband, double[][] residual) {
        var renewed = residual[2];
        for (var i = 0; i < 20; i++) {
            var predicted = CelpTables.BWS_MA_PREDICTION[i * 3 + 1] * residual[0][i]
                    + CelpTables.BWS_MA_PREDICTION[i * 3 + 2] * residual[1][i];
            if (i < 10) {
                predicted += CelpTables.BWS_NW_PREDICTION[i] * narrowband[i] * Math.PI;
            }
            renewed[i] = (lsp[i] - predicted) / CelpTables.BWS_MA_PREDICTION[i * 3];
        }
        residual[2] = residual[1];
        residual[1] = residual[0];
        residual[0] = renewed;
    }

    /**
     * Converts LSPs into the coefficients {@code a[k]} of the synthesis filter {@code 1 / (1 - sum a[k] z^-(k+1))}
     * (subclause 3.5.6.1.3.4).
     *
     * @param lsp the LSPs
     * @param scale the factor that turns {@code lsp} into radians: pi for normalized LSPs, 1 for radians
     * @param order the LPC order, even
     * @param a the coefficients
     * @param work scratch of at least {@code 2 * order + 4} values
     */
    static void toLpc(double[] lsp, double scale, int order, double[] a, double[] work) {
        var half = order >> 1;
        var states = 2 * order + 4;
        java.util.Arrays.fill(work, 0, states, 0);
        for (var j = 0; j <= order; j++) {
            var xin1 = j == 0 ? 1.0 : 0.0;
            var xin2 = xin1;
            var n4 = 3;
            for (var i = 0; i < half; i++) {
                var n1 = i * 4;
                var n2 = n1 + 1;
                var n3 = n2 + 1;
                n4 = n3 + 1;
                var xout1 = -2 * StrictMath.cos(lsp[i * 2] * scale) * work[n1] + work[n2] + xin1;
                var xout2 = -2 * StrictMath.cos(lsp[i * 2 + 1] * scale) * work[n3] + work[n4] + xin2;
                work[n2] = work[n1];
                work[n1] = xin1;
                work[n4] = work[n3];
                work[n3] = xin2;
                xin1 = xout1;
                xin2 = xout2;
            }
            var xout1 = xin1 + work[n4 + 1];
            var xout2 = xin2 - work[n4 + 2];
            work[n4 + 1] = xin1;
            work[n4 + 2] = xin2;
            if (j > 0) {
                a[j - 1] = -0.5 * (xout1 + xout2);
            }
        }
    }

    /**
     * Converts the coefficients of a synthesis filter into reflection coefficients, by the step-down recursion.
     *
     * @param a the coefficients of {@code 1 / (1 - sum a[k] z^-(k+1))}
     * @param order the LPC order
     * @param reflection the reflection coefficients
     * @param work scratch of at least {@code 2 * order} values
     */
    static void toReflection(double[] a, int order, double[] reflection, double[] work) {
        for (var i = 0; i < order; i++) {
            work[i] = -a[i];
        }
        for (var m = order - 1; m >= 0; m--) {
            var k = work[m];
            reflection[m] = k;
            var denominator = 1 - k * k;
            if (Math.abs(denominator) < 1e-12) {
                denominator = 1e-12;
            }
            for (var i = 0; i < m; i++) {
                work[order + i] = (work[i] - k * work[m - 1 - i]) / denominator;
            }
            System.arraycopy(work, order, work, 0, m);
        }
    }
}
