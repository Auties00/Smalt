package com.github.auties00.smalt.video.mpeg4.internal;

import com.github.auties00.smalt.internal.simd.Simd;

/**
 * Defines the hot sample-processing kernels of MPEG-4 Visual and H.263 reconstruction: the 8x8
 * inverse DCT with its store or add into a plane, the half sample bilinear motion compensation of
 * ISO/IEC 14496-2 7.6.2.1 (ITU-T H.263 6.1.2), the averaging of bidirectional predictions, and the
 * quarter sample interpolation of 7.6.2.2.
 *
 * <p>Every kernel exists twice: {@link ScalarMpeg4Dsp} is the reference implementation, and a
 * Vector API implementation overrides the kernels that profit from SIMD; both produce the same
 * samples for every input. {@link #select()} returns the implementation a picture is reconstructed
 * with. Implementations are stateless and thread-safe; callers pass the scratch buffers a kernel
 * needs.
 *
 * <p>Planes are {@code byte[]} arrays of unsigned 8-bit samples addressed by an offset and a row
 * stride; a field is addressed with twice the frame stride. Coefficient blocks are 64
 * {@code short} values in raster order, which the inverse transforms clear after use.
 */
public abstract class Mpeg4Dsp {
    /**
     * The scalar kernels, which every JVM runs.
     */
    static final Mpeg4Dsp SCALAR = new ScalarMpeg4Dsp();

    /**
     * The implementation every picture uses: the Vector API kernels, written for 256-bit vectors,
     * when {@link Simd#WIDE}, {@link #SCALAR} otherwise.
     */
    private static final Mpeg4Dsp FIXED = Simd.WIDE ? VectorMpeg4Dsp.create() : SCALAR;

    /**
     * The minimum size of the {@code int} scratch buffer of {@link #quarterPel}: the reference
     * samples of a 16 by 16 block and its horizontally interpolated rows, each with its mirrored
     * margins.
     */
    public static final int SCRATCH_INTS = 2 * 24 * 24;

    /**
     * Constructs a kernel set.
     */
    protected Mpeg4Dsp() {
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
    public static Mpeg4Dsp select() {
        return FIXED;
    }

    /**
     * Transforms a coefficient block and stores the samples, clipped to 0..255, into a plane.
     *
     * @param coefficients the coefficients in column-major order (coefficient {@code column} of row
     * {@code row} at {@code offset + column * 8 + row}); cleared on return
     * @param offset the index of the block's first coefficient
     * @param dst the plane
     * @param dstOffset the index of the block's top-left sample
     * @param stride the row stride of the destination
     * @param work a scratch array of at least 64 integers
     */
    public abstract void idctPut(short[] coefficients, int offset, byte[] dst, int dstOffset, int stride, int[] work);

    /**
     * Transforms a coefficient block and adds the residual to the prediction already in a plane,
     * clipping the sums to 0..255.
     *
     * @param coefficients the coefficients in column-major order (coefficient {@code column} of row
     * {@code row} at {@code offset + column * 8 + row}); cleared on return
     * @param offset the index of the block's first coefficient
     * @param dst the plane holding the prediction
     * @param dstOffset the index of the block's top-left sample
     * @param stride the row stride of the destination
     * @param work a scratch array of at least 64 integers
     */
    public abstract void idctAdd(short[] coefficients, int offset, byte[] dst, int dstOffset, int stride, int[] work);

    /**
     * Transforms a coefficient block into the residual samples, without clipping, with the inverse DCT the kernels
     * use.
     *
     * @param coefficients the coefficients in column-major order (coefficient {@code column} of row {@code row} at
     * {@code offset + column * 8 + row}); cleared on return
     * @param offset the index of the block's first coefficient
     * @param out receives the 64 samples in raster order
     */
    public static void inverseTransform(short[] coefficients, int offset, int[] out) {
        ScalarMpeg4Dsp.transform(coefficients, offset, out);
    }

    /**
     * Predicts a block by half sample bilinear interpolation (ISO/IEC 14496-2 Figure 7-18).
     *
     * @param src the reference plane
     * @param srcOffset the index of the reference sample at the block's integer position
     * @param srcStride the row stride of the reference
     * @param dst the destination plane
     * @param dstOffset the index of the destination's top-left sample
     * @param dstStride the row stride of the destination
     * @param width the block width, 4, 8 or 16
     * @param height the block height, from 1 to 16
     * @param halfX the horizontal half sample flag, 0 or 1
     * @param halfY the vertical half sample flag, 0 or 1
     * @param rounding the rounding control, 0 or 1
     */
    public abstract void halfPel(byte[] src, int srcOffset, int srcStride, byte[] dst, int dstOffset, int dstStride,
                                 int width, int height, int halfX, int halfY, int rounding);

    /**
     * Averages a second prediction into a block: {@code dst = (dst + src + 1) >> 1}.
     *
     * @param src the second prediction
     * @param srcOffset the index of its top-left sample
     * @param srcStride its row stride
     * @param dst the plane holding the first prediction
     * @param dstOffset the index of the block's top-left sample
     * @param dstStride the row stride of the destination
     * @param width the block width
     * @param height the block height
     */
    public abstract void average(byte[] src, int srcOffset, int srcStride, byte[] dst, int dstOffset, int dstStride,
                                 int width, int height);

    /**
     * Predicts a block at quarter sample precision (ISO/IEC 14496-2 7.6.2.2): the block of
     * {@code width + 1} by {@code height + 1} reference samples at the integer position is mirrored
     * by three samples at its boundaries and interpolated separably, first every row to the
     * horizontal fraction, then every column of that result to the vertical fraction. In each
     * direction a half sample comes from the 8-tap filter, and a quarter sample is the rounded
     * average of that half sample and the nearer sample of the pass's input; both roundings follow
     * the rounding control.
     *
     * @param src the reference plane
     * @param srcOffset the index of the reference sample at the block's integer position
     * @param srcStride the row stride of the reference
     * @param dst the destination plane
     * @param dstOffset the index of the destination's top-left sample
     * @param dstStride the row stride of the destination
     * @param width the block width, 8 or 16
     * @param height the block height, 8 or 16
     * @param quarterX the horizontal quarter sample fraction, from 0 to 3
     * @param quarterY the vertical quarter sample fraction, from 0 to 3
     * @param rounding the rounding control, 0 or 1
     * @param scratch a scratch buffer of at least {@link #SCRATCH_INTS} values
     */
    public abstract void quarterPel(byte[] src, int srcOffset, int srcStride, byte[] dst, int dstOffset,
                                    int dstStride, int width, int height, int quarterX, int quarterY, int rounding,
                                    int[] scratch);
}
