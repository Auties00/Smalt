/* Ports libopus 1.5.2 (silk/structs.h). Copyright Skype Limited; BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.audio.opus.internal.silk;

import java.util.Arrays;

import static com.github.auties00.smalt.audio.opus.internal.silk.Define.LTP_ORDER;
import static com.github.auties00.smalt.audio.opus.internal.silk.Define.MAX_LPC_ORDER;

/**
 * Holds the state of the SILK packet loss concealment.
 *
 * @implNote This implementation keeps the libopus field names.
 */
public final class PlcState {
    /**
     * The pitch lag used for voiced concealment, in Q8.
     */
    public int pitchL_Q8;

    /**
     * The LTP coefficients used for voiced concealment, in Q14.
     */
    public final short[] LTPCoef_Q14 = new short[LTP_ORDER];

    /**
     * The LPC coefficients of the last good frame, in Q12.
     */
    public final short[] prevLPC_Q12 = new short[MAX_LPC_ORDER];

    /**
     * Whether the previous frame was lost.
     */
    public int last_frame_lost;

    /**
     * The seed of the unvoiced excitation generator.
     */
    public int rand_seed;

    /**
     * The scaling of the unvoiced random signal, in Q14.
     */
    public short randScale_Q14;

    /**
     * The energy of the concealed signal.
     */
    public int conc_energy;

    /**
     * The shift of {@link #conc_energy}.
     */
    public int conc_energy_shift;

    /**
     * The LTP scaling of the last good frame, in Q14.
     */
    public short prevLTP_scale_Q14;

    /**
     * The gains of the last two subframes of the last good frame, in Q16.
     */
    public final int[] prevGain_Q16 = new int[2];

    /**
     * The internal sampling frequency in kHz.
     */
    public int fs_kHz;

    /**
     * The number of subframes per frame.
     */
    public int nb_subfr;

    /**
     * The subframe length in samples.
     */
    public int subfr_length;

    /**
     * Whether the deep PLC conceals lost frames on its own.
     */
    public boolean enable_deep_plc;

    /**
     * Creates a zeroed state.
     */
    public PlcState() {
    }

    /**
     * Zeroes every field, like {@code silk_memset} of the structure.
     */
    public void clear() {
        pitchL_Q8 = 0;
        Arrays.fill(LTPCoef_Q14, (short) 0);
        Arrays.fill(prevLPC_Q12, (short) 0);
        last_frame_lost = 0;
        rand_seed = 0;
        randScale_Q14 = 0;
        conc_energy = 0;
        conc_energy_shift = 0;
        prevLTP_scale_Q14 = 0;
        Arrays.fill(prevGain_Q16, 0);
        fs_kHz = 0;
        nb_subfr = 0;
        subfr_length = 0;
        enable_deep_plc = false;
    }
}
