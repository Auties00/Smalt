/* Ports vo-amrwbenc 0.1.3 (cor_h_x.c, c2t64fx.c, c4t64fx.c). Copyright VisualOn; Apache License 2.0. Modified for
   Smalt. */
package com.github.auties00.smalt.audio.amr.internal.wbenc;

import com.github.auties00.smalt.audio.amr.internal.AmrKernel;
import com.github.auties00.smalt.audio.amr.internal.AmrKernels;

import static com.github.auties00.smalt.audio.amr.internal.wbenc.WbBasicOps.*;

/**
 * Searches the algebraic (fixed) codebook of the AMR-WB encoder: the correlation of the target
 * with the impulse response, the 12-bit two-track search of the 6.60 kbit/s mode and the
 * depth-first four-track search of the other modes (TS 26.190 section 5.8).
 *
 * <p>An instance holds the correlation matrices and scratch vectors of the searches, so a search
 * allocates nothing; an instance is not thread-safe.
 *
 * @implNote This implementation computes as the 3GPP TS 26.173 fixed-point reference does: saturating accumulations,
 * the per-track normalization of {@code cor_h_x}, and the impulse response scaling of the four-track search.
 * Two-dimensional tables are flattened row by row.
 */
final class WbAcelp {
    /**
     * The subframe length.
     */
    private static final int L_SUBFR = 64;

    /**
     * The number of tracks of the four-track search.
     */
    private static final int NB_TRACK = 4;

    /**
     * The number of positions of a track of the four-track search.
     */
    private static final int NB_POS = 16;

    /**
     * The number of entries of a cross-correlation row of the four-track search.
     */
    private static final int MSIZE = 256;

    /**
     * The number of candidate positions per track kept by the four-track search.
     */
    private static final int NB_MAX = 8;

    /**
     * The largest number of pulses per track.
     */
    private static final int NPMAXPT = 6;

    /**
     * The order in which the four-track search visits the tracks ({@code tipos} of
     * {@code c4t64fx.c}).
     */
    private static final short[] TIPOS = {
            0, 1, 2, 3, 1, 2, 3, 0, 2, 3, 0, 1, 3, 0, 1, 2, 0, 1, 2, 3, 1, 2, 3, 0,
            2, 3, 0, 1, 3, 0, 1, 2, 0, 1, 2, 3
    };

    /**
     * The 32-bit correlations of {@link #corHX(short[], short[], short[])}.
     */
    private final int[] y32 = new int[L_SUBFR];

    /**
     * The sign of every position, +1 or -1 in Q15.
     */
    private final short[] sign = new short[L_SUBFR];

    /**
     * The opposite of {@link #sign}, then the filtered codeword under construction.
     */
    private final short[] vec = new short[L_SUBFR];

    /**
     * The mixed target and residual used to choose the signs and candidate positions.
     */
    private final short[] dn2 = new short[L_SUBFR];

    /**
     * The largest magnitude of the accumulator of a pair search for which adding the two
     * correlations of a candidate cannot saturate it.
     */
    private static final int SEARCH_HEADROOM = Integer.MAX_VALUE - (1 << 29) - (1 << 28);

    /**
     * The kernel that runs the integer filters.
     */
    private static final AmrKernel KERNEL = AmrKernels.INSTANCE;

    /**
     * The largest magnitude of an accumulation that cannot saturate.
     */
    private static final long UNSATURATED_SUM = 0x7fff0000L;

    /**
     * The row stride of {@link #phases}: the phase samples and the slack the vector loads read.
     */
    private static final int PHASE_STRIDE = 40;

    /**
     * The pulse vector in four phases: row {@code p} holds the samples {@code 4 j + p} of the
     * vector padded with zeros past its end.
     */
    private final short[] phases = new short[4 * PHASE_STRIDE];

    /**
     * The impulse response in four phases: row {@code r} holds the samples {@code 4 q + r}, as
     * integers.
     */
    private final int[][] impulsePhases = new int[4][16];

    /**
     * The target followed by the zeros of the correlation.
     */
    private final short[] xPad = new short[2 * L_SUBFR];

    /**
     * The doubled impulse response.
     */
    private final int[] impulseDoubled = new int[L_SUBFR];

    /**
     * The sums of the products of each phase of the impulse response with the pulse vector.
     */
    private final int[] phaseSums = new int[64];

    /**
     * The sum of the magnitudes of the impulse response.
     */
    private long impulseMagnitude;

    /**
     * Whether the impulse response holds the smallest 16-bit value.
     */
    private boolean impulseHasMinimum;

    /**
     * The largest magnitude of the pulse vector.
     */
    private int vecPeak;

    /**
     * The smallest value of the pulse vector.
     */
    private int vecMinimum;

    /**
     * The impulse response at index 64 and its opposite at index 192, both preceded by 64 zeros.
     */
    private final short[] hBuf = new short[4 * L_SUBFR];

    /**
     * The energies of the impulse response for each track and position, row by row.
     */
    private final short[] rrixix = new short[NB_TRACK * NB_POS];

    /**
     * The cross-correlations of the impulse response between the positions of consecutive
     * tracks, row by row.
     */
    private final short[] rrixiy = new short[NB_TRACK * MSIZE];

    /**
     * The positions of the pulses of the current candidate, then the pulse positions of each
     * track.
     *
     * <p>The array has spare slots after the {@code NPMAXPT * NB_TRACK} the reference declares:
     * when a pair search finds no improving pair it keeps the positions of the previous pair, so
     * a track can receive one pulse too many, and the reference then writes past its array into
     * stack memory it never reads again. The spare slots absorb those writes.
     */
    private final short[] ind = new short[2 * NPMAXPT * NB_TRACK];

    /**
     * The positions of the pulses of the best candidate.
     */
    private final short[] codvec = new short[24];

    /**
     * The number of candidate positions searched for each pulse pair.
     */
    private final short[] nbpos = new short[10];

    /**
     * The correlations of the second track of a pulse pair.
     */
    private final short[] corY = new short[NB_POS];

    /**
     * The position of the largest mixed correlation of each track.
     */
    private final short[] posMax = new short[NB_TRACK];

    /**
     * The track order of the current iteration.
     */
    private final short[] ipos = new short[24];

    /**
     * The correlation of the pulses chosen so far, updated by a pair search.
     */
    private short searchPs;

    /**
     * The energy of the pulses chosen so far, updated by a pair search.
     */
    private short searchAlp;

    /**
     * The first position chosen by a pair search.
     */
    private short searchIx;

    /**
     * The second position chosen by a pair search.
     */
    private short searchIy;

    /**
     * The pulse quantizer of the four-track search.
     */
    private final WbPulseQuantizer pulses = new WbPulseQuantizer();

    /**
     * Creates the search with its scratch buffers.
     */
    WbAcelp() {
    }

    /**
     * Computes the correlation between the target and the impulse response, normalized for the
     * codebook search ({@code cor_h_x}).
     *
     * @param h the impulse response
     * @param x the target
     * @param dn the 64 normalized correlations
     */
    void corHX(short[] h, short[] x, short[] dn) {
        var wideSums = false;
        var peak = 0;
        var minimum = 0;
        var magnitude = 0L;
        for (var i = 0; i < L_SUBFR; i++) {
            peak = Math.max(peak, Math.abs(x[i]));
            minimum = Math.min(minimum, Math.min(x[i], h[i]));
            magnitude += Math.abs(h[i]);
        }
        if (2L * peak * magnitude + 1 <= UNSATURATED_SUM && minimum != Short.MIN_VALUE) {
            System.arraycopy(x, 0, xPad, 0, L_SUBFR);
            for (var m = 0; m < L_SUBFR; m++) {
                impulseDoubled[m] = 2 * h[m];
            }
            KERNEL.fir(xPad, 0, impulseDoubled, L_SUBFR, L_SUBFR, phaseSums, 0);
            wideSums = true;
        }
        var tot = 1;
        for (var k = 0; k < 4; k++) {
            var max = 0;
            for (var i = k; i < L_SUBFR; i += 4) {
                var tmp = wideSums ? phaseSums[i] + 1 : macForward(1, x, i, h, 0, L_SUBFR - i);
                y32[i] = tmp;
                tmp = lAbs(tmp);
                if (tmp > max) {
                    max = tmp;
                }
            }
            max = max >> 2;
            tot = lAdd(tot, max);
            tot = lAdd(tot, max >> 1);
        }
        var j = normL(tot) - 4;
        for (var i = 0; i < L_SUBFR; i++) {
            dn[i] = WbBasicOps.round(lShl(y32[i], j));
        }
    }

    /**
     * Searches the 12-bit two-track codebook of the 6.60 kbit/s mode: one pulse on the even and
     * one on the odd positions ({@code ACELP_2t64_fx}).
     *
     * @param dn the correlation of the target with the impulse response, made positive
     * @param cn the residual after long-term prediction
     * @param hIn the impulse response
     * @param code the 64-sample codevector in Q9
     * @param y the filtered codevector in Q9
     * @param index the codebook index, written at index 0
     */
    void acelp2t64(short[] dn, short[] cn, short[] hIn, short[] code, short[] y, short[] index) {
        final int nbPos = 32;
        final int msize = 1024;
        var alp = 8192;
        var packed = dotProduct12(cn, 0, cn, 0, L_SUBFR);
        packed = isqrtN(value(packed), exponent(packed));
        var s = lShl(value(packed), add(exponent(packed), 5));
        var kCn = round(s);
        packed = dotProduct12(dn, 0, dn, 0, L_SUBFR);
        packed = isqrtN(value(packed), exponent(packed));
        var kDn = round(lShl(value(packed), add(exponent(packed), 5 + 3)));
        kDn = multR(alp, kDn);
        for (var i = 0; i < L_SUBFR; i++) {
            s = lMac(lMult(kCn, cn[i]), kDn, dn[i]);
            dn2[i] = extractH(lShl(s, 8));
        }
        for (var i = 0; i < L_SUBFR; i++) {
            var val = dn[i];
            if (dn2[i] >= 0) {
                sign[i] = 32767;
                vec[i] = -32768;
            } else {
                sign[i] = -32768;
                vec[i] = 32767;
                val = negate(val);
            }
            dn[i] = val;
        }
        var hb = hBuf;
        final int h = L_SUBFR;
        final int hInv = h + 2 * L_SUBFR;
        for (var i = 0; i < L_SUBFR; i++) {
            hb[h + i] = hIn[i];
            hb[hInv + i] = negate(hIn[i]);
        }
        var rrxx = rrixix;
        var p0 = nbPos - 1;
        var p1 = 2 * nbPos - 1;
        var ph = h;
        var cor = 0x00010000;
        for (var i = 0; i < nbPos; i++) {
            cor = lMac(cor, hb[ph], hb[ph]);
            ph++;
            rrxx[p1--] = extractH(cor);
            cor = lMac(cor, hb[ph], hb[ph]);
            ph++;
            rrxx[p0--] = extractH(cor);
        }
        for (var i = 0; i < 2 * nbPos; i++) {
            rrxx[i] = shr(rrxx[i], 1);
        }
        var rrxy = rrixiy;
        var pos = msize - 1;
        var pos2 = msize - 2;
        var ptrHf = h + 1;
        for (var k = 0; k < nbPos; k++) {
            p1 = pos;
            p0 = pos2;
            cor = 0x00008000;
            var ph1 = h;
            var ph2 = ptrHf;
            for (var i = k + 1; i < nbPos; i++) {
                cor = lMac(cor, hb[ph1++], hb[ph2++]);
                rrxy[p1] = extractH(cor);
                cor = lMac(cor, hb[ph1++], hb[ph2++]);
                rrxy[p0] = extractH(cor);
                p1 -= nbPos + 1;
                p0 -= nbPos + 1;
            }
            cor = lMac(cor, hb[ph1], hb[ph2]);
            rrxy[p1] = extractH(cor);
            pos -= nbPos;
            pos2--;
            ptrHf += 2;
        }
        p0 = 0;
        for (var i = 0; i < L_SUBFR; i += 2) {
            var psign = sign[i] < 0 ? vec : sign;
            for (var j = 1; j < L_SUBFR; j += 2) {
                rrxy[p0] = mult(rrxy[p0], psign[j]);
                p0++;
            }
        }
        p0 = 0;
        p1 = nbPos;
        var p2 = 0;
        var psk = (short) -1;
        var alpk = (short) 1;
        var ix = 0;
        var iy = 1;
        for (var i0 = 0; i0 < L_SUBFR; i0 += 2) {
            var ps1 = dn[i0];
            var alp1 = rrxx[p0++];
            pos = -1;
            for (var i1 = 1; i1 < L_SUBFR; i1 += 2) {
                var ps2 = add(ps1, dn[i1]);
                var alp2 = add(alp1, add(rrxx[p1++], rrxy[p2++]));
                var sq = mult(ps2, ps2);
                s = lMsu(lMult(alpk, sq), psk, alp2);
                if (s > 0) {
                    psk = sq;
                    alpk = alp2;
                    pos = i1;
                }
            }
            p1 -= nbPos;
            if (pos >= 0) {
                ix = i0;
                iy = pos;
            }
        }
        for (var i = 0; i < L_SUBFR; i++) {
            code[i] = 0;
        }
        var i0 = ix >> 1;
        var i1 = iy >> 1;
        int q0;
        int q1;
        if (sign[ix] > 0) {
            code[ix] = 512;
            q0 = h - ix;
        } else {
            code[ix] = -512;
            i0 += nbPos;
            q0 = hInv - ix;
        }
        if (sign[iy] > 0) {
            code[iy] = 512;
            q1 = h - iy;
        } else {
            code[iy] = -512;
            i1 += nbPos;
            q1 = hInv - iy;
        }
        index[0] = add(shl(i0, 6), i1);
        for (var i = 0; i < L_SUBFR; i++) {
            y[i] = shrR(add(hb[q0 + i], hb[q1 + i]), 3);
        }
    }

    /**
     * Searches the four-track codebook of the 8.85 to 23.85 kbit/s modes with the depth-first
     * tree search ({@code ACELP_4t64_fx}).
     *
     * @param dn the correlation of the target with the impulse response, made positive
     * @param cn the residual after long-term prediction
     * @param hIn the impulse response
     * @param code the 64-sample codevector in Q9
     * @param y the filtered codevector in Q9
     * @param nbbits the number of codebook bits: 20, 36, 44, 52, 64, 72 or 88
     * @param serSize the number of bits of the frame
     * @param indices the 4 or 8 codebook indices
     */
    void acelp4t64(short[] dn, short[] cn, short[] hIn, short[] code, short[] y, int nbbits, int serSize,
                   short[] indices) {
        int nbiter;
        int alp;
        int nbPulse;
        switch (nbbits) {
            case 20 -> {
                nbiter = 4;
                alp = 8192;
                nbPulse = 4;
                nbpos[0] = 4;
                nbpos[1] = 8;
            }
            case 36 -> {
                nbiter = 4;
                alp = 4096;
                nbPulse = 8;
                nbpos[0] = 4;
                nbpos[1] = 8;
                nbpos[2] = 8;
            }
            case 44 -> {
                nbiter = 4;
                alp = 4096;
                nbPulse = 10;
                nbpos[0] = 4;
                nbpos[1] = 6;
                nbpos[2] = 8;
                nbpos[3] = 8;
            }
            case 52 -> {
                nbiter = 4;
                alp = 4096;
                nbPulse = 12;
                nbpos[0] = 4;
                nbpos[1] = 6;
                nbpos[2] = 8;
                nbpos[3] = 8;
            }
            case 64 -> {
                nbiter = 3;
                alp = 3277;
                nbPulse = 16;
                nbpos[0] = 4;
                nbpos[1] = 4;
                nbpos[2] = 6;
                nbpos[3] = 6;
                nbpos[4] = 8;
                nbpos[5] = 8;
            }
            case 72 -> {
                nbiter = 3;
                alp = 3072;
                nbPulse = 18;
                nbpos[0] = 2;
                nbpos[1] = 3;
                nbpos[2] = 4;
                nbpos[3] = 5;
                nbpos[4] = 6;
                nbpos[5] = 7;
                nbpos[6] = 8;
            }
            case 88 -> {
                nbiter = serSize > 462 ? 1 : 2;
                alp = 2048;
                nbPulse = 24;
                nbpos[0] = 2;
                nbpos[1] = 2;
                nbpos[2] = 3;
                nbpos[3] = 4;
                nbpos[4] = 5;
                nbpos[5] = 6;
                nbpos[6] = 7;
                nbpos[7] = 8;
                nbpos[8] = 8;
                nbpos[9] = 8;
            }
            default -> {
                nbiter = 0;
                alp = 0;
                nbPulse = 0;
            }
        }
        for (var i = 0; i < nbPulse; i++) {
            codvec[i] = (short) i;
        }
        var packed = dotProduct12(cn, 0, cn, 0, L_SUBFR);
        packed = isqrtN(value(packed), exponent(packed));
        var s = lShl(value(packed), add(exponent(packed), 5));
        var kCn = round(s);
        packed = dotProduct12(dn, 0, dn, 0, L_SUBFR);
        packed = isqrtN(value(packed), exponent(packed));
        var kDn = round(lShl(value(packed), add(exponent(packed), 5 + 3)));
        kDn = multR(alp, kDn);
        for (var i = 0; i < L_SUBFR; i++) {
            s = lMac(lMult(kCn, cn[i]), kDn, dn[i]);
            dn2[i] = extractH(lShl(s, 8));
        }
        for (var i = 0; i < L_SUBFR; i++) {
            var val = dn[i];
            var ps = dn2[i];
            if (ps >= 0) {
                sign[i] = 32767;
                vec[i] = -32768;
            } else {
                sign[i] = -32768;
                vec[i] = 32767;
                val = negate(val);
                ps = negate(ps);
            }
            dn[i] = val;
            dn2[i] = ps;
        }
        var pos = 0;
        for (var i = 0; i < NB_TRACK; i++) {
            for (var k = 0; k < NB_MAX; k++) {
                var ps = -1;
                for (var j = i; j < L_SUBFR; j += 4) {
                    if (dn2[j] > ps) {
                        ps = dn2[j];
                        pos = j;
                    }
                }
                dn2[pos] = (short) (k - NB_MAX);
                if (k == 0) {
                    posMax[i] = (short) pos;
                }
            }
        }
        var hb = hBuf;
        final int h = L_SUBFR;
        final int hInv = 3 * L_SUBFR;
        var energy = 0;
        for (var i = 0; i < L_SUBFR; i++) {
            energy = lMac(energy, hIn[i], hIn[i]);
        }
        var val = extractH(energy);
        var hShift = 0;
        if (nbPulse >= 12 && val > 1024) {
            hShift = 1;
        }
        if (val > 0x6000) {
            hShift = 2;
        }
        for (var i = 0; i < L_SUBFR; i++) {
            hb[h + i] = shr(hIn[i], hShift);
            hb[hInv + i] = negate(hb[h + i]);
        }
        preparePhases(hb, h);
        if (!correlationsWide(hb, h)) {
            correlationsExact(hb, h);
        }
        var rrxx = rrixix;
        var rrxy = rrixiy;
        var p0 = 0;
        for (var k = 0; k < NB_TRACK; k++) {
            for (var i = k; i < L_SUBFR; i += 4) {
                var psign = sign[i] < 0 ? vec : sign;
                for (var j = (k + 1) % NB_TRACK; j < L_SUBFR; j += 4) {
                    rrxy[p0] = mult(rrxy[p0], psign[j]);
                    p0++;
                }
            }
        }
        var psk = (short) -1;
        var alpk = (short) 1;
        for (var k = 0; k < nbiter; k++) {
            for (var i = 0; i < nbPulse; i++) {
                ipos[i] = TIPOS[k * 4 + i];
            }
            short ps;
            if (nbbits == 20) {
                pos = 0;
                ps = 0;
                alp = 0;
                for (var i = 0; i < L_SUBFR; i++) {
                    vec[i] = 0;
                }
            } else if (nbbits == 36 || nbbits == 44) {
                pos = 2;
                var ix = posMax[ipos[0]];
                ind[0] = ix;
                var iy = posMax[ipos[1]];
                ind[1] = iy;
                ps = add(dn[ix], dn[iy]);
                var i = shr(ix, 2);
                var j = shr(iy, 2);
                s = lMult(rrxx[ipos[0] * NB_POS + i], 4096);
                s = lMac(s, rrxx[ipos[1] * NB_POS + j], 4096);
                i = add(shl(i, 4), j);
                s = lMac(s, rrxy[ipos[0] * MSIZE + i], 8192);
                alp = round(s);
                var q0 = sign[ix] < 0 ? hInv - ix : h - ix;
                var q1 = sign[iy] < 0 ? hInv - iy : h - iy;
                for (var n = 0; n < L_SUBFR; n++) {
                    vec[n] = add(hb[q0 + n], hb[q1 + n]);
                }
                if (nbbits == 44) {
                    ipos[8] = 0;
                    ipos[9] = 1;
                }
            } else {
                pos = 4;
                var ix = posMax[ipos[0]];
                ind[0] = ix;
                var iy = posMax[ipos[1]];
                ind[1] = iy;
                var i2 = posMax[ipos[2]];
                ind[2] = i2;
                var i3 = posMax[ipos[3]];
                ind[3] = i3;
                ps = add(add(add(dn[ix], dn[iy]), dn[i2]), dn[i3]);
                var q0 = sign[ix] < 0 ? hInv - ix : h - ix;
                var q1 = sign[iy] < 0 ? hInv - iy : h - iy;
                var q2 = sign[i2] < 0 ? hInv - i2 : h - i2;
                var q3 = sign[i3] < 0 ? hInv - i3 : h - i3;
                for (var n = 0; n < L_SUBFR; n++) {
                    vec[n] = add(add(add(hb[q0 + n], hb[q1 + n]), hb[q2 + n]), hb[q3 + n]);
                }
                var tmp = macForward(0, vec, 0, vec, 0, L_SUBFR);
                alp = round(lShr(tmp, 3));
                if (nbbits == 72) {
                    ipos[16] = 0;
                    ipos[17] = 1;
                }
            }
            searchPs = ps;
            searchAlp = (short) alp;
            for (int j = pos, st = 0; j < nbPulse; j += 2, st++) {
                splitVector();
                corHVec(hb, h, ipos[j + 1], corY);
                searchIxiy(nbpos[st], ipos[j], ipos[j + 1], dn, hb, h);
                ind[j] = searchIx;
                ind[j + 1] = searchIy;
                var q0 = sign[searchIx] < 0 ? hInv - searchIx : h - searchIx;
                var q1 = sign[searchIy] < 0 ? hInv - searchIy : h - searchIy;
                for (var n = 0; n < L_SUBFR; n++) {
                    vec[n] = add(vec[n], add(hb[q0 + n], hb[q1 + n]));
                }
            }
            ps = mult(searchPs, searchPs);
            alp = searchAlp;
            s = lMsu(lMult(alpk, ps), psk, alp);
            if (s > 0) {
                psk = ps;
                alpk = (short) alp;
                System.arraycopy(ind, 0, codvec, 0, nbPulse);
                System.arraycopy(vec, 0, y, 0, L_SUBFR);
            }
        }
        java.util.Arrays.fill(ind, (short) -1);
        for (var i = 0; i < L_SUBFR; i++) {
            code[i] = 0;
            y[i] = shrR(y[i], 3);
        }
        val = shr(512, hShift);
        for (var k = 0; k < nbPulse; k++) {
            var i = (int) codvec[k];
            var j = sign[i];
            var index = shr(i, 2);
            var track = i & 0x03;
            if (j > 0) {
                code[i] = add(code[i], val);
                codvec[k] = add(codvec[k], 2 * L_SUBFR);
            } else {
                code[i] = sub(code[i], val);
                index = add(index, NB_POS);
            }
            i = (short) lShr(lMult(track, NPMAXPT), 1);
            while (ind[i] >= 0) {
                i++;
            }
            ind[i] = index;
        }
        var k = 0;
        switch (nbbits) {
            case 20 -> {
                for (var track = 0; track < NB_TRACK; track++) {
                    indices[track] = (short) WbPulseQuantizer.quant1pN1(ind[k], 4);
                    k += NPMAXPT;
                }
            }
            case 36 -> {
                for (var track = 0; track < NB_TRACK; track++) {
                    indices[track] = (short) WbPulseQuantizer.quant2p2N1(ind[k], ind[k + 1], 4);
                    k += NPMAXPT;
                }
            }
            case 44 -> {
                for (var track = 0; track < NB_TRACK - 2; track++) {
                    indices[track] = (short) WbPulseQuantizer.quant3p3N1(ind[k], ind[k + 1], ind[k + 2], 4);
                    k += NPMAXPT;
                }
                for (var track = 2; track < NB_TRACK; track++) {
                    indices[track] = (short) WbPulseQuantizer.quant2p2N1(ind[k], ind[k + 1], 4);
                    k += NPMAXPT;
                }
            }
            case 52 -> {
                for (var track = 0; track < NB_TRACK; track++) {
                    indices[track] = (short) WbPulseQuantizer.quant3p3N1(ind[k], ind[k + 1], ind[k + 2], 4);
                    k += NPMAXPT;
                }
            }
            case 64 -> {
                for (var track = 0; track < NB_TRACK; track++) {
                    var lIndex = pulses.quant4p4N(ind, k, 4);
                    indices[track] = (short) (lShr(lIndex, 14) & 3);
                    indices[track + NB_TRACK] = (short) (lIndex & 0x3FFF);
                    k += NPMAXPT;
                }
            }
            case 72 -> {
                for (var track = 0; track < NB_TRACK - 2; track++) {
                    var lIndex = pulses.quant5p5N(ind, k, 4);
                    indices[track] = (short) (lShr(lIndex, 10) & 0x03FF);
                    indices[track + NB_TRACK] = (short) (lIndex & 0x03FF);
                    k += NPMAXPT;
                }
                for (var track = 2; track < NB_TRACK; track++) {
                    var lIndex = pulses.quant4p4N(ind, k, 4);
                    indices[track] = (short) (lShr(lIndex, 14) & 3);
                    indices[track + NB_TRACK] = (short) (lIndex & 0x3FFF);
                    k += NPMAXPT;
                }
            }
            case 88 -> {
                for (var track = 0; track < NB_TRACK; track++) {
                    var lIndex = pulses.quant6p6N2(ind, k, 4);
                    indices[track] = (short) (lShr(lIndex, 11) & 0x07FF);
                    indices[track + NB_TRACK] = (short) (lIndex & 0x07FF);
                    k += NPMAXPT;
                }
            }
            default -> {
            }
        }
    }

    /**
     * The bias that maps the signed 32-bit range onto the unsigned one.
     */
    private static final long SIGN = 1L << 31;

    /**
     * Computes the autocorrelation and cross-correlation tables of the impulse response with
     * wide accumulators, which gives those of the saturating chains of {@code acelp_4t64} when
     * none of their partial sums leaves the 32-bit range and no product is the one
     * {@code L_mult} saturates.
     *
     * @param hb the buffer holding the impulse response
     * @param h the offset of the impulse response in {@code hb}
     * @return whether the tables were computed; otherwise the exact chains must run
     */
    private boolean correlationsWide(short[] hb, int h) {
        for (var i = 0; i < L_SUBFR; i++) {
            if (hb[h + i] == Short.MIN_VALUE) {
                return false;
            }
        }
        var acc = 0L;
        var range = 0L;
        var rrxx = rrixix;
        var p0 = NB_POS - 1;
        var p1 = 2 * NB_POS - 1;
        var p2 = 3 * NB_POS - 1;
        var p3 = 4 * NB_POS - 1;
        var ph = h;
        acc = 0x00008000L;
        range |= acc + SIGN;
        for (var i = 0; i < NB_POS; i++) {
            acc += (long) hb[ph] * hb[ph] << 1;
            range |= acc + SIGN;
            ph++;
            rrxx[p3--] = (short) (acc >> 16);
            acc += (long) hb[ph] * hb[ph] << 1;
            range |= acc + SIGN;
            ph++;
            rrxx[p2--] = (short) (acc >> 16);
            acc += (long) hb[ph] * hb[ph] << 1;
            range |= acc + SIGN;
            ph++;
            rrxx[p1--] = (short) (acc >> 16);
            acc += (long) hb[ph] * hb[ph] << 1;
            range |= acc + SIGN;
            ph++;
            rrxx[p0--] = (short) (acc >> 16);
        }
        var rrxy = rrixiy;
        var pos = MSIZE - 1;
        var ptrHf = h + 1;
        for (var k = 0; k < NB_POS; k++) {
            p3 = 2 * MSIZE + pos;
            p2 = MSIZE + pos;
            p1 = pos;
            p0 = 3 * MSIZE + pos;
            acc = 0x00008000L;
            range |= acc + SIGN;
            var ph1 = h;
            var ph2 = ptrHf;
            for (var i = k + 1; i < NB_POS; i++) {
                acc += (long) hb[ph1++] * hb[ph2++] << 1;
            range |= acc + SIGN;
                rrxy[p3] = (short) (acc >> 16);
                acc += (long) hb[ph1++] * hb[ph2++] << 1;
            range |= acc + SIGN;
                rrxy[p2] = (short) (acc >> 16);
                acc += (long) hb[ph1++] * hb[ph2++] << 1;
            range |= acc + SIGN;
                rrxy[p1] = (short) (acc >> 16);
                acc += (long) hb[ph1++] * hb[ph2++] << 1;
            range |= acc + SIGN;
                rrxy[p0 - NB_POS] = (short) (acc >> 16);
                p3 -= NB_POS + 1;
                p2 -= NB_POS + 1;
                p1 -= NB_POS + 1;
                p0 -= NB_POS + 1;
            }
            acc += (long) hb[ph1++] * hb[ph2++] << 1;
            range |= acc + SIGN;
            rrxy[p3] = (short) (acc >> 16);
            acc += (long) hb[ph1++] * hb[ph2++] << 1;
            range |= acc + SIGN;
            rrxy[p2] = (short) (acc >> 16);
            acc += (long) hb[ph1] * hb[ph2] << 1;
            range |= acc + SIGN;
            rrxy[p1] = (short) (acc >> 16);
            pos -= NB_POS;
            ptrHf += 4;
        }
        pos = MSIZE - 1;
        ptrHf = h + 3;
        for (var k = 0; k < NB_POS; k++) {
            p3 = 3 * MSIZE + pos;
            p2 = 2 * MSIZE + pos - 1;
            p1 = MSIZE + pos - 1;
            p0 = pos - 1;
            acc = 0x00008000L;
            range |= acc + SIGN;
            var ph1 = h;
            var ph2 = ptrHf;
            for (var i = k + 1; i < NB_POS; i++) {
                acc += (long) hb[ph1++] * hb[ph2++] << 1;
            range |= acc + SIGN;
                rrxy[p3] = (short) (acc >> 16);
                acc += (long) hb[ph1++] * hb[ph2++] << 1;
            range |= acc + SIGN;
                rrxy[p2] = (short) (acc >> 16);
                acc += (long) hb[ph1++] * hb[ph2++] << 1;
            range |= acc + SIGN;
                rrxy[p1] = (short) (acc >> 16);
                acc += (long) hb[ph1++] * hb[ph2++] << 1;
            range |= acc + SIGN;
                rrxy[p0] = (short) (acc >> 16);
                p3 -= NB_POS + 1;
                p2 -= NB_POS + 1;
                p1 -= NB_POS + 1;
                p0 -= NB_POS + 1;
            }
            acc += (long) hb[ph1] * hb[ph2] << 1;
            range |= acc + SIGN;
            rrxy[p3] = (short) (acc >> 16);
            pos--;
            ptrHf += 4;
        }
        return range >>> 32 == 0;
    }

    /**
     * Computes the autocorrelation and cross-correlation tables of the impulse response with the
     * saturating chains of {@code acelp_4t64}.
     *
     * @param hb the buffer holding the impulse response
     * @param h the offset of the impulse response in {@code hb}
     */
    private void correlationsExact(short[] hb, int h) {
        var rrxx = rrixix;
        var p0 = NB_POS - 1;
        var p1 = 2 * NB_POS - 1;
        var p2 = 3 * NB_POS - 1;
        var p3 = 4 * NB_POS - 1;
        var ph = h;
        var cor = 0x00008000;
        for (var i = 0; i < NB_POS; i++) {
            cor = lMac(cor, hb[ph], hb[ph]);
            ph++;
            rrxx[p3--] = extractH(cor);
            cor = lMac(cor, hb[ph], hb[ph]);
            ph++;
            rrxx[p2--] = extractH(cor);
            cor = lMac(cor, hb[ph], hb[ph]);
            ph++;
            rrxx[p1--] = extractH(cor);
            cor = lMac(cor, hb[ph], hb[ph]);
            ph++;
            rrxx[p0--] = extractH(cor);
        }
        var rrxy = rrixiy;
        var pos = MSIZE - 1;
        var ptrHf = h + 1;
        for (var k = 0; k < NB_POS; k++) {
            p3 = 2 * MSIZE + pos;
            p2 = MSIZE + pos;
            p1 = pos;
            p0 = 3 * MSIZE + pos;
            cor = 0x00008000;
            var ph1 = h;
            var ph2 = ptrHf;
            for (var i = k + 1; i < NB_POS; i++) {
                cor = lMac(cor, hb[ph1++], hb[ph2++]);
                rrxy[p3] = extractH(cor);
                cor = lMac(cor, hb[ph1++], hb[ph2++]);
                rrxy[p2] = extractH(cor);
                cor = lMac(cor, hb[ph1++], hb[ph2++]);
                rrxy[p1] = extractH(cor);
                cor = lMac(cor, hb[ph1++], hb[ph2++]);
                rrxy[p0 - NB_POS] = extractH(cor);
                p3 -= NB_POS + 1;
                p2 -= NB_POS + 1;
                p1 -= NB_POS + 1;
                p0 -= NB_POS + 1;
            }
            cor = lMac(cor, hb[ph1++], hb[ph2++]);
            rrxy[p3] = extractH(cor);
            cor = lMac(cor, hb[ph1++], hb[ph2++]);
            rrxy[p2] = extractH(cor);
            cor = lMac(cor, hb[ph1], hb[ph2]);
            rrxy[p1] = extractH(cor);
            pos -= NB_POS;
            ptrHf += 4;
        }
        pos = MSIZE - 1;
        ptrHf = h + 3;
        for (var k = 0; k < NB_POS; k++) {
            p3 = 3 * MSIZE + pos;
            p2 = 2 * MSIZE + pos - 1;
            p1 = MSIZE + pos - 1;
            p0 = pos - 1;
            cor = 0x00008000;
            var ph1 = h;
            var ph2 = ptrHf;
            for (var i = k + 1; i < NB_POS; i++) {
                cor = lMac(cor, hb[ph1++], hb[ph2++]);
                rrxy[p3] = extractH(cor);
                cor = lMac(cor, hb[ph1++], hb[ph2++]);
                rrxy[p2] = extractH(cor);
                cor = lMac(cor, hb[ph1++], hb[ph2++]);
                rrxy[p1] = extractH(cor);
                cor = lMac(cor, hb[ph1++], hb[ph2++]);
                rrxy[p0] = extractH(cor);
                p3 -= NB_POS + 1;
                p2 -= NB_POS + 1;
                p1 -= NB_POS + 1;
                p0 -= NB_POS + 1;
            }
            cor = lMac(cor, hb[ph1], hb[ph2]);
            rrxy[p3] = extractH(cor);
            pos--;
            ptrHf += 4;
        }
    }

    /**
     * Splits the impulse response into the four phases the correlations with the pulse vector run
     * over, and measures it.
     *
     * @param hb the buffer holding the impulse response
     * @param h the offset of the impulse response in {@code hb}
     */
    private void preparePhases(short[] hb, int h) {
        impulseMagnitude = 0;
        impulseHasMinimum = false;
        for (var r = 0; r < 4; r++) {
            for (var q = 0; q < 16; q++) {
                var tap = hb[h + 4 * q + r];
                impulsePhases[r][q] = tap;
                impulseMagnitude += Math.abs(tap);
                impulseHasMinimum |= tap == Short.MIN_VALUE;
            }
        }
    }

    /**
     * Splits the pulse vector into four phases and measures it.
     */
    private void splitVector() {
        vecPeak = 0;
        vecMinimum = 0;
        for (var i = 0; i < L_SUBFR; i++) {
            var value = vec[i];
            vecPeak = Math.max(vecPeak, Math.abs(value));
            vecMinimum = Math.min(vecMinimum, value);
            phases[(i & 3) * PHASE_STRIDE + (i >> 2)] = value;
        }
        for (var p = 0; p < 4; p++) {
            java.util.Arrays.fill(phases, p * PHASE_STRIDE + 16, (p + 1) * PHASE_STRIDE, (short) 0);
        }
    }

    /**
     * Correlates the impulse response with the current pulse vector at every position of a track
     * and adds the energies of the track's positions ({@code cor_h_vec}).
     *
     * @param hb the buffer holding the impulse response
     * @param h the offset of the impulse response in {@code hb}
     * @param track the track, 0 to 3
     * @param cor the 16 correlations, one per position of the track
     */
    private void corHVec(short[] hb, int h, int track, short[] cor) {
        var p0 = track * NB_POS;
        if (2L * impulseMagnitude * vecPeak <= UNSATURATED_SUM && (vecMinimum != Short.MIN_VALUE || !impulseHasMinimum)) {
            for (var r = 0; r < 4; r++) {
                KERNEL.fir(phases, ((track + r) & 3) * PHASE_STRIDE + ((track + r) >> 2), impulsePhases[r], 16, NB_POS,
                        phaseSums, r * NB_POS);
            }
            for (var i = 0; i < NB_POS; i++) {
                var total = phaseSums[i] + phaseSums[NB_POS + i] + phaseSums[2 * NB_POS + i] + phaseSums[3 * NB_POS + i];
                var corr = round(lShl(2 * total, 1));
                var pos = track + 4 * i;
                cor[i] = add(mult(corr, sign[pos]), rrixix[p0++]);
            }
            return;
        }
        var pos = track;
        for (var i = 0; i < NB_POS; i++, pos += 4) {
            var sum = macForward(0, hb, h, vec, pos, L_SUBFR - pos);
            sum = lShl(sum, 1);
            var corr = round(sum);
            cor[i] = add(mult(corr, sign[pos]), rrixix[p0++]);
        }
    }

    /**
     * Correlates the impulse response with the current pulse vector at one position of a track
     * and adds the energy of the position, as {@link #corHVec(short[], int, int, short[])} does
     * for every position.
     *
     * @param hb the buffer holding the impulse response
     * @param h the offset of the impulse response in {@code hb}
     * @param track the track, 0 to 3
     * @param pos the position, on the track
     * @return the mixed correlation of the position
     */
    private short correlationAt(short[] hb, int h, int track, int pos) {
        var sum = macForward(0, hb, h, vec, pos, L_SUBFR - pos);
        sum = lShl(sum, 1);
        var corr = round(sum);
        return add(mult(corr, sign[pos]), rrixix[track * NB_POS + ((pos - track) >> 2)]);
    }

    /**
     * Finds the best pair of pulse positions on two tracks, given the pulses already placed
     * ({@code search_ixiy}).
     *
     * <p>Reads and updates {@link #searchPs} and {@link #searchAlp}, and writes the positions to
     * {@link #searchIx} and {@link #searchIy}.
     *
     * @param nbPosIx the number of positions of the first track to try
     * @param trackX the track of the first pulse
     * @param trackY the track of the second pulse
     * @param dn the correlations of the target with the impulse response, made positive
     * @param hb the buffer holding the impulse response
     * @param h the offset of the impulse response in {@code hb}
     */
    private void searchIxiy(int nbPosIx, int trackX, int trackY, short[] dn, short[] hb, int h) {
        var p1 = 0;
        var p2 = trackX * MSIZE;
        var thresIx = sub(nbPosIx, NB_MAX);
        var alp0 = lAdd(searchAlp << 16, 0x00008000);
        var sqk = (short) -1;
        var alpk = (short) 1;
        var ix = (short) trackX;
        var iy = (short) trackY;
        for (var x = trackX; x < L_SUBFR; x += 4) {
            var ps1 = add(searchPs, dn[x]);
            if (dn2[x] < thresIx) {
                var alp1 = lMac(alp0, correlationAt(hb, h, trackX, x), 4096);
                var pos = -1;
                if (alp1 >= -SEARCH_HEADROOM && alp1 <= SEARCH_HEADROOM) {
                    var t = 0;
                    for (var y = trackY; y < L_SUBFR; y += 4, t++) {
                        var ps2 = Math.clamp(ps1 + dn[y], Short.MIN_VALUE, Short.MAX_VALUE);
                        var alp16 = (alp1 + (corY[t] << 13) + (rrixiy[p2 + t] << 14)) >> 16;
                        var sq = Math.min((ps2 * ps2) >> 15, Short.MAX_VALUE);
                        if (alpk * sq > sqk * alp16) {
                            sqk = (short) sq;
                            alpk = (short) alp16;
                            pos = y;
                        }
                    }
                    p2 += NB_POS;
                } else {
                    for (var y = trackY; y < L_SUBFR; y += 4) {
                        var ps2 = add(ps1, dn[y]);
                        var alp2 = lMac(alp1, corY[p1++], 4096);
                        alp2 = lMac(alp2, rrixiy[p2++], 8192);
                        var alp16 = extractH(alp2);
                        var sq = mult(ps2, ps2);
                        var s = lMsu(lMult(alpk, sq), sqk, alp16);
                        if (s > 0) {
                            sqk = sq;
                            alpk = alp16;
                            pos = y;
                        }
                    }
                    p1 -= NB_POS;
                }
                if (pos >= 0) {
                    ix = (short) x;
                    iy = (short) pos;
                }
            } else {
                p2 += NB_POS;
            }
        }
        searchPs = add(searchPs, add(dn[ix], dn[iy]));
        searchAlp = alpk;
        searchIx = ix;
        searchIy = iy;
    }
}
