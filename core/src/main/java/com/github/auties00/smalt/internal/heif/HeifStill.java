package com.github.auties00.smalt.internal.heif;

import com.github.auties00.smalt.image.Picture;

/**
 * Holds the reconstructed primary image of a HEIF still image, as the AVIF and HEIC decoders
 * return it.
 *
 * @param picture the picture after the clean aperture
 * @param width the width of the reconstructed image before the clean aperture
 * @param height the height of the reconstructed image before the clean aperture
 * @param geometry the crop and orientation of the transformative properties
 */
public record HeifStill(Picture picture, int width, int height, HeifGeometry geometry) {
}
