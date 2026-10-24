package com.github.auties00.smalt.video.av1.internal.encoder;

import com.github.auties00.smalt.internal.simd.Simd;

/**
 * Computes the pixel statistics the AV1 encoder's analysis needs: sums of absolute differences,
 * variances, sub-sample variances, block averages, Hadamard transforms and their quantization
 * estimates.
 *
 * <p>Samples are held in {@code short} planes. The 8-bit kernels have a bit-identical scalar
 * implementation, {@link ScalarEncDsp}, and a Vector API implementation, {@link VectorEncDsp};
 * 10-bit and 12-bit samples take {@link HighBitDepthEncDsp} ({@link #select(int)}).
 *
 * @implNote This implementation follows libaom's {@code aom_dsp} C references ({@code sad.c},
 * {@code variance.c}, {@code avg.c}) and {@code av1_quantize_lp_c} ({@code av1/encoder/av1_quantize.c}).
 */
abstract class EncDsp {
    /**
     * The bilinear filter taps of each eighth-sample position ({@code bilinear_filters_2t}).
     */
    static final int[] BILINEAR = {128, 0, 112, 16, 96, 32, 80, 48, 64, 64, 48, 80, 32, 96, 16, 112};

    /**
     * The scalar kernels.
     */
    static final EncDsp SCALAR = new ScalarEncDsp();

    /**
     * The kernels every frame is analyzed with: {@link VectorEncDsp}, written for 256-bit vectors,
     * when {@link Simd#WIDE}, {@link #SCALAR} otherwise.
     */
    private static final EncDsp FIXED = Simd.WIDE ? new VectorEncDsp() : SCALAR;

    /**
     * Creates a kernel set.
     */
    EncDsp() {
    }

    /**
     * Returns the kernels a frame is analyzed with.
     *
     * <p>This is the Vector API set when {@link Simd#ENABLED} and the scalar set when not. Every
     * set returns the same values, so the stream never depends on the selection.
     *
     * @return the kernels
     */
    static EncDsp select() {
        return FIXED;
    }

    /**
     * The kernels of 10-bit samples.
     */
    private static final EncDsp TEN_BIT = new HighBitDepthEncDsp(10);

    /**
     * The kernels of 12-bit samples.
     */
    private static final EncDsp TWELVE_BIT = new HighBitDepthEncDsp(12);

    /**
     * Returns the kernels a frame of a bit depth is analyzed with: those of {@link #select()} at
     * 8 bits, a {@link HighBitDepthEncDsp}, whose measurements are at the 8-bit scale, above.
     *
     * @param bitDepth the bit depth, 8, 10 or 12
     * @return the kernels
     */
    static EncDsp select(int bitDepth) {
        return bitDepth == 8 ? FIXED : bitDepth == 10 ? TEN_BIT : TWELVE_BIT;
    }

    /**
     * Replaces samples with the rounded average of themselves and as many others, as a compound
     * prediction averages two predictions:
     * {@code a[aOff + i] = (a[aOff + i] + b[bOff + i] + 1) >> 1} for every {@code i} below
     * {@code n}.
     *
     * @param a the samples to replace
     * @param aOff the index of the first sample to replace
     * @param b the samples to average with
     * @param bOff the index of the first sample to average with
     * @param n the number of samples
     */
    void average(short[] a, int aOff, short[] b, int bOff, int n) {
        for (var i = 0; i < n; i++) {
            a[aOff + i] = (short) ((a[aOff + i] + b[bOff + i] + 1) >> 1);
        }
    }

    /**
     * Returns the sum of absolute differences of two blocks.
     *
     * @param a the first plane
     * @param aOff the offset of the first block
     * @param aStride the first plane's stride
     * @param b the second plane
     * @param bOff the offset of the second block
     * @param bStride the second plane's stride
     * @param w the block width
     * @param h the block height
     * @return the sum
     */
    abstract int sad(short[] a, int aOff, int aStride, short[] b, int bOff, int bStride, int w, int h);

    /**
     * Returns the squared error, in the transform domain, between the coefficients of a block and
     * the dequantization of their levels: a level of magnitude {@code m} dequantizes to
     * {@code (m * dequantizer) >> logScale} with the level's sign, the DC coefficient (raster
     * index 0) with the DC dequantizer and every other coefficient with the AC one.
     *
     * @param coeff the transform coefficients, in the decoder's layout
     * @param levels the signed levels, in the decoder's layout
     * @param n the number of coefficients, a multiple of 8
     * @param dcDq the DC dequantizer
     * @param acDq the AC dequantizer
     * @param logScale the transform's dequantization shift
     * @return the squared error
     */
    abstract long levelError(int[] coeff, int[] levels, int n, int dcDq, int acDq, int logScale);

    /**
     * Quantizes the coefficients of one transform block with the dead-zone rule, the DC
     * coefficient (raster index 0) with its own parameters and every other coefficient with the
     * AC ones, and returns the end of block.
     *
     * <p>A coefficient whose magnitude is at least its dead-zone threshold has the level
     * {@code (((min(magnitude + round, limit) * quant) >> 16) + min(...)) >> shift}, with the sign of
     * the coefficient; any other coefficient has level zero. All {@code n} levels are written.
     *
     * @param coeff the transform coefficients, in the decoder's layout
     * @param n the number of coded coefficients, a multiple of 8
     * @param iscan the inverse scan of the block: the scan position plus one of each coefficient, by
     * raster index
     * @param qcoeff receives the signed levels
     * @param zbinDc the DC dead-zone threshold
     * @param zbinAc the AC dead-zone threshold
     * @param roundDc the DC rounding offset
     * @param roundAc the AC rounding offset
     * @param quantDc the DC reciprocal multiplier
     * @param quantAc the AC reciprocal multiplier
     * @param shiftDc the DC final right shift
     * @param shiftAc the AC final right shift
     * @param limit the largest magnitude plus rounding offset the multiplication takes
     * @return the scan position plus one of the last non-zero level, or 0 when all are zero
     */
    abstract int quantizeDz(int[] coeff, int n, int[] iscan, int[] qcoeff, int zbinDc, int zbinAc, int roundDc,
                                   int roundAc, int quantDc, int quantAc, int shiftDc, int shiftAc, int limit);

    /**
     * Returns the sum of squared differences of two 8-bit planes over their first rows, from the
     * same offset in both, with a common stride.
     *
     * @param a the first plane
     * @param b the second plane
     * @param off the offset of the first sample in both
     * @param stride the stride of both planes
     * @param w the width
     * @param h the height
     * @return the sum
     */
    abstract long planeSse(short[] a, short[] b, int off, int stride, int w, int h);

    /**
     * Returns the sum of squared differences and the sum of differences of two blocks, packed
     * as {@code sse << 32 | (sum & 0xFFFFFFFF)}.
     *
     * @param a the first plane
     * @param aOff the offset of the first block
     * @param aStride the first plane's stride
     * @param b the second plane
     * @param bOff the offset of the second block
     * @param bStride the second plane's stride
     * @param w the block width
     * @param h the block height
     * @return the packed sums
     */
    abstract long sseSum(short[] a, int aOff, int aStride, short[] b, int bOff, int bStride, int w, int h);

    /**
     * Returns the sum of squared differences of a packed {@link #sseSum} result.
     *
     * @param packed the packed sums
     * @return the sum of squared differences
     */
    static int sse(long packed) {
        return (int) (packed >>> 32);
    }

    /**
     * Returns the sum of differences of a packed {@link #sseSum} result.
     *
     * @param packed the packed sums
     * @return the sum of differences
     */
    static int sum(long packed) {
        return (int) packed;
    }

    /**
     * Returns the variance of a packed {@link #sseSum} result, {@code sse - sum^2 / n}, or 0
     * when the square term exceeds the sum of squares, which the separately rounded sums of the
     * high bit depth kernels allow.
     *
     * @param packed the packed sums
     * @param log2n the base-2 logarithm of the number of samples
     * @return the variance times the number of samples, an unsigned 32-bit value
     */
    static int variance(long packed, int log2n) {
        var sum = (long) sum(packed);
        return (int) Math.max(0, (packed >>> 32) - ((sum * sum) >>> log2n));
    }

    /**
     * Returns the rounded average of an 8x8 block minus the rounded average of another
     * ({@code aom_avg_8x8} of each).
     *
     * @param a the first plane
     * @param aOff the offset of the first block
     * @param aStride the first plane's stride
     * @param b the second plane
     * @param bOff the offset of the second block
     * @param bStride the second plane's stride
     * @return the difference of the averages
     */
    abstract int avgDiff8x8(short[] a, int aOff, int aStride, short[] b, int bOff, int bStride);

    /**
     * Returns the rounded average of a 4x4 block ({@code aom_avg_4x4}).
     *
     * @param s the plane
     * @param off the offset of the block
     * @param stride the plane's stride
     * @return the average
     */
    abstract int avg4x4(short[] s, int off, int stride);

    /**
     * Computes the difference of two blocks ({@code aom_subtract_block}).
     *
     * @param src the source plane
     * @param srcOff the offset of the source block
     * @param srcStride the source stride
     * @param pred the prediction plane
     * @param predOff the offset of the prediction block
     * @param predStride the prediction stride
     * @param diff receives the differences, row-major with the block width as stride
     * @param w the block width
     * @param h the block height
     */
    abstract void subtract(short[] src, int srcOff, int srcStride, short[] pred, int predOff, int predStride,
                                  int[] diff, int w, int h);

    /**
     * Computes the 8x8 Hadamard transform of a residual block ({@code aom_hadamard_lp_8x8}),
     * in the transposed order libaom's vector kernels produce.
     *
     * @param diff the residual
     * @param diffOff the offset of the block
     * @param stride the residual stride
     * @param coeff receives 64 coefficients
     * @param coeffOff the offset of the coefficients
     * @param scratch a scratch array of at least 64 entries
     */
    abstract void hadamard8x8(int[] diff, int diffOff, int stride, int[] coeff, int coeffOff, int[] scratch);

    /**
     * Computes the 16x16 Hadamard transform of a residual block ({@code aom_hadamard_lp_16x16}).
     *
     * @param diff the residual
     * @param diffOff the offset of the block
     * @param stride the residual stride
     * @param coeff receives 256 coefficients
     * @param coeffOff the offset of the coefficients
     * @param scratch a scratch array of at least 64 entries
     */
    void hadamard16x16(int[] diff, int diffOff, int stride, int[] coeff, int coeffOff, int[] scratch) {
        for (var idx = 0; idx < 4; idx++) {
            hadamard8x8(diff, diffOff + (idx >> 1) * 8 * stride + (idx & 1) * 8, stride, coeff, coeffOff + idx * 64,
                    scratch);
        }
        for (var idx = 0; idx < 64; idx++) {
            var p = coeffOff + idx;
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
     * Computes the forward transform of a residual block ({@link FwdTxfm#forward}).
     *
     * @param t the transform, whose scratch buffers the kernels use
     * @param txSize the transform size, in the decoder's numbering
     * @param txType the transform type
     * @param residual the residual samples
     * @param resOff the offset of the top-left sample
     * @param resStride the distance between rows
     * @param out receives the coefficients in the decoder's layout
     */
    abstract void forwardTransform(FwdTxfm t, int txSize, int txType, int[] residual, int resOff,
                                          int resStride, int[] out);

    /**
     * Quantizes transform coefficients with the fast rounding rule and returns the end of block
     * ({@code av1_quantize_lp}).
     *
     * @param coeff the coefficients
     * @param coeffOff the offset of the coefficients
     * @param n the number of coefficients
     * @param roundFp the rounding offsets of the DC and AC coefficients
     * @param quantFp the reciprocal multipliers of the DC and AC coefficients
     * @param dequant the dequantizers of the DC and AC coefficients
     * @param scan the scan order
     * @param iscan the scan position plus one of each coefficient, the inverse of {@code scan}
     * @param qcoeff receives the levels
     * @param dqcoeff receives the dequantized coefficients
     * @return the number of coefficients up to the last non-zero one in scan order
     */
    abstract int quantizeLp(int[] coeff, int coeffOff, int n, int[] roundFp, int[] quantFp, int[] dequant,
                                   int[] scan, int[] iscan, int[] qcoeff, int[] dqcoeff);

    /**
     * Quantizes a block like {@link #quantizeLp} and measures the result as the transform-domain
     * rate estimate of the real-time mode decision does: the sum of the levels' magnitudes, as
     * {@link #satd} computes, and the squared error of the dequantized coefficients, as
     * {@link #blockError} computes.
     *
     * <p>The levels and dequantized coefficients go to {@code qcoeff} and {@code dqcoeff}, which an
     * implementation may use as scratch space and leave with any content. Every difference
     * between a coefficient and its dequantized value must be at most 46340 in magnitude, as for
     * {@link #blockError}.
     *
     * @param coeff the coefficients in raster order, from index 0
     * @param n the number of coefficients
     * @param roundFp the rounding offsets of the DC and AC coefficients
     * @param quantFp the reciprocal multipliers of the DC and AC coefficients
     * @param dequant the dequantizers of the DC and AC coefficients
     * @param scan the scan order
     * @param iscan the scan position plus one of each coefficient, the inverse of {@code scan}
     * @param qcoeff the scratch space of the levels
     * @param dqcoeff the scratch space of the dequantized coefficients
     * @param result receives the sum of the levels' magnitudes at index 0 and the squared error at
     * index 1
     * @return the number of coefficients up to the last non-zero one in scan order
     */
    int quantizeLpRd(int[] coeff, int n, int[] roundFp, int[] quantFp, int[] dequant, int[] scan,
                            int[] iscan, int[] qcoeff, int[] dqcoeff, long[] result) {
        var eob = quantizeLp(coeff, 0, n, roundFp, quantFp, dequant, scan, iscan, qcoeff, dqcoeff);
        result[0] = satd(qcoeff, n);
        result[1] = blockError(coeff, 0, dqcoeff, n);
        return eob;
    }

    /**
     * Returns the sum of absolute values ({@code aom_satd_lp}).
     *
     * @param c the values
     * @param n the number of values
     * @return the sum
     */
    abstract int satd(int[] c, int n);

    /**
     * Returns the sum of squared differences of two coefficient sets ({@code av1_block_error_lp}).
     *
     * <p>Every difference must be at most 46340 in magnitude, so its square fits an {@code int},
     * as with libaom's 16-bit coefficients.
     *
     * @param a the first set
     * @param aOff the offset of the first set
     * @param b the second set
     * @param n the number of coefficients
     * @return the sum
     */
    abstract long blockError(int[] a, int aOff, int[] b, int n);

    /**
     * Returns the variance of a block against a bilinearly interpolated reference block
     * ({@code aom_sub_pixel_variance}), packed like {@link #sseSum}.
     *
     * @param ref the reference plane
     * @param refOff the offset of the reference block's integer position
     * @param refStride the reference stride
     * @param xoff the horizontal eighth-sample offset, 0 to 7
     * @param yoff the vertical eighth-sample offset, 0 to 7
     * @param src the source plane
     * @param srcOff the offset of the source block
     * @param srcStride the source stride
     * @param w the block width
     * @param h the block height
     * @param scratch a scratch array of at least {@code (h + 1) * w} entries
     * @return the packed sums
     */
    abstract long subpelSseSum(short[] ref, int refOff, int refStride, int xoff, int yoff, short[] src,
                                      int srcOff, int srcStride, int w, int h, short[] scratch);

    /**
     * Computes the column sums of a block, normalized ({@code aom_int_pro_row}).
     *
     * @param hbuf receives one value per column
     * @param ref the plane
     * @param refOff the offset of the block
     * @param refStride the stride
     * @param width the block width
     * @param height the block height
     * @param norm the normalization shift
     */
    abstract void intProRow(int[] hbuf, short[] ref, int refOff, int refStride, int width, int height, int norm);

    /**
     * Computes the row sums of a block, normalized ({@code aom_int_pro_col}).
     *
     * @param vbuf receives one value per row
     * @param ref the plane
     * @param refOff the offset of the block
     * @param refStride the stride
     * @param width the block width
     * @param height the block height
     * @param norm the normalization shift
     */
    abstract void intProCol(int[] vbuf, short[] ref, int refOff, int refStride, int width, int height, int norm);
}
