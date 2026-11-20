package com.github.auties00.smalt.capture.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.capture.ScreenCaptureOptions;

/**
 * The rectangle of a source's image that a screen or window session captures, and the even size of its
 * pictures.
 *
 * @param x the left edge on the source's image
 * @param y the top edge on the source's image
 * @param width the even width of the pictures, at least 2
 * @param height the even height of the pictures, at least 2
 */
public record CaptureArea(int x, int y, int width, int height) {
    /**
     * Resolves the area of a capture on a source of a size.
     *
     * @param options the options naming the region, if any
     * @param sourceWidth the width of the source's image
     * @param sourceHeight the height of the source's image
     * @param what the source, named in failures
     * @return the area: the region clipped to the source, or the whole source, cropped to even dimensions
     * @throws MediaException.Unsupported if the region lies outside the source or the area is smaller than
     * 2x2 pixels
     */
    public static CaptureArea of(ScreenCaptureOptions options, int sourceWidth, int sourceHeight, String what) {
        if (sourceWidth < 2 || sourceHeight < 2) {
            throw new MediaException.Unsupported("capture size", what + " is " + sourceWidth + "x" + sourceHeight + " pixels");
        }
        var region = options.region().orElse(null);
        if (region == null) {
            return new CaptureArea(0, 0, sourceWidth & ~1, sourceHeight & ~1);
        }
        var clipped = region.clip(sourceWidth, sourceHeight).orElseThrow(() -> new MediaException.Unsupported(
                "capture region", "the region " + region + " lies outside " + what + " (" + sourceWidth + "x" + sourceHeight + ")"));
        var width = clipped.width() & ~1;
        var height = clipped.height() & ~1;
        if (width < 2 || height < 2) {
            throw new MediaException.Unsupported("capture region", "the part of the region on " + what + " is smaller than 2x2 pixels");
        }
        return new CaptureArea(clipped.x(), clipped.y(), width, height);
    }

    /**
     * Returns whether the area covers a whole source of a size, so no cropping is needed.
     *
     * @param sourceWidth the width of the source's image
     * @param sourceHeight the height of the source's image
     * @return whether the area starts at the origin and spans the even part of the source
     */
    public boolean covers(int sourceWidth, int sourceHeight) {
        return x == 0 && y == 0 && width == (sourceWidth & ~1) && height == (sourceHeight & ~1);
    }
}
