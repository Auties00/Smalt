/* Ports libopus 1.5.2 (celt/celt.h). Copyright Xiph.Org Foundation, CSIRO and Jean-Marc Valin; BSD 3-Clause License.
   Modified for Smalt. */
package com.github.auties00.smalt.audio.opus.internal.celt;

/**
 * Holds the SILK frame parameters the CELT encoder uses in hybrid mode ({@code SILKInfo}).
 */
public final class SilkInfo {
    /**
     * The SILK signal type: 0 inactive, 1 unvoiced, 2 voiced.
     */
    public int signalType;

    /**
     * The SILK quantization offset.
     */
    public int offset;

    /**
     * Creates zeroed parameters.
     */
    public SilkInfo() {
    }

    /**
     * Copies other parameters into this one.
     *
     * @param o the parameters to copy
     */
    public void copyFrom(SilkInfo o) {
        signalType = o.signalType;
        offset = o.offset;
    }
}
