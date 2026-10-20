package com.github.auties00.smalt.image;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.color.ColorProfile;
import com.github.auties00.smalt.internal.check.Checks;

import java.util.Objects;

/**
 * Holds the settings of an image decoder, such as {@link Images#decode(com.github.auties00.smalt.io.MediaSource,
 * ImageDecoderOptions) Images.decode}. Instances are immutable and thread-safe; create them with
 * {@link #builder()} or a preset such as {@link #defaults()}.
 */
public final class ImageDecoderOptions {
    private static final ImageDecoderOptions DEFAULTS = new ImageDecoderOptions(MediaLimits.DEFAULT, 0, 0, false, false,
            false);

    private final MediaLimits limits;
    private final int minWidth;
    private final int minHeight;
    private final boolean highBitDepth;
    private final boolean convertToSrgb;
    private final boolean keepChannels;

    private ImageDecoderOptions(MediaLimits limits, int minWidth, int minHeight, boolean highBitDepth,
                                boolean convertToSrgb, boolean keepChannels) {
        this.limits = limits;
        this.minWidth = minWidth;
        this.minHeight = minHeight;
        this.highBitDepth = highBitDepth;
        this.convertToSrgb = convertToSrgb;
        this.keepChannels = keepChannels;
    }

    /**
     * Returns full-size decoding to 8-bit pictures in the file's color space, under
     * {@link MediaLimits#DEFAULT}.
     *
     * @return the default options
     */
    public static ImageDecoderOptions defaults() {
        return DEFAULTS;
    }

    /**
     * Returns the default options with a minimum size hint, which lets a decoder that can decode at
     * a reduced size skip the work a later downscale would discard.
     *
     * @param minWidth the smallest acceptable decoded width, at least 0
     * @param minHeight the smallest acceptable decoded height, at least 0
     * @return the options
     * @throws IllegalArgumentException if a size is negative
     * @see #minWidth()
     */
    public static ImageDecoderOptions ofMinimumSize(int minWidth, int minHeight) {
        return builder().minSize(minWidth, minHeight).build();
    }

    /**
     * Returns a builder initialized with {@link #defaults()}.
     *
     * @return the builder
     */
    public static Builder builder() {
        return new Builder(DEFAULTS);
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
     * {@return the resource bounds checked before every allocation; {@link MediaLimits#DEFAULT} by default}
     */
    public MediaLimits limits() {
        return limits;
    }

    /**
     * {@return the smallest acceptable decoded width in pixels, at least 0; 0 (no hint) by default}
     *
     * <p>A decoder that can decode at a reduced size (JPEG DCT scaling, for example) returns the
     * smallest reduction that is still at least {@code minWidth()} wide and {@link #minHeight()}
     * tall, or the full image when no reduction fits. Decoders without reduced-size decoding ignore
     * the hint, and a hint of 0 in both dimensions asks for the full size. The hint is compared
     * against the stored (unrotated) dimensions.
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
     * {@return whether sources of more than 8 bits per sample keep their depth; {@code false} by default}
     *
     * <p>When {@code false}, such sources are reduced to 8 bits. When {@code true}, these decoders return
     * them at their own depth instead:
     * <ul>
     *   <li>PNG (16-bit images), JPEG 2000, JPEG XL, TIFF and camera raw files decode to
     *       {@link Rgba16Picture}s;</li>
     *   <li>12-bit JPEG and lossless JPEG (SOF3) of 9 to 16 bits decode gray and YCbCr images to
     *       {@link Yuv16Picture}s and RGB images to {@code Rgba16Picture}s, and still reduce CMYK and
     *       YCCK images;</li>
     *   <li>HEIC and AVIF decode to {@code Yuv16Picture}s, or to {@code Rgba16Picture}s for overlays and
     *       premultiplied images, and their animation readers return {@code Rgba16Picture} frames.</li>
     * </ul>
     * <p>The other formats always decode to 8 bits. Sources of 8 bits or fewer are unaffected.
     */
    public boolean highBitDepth() {
        return highBitDepth;
    }

    /**
     * {@return whether decoders convert pixels to sRGB; {@code false} by default}
     *
     * <p>When {@code false}, pixels are returned in the color space the file declares, which
     * {@link DecodedImage#colorProfile()} reports. When {@code true}, a decoder applies that
     * profile with the perceptual intent and returns sRGB pixels in packed RGBA, with
     * {@link DecodedImage#iccProfile()} empty and {@link DecodedImage#colorProfile()} holding
     * {@link ColorProfile#srgb()}; this costs a color transform per image whose profile is not
     * sRGB. Images without a profile, or whose profile is sRGB, are returned exactly as without the
     * option, as are images whose profile cannot be applied (a damaged profile, or a color space
     * that does not match the pixels). A JPEG stored as CMYK is converted with its embedded CMYK
     * profile; without one it is converted as without the option.
     */
    public boolean convertToSrgb() {
        return convertToSrgb;
    }

    /**
     * {@return whether decoders return the samples a packed RGBA or planar Y'CbCr picture cannot hold in a
     * {@link ChannelPicture}; {@code false} by default}
     *
     * <p>When {@code false}, such sources are converted to RGBA as each decoder documents. When {@code true},
     * these decoders return them in their stored color model instead:
     * <ul>
     *   <li>JPEG stored as CMYK or YCCK decodes to a CMYK {@link Channel8Picture}, and lossless CMYK of more
     *       than 8 bits to a {@link Channel16Picture} when {@link #highBitDepth()} is also set;</li>
     *   <li>TIFF decodes CMYK and multi-ink separations, floating-point samples, and pages with extra samples
     *       other than one alpha channel to a channel picture, of 8 or 16 bits as {@link #highBitDepth()}
     *       selects for integer samples, and a {@link ChannelFloatPicture} for floating-point ones;</li>
     *   <li>JPEG XL decodes CMYK images, floating-point images, and images with extra channels other than
     *       one alpha channel to a channel picture likewise.</li>
     * </ul>
     * <p>The decoded image of a channel picture keeps its embedded profile in
     * {@link DecodedImage#colorProfile()}, which describes the color channels. Other sources are unaffected,
     * and {@link #convertToSrgb()}, when also set, takes precedence.
     */
    public boolean keepChannels() {
        return keepChannels;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ImageDecoderOptions that
                && limits.equals(that.limits)
                && minWidth == that.minWidth
                && minHeight == that.minHeight
                && highBitDepth == that.highBitDepth
                && convertToSrgb == that.convertToSrgb
                && keepChannels == that.keepChannels;
    }

    @Override
    public int hashCode() {
        return Objects.hash(limits, minWidth, minHeight, highBitDepth, convertToSrgb, keepChannels);
    }

    @Override
    public String toString() {
        return "ImageDecoderOptions[limits=" + limits + ", minWidth=" + minWidth + ", minHeight=" + minHeight
                + ", highBitDepth=" + highBitDepth + ", convertToSrgb=" + convertToSrgb
                + ", keepChannels=" + keepChannels + "]";
    }

    /**
     * Builds {@link ImageDecoderOptions}.
     *
     * <p>Setters store their value; {@link #build()} checks the ranges. Instances are not
     * thread-safe.
     */
    public static final class Builder {
        private MediaLimits limits;
        private int minWidth;
        private int minHeight;
        private boolean highBitDepth;
        private boolean convertToSrgb;
        private boolean keepChannels;

        private Builder(ImageDecoderOptions options) {
            this.limits = options.limits;
            this.minWidth = options.minWidth;
            this.minHeight = options.minHeight;
            this.highBitDepth = options.highBitDepth;
            this.convertToSrgb = options.convertToSrgb;
            this.keepChannels = options.keepChannels;
        }

        /**
         * Sets the resource bounds; see {@link ImageDecoderOptions#limits()}.
         *
         * @param limits the value
         * @return this builder
         */
        public Builder limits(MediaLimits limits) {
            this.limits = Objects.requireNonNull(limits, "limits");
            return this;
        }

        /**
         * Sets the minimum size hint; see {@link ImageDecoderOptions#minWidth()}.
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
         * Clears the minimum size hint, so decoders decode at full size; see
         * {@link ImageDecoderOptions#minWidth()}.
         *
         * @return this builder
         */
        public Builder fullSize() {
            this.minWidth = 0;
            this.minHeight = 0;
            return this;
        }

        /**
         * Sets whether high-bit-depth sources keep their depth; see
         * {@link ImageDecoderOptions#highBitDepth()}.
         *
         * @param highBitDepth the value
         * @return this builder
         */
        public Builder highBitDepth(boolean highBitDepth) {
            this.highBitDepth = highBitDepth;
            return this;
        }

        /**
         * Sets whether pixels are converted to sRGB; see {@link ImageDecoderOptions#convertToSrgb()}.
         *
         * @param convertToSrgb the value
         * @return this builder
         */
        public Builder convertToSrgb(boolean convertToSrgb) {
            this.convertToSrgb = convertToSrgb;
            return this;
        }

        /**
         * Sets whether samples RGBA and Y'CbCr pictures cannot hold are kept in channel pictures; see
         * {@link ImageDecoderOptions#keepChannels()}.
         *
         * @param keepChannels the value
         * @return this builder
         */
        public Builder keepChannels(boolean keepChannels) {
            this.keepChannels = keepChannels;
            return this;
        }

        /**
         * Returns options holding the settings of this builder.
         *
         * @return the options
         * @throws IllegalArgumentException if the minimum width or height is negative
         */
        public ImageDecoderOptions build() {
            Checks.nonNegative("minimum width", minWidth);
            Checks.nonNegative("minimum height", minHeight);
            return new ImageDecoderOptions(limits, minWidth, minHeight, highBitDepth, convertToSrgb, keepChannels);
        }
    }
}
