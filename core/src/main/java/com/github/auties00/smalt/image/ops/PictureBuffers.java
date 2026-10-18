package com.github.auties00.smalt.image.ops;

import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.YuvPicture;

/**
 * The source of the pictures a color conversion writes: new ones ({@link #FRESH}) or pictures kept
 * across conversions. A conversion writes every sample of the pictures it obtains.
 */
interface PictureBuffers {
    /**
     * Allocates a new picture on every call.
     */
    PictureBuffers FRESH = new PictureBuffers() {
        @Override
        public RgbaPicture rgba(int width, int height, boolean alpha) {
            return RgbaPicture.allocate(width, height, alpha);
        }

        @Override
        public YuvPicture yuv(int width, int height, ChromaFormat format, boolean alpha, ColorInfo color) {
            return YuvPicture.allocate(width, height, format, alpha, color);
        }
    };

    /**
     * Returns an RGBA picture to write a conversion into.
     *
     * @param width the width in pixels, positive
     * @param height the height in pixels, positive
     * @param alpha whether the picture reports alpha
     * @return the picture
     */
    RgbaPicture rgba(int width, int height, boolean alpha);

    /**
     * Returns a planar picture to write a conversion into.
     *
     * @param width the width in pixels, positive
     * @param height the height in pixels, positive
     * @param format the chroma layout
     * @param alpha whether the picture has an alpha plane
     * @param color the color description
     * @return the picture
     */
    YuvPicture yuv(int width, int height, ChromaFormat format, boolean alpha, ColorInfo color);
}
