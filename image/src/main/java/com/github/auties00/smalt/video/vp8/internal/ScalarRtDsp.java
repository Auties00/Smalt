/* Ports libvpx 1.17.0 (vpx_dsp/sad.c, vpx_dsp/variance.c, vp8/common/idctllm.c). Copyright (c) 2010 The WebM project
   authors; BSD 3-Clause License.
   Ports libwebp 1.6.0 (src/dsp/enc.c: FTransform_C, QuantizeBlock_C). Copyright (c) 2010, Google Inc.; BSD 3-Clause
   License. Modified for Smalt. */
package com.github.auties00.smalt.video.vp8.internal;

/**
 * Implements {@link RtDsp} in plain Java; the reference implementation for {@link VectorRtDsp}.
 */
public final class ScalarRtDsp extends RtDsp {
    /**
     * Creates the scalar implementation.
     */
    public ScalarRtDsp() {
    }

    @Override
    public int sad16(byte[] a, int aOff, int aStride, byte[] b, int bOff, int bStride) {
        var sad = 0;
        for (var r = 0; r < 16; r++, aOff += aStride, bOff += bStride) {
            for (var c = 0; c < 16; c++) {
                sad += Math.abs((a[aOff + c] & 0xFF) - (b[bOff + c] & 0xFF));
            }
        }
        return sad;
    }

    @Override
    public int blockStats16(byte[] a, int aOff, int aStride, int[] columns) {
        var squares = 0;
        for (var c = 0; c < 16; c++) {
            columns[c] = 0;
        }
        for (var r = 0; r < 16; r++, aOff += aStride) {
            for (var c = 0; c < 16; c++) {
                var v = a[aOff + c] & 0xFF;
                columns[c] += v;
                squares += v * v;
            }
        }
        return squares;
    }

    @Override
    public void chromaIntraErrors(byte[] src, int srcOff, int srcStride, byte[] edges, int dcU, int dcV, int[] out) {
        var dc = 0;
        var vertical = 0;
        var horizontal = 0;
        var trueMotion = 0;
        for (var r = 0; r < 8; r++) {
            for (var c = 0; c < 16; c++) {
                var plane = c >> 3;
                var p = src[srcOff + r * srcStride + c] & 0xFF;
                var above = edges[c] & 0xFF;
                var left = edges[16 + plane * 8 + r] & 0xFF;
                var corner = edges[32 + plane] & 0xFF;
                var d = p - (plane == 0 ? dcU : dcV);
                dc += d * d;
                d = p - above;
                vertical += d * d;
                d = p - left;
                horizontal += d * d;
                d = p - Math.clamp(left + above - corner, 0, 255);
                trueMotion += d * d;
            }
        }
        out[0] = dc;
        out[1] = vertical;
        out[2] = horizontal;
        out[3] = trueMotion;
    }

    @Override
    public void sad16x3(byte[] a, int aOff, int aStride, byte[] b, int bOff0, int bOff1, int bOff2, int bStride,
                        int[] out, int outOff) {
        out[outOff] = sad16(a, aOff, aStride, b, bOff0, bStride);
        out[outOff + 1] = sad16(a, aOff, aStride, b, bOff1, bStride);
        out[outOff + 2] = sad16(a, aOff, aStride, b, bOff2, bStride);
    }

    @Override
    public void sad16x4(byte[] a, int aOff, int aStride, byte[] b, int bOff0, int bOff1, int bOff2, int bOff3,
                        int bStride, int[] out, int outOff) {
        out[outOff] = sad16(a, aOff, aStride, b, bOff0, bStride);
        out[outOff + 1] = sad16(a, aOff, aStride, b, bOff1, bStride);
        out[outOff + 2] = sad16(a, aOff, aStride, b, bOff2, bStride);
        out[outOff + 3] = sad16(a, aOff, aStride, b, bOff3, bStride);
    }

    @Override
    public long variance16(byte[] a, int aOff, int aStride, byte[] b, int bOff, int bStride) {
        return variance(a, aOff, aStride, b, bOff, bStride, 16);
    }

    /**
     * Returns the packed sums of the differences of two square blocks.
     *
     * @param a       the first samples
     * @param aOff    the index of the first block's top-left sample
     * @param aStride the first block's row stride
     * @param b       the second samples
     * @param bOff    the index of the second block's top-left sample
     * @param bStride the second block's row stride
     * @param size    the block side
     * @return the packed sums
     */
    private static long variance(byte[] a, int aOff, int aStride, byte[] b, int bOff, int bStride, int size) {
        var sse = 0;
        var sum = 0;
        for (var r = 0; r < size; r++, aOff += aStride, bOff += bStride) {
            for (var c = 0; c < size; c++) {
                var d = (a[aOff + c] & 0xFF) - (b[bOff + c] & 0xFF);
                sum += d;
                sse += d * d;
            }
        }
        return pack(sse, sum);
    }

    @Override
    public long subpelVariance16(byte[] ref, int refOff, int refStride, int xOffset, int yOffset, byte[] b, int bOff,
                                 int bStride, short[] scratch) {
        return subpelVariance(ref, refOff, refStride, xOffset, yOffset, b, bOff, bStride, scratch, 16);
    }

    /**
     * Filters a square block with the bilinear filters and returns the packed sums of its
     * differences with a second block.
     *
     * @param ref       the reference samples
     * @param refOff    the index of the reference block's top-left sample
     * @param refStride the reference row stride
     * @param xOffset   the horizontal offset in 1/8 pixel
     * @param yOffset   the vertical offset in 1/8 pixel
     * @param b         the second samples
     * @param bOff      the index of the second block's top-left sample
     * @param bStride   the second block's row stride
     * @param scratch   the intermediate rows
     * @param size      the block side
     * @return the packed sums
     */
    private static long subpelVariance(byte[] ref, int refOff, int refStride, int xOffset, int yOffset, byte[] b,
                                       int bOff, int bStride, short[] scratch, int size) {
        var h0 = BILINEAR_TAPS[xOffset * 2];
        var h1 = BILINEAR_TAPS[xOffset * 2 + 1];
        for (var r = 0; r <= size; r++) {
            var row = refOff + r * refStride;
            for (var c = 0; c < size; c++) {
                scratch[r * size + c] = (short) (((ref[row + c] & 0xFF) * h0 + (ref[row + c + 1] & 0xFF) * h1 + 64) >> 7);
            }
        }
        var v0 = BILINEAR_TAPS[yOffset * 2];
        var v1 = BILINEAR_TAPS[yOffset * 2 + 1];
        var sse = 0;
        var sum = 0;
        for (var r = 0; r < size; r++, bOff += bStride) {
            for (var c = 0; c < size; c++) {
                var p = (scratch[r * size + c] * v0 + scratch[(r + 1) * size + c] * v1 + 64) >> 7;
                var d = p - (b[bOff + c] & 0xFF);
                sum += d;
                sse += d * d;
            }
        }
        return pack(sse, sum);
    }

    @Override
    public void horizontalPass16(byte[] ref, int refOff, int refStride, int xOffset, short[] scratch) {
        var h0 = BILINEAR_TAPS[xOffset * 2];
        var h1 = BILINEAR_TAPS[xOffset * 2 + 1];
        for (var r = 0; r <= 16; r++) {
            var row = refOff + r * refStride;
            for (var c = 0; c < 16; c++) {
                scratch[r * 16 + c] = (short) (((ref[row + c] & 0xFF) * h0 + (ref[row + c + 1] & 0xFF) * h1 + 64) >> 7);
            }
        }
    }

    @Override
    public long verticalPassVariance16(short[] scratch, int yOffset, byte[] b, int bOff, int bStride) {
        var v0 = BILINEAR_TAPS[yOffset * 2];
        var v1 = BILINEAR_TAPS[yOffset * 2 + 1];
        var sse = 0;
        var sum = 0;
        for (var r = 0; r < 16; r++, bOff += bStride) {
            for (var c = 0; c < 16; c++) {
                var p = (scratch[r * 16 + c] * v0 + scratch[(r + 1) * 16 + c] * v1 + 64) >> 7;
                var d = p - (b[bOff + c] & 0xFF);
                sum += d;
                sse += d * d;
            }
        }
        return pack(sse, sum);
    }

    @Override
    public void groupMacroblock(byte[] y, int yOff, int yStride, byte[] u, byte[] v, int uvOff, int uvStride,
                                byte[] groups) {
        for (var h = 0; h < 2; h++) {
            for (var r = 0; r < 4; r++) {
                var upper = yOff + (8 * h + r) * yStride;
                var lower = upper + 4 * yStride;
                var g = h * GROUP + 32 * r;
                System.arraycopy(y, upper, groups, g, 16);
                System.arraycopy(y, lower, groups, g + 16, 16);
            }
        }
        for (var r = 0; r < 4; r++) {
            var upper = uvOff + r * uvStride;
            var lower = upper + 4 * uvStride;
            var g = 2 * GROUP + 32 * r;
            System.arraycopy(u, upper, groups, g, 8);
            System.arraycopy(u, lower, groups, g + 8, 8);
            System.arraycopy(v, upper, groups, g + 16, 8);
            System.arraycopy(v, lower, groups, g + 24, 8);
        }
    }

    @Override
    public void halfpelHorizontalPair(byte[] ref, int refOff, int refStride, byte[] b, int bOff, int bStride,
                                      long[] out) {
        out[0] = halfpel(ref, refOff - 1, 1, refStride, b, bOff, bStride);
        out[1] = halfpel(ref, refOff, 1, refStride, b, bOff, bStride);
    }

    @Override
    public void halfpelVerticalPair(byte[] ref, int refOff, int refStride, byte[] b, int bOff, int bStride,
                                    long[] out) {
        out[0] = halfpel(ref, refOff - refStride, refStride, refStride, b, bOff, bStride);
        out[1] = halfpel(ref, refOff, refStride, refStride, b, bOff, bStride);
    }

    /**
     * Filters a 16x16 reference block with the half-pixel bilinear filter in one direction and
     * returns the packed sums of its differences with a second block.
     *
     * @param ref       the reference samples
     * @param refOff    the index of the reference block's top-left sample
     * @param step      the distance between the two filtered samples: 1 or the row stride
     * @param refStride the reference row stride
     * @param b         the second samples
     * @param bOff      the index of the second block's top-left sample
     * @param bStride   the second block's row stride
     * @return the packed sums
     */
    private static long halfpel(byte[] ref, int refOff, int step, int refStride, byte[] b, int bOff, int bStride) {
        var sse = 0;
        var sum = 0;
        for (var r = 0; r < 16; r++, refOff += refStride, bOff += bStride) {
            for (var c = 0; c < 16; c++) {
                var p = ((ref[refOff + c] & 0xFF) * 64 + (ref[refOff + c + step] & 0xFF) * 64 + 64) >> 7;
                var d = p - (b[bOff + c] & 0xFF);
                sum += d;
                sse += d * d;
            }
        }
        return pack(sse, sum);
    }

    @Override
    public int forwardDct8(byte[] src, int srcOff, byte[] pred, int predOff, int[] coef, int coefOff) {
        var maxDc = 0;
        var maxAc = 0;
        for (var j = 0; j < 8; j++) {
            for (var r = 0; r < 4; r++) {
                var s = srcOff + 32 * r + 4 * j;
                var p = predOff + 32 * r + 4 * j;
                var d0 = (src[s] & 0xFF) - (pred[p] & 0xFF);
                var d1 = (src[s + 1] & 0xFF) - (pred[p + 1] & 0xFF);
                var d2 = (src[s + 2] & 0xFF) - (pred[p + 2] & 0xFF);
                var d3 = (src[s + 3] & 0xFF) - (pred[p + 3] & 0xFF);
                var a0 = d0 + d3;
                var a1 = d1 + d2;
                var a2 = d1 - d2;
                var a3 = d0 - d3;
                var t = coefOff + 32 * r + j;
                coef[t] = (a0 + a1) * 8;
                coef[t + 8] = (a2 * 2217 + a3 * 5352 + 1812) >> 9;
                coef[t + 16] = (a0 - a1) * 8;
                coef[t + 24] = (a3 * 2217 - a2 * 5352 + 937) >> 9;
            }
            for (var k = 0; k < 4; k++) {
                var t = coefOff + 8 * k + j;
                var c0 = coef[t];
                var c1 = coef[t + 32];
                var c2 = coef[t + 64];
                var c3 = coef[t + 96];
                var a0 = c0 + c3;
                var a1 = c1 + c2;
                var a2 = c1 - c2;
                var a3 = c0 - c3;
                coef[t] = (a0 + a1 + 7) >> 4;
                coef[t + 32] = ((a2 * 2217 + a3 * 5352 + 12000) >> 16) + (a3 != 0 ? 1 : 0);
                coef[t + 64] = (a0 - a1 + 7) >> 4;
                coef[t + 96] = (a3 * 2217 - a2 * 5352 + 51000) >> 16;
            }
            maxDc = Math.max(maxDc, Math.abs(coef[coefOff + j]));
            for (var p = 1; p < 16; p++) {
                maxAc = Math.max(maxAc, Math.abs(coef[coefOff + 8 * p + j]));
            }
        }
        return (maxDc << 16) | maxAc;
    }

    @Override
    void quantize8(int[] coef, int coefOff, int first, QuantMatrix m, short[] levels, int levelsOff, int[] eob,
                          int eobOff) {
        for (var j = 0; j < 8; j++) {
            var last = 0;
            for (var n = 0; n < 16; n++) {
                var at = coefOff + 8 * ZIGZAG[n] + j;
                var level = 0;
                if (n >= first) {
                    var i = n == 0 ? 0 : 1;
                    var v = coef[at];
                    level = Math.min(QuantMatrix.quantDiv(Math.abs(v), m.iq[i], m.bias[i]), MAX_LEVEL);
                    if (v < 0) {
                        level = -level;
                    }
                    coef[at] = (short) (level * m.q[i]);
                } else {
                    coef[at] = 0;
                }
                levels[levelsOff + n * LEVEL_STRIDE + j] = (short) level;
                if (level != 0) {
                    last = n + 1;
                }
            }
            eob[eobOff + j] = last;
        }
    }

    @Override
    public void inverseDctAdd8(int[] coef, int coefOff, byte[] pred, int predOff, byte[] out, int outOff) {
        for (var j = 0; j < 8; j++) {
            for (var i = 0; i < 4; i++) {
                var t = coefOff + 8 * i + j;
                var i0 = coef[t];
                var i4 = coef[t + 32];
                var i8 = coef[t + 64];
                var i12 = coef[t + 96];
                var a1 = i0 + i8;
                var b1 = i0 - i8;
                var c1 = ((i4 * ScalarVp8Dsp.SIN_PI8_SQRT2) >> 16)
                        - (i12 + ((i12 * ScalarVp8Dsp.COS_PI8_SQRT2_MINUS1) >> 16));
                var d1 = (i4 + ((i4 * ScalarVp8Dsp.COS_PI8_SQRT2_MINUS1) >> 16))
                        + ((i12 * ScalarVp8Dsp.SIN_PI8_SQRT2) >> 16);
                coef[t] = (short) (a1 + d1);
                coef[t + 96] = (short) (a1 - d1);
                coef[t + 32] = (short) (b1 + c1);
                coef[t + 64] = (short) (b1 - c1);
            }
            for (var r = 0; r < 4; r++) {
                var t = coefOff + 32 * r + j;
                var i0 = coef[t];
                var i1 = coef[t + 8];
                var i2 = coef[t + 16];
                var i3 = coef[t + 24];
                var a1 = i0 + i2;
                var b1 = i0 - i2;
                var c1 = ((i1 * ScalarVp8Dsp.SIN_PI8_SQRT2) >> 16)
                        - (i3 + ((i3 * ScalarVp8Dsp.COS_PI8_SQRT2_MINUS1) >> 16));
                var d1 = (i1 + ((i1 * ScalarVp8Dsp.COS_PI8_SQRT2_MINUS1) >> 16))
                        + ((i3 * ScalarVp8Dsp.SIN_PI8_SQRT2) >> 16);
                var p = predOff + 32 * r + 4 * j;
                var o = outOff + 32 * r + 4 * j;
                out[o] = (byte) Math.clamp(((a1 + d1 + 4) >> 3) + (pred[p] & 0xFF), 0, 255);
                out[o + 1] = (byte) Math.clamp(((b1 + c1 + 4) >> 3) + (pred[p + 1] & 0xFF), 0, 255);
                out[o + 2] = (byte) Math.clamp(((b1 - c1 + 4) >> 3) + (pred[p + 2] & 0xFF), 0, 255);
                out[o + 3] = (byte) Math.clamp(((a1 - d1 + 4) >> 3) + (pred[p + 3] & 0xFF), 0, 255);
            }
        }
    }
}
