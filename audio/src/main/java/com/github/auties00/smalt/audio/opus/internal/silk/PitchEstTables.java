/* Ports libopus 1.5.2 (silk/pitch_est_defines.h, silk/pitch_est_tables.c). Copyright Skype Limited; BSD 3-Clause
   License. Modified for Smalt. */
package com.github.auties00.smalt.audio.opus.internal.silk;

/**
 * Holds the constants and codebooks of the SILK pitch estimator and pitch decoder.
 *
 * @implNote This implementation keeps the libopus names.
 */
public final class PitchEstTables {
    private PitchEstTables() {
        throw new AssertionError();
    }

    /**
     * The maximum sampling frequency used by the pitch estimator, in kHz.
     */
    public static final int PE_MAX_FS_KHZ = 16;

    /**
     * The maximum number of subframes.
     */
    public static final int PE_MAX_NB_SUBFR = 4;

    /**
     * The subframe length in milliseconds.
     */
    public static final int PE_SUBFR_LENGTH_MS = 5;

    /**
     * The LTP memory length in milliseconds.
     */
    public static final int PE_LTP_MEM_LENGTH_MS = 4 * PE_SUBFR_LENGTH_MS;

    /**
     * The maximum frame length in milliseconds, including the LTP memory.
     */
    public static final int PE_MAX_FRAME_LENGTH_MS = PE_LTP_MEM_LENGTH_MS + PE_MAX_NB_SUBFR * PE_SUBFR_LENGTH_MS;

    /**
     * The maximum frame length in samples.
     */
    public static final int PE_MAX_FRAME_LENGTH = PE_MAX_FRAME_LENGTH_MS * PE_MAX_FS_KHZ;

    /**
     * The maximum pitch lag in milliseconds (56 Hz).
     */
    public static final int PE_MAX_LAG_MS = 18;

    /**
     * The minimum pitch lag in milliseconds (500 Hz).
     */
    public static final int PE_MIN_LAG_MS = 2;

    /**
     * The maximum pitch lag in samples.
     */
    public static final int PE_MAX_LAG = PE_MAX_LAG_MS * PE_MAX_FS_KHZ;

    /**
     * The search length of the second stage.
     */
    public static final int PE_D_SRCH_LENGTH = 24;

    /**
     * The number of lags searched by the third stage.
     */
    public static final int PE_NB_STAGE3_LAGS = 5;

    /**
     * The number of codebooks of the second stage.
     */
    public static final int PE_NB_CBKS_STAGE2 = 3;

    /**
     * The number of codebooks of the extended second stage.
     */
    public static final int PE_NB_CBKS_STAGE2_EXT = 11;

    /**
     * The maximum number of codebooks of the third stage.
     */
    public static final int PE_NB_CBKS_STAGE3_MAX = 34;

    /**
     * The number of codebooks of the third stage at mid complexity.
     */
    public static final int PE_NB_CBKS_STAGE3_MID = 24;

    /**
     * The number of codebooks of the third stage at minimum complexity.
     */
    public static final int PE_NB_CBKS_STAGE3_MIN = 16;

    /**
     * The number of codebooks of the third stage for 10 ms frames.
     */
    public static final int PE_NB_CBKS_STAGE3_10MS = 12;

    /**
     * The number of codebooks of the second stage for 10 ms frames.
     */
    public static final int PE_NB_CBKS_STAGE2_10MS = 3;

    /**
     * The bias towards short lags, for logarithmic weighting.
     */
    public static final float PE_SHORTLAG_BIAS = 0.2f;

    /**
     * The bias towards the previous lag, for logarithmic weighting.
     */
    public static final float PE_PREVLAG_BIAS = 0.2f;

    /**
     * The bias towards a flat pitch contour.
     */
    public static final float PE_FLATCONTOUR_BIAS = 0.05f;

    /**
     * The minimum pitch estimator complexity.
     */
    public static final int SILK_PE_MIN_COMPLEX = 0;

    /**
     * The medium pitch estimator complexity.
     */
    public static final int SILK_PE_MID_COMPLEX = 1;

    /**
     * The maximum pitch estimator complexity.
     */
    public static final int SILK_PE_MAX_COMPLEX = 2;

    /**
     * The table {@code silk_CB_lags_stage2_10_ms} of {@code silk/pitch_est_tables.c}.
     */
    public static final byte[][] silk_CB_lags_stage2_10_ms = {
        {0, 1, 0},
        {0, 0, 1}
    };

    /**
     * The table {@code silk_CB_lags_stage3_10_ms} of {@code silk/pitch_est_tables.c}.
     */
    public static final byte[][] silk_CB_lags_stage3_10_ms = {
        {0, 0, 1, -1, 1, -1, 2, -2, 2, -2, 3, -3},
        {0, 1, 0, 1, -1, 2, -1, 2, -2, 3, -2, 3}
    };

    /**
     * The table {@code silk_Lag_range_stage3_10_ms} of {@code silk/pitch_est_tables.c}.
     */
    public static final byte[][] silk_Lag_range_stage3_10_ms = {
        {-3, 7},
        {-2, 7}
    };

    /**
     * The table {@code silk_CB_lags_stage2} of {@code silk/pitch_est_tables.c}.
     */
    public static final byte[][] silk_CB_lags_stage2 = {
        {0, 2, -1, -1, -1, 0, 0, 1, 1, 0, 1},
        {0, 1, 0, 0, 0, 0, 0, 1, 0, 0, 0},
        {0, 0, 1, 0, 0, 0, 1, 0, 0, 0, 0},
        {0, -1, 2, 1, 0, 1, 1, 0, 0, -1, -1}
    };

    /**
     * The table {@code silk_CB_lags_stage3} of {@code silk/pitch_est_tables.c}.
     */
    public static final byte[][] silk_CB_lags_stage3 = {
        {
            0, 0, 1, -1, 0, 1, -1, 0, -1, 1, -2, 2,
            -2, -2, 2, -3, 2, 3, -3, -4, 3, -4, 4, 4,
            -5, 5, -6, -5, 6, -7, 6, 5, 8, -9
        },
        {
            0, 0, 1, 0, 0, 0, 0, 0, 0, 0, -1, 1,
            0, 0, 1, -1, 0, 1, -1, -1, 1, -1, 2, 1,
            -1, 2, -2, -2, 2, -2, 2, 2, 3, -3
        },
        {
            0, 1, 0, 0, 0, 0, 0, 0, 1, 0, 1, 0,
            0, 1, -1, 1, 0, 0, 2, 1, -1, 2, -1, -1,
            2, -1, 2, 2, -1, 3, -2, -2, -2, 3
        },
        {
            0, 1, 0, 0, 1, 0, 1, -1, 2, -1, 2, -1,
            2, 3, -2, 3, -2, -2, 4, 4, -3, 5, -3, -4,
            6, -4, 6, 5, -5, 8, -6, -5, -7, 9
        }
    };

    /**
     * The table {@code silk_Lag_range_stage3} of {@code silk/pitch_est_tables.c}.
     */
    public static final byte[][][] silk_Lag_range_stage3 = {
        {
            {-5, 8},
            {-1, 6},
            {-1, 6},
            {-4, 10}
        },
        {
            {-6, 10},
            {-2, 6},
            {-1, 6},
            {-5, 10}
        },
        {
            {-9, 12},
            {-3, 7},
            {-2, 7},
            {-7, 13}
        }
    };

    /**
     * The table {@code silk_nb_cbk_searchs_stage3} of {@code silk/pitch_est_tables.c}.
     */
    public static final byte[] silk_nb_cbk_searchs_stage3 = {PE_NB_CBKS_STAGE3_MIN, PE_NB_CBKS_STAGE3_MID, PE_NB_CBKS_STAGE3_MAX};
}
