package com.github.auties00.smalt.video.h263.internal;

/**
 * Holds one decoded picture of the H.263 annex decoder with what later pictures need from it.
 *
 * <p>The coded area is the picture rounded up to whole 16 by 16 macroblocks, as the picture store of ITU-T H.263 4.1
 * holds it; the planes extend it to whole 32 by 32 macroblocks for the Reduced-Resolution Update mode (Annex Q), and
 * motion compensation takes the samples outside the coded area from its edges. The picture records, on a grid of 16 by
 * 16 cells covering the planes, the coding mode of every cell and the motion vector of each 8 by 8 luminance block,
 * which overlapped motion compensation and the direct mode of B-pictures (O.5.2) read; a 32 by 32 macroblock of the
 * Reduced-Resolution Update mode writes the vector of each of its 16 by 16 blocks to the four blocks of its cell.
 */
final class PlusPicture {
    /**
     * The mode of a macroblock that was not coded: predicted with a zero vector.
     */
    static final byte SKIPPED = 0;

    /**
     * The mode of an INTER macroblock.
     */
    static final byte INTER = 1;

    /**
     * The mode of an INTRA macroblock.
     */
    static final byte INTRA = 2;

    /**
     * The display width.
     */
    final int width;

    /**
     * The display height.
     */
    final int height;

    /**
     * The width in 16 by 16 macroblocks.
     */
    final int mbWidth;

    /**
     * The height in 16 by 16 macroblocks.
     */
    final int mbHeight;

    /**
     * The width of the coded area, {@code mbWidth * 16}.
     */
    final int codedWidth;

    /**
     * The height of the coded area, {@code mbHeight * 16}.
     */
    final int codedHeight;

    /**
     * The width of the planes in 16 by 16 cells, even.
     */
    final int gridWidth;

    /**
     * The height of the planes in 16 by 16 cells, even.
     */
    final int gridHeight;

    /**
     * The luminance plane, {@code gridWidth * 16} samples per row.
     */
    final byte[] y;

    /**
     * The blue difference plane, {@code gridWidth * 8} samples per row.
     */
    final byte[] u;

    /**
     * The red difference plane, {@code gridWidth * 8} samples per row.
     */
    final byte[] v;

    /**
     * The mode of every 16 by 16 cell.
     */
    final byte[] modes;

    /**
     * The horizontal vector of every 8 by 8 luminance block in half samples, four per cell in raster order.
     */
    final int[] vectorX;

    /**
     * The vertical vector of every 8 by 8 luminance block in half samples.
     */
    final int[] vectorY;

    /**
     * The picture coding type, one of the {@code PictureHeader} type constants.
     */
    int type;

    /**
     * The enhancement layer number, 1 for the base layer.
     */
    int layer = 1;

    /**
     * The temporal reference, 8 or 10 bits.
     */
    int temporalReference;

    /**
     * Whether the temporal reference has 10 bits.
     */
    boolean extendedReference;

    /**
     * The time in ticks of 1/1800000 second.
     */
    long time;

    /**
     * The rounding type.
     */
    int rounding;

    /**
     * The warping parameters of the Reference Picture Resampling mode this picture was predicted with, or
     * {@code null}.
     */
    int[] warping;

    /**
     * The fill mode of the Reference Picture Resampling mode, 0 to 3 as Table P.1.
     */
    int fillMode = 3;

    /**
     * The fill color of the Reference Picture Resampling mode, as Y, Cb and Cr.
     */
    final int[] fillColor = {16, 128, 128};

    /**
     * Whether the picture is a keyframe: an I- or EI-picture.
     */
    boolean keyframe;

    /**
     * Whether some part of the picture was concealed rather than decoded.
     */
    boolean damaged;

    /**
     * The number of holders of the picture: reference stores, the output queue and the decoder writing it.
     */
    int users;

    /**
     * The video picture segment of every 16 by 16 cell, -1 where none was recorded.
     */
    final int[] segmentMap;

    /**
     * The left, top, right and bottom luminance sample of every video picture segment, inclusive.
     */
    int[] segmentRects = new int[0];

    /**
     * The number of video picture segments recorded.
     */
    int segmentCount;

    /**
     * Allocates a picture.
     *
     * @param width the display width
     * @param height the display height
     */
    PlusPicture(int width, int height) {
        this.width = width;
        this.height = height;
        this.mbWidth = (width + 15) >> 4;
        this.mbHeight = (height + 15) >> 4;
        this.codedWidth = mbWidth << 4;
        this.codedHeight = mbHeight << 4;
        this.gridWidth = (mbWidth + 1) & ~1;
        this.gridHeight = (mbHeight + 1) & ~1;
        var cells = gridWidth * gridHeight;
        this.y = new byte[cells << 8];
        this.u = new byte[cells << 6];
        this.v = new byte[cells << 6];
        this.modes = new byte[cells];
        this.vectorX = new int[cells << 2];
        this.vectorY = new int[cells << 2];
        this.segmentMap = new int[cells];
    }

    /**
     * Returns the number of bytes a picture of a size holds.
     *
     * @param width the luminance width in samples
     * @param height the luminance height in samples
     * @return the bytes
     */
    static long bytes(int width, int height) {
        long cells = (long) ((((width + 15) >> 4) + 1) & ~1) * ((((height + 15) >> 4) + 1) & ~1);
        return cells * (384 + 1 + 32);
    }

    /**
     * Returns the row stride of the luminance plane.
     *
     * @return the stride
     */
    int lumaStride() {
        return gridWidth << 4;
    }

    /**
     * Returns the row stride of the chrominance planes.
     *
     * @return the stride
     */
    int chromaStride() {
        return gridWidth << 3;
    }

    /**
     * Returns the plane of a component.
     *
     * @param component 0 for luminance, 1 for Cb, 2 for Cr
     * @return the plane
     */
    byte[] plane(int component) {
        return component == 0 ? y : component == 1 ? u : v;
    }

    /**
     * Returns the luminance rectangle of the video picture segment holding a sample, as the Independent Segment
     * Decoding mode delimits it (R.2).
     *
     * @param x the sample column
     * @param y the sample row
     * @param out receives the left, top, right and bottom sample, inclusive
     * @return whether the picture recorded a segment there
     */
    boolean segmentRect(int x, int y, int[] out) {
        if (segmentCount == 0 || x < 0 || y < 0 || x >= gridWidth * 16 || y >= gridHeight * 16) {
            return false;
        }
        var segment = segmentMap[(y >> 4) * gridWidth + (x >> 4)];
        if (segment < 0) {
            return false;
        }
        System.arraycopy(segmentRects, segment * 4, out, 0, 4);
        return true;
    }

}
