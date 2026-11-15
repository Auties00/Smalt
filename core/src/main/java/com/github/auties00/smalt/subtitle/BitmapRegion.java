package com.github.auties00.smalt.subtitle;

import com.github.auties00.smalt.image.RgbaPicture;

import java.util.Objects;

/**
 * Holds one picture of a bitmap subtitle and where it is shown.
 *
 * <p>The picture has an alpha channel and is not premultiplied: a pixel whose alpha is zero is not shown. Its
 * position is that of its top-left pixel on the cue's canvas; it may reach past the canvas, whose edges then clip
 * it.
 *
 * <p>Instances are not thread-safe.
 *
 * @param x the column of the picture's left edge on the canvas, in pixels
 * @param y the row of the picture's top edge on the canvas, in pixels
 * @param picture the pixels, with an alpha channel
 */
public record BitmapRegion(int x, int y, RgbaPicture picture) {
    /**
     * Validates the region.
     *
     * @throws IllegalArgumentException if {@code x} or {@code y} is negative or {@code picture} has no alpha
     * channel
     */
    public BitmapRegion {
        Objects.requireNonNull(picture, "picture");
        if (x < 0 || y < 0) {
            throw new IllegalArgumentException("region at " + x + "," + y + " starts before the canvas");
        }
        if (!picture.hasAlpha()) {
            throw new IllegalArgumentException("a subtitle picture needs an alpha channel");
        }
    }

    /**
     * {@return the width of the picture in pixels}
     */
    public int width() {
        return picture.width();
    }

    /**
     * {@return the height of the picture in pixels}
     */
    public int height() {
        return picture.height();
    }
}
