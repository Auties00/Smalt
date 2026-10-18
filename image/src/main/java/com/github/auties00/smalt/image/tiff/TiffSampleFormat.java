package com.github.auties00.smalt.image.tiff;

/**
 * Enumerates the TIFF sample formats, as the {@code SampleFormat} tag (339) names them.
 */
public enum TiffSampleFormat {
    /**
     * Unsigned integers (code 1, and the undefined code 4, which readers treat alike).
     */
    UNSIGNED_INTEGER,

    /**
     * Two's complement signed integers (code 2).
     */
    SIGNED_INTEGER,

    /**
     * IEEE floating point (code 3).
     */
    FLOATING_POINT,

    /**
     * Complex integers or complex floating point (codes 5 and 6), or an unknown code.
     */
    OTHER;

    /**
     * Returns the sample format of a tag value.
     *
     * @param code the tag value
     * @return the sample format
     */
    public static TiffSampleFormat of(int code) {
        return switch (code) {
            case 1, 4 -> UNSIGNED_INTEGER;
            case 2 -> SIGNED_INTEGER;
            case 3 -> FLOATING_POINT;
            default -> OTHER;
        };
    }
}
