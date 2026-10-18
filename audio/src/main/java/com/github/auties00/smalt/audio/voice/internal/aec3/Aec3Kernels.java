package com.github.auties00.smalt.audio.voice.internal.aec3;

import com.github.auties00.smalt.internal.simd.Simd;

/**
 * Selects the kernel implementations of AEC3.
 *
 * <p>The scalar kernels are always available and reproduce the reference bit for bit. The
 * Vector API kernels are used only when {@link Simd#ENABLED} is {@code true} and the caller asks
 * for them; this class never links the vector classes otherwise.
 */
final class Aec3Kernels {
    /**
     * The shared scalar matched filter kernel.
     */
    private static final MatchedFilterKernel SCALAR_MATCHED_FILTER = new ScalarMatchedFilterKernel();

    /**
     * The shared scalar adaptive filter kernel.
     */
    private static final AdaptiveFilterKernel SCALAR_ADAPTIVE_FILTER = new ScalarAdaptiveFilterKernel();

    /**
     * Prevents instantiation.
     */
    private Aec3Kernels() {
        throw new AssertionError();
    }

    /**
     * Returns the matched filter kernel.
     *
     * @param vectorize whether the Vector API kernel is wanted
     * @return the vector kernel when wanted and available, otherwise the scalar one
     */
    static MatchedFilterKernel matchedFilter(boolean vectorize) {
        if (!vectorize || !Simd.ENABLED) {
            return SCALAR_MATCHED_FILTER;
        }
        return VectorAec3Kernels.matchedFilter();
    }

    /**
     * Returns the adaptive filter kernel.
     *
     * @param vectorize whether the Vector API kernel is wanted
     * @return the vector kernel when wanted and available, otherwise the scalar one
     */
    static AdaptiveFilterKernel adaptiveFilter(boolean vectorize) {
        if (!vectorize || !Simd.ENABLED) {
            return SCALAR_ADAPTIVE_FILTER;
        }
        return VectorAec3Kernels.adaptiveFilter();
    }
}
