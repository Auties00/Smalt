package com.github.auties00.smalt.audio.ac3.internal;

/**
 * Holds the spectral extension attenuation table {@code spxattentab[][]} of ATSC A/52 Annex E
 * table E3.14.
 */
final class SpxAttenuation {
    /**
     * The three attenuation factors of each of the 32 attenuation codes; the notch filter applies
     * them as {@code [0], [1], [2], [1], [0]} across the five bins around a border.
     *
     * @implNote This implementation computes the table as {@code 2^(-(code + 1)(index + 1) / 15)},
     * which reproduces every printed entry of table E3.14 to its nine decimals.
     */
    static final float[][] FACTORS = factors();

    /**
     * Prevents instantiation.
     */
    private SpxAttenuation() {
        throw new AssertionError();
    }

    /**
     * Computes the table.
     *
     * @return the factors by code and bin index
     */
    private static float[][] factors() {
        var factors = new float[32][3];
        for (var code = 0; code < 32; code++) {
            for (var index = 0; index < 3; index++) {
                factors[code][index] = (float) Math.pow(2, -(code + 1) * (index + 1) / 15.0);
            }
        }
        return factors;
    }
}
