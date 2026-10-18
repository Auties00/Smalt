package com.github.auties00.smalt.video;

import com.github.auties00.smalt.internal.check.Checks;
import com.github.auties00.smalt.track.Rational;

import java.util.Objects;

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

    private VideoEncoderOptions(int width, int height, Rational frameRate, long bitrate, boolean realtime,
                                int threads) {
        this.width = width;
        this.height = height;
        this.frameRate = frameRate;
        this.bitrate = bitrate;
        this.realtime = realtime;
        this.threads = threads;
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

    @Override
    public boolean equals(Object other) {
        return other instanceof VideoEncoderOptions that
                && width == that.width
                && height == that.height
                && frameRate.equals(that.frameRate)
                && bitrate == that.bitrate
                && realtime == that.realtime
                && threads == that.threads;
    }

    @Override
    public int hashCode() {
        return Objects.hash(width, height, frameRate, bitrate, realtime, threads);
    }

    @Override
    public String toString() {
        return "VideoEncoderOptions[width=" + width + ", height=" + height + ", frameRate=" + frameRate
                + ", bitrate=" + bitrate + ", realtime=" + realtime + ", threads=" + threads + "]";
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
         * Returns options holding the settings of this builder.
         *
         * @return the options
         * @throws IllegalArgumentException if the width, the height, the frame rate or the bitrate is
         * not positive, or the thread count is negative
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
            return new VideoEncoderOptions(width, height, frameRate, bitrate, realtime, threads);
        }
    }
}
