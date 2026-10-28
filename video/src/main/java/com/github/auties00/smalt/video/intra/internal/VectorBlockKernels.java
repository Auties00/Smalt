package com.github.auties00.smalt.video.intra.internal;

import com.github.auties00.smalt.internal.simd.Simd;

/**
 * Creates the Vector API implementation of {@link BlockKernel}.
 *
 * <p>The factory returns the kernel through the scalar interface type, so the classes that select a
 * kernel never link against {@code jdk.incubator.vector} types themselves; this class is only
 * loaded when {@link Simd#ENABLED} is {@code true}. It falls back to the scalar kernel unless
 * {@link Simd#WIDE} is {@code true}, since the kernel is written for eight 32-bit lanes, which
 * narrower vectors would run in the Vector API's slow emulation.
 */
public final class VectorBlockKernels {
    /**
     * Prevents instantiation.
     */
    private VectorBlockKernels() {
        throw new AssertionError();
    }

    /**
     * Returns the block kernel for this platform.
     *
     * @return the vector kernel, or the scalar one on platforms without 256-bit vectors
     */
    static BlockKernel create() {
        return Simd.WIDE ? new VectorBlockKernel() : new ScalarBlockKernel();
    }

    /**
     * Returns the vector kernel regardless of the platform's vector width, for equivalence tests.
     *
     * @return the vector kernel
     */
    public static BlockKernel vector() {
        return new VectorBlockKernel();
    }
}
