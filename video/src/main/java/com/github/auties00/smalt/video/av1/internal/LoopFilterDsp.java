package com.github.auties00.smalt.video.av1.internal;

import com.github.auties00.smalt.internal.simd.Simd;

/**
 * Defines the AV1 deblocking filter kernels (AV1 specification section 7.14): the filtering of
 * every edge of one plane of one superblock column or row, driven by the edge masks the block
 * decoder builds (see {@link LfMask}).
 *
 * <p>Every kernel exists twice: {@link ScalarLoopFilterDsp} is the reference implementation, and
 * {@link VectorLoopFilterDsp} filters row edges with the Vector API. {@link #select()}
 * selects the one of each frame. Implementations are stateless and thread-safe.
 *
 * <p>Planes are {@code short[]} arrays of unsigned samples from {@code 0} to {@code bitdepthMax},
 * addressed by an offset and a row stride in samples.
 *
 * <p>The filter level of each 4x4 block comes from a level cache of four bytes per block (luma
 * vertical-edge level, luma horizontal-edge level, Cb level, Cr level), laid out in rows of
 * {@code b4Stride} blocks; a kernel is given the index of the byte of its plane and direction in
 * the first block of its edge run and steps by four bytes per block along a row and by
 * {@code 4 * b4Stride} bytes per block row. A block whose own level is zero takes the level of the
 * block before it across the edge (to its left for column edges, above it for row edges), which
 * the kernel therefore reads too.
 */
public abstract class LoopFilterDsp {
    /**
     * The scalar kernels, which every JVM runs.
     */
    static final LoopFilterDsp SCALAR = new ScalarLoopFilterDsp();

    /**
     * The implementation every frame uses: {@link VectorLoopFilterDsp}, written for 256-bit vectors,
     * when {@link Simd#WIDE}, {@link #SCALAR} otherwise.
     */
    private static final LoopFilterDsp FIXED = Simd.WIDE ? VectorLoopFilterDsp.create() : SCALAR;

    /**
     * The minimum size of the scratch buffer of {@link #loopFilterSb}: the filter decisions of a
     * group of 16 sample columns and the 14 transposed rows of 16 lines of a column edge group.
     */
    public static final int SCRATCH_SHORTS = 32 + 14 * 16;

    /**
     * The plane index of luma for {@link #loopFilterSb}.
     */
    public static final int PLANE_LUMA = 0;

    /**
     * The plane index of chroma for {@link #loopFilterSb}.
     */
    public static final int PLANE_CHROMA = 1;

    /**
     * The direction index of column edges (vertical edges between horizontally adjacent blocks,
     * filtered across with horizontal taps) for {@link #loopFilterSb}.
     */
    public static final int DIR_COLUMN_EDGES = 0;

    /**
     * The direction index of row edges (horizontal edges between vertically adjacent blocks,
     * filtered across with vertical taps) for {@link #loopFilterSb}.
     */
    public static final int DIR_ROW_EDGES = 1;

    /**
     * Constructs a kernel set.
     */
    protected LoopFilterDsp() {
    }

    /**
     * Returns the kernels one frame is deblocked with.
     *
     * <p>This is the Vector API implementation when {@link Simd#ENABLED} and {@link #SCALAR} when
     * not. Every implementation produces the same samples, so the output never depends on the
     * selection.
     *
     * @return the kernels
     */
    public static LoopFilterDsp select() {
        return FIXED;
    }

    /**
     * Filters the edges of one line of 4x4 blocks along a superblock: for column edges, the
     * vertical edge at the left of a column of up to 32 blocks stacked downwards; for row edges,
     * the horizontal edge at the top of a row of up to 32 blocks laid out to the right.
     *
     * <p>Bit {@code k} of the masks selects block {@code k} of the run (counted downwards for
     * column edges, rightwards for row edges, each block four samples long along the edge). For
     * luma the filter is 16 samples wide where bit {@code k} of {@code mask2} is set, 8 where
     * only {@code mask1} has it and 4 where only {@code mask0} has it; for chroma it is 6 where
     * {@code mask1} has it and 4 where only {@code mask0} has it, and {@code mask2} is ignored. A
     * block whose effective filter level is zero is left untouched.
     *
     * @implSpec
     * Implementations must produce exactly the samples of the normative edge filters. Across the
     * edge they read and write up to 7 samples before and 6 after the edge for luma, 3 before and
     * 2 after for chroma; along it they change only the selected blocks, though they may write back
     * unchanged samples of the same rows elsewhere within the row stride.
     *
     * @param plane       {@link #PLANE_LUMA} or {@link #PLANE_CHROMA}
     * @param dir         {@link #DIR_COLUMN_EDGES} or {@link #DIR_ROW_EDGES}
     * @param dst         the plane
     * @param dstOff      the index of the first sample after the edge in the first block of the
     *                    run (its top-left sample)
     * @param stride      the plane's row stride, in samples
     * @param mask0       the blocks filtered at the narrowest width (and the union of every
     *                    width, as built by {@link LoopFilterMasks})
     * @param mask1       the blocks filtered at the middle width
     * @param mask2       the blocks filtered at the widest width; luma only
     * @param lvl         the level cache
     * @param lvlOff      the index of the level byte of this plane and direction in the first
     *                    block of the run
     * @param b4Stride    the level cache row stride, in blocks of four bytes
     * @param lut         the filter limits of the frame
     * @param length      the number of 4x4 blocks of the run within the picture; informational,
     *                    the masks alone select the filtered blocks
     * @param scratch     a scratch buffer of at least {@link #SCRATCH_SHORTS} entries
     * @param bitdepthMax the largest sample value: 255, 1023 or 4095
     */
    public abstract void loopFilterSb(int plane, int dir, short[] dst, int dstOff, int stride,
                                      int mask0, int mask1, int mask2,
                                      byte[] lvl, int lvlOff, int b4Stride, FilterLut lut,
                                      int length, short[] scratch, int bitdepthMax);
}
