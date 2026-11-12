/* Ports JM, the ITU-T / ISO/IEC H.264 reference software (ldecod/src/vlc.c: the layout of the CAVLC code tables).
   Copyright ITU-T and ISO/IEC; royalty-free license with notice. Modified for Smalt. */
package com.github.auties00.smalt.video.h264.internal;

/**
 * Holds the variable-length code tables of H.264 context-adaptive variable-length coding
 * (CAVLC), shared by the decoder and the encoder.
 *
 * <p>Each table is given as a pair of arrays holding, for every symbol, the code length in bits
 * and the code value (the code bits read most-significant first); a length of zero marks a
 * symbol that has no code.
 *
 * @implNote This implementation transcribes the code lengths and values of ITU-T H.264 Tables
 * 9-5, 9-7, 9-8, 9-9 and 9-10 in the layout of the JM reference decoder's tables; the 4:2:2
 * chroma DC tables come from the Recommendation alone.
 */
public final class CavlcTables {
    /**
     * The {@code coeff_token} code lengths for {@code 0 <= nC < 2}, {@code 2 <= nC < 4} and
     * {@code 4 <= nC < 8}, indexed {@code [table][trailingOnes * 17 + totalCoeff]} (ITU-T H.264
     * Table 9-5).
     */
    public static final byte[][] COEFF_TOKEN_LENGTH = {
            {
                    1, 6, 8, 9, 10, 11, 13, 13, 13, 14, 14, 15, 15, 16, 16, 16, 16,
                    0, 2, 6, 8, 9, 10, 11, 13, 13, 14, 14, 15, 15, 15, 16, 16, 16,
                    0, 0, 3, 7, 8, 9, 10, 11, 13, 13, 14, 14, 15, 15, 16, 16, 16,
                    0, 0, 0, 5, 6, 7, 8, 9, 10, 11, 13, 14, 14, 15, 15, 16, 16
            },
            {
                    2, 6, 6, 7, 8, 8, 9, 11, 11, 12, 12, 12, 13, 13, 13, 14, 14,
                    0, 2, 5, 6, 6, 7, 8, 9, 11, 11, 12, 12, 13, 13, 14, 14, 14,
                    0, 0, 3, 6, 6, 7, 8, 9, 11, 11, 12, 12, 13, 13, 13, 14, 14,
                    0, 0, 0, 4, 4, 5, 6, 6, 7, 9, 11, 11, 12, 13, 13, 13, 14
            },
            {
                    4, 6, 6, 6, 7, 7, 7, 7, 8, 8, 9, 9, 9, 10, 10, 10, 10,
                    0, 4, 5, 5, 5, 5, 6, 6, 7, 8, 8, 9, 9, 9, 10, 10, 10,
                    0, 0, 4, 5, 5, 5, 6, 6, 7, 7, 8, 8, 9, 9, 10, 10, 10,
                    0, 0, 0, 4, 4, 4, 4, 4, 5, 6, 7, 8, 8, 9, 10, 10, 10
            }
    };

    /**
     * The {@code coeff_token} code values matching {@link #COEFF_TOKEN_LENGTH} (ITU-T H.264 Table
     * 9-5).
     */
    public static final byte[][] COEFF_TOKEN_CODE = {
            {
                    1, 5, 7, 7, 7, 7, 15, 11, 8, 15, 11, 15, 11, 15, 11, 7, 4,
                    0, 1, 4, 6, 6, 6, 6, 14, 10, 14, 10, 14, 10, 1, 14, 10, 6,
                    0, 0, 1, 5, 5, 5, 5, 5, 13, 9, 13, 9, 13, 9, 13, 9, 5,
                    0, 0, 0, 3, 3, 4, 4, 4, 4, 4, 12, 12, 8, 12, 8, 12, 8
            },
            {
                    3, 11, 7, 7, 7, 4, 7, 15, 11, 15, 11, 8, 15, 11, 7, 9, 7,
                    0, 2, 7, 10, 6, 6, 6, 6, 14, 10, 14, 10, 14, 10, 11, 8, 6,
                    0, 0, 3, 9, 5, 5, 5, 5, 13, 9, 13, 9, 13, 9, 6, 10, 5,
                    0, 0, 0, 5, 4, 6, 8, 4, 4, 4, 12, 8, 12, 12, 8, 1, 4
            },
            {
                    15, 15, 11, 8, 15, 11, 9, 8, 15, 11, 15, 11, 8, 13, 9, 5, 1,
                    0, 14, 15, 12, 10, 8, 14, 10, 14, 14, 10, 14, 10, 7, 12, 8, 4,
                    0, 0, 13, 14, 11, 9, 13, 9, 13, 10, 13, 9, 13, 9, 11, 7, 3,
                    0, 0, 0, 12, 11, 10, 9, 8, 13, 12, 12, 12, 8, 12, 10, 6, 2
            }
    };

    /**
     * The {@code coeff_token} code lengths for 4:2:0 chroma DC ({@code nC == -1}), indexed
     * {@code [trailingOnes * 5 + totalCoeff]} (ITU-T H.264 Table 9-5).
     */
    public static final byte[] CHROMA_DC_TOKEN_LENGTH = {
            2, 6, 6, 6, 6,
            0, 1, 6, 7, 8,
            0, 0, 3, 7, 8,
            0, 0, 0, 6, 7
    };

    /**
     * The {@code coeff_token} code values matching {@link #CHROMA_DC_TOKEN_LENGTH}.
     */
    public static final byte[] CHROMA_DC_TOKEN_CODE = {
            1, 7, 4, 3, 2,
            0, 1, 6, 3, 3,
            0, 0, 1, 2, 2,
            0, 0, 0, 5, 0
    };

    /**
     * The {@code coeff_token} code lengths for 4:2:2 chroma DC ({@code nC == -2}), indexed
     * {@code [trailingOnes * 9 + totalCoeff]} (ITU-T H.264 Table 9-5).
     */
    public static final byte[] CHROMA_DC_422_TOKEN_LENGTH = {
            1, 7, 7, 9, 9, 10, 11, 12, 13,
            0, 2, 7, 7, 9, 10, 11, 12, 12,
            0, 0, 3, 7, 7, 9, 10, 11, 12,
            0, 0, 0, 5, 6, 7, 7, 10, 11
    };

    /**
     * The {@code coeff_token} code values matching {@link #CHROMA_DC_422_TOKEN_LENGTH}.
     */
    public static final byte[] CHROMA_DC_422_TOKEN_CODE = {
            1, 15, 14, 7, 6, 7, 7, 7, 7,
            0, 1, 13, 12, 5, 6, 6, 6, 5,
            0, 0, 1, 11, 10, 4, 5, 5, 4,
            0, 0, 0, 1, 1, 9, 8, 4, 4
    };

    /**
     * The {@code total_zeros} code lengths for 4x4 blocks, indexed
     * {@code [totalCoeff - 1][totalZeros]} (ITU-T H.264 Tables 9-7 and 9-8).
     */
    public static final byte[][] TOTAL_ZEROS_LENGTH = {
            {1, 3, 3, 4, 4, 5, 5, 6, 6, 7, 7, 8, 8, 9, 9, 9},
            {3, 3, 3, 3, 3, 4, 4, 4, 4, 5, 5, 6, 6, 6, 6},
            {4, 3, 3, 3, 4, 4, 3, 3, 4, 5, 5, 6, 5, 6},
            {5, 3, 4, 4, 3, 3, 3, 4, 3, 4, 5, 5, 5},
            {4, 4, 4, 3, 3, 3, 3, 3, 4, 5, 4, 5},
            {6, 5, 3, 3, 3, 3, 3, 3, 4, 3, 6},
            {6, 5, 3, 3, 3, 2, 3, 4, 3, 6},
            {6, 4, 5, 3, 2, 2, 3, 3, 6},
            {6, 6, 4, 2, 2, 3, 2, 5},
            {5, 5, 3, 2, 2, 2, 4},
            {4, 4, 3, 3, 1, 3},
            {4, 4, 2, 1, 3},
            {3, 3, 1, 2},
            {2, 2, 1},
            {1, 1}
    };

    /**
     * The {@code total_zeros} code values matching {@link #TOTAL_ZEROS_LENGTH}.
     */
    public static final byte[][] TOTAL_ZEROS_CODE = {
            {1, 3, 2, 3, 2, 3, 2, 3, 2, 3, 2, 3, 2, 3, 2, 1},
            {7, 6, 5, 4, 3, 5, 4, 3, 2, 3, 2, 3, 2, 1, 0},
            {5, 7, 6, 5, 4, 3, 4, 3, 2, 3, 2, 1, 1, 0},
            {3, 7, 5, 4, 6, 5, 4, 3, 3, 2, 2, 1, 0},
            {5, 4, 3, 7, 6, 5, 4, 3, 2, 1, 1, 0},
            {1, 1, 7, 6, 5, 4, 3, 2, 1, 1, 0},
            {1, 1, 5, 4, 3, 3, 2, 1, 1, 0},
            {1, 1, 1, 3, 3, 2, 2, 1, 0},
            {1, 0, 1, 3, 2, 1, 1, 1},
            {1, 0, 1, 3, 2, 1, 1},
            {0, 1, 1, 2, 1, 3},
            {0, 1, 1, 1, 1},
            {0, 1, 1, 1},
            {0, 1, 1},
            {0, 1}
    };

    /**
     * The {@code total_zeros} code lengths for 4:2:0 chroma DC blocks, indexed
     * {@code [totalCoeff - 1][totalZeros]} (ITU-T H.264 Table 9-9a).
     */
    public static final byte[][] CHROMA_DC_TOTAL_ZEROS_LENGTH = {
            {1, 2, 3, 3},
            {1, 2, 2},
            {1, 1}
    };

    /**
     * The {@code total_zeros} code values matching {@link #CHROMA_DC_TOTAL_ZEROS_LENGTH}.
     */
    public static final byte[][] CHROMA_DC_TOTAL_ZEROS_CODE = {
            {1, 1, 1, 0},
            {1, 1, 0},
            {1, 0}
    };

    /**
     * The {@code total_zeros} code lengths for 4:2:2 chroma DC blocks, indexed
     * {@code [totalCoeff - 1][totalZeros]} (ITU-T H.264 Table 9-9b).
     */
    public static final byte[][] CHROMA_DC_422_TOTAL_ZEROS_LENGTH = {
            {1, 3, 3, 4, 4, 4, 5, 5},
            {3, 2, 3, 3, 3, 3, 3},
            {3, 3, 2, 2, 3, 3},
            {3, 2, 2, 2, 3},
            {2, 2, 2, 2},
            {2, 2, 1},
            {1, 1}
    };

    /**
     * The {@code total_zeros} code values matching {@link #CHROMA_DC_422_TOTAL_ZEROS_LENGTH}.
     */
    public static final byte[][] CHROMA_DC_422_TOTAL_ZEROS_CODE = {
            {1, 2, 3, 2, 3, 1, 1, 0},
            {0, 1, 1, 4, 5, 6, 7},
            {0, 1, 1, 2, 6, 7},
            {6, 0, 1, 2, 7},
            {0, 1, 2, 3},
            {0, 1, 1},
            {0, 1}
    };

    /**
     * The {@code run_before} code lengths, indexed {@code [min(zerosLeft, 7) - 1][runBefore]}
     * (ITU-T H.264 Table 9-10).
     */
    public static final byte[][] RUN_BEFORE_LENGTH = {
            {1, 1},
            {1, 2, 2},
            {2, 2, 2, 2},
            {2, 2, 2, 3, 3},
            {2, 2, 3, 3, 3, 3},
            {2, 3, 3, 3, 3, 3, 3},
            {3, 3, 3, 3, 3, 3, 3, 4, 5, 6, 7, 8, 9, 10, 11}
    };

    /**
     * The {@code run_before} code values matching {@link #RUN_BEFORE_LENGTH}.
     */
    public static final byte[][] RUN_BEFORE_CODE = {
            {1, 0},
            {1, 1, 0},
            {3, 2, 1, 0},
            {3, 2, 1, 1, 0},
            {3, 2, 3, 2, 1, 0},
            {3, 0, 1, 3, 2, 5, 4},
            {7, 6, 5, 4, 3, 2, 1, 1, 1, 1, 1, 1, 1, 1, 1}
    };

    private CavlcTables() {
        throw new AssertionError();
    }
}
