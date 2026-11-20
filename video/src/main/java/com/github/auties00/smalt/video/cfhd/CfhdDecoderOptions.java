package com.github.auties00.smalt.video.cfhd;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.internal.check.Checks;

import java.util.Objects;

/**
 * Holds the settings of a {@link CfhdDecoder}. Instances are immutable and thread-safe; create them with
 * {@link #builder()} or a preset such as {@link #defaults()}.
 *
 * @apiNote The codec-independent {@link com.github.auties00.smalt.video.VideoDecoders} registry builds its
 * CineForm decoders from {@link com.github.auties00.smalt.video.VideoDecoderOptions}, taking their limits and
 * thread count and the other settings of {@link #defaults()}, so a
 * {@link com.github.auties00.smalt.video.VideoReader} delivers CineForm RAW frames demosaiced.
 */
public final class CfhdDecoderOptions {
    /**
     * The settings of {@link #defaults()}.
     */
    private static final CfhdDecoderOptions DEFAULTS = new Builder().build();

    private final MediaLimits limits;

    private final int threads;

    private final CfhdBayerOutput bayerOutput;

    private CfhdDecoderOptions(Builder builder) {
        this.limits = builder.limits;
        this.threads = builder.threads;
        this.bayerOutput = builder.bayerOutput;
    }

    /**
     * Returns decoding on the shared pool under {@link MediaLimits#DEFAULT} with CineForm RAW frames demosaiced.
     *
     * @return the options
     */
    public static CfhdDecoderOptions defaults() {
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
     * {@return the resource bounds the decoder checks every frame against before allocating;
     * {@link MediaLimits#DEFAULT} by default}
     */
    public MediaLimits limits() {
        return limits;
    }

    /**
     * {@return the thread count, at least 0; 0 by default}
     *
     * <p>1 decodes on the calling thread only, 0 lets the decoder spread the rows of a CineForm RAW demosaic over
     * the module's shared helper threads, and a larger value spreads them over at most that many threads. The
     * pictures never depend on the thread count.
     */
    public int threads() {
        return threads;
    }

    /**
     * {@return what the decoder makes of CineForm RAW (Bayer coded) frames; {@link CfhdBayerOutput#DEMOSAICED} by
     * default}
     *
     * <p>Frames of the other encoded formats decode the same way whatever this setting.
     */
    public CfhdBayerOutput bayerOutput() {
        return bayerOutput;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof CfhdDecoderOptions that && limits.equals(that.limits) && threads == that.threads
                && bayerOutput == that.bayerOutput;
    }

    @Override
    public int hashCode() {
        return Objects.hash(limits, threads, bayerOutput);
    }

    @Override
    public String toString() {
        return "CfhdDecoderOptions[limits=" + limits + ", threads=" + threads + ", bayerOutput=" + bayerOutput + "]";
    }

    /**
     * A builder of {@link CfhdDecoderOptions}.
     *
     * <p>Setters reject only {@code null}; {@link #build()} checks the ranges. Instances are not thread-safe.
     */
    public static final class Builder {
        private MediaLimits limits = MediaLimits.DEFAULT;

        private int threads;

        private CfhdBayerOutput bayerOutput = CfhdBayerOutput.DEMOSAICED;

        private Builder() {
        }

        private Builder(CfhdDecoderOptions options) {
            this.limits = options.limits;
            this.threads = options.threads;
            this.bayerOutput = options.bayerOutput;
        }

        /**
         * Sets the resource bounds; see {@link CfhdDecoderOptions#limits()}.
         *
         * @param limits the value
         * @return this builder
         */
        public Builder limits(MediaLimits limits) {
            this.limits = Objects.requireNonNull(limits, "limits");
            return this;
        }

        /**
         * Sets the thread count; see {@link CfhdDecoderOptions#threads()}.
         *
         * @param threads the value
         * @return this builder
         */
        public Builder threads(int threads) {
            this.threads = threads;
            return this;
        }

        /**
         * Sets what CineForm RAW frames decode to; see {@link CfhdDecoderOptions#bayerOutput()}.
         *
         * @param bayerOutput the value
         * @return this builder
         */
        public Builder bayerOutput(CfhdBayerOutput bayerOutput) {
            this.bayerOutput = Objects.requireNonNull(bayerOutput, "bayerOutput");
            return this;
        }

        /**
         * Returns the options holding the settings of this builder.
         *
         * @return the options
         * @throws IllegalArgumentException if the thread count is negative
         */
        public CfhdDecoderOptions build() {
            Checks.nonNegative("thread count", threads);
            return new CfhdDecoderOptions(this);
        }
    }
}
