/* Ports opencore-amr 0.1.6 (cnst.h, cnst_vad.h, dtx_common_def.h, bitno_tab.h, bitreorder_tab.cpp, q_plsf.h,
   qua_gain.h, qgain475_tab.h, gains_tbl.cpp, amrdecode.h, amrencode.h). Copyright PacketVideo; Apache License 2.0.
   Modified for Smalt. */
package com.github.auties00.smalt.audio.amr.internal.nb;

/**
 * Holds the constants shared by the AMR-NB decoder and encoder: frame geometry, LPC order,
 * pitch range, quantizer sizes, parameter and bit counts, voice activity detection thresholds and
 * discontinuous transmission timing.
 *
 * <p>The voice activity detection constants that the reference derives from floating point
 * expressions (such as {@code (Word16)(0.65 * MAX_16)}) are stored as the integer values those
 * expressions produce.
 */
final class Constants {
    /**
     * The total size of the speech buffer.
     */
    static final int L_TOTAL = 320;

    /**
     * The LPC analysis window size.
     */
    static final int L_WINDOW = 240;

    /**
     * The frame size in samples.
     */
    static final int L_FRAME = 160;

    /**
     * Half the frame size.
     */
    static final int L_FRAME_BY2 = 80;

    /**
     * The subframe size in samples.
     */
    static final int L_SUBFR = 40;

    /**
     * The codevector length.
     */
    static final int L_CODE = 40;

    /**
     * The number of algebraic codebook tracks.
     */
    static final int NB_TRACK = 5;

    /**
     * The algebraic codebook track step.
     */
    static final int STEP = 5;

    /**
     * The number of algebraic codebook tracks of {@code MR102}.
     */
    static final int NB_TRACK_MR102 = 4;

    /**
     * The algebraic codebook track step of {@code MR102}.
     */
    static final int STEP_MR102 = 4;

    /**
     * The LPC order.
     */
    static final int M = 10;

    /**
     * The LPC order plus one, the size of one set of LPC coefficients.
     */
    static final int MP1 = M + 1;

    /**
     * The minimum distance between quantized LSFs.
     */
    static final short LSF_GAP = 205;

    /**
     * The LSF prediction factor of {@code MR122}, Q15.
     */
    static final short LSP_PRED_FAC_MR122 = 21299;

    /**
     * The size of the four sets of LPC coefficients of a frame.
     */
    static final int AZ_SIZE = 4 * M + 4;

    /**
     * The minimum pitch lag of {@code MR122}.
     */
    static final int PIT_MIN_MR122 = 18;

    /**
     * The minimum pitch lag of the other modes.
     */
    static final int PIT_MIN = 20;

    /**
     * The maximum pitch lag.
     */
    static final int PIT_MAX = 143;

    /**
     * The length of the fractional pitch interpolation filter.
     */
    static final int L_INTERPOL = 10 + 1;

    /**
     * The length of the interpolation filter of the fractional pitch search.
     */
    static final int L_INTER_SRCH = 4;

    /**
     * The postfilter tilt compensation factor, Q15.
     */
    static final short MU = 26214;

    /**
     * The automatic gain control factor, Q15.
     */
    static final short AGC_FAC = 29491;

    /**
     * The lookahead of the LPC analysis.
     */
    static final int L_NEXT = 40;

    /**
     * The maximum pitch sharpening gain, Q14.
     */
    static final short SHARPMAX = 13017;

    /**
     * The minimum pitch sharpening gain, Q14.
     */
    static final short SHARPMIN = 0;

    /**
     * The largest number of speech parameters of a frame.
     */
    static final int MAX_PRM_SIZE = 57;

    /**
     * The largest number of serial bits of a frame.
     */
    static final int MAX_SERIAL_SIZE = 244;

    /**
     * The pitch gain clipping threshold, Q14.
     */
    static final short GP_CLIP = 15565;

    /**
     * The number of past frames of the gain history.
     */
    static final int N_FRAME = 7;

    /**
     * The value of every sample of the encoder homing frame.
     */
    static final short EHF_MASK = 0x0008;

    /**
     * The number of Chebyshev grid intervals searched for LSP roots.
     */
    static final int GRID_POINTS = 60;

    /**
     * The value of a cleared serial bit.
     */
    static final short BIT_0 = 0;

    /**
     * The value of a set serial bit.
     */
    static final short BIT_1 = 1;

    /**
     * The number of mode indication bits of a received AMR silence descriptor.
     */
    static final int NUM_AMRSID_RXMODE_BITS = 3;

    /**
     * The serial position of the mode indication of a received AMR silence descriptor.
     */
    static final int AMRSID_RXMODE_BIT_OFFSET = 36;

    /**
     * The serial position of the type indicator (first or update) of a received AMR silence
     * descriptor.
     */
    static final int AMRSID_RXTYPE_BIT_OFFSET = 35;

    /**
     * The number of mode indication bits of a transmitted AMR silence descriptor.
     */
    static final int NUM_AMRSID_TXMODE_BITS = 3;

    /**
     * The serial position of the mode indication of a transmitted AMR silence descriptor.
     */
    static final int AMRSID_TXMODE_BIT_OFFSET = 36;

    /**
     * The serial position of the type indicator of a transmitted AMR silence descriptor.
     */
    static final int AMRSID_TXTYPE_BIT_OFFSET = 35;

    /**
     * The number of entries of the first LSF codebook of {@code MR795}.
     */
    static final int MR795_1_SIZE = 512;

    /**
     * The number of initial LSF prediction residual vectors.
     */
    static final int PAST_RQ_INIT_SIZE = 8;

    /**
     * The number of entries of the first 3-split LSF codebook.
     */
    static final int DICO1_SIZE = 256;

    /**
     * The number of entries of the second 3-split LSF codebook.
     */
    static final int DICO2_SIZE = 512;

    /**
     * The number of entries of the third 3-split LSF codebook.
     */
    static final int DICO3_SIZE = 512;

    /**
     * The number of entries of the first {@code MR122} LSF codebook.
     */
    static final int DICO1_5_SIZE = 128;

    /**
     * The number of entries of the second {@code MR122} LSF codebook.
     */
    static final int DICO2_5_SIZE = 256;

    /**
     * The number of entries of the third {@code MR122} LSF codebook.
     */
    static final int DICO3_5_SIZE = 256;

    /**
     * The number of entries of the fourth {@code MR122} LSF codebook.
     */
    static final int DICO4_5_SIZE = 256;

    /**
     * The number of entries of the fifth {@code MR122} LSF codebook.
     */
    static final int DICO5_5_SIZE = 64;

    /**
     * The number of entries of the third LSF codebook of {@code MR475} and {@code MR515}.
     */
    static final int MR515_3_SIZE = 128;

    /**
     * The number of entries of the joint gain codebook of {@code MR795} and {@code MR102}.
     */
    static final int VQ_SIZE_HIGHRATES = 128;

    /**
     * The number of entries of the joint gain codebook of {@code MR515} to {@code MR74}.
     */
    static final int VQ_SIZE_LOWRATES = 64;

    /**
     * The number of entries of the joint gain codebook of {@code MR475}.
     */
    static final int MR475_VQ_SIZE = 256;

    /**
     * The number of entries of the scalar pitch gain quantizer.
     */
    static final int NB_QUA_PITCH = 16;

    /**
     * The number of entries of the scalar code gain quantizer.
     */
    static final int NB_QUA_CODE = 32;

    /**
     * The voice activity detection frame length.
     */
    static final int FRAME_LEN = 160;

    /**
     * The number of voice activity detection sub-bands.
     */
    static final int COMPLEN = 9;

    /**
     * The reciprocal of {@link #COMPLEN}, Q15.
     */
    static final short INV_COMPLEN = 3641;

    /**
     * The voice activity detection lookahead.
     */
    static final int LOOKAHEAD = 40;

    /**
     * The shift of the voice activity detection level scale.
     */
    static final short UNIRSHFT = 6;

    /**
     * The tone detection threshold, Q15 ({@code (Word16)(0.65 * MAX_16)}).
     */
    static final short TONE_THR = 21298;

    /**
     * The fast upward noise adaptation factor, Q15.
     */
    static final short ALPHA_UP1 = 1638;

    /**
     * The fast downward noise adaptation factor, Q15.
     */
    static final short ALPHA_DOWN1 = 2097;

    /**
     * The slow upward noise adaptation factor, Q15.
     */
    static final short ALPHA_UP2 = 491;

    /**
     * The slow downward noise adaptation factor, Q15.
     */
    static final short ALPHA_DOWN2 = 1867;

    /**
     * The third adaptation factor, Q15.
     */
    static final short ALPHA3 = 1638;

    /**
     * The fourth adaptation factor, Q15.
     */
    static final short ALPHA4 = 3276;

    /**
     * The fifth adaptation factor, Q15.
     */
    static final short ALPHA5 = 16383;

    /**
     * The high voice activity threshold.
     */
    static final short VAD_THR_HIGH = 1260;

    /**
     * The low voice activity threshold.
     */
    static final short VAD_THR_LOW = 720;

    /**
     * The noise level at which the threshold starts decreasing.
     */
    static final short VAD_P1 = 0;

    /**
     * The noise level at which the threshold reaches its low value.
     */
    static final short VAD_P2 = 6300;

    /**
     * The slope of the threshold between {@link #VAD_P1} and {@link #VAD_P2}, Q15.
     */
    static final short VAD_SLOPE = -2808;

    /**
     * The number of stationary frames before the noise estimate adapts.
     */
    static final short STAT_COUNT = 20;

    /**
     * The minimum stationarity count of complex signal detection.
     */
    static final short CAD_MIN_STAT_COUNT = 5;

    /**
     * The level threshold of the stationarity estimate.
     */
    static final short STAT_THR_LEVEL = 184;

    /**
     * The stationarity threshold.
     */
    static final short STAT_THR = 1000;

    /**
     * The minimum noise level.
     */
    static final short NOISE_MIN = 40;

    /**
     * The maximum noise level.
     */
    static final short NOISE_MAX = 16000;

    /**
     * The initial noise level.
     */
    static final short NOISE_INIT = 150;

    /**
     * The noise level separating high and low noise hangover settings.
     */
    static final short HANG_NOISE_THR = 100;

    /**
     * The burst length in high noise.
     */
    static final short BURST_LEN_HIGH_NOISE = 4;

    /**
     * The hangover length in high noise.
     */
    static final short HANG_LEN_HIGH_NOISE = 7;

    /**
     * The burst length in low noise.
     */
    static final short BURST_LEN_LOW_NOISE = 5;

    /**
     * The hangover length in low noise.
     */
    static final short HANG_LEN_LOW_NOISE = 4;

    /**
     * The low power threshold of voice activity detection.
     */
    static final int VAD_POW_LOW = 15000;

    /**
     * The power threshold of pitch detection.
     */
    static final int POW_PITCH_THR = 343040;

    /**
     * The power threshold of complex signal detection.
     */
    static final int POW_COMPLEX_THR = 15000;

    /**
     * The coefficient of the 3rd order sub-band filter, Q15.
     */
    static final short COEFF3 = 13363;

    /**
     * The first coefficient of the 5th order sub-band filter, Q15.
     */
    static final short COEFF5_1 = 21955;

    /**
     * The second coefficient of the 5th order sub-band filter, Q15.
     */
    static final short COEFF5_2 = 6390;

    /**
     * The pitch lag difference threshold.
     */
    static final short LTHRESH = 4;

    /**
     * The number of lag differences counted for pitch detection.
     */
    static final short NTHRESH = 4;

    /**
     * The high adaptation threshold of complex signal detection, Q15.
     */
    static final short CVAD_THRESH_ADAPT_HIGH = 19660;

    /**
     * The low adaptation threshold of complex signal detection, Q15.
     */
    static final short CVAD_THRESH_ADAPT_LOW = 16383;

    /**
     * The in-noise threshold of complex signal detection, Q15.
     */
    static final short CVAD_THRESH_IN_NOISE = 21298;

    /**
     * The hangover threshold of complex signal detection, Q15.
     */
    static final short CVAD_THRESH_HANG = 22936;

    /**
     * The hangover limit of complex signal detection.
     */
    static final short CVAD_HANG_LIMIT = 100;

    /**
     * The hangover length of complex signal detection.
     */
    static final short CVAD_HANG_LENGTH = 250;

    /**
     * The low power reset threshold of complex signal detection, Q15.
     */
    static final short CVAD_LOWPOW_RESET = 13106;

    /**
     * The minimum correlation of complex signal detection, Q15.
     */
    static final short CVAD_MIN_CORR = 13106;

    /**
     * The slow adaptation factor of complex signal detection, Q15.
     */
    static final short CVAD_ADAPT_SLOW = 655;

    /**
     * The fast adaptation factor of complex signal detection, Q15.
     */
    static final short CVAD_ADAPT_FAST = 2621;

    /**
     * The very fast adaptation factor of complex signal detection, Q15.
     */
    static final short CVAD_ADAPT_REALLY_FAST = 6553;

    /**
     * The number of empty frames after which comfort noise generation stops updating.
     */
    static final short DTX_MAX_EMPTY_THRESH = 50;

    /**
     * The length of the comfort noise parameter history.
     */
    static final int DTX_HIST_SIZE = 8;

    /**
     * The number of frames since the last silence descriptor after which the hangover is taken
     * into account.
     */
    static final short DTX_ELAPSED_FRAMES_THRESH = 24 + 7 - 1;

    /**
     * The length of the discontinuous transmission hangover.
     */
    static final short DTX_HANG_CONST = 7;

    /**
     * Prevents instantiation.
     */
    private Constants() {
    }
}
