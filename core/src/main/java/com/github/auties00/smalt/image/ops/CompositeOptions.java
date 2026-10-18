package com.github.auties00.smalt.image.ops;

import com.github.auties00.smalt.internal.check.Checks;

import java.util.Objects;

/**
 * Holds the settings of {@link ImageCompositing#composite}. Instances are immutable and
 * thread-safe; create them with {@link #builder()} or a preset such as {@link #defaults()}.
 */
public final class CompositeOptions {
    private static final CompositeOptions DEFAULTS = new CompositeOptions(BlendMode.NORMAL, 1, false);

    private final BlendMode mode;
    private final double opacity;
    private final boolean linearLight;

    private CompositeOptions(BlendMode mode, double opacity, boolean linearLight) {
        this.mode = mode;
        this.opacity = opacity;
        this.linearLight = linearLight;
    }

    /**
     * Returns normal blending of a fully opaque overlay on gamma-encoded values.
     *
     * @return the default options
     */
    public static CompositeOptions defaults() {
        return DEFAULTS;
    }

    /**
     * Returns the default options with another blend mode.
     *
     * @param mode the blend mode
     * @return the options
     */
    public static CompositeOptions of(BlendMode mode) {
        return builder().mode(mode).build();
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
     * {@return the blend mode; {@link BlendMode#NORMAL} by default}
     */
    public BlendMode mode() {
        return mode;
    }

    /**
     * {@return the factor applied to the overlay's alpha, from 0 to 1; 1 by default}
     */
    public double opacity() {
        return opacity;
    }

    /**
     * {@return whether blending happens in linear light; {@code false} by default}
     *
     * <p>In linear light the gamma-encoded samples are decoded with the sRGB transfer function
     * before blending and encoded again after, which gives physically correct mixes of light (no
     * dark fringes where colors meet) at the cost of the conversions.
     */
    public boolean linearLight() {
        return linearLight;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof CompositeOptions that
                && mode == that.mode
                && Double.compare(opacity, that.opacity) == 0
                && linearLight == that.linearLight;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mode, opacity, linearLight);
    }

    @Override
    public String toString() {
        return "CompositeOptions[mode=" + mode + ", opacity=" + opacity + ", linearLight=" + linearLight + "]";
    }

    /**
     * Builds {@link CompositeOptions}.
     *
     * <p>Setters store their value; {@link #build()} checks the ranges. Instances are not
     * thread-safe.
     */
    public static final class Builder {
        private BlendMode mode;
        private double opacity;
        private boolean linearLight;

        private Builder(CompositeOptions options) {
            this.mode = options.mode;
            this.opacity = options.opacity;
            this.linearLight = options.linearLight;
        }

        /**
         * Sets the blend mode; see {@link CompositeOptions#mode()}.
         *
         * @param mode the value
         * @return this builder
         */
        public Builder mode(BlendMode mode) {
            this.mode = Objects.requireNonNull(mode, "mode");
            return this;
        }

        /**
         * Sets the factor applied to the overlay's alpha; see {@link CompositeOptions#opacity()}.
         *
         * @param opacity the value
         * @return this builder
         */
        public Builder opacity(double opacity) {
            this.opacity = opacity;
            return this;
        }

        /**
         * Sets whether blending happens in linear light; see {@link CompositeOptions#linearLight()}.
         *
         * @param linearLight the value
         * @return this builder
         */
        public Builder linearLight(boolean linearLight) {
            this.linearLight = linearLight;
            return this;
        }

        /**
         * Returns options holding the settings of this builder.
         *
         * @return the options
         * @throws IllegalArgumentException if the opacity is NaN or outside 0..1
         */
        public CompositeOptions build() {
            Checks.inRange("opacity", opacity, 0.0, 1.0);
            return new CompositeOptions(mode, opacity, linearLight);
        }
    }
}
