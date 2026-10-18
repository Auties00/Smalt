package com.github.auties00.smalt.audio.aac.internal.sbr;

/**
 * Instantiates {@link VectorSbrKernel} behind a signature that mentions no vector type, so
 * {@link SbrKernels} links even when the {@code jdk.incubator.vector} module is absent.
 */
final class VectorSbrKernelHolder {
    /**
     * Prevents instantiation.
     */
    private VectorSbrKernelHolder() {
        throw new AssertionError();
    }

    /**
     * Creates the vector kernel.
     *
     * @return the kernel
     */
    static SbrKernel create() {
        return new VectorSbrKernel();
    }
}
