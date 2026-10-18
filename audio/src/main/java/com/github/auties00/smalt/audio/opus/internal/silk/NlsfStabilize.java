/* Ports libopus 1.5.2 (silk/NLSF_stabilize.c). Copyright Skype Limited; BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.audio.opus.internal.silk;

import static com.github.auties00.smalt.audio.opus.internal.silk.SigProcFix.addSat16;
import static com.github.auties00.smalt.audio.opus.internal.silk.SigProcFix.limit;
import static com.github.auties00.smalt.audio.opus.internal.silk.SigProcFix.rshiftRound;

/**
 * Stabilizes an NLSF vector: moves coefficients that are too close to each other or to the
 * borders apart, with a modification of small Euclidean distance, and returns them sorted.
 */
public final class NlsfStabilize {
    /**
     * The maximum number of iterations of the minimum-distance method before the fallback.
     */
    private static final int MAX_LOOPS = 20;

    private NlsfStabilize() {
        throw new AssertionError();
    }

    /**
     * Stabilizes an NLSF vector in place ({@code silk_NLSF_stabilize}).
     *
     * @param NLSF_Q15      the NLSF vector in Q15; replaced with the stabilized vector
     * @param NDeltaMin_Q15 the {@code L + 1} minimum distances in Q15, the last at least 1
     * @param L             the number of NLSFs
     */
    public static void nlsfStabilize(short[] NLSF_Q15, short[] NDeltaMin_Q15, int L) {
        int loops;
        var I = 0;
        for (loops = 0; loops < MAX_LOOPS; loops++) {
            var min_diff_Q15 = NLSF_Q15[0] - NDeltaMin_Q15[0];
            I = 0;
            for (var i = 1; i <= L - 1; i++) {
                var diff_Q15 = NLSF_Q15[i] - (NLSF_Q15[i - 1] + NDeltaMin_Q15[i]);
                if (diff_Q15 < min_diff_Q15) {
                    min_diff_Q15 = diff_Q15;
                    I = i;
                }
            }
            var diff_Q15 = (1 << 15) - (NLSF_Q15[L - 1] + NDeltaMin_Q15[L]);
            if (diff_Q15 < min_diff_Q15) {
                min_diff_Q15 = diff_Q15;
                I = L;
            }
            if (min_diff_Q15 >= 0) {
                return;
            }
            if (I == 0) {
                NLSF_Q15[0] = NDeltaMin_Q15[0];
            } else if (I == L) {
                NLSF_Q15[L - 1] = (short) ((1 << 15) - NDeltaMin_Q15[L]);
            } else {
                var min_center_Q15 = 0;
                for (var k = 0; k < I; k++) {
                    min_center_Q15 += NDeltaMin_Q15[k];
                }
                min_center_Q15 += NDeltaMin_Q15[I] >> 1;
                var max_center_Q15 = 1 << 15;
                for (var k = L; k > I; k--) {
                    max_center_Q15 -= NDeltaMin_Q15[k];
                }
                max_center_Q15 -= NDeltaMin_Q15[I] >> 1;
                var center_freq_Q15 = (short) limit(rshiftRound(NLSF_Q15[I - 1] + NLSF_Q15[I], 1),
                        min_center_Q15, max_center_Q15);
                NLSF_Q15[I - 1] = (short) (center_freq_Q15 - (NDeltaMin_Q15[I] >> 1));
                NLSF_Q15[I] = (short) (NLSF_Q15[I - 1] + NDeltaMin_Q15[I]);
            }
        }
        if (loops == MAX_LOOPS) {
            Sort.insertionSortIncreasingAllValuesInt16(NLSF_Q15, L);
            NLSF_Q15[0] = (short) Math.max(NLSF_Q15[0], NDeltaMin_Q15[0]);
            for (var i = 1; i < L; i++) {
                NLSF_Q15[i] = (short) Math.max(NLSF_Q15[i], addSat16(NLSF_Q15[i - 1], NDeltaMin_Q15[i]));
            }
            NLSF_Q15[L - 1] = (short) Math.min(NLSF_Q15[L - 1], (1 << 15) - NDeltaMin_Q15[L]);
            for (var i = L - 2; i >= 0; i--) {
                NLSF_Q15[i] = (short) Math.min(NLSF_Q15[i], NLSF_Q15[i + 1] - NDeltaMin_Q15[i + 1]);
            }
        }
    }
}
