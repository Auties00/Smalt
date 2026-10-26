package com.github.auties00.smalt.audio.ac3;

/**
 * Enumerates the dynamic range control profiles from which an encoder computes the dynamic range
 * words ({@code dynrng}, applied by decoders in line mode) and the heavy compression words
 * ({@code compr}, applied in RF mode) of AC-3 and E-AC-3 streams (ATSC A/52 section 7.7).
 *
 * <p>A profile is a static curve from the level of the program, measured relative to its declared
 * dialogue level, to the gain the words carry: a maximum boost of quiet passages, a boost range, a
 * null band around the dialogue level where the gain is 0 dB, and cut ranges for loud passages. The
 * levels below are those of a program declaring a dialogue level of -31 dBFS; for another dialogue
 * level, the curve moves with it. The ranges are the ones Dolby publishes for its encoders' profiles
 * of the same names.
 */
public enum Ac3DynamicRangeProfile {
    /**
     * No dynamic range control: the words are left out, so decoders apply no gain.
     */
    NONE(0, 0, 0, 0, 0, 0, 0, 0),

    /**
     * Film standard: up to 6 dB of boost below -43 dB, a 2:1 boost range to -31 dB, a null band to
     * -26 dB, a 2:1 early cut range to -16 dB and a 20:1 cut above.
     */
    FILM_STANDARD(6, -43, 2, -31, -26, 2, -16, 20),

    /**
     * Film light: up to 6 dB of boost below -53 dB, a 2:1 boost range to -41 dB, a null band to
     * -21 dB, a 2:1 early cut range to -11 dB and a 20:1 cut above.
     */
    FILM_LIGHT(6, -53, 2, -41, -21, 2, -11, 20),

    /**
     * Music standard: up to 12 dB of boost below -55 dB, a 2:1 boost range to -31 dB, a null band
     * to -26 dB, a 2:1 early cut range to -16 dB and a 20:1 cut above.
     */
    MUSIC_STANDARD(12, -55, 2, -31, -26, 2, -16, 20),

    /**
     * Music light: up to 12 dB of boost below -65 dB, a 2:1 boost range to -41 dB, a null band to
     * -21 dB and a 2:1 cut above.
     */
    MUSIC_LIGHT(12, -65, 2, -41, -21, 2, Double.POSITIVE_INFINITY, 2),

    /**
     * Speech: up to 15 dB of boost below -50 dB, a 5:1 boost range to -31 dB, a null band to -26 dB,
     * a 2:1 early cut range to -16 dB and a 20:1 cut above.
     */
    SPEECH(15, -50, 5, -31, -26, 2, -16, 20);

    /**
     * The largest boost in decibels.
     */
    private final double maximumBoost;

    /**
     * The level below which the boost is largest.
     */
    private final double boostStart;

    /**
     * {@return the largest boost of the profile in dB}
     */
    public double maximumBoost() {
        return maximumBoost;
    }

    /**
     * {@return the level in dB below which the profile boosts by {@link #maximumBoost()}, at a
     * dialogue level of -31 dBFS}
     */
    public double boostStart() {
        return boostStart;
    }

    /**
     * The compression ratio of the boost range.
     */
    private final double boostRatio;

    /**
     * The level where the null band starts and the boost range ends.
     */
    private final double nullStart;

    /**
     * The level where the null band ends and the early cut range starts.
     */
    private final double nullEnd;

    /**
     * The compression ratio of the early cut range.
     */
    private final double earlyCutRatio;

    /**
     * The level where the early cut range ends and the cut range starts.
     */
    private final double cutStart;

    /**
     * The compression ratio of the cut range.
     */
    private final double cutRatio;

    Ac3DynamicRangeProfile(double maximumBoost, double boostStart, double boostRatio, double nullStart, double nullEnd,
                           double earlyCutRatio, double cutStart, double cutRatio) {
        this.maximumBoost = maximumBoost;
        this.boostStart = boostStart;
        this.boostRatio = boostRatio;
        this.nullStart = nullStart;
        this.nullEnd = nullEnd;
        this.earlyCutRatio = earlyCutRatio;
        this.cutStart = cutStart;
        this.cutRatio = cutRatio;
    }

    /**
     * Returns the gain the profile gives a program level.
     *
     * <p>The level is the program's level in dB relative to full scale shifted so that its declared
     * dialogue level falls at -31 dB. {@link #NONE} returns 0 for every level.
     *
     * @param level the shifted program level in dB
     * @return the gain in dB, positive to boost and negative to cut
     */
    public double gain(double level) {
        if (this == NONE) {
            return 0;
        }
        if (level < nullStart) {
            return Math.min(maximumBoost, (nullStart - level) * (1 - 1 / boostRatio));
        }
        if (level < nullEnd) {
            return 0;
        }
        if (level < cutStart) {
            return -(level - nullEnd) * (1 - 1 / earlyCutRatio);
        }
        return -(cutStart - nullEnd) * (1 - 1 / earlyCutRatio) - (level - cutStart) * (1 - 1 / cutRatio);
    }
}
