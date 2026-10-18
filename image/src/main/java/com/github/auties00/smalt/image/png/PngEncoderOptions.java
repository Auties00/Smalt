package com.github.auties00.smalt.image.png;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.color.ColorProfile;
import com.github.auties00.smalt.internal.check.Checks;

import java.util.Objects;
import java.util.Optional;

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

    private PngEncoderOptions(Builder builder) {
        this.compressionLevel = builder.compressionLevel;
        this.filter = builder.filter;
        this.colorProfile = builder.colorProfile;
    }

    /**
     * Returns compression level 6 with {@link Filter#ADAPTIVE} filtering and no embedded color
     * profile.
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
     * {@return the ICC profile embedded in the output, or empty when none is embedded; empty by
     * default}
     *
     * <p>The profile tells readers which color space the pixels are in; it does not convert them.
     * Without one, readers take the pixels as sRGB. It is written as a compressed {@code iCCP}
     * chunk after {@code IHDR}. A high-dynamic-range profile, which has no ICC form, is rejected.
     */
    public Optional<ColorProfile> colorProfile() {
        return Optional.ofNullable(colorProfile);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof PngEncoderOptions that
                && compressionLevel == that.compressionLevel
                && filter == that.filter
                && Objects.equals(colorProfile, that.colorProfile);
    }

    @Override
    public int hashCode() {
        return Objects.hash(compressionLevel, filter, colorProfile);
    }

    @Override
    public String toString() {
        return "PngEncoderOptions[compressionLevel=" + compressionLevel + ", filter=" + filter
                + (colorProfile == null ? "" : ", colorProfile=" + colorProfile.description().orElse("unnamed"))
                + "]";
    }

    /**
     * A builder of {@link PngEncoderOptions}.
     *
     * <p>Setters reject only {@code null}; {@link #build()} checks the ranges and the color
     * profile. Instances are not thread-safe.
     */
    public static final class Builder {
        private int compressionLevel = 6;

        private Filter filter = Filter.ADAPTIVE;

        private ColorProfile colorProfile;

        private Builder() {
        }

        private Builder(PngEncoderOptions options) {
            this.compressionLevel = options.compressionLevel;
            this.filter = options.filter;
            this.colorProfile = options.colorProfile;
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
         * Sets the ICC profile embedded in the output; see {@link PngEncoderOptions#colorProfile()}.
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
         * @throws IllegalArgumentException if the compression level is not in [0, 9]
         * @throws MediaException.Unsupported if the color profile has no ICC form (a
         * high-dynamic-range profile)
         */
        public PngEncoderOptions build() {
            Checks.inRange("compression level", compressionLevel, 0, 9);
            if (colorProfile != null && colorProfile.isHighDynamicRange()) {
                throw new MediaException.Unsupported("icc", "a high-dynamic-range profile has no ICC form");
            }
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
         * to the lower filter type. Indexed-color images are stored unfiltered, as that section
         * recommends.
         */
        ADAPTIVE
    }
}
