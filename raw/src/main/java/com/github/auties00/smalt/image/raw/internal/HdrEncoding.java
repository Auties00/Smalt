/* Ports the Digital Negative (DNG) Specification 1.7.1.0, Adobe. This product includes DNG technology under license
   by Adobe. */
package com.github.auties00.smalt.image.raw.internal;

/**
 * Applies the encoding function of DNG high-dynamic-range camera profiles ({@code ProfileDynamicRange}), which
 * compresses linear values from 0 to 16 into 0 to 1 so that the profile's tables and curves, defined over 0 to 1,
 * reach values above reference white: {@code f(x) = x (256 + x) / (256 (1 + x))}, and its inverse
 * {@code f^-1(y) = 16 (8 y - 8 + sqrt(64 y^2 - 127 y + 64))}.
 *
 * <p>Both functions are continued below 0 by the identity, which meets them with the same value and slope at 0;
 * above 16 and above 1 respectively they keep their formulas, which stay increasing.
 */
final class HdrEncoding {
    /**
     * Prevents instantiation.
     */
    private HdrEncoding() {
        throw new AssertionError();
    }

    /**
     * Encodes a linear value.
     *
     * @param x the linear value
     * @return the encoded value: 1 for 16, below 1 for smaller values
     */
    static float encode(float x) {
        if (!(x > 0)) {
            return x;
        }
        double v = x;
        return (float) (v * (256 + v) / (256 * (1 + v)));
    }

    /**
     * Decodes an encoded value.
     *
     * @param y the encoded value
     * @return the linear value
     */
    static float decode(float y) {
        if (!(y > 0)) {
            return y;
        }
        double v = y;
        // 16 (8y - 8 + sqrt(q)) rewritten as 16 y / (sqrt(q) - 8y + 8), since q - (8y - 8)^2 = y, which keeps its
        // precision near 0 where the two terms of the specification's form cancel
        return (float) (16 * v / (Math.sqrt(64 * v * v - 127 * v + 64) - 8 * v + 8));
    }

    /**
     * Encodes rows of values in place.
     *
     * @param r the red values
     * @param g the green values
     * @param b the blue values
     * @param count the number of pixels
     */
    static void encode(float[] r, float[] g, float[] b, int count) {
        for (var index = 0; index < count; index++) {
            r[index] = encode(r[index]);
            g[index] = encode(g[index]);
            b[index] = encode(b[index]);
        }
    }

    /**
     * Decodes rows of values in place.
     *
     * @param r the red values
     * @param g the green values
     * @param b the blue values
     * @param count the number of pixels
     */
    static void decode(float[] r, float[] g, float[] b, int count) {
        for (var index = 0; index < count; index++) {
            r[index] = decode(r[index]);
            g[index] = decode(g[index]);
            b[index] = decode(b[index]);
        }
    }
}
