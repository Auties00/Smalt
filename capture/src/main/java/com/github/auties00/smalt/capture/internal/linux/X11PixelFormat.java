package com.github.auties00.smalt.capture.internal.linux;

import com.github.auties00.smalt.capture.internal.convert.RgbLayout;

/**
 * Describes the pixels of an X11 {@code ZPixmap} image of a TrueColor or DirectColor visual and
 * expands those that are not 4-byte pixels of whole-byte channels.
 *
 * <p>A pixel is a {@code bits_per_pixel} value of 16, 24 or 32 bits stored in the image's byte order;
 * each channel is the contiguous run of bits its mask selects. Images of 32 bits per pixel whose
 * masks each select one whole byte are converted in place ({@link #direct()}); every other image,
 * the 16-bit visuals of depth 15 and 16, the packed 24-bit pixmap format and the 10-bit channels of
 * depth 30 included, is expanded to {@link RgbLayout#BGRX} pixels first. A channel of fewer than
 * 8 bits is scaled to 8 bits by rounding {@code value * 255 / max} to nearest, a wider one keeps
 * its 8 most significant bits.
 */
final class X11PixelFormat {
    /**
     * The {@code bits_per_pixel} of the images.
     */
    private final int bitsPerPixel;

    /**
     * The {@code byte_order} of the images.
     */
    private final int byteOrder;

    /**
     * The R, G and B masks of the images.
     */
    private final long[] masks;

    /**
     * The bytes per pixel.
     */
    private final int bytesPerPixel;

    /**
     * Whether the pixel value is stored least significant byte first.
     */
    private final boolean lsbFirst;

    /**
     * The layout of a pixel converted in place, or {@code null} when pixels are expanded.
     */
    private final RgbLayout direct;

    /**
     * The shifts of R, G and B in the pixel value.
     */
    private final int[] shift = new int[3];

    /**
     * The maxima of R, G and B, the masks shifted down.
     */
    private final int[] max = new int[3];

    /**
     * The extra right shift of a channel wider than 8 bits, else {@code 0}.
     */
    private final int[] narrow = new int[3];

    /**
     * The 8-bit value of every value of a channel of at most 8 bits, else {@code null}.
     */
    private final byte[][] scale = new byte[3][];

    /**
     * Describes a validated format.
     */
    private X11PixelFormat(int bitsPerPixel, int byteOrder, long[] masks, RgbLayout direct) {
        this.bitsPerPixel = bitsPerPixel;
        this.byteOrder = byteOrder;
        this.masks = masks;
        this.bytesPerPixel = bitsPerPixel / 8;
        this.lsbFirst = byteOrder == X11ScreenGrabber.LSB_FIRST;
        this.direct = direct;
        for (var channel = 0; channel < 3; channel++) {
            var mask = masks[channel];
            shift[channel] = Long.numberOfTrailingZeros(mask);
            var bits = Long.bitCount(mask);
            max[channel] = (1 << bits) - 1;
            if (bits > 8) {
                narrow[channel] = bits - 8;
            } else {
                var map = new byte[max[channel] + 1];
                for (var value = 0; value <= max[channel]; value++) {
                    map[value] = (byte) ((value * 255 + max[channel] / 2) / max[channel]);
                }
                scale[channel] = map;
            }
        }
    }

    /**
     * Returns the format of an image.
     *
     * @param bitsPerPixel the image's {@code bits_per_pixel}
     * @param byteOrder the image's {@code byte_order}, {@link X11ScreenGrabber#LSB_FIRST} or
     * {@link X11ScreenGrabber#MSB_FIRST}
     * @param redMask the image's {@code red_mask}
     * @param greenMask the image's {@code green_mask}
     * @param blueMask the image's {@code blue_mask}
     * @return the format, or {@code null} unless the image has 16, 24 or 32 bits per pixel, a known
     * byte order and three non-empty, contiguous, disjoint masks of at most 16 bits within the pixel
     */
    static X11PixelFormat of(int bitsPerPixel, int byteOrder, long redMask, long greenMask, long blueMask) {
        if (bitsPerPixel != 16 && bitsPerPixel != 24 && bitsPerPixel != 32) {
            return null;
        }
        if (byteOrder != X11ScreenGrabber.LSB_FIRST && byteOrder != X11ScreenGrabber.MSB_FIRST) {
            return null;
        }
        var masks = new long[]{redMask, greenMask, blueMask};
        var limit = (1L << bitsPerPixel) - 1;
        for (var mask : masks) {
            if (mask <= 0 || (mask & ~limit) != 0 || Long.bitCount(mask) > 16) {
                return null;
            }
            var low = Long.numberOfTrailingZeros(mask);
            if (mask != ((1L << Long.bitCount(mask)) - 1) << low) {
                return null;
            }
        }
        if ((redMask & greenMask) != 0 || (greenMask & blueMask) != 0 || (redMask & blueMask) != 0) {
            return null;
        }
        var direct = X11ScreenGrabber.layoutOf(bitsPerPixel, byteOrder, redMask, greenMask, blueMask);
        return new X11PixelFormat(bitsPerPixel, byteOrder, masks, direct);
    }

    /**
     * Returns whether this is the format of images with the given description, so a grabber can
     * keep it across grabs.
     *
     * @param bitsPerPixel the image's {@code bits_per_pixel}
     * @param byteOrder the image's {@code byte_order}
     * @param redMask the image's {@code red_mask}
     * @param greenMask the image's {@code green_mask}
     * @param blueMask the image's {@code blue_mask}
     * @return whether {@link #of} would describe the same format
     */
    boolean describes(int bitsPerPixel, int byteOrder, long redMask, long greenMask, long blueMask) {
        return this.bitsPerPixel == bitsPerPixel && this.byteOrder == byteOrder && masks[0] == redMask
                && masks[1] == greenMask && masks[2] == blueMask;
    }

    /**
     * Returns the layout of pixels converted in place.
     *
     * @return the layout, or {@code null} when the pixels must be expanded with {@link #expand}
     */
    RgbLayout direct() {
        return direct;
    }

    /**
     * Returns the bytes per pixel.
     *
     * @return 2, 3 or 4
     */
    int bytesPerPixel() {
        return bytesPerPixel;
    }

    /**
     * Expands rows of pixels into rows of {@link RgbLayout#BGRX} pixels.
     *
     * @param source the pixels
     * @param sourceStride the distance between source rows in bytes
     * @param width the pixels per row
     * @param height the rows
     * @param target the expanded pixels, rows of {@code 4 * width} bytes from index {@code 0}
     */
    void expand(byte[] source, int sourceStride, int width, int height, byte[] target) {
        for (var row = 0; row < height; row++) {
            var at = row * sourceStride;
            var out = row * 4 * width;
            for (var x = 0; x < width; x++) {
                var value = pixel(source, at + bytesPerPixel * x);
                target[out + 4 * x] = channel(2, value);
                target[out + 4 * x + 1] = channel(1, value);
                target[out + 4 * x + 2] = channel(0, value);
                target[out + 4 * x + 3] = (byte) 0xFF;
            }
        }
    }

    /**
     * Reads the value of one pixel.
     *
     * @param source the pixels
     * @param at the index of the pixel's first byte
     * @return the unsigned pixel value
     */
    private long pixel(byte[] source, int at) {
        var value = 0L;
        for (var index = 0; index < bytesPerPixel; index++) {
            var sample = source[at + index] & 0xFFL;
            value |= lsbFirst ? sample << (8 * index) : sample << (8 * (bytesPerPixel - 1 - index));
        }
        return value;
    }

    /**
     * Extracts a channel of a pixel value and scales it to 8 bits.
     *
     * @param channel 0 for R, 1 for G, 2 for B
     * @param value the pixel value
     * @return the 8-bit sample
     */
    private byte channel(int channel, long value) {
        var sample = (int) (value >>> shift[channel]) & max[channel];
        var map = scale[channel];
        return map != null ? map[sample] : (byte) (sample >>> narrow[channel]);
    }
}
