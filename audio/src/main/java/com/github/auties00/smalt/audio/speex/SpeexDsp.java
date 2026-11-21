/* Ports libspeex 1.2.1 (libspeex/filters.c, lsp.c, ltp.c, cb_search.c, quant_lsp.c, math_approx.h). Copyright
   2002-2008 Xiph.org Foundation, Jean-Marc Valin, 2006 David Rowe, 2005 Analog Devices; BSD 3-Clause License.
   Modified for Smalt. */
package com.github.auties00.smalt.audio.speex;

import static com.github.auties00.smalt.audio.speex.SpeexFixed.add16;
import static com.github.auties00.smalt.audio.speex.SpeexFixed.div32_16;
import static com.github.auties00.smalt.audio.speex.SpeexFixed.mac16_16;
import static com.github.auties00.smalt.audio.speex.SpeexFixed.mult16_16;
import static com.github.auties00.smalt.audio.speex.SpeexFixed.mult16_16_p14;
import static com.github.auties00.smalt.audio.speex.SpeexFixed.mult16_16_p15;
import static com.github.auties00.smalt.audio.speex.SpeexFixed.mult16_16_q13;
import static com.github.auties00.smalt.audio.speex.SpeexFixed.mult16_16_q14;
import static com.github.auties00.smalt.audio.speex.SpeexFixed.mult16_16_q15;
import static com.github.auties00.smalt.audio.speex.SpeexFixed.mult16_32_q14;
import static com.github.auties00.smalt.audio.speex.SpeexFixed.mult16_32_q15;
import static com.github.auties00.smalt.audio.speex.SpeexFixed.pdiv32;
import static com.github.auties00.smalt.audio.speex.SpeexFixed.pdiv32_16;
import static com.github.auties00.smalt.audio.speex.SpeexFixed.pshr16;
import static com.github.auties00.smalt.audio.speex.SpeexFixed.pshr32;
import static com.github.auties00.smalt.audio.speex.SpeexFixed.saturate;
import static com.github.auties00.smalt.audio.speex.SpeexFixed.shl16;
import static com.github.auties00.smalt.audio.speex.SpeexFixed.shl32;
import static com.github.auties00.smalt.audio.speex.SpeexFixed.sqrt;

/**
 * The signal processing of the Speex decoder: synthesis and high-pass filters, the quadrature mirror filter
 * synthesis, the perceptual enhancer, line spectral pair conversion and interpolation, and the unquantization of
 * line spectral pairs, pitch and innovation. Arrays are addressed as the reference addresses its pointers: an array
 * and the index its pointer stands at.
 *
 * <p>Arguments are not checked; values outside the documented ranges give unspecified results.
 */
final class SpeexDsp {
    static final int SIG_SHIFT = 14;
    static final int LPC_SHIFT = 13;
    static final int LSP_SHIFT = 13;
    static final int GAIN_SHIFT = 6;
    static final int LPC_SCALING = 8192;
    static final int GAIN_SCALING = 64;

    static final int HIGHPASS_NARROWBAND = 0;
    static final int HIGHPASS_WIDEBAND = 2;
    static final int HIGHPASS_OUTPUT = 1;

    private static final short[][] POLES = {{16384, -31313, 14991}, {16384, -31569, 15249}, {16384, -31677, 15328},
            {16384, -32313, 15947}, {16384, -22446, 6537}};

    private static final short[][] ZEROS = {{15672, -31344, 15672}, {15802, -31601, 15802}, {15847, -31694, 15847},
            {16162, -32322, 16162}, {14418, -28836, 14418}};

    private static final short[][] SHIFT_FILTER = {{-390, 1540, -4993, 20123, 20123, -4993, 1540},
            {-1064, 2817, -6694, 31589, 6837, -990, -209},
            {-209, -990, 6837, 31589, -6694, 2817, -1064}};

    private SpeexDsp() {
    }

    /**
     * Generates a pseudo-random number of a standard deviation ({@code speex_rand}).
     *
     * @param std the standard deviation, 16 bits
     * @param seed the generator state, updated
     * @return the number, 16 bits
     */
    static int random(int std, int[] seed) {
        seed[0] = 1664525 * seed[0] + 1013904223;
        var res = mult16_16((short) (seed[0] >>> 16), std);
        return (short) pshr32(res - (res >> 3), 14);
    }

    /**
     * Applies bandwidth expansion to an LPC filter ({@code bw_lpc}); the arrays may be the same.
     */
    static void bandwidthExpand(int gamma, short[] in, short[] out, int order) {
        var tmp = (short) gamma;
        for (var i = 0; i < order; i++) {
            out[i] = (short) mult16_16_p15(tmp, in[i]);
            tmp = (short) mult16_16_p15(tmp, gamma);
        }
    }

    /**
     * Clamps 32-bit values to a range ({@code sanitize_values32}).
     */
    static void sanitize(int[] vec, int offset, int min, int max, int length) {
        for (var i = offset; i < offset + length; i++) {
            if (vec[i] < min) {
                vec[i] = min;
            } else if (vec[i] > max) {
                vec[i] = max;
            }
        }
    }

    /**
     * Runs the decoder's output high-pass filter in place ({@code highpass}).
     */
    static void highpass(short[] x, int offset, int length, int filter, int[] mem) {
        if (filter > 4) {
            filter = 4;
        }
        var den = POLES[filter];
        var num = ZEROS[filter];
        for (var i = offset; i < offset + length; i++) {
            var xi = x[i];
            var vout = mult16_16(num[0], xi) + mem[0];
            var yi = (short) saturate(pshr32(vout, 14), 32767);
            mem[0] = mac16_16(mem[1], num[1], xi) + shl32(mult16_32_q15(-den[1], vout), 1);
            mem[1] = mult16_16(num[2], xi) + shl32(mult16_32_q15(-den[2], vout), 1);
            x[i] = yi;
        }
    }

    /**
     * Scales a signal ({@code signal_mul}); the arrays may be the same.
     */
    static void signalMultiply(int[] x, int xOffset, int[] y, int yOffset, int scale, int length) {
        for (var i = 0; i < length; i++) {
            y[yOffset + i] = shl32(mult16_32_q14((short) (x[xOffset + i] >> 7), scale), 7);
        }
    }

    /**
     * {@return the root mean square of a 16-bit signal whose length is a multiple of 4 ({@code compute_rms16})}
     */
    static int rms16(short[] x, int offset, int length) {
        var max = 10;
        for (var i = offset; i < offset + length; i++) {
            var tmp = Math.abs(x[i]);
            if (tmp > max) {
                max = (short) tmp;
            }
        }
        if (max > 16383) {
            var sum = 0;
            for (var i = offset; i < offset + length; i += 4) {
                var sum2 = 0;
                for (var k = 0; k < 4; k++) {
                    var v = x[i + k] >> 1;
                    sum2 = mac16_16(sum2, v, v);
                }
                sum += sum2 >> 6;
            }
            return shl16(sqrt(sum / length), 4);
        }
        var sum = 0;
        var shift = 0;
        if (max < 8192) {
            shift = 1;
        }
        if (max < 4096) {
            shift = 2;
        }
        if (max < 2048) {
            shift = 3;
        }
        for (var i = offset; i < offset + length; i += 4) {
            var sum2 = 0;
            for (var k = 0; k < 4; k++) {
                var v = shl16(x[i + k], shift);
                sum2 = mac16_16(sum2, v, v);
            }
            sum += sum2 >> 6;
        }
        return shl16(sqrt(sum / length), 3 - shift);
    }

    /**
     * Filters a signal through an all-pole synthesis filter with memory ({@code iir_mem16}); the input and output
     * may be the same.
     */
    static void iir(short[] x, int xOffset, short[] den, short[] y, int yOffset, int length, int order, int[] mem,
                    int memOffset) {
        for (var i = 0; i < length; i++) {
            var yi = (short) saturate(x[xOffset + i] + pshr32(mem[memOffset], LPC_SHIFT), 32767);
            var nyi = (short) -yi;
            for (var j = 0; j < order - 1; j++) {
                mem[memOffset + j] = mac16_16(mem[memOffset + j + 1], den[j], nyi);
            }
            mem[memOffset + order - 1] = mult16_16(den[order - 1], nyi);
            y[yOffset + i] = yi;
        }
    }

    /**
     * {@return the scaled inner product of two signals whose length is a multiple of 4 ({@code inner_prod})}
     */
    static int innerProduct(short[] x, int xOffset, short[] y, int yOffset, int length) {
        var sum = 0;
        for (var i = 0; i < length; i += 4) {
            var part = 0;
            part = mac16_16(part, x[xOffset + i], y[yOffset + i]);
            part = mac16_16(part, x[xOffset + i + 1], y[yOffset + i + 1]);
            part = mac16_16(part, x[xOffset + i + 2], y[yOffset + i + 2]);
            part = mac16_16(part, x[xOffset + i + 3], y[yOffset + i + 3]);
            sum += part >> 6;
        }
        return sum;
    }

    /**
     * Synthesizes the full band from the low and high band halves ({@code qmf_synth}).
     *
     * @param x1 the low band and the output, {@code n / 2} samples from {@code x1Offset} (the output covers
     * {@code n} samples from {@code yOffset})
     */
    static void qmfSynthesis(short[] x1, int x1Offset, short[] x2, int x2Offset, short[] a, short[] y, int yOffset,
                             int n, int m, short[] mem1, short[] mem2, short[] xx1, short[] xx2) {
        var m2 = m >> 1;
        var n2 = n >> 1;
        for (var i = 0; i < n2; i++) {
            xx1[i] = x1[x1Offset + n2 - 1 - i];
        }
        for (var i = 0; i < m2; i++) {
            xx1[n2 + i] = mem1[2 * i + 1];
        }
        for (var i = 0; i < n2; i++) {
            xx2[i] = x2[x2Offset + n2 - 1 - i];
        }
        for (var i = 0; i < m2; i++) {
            xx2[n2 + i] = mem2[2 * i + 1];
        }
        for (var i = 0; i < n2; i += 2) {
            var y0 = 0;
            var y1 = 0;
            var y2 = 0;
            var y3 = 0;
            var x10 = xx1[n2 - 2 - i];
            var x20 = xx2[n2 - 2 - i];
            for (var j = 0; j < m2; j += 2) {
                var a0 = a[2 * j];
                var a1 = a[2 * j + 1];
                var x11 = xx1[n2 - 1 + j - i];
                var x21 = xx2[n2 - 1 + j - i];
                y0 = mac16_16(mac16_16(y0, a0, x11), -a0, x21);
                y1 = mac16_16(mac16_16(y1, a1, x11), a1, x21);
                y2 = mac16_16(mac16_16(y2, a0, x10), -a0, x20);
                y3 = mac16_16(mac16_16(y3, a1, x10), a1, x20);
                a0 = a[2 * j + 2];
                a1 = a[2 * j + 3];
                x10 = xx1[n2 + j - i];
                x20 = xx2[n2 + j - i];
                y0 = mac16_16(mac16_16(y0, a0, x10), -a0, x20);
                y1 = mac16_16(mac16_16(y1, a1, x10), a1, x20);
                y2 = mac16_16(mac16_16(y2, a0, x11), -a0, x21);
                y3 = mac16_16(mac16_16(y3, a1, x11), a1, x21);
            }
            y[yOffset + 2 * i] = (short) saturate(pshr32(y0, 15), 32767);
            y[yOffset + 2 * i + 1] = (short) saturate(pshr32(y1, 15), 32767);
            y[yOffset + 2 * i + 2] = (short) saturate(pshr32(y2, 15), 32767);
            y[yOffset + 2 * i + 3] = (short) saturate(pshr32(y3, 15), 32767);
        }
        for (var i = 0; i < m2; i++) {
            mem1[2 * i + 1] = xx1[i];
        }
        for (var i = 0; i < m2; i++) {
            mem2[2 * i + 1] = xx2[i];
        }
    }

    /**
     * Interpolates the excitation at a fractional pitch near {@code pitch} ({@code interp_pitch}).
     *
     * @return the pitch used
     */
    private static int interpolatePitch(short[] exc, int excOffset, short[] interp, int interpOffset, int pitch,
                                        int length, int[][] corr) {
        for (var i = 0; i < 7; i++) {
            corr[0][i] = innerProduct(exc, excOffset, exc, excOffset - pitch - 3 + i, length);
        }
        for (var i = 0; i < 3; i++) {
            for (var j = 0; j < 7; j++) {
                var i1 = Math.max(3 - j, 0);
                var i2 = Math.min(10 - j, 7);
                var tmp = 0;
                for (var k = i1; k < i2; k++) {
                    tmp += mult16_32_q15(SHIFT_FILTER[i][k], corr[0][j + k - 3]);
                }
                corr[i + 1][j] = tmp;
            }
        }
        var maxi = 0;
        var maxj = 0;
        var maxcorr = corr[0][0];
        for (var i = 0; i < 4; i++) {
            for (var j = 0; j < 7; j++) {
                if (corr[i][j] > maxcorr) {
                    maxcorr = corr[i][j];
                    maxi = i;
                    maxj = j;
                }
            }
        }
        for (var i = 0; i < length; i++) {
            var tmp = 0;
            if (maxi > 0) {
                for (var k = 0; k < 7; k++) {
                    tmp += mult16_16(exc[excOffset + i - (pitch - maxj + 3) + k - 3], SHIFT_FILTER[maxi - 1][k]);
                }
            } else {
                tmp = shl32(exc[excOffset + i - (pitch - maxj + 3)], 15);
            }
            interp[interpOffset + i] = (short) pshr32(tmp, 15);
        }
        return pitch - maxj + 3;
    }

    /**
     * The scratch arrays of {@link #multicomb}.
     */
    static final class CombScratch {
        final short[] iexc = new short[160];

        final int[][] corr = new int[4][7];
    }

    /**
     * Applies the perceptual enhancer's comb filter to two subframes of excitation ({@code multicomb}).
     */
    static void multicomb(short[] exc, int excOffset, short[] newExc, int newOffset, int nsf, int pitch, int maxPitch,
                          int combGain, CombScratch scratch) {
        var iexc = scratch.iexc;
        var corrPitch = pitch;
        interpolatePitch(exc, excOffset, iexc, 0, corrPitch, 80, scratch.corr);
        if (corrPitch > maxPitch) {
            interpolatePitch(exc, excOffset, iexc, nsf, 2 * corrPitch, 80, scratch.corr);
        } else {
            interpolatePitch(exc, excOffset, iexc, nsf, -corrPitch, 80, scratch.corr);
        }
        var scaledown = false;
        for (var i = 0; i < nsf; i++) {
            if (Math.abs(exc[excOffset + i]) > 16383) {
                scaledown = true;
                break;
            }
        }
        if (scaledown) {
            for (var i = 0; i < nsf; i++) {
                exc[excOffset + i] = (short) (exc[excOffset + i] >> 1);
            }
            for (var i = 0; i < 2 * nsf; i++) {
                iexc[i] = (short) (iexc[i] >> 1);
            }
        }
        var iexc0Mag = sqrt(1000 + innerProduct(iexc, 0, iexc, 0, nsf));
        var iexc1Mag = sqrt(1000 + innerProduct(iexc, nsf, iexc, nsf, nsf));
        var excMag = sqrt(1 + innerProduct(exc, excOffset, exc, excOffset, nsf));
        var corr0 = innerProduct(iexc, 0, exc, excOffset, nsf);
        if (corr0 < 0) {
            corr0 = 0;
        }
        var corr1 = innerProduct(iexc, nsf, exc, excOffset, nsf);
        if (corr1 < 0) {
            corr1 = 0;
        }
        if (shl32(iexc0Mag, 6) < excMag) {
            iexc0Mag = add16(1, pshr16(excMag, 6));
        }
        if (shl32(iexc1Mag, 6) < excMag) {
            iexc1Mag = add16(1, pshr16(excMag, 6));
        }
        int pgain1;
        if (corr0 > mult16_16(iexc0Mag, excMag)) {
            pgain1 = SpeexFixed.q16(1., 14);
        } else {
            pgain1 = pdiv32_16(shl32(pdiv32(corr0, excMag), 14), iexc0Mag);
        }
        int pgain2;
        if (corr1 > mult16_16(iexc1Mag, excMag)) {
            pgain2 = SpeexFixed.q16(1., 14);
        } else {
            pgain2 = pdiv32_16(shl32(pdiv32(corr1, excMag), 14), iexc1Mag);
        }
        var gg1 = pdiv32_16(shl32(excMag, 8), iexc0Mag);
        var gg2 = pdiv32_16(shl32(excMag, 8), iexc1Mag);
        int c1;
        int c2;
        if (combGain > 0) {
            c1 = (short) (mult16_16_q15(Q_0_4, combGain) + Q_0_07);
            c2 = (short) (Q_0_5 + mult16_16_q14(Q_1_72, (c1 - Q_0_07)));
        } else {
            c1 = c2 = 0;
        }
        var g1 = (short) (32767 - mult16_16_q13(mult16_16_q15(c2, pgain1), pgain1));
        var g2 = (short) (32767 - mult16_16_q13(mult16_16_q15(c2, pgain2), pgain2));
        if (g1 < c1) {
            g1 = (short) c1;
        }
        if (g2 < c1) {
            g2 = (short) c1;
        }
        g1 = (short) pdiv32_16(shl32(c1, 14), g1);
        g2 = (short) pdiv32_16(shl32(c1, 14), g2);
        int gain0;
        int gain1;
        if (corrPitch > maxPitch) {
            gain0 = (short) mult16_16_q15(Q_0_7, mult16_16_q14(g1, gg1));
            gain1 = (short) mult16_16_q15(Q_0_3, mult16_16_q14(g2, gg2));
        } else {
            gain0 = (short) mult16_16_q15(Q_0_6, mult16_16_q14(g1, gg1));
            gain1 = (short) mult16_16_q15(Q_0_6, mult16_16_q14(g2, gg2));
        }
        for (var i = 0; i < nsf; i++) {
            newExc[newOffset + i] = (short) add16(exc[excOffset + i],
                    (short) pshr32(mult16_16(gain0, iexc[i]) + mult16_16(gain1, iexc[i + nsf]), 8));
        }
        var newEnergy = rms16(newExc, newOffset, nsf);
        var oldEnergy = rms16(exc, excOffset, nsf);
        if (oldEnergy < 1) {
            oldEnergy = 1;
        }
        if (newEnergy < 1) {
            newEnergy = 1;
        }
        if (oldEnergy > newEnergy) {
            oldEnergy = newEnergy;
        }
        var ngain = pdiv32_16(shl32(oldEnergy, 14), newEnergy);
        for (var i = 0; i < nsf; i++) {
            newExc[newOffset + i] = (short) mult16_16_q14(ngain, newExc[newOffset + i]);
        }
        if (scaledown) {
            for (var i = 0; i < nsf; i++) {
                exc[excOffset + i] = (short) shl16(exc[excOffset + i], 1);
            }
            for (var i = 0; i < nsf; i++) {
                newExc[newOffset + i] = (short) shl16(saturate(newExc[newOffset + i], 16383), 1);
            }
        }
    }

    private static final short Q_0_4 = SpeexFixed.q16(.4, 15);
    private static final short Q_0_07 = SpeexFixed.q16(.07, 15);
    private static final short Q_0_5 = SpeexFixed.q16(.5, 15);
    private static final short Q_1_72 = SpeexFixed.q16(1.72, 14);
    private static final short Q_0_7 = SpeexFixed.q16(.7, 15);
    private static final short Q_0_3 = SpeexFixed.q16(.3, 15);
    private static final short Q_0_6 = SpeexFixed.q16(.6, 15);

    /**
     * The scratch arrays of {@link #lspToLpc}.
     */
    static final class LpcScratch {
        final int[][] xp = new int[6][13];

        final int[][] xq = new int[6][13];

        final short[] freqn = new short[10];
    }

    /**
     * Converts line spectral pairs to LPC coefficients ({@code lsp_to_lpc}).
     */
    static void lspToLpc(short[] freq, short[] ak, int order, LpcScratch scratch) {
        var m = order >> 1;
        var xp = scratch.xp;
        var xq = scratch.xq;
        var freqn = scratch.freqn;
        for (var i = 0; i <= m; i++) {
            java.util.Arrays.fill(xp[i], 0);
            java.util.Arrays.fill(xq[i], 0);
        }
        for (var i = 0; i < order; i++) {
            freqn[i] = (short) shl16(SpeexFixed.cos(freq[i]), 2);
        }
        var xin = 1 << 20;
        for (var i = 0; i <= m; i++) {
            xp[i][1] = 0;
            xp[i][2] = xin;
            xp[i][2 + 2 * i] = xin;
            xq[i][1] = 0;
            xq[i][2] = xin;
            xq[i][2 + 2 * i] = xin;
        }
        xp[1][3] = -mult16_32_q14(freqn[0], xp[0][2]);
        xq[1][3] = -mult16_32_q14(freqn[1], xq[0][2]);
        var xout1 = 0;
        var xout2 = 0;
        for (var i = 1; i < m; i++) {
            int j;
            for (j = 1; j < 2 * (i + 1) - 1; j++) {
                var mult = mult16_32_q14(freqn[2 * i], xp[i][j + 1]);
                xp[i + 1][j + 2] = xp[i][j + 2] - mult + xp[i][j];
                mult = mult16_32_q14(freqn[2 * i + 1], xq[i][j + 1]);
                xq[i + 1][j + 2] = xq[i][j + 2] - mult + xq[i][j];
            }
            var mult = mult16_32_q14(freqn[2 * i], xp[i][j + 1]);
            xp[i + 1][j + 2] = xp[i][j] - mult;
            mult = mult16_32_q14(freqn[2 * i + 1], xq[i][j + 1]);
            xq[i + 1][j + 2] = xq[i][j] - mult;
        }
        for (var j = 1; j <= order; j++) {
            var a = pshr32(xp[m][j + 2] + xout1 + xq[m][j + 2] - xout2, 21 - 13);
            xout1 = xp[m][j + 2];
            xout2 = xq[m][j + 2];
            if (a < -32767) {
                a = -32767;
            }
            if (a > 32767) {
                a = 32767;
            }
            ak[j - 1] = (short) a;
        }
    }

    /**
     * Interpolates line spectral pairs between two frames and enforces their minimum distance
     * ({@code lsp_interpolate}).
     */
    static void lspInterpolate(short[] oldLsp, short[] newLsp, short[] lsp, int length, int subframe,
                               int subframes, int margin) {
        var m = (short) margin;
        var m2 = (short) (25736 - margin);
        var tmp = (short) div32_16(shl32(1 + subframe, 14), subframes);
        var tmp2 = (short) (16384 - tmp);
        for (var i = 0; i < length; i++) {
            lsp[i] = (short) (mult16_16_p14(tmp2, oldLsp[i]) + mult16_16_p14(tmp, newLsp[i]));
        }
        if (lsp[0] < m) {
            lsp[0] = m;
        }
        if (lsp[length - 1] > m2) {
            lsp[length - 1] = m2;
        }
        for (var i = 1; i < length - 1; i++) {
            if (lsp[i] < lsp[i - 1] + m) {
                lsp[i] = (short) (lsp[i - 1] + m);
            }
            if (lsp[i] > lsp[i + 1] - m) {
                lsp[i] = (short) ((lsp[i] >> 1) + ((lsp[i + 1] - m) >> 1));
            }
        }
    }

    private static short lspDiv256(int x) {
        return (short) shl16((short) x, 5);
    }

    private static short lspDiv512(int x) {
        return (short) shl16((short) x, 4);
    }

    private static short lspDiv1024(int x) {
        return (short) shl16((short) x, 3);
    }

    /**
     * Unquantizes the narrowband line spectral pairs of the higher rates ({@code lsp_unquant_nb}).
     */
    static void lspUnquantNb(short[] lsp, int order, SpeexBits bits) {
        for (var i = 0; i < order; i++) {
            lsp[i] = (short) shl16(i + 1, 11);
        }
        var id = bits.unsigned(6);
        for (var i = 0; i < 10; i++) {
            lsp[i] = (short) (lsp[i] + lspDiv256(SpeexTables.LSP_NB[id * 10 + i]));
        }
        id = bits.unsigned(6);
        for (var i = 0; i < 5; i++) {
            lsp[i] = (short) add16(lsp[i], lspDiv512(SpeexTables.LSP_NB_LOW1[id * 5 + i]));
        }
        id = bits.unsigned(6);
        for (var i = 0; i < 5; i++) {
            lsp[i] = (short) (lsp[i] + lspDiv1024(SpeexTables.LSP_NB_LOW2[id * 5 + i]));
        }
        id = bits.unsigned(6);
        for (var i = 0; i < 5; i++) {
            lsp[i + 5] = (short) (lsp[i + 5] + lspDiv512(SpeexTables.LSP_NB_HIGH1[id * 5 + i]));
        }
        id = bits.unsigned(6);
        for (var i = 0; i < 5; i++) {
            lsp[i + 5] = (short) (lsp[i + 5] + lspDiv1024(SpeexTables.LSP_NB_HIGH2[id * 5 + i]));
        }
    }

    /**
     * Unquantizes the narrowband line spectral pairs of the lower rates ({@code lsp_unquant_lbr}).
     */
    static void lspUnquantLbr(short[] lsp, int order, SpeexBits bits) {
        for (var i = 0; i < order; i++) {
            lsp[i] = (short) shl16(i + 1, 11);
        }
        var id = bits.unsigned(6);
        for (var i = 0; i < 10; i++) {
            lsp[i] = (short) (lsp[i] + lspDiv256(SpeexTables.LSP_NB[id * 10 + i]));
        }
        id = bits.unsigned(6);
        for (var i = 0; i < 5; i++) {
            lsp[i] = (short) (lsp[i] + lspDiv512(SpeexTables.LSP_NB_LOW1[id * 5 + i]));
        }
        id = bits.unsigned(6);
        for (var i = 0; i < 5; i++) {
            lsp[i + 5] = (short) (lsp[i + 5] + lspDiv512(SpeexTables.LSP_NB_HIGH1[id * 5 + i]));
        }
    }

    /**
     * Unquantizes the high band line spectral pairs ({@code lsp_unquant_high}).
     */
    static void lspUnquantHigh(short[] lsp, int order, SpeexBits bits) {
        for (var i = 0; i < order; i++) {
            lsp[i] = (short) add16(SpeexFixed.mult16_16_16(i, 2560), 6144);
        }
        var id = bits.unsigned(6);
        for (var i = 0; i < order; i++) {
            lsp[i] = (short) (lsp[i] + lspDiv256(SpeexTables.LSP_HIGH[id * order + i]));
        }
        id = bits.unsigned(6);
        for (var i = 0; i < order; i++) {
            lsp[i] = (short) (lsp[i] + lspDiv512(SpeexTables.LSP_HIGH2[id * order + i]));
        }
    }

    /**
     * {@return the one-tap equivalent of three pitch gains ({@code gain_3tap_to_1tap})}
     */
    static int gain3TapTo1Tap(short[] g) {
        return Math.abs(g[1]) + (g[0] > 0 ? g[0] : -(g[0] >> 1)) + (g[2] > 0 ? g[2] : -(g[2] >> 1));
    }

    /**
     * Unquantizes a three-tap pitch predictor ({@code pitch_unquant_3tap}).
     *
     * @param result receives the pitch at index 0
     */
    static void pitchUnquant3Tap(short[] exc, int excOffset, int[] out, int start, SpeexModes.Ltp params, int nsf,
                                 int[] result, short[] gainValue, SpeexBits bits, int countLost, int subframeOffset,
                                 int lastPitchGain, short[] gain) {
        var pitch = bits.unsigned(params.pitchBits()) + start;
        var gainIndex = bits.unsigned(params.gainBits());
        var codebook = params.gains();
        gain[0] = (short) add16(32, codebook[gainIndex * 4]);
        gain[1] = (short) add16(32, codebook[gainIndex * 4 + 1]);
        gain[2] = (short) add16(32, codebook[gainIndex * 4 + 2]);
        if (countLost != 0 && pitch > subframeOffset) {
            var tmp = (short) (countLost < 4 ? lastPitchGain : lastPitchGain >> 1);
            if (tmp > 62) {
                tmp = 62;
            }
            var gainSum = (short) gain3TapTo1Tap(gain);
            if (gainSum > tmp) {
                var fact = (short) div32_16(shl32(tmp, 14), gainSum);
                for (var i = 0; i < 3; i++) {
                    gain[i] = (short) mult16_16_q14(fact, gain[i]);
                }
            }
        }
        result[0] = pitch;
        gainValue[0] = gain[0];
        gainValue[1] = gain[1];
        gainValue[2] = gain[2];
        gain[0] = (short) shl16(gain[0], 7);
        gain[1] = (short) shl16(gain[1], 7);
        gain[2] = (short) shl16(gain[2], 7);
        java.util.Arrays.fill(out, 0, nsf, 0);
        for (var i = 0; i < 3; i++) {
            var pp = pitch + 1 - i;
            var tmp1 = Math.min(nsf, pp);
            for (var j = 0; j < tmp1; j++) {
                out[j] = mac16_16(out[j], gain[2 - i], exc[excOffset + j - pp]);
            }
            var tmp3 = Math.min(nsf, pp + pitch);
            for (var j = tmp1; j < tmp3; j++) {
                out[j] = mac16_16(out[j], gain[2 - i], exc[excOffset + j - pp - pitch]);
            }
        }
    }

    /**
     * Applies a forced pitch and gain ({@code forced_pitch_unquant}).
     */
    static void forcedPitchUnquant(short[] exc, int excOffset, int[] out, int start, int pitchCoef, int nsf,
                                   int[] result, short[] gainValue) {
        if (pitchCoef > 63) {
            pitchCoef = 63;
        }
        for (var i = 0; i < nsf; i++) {
            out[i] = mult16_16(exc[excOffset + i - start], shl16(pitchCoef, 7));
            exc[excOffset + i] = (short) pshr32(out[i], 13);
        }
        result[0] = start;
        gainValue[0] = gainValue[2] = 0;
        gainValue[1] = (short) pitchCoef;
    }

    /**
     * Unquantizes an innovation of signed shape codewords ({@code split_cb_shape_sign_unquant}).
     */
    static void splitCodebookUnquant(int[] exc, int offset, SpeexModes.SplitCodebook params, SpeexBits bits,
                                     int[] indices, int[] signs) {
        var size = params.subvectorSize();
        var count = params.subvectors();
        var shapes = params.shapes();
        for (var i = 0; i < count; i++) {
            signs[i] = params.hasSign() ? bits.unsigned(1) : 0;
            indices[i] = bits.unsigned(params.shapeBits());
        }
        for (var i = 0; i < count; i++) {
            for (var j = 0; j < size; j++) {
                var value = shl32(shapes[indices[i] * size + j], SIG_SHIFT - 5);
                exc[offset + size * i + j] = signs[i] != 0 ? -value : value;
            }
        }
    }

    /**
     * Fills an innovation with noise ({@code noise_codebook_unquant}).
     */
    static void noiseCodebookUnquant(int[] exc, int offset, int nsf, int[] seed) {
        for (var i = 0; i < nsf; i++) {
            exc[offset + i] = shl32(random(1, seed), SIG_SHIFT);
        }
    }

    /**
     * {@return the median of three values ({@code median3})}
     */
    static int median3(int a, int b, int c) {
        return a < b ? (b < c ? b : (a < c ? c : a)) : (c < b ? b : (c < a ? c : a));
    }
}
