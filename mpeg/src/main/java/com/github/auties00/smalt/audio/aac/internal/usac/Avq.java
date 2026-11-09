/* Ports libxaac (decoder/ixheaacd_avq_dec.c: the RE8 lattice decoder), commit 2fbadd57.
   Copyright 2018 The Android Open Source Project; Apache License 2.0. Modified for Smalt. */
package com.github.auties00.smalt.audio.aac.internal.usac;

import com.github.auties00.smalt.audio.aac.internal.AacBitReader;

/**
 * Decodes the algebraic vector quantizer of USAC: the rotated Gosset lattice RE8 with its Voronoi extension
 * (ISO/IEC 23003-3 subclause 7.15.3), which codes the FAC data and the LPC refinements.
 *
 * <p>Instances are not thread-safe.
 */
final class Avq {
    private static final int[] FACTORIALS = {5040, 720, 120, 24, 6, 2, 1, 1};

    private final int[] a = new int[8];
    private final int[] w = new int[8];
    private final int[] c = new int[8];
    private final int[] kv = new int[8];
    private final int[] z = new int[8];
    private final int[] v = new int[8];
    private final int[] rem1 = new int[8];
    private final int[] rem2 = new int[8];
    private final int[] y0 = new int[8];
    private final int[] y1 = new int[8];
    private final int[] x1 = new int[8];
    private final int[] e = new int[8];
    private final int[] remTemp = new int[8];

    /**
     * Reads one 8-dimensional point coded with a unary codebook number ({@code fac_data} element) and decodes
     * it ({@code ixheaacd_fac_decoding}, one iteration).
     *
     * @param reader the bit source
     * @param out receives the 8 components
     * @param offset the index of the first component
     */
    void readUnary(AacBitReader reader, int[] out, int offset) {
        var qn = 0;
        while (reader.readBit() == 1) {
            qn++;
            if (qn > 64) {
                throw new com.github.auties00.smalt.MediaException.Malformed("USAC AVQ codebook number overflows");
            }
        }
        if (qn != 0) {
            qn++;
        }
        var nk = 0;
        var n = qn;
        if (qn > 4) {
            nk = (qn - 3) >> 1;
            n = qn - nk * 2;
        }
        var index = reader.read(4 * n);
        for (var j = 0; j < 8; j++) {
            kv[j] = reader.read(nk);
        }
        decode(qn, index, kv, 0, out, offset);
    }

    /**
     * Decodes a point of codebook {@code qn} ({@code ixheaacd_rotated_gosset_mtx_dec}).
     *
     * @param qn the codebook number, 0 or from 2 up
     * @param index the base codebook index
     * @param voronoi the Voronoi indices
     * @param voronoiOffset the index of the first Voronoi index
     * @param out receives the 8 components
     * @param offset the index of the first component
     */
    void decode(int qn, int index, int[] voronoi, int voronoiOffset, int[] out, int offset) {
        if (qn <= 4) {
            decodeBase(qn, index, out, offset);
            return;
        }
        var count = 0;
        while (qn > 4) {
            count++;
            qn -= 2;
        }
        var m = count >= 31 ? Integer.MAX_VALUE : 1 << count;
        decodeBase(qn, index, out, offset);
        voronoiIndex(voronoi, voronoiOffset, m, c, count);
        for (var i = 0; i < 8; i++) {
            out[offset + i] = m * out[offset + i] + c[i];
        }
    }

    private static int leader(int[] table, int base, int index, int size) {
        int i;
        for (i = 4; i < size; i += 4) {
            if (Integer.compareUnsigned(index, table[base + i]) < 0) {
                break;
            }
        }
        if (i > size) {
            i = size;
        }
        if (Integer.compareUnsigned(index, table[base + i - 2]) < 0) {
            i -= 2;
        }
        if (Integer.compareUnsigned(index, table[base + i - 1]) < 0) {
            i--;
        }
        return i - 1;
    }

    private void decodeBase(int n, int index, int[] y, int offset) {
        if (n < 2) {
            for (var i = 0; i < 8; i++) {
                y[offset + i] = 0;
            }
            return;
        }
        int leaderIndex;
        if (n <= 3) {
            leaderIndex = LpdTables.LEADERS_Q3[leader(LpdTables.OFFSETS_Q3, 0, index, 9)];
        } else {
            leaderIndex = LpdTables.LEADERS_Q4[leader(LpdTables.OFFSETS_Q4, 0, index, 28)];
        }
        for (var i = 0; i < 8; i++) {
            y[offset + i] = LpdTables.ABSOLUTE_LEADERS[leaderIndex * 8 + i];
        }
        var t = LpdTables.SIGN_OFFSETS[leaderIndex];
        var count = LpdTables.SIGN_COUNTS[leaderIndex];
        var ks = leader(LpdTables.SIGNED_LEADER_OFFSETS, t, index, count);
        var sign = 2 * LpdTables.SIGN_CODES[t + ks];
        for (var i = 7; i >= 0; i--) {
            y[offset + i] *= 1 - (sign & 2);
            sign >>= 1;
        }
        var rank = index - LpdTables.SIGNED_LEADER_OFFSETS[t + ks];
        permute(rank, y, offset);
    }

    private void permute(int rank, int[] xs, int offset) {
        var j = 0;
        w[0] = 1;
        a[0] = xs[offset];
        var base = 1;
        for (var i = 1; i < 8; i++) {
            if (xs[offset + i] != xs[offset + i - 1]) {
                j++;
                w[j] = 1;
                a[j] = xs[offset + i];
            } else {
                w[j]++;
                base *= w[j];
            }
        }
        if (w[0] == 8) {
            for (var i = 0; i < 8; i++) {
                xs[offset + i] = a[0];
            }
            return;
        }
        var target = rank * base;
        var factorB = 1;
        for (var i = 0; i < 8; i++) {
            var factor = factorB * FACTORIALS[i];
            j = -1;
            do {
                j++;
                if (j >= 8) {
                    throw new com.github.auties00.smalt.MediaException.Malformed("USAC AVQ rank out of range");
                }
                target -= w[j] * factor;
            } while (target >= 0);
            xs[offset + i] = a[j];
            target += w[j] * factor;
            factorB *= w[j];
            w[j]--;
        }
    }

    private void nearest(int[] x, int[] y, int count, int[] rem) {
        System.arraycopy(rem, 0, remTemp, 0, 8);
        var sum = 0;
        for (var i = 0; i < 8; i++) {
            if (x[i] < 0) {
                y[i] = -(((1 - x[i]) >> 1) << 1);
            } else {
                y[i] = ((1 + x[i]) >> 1) << 1;
            }
            sum += y[i];
            if (x[i] % 2 != 0) {
                if (x[i] < 0) {
                    remTemp[i] = -(remTemp[i] - (1 << count));
                } else {
                    remTemp[i] = remTemp[i] - (1 << count);
                }
            }
        }
        if (sum % 4 != 0) {
            var em = 0;
            var j = 0;
            System.arraycopy(remTemp, 0, e, 0, 8);
            for (var i = 0; i < 8; i++) {
                var s = Math.abs(e[i]);
                if (em < s) {
                    em = s;
                    j = i;
                }
            }
            if (e[j] < 0) {
                y[j] -= 2;
                remTemp[j] += 2 << count;
            } else {
                y[j] += 2;
                remTemp[j] -= 2 << count;
            }
        }
        System.arraycopy(remTemp, 0, rem, 0, 8);
    }

    private void search(int[] x, int[] y, int count) {
        nearest(x, y0, count, rem1);
        for (var i = 0; i < 8; i++) {
            if (x[i] == 0) {
                if (rem2[i] == 0) {
                    x1[i] = x[i] - 1;
                } else {
                    x1[i] = 0;
                    rem2[i] -= 1 << count;
                }
            } else {
                x1[i] = x[i] - 1;
            }
        }
        nearest(x1, y1, count, rem2);
        for (var i = 0; i < 8; i++) {
            y1[i] += 1;
        }
        long e0 = 0;
        long e1 = 0;
        for (var i = 0; i < 8; i++) {
            e0 += (long) rem1[i] * rem1[i];
            e1 += (long) rem2[i] * rem2[i];
        }
        System.arraycopy(e0 < e1 ? y0 : y1, 0, y, 0, 8);
    }

    private void voronoiIndex(int[] k, int kOffset, int m, int[] y, int count) {
        for (var i = 0; i < 8; i++) {
            y[i] = k[kOffset + 7];
        }
        z[7] = y[7] >> count;
        rem1[7] = y[7] & (m - 1);
        var sum = 0;
        for (var i = 6; i >= 1; i--) {
            var tmp = k[kOffset + i] << 1;
            sum += tmp;
            y[i] += tmp;
            z[i] = y[i] >> count;
            rem1[i] = y[i] & (m - 1);
        }
        y[0] += 4 * k[kOffset] + sum;
        z[0] = (y[0] - 2) >> count;
        rem1[0] = m != 0 ? (y[0] - 2) % m : y[0] - 2;
        System.arraycopy(rem1, 0, rem2, 0, 8);
        search(z, v, count);
        for (var i = 0; i < 8; i++) {
            y[i] -= m * v[i];
        }
    }
}
