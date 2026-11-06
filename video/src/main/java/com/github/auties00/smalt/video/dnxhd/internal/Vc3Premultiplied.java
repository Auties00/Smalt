package com.github.auties00.smalt.video.dnxhd.internal;

/**
 * Applies and removes the alpha premultiplication of VC-3 coding units whose {@code PMA} flag is
 * set (ST 2019-1 Amendment 1:2023): every video sample is scaled by its alpha about a base level.
 *
 * <p>With {@code A} the alpha code and {@code M} the largest alpha code, a straight sample
 * {@code v} and its premultiplied form {@code p} are related by
 * {@snippet :
 * p = base + round((v - base) * A / M)
 * v = base + round((p - base) * M / A)      // A > 0, clipped to the sample range
 * v = base                                  // A == 0
 * }
 * where the base is the black level for luma and R, G and B, and the middle code for Cb and Cr, so
 * that premultiplying Y'CbCr equals premultiplying the R'G'B' it is converted from. A subsampled
 * chroma sample uses the mean alpha of the luma samples it covers. Rounding is to nearest, halves
 * away from zero.
 *
 * <p>Arguments are not checked; values outside the documented ranges give unspecified results.
 */
public final class Vc3Premultiplied {
    private Vc3Premultiplied() {
    }

    /**
     * Multiplies the samples of a plane by alpha in place, over a band of rows.
     *
     * @param plane the samples, unsigned
     * @param stride the row stride of {@code plane}
     * @param width the number of samples per row to process
     * @param firstRow the first row to process
     * @param endRow the row after the last one to process
     * @param shiftX the horizontal subsampling of the plane against the alpha plane: 0 or 1
     * @param shiftY the vertical subsampling of the plane against the alpha plane: 0 or 1
     * @param alpha the alpha codes, covering every sample of the plane at full resolution
     * @param alphaStride the row stride of {@code alpha}
     * @param alphaMax the largest alpha code
     * @param base the level the samples are scaled about
     */
    public static void apply(short[] plane, int stride, int width, int firstRow, int endRow, int shiftX, int shiftY,
                             short[] alpha, int alphaStride, int alphaMax, int base) {
        var count = 1 << (shiftX + shiftY);
        var scale = (long) alphaMax * count;
        for (var y = firstRow; y < endRow; y++) {
            for (var x = 0; x < width; x++) {
                var at = y * stride + x;
                var sum = alphaSum(alpha, alphaStride, x, y, shiftX, shiftY);
                plane[at] = (short) (base + divide((long) ((plane[at] & 0xFFFF) - base) * sum, scale));
            }
        }
    }

    /**
     * Divides the 16-bit samples of a plane by alpha in place, over a band of rows.
     *
     * @param plane the premultiplied samples, unsigned; the straight samples on return
     * @param stride the row stride of {@code plane}
     * @param width the number of samples per row to process
     * @param firstRow the first row to process
     * @param endRow the row after the last one to process
     * @param shiftX the horizontal subsampling of the plane against the alpha plane: 0 or 1
     * @param shiftY the vertical subsampling of the plane against the alpha plane: 0 or 1
     * @param alpha the alpha codes, covering every sample of the plane at full resolution
     * @param alphaStride the row stride of {@code alpha}
     * @param alphaMax the largest alpha code, also the largest sample code
     * @param base the level the samples are scaled about
     */
    public static void remove(short[] plane, int stride, int width, int firstRow, int endRow, int shiftX, int shiftY,
                              short[] alpha, int alphaStride, int alphaMax, int base) {
        var count = 1 << (shiftX + shiftY);
        for (var y = firstRow; y < endRow; y++) {
            for (var x = 0; x < width; x++) {
                var at = y * stride + x;
                var sum = alphaSum(alpha, alphaStride, x, y, shiftX, shiftY);
                plane[at] = (short) unscale(plane[at] & 0xFFFF, sum, (long) alphaMax * count, base, alphaMax);
            }
        }
    }

    /**
     * Divides the 8-bit samples of a plane by alpha in place, over a band of rows.
     *
     * @param plane the premultiplied samples, unsigned; the straight samples on return
     * @param stride the row stride of {@code plane}
     * @param width the number of samples per row to process
     * @param firstRow the first row to process
     * @param endRow the row after the last one to process
     * @param shiftX the horizontal subsampling of the plane against the alpha plane: 0 or 1
     * @param shiftY the vertical subsampling of the plane against the alpha plane: 0 or 1
     * @param alpha the 8-bit alpha codes, covering every sample of the plane at full resolution
     * @param alphaStride the row stride of {@code alpha}
     * @param base the level the samples are scaled about
     */
    public static void remove(byte[] plane, int stride, int width, int firstRow, int endRow, int shiftX, int shiftY,
                              byte[] alpha, int alphaStride, int base) {
        var count = 1 << (shiftX + shiftY);
        for (var y = firstRow; y < endRow; y++) {
            for (var x = 0; x < width; x++) {
                var at = y * stride + x;
                var sum = 0;
                for (var dy = 0; dy < 1 << shiftY; dy++) {
                    var row = ((y << shiftY) + dy) * alphaStride + (x << shiftX);
                    for (var dx = 0; dx < 1 << shiftX; dx++) {
                        sum += alpha[row + dx] & 0xFF;
                    }
                }
                plane[at] = (byte) unscale(plane[at] & 0xFF, sum, 255L * count, base, 255);
            }
        }
    }

    /**
     * Returns a full-range component divided by its full-range alpha.
     *
     * @param component the premultiplied component, from 0 to {@code max}
     * @param alpha the alpha, from 0 to {@code max}
     * @param max the largest component and alpha value
     * @return the straight component, from 0 to {@code max}; 0 when {@code alpha} is 0
     */
    public static int remove(int component, int alpha, int max) {
        return unscale(component, alpha, max, 0, max);
    }

    /**
     * Returns the sum of the alpha codes a sample covers.
     *
     * @param alpha the alpha codes
     * @param alphaStride the row stride of {@code alpha}
     * @param x the sample column
     * @param y the sample row
     * @param shiftX the horizontal subsampling
     * @param shiftY the vertical subsampling
     * @return the sum of {@code 2^(shiftX + shiftY)} codes
     */
    private static int alphaSum(short[] alpha, int alphaStride, int x, int y, int shiftX, int shiftY) {
        var sum = 0;
        for (var dy = 0; dy < 1 << shiftY; dy++) {
            var row = ((y << shiftY) + dy) * alphaStride + (x << shiftX);
            for (var dx = 0; dx < 1 << shiftX; dx++) {
                sum += alpha[row + dx] & 0xFFFF;
            }
        }
        return sum;
    }

    /**
     * Returns a premultiplied sample divided by its alpha.
     *
     * @param sample the premultiplied sample
     * @param alphaSum the sum of the alpha codes the sample covers
     * @param scale the largest alpha code times the number of codes summed
     * @param base the level the sample is scaled about
     * @param max the largest sample code
     * @return the straight sample, clipped to {@code [0, max]}; {@code base} when the alpha is 0
     */
    private static int unscale(int sample, int alphaSum, long scale, int base, int max) {
        if (alphaSum == 0) {
            return base;
        }
        return (int) Math.clamp(base + divide((sample - base) * scale, alphaSum), 0, max);
    }

    /**
     * Divides and rounds to nearest, halves away from zero.
     *
     * @param numerator the dividend
     * @param denominator the divisor, positive
     * @return the rounded quotient
     */
    private static long divide(long numerator, long denominator) {
        var half = denominator / 2;
        return numerator >= 0 ? (numerator + half) / denominator : -((-numerator + half) / denominator);
    }
}
