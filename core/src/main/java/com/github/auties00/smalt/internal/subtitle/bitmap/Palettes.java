package com.github.auties00.smalt.internal.subtitle.bitmap;

import com.github.auties00.smalt.image.RgbaPicture;

/**
 * Converts the palette entries of bitmap subtitles to RGBA and paints indexed pictures with them.
 *
 * <p>A color is packed as {@code 0xRRGGBBAA} in an {@code int}, alpha straight.
 */
public final class Palettes {
    /**
     * The fully transparent color.
     */
    public static final int TRANSPARENT = 0;

    private Palettes() {
        throw new AssertionError();
    }

    /**
     * Converts a limited-range Y'CbCr color to RGBA.
     *
     * @param y the luma, nominally 16 to 235
     * @param cb the blue difference, nominally 16 to 240
     * @param cr the red difference, nominally 16 to 240
     * @param alpha the opacity, 0 (transparent) to 255
     * @param bt709 whether the color uses the ITU-R BT.709 matrix rather than the BT.601 one
     * @return the color as {@code 0xRRGGBBAA}
     */
    public static int ycbcr(int y, int cb, int cr, int alpha, boolean bt709) {
        var luma = (y - 16) * 76309;
        var u = cb - 128;
        var v = cr - 128;
        int r;
        int g;
        int b;
        if (bt709) {
            r = luma + 117489 * v;
            g = luma - 13975 * u - 34925 * v;
            b = luma + 138438 * u;
        } else {
            r = luma + 104597 * v;
            g = luma - 25675 * u - 53279 * v;
            b = luma + 132201 * u;
        }
        return rgba(clamp(r), clamp(g), clamp(b), alpha);
    }

    /**
     * Packs an RGBA color.
     *
     * @param r the red sample, 0 to 255
     * @param g the green sample, 0 to 255
     * @param b the blue sample, 0 to 255
     * @param alpha the opacity, 0 to 255
     * @return the color as {@code 0xRRGGBBAA}
     */
    public static int rgba(int r, int g, int b, int alpha) {
        return r << 24 | g << 16 | b << 8 | alpha;
    }

    /**
     * Rounds a fixed-point sample with 16 fractional bits into 0 to 255.
     *
     * @param value the sample times 65536
     * @return the 8-bit sample
     */
    private static int clamp(int value) {
        var rounded = (value + 32768) >> 16;
        return rounded < 0 ? 0 : Math.min(rounded, 255);
    }

    /**
     * Paints an indexed picture.
     *
     * @param indices the palette index of every pixel, row by row without padding
     * @param width the width in pixels, positive
     * @param height the height in pixels, positive
     * @param palette the colors as {@code 0xRRGGBBAA}, indexed by the pixel values
     * @return the picture, with an alpha channel
     */
    public static RgbaPicture paint(byte[] indices, int width, int height, int[] palette) {
        var picture = RgbaPicture.allocate(width, height, true);
        var pixels = picture.pixels();
        var count = width * height;
        var mask = palette.length - 1;
        for (int index = 0, at = 0; index < count; index++, at += 4) {
            var color = palette[indices[index] & mask];
            pixels[at] = (byte) (color >>> 24);
            pixels[at + 1] = (byte) (color >>> 16);
            pixels[at + 2] = (byte) (color >>> 8);
            pixels[at + 3] = (byte) color;
        }
        return picture;
    }

    /**
     * Returns whether an indexed picture shows any pixel.
     *
     * @param indices the palette index of every pixel
     * @param count the number of pixels
     * @param palette the colors, indexed by the pixel values, a power of two long
     * @return {@code true} when a pixel's color is not fully transparent
     */
    public static boolean visible(byte[] indices, int count, int[] palette) {
        var mask = palette.length - 1;
        for (var index = 0; index < count; index++) {
            if ((palette[indices[index] & mask] & 0xFF) != 0) {
                return true;
            }
        }
        return false;
    }
}
