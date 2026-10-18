/* Ports libwebp 1.6.0 (src/enc/vp8i_enc.h, VP8ModeScore). Copyright (c) 2010, Google Inc.; BSD 3-Clause License.
   Modified for Smalt. */
package com.github.auties00.smalt.video.vp8.internal;

/**
 * Accumulates the score, modes, and quantized levels of one macroblock coding decision.
 */
final class ModeScore {
    /**
     * The score of an impossible decision; below the {@code long} maximum so sums do not
     * overflow.
     */
    static final long MAX_COST = 0x7FFFFFFFFFFFFFL;

    /**
     * The sum of squared errors.
     */
    long d;

    /**
     * The spectral distortion.
     */
    long sd;

    /**
     * The header (mode) cost.
     */
    long h;

    /**
     * The coefficient cost.
     */
    long r;

    /**
     * The combined rate-distortion score.
     */
    long score;

    /**
     * The quantized Y2 levels, in scan order.
     */
    final short[] yDcLevels = new short[16];

    /**
     * The quantized luma levels of the 16 blocks, in scan order.
     */
    final short[] yAcLevels = new short[256];

    /**
     * The quantized chroma levels of the 8 blocks, in scan order.
     */
    final short[] uvLevels = new short[128];

    /**
     * The 16x16 luma mode.
     */
    int modeI16;

    /**
     * The 16 subblock modes.
     */
    final int[] modesI4 = new int[16];

    /**
     * The chroma mode.
     */
    int modeUv;

    /**
     * The nonzero flags: bits 0 to 15 luma, 16 to 23 chroma, 24 Y2.
     */
    int nz;

    /**
     * The chroma DC diffusion errors of blocks 1 to 3 of each chroma plane, {@code [plane][3]}.
     */
    final int[] derr = new int[6];

    /**
     * Creates a score.
     */
    ModeScore() {
    }

    /**
     * Resets the scores to an impossible decision.
     */
    void init() {
        d = 0;
        sd = 0;
        r = 0;
        h = 0;
        nz = 0;
        score = MAX_COST;
    }

    /**
     * Copies the scalar scores of another decision.
     *
     * @param o the source
     */
    void copyScore(ModeScore o) {
        d = o.d;
        sd = o.sd;
        r = o.r;
        h = o.h;
        nz = o.nz;
        score = o.score;
    }

    /**
     * Adds the scalar scores of another decision, merging its nonzero flags.
     *
     * @param o the addend
     */
    void addScore(ModeScore o) {
        d += o.d;
        sd += o.sd;
        r += o.r;
        h += o.h;
        nz |= o.nz;
        score += o.score;
    }

    /**
     * Copies every field of another decision.
     *
     * @param o the source
     */
    void copyFrom(ModeScore o) {
        copyScore(o);
        System.arraycopy(o.yDcLevels, 0, yDcLevels, 0, 16);
        System.arraycopy(o.yAcLevels, 0, yAcLevels, 0, 256);
        System.arraycopy(o.uvLevels, 0, uvLevels, 0, 128);
        modeI16 = o.modeI16;
        System.arraycopy(o.modesI4, 0, modesI4, 0, 16);
        modeUv = o.modeUv;
        System.arraycopy(o.derr, 0, derr, 0, 6);
    }

    /**
     * Sets the score from its components: {@code (r + h) * lambda + 256 * (d + sd)}.
     *
     * @param lambda the rate weight
     */
    void setRdScore(int lambda) {
        score = (r + h) * lambda + 256 * (d + sd);
    }
}
