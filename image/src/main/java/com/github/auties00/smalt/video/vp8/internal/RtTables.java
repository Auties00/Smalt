/* Ports libvpx 1.17.0 (tables of vp8/encoder/boolhuff.c, ratectrl.c, onyx_if.c, rdopt.c, bitstream.c and
   vp8/common/entropymode.c). Copyright (c) 2010 The WebM project authors; BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.video.vp8.internal;

/**
 * Holds the constant tables of the real-time VP8 encoder: bit costs, rate control models, speed
 * feature maps, mode orders, and the bit patterns of the mode trees.
 */
final class RtTables {
    /**
     * The value that marks the end of a speed map, libvpx's {@code INT_MAX}.
     */
    static final int MAX = Integer.MAX_VALUE;

    /**
     * Holds the cost of coding a zero with each probability, in 1/256 bit, libvpx's
     * {@code vp8_prob_cost}.
     */
    static final int[] PROB_COST = {
            2047, 2047, 1791, 1641, 1535, 1452, 1385, 1328, 1279, 1235, 1196, 1161, 1129,
            1099, 1072, 1046, 1023, 1000, 979, 959, 940, 922, 905, 889, 873, 858,
            843, 829, 816, 803, 790, 778, 767, 755, 744, 733, 723, 713, 703,
            693, 684, 675, 666, 657, 649, 641, 633, 625, 617, 609, 602, 594,
            587, 580, 573, 567, 560, 553, 547, 541, 534, 528, 522, 516, 511,
            505, 499, 494, 488, 483, 477, 472, 467, 462, 457, 452, 447, 442,
            437, 433, 428, 424, 419, 415, 410, 406, 401, 397, 393, 389, 385,
            381, 377, 373, 369, 365, 361, 357, 353, 349, 346, 342, 338, 335,
            331, 328, 324, 321, 317, 314, 311, 307, 304, 301, 297, 294, 291,
            288, 285, 281, 278, 275, 272, 269, 266, 263, 260, 257, 255, 252,
            249, 246, 243, 240, 238, 235, 232, 229, 227, 224, 221, 219, 216,
            214, 211, 208, 206, 203, 201, 198, 196, 194, 191, 189, 186, 184,
            181, 179, 177, 174, 172, 170, 168, 165, 163, 161, 159, 156, 154,
            152, 150, 148, 145, 143, 141, 139, 137, 135, 133, 131, 129, 127,
            125, 123, 121, 119, 117, 115, 113, 111, 109, 107, 105, 103, 101,
            99, 97, 95, 93, 92, 90, 88, 86, 84, 82, 81, 79, 77,
            75, 73, 72, 70, 68, 66, 65, 63, 61, 60, 58, 56, 55,
            53, 51, 50, 48, 46, 45, 43, 41, 40, 38, 37, 35, 33,
            32, 30, 29, 27, 25, 24, 22, 21, 19, 18, 16, 15, 13,
            12, 10, 9, 7, 6, 4, 3, 1, 1
    };

    /**
     * Maps the user quantizer scale {@code [0, 63]} to the bitstream quantizer index
     * {@code [0, 127]}, libvpx's {@code q_trans}.
     */
    static final int[] Q_TRANS = {
            0, 1, 2, 3, 4, 5, 7, 8, 9, 10, 12, 13, 15, 17, 18, 19,
            20, 21, 23, 24, 25, 26, 27, 28, 29, 30, 31, 33, 35, 37, 39, 41,
            43, 45, 47, 49, 51, 53, 55, 57, 59, 61, 64, 67, 70, 73, 76, 79,
            82, 85, 88, 91, 94, 97, 100, 103, 106, 109, 112, 115, 118, 121, 124, 127
    };

    /**
     * Holds the expected bits per macroblock, times 512, at each quantizer index: key frames
     * first ({@code 450000 / q}), then inter frames ({@code 285000 / q}), libvpx's
     * {@code vp8_bits_per_mb}.
     */
    static final int[] BITS_PER_MB = {
            1125000, 900000, 750000, 642857, 562500, 500000, 450000, 450000, 409090,
            375000, 346153, 321428, 300000, 281250, 264705, 264705, 250000, 236842,
            225000, 225000, 214285, 214285, 204545, 204545, 195652, 195652, 187500,
            180000, 180000, 173076, 166666, 160714, 155172, 150000, 145161, 140625,
            136363, 132352, 128571, 125000, 121621, 121621, 118421, 115384, 112500,
            109756, 107142, 104651, 102272, 100000, 97826, 97826, 95744, 93750,
            91836, 90000, 88235, 86538, 84905, 83333, 81818, 80357, 78947,
            77586, 76271, 75000, 73770, 72580, 71428, 70312, 69230, 68181,
            67164, 66176, 65217, 64285, 63380, 62500, 61643, 60810, 60000,
            59210, 59210, 58441, 57692, 56962, 56250, 55555, 54878, 54216,
            53571, 52941, 52325, 51724, 51136, 50561, 49450, 48387, 47368,
            46875, 45918, 45000, 44554, 44117, 43269, 42452, 41666, 40909,
            40178, 39473, 38793, 38135, 36885, 36290, 35714, 35156, 34615,
            34090, 33582, 33088, 32608, 32142, 31468, 31034, 30405, 29801,
            29220, 28662,
            712500, 570000, 475000, 407142, 356250, 316666, 285000, 259090, 237500,
            219230, 203571, 190000, 178125, 167647, 158333, 150000, 142500, 135714,
            129545, 123913, 118750, 114000, 109615, 105555, 101785, 98275, 95000,
            91935, 89062, 86363, 83823, 81428, 79166, 77027, 75000, 73076,
            71250, 69512, 67857, 66279, 64772, 63333, 61956, 60638, 59375,
            58163, 57000, 55882, 54807, 53773, 52777, 51818, 50892, 50000,
            49137, 47500, 45967, 44531, 43181, 41911, 40714, 39583, 38513,
            37500, 36538, 35625, 34756, 33928, 33139, 32386, 31666, 30978,
            30319, 29687, 29081, 28500, 27941, 27403, 26886, 26388, 25909,
            25446, 25000, 24568, 23949, 23360, 22800, 22265, 21755, 21268,
            20802, 20357, 19930, 19520, 19127, 18750, 18387, 18037, 17701,
            17378, 17065, 16764, 16473, 16101, 15745, 15405, 15079, 14766,
            14467, 14179, 13902, 13636, 13380, 13133, 12895, 12666, 12445,
            12179, 11924, 11632, 11445, 11220, 11003, 10795, 10594, 10401,
            10215, 10035
    };

    /**
     * Holds the key frame boost adjustment in percent at each quantizer index, libvpx's
     * {@code kf_boost_qadjustment}.
     */
    static final int[] KF_BOOST_QADJUSTMENT = {
            128, 129, 130, 131, 132, 133, 134, 135, 136, 137, 138, 139, 140, 141, 142,
            143, 144, 145, 146, 147, 148, 149, 150, 151, 152, 153, 154, 155, 156, 157,
            158, 159, 160, 161, 162, 163, 164, 165, 166, 167, 168, 169, 170, 171, 172,
            173, 174, 175, 176, 177, 178, 179, 180, 181, 182, 183, 184, 185, 186, 187,
            188, 189, 190, 191, 192, 193, 194, 195, 196, 197, 198, 199, 200, 200, 201,
            201, 202, 203, 203, 203, 204, 204, 205, 205, 206, 206, 207, 207, 208, 208,
            209, 209, 210, 210, 211, 211, 212, 212, 213, 213, 214, 214, 215, 215, 216,
            216, 217, 217, 218, 218, 219, 219, 220, 220, 220, 220, 220, 220, 220, 220,
            220, 220, 220, 220, 220, 220, 220, 220
    };

    /**
     * Holds the lowest key frame quantizer index for each active worst quantizer index, libvpx's
     * {@code kf_high_motion_minq}.
     */
    static final int[] KF_HIGH_MOTION_MINQ = {
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 1, 1,
            1, 1, 2, 2, 2, 2, 3, 3, 3, 3, 3, 3, 3, 3, 4, 4, 4, 4, 5,
            5, 5, 5, 5, 5, 6, 6, 6, 6, 7, 7, 8, 8, 8, 8, 9, 9, 10, 10,
            10, 10, 11, 11, 11, 11, 12, 12, 13, 13, 13, 13, 14, 14, 15, 15, 15, 15, 16,
            16, 16, 16, 17, 17, 18, 18, 18, 18, 19, 19, 20, 20, 20, 20, 21, 21, 21, 21,
            22, 22, 23, 23, 24, 25, 25, 26, 26, 27, 28, 28, 29, 30
    };

    /**
     * Holds the lowest golden frame quantizer index for each active worst quantizer index,
     * libvpx's {@code gf_high_motion_minq}.
     */
    static final int[] GF_HIGH_MOTION_MINQ = {
            0, 0, 0, 0, 1, 1, 1, 1, 1, 2, 2, 2, 3, 3, 3, 4, 4, 4, 5,
            5, 5, 6, 6, 6, 7, 7, 7, 8, 8, 8, 9, 9, 9, 10, 10, 10, 11, 11,
            12, 12, 13, 13, 14, 14, 15, 15, 16, 16, 17, 17, 18, 18, 19, 19, 20, 20, 21,
            21, 22, 22, 23, 23, 24, 24, 25, 25, 26, 26, 27, 27, 28, 28, 29, 29, 30, 30,
            31, 31, 32, 32, 33, 33, 34, 34, 35, 35, 36, 36, 37, 37, 38, 38, 39, 39, 40,
            40, 41, 41, 42, 42, 43, 44, 45, 46, 47, 48, 49, 50, 51, 52, 53, 54, 55, 56,
            57, 58, 59, 60, 62, 64, 66, 68, 70, 72, 74, 76, 78, 80
    };

    /**
     * Holds the lowest inter frame quantizer index for each active worst quantizer index,
     * libvpx's {@code inter_minq}.
     */
    static final int[] INTER_MINQ = {
            0, 0, 1, 1, 2, 3, 3, 4, 4, 5, 6, 6, 7, 8, 8, 9, 9, 10, 11,
            11, 12, 13, 13, 14, 15, 15, 16, 17, 17, 18, 19, 20, 20, 21, 22, 22, 23, 24,
            24, 25, 26, 27, 27, 28, 29, 30, 30, 31, 32, 33, 33, 34, 35, 36, 36, 37, 38,
            39, 39, 40, 41, 42, 42, 43, 44, 45, 46, 46, 47, 48, 49, 50, 50, 51, 52, 53,
            54, 55, 55, 56, 57, 58, 59, 60, 60, 61, 62, 63, 64, 65, 66, 67, 67, 68, 69,
            70, 71, 72, 73, 74, 75, 75, 76, 77, 78, 79, 80, 81, 82, 83, 84, 85, 86, 86,
            87, 88, 89, 90, 91, 92, 93, 94, 95, 96, 97, 98, 99, 100
    };

    /**
     * Holds the weights of the five most recent key frame distances when averaging the key frame
     * frequency, libvpx's {@code prior_key_frame_weight}.
     */
    static final int[] PRIOR_KEY_FRAME_WEIGHT = {1, 2, 3, 4, 5};

    /**
     * Holds the motion search cost of one bit of motion vector, in SAD units, at each quantizer
     * index, libvpx's {@code sad_per_bit16lut}.
     */
    static final int[] SAD_PER_BIT16 = {
            2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 3, 3, 3,
            3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 4, 4, 4, 4, 4, 4, 4, 4,
            4, 4, 4, 4, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 6, 6, 6,
            6, 6, 6, 6, 6, 6, 6, 6, 6, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7,
            7, 7, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 9, 9, 9, 9, 9,
            9, 9, 9, 9, 9, 9, 9, 10, 10, 10, 10, 10, 10, 10, 10, 11, 11, 11, 11,
            11, 11, 12, 12, 12, 12, 12, 12, 13, 13, 13, 13, 14, 14
    };

    /**
     * Holds the macroblock mode tried at each position of the mode search, libvpx's
     * {@code vp8_mode_order}.
     */
    static final int[] MODE_ORDER = {
            Vp8FrameDecoder.ZEROMV, Vp8FrameDecoder.DC_PRED,
            Vp8FrameDecoder.NEARESTMV, Vp8FrameDecoder.NEARMV,
            Vp8FrameDecoder.ZEROMV, Vp8FrameDecoder.NEARESTMV,
            Vp8FrameDecoder.ZEROMV, Vp8FrameDecoder.NEARESTMV,
            Vp8FrameDecoder.NEARMV, Vp8FrameDecoder.NEARMV,
            Vp8FrameDecoder.V_PRED, Vp8FrameDecoder.H_PRED, Vp8FrameDecoder.TM_PRED,
            Vp8FrameDecoder.NEWMV, Vp8FrameDecoder.NEWMV, Vp8FrameDecoder.NEWMV,
            Vp8FrameDecoder.SPLITMV, Vp8FrameDecoder.SPLITMV, Vp8FrameDecoder.SPLITMV,
            Vp8FrameDecoder.B_PRED
    };

    /**
     * Holds the position, in the search order of the enabled references, of the reference tried
     * at each position of the mode search ({@code 0} is intra), libvpx's
     * {@code vp8_ref_frame_order}.
     */
    static final int[] REF_FRAME_ORDER = {1, 0, 1, 1, 2, 2, 3, 3, 2, 3, 0, 0, 0, 1, 2, 3, 1, 2, 3, 0};

    /**
     * The number of positions of the mode search.
     */
    static final int MAX_MODES = 20;

    /**
     * The mode search position of {@code NEARESTMV} with the first reference.
     */
    static final int THR_NEAREST1 = 2;

    /**
     * The mode search position of {@code NEARMV} with the first reference.
     */
    static final int THR_NEAR1 = 3;

    /**
     * The mode search position of {@code ZEROMV} with the second reference.
     */
    static final int THR_ZERO2 = 4;

    /**
     * The mode search position of {@code NEARESTMV} with the second reference.
     */
    static final int THR_NEAREST2 = 5;

    /**
     * The mode search position of {@code ZEROMV} with the third reference.
     */
    static final int THR_ZERO3 = 6;

    /**
     * The mode search position of {@code NEARESTMV} with the third reference.
     */
    static final int THR_NEAREST3 = 7;

    /**
     * The mode search position of {@code NEARMV} with the second reference.
     */
    static final int THR_NEAR2 = 8;

    /**
     * The mode search position of {@code NEARMV} with the third reference.
     */
    static final int THR_NEAR3 = 9;

    /**
     * The mode search position of {@code V_PRED}.
     */
    static final int THR_V_PRED = 10;

    /**
     * The mode search position of {@code H_PRED}.
     */
    static final int THR_H_PRED = 11;

    /**
     * The mode search position of {@code TM_PRED}.
     */
    static final int THR_TM = 12;

    /**
     * The mode search position of {@code NEWMV} with the first reference.
     */
    static final int THR_NEW1 = 13;

    /**
     * The mode search position of {@code NEWMV} with the second reference.
     */
    static final int THR_NEW2 = 14;

    /**
     * The mode search position of {@code NEWMV} with the third reference.
     */
    static final int THR_NEW3 = 15;

    /**
     * The mode search position of {@code B_PRED}.
     */
    static final int THR_B_PRED = 19;

    /**
     * Maps speeds to the threshold multiplier of {@code ZEROMV}, {@code NEARESTMV} and
     * {@code NEARMV} with the second and third references, libvpx's {@code thresh_mult_map_znn}
     * with its {@code GOOD} and {@code RT} speeds expanded (pairs of value and the first speed of
     * the next value).
     */
    static final int[] THRESH_ZNN = {0, 3, 1500, 4, 2000, 7, 1000, 9, 2000, MAX};

    /**
     * Maps speeds to the threshold multiplier of {@code V_PRED} and {@code H_PRED}, libvpx's
     * {@code thresh_mult_map_vhpred}.
     */
    static final int[] THRESH_VH = {1000, 3, 1500, 4, 2000, 7, 1000, 8, 2000, 14, MAX, MAX};

    /**
     * Maps speeds to the threshold multiplier of {@code B_PRED}, libvpx's
     * {@code thresh_mult_map_bpred}.
     */
    static final int[] THRESH_BPRED = {2000, 1, 2500, 3, 5000, 4, 7500, 7, 2500, 8, 5000, 13, MAX, MAX};

    /**
     * Maps speeds to the threshold multiplier of {@code TM_PRED}, libvpx's
     * {@code thresh_mult_map_tm}.
     */
    static final int[] THRESH_TM = {1000, 3, 1500, 4, 2000, 7, 0, 8, 1000, 9, 2000, 14, MAX, MAX};

    /**
     * Maps speeds to the threshold multiplier of {@code NEWMV} with the first reference,
     * libvpx's {@code thresh_mult_map_new1}.
     */
    static final int[] THRESH_NEW1 = {1000, 3, 2000, 7, 2000, MAX};

    /**
     * Maps speeds to the threshold multiplier of {@code NEWMV} with the second and third
     * references, libvpx's {@code thresh_mult_map_new2}.
     */
    static final int[] THRESH_NEW2 = {1000, 3, 2000, 4, 2500, 6, 4000, 7, 2000, 9, 2500, 12, 4000, MAX};

    /**
     * Maps speeds to the test frequency of {@code ZEROMV} and {@code NEARESTMV} with the second
     * and third references, libvpx's {@code mode_check_freq_map_zn2}.
     */
    static final int[] FREQ_ZN2 = {0, 17, 2, 18, 4, 19, 8, MAX};

    /**
     * Maps speeds to the test frequency of {@code V_PRED}, {@code H_PRED} and {@code B_PRED},
     * libvpx's {@code mode_check_freq_map_vhbpred}.
     */
    static final int[] FREQ_VHB = {0, 6, 2, 7, 0, 10, 2, 12, 4, MAX};

    /**
     * Maps speeds to the test frequency of {@code NEARMV} with the second and third references,
     * libvpx's {@code mode_check_freq_map_near2}.
     */
    static final int[] FREQ_NEAR2 = {0, 6, 2, 7, 0, 10, 2, 17, 4, 18, 8, 19, 16, MAX};

    /**
     * Maps speeds to the test frequency of {@code NEWMV} with the first reference, libvpx's
     * {@code mode_check_freq_map_new1}.
     */
    static final int[] FREQ_NEW1 = {0, 17, 2, 18, 4, 19, 8, MAX};

    /**
     * Maps speeds to the test frequency of {@code NEWMV} with the second and third references,
     * libvpx's {@code mode_check_freq_map_new2}.
     */
    static final int[] FREQ_NEW2 = {0, 6, 4, 7, 0, 10, 4, 17, 8, 18, 16, 19, 32, MAX};

    /**
     * Holds the bit patterns of the key-frame luma mode tree, {@code [mode] = path << 4 | length}
     * with the first decision in the highest bit of the path.
     */
    static final int[] KF_YMODE_CODES = codes(Vp8Tables.KF_YMODE_TREE, 5);

    /**
     * Holds the bit patterns of the inter-frame luma mode tree, laid out like
     * {@link #KF_YMODE_CODES}.
     */
    static final int[] YMODE_CODES = codes(Vp8Tables.YMODE_TREE, 5);

    /**
     * Holds the bit patterns of the chroma mode tree, laid out like {@link #KF_YMODE_CODES}.
     */
    static final int[] UV_MODE_CODES = codes(Vp8Tables.UV_MODE_TREE, 4);

    /**
     * Holds the bit patterns of the subblock mode tree, laid out like {@link #KF_YMODE_CODES}.
     */
    static final int[] BMODE_CODES = codes(Vp8Tables.BMODE_TREE, 10);

    /**
     * Holds the bit patterns of the short motion vector magnitude tree, laid out like
     * {@link #KF_YMODE_CODES}.
     */
    static final int[] SMALL_MV_CODES = codes(Vp8Tables.SMALL_MV_TREE, 8);

    /**
     * Prevents instantiation.
     */
    private RtTables() {
        throw new AssertionError();
    }

    /**
     * Returns the value a speed selects from a speed map, libvpx's {@code speed_map}.
     *
     * @param speed the continuous speed: the real-time speed plus 7
     * @param map   the pairs of value and first speed of the next value, ending with {@link #MAX}
     * @return the value
     */
    static int speedMap(int speed, int[] map) {
        var i = 0;
        int result;
        do {
            result = map[i++];
        } while (speed >= map[i++]);
        return result;
    }

    /**
     * Returns the cost of coding a bit with a probability, libvpx's {@code vp8_cost_bit}.
     *
     * @param prob the probability of a zero, {@code [0, 255]}
     * @param bit the coded bit, 0 or 1
     * @return the cost in 1/256 bit
     */
    static int bitCost(int prob, int bit) {
        return PROB_COST[bit == 0 ? prob : 255 - prob];
    }

    /**
     * Returns the cost of coding a symbol of a tree, libvpx's {@code vp8_cost_token}.
     *
     * @param codes the bit patterns of the tree, from {@link #codes}
     * @param tree the tree in libvpx layout
     * @param probs the node probabilities
     * @param off the index of the first node probability in {@code probs}
     * @param symbol the coded symbol, a leaf of the tree
     * @return the cost in 1/256 bit
     */
    static int treeCost(int[] codes, int[] tree, int[] probs, int off, int symbol) {
        var code = codes[symbol];
        var length = code & 15;
        var path = code >>> 4;
        var cost = 0;
        var node = 0;
        for (var n = length - 1; n >= 0; n--) {
            var bit = (path >>> n) & 1;
            cost += bitCost(probs[off + (node >> 1)], bit);
            node = tree[node + bit];
        }
        return cost;
    }

    /**
     * Codes a symbol of a tree, libvpx's {@code vp8_write_token}.
     *
     * @param bw the partition writer
     * @param codes the bit patterns of the tree, from {@link #codes}
     * @param tree the tree in libvpx layout
     * @param probs the node probabilities
     * @param off the index of the first node probability in {@code probs}
     * @param symbol the coded symbol, a leaf of the tree
     */
    static void writeTree(BoolEncoder bw, int[] codes, int[] tree, int[] probs, int off, int symbol) {
        var code = codes[symbol];
        var length = code & 15;
        var path = code >>> 4;
        var node = 0;
        for (var n = length - 1; n >= 0; n--) {
            var bit = (path >>> n) & 1;
            bw.putBit(bit, probs[off + (node >> 1)]);
            node = tree[node + bit];
        }
    }

    /**
     * Computes the bit pattern of every leaf of a tree.
     *
     * <p>Leaves are stored as negated symbols; the symbol {@code 0} is stored as {@code 0} and,
     * since no node points back to the root, is a leaf wherever it appears.
     *
     * @param tree    the tree in libvpx layout
     * @param symbols the number of symbols
     * @return {@code [symbol] = path << 4 | length}
     */
    private static int[] codes(int[] tree, int symbols) {
        var codes = new int[symbols];
        walk(tree, 0, 0, 0, codes);
        return codes;
    }

    /**
     * Records the bit patterns of the leaves below a node.
     *
     * @param tree the tree in libvpx layout
     * @param node the index of the node's first branch
     * @param path the bits leading to the node
     * @param length the number of those bits
     * @param codes receives the patterns
     */
    private static void walk(int[] tree, int node, int path, int length, int[] codes) {
        for (var bit = 0; bit < 2; bit++) {
            var next = tree[node + bit];
            var nextPath = (path << 1) | bit;
            if (next > 0) {
                walk(tree, next, nextPath, length + 1, codes);
            } else {
                codes[-next] = (nextPath << 4) | (length + 1);
            }
        }
    }
}
