package com.github.auties00.smalt.transcode;

/**
 * Enumerates how an image transcode treats the color space of its input.
 *
 * <p>This enum may gain constants in later releases; a {@code switch} over it needs a {@code default}
 * branch.
 */
public enum ColorMode {
    /**
     * Keeps the pixels in the color space the input declares and records its profile in the output; an
     * output format that cannot record the profile gets the pixels converted to sRGB instead, as
     * {@link com.github.auties00.smalt.image.ImageEncoderOptions#colorProfile()} describes.
     */
    KEEP_PROFILE,

    /**
     * Converts the pixels to sRGB through the profile the input declares, as
     * {@link com.github.auties00.smalt.image.ops.ImageOps#toSrgb(com.github.auties00.smalt.image.DecodedImage)}
     * does, and records no profile.
     */
    CONVERT_TO_SRGB
}
