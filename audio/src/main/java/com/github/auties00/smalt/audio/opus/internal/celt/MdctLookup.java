/* Ports libopus 1.5.2 (celt/mdct.h). Copyright Xiph.Org Foundation, CSIRO and Jean-Marc Valin; BSD 3-Clause License.
   Modified for Smalt. */
package com.github.auties00.smalt.audio.opus.internal.celt;

/**
 * Describes the MDCT configurations of a mode ({@code mdct_lookup}).
 *
 * <p>One lookup covers the long MDCT of {@link #n} samples and its {@link #maxshift} halvings;
 * transform {@code shift} uses {@code kfft[shift]} and the twiddles that follow those of the
 * larger transforms in {@link #trig}.
 */
public final class MdctLookup {
    /**
     * The size of the longest MDCT input, twice its number of coefficients.
     */
    public final int n;

    /**
     * The largest supported halving shift.
     */
    public final int maxshift;

    /**
     * The complex FFT used by each shift, the 480-point FFT first.
     */
    public final KissFftState[] kfft;

    /**
     * The pre- and post-rotation twiddles of every shift, concatenated.
     */
    public final float[] trig;

    /**
     * Creates a lookup.
     *
     * @param n        the longest MDCT input size
     * @param maxshift the largest halving shift
     * @param kfft     the FFT of each shift
     * @param trig     the concatenated twiddles
     */
    MdctLookup(int n, int maxshift, KissFftState[] kfft, float[] trig) {
        this.n = n;
        this.maxshift = maxshift;
        this.kfft = kfft;
        this.trig = trig;
    }
}
