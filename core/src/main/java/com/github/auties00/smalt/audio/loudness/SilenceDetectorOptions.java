package com.github.auties00.smalt.audio.loudness;

import com.github.auties00.smalt.internal.check.Checks;

import java.util.Objects;

/**
 * Holds the settings of a {@link SilenceDetector}. Instances are immutable and thread-safe; create
 * them with {@link #builder()} or a preset such as {@link #defaults()}.
 */
public final class SilenceDetectorOptions {
    private static final SilenceDetectorOptions DEFAULTS = new SilenceDetectorOptions(-60, 2_000_000);

    private final double thresholdDb;
    private final long minDurationMicros;

    private SilenceDetectorOptions(double thresholdDb, long minDurationMicros) {
        this.thresholdDb = thresholdDb;
        this.minDurationMicros = minDurationMicros;
    }

    /**
     * Returns silences of samples below -60 dBFS lasting at least two seconds, the defaults of the
     * common {@code silencedetect} tools.
     *
     * @return the default options
     */
    public static SilenceDetectorOptions defaults() {
        return DEFAULTS;
    }

    /**
     * Returns options with a threshold and a shortest duration.
     *
     * @param thresholdDb the level below which a sample is silent, in dBFS, finite and at most 0
     * @param minDurationMicros the shortest silence reported, in microseconds, positive
     * @return the options
     * @throws IllegalArgumentException if a value is out of range
     */
    public static SilenceDetectorOptions of(double thresholdDb, long minDurationMicros) {
        return builder().thresholdDb(thresholdDb).minDurationMicros(minDurationMicros).build();
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
     * {@return the level below which a sample is silent, in dBFS, finite and at most 0; -60 by
     * default}
     *
     * <p>A sample frame is silent when the magnitude of every channel is below it.
     */
    public double thresholdDb() {
        return thresholdDb;
    }

    /**
     * {@return the shortest run of silent samples reported as silence, in microseconds, positive;
     * 2000000 by default}
     */
    public long minDurationMicros() {
        return minDurationMicros;
    }

    /**
     * Returns the threshold as a linear magnitude.
     *
     * @return the magnitude, 1.0 being full scale
     */
    double thresholdAmplitude() {
        return Math.pow(10, thresholdDb / 20);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof SilenceDetectorOptions that && Double.compare(thresholdDb, that.thresholdDb) == 0
                && minDurationMicros == that.minDurationMicros;
    }

    @Override
    public int hashCode() {
        return Objects.hash(thresholdDb, minDurationMicros);
    }

    @Override
    public String toString() {
        return "SilenceDetectorOptions[thresholdDb=" + thresholdDb + ", minDurationMicros=" + minDurationMicros + "]";
    }

    /**
     * Builds {@link SilenceDetectorOptions}.
     *
     * <p>Setters store their value; {@link #build()} checks the ranges. Instances are not
     * thread-safe.
     */
    public static final class Builder {
        private double thresholdDb;
        private long minDurationMicros;

        private Builder(SilenceDetectorOptions options) {
            this.thresholdDb = options.thresholdDb;
            this.minDurationMicros = options.minDurationMicros;
        }

        /**
         * Sets the silence threshold; see {@link SilenceDetectorOptions#thresholdDb()}.
         *
         * @param thresholdDb the value
         * @return this builder
         */
        public Builder thresholdDb(double thresholdDb) {
            this.thresholdDb = thresholdDb;
            return this;
        }

        /**
         * Sets the shortest silence reported; see {@link SilenceDetectorOptions#minDurationMicros()}.
         *
         * @param minDurationMicros the value
         * @return this builder
         */
        public Builder minDurationMicros(long minDurationMicros) {
            this.minDurationMicros = minDurationMicros;
            return this;
        }

        /**
         * Returns options holding the settings of this builder.
         *
         * @return the options
         * @throws IllegalArgumentException if the threshold is not finite or above 0, or the shortest
         * duration is not positive
         */
        public SilenceDetectorOptions build() {
            if (Checks.inRange("silence threshold", thresholdDb, Double.NEGATIVE_INFINITY, 0.0) == Double.NEGATIVE_INFINITY) {
                throw new IllegalArgumentException("silence threshold " + thresholdDb + " is not finite");
            }
            Checks.positive("minimum silence duration", minDurationMicros);
            return new SilenceDetectorOptions(thresholdDb, minDurationMicros);
        }
    }
}
