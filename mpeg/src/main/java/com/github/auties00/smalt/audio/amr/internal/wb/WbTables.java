/* Ports opencore-amr 0.1.6 (oversamp_12k8_to_16k.cpp, band_pass_6k_7k.cpp, low_pass_filt_7k.cpp,
   phase_dispersion.cpp, homing_amr_wb_dec.cpp). Copyright PacketVideo; Apache License 2.0. Modified for Smalt. */
package com.github.auties00.smalt.audio.amr.internal.wb;

/**
 * Holds the constant tables of the AMR-WB decoder that the encoder does not share: the resampling, band-pass and
 * low-pass filter coefficients, the phase dispersion impulse responses and the decoder homing frames.
 */
final class WbTables {
    /**
     * The 12.8 kHz to 16 kHz interpolation filters, 4 phases of 24 taps, flattened ({@code fir_up} of
     * {@code oversamp_12k8_to_16k.cpp}).
     */
    static final short[] FIR_UP = {
            -1, 12, -33, 68, -119, 191, -291, 430, -634, 963, -1616, 3792,
            15317, -2496, 1288, -809, 542, -369, 247, -160, 96, -52, 23, -6,
            -4, 24, -62, 124, -213, 338, -510, 752, -1111, 1708, -2974, 8219,
            12368, -3432, 1881, -1204, 812, -552, 368, -235, 139, -73, 30, -7,
            -7, 30, -73, 139, -235, 368, -552, 812, -1204, 1881, -3432, 12368,
            8219, -2974, 1708, -1111, 752, -510, 338, -213, 124, -62, 24, -4,
            -6, 23, -52, 96, -160, 247, -369, 542, -809, 1288, -2496, 15317,
            3792, -1616, 963, -634, 430, -291, 191, -119, 68, -33, 12, -1
    };

    /**
     * The 30-tap 6 to 7 kHz band-pass filter of the high band ({@code fir_6k_7k} of {@code band_pass_6k_7k.cpp}).
     */
    static final short[] FIR_6K_7K = {
            -32, 47, 32, -27, -369, 1122, -1421, 0, 3798, -8880, 12349, -10984,
            3548, 7766, -18001, 22118, -18001, 7766, 3548, -10984, 12349, -8880, 3798, 0,
            -1421, 1122, -369, -27, 32, 47
    };

    /**
     * The 31-tap 7 kHz low-pass filter of the 23.85 kbit/s high band ({@code fir_7k} of {@code low_pass_filt_7k.cpp}).
     */
    static final short[] FIR_7K = {
            -21, 47, -89, 146, -203, 229, -177, 0, 335, -839, 1485, -2211,
            2931, -3542, 3953, 28682, 3953, -3542, 2931, -2211, 1485, -839, 335, 0,
            -177, 229, -203, 146, -89, 47, -21
    };

    /**
     * The strong phase dispersion impulse response ({@code ph_imp_low} of {@code phase_dispersion.cpp}).
     */
    static final short[] PH_IMP_LOW = {
            20182, 9693, 3270, -3437, 2864, -5240, 1589, -1357, 600, 3893, -1497, -698,
            1203, -5249, 1199, 5371, -1488, -705, -2887, 1976, 898, 721, -3876, 4227,
            -5112, 6400, -1032, -4725, 4093, -4352, 3205, 2130, -1996, -1835, 2648, -1786,
            -406, 573, 2484, -3608, 3139, -1363, -2566, 3808, -639, -2051, -541, 2376,
            3932, -6262, 1432, -3601, 4889, 370, 567, -1163, -2854, 1914, 39, -2418,
            3454, 2975, -4021, 3431
    };

    /**
     * The medium phase dispersion impulse response ({@code ph_imp_mid} of {@code phase_dispersion.cpp}).
     */
    static final short[] PH_IMP_MID = {
            24098, 10460, -5263, -763, 2048, -927, 1753, -3323, 2212, 652, -2146, 2487,
            -3539, 4109, -2107, -374, -626, 4270, -5485, 2235, 1858, -2769, 744, 1140,
            -763, -1615, 4060, -4574, 2982, -1163, 731, -1098, 803, 167, -714, 606,
            -560, 639, 43, -1766, 3228, -2782, 665, 763, 233, -2002, 1291, 1871,
            -3470, 1032, 2710, -4040, 3624, -4214, 5292, -4270, 1563, 108, -580, 1642,
            -2458, 957, 544, 2540
    };

    /**
     * The decoder homing frame of mode 7k as 15-bit parameter words ({@code dfh_M7k} of {@code homing_amr_wb_dec.cpp}).
     */
    static final short[] DHF_7K = {
            3168, 29954, 29213, 16121, 64, 13440, 30624, 16430, 19008
    };

    /**
     * The decoder homing frame of mode 9k as 15-bit parameter words ({@code dfh_M9k} of {@code homing_amr_wb_dec.cpp}).
     */
    static final short[] DHF_9K = {
            3168, 31665, 9943, 9123, 15599, 4358, 20248, 2048, 17040, 27787, 16816, 13888
    };

    /**
     * The decoder homing frame of mode 12k as 15-bit parameter words ({@code dfh_M12k} of
     * {@code homing_amr_wb_dec.cpp}).
     */
    static final short[] DHF_12K = {
            3168, 31665, 9943, 9128, 3647, 8129, 30930, 27926, 18880, 12319, 496, 1042,
            4061, 20446, 25629, 28069, 13948
    };

    /**
     * The decoder homing frame of mode 14k as 15-bit parameter words ({@code dfh_M14k} of
     * {@code homing_amr_wb_dec.cpp}).
     */
    static final short[] DHF_14K = {
            3168, 31665, 9943, 9131, 24815, 655, 26616, 26764, 7238, 19136, 6144, 88,
            4158, 25733, 30567, 30494, 221, 20321, 17823
    };

    /**
     * The decoder homing frame of mode 16k as 15-bit parameter words ({@code dfh_M16k} of
     * {@code homing_amr_wb_dec.cpp}).
     */
    static final short[] DHF_16K = {
            3168, 31665, 9943, 9131, 24815, 700, 3824, 7271, 26400, 9528, 6594, 26112,
            108, 2068, 12867, 16317, 23035, 24632, 7528, 1752, 6759, 24576
    };

    /**
     * The decoder homing frame of mode 18k as 15-bit parameter words ({@code dfh_M18k} of
     * {@code homing_amr_wb_dec.cpp}).
     */
    static final short[] DHF_18K = {
            3168, 31665, 9943, 9135, 14787, 14423, 30477, 24927, 25345, 30154, 916, 5728,
            18978, 2048, 528, 16449, 2436, 3581, 23527, 29479, 8237, 16810, 27091, 19052,
            0
    };

    /**
     * The decoder homing frame of mode 20k as 15-bit parameter words ({@code dfh_M20k} of
     * {@code homing_amr_wb_dec.cpp}).
     */
    static final short[] DHF_20K = {
            3168, 31665, 9943, 9129, 8637, 31807, 24646, 736, 28643, 2977, 2566, 25564,
            12930, 13960, 2048, 834, 3270, 4100, 26920, 16237, 31227, 17667, 15059, 20589,
            30249, 29123, 0
    };

    /**
     * The decoder homing frame of mode 23k as 15-bit parameter words ({@code dfh_M23k} of
     * {@code homing_amr_wb_dec.cpp}).
     */
    static final short[] DHF_23K = {
            3168, 31665, 9943, 9132, 16748, 3202, 28179, 16317, 30590, 15857, 19960, 8818,
            21711, 21538, 4260, 16690, 20224, 3666, 4194, 9497, 16320, 15388, 5755, 31551,
            14080, 3574, 15932, 50, 23392, 26053, 31216
    };

    /**
     * The decoder homing frame of mode 24k as 15-bit parameter words ({@code dfh_M24k} of
     * {@code homing_amr_wb_dec.cpp}).
     */
    static final short[] DHF_24K = {
            3168, 31665, 9943, 9134, 24776, 5857, 18475, 28535, 29662, 14321, 16725, 4396,
            29353, 10003, 17068, 20504, 720, 0, 8465, 12581, 28863, 24774, 9709, 26043,
            7941, 27649, 13965, 15236, 18026, 22047, 16681, 3968
    };

    /**
     * The number of bits of each mode up to the end of the first subframe, compared by the first-subframe homing test
     * ({@code prmnofsf} of {@code homing_amr_wb_dec.cpp}).
     */
    static final short[] DHF_FIRST_BITS = {
            63, 81, 100, 108, 116, 128, 136, 152, 156
    };

    /**
     * Prevents instantiation.
     */
    private WbTables() {
    }
}
