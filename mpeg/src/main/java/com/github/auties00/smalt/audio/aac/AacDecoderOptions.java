package com.github.auties00.smalt.audio.aac;

import com.github.auties00.smalt.MediaLimits;

import java.util.Objects;

/**
 * Holds the settings of an {@link AacDecoder}. Instances are immutable and thread-safe; create them
 * with {@link #builder()} or a preset such as {@link #defaults()}.
 */
public final class AacDecoderOptions {
    /**
     * The options of {@link #defaults()}.
     */
    private static final AacDecoderOptions DEFAULTS = new Builder().build();

    private final MediaLimits limits;

    private final boolean spectralBandReplication;

    private AacDecoderOptions(Builder builder) {
        this.limits = builder.limits;
        this.spectralBandReplication = builder.spectralBandReplication;
    }

    /**
     * Returns the default options: decoding under {@link MediaLimits#defaults()}, with spectral band
     * replication and parametric stereo.
     *
     * @return the default options
     */
    public static AacDecoderOptions defaults() {
        return DEFAULTS;
    }

    /**
     * Returns a builder initialized with {@link #defaults()}.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns a builder initialized with these options.
     *
     * @return a new builder
     */
    public Builder toBuilder() {
        return new Builder(this);
    }

    /**
     * {@return the resource bounds the decoder enforces; {@link MediaLimits#defaults()} by default}
     *
     * <p>The decoder checks the channel count that the configuration or a program configuration
     * element declares against {@link MediaLimits#maxChannels()}.
     */
    public MediaLimits limits() {
        return limits;
    }

    /**
     * {@return whether the decoder decodes spectral band replication and parametric stereo;
     * {@code true} by default}
     *
     * <p>When {@code false}, an HE-AAC stream decodes to its Low Complexity core, at the core rate
     * and with the core layout, for a fraction of the work.
     */
    public boolean spectralBandReplication() {
        return spectralBandReplication;
    }

    @Override
    public boolean equals(Object object) {
        return object instanceof AacDecoderOptions that && limits.equals(that.limits)
                && spectralBandReplication == that.spectralBandReplication;
    }

    @Override
    public int hashCode() {
        return Objects.hash(limits, spectralBandReplication);
    }

    @Override
    public String toString() {
        return "AacDecoderOptions[limits=" + limits + ", spectralBandReplication=" + spectralBandReplication + "]";
    }

    /**
     * A builder of {@link AacDecoderOptions}, starting from {@link AacDecoderOptions#defaults()}.
     *
     * <p>Setters reject only {@code null}; every value is valid. A builder may build repeatedly.
     * Instances are not thread-safe.
     */
    public static final class Builder {
        private MediaLimits limits;

        private boolean spectralBandReplication;

        private Builder() {
            this.limits = MediaLimits.DEFAULT;
            this.spectralBandReplication = true;
        }

        private Builder(AacDecoderOptions options) {
            this.limits = options.limits;
            this.spectralBandReplication = options.spectralBandReplication;
        }

        /**
         * Sets the resource bounds; see {@link AacDecoderOptions#limits()}.
         *
         * @param limits the value
         * @return this builder
         */
        public Builder limits(MediaLimits limits) {
            this.limits = Objects.requireNonNull(limits, "limits");
            return this;
        }

        /**
         * Sets whether spectral band replication and parametric stereo are decoded; see
         * {@link AacDecoderOptions#spectralBandReplication()}.
         *
         * @param enabled the value
         * @return this builder
         */
        public Builder spectralBandReplication(boolean enabled) {
            this.spectralBandReplication = enabled;
            return this;
        }

        /**
         * Returns the options holding the settings of this builder.
         *
         * @return the options
         */
        public AacDecoderOptions build() {
            return new AacDecoderOptions(this);
        }
    }
}
