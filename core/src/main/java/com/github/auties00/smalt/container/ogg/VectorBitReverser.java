package com.github.auties00.smalt.container.ogg;

import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

/**
 * Implements {@link BitReverser} with the Vector API.
 *
 * <p>This class references {@code jdk.incubator.vector} types, so it is loaded only when
 * {@link com.github.auties00.smalt.internal.simd.Simd#ENABLED} is {@code true}. The tail
 * shorter than one vector goes through {@link ScalarBitReverser}.
 */
final class VectorBitReverser implements BitReverser {
    /**
     * The byte species, the platform's preferred shape.
     */
    private static final VectorSpecies<Byte> BYTES = ByteVector.SPECIES_PREFERRED;

    /**
     * The concrete class of the vectors of {@link #BYTES}.
     */
    @SuppressWarnings("unchecked")
    private static final Class<ByteVector> BYTE_VECTOR = (Class<ByteVector>) BYTES.vectorType();

    /**
     * Reverses the tail shorter than one vector.
     */
    private final ScalarBitReverser tail;

    /**
     * Constructs the kernel.
     */
    private VectorBitReverser() {
        this.tail = new ScalarBitReverser();
    }

    /**
     * Creates the kernel.
     *
     * @return a new vector kernel
     */
    static BitReverser create() {
        return new VectorBitReverser();
    }

    @Override
    public void reverse(byte[] source, int sourceOffset, byte[] target, int targetOffset, int length) {
        var bound = BYTES.loopBound(length);
        var index = 0;
        for (; index < bound; index += BYTES.length()) {
            BYTE_VECTOR.cast(ByteVector.fromArray(BYTES, source, sourceOffset + index))
                    .lanewise(VectorOperators.REVERSE)
                    .intoArray(target, targetOffset + index);
        }
        tail.reverse(source, sourceOffset + index, target, targetOffset + index, length - index);
    }
}
