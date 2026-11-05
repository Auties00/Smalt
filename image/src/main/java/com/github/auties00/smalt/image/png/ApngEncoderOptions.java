package com.github.auties00.smalt.image.png;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.color.ColorProfile;
import com.github.auties00.smalt.image.color.ProfileColorSpace;
import com.github.auties00.smalt.internal.check.Checks;
import com.github.auties00.smalt.internal.tiff.TiffExif;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

/**
 * Holds the settings of an {@link ApngEncoder}. Instances are immutable and thread-safe; create them
 * with {@link #builder()} or a preset such as {@link #defaults()}.
 */
public final class ApngEncoderOptions {
    /**
     * The options of {@link #defaults()}.
     */
    private static final ApngEncoderOptions DEFAULTS = new Builder().build();

    private final int compressionLevel;

    private final PngEncoderOptions.Filter filter;

    private final int loopCount;

    private final ColorProfile colorProfile;

    private final byte[] exif;

    private final byte[] xmp;

    private ApngEncoderOptions(Builder builder) {
        this.compressionLevel = builder.compressionLevel;
        this.filter = builder.filter;
        this.loopCount = builder.loopCount;
        this.colorProfile = builder.colorProfile;
        this.exif = builder.exif;
        this.xmp = builder.xmp;
    }

    /**
     * Returns compression level 6 with {@link PngEncoderOptions.Filter#ADAPTIVE} filtering, as
     * {@link PngEncoderOptions#defaults()}, an animation that loops forever, and no color profile.
     *
     * @return the default options
     */
    public static ApngEncoderOptions defaults() {
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
     * {@return the zlib compression level, from 0 (stored, fastest) to 9 (smallest); 6 by default}
     *
     * <p>APNG is lossless, so the level trades encoding time for size only.
     */
    public int compressionLevel() {
        return compressionLevel;
    }

    /**
     * {@return the scanline filter selection, as for a {@link PngEncoder};
     * {@link PngEncoderOptions.Filter#ADAPTIVE} by default}
     */
    public PngEncoderOptions.Filter filter() {
        return filter;
    }

    /**
     * {@return the number of times the animation plays, at least 0, 0 meaning forever; 0 by
     * default}
     */
    public int loopCount() {
        return loopCount;
    }

    /**
     * {@return the color profile declared in the output, or empty when none is declared; empty by
     * default}
     *
     * <p>The profile tells readers which color space the frames are in; it does not convert them.
     * Without one, readers take the pixels as sRGB. An ICC profile, which must be an RGB profile
     * since every APNG color type this encoder writes has color samples, is written as a
     * compressed {@code iCCP} chunk; a high-dynamic-range profile as a {@code cICP} chunk with its
     * {@code mDCV} and {@code cLLI} metadata followed by its ICC form in {@code iCCP}, as
     * {@link PngEncoderOptions#colorProfile()} describes for color types with color samples.
     */
    public Optional<ColorProfile> colorProfile() {
        return Optional.ofNullable(colorProfile);
    }

    /**
     * {@return a copy of the EXIF block written as an {@code eXIf} chunk, as {@link PngEncoderOptions#exif()}
     * describes, or empty for none; empty by default}
     */
    public Optional<byte[]> exif() {
        return Optional.ofNullable(exif).map(byte[]::clone);
    }

    /**
     * {@return a copy of the XMP packet written as an {@code iTXt} chunk, as {@link PngEncoderOptions#xmp()}
     * describes, or empty for none; empty by default}
     */
    public Optional<byte[]> xmp() {
        return Optional.ofNullable(xmp).map(byte[]::clone);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ApngEncoderOptions that
                && compressionLevel == that.compressionLevel
                && filter == that.filter
                && loopCount == that.loopCount
                && Objects.equals(colorProfile, that.colorProfile)
                && Arrays.equals(exif, that.exif)
                && Arrays.equals(xmp, that.xmp);
    }

    @Override
    public int hashCode() {
        return Objects.hash(compressionLevel, filter, loopCount, colorProfile, Arrays.hashCode(exif),
                Arrays.hashCode(xmp));
    }

    @Override
    public String toString() {
        return "ApngEncoderOptions[compressionLevel=" + compressionLevel + ", filter=" + filter + ", loopCount="
                + loopCount + (colorProfile == null ? "" : ", colorProfile=" + colorProfile.description().orElse("unnamed"))
                + (exif == null ? "" : ", exif=" + exif.length + " bytes")
                + (xmp == null ? "" : ", xmp=" + xmp.length + " bytes")
                + "]";
    }

    /**
     * A builder of {@link ApngEncoderOptions}.
     *
     * <p>Setters reject only {@code null}; {@link #build()} checks the ranges and the color profile.
     * Instances are not thread-safe.
     */
    public static final class Builder {
        private int compressionLevel = 6;

        private PngEncoderOptions.Filter filter = PngEncoderOptions.Filter.ADAPTIVE;

        private int loopCount;

        private ColorProfile colorProfile;

        private byte[] exif;

        private byte[] xmp;

        private Builder() {
        }

        private Builder(ApngEncoderOptions options) {
            this.compressionLevel = options.compressionLevel;
            this.filter = options.filter;
            this.loopCount = options.loopCount;
            this.colorProfile = options.colorProfile;
            this.exif = options.exif;
            this.xmp = options.xmp;
        }

        /**
         * Sets the zlib compression level; see {@link ApngEncoderOptions#compressionLevel()}.
         *
         * @param compressionLevel the value
         * @return this builder
         */
        public Builder compressionLevel(int compressionLevel) {
            this.compressionLevel = compressionLevel;
            return this;
        }

        /**
         * Sets the scanline filter selection; see {@link ApngEncoderOptions#filter()}.
         *
         * @param filter the value
         * @return this builder
         */
        public Builder filter(PngEncoderOptions.Filter filter) {
            this.filter = Objects.requireNonNull(filter, "filter");
            return this;
        }

        /**
         * Sets the number of times the animation plays; see {@link ApngEncoderOptions#loopCount()}.
         *
         * @param loopCount the value
         * @return this builder
         */
        public Builder loopCount(int loopCount) {
            this.loopCount = loopCount;
            return this;
        }

        /**
         * Sets the color profile declared in the output; see {@link ApngEncoderOptions#colorProfile()}.
         *
         * @param colorProfile the value
         * @return this builder
         */
        public Builder colorProfile(ColorProfile colorProfile) {
            this.colorProfile = Objects.requireNonNull(colorProfile, "colorProfile");
            return this;
        }

        /**
         * Removes the color profile; see {@link ApngEncoderOptions#colorProfile()}.
         *
         * @return this builder
         */
        public Builder noColorProfile() {
            this.colorProfile = null;
            return this;
        }

        /**
         * Sets the EXIF block; see {@link ApngEncoderOptions#exif()}.
         *
         * @param exif the value, with or without the {@code "Exif\0\0"} identifier JPEG puts in front of it,
         * which is removed; copied
         * @return this builder
         */
        public Builder exif(byte[] exif) {
            this.exif = TiffExif.stripExifPrefix(Objects.requireNonNull(exif, "exif")).clone();
            return this;
        }

        /**
         * Writes no {@code eXIf} chunk; see {@link ApngEncoderOptions#exif()}.
         *
         * @return this builder
         */
        public Builder noExif() {
            this.exif = null;
            return this;
        }

        /**
         * Sets the XMP packet; see {@link ApngEncoderOptions#xmp()}.
         *
         * @param xmp the value; copied
         * @return this builder
         */
        public Builder xmp(byte[] xmp) {
            this.xmp = Objects.requireNonNull(xmp, "xmp").clone();
            return this;
        }

        /**
         * Writes no XMP {@code iTXt} chunk; see {@link ApngEncoderOptions#xmp()}.
         *
         * @return this builder
         */
        public Builder noXmp() {
            this.xmp = null;
            return this;
        }

        /**
         * Returns the options holding the settings of this builder.
         *
         * @return the options
         * @throws IllegalArgumentException if the compression level is not in [0, 9] or the loop
         * count is negative
         * @throws MediaException.Unsupported if the color profile is an ICC profile of a color space
         * other than RGB
         */
        public ApngEncoderOptions build() {
            Checks.inRange("compression level", compressionLevel, 0, 9);
            Checks.nonNegative("loop count", loopCount);
            if (colorProfile != null && colorProfile.colorSpace() != ProfileColorSpace.RGB) {
                throw new MediaException.Unsupported("apng " + colorProfile.colorSpace(),
                        "APNG frames take only RGB ICC profiles, not " + colorProfile.colorSpace());
            }
            return new ApngEncoderOptions(this);
        }
    }
}
