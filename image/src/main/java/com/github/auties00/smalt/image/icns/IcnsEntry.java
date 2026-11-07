package com.github.auties00.smalt.image.icns;

import java.util.Objects;

/**
 * Describes one image of an ICNS icon family.
 *
 * <p>The size of a PNG image comes from its {@code IHDR} chunk, since the element type only names the size it is
 * meant for; the size of every other image is the one its type defines.
 *
 * @param index the index of the image among the images of the file, in file order
 * @param type the four-character element type, such as {@code ic08} or {@code is32}
 * @param width the width in pixels
 * @param height the height in pixels
 * @param bitDepth the bits per pixel (1, 4 or 8 for indexed icons, 24 for an RGB icon, 32 for an RGB icon with an
 * 8-bit mask and for ARGB icons, the sample depth times the channels for a PNG image), or {@code 0} when unknown
 * @param encoding how the image is stored
 * @param dark whether the image belongs to the dark appearance the family nests in its {@code 0xFDD92FA8} element
 */
public record IcnsEntry(int index, String type, int width, int height, int bitDepth, Encoding encoding,
                        boolean dark) {
    /**
     * Validates the entry.
     *
     * @throws NullPointerException if {@code type} or {@code encoding} is {@code null}
     */
    public IcnsEntry {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(encoding, "encoding");
    }

    /**
     * Enumerates the ways an ICNS element stores its image.
     */
    public enum Encoding {
        /**
         * A complete PNG file.
         */
        PNG,

        /**
         * A JPEG 2000 file or codestream.
         */
        JPEG_2000,

        /**
         * The {@code ARGB} tag followed by run-length coded alpha, red, green and blue planes.
         */
        ARGB,

        /**
         * Red, green and blue planes, run-length coded or stored, with the 8-bit mask element of the same size as
         * alpha when the family has it.
         */
        RGB,

        /**
         * A 1, 4 or 8-bit icon in the Mac OS system palette, with the 1-bit mask of the same size as alpha when the
         * family has it.
         */
        INDEXED
    }
}
