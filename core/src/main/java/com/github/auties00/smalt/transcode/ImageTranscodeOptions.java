package com.github.auties00.smalt.transcode;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ImageEncoderOptions;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.ops.Region;
import com.github.auties00.smalt.image.ops.ResizeFilter;
import com.github.auties00.smalt.internal.check.Checks;
import com.github.auties00.smalt.internal.resize.SizePlan;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

/**
 * Holds the settings of one image transcode: the output format and encoder settings, the operations
 * applied to the picture, what happens to animations, and an optional size budget. Instances are
 * immutable and thread-safe; create them with {@link #builder()} or a preset such as
 * {@link #defaults()}.
 *
 * <p>The picture is decoded (at a reduced size when the format supports it and the output is
 * smaller), turned upright following its EXIF or container orientation, cropped
 * ({@link #crop()}, in displayed coordinates), scaled ({@link #width()}, {@link #height()},
 * {@link #scaleMode()}, {@link #upscale()}, {@link #maxLongEdge()}, {@link #maxShortEdge()},
 * resampled with {@link #filter()}), flattened on {@link #background()} when the output format has
 * no transparency (or {@link #flatten()} asks for it), and encoded through
 * {@link com.github.auties00.smalt.image.Images} with {@link #encoderOptions()}. The output records the
 * color profile of the input or holds its pixels converted to sRGB ({@link #color()}), and keeps, strips
 * or keeps without the location the EXIF block and XMP packet of the input ({@link #metadata()}).
 */
public final class ImageTranscodeOptions {
    /**
     * The options of {@link #defaults()}.
     */
    private static final ImageTranscodeOptions DEFAULTS = new Builder().build();

    /**
     * The output format, or {@code null} to keep the input format.
     */
    private final ImageFormat format;

    private final ImageEncoderOptions encoderOptions;

    private final ResizeSettings resize;

    private final int background;
    private final boolean flatten;
    private final AnimationMode animation;

    /**
     * The output size budget in bytes, or {@code 0} for none.
     */
    private final long maxBytes;

    private final boolean passthrough;
    private final MediaLimits limits;
    private final MetadataMode metadata;
    private final ColorMode color;

    private ImageTranscodeOptions(Builder builder, ResizeSettings resize, long maxBytes) {
        this.format = builder.format;
        this.encoderOptions = builder.encoderOptions;
        this.resize = resize;
        this.background = builder.background;
        this.flatten = builder.flatten;
        this.animation = builder.animation;
        this.maxBytes = maxBytes;
        this.passthrough = builder.passthrough;
        this.limits = builder.limits;
        this.metadata = builder.metadata;
        this.color = builder.color;
    }

    /**
     * Returns the default options: the input format when it is encodable (PNG otherwise), at the
     * input size, animations kept.
     *
     * @return the options
     */
    public static ImageTranscodeOptions defaults() {
        return DEFAULTS;
    }

    /**
     * Returns options converting to a format at its default settings.
     *
     * @param format the output format
     * @return the options
     */
    public static ImageTranscodeOptions of(ImageFormat format) {
        return DEFAULTS.toBuilder().format(format).build();
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
     * {@return the output format, or empty to keep the input format when it is encodable and write PNG
     * otherwise; empty by default}
     */
    public Optional<ImageFormat> format() {
        return Optional.ofNullable(format);
    }

    /**
     * {@return the encoder settings; {@link ImageEncoderOptions#defaults()} by default}
     *
     * <p>The transcode replaces their color profile, EXIF block, XMP packet and orientation with those
     * {@link #color()} and {@link #metadata()} derive from the input.
     */
    public ImageEncoderOptions encoderOptions() {
        return encoderOptions;
    }

    /**
     * {@return the width of the target box, in pixels, positive, or empty when free; empty by default}
     */
    public OptionalInt width() {
        return resize.width() == 0 ? OptionalInt.empty() : OptionalInt.of(resize.width());
    }

    /**
     * {@return the height of the target box, in pixels, positive, or empty when free; empty by default}
     */
    public OptionalInt height() {
        return resize.height() == 0 ? OptionalInt.empty() : OptionalInt.of(resize.height());
    }

    /**
     * {@return how the picture is fitted to the target box; {@link ScaleMode#FIT} by default}
     */
    public ScaleMode scaleMode() {
        return resize.scaleMode();
    }

    /**
     * {@return whether {@link ScaleMode#FIT} may enlarge a picture smaller than the box; {@code false}
     * by default}
     */
    public boolean upscale() {
        return resize.upscale();
    }

    /**
     * {@return the largest long edge of the output, in pixels, positive, or empty for no limit; empty
     * by default}
     */
    public OptionalInt maxLongEdge() {
        return resize.maxLongEdge() == 0 ? OptionalInt.empty() : OptionalInt.of(resize.maxLongEdge());
    }

    /**
     * {@return the largest short edge of the output, in pixels, positive, or empty for no limit; empty
     * by default}
     */
    public OptionalInt maxShortEdge() {
        return resize.maxShortEdge() == 0 ? OptionalInt.empty() : OptionalInt.of(resize.maxShortEdge());
    }

    /**
     * {@return the resampling filter; {@link ResizeFilter#LANCZOS3} by default}
     */
    public ResizeFilter filter() {
        return resize.filter();
    }

    /**
     * {@return the region of the displayed picture that is kept, or empty for the whole picture; empty
     * by default}
     *
     * <p>Coordinates refer to the picture as displayed, after its orientation is applied, with the
     * origin at the top-left corner. A region that is not inside the picture fails the transcode with
     * an {@link IllegalArgumentException} once the picture size is known.
     */
    public Optional<Region> crop() {
        return Optional.ofNullable(resize.crop());
    }

    /**
     * {@return the color transparent pixels are flattened on, as {@code 0xRRGGBB}; white
     * ({@code 0xFFFFFF}) by default}
     */
    public int background() {
        return background;
    }

    /**
     * {@return whether transparency is flattened even when the output format could keep it, so that the
     * output is always opaque; {@code false} by default}
     */
    public boolean flatten() {
        return flatten;
    }

    /**
     * {@return whether an animated input becomes an animation or its first frame;
     * {@link AnimationMode#KEEP} by default}
     */
    public AnimationMode animation() {
        return animation;
    }

    /**
     * {@return the size budget the output must fit, in bytes, positive, or empty for none; empty by
     * default}
     *
     * <p>A lossy encoding steps its quality down, then the picture is scaled down by a quarter at a
     * time, until the output fits. An animated output steps the same way with every frame kept;
     * when even its smallest size does not fit, the steps are repeated keeping every second frame,
     * then every fourth and so on, each kept frame shown for the time of the frames it replaces, so
     * the animation keeps its duration. A budget no encoding fits fails the transcode with a
     * {@link com.github.auties00.smalt.MediaException.LimitExceeded}.
     */
    public OptionalLong maxBytes() {
        return maxBytes == 0 ? OptionalLong.empty() : OptionalLong.of(maxBytes);
    }

    /**
     * {@return whether an input that needs no change is copied byte for byte; {@code true} by default}
     *
     * <p>An input needs no change when it already has the output format, needs no operation, fits the
     * budget, the encoder settings are the defaults, {@link #metadata()} is {@link MetadataMode#KEEP} and
     * {@link #color()} is {@link ColorMode#KEEP_PROFILE}. A copied input keeps its orientation, its EXIF
     * thumbnail and every byte of its metadata.
     */
    public boolean passthrough() {
        return passthrough;
    }

    /**
     * {@return the resource bounds the decoder enforces; {@link MediaLimits#defaults()} by default}
     */
    public MediaLimits limits() {
        return limits;
    }

    /**
     * {@return what happens to the EXIF block and the XMP packet of the input; {@link MetadataMode#KEEP} by
     * default}
     */
    public MetadataMode metadata() {
        return metadata;
    }

    /**
     * {@return whether the output records the color profile of the input or holds its pixels converted to
     * sRGB; {@link ColorMode#KEEP_PROFILE} by default}
     */
    public ColorMode color() {
        return color;
    }

    /**
     * Plans the scaling of a displayed picture.
     *
     * @param displayWidth the displayed width, after any crop
     * @param displayHeight the displayed height, after any crop
     * @return the plan
     */
    SizePlan sizePlan(int displayWidth, int displayHeight) {
        return resize.sizePlan(displayWidth, displayHeight, false);
    }

    /**
     * Returns the state compared by {@link #equals(Object)}.
     *
     * @return the field values
     */
    private Object[] state() {
        return new Object[]{format, encoderOptions, resize, background, flatten, animation, maxBytes, passthrough, limits,
                metadata, color};
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ImageTranscodeOptions that && Arrays.equals(state(), that.state());
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(state());
    }

    @Override
    public String toString() {
        return "ImageTranscodeOptions[format=" + format() + ", encoderOptions=" + encoderOptions + ", " + resize.describe()
                + ", filter=" + resize.filter() + ", crop=" + crop() + ", background=0x" + Integer.toHexString(background)
                + ", flatten=" + flatten + ", animation=" + animation + ", maxBytes=" + maxBytes() + ", passthrough="
                + passthrough + ", limits=" + limits + ", metadata=" + metadata + ", color=" + color + "]";
    }

    /**
     * Assembles {@link ImageTranscodeOptions} setting by setting.
     *
     * <p>Setters reject {@code null} unless documented otherwise and store numbers unchecked;
     * {@link #build()} checks the ranges. Instances are not thread-safe.
     */
    public static final class Builder {
        private ImageFormat format;
        private ImageEncoderOptions encoderOptions = ImageEncoderOptions.defaults();
        private int width;
        private int height;
        private ScaleMode scaleMode = ScaleMode.FIT;
        private boolean upscale;
        private int maxLongEdge;

        /**
         * Whether {@link #maxLongEdge} limits the output.
         */
        private boolean longEdgeLimited;

        private int maxShortEdge;

        /**
         * Whether {@link #maxShortEdge} limits the output.
         */
        private boolean shortEdgeLimited;

        private ResizeFilter filter = ResizeFilter.LANCZOS3;
        private Region crop;
        private int background = 0xFFFFFF;
        private boolean flatten;
        private AnimationMode animation = AnimationMode.KEEP;
        private long maxBytes;

        /**
         * Whether {@link #maxBytes} is a budget.
         */
        private boolean budgeted;

        private boolean passthrough = true;
        private MediaLimits limits = MediaLimits.DEFAULT;
        private MetadataMode metadata = MetadataMode.KEEP;
        private ColorMode color = ColorMode.KEEP_PROFILE;

        private Builder() {
        }

        private Builder(ImageTranscodeOptions options) {
            this.format = options.format;
            this.encoderOptions = options.encoderOptions;
            var resize = options.resize;
            this.width = resize.width();
            this.height = resize.height();
            this.scaleMode = resize.scaleMode();
            this.upscale = resize.upscale();
            this.maxLongEdge = resize.maxLongEdge();
            this.longEdgeLimited = resize.maxLongEdge() != 0;
            this.maxShortEdge = resize.maxShortEdge();
            this.shortEdgeLimited = resize.maxShortEdge() != 0;
            this.filter = resize.filter();
            this.crop = resize.crop();
            this.background = options.background;
            this.flatten = options.flatten;
            this.animation = options.animation;
            this.maxBytes = options.maxBytes;
            this.budgeted = options.maxBytes != 0;
            this.passthrough = options.passthrough;
            this.limits = options.limits;
            this.metadata = options.metadata;
            this.color = options.color;
        }

        /**
         * Sets the output format; see {@link ImageTranscodeOptions#format()}.
         *
         * @param format the value
         * @return this builder
         */
        public Builder format(ImageFormat format) {
            this.format = Objects.requireNonNull(format, "format");
            return this;
        }

        /**
         * Keeps the format of the input when it is encodable, and writes PNG otherwise; see
         * {@link ImageTranscodeOptions#format()}.
         *
         * @return this builder
         */
        public Builder sourceFormat() {
            this.format = null;
            return this;
        }

        /**
         * Sets the encoder settings; see {@link ImageTranscodeOptions#encoderOptions()}.
         *
         * @param encoderOptions the value
         * @return this builder
         */
        public Builder encoderOptions(ImageEncoderOptions encoderOptions) {
            this.encoderOptions = Objects.requireNonNull(encoderOptions, "encoderOptions");
            return this;
        }

        /**
         * Sets the target box and how the picture is fitted to it; see
         * {@link ImageTranscodeOptions#width()}, {@link ImageTranscodeOptions#height()} and
         * {@link ImageTranscodeOptions#scaleMode()}.
         *
         * @param width the box width in pixels, or 0 to leave it free
         * @param height the box height in pixels, or 0 to leave it free
         * @param mode the scale mode
         * @return this builder
         */
        public Builder size(int width, int height, ScaleMode mode) {
            this.scaleMode = Objects.requireNonNull(mode, "mode");
            this.width = width;
            this.height = height;
            return this;
        }

        /**
         * Keeps the picture within a box, scaling it down with its aspect ratio kept, as
         * {@link #size(int, int, ScaleMode) size(width, height, ScaleMode.FIT)} does; see
         * {@link ImageTranscodeOptions#width()} and {@link ImageTranscodeOptions#height()}.
         *
         * @param width the box width in pixels, or 0 to leave it free
         * @param height the box height in pixels, or 0 to leave it free
         * @return this builder
         */
        public Builder maxSize(int width, int height) {
            return size(width, height, ScaleMode.FIT);
        }

        /**
         * Sets whether {@link ScaleMode#FIT} may enlarge the picture; see
         * {@link ImageTranscodeOptions#upscale()}.
         *
         * @param upscale the value
         * @return this builder
         */
        public Builder upscale(boolean upscale) {
            this.upscale = upscale;
            return this;
        }

        /**
         * Limits the long edge; see {@link ImageTranscodeOptions#maxLongEdge()}.
         *
         * @param edge the value
         * @return this builder
         */
        public Builder maxLongEdge(int edge) {
            this.maxLongEdge = edge;
            this.longEdgeLimited = true;
            return this;
        }

        /**
         * Removes the limit on the long edge of the picture; see {@link ImageTranscodeOptions#maxLongEdge()}.
         *
         * @return this builder
         */
        public Builder noMaxLongEdge() {
            this.longEdgeLimited = false;
            return this;
        }

        /**
         * Limits the short edge; see {@link ImageTranscodeOptions#maxShortEdge()}.
         *
         * @param edge the value
         * @return this builder
         */
        public Builder maxShortEdge(int edge) {
            this.maxShortEdge = edge;
            this.shortEdgeLimited = true;
            return this;
        }

        /**
         * Removes the limit on the short edge of the picture; see {@link ImageTranscodeOptions#maxShortEdge()}.
         *
         * @return this builder
         */
        public Builder noMaxShortEdge() {
            this.shortEdgeLimited = false;
            return this;
        }

        /**
         * Sets the resampling filter; see {@link ImageTranscodeOptions#filter()}.
         *
         * @param filter the value
         * @return this builder
         */
        public Builder filter(ResizeFilter filter) {
            this.filter = Objects.requireNonNull(filter, "filter");
            return this;
        }

        /**
         * Sets the region of the displayed picture that is kept; see {@link ImageTranscodeOptions#crop()}.
         *
         * @param crop the value
         * @return this builder
         */
        public Builder crop(Region crop) {
            this.crop = Objects.requireNonNull(crop, "crop");
            return this;
        }

        /**
         * Keeps the whole picture; see {@link ImageTranscodeOptions#crop()}.
         *
         * @return this builder
         */
        public Builder noCrop() {
            this.crop = null;
            return this;
        }

        /**
         * Sets the flattening background; see {@link ImageTranscodeOptions#background()}.
         *
         * @param rgb the value as {@code 0xRRGGBB}; the top byte is ignored
         * @return this builder
         */
        public Builder background(int rgb) {
            this.background = rgb & 0xFFFFFF;
            return this;
        }

        /**
         * Sets whether transparency is always flattened; see {@link ImageTranscodeOptions#flatten()}.
         *
         * @param flatten the value
         * @return this builder
         */
        public Builder flatten(boolean flatten) {
            this.flatten = flatten;
            return this;
        }

        /**
         * Sets the animation handling; see {@link ImageTranscodeOptions#animation()}.
         *
         * @param animation the value
         * @return this builder
         */
        public Builder animation(AnimationMode animation) {
            this.animation = Objects.requireNonNull(animation, "animation");
            return this;
        }

        /**
         * Sets the size budget; see {@link ImageTranscodeOptions#maxBytes()}.
         *
         * @param maxBytes the value
         * @return this builder
         */
        public Builder maxBytes(long maxBytes) {
            this.maxBytes = maxBytes;
            this.budgeted = true;
            return this;
        }

        /**
         * Removes the output size budget; see {@link ImageTranscodeOptions#maxBytes()}.
         *
         * @return this builder
         */
        public Builder noMaxBytes() {
            this.budgeted = false;
            return this;
        }

        /**
         * Sets whether an input that needs no change is copied unchanged; see
         * {@link ImageTranscodeOptions#passthrough()}.
         *
         * @param passthrough the value
         * @return this builder
         */
        public Builder passthrough(boolean passthrough) {
            this.passthrough = passthrough;
            return this;
        }

        /**
         * Sets the decoder limits; see {@link ImageTranscodeOptions#limits()}.
         *
         * @param limits the value
         * @return this builder
         */
        public Builder limits(MediaLimits limits) {
            this.limits = Objects.requireNonNull(limits, "limits");
            return this;
        }

        /**
         * Sets what happens to the EXIF block and the XMP packet; see {@link ImageTranscodeOptions#metadata()}.
         *
         * @param metadata the value
         * @return this builder
         */
        public Builder metadata(MetadataMode metadata) {
            this.metadata = Objects.requireNonNull(metadata, "metadata");
            return this;
        }

        /**
         * Sets how the color space of the input is treated; see {@link ImageTranscodeOptions#color()}.
         *
         * @param color the value
         * @return this builder
         */
        public Builder color(ColorMode color) {
            this.color = Objects.requireNonNull(color, "color");
            return this;
        }

        /**
         * Builds the options.
         *
         * @return the options
         * @throws IllegalArgumentException if a box dimension is negative, or an edge limit or the size
         * budget is not positive
         */
        public ImageTranscodeOptions build() {
            var resize = ResizeSettings.checked(width, height, scaleMode, upscale, longEdgeLimited, maxLongEdge,
                    shortEdgeLimited, maxShortEdge, filter, crop);
            return new ImageTranscodeOptions(this, resize, budgeted ? Checks.positive("byte budget", maxBytes) : 0);
        }
    }
}
