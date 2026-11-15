package com.github.auties00.smalt.audio.vorbis.internal;

/**
 * Holds the scale conversions of the libvorbis psychoacoustics: decibels, Bark and octaves.
 *
 * @implNote This implementation ports libvorbis 1.3.7's {@code scales.h} (BSD-3), including its
 * bit-pattern decibel estimate, whose small bias the encoder tunings take into account.
 */
public final class Scales {
    /**
     * Prevents instantiation.
     */
    private Scales() {
        throw new AssertionError();
    }

    /**
     * Returns an estimate of {@code 20 log10(|x|)} from the float's bit pattern.
     *
     * @param x the value
     * @return the decibels
     */
    public static float todB(float x) {
        var bits = Float.floatToRawIntBits(x) & 0x7FFFFFFF;
        return bits * 7.17711438e-7f - 764.6161886f;
    }

    /**
     * Returns {@code 1} with the sign of a value.
     *
     * @param x the value
     * @return {@code 1} or {@code -1}
     */
    public static float unitNorm(float x) {
        return Float.intBitsToFloat((Float.floatToRawIntBits(x) & 0x80000000) | 0x3F800000);
    }

    /**
     * Returns the Bark rate of a frequency ({@code toBARK}).
     *
     * @param n the frequency in hertz, an integer value
     * @return the Bark rate, in the double precision the C macro evaluates to
     */
    public static double toBark(float n) {
        return 13.1f * StrictMath.atan(.00074f * n) + 2.24f * StrictMath.atan(n * n * 1.85e-8f) + 1e-4f * n;
    }

    /**
     * Returns the octave of a frequency, octave 0 being 63.5 Hz ({@code toOC}).
     *
     * @param n the frequency in hertz
     * @return the octave
     */
    public static double toOctave(double n) {
        return StrictMath.log(n) * 1.442695f - 5.965784f;
    }

    /**
     * Returns the frequency of an octave ({@code fromOC}).
     *
     * @param o the octave
     * @return the frequency in hertz
     */
    public static double fromOctave(double o) {
        return StrictMath.exp((o + 5.965784f) * .693147f);
    }
}
