/* Ports libopus 1.5.2 (silk/structs.h). Copyright Skype Limited; BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.audio.opus.internal.silk;

import java.util.Arrays;

import static com.github.auties00.smalt.audio.opus.internal.silk.Define.VAD_N_BANDS;

/**
 * Holds the state of the voice activity detector.
 *
 * @implNote This implementation keeps the libopus field names.
 */
public final class VadState {
    /**
     * The analysis filter bank state of the 0-8 kHz split.
     */
    public final int[] AnaState = new int[2];

    /**
     * The analysis filter bank state of the 0-4 kHz split.
     */
    public final int[] AnaState1 = new int[2];

    /**
     * The analysis filter bank state of the 0-2 kHz split.
     */
    public final int[] AnaState2 = new int[2];

    /**
     * The subframe energies of each band.
     */
    public final int[] XnrgSubfr = new int[VAD_N_BANDS];

    /**
     * The smoothed energy ratio of each band in Q8.
     */
    public final int[] NrgRatioSmth_Q8 = new int[VAD_N_BANDS];

    /**
     * The state of the differentiator of the lowest band.
     */
    public short HPstate;

    /**
     * The noise energy level of each band.
     */
    public final int[] NL = new int[VAD_N_BANDS];

    /**
     * The inverse noise energy level of each band.
     */
    public final int[] inv_NL = new int[VAD_N_BANDS];

    /**
     * The noise level estimator bias of each band.
     */
    public final int[] NoiseLevelBias = new int[VAD_N_BANDS];

    /**
     * The frame counter used in the initial phase.
     */
    public int counter;

    /**
     * Creates a zeroed state.
     */
    public VadState() {
    }

    /**
     * Zeroes every field, like {@code silk_memset} of the structure.
     */
    public void clear() {
        Arrays.fill(AnaState, 0);
        Arrays.fill(AnaState1, 0);
        Arrays.fill(AnaState2, 0);
        Arrays.fill(XnrgSubfr, 0);
        Arrays.fill(NrgRatioSmth_Q8, 0);
        HPstate = 0;
        Arrays.fill(NL, 0);
        Arrays.fill(inv_NL, 0);
        Arrays.fill(NoiseLevelBias, 0);
        counter = 0;
    }
}
