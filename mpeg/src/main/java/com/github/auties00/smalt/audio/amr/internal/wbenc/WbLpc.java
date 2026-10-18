/* Ports vo-amrwbenc 0.1.3 (autocorr.c, lag_wind.c, levinson.c, az_isp.c, isp_az.c, isp_isf.c, int_lpc.c). Copyright
   VisualOn; Apache License 2.0. Modified for Smalt. */
package com.github.auties00.smalt.audio.amr.internal.wbenc;

import com.github.auties00.smalt.audio.amr.internal.wbcommon.WbCodecTables;

import static com.github.auties00.smalt.audio.amr.internal.wbenc.WbBasicOps.*;

/**
 * Implements the LP analysis of the AMR-WB encoder: the windowed autocorrelation, the lag
 * window, the Levinson-Durbin recursion, the conversions between LP coefficients, immittance
 * spectral pairs (ISPs) and immittance spectral frequencies (ISFs), and the interpolation of the
 * ISPs over the subframes.
 *
 * <p>An instance holds the scratch buffers of the analysis, so analyzing a frame allocates
 * nothing; an instance is not thread-safe. The LP order is 16 throughout.
 *
 * @implNote This implementation computes with the saturating arithmetic of the 3GPP TS 26.173 fixed-point reference.
 * Only the 12.8 kHz path of {@code Isp_Az} is ported: the encoder never converts the order-20 ISPs of the 16 kHz
 * decoder path.
 */
final class WbLpc {
    /**
     * The LP order.
     */
    private static final int M = 16;

    /**
     * Half the LP order.
     */
    private static final int NC = M / 2;

    /**
     * The number of samples of the analysis window.
     */
    private static final int L_WINDOW = 384;

    /**
     * The number of intervals of the Chebyshev root search grid.
     */
    private static final int GRID_POINTS = 100;

    /**
     * The windowed speech of the autocorrelation.
     */
    private final short[] windowed = new short[L_WINDOW];

    /**
     * The high halves of the predictor of the current Levinson order.
     */
    private final short[] ah = new short[M + 1];

    /**
     * The low halves of the predictor of the current Levinson order.
     */
    private final short[] al = new short[M + 1];

    /**
     * The high halves of the predictor of the next Levinson order.
     */
    private final short[] anh = new short[M + 1];

    /**
     * The low halves of the predictor of the next Levinson order.
     */
    private final short[] anl = new short[M + 1];

    /**
     * The symmetric polynomial of the ISP root search.
     */
    private final short[] chebF1 = new short[NC + 1];

    /**
     * The antisymmetric polynomial of the ISP root search.
     */
    private final short[] chebF2 = new short[NC];

    /**
     * The symmetric polynomial of the ISP to LP conversion.
     */
    private final int[] polF1 = new int[NC + 1];

    /**
     * The antisymmetric polynomial of the ISP to LP conversion.
     */
    private final int[] polF2 = new int[NC];

    /**
     * The interpolated ISPs of one subframe.
     */
    private final short[] interpolated = new short[M];

    /**
     * Creates the analysis with its scratch buffers.
     */
    WbLpc() {
    }

    /**
     * Computes the autocorrelation of the windowed speech in double precision
     * ({@code Autocorr}).
     *
     * @param x the speech, 384 samples
     * @param xOff the offset of the speech
     * @param rh the 17 high halves of the autocorrelation
     * @param rl the 17 low halves of the autocorrelation
     */
    void autocorr(short[] x, int xOff, short[] rh, short[] rl) {
        var y = windowed;
        var window = WbTables.HAMMING_WINDOW;
        for (var i = 0; i < L_WINDOW; i++) {
            y[i] = multR(x[xOff + i], window[i]);
        }
        var sum = 16 << 16;
        for (var i = 0; i < L_WINDOW; i++) {
            var tmp = lMult(y[i], y[i]);
            tmp = lShr(tmp, 8);
            sum = lAdd(sum, tmp);
        }
        var norm = normL(sum);
        var shift = sub(4, shr(norm, 1));
        if (shift < 0) {
            shift = 0;
        }
        for (var i = 0; i < L_WINDOW; i++) {
            y[i] = shrR(y[i], shift);
        }
        sum = 1;
        for (var i = 0; i < L_WINDOW; i++) {
            sum = lMac(sum, y[i], y[i]);
        }
        norm = normL(sum);
        sum = lShl(sum, norm);
        rh[0] = hi(sum);
        rl[0] = lo(sum);
        for (var i = 1; i <= M; i++) {
            sum = macForward(0, y, 0, y, i, L_WINDOW - i);
            sum = lShl(sum, norm);
            rh[i] = hi(sum);
            rl[i] = lo(sum);
        }
    }

    /**
     * Applies the lag window to the autocorrelation ({@code Lag_window}).
     *
     * @param rh the high halves of the autocorrelation, windowed in place
     * @param rl the low halves of the autocorrelation, windowed in place
     */
    static void lagWindow(short[] rh, short[] rl) {
        for (var i = 1; i <= M; i++) {
            var x = mpy32(rh[i], rl[i], WbTables.LAG_WINDOW_HIGH[i - 1], WbTables.LAG_WINDOW_LOW[i - 1]);
            rh[i] = hi(x);
            rl[i] = lo(x);
        }
    }

    /**
     * Computes the LP coefficients from the autocorrelation with the Levinson-Durbin recursion
     * ({@code Levinson}).
     *
     * <p>When the recursion becomes unstable, the coefficients and the first two reflection
     * coefficients of the previous frame are used.
     *
     * @param rh the high halves of the autocorrelation
     * @param rl the low halves of the autocorrelation
     * @param a the 17 LP coefficients in Q12
     * @param rc the reflection coefficients
     * @param mem the 18-value memory: the previous coefficients and two reflection coefficients
     */
    void levinson(short[] rh, short[] rl, short[] a, short[] rc, short[] mem) {
        var t1 = lComp(rh[1], rl[1]);
        var t2 = lAbs(t1);
        var t0 = div32(t2, rh[0], rl[0]);
        if (t1 > 0) {
            t0 = lNegate(t0);
        }
        var kh = hi(t0);
        var kl = lo(t0);
        rc[0] = kh;
        t0 = lShr(t0, 4);
        ah[1] = hi(t0);
        al[1] = lo(t0);
        t0 = mpy32(kh, kl, kh, kl);
        t0 = lAbs(t0);
        t0 = lSub(0x7fffffff, t0);
        t0 = mpy32(rh[0], rl[0], hi(t0), lo(t0));
        var alpExp = normL(t0);
        t0 = lShl(t0, alpExp);
        var alpH = hi(t0);
        var alpL = lo(t0);
        for (var i = 2; i <= M; i++) {
            t0 = 0;
            for (var j = 1; j < i; j++) {
                t0 = lAdd(t0, mpy32(rh[j], rl[j], ah[i - j], al[i - j]));
            }
            t0 = lShl(t0, 4);
            t1 = lComp(rh[i], rl[i]);
            t0 = lAdd(t0, t1);
            t1 = lAbs(t0);
            t2 = div32(t1, alpH, alpL);
            if (t0 > 0) {
                t2 = lNegate(t2);
            }
            t2 = lShl(t2, alpExp);
            kh = hi(t2);
            kl = lo(t2);
            rc[i - 1] = kh;
            if (absS(kh) > 32750) {
                a[0] = 4096;
                for (var j = 0; j < M; j++) {
                    a[j + 1] = mem[j];
                }
                rc[0] = mem[M];
                rc[1] = mem[M + 1];
                return;
            }
            for (var j = 1; j < i; j++) {
                t0 = mpy32(kh, kl, ah[i - j], al[i - j]);
                t0 = lAdd(t0, lComp(ah[j], al[j]));
                anh[j] = hi(t0);
                anl[j] = lo(t0);
            }
            t2 = lShr(t2, 4);
            anh[i] = hi(t2);
            anl[i] = lo(t2);
            t0 = mpy32(kh, kl, kh, kl);
            t0 = lAbs(t0);
            t0 = lSub(0x7fffffff, t0);
            t0 = mpy32(alpH, alpL, hi(t0), lo(t0));
            var j = normL(t0);
            t0 = lShl(t0, j);
            alpH = hi(t0);
            alpL = lo(t0);
            alpExp = add(alpExp, j);
            for (j = 1; j <= i; j++) {
                ah[j] = anh[j];
                al[j] = anl[j];
            }
        }
        a[0] = 4096;
        for (var i = 1; i <= M; i++) {
            t0 = lComp(ah[i], al[i]);
            var value = round(lShl(t0, 1));
            a[i] = value;
            mem[i - 1] = value;
        }
        mem[M] = rc[0];
        mem[M + 1] = rc[1];
    }

    /**
     * Converts LP coefficients to ISPs by searching the roots of the symmetric and antisymmetric
     * polynomials on a grid ({@code Az_isp}).
     *
     * <p>When fewer than 15 roots are found, the previous ISPs are kept.
     *
     * @param a the 17 LP coefficients in Q12
     * @param aOff the offset of the coefficients
     * @param isp the 16 ISPs in Q15
     * @param oldIsp the ISPs of the previous frame
     */
    void azIsp(short[] a, int aOff, short[] isp, short[] oldIsp) {
        var f1 = chebF1;
        var f2 = chebF2;
        for (var i = 0; i < NC; i++) {
            var t0 = lMult(a[aOff + i], 16384);
            f1[i] = round(lMac(t0, a[aOff + M - i], 16384));
            f2[i] = round(lMsu(t0, a[aOff + M - i], 16384));
        }
        f1[NC] = a[aOff + NC];
        for (var i = 2; i < NC; i++) {
            f2[i] = add(f2[i], f2[i - 2]);
        }
        var nf = 0;
        var ip = 0;
        var coef = f1;
        var order = NC;
        var grid = WbTables.GRID;
        var xlow = grid[0];
        var ylow = chebps2(xlow, coef, order);
        var j = 0;
        while (nf < M - 1 && j < GRID_POINTS) {
            j++;
            var xhigh = xlow;
            var yhigh = ylow;
            xlow = grid[j];
            ylow = chebps2(xlow, coef, order);
            if (lMult(ylow, yhigh) <= 0) {
                for (var i = 0; i < 2; i++) {
                    var xmid = add(shr(xlow, 1), shr(xhigh, 1));
                    var ymid = chebps2(xmid, coef, order);
                    if (lMult(ylow, ymid) <= 0) {
                        yhigh = ymid;
                        xhigh = xmid;
                    } else {
                        ylow = ymid;
                        xlow = xmid;
                    }
                }
                var x = sub(xhigh, xlow);
                var y = sub(yhigh, ylow);
                short xint;
                if (y == 0) {
                    xint = xlow;
                } else {
                    var sign = y;
                    y = absS(y);
                    var exp = normS(y);
                    y = shl(y, exp);
                    y = divS(16383, y);
                    var t0 = lMult(x, y);
                    t0 = lShr(t0, sub(20, exp));
                    y = (short) t0;
                    if (sign < 0) {
                        y = negate(y);
                    }
                    t0 = lMult(ylow, y);
                    t0 = lShr(t0, 11);
                    xint = sub(xlow, (short) t0);
                }
                isp[nf] = xint;
                xlow = xint;
                nf++;
                if (ip == 0) {
                    ip = 1;
                    coef = f2;
                    order = NC - 1;
                } else {
                    ip = 0;
                    coef = f1;
                    order = NC;
                }
                ylow = chebps2(xlow, coef, order);
            }
        }
        if (nf < M - 1) {
            System.arraycopy(oldIsp, 0, isp, 0, M);
        } else {
            isp[M - 1] = shl(a[aOff + M], 3);
        }
    }

    /**
     * Evaluates a Chebyshev polynomial series at a point ({@code Chebps2}).
     *
     * @param x the point in Q15
     * @param f the coefficients
     * @param n the polynomial order
     * @return the value, never {@code -32768}
     */
    private static short chebps2(short x, short[] f, int n) {
        var t0 = lMult(f[0], 4096);
        var b2h = hi(t0);
        var b2l = lo(t0);
        t0 = mpy3216(b2h, b2l, x);
        t0 = lShl(t0, 1);
        t0 = lMac(t0, f[1], 4096);
        var b1h = hi(t0);
        var b1l = lo(t0);
        for (var i = 2; i < n; i++) {
            t0 = mpy3216(b1h, b1l, x);
            t0 = lMac(t0, b2h, -16384);
            t0 = lMac(t0, f[i], 2048);
            t0 = lShl(t0, 1);
            t0 = lMsu(t0, b2l, 1);
            var b0h = hi(t0);
            var b0l = lo(t0);
            b2l = b1l;
            b2h = b1h;
            b1l = b0l;
            b1h = b0h;
        }
        t0 = mpy3216(b1h, b1l, x);
        t0 = lMac(t0, b2h, -32768);
        t0 = lMsu(t0, b2l, 1);
        t0 = lMac(t0, f[n], 2048);
        t0 = lShl(t0, 6);
        var cheb = extractH(t0);
        return cheb == -32768 ? -32767 : cheb;
    }

    /**
     * Converts ISPs to LP coefficients ({@code Isp_Az}).
     *
     * @param isp the 16 ISPs in Q15
     * @param ispOff the offset of the ISPs
     * @param a the 17 LP coefficients in Q12
     * @param aOff the offset of the coefficients
     */
    void ispAz(short[] isp, int ispOff, short[] a, int aOff) {
        var f1 = polF1;
        var f2 = polF2;
        var m = M;
        var nc = NC;
        getIspPol(isp, ispOff, f1, nc);
        getIspPol(isp, ispOff + 1, f2, nc - 1);
        for (var i = nc - 1; i > 1; i--) {
            f2[i] = lSub(f2[i], f2[i - 2]);
        }
        for (var i = 0; i < nc; i++) {
            var t0 = mpy3216(hi(f1[i]), lo(f1[i]), isp[ispOff + m - 1]);
            f1[i] = lAdd(f1[i], t0);
            t0 = mpy3216(hi(f2[i]), lo(f2[i]), isp[ispOff + m - 1]);
            f2[i] = lSub(f2[i], t0);
        }
        a[aOff] = 4096;
        for (int i = 1, j = m - 1; i < nc; i++, j--) {
            a[aOff + i] = (short) lShrR(lAdd(f1[i], f2[i]), 12);
            a[aOff + j] = (short) lShrR(lSub(f1[i], f2[i]), 12);
        }
        var t0 = mpy3216(hi(f1[nc]), lo(f1[nc]), isp[ispOff + m - 1]);
        t0 = lAdd(f1[nc], t0);
        a[aOff + nc] = (short) lShrR(t0, 12);
        a[aOff + m] = shrR(isp[ispOff + m - 1], 3);
    }

    /**
     * Expands the polynomial whose roots are every second ISP ({@code Get_isp_pol}).
     *
     * @param isp the ISPs
     * @param ispOff the offset of the first ISP used
     * @param f the {@code n + 1} polynomial coefficients in Q23
     * @param n the polynomial order
     */
    private static void getIspPol(short[] isp, int ispOff, int[] f, int n) {
        f[0] = lMult(4096, 1024);
        f[1] = lMult(isp[ispOff], -256);
        var fp = 2;
        var ip = ispOff + 2;
        for (var i = 2; i <= n; i++) {
            f[fp] = f[fp - 2];
            for (var j = 1; j < i; j++, fp--) {
                var t0 = mpy3216(hi(f[fp - 1]), lo(f[fp - 1]), isp[ip]);
                t0 = lShl(t0, 1);
                f[fp] = lSub(f[fp], t0);
                f[fp] = lAdd(f[fp], f[fp - 2]);
            }
            f[fp] = lMsu(f[fp], isp[ip], 256);
            fp += i;
            ip += 2;
        }
    }

    /**
     * Converts ISPs to ISFs ({@code Isp_isf}).
     *
     * @param isp the 16 ISPs in Q15
     * @param isf the 16 ISFs, in the range 0 to 16384 (0 to 6400 Hz)
     */
    static void ispIsf(short[] isp, short[] isf) {
        var table = WbCodecTables.COS_TABLE;
        var slope = WbTables.COS_SLOPE;
        var ind = 127;
        for (var i = M - 1; i >= 0; i--) {
            if (i >= M - 2) {
                ind = 127;
            }
            while (table[ind] < isp[i]) {
                ind--;
            }
            var tmp = lMult(sub(isp[i], table[ind]), slope[ind]);
            isf[i] = round(lShl(tmp, 4));
            isf[i] = add(isf[i], shl(ind, 7));
        }
        isf[M - 1] = shr(isf[M - 1], 1);
    }

    /**
     * Converts ISFs to ISPs ({@code Isf_isp}).
     *
     * @param isf the 16 ISFs
     * @param isp the 16 ISPs in Q15
     */
    static void isfIsp(short[] isf, short[] isp) {
        var table = WbCodecTables.COS_TABLE;
        System.arraycopy(isf, 0, isp, 0, M - 1);
        isp[M - 1] = shl(isf[M - 1], 1);
        for (var i = 0; i < M; i++) {
            var ind = shr(isp[i], 7);
            var offset = (short) (isp[i] & 0x007f);
            var tmp = lMult(sub(table[ind + 1], table[ind]), offset);
            isp[i] = add(table[ind], (short) lShr(tmp, 8));
        }
    }

    /**
     * Interpolates the ISPs of the previous and current frames over the four subframes and
     * converts each to LP coefficients ({@code Int_isp}).
     *
     * @param ispOld the ISPs of the previous frame
     * @param ispNew the ISPs of the current frame
     * @param frac the interpolation factors of the first three subframes in Q15
     * @param az the 4 x 17 LP coefficients
     */
    void intIsp(short[] ispOld, short[] ispNew, short[] frac, short[] az) {
        var isp = interpolated;
        var off = 0;
        for (var k = 0; k < 3; k++) {
            var facNew = frac[k];
            var facOld = add(sub(32767, facNew), 1);
            for (var i = 0; i < M; i++) {
                var tmp = lMult(ispOld[i], facOld);
                tmp = lMac(tmp, ispNew[i], facNew);
                isp[i] = round(tmp);
            }
            ispAz(isp, 0, az, off);
            off += M + 1;
        }
        ispAz(ispNew, 0, az, off);
    }
}
