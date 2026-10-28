package com.github.auties00.smalt.capture.internal.windows;

import java.lang.foreign.MemorySegment;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;

/**
 * Draws the mouse pointer reported by the desktop duplication over captured rows.
 *
 * <p>Desktop duplication delivers the desktop image without the pointer, and the pointer's shape
 * and position separately. The overlay keeps the latest of both and composes the shape onto each
 * BGRX row as the row is converted, in desktop coordinates, following the semantics of the three
 * {@code DXGI_OUTDUPL_POINTER_SHAPE_TYPE} values:
 * <ul>
 *   <li>color: 32-bit BGRA blended with straight alpha;</li>
 *   <li>masked color: 32-bit BGRX whose alpha byte is a mask, {@code 0} replacing the desktop
 *       pixel with the shape's color and non-zero XOR-ing the shape's color into it;</li>
 *   <li>monochrome: two 1-bit masks stacked vertically, the AND mask over the XOR mask, applied
 *       as {@code (desktop AND and) XOR xor} to the color channels.</li>
 * </ul>
 *
 * @implNote This implementation follows the pointer composition of Microsoft's Desktop
 *           Duplication sample ({@code DisplayManager::ProcessMonoMask}).
 */
final class PointerOverlay {
    /**
     * The shape bytes, copied from the duplication's buffer.
     */
    private byte[] shape = new byte[0];

    /**
     * The shape type, one of the {@code Dxgi.POINTER_*} constants, or {@code 0} without a shape.
     */
    private int type;

    /**
     * The width of the shape in pixels.
     */
    private int width;

    /**
     * The height of the drawn shape in pixels (half the reported height of a monochrome shape).
     */
    private int height;

    /**
     * The number of bytes per row of the shape.
     */
    private int pitch;

    /**
     * The left edge of the shape on the captured image.
     */
    private int x;

    /**
     * The top edge of the shape on the captured image.
     */
    private int y;

    /**
     * Whether the pointer is visible on the captured output.
     */
    private boolean visible;

    /**
     * Creates an overlay without a shape, which draws nothing.
     */
    PointerOverlay() {
    }

    /**
     * Records the pointer's position.
     *
     * @param x       the left edge of the shape on the captured image
     * @param y       the top edge of the shape on the captured image
     * @param visible whether the pointer is visible on the captured output
     */
    void position(int x, int y, boolean visible) {
        this.x = x;
        this.y = y;
        this.visible = visible;
    }

    /**
     * Records the pointer's shape.
     *
     * <p>A description that does not fit its buffer, or has an unknown type, removes the shape.
     *
     * @param buffer the shape's bytes
     * @param type   the {@code DXGI_OUTDUPL_POINTER_SHAPE_TYPE}
     * @param width  the width in pixels
     * @param height the reported height in pixels (both masks for a monochrome shape)
     * @param pitch  the number of bytes per row
     */
    void shape(MemorySegment buffer, int type, int width, int height, int pitch) {
        var rowBytes = type == Dxgi.POINTER_MONOCHROME ? (width + 7L) / 8 : 4L * width;
        var valid = (type == Dxgi.POINTER_MONOCHROME || type == Dxgi.POINTER_COLOR || type == Dxgi.POINTER_MASKED_COLOR)
                && width > 0 && height > 0 && width <= 1024 && height <= 2048 && pitch >= rowBytes
                && (long) pitch * height <= buffer.byteSize();
        if (!valid) {
            this.type = 0;
            return;
        }
        var length = pitch * height;
        if (shape.length < length) {
            shape = new byte[length];
        }
        MemorySegment.copy(buffer, JAVA_BYTE, 0, shape, 0, length);
        this.type = type;
        this.width = width;
        this.height = type == Dxgi.POINTER_MONOCHROME ? height / 2 : height;
        this.pitch = pitch;
    }

    /**
     * Composes the pointer onto one row of the captured image.
     *
     * @param row      the BGRX pixels holding the row
     * @param offset   the index of the row's first byte
     * @param rowY     the row's index in the captured image
     * @param originX  the column of the captured image that the row's first pixel holds
     * @param rowWidth the number of pixels of the row
     */
    void apply(byte[] row, int offset, int rowY, int originX, int rowWidth) {
        if (!visible || type == 0 || rowY < y || rowY >= y + height) {
            return;
        }
        var left = x - originX;
        var shapeY = rowY - y;
        var from = Math.max(0, -left);
        var to = Math.min(width, rowWidth - left);
        switch (type) {
            case Dxgi.POINTER_COLOR -> color(row, offset, left, shapeY, from, to);
            case Dxgi.POINTER_MASKED_COLOR -> maskedColor(row, offset, left, shapeY, from, to);
            default -> monochrome(row, offset, left, shapeY, from, to);
        }
    }

    /**
     * Blends a row of a color shape.
     *
     * @param row    the BGRX pixels holding the row
     * @param offset the index of the row's first byte
     * @param left   the column of the row where the shape starts
     * @param shapeY the shape row
     * @param from   the first shape column drawn
     * @param to     the shape column after the last one drawn
     */
    private void color(byte[] row, int offset, int left, int shapeY, int from, int to) {
        var base = shapeY * pitch;
        for (var shapeX = from; shapeX < to; shapeX++) {
            var source = base + 4 * shapeX;
            var alpha = shape[source + 3] & 0xFF;
            if (alpha == 0) {
                continue;
            }
            var target = offset + 4 * (left + shapeX);
            for (var channel = 0; channel < 3; channel++) {
                var s = shape[source + channel] & 0xFF;
                var d = row[target + channel] & 0xFF;
                row[target + channel] = (byte) ((s * alpha + d * (255 - alpha) + 127) / 255);
            }
        }
    }

    /**
     * Composes a row of a masked color shape.
     *
     * @param row    the BGRX pixels holding the row
     * @param offset the index of the row's first byte
     * @param left   the column of the row where the shape starts
     * @param shapeY the shape row
     * @param from   the first shape column drawn
     * @param to     the shape column after the last one drawn
     */
    private void maskedColor(byte[] row, int offset, int left, int shapeY, int from, int to) {
        var base = shapeY * pitch;
        for (var shapeX = from; shapeX < to; shapeX++) {
            var source = base + 4 * shapeX;
            var xor = shape[source + 3] != 0;
            var target = offset + 4 * (left + shapeX);
            for (var channel = 0; channel < 3; channel++) {
                row[target + channel] = xor ? (byte) (row[target + channel] ^ shape[source + channel]) : shape[source + channel];
            }
        }
    }

    /**
     * Composes a row of a monochrome shape.
     *
     * @param row    the BGRX pixels holding the row
     * @param offset the index of the row's first byte
     * @param left   the column of the row where the shape starts
     * @param shapeY the shape row
     * @param from   the first shape column drawn
     * @param to     the shape column after the last one drawn
     */
    private void monochrome(byte[] row, int offset, int left, int shapeY, int from, int to) {
        var andBase = shapeY * pitch;
        var xorBase = (shapeY + height) * pitch;
        for (var shapeX = from; shapeX < to; shapeX++) {
            var bit = 0x80 >> (shapeX & 7);
            var keep = (shape[andBase + (shapeX >> 3)] & bit) != 0;
            var invert = (shape[xorBase + (shapeX >> 3)] & bit) != 0;
            var target = offset + 4 * (left + shapeX);
            for (var channel = 0; channel < 3; channel++) {
                var value = keep ? row[target + channel] : 0;
                row[target + channel] = (byte) (invert ? value ^ 0xFF : value);
            }
        }
    }
}
