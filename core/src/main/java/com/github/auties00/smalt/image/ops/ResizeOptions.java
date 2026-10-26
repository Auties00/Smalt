package com.github.auties00.smalt.image.ops;

import java.util.Objects;

/**
 * Holds the settings of {@link ImageOps#resize(com.github.auties00.smalt.image.Picture, int, int, ResizeOptions)}
 * and the other resampling operations of {@link ImageOps}. Instances are immutable and thread-safe; create them with
 * {@link #builder()} or a preset such as {@link #defaults()}.
 */
public final class ResizeOptions {
    private static final ResizeOptions DEFAULTS = new ResizeOptions(ResizeFilter.LANCZOS3, false);

    private final ResizeFilter filter;
    private final boolean linearLight;

    private ResizeOptions(ResizeFilter filter, boolean linearLight) {
        this.filter = filter;
        this.linearLight = linearLight;
    }

    /**
     * Returns {@link ResizeFilter#LANCZOS3} resampling of the gamma-encoded samples.
     *
     * @return the default options
     */
    public static ResizeOptions defaults() {
        return DEFAULTS;
    }

    /**
     * Returns the default options with another filter.
     *
     * @param filter the reconstruction filter
     * @return the options
     */
    public static ResizeOptions of(ResizeFilter filter) {
        return builder().filter(filter).build();
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
     * {@return the reconstruction filter; {@link ResizeFilter#LANCZOS3} by default}
     */
    public ResizeFilter filter() {
        return filter;
    }

    /**
     * {@return whether resampling happens in linear light; {@code false} by default}
     *
     * <p>In linear light the gamma-encoded R'G'B' samples are decoded with the sRGB transfer
     * function before filtering and the filtered values are encoded to the nearest sample after,
     * which keeps the brightness of fine detail when reducing (a black and white checkerboard
     * halves to the sRGB gray 188 rather than 128) at the cost of the conversions. Alpha is
     * filtered as it is. A Y'CbCr picture is converted to packed RGBA at its depth, resampled, and
     * converted back to its chroma layout and color description, its subsampled chroma weighted by
     * alpha when it has an alpha plane (see {@link ChromaDownsampling#ALPHA_WEIGHTED}), so the
     * transparent pixels around visible ones do not tint them. {@link ResizeFilter#NEAREST}
     * never blends, so it ignores this setting.
     */
    public boolean linearLight() {
        return linearLight;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ResizeOptions that && filter == that.filter && linearLight == that.linearLight;
    }

    @Override
    public int hashCode() {
        return Objects.hash(filter, linearLight);
    }

    @Override
    public String toString() {
        return "ResizeOptions[filter=" + filter + ", linearLight=" + linearLight + "]";
    }

    /**
     * Builds {@link ResizeOptions}.
     *
     * <p>Every value a setter accepts is valid, so {@link #build()} never fails. Instances are not
     * thread-safe.
     */
    public static final class Builder {
        private ResizeFilter filter;
        private boolean linearLight;

        private Builder(ResizeOptions options) {
            this.filter = options.filter;
            this.linearLight = options.linearLight;
        }

        /**
         * Sets the reconstruction filter; see {@link ResizeOptions#filter()}.
         *
         * @param filter the value
         * @return this builder
         */
        public Builder filter(ResizeFilter filter) {
            this.filter = Objects.requireNonNull(filter, "filter");
            return this;
        }

        /**
         * Sets whether resampling happens in linear light; see {@link ResizeOptions#linearLight()}.
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
         */
        public ResizeOptions build() {
            return new ResizeOptions(filter, linearLight);
        }
    }
}
