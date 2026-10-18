package com.github.auties00.smalt.image.ops;

/**
 * Computes chroma samples as alpha-weighted averages of the pixels they cover
 * ({@link ChromaDownsampling#ALPHA_WEIGHTED}).
 *
 * <p>With {@code a_i} the alpha of covered pixel {@code i}, the chroma sample is computed from
 * the weighted means {@code sum(a_i * r_i) / sum(a_i)} (and likewise for green and blue) with
 * the forward coefficients of {@link ColorMatrix}, rounded once with the same half-down
 * convention as the unweighted average. A block whose pixels are all fully transparent uses the
 * unweighted average.
 */
final class AlphaWeighting {
    /**
     * Prevents instantiation.
     */
    private AlphaWeighting() {
        throw new AssertionError();
    }

    /**
     * Computes one row of alpha-weighted chroma samples from one or two rows of RGBA pixels.
     *
     * @param src   the RGBA source
     * @param off0  the index of the upper row's first byte
     * @param off1  the index of the lower row's first byte; ignored unless {@code pairY}
     * @param width the number of pixels per row
     * @param pairX whether a chroma sample covers two columns
     * @param pairY whether a chroma sample covers two distinct rows
     * @param u     the Cb destination
     * @param v     the Cr destination
     * @param cOff  the index of the row's first chroma sample
     * @param m     the conversion coefficients
     */
    static void rgbaToChroma(byte[] src, int off0, int off1, int width, boolean pairX, boolean pairY,
                             byte[] u, byte[] v, int cOff, ColorMatrix m) {
        var count = pairX ? (width + 1) >> 1 : width;
        var slots = (pairX ? 2 : 1) * (pairY ? 2 : 1);
        var one = 1L << ColorMatrix.BITS;
        var constantU = (m.offsets[1] * one + one / 2) * slots - 1;
        var constantV = (m.offsets[2] * one + one / 2) * slots - 1;
        for (var k = 0; k < count; k++) {
            var x0 = pairX ? 2 * k : k;
            var x1 = pairX ? Math.min(2 * k + 1, width - 1) : x0;
            long weight = 0;
            long red = 0;
            long green = 0;
            long blue = 0;
            long plainRed = 0;
            long plainGreen = 0;
            long plainBlue = 0;
            for (var row = 0; row < (pairY ? 2 : 1); row++) {
                var base = row == 0 ? off0 : off1;
                for (var slot = 0; slot < (pairX ? 2 : 1); slot++) {
                    var index = base + 4 * (slot == 0 ? x0 : x1);
                    var r = src[index] & 0xFF;
                    var g = src[index + 1] & 0xFF;
                    var b = src[index + 2] & 0xFF;
                    var a = src[index + 3] & 0xFF;
                    weight += a;
                    red += (long) a * r;
                    green += (long) a * g;
                    blue += (long) a * b;
                    plainRed += r;
                    plainGreen += g;
                    plainBlue += b;
                }
            }
            if (weight == 0) {
                weight = slots;
                red = plainRed;
                green = plainGreen;
                blue = plainBlue;
            }
            u[cOff + k] = (byte) quotient(m.fu, red, green, blue, weight, slots, constantU, one);
            v[cOff + k] = (byte) quotient(m.fv, red, green, blue, weight, slots, constantV, one);
        }
    }

    /**
     * Applies a forward matrix row to weighted color sums, rescales them to the unweighted
     * average's sum of {@code slots} pixels, and rounds once.
     *
     * <p>The value is {@code floor((row . sums * slots + constant * weight) / (weight * slots *
     * one))}; for equal weights it reduces exactly to the unweighted formula.
     *
     * @param row      the forward coefficients multiplying R, G and B
     * @param red      the weighted red sum
     * @param green    the weighted green sum
     * @param blue     the weighted blue sum
     * @param weight   the total weight; positive
     * @param slots    the number of pixel slots the unweighted average sums
     * @param constant the unweighted forward constant for {@code slots} pixels, including the
     *                 rounding bias
     * @param one      the fixed-point unit
     * @return the clamped chroma sample
     */
    private static int quotient(int[] row, long red, long green, long blue, long weight, int slots, long constant, long one) {
        var numerator = (row[0] * red + row[1] * green + row[2] * blue) * slots + constant * weight;
        return (int) Math.clamp(Math.floorDiv(numerator, weight * slots * one), 0, 255);
    }
}
