package com.github.auties00.smalt.image.ops;

import com.github.auties00.smalt.internal.check.Checks;

import java.util.Objects;

/**
 * Holds the settings of {@link ImageQuantization#quantize}. Instances are immutable and
 * thread-safe; create them with {@link #builder()} or a preset such as {@link #defaults()}.
 */
public final class QuantizeOptions {
    private static final QuantizeOptions DEFAULTS = new QuantizeOptions(256, QuantizeMethod.MEDIAN_CUT,
            Dithering.FLOYD_STEINBERG, 10, 128);

    private final int colors;
    private final QuantizeMethod method;
    private final Dithering dithering;
    private final int sampleFactor;
    private final int alphaThreshold;

    private QuantizeOptions(int colors, QuantizeMethod method, Dithering dithering, int sampleFactor, int alphaThreshold) {
        this.colors = colors;
        this.method = method;
        this.dithering = dithering;
        this.sampleFactor = sampleFactor;
        this.alphaThreshold = alphaThreshold;
    }

    /**
     * Returns a palette of up to 256 colors built by median cut and mapped with Floyd-Steinberg
     * dithering.
     *
     * @return the default options
     */
    public static QuantizeOptions defaults() {
        return DEFAULTS;
    }

    /**
     * Returns the default options with another palette size.
     *
     * @param colors the largest number of palette entries, from 2 to 256
     * @return the options
     * @throws IllegalArgumentException if {@code colors} is outside 2..256
     */
    public static QuantizeOptions ofColors(int colors) {
        return builder().colors(colors).build();
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
     * {@return the largest number of palette entries, counting the transparent entry when the
     * picture needs one, from 2 to 256; 256 by default}
     */
    public int colors() {
        return colors;
    }

    /**
     * {@return the algorithm that builds the palette when the picture has more colors than it can
     * hold; {@link QuantizeMethod#MEDIAN_CUT} by default}
     */
    public QuantizeMethod method() {
        return method;
    }

    /**
     * {@return how pixels are mapped to palette entries; {@link Dithering#FLOYD_STEINBERG} by default}
     *
     * <p>A picture whose colors all fit the palette is never dithered.
     */
    public Dithering dithering() {
        return dithering;
    }

    /**
     * {@return the NeuQuant sampling factor, from 1 to 30; 10 by default}
     *
     * <p>The network trains on one color in this many; only {@link QuantizeMethod#NEUQUANT} reads
     * it.
     */
    public int sampleFactor() {
        return sampleFactor;
    }

    /**
     * {@return the alpha at and above which a pixel is opaque, from 1 to 255; 128 by default}
     *
     * <p>Pixels below it take the transparent entry.
     */
    public int alphaThreshold() {
        return alphaThreshold;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof QuantizeOptions that
                && colors == that.colors
                && method == that.method
                && dithering == that.dithering
                && sampleFactor == that.sampleFactor
                && alphaThreshold == that.alphaThreshold;
    }

    @Override
    public int hashCode() {
        return Objects.hash(colors, method, dithering, sampleFactor, alphaThreshold);
    }

    @Override
    public String toString() {
        return "QuantizeOptions[colors=" + colors + ", method=" + method + ", dithering=" + dithering + ", sampleFactor="
                + sampleFactor + ", alphaThreshold=" + alphaThreshold + "]";
    }

    /**
     * Builds {@link QuantizeOptions}.
     *
     * <p>Setters store their value; {@link #build()} checks the ranges. Instances are not
     * thread-safe.
     */
    public static final class Builder {
        private int colors;
        private QuantizeMethod method;
        private Dithering dithering;
        private int sampleFactor;
        private int alphaThreshold;

        private Builder(QuantizeOptions options) {
            this.colors = options.colors;
            this.method = options.method;
            this.dithering = options.dithering;
            this.sampleFactor = options.sampleFactor;
            this.alphaThreshold = options.alphaThreshold;
        }

        /**
         * Sets the largest number of palette entries; see {@link QuantizeOptions#colors()}.
         *
         * @param colors the value
         * @return this builder
         */
        public Builder colors(int colors) {
            this.colors = colors;
            return this;
        }

        /**
         * Sets the palette algorithm; see {@link QuantizeOptions#method()}.
         *
         * @param method the value
         * @return this builder
         */
        public Builder method(QuantizeMethod method) {
            this.method = Objects.requireNonNull(method, "method");
            return this;
        }

        /**
         * Sets the dithering; see {@link QuantizeOptions#dithering()}.
         *
         * @param dithering the value
         * @return this builder
         */
        public Builder dithering(Dithering dithering) {
            this.dithering = Objects.requireNonNull(dithering, "dithering");
            return this;
        }

        /**
         * Sets the NeuQuant sampling factor; see {@link QuantizeOptions#sampleFactor()}.
         *
         * @param sampleFactor the value
         * @return this builder
         */
        public Builder sampleFactor(int sampleFactor) {
            this.sampleFactor = sampleFactor;
            return this;
        }

        /**
         * Sets the alpha threshold; see {@link QuantizeOptions#alphaThreshold()}.
         *
         * @param alphaThreshold the value
         * @return this builder
         */
        public Builder alphaThreshold(int alphaThreshold) {
            this.alphaThreshold = alphaThreshold;
            return this;
        }

        /**
         * Returns options holding the settings of this builder.
         *
         * @return the options
         * @throws IllegalArgumentException if the palette size is outside 2..256, the sampling
         * factor outside 1..30 or the alpha threshold outside 1..255
         */
        public QuantizeOptions build() {
            Checks.inRange("palette size", colors, 2, 256);
            Checks.inRange("sampling factor", sampleFactor, 1, 30);
            Checks.inRange("alpha threshold", alphaThreshold, 1, 255);
            return new QuantizeOptions(colors, method, dithering, sampleFactor, alphaThreshold);
        }
    }
}
