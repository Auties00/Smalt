package com.github.auties00.smalt.container;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.track.Rational;

import java.nio.charset.Charset;
import java.util.Objects;
import java.util.Optional;

/**
 * Holds the settings of a {@link Demuxer}. Instances are immutable and thread-safe; create them with
 * {@link #builder()} or a preset such as {@link #defaults()}.
 */
public final class DemuxerOptions {
    /**
     * The options of {@link #defaults()}.
     */
    private static final DemuxerOptions DEFAULTS = new DemuxerOptions(MediaLimits.DEFAULT, null, null);

    private final MediaLimits limits;

    /**
     * The text encoding, or {@code null} to detect it.
     */
    private final Charset charset;

    /**
     * The MicroDVD frame rate, or {@code null} for the file's or the default.
     */
    private final Rational subtitleFrameRate;

    private DemuxerOptions(MediaLimits limits, Charset charset, Rational subtitleFrameRate) {
        this.limits = limits;
        this.charset = charset;
        this.subtitleFrameRate = subtitleFrameRate;
    }

    /**
     * Returns demuxing under {@link MediaLimits#defaults()}, with detected text encodings.
     *
     * @return the default options
     */
    public static DemuxerOptions defaults() {
        return DEFAULTS;
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
     * {@return the resource bounds the demuxer enforces; {@link MediaLimits#defaults()} by default}
     */
    public MediaLimits limits() {
        return limits;
    }

    /**
     * {@return the character encoding of text subtitle files that do not start with a byte order mark, or
     * empty to detect it; empty by default}
     *
     * <p>Detection recognizes UTF-8, UTF-16 without a byte order mark, Shift_JIS, EUC-JP, EUC-KR, GBK, Big5,
     * Windows-1251 and KOI8-R, and reads anything else as Windows-1252. A byte order mark always wins, and
     * so does the encoding a TTML document's XML declaration or a SAMI file's {@code charset} names.
     */
    public Optional<Charset> charset() {
        return Optional.ofNullable(charset);
    }

    /**
     * {@return the frame rate MicroDVD frame numbers count in, or empty for the rate the file declares or,
     * when it declares none, 24000/1001; empty by default}
     *
     * <p>A MicroDVD file declares its rate in a first cue at frame 1 whose text is the rate; this setting,
     * when present, takes precedence over it.
     */
    public Optional<Rational> subtitleFrameRate() {
        return Optional.ofNullable(subtitleFrameRate);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof DemuxerOptions that && limits.equals(that.limits)
                && Objects.equals(charset, that.charset) && Objects.equals(subtitleFrameRate, that.subtitleFrameRate);
    }

    @Override
    public int hashCode() {
        return Objects.hash(limits, charset, subtitleFrameRate);
    }

    @Override
    public String toString() {
        return "DemuxerOptions[limits=" + limits + ", charset=" + charset() + ", subtitleFrameRate="
                + subtitleFrameRate() + "]";
    }

    /**
     * Assembles {@link DemuxerOptions} setting by setting.
     *
     * <p>Setters reject {@code null}; {@link #build()} checks the frame rate. Instances are not thread-safe.
     */
    public static final class Builder {
        private MediaLimits limits;
        private Charset charset;
        private Rational subtitleFrameRate;

        private Builder(DemuxerOptions options) {
            this.limits = options.limits;
            this.charset = options.charset;
            this.subtitleFrameRate = options.subtitleFrameRate;
        }

        /**
         * Sets the resource bounds; see {@link DemuxerOptions#limits()}.
         *
         * @param limits the value
         * @return this builder
         */
        public Builder limits(MediaLimits limits) {
            this.limits = Objects.requireNonNull(limits, "limits");
            return this;
        }

        /**
         * Sets the text encoding of subtitle files; see {@link DemuxerOptions#charset()}.
         *
         * @param charset the value
         * @return this builder
         */
        public Builder charset(Charset charset) {
            this.charset = Objects.requireNonNull(charset, "charset");
            return this;
        }

        /**
         * Detects the text encoding of subtitle files; see {@link DemuxerOptions#charset()}.
         *
         * @return this builder
         */
        public Builder automaticCharset() {
            this.charset = null;
            return this;
        }

        /**
         * Sets the MicroDVD frame rate; see {@link DemuxerOptions#subtitleFrameRate()}.
         *
         * @param frameRate the value, in frames per second
         * @return this builder
         */
        public Builder subtitleFrameRate(Rational frameRate) {
            this.subtitleFrameRate = Objects.requireNonNull(frameRate, "frameRate");
            return this;
        }

        /**
         * Uses the frame rate a MicroDVD file declares, or 24000/1001; see
         * {@link DemuxerOptions#subtitleFrameRate()}.
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
         * @throws IllegalArgumentException if the subtitle frame rate is not positive
         */
        public DemuxerOptions build() {
            if (subtitleFrameRate != null && (subtitleFrameRate.numerator() <= 0 || subtitleFrameRate.denominator() <= 0)) {
                throw new IllegalArgumentException("the subtitle frame rate must be positive, not " + subtitleFrameRate);
            }
            return new DemuxerOptions(limits, charset, subtitleFrameRate);
        }
    }
}
