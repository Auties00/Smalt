/* Ports opencore-amr 0.1.6 (convolve.cpp, cor_h.cpp, cor_h_x.cpp, cor_h_x2.cpp, set_sign.cpp). Copyright
   PacketVideo; Apache License 2.0. Modified for Smalt. */
package com.github.auties00.smalt.audio.amr.internal.nb;

import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.MAX_16;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.extractH;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.fxpMac;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.lAbs;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.lAdd;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.lMac;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.lMult;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.lShl;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.mult;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.negate;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.normL;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.round;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.sub;
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
     * The largest sum of absolute products a correlation accumulates without any doubled partial sum
     * leaving the 32-bit range, so that the saturating accumulation of the reference is exact in plain
     * arithmetic.
     */
    private static final long NO_SATURATION = 0x3FFFFFFFL;

    /**
     * Prevents instantiation.
     */
    private CodebookCorrelation() {
        throw new AssertionError();
    }

    /**
     * Convolves a signal with an impulse response ({@code Convolve}):
     * {@code y[n] = (sum(x[i] * h[n - i], i = 0..n)) >> 12}, saturated as the 32-bit
     * accumulation of TS 26.073 saturates.
     *
     * @param x the signal
     * @param xOffset the first sample
     * @param h the impulse response
     * @param hOffset the first coefficient
     * @param y receives the output
     * @param yOffset the first output
     * @param length the number of samples, even
     * @param overflow the saturation flag
     */
    static void convolve(short[] x, int xOffset, short[] h, int hOffset, short[] y, int yOffset, int length,
                         Flag overflow) {
        for (var n = 0; n < length; n++) {
            var sum = 0L;
            var magnitude = 0L;
            for (var i = 0; i <= n; i++) {
                var product = x[xOffset + i] * h[hOffset + n - i];
                sum += product;
                magnitude += Math.abs(product);
            }
            int s;
            if (magnitude <= NO_SATURATION) {
                s = (int) sum << 1;
            } else {
                s = 0;
                for (var i = 0; i <= n; i++) {
                    s = lMac(s, x[xOffset + i], h[hOffset + n - i], overflow);
                }
            }
            y[yOffset + n] = extractH(lShl(s, (short) 3));
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
     * @param overflow the saturation flag
     */
    static void corHX(short[] h, int hOffset, short[] x, int xOffset, short[] dn, int sf, int[] y32, Flag overflow) {
        corHX2(h, hOffset, x, xOffset, dn, sf, NB_TRACK, STEP, y32, overflow);
    }

    /**
     * Computes the correlation between the target and the impulse response for the 10.2 and 12.2
     * kbit/s searches ({@code cor_h_x2}).
     *
     * @param h the impulse response
     * @param hOffset the first coefficient
     * @param x the target
     * @param xOffset the first sample
     * @param dn receives the 40 correlations
     * @param sf the scaling shift
     * @param nbTrack the number of tracks
     * @param step the track step
     * @param y32 scratch of 40 values
     * @param overflow the saturation flag
     */
    static void corHX2(short[] h, int hOffset, short[] x, int xOffset, short[] dn, int sf, int nbTrack, int step,
                       int[] y32, Flag overflow) {
        var tot = 5;
        for (var k = 0; k < nbTrack; k++) {
            var max = 0;
            for (var i = k; i < L_CODE; i += step) {
                var sum = 0L;
                var magnitude = 0L;
                for (var j = i; j < L_CODE; j++) {
                    var product = x[xOffset + j] * h[hOffset + j - i];
                    sum += product;
                    magnitude += Math.abs(product);
                }
                int s;
                if (magnitude <= NO_SATURATION) {
                    s = (int) sum << 1;
                } else {
                    s = 0;
                    for (var j = i; j < L_CODE; j++) {
                        s = lMac(s, x[xOffset + j], h[hOffset + j - i], overflow);
                    }
                }
                y32[i] = s;
                s = lAbs(s);
                if (s > max) {
                    max = s;
                }
            }
            tot = lAdd(tot, max >> 1, overflow);
        }
        var shift = sub(normL(tot), (short) sf, overflow);
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
     * @param overflow the saturation flag
     */
    static void corH(short[] h, int hOffset, short[] sign, short[] rr, short[] h2, Flag overflow) {
        var energy = 1L;
        for (var i = 0; i < L_CODE; i++) {
            var tmp = h[hOffset + i];
            energy += tmp * tmp;
        }
        // the saturated sum 2 + 2 * sum(h * h) of the reference
        var s = (int) Math.min(energy << 1, Integer.MAX_VALUE);
        if (extractH(s) == MAX_16) {
            for (var i = 0; i < L_CODE; i++) {
                h2[i] = (short) (h[hOffset + i] >> 1);
            }
        } else {
            s >>= 1;
            var k = extractH(lShl(FixedMath.invSqrt(s), (short) 7));
            k = mult(k, (short) 32440, overflow);
            for (var i = 0; i < L_CODE; i++) {
                h2[i] = round(lShl(lMult(h[hOffset + i], k, overflow), (short) 9), overflow);
            }
        }
        var squares = 0L;
        for (var i = 0; i < L_CODE; i++) {
            squares += h2[i] * h2[i];
        }
        if (squares << 1 > 0x7FFF7FFFL) {
            corHSaturated(sign, rr, h2, overflow);
            return;
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
     * Computes the matrix of {@link #corH} with the saturating 32-bit accumulation of TS 26.073,
     * for an impulse response energetic enough that a correlation may saturate.
     *
     * @param sign the sign of every position, +-32767
     * @param rr receives the 40 x 40 matrix
     * @param h2 the scaled impulse response
     * @param overflow the saturation flag
     */
    private static void corHSaturated(short[] sign, short[] rr, short[] h2, Flag overflow) {
        var s = 0;
        for (int k = 0, i = L_CODE - 1; k < L_CODE; k++, i--) {
            s = lMac(s, h2[k], h2[k], overflow);
            rr[i * L_CODE + i] = round(s, overflow);
        }
        for (var dec = 1; dec < L_CODE; dec++) {
            s = 0;
            var j = L_CODE - 1;
            var i = j - dec;
            for (var k = 0; k < L_CODE - dec; k++, i--, j--) {
                s = lMac(s, h2[k], h2[k + dec], overflow);
                rr[j * L_CODE + i] = mult(round(s, overflow), mult(sign[i], sign[j], overflow), overflow);
                rr[i * L_CODE + j] = rr[j * L_CODE + i];
            }
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
            t = lMac(t, val, val, overflow);
        }
        s = FixedMath.invSqrt(s);
        var kCn = extractH(lShl(s, (short) 5));
        t = FixedMath.invSqrt(t);
        var kDn = extractH(lShl(t, (short) 5));
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
