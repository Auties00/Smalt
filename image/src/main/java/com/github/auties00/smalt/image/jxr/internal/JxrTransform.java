/* Ports jxrlib 1.1 (image/decode/strInvTransform.c, image/sys/strTransform.c: the inverse photo core transform and
   photo overlap transform operators). Copyright (c) Microsoft Corporation; BSD 2-Clause License. Modified for
   Smalt. */
package com.github.auties00.smalt.image.jxr.internal;

/**
 * Applies the integer lifting operators of the JPEG XR inverse transform (ITU-T T.832 clause 9.9) to coefficients
 * held in one array, each operand named by its index.
 *
 * <p>The operators with an {@code alternate} suffix are those of codec subversions 1 and 9, which rescale the
 * overlap operators; the others are those of subversion 0.
 */
final class JxrTransform {
    private JxrTransform() {
        throw new AssertionError();
    }

    /**
     * Applies the 2x2 Hadamard butterfly that rounds down.
     */
    static void dct2x2dn(int[] p, int pa, int pb, int pc, int pd) {
        var a = p[pa];
        var b = p[pb];
        var cc = p[pc];
        var d = p[pd];
        a += d;
        b -= cc;
        var t = (a - b) >> 1;
        var c = t - d;
        d = t - cc;
        a -= d;
        b += c;
        p[pa] = a;
        p[pb] = b;
        p[pc] = c;
        p[pd] = d;
    }

    /**
     * Applies the 2x2 Hadamard butterfly that rounds up.
     */
    static void dct2x2up(int[] p, int pa, int pb, int pc, int pd) {
        var a = p[pa];
        var b = p[pb];
        var cc = p[pc];
        var d = p[pd];
        a += d;
        b -= cc;
        var t = (a - b + 1) >> 1;
        var c = t - d;
        d = t - cc;
        a -= d;
        b += c;
        p[pa] = a;
        p[pb] = b;
        p[pc] = c;
        p[pd] = d;
    }

    /**
     * Applies the 2x2 Hadamard butterfly with the doubling of the scaled arithmetic.
     */
    static void dct2x2dnScaled(int[] p, int pa, int pb, int pc, int pd) {
        dct2x2dn(p, pa, pb, pc, pd);
        p[pa] *= 2;
        p[pb] *= 2;
        p[pc] *= 2;
        p[pd] *= 2;
    }

    /**
     * Applies the inverse first-stage 4x4 core transform to one block.
     *
     * @param p the coefficients
     * @param o the index of the block's first coefficient
     */
    static void idct4x4Stage1(int[] p, int o) {
        dct2x2up(p, o, o + 1, o + 2, o + 3);
        invOdd(p, o + 5, o + 4, o + 7, o + 6);
        invOdd(p, o + 10, o + 8, o + 11, o + 9);
        invOddOdd(p, o + 15, o + 14, o + 13, o + 12);
        dct2x2dn(p, o, o + 4, o + 8, o + 12);
        dct2x2dn(p, o + 1, o + 5, o + 9, o + 13);
        dct2x2dn(p, o + 2, o + 6, o + 10, o + 14);
        dct2x2dn(p, o + 3, o + 7, o + 11, o + 15);
    }

    /**
     * Applies the inverse second-stage 4x4 core transform to the block DC coefficients of a macroblock.
     *
     * @param p the coefficients
     * @param o the index of the macroblock's first coefficient
     */
    static void idct4x4Stage2(int[] p, int o) {
        invOdd(p, o + 32, o + 48, o + 96, o + 112);
        invOdd(p, o + 128, o + 192, o + 144, o + 208);
        invOddOdd(p, o + 160, o + 224, o + 176, o + 240);
        dct2x2up(p, o, o + 64, o + 16, o + 80);
        dct2x2dn(p, o, o + 192, o + 48, o + 240);
        dct2x2dn(p, o + 64, o + 128, o + 112, o + 176);
        dct2x2dn(p, o + 16, o + 208, o + 32, o + 224);
        dct2x2dn(p, o + 80, o + 144, o + 96, o + 160);
    }

    /**
     * Doubles the block DC coefficients of a chroma macroblock under scaled arithmetic.
     *
     * @param p the coefficients
     * @param o the index of the macroblock's first coefficient
     * @param chroma whether the macroblock is of a chroma channel
     */
    static void normalize(int[] p, int o, boolean chroma) {
        if (chroma) {
            for (var i = 0; i < 256; i += 16) {
                p[o + i] += p[o + i];
            }
        }
    }

    /**
     * Applies the 2-point boundary overlap operator.
     */
    static void post2(int[] p, int a, int b) {
        p[b] += (p[a] + 4) >> 3;
        p[a] += (p[b] + 2) >> 2;
        p[b] += (p[a] + 4) >> 3;
    }

    /**
     * Applies the rescaled 2-point boundary overlap operator.
     */
    static void post2Alternate(int[] p, int pa, int pb) {
        var a = p[pa];
        var b = p[pb];
        b += (a + 2) >> 2;
        a += (b + 1) >> 1;
        a += b >> 5;
        a += b >> 9;
        a += b >> 13;
        b += (a + 2) >> 2;
        p[pa] = a;
        p[pb] = b;
    }

    /**
     * Applies the 2x2 overlap operator.
     */
    static void post2x2(int[] p, int pa, int pb, int pc, int pd) {
        var a = p[pa];
        var b = p[pb];
        var c = p[pc];
        var d = p[pd];
        a += d;
        b += c;
        d -= (a + 1) >> 1;
        c -= (b + 1) >> 1;
        b += (a + 2) >> 2;
        a += (b + 1) >> 1;
        b += (a + 2) >> 2;
        d += (a + 1) >> 1;
        c += (b + 1) >> 1;
        a -= d;
        b -= c;
        p[pa] = a;
        p[pb] = b;
        p[pc] = c;
        p[pd] = d;
    }

    /**
     * Applies the rescaled 2x2 overlap operator.
     */
    static void post2x2Alternate(int[] p, int pa, int pb, int pc, int pd) {
        var a = p[pa];
        var b = p[pb];
        var c = p[pc];
        var d = p[pd];
        a += d;
        b += c;
        d -= (a + 1) >> 1;
        c -= (b + 1) >> 1;
        b += (a + 2) >> 2;
        a += (b + 1) >> 1;
        a += b >> 5;
        a += b >> 9;
        a += b >> 13;
        b += (a + 2) >> 2;
        d += (a + 1) >> 1;
        c += (b + 1) >> 1;
        a -= d;
        b -= c;
        p[pa] = a;
        p[pb] = b;
        p[pc] = c;
        p[pd] = d;
    }

    /**
     * Applies the 4-point boundary overlap operator.
     */
    static void post4(int[] p, int pa, int pb, int pc, int pd) {
        var a = p[pa];
        var b = p[pb];
        var c = p[pc];
        var d = p[pd];
        a += d;
        b += c;
        d -= (a + 1) >> 1;
        c -= (b + 1) >> 1;
        c -= (d + 1) >> 1;
        d += (c + 1) >> 1;
        d += (a + 1) >> 1;
        c += (b + 1) >> 1;
        a -= d - ((d * 3 + 16) >> 5);
        b -= c - ((c * 3 + 16) >> 5);
        d += (a * 3 + 8) >> 4;
        c += (b * 3 + 8) >> 4;
        a += (d * 3 + 16) >> 5;
        b += (c * 3 + 16) >> 5;
        p[pa] = a;
        p[pb] = b;
        p[pc] = c;
        p[pd] = d;
    }

    /**
     * Applies the rescaled 4-point boundary overlap operator.
     */
    static void post4Alternate(int[] p, int pa, int pb, int pc, int pd) {
        var a = p[pa];
        var b = p[pb];
        var c = p[pc];
        var d = p[pd];
        a += d;
        b += c;
        d -= (a + 1) >> 1;
        c -= (b + 1) >> 1;
        // the edge scaling of (a, d) then (b, c)
        a += d;
        d = (a >> 1) - d;
        a += (d * 3) >> 3;
        d += (a * 3) >> 4;
        d += a >> 7;
        d -= a >> 10;
        a += (d * 3 + 4) >> 3;
        d -= a >> 1;
        a += d;
        d = -d;
        b += c;
        c = (b >> 1) - c;
        b += (c * 3) >> 3;
        c += (b * 3) >> 4;
        c += b >> 7;
        c -= b >> 10;
        b += (c * 3 + 4) >> 3;
        c -= b >> 1;
        b += c;
        c = -c;
        c -= (d + 1) >> 1;
        d += (c + 1) >> 1;
        d += (a + 1) >> 1;
        c += (b + 1) >> 1;
        a -= d;
        b -= c;
        p[pa] = a;
        p[pb] = b;
        p[pc] = c;
        p[pd] = d;
    }

    /**
     * Subtracts half a DC correction from the diagonal coefficients and adds it to the others.
     */
    private static void compensate(int[] p, int a, int b, int c, int d, int dc) {
        dc >>= 1;
        p[a] -= dc;
        p[d] -= dc;
        p[b] += dc;
        p[c] += dc;
    }

    /**
     * {@return the DC correction limited to the alternative estimate of the same sign}
     */
    private static int clipDcl(int dcl, int alternative) {
        if (dcl > 0) {
            return alternative > 0 ? Math.min(dcl, alternative) : 0;
        }
        if (dcl < 0) {
            return alternative < 0 ? Math.max(dcl, alternative) : 0;
        }
        return 0;
    }

    /**
     * Applies the first-stage 4x4 overlap operator across the corner of four blocks, two of them at {@code p0} and
     * two at {@code p1}, with the DC leakage compensation of subversion 0.
     *
     * @param p the coefficients
     * @param p0 the index of the upper pair
     * @param p1 the index of the lower pair
     * @param offset the distance correcting the block stride of chroma
     * @param hpQp the high-pass quantizer step
     * @param hpAbsent whether the high-pass band is absent
     */
    static void post4x4Stage1Split(int[] p, int p0, int p1, int offset, int hpQp, boolean hpAbsent) {
        var p2 = p0 + 72 - offset;
        var p3 = p1 + 64 - offset;
        p0 += 12;
        p1 += 4;
        for (var i = 0; i < 4; i++) {
            dct2x2dn(p, p0 + i, p2 + i, p1 + i, p3 + i);
        }
        invOddOddPost(p, p3, p3 + 1, p3 + 2, p3 + 3);
        rotate1(p, p1 + 2, p1 + 3);
        rotate1(p, p1, p1 + 1);
        rotate1(p, p2 + 1, p2 + 3);
        rotate1(p, p2, p2 + 2);
        for (var i = 0; i < 4; i++) {
            hst1(p, p0 + i, p3 + i);
        }
        for (var i = 0; i < 4; i++) {
            hst(p, p0 + i, p2 + i, p1 + i, p3 + i);
        }
        for (var i = 0; i < 4; i++) {
            var sum = (p[p0 + i] + p[p1 + i] + p[p2 + i] + p[p3 + i]) >> 1;
            var dcl = (sum * 595 + 65536) >> 17;
            if ((Math.abs(dcl) < hpQp && hpQp > 20) || hpAbsent) {
                var alternative = (p[p0 + i] - p[p1 + i] - p[p2 + i] + p[p3 + i]) >> 1;
                compensate(p, p0 + i, p2 + i, p1 + i, p3 + i, clipDcl(dcl, alternative));
            }
        }
    }

    /**
     * Applies {@link #post4x4Stage1Split} to two block pairs 16 coefficients apart.
     */
    static void post4x4Stage1(int[] p, int o, int offset, int hpQp, boolean hpAbsent) {
        post4x4Stage1Split(p, o, o + 16, offset, hpQp, hpAbsent);
    }

    /**
     * Applies the rescaled first-stage 4x4 overlap operator across the corner of four blocks.
     */
    static void post4x4Stage1SplitAlternate(int[] p, int p0, int p1, int offset) {
        var p2 = p0 + 72 - offset;
        var p3 = p1 + 64 - offset;
        p0 += 12;
        p1 += 4;
        for (var i = 0; i < 4; i++) {
            dct2x2dn(p, p0 + i, p2 + i, p1 + i, p3 + i);
        }
        invOddOddPost(p, p3, p3 + 1, p3 + 2, p3 + 3);
        rotate1(p, p1 + 2, p1 + 3);
        rotate1(p, p1, p1 + 1);
        rotate1(p, p2 + 1, p2 + 3);
        rotate1(p, p2, p2 + 2);
        for (var i = 0; i < 4; i++) {
            hst1Alternate(p, p0 + i, p3 + i);
        }
        for (var i = 0; i < 4; i++) {
            hst(p, p0 + i, p2 + i, p1 + i, p3 + i);
        }
    }

    /**
     * Applies {@link #post4x4Stage1SplitAlternate} to two block pairs 16 coefficients apart.
     */
    static void post4x4Stage1Alternate(int[] p, int o, int offset) {
        post4x4Stage1SplitAlternate(p, o, o + 16, offset);
    }

    /**
     * Applies the second-stage 4x4 overlap operator across the corner of four macroblocks.
     */
    static void post4x4Stage2Split(int[] p, int p0, int p1) {
        dct2x2dn(p, p0 - 96, p0 + 96, p1 - 112, p1 + 80);
        dct2x2dn(p, p0 - 32, p0 + 32, p1 - 48, p1 + 16);
        dct2x2dn(p, p0 - 80, p0 + 112, p1 - 128, p1 + 64);
        dct2x2dn(p, p0 - 16, p0 + 48, p1 - 64, p1);
        invOddOddPost(p, p1, p1 + 64, p1 + 16, p1 + 80);
        rotate1(p, p0 + 48, p0 + 32);
        rotate1(p, p0 + 112, p0 + 96);
        rotate1(p, p1 - 64, p1 - 128);
        rotate1(p, p1 - 48, p1 - 112);
        hst1(p, p0 - 96, p1 + 80);
        hst1(p, p0 - 32, p1 + 16);
        hst1(p, p0 - 80, p1 + 64);
        hst1(p, p0 - 16, p1);
        hst(p, p0 - 96, p1 - 112, p0 + 96, p1 + 80);
        hst(p, p0 - 32, p1 - 48, p0 + 32, p1 + 16);
        hst(p, p0 - 80, p1 - 128, p0 + 112, p1 + 64);
        hst(p, p0 - 16, p1 - 64, p0 + 48, p1);
    }

    /**
     * Applies the rescaled second-stage 4x4 overlap operator across the corner of four macroblocks.
     */
    static void post4x4Stage2SplitAlternate(int[] p, int p0, int p1) {
        dct2x2dn(p, p0 - 96, p0 + 96, p1 - 112, p1 + 80);
        dct2x2dn(p, p0 - 32, p0 + 32, p1 - 48, p1 + 16);
        dct2x2dn(p, p0 - 80, p0 + 112, p1 - 128, p1 + 64);
        dct2x2dn(p, p0 - 16, p0 + 48, p1 - 64, p1);
        invOddOddPost(p, p1, p1 + 64, p1 + 16, p1 + 80);
        rotate1(p, p0 + 48, p0 + 32);
        rotate1(p, p0 + 112, p0 + 96);
        rotate1(p, p1 - 64, p1 - 128);
        rotate1(p, p1 - 48, p1 - 112);
        hst1Alternate(p, p0 - 96, p1 + 80);
        hst1Alternate(p, p0 - 32, p1 + 16);
        hst1Alternate(p, p0 - 80, p1 + 64);
        hst1Alternate(p, p0 - 16, p1);
        hst(p, p0 - 96, p1 - 112, p0 + 96, p1 + 80);
        hst(p, p0 - 32, p1 - 48, p0 + 32, p1 + 16);
        hst(p, p0 - 80, p1 - 128, p0 + 112, p1 + 64);
        hst(p, p0 - 16, p1 - 64, p0 + 48, p1);
    }

    /**
     * Applies the rotation by -pi/8 of the overlap operators.
     */
    private static void rotate1(int[] p, int a, int b) {
        p[a] -= (p[b] + 1) >> 1;
        p[b] += (p[a] + 1) >> 1;
    }

    /**
     * Applies the first half of the Hadamard and scale operator.
     */
    private static void hst1(int[] p, int pa, int pd) {
        var a = p[pa];
        var d = p[pd];
        a += d;
        d = (a >> 1) - d;
        a += (d * 3) >> 3;
        d += (a * 3) >> 4;
        p[pa] = a;
        p[pd] = d;
    }

    /**
     * Applies the first half of the rescaled Hadamard and scale operator.
     */
    private static void hst1Alternate(int[] p, int pa, int pd) {
        var a = p[pa];
        var d = p[pd];
        a += d;
        d = (a >> 1) - d;
        a += (d * 3) >> 3;
        d += (a * 3) >> 4;
        d += a >> 7;
        d -= a >> 10;
        p[pa] = a;
        p[pd] = d;
    }

    /**
     * Applies the second half of the Hadamard and scale operator.
     */
    private static void hst(int[] p, int pa, int pb, int pc, int pd) {
        var a = p[pa];
        var b = p[pb];
        var c = p[pc];
        var d = p[pd];
        b -= c;
        a += (d * 3 + 4) >> 3;
        d -= b >> 1;
        c = ((a - b) >> 1) - c;
        p[pc] = d;
        p[pd] = c;
        p[pa] = a - c;
        p[pb] = b + d;
    }

    /**
     * Applies the inverse of the Kronecker product of two rotations by pi/8 of the core transform.
     */
    private static void invOddOdd(int[] p, int pa, int pb, int pc, int pd) {
        var a = p[pa];
        var b = p[pb];
        var c = p[pc];
        var d = p[pd];
        d += a;
        c -= b;
        var t1 = d >> 1;
        a -= t1;
        var t2 = c >> 1;
        b += t2;
        a -= (b * 3 + 3) >> 3;
        b += (a * 3 + 3) >> 2;
        a -= (b * 3 + 4) >> 3;
        b -= t2;
        a += t1;
        c += b;
        d -= a;
        p[pa] = a;
        p[pb] = -b;
        p[pc] = -c;
        p[pd] = d;
    }

    /**
     * Applies the inverse of the Kronecker product of two rotations by pi/8 of the overlap operators.
     */
    private static void invOddOddPost(int[] p, int pa, int pb, int pc, int pd) {
        var a = p[pa];
        var b = p[pb];
        var c = p[pc];
        var d = p[pd];
        d += a;
        c -= b;
        var t1 = d >> 1;
        a -= t1;
        var t2 = c >> 1;
        b += t2;
        a -= (b * 3 + 6) >> 3;
        b += (a * 3 + 2) >> 2;
        a -= (b * 3 + 4) >> 3;
        b -= t2;
        a += t1;
        c += b;
        d -= a;
        p[pa] = a;
        p[pb] = b;
        p[pc] = c;
        p[pd] = d;
    }

    /**
     * Applies the inverse of the Kronecker product of a rotation by -pi/8 and a Hadamard butterfly.
     */
    private static void invOdd(int[] p, int pa, int pb, int pc, int pd) {
        var a = p[pa];
        var b = p[pb];
        var c = p[pc];
        var d = p[pd];
        b += d;
        a -= c;
        d -= b >> 1;
        c += (a + 1) >> 1;
        a -= (b * 3 + 4) >> 3;
        b += (a * 3 + 4) >> 3;
        c -= (d * 3 + 4) >> 3;
        d += (c * 3 + 4) >> 3;
        c -= (b + 1) >> 1;
        d = ((a + 1) >> 1) - d;
        b += c;
        a -= d;
        p[pa] = a;
        p[pb] = b;
        p[pc] = c;
        p[pd] = d;
    }
}
