package com.github.auties00.smalt.video.h264.encoder.internal;

import static com.github.auties00.smalt.video.h264.internal.CavlcTables.CHROMA_DC_TOKEN_CODE;
import static com.github.auties00.smalt.video.h264.internal.CavlcTables.CHROMA_DC_TOKEN_LENGTH;
import static com.github.auties00.smalt.video.h264.internal.CavlcTables.CHROMA_DC_TOTAL_ZEROS_CODE;
import static com.github.auties00.smalt.video.h264.internal.CavlcTables.CHROMA_DC_TOTAL_ZEROS_LENGTH;
import static com.github.auties00.smalt.video.h264.internal.CavlcTables.COEFF_TOKEN_CODE;
import static com.github.auties00.smalt.video.h264.internal.CavlcTables.COEFF_TOKEN_LENGTH;
import static com.github.auties00.smalt.video.h264.internal.CavlcTables.RUN_BEFORE_CODE;
import static com.github.auties00.smalt.video.h264.internal.CavlcTables.RUN_BEFORE_LENGTH;
import static com.github.auties00.smalt.video.h264.internal.CavlcTables.TOTAL_ZEROS_CODE;
import static com.github.auties00.smalt.video.h264.internal.CavlcTables.TOTAL_ZEROS_LENGTH;

/**
 * Holds the variable-length code tables of CAVLC residual coding (clause 9.2) in the packed
 * layout the writer reads with a single lookup.
 *
 * <p>Every entry packs a code word and its length as {@code code | (length << 16)}; a zero
 * length marks a combination that cannot occur. The entries are derived once from the code
 * tables the decoder reads, {@link com.github.auties00.smalt.video.h264.internal.CavlcTables}
 * (ITU-T H.264 Tables 9-5, 9-7, 9-8, 9-9 and 9-10), plus the 6-bit fixed-length
 * {@code coeff_token} of {@code nC >= 8}.
 */
final class CavlcTables {
    /**
     * The {@code coeff_token} codes of Table 9-5, indexed by
     * {@code (table * 17 + totalCoeff) * 4 + trailingOnes}; table 0 serves {@code 0 <= nC < 2},
     * 1 serves {@code 2 <= nC < 4}, 2 serves {@code 4 <= nC < 8}, 3 serves {@code 8 <= nC} and 4
     * serves {@code nC == -1} (4:2:0 chroma DC).
     */
    static final int[] COEFF_TOKEN = new int[5 * 17 * 4];

    /**
     * The {@code total_zeros} codes of Tables 9-7 and 9-8 for 4x4 blocks, indexed by
     * {@code totalCoeff * 16 + totalZeros}.
     */
    static final int[] TOTAL_ZEROS = new int[16 * 16];

    /**
     * The {@code total_zeros} codes of Table 9-9(a) for 4:2:0 chroma DC blocks, indexed by
     * {@code totalCoeff * 4 + totalZeros}.
     */
    static final int[] TOTAL_ZEROS_CHROMA_DC = new int[4 * 4];

    /**
     * The {@code run_before} codes of Table 9-10, indexed by
     * {@code min(zerosLeft, 7) * 15 + runBefore}.
     */
    static final int[] RUN_BEFORE = new int[8 * 15];

    static {
        for (var total = 0; total <= 16; total++) {
            for (var trailingOnes = 0; trailingOnes < 4; trailingOnes++) {
                for (var table = 0; table < 3; table++) {
                    COEFF_TOKEN[(table * 17 + total) * 4 + trailingOnes] = pack(
                            COEFF_TOKEN_LENGTH[table][trailingOnes * 17 + total],
                            COEFF_TOKEN_CODE[table][trailingOnes * 17 + total]);
                }
                if (trailingOnes <= total) {
                    COEFF_TOKEN[(3 * 17 + total) * 4 + trailingOnes] = pack(6,
                            total == 0 ? 3 : ((total - 1) << 2) | trailingOnes);
                }
                if (total <= 4) {
                    COEFF_TOKEN[(4 * 17 + total) * 4 + trailingOnes] = pack(
                            CHROMA_DC_TOKEN_LENGTH[trailingOnes * 5 + total],
                            CHROMA_DC_TOKEN_CODE[trailingOnes * 5 + total]);
                }
            }
        }
        for (var total = 1; total <= 15; total++) {
            var lengths = TOTAL_ZEROS_LENGTH[total - 1];
            var codes = TOTAL_ZEROS_CODE[total - 1];
            for (var zeros = 0; zeros < lengths.length; zeros++) {
                TOTAL_ZEROS[total * 16 + zeros] = pack(lengths[zeros], codes[zeros]);
            }
        }
        for (var total = 1; total <= 3; total++) {
            var lengths = CHROMA_DC_TOTAL_ZEROS_LENGTH[total - 1];
            var codes = CHROMA_DC_TOTAL_ZEROS_CODE[total - 1];
            for (var zeros = 0; zeros < lengths.length; zeros++) {
                TOTAL_ZEROS_CHROMA_DC[total * 4 + zeros] = pack(lengths[zeros], codes[zeros]);
            }
        }
        for (var zerosLeft = 1; zerosLeft <= 7; zerosLeft++) {
            var lengths = RUN_BEFORE_LENGTH[zerosLeft - 1];
            var codes = RUN_BEFORE_CODE[zerosLeft - 1];
            for (var run = 0; run < lengths.length; run++) {
                RUN_BEFORE[zerosLeft * 15 + run] = pack(lengths[run], codes[run]);
            }
        }
    }

    private CavlcTables() {
        throw new AssertionError();
    }

    /**
     * Packs a code and its length, or returns zero for a code of length zero.
     *
     * @param length the code length in bits
     * @param code the code value, as an unsigned byte
     * @return the packed entry
     */
    private static int pack(int length, int code) {
        return length == 0 ? 0 : (length << 16) | (code & 0xFF);
    }

    /**
     * Returns the {@code coeff_token} table that serves a predicted coefficient count.
     *
     * @param nC the predicted number of non-zero coefficients, {@code -1} for chroma DC
     * @return the table index into {@link #COEFF_TOKEN}
     */
    static int coeffTokenTable(int nC) {
        if (nC < 0) {
            return 4;
        }
        if (nC < 2) {
            return 0;
        }
        if (nC < 4) {
            return 1;
        }
        return nC < 8 ? 2 : 3;
    }
}
