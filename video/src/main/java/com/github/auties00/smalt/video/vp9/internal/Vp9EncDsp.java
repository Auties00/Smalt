package com.github.auties00.smalt.video.vp9.internal;

import com.github.auties00.smalt.internal.simd.Simd;

/**
 * Implements the pixel metrics of the VP9 encoder's mode decision and motion search: sums of
 * absolute differences, variances, sub-pixel variances, residuals, Hadamard and forward cosine
 * transforms and block averages.
 *
 * <p>Blocks are {@code byte[]} planes addressed with an offset and a row stride; widths are 4,
 * 8, 16, 32 or 64 and heights 4 to 64. Variances return their sum of squared differences and sum
 * of differences packed into one {@code long} (see {@link #sse(long)} and {@link #sum(long)}).
 * {@link #INSTANCE} holds the implementation selected for this JVM; every implementation returns
 * identical results.
 *
 * @implNote This implementation follows the C kernels of libvpx's {@code vpx_dsp/sad.c},
 *           {@code variance.c}, {@code subtract.c} and {@code avg.c}, BSD-3.
 */
abstract sealed class Vp9EncDsp permits ScalarVp9EncDsp, VectorVp9EncDsp {
    /**
     * The implementation selected by {@link Simd#ENABLED}.
     */
    static final Vp9EncDsp INSTANCE = Simd.ENABLED ? new VectorVp9EncDsp() : new ScalarVp9EncDsp();

    /**
     * The taps of the bilinear filters of the sub-pixel variance, two per eighth-pixel phase
     * ({@code bilinear_filters} of {@code vpx_dsp/variance.c}).
     */
    static final int[] BILINEAR_TAPS = {128, 0, 112, 16, 96, 32, 80, 48, 64, 64, 48, 80, 32, 96, 16, 112};

    /**
     * The minimum size of the scratch array of {@link #subPixelVariance}.
     */
    static final int SUBPEL_SCRATCH = 65 * 64 + 64 * 64;

    /**
     * The minimum size of the {@code int} scratch array of {@link #fdct16x16} and
     * {@link #fdct32x32}.
     */
    static final int FDCT_SCRATCH = 32 * 32;

    /**
     * Creates an implementation.
     */
    Vp9EncDsp() {
    }

    /**
     * Returns the sum of squared differences of a packed variance.
     *
     * @param packed the packed variance
     * @return the sum of squared differences, at most {@code 64 * 64 * 255 * 255}
     */
    static long sse(long packed) {
        return packed >>> 32;
    }

    /**
     * Returns the sum of differences of a packed variance.
     *
     * @param packed the packed variance
     * @return the sum of differences
     */
    static int sum(long packed) {
        return (int) packed;
    }

    /**
     * Packs a sum of squared differences and a sum of differences.
     *
     * @param sse the sum of squared differences
     * @param sum the sum of differences
     * @return the packed value
     */
    static long pack(long sse, int sum) {
        return (sse << 32) | (sum & 0xFFFFFFFFL);
    }

    /**
     * Returns the variance of a packed result over a block: the sum of squared differences minus
     * the squared sum over the number of samples ({@code vpx_variance*}).
     *
     * @param packed the packed result
     * @param log2N  the log2 of the number of samples
     * @return the variance
     */
    static long variance(long packed, int log2N) {
        long s = sum(packed);
        return sse(packed) - ((s * s) >> log2N);
    }

    /**
     * Returns the sum of absolute differences of two blocks ({@code vpx_sad*}).
     *
     * @param src       the first block's plane
     * @param srcOff    the index of its top-left sample
     * @param srcStride its row stride
     * @param ref       the second block's plane
     * @param refOff    the index of its top-left sample
     * @param refStride its row stride
     * @param w         the width
     * @param h         the height
     * @return the sum
     */
    abstract int sad(byte[] src, int srcOff, int srcStride, byte[] ref, int refOff, int refStride, int w, int h);

    /**
     * Returns the sums of absolute differences of a block with four candidates of the same plane
     * ({@code vpx_sad*x4d}).
     *
     * @param src       the block's plane
     * @param srcOff    the index of its top-left sample
     * @param srcStride its row stride
     * @param ref       the candidates' plane
     * @param off0      the index of the first candidate
     * @param off1      the index of the second candidate
     * @param off2      the index of the third candidate
     * @param off3      the index of the fourth candidate
     * @param refStride the candidates' row stride
     * @param w         the width
     * @param h         the height
     * @param out       receives the four sums
     */
    void sad4(byte[] src, int srcOff, int srcStride, byte[] ref, int off0, int off1, int off2, int off3,
              int refStride, int w, int h, int[] out) {
        out[0] = sad(src, srcOff, srcStride, ref, off0, refStride, w, h);
        out[1] = sad(src, srcOff, srcStride, ref, off1, refStride, w, h);
        out[2] = sad(src, srcOff, srcStride, ref, off2, refStride, w, h);
        out[3] = sad(src, srcOff, srcStride, ref, off3, refStride, w, h);
    }

    /**
     * Returns the sum of absolute differences of the even rows of two blocks, the downsampled
     * metric of the motion search ({@code vpx_sad_skip*}): twice the sum over every other row.
     *
     * @param src       the first block's plane
     * @param srcOff    the index of its top-left sample
     * @param srcStride its row stride
     * @param ref       the second block's plane
     * @param refOff    the index of its top-left sample
     * @param refStride its row stride
     * @param w         the width
     * @param h         the height
     * @return the sum
     */
    int sadSkip(byte[] src, int srcOff, int srcStride, byte[] ref, int refOff, int refStride, int w, int h) {
        return 2 * sad(src, srcOff, 2 * srcStride, ref, refOff, 2 * refStride, w, h >> 1);
    }

    /**
     * Returns the packed sums of squared differences and of differences of two blocks
     * ({@code variance} of {@code vpx_dsp/variance.c}).
     *
     * @param src       the first block's plane
     * @param srcOff    the index of its top-left sample
     * @param srcStride its row stride
     * @param ref       the second block's plane
     * @param refOff    the index of its top-left sample
     * @param refStride its row stride
     * @param w         the width
     * @param h         the height
     * @return the packed sums, differences taken as first minus second
     */
    abstract long variance(byte[] src, int srcOff, int srcStride, byte[] ref, int refOff, int refStride, int w, int h);

    /**
     * Returns the packed sums of a block of a reference filtered at an eighth-pixel position
     * with the bilinear filters, compared with a second block ({@code vpx_sub_pixel_variance*}).
     *
     * <p>The reference is filtered horizontally into {@code h + 1} rows, then vertically; a zero
     * phase leaves its direction unchanged. Differences are the filtered reference minus the
     * second block.
     *
     * @param ref       the reference plane
     * @param refOff    the index of the sample at the block's integer position
     * @param refStride the reference row stride
     * @param xOffset   the horizontal phase, from 0 to 7
     * @param yOffset   the vertical phase, from 0 to 7
     * @param src       the second block's plane
     * @param srcOff    the index of its top-left sample
     * @param srcStride its row stride
     * @param w         the width
     * @param h         the height
     * @param scratch   a scratch array of at least {@link #SUBPEL_SCRATCH} shorts
     * @return the packed sums
     */
    abstract long subPixelVariance(byte[] ref, int refOff, int refStride, int xOffset, int yOffset, byte[] src,
                                   int srcOff, int srcStride, int w, int h, short[] scratch);

    /**
     * Computes the residual of a block, source minus prediction ({@code vpx_subtract_block}).
     *
     * @param diff       receives the residuals
     * @param diffOff    the index of the first residual
     * @param diffStride the residual row stride
     * @param src        the source plane
     * @param srcOff     the index of the source block's top-left sample
     * @param srcStride  the source row stride
     * @param pred       the prediction plane
     * @param predOff    the index of the prediction's top-left sample
     * @param predStride the prediction row stride
     * @param w          the width
     * @param h          the height
     */
    abstract void subtract(short[] diff, int diffOff, int diffStride, byte[] src, int srcOff, int srcStride,
                           byte[] pred, int predOff, int predStride, int w, int h);

    /**
     * Computes the 8x8 discrete cosine transform of residuals ({@code vpx_fdct8x8_c}), as
     * {@link Vp9FwdTxfm#fdct8x8} does.
     *
     * <p>The residuals must be differences of 8-bit samples, from {@code -255} to {@code 255}.
     *
     * @param in     the residuals
     * @param inOff  the index of the first residual
     * @param stride the residual row stride
     * @param out    receives the 64 coefficients
     * @param outOff the index of the first coefficient
     * @param tmp    a scratch array of at least 128 entries
     */
    void fdct8x8(short[] in, int inOff, int stride, int[] out, int outOff, long[] tmp) {
        Vp9FwdTxfm.fdct8x8(in, inOff, stride, out, outOff, tmp);
    }

    /**
     * Computes the 4x4 discrete cosine transform of residuals ({@code vpx_fdct4x4_c}), as
     * {@link Vp9FwdTxfm#fdct4x4} does.
     *
     * <p>The residuals must be differences of 8-bit samples, from {@code -255} to {@code 255}.
     *
     * @param in     the residuals
     * @param inOff  the index of the first residual
     * @param stride the residual row stride
     * @param out    receives the 16 coefficients
     * @param outOff the index of the first coefficient
     * @param tmp    a scratch array of at least 32 entries
     */
    void fdct4x4(short[] in, int inOff, int stride, int[] out, int outOff, long[] tmp) {
        Vp9FwdTxfm.fdct4x4(in, inOff, stride, out, outOff, tmp);
    }

    /**
     * Computes the 16x16 discrete cosine transform of residuals ({@code vpx_fdct16x16_c}), as
     * {@link Vp9FwdTxfm#fdct16x16} does.
     *
     * <p>The residuals must be differences of 8-bit samples, from {@code -255} to {@code 255}.
     *
     * @param in      the residuals
     * @param inOff   the index of the first residual
     * @param stride  the residual row stride
     * @param out     receives the 256 coefficients
     * @param outOff  the index of the first coefficient
     * @param tmp     a scratch array of at least 288 entries
     * @param scratch a scratch array of at least {@link #FDCT_SCRATCH} entries
     */
    void fdct16x16(short[] in, int inOff, int stride, int[] out, int outOff, long[] tmp, int[] scratch) {
        Vp9FwdTxfm.fdct16x16(in, inOff, stride, out, outOff, tmp);
    }

    /**
     * Computes the 32x32 discrete cosine transform of residuals ({@code vpx_fdct32x32_c}), or its
     * lower precision variant for the rate-distortion loop ({@code vpx_fdct32x32_rd_c}), as
     * {@link Vp9FwdTxfm#fdct32x32} does.
     *
     * <p>The residuals must be differences of 8-bit samples, from {@code -255} to {@code 255}.
     *
     * @param in        the residuals
     * @param inOff     the index of the first residual
     * @param stride    the residual row stride
     * @param out       receives the 1024 coefficients
     * @param outOff    the index of the first coefficient
     * @param rdVariant whether to compute the lower precision variant
     * @param tmp       a scratch array of at least 1024 + 96 entries
     * @param scratch   a scratch array of at least {@link #FDCT_SCRATCH} entries
     */
    void fdct32x32(short[] in, int inOff, int stride, int[] out, int outOff, boolean rdVariant, long[] tmp,
                   int[] scratch) {
        Vp9FwdTxfm.fdct32x32(in, inOff, stride, out, outOff, rdVariant, tmp);
    }

    /**
     * Computes the 8x8 Hadamard transform of residuals ({@code vpx_hadamard_8x8_c}).
     *
     * @param diff    the residuals
     * @param diffOff the index of the first residual
     * @param stride  the residual row stride
     * @param coeff   receives the 64 coefficients
     * @param outOff  the index of the first coefficient
     * @param buffer  a scratch array of at least 64 shorts
     */
    void hadamard8x8(short[] diff, int diffOff, int stride, int[] coeff, int outOff, short[] buffer) {
        for (var idx = 0; idx < 8; idx++) {
            hadamardCol8(diff, diffOff + idx, stride, buffer, idx * 8);
        }
        for (var idx = 0; idx < 8; idx++) {
            hadamardColInt(buffer, idx, coeff, outOff + 8 * idx);
        }
    }

    /**
     * Computes one 8-point Hadamard column of the first pass ({@code hadamard_col8}), with the
     * 16-bit wrap-around of libvpx's intermediates.
     *
     * @param src    the residuals
     * @param off    the index of the column's first sample
     * @param stride the residual row stride
     * @param out    receives the 8 outputs
     * @param outOff the index of the first output
     */
    private static void hadamardCol8(short[] src, int off, int stride, short[] out, int outOff) {
        var b0 = (short) (src[off] + src[off + stride]);
        var b1 = (short) (src[off] - src[off + stride]);
        var b2 = (short) (src[off + 2 * stride] + src[off + 3 * stride]);
        var b3 = (short) (src[off + 2 * stride] - src[off + 3 * stride]);
        var b4 = (short) (src[off + 4 * stride] + src[off + 5 * stride]);
        var b5 = (short) (src[off + 4 * stride] - src[off + 5 * stride]);
        var b6 = (short) (src[off + 6 * stride] + src[off + 7 * stride]);
        var b7 = (short) (src[off + 6 * stride] - src[off + 7 * stride]);
        var c0 = (short) (b0 + b2);
        var c1 = (short) (b1 + b3);
        var c2 = (short) (b0 - b2);
        var c3 = (short) (b1 - b3);
        var c4 = (short) (b4 + b6);
        var c5 = (short) (b5 + b7);
        var c6 = (short) (b4 - b6);
        var c7 = (short) (b5 - b7);
        out[outOff] = (short) (c0 + c4);
        out[outOff + 7] = (short) (c1 + c5);
        out[outOff + 3] = (short) (c2 + c6);
        out[outOff + 4] = (short) (c3 + c7);
        out[outOff + 2] = (short) (c0 - c4);
        out[outOff + 6] = (short) (c1 - c5);
        out[outOff + 1] = (short) (c2 - c6);
        out[outOff + 5] = (short) (c3 - c7);
    }

    /**
     * Computes one 8-point Hadamard column of the second pass, reading the first pass's outputs
     * 8 apart.
     *
     * @param src    the first pass's outputs
     * @param off    the index of the column's first value
     * @param out    receives the 8 outputs
     * @param outOff the index of the first output
     */
    private static void hadamardColInt(short[] src, int off, int[] out, int outOff) {
        var b0 = (short) (src[off] + src[off + 8]);
        var b1 = (short) (src[off] - src[off + 8]);
        var b2 = (short) (src[off + 16] + src[off + 24]);
        var b3 = (short) (src[off + 16] - src[off + 24]);
        var b4 = (short) (src[off + 32] + src[off + 40]);
        var b5 = (short) (src[off + 32] - src[off + 40]);
        var b6 = (short) (src[off + 48] + src[off + 56]);
        var b7 = (short) (src[off + 48] - src[off + 56]);
        var c0 = (short) (b0 + b2);
        var c1 = (short) (b1 + b3);
        var c2 = (short) (b0 - b2);
        var c3 = (short) (b1 - b3);
        var c4 = (short) (b4 + b6);
        var c5 = (short) (b5 + b7);
        var c6 = (short) (b4 - b6);
        var c7 = (short) (b5 - b7);
        out[outOff] = (short) (c0 + c4);
        out[outOff + 7] = (short) (c1 + c5);
        out[outOff + 3] = (short) (c2 + c6);
        out[outOff + 4] = (short) (c3 + c7);
        out[outOff + 2] = (short) (c0 - c4);
        out[outOff + 6] = (short) (c1 - c5);
        out[outOff + 1] = (short) (c2 - c6);
        out[outOff + 5] = (short) (c3 - c7);
    }

    /**
     * Computes the 16x16 Hadamard transform of residuals ({@code vpx_hadamard_16x16_c}).
     *
     * @param diff    the residuals
     * @param diffOff the index of the first residual
     * @param stride  the residual row stride
     * @param coeff   receives the 256 coefficients
     * @param outOff  the index of the first coefficient
     * @param buffer  a scratch array of at least 64 shorts
     */
    void hadamard16x16(short[] diff, int diffOff, int stride, int[] coeff, int outOff, short[] buffer) {
        for (var idx = 0; idx < 4; idx++) {
            var off = diffOff + (idx >> 1) * 8 * stride + (idx & 1) * 8;
            hadamard8x8(diff, off, stride, coeff, outOff + idx * 64, buffer);
        }
        for (var idx = 0; idx < 64; idx++) {
            var p = outOff + idx;
            var a0 = coeff[p];
            var a1 = coeff[p + 64];
            var a2 = coeff[p + 128];
            var a3 = coeff[p + 192];
            var b0 = (a0 + a1) >> 1;
            var b1 = (a0 - a1) >> 1;
            var b2 = (a2 + a3) >> 1;
            var b3 = (a2 - a3) >> 1;
            coeff[p] = b0 + b2;
            coeff[p + 64] = b1 + b3;
            coeff[p + 128] = b0 - b2;
            coeff[p + 192] = b1 - b3;
        }
    }

    /**
     * Quantizes a block with the fast quantizer of real-time inter coding, as
     * {@link Vp9Quantizer#quantizeFp} does.
     *
     * @param coeff   the coefficients in raster order
     * @param off     the index of the block's first coefficient in all three arrays
     * @param n       the number of coefficients
     * @param qcoeff  receives the quantized coefficients
     * @param dqcoeff receives the dequantized coefficients
     * @param scan    the scan order
     * @param iscan   the position plus one of each raster coefficient in the scan
     * @param round   the rounding offsets
     * @param quant   the multipliers
     * @param dequant the steps
     * @param pidx    the index of the DC parameters
     * @param is32x32 whether the block is 32x32
     * @return the end of block
     */
    int quantizeFp(int[] coeff, int off, int n, int[] qcoeff, int[] dqcoeff, short[] scan, int[] iscan, int[] round,
                   int[] quant, int[] dequant, int pidx, boolean is32x32) {
        return Vp9Quantizer.quantizeFp(coeff, off, n, qcoeff, dqcoeff, scan, round, quant, dequant, pidx, is32x32);
    }

    /**
     * Quantizes a block of at most 16x16 coefficients with the fast quantizer and measures the
     * result, as the transform-domain rate estimate of the mode decision does: the sum of the
     * quantized magnitudes ({@code vpx_satd}) and the squared error of the dequantized
     * coefficients ({@code vp9_block_error_fp}).
     *
     * <p>The quantized and dequantized coefficients go to {@code qcoeff} and {@code dqcoeff},
     * which an implementation may use as scratch space and leave with any content. The
     * coefficients must lie in {@code [-32768, 32767]}, as the Hadamard and 4x4 transforms of
     * 8-bit residuals do, and {@code n} must be at most 256.
     *
     * @param coeff   the coefficients in raster order
     * @param off     the index of the block's first coefficient in all three arrays
     * @param n       the number of coefficients
     * @param qcoeff  the scratch space of the quantized coefficients
     * @param dqcoeff the scratch space of the dequantized coefficients
     * @param scan    the scan order
     * @param iscan   the position plus one of each raster coefficient in the scan
     * @param round   the rounding offsets
     * @param quant   the multipliers
     * @param dequant the steps
     * @param pidx    the index of the DC parameters
     * @param result  receives the sum of magnitudes at index 0 and the squared error at index 1
     * @return the end of block
     */
    int quantizeFpRd(int[] coeff, int off, int n, int[] qcoeff, int[] dqcoeff, short[] scan, int[] iscan,
                     int[] round, int[] quant, int[] dequant, int pidx, long[] result) {
        var eob = quantizeFp(coeff, off, n, qcoeff, dqcoeff, scan, iscan, round, quant, dequant, pidx, false);
        result[0] = satd(qcoeff, off, n);
        result[1] = blockErrorFp(coeff, dqcoeff, off, n);
        return eob;
    }

    /**
     * Returns the sum of absolute values of coefficients ({@code vpx_satd_c}).
     *
     * @param coeff the coefficients
     * @param off   the index of the first coefficient
     * @param n     the number of coefficients
     * @return the sum
     */
    int satd(int[] coeff, int off, int n) {
        var satd = 0;
        for (var i = 0; i < n; i++) {
            satd += Math.abs(coeff[off + i]);
        }
        return satd;
    }

    /**
     * Returns the sum of squared differences of coefficients and their dequantized values
     * ({@code vp9_block_error_fp_c}).
     *
     * @param coeff   the coefficients
     * @param dqcoeff the dequantized coefficients
     * @param off     the index of the first coefficient of both arrays
     * @param n       the number of coefficients
     * @return the sum
     */
    long blockErrorFp(int[] coeff, int[] dqcoeff, int off, int n) {
        long error = 0;
        for (var i = 0; i < n; i++) {
            long diff = coeff[off + i] - dqcoeff[off + i];
            error += diff * diff;
        }
        return error;
    }

    /**
     * Returns the rounded average of an 8x8 block ({@code vpx_avg_8x8_c}).
     *
     * @param s      the plane
     * @param off    the index of the top-left sample
     * @param stride the row stride
     * @return the average
     */
    int avg8x8(byte[] s, int off, int stride) {
        var sum = 0;
        for (var i = 0; i < 8; i++, off += stride) {
            for (var j = 0; j < 8; j++) {
                sum += s[off + j] & 0xFF;
            }
        }
        return (sum + 32) >> 6;
    }

    /**
     * Computes the rounded averages of the four 8x8 blocks of a 16x16 block, as {@link #avg8x8}
     * computes each.
     *
     * @param s      the plane
     * @param off    the index of the 16x16 block's top-left sample
     * @param stride the row stride
     * @param out    receives the averages of the top-left, top-right, bottom-left and bottom-right
     *               blocks
     */
    void avg8x8Quad(byte[] s, int off, int stride, int[] out) {
        out[0] = avg8x8(s, off, stride);
        out[1] = avg8x8(s, off + 8, stride);
        out[2] = avg8x8(s, off + 8 * stride, stride);
        out[3] = avg8x8(s, off + 8 * stride + 8, stride);
    }

    /**
     * Returns the rounded average of a 4x4 block ({@code vpx_avg_4x4_c}).
     *
     * @param s      the plane
     * @param off    the index of the top-left sample
     * @param stride the row stride
     * @return the average
     */
    int avg4x4(byte[] s, int off, int stride) {
        var sum = 0;
        for (var i = 0; i < 4; i++, off += stride) {
            for (var j = 0; j < 4; j++) {
                sum += s[off + j] & 0xFF;
            }
        }
        return (sum + 8) >> 4;
    }

    /**
     * Returns the smallest and largest absolute difference of two 8x8 blocks
     * ({@code vpx_minmax_8x8_c}).
     *
     * @param s       the first plane
     * @param sOff    the index of the first block
     * @param sStride the first row stride
     * @param d       the second plane
     * @param dOff    the index of the second block
     * @param dStride the second row stride
     * @return the minimum in the low 16 bits and the maximum in the high 16 bits
     */
    int minmax8x8(byte[] s, int sOff, int sStride, byte[] d, int dOff, int dStride) {
        var min = 255;
        var max = 0;
        for (var i = 0; i < 8; i++, sOff += sStride, dOff += dStride) {
            for (var j = 0; j < 8; j++) {
                var diff = Math.abs((s[sOff + j] & 0xFF) - (d[dOff + j] & 0xFF));
                min = Math.min(min, diff);
                max = Math.max(max, diff);
            }
        }
        return min | (max << 16);
    }

    /**
     * Projects 16 columns of a block onto a row: each output is the column sum divided by half
     * the height ({@code vpx_int_pro_row_c}).
     *
     * @param hbuf   receives the 16 projections
     * @param hOff   the index of the first projection
     * @param ref    the plane
     * @param refOff the index of the first column's top sample
     * @param stride the row stride
     * @param height the number of rows, 16, 32 or 64
     */
    void intProRow(short[] hbuf, int hOff, byte[] ref, int refOff, int stride, int height) {
        var norm = height >> 1;
        for (var idx = 0; idx < 16; idx++) {
            var sum = 0;
            for (var i = 0; i < height; i++) {
                sum += ref[refOff + i * stride + idx] & 0xFF;
            }
            hbuf[hOff + idx] = (short) ((short) sum / norm);
        }
    }

    /**
     * Returns the sum of a row of samples ({@code vpx_int_pro_col_c}).
     *
     * @param ref   the plane
     * @param off   the index of the first sample
     * @param width the number of samples, 16, 32 or 64
     * @return the sum
     */
    int intProCol(byte[] ref, int off, int width) {
        var sum = 0;
        for (var i = 0; i < width; i++) {
            sum += ref[off + i] & 0xFF;
        }
        return (short) sum;
    }

    /**
     * Returns the variance of the difference of two projections ({@code vpx_vector_var_c}).
     *
     * @param ref    the first projection
     * @param refOff the index of its first value
     * @param src    the second projection
     * @param srcOff the index of its first value
     * @param bwl    the log2 of the length divided by 4
     * @return the variance
     */
    int vectorVar(short[] ref, int refOff, short[] src, int srcOff, int bwl) {
        var width = 4 << bwl;
        var sse = 0;
        var mean = 0;
        for (var i = 0; i < width; i++) {
            var diff = ref[refOff + i] - src[srcOff + i];
            mean += diff;
            sse += diff * diff;
        }
        return sse - ((mean * mean) >> (bwl + 2));
    }
}
