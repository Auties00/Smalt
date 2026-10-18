/* Ports libvpx (vpx_dsp). Copyright (c) 2010, The WebM Project authors; BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.video.vp9.internal;

import com.github.auties00.smalt.internal.simd.Simd;

/**
 * Implements the 8-bit pixel kernels of the VP9 decoder: inverse transforms, sub-pixel motion
 * compensation, loop filters and intra predictors.
 *
 * <p>Two implementations exist: {@link ScalarVp9Dsp}, the bit-exact reference ported from
 * libvpx's C code, and {@link VectorVp9Dsp}, which uses the Vector API and produces identical
 * output for every input a conformant stream can produce. {@link #INSTANCE} holds the one
 * selected for this JVM. Implementations are stateless and safe for concurrent use; kernels that
 * need scratch memory receive it from the caller.
 *
 * <p>Every kernel addresses a plane as a {@code byte[]} with an offset and a row stride. The
 * caller guarantees that every sample a kernel reads or writes, including the samples a vector
 * kernel touches past the end of a row (up to 32), lies inside the array.
 */
abstract sealed class Vp9Dsp permits ScalarVp9Dsp, VectorVp9Dsp {
    /**
     * The implementation selected by {@link Simd#ENABLED}.
     */
    public static final Vp9Dsp INSTANCE = Simd.ENABLED ? VectorVp9Dsp.create() : new ScalarVp9Dsp();

    /**
     * The minimum size of the {@code int} scratch array of {@link #inverseTransformAdd}: an
     * intermediate 32x32 block, 66 vectors of 8 values that the stages of the vector 32-point
     * transform hand to one another, and the input and output vectors of the column pass.
     */
    public static final int TRANSFORM_SCRATCH = 32 * 32 + 66 * 8 + 5 * 32 * 8;

    /**
     * The minimum size of the byte scratch array of {@link #convolve}: the intermediate rows of a
     * two-dimensional filter of a 64x64 block scaled by up to two, plus the averaged result.
     */
    public static final int CONVOLVE_SCRATCH = 64 * 135 + 64 * 64;

    /**
     * The minimum size of the byte scratch array of the loop filters: 44 rows of 16 bytes holding
     * the samples across an edge, the thresholds and the intermediate results of the vector
     * kernels.
     */
    public static final int LOOP_FILTER_SCRATCH = 44 * 16;

    /**
     * Creates an implementation.
     */
    Vp9Dsp() {
    }

    /**
     * Returns where {@link #inverseTransformAdd} expects each coefficient of a block: entry
     * {@code r * size + c} is the index, relative to the block's first coefficient, of the
     * coefficient of row {@code r} and column {@code c}, below 1024.
     *
     * @param txSize the transform size, {@link Vp9Constants#TX_4X4} to {@link Vp9Constants#TX_32X32}
     * @return the layout, shared and not to be modified
     */
    public abstract short[] coefficientLayout(int txSize);

    /**
     * Adds the inverse transform of a block of dequantized coefficients to the prediction in
     * place, clamping the result to {@code [0, 255]} ({@code vp9_iht*_add}, {@code vp9_idct*_add}).
     *
     * <p>The coefficients are in raster order, {@code 4 << txSize} per row. The kernel reads the
     * coefficients but does not clear them. {@code eob}, the number of coded coefficients in scan
     * order, selects the same reduced transforms libvpx uses for blocks whose coefficients all lie
     * in the top-left corner; the result is identical to the full transform.
     *
     * @param txSize the transform size, {@link Vp9Constants#TX_4X4} to {@link Vp9Constants#TX_32X32}
     * @param txType the transform type, {@link Vp9Constants#DCT_DCT} for 32x32
     * @param eob the number of coded coefficients, positive
     * @param coef the coefficients
     * @param coefOff the index of the first coefficient
     * @param dst the plane holding the prediction
     * @param dstOff the index of the block's top-left sample
     * @param stride the plane's row stride
     * @param scratch a scratch array of at least {@link #TRANSFORM_SCRATCH} entries
     */
    public abstract void inverseTransformAdd(int txSize, int txType, int eob, int[] coef, int coefOff, byte[] dst,
                                             int dstOff, int stride, int[] scratch);

    /**
     * Adds the inverse Walsh-Hadamard transform of a lossless 4x4 block to the prediction in
     * place ({@code vp9_iwht4x4_add}).
     *
     * @param eob the number of coded coefficients, positive
     * @param coef the coefficients in raster order
     * @param coefOff the index of the first coefficient
     * @param dst the plane holding the prediction
     * @param dstOff the index of the block's top-left sample
     * @param stride the plane's row stride
     * @param scratch a scratch array of at least 16 entries
     */
    public abstract void inverseWhtAdd(int eob, int[] coef, int coefOff, byte[] dst, int dstOff, int stride,
                                       int[] scratch);

    /**
     * Predicts a block from a reference with the VP9 eight-tap sub-pixel filters, optionally
     * averaging the prediction into the destination ({@code vpx_convolve8} and its variants).
     *
     * <p>Sample {@code (x, y)} of the block reads the reference around position
     * {@code (subX + x * xStep) / 16} columns and {@code (subY + y * yStep) / 16} rows from
     * {@code src}, at the filter phase given by the low four bits. When both steps are 16 the
     * block is unscaled: a zero phase copies, one nonzero phase filters in one direction, and two
     * filter horizontally into an intermediate 8-bit buffer and then vertically. Scaled blocks
     * always filter in both directions. With {@code average} set the result is averaged with the
     * destination, rounding up.
     *
     * @param src the reference plane
     * @param srcOff the index of the reference sample at the block's integer position
     * @param srcStride the reference row stride
     * @param dst the destination plane
     * @param dstOff the index of the block's top-left sample
     * @param dstStride the destination row stride
     * @param kernels the filter kernels, {@link Vp9Constants#FILTER_KERNELS}
     * @param kernelBase the index of phase 0 of the block's filter type in {@code kernels}
     * @param subX the horizontal phase of the first column, from 0 to 15
     * @param xStep the horizontal step in sixteenths, 16 when unscaled, at most 32
     * @param subY the vertical phase of the first row, from 0 to 15
     * @param yStep the vertical step in sixteenths, 16 when unscaled, at most 32
     * @param w the block width, at most 64
     * @param h the block height, at most 64
     * @param average whether to average into the destination
     * @param scratch a scratch array of at least {@link #CONVOLVE_SCRATCH} bytes
     */
    public abstract void convolve(byte[] src, int srcOff, int srcStride, byte[] dst, int dstOff, int dstStride,
                                  short[] kernels, int kernelBase, int subX, int xStep, int subY, int yStep,
                                  int w, int h, boolean average, byte[] scratch);

    /**
     * Applies the 4-tap loop filter across a horizontal edge of 8 columns
     * ({@code vpx_lpf_horizontal_4}).
     *
     * @param s the plane
     * @param off the index of the first sample below the edge
     * @param pitch the row stride
     * @param blimit the edge limit
     * @param limit the interior limit
     * @param thresh the high edge variance threshold
     * @param scratch a scratch array of at least {@link #LOOP_FILTER_SCRATCH} bytes
     */
    public abstract void lpfHorizontal4(byte[] s, int off, int pitch, int blimit, int limit, int thresh, byte[] scratch);

    /**
     * Applies the 4-tap loop filter across a horizontal edge of two 8-column units with their own
     * thresholds ({@code vpx_lpf_horizontal_4_dual}).
     *
     * @param s the plane
     * @param off the index of the first sample below the edge
     * @param pitch the row stride
     * @param blimit0 the edge limit of the first unit
     * @param limit0 the interior limit of the first unit
     * @param thresh0 the threshold of the first unit
     * @param blimit1 the edge limit of the second unit
     * @param limit1 the interior limit of the second unit
     * @param thresh1 the threshold of the second unit
     * @param scratch a scratch array of at least {@link #LOOP_FILTER_SCRATCH} bytes
     */
    public abstract void lpfHorizontal4Dual(byte[] s, int off, int pitch, int blimit0, int limit0, int thresh0,
                                            int blimit1, int limit1, int thresh1, byte[] scratch);

    /**
     * Applies the 8-tap loop filter across a horizontal edge of 8 columns
     * ({@code vpx_lpf_horizontal_8}).
     *
     * @param s the plane
     * @param off the index of the first sample below the edge
     * @param pitch the row stride
     * @param blimit the edge limit
     * @param limit the interior limit
     * @param thresh the high edge variance threshold
     * @param scratch a scratch array of at least {@link #LOOP_FILTER_SCRATCH} bytes
     */
    public abstract void lpfHorizontal8(byte[] s, int off, int pitch, int blimit, int limit, int thresh, byte[] scratch);

    /**
     * Applies the 8-tap loop filter across a horizontal edge of two 8-column units with their own
     * thresholds ({@code vpx_lpf_horizontal_8_dual}).
     *
     * @param s the plane
     * @param off the index of the first sample below the edge
     * @param pitch the row stride
     * @param blimit0 the edge limit of the first unit
     * @param limit0 the interior limit of the first unit
     * @param thresh0 the threshold of the first unit
     * @param blimit1 the edge limit of the second unit
     * @param limit1 the interior limit of the second unit
     * @param thresh1 the threshold of the second unit
     * @param scratch a scratch array of at least {@link #LOOP_FILTER_SCRATCH} bytes
     */
    public abstract void lpfHorizontal8Dual(byte[] s, int off, int pitch, int blimit0, int limit0, int thresh0,
                                            int blimit1, int limit1, int thresh1, byte[] scratch);

    /**
     * Applies the 16-tap loop filter across a horizontal edge, over {@code count} times 8 columns
     * with one set of thresholds ({@code vpx_lpf_horizontal_16} and {@code _16_dual}).
     *
     * @param s the plane
     * @param off the index of the first sample below the edge
     * @param pitch the row stride
     * @param blimit the edge limit
     * @param limit the interior limit
     * @param thresh the high edge variance threshold
     * @param count the number of 8-column units, 1 or 2
     * @param scratch a scratch array of at least {@link #LOOP_FILTER_SCRATCH} bytes
     */
    public abstract void lpfHorizontal16(byte[] s, int off, int pitch, int blimit, int limit, int thresh, int count, byte[] scratch);

    /**
     * Applies the 4-tap loop filter across a vertical edge of 8 rows ({@code vpx_lpf_vertical_4}).
     *
     * @param s the plane
     * @param off the index of the first sample right of the edge, in the first row
     * @param pitch the row stride
     * @param blimit the edge limit
     * @param limit the interior limit
     * @param thresh the high edge variance threshold
     * @param scratch a scratch array of at least {@link #LOOP_FILTER_SCRATCH} bytes
     */
    public abstract void lpfVertical4(byte[] s, int off, int pitch, int blimit, int limit, int thresh, byte[] scratch);

    /**
     * Applies the 4-tap loop filter across a vertical edge of two 8-row units with their own
     * thresholds ({@code vpx_lpf_vertical_4_dual}).
     *
     * @param s the plane
     * @param off the index of the first sample right of the edge, in the first row
     * @param pitch the row stride
     * @param blimit0 the edge limit of the first unit
     * @param limit0 the interior limit of the first unit
     * @param thresh0 the threshold of the first unit
     * @param blimit1 the edge limit of the second unit
     * @param limit1 the interior limit of the second unit
     * @param thresh1 the threshold of the second unit
     * @param scratch a scratch array of at least {@link #LOOP_FILTER_SCRATCH} bytes
     */
    public abstract void lpfVertical4Dual(byte[] s, int off, int pitch, int blimit0, int limit0, int thresh0,
                                          int blimit1, int limit1, int thresh1, byte[] scratch);

    /**
     * Applies the 8-tap loop filter across a vertical edge of 8 rows ({@code vpx_lpf_vertical_8}).
     *
     * @param s the plane
     * @param off the index of the first sample right of the edge, in the first row
     * @param pitch the row stride
     * @param blimit the edge limit
     * @param limit the interior limit
     * @param thresh the high edge variance threshold
     * @param scratch a scratch array of at least {@link #LOOP_FILTER_SCRATCH} bytes
     */
    public abstract void lpfVertical8(byte[] s, int off, int pitch, int blimit, int limit, int thresh, byte[] scratch);

    /**
     * Applies the 8-tap loop filter across a vertical edge of two 8-row units with their own
     * thresholds ({@code vpx_lpf_vertical_8_dual}).
     *
     * @param s the plane
     * @param off the index of the first sample right of the edge, in the first row
     * @param pitch the row stride
     * @param blimit0 the edge limit of the first unit
     * @param limit0 the interior limit of the first unit
     * @param thresh0 the threshold of the first unit
     * @param blimit1 the edge limit of the second unit
     * @param limit1 the interior limit of the second unit
     * @param thresh1 the threshold of the second unit
     * @param scratch a scratch array of at least {@link #LOOP_FILTER_SCRATCH} bytes
     */
    public abstract void lpfVertical8Dual(byte[] s, int off, int pitch, int blimit0, int limit0, int thresh0,
                                          int blimit1, int limit1, int thresh1, byte[] scratch);

    /**
     * Applies the 16-tap loop filter across a vertical edge, over {@code rows} rows with one set
     * of thresholds ({@code vpx_lpf_vertical_16} and {@code _16_dual}).
     *
     * @param s the plane
     * @param off the index of the first sample right of the edge, in the first row
     * @param pitch the row stride
     * @param blimit the edge limit
     * @param limit the interior limit
     * @param thresh the high edge variance threshold
     * @param rows the number of rows, 8 or 16
     * @param scratch a scratch array of at least {@link #LOOP_FILTER_SCRATCH} bytes
     */
    public abstract void lpfVertical16(byte[] s, int off, int pitch, int blimit, int limit, int thresh, int rows, byte[] scratch);

    /**
     * Predicts a square block from its decoded neighbors ({@code vpx_*_predictor_NxN}).
     *
     * <p>{@code above} holds the row above the block from {@code aboveOff}: the top-left
     * neighbor at {@code aboveOff - 1}, then {@code 2 << (txSize + 2)} samples (the above-right
     * half already extended by the caller). {@code left} holds the column left of the block from
     * {@code leftOff}. For {@link Vp9Constants#DC_PRED}, {@code dcEdges} selects which edges
     * the average uses: bit 0 the above row, bit 1 the left column; with neither the block is
     * filled with 128.
     *
     * @param mode the intra mode, {@link Vp9Constants#DC_PRED} to {@link Vp9Constants#TM_PRED}
     * @param txSize the block size, {@link Vp9Constants#TX_4X4} to {@link Vp9Constants#TX_32X32}
     * @param dcEdges the edges available to the DC predictor
     * @param dst the destination plane
     * @param dstOff the index of the block's top-left sample
     * @param stride the destination row stride
     * @param above the array holding the above row
     * @param aboveOff the index of the first above sample
     * @param left the array holding the left column
     * @param leftOff the index of the first left sample
     */
    public abstract void intraPredict(int mode, int txSize, int dcEdges, byte[] dst, int dstOff, int stride,
                                      byte[] above, int aboveOff, byte[] left, int leftOff);
}
