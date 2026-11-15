/* Ports libopus 1.5.2 (silk/structs.h). Copyright Skype Limited; BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.audio.opus.internal.silk;

import com.github.auties00.smalt.audio.opus.internal.dnn.Osce;

import java.util.Arrays;

import static com.github.auties00.smalt.audio.opus.internal.silk.Define.MAX_FRAMES_PER_PACKET;
import static com.github.auties00.smalt.audio.opus.internal.silk.Define.MAX_FRAME_LENGTH;
import static com.github.auties00.smalt.audio.opus.internal.silk.Define.MAX_LPC_ORDER;
import static com.github.auties00.smalt.audio.opus.internal.silk.Define.MAX_SUB_FRAME_LENGTH;
import static com.github.auties00.smalt.audio.opus.internal.silk.Define.SHELL_CODEC_FRAME_LENGTH;

/**
 * Holds the state of one SILK decoder channel.
 *
 * <p>Besides the fields of the reference structure, the state owns the scratch buffers of the
 * per-frame decoding functions, sized for the largest frame, so decoding allocates nothing.
 *
 * @implNote This implementation keeps the libopus field names and drops the OSCE state and the {@code arch} field.
 */
public final class DecoderState {
    /**
     * The gain of the last subframe of the previous frame, in Q16.
     */
    public int prev_gain_Q16;

    /**
     * The excitation of the current frame, in Q14.
     */
    public final int[] exc_Q14 = new int[MAX_FRAME_LENGTH];

    /**
     * The state of the LPC synthesis filter, in Q14.
     */
    public final int[] sLPC_Q14_buf = new int[MAX_LPC_ORDER];

    /**
     * The buffer of the output signal, holding the LTP memory followed by the current frame.
     */
    public final short[] outBuf = new short[MAX_FRAME_LENGTH + 2 * MAX_SUB_FRAME_LENGTH];

    /**
     * The pitch lag of the previous frame.
     */
    public int lagPrev;

    /**
     * The gain index of the previous subframe.
     */
    public byte LastGainIndex;

    /**
     * The internal sampling frequency in kHz.
     */
    public int fs_kHz;

    /**
     * The API sampling frequency in Hz.
     */
    public int fs_API_hz;

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
     * The LPC order.
     */
    public int LPC_order;

    /**
     * The NLSF vector of the previous frame, in Q15, used for interpolation.
     */
    public final short[] prevNLSF_Q15 = new short[MAX_LPC_ORDER];

    /**
     * Whether this is the first frame after a reset, deactivating NLSF interpolation.
     */
    public int first_frame_after_reset;

    /**
     * The iCDF of the low bits of the pitch lag index.
     */
    public short[] pitch_lag_low_bits_iCDF;

    /**
     * The iCDF of the pitch contour index.
     */
    public short[] pitch_contour_iCDF;

    /**
     * The number of frames of the current packet decoded so far.
     */
    public int nFramesDecoded;

    /**
     * The number of frames in the current packet.
     */
    public int nFramesPerPacket;

    /**
     * The signal type of the previous frame, for entropy coding.
     */
    public int ec_prevSignalType;

    /**
     * The lag index of the previous frame, for entropy coding.
     */
    public short ec_prevLagIndex;

    /**
     * The voice activity flag of each frame of the packet.
     */
    public final int[] VAD_flags = new int[MAX_FRAMES_PER_PACKET];

    /**
     * Whether the packet carries LBRR data.
     */
    public int LBRR_flag;

    /**
     * The LBRR flag of each frame of the packet.
     */
    public final int[] LBRR_flags = new int[MAX_FRAMES_PER_PACKET];

    /**
     * The state of the output resampler.
     */
    public final ResamplerState resampler_state = new ResamplerState();

    /**
     * The NLSF codebook.
     */
    public NlsfCodebook psNLSF_CB;

    /**
     * The quantization indices of the current frame.
     */
    public final SideInfoIndices indices = new SideInfoIndices();

    /**
     * The comfort noise generator state.
     */
    public final CngState sCNG = new CngState();

    /**
     * The number of consecutive lost frames.
     */
    public int lossCnt;

    /**
     * The signal type of the previous frame.
     */
    public int prevSignalType;

    /**
     * The packet loss concealment state.
     */
    public final PlcState sPLC = new PlcState();

    /**
     * Whether the channel behaves as the reference built with the deep PLC and OSCE.
     */
    public boolean dnn;

    /**
     * The speech enhancer of the channel, or {@code null} outside deep PLC mode; kept across resets.
     */
    public Osce osce;

    /**
     * The scratch buffer of the dequantized pulses of {@code silk_decode_frame}, rounded up to a
     * whole number of shell blocks.
     */
    final short[] scratchPulses = new short[(MAX_FRAME_LENGTH + SHELL_CODEC_FRAME_LENGTH - 1)
            & ~(SHELL_CODEC_FRAME_LENGTH - 1)];

    /**
     * The scratch buffer of the re-whitened LTP state of {@code silk_decode_core}.
     */
    final short[] scratchSLTP = new short[MAX_FRAME_LENGTH + 2 * MAX_SUB_FRAME_LENGTH];

    /**
     * The scratch buffer of the LTP state in Q15 of {@code silk_decode_core}.
     */
    final int[] scratchSLTP_Q15 = new int[2 * MAX_FRAME_LENGTH + 2 * MAX_SUB_FRAME_LENGTH];

    /**
     * The scratch buffer of the LPC residual of one subframe of {@code silk_decode_core}.
     */
    final int[] scratchResQ14 = new int[MAX_SUB_FRAME_LENGTH];

    /**
     * The scratch buffer of the LPC synthesis state of {@code silk_decode_core}.
     */
    final int[] scratchSLPC_Q14 = new int[MAX_SUB_FRAME_LENGTH + MAX_LPC_ORDER];

    /**
     * The scratch parameters of the frame being decoded.
     */
    final DecoderControl scratchControl = new DecoderControl();

    /**
     * The scratch buffer of the LTP input of {@code silk_PLC_conceal}.
     */
    final short[] scratchPlcSLTP = new short[MAX_FRAME_LENGTH + 2 * MAX_SUB_FRAME_LENGTH];

    /**
     * The scratch buffer of the LTP state in Q14 of {@code silk_PLC_conceal}.
     */
    final int[] scratchPlcSLTP_Q14 = new int[2 * MAX_FRAME_LENGTH + 2 * MAX_SUB_FRAME_LENGTH];

    /**
     * The scratch buffer of the scaled excitation of the last two subframes of
     * {@code silk_PLC_energy}.
     */
    final short[] scratchPlcExcBuf16 = new short[2 * MAX_SUB_FRAME_LENGTH];

    /**
     * The scratch buffer of the LPC coefficients of {@code silk_PLC_conceal}.
     */
    final short[] scratchPlcA_Q12 = new short[MAX_LPC_ORDER];

    /**
     * The scratch buffer of the gains of the last two subframes, in Q10, of {@code silk_PLC_conceal}.
     */
    final int[] scratchPlcPrevGain_Q10 = new int[2];

    /**
     * The scratch buffer of the packed energies of the last two subframes of {@code silk_PLC_conceal}.
     */
    final long[] scratchPlcEnergies = new long[2];

    /**
     * The scratch buffer of the comfort noise excitation of {@code silk_CNG}.
     */
    final int[] scratchCngSig = new int[MAX_FRAME_LENGTH + MAX_LPC_ORDER];

    /**
     * The scratch buffer of the comfort noise LPC coefficients of {@code silk_CNG}.
     */
    final short[] scratchCngA_Q12 = new short[MAX_LPC_ORDER];

    /**
     * The work arrays of the NLSF to LPC conversions of {@code silk_decode_parameters}.
     */
    final Nlsf2A.Scratch scratchNlsf2A = new Nlsf2A.Scratch();

    /**
     * The scratch NLSF vector of {@code silk_decode_parameters}.
     */
    final short[] scratchNLSF_Q15 = new short[MAX_LPC_ORDER];

    /**
     * The scratch interpolated NLSF vector of {@code silk_decode_parameters}.
     */
    final short[] scratchNLSF0_Q15 = new short[MAX_LPC_ORDER];

    /**
     * The scratch predictor coefficients of the NLSF decoding.
     */
    final short[] scratchPred_Q8 = new short[MAX_LPC_ORDER];

    /**
     * The scratch entropy table offsets of the NLSF decoding.
     */
    final short[] scratchEc_ix = new short[MAX_LPC_ORDER];

    /**
     * The scratch dequantized NLSF residuals.
     */
    final short[] scratchRes_Q10 = new short[MAX_LPC_ORDER];

    /**
     * The scratch pulse sums of {@code silk_decode_pulses}.
     */
    final int[] scratchSumPulses = new int[Define.MAX_NB_SHELL_BLOCKS];

    /**
     * The scratch least significant bit counts of {@code silk_decode_pulses}.
     */
    final int[] scratchNLshifts = new int[Define.MAX_NB_SHELL_BLOCKS];

    /**
     * The scratch sign distribution of {@code silk_decode_signs}.
     */
    final short[] scratchSignIcdf = new short[2];

    /**
     * Creates a zeroed state.
     */
    public DecoderState() {
    }

    /**
     * Zeroes every field of the reference structure, like the {@code silk_memset} of
     * {@code silk_reset_decoder} starting at {@code SILK_DECODER_STATE_RESET_START}.
     */
    void clear() {
        prev_gain_Q16 = 0;
        Arrays.fill(exc_Q14, 0);
        Arrays.fill(sLPC_Q14_buf, 0);
        Arrays.fill(outBuf, (short) 0);
        lagPrev = 0;
        LastGainIndex = 0;
        fs_kHz = 0;
        fs_API_hz = 0;
        nb_subfr = 0;
        frame_length = 0;
        subfr_length = 0;
        ltp_mem_length = 0;
        LPC_order = 0;
        Arrays.fill(prevNLSF_Q15, (short) 0);
        first_frame_after_reset = 0;
        pitch_lag_low_bits_iCDF = null;
        pitch_contour_iCDF = null;
        nFramesDecoded = 0;
        nFramesPerPacket = 0;
        ec_prevSignalType = 0;
        ec_prevLagIndex = 0;
        Arrays.fill(VAD_flags, 0);
        LBRR_flag = 0;
        Arrays.fill(LBRR_flags, 0);
        resampler_state.clear();
        psNLSF_CB = null;
        indices.clear();
        sCNG.clear();
        lossCnt = 0;
        prevSignalType = 0;
        sPLC.clear();
    }
}
