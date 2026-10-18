package com.github.auties00.smalt.video.av1.internal;

/**
 * Holds the constant tables of AV1 intra prediction.
 *
 * @implNote This implementation copies the tables from dav1d's {@code src/tables.c}.
 */
final class IpredTables {
    /**
     * The smooth predictor weights ({@code dav1d_sm_weights}, AV1 specification section
     * 7.11.2.6, {@code Sm_Weights_Tx_*}), stored back to back so that the weights of a block
     * dimension {@code n} (2 to 64) start at index {@code n}.
     */
    static final int[] SM_WEIGHTS = {
            0, 0,
            255, 128,
            255, 149, 85, 64,
            255, 197, 146, 105, 73, 50, 37, 32,
            255, 225, 196, 170, 145, 123, 102, 84,
            68, 54, 43, 33, 26, 20, 17, 16,
            255, 240, 225, 210, 196, 182, 169, 157,
            145, 133, 122, 111, 101, 92, 83, 74,
            66, 59, 52, 45, 39, 34, 29, 25,
            21, 17, 14, 12, 10, 9, 8, 8,
            255, 248, 240, 233, 225, 218, 210, 203,
            196, 189, 182, 176, 169, 163, 156, 150,
            144, 138, 133, 127, 121, 116, 111, 106,
            101, 96, 91, 86, 82, 77, 73, 69,
            65, 61, 57, 54, 50, 47, 44, 41,
            38, 35, 32, 29, 27, 25, 22, 20,
            18, 16, 15, 13, 12, 10, 9, 8,
            7, 6, 6, 5, 5, 4, 4, 4
    };

    /**
     * The directional prediction position steps ({@code dav1d_dr_intra_derivative}, AV1
     * specification section 7.11.2.4, {@code Dr_Intra_Derivative}), indexed by half the angle
     * measured from the nearest axis; entries that no coded angle reaches are zero.
     */
    static final int[] DR_INTRA_DERIVATIVE = {
            0,
            1023, 0,
            547,
            372, 0, 0,
            273,
            215, 0,
            178,
            151, 0,
            132,
            116, 0,
            102, 0,
            90,
            80, 0,
            71,
            64, 0,
            57,
            51, 0,
            45, 0,
            40,
            35, 0,
            31,
            27, 0,
            23,
            19, 0,
            15, 0,
            11, 0,
            7,
            3
    };

    /**
     * The recursive filter intra taps ({@code dav1d_filter_intra_taps}, AV1 specification section
     * 7.11.2.3, {@code Intra_Filter_Taps}), flattened as {@code [mode 5][output 8][tap 7]}; the
     * outputs cover a 4x2 block in raster order and the taps weigh the top-left, the four top and
     * the two left neighbors, in that order.
     */
    static final int[] FILTER_INTRA_TAPS = {
            -6, 10, 0, 0, 0, 12, 0,
            -5, 2, 10, 0, 0, 9, 0,
            -3, 1, 1, 10, 0, 7, 0,
            -3, 1, 1, 2, 10, 5, 0,
            -4, 6, 0, 0, 0, 2, 12,
            -3, 2, 6, 0, 0, 2, 9,
            -3, 2, 2, 6, 0, 2, 7,
            -3, 1, 2, 2, 6, 3, 5,

            -10, 16, 0, 0, 0, 10, 0,
            -6, 0, 16, 0, 0, 6, 0,
            -4, 0, 0, 16, 0, 4, 0,
            -2, 0, 0, 0, 16, 2, 0,
            -10, 16, 0, 0, 0, 0, 10,
            -6, 0, 16, 0, 0, 0, 6,
            -4, 0, 0, 16, 0, 0, 4,
            -2, 0, 0, 0, 16, 0, 2,

            -8, 8, 0, 0, 0, 16, 0,
            -8, 0, 8, 0, 0, 16, 0,
            -8, 0, 0, 8, 0, 16, 0,
            -8, 0, 0, 0, 8, 16, 0,
            -4, 4, 0, 0, 0, 0, 16,
            -4, 0, 4, 0, 0, 0, 16,
            -4, 0, 0, 4, 0, 0, 16,
            -4, 0, 0, 0, 4, 0, 16,

            -2, 8, 0, 0, 0, 10, 0,
            -1, 3, 8, 0, 0, 6, 0,
            -1, 2, 3, 8, 0, 4, 0,
            0, 1, 2, 3, 8, 2, 0,
            -1, 4, 0, 0, 0, 3, 10,
            -1, 3, 4, 0, 0, 4, 6,
            -1, 2, 3, 4, 0, 4, 4,
            -1, 2, 2, 3, 4, 3, 3,

            -12, 14, 0, 0, 0, 14, 0,
            -10, 0, 14, 0, 0, 12, 0,
            -9, 0, 0, 14, 0, 11, 0,
            -8, 0, 0, 0, 14, 10, 0,
            -10, 12, 0, 0, 0, 0, 14,
            -9, 1, 12, 0, 0, 0, 12,
            -8, 0, 0, 12, 0, 1, 11,
            -7, 0, 0, 1, 12, 1, 9
    };

    /**
     * The intra edge smoothing kernels of strengths 1 to 3 ({@code filter_edge} in dav1d's
     * {@code ipred_tmpl.c}, AV1 specification section 7.11.2.12), flattened as
     * {@code [strength - 1][tap 5]}.
     */
    static final int[] EDGE_KERNEL = {
            0, 4, 8, 4, 0,
            0, 5, 6, 5, 0,
            2, 4, 4, 4, 2
    };

    /**
     * Prevents instantiation.
     */
    private IpredTables() {
        throw new AssertionError();
    }
}
