/* Ports libopus 1.5.2 (silk/decode_frame.c). Copyright Skype Limited; BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.audio.opus.internal.silk;

import com.github.auties00.smalt.audio.opus.internal.celt.RangeDecoder;

import static com.github.auties00.smalt.audio.opus.internal.silk.Define.FLAG_DECODE_LBRR;
import static com.github.auties00.smalt.audio.opus.internal.silk.Define.FLAG_DECODE_NORMAL;

/**
 * Decodes one SILK frame of one channel, or conceals it when it is lost.
 *
 * @implNote This implementation omits the OSCE and deep PLC extensions.
 */
public final class DecodeFrame {
    private DecodeFrame() {
        throw new AssertionError();
    }

    /**
     * Decodes or conceals a frame and runs the comfort noise and loss smoothing
     * ({@code silk_decode_frame}).
     *
     * @param psDec      the channel state
     * @param psRangeDec the range decoder
     * @param pOut       receives the frame
     * @param pOutOff    the index of the first output sample
     * @param lostFlag   {@link Define#FLAG_DECODE_NORMAL}, {@link Define#FLAG_PACKET_LOST} or
     *                   {@link Define#FLAG_DECODE_LBRR}
     * @param condCoding the conditional coding type
     * @return the number of samples written, the frame length
     */
    public static int decodeFrame(DecoderState psDec, RangeDecoder psRangeDec, short[] pOut, int pOutOff, int lostFlag,
                                  int condCoding) {
        var psDecCtrl = psDec.scratchControl;
        var L = psDec.frame_length;
        psDecCtrl.LTP_scale_Q14 = 0;
        if (lostFlag == FLAG_DECODE_NORMAL
                || (lostFlag == FLAG_DECODE_LBRR && psDec.LBRR_flags[psDec.nFramesDecoded] == 1)) {
            var pulses = psDec.scratchPulses;
            DecodeIndices.decodeIndices(psDec, psRangeDec, psDec.nFramesDecoded, lostFlag, condCoding);
            DecodePulses.decodePulses(psRangeDec, pulses, psDec.indices.signalType, psDec.indices.quantOffsetType,
                    psDec.frame_length, psDec.scratchSumPulses, psDec.scratchNLshifts, psDec.scratchSignIcdf);
            DecodeParameters.decodeParameters(psDec, psDecCtrl, condCoding);
            DecodeCore.decodeCore(psDec, psDecCtrl, pOut, pOutOff, pulses);
            var mv_len = psDec.ltp_mem_length - psDec.frame_length;
            System.arraycopy(psDec.outBuf, psDec.frame_length, psDec.outBuf, 0, mv_len);
            System.arraycopy(pOut, pOutOff, psDec.outBuf, mv_len, psDec.frame_length);
            Plc.plc(psDec, psDecCtrl, pOut, pOutOff, 0);
            psDec.lossCnt = 0;
            psDec.prevSignalType = psDec.indices.signalType;
            psDec.first_frame_after_reset = 0;
        } else {
            Plc.plc(psDec, psDecCtrl, pOut, pOutOff, 1);
            var mv_len = psDec.ltp_mem_length - psDec.frame_length;
            System.arraycopy(psDec.outBuf, psDec.frame_length, psDec.outBuf, 0, mv_len);
            System.arraycopy(pOut, pOutOff, psDec.outBuf, mv_len, psDec.frame_length);
        }
        Cng.cng(psDec, psDecCtrl, pOut, pOutOff, L);
        Plc.plcGlueFrames(psDec, pOut, pOutOff, L);
        psDec.lagPrev = psDecCtrl.pitchL[psDec.nb_subfr - 1];
        return L;
    }
}
