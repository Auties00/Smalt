package com.github.auties00.smalt.capture.internal.linux;

import com.github.auties00.smalt.capture.internal.convert.RgbLayout;

import java.lang.foreign.MemorySegment;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT_UNALIGNED;

/**
 * Follows the pointer through the {@code SPA_META_Cursor} metadata of a screen cast's buffers and
 * draws it over the stream's pixels.
 *
 * <p>The metadata is a {@code spa_meta_cursor}: an id, zero when no pointer is to be shown, the
 * position of the pointer's hot spot in the stream, the hot spot's offset in the image and the
 * offset of a {@code spa_meta_bitmap}, zero when the image has not changed. The bitmap holds the
 * image in one of the 4-byte R'G'B' formats; color samples are premultiplied by alpha, as compositors
 * render them. Values are read defensively: a size or offset that leaves the metadata, or a format
 * this class does not read, leaves no image rather than one read from elsewhere.
 *
 * <p>Instances are not thread-safe.
 */
final class ScreenCastCursor {
    /**
     * The largest side of an image, and of the metadata requested.
     */
    static final int MAX_SIDE = 1024;

    /**
     * The size of {@code struct spa_meta_cursor}: {@code id}, {@code flags}, {@code position},
     * {@code hotspot} and {@code bitmap_offset}.
     */
    static final int CURSOR_SIZE = 28;

    /**
     * The size of {@code struct spa_meta_bitmap}: {@code format}, {@code size}, {@code stride} and
     * {@code offset}.
     */
    static final int BITMAP_SIZE = 20;

    /**
     * The premultiplied ARGB image, row by row; the first {@link #imageWidth} times
     * {@link #imageHeight} entries are valid.
     */
    private int[] image = new int[0];

    private int imageWidth;

    private int imageHeight;

    private int hotX;

    private int hotY;

    private int x;

    private int y;

    private boolean visible;

    /**
     * The pixels the last {@link #draw} covered, row by row, until {@link #erase}.
     */
    private byte[] covered = new byte[0];

    private int coveredOffset;

    private int coveredRows;

    private int coveredRowBytes;

    private int coveredStride;

    /**
     * Returns the size of the metadata of a square image.
     *
     * @param side the side of the image
     * @return the size in bytes
     */
    static int metaSize(int side) {
        return CURSOR_SIZE + BITMAP_SIZE + side * side * 4;
    }

    /**
     * Reads the metadata of a buffer.
     *
     * @param meta the metadata, of the size the buffer gives it
     * @return whether the pointer now looks different or is somewhere else
     */
    boolean update(MemorySegment meta) {
        if (meta.byteSize() < CURSOR_SIZE) {
            return false;
        }
        var shown = meta.get(JAVA_INT_UNALIGNED, 0) != 0;
        if (!shown) {
            var changed = visible;
            visible = false;
            return changed;
        }
        var newX = meta.get(JAVA_INT_UNALIGNED, 8);
        var newY = meta.get(JAVA_INT_UNALIGNED, 12);
        var changed = !visible || newX != x || newY != y;
        visible = true;
        x = newX;
        y = newY;
        var bitmapOffset = Integer.toUnsignedLong(meta.get(JAVA_INT_UNALIGNED, 24));
        if (bitmapOffset != 0) {
            var newHotX = meta.get(JAVA_INT_UNALIGNED, 16);
            var newHotY = meta.get(JAVA_INT_UNALIGNED, 20);
            changed |= newHotX != hotX || newHotY != hotY;
            hotX = newHotX;
            hotY = newHotY;
            changed |= readBitmap(meta, bitmapOffset);
        }
        return changed;
    }

    /**
     * Reads a {@code spa_meta_bitmap}.
     *
     * @param meta the metadata
     * @param at the offset of the bitmap
     * @return whether the image may have changed
     */
    private boolean readBitmap(MemorySegment meta, long at) {
        if (at < CURSOR_SIZE || at > meta.byteSize() - BITMAP_SIZE) {
            return clear();
        }
        var format = meta.get(JAVA_INT_UNALIGNED, at);
        if (format == 0) {
            return false;
        }
        var width = meta.get(JAVA_INT_UNALIGNED, at + 4);
        var height = meta.get(JAVA_INT_UNALIGNED, at + 8);
        var stride = meta.get(JAVA_INT_UNALIGNED, at + 12);
        var offset = Integer.toUnsignedLong(meta.get(JAVA_INT_UNALIGNED, at + 16));
        var layout = PortalScreenSession.layout(format);
        if (layout == null || offset < BITMAP_SIZE || width <= 0 || height <= 0 || width > MAX_SIDE || height > MAX_SIDE) {
            return clear();
        }
        var rowBytes = 4 * width;
        if (stride == 0) {
            stride = rowBytes;
        }
        var magnitude = Math.abs((long) stride);
        var first = at + offset;
        var lowest = Math.min(first, first + (long) stride * (height - 1));
        var highest = Math.max(first, first + (long) stride * (height - 1)) + rowBytes;
        if (magnitude < rowBytes || lowest < at + BITMAP_SIZE || highest > meta.byteSize()) {
            return clear();
        }
        if (image.length < width * height) {
            image = new int[width * height];
        }
        var alphaIndex = format >= 11 ? alphaIndex(layout) : -1;
        for (var row = 0; row < height; row++) {
            var base = first + (long) stride * row;
            for (var column = 0; column < width; column++) {
                var pixel = base + 4L * column;
                var alpha = alphaIndex < 0 ? 0xFF : meta.get(JAVA_BYTE, pixel + alphaIndex) & 0xFF;
                image[row * width + column] = alpha << 24
                        | (meta.get(JAVA_BYTE, pixel + layout.rIndex()) & 0xFF) << 16
                        | (meta.get(JAVA_BYTE, pixel + layout.gIndex()) & 0xFF) << 8
                        | (meta.get(JAVA_BYTE, pixel + layout.bIndex()) & 0xFF);
            }
        }
        imageWidth = width;
        imageHeight = height;
        return true;
    }

    /**
     * Returns the position of the alpha sample in a pixel of a layout: the one the color samples leave.
     */
    private static int alphaIndex(RgbLayout layout) {
        return 6 - layout.rIndex() - layout.gIndex() - layout.bIndex();
    }

    /**
     * Forgets the image.
     *
     * @return whether there was one
     */
    private boolean clear() {
        var had = imageWidth != 0;
        imageWidth = 0;
        imageHeight = 0;
        return had;
    }

    /**
     * Returns whether the last metadata asks for a pointer that has an image.
     */
    boolean drawable() {
        return visible && imageWidth != 0;
    }

    /**
     * Draws the pointer over pixels, keeping what it covers for {@link #erase(byte[])}.
     *
     * @param pixels the 4-byte pixels of the stream
     * @param stride the row stride of {@code pixels}
     * @param layout the positions of the color samples in a pixel
     * @param width the width of the stream
     * @param height the height of the stream
     */
    void draw(byte[] pixels, int stride, RgbLayout layout, int width, int height) {
        coveredRows = 0;
        if (!drawable()) {
            return;
        }
        var left = (long) x - hotX;
        var top = (long) y - hotY;
        var fromColumn = Math.max(0, left);
        var toColumn = Math.min(width, left + imageWidth);
        var fromRow = Math.max(0, top);
        var toRow = Math.min(height, top + imageHeight);
        if (fromColumn >= toColumn || fromRow >= toRow) {
            return;
        }
        coveredRows = (int) (toRow - fromRow);
        coveredRowBytes = (int) (toColumn - fromColumn) * 4;
        coveredStride = stride;
        coveredOffset = (int) fromRow * stride + (int) fromColumn * 4;
        var bytes = coveredRows * coveredRowBytes;
        if (covered.length < bytes) {
            covered = new byte[bytes];
        }
        for (var row = 0; row < coveredRows; row++) {
            System.arraycopy(pixels, coveredOffset + row * stride, covered, row * coveredRowBytes, coveredRowBytes);
        }
        X11Cursor.compose(image, imageWidth, imageHeight, (int) left, (int) top, pixels, stride, layout, width, height);
    }

    /**
     * Restores what the last {@link #draw} covered.
     *
     * @param pixels the same pixels
     */
    void erase(byte[] pixels) {
        for (var row = 0; row < coveredRows; row++) {
            System.arraycopy(covered, row * coveredRowBytes, pixels, coveredOffset + row * coveredStride, coveredRowBytes);
        }
        coveredRows = 0;
    }
}
