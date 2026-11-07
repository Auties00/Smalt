package com.github.auties00.smalt.video.theora;

import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.internal.check.Checks;
import com.github.auties00.smalt.track.Rational;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * The settings of a {@link TheoraEncoder}: the quality index, the key frame interval, the chroma format, the frame
 * rate and time base, the color description used to convert pictures of another layout, and the sample aspect
 * ratio.
 *
 * <p>Instances are immutable and thread-safe; build them with {@link #builder()} or {@link #toBuilder()}.
 */
public final class TheoraEncoderOptions {
    /**
     * The default key frame interval, in frames.
     */
    public static final int DEFAULT_KEYFRAME_INTERVAL = 64;

    private static final TheoraEncoderOptions DEFAULTS = new Builder().build();

    private final int quality;

    private final OptionalInt keyframeInterval;

    private final ChromaFormat chromaFormat;

    private final Rational frameRate;

    private final Rational timeBase;

    private final ColorInfo color;

    private final Rational sampleAspectRatio;

    private TheoraEncoderOptions(Builder builder) {
        this.quality = builder.quality;
        this.keyframeInterval = builder.keyframeInterval;
        this.chromaFormat = builder.chromaFormat;
        this.frameRate = builder.frameRate;
        this.timeBase = builder.timeBase;
        this.color = builder.color;
        this.sampleAspectRatio = builder.sampleAspectRatio;
    }

    /**
     * Returns quality index 48, a key frame interval of {@value #DEFAULT_KEYFRAME_INTERVAL} frames, 4:2:0 Y'CbCr, 30
     * frames per second with timestamps in 1/90000 s, BT.601 limited range conversion and no sample aspect ratio.
     *
     * @return the options
     */
    public static TheoraEncoderOptions defaults() {
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
     * {@return the quality index, from 0 (the coarsest quantizers) to 63 (the finest)}
     *
     * <p>It selects the quantizers of the VP3 tables of libtheora, which its quality setting selects too.
     */
    public int quality() {
        return quality;
    }

    /**
     * {@return the most frames from one key frame to the next, positive, or empty for key frames only at the start,
     * on request and at scene cuts; {@value #DEFAULT_KEYFRAME_INTERVAL} by default}
     *
     * <p>With an interval of {@code n} and no key frame added on request or at a scene cut, frame {@code k * n} is a
     * key frame for every {@code k}; an added key frame restarts the count. An interval of 1 makes every frame a key
     * frame. The identification header's granule shift is the number of bits of {@code n - 1}, and at least 6.
     * Without periodic key frames the granule shift is 31, the most the header holds, and a key frame still comes
     * {@link Integer#MAX_VALUE} frames after the last one, the longest distance a granule position counts.
     */
    public OptionalInt keyframeInterval() {
        return keyframeInterval;
    }

    /**
     * {@return the chroma format of the coded pictures: {@link ChromaFormat#YUV420}, {@link ChromaFormat#YUV422}
     * or {@link ChromaFormat#YUV444}}
     */
    public ChromaFormat chromaFormat() {
        return chromaFormat;
    }

    /**
     * {@return the frame rate the identification header declares and missing timestamps follow}
     */
    public Rational frameRate() {
        return frameRate;
    }

    /**
     * {@return the time base of the packets' timestamps}
     */
    public Rational timeBase() {
        return timeBase;
    }

    /**
     * {@return the color description that converts pictures of another layout}
     */
    public ColorInfo color() {
        return color;
    }

    /**
     * {@return the sample aspect ratio the identification header declares, or empty to leave it unknown}
     */
    public Optional<Rational> sampleAspectRatio() {
        return Optional.ofNullable(sampleAspectRatio);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof TheoraEncoderOptions that
                && quality == that.quality
                && keyframeInterval.equals(that.keyframeInterval)
                && chromaFormat == that.chromaFormat
                && frameRate.equals(that.frameRate)
                && timeBase.equals(that.timeBase)
                && color.equals(that.color)
                && Objects.equals(sampleAspectRatio, that.sampleAspectRatio);
    }

    @Override
    public int hashCode() {
        return Objects.hash(quality, keyframeInterval, chromaFormat, frameRate, timeBase, color, sampleAspectRatio);
    }

    @Override
    public String toString() {
        return "TheoraEncoderOptions[quality=" + quality + ", keyframeInterval=" + keyframeInterval
                + ", chromaFormat=" + chromaFormat + ", frameRate="
                + frameRate + ", timeBase=" + timeBase + ", color=" + color + ", sampleAspectRatio="
                + sampleAspectRatio + "]";
    }

    /**
     * Builds {@link TheoraEncoderOptions}.
     *
     * <p>Setters store their value; {@link #build()} checks the ranges. Instances are not thread-safe.
     */
    public static final class Builder {
        private int quality = 48;

        private OptionalInt keyframeInterval = OptionalInt.of(DEFAULT_KEYFRAME_INTERVAL);

        private ChromaFormat chromaFormat = ChromaFormat.YUV420;

        private Rational frameRate = new Rational(30, 1);

        private Rational timeBase = new Rational(1, 90_000);

        private ColorInfo color = ColorInfo.BT601_LIMITED;

        private Rational sampleAspectRatio;

        private Builder() {
        }

        private Builder(TheoraEncoderOptions options) {
            this.quality = options.quality;
            this.keyframeInterval = options.keyframeInterval;
            this.chromaFormat = options.chromaFormat;
            this.frameRate = options.frameRate;
            this.timeBase = options.timeBase;
            this.color = options.color;
            this.sampleAspectRatio = options.sampleAspectRatio;
        }

        /**
         * Sets the quality index; see {@link TheoraEncoderOptions#quality()}.
         *
         * @param quality the value
         * @return this builder
         */
        public Builder quality(int quality) {
            this.quality = quality;
            return this;
        }

        /**
         * Sets the key frame interval; see {@link TheoraEncoderOptions#keyframeInterval()}.
         *
         * @param frames the value
         * @return this builder
         */
        public Builder keyframeInterval(int frames) {
            this.keyframeInterval = OptionalInt.of(frames);
            return this;
        }

        /**
         * Codes key frames only at the start, on request and at scene cuts; see
         * {@link TheoraEncoderOptions#keyframeInterval()}.
         *
         * @return this builder
         */
        public Builder noPeriodicKeyframes() {
            this.keyframeInterval = OptionalInt.empty();
            return this;
        }

        /**
         * Sets the chroma format; see {@link TheoraEncoderOptions#chromaFormat()}.
         *
         * @param chromaFormat the value
         * @return this builder
         */
        public Builder chromaFormat(ChromaFormat chromaFormat) {
            this.chromaFormat = Objects.requireNonNull(chromaFormat, "chromaFormat");
            return this;
        }

        /**
         * Sets the frame rate; see {@link TheoraEncoderOptions#frameRate()}.
         *
         * @param frameRate the value
         * @return this builder
         */
        public Builder frameRate(Rational frameRate) {
            this.frameRate = Objects.requireNonNull(frameRate, "frameRate");
            return this;
        }

        /**
         * Sets the time base; see {@link TheoraEncoderOptions#timeBase()}.
         *
         * @param timeBase the value
         * @return this builder
         */
        public Builder timeBase(Rational timeBase) {
            this.timeBase = Objects.requireNonNull(timeBase, "timeBase");
            return this;
        }

        /**
         * Sets the conversion color description; see {@link TheoraEncoderOptions#color()}.
         *
         * @param color the value
         * @return this builder
         */
        public Builder color(ColorInfo color) {
            this.color = Objects.requireNonNull(color, "color");
            return this;
        }

        /**
         * Sets the sample aspect ratio; see {@link TheoraEncoderOptions#sampleAspectRatio()}.
         *
         * @param sampleAspectRatio the value
         * @return this builder
         */
        public Builder sampleAspectRatio(Rational sampleAspectRatio) {
            this.sampleAspectRatio = Objects.requireNonNull(sampleAspectRatio, "sampleAspectRatio");
            return this;
        }

        /**
         * Leaves the sample aspect ratio unknown, as {@link TheoraEncoderOptions#defaults()} does.
         *
         * @return this builder
         */
        public Builder noSampleAspectRatio() {
            this.sampleAspectRatio = null;
            return this;
        }

        /**
         * Returns the options holding the settings of this builder.
         *
         * @return the options
         * @throws IllegalArgumentException if the quality is not in [0, 63], the key frame interval is not positive,
         * the chroma format is not 4:2:0, 4:2:2
         * or 4:4:4, a frame rate or time base term is not positive or does not fit 32 bits, or a sample aspect
         * ratio term is not positive or does not fit 24 bits
         */
        public TheoraEncoderOptions build() {
            Checks.inRange("quality", quality, 0, 63);
            if (keyframeInterval.isPresent()) {
                Checks.positive("key frame interval", keyframeInterval.getAsInt());
            }
            if (chromaFormat != ChromaFormat.YUV420 && chromaFormat != ChromaFormat.YUV422
                    && chromaFormat != ChromaFormat.YUV444) {
                throw new IllegalArgumentException("Theora codes 4:2:0, 4:2:2 or 4:4:4 Y'CbCr, not " + chromaFormat);
            }
            Checks.positive("frame rate numerator", frameRate.numerator());
            Checks.positive("frame rate denominator", frameRate.denominator());
            if (frameRate.numerator() > 0xFFFFFFFFL || frameRate.denominator() > 0xFFFFFFFFL) {
                throw new IllegalArgumentException("Theora frame rate terms are 32-bit: " + frameRate);
            }
            Checks.positive("time base numerator", timeBase.numerator());
            Checks.positive("time base denominator", timeBase.denominator());
            if (sampleAspectRatio != null) {
                Checks.positive("sample aspect ratio numerator", sampleAspectRatio.numerator());
                Checks.positive("sample aspect ratio denominator", sampleAspectRatio.denominator());
                if (sampleAspectRatio.numerator() > 0xFFFFFF || sampleAspectRatio.denominator() > 0xFFFFFF) {
                    throw new IllegalArgumentException("Theora aspect ratio terms are 24-bit: "
                            + sampleAspectRatio);
                }
            }
            return new TheoraEncoderOptions(this);
        }
    }
}
