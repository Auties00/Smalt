package com.github.auties00.smalt.transcode;

/**
 * Enumerates what an image transcode does with the EXIF block and the XMP packet of its input.
 *
 * <p>The color profile is not metadata in this sense: {@link ColorMode} decides it, whatever this mode
 * says. The pixels a transcode writes are turned upright, so a kept EXIF block and XMP packet record the
 * orientation {@link com.github.auties00.smalt.image.Orientation#NORMAL}.
 *
 * <p>This enum may gain constants in later releases; a {@code switch} over it needs a {@code default}
 * branch.
 */
public enum MetadataMode {
    /**
     * Keeps the EXIF block and the XMP packet: the EXIF block keeps every entry and its maker note, except
     * that its orientation is reset, its {@code PixelXDimension} and {@code PixelYDimension} entries take
     * the output size and its thumbnail, which would show the uncropped and unrotated input, is removed;
     * the XMP packet has its {@code tiff:Orientation} reset the same way. An EXIF block that is not a TIFF
     * structure, or that a JPEG output cannot hold (more than the 65527 bytes of one {@code APP1} segment
     * once its thumbnail is removed), is left out.
     */
    KEEP,

    /**
     * Writes neither an EXIF block nor an XMP packet.
     */
    STRIP,

    /**
     * Keeps the EXIF block and the XMP packet as {@link #KEEP} does, without the location: the EXIF GPS
     * directory is removed and its bytes zeroed, and the XMP packet loses its {@code exif:GPS} coordinates and
     * the place names of the Photoshop ({@code City}, {@code State}, {@code Country}) and IPTC
     * ({@code Location}, {@code CountryCode}, {@code LocationCreated}, {@code LocationShown}) schemas, as
     * {@code ImageMetadataEdit.stripLocation()} of {@code smalt-image} removes them. Maker notes are kept as
     * stored.
     */
    STRIP_LOCATION
}
