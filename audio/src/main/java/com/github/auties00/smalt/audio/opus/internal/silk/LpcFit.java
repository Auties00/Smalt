/* Ports libopus 1.5.2 (silk/LPC_fit.c). Copyright Skype Limited; BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.audio.opus.internal.silk;

import static com.github.auties00.smalt.audio.opus.internal.silk.SigProcFix.fixConst;
import static com.github.auties00.smalt.audio.opus.internal.silk.SigProcFix.rshiftRound;
import static com.github.auties00.smalt.audio.opus.internal.silk.SigProcFix.sat16;

/**
 * Converts 32-bit prediction coefficients to 16 bits, chirping them until they fit.
 */
public final class LpcFit {
    /**
     * The base chirp factor in Q16.
     */
    private static final int CHIRP_BASE_Q16 = fixConst(0.999, 16);

    private LpcFit() {
        throw new AssertionError();
    }

    /**
     * Converts 32-bit coefficients to 16 bits without wrap-around ({@code silk_LPC_fit}).
     *
     * @param a_QOUT receives the coefficients in {@code QOUT}
     * @param a_QIN  the coefficients in {@code QIN}; chirped in place as needed
     * @param QOUT   the output Q-domain
     * @param QIN    the input Q-domain
     * @param d      the filter order
     */
    public static void lpcFit(short[] a_QOUT, int[] a_QIN, int QOUT, int QIN, int d) {
        var idx = 0;
        int i;
        for (i = 0; i < 10; i++) {
            var maxabs = 0;
            for (var k = 0; k < d; k++) {
                var absval = Math.abs(a_QIN[k]);
                if (absval > maxabs) {
                    maxabs = absval;
                    idx = k;
                }
            }
            maxabs = rshiftRound(maxabs, QIN - QOUT);
            if (maxabs > Short.MAX_VALUE) {
                maxabs = Math.min(maxabs, 163838);
                var chirp_Q16 = CHIRP_BASE_Q16 - ((maxabs - Short.MAX_VALUE) << 14) / ((maxabs * (idx + 1)) >> 2);
                Bwexpander.bwexpander32(a_QIN, d, chirp_Q16);
            } else {
                break;
            }
        }
        if (i == 10) {
            for (var k = 0; k < d; k++) {
                a_QOUT[k] = (short) sat16(rshiftRound(a_QIN[k], QIN - QOUT));
                a_QIN[k] = a_QOUT[k] << (QIN - QOUT);
            }
        } else {
            for (var k = 0; k < d; k++) {
                a_QOUT[k] = (short) rshiftRound(a_QIN[k], QIN - QOUT);
            }
        }
    }
}
