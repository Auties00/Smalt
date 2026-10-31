package com.github.auties00.smalt.video.vvc.internal;

import com.github.auties00.smalt.internal.simd.Simd;

/**
 * Selects the sample loop kernels of the VVC decoder once per JVM: the Vector API kernels where 256-bit vectors are
 * available ({@link Simd#WIDE}), the scalar ones otherwise.
 */
final class VvcDsp {
    /**
     * The kernels of this JVM.
     */
    static final ScalarVvcDsp KERNELS = Simd.WIDE ? VectorVvcDsp.create() : new ScalarVvcDsp();

    private VvcDsp() {
        throw new AssertionError();
    }
}
