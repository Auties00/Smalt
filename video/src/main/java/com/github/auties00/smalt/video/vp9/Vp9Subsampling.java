package com.github.auties00.smalt.video.vp9;

import com.github.auties00.smalt.image.ChromaFormat;

/**
 * Enumerates the chroma subsamplings a VP9 stream codes: the 4:2:0 of profiles 0 and 2 and the
 * 4:2:2, 4:4:0 and 4:4:4 of profiles 1 and 3.
 */
public enum Vp9Subsampling {
    /**
     * Chroma at half the width and half the height of luma.
     */
    YUV420(1, 1),

    /**
     * Chroma at half the width and the full height of luma.
     */
    YUV422(1, 0),

    /**
     * Chroma at the full width and half the height of luma; no {@link ChromaFormat} represents it,
     * so pictures are converted to 4:4:4 and their chroma halved vertically.
     */
    YUV440(0, 1),

    /**
     * Chroma at the full resolution of luma.
     */
    YUV444(0, 0);

    /**
     * The horizontal subsampling shift.
     */
    private final int shiftX;

    /**
     * The vertical subsampling shift.
     */
    private final int shiftY;

    /**
     * Creates a subsampling.
     *
     * @param shiftX the horizontal shift
     * @param shiftY the vertical shift
     */
    Vp9Subsampling(int shiftX, int shiftY) {
        this.shiftX = shiftX;
        this.shiftY = shiftY;
    }

    /**
     * Returns the subsampling of a chroma format.
     *
     * @param format the chroma format, 4:2:0, 4:2:2 or 4:4:4
     * @return the subsampling
     * @throws IllegalArgumentException if {@code format} is {@link ChromaFormat#MONOCHROME}, which
     * VP9 cannot code
     */
    public static Vp9Subsampling of(ChromaFormat format) {
        return switch (format) {
            case YUV420 -> YUV420;
            case YUV422 -> YUV422;
            case YUV444 -> YUV444;
            case MONOCHROME -> throw new IllegalArgumentException("vp9 cannot code monochrome pictures");
        };
    }

    /**
     * {@return the base-2 logarithm of the luma columns per chroma column, 0 or 1}
     */
    public int shiftX() {
        return shiftX;
    }

    /**
     * {@return the base-2 logarithm of the luma rows per chroma row, 0 or 1}
     */
    public int shiftY() {
        return shiftY;
    }
}
