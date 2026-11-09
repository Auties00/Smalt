package com.github.auties00.smalt.audio.celp;

import com.github.auties00.smalt.MediaLimits;

import java.util.Objects;
import java.util.OptionalInt;

/**
 * Holds the settings of a {@link CelpDecoder}. Instances are immutable and thread-safe; create them with
 * {@link #builder()} or a preset such as {@link #defaults()}.
 */
public final class CelpDecoderOptions {
    /**
     * The options of {@link #defaults()}.
     */
    private static final CelpDecoderOptions DEFAULTS = new Builder().build();

    private final MediaLimits limits;

    private final boolean postFilter;

    private final int maxLayer;

    private CelpDecoderOptions(Builder builder) {
        this.limits = builder.limits;
        this.postFilter = builder.postFilter;
        this.maxLayer = builder.maxLayer;
    }

    /**
     * Returns the default options: decoding every layer under {@link MediaLimits#defaults()}, with the
     * post-processor.
     *
     * @return the default options
     */
    public static CelpDecoderOptions defaults() {
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
     * <p>The decoder checks the access units the error protection tool reassembles against them; every other
     * allocation is fixed by the configuration.
     */
    public MediaLimits limits() {
        return limits;
    }

    /**
     * {@return whether the decoder runs the post-processor over the synthesized speech; {@code true} by default}
     *
     * <p>The post-processor (ISO/IEC 14496-3 Annex 3.A, informative) sharpens the formants of the speech, follows its
     * pitch with the multi-pulse coder, compensates the spectral tilt and keeps the level of the synthesized signal.
     * The standard defines the decoded signal without it, which is what conformance compares.
     */
    public boolean postFilter() {
        return postFilter;
    }

    /**
     * {@return the highest layer decoded, or empty to decode every layer the tracks carry; empty by default}
     *
     * <p>Layer 0 is the base layer. The bitrate scalable enhancement layers follow as layers 1 to 3, in the order of
     * their {@code CELP-BRS-id}, and the bandwidth scalable layer, which raises the output from 8 to 16 kHz, comes
     * after the last bitrate scalable layer the object codes. A limit above the top layer of an object decodes every
     * layer.
     */
    public OptionalInt maxLayer() {
        return maxLayer < 0 ? OptionalInt.empty() : OptionalInt.of(maxLayer);
    }

    /**
     * Builds {@link CelpDecoderOptions}. Every value is validated when its setter is called.
     */
    public static final class Builder {
        private MediaLimits limits = MediaLimits.defaults();

        private boolean postFilter = true;

        private int maxLayer = -1;

        private Builder() {
        }

        private Builder(CelpDecoderOptions options) {
            this.limits = options.limits;
            this.postFilter = options.postFilter;
            this.maxLayer = options.maxLayer;
        }

        /**
         * Sets the resource bounds; see {@link CelpDecoderOptions#limits()}.
         *
         * @param limits the value
         * @return this builder
         */
        public Builder limits(MediaLimits limits) {
            this.limits = Objects.requireNonNull(limits, "limits");
            return this;
        }

        /**
         * Sets whether the post-processor runs; see {@link CelpDecoderOptions#postFilter()}.
         *
         * @param postFilter the value
         * @return this builder
         */
        public Builder postFilter(boolean postFilter) {
            this.postFilter = postFilter;
            return this;
        }

        /**
         * Sets the highest layer decoded; see {@link CelpDecoderOptions#maxLayer()}.
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
         * Decodes every layer the tracks carry, which is the default; see {@link CelpDecoderOptions#maxLayer()}.
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
        public CelpDecoderOptions build() {
            return new CelpDecoderOptions(this);
        }
    }
}
