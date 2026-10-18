/* Ports libwebp 1.6.0 (src/enc/vp8i_enc.h, VP8SegmentInfo). Copyright (c) 2010, Google Inc.; BSD 3-Clause License.
   Modified for Smalt. */
package com.github.auties00.smalt.video.vp8.internal;

/**
 * Holds the quantization, filtering, and rate-distortion parameters of one encoder segment.
 */
final class SegmentInfo {
    /**
     * The luma AC (and DC without Y2) quantization matrix.
     */
    final QuantMatrix y1 = new QuantMatrix();

    /**
     * The Y2 quantization matrix.
     */
    final QuantMatrix y2 = new QuantMatrix();

    /**
     * The chroma quantization matrix.
     */
    final QuantMatrix uv = new QuantMatrix();

    /**
     * The quantization susceptibility, {@code [-127, 127]}.
     */
    int alpha;

    /**
     * The filter susceptibility, {@code [0, 255]}.
     */
    int beta;

    /**
     * The quantizer index.
     */
    int quant;

    /**
     * The loop filter strength.
     */
    int fstrength;

    /**
     * The largest DC step between subblocks of blocky macroblocks, for filter adjustment.
     */
    int maxEdge;

    /**
     * The distortion above which a blocky macroblock updates {@link #maxEdge}.
     */
    int minDisto;

    /**
     * The rate weight of 16x16 luma mode decisions.
     */
    int lambdaI16;

    /**
     * The rate weight of 4x4 luma mode decisions.
     */
    int lambdaI4;

    /**
     * The rate weight of chroma mode decisions.
     */
    int lambdaUv;

    /**
     * The rate weight of the final macroblock score.
     */
    int lambdaMode;

    /**
     * The spectral distortion weight.
     */
    int tlambda;

    /**
     * The rate weight of trellis quantization of 16x16 luma blocks.
     */
    int lambdaTrellisI16;

    /**
     * The rate weight of trellis quantization of 4x4 luma blocks.
     */
    int lambdaTrellisI4;

    /**
     * The penalty added to 4x4 luma scores in distortion-only decisions.
     */
    long i4Penalty;

    /**
     * Creates a zeroed segment.
     */
    SegmentInfo() {
    }

    /**
     * Copies every field of another segment into this one.
     *
     * @param o the source
     */
    void copyFrom(SegmentInfo o) {
        y1.copyFrom(o.y1);
        y2.copyFrom(o.y2);
        uv.copyFrom(o.uv);
        alpha = o.alpha;
        beta = o.beta;
        quant = o.quant;
        fstrength = o.fstrength;
        maxEdge = o.maxEdge;
        minDisto = o.minDisto;
        lambdaI16 = o.lambdaI16;
        lambdaI4 = o.lambdaI4;
        lambdaUv = o.lambdaUv;
        lambdaMode = o.lambdaMode;
        tlambda = o.tlambda;
        lambdaTrellisI16 = o.lambdaTrellisI16;
        lambdaTrellisI4 = o.lambdaTrellisI4;
        i4Penalty = o.i4Penalty;
    }
}
