package com.github.auties00.smalt.audio.loudness;

import com.github.auties00.smalt.internal.check.Checks;

import java.util.Objects;
import java.util.OptionalDouble;

/**
 * Holds the settings of a loudness normalization, the integrated loudness to reach and the true
 * peak not to exceed, and computes its gain with {@link #gainDb(LoudnessStats)}. Instances are
 * immutable and thread-safe; create them with {@link #builder()} or a preset such as
 * {@link #defaults()}.
 *
 * @apiNote The ceiling applies to the true peak of the samples the gain is applied to; a lossy
 * encoder that follows can add a fraction of a decibel of overshoot, so leave a margin (the
 * presets use -1 dBTP or lower) when the output is encoded.
 */
public final class LoudnessNormalization {
    private static final LoudnessNormalization EBU_R128 = new LoudnessNormalization(-23, -1, Double.POSITIVE_INFINITY);

    private final double targetLufs;
    private final double truePeakCeilingDb;
    private final double maxGainDb;

    private LoudnessNormalization(double targetLufs, double truePeakCeilingDb, double maxGainDb) {
        this.targetLufs = targetLufs;
        this.truePeakCeilingDb = truePeakCeilingDb;
        this.maxGainDb = maxGainDb;
    }

    /**
     * Returns the default normalization, {@link #ebuR128()}.
     *
     * @return the default normalization
     */
    public static LoudnessNormalization defaults() {
        return EBU_R128;
    }

    /**
     * Returns the broadcast normalization of EBU R128: -23 LUFS, true peak at most -1 dBTP.
     *
     * @return the normalization
     */
    public static LoudnessNormalization ebuR128() {
        return EBU_R128;
    }

    /**
     * Returns the normalization of the US broadcast recommendation ATSC A/85: -24 LUFS, true peak
     * at most -2 dBTP.
     *
     * @return the normalization
     */
    public static LoudnessNormalization atscA85() {
        return of(-24, -2);
    }

    /**
     * Returns the normalization common to music streaming services: -14 LUFS, true peak at most
     * -1 dBTP.
     *
     * @return the normalization
     */
    public static LoudnessNormalization forStreaming() {
        return of(-14, -1);
    }

    /**
     * Returns the normalization common for podcasts and spoken word: -16 LUFS, true peak at most
     * -1.5 dBTP.
     *
     * @return the normalization
     */
    public static LoudnessNormalization forPodcast() {
        return of(-16, -1.5);
    }

    /**
     * Returns a normalization to a target with a true-peak ceiling and no gain limit.
     *
     * @param targetLufs the integrated loudness to reach, in LUFS, finite and at most 0
     * @param truePeakCeilingDb the highest true peak, in dBTP, finite
     * @return the normalization
     * @throws IllegalArgumentException if a value is out of range
     */
    public static LoudnessNormalization of(double targetLufs, double truePeakCeilingDb) {
        return builder().targetLufs(targetLufs).truePeakCeilingDb(truePeakCeilingDb).build();
    }

    /**
     * Returns a builder initialized with {@link #defaults()}.
     *
     * @return the builder
     */
    public static Builder builder() {
        return new Builder(EBU_R128);
    }

    /**
     * Returns a builder initialized with this normalization.
     *
     * @return the builder
     */
    public Builder toBuilder() {
        return new Builder(this);
    }

    /**
     * {@return the integrated loudness to reach, in LUFS, finite and at most 0; -23 by default}
     */
    public double targetLufs() {
        return targetLufs;
    }

    /**
     * {@return the highest true peak allowed after the gain, in dBTP, finite, or empty when peaks
     * are not limited; -1 by default}
     */
    public OptionalDouble truePeakCeilingDb() {
        return Double.isInfinite(truePeakCeilingDb) ? OptionalDouble.empty() : OptionalDouble.of(truePeakCeilingDb);
    }

    /**
     * {@return the largest gain applied, in decibels, finite and at least 0, or empty when the gain
     * is not limited; empty by default}
     */
    public OptionalDouble maxGainDb() {
        return Double.isInfinite(maxGainDb) ? OptionalDouble.empty() : OptionalDouble.of(maxGainDb);
    }

    /**
     * Returns the gain that normalizes a measured program.
     *
     * <p>The normalization is linear: one gain for the whole program. The gain is the target
     * minus the integrated loudness; when the true peak (the sample peak if the true peak was not
     * measured) raised by that gain exceeds the ceiling, the gain is lowered to put the peak on the
     * ceiling; the result is then capped by {@link #maxGainDb()}. A program quieter than the target
     * and with loud peaks therefore ends below the target rather than being compressed. A program
     * whose integrated loudness is not finite (silence, with no block above the absolute gate) gets
     * 0.
     *
     * @param stats the measurements of the program
     * @return the gain in decibels, negative to attenuate
     */
    public double gainDb(LoudnessStats stats) {
        Objects.requireNonNull(stats, "stats");
        if (!Double.isFinite(stats.integrated())) {
            return 0;
        }
        var gain = targetLufs - stats.integrated();
        var peak = Double.isNaN(stats.truePeak()) ? stats.samplePeak() : stats.truePeak();
        if (Double.isFinite(truePeakCeilingDb) && peak > 0) {
            var peakDb = 20 * StrictMath.log10(peak);
            if (peakDb + gain > truePeakCeilingDb) {
                gain = truePeakCeilingDb - peakDb;
            }
        }
        return Math.min(gain, maxGainDb);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof LoudnessNormalization that && Double.compare(targetLufs, that.targetLufs) == 0
                && Double.compare(truePeakCeilingDb, that.truePeakCeilingDb) == 0
                && Double.compare(maxGainDb, that.maxGainDb) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(targetLufs, truePeakCeilingDb, maxGainDb);
    }

    @Override
    public String toString() {
        return "LoudnessNormalization[targetLufs=" + targetLufs + ", truePeakCeilingDb=" + truePeakCeilingDb()
                + ", maxGainDb=" + maxGainDb() + "]";
    }

    /**
     * Builds {@link LoudnessNormalization}s.
     *
     * <p>Setters store their value; {@link #build()} checks the ranges. Instances are not
     * thread-safe.
     */
    public static final class Builder {
        private double targetLufs;
        private OptionalDouble truePeakCeilingDb;
        private OptionalDouble maxGainDb;

        private Builder(LoudnessNormalization normalization) {
            this.targetLufs = normalization.targetLufs;
            this.truePeakCeilingDb = normalization.truePeakCeilingDb();
            this.maxGainDb = normalization.maxGainDb();
        }

        /**
         * Sets the integrated loudness to reach; see {@link LoudnessNormalization#targetLufs()}.
         *
         * @param targetLufs the value
         * @return this builder
         */
        public Builder targetLufs(double targetLufs) {
            this.targetLufs = targetLufs;
            return this;
        }

        /**
         * Sets the true-peak ceiling; see {@link LoudnessNormalization#truePeakCeilingDb()}.
         *
         * @param truePeakCeilingDb the value
         * @return this builder
         */
        public Builder truePeakCeilingDb(double truePeakCeilingDb) {
            this.truePeakCeilingDb = OptionalDouble.of(truePeakCeilingDb);
            return this;
        }

        /**
         * Removes the true-peak ceiling; see {@link LoudnessNormalization#truePeakCeilingDb()}.
         *
         * @return this builder
         */
        public Builder noTruePeakCeiling() {
            this.truePeakCeilingDb = OptionalDouble.empty();
            return this;
        }

        /**
         * Sets the largest gain applied; see {@link LoudnessNormalization#maxGainDb()}.
         *
         * @param maxGainDb the value
         * @return this builder
         */
        public Builder maxGainDb(double maxGainDb) {
            this.maxGainDb = OptionalDouble.of(maxGainDb);
            return this;
        }

        /**
         * Removes the gain limit; see {@link LoudnessNormalization#maxGainDb()}.
         *
         * @return this builder
         */
        public Builder noMaxGain() {
            this.maxGainDb = OptionalDouble.empty();
            return this;
        }

        /**
         * Returns a normalization holding the settings of this builder.
         *
         * @return the normalization
         * @throws IllegalArgumentException if the target is not finite or above 0, the ceiling is not
         * finite, or the gain limit is not finite or negative
         */
        public LoudnessNormalization build() {
            finite("target loudness", Checks.inRange("target loudness", targetLufs, Double.NEGATIVE_INFINITY, 0.0));
            var ceiling = truePeakCeilingDb.isPresent()
                    ? finite("true-peak ceiling", truePeakCeilingDb.getAsDouble()) : Double.POSITIVE_INFINITY;
            var limit = maxGainDb.isPresent()
                    ? finite("gain limit", Checks.nonNegative("gain limit", maxGainDb.getAsDouble()))
                    : Double.POSITIVE_INFINITY;
            return new LoudnessNormalization(targetLufs, ceiling, limit);
        }

        /**
         * Checks that a setting is finite, in the message format of {@link Checks}.
         *
         * @param name the setting, for the message
         * @param value the value to check
         * @return {@code value}
         * @throws IllegalArgumentException if {@code value} is NaN or infinite
         */
        private static double finite(String name, double value) {
            if (!Double.isFinite(value)) {
                throw new IllegalArgumentException(name + " " + value + " is not finite");
            }
            return value;
        }
    }
}
