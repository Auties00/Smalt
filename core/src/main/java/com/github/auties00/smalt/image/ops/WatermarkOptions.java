package com.github.auties00.smalt.image.ops;

import com.github.auties00.smalt.internal.check.Checks;

import java.util.Objects;

/**
 * Holds the settings of {@link ImageCompositing#watermark}. Instances are immutable and
 * thread-safe; create them with {@link #builder()} or a preset such as {@link #defaults()}.
 */
public final class WatermarkOptions {
    private static final WatermarkOptions DEFAULTS = new WatermarkOptions(Anchor.BOTTOM_RIGHT, 16, 16, 0,
            BlendMode.NORMAL, 0.5, false, ResizeFilter.LANCZOS3);

    private final Anchor anchor;
    private final int marginX;
    private final int marginY;
    private final double scale;
    private final CompositeOptions composite;
    private final ResizeFilter filter;

    private WatermarkOptions(Anchor anchor, int marginX, int marginY, double scale, BlendMode mode, double opacity,
                             boolean linearLight, ResizeFilter filter) {
        this.anchor = anchor;
        this.marginX = marginX;
        this.marginY = marginY;
        this.scale = scale;
        this.composite = CompositeOptions.builder().mode(mode).opacity(opacity).linearLight(linearLight).build();
        this.filter = filter;
    }

    /**
     * Returns the mark at its own size in the bottom-right corner, 16 pixels from the edges, blended
     * normally at half opacity.
     *
     * @return the default options
     */
    public static WatermarkOptions defaults() {
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
     * {@return where the mark is anchored on the picture; {@link Anchor#BOTTOM_RIGHT} by default}
     */
    public Anchor anchor() {
        return anchor;
    }

    /**
     * {@return the horizontal distance between the mark and the anchored edge in pixels, at least 0;
     * 16 by default}
     *
     * <p>Horizontally centered anchors ignore it.
     */
    public int marginX() {
        return marginX;
    }

    /**
     * {@return the vertical distance between the mark and the anchored edge in pixels, at least 0;
     * 16 by default}
     *
     * <p>Vertically centered anchors ignore it.
     */
    public int marginY() {
        return marginY;
    }

    /**
     * {@return the width of the mark relative to the picture's width, from 0 to 1, where 0 keeps the
     * mark's own size; 0 by default}
     *
     * <p>A scaled mark keeps its aspect ratio.
     */
    public double scale() {
        return scale;
    }

    /**
     * {@return the factor applied to the mark's alpha, from 0 to 1; 0.5 by default}
     */
    public double opacity() {
        return composite.opacity();
    }

    /**
     * {@return how the mark is blended onto the picture; {@link BlendMode#NORMAL} by default}
     */
    public BlendMode mode() {
        return composite.mode();
    }

    /**
     * {@return whether the mark is blended in linear light rather than on gamma-encoded values;
     * {@code false} by default}
     *
     * @see CompositeOptions#linearLight()
     */
    public boolean linearLight() {
        return composite.linearLight();
    }

    /**
     * {@return the blending of the mark, holding {@link #mode()}, {@link #opacity()} and
     * {@link #linearLight()}}
     */
    public CompositeOptions composite() {
        return composite;
    }

    /**
     * {@return the filter that resizes the mark when {@link #scale()} is not 0;
     * {@link ResizeFilter#LANCZOS3} by default}
     */
    public ResizeFilter filter() {
        return filter;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof WatermarkOptions that
                && anchor == that.anchor
                && marginX == that.marginX
                && marginY == that.marginY
                && Double.compare(scale, that.scale) == 0
                && composite.equals(that.composite)
                && filter == that.filter;
    }

    @Override
    public int hashCode() {
        return Objects.hash(anchor, marginX, marginY, scale, composite, filter);
    }

    @Override
    public String toString() {
        return "WatermarkOptions[anchor=" + anchor + ", marginX=" + marginX + ", marginY=" + marginY + ", scale=" + scale
                + ", composite=" + composite + ", filter=" + filter + "]";
    }

    /**
     * Builds {@link WatermarkOptions}.
     *
     * <p>Setters store their value; {@link #build()} checks the ranges. Instances are not
     * thread-safe.
     */
    public static final class Builder {
        private Anchor anchor;
        private int marginX;
        private int marginY;
        private double scale;
        private BlendMode mode;
        private double opacity;
        private boolean linearLight;
        private ResizeFilter filter;

        private Builder(WatermarkOptions options) {
            this.anchor = options.anchor;
            this.marginX = options.marginX;
            this.marginY = options.marginY;
            this.scale = options.scale;
            this.mode = options.mode();
            this.opacity = options.opacity();
            this.linearLight = options.linearLight();
            this.filter = options.filter;
        }

        /**
         * Sets where the mark is anchored; see {@link WatermarkOptions#anchor()}.
         *
         * @param anchor the value
         * @return this builder
         */
        public Builder anchor(Anchor anchor) {
            this.anchor = Objects.requireNonNull(anchor, "anchor");
            return this;
        }

        /**
         * Sets the margins; see {@link WatermarkOptions#marginX()} and {@link WatermarkOptions#marginY()}.
         *
         * @param marginX the horizontal margin
         * @param marginY the vertical margin
         * @return this builder
         */
        public Builder margins(int marginX, int marginY) {
            this.marginX = marginX;
            this.marginY = marginY;
            return this;
        }

        /**
         * Sets the width of the mark relative to the picture's width; see
         * {@link WatermarkOptions#scale()}.
         *
         * @param scale the value
         * @return this builder
         */
        public Builder scale(double scale) {
            this.scale = scale;
            return this;
        }

        /**
         * Sets the opacity of the mark; see {@link WatermarkOptions#opacity()}.
         *
         * @param opacity the value
         * @return this builder
         */
        public Builder opacity(double opacity) {
            this.opacity = opacity;
            return this;
        }

        /**
         * Sets how the mark is blended; see {@link WatermarkOptions#mode()}.
         *
         * @param mode the value
         * @return this builder
         */
        public Builder mode(BlendMode mode) {
            this.mode = Objects.requireNonNull(mode, "mode");
            return this;
        }

        /**
         * Sets whether the mark is blended in linear light; see {@link WatermarkOptions#linearLight()}.
         *
         * @param linearLight the value
         * @return this builder
         */
        public Builder linearLight(boolean linearLight) {
            this.linearLight = linearLight;
            return this;
        }

        /**
         * Sets the filter that resizes the mark; see {@link WatermarkOptions#filter()}.
         *
         * @param filter the value
         * @return this builder
         */
        public Builder filter(ResizeFilter filter) {
            this.filter = Objects.requireNonNull(filter, "filter");
            return this;
        }

        /**
         * Returns options holding the settings of this builder.
         *
         * @return the options
         * @throws IllegalArgumentException if a margin is negative, or the scale or the opacity is
         * NaN or outside 0..1
         */
        public WatermarkOptions build() {
            Checks.nonNegative("horizontal margin", marginX);
            Checks.nonNegative("vertical margin", marginY);
            Checks.inRange("scale", scale, 0.0, 1.0);
            Checks.inRange("opacity", opacity, 0.0, 1.0);
            return new WatermarkOptions(anchor, marginX, marginY, scale, mode, opacity, linearLight, filter);
        }
    }
}
