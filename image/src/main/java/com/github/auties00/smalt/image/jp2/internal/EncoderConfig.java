package com.github.auties00.smalt.image.jp2.internal;

import java.util.List;

/**
 * Holds the parameters of one encoding: the image and tile geometry, the coding style shared by
 * every component, the quality layers, the progressions and tile-parts, the region of interest,
 * and the markers to write.
 *
 * @param x0 the left edge of the image on the reference grid
 * @param y0 the top edge of the image on the reference grid
 * @param width the image width
 * @param height the image height
 * @param tileX0 the left edge of the first tile column on the reference grid
 * @param tileY0 the top edge of the first tile row on the reference grid
 * @param tileWidth the nominal tile width
 * @param tileHeight the nominal tile height
 * @param precisions the precision of each component, in bits
 * @param signed whether each component is signed
 * @param subsamplingX the horizontal subsampling of each component
 * @param subsamplingY the vertical subsampling of each component
 * @param resolutions the number of resolution levels
 * @param codeBlockWidth the base-2 logarithm of the code-block width
 * @param codeBlockHeight the base-2 logarithm of the code-block height
 * @param codeBlockStyle the code-block style flags
 * @param reversible whether the reversible 5-3 wavelet (and no quantization) is used
 * @param guardBits the number of guard bits
 * @param precinctWidths the precinct width exponents of each resolution, lowest first, or {@code null} for the
 * maximal precincts
 * @param precinctHeights the precinct height exponents of each resolution, or {@code null}
 * @param progression the progression order, 0 (LRCP) to 4 (CPRL)
 * @param changes the progression changes of every tile, empty for the {@code COD} progression alone
 * @param changesInMainHeader whether the {@code POC} segment goes in the main header rather than the first
 * tile-part header of each tile
 * @param tilePartSplit the letter of the dimension that starts tile-parts ({@code R}, {@code L} or {@code C}), or 0
 * for one tile-part per progression
 * @param ratios the compression ratio of each layer, the last one possibly 0 for a lossless last layer;
 * {@code null} when layers target qualities
 * @param qualities the PSNR target of each layer in decibels, the last one possibly 0 for a lossless last layer;
 * {@code null} when layers target ratios
 * @param capRatio the smallest compression ratio a codestream size limit allows, or 0 for no limit
 * @param maxComponentBytes the largest size of one component's packets in a tile, or 0 for no limit
 * @param transform whether the multiple component transformation is applied to the first three components
 * @param region the region of interest, or {@code null}
 * @param profile the {@code Rsiz} profile bits, 0 for none
 * @param sop whether packets start with {@code SOP} marker segments
 * @param eph whether packet headers end with {@code EPH} markers
 * @param tlm whether the main header lists the tile-part lengths in {@code TLM} segments
 * @param plt whether the tile-part headers list the packet lengths in {@code PLT} segments
 * @param comment the comment of the {@code COM} segment, or {@code null}
 */
public record EncoderConfig(long x0, long y0, int width, int height, long tileX0, long tileY0, int tileWidth,
                            int tileHeight, int[] precisions, boolean[] signed, int[] subsamplingX, int[] subsamplingY,
                            int resolutions, int codeBlockWidth, int codeBlockHeight, int codeBlockStyle,
                            boolean reversible, int guardBits, int[] precinctWidths, int[] precinctHeights,
                            int progression, List<ProgressionChange> changes, boolean changesInMainHeader,
                            char tilePartSplit, double[] ratios, double[] qualities, double capRatio,
                            long maxComponentBytes, boolean transform, Region region, int profile, boolean sop,
                            boolean eph, boolean tlm, boolean plt, String comment) {
    /**
     * Describes the region of interest of an encoding.
     *
     * @param component the component scaled whole, or {@code -1} for a rectangle of every component
     * @param shift the scaling exponent of the whole component
     * @param x0 the left edge of the rectangle on the reference grid
     * @param y0 the top edge of the rectangle on the reference grid
     * @param x1 the right edge of the rectangle on the reference grid (exclusive)
     * @param y1 the bottom edge of the rectangle on the reference grid (exclusive)
     */
    public record Region(int component, int shift, long x0, long y0, long x1, long y1) {
    }

    /**
     * {@return the number of components}
     */
    public int components() {
        return precisions.length;
    }

    /**
     * {@return the number of quality layers}
     */
    public int layers() {
        return ratios != null ? ratios.length : qualities.length;
    }

    /**
     * {@return the right edge of the image on the reference grid (exclusive)}
     */
    long x1() {
        return x0 + width;
    }

    /**
     * {@return the bottom edge of the image on the reference grid (exclusive)}
     */
    long y1() {
        return y0 + height;
    }

    /**
     * {@return the number of tile columns}
     */
    int tilesWide() {
        return (int) Math.ceilDiv(x1() - tileX0, tileWidth);
    }

    /**
     * {@return the number of tile rows}
     */
    int tilesHigh() {
        return (int) Math.ceilDiv(y1() - tileY0, tileHeight);
    }

    /**
     * Returns the area of a tile on the reference grid, clipped to the image.
     *
     * @param tile the tile index, row-major
     * @return the left, top, right and bottom edges
     */
    long[] tileBounds(int tile) {
        var column = tile % tilesWide();
        var row = tile / tilesWide();
        return new long[]{
                Math.max(tileX0 + (long) column * tileWidth, x0), Math.max(tileY0 + (long) row * tileHeight, y0),
                Math.min(tileX0 + (long) (column + 1) * tileWidth, x1()),
                Math.min(tileY0 + (long) (row + 1) * tileHeight, y1())
        };
    }
}
