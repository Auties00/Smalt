package com.github.auties00.smalt.video.av1.internal;

import com.github.auties00.smalt.internal.simd.Simd;

/**
 * Defines the AV1 intra prediction kernels: the DC, directional, smooth, Paeth and recursive
 * filter predictors, chroma-from-luma and palette prediction, plus the preparation of the
 * neighboring edge samples they read.
 *
 * <p>Every kernel exists as a scalar reference implementation, {@link ScalarIpredDsp}; a Vector
 * API implementation may override the hot ones. {@link #INSTANCE} selects one once per JVM.
 * Implementations are stateless and thread-safe; callers pass the scratch buffers a kernel needs.
 *
 * <p>Planes are {@code short[]} arrays of unsigned samples from {@code 0} to {@code bitdepthMax}
 * (255 for 8-bit, 1023 for 10-bit, 4095 for 12-bit), addressed by an offset and a row stride in
 * samples. Predictors read their neighbors from an edge buffer addressed by the offset of its
 * top-left sample: the top samples follow it (index {@code topleftOff + 1} is the left-most top
 * sample) and the left samples precede it (index {@code topleftOff - 1} is the top-most left
 * sample). {@link #prepareIntraEdges} fills such a buffer; the decoder sizes it
 * {@link #EDGE_BUFFER_SIZE} samples with the top-left sample at {@link #EDGE_TOPLEFT}, which
 * covers the {@code -2 * 64 .. 2 * 64} range a 64x64 block reads.
 *
 * <p>Mode numbers follow dav1d's {@code enum IntraPredMode}: the coded modes {@link #DC_PRED}
 * to {@link #PAETH_PRED}, and the implementation modes that {@link #prepareIntraEdges} converts
 * them to ({@link #LEFT_DC_PRED}, {@link #TOP_DC_PRED}, {@link #DC_128_PRED}, {@link #Z1_PRED},
 * {@link #Z2_PRED}, {@link #Z3_PRED}) plus {@link #FILTER_PRED}.
 *
 * @implNote This implementation ports dav1d's {@code src/ipred.h}, {@code src/ipred_tmpl.c} and
 * {@code src/ipred_prepare_tmpl.c}; {@link ScalarIpredDsp} reproduces dav1d's 8bpc
 * template when {@code bitdepthMax} is 255 and its 16bpc template otherwise.
 */
public abstract class IpredDsp {
    /**
     * The implementation used by the codec: {@link VectorIpredDsp}, written for 256-bit vectors, when
     * {@link Simd#WIDE}, {@link ScalarIpredDsp} otherwise.
     */
    public static final IpredDsp INSTANCE = Simd.WIDE ? new VectorIpredDsp() : new ScalarIpredDsp();

    /**
     * The DC predictor (average of the top and left edges).
     */
    public static final int DC_PRED = 0;

    /**
     * The vertical predictor (copy of the top edge).
     */
    public static final int VERT_PRED = 1;

    /**
     * The horizontal predictor (copy of the left edge).
     */
    public static final int HOR_PRED = 2;

    /**
     * The coded 45 degree directional mode.
     */
    public static final int DIAG_DOWN_LEFT_PRED = 3;

    /**
     * The coded 135 degree directional mode.
     */
    public static final int DIAG_DOWN_RIGHT_PRED = 4;

    /**
     * The coded 113 degree directional mode.
     */
    public static final int VERT_RIGHT_PRED = 5;

    /**
     * The coded 157 degree directional mode.
     */
    public static final int HOR_DOWN_PRED = 6;

    /**
     * The coded 203 degree directional mode.
     */
    public static final int HOR_UP_PRED = 7;

    /**
     * The coded 67 degree directional mode.
     */
    public static final int VERT_LEFT_PRED = 8;

    /**
     * The smooth predictor (quadratic blend of the top, left, bottom-left and top-right samples).
     */
    public static final int SMOOTH_PRED = 9;

    /**
     * The vertical smooth predictor.
     */
    public static final int SMOOTH_V_PRED = 10;

    /**
     * The horizontal smooth predictor.
     */
    public static final int SMOOTH_H_PRED = 11;

    /**
     * The Paeth predictor.
     */
    public static final int PAETH_PRED = 12;

    /**
     * The number of coded luma intra modes.
     */
    public static final int N_INTRA_PRED_MODES = 13;

    /**
     * The chroma-from-luma chroma mode.
     */
    public static final int CFL_PRED = 13;

    /**
     * The recursive filter predictor; its angle argument carries the filter mode, 0 to 4.
     */
    public static final int FILTER_PRED = 13;

    /**
     * The DC predictor reading only the left edge.
     */
    public static final int LEFT_DC_PRED = 3;

    /**
     * The DC predictor reading only the top edge.
     */
    public static final int TOP_DC_PRED = 4;

    /**
     * The DC predictor without edges (the mid-gray value).
     */
    public static final int DC_128_PRED = 5;

    /**
     * The directional predictor for angles below 90 degrees (top and top-right edges).
     */
    public static final int Z1_PRED = 6;

    /**
     * The directional predictor for angles between 90 and 180 degrees (top, left and top-left).
     */
    public static final int Z2_PRED = 7;

    /**
     * The directional predictor for angles above 180 degrees (left and bottom-left edges).
     */
    public static final int Z3_PRED = 8;


    /**
     * The bit of the angle argument that enables the intra edge filter and upsampling of the
     * directional predictors ({@code ANGLE_USE_EDGE_FILTER_FLAG}).
     */
    public static final int ANGLE_USE_EDGE_FILTER_FLAG = 1024;

    /**
     * The bit of the angle argument that signals a smooth-predicted neighbor, which weakens the
     * edge filter ({@code ANGLE_SMOOTH_EDGE_FLAG}).
     */
    public static final int ANGLE_SMOOTH_EDGE_FLAG = 512;

    /**
     * The edge flag: the top-right edge of a luma (or 4:4:4 chroma) block is available.
     */
    public static final int EDGE_I444_TOP_HAS_RIGHT = 1;



    /**
     * The edge flag: the bottom-left edge of a luma (or 4:4:4 chroma) block is available.
     */
    public static final int EDGE_I444_LEFT_HAS_BOTTOM = 1 << 3;



    /**
     * The size, in samples, of an edge buffer holding the edges of any block up to 64x64.
     */
    public static final int EDGE_BUFFER_SIZE = 257;

    /**
     * The index of the top-left sample in an edge buffer of {@link #EDGE_BUFFER_SIZE} samples.
     */
    public static final int EDGE_TOPLEFT = 128;

    /**
     * The minimum size of the {@code short} scratch buffer {@link #intraPred} takes: the filtered
     * or upsampled edges of the directional predictors.
     */
    public static final int SCRATCH_SHORTS = 129;

    /**
     * The minimum size of the chroma-from-luma {@code ac} buffer: a 32x32 chroma block.
     */
    public static final int AC_SHORTS = 32 * 32;

    /**
     * The angle of the coded directional modes {@link #VERT_PRED} to {@link #VERT_LEFT_PRED}, in
     * that order ({@code av1_mode_to_angle_map}).
     */
    private static final int[] MODE_TO_ANGLE = {90, 180, 45, 135, 113, 157, 203, 67};

    /**
     * The edge flag of {@link #EDGE_NEEDS} meaning the predictor reads the left edge.
     */
    private static final int NEEDS_LEFT = 1;

    /**
     * The edge flag of {@link #EDGE_NEEDS} meaning the predictor reads the top edge.
     */
    private static final int NEEDS_TOP = 2;

    /**
     * The edge flag of {@link #EDGE_NEEDS} meaning the predictor reads the top-left sample.
     */
    private static final int NEEDS_TOPLEFT = 4;

    /**
     * The edge flag of {@link #EDGE_NEEDS} meaning the predictor reads the top-right edge.
     */
    private static final int NEEDS_TOPRIGHT = 8;

    /**
     * The edge flag of {@link #EDGE_NEEDS} meaning the predictor reads the bottom-left edge.
     */
    private static final int NEEDS_BOTTOMLEFT = 16;

    /**
     * The edges each implementation mode reads ({@code av1_intra_prediction_edges}), indexed by
     * mode.
     */
    private static final int[] EDGE_NEEDS = {
            NEEDS_TOP | NEEDS_LEFT,
            NEEDS_TOP,
            NEEDS_LEFT,
            NEEDS_LEFT,
            NEEDS_TOP,
            0,
            NEEDS_TOP | NEEDS_TOPRIGHT | NEEDS_TOPLEFT,
            NEEDS_LEFT | NEEDS_TOP | NEEDS_TOPLEFT,
            NEEDS_LEFT | NEEDS_BOTTOMLEFT | NEEDS_TOPLEFT,
            NEEDS_LEFT | NEEDS_TOP,
            NEEDS_LEFT | NEEDS_TOP,
            NEEDS_LEFT | NEEDS_TOP,
            NEEDS_LEFT | NEEDS_TOP | NEEDS_TOPLEFT,
            NEEDS_LEFT | NEEDS_TOP | NEEDS_TOPLEFT
    };

    /**
     * Constructs a kernel set.
     */
    protected IpredDsp() {
    }

    /**
     * Predicts a block from its edges ({@code intra_pred[mode]}).
     *
     * <p>For the directional modes the angle argument holds the absolute prediction angle in
     * degrees in its low 9 bits, plus {@link #ANGLE_SMOOTH_EDGE_FLAG} and
     * {@link #ANGLE_USE_EDGE_FILTER_FLAG}; for {@link #FILTER_PRED} its low 9 bits hold the filter
     * mode, 0 to 4; the other modes ignore it. {@code maxWidth} and {@code maxHeight}, the
     * distance in samples from the block to the right and bottom picture edges, limit the edge
     * filter of {@link #Z2_PRED}.
     *
     * @implSpec
     * Implementations must write exactly the {@code width} x {@code height} samples of the block
     * and produce the samples of dav1d's {@code intra_pred} functions. {@link #FILTER_PRED}
     * accepts blocks up to 32x32; the other modes up to 64x64.
     *
     * @param mode the implementation mode, from {@link #DC_PRED} to {@link #FILTER_PRED}
     * @param dst the destination plane
     * @param dstOff the index of the block's top-left sample
     * @param stride the destination row stride
     * @param topleft the edge buffer
     * @param topleftOff the index of the top-left edge sample
     * @param width the block width: 4, 8, 16, 32 or 64
     * @param height the block height: 4, 8, 16, 32 or 64
     * @param angle the packed angle argument
     * @param maxWidth the samples from the block's left column to the picture's right edge
     * @param maxHeight the samples from the block's top row to the picture's bottom edge
     * @param scratch a scratch buffer of at least {@link #SCRATCH_SHORTS} samples
     * @param bitdepthMax the largest sample value
     */
    public abstract void intraPred(int mode, short[] dst, int dstOff, int stride, short[] topleft, int topleftOff,
                                   int width, int height, int angle, int maxWidth, int maxHeight, short[] scratch,
                                   int bitdepthMax);

    /**
     * Builds the chroma-from-luma AC contribution of a chroma block ({@code cfl_ac}): the
     * subsampled luma samples, scaled to 1/8 sample precision, minus their average.
     *
     * <p>Columns and rows of the block beyond the visible luma area (the last {@code wPad} and
     * {@code hPad} groups of four chroma samples) replicate the last visible ones. The output has
     * a row stride of {@code cw}.
     *
     * @param layout the pixel layout as dav1d's {@code enum Dav1dPixelLayout}: 1 for 4:2:0, 2 for
     * 4:2:2, 3 for 4:4:4
     * @param ac the output, at least {@code cw * ch} entries
     * @param y the luma plane
     * @param yOff the index of the co-located luma block's top-left sample
     * @param stride the luma row stride
     * @param wPad the number of padded 4-sample column groups, less than {@code cw / 4}
     * @param hPad the number of padded 4-sample row groups, less than {@code ch / 4}
     * @param cw the chroma block width: 4, 8, 16 or 32
     * @param ch the chroma block height: 4, 8, 16 or 32
     */
    public abstract void cflAc(int layout, short[] ac, short[] y, int yOff, int stride, int wPad, int hPad,
                               int cw, int ch);

    /**
     * Predicts a chroma block from its DC prediction plus a scaled luma AC contribution
     * ({@code cfl_pred[mode]}).
     *
     * @param mode the DC variant: {@link #DC_PRED}, {@link #DC_128_PRED},
     * {@link #TOP_DC_PRED} or {@link #LEFT_DC_PRED}
     * @param dst the destination plane
     * @param dstOff the index of the block's top-left sample
     * @param stride the destination row stride
     * @param topleft the edge buffer
     * @param topleftOff the index of the top-left edge sample
     * @param width the block width: 4, 8, 16 or 32
     * @param height the block height: 4, 8, 16 or 32
     * @param ac the AC contribution from {@link #cflAc}, with a row stride of
     * {@code width}
     * @param alpha the CfL scale in 1/8 units, from -16 to 16
     * @param bitdepthMax the largest sample value
     */
    public abstract void cflPred(int mode, short[] dst, int dstOff, int stride, short[] topleft, int topleftOff,
                                 int width, int height, short[] ac, int alpha, int bitdepthMax);

    /**
     * Fills a block from a palette ({@code pal_pred}).
     *
     * <p>Indices are packed two per byte, the left sample's in the low nibble and the right
     * sample's in the high nibble, row after row with {@code w / 2} bytes per row.
     *
     * @param dst the destination plane
     * @param dstOff the index of the block's top-left sample
     * @param stride the destination row stride
     * @param pal the palette colors
     * @param palOff the index of the first of the eight palette entries
     * @param idx the packed palette indices
     * @param idxOff the index of the first index byte
     * @param w the block width, even
     * @param h the block height
     */
    public abstract void palPred(short[] dst, int dstOff, int stride, short[] pal, int palOff, byte[] idx, int idxOff,
                                 int w, int h);

    /**
     * Converts a coded intra mode to the predictor to run and gathers the edge samples it reads
     * ({@code dav1d_prepare_intra_edges}).
     *
     * <p>Positions and sizes are in units of 4 samples: {@code x} and {@code y} locate the block
     * in the plane, {@code w} and {@code h} are the right tile edge and the bottom picture edge,
     * {@code tw} and {@code th} the transform block size. Edges outside the tile or not available
     * according to {@code edgeFlags} are extended from the nearest available samples, or set to
     * the mid-gray values the specification prescribes; edges the returned mode does not read are
     * left untouched.
     *
     * <p>For the coded directional modes, {@code angle[0]} holds the coded angle delta, -3 to 3,
     * on entry and the absolute angle in degrees on return; it is unchanged for the other modes.
     *
     * @param x the block column
     * @param haveLeft whether the left neighbor lies inside the tile
     * @param y the block row
     * @param haveTop whether the top neighbor lies inside the tile
     * @param w the column of the tile's right edge
     * @param h the row of the picture's bottom edge
     * @param edgeFlags the {@code EDGE_*} availability flags of the block
     * @param dst the plane being reconstructed
     * @param dstOff the index of the block's top-left sample
     * @param stride the plane's row stride
     * @param prefilterTopEdge the saved pre-loop-filter row above the superblock, or
     * {@code null} to read the row above the block from {@code dst}
     * @param prefilterTopEdgeOff the index of column 0 of the saved row
     * @param mode the coded mode, from {@link #DC_PRED} to {@link #FILTER_PRED}
     * @param angle the angle holder, read and written at index 0
     * @param tw the transform block width
     * @param th the transform block height
     * @param filterEdge whether the sequence enables the intra edge filter
     * @param topleft the edge buffer to fill
     * @param topleftOff the index of its top-left sample
     * @param bitdepthMax the largest sample value
     * @return the implementation mode to pass to {@link #intraPred} or {@link #cflPred}
     */
    public static int prepareIntraEdges(int x, boolean haveLeft, int y, boolean haveTop, int w, int h, int edgeFlags,
                                        short[] dst, int dstOff, int stride, short[] prefilterTopEdge,
                                        int prefilterTopEdgeOff, int mode, int[] angle, int tw, int th,
                                        boolean filterEdge, short[] topleft, int topleftOff, int bitdepthMax) {
        var bitdepth = 32 - Integer.numberOfLeadingZeros(bitdepthMax);
        switch (mode) {
            case VERT_PRED, HOR_PRED, DIAG_DOWN_LEFT_PRED, DIAG_DOWN_RIGHT_PRED, VERT_RIGHT_PRED, HOR_DOWN_PRED,
                 HOR_UP_PRED, VERT_LEFT_PRED -> {
                var a = MODE_TO_ANGLE[mode - VERT_PRED] + 3 * angle[0];
                angle[0] = a;
                if (a <= 90) {
                    mode = a < 90 && haveTop ? Z1_PRED : VERT_PRED;
                } else if (a < 180) {
                    mode = Z2_PRED;
                } else {
                    mode = a > 180 && haveLeft ? Z3_PRED : HOR_PRED;
                }
            }
            case DC_PRED -> mode = haveLeft ? (haveTop ? DC_PRED : LEFT_DC_PRED) : (haveTop ? TOP_DC_PRED : DC_128_PRED);
            case PAETH_PRED -> mode = haveLeft ? (haveTop ? PAETH_PRED : HOR_PRED) : (haveTop ? VERT_PRED : DC_128_PRED);
            default -> {
            }
        }
        var needs = EDGE_NEEDS[mode];
        short[] top = null;
        var topOff = 0;
        if (haveTop && ((needs & (NEEDS_TOP | NEEDS_TOPLEFT)) != 0 || ((needs & NEEDS_LEFT) != 0 && !haveLeft))) {
            if (prefilterTopEdge != null) {
                top = prefilterTopEdge;
                topOff = prefilterTopEdgeOff + x * 4;
            } else {
                top = dst;
                topOff = dstOff - stride;
            }
        }
        if ((needs & NEEDS_LEFT) != 0) {
            var sz = th << 2;
            var left = topleftOff - sz;
            if (haveLeft) {
                var pxHave = Math.min(sz, (h - y) << 2);
                for (var i = 0; i < pxHave; i++) {
                    topleft[left + sz - 1 - i] = dst[dstOff + stride * i - 1];
                }
                if (pxHave < sz) {
                    fill(topleft, left, sz - pxHave, topleft[left + sz - pxHave]);
                }
            } else {
                fill(topleft, left, sz, haveTop ? top[topOff] : ((1 << bitdepth) >> 1) + 1);
            }
            if ((needs & NEEDS_BOTTOMLEFT) != 0) {
                var haveBottomLeft = haveLeft && y + th < h && (edgeFlags & EDGE_I444_LEFT_HAS_BOTTOM) != 0;
                if (haveBottomLeft) {
                    var pxHave = Math.min(sz, (h - y - th) << 2);
                    for (var i = 0; i < pxHave; i++) {
                        topleft[left - (i + 1)] = dst[dstOff + (sz + i) * stride - 1];
                    }
                    if (pxHave < sz) {
                        fill(topleft, left - sz, sz - pxHave, topleft[left - pxHave]);
                    }
                } else {
                    fill(topleft, left - sz, sz, topleft[left]);
                }
            }
        }
        if ((needs & NEEDS_TOP) != 0) {
            var sz = tw << 2;
            var first = topleftOff + 1;
            if (haveTop) {
                var pxHave = Math.min(sz, (w - x) << 2);
                System.arraycopy(top, topOff, topleft, first, pxHave);
                if (pxHave < sz) {
                    fill(topleft, first + pxHave, sz - pxHave, topleft[first + pxHave - 1]);
                }
            } else {
                fill(topleft, first, sz, haveLeft ? dst[dstOff - 1] : ((1 << bitdepth) >> 1) - 1);
            }
            if ((needs & NEEDS_TOPRIGHT) != 0) {
                var haveTopRight = haveTop && x + tw < w && (edgeFlags & EDGE_I444_TOP_HAS_RIGHT) != 0;
                if (haveTopRight) {
                    var pxHave = Math.min(sz, (w - x - tw) << 2);
                    System.arraycopy(top, topOff + sz, topleft, first + sz, pxHave);
                    if (pxHave < sz) {
                        fill(topleft, first + sz + pxHave, sz - pxHave, topleft[first + sz + pxHave - 1]);
                    }
                } else {
                    fill(topleft, first + sz, sz, topleft[first + sz - 1]);
                }
            }
        }
        if ((needs & NEEDS_TOPLEFT) != 0) {
            if (haveLeft) {
                topleft[topleftOff] = haveTop ? top[topOff - 1] : dst[dstOff - 1];
            } else {
                topleft[topleftOff] = haveTop ? top[topOff] : (short) ((1 << bitdepth) >> 1);
            }
            if (mode == Z2_PRED && tw + th >= 6 && filterEdge) {
                topleft[topleftOff] = (short) (((topleft[topleftOff - 1] + topleft[topleftOff + 1]) * 5
                        + topleft[topleftOff] * 6 + 8) >> 4);
            }
        }
        return mode;
    }

    /**
     * Sets a run of samples to one value.
     *
     * @param plane the samples
     * @param offset the index of the first sample
     * @param count the number of samples
     * @param value the sample value
     */
    private static void fill(short[] plane, int offset, int count, int value) {
        var v = (short) value;
        for (var i = 0; i < count; i++) {
            plane[offset + i] = v;
        }
    }
}
