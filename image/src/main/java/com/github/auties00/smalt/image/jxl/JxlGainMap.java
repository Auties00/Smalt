package com.github.auties00.smalt.image.jxl;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.DecodedImage;
import com.github.auties00.smalt.image.color.ColorProfile;
import com.github.auties00.smalt.image.gainmap.GainMap;
import com.github.auties00.smalt.image.gainmap.GainMapMetadata;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

/**
 * Holds the gain map of a JPEG XL file: the content of its {@code jhgm} box, with the gain map image decoded.
 *
 * <p>A gain map describes how to turn the image of the file, the base rendition, into an alternate rendition,
 * typically one with a higher dynamic range: {@link #metadata()} holds the ISO 21496-1 gain map metadata as
 * stored, {@link #image()} the gain map image decoded from {@link #codestream()}, and {@link #alternateIcc()} the
 * color space of the alternate rendition.
 *
 * <p>Two gain maps are equal when their version and bytes are equal and their images are equal as
 * {@link DecodedImage#equals(Object)} compares them. Instances are not thread-safe: the arrays are shared, not
 * copied.
 *
 * @param version the version of the {@code jhgm} box, from 0 to 255; 0 in the current specification
 * @param metadata the gain map metadata, ISO 21496-1 binary metadata as stored
 * @param alternateIcc the ICC profile of the alternate rendition: the profile the box stores, or one synthesized
 * from the color encoding it stores, or empty when it stores neither
 * @param codestream the gain map as stored, a bare JPEG XL codestream
 * @param image the gain map image decoded from {@code codestream}
 */
public record JxlGainMap(int version, byte[] metadata, Optional<byte[]> alternateIcc, byte[] codestream,
                         DecodedImage image) {
    /**
     * Validates the gain map.
     *
     * @throws IllegalArgumentException if the version is not from 0 to 255
     */
    public JxlGainMap {
        Objects.requireNonNull(metadata, "metadata");
        Objects.requireNonNull(alternateIcc, "alternateIcc");
        Objects.requireNonNull(codestream, "codestream");
        Objects.requireNonNull(image, "image");
        if (version < 0 || version > 255) {
            throw new IllegalArgumentException("invalid jhgm version " + version);
        }
    }

    /**
     * Returns this gain map in the format-independent model: its metadata parsed as the binary ISO 21496-1
     * structure ({@link GainMapMetadata#parse(byte[])}), its image, and the alternate ICC profile as the color
     * space of the alternate rendition.
     *
     * @return the gain map; its alternate profile is empty when the box stores none or the stored one does not
     * parse
     * @throws MediaException.Malformed if the metadata is invalid
     * @throws MediaException.Unsupported if the metadata has an unsupported minimum version or the backward
     * direction
     */
    public GainMap toGainMap() {
        var profile = alternateIcc.flatMap(icc -> {
            try {
                return Optional.of(ColorProfile.parse(icc));
            } catch (MediaException exception) {
                return Optional.empty();
            }
        });
        return new GainMap(GainMapMetadata.parse(metadata), image.picture(), profile, Optional.empty());
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof JxlGainMap that && version == that.version && Arrays.equals(metadata, that.metadata)
                && alternateIcc.isPresent() == that.alternateIcc.isPresent()
                && (alternateIcc.isEmpty() || Arrays.equals(alternateIcc.get(), that.alternateIcc.get()))
                && Arrays.equals(codestream, that.codestream) && image.equals(that.image);
    }

    @Override
    public int hashCode() {
        var result = version;
        result = 31 * result + Arrays.hashCode(metadata);
        result = 31 * result + alternateIcc.map(Arrays::hashCode).orElse(0);
        result = 31 * result + Arrays.hashCode(codestream);
        return 31 * result + image.hashCode();
    }

    @Override
    public String toString() {
        return "JxlGainMap[version=" + version + ", metadata=" + metadata.length + " bytes, alternateIcc="
                + alternateIcc.map(icc -> icc.length + " bytes").orElse("none") + ", codestream=" + codestream.length
                + " bytes, image=" + image + "]";
    }
}
