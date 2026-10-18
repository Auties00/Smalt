/* Ports libvpx (vp8/common/idctllm.c, filter.c, loopfilter_filters.c). Copyright (c) 2010 The WebM project authors;
   BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.video.vp8.internal;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;

/**
 * Implements {@link Vp8Dsp} in plain Java; the reference implementation for {@link VectorVp8Dsp}.
 *
 * <p>Every kernel is bit-exact with libvpx's C kernel on every input, including the 16-bit
 * truncation of transform intermediates.
 */
public final class ScalarVp8Dsp extends Vp8Dsp {
    /**
     * Reads and writes four bytes of a byte array as an {@code int}.
     */
    private static final VarHandle INT = MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.LITTLE_ENDIAN);

    /**
     * The six-tap sub-pixel filters of RFC 6386 section 18.3, flattened as {@code [fraction 8][tap 6]}.
     */
    static final int[] SIXTAP = {
            0, 0, 128, 0, 0, 0,
            0, -6, 123, 12, -1, 0,
            2, -11, 108, 36, -8, 1,
            0, -9, 93, 50, -6, 0,
            3, -16, 77, 77, -16, 3,
            0, -6, 50, 93, -9, 0,
            1, -8, 36, 108, -11, 2,
            0, -1, 12, 123, -6, 0
    };

    /**
     * The first bilinear tap of each eighth-pixel fraction; the second tap is {@code 128} minus it.
     */
    static final int[] BILINEAR = {128, 112, 96, 80, 64, 48, 32, 16};

    /**
     * The fixed-point constant {@code sqrt(2) * cos(pi / 8) - 1} of the inverse DCT, in 1/65536.
     */
    static final int COS_PI8_SQRT2_MINUS1 = 20091;

    /**
     * The fixed-point constant {@code sqrt(2) * sin(pi / 8)} of the inverse DCT, in 1/65536.
     */
    static final int SIN_PI8_SQRT2 = 35468;

    /**
     * Creates the scalar implementation.
     */
    public ScalarVp8Dsp() {
    }

    @Override
    public void idctAdd(short[] coef, int coefOff, byte[] dst, int dstOff, int stride) {
        int t0, t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13, t14, t15;
        {
            var i0 = coef[coefOff];
            var i4 = coef[coefOff + 4];
            var i8 = coef[coefOff + 8];
            var i12 = coef[coefOff + 12];
            var a1 = i0 + i8;
            var b1 = i0 - i8;
            var c1 = ((i4 * SIN_PI8_SQRT2) >> 16) - (i12 + ((i12 * COS_PI8_SQRT2_MINUS1) >> 16));
            var d1 = (i4 + ((i4 * COS_PI8_SQRT2_MINUS1) >> 16)) + ((i12 * SIN_PI8_SQRT2) >> 16);
            t0 = (short) (a1 + d1);
            t12 = (short) (a1 - d1);
            t4 = (short) (b1 + c1);
            t8 = (short) (b1 - c1);
        }
        {
            var i0 = coef[coefOff + 1];
            var i4 = coef[coefOff + 5];
            var i8 = coef[coefOff + 9];
            var i12 = coef[coefOff + 13];
            var a1 = i0 + i8;
            var b1 = i0 - i8;
            var c1 = ((i4 * SIN_PI8_SQRT2) >> 16) - (i12 + ((i12 * COS_PI8_SQRT2_MINUS1) >> 16));
            var d1 = (i4 + ((i4 * COS_PI8_SQRT2_MINUS1) >> 16)) + ((i12 * SIN_PI8_SQRT2) >> 16);
            t1 = (short) (a1 + d1);
            t13 = (short) (a1 - d1);
            t5 = (short) (b1 + c1);
            t9 = (short) (b1 - c1);
        }
        {
            var i0 = coef[coefOff + 2];
            var i4 = coef[coefOff + 6];
            var i8 = coef[coefOff + 10];
            var i12 = coef[coefOff + 14];
            var a1 = i0 + i8;
            var b1 = i0 - i8;
            var c1 = ((i4 * SIN_PI8_SQRT2) >> 16) - (i12 + ((i12 * COS_PI8_SQRT2_MINUS1) >> 16));
            var d1 = (i4 + ((i4 * COS_PI8_SQRT2_MINUS1) >> 16)) + ((i12 * SIN_PI8_SQRT2) >> 16);
            t2 = (short) (a1 + d1);
            t14 = (short) (a1 - d1);
            t6 = (short) (b1 + c1);
            t10 = (short) (b1 - c1);
        }
        {
            var i0 = coef[coefOff + 3];
            var i4 = coef[coefOff + 7];
            var i8 = coef[coefOff + 11];
            var i12 = coef[coefOff + 15];
            var a1 = i0 + i8;
            var b1 = i0 - i8;
            var c1 = ((i4 * SIN_PI8_SQRT2) >> 16) - (i12 + ((i12 * COS_PI8_SQRT2_MINUS1) >> 16));
            var d1 = (i4 + ((i4 * COS_PI8_SQRT2_MINUS1) >> 16)) + ((i12 * SIN_PI8_SQRT2) >> 16);
            t3 = (short) (a1 + d1);
            t15 = (short) (a1 - d1);
            t7 = (short) (b1 + c1);
            t11 = (short) (b1 - c1);
        }
        for (var k = 0; k < 16; k++) {
            coef[coefOff + k] = 0;
        }
        idctRow(t0, t1, t2, t3, dst, dstOff);
        idctRow(t4, t5, t6, t7, dst, dstOff + stride);
        idctRow(t8, t9, t10, t11, dst, dstOff + 2 * stride);
        idctRow(t12, t13, t14, t15, dst, dstOff + 3 * stride);
    }

    /**
     * Applies the horizontal pass of the inverse DCT to one row and adds it to four pixels.
     *
     * @param i0  the first intermediate of the row
     * @param i1  the second intermediate of the row
     * @param i2  the third intermediate of the row
     * @param i3  the fourth intermediate of the row
     * @param dst the plane
     * @param off the index of the row's first pixel
     */
    private static void idctRow(int i0, int i1, int i2, int i3, byte[] dst, int off) {
        var a1 = i0 + i2;
        var b1 = i0 - i2;
        var c1 = ((i1 * SIN_PI8_SQRT2) >> 16) - (i3 + ((i3 * COS_PI8_SQRT2_MINUS1) >> 16));
        var d1 = (i1 + ((i1 * COS_PI8_SQRT2_MINUS1) >> 16)) + ((i3 * SIN_PI8_SQRT2) >> 16);
        dst[off] = clamp255((short) ((a1 + d1 + 4) >> 3) + (dst[off] & 0xFF));
        dst[off + 1] = clamp255((short) ((b1 + c1 + 4) >> 3) + (dst[off + 1] & 0xFF));
        dst[off + 2] = clamp255((short) ((b1 - c1 + 4) >> 3) + (dst[off + 2] & 0xFF));
        dst[off + 3] = clamp255((short) ((a1 - d1 + 4) >> 3) + (dst[off + 3] & 0xFF));
    }

    /**
     * Clamps a value to {@code [0, 255]} and narrows it to a pixel.
     *
     * @param v the value
     * @return the clamped pixel
     */
    private static byte clamp255(int v) {
        return (byte) (v < 0 ? 0 : (v > 255 ? 255 : v));
    }

    @Override
    public void dcOnlyAdd(int dc, byte[] dst, int dstOff, int stride) {
        var a1 = (dc + 4) >> 3;
        for (var r = 0; r < 4; r++) {
            var off = dstOff + r * stride;
            dst[off] = clamp255(a1 + (dst[off] & 0xFF));
            dst[off + 1] = clamp255(a1 + (dst[off + 1] & 0xFF));
            dst[off + 2] = clamp255(a1 + (dst[off + 2] & 0xFF));
            dst[off + 3] = clamp255(a1 + (dst[off + 3] & 0xFF));
        }
    }

    @Override
    public void sixtap(byte[] src, int srcOff, int srcStride, byte[] dst, int dstOff, int dstStride,
                       int width, int height, int mx, int my, byte[] scratch) {
        if (my == 0) {
            if (mx == 0) {
                copyRows(src, srcOff, srcStride, dst, dstOff, dstStride, width, height);
            } else {
                sixtapHorizontal(src, srcOff, srcStride, dst, dstOff, dstStride, width, height, mx);
            }
        } else if (mx == 0) {
            sixtapVertical(src, srcOff, srcStride, dst, dstOff, dstStride, width, height, my);
        } else {
            sixtapHorizontal(src, srcOff - 2 * srcStride, srcStride, scratch, 0, width, width, height + 5, mx);
            sixtapVertical(scratch, 2 * width, width, dst, dstOff, dstStride, width, height, my);
        }
    }

    /**
     * Applies the horizontal six-tap filter of one fraction.
     *
     * @param src       the source plane
     * @param srcOff    the index of the first source pixel
     * @param srcStride the source row stride
     * @param dst       the destination plane
     * @param dstOff    the index of the first destination pixel
     * @param dstStride the destination row stride
     * @param width     the number of columns
     * @param height    the number of rows
     * @param fraction  the fraction in eighths
     */
    static void sixtapHorizontal(byte[] src, int srcOff, int srcStride, byte[] dst, int dstOff, int dstStride,
                                 int width, int height, int fraction) {
        var base = fraction * 6;
        var f0 = SIXTAP[base];
        var f1 = SIXTAP[base + 1];
        var f2 = SIXTAP[base + 2];
        var f3 = SIXTAP[base + 3];
        var f4 = SIXTAP[base + 4];
        var f5 = SIXTAP[base + 5];
        for (var r = 0; r < height; r++) {
            var s = srcOff + r * srcStride;
            var d = dstOff + r * dstStride;
            for (var x = 0; x < width; x++) {
                var p = s + x;
                var sum = (src[p - 2] & 0xFF) * f0 + (src[p - 1] & 0xFF) * f1 + (src[p] & 0xFF) * f2
                        + (src[p + 1] & 0xFF) * f3 + (src[p + 2] & 0xFF) * f4 + (src[p + 3] & 0xFF) * f5 + 64;
                dst[d + x] = clamp255(sum >> 7);
            }
        }
    }

    /**
     * Applies the vertical six-tap filter of one fraction.
     *
     * @param src       the source plane
     * @param srcOff    the index of the first source pixel
     * @param srcStride the source row stride
     * @param dst       the destination plane
     * @param dstOff    the index of the first destination pixel
     * @param dstStride the destination row stride
     * @param width     the number of columns
     * @param height    the number of rows
     * @param fraction  the fraction in eighths
     */
    static void sixtapVertical(byte[] src, int srcOff, int srcStride, byte[] dst, int dstOff, int dstStride,
                               int width, int height, int fraction) {
        var base = fraction * 6;
        var f0 = SIXTAP[base];
        var f1 = SIXTAP[base + 1];
        var f2 = SIXTAP[base + 2];
        var f3 = SIXTAP[base + 3];
        var f4 = SIXTAP[base + 4];
        var f5 = SIXTAP[base + 5];
        for (var r = 0; r < height; r++) {
            var s = srcOff + r * srcStride;
            var d = dstOff + r * dstStride;
            for (var x = 0; x < width; x++) {
                var p = s + x;
                var sum = (src[p - 2 * srcStride] & 0xFF) * f0 + (src[p - srcStride] & 0xFF) * f1
                        + (src[p] & 0xFF) * f2 + (src[p + srcStride] & 0xFF) * f3
                        + (src[p + 2 * srcStride] & 0xFF) * f4 + (src[p + 3 * srcStride] & 0xFF) * f5 + 64;
                dst[d + x] = clamp255(sum >> 7);
            }
        }
    }

    @Override
    public void bilinear(byte[] src, int srcOff, int srcStride, byte[] dst, int dstOff, int dstStride,
                         int width, int height, int mx, int my, byte[] scratch) {
        if (my == 0) {
            if (mx == 0) {
                copyRows(src, srcOff, srcStride, dst, dstOff, dstStride, width, height);
            } else {
                bilinearHorizontal(src, srcOff, srcStride, dst, dstOff, dstStride, width, height, mx);
            }
        } else if (mx == 0) {
            bilinearVertical(src, srcOff, srcStride, dst, dstOff, dstStride, width, height, my);
        } else {
            bilinearHorizontal(src, srcOff, srcStride, scratch, 0, width, width, height + 1, mx);
            bilinearVertical(scratch, 0, width, dst, dstOff, dstStride, width, height, my);
        }
    }

    /**
     * Applies the horizontal bilinear filter of one fraction.
     *
     * @param src       the source plane
     * @param srcOff    the index of the first source pixel
     * @param srcStride the source row stride
     * @param dst       the destination plane
     * @param dstOff    the index of the first destination pixel
     * @param dstStride the destination row stride
     * @param width     the number of columns
     * @param height    the number of rows
     * @param fraction  the fraction in eighths
     */
    static void bilinearHorizontal(byte[] src, int srcOff, int srcStride, byte[] dst, int dstOff, int dstStride,
                                   int width, int height, int fraction) {
        var f0 = BILINEAR[fraction];
        var f1 = 128 - f0;
        for (var r = 0; r < height; r++) {
            var s = srcOff + r * srcStride;
            var d = dstOff + r * dstStride;
            for (var x = 0; x < width; x++) {
                dst[d + x] = (byte) (((src[s + x] & 0xFF) * f0 + (src[s + x + 1] & 0xFF) * f1 + 64) >> 7);
            }
        }
    }

    /**
     * Applies the vertical bilinear filter of one fraction.
     *
     * @param src       the source plane
     * @param srcOff    the index of the first source pixel
     * @param srcStride the source row stride
     * @param dst       the destination plane
     * @param dstOff    the index of the first destination pixel
     * @param dstStride the destination row stride
     * @param width     the number of columns
     * @param height    the number of rows
     * @param fraction  the fraction in eighths
     */
    static void bilinearVertical(byte[] src, int srcOff, int srcStride, byte[] dst, int dstOff, int dstStride,
                                 int width, int height, int fraction) {
        var f0 = BILINEAR[fraction];
        var f1 = 128 - f0;
        for (var r = 0; r < height; r++) {
            var s = srcOff + r * srcStride;
            var d = dstOff + r * dstStride;
            for (var x = 0; x < width; x++) {
                dst[d + x] = (byte) (((src[s + x] & 0xFF) * f0 + (src[s + x + srcStride] & 0xFF) * f1 + 64) >> 7);
            }
        }
    }

    /**
     * Copies a block of pixels.
     *
     * @param src       the source plane
     * @param srcOff    the index of the first source pixel
     * @param srcStride the source row stride
     * @param dst       the destination plane
     * @param dstOff    the index of the first destination pixel
     * @param dstStride the destination row stride
     * @param width     the number of columns
     * @param height    the number of rows
     */
    static void copyRows(byte[] src, int srcOff, int srcStride, byte[] dst, int dstOff, int dstStride,
                         int width, int height) {
        for (var r = 0; r < height; r++) {
            System.arraycopy(src, srcOff + r * srcStride, dst, dstOff + r * dstStride, width);
        }
    }

    @Override
    public void copy(byte[] src, int srcOff, int srcStride, byte[] dst, int dstOff, int dstStride, int width, int height) {
        for (var r = 0; r < height; r++) {
            var s = srcOff + r * srcStride;
            var d = dstOff + r * dstStride;
            for (var x = 0; x < width; x += 4) {
                INT.set(dst, d + x, (int) INT.get(src, s + x));
            }
        }
    }

    @Override
    public void filterNormal(byte[] y, int yOff, int yStride, byte[] u, byte[] v, int uvOff, int uvStride,
                             int mbLimit, int subLimit, int interiorLimit, int hevThreshold, int edges,
                             byte[] scratch) {
        var left = (edges & EDGE_LEFT) != 0;
        var top = (edges & EDGE_TOP) != 0;
        var inner = (edges & EDGE_INNER) != 0;
        filterPlane(y, yOff, yStride, 16, left, top, inner, mbLimit, subLimit, interiorLimit, hevThreshold);
        filterPlane(u, uvOff, uvStride, 8, left, top, inner, mbLimit, subLimit, interiorLimit, hevThreshold);
        filterPlane(v, uvOff, uvStride, 8, left, top, inner, mbLimit, subLimit, interiorLimit, hevThreshold);
    }

    /**
     * Applies the normal loop filter to the selected edges of one plane of a macroblock.
     *
     * @param p             the plane
     * @param off           the index of the macroblock's top-left pixel
     * @param stride        the row stride
     * @param size          the macroblock size in this plane: 16 or 8
     * @param left          whether to filter the left macroblock edge
     * @param top           whether to filter the top macroblock edge
     * @param inner         whether to filter the inner edges
     * @param mbLimit       the edge limit of macroblock edges
     * @param subLimit      the edge limit of inner edges
     * @param interiorLimit the interior difference limit
     * @param hevThreshold  the high edge variance threshold
     */
    private static void filterPlane(byte[] p, int off, int stride, int size, boolean left, boolean top, boolean inner,
                                    int mbLimit, int subLimit, int interiorLimit, int hevThreshold) {
        if (left) {
            mbEdge(p, off, 1, stride, size, mbLimit, interiorLimit, hevThreshold);
        }
        if (inner) {
            for (var x = 4; x < size; x += 4) {
                subEdge(p, off + x, 1, stride, size, subLimit, interiorLimit, hevThreshold);
            }
        }
        if (top) {
            mbEdge(p, off, stride, 1, size, mbLimit, interiorLimit, hevThreshold);
        }
        if (inner) {
            for (var y = 4; y < size; y += 4) {
                subEdge(p, off + y * stride, stride, 1, size, subLimit, interiorLimit, hevThreshold);
            }
        }
    }

    /**
     * Clamps a value to the signed 8-bit range.
     *
     * @param t the value
     * @return the value clamped to {@code [-128, 127]}
     */
    private static int s8(int t) {
        return t < -128 ? -128 : (t > 127 ? 127 : t);
    }

    /**
     * Filters a macroblock edge with the six-pixel filter of {@code vp8_mbfilter}.
     *
     * @param p      the plane
     * @param off    the index of the first pixel on the far side of the edge
     * @param step   the distance between pixels across the edge
     * @param pitch  the distance between pixels along the edge
     * @param count  the edge length in pixels
     * @param limit  the edge limit
     * @param ilimit the interior limit
     * @param thresh the high edge variance threshold
     */
    static void mbEdge(byte[] p, int off, int step, int pitch, int count, int limit, int ilimit, int thresh) {
        for (var i = 0; i < count; i++, off += pitch) {
            var p3 = p[off - 4 * step] & 0xFF;
            var p2 = p[off - 3 * step] & 0xFF;
            var p1 = p[off - 2 * step] & 0xFF;
            var p0 = p[off - step] & 0xFF;
            var q0 = p[off] & 0xFF;
            var q1 = p[off + step] & 0xFF;
            var q2 = p[off + 2 * step] & 0xFF;
            var q3 = p[off + 3 * step] & 0xFF;
            if (Math.abs(p3 - p2) > ilimit || Math.abs(p2 - p1) > ilimit || Math.abs(p1 - p0) > ilimit
                    || Math.abs(q1 - q0) > ilimit || Math.abs(q2 - q1) > ilimit || Math.abs(q3 - q2) > ilimit
                    || Math.abs(p0 - q0) * 2 + (Math.abs(p1 - q1) >> 1) > limit) {
                continue;
            }
            var hev = Math.abs(p1 - p0) > thresh || Math.abs(q1 - q0) > thresh;
            var ps2 = p2 - 128;
            var ps1 = p1 - 128;
            var ps0 = p0 - 128;
            var qs0 = q0 - 128;
            var qs1 = q1 - 128;
            var qs2 = q2 - 128;
            var fv = s8(ps1 - qs1);
            fv = s8(fv + 3 * (qs0 - ps0));
            if (hev) {
                var f1 = s8(fv + 4) >> 3;
                var f2 = s8(fv + 3) >> 3;
                p[off] = (byte) (s8(qs0 - f1) + 128);
                p[off - step] = (byte) (s8(ps0 + f2) + 128);
            } else {
                var u = s8((63 + fv * 27) >> 7);
                p[off] = (byte) (s8(qs0 - u) + 128);
                p[off - step] = (byte) (s8(ps0 + u) + 128);
                u = s8((63 + fv * 18) >> 7);
                p[off + step] = (byte) (s8(qs1 - u) + 128);
                p[off - 2 * step] = (byte) (s8(ps1 + u) + 128);
                u = s8((63 + fv * 9) >> 7);
                p[off + 2 * step] = (byte) (s8(qs2 - u) + 128);
                p[off - 3 * step] = (byte) (s8(ps2 + u) + 128);
            }
        }
    }

    /**
     * Filters an inner subblock edge with the four-pixel filter of {@code vp8_filter}.
     *
     * @param p      the plane
     * @param off    the index of the first pixel on the far side of the edge
     * @param step   the distance between pixels across the edge
     * @param pitch  the distance between pixels along the edge
     * @param count  the edge length in pixels
     * @param limit  the edge limit
     * @param ilimit the interior limit
     * @param thresh the high edge variance threshold
     */
    static void subEdge(byte[] p, int off, int step, int pitch, int count, int limit, int ilimit, int thresh) {
        for (var i = 0; i < count; i++, off += pitch) {
            var p3 = p[off - 4 * step] & 0xFF;
            var p2 = p[off - 3 * step] & 0xFF;
            var p1 = p[off - 2 * step] & 0xFF;
            var p0 = p[off - step] & 0xFF;
            var q0 = p[off] & 0xFF;
            var q1 = p[off + step] & 0xFF;
            var q2 = p[off + 2 * step] & 0xFF;
            var q3 = p[off + 3 * step] & 0xFF;
            if (Math.abs(p3 - p2) > ilimit || Math.abs(p2 - p1) > ilimit || Math.abs(p1 - p0) > ilimit
                    || Math.abs(q1 - q0) > ilimit || Math.abs(q2 - q1) > ilimit || Math.abs(q3 - q2) > ilimit
                    || Math.abs(p0 - q0) * 2 + (Math.abs(p1 - q1) >> 1) > limit) {
                continue;
            }
            var hev = Math.abs(p1 - p0) > thresh || Math.abs(q1 - q0) > thresh;
            var ps1 = p1 - 128;
            var ps0 = p0 - 128;
            var qs0 = q0 - 128;
            var qs1 = q1 - 128;
            var fv = hev ? s8(ps1 - qs1) : 0;
            fv = s8(fv + 3 * (qs0 - ps0));
            var f1 = s8(fv + 4) >> 3;
            var f2 = s8(fv + 3) >> 3;
            p[off] = (byte) (s8(qs0 - f1) + 128);
            p[off - step] = (byte) (s8(ps0 + f2) + 128);
            if (!hev) {
                var a = (f1 + 1) >> 1;
                p[off + step] = (byte) (s8(qs1 - a) + 128);
                p[off - 2 * step] = (byte) (s8(ps1 + a) + 128);
            }
        }
    }

    @Override
    public void filterSimple(byte[] y, int yOff, int yStride, int mbLimit, int subLimit, int edges, byte[] scratch) {
        var inner = (edges & EDGE_INNER) != 0;
        if ((edges & EDGE_LEFT) != 0) {
            simpleEdge(y, yOff, 1, yStride, mbLimit);
        }
        if (inner) {
            simpleEdge(y, yOff + 4, 1, yStride, subLimit);
            simpleEdge(y, yOff + 8, 1, yStride, subLimit);
            simpleEdge(y, yOff + 12, 1, yStride, subLimit);
        }
        if ((edges & EDGE_TOP) != 0) {
            simpleEdge(y, yOff, yStride, 1, mbLimit);
        }
        if (inner) {
            simpleEdge(y, yOff + 4 * yStride, yStride, 1, subLimit);
            simpleEdge(y, yOff + 8 * yStride, yStride, 1, subLimit);
            simpleEdge(y, yOff + 12 * yStride, yStride, 1, subLimit);
        }
    }

    /**
     * Filters one 16-pixel luma edge with the simple filter.
     *
     * @param p     the plane
     * @param off   the index of the first pixel on the far side of the edge
     * @param step  the distance between pixels across the edge
     * @param pitch the distance between pixels along the edge
     * @param limit the edge limit
     */
    static void simpleEdge(byte[] p, int off, int step, int pitch, int limit) {
        for (var i = 0; i < 16; i++, off += pitch) {
            var p1 = p[off - 2 * step] & 0xFF;
            var p0 = p[off - step] & 0xFF;
            var q0 = p[off] & 0xFF;
            var q1 = p[off + step] & 0xFF;
            if (Math.abs(p0 - q0) * 2 + (Math.abs(p1 - q1) >> 1) > limit) {
                continue;
            }
            var fv = s8(p1 - q1);
            fv = s8(fv + 3 * (q0 - p0));
            var f1 = s8(fv + 4) >> 3;
            var f2 = s8(fv + 3) >> 3;
            p[off] = (byte) (s8(q0 - 128 - f1) + 128);
            p[off - step] = (byte) (s8(p0 - 128 + f2) + 128);
        }
    }
}
