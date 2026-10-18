package com.github.auties00.smalt.image.color;

/**
 * Identifies the color space of the data a {@link ColorProfile} describes.
 *
 * <p>Constants may be added in minor releases; a {@code switch} over them needs a default.
 */
public enum ProfileColorSpace {
    /**
     * One gray channel.
     */
    GRAY(1),

    /**
     * Red, green and blue.
     */
    RGB(3),

    /**
     * Cyan, magenta, yellow and black ink.
     */
    CMYK(4),

    /**
     * Cyan, magenta and yellow ink.
     */
    CMY(3),

    /**
     * CIE L*a*b*.
     */
    LAB(3),

    /**
     * CIE XYZ.
     */
    XYZ(3),

    /**
     * Y'CbCr.
     */
    YCBCR(3),

    /**
     * Any other space: CIE L*u*v*, Yxy, HSV, HLS, or a space of 2 to 15 generic colorants.
     */
    OTHER(0);

    /**
     * The number of channels, or {@code 0} when it varies.
     */
    private final int channels;

    /**
     * Creates a constant.
     *
     * @param channels the number of channels, or {@code 0} when it varies
     */
    ProfileColorSpace(int channels) {
        this.channels = channels;
    }

    /**
     * Returns the color space of an ICC signature.
     *
     * @param signature the color space signature
     * @return the constant, {@link #OTHER} when it has none of its own
     */
    static ProfileColorSpace of(int signature) {
        return switch (IccReader.name(signature)) {
            case "GRAY" -> GRAY;
            case "RGB " -> RGB;
            case "CMYK" -> CMYK;
            case "CMY " -> CMY;
            case "Lab " -> LAB;
            case "XYZ " -> XYZ;
            case "YCbr" -> YCBCR;
            default -> OTHER;
        };
    }

    /**
     * Returns the number of channels of the space.
     *
     * @return the channel count, or {@code 0} for {@link #OTHER}, whose profiles report theirs
     *         through {@link ColorProfile#channels()}
     */
    public int channels() {
        return channels;
    }
}
