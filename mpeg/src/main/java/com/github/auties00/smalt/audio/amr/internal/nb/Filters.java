/* Ports opencore-amr 0.1.6 (syn_filt.cpp, residu.cpp, weight_a.cpp, pred_lt.cpp). Copyright PacketVideo; Apache
   License 2.0. Modified for Smalt. */
package com.github.auties00.smalt.audio.amr.internal.nb;

import com.github.auties00.smalt.audio.amr.internal.AmrKernel;
import com.github.auties00.smalt.audio.amr.internal.AmrKernels;

import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.MAX_16;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.MIN_16;
import static com.github.auties00.smalt.audio.amr.internal.nb.Constants.M;

/**
 * Implements the LPC filters of the AMR-NB codec: the synthesis filter {@code 1/A(z)}, the
 * analysis filter {@code A(z)}, bandwidth expansion of {@code A(z)}, and the fractional pitch
 * interpolation that builds the adaptive codebook excitation.
 *
 * <p>The filters accumulate with plain wrapping {@code int} arithmetic, as the reference does,
 * and saturate only the final 16-bit outputs.
 *
 * @implNote This implementation reads the synthesis filter memory in place and the pitch interpolation coefficients
 * straight from the table instead of copying them first, which computes the same sums.
 */
final class Filters {
    /**
     * Holds the 1/6-resolution interpolation filter of the adaptive codebook, Q15
     * ({@code pred_lt.cpp} {@code inter_6_pred_lt}).
     */
    static final short[] INTER_6_PRED_LT = {
            29443, 28346, 25207, 20449, 14701, 8693, 3143, -1352, -4402, -5865, -5850, -4673,
            -2783, -672, 1211, 2536, 3130, 2991, 2259, 1170, 0, -1001, -1652, -1868,
            -1666, -1147, -464, 218, 756, 1060, 1099, 904, 550, 135, -245, -514,
            -634, -602, -451, -231, 0, 191, 308, 340, 296, 198, 78, -36,
            -120, -163, -165, -132, -79, -19, 34, 73, 91, 89, 70, 38,
            0
    };

    /**
     * The kernel that runs the integer filters.
     */
    private static final AmrKernel KERNEL = AmrKernels.INSTANCE;

    /**
     * The taps of {@link #INTER_6_PRED_LT} of each of the seven phases, as integers.
     */
    private static final int[][] PRED_LT_TAPS = predLtTaps();

    /**
     * The upsampling factor of the pitch interpolation.
     */
    private static final int UP_SAMP_MAX = 6;

    /**
     * The number of taps on each side of the pitch interpolation filter.
     */
    private static final int L_INTER10 = Constants.L_INTERPOL - 1;

    /**
     * Prevents instantiation.
     */
    private Filters() {
    }

    /**
     * Gathers the taps of each phase of the pitch interpolation filter.
     *
     * @return the seven phases of ten taps
     */
    private static int[][] predLtTaps() {
        var taps = new int[UP_SAMP_MAX + 1][L_INTER10];
        for (var phase = 0; phase <= UP_SAMP_MAX; phase++) {
            for (var m = 0; m < L_INTER10; m++) {
                taps[phase][m] = INTER_6_PRED_LT[phase + UP_SAMP_MAX * m];
            }
        }
        return taps;
    }

    /**
     * Filters a signal through the 10th order synthesis filter {@code 1/A(z)} ({@code Syn_filt}).
     *
     * <p>The output is {@code y[n] = sat((x[n] * a[0] + 0x800 - sum(a[k] * y[n - k])) >> 12)},
     * where {@code y[-k]} is {@code mem[M - k]}. Like the reference, the filter produces
     * {@code M + 2 * ((lg - M) / 2)} outputs, so {@code lg} must be at least {@link Constants#M}.
     * {@code x} and {@code y} may be the same region; {@code mem} must not overlap the first
     * {@link Constants#M} outputs.
     *
     * @param a the 11 filter coefficients, Q12
     * @param aOffset the index of the first coefficient
     * @param x the input signal
     * @param xOffset the index of the first input sample
     * @param y receives the output signal
     * @param yOffset the index of the first output sample
     * @param lg the number of samples
     * @param mem the 10 past outputs, oldest first
     * @param memOffset the index of the oldest past output
     * @param update whether the last 10 outputs are copied into {@code mem}
     */
    static void synFilt(short[] a, int aOffset, short[] x, int xOffset, short[] y, int yOffset, int lg,
                        short[] mem, int memOffset, boolean update) {
        var count = M + 2 * ((lg - M) >> 1);
        var a0 = a[aOffset];
        var head = Math.min(M, count);
        for (var n = 0; n < head; n++) {
            var s = BasicOps.fxpMac(x[xOffset + n], a0, 0x800);
            for (var k = 1; k <= n; k++) {
                s -= a[aOffset + k] * y[yOffset + n - k];
            }
            for (var k = n + 1; k <= M; k++) {
                s -= a[aOffset + k] * mem[memOffset + M + n - k];
            }
            y[yOffset + n] = saturate12(s);
        }
        var a1 = a[aOffset + 1];
        var a2 = a[aOffset + 2];
        var a3 = a[aOffset + 3];
        var a4 = a[aOffset + 4];
        var a5 = a[aOffset + 5];
        var a6 = a[aOffset + 6];
        var a7 = a[aOffset + 7];
        var a8 = a[aOffset + 8];
        var a9 = a[aOffset + 9];
        var a10 = a[aOffset + 10];
        for (var n = M; n < count; n++) {
            var base = yOffset + n;
            var s = BasicOps.fxpMac(x[xOffset + n], a0, 0x800);
            s -= a1 * y[base - 1];
            s -= a2 * y[base - 2];
            s -= a3 * y[base - 3];
            s -= a4 * y[base - 4];
            s -= a5 * y[base - 5];
            s -= a6 * y[base - 6];
            s -= a7 * y[base - 7];
            s -= a8 * y[base - 8];
            s -= a9 * y[base - 9];
            s -= a10 * y[base - 10];
            y[base] = saturate12(s);
        }
        if (update) {
            System.arraycopy(y, yOffset + lg - M, mem, memOffset, M);
        }
    }

    /**
     * Shifts a synthesis filter accumulator right by 12 bits and saturates it the way the
     * reference does, including its mapping of {@code 0x07ffffff} to {@link BasicOps#MIN_16}.
     *
     * @param s the accumulator
     * @return the saturated output sample
     */
    private static short saturate12(int s) {
        if (Integer.compareUnsigned(s + 134217728, 0x0fffffff) < 0) {
            return (short) (s >> 12);
        }
        return s > 0x07ffffff ? MAX_16 : MIN_16;
    }

    /**
     * Filters a signal through the 10th order analysis filter {@code A(z)} ({@code Residu}).
     *
     * <p>The output is {@code y[n] = (0x800 + sum(a[j] * x[n - j], j = 0..10)) >> 12}, truncated
     * to 16 bits; {@code x} must hold the 10 samples before the first input. Like the reference,
     * only the last {@code 4 * (lg / 4)} outputs are computed, so {@code lg} is normally a
     * multiple of 4. {@code x} and {@code y} may be the same region.
     *
     * @param a the 11 filter coefficients, Q12
     * @param aOffset the index of the first coefficient
     * @param x the input signal
     * @param xOffset the index of the first input sample
     * @param y receives the residual
     * @param yOffset the index of the first residual sample
     * @param lg the number of samples
     * @param work scratch of {@code 16 + lg} integers
     */
    static void residu(short[] a, int aOffset, short[] x, int xOffset, short[] y, int yOffset, int lg, int[] work) {
        var last = lg - 4 * (lg >> 2);
        var count = lg - last;
        for (var j = 0; j <= M; j++) {
            work[j] = a[aOffset + j];
        }
        KERNEL.firReverse(x, xOffset + last, work, M + 1, count, work, 16);
        for (var n = 0; n < count; n++) {
            y[yOffset + last + n] = (short) ((work[16 + n] + 0x800) >> 12);
        }
    }

    /**
     * Expands the bandwidth of {@code A(z)} by weighting its coefficients ({@code Weight_Ai}):
     * {@code aExp[i] = round(a[i] * fac[i - 1])}.
     *
     * @param a the 11 coefficients
     * @param aOffset the index of the first coefficient
     * @param fac the 10 weighting factors, Q15
     * @param facOffset the index of the first factor
     * @param aExp receives the 11 weighted coefficients
     * @param aExpOffset the index of the first weighted coefficient
     */
    static void weightAi(short[] a, int aOffset, short[] fac, int facOffset, short[] aExp, int aExpOffset) {
        aExp[aExpOffset] = a[aOffset];
        for (var i = 1; i <= M; i++) {
            aExp[aExpOffset + i] = (short) ((a[aOffset + i] * fac[facOffset + i - 1] + 0x4000) >> 15);
        }
    }

    /**
     * Builds the adaptive codebook excitation by interpolating the past excitation at a
     * fractional pitch lag ({@code Pred_lt_3or6}).
     *
     * <p>The excitation at {@code excOffset} is overwritten with the past excitation delayed by
     * {@code t0 + frac / 3} (or {@code frac / 6}), so {@code exc} must hold the
     * {@code t0 + 11} samples before {@code excOffset}.
     *
     * @param exc the excitation buffer
     * @param excOffset the index of the first output sample
     * @param t0 the integer pitch lag
     * @param frac the fractional pitch lag, in thirds or sixths
     * @param lSubfr the number of samples, even
     * @param flag3 whether {@code frac} is in thirds
     * @param work scratch of 96 integers
     */
    static void predLt3or6(short[] exc, int excOffset, short t0, short frac, int lSubfr, boolean flag3, int[] work) {
        var x0 = excOffset - t0;
        frac = (short) -frac;
        if (flag3) {
            frac = (short) (frac << 1);
        }
        if (frac < 0) {
            frac += UP_SAMP_MAX;
            x0--;
        }
        var back = PRED_LT_TAPS[frac];
        var forward = PRED_LT_TAPS[UP_SAMP_MAX - frac];
        var block = Math.max(1, Math.min(lSubfr, t0 - L_INTER10 - 1));
        for (var start = 0; start < lSubfr; start += block) {
            var count = Math.min(block, lSubfr - start);
            KERNEL.firReverse(exc, x0 + start, back, L_INTER10, count, work, 0);
            KERNEL.fir(exc, x0 + start + 1, forward, L_INTER10, count, work, 48);
            for (var n = 0; n < count; n++) {
                exc[excOffset + start + n] = (short) ((0x4000 + work[n] + work[48 + n]) >> 15);
            }
        }
    }
}
