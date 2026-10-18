package com.github.auties00.smalt.audio.aac.internal.sbr;

import com.github.auties00.smalt.internal.simd.Simd;

/**
 * Selects the {@link SbrKernel} implementation once per JVM.
 */
public final class SbrKernels {
    /**
     * The kernel every spectral band replication component uses: the Vector API implementation when
     * {@link Simd#ENABLED}, the scalar reference otherwise.
     */
    static final SbrKernel INSTANCE = Simd.ENABLED ? VectorSbrKernelHolder.create() : new ScalarSbrKernel();

    /**
     * Prevents instantiation.
     */
    private SbrKernels() {
        throw new AssertionError();
    }

    /**
     * Returns the scalar reference kernel.
     *
     * @return a new scalar kernel
     */
    static SbrKernel scalar() {
        return new ScalarSbrKernel();
    }

    /**
     * Returns the Vector API kernel regardless of {@link Simd#ENABLED}.
     *
     * @return a new vector kernel
     * @throws LinkageError if the {@code jdk.incubator.vector} module is absent
     */
    static SbrKernel vector() {
        return VectorSbrKernelHolder.create();
    }
}
