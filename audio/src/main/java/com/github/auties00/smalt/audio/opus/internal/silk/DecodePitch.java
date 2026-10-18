/* Ports libopus 1.5.2 (silk/decode_pitch.c). Copyright Skype Limited; BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.audio.opus.internal.silk;

import static com.github.auties00.smalt.audio.opus.internal.silk.PitchEstTables.PE_MAX_LAG_MS;
import static com.github.auties00.smalt.audio.opus.internal.silk.PitchEstTables.PE_MAX_NB_SUBFR;
import static com.github.auties00.smalt.audio.opus.internal.silk.PitchEstTables.PE_MIN_LAG_MS;
import static com.github.auties00.smalt.audio.opus.internal.silk.SigProcFix.limit;

/**
 * Reconstructs the per-subframe pitch lags from a lag index and a contour index.
 */
public final class DecodePitch {
    private DecodePitch() {
        throw new AssertionError();
    }

    /**
     * Computes the pitch lag of each subframe ({@code silk_decode_pitch}).
     *
     * @param lagIndex     the lag index relative to the minimum lag
     * @param contourIndex the contour codebook index
     * @param pitch_lags   receives the pitch lag of each subframe, clamped to the valid range
     * @param Fs_kHz       the internal sampling frequency in kHz
     * @param nb_subfr     the number of subframes, 2 or 4
     */
    public static void decodePitch(int lagIndex, int contourIndex, int[] pitch_lags, int Fs_kHz, int nb_subfr) {
        byte[][] Lag_CB_ptr;
        if (Fs_kHz == 8) {
            if (nb_subfr == PE_MAX_NB_SUBFR) {
                Lag_CB_ptr = PitchEstTables.silk_CB_lags_stage2;
            } else {
                Lag_CB_ptr = PitchEstTables.silk_CB_lags_stage2_10_ms;
            }
        } else {
            if (nb_subfr == PE_MAX_NB_SUBFR) {
                Lag_CB_ptr = PitchEstTables.silk_CB_lags_stage3;
            } else {
                Lag_CB_ptr = PitchEstTables.silk_CB_lags_stage3_10_ms;
            }
        }
        var min_lag = PE_MIN_LAG_MS * Fs_kHz;
        var max_lag = PE_MAX_LAG_MS * Fs_kHz;
        var lag = min_lag + lagIndex;
        for (var k = 0; k < nb_subfr; k++) {
            pitch_lags[k] = lag + Lag_CB_ptr[k][contourIndex];
            pitch_lags[k] = limit(pitch_lags[k], min_lag, max_lag);
        }
    }
}
