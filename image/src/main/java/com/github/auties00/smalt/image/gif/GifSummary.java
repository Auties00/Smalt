package com.github.auties00.smalt.image.gif;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.io.MediaSource;

/**
 * Summarizes a GIF datastream from one pass over its block structure, skipping the image data.
 *
 * <p>The canvas is the logical screen; a logical screen with a zero width or height takes that
 * dimension from the extent of the first image ({@code left + width}, {@code top + height}), as
 * browsers do. The frame count is the number of images {@link GifReader} yields, including a
 * truncated last one. The datastream declares transparency when an image has a transparent
 * index, the first image does not cover the canvas, an image is disposed to background, or an
 * image is truncated.
 *
 * @param width     the canvas width in pixels, positive
 * @param height    the canvas height in pixels, positive
 * @param frames    the number of images, positive
 * @param loopCount the stored loop count of the first looping application extension, or
 *                  {@code -1} when there is none
 * @param hasAlpha  whether the composited frames may contain transparent pixels
 */
record GifSummary(int width, int height, int frames, int loopCount, boolean hasAlpha) {
    /**
     * Scans a datastream.
     *
     * @param source the input
     * @param limits the limits applied to the scan
     * @return the summary
     * @throws MediaException.Malformed if the header is invalid, the datastream holds no image, or
     *                                  the canvas is empty
     */
    static GifSummary scan(MediaSource source, MediaLimits limits) {
        var reader = new GifReader(source, limits);
        reader.readHeader();
        var frame = new GifFrame();
        var frames = 0;
        var width = reader.screenWidth();
        var height = reader.screenHeight();
        var alpha = false;
        while (reader.nextFrame(frame, false)) {
            if (frames == 0) {
                width = canvasSide(width, frame.left, frame.width);
                height = canvasSide(height, frame.top, frame.height);
                alpha = !frame.covers(width, height);
            }
            frames++;
            alpha |= frame.transparentIndex >= 0 || frame.disposal == GifFrame.DISPOSE_BACKGROUND || frame.truncated;
        }
        if (frames == 0) {
            throw new MediaException.Malformed("GIF datastream holds no image");
        }
        if (width <= 0 || height <= 0) {
            throw new MediaException.Malformed("GIF canvas is empty");
        }
        return new GifSummary(width, height, frames, reader.loopCount(), alpha);
    }

    /**
     * Returns one side of the canvas.
     *
     * @param screen the logical screen side as stored
     * @param offset the first image's offset along that side
     * @param extent the first image's size along that side
     * @return {@code screen}, or {@code offset + extent} when {@code screen} is zero
     */
    static int canvasSide(int screen, int offset, int extent) {
        return screen != 0 ? screen : offset + extent;
    }
}
