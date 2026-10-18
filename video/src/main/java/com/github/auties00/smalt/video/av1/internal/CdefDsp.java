package com.github.auties00.smalt.video.av1.internal;

import com.github.auties00.smalt.internal.simd.Simd;

/**
 * Defines the kernels of the AV1 constrained directional enhancement filter (CDEF, AV1
 * specification section 7.15): the direction search of an 8x8 block and the filter of one 8x8,
 * 4x8 or 4x4 block.
 *
 * <p>Every kernel exists as a scalar reference, {@link ScalarCdefDsp}, and with the Vector API,
 * {@link VectorCdefDsp}; {@link #select()} selects the implementation of each frame.
 * Implementations are stateless and thread-safe; callers pass the scratch buffer a kernel needs.
 *
 * <p>Planes are {@code short[]} arrays of unsigned samples from {@code 0} to {@code bitdepthMax},
 * addressed by an offset and a row stride in samples. The filter operates entirely on pre-filter
 * samples: the block itself and, when the corresponding edge flag is set, its right and bottom
 * neighbors are read from the destination plane before any of them is written, while the two
 * rows above and below and the two columns to the left come from the separate {@code top},
 * {@code bottom} and {@code left} buffers, because the destination rows around the block may
 * already hold filtered samples.
 *
 * @implNote This implementation ports dav1d's {@code cdef_tmpl.c} ({@code cdef_find_dir_c} and
 *           {@code cdef_filter_block_c} with its {@code padding}); the kernel signatures mirror
 *           {@code Dav1dCdefDSPContext}.
 */
public abstract class CdefDsp {
    /**
     * The scalar kernels, which every JVM runs.
     */
    static final CdefDsp SCALAR = new ScalarCdefDsp();

    /**
     * The implementation every frame uses: {@link VectorCdefDsp} when
     * {@link Simd#ENABLED}, {@link #SCALAR} otherwise.
     */
    private static final CdefDsp FIXED = Simd.ENABLED ? VectorCdefDsp.create() : SCALAR;

    /**
     * The edge flag telling that the two columns left of the block exist ({@code CDEF_HAVE_LEFT}).
     */
    public static final int HAVE_LEFT = 1;

    /**
     * The edge flag telling that the two columns right of the block exist
     * ({@code CDEF_HAVE_RIGHT}).
     */
    public static final int HAVE_RIGHT = 1 << 1;

    /**
     * The edge flag telling that the two rows above the block exist ({@code CDEF_HAVE_TOP}).
     */
    public static final int HAVE_TOP = 1 << 2;

    /**
     * The edge flag telling that the two rows below the block exist ({@code CDEF_HAVE_BOTTOM}).
     */
    public static final int HAVE_BOTTOM = 1 << 3;

    /**
     * The block layout index of an 8x8 block: luma, or 4:4:4 chroma.
     */
    public static final int LAYOUT_8X8 = 0;

    /**
     * The block layout index of a 4x8 block: 4:2:2 chroma.
     */
    public static final int LAYOUT_4X8 = 1;

    /**
     * The block layout index of a 4x4 block: 4:2:0 chroma.
     */
    public static final int LAYOUT_4X4 = 2;

    /**
     * The minimum size, in {@code short}s, of the scratch buffer {@code filter} needs: a 12x12 padded
     * copy of the block, followed by 192 entries of per-row partial results that vector
     * implementations may use.
     */
    public static final int SCRATCH_SHORTS = 144 + 192;

    /**
     * The minimum size, in {@code int}s, of the array passed to
     * {@link #dir(short[], int, int, int[], int)}: element 0 receives the variance and the others
     * hold the partial sums and costs of the search.
     */
    public static final int DIR_SCRATCH_INTS = 99;

    /**
     * The row stride of the padded block copy, which the offsets of {@link #DIRECTIONS} assume.
     */
    static final int TMP_STRIDE = 12;

    /**
     * The sample offsets of the two filter taps along each direction in the padded block copy, as
     * {@code [2 + direction + 2][tap]} with the row stride {@link #TMP_STRIDE}; the two entries
     * before and after the eight directions wrap around, so {@code direction - 2} and
     * {@code direction + 2} index the table directly.
     *
     * @implNote This implementation copies dav1d's {@code dav1d_cdef_directions} ({@code tables.c}).
     */
    static final int[] DIRECTIONS = {
            TMP_STRIDE, 2 * TMP_STRIDE,
            TMP_STRIDE, 2 * TMP_STRIDE - 1,
            -TMP_STRIDE + 1, -2 * TMP_STRIDE + 2,
            1, -TMP_STRIDE + 2,
            1, 2,
            1, TMP_STRIDE + 2,
            TMP_STRIDE + 1, 2 * TMP_STRIDE + 2,
            TMP_STRIDE, 2 * TMP_STRIDE + 1,
            TMP_STRIDE, 2 * TMP_STRIDE,
            TMP_STRIDE, 2 * TMP_STRIDE - 1,
            -TMP_STRIDE + 1, -2 * TMP_STRIDE + 2,
            1, -TMP_STRIDE + 2
    };

    /**
     * Constructs a kernel set.
     */
    protected CdefDsp() {
    }

    /**
     * Returns the kernels one frame is filtered with.
     *
     * <p>This is the Vector API implementation when {@link Simd#ENABLED} and {@link #SCALAR} when
     * not. Every implementation produces the same samples, so the output never depends on the
     * selection.
     *
     * @return the kernels
     */
    public static CdefDsp select() {
        return FIXED;
    }

    /**
     * Finds the dominant direction of an 8x8 block (AV1 specification section 7.15.2).
     *
     * @param src         the plane holding the block
     * @param srcOff      the index of the block's top-left sample
     * @param stride      the row stride of {@code src}
     * @param variance    an array of at least {@link #DIR_SCRATCH_INTS} entries; element
     *                    {@code 0} receives the directional variance of the block (the cost
     *                    difference between the best direction and its orthogonal one, shifted
     *                    right by 10, as an unsigned value), the others are working storage
     * @param bitdepthMax the largest sample value
     * @return the direction, from 0 to 7
     */
    public abstract int dir(short[] src, int srcOff, int stride, int[] variance, int bitdepthMax);

    /**
     * Filters one block in place with the primary and secondary CDEF filters (AV1 specification
     * section 7.15.3).
     *
     * <p>The block is {@code w x h} samples, 8x8, 4x8 or 4x4 by {@code layoutIndex}. Samples
     * outside the edges the flags declare missing are excluded from the filter. When
     * {@link #HAVE_TOP} is set, {@code top} holds the two rows above the block, row {@code r} at
     * {@code topOff + r * stride}, with the block's first column at that index; the kernel reads
     * the columns from {@code -2} to {@code w + 1} of each row, narrowed to {@code 0} or
     * {@code w - 1} when the left or right edge is missing. {@code bottom} holds the two rows below
     * the block in the same way when {@link #HAVE_BOTTOM} is set. When {@link #HAVE_LEFT} is set,
     * {@code left} holds the two samples left of every block row, row {@code y} at
     * {@code leftOff + 2 * y} (the farther column first). When {@link #HAVE_RIGHT} is set, the two
     * samples right of every row are read from {@code dst}. A missing buffer may be {@code null}.
     * Only the {@code w x h} block of {@code dst} is written.
     *
     * @param layoutIndex the block layout: {@link #LAYOUT_8X8}, {@link #LAYOUT_4X8} or
     *                    {@link #LAYOUT_4X4}
     * @param dst         the plane holding the block, filtered in place
     * @param dstOff      the index of the block's top-left sample
     * @param stride      the row stride of {@code dst}, {@code top} and {@code bottom}
     * @param left        the left columns, two samples per row
     * @param leftOff     the index of the left samples of the block's first row
     * @param top         the two rows above the block
     * @param topOff      the index, in {@code top}, of the sample above the block's first column
     *                    in the farther row
     * @param bottom      the two rows below the block
     * @param bottomOff   the index, in {@code bottom}, of the sample below the block's first column
     *                    in the nearer row
     * @param priStrength the primary strength, already scaled to the bit depth; zero disables the
     *                    primary filter
     * @param secStrength the secondary strength (0, 1, 2 or 4, scaled to the bit depth); zero
     *                    disables the secondary filter
     * @param dir         the filter direction, from 0 to 7
     * @param damping     the damping, already scaled to the bit depth
     * @param edges       the edge flags ({@link #HAVE_LEFT}, {@link #HAVE_RIGHT},
     *                    {@link #HAVE_TOP}, {@link #HAVE_BOTTOM})
     * @param scratch     a scratch buffer of at least {@link #SCRATCH_SHORTS} entries
     * @param bitdepthMax the largest sample value
     */
    public abstract void filter(int layoutIndex, short[] dst, int dstOff, int stride, short[] left, int leftOff,
                                short[] top, int topOff, short[] bottom, int bottomOff, int priStrength,
                                int secStrength, int dir, int damping, int edges, short[] scratch, int bitdepthMax);
}
