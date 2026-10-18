/* Ports libopus 1.5.2 (silk/structs.h). Copyright Skype Limited; BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.audio.opus.internal.silk;

import java.util.Arrays;

import static com.github.auties00.smalt.audio.opus.internal.silk.Define.MAX_FRAMES_PER_PACKET;

/**
 * Holds the state of the stereo encoder: predictors, filter memories and the width smoother.
 *
 * @implNote This implementation keeps the libopus field names.
 */
public final class StereoEncState {
    /**
     * The previous predictors in Q13.
     */
    public final short[] pred_prev_Q13 = new short[2];

    /**
     * The mid signal memory.
     */
    public final short[] sMid = new short[2];

    /**
     * The side signal memory.
     */
    public final short[] sSide = new short[2];

    /**
     * The smoothed mid and residual amplitudes.
     */
    public final int[] mid_side_amp_Q0 = new int[4];

    /**
     * The smoothed stereo width in Q14.
     */
    public short smth_width_Q14;

    /**
     * The previous stereo width in Q14.
     */
    public short width_prev_Q14;

    /**
     * The length of the current run of silent side frames.
     */
    public short silent_side_len;

    /**
     * The quantization indices of the predictors, per frame of the packet, as
     * {@code [frame][2][3]}.
     */
    public final byte[][][] predIx = new byte[MAX_FRAMES_PER_PACKET][2][3];

    /**
     * The mid-only flag of each frame of the packet.
     */
    public final byte[] mid_only_flags = new byte[MAX_FRAMES_PER_PACKET];

    /**
     * Creates a zeroed state.
     */
    public StereoEncState() {
    }

    /**
     * Zeroes every field, like {@code silk_memset} of the structure.
     */
    public void clear() {
        Arrays.fill(pred_prev_Q13, (short) 0);
        Arrays.fill(sMid, (short) 0);
        Arrays.fill(sSide, (short) 0);
        Arrays.fill(mid_side_amp_Q0, 0);
        smth_width_Q14 = 0;
        width_prev_Q14 = 0;
        silent_side_len = 0;
        for (var frame : predIx) {
            for (var row : frame) {
                Arrays.fill(row, (byte) 0);
            }
        }
        Arrays.fill(mid_only_flags, (byte) 0);
    }
}
