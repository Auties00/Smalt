package com.github.auties00.smalt.video.h264.internal;

import com.github.auties00.smalt.internal.simd.Simd;

/**
 * Defines the hot sample-processing kernels of H.264 reconstruction: fractional-sample
 * interpolation, weighted prediction, inverse transforms and the deblocking filter.
 *
 * <p>Every kernel exists twice: {@link ScalarH264Dsp} is the reference implementation, and a
 * Vector API implementation overrides the kernels that profit from SIMD; both produce the same
 * samples for every input. {@link #INSTANCE} is the implementation pictures are reconstructed
 * with. Implementations are stateless and thread-safe; callers pass the scratch buffers a kernel
 * needs.
 *
 * <p>Planes are {@code byte[]} arrays of unsigned 8-bit samples addressed by an offset and a
 * row stride. Kernels never read or write outside the rectangle they are given, extended by the
 * filter margins documented on each method.
 */
public abstract class H264Dsp {
    /**
     * The scalar kernels, which every JVM runs.
     */
    static final H264Dsp SCALAR = new ScalarH264Dsp();

    /**
     * The kernels pictures are reconstructed and deblocked with: the Vector API implementation
     * when {@link Simd#ENABLED}, the scalar one otherwise.
     */
    public static final H264Dsp INSTANCE = Simd.ENABLED ? VectorH264DspHolder.create() : SCALAR;

    /**
     * The number of bytes of the working storage the vertical edge filters take: eight columns
     * of sixteen 16-bit samples.
     */
    public static final int EDGE_SCRATCH = 256;

    /**
     * The minimum size of the {@code short} scratch buffer the luma interpolation kernels need:
     * the horizontal sums of the 21 rows of a 16-wide center-position block.
     */
    public static final int LUMA_SCRATCH_SHORTS = 21 * 16;

    /**
     * The minimum size of the {@code byte} scratch buffer the luma interpolation kernels need.
     */
    public static final int LUMA_SCRATCH_BYTES = 2 * 16 * 16;

    /**
     * The packed chroma {@code tC0} byte that selects the boundary strength 4 filter.
     */
    public static final int CHROMA_STRONG = 0x40;

    /**
     * Constructs a kernel set.
     */
    protected H264Dsp() {
    }

    /**
     * Interpolates a luma block at quarter-sample precision (ITU-T H.264 8.4.2.2.1).
     *
     * @implSpec
     * Implementations must produce exactly the samples of the normative 6-tap interpolation.
     * They read {@code src} from 2 samples before to 3 samples after the block in each
     * interpolated direction.
     *
     * @param src the reference plane
     * @param srcOffset the index of the integer sample at the block's top-left corner
     * @param srcStride the reference row stride
     * @param dst the destination plane
     * @param dstOffset the index of the destination top-left sample
     * @param dstStride the destination row stride
     * @param width the block width: 4, 8 or 16
     * @param height the block height: 4, 8 or 16
     * @param fracX the horizontal quarter-sample fraction, 0 to 3
     * @param fracY the vertical quarter-sample fraction, 0 to 3
     * @param sums a {@code short} scratch buffer of at least {@link #LUMA_SCRATCH_SHORTS}
     * entries
     * @param scratch a {@code byte} scratch buffer of at least {@link #LUMA_SCRATCH_BYTES}
     * entries
     */
    public abstract void lumaMc(byte[] src, int srcOffset, int srcStride, byte[] dst, int dstOffset, int dstStride,
                                int width, int height, int fracX, int fracY, short[] sums, byte[] scratch);

    /**
     * Interpolates the co-located blocks of both chroma planes at eighth-sample precision
     * (ITU-T H.264 8.4.2.2.2).
     *
     * @implSpec
     * Implementations read one sample to the right of and below each block when the matching
     * fraction is non-zero. Both planes share the offsets, strides and fractions.
     *
     * @param srcCb the Cb reference plane
     * @param srcCr the Cr reference plane
     * @param srcOffset the index of the integer sample at the blocks' top-left corner
     * @param srcStride the reference row stride
     * @param dstCb the Cb destination
     * @param dstCr the Cr destination
     * @param dstOffset the index of the destination top-left sample
     * @param dstStride the destination row stride
     * @param width the block width: 2, 4 or 8
     * @param height the block height: 2, 4 or 8
     * @param fracX the horizontal eighth-sample fraction, 0 to 7
     * @param fracY the vertical eighth-sample fraction, 0 to 7
     */
    public abstract void chromaMc(byte[] srcCb, byte[] srcCr, int srcOffset, int srcStride, byte[] dstCb, byte[] dstCr,
                                  int dstOffset, int dstStride, int width, int height, int fracX, int fracY);

    /**
     * Averages two blocks with upward rounding, {@code (a + b + 1) >> 1}, into a third: the
     * default bi-predictive combination of two full-sample predictions read straight from their
     * reference pictures (ITU-T H.264 8.4.2.3.1).
     *
     * <p>The destination may not overlap either source.
     *
     * @param a the first block
     * @param aOffset the index of the top-left sample in {@code a}
     * @param aStride the row stride of {@code a}
     * @param b the second block
     * @param bOffset the index of the top-left sample in {@code b}
     * @param bStride the row stride of {@code b}
     * @param dst receives the average
     * @param dstOffset the index of the top-left sample in {@code dst}
     * @param dstStride the row stride of {@code dst}
     * @param width the block width
     * @param height the block height
     */
    public abstract void averageBlocks(byte[] a, int aOffset, int aStride, byte[] b, int bOffset, int bStride,
                                       byte[] dst, int dstOffset, int dstStride, int width, int height);

    /**
     * Averages two predictions with upward rounding, {@code (a + b + 1) >> 1}, the default
     * bi-predictive combination (ITU-T H.264 8.4.2.3.1).
     *
     * @param dst the first prediction; receives the result
     * @param dstOffset the index of the top-left sample in {@code dst}
     * @param dstStride the row stride of {@code dst}
     * @param src the second prediction
     * @param srcOffset the index of the top-left sample in {@code src}
     * @param srcStride the row stride of {@code src}
     * @param width the block width
     * @param height the block height
     */
    public abstract void average(byte[] dst, int dstOffset, int dstStride, byte[] src, int srcOffset, int srcStride,
                                 int width, int height);

    /**
     * Applies explicit unidirectional weighted prediction in place (ITU-T H.264 equation
     * 8-270).
     *
     * @param dst the prediction; receives the result
     * @param dstOffset the index of the top-left sample
     * @param dstStride the row stride
     * @param width the block width
     * @param height the block height
     * @param logWd the weight denominator exponent, 0 to 7
     * @param weight the explicit weight {@code w}
     * @param offset the explicit offset {@code o}, added after the shift
     */
    public abstract void weight(byte[] dst, int dstOffset, int dstStride, int width, int height,
                                int logWd, int weight, int offset);

    /**
     * Combines two predictions with explicit or implicit bi-predictive weights (ITU-T H.264
     * equation 8-273).
     *
     * @param dst the list 0 prediction; receives the result
     * @param dstOffset the index of the top-left sample in {@code dst}
     * @param dstStride the row stride of {@code dst}
     * @param src the list 1 prediction
     * @param srcOffset the index of the top-left sample in {@code src}
     * @param srcStride the row stride of {@code src}
     * @param width the block width
     * @param height the block height
     * @param logWd the weight denominator exponent
     * @param weight0 the list 0 weight
     * @param weight1 the list 1 weight
     * @param offset the combined offset {@code (o0 + o1 + 1) >> 1}
     */
    public abstract void biWeight(byte[] dst, int dstOffset, int dstStride, byte[] src, int srcOffset, int srcStride,
                                  int width, int height, int logWd, int weight0, int weight1, int offset);

    /**
     * Adds the inverse 4x4 transform of a block of scaled coefficients to the prediction and
     * clears the coefficients (ITU-T H.264 8.5.12).
     *
     * @param coefficients the scaled coefficients in raster order; zeroed on return
     * @param coeffOffset the index of the first coefficient
     * @param dst the prediction plane; receives the reconstruction
     * @param dstOffset the index of the top-left sample
     * @param dstStride the row stride
     */
    public abstract void idct4x4Add(int[] coefficients, int coeffOffset, byte[] dst, int dstOffset, int dstStride);

    /**
     * Adds the inverse 8x8 transform of a block of scaled coefficients to the prediction and
     * clears the coefficients (ITU-T H.264 8.5.13).
     *
     * @param coefficients the scaled coefficients in raster order; zeroed on return
     * @param coeffOffset the index of the first coefficient
     * @param dst the prediction plane; receives the reconstruction
     * @param dstOffset the index of the top-left sample
     * @param dstStride the row stride
     */
    public abstract void idct8x8Add(int[] coefficients, int coeffOffset, byte[] dst, int dstOffset, int dstStride);

    /**
     * Adds the inverse transform of a 4x4 block whose only non-zero coefficient is the DC one,
     * {@code (dc + 32) >> 6} to every sample, and clears the coefficient.
     *
     * @param coefficients the scaled coefficients; entry {@code coeffOffset} is the DC value
     * @param coeffOffset the index of the DC coefficient
     * @param dst the prediction plane; receives the reconstruction
     * @param dstOffset the index of the top-left sample
     * @param dstStride the row stride
     */
    public abstract void idct4x4DcAdd(int[] coefficients, int coeffOffset, byte[] dst, int dstOffset, int dstStride);

    /**
     * Adds the inverse transform of an 8x8 block whose only non-zero coefficient is the DC one,
     * {@code (dc + 32) >> 6} to every sample, and clears the coefficient.
     *
     * @param coefficients the scaled coefficients; entry {@code coeffOffset} is the DC value
     * @param coeffOffset the index of the DC coefficient
     * @param dst the prediction plane; receives the reconstruction
     * @param dstOffset the index of the top-left sample
     * @param dstStride the row stride
     */
    public abstract void idct8x8DcAdd(int[] coefficients, int coeffOffset, byte[] dst, int dstOffset, int dstStride);

    /**
     * Adds the inverse transforms of the four 4x4 blocks of an 8x8 chroma block none of which
     * has a non-zero AC coefficient: {@code (dc + 32) >> 6} of each block's DC coefficient (at
     * {@code 0}, {@code 16}, {@code 32} and {@code 48}, blocks in raster order) to its samples,
     * and clears the four coefficients.
     *
     * @param coefficients the scaled coefficients of the four blocks, 16 per block
     * @param dst the prediction plane; receives the reconstruction
     * @param dstOffset the index of the top-left sample of the 8x8 block
     * @param dstStride the row stride
     */
    public abstract void chromaDcAdd(int[] coefficients, byte[] dst, int dstOffset, int dstStride);

    /**
     * Filters the samples across one vertical luma edge of 16 rows with boundary strengths below
     * 4 (ITU-T H.264 8.7.2.3).
     *
     * <p>{@code tc0} packs the {@code tC0} of the four 4-row segments as signed bytes, segment
     * {@code i} in bits {@code 8i..8i+7}; a negative byte leaves its segment unfiltered. The edge
     * reads the three samples on each side of it and writes at most two.
     *
     * @implSpec Implementations may also read, without using them, up to twelve samples on each
     * side of the edge in every row, so the plane must hold them.
     *
     * @param pix the plane
     * @param offset the index of the first {@code q0} sample (top row, right of the edge)
     * @param stride the row stride
     * @param alpha the {@code alpha} threshold
     * @param beta the {@code beta} threshold
     * @param tc0 the packed per-segment {@code tC0}
     * @param scratch working storage of at least {@link #EDGE_SCRATCH} elements, overwritten
     */
    public abstract void lumaEdgeVertical(byte[] pix, int offset, int stride, int alpha, int beta, int tc0,
                                          byte[] scratch);

    /**
     * Filters the samples across one horizontal luma edge of 16 columns with boundary strengths
     * below 4, with the {@code tc0} packing of {@link #lumaEdgeVertical}.
     *
     * @param pix the plane
     * @param offset the index of the first {@code q0} sample (left column, below the edge)
     * @param stride the row stride
     * @param alpha the {@code alpha} threshold
     * @param beta the {@code beta} threshold
     * @param tc0 the packed per-segment {@code tC0}
     */
    public abstract void lumaEdgeHorizontal(byte[] pix, int offset, int stride, int alpha, int beta, int tc0);

    /**
     * Filters the samples across one vertical luma edge of 16 rows with boundary strength 4
     * (ITU-T H.264 8.7.2.4). The edge reads four samples on each side of it and writes at most
     * three.
     *
     * @implSpec Implementations may also read, without using them, up to twelve samples on each
     * side of the edge in every row, so the plane must hold them.
     *
     * @param pix the plane
     * @param offset the index of the first {@code q0} sample
     * @param stride the row stride
     * @param alpha the {@code alpha} threshold
     * @param beta the {@code beta} threshold
     * @param scratch working storage of at least {@link #EDGE_SCRATCH} elements, overwritten
     */
    public abstract void lumaEdgeVerticalStrong(byte[] pix, int offset, int stride, int alpha, int beta,
                                                byte[] scratch);

    /**
     * Filters the samples across one horizontal luma edge of 16 columns with boundary strength
     * 4.
     *
     * @param pix the plane
     * @param offset the index of the first {@code q0} sample
     * @param stride the row stride
     * @param alpha the {@code alpha} threshold
     * @param beta the {@code beta} threshold
     */
    public abstract void lumaEdgeHorizontalStrong(byte[] pix, int offset, int stride, int alpha, int beta);

    /**
     * Filters the two vertical chroma edges of a macroblock (chroma columns 0 and 4, 8 rows
     * each) in both chroma planes.
     *
     * <p>{@code params} holds four {@code int}s per edge, the first edge at index 0 and the
     * second at index 4: the thresholds of Cb and of Cr ({@code alpha | beta << 8}) and the
     * packed {@code tC0} of Cb and of Cr. A packed {@code tC0} holds one signed byte per luma
     * segment, segment {@code i} (chroma rows {@code 2i} and {@code 2i + 1}) in bits
     * {@code 8i..8i+7}: negative to leave the rows unfiltered, {@link #CHROMA_STRONG} for boundary
     * strength 4, otherwise the {@code tC0} of a boundary strength below 4. A zero threshold
     * leaves its plane unfiltered. Only the edges selected by {@code edges} (bit 0 for column 0,
     * bit 1 for column 4) are filtered. The two edges read and write disjoint columns.
     *
     * @implSpec Implementations may also read, without using them, the samples of every row from
     * ten columns left of the macroblock to fourteen columns right of its first column, so the
     * planes must hold them.
     *
     * @param cb the Cb plane
     * @param cr the Cr plane
     * @param offset the index of the macroblock's top-left chroma sample in both planes
     * @param stride the row stride of both planes
     * @param edges the bit set of the edges to filter
     * @param params the per-edge thresholds and packed {@code tC0}
     * @param scratch working storage of at least {@link #EDGE_SCRATCH} elements, overwritten
     */
    public abstract void chromaEdgesVertical(byte[] cb, byte[] cr, int offset, int stride, int edges, int[] params,
                                             byte[] scratch);

    /**
     * Filters one horizontal chroma edge of 8 columns in both chroma planes, with the parameter
     * layout of {@link #chromaEdgesVertical}.
     *
     * @param cb the Cb plane
     * @param cr the Cr plane
     * @param offset the index of the first {@code q0} sample in both planes
     * @param stride the row stride of both planes
     * @param params the thresholds and packed {@code tC0} of the edge, from index {@code at}
     * @param at the index of the edge's first parameter
     */
    public abstract void chromaEdgeHorizontal(byte[] cb, byte[] cr, int offset, int stride, int[] params, int at);
}
