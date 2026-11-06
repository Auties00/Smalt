package com.github.auties00.smalt.video.h264.encoder.internal;

import com.github.auties00.smalt.internal.simd.Simd;

/**
 * Selects the implementation of every DSP kernel family a frame is encoded with: the Vector API
 * subclasses when {@link Simd#ENABLED}, the scalar reference implementations otherwise.
 *
 * <p>Every implementation of a family produces the same results for every input, so the bitstream
 * never depends on the selection.
 */
final class Kernels {
    /**
     * The scalar pixel metric and copy kernels.
     */
    static final PixelKernels SCALAR_PIXELS = new PixelKernels();

    /**
     * The scalar transform and quantization kernels.
     */
    static final TransformKernels SCALAR_TRANSFORM = new TransformKernels();

    /**
     * The scalar interpolation kernels.
     */
    static final InterpolationKernels SCALAR_INTERPOLATION = new InterpolationKernels();

    /**
     * The pixel kernels every frame uses.
     */
    private static final PixelKernels PIXELS = Simd.ENABLED ? VectorKernelsHolder.pixels() : SCALAR_PIXELS;

    /**
     * The transform kernels every frame uses.
     */
    private static final TransformKernels TRANSFORM = Simd.WIDE ? VectorKernelsHolder.transform() : SCALAR_TRANSFORM;

    /**
     * The interpolation kernels every frame uses.
     */
    private static final InterpolationKernels INTERPOLATION = Simd.WIDE
            ? VectorKernelsHolder.interpolation() : SCALAR_INTERPOLATION;

    private Kernels() {
        throw new AssertionError();
    }

    /**
     * Returns the pixel kernels frames are encoded with.
     *
     * @return the kernels
     */
    static PixelKernels pixels() {
        return PIXELS;
    }

    /**
     * Returns the transform kernels frames are encoded with.
     *
     * @return the kernels
     */
    static TransformKernels transform() {
        return TRANSFORM;
    }

    /**
     * Returns the interpolation kernels frames are encoded with.
     *
     * @return the kernels
     */
    static InterpolationKernels interpolation() {
        return INTERPOLATION;
    }
}
