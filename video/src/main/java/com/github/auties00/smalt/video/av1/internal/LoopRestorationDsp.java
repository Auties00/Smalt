package com.github.auties00.smalt.video.av1.internal;

import com.github.auties00.smalt.internal.simd.Simd;

/**
 * Defines the kernels of the AV1 loop restoration filters (AV1 specification section 7.17): the
 * separable Wiener filter and the self-guided filter in its 5x5, 3x3 and mixed forms.
 *
 * <p>Every kernel exists as a scalar reference, {@link ScalarLoopRestorationDsp}, which
 * {@link VectorLoopRestorationDsp} accelerates with the Vector API; {@link #select()} selects the
 * implementation of each frame. Implementations are stateless and
 * thread-safe; callers pass the scratch buffer a kernel needs.
 *
 * <p>A kernel restores one stripe of one restoration unit in place: {@code w x h} samples of a
 * {@code short[]} plane of unsigned samples from {@code 0} to {@code bitdepthMax}, addressed by
 * an offset and a row stride in samples. The filters reach three samples (Wiener) or up to three
 * samples (self-guided) around every output sample:
 * <ul>
 *   <li>left of the unit, from {@code left}, which holds four samples per stripe row (the four
 *       columns left of the unit, farthest first), when {@link #HAVE_LEFT} is set; otherwise the
 *       first column is replicated;</li>
 *   <li>right of the unit, read directly from {@code dst} up to three samples past {@code w - 1},
 *       when {@link #HAVE_RIGHT} is set; otherwise the last column is replicated;</li>
 *   <li>above and below the stripe, from {@code lpf} when {@link #HAVE_TOP} or
 *       {@link #HAVE_BOTTOM} is set; otherwise the first or last stripe row is replicated.</li>
 * </ul>
 *
 * <p>{@code lpf} uses the row stride of {@code dst}, and {@code lpfOff} indexes the sample in the
 * unit's first column of its row 0: rows 0 and 1 are the two rows above the stripe (row 1 the
 * nearer one) and rows 6 and 7 the two rows below it (row 6 the nearer one); rows 2 to 5 are not
 * read. In those rows the kernels read the three columns left of the unit directly from
 * {@code lpf} when {@link #HAVE_LEFT} is set, and up to three columns past {@code w - 1} when
 * {@link #HAVE_RIGHT} is set. The kernels write exactly the {@code w x h} samples of the stripe and
 * nothing else.
 *
 * @implNote This implementation ports dav1d's {@code looprestoration_tmpl.c}; the kernel
 *           signatures mirror {@code Dav1dLoopRestorationDSPContext}.
 */
public abstract class LoopRestorationDsp {
    /**
     * The scalar kernels, which every JVM runs.
     */
    static final LoopRestorationDsp SCALAR = new ScalarLoopRestorationDsp();

    /**
     * The implementation every frame uses: {@link VectorLoopRestorationDsp}, written for 256-bit
     * vectors, when {@link Simd#WIDE}, {@link #SCALAR} otherwise.
     */
    private static final LoopRestorationDsp FIXED = Simd.WIDE ? VectorLoopRestorationDsp.create() : SCALAR;

    /**
     * The edge flag telling that the columns left of the unit exist ({@code LR_HAVE_LEFT}).
     */
    public static final int HAVE_LEFT = 1;

    /**
     * The edge flag telling that the columns right of the unit exist ({@code LR_HAVE_RIGHT}).
     */
    public static final int HAVE_RIGHT = 1 << 1;

    /**
     * The edge flag telling that the rows above the stripe exist ({@code LR_HAVE_TOP}).
     */
    public static final int HAVE_TOP = 1 << 2;

    /**
     * The edge flag telling that the rows below the stripe exist ({@code LR_HAVE_BOTTOM}).
     */
    public static final int HAVE_BOTTOM = 1 << 3;

    /**
     * The Wiener filter selector of the 7-tap (luma) filter.
     */
    public static final int WIENER_7TAP = 0;

    /**
     * The Wiener filter selector of the 5-tap filter, used when both outer taps are zero.
     */
    public static final int WIENER_5TAP = 1;

    /**
     * The self-guided filter selector of the 5x5 (radius 2) pass alone.
     */
    public static final int SGR_5X5 = 0;

    /**
     * The self-guided filter selector of the 3x3 (radius 1) pass alone.
     */
    public static final int SGR_3X3 = 1;

    /**
     * The self-guided filter selector of both passes combined.
     */
    public static final int SGR_MIX = 2;

    /**
     * The widest unit stripe a kernel accepts, in samples: one and a half times the largest
     * restoration unit.
     */
    public static final int MAX_WIDTH = 384;

    /**
     * The tallest stripe a kernel accepts, in rows.
     */
    public static final int MAX_HEIGHT = 64;

    /**
     * The minimum size, in {@code int}s, of the scratch buffer every kernel needs.
     */
    public static final int SCRATCH_INTS = 12768;

    /**
     * The self-guided filter parameter sets as {@code [set][pass]}: the scales of the 5x5 and the
     * 3x3 passes, zero when the pass is disabled (AV1 specification section 7.17.3,
     * {@code Sgr_Params}).
     *
     * @implNote This implementation copies dav1d's {@code dav1d_sgr_params} ({@code tables.c}).
     */
    public static final int[][] SGR_PARAMS = {
            {140, 3236}, {112, 2158}, {93, 1618}, {80, 1438},
            {70, 1295}, {58, 1177}, {47, 1079}, {37, 996},
            {30, 925}, {25, 863}, {0, 2589}, {0, 1618},
            {0, 1177}, {0, 925}, {56, 0}, {22, 0}
    };

    /**
     * Constructs a kernel set.
     */
    protected LoopRestorationDsp() {
    }

    /**
     * Returns the kernels one frame is restored with.
     *
     * <p>This is the Vector API implementation when {@link Simd#ENABLED} and {@link #SCALAR} when
     * not. Every implementation produces the same samples, so the output never depends on the
     * selection.
     *
     * @return the kernels
     */
    public static LoopRestorationDsp select() {
        return FIXED;
    }

    /**
     * Applies the separable Wiener filter to one stripe of a restoration unit (AV1 specification
     * section 7.17.4).
     *
     * @param taps        {@link #WIENER_7TAP} or {@link #WIENER_5TAP}; the 5-tap form requires
     *                    zero outer taps in both directions and computes the same result
     * @param dst         the plane, restored in place
     * @param dstOff      the index of the stripe's top-left sample
     * @param stride      the row stride of {@code dst} and {@code lpf}
     * @param left        the four columns left of the unit, four samples per stripe row
     * @param leftOff     the index of the left samples of the stripe's first row
     * @param lpf         the saved rows above and below the stripe, as the class documentation
     *                    describes
     * @param lpfOff      the index of the sample in the unit's first column of {@code lpf} row 0
     * @param w           the stripe width, from 1 to {@link #MAX_WIDTH}
     * @param h           the stripe height, from 1 to {@link #MAX_HEIGHT}
     * @param params      the filter taps ({@link LrParams#filter})
     * @param edges       the edge flags ({@link #HAVE_LEFT}, {@link #HAVE_RIGHT},
     *                    {@link #HAVE_TOP}, {@link #HAVE_BOTTOM})
     * @param scratch     a scratch buffer of at least {@link #SCRATCH_INTS} entries
     * @param bitdepthMax the largest sample value
     */
    public abstract void wiener(int taps, short[] dst, int dstOff, int stride, short[] left, int leftOff,
                                short[] lpf, int lpfOff, int w, int h, LrParams params, int edges, int[] scratch,
                                int bitdepthMax);

    /**
     * Applies the self-guided filter to one stripe of a restoration unit (AV1 specification
     * section 7.17.3).
     *
     * @param type        {@link #SGR_5X5} (only {@link LrParams#s0} is non-zero),
     *                    {@link #SGR_3X3} (only {@link LrParams#s1} is non-zero) or
     *                    {@link #SGR_MIX} (both are non-zero)
     * @param dst         the plane, restored in place
     * @param dstOff      the index of the stripe's top-left sample
     * @param stride      the row stride of {@code dst} and {@code lpf}
     * @param left        the four columns left of the unit, four samples per stripe row
     * @param leftOff     the index of the left samples of the stripe's first row
     * @param lpf         the saved rows above and below the stripe, as the class documentation
     *                    describes
     * @param lpfOff      the index of the sample in the unit's first column of {@code lpf} row 0
     * @param w           the stripe width, from 1 to {@link #MAX_WIDTH}
     * @param h           the stripe height, from 1 to {@link #MAX_HEIGHT}
     * @param params      the scales and projection weights
     * @param edges       the edge flags ({@link #HAVE_LEFT}, {@link #HAVE_RIGHT},
     *                    {@link #HAVE_TOP}, {@link #HAVE_BOTTOM})
     * @param scratch     a scratch buffer of at least {@link #SCRATCH_INTS} entries
     * @param bitdepthMax the largest sample value
     */
    public abstract void sgr(int type, short[] dst, int dstOff, int stride, short[] left, int leftOff,
                             short[] lpf, int lpfOff, int w, int h, LrParams params, int edges, int[] scratch,
                             int bitdepthMax);
}
