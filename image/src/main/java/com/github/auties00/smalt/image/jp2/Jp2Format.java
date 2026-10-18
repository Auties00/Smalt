package com.github.auties00.smalt.image.jp2;

/**
 * Enumerates the forms a JPEG 2000 encoder writes.
 *
 * <p>Constants may be added in minor releases, so a {@code switch} over this type needs a
 * {@code default} branch.
 */
public enum Jp2Format {
    /**
     * A JP2 file (ISO/IEC 15444-1 annex I, {@code .jp2}): the codestream in boxes that declare its
     * color space, channel roles and ICC profile.
     */
    JP2,

    /**
     * A raw codestream ({@code .j2k} or {@code .j2c}), as embedded in PDF, DICOM and other
     * containers; it carries no color space.
     */
    CODESTREAM
}
