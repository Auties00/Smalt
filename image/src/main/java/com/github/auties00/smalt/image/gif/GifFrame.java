package com.github.auties00.smalt.image.gif;

/**
 * Holds one image of a GIF datastream as read by {@link GifReader}: its descriptor, the graphic
 * control that applies to it, its color table, and its LZW-compressed data.
 *
 * <p>One instance is reused for every frame of a datastream; {@link GifReader#nextFrame} resets
 * every field.
 */
final class GifFrame {
    /**
     * The disposal method that leaves the canvas as it is (GIF89a methods 0 and 1).
     */
    static final int DISPOSE_NONE = 0;

    /**
     * The disposal method that clears the frame rectangle to transparent (GIF89a method 2).
     */
    static final int DISPOSE_BACKGROUND = 2;

    /**
     * The disposal method that restores the frame rectangle to its previous content (GIF89a
     * method 3, and method 4 as browsers read it).
     */
    static final int DISPOSE_PREVIOUS = 3;

    /**
     * The column of the frame's left edge on the logical screen.
     */
    int left;

    /**
     * The row of the frame's top edge on the logical screen.
     */
    int top;

    /**
     * The frame width in pixels, possibly zero.
     */
    int width;

    /**
     * The frame height in pixels, possibly zero.
     */
    int height;

    /**
     * Whether the frame's rows are stored in the four-pass interlaced order.
     */
    boolean interlaced;

    /**
     * The frame's color table as packed little-endian RGBA pixels (alpha 255), 256 entries.
     */
    final int[] colors;

    /**
     * The number of valid entries of {@link #colors}; {@code 0} when neither a local nor a global
     * table exists.
     */
    int colorCount;

    /**
     * The transparent color index, or {@code -1} when the graphic control declares none.
     */
    int transparentIndex;

    /**
     * The effective disposal method: {@link #DISPOSE_NONE}, {@link #DISPOSE_BACKGROUND} or
     * {@link #DISPOSE_PREVIOUS}.
     */
    int disposal;

    /**
     * The display time in hundredths of a second, as stored.
     */
    int delay;

    /**
     * The LZW minimum code size byte.
     */
    int minimumCodeSize;

    /**
     * The concatenated data sub-blocks.
     */
    byte[] data;

    /**
     * The number of valid bytes in {@link #data}.
     */
    int dataLength;

    /**
     * Whether the input ended inside the frame's data sub-blocks.
     */
    boolean truncated;

    /**
     * Constructs an empty frame.
     */
    GifFrame() {
        this.colors = new int[256];
        this.data = new byte[4096];
    }

    /**
     * Returns whether the frame rectangle covers the whole canvas.
     *
     * @param canvasWidth  the canvas width
     * @param canvasHeight the canvas height
     * @return {@code true} when every canvas pixel lies inside the frame
     */
    boolean covers(int canvasWidth, int canvasHeight) {
        return left == 0 && top == 0 && width >= canvasWidth && height >= canvasHeight;
    }
}
