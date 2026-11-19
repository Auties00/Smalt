package com.github.auties00.smalt.capture.internal.windows;

import com.github.auties00.smalt.capture.internal.CaptureArea;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * Locates the visible part of a window inside the images that capture it, and the rectangle of those
 * images a session converts.
 *
 * <p>A window's image as {@code PrintWindow} or Windows Graphics Capture delivers it may span the window
 * rectangle, which includes the invisible resize borders of a framed window, while a user sees, and
 * {@link TopWindows} lists, its extended frame bounds. When an image has the size of the window rectangle,
 * the visible part is the frame bounds inside it; otherwise the whole image is the visible part. The area a
 * session captures is relative to the visible part: the whole visible part when the session captures the
 * whole window, even after the window was resized, or the region clipped to it. The rectangle is recomputed
 * only when the geometry changes, so a frame allocates nothing. Instances are not thread-safe.
 */
final class WindowImage {
    private final MemorySegment handle;

    /**
     * The window rectangle and the frame bounds, four integers each.
     */
    private final MemorySegment rects;

    /**
     * The area relative to the visible part, or {@code null} for all of it.
     */
    private CaptureArea area;

    /**
     * The crop of the last call to {@link #crop(int, int)}, or {@code null} before the first.
     */
    private CaptureArea cached;

    private int cachedImageWidth = -1;

    private int cachedImageHeight = -1;

    private int cachedOffsetX;

    private int cachedOffsetY;

    private int cachedVisibleWidth;

    private int cachedVisibleHeight;

    /**
     * Creates a locator of a window.
     *
     * @param handle the window
     * @param arena the arena of the scratch memory, which outlives the locator
     */
    WindowImage(MemorySegment handle, Arena arena) {
        this.handle = handle;
        this.rects = arena.allocate(JAVA_INT, 8);
    }

    /**
     * Sets the area relative to the visible part.
     *
     * @param area the area, or {@code null} for the whole visible part
     */
    void area(CaptureArea area) {
        this.area = area;
        this.cached = null;
    }

    /**
     * Reads the current size of the window's visible part.
     *
     * @return the width and height, or {@code null} when the window cannot be read
     */
    int[] visibleSize() {
        if (!TopWindows.bounds(handle, rects.asSlice(16, 16))) {
            return null;
        }
        return new int[]{rects.getAtIndex(JAVA_INT, 6) - rects.getAtIndex(JAVA_INT, 4),
                rects.getAtIndex(JAVA_INT, 7) - rects.getAtIndex(JAVA_INT, 5)};
    }

    /**
     * Returns the rectangle of an image of the window to convert.
     *
     * @param imageWidth the width of the image
     * @param imageHeight the height of the image
     * @return the rectangle, or {@code null} when nothing of the area lies on the image
     */
    CaptureArea crop(int imageWidth, int imageHeight) {
        var offsetX = 0;
        var offsetY = 0;
        var visibleWidth = imageWidth;
        var visibleHeight = imageHeight;
        if (TopWindows.windowRect(handle, rects) && TopWindows.bounds(handle, rects.asSlice(16, 16))) {
            var rectWidth = rects.getAtIndex(JAVA_INT, 2) - rects.getAtIndex(JAVA_INT, 0);
            var rectHeight = rects.getAtIndex(JAVA_INT, 3) - rects.getAtIndex(JAVA_INT, 1);
            if (rectWidth == imageWidth && rectHeight == imageHeight) {
                offsetX = Math.max(0, rects.getAtIndex(JAVA_INT, 4) - rects.getAtIndex(JAVA_INT, 0));
                offsetY = Math.max(0, rects.getAtIndex(JAVA_INT, 5) - rects.getAtIndex(JAVA_INT, 1));
                visibleWidth = Math.min(imageWidth - offsetX, rects.getAtIndex(JAVA_INT, 6) - rects.getAtIndex(JAVA_INT, 4));
                visibleHeight = Math.min(imageHeight - offsetY, rects.getAtIndex(JAVA_INT, 7) - rects.getAtIndex(JAVA_INT, 5));
            }
        }
        if (cached != null && imageWidth == cachedImageWidth && imageHeight == cachedImageHeight && offsetX == cachedOffsetX
                && offsetY == cachedOffsetY && visibleWidth == cachedVisibleWidth && visibleHeight == cachedVisibleHeight) {
            return cached;
        }
        cachedImageWidth = imageWidth;
        cachedImageHeight = imageHeight;
        cachedOffsetX = offsetX;
        cachedOffsetY = offsetY;
        cachedVisibleWidth = visibleWidth;
        cachedVisibleHeight = visibleHeight;
        cached = compose(area, offsetX, offsetY, visibleWidth, visibleHeight);
        return cached;
    }

    /**
     * Places an area of the visible part on the image.
     *
     * @param area the area relative to the visible part, or {@code null} for all of it
     * @param offsetX the left edge of the visible part on the image
     * @param offsetY the top edge of the visible part on the image
     * @param visibleWidth the width of the visible part
     * @param visibleHeight the height of the visible part
     * @return the rectangle on the image, or {@code null} when it is smaller than 2x2 pixels
     */
    static CaptureArea compose(CaptureArea area, int offsetX, int offsetY, int visibleWidth, int visibleHeight) {
        var x = 0;
        var y = 0;
        var width = visibleWidth;
        var height = visibleHeight;
        if (area != null) {
            x = Math.min(area.x(), visibleWidth);
            y = Math.min(area.y(), visibleHeight);
            width = Math.min(area.width(), visibleWidth - x);
            height = Math.min(area.height(), visibleHeight - y);
        }
        width &= ~1;
        height &= ~1;
        if (width < 2 || height < 2) {
            return null;
        }
        return new CaptureArea(offsetX + x, offsetY + y, width, height);
    }
}
