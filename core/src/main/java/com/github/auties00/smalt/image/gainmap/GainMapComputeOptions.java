package com.github.auties00.smalt.image.gainmap;

import java.util.Objects;
import java.util.OptionalDouble;

/**
 * Holds the settings of {@link GainMaps#compute}. Instances are immutable and thread-safe; create them with
 * {@link #builder()} or a preset such as {@link #defaults()}.
 */
public final class GainMapComputeOptions {
    /**
     * The largest downscaling factor of the gain map.
     */
    public static final int MAX_SCALE = 128;

    private static final GainMapComputeOptions DEFAULTS = new GainMapComputeOptions(false, 1, 1,
            GainMapChannel.DEFAULT_OFFSET, GainMapChannel.DEFAULT_OFFSET, true, Double.NaN,
            GainMapOptions.DEFAULT_REFERENCE_WHITE);

    private final boolean multichannel;
    private final int scale;
    private final double gamma;
    private final double baseOffset;
    private final double alternateOffset;
    private final boolean useBaseColorSpace;
    private final double alternateHeadroom;
    private final double referenceWhite;

    private GainMapComputeOptions(boolean multichannel, int scale, double gamma, double baseOffset,
                                  double alternateOffset, boolean useBaseColorSpace, double alternateHeadroom,
                                  double referenceWhite) {
        this.multichannel = multichannel;
        this.scale = scale;
        this.gamma = gamma;
        this.baseOffset = baseOffset;
        this.alternateOffset = alternateOffset;
        this.useBaseColorSpace = useBaseColorSpace;
        this.alternateHeadroom = alternateHeadroom;
        this.referenceWhite = referenceWhite;
    }

    /**
     * Returns the options of a full-size, single-channel gain map with a gamma of 1 and offsets of 1/64,
     * computed in the base color space.
     *
     * @return the default options
     */
    public static GainMapComputeOptions defaults() {
        return DEFAULTS;
    }

    /**
     * Returns a builder initialized with {@link #defaults()}.
     *
     * @return the builder
     */
    public static Builder builder() {
        return new Builder(DEFAULTS);
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
     * {@return whether the gain map has a gain per color channel rather than one per pixel; {@code false} by
     * default}
     *
     * <p>A single-channel gain map holds the gain of the luminance and keeps the base hues; a multichannel one
     * reproduces the hues of the alternate rendition at three times the size.
     */
    public boolean multichannel() {
        return multichannel;
    }

    /**
     * {@return the factor by which the gain map is smaller than the images in each dimension, from 1 to
     * {@value #MAX_SCALE}; 1 by default}
     *
     * <p>The gain map is {@code ceil(width / scale)} by {@code ceil(height / scale)} pixels, each the mean log2
     * gain of the pixels it covers.
     */
    public int scale() {
        return scale;
    }

    /**
     * {@return the gamma the gain map samples are encoded with, positive; 1 by default}
     *
     * <p>A gamma above 1 gives more code values to the small gains.
     */
    public double gamma() {
        return gamma;
    }

    /**
     * {@return the offset added to the linear base values before the ratio is taken, from 0 to 1; 1/64 by
     * default}
     */
    public double baseOffset() {
        return baseOffset;
    }

    /**
     * {@return the offset added to the linear alternate values before the ratio is taken, from 0 to 1; 1/64 by
     * default}
     */
    public double alternateOffset() {
        return alternateOffset;
    }

    /**
     * {@return whether the gain map is computed, and applies, in the color space of the base rendition rather
     * than that of the alternate one; {@code true} by default}
     */
    public boolean useBaseColorSpace() {
        return useBaseColorSpace;
    }

    /**
     * {@return the headroom recorded for the alternate rendition, as a base-2 logarithm, at least 0; empty by
     * default, which records the headroom of its brightest value}
     */
    public OptionalDouble alternateHeadroom() {
        return Double.isNaN(alternateHeadroom) ? OptionalDouble.empty() : OptionalDouble.of(alternateHeadroom);
    }

    /**
     * {@return the luminance of a linear value of 1, in candelas per square meter, from 1 to 10000;
     * {@value GainMapOptions#DEFAULT_REFERENCE_WHITE} by default}
     *
     * <p>PQ and HLG renditions decode to their luminance divided by it.
     */
    public double referenceWhite() {
        return referenceWhite;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof GainMapComputeOptions that && multichannel == that.multichannel
                && scale == that.scale && Double.compare(gamma, that.gamma) == 0
                && Double.compare(baseOffset, that.baseOffset) == 0
                && Double.compare(alternateOffset, that.alternateOffset) == 0
                && useBaseColorSpace == that.useBaseColorSpace
                && Double.compare(alternateHeadroom, that.alternateHeadroom) == 0
                && Double.compare(referenceWhite, that.referenceWhite) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(multichannel, scale, gamma, baseOffset, alternateOffset, useBaseColorSpace,
                alternateHeadroom, referenceWhite);
    }

    @Override
    public String toString() {
        return "GainMapComputeOptions[multichannel=" + multichannel + ", scale=" + scale + ", gamma=" + gamma
                + ", baseOffset=" + baseOffset + ", alternateOffset=" + alternateOffset + ", useBaseColorSpace="
                + useBaseColorSpace + ", alternateHeadroom=" + alternateHeadroom() + ", referenceWhite="
                + referenceWhite + "]";
    }

    /**
     * Builds {@link GainMapComputeOptions}.
     *
     * <p>Setters store their value; {@link #build()} checks the ranges. Instances are not thread-safe.
     */
    public static final class Builder {
        private boolean multichannel;
        private int scale;
        private double gamma;
        private double baseOffset;
        private double alternateOffset;
        private boolean useBaseColorSpace;
        private double alternateHeadroom;
        private boolean automaticAlternateHeadroom;
        private double referenceWhite;

        private Builder(GainMapComputeOptions options) {
            this.multichannel = options.multichannel;
            this.scale = options.scale;
            this.gamma = options.gamma;
            this.baseOffset = options.baseOffset;
            this.alternateOffset = options.alternateOffset;
            this.useBaseColorSpace = options.useBaseColorSpace;
            this.alternateHeadroom = options.alternateHeadroom;
            this.automaticAlternateHeadroom = Double.isNaN(options.alternateHeadroom);
            this.referenceWhite = options.referenceWhite;
        }

        /**
         * Sets whether the gain map has a gain per color channel; see
         * {@link GainMapComputeOptions#multichannel()}.
         *
         * @param multichannel the value
         * @return this builder
         */
        public Builder multichannel(boolean multichannel) {
            this.multichannel = multichannel;
            return this;
        }

        /**
         * Sets the downscaling factor of the gain map; see {@link GainMapComputeOptions#scale()}.
         *
         * @param scale the value
         * @return this builder
         */
        public Builder scale(int scale) {
            this.scale = scale;
            return this;
        }

        /**
         * Sets the gamma of the gain map samples; see {@link GainMapComputeOptions#gamma()}.
         *
         * @param gamma the value
         * @return this builder
         */
        public Builder gamma(double gamma) {
            this.gamma = gamma;
            return this;
        }

        /**
         * Sets the offset of the base values; see {@link GainMapComputeOptions#baseOffset()}.
         *
         * @param baseOffset the value
         * @return this builder
         */
        public Builder baseOffset(double baseOffset) {
            this.baseOffset = baseOffset;
            return this;
        }

        /**
         * Sets the offset of the alternate values; see {@link GainMapComputeOptions#alternateOffset()}.
         *
         * @param alternateOffset the value
         * @return this builder
         */
        public Builder alternateOffset(double alternateOffset) {
            this.alternateOffset = alternateOffset;
            return this;
        }

        /**
         * Sets whether the gain map applies in the base color space; see
         * {@link GainMapComputeOptions#useBaseColorSpace()}.
         *
         * @param useBaseColorSpace the value
         * @return this builder
         */
        public Builder useBaseColorSpace(boolean useBaseColorSpace) {
            this.useBaseColorSpace = useBaseColorSpace;
            return this;
        }

        /**
         * Sets the headroom recorded for the alternate rendition; see
         * {@link GainMapComputeOptions#alternateHeadroom()}.
         *
         * @param alternateHeadroom the value
         * @return this builder
         */
        public Builder alternateHeadroom(double alternateHeadroom) {
            this.alternateHeadroom = alternateHeadroom;
            this.automaticAlternateHeadroom = false;
            return this;
        }

        /**
         * Records the headroom of the brightest alternate value; see
         * {@link GainMapComputeOptions#alternateHeadroom()}.
         *
         * @return this builder
         */
        public Builder automaticAlternateHeadroom() {
            this.automaticAlternateHeadroom = true;
            return this;
        }

        /**
         * Sets the luminance of a linear value of 1; see {@link GainMapComputeOptions#referenceWhite()}.
         *
         * @param referenceWhite the value
         * @return this builder
         */
        public Builder referenceWhite(double referenceWhite) {
            this.referenceWhite = referenceWhite;
            return this;
        }

        /**
         * Builds the options.
         *
         * @return the options
         * @throws IllegalArgumentException if the scale is not from 1 to {@value #MAX_SCALE}, the gamma is not
         * positive and finite, an offset is not from 0 to 1, the alternate headroom is negative or not finite, or
         * the reference white is not from 1 to 10000
         */
        public GainMapComputeOptions build() {
            if (scale < 1 || scale > MAX_SCALE) {
                throw new IllegalArgumentException("invalid gain map scale " + scale);
            }
            if (!(gamma > 0 && gamma < Double.POSITIVE_INFINITY)) {
                throw new IllegalArgumentException("invalid gain map gamma " + gamma);
            }
            if (!(baseOffset >= 0 && baseOffset <= 1) || !(alternateOffset >= 0 && alternateOffset <= 1)) {
                throw new IllegalArgumentException("invalid gain map offsets " + baseOffset + " and "
                        + alternateOffset);
            }
            if (!automaticAlternateHeadroom && !(alternateHeadroom >= 0
                    && alternateHeadroom < Double.POSITIVE_INFINITY)) {
                throw new IllegalArgumentException("invalid alternate headroom " + alternateHeadroom);
            }
            if (!(referenceWhite >= 1 && referenceWhite <= 10000)) {
                throw new IllegalArgumentException("invalid reference white " + referenceWhite);
            }
            return new GainMapComputeOptions(multichannel, scale, gamma, baseOffset, alternateOffset,
                    useBaseColorSpace, automaticAlternateHeadroom ? Double.NaN : alternateHeadroom, referenceWhite);
        }
    }
}
