package com.github.auties00.smalt.audio.vorbis.internal;

/**
 * Holds the configuration of a mapping of type 0: the submaps with their floors and residues,
 * and the channel coupling steps.
 *
 * @implNote This implementation mirrors libvorbis's {@code vorbis_info_mapping0} (BSD-3).
 */
public final class Mapping0Info {
    /**
     * The number of submaps.
     */
    public int submaps;

    /**
     * The submap of each channel.
     */
    public final int[] channelMux = new int[256];

    /**
     * The floor of each submap.
     */
    public final int[] floorSubmap = new int[16];

    /**
     * The residue of each submap.
     */
    public final int[] residueSubmap = new int[16];

    /**
     * The number of coupling steps.
     */
    public int couplingSteps;

    /**
     * The magnitude channel of each coupling step.
     */
    public final int[] couplingMagnitude = new int[256];

    /**
     * The angle channel of each coupling step.
     */
    public final int[] couplingAngle = new int[256];

    /**
     * Creates an empty configuration.
     */
    public Mapping0Info() {
    }

    /**
     * Returns a copy of this configuration.
     *
     * @return the copy
     */
    public Mapping0Info copy() {
        var copy = new Mapping0Info();
        copy.submaps = submaps;
        System.arraycopy(channelMux, 0, copy.channelMux, 0, 256);
        System.arraycopy(floorSubmap, 0, copy.floorSubmap, 0, 16);
        System.arraycopy(residueSubmap, 0, copy.residueSubmap, 0, 16);
        copy.couplingSteps = couplingSteps;
        System.arraycopy(couplingMagnitude, 0, copy.couplingMagnitude, 0, 256);
        System.arraycopy(couplingAngle, 0, copy.couplingAngle, 0, 256);
        return copy;
    }
}
