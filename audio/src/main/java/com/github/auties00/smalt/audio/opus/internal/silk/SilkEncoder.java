/* Ports libopus 1.5.2 (silk/enc_API.c, silk/float/structs_FLP.h). Copyright Skype Limited; BSD 3-Clause License.
   Modified for Smalt. */
package com.github.auties00.smalt.audio.opus.internal.silk;

import com.github.auties00.smalt.audio.opus.internal.celt.RangeEncoder;

import java.util.Arrays;

import static com.github.auties00.smalt.audio.opus.internal.silk.Define.*;
import static com.github.auties00.smalt.audio.opus.internal.silk.SigProcFix.*;
import static com.github.auties00.smalt.audio.opus.internal.silk.SilkErrors.*;
import static com.github.auties00.smalt.audio.opus.internal.silk.TuningParameters.*;

/**
 * Encodes 16-bit PCM into SILK frames: the SILK layer of the Opus encoder ({@code silk_encoder}).
 *
 * <p>Input is buffered and resampled to the internal rate, each channel pair is converted to
 * adaptive mid/side, and every complete frame is analyzed, quantized and range coded, including
 * the in-band FEC (LBRR) of the previous packet and the VAD/LBRR flags patched in at the start of
 * the payload. The encoder is not thread-safe; one instance encodes one stream.
 *
 * @implNote This implementation omits the Deep Redundancy (DRED) extension.
 */
public final class SilkEncoder {
    /**
     * The largest number of input samples per channel of one call: 60 ms at 48 kHz.
     */
    private static final int MAX_INPUT_SAMPLES = 60 * MAX_API_FS_KHZ;

    /**
     * The per-channel encoder states.
     */
    public final EncoderStateFlp[] state_Fxx = {new EncoderStateFlp(), new EncoderStateFlp()};

    /**
     * The stereo mid/side state.
     */
    public final StereoEncState sStereo = new StereoEncState();

    /**
     * The moving average of the bits spent on LBRR.
     */
    public int nBitsUsedLBRR;

    /**
     * The number of bits spent above the target, decaying over time.
     */
    public int nBitsExceeded;

    /**
     * The number of channels of the API input.
     */
    public int nChannelsAPI;

    /**
     * The number of channels coded internally.
     */
    public int nChannelsInternal;

    /**
     * The number of channels coded internally by the previous call.
     */
    public int nPrevChannelsInternal;

    /**
     * The time since switching the internal bandwidth was last allowed, in milliseconds.
     */
    public int timeSinceSwitchAllowed_ms;

    /**
     * Whether switching the internal bandwidth is allowed.
     */
    public int allowBandwidthSwitch;

    /**
     * Whether only the mid channel was coded in the previous frame.
     */
    public int prev_decode_only_middle;

    /**
     * The deinterleaved or downmixed input of one resampler call ({@code buf}).
     */
    private final short[] buf = new short[MAX_INPUT_SAMPLES];

    /**
     * The mid/side conversion scratch.
     */
    private final StereoEncode stereoEncode = new StereoEncode();

    /**
     * The target bitrates of the mid and side channels ({@code MStargetRates_bps}).
     */
    private final int[] MStargetRates_bps = new int[2];

    /**
     * The inverse CDF of the placeholder VAD and FEC flags.
     */
    private final short[] iCDF = new short[2];

    /**
     * The payload size output of one frame.
     */
    private final int[] frameBytes = new int[1];

    /**
     * Creates an encoder; call {@link #initEncoder(SilkEncControl)} before encoding.
     */
    public SilkEncoder() {
    }

    /**
     * Resets the encoder and reports its default configuration ({@code silk_InitEncoder}).
     *
     * @param encStatus receives the encoder status
     * @return {@link SilkErrors#SILK_NO_ERROR} or an error code
     */
    public int initEncoder(SilkEncControl encStatus) {
        int n, ret = SILK_NO_ERROR;

        for (n = 0; n < ENCODER_NUM_CHANNELS; n++) {
            state_Fxx[n].clear();
        }
        sStereo.clear();
        nBitsUsedLBRR = 0;
        nBitsExceeded = 0;
        nChannelsAPI = 0;
        nChannelsInternal = 0;
        nPrevChannelsInternal = 0;
        timeSinceSwitchAllowed_ms = 0;
        allowBandwidthSwitch = 0;
        prev_decode_only_middle = 0;

        for (n = 0; n < ENCODER_NUM_CHANNELS; n++) {
            ret += ControlCodec.initEncoder(state_Fxx[n]);
        }

        nChannelsAPI = 1;
        nChannelsInternal = 1;

        ret += queryEncoder(encStatus);

        return ret;
    }

    /**
     * Reads the encoder status into a control structure ({@code silk_QueryEncoder}).
     *
     * @param encStatus receives the status
     * @return {@link SilkErrors#SILK_NO_ERROR}
     */
    private int queryEncoder(SilkEncControl encStatus) {
        var sCmn = state_Fxx[0].sCmn;
        encStatus.nChannelsAPI = nChannelsAPI;
        encStatus.nChannelsInternal = nChannelsInternal;
        encStatus.API_sampleRate = sCmn.API_fs_Hz;
        encStatus.maxInternalSampleRate = sCmn.maxInternal_fs_Hz;
        encStatus.minInternalSampleRate = sCmn.minInternal_fs_Hz;
        encStatus.desiredInternalSampleRate = sCmn.desiredInternal_fs_Hz;
        encStatus.payloadSize_ms = sCmn.PacketSize_ms;
        encStatus.bitRate = sCmn.TargetRate_bps;
        encStatus.packetLossPercentage = sCmn.PacketLoss_perc;
        encStatus.complexity = sCmn.Complexity;
        encStatus.useInBandFEC = sCmn.useInBandFEC;
        encStatus.useDTX = sCmn.useDTX;
        encStatus.useCBR = sCmn.useCBR;
        encStatus.internalSampleRate = smulbb(sCmn.fs_kHz, 1000);
        encStatus.allowBandwidthSwitch = sCmn.allow_bandwidth_switch;
        encStatus.inWBmodeWithoutVariableLP = sCmn.fs_kHz == 16 && sCmn.sLP.mode == 0 ? 1 : 0;
        return SILK_NO_ERROR;
    }

    /**
     * Copies a variable low-pass filter state.
     *
     * @param dst the destination
     * @param src the source
     */
    private static void copyLpState(LpState dst, LpState src) {
        dst.In_LP_State[0] = src.In_LP_State[0];
        dst.In_LP_State[1] = src.In_LP_State[1];
        dst.transition_frame_no = src.transition_frame_no;
        dst.mode = src.mode;
        dst.saved_fs_kHz = src.saved_fs_kHz;
    }

    /**
     * Encodes a block of input ({@code silk_Encode}).
     *
     * <p>The input holds {@code nSamplesIn} samples per channel, interleaved when
     * {@code encControl.nChannelsAPI} is two, and must be a multiple of 10 ms that does not exceed
     * one packet. When {@code prefillFlag} is set the input must be exactly 10 ms, the encoder is
     * reset and primed without producing output, and {@code psRangeEnc} may be {@code null}.
     *
     * @param encControl   the control structure; status fields are written back
     * @param samplesIn    the input samples
     * @param samplesInOff the index of the first input sample
     * @param nSamplesIn   the number of samples per channel
     * @param psRangeEnc   the range encoder receiving the payload
     * @param nBytesOut    receives the number of payload bytes at index 0, zero until a packet is
     *                     complete or when the packet is discontinuous (DTX)
     * @param prefillFlag  {@code 1} to prefill the buffers, {@code 2} to prefill while keeping the
     *                     variable low-pass state, {@code 0} to encode
     * @param activity     the voice activity decision of the Opus layer
     * @return {@link SilkErrors#SILK_NO_ERROR} or an error code
     */
    public int encode(SilkEncControl encControl, short[] samplesIn, int samplesInOff, int nSamplesIn,
                      RangeEncoder psRangeEnc, int[] nBytesOut, int prefillFlag, int activity) {
        int n, i, nBits, flags, tmp_payloadSize_ms = 0, tmp_complexity = 0, ret = 0;
        int nSamplesToBuffer, nSamplesToBufferMax, nBlocksOf10ms;
        int nSamplesFromInput, nSamplesFromInputMax;
        int speech_act_thr_for_switch_Q8;
        int TargetRate_bps, channelRate_bps, LBRR_symbol, sum;
        int transition, curr_block, tot_blocks;
        var st0 = state_Fxx[0];
        var st1 = state_Fxx[1];

        if (encControl.reducedDependency != 0) {
            st0.sCmn.first_frame_after_reset = 1;
            st1.sCmn.first_frame_after_reset = 1;
        }
        st0.sCmn.nFramesEncoded = st1.sCmn.nFramesEncoded = 0;

        if ((ret = ControlCodec.checkControlInput(encControl)) != 0) {
            return ret;
        }

        encControl.switchReady = 0;

        if (encControl.nChannelsInternal > nChannelsInternal) {
            ret += ControlCodec.initEncoder(st1);
            sStereo.pred_prev_Q13[0] = 0;
            sStereo.pred_prev_Q13[1] = 0;
            sStereo.sSide[0] = 0;
            sStereo.sSide[1] = 0;
            sStereo.mid_side_amp_Q0[0] = 0;
            sStereo.mid_side_amp_Q0[1] = 1;
            sStereo.mid_side_amp_Q0[2] = 0;
            sStereo.mid_side_amp_Q0[3] = 1;
            sStereo.width_prev_Q14 = 0;
            sStereo.smth_width_Q14 = (short) fixConst(1, 14);
            if (nChannelsAPI == 2) {
                st1.sCmn.resampler_state.copyFrom(st0.sCmn.resampler_state);
                st1.sCmn.In_HP_State[0] = st0.sCmn.In_HP_State[0];
                st1.sCmn.In_HP_State[1] = st0.sCmn.In_HP_State[1];
            }
        }

        transition = (encControl.payloadSize_ms != st0.sCmn.PacketSize_ms) || (nChannelsInternal != encControl.nChannelsInternal) ? 1 : 0;

        nChannelsAPI = encControl.nChannelsAPI;
        nChannelsInternal = encControl.nChannelsInternal;

        nBlocksOf10ms = (100 * nSamplesIn) / encControl.API_sampleRate;
        tot_blocks = (nBlocksOf10ms > 1) ? nBlocksOf10ms >> 1 : 1;
        curr_block = 0;
        if (prefillFlag != 0) {
            var save_LP = new LpState();
            if (nBlocksOf10ms != 1) {
                return SILK_ENC_INPUT_INVALID_NO_OF_SAMPLES;
            }
            if (prefillFlag == 2) {
                copyLpState(save_LP, st0.sCmn.sLP);
                save_LP.saved_fs_kHz = st0.sCmn.fs_kHz;
            }
            for (n = 0; n < encControl.nChannelsInternal; n++) {
                ret = ControlCodec.initEncoder(state_Fxx[n]);
                if (prefillFlag == 2) {
                    copyLpState(state_Fxx[n].sCmn.sLP, save_LP);
                }
            }
            tmp_payloadSize_ms = encControl.payloadSize_ms;
            encControl.payloadSize_ms = 10;
            tmp_complexity = encControl.complexity;
            encControl.complexity = 0;
            for (n = 0; n < encControl.nChannelsInternal; n++) {
                state_Fxx[n].sCmn.controlled_since_last_payload = 0;
                state_Fxx[n].sCmn.prefillFlag = 1;
            }
        } else {
            if (nBlocksOf10ms * encControl.API_sampleRate != 100 * nSamplesIn || nSamplesIn < 0) {
                return SILK_ENC_INPUT_INVALID_NO_OF_SAMPLES;
            }
            if (1000 * nSamplesIn > encControl.payloadSize_ms * encControl.API_sampleRate) {
                return SILK_ENC_INPUT_INVALID_NO_OF_SAMPLES;
            }
        }

        for (n = 0; n < encControl.nChannelsInternal; n++) {
            var force_fs_kHz = (n == 1) ? st0.sCmn.fs_kHz : 0;
            if ((ret = ControlCodec.controlEncoder(state_Fxx[n], encControl, allowBandwidthSwitch, n, force_fs_kHz)) != 0) {
                return ret;
            }
            if (state_Fxx[n].sCmn.first_frame_after_reset != 0 || transition != 0) {
                for (i = 0; i < st0.sCmn.nFramesPerPacket; i++) {
                    state_Fxx[n].sCmn.LBRR_flags[i] = 0;
                }
            }
            state_Fxx[n].sCmn.inDTX = state_Fxx[n].sCmn.useDTX;
        }

        nSamplesToBufferMax = 10 * nBlocksOf10ms * st0.sCmn.fs_kHz;
        nSamplesFromInputMax = (nSamplesToBufferMax * st0.sCmn.API_fs_Hz) / (st0.sCmn.fs_kHz * 1000);
        var buf = this.buf;
        if (nSamplesFromInputMax > buf.length) {
            return SILK_ENC_INPUT_INVALID_NO_OF_SAMPLES;
        }
        while (true) {
            var curr_nBitsUsedLBRR = 0;
            nSamplesToBuffer = st0.sCmn.frame_length - st0.sCmn.inputBufIx;
            nSamplesToBuffer = Math.min(nSamplesToBuffer, nSamplesToBufferMax);
            nSamplesFromInput = (nSamplesToBuffer * st0.sCmn.API_fs_Hz) / (st0.sCmn.fs_kHz * 1000);
            if (encControl.nChannelsAPI == 2 && encControl.nChannelsInternal == 2) {
                var id = st0.sCmn.nFramesEncoded;
                for (n = 0; n < nSamplesFromInput; n++) {
                    buf[n] = samplesIn[samplesInOff + 2 * n];
                }
                if (nPrevChannelsInternal == 1 && id == 0) {
                    st1.sCmn.resampler_state.copyFrom(st0.sCmn.resampler_state);
                }
                ret += Resampler.resampler(st0.sCmn.resampler_state, st0.sCmn.inputBuf, st0.sCmn.inputBufIx + 2, buf, 0,
                        nSamplesFromInput);
                st0.sCmn.inputBufIx += nSamplesToBuffer;

                nSamplesToBuffer = st1.sCmn.frame_length - st1.sCmn.inputBufIx;
                nSamplesToBuffer = Math.min(nSamplesToBuffer, 10 * nBlocksOf10ms * st1.sCmn.fs_kHz);
                for (n = 0; n < nSamplesFromInput; n++) {
                    buf[n] = samplesIn[samplesInOff + 2 * n + 1];
                }
                ret += Resampler.resampler(st1.sCmn.resampler_state, st1.sCmn.inputBuf, st1.sCmn.inputBufIx + 2, buf, 0,
                        nSamplesFromInput);

                st1.sCmn.inputBufIx += nSamplesToBuffer;
            } else if (encControl.nChannelsAPI == 2 && encControl.nChannelsInternal == 1) {
                for (n = 0; n < nSamplesFromInput; n++) {
                    sum = samplesIn[samplesInOff + 2 * n] + samplesIn[samplesInOff + 2 * n + 1];
                    buf[n] = (short) rshiftRound(sum, 1);
                }
                ret += Resampler.resampler(st0.sCmn.resampler_state, st0.sCmn.inputBuf, st0.sCmn.inputBufIx + 2, buf, 0,
                        nSamplesFromInput);
                if (nPrevChannelsInternal == 2 && st0.sCmn.nFramesEncoded == 0) {
                    ret += Resampler.resampler(st1.sCmn.resampler_state, st1.sCmn.inputBuf, st1.sCmn.inputBufIx + 2, buf,
                            0, nSamplesFromInput);
                    for (n = 0; n < st0.sCmn.frame_length; n++) {
                        st0.sCmn.inputBuf[st0.sCmn.inputBufIx + n + 2] = (short) ((st0.sCmn.inputBuf[st0.sCmn.inputBufIx + n + 2]
                                + st1.sCmn.inputBuf[st1.sCmn.inputBufIx + n + 2]) >> 1);
                    }
                }
                st0.sCmn.inputBufIx += nSamplesToBuffer;
            } else {
                System.arraycopy(samplesIn, samplesInOff, buf, 0, nSamplesFromInput);
                ret += Resampler.resampler(st0.sCmn.resampler_state, st0.sCmn.inputBuf, st0.sCmn.inputBufIx + 2, buf, 0,
                        nSamplesFromInput);
                st0.sCmn.inputBufIx += nSamplesToBuffer;
            }

            samplesInOff += nSamplesFromInput * encControl.nChannelsAPI;
            nSamplesIn -= nSamplesFromInput;

            allowBandwidthSwitch = 0;

            if (st0.sCmn.inputBufIx >= st0.sCmn.frame_length) {
                if (st0.sCmn.nFramesEncoded == 0 && prefillFlag == 0) {
                    iCDF[0] = (short) (256 - (256 >> ((st0.sCmn.nFramesPerPacket + 1) * encControl.nChannelsInternal)));
                    iCDF[1] = 0;
                    psRangeEnc.encIcdf(0, iCDF, 8);
                    curr_nBitsUsedLBRR = psRangeEnc.tell();

                    for (n = 0; n < encControl.nChannelsInternal; n++) {
                        LBRR_symbol = 0;
                        for (i = 0; i < state_Fxx[n].sCmn.nFramesPerPacket; i++) {
                            LBRR_symbol |= state_Fxx[n].sCmn.LBRR_flags[i] << i;
                        }
                        state_Fxx[n].sCmn.LBRR_flag = (byte) (LBRR_symbol > 0 ? 1 : 0);
                        if (LBRR_symbol != 0 && state_Fxx[n].sCmn.nFramesPerPacket > 1) {
                            psRangeEnc.encIcdf(LBRR_symbol - 1, Tables.silk_LBRR_flags_iCDF_ptr[state_Fxx[n].sCmn.nFramesPerPacket - 2], 8);
                        }
                    }

                    for (i = 0; i < st0.sCmn.nFramesPerPacket; i++) {
                        for (n = 0; n < encControl.nChannelsInternal; n++) {
                            if (state_Fxx[n].sCmn.LBRR_flags[i] != 0) {
                                int condCoding;

                                if (encControl.nChannelsInternal == 2 && n == 0) {
                                    StereoEncode.stereoEncodePred(psRangeEnc, sStereo.predIx[i]);
                                    if (st1.sCmn.LBRR_flags[i] == 0) {
                                        StereoEncode.stereoEncodeMidOnly(psRangeEnc, sStereo.mid_only_flags[i]);
                                    }
                                }
                                if (i > 0 && state_Fxx[n].sCmn.LBRR_flags[i - 1] != 0) {
                                    condCoding = CODE_CONDITIONALLY;
                                } else {
                                    condCoding = CODE_INDEPENDENTLY;
                                }
                                state_Fxx[n].encodeIndices.encodeIndices(state_Fxx[n].sCmn, psRangeEnc, i, 1, condCoding);
                                state_Fxx[n].encodePulses.encodePulses(psRangeEnc, state_Fxx[n].sCmn.indices_LBRR[i].signalType,
                                        state_Fxx[n].sCmn.indices_LBRR[i].quantOffsetType,
                                        state_Fxx[n].sCmn.pulses_LBRR[i], state_Fxx[n].sCmn.frame_length);
                            }
                        }
                    }

                    for (n = 0; n < encControl.nChannelsInternal; n++) {
                        Arrays.fill(state_Fxx[n].sCmn.LBRR_flags, 0);
                    }

                    curr_nBitsUsedLBRR = psRangeEnc.tell() - curr_nBitsUsedLBRR;
                }

                VariableCutoff.hpVariableCutoff(st0.sCmn);

                nBits = (encControl.bitRate * encControl.payloadSize_ms) / 1000;
                if (prefillFlag == 0) {
                    if (curr_nBitsUsedLBRR < 10) {
                        nBitsUsedLBRR = 0;
                    } else if (nBitsUsedLBRR < 10) {
                        nBitsUsedLBRR = curr_nBitsUsedLBRR;
                    } else {
                        nBitsUsedLBRR = (nBitsUsedLBRR + curr_nBitsUsedLBRR) / 2;
                    }
                    nBits -= nBitsUsedLBRR;
                }
                nBits = nBits / st0.sCmn.nFramesPerPacket;
                if (encControl.payloadSize_ms == 10) {
                    TargetRate_bps = smulbb(nBits, 100);
                } else {
                    TargetRate_bps = smulbb(nBits, 50);
                }
                TargetRate_bps -= (nBitsExceeded * 1000) / BITRESERVOIR_DECAY_TIME_MS;
                if (prefillFlag == 0 && st0.sCmn.nFramesEncoded > 0) {
                    var bitsBalance = psRangeEnc.tell() - nBitsUsedLBRR - nBits * st0.sCmn.nFramesEncoded;
                    TargetRate_bps -= (bitsBalance * 1000) / BITRESERVOIR_DECAY_TIME_MS;
                }
                TargetRate_bps = limit(TargetRate_bps, encControl.bitRate, 5000);

                if (encControl.nChannelsInternal == 2) {
                    stereoEncode.stereoLrToMs(sStereo, st0.sCmn.inputBuf, 2, st1.sCmn.inputBuf, 2,
                            sStereo.predIx[st0.sCmn.nFramesEncoded], sStereo.mid_only_flags, st0.sCmn.nFramesEncoded,
                            MStargetRates_bps, TargetRate_bps, st0.sCmn.speech_activity_Q8, encControl.toMono,
                            st0.sCmn.fs_kHz, st0.sCmn.frame_length);
                    if (sStereo.mid_only_flags[st0.sCmn.nFramesEncoded] == 0) {
                        if (prev_decode_only_middle == 1) {
                            st1.sShape.clear();
                            st1.sCmn.sNSQ.clear();
                            Arrays.fill(st1.sCmn.prev_NLSFq_Q15, (short) 0);
                            st1.sCmn.sLP.In_LP_State[0] = 0;
                            st1.sCmn.sLP.In_LP_State[1] = 0;
                            st1.sCmn.prevLag = 100;
                            st1.sCmn.sNSQ.lagPrev = 100;
                            st1.sShape.LastGainIndex = 10;
                            st1.sCmn.prevSignalType = TYPE_NO_VOICE_ACTIVITY;
                            st1.sCmn.sNSQ.prev_gain_Q16 = 65536;
                            st1.sCmn.first_frame_after_reset = 1;
                        }
                        EncodeFrameFlp.encodeDoVad(st1, activity);
                    } else {
                        st1.sCmn.VAD_flags[st0.sCmn.nFramesEncoded] = 0;
                    }
                    if (prefillFlag == 0) {
                        StereoEncode.stereoEncodePred(psRangeEnc, sStereo.predIx[st0.sCmn.nFramesEncoded]);
                        if (st1.sCmn.VAD_flags[st0.sCmn.nFramesEncoded] == 0) {
                            StereoEncode.stereoEncodeMidOnly(psRangeEnc, sStereo.mid_only_flags[st0.sCmn.nFramesEncoded]);
                        }
                    }
                } else {
                    st0.sCmn.inputBuf[0] = sStereo.sMid[0];
                    st0.sCmn.inputBuf[1] = sStereo.sMid[1];
                    sStereo.sMid[0] = st0.sCmn.inputBuf[st0.sCmn.frame_length];
                    sStereo.sMid[1] = st0.sCmn.inputBuf[st0.sCmn.frame_length + 1];
                }
                EncodeFrameFlp.encodeDoVad(st0, activity);

                for (n = 0; n < encControl.nChannelsInternal; n++) {
                    int maxBits, useCBR;

                    maxBits = encControl.maxBits;
                    if (tot_blocks == 2 && curr_block == 0) {
                        maxBits = maxBits * 3 / 5;
                    } else if (tot_blocks == 3) {
                        if (curr_block == 0) {
                            maxBits = maxBits * 2 / 5;
                        } else if (curr_block == 1) {
                            maxBits = maxBits * 3 / 4;
                        }
                    }
                    useCBR = encControl.useCBR != 0 && curr_block == tot_blocks - 1 ? 1 : 0;

                    if (encControl.nChannelsInternal == 1) {
                        channelRate_bps = TargetRate_bps;
                    } else {
                        channelRate_bps = MStargetRates_bps[n];
                        if (n == 0 && MStargetRates_bps[1] > 0) {
                            useCBR = 0;
                            maxBits -= encControl.maxBits / (tot_blocks * 2);
                        }
                    }

                    if (channelRate_bps > 0) {
                        int condCoding;

                        ControlCodec.controlSnr(state_Fxx[n].sCmn, channelRate_bps);

                        if (st0.sCmn.nFramesEncoded - n <= 0) {
                            condCoding = CODE_INDEPENDENTLY;
                        } else if (n > 0 && prev_decode_only_middle != 0) {
                            condCoding = CODE_INDEPENDENTLY_NO_LTP_SCALING;
                        } else {
                            condCoding = CODE_CONDITIONALLY;
                        }
                        ret = EncodeFrameFlp.encodeFrame(state_Fxx[n], frameBytes, psRangeEnc, condCoding, maxBits, useCBR);
                        nBytesOut[0] = frameBytes[0];
                    }
                    state_Fxx[n].sCmn.controlled_since_last_payload = 0;
                    state_Fxx[n].sCmn.inputBufIx = 0;
                    state_Fxx[n].sCmn.nFramesEncoded++;
                }
                prev_decode_only_middle = sStereo.mid_only_flags[st0.sCmn.nFramesEncoded - 1];

                if (nBytesOut[0] > 0 && st0.sCmn.nFramesEncoded == st0.sCmn.nFramesPerPacket) {
                    flags = 0;
                    for (n = 0; n < encControl.nChannelsInternal; n++) {
                        for (i = 0; i < state_Fxx[n].sCmn.nFramesPerPacket; i++) {
                            flags = flags << 1;
                            flags |= state_Fxx[n].sCmn.VAD_flags[i];
                        }
                        flags = flags << 1;
                        flags |= state_Fxx[n].sCmn.LBRR_flag;
                    }
                    if (prefillFlag == 0) {
                        psRangeEnc.encPatchInitialBits(flags, (st0.sCmn.nFramesPerPacket + 1) * encControl.nChannelsInternal);
                    }

                    if (st0.sCmn.inDTX != 0 && (encControl.nChannelsInternal == 1 || st1.sCmn.inDTX != 0)) {
                        nBytesOut[0] = 0;
                    }

                    nBitsExceeded += nBytesOut[0] * 8;
                    nBitsExceeded -= (encControl.bitRate * encControl.payloadSize_ms) / 1000;
                    nBitsExceeded = limit(nBitsExceeded, 0, 10000);

                    speech_act_thr_for_switch_Q8 = smlawb(fixConst((double) SPEECH_ACTIVITY_DTX_THRES, 8),
                            fixConst((double) ((1 - SPEECH_ACTIVITY_DTX_THRES) / MAX_BANDWIDTH_SWITCH_DELAY_MS), 16 + 8),
                            timeSinceSwitchAllowed_ms);
                    if (st0.sCmn.speech_activity_Q8 < speech_act_thr_for_switch_Q8) {
                        allowBandwidthSwitch = 1;
                        timeSinceSwitchAllowed_ms = 0;
                    } else {
                        allowBandwidthSwitch = 0;
                        timeSinceSwitchAllowed_ms += encControl.payloadSize_ms;
                    }
                }

                if (nSamplesIn == 0) {
                    break;
                }
            } else {
                break;
            }
            curr_block++;
        }

        nPrevChannelsInternal = encControl.nChannelsInternal;

        encControl.allowBandwidthSwitch = allowBandwidthSwitch;
        encControl.inWBmodeWithoutVariableLP = st0.sCmn.fs_kHz == 16 && st0.sCmn.sLP.mode == 0 ? 1 : 0;
        encControl.internalSampleRate = smulbb(st0.sCmn.fs_kHz, 1000);
        encControl.stereoWidth_Q14 = encControl.toMono != 0 ? 0 : sStereo.smth_width_Q14;
        if (prefillFlag != 0) {
            encControl.payloadSize_ms = tmp_payloadSize_ms;
            encControl.complexity = tmp_complexity;
            for (n = 0; n < encControl.nChannelsInternal; n++) {
                state_Fxx[n].sCmn.controlled_since_last_payload = 0;
                state_Fxx[n].sCmn.prefillFlag = 0;
            }
        }

        encControl.signalType = st0.sCmn.indices.signalType;
        encControl.offset = Tables.silk_Quantization_Offsets_Q10[st0.sCmn.indices.signalType >> 1][st0.sCmn.indices.quantOffsetType];

        return ret;
    }
}
