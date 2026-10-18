package com.github.auties00.smalt.video.dnxhd;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.internal.check.Checks;

import java.util.Objects;

/**
 * Holds the settings of a {@link DnxhdDecoder}. Instances are immutable and thread-safe; create them
 * with {@link #builder()} or a preset such as {@link #defaults()}.
 *
 * @apiNote The codec-independent {@link com.github.auties00.smalt.video.VideoDecoders} registry
 * passes its own {@link com.github.auties00.smalt.video.VideoDecoderOptions#highBitDepth()}, which
 * is off by default, so a {@link com.github.auties00.smalt.video.VideoReader} delivers 8-bit
 * pictures unless asked for high bit depth, like every other codec.
 */
public final class DnxhdDecoderOptions {
    /**
     * The settings of {@link #defaults()}.
     */
    private static final DnxhdDecoderOptions DEFAULTS = new Builder().build();

    private final MediaLimits limits;

    private final int threads;

    private final boolean highBitDepth;

    private DnxhdDecoderOptions(Builder builder) {
        this.limits = builder.limits;
        this.threads = builder.threads;
        this.highBitDepth = builder.highBitDepth;
    }

    /**
     * Returns decoding to pictures of the stream's depth on the shared pool under
     * {@link MediaLimits#DEFAULT}.
     *
     * @return the options
     */
    public static DnxhdDecoderOptions defaults() {
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
     * <p>1 decodes on the calling thread only, 0 lets the decoder spread the macroblock rows of a frame
     * over the module's shared helper threads, and a larger value decodes up to that many frames at
     * once, one per helper thread, which delays every frame by up to that many packets (see
     * {@link DnxhdDecoder}). The pictures never depend on the thread count.
     */
    public int threads() {
        return threads;
    }

    /**
     * {@return whether the pictures of 10 and 12-bit frames keep their depth; {@code true} by
     * default}
     *
     * <p>When set, 10 and 12-bit Y'CbCr frames are {@link com.github.auties00.smalt.image.Yuv16Picture}s
     * of their depth and RGB frames are {@link com.github.auties00.smalt.image.Rgba16Picture}s; when
     * clear, the decoder computes 8-bit pictures directly. 8-bit frames always decode to 8-bit
     * pictures.
     */
    public boolean highBitDepth() {
        return highBitDepth;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof DnxhdDecoderOptions that && limits.equals(that.limits) && threads == that.threads
                && highBitDepth == that.highBitDepth;
    }

    @Override
    public int hashCode() {
        return Objects.hash(limits, threads, highBitDepth);
    }

    @Override
    public String toString() {
        return "DnxhdDecoderOptions[limits=" + limits + ", threads=" + threads + ", highBitDepth=" + highBitDepth
                + "]";
    }

    /**
     * A builder of {@link DnxhdDecoderOptions}.
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

        private Builder(DnxhdDecoderOptions options) {
            this.limits = options.limits;
            this.threads = options.threads;
            this.highBitDepth = options.highBitDepth;
        }

        /**
         * Sets the resource bounds; see {@link DnxhdDecoderOptions#limits()}.
         *
         * @param limits the value
         * @return this builder
         */
        public Builder limits(MediaLimits limits) {
            this.limits = Objects.requireNonNull(limits, "limits");
            return this;
        }

        /**
         * Sets the thread count; see {@link DnxhdDecoderOptions#threads()}.
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
         * {@link DnxhdDecoderOptions#highBitDepth()}.
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
        public DnxhdDecoderOptions build() {
            Checks.nonNegative("thread count", threads);
            return new DnxhdDecoderOptions(this);
        }
    }
}
