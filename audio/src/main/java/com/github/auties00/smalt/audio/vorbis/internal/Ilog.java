package com.github.auties00.smalt.audio.vorbis.internal;

/**
 * Computes the bit length of an integer, the {@code ilog} function of the Vorbis I specification.
 */
public final class Ilog {
    /**
     * Prevents instantiation.
     */
    private Ilog() {
        throw new AssertionError();
    }

    /**
     * Returns the position of the highest set bit of a value (Vorbis I section 9.2.1).
     *
     * @param value the integer, of any sign
     * @return the bit length, from 1 to 31, or 0 for zero and negative values
     */
    public static int ilog(int value) {
        return value <= 0 ? 0 : 32 - Integer.numberOfLeadingZeros(value);
    }
}
