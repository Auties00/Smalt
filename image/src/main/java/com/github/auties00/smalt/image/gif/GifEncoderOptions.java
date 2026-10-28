package com.github.auties00.smalt.image.gif;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.color.ColorProfile;
import com.github.auties00.smalt.image.color.ProfileColorSpace;
import com.github.auties00.smalt.image.ops.Dithering;
import com.github.auties00.smalt.image.ops.QuantizeMethod;
import com.github.auties00.smalt.internal.check.Checks;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

/**
 * Holds the settings of a {@link GifEncoder} and a {@link GifAnimationEncoder}. Instances are
 * immutable and thread-safe; create them with {@link #builder()} or a preset such as
 * {@link #defaults()}.
 *
 * <p>A GIF image holds at most 256 colors, one of which may be fully transparent. An image, or
 * the changed part of an animation frame, with few enough colors always gets an exact palette, so
 * it is coded losslessly; the settings decide how any other picture is reduced to a palette.
 */
public final class GifEncoderOptions {
    /**
     * The largest number of plays an animation stores: the {@code NETSCAPE2.0} extension holds
     * up to 65535 repeats after the first play.
     */
    public static final int MAX_LOOP_COUNT = 65_536;

    /**
     * The options of {@link #defaults()}.
     */
    private static final GifEncoderOptions DEFAULTS = new Builder().build();

    /**
     * The options of {@link #lossless()}.
     */
    private static final GifEncoderOptions LOSSLESS = new Builder().lossless(true).build();

    private final QuantizeMethod method;

    private final int sampleFactor;

    private final int maxColors;

    private final Dithering dithering;

    private final int alphaThreshold;

    private final PaletteMode paletteMode;

    private final boolean interlaced;

    private final int loopCount;

    private final boolean lossless;

    private final ColorProfile colorProfile;

    private final byte[] xmp;

    private GifEncoderOptions(Builder builder) {
        this.method = builder.method;
        this.sampleFactor = builder.sampleFactor;
        this.maxColors = builder.maxColors;
        this.dithering = builder.dithering;
        this.alphaThreshold = builder.alphaThreshold;
        this.paletteMode = builder.paletteMode;
        this.interlaced = builder.interlaced;
        this.loopCount = builder.loopCount;
        this.lossless = builder.lossless;
        this.colorProfile = builder.colorProfile;
        this.xmp = builder.xmp;
    }

    /**
     * Returns median-cut quantization into palettes of up to 256 colors, Sierra dithering, alpha
     * threshold 128, {@link PaletteMode#AUTO} palettes, non-interlaced rows and animations that
     * loop forever.
     *
     * @implNote This implementation defaults to median cut and Sierra because, on natural video,
     * that pair gave both the smallest files and the highest quality of the quantizer and dithering
     * pairs, and the fastest encoding of the error-diffusing pairs.
     * @return the default options
     */
    public static GifEncoderOptions defaults() {
        return DEFAULTS;
    }

    /**
     * Returns the {@link #defaults()} with lossless coding: every image and every changed part of
     * an animation frame must have few enough colors for an exact palette, and every alpha must
     * be 0 or 255, otherwise encoding fails instead of quantizing.
     *
     * @return the options
     */
    public static GifEncoderOptions lossless() {
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
     * {@return the algorithm that builds a palette when a picture has more colors than the palette
     * holds; {@link QuantizeMethod#MEDIAN_CUT} by default}
     *
     * <p>{@link QuantizeMethod#NEUQUANT} trains on a sample of the colors whose size
     * {@link #sampleFactor()} sets; median cut works on a 5-bit-per-channel histogram.
     */
    public QuantizeMethod method() {
        return method;
    }

    /**
     * {@return the NeuQuant sampling factor, from 1 (best) to 30 (fastest): the network trains on
     * one color in this many; 10 by default}
     *
     * <p>{@link QuantizeMethod#MEDIAN_CUT} ignores it.
     */
    public int sampleFactor() {
        return sampleFactor;
    }

    /**
     * {@return the largest number of palette entries, the transparent entry included, from 2 to
     * 256; 256 by default}
     */
    public int maxColors() {
        return maxColors;
    }

    /**
     * {@return how pixels are mapped to a palette that lacks their exact colors;
     * {@link Dithering#SIERRA} by default}
     */
    public Dithering dithering() {
        return dithering;
    }

    /**
     * {@return the alpha below which a pixel is coded transparent, from 0 (every pixel opaque) to
     * 255 (only pixels of alpha 255 opaque); 128 by default}
     *
     * <p>GIF transparency is binary, so any pixel whose alpha is not below the threshold is opaque.
     * Pictures without alpha ignore it.
     */
    public int alphaThreshold() {
        return alphaThreshold;
    }

    /**
     * {@return when the frames of an animation share the global palette and when they carry their
     * own; {@link PaletteMode#AUTO} by default}
     */
    public PaletteMode paletteMode() {
        return paletteMode;
    }

    /**
     * {@return whether the rows are stored in the four-pass interlaced order; {@code false} by
     * default}
     */
    public boolean isInterlaced() {
        return interlaced;
    }

    /**
     * {@return the number of times an animation plays, from 0 to {@link #MAX_LOOP_COUNT}, 0 meaning
     * forever; 0 by default}
     *
     * <p>A still image ignores it.
     */
    public int loopCount() {
        return loopCount;
    }

    /**
     * {@return whether a picture that a GIF cannot hold exactly is rejected instead of quantized;
     * {@code false} by default}
     */
    public boolean isLossless() {
        return lossless;
    }

    /**
     * {@return the color profile embedded in the output, or empty when none is; empty by default}
     *
     * <p>The profile tells readers which color space the pixels are in; it does not convert them.
     * Without one, readers take the pixels as sRGB. Its ICC bytes, which must describe an RGB color
     * space, are stored in an {@code ICCRGBG1} application extension (ICC.1 Annex B) that follows
     * the global color table; a high-dynamic-range profile is written in its ICC form.
     */
    public Optional<ColorProfile> colorProfile() {
        return Optional.ofNullable(colorProfile);
    }

    /**
     * {@return a copy of the XMP packet written in an {@code XMP DataXMP} application extension, in UTF-8, or
     * empty for none; empty by default}
     *
     * <p>The extension follows the logical screen descriptor and any {@code ICCRGBG1} extension, before the
     * first image; it holds the packet as raw data sub-blocks followed by the 258-byte magic trailer of XMP
     * specification part 3, so that GIF readers that do not know it skip it. A GIF holds no EXIF block.
     */
    public Optional<byte[]> xmp() {
        return Optional.ofNullable(xmp).map(byte[]::clone);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof GifEncoderOptions that
                && method == that.method
                && sampleFactor == that.sampleFactor
                && maxColors == that.maxColors
                && dithering == that.dithering
                && alphaThreshold == that.alphaThreshold
                && paletteMode == that.paletteMode
                && interlaced == that.interlaced
                && loopCount == that.loopCount
                && lossless == that.lossless
                && Objects.equals(colorProfile, that.colorProfile)
                && Arrays.equals(xmp, that.xmp);
    }

    @Override
    public int hashCode() {
        return Objects.hash(method, sampleFactor, maxColors, dithering, alphaThreshold, paletteMode, interlaced,
                loopCount, lossless, colorProfile, Arrays.hashCode(xmp));
    }

    @Override
    public String toString() {
        return "GifEncoderOptions[method=" + method + ", sampleFactor=" + sampleFactor
                + ", maxColors=" + maxColors + ", dithering=" + dithering + ", alphaThreshold=" + alphaThreshold
                + ", paletteMode=" + paletteMode + ", interlaced=" + interlaced + ", loopCount=" + loopCount
                + ", lossless=" + lossless
                + (colorProfile == null ? "" : ", colorProfile=" + colorProfile.description().orElse("unnamed"))
                + (xmp == null ? "" : ", xmp=" + xmp.length + " bytes") + "]";
    }

    /**
     * A builder of {@link GifEncoderOptions}.
     *
     * <p>Setters reject only {@code null}; {@link #build()} checks the ranges. Instances are not
     * thread-safe.
     */
    public static final class Builder {
        private QuantizeMethod method = QuantizeMethod.MEDIAN_CUT;

        private int sampleFactor = 10;

        private int maxColors = 256;

        private Dithering dithering = Dithering.SIERRA;

        private int alphaThreshold = 128;

        private PaletteMode paletteMode = PaletteMode.AUTO;

        private boolean interlaced;

        private int loopCount;

        private boolean lossless;

        private ColorProfile colorProfile;

        private byte[] xmp;

        private Builder() {
        }

        private Builder(GifEncoderOptions options) {
            this.method = options.method;
            this.sampleFactor = options.sampleFactor;
            this.maxColors = options.maxColors;
            this.dithering = options.dithering;
            this.alphaThreshold = options.alphaThreshold;
            this.paletteMode = options.paletteMode;
            this.interlaced = options.interlaced;
            this.loopCount = options.loopCount;
            this.lossless = options.lossless;
            this.colorProfile = options.colorProfile;
            this.xmp = options.xmp;
        }

        /**
         * Sets the palette construction algorithm; see {@link GifEncoderOptions#method()}.
         *
         * @param method the value
         * @return this builder
         */
        public Builder method(QuantizeMethod method) {
            this.method = Objects.requireNonNull(method, "method");
            return this;
        }

        /**
         * Sets the NeuQuant sampling factor; see {@link GifEncoderOptions#sampleFactor()}.
         *
         * @param sampleFactor the value
         * @return this builder
         */
        public Builder sampleFactor(int sampleFactor) {
            this.sampleFactor = sampleFactor;
            return this;
        }

        /**
         * Sets the largest palette size; see {@link GifEncoderOptions#maxColors()}.
         *
         * @param maxColors the value
         * @return this builder
         */
        public Builder maxColors(int maxColors) {
            this.maxColors = maxColors;
            return this;
        }

        /**
         * Sets the dithering method; see {@link GifEncoderOptions#dithering()}.
         *
         * @param dithering the value
         * @return this builder
         */
        public Builder dithering(Dithering dithering) {
            this.dithering = Objects.requireNonNull(dithering, "dithering");
            return this;
        }

        /**
         * Sets the alpha threshold; see {@link GifEncoderOptions#alphaThreshold()}.
         *
         * @param alphaThreshold the value
         * @return this builder
         */
        public Builder alphaThreshold(int alphaThreshold) {
            this.alphaThreshold = alphaThreshold;
            return this;
        }

        /**
         * Sets the palette sharing policy of animations; see {@link GifEncoderOptions#paletteMode()}.
         *
         * @param paletteMode the value
         * @return this builder
         */
        public Builder paletteMode(PaletteMode paletteMode) {
            this.paletteMode = Objects.requireNonNull(paletteMode, "paletteMode");
            return this;
        }

        /**
         * Sets whether the rows are interlaced; see {@link GifEncoderOptions#isInterlaced()}.
         *
         * @param interlaced the value
         * @return this builder
         */
        public Builder interlaced(boolean interlaced) {
            this.interlaced = interlaced;
            return this;
        }

        /**
         * Sets the number of times an animation plays; see {@link GifEncoderOptions#loopCount()}.
         *
         * @param loopCount the value
         * @return this builder
         */
        public Builder loopCount(int loopCount) {
            this.loopCount = loopCount;
            return this;
        }

        /**
         * Sets whether pictures that need quantization are rejected; see
         * {@link GifEncoderOptions#isLossless()}.
         *
         * @param lossless the value
         * @return this builder
         */
        public Builder lossless(boolean lossless) {
            this.lossless = lossless;
            return this;
        }

        /**
         * Sets the color profile embedded in the output; see {@link GifEncoderOptions#colorProfile()}.
         *
         * @param colorProfile the value
         * @return this builder
         */
        public Builder colorProfile(ColorProfile colorProfile) {
            this.colorProfile = Objects.requireNonNull(colorProfile, "colorProfile");
            return this;
        }

        /**
         * Removes the color profile; see {@link GifEncoderOptions#colorProfile()}.
         *
         * @return this builder
         */
        public Builder noColorProfile() {
            this.colorProfile = null;
            return this;
        }

        /**
         * Sets the XMP packet; see {@link GifEncoderOptions#xmp()}.
         *
         * @param xmp the value; copied
         * @return this builder
         */
        public Builder xmp(byte[] xmp) {
            this.xmp = Objects.requireNonNull(xmp, "xmp").clone();
            return this;
        }

        /**
         * Writes no XMP packet; see {@link GifEncoderOptions#xmp()}.
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
         * @throws IllegalArgumentException if the sample factor is not in [1, 30], the palette size is
         * not in [2, 256], the alpha threshold is not in [0, 255], the loop count is not in [0,
         * {@link GifEncoderOptions#MAX_LOOP_COUNT}] or the XMP packet holds a zero byte, which the extension
         * cannot carry (a UTF-16 or UTF-32 packet)
         * @throws MediaException.Unsupported if the color profile does not describe an RGB color space
         */
        public GifEncoderOptions build() {
            Checks.inRange("sample factor", sampleFactor, 1, 30);
            if (xmp != null) {
                for (var value : xmp) {
                    if (value == 0) {
                        throw new IllegalArgumentException("a GIF XMP packet cannot hold a zero byte");
                    }
                }
            }
            Checks.inRange("palette size", maxColors, 2, 256);
            Checks.inRange("alpha threshold", alphaThreshold, 0, 255);
            Checks.inRange("loop count", loopCount, 0, MAX_LOOP_COUNT);
            if (colorProfile != null && colorProfile.colorSpace() != ProfileColorSpace.RGB) {
                throw new MediaException.Unsupported("gif " + colorProfile.colorSpace(),
                        "GIF holds only RGB ICC profiles, not " + colorProfile.colorSpace());
            }
            return new GifEncoderOptions(this);
        }
    }

    /**
     * Enumerates the policies for sharing the global palette between the frames of an animation.
     *
     * <p>The global palette is the palette of the first frame. A frame whose changed pixels all
     * have colors of the global palette always uses it, whatever the policy.
     */
    public enum PaletteMode {
        /**
         * A frame uses the global palette when quantizing it to the global palette is about as
         * accurate as to a palette of its own, and carries its own palette otherwise.
         */
        AUTO,

        /**
         * Every frame uses the global palette, unless it needs a transparent entry the global
         * palette lacks or lossless coding requires its own colors.
         */
        GLOBAL,

        /**
         * Every frame whose colors are not all in the global palette carries a palette of its
         * own.
         */
        LOCAL
    }
}
