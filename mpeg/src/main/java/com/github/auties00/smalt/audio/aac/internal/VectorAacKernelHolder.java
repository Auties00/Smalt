package com.github.auties00.smalt.audio.aac.internal;

/**
 * Instantiates {@link VectorAacKernel} behind a signature that mentions no vector type, so
 * {@link AacKernels} links even when the {@code jdk.incubator.vector} module is absent.
 */
final class VectorAacKernelHolder {
    /**
     * Prevents instantiation.
     */
    private VectorAacKernelHolder() {
        throw new AssertionError();
    }

    /**
     * Creates the vector kernel.
     *
     * @return the kernel
     */
    static AacKernel create() {
        return new VectorAacKernel();
    }
}
