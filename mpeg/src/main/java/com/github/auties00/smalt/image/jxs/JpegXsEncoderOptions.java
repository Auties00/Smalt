package com.github.auties00.smalt.image.jxs;

import com.github.auties00.smalt.image.ColorInfo;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Holds the settings of a {@link JpegXsEncoder}. Instances are immutable and thread-safe; create them with
 * {@link #builder()} or a preset such as {@link #defaults()}.
 */
public final class JpegXsEncoderOptions {
    /**
     * The default rate, in bits per pixel of the sampling grid.
     */
    public static final double DEFAULT_BITS_PER_PIXEL = 4.0;

    /**
     * The default slice height, in lines.
     */
    public static final int DEFAULT_SLICE_HEIGHT = 16;

    /**
     * The default number of horizontal decomposition levels.
     */
    public static final int DEFAULT_HORIZONTAL_LEVELS = 5;

    private static final JpegXsEncoderOptions DEFAULTS = new Builder().build();

    private final JpegXsProfile profile;
    private final double bitsPerPixel;
    private final boolean lossless;
    private final int horizontalLevels;
    private final OptionalInt verticalLevels;
    private final int sliceHeight;
    private final Quantizer quantizer;
    private final boolean colorTransform;
    private final int precinctWidth;
    private final boolean fileFormat;
    private final ColorInfo color;

    private JpegXsEncoderOptions(Builder builder) {
        this.profile = builder.profile;
        this.bitsPerPixel = builder.bitsPerPixel;
        this.lossless = builder.lossless;
        this.horizontalLevels = builder.horizontalLevels;
        this.verticalLevels = builder.verticalLevels;
        this.sliceHeight = builder.sliceHeight;
        this.quantizer = builder.quantizer;
        this.colorTransform = builder.colorTransform;
        this.precinctWidth = builder.precinctWidth;
        this.fileFormat = builder.fileFormat;
        this.color = builder.color;
    }

    /**
     * Returns the general-purpose settings: {@value #DEFAULT_BITS_PER_PIXEL} bits per pixel, the profile chosen from
     * the picture, {@value #DEFAULT_HORIZONTAL_LEVELS} horizontal and the profile's vertical decomposition levels,
     * slices of {@value #DEFAULT_SLICE_HEIGHT} lines, the profile's quantizer, the reversible color transform for
     * R'G'B' pictures and a JXS file.
     *
     * @return the options
     */
    public static JpegXsEncoderOptions defaults() {
        return DEFAULTS;
    }

    /**
     * Returns the {@link #defaults()} at another rate.
     *
     * @param bitsPerPixel the rate; see {@link #bitsPerPixel()}
     * @return the options
     * @throws IllegalArgumentException if {@code bitsPerPixel} is not positive and finite
     */
    public static JpegXsEncoderOptions ofBitsPerPixel(double bitsPerPixel) {
        return new Builder().bitsPerPixel(bitsPerPixel).build();
    }

    /**
     * Returns the {@link #defaults()} with mathematically lossless coding.
     *
     * @return the options
     */
    public static JpegXsEncoderOptions forLossless() {
        return new Builder().lossless(true).build();
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
     * {@return the profile the codestream declares and whose coding tools it uses, or empty to choose it from the
     * picture; empty by default}
     *
     * <p>The automatic choice is MLS.12 or MLS.16 for lossless coding (by precision), Main Bayer for a Bayer image,
     * and otherwise Main 420.12, Main 422.10, Main 444.12 or Main 4444.12 by chroma format and component count where
     * the precision fits the profile, Unrestricted elsewhere. The TDC profiles are not encoded.
     */
    public Optional<JpegXsProfile> profile() {
        return Optional.ofNullable(profile);
    }

    /**
     * {@return the rate of lossy coding in coded bits per pixel of the sampling grid (of each 2x2 quad of a Bayer
     * image), positive; {@value #DEFAULT_BITS_PER_PIXEL} by default}
     *
     * <p>The codestream fits within {@code bitsPerPixel * width * height / 8} bytes unless that budget is smaller than
     * the coarsest quantization needs. A rate above the one the full sublevel of the profile admits (ISO/IEC 21122-2,
     * for example 20 bits per pixel for Main 422.10) is lowered to it. Ignored when {@link #lossless()} is set.
     */
    public double bitsPerPixel() {
        return bitsPerPixel;
    }

    /**
     * {@return whether the codestream is mathematically lossless, its size as the content needs; {@code false} by
     * default}
     */
    public boolean lossless() {
        return lossless;
    }

    /**
     * {@return the largest number of horizontal wavelet decomposition levels, from 1 to 8; {@value
     * #DEFAULT_HORIZONTAL_LEVELS} by default}
     *
     * <p>A picture too narrow for these levels is coded with as many as its width allows.
     */
    public int horizontalLevels() {
        return horizontalLevels;
    }

    /**
     * {@return the number of vertical wavelet decomposition levels, from 0 to 2 and at most the horizontal ones, or
     * empty for the largest the profile allows; empty by default}
     *
     * <p>More levels compress better and need more lines of latency. A 4:2:0 picture needs at least one.
     */
    public OptionalInt verticalLevels() {
        return verticalLevels;
    }

    /**
     * {@return the slice height in lines, rounded up to a multiple of {@code 2^verticalLevels}; {@value
     * #DEFAULT_SLICE_HEIGHT} by default}
     *
     * <p>Slices are coded independently, so shorter slices bound the latency and the spread of an error, at a cost in
     * compression.
     */
    public int sliceHeight() {
        return sliceHeight;
    }

    /**
     * {@return the inverse quantizer the decoder applies, or empty for the profile's; empty by default}
     */
    public Optional<Quantizer> quantizer() {
        return Optional.ofNullable(quantizer);
    }

    /**
     * {@return whether an R'G'B' picture is coded through the reversible color transform; {@code true} by default}
     */
    public boolean colorTransform() {
        return colorTransform;
    }

    /**
     * {@return the precinct width {@code Cw} in units of {@code 8 * 2^horizontalLevels} sampling grid points, or 0 for
     * precincts as wide as the picture (or as the profile allows); 0 by default}
     */
    public int precinctWidth() {
        return precinctWidth;
    }

    /**
     * {@return whether the output is a JXS file (ISO/IEC 21122-3) rather than a raw codestream; {@code true} by
     * default}
     */
    public boolean fileFormat() {
        return fileFormat;
    }

    /**
     * {@return the color description the JXS file declares in its color specification box, or empty to declare the
     * picture's; empty by default}
     *
     * <p>Without one, an R'G'B' picture is declared sRGB and a Y'CbCr picture by its own color description.
     */
    public Optional<ColorInfo> color() {
        return Optional.ofNullable(color);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof JpegXsEncoderOptions that && Objects.equals(profile, that.profile)
                && Double.compare(bitsPerPixel, that.bitsPerPixel) == 0 && lossless == that.lossless
                && horizontalLevels == that.horizontalLevels && verticalLevels.equals(that.verticalLevels)
                && sliceHeight == that.sliceHeight && quantizer == that.quantizer
                && colorTransform == that.colorTransform && precinctWidth == that.precinctWidth
                && fileFormat == that.fileFormat && Objects.equals(color, that.color);
    }

    @Override
    public int hashCode() {
        return Objects.hash(profile, bitsPerPixel, lossless, horizontalLevels, verticalLevels, sliceHeight, quantizer,
                colorTransform, precinctWidth, fileFormat, color);
    }

    @Override
    public String toString() {
        return "JpegXsEncoderOptions[profile=" + profile + ", bitsPerPixel=" + bitsPerPixel + ", lossless=" + lossless
                + ", horizontalLevels=" + horizontalLevels + ", verticalLevels=" + verticalLevels + ", sliceHeight="
                + sliceHeight + ", quantizer=" + quantizer + ", colorTransform=" + colorTransform + ", precinctWidth="
                + precinctWidth + ", fileFormat=" + fileFormat + ", color=" + color + "]";
    }

    /**
     * Enumerates the inverse quantizers of JPEG XS ({@code Qpih}).
     */
    public enum Quantizer {
        /**
         * The deadzone quantizer: reconstruction at the middle of the quantization interval.
         */
        DEADZONE,
        /**
         * The uniform quantizer: reconstruction on a uniform grid spanning the coded range.
         */
        UNIFORM
    }

    /**
     * Builds {@link JpegXsEncoderOptions}. Setters validate their argument at once. Instances are not thread-safe.
     */
    public static final class Builder {
        private JpegXsProfile profile;
        private double bitsPerPixel = DEFAULT_BITS_PER_PIXEL;
        private boolean lossless;
        private int horizontalLevels = DEFAULT_HORIZONTAL_LEVELS;
        private OptionalInt verticalLevels = OptionalInt.empty();
        private int sliceHeight = DEFAULT_SLICE_HEIGHT;
        private Quantizer quantizer;
        private boolean colorTransform = true;
        private int precinctWidth;
        private boolean fileFormat = true;
        private ColorInfo color;

        private Builder() {
        }

        private Builder(JpegXsEncoderOptions options) {
            profile = options.profile;
            bitsPerPixel = options.bitsPerPixel;
            lossless = options.lossless;
            horizontalLevels = options.horizontalLevels;
            verticalLevels = options.verticalLevels;
            sliceHeight = options.sliceHeight;
            quantizer = options.quantizer;
            colorTransform = options.colorTransform;
            precinctWidth = options.precinctWidth;
            fileFormat = options.fileFormat;
            color = options.color;
        }

        /**
         * Sets the profile; see {@link JpegXsEncoderOptions#profile()}.
         *
         * @param profile the value
         * @return this builder
         * @throws IllegalArgumentException if {@code profile} is a TDC profile
         */
        public Builder profile(JpegXsProfile profile) {
            Objects.requireNonNull(profile, "profile");
            if (profile == JpegXsProfile.TDC_444_12 || profile == JpegXsProfile.TDC_MLS_444_12) {
                throw new IllegalArgumentException("the encoder does not code the TDC profiles");
            }
            this.profile = profile;
            return this;
        }

        /**
         * Chooses the profile from the picture; see {@link JpegXsEncoderOptions#profile()}.
         *
         * @return this builder
         */
        public Builder automaticProfile() {
            this.profile = null;
            return this;
        }

        /**
         * Sets the rate; see {@link JpegXsEncoderOptions#bitsPerPixel()}.
         *
         * @param bitsPerPixel the value
         * @return this builder
         * @throws IllegalArgumentException if {@code bitsPerPixel} is not positive and finite
         */
        public Builder bitsPerPixel(double bitsPerPixel) {
            if (!(bitsPerPixel > 0) || Double.isInfinite(bitsPerPixel)) {
                throw new IllegalArgumentException("bitsPerPixel " + bitsPerPixel + " is not positive and finite");
            }
            this.bitsPerPixel = bitsPerPixel;
            return this;
        }

        /**
         * Sets whether coding is lossless; see {@link JpegXsEncoderOptions#lossless()}.
         *
         * @param lossless the value
         * @return this builder
         */
        public Builder lossless(boolean lossless) {
            this.lossless = lossless;
            return this;
        }

        /**
         * Sets the largest number of horizontal decomposition levels; see
         * {@link JpegXsEncoderOptions#horizontalLevels()}.
         *
         * @param levels the value
         * @return this builder
         * @throws IllegalArgumentException if {@code levels} is not in [1, 8]
         */
        public Builder horizontalLevels(int levels) {
            if (levels < 1 || levels > 8) {
                throw new IllegalArgumentException("horizontal levels " + levels + " are not in [1, 8]");
            }
            this.horizontalLevels = levels;
            return this;
        }

        /**
         * Sets the number of vertical decomposition levels; see {@link JpegXsEncoderOptions#verticalLevels()}.
         *
         * @param levels the value
         * @return this builder
         * @throws IllegalArgumentException if {@code levels} is not in [0, 2]
         */
        public Builder verticalLevels(int levels) {
            if (levels < 0 || levels > 2) {
                throw new IllegalArgumentException("vertical levels " + levels + " are not in [0, 2]");
            }
            this.verticalLevels = OptionalInt.of(levels);
            return this;
        }

        /**
         * Uses the largest number of vertical decomposition levels the profile allows; see
         * {@link JpegXsEncoderOptions#verticalLevels()}.
         *
         * @return this builder
         */
        public Builder automaticVerticalLevels() {
            this.verticalLevels = OptionalInt.empty();
            return this;
        }

        /**
         * Sets the slice height; see {@link JpegXsEncoderOptions#sliceHeight()}.
         *
         * @param lines the value
         * @return this builder
         * @throws IllegalArgumentException if {@code lines} is not in [1, 65535]
         */
        public Builder sliceHeight(int lines) {
            if (lines < 1 || lines > 65535) {
                throw new IllegalArgumentException("slice height " + lines + " is not in [1, 65535]");
            }
            this.sliceHeight = lines;
            return this;
        }

        /**
         * Sets the inverse quantizer; see {@link JpegXsEncoderOptions#quantizer()}.
         *
         * @param quantizer the value
         * @return this builder
         */
        public Builder quantizer(Quantizer quantizer) {
            this.quantizer = Objects.requireNonNull(quantizer, "quantizer");
            return this;
        }

        /**
         * Uses the profile's inverse quantizer; see {@link JpegXsEncoderOptions#quantizer()}.
         *
         * @return this builder
         */
        public Builder automaticQuantizer() {
            this.quantizer = null;
            return this;
        }

        /**
         * Sets whether R'G'B' pictures use the reversible color transform; see
         * {@link JpegXsEncoderOptions#colorTransform()}.
         *
         * @param colorTransform the value
         * @return this builder
         */
        public Builder colorTransform(boolean colorTransform) {
            this.colorTransform = colorTransform;
            return this;
        }

        /**
         * Sets the precinct width; see {@link JpegXsEncoderOptions#precinctWidth()}.
         *
         * @param width the value
         * @return this builder
         * @throws IllegalArgumentException if {@code width} is not in [0, 65535]
         */
        public Builder precinctWidth(int width) {
            if (width < 0 || width > 65535) {
                throw new IllegalArgumentException("precinct width " + width + " is not in [0, 65535]");
            }
            this.precinctWidth = width;
            return this;
        }

        /**
         * Sets whether the output is a JXS file; see {@link JpegXsEncoderOptions#fileFormat()}.
         *
         * @param fileFormat the value
         * @return this builder
         */
        public Builder fileFormat(boolean fileFormat) {
            this.fileFormat = fileFormat;
            return this;
        }

        /**
         * Sets the declared color description; see {@link JpegXsEncoderOptions#color()}.
         *
         * @param color the value
         * @return this builder
         */
        public Builder color(ColorInfo color) {
            this.color = Objects.requireNonNull(color, "color");
            return this;
        }

        /**
         * Declares the picture's own color description; see {@link JpegXsEncoderOptions#color()}.
         *
         * @return this builder
         */
        public Builder pictureColor() {
            this.color = null;
            return this;
        }

        /**
         * Builds the options from the settings of this builder.
         *
         * @return the options
         */
        public JpegXsEncoderOptions build() {
            return new JpegXsEncoderOptions(this);
        }
    }
}
