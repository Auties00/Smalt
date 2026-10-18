package com.github.auties00.smalt.audio.aac.internal;

import com.github.auties00.smalt.internal.simd.Simd;

/**
 * Selects the {@link AacKernel} implementation once per JVM.
 */
public final class AacKernels {
    /**
     * The kernel every AAC component uses: the Vector API implementation when
     * {@link Simd#ENABLED}, the scalar reference otherwise.
     */
    public static final AacKernel INSTANCE = Simd.ENABLED ? VectorAacKernelHolder.create() : new ScalarAacKernel();

    /**
     * Prevents instantiation.
     */
    private AacKernels() {
        throw new AssertionError();
    }

    /**
     * Returns the scalar reference kernel.
     *
     * @return a new scalar kernel
     */
    public static AacKernel scalar() {
        return new ScalarAacKernel();
    }

    /**
     * Returns the Vector API kernel regardless of {@link Simd#ENABLED}.
     *
     * @return a new vector kernel
     * @throws LinkageError if the {@code jdk.incubator.vector} module is absent
     */
    public static AacKernel vector() {
        return VectorAacKernelHolder.create();
    }
}
