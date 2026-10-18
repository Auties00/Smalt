/* Ports libopus 1.5.2 (silk/float/structs_FLP.h). Copyright Skype Limited; BSD 3-Clause License. Modified for Smalt.
   */
package com.github.auties00.smalt.audio.opus.internal.silk;

/**
 * Holds the noise-shaping analysis state of a SILK encoder channel ({@code silk_shape_state_FLP}).
 */
public final class ShapeStateFlp {
    /**
     * The gain index of the last subframe of the previous frame.
     */
    public byte LastGainIndex;

    /**
     * The smoothed harmonic shaping gain.
     */
    public float HarmShapeGain_smth;

    /**
     * The smoothed spectral tilt.
     */
    public float Tilt_smth;

    /**
     * Creates a zeroed state.
     */
    public ShapeStateFlp() {
    }

    /**
     * Resets every field to zero, as {@code memset} does in the reference.
     */
    public void clear() {
        LastGainIndex = 0;
        HarmShapeGain_smth = 0;
        Tilt_smth = 0;
    }
}
