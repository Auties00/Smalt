package com.github.auties00.smalt.capture.internal.convert;

/**
 * The byte positions of the color samples within a 4-byte pixel.
 *
 * <p>The fourth byte (alpha or padding) is ignored by every conversion.
 *
 * @param rIndex the byte position of R, from 0 to 3
 * @param gIndex the byte position of G, from 0 to 3
 * @param bIndex the byte position of B, from 0 to 3
 */
public record RgbLayout(int rIndex, int gIndex, int bIndex) {
    /**
     * The layout {@code B G R X} of Windows and macOS frame buffers, and of a little-endian X11
     * image of a TrueColor visual.
     */
    public static final RgbLayout BGRX = new RgbLayout(2, 1, 0);

    /**
     * Derives the layout of a 32-bit pixel whose channels are selected by masks of its integer value.
     *
     * @param bitsPerPixel the bits per pixel
     * @param littleEndian whether the pixel value is stored least significant byte first
     * @param redMask the mask of R
     * @param greenMask the mask of G
     * @param blueMask the mask of B
     * @return the layout, or {@code null} unless the pixel has 32 bits and every mask selects one
     * distinct whole byte
     */
    public static RgbLayout fromMasks(int bitsPerPixel, boolean littleEndian, long redMask, long greenMask,
                                      long blueMask) {
        if (bitsPerPixel != 32) {
            return null;
        }
        var r = byteIndex(redMask, littleEndian);
        var g = byteIndex(greenMask, littleEndian);
        var b = byteIndex(blueMask, littleEndian);
        if (r < 0 || g < 0 || b < 0 || r == g || g == b || r == b) {
            return null;
        }
        return new RgbLayout(r, g, b);
    }

    /**
     * Returns the byte position a channel mask selects, or {@code -1} when the mask is not one whole
     * byte of a 32-bit value.
     */
    private static int byteIndex(long mask, boolean littleEndian) {
        if (mask <= 0 || mask > 0xFFFFFFFFL) {
            return -1;
        }
        var shift = Long.numberOfTrailingZeros(mask);
        if (shift % 8 != 0 || mask != 0xFFL << shift) {
            return -1;
        }
        var index = shift / 8;
        return littleEndian ? index : 3 - index;
    }
}
