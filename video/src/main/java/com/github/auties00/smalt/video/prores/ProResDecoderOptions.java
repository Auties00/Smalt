package com.github.auties00.smalt.video.prores;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.internal.check.Checks;

import java.util.Objects;

/**
 * Holds the settings of a {@link ProResDecoder}. Instances are immutable and thread-safe; create them
 * with {@link #builder()} or a preset such as {@link #defaults()}.
 *
 * @apiNote The codec-independent {@link com.github.auties00.smalt.video.VideoDecoders} registry
 * passes its own {@link com.github.auties00.smalt.video.VideoDecoderOptions#highBitDepth()}, which
 * is off by default, so a {@link com.github.auties00.smalt.video.VideoReader} delivers 8-bit
 * pictures unless asked for high bit depth, like every other codec.
 */
public final class ProResDecoderOptions {
    /**
     * The settings of {@link #defaults()}.
     */
    private static final ProResDecoderOptions DEFAULTS = new Builder().build();

    private final MediaLimits limits;

    private final int threads;

    private final boolean highBitDepth;

    private ProResDecoderOptions(Builder builder) {
        this.limits = builder.limits;
        this.threads = builder.threads;
        this.highBitDepth = builder.highBitDepth;
    }

    /**
     * Returns decoding to 10 and 12-bit pictures on the shared pool under
     * {@link MediaLimits#DEFAULT}.
     *
     * @return the options
     */
    public static ProResDecoderOptions defaults() {
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
     * <p>1 decodes on the calling thread only, 0 lets the decoder spread the slice rows of a frame
     * over the module's shared helper threads, and a larger value decodes up to that many frames at
     * once, one per helper thread, which delays every frame by up to that many packets (see
     * {@link ProResDecoder}). The pictures never depend on the thread count.
     */
    public int threads() {
        return threads;
    }

    /**
     * {@return whether the pictures keep the 10 bits of 4:2:2 frames and the 12 bits of 4:4:4
     * frames; {@code true} by default}
     *
     * <p>When set, 4:2:2 frames are 10-bit and 4:4:4 frames 12-bit
     * {@link com.github.auties00.smalt.image.Yuv16Picture}s, the depths ProRes is mastered at; when
     * clear, the decoder computes 8-bit {@link com.github.auties00.smalt.image.YuvPicture}s
     * directly.
     */
    public boolean highBitDepth() {
        return highBitDepth;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ProResDecoderOptions that && limits.equals(that.limits) && threads == that.threads
                && highBitDepth == that.highBitDepth;
    }

    @Override
    public int hashCode() {
        return Objects.hash(limits, threads, highBitDepth);
    }

    @Override
    public String toString() {
        return "ProResDecoderOptions[limits=" + limits + ", threads=" + threads + ", highBitDepth=" + highBitDepth
                + "]";
    }

    /**
     * A builder of {@link ProResDecoderOptions}.
     *
     * <p>Setters reject only {@code null}; {@link #build()} checks the ranges. Instances are not
     * thread-safe.
     */
    public static final class Builder {
        private MediaLimits limits = MediaLimits.DEFAULT;

        private int threads;

        private boolean highBitDepth = true;

        private Builder() {
        }

        private Builder(ProResDecoderOptions options) {
            this.limits = options.limits;
            this.threads = options.threads;
            this.highBitDepth = options.highBitDepth;
        }

        /**
         * Sets the resource bounds; see {@link ProResDecoderOptions#limits()}.
         *
         * @param limits the value
         * @return this builder
         */
        public Builder limits(MediaLimits limits) {
            this.limits = Objects.requireNonNull(limits, "limits");
            return this;
        }

        /**
         * Sets the thread count; see {@link ProResDecoderOptions#threads()}.
         *
         * @param threads the value
         * @return this builder
         */
        public Builder threads(int threads) {
            this.threads = threads;
            return this;
        }

        /**
         * Sets whether pictures keep the depth of deep frames; see
         * {@link ProResDecoderOptions#highBitDepth()}.
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
         * @throws IllegalArgumentException if the thread count is negative
         */
        public ProResDecoderOptions build() {
            Checks.nonNegative("thread count", threads);
            return new ProResDecoderOptions(this);
        }
    }
}
