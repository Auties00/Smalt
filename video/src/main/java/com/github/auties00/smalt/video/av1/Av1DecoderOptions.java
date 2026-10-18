package com.github.auties00.smalt.video.av1;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.internal.check.Checks;
import com.github.auties00.smalt.video.av1.internal.Av1Context;

import java.util.Objects;

/**
 * Holds the settings of an {@link Av1Decoder}. Instances are immutable and thread-safe; create
 * them with {@link #builder()} or a preset such as {@link #defaults()}.
 */
public final class Av1DecoderOptions {
    /**
     * The settings of {@link #defaults()}.
     */
    private static final Av1DecoderOptions DEFAULTS = new Builder().build();

    private final MediaLimits limits;

    private final int threads;

    private final int maxFrameDelay;

    private final boolean highBitDepth;

    private Av1DecoderOptions(Builder builder) {
        this.limits = builder.limits;
        this.threads = builder.threads;
        this.maxFrameDelay = builder.maxFrameDelay;
        this.highBitDepth = builder.highBitDepth;
    }

    /**
     * Returns the default options: 8-bit pictures, decoded with the shared helper threads under
     * {@link MediaLimits#DEFAULT}, one frame at a time.
     *
     * @return the options
     */
    public static Av1DecoderOptions defaults() {
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
     * {@return the resource bounds the decoder checks every frame size against before allocating;
     * {@link MediaLimits#DEFAULT} by default}
     */
    public MediaLimits limits() {
        return limits;
    }

    /**
     * {@return the thread count, at least 0; 0 by default}
     *
     * <p>1 decodes on the calling thread only; 0 or a larger value lets the decoder spread the
     * entropy decoding, reconstruction and in-loop filtering of large frames over the module's
     * shared helper threads. The pictures never depend on the thread count.
     */
    public int threads() {
        return threads;
    }

    /**
     * {@return the most frames the decoder works on at once, from 1 to 64; 1 by default}
     *
     * <p>1 returns every frame from the call that decodes it, the lowest latency, for real-time
     * streams such as the video of a call. A larger value lets the decoder overlap the decoding of
     * up to that many consecutive frames, which multiplies the throughput on many threads,
     * especially for streams coded as a single tile, at the price of returning each frame up to that
     * many packets minus one later (the rest come from {@link Av1Decoder#flush}). It has no effect
     * with {@link #threads()} 1.
     */
    public int maxFrameDelay() {
        return maxFrameDelay;
    }

    /**
     * {@return whether the frames of 10 and 12-bit streams keep their bit depth; {@code false} by
     * default}
     *
     * <p>When set, such frames are {@link com.github.auties00.smalt.image.Yuv16Picture}s of the
     * stream's bit depth; otherwise they are reduced to 8-bit
     * {@link com.github.auties00.smalt.image.YuvPicture}s.
     */
    public boolean highBitDepth() {
        return highBitDepth;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Av1DecoderOptions that && limits.equals(that.limits) && threads == that.threads
                && maxFrameDelay == that.maxFrameDelay && highBitDepth == that.highBitDepth;
    }

    @Override
    public int hashCode() {
        return Objects.hash(limits, threads, maxFrameDelay, highBitDepth);
    }

    @Override
    public String toString() {
        return "Av1DecoderOptions[limits=" + limits + ", threads=" + threads + ", maxFrameDelay=" + maxFrameDelay
                + ", highBitDepth=" + highBitDepth + "]";
    }

    /**
     * A builder of {@link Av1DecoderOptions}.
     *
     * <p>Setters reject only {@code null}; {@link #build()} checks the ranges. Instances are not
     * thread-safe.
     */
    public static final class Builder {
        private MediaLimits limits = MediaLimits.DEFAULT;

        private int threads;

        private int maxFrameDelay = 1;

        private boolean highBitDepth;

        private Builder() {
        }

        private Builder(Av1DecoderOptions options) {
            this.limits = options.limits;
            this.threads = options.threads;
            this.maxFrameDelay = options.maxFrameDelay;
            this.highBitDepth = options.highBitDepth;
        }

        /**
         * Sets the resource bounds; see {@link Av1DecoderOptions#limits()}.
         *
         * @param limits the value
         * @return this builder
         */
        public Builder limits(MediaLimits limits) {
            this.limits = Objects.requireNonNull(limits, "limits");
            return this;
        }

        /**
         * Sets the thread count; see {@link Av1DecoderOptions#threads()}.
         *
         * @param threads the value
         * @return this builder
         */
        public Builder threads(int threads) {
            this.threads = threads;
            return this;
        }

        /**
         * Sets the frame delay; see {@link Av1DecoderOptions#maxFrameDelay()}.
         *
         * @param maxFrameDelay the value
         * @return this builder
         */
        public Builder maxFrameDelay(int maxFrameDelay) {
            this.maxFrameDelay = maxFrameDelay;
            return this;
        }

        /**
         * Sets whether frames deeper than 8 bits keep their depth; see
         * {@link Av1DecoderOptions#highBitDepth()}.
         *
         * @param highBitDepth the value
         * @return this builder
         */
        public Builder highBitDepth(boolean highBitDepth) {
            this.highBitDepth = highBitDepth;
            return this;
        }

        /**
         * Returns the options holding the settings of this builder.
         *
         * @return the options
         * @throws IllegalArgumentException if the thread count is negative or the frame delay is
         * outside 1 to 64
         */
        public Av1DecoderOptions build() {
            Checks.nonNegative("thread count", threads);
            Checks.inRange("frame delay", maxFrameDelay, 1, Av1Context.MAX_FRAME_DELAY);
            return new Av1DecoderOptions(this);
        }
    }
}
