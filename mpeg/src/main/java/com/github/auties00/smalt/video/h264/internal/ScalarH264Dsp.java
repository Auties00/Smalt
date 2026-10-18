package com.github.auties00.smalt.video.h264.internal;

import java.util.Arrays;

/**
 * Implements the H.264 sample-processing kernels with scalar Java code.
 *
 * <p>This is the reference implementation: every Vector API kernel is tested against it for
 * identical output.
 */
public class ScalarH264Dsp extends H264Dsp {
    /**
     * Constructs the scalar kernel set.
     */
    public ScalarH264Dsp() {
    }

    /**
     * Clips a value to the 8-bit sample range.
     *
     * @param value the sample, possibly outside 0 to 255
     * @return {@code value} clamped to {@code [0, 255]}
     */
    static int clip(int value) {
        return (value & ~0xFF) == 0 ? value : (~value >>> 31) * 255;
    }

    /**
     * Clips a value to a symmetric range.
     *
     * @param value the value to clamp
     * @param limit the non-negative bound
     * @return {@code value} clamped to {@code [-limit, limit]}
     */
    static int clip3(int value, int limit) {
        return value < -limit ? -limit : (value > limit ? limit : value);
    }

    @Override
    public void lumaMc(byte[] src, int srcOffset, int srcStride, byte[] dst, int dstOffset, int dstStride,
                       int width, int height, int fracX, int fracY, short[] sums, byte[] scratch) {
        switch ((fracY << 2) | fracX) {
            case 0 -> copy(src, srcOffset, srcStride, dst, dstOffset, dstStride, width, height);
            case 1 -> filterHorizontal(src, srcOffset, srcStride, dst, dstOffset, dstStride, width, height, 1);
            case 2 -> filterHorizontal(src, srcOffset, srcStride, dst, dstOffset, dstStride, width, height, 0);
            case 3 -> filterHorizontal(src, srcOffset, srcStride, dst, dstOffset, dstStride, width, height, 2);
            case 4 -> filterVertical(src, srcOffset, srcStride, dst, dstOffset, dstStride, width, height, 1);
            case 8 -> filterVertical(src, srcOffset, srcStride, dst, dstOffset, dstStride, width, height, 0);
            case 12 -> filterVertical(src, srcOffset, srcStride, dst, dstOffset, dstStride, width, height, 2);
            case 10 -> filterCenter(src, srcOffset, srcStride, dst, dstOffset, dstStride, width, height, sums);
            case 6, 14 -> {
                filterCenter(src, srcOffset, srcStride, scratch, 0, 16, width, height, sums);
                var row = fracY == 1 ? srcOffset : srcOffset + srcStride;
                filterHorizontal(src, row, srcStride, scratch, 256, 16, width, height, 0);
                averageInto(scratch, 0, scratch, 256, dst, dstOffset, dstStride, width, height);
            }
            case 9, 11 -> {
                filterCenter(src, srcOffset, srcStride, scratch, 0, 16, width, height, sums);
                var column = fracX == 1 ? srcOffset : srcOffset + 1;
                filterVertical(src, column, srcStride, scratch, 256, 16, width, height, 0);
                averageInto(scratch, 0, scratch, 256, dst, dstOffset, dstStride, width, height);
            }
            default -> {
                var row = fracY == 1 ? srcOffset : srcOffset + srcStride;
                var column = fracX == 1 ? srcOffset : srcOffset + 1;
                filterHorizontal(src, row, srcStride, scratch, 0, 16, width, height, 0);
                filterVertical(src, column, srcStride, scratch, 256, 16, width, height, 0);
                averageInto(scratch, 0, scratch, 256, dst, dstOffset, dstStride, width, height);
            }
        }
    }

    /**
     * Copies a block.
     *
     * @param src the source plane
     * @param srcOffset the source top-left index
     * @param srcStride the source stride
     * @param dst the destination plane
     * @param dstOffset the destination top-left index
     * @param dstStride the destination stride
     * @param width the block width
     * @param height the block height
     */
    static void copy(byte[] src, int srcOffset, int srcStride, byte[] dst, int dstOffset, int dstStride,
                     int width, int height) {
        for (var y = 0; y < height; y++) {
            System.arraycopy(src, srcOffset + y * srcStride, dst, dstOffset + y * dstStride, width);
        }
    }

    /**
     * Writes {@code (a + b + 1) >> 1} of two byte blocks into a third.
     *
     * @param a the first block, stride 16
     * @param aOffset the index of the first block
     * @param b the second block, stride 16
     * @param bOffset the index of the second block
     * @param dst the destination plane
     * @param dstOffset the destination top-left index
     * @param dstStride the destination stride
     * @param width the block width
     * @param height the block height
     */
    private static void averageInto(byte[] a, int aOffset, byte[] b, int bOffset, byte[] dst, int dstOffset,
                                    int dstStride, int width, int height) {
        for (var y = 0; y < height; y++) {
            var ai = aOffset + y * 16;
            var bi = bOffset + y * 16;
            var di = dstOffset + y * dstStride;
            for (var x = 0; x < width; x++) {
                dst[di + x] = (byte) (((a[ai + x] & 0xFF) + (b[bi + x] & 0xFF) + 1) >> 1);
            }
        }
    }

    /**
     * Applies the horizontal half-sample filter, optionally averaged with the integer sample to
     * its left or right.
     *
     * @param src the source plane
     * @param srcOffset the source top-left integer sample index
     * @param srcStride the source stride
     * @param dst the destination plane
     * @param dstOffset the destination top-left index
     * @param dstStride the destination stride
     * @param width the block width
     * @param height the block height
     * @param mode 0 for the half sample, 1 to average with the left integer sample, 2 with
     * the right one
     */
    private static void filterHorizontal(byte[] src, int srcOffset, int srcStride, byte[] dst, int dstOffset,
                                         int dstStride, int width, int height, int mode) {
        for (var y = 0; y < height; y++) {
            var s = srcOffset + y * srcStride;
            var d = dstOffset + y * dstStride;
            for (var x = 0; x < width; x++) {
                var i = s + x;
                var value = (src[i - 2] & 0xFF) - 5 * (src[i - 1] & 0xFF) + 20 * (src[i] & 0xFF)
                        + 20 * (src[i + 1] & 0xFF) - 5 * (src[i + 2] & 0xFF) + (src[i + 3] & 0xFF);
                var half = clip((value + 16) >> 5);
                if (mode == 1) {
                    half = (half + (src[i] & 0xFF) + 1) >> 1;
                } else if (mode == 2) {
                    half = (half + (src[i + 1] & 0xFF) + 1) >> 1;
                }
                dst[d + x] = (byte) half;
            }
        }
    }

    /**
     * Applies the vertical half-sample filter, optionally averaged with the integer sample above
     * or below.
     *
     * @param src the source plane
     * @param srcOffset the source top-left integer sample index
     * @param srcStride the source stride
     * @param dst the destination plane
     * @param dstOffset the destination top-left index
     * @param dstStride the destination stride
     * @param width the block width
     * @param height the block height
     * @param mode 0 for the half sample, 1 to average with the upper integer sample, 2 with
     * the lower one
     */
    private static void filterVertical(byte[] src, int srcOffset, int srcStride, byte[] dst, int dstOffset,
                                       int dstStride, int width, int height, int mode) {
        for (var y = 0; y < height; y++) {
            var s = srcOffset + y * srcStride;
            var d = dstOffset + y * dstStride;
            for (var x = 0; x < width; x++) {
                var i = s + x;
                var value = (src[i - 2 * srcStride] & 0xFF) - 5 * (src[i - srcStride] & 0xFF) + 20 * (src[i] & 0xFF)
                        + 20 * (src[i + srcStride] & 0xFF) - 5 * (src[i + 2 * srcStride] & 0xFF)
                        + (src[i + 3 * srcStride] & 0xFF);
                var half = clip((value + 16) >> 5);
                if (mode == 1) {
                    half = (half + (src[i] & 0xFF) + 1) >> 1;
                } else if (mode == 2) {
                    half = (half + (src[i + srcStride] & 0xFF) + 1) >> 1;
                }
                dst[d + x] = (byte) half;
            }
        }
    }

    /**
     * Computes the center half sample {@code j} of every position of a block.
     *
     * @param src the source plane
     * @param srcOffset the source top-left integer sample index
     * @param srcStride the source stride
     * @param dst the destination plane
     * @param dstOffset the destination top-left index
     * @param dstStride the destination stride
     * @param width the block width
     * @param height the block height
     * @param tmp scratch for {@code (height + 5) * width} intermediate values, whose range
     * {@code -2550..10710} fits 16 bits
     */
    private static void filterCenter(byte[] src, int srcOffset, int srcStride, byte[] dst, int dstOffset,
                                     int dstStride, int width, int height, short[] tmp) {
        for (var r = 0; r < height + 5; r++) {
            var s = srcOffset + (r - 2) * srcStride;
            var t = r * width;
            for (var x = 0; x < width; x++) {
                var i = s + x;
                tmp[t + x] = (short) ((src[i - 2] & 0xFF) - 5 * (src[i - 1] & 0xFF) + 20 * (src[i] & 0xFF)
                        + 20 * (src[i + 1] & 0xFF) - 5 * (src[i + 2] & 0xFF) + (src[i + 3] & 0xFF));
            }
        }
        for (var y = 0; y < height; y++) {
            var d = dstOffset + y * dstStride;
            var t = y * width;
            for (var x = 0; x < width; x++) {
                var i = t + x;
                var value = tmp[i] - 5 * tmp[i + width] + 20 * tmp[i + 2 * width] + 20 * tmp[i + 3 * width]
                        - 5 * tmp[i + 4 * width] + tmp[i + 5 * width];
                dst[d + x] = (byte) clip((value + 512) >> 10);
            }
        }
    }

    @Override
    public void chromaMc(byte[] srcCb, byte[] srcCr, int srcOffset, int srcStride, byte[] dstCb, byte[] dstCr,
                         int dstOffset, int dstStride, int width, int height, int fracX, int fracY) {
        chromaPlane(srcCb, srcOffset, srcStride, dstCb, dstOffset, dstStride, width, height, fracX, fracY);
        chromaPlane(srcCr, srcOffset, srcStride, dstCr, dstOffset, dstStride, width, height, fracX, fracY);
    }

    /**
     * Interpolates one chroma block at eighth-sample precision.
     *
     * @param src the reference plane
     * @param srcOffset the index of the integer sample at the block's top-left corner
     * @param srcStride the reference row stride
     * @param dst the destination
     * @param dstOffset the index of the destination top-left sample
     * @param dstStride the destination row stride
     * @param width the block width
     * @param height the block height
     * @param fracX the horizontal eighth-sample fraction, 0 to 7
     * @param fracY the vertical eighth-sample fraction, 0 to 7
     */
    static void chromaPlane(byte[] src, int srcOffset, int srcStride, byte[] dst, int dstOffset, int dstStride,
                            int width, int height, int fracX, int fracY) {
        if ((fracX | fracY) == 0) {
            copy(src, srcOffset, srcStride, dst, dstOffset, dstStride, width, height);
            return;
        }
        var a = (8 - fracX) * (8 - fracY);
        var b = fracX * (8 - fracY);
        var c = (8 - fracX) * fracY;
        var d = fracX * fracY;
        if (d == 0) {
            var step = fracX != 0 ? 1 : srcStride;
            var w1 = b + c;
            for (var y = 0; y < height; y++) {
                var s = srcOffset + y * srcStride;
                var o = dstOffset + y * dstStride;
                for (var x = 0; x < width; x++) {
                    dst[o + x] = (byte) ((a * (src[s + x] & 0xFF) + w1 * (src[s + x + step] & 0xFF) + 32) >> 6);
                }
            }
            return;
        }
        for (var y = 0; y < height; y++) {
            var s = srcOffset + y * srcStride;
            var o = dstOffset + y * dstStride;
            for (var x = 0; x < width; x++) {
                var i = s + x;
                dst[o + x] = (byte) ((a * (src[i] & 0xFF) + b * (src[i + 1] & 0xFF)
                        + c * (src[i + srcStride] & 0xFF) + d * (src[i + srcStride + 1] & 0xFF) + 32) >> 6);
            }
        }
    }

    @Override
    public void averageBlocks(byte[] a, int aOffset, int aStride, byte[] b, int bOffset, int bStride,
                              byte[] dst, int dstOffset, int dstStride, int width, int height) {
        for (var y = 0; y < height; y++) {
            var ai = aOffset + y * aStride;
            var bi = bOffset + y * bStride;
            var d = dstOffset + y * dstStride;
            for (var x = 0; x < width; x++) {
                dst[d + x] = (byte) (((a[ai + x] & 0xFF) + (b[bi + x] & 0xFF) + 1) >> 1);
            }
        }
    }

    @Override
    public void average(byte[] dst, int dstOffset, int dstStride, byte[] src, int srcOffset, int srcStride,
                        int width, int height) {
        for (var y = 0; y < height; y++) {
            var d = dstOffset + y * dstStride;
            var s = srcOffset + y * srcStride;
            for (var x = 0; x < width; x++) {
                dst[d + x] = (byte) (((dst[d + x] & 0xFF) + (src[s + x] & 0xFF) + 1) >> 1);
            }
        }
    }

    @Override
    public void weight(byte[] dst, int dstOffset, int dstStride, int width, int height,
                       int logWd, int weight, int offset) {
        var round = logWd >= 1 ? 1 << (logWd - 1) : 0;
        for (var y = 0; y < height; y++) {
            var d = dstOffset + y * dstStride;
            for (var x = 0; x < width; x++) {
                var value = (((dst[d + x] & 0xFF) * weight + round) >> logWd) + offset;
                dst[d + x] = (byte) clip(value);
            }
        }
    }

    @Override
    public void biWeight(byte[] dst, int dstOffset, int dstStride, byte[] src, int srcOffset, int srcStride,
                         int width, int height, int logWd, int weight0, int weight1, int offset) {
        var round = 1 << logWd;
        var shift = logWd + 1;
        for (var y = 0; y < height; y++) {
            var d = dstOffset + y * dstStride;
            var s = srcOffset + y * srcStride;
            for (var x = 0; x < width; x++) {
                var value = (((dst[d + x] & 0xFF) * weight0 + (src[s + x] & 0xFF) * weight1 + round) >> shift) + offset;
                dst[d + x] = (byte) clip(value);
            }
        }
    }

    @Override
    public void idct4x4Add(int[] c, int o, byte[] dst, int dstOffset, int dstStride) {
        var d0 = c[o];
        var d1 = c[o + 1];
        var d2 = c[o + 2];
        var d3 = c[o + 3];
        var e = d0 + d2;
        var f = d0 - d2;
        var g = (d1 >> 1) - d3;
        var h = d1 + (d3 >> 1);
        var r00 = e + h;
        var r01 = f + g;
        var r02 = f - g;
        var r03 = e - h;
        d0 = c[o + 4];
        d1 = c[o + 5];
        d2 = c[o + 6];
        d3 = c[o + 7];
        e = d0 + d2;
        f = d0 - d2;
        g = (d1 >> 1) - d3;
        h = d1 + (d3 >> 1);
        var r10 = e + h;
        var r11 = f + g;
        var r12 = f - g;
        var r13 = e - h;
        d0 = c[o + 8];
        d1 = c[o + 9];
        d2 = c[o + 10];
        d3 = c[o + 11];
        e = d0 + d2;
        f = d0 - d2;
        g = (d1 >> 1) - d3;
        h = d1 + (d3 >> 1);
        var r20 = e + h;
        var r21 = f + g;
        var r22 = f - g;
        var r23 = e - h;
        d0 = c[o + 12];
        d1 = c[o + 13];
        d2 = c[o + 14];
        d3 = c[o + 15];
        e = d0 + d2;
        f = d0 - d2;
        g = (d1 >> 1) - d3;
        h = d1 + (d3 >> 1);
        var r30 = e + h;
        var r31 = f + g;
        var r32 = f - g;
        var r33 = e - h;
        Arrays.fill(c, o, o + 16, 0);
        addColumn(dst, dstOffset, dstStride, r00, r10, r20, r30);
        addColumn(dst, dstOffset + 1, dstStride, r01, r11, r21, r31);
        addColumn(dst, dstOffset + 2, dstStride, r02, r12, r22, r32);
        addColumn(dst, dstOffset + 3, dstStride, r03, r13, r23, r33);
    }

    /**
     * Applies the vertical 4-point inverse transform to one column of row-transformed values and
     * adds the rounded result to a column of four samples.
     *
     * @param dst the plane
     * @param p the index of the column's top sample
     * @param stride the row stride
     * @param d0 the row-transformed value of row 0
     * @param d1 the row-transformed value of row 1
     * @param d2 the row-transformed value of row 2
     * @param d3 the row-transformed value of row 3
     */
    private static void addColumn(byte[] dst, int p, int stride, int d0, int d1, int d2, int d3) {
        var e = d0 + d2;
        var f = d0 - d2;
        var g = (d1 >> 1) - d3;
        var h = d1 + (d3 >> 1);
        dst[p] = (byte) clip((dst[p] & 0xFF) + ((e + h + 32) >> 6));
        p += stride;
        dst[p] = (byte) clip((dst[p] & 0xFF) + ((f + g + 32) >> 6));
        p += stride;
        dst[p] = (byte) clip((dst[p] & 0xFF) + ((f - g + 32) >> 6));
        p += stride;
        dst[p] = (byte) clip((dst[p] & 0xFF) + ((e - h + 32) >> 6));
    }

    @Override
    public void idct4x4DcAdd(int[] c, int o, byte[] dst, int dstOffset, int dstStride) {
        var dc = (c[o] + 32) >> 6;
        c[o] = 0;
        for (var y = 0; y < 4; y++) {
            var p = dstOffset + y * dstStride;
            for (var x = 0; x < 4; x++) {
                dst[p + x] = (byte) clip((dst[p + x] & 0xFF) + dc);
            }
        }
    }

    @Override
    public void chromaDcAdd(int[] c, byte[] dst, int dstOffset, int dstStride) {
        for (var blk = 0; blk < 4; blk++) {
            idct4x4DcAdd(c, blk * 16, dst, dstOffset + (blk >> 1) * 4 * dstStride + (blk & 1) * 4, dstStride);
        }
    }

    @Override
    public void idct8x8DcAdd(int[] c, int o, byte[] dst, int dstOffset, int dstStride) {
        var dc = (c[o] + 32) >> 6;
        c[o] = 0;
        for (var y = 0; y < 8; y++) {
            var p = dstOffset + y * dstStride;
            for (var x = 0; x < 8; x++) {
                dst[p + x] = (byte) clip((dst[p + x] & 0xFF) + dc);
            }
        }
    }

    @Override
    public void idct8x8Add(int[] c, int o, byte[] dst, int dstOffset, int dstStride) {
        for (var i = 0; i < 8; i++) {
            transform8(c, o + i * 8, 1);
        }
        for (var j = 0; j < 8; j++) {
            transform8(c, o + j, 8);
        }
        for (var y = 0; y < 8; y++) {
            var p = dstOffset + y * dstStride;
            var r = o + y * 8;
            for (var x = 0; x < 8; x++) {
                dst[p + x] = (byte) clip((dst[p + x] & 0xFF) + ((c[r + x] + 32) >> 6));
                c[r + x] = 0;
            }
        }
    }

    /**
     * Applies the one-dimensional 8-point inverse transform in place.
     *
     * @param c the values
     * @param o the index of the first value
     * @param step the distance between successive values
     */
    static void transform8(int[] c, int o, int step) {
        var d0 = c[o];
        var d1 = c[o + step];
        var d2 = c[o + 2 * step];
        var d3 = c[o + 3 * step];
        var d4 = c[o + 4 * step];
        var d5 = c[o + 5 * step];
        var d6 = c[o + 6 * step];
        var d7 = c[o + 7 * step];
        var e0 = d0 + d4;
        var e1 = -d3 + d5 - d7 - (d7 >> 1);
        var e2 = d0 - d4;
        var e3 = d1 + d7 - d3 - (d3 >> 1);
        var e4 = (d2 >> 1) - d6;
        var e5 = -d1 + d7 + d5 + (d5 >> 1);
        var e6 = d2 + (d6 >> 1);
        var e7 = d3 + d5 + d1 + (d1 >> 1);
        var f0 = e0 + e6;
        var f1 = e1 + (e7 >> 2);
        var f2 = e2 + e4;
        var f3 = e3 + (e5 >> 2);
        var f4 = e2 - e4;
        var f5 = (e3 >> 2) - e5;
        var f6 = e0 - e6;
        var f7 = e7 - (e1 >> 2);
        c[o] = f0 + f7;
        c[o + step] = f2 + f5;
        c[o + 2 * step] = f4 + f3;
        c[o + 3 * step] = f6 + f1;
        c[o + 4 * step] = f6 - f1;
        c[o + 5 * step] = f4 - f3;
        c[o + 6 * step] = f2 - f5;
        c[o + 7 * step] = f0 - f7;
    }

    @Override
    public void lumaEdgeVertical(byte[] pix, int offset, int stride, int alpha, int beta, int tc0, byte[] scratch) {
        for (var segment = 0; segment < 4; segment++) {
            var tc = (byte) (tc0 >> (segment * 8));
            if (tc < 0) {
                continue;
            }
            for (var row = 0; row < 4; row++) {
                filterLumaNormal(pix, offset + (segment * 4 + row) * stride, 1, alpha, beta, tc);
            }
        }
    }

    @Override
    public void lumaEdgeHorizontal(byte[] pix, int offset, int stride, int alpha, int beta, int tc0) {
        for (var segment = 0; segment < 4; segment++) {
            var tc = (byte) (tc0 >> (segment * 8));
            if (tc < 0) {
                continue;
            }
            for (var column = 0; column < 4; column++) {
                filterLumaNormal(pix, offset + segment * 4 + column, stride, alpha, beta, tc);
            }
        }
    }

    /**
     * Filters one line of samples across a luma edge with boundary strength below 4.
     *
     * @param pix the plane
     * @param q the index of {@code q0}
     * @param step the distance between successive samples across the edge
     * @param alpha the {@code alpha} threshold
     * @param beta the {@code beta} threshold
     * @param tc0 the {@code tC0}
     */
    private static void filterLumaNormal(byte[] pix, int q, int step, int alpha, int beta, int tc0) {
        var p0 = pix[q - step] & 0xFF;
        var q0 = pix[q] & 0xFF;
        var p1 = pix[q - 2 * step] & 0xFF;
        var q1 = pix[q + step] & 0xFF;
        if (Math.abs(p0 - q0) >= alpha || Math.abs(p1 - p0) >= beta || Math.abs(q1 - q0) >= beta) {
            return;
        }
        var p2 = pix[q - 3 * step] & 0xFF;
        var q2 = pix[q + 2 * step] & 0xFF;
        var ap = Math.abs(p2 - p0) < beta;
        var aq = Math.abs(q2 - q0) < beta;
        var tc = tc0 + (ap ? 1 : 0) + (aq ? 1 : 0);
        var delta = clip3((((q0 - p0) << 2) + (p1 - q1) + 4) >> 3, tc);
        pix[q - step] = (byte) clip(p0 + delta);
        pix[q] = (byte) clip(q0 - delta);
        var average = (p0 + q0 + 1) >> 1;
        if (ap) {
            pix[q - 2 * step] = (byte) (p1 + clip3((p2 + average - (p1 << 1)) >> 1, tc0));
        }
        if (aq) {
            pix[q + step] = (byte) (q1 + clip3((q2 + average - (q1 << 1)) >> 1, tc0));
        }
    }

    @Override
    public void lumaEdgeVerticalStrong(byte[] pix, int offset, int stride, int alpha, int beta, byte[] scratch) {
        for (var row = 0; row < 16; row++) {
            filterLumaStrong(pix, offset + row * stride, 1, alpha, beta);
        }
    }

    @Override
    public void lumaEdgeHorizontalStrong(byte[] pix, int offset, int stride, int alpha, int beta) {
        for (var column = 0; column < 16; column++) {
            filterLumaStrong(pix, offset + column, stride, alpha, beta);
        }
    }

    /**
     * Filters one line of samples across a luma edge with boundary strength 4.
     *
     * @param pix the plane
     * @param q the index of {@code q0}
     * @param step the distance between successive samples across the edge
     * @param alpha the {@code alpha} threshold
     * @param beta the {@code beta} threshold
     */
    private static void filterLumaStrong(byte[] pix, int q, int step, int alpha, int beta) {
        var p0 = pix[q - step] & 0xFF;
        var q0 = pix[q] & 0xFF;
        var p1 = pix[q - 2 * step] & 0xFF;
        var q1 = pix[q + step] & 0xFF;
        var difference = Math.abs(p0 - q0);
        if (difference >= alpha || Math.abs(p1 - p0) >= beta || Math.abs(q1 - q0) >= beta) {
            return;
        }
        var p2 = pix[q - 3 * step] & 0xFF;
        var q2 = pix[q + 2 * step] & 0xFF;
        var strong = difference < ((alpha >> 2) + 2);
        if (strong && Math.abs(p2 - p0) < beta) {
            var p3 = pix[q - 4 * step] & 0xFF;
            pix[q - step] = (byte) ((p2 + 2 * p1 + 2 * p0 + 2 * q0 + q1 + 4) >> 3);
            pix[q - 2 * step] = (byte) ((p2 + p1 + p0 + q0 + 2) >> 2);
            pix[q - 3 * step] = (byte) ((2 * p3 + 3 * p2 + p1 + p0 + q0 + 4) >> 3);
        } else {
            pix[q - step] = (byte) ((2 * p1 + p0 + q1 + 2) >> 2);
        }
        if (strong && Math.abs(q2 - q0) < beta) {
            var q3 = pix[q + 3 * step] & 0xFF;
            pix[q] = (byte) ((p1 + 2 * p0 + 2 * q0 + 2 * q1 + q2 + 4) >> 3);
            pix[q + step] = (byte) ((p0 + q0 + q1 + q2 + 2) >> 2);
            pix[q + 2 * step] = (byte) ((2 * q3 + 3 * q2 + q1 + q0 + p0 + 4) >> 3);
        } else {
            pix[q] = (byte) ((2 * q1 + q0 + p1 + 2) >> 2);
        }
    }

    @Override
    public void chromaEdgesVertical(byte[] cb, byte[] cr, int offset, int stride, int edges, int[] params,
                                    byte[] scratch) {
        for (var edge = 0; edge < 2; edge++) {
            if ((edges & (1 << edge)) == 0) {
                continue;
            }
            var at = edge * 4;
            var q0 = offset + edge * 4;
            chromaEdge(cb, q0, stride, 1, params[at], params[at + 2]);
            chromaEdge(cr, q0, stride, 1, params[at + 1], params[at + 3]);
        }
    }

    @Override
    public void chromaEdgeHorizontal(byte[] cb, byte[] cr, int offset, int stride, int[] params, int at) {
        chromaEdge(cb, offset, 1, stride, params[at], params[at + 2]);
        chromaEdge(cr, offset, 1, stride, params[at + 1], params[at + 3]);
    }

    /**
     * Filters one chroma edge of 8 lines in one plane.
     *
     * @param pix the plane
     * @param q0 the index of the first line's {@code q0} sample
     * @param lineStep the index distance between consecutive lines along the edge
     * @param sampleStep the index distance between consecutive samples across the edge
     * @param thresholds the thresholds, {@code alpha | beta << 8}
     * @param tc0 the packed per-segment {@code tC0}
     */
    private static void chromaEdge(byte[] pix, int q0, int lineStep, int sampleStep, int thresholds, int tc0) {
        var alpha = thresholds & 0xFF;
        var beta = (thresholds >> 8) & 0xFF;
        for (var segment = 0; segment < 4; segment++) {
            var tc = (byte) (tc0 >> (segment * 8));
            if (tc < 0) {
                continue;
            }
            for (var line = 0; line < 2; line++) {
                filterChroma(pix, q0 + (segment * 2 + line) * lineStep, sampleStep, alpha, beta, tc);
            }
        }
    }

    /**
     * Filters one line of samples across a chroma edge.
     *
     * @param pix the plane
     * @param q the index of {@code q0}
     * @param step the distance between successive samples across the edge
     * @param alpha the {@code alpha} threshold
     * @param beta the {@code beta} threshold
     * @param tc0 the {@code tC0}, or {@link #CHROMA_STRONG} for boundary strength 4
     */
    private static void filterChroma(byte[] pix, int q, int step, int alpha, int beta, int tc0) {
        var p0 = pix[q - step] & 0xFF;
        var q0 = pix[q] & 0xFF;
        var p1 = pix[q - 2 * step] & 0xFF;
        var q1 = pix[q + step] & 0xFF;
        if (Math.abs(p0 - q0) >= alpha || Math.abs(p1 - p0) >= beta || Math.abs(q1 - q0) >= beta) {
            return;
        }
        if (tc0 == CHROMA_STRONG) {
            pix[q - step] = (byte) ((2 * p1 + p0 + q1 + 2) >> 2);
            pix[q] = (byte) ((2 * q1 + q0 + p1 + 2) >> 2);
            return;
        }
        var delta = clip3((((q0 - p0) << 2) + (p1 - q1) + 4) >> 3, tc0 + 1);
        pix[q - step] = (byte) clip(p0 + delta);
        pix[q] = (byte) clip(q0 - delta);
    }
}
