package com.github.auties00.smalt.image.raw;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.internal.check.Checks;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Holds the settings of a {@link RawDecoder}. Instances are immutable and thread-safe; create them with
 * {@link #builder()} or a preset such as {@link #defaults()}.
 */
public final class RawDecoderOptions {
    private static final RawDecoderOptions DEFAULTS = builder().build();

    private final MediaLimits limits;
    private final int minWidth;
    private final int minHeight;
    private final boolean embeddedPreview;
    private final RawDemosaic demosaic;
    private final RawWhiteBalance whiteBalance;
    private final RawHighlights highlights;
    private final RawToneCurve toneCurve;
    private final double exposure;
    private final RawColorSpace colorSpace;
    private final boolean highBitDepth;
    private final boolean opcodes;
    private final boolean defaultCrop;
    private final double[] colorMatrix;
    private final boolean enhancedImage;
    private final boolean userCrop;
    private final boolean autoBlack;
    private final String cameraProfile;
    private final boolean lensCorrections;
    private final int frame;

    private RawDecoderOptions(Builder builder) {
        this.limits = builder.limits;
        this.minWidth = builder.minWidth;
        this.minHeight = builder.minHeight;
        this.embeddedPreview = builder.embeddedPreview;
        this.demosaic = builder.demosaic;
        this.whiteBalance = builder.whiteBalance;
        this.highlights = builder.highlights;
        this.toneCurve = builder.toneCurve;
        this.exposure = builder.exposure;
        this.colorSpace = builder.colorSpace;
        this.highBitDepth = builder.highBitDepth;
        this.opcodes = builder.opcodes;
        this.defaultCrop = builder.defaultCrop;
        this.colorMatrix = builder.colorMatrix == null ? null : builder.colorMatrix.clone();
        this.enhancedImage = builder.enhancedImage;
        this.userCrop = builder.userCrop;
        this.autoBlack = builder.autoBlack;
        this.cameraProfile = builder.cameraProfile;
        this.lensCorrections = builder.lensCorrections;
        this.frame = builder.allFrames ? -1 : builder.frame;
    }

    /**
     * Returns the default rendering: Menon demosaicing, the as-shot white balance, clipped
     * highlights, the standard tone curve, 8-bit sRGB output, the DNG opcode lists and default
     * crop applied, at full size under {@link MediaLimits#DEFAULT}.
     *
     * @return the default options
     */
    public static RawDecoderOptions defaults() {
        return DEFAULTS;
    }

    /**
     * Returns the default rendering with bilinear demosaicing: several times faster, with softer
     * detail.
     *
     * @return the fast options
     */
    public static RawDecoderOptions fast() {
        return builder().demosaic(RawDemosaic.BILINEAR).build();
    }

    /**
     * Returns options for further processing: 16-bit linear ITU-R BT.2020 output without a tone
     * curve, highlights reconstructed, never from an embedded preview.
     *
     * @return the linear options
     */
    public static RawDecoderOptions linear() {
        return builder().colorSpace(RawColorSpace.LINEAR_REC2020).highBitDepth(true)
                .toneCurve(RawToneCurve.LINEAR).highlights(RawHighlights.BLEND).embeddedPreview(false).build();
    }

    /**
     * Returns the default options with a minimum size hint, which lets the decoder meet the hint
     * from an embedded preview or a half-size rendering when either is large enough.
     *
     * @param minWidth the smallest acceptable width, at least 0
     * @param minHeight the smallest acceptable height, at least 0
     * @return the options
     * @throws IllegalArgumentException if a size is negative
     * @see #minWidth()
     */
    public static RawDecoderOptions ofMinimumSize(int minWidth, int minHeight) {
        return builder().minSize(minWidth, minHeight).build();
    }

    /**
     * Returns the raw options equivalent to generic image decoder options: their limits, size hint
     * and bit depth choice, with sRGB output (which also satisfies their sRGB conversion).
     *
     * @param options the image decoder options
     * @return the raw decoder options
     */
    public static RawDecoderOptions of(ImageDecoderOptions options) {
        Objects.requireNonNull(options, "options");
        return builder().limits(options.limits()).minSize(options.minWidth(), options.minHeight())
                .highBitDepth(options.highBitDepth()).build();
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
        var builder = new Builder();
        builder.limits = limits;
        builder.minWidth = minWidth;
        builder.minHeight = minHeight;
        builder.embeddedPreview = embeddedPreview;
        builder.demosaic = demosaic;
        builder.whiteBalance = whiteBalance;
        builder.highlights = highlights;
        builder.toneCurve = toneCurve;
        builder.exposure = exposure;
        builder.colorSpace = colorSpace;
        builder.highBitDepth = highBitDepth;
        builder.opcodes = opcodes;
        builder.defaultCrop = defaultCrop;
        builder.colorMatrix = colorMatrix;
        builder.enhancedImage = enhancedImage;
        builder.userCrop = userCrop;
        builder.autoBlack = autoBlack;
        builder.cameraProfile = cameraProfile;
        builder.lensCorrections = lensCorrections;
        builder.frame = Math.max(frame, 0);
        builder.allFrames = frame < 0;
        return builder;
    }

    /**
     * {@return the resource bounds checked before every allocation; {@link MediaLimits#DEFAULT} by default}
     */
    public MediaLimits limits() {
        return limits;
    }

    /**
     * {@return the smallest acceptable decoded width in pixels, at least 0; 0 (no hint) by default}
     *
     * <p>With a hint set, the decoder returns the smallest of these that is at least
     * {@code minWidth()} wide and {@link #minHeight()} tall: the largest embedded JPEG preview (when
     * {@link #embeddedPreview()} allows it), a half-size rendering of the sensor image (one output
     * pixel per 2x2 Bayer quad, with no demosaicing), or the full rendering. A hint of 0 in both
     * dimensions asks for the full rendering. The hint is compared against the stored (unrotated)
     * dimensions.
     */
    public int minWidth() {
        return minWidth;
    }

    /**
     * {@return the smallest acceptable decoded height in pixels, at least 0; 0 (no hint) by default}
     *
     * <p>The hint applies as {@link #minWidth()} describes.
     */
    public int minHeight() {
        return minHeight;
    }

    /**
     * {@return whether a size hint may be met from the largest embedded JPEG preview, the camera's own
     * rendering, instead of rendering the sensor data; {@code true} by default}
     */
    public boolean embeddedPreview() {
        return embeddedPreview;
    }

    /**
     * {@return the demosaicing algorithm; {@link RawDemosaic#MENON} by default}
     */
    public RawDemosaic demosaic() {
        return demosaic;
    }

    /**
     * {@return the white balance; {@link RawWhiteBalance#asShot()} by default}
     */
    public RawWhiteBalance whiteBalance() {
        return whiteBalance;
    }

    /**
     * {@return the treatment of clipped highlights; {@link RawHighlights#CLIP} by default}
     */
    public RawHighlights highlights() {
        return highlights;
    }

    /**
     * {@return the tone curve; {@link RawToneCurve#STANDARD} by default}
     */
    public RawToneCurve toneCurve() {
        return toneCurve;
    }

    /**
     * {@return the exposure adjustment in stops, from -10 to 10; 0 by default}
     *
     * <p>The adjustment is added to the file's baseline exposure under the standard tone curve.
     */
    public double exposure() {
        return exposure;
    }

    /**
     * {@return the output color space; {@link RawColorSpace#SRGB} by default}
     */
    public RawColorSpace colorSpace() {
        return colorSpace;
    }

    /**
     * {@return whether the output has 16 bits per sample ({@link com.github.auties00.smalt.image.Rgba16Picture})
     * rather than 8 ({@link com.github.auties00.smalt.image.RgbaPicture}); {@code false} by default}
     */
    public boolean highBitDepth() {
        return highBitDepth;
    }

    /**
     * {@return whether the opcode lists of a DNG file (lens shading gain maps, vignetting and distortion
     * corrections, bad pixel fixes) and the lens shading and bad pixel corrections of a Sigma X3F file
     * are applied; {@code true} by default}
     */
    public boolean opcodes() {
        return opcodes;
    }

    /**
     * {@return whether the image is cropped to the file's default crop rather than returned with the
     * whole area of the sensor that holds image data; {@code true} by default}
     */
    public boolean defaultCrop() {
        return defaultCrop;
    }

    /**
     * {@return a copy of the XYZ to camera matrix that replaces the file's own color matrices; empty (the
     * file's matrices, or the generic fallback) by default}
     *
     * <p>The values, row by row, map CIE XYZ under D65 to the camera's channels as a DNG
     * {@code ColorMatrix} does, for example from an Adobe DNG Converter file of the same camera model:
     * nine values (three rows) for a sensor of red, green and blue, twelve (four rows, in the order of
     * the file's color planes) for a four-color sensor. They are finite, and the matrix has full column
     * rank. A matrix whose rows differ in number from the sensor's colors cannot replace its
     * matrices: decoding with one throws {@link com.github.auties00.smalt.MediaException.Unsupported}.
     */
    public Optional<double[]> colorMatrix() {
        return colorMatrix == null ? Optional.empty() : Optional.of(colorMatrix.clone());
    }

    /**
     * {@return whether a DNG file's enhanced image (DNG 1.5, {@code NewSubFileType} 16: a demosaiced,
     * typically denoised or upscaled version of the raw image that software stored with it) is
     * rendered and returned instead of the raw image; {@code false} by default}
     *
     * <p>The enhanced image is rendered with the file's camera profile, crop and scale (scaled to its
     * size when its resolution differs). A file without one, or whose enhanced image cannot be decoded,
     * is decoded from its raw image. {@link RawDecoder#decodeRaw} then returns the enhanced image's
     * three linear channels.
     */
    public boolean enhancedImage() {
        return enhancedImage;
    }

    /**
     * {@return whether the image is cropped further to the file's default user crop (DNG
     * {@code DefaultUserCrop}, the crop the photographer chose, such as an aspect ratio set in the
     * camera); {@code false} by default}
     *
     * <p>The user crop lies within the default crop and applies only when {@link #defaultCrop()} does.
     */
    public boolean userCrop() {
        return userCrop;
    }

    /**
     * {@return whether the rendering subtracts an automatically determined black point (flare) from
     * the image, as a raw converter does by default; {@code false} by default}
     *
     * <p>The black point is measured on the image: the luminance below which its darkest 0.01% of
     * pixels lie, at most 2% of white. A camera profile whose {@code DefaultBlackRender} is None turns
     * the subtraction off, since its tables or tone curve already set the black level. It applies only
     * under {@link RawToneCurve#STANDARD}.
     */
    public boolean autoBlack() {
        return autoBlack;
    }

    /**
     * {@return the name of the DNG camera profile the file is rendered with; empty (the as-shot
     * profile the file names, or else its primary profile) by default}
     *
     * <p>{@link RawInfo#cameraProfiles()} lists the profiles a file holds. Decoding a file that holds
     * no profile of this name throws {@link com.github.auties00.smalt.MediaException.Unsupported}.
     */
    public Optional<String> cameraProfile() {
        return Optional.ofNullable(cameraProfile);
    }

    /**
     * {@return whether the lens corrections a camera raw file records for its shot (radial distortion, lateral
     * chromatic aberration and vignetting, from the maker note of Fujifilm, Olympus, Panasonic and Sony files) are
     * applied to the rendering; {@code true} by default}
     *
     * <p>The corrections apply to the rendering of files that hold no DNG opcode list 3 of their own, after
     * demosaicing, as DNG {@code FixVignetteRadial} and {@code WarpRectilinear} opcodes would; a DNG file
     * converted with them on carries them as its opcode list 3. {@link RawDecoder#decodeRaw} returns the sensor
     * values either way. A half-size rendering (see {@link #minWidth()}) does not apply them.
     */
    public boolean lensCorrections() {
        return lensCorrections;
    }

    /**
     * {@return the frame of a multi-frame pixel-shift file that is decoded alone, from 0; empty (all the frames,
     * merged into one image of red, green and blue at every pixel) by default}
     *
     * <p>{@link RawInfo#frameCount()} gives a file's frames. Frame 0 of a file of one frame is its image; decoding
     * a frame the file does not hold throws {@link com.github.auties00.smalt.MediaException.Unsupported}. A
     * single frame is a color filter array image, which renders like the file of a single shot.
     */
    public OptionalInt frame() {
        return frame < 0 ? OptionalInt.empty() : OptionalInt.of(frame);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof RawDecoderOptions that && limits.equals(that.limits) && minWidth == that.minWidth
                && minHeight == that.minHeight && embeddedPreview == that.embeddedPreview && demosaic == that.demosaic
                && whiteBalance.equals(that.whiteBalance) && highlights == that.highlights
                && toneCurve == that.toneCurve && Double.compare(exposure, that.exposure) == 0
                && colorSpace == that.colorSpace && highBitDepth == that.highBitDepth && opcodes == that.opcodes
                && defaultCrop == that.defaultCrop && Arrays.equals(colorMatrix, that.colorMatrix)
                && enhancedImage == that.enhancedImage && userCrop == that.userCrop && autoBlack == that.autoBlack
                && Objects.equals(cameraProfile, that.cameraProfile) && lensCorrections == that.lensCorrections
                && frame == that.frame;
    }

    @Override
    public int hashCode() {
        return Objects.hash(limits, minWidth, minHeight, embeddedPreview, demosaic, whiteBalance, highlights,
                toneCurve, exposure, colorSpace, highBitDepth, opcodes, defaultCrop, Arrays.hashCode(colorMatrix),
                enhancedImage, userCrop, autoBlack, cameraProfile, lensCorrections, frame);
    }

    @Override
    public String toString() {
        return "RawDecoderOptions[limits=" + limits + ", minWidth=" + minWidth + ", minHeight=" + minHeight
                + ", embeddedPreview=" + embeddedPreview + ", demosaic=" + demosaic + ", whiteBalance=" + whiteBalance
                + ", highlights=" + highlights + ", toneCurve=" + toneCurve + ", exposure=" + exposure
                + ", colorSpace=" + colorSpace + ", highBitDepth=" + highBitDepth + ", opcodes=" + opcodes
                + ", defaultCrop=" + defaultCrop + (colorMatrix != null ? ", colorMatrix=" + Arrays.toString(colorMatrix) : "")
                + ", enhancedImage=" + enhancedImage + ", userCrop=" + userCrop + ", autoBlack=" + autoBlack
                + (cameraProfile != null ? ", cameraProfile=" + cameraProfile : "")
                + ", lensCorrections=" + lensCorrections + (frame >= 0 ? ", frame=" + frame : "")
                + "]";
    }

    /**
     * Builds {@link RawDecoderOptions}.
     *
     * <p>Setters store their value; {@link #build()} checks the ranges. Instances are not
     * thread-safe.
     */
    public static final class Builder {
        private MediaLimits limits = MediaLimits.DEFAULT;
        private int minWidth;
        private int minHeight;
        private boolean embeddedPreview = true;
        private RawDemosaic demosaic = RawDemosaic.MENON;
        private RawWhiteBalance whiteBalance = RawWhiteBalance.asShot();
        private RawHighlights highlights = RawHighlights.CLIP;
        private RawToneCurve toneCurve = RawToneCurve.STANDARD;
        private double exposure;
        private RawColorSpace colorSpace = RawColorSpace.SRGB;
        private boolean highBitDepth;
        private boolean opcodes = true;
        private boolean defaultCrop = true;
        private double[] colorMatrix;
        private boolean enhancedImage;
        private boolean userCrop;
        private boolean autoBlack;
        private String cameraProfile;
        private boolean lensCorrections = true;
        private int frame;
        private boolean allFrames = true;

        private Builder() {
        }

        /**
         * Sets the resource bounds; see {@link RawDecoderOptions#limits()}.
         *
         * @param limits the value
         * @return this builder
         */
        public Builder limits(MediaLimits limits) {
            this.limits = Objects.requireNonNull(limits, "limits");
            return this;
        }

        /**
         * Sets the minimum size hint; see {@link RawDecoderOptions#minWidth()}.
         *
         * @param minWidth the smallest acceptable decoded width
         * @param minHeight the smallest acceptable decoded height
         * @return this builder
         */
        public Builder minSize(int minWidth, int minHeight) {
            this.minWidth = minWidth;
            this.minHeight = minHeight;
            return this;
        }

        /**
         * Clears the minimum size hint, so the sensor image is rendered at full size; see
         * {@link RawDecoderOptions#minWidth()}.
         *
         * @return this builder
         */
        public Builder fullSize() {
            this.minWidth = 0;
            this.minHeight = 0;
            return this;
        }

        /**
         * Sets whether the size hint may be met from an embedded preview; see
         * {@link RawDecoderOptions#embeddedPreview()}.
         *
         * @param embeddedPreview the value
         * @return this builder
         */
        public Builder embeddedPreview(boolean embeddedPreview) {
            this.embeddedPreview = embeddedPreview;
            return this;
        }

        /**
         * Sets the demosaicing algorithm; see {@link RawDecoderOptions#demosaic()}.
         *
         * @param demosaic the value
         * @return this builder
         */
        public Builder demosaic(RawDemosaic demosaic) {
            this.demosaic = Objects.requireNonNull(demosaic, "demosaic");
            return this;
        }

        /**
         * Sets the white balance; see {@link RawDecoderOptions#whiteBalance()}.
         *
         * @param whiteBalance the value
         * @return this builder
         */
        public Builder whiteBalance(RawWhiteBalance whiteBalance) {
            this.whiteBalance = Objects.requireNonNull(whiteBalance, "whiteBalance");
            return this;
        }

        /**
         * Sets the treatment of clipped highlights; see {@link RawDecoderOptions#highlights()}.
         *
         * @param highlights the value
         * @return this builder
         */
        public Builder highlights(RawHighlights highlights) {
            this.highlights = Objects.requireNonNull(highlights, "highlights");
            return this;
        }

        /**
         * Sets the tone curve; see {@link RawDecoderOptions#toneCurve()}.
         *
         * @param toneCurve the value
         * @return this builder
         */
        public Builder toneCurve(RawToneCurve toneCurve) {
            this.toneCurve = Objects.requireNonNull(toneCurve, "toneCurve");
            return this;
        }

        /**
         * Sets the exposure adjustment; see {@link RawDecoderOptions#exposure()}.
         *
         * @param exposure the value
         * @return this builder
         */
        public Builder exposure(double exposure) {
            this.exposure = exposure;
            return this;
        }

        /**
         * Sets the output color space; see {@link RawDecoderOptions#colorSpace()}.
         *
         * @param colorSpace the value
         * @return this builder
         */
        public Builder colorSpace(RawColorSpace colorSpace) {
            this.colorSpace = Objects.requireNonNull(colorSpace, "colorSpace");
            return this;
        }

        /**
         * Sets whether the output has 16 bits per sample; see {@link RawDecoderOptions#highBitDepth()}.
         *
         * @param highBitDepth the value
         * @return this builder
         */
        public Builder highBitDepth(boolean highBitDepth) {
            this.highBitDepth = highBitDepth;
            return this;
        }

        /**
         * Sets whether the DNG opcode lists and the X3F corrections are applied; see
         * {@link RawDecoderOptions#opcodes()}.
         *
         * @param opcodes the value
         * @return this builder
         */
        public Builder opcodes(boolean opcodes) {
            this.opcodes = opcodes;
            return this;
        }

        /**
         * Sets whether the default crop is applied; see {@link RawDecoderOptions#defaultCrop()}.
         *
         * @param defaultCrop the value
         * @return this builder
         */
        public Builder defaultCrop(boolean defaultCrop) {
            this.defaultCrop = defaultCrop;
            return this;
        }

        /**
         * Sets the XYZ to camera matrix that replaces the file's color matrices; see
         * {@link RawDecoderOptions#colorMatrix()}. The array is copied.
         *
         * @param colorMatrix the nine or twelve values, row by row
         * @return this builder
         */
        public Builder colorMatrix(double[] colorMatrix) {
            this.colorMatrix = Objects.requireNonNull(colorMatrix, "colorMatrix").clone();
            return this;
        }

        /**
         * Renders with the file's own color matrices, or the generic fallback; see
         * {@link RawDecoderOptions#colorMatrix()}.
         *
         * @return this builder
         */
        public Builder fileColorMatrix() {
            this.colorMatrix = null;
            return this;
        }

        /**
         * Sets whether a DNG file's enhanced image is rendered instead of its raw image; see
         * {@link RawDecoderOptions#enhancedImage()}.
         *
         * @param enhancedImage the value
         * @return this builder
         */
        public Builder enhancedImage(boolean enhancedImage) {
            this.enhancedImage = enhancedImage;
            return this;
        }

        /**
         * Sets whether the default user crop is applied; see {@link RawDecoderOptions#userCrop()}.
         *
         * @param userCrop the value
         * @return this builder
         */
        public Builder userCrop(boolean userCrop) {
            this.userCrop = userCrop;
            return this;
        }

        /**
         * Sets whether an automatic black point is subtracted; see {@link RawDecoderOptions#autoBlack()}.
         *
         * @param autoBlack the value
         * @return this builder
         */
        public Builder autoBlack(boolean autoBlack) {
            this.autoBlack = autoBlack;
            return this;
        }

        /**
         * Sets the camera profile the file is rendered with; see {@link RawDecoderOptions#cameraProfile()}.
         *
         * @param name the profile's name, as {@link RawInfo#cameraProfiles()} lists it
         * @return this builder
         */
        public Builder cameraProfile(String name) {
            this.cameraProfile = Objects.requireNonNull(name, "name");
            return this;
        }

        /**
         * Renders with the as-shot camera profile, or else the primary one; see
         * {@link RawDecoderOptions#cameraProfile()}.
         *
         * @return this builder
         */
        public Builder asShotProfile() {
            this.cameraProfile = null;
            return this;
        }

        /**
         * Sets whether a camera raw file's own lens corrections are applied; see
         * {@link RawDecoderOptions#lensCorrections()}.
         *
         * @param lensCorrections the value
         * @return this builder
         */
        public Builder lensCorrections(boolean lensCorrections) {
            this.lensCorrections = lensCorrections;
            return this;
        }

        /**
         * Sets the frame of a pixel-shift file that is decoded alone; see {@link RawDecoderOptions#frame()}.
         *
         * @param index the frame, from 0
         * @return this builder
         */
        public Builder frame(int index) {
            this.frame = index;
            this.allFrames = false;
            return this;
        }

        /**
         * Decodes every frame of a pixel-shift file, merged; see {@link RawDecoderOptions#frame()}.
         *
         * @return this builder
         */
        public Builder allFrames() {
            this.frame = 0;
            this.allFrames = true;
            return this;
        }

        /**
         * Returns options holding the settings of this builder.
         *
         * @return the options
         * @throws IllegalArgumentException if the minimum width or height is negative, the exposure is
         * outside -10 to 10 stops, the frame is negative, or the color matrix does not hold nine or twelve
         * finite values or lacks full column rank
         */
        public RawDecoderOptions build() {
            Checks.nonNegative("minimum width", minWidth);
            if (!allFrames && frame < 0) {
                throw new IllegalArgumentException("frame " + frame + " is negative");
            }
            Checks.nonNegative("minimum height", minHeight);
            Checks.inRange("exposure", exposure, -10, 10);
            if (colorMatrix != null) {
                checkColorMatrix(colorMatrix);
            }
            return new RawDecoderOptions(this);
        }

        /**
         * Checks that a color matrix holds nine or twelve finite values and has full column rank.
         *
         * @param m the matrix, row by row
         * @throws IllegalArgumentException if it does not
         */
        private static void checkColorMatrix(double[] m) {
            if (m.length != 9 && m.length != 12) {
                throw new IllegalArgumentException("a color matrix has nine or twelve values, not " + m.length);
            }
            for (var value : m) {
                if (!Double.isFinite(value)) {
                    throw new IllegalArgumentException("non-finite color matrix value " + value);
                }
            }
            // the Gram matrix of the columns, whose determinant is 0 exactly when the rank is below 3
            var rows = m.length / 3;
            var g = new double[9];
            for (var i = 0; i < 3; i++) {
                for (var j = 0; j < 3; j++) {
                    for (var row = 0; row < rows; row++) {
                        g[i * 3 + j] += m[row * 3 + i] * m[row * 3 + j];
                    }
                }
            }
            var determinant = g[0] * (g[4] * g[8] - g[5] * g[7]) - g[1] * (g[3] * g[8] - g[5] * g[6])
                    + g[2] * (g[3] * g[7] - g[4] * g[6]);
            if (Math.abs(determinant) < 1e-18) {
                throw new IllegalArgumentException("singular color matrix");
            }
        }
    }
}
