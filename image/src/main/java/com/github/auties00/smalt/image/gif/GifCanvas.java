package com.github.auties00.smalt.image.gif;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.internal.io.ByteArrays;

import com.github.auties00.smalt.internal.limits.LimitChecks;
import java.util.Arrays;

/**
 * Composites GIF images onto an RGBA canvas the way browsers do.
 *
 * <p>The canvas starts fully transparent. Before an image is drawn, the disposal of the previous
 * image is applied to that image's rectangle: {@link GifFrame#DISPOSE_BACKGROUND} clears it to
 * transparent (browsers ignore the background color index), {@link GifFrame#DISPOSE_PREVIOUS}
 * restores the content it had before that image was drawn. Pixels whose index is the transparent
 * index, or lies past the color table, are not drawn, so the canvas shows through; pixels outside
 * the canvas are clipped; interlaced rows are placed in their final positions; and only the pixels
 * the LZW data actually produced are drawn, so a truncated or corrupt image draws its decoded part
 * and leaves the rest of its rectangle untouched.
 */
final class GifCanvas {
    /**
     * The canvas width.
     */
    private final int width;

    /**
     * The canvas height.
     */
    private final int height;

    /**
     * The canvas picture.
     */
    private final RgbaPicture picture;

    /**
     * The canvas samples.
     */
    private final byte[] pixels;

    /**
     * The limits checked before growing the index buffer.
     */
    private final MediaLimits limits;

    /**
     * The LZW decoder shared by every image.
     */
    private final GifLzwDecoder lzw;

    /**
     * The decoded color indices of the current image.
     */
    private byte[] indices;

    /**
     * The canvas rectangle saved for a {@link GifFrame#DISPOSE_PREVIOUS} disposal.
     */
    private byte[] saved;

    /**
     * Whether an image has been drawn, so that its disposal is pending.
     */
    private boolean pending;

    /**
     * The left edge of the pending disposal rectangle, clipped to the canvas.
     */
    private int disposeLeft;

    /**
     * The top edge of the pending disposal rectangle, clipped to the canvas.
     */
    private int disposeTop;

    /**
     * The width of the pending disposal rectangle, clipped to the canvas.
     */
    private int disposeWidth;

    /**
     * The height of the pending disposal rectangle, clipped to the canvas.
     */
    private int disposeHeight;

    /**
     * The disposal method of the last drawn image.
     */
    private int disposal;

    /**
     * Allocates a transparent canvas.
     *
     * @param width    the canvas width, positive
     * @param height   the canvas height, positive
     * @param hasAlpha the transparency flag of the canvas picture
     * @param limits   the limits checked before growing buffers
     */
    GifCanvas(int width, int height, boolean hasAlpha, MediaLimits limits) {
        this.width = width;
        this.height = height;
        this.limits = limits;
        this.picture = RgbaPicture.allocate(width, height, hasAlpha);
        this.pixels = picture.pixels();
        this.lzw = new GifLzwDecoder();
        this.indices = new byte[0];
    }

    /**
     * Returns the canvas picture.
     *
     * @return the picture, updated in place by {@link #draw(GifFrame)}
     */
    RgbaPicture picture() {
        return picture;
    }

    /**
     * Applies the pending disposal, then decodes an image and draws it.
     *
     * @param frame the image
     * @return {@code true} when every pixel of the image was decoded
     * @throws com.github.auties00.smalt.MediaException.LimitExceeded if the image's index
     *                                                                       buffer exceeds the
     *                                                                       limits
     */
    boolean draw(GifFrame frame) {
        var total = begin(frame);
        if (total == 0) {
            return true;
        }
        if (indices.length < total) {
            LimitChecks.checkAllocation(limits, total);
            indices = new byte[(int) total];
        }
        var decoded = lzw.decode(frame.data, frame.dataLength, frame.minimumCodeSize, indices, (int) total);
        draw(frame, indices, decoded);
        return decoded == total;
    }

    /**
     * Applies the pending disposal and records the disposal of an image about to be drawn,
     * saving the rectangle it covers when its disposal restores the previous content.
     *
     * @param frame the image
     * @return the number of pixels of the image, whose indices the LZW data should produce
     */
    long begin(GifFrame frame) {
        if (pending) {
            dispose();
        }
        var left = Math.min(frame.left, width);
        var top = Math.min(frame.top, height);
        disposeLeft = left;
        disposeTop = top;
        disposeWidth = Math.min(frame.width, width - left);
        disposeHeight = Math.min(frame.height, height - top);
        disposal = frame.disposal;
        pending = true;
        if (disposal == GifFrame.DISPOSE_PREVIOUS) {
            save();
        }
        return (long) frame.width * frame.height;
    }

    /**
     * Draws the decoded pixels of an image whose disposal {@link #begin(GifFrame)} has recorded.
     *
     * @param frame   the image
     * @param indices the color indices of the image, row by row in stored order
     * @param decoded the number of indices the LZW data produced
     */
    void draw(GifFrame frame, byte[] indices, int decoded) {
        var visibleWidth = Math.min(frame.width, width - Math.min(frame.left, width));
        var visibleHeight = Math.min(frame.height, height - Math.min(frame.top, height));
        if (visibleWidth > 0 && visibleHeight > 0) {
            composite(frame, indices, decoded, visibleWidth);
        }
    }

    /**
     * Draws the decoded indices of an image onto the canvas.
     *
     * @param frame        the image
     * @param indices      the color indices of the image
     * @param decoded      the number of indices decoded
     * @param visibleWidth the number of columns of the image inside the canvas
     */
    private void composite(GifFrame frame, byte[] indices, int decoded, int visibleWidth) {
        var colors = frame.colors;
        var colorCount = frame.colorCount;
        var transparent = frame.transparentIndex;
        var frameWidth = frame.width;
        var frameHeight = frame.height;
        var rows = (decoded + frameWidth - 1) / frameWidth;
        for (var row = 0; row < rows; row++) {
            var y = frame.interlaced ? interlacedRow(row, frameHeight) : row;
            var canvasY = frame.top + y;
            if (canvasY >= height) {
                continue;
            }
            var source = row * frameWidth;
            var columns = Math.min(visibleWidth, decoded - source);
            var target = (canvasY * width + frame.left) * 4;
            if (transparent < 0 && colorCount == 256) {
                for (var x = 0; x < columns; x++) {
                    ByteArrays.put32le(pixels, target + x * 4, colors[indices[source + x] & 0xFF]);
                }
            } else {
                for (var x = 0; x < columns; x++) {
                    var index = indices[source + x] & 0xFF;
                    if (index != transparent && index < colorCount) {
                        ByteArrays.put32le(pixels, target + x * 4, colors[index]);
                    }
                }
            }
        }
    }

    /**
     * Returns the image row of the {@code row}-th stored row of an interlaced image (GIF89a
     * appendix E: every 8th row from 0, every 8th from 4, every 4th from 2, every 2nd from 1).
     *
     * @param row    the stored row index
     * @param height the image height
     * @return the row in the image
     */
    static int interlacedRow(int row, int height) {
        var pass1 = (height + 7) >>> 3;
        if (row < pass1) {
            return row << 3;
        }
        row -= pass1;
        var pass2 = (height + 3) >>> 3;
        if (row < pass2) {
            return 4 + (row << 3);
        }
        row -= pass2;
        var pass3 = (height + 1) >>> 2;
        if (row < pass3) {
            return 2 + (row << 2);
        }
        return 1 + ((row - pass3) << 1);
    }

    /**
     * Applies the disposal of the last drawn image.
     */
    private void dispose() {
        var rowBytes = disposeWidth * 4;
        if (rowBytes <= 0 || disposeHeight <= 0) {
            return;
        }
        switch (disposal) {
            case GifFrame.DISPOSE_BACKGROUND -> {
                for (var row = 0; row < disposeHeight; row++) {
                    var start = ((disposeTop + row) * width + disposeLeft) * 4;
                    Arrays.fill(pixels, start, start + rowBytes, (byte) 0);
                }
            }
            case GifFrame.DISPOSE_PREVIOUS -> {
                for (var row = 0; row < disposeHeight; row++) {
                    System.arraycopy(saved, row * rowBytes, pixels, ((disposeTop + row) * width + disposeLeft) * 4, rowBytes);
                }
            }
            default -> {
            }
        }
    }

    /**
     * Saves the canvas rectangle of the image about to be drawn.
     */
    private void save() {
        var rowBytes = disposeWidth * 4;
        if (rowBytes <= 0 || disposeHeight <= 0) {
            return;
        }
        if (saved == null || saved.length < rowBytes * disposeHeight) {
            saved = new byte[rowBytes * disposeHeight];
        }
        for (var row = 0; row < disposeHeight; row++) {
            System.arraycopy(pixels, ((disposeTop + row) * width + disposeLeft) * 4, saved, row * rowBytes, rowBytes);
        }
    }
}
