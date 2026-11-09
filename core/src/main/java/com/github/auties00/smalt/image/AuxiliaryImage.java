package com.github.auties00.smalt.image;

import java.util.Objects;

/**
 * Describes an image stored next to the main image of a file: a depth map, a matte, a gain map, a thumbnail, a
 * view of a stereo pair, or another Multi-Picture Format image.
 *
 * <p>The decoders that store such images list them ({@code auxiliaryImages}) and decode one by its index
 * ({@code decodeAuxiliaryImage}).
 *
 * <p>Instances are immutable and thread-safe.
 *
 * @param index the position of the image in the list of its file, from 0; it names the image to decode
 * @param type the role of the image
 * @param identifier the identifier the file gives the role: the {@code aux_type} URN of a HEIF auxiliary image,
 * {@code "thmb"} for a HEIF thumbnail, {@code "tmap"} for the gain map of a HEIF {@code tmap} item, or
 * {@code "mpf:"} followed by the six hexadecimal digits of the type code of a Multi-Picture Format image (with
 * {@code "/"} and the {@code Item:Semantic} of the XMP container directory when it names one, as
 * {@code "mpf:000000/GainMap"}), or {@code "container:"} followed by the {@code Item:Semantic}, possibly empty,
 * of an image that only the XMP container directory of a JPEG file lists
 * @param width the width the file declares, in pixels, positive
 * @param height the height the file declares, in pixels, positive
 */
public record AuxiliaryImage(int index, AuxiliaryImageType type, String identifier, int width, int height) {
    /**
     * Validates the description.
     *
     * @throws IllegalArgumentException if the index is negative or a dimension is not positive
     */
    public AuxiliaryImage {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(identifier, "identifier");
        if (index < 0) {
            throw new IllegalArgumentException("negative auxiliary image index " + index);
        }
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("invalid auxiliary image size " + width + "x" + height);
        }
    }
}
