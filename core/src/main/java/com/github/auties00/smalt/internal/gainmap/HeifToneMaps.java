/* Ports libavif 1.3.0 (src/read.c: avifDecoderFindGainMapItem, avifParseToneMappedImageBox). Copyright 2023 Google
 * LLC; BSD 2-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.internal.gainmap;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.AuxiliaryImageType;
import com.github.auties00.smalt.image.ChromaSiting;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ColorPrimaries;
import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.image.ContentLightLevel;
import com.github.auties00.smalt.image.MatrixCoefficients;
import com.github.auties00.smalt.image.TransferCharacteristics;
import com.github.auties00.smalt.image.color.ColorProfile;
import com.github.auties00.smalt.image.gainmap.GainMapMetadata;
import com.github.auties00.smalt.internal.heif.HeifFile;
import com.github.auties00.smalt.internal.heif.HeifItem;
import com.github.auties00.smalt.internal.heif.HeifProperty;
import com.github.auties00.smalt.io.MediaSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Finds the gain maps and the auxiliary images of HEIF and AVIF files: the tone map derived image item
 * ({@code tmap}, ISO/IEC 23008-12:2024 with ISO 21496-1 metadata) whose inputs are the primary image and its gain
 * map, and the auxiliary, thumbnail and gain map items of the primary image.
 *
 * <p>A {@code tmap} item counts when it has data, references exactly two distinct items with {@code dimg}, the
 * first being the primary item, and an alternatives group ({@code altr}) prefers it to the primary item; a file
 * whose brands must include {@code tmap} (AVIF) is ignored without that brand. Its payload is a version byte, 0,
 * followed by the ISO 21496-1 metadata; another version, or metadata of an unsupported minimum version, makes
 * readers ignore the gain map. This class is thread-safe.
 */
public final class HeifToneMaps {
    private HeifToneMaps() {
        throw new AssertionError();
    }

    /**
     * A tone map item with its gain map.
     *
     * @param toneMap the {@code tmap} item, whose properties describe the alternate rendition
     * @param gainMap the gain map image item
     * @param metadata the gain map metadata
     */
    public record ToneMap(HeifItem toneMap, HeifItem gainMap, GainMapMetadata metadata) {
        /**
         * {@return the color profile of the alternate rendition: its ICC {@code colr} property, else its
         * {@code nclx} one, or empty when it has neither or the ICC profile does not parse}
         */
        public Optional<ColorProfile> alternateProfile() {
            var icc = toneMap.property(HeifProperty.ColorIcc.class);
            if (icc != null) {
                try {
                    return Optional.of(ColorProfile.parse(icc.profile()));
                } catch (MediaException exception) {
                    return Optional.empty();
                }
            }
            var nclx = toneMap.property(HeifProperty.ColorNclx.class);
            if (nclx == null) {
                return Optional.empty();
            }
            var primaries = ColorPrimaries.of(nclx.primaries());
            var transfer = TransferCharacteristics.of(nclx.transfer());
            if (primaries == ColorPrimaries.UNSPECIFIED && transfer == TransferCharacteristics.UNSPECIFIED) {
                return Optional.empty();
            }
            var light = alternateLightLevel();
            var color = new ColorInfo(MatrixCoefficients.of(nclx.matrix()), nclx.fullRange() ? ColorRange.FULL
                    : ColorRange.LIMITED, primaries, transfer, ChromaSiting.UNSPECIFIED, Optional.empty(), light);
            return Optional.of(ColorProfile.of(color));
        }

        /**
         * {@return the content light level of the alternate rendition, or empty when its {@code clli} property is
         * absent}
         */
        public Optional<ContentLightLevel> alternateLightLevel() {
            var clli = toneMap.property(HeifProperty.ContentLightLevel.class);
            return clli == null ? Optional.empty() : Optional.of(new ContentLightLevel(
                    clli.maxContentLightLevel(), clli.maxPictureAverageLightLevel()));
        }
    }

    /**
     * Finds the tone map item of a primary image.
     *
     * @param source the file
     * @param file the parsed file
     * @param primary the primary item
     * @param limits the bounds of the item data
     * @param brand whether the {@code tmap} brand is required
     * @return the tone map, or {@code null} when the file has none that counts
     * @throws MediaException.Malformed if the metadata of a counting item is invalid
     * @throws MediaException.Io if reading the source fails
     */
    public static ToneMap find(MediaSource source, HeifFile file, HeifItem primary, MediaLimits limits,
                               boolean brand) {
        if (brand && !file.hasBrand("tmap")) {
            return null;
        }
        var preferred = file.preferredAlternatives(primary.id());
        for (var item : file.items()) {
            if (!item.type().equals("tmap") || !item.isLocated() || item.size() == 0 || !preferred.contains(item)) {
                continue;
            }
            var inputs = item.references("dimg");
            if (inputs.length != 2 || inputs[0] != primary.id() || inputs[1] == inputs[0]) {
                continue;
            }
            var gainMap = file.item(inputs[1]);
            if (gainMap == null) {
                continue;
            }
            var payload = file.readItem(source, item, limits);
            if (payload.length < 1 || payload[0] != 0) {
                return null;
            }
            try {
                var metadata = GainMapMetadata.parse(Arrays.copyOfRange(payload, 1, payload.length));
                return new ToneMap(item, gainMap, metadata);
            } catch (MediaException.Unsupported exception) {
                return null;
            }
        }
        return null;
    }

    /**
     * An auxiliary image of a primary image.
     *
     * @param item the image item
     * @param type its role
     * @param identifier its identifier, as {@link com.github.auties00.smalt.image.AuxiliaryImage} defines it
     */
    public record Auxiliary(HeifItem item, AuxiliaryImageType type, String identifier) {
    }

    /**
     * Lists the auxiliary images of a primary image: its auxiliary items ({@code auxl} references, by their
     * {@code auxC} type), its thumbnails ({@code thmb}) and the gain map of its tone map item.
     *
     * @param source the file
     * @param file the parsed file
     * @param primary the primary item
     * @param limits the bounds of the item data
     * @param brand whether the tone map needs the {@code tmap} brand
     * @return the images, auxiliary items first, then thumbnails, then the gain map, each in item order
     */
    public static List<Auxiliary> auxiliaries(MediaSource source, HeifFile file, HeifItem primary, MediaLimits limits,
                                              boolean brand) {
        var result = new ArrayList<Auxiliary>();
        for (var item : file.auxiliariesOf(primary.id())) {
            var auxiliary = item.property(HeifProperty.AuxiliaryType.class);
            var urn = auxiliary == null ? "" : auxiliary.urn();
            result.add(new Auxiliary(item, type(urn), urn));
        }
        for (var item : file.thumbnailsOf(primary.id())) {
            result.add(new Auxiliary(item, AuxiliaryImageType.THUMBNAIL, "thmb"));
        }
        ToneMap toneMap;
        try {
            toneMap = find(source, file, primary, limits, brand);
        } catch (MediaException.Malformed exception) {
            toneMap = null;
        }
        if (toneMap != null) {
            result.add(new Auxiliary(toneMap.gainMap(), AuxiliaryImageType.GAIN_MAP, "tmap"));
        }
        return result;
    }

    /**
     * {@return the role an {@code aux_type} URN names}
     */
    static AuxiliaryImageType type(String urn) {
        if (urn.equals(HeifProperty.AuxiliaryType.ALPHA_URN) || urn.equals(HeifProperty.AuxiliaryType.HEVC_ALPHA_URN)) {
            return AuxiliaryImageType.ALPHA;
        }
        if (urn.equals(AppleGainMaps.URN)) {
            return AuxiliaryImageType.GAIN_MAP;
        }
        if (urn.equals("urn:mpeg:hevc:2015:auxid:2") || urn.contains("depth")) {
            return AuxiliaryImageType.DEPTH;
        }
        if (urn.contains("matte")) {
            return AuxiliaryImageType.MATTE;
        }
        return AuxiliaryImageType.OTHER;
    }
}
