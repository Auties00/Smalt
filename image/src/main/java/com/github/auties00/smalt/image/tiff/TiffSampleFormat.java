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
     * Two's complement complex integers (code 5): a real part then an imaginary part, each of half the
     * {@code BitsPerSample}.
     */
    COMPLEX_INTEGER,

    /**
     * IEEE complex floating point (code 6): a real part then an imaginary part, each of half the
     * {@code BitsPerSample}.
     */
    COMPLEX_FLOATING_POINT,

    /**
     * An unknown code.
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
            case 5 -> COMPLEX_INTEGER;
            case 6 -> COMPLEX_FLOATING_POINT;
            default -> OTHER;
        };
    }
}
