package com.github.auties00.smalt.video.av1.internal;

import java.util.Arrays;

/**
 * Holds the in-loop filter state of one 128x128 luma area of a frame (one 128x128 superblock or
 * four 64x64 ones), before super-resolution scaling: the deblocking edge masks, the CDEF strength
 * indices and the mask of blocks with coded residual.
 *
 * <p>The edge masks record, for every edge position, which 4x4 blocks along it are filtered and at
 * which width. They are flat arrays of unsigned 16-bit words, each word covering 16 consecutive
 * 4x4 blocks (luma) or 16 consecutive chroma 4x4 blocks along the edge; bit {@code k} of word
 * {@code half} is block {@code 16 * half + k} of the run. For subsampled chroma the run of a word
 * is {@code 16 >> ss} blocks long, so the second word starts at block {@code 16 >> ss}.
 * <ul>
 *   <li>{@link #filterY} is dav1d's {@code filter_y[2][32][3][2]}: index
 *       {@code ((dir * 32 + pos) * 3 + size) * 2 + half}, with {@code dir} 0 for column edges
 *       ({@code pos} is the 4x4 column of the edge, the run goes down) and 1 for row edges
 *       ({@code pos} is the 4x4 row, the run goes right), and {@code size} 0, 1, 2 for filters 4, 8
 *       and 16 samples wide; see {@link #yIndex(int, int, int, int)}.</li>
 *   <li>{@link #filterUv} is dav1d's {@code filter_uv[2][32][2][2]}: index
 *       {@code ((dir * 32 + pos) * 2 + size) * 2 + half}, positions in chroma 4x4 units, {@code size}
 *       0 and 1 for filters 4 and 6 samples wide; see {@link #uvIndex(int, int, int, int)}.</li>
 * </ul>
 *
 * <p>The decoder clears the masks of a frame with {@link #clear()} before decoding it.
 *
 * @implNote This implementation mirrors dav1d's {@code Av1Filter}; the unsigned 16-bit words are
 *           Java {@code char}s, so widening them never sign-extends.
 */
public final class LfMask {
    /**
     * The number of words of {@link #filterY}.
     */
    public static final int FILTER_Y_LENGTH = 2 * 32 * 3 * 2;

    /**
     * The number of words of {@link #filterUv}.
     */
    public static final int FILTER_UV_LENGTH = 2 * 32 * 2 * 2;

    /**
     * The luma edge masks, dav1d's {@code filter_y[2][32][3][2]} flattened as the class
     * documentation describes.
     */
    public final char[] filterY = new char[FILTER_Y_LENGTH];

    /**
     * The chroma edge masks, dav1d's {@code filter_uv[2][32][2][2]} flattened as the class
     * documentation describes.
     */
    public final char[] filterUv = new char[FILTER_UV_LENGTH];

    /**
     * The CDEF strength index of each 64x64 quarter in raster order, or {@code -1} when unset.
     */
    public final byte[] cdefIdx = new byte[4];

    /**
     * The blocks with coded residual, dav1d's {@code noskip_mask[16][2]}: one pair of words per
     * two 4x4 rows (index {@code row8 * 2 + half}), bit {@code k} of word {@code half} for 4x4
     * column {@code 16 * half + k}.
     */
    public final char[] noskipMask = new char[32];

    /**
     * Creates a cleared mask set.
     */
    public LfMask() {
    }

    /**
     * Returns the index of a luma edge mask word in {@link #filterY}.
     *
     * @param dir  0 for column edges, 1 for row edges
     * @param pos  the 4x4 column (column edges) or row (row edges) of the edge, from 0 to 31
     * @param size 0, 1 or 2 for filters 4, 8 or 16 samples wide
     * @param half the word: 0 for blocks 0 to 15 of the run, 1 for blocks 16 to 31
     * @return the index
     */
    public static int yIndex(int dir, int pos, int size, int half) {
        return ((dir * 32 + pos) * 3 + size) * 2 + half;
    }

    /**
     * Returns the index of a chroma edge mask word in {@link #filterUv}.
     *
     * @param dir  0 for column edges, 1 for row edges
     * @param pos  the chroma 4x4 column (column edges) or row (row edges) of the edge
     * @param size 0 or 1 for filters 4 or 6 samples wide
     * @param half the word: 0 for the first {@code 16 >> ss} blocks of the run, 1 for the rest
     * @return the index
     */
    public static int uvIndex(int dir, int pos, int size, int half) {
        return ((dir * 32 + pos) * 2 + size) * 2 + half;
    }

    /**
     * Clears every mask word and sets every CDEF index to zero, as dav1d's per-frame
     * {@code memset} does.
     */
    public void clear() {
        Arrays.fill(filterY, (char) 0);
        Arrays.fill(filterUv, (char) 0);
        Arrays.fill(cdefIdx, (byte) 0);
        Arrays.fill(noskipMask, (char) 0);
    }
}
