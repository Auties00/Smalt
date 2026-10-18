package com.github.auties00.smalt.video.h264.encoder.internal;

/**
 * Creates the Vector API kernel implementations; loaded only when the Vector API is usable.
 */
final class VectorKernelsHolder {
    private VectorKernelsHolder() {
        throw new AssertionError();
    }

    /**
     * Returns the vector pixel kernels.
     *
     * @return the kernels
     */
    static PixelKernels pixels() {
        return new VectorPixelKernels();
    }

    /**
     * Returns the vector transform kernels.
     *
     * @return the kernels
     */
    static TransformKernels transform() {
        return new VectorTransformKernels();
    }

    /**
     * Returns the vector interpolation kernels.
     *
     * @return the kernels
     */
    static InterpolationKernels interpolation() {
        return new VectorInterpolationKernels();
    }
}
