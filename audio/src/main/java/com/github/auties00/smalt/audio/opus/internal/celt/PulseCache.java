/* Ports libopus 1.5.2 (celt/modes.h). Copyright Xiph.Org Foundation, CSIRO and Jean-Marc Valin; BSD 3-Clause License.
   Modified for Smalt. */
package com.github.auties00.smalt.audio.opus.internal.celt;

/**
 * Holds the precomputed PVQ bit costs of a mode ({@code PulseCache}).
 */
public final class PulseCache {
    /**
     * The number of entries in {@link #bits}.
     */
    public final int size;

    /**
     * The offset in {@link #bits} of the cache of each {@code (LM + 1) * nbEBands + band}, or
     * {@code -1} when the band is empty.
     */
    public final short[] index;

    /**
     * The cost in 1/8 bits, minus one, of each pseudo-pulse count; the first entry of each run is
     * the largest pseudo-pulse count of that run. Unsigned bytes.
     */
    public final short[] bits;

    /**
     * The largest rate at which each band reliably uses the bits it is given, per LM and channel
     * count. Unsigned bytes.
     */
    public final short[] caps;

    /**
     * Creates a pulse cache.
     *
     * @param size  the number of cost entries
     * @param index the per-band offsets
     * @param bits  the costs
     * @param caps  the rate caps
     */
    PulseCache(int size, short[] index, short[] bits, short[] caps) {
        this.size = size;
        this.index = index;
        this.bits = bits;
        this.caps = caps;
    }
}
