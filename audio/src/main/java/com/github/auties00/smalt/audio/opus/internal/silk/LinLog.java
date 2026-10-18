/* Ports libopus 1.5.2 (silk/lin2log.c, silk/log2lin.c). Copyright Skype Limited; BSD 3-Clause License. Modified for
   Smalt. */
package com.github.auties00.smalt.audio.opus.internal.silk;

import static com.github.auties00.smalt.audio.opus.internal.silk.SigProcFix.clzFracQ7;
import static com.github.auties00.smalt.audio.opus.internal.silk.SigProcFix.smlawb;
import static com.github.auties00.smalt.audio.opus.internal.silk.SigProcFix.smulbb;

/**
 * Converts between the linear domain and the {@code 128 * log2} domain with piecewise parabolic
 * approximations that are close inverses of each other.
 */
public final class LinLog {
    private LinLog() {
        throw new AssertionError();
    }

    /**
     * Approximates {@code 128 * log2(inLin)} ({@code silk_lin2log}).
     *
     * @param inLin the value in the linear domain
     * @return the value in the log domain, Q7
     */
    public static int lin2log(int inLin) {
        var lz = Integer.numberOfLeadingZeros(inLin);
        var frac_Q7 = clzFracQ7(inLin);
        return smlawb(frac_Q7, frac_Q7 * (128 - frac_Q7), 179) + ((31 - lz) << 7);
    }

    /**
     * Approximates {@code 2^(inLog_Q7 / 128)} ({@code silk_log2lin}).
     *
     * @param inLog_Q7 the value in the log domain, Q7
     * @return the value in the linear domain: zero for negative input, {@link Integer#MAX_VALUE}
     *         from {@code 3967} upwards
     */
    public static int log2lin(int inLog_Q7) {
        if (inLog_Q7 < 0) {
            return 0;
        } else if (inLog_Q7 >= 3967) {
            return Integer.MAX_VALUE;
        }
        var out = 1 << (inLog_Q7 >> 7);
        var frac_Q7 = inLog_Q7 & 0x7F;
        if (inLog_Q7 < 2048) {
            out = out + ((out * smlawb(frac_Q7, smulbb(frac_Q7, 128 - frac_Q7), -174)) >> 7);
        } else {
            out = out + (out >> 7) * smlawb(frac_Q7, smulbb(frac_Q7, 128 - frac_Q7), -174);
        }
        return out;
    }
}
