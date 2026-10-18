package com.github.auties00.smalt.video.vp8;

/**
 * An upscaling factor a VP8 key frame asks the display to apply in one direction, its 2-bit
 * {@code horizontal_scale} or {@code vertical_scale} field (RFC 6386 section 9.1).
 *
 * <p>A decoder outputs frames at their coded size; the factor only tells the display how much to
 * enlarge them.
 */
public enum Vp8Scale {
    /**
     * No scaling, code 0.
     */
    NONE(0, 1, 1),

    /**
     * Upscaling by 5/4, code 1.
     */
    FIVE_FOURTHS(1, 5, 4),

    /**
     * Upscaling by 5/3, code 2.
     */
    FIVE_THIRDS(2, 5, 3),

    /**
     * Upscaling by 2, code 3.
     */
    TWO(3, 2, 1);

    /**
     * The 2-bit field value.
     */
    private final int code;

    /**
     * The numerator of the factor.
     */
    private final int numerator;

    /**
     * The denominator of the factor.
     */
    private final int denominator;

    /**
     * Constructs a constant.
     *
     * @param code the 2-bit field value
     * @param numerator the numerator of the factor
     * @param denominator the denominator of the factor
     */
    Vp8Scale(int code, int numerator, int denominator) {
        this.code = code;
        this.numerator = numerator;
        this.denominator = denominator;
    }

    /**
     * Returns the constant of a 2-bit scale field value.
     *
     * @param code the field value, from 0 to 3
     * @return the constant
     * @throws IllegalArgumentException if {@code code} is outside 0..3
     */
    public static Vp8Scale of(int code) {
        return switch (code) {
            case 0 -> NONE;
            case 1 -> FIVE_FOURTHS;
            case 2 -> FIVE_THIRDS;
            case 3 -> TWO;
            default -> throw new IllegalArgumentException("VP8 scale code " + code + " is not in [0, 3]");
        };
    }

    /**
     * {@return the 2-bit field value, from 0 to 3}
     */
    public int code() {
        return code;
    }

    /**
     * {@return the numerator of the upscaling factor}
     */
    public int numerator() {
        return numerator;
    }

    /**
     * {@return the denominator of the upscaling factor}
     */
    public int denominator() {
        return denominator;
    }
}
