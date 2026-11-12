package com.github.auties00.smalt.video.qtrle;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.internal.check.Checks;
import com.github.auties00.smalt.track.Rational;

import java.util.Objects;

/**
 * Holds the settings of a {@link QtrleEncoder}. Instances are immutable and thread-safe; create them with
 * {@link #builder()} or a preset such as {@link #defaults()}.
 */
public final class QtrleEncoderOptions {
    /**
     * The options of {@link #defaults()}.
     */
    private static final QtrleEncoderOptions DEFAULTS = new Builder().build();

    private final int depth;

    private final int keyframeInterval;

    private final Rational frameRate;

    private final Rational timeBase;

    private final MediaLimits limits;

    private QtrleEncoderOptions(Builder builder) {
        this.depth = builder.depth;
        this.keyframeInterval = builder.keyframeInterval;
        this.frameRate = builder.frameRate;
        this.timeBase = builder.timeBase;
        this.limits = builder.limits;
    }

    /**
     * Returns the options for 24-bit RGB with a key frame every 12 frames at 30 frames per second, timestamps in
     * 1/90000 s, under the default limits.
     *
     * @return the default options
     */
    public static QtrleEncoderOptions defaults() {
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
     * {@return the depth of the coded pixels: 16 for 5-5-5 RGB (each component keeps its 5 high bits), 24 for RGB,
     * 32 for ARGB, or 40 for 8-bit gray (the luma of each pixel, rounded); 24 by default}
     *
     * <p>Depths 24 and 32 are lossless for 8-bit pictures; depth 24 drops alpha.
     */
    public int depth() {
        return depth;
    }

    /**
     * {@return the number of frames from one key frame to the next, at least 1; 12 by default}
     *
     * <p>A frame that is not a key frame codes only the rows and pixels that differ from the frame before it.
     */
    public int keyframeInterval() {
        return keyframeInterval;
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
        return other instanceof QtrleEncoderOptions that
                && depth == that.depth
                && keyframeInterval == that.keyframeInterval
                && frameRate.equals(that.frameRate)
                && timeBase.equals(that.timeBase)
                && limits.equals(that.limits);
    }

    @Override
    public int hashCode() {
        return Objects.hash(depth, keyframeInterval, frameRate, timeBase, limits);
    }

    @Override
    public String toString() {
        return "QtrleEncoderOptions[depth=" + depth + ", keyframeInterval=" + keyframeInterval + ", frameRate="
                + frameRate + ", timeBase=" + timeBase + ", limits=" + limits + "]";
    }

    /**
     * A builder of {@link QtrleEncoderOptions}.
     *
     * <p>Setters reject only {@code null}; {@link #build()} checks the ranges. Instances are not thread-safe.
     */
    public static final class Builder {
        private int depth = 24;

        private int keyframeInterval = 12;

        private Rational frameRate = new Rational(30, 1);

        private Rational timeBase = new Rational(1, 90_000);

        private MediaLimits limits = MediaLimits.defaults();

        private Builder() {
        }

        private Builder(QtrleEncoderOptions options) {
            this.depth = options.depth;
            this.keyframeInterval = options.keyframeInterval;
            this.frameRate = options.frameRate;
            this.timeBase = options.timeBase;
            this.limits = options.limits;
        }

        /**
         * Sets the depth; see {@link QtrleEncoderOptions#depth()}.
         *
         * @param depth the value
         * @return this builder
         */
        public Builder depth(int depth) {
            this.depth = depth;
            return this;
        }

        /**
         * Sets the key frame interval; see {@link QtrleEncoderOptions#keyframeInterval()}.
         *
         * @param keyframeInterval the value
         * @return this builder
         */
        public Builder keyframeInterval(int keyframeInterval) {
            this.keyframeInterval = keyframeInterval;
            return this;
        }

        /**
         * Sets the nominal frame rate; see {@link QtrleEncoderOptions#frameRate()}.
         *
         * @param frameRate the value
         * @return this builder
         */
        public Builder frameRate(Rational frameRate) {
            this.frameRate = Objects.requireNonNull(frameRate, "frameRate");
            return this;
        }

        /**
         * Sets the time base; see {@link QtrleEncoderOptions#timeBase()}.
         *
         * @param timeBase the value
         * @return this builder
         */
        public Builder timeBase(Rational timeBase) {
            this.timeBase = Objects.requireNonNull(timeBase, "timeBase");
            return this;
        }

        /**
         * Sets the limits; see {@link QtrleEncoderOptions#limits()}.
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
         * @throws IllegalArgumentException if the depth is not 16, 24, 32 or 40, the key frame interval is not
         * positive, the frame rate is not in [0.1, 1000] or a time base term is not positive
         */
        public QtrleEncoderOptions build() {
            if (depth != 16 && depth != 24 && depth != 32 && depth != 40) {
                throw new IllegalArgumentException("depth must be 16, 24, 32 or 40: " + depth);
            }
            Checks.positive("key frame interval", keyframeInterval);
            Checks.inRange("frame rate", frameRate.toDouble(), 0.1, 1000);
            Checks.positive("time base numerator", timeBase.numerator());
            Checks.positive("time base denominator", timeBase.denominator());
            return new QtrleEncoderOptions(this);
        }
    }
}
