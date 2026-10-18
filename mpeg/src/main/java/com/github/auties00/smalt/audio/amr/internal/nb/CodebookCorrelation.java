/* Ports opencore-amr 0.1.6 (convolve.cpp, cor_h.cpp, cor_h_x.cpp, cor_h_x2.cpp, set_sign.cpp). Copyright
   PacketVideo; Apache License 2.0. Modified for Smalt. */
package com.github.auties00.smalt.audio.amr.internal.nb;

import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.fxpMac;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.lAbs;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.lMac;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.lShl;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.negate;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.normL;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.round;
import static com.github.auties00.smalt.audio.amr.internal.nb.Constants.L_CODE;
import static com.github.auties00.smalt.audio.amr.internal.nb.Constants.NB_TRACK;
import static com.github.auties00.smalt.audio.amr.internal.nb.Constants.STEP;

/**
 * Computes the correlations the algebraic codebook searches of the AMR-NB encoder start from:
 * the convolution of the excitation with the impulse response, the correlation of the target
 * with the impulse response, the signed impulse response autocorrelation matrix, and the pulse
 * sign and position preselection.
 *
 * <p>The autocorrelation matrix is a flat {@code short[L_CODE * L_CODE]}, row major.
 */
final class CodebookCorrelation {
    /**
     * Prevents instantiation.
     */
    private CodebookCorrelation() {
        throw new AssertionError();
    }

    /**
     * Convolves a signal with an impulse response ({@code Convolve}):
     * {@code y[n] = (sum(x[i] * h[n - i], i = 0..n)) >> 12}.
     *
     * @param x the signal
     * @param xOffset the first sample
     * @param h the impulse response
     * @param hOffset the first coefficient
     * @param y receives the output
     * @param yOffset the first output
     * @param length the number of samples, even
     */
    static void convolve(short[] x, int xOffset, short[] h, int hOffset, short[] y, int yOffset, int length) {
        for (var n = 0; n < length; n++) {
            var s = 0;
            for (var i = 0; i <= n; i++) {
                s += x[xOffset + i] * h[hOffset + n - i];
            }
            y[yOffset + n] = (short) (s >> 12);
        }
    }

    /**
     * Computes the correlation between the target and the impulse response ({@code cor_h_x}),
     * normalized so that the sum of the track maxima fits.
     *
     * @param h the impulse response
     * @param hOffset the first coefficient
     * @param x the target
     * @param xOffset the first sample
     * @param dn receives the 40 correlations
     * @param sf the scaling shift
     * @param y32 scratch of 40 values
     */
    static void corHX(short[] h, int hOffset, short[] x, int xOffset, short[] dn, int sf, int[] y32) {
        var tot = 5;
        for (var k = 0; k < NB_TRACK; k++) {
            var max = 0;
            for (var i = k; i < L_CODE; i += STEP) {
                var s = 0;
                for (var j = i; j < L_CODE; j++) {
                    s += (x[xOffset + j] * h[hOffset + j - i]) << 1;
                }
                y32[i] = s;
                if (s < 0) {
                    s = -s;
                }
                if (s > max) {
                    max = s;
                }
            }
            tot += max >> 1;
        }
        var shift = (short) (normL(tot) - sf);
        for (var i = 0; i < L_CODE; i++) {
            var s = lShl(y32[i], shift);
            dn[i] = (short) ((s + 0x00008000) >> 16);
        }
    }

    /**
     * Computes the correlation between the target and the impulse response for the 10.2 and 12.2
     * kbit/s searches ({@code cor_h_x2}).
     *
     * @param h the impulse response
     * @param hOffset the first coefficient
     * @param x the target
     * @param dn receives the 40 correlations
     * @param sf the scaling shift
     * @param nbTrack the number of tracks
     * @param step the track step
     * @param y32 scratch of 40 values
     * @param overflow the saturation flag
     */
    static void corHX2(short[] h, int hOffset, short[] x, short[] dn, int sf, int nbTrack, int step, int[] y32,
                       Flag overflow) {
        var tot = 5;
        for (var k = 0; k < nbTrack; k++) {
            var max = 0;
            for (var i = k; i < L_CODE; i += step) {
                var s = 0;
                for (var j = i; j < L_CODE; j++) {
                    s = fxpMac(x[j], h[hOffset + j - i], s);
                }
                s = s << 1;
                y32[i] = s;
                s = lAbs(s);
                if (s > max) {
                    max = s;
                }
            }
            tot = tot + (max >> 1);
        }
        var shift = (short) (normL(tot) - sf);
        for (var i = 0; i < L_CODE; i++) {
            dn[i] = round(lShl(y32[i], shift), overflow);
        }
    }

    /**
     * Computes the impulse response autocorrelation matrix weighted by the pulse signs
     * ({@code cor_h}).
     *
     * @param h the impulse response
     * @param hOffset the first coefficient
     * @param sign the sign of every position, +-32767
     * @param rr receives the 40 x 40 matrix
     * @param h2 scratch of 40 values
     */
    static void corH(short[] h, int hOffset, short[] sign, short[] rr, short[] h2) {
        var s = 1;
        for (var i = 0; i < L_CODE; i++) {
            var tmp = h[hOffset + i];
            s = fxpMac(tmp, tmp, s);
        }
        s <<= 1;
        if ((s & 0x80000000) != 0) {
            for (var i = 0; i < L_CODE; i++) {
                h2[i] = (short) (h[hOffset + i] >> 1);
            }
        } else {
            s >>= 1;
            s = FixedMath.invSqrt(s);
            short dec;
            if (s < 0x00ffffff) {
                dec = (short) (((s >> 9) * 32440) >> 15);
            } else {
                dec = 32440;
            }
            for (var i = 0; i < L_CODE; i++) {
                h2[i] = (short) (fxpMac(h[hOffset + i], dec, 0x020) >> 6);
            }
        }
        s = 0;
        var rr1 = (L_CODE - 1) * L_CODE + L_CODE - 1;
        for (var i = 0; i < L_CODE; i++) {
            var tmp = h2[i];
            s = fxpMac(tmp, tmp, s);
            rr[rr1] = (short) ((s + 0x00004000) >> 15);
            rr1 -= L_CODE + 1;
        }
        for (var dec = 1; dec < L_CODE; dec += 2) {
            rr1 = (L_CODE - 1) * L_CODE + L_CODE - 1 - dec;
            var rr2 = (L_CODE - 1 - dec) * L_CODE + L_CODE - 1;
            var rr3 = (L_CODE - 1 - (dec + 1)) * L_CODE + L_CODE - 1;
            s = 0;
            var s2 = 0;
            var sign1 = L_CODE - 1;
            var sign2 = L_CODE - 1 - dec;
            var ph2 = 0;
            var ph = dec;
            for (var i = L_CODE - dec - 1; i != 0; i--) {
                s = fxpMac(h2[ph2], h2[ph++], s);
                s2 = fxpMac(h2[ph2++], h2[ph], s2);
                var tmp1 = (short) ((s + 0x00004000) >> 15);
                var tmp11 = (short) ((s2 + 0x00004000) >> 15);
                var tmp2 = (short) ((sign[sign1] * sign[sign2--]) >> 15);
                var tmp22 = (short) ((sign[sign1--] * sign[sign2]) >> 15);
                rr[rr2] = (short) ((tmp1 * tmp2) >> 15);
                rr[rr1--] = rr[rr2];
                rr[rr1] = (short) ((tmp11 * tmp22) >> 15);
                rr[rr3] = rr[rr1];
                rr1 -= L_CODE;
                rr2 -= L_CODE + 1;
                rr3 -= L_CODE + 1;
            }
            s = fxpMac(h2[ph2], h2[ph], s);
            var tmp1 = (short) ((s + 0x00004000) >> 15);
            var tmp2 = (short) ((sign[sign1] * sign[sign2]) >> 15);
            rr[rr1] = (short) ((tmp1 * tmp2) >> 15);
            rr[rr2] = rr[rr1];
        }
    }

    /**
     * Sets the pulse signs from the correlation and marks the positions the search skips
     * ({@code set_sign}): {@code dn} becomes its absolute value, and in {@code dn2} the
     * {@code 8 - n} weakest positions of every track are set to -1.
     *
     * @param dn the correlation, made absolute
     * @param sign receives the sign of every position, +-32767
     * @param dn2 receives the preselection
     * @param n the number of positions kept per track
     */
    static void setSign(short[] dn, short[] sign, short[] dn2, int n) {
        for (var i = L_CODE - 1; i >= 0; i--) {
            var val = dn[i];
            if (val >= 0) {
                sign[i] = 32767;
            } else {
                sign[i] = -32767;
                val = negate(val);
                dn[i] = val;
            }
            dn2[i] = val;
        }
        var pos = 0;
        for (var i = 0; i < NB_TRACK; i++) {
            for (var k = 0; k < 8 - n; k++) {
                short min = 0x7fff;
                for (var j = i; j < L_CODE; j += STEP) {
                    if (dn2[j] >= 0 && dn2[j] < min) {
                        min = dn2[j];
                        pos = j;
                    }
                }
                dn2[pos] = -1;
            }
        }
    }

    /**
     * Sets the pulse signs of the 10.2 and 12.2 kbit/s searches from a mix of the correlation and
     * the residual, and finds the strongest position of every track and the track order
     * ({@code set_sign12k2}).
     *
     * @param dn the correlation, made sign-adjusted
     * @param cn the long-term prediction residual
     * @param sign receives the sign of every position, +-32767
     * @param posMax receives the strongest position of every track
     * @param nbTrack the number of tracks
     * @param ipos receives the track order, {@code 2 * nbTrack} entries
     * @param step the track step
     * @param en scratch of 40 values
     * @param overflow the saturation flag
     */
    static void setSign12k2(short[] dn, short[] cn, short[] sign, short[] posMax, int nbTrack, short[] ipos, int step,
                            short[] en, Flag overflow) {
        var s = 256;
        var t = 256;
        for (var i = 0; i < L_CODE; i++) {
            var val = cn[i];
            s = lMac(s, val, val, overflow);
            val = dn[i];
            t += (val * val) << 1;
        }
        s = FixedMath.invSqrt(s);
        var kCn = (short) (lShl(s, (short) 5) >> 16);
        t = FixedMath.invSqrt(t);
        var kDn = (short) (t >> 11);
        for (var i = L_CODE - 1; i >= 0; i--) {
            var lTemp = (kCn * cn[i]) << 1;
            var val = dn[i];
            s = lMac(lTemp, kDn, val, overflow);
            lTemp = lShl(s, (short) 10);
            var cor = round(lTemp, overflow);
            if (cor >= 0) {
                sign[i] = 32767;
            } else {
                sign[i] = -32767;
                cor = negate(cor);
                dn[i] = negate(val);
            }
            en[i] = cor;
        }
        short maxOfAll = -1;
        short pos = 0;
        for (var i = 0; i < nbTrack; i++) {
            short max = -1;
            for (var j = i; j < L_CODE; j += step) {
                var cor = en[j];
                if (cor > max) {
                    max = cor;
                    pos = (short) j;
                }
            }
            posMax[i] = pos;
            if (max > maxOfAll) {
                maxOfAll = max;
                ipos[0] = (short) i;
            }
        }
        pos = ipos[0];
        ipos[nbTrack] = pos;
        for (var i = 1; i < nbTrack; i++) {
            pos++;
            if (pos >= nbTrack) {
                pos = 0;
            }
            ipos[i] = pos;
            ipos[i + nbTrack] = pos;
        }
    }
}
