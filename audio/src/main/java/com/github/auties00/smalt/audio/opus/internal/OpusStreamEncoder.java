/* Ports libopus 1.5.2 (src/opus_encoder.c). Copyright Xiph.Org Foundation and Skype Limited; BSD 3-Clause License.
   Modified for Smalt. */
package com.github.auties00.smalt.audio.opus.internal;

import com.github.auties00.smalt.audio.opus.internal.celt.AnalysisInfo;
import com.github.auties00.smalt.audio.opus.internal.celt.CeltEncoder;
import com.github.auties00.smalt.audio.opus.internal.celt.CeltMode;
import com.github.auties00.smalt.audio.opus.internal.celt.MathOps;
import com.github.auties00.smalt.audio.opus.internal.celt.Pitch;
import com.github.auties00.smalt.audio.opus.internal.celt.RangeEncoder;
import com.github.auties00.smalt.audio.opus.internal.celt.SilkInfo;
import com.github.auties00.smalt.audio.opus.internal.silk.Define;
import com.github.auties00.smalt.audio.opus.internal.silk.LinLog;
import com.github.auties00.smalt.audio.opus.internal.silk.SigProcFix;
import com.github.auties00.smalt.audio.opus.internal.silk.SilkEncControl;
import com.github.auties00.smalt.audio.opus.internal.silk.SilkEncoder;
import com.github.auties00.smalt.audio.opus.internal.silk.TuningParameters;

import static com.github.auties00.smalt.audio.opus.internal.OpusDefines.*;

/**
 * Encodes one Opus elementary stream from float samples: it chooses the coding mode (SILK-only,
 * hybrid or CELT-only), the audio bandwidth and the number of coded channels for every frame,
 * drives the SILK and CELT encoders, and codes the redundancy frames that make mode transitions
 * seamless.
 *
 * <p>Input is interleaved float PCM nominally in {@code [-1, 1]} at 8, 12, 16, 24 or 48 kHz with
 * one or two channels; each call encodes one frame of 2.5 to 120 ms into one packet.
 *
 * @implNote This implementation omits the DRED, OSCE-training and fixed-point code and holds the stack buffers of the
 * reference. As in the float build, the tonality analysis runs for complexity 7 and above.
 */
public final class OpusStreamEncoder {
    /**
     * The size of the delay buffer, 10 ms at 48 kHz ({@code MAX_ENCODER_BUFFER}).
     */
    private static final int MAX_ENCODER_BUFFER = 480;

    /**
     * The ratio of peak signal energy to frame energy above which a noise frame still counts as
     * active, 25 dB ({@code PSEUDO_SNR_THRESHOLD}).
     */
    private static final float PSEUDO_SNR_THRESHOLD = 316.23f;

    /**
     * The mono voice bandwidth thresholds: middle threshold and hysteresis for NB/MB, MB/WB,
     * WB/SWB and SWB/FB ({@code mono_voice_bandwidth_thresholds}).
     */
    private static final int[] mono_voice_bandwidth_thresholds = {9000, 700, 9000, 700, 13500, 1000, 14000, 2000};

    /**
     * The mono music bandwidth thresholds ({@code mono_music_bandwidth_thresholds}).
     */
    private static final int[] mono_music_bandwidth_thresholds = {9000, 700, 9000, 700, 11000, 1000, 12000, 2000};

    /**
     * The stereo voice bandwidth thresholds ({@code stereo_voice_bandwidth_thresholds}).
     */
    private static final int[] stereo_voice_bandwidth_thresholds = {9000, 700, 9000, 700, 13500, 1000, 14000, 2000};

    /**
     * The stereo music bandwidth thresholds ({@code stereo_music_bandwidth_thresholds}).
     */
    private static final int[] stereo_music_bandwidth_thresholds = {9000, 700, 9000, 700, 11000, 1000, 12000, 2000};

    /**
     * The bitrate above which voice is coded in stereo ({@code stereo_voice_threshold}).
     */
    private static final int stereo_voice_threshold = 19000;

    /**
     * The bitrate above which music is coded in stereo ({@code stereo_music_threshold}).
     */
    private static final int stereo_music_threshold = 17000;

    /**
     * The bitrates above which CELT-only is chosen, by channel count and voice/music
     * ({@code mode_thresholds}).
     */
    private static final int[][] mode_thresholds = {{64000, 10000}, {44000, 10000}};

    /**
     * The bitrate thresholds and hysteresis for in-band FEC per bandwidth ({@code fec_thresholds}).
     */
    private static final int[] fec_thresholds = {12000, 1000, 14000, 1000, 16000, 1000, 20000, 1000, 22000, 1000};

    /**
     * The total and SILK bitrates of the hybrid rate split ({@code rate_table} of
     * {@code compute_silk_rate_for_hybrid}): total, then SILK for 10 and 20 ms without and with
     * FEC.
     */
    private static final int[][] rate_table = {
            {0, 0, 0, 0, 0},
            {12000, 10000, 10000, 11000, 11000},
            {16000, 13500, 13500, 15000, 15000},
            {20000, 16000, 16000, 18000, 18000},
            {24000, 18000, 18000, 21000, 21000},
            {32000, 22000, 22000, 28000, 28000},
            {64000, 38000, 38000, 50000, 50000}
    };

    /**
     * The SILK encoder control and status ({@code silk_mode}).
     */
    private final SilkEncControl silk_mode = new SilkEncControl();

    /**
     * The coding application.
     */
    private int application;

    /**
     * The number of input channels.
     */
    private final int channels;

    /**
     * The delay compensation in samples, 4 ms.
     */
    private final int delay_compensation;

    /**
     * The forced number of coded channels, or {@link OpusDefines#OPUS_AUTO}.
     */
    private int force_channels;

    /**
     * The signal type hint, or {@link OpusDefines#OPUS_AUTO}.
     */
    private int signal_type;

    /**
     * The forced bandwidth, or {@link OpusDefines#OPUS_AUTO}.
     */
    private int user_bandwidth;

    /**
     * The maximum bandwidth.
     */
    private int max_bandwidth;

    /**
     * The forced coding mode, or {@link OpusDefines#OPUS_AUTO}.
     */
    private int user_forced_mode;

    /**
     * The voice probability in percent from the analysis, or {@code -1}.
     */
    private int voice_ratio;

    /**
     * The input sampling rate.
     */
    private final int Fs;

    /**
     * Whether variable bitrate is enabled.
     */
    private int use_vbr;

    /**
     * Whether the variable bitrate is constrained.
     */
    private int vbr_constraint;

    /**
     * The bitrate of the current frame.
     */
    private int bitrate_bps;

    /**
     * The requested bitrate, {@link OpusDefines#OPUS_AUTO} or {@link OpusDefines#OPUS_BITRATE_MAX}.
     */
    private int user_bitrate_bps;

    /**
     * The effective bit depth of the input.
     */
    private int lsb_depth;

    /**
     * The number of samples of the delay buffer.
     */
    private final int encoder_buffer;

    /**
     * Whether discontinuous transmission is enabled.
     */
    private int use_dtx;

    /**
     * The in-band FEC configuration, 0 to 2.
     */
    private int fec_config;

    /**
     * The tonality analysis.
     */
    private final TonalityAnalysis analysis;

    /**
     * The number of coded channels.
     */
    private int stream_channels;

    /**
     * The stereo width of the previous hybrid frame in Q14.
     */
    private short hybrid_stereo_width_Q14;

    /**
     * The smoothed high-pass cutoff in Q15 log units.
     */
    private int variable_HP_smth2_Q15;

    /**
     * The high-band gain of the previous frame.
     */
    private float prev_HB_gain;

    /**
     * The high-pass filter state.
     */
    private final float[] hp_mem = new float[4];

    /**
     * The coding mode of the current frame.
     */
    private int mode;

    /**
     * The coding mode of the previous frame, or {@code 0} before the first.
     */
    private int prev_mode;

    /**
     * The number of coded channels of the previous frame.
     */
    private int prev_channels;

    /**
     * The frame size of the previous frame.
     */
    private int prev_framesize;

    /**
     * The bandwidth of the current frame.
     */
    private int bandwidth;

    /**
     * The bandwidth chosen from the rate alone.
     */
    private int auto_bandwidth;

    /**
     * Whether SILK switched its internal bandwidth and the next frame needs redundancy.
     */
    private int silk_bw_switch;

    /**
     * Whether no frame has been encoded yet.
     */
    private int first;

    /**
     * The stereo width estimator state ({@code StereoWidthState} XX).
     */
    private float width_XX;

    /**
     * The stereo width estimator state ({@code StereoWidthState} XY).
     */
    private float width_XY;

    /**
     * The stereo width estimator state ({@code StereoWidthState} YY).
     */
    private float width_YY;

    /**
     * The smoothed stereo width.
     */
    private float width_smoothed_width;

    /**
     * The stereo width peak follower.
     */
    private float width_max_follower;

    /**
     * The last {@link #encoder_buffer} input samples after the high-pass filter.
     */
    private final float[] delay_buffer = new float[MAX_ENCODER_BUFFER * 2];

    /**
     * The bandwidth detected by the analysis, or {@code 0}.
     */
    private int detected_bandwidth;

    /**
     * The duration of consecutive inactive frames in half milliseconds.
     */
    private int nb_no_activity_ms_Q1;

    /**
     * The tracked peak frame energy of active frames.
     */
    private float peak_signal_energy;

    /**
     * Whether the current frame is not the last of a multi-frame packet.
     */
    private boolean nonfinal_frame;

    /**
     * The final range of the last packet, unsigned.
     */
    private int rangeFinal;

    /**
     * Whether the stream codes a low-frequency effects channel ({@code lfe}).
     */
    private boolean lfe;

    /**
     * The surround masking of each band and channel, 21 values per channel, or {@code null}
     * ({@code energy_masking}).
     */
    private float[] energy_masking;

    /**
     * The SILK encoder.
     */
    private final SilkEncoder silk_enc;

    /**
     * The CELT encoder.
     */
    private final CeltEncoder celt_enc;

    /**
     * The range encoder of the current frame.
     */
    private final RangeEncoder enc = new RangeEncoder();

    /**
     * The analysis result of the current frame.
     */
    private final AnalysisInfo analysis_info = new AnalysisInfo();

    /**
     * The SILK side information handed to CELT.
     */
    private final SilkInfo silkInfo = new SilkInfo();

    /**
     * The delayed and filtered input of a frame ({@code pcm_buf}).
     */
    private final float[] pcm_buf;

    /**
     * The 16-bit SILK input ({@code pcm_silk}).
     */
    private final short[] pcm_silk;

    /**
     * The CELT prefill input ({@code tmp_prefill}).
     */
    private final float[] tmp_prefill;

    /**
     * The 16-bit input of {@link #encode(short[], int, int, byte[], int, int)} converted to float
     * ({@code in} of {@code opus_encode}), sized for the longest frame.
     */
    private final float[] pcm_int16;

    /**
     * Receives the number of bytes SILK wrote.
     */
    private final int[] nBytes = new int[1];

    /**
     * A two-byte scratch output for CELT prefills.
     */
    private final byte[] dummy = new byte[2];

    /**
     * The interpolated bandwidth thresholds of the current frame ({@code bandwidth_thresholds}).
     */
    private final int[] bandwidth_thresholds = new int[8];

    /**
     * Holds the bandwidth that {@code decide_fec} may lower.
     */
    private final int[] bandwidthRef = new int[1];

    /**
     * The scratch buffer holding the frames of a multi-frame packet ({@code tmp_data}), grown to the
     * largest frame space a call has needed.
     */
    private byte[] multiframeBuffer = new byte[1276 + OpusPacket.MAX_FRAMES];

    /**
     * The repacketizer assembling multi-frame packets.
     */
    private final Repacketizer rp = new Repacketizer();

    /**
     * The repacketizer padding constant-bitrate packets, apart from {@link #rp}, which may hold the
     * frames of a multi-frame packet while one of them is padded.
     */
    private final Repacketizer padder = new Repacketizer();

    /**
     * The three feed-forward coefficients of the VoIP high-pass filter in Q28.
     */
    private final int[] hpB_Q28 = new int[3];

    /**
     * The two feedback coefficients of the VoIP high-pass filter in Q28.
     */
    private final int[] hpA_Q28 = new int[2];

    /**
     * Creates an encoder ({@code opus_encoder_init}).
     *
     * @param Fs          the input rate: 8000, 12000, 16000, 24000 or 48000
     * @param channels    the number of channels: 1 or 2
     * @param application one of the {@code OPUS_APPLICATION_*} constants
     * @throws IllegalArgumentException if a parameter is invalid
     */
    public OpusStreamEncoder(int Fs, int channels, int application) {
        if ((Fs != 48000 && Fs != 24000 && Fs != 16000 && Fs != 12000 && Fs != 8000) || (channels != 1 && channels != 2)
                || (application != OPUS_APPLICATION_VOIP && application != OPUS_APPLICATION_AUDIO
                && application != OPUS_APPLICATION_RESTRICTED_LOWDELAY)) {
            throw new IllegalArgumentException("invalid Opus encoder configuration");
        }
        this.stream_channels = this.channels = channels;
        this.Fs = Fs;
        silk_enc = new SilkEncoder();
        if (silk_enc.initEncoder(silk_mode) != 0) {
            throw new IllegalStateException("SILK encoder initialization failed");
        }
        silk_mode.nChannelsAPI = channels;
        silk_mode.nChannelsInternal = channels;
        silk_mode.API_sampleRate = Fs;
        silk_mode.maxInternalSampleRate = 16000;
        silk_mode.minInternalSampleRate = 8000;
        silk_mode.desiredInternalSampleRate = 16000;
        silk_mode.payloadSize_ms = 20;
        silk_mode.bitRate = 25000;
        silk_mode.packetLossPercentage = 0;
        silk_mode.complexity = 9;
        silk_mode.useInBandFEC = 0;
        silk_mode.useDTX = 0;
        silk_mode.useCBR = 0;
        silk_mode.reducedDependency = 0;
        celt_enc = new CeltEncoder(Fs, channels);
        celt_enc.setSignaling(0);
        celt_enc.setComplexity(silk_mode.complexity);
        use_vbr = 1;
        vbr_constraint = 1;
        user_bitrate_bps = OPUS_AUTO;
        bitrate_bps = 3000 + Fs * channels;
        this.application = application;
        signal_type = OPUS_AUTO;
        user_bandwidth = OPUS_AUTO;
        max_bandwidth = OPUS_BANDWIDTH_FULLBAND;
        force_channels = OPUS_AUTO;
        user_forced_mode = OPUS_AUTO;
        voice_ratio = -1;
        encoder_buffer = Fs / 100;
        lsb_depth = 24;
        delay_compensation = Fs / 250;
        hybrid_stereo_width_Q14 = 1 << 14;
        prev_HB_gain = 1.0f;
        variable_HP_smth2_Q15 = LinLog.lin2log(TuningParameters.VARIABLE_HP_MIN_CUTOFF_HZ) << 8;
        first = 1;
        mode = MODE_HYBRID;
        bandwidth = OPUS_BANDWIDTH_FULLBAND;
        analysis = new TonalityAnalysis(Fs);
        analysis.application = application;
        var maxFrame = Fs / 25 * 3;
        pcm_buf = new float[(delay_compensation + maxFrame) * channels];
        pcm_silk = new short[Math.max(maxFrame, encoder_buffer) * channels];
        tmp_prefill = new float[channels * Fs / 400];
        pcm_int16 = new float[maxFrame * channels];
    }

    /**
     * Sets the target bitrate ({@code OPUS_SET_BITRATE}).
     *
     * @param value the bitrate in bits per second, {@link OpusDefines#OPUS_AUTO} or
     *              {@link OpusDefines#OPUS_BITRATE_MAX}; clamped to {@code [500, 300000 * channels]}
     * @throws IllegalArgumentException if the value is not positive and not a special value
     */
    public void setBitrate(int value) {
        if (value != OPUS_AUTO && value != OPUS_BITRATE_MAX) {
            if (value <= 0) {
                throw new IllegalArgumentException("invalid bitrate " + value);
            } else if (value <= 500) {
                value = 500;
            } else if (value > 300000 * channels) {
                value = 300000 * channels;
            }
        }
        user_bitrate_bps = value;
    }

    /**
     * Sets whether the bitrate is variable ({@code OPUS_SET_VBR}).
     *
     * @param value {@code true} for variable bitrate
     */
    public void setVbr(boolean value) {
        use_vbr = value ? 1 : 0;
        silk_mode.useCBR = 1 - use_vbr;
    }

    /**
     * Sets whether the variable bitrate is constrained ({@code OPUS_SET_VBR_CONSTRAINT}).
     *
     * @param value {@code true} for constrained variable bitrate
     */
    public void setVbrConstraint(boolean value) {
        vbr_constraint = value ? 1 : 0;
    }

    /**
     * Sets the computational complexity ({@code OPUS_SET_COMPLEXITY}).
     *
     * @param value the complexity, 0 to 10
     * @throws IllegalArgumentException if the value is out of range
     */
    public void setComplexity(int value) {
        if (value < 0 || value > 10) {
            throw new IllegalArgumentException("invalid complexity " + value);
        }
        silk_mode.complexity = value;
        celt_enc.setComplexity(value);
    }

    /**
     * Sets the signal type hint ({@code OPUS_SET_SIGNAL}).
     *
     * @param value {@link OpusDefines#OPUS_AUTO}, {@link OpusDefines#OPUS_SIGNAL_VOICE} or
     *              {@link OpusDefines#OPUS_SIGNAL_MUSIC}
     * @throws IllegalArgumentException if the value is invalid
     */
    public void setSignal(int value) {
        if (value != OPUS_AUTO && value != OPUS_SIGNAL_VOICE && value != OPUS_SIGNAL_MUSIC) {
            throw new IllegalArgumentException("invalid signal type " + value);
        }
        signal_type = value;
    }

    /**
     * Sets the maximum audio bandwidth ({@code OPUS_SET_MAX_BANDWIDTH}).
     *
     * @param value one of the {@code OPUS_BANDWIDTH_*} constants
     * @throws IllegalArgumentException if the value is invalid
     */
    public void setMaxBandwidth(int value) {
        if (value < OPUS_BANDWIDTH_NARROWBAND || value > OPUS_BANDWIDTH_FULLBAND) {
            throw new IllegalArgumentException("invalid bandwidth " + value);
        }
        max_bandwidth = value;
        if (max_bandwidth == OPUS_BANDWIDTH_NARROWBAND) {
            silk_mode.maxInternalSampleRate = 8000;
        } else if (max_bandwidth == OPUS_BANDWIDTH_MEDIUMBAND) {
            silk_mode.maxInternalSampleRate = 12000;
        } else {
            silk_mode.maxInternalSampleRate = 16000;
        }
    }

    /**
     * Forces the audio bandwidth ({@code OPUS_SET_BANDWIDTH}).
     *
     * @param value one of the {@code OPUS_BANDWIDTH_*} constants or {@link OpusDefines#OPUS_AUTO}
     * @throws IllegalArgumentException if the value is invalid
     */
    public void setBandwidth(int value) {
        if ((value < OPUS_BANDWIDTH_NARROWBAND || value > OPUS_BANDWIDTH_FULLBAND) && value != OPUS_AUTO) {
            throw new IllegalArgumentException("invalid bandwidth " + value);
        }
        user_bandwidth = value;
        if (user_bandwidth == OPUS_BANDWIDTH_NARROWBAND) {
            silk_mode.maxInternalSampleRate = 8000;
        } else if (user_bandwidth == OPUS_BANDWIDTH_MEDIUMBAND) {
            silk_mode.maxInternalSampleRate = 12000;
        } else {
            silk_mode.maxInternalSampleRate = 16000;
        }
    }

    /**
     * Forces the number of coded channels ({@code OPUS_SET_FORCE_CHANNELS}).
     *
     * @param value 1, 2 (at most the input channels) or {@link OpusDefines#OPUS_AUTO}
     * @throws IllegalArgumentException if the value is invalid
     */
    public void setForceChannels(int value) {
        if ((value < 1 || value > channels) && value != OPUS_AUTO) {
            throw new IllegalArgumentException("invalid channel count " + value);
        }
        force_channels = value;
    }

    /**
     * Forces the coding mode, for testing ({@code OPUS_SET_FORCE_MODE}).
     *
     * @param value one of the {@code MODE_*} constants or {@link OpusDefines#OPUS_AUTO}
     * @throws IllegalArgumentException if the value is invalid
     */
    public void setForceMode(int value) {
        if ((value < MODE_SILK_ONLY || value > MODE_CELT_ONLY) && value != OPUS_AUTO) {
            throw new IllegalArgumentException("invalid mode " + value);
        }
        user_forced_mode = value;
    }

    /**
     * Sets discontinuous transmission ({@code OPUS_SET_DTX}).
     *
     * @param value {@code true} to emit tiny packets during silence
     */
    public void setDtx(boolean value) {
        use_dtx = value ? 1 : 0;
    }

    /**
     * Sets in-band forward error correction ({@code OPUS_SET_INBAND_FEC}).
     *
     * @param value 0 (off), 1 (on) or 2 (on, but not forcing SILK for music)
     * @throws IllegalArgumentException if the value is out of range
     */
    public void setInbandFec(int value) {
        if (value < 0 || value > 2) {
            throw new IllegalArgumentException("invalid FEC setting " + value);
        }
        fec_config = value;
        silk_mode.useInBandFEC = value != 0 ? 1 : 0;
    }

    /**
     * Sets the expected packet loss ({@code OPUS_SET_PACKET_LOSS_PERC}).
     *
     * @param value the loss in percent, 0 to 100
     * @throws IllegalArgumentException if the value is out of range
     */
    public void setPacketLossPerc(int value) {
        if (value < 0 || value > 100) {
            throw new IllegalArgumentException("invalid packet loss " + value);
        }
        silk_mode.packetLossPercentage = value;
        celt_enc.setPacketLossPerc(value);
    }

    /**
     * Sets the effective bit depth of the input ({@code OPUS_SET_LSB_DEPTH}).
     *
     * @param value the depth, 8 to 24
     * @throws IllegalArgumentException if the value is out of range
     */
    public void setLsbDepth(int value) {
        if (value < 8 || value > 24) {
            throw new IllegalArgumentException("invalid LSB depth " + value);
        }
        lsb_depth = value;
    }

    /**
     * Disables inter-frame prediction ({@code OPUS_SET_PREDICTION_DISABLED}).
     *
     * @param value {@code true} to make every frame independently decodable
     */
    public void setPredictionDisabled(boolean value) {
        silk_mode.reducedDependency = value ? 1 : 0;
    }

    /**
     * Disables the stereo phase inversion of CELT ({@code OPUS_SET_PHASE_INVERSION_DISABLED}).
     *
     * @param value {@code true} to disable phase inversion
     */
    public void setPhaseInversionDisabled(boolean value) {
        celt_enc.setPhaseInversionDisabled(value);
    }

    /**
     * Marks the stream as a low-frequency effects channel, coded in narrowband CELT
     * ({@code OPUS_SET_LFE}).
     *
     * @param value {@code true} for an LFE stream
     */
    void setLfe(boolean value) {
        lfe = value;
        celt_enc.setLfe(value ? 1 : 0);
    }

    /**
     * Sets the surround masking the SILK and CELT rate decisions use ({@code OPUS_SET_ENERGY_MASK}).
     *
     * <p>The array is referenced, not copied; {@link #reset()} clears it.
     *
     * @param value the masking in log2 units, 21 values per channel, or {@code null} for none
     */
    void setEnergyMask(float[] value) {
        energy_masking = value;
        celt_enc.setEnergyMask(value);
    }

    /**
     * Returns the encoder look-ahead ({@code OPUS_GET_LOOKAHEAD}).
     *
     * @return the number of samples at the input rate
     */
    public int lookahead() {
        var value = Fs / 400;
        if (application != OPUS_APPLICATION_RESTRICTED_LOWDELAY) {
            value += delay_compensation;
        }
        return value;
    }

    /**
     * Returns the bitrate the encoder targets ({@code OPUS_GET_BITRATE}).
     *
     * <p>An explicit bitrate is returned as set; {@link OpusDefines#OPUS_AUTO} and
     * {@link OpusDefines#OPUS_BITRATE_MAX} resolve to the rate they stand for at the duration of the
     * previous frame, or of a 2.5 ms frame before the first one.
     *
     * @return the bitrate in bits per second
     */
    public int bitrate() {
        return userBitrateToBitrate(prev_framesize, 1276);
    }

    /**
     * Returns whether the bitrate is variable ({@code OPUS_GET_VBR}).
     *
     * @return {@code true} for variable bitrate
     */
    boolean vbr() {
        return use_vbr != 0;
    }

    /**
     * Returns the final range of the last packet ({@code OPUS_GET_FINAL_RANGE}).
     *
     * @return the unsigned 32-bit final range
     */
    public int finalRange() {
        return rangeFinal;
    }

    /**
     * Returns the input sampling rate.
     *
     * @return the rate in hertz
     */
    public int sampleRate() {
        return Fs;
    }

    /**
     * Returns the number of input channels.
     *
     * @return the channel count
     */
    public int channels() {
        return channels;
    }

    /**
     * Resets the encoder to its initial state, keeping the configuration ({@code OPUS_RESET_STATE}).
     */
    public void reset() {
        analysis.reset();
        stream_channels = channels;
        hybrid_stereo_width_Q14 = 1 << 14;
        prev_HB_gain = 1.0f;
        java.util.Arrays.fill(hp_mem, 0);
        mode = MODE_HYBRID;
        prev_mode = 0;
        prev_channels = 0;
        prev_framesize = 0;
        bandwidth = OPUS_BANDWIDTH_FULLBAND;
        auto_bandwidth = 0;
        silk_bw_switch = 0;
        first = 1;
        width_XX = 0;
        width_XY = 0;
        width_YY = 0;
        width_smoothed_width = 0;
        width_max_follower = 0;
        java.util.Arrays.fill(delay_buffer, 0);
        detected_bandwidth = 0;
        nb_no_activity_ms_Q1 = 0;
        peak_signal_energy = 0;
        nonfinal_frame = false;
        rangeFinal = 0;
        energy_masking = null;
        celt_enc.resetState();
        silk_enc.initEncoder(new SilkEncControl());
        variable_HP_smth2_Q15 = LinLog.lin2log(TuningParameters.VARIABLE_HP_MIN_CUTOFF_HZ) << 8;
    }

    /**
     * Builds the table-of-contents byte of a packet ({@code gen_toc}).
     *
     * @param mode      the coding mode
     * @param framerate the number of frames per second
     * @param bandwidth the audio bandwidth
     * @param channels  the number of coded channels
     * @return the table-of-contents byte
     */
    private static int genToc(int mode, int framerate, int bandwidth, int channels) {
        var period = 0;
        while (framerate < 400) {
            framerate <<= 1;
            period++;
        }
        int toc;
        if (mode == MODE_SILK_ONLY) {
            toc = (bandwidth - OPUS_BANDWIDTH_NARROWBAND) << 5;
            toc |= (period - 2) << 3;
        } else if (mode == MODE_CELT_ONLY) {
            var tmp = bandwidth - OPUS_BANDWIDTH_MEDIUMBAND;
            if (tmp < 0) {
                tmp = 0;
            }
            toc = 0x80;
            toc |= tmp << 5;
            toc |= period << 3;
        } else {
            toc = 0x60;
            toc |= (bandwidth - OPUS_BANDWIDTH_SUPERWIDEBAND) << 4;
            toc |= (period - 2) << 3;
        }
        toc |= (channels == 2 ? 1 : 0) << 2;
        return toc & 0xFF;
    }

    /**
     * Runs a second-order IIR filter in transposed direct form II on one channel
     * ({@code silk_biquad_float}).
     *
     * @param in     the input
     * @param inOff  the index of the first input sample
     * @param B_Q28  the three feed-forward coefficients in Q28
     * @param A_Q28  the two feedback coefficients in Q28
     * @param S      the filter state
     * @param SOff   the index of the channel's two state values
     * @param out    the output, may alias the input
     * @param outOff the index of the first output sample
     * @param len    the number of samples
     * @param stride the distance between consecutive samples
     */
    private static void silkBiquadFloat(float[] in, int inOff, int[] B_Q28, int[] A_Q28, float[] S, int SOff,
                                        float[] out, int outOff, int len, int stride) {
        var A0 = A_Q28[0] * (1.f / (1 << 28));
        var A1 = A_Q28[1] * (1.f / (1 << 28));
        var B0 = B_Q28[0] * (1.f / (1 << 28));
        var B1 = B_Q28[1] * (1.f / (1 << 28));
        var B2 = B_Q28[2] * (1.f / (1 << 28));
        for (var k = 0; k < len; k++) {
            var inval = in[inOff + k * stride];
            var vout = S[SOff] + B0 * inval;
            S[SOff] = S[SOff + 1] - vout * A0 + B1 * inval;
            S[SOff + 1] = -vout * A1 + B2 * inval + MathOps.VERY_SMALL;
            out[outOff + k * stride] = vout;
        }
    }

    /**
     * Applies the variable high-pass filter of the VoIP application ({@code hp_cutoff}).
     *
     * @param in        the interleaved input
     * @param inOff     the index of the first input sample
     * @param cutoff_Hz the cutoff frequency
     * @param out       the interleaved output
     * @param outOff    the index of the first output sample
     * @param hp_mem    the filter state, two values per channel
     * @param len       the number of samples per channel
     * @param channels  the number of channels
     * @param Fs        the sampling rate
     */
    private void hpCutoff(float[] in, int inOff, int cutoff_Hz, float[] out, int outOff, float[] hp_mem,
                          int len, int channels, int Fs) {
        var B_Q28 = hpB_Q28;
        var A_Q28 = hpA_Q28;
        var Fc_Q19 = SigProcFix.div32_16(SigProcFix.smulbb(SigProcFix.fixConst(1.5 * 3.14159 / 1000, 19), cutoff_Hz), Fs / 1000);
        var r_Q28 = SigProcFix.fixConst(1.0, 28) - SigProcFix.fixConst(0.92, 9) * Fc_Q19;
        B_Q28[0] = r_Q28;
        B_Q28[1] = (-r_Q28) << 1;
        B_Q28[2] = r_Q28;
        var r_Q22 = r_Q28 >> 6;
        A_Q28[0] = SigProcFix.smulww(r_Q22, SigProcFix.smulww(Fc_Q19, Fc_Q19) - SigProcFix.fixConst(2.0, 22));
        A_Q28[1] = SigProcFix.smulww(r_Q22, r_Q22);
        silkBiquadFloat(in, inOff, B_Q28, A_Q28, hp_mem, 0, out, outOff, len, channels);
        if (channels == 2) {
            silkBiquadFloat(in, inOff + 1, B_Q28, A_Q28, hp_mem, 2, out, outOff + 1, len, channels);
        }
    }

    /**
     * Removes the DC offset with a one-pole high-pass filter ({@code dc_reject}).
     *
     * @param in        the interleaved input
     * @param inOff     the index of the first input sample
     * @param cutoff_Hz the cutoff frequency
     * @param out       the interleaved output
     * @param outOff    the index of the first output sample
     * @param hp_mem    the filter state, using indices 0 and 2
     * @param len       the number of samples per channel
     * @param channels  the number of channels
     * @param Fs        the sampling rate
     */
    private static void dcReject(float[] in, int inOff, int cutoff_Hz, float[] out, int outOff, float[] hp_mem,
                                 int len, int channels, int Fs) {
        var coef = 6.3f * cutoff_Hz / Fs;
        var coef2 = 1 - coef;
        if (channels == 2) {
            var m0 = hp_mem[0];
            var m2 = hp_mem[2];
            for (var i = 0; i < len; i++) {
                var x0 = in[inOff + 2 * i];
                var x1 = in[inOff + 2 * i + 1];
                var out0 = x0 - m0;
                var out1 = x1 - m2;
                m0 = coef * x0 + MathOps.VERY_SMALL + coef2 * m0;
                m2 = coef * x1 + MathOps.VERY_SMALL + coef2 * m2;
                out[outOff + 2 * i] = out0;
                out[outOff + 2 * i + 1] = out1;
            }
            hp_mem[0] = m0;
            hp_mem[2] = m2;
        } else {
            var m0 = hp_mem[0];
            for (var i = 0; i < len; i++) {
                var x = in[inOff + i];
                var y = x - m0;
                m0 = coef * x + MathOps.VERY_SMALL + coef2 * m0;
                out[outOff + i] = y;
            }
            hp_mem[0] = m0;
        }
    }

    /**
     * Cross-fades the side signal of a stereo frame between two widths ({@code stereo_fade}).
     *
     * @param in         the interleaved input
     * @param out        the interleaved output, may alias the input
     * @param g1         the width at the start
     * @param g2         the width at the end
     * @param overlap48  the fade length at 48 kHz
     * @param frame_size the number of samples per channel
     * @param channels   the number of channels (2)
     * @param window     the CELT window
     * @param Fs         the sampling rate
     */
    private static void stereoFade(float[] in, float[] out, float g1, float g2, int overlap48, int frame_size,
                                   int channels, float[] window, int Fs) {
        var inc = 48000 / Fs;
        var overlap = overlap48 / inc;
        g1 = 1.0f - g1;
        g2 = 1.0f - g2;
        int i;
        for (i = 0; i < overlap; i++) {
            var w = window[i * inc] * window[i * inc];
            var g = w * g2 + (1.0f - w) * g1;
            var diff = .5f * (in[i * channels] - in[i * channels + 1]);
            diff = g * diff;
            out[i * channels] = out[i * channels] - diff;
            out[i * channels + 1] = out[i * channels + 1] + diff;
        }
        for (; i < frame_size; i++) {
            var diff = .5f * (in[i * channels] - in[i * channels + 1]);
            diff = g2 * diff;
            out[i * channels] = out[i * channels] - diff;
            out[i * channels + 1] = out[i * channels + 1] + diff;
        }
    }

    /**
     * Cross-fades the gain of a frame ({@code gain_fade}).
     *
     * @param in         the interleaved input
     * @param inOff      the index of the first input sample
     * @param out        the interleaved output, may alias the input
     * @param outOff     the index of the first output sample
     * @param g1         the gain at the start
     * @param g2         the gain at the end
     * @param overlap48  the fade length at 48 kHz
     * @param frame_size the number of samples per channel
     * @param channels   the number of channels
     * @param window     the CELT window
     * @param Fs         the sampling rate
     */
    private static void gainFade(float[] in, int inOff, float[] out, int outOff, float g1, float g2, int overlap48,
                                 int frame_size, int channels, float[] window, int Fs) {
        var inc = 48000 / Fs;
        var overlap = overlap48 / inc;
        if (channels == 1) {
            for (var i = 0; i < overlap; i++) {
                var w = window[i * inc] * window[i * inc];
                var g = w * g2 + (1.0f - w) * g1;
                out[outOff + i] = g * in[inOff + i];
            }
        } else {
            for (var i = 0; i < overlap; i++) {
                var w = window[i * inc] * window[i * inc];
                var g = w * g2 + (1.0f - w) * g1;
                out[outOff + i * 2] = g * in[inOff + i * 2];
                out[outOff + i * 2 + 1] = g * in[inOff + i * 2 + 1];
            }
        }
        var c = 0;
        do {
            for (var i = overlap; i < frame_size; i++) {
                out[outOff + i * channels + c] = g2 * in[inOff + i * channels + c];
            }
        } while (++c < channels);
    }

    /**
     * Returns the bitrate to use for a frame ({@code user_bitrate_to_bitrate}).
     *
     * @param frame_size     the frame size, or {@code 0}
     * @param max_data_bytes the packet size limit
     * @return the bitrate in bits per second
     */
    private int userBitrateToBitrate(int frame_size, int max_data_bytes) {
        if (frame_size == 0) {
            frame_size = Fs / 400;
        }
        if (user_bitrate_bps == OPUS_AUTO) {
            return 60 * Fs / frame_size + Fs * channels;
        } else if (user_bitrate_bps == OPUS_BITRATE_MAX) {
            return max_data_bytes * 8 * Fs / frame_size;
        } else {
            return user_bitrate_bps;
        }
    }

    /**
     * Estimates how wide the stereo image is, from 0 (mono) to 1 ({@code compute_stereo_width}).
     *
     * @param pcm        the interleaved stereo input
     * @param pcmOff     the index of the first sample
     * @param frame_size the number of samples per channel
     * @param Fs         the sampling rate
     * @return the smoothed width estimate
     */
    private float computeStereoWidth(float[] pcm, int pcmOff, int frame_size, int Fs) {
        var frame_rate = Fs / frame_size;
        var short_alpha = 1.0f - 25f * 1.0f / Math.max(50, frame_rate);
        var xx = 0f;
        var xy = 0f;
        var yy = 0f;
        for (var i = 0; i < frame_size - 3; i += 4) {
            var x = pcm[pcmOff + 2 * i];
            var y = pcm[pcmOff + 2 * i + 1];
            var pxx = x * x;
            var pxy = x * y;
            var pyy = y * y;
            x = pcm[pcmOff + 2 * i + 2];
            y = pcm[pcmOff + 2 * i + 3];
            pxx += x * x;
            pxy += x * y;
            pyy += y * y;
            x = pcm[pcmOff + 2 * i + 4];
            y = pcm[pcmOff + 2 * i + 5];
            pxx += x * x;
            pxy += x * y;
            pyy += y * y;
            x = pcm[pcmOff + 2 * i + 6];
            y = pcm[pcmOff + 2 * i + 7];
            pxx += x * x;
            pxy += x * y;
            pyy += y * y;
            xx += pxx;
            xy += pxy;
            yy += pyy;
        }
        if (!(xx < 1e9f) || Float.isNaN(xx) || !(yy < 1e9f) || Float.isNaN(yy)) {
            xy = xx = yy = 0;
        }
        width_XX += short_alpha * (xx - width_XX);
        width_XY += short_alpha * (xy - width_XY);
        width_YY += short_alpha * (yy - width_YY);
        width_XX = 0 > width_XX ? 0 : width_XX;
        width_XY = 0 > width_XY ? 0 : width_XY;
        width_YY = 0 > width_YY ? 0 : width_YY;
        if ((width_XX > width_YY ? width_XX : width_YY) > 8e-4f) {
            var sqrt_xx = MathOps.celtSqrt(width_XX);
            var sqrt_yy = MathOps.celtSqrt(width_YY);
            var qrrt_xx = MathOps.celtSqrt(sqrt_xx);
            var qrrt_yy = MathOps.celtSqrt(sqrt_yy);
            width_XY = width_XY < sqrt_xx * sqrt_yy ? width_XY : sqrt_xx * sqrt_yy;
            var corr = width_XY / (MathOps.EPSILON + sqrt_xx * sqrt_yy);
            var ldiff = 1.0f * Math.abs(qrrt_xx - qrrt_yy) / (MathOps.EPSILON + qrrt_xx + qrrt_yy);
            var width = MathOps.celtSqrt(1.f - corr * corr) * ldiff;
            width_smoothed_width += (width - width_smoothed_width) / frame_rate;
            var follower = width_max_follower - .02f / frame_rate;
            width_max_follower = follower > width_smoothed_width ? follower : width_smoothed_width;
        }
        var scaled = 20 * width_max_follower;
        return 1.0f < scaled ? 1.0f : scaled;
    }

    /**
     * Decides whether to code in-band FEC, lowering the bandwidth to afford it when the loss is
     * high ({@code decide_fec}).
     *
     * @param useInBandFEC    whether FEC is enabled
     * @param PacketLoss_perc the expected loss in percent
     * @param last_fec        whether the previous frame coded FEC
     * @param mode            the coding mode
     * @param bandwidthRef    holds the bandwidth at index 0, possibly lowered
     * @param rate            the equivalent bitrate
     * @return {@code 1} to code FEC
     */
    private static int decideFec(int useInBandFEC, int PacketLoss_perc, int last_fec, int mode, int[] bandwidthRef, int rate) {
        if (useInBandFEC == 0 || PacketLoss_perc == 0 || mode == MODE_CELT_ONLY) {
            return 0;
        }
        var orig_bandwidth = bandwidthRef[0];
        for (; ; ) {
            var LBRR_rate_thres_bps = fec_thresholds[2 * (bandwidthRef[0] - OPUS_BANDWIDTH_NARROWBAND)];
            var hysteresis = fec_thresholds[2 * (bandwidthRef[0] - OPUS_BANDWIDTH_NARROWBAND) + 1];
            if (last_fec == 1) {
                LBRR_rate_thres_bps -= hysteresis;
            }
            if (last_fec == 0) {
                LBRR_rate_thres_bps += hysteresis;
            }
            LBRR_rate_thres_bps = SigProcFix.smulwb(LBRR_rate_thres_bps * (125 - Math.min(PacketLoss_perc, 25)),
                    SigProcFix.fixConst(0.01, 16));
            if (rate > LBRR_rate_thres_bps) {
                return 1;
            } else if (PacketLoss_perc <= 5) {
                return 0;
            } else if (bandwidthRef[0] > OPUS_BANDWIDTH_NARROWBAND) {
                bandwidthRef[0]--;
            } else {
                break;
            }
        }
        bandwidthRef[0] = orig_bandwidth;
        return 0;
    }

    /**
     * Returns the share of a hybrid bitrate given to SILK ({@code compute_silk_rate_for_hybrid}).
     *
     * @param rate      the total bitrate
     * @param bandwidth the audio bandwidth
     * @param frame20ms {@code 1} for 20 ms frames
     * @param vbr       whether the bitrate is variable
     * @param fec       whether FEC is coded
     * @param channels  the number of coded channels
     * @return the SILK bitrate
     */
    private static int computeSilkRateForHybrid(int rate, int bandwidth, int frame20ms, int vbr, int fec, int channels) {
        rate /= channels;
        var entry = 1 + frame20ms + 2 * fec;
        var N = rate_table.length;
        int i;
        for (i = 1; i < N; i++) {
            if (rate_table[i][0] > rate) {
                break;
            }
        }
        int silk_rate;
        if (i == N) {
            silk_rate = rate_table[i - 1][entry];
            silk_rate += (rate - rate_table[i - 1][0]) / 2;
        } else {
            var lo = rate_table[i - 1][entry];
            var hi = rate_table[i][entry];
            var x0 = rate_table[i - 1][0];
            var x1 = rate_table[i][0];
            silk_rate = (lo * (x1 - rate) + hi * (rate - x0)) / (x1 - x0);
        }
        if (vbr == 0) {
            silk_rate += 100;
        }
        if (bandwidth == OPUS_BANDWIDTH_SUPERWIDEBAND) {
            silk_rate += 300;
        }
        silk_rate *= channels;
        if (channels == 2 && rate >= 12000) {
            silk_rate -= 1000;
        }
        return silk_rate;
    }

    /**
     * Returns the bitrate equivalent to 20 ms VBR frames at complexity 10
     * ({@code compute_equiv_rate}).
     *
     * @param bitrate the bitrate in bits per second
     * @param channels the number of coded channels
     * @param frame_rate the frames per second
     * @param vbr whether the bitrate is variable
     * @param mode the coding mode, or {@code 0} when unknown
     * @param complexity the complexity, 0 to 10
     * @param loss the expected loss in percent
     * @return the equivalent bitrate
     */
    private static int computeEquivRate(int bitrate, int channels, int frame_rate, int vbr, int mode, int complexity, int loss) {
        var equiv = bitrate;
        if (frame_rate > 50) {
            equiv -= (40 * channels + 20) * (frame_rate - 50);
        }
        if (vbr == 0) {
            equiv -= equiv / 12;
        }
        equiv = equiv * (90 + complexity) / 100;
        if (mode == MODE_SILK_ONLY || mode == MODE_HYBRID) {
            if (complexity < 2) {
                equiv = equiv * 4 / 5;
            }
            equiv -= equiv * loss / (6 * loss + 10);
        } else if (mode == MODE_CELT_ONLY) {
            if (complexity < 5) {
                equiv = equiv * 9 / 10;
            }
        } else {
            equiv -= equiv * loss / (12 * loss + 20);
        }
        return equiv;
    }

    /**
     * Returns whether a frame is digital silence at the given bit depth
     * ({@code is_digital_silence}).
     *
     * @param pcm        the interleaved samples
     * @param off        the index of the first sample
     * @param frame_size the number of samples per channel
     * @param channels   the number of channels
     * @param lsb_depth  the effective bit depth
     * @return whether every sample is below one least significant bit
     */
    static boolean isDigitalSilence(float[] pcm, int off, int frame_size, int channels, int lsb_depth) {
        var sample_max = MathOps.celtMaxabs16(pcm, off, frame_size * channels);
        return sample_max <= (float) 1 / (1 << lsb_depth);
    }

    /**
     * Returns the mean energy of a frame ({@code compute_frame_energy}).
     *
     * @param pcm        the interleaved samples
     * @param off        the index of the first sample
     * @param frame_size the number of samples per channel
     * @param channels   the number of channels
     * @return the energy per sample
     */
    private static float computeFrameEnergy(float[] pcm, int off, int frame_size, int channels) {
        var len = frame_size * channels;
        return Pitch.celtInnerProd(pcm, off, pcm, off, len) / len;
    }

    /**
     * Decides whether a frame is sent as a DTX frame ({@code decide_dtx_mode}).
     *
     * @param activity         whether the frame is active
     * @param frame_size_ms_Q1 the frame duration in half milliseconds
     * @return whether to send a DTX frame
     */
    private boolean decideDtxMode(boolean activity, int frame_size_ms_Q1) {
        if (!activity) {
            nb_no_activity_ms_Q1 += frame_size_ms_Q1;
            if (nb_no_activity_ms_Q1 > Define.NB_SPEECH_FRAMES_BEFORE_DTX * 20 * 2) {
                if (nb_no_activity_ms_Q1 <= (Define.NB_SPEECH_FRAMES_BEFORE_DTX + Define.MAX_CONSECUTIVE_DTX) * 20 * 2) {
                    return true;
                } else {
                    nb_no_activity_ms_Q1 = Define.NB_SPEECH_FRAMES_BEFORE_DTX * 20 * 2;
                }
            }
        } else {
            nb_no_activity_ms_Q1 = 0;
        }
        return false;
    }

    /**
     * Returns the size of a mode-transition redundancy frame ({@code compute_redundancy_bytes}).
     *
     * @param max_data_bytes the packet size limit
     * @param bitrate_bps    the bitrate
     * @param frame_rate     the frames per second
     * @param channels       the number of coded channels
     * @return the redundancy size in bytes, or {@code 0} when not worth it
     */
    private static int computeRedundancyBytes(int max_data_bytes, int bitrate_bps, int frame_rate, int channels) {
        var base_bits = 40 * channels + 20;
        var redundancy_rate = bitrate_bps + base_bits * (200 - frame_rate);
        redundancy_rate = 3 * redundancy_rate / 2;
        var redundancy_bytes = redundancy_rate / 1600;
        var available_bits = max_data_bytes * 8 - 2 * base_bits;
        var redundancy_bytes_cap = (available_bits * 240 / (240 + 48000 / frame_rate) + base_bits) / 8;
        redundancy_bytes = Math.min(redundancy_bytes, redundancy_bytes_cap);
        if (redundancy_bytes > 4 + 8 * channels) {
            redundancy_bytes = Math.min(257, redundancy_bytes);
        } else {
            redundancy_bytes = 0;
        }
        return redundancy_bytes;
    }

    /**
     * Encodes one frame of float input into one packet ({@code opus_encode_float}).
     *
     * @param pcm            the interleaved input, nominally in {@code [-1, 1]}
     * @param pcmOff         the index of the first sample
     * @param frame_size     the number of samples per channel: 2.5, 5, 10, 20, 40, 60, 80, 100 or
     *                       120 ms
     * @param data           the output buffer
     * @param dataOff        the index of the first output byte
     * @param out_data_bytes the space available, at most 1276 bytes are used
     * @return the packet length, or a negative error code
     */
    public int encodeFloat(float[] pcm, int pcmOff, int frame_size, byte[] data, int dataOff, int out_data_bytes) {
        var fs = frameSizeSelect(frame_size, Fs);
        if (fs <= 0) {
            return OPUS_BAD_ARG;
        }
        return encodeNative(pcm, pcmOff, fs, data, dataOff, out_data_bytes, 24, pcm, pcmOff, frame_size, 0, -2, channels);
    }

    /**
     * Encodes one frame of 16-bit input into one packet ({@code opus_encode}).
     *
     * <p>Each sample is scaled by {@code 1/32768} and the frame is coded as 16-bit input, so the
     * effective bit depth is at most 16 whatever {@link #setLsbDepth(int)} says.
     *
     * @implNote This implementation feeds the scaled samples to the tonality analysis instead of the
     * 16-bit originals the reference hands to {@code downmix_int}; the analysis multiplies its input
     * by {@code 32768} first, which restores the originals exactly. It also runs the float API's
     * guard against non-finite and huge input, which 16-bit input can never trip. The packets are
     * therefore identical to the reference's.
     *
     * @param pcm            the interleaved input
     * @param pcmOff         the index of the first sample
     * @param frame_size     the number of samples per channel: 2.5, 5, 10, 20, 40, 60, 80, 100 or
     *                       120 ms
     * @param data           the output buffer
     * @param dataOff        the index of the first output byte
     * @param out_data_bytes the space available, at most 1276 bytes are used
     * @return the packet length, or a negative error code
     */
    public int encode(short[] pcm, int pcmOff, int frame_size, byte[] data, int dataOff, int out_data_bytes) {
        var fs = frameSizeSelect(frame_size, Fs);
        if (fs <= 0) {
            return OPUS_BAD_ARG;
        }
        var in = pcm_int16;
        var count = fs * channels;
        for (var i = 0; i < count; i++) {
            in[i] = (1.0f / 32768) * pcm[pcmOff + i];
        }
        return encodeNative(in, 0, fs, data, dataOff, out_data_bytes, 16, in, 0, frame_size, 0, -2, channels);
    }

    /**
     * Validates a frame size ({@code frame_size_select} with {@code OPUS_FRAMESIZE_ARG}).
     *
     * @param frame_size the frame size in samples
     * @param Fs         the sampling rate
     * @return the frame size, or {@code -1} when it is not a valid Opus duration
     */
    static int frameSizeSelect(int frame_size, int Fs) {
        if (frame_size < Fs / 400) {
            return -1;
        }
        var new_size = frame_size;
        if (400 * new_size != Fs && 200 * new_size != Fs && 100 * new_size != Fs
                && 50 * new_size != Fs && 25 * new_size != Fs && 50 * new_size != 3 * Fs
                && 50 * new_size != 4 * Fs && 50 * new_size != 5 * Fs && 50 * new_size != 6 * Fs) {
            return -1;
        }
        return new_size;
    }

    /**
     * Encodes one frame, choosing the mode, bandwidth and channels ({@code opus_encode_native}).
     *
     * @param pcm                 the interleaved input
     * @param pcmOff              the index of the first sample
     * @param frame_size          the frame size in samples
     * @param data                the output buffer
     * @param dataOff             the index of the first output byte
     * @param out_data_bytes      the space available
     * @param lsb_depth           the effective bit depth of the input
     * @param analysis_pcm        the interleaved input of the tonality analysis, which a multistream
     *                            encoder shares between its streams
     * @param analysisOff         the index of the first sample of {@code analysis_pcm}
     * @param analysis_frame_size the number of new samples for the analysis
     * @param c1                  the first channel of {@code analysis_pcm} the analysis mixes
     * @param c2                  the second channel, {@code -1} for none or {@code -2} for all others
     * @param analysis_channels   the number of interleaved channels of {@code analysis_pcm}
     * @return the packet length, or a negative error code
     */
    int encodeNative(float[] pcm, int pcmOff, int frame_size, byte[] data, int dataOff, int out_data_bytes,
                     int lsb_depth, float[] analysis_pcm, int analysisOff, int analysis_frame_size, int c1, int c2,
                     int analysis_channels) {
        var prefill = 0;
        var redundancy = 0;
        var celt_to_silk = 0;
        var to_celt = 0;
        int voice_est;
        int equiv_rate;
        int frame_rate;
        int max_rate;
        int curr_bandwidth;
        int max_data_bytes;
        var cbr_bytes = -1;
        float stereo_width;
        var analysis_read_pos_bak = -1;
        var analysis_read_subframe_bak = -1;
        var is_silence = false;
        int ret;
        max_data_bytes = Math.min(1276, out_data_bytes);
        rangeFinal = 0;
        if (frame_size <= 0 || max_data_bytes <= 0) {
            return OPUS_BAD_ARG;
        }
        if (max_data_bytes == 1 && Fs == frame_size * 10) {
            return OPUS_BUFFER_TOO_SMALL;
        }
        lsb_depth = Math.min(lsb_depth, this.lsb_depth);
        var celt_mode = celt_enc.getMode();
        analysis_info.valid = 0;
        if (silk_mode.complexity >= 7 && Fs >= 16000) {
            is_silence = isDigitalSilence(pcm, pcmOff, frame_size, channels, lsb_depth);
            analysis_read_pos_bak = analysis.read_pos;
            analysis_read_subframe_bak = analysis.read_subframe;
            analysis.runAnalysis(analysis_pcm, analysisOff, analysis_frame_size, frame_size, c1, c2, analysis_channels, Fs,
                    lsb_depth, analysis_info);
            if (!is_silence && analysis_info.activity_probability > Define.DTX_ACTIVITY_THRESHOLD) {
                var decayed = 0.999f * peak_signal_energy;
                var energy = computeFrameEnergy(pcm, pcmOff, frame_size, channels);
                peak_signal_energy = decayed > energy ? decayed : energy;
            }
        } else if (analysis.initialized) {
            analysis.reset();
        }
        if (!is_silence) {
            voice_ratio = -1;
        }
        detected_bandwidth = 0;
        if (analysis_info.valid != 0) {
            if (signal_type == OPUS_AUTO) {
                float prob;
                if (prev_mode == 0) {
                    prob = analysis_info.music_prob;
                } else if (prev_mode == MODE_CELT_ONLY) {
                    prob = analysis_info.music_prob_max;
                } else {
                    prob = analysis_info.music_prob_min;
                }
                voice_ratio = (int) Math.floor(.5 + 100 * (1 - prob));
            }
            var analysis_bandwidth = analysis_info.bandwidth;
            if (analysis_bandwidth <= 12) {
                detected_bandwidth = OPUS_BANDWIDTH_NARROWBAND;
            } else if (analysis_bandwidth <= 14) {
                detected_bandwidth = OPUS_BANDWIDTH_MEDIUMBAND;
            } else if (analysis_bandwidth <= 16) {
                detected_bandwidth = OPUS_BANDWIDTH_WIDEBAND;
            } else if (analysis_bandwidth <= 18) {
                detected_bandwidth = OPUS_BANDWIDTH_SUPERWIDEBAND;
            } else {
                detected_bandwidth = OPUS_BANDWIDTH_FULLBAND;
            }
        }
        if (channels == 2 && force_channels != 1) {
            stereo_width = computeStereoWidth(pcm, pcmOff, frame_size, Fs);
        } else {
            stereo_width = 0;
        }
        bitrate_bps = userBitrateToBitrate(frame_size, max_data_bytes);
        frame_rate = Fs / frame_size;
        if (use_vbr == 0) {
            var frame_rate12 = 12 * Fs / frame_size;
            cbr_bytes = Math.min((12 * bitrate_bps / 8 + frame_rate12 / 2) / frame_rate12, max_data_bytes);
            bitrate_bps = cbr_bytes * frame_rate12 * 8 / 12;
            max_data_bytes = Math.max(1, cbr_bytes);
        }
        if (max_data_bytes < 3 || bitrate_bps < 3 * frame_rate * 8
                || (frame_rate < 50 && (max_data_bytes * frame_rate < 300 || bitrate_bps < 2400))) {
            var tocmode = mode;
            var bw = bandwidth == 0 ? OPUS_BANDWIDTH_NARROWBAND : bandwidth;
            var packet_code = 0;
            var num_multiframes = 0;
            if (tocmode == 0) {
                tocmode = MODE_SILK_ONLY;
            }
            if (frame_rate > 100) {
                tocmode = MODE_CELT_ONLY;
            }
            if (frame_rate == 25 && tocmode != MODE_SILK_ONLY) {
                frame_rate = 50;
                packet_code = 1;
            }
            if (frame_rate <= 16) {
                if (out_data_bytes == 1 || (tocmode == MODE_SILK_ONLY && frame_rate != 10)) {
                    tocmode = MODE_SILK_ONLY;
                    packet_code = frame_rate <= 12 ? 1 : 0;
                    frame_rate = frame_rate == 12 ? 25 : 16;
                } else {
                    num_multiframes = 50 / frame_rate;
                    frame_rate = 50;
                    packet_code = 3;
                }
            }
            if (tocmode == MODE_SILK_ONLY && bw > OPUS_BANDWIDTH_WIDEBAND) {
                bw = OPUS_BANDWIDTH_WIDEBAND;
            } else if (tocmode == MODE_CELT_ONLY && bw == OPUS_BANDWIDTH_MEDIUMBAND) {
                bw = OPUS_BANDWIDTH_NARROWBAND;
            } else if (tocmode == MODE_HYBRID && bw <= OPUS_BANDWIDTH_SUPERWIDEBAND) {
                bw = OPUS_BANDWIDTH_SUPERWIDEBAND;
            }
            data[dataOff] = (byte) genToc(tocmode, frame_rate, bw, stream_channels);
            data[dataOff] |= (byte) packet_code;
            ret = packet_code <= 1 ? 1 : 2;
            max_data_bytes = Math.max(max_data_bytes, ret);
            if (packet_code == 3) {
                data[dataOff + 1] = (byte) num_multiframes;
            }
            if (use_vbr == 0) {
                ret = padder.pad(data, dataOff, ret, max_data_bytes);
                if (ret == OPUS_OK) {
                    ret = max_data_bytes;
                } else {
                    ret = OPUS_INTERNAL_ERROR;
                }
            }
            return ret;
        }
        max_rate = frame_rate * max_data_bytes * 8;
        equiv_rate = computeEquivRate(bitrate_bps, channels, Fs / frame_size, use_vbr, 0,
                silk_mode.complexity, silk_mode.packetLossPercentage);
        if (signal_type == OPUS_SIGNAL_VOICE) {
            voice_est = 127;
        } else if (signal_type == OPUS_SIGNAL_MUSIC) {
            voice_est = 0;
        } else if (voice_ratio >= 0) {
            voice_est = voice_ratio * 327 >> 8;
            if (application == OPUS_APPLICATION_AUDIO) {
                voice_est = Math.min(voice_est, 115);
            }
        } else if (application == OPUS_APPLICATION_VOIP) {
            voice_est = 115;
        } else {
            voice_est = 48;
        }
        if (force_channels != OPUS_AUTO && channels == 2) {
            stream_channels = force_channels;
        } else {
            if (channels == 2) {
                var stereo_threshold = stereo_music_threshold
                        + ((voice_est * voice_est * (stereo_voice_threshold - stereo_music_threshold)) >> 14);
                if (stream_channels == 2) {
                    stereo_threshold -= 1000;
                } else {
                    stereo_threshold += 1000;
                }
                stream_channels = equiv_rate > stereo_threshold ? 2 : 1;
            } else {
                stream_channels = channels;
            }
        }
        equiv_rate = computeEquivRate(bitrate_bps, stream_channels, Fs / frame_size, use_vbr, 0,
                silk_mode.complexity, silk_mode.packetLossPercentage);
        silk_mode.useDTX = use_dtx != 0 && !(analysis_info.valid != 0 || is_silence) ? 1 : 0;
        if (application == OPUS_APPLICATION_RESTRICTED_LOWDELAY) {
            mode = MODE_CELT_ONLY;
        } else if (user_forced_mode == OPUS_AUTO) {
            var mode_voice = (int) ((1.0f - stereo_width) * mode_thresholds[0][0] + stereo_width * mode_thresholds[1][0]);
            var mode_music = (int) ((1.0f - stereo_width) * mode_thresholds[1][1] + stereo_width * mode_thresholds[1][1]);
            var threshold = mode_music + ((voice_est * voice_est * (mode_voice - mode_music)) >> 14);
            if (application == OPUS_APPLICATION_VOIP) {
                threshold += 8000;
            }
            if (prev_mode == MODE_CELT_ONLY) {
                threshold -= 4000;
            } else if (prev_mode > 0) {
                threshold += 4000;
            }
            mode = equiv_rate >= threshold ? MODE_CELT_ONLY : MODE_SILK_ONLY;
            if (silk_mode.useInBandFEC != 0 && silk_mode.packetLossPercentage > (128 - voice_est) >> 4
                    && (fec_config != 2 || voice_est > 25)) {
                mode = MODE_SILK_ONLY;
            }
            if (silk_mode.useDTX != 0 && voice_est > 100) {
                mode = MODE_SILK_ONLY;
            }
            if (max_data_bytes < (frame_rate > 50 ? 9000 : 6000) * frame_size / (Fs * 8)) {
                mode = MODE_CELT_ONLY;
            }
        } else {
            mode = user_forced_mode;
        }
        if (mode != MODE_CELT_ONLY && frame_size < Fs / 100) {
            mode = MODE_CELT_ONLY;
        }
        if (lfe) {
            mode = MODE_CELT_ONLY;
        }
        if (prev_mode > 0 && ((mode != MODE_CELT_ONLY && prev_mode == MODE_CELT_ONLY)
                || (mode == MODE_CELT_ONLY && prev_mode != MODE_CELT_ONLY))) {
            redundancy = 1;
            celt_to_silk = mode != MODE_CELT_ONLY ? 1 : 0;
            if (celt_to_silk == 0) {
                if (frame_size >= Fs / 100) {
                    mode = prev_mode;
                    to_celt = 1;
                } else {
                    redundancy = 0;
                }
            }
        }
        if (stream_channels == 1 && prev_channels == 2 && silk_mode.toMono == 0
                && mode != MODE_CELT_ONLY && prev_mode != MODE_CELT_ONLY) {
            silk_mode.toMono = 1;
            stream_channels = 2;
        } else {
            silk_mode.toMono = 0;
        }
        equiv_rate = computeEquivRate(bitrate_bps, stream_channels, Fs / frame_size, use_vbr, mode,
                silk_mode.complexity, silk_mode.packetLossPercentage);
        if (mode != MODE_CELT_ONLY && prev_mode == MODE_CELT_ONLY) {
            silk_enc.initEncoder(new SilkEncControl());
            prefill = 1;
        }
        if (mode == MODE_CELT_ONLY || first != 0 || silk_mode.allowBandwidthSwitch != 0) {
            int[] voice_bandwidth_thresholds;
            int[] music_bandwidth_thresholds;
            var bandwidth_thresholds = this.bandwidth_thresholds;
            var bandwidth = OPUS_BANDWIDTH_FULLBAND;
            if (channels == 2 && force_channels != 1) {
                voice_bandwidth_thresholds = stereo_voice_bandwidth_thresholds;
                music_bandwidth_thresholds = stereo_music_bandwidth_thresholds;
            } else {
                voice_bandwidth_thresholds = mono_voice_bandwidth_thresholds;
                music_bandwidth_thresholds = mono_music_bandwidth_thresholds;
            }
            for (var i = 0; i < 8; i++) {
                bandwidth_thresholds[i] = music_bandwidth_thresholds[i]
                        + ((voice_est * voice_est * (voice_bandwidth_thresholds[i] - music_bandwidth_thresholds[i])) >> 14);
            }
            do {
                var threshold = bandwidth_thresholds[2 * (bandwidth - OPUS_BANDWIDTH_MEDIUMBAND)];
                var hysteresis = bandwidth_thresholds[2 * (bandwidth - OPUS_BANDWIDTH_MEDIUMBAND) + 1];
                if (first == 0) {
                    if (auto_bandwidth >= bandwidth) {
                        threshold -= hysteresis;
                    } else {
                        threshold += hysteresis;
                    }
                }
                if (equiv_rate >= threshold) {
                    break;
                }
            } while (--bandwidth > OPUS_BANDWIDTH_NARROWBAND);
            if (bandwidth == OPUS_BANDWIDTH_MEDIUMBAND) {
                bandwidth = OPUS_BANDWIDTH_WIDEBAND;
            }
            this.bandwidth = auto_bandwidth = bandwidth;
            if (first == 0 && mode != MODE_CELT_ONLY && silk_mode.inWBmodeWithoutVariableLP == 0
                    && this.bandwidth > OPUS_BANDWIDTH_WIDEBAND) {
                this.bandwidth = OPUS_BANDWIDTH_WIDEBAND;
            }
        }
        if (bandwidth > max_bandwidth) {
            bandwidth = max_bandwidth;
        }
        if (user_bandwidth != OPUS_AUTO) {
            bandwidth = user_bandwidth;
        }
        if (mode != MODE_CELT_ONLY && max_rate < 15000) {
            bandwidth = Math.min(bandwidth, OPUS_BANDWIDTH_WIDEBAND);
        }
        if (Fs <= 24000 && bandwidth > OPUS_BANDWIDTH_SUPERWIDEBAND) {
            bandwidth = OPUS_BANDWIDTH_SUPERWIDEBAND;
        }
        if (Fs <= 16000 && bandwidth > OPUS_BANDWIDTH_WIDEBAND) {
            bandwidth = OPUS_BANDWIDTH_WIDEBAND;
        }
        if (Fs <= 12000 && bandwidth > OPUS_BANDWIDTH_MEDIUMBAND) {
            bandwidth = OPUS_BANDWIDTH_MEDIUMBAND;
        }
        if (Fs <= 8000 && bandwidth > OPUS_BANDWIDTH_NARROWBAND) {
            bandwidth = OPUS_BANDWIDTH_NARROWBAND;
        }
        if (detected_bandwidth != 0 && user_bandwidth == OPUS_AUTO) {
            int min_detected_bandwidth;
            if (equiv_rate <= 18000 * stream_channels && mode == MODE_CELT_ONLY) {
                min_detected_bandwidth = OPUS_BANDWIDTH_NARROWBAND;
            } else if (equiv_rate <= 24000 * stream_channels && mode == MODE_CELT_ONLY) {
                min_detected_bandwidth = OPUS_BANDWIDTH_MEDIUMBAND;
            } else if (equiv_rate <= 30000 * stream_channels) {
                min_detected_bandwidth = OPUS_BANDWIDTH_WIDEBAND;
            } else if (equiv_rate <= 44000 * stream_channels) {
                min_detected_bandwidth = OPUS_BANDWIDTH_SUPERWIDEBAND;
            } else {
                min_detected_bandwidth = OPUS_BANDWIDTH_FULLBAND;
            }
            detected_bandwidth = Math.max(detected_bandwidth, min_detected_bandwidth);
            bandwidth = Math.min(bandwidth, detected_bandwidth);
        }
        var bandwidthRef = this.bandwidthRef;
        bandwidthRef[0] = bandwidth;
        silk_mode.LBRR_coded = decideFec(silk_mode.useInBandFEC, silk_mode.packetLossPercentage,
                silk_mode.LBRR_coded, mode, bandwidthRef, equiv_rate);
        bandwidth = bandwidthRef[0];
        celt_enc.setLsbDepth(lsb_depth);
        if (mode == MODE_CELT_ONLY && bandwidth == OPUS_BANDWIDTH_MEDIUMBAND) {
            bandwidth = OPUS_BANDWIDTH_WIDEBAND;
        }
        if (lfe) {
            bandwidth = OPUS_BANDWIDTH_NARROWBAND;
        }
        curr_bandwidth = bandwidth;
        if (mode == MODE_SILK_ONLY && curr_bandwidth > OPUS_BANDWIDTH_WIDEBAND) {
            mode = MODE_HYBRID;
        }
        if (mode == MODE_HYBRID && curr_bandwidth <= OPUS_BANDWIDTH_WIDEBAND) {
            mode = MODE_SILK_ONLY;
        }
        if ((frame_size > Fs / 50 && mode != MODE_SILK_ONLY) || frame_size > 3 * Fs / 50) {
            int enc_frame_size;
            if (mode == MODE_SILK_ONLY) {
                if (frame_size == 2 * Fs / 25) {
                    enc_frame_size = Fs / 25;
                } else if (frame_size == 3 * Fs / 25) {
                    enc_frame_size = 3 * Fs / 50;
                } else {
                    enc_frame_size = Fs / 50;
                }
            } else {
                enc_frame_size = Fs / 50;
            }
            var nb_frames = frame_size / enc_frame_size;
            if (analysis_read_pos_bak != -1) {
                analysis.read_pos = analysis_read_pos_bak;
                analysis.read_subframe = analysis_read_subframe_bak;
            }
            var max_header_bytes = nb_frames == 2 ? 3 : 2 + (nb_frames - 1) * 2;
            int repacketize_len;
            if (use_vbr != 0 || user_bitrate_bps == OPUS_BITRATE_MAX) {
                repacketize_len = out_data_bytes;
            } else {
                repacketize_len = Math.min(cbr_bytes, out_data_bytes);
            }
            var max_len_sum = nb_frames + repacketize_len - max_header_bytes;
            if (max_len_sum > multiframeBuffer.length) {
                multiframeBuffer = new byte[max_len_sum];
            }
            var tmp_data = multiframeBuffer;
            var curr_data = 0;
            rp.init();
            var bak_to_mono = silk_mode.toMono;
            if (bak_to_mono != 0) {
                force_channels = 1;
            } else {
                prev_channels = stream_channels;
            }
            var tot_size = 0;
            var dtx_count = 0;
            for (var i = 0; i < nb_frames; i++) {
                silk_mode.toMono = 0;
                nonfinal_frame = i < nb_frames - 1;
                var frame_to_celt = to_celt != 0 && i == nb_frames - 1 ? 1 : 0;
                var frame_redundancy = redundancy != 0 && (frame_to_celt != 0 || (to_celt == 0 && i == 0)) ? 1 : 0;
                var curr_max = Math.min(3 * bitrate_bps / (3 * 8 * Fs / enc_frame_size), max_len_sum / nb_frames);
                curr_max = Math.min(max_len_sum - tot_size, curr_max);
                if (analysis_read_pos_bak != -1) {
                    is_silence = isDigitalSilence(pcm, pcmOff, frame_size, channels, lsb_depth);
                    analysis.tonalityGetInfo(analysis_info, enc_frame_size);
                }
                var tmp_len = encodeFrameNative(pcm, pcmOff + i * (channels * enc_frame_size), enc_frame_size,
                        tmp_data, curr_data, curr_max, is_silence, frame_redundancy, celt_to_silk, prefill,
                        equiv_rate, frame_to_celt);
                if (tmp_len < 0) {
                    return OPUS_INTERNAL_ERROR;
                } else if (tmp_len == 1) {
                    dtx_count++;
                }
                ret = rp.cat(tmp_data, curr_data, tmp_len);
                if (ret < 0) {
                    return OPUS_INTERNAL_ERROR;
                }
                tot_size += tmp_len;
                curr_data += tmp_len;
            }
            ret = rp.outRange(0, nb_frames, data, dataOff, repacketize_len, use_vbr == 0 && dtx_count != nb_frames);
            if (ret < 0) {
                ret = OPUS_INTERNAL_ERROR;
            }
            silk_mode.toMono = bak_to_mono;
            return ret;
        } else {
            return encodeFrameNative(pcm, pcmOff, frame_size, data, dataOff, max_data_bytes, is_silence,
                    redundancy, celt_to_silk, prefill, equiv_rate, to_celt);
        }
    }

    /**
     * Returns the change of the SILK bitrate that the surround masking of the other channels allows,
     * half of it in hybrid frames' SILK layer (the surround masking block of
     * {@code opus_encode_frame_native}).
     *
     * @return the bitrate change in bits per second, usually negative
     */
    private int surroundSilkRateOffset() {
        var mask_sum = 0f;
        var end = 17;
        var srate = 16000;
        if (bandwidth == OPUS_BANDWIDTH_NARROWBAND) {
            end = 13;
            srate = 8000;
        } else if (bandwidth == OPUS_BANDWIDTH_MEDIUMBAND) {
            end = 15;
            srate = 12000;
        }
        for (var c = 0; c < channels; c++) {
            for (var i = 0; i < end; i++) {
                var mask = MathOps.max(MathOps.min(energy_masking[21 * c + i], .5f), -2.0f);
                if (mask > 0) {
                    mask = .5f * mask;
                }
                mask_sum += mask;
            }
        }
        var masking_depth = mask_sum / end * channels;
        masking_depth += .2f;
        var rate_offset = (int) (srate * masking_depth);
        rate_offset = Math.max(rate_offset, -2 * silk_mode.bitRate / 3);
        if (bandwidth == OPUS_BANDWIDTH_SUPERWIDEBAND || bandwidth == OPUS_BANDWIDTH_FULLBAND) {
            return 3 * rate_offset / 5;
        }
        return rate_offset;
    }

    /**
     * Encodes one frame of at most 20 ms (60 ms for SILK) in the mode already chosen
     * ({@code opus_encode_frame_native}).
     *
     * @param pcm            the interleaved input
     * @param pcmOff         the index of the first sample
     * @param frame_size     the frame size in samples
     * @param data           the output buffer
     * @param dataOff        the index of the first output byte (the table of contents)
     * @param max_data_bytes the space available
     * @param is_silence     whether the input is digital silence
     * @param redundancy     whether to code a transition redundancy frame
     * @param celt_to_silk   whether the transition is from CELT to SILK
     * @param prefill        the SILK prefill mode: 0 none, 1 full reset, 2 keep the rate control
     * @param equiv_rate     the equivalent bitrate
     * @param to_celt        whether this is the last frame before switching to CELT
     * @return the packet length, or a negative error code
     */
    private int encodeFrameNative(float[] pcm, int pcmOff, int frame_size, byte[] data, int dataOff, int max_data_bytes,
                                  boolean is_silence, int redundancy, int celt_to_silk, int prefill, int equiv_rate,
                                  int to_celt) {
        var ret = 0;
        int bytes_target;
        var start_band = 0;
        var redundancy_bytes = 0;
        int nb_compr_bytes;
        var redundant_rng = 0;
        int cutoff_Hz;
        int hp_freq_smth1;
        float HB_gain;
        boolean apply_padding;
        int frame_rate;
        int curr_bandwidth;
        int delay_compensation;
        int total_buffer;
        var activity = Define.VAD_NO_DECISION;
        rangeFinal = 0;
        var celt_mode = celt_enc.getMode();
        curr_bandwidth = bandwidth;
        if (application == OPUS_APPLICATION_RESTRICTED_LOWDELAY) {
            delay_compensation = 0;
        } else {
            delay_compensation = this.delay_compensation;
        }
        total_buffer = delay_compensation;
        frame_rate = Fs / frame_size;
        if (is_silence) {
            activity = 0;
        } else if (analysis_info.valid != 0) {
            activity = analysis_info.activity_probability >= Define.DTX_ACTIVITY_THRESHOLD ? 1 : 0;
            if (activity == 0) {
                var noise_energy = computeFrameEnergy(pcm, pcmOff, frame_size, channels);
                activity = peak_signal_energy < PSEUDO_SNR_THRESHOLD * noise_energy ? 1 : 0;
            }
        }
        if (silk_bw_switch != 0) {
            redundancy = 1;
            celt_to_silk = 1;
            silk_bw_switch = 0;
            prefill = 2;
        }
        if (mode == MODE_CELT_ONLY) {
            redundancy = 0;
        }
        if (redundancy != 0) {
            redundancy_bytes = computeRedundancyBytes(max_data_bytes, bitrate_bps, frame_rate, stream_channels);
            if (redundancy_bytes == 0) {
                redundancy = 0;
            }
        }
        bytes_target = Math.min(max_data_bytes - redundancy_bytes, bitrate_bps * frame_size / (Fs * 8)) - 1;
        var body = dataOff + 1;
        enc.init(data, body, max_data_bytes - 1);
        var pcm_buf = this.pcm_buf;
        System.arraycopy(delay_buffer, (encoder_buffer - total_buffer) * channels, pcm_buf, 0, total_buffer * channels);
        if (mode == MODE_CELT_ONLY) {
            hp_freq_smth1 = LinLog.lin2log(TuningParameters.VARIABLE_HP_MIN_CUTOFF_HZ) << 8;
        } else {
            hp_freq_smth1 = silk_enc.state_Fxx[0].sCmn.variable_HP_smth1_Q15;
        }
        variable_HP_smth2_Q15 = SigProcFix.smlawb(variable_HP_smth2_Q15, hp_freq_smth1 - variable_HP_smth2_Q15,
                SigProcFix.fixConst(TuningParameters.VARIABLE_HP_SMTH_COEF2, 16));
        cutoff_Hz = LinLog.log2lin(variable_HP_smth2_Q15 >> 8);
        if (application == OPUS_APPLICATION_VOIP) {
            hpCutoff(pcm, pcmOff, cutoff_Hz, pcm_buf, total_buffer * channels, hp_mem, frame_size, channels, Fs);
        } else {
            dcReject(pcm, pcmOff, 3, pcm_buf, total_buffer * channels, hp_mem, frame_size, channels, Fs);
        }
        {
            var sum = Pitch.celtInnerProd(pcm_buf, total_buffer * channels, pcm_buf, total_buffer * channels, frame_size * channels);
            if (!(sum < 1e9f) || Float.isNaN(sum)) {
                java.util.Arrays.fill(pcm_buf, total_buffer * channels, (total_buffer + frame_size) * channels, 0);
                hp_mem[0] = hp_mem[1] = hp_mem[2] = hp_mem[3] = 0;
            }
        }
        HB_gain = 1.0f;
        if (mode != MODE_CELT_ONLY) {
            int total_bitRate;
            int celt_rate;
            total_bitRate = 8 * bytes_target * frame_rate;
            if (mode == MODE_HYBRID) {
                silk_mode.bitRate = computeSilkRateForHybrid(total_bitRate, curr_bandwidth, Fs == 50 * frame_size ? 1 : 0,
                        use_vbr, silk_mode.LBRR_coded, stream_channels);
                if (energy_masking == null) {
                    celt_rate = total_bitRate - silk_mode.bitRate;
                    HB_gain = 1.0f - MathOps.celtExp2(-celt_rate * (1.f / 1024));
                }
            } else {
                silk_mode.bitRate = total_bitRate;
            }
            if (energy_masking != null && use_vbr != 0 && !lfe) {
                silk_mode.bitRate += surroundSilkRateOffset();
            }
            silk_mode.payloadSize_ms = 1000 * frame_size / Fs;
            silk_mode.nChannelsAPI = channels;
            silk_mode.nChannelsInternal = stream_channels;
            if (curr_bandwidth == OPUS_BANDWIDTH_NARROWBAND) {
                silk_mode.desiredInternalSampleRate = 8000;
            } else if (curr_bandwidth == OPUS_BANDWIDTH_MEDIUMBAND) {
                silk_mode.desiredInternalSampleRate = 12000;
            } else {
                silk_mode.desiredInternalSampleRate = 16000;
            }
            if (mode == MODE_HYBRID) {
                silk_mode.minInternalSampleRate = 16000;
            } else {
                silk_mode.minInternalSampleRate = 8000;
            }
            silk_mode.maxInternalSampleRate = 16000;
            if (mode == MODE_SILK_ONLY) {
                var effective_max_rate = frame_rate * max_data_bytes * 8;
                if (frame_rate > 50) {
                    effective_max_rate = effective_max_rate * 2 / 3;
                }
                if (effective_max_rate < 8000) {
                    silk_mode.maxInternalSampleRate = 12000;
                    silk_mode.desiredInternalSampleRate = Math.min(12000, silk_mode.desiredInternalSampleRate);
                }
                if (effective_max_rate < 7000) {
                    silk_mode.maxInternalSampleRate = 8000;
                    silk_mode.desiredInternalSampleRate = Math.min(8000, silk_mode.desiredInternalSampleRate);
                }
            }
            silk_mode.useCBR = use_vbr == 0 ? 1 : 0;
            silk_mode.maxBits = (max_data_bytes - 1) * 8;
            if (redundancy != 0 && redundancy_bytes >= 2) {
                silk_mode.maxBits -= redundancy_bytes * 8 + 1;
                if (mode == MODE_HYBRID) {
                    silk_mode.maxBits -= 20;
                }
            }
            if (silk_mode.useCBR != 0) {
                if (mode == MODE_HYBRID) {
                    var other_bits = (short) Math.max(0, silk_mode.maxBits - silk_mode.bitRate * frame_size / Fs);
                    silk_mode.maxBits = Math.max(0, silk_mode.maxBits - other_bits * 3 / 4);
                    silk_mode.useCBR = 0;
                }
            } else {
                if (mode == MODE_HYBRID) {
                    var maxBitRate = computeSilkRateForHybrid(silk_mode.maxBits * Fs / frame_size, curr_bandwidth,
                            Fs == 50 * frame_size ? 1 : 0, use_vbr, silk_mode.LBRR_coded, stream_channels);
                    silk_mode.maxBits = maxBitRate * frame_size / Fs;
                }
            }
            if (prefill != 0) {
                nBytes[0] = 0;
                var prefill_offset = channels * (encoder_buffer - this.delay_compensation - Fs / 400);
                gainFade(delay_buffer, prefill_offset, delay_buffer, prefill_offset, 0, 1.0f,
                        celt_mode.overlap, Fs / 400, channels, celt_mode.window, Fs);
                java.util.Arrays.fill(delay_buffer, 0, prefill_offset, 0);
                for (var i = 0; i < encoder_buffer * channels; i++) {
                    pcm_silk[i] = MathOps.float2int16(delay_buffer[i]);
                }
                silk_enc.encode(silk_mode, pcm_silk, 0, encoder_buffer, null, nBytes, prefill, activity);
                silk_mode.opusCanSwitch = 0;
            }
            for (var i = 0; i < frame_size * channels; i++) {
                pcm_silk[i] = MathOps.float2int16(pcm_buf[total_buffer * channels + i]);
            }
            nBytes[0] = 0;
            ret = silk_enc.encode(silk_mode, pcm_silk, 0, frame_size, enc, nBytes, 0, activity);
            if (ret != 0) {
                return OPUS_INTERNAL_ERROR;
            }
            if (mode == MODE_SILK_ONLY) {
                if (silk_mode.internalSampleRate == 8000) {
                    curr_bandwidth = OPUS_BANDWIDTH_NARROWBAND;
                } else if (silk_mode.internalSampleRate == 12000) {
                    curr_bandwidth = OPUS_BANDWIDTH_MEDIUMBAND;
                } else if (silk_mode.internalSampleRate == 16000) {
                    curr_bandwidth = OPUS_BANDWIDTH_WIDEBAND;
                }
            }
            silk_mode.opusCanSwitch = silk_mode.switchReady != 0 && !nonfinal_frame ? 1 : 0;
            if (nBytes[0] == 0) {
                rangeFinal = 0;
                data[dataOff] = (byte) genToc(mode, Fs / frame_size, curr_bandwidth, stream_channels);
                return 1;
            }
            if (silk_mode.opusCanSwitch != 0) {
                redundancy_bytes = computeRedundancyBytes(max_data_bytes, bitrate_bps, frame_rate, stream_channels);
                redundancy = redundancy_bytes != 0 ? 1 : 0;
                celt_to_silk = 0;
                silk_bw_switch = 1;
            }
        }
        {
            var endband = switch (curr_bandwidth) {
                case OPUS_BANDWIDTH_NARROWBAND -> 13;
                case OPUS_BANDWIDTH_MEDIUMBAND, OPUS_BANDWIDTH_WIDEBAND -> 17;
                case OPUS_BANDWIDTH_SUPERWIDEBAND -> 19;
                default -> 21;
            };
            celt_enc.setEndBand(endband);
            celt_enc.setChannels(stream_channels);
        }
        celt_enc.setBitrate(OPUS_BITRATE_MAX);
        if (mode != MODE_SILK_ONLY) {
            var celt_pred = 2;
            if (silk_mode.reducedDependency != 0) {
                celt_pred = 0;
            }
            celt_enc.setPrediction(celt_pred);
        }
        if (mode != MODE_SILK_ONLY && mode != prev_mode && prev_mode > 0) {
            System.arraycopy(delay_buffer, (encoder_buffer - total_buffer - Fs / 400) * channels, tmp_prefill, 0,
                    channels * Fs / 400);
        }
        if (channels * (encoder_buffer - (frame_size + total_buffer)) > 0) {
            System.arraycopy(delay_buffer, channels * frame_size, delay_buffer, 0,
                    channels * (encoder_buffer - frame_size - total_buffer));
            System.arraycopy(pcm_buf, 0, delay_buffer, channels * (encoder_buffer - frame_size - total_buffer),
                    (frame_size + total_buffer) * channels);
        } else {
            System.arraycopy(pcm_buf, (frame_size + total_buffer - encoder_buffer) * channels, delay_buffer, 0,
                    encoder_buffer * channels);
        }
        if (prev_HB_gain < 1.0f || HB_gain < 1.0f) {
            gainFade(pcm_buf, 0, pcm_buf, 0, prev_HB_gain, HB_gain, celt_mode.overlap, frame_size, channels,
                    celt_mode.window, Fs);
        }
        prev_HB_gain = HB_gain;
        if (mode != MODE_HYBRID || stream_channels == 1) {
            if (equiv_rate > 32000) {
                silk_mode.stereoWidth_Q14 = 16384;
            } else if (equiv_rate < 16000) {
                silk_mode.stereoWidth_Q14 = 0;
            } else {
                silk_mode.stereoWidth_Q14 = 16384 - 2048 * (32000 - equiv_rate) / (equiv_rate - 14000);
            }
        }
        if (energy_masking == null && channels == 2) {
            if (hybrid_stereo_width_Q14 < (1 << 14) || silk_mode.stereoWidth_Q14 < (1 << 14)) {
                float g1 = hybrid_stereo_width_Q14;
                float g2 = silk_mode.stereoWidth_Q14;
                g1 *= (1.f / 16384);
                g2 *= (1.f / 16384);
                stereoFade(pcm_buf, pcm_buf, g1, g2, celt_mode.overlap, frame_size, channels, celt_mode.window, Fs);
                hybrid_stereo_width_Q14 = (short) silk_mode.stereoWidth_Q14;
            }
        }
        if (mode != MODE_CELT_ONLY && enc.tell() + 17 + 20 * (mode == MODE_HYBRID ? 1 : 0) <= 8 * (max_data_bytes - 1)) {
            if (mode == MODE_HYBRID) {
                enc.encBitLogp(redundancy, 12);
            }
            if (redundancy != 0) {
                int max_redundancy;
                enc.encBitLogp(celt_to_silk, 1);
                if (mode == MODE_HYBRID) {
                    max_redundancy = (max_data_bytes - 1) - ((enc.tell() + 8 + 3 + 7) >> 3);
                } else {
                    max_redundancy = (max_data_bytes - 1) - ((enc.tell() + 7) >> 3);
                }
                redundancy_bytes = Math.min(max_redundancy, redundancy_bytes);
                redundancy_bytes = Math.min(257, Math.max(2, redundancy_bytes));
                if (mode == MODE_HYBRID) {
                    enc.encUint(redundancy_bytes - 2, 256);
                }
            }
        } else {
            redundancy = 0;
        }
        if (redundancy == 0) {
            silk_bw_switch = 0;
            redundancy_bytes = 0;
        }
        if (mode != MODE_CELT_ONLY) {
            start_band = 17;
        }
        if (mode == MODE_SILK_ONLY) {
            ret = (enc.tell() + 7) >> 3;
            enc.encDone();
            nb_compr_bytes = ret;
        } else {
            nb_compr_bytes = (max_data_bytes - 1) - redundancy_bytes;
            enc.encShrink(nb_compr_bytes);
        }
        if (redundancy != 0 || mode != MODE_SILK_ONLY) {
            celt_enc.setAnalysis(analysis_info);
        }
        if (mode == MODE_HYBRID) {
            silkInfo.signalType = silk_mode.signalType;
            silkInfo.offset = silk_mode.offset;
            celt_enc.setSilkInfo(silkInfo);
        }
        if (redundancy != 0 && celt_to_silk != 0) {
            celt_enc.setStartBand(0);
            celt_enc.setVbr(0);
            celt_enc.setBitrate(OPUS_BITRATE_MAX);
            var err = celt_enc.encodeWithEc(pcm_buf, 0, Fs / 200, data, body + nb_compr_bytes, redundancy_bytes, null);
            if (err < 0) {
                return OPUS_INTERNAL_ERROR;
            }
            redundant_rng = celt_enc.getFinalRange();
            celt_enc.resetState();
        }
        celt_enc.setStartBand(start_band);
        if (mode != MODE_SILK_ONLY) {
            celt_enc.setVbr(use_vbr);
            if (mode == MODE_HYBRID) {
                if (use_vbr != 0) {
                    celt_enc.setBitrate(bitrate_bps - silk_mode.bitRate);
                    celt_enc.setVbrConstraint(0);
                }
            } else {
                if (use_vbr != 0) {
                    celt_enc.setVbr(1);
                    celt_enc.setVbrConstraint(vbr_constraint);
                    celt_enc.setBitrate(bitrate_bps);
                }
            }
            if (mode != prev_mode && prev_mode > 0) {
                celt_enc.resetState();
                celt_enc.encodeWithEc(tmp_prefill, 0, Fs / 400, dummy, 0, 2, null);
                celt_enc.setPrediction(0);
            }
            if (enc.tell() <= 8 * nb_compr_bytes) {
                ret = celt_enc.encodeWithEc(pcm_buf, 0, frame_size, null, 0, nb_compr_bytes, enc);
                if (ret < 0) {
                    return OPUS_INTERNAL_ERROR;
                }
                if (redundancy != 0 && celt_to_silk != 0 && mode == MODE_HYBRID && nb_compr_bytes != ret) {
                    System.arraycopy(data, body + nb_compr_bytes, data, body + ret, redundancy_bytes);
                    nb_compr_bytes = ret + redundancy_bytes;
                }
            }
        }
        if (redundancy != 0 && celt_to_silk == 0) {
            var N2 = Fs / 200;
            var N4 = Fs / 400;
            celt_enc.resetState();
            celt_enc.setStartBand(0);
            celt_enc.setPrediction(0);
            celt_enc.setVbr(0);
            celt_enc.setBitrate(OPUS_BITRATE_MAX);
            if (mode == MODE_HYBRID) {
                nb_compr_bytes = ret;
                enc.encShrink(nb_compr_bytes);
            }
            celt_enc.encodeWithEc(pcm_buf, channels * (frame_size - N2 - N4), N4, dummy, 0, 2, null);
            var err = celt_enc.encodeWithEc(pcm_buf, channels * (frame_size - N2), N2, data, body + nb_compr_bytes,
                    redundancy_bytes, null);
            if (err < 0) {
                return OPUS_INTERNAL_ERROR;
            }
            redundant_rng = celt_enc.getFinalRange();
        }
        data[dataOff] = (byte) genToc(mode, Fs / frame_size, curr_bandwidth, stream_channels);
        rangeFinal = enc.rng ^ redundant_rng;
        if (to_celt != 0) {
            prev_mode = MODE_CELT_ONLY;
        } else {
            prev_mode = mode;
        }
        prev_channels = stream_channels;
        prev_framesize = frame_size;
        first = 0;
        if (use_dtx != 0 && (analysis_info.valid != 0 || is_silence)) {
            if (decideDtxMode(activity != 0, 2 * 1000 * frame_size / Fs)) {
                rangeFinal = 0;
                data[dataOff] = (byte) genToc(mode, Fs / frame_size, curr_bandwidth, stream_channels);
                return 1;
            }
        } else {
            nb_no_activity_ms_Q1 = 0;
        }
        if (enc.tell() > (max_data_bytes - 1) * 8) {
            if (max_data_bytes < 2) {
                return OPUS_BUFFER_TOO_SMALL;
            }
            data[dataOff + 1] = 0;
            ret = 1;
            rangeFinal = 0;
        } else if (mode == MODE_SILK_ONLY && redundancy == 0) {
            while (ret > 2 && data[dataOff + ret] == 0) {
                ret--;
            }
        }
        ret += 1 + redundancy_bytes;
        apply_padding = use_vbr == 0;
        if (apply_padding) {
            if (padder.pad(data, dataOff, ret, max_data_bytes) != OPUS_OK) {
                return OPUS_INTERNAL_ERROR;
            }
            ret = max_data_bytes;
        }
        return ret;
    }
}
