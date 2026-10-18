package com.github.auties00.smalt.image;

/**
 * Enumerates the chroma subsampling layouts of a {@link YuvPicture}.
 *
 * <p>Each layout halves the chroma resolution horizontally and/or vertically, described by the
 * binary logarithm of the subsampling factor in {@link #shiftX()} and {@link #shiftY()}. A chroma
 * plane of a picture {@code w} pixels wide is {@code (w + (1 << shiftX) - 1) >> shiftX} samples
 * wide, so odd dimensions round up.
 *
 * <p>Instances are immutable and thread-safe.
 */
public enum ChromaFormat {
    /**
     * Luma only; the picture has no chroma planes.
     */
    MONOCHROME(0, 0, false),

    /**
     * Chroma halved horizontally and vertically (4:2:0).
     */
    YUV420(1, 1, true),

    /**
     * Chroma halved horizontally (4:2:2).
     */
    YUV422(1, 0, true),

    /**
     * Chroma at full resolution (4:4:4).
     */
    YUV444(0, 0, true);

    /**
     * The binary logarithm of the horizontal chroma subsampling factor.
     */
    private final int shiftX;

    /**
     * The binary logarithm of the vertical chroma subsampling factor.
     */
    private final int shiftY;

    /**
     * Whether the layout carries chroma planes.
     */
    private final boolean hasChroma;

    /**
     * Constructs a layout.
     *
     * @param shiftX    the binary logarithm of the horizontal subsampling factor
     * @param shiftY    the binary logarithm of the vertical subsampling factor
     * @param hasChroma whether the layout carries chroma planes
     */
    ChromaFormat(int shiftX, int shiftY, boolean hasChroma) {
        this.shiftX = shiftX;
        this.shiftY = shiftY;
        this.hasChroma = hasChroma;
    }

    /**
     * Returns the binary logarithm of the horizontal chroma subsampling factor.
     *
     * @return {@code 1} when chroma is halved horizontally, {@code 0} otherwise
     */
    public int shiftX() {
        return shiftX;
    }

    /**
     * Returns the binary logarithm of the vertical chroma subsampling factor.
     *
     * @return {@code 1} when chroma is halved vertically, {@code 0} otherwise
     */
    public int shiftY() {
        return shiftY;
    }

    /**
     * Returns whether the layout carries chroma planes.
     *
     * @return {@code false} only for {@link #MONOCHROME}
     */
    public boolean hasChroma() {
        return hasChroma;
    }

    /**
     * Returns the chroma plane width for a picture of the given luma width.
     *
     * @param lumaWidth the picture width in pixels
     * @return the chroma width, rounded up, or {@code 0} for {@link #MONOCHROME}
     */
    public int chromaWidth(int lumaWidth) {
        return hasChroma ? (lumaWidth + (1 << shiftX) - 1) >> shiftX : 0;
    }

    /**
     * Returns the chroma plane height for a picture of the given luma height.
     *
     * @param lumaHeight the picture height in pixels
     * @return the chroma height, rounded up, or {@code 0} for {@link #MONOCHROME}
     */
    public int chromaHeight(int lumaHeight) {
        return hasChroma ? (lumaHeight + (1 << shiftY) - 1) >> shiftY : 0;
    }
}
