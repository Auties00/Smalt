package com.github.auties00.smalt.video.hevc.internal;

import com.github.auties00.smalt.internal.simd.Simd;

/**
 * Defines the hot sample-processing kernels of HEVC reconstruction: the fractional sample
 * interpolation of inter prediction (ITU-T H.265 8.5.3.3.3), the default and explicit weighted
 * sample prediction that writes the prediction into the picture (8.5.3.3.4.2 and 8.5.3.3.4.3), and
 * the edge and band offsets of the sample adaptive offset filter (8.7.3).
 *
 * <p>Every kernel exists twice: {@link ScalarHevcDsp} is the reference implementation, and a
 * Vector API implementation overrides the kernels that profit from SIMD; both produce the same
 * samples for every input. {@link #select()} returns the implementation a picture is reconstructed
 * with. Implementations are stateless and thread-safe; callers pass the scratch buffers a kernel
 * needs.
 *
 * <p>Planes are {@code short[]} arrays of unsigned samples, or, for pictures whose samples all have
 * 8 bits, {@code byte[]} arrays of unsigned samples, addressed by an offset and a row stride in
 * samples; every kernel exists for both kinds of plane, the 8-bit ones taking no bit depth. An
 * 8-bit plane holds at least {@link HevcFrame#PADDING} bytes after the last sample a kernel reads,
 * which the vector kernels may load and ignore. Prediction blocks are {@code short[]} arrays of the
 * 14-bit intermediate samples of
 * the specification ({@code predSamplesLX}), {@code w} values per row. The kernels serve pictures
 * of up to 12 bits, whose intermediate samples fit 16 bits except for the bi-directionally
 * filtered luma samples of some pathological sample patterns: the interpolation reports those, and
 * the caller then predicts the block with the 32-bit processes of {@link ScalarHevcDsp}, which also
 * serve deeper pictures. The luma interpolation
 * reads 3 columns left and 4 right of the block when its horizontal fraction is not zero, and 3
 * rows above and 4 below when its vertical fraction is not zero; the chroma interpolation reads 1
 * column (row) before and 2 after. Callers extend the reference picture when that area leaves it.
 */
public abstract class HevcDsp {
    /**
     * The scalar kernels, which every JVM runs.
     */
    static final HevcDsp SCALAR = new ScalarHevcDsp();

    /**
     * The implementation every picture uses: the Vector API kernels
     * when {@link Simd#ENABLED}, {@link #SCALAR} otherwise.
     */
    private static final HevcDsp FIXED = Simd.ENABLED ? VectorHevcDsp.create() : SCALAR;

    /**
     * The flag of {@link #deblockLuma} selecting the strong luma filter of a column.
     */
    public static final short DEBLOCK_STRONG = 1;

    /**
     * The flag of {@link #deblockLuma} selecting the normal luma filter of a column.
     */
    public static final short DEBLOCK_NORMAL = 2;

    /**
     * The flag of {@link #deblockLuma} letting the normal filter modify {@code p1} ({@code dEp}).
     */
    public static final short DEBLOCK_P1 = 4;

    /**
     * The flag of {@link #deblockLuma} letting the normal filter modify {@code q1} ({@code dEq}).
     */
    public static final short DEBLOCK_Q1 = 8;

    /**
     * The flag of {@link #deblockLuma} letting the filter modify the samples above the edge.
     */
    public static final short DEBLOCK_WRITE_P = 16;

    /**
     * The flag of {@link #deblockLuma} letting the filter modify the samples below the edge.
     */
    public static final short DEBLOCK_WRITE_Q = 32;

    /**
     * The size of the scratch buffer of {@link #deblockLumaVertical}: the 8 samples across the edge
     * of each of the 16 rows.
     */
    public static final int DEBLOCK_SCRATCH = 128;

    /**
     * The largest prediction block side.
     */
    public static final int MAX_BLOCK = 64;

    /**
     * The minimum size of the scratch buffer of the interpolation kernels: the horizontally
     * filtered rows of a 64 by 64 block with the vertical filter margins.
     */
    public static final int SCRATCH_SIZE = (MAX_BLOCK + 7) * MAX_BLOCK;

    /**
     * The luma interpolation filters of equations 8-224 to 8-229, indexed {@code frac * 8 + tap}
     * for taps at offsets -3 to 4.
     */
    static final int[] LUMA_FILTER = {
            0, 0, 0, 64, 0, 0, 0, 0,
            -1, 4, -10, 58, 17, -5, 1, 0,
            -1, 4, -11, 40, 40, -11, 4, -1,
            0, 1, -5, 17, 58, -10, 4, -1
    };

    /**
     * The chroma interpolation filters of equations 8-241 to 8-247, indexed {@code frac * 4 + tap}
     * for taps at offsets -1 to 2.
     */
    static final int[] CHROMA_FILTER = {
            0, 64, 0, 0,
            -2, 58, 10, -2,
            -4, 54, 16, -2,
            -6, 46, 28, -4,
            -4, 36, 36, -4,
            -4, 28, 46, -6,
            -2, 16, 54, -4,
            -2, 10, 58, -2
    };

    /**
     * Constructs a kernel set.
     */
    protected HevcDsp() {
    }

    /**
     * Returns the kernels one picture is reconstructed with.
     *
     * <p>This is the Vector API implementation when {@link Simd#ENABLED} and {@link #SCALAR} when
     * not. Every implementation produces the same samples, so the output never depends on the
     * selection.
     *
     * @return the kernels
     */
    public static HevcDsp select() {
        return FIXED;
    }

    /**
     * Returns the kernels a picture of a given sample bit depth is reconstructed with: those of
     * {@link #select()} up to 12 bits, and {@link #SCALAR} above, whose samples use the full
     * unsigned 16-bit range and the intermediate shifts of the range extensions.
     *
     * @param bitDepth the largest bit depth of the picture's components
     * @return the kernels
     */
    public static HevcDsp select(int bitDepth) {
        return bitDepth > 12 ? SCALAR : select();
    }

    /**
     * Interpolates a luma block at quarter-sample precision (8.5.3.3.3.2) into 14-bit
     * intermediate samples kept in 16 bits.
     *
     * <p>Every intermediate sample fits 16 bits except some of the half-sample positions in both
     * directions, which can reach 17 bits for extreme sample patterns; the method then returns
     * {@code false}, and the values of the block are unspecified.
     *
     * @param src the reference luma plane
     * @param srcOff the index of the reference sample at the block's integer position
     * @param srcStride the plane's row stride
     * @param dst receives the prediction, {@code w} values per row
     * @param w the block width, from 4 to {@link #MAX_BLOCK}
     * @param h the block height, from 4 to {@link #MAX_BLOCK}
     * @param xFrac the horizontal quarter-sample fraction, from 0 to 3
     * @param yFrac the vertical quarter-sample fraction, from 0 to 3
     * @param bitDepth the luma bit depth, from 8 to 12
     * @param scratch a scratch buffer of at least {@link #SCRATCH_SIZE} entries
     * @return {@code true} when every intermediate sample fits 16 bits
     */
    public abstract boolean lumaMc(short[] src, int srcOff, int srcStride, short[] dst, int w, int h, int xFrac,
                                   int yFrac, int bitDepth, short[] scratch);

    /**
     * Interpolates a chroma block at eighth-sample precision (8.5.3.3.3.3) into 14-bit
     * intermediate samples kept in 16 bits.
     *
     * @param src the reference chroma plane
     * @param srcOff the index of the reference sample at the block's integer position
     * @param srcStride the plane's row stride
     * @param dst receives the prediction, {@code w} values per row
     * @param w the block width, from 2 to {@link #MAX_BLOCK}
     * @param h the block height, from 2 to {@link #MAX_BLOCK}
     * @param xFrac the horizontal eighth-sample fraction, from 0 to 7
     * @param yFrac the vertical eighth-sample fraction, from 0 to 7
     * @param bitDepth the chroma bit depth, from 8 to 12
     * @param scratch a scratch buffer of at least {@link #SCRATCH_SIZE} entries
     * @return {@code true} when every intermediate sample fits 16 bits, which the chroma filters
     * always achieve
     */
    public abstract boolean chromaMc(short[] src, int srcOff, int srcStride, short[] dst, int w, int h, int xFrac,
                                     int yFrac, int bitDepth, short[] scratch);

    /**
     * Writes a uni-directional prediction into a plane with the default weighted sample prediction
     * (equation 8-252): {@code clip((pred + offset1) >> shift1)}.
     *
     * @param dst the destination plane
     * @param dstOff the index of the block's top-left sample
     * @param dstStride the plane's row stride
     * @param pred the prediction, {@code w} values per row
     * @param w the block width
     * @param h the block height
     * @param bitDepth the bit depth, from 8 to 12
     */
    public abstract void putUni(short[] dst, int dstOff, int dstStride, short[] pred, int w, int h, int bitDepth);

    /**
     * Writes a bi-directional prediction into a plane with the default weighted sample prediction
     * (equation 8-253): {@code clip((pred0 + pred1 + offset2) >> shift2)}.
     *
     * @param dst the destination plane
     * @param dstOff the index of the block's top-left sample
     * @param dstStride the plane's row stride
     * @param pred0 the list 0 prediction, {@code w} values per row
     * @param pred1 the list 1 prediction, {@code w} values per row
     * @param w the block width
     * @param h the block height
     * @param bitDepth the bit depth, from 8 to 12
     */
    public abstract void putBi(short[] dst, int dstOff, int dstStride, short[] pred0, short[] pred1, int w, int h,
                               int bitDepth);

    /**
     * Writes a uni-directional prediction into a plane with explicit weighted sample prediction
     * (equation 8-265): {@code clip(((pred * weight + (1 << (log2Wd - 1))) >> log2Wd) + offset)}.
     *
     * @param dst the destination plane
     * @param dstOff the index of the block's top-left sample
     * @param dstStride the plane's row stride
     * @param pred the prediction, {@code w} values per row
     * @param w the block width
     * @param h the block height
     * @param bitDepth the bit depth, from 8 to 12
     * @param weight the weighting factor of the reference picture ({@code w0} of equation 8-265)
     * @param offset the offset, scaled to the bit depth
     * @param log2Wd the rounding shift, at least 1
     */
    public abstract void putWeightedUni(short[] dst, int dstOff, int dstStride, short[] pred, int w, int h,
                                        int bitDepth, int weight, int offset, int log2Wd);

    /**
     * Writes a bi-directional prediction into a plane with explicit weighted sample prediction
     * (equation 8-266):
     * {@code clip((pred0 * weight0 + pred1 * weight1 + ((offset0 + offset1 + 1) << log2Wd)) >> (log2Wd + 1))}.
     *
     * @param dst the destination plane
     * @param dstOff the index of the block's top-left sample
     * @param dstStride the plane's row stride
     * @param pred0 the list 0 prediction, {@code w} values per row
     * @param pred1 the list 1 prediction, {@code w} values per row
     * @param w the block width
     * @param h the block height
     * @param bitDepth the bit depth, from 8 to 12
     * @param weight0 the list 0 weight
     * @param weight1 the list 1 weight
     * @param offsets the sum of the two offsets scaled to the bit depth, {@code offset0 + offset1}
     * @param log2Wd the rounding shift, at least 1
     */
    public abstract void putWeightedBi(short[] dst, int dstOff, int dstStride, short[] pred0, short[] pred1, int w,
                                       int h, int bitDepth, int weight0, int weight1, int offsets, int log2Wd);

    /**
     * Applies an edge offset to a block whose samples may all use their two neighbors (8.7.3.2):
     * {@code dst[p] = clip(src[p] + lut[2 + sign(src[p] - src[p + deltaA]) + sign(src[p] - src[p + deltaB])])}.
     *
     * @param dst the plane receiving the offset samples
     * @param src the deblocked samples, laid out like {@code dst}
     * @param offset the index of the block's top-left sample
     * @param stride the row stride of both planes
     * @param w the block width
     * @param h the block height
     * @param deltaA the index offset of the first neighbor
     * @param deltaB the index offset of the second neighbor
     * @param lut the offsets of the values of {@code 2 + sign + sign}, from 0 to 4; entry 2 is 0
     * @param max the largest sample value
     */
    public abstract void saoEdge(short[] dst, short[] src, int offset, int stride, int w, int h, int deltaA,
                                 int deltaB, int[] lut, int max);

    /**
     * Applies a band offset to a block (8.7.3.2): the samples of the four bands from
     * {@code position} on receive the offsets {@code o0} to {@code o3}, the others are copied.
     *
     * @param dst the plane receiving the offset samples
     * @param src the deblocked samples, laid out like {@code dst}
     * @param offset the index of the block's top-left sample
     * @param stride the row stride of both planes
     * @param w the block width
     * @param h the block height
     * @param position {@code sao_band_position}, from 0 to 31
     * @param shift the band shift, {@code bitDepth - 5}
     * @param o0 the offset of band {@code position}
     * @param o1 the offset of the next band
     * @param o2 the offset of the band after
     * @param o3 the offset of the fourth band
     * @param max the largest sample value
     */
    public abstract void saoBand(short[] dst, short[] src, int offset, int stride, int w, int h, int position,
                                 int shift, int o0, int o1, int o2, int o3, int max);

    /**
     * Adds a square block of residual samples to the predicted samples of a plane and clips the
     * sums to the sample range (8.6.7).
     *
     * @param plane the plane holding the predicted samples, modified in place
     * @param offset the index of the block's top-left sample
     * @param stride the plane's row stride
     * @param residual the residual samples, {@code size} per row
     * @param size the block side, from 4 to 32
     * @param max the largest sample value
     */
    public abstract void addResidual(short[] plane, int offset, int stride, int[] residual, int size, int max);

    /**
     * Interpolates a luma block of an 8-bit plane at quarter-sample precision (8.5.3.3.3.2) into
     * 14-bit intermediate samples kept in 16 bits, as
     * {@link #lumaMc(short[], int, int, short[], int, int, int, int, int, short[])} does at bit depth
     * 8.
     *
     * @param src the reference luma plane
     * @param srcOff the index of the reference sample at the block's integer position
     * @param srcStride the plane's row stride
     * @param dst receives the prediction, {@code w} values per row
     * @param w the block width, from 4 to {@link #MAX_BLOCK}
     * @param h the block height, from 4 to {@link #MAX_BLOCK}
     * @param xFrac the horizontal quarter-sample fraction, from 0 to 3
     * @param yFrac the vertical quarter-sample fraction, from 0 to 3
     * @param scratch a scratch buffer of at least {@link #SCRATCH_SIZE} entries
     * @return {@code true} when every intermediate sample fits 16 bits
     */
    public abstract boolean lumaMc(byte[] src, int srcOff, int srcStride, short[] dst, int w, int h, int xFrac,
                                   int yFrac, short[] scratch);

    /**
     * Interpolates a chroma block of an 8-bit plane at eighth-sample precision (8.5.3.3.3.3) into
     * 14-bit intermediate samples kept in 16 bits, as
     * {@link #chromaMc(short[], int, int, short[], int, int, int, int, int, short[])} does at bit
     * depth 8.
     *
     * @param src the reference chroma plane
     * @param srcOff the index of the reference sample at the block's integer position
     * @param srcStride the plane's row stride
     * @param dst receives the prediction, {@code w} values per row
     * @param w the block width, from 2 to {@link #MAX_BLOCK}
     * @param h the block height, from 2 to {@link #MAX_BLOCK}
     * @param xFrac the horizontal eighth-sample fraction, from 0 to 7
     * @param yFrac the vertical eighth-sample fraction, from 0 to 7
     * @param scratch a scratch buffer of at least {@link #SCRATCH_SIZE} entries
     * @return {@code true}, as every intermediate chroma sample fits 16 bits
     */
    public abstract boolean chromaMc(byte[] src, int srcOff, int srcStride, short[] dst, int w, int h, int xFrac,
                                     int yFrac, short[] scratch);

    /**
     * Writes a uni-directional prediction into an 8-bit plane with the default weighted sample
     * prediction (equation 8-252): {@code clip((pred + 32) >> 6)}.
     *
     * @param dst the destination plane
     * @param dstOff the index of the block's top-left sample
     * @param dstStride the plane's row stride
     * @param pred the prediction, {@code w} values per row
     * @param w the block width
     * @param h the block height
     */
    public abstract void putUni(byte[] dst, int dstOff, int dstStride, short[] pred, int w, int h);

    /**
     * Writes a bi-directional prediction into an 8-bit plane with the default weighted sample
     * prediction (equation 8-253): {@code clip((pred0 + pred1 + 64) >> 7)}.
     *
     * @param dst the destination plane
     * @param dstOff the index of the block's top-left sample
     * @param dstStride the plane's row stride
     * @param pred0 the list 0 prediction, {@code w} values per row
     * @param pred1 the list 1 prediction, {@code w} values per row
     * @param w the block width
     * @param h the block height
     */
    public abstract void putBi(byte[] dst, int dstOff, int dstStride, short[] pred0, short[] pred1, int w, int h);

    /**
     * Writes a uni-directional prediction into an 8-bit plane with explicit weighted sample
     * prediction (equation 8-265).
     *
     * @param dst the destination plane
     * @param dstOff the index of the block's top-left sample
     * @param dstStride the plane's row stride
     * @param pred the prediction, {@code w} values per row
     * @param w the block width
     * @param h the block height
     * @param weight the weighting factor of the reference picture ({@code w0} of equation 8-265)
     * @param offset the additive offset ({@code o0} of equation 8-265)
     * @param log2Wd the rounding shift, at least 1
     */
    public abstract void putWeightedUni(byte[] dst, int dstOff, int dstStride, short[] pred, int w, int h, int weight,
                                        int offset, int log2Wd);

    /**
     * Writes a bi-directional prediction into an 8-bit plane with explicit weighted sample
     * prediction (equation 8-266).
     *
     * @param dst the destination plane
     * @param dstOff the index of the block's top-left sample
     * @param dstStride the plane's row stride
     * @param pred0 the list 0 prediction, {@code w} values per row
     * @param pred1 the list 1 prediction, {@code w} values per row
     * @param w the block width
     * @param h the block height
     * @param weight0 the list 0 weight
     * @param weight1 the list 1 weight
     * @param offsets the sum of the two offsets
     * @param log2Wd the rounding shift, at least 1
     */
    public abstract void putWeightedBi(byte[] dst, int dstOff, int dstStride, short[] pred0, short[] pred1, int w,
                                       int h, int weight0, int weight1, int offsets, int log2Wd);

    /**
     * Applies an edge offset to a block of an 8-bit plane whose samples may all use their two
     * neighbors (8.7.3.2), as
     * {@link #saoEdge(short[], short[], int, int, int, int, int, int, int[], int)} does with the
     * largest sample value 255.
     *
     * @param dst the destination plane
     * @param src the deblocked samples, laid out like {@code dst}
     * @param offset the index of the block's top-left sample
     * @param stride the row stride
     * @param w the block width
     * @param h the block height
     * @param deltaA the index distance to the first neighbor
     * @param deltaB the index distance to the second neighbor
     * @param lut the offsets indexed by {@code 2 + sign + sign}; entry 2 is 0
     */
    public abstract void saoEdge(byte[] dst, byte[] src, int offset, int stride, int w, int h, int deltaA,
                                 int deltaB, int[] lut);

    /**
     * Applies a band offset to a block of an 8-bit plane (8.7.3.2): the samples of the four bands
     * from {@code position} on receive the offsets {@code o0} to {@code o3}, the others are copied.
     *
     * @param dst the destination plane
     * @param src the deblocked samples, laid out like {@code dst}
     * @param offset the index of the block's top-left sample
     * @param stride the row stride
     * @param w the block width
     * @param h the block height
     * @param position {@code sao_band_position}
     * @param o0 the offset of the first band
     * @param o1 the offset of the second band
     * @param o2 the offset of the third band
     * @param o3 the offset of the fourth band
     */
    public abstract void saoBand(byte[] dst, byte[] src, int offset, int stride, int w, int h, int position, int o0,
                                 int o1, int o2, int o3);

    /**
     * Adds a square block of residual samples to the predicted samples of an 8-bit plane and clips
     * the sums to 0 to 255 (8.6.7).
     *
     * @param plane the plane holding the predicted samples, modified in place
     * @param offset the index of the block's top-left sample
     * @param stride the plane's row stride
     * @param residual the residual samples, {@code size} per row
     * @param size the block side, from 4 to 32
     */
    public abstract void addResidual(byte[] plane, int offset, int stride, int[] residual, int size);

    /**
     * Adds one value to every sample of a square block of an 8-bit plane and clips the sums to 0
     * to 255, as {@link #addResidual(byte[], int, int, int[], int)} does for a residual whose
     * samples are all equal.
     *
     * @param plane the plane holding the predicted samples, modified in place
     * @param offset the index of the block's top-left sample
     * @param stride the plane's row stride
     * @param size the block side, from 4 to 32
     * @param value the value to add, from -32768 to 32767
     */
    public abstract void addConstant(byte[] plane, int offset, int stride, int size, int value);

    /**
     * Computes the inverse DCT of a square block (ITU-T H.265 8.6.4.2): the vertical transform of
     * every column, its results rounded, shifted by 7 and clipped to 16 bits, then the horizontal
     * transform of every row, rounded and shifted by {@code bdShift}. Coefficients outside the
     * leading {@code columns} by {@code rows} area must be 0.
     *
     * @param coefficients the scaled transform coefficients {@code d}, {@code size * size} entries
     * @param log2Size the block size as a power of two, 2 to 5
     * @param bdShift the final shift, {@code 20 - bitDepth}, from 8 to 12
     * @param columns the number of leading columns that may hold non-zero coefficients
     * @param rows the number of leading rows that may hold non-zero coefficients
     * @param residual receives the residual samples, {@code size * size} entries
     * @param scratch a scratch buffer of {@link HevcTransform#SCRATCH_INTS} entries
     */
    public abstract void inverseDct(int[] coefficients, int log2Size, int bdShift, int columns, int rows,
                                    int[] residual, int[] scratch);

    /**
     * Applies the luma deblocking filter of ITU-T H.265 8.7.2.5.7 across a horizontal edge of an
     * 8-bit plane, 16 columns at once, with the decisions of every column already taken: a column
     * whose flags hold {@link #DEBLOCK_STRONG} receives the strong filter, one whose flags hold
     * {@link #DEBLOCK_NORMAL} the normal filter, which leaves the column unchanged when
     * {@code abs(delta) >= 10 * tC} and modifies {@code p1} ({@code q1}) only with
     * {@link #DEBLOCK_P1} ({@link #DEBLOCK_Q1}); the samples above (below) the edge change only with
     * {@link #DEBLOCK_WRITE_P} ({@link #DEBLOCK_WRITE_Q}). Columns without a filter flag keep their
     * samples.
     *
     * @param plane the plane, modified in place; the 4 rows above and below the edge must exist
     * @param origin the index of {@code q0} of the first column: the first sample below the edge
     * @param stride the plane's row stride
     * @param params {@code tC} of each column in entries 0 to 15 and its flags in entries 16 to 31
     */
    public abstract void deblockLuma(byte[] plane, int origin, int stride, short[] params);

    /**
     * Applies the luma deblocking filter of {@link #deblockLuma} across a vertical edge of an 8-bit
     * plane, 16 rows at once: row {@code i} of the edge takes the role of column {@code i}, the
     * samples left of the edge those above it, and {@code params} holds the {@code tC} and flags of
     * each row.
     *
     * @param plane the plane, modified in place; the 4 columns left and right of the edge must
     * exist in each of the 16 rows
     * @param origin the index of {@code q0} of the first row: the first sample right of the edge
     * @param stride the plane's row stride
     * @param params {@code tC} of each row in entries 0 to 15 and its flags in entries 16 to 31
     * @param scratch a scratch buffer of {@link #DEBLOCK_SCRATCH} bytes
     */
    public abstract void deblockLumaVertical(byte[] plane, int origin, int stride, short[] params, byte[] scratch);
}
