/* Ports libvpx 1.15.1 (tables of vp8/encoder/onyx_if.c and vp8/encoder/ratectrl.c). Copyright (c) 2010 The WebM
   project authors; BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.video.vp8.internal;

/**
 * Holds the constant tables of the good-quality VP8 encoder that the real-time encoder does not
 * use: the active best quantizers of boosted frames and the golden frame boost adjustment.
 */
final class GoodTables {
    /**
     * The best quantizer index of a key frame of a static clip at each worst quantizer index,
     * libvpx's {@code kf_low_motion_minq}.
     */
    static final int[] KF_LOW_MOTION_MINQ = {
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 1,
            1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3, 3, 3, 4, 4, 4, 5, 5, 5,
            5, 5, 6, 6, 6, 6, 7, 7, 8, 8, 8, 8, 9, 9, 10, 10, 10, 10, 11,
            11, 11, 11, 12, 12, 13, 13, 13, 13, 14, 14, 15, 15, 15, 15, 16, 16, 16, 16,
            17, 17, 18, 18, 18, 18, 19, 20, 20, 21, 21, 22, 23, 23
    };

    /**
     * The best quantizer index of a golden frame or alternate reference of a static group,
     * libvpx's {@code gf_low_motion_minq}.
     */
    static final int[] GF_LOW_MOTION_MINQ = {
            0, 0, 0, 0, 1, 1, 1, 1, 1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3,
            3, 4, 4, 4, 4, 5, 5, 5, 5, 6, 6, 6, 6, 7, 7, 7, 7, 8, 8,
            8, 8, 9, 9, 9, 9, 10, 10, 10, 10, 11, 11, 12, 12, 13, 13, 14, 14, 15,
            15, 16, 16, 17, 17, 18, 18, 19, 19, 20, 20, 21, 21, 22, 22, 23, 23, 24, 24,
            25, 25, 26, 26, 27, 27, 28, 28, 29, 29, 30, 30, 31, 31, 32, 32, 33, 33, 34,
            34, 35, 35, 36, 36, 37, 37, 38, 38, 39, 39, 40, 40, 41, 41, 42, 42, 43, 44,
            45, 46, 47, 48, 49, 50, 51, 52, 53, 54, 55, 56, 57, 58
    };

    /**
     * The best quantizer index of a golden frame or alternate reference of a group with some
     * motion, libvpx's {@code gf_mid_motion_minq}.
     */
    static final int[] GF_MID_MOTION_MINQ = {
            0, 0, 0, 0, 1, 1, 1, 1, 1, 1, 2, 2, 3, 3, 3, 4, 4, 4, 5,
            5, 5, 6, 6, 6, 7, 7, 7, 8, 8, 8, 9, 9, 9, 10, 10, 10, 10, 11,
            11, 11, 12, 12, 12, 12, 13, 13, 13, 14, 14, 14, 15, 15, 16, 16, 17, 17, 18,
            18, 19, 19, 20, 20, 21, 21, 22, 22, 23, 23, 24, 24, 25, 25, 26, 26, 27, 27,
            28, 28, 29, 29, 30, 30, 31, 31, 32, 32, 33, 33, 34, 34, 35, 35, 36, 36, 37,
            37, 38, 39, 39, 40, 40, 41, 41, 42, 42, 43, 43, 44, 45, 46, 47, 48, 49, 50,
            51, 52, 53, 54, 55, 56, 57, 58, 59, 60, 61, 62, 63, 64
    };

    /**
     * The adjustment of a golden frame's boost to the quantizer index, in percent, libvpx's
     * {@code vp8_gf_boost_qadjustment}.
     */
    static final int[] GF_BOOST_QADJUSTMENT = {
            80, 82, 84, 86, 88, 90, 92, 94, 96, 97, 98, 99, 100, 101, 102,
            103, 104, 105, 106, 107, 108, 109, 110, 111, 112, 113, 114, 115, 116, 117,
            118, 119, 120, 121, 122, 123, 124, 125, 126, 127, 128, 129, 130, 131, 132,
            133, 134, 135, 136, 137, 138, 139, 140, 141, 142, 143, 144, 145, 146, 147,
            148, 149, 150, 151, 152, 153, 154, 155, 156, 157, 158, 159, 160, 161, 162,
            163, 164, 165, 166, 167, 168, 169, 170, 171, 172, 173, 174, 175, 176, 177,
            178, 179, 180, 181, 182, 183, 184, 184, 185, 185, 186, 186, 187, 187, 188,
            188, 189, 189, 190, 190, 191, 191, 192, 192, 193, 193, 194, 194, 194, 194,
            195, 195, 196, 196, 197, 197, 198, 198
    };

    /**
     * Maps speeds to the threshold multiplier of {@code SPLITMV} with the first reference,
     * libvpx's {@code thresh_mult_map_split1} with its {@code GOOD} and {@code RT} speeds expanded
     * (pairs of value and the first speed of the next value).
     */
    static final int[] THRESH_SPLIT1 = {2500, 1, 1700, 3, 10000, 4, 25000, 5, RtTables.MAX, 7, 5000, 8, 10000, 9,
            25000, 10, RtTables.MAX, RtTables.MAX};

    /**
     * Maps speeds to the threshold multiplier of {@code SPLITMV} with the second and third
     * references, libvpx's {@code thresh_mult_map_split2}.
     */
    static final int[] THRESH_SPLIT2 = {5000, 1, 4500, 3, 20000, 4, 50000, 5, RtTables.MAX, 7, 10000, 8, 20000, 9,
            50000, 10, RtTables.MAX, RtTables.MAX};

    /**
     * Maps speeds to the test frequency of {@code SPLITMV} with the first reference, libvpx's
     * {@code mode_check_freq_map_split1}.
     */
    static final int[] FREQ_SPLIT1 = {0, 3, 2, 4, 7, 8, 2, 9, 7, RtTables.MAX};

    /**
     * Maps speeds to the test frequency of {@code SPLITMV} with the second and third
     * references, libvpx's {@code mode_check_freq_map_split2}.
     */
    static final int[] FREQ_SPLIT2 = {0, 2, 2, 3, 4, 4, 15, 8, 4, 9, 15, RtTables.MAX};

    /**
     * The cost of a vector bit in SAD units of 4x4 blocks at each quantizer index, libvpx's
     * {@code sad_per_bit4lut}.
     */
    static final int[] SAD_PER_BIT4 = {
            2, 2, 2, 2, 2, 2, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3,
            3, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 5, 5, 5, 5, 5, 5, 6, 6,
            6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 7, 7, 7, 7, 7, 7, 7, 7, 7,
            7, 7, 7, 7, 8, 8, 8, 8, 8, 9, 9, 9, 9, 9, 9, 10, 10, 10, 10,
            10, 10, 10, 10, 11, 11, 11, 11, 11, 11, 11, 11, 12, 12, 12, 12, 12, 12, 12,
            12, 13, 13, 13, 13, 13, 13, 13, 14, 14, 14, 14, 14, 15, 15, 15, 15, 16, 16,
            16, 16, 17, 17, 17, 18, 18, 18, 19, 19, 19, 20, 20, 20
    };

    /**
     * The probability of a macroblock with coefficients at each quantizer index before any frame
     * was coded at it, libvpx's {@code vp8cx_base_skip_false_prob}.
     */
    static final int[] BASE_SKIP_FALSE_PROB = {
            255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255,
            255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255,
            255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255,
            255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 251, 248, 244, 240,
            236, 232, 229, 225, 221, 217, 213, 208, 204, 199, 194, 190, 187, 183, 179,
            175, 172, 168, 164, 160, 157, 153, 149, 145, 142, 138, 134, 130, 127, 124,
            120, 117, 114, 110, 107, 104, 101, 98, 95, 92, 89, 86, 83, 80, 77,
            74, 71, 68, 65, 62, 59, 56, 53, 50, 47, 44, 41, 38, 35, 32,
            30, 28, 26, 24, 22, 20, 18, 16
    };

    private GoodTables() {
        throw new AssertionError();
    }
}
