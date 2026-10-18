package com.github.auties00.smalt.image.webp.internal.color;

/**
 * Implements the color conversion kernels with plain Java arithmetic; the reference for every
 * other implementation.
 *
 * @implNote This implementation follows libwebp's {@code src/dsp/yuv.h} (BSD-3):
 * {@code VP8YUVToR/G/B} with 14-bit coefficients ({@code MultHi(v, c) = (v * c) >> 8}) and a
 * 6-bit final shift with clipping, and {@code VP8RGBToY} with 16-bit coefficients and a
 * {@code 1 << 15} rounding term.
 */
public final class ScalarYuvDsp implements YuvDsp {
    /**
     * The mask of the values {@link #clip8(int)} passes through unclipped.
     */
    private static final int YUV_MASK2 = (256 << 6) - 1;

    /**
     * Constructs the kernel.
     */
    public ScalarYuvDsp() {
    }

    @Override
    public void yuvToRgbaRow(byte[] y, int yPos, int[] u, int[] v, byte[] dst, int dstPos, int count) {
        yuvToRgbaRowFrom(y, yPos, u, v, dst, dstPos, 0, count);
    }

    /**
     * Converts the pixels {@code [from, count)} of one row, as {@link #yuvToRgbaRow} does for
     * the whole row.
     *
     * @param y      the luma samples
     * @param yPos   the index of the luma sample of pixel 0
     * @param u      the Cb value of every pixel
     * @param v      the Cr value of every pixel
     * @param dst    the RGBA destination
     * @param dstPos the index of the first byte of pixel 0
     * @param from   the first pixel converted
     * @param count  the number of pixels of the row
     */
    void yuvToRgbaRowFrom(byte[] y, int yPos, int[] u, int[] v, byte[] dst, int dstPos, int from, int count) {
        for (var i = from; i < count; i++) {
            var yy = y[yPos + i] & 0xFF;
            var uu = u[i];
            var vv = v[i];
            var d = dstPos + (i << 2);
            dst[d] = (byte) yuvToR(yy, vv);
            dst[d + 1] = (byte) yuvToG(yy, uu, vv);
            dst[d + 2] = (byte) yuvToB(yy, uu);
            dst[d + 3] = (byte) 0xFF;
        }
    }

    @Override
    public void rgbaToYRow(byte[] rgba, int rgbaPos, byte[] y, int yPos, int count) {
        for (var i = 0; i < count; i++) {
            var p = rgbaPos + (i << 2);
            y[yPos + i] = (byte) rgbToY(rgba[p] & 0xFF, rgba[p + 1] & 0xFF, rgba[p + 2] & 0xFF, 1 << 15);
        }
    }

    /**
     * Multiplies by a 14-bit coefficient and drops 8 fractional bits.
     *
     * @param v     the sample
     * @param coeff the coefficient
     * @return {@code (v * coeff) >> 8}
     */
    private static int mulHi(int v, int coeff) {
        return (v * coeff) >> 8;
    }

    /**
     * Drops 6 fractional bits and clips to 0..255.
     *
     * @param v the fixed-point value
     * @return the 8-bit channel value
     */
    static int clip8(int v) {
        return (v & ~YUV_MASK2) == 0 ? v >> 6 : v < 0 ? 0 : 255;
    }

    /**
     * Returns the red channel of a Y'CbCr sample.
     *
     * @param y the luma
     * @param v the Cr value
     * @return the red value
     */
    public static int yuvToR(int y, int v) {
        return clip8(mulHi(y, 19077) + mulHi(v, 26149) - 14234);
    }

    /**
     * Returns the green channel of a Y'CbCr sample.
     *
     * @param y the luma
     * @param u the Cb value
     * @param v the Cr value
     * @return the green value
     */
    public static int yuvToG(int y, int u, int v) {
        return clip8(mulHi(y, 19077) - mulHi(u, 6419) - mulHi(v, 13320) + 8708);
    }

    /**
     * Returns the blue channel of a Y'CbCr sample.
     *
     * @param y the luma
     * @param u the Cb value
     * @return the blue value
     */
    public static int yuvToB(int y, int u) {
        return clip8(mulHi(y, 19077) + mulHi(u, 33050) - 17685);
    }

    /**
     * Returns the limited-range luma of an RGB color.
     *
     * @param r        the red value
     * @param g        the green value
     * @param b        the blue value
     * @param rounding the rounding term added before the 16-bit shift
     * @return the luma, from 16 to 235
     */
    public static int rgbToY(int r, int g, int b, int rounding) {
        var luma = 16839 * r + 33059 * g + 6420 * b;
        return (luma + rounding + (16 << 16)) >> 16;
    }

    /**
     * Returns the limited-range Cb of four summed RGB colors.
     *
     * @param r        the sum of four red values (or of their gamma-averaged equivalent)
     * @param g        the sum of four green values
     * @param b        the sum of four blue values
     * @param rounding the rounding term added before the 18-bit shift
     * @return the Cb value, clipped to 0..255
     */
    public static int rgbToU(int r, int g, int b, int rounding) {
        return clipUv(-9719 * r - 19081 * g + 28800 * b, rounding);
    }

    /**
     * Returns the limited-range Cr of four summed RGB colors.
     *
     * @param r        the sum of four red values (or of their gamma-averaged equivalent)
     * @param g        the sum of four green values
     * @param b        the sum of four blue values
     * @param rounding the rounding term added before the 18-bit shift
     * @return the Cr value, clipped to 0..255
     */
    public static int rgbToV(int r, int g, int b, int rounding) {
        return clipUv(28800 * r - 24116 * g - 4684 * b, rounding);
    }

    /**
     * Offsets, descales and clips a chroma accumulator.
     *
     * @param uv       the accumulator
     * @param rounding the rounding term
     * @return the chroma value, clipped to 0..255
     */
    private static int clipUv(int uv, int rounding) {
        uv = (uv + rounding + (128 << 18)) >> 18;
        return (uv & ~0xFF) == 0 ? uv : uv < 0 ? 0 : 255;
    }
}
