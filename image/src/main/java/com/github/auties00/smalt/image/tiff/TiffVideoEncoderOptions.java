package com.github.auties00.smalt.image.tiff;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.internal.check.Checks;
import com.github.auties00.smalt.track.Rational;

import java.util.Objects;

/**
 * Holds the settings of a {@link TiffVideoEncoder}. Instances are immutable and thread-safe; create them with
 * {@link #builder()} or a preset such as {@link #defaults()}.
 */
public final class TiffVideoEncoderOptions {
    /**
     * The options of {@link #defaults()}.
     */
    private static final TiffVideoEncoderOptions DEFAULTS = new Builder().build();

    private final TiffEncoderOptions coding;

    private final Rational frameRate;

    private final Rational timeBase;

    private final MediaLimits limits;

    private TiffVideoEncoderOptions(Builder builder) {
        this.coding = builder.coding;
        this.frameRate = builder.frameRate;
        this.timeBase = builder.timeBase;
        this.limits = builder.limits;
    }

    /**
     * Returns the {@link TiffEncoderOptions#defaults()} coding at 30 frames per second, timestamps in 1/90000 s,
     * under the default limits.
     *
     * @return the default options
     */
    public static TiffVideoEncoderOptions defaults() {
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
     * {@return the coding of each frame: compression, predictor, strips or tiles and the metadata every frame
     * carries; {@link TiffEncoderOptions#defaults()} by default}
     */
    public TiffEncoderOptions coding() {
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
        return other instanceof TiffVideoEncoderOptions that
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
        return "TiffVideoEncoderOptions[coding=" + coding + ", frameRate=" + frameRate + ", timeBase=" + timeBase
                + ", limits=" + limits + "]";
    }

    /**
     * A builder of {@link TiffVideoEncoderOptions}.
     *
     * <p>Setters reject only {@code null}; {@link #build()} checks the ranges. Instances are not thread-safe.
     */
    public static final class Builder {
        private TiffEncoderOptions coding = TiffEncoderOptions.defaults();

        private Rational frameRate = new Rational(30, 1);

        private Rational timeBase = new Rational(1, 90_000);

        private MediaLimits limits = MediaLimits.defaults();

        private Builder() {
        }

        private Builder(TiffVideoEncoderOptions options) {
            this.coding = options.coding;
            this.frameRate = options.frameRate;
            this.timeBase = options.timeBase;
            this.limits = options.limits;
        }

        /**
         * Sets the coding of each frame; see {@link TiffVideoEncoderOptions#coding()}.
         *
         * @param coding the value
         * @return this builder
         */
        public Builder coding(TiffEncoderOptions coding) {
            this.coding = Objects.requireNonNull(coding, "coding");
            return this;
        }

        /**
         * Sets the nominal frame rate; see {@link TiffVideoEncoderOptions#frameRate()}.
         *
         * @param frameRate the value
         * @return this builder
         */
        public Builder frameRate(Rational frameRate) {
            this.frameRate = Objects.requireNonNull(frameRate, "frameRate");
            return this;
        }

        /**
         * Sets the time base; see {@link TiffVideoEncoderOptions#timeBase()}.
         *
         * @param timeBase the value
         * @return this builder
         */
        public Builder timeBase(Rational timeBase) {
            this.timeBase = Objects.requireNonNull(timeBase, "timeBase");
            return this;
        }

        /**
         * Sets the limits; see {@link TiffVideoEncoderOptions#limits()}.
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
        public TiffVideoEncoderOptions build() {
            Checks.inRange("frame rate", frameRate.toDouble(), 0.1, 1000);
            Checks.positive("time base numerator", timeBase.numerator());
            Checks.positive("time base denominator", timeBase.denominator());
            return new TiffVideoEncoderOptions(this);
        }
    }
}
