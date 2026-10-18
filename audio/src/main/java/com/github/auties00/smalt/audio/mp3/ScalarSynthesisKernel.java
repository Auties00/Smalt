package com.github.auties00.smalt.audio.mp3;

/**
 * Implements the synthesis filterbank stages with scalar arithmetic; the reference for the vector
 * implementation.
 *
 * @implNote This implementation ports minimp3's {@code mp3d_DCT_II} and {@code mp3d_synth}
 * (CC0). Where minimp3's SIMD and scalar paths add three terms in different orders, the SIMD
 * order is used so that both kernels of this module agree bit for bit.
 */
final class ScalarSynthesisKernel implements SynthesisKernel {
    /**
     * The factor that scales the window output (16-bit range) to {@code [-1, 1]}.
     */
    static final float OUTPUT_SCALE = 1.0f / 32768.0f;

    /**
     * The four butterfly rows of eight values each of one DCT column.
     */
    private final float[] rows = new float[32];

    /**
     * The scratch area of the 9-point DCTs of the long IMDCT.
     */
    private final float[] dctScratch = new float[18];

    @Override
    public void dct(float[] grbuf, int base, int slots) {
        var sec = Mp3Tables.DCT_SECANTS;
        var t = rows;
        for (var k = 0; k < slots; k++) {
            var y = base + k;
            for (var i = 0; i < 8; i++) {
                var x0 = grbuf[y + i * 18];
                var x1 = grbuf[y + (15 - i) * 18];
                var x2 = grbuf[y + (16 + i) * 18];
                var x3 = grbuf[y + (31 - i) * 18];
                var t0 = x0 + x3;
                var t1 = x1 + x2;
                var t2 = (x1 - x2) * sec[3 * i];
                var t3 = (x0 - x3) * sec[3 * i + 1];
                t[i] = t0 + t1;
                t[8 + i] = (t0 - t1) * sec[3 * i + 2];
                t[16 + i] = t3 + t2;
                t[24 + i] = (t3 - t2) * sec[3 * i + 2];
            }
            for (var r = 0; r < 32; r += 8) {
                var x0 = t[r];
                var x1 = t[r + 1];
                var x2 = t[r + 2];
                var x3 = t[r + 3];
                var x4 = t[r + 4];
                var x5 = t[r + 5];
                var x6 = t[r + 6];
                var x7 = t[r + 7];
                var xt = x0 - x7;
                x0 += x7;
                x7 = x1 - x6;
                x1 += x6;
                x6 = x2 - x5;
                x2 += x5;
                x5 = x3 - x4;
                x3 += x4;
                x4 = x0 - x3;
                x0 += x3;
                x3 = x1 - x2;
                x1 += x2;
                t[r] = x0 + x1;
                t[r + 4] = (x0 - x1) * 0.70710677f;
                x5 = x5 + x6;
                x6 = (x6 + x7) * 0.70710677f;
                x7 = x7 + xt;
                x3 = (x3 + x4) * 0.70710677f;
                // rotation by pi/8 as three shears
                x5 -= x7 * 0.198912367f;
                x7 += x5 * 0.382683432f;
                x5 -= x7 * 0.198912367f;
                x0 = xt - x6;
                xt += x6;
                t[r + 1] = (xt + x7) * 0.50979561f;
                t[r + 2] = (x4 + x3) * 0.54119611f;
                t[r + 3] = (x0 - x5) * 0.60134488f;
                t[r + 5] = (x0 + x5) * 0.89997619f;
                t[r + 6] = (x4 - x3) * 1.30656302f;
                t[r + 7] = (xt - x7) * 2.56291556f;
            }
            for (var i = 0; i < 7; i++, y += 4 * 18) {
                var s = t[24 + i] + t[24 + i + 1];
                grbuf[y] = t[i];
                grbuf[y + 18] = t[16 + i] + s;
                grbuf[y + 36] = t[8 + i] + t[8 + i + 1];
                grbuf[y + 54] = t[16 + i + 1] + s;
            }
            grbuf[y] = t[7];
            grbuf[y + 18] = t[16 + 7] + t[24 + 7];
            grbuf[y + 36] = t[8 + 7];
            grbuf[y + 54] = t[24 + 7];
        }
    }

    @Override
    public void window(float[] grbuf, int left, int right, float[] outLeft, float[] outRight, int outOffset,
                       float[] lins, int linsOffset) {
        var z = linsOffset + 15 * 64;
        lins[z + 4 * 15] = grbuf[left + 18 * 16];
        lins[z + 4 * 15 + 1] = grbuf[right + 18 * 16];
        lins[z + 4 * 15 + 2] = grbuf[left];
        lins[z + 4 * 15 + 3] = grbuf[right];
        lins[z + 4 * 31] = grbuf[left + 1 + 18 * 16];
        lins[z + 4 * 31 + 1] = grbuf[right + 1 + 18 * 16];
        lins[z + 4 * 31 + 2] = grbuf[left + 1];
        lins[z + 4 * 31 + 3] = grbuf[right + 1];
        pair(outRight, outOffset, lins, linsOffset + 4 * 15 + 1);
        pair(outRight, outOffset + 32, lins, linsOffset + 4 * 15 + 64 + 1);
        pair(outLeft, outOffset, lins, linsOffset + 4 * 15);
        pair(outLeft, outOffset + 32, lins, linsOffset + 4 * 15 + 64);
        var w = Mp3Tables.SYNTH_WINDOW;
        var wi = 0;
        for (var i = 14; i >= 0; i--) {
            var zi = z + 4 * i;
            lins[zi] = grbuf[left + 18 * (31 - i)];
            lins[zi + 1] = grbuf[right + 18 * (31 - i)];
            lins[zi + 2] = grbuf[left + 1 + 18 * (31 - i)];
            lins[zi + 3] = grbuf[right + 1 + 18 * (31 - i)];
            lins[zi + 64] = grbuf[left + 1 + 18 * (1 + i)];
            lins[zi + 64 + 1] = grbuf[right + 1 + 18 * (1 + i)];
            lins[zi - 64 + 2] = grbuf[left + 18 * (1 + i)];
            lins[zi - 64 + 3] = grbuf[right + 18 * (1 + i)];
            float a0 = 0, a1 = 0, a2 = 0, a3 = 0, b0 = 0, b1 = 0, b2 = 0, b3 = 0;
            for (var k = 0; k < 8; k++) {
                var w0 = w[wi++];
                var w1 = w[wi++];
                var vz = zi - k * 64;
                var vy = zi - (15 - k) * 64;
                var z0 = lins[vz];
                var z1 = lins[vz + 1];
                var z2 = lins[vz + 2];
                var z3 = lins[vz + 3];
                var y0 = lins[vy];
                var y1 = lins[vy + 1];
                var y2 = lins[vy + 2];
                var y3 = lins[vy + 3];
                if (k == 0) {
                    b0 = z0 * w1 + y0 * w0;
                    b1 = z1 * w1 + y1 * w0;
                    b2 = z2 * w1 + y2 * w0;
                    b3 = z3 * w1 + y3 * w0;
                    a0 = z0 * w0 - y0 * w1;
                    a1 = z1 * w0 - y1 * w1;
                    a2 = z2 * w0 - y2 * w1;
                    a3 = z3 * w0 - y3 * w1;
                } else if ((k & 1) == 0) {
                    b0 += z0 * w1 + y0 * w0;
                    b1 += z1 * w1 + y1 * w0;
                    b2 += z2 * w1 + y2 * w0;
                    b3 += z3 * w1 + y3 * w0;
                    a0 += z0 * w0 - y0 * w1;
                    a1 += z1 * w0 - y1 * w1;
                    a2 += z2 * w0 - y2 * w1;
                    a3 += z3 * w0 - y3 * w1;
                } else {
                    b0 += z0 * w1 + y0 * w0;
                    b1 += z1 * w1 + y1 * w0;
                    b2 += z2 * w1 + y2 * w0;
                    b3 += z3 * w1 + y3 * w0;
                    a0 += y0 * w1 - z0 * w0;
                    a1 += y1 * w1 - z1 * w0;
                    a2 += y2 * w1 - z2 * w0;
                    a3 += y3 * w1 - z3 * w0;
                }
            }
            outRight[outOffset + 15 - i] = a1 * OUTPUT_SCALE;
            outRight[outOffset + 17 + i] = b1 * OUTPUT_SCALE;
            outLeft[outOffset + 15 - i] = a0 * OUTPUT_SCALE;
            outLeft[outOffset + 17 + i] = b0 * OUTPUT_SCALE;
            outRight[outOffset + 47 - i] = a3 * OUTPUT_SCALE;
            outRight[outOffset + 49 + i] = b3 * OUTPUT_SCALE;
            outLeft[outOffset + 47 - i] = a2 * OUTPUT_SCALE;
            outLeft[outOffset + 49 + i] = b2 * OUTPUT_SCALE;
        }
    }

    /**
     * Computes the two output samples of a time slot pair that fall on the symmetry points of the
     * window (sample 0 and sample 16 of each 32-sample block).
     *
     * @param out    the output plane
     * @param offset the index of the output sample 0
     * @param z      the filterbank history
     * @param zi     the index of the history lane holding the slot
     */
    static void pair(float[] out, int offset, float[] z, int zi) {
        var a = (z[zi + 14 * 64] - z[zi]) * 29;
        a += (z[zi + 64] + z[zi + 13 * 64]) * 213;
        a += (z[zi + 12 * 64] - z[zi + 2 * 64]) * 459;
        a += (z[zi + 3 * 64] + z[zi + 11 * 64]) * 2037;
        a += (z[zi + 10 * 64] - z[zi + 4 * 64]) * 5153;
        a += (z[zi + 5 * 64] + z[zi + 9 * 64]) * 6574;
        a += (z[zi + 8 * 64] - z[zi + 6 * 64]) * 37489;
        a += z[zi + 7 * 64] * 75038;
        out[offset] = a * OUTPUT_SCALE;
        zi += 2;
        a = z[zi + 14 * 64] * 104;
        a += z[zi + 12 * 64] * 1567;
        a += z[zi + 10 * 64] * 9727;
        a += z[zi + 8 * 64] * 64019;
        a += z[zi + 6 * 64] * -9975;
        a += z[zi + 4 * 64] * -45;
        a += z[zi + 2 * 64] * 146;
        a += z[zi] * -5;
        out[offset + 16] = a * OUTPUT_SCALE;
    }

    /**
     * Computes the 9-point DCT-III used by the 36-point IMDCT, in place.
     *
     * @param y      the values
     * @param offset the index of the first value
     */
    static void dct3x9(float[] y, int offset) {
        var s0 = y[offset];
        var s2 = y[offset + 2];
        var s4 = y[offset + 4];
        var s6 = y[offset + 6];
        var s8 = y[offset + 8];
        var t0 = s0 + s6 * 0.5f;
        s0 -= s6;
        var t4 = (s4 + s2) * 0.93969262f;
        var t2 = (s8 + s2) * 0.76604444f;
        s6 = (s4 - s8) * 0.17364818f;
        s4 += s8 - s2;
        s2 = s0 - s4 * 0.5f;
        y[offset + 4] = s4 + s0;
        s8 = t0 - t2 + s6;
        s0 = t0 - t4 + t2;
        s4 = t0 + t4 - s6;
        var s1 = y[offset + 1];
        var s3 = y[offset + 3];
        var s5 = y[offset + 5];
        var s7 = y[offset + 7];
        s3 *= 0.86602540f;
        t0 = (s5 + s1) * 0.98480775f;
        t4 = (s5 - s7) * 0.34202014f;
        t2 = (s1 + s7) * 0.64278761f;
        s1 = (s1 - s5 - s7) * 0.86602540f;
        s5 = t0 - s3 - t2;
        s7 = t4 - s3 - t0;
        s3 = t4 + s3 - t2;
        y[offset] = s4 - s7;
        y[offset + 1] = s2 + s1;
        y[offset + 2] = s0 - s3;
        y[offset + 3] = s8 + s5;
        y[offset + 5] = s8 - s5;
        y[offset + 6] = s0 + s3;
        y[offset + 7] = s2 - s1;
        y[offset + 8] = s4 + s7;
    }

    @Override
    public void imdct36(float[] grbuf, int base, float[] overlap, int ovl, float[] window, int bands) {
        var tw = Mp3Tables.TWIDDLE_36;
        var t = dctScratch;
        for (var j = 0; j < bands; j++, base += 18, ovl += 9) {
            t[0] = -grbuf[base];
            t[9] = grbuf[base + 17];
            for (var i = 0; i < 4; i++) {
                t[9 + 8 - 2 * i] = grbuf[base + 4 * i + 1] - grbuf[base + 4 * i + 2];
                t[1 + 2 * i] = grbuf[base + 4 * i + 1] + grbuf[base + 4 * i + 2];
                t[9 + 7 - 2 * i] = grbuf[base + 4 * i + 4] - grbuf[base + 4 * i + 3];
                t[2 + 2 * i] = -(grbuf[base + 4 * i + 3] + grbuf[base + 4 * i + 4]);
            }
            dct3x9(t, 0);
            dct3x9(t, 9);
            t[9 + 1] = -t[9 + 1];
            t[9 + 3] = -t[9 + 3];
            t[9 + 5] = -t[9 + 5];
            t[9 + 7] = -t[9 + 7];
            for (var i = 0; i < 9; i++) {
                var previous = overlap[ovl + i];
                var co = t[i];
                var si = t[9 + i];
                var sum = co * tw[9 + i] + si * tw[i];
                overlap[ovl + i] = co * tw[i] - si * tw[9 + i];
                grbuf[base + i] = previous * window[i] - sum * window[9 + i];
                grbuf[base + 17 - i] = previous * window[9 + i] + sum * window[i];
            }
        }
    }
}
