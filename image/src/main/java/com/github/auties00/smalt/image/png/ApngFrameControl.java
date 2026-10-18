package com.github.auties00.smalt.image.png;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.internal.io.ByteArrays;

/**
 * Holds the fields of an APNG {@code fcTL} chunk (PNG 3rd edition 11.3.6.2).
 *
 * @param sequence the chunk's sequence number
 * @param width the frame width in pixels
 * @param height the frame height in pixels
 * @param x the horizontal offset of the frame on the canvas
 * @param y the vertical offset of the frame on the canvas
 * @param delayNumerator the numerator of the display time in seconds
 * @param delayDenominator the denominator of the display time, {@code 0} meaning 100
 * @param dispose the disposal operation: {@link #DISPOSE_NONE},
 * {@link #DISPOSE_BACKGROUND} or {@link #DISPOSE_PREVIOUS}
 * @param blend the blend operation: {@link #BLEND_SOURCE} or {@link #BLEND_OVER}
 */
record ApngFrameControl(long sequence, long width, long height, long x, long y, int delayNumerator,
                        int delayDenominator, int dispose, int blend) {
    /**
     * The data length of an {@code fcTL} chunk.
     */
    static final int LENGTH = 26;

    /**
     * Leaves the canvas as it is after the frame.
     */
    static final int DISPOSE_NONE = 0;

    /**
     * Clears the frame region to transparent black after the frame.
     */
    static final int DISPOSE_BACKGROUND = 1;

    /**
     * Restores the frame region to its content before the frame.
     */
    static final int DISPOSE_PREVIOUS = 2;

    /**
     * Replaces the frame region, alpha included.
     */
    static final int BLEND_SOURCE = 0;

    /**
     * Composites the frame over the region.
     */
    static final int BLEND_OVER = 1;

    /**
     * Parses the data of an {@code fcTL} chunk.
     *
     * @param data the chunk data, {@link #LENGTH} bytes
     * @return the frame control, not yet validated against the canvas
     */
    static ApngFrameControl parse(byte[] data) {
        return new ApngFrameControl(
                ByteArrays.u32be(data, 0),
                ByteArrays.u32be(data, 4),
                ByteArrays.u32be(data, 8),
                ByteArrays.u32be(data, 12),
                ByteArrays.u32be(data, 16),
                ByteArrays.u16be(data, 20),
                ByteArrays.u16be(data, 22),
                data[24] & 0xFF,
                data[25] & 0xFF);
    }

    /**
     * Checks that the frame lies inside the canvas and uses defined operations.
     *
     * @param canvasWidth the {@code IHDR} width
     * @param canvasHeight the {@code IHDR} height
     * @throws MediaException.Malformed if the frame is empty, extends past the canvas, or uses an
     * undefined disposal or blend operation
     */
    void validate(int canvasWidth, int canvasHeight) {
        if (width == 0 || height == 0 || x + width > canvasWidth || y + height > canvasHeight) {
            throw new MediaException.Malformed("APNG frame " + width + "x" + height + " at " + x + "," + y
                    + " does not fit the " + canvasWidth + "x" + canvasHeight + " canvas");
        }
        if (dispose > DISPOSE_PREVIOUS) {
            throw new MediaException.Malformed("invalid APNG dispose operation " + dispose);
        }
        if (blend > BLEND_OVER) {
            throw new MediaException.Malformed("invalid APNG blend operation " + blend);
        }
    }

    /**
     * Returns the display time in milliseconds.
     *
     * <p>A zero denominator counts as 100 (PNG 3rd edition 11.3.6.2). The fraction is converted
     * as {@code (numerator * 1000 + denominator / 2) / denominator}, rounding to the nearest
     * millisecond with halves rounded up.
     *
     * @return the delay, from 0 to 65535000
     */
    int durationMillis() {
        var denominator = delayDenominator == 0 ? 100 : delayDenominator;
        return (delayNumerator * 1000 + denominator / 2) / denominator;
    }
}
