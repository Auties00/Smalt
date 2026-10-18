package com.github.auties00.smalt.image.raw.internal;

/**
 * Applies a 3x3 color matrix to rows of planar pixels and scales the result to transfer-table
 * indices.
 *
 * <p>For each pixel, {@code out = clamp(matrix * (r, g, b), 0, 1) * scale}, computed in single
 * precision without fused multiply-add, in the order {@code m0 * r + m1 * g + m2 * b}; the results
 * replace the inputs. The scalar and Vector API implementations return bit-identical values.
 */
interface ColorKernel {
    /**
     * Transforms a row in place.
     *
     * @param r the red values
     * @param g the green values
     * @param b the blue values
     * @param count the number of pixels
     * @param matrix the nine matrix coefficients, row by row
     * @param scale the factor applied after clamping
     */
    void transform(float[] r, float[] g, float[] b, int count, float[] matrix, float scale);

    /**
     * Clamps a row to [0, 1] and scales it, in place.
     *
     * @param r the red values
     * @param g the green values
     * @param b the blue values
     * @param count the number of pixels
     * @param scale the factor applied after clamping
     */
    void scale(float[] r, float[] g, float[] b, int count, float scale);
}
