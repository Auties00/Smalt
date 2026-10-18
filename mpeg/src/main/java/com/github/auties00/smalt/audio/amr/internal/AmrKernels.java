package com.github.auties00.smalt.audio.amr.internal;

import com.github.auties00.smalt.internal.simd.Simd;

/**
 * Selects the {@link AmrKernel} implementation once per JVM, as {@link Simd} describes.
 */
public final class AmrKernels {
    /**
     * The kernel every AMR component uses: the Vector API implementation when {@link Simd#ENABLED}, the scalar
     * reference otherwise.
     */
    public static final AmrKernel INSTANCE = Simd.ENABLED ? VectorAmrKernel.create() : new ScalarAmrKernel();

    /**
     * Prevents instantiation.
     */
    private AmrKernels() {
        throw new AssertionError();
    }

    /**
     * Returns a new scalar reference kernel.
     *
     * @return the kernel
     */
    public static AmrKernel scalar() {
        return new ScalarAmrKernel();
    }

    /**
     * Returns a new Vector API kernel regardless of {@link Simd#ENABLED}.
     *
     * @return the kernel
     * @throws LinkageError if the {@code jdk.incubator.vector} module is absent
     */
    public static AmrKernel vector() {
        return VectorAmrKernel.create();
    }
}
