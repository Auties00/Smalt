/* Ports libopus 1.5.2 (silk/resampler_down2.c, silk/resampler_down2_3.c). Copyright Skype Limited; BSD 3-Clause
   License. Modified for Smalt. */
package com.github.auties00.smalt.audio.opus.internal.silk;

import static com.github.auties00.smalt.audio.opus.internal.silk.SigProcFix.rshiftRound;
import static com.github.auties00.smalt.audio.opus.internal.silk.SigProcFix.sat16;
import static com.github.auties00.smalt.audio.opus.internal.silk.SigProcFix.smlawb;
import static com.github.auties00.smalt.audio.opus.internal.silk.SigProcFix.smulwb;

/**
 * Implements the standalone low-complexity downsamplers used by the encoder analysis.
 */
public final class ResamplerDown {
    /**
     * The FIR order of the 2/3 downsampler.
     */
    private static final int ORDER_FIR = 4;

    private ResamplerDown() {
        throw new AssertionError();
    }

    /**
     * Downsamples by two with all-pass filters ({@code silk_resampler_down2}).
     *
     * @param S      the two-element state, updated
     * @param out    receives {@code inLen / 2} output samples
     * @param outOff the index of the first output sample
     * @param in     the input signal
     * @param inOff  the index of the first input sample
     * @param inLen  the number of input samples
     */
    public static void down2(int[] S, short[] out, int outOff, short[] in, int inOff, int inLen) {
        var len2 = inLen >> 1;
        for (var k = 0; k < len2; k++) {
            var in32 = in[inOff + 2 * k] << 10;
            var Y = in32 - S[0];
            var X = smlawb(Y, Y, ResamplerRom.silk_resampler_down2_1);
            var out32 = S[0] + X;
            S[0] = in32 + X;
            in32 = in[inOff + 2 * k + 1] << 10;
            Y = in32 - S[1];
            X = smulwb(Y, ResamplerRom.silk_resampler_down2_0);
            out32 = out32 + S[1];
            out32 = out32 + X;
            S[1] = in32 + X;
            out[outOff + k] = (short) sat16(rshiftRound(out32, 11));
        }
    }

    /**
     * Downsamples by 2/3 with low quality ({@code silk_resampler_down2_3}).
     *
     * @param S      the six-element state (four FIR samples, then the AR2 state), updated
     * @param out    receives {@code floor(2 * inLen / 3)} output samples
     * @param outOff the index of the first output sample
     * @param in     the input signal
     * @param inOff  the index of the first input sample
     * @param inLen  the number of input samples
     */
    public static void down2_3(int[] S, short[] out, int outOff, short[] in, int inOff, int inLen) {
        var coefs = ResamplerRom.silk_Resampler_2_3_COEFS_LQ;
        var buf = new int[Resampler.RESAMPLER_MAX_BATCH_SIZE_IN + ORDER_FIR];
        System.arraycopy(S, 0, buf, 0, ORDER_FIR);
        int nSamplesIn;
        while (true) {
            nSamplesIn = Math.min(inLen, Resampler.RESAMPLER_MAX_BATCH_SIZE_IN);
            ResamplerPrivate.ar2(S, ORDER_FIR, buf, ORDER_FIR, in, inOff, coefs, 0, nSamplesIn);
            var buf_ptr = 0;
            var counter = nSamplesIn;
            while (counter > 2) {
                var res_Q6 = smulwb(buf[buf_ptr], coefs[2]);
                res_Q6 = smlawb(res_Q6, buf[buf_ptr + 1], coefs[3]);
                res_Q6 = smlawb(res_Q6, buf[buf_ptr + 2], coefs[5]);
                res_Q6 = smlawb(res_Q6, buf[buf_ptr + 3], coefs[4]);
                out[outOff++] = (short) sat16(rshiftRound(res_Q6, 6));
                res_Q6 = smulwb(buf[buf_ptr + 1], coefs[4]);
                res_Q6 = smlawb(res_Q6, buf[buf_ptr + 2], coefs[5]);
                res_Q6 = smlawb(res_Q6, buf[buf_ptr + 3], coefs[3]);
                res_Q6 = smlawb(res_Q6, buf[buf_ptr + 4], coefs[2]);
                out[outOff++] = (short) sat16(rshiftRound(res_Q6, 6));
                buf_ptr += 3;
                counter -= 3;
            }
            inOff += nSamplesIn;
            inLen -= nSamplesIn;
            if (inLen > 0) {
                System.arraycopy(buf, nSamplesIn, buf, 0, ORDER_FIR);
            } else {
                break;
            }
        }
        System.arraycopy(buf, nSamplesIn, S, 0, ORDER_FIR);
    }
}
