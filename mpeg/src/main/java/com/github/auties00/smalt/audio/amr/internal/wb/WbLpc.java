/* Ports opencore-amr 0.1.6 (qpisf_2s.cpp, qisf_ns.cpp, isp_isf.cpp, isp_az.cpp, interpolate_isp.cpp,
   isf_extrapolation.cpp). Copyright PacketVideo; Apache License 2.0. Modified for Smalt. */
package com.github.auties00.smalt.audio.amr.internal.wb;

import com.github.auties00.smalt.audio.amr.internal.wbcommon.WbCodecTables;
import com.github.auties00.smalt.audio.amr.internal.wbcommon.WbIsfCodebooks;
import com.github.auties00.smalt.audio.amr.internal.wbcommon.WbIsfSplitCodebooks;

import static com.github.auties00.smalt.audio.amr.internal.wb.WbMath.*;

/**
 * Decodes and converts the linear prediction parameters of the AMR-WB decoder: the ISF
 * dequantizers of speech and comfort noise, the ISF to ISP and ISP to LP conversions, the ISP
 * interpolation and the ISF extrapolation of the 6.60 kbit/s high band.
 *
 * <p>An instance holds the scratch arrays of these routines, so it serves one decoder at a time.
 */
final class WbLpc {
    /**
     * The LP order of the 12.8 kHz core.
     */
    static final int M = 16;

    /**
     * The LP order of the 6.60 kbit/s high band.
     */
    static final int M16K = 20;

    /**
     * The number of past quantized ISF vectors averaged for concealment.
     */
    private static final int L_MEANBUF = 3;

    /**
     * The minimum distance between consecutive quantized ISFs.
     */
    private static final int ISF_GAP = 128;

    /**
     * The prediction factor of the ISF quantizer (1/3 in Q15).
     */
    private static final int MU = 10923;

    /**
     * The concealment weight of the previous ISF vector (0.9 in Q15).
     */
    private static final int ALPHA = 29491;

    /**
     * The concealment weight of the mean ISF vector.
     */
    private static final int ONE_ALPHA = 32768 - ALPHA;

    /**
     * The scratch sum polynomial of the ISP to LP conversion.
     */
    private final int[] f1 = new int[M16K / 2 + 1];

    /**
     * The scratch difference polynomial of the ISP to LP conversion.
     */
    private final int[] f2 = new int[M16K / 2];

    /**
     * The scratch interpolated ISP vector.
     */
    private final short[] isp = new short[M];

    /**
     * The scratch reference ISF vector of the concealment.
     */
    private final short[] refIsf = new short[M];

    /**
     * The scratch ISF differences of the extrapolation.
     */
    private final short[] isfDiff = new short[M - 2];

    /**
     * Creates the scratch state.
     */
    WbLpc() {
    }

    /**
     * Converts ISFs to ISPs (cosine domain).
     *
     * @param isf the ISFs
     * @param isfOff the offset of the ISFs
     * @param isp the ISPs, which may alias the ISFs
     * @param ispOff the offset of the ISPs
     * @param m the order
     */
    static void isfToIsp(short[] isf, int isfOff, short[] isp, int ispOff, int m) {
        for (var i = 0; i < m - 1; i++) {
            isp[ispOff + i] = isf[isfOff + i];
        }
        isp[ispOff + m - 1] = shl16(isf[isfOff + m - 1], 1);
        var table = WbCodecTables.COS_TABLE;
        for (var i = 0; i < m; i++) {
            var ind = clampIndex(isp[ispOff + i] >> 7, table.length - 2);
            var offset = (short) (isp[ispOff + i] & 0x007f);
            var sum = mul(table[ind + 1] - table[ind], offset);
            isp[ispOff + i] = add16(table[ind], (short) (sum >> 8));
        }
    }

    /**
     * Converts ISPs to LP coefficients {@code a[0..m]} in Q12.
     *
     * @implNote This implementation expands the polynomials with saturating double-precision
     * arithmetic, as TS 26.173 specifies; opencore wraps the sums.
     *
     * @param isp the ISPs
     * @param ispOff the offset of the ISPs
     * @param a the LP coefficients
     * @param aOff the offset of the LP coefficients
     * @param m the order, 16 or 20
     * @param adaptiveScaling whether the coefficients are scaled down when they would overflow
     */
    void ispToAz(short[] isp, int ispOff, short[] a, int aOff, int m, boolean adaptiveScaling) {
        var f1 = this.f1;
        var f2 = this.f2;
        var nc = m >> 1;
        if (nc > 8) {
            ispPol(isp, ispOff, f1, nc, 0x00200000, 7);
            for (var i = 0; i <= nc; i++) {
                f1[i] = shl32(f1[i], 2);
            }
            ispPol(isp, ispOff + 1, f2, nc - 1, 0x00200000, 7);
            for (var i = 0; i <= nc - 1; i++) {
                f2[i] = shl32(f2[i], 2);
            }
        } else {
            ispPol(isp, ispOff, f1, nc, 0x00800000, 9);
            ispPol(isp, ispOff + 1, f2, nc - 1, 0x00800000, 9);
        }
        for (var i = nc - 1; i > 1; i--) {
            f2[i] = sub32(f2[i], f2[i - 2]);
        }
        var last = isp[ispOff + m - 1];
        for (var i = 0; i < nc; i++) {
            f1[i] = add32(f1[i], mulDpf(f1[i], last));
            f2[i] = sub32(f2[i], mulDpf(f2[i], last));
        }
        a[aOff] = 4096;
        var tmax = 1;
        var j = m - 1;
        for (var i = 1; i < nc; i++) {
            var t0 = add32(f1[i], f2[i]);
            var t1 = t0 - (t0 < 0 ? 1 : 0);
            t1 = t1 ^ (t1 >> 31);
            tmax |= t1;
            a[aOff + i] = (short) ((t0 >> 12) + ((t0 >> 11) & 1));
            t0 = sub32(f1[i], f2[i]);
            t1 = t0 - (t0 < 0 ? 1 : 0);
            t1 = t1 ^ (t1 >> 31);
            tmax |= t1;
            a[aOff + j--] = (short) ((t0 >> 12) + ((t0 >> 11) & 1));
        }
        var q = adaptiveScaling ? (short) (4 - normalize(tmax)) : 0;
        int qSug;
        if (q > 0) {
            qSug = 12 + q;
            j = m - 1;
            for (var i = 1; i < nc; i++, j--) {
                var t0 = add32(f1[i], f2[i]);
                a[aOff + i] = (short) ((t0 >> qSug) + ((t0 >> (qSug - 1)) & 1));
                t0 = sub32(f1[i], f2[i]);
                a[aOff + j] = (short) ((t0 >> qSug) + ((t0 >> (qSug - 1)) & 1));
            }
            a[aOff] = (short) (a[aOff] >> q);
        } else {
            qSug = 12;
            q = 0;
        }
        var t0 = add32(f1[nc], mulDpf(f1[nc], last));
        a[aOff + nc] = (short) ((t0 >> qSug) + ((t0 >> (qSug - 1)) & 1));
        a[aOff + m] = shrRnd(last, 3 + q);
    }

    /**
     * Expands the polynomial whose roots are every other ISP into its coefficients.
     *
     * @param isp the ISPs, every other one used
     * @param ispOff the offset of the first ISP used
     * @param f the coefficients {@code f[0..n]}
     * @param n the polynomial order
     * @param one the value of the leading coefficient
     * @param shift the scaling of the ISPs
     */
    private static void ispPol(short[] isp, int ispOff, int[] f, int n, int one, int shift) {
        f[0] = one;
        f[1] = -isp[ispOff] << shift;
        var fp = 2;
        var ip = ispOff + 2;
        var weight = 1 << (shift - 1);
        for (var i = 2; i <= n; i++) {
            f[fp] = f[fp - 2];
            for (var j = 1; j < i; j++, fp--) {
                var t0 = shl32(mulDpf(f[fp - 1], isp[ip]), 1);
                f[fp] = sub32(f[fp], t0);
                f[fp] = add32(f[fp], f[fp - 2]);
            }
            f[fp] = msu(f[fp], isp[ip], weight);
            fp += i;
            ip += 2;
        }
    }

    /**
     * Multiplies a 32-bit value, split into double-precision parts, by a 16-bit value.
     *
     * @param value the 32-bit value
     * @param n the multiplier
     * @return the doubled product
     */
    private static int mulDpf(int value, int n) {
        var dpf = toDpf(value);
        return mul32By16((short) (dpf >> 16), (short) dpf, n);
    }

    /**
     * Interpolates the ISPs of the first three subframes between the previous and the current
     * frame and converts the four subframe ISP vectors to LP coefficients.
     *
     * @param ispOld the ISPs of the previous frame
     * @param ispNew the ISPs of the current frame
     * @param frac the interpolation factors of the first three subframes
     * @param az the LP coefficients of the four subframes, 17 per subframe
     * @param azOff the offset of the LP coefficients
     */
    void interpolateIsp(short[] ispOld, short[] ispNew, short[] frac, short[] az, int azOff) {
        var isp = this.isp;
        for (var k = 0; k < 3; k++) {
            var facNew = frac[k];
            var facOld = add16(sub16(32767, facNew), 1);
            for (var i = 0; i < M; i++) {
                var sum = mul(ispOld[i], facOld);
                sum = mac(sum, ispNew[i], facNew);
                isp[i] = round(sum);
            }
            ispToAz(isp, 0, az, azOff, M, false);
            azOff += M + 1;
        }
        ispToAz(ispNew, 0, az, azOff, M, false);
    }

    /**
     * Enforces the minimum distance between consecutive ISFs.
     *
     * @param isf the ISFs
     * @param off the offset of the ISFs
     * @param minDist the minimum distance
     * @param n the order
     */
    static void reorderIsf(short[] isf, int off, int minDist, int n) {
        var isfMin = (short) minDist;
        for (var i = 0; i < n - 1; i++) {
            if (isf[off + i] < isfMin) {
                isf[off + i] = isfMin;
            }
            isfMin = add16(isf[off + i], minDist);
        }
    }

    /**
     * Dequantizes the ISFs of the 46-bit split-split vector quantizer (8.85 to 23.85 kbit/s), or
     * conceals them for a bad frame.
     *
     * @implNote This implementation adds the split codebook contributions with saturation, as
     * TS 26.173 specifies; opencore wraps them.
     *
     * @param indice the seven indices
     * @param isfQ the dequantized ISFs
     * @param isfOff the offset of the dequantized ISFs
     * @param pastIsfq the past quantized ISF residual, updated
     * @param isfold the ISFs of the previous frame
     * @param isfBuf the last three good ISF vectors, updated
     * @param bfi whether the frame is bad
     */
    void dpisf46b(short[] indice, short[] isfQ, int isfOff, short[] pastIsfq, short[] isfold, short[] isfBuf,
                  boolean bfi) {
        if (!bfi) {
            var d1 = WbIsfCodebooks.DICO1;
            var d2 = WbIsfCodebooks.DICO2;
            for (var i = 0; i < 9; i++) {
                isfQ[isfOff + i] = d1[(indice[0] << 3) + indice[0] + i];
            }
            for (var i = 0; i < 7; i++) {
                isfQ[isfOff + i + 9] = d2[(indice[1] << 3) - indice[1] + i];
            }
            int i;
            for (i = 0; i < 3; i++) {
                isfQ[isfOff + i] = add16(isfQ[isfOff + i], WbIsfSplitCodebooks.DICO21[indice[2] * 3 + i]);
                isfQ[isfOff + i + 3] = add16(isfQ[isfOff + i + 3], WbIsfSplitCodebooks.DICO22[indice[3] * 3 + i]);
                isfQ[isfOff + i + 6] = add16(isfQ[isfOff + i + 6], WbIsfSplitCodebooks.DICO23[indice[4] * 3 + i]);
                isfQ[isfOff + i + 9] = add16(isfQ[isfOff + i + 9], WbIsfSplitCodebooks.DICO24[indice[5] * 3 + i]);
                isfQ[isfOff + i + 12] = add16(isfQ[isfOff + i + 12], WbIsfSplitCodebooks.DICO25[(indice[6] << 2) + i]);
            }
            isfQ[isfOff + i + 12] = add16(isfQ[isfOff + i + 12], WbIsfSplitCodebooks.DICO25[(indice[6] << 2) + i]);
            for (i = 0; i < M; i++) {
                var tmp = isfQ[isfOff + i];
                isfQ[isfOff + i] = add16(tmp, WbIsfCodebooks.MEAN_ISF[i]);
                isfQ[isfOff + i] = add16(isfQ[isfOff + i], mult(MU, pastIsfq[i]));
                pastIsfq[i] = tmp;
            }
            pushIsfBuffer(isfQ, isfOff, isfBuf);
        } else {
            conceal(isfQ, isfOff, pastIsfq, isfold, isfBuf);
        }
        reorderIsf(isfQ, isfOff, ISF_GAP, M);
    }

    /**
     * Dequantizes the ISFs of the 36-bit split-split vector quantizer (6.60 kbit/s), or conceals
     * them for a bad frame.
     *
     * @param indice the five indices
     * @param isfQ the dequantized ISFs
     * @param isfOff the offset of the dequantized ISFs
     * @param pastIsfq the past quantized ISF residual, updated
     * @param isfold the ISFs of the previous frame
     * @param isfBuf the last three good ISF vectors, updated
     * @param bfi whether the frame is bad
     */
    void dpisf36b(short[] indice, short[] isfQ, int isfOff, short[] pastIsfq, short[] isfold, short[] isfBuf,
                  boolean bfi) {
        if (!bfi) {
            for (var i = 0; i < 9; i++) {
                isfQ[isfOff + i] = WbIsfCodebooks.DICO1[indice[0] * 9 + i];
            }
            for (var i = 0; i < 7; i++) {
                isfQ[isfOff + i + 9] = add16(WbIsfCodebooks.DICO2[indice[1] * 7 + i],
                        WbIsfSplitCodebooks.DICO23_36B[indice[4] * 7 + i]);
            }
            for (var i = 0; i < 5; i++) {
                isfQ[isfOff + i] = add16(isfQ[isfOff + i], WbIsfSplitCodebooks.DICO21_36B[indice[2] * 5 + i]);
            }
            for (var i = 0; i < 4; i++) {
                isfQ[isfOff + i + 5] = add16(isfQ[isfOff + i + 5], WbIsfSplitCodebooks.DICO22_36B[(indice[3] << 2) + i]);
            }
            for (var i = 0; i < M; i++) {
                var tmp = isfQ[isfOff + i];
                isfQ[isfOff + i] = add16(tmp, WbIsfCodebooks.MEAN_ISF[i]);
                isfQ[isfOff + i] = add16(isfQ[isfOff + i], mult(MU, pastIsfq[i]));
                pastIsfq[i] = tmp;
            }
            pushIsfBuffer(isfQ, isfOff, isfBuf);
        } else {
            conceal(isfQ, isfOff, pastIsfq, isfold, isfBuf);
        }
        reorderIsf(isfQ, isfOff, ISF_GAP, M);
    }

    /**
     * Shifts a good ISF vector into the history of the last three.
     *
     * @param isfQ the ISFs
     * @param isfOff the offset of the ISFs
     * @param isfBuf the history, newest first
     */
    private static void pushIsfBuffer(short[] isfQ, int isfOff, short[] isfBuf) {
        for (var i = 0; i < M; i++) {
            for (var j = L_MEANBUF - 1; j > 0; j--) {
                isfBuf[j * M + i] = isfBuf[(j - 1) * M + i];
            }
            isfBuf[i] = isfQ[isfOff + i];
        }
    }

    /**
     * Conceals the ISFs of a bad frame from the previous ISFs and the history average.
     *
     * @param isfQ the concealed ISFs
     * @param isfOff the offset of the concealed ISFs
     * @param pastIsfq the past quantized ISF residual, updated
     * @param isfold the ISFs of the previous frame
     * @param isfBuf the last three good ISF vectors
     */
    private void conceal(short[] isfQ, int isfOff, short[] pastIsfq, short[] isfold, short[] isfBuf) {
        var refIsf = this.refIsf;
        for (var i = 0; i < M; i++) {
            var sum = mul(WbIsfCodebooks.MEAN_ISF[i], 8192);
            for (var j = 0; j < L_MEANBUF; j++) {
                sum = mac(sum, isfBuf[j * M + i], 8192);
            }
            refIsf[i] = round(sum);
        }
        for (var i = 0; i < M; i++) {
            isfQ[isfOff + i] = add16(mult(ALPHA, isfold[i]), mult(ONE_ALPHA, refIsf[i]));
        }
        for (var i = 0; i < M; i++) {
            var tmp = add16(refIsf[i], mult(pastIsfq[i], MU));
            pastIsfq[i] = sub16(isfQ[isfOff + i], tmp);
            pastIsfq[i] = (short) (pastIsfq[i] >> 1);
        }
    }

    /**
     * Dequantizes the comfort noise ISFs of a SID frame.
     *
     * @param indice the five indices
     * @param isfQ the dequantized ISFs
     */
    static void disfNs(short[] indice, short[] isfQ) {
        isfQ[0] = WbIsfSplitCodebooks.DICO1_NOISE[indice[0] << 1];
        isfQ[1] = WbIsfSplitCodebooks.DICO1_NOISE[(indice[0] << 1) + 1];
        for (var i = 0; i < 3; i++) {
            isfQ[i + 2] = WbIsfSplitCodebooks.DICO2_NOISE[(indice[1] << 1) + indice[1] + i];
            isfQ[i + 5] = WbIsfSplitCodebooks.DICO3_NOISE[(indice[2] << 1) + indice[2] + i];
        }
        for (var i = 0; i < 4; i++) {
            isfQ[i + 8] = WbIsfSplitCodebooks.DICO4_NOISE[(indice[3] << 2) + i];
            isfQ[i + 12] = WbIsfSplitCodebooks.DICO5_NOISE[(indice[4] << 2) + i];
        }
        for (var i = 0; i < M; i++) {
            isfQ[i] = add16(isfQ[i], WbIsfSplitCodebooks.MEAN_ISF_NOISE[i]);
        }
        reorderIsf(isfQ, 0, ISF_GAP, M);
    }

    /**
     * Extrapolates the 16 ISFs of the core to the 20 ISFs of the 6.60 kbit/s high band, in place,
     * and converts the result to ISPs.
     *
     * @implNote This implementation shifts, adds and subtracts with the saturating TS 26.173
     * operators; opencore wraps the sums and shifts by a raw count that is -1 when the slope term
     * is already normalized.
     *
     * @param hfIsf the ISFs, 20 values of which the first 16 are set
     * @param off the offset of the ISFs
     */
    void isfExtrapolation(short[] hfIsf, int off) {
        var diff = this.isfDiff;
        hfIsf[off + M16K - 1] = hfIsf[off + M - 1];
        for (var i = 1; i < M - 1; i++) {
            diff[i - 1] = sub16(hfIsf[off + i], hfIsf[off + i - 1]);
        }
        var sum = 0;
        for (var i = 3; i < M - 1; i++) {
            sum = mac(sum, diff[i - 1], 2731);
        }
        var mean = round(sum);
        short tmp = 0;
        for (var i = 0; i < M - 2; i++) {
            if (diff[i] > tmp) {
                tmp = diff[i];
            }
        }
        var exp = (short) normS(tmp);
        for (var i = 0; i < M - 2; i++) {
            diff[i] = shl16(diff[i], exp);
        }
        mean = shl16(mean, exp);
        var corr0 = correlation(diff, mean, 2);
        var corr1 = correlation(diff, mean, 3);
        var corr2 = correlation(diff, mean, 4);
        var maxCorr = corr0 > corr1 ? 0 : 1;
        var best = maxCorr == 0 ? corr0 : corr1;
        if (corr2 > best) {
            maxCorr = 2;
        }
        maxCorr++;
        for (var i = M - 1; i < M16K - 1; i++) {
            tmp = sub16(hfIsf[off + i - 1 - maxCorr], hfIsf[off + i - 2 - maxCorr]);
            hfIsf[off + i] = add16(hfIsf[off + i - 1], tmp);
        }
        tmp = add16(hfIsf[off + 4], hfIsf[off + 3]);
        tmp = sub16(hfIsf[off + 2], tmp);
        tmp = mult(tmp, 5461);
        tmp = add16(tmp, 20390);
        if (tmp > 19456) {
            tmp = 19456;
        }
        tmp = sub16(tmp, hfIsf[off + M - 2]);
        var tmp2 = sub16(hfIsf[off + M16K - 2], hfIsf[off + M - 2]);
        var exp2 = (short) normS(tmp2);
        exp = sub16(normS(tmp), 1);
        tmp = shl16(tmp, exp);
        tmp2 = shl16(tmp2, exp2);
        var coeff = div(tmp, tmp2);
        exp = sub16(exp2, exp);
        for (var i = M - 1; i < M16K - 1; i++) {
            tmp = mult(sub16(hfIsf[off + i], hfIsf[off + i - 1]), coeff);
            diff[i - (M - 1)] = shl16(tmp, exp);
        }
        for (var i = M; i < M16K - 1; i++) {
            tmp = sub16(add16(diff[i - (M - 1)], diff[i - M]), 1280);
            if (tmp < 0) {
                if (diff[i - (M - 1)] > diff[i - M]) {
                    diff[i - M] = sub16(1280, diff[i - (M - 1)]);
                } else {
                    diff[i - (M - 1)] = sub16(1280, diff[i - M]);
                }
            }
        }
        for (var i = M - 1; i < M16K - 1; i++) {
            hfIsf[off + i] = add16(hfIsf[off + i - 1], diff[i - (M - 1)]);
        }
        for (var i = 0; i < M16K - 1; i++) {
            hfIsf[off + i] = mult(hfIsf[off + i], 26214);
        }
        isfToIsp(hfIsf, off, hfIsf, off, M16K);
    }

    /**
     * Computes the double-precision autocorrelation of the mean-removed ISF differences at a lag.
     *
     * @param diff the normalized ISF differences
     * @param mean the normalized mean difference
     * @param lag the lag, 2 to 4
     * @return the correlation
     */
    private static int correlation(short[] diff, short mean, int lag) {
        var corr = 0;
        for (var i = 7; i < M - 2; i++) {
            var tmp2 = sub16(diff[i], mean);
            var tmp3 = sub16(diff[i - lag], mean);
            var sum = mul(tmp2, tmp3);
            var dpf = toDpf(sum);
            var hi = (short) (dpf >> 16);
            var lo = (short) dpf;
            sum = mpyDpf32(hi, lo, hi, lo);
            corr = add32(corr, sum);
        }
        return corr;
    }
}
