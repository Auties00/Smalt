package com.github.auties00.smalt.video.prores.internal;

/**
 * Holds the constant tables of the ProRes bitstream (SMPTE RDD 36): the block scan patterns, the
 * codebooks of the entropy coder and the quantization scale mapping.
 *
 * <p>A codebook is packed into an {@code int} as {@code (lastRiceQ + 1) << 8 | kRice << 4 | kExp}:
 * the Golomb-Rice/exponential-Golomb combination code {@code RICE_EXP_COMBO_CODE(lastRiceQ, kRice,
 * kExp)} of RDD 36 clause 7.1.1.1, where an exponential-Golomb code {@code EXP_GOLOMB_CODE(k)} is
 * the combination with no Rice part ({@code lastRiceQ + 1 == 0}).
 */
public final class ProResTables {
    /**
     * The raster position ({@code v * 8 + u}) of every scan index of a progressive block: the
     * inverse of RDD 36 Figure 4.
     */
    public static final int[] PROGRESSIVE_SCAN = invert(new int[]{
            0, 1, 4, 5, 16, 17, 21, 22,
            2, 3, 6, 7, 18, 20, 23, 28,
            8, 9, 12, 13, 19, 24, 27, 29,
            10, 11, 14, 15, 25, 26, 30, 31,
            32, 33, 37, 38, 45, 46, 53, 54,
            34, 36, 39, 44, 47, 52, 55, 60,
            35, 40, 43, 48, 51, 56, 59, 61,
            41, 42, 49, 50, 57, 58, 62, 63
    });

    /**
     * The raster position ({@code v * 8 + u}) of every scan index of a field block: the inverse of
     * RDD 36 Figure 5.
     */
    public static final int[] INTERLACED_SCAN = invert(new int[]{
            0, 2, 8, 10, 32, 34, 35, 41,
            1, 3, 9, 11, 33, 36, 40, 42,
            4, 6, 12, 14, 37, 39, 43, 49,
            5, 7, 13, 15, 38, 44, 48, 50,
            16, 18, 19, 25, 45, 47, 51, 57,
            17, 20, 24, 26, 46, 52, 56, 58,
            21, 23, 27, 30, 53, 55, 59, 62,
            22, 28, 29, 31, 54, 60, 61, 63
    });

    /**
     * The codebook of the first DC coefficient of a component: {@code EXP_GOLOMB_CODE(5)}
     * (RDD 36 clause 7.1.1.3).
     */
    public static final int FIRST_DC = codebook(-1, 0, 5);

    /**
     * The codebook of a DC difference by the magnitude of the previous difference, clamped to 3:
     * RDD 36 Table 9.
     */
    public static final int[] DC_DIFFERENCE = {
            codebook(-1, 0, 0), codebook(-1, 0, 1), codebook(1, 2, 3), codebook(-1, 0, 3)
    };

    /**
     * The codebook of a run by the previous run, clamped to 15: RDD 36 Table 10.
     */
    public static final int[] RUN = {
            codebook(2, 0, 1), codebook(2, 0, 1), codebook(1, 0, 1), codebook(1, 0, 1),
            codebook(-1, 0, 0), codebook(1, 1, 2), codebook(1, 1, 2), codebook(1, 1, 2),
            codebook(1, 1, 2), codebook(-1, 0, 1), codebook(-1, 0, 1), codebook(-1, 0, 1),
            codebook(-1, 0, 1), codebook(-1, 0, 1), codebook(-1, 0, 1), codebook(-1, 0, 2)
    };

    /**
     * The codebook of a level magnitude minus one by the previous one, clamped to 8: RDD 36
     * Table 11.
     */
    public static final int[] LEVEL = {
            codebook(2, 0, 2), codebook(1, 0, 1), codebook(2, 0, 1), codebook(-1, 0, 0),
            codebook(-1, 0, 1), codebook(-1, 0, 1), codebook(-1, 0, 1), codebook(-1, 0, 1),
            codebook(-1, 0, 2)
    };

    /**
     * The previous DC difference that selects the codebook of a component's first difference.
     */
    public static final int INITIAL_DC_DIFFERENCE = 3;

    /**
     * The previous run that selects the codebook of a component's first run.
     */
    public static final int INITIAL_RUN = 4;

    /**
     * The previous level symbol that selects the codebook of a component's first level.
     */
    public static final int INITIAL_LEVEL = 1;

    /**
     * The largest quantization index.
     */
    public static final int MAX_QUANTIZATION_INDEX = 224;

    /**
     * The longest run of identical alpha values one alpha codeword covers.
     */
    public static final int MAX_ALPHA_RUN = 2048;

    /**
     * Prevents instantiation.
     */
    private ProResTables() {
        throw new AssertionError();
    }

    /**
     * Packs a codebook.
     *
     * @param lastRiceQ the largest Golomb-Rice quotient, or {@code -1} for a pure exponential-Golomb
     *                  code
     * @param kRice     the Golomb-Rice order
     * @param kExp      the exponential-Golomb order
     * @return the packed codebook
     */
    private static int codebook(int lastRiceQ, int kRice, int kExp) {
        return (lastRiceQ + 1) << 8 | kRice << 4 | kExp;
    }

    /**
     * Returns the quantization scale factor of a quantization index (RDD 36 Table 15).
     *
     * @param index the quantization index, from 1 to 224
     * @return {@code qScale}
     */
    public static int scale(int index) {
        return index <= 128 ? index : 128 + 4 * (index - 128);
    }

    /**
     * Inverts a scan pattern given as the scan index of every raster position.
     *
     * @param scanOfRaster the scan index of each of the 64 raster positions
     * @return the raster position of each scan index
     */
    private static int[] invert(int[] scanOfRaster) {
        var result = new int[64];
        for (var position = 0; position < 64; position++) {
            result[scanOfRaster[position]] = position;
        }
        return result;
    }
}
