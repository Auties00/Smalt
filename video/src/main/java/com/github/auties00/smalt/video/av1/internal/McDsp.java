package com.github.auties00.smalt.video.av1.internal;

import com.github.auties00.smalt.internal.simd.Simd;

/**
 * Defines the motion compensation kernels of AV1 inter prediction: sub-pixel interpolation of
 * single predictions ({@code put}) and of compound intermediates ({@code prep}), at the native and
 * at a scaled reference resolution, the compound combiners, the overlapped block blends, the
 * affine warp, the reference edge extension and the super-resolution upscaler.
 *
 * <p>Planes are {@code short[]} arrays of unsigned samples from {@code 0} to {@code bitdepthMax},
 * addressed by an offset and a row stride in samples. Compound intermediates ({@code tmp}
 * buffers) are {@code short[]} arrays holding a block of {@code w * h} values with a row stride of
 * {@code w}; their format depends on the bit depth exactly as in dav1d: each value is the
 * interpolated sample scaled by {@code 1 << intermediateBits} ({@code 4} bits for 8- and 10-bit
 * video, {@code 2} for 12-bit) minus a bias of {@code 0} for 8-bit video and {@code 8192} for
 * higher bit depths, so it always fits in 16 bits. Only buffers produced by {@link #mct} or
 * {@link #mctScaled} at the same bit depth may be combined.
 *
 * <p>Filter selection follows dav1d's {@code Filter2d} numbering (horizontal filter first):
 * {@link #FILTER_2D_8TAP_REGULAR}, {@link #FILTER_2D_8TAP_REGULAR_SMOOTH},
 * {@link #FILTER_2D_8TAP_REGULAR_SHARP}, {@link #FILTER_2D_8TAP_SHARP_REGULAR},
 * {@link #FILTER_2D_8TAP_SHARP_SMOOTH}, {@link #FILTER_2D_8TAP_SHARP},
 * {@link #FILTER_2D_8TAP_SMOOTH_REGULAR}, {@link #FILTER_2D_8TAP_SMOOTH},
 * {@link #FILTER_2D_8TAP_SMOOTH_SHARP} and {@link #FILTER_2D_BILINEAR}. Blocks of width (height)
 * at most 4 use the 4-tap variants of the regular and smooth filters horizontally (vertically), and
 * the sharp filter falls back to the regular one there, as the AV1 specification requires.
 *
 * <p>Source reads extend past the block: an 8-tap kernel with a non-zero horizontal fraction
 * reads 3 columns before and 4 after the block, and with a non-zero vertical fraction 3 rows above
 * and 4 below; the bilinear kernel reads 1 column after and 1 row below when the matching fraction
 * is non-zero. The scaled kernels read the same margins around the scaled footprint, and
 * {@link #warp8x8} reads the 15 by 15 area starting 3 rows above and 3 columns left of the block.
 * Callers extend the reference with {@link #emuEdge} when that area leaves the picture.
 *
 * <p>Every kernel is stateless and thread-safe; the scratch buffer a kernel needs is passed by the
 * caller and must hold at least {@link #SCRATCH_SHORTS} entries.
 */
public abstract class McDsp {
    /**
     * The scalar kernels, which every JVM runs.
     */
    static final McDsp SCALAR = new ScalarMcDsp();

    /**
     * The implementation every frame uses: {@link VectorMcDsp}, written for 256-bit vectors, when
     * {@link Simd#WIDE}, {@link #SCALAR} otherwise.
     */
    private static final McDsp FIXED = Simd.WIDE ? VectorMcDsp.create() : SCALAR;

    /**
     * The minimum size of the {@code short} scratch buffer of the interpolation and warp kernels:
     * the 135 filtered rows of 128 samples of a 128 by 128 block.
     */
    public static final int SCRATCH_SHORTS = 128 * 135;

    /**
     * The minimum size of the {@code int} scratch buffer of the warp kernels: the filter positions
     * of one row of 8 samples.
     */
    public static final int WARP_SCRATCH_INTS = 8;

    /**
     * The regular 8-tap filter in both directions.
     */
    public static final int FILTER_2D_8TAP_REGULAR = 0;

    /**
     * The regular 8-tap filter horizontally and the smooth one vertically.
     */
    public static final int FILTER_2D_8TAP_REGULAR_SMOOTH = 1;

    /**
     * The regular 8-tap filter horizontally and the sharp one vertically.
     */
    public static final int FILTER_2D_8TAP_REGULAR_SHARP = 2;

    /**
     * The sharp 8-tap filter horizontally and the regular one vertically.
     */
    public static final int FILTER_2D_8TAP_SHARP_REGULAR = 3;

    /**
     * The sharp 8-tap filter horizontally and the smooth one vertically.
     */
    public static final int FILTER_2D_8TAP_SHARP_SMOOTH = 4;

    /**
     * The sharp 8-tap filter in both directions.
     */
    public static final int FILTER_2D_8TAP_SHARP = 5;

    /**
     * The smooth 8-tap filter horizontally and the regular one vertically.
     */
    public static final int FILTER_2D_8TAP_SMOOTH_REGULAR = 6;

    /**
     * The smooth 8-tap filter in both directions.
     */
    public static final int FILTER_2D_8TAP_SMOOTH = 7;

    /**
     * The smooth 8-tap filter horizontally and the sharp one vertically.
     */
    public static final int FILTER_2D_8TAP_SMOOTH_SHARP = 8;

    /**
     * The bilinear filter in both directions.
     */
    public static final int FILTER_2D_BILINEAR = 9;


    /**
     * Constructs a kernel set.
     */
    protected McDsp() {
    }

    /**
     * Returns the kernels one frame is predicted with.
     *
     * <p>This is the Vector API implementation when {@link Simd#ENABLED} and {@link #SCALAR} when
     * not. Every implementation produces the same samples, so the output never depends on the
     * selection.
     *
     * @return the kernels
     */
    public static McDsp select() {
        return FIXED;
    }

    /**
     * Interpolates a block of a reference plane at sixteenth-sample precision into a picture.
     *
     * @param filter2d the filter combination, from {@link #FILTER_2D_8TAP_REGULAR} to
     * {@link #FILTER_2D_BILINEAR}
     * @param dst the destination plane
     * @param dstOff the index of the destination top-left sample
     * @param dstStride the destination row stride
     * @param src the reference plane
     * @param srcOff the index of the reference sample at the block's integer position
     * @param srcStride the reference row stride
     * @param w the block width, from 2 to 128
     * @param h the block height, from 2 to 128
     * @param mx the horizontal fraction in sixteenths, from 0 to 15
     * @param my the vertical fraction in sixteenths, from 0 to 15
     * @param scratch a scratch buffer of at least {@link #SCRATCH_SHORTS} entries
     * @param bitdepthMax the largest sample value, {@code (1 << bitDepth) - 1}
     */
    public abstract void mc(int filter2d, short[] dst, int dstOff, int dstStride, short[] src, int srcOff,
                            int srcStride, int w, int h, int mx, int my, short[] scratch, int bitdepthMax);

    /**
     * Interpolates a block of a reference plane of a different resolution into a picture, stepping
     * through the reference by {@code dx} and {@code dy} 1/1024 samples per output sample.
     *
     * @param filter2d the filter combination, from {@link #FILTER_2D_8TAP_REGULAR} to
     * {@link #FILTER_2D_BILINEAR}
     * @param dst the destination plane
     * @param dstOff the index of the destination top-left sample
     * @param dstStride the destination row stride
     * @param src the reference plane
     * @param srcOff the index of the reference sample at the block's integer position
     * @param srcStride the reference row stride
     * @param w the block width, from 2 to 128
     * @param h the block height, from 2 to 128
     * @param mx the horizontal start fraction in 1/1024 samples, from 0 to 1023
     * @param my the vertical start fraction in 1/1024 samples, from 0 to 1023
     * @param dx the horizontal step in 1/1024 samples, from 64 to 2048
     * @param dy the vertical step in 1/1024 samples, from 64 to 2048
     * @param scratch a scratch buffer of at least {@link #SCRATCH_SHORTS} entries
     * @param bitdepthMax the largest sample value, {@code (1 << bitDepth) - 1}
     */
    public abstract void mcScaled(int filter2d, short[] dst, int dstOff, int dstStride, short[] src, int srcOff,
                                  int srcStride, int w, int h, int mx, int my, int dx, int dy, short[] scratch,
                                  int bitdepthMax);

    /**
     * Interpolates a block of a reference plane into a compound intermediate buffer.
     *
     * @param filter2d the filter combination, from {@link #FILTER_2D_8TAP_REGULAR} to
     * {@link #FILTER_2D_BILINEAR}
     * @param tmp the intermediate buffer, receiving {@code w * h} values with stride
     * {@code w}
     * @param tmpOff the index of the first intermediate value
     * @param src the reference plane
     * @param srcOff the index of the reference sample at the block's integer position
     * @param srcStride the reference row stride
     * @param w the block width, from 2 to 128
     * @param h the block height, from 2 to 128
     * @param mx the horizontal fraction in sixteenths, from 0 to 15
     * @param my the vertical fraction in sixteenths, from 0 to 15
     * @param scratch a scratch buffer of at least {@link #SCRATCH_SHORTS} entries
     * @param bitdepthMax the largest sample value, {@code (1 << bitDepth) - 1}
     */
    public abstract void mct(int filter2d, short[] tmp, int tmpOff, short[] src, int srcOff, int srcStride,
                             int w, int h, int mx, int my, short[] scratch, int bitdepthMax);

    /**
     * Interpolates a block of a reference plane of a different resolution into a compound
     * intermediate buffer.
     *
     * @param filter2d the filter combination, from {@link #FILTER_2D_8TAP_REGULAR} to
     * {@link #FILTER_2D_BILINEAR}
     * @param tmp the intermediate buffer, receiving {@code w * h} values with stride
     * {@code w}
     * @param tmpOff the index of the first intermediate value
     * @param src the reference plane
     * @param srcOff the index of the reference sample at the block's integer position
     * @param srcStride the reference row stride
     * @param w the block width, from 2 to 128
     * @param h the block height, from 2 to 128
     * @param mx the horizontal start fraction in 1/1024 samples, from 0 to 1023
     * @param my the vertical start fraction in 1/1024 samples, from 0 to 1023
     * @param dx the horizontal step in 1/1024 samples, from 64 to 2048
     * @param dy the vertical step in 1/1024 samples, from 64 to 2048
     * @param scratch a scratch buffer of at least {@link #SCRATCH_SHORTS} entries
     * @param bitdepthMax the largest sample value, {@code (1 << bitDepth) - 1}
     */
    public abstract void mctScaled(int filter2d, short[] tmp, int tmpOff, short[] src, int srcOff, int srcStride,
                                   int w, int h, int mx, int my, int dx, int dy, short[] scratch, int bitdepthMax);

    /**
     * Writes the rounded average of two compound intermediates.
     *
     * @param dst the destination plane
     * @param dstOff the index of the destination top-left sample
     * @param dstStride the destination row stride
     * @param tmp1 the first intermediate, with stride {@code w}
     * @param tmp1Off the index of its first value
     * @param tmp2 the second intermediate, with stride {@code w}
     * @param tmp2Off the index of its first value
     * @param w the block width
     * @param h the block height
     * @param bitdepthMax the largest sample value, {@code (1 << bitDepth) - 1}
     */
    public abstract void avg(short[] dst, int dstOff, int dstStride, short[] tmp1, int tmp1Off, short[] tmp2,
                             int tmp2Off, int w, int h, int bitdepthMax);

    /**
     * Writes the weighted average {@code (tmp1 * weight + tmp2 * (16 - weight)) / 16} of two
     * compound intermediates (distance-weighted compound prediction).
     *
     * @param dst the destination plane
     * @param dstOff the index of the destination top-left sample
     * @param dstStride the destination row stride
     * @param tmp1 the first intermediate, with stride {@code w}
     * @param tmp1Off the index of its first value
     * @param tmp2 the second intermediate, with stride {@code w}
     * @param tmp2Off the index of its first value
     * @param w the block width
     * @param h the block height
     * @param weight the weight of the first intermediate in sixteenths
     * @param bitdepthMax the largest sample value, {@code (1 << bitDepth) - 1}
     */
    public abstract void wAvg(short[] dst, int dstOff, int dstStride, short[] tmp1, int tmp1Off, short[] tmp2,
                              int tmp2Off, int w, int h, int weight, int bitdepthMax);

    /**
     * Blends two compound intermediates with a per-sample mask
     * {@code (tmp1 * m + tmp2 * (64 - m)) / 64} (wedge and masked compound prediction).
     *
     * @param dst the destination plane
     * @param dstOff the index of the destination top-left sample
     * @param dstStride the destination row stride
     * @param tmp1 the first intermediate, with stride {@code w}
     * @param tmp1Off the index of its first value
     * @param tmp2 the second intermediate, with stride {@code w}
     * @param tmp2Off the index of its first value
     * @param w the block width
     * @param h the block height
     * @param mask the mask, unsigned values from 0 to 64 with stride {@code w}
     * @param maskOff the index of the first mask value
     * @param bitdepthMax the largest sample value, {@code (1 << bitDepth) - 1}
     */
    public abstract void mask(short[] dst, int dstOff, int dstStride, short[] tmp1, int tmp1Off, short[] tmp2,
                              int tmp2Off, int w, int h, byte[] mask, int maskOff, int bitdepthMax);

    /**
     * Blends two compound intermediates with a mask derived from their difference
     * (difference-weighted compound prediction) and stores the mask at the chroma resolution for
     * the chroma planes.
     *
     * <p>The stored mask has {@code w >> ssHor} columns and {@code h >> ssVer} rows with a stride
     * of {@code w >> ssHor}, where the subsampling follows {@code layoutIndex}. With vertical
     * subsampling, each even row's partial sum is stored first and combined with the odd row, so
     * the mask buffer must not be shared with another writer during the call.
     *
     * @param layoutIndex the chroma layout of the stored mask: {@code 0} for 4:4:4, {@code 1} for
     * 4:2:2, {@code 2} for 4:2:0
     * @param dst the destination plane
     * @param dstOff the index of the destination top-left sample
     * @param dstStride the destination row stride
     * @param tmp1 the first intermediate, with stride {@code w}
     * @param tmp1Off the index of its first value
     * @param tmp2 the second intermediate, with stride {@code w}
     * @param tmp2Off the index of its first value
     * @param w the block width, even when subsampled horizontally
     * @param h the block height, even when subsampled vertically
     * @param mask receives the subsampled mask, unsigned values from 0 to 64
     * @param maskOff the index of the first mask value
     * @param sign {@code 1} when the mask selects the second prediction, else {@code 0};
     * it biases the rounding of the subsampled mask
     * @param bitdepthMax the largest sample value, {@code (1 << bitDepth) - 1}
     */
    public abstract void wMask(int layoutIndex, short[] dst, int dstOff, int dstStride, short[] tmp1, int tmp1Off,
                               short[] tmp2, int tmp2Off, int w, int h, byte[] mask, int maskOff, int sign,
                               int bitdepthMax);

    /**
     * Blends a prediction into a picture with a per-sample mask
     * {@code (dst * (64 - m) + tmp * m) / 64} (inter-intra prediction).
     *
     * @param dst the destination plane, read and written
     * @param dstOff the index of the destination top-left sample
     * @param dstStride the destination row stride
     * @param tmp the prediction to blend in, with stride {@code w}
     * @param tmpOff the index of its first sample
     * @param w the block width
     * @param h the block height
     * @param mask the mask, unsigned values from 0 to 64 with stride {@code w}
     * @param maskOff the index of the first mask value
     */
    public abstract void blend(short[] dst, int dstOff, int dstStride, short[] tmp, int tmpOff, int w, int h,
                               byte[] mask, int maskOff);

    /**
     * Blends the prediction of the left neighbor's motion into the left columns of a block
     * (overlapped block motion compensation), with the column weights of
     * {@link McTables#OBMC_MASKS}; only the left {@code 3 * w / 4} columns change.
     *
     * @param dst the destination plane, read and written
     * @param dstOff the index of the destination top-left sample
     * @param dstStride the destination row stride
     * @param tmp the neighbor's prediction, with stride {@code w}
     * @param tmpOff the index of its first sample
     * @param w the blended width, from 2 to 32
     * @param h the block height
     */
    public abstract void blendV(short[] dst, int dstOff, int dstStride, short[] tmp, int tmpOff, int w, int h);

    /**
     * Blends the prediction of the above neighbor's motion into the top rows of a block
     * (overlapped block motion compensation), with the row weights of
     * {@link McTables#OBMC_MASKS}; only the top {@code 3 * h / 4} rows change.
     *
     * @param dst the destination plane, read and written
     * @param dstOff the index of the destination top-left sample
     * @param dstStride the destination row stride
     * @param tmp the neighbor's prediction, with stride {@code w}
     * @param tmpOff the index of its first sample
     * @param w the block width
     * @param h the blended height, from 2 to 32
     */
    public abstract void blendH(short[] dst, int dstOff, int dstStride, short[] tmp, int tmpOff, int w, int h);

    /**
     * Predicts an 8 by 8 block with an affine warp of the reference into a picture.
     *
     * <p>The filter of each sample is selected by its position {@code mx + x * abcd[0] + y *
     * abcd[1]} horizontally and {@code my + x * abcd[2] + y * abcd[3]} vertically, in 1/65536
     * samples relative to the block's integer position.
     *
     * @param dst the destination plane
     * @param dstOff the index of the destination top-left sample
     * @param dstStride the destination row stride
     * @param src the reference plane
     * @param srcOff the index of the reference sample at the block's integer position
     * @param srcStride the reference row stride
     * @param abcd the shear parameters {@code alpha, beta, gamma, delta} at indices 0 to 3
     * @param mx the horizontal filter position of the top-left sample
     * @param my the vertical filter position of the top-left sample
     * @param scratch a scratch buffer of at least {@link #SCRATCH_SHORTS} entries
     * @param indices a scratch buffer of at least {@link #WARP_SCRATCH_INTS} entries
     * @param bitdepthMax the largest sample value, {@code (1 << bitDepth) - 1}
     */
    public abstract void warp8x8(short[] dst, int dstOff, int dstStride, short[] src, int srcOff, int srcStride,
                                 short[] abcd, int mx, int my, short[] scratch, int[] indices, int bitdepthMax);

    /**
     * Predicts an 8 by 8 block with an affine warp of the reference into a compound intermediate
     * buffer, as {@link #warp8x8} does.
     *
     * @param tmp the intermediate buffer
     * @param tmpOff the index of the block's first intermediate value
     * @param tmpStride the intermediate row stride, in values
     * @param src the reference plane
     * @param srcOff the index of the reference sample at the block's integer position
     * @param srcStride the reference row stride
     * @param abcd the shear parameters {@code alpha, beta, gamma, delta} at indices 0 to 3
     * @param mx the horizontal filter position of the top-left sample
     * @param my the vertical filter position of the top-left sample
     * @param scratch a scratch buffer of at least {@link #SCRATCH_SHORTS} entries
     * @param indices a scratch buffer of at least {@link #WARP_SCRATCH_INTS} entries
     * @param bitdepthMax the largest sample value, {@code (1 << bitDepth) - 1}
     */
    public abstract void warp8x8t(short[] tmp, int tmpOff, int tmpStride, short[] src, int srcOff, int srcStride,
                                  short[] abcd, int mx, int my, short[] scratch, int[] indices, int bitdepthMax);

    /**
     * Copies a block of a reference plane whose area may leave the plane, replicating the nearest
     * edge samples into the outside part.
     *
     * <p>The block must overlap the plane by at least one column and one row: {@code x} lies in
     * {@code [-(bw - 1), iw - 1]} and {@code y} in {@code [-(bh - 1), ih - 1]}.
     *
     * @param bw the block width
     * @param bh the block height
     * @param iw the plane width
     * @param ih the plane height
     * @param x the column of the block's left edge in the plane, possibly negative
     * @param y the row of the block's top edge in the plane, possibly negative
     * @param dst the destination buffer
     * @param dstOff the index of the destination top-left sample
     * @param dstStride the destination row stride
     * @param ref the reference plane
     * @param refOff the index of the plane's top-left sample
     * @param refStride the reference row stride
     */
    public abstract void emuEdge(int bw, int bh, int iw, int ih, int x, int y, short[] dst, int dstOff,
                                 int dstStride, short[] ref, int refOff, int refStride);

    /**
     * Upscales rows horizontally with the 8-tap super-resolution filter, replicating the row's end
     * samples past its edges.
     *
     * @param dst the destination plane
     * @param dstOff the index of the destination top-left sample
     * @param dstStride the destination row stride
     * @param src the source plane
     * @param srcOff the index of the source top-left sample
     * @param srcStride the source row stride
     * @param dstW the upscaled row width
     * @param h the number of rows
     * @param srcW the source row width
     * @param dx the source step per output sample in 1/16384 samples
     * @param mx0 the initial source position fraction in 1/16384 samples
     * @param bitdepthMax the largest sample value, {@code (1 << bitDepth) - 1}
     */
    public abstract void resize(short[] dst, int dstOff, int dstStride, short[] src, int srcOff, int srcStride,
                                int dstW, int h, int srcW, int dx, int mx0, int bitdepthMax);
}
