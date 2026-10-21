package com.github.auties00.smalt.image.png;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.color.ColorProfile;
import com.github.auties00.smalt.image.color.ProfileColorSpace;
import com.github.auties00.smalt.image.color.RenderingIntent;
import com.github.auties00.smalt.internal.check.Checks;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;

/**
 * Holds the settings of a {@link PngEncoder}. Instances are immutable and thread-safe; create them
 * with {@link #builder()} or a preset such as {@link #defaults()}.
 */
public final class PngEncoderOptions {
    /**
     * The options of {@link #defaults()}.
     */
    private static final PngEncoderOptions DEFAULTS = new Builder().build();

    private final int compressionLevel;

    private final Filter filter;

    private final ColorProfile colorProfile;

    private final boolean interlaced;

    private final Integer significantBits;

    private final RenderingIntent srgbIntent;

    private final Double gamma;

    private final PngChromaticities chromaticities;

    private final PngPhysicalDimensions physicalDimensions;

    private PngEncoderOptions(Builder builder) {
        this.compressionLevel = builder.compressionLevel;
        this.filter = builder.filter;
        this.colorProfile = builder.colorProfile;
        this.interlaced = builder.interlaced;
        this.significantBits = builder.significantBits;
        this.srgbIntent = builder.srgbIntent;
        this.gamma = builder.gamma;
        this.chromaticities = builder.chromaticities;
        this.physicalDimensions = builder.physicalDimensions;
    }

    /**
     * Returns compression level 6 with {@link Filter#ADAPTIVE} filtering, non-interlaced, and no
     * ancillary chunks beyond those the pixels need.
     *
     * @return the default options
     */
    public static PngEncoderOptions defaults() {
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
     * {@return the zlib compression level, from 0 (stored, fastest) to 9 (smallest), as
     * {@link java.util.zip.Deflater#setLevel(int)} accepts it; 6 by default}
     *
     * <p>PNG is lossless, so the level trades encoding time for size only.
     */
    public int compressionLevel() {
        return compressionLevel;
    }

    /**
     * {@return the scanline filter selection; {@link Filter#ADAPTIVE} by default}
     */
    public Filter filter() {
        return filter;
    }

    /**
     * {@return the color profile declared in the output, or empty when none is declared; empty by
     * default}
     *
     * <p>The profile tells readers which color space the pixels are in; it does not convert them.
     * Without one, readers take the pixels as sRGB. An ICC profile, which must be an RGB or a gray
     * profile, is written as a compressed {@code iCCP} chunk; an RGB profile keeps the image in a
     * color type with color samples and a gray profile in a grayscale color type, as PNG 3rd
     * edition 11.3.2.3 requires. A high-dynamic-range profile ({@link ColorProfile#isHighDynamicRange()})
     * is first written as a {@code cICP} chunk with its primaries, its transfer characteristics, the
     * identity matrix and full range, followed by an {@code mDCV} chunk when its
     * {@link ColorProfile#colorInfo()} has a mastering display and a {@code cLLI} chunk when it has
     * a content light level, which readers that know them prefer to {@code iCCP}; for the others
     * its RGB ICC form, with a {@code cicp} tag of the same code points, follows in an {@code iCCP}
     * chunk unless the image is stored in a grayscale color type, which a high-dynamic-range profile
     * does not prevent and which an RGB ICC profile cannot describe.
     */
    public Optional<ColorProfile> colorProfile() {
        return Optional.ofNullable(colorProfile);
    }

    /**
     * {@return whether the image is stored in the seven-pass Adam7 interlaced order (PNG 3rd edition
     * 8.2), which lets a reader show a coarse image early at the cost of a slightly larger file;
     * {@code false} by default}
     */
    public boolean isInterlaced() {
        return interlaced;
    }

    /**
     * {@return the number of significant bits of every channel of the original samples, from 1 to
     * 16, written as an {@code sBIT} chunk; empty by default, meaning every bit is significant}
     *
     * <p>The chunk only records the precision; the samples are written as given, so a caller must
     * already have scaled them to the full sample depth (for example 10-bit values to 16 bits).
     * Each channel's value is capped at its sample depth in the output (8 for indexed color), and
     * no value is written for alpha that a {@code tRNS} chunk supplies.
     */
    public OptionalInt significantBits() {
        return significantBits == null ? OptionalInt.empty() : OptionalInt.of(significantBits);
    }

    /**
     * {@return the rendering intent of an {@code sRGB} chunk declaring the pixels sRGB, or empty
     * when none is written; empty by default}
     *
     * <p>The chunk is accompanied by the {@code gAMA} value 45455 and the {@code cHRM} values of
     * {@link PngChromaticities#ofSrgb()}, as PNG 3rd edition 11.3.2.5 recommends.
     */
    public Optional<RenderingIntent> srgbIntent() {
        return Optional.ofNullable(srgbIntent);
    }

    /**
     * {@return the image gamma written as a {@code gAMA} chunk, or empty when none is written;
     * empty by default}
     *
     * <p>The image gamma is the exponent that encoded linear light into the samples, for example
     * {@code 1 / 2.2}; the chunk stores it times 100000, rounded to the nearest integer.
     */
    public OptionalDouble gamma() {
        return gamma == null ? OptionalDouble.empty() : OptionalDouble.of(gamma);
    }

    /**
     * {@return the chromaticities written as a {@code cHRM} chunk, or empty when none is written;
     * empty by default}
     */
    public Optional<PngChromaticities> chromaticities() {
        return Optional.ofNullable(chromaticities);
    }

    /**
     * {@return the pixel size or aspect ratio written as a {@code pHYs} chunk, or empty when none
     * is written; empty by default}
     */
    public Optional<PngPhysicalDimensions> physicalDimensions() {
        return Optional.ofNullable(physicalDimensions);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof PngEncoderOptions that
                && compressionLevel == that.compressionLevel
                && filter == that.filter
                && Objects.equals(colorProfile, that.colorProfile)
                && interlaced == that.interlaced
                && Objects.equals(significantBits, that.significantBits)
                && srgbIntent == that.srgbIntent
                && Objects.equals(gamma, that.gamma)
                && Objects.equals(chromaticities, that.chromaticities)
                && Objects.equals(physicalDimensions, that.physicalDimensions);
    }

    @Override
    public int hashCode() {
        return Objects.hash(compressionLevel, filter, colorProfile, interlaced, significantBits, srgbIntent, gamma,
                chromaticities, physicalDimensions);
    }

    @Override
    public String toString() {
        return "PngEncoderOptions[compressionLevel=" + compressionLevel + ", filter=" + filter
                + (colorProfile == null ? "" : ", colorProfile=" + colorProfile.description().orElse("unnamed"))
                + (interlaced ? ", interlaced" : "")
                + (significantBits == null ? "" : ", significantBits=" + significantBits)
                + (srgbIntent == null ? "" : ", srgbIntent=" + srgbIntent)
                + (gamma == null ? "" : ", gamma=" + gamma)
                + (chromaticities == null ? "" : ", chromaticities=" + chromaticities)
                + (physicalDimensions == null ? "" : ", physicalDimensions=" + physicalDimensions)
                + "]";
    }

    /**
     * Checks that a color profile can be written to a PNG datastream.
     *
     * @param colorProfile the profile, or {@code null}
     * @throws MediaException.Unsupported if it is an ICC profile of a color space other than RGB
     * and gray
     */
    static void checkProfile(ColorProfile colorProfile) {
        if (colorProfile != null && colorProfile.colorSpace() != ProfileColorSpace.RGB
                && colorProfile.colorSpace() != ProfileColorSpace.GRAY) {
            throw new MediaException.Unsupported("png " + colorProfile.colorSpace(),
                    "PNG holds only RGB and gray ICC profiles, not " + colorProfile.colorSpace());
        }
    }

    /**
     * A builder of {@link PngEncoderOptions}.
     *
     * <p>Setters reject only {@code null}; {@link #build()} checks the ranges, the color profile
     * and the combination of color chunks. Instances are not thread-safe.
     */
    public static final class Builder {
        private int compressionLevel = 6;

        private Filter filter = Filter.ADAPTIVE;

        private ColorProfile colorProfile;

        private boolean interlaced;

        private Integer significantBits;

        private RenderingIntent srgbIntent;

        private Double gamma;

        private PngChromaticities chromaticities;

        private PngPhysicalDimensions physicalDimensions;

        private Builder() {
        }

        private Builder(PngEncoderOptions options) {
            this.compressionLevel = options.compressionLevel;
            this.filter = options.filter;
            this.colorProfile = options.colorProfile;
            this.interlaced = options.interlaced;
            this.significantBits = options.significantBits;
            this.srgbIntent = options.srgbIntent;
            this.gamma = options.gamma;
            this.chromaticities = options.chromaticities;
            this.physicalDimensions = options.physicalDimensions;
        }

        /**
         * Sets the zlib compression level; see {@link PngEncoderOptions#compressionLevel()}.
         *
         * @param compressionLevel the value
         * @return this builder
         */
        public Builder compressionLevel(int compressionLevel) {
            this.compressionLevel = compressionLevel;
            return this;
        }

        /**
         * Sets the scanline filter selection; see {@link PngEncoderOptions#filter()}.
         *
         * @param filter the value
         * @return this builder
         */
        public Builder filter(Filter filter) {
            this.filter = Objects.requireNonNull(filter, "filter");
            return this;
        }

        /**
         * Sets the color profile declared in the output; see {@link PngEncoderOptions#colorProfile()}.
         *
         * @param colorProfile the value, or {@code null} for none
         * @return this builder
         */
        public Builder colorProfile(ColorProfile colorProfile) {
            this.colorProfile = colorProfile;
            return this;
        }

        /**
         * Removes the color profile; see {@link PngEncoderOptions#colorProfile()}.
         *
         * @return this builder
         */
        public Builder noColorProfile() {
            this.colorProfile = null;
            return this;
        }

        /**
         * Sets whether the image is interlaced; see {@link PngEncoderOptions#isInterlaced()}.
         *
         * @param interlaced the value
         * @return this builder
         */
        public Builder interlaced(boolean interlaced) {
            this.interlaced = interlaced;
            return this;
        }

        /**
         * Sets the number of significant bits; see {@link PngEncoderOptions#significantBits()}.
         *
         * @param significantBits the value
         * @return this builder
         */
        public Builder significantBits(int significantBits) {
            this.significantBits = significantBits;
            return this;
        }

        /**
         * Removes the {@code sBIT} chunk; see {@link PngEncoderOptions#significantBits()}.
         *
         * @return this builder
         */
        public Builder noSignificantBits() {
            this.significantBits = null;
            return this;
        }

        /**
         * Sets the rendering intent of the {@code sRGB} chunk; see
         * {@link PngEncoderOptions#srgbIntent()}.
         *
         * @param srgbIntent the value
         * @return this builder
         */
        public Builder srgbIntent(RenderingIntent srgbIntent) {
            this.srgbIntent = Objects.requireNonNull(srgbIntent, "srgbIntent");
            return this;
        }

        /**
         * Removes the {@code sRGB} chunk; see {@link PngEncoderOptions#srgbIntent()}.
         *
         * @return this builder
         */
        public Builder noSrgbIntent() {
            this.srgbIntent = null;
            return this;
        }

        /**
         * Sets the image gamma; see {@link PngEncoderOptions#gamma()}.
         *
         * @param gamma the value
         * @return this builder
         */
        public Builder gamma(double gamma) {
            this.gamma = gamma;
            return this;
        }

        /**
         * Removes the {@code gAMA} chunk; see {@link PngEncoderOptions#gamma()}.
         *
         * @return this builder
         */
        public Builder noGamma() {
            this.gamma = null;
            return this;
        }

        /**
         * Sets the chromaticities; see {@link PngEncoderOptions#chromaticities()}.
         *
         * @param chromaticities the value
         * @return this builder
         */
        public Builder chromaticities(PngChromaticities chromaticities) {
            this.chromaticities = Objects.requireNonNull(chromaticities, "chromaticities");
            return this;
        }

        /**
         * Removes the {@code cHRM} chunk; see {@link PngEncoderOptions#chromaticities()}.
         *
         * @return this builder
         */
        public Builder noChromaticities() {
            this.chromaticities = null;
            return this;
        }

        /**
         * Sets the pixel size or aspect ratio; see {@link PngEncoderOptions#physicalDimensions()}.
         *
         * @param physicalDimensions the value
         * @return this builder
         */
        public Builder physicalDimensions(PngPhysicalDimensions physicalDimensions) {
            this.physicalDimensions = Objects.requireNonNull(physicalDimensions, "physicalDimensions");
            return this;
        }

        /**
         * Removes the {@code pHYs} chunk; see {@link PngEncoderOptions#physicalDimensions()}.
         *
         * @return this builder
         */
        public Builder noPhysicalDimensions() {
            this.physicalDimensions = null;
            return this;
        }

        /**
         * Returns the options holding the settings of this builder.
         *
         * @return the options
         * @throws IllegalArgumentException if the compression level is not in [0, 9], the
         * significant bits are not in [1, 16], the gamma is
         * not positive or rounds outside {@code [1, 2^31 - 1]} hundred-thousandths, or an
         * {@code sRGB} intent is combined with a color profile, a gamma or chromaticities
         * @throws MediaException.Unsupported if the color profile is an ICC profile of a color space
         * other than RGB and gray
         */
        public PngEncoderOptions build() {
            Checks.inRange("compression level", compressionLevel, 0, 9);
            if (significantBits != null) {
                Checks.inRange("significant bits", significantBits, 1, 16);
            }
            if (gamma != null) {
                var stored = Math.round(gamma * 100000);
                if (!(gamma > 0) || stored < 1 || stored > Integer.MAX_VALUE) {
                    throw new IllegalArgumentException("invalid gamma " + gamma);
                }
            }
            if (srgbIntent != null && (colorProfile != null || gamma != null || chromaticities != null)) {
                throw new IllegalArgumentException("an sRGB intent writes its own gAMA and cHRM and excludes a color profile");
            }
            checkProfile(colorProfile);
            return new PngEncoderOptions(this);
        }
    }

    /**
     * Enumerates the scanline filter selections of PNG filter method 0 (PNG 3rd edition 9.2).
     */
    public enum Filter {
        /**
         * Every scanline is stored unfiltered (filter type 0).
         */
        NONE,

        /**
         * Every scanline predicts each byte from the byte to its left (filter type 1).
         */
        SUB,

        /**
         * Every scanline predicts each byte from the byte above it (filter type 2).
         */
        UP,

        /**
         * Every scanline predicts each byte from the mean of the left and upper bytes (filter
         * type 3).
         */
        AVERAGE,

        /**
         * Every scanline uses the Paeth predictor (filter type 4).
         */
        PAETH,

        /**
         * Each scanline uses the filter type whose output has the smallest sum of absolute values
         * of its bytes read as signed numbers, libpng's heuristic (PNG 3rd edition 12.7); ties go
         * to the lower filter type. Indexed-color images and images of fewer than 8 bits per
         * sample are stored unfiltered, as that section recommends.
         */
        ADAPTIVE
    }
}
