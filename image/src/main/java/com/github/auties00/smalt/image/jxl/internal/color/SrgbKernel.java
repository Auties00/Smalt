/* Ports jxl-oxide 0.12.6 (crates/jxl-color) and libjxl (lib/jxl/cms). Copyright Wonwoo Choi and the jxl-oxide
   contributors, and the JPEG XL Project Authors; MIT License and BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.image.jxl.internal.color;

import com.github.auties00.smalt.internal.simd.Simd;

/**
 * Encodes linear samples with the sRGB transfer function in place, negative samples mirrored.
 *
 * <p>Every implementation computes, per sample, {@link ColorConverter#srgbFromLinear(float)} of
 * the magnitude with the same operations, negated when the sample is below zero, so they return
 * bit-identical samples.
 */
interface SrgbKernel {
    /**
     * The implementation selected for this JVM: {@link VectorSrgbKernel} when the Vector API is
     * usable ({@link Simd#ENABLED}), else {@link ScalarSrgbKernel}.
     */
    SrgbKernel INSTANCE = Simd.ENABLED ? VectorHolder.create() : new ScalarSrgbKernel();

    /**
     * Encodes a range of samples in place.
     *
     * @param plane the samples
     * @param from the first sample
     * @param to the end of the range
     */
    void encode(float[] plane, int from, int to);

    /**
     * Creates the Vector API kernel in a class of its own, so that the vector types are linked only
     * when it is used.
     */
    final class VectorHolder {
        /**
         * Prevents instantiation.
         */
        private VectorHolder() {
            throw new AssertionError();
        }

        /**
         * Creates the Vector API kernel.
         *
         * @return the kernel
         */
        static SrgbKernel create() {
            return new VectorSrgbKernel();
        }
    }
}
