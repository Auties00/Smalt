package com.github.auties00.smalt.video.av1.internal;

/**
 * Holds the parameters of one loop restoration unit as the restoration kernels consume them.
 *
 * <p>A Wiener unit uses {@link #filter}; a self-guided unit uses {@link #s0}, {@link #s1},
 * {@link #w0} and {@link #w1}. Instances are mutable and reused by the caller from unit to unit.
 *
 * @implNote This implementation mirrors dav1d's {@code LooprestorationParams} union.
 */
public final class LrParams {
    /**
     * The two 7-tap Wiener filters, flattened as {@code [direction][tap]} with eight entries per
     * direction: entries 0 to 6 are the horizontal taps, 8 to 14 the vertical taps, and 7 and 15
     * are unused.
     *
     * <p>Both filters hold the full symmetric taps (AV1 specification section 7.17.4): taps
     * {@code k} and {@code 6 - k} equal the coded coefficient {@code k} for {@code k < 3}, and the
     * center tap is {@code 128 - 2 * (c0 + c1 + c2)}. Unlike dav1d's 8-bit template, which keeps the
     * {@code 128} of the horizontal center tap out of the coefficient and adds it separately, the
     * horizontal center tap includes it for every bit depth; both forms compute the same values.
     * A 5-tap filter has zero outer taps.
     */
    public final short[] filter = new short[16];

    /**
     * The self-guided scale of the 5x5 (radius 2) box filter ({@code dav1d_sgr_params[set][0]}),
     * or zero when that pass is disabled.
     */
    public int s0;

    /**
     * The self-guided scale of the 3x3 (radius 1) box filter ({@code dav1d_sgr_params[set][1]}),
     * or zero when that pass is disabled.
     */
    public int s1;

    /**
     * The weight of the 5x5 pass in the self-guided projection ({@code sgr_weights[0]}).
     */
    public int w0;

    /**
     * The weight of the 3x3 pass in the self-guided projection,
     * {@code 128 - (sgr_weights[0] + sgr_weights[1])}.
     */
    public int w1;

    /**
     * Creates an all-zero parameter set.
     */
    public LrParams() {
    }

    /**
     * Fills {@link #filter} with the symmetric Wiener taps of a unit.
     *
     * @param h0 the outermost horizontal coefficient
     * @param h1 the middle horizontal coefficient
     * @param h2 the innermost horizontal coefficient
     * @param v0 the outermost vertical coefficient
     * @param v1 the middle vertical coefficient
     * @param v2 the innermost vertical coefficient
     */
    public void setWiener(int h0, int h1, int h2, int v0, int v1, int v2) {
        filter[0] = filter[6] = (short) h0;
        filter[1] = filter[5] = (short) h1;
        filter[2] = filter[4] = (short) h2;
        filter[3] = (short) (128 - (h0 + h1 + h2) * 2);
        filter[7] = 0;
        filter[8] = filter[14] = (short) v0;
        filter[9] = filter[13] = (short) v1;
        filter[10] = filter[12] = (short) v2;
        filter[11] = (short) (128 - (v0 + v1 + v2) * 2);
        filter[15] = 0;
    }
}
