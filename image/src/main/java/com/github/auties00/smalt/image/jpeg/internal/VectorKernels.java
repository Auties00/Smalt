package com.github.auties00.smalt.image.jpeg.internal;

import jdk.incubator.vector.IntVector;

/**
 * Creates the Vector API kernels of the JPEG codec.
 *
 * <p>Every factory returns its kernel through the scalar interface type, so the classes that
 * select a kernel never link against {@code jdk.incubator.vector} types themselves; this class is
 * only loaded when {@link com.github.auties00.smalt.internal.simd.Simd#ENABLED} is
 * {@code true}. A factory falls back to the scalar kernel when the platform's preferred vector is
 * narrower than the eight 32-bit lanes the kernels are written for, because wider vectors would
 * then run in the Vector API's slow emulation.
 */
final class VectorKernels {
    /**
     * Whether the platform executes eight 32-bit lanes natively.
     */
    private static final boolean WIDE = IntVector.SPECIES_PREFERRED.vectorBitSize() >= 256;

    /**
     * Prevents instantiation.
     */
    private VectorKernels() {
        throw new AssertionError();
    }

    /**
     * Returns the batched inverse DCT kernel.
     *
     * @return the vector kernel, or the scalar one on platforms without 256-bit vectors
     */
    static BatchIdctKernel batchIdct() {
        return WIDE ? new VectorBatchIdctKernel() : new ScalarBatchIdctKernel();
    }

    /**
     * Returns the vector batched inverse DCT kernel regardless of the platform's vector width,
     * for equivalence tests.
     *
     * @return the vector kernel
     */
    static BatchIdctKernel vectorBatchIdct() {
        return new VectorBatchIdctKernel();
    }

    /**
     * Returns the batched forward DCT and quantization kernel.
     *
     * @return the vector kernel, or the scalar one on platforms without 256-bit vectors
     */
    static BatchFdctKernel batchFdct() {
        return WIDE ? new VectorBatchFdctKernel() : new ScalarBatchFdctKernel();
    }

    /**
     * Returns the vector batched forward DCT kernel regardless of the platform's vector width,
     * for equivalence tests.
     *
     * @return the vector kernel
     */
    static BatchFdctKernel vectorBatchFdct() {
        return new VectorBatchFdctKernel();
    }

    /**
     * Returns the RGBA to Y'CbCr conversion kernel.
     *
     * @return the vector kernel, or the scalar one on platforms without 256-bit vectors
     */
    static ColorKernel color() {
        return WIDE ? new VectorColorKernel() : new ScalarColorKernel();
    }

    /**
     * Returns the vector color conversion kernel regardless of the platform's vector width, for
     * equivalence tests.
     *
     * @return the vector kernel
     */
    static ColorKernel vectorColor() {
        return new VectorColorKernel();
    }
}
