package com.github.auties00.smalt.audio.ac3;

/**
 * Enumerates the levels at which a downmix mixes a center or surround channel into the front pair,
 * the values of the mix level fields of AC-3 and E-AC-3 (ATSC A/52 tables 5.9, 5.10 and D2.3 to
 * D2.6).
 *
 * <p>The center mix level {@code cmixlev} of AC-3 takes -3, -4.5 and -6 dB, its surround mix level
 * {@code surmixlev} -3 dB, -6 dB and silence; the Lt/Rt and Lo/Ro center levels of the Annex D syntax
 * and of the E-AC-3 mixing metadata take every level, their surround levels -1.5 dB and below.
 */
public enum Ac3MixLevel {
    /**
     * +3 dB, a gain of 1.414.
     */
    PLUS_3_DB(3),

    /**
     * +1.5 dB, a gain of 1.189.
     */
    PLUS_1_5_DB(1.5),

    /**
     * 0 dB, a gain of 1.
     */
    ZERO_DB(0),

    /**
     * -1.5 dB, a gain of 0.841.
     */
    MINUS_1_5_DB(-1.5),

    /**
     * -3 dB, a gain of 0.707.
     */
    MINUS_3_DB(-3),

    /**
     * -4.5 dB, a gain of 0.595.
     */
    MINUS_4_5_DB(-4.5),

    /**
     * -6 dB, a gain of 0.5.
     */
    MINUS_6_DB(-6),

    /**
     * Silence: the channel is left out of the downmix.
     */
    SILENT(Double.NEGATIVE_INFINITY);

    /**
     * The level in decibels.
     */
    private final double decibels;

    Ac3MixLevel(double decibels) {
        this.decibels = decibels;
    }

    /**
     * {@return the level in decibels, negative infinity for {@link #SILENT}}
     */
    public double decibels() {
        return decibels;
    }

    /**
     * {@return the linear gain of the level, 0 for {@link #SILENT}}
     */
    public double gain() {
        return this == SILENT ? 0 : StrictMath.pow(10, decibels / 20);
    }
}
