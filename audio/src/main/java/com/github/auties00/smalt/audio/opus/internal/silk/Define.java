/* Ports libopus 1.5.2 (silk/define.h, silk/typedef.h, silk/control.h, silk/SigProc_FIX.h, silk/resampler_structs.h).
   Copyright Skype Limited; BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.audio.opus.internal.silk;

/**
 * Holds the compile-time constants of the SILK codec.
 *
 * <p>Names match the libopus macros exactly so the port stays diffable against upstream.
 */
public final class Define {
    /**
     * The maximum number of encoder channels.
     */
    public static final int ENCODER_NUM_CHANNELS = 2;

    /**
     * The maximum number of decoder channels.
     */
    public static final int DECODER_NUM_CHANNELS = 2;

    /**
     * The maximum number of SILK frames in one Opus packet.
     */
    public static final int MAX_FRAMES_PER_PACKET = 3;

    /**
     * The number of inactive frames (200 ms) before discontinuous transmission starts.
     */
    public static final int NB_SPEECH_FRAMES_BEFORE_DTX = 10;

    /**
     * The maximum number of consecutive DTX frames (400 ms).
     */
    public static final int MAX_CONSECUTIVE_DTX = 20;

    /**
     * The activity probability below which a frame counts as inactive for DTX.
     */
    public static final float DTX_ACTIVITY_THRESHOLD = 0.1f;

    /**
     * The voice activity decision meaning no decision was made.
     */
    public static final int VAD_NO_DECISION = -1;

    /**
     * The voice activity decision meaning no activity.
     */
    public static final int VAD_NO_ACTIVITY = 0;

    /**
     * The maximum internal sampling frequency in kHz.
     */
    public static final int MAX_FS_KHZ = 16;

    /**
     * The maximum API sampling frequency in kHz.
     */
    public static final int MAX_API_FS_KHZ = 48;

    /**
     * The signal type of a frame with no voice activity.
     */
    public static final int TYPE_NO_VOICE_ACTIVITY = 0;

    /**
     * The signal type of an unvoiced frame.
     */
    public static final int TYPE_UNVOICED = 1;

    /**
     * The signal type of a voiced frame.
     */
    public static final int TYPE_VOICED = 2;

    /**
     * The conditional coding type of a frame coded independently.
     */
    public static final int CODE_INDEPENDENTLY = 0;

    /**
     * The conditional coding type of a frame coded independently without LTP scaling.
     */
    public static final int CODE_INDEPENDENTLY_NO_LTP_SCALING = 1;

    /**
     * The conditional coding type of a frame coded conditionally on the previous one.
     */
    public static final int CODE_CONDITIONALLY = 2;

    /**
     * The size of the stereo predictor quantization table.
     */
    public static final int STEREO_QUANT_TAB_SIZE = 16;

    /**
     * The number of sub-steps between stereo predictor quantization levels.
     */
    public static final int STEREO_QUANT_SUB_STEPS = 5;

    /**
     * The length of the stereo predictor interpolation in milliseconds; even.
     */
    public static final int STEREO_INTERP_LEN_MS = 8;

    /**
     * The smoothing coefficient for the stereo signal norms and width.
     */
    public static final double STEREO_RATIO_SMOOTH_COEF = 0.01;

    /**
     * The maximum number of subframes in a frame.
     */
    public static final int MAX_NB_SUBFR = 4;

    /**
     * The length of the LTP memory in milliseconds.
     */
    public static final int LTP_MEM_LENGTH_MS = 20;

    /**
     * The length of a subframe in milliseconds.
     */
    public static final int SUB_FRAME_LENGTH_MS = 5;

    /**
     * The maximum number of samples in a subframe.
     */
    public static final int MAX_SUB_FRAME_LENGTH = SUB_FRAME_LENGTH_MS * MAX_FS_KHZ;

    /**
     * The maximum length of a frame in milliseconds.
     */
    public static final int MAX_FRAME_LENGTH_MS = SUB_FRAME_LENGTH_MS * MAX_NB_SUBFR;

    /**
     * The maximum number of samples in a frame.
     */
    public static final int MAX_FRAME_LENGTH = MAX_FRAME_LENGTH_MS * MAX_FS_KHZ;

    /**
     * The lookahead of the pitch analysis in milliseconds.
     */
    public static final int LA_PITCH_MS = 2;

    /**
     * The maximum lookahead of the pitch analysis in samples.
     */
    public static final int LA_PITCH_MAX = LA_PITCH_MS * MAX_FS_KHZ;

    /**
     * The order of the whitening LPC used by the pitch analysis.
     */
    public static final int MAX_FIND_PITCH_LPC_ORDER = 16;

    /**
     * The length in milliseconds of the LPC window of the pitch analysis.
     */
    public static final int FIND_PITCH_LPC_WIN_MS = 20 + (LA_PITCH_MS << 1);

    /**
     * The length in milliseconds of the LPC window of the pitch analysis for two-subframe frames.
     */
    public static final int FIND_PITCH_LPC_WIN_MS_2_SF = 10 + (LA_PITCH_MS << 1);

    /**
     * The maximum length in samples of the LPC window of the pitch analysis.
     */
    public static final int FIND_PITCH_LPC_WIN_MAX = FIND_PITCH_LPC_WIN_MS * MAX_FS_KHZ;

    /**
     * The lookahead of the noise shape analysis in milliseconds.
     */
    public static final int LA_SHAPE_MS = 5;

    /**
     * The maximum lookahead of the noise shape analysis in samples.
     */
    public static final int LA_SHAPE_MAX = LA_SHAPE_MS * MAX_FS_KHZ;

    /**
     * The maximum length in samples of the LPC window of the noise shape analysis.
     */
    public static final int SHAPE_LPC_WIN_MAX = 15 * MAX_FS_KHZ;

    /**
     * The level in dB of the lowest gain quantization level.
     */
    public static final int MIN_QGAIN_DB = 2;

    /**
     * The level in dB of the highest gain quantization level.
     */
    public static final int MAX_QGAIN_DB = 88;

    /**
     * The number of gain quantization levels.
     */
    public static final int N_LEVELS_QGAIN = 64;

    /**
     * The maximum increase of a delta-coded gain index.
     */
    public static final int MAX_DELTA_GAIN_QUANT = 36;

    /**
     * The maximum decrease of a delta-coded gain index.
     */
    public static final int MIN_DELTA_GAIN_QUANT = -4;

    /**
     * The quantization offset of voiced frames with a low offset type, in Q10.
     */
    public static final int OFFSET_VL_Q10 = 32;

    /**
     * The quantization offset of voiced frames with a high offset type, in Q10.
     */
    public static final int OFFSET_VH_Q10 = 100;

    /**
     * The quantization offset of unvoiced frames with a low offset type, in Q10.
     */
    public static final int OFFSET_UVL_Q10 = 100;

    /**
     * The quantization offset of unvoiced frames with a high offset type, in Q10.
     */
    public static final int OFFSET_UVH_Q10 = 240;

    /**
     * The quantization level adjustment in Q10.
     */
    public static final int QUANT_LEVEL_ADJUST_Q10 = 80;

    /**
     * The maximum number of iterations used to stabilize an LPC vector.
     */
    public static final int MAX_LPC_STABILIZE_ITERATIONS = 16;

    /**
     * The maximum prediction power gain of an LPC filter.
     */
    public static final float MAX_PREDICTION_POWER_GAIN = 1e4f;

    /**
     * The maximum prediction power gain of an LPC filter right after a reset.
     */
    public static final float MAX_PREDICTION_POWER_GAIN_AFTER_RESET = 1e2f;

    /**
     * The maximum LPC order.
     */
    public static final int MAX_LPC_ORDER = 16;

    /**
     * The minimum LPC order.
     */
    public static final int MIN_LPC_ORDER = 10;

    /**
     * The number of taps of the long-term predictor.
     */
    public static final int LTP_ORDER = 5;

    /**
     * Whether harmonic noise shaping is used.
     */
    public static final int USE_HARM_SHAPING = 1;

    /**
     * The maximum LPC order of the noise shaping filters.
     */
    public static final int MAX_SHAPE_LPC_ORDER = 24;

    /**
     * The number of taps of the harmonic shaping filter.
     */
    public static final int HARM_SHAPE_FIR_TAPS = 3;

    /**
     * The maximum number of delayed decision states.
     */
    public static final int MAX_DEL_DEC_STATES = 4;

    /**
     * The length of the LTP buffer.
     */
    public static final int LTP_BUF_LENGTH = 512;

    /**
     * The decision delay of the delayed-decision quantizer in samples.
     */
    public static final int DECISION_DELAY = 40;

    /**
     * The number of pulses in a shell coding block.
     */
    public static final int SHELL_CODEC_FRAME_LENGTH = 16;

    /**
     * The base-2 logarithm of {@link #SHELL_CODEC_FRAME_LENGTH}.
     */
    public static final int LOG2_SHELL_CODEC_FRAME_LENGTH = 4;

    /**
     * The maximum number of shell coding blocks in a frame.
     */
    public static final int MAX_NB_SHELL_BLOCKS = MAX_FRAME_LENGTH / SHELL_CODEC_FRAME_LENGTH;

    /**
     * The number of rate levels for the entropy coding of the excitation.
     */
    public static final int N_RATE_LEVELS = 10;

    /**
     * The maximum sum of pulses in a shell coding block.
     */
    public static final int SILK_MAX_PULSES = 16;

    /**
     * The length of the LPC history buffer of the noise shaping quantizer.
     */
    public static final int NSQ_LPC_BUF_LENGTH = MAX_LPC_ORDER;

    /**
     * The number of frequency bands of the voice activity detector.
     */
    public static final int VAD_N_BANDS = 4;

    /**
     * The base-2 logarithm of the number of internal subframes of the voice activity detector.
     */
    public static final int VAD_INTERNAL_SUBFRAMES_LOG2 = 2;

    /**
     * The number of internal subframes of the voice activity detector.
     */
    public static final int VAD_INTERNAL_SUBFRAMES = 1 << VAD_INTERNAL_SUBFRAMES_LOG2;

    /**
     * The noise level smoothing coefficient of the voice activity detector in Q16; below 4096.
     */
    public static final int VAD_NOISE_LEVEL_SMOOTH_COEF_Q16 = 1024;

    /**
     * The noise level bias of the voice activity detector.
     */
    public static final int VAD_NOISE_LEVELS_BIAS = 50;

    /**
     * The sigmoid offset of the voice activity detector in Q5: the sigmoid is zero at -128.
     */
    public static final int VAD_NEGATIVE_OFFSET_Q5 = 128;

    /**
     * The SNR factor of the voice activity detector in Q16.
     */
    public static final int VAD_SNR_FACTOR_Q16 = 45000;

    /**
     * The SNR smoothing coefficient of the voice activity detector in Q18.
     */
    public static final int VAD_SNR_SMOOTH_COEF_Q18 = 4096;

    /**
     * The size of the piecewise linear cosine table used for LSFs.
     */
    public static final int LSF_COS_TAB_SZ_FIX = 128;

    /**
     * The Q-domain of the NLSF weights.
     */
    public static final int NLSF_W_Q = 2;

    /**
     * The maximum number of vectors in an NLSF first-stage codebook.
     */
    public static final int NLSF_VQ_MAX_VECTORS = 32;

    /**
     * The maximum amplitude of an NLSF residual index coded without extension.
     */
    public static final int NLSF_QUANT_MAX_AMPLITUDE = 4;

    /**
     * The maximum amplitude of an NLSF residual index including the extension.
     */
    public static final int NLSF_QUANT_MAX_AMPLITUDE_EXT = 10;

    /**
     * The NLSF quantization level adjustment.
     */
    public static final double NLSF_QUANT_LEVEL_ADJ = 0.1;

    /**
     * The base-2 logarithm of the number of delayed decision states of the NLSF quantizer.
     */
    public static final int NLSF_QUANT_DEL_DEC_STATES_LOG2 = 2;

    /**
     * The number of delayed decision states of the NLSF quantizer.
     */
    public static final int NLSF_QUANT_DEL_DEC_STATES = 1 << NLSF_QUANT_DEL_DEC_STATES_LOG2;

    /**
     * The duration of a bandwidth transition in milliseconds.
     */
    public static final int TRANSITION_TIME_MS = 5120;

    /**
     * The number of numerator taps of the transition filter.
     */
    public static final int TRANSITION_NB = 3;

    /**
     * The number of denominator taps of the transition filter.
     */
    public static final int TRANSITION_NA = 2;

    /**
     * The number of interpolation points of the transition filter.
     */
    public static final int TRANSITION_INT_NUM = 5;

    /**
     * The number of frames of a bandwidth transition.
     */
    public static final int TRANSITION_FRAMES = TRANSITION_TIME_MS / MAX_FRAME_LENGTH_MS;

    /**
     * The bandwidth expansion factor applied after a packet loss, in Q16.
     */
    public static final int BWE_AFTER_LOSS_Q16 = 63570;

    /**
     * The index mask of the comfort noise excitation buffer.
     */
    public static final int CNG_BUF_MASK_MAX = 255;

    /**
     * The comfort noise gain smoothing coefficient in Q16 (0.25^(1/4)).
     */
    public static final int CNG_GAIN_SMTH_Q16 = 4634;

    /**
     * The comfort noise gain smoothing threshold in Q16 (-3 dB).
     */
    public static final int CNG_GAIN_SMTH_THRESHOLD_Q16 = 46396;

    /**
     * The comfort noise NLSF smoothing coefficient in Q16 (0.25).
     */
    public static final int CNG_NLSF_SMTH_Q16 = 16348;

    /**
     * The decoder flag of a normally received frame ({@code control.h}).
     */
    public static final int FLAG_DECODE_NORMAL = 0;

    /**
     * The decoder flag of a lost frame to conceal ({@code control.h}).
     */
    public static final int FLAG_PACKET_LOST = 1;

    /**
     * The decoder flag of a frame decoded from LBRR data ({@code control.h}).
     */
    public static final int FLAG_DECODE_LBRR = 2;

    /**
     * The maximum LPC order of {@code silk_schur} and {@code silk_k2a} ({@code SigProc_FIX.h}).
     */
    public static final int SILK_MAX_ORDER_LPC = 24;

    /**
     * The multiplier of the SILK linear congruential generator ({@code SigProc_FIX.h}).
     */
    public static final int RAND_MULTIPLIER = 196314165;

    /**
     * The increment of the SILK linear congruential generator ({@code SigProc_FIX.h}).
     */
    public static final int RAND_INCREMENT = 907633515;

    /**
     * The maximum FIR order of the resampler ({@code resampler_structs.h}).
     */
    public static final int SILK_RESAMPLER_MAX_FIR_ORDER = 36;

    /**
     * The maximum IIR order of the resampler ({@code resampler_structs.h}).
     */
    public static final int SILK_RESAMPLER_MAX_IIR_ORDER = 6;

    private Define() {
        throw new AssertionError();
    }
}
