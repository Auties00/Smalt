/* Ports libvpx (vpx_dsp/inv_txfm.c, vp9/common/vp9_idct.c, vpx_dsp/vpx_convolve.c, vpx_dsp/loopfilter.c,
   vpx_dsp/intrapred.c). Copyright (c) 2010, The WebM Project authors; BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.video.vp9.internal;

import java.util.Arrays;

/**
 * Implements the 8-bit VP9 pixel kernels in plain Java, the bit-exact reference of
 * {@link VectorVp9Dsp}.
 *
 * <p>The one-dimensional transforms keep libvpx's 16-bit truncation of the DCT stages and its
 * 32-bit arithmetic, so that they reproduce libvpx on every input, conformant or not.
 *
 * @implNote This implementation ports libvpx's C kernels: {@code vpx_dsp/inv_txfm.c} and
 * {@code vp9/common/vp9_idct.c} (transforms), {@code vpx_dsp/vpx_convolve.c} (motion
 * compensation), {@code vpx_dsp/loopfilter.c} (loop filters) and {@code vpx_dsp/intrapred.c}
 * (intra predictors). The one-dimensional transforms keep libvpx's names and stage order, because
 * the vector transforms are generated from them.
 */
final class ScalarVp9Dsp extends Vp9Dsp {
    /**
     * The raster coefficient layout of each transform size: entry {@code i} is {@code i}.
     */
    private static final short[][] RASTER = {raster(4), raster(8), raster(16), raster(32)};

    /**
     * The DCT constant {@code round(16384 * cos(1 * pi / 64))}.
     */
    static final int COSPI_1_64 = 16364;

    /**
     * The DCT constant {@code round(16384 * cos(2 * pi / 64))}.
     */
    static final int COSPI_2_64 = 16305;

    /**
     * The DCT constant {@code round(16384 * cos(3 * pi / 64))}.
     */
    static final int COSPI_3_64 = 16207;

    /**
     * The DCT constant {@code round(16384 * cos(4 * pi / 64))}.
     */
    static final int COSPI_4_64 = 16069;

    /**
     * The DCT constant {@code round(16384 * cos(5 * pi / 64))}.
     */
    static final int COSPI_5_64 = 15893;

    /**
     * The DCT constant {@code round(16384 * cos(6 * pi / 64))}.
     */
    static final int COSPI_6_64 = 15679;

    /**
     * The DCT constant {@code round(16384 * cos(7 * pi / 64))}.
     */
    static final int COSPI_7_64 = 15426;

    /**
     * The DCT constant {@code round(16384 * cos(8 * pi / 64))}.
     */
    static final int COSPI_8_64 = 15137;

    /**
     * The DCT constant {@code round(16384 * cos(9 * pi / 64))}.
     */
    static final int COSPI_9_64 = 14811;

    /**
     * The DCT constant {@code round(16384 * cos(10 * pi / 64))}.
     */
    static final int COSPI_10_64 = 14449;

    /**
     * The DCT constant {@code round(16384 * cos(11 * pi / 64))}.
     */
    static final int COSPI_11_64 = 14053;

    /**
     * The DCT constant {@code round(16384 * cos(12 * pi / 64))}.
     */
    static final int COSPI_12_64 = 13623;

    /**
     * The DCT constant {@code round(16384 * cos(13 * pi / 64))}.
     */
    static final int COSPI_13_64 = 13160;

    /**
     * The DCT constant {@code round(16384 * cos(14 * pi / 64))}.
     */
    static final int COSPI_14_64 = 12665;

    /**
     * The DCT constant {@code round(16384 * cos(15 * pi / 64))}.
     */
    static final int COSPI_15_64 = 12140;

    /**
     * The DCT constant {@code round(16384 * cos(16 * pi / 64))}.
     */
    static final int COSPI_16_64 = 11585;

    /**
     * The DCT constant {@code round(16384 * cos(17 * pi / 64))}.
     */
    static final int COSPI_17_64 = 11003;

    /**
     * The DCT constant {@code round(16384 * cos(18 * pi / 64))}.
     */
    static final int COSPI_18_64 = 10394;

    /**
     * The DCT constant {@code round(16384 * cos(19 * pi / 64))}.
     */
    static final int COSPI_19_64 = 9760;

    /**
     * The DCT constant {@code round(16384 * cos(20 * pi / 64))}.
     */
    static final int COSPI_20_64 = 9102;

    /**
     * The DCT constant {@code round(16384 * cos(21 * pi / 64))}.
     */
    static final int COSPI_21_64 = 8423;

    /**
     * The DCT constant {@code round(16384 * cos(22 * pi / 64))}.
     */
    static final int COSPI_22_64 = 7723;

    /**
     * The DCT constant {@code round(16384 * cos(23 * pi / 64))}.
     */
    static final int COSPI_23_64 = 7005;

    /**
     * The DCT constant {@code round(16384 * cos(24 * pi / 64))}.
     */
    static final int COSPI_24_64 = 6270;

    /**
     * The DCT constant {@code round(16384 * cos(25 * pi / 64))}.
     */
    static final int COSPI_25_64 = 5520;

    /**
     * The DCT constant {@code round(16384 * cos(26 * pi / 64))}.
     */
    static final int COSPI_26_64 = 4756;

    /**
     * The DCT constant {@code round(16384 * cos(27 * pi / 64))}.
     */
    static final int COSPI_27_64 = 3981;

    /**
     * The DCT constant {@code round(16384 * cos(28 * pi / 64))}.
     */
    static final int COSPI_28_64 = 3196;

    /**
     * The DCT constant {@code round(16384 * cos(29 * pi / 64))}.
     */
    static final int COSPI_29_64 = 2404;

    /**
     * The DCT constant {@code round(16384 * cos(30 * pi / 64))}.
     */
    static final int COSPI_30_64 = 1606;

    /**
     * The DCT constant {@code round(16384 * cos(31 * pi / 64))}.
     */
    static final int COSPI_31_64 = 804;

    /**
     * The 4-point ADST constant {@code round(16384 * 2 * sqrt(2) * sin(1 * pi / 9) / 3)}.
     */
    static final int SINPI_1_9 = 5283;

    /**
     * The 4-point ADST constant {@code round(16384 * 2 * sqrt(2) * sin(2 * pi / 9) / 3)}.
     */
    static final int SINPI_2_9 = 9929;

    /**
     * The 4-point ADST constant {@code round(16384 * 2 * sqrt(2) * sin(3 * pi / 9) / 3)}.
     */
    static final int SINPI_3_9 = 13377;

    /**
     * The 4-point ADST constant {@code round(16384 * 2 * sqrt(2) * sin(4 * pi / 9) / 3)}.
     */
    static final int SINPI_4_9 = 15212;

    /**
     * Creates the scalar implementation.
     */
    public ScalarVp9Dsp() {
    }

    /**
     * Returns the identity layout of a block size.
     *
     * @param size the block size
     * @return the layout
     */
    private static short[] raster(int size) {
        var layout = new short[size * size];
        for (var i = 0; i < layout.length; i++) {
            layout[i] = (short) i;
        }
        return layout;
    }

    /**
     * Returns a sample clamped to {@code [0, 255]}.
     *
     * @param value the sample before clipping
     * @return the clamped value
     */
    static int clip(int value) {
        return value < 0 ? 0 : (value > 255 ? 255 : value);
    }

    /**
     * {@inheritDoc}
     *
     * <p>This implementation stores the coefficients in raster order.
     */
    @Override
    public short[] coefficientLayout(int txSize) {
        return RASTER[txSize];
    }

    @Override
    public void inverseTransformAdd(int txSize, int txType, int eob, int[] coef, int coefOff, byte[] dst,
                                    int dstOff, int stride, int[] scratch) {
        if (txType == Vp9Constants.DCT_DCT && eob == 1) {
            dcOnlyAdd(txSize, coef[coefOff], dst, dstOff, stride);
            return;
        }
        var size = 4 << txSize;
        var rows = size;
        if (txType == Vp9Constants.DCT_DCT) {
            rows = partialRows(txSize, eob);
        }
        var shift = txSize == Vp9Constants.TX_4X4 ? 4 : txSize == Vp9Constants.TX_8X8 ? 5 : 6;
        var rowAdst = (txType & 2) != 0;
        var colAdst = (txType & 1) != 0;
        var out = scratch;
        var tempIn = size * size;
        var tempOut = tempIn + 32;
        for (var i = 0; i < rows; i++) {
            var input = coefOff + i * size;
            if (txSize == Vp9Constants.TX_32X32 && rows == 32) {
                var zero = 0;
                for (var j = 0; j < 32; j++) {
                    zero |= coef[input + j];
                }
                if ((short) zero == 0) {
                    Arrays.fill(out, i * 32, i * 32 + 32, 0);
                    continue;
                }
            }
            transform1d(txSize, rowAdst, coef, input, out, i * size);
        }
        if (rows < size) {
            Arrays.fill(out, rows * size, size * size, 0);
        }
        var round = 1 << (shift - 1);
        for (var i = 0; i < size; i++) {
            for (var j = 0; j < size; j++) {
                scratch[tempIn + j] = out[j * size + i];
            }
            transform1d(txSize, colAdst, scratch, tempIn, scratch, tempOut);
            for (var j = 0; j < size; j++) {
                var index = dstOff + j * stride + i;
                dst[index] = (byte) clip((dst[index] & 0xFF) + ((scratch[tempOut + j] + round) >> shift));
            }
        }
    }

    /**
     * Returns how many leading rows of a DCT block can hold nonzero coefficients given the
     * number of coded coefficients, the rows libvpx's reduced transforms process
     * ({@code vp9_idct8x8_add}, {@code vp9_idct16x16_add}, {@code vp9_idct32x32_add}).
     *
     * @param txSize the transform size
     * @param eob the number of coded coefficients
     * @return the number of rows to transform
     */
    static int partialRows(int txSize, int eob) {
        return switch (txSize) {
            case Vp9Constants.TX_8X8 -> eob <= 12 ? 4 : 8;
            case Vp9Constants.TX_16X16 -> eob <= 10 ? 4 : eob <= 38 ? 8 : 16;
            case Vp9Constants.TX_32X32 -> eob <= 34 ? 8 : eob <= 135 ? 16 : 32;
            default -> 4;
        };
    }

    /**
     * Adds the inverse DCT of a block whose only coefficient is the DC to the prediction
     * ({@code vpx_idct*_1_add}).
     *
     * @param txSize the transform size
     * @param dc the DC coefficient
     * @param dst the plane holding the prediction
     * @param dstOff the index of the block's top-left sample
     * @param stride the plane's row stride
     */
    private static void dcOnlyAdd(int txSize, int dc, byte[] dst, int dstOff, int stride) {
        var out = ((short) dc * COSPI_16_64 + 8192) >> 14;
        out = (out * COSPI_16_64 + 8192) >> 14;
        var shift = txSize == Vp9Constants.TX_4X4 ? 4 : txSize == Vp9Constants.TX_8X8 ? 5 : 6;
        var a1 = (out + (1 << (shift - 1))) >> shift;
        var size = 4 << txSize;
        for (var y = 0; y < size; y++) {
            var row = dstOff + y * stride;
            for (var x = 0; x < size; x++) {
                dst[row + x] = (byte) clip((dst[row + x] & 0xFF) + a1);
            }
        }
    }

    /**
     * Computes one row or column of an inverse transform.
     *
     * @param txSize the transform size
     * @param adst whether the dimension uses the ADST rather than the DCT
     * @param in the input array
     * @param inOff the index of the first input value
     * @param out the output array
     * @param outOff the index of the first output value
     */
    private static void transform1d(int txSize, boolean adst, int[] in, int inOff, int[] out, int outOff) {
        switch (txSize) {
            case Vp9Constants.TX_4X4 -> {
                if (adst) {
                    iadst4(in, inOff, out, outOff);
                } else {
                    idct4(in, inOff, out, outOff);
                }
            }
            case Vp9Constants.TX_8X8 -> {
                if (adst) {
                    iadst8(in, inOff, out, outOff);
                } else {
                    idct8(in, inOff, out, outOff);
                }
            }
            case Vp9Constants.TX_16X16 -> {
                if (adst) {
                    iadst16(in, inOff, out, outOff);
                } else {
                    idct16(in, inOff, out, outOff);
                }
            }
            default -> idct32(in, inOff, out, outOff);
        }
    }

    @Override
    public void inverseWhtAdd(int eob, int[] coef, int coefOff, byte[] dst, int dstOff, int stride, int[] scratch) {
        if (eob > 1) {
            var tmp = scratch;
            for (var i = 0; i < 4; i++) {
                var ip = coefOff + i * 4;
                var a1 = coef[ip] >> 2;
                var c1 = coef[ip + 1] >> 2;
                var d1 = coef[ip + 2] >> 2;
                var b1 = coef[ip + 3] >> 2;
                a1 += c1;
                d1 -= b1;
                var e1 = (a1 - d1) >> 1;
                b1 = e1 - b1;
                c1 = e1 - c1;
                a1 -= b1;
                d1 += c1;
                tmp[i * 4] = a1;
                tmp[i * 4 + 1] = b1;
                tmp[i * 4 + 2] = c1;
                tmp[i * 4 + 3] = d1;
            }
            for (var i = 0; i < 4; i++) {
                var a1 = tmp[i];
                var c1 = tmp[4 + i];
                var d1 = tmp[8 + i];
                var b1 = tmp[12 + i];
                a1 += c1;
                d1 -= b1;
                var e1 = (a1 - d1) >> 1;
                b1 = e1 - b1;
                c1 = e1 - c1;
                a1 -= b1;
                d1 += c1;
                var column = dstOff + i;
                dst[column] = (byte) clip((dst[column] & 0xFF) + a1);
                dst[column + stride] = (byte) clip((dst[column + stride] & 0xFF) + b1);
                dst[column + 2 * stride] = (byte) clip((dst[column + 2 * stride] & 0xFF) + c1);
                dst[column + 3 * stride] = (byte) clip((dst[column + 3 * stride] & 0xFF) + d1);
            }
        } else {
            var a1 = coef[coefOff] >> 2;
            var e1 = a1 >> 1;
            a1 -= e1;
            for (var i = 0; i < 4; i++) {
                var value = i == 0 ? a1 : e1;
                var e = value >> 1;
                var a = value - e;
                var column = dstOff + i;
                dst[column] = (byte) clip((dst[column] & 0xFF) + a);
                dst[column + stride] = (byte) clip((dst[column + stride] & 0xFF) + e);
                dst[column + 2 * stride] = (byte) clip((dst[column + 2 * stride] & 0xFF) + e);
                dst[column + 3 * stride] = (byte) clip((dst[column + 3 * stride] & 0xFF) + e);
            }
        }
    }

    @Override
    public void convolve(byte[] src, int srcOff, int srcStride, byte[] dst, int dstOff, int dstStride,
                         short[] kernels, int kernelBase, int subX, int xStep, int subY, int yStep,
                         int w, int h, boolean average, byte[] scratch) {
        if (xStep == 16 && yStep == 16) {
            if (subX == 0 && subY == 0) {
                copyOrAverage(src, srcOff, srcStride, dst, dstOff, dstStride, w, h, average);
            } else if (subY == 0) {
                convolveHorizontal(src, srcOff, srcStride, dst, dstOff, dstStride, kernels, kernelBase, subX, 16, w, h,
                        average);
            } else if (subX == 0) {
                convolveVertical(src, srcOff, srcStride, dst, dstOff, dstStride, kernels, kernelBase, subY, 16, w, h,
                        average);
            } else {
                convolve2d(src, srcOff, srcStride, dst, dstOff, dstStride, kernels, kernelBase, subX, 16, subY, 16, w, h,
                        average, scratch);
            }
        } else {
            convolve2d(src, srcOff, srcStride, dst, dstOff, dstStride, kernels, kernelBase, subX, xStep, subY, yStep, w, h,
                    average, scratch);
        }
    }

    /**
     * Copies a block, or averages it into the destination ({@code vpx_convolve_copy},
     * {@code vpx_convolve_avg}).
     *
     * @param src the source plane
     * @param srcOff the index of the first source sample
     * @param srcStride the source row stride
     * @param dst the destination plane
     * @param dstOff the index of the first destination sample
     * @param dstStride the destination row stride
     * @param w the block width
     * @param h the block height
     * @param average whether to average into the destination
     */
    private static void copyOrAverage(byte[] src, int srcOff, int srcStride, byte[] dst, int dstOff, int dstStride,
                                      int w, int h, boolean average) {
        for (var y = 0; y < h; y++) {
            var s = srcOff + y * srcStride;
            var d = dstOff + y * dstStride;
            if (average) {
                for (var x = 0; x < w; x++) {
                    dst[d + x] = (byte) (((dst[d + x] & 0xFF) + (src[s + x] & 0xFF) + 1) >> 1);
                }
            } else {
                System.arraycopy(src, s, dst, d, w);
            }
        }
    }

    /**
     * Filters a block horizontally ({@code convolve_horiz}, {@code convolve_avg_horiz}).
     *
     * @param src the source plane
     * @param srcOff the index of the source sample at the block's integer position
     * @param srcStride the source row stride
     * @param dst the destination plane
     * @param dstOff the index of the first destination sample
     * @param dstStride the destination row stride
     * @param kernels the filter kernels
     * @param kernelBase the index of phase 0 of the filter type
     * @param x0 the phase of the first column
     * @param xStep the horizontal step in sixteenths
     * @param w the block width
     * @param h the number of rows
     * @param average whether to average into the destination
     */
    private static void convolveHorizontal(byte[] src, int srcOff, int srcStride, byte[] dst, int dstOff,
                                           int dstStride, short[] kernels, int kernelBase, int x0, int xStep,
                                           int w, int h, boolean average) {
        for (var y = 0; y < h; y++) {
            var s = srcOff + y * srcStride - 3;
            var d = dstOff + y * dstStride;
            var xq = x0;
            for (var x = 0; x < w; x++) {
                var p = s + (xq >> 4);
                var k = kernelBase + (xq & 15) * 8;
                var sum = 0;
                for (var t = 0; t < 8; t++) {
                    sum += (src[p + t] & 0xFF) * kernels[k + t];
                }
                var value = clip((sum + 64) >> 7);
                if (average) {
                    value = ((dst[d + x] & 0xFF) + value + 1) >> 1;
                }
                dst[d + x] = (byte) value;
                xq += xStep;
            }
        }
    }

    /**
     * Filters a block vertically ({@code convolve_vert}, {@code convolve_avg_vert}).
     *
     * @param src the source plane
     * @param srcOff the index of the source sample at the block's integer position
     * @param srcStride the source row stride
     * @param dst the destination plane
     * @param dstOff the index of the first destination sample
     * @param dstStride the destination row stride
     * @param kernels the filter kernels
     * @param kernelBase the index of phase 0 of the filter type
     * @param y0 the phase of the first row
     * @param yStep the vertical step in sixteenths
     * @param w the block width
     * @param h the block height
     * @param average whether to average into the destination
     */
    private static void convolveVertical(byte[] src, int srcOff, int srcStride, byte[] dst, int dstOff,
                                         int dstStride, short[] kernels, int kernelBase, int y0, int yStep,
                                         int w, int h, boolean average) {
        var base = srcOff - 3 * srcStride;
        for (var x = 0; x < w; x++) {
            var yq = y0;
            for (var y = 0; y < h; y++) {
                var p = base + (yq >> 4) * srcStride + x;
                var k = kernelBase + (yq & 15) * 8;
                var sum = 0;
                for (var t = 0; t < 8; t++) {
                    sum += (src[p + t * srcStride] & 0xFF) * kernels[k + t];
                }
                var value = clip((sum + 64) >> 7);
                var d = dstOff + y * dstStride + x;
                if (average) {
                    value = ((dst[d] & 0xFF) + value + 1) >> 1;
                }
                dst[d] = (byte) value;
                yq += yStep;
            }
        }
    }

    /**
     * Filters a block horizontally into an intermediate buffer, then vertically into the
     * destination ({@code vpx_convolve8}, {@code vpx_convolve8_avg}, {@code vpx_scaled_2d}).
     *
     * @param src the source plane
     * @param srcOff the index of the source sample at the block's integer position
     * @param srcStride the source row stride
     * @param dst the destination plane
     * @param dstOff the index of the first destination sample
     * @param dstStride the destination row stride
     * @param kernels the filter kernels
     * @param kernelBase the index of phase 0 of the filter type
     * @param x0 the phase of the first column
     * @param xStep the horizontal step in sixteenths
     * @param y0 the phase of the first row
     * @param yStep the vertical step in sixteenths
     * @param w the block width
     * @param h the block height
     * @param average whether to average into the destination
     * @param scratch a scratch array of at least {@link Vp9Dsp#CONVOLVE_SCRATCH} bytes
     */
    private static void convolve2d(byte[] src, int srcOff, int srcStride, byte[] dst, int dstOff, int dstStride,
                                   short[] kernels, int kernelBase, int x0, int xStep, int y0, int yStep, int w, int h,
                                   boolean average, byte[] scratch) {
        var intermediate = (((h - 1) * yStep + y0) >> 4) + 8;
        convolveHorizontal(src, srcOff - 3 * srcStride, srcStride, scratch, 0, 64, kernels, kernelBase, x0, xStep, w,
                intermediate, false);
        if (average) {
            var temp = 64 * 135;
            convolveVertical(scratch, 3 * 64, 64, scratch, temp, 64, kernels, kernelBase, y0, yStep, w, h, false);
            copyOrAverage(scratch, temp, 64, dst, dstOff, dstStride, w, h, true);
        } else {
            convolveVertical(scratch, 3 * 64, 64, dst, dstOff, dstStride, kernels, kernelBase, y0, yStep, w, h, false);
        }
    }

    /**
     * Returns whether no difference across or along an edge exceeds the limits
     * ({@code filter_mask}).
     *
     * @param limit the interior limit
     * @param blimit the edge limit
     * @param p3 the fourth sample before the edge
     * @param p2 the third sample before the edge
     * @param p1 the second sample before the edge
     * @param p0 the sample before the edge
     * @param q0 the sample after the edge
     * @param q1 the second sample after the edge
     * @param q2 the third sample after the edge
     * @param q3 the fourth sample after the edge
     * @return {@code true} when the edge is filtered
     */
    private static boolean filterMask(int limit, int blimit, int p3, int p2, int p1, int p0, int q0, int q1, int q2,
                                      int q3) {
        return Math.abs(p3 - p2) <= limit && Math.abs(p2 - p1) <= limit && Math.abs(p1 - p0) <= limit
                && Math.abs(q1 - q0) <= limit && Math.abs(q2 - q1) <= limit && Math.abs(q3 - q2) <= limit
                && Math.abs(p0 - q0) * 2 + Math.abs(p1 - q1) / 2 <= blimit;
    }

    /**
     * Returns whether the four samples on each side of an edge are within 1 of the edge samples
     * ({@code flat_mask4} with threshold 1).
     *
     * @param p3 the fourth sample before the edge
     * @param p2 the third sample before the edge
     * @param p1 the second sample before the edge
     * @param p0 the sample before the edge
     * @param q0 the sample after the edge
     * @param q1 the second sample after the edge
     * @param q2 the third sample after the edge
     * @param q3 the fourth sample after the edge
     * @return {@code true} when the region is flat
     */
    private static boolean flat4(int p3, int p2, int p1, int p0, int q0, int q1, int q2, int q3) {
        return Math.abs(p1 - p0) <= 1 && Math.abs(q1 - q0) <= 1 && Math.abs(p2 - p0) <= 1 && Math.abs(q2 - q0) <= 1
                && Math.abs(p3 - p0) <= 1 && Math.abs(q3 - q0) <= 1;
    }

    /**
     * Returns a value clamped to the signed byte range.
     *
     * @param value the value to clamp to a signed byte
     * @return the clamped value
     */
    private static int signedClamp(int value) {
        return value < -128 ? -128 : (value > 127 ? 127 : value);
    }

    /**
     * Applies the 4-tap filter to one line across an edge ({@code filter4}), with the mask
     * already known to be set.
     *
     * @param s the plane
     * @param off the index of the first sample after the edge
     * @param step the distance between consecutive samples across the edge
     * @param thresh the high edge variance threshold
     */
    private static void filter4(byte[] s, int off, int step, int thresh) {
        var op1 = s[off - 2 * step] & 0xFF;
        var op0 = s[off - step] & 0xFF;
        var oq0 = s[off] & 0xFF;
        var oq1 = s[off + step] & 0xFF;
        var ps1 = (byte) (op1 ^ 0x80);
        var ps0 = (byte) (op0 ^ 0x80);
        var qs0 = (byte) (oq0 ^ 0x80);
        var qs1 = (byte) (oq1 ^ 0x80);
        var hev = Math.abs(op1 - op0) > thresh || Math.abs(oq1 - oq0) > thresh;
        var filter = hev ? signedClamp(ps1 - qs1) : 0;
        filter = signedClamp(filter + 3 * (qs0 - ps0));
        var filter1 = signedClamp(filter + 4) >> 3;
        var filter2 = signedClamp(filter + 3) >> 3;
        s[off] = (byte) (signedClamp(qs0 - filter1) ^ 0x80);
        s[off - step] = (byte) (signedClamp(ps0 + filter2) ^ 0x80);
        if (!hev) {
            filter = (filter1 + 1) >> 1;
            s[off + step] = (byte) (signedClamp(qs1 - filter) ^ 0x80);
            s[off - 2 * step] = (byte) (signedClamp(ps1 + filter) ^ 0x80);
        }
    }

    /**
     * Applies the 4-tap loop filter to {@code count} lines across an edge.
     *
     * @param s the plane
     * @param off the index of the first sample after the edge, on the first line
     * @param step the distance between consecutive samples across the edge
     * @param along the distance between consecutive lines
     * @param count the number of lines
     * @param blimit the edge limit
     * @param limit the interior limit
     * @param thresh the high edge variance threshold
     */
    private static void loopFilter4(byte[] s, int off, int step, int along, int count, int blimit, int limit,
                                    int thresh) {
        for (var i = 0; i < count; i++, off += along) {
            var p3 = s[off - 4 * step] & 0xFF;
            var p2 = s[off - 3 * step] & 0xFF;
            var p1 = s[off - 2 * step] & 0xFF;
            var p0 = s[off - step] & 0xFF;
            var q0 = s[off] & 0xFF;
            var q1 = s[off + step] & 0xFF;
            var q2 = s[off + 2 * step] & 0xFF;
            var q3 = s[off + 3 * step] & 0xFF;
            if (filterMask(limit, blimit, p3, p2, p1, p0, q0, q1, q2, q3)) {
                filter4(s, off, step, thresh);
            }
        }
    }

    /**
     * Applies the 8-tap loop filter to {@code count} lines across an edge ({@code filter8}).
     *
     * @param s the plane
     * @param off the index of the first sample after the edge, on the first line
     * @param step the distance between consecutive samples across the edge
     * @param along the distance between consecutive lines
     * @param count the number of lines
     * @param blimit the edge limit
     * @param limit the interior limit
     * @param thresh the high edge variance threshold
     */
    private static void loopFilter8(byte[] s, int off, int step, int along, int count, int blimit, int limit,
                                    int thresh) {
        for (var i = 0; i < count; i++, off += along) {
            var p3 = s[off - 4 * step] & 0xFF;
            var p2 = s[off - 3 * step] & 0xFF;
            var p1 = s[off - 2 * step] & 0xFF;
            var p0 = s[off - step] & 0xFF;
            var q0 = s[off] & 0xFF;
            var q1 = s[off + step] & 0xFF;
            var q2 = s[off + 2 * step] & 0xFF;
            var q3 = s[off + 3 * step] & 0xFF;
            if (!filterMask(limit, blimit, p3, p2, p1, p0, q0, q1, q2, q3)) {
                continue;
            }
            if (flat4(p3, p2, p1, p0, q0, q1, q2, q3)) {
                s[off - 3 * step] = (byte) ((p3 + p3 + p3 + 2 * p2 + p1 + p0 + q0 + 4) >> 3);
                s[off - 2 * step] = (byte) ((p3 + p3 + p2 + 2 * p1 + p0 + q0 + q1 + 4) >> 3);
                s[off - step] = (byte) ((p3 + p2 + p1 + 2 * p0 + q0 + q1 + q2 + 4) >> 3);
                s[off] = (byte) ((p2 + p1 + p0 + 2 * q0 + q1 + q2 + q3 + 4) >> 3);
                s[off + step] = (byte) ((p1 + p0 + q0 + 2 * q1 + q2 + q3 + q3 + 4) >> 3);
                s[off + 2 * step] = (byte) ((p0 + q0 + q1 + 2 * q2 + q3 + q3 + q3 + 4) >> 3);
            } else {
                filter4(s, off, step, thresh);
            }
        }
    }

    /**
     * Applies the 16-tap loop filter to {@code count} lines across an edge ({@code filter16}).
     *
     * @param s the plane
     * @param off the index of the first sample after the edge, on the first line
     * @param step the distance between consecutive samples across the edge
     * @param along the distance between consecutive lines
     * @param count the number of lines
     * @param blimit the edge limit
     * @param limit the interior limit
     * @param thresh the high edge variance threshold
     */
    private static void loopFilter16(byte[] s, int off, int step, int along, int count, int blimit, int limit,
                                     int thresh) {
        for (var i = 0; i < count; i++, off += along) {
            var p3 = s[off - 4 * step] & 0xFF;
            var p2 = s[off - 3 * step] & 0xFF;
            var p1 = s[off - 2 * step] & 0xFF;
            var p0 = s[off - step] & 0xFF;
            var q0 = s[off] & 0xFF;
            var q1 = s[off + step] & 0xFF;
            var q2 = s[off + 2 * step] & 0xFF;
            var q3 = s[off + 3 * step] & 0xFF;
            if (!filterMask(limit, blimit, p3, p2, p1, p0, q0, q1, q2, q3)) {
                continue;
            }
            if (!flat4(p3, p2, p1, p0, q0, q1, q2, q3)) {
                filter4(s, off, step, thresh);
                continue;
            }
            var p7 = s[off - 8 * step] & 0xFF;
            var p6 = s[off - 7 * step] & 0xFF;
            var p5 = s[off - 6 * step] & 0xFF;
            var p4 = s[off - 5 * step] & 0xFF;
            var q4 = s[off + 4 * step] & 0xFF;
            var q5 = s[off + 5 * step] & 0xFF;
            var q6 = s[off + 6 * step] & 0xFF;
            var q7 = s[off + 7 * step] & 0xFF;
            var flat2 = Math.abs(p4 - p0) <= 1 && Math.abs(q4 - q0) <= 1 && Math.abs(p5 - p0) <= 1
                    && Math.abs(q5 - q0) <= 1 && Math.abs(p6 - p0) <= 1 && Math.abs(q6 - q0) <= 1
                    && Math.abs(p7 - p0) <= 1 && Math.abs(q7 - q0) <= 1;
            if (flat2) {
                s[off - 7 * step] = (byte) ((p7 * 7 + p6 * 2 + p5 + p4 + p3 + p2 + p1 + p0 + q0 + 8) >> 4);
                s[off - 6 * step] = (byte) ((p7 * 6 + p6 + p5 * 2 + p4 + p3 + p2 + p1 + p0 + q0 + q1 + 8) >> 4);
                s[off - 5 * step] = (byte) ((p7 * 5 + p6 + p5 + p4 * 2 + p3 + p2 + p1 + p0 + q0 + q1 + q2 + 8) >> 4);
                s[off - 4 * step] = (byte) ((p7 * 4 + p6 + p5 + p4 + p3 * 2 + p2 + p1 + p0 + q0 + q1 + q2 + q3 + 8) >> 4);
                s[off - 3 * step] = (byte) ((p7 * 3 + p6 + p5 + p4 + p3 + p2 * 2 + p1 + p0 + q0 + q1 + q2 + q3 + q4 + 8)
                        >> 4);
                s[off - 2 * step] = (byte) ((p7 * 2 + p6 + p5 + p4 + p3 + p2 + p1 * 2 + p0 + q0 + q1 + q2 + q3 + q4 + q5
                        + 8) >> 4);
                s[off - step] = (byte) ((p7 + p6 + p5 + p4 + p3 + p2 + p1 + p0 * 2 + q0 + q1 + q2 + q3 + q4 + q5 + q6
                        + 8) >> 4);
                s[off] = (byte) ((p6 + p5 + p4 + p3 + p2 + p1 + p0 + q0 * 2 + q1 + q2 + q3 + q4 + q5 + q6 + q7 + 8) >> 4);
                s[off + step] = (byte) ((p5 + p4 + p3 + p2 + p1 + p0 + q0 + q1 * 2 + q2 + q3 + q4 + q5 + q6 + q7 * 2
                        + 8) >> 4);
                s[off + 2 * step] = (byte) ((p4 + p3 + p2 + p1 + p0 + q0 + q1 + q2 * 2 + q3 + q4 + q5 + q6 + q7 * 3 + 8)
                        >> 4);
                s[off + 3 * step] = (byte) ((p3 + p2 + p1 + p0 + q0 + q1 + q2 + q3 * 2 + q4 + q5 + q6 + q7 * 4 + 8) >> 4);
                s[off + 4 * step] = (byte) ((p2 + p1 + p0 + q0 + q1 + q2 + q3 + q4 * 2 + q5 + q6 + q7 * 5 + 8) >> 4);
                s[off + 5 * step] = (byte) ((p1 + p0 + q0 + q1 + q2 + q3 + q4 + q5 * 2 + q6 + q7 * 6 + 8) >> 4);
                s[off + 6 * step] = (byte) ((p0 + q0 + q1 + q2 + q3 + q4 + q5 + q6 * 2 + q7 * 7 + 8) >> 4);
            } else {
                s[off - 3 * step] = (byte) ((p3 + p3 + p3 + 2 * p2 + p1 + p0 + q0 + 4) >> 3);
                s[off - 2 * step] = (byte) ((p3 + p3 + p2 + 2 * p1 + p0 + q0 + q1 + 4) >> 3);
                s[off - step] = (byte) ((p3 + p2 + p1 + 2 * p0 + q0 + q1 + q2 + 4) >> 3);
                s[off] = (byte) ((p2 + p1 + p0 + 2 * q0 + q1 + q2 + q3 + 4) >> 3);
                s[off + step] = (byte) ((p1 + p0 + q0 + 2 * q1 + q2 + q3 + q3 + 4) >> 3);
                s[off + 2 * step] = (byte) ((p0 + q0 + q1 + 2 * q2 + q3 + q3 + q3 + 4) >> 3);
            }
        }
    }

    @Override
    public void lpfHorizontal4(byte[] s, int off, int pitch, int blimit, int limit, int thresh, byte[] scratch) {
        loopFilter4(s, off, pitch, 1, 8, blimit, limit, thresh);
    }

    @Override
    public void lpfHorizontal4Dual(byte[] s, int off, int pitch, int blimit0, int limit0, int thresh0,
                                   int blimit1, int limit1, int thresh1, byte[] scratch) {
        loopFilter4(s, off, pitch, 1, 8, blimit0, limit0, thresh0);
        loopFilter4(s, off + 8, pitch, 1, 8, blimit1, limit1, thresh1);
    }

    @Override
    public void lpfHorizontal8(byte[] s, int off, int pitch, int blimit, int limit, int thresh, byte[] scratch) {
        loopFilter8(s, off, pitch, 1, 8, blimit, limit, thresh);
    }

    @Override
    public void lpfHorizontal8Dual(byte[] s, int off, int pitch, int blimit0, int limit0, int thresh0,
                                   int blimit1, int limit1, int thresh1, byte[] scratch) {
        loopFilter8(s, off, pitch, 1, 8, blimit0, limit0, thresh0);
        loopFilter8(s, off + 8, pitch, 1, 8, blimit1, limit1, thresh1);
    }

    @Override
    public void lpfHorizontal16(byte[] s, int off, int pitch, int blimit, int limit, int thresh, int count, byte[] scratch) {
        loopFilter16(s, off, pitch, 1, 8 * count, blimit, limit, thresh);
    }

    @Override
    public void lpfVertical4(byte[] s, int off, int pitch, int blimit, int limit, int thresh, byte[] scratch) {
        loopFilter4(s, off, 1, pitch, 8, blimit, limit, thresh);
    }

    @Override
    public void lpfVertical4Dual(byte[] s, int off, int pitch, int blimit0, int limit0, int thresh0,
                                 int blimit1, int limit1, int thresh1, byte[] scratch) {
        loopFilter4(s, off, 1, pitch, 8, blimit0, limit0, thresh0);
        loopFilter4(s, off + 8 * pitch, 1, pitch, 8, blimit1, limit1, thresh1);
    }

    @Override
    public void lpfVertical8(byte[] s, int off, int pitch, int blimit, int limit, int thresh, byte[] scratch) {
        loopFilter8(s, off, 1, pitch, 8, blimit, limit, thresh);
    }

    @Override
    public void lpfVertical8Dual(byte[] s, int off, int pitch, int blimit0, int limit0, int thresh0,
                                 int blimit1, int limit1, int thresh1, byte[] scratch) {
        loopFilter8(s, off, 1, pitch, 8, blimit0, limit0, thresh0);
        loopFilter8(s, off + 8 * pitch, 1, pitch, 8, blimit1, limit1, thresh1);
    }

    @Override
    public void lpfVertical16(byte[] s, int off, int pitch, int blimit, int limit, int thresh, int rows, byte[] scratch) {
        loopFilter16(s, off, 1, pitch, rows, blimit, limit, thresh);
    }

    @Override
    public void intraPredict(int mode, int txSize, int dcEdges, byte[] dst, int dstOff, int stride,
                             byte[] above, int aboveOff, byte[] left, int leftOff) {
        var bs = 4 << txSize;
        switch (mode) {
            case Vp9Constants.DC_PRED -> predictDc(dcEdges, bs, dst, dstOff, stride, above, aboveOff, left, leftOff);
            case Vp9Constants.V_PRED -> {
                for (var r = 0; r < bs; r++) {
                    System.arraycopy(above, aboveOff, dst, dstOff + r * stride, bs);
                }
            }
            case Vp9Constants.H_PRED -> {
                for (var r = 0; r < bs; r++) {
                    Arrays.fill(dst, dstOff + r * stride, dstOff + r * stride + bs, left[leftOff + r]);
                }
            }
            case Vp9Constants.TM_PRED -> {
                var topLeft = above[aboveOff - 1] & 0xFF;
                for (var r = 0; r < bs; r++) {
                    var l = (left[leftOff + r] & 0xFF) - topLeft;
                    var row = dstOff + r * stride;
                    for (var c = 0; c < bs; c++) {
                        dst[row + c] = (byte) clip(l + (above[aboveOff + c] & 0xFF));
                    }
                }
            }
            default -> {
                if (bs == 4) {
                    predictDirectional4(mode, dst, dstOff, stride, above, aboveOff, left, leftOff);
                } else {
                    predictDirectional(mode, bs, dst, dstOff, stride, above, aboveOff, left, leftOff);
                }
            }
        }
    }

    /**
     * Fills a block with the DC prediction ({@code dc_predictor}, {@code dc_top_predictor},
     * {@code dc_left_predictor}, {@code dc_128_predictor}).
     *
     * @param dcEdges bit 0 when the above row is used, bit 1 when the left column is
     * @param bs the block size
     * @param dst the destination plane
     * @param dstOff the index of the block's top-left sample
     * @param stride the destination row stride
     * @param above the array holding the above row
     * @param aboveOff the index of the first above sample
     * @param left the array holding the left column
     * @param leftOff the index of the first left sample
     */
    private static void predictDc(int dcEdges, int bs, byte[] dst, int dstOff, int stride, byte[] above,
                                  int aboveOff, byte[] left, int leftOff) {
        int value;
        var sum = 0;
        switch (dcEdges) {
            case 3 -> {
                for (var i = 0; i < bs; i++) {
                    sum += (above[aboveOff + i] & 0xFF) + (left[leftOff + i] & 0xFF);
                }
                value = (sum + bs) / (2 * bs);
            }
            case 1 -> {
                for (var i = 0; i < bs; i++) {
                    sum += above[aboveOff + i] & 0xFF;
                }
                value = (sum + (bs >> 1)) / bs;
            }
            case 2 -> {
                for (var i = 0; i < bs; i++) {
                    sum += left[leftOff + i] & 0xFF;
                }
                value = (sum + (bs >> 1)) / bs;
            }
            default -> value = 128;
        }
        for (var r = 0; r < bs; r++) {
            Arrays.fill(dst, dstOff + r * stride, dstOff + r * stride + bs, (byte) value);
        }
    }

    /**
     * Returns the three-tap smoothed sample {@code (a + 2b + c + 2) / 4}.
     *
     * @param a the first sample
     * @param b the middle sample
     * @param c the last sample
     * @return the smoothed sample
     */
    private static int avg3(int a, int b, int c) {
        return (a + 2 * b + c + 2) >> 2;
    }

    /**
     * Returns the rounded average of two samples.
     *
     * @param a the first sample
     * @param b the second sample
     * @return the average
     */
    private static int avg2(int a, int b) {
        return (a + b + 1) >> 1;
    }

    /**
     * Predicts a 4x4 block with a directional mode, using the dedicated 4x4 predictors of
     * libvpx ({@code vpx_d*_predictor_4x4_c}), which read the true above-right samples.
     *
     * @param mode the directional mode
     * @param dst the destination plane
     * @param dstOff the index of the block's top-left sample
     * @param stride the destination row stride
     * @param above the array holding the above row
     * @param aboveOff the index of the first above sample
     * @param left the array holding the left column
     * @param leftOff the index of the first left sample
     */
    private static void predictDirectional4(int mode, byte[] dst, int dstOff, int stride, byte[] above,
                                            int aboveOff, byte[] left, int leftOff) {
        var r1 = dstOff + stride;
        var r2 = dstOff + 2 * stride;
        var r3 = dstOff + 3 * stride;
        switch (mode) {
            case Vp9Constants.D207_PRED -> {
                var i = left[leftOff] & 0xFF;
                var j = left[leftOff + 1] & 0xFF;
                var k = left[leftOff + 2] & 0xFF;
                var l = left[leftOff + 3] & 0xFF;
                dst[dstOff] = (byte) avg2(i, j);
                dst[dstOff + 2] = dst[r1] = (byte) avg2(j, k);
                dst[r1 + 2] = dst[r2] = (byte) avg2(k, l);
                dst[dstOff + 1] = (byte) avg3(i, j, k);
                dst[dstOff + 3] = dst[r1 + 1] = (byte) avg3(j, k, l);
                dst[r1 + 3] = dst[r2 + 1] = (byte) avg3(k, l, l);
                dst[r2 + 3] = dst[r2 + 2] = dst[r3] = dst[r3 + 1] = dst[r3 + 2] = dst[r3 + 3] = (byte) l;
            }
            case Vp9Constants.D63_PRED -> {
                var a = above[aboveOff] & 0xFF;
                var b = above[aboveOff + 1] & 0xFF;
                var c = above[aboveOff + 2] & 0xFF;
                var d = above[aboveOff + 3] & 0xFF;
                var e = above[aboveOff + 4] & 0xFF;
                var f = above[aboveOff + 5] & 0xFF;
                var g = above[aboveOff + 6] & 0xFF;
                dst[dstOff] = (byte) avg2(a, b);
                dst[dstOff + 1] = dst[r2] = (byte) avg2(b, c);
                dst[dstOff + 2] = dst[r2 + 1] = (byte) avg2(c, d);
                dst[dstOff + 3] = dst[r2 + 2] = (byte) avg2(d, e);
                dst[r2 + 3] = (byte) avg2(e, f);
                dst[r1] = (byte) avg3(a, b, c);
                dst[r1 + 1] = dst[r3] = (byte) avg3(b, c, d);
                dst[r1 + 2] = dst[r3 + 1] = (byte) avg3(c, d, e);
                dst[r1 + 3] = dst[r3 + 2] = (byte) avg3(d, e, f);
                dst[r3 + 3] = (byte) avg3(e, f, g);
            }
            case Vp9Constants.D45_PRED -> {
                var a = above[aboveOff] & 0xFF;
                var b = above[aboveOff + 1] & 0xFF;
                var c = above[aboveOff + 2] & 0xFF;
                var d = above[aboveOff + 3] & 0xFF;
                var e = above[aboveOff + 4] & 0xFF;
                var f = above[aboveOff + 5] & 0xFF;
                var g = above[aboveOff + 6] & 0xFF;
                var h = above[aboveOff + 7] & 0xFF;
                dst[dstOff] = (byte) avg3(a, b, c);
                dst[dstOff + 1] = dst[r1] = (byte) avg3(b, c, d);
                dst[dstOff + 2] = dst[r1 + 1] = dst[r2] = (byte) avg3(c, d, e);
                dst[dstOff + 3] = dst[r1 + 2] = dst[r2 + 1] = dst[r3] = (byte) avg3(d, e, f);
                dst[r1 + 3] = dst[r2 + 2] = dst[r3 + 1] = (byte) avg3(e, f, g);
                dst[r2 + 3] = dst[r3 + 2] = (byte) avg3(f, g, h);
                dst[r3 + 3] = (byte) h;
            }
            case Vp9Constants.D117_PRED -> {
                var i = left[leftOff] & 0xFF;
                var j = left[leftOff + 1] & 0xFF;
                var k = left[leftOff + 2] & 0xFF;
                var x = above[aboveOff - 1] & 0xFF;
                var a = above[aboveOff] & 0xFF;
                var b = above[aboveOff + 1] & 0xFF;
                var c = above[aboveOff + 2] & 0xFF;
                var d = above[aboveOff + 3] & 0xFF;
                dst[dstOff] = dst[r2 + 1] = (byte) avg2(x, a);
                dst[dstOff + 1] = dst[r2 + 2] = (byte) avg2(a, b);
                dst[dstOff + 2] = dst[r2 + 3] = (byte) avg2(b, c);
                dst[dstOff + 3] = (byte) avg2(c, d);
                dst[r3] = (byte) avg3(k, j, i);
                dst[r2] = (byte) avg3(j, i, x);
                dst[r1] = dst[r3 + 1] = (byte) avg3(i, x, a);
                dst[r1 + 1] = dst[r3 + 2] = (byte) avg3(x, a, b);
                dst[r1 + 2] = dst[r3 + 3] = (byte) avg3(a, b, c);
                dst[r1 + 3] = (byte) avg3(b, c, d);
            }
            case Vp9Constants.D135_PRED -> {
                var i = left[leftOff] & 0xFF;
                var j = left[leftOff + 1] & 0xFF;
                var k = left[leftOff + 2] & 0xFF;
                var l = left[leftOff + 3] & 0xFF;
                var x = above[aboveOff - 1] & 0xFF;
                var a = above[aboveOff] & 0xFF;
                var b = above[aboveOff + 1] & 0xFF;
                var c = above[aboveOff + 2] & 0xFF;
                var d = above[aboveOff + 3] & 0xFF;
                dst[r3] = (byte) avg3(j, k, l);
                dst[r3 + 1] = dst[r2] = (byte) avg3(i, j, k);
                dst[r3 + 2] = dst[r2 + 1] = dst[r1] = (byte) avg3(x, i, j);
                dst[r3 + 3] = dst[r2 + 2] = dst[r1 + 1] = dst[dstOff] = (byte) avg3(a, x, i);
                dst[r2 + 3] = dst[r1 + 2] = dst[dstOff + 1] = (byte) avg3(b, a, x);
                dst[r1 + 3] = dst[dstOff + 2] = (byte) avg3(c, b, a);
                dst[dstOff + 3] = (byte) avg3(d, c, b);
            }
            default -> {
                var i = left[leftOff] & 0xFF;
                var j = left[leftOff + 1] & 0xFF;
                var k = left[leftOff + 2] & 0xFF;
                var l = left[leftOff + 3] & 0xFF;
                var x = above[aboveOff - 1] & 0xFF;
                var a = above[aboveOff] & 0xFF;
                var b = above[aboveOff + 1] & 0xFF;
                var c = above[aboveOff + 2] & 0xFF;
                dst[dstOff] = dst[r1 + 2] = (byte) avg2(i, x);
                dst[r1] = dst[r2 + 2] = (byte) avg2(j, i);
                dst[r2] = dst[r3 + 2] = (byte) avg2(k, j);
                dst[r3] = (byte) avg2(l, k);
                dst[dstOff + 3] = (byte) avg3(a, b, c);
                dst[dstOff + 2] = (byte) avg3(x, a, b);
                dst[dstOff + 1] = dst[r1 + 3] = (byte) avg3(i, x, a);
                dst[r1 + 1] = dst[r2 + 3] = (byte) avg3(j, i, x);
                dst[r2 + 1] = dst[r3 + 3] = (byte) avg3(k, j, i);
                dst[r3 + 1] = (byte) avg3(l, k, j);
            }
        }
    }

    /**
     * Returns entry {@code k} of the outer border of the 135 degree predictor, which runs from
     * the bottom-left (entry 0) up the left column, through the top-left corner (entry
     * {@code bs - 1}) and along the above row ({@code d135_predictor}).
     *
     * @param k the border entry, from 0 to {@code 2 * bs - 2}
     * @param bs the block size
     * @param above the array holding the above row
     * @param aboveOff the index of the first above sample
     * @param left the array holding the left column
     * @param leftOff the index of the first left sample
     * @return the smoothed sample
     */
    private static int d135Border(int k, int bs, byte[] above, int aboveOff, byte[] left, int leftOff) {
        if (k < bs - 2) {
            return avg3(left[leftOff + bs - 3 - k] & 0xFF, left[leftOff + bs - 2 - k] & 0xFF,
                    left[leftOff + bs - 1 - k] & 0xFF);
        }
        if (k == bs - 2) {
            return avg3(above[aboveOff - 1] & 0xFF, left[leftOff] & 0xFF, left[leftOff + 1] & 0xFF);
        }
        if (k == bs - 1) {
            return avg3(left[leftOff] & 0xFF, above[aboveOff - 1] & 0xFF, above[aboveOff] & 0xFF);
        }
        if (k == bs) {
            return avg3(above[aboveOff - 1] & 0xFF, above[aboveOff] & 0xFF, above[aboveOff + 1] & 0xFF);
        }
        var i = k - bs - 1;
        return avg3(above[aboveOff + i] & 0xFF, above[aboveOff + i + 1] & 0xFF, above[aboveOff + i + 2] & 0xFF);
    }

    /**
     * Predicts a block of 8, 16 or 32 samples with a directional mode ({@code d207_predictor},
     * {@code d63_predictor}, {@code d45_predictor}, {@code d117_predictor},
     * {@code d135_predictor}, {@code d153_predictor}).
     *
     * @param mode the directional mode
     * @param bs the block size
     * @param dst the destination plane
     * @param dstOff the index of the block's top-left sample
     * @param stride the destination row stride
     * @param above the array holding the above row
     * @param aboveOff the index of the first above sample
     * @param left the array holding the left column
     * @param leftOff the index of the first left sample
     */
    private static void predictDirectional(int mode, int bs, byte[] dst, int dstOff, int stride, byte[] above,
                                           int aboveOff, byte[] left, int leftOff) {
        switch (mode) {
            case Vp9Constants.D207_PRED -> {
                var d = dstOff;
                for (var r = 0; r < bs - 1; r++) {
                    dst[d + r * stride] = (byte) avg2(left[leftOff + r] & 0xFF, left[leftOff + r + 1] & 0xFF);
                }
                dst[d + (bs - 1) * stride] = left[leftOff + bs - 1];
                d++;
                for (var r = 0; r < bs - 2; r++) {
                    dst[d + r * stride] = (byte) avg3(left[leftOff + r] & 0xFF, left[leftOff + r + 1] & 0xFF,
                            left[leftOff + r + 2] & 0xFF);
                }
                dst[d + (bs - 2) * stride] = (byte) avg3(left[leftOff + bs - 2] & 0xFF, left[leftOff + bs - 1] & 0xFF,
                        left[leftOff + bs - 1] & 0xFF);
                dst[d + (bs - 1) * stride] = left[leftOff + bs - 1];
                d++;
                for (var c = 0; c < bs - 2; c++) {
                    dst[d + (bs - 1) * stride + c] = left[leftOff + bs - 1];
                }
                for (var r = bs - 2; r >= 0; r--) {
                    System.arraycopy(dst, d + (r + 1) * stride - 2, dst, d + r * stride, bs - 2);
                }
            }
            case Vp9Constants.D63_PRED -> {
                for (var c = 0; c < bs; c++) {
                    dst[dstOff + c] = (byte) avg2(above[aboveOff + c] & 0xFF, above[aboveOff + c + 1] & 0xFF);
                    dst[dstOff + stride + c] = (byte) avg3(above[aboveOff + c] & 0xFF, above[aboveOff + c + 1] & 0xFF,
                            above[aboveOff + c + 2] & 0xFF);
                }
                var last = above[aboveOff + bs - 1];
                for (int r = 2, size = bs - 2; r < bs; r += 2, size--) {
                    var row0 = dstOff + r * stride;
                    var row1 = row0 + stride;
                    System.arraycopy(dst, dstOff + (r >> 1), dst, row0, size);
                    Arrays.fill(dst, row0 + size, row0 + bs, last);
                    System.arraycopy(dst, dstOff + stride + (r >> 1), dst, row1, size);
                    Arrays.fill(dst, row1 + size, row1 + bs, last);
                }
            }
            case Vp9Constants.D45_PRED -> {
                var aboveRight = above[aboveOff + bs - 1];
                for (var x = 0; x < bs - 1; x++) {
                    dst[dstOff + x] = (byte) avg3(above[aboveOff + x] & 0xFF, above[aboveOff + x + 1] & 0xFF,
                            above[aboveOff + x + 2] & 0xFF);
                }
                dst[dstOff + bs - 1] = aboveRight;
                var d = dstOff + stride;
                for (int x = 1, size = bs - 2; x < bs; x++, size--) {
                    System.arraycopy(dst, dstOff + x, dst, d, size);
                    Arrays.fill(dst, d + size, d + bs, aboveRight);
                    d += stride;
                }
            }
            case Vp9Constants.D117_PRED -> {
                for (var c = 0; c < bs; c++) {
                    dst[dstOff + c] = (byte) avg2(above[aboveOff + c - 1] & 0xFF, above[aboveOff + c] & 0xFF);
                }
                var d = dstOff + stride;
                dst[d] = (byte) avg3(left[leftOff] & 0xFF, above[aboveOff - 1] & 0xFF, above[aboveOff] & 0xFF);
                for (var c = 1; c < bs; c++) {
                    dst[d + c] = (byte) avg3(above[aboveOff + c - 2] & 0xFF, above[aboveOff + c - 1] & 0xFF,
                            above[aboveOff + c] & 0xFF);
                }
                d += stride;
                dst[d] = (byte) avg3(above[aboveOff - 1] & 0xFF, left[leftOff] & 0xFF, left[leftOff + 1] & 0xFF);
                for (var r = 3; r < bs; r++) {
                    dst[d + (r - 2) * stride] = (byte) avg3(left[leftOff + r - 3] & 0xFF, left[leftOff + r - 2] & 0xFF,
                            left[leftOff + r - 1] & 0xFF);
                }
                for (var r = 2; r < bs; r++) {
                    System.arraycopy(dst, d - 2 * stride, dst, d + 1, bs - 1);
                    d += stride;
                }
            }
            case Vp9Constants.D135_PRED -> {
                for (var j = 0; j < bs; j++) {
                    dst[dstOff + j] = (byte) d135Border(bs - 1 + j, bs, above, aboveOff, left, leftOff);
                }
                for (var i = 1; i < bs; i++) {
                    dst[dstOff + i * stride] = (byte) d135Border(bs - 1 - i, bs, above, aboveOff, left, leftOff);
                }
                for (var i = 1; i < bs; i++) {
                    var row = dstOff + i * stride;
                    System.arraycopy(dst, row - stride, dst, row + 1, bs - 1);
                }
            }
            default -> {
                var d = dstOff;
                dst[d] = (byte) avg2(above[aboveOff - 1] & 0xFF, left[leftOff] & 0xFF);
                for (var r = 1; r < bs; r++) {
                    dst[d + r * stride] = (byte) avg2(left[leftOff + r - 1] & 0xFF, left[leftOff + r] & 0xFF);
                }
                d++;
                dst[d] = (byte) avg3(left[leftOff] & 0xFF, above[aboveOff - 1] & 0xFF, above[aboveOff] & 0xFF);
                dst[d + stride] = (byte) avg3(above[aboveOff - 1] & 0xFF, left[leftOff] & 0xFF, left[leftOff + 1] & 0xFF);
                for (var r = 2; r < bs; r++) {
                    dst[d + r * stride] = (byte) avg3(left[leftOff + r - 2] & 0xFF, left[leftOff + r - 1] & 0xFF,
                            left[leftOff + r] & 0xFF);
                }
                d++;
                for (var c = 0; c < bs - 2; c++) {
                    dst[d + c] = (byte) avg3(above[aboveOff + c - 1] & 0xFF, above[aboveOff + c] & 0xFF,
                            above[aboveOff + c + 1] & 0xFF);
                }
                d += stride;
                for (var r = 1; r < bs; r++) {
                    System.arraycopy(dst, d - stride - 2, dst, d, bs - 2);
                    d += stride;
                }
            }
        }
    }

    /**
     * Computes the 4-point inverse DCT of one row or column ({@code idct4_c}).
     *
     * @param in the input array
     * @param inOff the index of the first input value
     * @param out the output array
     * @param outOff the index of the first output value
     */
    static void idct4(int[] in, int inOff, int[] out, int outOff) {
        int step_0 = 0, step_1 = 0, step_2 = 0, step_3 = 0;
        int temp1 = 0;
        int temp2 = 0;
        temp1 = ((short) in[inOff + 0] + (short) in[inOff + 2]) * COSPI_16_64;
        temp2 = ((short) in[inOff + 0] - (short) in[inOff + 2]) * COSPI_16_64;
        step_0 = (short) ((temp1 + 8192) >> 14);
        step_1 = (short) ((temp2 + 8192) >> 14);
        temp1 = (short) in[inOff + 1] * COSPI_24_64 - (short) in[inOff + 3] * COSPI_8_64;
        temp2 = (short) in[inOff + 1] * COSPI_8_64 + (short) in[inOff + 3] * COSPI_24_64;
        step_2 = (short) ((temp1 + 8192) >> 14);
        step_3 = (short) ((temp2 + 8192) >> 14);
        out[outOff + 0] = (step_0 + step_3);
        out[outOff + 1] = (step_1 + step_2);
        out[outOff + 2] = (step_1 - step_2);
        out[outOff + 3] = (step_0 - step_3);
    }

    /**
     * Computes the 4-point inverse ADST of one row or column ({@code iadst4_c}).
     *
     * @param in the input array
     * @param inOff the index of the first input value
     * @param out the output array
     * @param outOff the index of the first output value
     */
    static void iadst4(int[] in, int inOff, int[] out, int outOff) {
        int s0 = 0;
        int s1 = 0;
        int s2 = 0;
        int s3 = 0;
        int s4 = 0;
        int s5 = 0;
        int s6 = 0;
        int s7 = 0;
        int x0 = in[inOff + 0];
        int x1 = in[inOff + 1];
        int x2 = in[inOff + 2];
        int x3 = in[inOff + 3];
        if ((x0 | x1 | x2 | x3) == 0) {
            Arrays.fill(out, outOff, outOff + 4, 0);
            return;
        }
        s0 = SINPI_1_9 * x0;
        s1 = SINPI_2_9 * x0;
        s2 = SINPI_3_9 * x1;
        s3 = SINPI_4_9 * x2;
        s4 = SINPI_1_9 * x2;
        s5 = SINPI_2_9 * x3;
        s6 = SINPI_4_9 * x3;
        s7 = (x0 - x2 + x3);
        s0 = s0 + s3 + s5;
        s1 = s1 - s4 - s6;
        s3 = s2;
        s2 = SINPI_3_9 * s7;
        out[outOff + 0] = (((s0 + s3) + 8192) >> 14);
        out[outOff + 1] = (((s1 + s3) + 8192) >> 14);
        out[outOff + 2] = ((s2 + 8192) >> 14);
        out[outOff + 3] = ((((s0 + s1 - s3) + 8192) >> 14));
    }

    /**
     * Computes the 8-point inverse DCT of one row or column ({@code idct8_c}).
     *
     * @param in the input array
     * @param inOff the index of the first input value
     * @param out the output array
     * @param outOff the index of the first output value
     */
    static void idct8(int[] in, int inOff, int[] out, int outOff) {
        int step1_0 = 0, step1_1 = 0, step1_2 = 0, step1_3 = 0, step1_4 = 0, step1_5 = 0, step1_6 = 0, step1_7 = 0;
        int step2_0 = 0, step2_1 = 0, step2_2 = 0, step2_3 = 0, step2_4 = 0, step2_5 = 0, step2_6 = 0, step2_7 = 0;
        int temp1 = 0;
        int temp2 = 0;
        step1_0 = (short) (in[inOff + 0]);
        step1_2 = (short) (in[inOff + 4]);
        step1_1 = (short) (in[inOff + 2]);
        step1_3 = (short) (in[inOff + 6]);
        temp1 = (short) in[inOff + 1] * COSPI_28_64 - (short) in[inOff + 7] * COSPI_4_64;
        temp2 = (short) in[inOff + 1] * COSPI_4_64 + (short) in[inOff + 7] * COSPI_28_64;
        step1_4 = (short) ((temp1 + 8192) >> 14);
        step1_7 = (short) ((temp2 + 8192) >> 14);
        temp1 = (short) in[inOff + 5] * COSPI_12_64 - (short) in[inOff + 3] * COSPI_20_64;
        temp2 = (short) in[inOff + 5] * COSPI_20_64 + (short) in[inOff + 3] * COSPI_12_64;
        step1_5 = (short) ((temp1 + 8192) >> 14);
        step1_6 = (short) ((temp2 + 8192) >> 14);
        temp1 = (step1_0 + step1_2) * COSPI_16_64;
        temp2 = (step1_0 - step1_2) * COSPI_16_64;
        step2_0 = (short) ((temp1 + 8192) >> 14);
        step2_1 = (short) ((temp2 + 8192) >> 14);
        temp1 = step1_1 * COSPI_24_64 - step1_3 * COSPI_8_64;
        temp2 = step1_1 * COSPI_8_64 + step1_3 * COSPI_24_64;
        step2_2 = (short) ((temp1 + 8192) >> 14);
        step2_3 = (short) ((temp2 + 8192) >> 14);
        step2_4 = (short) ((step1_4 + step1_5));
        step2_5 = (short) ((step1_4 - step1_5));
        step2_6 = (short) ((-step1_6 + step1_7));
        step2_7 = (short) ((step1_6 + step1_7));
        step1_0 = (short) ((step2_0 + step2_3));
        step1_1 = (short) ((step2_1 + step2_2));
        step1_2 = (short) ((step2_1 - step2_2));
        step1_3 = (short) ((step2_0 - step2_3));
        step1_4 = (short) (step2_4);
        temp1 = (step2_6 - step2_5) * COSPI_16_64;
        temp2 = (step2_5 + step2_6) * COSPI_16_64;
        step1_5 = (short) ((temp1 + 8192) >> 14);
        step1_6 = (short) ((temp2 + 8192) >> 14);
        step1_7 = (short) (step2_7);
        out[outOff + 0] = (step1_0 + step1_7);
        out[outOff + 1] = (step1_1 + step1_6);
        out[outOff + 2] = (step1_2 + step1_5);
        out[outOff + 3] = (step1_3 + step1_4);
        out[outOff + 4] = (step1_3 - step1_4);
        out[outOff + 5] = (step1_2 - step1_5);
        out[outOff + 6] = (step1_1 - step1_6);
        out[outOff + 7] = (step1_0 - step1_7);
    }

    /**
     * Computes the 8-point inverse ADST of one row or column ({@code iadst8_c}).
     *
     * @param in the input array
     * @param inOff the index of the first input value
     * @param out the output array
     * @param outOff the index of the first output value
     */
    static void iadst8(int[] in, int inOff, int[] out, int outOff) {
        int s0 = 0;
        int s1 = 0;
        int s2 = 0;
        int s3 = 0;
        int s4 = 0;
        int s5 = 0;
        int s6 = 0;
        int s7 = 0;
        int x0 = in[inOff + 7];
        int x1 = in[inOff + 0];
        int x2 = in[inOff + 5];
        int x3 = in[inOff + 2];
        int x4 = in[inOff + 3];
        int x5 = in[inOff + 4];
        int x6 = in[inOff + 1];
        int x7 = in[inOff + 6];
        if ((x0 | x1 | x2 | x3 | x4 | x5 | x6 | x7) == 0) {
            Arrays.fill(out, outOff, outOff + 8, 0);
            return;
        }
        s0 = (int)(COSPI_2_64 * x0 + COSPI_30_64 * x1);
        s1 = (int)(COSPI_30_64 * x0 - COSPI_2_64 * x1);
        s2 = (int)(COSPI_10_64 * x2 + COSPI_22_64 * x3);
        s3 = (int)(COSPI_22_64 * x2 - COSPI_10_64 * x3);
        s4 = (int)(COSPI_18_64 * x4 + COSPI_14_64 * x5);
        s5 = (int)(COSPI_14_64 * x4 - COSPI_18_64 * x5);
        s6 = (int)(COSPI_26_64 * x6 + COSPI_6_64 * x7);
        s7 = (int)(COSPI_6_64 * x6 - COSPI_26_64 * x7);
        x0 = (((s0 + s4) + 8192) >> 14);
        x1 = (((s1 + s5) + 8192) >> 14);
        x2 = (((s2 + s6) + 8192) >> 14);
        x3 = (((s3 + s7) + 8192) >> 14);
        x4 = (((s0 - s4) + 8192) >> 14);
        x5 = (((s1 - s5) + 8192) >> 14);
        x6 = (((s2 - s6) + 8192) >> 14);
        x7 = (((s3 - s7) + 8192) >> 14);
        s0 = (int)x0;
        s1 = (int)x1;
        s2 = (int)x2;
        s3 = (int)x3;
        s4 = (int)(COSPI_8_64 * x4 + COSPI_24_64 * x5);
        s5 = (int)(COSPI_24_64 * x4 - COSPI_8_64 * x5);
        s6 = (int)(-COSPI_24_64 * x6 + COSPI_8_64 * x7);
        s7 = (int)(COSPI_8_64 * x6 + COSPI_24_64 * x7);
        x0 = (s0 + s2);
        x1 = (s1 + s3);
        x2 = (s0 - s2);
        x3 = (s1 - s3);
        x4 = (((s4 + s6) + 8192) >> 14);
        x5 = (((s5 + s7) + 8192) >> 14);
        x6 = (((s4 - s6) + 8192) >> 14);
        x7 = (((s5 - s7) + 8192) >> 14);
        s2 = (int)(COSPI_16_64 * (x2 + x3));
        s3 = (int)(COSPI_16_64 * (x2 - x3));
        s6 = (int)(COSPI_16_64 * (x6 + x7));
        s7 = (int)(COSPI_16_64 * (x6 - x7));
        x2 = ((s2 + 8192) >> 14);
        x3 = ((s3 + 8192) >> 14);
        x6 = ((s6 + 8192) >> 14);
        x7 = ((s7 + 8192) >> 14);
        out[outOff + 0] = (x0);
        out[outOff + 1] = (-x4);
        out[outOff + 2] = (x6);
        out[outOff + 3] = (-x2);
        out[outOff + 4] = (x3);
        out[outOff + 5] = (-x7);
        out[outOff + 6] = (x5);
        out[outOff + 7] = (-x1);
    }

    /**
     * Computes the 16-point inverse DCT of one row or column ({@code idct16_c}).
     *
     * @param in the input array
     * @param inOff the index of the first input value
     * @param out the output array
     * @param outOff the index of the first output value
     */
    static void idct16(int[] in, int inOff, int[] out, int outOff) {
        int step1_0 = 0, step1_1 = 0, step1_2 = 0, step1_3 = 0, step1_4 = 0, step1_5 = 0, step1_6 = 0, step1_7 = 0, step1_8 = 0, step1_9 = 0, step1_10 = 0, step1_11 = 0, step1_12 = 0, step1_13 = 0, step1_14 = 0, step1_15 = 0;
        int step2_0 = 0, step2_1 = 0, step2_2 = 0, step2_3 = 0, step2_4 = 0, step2_5 = 0, step2_6 = 0, step2_7 = 0, step2_8 = 0, step2_9 = 0, step2_10 = 0, step2_11 = 0, step2_12 = 0, step2_13 = 0, step2_14 = 0, step2_15 = 0;
        int temp1 = 0;
        int temp2 = 0;
        step1_0 = (short) (in[inOff + 0]);
        step1_1 = (short) (in[inOff + 8]);
        step1_2 = (short) (in[inOff + 4]);
        step1_3 = (short) (in[inOff + 12]);
        step1_4 = (short) (in[inOff + 2]);
        step1_5 = (short) (in[inOff + 10]);
        step1_6 = (short) (in[inOff + 6]);
        step1_7 = (short) (in[inOff + 14]);
        step1_8 = (short) (in[inOff + 1]);
        step1_9 = (short) (in[inOff + 9]);
        step1_10 = (short) (in[inOff + 5]);
        step1_11 = (short) (in[inOff + 13]);
        step1_12 = (short) (in[inOff + 3]);
        step1_13 = (short) (in[inOff + 11]);
        step1_14 = (short) (in[inOff + 7]);
        step1_15 = (short) (in[inOff + 15]);
        step2_0 = (short) (step1_0);
        step2_1 = (short) (step1_1);
        step2_2 = (short) (step1_2);
        step2_3 = (short) (step1_3);
        step2_4 = (short) (step1_4);
        step2_5 = (short) (step1_5);
        step2_6 = (short) (step1_6);
        step2_7 = (short) (step1_7);
        temp1 = step1_8 * COSPI_30_64 - step1_15 * COSPI_2_64;
        temp2 = step1_8 * COSPI_2_64 + step1_15 * COSPI_30_64;
        step2_8 = (short) ((temp1 + 8192) >> 14);
        step2_15 = (short) ((temp2 + 8192) >> 14);
        temp1 = step1_9 * COSPI_14_64 - step1_14 * COSPI_18_64;
        temp2 = step1_9 * COSPI_18_64 + step1_14 * COSPI_14_64;
        step2_9 = (short) ((temp1 + 8192) >> 14);
        step2_14 = (short) ((temp2 + 8192) >> 14);
        temp1 = step1_10 * COSPI_22_64 - step1_13 * COSPI_10_64;
        temp2 = step1_10 * COSPI_10_64 + step1_13 * COSPI_22_64;
        step2_10 = (short) ((temp1 + 8192) >> 14);
        step2_13 = (short) ((temp2 + 8192) >> 14);
        temp1 = step1_11 * COSPI_6_64 - step1_12 * COSPI_26_64;
        temp2 = step1_11 * COSPI_26_64 + step1_12 * COSPI_6_64;
        step2_11 = (short) ((temp1 + 8192) >> 14);
        step2_12 = (short) ((temp2 + 8192) >> 14);
        step1_0 = (short) (step2_0);
        step1_1 = (short) (step2_1);
        step1_2 = (short) (step2_2);
        step1_3 = (short) (step2_3);
        temp1 = step2_4 * COSPI_28_64 - step2_7 * COSPI_4_64;
        temp2 = step2_4 * COSPI_4_64 + step2_7 * COSPI_28_64;
        step1_4 = (short) ((temp1 + 8192) >> 14);
        step1_7 = (short) ((temp2 + 8192) >> 14);
        temp1 = step2_5 * COSPI_12_64 - step2_6 * COSPI_20_64;
        temp2 = step2_5 * COSPI_20_64 + step2_6 * COSPI_12_64;
        step1_5 = (short) ((temp1 + 8192) >> 14);
        step1_6 = (short) ((temp2 + 8192) >> 14);
        step1_8 = (short) ((step2_8 + step2_9));
        step1_9 = (short) ((step2_8 - step2_9));
        step1_10 = (short) ((-step2_10 + step2_11));
        step1_11 = (short) ((step2_10 + step2_11));
        step1_12 = (short) ((step2_12 + step2_13));
        step1_13 = (short) ((step2_12 - step2_13));
        step1_14 = (short) ((-step2_14 + step2_15));
        step1_15 = (short) ((step2_14 + step2_15));
        temp1 = (step1_0 + step1_1) * COSPI_16_64;
        temp2 = (step1_0 - step1_1) * COSPI_16_64;
        step2_0 = (short) ((temp1 + 8192) >> 14);
        step2_1 = (short) ((temp2 + 8192) >> 14);
        temp1 = step1_2 * COSPI_24_64 - step1_3 * COSPI_8_64;
        temp2 = step1_2 * COSPI_8_64 + step1_3 * COSPI_24_64;
        step2_2 = (short) ((temp1 + 8192) >> 14);
        step2_3 = (short) ((temp2 + 8192) >> 14);
        step2_4 = (short) ((step1_4 + step1_5));
        step2_5 = (short) ((step1_4 - step1_5));
        step2_6 = (short) ((-step1_6 + step1_7));
        step2_7 = (short) ((step1_6 + step1_7));
        step2_8 = (short) (step1_8);
        step2_15 = (short) (step1_15);
        temp1 = -step1_9 * COSPI_8_64 + step1_14 * COSPI_24_64;
        temp2 = step1_9 * COSPI_24_64 + step1_14 * COSPI_8_64;
        step2_9 = (short) ((temp1 + 8192) >> 14);
        step2_14 = (short) ((temp2 + 8192) >> 14);
        temp1 = -step1_10 * COSPI_24_64 - step1_13 * COSPI_8_64;
        temp2 = -step1_10 * COSPI_8_64 + step1_13 * COSPI_24_64;
        step2_10 = (short) ((temp1 + 8192) >> 14);
        step2_13 = (short) ((temp2 + 8192) >> 14);
        step2_11 = (short) (step1_11);
        step2_12 = (short) (step1_12);
        step1_0 = (short) ((step2_0 + step2_3));
        step1_1 = (short) ((step2_1 + step2_2));
        step1_2 = (short) ((step2_1 - step2_2));
        step1_3 = (short) ((step2_0 - step2_3));
        step1_4 = (short) (step2_4);
        temp1 = (step2_6 - step2_5) * COSPI_16_64;
        temp2 = (step2_5 + step2_6) * COSPI_16_64;
        step1_5 = (short) ((temp1 + 8192) >> 14);
        step1_6 = (short) ((temp2 + 8192) >> 14);
        step1_7 = (short) (step2_7);
        step1_8 = (short) ((step2_8 + step2_11));
        step1_9 = (short) ((step2_9 + step2_10));
        step1_10 = (short) ((step2_9 - step2_10));
        step1_11 = (short) ((step2_8 - step2_11));
        step1_12 = (short) ((-step2_12 + step2_15));
        step1_13 = (short) ((-step2_13 + step2_14));
        step1_14 = (short) ((step2_13 + step2_14));
        step1_15 = (short) ((step2_12 + step2_15));
        step2_0 = (short) ((step1_0 + step1_7));
        step2_1 = (short) ((step1_1 + step1_6));
        step2_2 = (short) ((step1_2 + step1_5));
        step2_3 = (short) ((step1_3 + step1_4));
        step2_4 = (short) ((step1_3 - step1_4));
        step2_5 = (short) ((step1_2 - step1_5));
        step2_6 = (short) ((step1_1 - step1_6));
        step2_7 = (short) ((step1_0 - step1_7));
        step2_8 = (short) (step1_8);
        step2_9 = (short) (step1_9);
        temp1 = (-step1_10 + step1_13) * COSPI_16_64;
        temp2 = (step1_10 + step1_13) * COSPI_16_64;
        step2_10 = (short) ((temp1 + 8192) >> 14);
        step2_13 = (short) ((temp2 + 8192) >> 14);
        temp1 = (-step1_11 + step1_12) * COSPI_16_64;
        temp2 = (step1_11 + step1_12) * COSPI_16_64;
        step2_11 = (short) ((temp1 + 8192) >> 14);
        step2_12 = (short) ((temp2 + 8192) >> 14);
        step2_14 = (short) (step1_14);
        step2_15 = (short) (step1_15);
        out[outOff + 0] = (int) (step2_0 + step2_15);
        out[outOff + 1] = (int) (step2_1 + step2_14);
        out[outOff + 2] = (int) (step2_2 + step2_13);
        out[outOff + 3] = (int) (step2_3 + step2_12);
        out[outOff + 4] = (int) (step2_4 + step2_11);
        out[outOff + 5] = (int) (step2_5 + step2_10);
        out[outOff + 6] = (int) (step2_6 + step2_9);
        out[outOff + 7] = (int) (step2_7 + step2_8);
        out[outOff + 8] = (int) (step2_7 - step2_8);
        out[outOff + 9] = (int) (step2_6 - step2_9);
        out[outOff + 10] = (int) (step2_5 - step2_10);
        out[outOff + 11] = (int) (step2_4 - step2_11);
        out[outOff + 12] = (int) (step2_3 - step2_12);
        out[outOff + 13] = (int) (step2_2 - step2_13);
        out[outOff + 14] = (int) (step2_1 - step2_14);
        out[outOff + 15] = (int) (step2_0 - step2_15);
    }

    /**
     * Computes the 16-point inverse ADST of one row or column ({@code iadst16_c}).
     *
     * @param in the input array
     * @param inOff the index of the first input value
     * @param out the output array
     * @param outOff the index of the first output value
     */
    static void iadst16(int[] in, int inOff, int[] out, int outOff) {
        int s0 = 0;
        int s1 = 0;
        int s2 = 0;
        int s3 = 0;
        int s4 = 0;
        int s5 = 0;
        int s6 = 0;
        int s7 = 0;
        int s8 = 0;
        int s9 = 0;
        int s10 = 0;
        int s11 = 0;
        int s12 = 0;
        int s13 = 0;
        int s14 = 0;
        int s15 = 0;
        int x0 = in[inOff + 15];
        int x1 = in[inOff + 0];
        int x2 = in[inOff + 13];
        int x3 = in[inOff + 2];
        int x4 = in[inOff + 11];
        int x5 = in[inOff + 4];
        int x6 = in[inOff + 9];
        int x7 = in[inOff + 6];
        int x8 = in[inOff + 7];
        int x9 = in[inOff + 8];
        int x10 = in[inOff + 5];
        int x11 = in[inOff + 10];
        int x12 = in[inOff + 3];
        int x13 = in[inOff + 12];
        int x14 = in[inOff + 1];
        int x15 = in[inOff + 14];
        if ((x0 | x1 | x2 | x3 | x4 | x5 | x6 | x7 | x8 | x9 | x10 | x11 | x12 | x13 | x14 | x15) == 0) {
            Arrays.fill(out, outOff, outOff + 16, 0);
            return;
        }
        s0 = x0 * COSPI_1_64 + x1 * COSPI_31_64;
        s1 = x0 * COSPI_31_64 - x1 * COSPI_1_64;
        s2 = x2 * COSPI_5_64 + x3 * COSPI_27_64;
        s3 = x2 * COSPI_27_64 - x3 * COSPI_5_64;
        s4 = x4 * COSPI_9_64 + x5 * COSPI_23_64;
        s5 = x4 * COSPI_23_64 - x5 * COSPI_9_64;
        s6 = x6 * COSPI_13_64 + x7 * COSPI_19_64;
        s7 = x6 * COSPI_19_64 - x7 * COSPI_13_64;
        s8 = x8 * COSPI_17_64 + x9 * COSPI_15_64;
        s9 = x8 * COSPI_15_64 - x9 * COSPI_17_64;
        s10 = x10 * COSPI_21_64 + x11 * COSPI_11_64;
        s11 = x10 * COSPI_11_64 - x11 * COSPI_21_64;
        s12 = x12 * COSPI_25_64 + x13 * COSPI_7_64;
        s13 = x12 * COSPI_7_64 - x13 * COSPI_25_64;
        s14 = x14 * COSPI_29_64 + x15 * COSPI_3_64;
        s15 = x14 * COSPI_3_64 - x15 * COSPI_29_64;
        x0 = (((s0 + s8) + 8192) >> 14);
        x1 = (((s1 + s9) + 8192) >> 14);
        x2 = (((s2 + s10) + 8192) >> 14);
        x3 = (((s3 + s11) + 8192) >> 14);
        x4 = (((s4 + s12) + 8192) >> 14);
        x5 = (((s5 + s13) + 8192) >> 14);
        x6 = (((s6 + s14) + 8192) >> 14);
        x7 = (((s7 + s15) + 8192) >> 14);
        x8 = (((s0 - s8) + 8192) >> 14);
        x9 = (((s1 - s9) + 8192) >> 14);
        x10 = (((s2 - s10) + 8192) >> 14);
        x11 = (((s3 - s11) + 8192) >> 14);
        x12 = (((s4 - s12) + 8192) >> 14);
        x13 = (((s5 - s13) + 8192) >> 14);
        x14 = (((s6 - s14) + 8192) >> 14);
        x15 = (((s7 - s15) + 8192) >> 14);
        s0 = x0;
        s1 = x1;
        s2 = x2;
        s3 = x3;
        s4 = x4;
        s5 = x5;
        s6 = x6;
        s7 = x7;
        s8 = x8 * COSPI_4_64 + x9 * COSPI_28_64;
        s9 = x8 * COSPI_28_64 - x9 * COSPI_4_64;
        s10 = x10 * COSPI_20_64 + x11 * COSPI_12_64;
        s11 = x10 * COSPI_12_64 - x11 * COSPI_20_64;
        s12 = -x12 * COSPI_28_64 + x13 * COSPI_4_64;
        s13 = x12 * COSPI_4_64 + x13 * COSPI_28_64;
        s14 = -x14 * COSPI_12_64 + x15 * COSPI_20_64;
        s15 = x14 * COSPI_20_64 + x15 * COSPI_12_64;
        x0 = (s0 + s4);
        x1 = (s1 + s5);
        x2 = (s2 + s6);
        x3 = (s3 + s7);
        x4 = (s0 - s4);
        x5 = (s1 - s5);
        x6 = (s2 - s6);
        x7 = (s3 - s7);
        x8 = (((s8 + s12) + 8192) >> 14);
        x9 = (((s9 + s13) + 8192) >> 14);
        x10 = (((s10 + s14) + 8192) >> 14);
        x11 = (((s11 + s15) + 8192) >> 14);
        x12 = (((s8 - s12) + 8192) >> 14);
        x13 = (((s9 - s13) + 8192) >> 14);
        x14 = (((s10 - s14) + 8192) >> 14);
        x15 = (((s11 - s15) + 8192) >> 14);
        s0 = x0;
        s1 = x1;
        s2 = x2;
        s3 = x3;
        s4 = x4 * COSPI_8_64 + x5 * COSPI_24_64;
        s5 = x4 * COSPI_24_64 - x5 * COSPI_8_64;
        s6 = -x6 * COSPI_24_64 + x7 * COSPI_8_64;
        s7 = x6 * COSPI_8_64 + x7 * COSPI_24_64;
        s8 = x8;
        s9 = x9;
        s10 = x10;
        s11 = x11;
        s12 = x12 * COSPI_8_64 + x13 * COSPI_24_64;
        s13 = x12 * COSPI_24_64 - x13 * COSPI_8_64;
        s14 = -x14 * COSPI_24_64 + x15 * COSPI_8_64;
        s15 = x14 * COSPI_8_64 + x15 * COSPI_24_64;
        x0 = (s0 + s2);
        x1 = (s1 + s3);
        x2 = (s0 - s2);
        x3 = (s1 - s3);
        x4 = (((s4 + s6) + 8192) >> 14);
        x5 = (((s5 + s7) + 8192) >> 14);
        x6 = (((s4 - s6) + 8192) >> 14);
        x7 = (((s5 - s7) + 8192) >> 14);
        x8 = (s8 + s10);
        x9 = (s9 + s11);
        x10 = (s8 - s10);
        x11 = (s9 - s11);
        x12 = (((s12 + s14) + 8192) >> 14);
        x13 = (((s13 + s15) + 8192) >> 14);
        x14 = (((s12 - s14) + 8192) >> 14);
        x15 = (((s13 - s15) + 8192) >> 14);
        s2 = (-COSPI_16_64) * (x2 + x3);
        s3 = COSPI_16_64 * (x2 - x3);
        s6 = COSPI_16_64 * (x6 + x7);
        s7 = COSPI_16_64 * (-x6 + x7);
        s10 = COSPI_16_64 * (x10 + x11);
        s11 = COSPI_16_64 * (-x10 + x11);
        s14 = (-COSPI_16_64) * (x14 + x15);
        s15 = COSPI_16_64 * (x14 - x15);
        x2 = ((s2 + 8192) >> 14);
        x3 = ((s3 + 8192) >> 14);
        x6 = ((s6 + 8192) >> 14);
        x7 = ((s7 + 8192) >> 14);
        x10 = ((s10 + 8192) >> 14);
        x11 = ((s11 + 8192) >> 14);
        x14 = ((s14 + 8192) >> 14);
        x15 = ((s15 + 8192) >> 14);
        out[outOff + 0] = (x0);
        out[outOff + 1] = (-x8);
        out[outOff + 2] = (x12);
        out[outOff + 3] = (-x4);
        out[outOff + 4] = (x6);
        out[outOff + 5] = (x14);
        out[outOff + 6] = (x10);
        out[outOff + 7] = (x2);
        out[outOff + 8] = (x3);
        out[outOff + 9] = (x11);
        out[outOff + 10] = (x15);
        out[outOff + 11] = (x7);
        out[outOff + 12] = (x5);
        out[outOff + 13] = (-x13);
        out[outOff + 14] = (x9);
        out[outOff + 15] = (-x1);
    }

    /**
     * Computes the 32-point inverse DCT of one row or column ({@code idct32_c}).
     *
     * @param in the input array
     * @param inOff the index of the first input value
     * @param out the output array
     * @param outOff the index of the first output value
     */
    static void idct32(int[] in, int inOff, int[] out, int outOff) {
        int step1_0 = 0, step1_1 = 0, step1_2 = 0, step1_3 = 0, step1_4 = 0, step1_5 = 0, step1_6 = 0, step1_7 = 0, step1_8 = 0, step1_9 = 0, step1_10 = 0, step1_11 = 0, step1_12 = 0, step1_13 = 0, step1_14 = 0, step1_15 = 0, step1_16 = 0, step1_17 = 0, step1_18 = 0, step1_19 = 0, step1_20 = 0, step1_21 = 0, step1_22 = 0, step1_23 = 0, step1_24 = 0, step1_25 = 0, step1_26 = 0, step1_27 = 0, step1_28 = 0, step1_29 = 0, step1_30 = 0, step1_31 = 0;
        int step2_0 = 0, step2_1 = 0, step2_2 = 0, step2_3 = 0, step2_4 = 0, step2_5 = 0, step2_6 = 0, step2_7 = 0, step2_8 = 0, step2_9 = 0, step2_10 = 0, step2_11 = 0, step2_12 = 0, step2_13 = 0, step2_14 = 0, step2_15 = 0, step2_16 = 0, step2_17 = 0, step2_18 = 0, step2_19 = 0, step2_20 = 0, step2_21 = 0, step2_22 = 0, step2_23 = 0, step2_24 = 0, step2_25 = 0, step2_26 = 0, step2_27 = 0, step2_28 = 0, step2_29 = 0, step2_30 = 0, step2_31 = 0;
        int temp1 = 0;
        int temp2 = 0;
        step1_0 = (short) (in[inOff + 0]);
        step1_1 = (short) (in[inOff + 16]);
        step1_2 = (short) (in[inOff + 8]);
        step1_3 = (short) (in[inOff + 24]);
        step1_4 = (short) (in[inOff + 4]);
        step1_5 = (short) (in[inOff + 20]);
        step1_6 = (short) (in[inOff + 12]);
        step1_7 = (short) (in[inOff + 28]);
        step1_8 = (short) (in[inOff + 2]);
        step1_9 = (short) (in[inOff + 18]);
        step1_10 = (short) (in[inOff + 10]);
        step1_11 = (short) (in[inOff + 26]);
        step1_12 = (short) (in[inOff + 6]);
        step1_13 = (short) (in[inOff + 22]);
        step1_14 = (short) (in[inOff + 14]);
        step1_15 = (short) (in[inOff + 30]);
        temp1 = (short) in[inOff + 1] * COSPI_31_64 - (short) in[inOff + 31] * COSPI_1_64;
        temp2 = (short) in[inOff + 1] * COSPI_1_64 + (short) in[inOff + 31] * COSPI_31_64;
        step1_16 = (short) ((temp1 + 8192) >> 14);
        step1_31 = (short) ((temp2 + 8192) >> 14);
        temp1 = (short) in[inOff + 17] * COSPI_15_64 - (short) in[inOff + 15] * COSPI_17_64;
        temp2 = (short) in[inOff + 17] * COSPI_17_64 + (short) in[inOff + 15] * COSPI_15_64;
        step1_17 = (short) ((temp1 + 8192) >> 14);
        step1_30 = (short) ((temp2 + 8192) >> 14);
        temp1 = (short) in[inOff + 9] * COSPI_23_64 - (short) in[inOff + 23] * COSPI_9_64;
        temp2 = (short) in[inOff + 9] * COSPI_9_64 + (short) in[inOff + 23] * COSPI_23_64;
        step1_18 = (short) ((temp1 + 8192) >> 14);
        step1_29 = (short) ((temp2 + 8192) >> 14);
        temp1 = (short) in[inOff + 25] * COSPI_7_64 - (short) in[inOff + 7] * COSPI_25_64;
        temp2 = (short) in[inOff + 25] * COSPI_25_64 + (short) in[inOff + 7] * COSPI_7_64;
        step1_19 = (short) ((temp1 + 8192) >> 14);
        step1_28 = (short) ((temp2 + 8192) >> 14);
        temp1 = (short) in[inOff + 5] * COSPI_27_64 - (short) in[inOff + 27] * COSPI_5_64;
        temp2 = (short) in[inOff + 5] * COSPI_5_64 + (short) in[inOff + 27] * COSPI_27_64;
        step1_20 = (short) ((temp1 + 8192) >> 14);
        step1_27 = (short) ((temp2 + 8192) >> 14);
        temp1 = (short) in[inOff + 21] * COSPI_11_64 - (short) in[inOff + 11] * COSPI_21_64;
        temp2 = (short) in[inOff + 21] * COSPI_21_64 + (short) in[inOff + 11] * COSPI_11_64;
        step1_21 = (short) ((temp1 + 8192) >> 14);
        step1_26 = (short) ((temp2 + 8192) >> 14);
        temp1 = (short) in[inOff + 13] * COSPI_19_64 - (short) in[inOff + 19] * COSPI_13_64;
        temp2 = (short) in[inOff + 13] * COSPI_13_64 + (short) in[inOff + 19] * COSPI_19_64;
        step1_22 = (short) ((temp1 + 8192) >> 14);
        step1_25 = (short) ((temp2 + 8192) >> 14);
        temp1 = (short) in[inOff + 29] * COSPI_3_64 - (short) in[inOff + 3] * COSPI_29_64;
        temp2 = (short) in[inOff + 29] * COSPI_29_64 + (short) in[inOff + 3] * COSPI_3_64;
        step1_23 = (short) ((temp1 + 8192) >> 14);
        step1_24 = (short) ((temp2 + 8192) >> 14);
        step2_0 = (short) (step1_0);
        step2_1 = (short) (step1_1);
        step2_2 = (short) (step1_2);
        step2_3 = (short) (step1_3);
        step2_4 = (short) (step1_4);
        step2_5 = (short) (step1_5);
        step2_6 = (short) (step1_6);
        step2_7 = (short) (step1_7);
        temp1 = step1_8 * COSPI_30_64 - step1_15 * COSPI_2_64;
        temp2 = step1_8 * COSPI_2_64 + step1_15 * COSPI_30_64;
        step2_8 = (short) ((temp1 + 8192) >> 14);
        step2_15 = (short) ((temp2 + 8192) >> 14);
        temp1 = step1_9 * COSPI_14_64 - step1_14 * COSPI_18_64;
        temp2 = step1_9 * COSPI_18_64 + step1_14 * COSPI_14_64;
        step2_9 = (short) ((temp1 + 8192) >> 14);
        step2_14 = (short) ((temp2 + 8192) >> 14);
        temp1 = step1_10 * COSPI_22_64 - step1_13 * COSPI_10_64;
        temp2 = step1_10 * COSPI_10_64 + step1_13 * COSPI_22_64;
        step2_10 = (short) ((temp1 + 8192) >> 14);
        step2_13 = (short) ((temp2 + 8192) >> 14);
        temp1 = step1_11 * COSPI_6_64 - step1_12 * COSPI_26_64;
        temp2 = step1_11 * COSPI_26_64 + step1_12 * COSPI_6_64;
        step2_11 = (short) ((temp1 + 8192) >> 14);
        step2_12 = (short) ((temp2 + 8192) >> 14);
        step2_16 = (short) ((step1_16 + step1_17));
        step2_17 = (short) ((step1_16 - step1_17));
        step2_18 = (short) ((-step1_18 + step1_19));
        step2_19 = (short) ((step1_18 + step1_19));
        step2_20 = (short) ((step1_20 + step1_21));
        step2_21 = (short) ((step1_20 - step1_21));
        step2_22 = (short) ((-step1_22 + step1_23));
        step2_23 = (short) ((step1_22 + step1_23));
        step2_24 = (short) ((step1_24 + step1_25));
        step2_25 = (short) ((step1_24 - step1_25));
        step2_26 = (short) ((-step1_26 + step1_27));
        step2_27 = (short) ((step1_26 + step1_27));
        step2_28 = (short) ((step1_28 + step1_29));
        step2_29 = (short) ((step1_28 - step1_29));
        step2_30 = (short) ((-step1_30 + step1_31));
        step2_31 = (short) ((step1_30 + step1_31));
        step1_0 = (short) (step2_0);
        step1_1 = (short) (step2_1);
        step1_2 = (short) (step2_2);
        step1_3 = (short) (step2_3);
        temp1 = step2_4 * COSPI_28_64 - step2_7 * COSPI_4_64;
        temp2 = step2_4 * COSPI_4_64 + step2_7 * COSPI_28_64;
        step1_4 = (short) ((temp1 + 8192) >> 14);
        step1_7 = (short) ((temp2 + 8192) >> 14);
        temp1 = step2_5 * COSPI_12_64 - step2_6 * COSPI_20_64;
        temp2 = step2_5 * COSPI_20_64 + step2_6 * COSPI_12_64;
        step1_5 = (short) ((temp1 + 8192) >> 14);
        step1_6 = (short) ((temp2 + 8192) >> 14);
        step1_8 = (short) ((step2_8 + step2_9));
        step1_9 = (short) ((step2_8 - step2_9));
        step1_10 = (short) ((-step2_10 + step2_11));
        step1_11 = (short) ((step2_10 + step2_11));
        step1_12 = (short) ((step2_12 + step2_13));
        step1_13 = (short) ((step2_12 - step2_13));
        step1_14 = (short) ((-step2_14 + step2_15));
        step1_15 = (short) ((step2_14 + step2_15));
        step1_16 = (short) (step2_16);
        step1_31 = (short) (step2_31);
        temp1 = -step2_17 * COSPI_4_64 + step2_30 * COSPI_28_64;
        temp2 = step2_17 * COSPI_28_64 + step2_30 * COSPI_4_64;
        step1_17 = (short) ((temp1 + 8192) >> 14);
        step1_30 = (short) ((temp2 + 8192) >> 14);
        temp1 = -step2_18 * COSPI_28_64 - step2_29 * COSPI_4_64;
        temp2 = -step2_18 * COSPI_4_64 + step2_29 * COSPI_28_64;
        step1_18 = (short) ((temp1 + 8192) >> 14);
        step1_29 = (short) ((temp2 + 8192) >> 14);
        step1_19 = (short) (step2_19);
        step1_20 = (short) (step2_20);
        temp1 = -step2_21 * COSPI_20_64 + step2_26 * COSPI_12_64;
        temp2 = step2_21 * COSPI_12_64 + step2_26 * COSPI_20_64;
        step1_21 = (short) ((temp1 + 8192) >> 14);
        step1_26 = (short) ((temp2 + 8192) >> 14);
        temp1 = -step2_22 * COSPI_12_64 - step2_25 * COSPI_20_64;
        temp2 = -step2_22 * COSPI_20_64 + step2_25 * COSPI_12_64;
        step1_22 = (short) ((temp1 + 8192) >> 14);
        step1_25 = (short) ((temp2 + 8192) >> 14);
        step1_23 = (short) (step2_23);
        step1_24 = (short) (step2_24);
        step1_27 = (short) (step2_27);
        step1_28 = (short) (step2_28);
        temp1 = (step1_0 + step1_1) * COSPI_16_64;
        temp2 = (step1_0 - step1_1) * COSPI_16_64;
        step2_0 = (short) ((temp1 + 8192) >> 14);
        step2_1 = (short) ((temp2 + 8192) >> 14);
        temp1 = step1_2 * COSPI_24_64 - step1_3 * COSPI_8_64;
        temp2 = step1_2 * COSPI_8_64 + step1_3 * COSPI_24_64;
        step2_2 = (short) ((temp1 + 8192) >> 14);
        step2_3 = (short) ((temp2 + 8192) >> 14);
        step2_4 = (short) ((step1_4 + step1_5));
        step2_5 = (short) ((step1_4 - step1_5));
        step2_6 = (short) ((-step1_6 + step1_7));
        step2_7 = (short) ((step1_6 + step1_7));
        step2_8 = (short) (step1_8);
        step2_15 = (short) (step1_15);
        temp1 = -step1_9 * COSPI_8_64 + step1_14 * COSPI_24_64;
        temp2 = step1_9 * COSPI_24_64 + step1_14 * COSPI_8_64;
        step2_9 = (short) ((temp1 + 8192) >> 14);
        step2_14 = (short) ((temp2 + 8192) >> 14);
        temp1 = -step1_10 * COSPI_24_64 - step1_13 * COSPI_8_64;
        temp2 = -step1_10 * COSPI_8_64 + step1_13 * COSPI_24_64;
        step2_10 = (short) ((temp1 + 8192) >> 14);
        step2_13 = (short) ((temp2 + 8192) >> 14);
        step2_11 = (short) (step1_11);
        step2_12 = (short) (step1_12);
        step2_16 = (short) ((step1_16 + step1_19));
        step2_17 = (short) ((step1_17 + step1_18));
        step2_18 = (short) ((step1_17 - step1_18));
        step2_19 = (short) ((step1_16 - step1_19));
        step2_20 = (short) ((-step1_20 + step1_23));
        step2_21 = (short) ((-step1_21 + step1_22));
        step2_22 = (short) ((step1_21 + step1_22));
        step2_23 = (short) ((step1_20 + step1_23));
        step2_24 = (short) ((step1_24 + step1_27));
        step2_25 = (short) ((step1_25 + step1_26));
        step2_26 = (short) ((step1_25 - step1_26));
        step2_27 = (short) ((step1_24 - step1_27));
        step2_28 = (short) ((-step1_28 + step1_31));
        step2_29 = (short) ((-step1_29 + step1_30));
        step2_30 = (short) ((step1_29 + step1_30));
        step2_31 = (short) ((step1_28 + step1_31));
        step1_0 = (short) ((step2_0 + step2_3));
        step1_1 = (short) ((step2_1 + step2_2));
        step1_2 = (short) ((step2_1 - step2_2));
        step1_3 = (short) ((step2_0 - step2_3));
        step1_4 = (short) (step2_4);
        temp1 = (step2_6 - step2_5) * COSPI_16_64;
        temp2 = (step2_5 + step2_6) * COSPI_16_64;
        step1_5 = (short) ((temp1 + 8192) >> 14);
        step1_6 = (short) ((temp2 + 8192) >> 14);
        step1_7 = (short) (step2_7);
        step1_8 = (short) ((step2_8 + step2_11));
        step1_9 = (short) ((step2_9 + step2_10));
        step1_10 = (short) ((step2_9 - step2_10));
        step1_11 = (short) ((step2_8 - step2_11));
        step1_12 = (short) ((-step2_12 + step2_15));
        step1_13 = (short) ((-step2_13 + step2_14));
        step1_14 = (short) ((step2_13 + step2_14));
        step1_15 = (short) ((step2_12 + step2_15));
        step1_16 = (short) (step2_16);
        step1_17 = (short) (step2_17);
        temp1 = -step2_18 * COSPI_8_64 + step2_29 * COSPI_24_64;
        temp2 = step2_18 * COSPI_24_64 + step2_29 * COSPI_8_64;
        step1_18 = (short) ((temp1 + 8192) >> 14);
        step1_29 = (short) ((temp2 + 8192) >> 14);
        temp1 = -step2_19 * COSPI_8_64 + step2_28 * COSPI_24_64;
        temp2 = step2_19 * COSPI_24_64 + step2_28 * COSPI_8_64;
        step1_19 = (short) ((temp1 + 8192) >> 14);
        step1_28 = (short) ((temp2 + 8192) >> 14);
        temp1 = -step2_20 * COSPI_24_64 - step2_27 * COSPI_8_64;
        temp2 = -step2_20 * COSPI_8_64 + step2_27 * COSPI_24_64;
        step1_20 = (short) ((temp1 + 8192) >> 14);
        step1_27 = (short) ((temp2 + 8192) >> 14);
        temp1 = -step2_21 * COSPI_24_64 - step2_26 * COSPI_8_64;
        temp2 = -step2_21 * COSPI_8_64 + step2_26 * COSPI_24_64;
        step1_21 = (short) ((temp1 + 8192) >> 14);
        step1_26 = (short) ((temp2 + 8192) >> 14);
        step1_22 = (short) (step2_22);
        step1_23 = (short) (step2_23);
        step1_24 = (short) (step2_24);
        step1_25 = (short) (step2_25);
        step1_30 = (short) (step2_30);
        step1_31 = (short) (step2_31);
        step2_0 = (short) ((step1_0 + step1_7));
        step2_1 = (short) ((step1_1 + step1_6));
        step2_2 = (short) ((step1_2 + step1_5));
        step2_3 = (short) ((step1_3 + step1_4));
        step2_4 = (short) ((step1_3 - step1_4));
        step2_5 = (short) ((step1_2 - step1_5));
        step2_6 = (short) ((step1_1 - step1_6));
        step2_7 = (short) ((step1_0 - step1_7));
        step2_8 = (short) (step1_8);
        step2_9 = (short) (step1_9);
        temp1 = (-step1_10 + step1_13) * COSPI_16_64;
        temp2 = (step1_10 + step1_13) * COSPI_16_64;
        step2_10 = (short) ((temp1 + 8192) >> 14);
        step2_13 = (short) ((temp2 + 8192) >> 14);
        temp1 = (-step1_11 + step1_12) * COSPI_16_64;
        temp2 = (step1_11 + step1_12) * COSPI_16_64;
        step2_11 = (short) ((temp1 + 8192) >> 14);
        step2_12 = (short) ((temp2 + 8192) >> 14);
        step2_14 = (short) (step1_14);
        step2_15 = (short) (step1_15);
        step2_16 = (short) ((step1_16 + step1_23));
        step2_17 = (short) ((step1_17 + step1_22));
        step2_18 = (short) ((step1_18 + step1_21));
        step2_19 = (short) ((step1_19 + step1_20));
        step2_20 = (short) ((step1_19 - step1_20));
        step2_21 = (short) ((step1_18 - step1_21));
        step2_22 = (short) ((step1_17 - step1_22));
        step2_23 = (short) ((step1_16 - step1_23));
        step2_24 = (short) ((-step1_24 + step1_31));
        step2_25 = (short) ((-step1_25 + step1_30));
        step2_26 = (short) ((-step1_26 + step1_29));
        step2_27 = (short) ((-step1_27 + step1_28));
        step2_28 = (short) ((step1_27 + step1_28));
        step2_29 = (short) ((step1_26 + step1_29));
        step2_30 = (short) ((step1_25 + step1_30));
        step2_31 = (short) ((step1_24 + step1_31));
        step1_0 = (short) ((step2_0 + step2_15));
        step1_1 = (short) ((step2_1 + step2_14));
        step1_2 = (short) ((step2_2 + step2_13));
        step1_3 = (short) ((step2_3 + step2_12));
        step1_4 = (short) ((step2_4 + step2_11));
        step1_5 = (short) ((step2_5 + step2_10));
        step1_6 = (short) ((step2_6 + step2_9));
        step1_7 = (short) ((step2_7 + step2_8));
        step1_8 = (short) ((step2_7 - step2_8));
        step1_9 = (short) ((step2_6 - step2_9));
        step1_10 = (short) ((step2_5 - step2_10));
        step1_11 = (short) ((step2_4 - step2_11));
        step1_12 = (short) ((step2_3 - step2_12));
        step1_13 = (short) ((step2_2 - step2_13));
        step1_14 = (short) ((step2_1 - step2_14));
        step1_15 = (short) ((step2_0 - step2_15));
        step1_16 = (short) (step2_16);
        step1_17 = (short) (step2_17);
        step1_18 = (short) (step2_18);
        step1_19 = (short) (step2_19);
        temp1 = (-step2_20 + step2_27) * COSPI_16_64;
        temp2 = (step2_20 + step2_27) * COSPI_16_64;
        step1_20 = (short) ((temp1 + 8192) >> 14);
        step1_27 = (short) ((temp2 + 8192) >> 14);
        temp1 = (-step2_21 + step2_26) * COSPI_16_64;
        temp2 = (step2_21 + step2_26) * COSPI_16_64;
        step1_21 = (short) ((temp1 + 8192) >> 14);
        step1_26 = (short) ((temp2 + 8192) >> 14);
        temp1 = (-step2_22 + step2_25) * COSPI_16_64;
        temp2 = (step2_22 + step2_25) * COSPI_16_64;
        step1_22 = (short) ((temp1 + 8192) >> 14);
        step1_25 = (short) ((temp2 + 8192) >> 14);
        temp1 = (-step2_23 + step2_24) * COSPI_16_64;
        temp2 = (step2_23 + step2_24) * COSPI_16_64;
        step1_23 = (short) ((temp1 + 8192) >> 14);
        step1_24 = (short) ((temp2 + 8192) >> 14);
        step1_28 = (short) (step2_28);
        step1_29 = (short) (step2_29);
        step1_30 = (short) (step2_30);
        step1_31 = (short) (step2_31);
        out[outOff + 0] = (step1_0 + step1_31);
        out[outOff + 1] = (step1_1 + step1_30);
        out[outOff + 2] = (step1_2 + step1_29);
        out[outOff + 3] = (step1_3 + step1_28);
        out[outOff + 4] = (step1_4 + step1_27);
        out[outOff + 5] = (step1_5 + step1_26);
        out[outOff + 6] = (step1_6 + step1_25);
        out[outOff + 7] = (step1_7 + step1_24);
        out[outOff + 8] = (step1_8 + step1_23);
        out[outOff + 9] = (step1_9 + step1_22);
        out[outOff + 10] = (step1_10 + step1_21);
        out[outOff + 11] = (step1_11 + step1_20);
        out[outOff + 12] = (step1_12 + step1_19);
        out[outOff + 13] = (step1_13 + step1_18);
        out[outOff + 14] = (step1_14 + step1_17);
        out[outOff + 15] = (step1_15 + step1_16);
        out[outOff + 16] = (step1_15 - step1_16);
        out[outOff + 17] = (step1_14 - step1_17);
        out[outOff + 18] = (step1_13 - step1_18);
        out[outOff + 19] = (step1_12 - step1_19);
        out[outOff + 20] = (step1_11 - step1_20);
        out[outOff + 21] = (step1_10 - step1_21);
        out[outOff + 22] = (step1_9 - step1_22);
        out[outOff + 23] = (step1_8 - step1_23);
        out[outOff + 24] = (step1_7 - step1_24);
        out[outOff + 25] = (step1_6 - step1_25);
        out[outOff + 26] = (step1_5 - step1_26);
        out[outOff + 27] = (step1_4 - step1_27);
        out[outOff + 28] = (step1_3 - step1_28);
        out[outOff + 29] = (step1_2 - step1_29);
        out[outOff + 30] = (step1_1 - step1_30);
        out[outOff + 31] = (step1_0 - step1_31);
    }
}
