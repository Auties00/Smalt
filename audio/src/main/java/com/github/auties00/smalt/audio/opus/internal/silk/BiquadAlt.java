/* Ports libopus 1.5.2 (silk/biquad_alt.c). Copyright Skype Limited; BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.audio.opus.internal.silk;

import static com.github.auties00.smalt.audio.opus.internal.silk.SigProcFix.rshiftRound;
import static com.github.auties00.smalt.audio.opus.internal.silk.SigProcFix.sat16;
import static com.github.auties00.smalt.audio.opus.internal.silk.SigProcFix.smlawb;
import static com.github.auties00.smalt.audio.opus.internal.silk.SigProcFix.smulwb;

/**
 * Filters a signal with a second-order ARMA filter in transposed direct form II, tolerating
 * slowly varying coefficients.
 */
public final class BiquadAlt {
    private BiquadAlt() {
        throw new AssertionError();
    }

    /**
     * Filters a mono signal ({@code silk_biquad_alt_stride1}).
     *
     * @param in     the input signal
     * @param inOff  the index of the first input sample
     * @param B_Q28  the three MA coefficients in Q28
     * @param A_Q28  the two AR coefficients in Q28
     * @param S      the two-element state, updated
     * @param out    receives the output signal
     * @param outOff the index of the first output sample
     * @param len    the number of samples
     */
    public static void biquadAltStride1(short[] in, int inOff, int[] B_Q28, int[] A_Q28, int[] S, short[] out,
                                        int outOff, int len) {
        var A0_L_Q28 = (-A_Q28[0]) & 0x00003FFF;
        var A0_U_Q28 = (-A_Q28[0]) >> 14;
        var A1_L_Q28 = (-A_Q28[1]) & 0x00003FFF;
        var A1_U_Q28 = (-A_Q28[1]) >> 14;
        for (var k = 0; k < len; k++) {
            int inval = in[inOff + k];
            var out32_Q14 = smlawb(S[0], B_Q28[0], inval) << 2;
            S[0] = S[1] + rshiftRound(smulwb(out32_Q14, A0_L_Q28), 14);
            S[0] = smlawb(S[0], out32_Q14, A0_U_Q28);
            S[0] = smlawb(S[0], B_Q28[1], inval);
            S[1] = rshiftRound(smulwb(out32_Q14, A1_L_Q28), 14);
            S[1] = smlawb(S[1], out32_Q14, A1_U_Q28);
            S[1] = smlawb(S[1], B_Q28[2], inval);
            out[outOff + k] = (short) sat16((out32_Q14 + (1 << 14) - 1) >> 14);
        }
    }
}
