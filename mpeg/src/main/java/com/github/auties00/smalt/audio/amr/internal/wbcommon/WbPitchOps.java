/* Ports opencore-amr 0.1.6 (pred_lt4.cpp, median5.cpp) and vo-amrwbenc 0.1.3 (pred_lt4.c, p_med_ol.c). Copyright
   PacketVideo, VisualOn; Apache License 2.0. Modified for Smalt. */
package com.github.auties00.smalt.audio.amr.internal.wbcommon;

import com.github.auties00.smalt.audio.amr.internal.AmrKernel;
import com.github.auties00.smalt.audio.amr.internal.AmrKernels;

/**
 * Implements the pitch routines the AMR-WB decoder and encoder share: the interpolation of the adaptive codebook
 * and the median of five values.
 *
 * <p>Arguments are not checked; values outside the documented ranges give unspecified results.
 */
public final class WbPitchOps {
    /**
     * The kernel that runs the interpolation filters.
     */
    private static final AmrKernel KERNEL = AmrKernels.INSTANCE;

    /**
     * The largest magnitude of an accumulation whose doubling and rounding cannot saturate.
     */
    private static final long UNSATURATED_SUM = 0x7fff0000L;

    /**
     * The doubled taps of each phase of the interpolation filter.
     */
    private static final int[][] INTER_TAPS = new int[4][32];

    /**
     * The sum of the magnitudes of the taps of each phase of the interpolation filter.
     */
    private static final long[] INTER_MAGNITUDE = new long[4];

    static {
        for (var row = 0; row < 4; row++) {
            for (var i = 0; i < 32; i++) {
                var tap = WbCodecTables.INTER4_2[row * 32 + i];
                INTER_TAPS[row][i] = 2 * tap;
                INTER_MAGNITUDE[row] += Math.abs(tap);
            }
        }
    }

    /**
     * Prevents instantiation.
     */
    private WbPitchOps() {
    }

    /**
     * Computes the adaptive codebook vector by interpolating the past excitation at a fractional pitch lag with the
     * quarter-sample filters ({@code Pred_lt4}).
     *
     * <p>The 32 taps accumulate with the saturation and in the order of TS 26.173, and the sum is doubled and
     * rounded to 16 bits.
     *
     * @implNote This implementation sums with wrapping 32-bit arithmetic first and redoes the vector with saturating
     * arithmetic only when the magnitudes of the samples and taps allow a sum to saturate. A tap of
     * {@code -32768} would make a single product saturate, and no tap of {@link WbCodecTables#INTER4_2} has that
     * value.
     *
     * @param exc the excitation, read before {@code off} and written from it
     * @param off the offset of the vector
     * @param t0 the integer pitch lag, positive
     * @param frac the fractional lag, from -3 to 3
     * @param length the number of samples to compute, at most 80
     * @param scratch a scratch buffer of at least 80 integers
     */
    public static void predLt4(short[] exc, int off, int t0, int frac, int length, int[] scratch) {
        var x = off - t0;
        frac = -frac;
        if (frac < 0) {
            frac += 4;
            x--;
        }
        x -= 15;
        var row = 3 - frac;
        var peak = 0;
        for (var i = x; i < off; i++) {
            peak = Math.max(peak, Math.abs(exc[i]));
        }
        // An output reads the vector from t0 - 16 samples back, so a block of that many reads only earlier ones.
        var block = Math.clamp(t0 - 16, 1, 80);
        for (var start = 0; start < length; start += block) {
            var count = Math.min(block, length - start);
            KERNEL.fir(exc, x + start, INTER_TAPS[row], 32, count, scratch, 0);
            for (var n = 0; n < count; n++) {
                var out = roundDoubled(scratch[n]);
                exc[off + start + n] = out;
                peak = Math.max(peak, Math.abs(out));
            }
        }
        if (2L * peak * INTER_MAGNITUDE[row] <= UNSATURATED_SUM) {
            return;
        }
        var filter = row * 32;
        for (var n = 0; n < length; n++) {
            var sum = 0L;
            for (var i = 0; i < 32; i++) {
                sum = saturate(sum + 2 * exc[x + n + i] * WbCodecTables.INTER4_2[filter + i]);
            }
            exc[off + n] = roundDoubled((int) sum);
        }
    }

    /**
     * Returns the median of five values ({@code median5}).
     *
     * @param x the array holding the values
     * @param off the index of the middle value; the values are {@code x[off - 2]} to {@code x[off + 2]}
     * @return the median
     */
    public static short median5(short[] x, int off) {
        var x1 = x[off - 2];
        var x2 = x[off - 1];
        var x3 = x[off];
        var x4 = x[off + 1];
        var x5 = x[off + 2];
        short tmp;
        if (x2 < x1) {
            tmp = x1;
            x1 = x2;
            x2 = tmp;
        }
        if (x3 < x1) {
            tmp = x1;
            x1 = x3;
            x3 = tmp;
        }
        if (x4 < x1) {
            tmp = x1;
            x1 = x4;
            x4 = tmp;
        }
        if (x5 < x1) {
            x5 = x1;
        }
        if (x3 < x2) {
            tmp = x2;
            x2 = x3;
            x3 = tmp;
        }
        if (x4 < x2) {
            tmp = x2;
            x2 = x4;
            x4 = tmp;
        }
        if (x5 < x2) {
            x5 = x2;
        }
        if (x4 < x3) {
            x3 = x4;
        }
        if (x5 < x3) {
            x3 = x5;
        }
        return x3;
    }

    /**
     * Clamps a sum to the 32-bit range ({@code L_add} saturation).
     *
     * @param sum the exact sum
     * @return the saturated sum
     */
    private static long saturate(long sum) {
        return Math.clamp(sum, Integer.MIN_VALUE, Integer.MAX_VALUE);
    }

    /**
     * Doubles a 32-bit value with saturation and rounds it to its upper 16 bits ({@code round(L_shl(x, 1))}).
     *
     * @param value the 32-bit sum
     * @return the rounded upper half of the doubled value
     */
    private static short roundDoubled(int value) {
        var doubled = Math.clamp(2L * value, Integer.MIN_VALUE, Integer.MAX_VALUE);
        return (short) (Math.min((long) doubled + 0x8000, Integer.MAX_VALUE) >> 16);
    }
}
