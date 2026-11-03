package com.github.auties00.smalt.internal.heif;

import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.image.ops.Region;

/**
 * Decodes a region of a HEIF image grid from the grid images that intersect it: the window of whole grid images that
 * covers the region, and the crop of that window that equals the clean aperture of the whole grid followed by the
 * crop of the region.
 */
public final class HeifRegions {
    /**
     * Prevents instantiation.
     */
    private HeifRegions() {
        throw new AssertionError();
    }

    /**
     * Describes the grid images a region decode covers.
     *
     * @param x the left edge of the window on the grid canvas
     * @param y the top edge of the window on the grid canvas
     * @param width the width of the window, clipped to the grid output
     * @param height the height of the window, clipped to the grid output
     * @param firstColumn the first grid column of the window
     * @param firstRow the first grid row of the window
     * @param lastColumn the grid column after the last one
     * @param lastRow the grid row after the last one
     */
    public record Window(int x, int y, int width, int height, int firstColumn, int firstRow, int lastColumn,
                         int lastRow) {
        /**
         * Returns whether the window holds a grid image.
         *
         * @param index the index of the grid image in row-major order
         * @param columns the number of grid columns
         * @return whether its column and row lie in the window
         */
        public boolean holds(int index, int columns) {
            var column = index % columns;
            var row = index / columns;
            return column >= firstColumn && column < lastColumn && row >= firstRow && row < lastRow;
        }
    }

    /**
     * Returns the rectangle of a grid canvas that a region of its output image covers: the region moved by the clean
     * aperture.
     *
     * @param region the region, in the pixels of the output image after the clean aperture
     * @param geometry the geometry of the grid item
     * @param width the grid output width
     * @param height the grid output height
     * @return the left, top, right and bottom edges (the last two exclusive) on the grid canvas
     */
    public static int[] canvasArea(Region region, HeifGeometry geometry, int width, int height) {
        var crops = geometry.crops(width, height);
        var left = (crops ? geometry.x() : 0) + region.x();
        var top = (crops ? geometry.y() : 0) + region.y();
        return new int[]{left, top, left + region.width(), top + region.height()};
    }

    /**
     * Returns the window of whole grid images covering a rectangle of a grid canvas.
     *
     * @param area the left, top, right and bottom edges of the rectangle, inside the grid output
     * @param width the grid output width
     * @param height the grid output height
     * @param tileWidth the width of every grid image
     * @param tileHeight the height of every grid image
     * @return the window
     */
    public static Window window(int[] area, int width, int height, int tileWidth, int tileHeight) {
        var firstColumn = area[0] / tileWidth;
        var firstRow = area[1] / tileHeight;
        var lastColumn = (int) ((area[2] + (long) tileWidth - 1) / tileWidth);
        var lastRow = (int) ((area[3] + (long) tileHeight - 1) / tileHeight);
        var x = firstColumn * tileWidth;
        var y = firstRow * tileHeight;
        var right = (int) Math.min(width, (long) lastColumn * tileWidth);
        var bottom = (int) Math.min(height, (long) lastRow * tileHeight);
        return new Window(x, y, right - x, bottom - y, firstColumn, firstRow, lastColumn, lastRow);
    }

    /**
     * Crops a decoded window of a grid canvas to a region of the output image, with the result the clean aperture
     * crop of the whole canvas ({@link HeifPictures#crop}) followed by {@link ImageOps#crop(Picture, Region)} of the
     * region gives.
     *
     * @param window the decoded window, whose origin lies on a chroma sample boundary of the canvas
     * @param x the left edge of the window on the canvas
     * @param y the top edge of the window on the canvas
     * @param geometry the geometry of the grid item
     * @param width the grid output width
     * @param height the grid output height
     * @param region the region, in the pixels of the output image after the clean aperture
     * @return the picture of the region
     */
    public static Picture crop(Picture window, int x, int y, HeifGeometry geometry, int width, int height,
                               Region region) {
        var area = canvasArea(region, geometry, width, height);
        var left = area[0] - x;
        var top = area[1] - y;
        if (geometry.crops(width, height) && !onChromaBoundary(window, geometry.x(), geometry.y())) {
            return HeifPictures.cropToFullChroma(window, left, top, region.width(), region.height());
        }
        return ImageOps.crop(window, left, top, region.width(), region.height());
    }

    /**
     * Returns whether a position lies on a chroma sample boundary of a planar picture, always {@code true} for other
     * pictures.
     *
     * @param picture the picture whose chroma subsampling is checked
     * @param x the column
     * @param y the row
     * @return whether the clean aperture keeps the chroma subsampling at that position
     */
    private static boolean onChromaBoundary(Picture picture, int x, int y) {
        var format = switch (picture) {
            case YuvPicture yuv -> yuv.chromaFormat();
            case Yuv16Picture yuv -> yuv.chromaFormat();
            default -> ChromaFormat.YUV444;
        };
        return (x & ((1 << format.shiftX()) - 1)) == 0 && (y & ((1 << format.shiftY()) - 1)) == 0;
    }
}
