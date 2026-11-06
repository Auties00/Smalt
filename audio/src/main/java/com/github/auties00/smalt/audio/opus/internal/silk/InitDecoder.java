/* Ports libopus 1.5.2 (silk/init_decoder.c). Copyright Skype Limited; BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.audio.opus.internal.silk;

import com.github.auties00.smalt.audio.opus.internal.dnn.Osce;

/**
 * Initializes and resets the state of one SILK decoder channel.
 *
 */
public final class InitDecoder {
    private InitDecoder() {
        throw new AssertionError();
    }

    /**
     * Resets a decoder channel ({@code silk_reset_decoder}).
     *
     * <p>Clears the state, deactivates NLSF interpolation for the next frame, sets the previous
     * gain to one, and resets the comfort noise generator and the packet loss concealment.
     *
     * @param psDec the channel state
     * @return zero
     */
    public static int resetDecoder(DecoderState psDec) {
        psDec.clear();
        psDec.first_frame_after_reset = 1;
        psDec.prev_gain_Q16 = 65536;
        Cng.cngReset(psDec);
        Plc.plcReset(psDec);
        if (psDec.osce != null) {
            psDec.osce.reset(Osce.METHOD_NOLACE);
        }
        return 0;
    }

    /**
     * Initializes a decoder channel ({@code silk_init_decoder}).
     *
     * @param psDec the channel state
     * @return zero
     */
    public static int initDecoder(DecoderState psDec) {
        psDec.clear();
        resetDecoder(psDec);
        return 0;
    }
}
