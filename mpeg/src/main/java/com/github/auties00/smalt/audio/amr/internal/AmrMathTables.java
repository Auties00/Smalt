/* Ports opencore-amr 0.1.6 (log2_tbl.cpp, pow2_tbl.cpp, inv_sqrt_tbl.cpp, pvamrwb_math_op.cpp) and vo-amrwbenc
   0.1.3 (math_op.c, log2_tab.h). Copyright PacketVideo, VisualOn; Apache License 2.0. Modified for Smalt. */
package com.github.auties00.smalt.audio.amr.internal;

/**
 * Holds the interpolation tables of the fixed-point base-2 logarithm, power of two and inverse square root that the
 * AMR-NB codec and the AMR-WB decoder and encoder share (TS 26.073 and TS 26.173).
 */
public final class AmrMathTables {
    /**
     * Holds {@code 2^15 * log2(1 + i / 32)} for {@code i} from 0 to 32 ({@code log2_tbl}).
     */
    public static final short[] LOG2 = {
            0, 1455, 2866, 4236, 5568, 6863, 8124, 9352, 10549, 11716, 12855, 13967,
            15054, 16117, 17156, 18172, 19167, 20142, 21097, 22033, 22951, 23852, 24735, 25603,
            26455, 27291, 28113, 28922, 29716, 30497, 31266, 32023, 32767
    };

    /**
     * Holds {@code 2^14 * 2^(i / 32)} for {@code i} from 0 to 32 ({@code pow2_tbl}).
     */
    public static final short[] POW2 = {
            16384, 16743, 17109, 17484, 17867, 18258, 18658, 19066, 19484, 19911, 20347, 20792,
            21247, 21713, 22188, 22674, 23170, 23678, 24196, 24726, 25268, 25821, 26386, 26964,
            27554, 28158, 28774, 29405, 30048, 30706, 31379, 32066, 32767
    };

    /**
     * Holds the inverse square root interpolation table, 49 points from {@code 1 / sqrt(0.25)} down to
     * {@code 1 / sqrt(1)} in Q15 ({@code inv_sqrt_tbl}).
     */
    public static final short[] INV_SQRT = {
            32767, 31790, 30894, 30070, 29309, 28602, 27945, 27330, 26755, 26214, 25705, 25225,
            24770, 24339, 23930, 23541, 23170, 22817, 22479, 22155, 21845, 21548, 21263, 20988,
            20724, 20470, 20225, 19988, 19760, 19539, 19326, 19119, 18919, 18725, 18536, 18354,
            18176, 18004, 17837, 17674, 17515, 17361, 17211, 17064, 16921, 16782, 16646, 16514,
            16384
    };

    /**
     * Prevents instantiation.
     */
    private AmrMathTables() {
    }
}
