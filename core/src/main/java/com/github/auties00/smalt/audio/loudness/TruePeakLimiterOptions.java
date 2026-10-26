package com.github.auties00.smalt.audio.loudness;

import java.util.Objects;

/**
 * Holds the settings of a {@link TruePeakLimiter}. Instances are immutable and thread-safe; create them
 * with {@link #builder()} or a preset such as {@link #defaults()}.
 */
public final class TruePeakLimiterOptions {
    /**
     * The default ceiling, in dBTP.
     */
    public static final double DEFAULT_CEILING_DB = -1;

    /**
     * The default look-ahead, in milliseconds.
     */
    public static final double DEFAULT_LOOKAHEAD_MILLIS = 5;

    /**
     * The default release time, in milliseconds.
     */
    public static final double DEFAULT_RELEASE_MILLIS = 100;

    private static final TruePeakLimiterOptions DEFAULTS =
            new TruePeakLimiterOptions(DEFAULT_CEILING_DB, DEFAULT_LOOKAHEAD_MILLIS, DEFAULT_RELEASE_MILLIS);

    private final double ceilingDb;
    private final double lookaheadMillis;
    private final double releaseMillis;

    private TruePeakLimiterOptions(double ceilingDb, double lookaheadMillis, double releaseMillis) {
        this.ceilingDb = ceilingDb;
        this.lookaheadMillis = lookaheadMillis;
        this.releaseMillis = releaseMillis;
    }

    /**
     * Returns the default options: a ceiling of {@value #DEFAULT_CEILING_DB} dBTP, a look-ahead of
     * {@value #DEFAULT_LOOKAHEAD_MILLIS} ms and a release of {@value #DEFAULT_RELEASE_MILLIS} ms.
     *
     * @return the default options
     */
    public static TruePeakLimiterOptions defaults() {
        return DEFAULTS;
    }

    /**
     * Returns the default options with another ceiling.
     *
     * @param ceilingDb the highest true peak of the output, in dBTP, from -60 to 0
     * @return the options
     * @throws IllegalArgumentException if {@code ceilingDb} is out of range
     */
    public static TruePeakLimiterOptions of(double ceilingDb) {
        return builder().ceilingDb(ceilingDb).build();
    }

    /**
     * Returns a builder initialized with {@link #defaults()}.
     *
     * @return the builder
     */
    public static Builder builder() {
        return new Builder(DEFAULTS);
    }

    /**
     * Returns a builder initialized with these options.
     *
     * @return the builder
     */
    public Builder toBuilder() {
        return new Builder(this);
    }

    /**
     * {@return the highest true peak of the output, in dBTP, from -60 to 0; {@value #DEFAULT_CEILING_DB}
     * by default}
     */
    public double ceilingDb() {
        return ceilingDb;
    }

    /**
     * {@return how far ahead the limiter looks for peaks, in milliseconds, from 1 to 100;
     * {@value #DEFAULT_LOOKAHEAD_MILLIS} by default}
     *
     * <p>The gain falls smoothly over this time before a peak, so a longer look-ahead distorts less and
     * delays the output more.
     */
    public double lookaheadMillis() {
        return lookaheadMillis;
    }

    /**
     * {@return the time constant with which the gain recovers after a peak, in milliseconds, from 1 to
     * 10000; {@value #DEFAULT_RELEASE_MILLIS} by default}
     *
     * <p>A shorter release keeps the program louder and distorts low frequencies more.
     */
    public double releaseMillis() {
        return releaseMillis;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof TruePeakLimiterOptions that && Double.compare(ceilingDb, that.ceilingDb) == 0
                && Double.compare(lookaheadMillis, that.lookaheadMillis) == 0
                && Double.compare(releaseMillis, that.releaseMillis) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(ceilingDb, lookaheadMillis, releaseMillis);
    }

    @Override
    public String toString() {
        return "TruePeakLimiterOptions[ceilingDb=" + ceilingDb + ", lookaheadMillis=" + lookaheadMillis
                + ", releaseMillis=" + releaseMillis + "]";
    }

    /**
     * Builds {@link TruePeakLimiterOptions}.
     *
     * <p>Setters store their value; {@link #build()} checks the ranges. Instances are not
     * thread-safe.
     */
    public static final class Builder {
        private double ceilingDb;
        private double lookaheadMillis;
        private double releaseMillis;

        private Builder(TruePeakLimiterOptions options) {
            this.ceilingDb = options.ceilingDb;
            this.lookaheadMillis = options.lookaheadMillis;
            this.releaseMillis = options.releaseMillis;
        }

        /**
         * Sets the ceiling; see {@link TruePeakLimiterOptions#ceilingDb()}.
         *
         * @param ceilingDb the value
         * @return this builder
         */
        public Builder ceilingDb(double ceilingDb) {
            this.ceilingDb = ceilingDb;
            return this;
        }

        /**
         * Sets the look-ahead; see {@link TruePeakLimiterOptions#lookaheadMillis()}.
         *
         * @param millis the value
         * @return this builder
         */
        public Builder lookaheadMillis(double millis) {
            this.lookaheadMillis = millis;
            return this;
        }

        /**
         * Sets the release time; see {@link TruePeakLimiterOptions#releaseMillis()}.
         *
         * @param millis the value
         * @return this builder
         */
        public Builder releaseMillis(double millis) {
            this.releaseMillis = millis;
            return this;
        }

        /**
         * Returns options holding the settings of this builder.
         *
         * @return the options
         * @throws IllegalArgumentException if the ceiling is not in -60..0, the look-ahead not in 1..100
         * or the release not in 1..10000
         */
        public TruePeakLimiterOptions build() {
            check("ceiling", ceilingDb, -60, 0);
            check("look-ahead", lookaheadMillis, 1, 100);
            check("release", releaseMillis, 1, 10_000);
            return new TruePeakLimiterOptions(ceilingDb, lookaheadMillis, releaseMillis);
        }

        /**
         * Checks that a setting lies in a closed range.
         *
         * @param name the setting, for the message
         * @param value the setting's value
         * @param min the smallest value
         * @param max the largest value
         * @throws IllegalArgumentException if the value is out of range or NaN
         */
        private static void check(String name, double value, double min, double max) {
            if (!(value >= min && value <= max)) {
                throw new IllegalArgumentException(name + " " + value + " is not in " + min + ".." + max);
            }
        }
    }
}
