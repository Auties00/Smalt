package com.github.auties00.smalt.video.vp8.internal;

import com.github.auties00.smalt.internal.simd.Simd;

/**
 * Implements the pixel kernels of the VP8 decoder: inverse transforms, motion compensation
 * filters, and loop filters.
 *
 * <p>Two implementations exist: {@link ScalarVp8Dsp}, the bit-exact reference ported from libvpx's
 * C code, and {@link VectorVp8Dsp}, which uses the Vector API and produces identical output.
 * {@link #INSTANCE} holds the one selected for this JVM. Implementations are stateless and safe for
 * concurrent use; kernels that need scratch memory receive it from the caller.
 *
 * <p>Every kernel addresses a plane as a {@code byte[]} with an offset and a row stride. Offsets
 * may point anywhere inside the plane's border, so reads to the left of and above the addressed
 * block are valid as long as the caller guarantees the border is wide enough.
 */
public abstract sealed class Vp8Dsp permits ScalarVp8Dsp, VectorVp8Dsp {
    /**
     * The scalar implementation, which every JVM runs.
     */
    public static final Vp8Dsp SCALAR = new ScalarVp8Dsp();

    /**
     * The implementation selected by {@link Simd#ENABLED}.
     */
    public static final Vp8Dsp INSTANCE = Simd.ENABLED ? VectorVp8Dsp.create() : SCALAR;

    /**
     * The loop filter edge flag for the left macroblock edge.
     */
    public static final int EDGE_LEFT = 1;

    /**
     * The loop filter edge flag for the top macroblock edge.
     */
    public static final int EDGE_TOP = 2;

    /**
     * The loop filter edge flag for the inner subblock edges.
     */
    public static final int EDGE_INNER = 4;

    /**
     * The minimum size of the scratch array passed to the motion compensation and loop filter
     * kernels.
     */
    public static final int SCRATCH_SIZE = 1024;

    /**
     * Creates an implementation.
     */
    Vp8Dsp() {
    }

    /**
     * Applies the inverse DCT to one 4x4 block of dequantized coefficients, adds the result to
     * the prediction in place, and clears the coefficients.
     *
     * <p>Intermediate values are truncated to 16 bits exactly as libvpx's
     * {@code vp8_short_idct4x4llm_c}, so overflowing input decodes identically.
     *
     * @param coef    the dequantized coefficients in raster order
     * @param coefOff the index of the first coefficient
     * @param dst     the plane holding the prediction
     * @param dstOff  the index of the block's top-left pixel
     * @param stride  the plane's row stride
     */
    public abstract void idctAdd(short[] coef, int coefOff, byte[] dst, int dstOff, int stride);

    /**
     * Adds the inverse transform of a block whose only nonzero coefficient is the DC to the
     * prediction in place.
     *
     * @param dc     the dequantized DC coefficient, already truncated to 16 bits
     * @param dst    the plane holding the prediction
     * @param dstOff the index of the block's top-left pixel
     * @param stride the plane's row stride
     */
    public abstract void dcOnlyAdd(int dc, byte[] dst, int dstOff, int stride);

    /**
     * Predicts a block with the VP8 six-tap sub-pixel filters (RFC 6386 section 18.3).
     *
     * <p>The horizontal filter runs first over {@code height + 5} rows starting two rows above
     * the block, then the vertical filter; each pass rounds, shifts by 7, and clamps to
     * {@code [0, 255]}. A zero fraction selects the identity filter.
     *
     * @param src       the reference plane
     * @param srcOff    the index of the reference pixel at the block's full-pixel position
     * @param srcStride the reference plane's row stride
     * @param dst       the destination plane
     * @param dstOff    the index of the destination block's top-left pixel
     * @param dstStride the destination plane's row stride
     * @param width     the block width: 4, 8, or 16
     * @param height    the block height: 4, 8, or 16
     * @param mx        the horizontal fraction in eighths, {@code [0, 7]}
     * @param my        the vertical fraction in eighths, {@code [0, 7]}
     * @param scratch   a scratch array of at least {@link #SCRATCH_SIZE} bytes
     */
    public abstract void sixtap(byte[] src, int srcOff, int srcStride, byte[] dst, int dstOff, int dstStride,
                                int width, int height, int mx, int my, byte[] scratch);

    /**
     * Predicts a block with the VP8 bilinear sub-pixel filters used by bitstream versions 1 to 3.
     *
     * <p>The horizontal filter runs first over {@code height + 1} rows, then the vertical filter;
     * each pass rounds and shifts by 7.
     *
     * @param src       the reference plane
     * @param srcOff    the index of the reference pixel at the block's full-pixel position
     * @param srcStride the reference plane's row stride
     * @param dst       the destination plane
     * @param dstOff    the index of the destination block's top-left pixel
     * @param dstStride the destination plane's row stride
     * @param width     the block width: 4, 8, or 16
     * @param height    the block height: 4, 8, or 16
     * @param mx        the horizontal fraction in eighths, {@code [0, 7]}
     * @param my        the vertical fraction in eighths, {@code [0, 7]}
     * @param scratch   a scratch array of at least {@link #SCRATCH_SIZE} bytes
     */
    public abstract void bilinear(byte[] src, int srcOff, int srcStride, byte[] dst, int dstOff, int dstStride,
                                  int width, int height, int mx, int my, byte[] scratch);

    /**
     * Applies the normal loop filter (RFC 6386 section 15.3) to the selected edges of one
     * macroblock, luma and chroma.
     *
     * <p>Within each plane the left macroblock edge is filtered first, then the inner vertical
     * edges, the top macroblock edge, and the inner horizontal edges.
     *
     * @param y             the luma plane
     * @param yOff          the index of the macroblock's top-left luma pixel
     * @param yStride       the luma row stride
     * @param u             the Cb plane
     * @param v             the Cr plane
     * @param uvOff         the index of the macroblock's top-left chroma pixel
     * @param uvStride      the chroma row stride
     * @param mbLimit       the edge limit of macroblock edges
     * @param subLimit      the edge limit of inner edges
     * @param interiorLimit the interior difference limit
     * @param hevThreshold  the high edge variance threshold
     * @param edges         the union of {@link #EDGE_LEFT}, {@link #EDGE_TOP}, and
     *                      {@link #EDGE_INNER} selecting the edges to filter
     * @param scratch       a scratch array of at least {@link #SCRATCH_SIZE} bytes
     */
    public abstract void filterNormal(byte[] y, int yOff, int yStride, byte[] u, byte[] v, int uvOff, int uvStride,
                                      int mbLimit, int subLimit, int interiorLimit, int hevThreshold, int edges,
                                      byte[] scratch);

    /**
     * Applies the simple loop filter (RFC 6386 section 15.2) to the selected luma edges of one
     * macroblock, in the same order as {@link #filterNormal}.
     *
     * @param y        the luma plane
     * @param yOff     the index of the macroblock's top-left luma pixel
     * @param yStride  the luma row stride
     * @param mbLimit  the edge limit of macroblock edges
     * @param subLimit the edge limit of inner edges
     * @param edges    the union of {@link #EDGE_LEFT}, {@link #EDGE_TOP}, and {@link #EDGE_INNER}
     * @param scratch  a scratch array of at least {@link #SCRATCH_SIZE} bytes
     */
    public abstract void filterSimple(byte[] y, int yOff, int yStride, int mbLimit, int subLimit, int edges,
                                      byte[] scratch);

    /**
     * Copies a block of pixels, the full-pixel motion compensation case.
     *
     * @param src       the source plane
     * @param srcOff    the index of the first source pixel
     * @param srcStride the source row stride
     * @param dst       the destination plane
     * @param dstOff    the index of the first destination pixel
     * @param dstStride the destination row stride
     * @param width     the block width: 4, 8, or 16
     * @param height    the block height
     */
    public abstract void copy(byte[] src, int srcOff, int srcStride, byte[] dst, int dstOff, int dstStride,
                              int width, int height);
}
