package com.github.auties00.smalt.image.jpeg.internal;

/**
 * Describes one component of a JPEG frame and its block layout.
 *
 * <p>The identity fields come from the SOF segment. The layout fields are derived by
 * {@link JpegFrame#computeLayout()} once the frame height is known: the component's own size in
 * samples, the extent in blocks a non-interleaved scan covers, and the extent in blocks of the
 * MCU-padded grid an interleaved scan covers.
 */
public final class JpegComponent {
    /**
     * The component identifier used by scans to refer to the component.
     */
    final int id;

    /**
     * The horizontal sampling factor, from 1 to 4.
     */
    final int h;

    /**
     * The vertical sampling factor, from 1 to 4.
     */
    final int v;

    /**
     * The quantization table selector, from 0 to 3.
     */
    final int quantSelector;

    /**
     * The position of the component in the frame header.
     */
    final int index;

    /**
     * The component width in samples, {@code ceil(frameWidth * h / hMax)}.
     */
    int width;

    /**
     * The component height in samples, {@code ceil(frameHeight * v / vMax)}.
     */
    int height;

    /**
     * The number of block columns a non-interleaved scan covers, {@code ceil(width / 8)}.
     */
    int blocksWide;

    /**
     * The number of block rows a non-interleaved scan covers, {@code ceil(height / 8)}.
     */
    int blocksHigh;

    /**
     * The number of block columns of the MCU-padded grid.
     */
    int paddedBlocksWide;

    /**
     * The number of block rows of the MCU-padded grid.
     */
    int paddedBlocksHigh;

    /**
     * Constructs a component from its SOF fields.
     *
     * @param id            the component identifier
     * @param h             the horizontal sampling factor
     * @param v             the vertical sampling factor
     * @param quantSelector the quantization table selector
     * @param index         the position in the frame header
     */
    JpegComponent(int id, int h, int v, int quantSelector, int index) {
        this.id = id;
        this.h = h;
        this.v = v;
        this.quantSelector = quantSelector;
        this.index = index;
    }

    /**
     * Returns the component identifier.
     *
     * @return the identifier, from 0 to 255
     */
    public int id() {
        return id;
    }

    /**
     * Returns the horizontal sampling factor.
     *
     * @return the factor, from 1 to 4
     */
    public int horizontalSampling() {
        return h;
    }

    /**
     * Returns the vertical sampling factor.
     *
     * @return the factor, from 1 to 4
     */
    public int verticalSampling() {
        return v;
    }

    /**
     * Returns the quantization table selector.
     *
     * @return the selector, from 0 to 3
     */
    public int quantSelector() {
        return quantSelector;
    }

    /**
     * Returns the component width in samples.
     *
     * @return the width
     */
    public int width() {
        return width;
    }

    /**
     * Returns the component height in samples.
     *
     * @return the height
     */
    public int height() {
        return height;
    }

    /**
     * Returns the number of block columns of the MCU-padded grid, which is the row length of the
     * component's coefficient buffer.
     *
     * @return the padded block columns
     */
    public int paddedBlocksWide() {
        return paddedBlocksWide;
    }

    /**
     * Returns the number of block rows of the MCU-padded grid.
     *
     * @return the padded block rows
     */
    public int paddedBlocksHigh() {
        return paddedBlocksHigh;
    }

    /**
     * Returns the number of block columns covered by a non-interleaved scan.
     *
     * @return the block columns
     */
    public int blocksWide() {
        return blocksWide;
    }

    /**
     * Returns the number of block rows covered by a non-interleaved scan.
     *
     * @return the block rows
     */
    public int blocksHigh() {
        return blocksHigh;
    }
}
