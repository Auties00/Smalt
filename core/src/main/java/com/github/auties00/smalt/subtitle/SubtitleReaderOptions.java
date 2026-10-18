package com.github.auties00.smalt.subtitle;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.internal.check.Checks;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Holds the settings of a {@link SubtitleReader}: which track it reads, the markup of the cues it
 * returns and the limits of the demuxer. Instances are immutable and thread-safe; create them with
 * {@link #builder()} or a preset such as {@link #defaults()}.
 */
public final class SubtitleReaderOptions {
    /**
     * The options of {@link #defaults()}.
     */
    private static final SubtitleReaderOptions DEFAULTS = new Builder().build();

    /**
     * The identifier of the track read, or {@code -1} for the first subtitle track.
     */
    private final int trackId;

    /**
     * The markup of the returned cues, or {@code null} for the track's.
     */
    private final SubtitleMarkup markup;

    private final MediaLimits limits;

    private SubtitleReaderOptions(int trackId, SubtitleMarkup markup, MediaLimits limits) {
        this.trackId = trackId;
        this.markup = markup;
        this.limits = limits;
    }

    /**
     * Returns the default options: the first text subtitle track, in its own markup, under
     * {@link MediaLimits#defaults()}.
     *
     * @return the options
     */
    public static SubtitleReaderOptions defaults() {
        return DEFAULTS;
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
     * {@return the identifier of the track read, zero or more, or empty for the first text subtitle
     * track; empty by default}
     */
    public OptionalInt trackId() {
        return trackId < 0 ? OptionalInt.empty() : OptionalInt.of(trackId);
    }

    /**
     * {@return the markup the cue text is converted to, or empty to keep the track's; empty by default}
     */
    public Optional<SubtitleMarkup> markup() {
        return Optional.ofNullable(markup);
    }

    /**
     * {@return the limits of the demuxer; {@link MediaLimits#defaults()} by default}
     */
    public MediaLimits limits() {
        return limits;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof SubtitleReaderOptions that && trackId == that.trackId && markup == that.markup
                && limits.equals(that.limits);
    }

    @Override
    public int hashCode() {
        return Objects.hash(trackId, markup, limits);
    }

    @Override
    public String toString() {
        return "SubtitleReaderOptions[trackId=" + trackId() + ", markup=" + markup() + ", limits=" + limits + "]";
    }

    /**
     * Assembles {@link SubtitleReaderOptions} setting by setting.
     *
     * <p>Setters reject {@code null} and store their value unchecked; {@link #build()} checks the
     * track identifier. Instances are not thread-safe.
     */
    public static final class Builder {
        /**
         * The builder value of the first-track selection; outside the {@code int} range.
         */
        private static final long FIRST_TRACK = Long.MIN_VALUE;

        private long trackId = FIRST_TRACK;
        private SubtitleMarkup markup;
        private MediaLimits limits = MediaLimits.DEFAULT;

        private Builder() {
        }

        private Builder(SubtitleReaderOptions options) {
            this.trackId = options.trackId < 0 ? FIRST_TRACK : options.trackId;
            this.markup = options.markup;
            this.limits = options.limits;
        }

        /**
         * Selects one track; see {@link SubtitleReaderOptions#trackId()}.
         *
         * @param trackId the value
         * @return this builder
         */
        public Builder trackId(int trackId) {
            this.trackId = trackId;
            return this;
        }

        /**
         * Selects the first text subtitle track; see {@link SubtitleReaderOptions#trackId()}.
         *
         * @return this builder
         */
        public Builder firstTrack() {
            this.trackId = FIRST_TRACK;
            return this;
        }

        /**
         * Sets the markup the cue text is converted to; see {@link SubtitleReaderOptions#markup()}.
         *
         * @param markup the value
         * @return this builder
         */
        public Builder markup(SubtitleMarkup markup) {
            this.markup = Objects.requireNonNull(markup, "markup");
            return this;
        }

        /**
         * Keeps the markup of the track; see {@link SubtitleReaderOptions#markup()}.
         *
         * @return this builder
         */
        public Builder sourceMarkup() {
            this.markup = null;
            return this;
        }

        /**
         * Sets the demuxer limits; see {@link SubtitleReaderOptions#limits()}.
         *
         * @param limits the value
         * @return this builder
         */
        public Builder limits(MediaLimits limits) {
            this.limits = Objects.requireNonNull(limits, "limits");
            return this;
        }

        /**
         * Builds the options.
         *
         * @return the options
         * @throws IllegalArgumentException if the track identifier is negative
         */
        public SubtitleReaderOptions build() {
            var id = trackId == FIRST_TRACK ? -1 : Checks.nonNegative("track identifier", (int) trackId);
            return new SubtitleReaderOptions(id, markup, limits);
        }
    }
}
