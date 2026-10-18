package com.github.auties00.smalt.image.ops;

/**
 * Implements {@link PixelKernel} with plain Java arithmetic.
 *
 * <p>This is the reference implementation; the vector implementation delegates to the range
 * methods of this class for the pixels its vector loops do not cover.
 */
final class ScalarPixelKernel implements PixelKernel {
    /**
     * Constructs the kernel.
     */
    ScalarPixelKernel() {
    }

    /**
     * Returns {@code round(t / 255)} for {@code t} in {@code 0..65025}.
     *
     * @param t the dividend
     * @return the rounded quotient
     */
    static int divide255(int t) {
        var biased = t + 128;
        return (biased + (biased >> 8)) >> 8;
    }

    @Override
    public boolean isOpaqueRgba(byte[] pixels, int offset, int width) {
        return isOpaqueRgbaRange(pixels, offset, 0, width);
    }

    /**
     * Returns whether the alpha bytes of the pixels {@code [from, to)} of one row are all
     * {@code 255}.
     *
     * @param pixels the RGBA samples
     * @param offset the index of the row's first byte
     * @param from   the first pixel
     * @param to     the pixel after the last one
     * @return whether those pixels are opaque
     */
    boolean isOpaqueRgbaRange(byte[] pixels, int offset, int from, int to) {
        var all = -1;
        for (var x = from; x < to; x++) {
            all &= pixels[offset + 4 * x + 3];
        }
        return (byte) all == -1;
    }

    @Override
    public boolean isOpaquePlane(byte[] plane, int offset, int width) {
        return isOpaquePlaneRange(plane, offset, 0, width);
    }

    /**
     * Returns whether the samples {@code [from, to)} of one row are all {@code 255}.
     *
     * @param plane  the samples
     * @param offset the index of the row's first sample
     * @param from   the first sample
     * @param to     the sample after the last one
     * @return whether those samples are opaque
     */
    boolean isOpaquePlaneRange(byte[] plane, int offset, int from, int to) {
        var all = -1;
        for (var x = from; x < to; x++) {
            all &= plane[offset + x];
        }
        return (byte) all == -1;
    }

    @Override
    public void flattenRgba(byte[] src, int sOff, int width, byte[] dst, int dOff, int rgb) {
        flattenRgbaRange(src, sOff, dst, dOff, rgb, 0, width);
    }

    /**
     * Composites the pixels {@code [from, to)} of one row over an opaque color.
     *
     * @param src  the RGBA source
     * @param sOff the index of the row's first source byte
     * @param dst  the RGBA destination
     * @param dOff the index of the row's first destination byte
     * @param rgb  the background as {@code 0xRRGGBB}
     * @param from the first pixel
     * @param to   the pixel after the last one
     */
    void flattenRgbaRange(byte[] src, int sOff, byte[] dst, int dOff, int rgb, int from, int to) {
        var red = (rgb >>> 16) & 0xFF;
        var green = (rgb >>> 8) & 0xFF;
        var blue = rgb & 0xFF;
        for (var x = from; x < to; x++) {
            var index = sOff + 4 * x;
            var a = src[index + 3] & 0xFF;
            var inverse = 255 - a;
            var out = dOff + 4 * x;
            dst[out] = (byte) divide255((src[index] & 0xFF) * a + red * inverse);
            dst[out + 1] = (byte) divide255((src[index + 1] & 0xFF) * a + green * inverse);
            dst[out + 2] = (byte) divide255((src[index + 2] & 0xFF) * a + blue * inverse);
            dst[out + 3] = (byte) 255;
        }
    }

    @Override
    public void flattenPlane(byte[] src, int sOff, byte[] alpha, int aOff, int width, int background, byte[] dst, int dOff) {
        flattenPlaneRange(src, sOff, alpha, aOff, background, dst, dOff, 0, width);
    }

    /**
     * Composites the samples {@code [from, to)} of one row over a constant sample value.
     *
     * @param src        the samples
     * @param sOff       the index of the row's first sample
     * @param alpha      the alpha samples
     * @param aOff       the index of the row's first alpha sample
     * @param background the background sample value
     * @param dst        the destination samples
     * @param dOff       the index of the row's first destination sample
     * @param from       the first sample
     * @param to         the sample after the last one
     */
    void flattenPlaneRange(byte[] src, int sOff, byte[] alpha, int aOff, int background, byte[] dst, int dOff, int from, int to) {
        for (var x = from; x < to; x++) {
            var a = alpha[aOff + x] & 0xFF;
            dst[dOff + x] = (byte) divide255((src[sOff + x] & 0xFF) * a + background * (255 - a));
        }
    }
}
