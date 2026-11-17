package com.github.auties00.smalt.image.png;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.internal.check.Checks;
import com.github.auties00.smalt.track.Rational;

import java.util.Objects;

/**
 * Holds the settings of a {@link PngVideoEncoder}. Instances are immutable and thread-safe; create them with
 * {@link #builder()} or a preset such as {@link #defaults()}.
 */
public final class PngVideoEncoderOptions {
    /**
     * The options of {@link #defaults()}.
     */
    private static final PngVideoEncoderOptions DEFAULTS = new Builder().build();

    private final PngEncoderOptions coding;

    private final Rational frameRate;

    private final Rational timeBase;

    private final MediaLimits limits;

    private PngVideoEncoderOptions(Builder builder) {
        this.coding = builder.coding;
        this.frameRate = builder.frameRate;
        this.timeBase = builder.timeBase;
        this.limits = builder.limits;
    }

    /**
     * Returns the {@link PngEncoderOptions#defaults()} coding at 30 frames per second, timestamps in 1/90000 s,
     * under the default limits.
     *
     * @return the default options
     */
    public static PngVideoEncoderOptions defaults() {
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
     * {@return the coding of each frame: compression level, filters, interlacing and the metadata every frame
     * carries; {@link PngEncoderOptions#defaults()} by default}
     */
    public PngEncoderOptions coding() {
        return coding;
    }

    /**
     * {@return the nominal frame rate, from 0.1 to 1000 frames per second, which gives the timing of frames without
     * one; 30 by default}
     */
    public Rational frameRate() {
        return frameRate;
    }

    /**
     * {@return the time base of the timestamps of accepted frames and produced packets; {@code 1/90000} by default}
     */
    public Rational timeBase() {
        return timeBase;
    }

    /**
     * {@return the limits the picture size is checked against; {@link MediaLimits#defaults()} by default}
     */
    public MediaLimits limits() {
        return limits;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof PngVideoEncoderOptions that
                && coding.equals(that.coding)
                && frameRate.equals(that.frameRate)
                && timeBase.equals(that.timeBase)
                && limits.equals(that.limits);
    }

    @Override
    public int hashCode() {
        return Objects.hash(coding, frameRate, timeBase, limits);
    }

    @Override
    public String toString() {
        return "PngVideoEncoderOptions[coding=" + coding + ", frameRate=" + frameRate + ", timeBase=" + timeBase
                + ", limits=" + limits + "]";
    }

    /**
     * A builder of {@link PngVideoEncoderOptions}.
     *
     * <p>Setters reject only {@code null}; {@link #build()} checks the ranges. Instances are not thread-safe.
     */
    public static final class Builder {
        private PngEncoderOptions coding = PngEncoderOptions.defaults();

        private Rational frameRate = new Rational(30, 1);

        private Rational timeBase = new Rational(1, 90_000);

        private MediaLimits limits = MediaLimits.defaults();

        private Builder() {
        }

        private Builder(PngVideoEncoderOptions options) {
            this.coding = options.coding;
            this.frameRate = options.frameRate;
            this.timeBase = options.timeBase;
            this.limits = options.limits;
        }

        /**
         * Sets the coding of each frame; see {@link PngVideoEncoderOptions#coding()}.
         *
         * @param coding the value
         * @return this builder
         */
        public Builder coding(PngEncoderOptions coding) {
            this.coding = Objects.requireNonNull(coding, "coding");
            return this;
        }

        /**
         * Sets the nominal frame rate; see {@link PngVideoEncoderOptions#frameRate()}.
         *
         * @param frameRate the value
         * @return this builder
         */
        public Builder frameRate(Rational frameRate) {
            this.frameRate = Objects.requireNonNull(frameRate, "frameRate");
            return this;
        }

        /**
         * Sets the time base; see {@link PngVideoEncoderOptions#timeBase()}.
         *
         * @param timeBase the value
         * @return this builder
         */
        public Builder timeBase(Rational timeBase) {
            this.timeBase = Objects.requireNonNull(timeBase, "timeBase");
            return this;
        }

        /**
         * Sets the limits; see {@link PngVideoEncoderOptions#limits()}.
         *
         * @param limits the value
         * @return this builder
         */
        public Builder limits(MediaLimits limits) {
            this.limits = Objects.requireNonNull(limits, "limits");
            return this;
        }

        /**
         * Returns the options holding the settings of this builder.
         *
         * @return the options
         * @throws IllegalArgumentException if the frame rate is not in [0.1, 1000] or a time base term is not positive
         */
        public PngVideoEncoderOptions build() {
            Checks.inRange("frame rate", frameRate.toDouble(), 0.1, 1000);
            Checks.positive("time base numerator", timeBase.numerator());
            Checks.positive("time base denominator", timeBase.denominator());
            return new PngVideoEncoderOptions(this);
        }
    }
}
