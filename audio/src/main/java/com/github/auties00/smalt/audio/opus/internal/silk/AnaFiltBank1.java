/* Ports libopus 1.5.2 (silk/ana_filt_bank_1.c). Copyright Skype Limited; BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.audio.opus.internal.silk;

import static com.github.auties00.smalt.audio.opus.internal.silk.SigProcFix.rshiftRound;
import static com.github.auties00.smalt.audio.opus.internal.silk.SigProcFix.sat16;
import static com.github.auties00.smalt.audio.opus.internal.silk.SigProcFix.smlawb;
import static com.github.auties00.smalt.audio.opus.internal.silk.SigProcFix.smulwb;

/**
 * Splits a signal into two decimated bands with first-order all-pass filters.
 */
public final class AnaFiltBank1 {
    /**
     * The all-pass coefficient of the even branch ({@code 5394 << 1}).
     */
    private static final short A_fb1_20 = 5394 << 1;

    /**
     * The all-pass coefficient of the odd branch ({@code (opus_int16) (20623 << 1)}).
     */
    private static final short A_fb1_21 = -24290;

    private AnaFiltBank1() {
        throw new AssertionError();
    }

    /**
     * Splits a signal into a low band and a high band, each at half the rate
     * ({@code silk_ana_filt_bank_1}).
     *
     * @param in      the input signal
     * @param inOff   the index of the first input sample
     * @param S       the two-element state, updated
     * @param outL    receives the low band
     * @param outLOff the index of the first low-band sample
     * @param outH    receives the high band
     * @param outHOff the index of the first high-band sample
     * @param N       the number of input samples
     */
    public static void anaFiltBank1(short[] in, int inOff, int[] S, short[] outL, int outLOff, short[] outH,
                                    int outHOff, int N) {
        var N2 = N >> 1;
        for (var k = 0; k < N2; k++) {
            var in32 = in[inOff + 2 * k] << 10;
            var Y = in32 - S[0];
            var X = smlawb(Y, Y, A_fb1_21);
            var out_1 = S[0] + X;
            S[0] = in32 + X;
            in32 = in[inOff + 2 * k + 1] << 10;
            Y = in32 - S[1];
            X = smulwb(Y, A_fb1_20);
            var out_2 = S[1] + X;
            S[1] = in32 + X;
            outL[outLOff + k] = (short) sat16(rshiftRound(out_2 + out_1, 11));
            outH[outHOff + k] = (short) sat16(rshiftRound(out_2 - out_1, 11));
        }
    }
}
