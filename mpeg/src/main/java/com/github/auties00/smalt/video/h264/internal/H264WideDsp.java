package com.github.auties00.smalt.video.h264.internal;

import com.github.auties00.smalt.internal.simd.Simd;

/**
 * Defines the sample-processing kernels of the reconstruction of H.264 pictures deeper than 8
 * bits, on planes of 16-bit samples: fractional-sample interpolation, weighted prediction, the
 * inverse transforms with reconstruction, and the deblocking edge filters (ITU-T H.264 8.4.2, 8.5
 * and 8.7 for {@code BitDepthY} and {@code BitDepthC} from 8 to 14).
 *
 * <p>The kernels mirror those of {@link H264Dsp} with the sample range {@code 0..max} in place of
 * {@code 0..255}. The deblocking kernels take the thresholds and {@code tC0} of the 8-bit tables
 * and scale them by {@code 1 << shift}, where {@code shift} is the bit depth minus 8, as ITU-T
 * H.264 equations 8-466, 8-467 and 8-470 do.
 *
 * <p>Every kernel exists twice: {@link ScalarH264WideDsp} is the reference implementation, and a
 * Vector API implementation overrides the kernels that profit from SIMD; both produce the same
 * samples for every input. {@link #INSTANCE} is the implementation pictures are reconstructed
 * with. Implementations are stateless and thread-safe; callers pass the scratch buffers a kernel
 * needs. Arguments are not checked; values outside the documented ranges give unspecified results.
 */
abstract class H264WideDsp {
    /**
     * The minimum size of the {@code int} scratch buffer the luma interpolation kernel needs: one
     * row of horizontal sums per row of a 16x16 block and the five rows around it.
     */
    static final int LUMA_SCRATCH_INTS = 16 * 21;

    /**
     * The minimum size of the {@code short} scratch buffer the luma interpolation kernel needs:
     * two 16x16 blocks.
     */
    static final int LUMA_SCRATCH_SHORTS = 2 * 256;

    /**
     * The scalar kernels, which every JVM runs.
     */
    static final H264WideDsp SCALAR = new ScalarH264WideDsp();

    /**
     * The kernels pictures are reconstructed and deblocked with: the Vector API implementation
     * when {@link Simd#ENABLED}, the scalar one otherwise.
     */
    static final H264WideDsp INSTANCE = Simd.ENABLED ? VectorH264WideDspHolder.create() : SCALAR;

    /**
     * Constructs a kernel set.
     */
    H264WideDsp() {
    }

    /**
     * Clips a value to the sample range.
     *
     * @param value the sample, possibly outside {@code 0..max}
     * @param max the largest sample value, {@code (1 << bitDepth) - 1}
     * @return {@code value} clamped to {@code [0, max]}
     */
    static int clip(int value, int max) {
        return value < 0 ? 0 : (value > max ? max : value);
    }

    /**
     * Interpolates a luma block at quarter-sample precision (ITU-T H.264 8.4.2.2.1).
     *
     * <p>Reads {@code src} from 2 samples before to 3 samples after the block in each direction
     * when the matching fraction is not zero.
     *
     * @param src the reference plane
     * @param srcOffset the index of the integer sample at the block's top-left corner
     * @param srcStride the reference row stride
     * @param dst the destination
     * @param dstOffset the index of the destination top-left sample
     * @param dstStride the destination row stride
     * @param width the block width, 4, 8 or 16
     * @param height the block height, 4, 8 or 16
     * @param fracX the horizontal quarter-sample fraction, 0 to 3
     * @param fracY the vertical quarter-sample fraction, 0 to 3
     * @param sums scratch of at least {@link #LUMA_SCRATCH_INTS} values
     * @param scratch scratch of at least {@link #LUMA_SCRATCH_SHORTS} values
     * @param max the largest sample value
     */
    public abstract void lumaMc(short[] src, int srcOffset, int srcStride, short[] dst, int dstOffset, int dstStride,
                                int width, int height, int fracX, int fracY, int[] sums, short[] scratch, int max);

    /**
     * Interpolates the co-located blocks of both chroma planes at eighth-sample precision
     * (ITU-T H.264 8.4.2.2.2).
     *
     * <p>Reads one sample to the right of and below each block when the matching fraction is not
     * zero.
     *
     * @param srcCb the Cb reference plane
     * @param srcCr the Cr reference plane
     * @param srcOffset the index of the integer sample at the block's top-left corner in both
     * planes
     * @param srcStride the reference row stride
     * @param dstCb the Cb destination
     * @param dstCr the Cr destination
     * @param dstOffset the index of the destination top-left sample in both planes
     * @param dstStride the destination row stride
     * @param width the block width
     * @param height the block height
     * @param fracX the horizontal eighth-sample fraction, 0 to 7
     * @param fracY the vertical eighth-sample fraction, 0 to 7
     */
    public abstract void chromaMc(short[] srcCb, short[] srcCr, int srcOffset, int srcStride, short[] dstCb,
                                  short[] dstCr, int dstOffset, int dstStride, int width, int height, int fracX,
                                  int fracY);

    /**
     * Averages two blocks with upward rounding, {@code (a + b + 1) >> 1}, into a third.
     *
     * @param a the first block's plane
     * @param aOffset the index of the first block's top-left sample
     * @param aStride the first block's row stride
     * @param b the second block's plane
     * @param bOffset the index of the second block's top-left sample
     * @param bStride the second block's row stride
     * @param dst the destination
     * @param dstOffset the index of the destination top-left sample
     * @param dstStride the destination row stride
     * @param width the block width
     * @param height the block height
     */
    public abstract void averageBlocks(short[] a, int aOffset, int aStride, short[] b, int bOffset, int bStride,
                                       short[] dst, int dstOffset, int dstStride, int width, int height);

    /**
     * Averages a prediction in place with a second one, {@code (dst + src + 1) >> 1}.
     *
     * @param dst the first prediction, replaced by the average
     * @param dstOffset the index of its top-left sample
     * @param dstStride its row stride
     * @param src the second prediction
     * @param srcOffset the index of its top-left sample
     * @param srcStride its row stride
     * @param width the block width
     * @param height the block height
     */
    public abstract void average(short[] dst, int dstOffset, int dstStride, short[] src, int srcOffset, int srcStride,
                                 int width, int height);

    /**
     * Applies explicit unidirectional weighted prediction in place (ITU-T H.264 equation 8-449).
     *
     * @param dst the prediction, weighted in place
     * @param dstOffset the index of its top-left sample
     * @param dstStride its row stride
     * @param width the block width
     * @param height the block height
     * @param logWd the weight denominator exponent, 0 to 7
     * @param weight the multiplier of each sample, applied before the rounding shift
     * @param offset the offset, already scaled to the bit depth
     * @param max the largest sample value
     */
    public abstract void weight(short[] dst, int dstOffset, int dstStride, int width, int height, int logWd, int weight,
                                int offset, int max);

    /**
     * Combines two predictions with bi-predictive weights (ITU-T H.264 equation 8-451).
     *
     * @param dst the list 0 prediction, replaced by the combination
     * @param dstOffset the index of its top-left sample
     * @param dstStride its row stride
     * @param src the list 1 prediction
     * @param srcOffset the index of its top-left sample
     * @param srcStride its row stride
     * @param width the block width
     * @param height the block height
     * @param logWd the weight denominator exponent
     * @param weight0 the list 0 weight
     * @param weight1 the list 1 weight
     * @param offset the combined offset {@code (o0 + o1 + 1) >> 1}, already scaled to the bit depth
     * @param max the largest sample value
     */
    public abstract void biWeight(short[] dst, int dstOffset, int dstStride, short[] src, int srcOffset, int srcStride,
                                  int width, int height, int logWd, int weight0, int weight1, int offset, int max);

    /**
     * Adds the inverse 4x4 transform of a block of scaled coefficients to the prediction and
     * clears the coefficients (ITU-T H.264 8.5.12).
     *
     * @param c the coefficients, in raster order
     * @param o the index of the block's first coefficient
     * @param dst the plane holding the prediction
     * @param dstOffset the index of the block's top-left sample
     * @param dstStride the row stride
     * @param max the largest sample value
     */
    public abstract void idct4x4Add(int[] c, int o, short[] dst, int dstOffset, int dstStride, int max);

    /**
     * Adds the inverse transform of a 4x4 block whose only non-zero coefficient is the DC one,
     * and clears it.
     *
     * @param c the coefficients
     * @param o the index of the block's DC coefficient
     * @param dst the plane holding the prediction
     * @param dstOffset the index of the block's top-left sample
     * @param dstStride the row stride
     * @param max the largest sample value
     */
    public abstract void idct4x4DcAdd(int[] c, int o, short[] dst, int dstOffset, int dstStride, int max);

    /**
     * Adds the inverse transforms of the four DC-only 4x4 blocks of an 8x8 chroma block and
     * clears their coefficients.
     *
     * @param c the coefficients of the four blocks, 16 per block
     * @param dst the plane holding the prediction
     * @param dstOffset the index of the block's top-left sample
     * @param dstStride the row stride
     * @param max the largest sample value
     */
    public abstract void chromaDcAdd(int[] c, short[] dst, int dstOffset, int dstStride, int max);

    /**
     * Adds the inverse transform of an 8x8 block whose only non-zero coefficient is the DC one,
     * and clears it.
     *
     * @param c the coefficients
     * @param o the index of the block's DC coefficient
     * @param dst the plane holding the prediction
     * @param dstOffset the index of the block's top-left sample
     * @param dstStride the row stride
     * @param max the largest sample value
     */
    public abstract void idct8x8DcAdd(int[] c, int o, short[] dst, int dstOffset, int dstStride, int max);

    /**
     * Adds the inverse 8x8 transform of a block of scaled coefficients to the prediction and
     * clears the coefficients (ITU-T H.264 8.5.13).
     *
     * @param c the coefficients, in raster order
     * @param o the index of the block's first coefficient
     * @param dst the plane holding the prediction
     * @param dstOffset the index of the block's top-left sample
     * @param dstStride the row stride
     * @param max the largest sample value
     */
    public abstract void idct8x8Add(int[] c, int o, short[] dst, int dstOffset, int dstStride, int max);

    /**
     * Filters the samples across one vertical luma edge of 16 rows with boundary strengths below
     * 4.
     *
     * @param pix the plane, edited in place
     * @param offset the index of the first row's {@code q0} sample
     * @param stride the row stride
     * @param alpha the 8-bit {@code alpha'} threshold
     * @param beta the 8-bit {@code beta'} threshold
     * @param tc0 the 8-bit {@code tC0'} of the four 4-row segments, one signed byte each from
     * the lowest, negative for a segment left unfiltered
     * @param shift the bit depth minus 8
     */
    public abstract void lumaEdgeVertical(short[] pix, int offset, int stride, int alpha, int beta, int tc0, int shift);

    /**
     * Filters the samples across one horizontal luma edge of 16 columns with boundary strengths
     * below 4.
     *
     * @param pix the plane, edited in place
     * @param offset the index of the first column's {@code q0} sample
     * @param stride the row stride
     * @param alpha the 8-bit {@code alpha'} threshold
     * @param beta the 8-bit {@code beta'} threshold
     * @param tc0 the packed 8-bit {@code tC0'} of the four segments, as for
     * {@link #lumaEdgeVertical}
     * @param shift the bit depth minus 8
     */
    public abstract void lumaEdgeHorizontal(short[] pix, int offset, int stride, int alpha, int beta, int tc0,
                                            int shift);

    /**
     * Filters the samples across one vertical luma edge of 16 rows with boundary strength 4.
     *
     * @param pix the plane, edited in place
     * @param offset the index of the first row's {@code q0} sample
     * @param stride the row stride
     * @param alpha the 8-bit {@code alpha'} threshold
     * @param beta the 8-bit {@code beta'} threshold
     * @param shift the bit depth minus 8
     */
    public abstract void lumaEdgeVerticalStrong(short[] pix, int offset, int stride, int alpha, int beta, int shift);

    /**
     * Filters the samples across one horizontal luma edge of 16 columns with boundary strength 4.
     *
     * @param pix the plane, edited in place
     * @param offset the index of the first column's {@code q0} sample
     * @param stride the row stride
     * @param alpha the 8-bit {@code alpha'} threshold
     * @param beta the 8-bit {@code beta'} threshold
     * @param shift the bit depth minus 8
     */
    public abstract void lumaEdgeHorizontalStrong(short[] pix, int offset, int stride, int alpha, int beta, int shift);

    /**
     * Filters the two vertical chroma edges of a macroblock (chroma columns 0 and 4, 8 rows each)
     * in both chroma planes.
     *
     * @param cb the Cb plane, edited in place
     * @param cr the Cr plane, edited in place
     * @param offset the index of the macroblock's top-left chroma sample
     * @param stride the row stride
     * @param edges the edges to filter: bit 0 for column 0, bit 1 for column 4
     * @param params the parameters of the edges in the layout of
     * {@link H264Dsp#chromaEdgesVertical}, with 8-bit thresholds and {@code tC0'}
     * @param shift the chroma bit depth minus 8
     */
    public abstract void chromaEdgesVertical(short[] cb, short[] cr, int offset, int stride, int edges, int[] params,
                                             int shift);

    /**
     * Filters one horizontal chroma edge of 8 columns in both chroma planes.
     *
     * @param cb the Cb plane, edited in place
     * @param cr the Cr plane, edited in place
     * @param offset the index of the edge's first {@code q0} sample
     * @param stride the row stride
     * @param params the parameters, as for {@link #chromaEdgesVertical}
     * @param at the index of the edge's first parameter
     * @param shift the chroma bit depth minus 8
     */
    public abstract void chromaEdgeHorizontal(short[] cb, short[] cr, int offset, int stride, int[] params, int at,
                                              int shift);
}
