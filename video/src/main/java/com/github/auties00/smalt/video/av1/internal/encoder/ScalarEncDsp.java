package com.github.auties00.smalt.video.av1.internal.encoder;

/**
 * Implements the encoder's pixel statistics with plain scalar loops; the reference the vectorized
 * kernels must match bit for bit.
 */
final class ScalarEncDsp extends EncDsp {
    /**
     * Creates the kernels.
     */
    ScalarEncDsp() {
    }

    @Override
    long levelError(int[] coeff, int[] levels, int n, int dcDq, int acDq, int logScale) {
        long error = 0;
        for (var i = 0; i < n; i++) {
            var level = levels[i];
            long d = ((long) Math.abs(level) * (i == 0 ? dcDq : acDq)) >> logScale;
            long e = coeff[i] - (level < 0 ? -d : d);
            error += e * e;
        }
        return error;
    }

    @Override
    int quantizeDz(int[] coeff, int n, int[] iscan, int[] qcoeff, int zbinDc, int zbinAc, int roundDc,
                          int roundAc, int quantDc, int quantAc, int shiftDc, int shiftAc, int limit) {
        var eob = 0;
        for (var i = 0; i < n; i++) {
            var c = coeff[i];
            var magnitude = Math.abs(c);
            var dc = i == 0;
            var level = 0;
            if (magnitude >= (dc ? zbinDc : zbinAc)) {
                long tmp = Math.min((long) magnitude + (dc ? roundDc : roundAc), limit);
                var t2 = (((tmp * (dc ? quantDc : quantAc)) >> 16) + tmp);
                level = (int) (t2 >> (dc ? shiftDc : shiftAc));
            }
            qcoeff[i] = c < 0 ? -level : level;
            if (level != 0 && iscan[i] > eob) {
                eob = iscan[i];
            }
        }
        return eob;
    }

    @Override
    long planeSse(short[] a, short[] b, int off, int stride, int w, int h) {
        long sum = 0;
        for (var y = 0; y < h; y++) {
            var o = off + y * stride;
            for (var x = 0; x < w; x++) {
                var d = a[o + x] - b[o + x];
                sum += d * d;
            }
        }
        return sum;
    }

    @Override
    int sad(short[] a, int aOff, int aStride, short[] b, int bOff, int bStride, int w, int h) {
        var sum = 0;
        for (var y = 0; y < h; y++) {
            var pa = aOff + y * aStride;
            var pb = bOff + y * bStride;
            for (var x = 0; x < w; x++) {
                sum += Math.abs(a[pa + x] - b[pb + x]);
            }
        }
        return sum;
    }

    @Override
    long sseSum(short[] a, int aOff, int aStride, short[] b, int bOff, int bStride, int w, int h) {
        var sse = 0;
        var sum = 0;
        for (var y = 0; y < h; y++) {
            var pa = aOff + y * aStride;
            var pb = bOff + y * bStride;
            for (var x = 0; x < w; x++) {
                var d = a[pa + x] - b[pb + x];
                sum += d;
                sse += d * d;
            }
        }
        return (long) sse << 32 | (sum & 0xFFFFFFFFL);
    }

    @Override
    int avgDiff8x8(short[] a, int aOff, int aStride, short[] b, int bOff, int bStride) {
        var sumA = 0;
        var sumB = 0;
        for (var y = 0; y < 8; y++) {
            var p = aOff + y * aStride;
            var q = bOff + y * bStride;
            for (var x = 0; x < 8; x++) {
                sumA += a[p + x];
                sumB += b[q + x];
            }
        }
        return ((sumA + 32) >> 6) - ((sumB + 32) >> 6);
    }

    @Override
    int avg4x4(short[] s, int off, int stride) {
        var sum = 0;
        for (var y = 0; y < 4; y++) {
            var p = off + y * stride;
            for (var x = 0; x < 4; x++) {
                sum += s[p + x];
            }
        }
        return (sum + 8) >> 4;
    }

    @Override
    void subtract(short[] src, int srcOff, int srcStride, short[] pred, int predOff, int predStride, int[] diff,
                         int w, int h) {
        for (var y = 0; y < h; y++) {
            var ps = srcOff + y * srcStride;
            var pp = predOff + y * predStride;
            var pd = y * w;
            for (var x = 0; x < w; x++) {
                diff[pd + x] = src[ps + x] - pred[pp + x];
            }
        }
    }

    /**
     * Applies the eight-point Hadamard butterfly to one column or row ({@code hadamard_col8}).
     *
     * @param in the input
     * @param inOff the offset of the first element
     * @param stride the distance between elements
     * @param out receives eight values
     * @param outOff the offset of the first output
     * @param os the distance between outputs
     */
    private static void col8(int[] in, int inOff, int stride, int[] out, int outOff, int os) {
        var b0 = in[inOff] + in[inOff + stride];
        var b1 = in[inOff] - in[inOff + stride];
        var b2 = in[inOff + 2 * stride] + in[inOff + 3 * stride];
        var b3 = in[inOff + 2 * stride] - in[inOff + 3 * stride];
        var b4 = in[inOff + 4 * stride] + in[inOff + 5 * stride];
        var b5 = in[inOff + 4 * stride] - in[inOff + 5 * stride];
        var b6 = in[inOff + 6 * stride] + in[inOff + 7 * stride];
        var b7 = in[inOff + 6 * stride] - in[inOff + 7 * stride];
        var c0 = b0 + b2;
        var c1 = b1 + b3;
        var c2 = b0 - b2;
        var c3 = b1 - b3;
        var c4 = b4 + b6;
        var c5 = b5 + b7;
        var c6 = b4 - b6;
        var c7 = b5 - b7;
        out[outOff] = c0 + c4;
        out[outOff + 7 * os] = c1 + c5;
        out[outOff + 3 * os] = c2 + c6;
        out[outOff + 4 * os] = c3 + c7;
        out[outOff + 2 * os] = c0 - c4;
        out[outOff + 6 * os] = c1 - c5;
        out[outOff + 1 * os] = c2 - c6;
        out[outOff + 5 * os] = c3 - c7;
    }

    @Override
    void hadamard8x8(int[] diff, int diffOff, int stride, int[] coeff, int coeffOff, int[] scratch) {
        for (var idx = 0; idx < 8; idx++) {
            col8(diff, diffOff + idx, stride, scratch, idx * 8, 1);
        }
        for (var idx = 0; idx < 8; idx++) {
            col8(scratch, idx, 8, coeff, coeffOff + idx, 8);
        }
    }

    @Override
    int quantizeLp(int[] coeff, int coeffOff, int n, int[] roundFp, int[] quantFp, int[] dequant, int[] scan,
                          int[] iscan, int[] qcoeff, int[] dqcoeff) {
        var eob = -1;
        for (var i = 0; i < n; i++) {
            var rc = scan[i];
            var c = coeff[coeffOff + rc];
            var ac = rc != 0 ? 1 : 0;
            var tmp = Math.clamp(Math.abs(c) + roundFp[ac], Short.MIN_VALUE, Short.MAX_VALUE);
            tmp = (tmp * quantFp[ac]) >> 16;
            var q = c < 0 ? -tmp : tmp;
            qcoeff[rc] = q;
            dqcoeff[rc] = q * dequant[ac];
            if (tmp != 0) {
                eob = i;
            }
        }
        return eob + 1;
    }

    @Override
    void forwardTransform(FwdTxfm t, int txSize, int txType, int[] residual, int resOff, int resStride,
                                 int[] out) {
        t.forward(txSize, txType, residual, resOff, resStride, out);
    }

    @Override
    int satd(int[] c, int n) {
        var sum = 0;
        for (var i = 0; i < n; i++) {
            sum += Math.abs(c[i]);
        }
        return sum;
    }

    @Override
    long blockError(int[] a, int aOff, int[] b, int n) {
        var err = 0L;
        for (var i = 0; i < n; i++) {
            var d = a[aOff + i] - b[i];
            err += (long) d * d;
        }
        return err;
    }

    @Override
    long subpelSseSum(short[] ref, int refOff, int refStride, int xoff, int yoff, short[] src, int srcOff,
                             int srcStride, int w, int h, short[] scratch) {
        var f0 = BILINEAR[xoff * 2];
        var f1 = BILINEAR[xoff * 2 + 1];
        for (var y = 0; y < h + 1; y++) {
            var p = refOff + y * refStride;
            var o = y * w;
            for (var x = 0; x < w; x++) {
                scratch[o + x] = (short) ((ref[p + x] * f0 + ref[p + x + 1] * f1 + 64) >> 7);
            }
        }
        var g0 = BILINEAR[yoff * 2];
        var g1 = BILINEAR[yoff * 2 + 1];
        var sse = 0;
        var sum = 0;
        for (var y = 0; y < h; y++) {
            var o = y * w;
            var s = srcOff + y * srcStride;
            for (var x = 0; x < w; x++) {
                var v = (scratch[o + x] * g0 + scratch[o + w + x] * g1 + 64) >> 7;
                var d = v - src[s + x];
                sum += d;
                sse += d * d;
            }
        }
        return (long) sse << 32 | (sum & 0xFFFFFFFFL);
    }

    @Override
    void intProRow(int[] hbuf, short[] ref, int refOff, int refStride, int width, int height, int norm) {
        for (var idx = 0; idx < width; idx++) {
            var sum = 0;
            for (var i = 0; i < height; i++) {
                sum += ref[refOff + idx + i * refStride];
            }
            hbuf[idx] = sum >> norm;
        }
    }

    @Override
    void intProCol(int[] vbuf, short[] ref, int refOff, int refStride, int width, int height, int norm) {
        for (var ht = 0; ht < height; ht++) {
            var sum = 0;
            var p = refOff + ht * refStride;
            for (var idx = 0; idx < width; idx++) {
                sum += ref[p + idx];
            }
            vbuf[ht] = sum >> norm;
        }
    }
}
