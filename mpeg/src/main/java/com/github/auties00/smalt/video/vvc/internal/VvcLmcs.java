/* Ports VVdeC 3.3.0 (source/Lib/CommonLib/Reshape.cpp: luma mapping with chroma scaling). Copyright (c) 2018-2026,
 * Fraunhofer-Gesellschaft zur Foerderung der angewandten Forschung e.V. & The VVdeC Authors; Clear BSD License.
 * Modified for Smalt. */
package com.github.auties00.smalt.video.vvc.internal;

/**
 * Holds the luma mapping with chroma scaling model of a picture (ITU-T H.266 7.4.3.19) and applies it: the forward
 * mapping of inter predicted luma samples (8.7.5.2), the chroma residual scaling (8.7.5.3) and the inverse mapping
 * before the in-loop filters (8.8.2).
 *
 * <p>Instances are not thread-safe.
 */
final class VvcLmcs {
    /**
     * Constructs a mapping, set up by {@link #init}.
     */
    VvcLmcs() {
    }

    /**
     * {@code InputPivot} of each bin.
     */
    private final int[] inputPivot = new int[17];
    /**
     * {@code LmcsPivot} of each bin.
     */
    private final int[] lmcsPivot = new int[17];
    /**
     * {@code ScaleCoeff} of each bin.
     */
    private final int[] scaleCoeff = new int[16];
    /**
     * {@code InvScaleCoeff} of each bin.
     */
    private final int[] invScaleCoeff = new int[16];
    /**
     * {@code ChromaScaleCoeff} of each bin.
     */
    private final int[] chromaScaleCoeff = new int[16];
    /**
     * {@code lmcs_min_bin_idx}.
     */
    private int minBin;
    /**
     * {@code LmcsMaxBinIdx}.
     */
    private int maxBin;
    /**
     * The base 2 logarithm of {@code OrgCW}.
     */
    private int log2OrgCw;
    /**
     * The luma bit depth.
     */
    private int bitDepth;
    /**
     * The forward and inverse mapping of every sample value.
     */
    private int[] forward = new int[0];
    /**
     * The inverse mapping of every reconstructed luma sample value.
     */
    private int[] inverse = new int[0];

    /**
     * Derives the model from an LMCS adaptation parameter set.
     *
     * @param aps the parameter set
     * @param bitDepth the luma bit depth
     */
    void init(VvcAps aps, int bitDepth) {
        this.bitDepth = bitDepth;
        var orgCw = (1 << bitDepth) / 16;
        log2OrgCw = Integer.numberOfTrailingZeros(orgCw);
        minBin = aps.lmcsMinBinIdx;
        maxBin = aps.lmcsMaxBinIdx;
        var cw = new int[16];
        for (var i = minBin; i <= maxBin; i++) {
            cw[i] = orgCw + aps.lmcsDeltaCw[i];
        }
        lmcsPivot[0] = 0;
        for (var i = 0; i < 16; i++) {
            inputPivot[i] = i * orgCw;
            lmcsPivot[i + 1] = lmcsPivot[i] + cw[i];
            scaleCoeff[i] = (cw[i] * (1 << 11) + (1 << (log2OrgCw - 1))) >> log2OrgCw;
            invScaleCoeff[i] = cw[i] == 0 ? 0 : orgCw * (1 << 11) / cw[i];
            var denominator = cw[i] + aps.lmcsDeltaCrs;
            chromaScaleCoeff[i] = cw[i] == 0 ? 1 << 11 : denominator <= 0 ? 1 << 11
                    : orgCw * (1 << 11) / denominator;
        }
        inputPivot[16] = 16 * orgCw;
        var size = 1 << bitDepth;
        if (forward.length != size) {
            forward = new int[size];
            inverse = new int[size];
        }
        var max = size - 1;
        for (var v = 0; v < size; v++) {
            var idx = v >> log2OrgCw;
            forward[v] = lmcsPivot[idx] + ((scaleCoeff[idx] * (v - inputPivot[idx]) + (1 << 10)) >> 11);
            var inv = pieceIndex(v);
            inverse[v] = Math.clamp(inputPivot[inv] + ((invScaleCoeff[inv] * (v - lmcsPivot[inv]) + (1 << 10)) >> 11),
                    0, max);
        }
    }

    /**
     * Derives the piecewise function index of a mapped luma sample (8.8.2.3).
     *
     * @param sample the mapped luma sample
     * @return the piece index, from {@code lmcs_min_bin_idx} to {@code LmcsMaxBinIdx}
     */
    int pieceIndex(int sample) {
        var idx = minBin;
        for (; idx <= maxBin; idx++) {
            if (sample < lmcsPivot[idx + 1]) {
                break;
            }
        }
        return Math.min(idx, 15);
    }

    /**
     * {@return the forward mapping of a predicted luma sample}
     *
     * @param sample the predicted luma sample
     */
    int forward(int sample) {
        return forward[sample];
    }

    /**
     * {@return the inverse mapping of a reconstructed luma sample}
     *
     * @param sample the reconstructed luma sample
     */
    int inverse(int sample) {
        return inverse[sample];
    }

    /**
     * {@return {@code ChromaScaleCoeff} of the piece containing an average luma value}
     *
     * @param averageLuma the average of the mapped luma samples around the chroma block
     */
    int chromaScale(int averageLuma) {
        return chromaScaleCoeff[pieceIndex(averageLuma)];
    }
}
