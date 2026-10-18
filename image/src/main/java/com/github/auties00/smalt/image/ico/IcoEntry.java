package com.github.auties00.smalt.image.ico;

import java.util.Objects;
import java.util.Optional;

/**
 * Describes one image of an icon or cursor file, as its directory entry and image header declare
 * it.
 *
 * <p>The size and depth come from the image's own header (the PNG {@code IHDR} or the bitmap
 * header) when it can be read, since directory entries are often wrong, and from the directory
 * entry otherwise.
 *
 * @param index    the index of the entry in the directory
 * @param width    the width in pixels
 * @param height   the height in pixels
 * @param bitDepth the bits per pixel (for example 32 for BGRA bitmaps and RGBA PNGs, 8 for
 *                 256-color bitmaps), or {@code 0} when unknown
 * @param encoding how the image is stored
 * @param hotspot  the hotspot of a cursor entry, or empty for an icon
 */
public record IcoEntry(int index, int width, int height, int bitDepth, Encoding encoding, Optional<Hotspot> hotspot) {
    /**
     * Validates the entry.
     *
     * @throws NullPointerException if {@code encoding} or {@code hotspot} is {@code null}
     */
    public IcoEntry {
        Objects.requireNonNull(encoding, "encoding");
        Objects.requireNonNull(hotspot, "hotspot");
    }

    /**
     * Enumerates the ways an entry stores its image.
     */
    public enum Encoding {
        /**
         * A Windows bitmap without its file header, twice as tall as the image: the color rows
         * followed by a 1-bit transparency (AND) mask.
         */
        BMP,

        /**
         * A complete PNG file.
         */
        PNG
    }

    /**
     * Holds the hotspot of a cursor: the pixel that marks the pointer position.
     *
     * @param x the column, from the left edge
     * @param y the row, from the top edge
     */
    public record Hotspot(int x, int y) {
    }
}
