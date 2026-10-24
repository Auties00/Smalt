package com.github.auties00.smalt.subtitle;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.container.DemuxerOptions;
import com.github.auties00.smalt.internal.check.Checks;
import com.github.auties00.smalt.track.Rational;

import java.nio.charset.Charset;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Holds the settings of a {@link SubtitleReader}: which track it reads, the markup of the cues it
 * returns, the text encoding and MicroDVD frame rate of subtitle files and the limits of the demuxer.
 * Instances are immutable and thread-safe; create them with {@link #builder()} or a preset such as
 * {@link #defaults()}.
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

    /**
     * The text encoding, or {@code null} to detect it.
     */
    private final Charset charset;

    /**
     * The MicroDVD frame rate, or {@code null} for the file's or the default.
     */
    private final Rational subtitleFrameRate;

    private SubtitleReaderOptions(int trackId, SubtitleMarkup markup, MediaLimits limits, Charset charset,
                                  Rational subtitleFrameRate) {
        this.trackId = trackId;
        this.markup = markup;
        this.limits = limits;
        this.charset = charset;
        this.subtitleFrameRate = subtitleFrameRate;
    }

    /**
     * Returns the default options: the first text subtitle track, in its own markup, the text encoding
     * detected, under {@link MediaLimits#defaults()}.
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

    /**
     * {@return the character encoding of subtitle files without a byte order mark, or empty to detect it;
     * empty by default}
     *
     * <p>The meaning is that of {@link DemuxerOptions#charset()}.
     */
    public Optional<Charset> charset() {
        return Optional.ofNullable(charset);
    }

    /**
     * {@return the frame rate of MicroDVD frame numbers, or empty for the rate the file declares or
     * 24000/1001; empty by default}
     *
     * <p>The meaning is that of {@link DemuxerOptions#subtitleFrameRate()}.
     */
    public Optional<Rational> subtitleFrameRate() {
        return Optional.ofNullable(subtitleFrameRate);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof SubtitleReaderOptions that && trackId == that.trackId && markup == that.markup
                && limits.equals(that.limits) && Objects.equals(charset, that.charset)
                && Objects.equals(subtitleFrameRate, that.subtitleFrameRate);
    }

    @Override
    public int hashCode() {
        return Objects.hash(trackId, markup, limits, charset, subtitleFrameRate);
    }

    @Override
    public String toString() {
        return "SubtitleReaderOptions[trackId=" + trackId() + ", markup=" + markup() + ", limits=" + limits
                + ", charset=" + charset() + ", subtitleFrameRate=" + subtitleFrameRate() + "]";
    }

    /**
     * Assembles {@link SubtitleReaderOptions} setting by setting.
     *
     * <p>Setters reject {@code null} and store their value unchecked; {@link #build()} checks the
     * track identifier and the frame rate. Instances are not thread-safe.
     */
    public static final class Builder {
        /**
         * The builder value of the first-track selection; outside the {@code int} range.
         */
        private static final long FIRST_TRACK = Long.MIN_VALUE;

        private long trackId = FIRST_TRACK;
        private SubtitleMarkup markup;
        private MediaLimits limits = MediaLimits.DEFAULT;
        private Charset charset;
        private Rational subtitleFrameRate;

        private Builder() {
        }

        private Builder(SubtitleReaderOptions options) {
            this.trackId = options.trackId < 0 ? FIRST_TRACK : options.trackId;
            this.markup = options.markup;
            this.limits = options.limits;
            this.charset = options.charset;
            this.subtitleFrameRate = options.subtitleFrameRate;
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
         * Sets the text encoding of subtitle files; see {@link SubtitleReaderOptions#charset()}.
         *
         * @param charset the value
         * @return this builder
         */
        public Builder charset(Charset charset) {
            this.charset = Objects.requireNonNull(charset, "charset");
            return this;
        }

        /**
         * Detects the text encoding of subtitle files; see {@link SubtitleReaderOptions#charset()}.
         *
         * @return this builder
         */
        public Builder automaticCharset() {
            this.charset = null;
            return this;
        }

        /**
         * Sets the MicroDVD frame rate; see {@link SubtitleReaderOptions#subtitleFrameRate()}.
         *
         * @param frameRate the value, in frames per second
         * @return this builder
         */
        public Builder subtitleFrameRate(Rational frameRate) {
            this.subtitleFrameRate = Objects.requireNonNull(frameRate, "frameRate");
            return this;
        }

        /**
         * Uses the MicroDVD frame rate the file declares, or 24000/1001; see
         * {@link SubtitleReaderOptions#subtitleFrameRate()}.
         *
         * @return this builder
         */
        public Builder automaticSubtitleFrameRate() {
            this.subtitleFrameRate = null;
            return this;
        }

        /**
         * Builds the options.
         *
         * @return the options
         * @throws IllegalArgumentException if the track identifier is negative or the frame rate is not
         * positive
         */
        public SubtitleReaderOptions build() {
            var id = trackId == FIRST_TRACK ? -1 : Checks.nonNegative("track identifier", (int) trackId);
            if (subtitleFrameRate != null && (subtitleFrameRate.numerator() <= 0
                    || subtitleFrameRate.denominator() <= 0)) {
                throw new IllegalArgumentException("the subtitle frame rate must be positive, not " + subtitleFrameRate);
            }
            return new SubtitleReaderOptions(id, markup, limits, charset, subtitleFrameRate);
        }
    }
}
