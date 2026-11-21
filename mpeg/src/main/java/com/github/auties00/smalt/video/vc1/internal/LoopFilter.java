package com.github.auties00.smalt.video.vc1.internal;

/**
 * Applies the in-loop deblocking filter of SMPTE ST 421 section 8.6 to a decoded progressive frame: every 8x8 block
 * boundary of I, BI and B pictures, and the transform boundaries of P pictures that separate intra blocks, blocks with
 * different motion vectors or coded subblocks, with the main profile exceptions of section 8.6.4.1.
 *
 * <p>Instances are not thread-safe.
 */
final class LoopFilter {
    /**
     * The block state of the plane being filtered.
     */
    private BlockState state;

    /**
     * Whether the stream is in the main profile.
     */
    private boolean mainProfile;

    /**
     * Whether the first luma block of the picture is intra coded, which the first main profile exception tests.
     */
    private boolean firstIntra;

    /**
     * Whether each macroblock is a 4-MV macroblock.
     */
    private boolean[] fourMv;

    /**
     * The macroblock columns.
     */
    private int mbWidth;

    /**
     * The plane being filtered, 0 for luma.
     */
    private int planeIndex;

    /**
     * Every 8x8 block boundary is filtered (I, BI and B pictures).
     */
    static final int ALL_BLOCKS = 0;

    /**
     * Transform boundaries are filtered unless both sides are inter coded with the same motion vector and no
     * coefficients (P pictures).
     */
    static final int PREDICTED = 1;

    /**
     * Every block boundary is filtered, and internal transform boundaries when either side has coefficients (B field
     * pictures).
     */
    static final int B_FIELD = 2;

    /**
     * Filters a progressive frame or one field of an interlace field picture.
     *
     * @param frame the decoded frame
     * @param lumaBase the index of the picture's first luma sample
     * @param lumaPitch the distance between the picture's luma rows
     * @param chromaBase the index of the picture's first color-difference sample
     * @param chromaPitch the distance between the picture's color-difference rows
     * @param luma the luma block state
     * @param chroma the color-difference block states
     * @param mbWidth the macroblock columns
     * @param mbHeight the macroblock rows
     * @param sliceTop the first row of the slice of each macroblock row
     * @param pquant the picture quantizer
     * @param mode {@link #ALL_BLOCKS}, {@link #PREDICTED} or {@link #B_FIELD}
     * @param mainProfile whether the stream is in the main profile, which enables the exceptions of 8.6.4.1
     * @param fourMv whether each macroblock is a 4-MV macroblock
     */
    void filter(Frame frame, int lumaBase, int lumaPitch, int chromaBase, int chromaPitch, BlockState luma,
                BlockState[] chroma, int mbWidth, int mbHeight, int[] sliceTop, int pquant, int mode,
                boolean mainProfile, boolean[] fourMv) {
        this.mainProfile = mainProfile;
        this.fourMv = fourMv;
        this.mbWidth = mbWidth;
        this.firstIntra = luma.intra[0];
        this.mode = mode;
        var internal = mode != ALL_BLOCKS;
        for (var plane = 0; plane < 3; plane++) {
            state = plane == 0 ? luma : chroma[plane - 1];
            planeIndex = plane;
            var samples = frame.plane(plane);
            var stride = plane == 0 ? lumaPitch : chromaPitch;
            var base = plane == 0 ? lumaBase : chromaBase;
            var columns = state.columns;
            var rows = state.rows;
            var perMb = plane == 0 ? 2 : 1;
            for (var pass = 0; pass < 2; pass++) {
                if (pass == 1 && !internal) {
                    break;
                }
                for (var by = pass == 0 ? 1 : 0; by < rows; by++) {
                    if (pass == 0 && by % perMb == 0 && sliceTop[by / perMb] != sliceTop[by / perMb - 1]) {
                        continue;
                    }
                    var y = by * 8 + pass * 4;
                    for (var bx = 0; bx < columns; bx++) {
                        for (var segment = 0; segment < 2; segment++) {
                            var filter = pass == 0 ? mode != PREDICTED || horizontalBoundary(bx, by, segment)
                                    : horizontalInternal(bx, by, segment);
                            if (filter) {
                                segment(samples, base + y * stride + bx * 8 + segment * 4, 1, stride, pquant);
                            }
                        }
                    }
                }
            }
            for (var pass = 0; pass < 2; pass++) {
                if (pass == 1 && !internal) {
                    break;
                }
                for (var bx = pass == 0 ? 1 : 0; bx < columns; bx++) {
                    var x = bx * 8 + pass * 4;
                    for (var by = 0; by < rows; by++) {
                        for (var segment = 0; segment < 2; segment++) {
                            var filter = pass == 0 ? mode != PREDICTED || verticalBoundary(bx, by, segment)
                                    : verticalInternal(bx, by, segment);
                            if (filter) {
                                segment(samples, base + (by * 8 + segment * 4) * stride + x, stride, 1, pquant);
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * The filtering mode of the picture.
     */
    private int mode;

    /**
     * Filters an interlace frame picture with field-based filtering (section 10.10.4): every horizontal edge of the
     * picture first, then every vertical edge, macroblock by macroblock in raster order.
     *
     * @param frame the decoded frame
     * @param luma the luma block state, whose transform types select the internal edges of P and B pictures
     * @param chroma the color-difference block states
     * @param mbWidth the macroblock columns
     * @param mbHeight the macroblock rows
     * @param sliceTop the first row of the slice of each macroblock row
     * @param pquant the picture quantizer
     * @param intraPicture whether the picture is an I or BI picture
     * @param fieldTx whether each macroblock is field transform coded
     */
    void filterFrameInterlace(Frame frame, BlockState luma, BlockState[] chroma, int mbWidth, int mbHeight,
                              int[] sliceTop, int pquant, boolean intraPicture, boolean[] fieldTx) {
        var y = frame.y;
        var stride = frame.width;
        var chromaStride = frame.chromaWidth;
        for (var mby = 0; mby < mbHeight; mby++) {
            var last = mby == mbHeight - 1 || sliceTop[mby + 1] != sliceTop[mby];
            var first = mby == sliceTop[mby];
            var bottomEdge = !last;
            for (var mbx = 0; mbx < mbWidth; mbx++) {
                var field = fieldTx[mby * mbWidth + mbx];
                var origin = mby * 16 * stride + mbx * 16;
                if (intraPicture) {
                    if (!field) {
                        horizontal(y, origin, stride, 8, 16, pquant);
                        horizontal(y, origin, stride, 9, 16, pquant);
                    }
                    if (bottomEdge) {
                        horizontal(y, origin, stride, 16, 16, pquant);
                        horizontal(y, origin, stride, 17, 16, pquant);
                    }
                } else {
                    for (var block = 0; block < 4; block++) {
                        var index = (mby * 2 + (block >>> 1)) * luma.columns + mbx * 2 + (block & 1);
                        var split = horizontalSplit(luma.transform[index]);
                        var at = origin + (block & 1) * 8;
                        if (!field) {
                            var bottom = (block >>> 1) == 1;
                            var top = bottom ? 8 : 0;
                            if (split && (bottom ? !last : !first)) {
                                horizontal(y, at, stride, top + 4, 8, pquant);
                                horizontal(y, at, stride, top + 5, 8, pquant);
                            }
                            if (!bottom || !last) {
                                horizontal(y, at, stride, top + 8, 8, pquant);
                                horizontal(y, at, stride, top + 9, 8, pquant);
                            }
                        } else {
                            var parity = block >>> 1;
                            if (split) {
                                horizontal(y, at, stride, 8 + parity, 8, pquant);
                            }
                            if (!last) {
                                horizontal(y, at, stride, 16 + parity, 8, pquant);
                            }
                        }
                    }
                }
                for (var plane = 1; plane < 3; plane++) {
                    var samples = frame.plane(plane);
                    var at = mby * 8 * chromaStride + mbx * 8;
                    var state = chroma[plane - 1];
                    if (!intraPicture && !first && !last
                            && horizontalSplit(state.transform[mby * state.columns + mbx])) {
                        horizontal(samples, at, chromaStride, 4, 8, pquant);
                        horizontal(samples, at, chromaStride, 5, 8, pquant);
                    }
                    if (bottomEdge) {
                        horizontal(samples, at, chromaStride, 8, 8, pquant);
                        horizontal(samples, at, chromaStride, 9, 8, pquant);
                    }
                }
            }
        }
        for (var mby = 0; mby < mbHeight; mby++) {
            for (var mbx = 0; mbx < mbWidth; mbx++) {
                var right = mbx < mbWidth - 1;
                var field = fieldTx[mby * mbWidth + mbx];
                var origin = mby * 16 * stride + mbx * 16;
                if (intraPicture) {
                    vertical(y, origin, stride, 8, 0, 8, pquant);
                    vertical(y, origin, stride, 8, 1, 8, pquant);
                    if (right) {
                        vertical(y, origin, stride, 16, 0, 8, pquant);
                        vertical(y, origin, stride, 16, 1, 8, pquant);
                    }
                } else {
                    for (var block = 0; block < 4; block++) {
                        var index = (mby * 2 + (block >>> 1)) * luma.columns + mbx * 2 + (block & 1);
                        var split = verticalSplit(luma.transform[index]);
                        var column = (block & 1) * 8;
                        var edge = column == 0 || right;
                        if (!field) {
                            var top = (block >>> 1) * 8;
                            for (var parity = 0; parity < 2; parity++) {
                                if (split) {
                                    vertical(y, origin, stride, column + 4, top + parity, 4, pquant);
                                }
                            }
                            if (edge) {
                                vertical(y, origin, stride, column + 8, top, 4, pquant);
                                vertical(y, origin, stride, column + 8, top + 1, 4, pquant);
                            }
                        } else {
                            var parity = block >>> 1;
                            if (split) {
                                vertical(y, origin, stride, column + 4, parity, 8, pquant);
                            }
                            if (edge) {
                                vertical(y, origin, stride, column + 8, parity, 8, pquant);
                            }
                        }
                    }
                }
                for (var plane = 1; plane < 3; plane++) {
                    var samples = frame.plane(plane);
                    var at = mby * 8 * chromaStride + mbx * 8;
                    var state = chroma[plane - 1];
                    if (!intraPicture && verticalSplit(state.transform[mby * state.columns + mbx])) {
                        vertical(samples, at, chromaStride, 4, 0, 4, pquant);
                        vertical(samples, at, chromaStride, 4, 1, 4, pquant);
                    }
                    if (right) {
                        vertical(samples, at, chromaStride, 8, 0, 4, pquant);
                        vertical(samples, at, chromaStride, 8, 1, 4, pquant);
                    }
                }
            }
        }
    }

    /**
     * Returns whether a transform has an internal horizontal edge.
     *
     * @param transform the transform type, a {@code Vc1Codes.TT_*} value
     * @return {@code true} for 8x4 and 4x4
     */
    private static boolean horizontalSplit(int transform) {
        return transform == Vc1Codes.TT_8X4 || transform == Vc1Codes.TT_4X4;
    }

    /**
     * Returns whether a transform has an internal vertical edge.
     *
     * @param transform the transform type, a {@code Vc1Codes.TT_*} value
     * @return {@code true} for 4x8 and 4x4
     */
    private static boolean verticalSplit(int transform) {
        return transform == Vc1Codes.TT_4X8 || transform == Vc1Codes.TT_4X4;
    }

    /**
     * Filters a horizontal edge with the lines of one field: the edge lies just above {@code row}, and the filter
     * reads the lines of the same parity, two rows apart.
     *
     * @param origin the index of the area's top-left sample
     * @param row the first row below the edge, relative to the area
     * @param width the samples along the edge, a multiple of 4
     */
    private static void horizontal(byte[] samples, int origin, int stride, int row, int width, int pquant) {
        var first = origin + row * stride;
        for (var x = 0; x < width; x += 4) {
            segment(samples, first + x, 1, 2 * stride, pquant);
        }
    }

    /**
     * Filters a vertical edge on the lines of one field.
     *
     * @param origin the index of the area's top-left sample
     * @param column the first column right of the edge, relative to the area
     * @param row the first line, relative to the area
     * @param lines the lines of the field along the edge, a multiple of 4
     */
    private static void vertical(byte[] samples, int origin, int stride, int column, int row, int lines,
                                 int pquant) {
        for (var line = 0; line < lines; line += 4) {
            segment(samples, origin + (row + 2 * line) * stride + column, 2 * stride, 1, pquant);
        }
    }

    /**
     * Returns whether two blocks have the same motion vector and reference field.
     *
     * @param first the index of a block
     * @param second the index of the other
     * @return {@code true} when they move alike
     */
    private boolean sameMotion(int first, int second) {
        return state.mv[first] == state.mv[second] && state.opposite[first] == state.opposite[second];
    }

    /**
     * Returns whether a quadrant of a block has coefficients.
     *
     * @param index the block
     * @param quadrant the quadrant, 0 top-left to 3 bottom-right
     * @return {@code true} when coded
     */
    private boolean coded(int index, int quadrant) {
        return ((state.coded[index] >>> quadrant) & 1) != 0;
    }

    /**
     * Returns whether the macroblock of a block is a 1-MV macroblock.
     *
     * @param bx the block column
     * @param by the block row
     * @return {@code true} for a 1-MV macroblock
     */
    private boolean oneMvInter(int bx, int by) {
        var mb = planeIndex == 0 ? (by >> 1) * mbWidth + (bx >> 1) : by * mbWidth + bx;
        return !fourMv[mb];
    }

    /**
     * Returns whether one segment of the horizontal edge above a block of a P picture is filtered (section 8.6.2).
     *
     * @param bx the block column
     * @param by the block row, above which the edge lies
     * @param segment the 4-sample half of the edge, 0 left
     * @return {@code true} to filter it
     */
    private boolean horizontalBoundary(int bx, int by, int segment) {
        var columns = state.columns;
        var upper = (by - 1) * columns + bx;
        var lower = by * columns + bx;
        if (state.intra[upper] || state.intra[lower]) {
            return true;
        }
        if (mainProfile) {
            if (firstIntra && oneMvInter(bx, by)) {
                return true;
            }
            if (state.transform[upper] == Vc1Codes.TT_4X4 || state.transform[lower] == Vc1Codes.TT_4X4) {
                return true;
            }
        }
        if (!sameMotion(upper, lower)) {
            return true;
        }
        return coded(upper, 2 + segment) || coded(lower, segment);
    }

    /**
     * Returns whether one segment of the internal horizontal edge of an 8x4 or 4x4 transformed block is filtered.
     *
     * @param bx the block column
     * @param by the block row
     * @param segment the 4-sample half of the edge, 0 left
     * @return {@code true} to filter it
     */
    private boolean horizontalInternal(int bx, int by, int segment) {
        var index = by * state.columns + bx;
        var transform = state.transform[index];
        if (state.intra[index] || (transform != Vc1Codes.TT_8X4 && transform != Vc1Codes.TT_4X4)) {
            return false;
        }
        return coded(index, segment) || coded(index, 2 + segment);
    }

    /**
     * Returns whether one segment of the vertical edge left of a block of a P picture is filtered (section 8.6.2).
     *
     * @param bx the block column, left of which the edge lies
     * @param by the block row
     * @param segment the 4-sample half of the edge, 0 top
     * @return {@code true} to filter it
     */
    private boolean verticalBoundary(int bx, int by, int segment) {
        var columns = state.columns;
        var left = by * columns + bx - 1;
        var right = by * columns + bx;
        if (state.intra[left] || state.intra[right]) {
            return true;
        }
        var codedLeft = left;
        if (mainProfile) {
            if (firstIntra && oneMvInter(bx, by)) {
                return true;
            }
            if (planeIndex == 0 && (bx & 1) == 1 && (by & 1) == 1) {
                var block1 = right - columns;
                if (state.transform[block1] == Vc1Codes.TT_4X4 || state.transform[right] == Vc1Codes.TT_4X4) {
                    return true;
                }
                if (!sameMotion(left, right)) {
                    return true;
                }
                if (state.intra[block1]) {
                    return state.coded[block1] != 0 || coded(right, 2 * segment);
                }
                codedLeft = block1;
            } else if (state.transform[left] == Vc1Codes.TT_4X4 || state.transform[right] == Vc1Codes.TT_4X4) {
                return true;
            }
        }
        if (!sameMotion(left, right)) {
            return true;
        }
        return coded(codedLeft, 2 * segment + 1) || coded(right, 2 * segment);
    }

    /**
     * Returns whether one segment of the internal vertical edge of a 4x8 or 4x4 transformed block is filtered.
     *
     * @param bx the block column
     * @param by the block row
     * @param segment the 4-sample half of the edge, 0 top
     * @return {@code true} to filter it
     */
    private boolean verticalInternal(int bx, int by, int segment) {
        var index = by * state.columns + bx;
        var transform = state.transform[index];
        if (state.intra[index] || (transform != Vc1Codes.TT_4X8 && transform != Vc1Codes.TT_4X4)) {
            return false;
        }
        return coded(index, 2 * segment) || coded(index, 2 * segment + 1);
    }

    /**
     * Filters one 4-sample segment of an edge (Figures 82 and 83).
     *
     * @param samples the plane
     * @param first the index of the sample just past the edge on the first line of the segment
     * @param along the distance between the lines of the segment
     * @param across the distance between samples across the edge
     * @param pquant the picture quantizer
     */
    static void segment(byte[] samples, int first, int along, int across, int pquant) {
        if (filterPair(samples, first + 2 * along, across, pquant)) {
            filterPair(samples, first, across, pquant);
            filterPair(samples, first + along, across, pquant);
            filterPair(samples, first + 3 * along, across, pquant);
        }
    }

    /**
     * Filters the samples across the edge on one line (Figures 82 and 83).
     *
     * @param samples the plane
     * @param at the index of the first sample past the edge
     * @param across the distance between samples across the edge
     * @param pquant the picture quantizer
     * @return {@code true} when the line was filtered, which decides whether the other lines of a segment are
     */
    private static boolean filterPair(byte[] samples, int at, int across, int pquant) {
        var p1 = samples[at - 4 * across] & 0xFF;
        var p2 = samples[at - 3 * across] & 0xFF;
        var p3 = samples[at - 2 * across] & 0xFF;
        var p4 = samples[at - across] & 0xFF;
        var p5 = samples[at] & 0xFF;
        var p6 = samples[at + across] & 0xFF;
        var p7 = samples[at + 2 * across] & 0xFF;
        var p8 = samples[at + 3 * across] & 0xFF;
        var a0 = (2 * (p3 - p6) - 5 * (p4 - p5) + 4) >> 3;
        if (Math.abs(a0) >= pquant) {
            return false;
        }
        var a1 = (2 * (p1 - p4) - 5 * (p2 - p3) + 4) >> 3;
        var a2 = (2 * (p5 - p8) - 5 * (p6 - p7) + 4) >> 3;
        var a3 = Math.min(Math.abs(a1), Math.abs(a2));
        if (a3 >= Math.abs(a0)) {
            return false;
        }
        var d = 5 * ((Integer.signum(a0) * a3) - a0) / 8;
        var clip = (p4 - p5) / 2;
        if (clip == 0) {
            return false;
        }
        if (clip > 0) {
            d = d < 0 ? 0 : Math.min(d, clip);
        } else {
            d = d > 0 ? 0 : Math.max(d, clip);
        }
        samples[at - across] = (byte) (p4 - d);
        samples[at] = (byte) (p5 + d);
        return true;
    }
}
