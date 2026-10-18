/* Ports jxl-oxide 0.12.6 (crates/jxl-color) and libjxl (lib/jxl/cms). Copyright Wonwoo Choi and the jxl-oxide
   contributors, and the JPEG XL Project Authors; MIT License and BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.image.jxl.internal.color;

/**
 * Implements {@link SrgbKernel} one sample at a time.
 */
final class ScalarSrgbKernel implements SrgbKernel {
    /**
     * Creates the kernel.
     */
    ScalarSrgbKernel() {
    }

    @Override
    public void encode(float[] plane, int from, int to) {
        for (var i = from; i < to; i++) {
            plane[i] = encode(plane[i]);
        }
    }

    /**
     * Encodes one sample.
     *
     * @param v the linear sample
     * @return the encoded sample, negative when {@code v} is
     */
    static float encode(float v) {
        var magnitude = ColorConverter.srgbFromLinear(Math.abs(v));
        return v < 0.0f ? -magnitude : magnitude;
    }
}
