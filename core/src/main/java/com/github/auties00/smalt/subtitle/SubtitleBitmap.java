package com.github.auties00.smalt.subtitle;

import com.github.auties00.smalt.image.RgbaPicture;

import java.util.Objects;

/**
 * Holds a drawn cue: its picture and where it goes on the picture it was drawn for.
 *
 * <p>The picture has straight (not premultiplied) alpha and sRGB colors, and is transparent outside
 * the text and its outline. Its position may place part of it outside the target picture, which then
 * clips it.
 *
 * <p>Instances are not thread-safe; the picture is shared, not copied.
 *
 * @param picture the drawn text, with alpha
 * @param x the column of the target picture where its left edge goes; may be negative
 * @param y the row of the target picture where its top edge goes; may be negative
 */
public record SubtitleBitmap(RgbaPicture picture, int x, int y) {
    /**
     * Validates the bitmap.
     *
     * @throws NullPointerException if {@code picture} is {@code null}
     */
    public SubtitleBitmap {
        Objects.requireNonNull(picture, "picture");
    }
}
