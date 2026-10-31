/* Ports libaom 3.13.1 (aom_dsp/variance.c, aom_dsp/sad.c and av1/encoder/av1_quantize.c: the high bit depth
   variants of the encoder's statistics). Copyright (c) 2016, Alliance for Open Media; BSD 2-Clause License.
   Modified for Smalt. */
package com.github.auties00.smalt.video.av1.internal.encoder;

/**
 * Implements the encoder's pixel statistics for 10-bit and 12-bit samples with 64-bit scalar
 * arithmetic.
 *
 * <p>Every measurement is returned at the 8-bit scale, so the thresholds and rate models tuned on
 * 8-bit content apply unchanged: sums of absolute differences, averages, projections and sums of
 * differences are divided by {@code 2^(bitDepth - 8)}, and squared errors by
 * {@code 4^(bitDepth - 8)}, each rounded to nearest. The kernels whose results are coded rather
 * than measured (the difference, the Hadamard and forward transforms, the quantizers and the sum of
 * absolute levels) keep the samples' own scale.
 *
 * @implNote This implementation normalizes as libaom's {@code highbd_10} and {@code highbd_12}
 * variance functions and {@code av1_highbd_block_error} do, and extends the rule to the sums of
 * absolute differences, the averages and the projections, which libaom leaves at the samples'
 * scale.
 */
final class HighBitDepthEncDsp extends EncDsp {
    /**
     * The bit depth above 8.
     */
    private final int shift;

    /**
     * Creates the kernels of a bit depth.
     *
     * @param bitDepth the bit depth, 10 or 12
     */
    HighBitDepthEncDsp(int bitDepth) {
        this.shift = bitDepth - 8;
    }

    /**
     * Divides a value by {@code 2^bits}, rounded to nearest, half away from minus infinity.
     *
     * @param value the dividend
     * @param bits the shift
     * @return the rounded quotient
     */
    private static long round(long value, int bits) {
        return bits == 0 ? value : (value + (1L << (bits - 1))) >> bits;
    }

    /**
     * Packs the normalized sums of squared and of signed differences like {@link EncDsp#sseSum}.
     *
     * @param sse the sum of squared differences at the samples' scale
     * @param sum the sum of differences at the samples' scale
     * @return the packed normalized sums
     */
    private long pack(long sse, long sum) {
        return round(sse, 2 * shift) << 32 | (round(sum, shift) & 0xFFFFFFFFL);
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
        return round(error, 2 * shift);
    }

    @Override
    int quantizeDz(int[] coeff, int n, int[] iscan, int[] qcoeff, int zbinDc, int zbinAc, int roundDc, int roundAc,
                   int quantDc, int quantAc, int shiftDc, int shiftAc, int limit) {
        var eob = 0;
        for (var i = 0; i < n; i++) {
            var c = coeff[i];
            var magnitude = Math.abs(c);
            var dc = i == 0;
            var level = 0;
            if (magnitude >= (dc ? zbinDc : zbinAc)) {
                long tmp = Math.min((long) magnitude + (dc ? roundDc : roundAc), limit);
                var t2 = ((tmp * (dc ? quantDc : quantAc)) >> 16) + tmp;
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
                long d = a[o + x] - b[o + x];
                sum += d * d;
            }
        }
        return round(sum, 2 * shift);
    }

    @Override
    int sad(short[] a, int aOff, int aStride, short[] b, int bOff, int bStride, int w, int h) {
        long sum = 0;
        for (var y = 0; y < h; y++) {
            var pa = aOff + y * aStride;
            var pb = bOff + y * bStride;
            for (var x = 0; x < w; x++) {
                sum += Math.abs(a[pa + x] - b[pb + x]);
            }
        }
        return (int) round(sum, shift);
    }

    @Override
    long sseSum(short[] a, int aOff, int aStride, short[] b, int bOff, int bStride, int w, int h) {
        long sse = 0;
        long sum = 0;
        for (var y = 0; y < h; y++) {
            var pa = aOff + y * aStride;
            var pb = bOff + y * bStride;
            for (var x = 0; x < w; x++) {
                long d = a[pa + x] - b[pb + x];
                sum += d;
                sse += d * d;
            }
        }
        return pack(sse, sum);
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
        return (int) round(((sumA + 32) >> 6) - ((sumB + 32) >> 6), shift);
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
        return (int) round((sum + 8) >> 4, shift);
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

    @Override
    void hadamard8x8(int[] diff, int diffOff, int stride, int[] coeff, int coeffOff, int[] scratch) {
        EncDsp.SCALAR.hadamard8x8(diff, diffOff, stride, coeff, coeffOff, scratch);
    }

    @Override
    void forwardTransform(FwdTxfm t, int txSize, int txType, int[] residual, int resOff, int resStride, int[] out) {
        t.forward(txSize, txType, residual, resOff, resStride, out);
    }

    @Override
    int quantizeLp(int[] coeff, int coeffOff, int n, int[] roundFp, int[] quantFp, int[] dequant, int[] scan,
                   int[] iscan, int[] qcoeff, int[] dqcoeff) {
        var eob = -1;
        for (var i = 0; i < n; i++) {
            var rc = scan[i];
            var c = coeff[coeffOff + rc];
            var ac = rc != 0 ? 1 : 0;
            var tmp = (int) ((((long) Math.abs(c) + roundFp[ac]) * quantFp[ac]) >> 16);
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
            long d = a[aOff + i] - b[i];
            err += d * d;
        }
        return round(err, 2 * shift);
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
        long sse = 0;
        long sum = 0;
        for (var y = 0; y < h; y++) {
            var o = y * w;
            var s = srcOff + y * srcStride;
            for (var x = 0; x < w; x++) {
                long d = ((scratch[o + x] * g0 + scratch[o + w + x] * g1 + 64) >> 7) - src[s + x];
                sum += d;
                sse += d * d;
            }
        }
        return pack(sse, sum);
    }

    @Override
    void intProRow(int[] hbuf, short[] ref, int refOff, int refStride, int width, int height, int norm) {
        for (var idx = 0; idx < width; idx++) {
            var sum = 0;
            for (var i = 0; i < height; i++) {
                sum += ref[refOff + idx + i * refStride];
            }
            hbuf[idx] = (int) round(sum >> norm, shift);
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
            vbuf[ht] = (int) round(sum >> norm, shift);
        }
    }
}
