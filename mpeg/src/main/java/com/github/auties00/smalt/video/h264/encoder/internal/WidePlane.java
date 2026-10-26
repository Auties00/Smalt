package com.github.auties00.smalt.video.h264.encoder.internal;

import java.util.Arrays;

/**
 * A view of one plane of 16-bit samples of the extended engine: the samples of a frame, or of
 * one field of a frame, with padding around them.
 *
 * <p>Sample {@code (x, y)} lives at {@code origin + y * stride + x}. A frame plane owns its
 * array; a field view of a frame plane shares it, starting at the field's first row and stepping
 * over the rows of the other field, and has no padding of its own.
 */
final class WidePlane {
    /**
     * The samples.
     */
    final short[] data;

    /**
     * The index of sample {@code (0, 0)}.
     */
    final int origin;

    /**
     * The distance between two rows of the view.
     */
    final int stride;

    /**
     * The width of the view in samples.
     */
    final int width;

    /**
     * The height of the view in samples.
     */
    final int height;

    /**
     * The padding on the left and right, in samples, or 0 for a field view.
     */
    final int padX;

    /**
     * The padding above and below, in rows, or 0 for a field view.
     */
    final int padY;

    /**
     * Allocates a padded plane.
     *
     * @param width the width in samples
     * @param height the height in samples
     * @param padX the padding on the left and right
     * @param padY the padding above and below
     */
    WidePlane(int width, int height, int padX, int padY) {
        this.width = width;
        this.height = height;
        this.padX = padX;
        this.padY = padY;
        this.stride = width + 2 * padX;
        this.origin = padY * stride + padX;
        this.data = new short[stride * (height + 2 * padY)];
    }

    /**
     * Creates a view of the rows of one field of a frame plane.
     *
     * @param frame the frame plane
     * @param bottom whether the view holds the bottom field, the odd rows
     */
    WidePlane(WidePlane frame, boolean bottom) {
        this.data = frame.data;
        this.origin = frame.origin + (bottom ? frame.stride : 0);
        this.stride = 2 * frame.stride;
        this.width = frame.width;
        this.height = frame.height / 2;
        this.padX = 0;
        this.padY = 0;
    }

    /**
     * Returns the sample at a position of the view.
     *
     * @param x the column
     * @param y the row
     * @return the sample
     */
    int get(int x, int y) {
        return data[origin + y * stride + x];
    }

    /**
     * Replicates the edge samples of the plane into its padding, so that every read of the padded
     * area returns the sample a decoder's clamped read returns (ITU-T H.264 8.4.2.2).
     */
    void pad() {
        for (var y = 0; y < height; y++) {
            var row = origin + y * stride;
            Arrays.fill(data, row - padX, row, data[row]);
            Arrays.fill(data, row + width, row + width + padX, data[row + width - 1]);
        }
        var first = origin - padX;
        for (var y = 1; y <= padY; y++) {
            System.arraycopy(data, first, data, first - y * stride, stride);
        }
        var last = origin + (height - 1) * stride - padX;
        for (var y = 1; y <= padY; y++) {
            System.arraycopy(data, last, data, last + y * stride, stride);
        }
    }

    /**
     * Copies the samples of a field view of another plane into this plane, row for row.
     *
     * @param field the field view, of this plane's size
     */
    void copyFrom(WidePlane field) {
        for (var y = 0; y < height; y++) {
            System.arraycopy(field.data, field.origin + y * field.stride, data, origin + y * stride, width);
        }
    }
}
