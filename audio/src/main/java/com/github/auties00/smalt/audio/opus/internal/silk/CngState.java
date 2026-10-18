/* Ports libopus 1.5.2 (silk/structs.h). Copyright Skype Limited; BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.audio.opus.internal.silk;

import java.util.Arrays;

import static com.github.auties00.smalt.audio.opus.internal.silk.Define.MAX_FRAME_LENGTH;
import static com.github.auties00.smalt.audio.opus.internal.silk.Define.MAX_LPC_ORDER;

/**
 * Holds the state of the SILK comfort noise generator.
 *
 * @implNote This implementation keeps the libopus field names.
 */
public final class CngState {
    /**
     * The buffer of past excitation, in Q14.
     */
    public final int[] CNG_exc_buf_Q14 = new int[MAX_FRAME_LENGTH];

    /**
     * The smoothed NLSF vector, in Q15.
     */
    public final short[] CNG_smth_NLSF_Q15 = new short[MAX_LPC_ORDER];

    /**
     * The state of the synthesis filter.
     */
    public final int[] CNG_synth_state = new int[MAX_LPC_ORDER];

    /**
     * The smoothed gain, in Q16.
     */
    public int CNG_smth_Gain_Q16;

    /**
     * The seed of the noise generator.
     */
    public int rand_seed;

    /**
     * The sampling frequency in kHz the state was computed for.
     */
    public int fs_kHz;

    /**
     * Creates a zeroed state.
     */
    public CngState() {
    }

    /**
     * Zeroes every field, like {@code silk_memset} of the structure.
     */
    public void clear() {
        Arrays.fill(CNG_exc_buf_Q14, 0);
        Arrays.fill(CNG_smth_NLSF_Q15, (short) 0);
        Arrays.fill(CNG_synth_state, 0);
        CNG_smth_Gain_Q16 = 0;
        rand_seed = 0;
        fs_kHz = 0;
    }
}
