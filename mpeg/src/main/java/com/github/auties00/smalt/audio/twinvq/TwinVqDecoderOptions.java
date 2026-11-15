package com.github.auties00.smalt.audio.twinvq;

import com.github.auties00.smalt.MediaLimits;

import java.util.Objects;
import java.util.OptionalInt;

/**
 * Holds the settings of a {@link TwinVqDecoder}. Instances are immutable and thread-safe; create them with
 * {@link #builder()} or a preset such as {@link #defaults()}.
 */
public final class TwinVqDecoderOptions {
    /**
     * The options of {@link #defaults()}.
     */
    private static final TwinVqDecoderOptions DEFAULTS = new Builder().build();

    private final MediaLimits limits;

    private final int maxLayer;

    private TwinVqDecoderOptions(Builder builder) {
        this.limits = builder.limits;
        this.maxLayer = builder.maxLayer;
    }

    /**
     * Returns the default options: decoding every layer under {@link MediaLimits#defaults()}.
     *
     * @return the default options
     */
    public static TwinVqDecoderOptions defaults() {
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
     * <p>The decoder checks the channels of the configuration and the access units the error protection tool
     * reassembles against them; every other allocation is fixed by the configuration or bounded by the frame length.
     */
    public MediaLimits limits() {
        return limits;
    }

    /**
     * {@return the highest layer decoded, or empty to decode every layer the tracks carry; empty by default}
     *
     * <p>Layer 0 is the base layer and the scalable layers follow in the order of their tracks. A limit above the top
     * layer of an object decodes every layer.
     */
    public OptionalInt maxLayer() {
        return maxLayer < 0 ? OptionalInt.empty() : OptionalInt.of(maxLayer);
    }

    /**
     * Builds {@link TwinVqDecoderOptions}. Every value is validated when its setter is called.
     */
    public static final class Builder {
        private MediaLimits limits = MediaLimits.defaults();

        private int maxLayer = -1;

        private Builder() {
        }

        private Builder(TwinVqDecoderOptions options) {
            this.limits = options.limits;
            this.maxLayer = options.maxLayer;
        }

        /**
         * Sets the resource bounds; see {@link TwinVqDecoderOptions#limits()}.
         *
         * @param limits the value
         * @return this builder
         */
        public Builder limits(MediaLimits limits) {
            this.limits = Objects.requireNonNull(limits, "limits");
            return this;
        }

        /**
         * Sets the highest layer decoded; see {@link TwinVqDecoderOptions#maxLayer()}.
         *
         * @param maxLayer the value, from 0
         * @return this builder
         * @throws IllegalArgumentException if {@code maxLayer} is negative
         */
        public Builder maxLayer(int maxLayer) {
            if (maxLayer < 0) {
                throw new IllegalArgumentException("maxLayer " + maxLayer + " is negative");
            }
            this.maxLayer = maxLayer;
            return this;
        }

        /**
         * Decodes every layer the tracks carry, which is the default; see {@link TwinVqDecoderOptions#maxLayer()}.
         *
         * @return this builder
         */
        public Builder allLayers() {
            this.maxLayer = -1;
            return this;
        }

        /**
         * Builds the options.
         *
         * @return the options
         */
        public TwinVqDecoderOptions build() {
            return new TwinVqDecoderOptions(this);
        }
    }
}
