/* Ports opencore-amr 0.1.6 (wb_syn_filt.cpp, deemphasis_32.cpp, preemph_amrwb_dec.cpp, highpass_50hz_at_12k8.cpp,
   highpass_400hz_at_12k8.cpp, band_pass_6k_7k.cpp, low_pass_filt_7k.cpp, oversamp_12k8_to_16k.cpp, pit_shrp.cpp,
   phase_dispersion.cpp, voice_factor.cpp, scale_signal.cpp, agc2_amr_wb.cpp, weight_amrwb_lpc.cpp). Copyright
   PacketVideo; Apache License 2.0. Modified for Smalt. */
package com.github.auties00.smalt.audio.amr.internal.wb;

import com.github.auties00.smalt.audio.amr.internal.AmrKernel;
import com.github.auties00.smalt.audio.amr.internal.AmrKernels;

import java.util.Arrays;

import static com.github.auties00.smalt.audio.amr.internal.wb.WbMath.*;

/**
 * Implements the signal filters of the AMR-WB decoder: the LP synthesis filters, de-emphasis and
 * pre-emphasis, the high-pass, band-pass and low-pass filters, the 12.8 to 16 kHz resampler, the
 * adaptive codebook interpolation, pitch sharpening, phase dispersion, the voicing factor, the
 * excitation scaling and the gain control of the low-rate pitch enhancer.
 *
 * <p>Every routine works in place on arrays addressed by an offset, and reads the history that
 * precedes the offset where the specification reads before its pointer.
 *
 * @implNote This implementation accumulates with saturation in the order TS 26.173 specifies where opencore replaces
 * the saturating multiply-accumulate chains by wrapping 32-bit sums, so the overload frames of the TS 26.174 sequences
 * decode bit-exactly; each routine concerned says so.
 */
final class WbFilters {
    /**
     * The length of the band-pass and low-pass filter histories.
     */
    private static final int L_FIR = 30;

    /**
     * The number of history samples of the resampler.
     */
    private static final int NB_COEF_UP = 12;

    /**
     * The bias that maps the signed 32-bit range onto the unsigned one.
     */
    private static final long SIGN = 1L << 31;

    /**
     * The largest magnitude of an accumulation that cannot saturate its rounding.
     */
    private static final long UNSATURATED_SUM = 0x7fff0000L;

    /**
     * The sum of the magnitudes of the taps of the band-pass filter, with its last tap.
     */
    private static final long BAND_PASS_MAGNITUDE = magnitude(WbTables.FIR_6K_7K, WbTables.FIR_6K_7K.length) + Math.abs(WbTables.FIR_6K_7K[0]);

    /**
     * The sum of the magnitudes of the taps of the low-pass filter.
     */
    private static final long LOW_PASS_MAGNITUDE = magnitude(WbTables.FIR_7K, WbTables.FIR_7K.length);

    /**
     * The kernel that runs the integer filters.
     */
    private static final AmrKernel KERNEL = AmrKernels.INSTANCE;

    /**
     * The doubled taps of the 6 to 7 kHz band-pass filter, with its last tap.
     */
    private static final int[] BAND_PASS_TAPS = doubled(WbTables.FIR_6K_7K, 0, L_FIR, 1);

    /**
     * The doubled taps of the 7 kHz low-pass filter.
     */
    private static final int[] LOW_PASS_TAPS = doubled(WbTables.FIR_7K, 0, L_FIR + 1, 0);

    /**
     * The largest sum of the magnitudes of the taps of a resampling filter.
     */
    private static final long UP_MAGNITUDE = Arrays.stream(rowMagnitudes(WbTables.FIR_UP, 4, 24)).max().orElseThrow();

    /**
     * Prevents instantiation.
     */
    private WbFilters() {
    }

    /**
     * Sums the magnitudes of the taps of each row of a table of filters.
     *
     * @param taps the table
     * @param rows the number of filters
     * @param size the number of taps of a filter
     * @return the sum of each row
     */
    private static long[] rowMagnitudes(short[] taps, int rows, int size) {
        var sums = new long[rows];
        for (var r = 0; r < rows; r++) {
            for (var i = 0; i < size; i++) {
                sums[r] += Math.abs(taps[r * size + i]);
            }
        }
        return sums;
    }

    /**
     * Doubles a run of filter taps into integers and appends the first of them again.
     *
     * @param taps the table
     * @param from the index of the first tap
     * @param count the number of taps
     * @param repeat the number of copies of the first tap appended
     * @return the doubled taps
     */
    private static int[] doubled(short[] taps, int from, int count, int repeat) {
        var out = new int[count + repeat];
        for (var i = 0; i < count; i++) {
            out[i] = 2 * taps[from + i];
        }
        for (var i = 0; i < repeat; i++) {
            out[count + i] = 2 * taps[from];
        }
        return out;
    }

    /**
     * Sums the magnitudes of the first taps of a filter.
     *
     * @param taps the filter coefficients
     * @param count the number of taps
     * @return the sum of the magnitudes
     */
    private static long magnitude(short[] taps, int count) {
        var sum = 0L;
        for (var i = 0; i < count; i++) {
            sum += Math.abs(taps[i]);
        }
        return sum;
    }

    /**
     * Filters a signal through the LP synthesis filter {@code 1 / A(z)} with 16-bit memory.
     *
     * @implNote This implementation accumulates the taps with saturation and scales by the
     * normalization of {@code a[0]}, as TS 26.173 specifies; opencore wraps the sum, negates it
     * after the final shift and assumes {@code a[0] == 4096}.
     *
     * @param a the coefficients {@code a[0..m]} (Q12)
     * @param aOff the offset of the coefficients
     * @param m the filter order
     * @param x the input signal, which may be {@code y}
     * @param xOff the offset of the input
     * @param y the output signal
     * @param yOff the offset of the output
     * @param lg the number of samples
     * @param mem the filter memory of {@code m} samples, updated with the last output samples
     * @param memOff the offset of the memory
     * @param yBuf a scratch buffer of {@code m + lg} samples
     * @param bufOff the offset of the scratch buffer
     */
    static void wbSynFilt(short[] a, int aOff, int m, short[] x, int xOff, short[] y, int yOff, int lg,
                          short[] mem, int memOff, short[] yBuf, int bufOff) {
        System.arraycopy(mem, memOff, yBuf, bufOff, m);
        var yy = bufOff + m;
        var s = normS(a[aOff]) - 2;
        var a0 = (short) (a[aOff] >> 1);
        var magnitude = 0;
        for (var k = 1; k <= m; k++) {
            magnitude += Math.abs(a[aOff + k]);
        }
        var peak = 0;
        for (var k = 0; k < m; k++) {
            peak = Math.max(peak, Math.abs(yBuf[bufOff + k]));
        }
        var inputPeak = 0;
        for (var n = 0; n < lg; n++) {
            inputPeak = Math.max(inputPeak, Math.abs(x[xOff + n]));
        }
        for (var n = 0; n < lg; n++) {
            var dot = x[xOff + n] * a0;
            for (var k = 1; k <= m; k++) {
                dot -= a[aOff + k] * yBuf[yy + n - k];
            }
            var out = round(shl32(dot << 1, 3 + s));
            yBuf[yy + n] = out;
            peak = Math.max(peak, Math.abs(out));
        }
        if (2L * (peak * (long) magnitude + inputPeak * (long) Math.abs(a0)) <= UNSATURATED_SUM) {
            System.arraycopy(yBuf, yy, y, yOff, lg);
        } else {
            for (var n = 0; n < lg; n++) {
                long acc = mul(x[xOff + n], a0);
                for (var k = 1; k <= m; k++) {
                    acc = saturate(acc - mul(a[aOff + k], yBuf[yy + n - k]));
                }
                var sum = shl32((int) acc, 3 + s);
                var out = round(sum);
                yBuf[yy + n] = out;
                y[yOff + n] = out;
            }
        }
        System.arraycopy(y, yOff + lg - m, mem, memOff, m);
    }

    /**
     * Filters the excitation through the LP synthesis filter with 32-bit precision, producing the
     * high and low parts of the synthesis.
     *
     * @implNote This implementation accumulates both parts with saturation and scales by the
     * normalization of {@code a[0]}, as TS 26.173 specifies; opencore wraps the sums and assumes
     * {@code a[0] == 4096}, which the adaptively scaled coefficients of comfort noise frames
     * break.
     *
     * @param a the coefficients {@code a[0..m]} (Q12)
     * @param aOff the offset of the coefficients
     * @param m the filter order
     * @param exc the excitation
     * @param eOff the offset of the excitation
     * @param qNew the scaling of the excitation
     * @param buf the array holding both outputs, each preceded by {@code m} history samples
     * @param hiOff the offset of the high part
     * @param loOff the offset of the low part
     * @param lg the number of samples
     */
    static void synFilt32(short[] a, int aOff, int m, short[] exc, int eOff, int qNew, short[] buf, int hiOff,
                          int loOff, int lg) {
        var s = normS(a[aOff]) - 2;
        var a0 = shr16(a[aOff], 4 + qNew);
        var magnitude = 0;
        for (var k = 1; k <= m; k++) {
            magnitude += Math.abs(a[aOff + k]);
        }
        var peak = 0;
        for (var k = 1; k <= m; k++) {
            peak = Math.max(peak, Math.abs(buf[loOff - k]));
            peak = Math.max(peak, Math.abs(buf[hiOff - k]));
        }
        var excitationPeak = 0;
        for (var n = 0; n < lg; n++) {
            excitationPeak = Math.max(excitationPeak, Math.abs(exc[eOff + n]));
        }
        for (var n = 0; n < lg; n++) {
            var low = 0;
            var dot = exc[eOff + n] * a0;
            for (var k = 1; k <= m; k++) {
                var c = a[aOff + k];
                low += buf[loOff + n - k] * c;
                dot -= buf[hiOff + n - k] * c;
            }
            var acc = ((-(low << 1)) >> 12) + (dot << 1);
            var sum = shl32(acc, 3 + s);
            var high = (short) (sum >> 16);
            buf[hiOff + n] = high;
            sum = sum >> 4;
            var rest = (short) msu(sum, high, 2048);
            buf[loOff + n] = rest;
            peak = Math.max(peak, Math.max(Math.abs(high), Math.abs(rest)));
        }
        if (2L * (2 * peak * (long) magnitude + excitationPeak * (long) Math.abs(a0)) <= UNSATURATED_SUM) {
            return;
        }
        for (var n = 0; n < lg; n++) {
            var acc = 0L;
            for (var k = 1; k <= m; k++) {
                acc = saturate(acc - mul(buf[loOff + n - k], a[aOff + k]));
            }
            acc = shr32((int) acc, 16 - 4);
            acc = saturate(acc + mul(exc[eOff + n], a0));
            for (var k = 1; k <= m; k++) {
                acc = saturate(acc - mul(buf[hiOff + n - k], a[aOff + k]));
            }
            var sum = shl32((int) acc, 3 + s);
            var high = (short) (sum >> 16);
            buf[hiOff + n] = high;
            sum = shr32(sum, 4);
            buf[loOff + n] = (short) msu(sum, high, 2048);
        }
    }

    /**
     * Applies the de-emphasis filter {@code 1 / (1 - mu z^-1)} to a 32-bit signal given as high
     * and low parts.
     *
     * @implNote This implementation adds the feedback term with saturation, as TS 26.173
     * specifies; opencore wraps it.
     *
     * @param buf the array holding the high and low parts
     * @param hiOff the offset of the high part
     * @param loOff the offset of the low part
     * @param y the output
     * @param yOff the offset of the output
     * @param mu the de-emphasis factor
     * @param lg the number of samples
     * @param mem the last output sample of the previous call
     * @return the last output sample, the memory of the next call
     */
    static short deemphasis32(short[] buf, int hiOff, int loOff, short[] y, int yOff, int mu, int lg, short mem) {
        var fac = (short) (mu >> 1);
        var previous = mem;
        for (var n = 0; n < lg; n++) {
            var sum = buf[hiOff + n] << 16;
            sum = mac(sum, buf[loOff + n], 8);
            sum = shl32(sum, 3);
            sum = mac(sum, previous, fac);
            sum = shl32(sum, 1);
            previous = round(sum);
            y[yOff + n] = previous;
        }
        return previous;
    }

    /**
     * Applies the pre-emphasis filter {@code 1 - mu z^-1} in place.
     *
     * @param x the signal
     * @param off the offset of the signal
     * @param mu the pre-emphasis factor
     * @param lg the number of samples
     */
    static void preemph(short[] x, int off, int mu, int lg) {
        for (var i = lg - 1; i != 0; i--) {
            var sum = msu(x[off + i] << 16, x[off + i - 1], mu);
            x[off + i] = round(sum);
        }
    }

    /**
     * Applies the 50 Hz high-pass filter of the 12.8 kHz synthesis in place.
     *
     * @implNote This implementation accumulates the filter with saturation, as TS 26.173
     * specifies; opencore wraps the accumulation and the final shift.
     *
     * @param signal the samples, filtered in place
     * @param off the offset of the signal
     * @param lg the number of samples
     * @param mem the 6-sample filter memory
     */
    static void highpass50(short[] signal, int off, int lg, short[] mem) {
        var y2Hi = mem[0];
        var y2Lo = mem[1];
        var y1Hi = mem[2];
        var y1Lo = mem[3];
        var x0 = mem[4];
        var x1 = mem[5];
        for (var i = 0; i < lg; i++) {
            var x2 = x1;
            x1 = x0;
            x0 = signal[off + i];
            var p0 = 16384L + ((long) y1Lo * 16211 << 1);
            var p1 = p0 + ((long) y2Lo * -8021 << 1);
            var p2 = (p1 >> 15) + ((long) y1Hi * 16211 << 1);
            var p3 = p2 + ((long) y2Hi * -8021 << 1);
            var p4 = p3 + ((long) x0 * 4053 << 1);
            var p5 = p4 + ((long) x1 * -8106 << 1);
            var p6 = p5 + ((long) x2 * 4053 << 1);
            int sum;
            if (((p0 + SIGN) | (p1 + SIGN) | (p2 + SIGN) | (p3 + SIGN) | (p4 + SIGN) | (p5 + SIGN) | (p6 + SIGN)) >>> 32 == 0) {
                sum = (int) p6;
            } else {
                sum = 16384;
                sum = mac(sum, y1Lo, 16211);
                sum = mac(sum, y2Lo, -8021);
                sum = shr32(sum, 15);
                sum = mac(sum, y1Hi, 16211);
                sum = mac(sum, y2Hi, -8021);
                sum = mac(sum, x0, 4053);
                sum = mac(sum, x1, -8106);
                sum = mac(sum, x2, 4053);
            }
            sum = shl32(sum, 2);
            y2Hi = y1Hi;
            y2Lo = y1Lo;
            y1Hi = (short) (sum >> 16);
            y1Lo = (short) ((sum >> 1) - (y1Hi << 15));
            signal[off + i] = round(shl32(sum, 1));
        }
        mem[0] = y2Hi;
        mem[1] = y2Lo;
        mem[2] = y1Hi;
        mem[3] = y1Lo;
        mem[4] = x0;
        mem[5] = x1;
    }

    /**
     * Applies the 400 Hz high-pass filter of the high-band gain estimation in place.
     *
     * @implNote This implementation accumulates the filter with saturation, as TS 26.173
     * specifies; opencore wraps the accumulation and the final shift.
     *
     * @param signal the samples, filtered in place
     * @param off the offset of the signal
     * @param lg the number of samples
     * @param mem the 6-sample filter memory
     */
    static void highpass400(short[] signal, int off, int lg, short[] mem) {
        var y2Hi = mem[0];
        var y2Lo = mem[1];
        var y1Hi = mem[2];
        var y1Lo = mem[3];
        var x0 = mem[4];
        var x1 = mem[5];
        for (var i = 0; i < lg; i++) {
            var x2 = x1;
            x1 = x0;
            x0 = signal[off + i];
            var p0 = 16384L + ((long) y1Lo * 29280 << 1);
            var p1 = p0 + ((long) y2Lo * -14160 << 1);
            var p2 = (p1 >> 15) + ((long) y1Hi * 29280 << 1);
            var p3 = p2 + ((long) y2Hi * -14160 << 1);
            var p4 = p3 + ((long) x0 * 915 << 1);
            var p5 = p4 + ((long) x1 * -1830 << 1);
            var p6 = p5 + ((long) x2 * 915 << 1);
            int sum;
            if (((p0 + SIGN) | (p1 + SIGN) | (p2 + SIGN) | (p3 + SIGN) | (p4 + SIGN) | (p5 + SIGN) | (p6 + SIGN)) >>> 32 == 0) {
                sum = (int) p6;
            } else {
                sum = 16384;
                sum = mac(sum, y1Lo, 29280);
                sum = mac(sum, y2Lo, -14160);
                sum = shr32(sum, 15);
                sum = mac(sum, y1Hi, 29280);
                sum = mac(sum, y2Hi, -14160);
                sum = mac(sum, x0, 915);
                sum = mac(sum, x1, -1830);
                sum = mac(sum, x2, 915);
            }
            sum = shl32(sum, 1);
            y2Hi = y1Hi;
            y2Lo = y1Lo;
            y1Hi = (short) (sum >> 16);
            y1Lo = (short) ((sum >> 1) - (y1Hi << 15));
            signal[off + i] = round(sum);
        }
        mem[0] = y2Hi;
        mem[1] = y2Lo;
        mem[2] = y1Hi;
        mem[3] = y1Lo;
        mem[4] = x0;
        mem[5] = x1;
    }

    /**
     * Applies the 31-tap 6 to 7 kHz band-pass filter of the high band in place.
     *
     * @implNote This implementation accumulates the taps with saturation in tap order and rounds,
     * as TS 26.173 specifies; opencore wraps the sum and truncates it to 16 bits. The table holds
     * the first 30 taps; the last tap equals the first.
     *
     * @param signal the samples, filtered in place
     * @param off the offset of the signal
     * @param lg the number of samples
     * @param mem the 30-sample filter memory
     * @param x a scratch buffer of {@code 30 + lg} samples
     * @param xOff the offset of the scratch buffer
     * @param acc a scratch buffer of {@code lg} integers
     */
    static void bandPass6k7k(short[] signal, int off, int lg, short[] mem, short[] x, int xOff, int[] acc) {
        System.arraycopy(mem, 0, x, xOff, L_FIR);
        var peak = 0;
        for (var n = 0; n < lg; n++) {
            x[xOff + L_FIR + n] = (short) (signal[off + n] >> 2);
        }
        for (var n = 0; n < L_FIR + lg; n++) {
            peak = Math.max(peak, Math.abs(x[xOff + n]));
        }
        if (2L * peak * BAND_PASS_MAGNITUDE <= UNSATURATED_SUM) {
            KERNEL.fir(x, xOff, BAND_PASS_TAPS, L_FIR + 1, lg, acc, 0);
            for (var n = 0; n < lg; n++) {
                signal[off + n] = (short) ((acc[n] + 0x8000) >> 16);
            }
        } else {
            var fir = WbTables.FIR_6K_7K;
            for (var n = 0; n < lg; n++) {
                var sum = 0L;
                for (var j = 0; j < L_FIR; j++) {
                    sum = saturate(sum + 2 * x[xOff + n + j] * fir[j]);
                }
                sum = saturate(sum + 2 * x[xOff + n + L_FIR] * fir[0]);
                signal[off + n] = round((int) sum);
            }
        }
        System.arraycopy(x, xOff + lg, mem, 0, L_FIR);
    }

    /**
     * Applies the 31-tap 7 kHz low-pass filter of the 23.85 kbit/s high band in place.
     *
     * @implNote This implementation accumulates the taps with saturation in tap order and rounds,
     * as TS 26.173 specifies; opencore wraps the sum, folds the outer taps into one 16-bit sum and
     * truncates the result to 16 bits.
     *
     * @param signal the samples, filtered in place
     * @param off the offset of the signal
     * @param lg the number of samples
     * @param mem the 30-sample filter memory
     * @param x a scratch buffer of {@code 30 + lg} samples
     * @param xOff the offset of the scratch buffer
     * @param acc a scratch buffer of {@code lg} integers
     */
    static void lowPass7k(short[] signal, int off, int lg, short[] mem, short[] x, int xOff, int[] acc) {
        System.arraycopy(mem, 0, x, xOff, L_FIR);
        System.arraycopy(signal, off, x, xOff + L_FIR, lg);
        var peak = 0;
        for (var n = 0; n < L_FIR + lg; n++) {
            peak = Math.max(peak, Math.abs(x[xOff + n]));
        }
        if (2L * peak * LOW_PASS_MAGNITUDE <= UNSATURATED_SUM) {
            KERNEL.fir(x, xOff, LOW_PASS_TAPS, L_FIR + 1, lg, acc, 0);
            for (var n = 0; n < lg; n++) {
                signal[off + n] = (short) ((acc[n] + 0x8000) >> 16);
            }
        } else {
            var fir = WbTables.FIR_7K;
            for (var n = 0; n < lg; n++) {
                var sum = 0L;
                for (var j = 0; j <= L_FIR; j++) {
                    sum = saturate(sum + 2 * x[xOff + n + j] * fir[j]);
                }
                signal[off + n] = round((int) sum);
            }
        }
        System.arraycopy(x, xOff + lg, mem, 0, L_FIR);
    }

    /**
     * Resamples the 12.8 kHz synthesis to 16 kHz by a factor of 5/4.
     *
     * @implNote This implementation accumulates the interpolation taps with saturation in tap
     * order, as TS 26.173 specifies; opencore wraps the sum before its final saturating shift.
     *
     * @param sig12k8 the 12.8 kHz signal
     * @param sOff the offset of the 12.8 kHz signal
     * @param lg the number of input samples, a multiple of 4
     * @param sig16k the 16 kHz output of {@code lg * 5 / 4} samples
     * @param oOff the offset of the output
     * @param mem the 24-sample resampler memory
     * @param signal a scratch buffer of {@code 24 + lg} samples
     * @param sigOff the offset of the scratch buffer
     */
    static void oversample(short[] sig12k8, int sOff, int lg, short[] sig16k, int oOff, short[] mem,
                           short[] signal, int sigOff) {
        System.arraycopy(mem, 0, signal, sigOff, 2 * NB_COEF_UP);
        System.arraycopy(sig12k8, sOff, signal, sigOff + 2 * NB_COEF_UP, lg);
        var groups = lg >> 2;
        var sigD = sigOff + NB_COEF_UP;
        var fir = WbTables.FIR_UP;
        var peak = 0;
        for (var i = 0; i < 2 * NB_COEF_UP + lg; i++) {
            peak = Math.max(peak, Math.abs(signal[sigOff + i]));
        }
        if (2L * peak * UP_MAGNITUDE <= UNSATURATED_SUM) {
            for (var g = 0; g < groups; g++) {
                var out = oOff + 5 * g;
                var x = sigD + 4 * g;
                sig16k[out] = signal[x];
                for (var r = 0; r < 4; r++) {
                    var from = x + r - 11;
                    var firOff = r * 24;
                    var sum = 0;
                    for (var k = 0; k < 24; k++) {
                        sum += signal[from + k] * fir[firOff + k];
                    }
                    sig16k[out + 1 + r] = round(shl32(sum << 1, 1));
                }
            }
        } else {
            for (var g = 0; g < groups; g++) {
                var out = oOff + 5 * g;
                var x = sigD + 4 * g;
                sig16k[out] = signal[x];
                for (var r = 0; r < 4; r++) {
                    var from = x + r - 11;
                    var firOff = r * 24;
                    var sum = 0L;
                    for (var k = 0; k < 24; k++) {
                        sum = saturate(sum + 2 * signal[from + k] * fir[firOff + k]);
                    }
                    sig16k[out + 1 + r] = round(shl32((int) sum, 1));
                }
            }
        }
        System.arraycopy(signal, sigOff + lg, mem, 0, 2 * NB_COEF_UP);
    }

    /**
     * Sharpens the innovation with the pitch lag in place.
     *
     * @param x the innovation
     * @param off the offset of the innovation
     * @param pitLag the pitch lag
     * @param sharp the sharpening factor
     * @param lSubfr the number of samples
     */
    static void pitShrp(short[] x, int off, int pitLag, int sharp, int lSubfr) {
        for (var i = pitLag; i < lSubfr; i++) {
            var sum = mac(x[off + i] << 16, x[off + i - pitLag], sharp);
            x[off + i] = round(sum);
        }
    }

    /**
     * Rescales a signal in place by {@code 2^exp}, with rounding.
     *
     * @implNote This implementation rounds through a 32-bit shift, as TS 26.173 specifies;
     * opencore adds the rounding offset with 16-bit saturation, which loses one step on samples
     * near the largest value.
     *
     * @param x the signal
     * @param off the offset of the signal
     * @param lg the number of samples
     * @param exp the scaling exponent
     */
    static void scaleSignal(short[] x, int off, int lg, int exp) {
        if (exp == 0) {
            return;
        }
        if (exp >= -16 && exp <= 15) {
            var shift = 16 + exp;
            for (var i = 0; i < lg; i++) {
                var scaled = (long) x[off + i] << shift;
                var clamped = scaled > MAX_32 ? MAX_32 : scaled < MIN_32 ? MIN_32 : scaled;
                var rounded = clamped + 0x8000;
                x[off + i] = (short) ((rounded > MAX_32 ? MAX_32 : rounded) >> 16);
            }
            return;
        }
        for (var i = 0; i < lg; i++) {
            x[off + i] = round(shl32(x[off + i] << 16, exp));
        }
    }

    /**
     * Scales the enhanced excitation so that its energy matches that of the input excitation.
     *
     * @implNote This implementation normalizes and shifts the energies with the TS 26.173
     * operators; opencore shifts a saturated energy left by 31 where TS 26.173 shifts it right by
     * one.
     *
     * @param sigIn the reference signal
     * @param inOff the offset of the reference signal
     * @param sigOut the signal scaled in place
     * @param outOff the offset of the scaled signal
     * @param lTrm the number of samples
     */
    static void agc2(short[] sigIn, int inOff, short[] sigOut, int outOff, int lTrm) {
        var temp = shr16(sigOut[outOff], 2);
        var s = mul(temp, temp);
        for (var i = 1; i < lTrm; i++) {
            temp = shr16(sigOut[outOff + i], 2);
            s = mac(s, temp, temp);
        }
        if (s == 0) {
            return;
        }
        var exp = sub16(normalize(s), 1);
        var gainOut = round(shl32(s, exp));
        temp = shr16(sigIn[inOff], 2);
        s = mul(temp, temp);
        for (var i = 1; i < lTrm; i++) {
            temp = shr16(sigIn[inOff + i], 2);
            s = mac(s, temp, temp);
        }
        short g0;
        if (s == 0) {
            g0 = 0;
        } else {
            var i = normalize(s);
            var gainIn = round(shl32(s, i));
            exp = sub16(exp, i);
            s = div(gainOut, gainIn);
            s = shl32(s, 7);
            s = shr32(s, exp);
            s = oneOvSqrt(s);
            g0 = round(shl32(s, 9));
        }
        for (var i = 0; i < lTrm; i++) {
            sigOut[outOff + i] = (short) (shl32(mul(sigOut[outOff + i], g0), 2) >> 16);
        }
    }

    /**
     * Computes the voicing factor of a subframe, from -1 (unvoiced) to 1 (voiced) in Q15.
     *
     * @implNote This implementation normalizes and shifts with the TS 26.173 operators;
     * opencore normalizes a zero gain to 27 and masks shift counts, which changes the factor of
     * subframes with a zero gain.
     *
     * @param exc the adaptive codebook excitation
     * @param excOff the offset of the excitation
     * @param qExc the scaling of the excitation
     * @param gainPit the pitch gain
     * @param code the innovation
     * @param codeOff the offset of the innovation
     * @param gainCode the innovation gain
     * @param lSubfr the number of samples
     * @return the voicing factor
     */
    static short voiceFactor(short[] exc, int excOff, int qExc, int gainPit, short[] code, int codeOff,
                             int gainCode, int lSubfr) {
        var packed = dotProduct12(exc, excOff, exc, excOff, lSubfr);
        var ener1 = (short) (value(packed) >> 16);
        var exp1 = sub16(exponent(packed), add16(qExc, qExc));
        var product = mul(gainPit, gainPit);
        var exp = (short) normalize(product);
        var tmp = (short) (shl32(product, exp) >> 16);
        ener1 = mult(ener1, tmp);
        exp1 = sub16(sub16(exp1, exp), 10);
        packed = dotProduct12(code, codeOff, code, codeOff, lSubfr);
        var ener2 = (short) (value(packed) >> 16);
        exp = (short) normS(gainCode);
        tmp = shl16(gainCode, exp);
        tmp = mult(tmp, tmp);
        ener2 = mult(ener2, tmp);
        var exp2 = sub16(exponent(packed), add16(exp, exp));
        var i = sub16(exp1, exp2);
        if (i >= 0) {
            ener1 = shr16(ener1, 1);
            ener2 = shr16(ener2, add16(i, 1));
        } else {
            ener1 = shr16(ener1, sub16(1, i));
            ener2 = shr16(ener2, 1);
        }
        tmp = sub16(ener1, ener2);
        ener1 = add16(add16(ener1, ener2), 1);
        if (tmp >= 0) {
            tmp = div(tmp, ener1);
        } else {
            tmp = negate(div(negate(tmp), ener1));
        }
        return tmp;
    }

    /**
     * Applies the adaptive phase dispersion to the innovation in place.
     *
     * @param gainCode the innovation gain
     * @param gainPit the pitch gain
     * @param code the innovation
     * @param codeOff the offset of the innovation
     * @param mode the dispersion mode: 0 for 6.60, 1 for 8.85 and 2 for the higher rates
     * @param dispMem the 8-value dispersion memory: the previous state, the previous gain and
     * the last 6 pitch gains
     * @param scratch a scratch buffer of 128 samples
     * @param sOff the offset of the scratch buffer
     */
    static void phaseDispersion(int gainCode, int gainPit, short[] code, int codeOff, int mode, short[] dispMem,
                                short[] scratch, int sOff) {
        gainCode = (short) gainCode;
        gainPit = (short) gainPit;
        java.util.Arrays.fill(scratch, sOff, sOff + 128, (short) 0);
        int state;
        if (gainPit < 9830) {
            state = 0;
        } else if (gainPit < 14746) {
            state = 1;
        } else {
            state = 2;
        }
        for (var i = 5; i > 0; i--) {
            dispMem[2 + i] = dispMem[2 + i - 1];
        }
        dispMem[2] = (short) gainPit;
        if (sub16(gainCode, dispMem[1]) > shl16(dispMem[1], 1)) {
            if (state < 2) {
                state++;
            }
        } else {
            var j = 0;
            for (var i = 0; i < 6; i++) {
                if (dispMem[2 + i] < 9830) {
                    j++;
                }
            }
            if (j > 2) {
                state = 0;
            }
            if (state > dispMem[0] + 1) {
                state--;
            }
        }
        dispMem[1] = (short) gainCode;
        dispMem[0] = (short) state;
        state += mode;
        if (state == 0 || state == 1) {
            var impulse = state == 0 ? WbTables.PH_IMP_LOW : WbTables.PH_IMP_MID;
            for (var i = 0; i < 64; i++) {
                var c = code[codeOff + i];
                if (c != 0) {
                    for (var j = 0; j < 64; j++) {
                        scratch[sOff + i + j] = add16(scratch[sOff + i + j], multR(c, impulse[j]));
                    }
                }
            }
        }
        if (state < 2) {
            for (var i = 0; i < 64; i++) {
                code[codeOff + i] = add16(scratch[sOff + i], scratch[sOff + i + 64]);
            }
        }
    }

    /**
     * Weights LP coefficients: {@code ap[i] = a[i] * gamma^i}.
     *
     * @param a the coefficients
     * @param aOff the offset of the coefficients
     * @param ap the weighted coefficients
     * @param apOff the offset of the weighted coefficients
     * @param gamma the weighting factor
     * @param m the filter order
     */
    static void weightLpc(short[] a, int aOff, short[] ap, int apOff, int gamma, int m) {
        ap[apOff] = a[aOff];
        var fac = (short) gamma;
        int i;
        for (i = 1; i < m; i++) {
            ap[apOff + i] = round(mul(a[aOff + i], fac));
            fac = round(mul(fac, gamma));
        }
        ap[apOff + i] = round(mul(a[aOff + i], fac));
    }
}
