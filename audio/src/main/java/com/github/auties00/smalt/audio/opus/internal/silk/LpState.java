/* Ports libopus 1.5.2 (silk/structs.h). Copyright Skype Limited; BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.audio.opus.internal.silk;

/**
 * Holds the state of the variable cutoff low-pass filter used for bandwidth transitions.
 *
 * @implNote This implementation keeps the libopus field names.
 */
public final class LpState {
    /**
     * The low-pass filter state.
     */
    public final int[] In_LP_State = new int[2];

    /**
     * The transition counter, mapped to a cutoff frequency.
     */
    public int transition_frame_no;

    /**
     * The operating mode: negative to switch down, positive to switch up, zero to do nothing.
     */
    public int mode;

    /**
     * The last sampling rate before a bandwidth switching reset, or zero.
     */
    public int saved_fs_kHz;

    /**
     * The interpolated feed-forward coefficients of the current transition frame, in Q28.
     */
    final int[] B_Q28 = new int[Define.TRANSITION_NB];

    /**
     * The interpolated feedback coefficients of the current transition frame, in Q28.
     */
    final int[] A_Q28 = new int[Define.TRANSITION_NA];

    /**
     * Creates a zeroed state.
     */
    public LpState() {
    }

    /**
     * Zeroes every field, like {@code silk_memset} of the structure.
     */
    public void clear() {
        In_LP_State[0] = 0;
        In_LP_State[1] = 0;
        transition_frame_no = 0;
        mode = 0;
        saved_fs_kHz = 0;
    }
}
