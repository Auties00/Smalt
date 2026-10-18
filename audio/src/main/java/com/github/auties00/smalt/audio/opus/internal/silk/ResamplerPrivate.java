/* Ports libopus 1.5.2 (silk/resampler_private_up2_HQ.c, silk/resampler_private_AR2.c,
   silk/resampler_private_IIR_FIR.c, silk/resampler_private_down_FIR.c). Copyright Skype Limited; BSD 3-Clause
   License. Modified for Smalt. */
package com.github.auties00.smalt.audio.opus.internal.silk;

import static com.github.auties00.smalt.audio.opus.internal.silk.ResamplerRom.RESAMPLER_ORDER_FIR_12;
import static com.github.auties00.smalt.audio.opus.internal.silk.SigProcFix.rshiftRound;
import static com.github.auties00.smalt.audio.opus.internal.silk.SigProcFix.sat16;
import static com.github.auties00.smalt.audio.opus.internal.silk.SigProcFix.smlawb;
import static com.github.auties00.smalt.audio.opus.internal.silk.SigProcFix.smulwb;

/**
 * Implements the building blocks of the SILK resampler: the all-pass 2x upsampler, the AR2
 * prefilter, and the upsampling and decimating interpolators.
 *
 * <p>The interpolation loops run through {@link SilkKernels}, whose scalar and vector
 * implementations are bit-identical.
 */
public final class ResamplerPrivate {
    private ResamplerPrivate() {
        throw new AssertionError();
    }

    /**
     * Upsamples by two with high-quality all-pass filters followed by a notch just above Nyquist
     * ({@code silk_resampler_private_up2_HQ}).
     *
     * @param S      the six-element filter state, updated
     * @param out    receives {@code 2 * len} output samples
     * @param outOff the index of the first output sample
     * @param in     the input signal
     * @param inOff  the index of the first input sample
     * @param len    the number of input samples
     */
    public static void up2Hq(int[] S, short[] out, int outOff, short[] in, int inOff, int len) {
        final var c00 = ResamplerRom.silk_resampler_up2_hq_0[0];
        final var c01 = ResamplerRom.silk_resampler_up2_hq_0[1];
        final var c02 = ResamplerRom.silk_resampler_up2_hq_0[2];
        final var c10 = ResamplerRom.silk_resampler_up2_hq_1[0];
        final var c11 = ResamplerRom.silk_resampler_up2_hq_1[1];
        final var c12 = ResamplerRom.silk_resampler_up2_hq_1[2];
        var s0 = S[0];
        var s1 = S[1];
        var s2 = S[2];
        var s3 = S[3];
        var s4 = S[4];
        var s5 = S[5];
        for (var k = 0; k < len; k++) {
            var in32 = in[inOff + k] << 10;
            var Y = in32 - s0;
            var X = smulwb(Y, c00);
            var out32_1 = s0 + X;
            s0 = in32 + X;
            Y = out32_1 - s1;
            X = smulwb(Y, c01);
            var out32_2 = s1 + X;
            s1 = out32_1 + X;
            Y = out32_2 - s2;
            X = smlawb(Y, Y, c02);
            out32_1 = s2 + X;
            s2 = out32_2 + X;
            out[outOff + 2 * k] = (short) sat16(rshiftRound(out32_1, 10));
            Y = in32 - s3;
            X = smulwb(Y, c10);
            out32_1 = s3 + X;
            s3 = in32 + X;
            Y = out32_1 - s4;
            X = smulwb(Y, c11);
            out32_2 = s4 + X;
            s4 = out32_1 + X;
            Y = out32_2 - s5;
            X = smlawb(Y, Y, c12);
            out32_1 = s5 + X;
            s5 = out32_2 + X;
            out[outOff + 2 * k + 1] = (short) sat16(rshiftRound(out32_1, 10));
        }
        S[0] = s0;
        S[1] = s1;
        S[2] = s2;
        S[3] = s3;
        S[4] = s4;
        S[5] = s5;
    }

    /**
     * Upsamples by two using the IIR state of a resampler ({@code silk_resampler_private_up2_HQ_wrapper}).
     *
     * @param S      the resampler state
     * @param out    receives {@code 2 * len} output samples
     * @param outOff the index of the first output sample
     * @param in     the input signal
     * @param inOff  the index of the first input sample
     * @param len    the number of input samples
     */
    public static void up2HqWrapper(ResamplerState S, short[] out, int outOff, short[] in, int inOff, int len) {
        up2Hq(S.sIIR, out, outOff, in, inOff, len);
    }

    /**
     * Filters with a second-order AR filter with single delay elements
     * ({@code silk_resampler_private_AR2}).
     *
     * @param S      the state array
     * @param sOff   the index of the two-element state in {@code S}
     * @param out_Q8 receives the output in Q8
     * @param outOff the index of the first output sample
     * @param in     the input signal
     * @param inOff  the index of the first input sample
     * @param A_Q14  the array holding the two AR coefficients in Q14
     * @param aOff   the index of the first coefficient
     * @param len    the number of samples
     */
    public static void ar2(int[] S, int sOff, int[] out_Q8, int outOff, short[] in, int inOff, short[] A_Q14,
                           int aOff, int len) {
        final var a0 = A_Q14[aOff];
        final var a1 = A_Q14[aOff + 1];
        var s0 = S[sOff];
        var s1 = S[sOff + 1];
        for (var k = 0; k < len; k++) {
            var out32 = s0 + (in[inOff + k] << 8);
            out_Q8[outOff + k] = out32;
            out32 = out32 << 2;
            s0 = smlawb(s1, out32, a0);
            s1 = smulwb(out32, a1);
        }
        S[sOff] = s0;
        S[sOff + 1] = s1;
    }

    /**
     * Upsamples with 2x all-pass upsampling followed by fractional FIR interpolation
     * ({@code silk_resampler_private_IIR_FIR}).
     *
     * @param S      the resampler state
     * @param out    receives the output signal
     * @param outOff the index of the first output sample
     * @param in     the input signal
     * @param inOff  the index of the first input sample
     * @param inLen  the number of input samples
     * @return the index after the last output sample written
     */
    public static int iirFir(ResamplerState S, short[] out, int outOff, short[] in, int inOff, int inLen) {
        var buf = S.scratchIirFir;
        System.arraycopy(S.sFIR_i16, 0, buf, 0, RESAMPLER_ORDER_FIR_12);
        var index_increment_Q16 = S.invRatio_Q16;
        int nSamplesIn;
        while (true) {
            nSamplesIn = Math.min(inLen, S.batchSize);
            up2Hq(S.sIIR, buf, RESAMPLER_ORDER_FIR_12, in, inOff, nSamplesIn);
            var max_index_Q16 = nSamplesIn << (16 + 1);
            outOff = SilkKernels.INSTANCE.iirFirInterpol(out, outOff, buf, max_index_Q16, index_increment_Q16,
                    S.scratchKernel);
            inOff += nSamplesIn;
            inLen -= nSamplesIn;
            if (inLen > 0) {
                System.arraycopy(buf, nSamplesIn << 1, buf, 0, RESAMPLER_ORDER_FIR_12);
            } else {
                break;
            }
        }
        System.arraycopy(buf, nSamplesIn << 1, S.sFIR_i16, 0, RESAMPLER_ORDER_FIR_12);
        return outOff;
    }

    /**
     * Downsamples with a second-order AR filter followed by FIR interpolation
     * ({@code silk_resampler_private_down_FIR}).
     *
     * @param S      the resampler state
     * @param out    receives the output signal
     * @param outOff the index of the first output sample
     * @param in     the input signal
     * @param inOff  the index of the first input sample
     * @param inLen  the number of input samples
     * @return the index after the last output sample written
     */
    public static int downFir(ResamplerState S, short[] out, int outOff, short[] in, int inOff, int inLen) {
        var buf = S.scratchDownFir;
        System.arraycopy(S.sFIR_i32, 0, buf, 0, S.FIR_Order);
        var index_increment_Q16 = S.invRatio_Q16;
        int nSamplesIn;
        while (true) {
            nSamplesIn = Math.min(inLen, S.batchSize);
            ar2(S.sIIR, 0, buf, S.FIR_Order, in, inOff, S.Coefs, 0, nSamplesIn);
            var max_index_Q16 = nSamplesIn << 16;
            outOff = SilkKernels.INSTANCE.downFirInterpol(out, outOff, buf, S.Coefs, 2, S.FIR_Order, S.FIR_Fracs,
                    max_index_Q16, index_increment_Q16, S.scratchKernel);
            inOff += nSamplesIn;
            inLen -= nSamplesIn;
            if (inLen > 1) {
                System.arraycopy(buf, nSamplesIn, buf, 0, S.FIR_Order);
            } else {
                break;
            }
        }
        System.arraycopy(buf, nSamplesIn, S.sFIR_i32, 0, S.FIR_Order);
        return outOff;
    }
}
