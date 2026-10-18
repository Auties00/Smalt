package com.github.auties00.smalt.image.raw.internal;

import com.github.auties00.smalt.internal.simd.Simd;

/**
 * Selects the color, profile table and demosaicing kernel implementations of the platform: the Vector API kernels
 * when {@link Simd#ENABLED}, the scalar ones otherwise.
 */
final class ColorKernels {
    /**
     * The color kernel.
     */
    static final ColorKernel COLOR = Simd.ENABLED ? VectorColorKernel.create() : new ScalarColorKernel();

    /**
     * The hue, saturation and value table kernel.
     */
    static final HueSatKernel HUE_SAT = Simd.ENABLED ? VectorHueSatKernel.create() : new ScalarHueSatKernel();

    /**
     * The Bayer bilinear kernel.
     */
    static final BayerKernel BAYER = Simd.ENABLED ? VectorBayerKernel.create() : new ScalarBayerKernel();

    /**
     * Prevents instantiation.
     */
    private ColorKernels() {
        throw new AssertionError();
    }
}
