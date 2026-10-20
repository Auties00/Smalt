package com.github.auties00.smalt.video.h264.internal;

import java.util.Arrays;

/**
 * Implements {@link H264WideDsp} with scalar Java code: the reference implementation, which every
 * JVM can run and every Vector API kernel is tested against.
 */
class ScalarH264WideDsp extends H264WideDsp {
    /**
     * Constructs the scalar kernel set.
     */
    ScalarH264WideDsp() {
    }

    /**
     * Clips a value to a symmetric range.
     *
     * @param value the value to clamp
     * @param limit the non-negative bound
     * @return {@code value} clamped to {@code [-limit, limit]}
     */
    private static int clip3(int value, int limit) {
        return value < -limit ? -limit : (value > limit ? limit : value);
    }

    @Override
    public void lumaMc(short[] src, int srcOffset, int srcStride, short[] dst, int dstOffset, int dstStride, int width,
                       int height, int fracX, int fracY, int[] sums, short[] scratch, int max) {
        switch ((fracY << 2) | fracX) {
            case 0 -> copy(src, srcOffset, srcStride, dst, dstOffset, dstStride, width, height);
            case 1 -> filterHorizontal(src, srcOffset, srcStride, dst, dstOffset, dstStride, width, height, 1, max);
            case 2 -> filterHorizontal(src, srcOffset, srcStride, dst, dstOffset, dstStride, width, height, 0, max);
            case 3 -> filterHorizontal(src, srcOffset, srcStride, dst, dstOffset, dstStride, width, height, 2, max);
            case 4 -> filterVertical(src, srcOffset, srcStride, dst, dstOffset, dstStride, width, height, 1, max);
            case 8 -> filterVertical(src, srcOffset, srcStride, dst, dstOffset, dstStride, width, height, 0, max);
            case 12 -> filterVertical(src, srcOffset, srcStride, dst, dstOffset, dstStride, width, height, 2, max);
            case 10 -> filterCenter(src, srcOffset, srcStride, dst, dstOffset, dstStride, width, height, sums, max);
            case 6, 14 -> {
                filterCenter(src, srcOffset, srcStride, scratch, 0, 16, width, height, sums, max);
                var row = fracY == 1 ? srcOffset : srcOffset + srcStride;
                filterHorizontal(src, row, srcStride, scratch, 256, 16, width, height, 0, max);
                averageInto(scratch, 0, scratch, 256, dst, dstOffset, dstStride, width, height);
            }
            case 9, 11 -> {
                filterCenter(src, srcOffset, srcStride, scratch, 0, 16, width, height, sums, max);
                var column = fracX == 1 ? srcOffset : srcOffset + 1;
                filterVertical(src, column, srcStride, scratch, 256, 16, width, height, 0, max);
                averageInto(scratch, 0, scratch, 256, dst, dstOffset, dstStride, width, height);
            }
            default -> {
                var row = fracY == 1 ? srcOffset : srcOffset + srcStride;
                var column = fracX == 1 ? srcOffset : srcOffset + 1;
                filterHorizontal(src, row, srcStride, scratch, 0, 16, width, height, 0, max);
                filterVertical(src, column, srcStride, scratch, 256, 16, width, height, 0, max);
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
    private static void copy(short[] src, int srcOffset, int srcStride, short[] dst, int dstOffset, int dstStride,
                     int width, int height) {
        for (var y = 0; y < height; y++) {
            System.arraycopy(src, srcOffset + y * srcStride, dst, dstOffset + y * dstStride, width);
        }
    }

    /**
     * Writes {@code (a + b + 1) >> 1} of two blocks of stride 16 into a third.
     *
     * @param a the first block
     * @param aOffset the index of the first block
     * @param b the second block
     * @param bOffset the index of the second block
     * @param dst the destination plane
     * @param dstOffset the destination top-left index
     * @param dstStride the destination stride
     * @param width the block width
     * @param height the block height
     */
    private static void averageInto(short[] a, int aOffset, short[] b, int bOffset, short[] dst, int dstOffset,
                                    int dstStride, int width, int height) {
        for (var y = 0; y < height; y++) {
            var ai = aOffset + y * 16;
            var bi = bOffset + y * 16;
            var di = dstOffset + y * dstStride;
            for (var x = 0; x < width; x++) {
                dst[di + x] = (short) ((a[ai + x] + b[bi + x] + 1) >> 1);
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
     * @param max the largest sample value
     */
    private static void filterHorizontal(short[] src, int srcOffset, int srcStride, short[] dst, int dstOffset,
                                         int dstStride, int width, int height, int mode, int max) {
        for (var y = 0; y < height; y++) {
            var s = srcOffset + y * srcStride;
            var d = dstOffset + y * dstStride;
            for (var x = 0; x < width; x++) {
                var i = s + x;
                var value = src[i - 2] - 5 * src[i - 1] + 20 * src[i] + 20 * src[i + 1] - 5 * src[i + 2] + src[i + 3];
                var half = H264WideDsp.clip((value + 16) >> 5, max);
                if (mode == 1) {
                    half = (half + src[i] + 1) >> 1;
                } else if (mode == 2) {
                    half = (half + src[i + 1] + 1) >> 1;
                }
                dst[d + x] = (short) half;
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
     * @param max the largest sample value
     */
    private static void filterVertical(short[] src, int srcOffset, int srcStride, short[] dst, int dstOffset,
                                       int dstStride, int width, int height, int mode, int max) {
        for (var y = 0; y < height; y++) {
            var s = srcOffset + y * srcStride;
            var d = dstOffset + y * dstStride;
            for (var x = 0; x < width; x++) {
                var i = s + x;
                var value = src[i - 2 * srcStride] - 5 * src[i - srcStride] + 20 * src[i] + 20 * src[i + srcStride]
                        - 5 * src[i + 2 * srcStride] + src[i + 3 * srcStride];
                var half = H264WideDsp.clip((value + 16) >> 5, max);
                if (mode == 1) {
                    half = (half + src[i] + 1) >> 1;
                } else if (mode == 2) {
                    half = (half + src[i + srcStride] + 1) >> 1;
                }
                dst[d + x] = (short) half;
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
     * @param tmp scratch for {@code (height + 5) * width} intermediate values
     * @param max the largest sample value
     */
    private static void filterCenter(short[] src, int srcOffset, int srcStride, short[] dst, int dstOffset,
                                     int dstStride, int width, int height, int[] tmp, int max) {
        for (var r = 0; r < height + 5; r++) {
            var s = srcOffset + (r - 2) * srcStride;
            var t = r * width;
            for (var x = 0; x < width; x++) {
                var i = s + x;
                tmp[t + x] = src[i - 2] - 5 * src[i - 1] + 20 * src[i] + 20 * src[i + 1] - 5 * src[i + 2] + src[i + 3];
            }
        }
        for (var y = 0; y < height; y++) {
            var d = dstOffset + y * dstStride;
            var t = y * width;
            for (var x = 0; x < width; x++) {
                var i = t + x;
                var value = tmp[i] - 5 * tmp[i + width] + 20 * tmp[i + 2 * width] + 20 * tmp[i + 3 * width]
                        - 5 * tmp[i + 4 * width] + tmp[i + 5 * width];
                dst[d + x] = (short) H264WideDsp.clip((value + 512) >> 10, max);
            }
        }
    }

    @Override
    public void chromaMc(short[] srcCb, short[] srcCr, int srcOffset, int srcStride, short[] dstCb, short[] dstCr,
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
    private static void chromaPlane(short[] src, int srcOffset, int srcStride, short[] dst, int dstOffset,
                                    int dstStride, int width, int height, int fracX, int fracY) {
        if ((fracX | fracY) == 0) {
            copy(src, srcOffset, srcStride, dst, dstOffset, dstStride, width, height);
            return;
        }
        var a = (8 - fracX) * (8 - fracY);
        var b = fracX * (8 - fracY);
        var c = (8 - fracX) * fracY;
        var d = fracX * fracY;
        for (var y = 0; y < height; y++) {
            var s = srcOffset + y * srcStride;
            var o = dstOffset + y * dstStride;
            for (var x = 0; x < width; x++) {
                var i = s + x;
                dst[o + x] = (short) ((a * src[i] + b * src[i + 1] + c * src[i + srcStride]
                        + d * src[i + srcStride + 1] + 32) >> 6);
            }
        }
    }

    @Override
    public void averageBlocks(short[] a, int aOffset, int aStride, short[] b, int bOffset, int bStride, short[] dst,
                              int dstOffset, int dstStride, int width, int height) {
        for (var y = 0; y < height; y++) {
            var ai = aOffset + y * aStride;
            var bi = bOffset + y * bStride;
            var d = dstOffset + y * dstStride;
            for (var x = 0; x < width; x++) {
                dst[d + x] = (short) ((a[ai + x] + b[bi + x] + 1) >> 1);
            }
        }
    }

    @Override
    public void average(short[] dst, int dstOffset, int dstStride, short[] src, int srcOffset, int srcStride, int width,
                        int height) {
        for (var y = 0; y < height; y++) {
            var d = dstOffset + y * dstStride;
            var s = srcOffset + y * srcStride;
            for (var x = 0; x < width; x++) {
                dst[d + x] = (short) ((dst[d + x] + src[s + x] + 1) >> 1);
            }
        }
    }

    @Override
    public void weight(short[] dst, int dstOffset, int dstStride, int width, int height, int logWd, int weight,
                       int offset, int max) {
        var round = logWd >= 1 ? 1 << (logWd - 1) : 0;
        for (var y = 0; y < height; y++) {
            var d = dstOffset + y * dstStride;
            for (var x = 0; x < width; x++) {
                dst[d + x] = (short) H264WideDsp.clip(((dst[d + x] * weight + round) >> logWd) + offset, max);
            }
        }
    }

    @Override
    public void biWeight(short[] dst, int dstOffset, int dstStride, short[] src, int srcOffset, int srcStride,
                         int width, int height, int logWd, int weight0, int weight1, int offset, int max) {
        var round = 1 << logWd;
        var shift = logWd + 1;
        for (var y = 0; y < height; y++) {
            var d = dstOffset + y * dstStride;
            var s = srcOffset + y * srcStride;
            for (var x = 0; x < width; x++) {
                var value = ((dst[d + x] * weight0 + src[s + x] * weight1 + round) >> shift) + offset;
                dst[d + x] = (short) H264WideDsp.clip(value, max);
            }
        }
    }

    @Override
    public void idct4x4Add(int[] c, int o, short[] dst, int dstOffset, int dstStride, int max) {
        for (var i = 0; i < 4; i++) {
            var r = o + i * 4;
            var d0 = c[r];
            var d1 = c[r + 1];
            var d2 = c[r + 2];
            var d3 = c[r + 3];
            var e = d0 + d2;
            var f = d0 - d2;
            var g = (d1 >> 1) - d3;
            var h = d1 + (d3 >> 1);
            c[r] = e + h;
            c[r + 1] = f + g;
            c[r + 2] = f - g;
            c[r + 3] = e - h;
        }
        for (var j = 0; j < 4; j++) {
            var d0 = c[o + j];
            var d1 = c[o + 4 + j];
            var d2 = c[o + 8 + j];
            var d3 = c[o + 12 + j];
            var e = d0 + d2;
            var f = d0 - d2;
            var g = (d1 >> 1) - d3;
            var h = d1 + (d3 >> 1);
            var p = dstOffset + j;
            dst[p] = (short) H264WideDsp.clip(dst[p] + ((e + h + 32) >> 6), max);
            p += dstStride;
            dst[p] = (short) H264WideDsp.clip(dst[p] + ((f + g + 32) >> 6), max);
            p += dstStride;
            dst[p] = (short) H264WideDsp.clip(dst[p] + ((f - g + 32) >> 6), max);
            p += dstStride;
            dst[p] = (short) H264WideDsp.clip(dst[p] + ((e - h + 32) >> 6), max);
        }
        Arrays.fill(c, o, o + 16, 0);
    }

    @Override
    public void idct4x4DcAdd(int[] c, int o, short[] dst, int dstOffset, int dstStride, int max) {
        var dc = (c[o] + 32) >> 6;
        c[o] = 0;
        for (var y = 0; y < 4; y++) {
            var p = dstOffset + y * dstStride;
            for (var x = 0; x < 4; x++) {
                dst[p + x] = (short) H264WideDsp.clip(dst[p + x] + dc, max);
            }
        }
    }

    @Override
    public void chromaDcAdd(int[] c, short[] dst, int dstOffset, int dstStride, int max) {
        for (var blk = 0; blk < 4; blk++) {
            idct4x4DcAdd(c, blk * 16, dst, dstOffset + (blk >> 1) * 4 * dstStride + (blk & 1) * 4, dstStride, max);
        }
    }

    @Override
    public void idct8x8DcAdd(int[] c, int o, short[] dst, int dstOffset, int dstStride, int max) {
        var dc = (c[o] + 32) >> 6;
        c[o] = 0;
        for (var y = 0; y < 8; y++) {
            var p = dstOffset + y * dstStride;
            for (var x = 0; x < 8; x++) {
                dst[p + x] = (short) H264WideDsp.clip(dst[p + x] + dc, max);
            }
        }
    }

    @Override
    public void idct8x8Add(int[] c, int o, short[] dst, int dstOffset, int dstStride, int max) {
        for (var i = 0; i < 8; i++) {
            ScalarH264Dsp.transform8(c, o + i * 8, 1);
        }
        for (var j = 0; j < 8; j++) {
            ScalarH264Dsp.transform8(c, o + j, 8);
        }
        for (var y = 0; y < 8; y++) {
            var p = dstOffset + y * dstStride;
            var r = o + y * 8;
            for (var x = 0; x < 8; x++) {
                dst[p + x] = (short) H264WideDsp.clip(dst[p + x] + ((c[r + x] + 32) >> 6), max);
                c[r + x] = 0;
            }
        }
    }

    @Override
    public void lumaEdgeVertical(short[] pix, int offset, int stride, int alpha, int beta, int tc0, int shift) {
        var max = (1 << (8 + shift)) - 1;
        for (var segment = 0; segment < 4; segment++) {
            var tc = (byte) (tc0 >> (segment * 8));
            if (tc < 0) {
                continue;
            }
            for (var row = 0; row < 4; row++) {
                filterLumaNormal(pix, offset + (segment * 4 + row) * stride, 1, alpha << shift, beta << shift,
                        tc << shift, max);
            }
        }
    }

    @Override
    public void lumaEdgeHorizontal(short[] pix, int offset, int stride, int alpha, int beta, int tc0, int shift) {
        var max = (1 << (8 + shift)) - 1;
        for (var segment = 0; segment < 4; segment++) {
            var tc = (byte) (tc0 >> (segment * 8));
            if (tc < 0) {
                continue;
            }
            for (var column = 0; column < 4; column++) {
                filterLumaNormal(pix, offset + segment * 4 + column, stride, alpha << shift, beta << shift,
                        tc << shift, max);
            }
        }
    }

    /**
     * Filters one line of samples across a luma edge with boundary strength below 4.
     *
     * @param pix the plane
     * @param q the index of {@code q0}
     * @param step the distance between successive samples across the edge
     * @param alpha the scaled {@code alpha} threshold
     * @param beta the scaled {@code beta} threshold
     * @param tc0 the scaled {@code tC0}
     * @param max the largest sample value
     */
    static void filterLumaNormal(short[] pix, int q, int step, int alpha, int beta, int tc0, int max) {
        int p0 = pix[q - step];
        int q0 = pix[q];
        int p1 = pix[q - 2 * step];
        int q1 = pix[q + step];
        if (Math.abs(p0 - q0) >= alpha || Math.abs(p1 - p0) >= beta || Math.abs(q1 - q0) >= beta) {
            return;
        }
        int p2 = pix[q - 3 * step];
        int q2 = pix[q + 2 * step];
        var ap = Math.abs(p2 - p0) < beta;
        var aq = Math.abs(q2 - q0) < beta;
        var tc = tc0 + (ap ? 1 : 0) + (aq ? 1 : 0);
        var delta = clip3((((q0 - p0) << 2) + (p1 - q1) + 4) >> 3, tc);
        pix[q - step] = (short) H264WideDsp.clip(p0 + delta, max);
        pix[q] = (short) H264WideDsp.clip(q0 - delta, max);
        var average = (p0 + q0 + 1) >> 1;
        if (ap) {
            pix[q - 2 * step] = (short) (p1 + clip3((p2 + average - (p1 << 1)) >> 1, tc0));
        }
        if (aq) {
            pix[q + step] = (short) (q1 + clip3((q2 + average - (q1 << 1)) >> 1, tc0));
        }
    }

    @Override
    public void lumaEdgeVerticalStrong(short[] pix, int offset, int stride, int alpha, int beta, int shift) {
        for (var row = 0; row < 16; row++) {
            filterLumaStrong(pix, offset + row * stride, 1, alpha << shift, beta << shift);
        }
    }

    @Override
    public void lumaEdgeHorizontalStrong(short[] pix, int offset, int stride, int alpha, int beta, int shift) {
        for (var column = 0; column < 16; column++) {
            filterLumaStrong(pix, offset + column, stride, alpha << shift, beta << shift);
        }
    }

    /**
     * Filters one line of samples across a luma edge with boundary strength 4.
     *
     * @param pix the plane
     * @param q the index of {@code q0}
     * @param step the distance between successive samples across the edge
     * @param alpha the scaled {@code alpha} threshold
     * @param beta the scaled {@code beta} threshold
     */
    static void filterLumaStrong(short[] pix, int q, int step, int alpha, int beta) {
        int p0 = pix[q - step];
        int q0 = pix[q];
        int p1 = pix[q - 2 * step];
        int q1 = pix[q + step];
        var difference = Math.abs(p0 - q0);
        if (difference >= alpha || Math.abs(p1 - p0) >= beta || Math.abs(q1 - q0) >= beta) {
            return;
        }
        int p2 = pix[q - 3 * step];
        int q2 = pix[q + 2 * step];
        var strong = difference < ((alpha >> 2) + 2);
        if (strong && Math.abs(p2 - p0) < beta) {
            int p3 = pix[q - 4 * step];
            pix[q - step] = (short) ((p2 + 2 * p1 + 2 * p0 + 2 * q0 + q1 + 4) >> 3);
            pix[q - 2 * step] = (short) ((p2 + p1 + p0 + q0 + 2) >> 2);
            pix[q - 3 * step] = (short) ((2 * p3 + 3 * p2 + p1 + p0 + q0 + 4) >> 3);
        } else {
            pix[q - step] = (short) ((2 * p1 + p0 + q1 + 2) >> 2);
        }
        if (strong && Math.abs(q2 - q0) < beta) {
            int q3 = pix[q + 3 * step];
            pix[q] = (short) ((p1 + 2 * p0 + 2 * q0 + 2 * q1 + q2 + 4) >> 3);
            pix[q + step] = (short) ((p0 + q0 + q1 + q2 + 2) >> 2);
            pix[q + 2 * step] = (short) ((2 * q3 + 3 * q2 + q1 + q0 + p0 + 4) >> 3);
        } else {
            pix[q] = (short) ((2 * q1 + q0 + p1 + 2) >> 2);
        }
    }

    @Override
    public void chromaEdgesVertical(short[] cb, short[] cr, int offset, int stride, int edges, int[] params,
                                    int shift) {
        for (var edge = 0; edge < 2; edge++) {
            if ((edges & (1 << edge)) == 0) {
                continue;
            }
            var at = edge * 4;
            var q0 = offset + edge * 4;
            chromaEdge(cb, q0, stride, 1, params[at], params[at + 2], shift);
            chromaEdge(cr, q0, stride, 1, params[at + 1], params[at + 3], shift);
        }
    }

    @Override
    public void chromaEdgeHorizontal(short[] cb, short[] cr, int offset, int stride, int[] params, int at, int shift) {
        chromaEdge(cb, offset, 1, stride, params[at], params[at + 2], shift);
        chromaEdge(cr, offset, 1, stride, params[at + 1], params[at + 3], shift);
    }

    /**
     * Filters one chroma edge of 8 lines in one plane.
     *
     * @param pix the plane
     * @param q0 the index of the first line's {@code q0} sample
     * @param lineStep the index distance between consecutive lines along the edge
     * @param sampleStep the index distance between consecutive samples across the edge
     * @param thresholds the 8-bit thresholds, {@code alpha | beta << 8}
     * @param tc0 the packed per-segment 8-bit {@code tC0'}
     * @param shift the chroma bit depth minus 8
     */
    private static void chromaEdge(short[] pix, int q0, int lineStep, int sampleStep, int thresholds, int tc0,
                                   int shift) {
        var alpha = (thresholds & 0xFF) << shift;
        var beta = ((thresholds >> 8) & 0xFF) << shift;
        var max = (1 << (8 + shift)) - 1;
        for (var segment = 0; segment < 4; segment++) {
            var tc = (byte) (tc0 >> (segment * 8));
            if (tc < 0) {
                continue;
            }
            for (var line = 0; line < 2; line++) {
                var q = q0 + (segment * 2 + line) * lineStep;
                if (tc == H264Dsp.CHROMA_STRONG) {
                    filterChromaStrong(pix, q, sampleStep, alpha, beta);
                } else {
                    filterChromaNormal(pix, q, sampleStep, alpha, beta, tc << shift, max);
                }
            }
        }
    }

    /**
     * Filters one line of samples across a chroma edge with boundary strength below 4.
     *
     * @param pix the plane
     * @param q the index of {@code q0}
     * @param step the distance between successive samples across the edge
     * @param alpha the scaled {@code alpha} threshold
     * @param beta the scaled {@code beta} threshold
     * @param tc0 the scaled {@code tC0}
     * @param max the largest sample value
     */
    static void filterChromaNormal(short[] pix, int q, int step, int alpha, int beta, int tc0, int max) {
        int p0 = pix[q - step];
        int q0 = pix[q];
        int p1 = pix[q - 2 * step];
        int q1 = pix[q + step];
        if (Math.abs(p0 - q0) >= alpha || Math.abs(p1 - p0) >= beta || Math.abs(q1 - q0) >= beta) {
            return;
        }
        var delta = clip3((((q0 - p0) << 2) + (p1 - q1) + 4) >> 3, tc0 + 1);
        pix[q - step] = (short) H264WideDsp.clip(p0 + delta, max);
        pix[q] = (short) H264WideDsp.clip(q0 - delta, max);
    }

    /**
     * Filters one line of samples across a chroma edge with boundary strength 4.
     *
     * @param pix the plane
     * @param q the index of {@code q0}
     * @param step the distance between successive samples across the edge
     * @param alpha the scaled {@code alpha} threshold
     * @param beta the scaled {@code beta} threshold
     */
    static void filterChromaStrong(short[] pix, int q, int step, int alpha, int beta) {
        int p0 = pix[q - step];
        int q0 = pix[q];
        int p1 = pix[q - 2 * step];
        int q1 = pix[q + step];
        if (Math.abs(p0 - q0) >= alpha || Math.abs(p1 - p0) >= beta || Math.abs(q1 - q0) >= beta) {
            return;
        }
        pix[q - step] = (short) ((2 * p1 + p0 + q1 + 2) >> 2);
        pix[q] = (short) ((2 * q1 + q0 + p1 + 2) >> 2);
    }
}
