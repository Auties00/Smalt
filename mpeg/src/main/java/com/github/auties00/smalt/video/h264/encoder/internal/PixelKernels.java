package com.github.auties00.smalt.video.h264.encoder.internal;

import java.util.Arrays;

/**
 * Computes the block distortion metrics and pixel moves used by motion estimation, mode decision
 * and motion compensation.
 *
 * <p>This class is the scalar reference implementation; a Vector API subclass overrides the hot
 * methods with identical results. Every method reads blocks at an offset and a row stride in a
 * byte array of unsigned samples. Block widths are 4, 8 or 16 and heights 4, 8 or 16 unless a
 * method says otherwise.
 */
class PixelKernels {
    /**
     * The number of values of the scratch array the SATD methods over 16-sample rows need: the
     * transformed rows of a whole macroblock, sixteen rows of sixteen values.
     */
    static final int SATD_SCRATCH = 256;

    /**
     * Constructs the scalar kernels.
     */
    PixelKernels() {
    }

    /**
     * Returns the sum of absolute differences of two blocks.
     *
     * @param a the first block's samples
     * @param aOffset the index of the first block's top-left sample
     * @param aStride the first block's row stride
     * @param b the second block's samples
     * @param bOffset the index of the second block's top-left sample
     * @param bStride the second block's row stride
     * @param width the block width
     * @param height the block height
     * @return the SAD
     */
    int sad(byte[] a, int aOffset, int aStride, byte[] b, int bOffset, int bStride, int width, int height) {
        var sum = 0;
        for (var y = 0; y < height; y++) {
            var ai = aOffset + y * aStride;
            var bi = bOffset + y * bStride;
            for (var x = 0; x < width; x++) {
                sum += Math.abs((a[ai + x] & 0xFF) - (b[bi + x] & 0xFF));
            }
        }
        return sum;
    }

    /**
     * Returns the sum of squared differences of two blocks.
     *
     * @param a the first block's samples
     * @param aOffset the index of the first block's top-left sample
     * @param aStride the first block's row stride
     * @param b the second block's samples
     * @param bOffset the index of the second block's top-left sample
     * @param bStride the second block's row stride
     * @param width the block width, any positive value
     * @param height the block height, any positive value
     * @return the SSD
     */
    long ssd(byte[] a, int aOffset, int aStride, byte[] b, int bOffset, int bStride, int width, int height) {
        long sum = 0;
        for (var y = 0; y < height; y++) {
            var ai = aOffset + y * aStride;
            var bi = bOffset + y * bStride;
            var row = 0;
            for (var x = 0; x < width; x++) {
                var d = (a[ai + x] & 0xFF) - (b[bi + x] & 0xFF);
                row += d * d;
            }
            sum += row;
        }
        return sum;
    }

    /**
     * Returns the Hadamard-transformed difference of two blocks, the sum over their 4x4
     * sub-blocks of the absolute 4x4 Hadamard coefficients of the difference, halved.
     *
     * @param a the first block's samples
     * @param aOffset the index of the first block's top-left sample
     * @param aStride the first block's row stride
     * @param b the second block's samples
     * @param bOffset the index of the second block's top-left sample
     * @param bStride the second block's row stride
     * @param width the block width, a multiple of 4
     * @param height the block height, a multiple of 4
     * @return the SATD
     */
    int satd(byte[] a, int aOffset, int aStride, byte[] b, int bOffset, int bStride, int width, int height) {
        var sum = 0;
        for (var y = 0; y < height; y += 4) {
            for (var x = 0; x < width; x += 4) {
                sum += satd4x4(a, aOffset + y * aStride + x, aStride, b, bOffset + y * bStride + x, bStride);
            }
        }
        return sum >> 1;
    }

    /**
     * Returns the SATD of a block 16 samples wide against the rounded-up average
     * {@code (a + b + 1) >> 1} of two others, as {@link #satd} of the block and the result of
     * {@link #average} would.
     *
     * @param src the block's samples
     * @param srcOffset the index of the block's top-left sample
     * @param srcStride the block's row stride
     * @param a the first averaged block's samples
     * @param aOffset the index of the first averaged block's top-left sample
     * @param b the second averaged block's samples
     * @param bOffset the index of the second averaged block's top-left sample
     * @param stride the row stride of both averaged blocks
     * @param height the block height, 8 or 16
     * @param scratch scratch space of at least {@link #SATD_SCRATCH} values, private to the
     * caller
     * @return the SATD
     */
    int satdAverage16(byte[] src, int srcOffset, int srcStride, byte[] a, int aOffset, byte[] b, int bOffset,
                      int stride, int height, short[] scratch) {
        var sum = 0;
        for (var y = 0; y < height; y += 4) {
            for (var x = 0; x < 16; x += 4) {
                sum += satdAverage4x4(src, srcOffset + y * srcStride + x, srcStride, a, aOffset + y * stride + x, b,
                        bOffset + y * stride + x, stride);
            }
        }
        return sum >> 1;
    }

    /**
     * Returns the unhalved SATDs of the four 8x8 quadrants of two 16x16 blocks, each the sum of
     * the absolute 4x4 Hadamard coefficients of its four 4x4 blocks, packed as four unsigned
     * 16-bit fields: top-left in bits 0 to 15, then top-right, bottom-left and bottom-right.
     *
     * <p>The SATD of any union of quadrants is the sum of their fields shifted right by one.
     *
     * @param a the first block's samples
     * @param aOffset the index of the first block's top-left sample
     * @param aStride the first block's row stride
     * @param b the second block's samples
     * @param bOffset the index of the second block's top-left sample
     * @param bStride the second block's row stride
     * @param scratch scratch space of at least {@link #SATD_SCRATCH} values, private to the caller
     * @return the packed unhalved quadrant SATDs
     */
    long satdQuadrants(byte[] a, int aOffset, int aStride, byte[] b, int bOffset, int bStride, short[] scratch) {
        var packed = 0L;
        for (var quadrant = 0; quadrant < 4; quadrant++) {
            var x = (quadrant & 1) * 8;
            var y = (quadrant >> 1) * 8;
            var sum = 0L;
            for (var block = 0; block < 4; block++) {
                var bx = x + (block & 1) * 4;
                var by = y + (block >> 1) * 4;
                sum += satd4x4(a, aOffset + by * aStride + bx, aStride, b, bOffset + by * bStride + bx, bStride);
            }
            packed |= sum << (16 * quadrant);
        }
        return packed;
    }

    /**
     * Returns the unhalved SATDs of the four 8x8 quadrants of a 16x16 block against the
     * rounded-up average {@code (a + b + 1) >> 1} of two others, packed like
     * {@link #satdQuadrants}.
     *
     * @param src the block's samples
     * @param srcOffset the index of the block's top-left sample
     * @param srcStride the block's row stride
     * @param a the first averaged block's samples
     * @param aOffset the index of the first averaged block's top-left sample
     * @param b the second averaged block's samples
     * @param bOffset the index of the second averaged block's top-left sample
     * @param stride the row stride of both averaged blocks
     * @param scratch scratch space of at least {@link #SATD_SCRATCH} values, private to the
     * caller
     * @return the packed unhalved quadrant SATDs
     */
    long satdAverageQuadrants(byte[] src, int srcOffset, int srcStride, byte[] a, int aOffset, byte[] b, int bOffset,
                              int stride, short[] scratch) {
        var packed = 0L;
        for (var quadrant = 0; quadrant < 4; quadrant++) {
            var x = (quadrant & 1) * 8;
            var y = (quadrant >> 1) * 8;
            var sum = 0L;
            for (var block = 0; block < 4; block++) {
                var bx = x + (block & 1) * 4;
                var by = y + (block >> 1) * 4;
                sum += satdAverage4x4(src, srcOffset + by * srcStride + bx, srcStride, a, aOffset + by * stride + bx,
                        b, bOffset + by * stride + bx, stride);
            }
            packed |= sum << (16 * quadrant);
        }
        return packed;
    }

    /**
     * Returns the sum of absolute 4x4 Hadamard coefficients of the difference of a 4x4 block and
     * the rounded-up average of two others.
     *
     * @param src the block's samples
     * @param srcOffset the index of the block's top-left sample
     * @param srcStride the block's row stride
     * @param a the first averaged block's samples
     * @param aOffset the index of the first averaged block's top-left sample
     * @param b the second averaged block's samples
     * @param bOffset the index of the second averaged block's top-left sample
     * @param stride the row stride of both averaged blocks
     * @return the unhalved SATD of the 4x4 block
     */
    private static int satdAverage4x4(byte[] src, int srcOffset, int srcStride, byte[] a, int aOffset, byte[] b,
                                      int bOffset, int stride) {
        int t0, t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13, t14, t15;
        {
            var d0 = averagedDifference(src, srcOffset, a, b, aOffset, bOffset);
            var d1 = averagedDifference(src, srcOffset + 1, a, b, aOffset + 1, bOffset + 1);
            var d2 = averagedDifference(src, srcOffset + 2, a, b, aOffset + 2, bOffset + 2);
            var d3 = averagedDifference(src, srcOffset + 3, a, b, aOffset + 3, bOffset + 3);
            t0 = d0 + d1 + d2 + d3;
            t1 = d0 + d1 - d2 - d3;
            t2 = d0 - d1 - d2 + d3;
            t3 = d0 - d1 + d2 - d3;
        }
        {
            var si = srcOffset + srcStride;
            var ai = aOffset + stride;
            var bi = bOffset + stride;
            var d0 = averagedDifference(src, si, a, b, ai, bi);
            var d1 = averagedDifference(src, si + 1, a, b, ai + 1, bi + 1);
            var d2 = averagedDifference(src, si + 2, a, b, ai + 2, bi + 2);
            var d3 = averagedDifference(src, si + 3, a, b, ai + 3, bi + 3);
            t4 = d0 + d1 + d2 + d3;
            t5 = d0 + d1 - d2 - d3;
            t6 = d0 - d1 - d2 + d3;
            t7 = d0 - d1 + d2 - d3;
        }
        {
            var si = srcOffset + 2 * srcStride;
            var ai = aOffset + 2 * stride;
            var bi = bOffset + 2 * stride;
            var d0 = averagedDifference(src, si, a, b, ai, bi);
            var d1 = averagedDifference(src, si + 1, a, b, ai + 1, bi + 1);
            var d2 = averagedDifference(src, si + 2, a, b, ai + 2, bi + 2);
            var d3 = averagedDifference(src, si + 3, a, b, ai + 3, bi + 3);
            t8 = d0 + d1 + d2 + d3;
            t9 = d0 + d1 - d2 - d3;
            t10 = d0 - d1 - d2 + d3;
            t11 = d0 - d1 + d2 - d3;
        }
        {
            var si = srcOffset + 3 * srcStride;
            var ai = aOffset + 3 * stride;
            var bi = bOffset + 3 * stride;
            var d0 = averagedDifference(src, si, a, b, ai, bi);
            var d1 = averagedDifference(src, si + 1, a, b, ai + 1, bi + 1);
            var d2 = averagedDifference(src, si + 2, a, b, ai + 2, bi + 2);
            var d3 = averagedDifference(src, si + 3, a, b, ai + 3, bi + 3);
            t12 = d0 + d1 + d2 + d3;
            t13 = d0 + d1 - d2 - d3;
            t14 = d0 - d1 - d2 + d3;
            t15 = d0 - d1 + d2 - d3;
        }
        return column(t0, t4, t8, t12) + column(t1, t5, t9, t13) + column(t2, t6, t10, t14) + column(t3, t7, t11, t15);
    }

    /**
     * Returns the difference of a sample and the rounded-up average of two others.
     *
     * @param src the samples of the first
     * @param srcOffset the index of the first
     * @param a the samples of the first averaged one
     * @param b the samples of the second averaged one
     * @param aOffset the index of the first averaged one
     * @param bOffset the index of the second averaged one
     * @return {@code src - ((a + b + 1) >> 1)}
     */
    private static int averagedDifference(byte[] src, int srcOffset, byte[] a, byte[] b, int aOffset, int bOffset) {
        return (src[srcOffset] & 0xFF) - (((a[aOffset] & 0xFF) + (b[bOffset] & 0xFF) + 1) >> 1);
    }

    /**
     * Returns the sum of absolute 4x4 Hadamard coefficients of the difference of two 4x4 blocks.
     *
     * @param a the first block's samples
     * @param aOffset the index of the first block's top-left sample
     * @param aStride the first block's row stride
     * @param b the second block's samples
     * @param bOffset the index of the second block's top-left sample
     * @param bStride the second block's row stride
     * @return the unhalved SATD of the 4x4 block
     */
    static int satd4x4(byte[] a, int aOffset, int aStride, byte[] b, int bOffset, int bStride) {
        int t0, t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13, t14, t15;
        {
            var d0 = (a[aOffset] & 0xFF) - (b[bOffset] & 0xFF);
            var d1 = (a[aOffset + 1] & 0xFF) - (b[bOffset + 1] & 0xFF);
            var d2 = (a[aOffset + 2] & 0xFF) - (b[bOffset + 2] & 0xFF);
            var d3 = (a[aOffset + 3] & 0xFF) - (b[bOffset + 3] & 0xFF);
            var s01 = d0 + d1;
            var d01 = d0 - d1;
            var s23 = d2 + d3;
            var d23 = d2 - d3;
            t0 = s01 + s23;
            t1 = s01 - s23;
            t2 = d01 - d23;
            t3 = d01 + d23;
        }
        {
            var ai = aOffset + aStride;
            var bi = bOffset + bStride;
            var d0 = (a[ai] & 0xFF) - (b[bi] & 0xFF);
            var d1 = (a[ai + 1] & 0xFF) - (b[bi + 1] & 0xFF);
            var d2 = (a[ai + 2] & 0xFF) - (b[bi + 2] & 0xFF);
            var d3 = (a[ai + 3] & 0xFF) - (b[bi + 3] & 0xFF);
            var s01 = d0 + d1;
            var d01 = d0 - d1;
            var s23 = d2 + d3;
            var d23 = d2 - d3;
            t4 = s01 + s23;
            t5 = s01 - s23;
            t6 = d01 - d23;
            t7 = d01 + d23;
        }
        {
            var ai = aOffset + 2 * aStride;
            var bi = bOffset + 2 * bStride;
            var d0 = (a[ai] & 0xFF) - (b[bi] & 0xFF);
            var d1 = (a[ai + 1] & 0xFF) - (b[bi + 1] & 0xFF);
            var d2 = (a[ai + 2] & 0xFF) - (b[bi + 2] & 0xFF);
            var d3 = (a[ai + 3] & 0xFF) - (b[bi + 3] & 0xFF);
            var s01 = d0 + d1;
            var d01 = d0 - d1;
            var s23 = d2 + d3;
            var d23 = d2 - d3;
            t8 = s01 + s23;
            t9 = s01 - s23;
            t10 = d01 - d23;
            t11 = d01 + d23;
        }
        {
            var ai = aOffset + 3 * aStride;
            var bi = bOffset + 3 * bStride;
            var d0 = (a[ai] & 0xFF) - (b[bi] & 0xFF);
            var d1 = (a[ai + 1] & 0xFF) - (b[bi + 1] & 0xFF);
            var d2 = (a[ai + 2] & 0xFF) - (b[bi + 2] & 0xFF);
            var d3 = (a[ai + 3] & 0xFF) - (b[bi + 3] & 0xFF);
            var s01 = d0 + d1;
            var d01 = d0 - d1;
            var s23 = d2 + d3;
            var d23 = d2 - d3;
            t12 = s01 + s23;
            t13 = s01 - s23;
            t14 = d01 - d23;
            t15 = d01 + d23;
        }
        return column(t0, t4, t8, t12) + column(t1, t5, t9, t13) + column(t2, t6, t10, t14) + column(t3, t7, t11, t15);
    }

    /**
     * Computes the 4x4 Hadamard transform of a block of samples, with the butterflies of
     * {@link #satd4x4}: {@code out[v * 4 + u]} holds the coefficient of vertical frequency index
     * {@code v} and horizontal frequency index {@code u}, both in the order
     * {@code (a + b + c + d, a + b - c - d, a - b - c + d, a - b + c - d)}.
     *
     * <p>Since the transform is linear, the unhalved SATD of the block against a prediction is
     * the sum of the absolute differences of their coefficients.
     *
     * @param src the samples
     * @param offset the index of the block's top-left sample
     * @param stride the row stride
     * @param out receives the 16 coefficients
     */
    static void hadamard4x4(byte[] src, int offset, int stride, int[] out) {
        for (var row = 0; row < 4; row++) {
            var i = offset + row * stride;
            var d0 = src[i] & 0xFF;
            var d1 = src[i + 1] & 0xFF;
            var d2 = src[i + 2] & 0xFF;
            var d3 = src[i + 3] & 0xFF;
            var s01 = d0 + d1;
            var d01 = d0 - d1;
            var s23 = d2 + d3;
            var d23 = d2 - d3;
            out[row * 4] = s01 + s23;
            out[row * 4 + 1] = s01 - s23;
            out[row * 4 + 2] = d01 - d23;
            out[row * 4 + 3] = d01 + d23;
        }
        for (var u = 0; u < 4; u++) {
            var c0 = out[u];
            var c1 = out[4 + u];
            var c2 = out[8 + u];
            var c3 = out[12 + u];
            var s01 = c0 + c1;
            var d01 = c0 - c1;
            var s23 = c2 + c3;
            var d23 = c2 - c3;
            out[u] = s01 + s23;
            out[4 + u] = s01 - s23;
            out[8 + u] = d01 - d23;
            out[12 + u] = d01 + d23;
        }
    }

    /**
     * Returns the sum of absolute values of the 4-point Hadamard transform of a column.
     *
     * @param c0 the first element
     * @param c1 the second element
     * @param c2 the third element
     * @param c3 the fourth element
     * @return the absolute sum of the transformed column
     */
    private static int column(int c0, int c1, int c2, int c3) {
        var s01 = c0 + c1;
        var d01 = c0 - c1;
        var s23 = c2 + c3;
        var d23 = c2 - c3;
        return Math.abs(s01 + s23) + Math.abs(s01 - s23) + Math.abs(d01 - d23) + Math.abs(d01 + d23);
    }

    /**
     * Returns the smallest SATD of an 8x8 block against its DC, vertical and horizontal intra
     * predictions.
     *
     * <p>The DC prediction is the constant {@code dc}; the vertical prediction repeats the 8
     * samples above the block and the horizontal one the sample left of each row. The vertical
     * and horizontal predictions are only considered when their neighbors exist.
     *
     * @param src the samples, with the neighbors of the block
     * @param offset the index of the block's top-left sample
     * @param stride the row stride
     * @param dc the DC prediction value
     * @param top whether the samples above the block exist
     * @param left whether the samples left of the block exist
     * @return the smallest SATD
     */
    int intraSatd8x8(byte[] src, int offset, int stride, int dc, boolean top, boolean left) {
        var prediction = new byte[64];
        Arrays.fill(prediction, (byte) dc);
        var best = satd(src, offset, stride, prediction, 0, 8, 8, 8);
        if (top) {
            for (var y = 0; y < 8; y++) {
                System.arraycopy(src, offset - stride, prediction, y * 8, 8);
            }
            best = Math.min(best, satd(src, offset, stride, prediction, 0, 8, 8, 8));
        }
        if (left) {
            for (var y = 0; y < 8; y++) {
                Arrays.fill(prediction, y * 8, y * 8 + 8, src[offset + y * stride - 1]);
            }
            best = Math.min(best, satd(src, offset, stride, prediction, 0, 8, 8, 8));
        }
        return best;
    }

    /**
     * Downscales rows of a plane by two in both directions, each output sample the rounded
     * average {@code (a + b + c + d + 2) >> 2} of a 2x2 input block.
     *
     * @param src the input samples
     * @param srcOffset the index of the first input row's first sample
     * @param srcStride the input row stride
     * @param dst the output samples
     * @param dstOffset the index of the first output row's first sample
     * @param dstStride the output row stride
     * @param width the output width
     * @param rows the number of output rows
     */
    void downscale(byte[] src, int srcOffset, int srcStride, byte[] dst, int dstOffset, int dstStride, int width,
                   int rows) {
        for (var y = 0; y < rows; y++) {
            var s0 = srcOffset + 2 * y * srcStride;
            var s1 = s0 + srcStride;
            var d = dstOffset + y * dstStride;
            for (var x = 0; x < width; x++) {
                dst[d + x] = (byte) (((src[s0 + 2 * x] & 0xFF) + (src[s0 + 2 * x + 1] & 0xFF)
                        + (src[s1 + 2 * x] & 0xFF) + (src[s1 + 2 * x + 1] & 0xFF) + 2) >> 2);
            }
        }
    }

    /**
     * Writes the rounded-up average of two blocks, {@code (a + b + 1) >> 1}.
     *
     * @param a the first block's samples
     * @param aOffset the index of the first block's top-left sample
     * @param aStride the first block's row stride
     * @param b the second block's samples
     * @param bOffset the index of the second block's top-left sample
     * @param bStride the second block's row stride
     * @param dst the destination samples
     * @param dstOffset the index of the destination's top-left sample
     * @param dstStride the destination row stride
     * @param width the block width, any positive value
     * @param height the block height, any positive value
     */
    void average(byte[] a, int aOffset, int aStride, byte[] b, int bOffset, int bStride,
                 byte[] dst, int dstOffset, int dstStride, int width, int height) {
        for (var y = 0; y < height; y++) {
            var ai = aOffset + y * aStride;
            var bi = bOffset + y * bStride;
            var di = dstOffset + y * dstStride;
            for (var x = 0; x < width; x++) {
                dst[di + x] = (byte) (((a[ai + x] & 0xFF) + (b[bi + x] & 0xFF) + 1) >> 1);
            }
        }
    }

    /**
     * Copies a block.
     *
     * @param src the source samples
     * @param srcOffset the index of the source's top-left sample
     * @param srcStride the source row stride
     * @param dst the destination samples
     * @param dstOffset the index of the destination's top-left sample
     * @param dstStride the destination row stride
     * @param width the block width, any positive value
     * @param height the block height, any positive value
     */
    void copy(byte[] src, int srcOffset, int srcStride, byte[] dst, int dstOffset, int dstStride, int width, int height) {
        for (var y = 0; y < height; y++) {
            System.arraycopy(src, srcOffset + y * srcStride, dst, dstOffset + y * dstStride, width);
        }
    }

    /**
     * Returns the sum and the sum of squares of a block's samples, packed as
     * {@code sum | (sumOfSquares << 32)}.
     *
     * @param src the samples
     * @param offset the index of the top-left sample
     * @param stride the row stride
     * @param width the block width, at most 16
     * @param height the block height, at most 16
     * @return the packed sums
     */
    long sumAndSquares(byte[] src, int offset, int stride, int width, int height) {
        var sum = 0;
        long squares = 0;
        for (var y = 0; y < height; y++) {
            var i = offset + y * stride;
            for (var x = 0; x < width; x++) {
                var v = src[i + x] & 0xFF;
                sum += v;
                squares += v * v;
            }
        }
        return sum | (squares << 32);
    }

    /**
     * Writes a plane prediction {@code Clip1((a + b * (x - center) + c * (y - center) + 16) >> 5)}.
     *
     * <p>The parameters are those clause 8.3.3.4 (16x16 luma, {@code center} 7) and clause
     * 8.3.4.4 (chroma, {@code center} 3) derive from the neighboring samples, so every
     * intermediate value fits in 16 bits.
     *
     * @param dst the destination samples
     * @param offset the index of the top-left sample
     * @param stride the row stride
     * @param size the block size, 8 or 16
     * @param a the plane offset
     * @param b the horizontal gradient
     * @param c the vertical gradient
     * @param center the coordinate at which the gradients are anchored, 3 or 7
     */
    void planePrediction(byte[] dst, int offset, int stride, int size, int a, int b, int c, int center) {
        for (var y = 0; y < size; y++) {
            var base = a + c * (y - center) - b * center + 16;
            var d = offset + y * stride;
            for (var x = 0; x < size; x++) {
                var value = (base + b * x) >> 5;
                dst[d + x] = (byte) (value < 0 ? 0 : Math.min(value, 255));
            }
        }
    }
}
