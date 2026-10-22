package com.github.auties00.smalt.image.webp;

import com.github.auties00.smalt.image.color.ColorProfile;
import com.github.auties00.smalt.internal.check.Checks;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
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

    /**
     * The PSNR in dB lossy coding aims at, or 0 for none.
     */
    private final double targetPsnr;

    /**
     * The number of entropy-analysis passes, or 0 for automatic.
     */
    private final int passes;

    private final int nearLossless;

    private final boolean sharpYuv;

    private final boolean dithering;

    private final int snsStrength;

    private final int filterStrength;

    private final int filterSharpness;

    private final boolean simpleFilter;

    private final boolean autoFilter;

    private final int segments;

    private final int tokenPartitions;

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
        this.targetPsnr = builder.targetPsnr;
        this.passes = builder.passes;
        this.nearLossless = builder.nearLossless;
        this.sharpYuv = builder.sharpYuv;
        this.dithering = builder.dithering;
        this.snsStrength = builder.snsStrength;
        this.filterStrength = builder.filterStrength;
        this.filterSharpness = builder.filterSharpness;
        this.simpleFilter = builder.simpleFilter;
        this.autoFilter = builder.autoFilter;
        this.segments = builder.segments;
        this.tokenPartitions = builder.tokenPartitions;
        this.extendedFormat = builder.extendedFormat;
        this.colorProfile = builder.colorProfile;
    }

    /**
     * Returns lossy coding at quality 75, method 4, exact alpha with {@link AlphaFilter#FAST}
     * filtering, no target size and the simple container; the lossy tuning is that of
     * {@link Preset#DEFAULT}.
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
     * Returns the {@link #defaults()} with the lossy tuning of a kind of content: its
     * {@linkplain #snsStrength() spatial noise shaping}, {@linkplain #filterStrength() filter
     * strength}, {@linkplain #filterSharpness() filter sharpness}, {@linkplain #dithering()
     * dithering} and {@linkplain #segments() segments}, as {@link Preset} lists them.
     *
     * @apiNote The result is an ordinary options value: change the quality or any other setting
     * through {@link #toBuilder()}.
     * @param preset the kind of content
     * @return the options
     */
    public static WebpEncoderOptions forPreset(Preset preset) {
        Objects.requireNonNull(preset, "preset");
        var builder = new Builder();
        switch (preset) {
            case DEFAULT -> {
            }
            case PICTURE -> builder.snsStrength(80).filterSharpness(4).filterStrength(35);
            case PHOTO -> builder.snsStrength(80).filterSharpness(3).filterStrength(30).dithering(true);
            case DRAWING -> builder.snsStrength(25).filterSharpness(6).filterStrength(10);
            case ICON -> builder.snsStrength(0).filterStrength(0);
            case TEXT -> builder.snsStrength(0).filterStrength(0).segments(2);
        }
        return builder.build();
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
     * {@code cwebp -q}. A higher effort searches harder for a smaller lossless file. With a
     * {@linkplain #targetPsnr() target PSNR} it is the quality the search starts from.
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
     * <p>Lossless coding ignores it. It cannot be combined with a {@linkplain #targetPsnr() target
     * PSNR}.
     */
    public OptionalInt targetSize() {
        return targetSize == 0 ? OptionalInt.empty() : OptionalInt.of(targetSize);
    }

    /**
     * {@return the peak signal-to-noise ratio in dB, above 0 and at most 99, that lossy coding
     * aims at by adjusting the quality over the {@linkplain #passes() passes}, or empty when the
     * quality is used as given; empty by default}
     *
     * <p>The ratio is measured between the source and the coded Y'CbCr samples, all three planes
     * together, before the loop filter. The search moves the quality from {@link #quality()} by
     * secant steps and stops once a step falls below 0.4 or the passes run out, so the result
     * approaches the target without a guarantee to reach it. Lossless coding ignores it. It cannot
     * be combined with a {@linkplain #targetSize() target size}.
     */
    public OptionalDouble targetPsnr() {
        return targetPsnr == 0 ? OptionalDouble.empty() : OptionalDouble.of(targetPsnr);
    }

    /**
     * {@return the number of entropy-analysis passes of lossy coding, from 1 to 10, or empty for
     * the automatic count: 6 with a {@linkplain #targetPsnr() target PSNR}, 1 otherwise; empty by
     * default}
     *
     * <p>Each pass refines the token probabilities the next one codes with and, with a target
     * PSNR, moves the quality towards it. More passes cost proportionally more time.
     */
    public OptionalInt passes() {
        return passes == 0 ? OptionalInt.empty() : OptionalInt.of(passes);
    }

    /**
     * {@return the near-lossless level of lossless coding, from 0 to 100, where 100 keeps every
     * pixel exact and lower levels allow larger changes for a smaller file; 100 by default}
     *
     * <p>Below 100, every channel of a pixel off the image border may change by less than
     * {@code 1 << (5 - level / 20)}: pixels whose neighbors differ from them are rounded, or their
     * prediction residuals quantized, while smooth areas and fully transparent or fully opaque
     * alpha stay exact. Images that fit a palette are not changed, and neither are images smaller
     * than 64 pixels in both directions when no predictor is used. A translucent pixel whose
     * quantized residual leaves it fully transparent then loses its color unless coding is
     * {@linkplain #exact() exact}. With exact coding, and at {@linkplain #method() method} 0, which
     * always codes with a predictor or a palette, the prediction residuals are not quantized, so an
     * image coded with a predictor stays lossless. Lossy coding ignores it.
     */
    public int nearLossless() {
        return nearLossless;
    }

    /**
     * {@return whether lossy coding converts RGB to Y'CbCr with the iterative sharp conversion;
     * {@code false} by default}
     *
     * <p>The sharp conversion chooses luma and chroma so that the upsampled result matches the
     * source in a linear-light domain, which keeps edges between saturated colors crisp at
     * several times the cost of the plain conversion. It does not weigh chroma by alpha. It takes
     * precedence over {@linkplain #dithering() dithering}. It applies only to RGB input; Y'CbCr
     * pictures already in the lossy layout are coded as they are, and lossless coding ignores it.
     */
    public boolean sharpYuv() {
        return sharpYuv;
    }

    /**
     * {@return whether lossy coding adds pseudo-random dithering to the rounding of the RGB to
     * Y'CbCr conversion, whose amplitude falls from one unit at quality 0 to half a unit at quality
     * 100; {@code false} by default, {@code true} in {@link Preset#PHOTO}}
     *
     * <p>The dithering sequence is fixed, so the output stays deterministic; the conversion then
     * runs on one thread. It applies only to RGB input and not with {@linkplain #sharpYuv() sharp
     * conversion}; lossless coding ignores it.
     */
    public boolean dithering() {
        return dithering;
    }

    /**
     * {@return the spatial noise shaping strength of lossy coding, from 0 to 100: how much more
     * coarsely busy areas are quantized than flat ones, where 0 quantizes evenly; 50 by default}
     */
    public int snsStrength() {
        return snsStrength;
    }

    /**
     * {@return the deblocking loop filter strength of lossy coding, from 0 (off) to 100
     * (strongest); 60 by default}
     *
     * <p>With {@linkplain #autoFilter() automatic filtering} it is only the starting point of the
     * search.
     */
    public int filterStrength() {
        return filterStrength;
    }

    /**
     * {@return the loop filter sharpness of lossy coding, from 0 (smoothest) to 7 (sharpest): how
     * much the filter spares detail at block edges; 0 by default}
     */
    public int filterSharpness() {
        return filterSharpness;
    }

    /**
     * {@return whether lossy coding signals the simple loop filter, which filters luma only and
     * costs a decoder less, instead of the normal one; {@code false} by default}
     */
    public boolean simpleFilter() {
        return simpleFilter;
    }

    /**
     * {@return whether lossy coding chooses the loop filter strength of every segment by measuring
     * the structural similarity of the filtered reconstruction to the source; {@code false} by
     * default}
     *
     * <p>The search turns the filter on even when the {@linkplain #filterStrength() filter
     * strength} is 0. A frame coded with a single segment, because one was requested or because the
     * {@linkplain #segments() segments} merged into one, keeps the strength computed from the
     * filter strength. The search costs roughly as much as coding the image again.
     */
    public boolean autoFilter() {
        return autoFilter;
    }

    /**
     * {@return the number of segments of lossy coding, from 1 to 4: groups of macroblocks with a
     * quantizer and filter strength of their own; 4 by default}
     *
     * <p>Fewer segments save header bits and give up adapting the quantizer to the content.
     */
    public int segments() {
        return segments;
    }

    /**
     * {@return the number of token partitions of lossy coding, 1, 2, 4 or 8; 1 by default}
     *
     * <p>Macroblock rows are spread over the partitions in turn, so a decoder can parse the rows
     * of different partitions in parallel; every partition beyond the first costs 3 bytes.
     */
    public int tokenPartitions() {
        return tokenPartitions;
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
                && Double.compare(targetPsnr, that.targetPsnr) == 0
                && passes == that.passes
                && nearLossless == that.nearLossless
                && sharpYuv == that.sharpYuv
                && dithering == that.dithering
                && snsStrength == that.snsStrength
                && filterStrength == that.filterStrength
                && filterSharpness == that.filterSharpness
                && simpleFilter == that.simpleFilter
                && autoFilter == that.autoFilter
                && segments == that.segments
                && tokenPartitions == that.tokenPartitions
                && extendedFormat == that.extendedFormat
                && Objects.equals(colorProfile, that.colorProfile);
    }

    @Override
    public int hashCode() {
        return Objects.hash(lossless, quality, method, alphaQuality, alphaFilter, exact, targetSize, targetPsnr, passes,
                nearLossless, sharpYuv, dithering, snsStrength, filterStrength, filterSharpness, simpleFilter,
                autoFilter, segments, tokenPartitions, extendedFormat, colorProfile);
    }

    @Override
    public String toString() {
        return "WebpEncoderOptions[lossless=" + lossless + ", quality=" + quality + ", method=" + method
                + ", alphaQuality=" + alphaQuality + ", alphaFilter=" + alphaFilter + ", exact=" + exact
                + ", targetSize=" + targetSize + ", targetPsnr=" + targetPsnr + ", passes=" + passes
                + ", nearLossless=" + nearLossless + ", sharpYuv=" + sharpYuv + ", dithering=" + dithering
                + ", snsStrength=" + snsStrength + ", filterStrength=" + filterStrength
                + ", filterSharpness=" + filterSharpness + ", simpleFilter=" + simpleFilter
                + ", autoFilter=" + autoFilter + ", segments=" + segments + ", tokenPartitions=" + tokenPartitions
                + ", extendedFormat=" + extendedFormat
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
     * Enumerates the kinds of content {@link #forPreset(Preset)} tunes lossy coding for, as
     * {@code cwebp -preset} names them.
     */
    public enum Preset {
        /**
         * General content: the {@linkplain #defaults() default} tuning, spatial noise shaping 50,
         * filter strength 60, sharpness 0, 4 segments.
         */
        DEFAULT,

        /**
         * Digital pictures such as portraits and indoor shots: spatial noise shaping 80, filter
         * strength 35, sharpness 4.
         */
        PICTURE,

        /**
         * Outdoor photographs with natural lighting: spatial noise shaping 80, filter strength 30,
         * sharpness 3, dithering.
         */
        PHOTO,

        /**
         * Hand or line drawings with high-contrast details: spatial noise shaping 25, filter
         * strength 10, sharpness 6.
         */
        DRAWING,

        /**
         * Small colorful images: no spatial noise shaping and no loop filter.
         */
        ICON,

        /**
         * Text-like images: no spatial noise shaping, no loop filter, 2 segments.
         */
        TEXT
    }

    /**
     * A builder of {@link WebpEncoderOptions}.
     *
     * <p>Setters reject only {@code null}; {@link #build()} checks the ranges, the combination of
     * targets and the color profile. Instances are not thread-safe.
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

        private double targetPsnr;

        private boolean hasTargetPsnr;

        private int passes;

        private boolean hasPasses;

        private int nearLossless = 100;

        private boolean sharpYuv;

        private boolean dithering;

        private int snsStrength = 50;

        private int filterStrength = 60;

        private int filterSharpness;

        private boolean simpleFilter;

        private boolean autoFilter;

        private int segments = 4;

        private int tokenPartitions = 1;

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
            this.targetPsnr = options.targetPsnr;
            this.hasTargetPsnr = options.targetPsnr != 0;
            this.passes = options.passes;
            this.hasPasses = options.passes != 0;
            this.nearLossless = options.nearLossless;
            this.sharpYuv = options.sharpYuv;
            this.dithering = options.dithering;
            this.snsStrength = options.snsStrength;
            this.filterStrength = options.filterStrength;
            this.filterSharpness = options.filterSharpness;
            this.simpleFilter = options.simpleFilter;
            this.autoFilter = options.autoFilter;
            this.segments = options.segments;
            this.tokenPartitions = options.tokenPartitions;
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
         * Sets the PSNR lossy coding aims at; see {@link WebpEncoderOptions#targetPsnr()}.
         *
         * @param targetPsnr the value, in dB
         * @return this builder
         */
        public Builder targetPsnr(double targetPsnr) {
            this.targetPsnr = targetPsnr;
            this.hasTargetPsnr = true;
            return this;
        }

        /**
         * Removes the target PSNR, so lossy coding uses the quality as given; see
         * {@link WebpEncoderOptions#targetPsnr()}.
         *
         * @return this builder
         */
        public Builder noTargetPsnr() {
            this.targetPsnr = 0;
            this.hasTargetPsnr = false;
            return this;
        }

        /**
         * Sets the number of entropy-analysis passes; see {@link WebpEncoderOptions#passes()}.
         *
         * @param passes the value
         * @return this builder
         */
        public Builder passes(int passes) {
            this.passes = passes;
            this.hasPasses = true;
            return this;
        }

        /**
         * Restores the automatic number of entropy-analysis passes; see
         * {@link WebpEncoderOptions#passes()}.
         *
         * @return this builder
         */
        public Builder automaticPasses() {
            this.passes = 0;
            this.hasPasses = false;
            return this;
        }

        /**
         * Sets the near-lossless level of lossless coding; see
         * {@link WebpEncoderOptions#nearLossless()}.
         *
         * @param nearLossless the value
         * @return this builder
         */
        public Builder nearLossless(int nearLossless) {
            this.nearLossless = nearLossless;
            return this;
        }

        /**
         * Sets whether lossy coding uses the sharp RGB to Y'CbCr conversion; see
         * {@link WebpEncoderOptions#sharpYuv()}.
         *
         * @param sharpYuv the value
         * @return this builder
         */
        public Builder sharpYuv(boolean sharpYuv) {
            this.sharpYuv = sharpYuv;
            return this;
        }

        /**
         * Sets whether the RGB to Y'CbCr conversion is dithered; see
         * {@link WebpEncoderOptions#dithering()}.
         *
         * @param dithering the value
         * @return this builder
         */
        public Builder dithering(boolean dithering) {
            this.dithering = dithering;
            return this;
        }

        /**
         * Sets the spatial noise shaping strength; see {@link WebpEncoderOptions#snsStrength()}.
         *
         * @param snsStrength the value
         * @return this builder
         */
        public Builder snsStrength(int snsStrength) {
            this.snsStrength = snsStrength;
            return this;
        }

        /**
         * Sets the loop filter strength; see {@link WebpEncoderOptions#filterStrength()}.
         *
         * @param filterStrength the value
         * @return this builder
         */
        public Builder filterStrength(int filterStrength) {
            this.filterStrength = filterStrength;
            return this;
        }

        /**
         * Sets the loop filter sharpness; see {@link WebpEncoderOptions#filterSharpness()}.
         *
         * @param filterSharpness the value
         * @return this builder
         */
        public Builder filterSharpness(int filterSharpness) {
            this.filterSharpness = filterSharpness;
            return this;
        }

        /**
         * Sets whether the simple loop filter is signaled; see
         * {@link WebpEncoderOptions#simpleFilter()}.
         *
         * @param simpleFilter the value
         * @return this builder
         */
        public Builder simpleFilter(boolean simpleFilter) {
            this.simpleFilter = simpleFilter;
            return this;
        }

        /**
         * Sets whether the filter strength is searched per segment; see
         * {@link WebpEncoderOptions#autoFilter()}.
         *
         * @param autoFilter the value
         * @return this builder
         */
        public Builder autoFilter(boolean autoFilter) {
            this.autoFilter = autoFilter;
            return this;
        }

        /**
         * Sets the number of segments; see {@link WebpEncoderOptions#segments()}.
         *
         * @param segments the value
         * @return this builder
         */
        public Builder segments(int segments) {
            this.segments = segments;
            return this;
        }

        /**
         * Sets the number of token partitions; see {@link WebpEncoderOptions#tokenPartitions()}.
         *
         * @param tokenPartitions the value
         * @return this builder
         */
        public Builder tokenPartitions(int tokenPartitions) {
            this.tokenPartitions = tokenPartitions;
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
         * [0, 6], the alpha quality is not in [0, 100], the target size is not positive, the target
         * PSNR is not in (0, 99], both a target size and a target PSNR are set, the passes are not
         * in [1, 10], the near-lossless level, the spatial noise shaping strength or the filter
         * strength is not in [0, 100], the filter sharpness is not in [0, 7], the segments are not
         * in [1, 4] or the token partitions are not 1, 2, 4 or 8
         */
        public WebpEncoderOptions build() {
            Checks.inRange("quality", quality, 0, 100);
            Checks.inRange("method", method, 0, 6);
            Checks.inRange("alpha quality", alphaQuality, 0, 100);
            if (hasTargetSize) {
                Checks.positive("target size", targetSize);
            }
            if (hasTargetPsnr) {
                Checks.positive("target PSNR", targetPsnr);
                Checks.inRange("target PSNR", targetPsnr, 0, 99);
                if (hasTargetSize) {
                    throw new IllegalArgumentException("a target size and a target PSNR cannot be combined");
                }
            }
            if (hasPasses) {
                Checks.inRange("passes", passes, 1, 10);
            }
            Checks.inRange("near-lossless level", nearLossless, 0, 100);
            Checks.inRange("sns strength", snsStrength, 0, 100);
            Checks.inRange("filter strength", filterStrength, 0, 100);
            Checks.inRange("filter sharpness", filterSharpness, 0, 7);
            Checks.inRange("segments", segments, 1, 4);
            if (tokenPartitions != 1 && tokenPartitions != 2 && tokenPartitions != 4 && tokenPartitions != 8) {
                throw new IllegalArgumentException("token partitions " + tokenPartitions + " is not 1, 2, 4 or 8");
            }
            return new WebpEncoderOptions(this);
        }
    }
}
