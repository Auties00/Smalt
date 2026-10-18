package com.github.auties00.smalt.video.av1.internal;

/**
 * Holds the loop restoration parameters of one restoration unit.
 *
 * <p>For self-guided units, {@link #type} is {@link FrameHeader#RESTORATION_SGRPROJ} plus the
 * parameter set index.
 *
 * @implNote This implementation mirrors dav1d's {@code Av1RestorationUnit} ({@code src/lf_mask.h}).
 */
public final class RestorationUnit {
    /**
     * The restoration type.
     */
    public int type;

    /**
     * The three distinct horizontal Wiener taps.
     */
    public final int[] filterH = new int[3];

    /**
     * The three distinct vertical Wiener taps.
     */
    public final int[] filterV = new int[3];

    /**
     * The two self-guided projection weights.
     */
    public final int[] sgrWeights = new int[2];

    /**
     * Creates a unit without restoration.
     */
    public RestorationUnit() {
    }
}
