package com.github.auties00.smalt.video.av1.internal.encoder;

import com.github.auties00.smalt.video.av1.internal.Cdf;

import java.util.Arrays;

/**
 * Holds the rate-distortion constants of one frame: the Lagrangian multiplier, the bit costs of
 * the mode and motion vector symbols derived from the frame's initial CDFs, and the statistical
 * rate and distortion models of the real-time mode decision.
 *
 * <p>Costs are in units of 1/512 bit; distortions are sums of squared errors scaled by 16, as
 * libaom's {@code RDCOST} expects.
 *
 * @implNote This implementation ports libaom's {@code av1_cost_tokens_from_cdf},
 * {@code av1_cost_symbol} and {@code av1_prob_cost} ({@code av1/encoder/cost.c}),
 * {@code av1_compute_rd_mult_based_on_qindex}, {@code av1_model_rd_from_var_lapndz},
 * {@code av1_model_rd_curvfit} and its tables ({@code av1/encoder/rd.c}),
 * {@code model_rd_with_curvfit} ({@code av1/encoder/model_rd.h}) and the motion vector
 * cost tables of {@code av1_build_nmv_cost_table} ({@code av1/encoder/encodemv.c}).
 */
final class RdCost {
    /**
     * The precision of costs, in bits.
     */
    static final int PROB_COST_SHIFT = 9;

    /**
     * The scaling of distortion in {@link #rdcost}.
     */
    static final int RDDIV_BITS = 7;

    /**
     * The weight of motion vector costs during motion search.
     */
    static final int MV_COST_WEIGHT = 108;

    /**
     * The costs of probabilities from one half to one ({@code av1_prob_cost}).
     */
    private static final int[] PROB_COST = {
            512, 506, 501, 495, 489, 484, 478, 473, 467, 462, 456, 451, 446, 441, 435,
            430, 425, 420, 415, 410, 405, 400, 395, 390, 385, 380, 375, 371, 366, 361,
            356, 352, 347, 343, 338, 333, 329, 324, 320, 316, 311, 307, 302, 298, 294,
            289, 285, 281, 277, 273, 268, 264, 260, 256, 252, 248, 244, 240, 236, 232,
            228, 224, 220, 216, 212, 209, 205, 201, 197, 194, 190, 186, 182, 179, 175,
            171, 168, 164, 161, 157, 153, 150, 146, 143, 139, 136, 132, 129, 125, 122,
            119, 115, 112, 109, 105, 102, 99, 95, 92, 89, 86, 82, 79, 76, 73,
            70, 66, 63, 60, 57, 54, 51, 48, 45, 42, 38, 35, 32, 29, 26,
            23, 20, 18, 15, 12, 9, 6, 3
    };

    /**
     * The cost of every probability from 1 to 32767, as {@link #computeCostSymbol} gives it.
     */
    private static final int[] SYMBOL_COST = new int[32768];

    static {
        for (var p = 1; p < 32768; p++) {
            SYMBOL_COST[p] = computeCostSymbol(p);
        }
    }

    /**
     * The rate curve of the curve-fit model by block size category ({@code interp_rgrid_curv}).
     */
    private static final double[][] RGRID = {
            {0.000000, 0.000000, 0.000000, 0.000000, 0.000000, 0.000000, 0.000000, 0.000000, 0.000000, 0.000000,
                    0.000000, 118.257702, 120.210658, 121.434853, 122.100487, 122.377758, 122.436865, 72.290102,
                    96.974289, 101.652727, 126.830141, 140.417377, 157.644879, 184.315291, 215.823873, 262.300169,
                    335.919859, 420.624173, 519.185032, 619.854243, 726.053595, 827.663369, 933.127475, 1037.988755,
                    1138.839609, 1233.342933, 1333.508064, 1428.760126, 1533.396364, 1616.952052, 1744.539319,
                    1803.413586, 1951.466618, 1994.227838, 2086.031680, 2148.635443, 2239.068450, 2222.590637,
                    2338.859809, 2402.929011, 2418.727875, 2435.342670, 2471.159469, 2523.187446, 2591.183827,
                    2674.905840, 2774.110714, 2888.555675, 3017.997952, 3162.194773, 3320.903365, 3493.880956,
                    3680.884773, 3881.672045, 4096.000000},
            {0.000000, 0.000000, 0.000000, 0.000000, 0.000000, 0.000000, 0.000000, 0.000000, 0.000000, 0.000000,
                    0.000000, 13.087244, 15.919735, 25.930313, 24.412411, 28.567417, 29.924194, 30.857010,
                    32.742979, 36.382570, 39.210386, 42.265690, 47.378572, 57.014850, 82.740067, 137.346562,
                    219.968084, 316.781856, 415.643773, 516.706538, 614.914364, 714.303763, 815.512135, 911.210485,
                    1008.501528, 1109.787854, 1213.772279, 1322.922561, 1414.752579, 1510.505641, 1615.741888,
                    1697.989032, 1780.123933, 1847.453790, 1913.742309, 1960.828122, 2047.500168, 2085.454095,
                    2129.230668, 2158.171824, 2182.231724, 2217.684864, 2269.589211, 2337.264824, 2420.618694,
                    2519.557814, 2633.989178, 2763.819779, 2908.956609, 3069.306660, 3244.776927, 3435.274401,
                    3640.706076, 3860.978945, 4096.000000},
            {0.000000, 0.000000, 0.000000, 0.000000, 0.000000, 0.000000, 0.000000, 0.000000, 0.000000, 0.000000,
                    0.000000, 4.656893, 5.123633, 5.594132, 6.162376, 6.918433, 7.768444, 8.739415, 10.105862,
                    11.477328, 13.236604, 15.421030, 19.093623, 25.801871, 46.724612, 98.841054, 181.113466,
                    272.586364, 359.499769, 445.546343, 525.944439, 605.188743, 681.793483, 756.668359, 838.486885,
                    926.950356, 1015.482542, 1113.353926, 1204.897193, 1288.871992, 1373.464145, 1455.746628,
                    1527.796460, 1588.475066, 1658.144771, 1710.302500, 1807.563351, 1863.197608, 1927.281616,
                    1964.450872, 2022.719898, 2100.041145, 2185.205712, 2280.993936, 2387.616216, 2505.282950,
                    2634.204540, 2774.591385, 2926.653884, 3090.602436, 3266.647443, 3454.999303, 3655.868416,
                    3869.465182, 4096.000000},
            {0.000000, 0.000000, 0.000000, 0.000000, 0.000000, 0.000000, 0.000000, 0.000000, 0.000000, 0.000000,
                    0.000000, 0.337370, 0.391916, 0.468839, 0.566334, 0.762564, 1.069225, 1.384361, 1.787581,
                    2.293948, 3.251909, 4.412991, 8.050068, 11.606073, 27.668092, 65.227758, 128.463938,
                    202.097653, 262.715851, 312.464873, 355.601398, 400.609054, 447.201352, 495.761568, 552.871938,
                    619.067625, 691.984883, 773.753288, 860.628503, 946.262808, 1019.805896, 1106.061360,
                    1178.422145, 1244.852258, 1302.173987, 1399.650266, 1548.092912, 1545.928652, 1670.817500,
                    1694.523823, 1779.195362, 1882.155494, 1990.662097, 2108.325181, 2235.456119, 2372.366287,
                    2519.367059, 2676.769812, 2844.885918, 3024.026754, 3214.503695, 3416.628115, 3630.711389,
                    3857.064892, 4096.000000}
    };

    /**
     * The distortion curve of the curve-fit model by normalized error category
     * ({@code interp_dgrid_curv}).
     */
    private static final double[][] DGRID = {
            {16.000000, 15.962891, 15.925174, 15.886888, 15.848074, 15.808770, 15.769015, 15.728850, 15.688313,
                    15.647445, 15.606284, 15.564870, 15.525918, 15.483820, 15.373330, 15.126844, 14.637442,
                    14.184387, 13.560070, 12.880717, 12.165995, 11.378144, 10.438769, 9.130790, 7.487633, 5.688649,
                    4.267515, 3.196300, 2.434201, 1.834064, 1.369920, 1.035921, 0.775279, 0.574895, 0.427232,
                    0.314123, 0.233236, 0.171440, 0.128188, 0.092762, 0.067569, 0.049324, 0.036330, 0.027008,
                    0.019853, 0.015539, 0.011093, 0.008733, 0.007624, 0.008105, 0.005427, 0.004065, 0.003427,
                    0.002848, 0.002328, 0.001865, 0.001457, 0.001103, 0.000801, 0.000550, 0.000348, 0.000193,
                    0.000085, 0.000021, 0.000000},
            {16.000000, 15.996116, 15.984769, 15.966413, 15.941505, 15.910501, 15.873856, 15.832026, 15.785466,
                    15.734633, 15.679981, 15.621967, 15.560961, 15.460157, 15.288367, 15.052462, 14.466922,
                    13.921212, 13.073692, 12.222005, 11.237799, 9.985848, 8.898823, 7.423519, 5.995325, 4.773152,
                    3.744032, 2.938217, 2.294526, 1.762412, 1.327145, 1.020728, 0.765535, 0.570548, 0.425833,
                    0.313825, 0.232959, 0.171324, 0.128174, 0.092750, 0.067558, 0.049319, 0.036330, 0.027008,
                    0.019853, 0.015539, 0.011093, 0.008733, 0.007624, 0.008105, 0.005427, 0.004065, 0.003427,
                    0.002848, 0.002328, 0.001865, 0.001457, 0.001103, 0.000801, 0.000550, 0.000348, 0.000193,
                    0.000085, 0.000021, -0.000000}
    };

    /**
     * The curve-fit rate category of each block size, in the decoder's block size order.
     */
    private static final int[] BSIZE_CURVFIT_CAT = new int[22];

    static {
        // libaom's table is indexed in libaom's ascending block size order; map it to the
        // decoder's descending order.
        int[] aomOrder = {0, 0, 0, 1, 1, 1, 2, 2, 2, 3, 3, 3, 3, 3, 3, 3, 1, 1, 2, 2, 3, 3};
        int[] aomToDav1d = {21, 20, 18, 17, 16, 13, 12, 11, 8, 7, 6, 4, 3, 2, 1, 0, 19, 14, 15, 9, 10, 5};
        for (var i = 0; i < aomOrder.length; i++) {
            BSIZE_CURVFIT_CAT[aomToDav1d[i]] = aomOrder[i];
        }
    }

    /**
     * The normalized rate of the Laplacian model ({@code rate_tab_q10}).
     */
    private static final int[] RATE_TAB_Q10 = {
            65536, 6086, 5574, 5275, 5063, 4899, 4764, 4651, 4553, 4389, 4255, 4142,
            4044, 3958, 3881, 3811, 3748, 3635, 3538, 3453, 3376, 3307, 3244, 3186,
            3133, 3037, 2952, 2877, 2809, 2747, 2690, 2638, 2589, 2501, 2423, 2353,
            2290, 2232, 2179, 2130, 2084, 2001, 1928, 1862, 1802, 1748, 1698, 1651,
            1608, 1530, 1460, 1398, 1342, 1290, 1243, 1199, 1159, 1086, 1021, 963,
            911, 864, 821, 781, 745, 680, 623, 574, 530, 490, 455, 424,
            395, 345, 304, 269, 239, 213, 190, 171, 154, 126, 104, 87,
            73, 61, 52, 44, 38, 28, 21, 16, 12, 10, 8, 6,
            5, 3, 2, 1, 1, 1, 0, 0
    };

    /**
     * The normalized distortion of the Laplacian model ({@code dist_tab_q10}).
     */
    private static final int[] DIST_TAB_Q10 = {
            0, 0, 1, 1, 1, 2, 2, 2, 3, 3, 4, 5,
            5, 6, 7, 7, 8, 9, 11, 12, 13, 15, 16, 17,
            18, 21, 24, 26, 29, 31, 34, 36, 39, 44, 49, 54,
            59, 64, 69, 73, 78, 88, 97, 106, 115, 124, 133, 142,
            151, 167, 184, 200, 215, 231, 245, 260, 274, 301, 327, 351,
            375, 397, 418, 439, 458, 495, 528, 559, 587, 613, 637, 659,
            680, 717, 749, 777, 801, 823, 842, 859, 874, 899, 919, 936,
            949, 960, 969, 977, 983, 994, 1001, 1006, 1010, 1013, 1015, 1017,
            1018, 1020, 1022, 1022, 1023, 1023, 1023, 1024
    };

    /**
     * The sample points of the Laplacian model tables ({@code xsq_iq_q10}).
     */
    private static final int[] XSQ_IQ_Q10 = {
            0, 4, 8, 12, 16, 20, 24, 28, 32,
            40, 48, 56, 64, 72, 80, 88, 96, 112,
            128, 144, 160, 176, 192, 208, 224, 256, 288,
            320, 352, 384, 416, 448, 480, 544, 608, 672,
            736, 800, 864, 928, 992, 1120, 1248, 1376, 1504,
            1632, 1760, 1888, 2016, 2272, 2528, 2784, 3040, 3296,
            3552, 3808, 4064, 4576, 5088, 5600, 6112, 6624, 7136,
            7648, 8160, 9184, 10208, 11232, 12256, 13280, 14304, 15328,
            16352, 18400, 20448, 22496, 24544, 26592, 28640, 30688, 32736,
            36832, 40928, 45024, 49120, 53216, 57312, 61408, 65504, 73696,
            81888, 90080, 98272, 106464, 114656, 122848, 131040, 147424, 163808,
            180192, 196576, 212960, 229344, 245728
    };

    /**
     * The Lagrangian multiplier of the frame.
     */
    int rdmult;

    /**
     * The Lagrangian multiplier of each segment: the frame's, or for the boosted segments of a
     * cyclic refresh the one of their finer quantizer ({@code av1_cyclic_refresh_get_rdmult}).
     */
    final int[] segmentRdmult = new int[8];

    /**
     * The SAD weight of a motion vector bit in the full-sample search ({@code sadperbit}).
     */
    int sadPerBit;

    /**
     * The error weight of a motion vector bit in the sub-sample search ({@code errorperbit}).
     */
    int errorPerBit;

    /**
     * The costs of the skip flag, as {@code [ctx 3][bit 2]}.
     */
    final int[] skip = new int[3 * 2];

    /**
     * The costs of the intra flag, as {@code [ctx 4][is_inter 2]}.
     */
    final int[] intraInter = new int[4 * 2];

    /**
     * The costs of the single reference flags, as {@code [decision 6][ctx 3][bit 2]} in the
     * decoder's CDF order.
     */
    final int[] singleRef = new int[6 * 3 * 2];

    /**
     * The costs of the new motion vector flag, as {@code [ctx 6][bit 2]}.
     */
    final int[] newmv = new int[6 * 2];

    /**
     * The costs of the global motion vector flag, as {@code [ctx 2][bit 2]}.
     */
    final int[] globalmv = new int[2 * 2];

    /**
     * The costs of the reference motion vector flag, as {@code [ctx 6][bit 2]}.
     */
    final int[] refmv = new int[6 * 2];

    /**
     * The costs of the reference motion vector index flags, as {@code [ctx 3][bit 2]}.
     */
    final int[] drl = new int[3 * 2];

    /**
     * The costs of the key frame luma modes, as {@code [above 5][left 5][mode 13]}.
     */
    final int[] kfYMode = new int[5 * 5 * 13];

    /**
     * The costs of the inter frame luma modes, as {@code [size group 4][mode 13]}.
     */
    final int[] yMode = new int[4 * 13];

    /**
     * The costs of the motion vector joints.
     */
    final int[] mvJoint = new int[4];

    /**
     * The cost of each angle delta of each directional mode, as {@code [(mode - 1) * 7 + delta + 3]}.
     */
    final int[] angleDelta = new int[8 * 7];

    /**
     * The cost of each interpolation filter by context, as {@code [ctx * 3 + filter]}.
     */
    final int[] interpFilter = new int[16 * 3];

    /**
     * The costs of the motion vector components, indexed by component and difference plus
     * {@link #MV_MAX}, for the frame's precision.
     */
    final int[][] mvComp = new int[2][2 * MV_MAX + 1];

    /**
     * The largest motion vector difference in 1/8 samples.
     */
    static final int MV_MAX = (1 << 14) - 1;

    /**
     * Creates empty tables.
     */
    RdCost() {
    }

    /**
     * Returns the cost of a probability ({@code av1_cost_symbol}).
     *
     * @param p15 the probability scaled by 32768
     * @return the cost
     */
    static int costSymbol(int p15) {
        return SYMBOL_COST[Math.clamp(p15, 1, 32767)];
    }

    /**
     * Computes the cost of a probability ({@code av1_cost_symbol}).
     *
     * @param p15 the probability scaled by 32768, from 1 to 32767
     * @return the cost
     */
    private static int computeCostSymbol(int p15) {
        var shift = 15 - 1 - (31 - Integer.numberOfLeadingZeros(p15));
        var prob = Math.clamp((int) (((long) (p15 << shift) * 256 + 16384) / 32768), 1, 255);
        return PROB_COST[prob - 128] + (shift << PROB_COST_SHIFT);
    }

    /**
     * Fills the costs of every symbol of a CDF ({@code av1_cost_tokens_from_cdf}).
     *
     * @param costs receives one cost per symbol
     * @param out the offset of the first cost
     * @param cdf the CDFs, in the decoder's inverse layout
     * @param off the offset of the CDF
     * @param n the number of symbols minus one
     */
    static void costsFromCdf(int[] costs, int out, char[] cdf, int off, int n) {
        var prev = 0;
        for (var i = 0; i <= n; i++) {
            var cur = i < n ? 32768 - cdf[off + i] : 32768;
            var p15 = Math.max(cur - prev, 4);
            prev = cur;
            costs[out + i] = costSymbol(p15);
        }
    }

    /**
     * The costs of the compound flag, by context and flag.
     */
    final int[] compFlag = new int[5 * 2];

    /**
     * The costs of the compound direction, by context and direction (0 unidirectional).
     */
    final int[] compDir = new int[5 * 2];

    /**
     * The costs of the bidirectional compound forward reference bits, by tree node and context.
     */
    final int[] compFwdRef = new int[9 * 2];

    /**
     * The costs of the bidirectional compound backward reference bits, by tree node and context.
     */
    final int[] compBwdRef = new int[6 * 2];

    /**
     * The costs of each compound inter mode, by context.
     */
    final int[] compMode = new int[8 * 8];

    /**
     * The costs of the skip mode flag, by context and flag.
     */
    final int[] skipMode = new int[3 * 2];

    /**
     * Fills the costs of the compound symbols from a frame's initial CDFs.
     *
     * @param cdf the CDFs
     */
    private void setupCompound(char[] cdf) {
        for (var c = 0; c < 5; c++) {
            costsFromCdf(compFlag, c * 2, cdf, Cdf.COMP + c * 2, 1);
            costsFromCdf(compDir, c * 2, cdf, Cdf.COMP_DIR + c * 2, 1);
        }
        for (var i = 0; i < 9; i++) {
            costsFromCdf(compFwdRef, i * 2, cdf, Cdf.COMP_FWD_REF + i * 2, 1);
        }
        for (var i = 0; i < 6; i++) {
            costsFromCdf(compBwdRef, i * 2, cdf, Cdf.COMP_BWD_REF + i * 2, 1);
        }
        for (var c = 0; c < 8; c++) {
            costsFromCdf(compMode, c * 8, cdf, Cdf.COMP_INTER_MODE + c * 8, 7);
        }
        for (var c = 0; c < 3; c++) {
            costsFromCdf(skipMode, c * 2, cdf, Cdf.SKIP_MODE + c * 2, 1);
        }
    }

    /**
     * Scales the frame's Lagrangian multipliers, as libaom's layer depth and boost factors do for
     * the frames of a pyramid ({@code av1_compute_rd_mult}).
     *
     * @param scale the scale in 1/128 units
     */
    void scale(int scale) {
        rdmult = (int) Math.clamp(((long) rdmult * scale) >> 7, 1, Integer.MAX_VALUE);
        Arrays.fill(segmentRdmult, rdmult);
        errorPerBit = Math.max(rdmult >> 6, 1);
    }

    /**
     * Prepares the multiplier and cost tables of a frame.
     *
     * @param cdf the frame's initial CDFs
     * @param qindex the base quantizer index
     * @param key whether the frame is a key frame
     * @param golden whether the frame refreshes the golden reference
     * @param hp whether motion vectors have eighth-sample precision
     */
    void setup(char[] cdf, int qindex, boolean key, boolean golden, boolean hp) {
        setupCompound(cdf);
        rdmult = rdmult(qindex, key, golden);
        Arrays.fill(segmentRdmult, rdmult);
        errorPerBit = Math.max(rdmult >> 6, 1);
        sadPerBit = (int) (0.0418 * (Quantizer.acQuant(qindex, 0) / 4.0) + 2.4107);
        for (var c = 0; c < 3; c++) {
            costsFromCdf(skip, c * 2, cdf, Cdf.SKIP + c * 2, 1);
            costsFromCdf(drl, c * 2, cdf, Cdf.DRL_BIT + c * 2, 1);
        }
        for (var c = 0; c < 4; c++) {
            costsFromCdf(intraInter, c * 2, cdf, Cdf.INTRA + c * 2, 1);
        }
        for (var i = 0; i < 18; i++) {
            costsFromCdf(singleRef, i * 2, cdf, Cdf.REF + i * 2, 1);
        }
        for (var c = 0; c < 6; c++) {
            costsFromCdf(newmv, c * 2, cdf, Cdf.NEWMV_MODE + c * 2, 1);
            costsFromCdf(refmv, c * 2, cdf, Cdf.REFMV_MODE + c * 2, 1);
        }
        for (var c = 0; c < 2; c++) {
            costsFromCdf(globalmv, c * 2, cdf, Cdf.GLOBALMV_MODE + c * 2, 1);
        }
        for (var c = 0; c < 25; c++) {
            costsFromCdf(kfYMode, c * 13, cdf, Cdf.KFYM + c * 16, 12);
        }
        for (var c = 0; c < 4; c++) {
            costsFromCdf(yMode, c * 13, cdf, Cdf.Y_MODE + c * 16, 12);
        }
        costsFromCdf(mvJoint, 0, cdf, Cdf.MV_JOINT, 3);
        for (var c = 0; c < 16; c++) {
            costsFromCdf(interpFilter, c * 3, cdf, Cdf.FILTER + c * 4, 2);
        }
        for (var m = 0; m < 8; m++) {
            costsFromCdf(angleDelta, m * 7, cdf, Cdf.ANGLE_DELTA + m * 8, 6);
        }
        for (var comp = 0; comp < 2; comp++) {
            buildMvComponent(mvComp[comp], cdf, comp * Cdf.MV_COMP_STRIDE, hp);
        }
    }

    /**
     * Returns the Lagrangian multiplier of a quantizer index ({@code av1_compute_rd_mult_based_on_qindex}).
     *
     * @param qindex the quantizer index
     * @param key whether the frame is a key frame
     * @param golden whether the frame is a golden frame
     * @return the multiplier
     */
    static int rdmult(int qindex, boolean key, boolean golden) {
        var q = Quantizer.dcQuant(qindex, 0);
        var rdmult = (long) q * q;
        var mult = key ? 3.3 + 0.0015 * q : golden ? 3.25 + 0.0015 * q : 3.2 + 0.0015 * q;
        rdmult = (long) (rdmult * mult);
        return rdmult > 0 ? (int) Math.min(rdmult, Integer.MAX_VALUE) : 1;
    }

    /**
     * Builds the cost of every difference of one motion vector component
     * ({@code build_nmv_component_cost_table}).
     *
     * @param out receives the costs, indexed by difference plus {@link #MV_MAX}
     * @param cdf the CDFs
     * @param comp the offset of the component's CDFs
     * @param hp whether eighth-sample precision is coded
     */
    private void buildMvComponent(int[] out, char[] cdf, int comp, boolean hp) {
        var sign = mvSign;
        var classes = mvClasses;
        var class0 = mvClass0;
        var bits = mvBits;
        var class0Fp = mvClass0Fp;
        var fp = mvFp;
        var class0Hp = mvClass0Hp;
        var hpCost = mvHp;
        costsFromCdf(sign, 0, cdf, comp + Cdf.MV_SIGN, 1);
        costsFromCdf(classes, 0, cdf, comp + Cdf.MV_CLASSES, 10);
        costsFromCdf(class0, 0, cdf, comp + Cdf.MV_CLASS0, 1);
        for (var i = 0; i < 10; i++) {
            costsFromCdf(bits, i * 2, cdf, comp + Cdf.MV_CLASSN + i * 2, 1);
        }
        for (var i = 0; i < 2; i++) {
            costsFromCdf(class0Fp, i * 4, cdf, comp + Cdf.MV_CLASS0_FP + i * 4, 3);
        }
        costsFromCdf(fp, 0, cdf, comp + Cdf.MV_CLASSN_FP, 3);
        costsFromCdf(class0Hp, 0, cdf, comp + Cdf.MV_CLASS0_HP, 1);
        costsFromCdf(hpCost, 0, cdf, comp + Cdf.MV_CLASSN_HP, 1);
        out[MV_MAX] = 0;
        var intBits = mvIntBits;
        for (var c = 0; c <= 10; c++) {
            var base = c != 0 ? 2 << (c + 2) : 0;
            var ints = c == 0 ? 2 : 1 << c;
            if (c != 0) {
                var zeros = 0;
                for (var i = 0; i < c; i++) {
                    zeros += bits[i * 2];
                }
                intBits[0] = zeros;
                for (var d = 1; d < ints; d++) {
                    var low = Integer.numberOfTrailingZeros(d);
                    intBits[d] = intBits[d & (d - 1)] + bits[low * 2 + 1] - bits[low * 2];
                }
            }
            for (var d = 0; d < ints; d++) {
                var intCost = classes[c] + (c == 0 ? class0[d] : intBits[d]);
                for (var f = 0; f < 4; f++) {
                    var fracCost = intCost + (c == 0 ? class0Fp[d * 4 + f] : fp[f]);
                    for (var e = 0; e < 2; e++) {
                        var v = base + (d << 3) + (f << 1) + e + 1;
                        if (v > MV_MAX) {
                            break;
                        }
                        var cost = fracCost + (hp ? (c == 0 ? class0Hp[e] : hpCost[e]) : 0);
                        out[MV_MAX + v] = cost + sign[0];
                        out[MV_MAX - v] = cost + sign[1];
                    }
                }
            }
        }
    }

    /**
     * The sign costs of the component being built.
     */
    private final int[] mvSign = new int[2];

    /**
     * The class costs of the component being built.
     */
    private final int[] mvClasses = new int[11];

    /**
     * The class 0 integer costs of the component being built.
     */
    private final int[] mvClass0 = new int[2];

    /**
     * The integer bit costs of the component being built, as {@code [bit * 2 + value]}.
     */
    private final int[] mvBits = new int[10 * 2];

    /**
     * The class 0 fraction costs of the component being built.
     */
    private final int[] mvClass0Fp = new int[2 * 4];

    /**
     * The fraction costs of the component being built.
     */
    private final int[] mvFp = new int[4];

    /**
     * The class 0 high-precision bit costs of the component being built.
     */
    private final int[] mvClass0Hp = new int[2];

    /**
     * The high-precision bit costs of the component being built.
     */
    private final int[] mvHp = new int[2];

    /**
     * The integer part costs of the class being built.
     */
    private final int[] mvIntBits = new int[1 << 10];

    /**
     * Returns the cost of coding a motion vector against a predictor ({@code av1_mv_bit_cost}).
     *
     * @param mvRow the motion vector row in 1/8 samples
     * @param mvCol the motion vector column in 1/8 samples
     * @param refRow the predictor row
     * @param refCol the predictor column
     * @param weight the weight, {@link #MV_COST_WEIGHT} or 128
     * @return the weighted cost
     */
    int mvBitCost(int mvRow, int mvCol, int refRow, int refCol, int weight) {
        var dr = Math.clamp(mvRow - refRow, -MV_MAX, MV_MAX);
        var dc = Math.clamp(mvCol - refCol, -MV_MAX, MV_MAX);
        var joint = (dr != 0 ? 2 : 0) | (dc != 0 ? 1 : 0);
        var cost = mvJoint[joint] + mvComp[0][MV_MAX + dr] + mvComp[1][MV_MAX + dc];
        return (cost * weight + 64) >> 7;
    }

    /**
     * Returns the rate-distortion cost of a rate and a distortion ({@code RDCOST}).
     *
     * @param rate the rate in 1/512 bit
     * @param dist the distortion, squared error times 16
     * @return the cost
     */
    long rdcost(int rate, long dist) {
        return rdcost(rdmult, rate, dist);
    }

    /**
     * Returns the Lagrangian cost of a rate and a distortion with a given multiplier
     * ({@code RDCOST}).
     *
     * @param rdmult the multiplier
     * @param rate the rate in {@code 1 << PROB_COST_SHIFT} units
     * @param dist the distortion
     * @return the cost
     */
    static long rdcost(int rdmult, long rate, long dist) {
        return ((rate * rdmult + (1 << (PROB_COST_SHIFT - 1))) >> PROB_COST_SHIFT) + (dist << RDDIV_BITS);
    }

    /**
     * Interpolates four samples with a cubic ({@code interp_cubic}).
     *
     * @param p the table
     * @param off the offset of the four samples
     * @param x the fractional position
     * @return the interpolated value
     */
    private static double interpCubic(double[] p, int off, double x) {
        return p[off + 1] + 0.5 * x * (p[off + 2] - p[off] + x * (2.0 * p[off] - 5.0 * p[off + 1]
                + 4.0 * p[off + 2] - p[off + 3] + x * (3.0 * (p[off + 1] - p[off + 2]) + p[off + 3] - p[off])));
    }

    /**
     * Estimates the luma rate and distortion of a block's residual from its sum of squared errors
     * ({@code model_rd_with_curvfit}).
     *
     * @param bs the block size
     * @param sse the sum of squared errors
     * @param numSamples the number of samples
     * @param qstepAc the AC dequantizer
     * @param out receives the rate in {@code [0]} and the distortion in {@code [1]}
     */
    void modelCurvfit(int bs, long sse, int numSamples, int qstepAc, long[] out) {
        modelCurvfit(rdmult, bs, sse, numSamples, qstepAc, out);
    }

    /**
     * Estimates the rate and distortion of a residual with the curve-fit model, deciding the skip
     * with a given multiplier ({@code model_rd_with_curvfit}).
     *
     * @param rdmult the multiplier
     * @param bs the block size
     * @param sse the sum of squared errors
     * @param numSamples the number of samples
     * @param qstepAc the AC dequantizer
     * @param out receives the rate in {@code [0]} and the distortion in {@code [1]}
     */
    static void modelCurvfit(int rdmult, int bs, long sse, int numSamples, int qstepAc, long[] out) {
        if (sse == 0) {
            out[0] = 0;
            out[1] = 0;
            return;
        }
        var qstep = Math.max(qstepAc >> 3, 1);
        var sseNorm = (double) sse / numSamples;
        var xqr = Math.log(sseNorm / ((double) qstep * qstep)) / Math.log(2);
        xqr = Math.clamp(xqr, -15.5 + 0.5 + 1e-6, 16.5 - 0.5 - 1e-6);
        var x = (xqr + 15.5) / 0.5;
        var xi = (int) Math.floor(x);
        var xo = x - xi;
        var rateF = interpCubic(RGRID[BSIZE_CURVFIT_CAT[bs]], xi - 1, xo);
        var distBySse = interpCubic(DGRID[sseNorm > 16.0 ? 1 : 0], xi - 1, xo);
        var distF = distBySse * sseNorm;
        var rate = (int) (Math.max(0.0, rateF * numSamples) + 0.5);
        var dist = (long) (Math.max(0.0, distF * numSamples) + 0.5);
        if (rate == 0) {
            dist = sse << 4;
        } else if (rdcost(rdmult, rate, dist) >= rdcost(rdmult, 0, sse << 4)) {
            rate = 0;
            dist = sse << 4;
        }
        out[0] = rate;
        out[1] = dist;
    }

    /**
     * Estimates the rate and distortion of a Laplacian source quantized uniformly
     * ({@code av1_model_rd_from_var_lapndz}).
     *
     * @param var the variance times the number of samples
     * @param nLog2 the base-2 logarithm of the number of samples
     * @param qstep the quantizer step
     * @param out receives the rate in {@code [0]} and the distortion in {@code [1]}
     */
    static void modelLapndz(long var, int nLog2, int qstep, long[] out) {
        if (var == 0) {
            out[0] = 0;
            out[1] = 0;
            return;
        }
        var xsq = (((long) qstep * qstep << (nLog2 + 10)) + (var >> 1)) / var;
        var xsqQ10 = (int) Math.min(xsq, 245727);
        var tmp = (xsqQ10 >> 2) + 8;
        var k = 31 - Integer.numberOfLeadingZeros(tmp) - 3;
        var xq = (k << 3) + ((tmp >> k) & 7);
        var aQ10 = ((xsqQ10 - XSQ_IQ_Q10[xq]) << 10) >> (2 + k);
        var bQ10 = (1 << 10) - aQ10;
        var rQ10 = (RATE_TAB_Q10[xq] * bQ10 + RATE_TAB_Q10[xq + 1] * aQ10) >> 10;
        var dQ10 = (DIST_TAB_Q10[xq] * bQ10 + DIST_TAB_Q10[xq + 1] * aQ10) >> 10;
        out[0] = ((long) rQ10 << nLog2 + (1 << (10 - PROB_COST_SHIFT - 1))) >> (10 - PROB_COST_SHIFT);
        out[1] = (var * dQ10 + 512) >> 10;
    }
}
