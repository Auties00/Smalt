/* Ports libopus 1.5.2 (silk/structs.h). Copyright Skype Limited; BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.audio.opus.internal.silk;

import java.util.Arrays;

import static com.github.auties00.smalt.audio.opus.internal.silk.Define.MAX_FRAMES_PER_PACKET;
import static com.github.auties00.smalt.audio.opus.internal.silk.Define.MAX_FRAME_LENGTH;
import static com.github.auties00.smalt.audio.opus.internal.silk.Define.MAX_LPC_ORDER;
import static com.github.auties00.smalt.audio.opus.internal.silk.Define.VAD_N_BANDS;

/**
 * Holds the state of one SILK encoder channel that is shared by the fixed-point and float
 * encoders.
 *
 * @implNote This implementation keeps the libopus field names and drops the {@code arch} field.
 */
public final class EncoderState {
    /**
     * The high-pass filter state.
     */
    public final int[] In_HP_State = new int[2];

    /**
     * The state of the first smoother of the variable high-pass cutoff.
     */
    public int variable_HP_smth1_Q15;

    /**
     * The state of the second smoother of the variable high-pass cutoff.
     */
    public int variable_HP_smth2_Q15;

    /**
     * The low-pass filter state.
     */
    public final LpState sLP = new LpState();

    /**
     * The voice activity detector state.
     */
    public final VadState sVAD = new VadState();

    /**
     * The noise shaping quantizer state.
     */
    public final NsqState sNSQ = new NsqState();

    /**
     * The previously quantized NLSF vector in Q15.
     */
    public final short[] prev_NLSFq_Q15 = new short[MAX_LPC_ORDER];

    /**
     * The speech activity in Q8.
     */
    public int speech_activity_Q8;

    /**
     * Whether switching of the internal bandwidth is allowed.
     */
    public int allow_bandwidth_switch;

    /**
     * The last gain index of the previous LBRR frame.
     */
    public byte LBRRprevLastGainIndex;

    /**
     * The signal type of the previous frame.
     */
    public byte prevSignalType;

    /**
     * The pitch lag of the previous frame.
     */
    public int prevLag;

    /**
     * The length of the LPC window of the pitch analysis.
     */
    public int pitch_LPC_win_length;

    /**
     * The API sampling frequency in Hz.
     */
    public int API_fs_Hz;

    /**
     * The previous API sampling frequency in Hz.
     */
    public int prev_API_fs_Hz;

    /**
     * The maximum internal sampling frequency in Hz.
     */
    public int maxInternal_fs_Hz;

    /**
     * The minimum internal sampling frequency in Hz.
     */
    public int minInternal_fs_Hz;

    /**
     * The soft request for the internal sampling frequency in Hz.
     */
    public int desiredInternal_fs_Hz;

    /**
     * The internal sampling frequency in kHz.
     */
    public int fs_kHz;

    /**
     * The number of 5 ms subframes in a frame.
     */
    public int nb_subfr;

    /**
     * The frame length in samples.
     */
    public int frame_length;

    /**
     * The subframe length in samples.
     */
    public int subfr_length;

    /**
     * The length of the LTP memory in samples.
     */
    public int ltp_mem_length;

    /**
     * The lookahead of the pitch analysis in samples.
     */
    public int la_pitch;

    /**
     * The lookahead of the noise shape analysis in samples.
     */
    public int la_shape;

    /**
     * The window length of the noise shape analysis in samples.
     */
    public int shapeWinLength;

    /**
     * The target bitrate in bits per second.
     */
    public int TargetRate_bps;

    /**
     * The number of milliseconds in each packet.
     */
    public int PacketSize_ms;

    /**
     * The packet loss rate reported by the far end, in percent.
     */
    public int PacketLoss_perc;

    /**
     * The frame counter.
     */
    public int frameCounter;

    /**
     * The complexity setting.
     */
    public int Complexity;

    /**
     * The number of states of the delayed decision quantizer.
     */
    public int nStatesDelayedDecision;

    /**
     * Whether NLSF interpolation is used.
     */
    public int useInterpolatedNLSFs;

    /**
     * The order of the noise shaping filters.
     */
    public int shapingLPCOrder;

    /**
     * The order of the prediction filters.
     */
    public int predictLPCOrder;

    /**
     * The complexity of the pitch estimator.
     */
    public int pitchEstimationComplexity;

    /**
     * The whitening filter order of the pitch estimator.
     */
    public int pitchEstimationLPCOrder;

    /**
     * The threshold of the pitch estimator in Q16.
     */
    public int pitchEstimationThreshold_Q16;

    /**
     * The cumulative maximum prediction gain in Q7.
     */
    public int sum_log_gain_Q7;

    /**
     * The number of survivors of the NLSF multi-stage vector quantizer.
     */
    public int NLSF_MSVQ_Survivors;

    /**
     * Whether this is the first frame after a reset, deactivating NLSF interpolation and pitch
     * prediction.
     */
    public int first_frame_after_reset;

    /**
     * Whether the codec control already ran for the current packet.
     */
    public int controlled_since_last_payload;

    /**
     * The warping parameter of the warped noise shaping, in Q16.
     */
    public int warping_Q16;

    /**
     * Whether constant bitrate is enabled.
     */
    public int useCBR;

    /**
     * Whether only the buffers are prefilled, without coding.
     */
    public int prefillFlag;

    /**
     * The iCDF of the low bits of the pitch lag index.
     */
    public short[] pitch_lag_low_bits_iCDF;

    /**
     * The iCDF of the pitch contour index.
     */
    public short[] pitch_contour_iCDF;

    /**
     * The NLSF codebook.
     */
    public NlsfCodebook psNLSF_CB;

    /**
     * The input quality of each VAD band in Q15.
     */
    public final int[] input_quality_bands_Q15 = new int[VAD_N_BANDS];

    /**
     * The spectral tilt of the input in Q15.
     */
    public int input_tilt_Q15;

    /**
     * The quality setting in dB, Q7.
     */
    public int SNR_dB_Q7;

    /**
     * The voice activity flag of each frame of the packet.
     */
    public final byte[] VAD_flags = new byte[MAX_FRAMES_PER_PACKET];

    /**
     * Whether LBRR data is coded in the packet.
     */
    public byte LBRR_flag;

    /**
     * The LBRR flag of each frame of the packet.
     */
    public final int[] LBRR_flags = new int[MAX_FRAMES_PER_PACKET];

    /**
     * The quantization indices of the current frame.
     */
    public final SideInfoIndices indices = new SideInfoIndices();

    /**
     * The quantized excitation of the current frame.
     */
    public final byte[] pulses = new byte[MAX_FRAME_LENGTH];

    /**
     * The buffer holding the input signal.
     */
    public final short[] inputBuf = new short[MAX_FRAME_LENGTH + 2];

    /**
     * The write index of {@link #inputBuf}.
     */
    public int inputBufIx;

    /**
     * The number of frames in each packet.
     */
    public int nFramesPerPacket;

    /**
     * The number of frames analyzed in the current packet.
     */
    public int nFramesEncoded;

    /**
     * The number of API channels.
     */
    public int nChannelsAPI;

    /**
     * The number of internal channels.
     */
    public int nChannelsInternal;

    /**
     * The index of this channel.
     */
    public int channelNb;

    /**
     * The signal type of the previous frame, for entropy coding.
     */
    public int ec_prevSignalType;

    /**
     * The lag index of the previous frame, for entropy coding.
     */
    public short ec_prevLagIndex;

    /**
     * The state of the input resampler.
     */
    public final ResamplerState resampler_state = new ResamplerState();

    /**
     * Whether discontinuous transmission is enabled.
     */
    public int useDTX;

    /**
     * Whether a DTX period is in progress.
     */
    public int inDTX;

    /**
     * The number of consecutive inactive frames, used by DTX.
     */
    public int noSpeechCounter;

    /**
     * The in-band FEC setting of the API.
     */
    public int useInBandFEC;

    /**
     * Whether LBRR is enabled, depending on the FEC setting, bitrate and loss rate.
     */
    public int LBRR_enabled;

    /**
     * The gain increment of LBRR frames.
     */
    public int LBRR_GainIncreases;

    /**
     * The quantization indices of the LBRR frames of the packet.
     */
    public final SideInfoIndices[] indices_LBRR = {new SideInfoIndices(), new SideInfoIndices(), new SideInfoIndices()};

    /**
     * The quantized excitation of the LBRR frames of the packet.
     */
    public final byte[][] pulses_LBRR = new byte[MAX_FRAMES_PER_PACKET][MAX_FRAME_LENGTH];

    /**
     * Creates a zeroed state.
     */
    public EncoderState() {
    }

    /**
     * Zeroes every field, like {@code silk_memset} of the structure.
     */
    public void clear() {
        Arrays.fill(In_HP_State, 0);
        variable_HP_smth1_Q15 = 0;
        variable_HP_smth2_Q15 = 0;
        sLP.clear();
        sVAD.clear();
        sNSQ.clear();
        Arrays.fill(prev_NLSFq_Q15, (short) 0);
        speech_activity_Q8 = 0;
        allow_bandwidth_switch = 0;
        LBRRprevLastGainIndex = 0;
        prevSignalType = 0;
        prevLag = 0;
        pitch_LPC_win_length = 0;
        API_fs_Hz = 0;
        prev_API_fs_Hz = 0;
        maxInternal_fs_Hz = 0;
        minInternal_fs_Hz = 0;
        desiredInternal_fs_Hz = 0;
        fs_kHz = 0;
        nb_subfr = 0;
        frame_length = 0;
        subfr_length = 0;
        ltp_mem_length = 0;
        la_pitch = 0;
        la_shape = 0;
        shapeWinLength = 0;
        TargetRate_bps = 0;
        PacketSize_ms = 0;
        PacketLoss_perc = 0;
        frameCounter = 0;
        Complexity = 0;
        nStatesDelayedDecision = 0;
        useInterpolatedNLSFs = 0;
        shapingLPCOrder = 0;
        predictLPCOrder = 0;
        pitchEstimationComplexity = 0;
        pitchEstimationLPCOrder = 0;
        pitchEstimationThreshold_Q16 = 0;
        sum_log_gain_Q7 = 0;
        NLSF_MSVQ_Survivors = 0;
        first_frame_after_reset = 0;
        controlled_since_last_payload = 0;
        warping_Q16 = 0;
        useCBR = 0;
        prefillFlag = 0;
        pitch_lag_low_bits_iCDF = null;
        pitch_contour_iCDF = null;
        psNLSF_CB = null;
        Arrays.fill(input_quality_bands_Q15, 0);
        input_tilt_Q15 = 0;
        SNR_dB_Q7 = 0;
        Arrays.fill(VAD_flags, (byte) 0);
        LBRR_flag = 0;
        Arrays.fill(LBRR_flags, 0);
        indices.clear();
        Arrays.fill(pulses, (byte) 0);
        Arrays.fill(inputBuf, (short) 0);
        inputBufIx = 0;
        nFramesPerPacket = 0;
        nFramesEncoded = 0;
        nChannelsAPI = 0;
        nChannelsInternal = 0;
        channelNb = 0;
        ec_prevSignalType = 0;
        ec_prevLagIndex = 0;
        resampler_state.clear();
        useDTX = 0;
        inDTX = 0;
        noSpeechCounter = 0;
        useInBandFEC = 0;
        LBRR_enabled = 0;
        LBRR_GainIncreases = 0;
        for (var lbrr : indices_LBRR) {
            lbrr.clear();
        }
        for (var lbrr : pulses_LBRR) {
            Arrays.fill(lbrr, (byte) 0);
        }
    }
}
