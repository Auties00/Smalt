package com.github.auties00.smalt.video.h264;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.internal.check.Checks;

import java.util.Objects;

/**
 * Holds the settings of an {@link H264Decoder}. Instances are immutable and thread-safe; create them
 * with {@link #builder()} or a preset such as {@link #defaults()}.
 */
public final class H264DecoderOptions {
    /**
     * The settings of {@link #defaults()}.
     */
    private static final H264DecoderOptions DEFAULTS = new Builder().build();

    /**
     * The settings of {@link #forRealtime()}.
     */
    private static final H264DecoderOptions REALTIME = new Builder().threads(1).realtime(true).build();

    private final MediaLimits limits;

    private final int threads;

    private final boolean realtime;

    private H264DecoderOptions(Builder builder) {
        this.limits = builder.limits;
        this.threads = builder.threads;
        this.realtime = builder.realtime;
    }

    /**
     * Returns the settings for files: one picture at a time on the calling thread under
     * {@link MediaLimits#defaults()}, with the reordering the stream signals.
     *
     * @return the options
     */
    public static H264DecoderOptions defaults() {
        return DEFAULTS;
    }

    /**
     * Returns the settings for real-time streams, such as the video of a call: low-delay mode (see
     * {@link #realtime()}) on the calling thread under {@link MediaLimits#defaults()}.
     *
     * @return the options
     */
    public static H264DecoderOptions forRealtime() {
        return REALTIME;
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
     * {@return the resource bounds the decoder enforces; {@link MediaLimits#defaults()} by default}
     */
    public MediaLimits limits() {
        return limits;
    }

    /**
     * {@return the most pictures decoded at once: 0 or 1 to decode one picture at a time on the
     * calling thread, or a larger count to decode up to that many pictures concurrently on the shared
     * helper threads; 0 by default}
     *
     * <p>The output never depends on the thread count.
     */
    public int threads() {
        return threads;
    }

    /**
     * {@return whether the decoder works in low-delay mode; {@code false} by default}
     *
     * <p>In low-delay mode every packet must hold one whole access unit, whose picture is finished
     * and output before the decode call returns (at most one frame per packet, none when the
     * access unit carried no picture or only part of one that could not start). Baseline profile
     * sequences never reorder pictures, so their pictures are output at once even when the
     * sequence parameter set signals no reordering limit; other profiles keep the reordering the
     * sequence signals (none for a {@code bitstream_restriction} with
     * {@code max_num_reorder_frames} 0 or picture order count type 2). Without a configuration
     * record on the track, packets are Annex B access units carrying their parameter sets in
     * band. A low-delay decoder decodes on the calling thread, so {@link #threads()} is at most 1.
     */
    public boolean realtime() {
        return realtime;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof H264DecoderOptions that && limits.equals(that.limits) && threads == that.threads
                && realtime == that.realtime;
    }

    @Override
    public int hashCode() {
        return Objects.hash(limits, threads, realtime);
    }

    @Override
    public String toString() {
        return "H264DecoderOptions[limits=" + limits + ", threads=" + threads + ", realtime=" + realtime + "]";
    }

    /**
     * A builder of {@link H264DecoderOptions}, starting from {@link H264DecoderOptions#defaults()}.
     *
     * <p>Setters reject only {@code null}; {@link #build()} checks the values and how they combine.
     * A builder may build repeatedly. Instances are not thread-safe.
     */
    public static final class Builder {
        private MediaLimits limits;

        private int threads;

        private boolean realtime;

        private Builder() {
            this.limits = MediaLimits.DEFAULT;
        }

        private Builder(H264DecoderOptions options) {
            this.limits = options.limits;
            this.threads = options.threads;
            this.realtime = options.realtime;
        }

        /**
         * Sets the resource bounds; see {@link H264DecoderOptions#limits()}.
         *
         * @param limits the value
         * @return this builder
         */
        public Builder limits(MediaLimits limits) {
            this.limits = Objects.requireNonNull(limits, "limits");
            return this;
        }

        /**
         * Sets the most pictures decoded at once; see {@link H264DecoderOptions#threads()}.
         *
         * @param threads the value
         * @return this builder
         */
        public Builder threads(int threads) {
            this.threads = threads;
            return this;
        }

        /**
         * Sets whether the decoder works in low-delay mode; see {@link H264DecoderOptions#realtime()}.
         *
         * @param realtime the value
         * @return this builder
         */
        public Builder realtime(boolean realtime) {
            this.realtime = realtime;
            return this;
        }

        /**
         * Returns the options holding the settings of this builder.
         *
         * @return the options
         * @throws IllegalArgumentException if the thread count is negative, or above one in low-delay
         * mode
         */
        public H264DecoderOptions build() {
            Checks.nonNegative("thread count", threads);
            if (realtime && threads > 1) {
                throw new IllegalArgumentException("thread count " + threads + " is above 1 in low-delay mode");
            }
            return new H264DecoderOptions(this);
        }
    }
}
