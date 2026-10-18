/* Ports vo-amrwbenc 0.1.3 (decim54.c, hp50.c, hp400.c, hp6k.c, hp_wsp.c, lp_dec2.c, preemph.c, deemph.c, residu.c,
   syn_filt.c, convolve.c, scale.c, pit_shrp.c, updt_tar.c, random.c, weight_a.c, gpclip.c). Copyright VisualOn;
   Apache License 2.0. Modified for Smalt. */
package com.github.auties00.smalt.audio.amr.internal.wbenc;

import com.github.auties00.smalt.audio.amr.internal.AmrKernel;
import com.github.auties00.smalt.audio.amr.internal.AmrKernels;

import static com.github.auties00.smalt.audio.amr.internal.wbenc.WbBasicOps.*;

/**
 * Implements the filters of the AMR-WB encoder: the 16 kHz to 12.8 kHz decimation, the high-pass
 * and band-pass filters, the pre-emphasis and de-emphasis filters, the LP analysis and synthesis
 * filters, convolution, scaling and the small vector helpers of the subframe loop.
 *
 * <p>An instance holds the scratch buffers the filters need, so filtering allocates nothing; an
 * instance is not thread-safe. Arrays are passed with an offset where the reference passes a
 * pointer into an array, and filters that read history before that offset rely on the caller's
 * array holding it, as the reference does.
 *
 * @implNote This implementation computes as the 3GPP TS 26.173 fixed-point reference does: saturating accumulations,
 * the reference's coefficient alignment in the 32-bit synthesis filter, and the pitch gain clipping of the 6.60 and
 * 8.85 kbit/s modes.
 */
final class WbFilters {
    /**
     * The LP order.
     */
    private static final int M = 16;

    /**
     * The number of taps on each side of the decimation filter.
     */
    private static final int NB_COEF_DOWN = 15;

    /**
     * The decimation ratio 4/5 in Q15.
     */
    private static final int DOWN_FAC = 26215;

    /**
     * The numerator of the 50 Hz high-pass filter at 12.8 kHz ({@code b} of {@code hp50.c}).
     */
    private static final short[] HP50_B = {4053, -8106, 4053};

    /**
     * The denominator of the 50 Hz high-pass filter at 12.8 kHz ({@code a} of {@code hp50.c}).
     */
    private static final short[] HP50_A = {8192, 16211, -8021};

    /**
     * The numerator of the 400 Hz high-pass filter at 12.8 kHz ({@code b} of {@code hp400.c}).
     */
    private static final short[] HP400_B = {915, -1830, 915};

    /**
     * The denominator of the 400 Hz high-pass filter at 12.8 kHz ({@code a} of {@code hp400.c}).
     */
    private static final short[] HP400_A = {16384, 29280, -14160};

    /**
     * The denominator of the high-pass filter of the weighted speech ({@code a} of
     * {@code hp_wsp.c}).
     */
    private static final short[] HP_WSP_A = {8192, 21663, -19258, 5734};

    /**
     * The numerator of the high-pass filter of the weighted speech ({@code b} of
     * {@code hp_wsp.c}).
     */
    private static final short[] HP_WSP_B = {-3432, 10280, -10280, 3432};

    /**
     * The 5-tap low-pass filter of the decimation by 2 ({@code h_fir} of {@code lp_dec2.c}).
     */
    private static final short[] LP_DECIM2_FIR = {4260, 7536, 9175, 7536, 4260};

    /**
     * The ISF distance above which the gain clipping memory saturates (120 Hz).
     */
    private static final int DIST_ISF_MAX = 307;

    /**
     * The ISF distance above which the gain clipping memory saturates at 6.60 and 8.85 kbit/s
     * (150 Hz).
     */
    private static final int DIST_ISF_MAX_IO = 384;

    /**
     * The ISF distance below which the pitch gain may be clipped (60 Hz).
     */
    private static final int DIST_ISF_THRES = 154;

    /**
     * The pitch gain memory above which the pitch gain may be clipped (0.9 in Q14).
     */
    private static final int GAIN_PIT_THRES = 14746;

    /**
     * The number of bits of a 6.60 kbit/s frame.
     */
    private static final int NBBITS_7K = 132;

    /**
     * The number of bits of an 8.85 kbit/s frame.
     */
    private static final int NBBITS_9K = 177;

    /**
     * The smallest pitch gain kept by the gain clipping memory.
     */
    private static final int GAIN_PIT_MIN = 9830;

    /**
     * The scratch buffer of the decimation: the filter memory followed by one frame.
     */
    private final short[] decimationBuffer = new short[320 + 2 * NB_COEF_DOWN];

    /**
     * The scratch buffer of the 6-7 kHz band-pass filter: the filter memory followed by one
     * subframe.
     */
    private final short[] bandPassBuffer = new short[80 + 30];

    /**
     * The scratch buffer of the LP synthesis filter: the filter memory followed by one subframe.
     */
    private final short[] synthesisBuffer = new short[80 + 20];

    /**
     * The scratch buffer of the decimation by 2: the filter memory followed by one frame.
     */
    private final short[] decim2Buffer = new short[256 + 3];

    /**
     * The kernel that runs the integer filters.
     */
    private static final AmrKernel KERNEL = AmrKernels.INSTANCE;

    /**
     * The largest magnitude of an accumulation that cannot saturate its rounding.
     */
    private static final long UNSATURATED_SUM = 0x7fff0000L;

    /**
     * The doubled taps of the 6 to 7 kHz band-pass filter.
     */
    private static final int[] BAND_PASS_TAPS = doubled(WbTables.FIR_6K_7K);

    /**
     * The sum of the magnitudes of the taps of the band-pass filter.
     */
    private static final long BAND_PASS_MAGNITUDE = magnitude(WbTables.FIR_6K_7K);

    /**
     * Doubles filter taps into integers.
     *
     * @param taps the filter coefficients
     * @return the doubled taps
     */
    private static int[] doubled(short[] taps) {
        var out = new int[taps.length];
        for (var i = 0; i < out.length; i++) {
            out[i] = 2 * taps[i];
        }
        return out;
    }

    /**
     * Sums the magnitudes of filter taps.
     *
     * @param taps the filter coefficients
     * @return the sum of the magnitudes
     */
    private static long magnitude(short[] taps) {
        var sum = 0L;
        for (var tap : taps) {
            sum += Math.abs(tap);
        }
        return sum;
    }

    /**
     * The impulse response preceded by the zeros of the convolution.
     */
    private final short[] convolvePad = new short[127];

    /**
     * The doubled coefficients of the integer filters.
     */
    private final int[] firCoefficients = new int[64];

    /**
     * The accumulators of the integer filters.
     */
    private final int[] firAccumulators = new int[256];

    /**
     * Creates the filters with their scratch buffers.
     */
    WbFilters() {
    }

    /**
     * Decimates a 16 kHz signal to 12.8 kHz ({@code Decim_12k8}).
     *
     * @param sig16k the 16 kHz input
     * @param sig16kOff the offset of the input
     * @param length the number of input samples
     * @param sig12k8 the 12.8 kHz output, {@code length * 4 / 5} samples
     * @param sig12k8Off the offset of the output
     * @param mem the 30-sample filter memory, updated
     */
    void decim12k8(short[] sig16k, int sig16kOff, int length, short[] sig12k8, int sig12k8Off, short[] mem) {
        var signal = decimationBuffer;
        System.arraycopy(mem, 0, signal, 0, 2 * NB_COEF_DOWN);
        System.arraycopy(sig16k, sig16kOff, signal, 2 * NB_COEF_DOWN, length);
        var lengthDown = mult(length, DOWN_FAC);
        downSample(signal, NB_COEF_DOWN, sig12k8, sig12k8Off, lengthDown);
        System.arraycopy(signal, length, mem, 0, 2 * NB_COEF_DOWN);
    }

    /**
     * Interpolates the 4/5 decimation of a signal with the polyphase filter ({@code Down_samp}).
     *
     * @param sig the input, with {@link #NB_COEF_DOWN} samples of history before the offset
     * @param sigOff the offset of the input
     * @param out the output
     * @param outOff the offset of the output
     * @param lengthD the number of output samples
     */
    private static void downSample(short[] sig, int sigOff, short[] out, int outOff, int lengthD) {
        var fir = WbTables.FIR_DOWN;
        var pos = 0;
        for (var j = 0; j < lengthD; j++) {
            var i = pos >> 2;
            var phase = (pos & 3) * 30;
            var x = sigOff + i - NB_COEF_DOWN + 1;
            var sum = macForward(0, sig, x, fir, phase, 30);
            sum = lShl(sum, 1);
            out[outOff + j] = round(sum);
            pos = add(pos, 5);
        }
    }

    /**
     * Filters a 12.8 kHz signal in place with the 50 Hz second-order high-pass filter
     * ({@code HP50_12k8}).
     *
     * @param signal the signal, filtered in place
     * @param off the offset of the signal
     * @param length the number of samples, at least {@code 1}
     * @param mem the 6-value filter memory, updated
     */
    static void hp50(short[] signal, int off, int length, short[] mem) {
        var y2Hi = mem[0];
        var y2Lo = mem[1];
        var y1Hi = mem[2];
        var y1Lo = mem[3];
        var x0 = mem[4];
        var x1 = mem[5];
        for (var n = 0; n < length; n++) {
            var x2 = x1;
            x1 = x0;
            x0 = signal[off + n];
            var tmp = 16384;
            tmp = lMac(tmp, y1Lo, HP50_A[1]);
            tmp = lMac(tmp, y2Lo, HP50_A[2]);
            tmp = lShr(tmp, 15);
            tmp = lMac(tmp, y1Hi, HP50_A[1]);
            tmp = lMac(tmp, y2Hi, HP50_A[2]);
            tmp = lMac(tmp, x0, HP50_B[0]);
            tmp = lMac(tmp, x1, HP50_B[1]);
            tmp = lMac(tmp, x2, HP50_B[2]);
            tmp = lShl(tmp, 2);
            y2Hi = y1Hi;
            y2Lo = y1Lo;
            y1Hi = hi(tmp);
            y1Lo = lo(tmp);
            tmp = lShl(tmp, 1);
            signal[off + n] = round(tmp);
        }
        mem[0] = y2Hi;
        mem[1] = y2Lo;
        mem[2] = y1Hi;
        mem[3] = y1Lo;
        mem[4] = x0;
        mem[5] = x1;
    }

    /**
     * Filters a 12.8 kHz signal in place with the 400 Hz second-order high-pass filter
     * ({@code HP400_12k8}).
     *
     * @param signal the signal, filtered in place
     * @param off the offset of the signal
     * @param length the number of samples, at least {@code 1}
     * @param mem the 6-value filter memory, updated
     */
    static void hp400(short[] signal, int off, int length, short[] mem) {
        var y2Hi = mem[0];
        var y2Lo = mem[1];
        var y1Hi = mem[2];
        var y1Lo = mem[3];
        var x0 = mem[4];
        var x1 = mem[5];
        for (var n = 0; n < length; n++) {
            var x2 = x1;
            x1 = x0;
            x0 = signal[off + n];
            var tmp = 16384;
            tmp = lMac(tmp, y1Lo, HP400_A[1]);
            tmp = lMac(tmp, y2Lo, HP400_A[2]);
            tmp = lShr(tmp, 15);
            tmp = lMac(tmp, y1Hi, HP400_A[1]);
            tmp = lMac(tmp, y2Hi, HP400_A[2]);
            tmp = lMac(tmp, x0, HP400_B[0]);
            tmp = lMac(tmp, x1, HP400_B[1]);
            tmp = lMac(tmp, x2, HP400_B[2]);
            tmp = lShl(tmp, 1);
            y2Hi = y1Hi;
            y2Lo = y1Lo;
            y1Hi = hi(tmp);
            y1Lo = lo(tmp);
            signal[off + n] = round(tmp);
        }
        mem[0] = y2Hi;
        mem[1] = y2Lo;
        mem[2] = y1Hi;
        mem[3] = y1Lo;
        mem[4] = x0;
        mem[5] = x1;
    }

    /**
     * Filters a 16 kHz signal in place with the 31-tap 6-7 kHz band-pass filter
     * ({@code Filt_6k_7k}).
     *
     * @param signal the signal, filtered in place
     * @param off the offset of the signal
     * @param length the number of samples, at most 80
     * @param mem the 30-sample filter memory, updated
     */
    void filt6k7k(short[] signal, int off, int length, short[] mem) {
        var x = bandPassBuffer;
        var fir = WbTables.FIR_6K_7K;
        System.arraycopy(mem, 0, x, 0, 30);
        var peak = 0;
        var minimum = 0;
        for (var i = 0; i < 30; i++) {
            peak = Math.max(peak, Math.abs(x[i]));
            minimum = Math.min(minimum, x[i]);
        }
        for (var i = 0; i < length; i++) {
            var sample = shr(signal[off + i], 2);
            x[i + 30] = sample;
            peak = Math.max(peak, Math.abs(sample));
            minimum = Math.min(minimum, sample);
        }
        if (2L * peak * BAND_PASS_MAGNITUDE <= UNSATURATED_SUM && minimum != Short.MIN_VALUE) {
            KERNEL.fir(x, 0, BAND_PASS_TAPS, 31, length, firAccumulators, 0);
            for (var i = 0; i < length; i++) {
                signal[off + i] = round(firAccumulators[i]);
            }
        } else {
            for (var i = 0; i < length; i++) {
                signal[off + i] = round(macForward(0, x, i, fir, 0, 31));
            }
        }
        System.arraycopy(x, length, mem, 0, 30);
    }

    /**
     * Rescales the memory of the weighted-speech high-pass filter ({@code scale_mem_Hp_wsp}).
     *
     * @param mem the 9-value filter memory, rescaled in place
     * @param exp the left shift to apply, negative to shift right
     */
    static void scaleMemHpWsp(short[] mem, int exp) {
        for (var i = 0; i < 6; i += 2) {
            var tmp = lMac(mem[i] << 16, mem[i + 1], 1);
            tmp = lShl(tmp, exp);
            mem[i] = hi(tmp);
            mem[i + 1] = lo(tmp);
        }
        for (var i = 6; i < 9; i++) {
            var tmp = mem[i] << 16;
            tmp = lShl(tmp, exp);
            mem[i] = round(tmp);
        }
    }

    /**
     * Filters the decimated weighted speech with the third-order high-pass filter of the
     * open-loop pitch gain ({@code Hp_wsp}).
     *
     * @param wsp the input
     * @param wspOff the offset of the input
     * @param hpWsp the output
     * @param hpWspOff the offset of the output
     * @param length the number of samples
     * @param mem the 9-value filter memory, updated
     */
    static void hpWsp(short[] wsp, int wspOff, short[] hpWsp, int hpWspOff, int length, short[] mem) {
        var y3Hi = mem[0];
        var y3Lo = mem[1];
        var y2Hi = mem[2];
        var y2Lo = mem[3];
        var y1Hi = mem[4];
        var y1Lo = mem[5];
        var x0 = mem[6];
        var x1 = mem[7];
        var x2 = mem[8];
        for (var i = 0; i < length; i++) {
            var x3 = x2;
            x2 = x1;
            x1 = x0;
            x0 = wsp[wspOff + i];
            var tmp = 16384;
            tmp = lMac(tmp, y1Lo, HP_WSP_A[1]);
            tmp = lMac(tmp, y2Lo, HP_WSP_A[2]);
            tmp = lMac(tmp, y3Lo, HP_WSP_A[3]);
            tmp = lShr(tmp, 15);
            tmp = lMac(tmp, y1Hi, HP_WSP_A[1]);
            tmp = lMac(tmp, y2Hi, HP_WSP_A[2]);
            tmp = lMac(tmp, y3Hi, HP_WSP_A[3]);
            tmp = lMac(tmp, x0, HP_WSP_B[0]);
            tmp = lMac(tmp, x1, HP_WSP_B[1]);
            tmp = lMac(tmp, x2, HP_WSP_B[2]);
            tmp = lMac(tmp, x3, HP_WSP_B[3]);
            tmp = lShl(tmp, 2);
            y3Hi = y2Hi;
            y3Lo = y2Lo;
            y2Hi = y1Hi;
            y2Lo = y1Lo;
            y1Hi = hi(tmp);
            y1Lo = lo(tmp);
            tmp = lShl(tmp, 1);
            hpWsp[hpWspOff + i] = round(tmp);
        }
        mem[0] = y3Hi;
        mem[1] = y3Lo;
        mem[2] = y2Hi;
        mem[3] = y2Lo;
        mem[4] = y1Hi;
        mem[5] = y1Lo;
        mem[6] = x0;
        mem[7] = x1;
        mem[8] = x2;
    }

    /**
     * Low-pass filters and decimates a signal by 2 in place ({@code LP_Decim2}).
     *
     * @param x the signal, replaced by its first {@code length / 2} decimated samples
     * @param off the offset of the signal
     * @param length the number of input samples, even and at most 256
     * @param mem the 3-sample filter memory, updated
     */
    void lpDecim2(short[] x, int off, int length, short[] mem) {
        var buffer = decim2Buffer;
        for (var i = 0; i < 3; i++) {
            buffer[i] = mem[i];
        }
        System.arraycopy(x, off, buffer, 3, length);
        for (var i = 0; i < 3; i++) {
            mem[i] = x[off + length - 3 + i];
        }
        for (int i = 0, j = 0; i < length; i += 2, j++) {
            var tmp = 0;
            for (var k = 0; k < 5; k++) {
                tmp = lMac(tmp, buffer[i + k], LP_DECIM2_FIR[k]);
            }
            x[off + j] = round(tmp);
        }
    }

    /**
     * Applies the pre-emphasis filter {@code 1 - mu z^-1} in place ({@code Preemph}).
     *
     * @param x the signal, filtered in place
     * @param off the offset of the signal
     * @param mu the filter factor in Q15
     * @param length the number of samples, at least {@code 1}
     * @param mem the previous input sample
     * @return the new memory: the last input sample
     */
    static short preemph(short[] x, int off, int mu, int length, short mem) {
        var temp = x[off + length - 1];
        for (var i = length - 1; i > 0; i--) {
            var tmp = x[off + i] << 16;
            tmp = lMsu(tmp, x[off + i - 1], mu);
            x[off + i] = round(tmp);
        }
        var tmp = x[off] << 16;
        tmp = lMsu(tmp, mem, mu);
        x[off] = round(tmp);
        return temp;
    }

    /**
     * Applies the pre-emphasis filter {@code 1 - mu z^-1} with a gain of 2 in place
     * ({@code Preemph2}).
     *
     * @param x the signal, filtered in place
     * @param off the offset of the signal
     * @param mu the filter factor in Q15
     * @param length the number of samples, at least {@code 1}
     * @param mem the previous input sample
     * @return the new memory: the last input sample
     */
    static short preemph2(short[] x, int off, int mu, int length, short mem) {
        var temp = x[off + length - 1];
        for (var i = length - 1; i > 0; i--) {
            var tmp = x[off + i] << 16;
            tmp = lMsu(tmp, x[off + i - 1], mu);
            tmp = lShl(tmp, 1);
            x[off + i] = round(tmp);
        }
        var tmp = x[off] << 16;
        tmp = lMsu(tmp, mem, mu);
        tmp = lShl(tmp, 1);
        x[off] = round(tmp);
        return temp;
    }

    /**
     * Applies the de-emphasis filter {@code 1 / (1 - mu z^-1)} with a gain of 1/2 in place
     * ({@code Deemph2}).
     *
     * @param x the signal, filtered in place
     * @param off the offset of the signal
     * @param mu the filter factor in Q15
     * @param length the number of samples, at least {@code 1}
     * @param mem the previous output sample
     * @return the new memory: the last output sample
     */
    static short deemph2(short[] x, int off, int mu, int length, short mem) {
        var tmp = lMult(x[off], 16384);
        tmp = lMac(tmp, mem, mu);
        x[off] = round(tmp);
        for (var i = 1; i < length; i++) {
            tmp = lMult(x[off + i], 16384);
            tmp = lMac(tmp, x[off + i - 1], mu);
            x[off + i] = round(tmp);
        }
        return x[off + length - 1];
    }

    /**
     * Applies the de-emphasis filter to a double-precision signal ({@code Deemph_32}).
     *
     * @param xHi the high halves of the input
     * @param xLo the low halves of the input
     * @param xOff the offset of the input
     * @param y the output
     * @param mu the filter factor in Q15
     * @param length the number of samples, at least {@code 1}
     * @param mem the previous output sample
     * @return the new memory: the last output sample
     */
    static short deemph32(short[] xHi, short[] xLo, int xOff, short[] y, int mu, int length, short mem) {
        var fac = shr(mu, 1);
        var tmp = xHi[xOff] << 16;
        tmp = lMac(tmp, xLo[xOff], 8);
        tmp = lShl(tmp, 3);
        tmp = lMac(tmp, mem, fac);
        tmp = lShl(tmp, 1);
        y[0] = round(tmp);
        for (var i = 1; i < length; i++) {
            tmp = xHi[xOff + i] << 16;
            tmp = lMac(tmp, xLo[xOff + i], 8);
            tmp = lShl(tmp, 3);
            tmp = lMac(tmp, y[i - 1], fac);
            tmp = lShl(tmp, 1);
            y[i] = round(tmp);
        }
        return y[length - 1];
    }

    /**
     * Computes the LP residual {@code y = A(z) x} ({@code Residu}).
     *
     * @param a the 17 LP coefficients in Q12
     * @param aOff the offset of the coefficients
     * @param x the input, with 16 samples of history before the offset
     * @param xOff the offset of the input
     * @param y the output
     * @param yOff the offset of the output
     * @param length the number of samples
     */
    void residu(short[] a, int aOff, short[] x, int xOff, short[] y, int yOff, int length) {
        var sum = 0L;
        var minA = 0;
        for (var k = 0; k <= M; k++) {
            sum += Math.abs(a[aOff + k]);
            minA = Math.min(minA, a[aOff + k]);
        }
        var peak = 0;
        var minX = 0;
        for (var i = xOff - M; i < xOff + length; i++) {
            peak = Math.max(peak, Math.abs(x[i]));
            minX = Math.min(minX, x[i]);
        }
        if (2L * sum * peak <= UNSATURATED_SUM && (minA != Short.MIN_VALUE || minX != Short.MIN_VALUE)) {
            for (var k = 0; k <= M; k++) {
                firCoefficients[k] = 2 * a[aOff + k];
            }
            KERNEL.firReverse(x, xOff, firCoefficients, M + 1, length, firAccumulators, 0);
            for (var i = 0; i < length; i++) {
                y[yOff + i] = round(lShl(firAccumulators[i], 4));
            }
            return;
        }
        for (var i = 0; i < length; i++) {
            var p = xOff + i;
            var s = macReverse(lMult(x[p], a[aOff]), a, aOff + 1, x, p - 1, M);
            s = lShl(s, 4);
            y[yOff + i] = round(s);
        }
    }

    /**
     * Applies the LP synthesis filter {@code 1 / A(z)} ({@code Syn_filt}).
     *
     * <p>The input and output may be the same array region.
     *
     * @param a the 17 LP coefficients in Q12
     * @param aOff the offset of the coefficients
     * @param x the input
     * @param xOff the offset of the input
     * @param y the output
     * @param yOff the offset of the output
     * @param length the number of samples, at most 80
     * @param mem the 16 previous output samples
     * @param memOff the offset of the memory
     * @param update whether to store the last 16 output samples in the memory
     */
    void synFilt(short[] a, int aOff, short[] x, int xOff, short[] y, int yOff, int length,
                 short[] mem, int memOff, boolean update) {
        var buffer = synthesisBuffer;
        System.arraycopy(mem, memOff, buffer, 0, M);
        var s = sub(normS(a[aOff]), 2);
        var a0 = shr(a[aOff], 1);
        var shift = add(3, s);
        var magnitude = 0L;
        var minimum = Math.min(0, a0);
        for (var k = 1; k <= M; k++) {
            magnitude += Math.abs(a[aOff + k]);
            minimum = Math.min(minimum, a[aOff + k]);
        }
        var peak = 0;
        for (var k = 0; k < M; k++) {
            peak = Math.max(peak, Math.abs(buffer[k]));
            minimum = Math.min(minimum, buffer[k]);
        }
        var inputPeak = 0;
        for (var i = 0; i < length; i++) {
            inputPeak = Math.max(inputPeak, Math.abs(x[xOff + i]));
            minimum = Math.min(minimum, x[xOff + i]);
        }
        for (var i = 0; i < length; i++) {
            var dot = x[xOff + i] * a0;
            for (var k = 1; k <= M; k++) {
                dot -= a[aOff + k] * buffer[M + i - k];
            }
            var out = round(lShl(dot << 1, shift));
            buffer[M + i] = out;
            peak = Math.max(peak, Math.abs(out));
            minimum = Math.min(minimum, out);
        }
        if (2L * (peak * magnitude + inputPeak * (long) Math.abs(a0)) <= UNSATURATED_SUM && minimum != Short.MIN_VALUE) {
            System.arraycopy(buffer, M, y, yOff, length);
        } else {
            for (var i = 0; i < length; i++) {
                var tmp = msuReverse(lMult(x[xOff + i], a0), a, aOff + 1, buffer, M + i - 1, M);
                tmp = lShl(tmp, shift);
                var out = round(tmp);
                buffer[M + i] = out;
                y[yOff + i] = out;
            }
        }
        if (update) {
            System.arraycopy(buffer, length, mem, memOff, M);
        }
    }

    /**
     * Applies the LP synthesis filter to a scaled excitation with a double-precision output
     * ({@code Syn_filt_32}).
     *
     * @param a the LP coefficients in Q12
     * @param aOff the offset of the coefficients
     * @param exc the excitation
     * @param excOff the offset of the excitation
     * @param qNew the scaling of the excitation
     * @param sigHi the high halves of the output, with 16 samples of history before the offset
     * @param sigLo the low halves of the output, with 16 samples of history before the offset
     * @param sigOff the offset of the output
     * @param length the number of samples
     */
    static void synFilt32(short[] a, int aOff, short[] exc, int excOff, int qNew, short[] sigHi, short[] sigLo,
                          int sigOff, int length) {
        var s = sub(normS(a[aOff]), 2);
        var a0 = shr(a[aOff], add(4, qNew));
        var shift = add(3, s);
        var magnitude = 0L;
        var minimum = Math.min(0, a0);
        for (var k = 1; k <= M; k++) {
            magnitude += Math.abs(a[aOff + k]);
            minimum = Math.min(minimum, a[aOff + k]);
        }
        var peak = 0;
        for (var k = 1; k <= M; k++) {
            peak = Math.max(peak, Math.max(Math.abs(sigLo[sigOff - k]), Math.abs(sigHi[sigOff - k])));
            minimum = Math.min(minimum, Math.min(sigLo[sigOff - k], sigHi[sigOff - k]));
        }
        var excitationPeak = 0;
        for (var i = 0; i < length; i++) {
            excitationPeak = Math.max(excitationPeak, Math.abs(exc[excOff + i]));
            minimum = Math.min(minimum, exc[excOff + i]);
        }
        for (var i = 0; i < length; i++) {
            var p = sigOff + i;
            var low = 0;
            var dot = exc[excOff + i] * a0;
            for (var k = 1; k <= M; k++) {
                var c = a[aOff + k];
                low += sigLo[p - k] * c;
                dot -= sigHi[p - k] * c;
            }
            var tmp = ((-(low << 1)) >> 12) + (dot << 1);
            tmp = lShl(tmp, shift);
            var high = extractH(tmp);
            sigHi[p] = high;
            tmp = lShr(tmp, 4);
            var rest = (short) lMsu(tmp, high, 2048);
            sigLo[p] = rest;
            peak = Math.max(peak, Math.max(Math.abs(high), Math.abs(rest)));
            minimum = Math.min(minimum, Math.min(high, rest));
        }
        if (2L * (2 * peak * magnitude + excitationPeak * (long) Math.abs(a0)) <= UNSATURATED_SUM && minimum != Short.MIN_VALUE) {
            return;
        }
        for (var i = 0; i < length; i++) {
            var p = sigOff + i;
            var tmp = msuReverse(0, a, aOff + 1, sigLo, p - 1, M);
            tmp = lShr(tmp, 16 - 4);
            tmp = lMac(tmp, exc[excOff + i], a0);
            tmp = msuReverse(tmp, a, aOff + 1, sigHi, p - 1, M);
            tmp = lShl(tmp, shift);
            var high = extractH(tmp);
            sigHi[p] = high;
            tmp = lShr(tmp, 4);
            sigLo[p] = (short) lMsu(tmp, high, 2048);
        }
    }

    /**
     * Convolves a 64-sample vector with a 64-sample impulse response ({@code Convolve}).
     *
     * @param x the input
     * @param xOff the offset of the input
     * @param h the impulse response
     * @param y the 64-sample output
     */
    void convolve(short[] x, int xOff, short[] h, short[] y) {
        var sum = 0L;
        var peakX = 0;
        var minX = 0;
        var peakH = 0;
        var minH = 0;
        for (var i = 0; i < 64; i++) {
            var sample = x[xOff + i];
            var tap = h[i];
            sum += Math.abs(sample);
            peakH = Math.max(peakH, Math.abs(tap));
            minX = Math.min(minX, sample);
            minH = Math.min(minH, tap);
        }
        if (2L * sum * peakH <= UNSATURATED_SUM && (minX != Short.MIN_VALUE || minH != Short.MIN_VALUE)) {
            System.arraycopy(h, 0, convolvePad, 63, 64);
            for (var i = 0; i < 64; i++) {
                firCoefficients[i] = 2 * x[xOff + i];
            }
            for (var block = 0; block < 8; block++) {
                KERNEL.firReverse(convolvePad, 63 + 8 * block, firCoefficients, 8 * block + 8, 8, firAccumulators, 8 * block);
            }
            for (var n = 0; n < 64; n++) {
                y[n] = (short) ((firAccumulators[n] + 0x8000) >> 16);
            }
            return;
        }
        for (var n = 0; n < 64; n++) {
            y[n] = round(macReverse(0, x, xOff, h, n, n + 1));
        }
    }

    /**
     * Scales a signal in place by a power of 2 with rounding ({@code Scale_sig}).
     *
     * @param x the signal, scaled in place
     * @param off the offset of the signal
     * @param length the number of samples
     * @param exp the left shift, negative to shift right
     */
    static void scaleSig(short[] x, int off, int length, int exp) {
        if (exp > 0) {
            var shift = Math.min(exp, 31);
            var high = MAX_32 >> shift;
            var low = MIN_32 >> shift;
            for (var i = 0; i < length; i++) {
                var value = x[off + i] << 16;
                x[off + i] = round(value > high ? MAX_32 : value < low ? MIN_32 : value << shift);
            }
        } else {
            var shift = Math.min(-exp, 31);
            for (var i = 0; i < length; i++) {
                x[off + i] = round((x[off + i] << 16) >> shift);
            }
        }
    }

    /**
     * Scales one value by a power of 2 with rounding, as {@link #scaleSig(short[], int, int, int)}
     * does for each element.
     *
     * @param x the value
     * @param exp the left shift, negative to shift right
     * @return the scaled value
     */
    static short scaleOne(short x, int exp) {
        return round(lShl(x << 16, exp));
    }

    /**
     * Sharpens a codebook vector with its pitch-periodic copy ({@code Pit_shrp}).
     *
     * @param x the vector, sharpened in place
     * @param pitLag the pitch lag
     * @param sharp the sharpening factor in Q15
     * @param length the vector length
     */
    static void pitShrp(short[] x, int pitLag, int sharp, int length) {
        for (var i = pitLag; i < length; i++) {
            var tmp = x[i] << 16;
            tmp = lMac(tmp, x[i - pitLag], sharp);
            x[i] = round(tmp);
        }
    }

    /**
     * Updates a target vector by removing a filtered, gain-scaled contribution
     * ({@code Updt_tar}).
     *
     * @param x the old target
     * @param x2 the new target, which may be {@code x}
     * @param y the filtered contribution
     * @param yOff the offset of the contribution
     * @param gain the gain in Q14
     * @param length the vector length
     */
    static void updtTar(short[] x, short[] x2, short[] y, int yOff, int gain, int length) {
        for (var i = 0; i < length; i++) {
            var tmp = lMult(x[i], 16384);
            tmp = lMsu(tmp, y[yOff + i], gain);
            x2[i] = extractH(lShl(tmp, 1));
        }
    }

    /**
     * Advances the linear congruential noise generator ({@code Random}).
     *
     * @param seed the current seed
     * @return the next seed, which is also the generated value
     */
    static short random(short seed) {
        return (short) lAdd(lShr(lMult(seed, 31821), 1), 13849);
    }

    /**
     * Weights LP coefficients by the powers of a factor ({@code Weight_a}).
     *
     * @param a the coefficients
     * @param aOff the offset of the coefficients
     * @param ap the weighted coefficients
     * @param apOff the offset of the weighted coefficients
     * @param gamma the weighting factor in Q15
     * @param m the LP order
     */
    static void weightA(short[] a, int aOff, short[] ap, int apOff, int gamma, int m) {
        ap[apOff] = a[aOff];
        var fac = (short) gamma;
        for (var i = 1; i < m; i++) {
            ap[apOff + i] = round(lMult(a[aOff + i], fac));
            fac = round(lMult(fac, gamma));
        }
        ap[apOff + m] = round(lMult(a[aOff + m], fac));
    }

    /**
     * Initializes the gain clipping memory ({@code Init_gp_clip}).
     *
     * @param mem the 2-value memory: the ISF distance and the pitch gain
     */
    static void initGpClip(short[] mem) {
        mem[0] = DIST_ISF_MAX;
        mem[1] = GAIN_PIT_MIN;
    }

    /**
     * Updates the gain clipping memory with the minimum ISF distance of a frame
     * ({@code Gp_clip_test_isf}).
     *
     * @param serSize the number of bits of the frame
     * @param isf the 16 ISFs
     * @param mem the 2-value memory, updated
     */
    static void gpClipTestIsf(int serSize, short[] isf, short[] mem) {
        var distMin = sub(isf[1], isf[0]);
        for (var i = 2; i < M - 1; i++) {
            var dist = sub(isf[i], isf[i - 1]);
            if (dist < distMin) {
                distMin = dist;
            }
        }
        var dist = extractH(lMac(lMult(26214, mem[0]), 6554, distMin));
        var max = serSize == NBBITS_7K || serSize == NBBITS_9K ? DIST_ISF_MAX_IO : DIST_ISF_MAX;
        if (dist > max) {
            dist = (short) max;
        }
        mem[0] = dist;
    }

    /**
     * Tells whether the pitch gain must be clipped to avoid an unstable synthesis after frame
     * erasures ({@code Gp_clip}).
     *
     * <p>At 6.60 and 8.85 kbit/s the pitch gain memory is compared with a threshold that rises
     * with the ISF distance; at the other rates the clipping applies when the ISF distance is
     * small and the pitch gain memory large.
     *
     * @param serSize the number of bits of the frame
     * @param mem the clipping memory: the ISF distance and the pitch gain
     * @return {@code true} when the gain must be clipped
     */
    static boolean gpClip(int serSize, short[] mem) {
        if (serSize == NBBITS_7K || serSize == NBBITS_9K) {
            var threshold = add(14746, mult(1638, (short) lMult(mem[0], 16384 / DIST_ISF_MAX_IO)));
            return mem[1] > threshold;
        }
        return mem[0] < DIST_ISF_THRES && mem[1] > GAIN_PIT_THRES;
    }

    /**
     * Updates the gain clipping memory with a quantized pitch gain
     * ({@code Gp_clip_test_gain_pit}).
     *
     * @param serSize the number of bits of the frame
     * @param gainPit the pitch gain in Q14
     * @param mem the 2-value memory, updated
     */
    static void gpClipTestGainPit(int serSize, int gainPit, short[] mem) {
        int tmp;
        if (serSize == NBBITS_7K || serSize == NBBITS_9K) {
            tmp = lMult(32113, mem[1]);
            tmp = lMac(tmp, 655, gainPit);
        } else {
            tmp = lMult(29491, mem[1]);
            tmp = lMac(tmp, 3277, gainPit);
        }
        var gain = extractH(tmp);
        if (gain < GAIN_PIT_MIN) {
            gain = GAIN_PIT_MIN;
        }
        mem[1] = gain;
    }
}
