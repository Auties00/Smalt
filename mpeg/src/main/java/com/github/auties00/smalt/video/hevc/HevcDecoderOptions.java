package com.github.auties00.smalt.video.hevc;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.internal.check.Checks;

import java.util.Objects;

/**
 * Holds the settings of an {@link HevcDecoder}. Instances are immutable and thread-safe; create them
 * with {@link #builder()} or a preset such as {@link #defaults()}.
 */
public final class HevcDecoderOptions {
    /**
     * The options of {@link #defaults()}.
     */
    private static final HevcDecoderOptions DEFAULTS = new Builder().build();

    private final MediaLimits limits;

    private final int threads;

    private final boolean highBitDepth;

    private final boolean hashVerification;

    private HevcDecoderOptions(Builder builder) {
        this.limits = builder.limits;
        this.threads = builder.threads;
        this.highBitDepth = builder.highBitDepth;
        this.hashVerification = builder.hashVerification;
    }

    /**
     * Returns the default options: {@link MediaLimits#defaults()}, the shared pool, 8-bit output and
     * no hash verification.
     *
     * @return the default options
     */
    public static HevcDecoderOptions defaults() {
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
     * {@return the thread count, zero or more: 0 lets the decoder use the module's shared pool of
     * helper threads (sized by the {@code smalt.threads} system property), 1 keeps the work on the
     * calling thread, and a larger value allows up to that many threads; 0 by default}
     *
     * <p>The output never depends on the thread count.
     */
    public int threads() {
        return threads;
    }

    /**
     * {@return whether the pictures of streams deeper than 8 bits are handed out at their own depth
     * as {@link Yuv16Picture}s rather than rounded to 8-bit {@link YuvPicture}s; {@code false} by
     * default}
     */
    public boolean highBitDepth() {
        return highBitDepth;
    }

    /**
     * {@return whether each picture that a decoded picture hash SEI message describes is checked
     * against it, a picture that does not match raising {@link MediaException.Malformed};
     * {@code false} by default}
     */
    public boolean hashVerification() {
        return hashVerification;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof HevcDecoderOptions that && limits.equals(that.limits) && threads == that.threads
                && highBitDepth == that.highBitDepth && hashVerification == that.hashVerification;
    }

    @Override
    public int hashCode() {
        return Objects.hash(limits, threads, highBitDepth, hashVerification);
    }

    @Override
    public String toString() {
        return "HevcDecoderOptions[limits=" + limits + ", threads=" + threads + ", highBitDepth=" + highBitDepth
                + ", hashVerification=" + hashVerification + "]";
    }

    /**
     * A builder of {@link HevcDecoderOptions}, starting from {@link HevcDecoderOptions#defaults()}.
     *
     * <p>Setters reject only {@code null}; {@link #build()} checks the ranges. A builder may build
     * repeatedly. Instances are not thread-safe.
     */
    public static final class Builder {
        private MediaLimits limits;

        private int threads;

        private boolean highBitDepth;

        private boolean hashVerification;

        private Builder() {
            this.limits = MediaLimits.DEFAULT;
        }

        private Builder(HevcDecoderOptions options) {
            this.limits = options.limits;
            this.threads = options.threads;
            this.highBitDepth = options.highBitDepth;
            this.hashVerification = options.hashVerification;
        }

        /**
         * Sets the resource bounds; see {@link HevcDecoderOptions#limits()}.
         *
         * @param limits the value
         * @return this builder
         */
        public Builder limits(MediaLimits limits) {
            this.limits = Objects.requireNonNull(limits, "limits");
            return this;
        }

        /**
         * Sets the thread count; see {@link HevcDecoderOptions#threads()}.
         *
         * @param threads the value
         * @return this builder
         */
        public Builder threads(int threads) {
            this.threads = threads;
            return this;
        }

        /**
         * Sets whether pictures deeper than 8 bits keep their depth; see
         * {@link HevcDecoderOptions#highBitDepth()}.
         *
         * @param highBitDepth the value
         * @return this builder
         */
        public Builder highBitDepth(boolean highBitDepth) {
            this.highBitDepth = highBitDepth;
            return this;
        }

        /**
         * Sets whether decoded picture hashes are verified; see
         * {@link HevcDecoderOptions#hashVerification()}.
         *
         * @param hashVerification the value
         * @return this builder
         */
        public Builder hashVerification(boolean hashVerification) {
            this.hashVerification = hashVerification;
            return this;
        }

        /**
         * Returns the options holding the settings of this builder.
         *
         * @return the options
         * @throws IllegalArgumentException if the thread count is negative
         */
        public HevcDecoderOptions build() {
            Checks.nonNegative("thread count", threads);
            return new HevcDecoderOptions(this);
        }
    }
}
