package com.github.auties00.smalt.image;

/**
 * Names the role of an image stored next to the main image of a file.
 *
 * <p>This enum may gain constants in later releases; a {@code switch} over it needs a {@code default} branch.
 */
public enum AuxiliaryImageType {
    /**
     * An alpha plane: a HEIF auxiliary image of type {@code urn:mpeg:mpegB:cicp:systems:auxiliary:alpha} or
     * {@code urn:mpeg:hevc:2015:auxid:1}.
     */
    ALPHA,

    /**
     * A depth map: a HEIF auxiliary image of type {@code urn:mpeg:hevc:2015:auxid:2},
     * {@code urn:mpeg:mpegB:cicp:systems:auxiliary:depth} or another depth URN.
     */
    DEPTH,

    /**
     * A view of a stereoscopic image set, which the Multi-Picture Format calls a disparity image: an image of type
     * {@code 0x020002}, taken from another viewpoint than the main image.
     */
    DISPARITY,

    /**
     * An HDR gain map: the gain map input of a HEIF {@code tmap} item, an Apple
     * {@code urn:com:apple:photo:2020:aux:hdrgainmap} auxiliary image, or the gain map of an Ultra HDR or Adobe
     * JPEG file.
     */
    GAIN_MAP,

    /**
     * A segmentation matte, such as Apple's portrait effects and semantic mattes
     * ({@code urn:com:apple:photo:2018:aux:portraiteffectsmatte} and the {@code semantic} mattes).
     */
    MATTE,

    /**
     * A reduced copy of the main image: a HEIF thumbnail item, or a Multi-Picture Format large thumbnail
     * ({@code 0x010001}, {@code 0x010002}).
     */
    THUMBNAIL,

    /**
     * Another image of the scene: a Multi-Picture Format multi-frame image of a panorama ({@code 0x020001}) or of
     * a multi-angle set ({@code 0x020003}), or a further image typed as a baseline primary image
     * ({@code 0x030000}).
     */
    VIEW,

    /**
     * An image of another or an unknown role.
     */
    OTHER
}
