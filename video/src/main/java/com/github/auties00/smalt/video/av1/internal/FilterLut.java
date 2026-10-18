package com.github.auties00.smalt.video.av1.internal;

/**
 * Holds the deblocking filter limits of one frame, indexed by filter level (AV1 specification
 * section 7.14.4, the filter limit derivation).
 *
 * <p>For every filter level from 0 to 63, {@link #e} holds the edge limit and {@link #i} the
 * interior limit at 8-bit precision; the kernels scale both by the bit depth. The high edge
 * variance threshold is the level shifted right by four and needs no table. The table depends
 * only on the frame's {@code loop_filter_sharpness} and is rebuilt by
 * {@link LoopFilterMasks#calcEih(FilterLut, int)} when the sharpness changes.
 *
 * @implNote This implementation mirrors dav1d's {@code Av1FilterLUT}; the limits are stored as
 *           {@code int} rather than bytes so the kernels read them without masking.
 */
public final class FilterLut {
    /**
     * The edge limits ({@code limit + 2 * (level + 2)}), indexed by filter level.
     */
    public final int[] e = new int[64];

    /**
     * The interior limits, indexed by filter level.
     */
    public final int[] i = new int[64];

    /**
     * The sharpness-derived shift ({@code (sharpness + 3) >> 2}) at index 0 and the limit cap
     * ({@code 9 - sharpness}, or {@code 0xff} at sharpness 0) at index 1, as dav1d keeps them for
     * its vector kernels.
     */
    public final int[] sharp = new int[2];

    /**
     * Creates an all-zero table; {@link LoopFilterMasks#calcEih(FilterLut, int)} fills it.
     */
    public FilterLut() {
    }
}
