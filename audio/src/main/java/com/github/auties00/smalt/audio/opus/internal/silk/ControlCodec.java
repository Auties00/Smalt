/* Ports libopus 1.5.2 (silk/init_encoder.c, silk/control_codec.c, silk/check_control_input.c, silk/control_SNR.c,
   silk/control_audio_bandwidth.c). Copyright Skype Limited; BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.audio.opus.internal.silk;

import java.util.Arrays;

import static com.github.auties00.smalt.audio.opus.internal.silk.Define.*;
import static com.github.auties00.smalt.audio.opus.internal.silk.PitchEstTables.*;
import static com.github.auties00.smalt.audio.opus.internal.silk.SigProcFix.*;
import static com.github.auties00.smalt.audio.opus.internal.silk.SilkErrors.*;

/**
 * Initializes and reconfigures a SILK encoder channel: validation of the control structure,
 * internal sampling rate, frame size, complexity, LBRR and SNR target.
 */
final class ControlCodec {
    /**
     * The narrowband SNR table: SNR divided by 21 per 400 bps step above 4 kbps
     * ({@code silk_TargetRate_NB_21}, {@code silk/control_SNR.c}).
     */
    private static final short[] silk_TargetRate_NB_21 = {
            0, 15, 39, 52, 61, 68,
            74, 79, 84, 88, 92, 95, 99, 102, 105, 108, 111, 114, 117, 119, 122, 124,
            126, 129, 131, 133, 135, 137, 139, 142, 143, 145, 147, 149, 151, 153, 155, 157,
            158, 160, 162, 163, 165, 167, 168, 170, 171, 173, 174, 176, 177, 179, 180, 182,
            183, 185, 186, 187, 189, 190, 192, 193, 194, 196, 197, 199, 200, 201, 203, 204,
            205, 207, 208, 209, 211, 212, 213, 215, 216, 217, 219, 220, 221, 223, 224, 225,
            227, 228, 230, 231, 232, 234, 235, 236, 238, 239, 241, 242, 243, 245, 246, 248,
            249, 250, 252, 253, 255
    };

    /**
     * The mediumband SNR table ({@code silk_TargetRate_MB_21}, {@code silk/control_SNR.c}).
     */
    private static final short[] silk_TargetRate_MB_21 = {
            0, 0, 28, 43, 52, 59,
            65, 70, 74, 78, 81, 85, 87, 90, 93, 95, 98, 100, 102, 105, 107, 109,
            111, 113, 115, 116, 118, 120, 122, 123, 125, 127, 128, 130, 131, 133, 134, 136,
            137, 138, 140, 141, 143, 144, 145, 147, 148, 149, 151, 152, 153, 154, 156, 157,
            158, 159, 160, 162, 163, 164, 165, 166, 167, 168, 169, 171, 172, 173, 174, 175,
            176, 177, 178, 179, 180, 181, 182, 183, 184, 185, 186, 187, 188, 188, 189, 190,
            191, 192, 193, 194, 195, 196, 197, 198, 199, 200, 201, 202, 203, 203, 204, 205,
            206, 207, 208, 209, 210, 211, 212, 213, 214, 214, 215, 216, 217, 218, 219, 220,
            221, 222, 223, 224, 224, 225, 226, 227, 228, 229, 230, 231, 232, 233, 234, 235,
            236, 236, 237, 238, 239, 240, 241, 242, 243, 244, 245, 246, 247, 248, 249, 250,
            251, 252, 253, 254, 255
    };

    /**
     * The wideband SNR table ({@code silk_TargetRate_WB_21}, {@code silk/control_SNR.c}).
     */
    private static final short[] silk_TargetRate_WB_21 = {
            0, 0, 0, 8, 29, 41,
            49, 56, 62, 66, 70, 74, 77, 80, 83, 86, 88, 91, 93, 95, 97, 99,
            101, 103, 105, 107, 108, 110, 112, 113, 115, 116, 118, 119, 121, 122, 123, 125,
            126, 127, 129, 130, 131, 132, 134, 135, 136, 137, 138, 140, 141, 142, 143, 144,
            145, 146, 147, 148, 149, 150, 151, 152, 153, 154, 156, 157, 158, 159, 159, 160,
            161, 162, 163, 164, 165, 166, 167, 168, 169, 170, 171, 171, 172, 173, 174, 175,
            176, 177, 177, 178, 179, 180, 181, 181, 182, 183, 184, 185, 185, 186, 187, 188,
            189, 189, 190, 191, 192, 192, 193, 194, 195, 195, 196, 197, 198, 198, 199, 200,
            200, 201, 202, 203, 203, 204, 205, 206, 206, 207, 208, 209, 209, 210, 211, 211,
            212, 213, 214, 214, 215, 216, 216, 217, 218, 219, 219, 220, 221, 221, 222, 223,
            224, 224, 225, 226, 226, 227, 228, 229, 229, 230, 231, 232, 232, 233, 234, 234,
            235, 236, 237, 237, 238, 239, 240, 240, 241, 242, 243, 243, 244, 245, 246, 246,
            247, 248, 249, 249, 250, 251, 252, 253, 255
    };

    private ControlCodec() {
        throw new AssertionError();
    }

    /**
     * Resets an encoder channel ({@code silk_init_encoder}).
     *
     * @param psEnc the channel state
     * @return {@code 0}
     */
    static int initEncoder(EncoderStateFlp psEnc) {
        var ret = 0;
        psEnc.clear();

        psEnc.sCmn.variable_HP_smth1_Q15 = (LinLog.lin2log(fixConst(TuningParameters.VARIABLE_HP_MIN_CUTOFF_HZ, 16)) - (16 << 7)) << 8;
        psEnc.sCmn.variable_HP_smth2_Q15 = psEnc.sCmn.variable_HP_smth1_Q15;

        psEnc.sCmn.first_frame_after_reset = 1;

        ret += Vad.init(psEnc.sCmn.sVAD);

        return ret;
    }

    /**
     * Checks the values of an encoder control structure ({@code check_control_input}).
     *
     * @param encControl the control structure
     * @return {@link SilkErrors#SILK_NO_ERROR} or the error code of the first invalid value
     */
    static int checkControlInput(SilkEncControl encControl) {
        if (((encControl.API_sampleRate != 8000)
                && (encControl.API_sampleRate != 12000)
                && (encControl.API_sampleRate != 16000)
                && (encControl.API_sampleRate != 24000)
                && (encControl.API_sampleRate != 32000)
                && (encControl.API_sampleRate != 44100)
                && (encControl.API_sampleRate != 48000))
                || ((encControl.desiredInternalSampleRate != 8000)
                && (encControl.desiredInternalSampleRate != 12000)
                && (encControl.desiredInternalSampleRate != 16000))
                || ((encControl.maxInternalSampleRate != 8000)
                && (encControl.maxInternalSampleRate != 12000)
                && (encControl.maxInternalSampleRate != 16000))
                || ((encControl.minInternalSampleRate != 8000)
                && (encControl.minInternalSampleRate != 12000)
                && (encControl.minInternalSampleRate != 16000))
                || (encControl.minInternalSampleRate > encControl.desiredInternalSampleRate)
                || (encControl.maxInternalSampleRate < encControl.desiredInternalSampleRate)
                || (encControl.minInternalSampleRate > encControl.maxInternalSampleRate)) {
            return SILK_ENC_FS_NOT_SUPPORTED;
        }
        if (encControl.payloadSize_ms != 10
                && encControl.payloadSize_ms != 20
                && encControl.payloadSize_ms != 40
                && encControl.payloadSize_ms != 60) {
            return SILK_ENC_PACKET_SIZE_NOT_SUPPORTED;
        }
        if (encControl.packetLossPercentage < 0 || encControl.packetLossPercentage > 100) {
            return SILK_ENC_INVALID_LOSS_RATE;
        }
        if (encControl.useDTX < 0 || encControl.useDTX > 1) {
            return SILK_ENC_INVALID_DTX_SETTING;
        }
        if (encControl.useCBR < 0 || encControl.useCBR > 1) {
            return SILK_ENC_INVALID_CBR_SETTING;
        }
        if (encControl.useInBandFEC < 0 || encControl.useInBandFEC > 1) {
            return SILK_ENC_INVALID_INBAND_FEC_SETTING;
        }
        if (encControl.nChannelsAPI < 1 || encControl.nChannelsAPI > ENCODER_NUM_CHANNELS) {
            return SILK_ENC_INVALID_NUMBER_OF_CHANNELS_ERROR;
        }
        if (encControl.nChannelsInternal < 1 || encControl.nChannelsInternal > ENCODER_NUM_CHANNELS) {
            return SILK_ENC_INVALID_NUMBER_OF_CHANNELS_ERROR;
        }
        if (encControl.nChannelsInternal > encControl.nChannelsAPI) {
            return SILK_ENC_INVALID_NUMBER_OF_CHANNELS_ERROR;
        }
        if (encControl.complexity < 0 || encControl.complexity > 10) {
            return SILK_ENC_INVALID_COMPLEXITY_SETTING;
        }
        return SILK_NO_ERROR;
    }

    /**
     * Sets the target SNR of the residual quantizer from the target bitrate
     * ({@code silk_control_SNR}).
     *
     * @param psEncC         the encoder state
     * @param TargetRate_bps the target bitrate
     * @return {@link SilkErrors#SILK_NO_ERROR}
     */
    static int controlSnr(EncoderState psEncC, int TargetRate_bps) {
        int id;
        int bound;
        short[] snr_table;

        psEncC.TargetRate_bps = TargetRate_bps;
        if (psEncC.nb_subfr == 2) {
            TargetRate_bps -= 2000 + psEncC.fs_kHz / 16;
        }
        if (psEncC.fs_kHz == 8) {
            bound = silk_TargetRate_NB_21.length;
            snr_table = silk_TargetRate_NB_21;
        } else if (psEncC.fs_kHz == 12) {
            bound = silk_TargetRate_MB_21.length;
            snr_table = silk_TargetRate_MB_21;
        } else {
            bound = silk_TargetRate_WB_21.length;
            snr_table = silk_TargetRate_WB_21;
        }
        id = (TargetRate_bps + 200) / 400;
        id = Math.min(id - 10, bound - 1);
        if (id <= 0) {
            psEncC.SNR_dB_Q7 = 0;
        } else {
            psEncC.SNR_dB_Q7 = snr_table[id] * 21;
        }
        return SILK_NO_ERROR;
    }

    /**
     * Chooses the internal sampling rate and drives the bandwidth-switching state machine
     * ({@code silk_control_audio_bandwidth}).
     *
     * @param psEncC     the encoder state
     * @param encControl the control structure; {@code switchReady} and {@code maxBits} may be
     *                   updated
     * @return the internal sampling rate in kHz
     */
    static int controlAudioBandwidth(EncoderState psEncC, SilkEncControl encControl) {
        int fs_kHz;
        int orig_kHz;
        int fs_Hz;

        orig_kHz = psEncC.fs_kHz;
        if (orig_kHz == 0) {
            orig_kHz = psEncC.sLP.saved_fs_kHz;
        }
        fs_kHz = orig_kHz;
        fs_Hz = smulbb(fs_kHz, 1000);
        if (fs_Hz == 0) {
            fs_Hz = Math.min(psEncC.desiredInternal_fs_Hz, psEncC.API_fs_Hz);
            fs_kHz = fs_Hz / 1000;
        } else if (fs_Hz > psEncC.API_fs_Hz || fs_Hz > psEncC.maxInternal_fs_Hz || fs_Hz < psEncC.minInternal_fs_Hz) {
            fs_Hz = psEncC.API_fs_Hz;
            fs_Hz = Math.min(fs_Hz, psEncC.maxInternal_fs_Hz);
            fs_Hz = Math.max(fs_Hz, psEncC.minInternal_fs_Hz);
            fs_kHz = fs_Hz / 1000;
        } else {
            if (psEncC.sLP.transition_frame_no >= TRANSITION_FRAMES) {
                psEncC.sLP.mode = 0;
            }
            if (psEncC.allow_bandwidth_switch != 0 || encControl.opusCanSwitch != 0) {
                if (smulbb(orig_kHz, 1000) > psEncC.desiredInternal_fs_Hz) {
                    if (psEncC.sLP.mode == 0) {
                        psEncC.sLP.transition_frame_no = TRANSITION_FRAMES;
                        psEncC.sLP.In_LP_State[0] = 0;
                        psEncC.sLP.In_LP_State[1] = 0;
                    }
                    if (encControl.opusCanSwitch != 0) {
                        psEncC.sLP.mode = 0;
                        fs_kHz = orig_kHz == 16 ? 12 : 8;
                    } else {
                        if (psEncC.sLP.transition_frame_no <= 0) {
                            encControl.switchReady = 1;
                            encControl.maxBits -= encControl.maxBits * 5 / (encControl.payloadSize_ms + 5);
                        } else {
                            psEncC.sLP.mode = -2;
                        }
                    }
                } else if (smulbb(orig_kHz, 1000) < psEncC.desiredInternal_fs_Hz) {
                    if (encControl.opusCanSwitch != 0) {
                        fs_kHz = orig_kHz == 8 ? 12 : 16;
                        psEncC.sLP.transition_frame_no = 0;
                        psEncC.sLP.In_LP_State[0] = 0;
                        psEncC.sLP.In_LP_State[1] = 0;
                        psEncC.sLP.mode = 1;
                    } else {
                        if (psEncC.sLP.mode == 0) {
                            encControl.switchReady = 1;
                            encControl.maxBits -= encControl.maxBits * 5 / (encControl.payloadSize_ms + 5);
                        } else {
                            psEncC.sLP.mode = 1;
                        }
                    }
                } else {
                    if (psEncC.sLP.mode < 0) {
                        psEncC.sLP.mode = 1;
                    }
                }
            }
        }
        return fs_kHz;
    }

    /**
     * Applies the control structure to an encoder channel ({@code silk_control_encoder}).
     *
     * @param psEnc           the channel state
     * @param encControl      the control structure
     * @param allow_bw_switch whether switching the internal bandwidth is allowed
     * @param channelNb       the channel number
     * @param force_fs_kHz    the internal sampling rate to force in kHz, or zero
     * @return {@link SilkErrors#SILK_NO_ERROR} or an error code
     */
    static int controlEncoder(EncoderStateFlp psEnc, SilkEncControl encControl, int allow_bw_switch, int channelNb,
                              int force_fs_kHz) {
        int fs_kHz, ret = 0;

        psEnc.sCmn.useDTX = encControl.useDTX;
        psEnc.sCmn.useCBR = encControl.useCBR;
        psEnc.sCmn.API_fs_Hz = encControl.API_sampleRate;
        psEnc.sCmn.maxInternal_fs_Hz = encControl.maxInternalSampleRate;
        psEnc.sCmn.minInternal_fs_Hz = encControl.minInternalSampleRate;
        psEnc.sCmn.desiredInternal_fs_Hz = encControl.desiredInternalSampleRate;
        psEnc.sCmn.useInBandFEC = encControl.useInBandFEC;
        psEnc.sCmn.nChannelsAPI = encControl.nChannelsAPI;
        psEnc.sCmn.nChannelsInternal = encControl.nChannelsInternal;
        psEnc.sCmn.allow_bandwidth_switch = allow_bw_switch;
        psEnc.sCmn.channelNb = channelNb;

        if (psEnc.sCmn.controlled_since_last_payload != 0 && psEnc.sCmn.prefillFlag == 0) {
            if (psEnc.sCmn.API_fs_Hz != psEnc.sCmn.prev_API_fs_Hz && psEnc.sCmn.fs_kHz > 0) {
                ret += setupResamplers(psEnc, psEnc.sCmn.fs_kHz);
            }
            return ret;
        }

        fs_kHz = controlAudioBandwidth(psEnc.sCmn, encControl);
        if (force_fs_kHz != 0) {
            fs_kHz = force_fs_kHz;
        }

        ret += setupResamplers(psEnc, fs_kHz);

        ret += setupFs(psEnc, fs_kHz, encControl.payloadSize_ms);

        ret += setupComplexity(psEnc.sCmn, encControl.complexity);

        psEnc.sCmn.PacketLoss_perc = encControl.packetLossPercentage;

        ret += setupLbrr(psEnc.sCmn, encControl);

        psEnc.sCmn.controlled_since_last_payload = 1;

        return ret;
    }

    /**
     * Re-initializes the input resampler for a new internal or API rate, resampling the buffered
     * analysis signal so that no audio is lost ({@code silk_setup_resamplers}).
     *
     * @param psEnc  the channel state
     * @param fs_kHz the new internal sampling rate in kHz
     * @return {@link SilkErrors#SILK_NO_ERROR} or an error code
     */
    private static int setupResamplers(EncoderStateFlp psEnc, int fs_kHz) {
        var ret = SILK_NO_ERROR;

        if (psEnc.sCmn.fs_kHz != fs_kHz || psEnc.sCmn.prev_API_fs_Hz != psEnc.sCmn.API_fs_Hz) {
            if (psEnc.sCmn.fs_kHz == 0) {
                ret += Resampler.resamplerInit(psEnc.sCmn.resampler_state, psEnc.sCmn.API_fs_Hz, fs_kHz * 1000, 1);
            } else {
                int new_buf_samples;
                int api_buf_samples;
                int old_buf_samples;
                int buf_length_ms;

                buf_length_ms = ((psEnc.sCmn.nb_subfr * 5) << 1) + LA_SHAPE_MS;
                old_buf_samples = buf_length_ms * psEnc.sCmn.fs_kHz;

                new_buf_samples = buf_length_ms * fs_kHz;
                var x_bufFIX = new short[Math.max(old_buf_samples, new_buf_samples)];
                SigProcFlp.float2shortArray(x_bufFIX, 0, psEnc.x_buf, 0, old_buf_samples);

                var temp_resampler_state = new ResamplerState();
                ret += Resampler.resamplerInit(temp_resampler_state, smulbb(psEnc.sCmn.fs_kHz, 1000), psEnc.sCmn.API_fs_Hz, 0);

                api_buf_samples = buf_length_ms * (psEnc.sCmn.API_fs_Hz / 1000);

                var x_buf_API_fs_Hz = new short[api_buf_samples];
                ret += Resampler.resampler(temp_resampler_state, x_buf_API_fs_Hz, 0, x_bufFIX, 0, old_buf_samples);

                ret += Resampler.resamplerInit(psEnc.sCmn.resampler_state, psEnc.sCmn.API_fs_Hz, smulbb(fs_kHz, 1000), 1);

                ret += Resampler.resampler(psEnc.sCmn.resampler_state, x_bufFIX, 0, x_buf_API_fs_Hz, 0, api_buf_samples);

                SigProcFlp.short2floatArray(psEnc.x_buf, 0, x_bufFIX, 0, new_buf_samples);
            }
        }

        psEnc.sCmn.prev_API_fs_Hz = psEnc.sCmn.API_fs_Hz;

        return ret;
    }

    /**
     * Sets the packet size and the internal sampling rate, resetting the rate-dependent state
     * when the rate changes ({@code silk_setup_fs}).
     *
     * @param psEnc         the channel state
     * @param fs_kHz        the internal sampling rate in kHz
     * @param PacketSize_ms the packet duration in milliseconds
     * @return {@link SilkErrors#SILK_NO_ERROR} or an error code
     */
    private static int setupFs(EncoderStateFlp psEnc, int fs_kHz, int PacketSize_ms) {
        var ret = SILK_NO_ERROR;
        var sCmn = psEnc.sCmn;

        if (PacketSize_ms != sCmn.PacketSize_ms) {
            if ((PacketSize_ms != 10)
                    && (PacketSize_ms != 20)
                    && (PacketSize_ms != 40)
                    && (PacketSize_ms != 60)) {
                ret = SILK_ENC_PACKET_SIZE_NOT_SUPPORTED;
            }
            if (PacketSize_ms <= 10) {
                sCmn.nFramesPerPacket = 1;
                sCmn.nb_subfr = PacketSize_ms == 10 ? 2 : 1;
                sCmn.frame_length = smulbb(PacketSize_ms, fs_kHz);
                sCmn.pitch_LPC_win_length = smulbb(FIND_PITCH_LPC_WIN_MS_2_SF, fs_kHz);
                if (sCmn.fs_kHz == 8) {
                    sCmn.pitch_contour_iCDF = Tables.silk_pitch_contour_10_ms_NB_iCDF;
                } else {
                    sCmn.pitch_contour_iCDF = Tables.silk_pitch_contour_10_ms_iCDF;
                }
            } else {
                sCmn.nFramesPerPacket = PacketSize_ms / MAX_FRAME_LENGTH_MS;
                sCmn.nb_subfr = MAX_NB_SUBFR;
                sCmn.frame_length = smulbb(20, fs_kHz);
                sCmn.pitch_LPC_win_length = smulbb(FIND_PITCH_LPC_WIN_MS, fs_kHz);
                if (sCmn.fs_kHz == 8) {
                    sCmn.pitch_contour_iCDF = Tables.silk_pitch_contour_NB_iCDF;
                } else {
                    sCmn.pitch_contour_iCDF = Tables.silk_pitch_contour_iCDF;
                }
            }
            sCmn.PacketSize_ms = PacketSize_ms;
            sCmn.TargetRate_bps = 0;
        }

        if (sCmn.fs_kHz != fs_kHz) {
            psEnc.sShape.clear();
            sCmn.sNSQ.clear();
            Arrays.fill(sCmn.prev_NLSFq_Q15, (short) 0);
            sCmn.sLP.In_LP_State[0] = 0;
            sCmn.sLP.In_LP_State[1] = 0;
            sCmn.inputBufIx = 0;
            sCmn.nFramesEncoded = 0;
            sCmn.TargetRate_bps = 0;

            sCmn.prevLag = 100;
            sCmn.first_frame_after_reset = 1;
            psEnc.sShape.LastGainIndex = 10;
            sCmn.sNSQ.lagPrev = 100;
            sCmn.sNSQ.prev_gain_Q16 = 65536;
            sCmn.prevSignalType = TYPE_NO_VOICE_ACTIVITY;

            sCmn.fs_kHz = fs_kHz;
            if (sCmn.fs_kHz == 8) {
                if (sCmn.nb_subfr == MAX_NB_SUBFR) {
                    sCmn.pitch_contour_iCDF = Tables.silk_pitch_contour_NB_iCDF;
                } else {
                    sCmn.pitch_contour_iCDF = Tables.silk_pitch_contour_10_ms_NB_iCDF;
                }
            } else {
                if (sCmn.nb_subfr == MAX_NB_SUBFR) {
                    sCmn.pitch_contour_iCDF = Tables.silk_pitch_contour_iCDF;
                } else {
                    sCmn.pitch_contour_iCDF = Tables.silk_pitch_contour_10_ms_iCDF;
                }
            }
            if (sCmn.fs_kHz == 8 || sCmn.fs_kHz == 12) {
                sCmn.predictLPCOrder = MIN_LPC_ORDER;
                sCmn.psNLSF_CB = Tables.silk_NLSF_CB_NB_MB;
            } else {
                sCmn.predictLPCOrder = MAX_LPC_ORDER;
                sCmn.psNLSF_CB = Tables.silk_NLSF_CB_WB;
            }
            sCmn.subfr_length = SUB_FRAME_LENGTH_MS * fs_kHz;
            sCmn.frame_length = smulbb(sCmn.subfr_length, sCmn.nb_subfr);
            sCmn.ltp_mem_length = smulbb(LTP_MEM_LENGTH_MS, fs_kHz);
            sCmn.la_pitch = smulbb(LA_PITCH_MS, fs_kHz);
            if (sCmn.nb_subfr == MAX_NB_SUBFR) {
                sCmn.pitch_LPC_win_length = smulbb(FIND_PITCH_LPC_WIN_MS, fs_kHz);
            } else {
                sCmn.pitch_LPC_win_length = smulbb(FIND_PITCH_LPC_WIN_MS_2_SF, fs_kHz);
            }
            if (sCmn.fs_kHz == 16) {
                sCmn.pitch_lag_low_bits_iCDF = Tables.silk_uniform8_iCDF;
            } else if (sCmn.fs_kHz == 12) {
                sCmn.pitch_lag_low_bits_iCDF = Tables.silk_uniform6_iCDF;
            } else {
                sCmn.pitch_lag_low_bits_iCDF = Tables.silk_uniform4_iCDF;
            }
        }

        return ret;
    }

    /**
     * Sets the complexity-dependent encoder parameters ({@code silk_setup_complexity}).
     *
     * @param psEncC     the encoder state
     * @param Complexity the complexity, 0 to 10
     * @return {@code 0}
     */
    private static int setupComplexity(EncoderState psEncC, int Complexity) {
        var ret = 0;
        var warping = fixConst((double) TuningParameters.WARPING_MULTIPLIER, 16);
        if (Complexity < 1) {
            psEncC.pitchEstimationComplexity = SILK_PE_MIN_COMPLEX;
            psEncC.pitchEstimationThreshold_Q16 = fixConst(0.8, 16);
            psEncC.pitchEstimationLPCOrder = 6;
            psEncC.shapingLPCOrder = 12;
            psEncC.la_shape = 3 * psEncC.fs_kHz;
            psEncC.nStatesDelayedDecision = 1;
            psEncC.useInterpolatedNLSFs = 0;
            psEncC.NLSF_MSVQ_Survivors = 2;
            psEncC.warping_Q16 = 0;
        } else if (Complexity < 2) {
            psEncC.pitchEstimationComplexity = SILK_PE_MID_COMPLEX;
            psEncC.pitchEstimationThreshold_Q16 = fixConst(0.76, 16);
            psEncC.pitchEstimationLPCOrder = 8;
            psEncC.shapingLPCOrder = 14;
            psEncC.la_shape = 5 * psEncC.fs_kHz;
            psEncC.nStatesDelayedDecision = 1;
            psEncC.useInterpolatedNLSFs = 0;
            psEncC.NLSF_MSVQ_Survivors = 3;
            psEncC.warping_Q16 = 0;
        } else if (Complexity < 3) {
            psEncC.pitchEstimationComplexity = SILK_PE_MIN_COMPLEX;
            psEncC.pitchEstimationThreshold_Q16 = fixConst(0.8, 16);
            psEncC.pitchEstimationLPCOrder = 6;
            psEncC.shapingLPCOrder = 12;
            psEncC.la_shape = 3 * psEncC.fs_kHz;
            psEncC.nStatesDelayedDecision = 2;
            psEncC.useInterpolatedNLSFs = 0;
            psEncC.NLSF_MSVQ_Survivors = 2;
            psEncC.warping_Q16 = 0;
        } else if (Complexity < 4) {
            psEncC.pitchEstimationComplexity = SILK_PE_MID_COMPLEX;
            psEncC.pitchEstimationThreshold_Q16 = fixConst(0.76, 16);
            psEncC.pitchEstimationLPCOrder = 8;
            psEncC.shapingLPCOrder = 14;
            psEncC.la_shape = 5 * psEncC.fs_kHz;
            psEncC.nStatesDelayedDecision = 2;
            psEncC.useInterpolatedNLSFs = 0;
            psEncC.NLSF_MSVQ_Survivors = 4;
            psEncC.warping_Q16 = 0;
        } else if (Complexity < 6) {
            psEncC.pitchEstimationComplexity = SILK_PE_MID_COMPLEX;
            psEncC.pitchEstimationThreshold_Q16 = fixConst(0.74, 16);
            psEncC.pitchEstimationLPCOrder = 10;
            psEncC.shapingLPCOrder = 16;
            psEncC.la_shape = 5 * psEncC.fs_kHz;
            psEncC.nStatesDelayedDecision = 2;
            psEncC.useInterpolatedNLSFs = 1;
            psEncC.NLSF_MSVQ_Survivors = 6;
            psEncC.warping_Q16 = psEncC.fs_kHz * warping;
        } else if (Complexity < 8) {
            psEncC.pitchEstimationComplexity = SILK_PE_MID_COMPLEX;
            psEncC.pitchEstimationThreshold_Q16 = fixConst(0.72, 16);
            psEncC.pitchEstimationLPCOrder = 12;
            psEncC.shapingLPCOrder = 20;
            psEncC.la_shape = 5 * psEncC.fs_kHz;
            psEncC.nStatesDelayedDecision = 3;
            psEncC.useInterpolatedNLSFs = 1;
            psEncC.NLSF_MSVQ_Survivors = 8;
            psEncC.warping_Q16 = psEncC.fs_kHz * warping;
        } else {
            psEncC.pitchEstimationComplexity = SILK_PE_MAX_COMPLEX;
            psEncC.pitchEstimationThreshold_Q16 = fixConst(0.7, 16);
            psEncC.pitchEstimationLPCOrder = 16;
            psEncC.shapingLPCOrder = 24;
            psEncC.la_shape = 5 * psEncC.fs_kHz;
            psEncC.nStatesDelayedDecision = MAX_DEL_DEC_STATES;
            psEncC.useInterpolatedNLSFs = 1;
            psEncC.NLSF_MSVQ_Survivors = 16;
            psEncC.warping_Q16 = psEncC.fs_kHz * warping;
        }

        psEncC.pitchEstimationLPCOrder = Math.min(psEncC.pitchEstimationLPCOrder, psEncC.predictLPCOrder);
        psEncC.shapeWinLength = SUB_FRAME_LENGTH_MS * psEncC.fs_kHz + 2 * psEncC.la_shape;
        psEncC.Complexity = Complexity;

        return ret;
    }

    /**
     * Enables or disables LBRR and sets its gain increase ({@code silk_setup_LBRR}).
     *
     * @param psEncC     the encoder state
     * @param encControl the control structure
     * @return {@link SilkErrors#SILK_NO_ERROR}
     */
    private static int setupLbrr(EncoderState psEncC, SilkEncControl encControl) {
        int LBRR_in_previous_packet, ret = SILK_NO_ERROR;

        LBRR_in_previous_packet = psEncC.LBRR_enabled;
        psEncC.LBRR_enabled = encControl.LBRR_coded;
        if (psEncC.LBRR_enabled != 0) {
            if (LBRR_in_previous_packet == 0) {
                psEncC.LBRR_GainIncreases = 7;
            } else {
                psEncC.LBRR_GainIncreases = Math.max(7 - smulwb(psEncC.PacketLoss_perc, fixConst(0.2, 16)), 3);
            }
        }

        return ret;
    }
}
