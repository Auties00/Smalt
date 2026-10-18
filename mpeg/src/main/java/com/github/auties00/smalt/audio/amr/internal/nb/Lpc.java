/* Ports opencore-amr 0.1.6 (az_lsp.cpp, lsp_az.cpp, int_lpc.cpp). Copyright PacketVideo; Apache License 2.0.
   Modified for Smalt. */
package com.github.auties00.smalt.audio.amr.internal.nb;

import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.MAX_16;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.MIN_16;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.abs;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.divS;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.normS;
import static com.github.auties00.smalt.audio.amr.internal.nb.Constants.GRID_POINTS;
import static com.github.auties00.smalt.audio.amr.internal.nb.Constants.M;
import static com.github.auties00.smalt.audio.amr.internal.nb.Constants.MP1;

/**
 * Converts between LPC coefficients and line spectral pairs and interpolates LSPs across the
 * subframes of a frame.
 *
 * <p>An instance holds the scratch buffers of these conversions, so the conversions allocate
 * nothing; it is not thread-safe, and each codec instance owns one.
 */
final class Lpc {
    /**
     * Half the LPC order: the order of the sum and difference polynomials.
     */
    private static final int NC = M / 2;

    /**
     * Holds the coefficients of the symmetric polynomial F1 during {@link #azLsp}.
     */
    private final short[] f1 = new short[NC + 1];

    /**
     * Holds the coefficients of the antisymmetric polynomial F2 during {@link #azLsp}.
     */
    private final short[] f2 = new short[NC + 1];

    /**
     * Holds the coefficients of the first LSP polynomial during {@link #lspAz}.
     */
    private final int[] pol1 = new int[6];

    /**
     * Holds the coefficients of the second LSP polynomial during {@link #lspAz}.
     */
    private final int[] pol2 = new int[6];

    /**
     * Holds the interpolated LSPs during the interpolation methods.
     */
    private final short[] lsp = new short[M];

    /**
     * Creates the conversion buffers.
     */
    Lpc() {
    }

    /**
     * Evaluates a Chebyshev polynomial series at {@code x} ({@code Chebps}).
     *
     * @param x the evaluation point, cosine domain Q15
     * @param f the polynomial coefficients; {@code f[1]} to {@code f[n]} are used
     * @param n the order
     * @return the value, Q15, saturated
     */
    private static short chebps(short x, short[] f, int n) {
        var p = 1;
        var temp = 0x01000000;
        var t0 = (x << 10) + (f[p++] << 14);
        var b1h = (short) (t0 >> 16);
        var b1l = (short) ((t0 >> 1) - (b1h << 15));
        for (var i = 2; i < n; i++) {
            t0 = b1h * x;
            t0 += (b1l * x) >> 15;
            t0 <<= 2;
            t0 -= temp;
            t0 += f[p++] << 14;
            temp = (b1h << 16) + (b1l << 1);
            b1h = (short) (t0 >> 16);
            b1l = (short) ((t0 >> 1) - (b1h << 15));
        }
        t0 = b1h * x;
        t0 += (b1l * x) >> 15;
        t0 <<= 1;
        t0 -= temp;
        t0 += f[p] << 13;
        if (Integer.compareUnsigned(t0 + 33554432, 67108863) < 0) {
            return (short) (t0 >> 10);
        }
        return t0 > 0x01ffffff ? MAX_16 : MIN_16;
    }

    /**
     * Converts LPC coefficients to LSPs by searching the roots of the sum and difference
     * polynomials on the Chebyshev grid ({@code Az_lsp}).
     *
     * <p>When fewer than 10 roots are found, the previous LSPs are copied instead.
     *
     * @param a the 11 LPC coefficients, Q12
     * @param aOffset the index of the first coefficient
     * @param lsp receives the 10 LSPs, cosine domain Q15
     * @param lspOffset the index of the first LSP
     * @param oldLsp the previous 10 LSPs
     * @param oldLspOffset the index of the first previous LSP
     */
    void azLsp(short[] a, int aOffset, short[] lsp, int lspOffset, short[] oldLsp, int oldLspOffset) {
        f1[0] = 1024;
        f2[0] = 1024;
        for (var i = 0; i < NC; i++) {
            int temp1 = a[aOffset + i + 1];
            int temp2 = a[aOffset + M - i];
            var x = (short) ((temp1 + temp2) >> 2);
            var y = (short) ((temp1 - temp2) >> 2);
            x = (short) (x - f1[i]);
            f1[i + 1] = x;
            y = (short) (y + f2[i]);
            f2[i + 1] = y;
        }
        var nf = 0;
        var ip = 0;
        var coef = f1;
        var xlow = CommonTables.GRID[0];
        var ylow = chebps(xlow, coef, NC);
        var j = 0;
        while (nf < M && j < GRID_POINTS) {
            j++;
            var xhigh = xlow;
            var yhigh = ylow;
            xlow = CommonTables.GRID[j];
            ylow = chebps(xlow, coef, NC);
            if (ylow * yhigh <= 0) {
                for (var i = 4; i != 0; i--) {
                    var x = (short) (xlow >> 1);
                    var y = (short) (xhigh >> 1);
                    var xmid = (short) (x + y);
                    var ymid = chebps(xmid, coef, NC);
                    if (ylow * ymid <= 0) {
                        yhigh = ymid;
                        xhigh = xmid;
                    } else {
                        ylow = ymid;
                        xlow = xmid;
                    }
                }
                var x = (short) (xhigh - xlow);
                var y = (short) (yhigh - ylow);
                short xint;
                if (y == 0) {
                    xint = xlow;
                } else {
                    var sign = y;
                    y = abs(y);
                    var exp = normS(y);
                    y = (short) (y << exp);
                    y = divS((short) 16383, y);
                    y = (short) ((x * y) >> (19 - exp));
                    if (sign < 0) {
                        y = (short) -y;
                    }
                    xint = (short) (xlow - ((ylow * y) >> 10));
                }
                lsp[lspOffset + nf] = xint;
                xlow = xint;
                nf++;
                if (ip == 0) {
                    ip = 1;
                    coef = f2;
                } else {
                    ip = 0;
                    coef = f1;
                }
                ylow = chebps(xlow, coef, NC);
            }
        }
        if (nf < M) {
            System.arraycopy(oldLsp, oldLspOffset, lsp, lspOffset, M);
        }
    }

    /**
     * Expands the polynomial whose roots are every other LSP ({@code Get_lsp_pol}).
     *
     * @param lsp the LSPs; {@code lsp[offset]}, {@code lsp[offset + 2]}, ... are used
     * @param offset the index of the first LSP used
     * @param f receives the 6 polynomial coefficients, Q24
     */
    private static void getLspPol(short[] lsp, int offset, int[] f) {
        var l = offset;
        f[0] = 0x01000000;
        f[1] = -lsp[l] << 10;
        l += 2;
        for (var i = 2; i <= 5; i++) {
            var p = i;
            f[p] = f[p - 2];
            int value = lsp[l];
            for (var j = 1; j < i; j++) {
                var hi = (short) (f[p - 1] >> 16);
                var lo = (short) ((f[p - 1] >> 1) - (hi << 15));
                var t0 = hi * value;
                t0 += (lo * value) >> 15;
                f[p] += f[p - 2];
                f[p] -= t0 << 2;
                p--;
            }
            f[p] -= value << 10;
            l += 2;
        }
    }

    /**
     * Converts 10 LSPs to 11 LPC coefficients ({@code Lsp_Az}).
     *
     * @param lsp the 10 LSPs, cosine domain Q15
     * @param lspOffset the index of the first LSP
     * @param a receives the 11 LPC coefficients, Q12
     * @param aOffset the index of the first coefficient
     */
    void lspAz(short[] lsp, int lspOffset, short[] a, int aOffset) {
        getLspPol(lsp, lspOffset, pol1);
        getLspPol(lsp, lspOffset + 1, pol2);
        for (var i = 5; i > 0; i--) {
            pol1[i] += pol1[i - 1];
            pol2[i] -= pol2[i - 1];
        }
        a[aOffset] = 4096;
        for (int i = 1, j = 10; i <= 5; i++, j--) {
            var t0 = pol1[i] + pol2[i];
            var t1 = pol1[i] - pol2[i];
            t0 = t0 + (1 << 12);
            t1 = t1 + (1 << 12);
            a[aOffset + i] = (short) (t0 >> 13);
            a[aOffset + j] = (short) (t1 >> 13);
        }
    }

    /**
     * Interpolates the LSPs of subframes 1 and 3 from the previous, middle and new LSPs and
     * converts the four sets to LPC coefficients ({@code Int_lpc_1and3}).
     *
     * @param lspOld the LSPs of the previous frame
     * @param lspOldOffset the index of the first previous LSP
     * @param lspMid the LSPs of subframe 2
     * @param lspMidOffset the index of the first middle LSP
     * @param lspNew the LSPs of subframe 4
     * @param lspNewOffset the index of the first new LSP
     * @param az receives the 4 sets of 11 LPC coefficients
     * @param azOffset the index of the first coefficient
     */
    void intLpc1and3(short[] lspOld, int lspOldOffset, short[] lspMid, int lspMidOffset,
                     short[] lspNew, int lspNewOffset, short[] az, int azOffset) {
        average(lspOld, lspOldOffset, lspMid, lspMidOffset);
        lspAz(lsp, 0, az, azOffset);
        lspAz(lspMid, lspMidOffset, az, azOffset + MP1);
        average(lspMid, lspMidOffset, lspNew, lspNewOffset);
        lspAz(lsp, 0, az, azOffset + 2 * MP1);
        lspAz(lspNew, lspNewOffset, az, azOffset + 3 * MP1);
    }

    /**
     * Interpolates the LSPs of subframes 1 and 3 and converts only those two sets to LPC
     * coefficients, at the first and third positions of {@code az} ({@code Int_lpc_1and3_2}).
     *
     * @param lspOld the LSPs of the previous frame
     * @param lspOldOffset the index of the first previous LSP
     * @param lspMid the LSPs of subframe 2
     * @param lspMidOffset the index of the first middle LSP
     * @param lspNew the LSPs of subframe 4
     * @param lspNewOffset the index of the first new LSP
     * @param az receives the 2 sets of 11 LPC coefficients
     * @param azOffset the index of the first coefficient
     */
    void intLpc1and3Partial(short[] lspOld, int lspOldOffset, short[] lspMid, int lspMidOffset,
                      short[] lspNew, int lspNewOffset, short[] az, int azOffset) {
        average(lspOld, lspOldOffset, lspMid, lspMidOffset);
        lspAz(lsp, 0, az, azOffset);
        average(lspMid, lspMidOffset, lspNew, lspNewOffset);
        lspAz(lsp, 0, az, azOffset + 2 * MP1);
    }

    /**
     * Interpolates the LSPs of subframes 1 to 3 between the previous and new LSPs and converts
     * the four sets to LPC coefficients ({@code Int_lpc_1to3}).
     *
     * @param lspOld the LSPs of the previous frame
     * @param lspOldOffset the index of the first previous LSP
     * @param lspNew the LSPs of subframe 4
     * @param lspNewOffset the index of the first new LSP
     * @param az receives the 4 sets of 11 LPC coefficients
     * @param azOffset the index of the first coefficient
     */
    void intLpc1to3(short[] lspOld, int lspOldOffset, short[] lspNew, int lspNewOffset, short[] az, int azOffset) {
        intLpc1to3Partial(lspOld, lspOldOffset, lspNew, lspNewOffset, az, azOffset);
        lspAz(lspNew, lspNewOffset, az, azOffset + 3 * MP1);
    }

    /**
     * Interpolates the LSPs of subframes 1 to 3 and converts only those three sets to LPC
     * coefficients ({@code Int_lpc_1to3_2}).
     *
     * @param lspOld the LSPs of the previous frame
     * @param lspOldOffset the index of the first previous LSP
     * @param lspNew the LSPs of subframe 4
     * @param lspNewOffset the index of the first new LSP
     * @param az receives the 3 sets of 11 LPC coefficients
     * @param azOffset the index of the first coefficient
     */
    void intLpc1to3Partial(short[] lspOld, int lspOldOffset, short[] lspNew, int lspNewOffset, short[] az, int azOffset) {
        for (var i = 0; i < M; i++) {
            var old = lspOld[lspOldOffset + i];
            var temp = (short) (old - (old >> 2));
            lsp[i] = (short) (temp + (lspNew[lspNewOffset + i] >> 2));
        }
        lspAz(lsp, 0, az, azOffset);
        for (var i = 0; i < M; i++) {
            lsp[i] = (short) ((lspNew[lspNewOffset + i] >> 1) + (lspOld[lspOldOffset + i] >> 1));
        }
        lspAz(lsp, 0, az, azOffset + MP1);
        for (var i = 0; i < M; i++) {
            var value = lspNew[lspNewOffset + i];
            var temp = (short) (value - (value >> 2));
            lsp[i] = (short) (temp + (lspOld[lspOldOffset + i] >> 2));
        }
        lspAz(lsp, 0, az, azOffset + 2 * MP1);
    }

    /**
     * Stores the halved sum of two LSP vectors in {@link #lsp}.
     *
     * @param first the first vector
     * @param firstOffset the index of its first value
     * @param second the second vector
     * @param secondOffset the index of its first value
     */
    private void average(short[] first, int firstOffset, short[] second, int secondOffset) {
        for (var i = 0; i < M; i++) {
            lsp[i] = (short) ((first[firstOffset + i] >> 1) + (second[secondOffset + i] >> 1));
        }
    }
}
