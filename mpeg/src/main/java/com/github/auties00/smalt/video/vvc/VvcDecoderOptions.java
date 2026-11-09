package com.github.auties00.smalt.video.vvc;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.internal.check.Checks;

import java.util.Objects;

/**
 * Holds the settings of a {@link VvcDecoder}. Instances are immutable and thread-safe; create them with
 * {@link #builder()} or the preset {@link #defaults()}.
 */
public final class VvcDecoderOptions {
    /**
     * The options of {@link #defaults()}.
     */
    private static final VvcDecoderOptions DEFAULTS = new Builder().build();

    /**
     * The resource bounds; see {@link #limits()}.
     */
    private final MediaLimits limits;

    /**
     * The thread count; see {@link #threads()}.
     */
    private final int threads;

    /**
     * Whether deep pictures keep their depth; see {@link #highBitDepth()}.
     */
    private final boolean highBitDepth;

    /**
     * Whether picture hashes are verified; see {@link #hashVerification()}.
     */
    private final boolean hashVerification;

    /**
     * Whether film grain is added; see {@link #filmGrain()}.
     */
    private final boolean filmGrain;

    /**
     * Constructs the options a builder holds.
     *
     * @param builder the builder holding the settings
     */
    private VvcDecoderOptions(Builder builder) {
        this.limits = builder.limits;
        this.threads = builder.threads;
        this.highBitDepth = builder.highBitDepth;
        this.hashVerification = builder.hashVerification;
        this.filmGrain = builder.filmGrain;
    }

    /**
     * Returns the default options: {@link MediaLimits#defaults()}, the shared pool, 8-bit output, no hash
     * verification and film grain added.
     *
     * @return the default options
     */
    public static VvcDecoderOptions defaults() {
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
     * {@return the resource bounds the decoder enforces; {@link MediaLimits#defaults()} by default}
     */
    public MediaLimits limits() {
        return limits;
    }

    /**
     * {@return the thread count, zero or more: 0 lets the decoder use the module's shared pool of helper threads
     * (sized by the {@code smalt.threads} system property), 1 keeps the work on the calling thread, and a larger
     * value allows up to that many threads; 0 by default}
     *
     * <p>The output never depends on the thread count.
     */
    public int threads() {
        return threads;
    }

    /**
     * {@return whether the pictures of 10-bit streams are handed out at their own depth as {@link Yuv16Picture}s
     * rather than rounded to 8-bit {@link YuvPicture}s; {@code false} by default}
     */
    public boolean highBitDepth() {
        return highBitDepth;
    }

    /**
     * {@return whether each picture that a decoded picture hash SEI message describes is checked against it, a
     * picture that does not match raising {@link MediaException.Malformed} (the pictures a GDR picture recovers
     * before its recovery point excepted, since they are decoded from generated references); {@code false} by
     * default}
     */
    public boolean hashVerification() {
        return hashVerification;
    }

    /**
     * {@return whether the decoder adds the film grain that film grain characteristics SEI messages describe to the
     * pictures; {@code true} by default}
     *
     * <p>When set, the grain of the frequency filtering model with additive blending is rendered as {@link VvcDecoder}
     * describes; when clear, frames hold the decoded samples as they are. Decoded picture hashes are always checked
     * on the samples without grain.
     */
    public boolean filmGrain() {
        return filmGrain;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof VvcDecoderOptions that && limits.equals(that.limits) && threads == that.threads
                && highBitDepth == that.highBitDepth && hashVerification == that.hashVerification
                && filmGrain == that.filmGrain;
    }

    @Override
    public int hashCode() {
        return Objects.hash(limits, threads, highBitDepth, hashVerification, filmGrain);
    }

    @Override
    public String toString() {
        return "VvcDecoderOptions[limits=" + limits + ", threads=" + threads + ", highBitDepth=" + highBitDepth
                + ", hashVerification=" + hashVerification + ", filmGrain=" + filmGrain + "]";
    }

    /**
     * A builder of {@link VvcDecoderOptions}, starting from {@link VvcDecoderOptions#defaults()}.
     *
     * <p>Setters reject only {@code null}; {@link #build()} checks the ranges. A builder may build repeatedly.
     * Instances are not thread-safe.
     */
    public static final class Builder {
        /**
         * The resource bounds.
         */
        private MediaLimits limits;

        /**
         * The thread count.
         */
        private int threads;

        /**
         * Whether deep pictures keep their depth.
         */
        private boolean highBitDepth;

        /**
         * Whether picture hashes are verified.
         */
        private boolean hashVerification;

        /**
         * Whether film grain is added.
         */
        private boolean filmGrain;

        /**
         * Constructs a builder holding the defaults.
         */
        private Builder() {
            this.limits = MediaLimits.DEFAULT;
            this.filmGrain = true;
        }

        /**
         * Constructs a builder holding a set of options.
         *
         * @param options the options whose settings the builder starts from
         */
        private Builder(VvcDecoderOptions options) {
            this.limits = options.limits;
            this.threads = options.threads;
            this.highBitDepth = options.highBitDepth;
            this.hashVerification = options.hashVerification;
            this.filmGrain = options.filmGrain;
        }

        /**
         * Sets the resource bounds; see {@link VvcDecoderOptions#limits()}.
         *
         * @param limits the value
         * @return this builder
         */
        public Builder limits(MediaLimits limits) {
            this.limits = Objects.requireNonNull(limits, "limits");
            return this;
        }

        /**
         * Sets the thread count; see {@link VvcDecoderOptions#threads()}.
         *
         * @param threads the value
         * @return this builder
         */
        public Builder threads(int threads) {
            this.threads = threads;
            return this;
        }

        /**
         * Sets whether pictures deeper than 8 bits keep their depth; see {@link VvcDecoderOptions#highBitDepth()}.
         *
         * @param highBitDepth the value
         * @return this builder
         */
        public Builder highBitDepth(boolean highBitDepth) {
            this.highBitDepth = highBitDepth;
            return this;
        }

        /**
         * Sets whether decoded picture hashes are verified; see {@link VvcDecoderOptions#hashVerification()}.
         *
         * @param hashVerification the value
         * @return this builder
         */
        public Builder hashVerification(boolean hashVerification) {
            this.hashVerification = hashVerification;
            return this;
        }

        /**
         * Sets whether film grain characteristics SEI messages add grain to the pictures; see
         * {@link VvcDecoderOptions#filmGrain()}.
         *
         * @param filmGrain the value
         * @return this builder
         */
        public Builder filmGrain(boolean filmGrain) {
            this.filmGrain = filmGrain;
            return this;
        }

        /**
         * Builds the options.
         *
         * @return the options
         * @throws IllegalArgumentException if the thread count is negative
         */
        public VvcDecoderOptions build() {
            Checks.nonNegative("thread count", threads);
            return new VvcDecoderOptions(this);
        }
    }
}
