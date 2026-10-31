package com.github.auties00.smalt.transcode;

import com.github.auties00.smalt.audio.loudness.SilenceDetector;
import com.github.auties00.smalt.audio.loudness.SilenceDetectorOptions;
import com.github.auties00.smalt.internal.check.Checks;

import java.util.Objects;
import java.util.OptionalLong;

/**
 * Holds the settings of the silence trimming of a transcode. Instances are immutable and thread-safe; create them
 * with {@link #builder()} or a preset such as {@link #defaults()}.
 *
 * <p>Before the transcode starts, a first pass decodes the first selected audio stream over the kept range and
 * finds its silences with a {@link SilenceDetector}. A silence at the start of the range moves the start of the range
 * to its end, and a silence at the end of the range moves the end of the range to its start, for every stream, so
 * video and subtitles stay in step with the audio. A silence inside the range longer than {@link #maxPauseMicros()}
 * is shortened to that length by removing its middle, which only an output of audio streams alone supports.
 */
public final class SilenceTrim {
    private static final SilenceTrim DEFAULTS = new Builder().build();

    private final boolean leading;
    private final boolean trailing;

    /**
     * The longest pause kept in microseconds, or {@code -1} to keep every pause.
     */
    private final long maxPauseMicros;

    private final SilenceDetectorOptions detection;

    private SilenceTrim(Builder builder) {
        this.leading = builder.leading;
        this.trailing = builder.trailing;
        this.maxPauseMicros = builder.maxPauseMicros;
        this.detection = builder.detection;
    }

    /**
     * Returns trimming of the silence at the start and at the end, keeping the pauses inside.
     *
     * @return the options
     */
    public static SilenceTrim defaults() {
        return DEFAULTS;
    }

    /**
     * Returns trimming of the silence at the start and at the end, and shortening of every longer pause inside.
     *
     * @param maxPauseMicros the longest pause kept, in microseconds, positive
     * @return the options
     * @throws IllegalArgumentException if the length is not positive
     */
    public static SilenceTrim shorteningPauses(long maxPauseMicros) {
        return builder().maxPause(maxPauseMicros).build();
    }

    /**
     * Returns a builder initialized with {@link #defaults()}.
     *
     * @return the builder
     */
    public static Builder builder() {
        return new Builder();
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
     * {@return whether a silence at the start of the kept range is removed; {@code true} by default}
     */
    public boolean leading() {
        return leading;
    }

    /**
     * {@return whether a silence at the end of the kept range is removed; {@code true} by default}
     */
    public boolean trailing() {
        return trailing;
    }

    /**
     * {@return the longest silence kept inside the range, in microseconds, or empty to keep every pause; empty by
     * default}
     */
    public OptionalLong maxPauseMicros() {
        return maxPauseMicros < 0 ? OptionalLong.empty() : OptionalLong.of(maxPauseMicros);
    }

    /**
     * {@return what counts as silence: samples below -60 dBFS for at least 50 ms by default}
     */
    public SilenceDetectorOptions detection() {
        return detection;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof SilenceTrim that && leading == that.leading && trailing == that.trailing
                && maxPauseMicros == that.maxPauseMicros && detection.equals(that.detection);
    }

    @Override
    public int hashCode() {
        return Objects.hash(leading, trailing, maxPauseMicros, detection);
    }

    @Override
    public String toString() {
        return "SilenceTrim[leading=" + leading + ", trailing=" + trailing + ", maxPauseMicros="
                + maxPauseMicros() + ", detection=" + detection + "]";
    }

    /**
     * Assembles {@link SilenceTrim} setting by setting.
     *
     * <p>Setters check their values. Instances are not thread-safe.
     */
    public static final class Builder {
        private boolean leading = true;
        private boolean trailing = true;
        private long maxPauseMicros = -1;
        private SilenceDetectorOptions detection = SilenceDetectorOptions.of(-60, 50_000);

        private Builder() {
        }

        private Builder(SilenceTrim options) {
            this.leading = options.leading;
            this.trailing = options.trailing;
            this.maxPauseMicros = options.maxPauseMicros;
            this.detection = options.detection;
        }

        /**
         * Sets whether the leading silence is removed; see {@link SilenceTrim#leading()}.
         *
         * @param leading the value
         * @return this builder
         */
        public Builder leading(boolean leading) {
            this.leading = leading;
            return this;
        }

        /**
         * Sets whether the trailing silence is removed; see {@link SilenceTrim#trailing()}.
         *
         * @param trailing the value
         * @return this builder
         */
        public Builder trailing(boolean trailing) {
            this.trailing = trailing;
            return this;
        }

        /**
         * Sets the longest pause kept; see {@link SilenceTrim#maxPauseMicros()}.
         *
         * @param maxPauseMicros the value, positive
         * @return this builder
         * @throws IllegalArgumentException if the length is not positive
         */
        public Builder maxPause(long maxPauseMicros) {
            this.maxPauseMicros = Checks.positive("longest pause", maxPauseMicros);
            return this;
        }

        /**
         * Keeps every pause; see {@link SilenceTrim#maxPauseMicros()}.
         *
         * @return this builder
         */
        public Builder keepPauses() {
            this.maxPauseMicros = -1;
            return this;
        }

        /**
         * Sets what counts as silence; see {@link SilenceTrim#detection()}.
         *
         * @param detection the value
         * @return this builder
         */
        public Builder detection(SilenceDetectorOptions detection) {
            this.detection = Objects.requireNonNull(detection, "detection");
            return this;
        }

        /**
         * Builds the options.
         *
         * @return the options
         */
        public SilenceTrim build() {
            return new SilenceTrim(this);
        }
    }
}
