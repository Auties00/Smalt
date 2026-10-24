package com.github.auties00.smalt.image.gainmap;

import com.github.auties00.smalt.image.ContentLightLevel;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.color.ColorProfile;

import java.util.Objects;
import java.util.Optional;

/**
 * Holds a gain map: the image that turns the image it accompanies into an alternate rendition, typically one of
 * a higher dynamic range, with its metadata.
 *
 * <p>The {@link #image()} holds the gain map samples as decoded: a gray picture, whose samples apply to the
 * three color channels, or a color picture with one gain per channel. Its samples are taken at their nominal
 * range, 0 to 1, as {@link com.github.auties00.smalt.image.ops.ImageOps#toChannelFloat} maps them, after
 * conversion to RGB with the matrix and range of a Y'CbCr picture. It may be smaller or larger than the base
 * image; it then covers the same area and is resampled to it. The {@link #alternateProfile()} is the color
 * space of the alternate rendition: the gain map applies in it when {@link GainMapMetadata#useBaseColorSpace()}
 * is {@code false}, and {@link GainMaps#apply} renders in its primaries by default.
 *
 * <p>Two gain maps are equal when their metadata, profiles and light levels are equal and they hold the same
 * picture instance; pictures do not define value equality. Instances are not thread-safe: the picture is
 * shared, not copied.
 *
 * @param metadata the gain map metadata
 * @param image the gain map image
 * @param alternateProfile the color space of the alternate rendition, or empty when the file does not declare
 * one, in which case it is that of the base rendition
 * @param alternateLightLevel the content light level of the alternate rendition, or empty when the file does
 * not declare one
 */
public record GainMap(GainMapMetadata metadata, Picture image, Optional<ColorProfile> alternateProfile,
                      Optional<ContentLightLevel> alternateLightLevel) {
    /**
     * Validates the gain map.
     */
    public GainMap {
        Objects.requireNonNull(metadata, "metadata");
        Objects.requireNonNull(image, "image");
        Objects.requireNonNull(alternateProfile, "alternateProfile");
        Objects.requireNonNull(alternateLightLevel, "alternateLightLevel");
    }

    /**
     * Constructs a gain map without an alternate color space or light level.
     *
     * @param metadata the gain map metadata
     * @param image the gain map image
     */
    public GainMap(GainMapMetadata metadata, Picture image) {
        this(metadata, image, Optional.empty(), Optional.empty());
    }

    /**
     * Returns a copy of this gain map with other metadata.
     *
     * @param metadata the metadata of the copy
     * @return the copy, which shares the picture with this gain map
     */
    public GainMap withMetadata(GainMapMetadata metadata) {
        return new GainMap(metadata, image, alternateProfile, alternateLightLevel);
    }

    /**
     * Returns a copy of this gain map with another alternate color space.
     *
     * @param alternateProfile the color space of the alternate rendition, or empty for that of the base
     * @return the copy, which shares the picture with this gain map
     */
    public GainMap withAlternateProfile(Optional<ColorProfile> alternateProfile) {
        return new GainMap(metadata, image, alternateProfile, alternateLightLevel);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof GainMap that && metadata.equals(that.metadata) && image == that.image
                && alternateProfile.equals(that.alternateProfile)
                && alternateLightLevel.equals(that.alternateLightLevel);
    }

    @Override
    public int hashCode() {
        return Objects.hash(metadata, System.identityHashCode(image), alternateProfile, alternateLightLevel);
    }
}
