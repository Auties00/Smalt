package com.github.auties00.smalt.image.raw.internal;

/**
 * Applies a DNG hue, saturation and value table to rows of planar linear ProPhoto RGB pixels.
 *
 * <p>For each pixel whose largest component is positive, the color is converted to hue (0 to 6),
 * saturation and value, the table's hue shift, saturation scale and value scale are interpolated
 * trilinearly at that point (hue wrapping around, the value axis optionally sRGB-encoded), applied,
 * and the color converted back; other pixels are left as they are. Every step runs in single
 * precision without fused multiply-add, in the order {@link ScalarHueSatKernel} spells out; the
 * scalar and Vector API implementations return bit-identical values. The results replace the
 * inputs.
 */
interface HueSatKernel {
    /**
     * Applies the table to a row in place.
     *
     * @param table the hue, saturation and value table to apply
     * @param r the red values
     * @param g the green values
     * @param b the blue values
     * @param count the number of pixels
     */
    void apply(HueSatTable table, float[] r, float[] g, float[] b, int count);
}
