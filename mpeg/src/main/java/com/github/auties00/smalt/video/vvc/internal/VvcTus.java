package com.github.auties00.smalt.video.vvc.internal;

/**
 * The transform units of the coding unit being decoded, with their coefficient levels.
 *
 * <p>Luma positions and sizes are in luma samples; the chroma block of a unit has its top-left corner at the luma
 * position {@code (cx, cy)} and is {@code cw} by {@code ch} chroma samples. Coefficients are stored per component in
 * raster order at {@link #offset}.
 */
final class VvcTus {
    /**
     * Constructs an empty list of transform units.
     */
    VvcTus() {
    }

    /**
     * The most transform units of one coding unit.
     */
    static final int MAX = 64;

    /**
     * The number of transform units.
     */
    int count;
    /**
     * The luma column of each transform unit.
     */
    final int[] x = new int[MAX];
    /**
     * The luma row of each transform unit.
     */
    final int[] y = new int[MAX];
    /**
     * The luma width of each transform unit.
     */
    final int[] w = new int[MAX];
    /**
     * The luma height of each transform unit.
     */
    final int[] h = new int[MAX];
    /**
     * The luma column of the top-left corner of the chroma block of each transform unit.
     */
    final int[] cx = new int[MAX];
    /**
     * The luma row of the top-left corner of the chroma block of each transform unit.
     */
    final int[] cy = new int[MAX];
    /**
     * The width in chroma samples of the chroma block of each transform unit.
     */
    final int[] cw = new int[MAX];
    /**
     * The height in chroma samples of the chroma block of each transform unit.
     */
    final int[] ch = new int[MAX];
    /**
     * Whether each transform unit codes luma.
     */
    final boolean[] hasLuma = new boolean[MAX];
    /**
     * Whether each transform unit codes chroma.
     */
    final boolean[] hasChroma = new boolean[MAX];
    /**
     * Bit 0 luma, bit 1 Cb, bit 2 Cr.
     */
    final int[] cbf = new int[MAX];
    /**
     * Transform skip per component, the same bit layout as {@link #cbf}.
     */
    final int[] ts = new int[MAX];
    /**
     * {@code tu_joint_cbcr_residual_flag} with the coded block flags: the joint chroma mode of each transform unit, 0
     * when off.
     */
    final int[] jointCbCr = new int[MAX];
    /**
     * {@code CuQpOffsetCb} of each transform unit.
     */
    final int[] qpOffsetCb = new int[MAX];
    /**
     * {@code CuQpOffsetCr} of each transform unit.
     */
    final int[] qpOffsetCr = new int[MAX];
    /**
     * {@code CuQpOffsetCbCr} of each transform unit.
     */
    final int[] qpOffsetCbCr = new int[MAX];
    /**
     * The chroma QP offsets ({@code CuQpOffsetCb}, {@code CuQpOffsetCr}, {@code CuQpOffsetCbCr}) in effect for a coding
     * unit without coded transform units.
     */
    int cuQpOffsetCb;
    /**
     * {@code CuQpOffsetCr} in effect for a coding unit without coded transform units.
     */
    int cuQpOffsetCr;
    /**
     * {@code CuQpOffsetCbCr} in effect for a coding unit without coded transform units.
     */
    int cuQpOffsetCbCr;
    /**
     * The offset of the coefficients of each transform unit in the storage of each component.
     */
    final int[][] offset = new int[3][MAX];
    /**
     * The coefficient storage of each component.
     */
    final int[][] coeff = {new int[128 * 128], new int[64 * 64 * 4], new int[64 * 64 * 4]};
    /**
     * The number of coefficients reserved in the storage of each component.
     */
    private final int[] used = new int[3];

    /**
     * Empties the list.
     */
    void reset() {
        count = 0;
        used[0] = 0;
        used[1] = 0;
        used[2] = 0;
    }

    /**
     * Appends a transform unit with no coded block flags, transform skip or joint chroma residual.
     *
     * @param x0 the luma column
     * @param y0 the luma row
     * @param w0 the luma width
     * @param h0 the luma height
     * @return the index of the unit
     * @throws com.github.auties00.smalt.MediaException.Malformed if the coding unit has {@value #MAX} units already
     */
    int add(int x0, int y0, int w0, int h0) {
        if (count == MAX) {
            throw new com.github.auties00.smalt.MediaException.Malformed("vvc too many transform units");
        }
        var t = count++;
        x[t] = x0;
        y[t] = y0;
        w[t] = w0;
        h[t] = h0;
        cbf[t] = 0;
        ts[t] = 0;
        jointCbCr[t] = 0;
        return t;
    }

    /**
     * Reserves the coefficient storage of a component of a transform unit.
     *
     * @param t the index of the transform unit
     * @param c the component
     * @param size the number of coefficients
     * @return the storage of the component, the block starting at {@code offset[c][t]}
     */
    int[] allocate(int t, int c, int size) {
        offset[c][t] = used[c];
        used[c] += size;
        return coeff[c];
    }
}
