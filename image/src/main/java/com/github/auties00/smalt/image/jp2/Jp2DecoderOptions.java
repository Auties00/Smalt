package com.github.auties00.smalt.image.jp2;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.internal.check.Checks;

import java.util.Objects;
import java.util.OptionalInt;

/**
 * Holds the settings of {@link Jp2Decoder#decodeChannels(com.github.auties00.smalt.io.MediaSource,
 * Jp2DecoderOptions)}. Instances are immutable and thread-safe; create them with
 * {@link #builder()} or a preset such as {@link #defaults()}.
 */
public final class Jp2DecoderOptions {
    /**
     * The options of {@link #defaults()}.
     */
    private static final Jp2DecoderOptions DEFAULTS = new Builder().build();

    private final MediaLimits limits;

    private final int reduction;

    /**
     * The number of quality layers to decode, or 0 for every layer.
     */
    private final int qualityLayers;

    private Jp2DecoderOptions(Builder builder) {
        this.limits = builder.limits;
        this.reduction = builder.reduction;
        this.qualityLayers = builder.allQualityLayers ? 0 : builder.qualityLayers;
    }

    /**
     * Returns full-resolution decoding of every layer under {@link MediaLimits#DEFAULT}.
     *
     * @return the default options
     */
    public static Jp2DecoderOptions defaults() {
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
     * {@return the resource bounds checked before every allocation; {@link MediaLimits#DEFAULT} by
     * default}
     */
    public MediaLimits limits() {
        return limits;
    }

    /**
     * {@return the number of resolution levels to discard, at least 0; 0 (full resolution) by
     * default}
     *
     * <p>A reduction of {@code r} decodes the image {@code 2^r} times smaller in each dimension,
     * rounded up, at a fraction of the cost. A reduction larger than the codestream allows is
     * lowered to the largest it allows.
     */
    public int reduction() {
        return reduction;
    }

    /**
     * {@return the number of quality layers to decode, at least 1, or empty to decode every layer;
     * empty by default}
     *
     * <p>Decoding only the first layers of a layered codestream gives the coarser image a viewer
     * shows from the start of a progressive stream.
     */
    public OptionalInt qualityLayers() {
        return qualityLayers == 0 ? OptionalInt.empty() : OptionalInt.of(qualityLayers);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Jp2DecoderOptions that
                && limits.equals(that.limits)
                && reduction == that.reduction
                && qualityLayers == that.qualityLayers;
    }

    @Override
    public int hashCode() {
        return Objects.hash(limits, reduction, qualityLayers);
    }

    @Override
    public String toString() {
        return "Jp2DecoderOptions[limits=" + limits + ", reduction=" + reduction + ", qualityLayers="
                + (qualityLayers == 0 ? "all" : qualityLayers) + "]";
    }

    /**
     * A builder of {@link Jp2DecoderOptions}.
     *
     * <p>Setters reject only {@code null}; {@link #build()} checks the ranges. Instances are not
     * thread-safe.
     */
    public static final class Builder {
        private MediaLimits limits = MediaLimits.DEFAULT;

        private int reduction;

        private int qualityLayers;

        private boolean allQualityLayers = true;

        private Builder() {
        }

        private Builder(Jp2DecoderOptions options) {
            this.limits = options.limits;
            this.reduction = options.reduction;
            this.qualityLayers = options.qualityLayers;
            this.allQualityLayers = options.qualityLayers == 0;
        }

        /**
         * Sets the resource bounds; see {@link Jp2DecoderOptions#limits()}.
         *
         * @param limits the value
         * @return this builder
         */
        public Builder limits(MediaLimits limits) {
            this.limits = Objects.requireNonNull(limits, "limits");
            return this;
        }

        /**
         * Sets the number of resolution levels to discard; see {@link Jp2DecoderOptions#reduction()}.
         *
         * @param reduction the value
         * @return this builder
         */
        public Builder reduction(int reduction) {
            this.reduction = reduction;
            return this;
        }

        /**
         * Sets the number of quality layers to decode; see {@link Jp2DecoderOptions#qualityLayers()}.
         *
         * @param qualityLayers the value
         * @return this builder
         */
        public Builder qualityLayers(int qualityLayers) {
            this.qualityLayers = qualityLayers;
            this.allQualityLayers = false;
            return this;
        }

        /**
         * Decodes every quality layer; see {@link Jp2DecoderOptions#qualityLayers()}.
         *
         * @return this builder
         */
        public Builder allQualityLayers() {
            this.qualityLayers = 0;
            this.allQualityLayers = true;
            return this;
        }

        /**
         * Returns the options holding the settings of this builder.
         *
         * @return the options
         * @throws IllegalArgumentException if the reduction is negative or the quality layer count is
         * not positive
         */
        public Jp2DecoderOptions build() {
            Checks.nonNegative("reduction", reduction);
            if (!allQualityLayers) {
                Checks.positive("quality layer count", qualityLayers);
            }
            return new Jp2DecoderOptions(this);
        }
    }
}
