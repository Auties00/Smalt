package com.github.auties00.smalt.image.gainmap;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.DecodedImage;
import com.github.auties00.smalt.internal.task.ParallelBands;

import java.util.Objects;

/**
 * Renders images with their gain maps and computes gain maps.
 *
 * <p>Both operations work in linear light relative to a reference white: a standard-dynamic-range white is 1, and
 * PQ and HLG values are their luminance divided by the reference white of the options (the HLG reference display
 * peaks at 1000 candelas per square meter, with the BT.2100 system gamma of 1.2 on the luminance). The color of an
 * image is that of its {@link DecodedImage#colorProfile()}, or for a Y'CbCr picture from a file that embeds no ICC
 * profile, the CICP description of the picture; an absent profile is sRGB.
 *
 * <p>This class is thread-safe.
 */
public final class GainMaps {
    private GainMaps() {
        throw new AssertionError();
    }

    /**
     * Renders the alternate rendition of an image in full, as 16-bit PQ in the primaries of the alternate
     * rendition.
     *
     * @param base the base image, as its decoder returns it
     * @param gainMap the gain map of the image
     * @return the rendered image, as {@link #apply(DecodedImage, GainMap, GainMapOptions)} describes
     * @throws MediaException.Unsupported if a color profile is neither RGB nor gray
     * @throws MediaException.Malformed if a color profile is damaged
     * @throws MediaException.LimitExceeded if a converted picture does not fit one Java array
     */
    public static DecodedImage apply(DecodedImage base, GainMap gainMap) {
        return apply(base, gainMap, GainMapOptions.defaults());
    }

    /**
     * Renders an image with its gain map for a display headroom.
     *
     * <p>Each base pixel is decoded to linear light, converted to the primaries the gain map applies in (those of
     * the base rendition, or of the {@linkplain GainMap#alternateProfile() alternate rendition} when
     * {@link GainMapMetadata#useBaseColorSpace()} is {@code false}), multiplied by the gain of its gain map
     * sample with the {@linkplain GainMapMetadata#weight(double) weight} of the headroom and the offsets of the
     * metadata, converted to the output primaries, and encoded with the output transfer function; at a weight of 0
     * the base pixels are kept as they are, without the offsets. A gain map of
     * another size than the image is resampled bilinearly at the pixel centers, in the log2 domain. Values that
     * fall outside the output signal range are clipped.
     *
     * <p>The result holds an {@link com.github.auties00.smalt.image.Rgba16Picture} of the base image's size,
     * with the base image's alpha, and a {@link DecodedImage#colorProfile()} made by
     * {@link com.github.auties00.smalt.image.color.ColorProfile#of} from the output primaries, the output
     * transfer function, the identity matrix and full range, with the content light level of the rendered
     * pixels. It keeps the format, orientation, source size, EXIF, XMP and density of the base image and has no
     * ICC bytes. The output does not depend on the thread count.
     *
     * @apiNote Decode a base image of more than 8 bits, such as the PQ base of an HDR file whose gain map dims it
     * to SDR, and a gain map image of more than 8 bits with
     * {@link com.github.auties00.smalt.image.ImageDecoderOptions#highBitDepth()}: reduced to 8 bits, a PQ base
     * loses the precision its darks need.
     * @param base the base image, as its decoder returns it
     * @param gainMap the gain map of the image
     * @param options the display headroom and the output encoding
     * @return the rendered image
     * @throws MediaException.Unsupported if a color profile is neither RGB nor gray
     * @throws MediaException.Malformed if a color profile is damaged
     * @throws MediaException.LimitExceeded if a converted picture does not fit one Java array
     */
    public static DecodedImage apply(DecodedImage base, GainMap gainMap, GainMapOptions options) {
        Objects.requireNonNull(base, "base");
        Objects.requireNonNull(gainMap, "gainMap");
        Objects.requireNonNull(options, "options");
        try (var _ = ParallelBands.operation()) {
            return GainMapRenderer.render(base, gainMap, options);
        }
    }

    /**
     * Computes the gain map of two renditions of an image with the default options.
     *
     * @param base the rendition the gain map accompanies
     * @param alternate the rendition the gain map produces, of the same size
     * @return the gain map, as {@link #compute(DecodedImage, DecodedImage, GainMapComputeOptions)} describes
     * @throws IllegalArgumentException if the renditions differ in size
     * @throws MediaException.Unsupported if a color profile is neither RGB nor gray
     * @throws MediaException.Malformed if a color profile is damaged
     * @throws MediaException.LimitExceeded if a converted picture does not fit one Java array
     */
    public static GainMap compute(DecodedImage base, DecodedImage alternate) {
        return compute(base, alternate, GainMapComputeOptions.defaults());
    }

    /**
     * Computes the gain map of two renditions of an image.
     *
     * <p>Both renditions are decoded to linear light and converted to the primaries of the base rendition (or of
     * the alternate one when {@link GainMapComputeOptions#useBaseColorSpace()} is {@code false}); the gain of each
     * pixel is the log2 ratio of the alternate value plus its offset to the base value plus its offset, of the
     * luminance for a single-channel gain map and of each channel for a multichannel one. The headroom of each
     * rendition is the log2 of its brightest value, at least 0, unless the options set the alternate one. When
     * the alternate headroom is the smaller, the gains are negated, so that the gain map always holds the ratio of
     * the brighter rendition to the dimmer one. The range of the gains, with at most 0.1% of isolated values left
     * out on each side, is mapped to 8-bit samples with the gamma of the options.
     *
     * <p>The gain map image is a monochrome full-range {@link com.github.auties00.smalt.image.YuvPicture} for a
     * single-channel gain map and an opaque {@link com.github.auties00.smalt.image.RgbaPicture} for a
     * multichannel one. Its {@linkplain GainMap#alternateProfile() alternate profile} is the color profile of the
     * alternate rendition, and its light level the one that profile declares. Applying the gain map to the base
     * rendition at the alternate headroom gives back the alternate rendition within the precision of the 8-bit
     * samples and of the range left out. The result does not depend on the thread count.
     *
     * @param base the rendition the gain map accompanies
     * @param alternate the rendition the gain map produces, of the same size
     * @param options the gain map layout and encoding
     * @return the gain map
     * @throws IllegalArgumentException if the renditions differ in size
     * @throws MediaException.Unsupported if a color profile is neither RGB nor gray
     * @throws MediaException.Malformed if a color profile is damaged
     * @throws MediaException.LimitExceeded if a converted picture does not fit one Java array
     */
    public static GainMap compute(DecodedImage base, DecodedImage alternate, GainMapComputeOptions options) {
        Objects.requireNonNull(base, "base");
        Objects.requireNonNull(alternate, "alternate");
        Objects.requireNonNull(options, "options");
        try (var _ = ParallelBands.operation()) {
            return GainMapComputer.compute(base, alternate, options);
        }
    }
}
