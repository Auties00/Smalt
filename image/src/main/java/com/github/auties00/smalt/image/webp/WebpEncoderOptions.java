package com.github.auties00.smalt.image.webp;

import com.github.auties00.smalt.image.color.ColorProfile;
import com.github.auties00.smalt.internal.check.Checks;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Holds the settings of a {@link WebpEncoder}. Instances are immutable and thread-safe; create them
 * with {@link #builder()} or a preset such as {@link #defaults()}.
 */
public final class WebpEncoderOptions {
    /**
     * The options of {@link #defaults()}.
     */
    private static final WebpEncoderOptions DEFAULTS = new Builder().build();

    /**
     * The options of {@link #lossless()}.
     */
    private static final WebpEncoderOptions LOSSLESS = new Builder().lossless(true).build();

    private final boolean lossless;

    private final int quality;

    private final int method;

    private final int alphaQuality;

    private final AlphaFilter alphaFilter;

    private final boolean exact;

    /**
     * The file size in bytes lossy coding stays within, or 0 for none.
     */
    private final int targetSize;

    private final boolean extendedFormat;

    private final ColorProfile colorProfile;

    private WebpEncoderOptions(Builder builder) {
        this.lossless = builder.lossless;
        this.quality = builder.quality;
        this.method = builder.method;
        this.alphaQuality = builder.alphaQuality;
        this.alphaFilter = builder.alphaFilter;
        this.exact = builder.exact;
        this.targetSize = builder.targetSize;
        this.extendedFormat = builder.extendedFormat;
        this.colorProfile = builder.colorProfile;
    }

    /**
     * Returns lossy coding at quality 75, method 4, exact alpha with {@link AlphaFilter#FAST}
     * filtering, no target size and the simple container.
     *
     * @return the default options
     */
    public static WebpEncoderOptions defaults() {
        return DEFAULTS;
    }

    /**
     * Returns the {@link #defaults()} at another quality.
     *
     * @param quality the quality, from 0 (smallest) to 100 (best)
     * @return the options
     * @throws IllegalArgumentException if {@code quality} is not in [0, 100]
     */
    public static WebpEncoderOptions lossy(int quality) {
        return new Builder().quality(quality).build();
    }

    /**
     * Returns lossless coding at effort 75 and method 4.
     *
     * @return the options
     */
    public static WebpEncoderOptions lossless() {
        return LOSSLESS;
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
     * {@return whether the image is coded losslessly (VP8L) rather than lossily (VP8); {@code false}
     * by default}
     *
     * <p>In lossy mode, transparency, if any, is coded as a separate alpha plane ({@code ALPH})
     * whose precision the {@linkplain #alphaQuality() alpha quality} sets.
     */
    public boolean isLossless() {
        return lossless;
    }

    /**
     * {@return the lossy quality, or the compression effort when the image is coded losslessly,
     * from 0 (smallest or fastest) to 100 (best or smallest); 75 by default}
     *
     * <p>The quality is on the scale every Smalt image encoder shares, the scale of
     * {@code cwebp -q}. A higher effort searches harder for a smaller lossless file.
     */
    public int quality() {
        return quality;
    }

    /**
     * {@return the speed and size trade-off, from 0 (fastest) to 6 (smallest); 4 by default}
     */
    public int method() {
        return method;
    }

    /**
     * {@return the precision of a lossy image's alpha plane, from 0 to 100, where 100 keeps alpha
     * exact and lower values quantize it to fewer levels; 100 by default}
     */
    public int alphaQuality() {
        return alphaQuality;
    }

    /**
     * {@return the prediction filter selection of a lossy image's alpha plane;
     * {@link AlphaFilter#FAST} by default}
     */
    public AlphaFilter alphaFilter() {
        return alphaFilter;
    }

    /**
     * {@return whether the color of fully transparent pixels is preserved rather than replaced by
     * values that compress better; {@code false} by default}
     */
    public boolean exact() {
        return exact;
    }

    /**
     * {@return the file size in bytes, at least 1, that lossy coding stays within by lowering the
     * quality, or empty when the quality is used as given; empty by default}
     *
     * <p>Lossless coding ignores it.
     */
    public OptionalInt targetSize() {
        return targetSize == 0 ? OptionalInt.empty() : OptionalInt.of(targetSize);
    }

    /**
     * {@return whether the extended container ({@code VP8X}) is always written; {@code false} by
     * default}
     *
     * <p>Transparency in lossy mode and an embedded {@linkplain #colorProfile() color profile} need
     * the extended container, so such an image has it whatever this setting says.
     */
    public boolean extendedFormat() {
        return extendedFormat;
    }

    /**
     * {@return the ICC profile embedded in the output, or empty when none is embedded; empty by
     * default}
     *
     * <p>The profile tells readers which color space the pixels are in; it does not convert them.
     * Without one, readers take the pixels as sRGB. It is written as an {@code ICCP} chunk. A
     * high-dynamic-range profile is written in its ICC form, whose {@code cicp} tag carries its PQ
     * or HLG code points.
     */
    public Optional<ColorProfile> colorProfile() {
        return Optional.ofNullable(colorProfile);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof WebpEncoderOptions that
                && lossless == that.lossless
                && quality == that.quality
                && method == that.method
                && alphaQuality == that.alphaQuality
                && alphaFilter == that.alphaFilter
                && exact == that.exact
                && targetSize == that.targetSize
                && extendedFormat == that.extendedFormat
                && Objects.equals(colorProfile, that.colorProfile);
    }

    @Override
    public int hashCode() {
        return Objects.hash(lossless, quality, method, alphaQuality, alphaFilter, exact, targetSize, extendedFormat,
                colorProfile);
    }

    @Override
    public String toString() {
        return "WebpEncoderOptions[lossless=" + lossless + ", quality=" + quality + ", method=" + method
                + ", alphaQuality=" + alphaQuality + ", alphaFilter=" + alphaFilter + ", exact=" + exact
                + ", targetSize=" + targetSize + ", extendedFormat=" + extendedFormat
                + (colorProfile == null ? "" : ", colorProfile=" + colorProfile.description().orElse("unnamed"))
                + "]";
    }

    /**
     * Enumerates the prediction filter selections of a lossy image's alpha plane, as
     * {@code cwebp -alpha_filter} names them.
     */
    public enum AlphaFilter {
        /**
         * The alpha plane is coded unfiltered.
         */
        NONE,

        /**
         * One filter is chosen from an estimate of the alpha plane's gradients.
         */
        FAST,

        /**
         * Every filter is tried and the one giving the smallest plane is kept.
         */
        BEST
    }

    /**
     * A builder of {@link WebpEncoderOptions}.
     *
     * <p>Setters reject only {@code null}; {@link #build()} checks the ranges and the color
     * profile. Instances are not thread-safe.
     */
    public static final class Builder {
        private boolean lossless;

        private int quality = 75;

        private int method = 4;

        private int alphaQuality = 100;

        private AlphaFilter alphaFilter = AlphaFilter.FAST;

        private boolean exact;

        private int targetSize;

        private boolean hasTargetSize;

        private boolean extendedFormat;

        private ColorProfile colorProfile;

        private Builder() {
        }

        private Builder(WebpEncoderOptions options) {
            this.lossless = options.lossless;
            this.quality = options.quality;
            this.method = options.method;
            this.alphaQuality = options.alphaQuality;
            this.alphaFilter = options.alphaFilter;
            this.exact = options.exact;
            this.targetSize = options.targetSize;
            this.hasTargetSize = options.targetSize != 0;
            this.extendedFormat = options.extendedFormat;
            this.colorProfile = options.colorProfile;
        }

        /**
         * Sets whether the image is coded losslessly; see {@link WebpEncoderOptions#isLossless()}.
         * The quality is kept, and becomes the effort when coding losslessly.
         *
         * @param lossless the value
         * @return this builder
         */
        public Builder lossless(boolean lossless) {
            this.lossless = lossless;
            return this;
        }

        /**
         * Sets the lossy quality, or the effort when coding losslessly; see
         * {@link WebpEncoderOptions#quality()}.
         *
         * @param quality the value
         * @return this builder
         */
        public Builder quality(int quality) {
            this.quality = quality;
            return this;
        }

        /**
         * Sets the speed and size trade-off; see {@link WebpEncoderOptions#method()}.
         *
         * @param method the value
         * @return this builder
         */
        public Builder method(int method) {
            this.method = method;
            return this;
        }

        /**
         * Sets the precision of a lossy alpha plane; see {@link WebpEncoderOptions#alphaQuality()}.
         *
         * @param alphaQuality the value
         * @return this builder
         */
        public Builder alphaQuality(int alphaQuality) {
            this.alphaQuality = alphaQuality;
            return this;
        }

        /**
         * Sets the prediction filter selection of a lossy alpha plane; see
         * {@link WebpEncoderOptions#alphaFilter()}.
         *
         * @param alphaFilter the value
         * @return this builder
         */
        public Builder alphaFilter(AlphaFilter alphaFilter) {
            this.alphaFilter = Objects.requireNonNull(alphaFilter, "alphaFilter");
            return this;
        }

        /**
         * Sets whether the color of fully transparent pixels is preserved; see
         * {@link WebpEncoderOptions#exact()}.
         *
         * @param exact the value
         * @return this builder
         */
        public Builder exact(boolean exact) {
            this.exact = exact;
            return this;
        }

        /**
         * Sets the file size lossy coding stays within; see {@link WebpEncoderOptions#targetSize()}.
         *
         * @param targetSize the value, in bytes
         * @return this builder
         */
        public Builder targetSize(int targetSize) {
            this.targetSize = targetSize;
            this.hasTargetSize = true;
            return this;
        }

        /**
         * Removes the target file size, so lossy coding uses the quality as given; see
         * {@link WebpEncoderOptions#targetSize()}.
         *
         * @return this builder
         */
        public Builder noTargetSize() {
            this.targetSize = 0;
            this.hasTargetSize = false;
            return this;
        }

        /**
         * Sets whether the extended container is always written; see
         * {@link WebpEncoderOptions#extendedFormat()}.
         *
         * @param extendedFormat the value
         * @return this builder
         */
        public Builder extendedFormat(boolean extendedFormat) {
            this.extendedFormat = extendedFormat;
            return this;
        }

        /**
         * Sets the ICC profile embedded in the output; see {@link WebpEncoderOptions#colorProfile()}.
         *
         * @param colorProfile the value, or {@code null} for none
         * @return this builder
         */
        public Builder colorProfile(ColorProfile colorProfile) {
            this.colorProfile = colorProfile;
            return this;
        }

        /**
         * Returns the options holding the settings of this builder.
         *
         * @return the options
         * @throws IllegalArgumentException if the quality is not in [0, 100], the method is not in
         * [0, 6], the alpha quality is not in [0, 100] or the target size is not positive
         */
        public WebpEncoderOptions build() {
            Checks.inRange("quality", quality, 0, 100);
            Checks.inRange("method", method, 0, 6);
            Checks.inRange("alpha quality", alphaQuality, 0, 100);
            if (hasTargetSize) {
                Checks.positive("target size", targetSize);
            }
            return new WebpEncoderOptions(this);
        }
    }
}
