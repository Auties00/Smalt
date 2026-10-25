package com.github.auties00.smalt.capture.internal.linux;

import com.github.auties00.smalt.capture.internal.convert.RgbLayout;

import java.lang.foreign.MemorySegment;

import static java.lang.foreign.MemoryLayout.PathElement.groupElement;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

/**
 * Draws the X11 pointer, as XFixes reports it, over grabbed pixels.
 *
 * <p>XFixes reports the pointer's position on the root window, its hot spot and its image as
 * premultiplied ARGB; {@link #compose} blends that image over 32-bit pixels with the "over" operator. The
 * image is read again only when its serial changes. Instances are not thread-safe.
 */
final class X11Cursor {
    private static final long X = X11Windowing.CURSOR_IMAGE.byteOffset(groupElement("x"));

    private static final long Y = X11Windowing.CURSOR_IMAGE.byteOffset(groupElement("y"));

    private static final long WIDTH = X11Windowing.CURSOR_IMAGE.byteOffset(groupElement("width"));

    private static final long HEIGHT = X11Windowing.CURSOR_IMAGE.byteOffset(groupElement("height"));

    private static final long HOT_X = X11Windowing.CURSOR_IMAGE.byteOffset(groupElement("xhot"));

    private static final long HOT_Y = X11Windowing.CURSOR_IMAGE.byteOffset(groupElement("yhot"));

    private static final long SERIAL = X11Windowing.CURSOR_IMAGE.byteOffset(groupElement("cursor_serial"));

    private static final long PIXELS = X11Windowing.CURSOR_IMAGE.byteOffset(groupElement("pixels"));

    private final X11Windowing windowing;

    private final MemorySegment display;

    /**
     * The premultiplied ARGB image of the pointer, row by row.
     */
    private int[] image = new int[0];

    private int width;

    private int height;

    private int hotX;

    private int hotY;

    /**
     * The serial of {@link #image}, or {@code -1} before the first read.
     */
    private long serial = -1;

    /**
     * Creates an overlay over a display connection that has XFixes.
     */
    X11Cursor(X11Windowing windowing, MemorySegment display) {
        this.windowing = windowing;
        this.display = display;
    }

    /**
     * Draws the pointer over a rectangle of the root window.
     *
     * @param pixels the grabbed pixels of the rectangle
     * @param bytesPerLine the row stride of {@code pixels}
     * @param layout the positions of the color samples in a pixel
     * @param left the left edge of the rectangle on the root window
     * @param top the top edge of the rectangle on the root window
     * @param width the width of the rectangle
     * @param height the height of the rectangle
     */
    void draw(byte[] pixels, int bytesPerLine, RgbLayout layout, int left, int top, int width, int height) {
        var cursor = windowing.cursorImage(display);
        if (cursor.equals(MemorySegment.NULL)) {
            return;
        }
        int x;
        int y;
        try {
            x = cursor.get(JAVA_SHORT, X);
            y = cursor.get(JAVA_SHORT, Y);
            var serialNow = cursor.get(JAVA_LONG, SERIAL);
            if (serialNow != serial) {
                var w = Short.toUnsignedInt(cursor.get(JAVA_SHORT, WIDTH));
                var h = Short.toUnsignedInt(cursor.get(JAVA_SHORT, HEIGHT));
                if (w > 512 || h > 512) {
                    return;
                }
                var data = MemorySegment.ofAddress(cursor.get(JAVA_LONG, PIXELS)).reinterpret(8L * w * h);
                if (image.length < w * h) {
                    image = new int[w * h];
                }
                for (var index = 0; index < w * h; index++) {
                    image[index] = (int) data.getAtIndex(JAVA_LONG, index);
                }
                this.width = w;
                this.height = h;
                this.hotX = Short.toUnsignedInt(cursor.get(JAVA_SHORT, HOT_X));
                this.hotY = Short.toUnsignedInt(cursor.get(JAVA_SHORT, HOT_Y));
                serial = serialNow;
            }
        } finally {
            windowing.x11().free(cursor);
        }
        compose(image, this.width, this.height, x - hotX - left, y - hotY - top, pixels, bytesPerLine, layout,
                width, height);
    }

    /**
     * Blends a premultiplied ARGB image over 32-bit pixels.
     *
     * <p>Each covered pixel becomes {@code source + destination * (255 - alpha) / 255}, rounded, per color
     * sample; the part of the image outside the rectangle is skipped.
     *
     * @param image the premultiplied ARGB image, row by row
     * @param imageWidth the width of the image
     * @param imageHeight the height of the image
     * @param x the left edge of the image on the pixels
     * @param y the top edge of the image on the pixels
     * @param pixels the 32-bit pixels receiving the image, row by row
     * @param bytesPerLine the row stride of {@code pixels}
     * @param layout the positions of the color samples in a pixel
     * @param width the width of the pixels
     * @param height the height of the pixels
     */
    static void compose(int[] image, int imageWidth, int imageHeight, int x, int y, byte[] pixels, int bytesPerLine,
                        RgbLayout layout, int width, int height) {
        var fromRow = Math.max(0, -y);
        var toRow = Math.min(imageHeight, height - y);
        var fromColumn = Math.max(0, -x);
        var toColumn = Math.min(imageWidth, width - x);
        for (var row = fromRow; row < toRow; row++) {
            var base = (y + row) * bytesPerLine;
            for (var column = fromColumn; column < toColumn; column++) {
                var argb = image[row * imageWidth + column];
                var alpha = argb >>> 24;
                if (alpha == 0 && (argb & 0xFFFFFF) == 0) {
                    continue;
                }
                var offset = base + 4 * (x + column);
                blend(pixels, offset + layout.rIndex(), (argb >>> 16) & 0xFF, alpha);
                blend(pixels, offset + layout.gIndex(), (argb >>> 8) & 0xFF, alpha);
                blend(pixels, offset + layout.bIndex(), argb & 0xFF, alpha);
            }
        }
    }

    /**
     * Blends one premultiplied sample over one pixel sample.
     */
    private static void blend(byte[] pixels, int index, int source, int alpha) {
        var destination = pixels[index] & 0xFF;
        pixels[index] = (byte) Math.min(255, source + (destination * (255 - alpha) + 127) / 255);
    }
}
