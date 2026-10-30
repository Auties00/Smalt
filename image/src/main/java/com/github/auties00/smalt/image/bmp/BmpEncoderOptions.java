package com.github.auties00.smalt.image.bmp;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.PixelDensity;
import com.github.auties00.smalt.image.color.ColorProfile;
import com.github.auties00.smalt.image.color.ProfileColorSpace;
import com.github.auties00.smalt.image.internal.density.DensityTerms;
import com.github.auties00.smalt.image.ops.Dithering;
import com.github.auties00.smalt.image.ops.QuantizeMethod;

import java.util.Objects;
import java.util.Optional;

/**
 * Holds the settings of a {@link BmpEncoder}. Instances are immutable and thread-safe; create them with
 * {@link #builder()} or a preset such as {@link #defaults()}.
 */
public final class BmpEncoderOptions {
    /**
     * The options of {@link #defaults()}.
     */
    private static final BmpEncoderOptions DEFAULTS = new Builder().build();

    private final PixelFormat pixelFormat;

    private final boolean runLength;

    private final HeaderVersion headerVersion;

    private final ColorProfile colorProfile;

    private final boolean topDown;

    private final PixelDensity density;

    private final QuantizeMethod method;

    private final Dithering dithering;

    private BmpEncoderOptions(Builder builder) {
        this.pixelFormat = builder.pixelFormat;
        this.runLength = builder.runLength;
        this.headerVersion = builder.headerVersion;
        this.colorProfile = builder.colorProfile;
        this.topDown = builder.topDown;
        this.density = builder.density;
        this.method = builder.method;
        this.dithering = builder.dithering;
    }

    /**
     * Returns the automatic pixel format and header version, uncompressed bottom-up rows, no color
     * profile and no resolution.
     *
     * @return the default options
     */
    public static BmpEncoderOptions defaults() {
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
     * {@return the layout of the stored pixels, or empty to choose it from the picture; empty by default}
     *
     * <p>The automatic choice keeps every color of an 8-bit picture: {@link PixelFormat#ARGB_8888} when
     * the picture has alpha and some pixel is not opaque, unless the header version is
     * {@link HeaderVersion#INFO}, which holds no alpha; otherwise the palette of the fewest bits that holds
     * every color of the picture ({@link PixelFormat#PALETTE_1}, {@link PixelFormat#PALETTE_4} or
     * {@link PixelFormat#PALETTE_8}), or {@link PixelFormat#RGB_888} when it has more than 256 colors.
     * With {@link #isRunLength()} it is {@link PixelFormat#PALETTE_4} for a picture of at most 16 colors
     * and {@link PixelFormat#PALETTE_8} otherwise.
     */
    public Optional<PixelFormat> pixelFormat() {
        return Optional.ofNullable(pixelFormat);
    }

    /**
     * {@return whether palette indices are run-length encoded ({@code BI_RLE4} for
     * {@link PixelFormat#PALETTE_4}, {@code BI_RLE8} for {@link PixelFormat#PALETTE_8}); {@code false}
     * by default}
     *
     * <p>Run-length encoding needs a 4- or 8-bit palette and bottom-up rows. Every pixel is coded, so a
     * decoder sets every pixel; a row of pixels that do not repeat takes a little more space than
     * uncompressed, other rows less.
     */
    public boolean isRunLength() {
        return runLength;
    }

    /**
     * {@return the information header written, or empty for the smallest one that holds the
     * settings; empty by default}
     *
     * <p>The automatic choice is {@link HeaderVersion#V5} for {@link PixelFormat#ARGB_8888} and with a
     * color profile, and {@link HeaderVersion#INFO} otherwise.
     */
    public Optional<HeaderVersion> headerVersion() {
        return Optional.ofNullable(headerVersion);
    }

    /**
     * {@return the color profile embedded in the output, or empty when none is; empty by default}
     *
     * <p>The profile tells readers which color space the pixels are in; it does not convert them.
     * Without one, a V4 or V5 header declares sRGB. Its ICC bytes, which must describe an RGB color
     * space, follow the pixel data and are referenced by a V5 header with the {@code PROFILE_EMBEDDED}
     * color space type; a high-dynamic-range profile is written in its ICC form.
     */
    public Optional<ColorProfile> colorProfile() {
        return Optional.ofNullable(colorProfile);
    }

    /**
     * {@return whether the rows are stored top row first, with a negative height; {@code false} (the
     * bottom row first, which every reader supports) by default}
     */
    public boolean isTopDown() {
        return topDown;
    }

    /**
     * {@return the pixel density written in the information header, or empty to leave its resolution
     * unspecified (0 pixels per meter); empty by default}
     *
     * <p>The header stores whole pixels per meter from 1 to {@code 2^31 - 1}: a physical density is converted
     * to pixels per meter and rounded, so that 72 dots per inch is written as 2835. An aspect ratio is not
     * written, since the header has no unitless form.
     */
    public Optional<PixelDensity> density() {
        return Optional.ofNullable(density);
    }

    /**
     * {@return the horizontal and vertical pixels per meter written in the information header, 0 for an
     * unspecified resolution}
     */
    long[] pixelsPerMeter() {
        if (density == null || !density.isPhysical()) {
            return new long[2];
        }
        return DensityTerms.rounded(density, PixelDensity.Unit.DOTS_PER_METER, Integer.MAX_VALUE);
    }

    /**
     * {@return the algorithm that builds a palette when a picture has more colors than the palette
     * holds; {@link QuantizeMethod#MEDIAN_CUT} by default}
     */
    public QuantizeMethod method() {
        return method;
    }

    /**
     * {@return how pixels are mapped to a palette that lacks their exact colors;
     * {@link Dithering#FLOYD_STEINBERG} by default}
     *
     * <p>A picture whose colors all fit the palette is never dithered.
     */
    public Dithering dithering() {
        return dithering;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof BmpEncoderOptions that
                && pixelFormat == that.pixelFormat
                && runLength == that.runLength
                && headerVersion == that.headerVersion
                && Objects.equals(colorProfile, that.colorProfile)
                && topDown == that.topDown
                && Objects.equals(density, that.density)
                && method == that.method
                && dithering == that.dithering;
    }

    @Override
    public int hashCode() {
        return Objects.hash(pixelFormat, runLength, headerVersion, colorProfile, topDown, density, method,
                dithering);
    }

    @Override
    public String toString() {
        return "BmpEncoderOptions[pixelFormat=" + (pixelFormat == null ? "automatic" : pixelFormat)
                + (runLength ? ", runLength" : "")
                + ", headerVersion=" + (headerVersion == null ? "automatic" : headerVersion)
                + (colorProfile == null ? "" : ", colorProfile=" + colorProfile.description().orElse("unnamed"))
                + (topDown ? ", topDown" : "")
                + (density == null ? "" : ", density=" + density)
                + ", method=" + method + ", dithering=" + dithering + "]";
    }

    /**
     * A builder of {@link BmpEncoderOptions}.
     *
     * <p>Setters reject only {@code null}; {@link #build()} checks the ranges, the color profile and the
     * combination of settings. Instances are not thread-safe.
     */
    public static final class Builder {
        private PixelFormat pixelFormat;

        private boolean runLength;

        private HeaderVersion headerVersion;

        private ColorProfile colorProfile;

        private boolean topDown;

        private PixelDensity density;

        private QuantizeMethod method = QuantizeMethod.MEDIAN_CUT;

        private Dithering dithering = Dithering.FLOYD_STEINBERG;

        private Builder() {
        }

        private Builder(BmpEncoderOptions options) {
            this.pixelFormat = options.pixelFormat;
            this.runLength = options.runLength;
            this.headerVersion = options.headerVersion;
            this.colorProfile = options.colorProfile;
            this.topDown = options.topDown;
            this.density = options.density;
            this.method = options.method;
            this.dithering = options.dithering;
        }

        /**
         * Sets the layout of the stored pixels; see {@link BmpEncoderOptions#pixelFormat()}.
         *
         * @param pixelFormat the value
         * @return this builder
         */
        public Builder pixelFormat(PixelFormat pixelFormat) {
            this.pixelFormat = Objects.requireNonNull(pixelFormat, "pixelFormat");
            return this;
        }

        /**
         * Chooses the layout of the stored pixels from the picture; see
         * {@link BmpEncoderOptions#pixelFormat()}.
         *
         * @return this builder
         */
        public Builder automaticPixelFormat() {
            this.pixelFormat = null;
            return this;
        }

        /**
         * Sets whether palette indices are run-length encoded; see {@link BmpEncoderOptions#isRunLength()}.
         *
         * @param runLength the value
         * @return this builder
         */
        public Builder runLength(boolean runLength) {
            this.runLength = runLength;
            return this;
        }

        /**
         * Sets the information header written; see {@link BmpEncoderOptions#headerVersion()}.
         *
         * @param headerVersion the value
         * @return this builder
         */
        public Builder headerVersion(HeaderVersion headerVersion) {
            this.headerVersion = Objects.requireNonNull(headerVersion, "headerVersion");
            return this;
        }

        /**
         * Writes the smallest information header that holds the settings; see
         * {@link BmpEncoderOptions#headerVersion()}.
         *
         * @return this builder
         */
        public Builder automaticHeaderVersion() {
            this.headerVersion = null;
            return this;
        }

        /**
         * Sets the color profile embedded in the output; see {@link BmpEncoderOptions#colorProfile()}.
         *
         * @param colorProfile the value
         * @return this builder
         */
        public Builder colorProfile(ColorProfile colorProfile) {
            this.colorProfile = Objects.requireNonNull(colorProfile, "colorProfile");
            return this;
        }

        /**
         * Removes the color profile; see {@link BmpEncoderOptions#colorProfile()}.
         *
         * @return this builder
         */
        public Builder noColorProfile() {
            this.colorProfile = null;
            return this;
        }

        /**
         * Sets whether the rows are stored top row first; see {@link BmpEncoderOptions#isTopDown()}.
         *
         * @param topDown the value
         * @return this builder
         */
        public Builder topDown(boolean topDown) {
            this.topDown = topDown;
            return this;
        }

        /**
         * Sets the pixel density; see {@link BmpEncoderOptions#density()}.
         *
         * @param density the value
         * @return this builder
         */
        public Builder density(PixelDensity density) {
            this.density = Objects.requireNonNull(density, "density");
            return this;
        }

        /**
         * Leaves the resolution unspecified; see {@link BmpEncoderOptions#density()}.
         *
         * @return this builder
         */
        public Builder noDensity() {
            this.density = null;
            return this;
        }

        /**
         * Sets the palette construction algorithm; see {@link BmpEncoderOptions#method()}.
         *
         * @param method the value
         * @return this builder
         */
        public Builder method(QuantizeMethod method) {
            this.method = Objects.requireNonNull(method, "method");
            return this;
        }

        /**
         * Sets the dithering method; see {@link BmpEncoderOptions#dithering()}.
         *
         * @param dithering the value
         * @return this builder
         */
        public Builder dithering(Dithering dithering) {
            this.dithering = Objects.requireNonNull(dithering, "dithering");
            return this;
        }

        /**
         * Returns the options holding the settings of this builder.
         *
         * @return the options
         * @throws IllegalArgumentException if a physical density rounds to 0 or to more than {@code 2^31 - 1}
         * pixels per meter; if run-length encoding is combined
         * with top-down rows or with a pixel format other than {@link PixelFormat#PALETTE_4} and
         * {@link PixelFormat#PALETTE_8}; or if the header version cannot hold the settings
         * ({@link HeaderVersion#INFO} with {@link PixelFormat#ARGB_8888} or a color profile,
         * {@link HeaderVersion#V4} with a color profile)
         * @throws MediaException.Unsupported if the color profile describes a color space other than RGB
         */
        public BmpEncoderOptions build() {
            if (density != null && density.isPhysical()) {
                DensityTerms.rounded(density, PixelDensity.Unit.DOTS_PER_METER, Integer.MAX_VALUE);
            }
            if (runLength && topDown) {
                throw new IllegalArgumentException("run-length encoded bitmaps are stored bottom-up");
            }
            if (runLength && pixelFormat != null && pixelFormat != PixelFormat.PALETTE_4
                    && pixelFormat != PixelFormat.PALETTE_8) {
                throw new IllegalArgumentException("run-length encoding needs a 4- or 8-bit palette, not " + pixelFormat);
            }
            if (headerVersion == HeaderVersion.INFO && (pixelFormat == PixelFormat.ARGB_8888 || colorProfile != null)) {
                throw new IllegalArgumentException("a BITMAPINFOHEADER holds neither an alpha mask nor a color profile");
            }
            if (headerVersion == HeaderVersion.V4 && colorProfile != null) {
                throw new IllegalArgumentException("a BITMAPV4HEADER cannot embed a color profile");
            }
            if (colorProfile != null && colorProfile.colorSpace() != ProfileColorSpace.RGB) {
                throw new MediaException.Unsupported("bmp " + colorProfile.colorSpace(),
                        "BMP holds only RGB ICC profiles, not " + colorProfile.colorSpace());
            }
            return new BmpEncoderOptions(this);
        }
    }

    /**
     * Enumerates the layouts of the stored pixels.
     */
    public enum PixelFormat {
        /**
         * 1-bit indices into a color table of up to 2 entries ({@code BI_RGB}).
         */
        PALETTE_1,

        /**
         * 4-bit indices into a color table of up to 16 entries ({@code BI_RGB} or {@code BI_RLE4}).
         */
        PALETTE_4,

        /**
         * 8-bit indices into a color table of up to 256 entries ({@code BI_RGB} or {@code BI_RLE8}).
         */
        PALETTE_8,

        /**
         * 16-bit pixels of 5 bits per color channel, the top bit unused ({@code BI_RGB}).
         */
        RGB_555,

        /**
         * 16-bit pixels of 5 red, 6 green and 5 blue bits ({@code BI_BITFIELDS}).
         */
        RGB_565,

        /**
         * 24-bit blue, green, red pixels ({@code BI_RGB}).
         */
        RGB_888,

        /**
         * 32-bit blue, green, red pixels with an unused zero byte ({@code BI_RGB}).
         */
        XRGB_8888,

        /**
         * 32-bit blue, green, red, straight alpha pixels, with the alpha mask {@code 0xFF000000} of a V4 or
         * V5 header ({@code BI_BITFIELDS}).
         */
        ARGB_8888
    }

    /**
     * Enumerates the information headers written.
     */
    public enum HeaderVersion {
        /**
         * The 40-byte {@code BITMAPINFOHEADER}, which every reader supports; the masks of
         * {@link PixelFormat#RGB_565} follow it.
         */
        INFO,

        /**
         * The 108-byte {@code BITMAPV4HEADER}, which adds the color masks and the color space, sRGB.
         */
        V4,

        /**
         * The 124-byte {@code BITMAPV5HEADER}, which adds the rendering intent and the embedded profile.
         */
        V5
    }
}
