package com.github.auties00.smalt.video;

import com.github.auties00.smalt.internal.check.Checks;
import com.github.auties00.smalt.track.Rational;

import java.util.Objects;
import java.util.OptionalInt;

/**
 * Holds the codec-independent settings of a video encoder created by {@link VideoEncoders}.
 * Instances are immutable and thread-safe; create them with {@link #builder()} or a preset such as
 * {@link #of(int, int, Rational, long)}.
 *
 * @apiNote There is no {@code defaults()}, because a picture size has no sensible default. These
 * options carry only what every video codec understands; a codec maps them onto its own
 * configuration and reports the time base its frames are stamped in through
 * {@link VideoEncoder#timeBase()}, and a caller that needs a codec's other parameters constructs that
 * codec's encoder directly.
 */
public final class VideoEncoderOptions {
    private final int width;
    private final int height;
    private final Rational frameRate;
    private final long bitrate;
    private final boolean realtime;
    private final int threads;
    private final OptionalInt keyframeInterval;
    private final boolean periodicKeyframes;

    private VideoEncoderOptions(Builder builder) {
        this.width = builder.width;
        this.height = builder.height;
        this.frameRate = builder.frameRate;
        this.bitrate = builder.bitrate;
        this.realtime = builder.realtime;
        this.threads = builder.threads;
        this.keyframeInterval = builder.keyframeInterval;
        this.periodicKeyframes = builder.periodicKeyframes;
    }

    /**
     * Returns options for a file or on-demand stream, on the default threads.
     *
     * @param width the picture width in pixels, positive
     * @param height the picture height in pixels, positive
     * @param frameRate the nominal frame rate in frames per second, positive
     * @param bitrate the target bitrate in bits per second, positive
     * @return the options
     * @throws IllegalArgumentException if a value is not positive
     */
    public static VideoEncoderOptions of(int width, int height, Rational frameRate, long bitrate) {
        return builder().size(width, height).frameRate(frameRate).bitrate(bitrate).build();
    }

    /**
     * Returns options for interactive transmission, such as a call: low delay, no reordering,
     * constant bitrate, on the default threads.
     *
     * @param width the picture width in pixels, positive
     * @param height the picture height in pixels, positive
     * @param frameRate the nominal frame rate in frames per second, positive
     * @param bitrate the target bitrate in bits per second, positive
     * @return the options
     * @throws IllegalArgumentException if a value is not positive
     */
    public static VideoEncoderOptions forRealtime(int width, int height, Rational frameRate, long bitrate) {
        return builder().size(width, height).frameRate(frameRate).bitrate(bitrate).realtime(true).build();
    }

    /**
     * Returns a builder with no picture size, frame rate or bitrate, for file use on the default
     * threads.
     *
     * @return the builder, whose {@link Builder#build()} requires the size, frame rate and bitrate
     * to be set
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
     * {@return the picture width in pixels, positive}
     */
    public int width() {
        return width;
    }

    /**
     * {@return the picture height in pixels, positive}
     */
    public int height() {
        return height;
    }

    /**
     * {@return the nominal frame rate in frames per second, positive}
     */
    public Rational frameRate() {
        return frameRate;
    }

    /**
     * {@return the target bitrate in bits per second, positive}
     *
     * <p>Each codec approximates the target, and a codec with a bounded range clamps it into that
     * range.
     */
    public long bitrate() {
        return bitrate;
    }

    /**
     * {@return whether the encoder is tuned for interactive transmission; {@code false} by default}
     *
     * <p>Real-time tuning means low delay, no frame reordering and a constant bitrate.
     */
    public boolean realtime() {
        return realtime;
    }

    /**
     * {@return the thread count, at least 0; 0 by default}
     *
     * <p>The count follows the module's per-operation convention: 0 lets the encoder use its
     * default share of the module's helper threads, 1 keeps the work on the calling thread, and a
     * larger value allows up to that many threads.
     */
    public int threads() {
        return threads;
    }

    /**
     * {@return the most frames from one key frame to the next, positive, or empty when the codec
     * chooses the distance or {@link #periodicKeyframes()} is {@code false}; empty by default}
     *
     * <p>With an interval of {@code n} and no key frame the codec adds at a scene cut or on
     * {@link VideoEncoder#requestKeyframe()}, frame {@code k * n} is a key frame for every
     * {@code k}; an added key frame restarts the count. An intra-only codec makes every frame a key
     * frame whatever the setting.
     */
    public OptionalInt keyframeInterval() {
        return keyframeInterval;
    }

    /**
     * {@return whether the stream may have periodic key frames; {@code true} by default}
     *
     * <p>When {@code false}, key frames come only at the first frame, at scene cuts a codec
     * detects and on {@link VideoEncoder#requestKeyframe()}. When {@code true}, their distance is
     * {@link #keyframeInterval()}, or, when that is empty, the codec's default for its tuning
     * (which may be none for {@link #realtime()} tuning).
     */
    public boolean periodicKeyframes() {
        return periodicKeyframes;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof VideoEncoderOptions that
                && width == that.width
                && height == that.height
                && frameRate.equals(that.frameRate)
                && bitrate == that.bitrate
                && realtime == that.realtime
                && threads == that.threads
                && keyframeInterval.equals(that.keyframeInterval)
                && periodicKeyframes == that.periodicKeyframes;
    }

    @Override
    public int hashCode() {
        return Objects.hash(width, height, frameRate, bitrate, realtime, threads, keyframeInterval, periodicKeyframes);
    }

    @Override
    public String toString() {
        return "VideoEncoderOptions[width=" + width + ", height=" + height + ", frameRate=" + frameRate
                + ", bitrate=" + bitrate + ", realtime=" + realtime + ", threads=" + threads + ", keyframeInterval="
                + keyframeInterval + ", periodicKeyframes=" + periodicKeyframes + "]";
    }

    /**
     * Builds {@link VideoEncoderOptions}.
     *
     * <p>A builder from {@link VideoEncoderOptions#builder()} starts without a picture size, frame
     * rate and bitrate, which must be set before {@link #build()}; one from
     * {@link VideoEncoderOptions#toBuilder()} starts with every setting. Setters store their value;
     * {@link #build()} checks the ranges. Instances are not thread-safe.
     */
    public static final class Builder {
        private int width;
        private int height;
        private Rational frameRate;
        private long bitrate;
        private boolean realtime;
        private int threads;
        private OptionalInt keyframeInterval = OptionalInt.empty();
        private boolean periodicKeyframes = true;
        private boolean sizeSet;
        private boolean bitrateSet;

        private Builder() {
        }

        private Builder(VideoEncoderOptions options) {
            this.width = options.width;
            this.height = options.height;
            this.frameRate = options.frameRate;
            this.bitrate = options.bitrate;
            this.realtime = options.realtime;
            this.threads = options.threads;
            this.keyframeInterval = options.keyframeInterval;
            this.periodicKeyframes = options.periodicKeyframes;
            this.sizeSet = true;
            this.bitrateSet = true;
        }

        /**
         * Sets the picture size; see {@link VideoEncoderOptions#width()} and
         * {@link VideoEncoderOptions#height()}.
         *
         * @param width the picture width
         * @param height the picture height
         * @return this builder
         */
        public Builder size(int width, int height) {
            this.width = width;
            this.height = height;
            this.sizeSet = true;
            return this;
        }

        /**
         * Sets the nominal frame rate; see {@link VideoEncoderOptions#frameRate()}.
         *
         * @param frameRate the value
         * @return this builder
         */
        public Builder frameRate(Rational frameRate) {
            this.frameRate = Objects.requireNonNull(frameRate, "frameRate");
            return this;
        }

        /**
         * Sets the target bitrate; see {@link VideoEncoderOptions#bitrate()}.
         *
         * @param bitrate the value
         * @return this builder
         */
        public Builder bitrate(long bitrate) {
            this.bitrate = bitrate;
            this.bitrateSet = true;
            return this;
        }

        /**
         * Sets whether the encoder is tuned for interactive transmission; see
         * {@link VideoEncoderOptions#realtime()}.
         *
         * @param realtime the value
         * @return this builder
         */
        public Builder realtime(boolean realtime) {
            this.realtime = realtime;
            return this;
        }

        /**
         * Sets the thread count; see {@link VideoEncoderOptions#threads()}.
         *
         * @param threads the value
         * @return this builder
         */
        public Builder threads(int threads) {
            this.threads = threads;
            return this;
        }

        /**
         * Sets the most frames from one key frame to the next, turning periodic key frames on; see
         * {@link VideoEncoderOptions#keyframeInterval()}.
         *
         * @param frames the distance in frames
         * @return this builder
         */
        public Builder keyframeInterval(int frames) {
            this.keyframeInterval = OptionalInt.of(frames);
            this.periodicKeyframes = true;
            return this;
        }

        /**
         * Codes key frames only at the first frame, at scene cuts and on request; see
         * {@link VideoEncoderOptions#periodicKeyframes()}.
         *
         * @return this builder
         */
        public Builder noPeriodicKeyframes() {
            this.keyframeInterval = OptionalInt.empty();
            this.periodicKeyframes = false;
            return this;
        }

        /**
         * Lets the codec choose the key frame distance for its tuning; see
         * {@link VideoEncoderOptions#keyframeInterval()}.
         *
         * @return this builder
         */
        public Builder automaticKeyframeInterval() {
            this.keyframeInterval = OptionalInt.empty();
            this.periodicKeyframes = true;
            return this;
        }

        /**
         * Returns options holding the settings of this builder.
         *
         * @return the options
         * @throws IllegalArgumentException if the width, the height, the frame rate, the bitrate or
         * the key frame interval is not positive, or the thread count is negative
         * @throws IllegalStateException if the picture size, the frame rate or the bitrate was never
         * set
         */
        public VideoEncoderOptions build() {
            if (!sizeSet || frameRate == null || !bitrateSet) {
                throw new IllegalStateException("picture size, frame rate and bitrate must be set");
            }
            Checks.positive("picture width", width);
            Checks.positive("picture height", height);
            Checks.positive("frame rate", frameRate.toDouble());
            Checks.positive("bitrate", bitrate);
            Checks.nonNegative("thread count", threads);
            if (keyframeInterval.isPresent()) {
                Checks.positive("key frame interval", keyframeInterval.getAsInt());
            }
            return new VideoEncoderOptions(this);
        }
    }
}
