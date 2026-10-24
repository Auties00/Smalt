package com.github.auties00.smalt.container.raw;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.container.DemuxerOptions;
import com.github.auties00.smalt.track.Rational;

import java.util.Objects;
import java.util.Optional;

/**
 * Holds the settings of the demuxers of elementary video streams that time their pictures at a frame rate
 * ({@link AnnexBDemuxer} and {@link ObuDemuxer}). Instances are immutable and thread-safe; create them with
 * {@link #builder()} or a preset such as {@link #defaults()}.
 */
public final class ElementaryStreamOptions {
    /**
     * The frame rate assumed when neither the options nor the stream give one: 25 frames per second.
     */
    public static final Rational DEFAULT_FRAME_RATE = new Rational(25, 1);

    /**
     * The options of {@link #defaults()}.
     */
    private static final ElementaryStreamOptions DEFAULTS = new ElementaryStreamOptions(MediaLimits.DEFAULT, null);

    private final MediaLimits limits;

    private final Rational frameRate;

    private ElementaryStreamOptions(MediaLimits limits, Rational frameRate) {
        this.limits = limits;
        this.frameRate = frameRate;
    }

    /**
     * Returns demuxing under {@link MediaLimits#defaults()} at the frame rate the stream signals.
     *
     * @return the default options
     */
    public static ElementaryStreamOptions defaults() {
        return DEFAULTS;
    }

    /**
     * Returns demuxing with the limits of generic demuxer options at the frame rate the stream signals.
     *
     * @param options the generic demuxer options
     * @return the options
     */
    public static ElementaryStreamOptions of(DemuxerOptions options) {
        return builder().limits(options.limits()).build();
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
     * Returns the frame rate that times the pictures, in frames per second.
     *
     * <p>An elementary stream carries no timestamps: the demuxer gives each picture the duration of one frame at
     * this rate (half of it to a field coded alone). When empty (the default), the rate is the one the stream's
     * timing information signals (the H.264 or HEVC VUI, the AV1 sequence header), or
     * {@link #DEFAULT_FRAME_RATE} when the stream signals none.
     *
     * @return the frame rate, or empty to use the stream's
     */
    public Optional<Rational> frameRate() {
        return Optional.ofNullable(frameRate);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ElementaryStreamOptions that && limits.equals(that.limits)
                && Objects.equals(frameRate, that.frameRate);
    }

    @Override
    public int hashCode() {
        return Objects.hash(limits, frameRate);
    }

    @Override
    public String toString() {
        return "ElementaryStreamOptions[limits=" + limits + ", frameRate=" + frameRate + "]";
    }

    /**
     * Assembles {@link ElementaryStreamOptions} setting by setting.
     *
     * <p>Setters reject {@code null} and validate their argument at once. Instances are not thread-safe.
     */
    public static final class Builder {
        private MediaLimits limits;

        private Rational frameRate;

        private Builder(ElementaryStreamOptions options) {
            this.limits = options.limits;
            this.frameRate = options.frameRate;
        }

        /**
         * Sets the resource bounds; see {@link ElementaryStreamOptions#limits()}.
         *
         * @param limits the value
         * @return this builder
         */
        public Builder limits(MediaLimits limits) {
            this.limits = Objects.requireNonNull(limits, "limits");
            return this;
        }

        /**
         * Sets the frame rate; see {@link ElementaryStreamOptions#frameRate()}.
         *
         * @param frameRate the value, in frames per second
         * @return this builder
         * @throws IllegalArgumentException if the rate is not positive or its terms exceed {@code 2^30}
         */
        public Builder frameRate(Rational frameRate) {
            Objects.requireNonNull(frameRate, "frameRate");
            if (frameRate.numerator() <= 0 || frameRate.denominator() <= 0 || frameRate.numerator() > 1 << 30
                    || frameRate.denominator() > 1 << 30) {
                throw new IllegalArgumentException("invalid frame rate " + frameRate);
            }
            this.frameRate = frameRate;
            return this;
        }

        /**
         * Makes the demuxer use the frame rate the stream signals; see {@link ElementaryStreamOptions#frameRate()}.
         *
         * @return this builder
         */
        public Builder automaticFrameRate() {
            this.frameRate = null;
            return this;
        }

        /**
         * Builds the options.
         *
         * @return the options
         */
        public ElementaryStreamOptions build() {
            return new ElementaryStreamOptions(limits, frameRate);
        }
    }
}
