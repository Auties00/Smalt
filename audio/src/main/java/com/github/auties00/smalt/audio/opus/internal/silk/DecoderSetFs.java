/* Ports libopus 1.5.2 (silk/decoder_set_fs.c). Copyright Skype Limited; BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.audio.opus.internal.silk;

import java.util.Arrays;

import static com.github.auties00.smalt.audio.opus.internal.silk.Define.LTP_MEM_LENGTH_MS;
import static com.github.auties00.smalt.audio.opus.internal.silk.Define.MAX_LPC_ORDER;
import static com.github.auties00.smalt.audio.opus.internal.silk.Define.MAX_NB_SUBFR;
import static com.github.auties00.smalt.audio.opus.internal.silk.Define.MIN_LPC_ORDER;
import static com.github.auties00.smalt.audio.opus.internal.silk.Define.SUB_FRAME_LENGTH_MS;
import static com.github.auties00.smalt.audio.opus.internal.silk.Define.TYPE_NO_VOICE_ACTIVITY;

/**
 * Configures a SILK decoder channel for an internal and an API sampling rate.
 */
public final class DecoderSetFs {
    private DecoderSetFs() {
        throw new AssertionError();
    }

    /**
     * Sets the internal and API sampling rates and the frame geometry ({@code silk_decoder_set_fs}).
     *
     * <p>The output resampler is reinitialized when either rate changes; a change of the internal
     * rate also resets the LPC order, codebooks and signal history.
     *
     * @param psDec     the channel state, whose {@code nb_subfr} is already set
     * @param fs_kHz    the internal rate in kHz: 8, 12 or 16
     * @param fs_API_Hz the API rate in Hz
     * @return zero on success, or a negative value when the resampler rejects the rates
     */
    public static int decoderSetFs(DecoderState psDec, int fs_kHz, int fs_API_Hz) {
        var ret = 0;
        psDec.subfr_length = SUB_FRAME_LENGTH_MS * fs_kHz;
        var frame_length = psDec.nb_subfr * psDec.subfr_length;
        if (psDec.fs_kHz != fs_kHz || psDec.fs_API_hz != fs_API_Hz) {
            ret += Resampler.resamplerInit(psDec.resampler_state, fs_kHz * 1000, fs_API_Hz, 0);
            psDec.fs_API_hz = fs_API_Hz;
        }
        if (psDec.fs_kHz != fs_kHz || frame_length != psDec.frame_length) {
            if (fs_kHz == 8) {
                if (psDec.nb_subfr == MAX_NB_SUBFR) {
                    psDec.pitch_contour_iCDF = Tables.silk_pitch_contour_NB_iCDF;
                } else {
                    psDec.pitch_contour_iCDF = Tables.silk_pitch_contour_10_ms_NB_iCDF;
                }
            } else {
                if (psDec.nb_subfr == MAX_NB_SUBFR) {
                    psDec.pitch_contour_iCDF = Tables.silk_pitch_contour_iCDF;
                } else {
                    psDec.pitch_contour_iCDF = Tables.silk_pitch_contour_10_ms_iCDF;
                }
            }
            if (psDec.fs_kHz != fs_kHz) {
                psDec.ltp_mem_length = LTP_MEM_LENGTH_MS * fs_kHz;
                if (fs_kHz == 8 || fs_kHz == 12) {
                    psDec.LPC_order = MIN_LPC_ORDER;
                    psDec.psNLSF_CB = Tables.silk_NLSF_CB_NB_MB;
                } else {
                    psDec.LPC_order = MAX_LPC_ORDER;
                    psDec.psNLSF_CB = Tables.silk_NLSF_CB_WB;
                }
                if (fs_kHz == 16) {
                    psDec.pitch_lag_low_bits_iCDF = Tables.silk_uniform8_iCDF;
                } else if (fs_kHz == 12) {
                    psDec.pitch_lag_low_bits_iCDF = Tables.silk_uniform6_iCDF;
                } else {
                    psDec.pitch_lag_low_bits_iCDF = Tables.silk_uniform4_iCDF;
                }
                psDec.first_frame_after_reset = 1;
                psDec.lagPrev = 100;
                psDec.LastGainIndex = 10;
                psDec.prevSignalType = TYPE_NO_VOICE_ACTIVITY;
                Arrays.fill(psDec.outBuf, (short) 0);
                Arrays.fill(psDec.sLPC_Q14_buf, 0);
            }
            psDec.fs_kHz = fs_kHz;
            psDec.frame_length = frame_length;
        }
        return ret;
    }
}
