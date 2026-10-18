package com.github.auties00.smalt.internal.simd;

import jdk.incubator.vector.IntVector;

/**
 * Queries the Vector API for the preferred vector shape.
 *
 * <p>This class references {@code jdk.incubator.vector} types, so it is loaded only after
 * {@link Simd} verified that the module is present.
 */
final class VectorProbe {
    /**
     * Prevents instantiation.
     */
    private VectorProbe() {
        throw new AssertionError();
    }

    /**
     * Returns the width of the platform's preferred integer vector.
     *
     * @return the width in bits
     */
    static int preferredBits() {
        return IntVector.SPECIES_PREFERRED.vectorBitSize();
    }
}
