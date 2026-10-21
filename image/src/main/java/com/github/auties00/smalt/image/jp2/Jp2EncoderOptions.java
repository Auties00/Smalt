package com.github.auties00.smalt.image.jp2;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.color.ColorProfile;
import com.github.auties00.smalt.internal.check.Checks;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Holds the settings of a {@link Jp2Encoder}. Instances are immutable and thread-safe; create them
 * with {@link #builder()} or a preset such as {@link #defaults()}.
 *
 * <p>The wavelet decides whether coding can be lossless, and the quality layers decide the size:
 * each layer meets either a compression ratio or a PSNR target and refines the previous one, so a
 * decoder that reads only the first layers gets a smaller, coarser image.
 */
public final class Jp2EncoderOptions {
    /**
     * The largest number of quality layers.
     */
    private static final int MAX_LAYERS = 100;

    /**
     * The longest comment, in characters: a {@code COM} segment holds 65535 bytes, four of them its
     * length and registration fields.
     */
    private static final int MAX_COMMENT = 65531;

    /**
     * The options of {@link #defaults()}.
     */
    private static final Jp2EncoderOptions DEFAULTS = new Builder().build();

    private final MediaLimits limits;

    private final Jp2Format format;

    private final boolean reversible;

    /**
     * The compression ratio of each layer, or {@code null} when layers target PSNR values.
     */
    private final double[] ratios;

    /**
     * The PSNR target of each layer, or {@code null} when layers target ratios.
     */
    private final double[] psnrTargets;

    private final int decompositionLevels;

    /**
     * The tile width, or 0 for one tile.
     */
    private final int tileWidth;

    /**
     * The tile height, or 0 for one tile.
     */
    private final int tileHeight;

    private final int codeBlockWidth;

    private final int codeBlockHeight;

    /**
     * The precinct width at the highest resolution, or 0 for maximal precincts.
     */
    private final int precinctWidth;

    /**
     * The precinct height at the highest resolution, or 0 for maximal precincts.
     */
    private final int precinctHeight;

    private final Jp2ProgressionOrder progression;

    private final Set<Jp2CodeBlockStyle> codeBlockStyles;

    private final boolean sopMarkers;

    private final boolean ephMarkers;

    private final boolean colorTransform;

    private final ColorProfile colorProfile;

    private final String comment;

    private Jp2EncoderOptions(Builder builder) {
        this.limits = builder.limits;
        this.format = builder.format;
        this.reversible = builder.reversible;
        this.ratios = builder.ratios == null ? null : builder.ratios.clone();
        this.psnrTargets = builder.psnrTargets == null ? null : builder.psnrTargets.clone();
        this.decompositionLevels = builder.decompositionLevels;
        this.tileWidth = builder.tileWidth;
        this.tileHeight = builder.tileHeight;
        this.codeBlockWidth = builder.codeBlockWidth;
        this.codeBlockHeight = builder.codeBlockHeight;
        this.precinctWidth = builder.precinctWidth;
        this.precinctHeight = builder.precinctHeight;
        this.progression = builder.progression;
        this.codeBlockStyles = builder.codeBlockStyles.isEmpty() ? Set.of()
                : Set.copyOf(EnumSet.copyOf(builder.codeBlockStyles));
        this.sopMarkers = builder.sopMarkers;
        this.ephMarkers = builder.ephMarkers;
        this.colorTransform = builder.colorTransform;
        this.colorProfile = builder.colorProfile;
        this.comment = builder.comment;
    }

    /**
     * Returns lossless coding in one layer: the 5-3 wavelet, 5 decomposition levels, 64x64
     * code-blocks, maximal precincts, LRCP progression, one tile, the component transform, and a
     * JP2 file.
     *
     * @return the default options
     */
    public static Jp2EncoderOptions defaults() {
        return DEFAULTS;
    }

    /**
     * Returns lossless coding in one layer, the same as {@link #defaults()}.
     *
     * @return the options
     */
    public static Jp2EncoderOptions lossless() {
        return DEFAULTS;
    }

    /**
     * Returns lossy coding with the 9-7 wavelet at a compression ratio, in one quality layer; every
     * other setting is that of {@link #defaults()}.
     *
     * @param compressionRatio how many times smaller than the raw samples the file is, above 1;
     * 10 to 20 keeps photographs visually lossless
     * @return the options
     * @throws IllegalArgumentException if {@code compressionRatio} is not above 1 and finite
     */
    public static Jp2EncoderOptions lossy(double compressionRatio) {
        if (!(compressionRatio > 1) || Double.isInfinite(compressionRatio)) {
            throw new IllegalArgumentException("compression ratio " + compressionRatio + " is not above 1");
        }
        return new Builder().reversible(false).compressionRatios(compressionRatio).build();
    }

    /**
     * Returns lossy coding with the 9-7 wavelet at a PSNR target, in one quality layer; every other
     * setting is that of {@link #defaults()}.
     *
     * @param decibels the PSNR the decoded image reaches, above 0; 40 is visually lossless
     * @return the options
     * @throws IllegalArgumentException if {@code decibels} is not positive and finite
     */
    public static Jp2EncoderOptions ofPsnr(double decibels) {
        if (!(decibels > 0) || Double.isInfinite(decibels)) {
            throw new IllegalArgumentException("PSNR " + decibels + " is not positive");
        }
        return new Builder().reversible(false).psnrTargets(decibels).build();
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
     * {@return the output form; {@link Jp2Format#JP2} by default}
     */
    public Jp2Format format() {
        return format;
    }

    /**
     * {@return whether the reversible 5-3 wavelet is used rather than the irreversible 9-7 one;
     * {@code true} by default}
     *
     * <p>The 5-3 wavelet without quantization reproduces every sample exactly when the last quality
     * layer holds every coding pass; the 9-7 wavelet compresses better but always loses some
     * precision.
     */
    public boolean isReversible() {
        return reversible;
    }

    /**
     * {@return whether every sample is reproduced exactly: the 5-3 wavelet with a last layer that
     * holds every coding pass}
     */
    public boolean isLossless() {
        var layers = ratios != null ? ratios : psnrTargets;
        var last = layers[layers.length - 1];
        return reversible && (ratios != null ? last <= 1 : last == 0);
    }

    /**
     * {@return a copy of the compression ratio of each quality layer, in layer order, or an empty
     * array when the layers target PSNR values; {@code {0}} by default}
     *
     * <p>A layer meeting a ratio makes the file that many times smaller than the raw samples; a
     * ratio of 0 or 1 gives the layer every coding pass left.
     */
    public double[] compressionRatios() {
        return ratios == null ? new double[0] : ratios.clone();
    }

    /**
     * {@return a copy of the PSNR target of each quality layer in decibels, in layer order, or an
     * empty array when the layers target compression ratios; empty by default}
     *
     * <p>A target of 0 gives the layer every coding pass left.
     */
    public double[] psnrTargets() {
        return psnrTargets == null ? new double[0] : psnrTargets.clone();
    }

    /**
     * {@return the number of wavelet decomposition levels, from 0 to 32; 5 by default}
     */
    public int decompositionLevels() {
        return decompositionLevels;
    }

    /**
     * {@return the tile width, positive, or 0 for one tile; 0 by default}
     */
    public int tileWidth() {
        return tileWidth;
    }

    /**
     * {@return the tile height, positive, or 0 for one tile; 0 by default}
     */
    public int tileHeight() {
        return tileHeight;
    }

    /**
     * {@return the code-block width, a power of two from 4 to 1024; 64 by default}
     *
     * <p>A code-block holds at most 4096 samples.
     */
    public int codeBlockWidth() {
        return codeBlockWidth;
    }

    /**
     * {@return the code-block height, a power of two from 4 to 1024; 64 by default}
     *
     * <p>A code-block holds at most 4096 samples.
     */
    public int codeBlockHeight() {
        return codeBlockHeight;
    }

    /**
     * {@return the precinct width at the highest resolution, a power of two from 2 to 32768, or 0
     * for maximal precincts; 0 by default}
     *
     * <p>Each lower resolution halves it, down to 2.
     */
    public int precinctWidth() {
        return precinctWidth;
    }

    /**
     * {@return the precinct height at the highest resolution, a power of two from 2 to 32768, or 0
     * for maximal precincts; 0 by default}
     *
     * <p>Each lower resolution halves it, down to 2.
     */
    public int precinctHeight() {
        return precinctHeight;
    }

    /**
     * {@return the progression order; {@link Jp2ProgressionOrder#LRCP} by default}
     */
    public Jp2ProgressionOrder progressionOrder() {
        return progression;
    }

    /**
     * {@return the code-block style options; empty by default}
     */
    public Set<Jp2CodeBlockStyle> codeBlockStyles() {
        return codeBlockStyles;
    }

    /**
     * {@return whether packets start with {@code SOP} marker segments; {@code false} by default}
     */
    public boolean sopMarkers() {
        return sopMarkers;
    }

    /**
     * {@return whether packet headers end with {@code EPH} markers; {@code false} by default}
     */
    public boolean ephMarkers() {
        return ephMarkers;
    }

    /**
     * {@return whether the first three channels go through the component transform, which
     * decorrelates them and compresses better; {@code true} by default}
     *
     * <p>The transform applies only to an image with at least three channels whose first three
     * share one subsampling and whose color space is not sYCC.
     */
    public boolean colorTransform() {
        return colorTransform;
    }

    /**
     * {@return the color profile embedded in a JP2 file, or empty to declare sRGB (or gray); empty
     * by default}
     *
     * <p>A high-dynamic-range profile is written in its ICC form, whose {@code cicp} tag carries its PQ
     * or HLG code points.
     */
    public Optional<ColorProfile> colorProfile() {
        return Optional.ofNullable(colorProfile);
    }

    /**
     * {@return the comment written in the codestream, Latin-1 text of at most 65531 characters, or
     * empty for none; empty by default}
     */
    public Optional<String> comment() {
        return Optional.ofNullable(comment);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Jp2EncoderOptions that && limits.equals(that.limits) && format == that.format
                && reversible == that.reversible && Arrays.equals(ratios, that.ratios)
                && Arrays.equals(psnrTargets, that.psnrTargets) && decompositionLevels == that.decompositionLevels
                && tileWidth == that.tileWidth
                && tileHeight == that.tileHeight && codeBlockWidth == that.codeBlockWidth
                && codeBlockHeight == that.codeBlockHeight && precinctWidth == that.precinctWidth
                && precinctHeight == that.precinctHeight && progression == that.progression
                && codeBlockStyles.equals(that.codeBlockStyles) && sopMarkers == that.sopMarkers
                && ephMarkers == that.ephMarkers && colorTransform == that.colorTransform
                && Objects.equals(colorProfile, that.colorProfile) && Objects.equals(comment, that.comment);
    }

    @Override
    public int hashCode() {
        return Objects.hash(limits, format, reversible, Arrays.hashCode(ratios), Arrays.hashCode(psnrTargets),
                decompositionLevels, tileWidth, tileHeight, codeBlockWidth, codeBlockHeight, precinctWidth,
                precinctHeight, progression, codeBlockStyles, sopMarkers, ephMarkers, colorTransform, colorProfile,
                comment);
    }

    @Override
    public String toString() {
        return "Jp2EncoderOptions[format=" + format + ", reversible=" + reversible
                + (ratios != null ? ", ratios=" + Arrays.toString(ratios) : ", psnr=" + Arrays.toString(psnrTargets))
                + ", levels=" + decompositionLevels + ", tiles=" + tileWidth + "x" + tileHeight + ", codeBlocks="
                + codeBlockWidth + "x" + codeBlockHeight + ", precincts=" + precinctWidth + "x" + precinctHeight
                + ", progression=" + progression + ", styles=" + codeBlockStyles + ", sop=" + sopMarkers + ", eph="
                + ephMarkers + ", colorTransform=" + colorTransform + ", colorProfile=" + (colorProfile != null)
                + ", comment=" + (comment != null) + "]";
    }

    /**
     * A builder of {@link Jp2EncoderOptions}.
     *
     * <p>Setters reject only {@code null}; {@link #build()} checks the ranges, the comment and the
     * color profile. Instances are not thread-safe.
     */
    public static final class Builder {
        private MediaLimits limits = MediaLimits.DEFAULT;

        private Jp2Format format = Jp2Format.JP2;

        private boolean reversible = true;

        private double[] ratios = {0};

        private double[] psnrTargets;

        private int decompositionLevels = 5;

        private int tileWidth;

        private int tileHeight;

        /**
         * Whether {@link #tileSize(int, int)} set the tile size, so that {@link #build()} checks it.
         */
        private boolean tilesSet;

        private int codeBlockWidth = 64;

        private int codeBlockHeight = 64;

        private int precinctWidth;

        private int precinctHeight;

        /**
         * Whether {@link #precinctSize(int, int)} set the precinct size, so that {@link #build()}
         * checks it.
         */
        private boolean precinctsSet;

        private Jp2ProgressionOrder progression = Jp2ProgressionOrder.LRCP;

        private Set<Jp2CodeBlockStyle> codeBlockStyles = EnumSet.noneOf(Jp2CodeBlockStyle.class);

        private boolean sopMarkers;

        private boolean ephMarkers;

        private boolean colorTransform = true;

        private ColorProfile colorProfile;

        private String comment;

        private Builder() {
        }

        private Builder(Jp2EncoderOptions options) {
            this.limits = options.limits;
            this.format = options.format;
            this.reversible = options.reversible;
            this.ratios = options.ratios;
            this.psnrTargets = options.psnrTargets;
            this.decompositionLevels = options.decompositionLevels;
            this.tileWidth = options.tileWidth;
            this.tileHeight = options.tileHeight;
            this.codeBlockWidth = options.codeBlockWidth;
            this.codeBlockHeight = options.codeBlockHeight;
            this.precinctWidth = options.precinctWidth;
            this.precinctHeight = options.precinctHeight;
            this.progression = options.progression;
            this.codeBlockStyles = options.codeBlockStyles.isEmpty() ? EnumSet.noneOf(Jp2CodeBlockStyle.class)
                    : EnumSet.copyOf(options.codeBlockStyles);
            this.sopMarkers = options.sopMarkers;
            this.ephMarkers = options.ephMarkers;
            this.colorTransform = options.colorTransform;
            this.colorProfile = options.colorProfile;
            this.comment = options.comment;
        }

        /**
         * Sets the resource bounds; see {@link Jp2EncoderOptions#limits()}.
         *
         * @param limits the value
         * @return this builder
         */
        public Builder limits(MediaLimits limits) {
            this.limits = Objects.requireNonNull(limits, "limits");
            return this;
        }

        /**
         * Sets the output form; see {@link Jp2EncoderOptions#format()}.
         *
         * @param format the value
         * @return this builder
         */
        public Builder format(Jp2Format format) {
            this.format = Objects.requireNonNull(format, "format");
            return this;
        }

        /**
         * Sets the wavelet; see {@link Jp2EncoderOptions#isReversible()}.
         *
         * @param reversible the value
         * @return this builder
         */
        public Builder reversible(boolean reversible) {
            this.reversible = reversible;
            return this;
        }

        /**
         * Makes the quality layers meet compression ratios, replacing any PSNR targets; see
         * {@link Jp2EncoderOptions#compressionRatios()}.
         *
         * @param ratios the value, copied
         * @return this builder
         */
        public Builder compressionRatios(double... ratios) {
            this.ratios = ratios.clone();
            this.psnrTargets = null;
            return this;
        }

        /**
         * Makes the quality layers meet PSNR targets, replacing any compression ratios; see
         * {@link Jp2EncoderOptions#psnrTargets()}.
         *
         * @param decibels the value, copied
         * @return this builder
         */
        public Builder psnrTargets(double... decibels) {
            this.psnrTargets = decibels.clone();
            this.ratios = null;
            return this;
        }

        /**
         * Sets the number of decomposition levels; see
         * {@link Jp2EncoderOptions#decompositionLevels()}.
         *
         * @param levels the value
         * @return this builder
         */
        public Builder decompositionLevels(int levels) {
            this.decompositionLevels = levels;
            return this;
        }

        /**
         * Sets the tile size; see {@link Jp2EncoderOptions#tileWidth()} and
         * {@link Jp2EncoderOptions#tileHeight()}.
         *
         * @param width the tile width
         * @param height the tile height
         * @return this builder
         */
        public Builder tileSize(int width, int height) {
            this.tileWidth = width;
            this.tileHeight = height;
            this.tilesSet = true;
            return this;
        }

        /**
         * Codes the image as one tile; see {@link Jp2EncoderOptions#tileWidth()}.
         *
         * @return this builder
         */
        public Builder noTiles() {
            this.tileWidth = 0;
            this.tileHeight = 0;
            this.tilesSet = false;
            return this;
        }

        /**
         * Sets the code-block size; see {@link Jp2EncoderOptions#codeBlockWidth()} and
         * {@link Jp2EncoderOptions#codeBlockHeight()}.
         *
         * @param width the code-block width
         * @param height the code-block height
         * @return this builder
         */
        public Builder codeBlockSize(int width, int height) {
            this.codeBlockWidth = width;
            this.codeBlockHeight = height;
            return this;
        }

        /**
         * Sets the precinct size at the highest resolution; see
         * {@link Jp2EncoderOptions#precinctWidth()} and {@link Jp2EncoderOptions#precinctHeight()}.
         *
         * @param width the precinct width
         * @param height the precinct height
         * @return this builder
         */
        public Builder precinctSize(int width, int height) {
            this.precinctWidth = width;
            this.precinctHeight = height;
            this.precinctsSet = true;
            return this;
        }

        /**
         * Uses maximal precincts; see {@link Jp2EncoderOptions#precinctWidth()}.
         *
         * @return this builder
         */
        public Builder maximalPrecincts() {
            this.precinctWidth = 0;
            this.precinctHeight = 0;
            this.precinctsSet = false;
            return this;
        }

        /**
         * Sets the progression order; see {@link Jp2EncoderOptions#progressionOrder()}.
         *
         * @param order the value
         * @return this builder
         */
        public Builder progressionOrder(Jp2ProgressionOrder order) {
            this.progression = Objects.requireNonNull(order, "order");
            return this;
        }

        /**
         * Sets the code-block style options; see {@link Jp2EncoderOptions#codeBlockStyles()}.
         *
         * @param styles the value, copied
         * @return this builder
         */
        public Builder codeBlockStyles(Set<Jp2CodeBlockStyle> styles) {
            this.codeBlockStyles = styles.isEmpty() ? EnumSet.noneOf(Jp2CodeBlockStyle.class) : EnumSet.copyOf(styles);
            return this;
        }

        /**
         * Sets whether {@code SOP} segments are written; see {@link Jp2EncoderOptions#sopMarkers()}.
         *
         * @param sopMarkers the value
         * @return this builder
         */
        public Builder sopMarkers(boolean sopMarkers) {
            this.sopMarkers = sopMarkers;
            return this;
        }

        /**
         * Sets whether {@code EPH} markers are written; see {@link Jp2EncoderOptions#ephMarkers()}.
         *
         * @param ephMarkers the value
         * @return this builder
         */
        public Builder ephMarkers(boolean ephMarkers) {
            this.ephMarkers = ephMarkers;
            return this;
        }

        /**
         * Sets whether the first three channels go through the component transform; see
         * {@link Jp2EncoderOptions#colorTransform()}.
         *
         * @param colorTransform the value
         * @return this builder
         */
        public Builder colorTransform(boolean colorTransform) {
            this.colorTransform = colorTransform;
            return this;
        }

        /**
         * Sets the color profile to embed; see {@link Jp2EncoderOptions#colorProfile()}.
         *
         * @param profile the value, or {@code null} for none
         * @return this builder
         */
        public Builder colorProfile(ColorProfile profile) {
            this.colorProfile = profile;
            return this;
        }

        /**
         * Sets the codestream comment; see {@link Jp2EncoderOptions#comment()}.
         *
         * @param comment the value, or {@code null} for none
         * @return this builder
         */
        public Builder comment(String comment) {
            this.comment = comment;
            return this;
        }

        /**
         * Returns the options holding the settings of this builder.
         *
         * @return the options
         * @throws IllegalArgumentException if there are no quality layers or more than 100, a layer
         * target is negative or not finite, the decomposition levels are not in [0, 32], a tile
         * dimension is not positive, a code-block dimension is not a power of two in [4, 1024] or a
         * code-block holds more than 4096 samples, a precinct dimension is not a power of two in
         * [2, 32768], or the comment is not Latin-1 text of at most 65531 characters
         */
        public Jp2EncoderOptions build() {
            var layers = ratios != null ? ratios : psnrTargets;
            Checks.inRange("quality layer count", layers.length, 1, MAX_LAYERS);
            for (var value : layers) {
                if (!(value >= 0) || Double.isInfinite(value)) {
                    throw new IllegalArgumentException("layer target " + value + " is not a finite non-negative "
                            + "number");
                }
            }
            Checks.inRange("decomposition levels", decompositionLevels, 0, 32);
            if (tilesSet) {
                Checks.positive("tile width", tileWidth);
                Checks.positive("tile height", tileHeight);
            }
            if (!powerOfTwo(codeBlockWidth, 4, 1024) || !powerOfTwo(codeBlockHeight, 4, 1024)
                    || codeBlockWidth * codeBlockHeight > 4096) {
                throw new IllegalArgumentException("code-block size " + codeBlockWidth + "x" + codeBlockHeight
                        + " is not two powers of two in [4, 1024] with at most 4096 samples");
            }
            if (precinctsSet && (!powerOfTwo(precinctWidth, 2, 1 << 15) || !powerOfTwo(precinctHeight, 2, 1 << 15))) {
                throw new IllegalArgumentException("precinct size " + precinctWidth + "x" + precinctHeight
                        + " is not two powers of two in [2, 32768]");
            }
            if (comment != null && (comment.length() > MAX_COMMENT
                    || !StandardCharsets.ISO_8859_1.newEncoder().canEncode(comment))) {
                throw new IllegalArgumentException("comment is not Latin-1 text of at most " + MAX_COMMENT
                        + " characters");
            }
            return new Jp2EncoderOptions(this);
        }

        /**
         * Returns whether a value is a power of two within a range.
         *
         * @param value the number to test
         * @param min the smallest value allowed
         * @param max the largest value allowed
         * @return whether it is
         */
        private static boolean powerOfTwo(int value, int min, int max) {
            return value >= min && value <= max && Integer.bitCount(value) == 1;
        }
    }
}
