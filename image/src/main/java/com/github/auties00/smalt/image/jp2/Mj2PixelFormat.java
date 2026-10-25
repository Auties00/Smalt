package com.github.auties00.smalt.image.jp2;

import com.github.auties00.smalt.image.ChromaFormat;

/**
 * Enumerates the component layouts a {@link Mj2Encoder} codes frames in.
 *
 * <p>The Y'CbCr layouts code the luma and chroma planes as they are, without a component transform, and declare
 * the sYCC color space; the RGB layouts code red, green and blue through the component transform and declare sRGB.
 */
public enum Mj2PixelFormat {
    /**
     * One luma component, declared greyscale.
     */
    GRAY(ChromaFormat.MONOCHROME, 1),

    /**
     * Y'CbCr with both chroma components subsampled 2x2.
     */
    YUV420(ChromaFormat.YUV420, 3),

    /**
     * Y'CbCr with both chroma components subsampled 2x1.
     */
    YUV422(ChromaFormat.YUV422, 3),

    /**
     * Y'CbCr without subsampling.
     */
    YUV444(ChromaFormat.YUV444, 3),

    /**
     * Red, green and blue.
     */
    RGB(null, 3),

    /**
     * Red, green and blue followed by an opacity component, declared as such by the channel definitions of a JP2
     * file.
     */
    RGBA(null, 4);

    /**
     * The chroma layout of a Y'CbCr format, or {@code null} for RGB.
     */
    private final ChromaFormat chromaFormat;

    /**
     * The number of components.
     */
    private final int components;

    /**
     * Constructs a constant.
     *
     * @param chromaFormat the chroma layout, or {@code null} for RGB
     * @param components the number of components
     */
    Mj2PixelFormat(ChromaFormat chromaFormat, int components) {
        this.chromaFormat = chromaFormat;
        this.components = components;
    }

    /**
     * {@return the chroma layout of a gray or Y'CbCr format, or {@code null} for {@link #RGB} and {@link #RGBA}}
     */
    public ChromaFormat chromaFormat() {
        return chromaFormat;
    }

    /**
     * {@return the number of components a frame is coded in, 1, 3 or 4}
     */
    public int components() {
        return components;
    }

    /**
     * {@return whether the format codes red, green and blue rather than luma and chroma}
     */
    public boolean isRgb() {
        return chromaFormat == null;
    }
}
