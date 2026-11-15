/* Ports libopus 1.5.2 (silk/dec_API.c). Copyright Skype Limited; BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.audio.opus.internal.silk;

import com.github.auties00.smalt.audio.opus.internal.celt.RangeDecoder;
import com.github.auties00.smalt.audio.opus.internal.dnn.LpcnetPlc;
import com.github.auties00.smalt.audio.opus.internal.dnn.Osce;

import java.util.Arrays;

import static com.github.auties00.smalt.audio.opus.internal.silk.Define.CODE_CONDITIONALLY;
import static com.github.auties00.smalt.audio.opus.internal.silk.Define.CODE_INDEPENDENTLY;
import static com.github.auties00.smalt.audio.opus.internal.silk.Define.CODE_INDEPENDENTLY_NO_LTP_SCALING;
import static com.github.auties00.smalt.audio.opus.internal.silk.Define.DECODER_NUM_CHANNELS;
import static com.github.auties00.smalt.audio.opus.internal.silk.Define.FLAG_DECODE_LBRR;
import static com.github.auties00.smalt.audio.opus.internal.silk.Define.FLAG_DECODE_NORMAL;
import static com.github.auties00.smalt.audio.opus.internal.silk.Define.FLAG_PACKET_LOST;
import static com.github.auties00.smalt.audio.opus.internal.silk.Define.MAX_API_FS_KHZ;
import static com.github.auties00.smalt.audio.opus.internal.silk.Define.MAX_FRAME_LENGTH;
import static com.github.auties00.smalt.audio.opus.internal.silk.Define.TYPE_NO_VOICE_ACTIVITY;
import static com.github.auties00.smalt.audio.opus.internal.silk.Define.TYPE_VOICED;
import static com.github.auties00.smalt.audio.opus.internal.silk.SilkErrors.SILK_DEC_INVALID_FRAME_SIZE;
import static com.github.auties00.smalt.audio.opus.internal.silk.SilkErrors.SILK_DEC_INVALID_SAMPLING_FREQUENCY;
import static com.github.auties00.smalt.audio.opus.internal.silk.SilkErrors.SILK_NO_ERROR;

/**
 * Decodes SILK frames of a mono or stereo stream, including LBRR (in-band FEC) frames and the
 * concealment of lost frames, and resamples the result to the API rate.
 *
 * <p>The Opus layer calls {@link #decode} once per SILK frame (10 or 20 ms) of a packet. The
 * decoder never throws on any bitstream content: every decoded index is clamped by the codec, and
 * the controls, which come from the validated packet header, select the frame geometry.
 *
 * @implNote This implementation holds the temporary output buffers the
 * reference places on the stack, or inside the caller's output buffer, as scratch arrays.
 */
public final class SilkDecoder {
    /**
     * The state of each decoder channel ({@code channel_state}).
     */
    final DecoderState[] channel_state = {new DecoderState(), new DecoderState()};

    /**
     * The stereo decoder state ({@code sStereo}).
     */
    final StereoDecState sStereo = new StereoDecState();

    /**
     * The number of output channels of the previous call.
     */
    int nChannelsAPI;

    /**
     * The number of coded channels of the previous call.
     */
    int nChannelsInternal;

    /**
     * Whether only the mid channel was coded in the previous frame.
     */
    int prev_decode_only_middle;

    /**
     * The decoded frames of both channels at the internal rate, each with two samples of history
     * ({@code samplesOut1_tmp}).
     */
    private final short[] samplesOut1Tmp = new short[2 * (MAX_FRAME_LENGTH + 2)];

    /**
     * The resampled frame of one channel before interleaving ({@code samplesOut2_tmp}).
     */
    private final short[] samplesOut2Tmp = new short[MAX_API_FS_KHZ * 20];

    /**
     * The scratch pulses of skipped LBRR frames.
     */
    private final short[] lbrrPulses = new short[MAX_FRAME_LENGTH];

    /**
     * The mid/side predictors of the current frame.
     */
    private final int[] MS_pred_Q13 = new int[2];

    /**
     * Creates an initialized decoder ({@code silk_InitDecoder}).
     */
    public SilkDecoder() {
        initDecoder();
    }

    /**
     * Initializes every channel and the stereo state ({@code silk_InitDecoder}).
     *
     * @return {@link SilkErrors#SILK_NO_ERROR}
     */
    public int initDecoder() {
        var ret = SILK_NO_ERROR;
        for (var n = 0; n < DECODER_NUM_CHANNELS; n++) {
            ret = InitDecoder.initDecoder(channel_state[n]);
        }
        sStereo.clear();
        prev_decode_only_middle = 0;
        return ret;
    }

    /**
     * Resets every channel and the stereo state ({@code silk_ResetDecoder}).
     *
     * @return {@link SilkErrors#SILK_NO_ERROR}
     */
    public int resetDecoder() {
        var ret = SILK_NO_ERROR;
        for (var n = 0; n < DECODER_NUM_CHANNELS; n++) {
            ret = InitDecoder.resetDecoder(channel_state[n]);
        }
        sStereo.clear();
        prev_decode_only_middle = 0;
        return ret;
    }

    /**
     * Decodes one SILK frame ({@code silk_Decode}).
     *
     * <p>The output is interleaved when {@code decControl.nChannelsAPI} is 2 and holds
     * {@code nSamplesOut[0]} samples per channel at {@code decControl.API_sampleRate}.
     *
     * <p>The caller must drive the decoder like {@code opus_decode_frame}: set
     * {@code newPacketFlag} on the first call of every packet and make exactly
     * {@code payloadSize_ms / 20} calls (one for 10 ms) per packet; pass valid controls
     * ({@code nChannelsAPI} and {@code nChannelsInternal} 1 or 2, {@code internalSampleRate}
     * 8000, 12000 or 16000, {@code payloadSize_ms} 10, 20, 40 or 60, {@code API_sampleRate} one of
     * 8000, 12000, 16000, 24000 or 48000); and never request concealment
     * ({@link Define#FLAG_PACKET_LOST}) before the first packet was decoded, where the Opus layer
     * outputs silence instead. Under those preconditions no bitstream content can make this method
     * throw.
     *
     * @param decControl    the controls; {@code prevPitchLag} is written back
     * @param lostFlag      {@link Define#FLAG_DECODE_NORMAL}, {@link Define#FLAG_PACKET_LOST}
     *                      or {@link Define#FLAG_DECODE_LBRR}
     * @param newPacketFlag nonzero for the first call of a packet
     * @param psRangeDec    the range decoder positioned on the SILK data
     * @param samplesOut    receives the decoded samples
     * @param samplesOutOff the index of the first output sample
     * @param nSamplesOut   receives the number of samples per channel at index 0
     * @return {@link SilkErrors#SILK_NO_ERROR} or a negative SILK error code
     */
    public int decode(SilkDecControl decControl, int lostFlag, int newPacketFlag, RangeDecoder psRangeDec,
                      short[] samplesOut, int samplesOutOff, int[] nSamplesOut) {
        return decode(decControl, lostFlag, newPacketFlag, psRangeDec, samplesOut, samplesOutOff, nSamplesOut, null);
    }

    /**
     * Puts both channels in deep PLC mode, where they behave as the reference built with the deep PLC and OSCE;
     * each channel gets its own speech enhancer. Call it once, before the first frame.
     */
    public void enableDnn() {
        for (var state : channel_state) {
            state.dnn = true;
            if (state.osce == null) {
                state.osce = new Osce();
            }
        }
    }

    /**
     * Decodes one SILK frame with the deep PLC available ({@code silk_Decode}), as
     * {@link #decode(SilkDecControl, int, int, RangeDecoder, short[], int, int[])} does.
     *
     * @param decControl    the controls; {@code prevPitchLag} is written back
     * @param lostFlag      {@link Define#FLAG_DECODE_NORMAL}, {@link Define#FLAG_PACKET_LOST}
     *                      or {@link Define#FLAG_DECODE_LBRR}
     * @param newPacketFlag nonzero for the first call of a packet
     * @param psRangeDec    the range decoder positioned on the SILK data
     * @param samplesOut    receives the decoded samples
     * @param samplesOutOff the index of the first output sample
     * @param nSamplesOut   receives the number of samples per channel at index 0
     * @param lpcnet        the deep PLC of the first channel, or {@code null}
     * @return {@link SilkErrors#SILK_NO_ERROR} or a negative SILK error code
     */
    public int decode(SilkDecControl decControl, int lostFlag, int newPacketFlag, RangeDecoder psRangeDec,
                      short[] samplesOut, int samplesOutOff, int[] nSamplesOut, LpcnetPlc lpcnet) {
        var decode_only_middle = 0;
        var ret = SILK_NO_ERROR;
        var MS_pred_Q13 = this.MS_pred_Q13;
        MS_pred_Q13[0] = 0;
        MS_pred_Q13[1] = 0;
        var channel_state = this.channel_state;
        if (newPacketFlag != 0) {
            for (var n = 0; n < decControl.nChannelsInternal; n++) {
                channel_state[n].nFramesDecoded = 0;
            }
        }
        if (decControl.nChannelsInternal > nChannelsInternal) {
            ret += InitDecoder.initDecoder(channel_state[1]);
        }
        var stereo_to_mono = decControl.nChannelsInternal == 1 && nChannelsInternal == 2
                && decControl.internalSampleRate == 1000 * channel_state[0].fs_kHz;
        if (channel_state[0].nFramesDecoded == 0) {
            for (var n = 0; n < decControl.nChannelsInternal; n++) {
                var state = channel_state[n];
                switch (decControl.payloadSize_ms) {
                    case 0, 10 -> {
                        state.nFramesPerPacket = 1;
                        state.nb_subfr = 2;
                    }
                    case 20 -> {
                        state.nFramesPerPacket = 1;
                        state.nb_subfr = 4;
                    }
                    case 40 -> {
                        state.nFramesPerPacket = 2;
                        state.nb_subfr = 4;
                    }
                    case 60 -> {
                        state.nFramesPerPacket = 3;
                        state.nb_subfr = 4;
                    }
                    default -> {
                        return SILK_DEC_INVALID_FRAME_SIZE;
                    }
                }
                var fs_kHz_dec = (decControl.internalSampleRate >> 10) + 1;
                if (fs_kHz_dec != 8 && fs_kHz_dec != 12 && fs_kHz_dec != 16) {
                    return SILK_DEC_INVALID_SAMPLING_FREQUENCY;
                }
                ret += DecoderSetFs.decoderSetFs(state, fs_kHz_dec, decControl.API_sampleRate);
            }
        }
        if (decControl.nChannelsAPI == 2 && decControl.nChannelsInternal == 2
                && (nChannelsAPI == 1 || nChannelsInternal == 1)) {
            Arrays.fill(sStereo.pred_prev_Q13, (short) 0);
            Arrays.fill(sStereo.sSide, (short) 0);
            channel_state[1].resampler_state.copyFrom(channel_state[0].resampler_state);
        }
        nChannelsAPI = decControl.nChannelsAPI;
        nChannelsInternal = decControl.nChannelsInternal;
        if (decControl.API_sampleRate > MAX_API_FS_KHZ * 1000 || decControl.API_sampleRate < 8000) {
            return SILK_DEC_INVALID_SAMPLING_FREQUENCY;
        }
        if (lostFlag != FLAG_PACKET_LOST && channel_state[0].nFramesDecoded == 0) {
            for (var n = 0; n < decControl.nChannelsInternal; n++) {
                for (var i = 0; i < channel_state[n].nFramesPerPacket; i++) {
                    channel_state[n].VAD_flags[i] = psRangeDec.decBitLogp(1);
                }
                channel_state[n].LBRR_flag = psRangeDec.decBitLogp(1);
            }
            for (var n = 0; n < decControl.nChannelsInternal; n++) {
                var state = channel_state[n];
                Arrays.fill(state.LBRR_flags, 0);
                if (state.LBRR_flag != 0) {
                    if (state.nFramesPerPacket == 1) {
                        state.LBRR_flags[0] = 1;
                    } else {
                        var LBRR_symbol = psRangeDec.decIcdf(Tables.silk_LBRR_flags_iCDF_ptr[state.nFramesPerPacket - 2], 8)
                                + 1;
                        for (var i = 0; i < state.nFramesPerPacket; i++) {
                            state.LBRR_flags[i] = (LBRR_symbol >> i) & 1;
                        }
                    }
                }
            }
            if (lostFlag == FLAG_DECODE_NORMAL) {
                for (var i = 0; i < channel_state[0].nFramesPerPacket; i++) {
                    for (var n = 0; n < decControl.nChannelsInternal; n++) {
                        if (channel_state[n].LBRR_flags[i] != 0) {
                            if (decControl.nChannelsInternal == 2 && n == 0) {
                                StereoDecodePred.stereoDecodePred(psRangeDec, MS_pred_Q13);
                                if (channel_state[1].LBRR_flags[i] == 0) {
                                    decode_only_middle = StereoDecodePred.stereoDecodeMidOnly(psRangeDec);
                                }
                            }
                            int condCoding;
                            if (i > 0 && channel_state[n].LBRR_flags[i - 1] != 0) {
                                condCoding = CODE_CONDITIONALLY;
                            } else {
                                condCoding = CODE_INDEPENDENTLY;
                            }
                            DecodeIndices.decodeIndices(channel_state[n], psRangeDec, i, 1, condCoding);
                            var state = channel_state[n];
                            DecodePulses.decodePulses(psRangeDec, lbrrPulses, state.indices.signalType,
                                    state.indices.quantOffsetType, state.frame_length, state.scratchSumPulses,
                                    state.scratchNLshifts, state.scratchSignIcdf);
                        }
                    }
                }
            }
        }
        if (decControl.nChannelsInternal == 2) {
            if (lostFlag == FLAG_DECODE_NORMAL
                    || (lostFlag == FLAG_DECODE_LBRR
                    && channel_state[0].LBRR_flags[channel_state[0].nFramesDecoded] == 1)) {
                StereoDecodePred.stereoDecodePred(psRangeDec, MS_pred_Q13);
                if ((lostFlag == FLAG_DECODE_NORMAL
                        && channel_state[1].VAD_flags[channel_state[0].nFramesDecoded] == 0)
                        || (lostFlag == FLAG_DECODE_LBRR
                        && channel_state[1].LBRR_flags[channel_state[0].nFramesDecoded] == 0)) {
                    decode_only_middle = StereoDecodePred.stereoDecodeMidOnly(psRangeDec);
                } else {
                    decode_only_middle = 0;
                }
            } else {
                for (var n = 0; n < 2; n++) {
                    MS_pred_Q13[n] = sStereo.pred_prev_Q13[n];
                }
            }
        }
        if (decControl.nChannelsInternal == 2 && decode_only_middle == 0 && prev_decode_only_middle == 1) {
            var side = channel_state[1];
            Arrays.fill(side.outBuf, (short) 0);
            Arrays.fill(side.sLPC_Q14_buf, 0);
            side.lagPrev = 100;
            side.LastGainIndex = 10;
            side.prevSignalType = TYPE_NO_VOICE_ACTIVITY;
            side.first_frame_after_reset = 1;
        }
        var samplesOut1 = samplesOut1Tmp;
        var tmp0 = 0;
        var tmp1 = channel_state[0].frame_length + 2;
        int has_side;
        if (lostFlag == FLAG_DECODE_NORMAL) {
            has_side = decode_only_middle == 0 ? 1 : 0;
        } else {
            has_side = prev_decode_only_middle == 0
                    || (decControl.nChannelsInternal == 2 && lostFlag == FLAG_DECODE_LBRR
                    && channel_state[1].LBRR_flags[channel_state[1].nFramesDecoded] == 1) ? 1 : 0;
        }
        channel_state[0].sPLC.enable_deep_plc = decControl.enable_deep_plc;
        var nSamplesOutDec = 0;
        for (var n = 0; n < decControl.nChannelsInternal; n++) {
            var tmpOff = n == 0 ? tmp0 : tmp1;
            if (n == 0 || has_side != 0) {
                var FrameIndex = channel_state[0].nFramesDecoded - n;
                int condCoding;
                if (FrameIndex <= 0) {
                    condCoding = CODE_INDEPENDENTLY;
                } else if (lostFlag == FLAG_DECODE_LBRR) {
                    condCoding = channel_state[n].LBRR_flags[FrameIndex - 1] != 0 ? CODE_CONDITIONALLY
                            : CODE_INDEPENDENTLY;
                } else if (n > 0 && prev_decode_only_middle != 0) {
                    condCoding = CODE_INDEPENDENTLY_NO_LTP_SCALING;
                } else {
                    condCoding = CODE_CONDITIONALLY;
                }
                var osce = channel_state[n].osce;
                if (osce != null && osce.method() != decControl.osce_method) {
                    osce.reset(decControl.osce_method);
                }
                nSamplesOutDec = DecodeFrame.decodeFrame(channel_state[n], psRangeDec, samplesOut1, tmpOff + 2,
                        lostFlag, condCoding, n == 0 && channel_state[n].dnn ? lpcnet : null);
            } else {
                Arrays.fill(samplesOut1, tmpOff + 2, tmpOff + 2 + nSamplesOutDec, (short) 0);
            }
            channel_state[n].nFramesDecoded++;
        }
        if (decControl.nChannelsAPI == 2 && decControl.nChannelsInternal == 2) {
            StereoMsToLr.stereoMsToLr(sStereo, samplesOut1, tmp0, samplesOut1, tmp1, MS_pred_Q13,
                    channel_state[0].fs_kHz, nSamplesOutDec);
        } else {
            samplesOut1[tmp0] = sStereo.sMid[0];
            samplesOut1[tmp0 + 1] = sStereo.sMid[1];
            sStereo.sMid[0] = samplesOut1[tmp0 + nSamplesOutDec];
            sStereo.sMid[1] = samplesOut1[tmp0 + nSamplesOutDec + 1];
        }
        var outCount = nSamplesOutDec * decControl.API_sampleRate / (channel_state[0].fs_kHz * 1000);
        nSamplesOut[0] = outCount;
        var resample_out = decControl.nChannelsAPI == 2 ? samplesOut2Tmp : samplesOut;
        var resample_outOff = decControl.nChannelsAPI == 2 ? 0 : samplesOutOff;
        for (var n = 0; n < Math.min(decControl.nChannelsAPI, decControl.nChannelsInternal); n++) {
            var tmpOff = n == 0 ? tmp0 : tmp1;
            ret += Resampler.resampler(channel_state[n].resampler_state, resample_out, resample_outOff, samplesOut1,
                    tmpOff + 1, nSamplesOutDec);
            if (decControl.nChannelsAPI == 2) {
                for (var i = 0; i < outCount; i++) {
                    samplesOut[samplesOutOff + n + 2 * i] = resample_out[resample_outOff + i];
                }
            }
        }
        if (decControl.nChannelsAPI == 2 && decControl.nChannelsInternal == 1) {
            if (stereo_to_mono) {
                ret += Resampler.resampler(channel_state[1].resampler_state, resample_out, resample_outOff,
                        samplesOut1, tmp0 + 1, nSamplesOutDec);
                for (var i = 0; i < outCount; i++) {
                    samplesOut[samplesOutOff + 1 + 2 * i] = resample_out[resample_outOff + i];
                }
            } else {
                for (var i = 0; i < outCount; i++) {
                    samplesOut[samplesOutOff + 1 + 2 * i] = samplesOut[samplesOutOff + 2 * i];
                }
            }
        }
        if (channel_state[0].prevSignalType == TYPE_VOICED) {
            var mult = switch ((channel_state[0].fs_kHz - 8) >> 2) {
                case 0 -> 6;
                case 1 -> 4;
                default -> 3;
            };
            decControl.prevPitchLag = channel_state[0].lagPrev * mult;
        } else {
            decControl.prevPitchLag = 0;
        }
        if (lostFlag == FLAG_PACKET_LOST) {
            for (var i = 0; i < nChannelsInternal; i++) {
                channel_state[i].LastGainIndex = 10;
            }
        } else {
            prev_decode_only_middle = decode_only_middle;
        }
        return ret;
    }
}
