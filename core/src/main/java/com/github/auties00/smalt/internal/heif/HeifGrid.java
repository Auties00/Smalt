package com.github.auties00.smalt.internal.heif;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.internal.io.FourCc;

/**
 * Describes the image grid of a {@code grid} derived image item (ISO/IEC 23008-12 clause
 * 6.6.2.3): its input images, the {@code dimg} references of the item in row-major order, are
 * placed edge to edge on a canvas of {@code columns} by {@code rows} tiles and the result is
 * cropped to {@code outputWidth} by {@code outputHeight} from the top-left corner.
 *
 * @param rows the number of tile rows, from 1 to 256
 * @param columns the number of tile columns, from 1 to 256
 * @param outputWidth the width of the reconstructed image, positive
 * @param outputHeight the height of the reconstructed image, positive
 */
public record HeifGrid(int rows, int columns, long outputWidth, long outputHeight) {
    /**
     * Parses the payload of a {@code grid} item ({@code ImageGrid}).
     *
     * @param payload the item data
     * @return the grid
     * @throws MediaException.Unsupported if the version is not {@code 0}
     * @throws MediaException.Malformed if the payload is truncated, has trailing bytes or declares
     * an empty output
     */
    public static HeifGrid parse(byte[] payload) {
        var box = BoxCursor.strict(payload, FourCc.of("grid"));
        var version = box.u8();
        if (version != 0) {
            throw new MediaException.Unsupported("heif", "image grid version " + version);
        }
        var flags = box.u8();
        var rows = box.u8() + 1;
        var columns = box.u8() + 1;
        long width;
        long height;
        if ((flags & 1) != 0) {
            width = box.u32();
            height = box.u32();
        } else {
            width = box.u16();
            height = box.u16();
        }
        if (box.remaining() != 0) {
            throw new MediaException.Malformed("heif: image grid has " + box.remaining() + " trailing bytes");
        }
        if (width == 0 || height == 0) {
            throw new MediaException.Malformed("heif: image grid output size is " + width + "x" + height);
        }
        return new HeifGrid(rows, columns, width, height);
    }

    /**
     * Returns the number of tiles.
     *
     * @return {@code rows * columns}
     */
    public int tiles() {
        return rows * columns;
    }

    /**
     * Checks that tiles of one size cover the output as ISO/IEC 23008-12 clause 6.6.2.3.1 and
     * MIAF clause 7.3.11.4.2 require.
     *
     * <p>The tiles must cover the whole output, every tile of the last row and column must
     * overlap it, tiles must be at least 64 samples on each side, and with subsampled chroma the
     * tile and output sizes must be even in each subsampled direction.
     *
     * @param tileWidth the width of every tile
     * @param tileHeight the height of every tile
     * @param subsampledX whether chroma is subsampled horizontally
     * @param subsampledY whether chroma is subsampled vertically
     * @throws MediaException.Malformed if the tiles do not form a valid grid
     */
    public void validate(int tileWidth, int tileHeight, boolean subsampledX, boolean subsampledY) {
        validateCoverage(tileWidth, tileHeight);
        if (tileWidth < 64 || tileHeight < 64) {
            throw new MediaException.Malformed("heif: grid tiles of " + tileWidth + "x" + tileHeight
                    + " are smaller than 64x64");
        }
        if ((subsampledX && ((tileWidth & 1) != 0 || (outputWidth & 1) != 0))
                || (subsampledY && ((tileHeight & 1) != 0 || (outputHeight & 1) != 0))) {
            throw new MediaException.Malformed("heif: grid sizes are odd in a chroma-subsampled direction");
        }
    }

    /**
     * Checks that tiles of one size cover the output as ISO/IEC 23008-12 clause 6.6.2.3.1
     * requires, without the MIAF constraints of {@link #validate}.
     *
     * <p>The tiles must cover the whole output and every tile of the last row and column must
     * overlap it.
     *
     * @param tileWidth the width of every tile, positive
     * @param tileHeight the height of every tile, positive
     * @throws MediaException.Malformed if the tiles do not cover the output exactly once
     */
    public void validateCoverage(int tileWidth, int tileHeight) {
        if ((long) tileWidth * columns < outputWidth || (long) tileHeight * rows < outputHeight) {
            throw new MediaException.Malformed("heif: " + columns + "x" + rows + " tiles of " + tileWidth + "x" + tileHeight
                    + " do not cover the " + outputWidth + "x" + outputHeight + " grid");
        }
        if ((long) tileWidth * (columns - 1) >= outputWidth || (long) tileHeight * (rows - 1) >= outputHeight) {
            throw new MediaException.Malformed("heif: the last grid tiles lie outside the " + outputWidth + "x"
                    + outputHeight + " output");
        }
    }
}
