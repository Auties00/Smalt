package com.github.auties00.smalt.audio.ac3.internal;

import com.github.auties00.smalt.internal.simd.Simd;

/**
 * Selects the {@link Ac3Kernel} implementation once per JVM.
 */
public final class Ac3Kernels {
    /**
     * The kernel the codec uses: the Vector API kernel when {@link Simd#ENABLED}, the scalar kernel
     * otherwise.
     */
    public static final Ac3Kernel INSTANCE = Simd.ENABLED ? VectorAc3KernelHolder.create() : new ScalarAc3Kernel();

    /**
     * Prevents instantiation.
     */
    private Ac3Kernels() {
        throw new AssertionError();
    }

    /**
     * Returns a new scalar reference kernel.
     *
     * @return the scalar kernel
     */
    public static Ac3Kernel scalar() {
        return new ScalarAc3Kernel();
    }

    /**
     * Returns a new Vector API kernel.
     *
     * @return the vector kernel
     * @throws LinkageError if the incubating Vector API module is absent
     */
    public static Ac3Kernel vector() {
        return VectorAc3KernelHolder.create();
    }
}
