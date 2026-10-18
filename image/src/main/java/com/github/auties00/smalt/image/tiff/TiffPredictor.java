package com.github.auties00.smalt.image.tiff;

/**
 * Enumerates the TIFF predictors, as the {@code Predictor} tag (317) names them.
 *
 * <p>A predictor transforms the samples of each row before compression so that LZW or Deflate
 * compresses them better; it is reversed after decompression and never changes the pixels.
 */
public enum TiffPredictor {
    /**
     * No prediction (code 1).
     */
    NONE(1),

    /**
     * Horizontal differencing of integer samples, TIFF 6.0 section 14 (code 2).
     */
    HORIZONTAL(2),

    /**
     * Byte-plane differencing of floating-point samples, Adobe Photoshop TIFF Technical Note 3
     * (code 3).
     */
    FLOATING_POINT(3);

    /**
     * The value of the tag.
     */
    private final int code;

    /**
     * Constructs a constant.
     *
     * @param code the tag value
     */
    TiffPredictor(int code) {
        this.code = code;
    }

    /**
     * Returns the value of the {@code Predictor} tag for this predictor.
     *
     * @return the tag value
     */
    public int code() {
        return code;
    }

    /**
     * Returns the predictor of a tag value.
     *
     * @param code the tag value
     * @return the predictor, {@link #NONE} for an unknown value
     */
    public static TiffPredictor of(int code) {
        return switch (code) {
            case 2 -> HORIZONTAL;
            case 3 -> FLOATING_POINT;
            default -> NONE;
        };
    }
}
