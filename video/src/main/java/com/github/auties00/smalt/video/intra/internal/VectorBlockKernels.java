package com.github.auties00.smalt.video.intra.internal;

import jdk.incubator.vector.FloatVector;

/**
 * Creates the Vector API implementation of {@link BlockKernel}.
 *
 * <p>The factory returns the kernel through the scalar interface type, so the classes that select a
 * kernel never link against {@code jdk.incubator.vector} types themselves; this class is only
 * loaded when {@link com.github.auties00.smalt.internal.simd.Simd#ENABLED} is {@code true}. It
 * falls back to the scalar kernel when the platform's preferred vector is narrower than the eight
 * 32-bit lanes the kernel is written for, because wider vectors would then run in the Vector API's
 * slow emulation.
 */
public final class VectorBlockKernels {
    /**
     * Whether the platform executes eight 32-bit lanes natively.
     */
    private static final boolean WIDE = FloatVector.SPECIES_PREFERRED.vectorBitSize() >= 256;

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
        return WIDE ? new VectorBlockKernel() : new ScalarBlockKernel();
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
