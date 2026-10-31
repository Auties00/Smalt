package com.github.auties00.smalt.image.heic;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.MatrixCoefficients;
import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.image.color.ColorProfile;
import com.github.auties00.smalt.internal.check.Checks;
import com.github.auties00.smalt.internal.color.YcgcoR;
import com.github.auties00.smalt.video.hevc.encoder.HevcEncoderSpeed;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Holds the settings of a {@link HeicEncoder}. Instances are immutable and thread-safe; create them with
 * {@link #builder()} or a preset such as {@link #defaults()}.
 */
public final class HeicEncoderOptions {
    /**
     * The default quality.
     */
    private static final int DEFAULT_QUALITY = 50;

    /**
     * The largest side of the images of a grid: the largest even side whose square fits the picture size of
     * HEVC level 6.2 (35651584 luma samples), the highest level.
     */
    private static final int MAX_GRID_TILE = 5970;

    /**
     * The options of {@link #defaults()}.
     */
    private static final HeicEncoderOptions DEFAULTS = new Builder().build();

    private final int quality;

    private final int alphaQuality;

    private final HevcEncoderSpeed speed;

    private final OptionalInt bitDepth;

    private final ColorInfo color;

    /**
     * The ICC profile, or {@code null}.
     */
    private final ColorProfile colorProfile;

    private final Orientation orientation;

    /**
     * The Exif metadata, or {@code null}.
     */
    private final byte[] exif;

    /**
     * The XMP metadata, or {@code null}.
     */
    private final byte[] xmp;

    private final boolean premultipliedAlpha;

    private final OptionalInt gridTileSize;

    private final int threads;

    private final MediaLimits limits;

    private final int loopCount;

    private final boolean interFrames;

    private HeicEncoderOptions(Builder builder) {
        this.quality = builder.quality;
        this.alphaQuality = builder.alphaQuality;
        this.speed = builder.speed;
        this.bitDepth = builder.bitDepth;
        this.color = builder.color;
        this.colorProfile = builder.colorProfile;
        this.orientation = builder.orientation;
        this.exif = builder.exif;
        this.xmp = builder.xmp;
        this.premultipliedAlpha = builder.premultipliedAlpha;
        this.gridTileSize = builder.gridTileSize;
        this.threads = builder.threads;
        this.limits = builder.limits;
        this.loopCount = builder.loopCount;
        this.interFrames = builder.interFrames;
    }

    /**
     * Returns the general-purpose settings: quality 50, alpha at quality 100,
     * {@link HevcEncoderSpeed#BALANCED}, the picture's depth (8 or 10 bits), full-range BT.601 conversion of
     * RGB pictures with BT.709 primaries and the sRGB transfer ({@link ColorInfo#JPEG}), no metadata, an image
     * grid only for very large pictures, and animations that loop forever with predicted frames.
     *
     * @return the options
     */
    public static HeicEncoderOptions defaults() {
        return DEFAULTS;
    }

    /**
     * Returns the {@link #defaults()} with another quality.
     *
     * @param quality the quality, from 0 to 100; see {@link #quality()}
     * @return the options
     * @throws IllegalArgumentException if {@code quality} is not in [0, 100]
     */
    public static HeicEncoderOptions ofQuality(int quality) {
        return new Builder().quality(quality).build();
    }

    /**
     * Returns the settings for archiving: quality 90 at 10 bits per sample with {@link HevcEncoderSpeed#SLOW},
     * so gradients keep their precision.
     *
     * @return the options
     */
    public static HeicEncoderOptions forArchive() {
        return new Builder().quality(90).bitDepth(10).speed(HevcEncoderSpeed.SLOW).build();
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
     * {@return the quality, from 0 (smallest) to 100 (best); 50 by default}
     *
     * <p>The quality maps to the HEVC quantizer {@code (100 - quality) * 51 / 100}, rounded to nearest, every
     * image coded at that constant quantizer. Quality 100 is the finest quantization, not lossless.
     */
    public int quality() {
        return quality;
    }

    /**
     * {@return the quality of the alpha plane of a transparent picture, on the scale of {@link #quality()};
     * 100 by default}
     */
    public int alphaQuality() {
        return alphaQuality;
    }

    /**
     * {@return the trade between compression and encoding time; {@link HevcEncoderSpeed#BALANCED} by default}
     */
    public HevcEncoderSpeed speed() {
        return speed;
    }

    /**
     * {@return the bit depth of the coded images, 8 or 10, or empty for automatic; empty by default}
     *
     * <p>Automatic is 8 for 8-bit pictures and 10 for deeper ones. 8-bit images are coded in the Main Still
     * Picture profile and announced by the {@code heic} brand, 10-bit images in the Main 10 Still Picture
     * profile and announced by the {@code heix} brand; image sequences use the Main and Main 10 profiles.
     */
    public OptionalInt bitDepth() {
        return bitDepth;
    }

    /**
     * {@return the color description RGB pictures are converted with and the file signals for them;
     * {@link ColorInfo#JPEG} by default}
     *
     * <p>Planar pictures keep their own description. The identity matrix and the YCgCo-R matrices
     * ({@link MatrixCoefficients#YCGCO_RE}, {@link MatrixCoefficients#YCGCO_RO}), which need 4:4:4 chroma,
     * cannot be used.
     */
    public ColorInfo color() {
        return color;
    }

    /**
     * {@return the ICC profile the file embeds, or empty for none; empty by default}
     */
    public Optional<ColorProfile> colorProfile() {
        return Optional.ofNullable(colorProfile);
    }

    /**
     * {@return the display orientation the file signals with its rotation and mirroring properties;
     * {@link Orientation#NORMAL} by default}
     */
    public Orientation orientation() {
        return orientation;
    }

    /**
     * {@return a copy of the Exif metadata the file embeds, or empty for none; empty by default}
     */
    public Optional<byte[]> exif() {
        return Optional.ofNullable(exif).map(byte[]::clone);
    }

    /**
     * {@return a copy of the XMP metadata the file embeds, or empty for none; empty by default}
     */
    public Optional<byte[]> xmp() {
        return Optional.ofNullable(xmp).map(byte[]::clone);
    }

    /**
     * {@return whether the color of a transparent picture is stored premultiplied by its alpha; {@code false}
     * by default}
     */
    public boolean premultipliedAlpha() {
        return premultipliedAlpha;
    }

    /**
     * {@return the side of the images an image grid splits the picture into, an even number from 64 to 5970,
     * or empty for automatic; empty by default}
     *
     * <p>Automatic splits only the pictures that exceed the picture size of HEVC level 5.1 (8912896 samples, or
     * a side longer than 8444), the largest that common hardware decoders accept, into images of 512 by 512.
     */
    public OptionalInt gridTileSize() {
        return gridTileSize;
    }

    /**
     * {@return the thread count, at least 0; 0 by default}
     *
     * <p>0 encodes on the module's shared pool, 1 on the calling thread only, and a larger value caps the
     * threads of the shared pool one picture uses. The file does not depend on it.
     */
    public int threads() {
        return threads;
    }

    /**
     * {@return the allocation limits pictures are checked against; {@link MediaLimits#DEFAULT} by default}
     */
    public MediaLimits limits() {
        return limits;
    }

    /**
     * {@return the number of times an animation plays, at least 0, 0 meaning forever; 0 by default}
     */
    public int loopCount() {
        return loopCount;
    }

    /**
     * {@return whether the frames of an animation after the first predict from earlier and later frames;
     * {@code true} by default}
     *
     * <p>Predicted frames form groups of P and B pictures between IDR pictures, at most 60 frames apart, so
     * the samples of the sequence are reordered. Otherwise every frame is an independent IDR picture.
     */
    public boolean interFrames() {
        return interFrames;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof HeicEncoderOptions that
                && quality == that.quality
                && alphaQuality == that.alphaQuality
                && speed == that.speed
                && bitDepth.equals(that.bitDepth)
                && color.equals(that.color)
                && Objects.equals(colorProfile, that.colorProfile)
                && orientation == that.orientation
                && Arrays.equals(exif, that.exif)
                && Arrays.equals(xmp, that.xmp)
                && premultipliedAlpha == that.premultipliedAlpha
                && gridTileSize.equals(that.gridTileSize)
                && threads == that.threads
                && limits.equals(that.limits)
                && loopCount == that.loopCount
                && interFrames == that.interFrames;
    }

    @Override
    public int hashCode() {
        return Objects.hash(quality, alphaQuality, speed, bitDepth, color, colorProfile, orientation,
                Arrays.hashCode(exif), Arrays.hashCode(xmp), premultipliedAlpha, gridTileSize, threads, limits,
                loopCount, interFrames);
    }

    @Override
    public String toString() {
        return "HeicEncoderOptions[quality=" + quality + ", alphaQuality=" + alphaQuality + ", speed=" + speed
                + ", bitDepth=" + (bitDepth.isEmpty() ? "auto" : bitDepth.getAsInt()) + ", color=" + color
                + (colorProfile == null ? "" : ", colorProfile=" + colorProfile.description().orElse("unnamed"))
                + ", orientation=" + orientation + (exif == null ? "" : ", exif=" + exif.length + " bytes")
                + (xmp == null ? "" : ", xmp=" + xmp.length + " bytes") + ", premultipliedAlpha=" + premultipliedAlpha
                + ", gridTileSize=" + (gridTileSize.isEmpty() ? "auto" : gridTileSize.getAsInt()) + ", threads="
                + threads + ", limits=" + limits + ", loopCount=" + loopCount + ", interFrames=" + interFrames + "]";
    }

    /**
     * A builder of {@link HeicEncoderOptions}.
     *
     * <p>Setters reject only {@code null}; {@link #build()} checks the ranges. Instances are not thread-safe.
     */
    public static final class Builder {
        private int quality = DEFAULT_QUALITY;

        private int alphaQuality = 100;

        private HevcEncoderSpeed speed = HevcEncoderSpeed.BALANCED;

        private OptionalInt bitDepth = OptionalInt.empty();

        private ColorInfo color = ColorInfo.JPEG;

        private ColorProfile colorProfile;

        private Orientation orientation = Orientation.NORMAL;

        private byte[] exif;

        private byte[] xmp;

        private boolean premultipliedAlpha;

        private OptionalInt gridTileSize = OptionalInt.empty();

        private int threads;

        private MediaLimits limits = MediaLimits.DEFAULT;

        private int loopCount;

        private boolean interFrames = true;

        private Builder() {
        }

        private Builder(HeicEncoderOptions o) {
            this.quality = o.quality;
            this.alphaQuality = o.alphaQuality;
            this.speed = o.speed;
            this.bitDepth = o.bitDepth;
            this.color = o.color;
            this.colorProfile = o.colorProfile;
            this.orientation = o.orientation;
            this.exif = o.exif;
            this.xmp = o.xmp;
            this.premultipliedAlpha = o.premultipliedAlpha;
            this.gridTileSize = o.gridTileSize;
            this.threads = o.threads;
            this.limits = o.limits;
            this.loopCount = o.loopCount;
            this.interFrames = o.interFrames;
        }

        /**
         * Sets the quality; see {@link HeicEncoderOptions#quality()}.
         *
         * @param quality the value
         * @return this builder
         */
        public Builder quality(int quality) {
            this.quality = quality;
            return this;
        }

        /**
         * Sets the alpha quality; see {@link HeicEncoderOptions#alphaQuality()}.
         *
         * @param alphaQuality the value
         * @return this builder
         */
        public Builder alphaQuality(int alphaQuality) {
            this.alphaQuality = alphaQuality;
            return this;
        }

        /**
         * Sets the speed; see {@link HeicEncoderOptions#speed()}.
         *
         * @param speed the value
         * @return this builder
         */
        public Builder speed(HevcEncoderSpeed speed) {
            this.speed = Objects.requireNonNull(speed, "speed");
            return this;
        }

        /**
         * Sets the bit depth; see {@link HeicEncoderOptions#bitDepth()}.
         *
         * @param bitDepth the value
         * @return this builder
         */
        public Builder bitDepth(int bitDepth) {
            this.bitDepth = OptionalInt.of(bitDepth);
            return this;
        }

        /**
         * Codes at the bit depth chosen from the picture; see {@link HeicEncoderOptions#bitDepth()}.
         *
         * @return this builder
         */
        public Builder automaticBitDepth() {
            this.bitDepth = OptionalInt.empty();
            return this;
        }

        /**
         * Sets the color description of RGB pictures; see {@link HeicEncoderOptions#color()}.
         *
         * @param color the value
         * @return this builder
         */
        public Builder color(ColorInfo color) {
            this.color = Objects.requireNonNull(color, "color");
            return this;
        }

        /**
         * Sets the ICC profile; see {@link HeicEncoderOptions#colorProfile()}.
         *
         * @param colorProfile the value
         * @return this builder
         */
        public Builder colorProfile(ColorProfile colorProfile) {
            this.colorProfile = Objects.requireNonNull(colorProfile, "colorProfile");
            return this;
        }

        /**
         * Embeds no ICC profile; see {@link HeicEncoderOptions#colorProfile()}.
         *
         * @return this builder
         */
        public Builder noColorProfile() {
            this.colorProfile = null;
            return this;
        }

        /**
         * Sets the display orientation; see {@link HeicEncoderOptions#orientation()}.
         *
         * @param orientation the value
         * @return this builder
         */
        public Builder orientation(Orientation orientation) {
            this.orientation = Objects.requireNonNull(orientation, "orientation");
            return this;
        }

        /**
         * Sets the Exif metadata; see {@link HeicEncoderOptions#exif()}.
         *
         * @param exif the value, copied
         * @return this builder
         */
        public Builder exif(byte[] exif) {
            this.exif = exif.clone();
            return this;
        }

        /**
         * Embeds no Exif metadata; see {@link HeicEncoderOptions#exif()}.
         *
         * @return this builder
         */
        public Builder noExif() {
            this.exif = null;
            return this;
        }

        /**
         * Sets the XMP metadata; see {@link HeicEncoderOptions#xmp()}.
         *
         * @param xmp the value, copied
         * @return this builder
         */
        public Builder xmp(byte[] xmp) {
            this.xmp = xmp.clone();
            return this;
        }

        /**
         * Embeds no XMP metadata; see {@link HeicEncoderOptions#xmp()}.
         *
         * @return this builder
         */
        public Builder noXmp() {
            this.xmp = null;
            return this;
        }

        /**
         * Embeds neither Exif nor XMP metadata; see {@link HeicEncoderOptions#exif()} and
         * {@link HeicEncoderOptions#xmp()}.
         *
         * @return this builder
         */
        public Builder noMetadata() {
            this.exif = null;
            this.xmp = null;
            return this;
        }

        /**
         * Sets whether color is premultiplied by alpha; see {@link HeicEncoderOptions#premultipliedAlpha()}.
         *
         * @param premultipliedAlpha the value
         * @return this builder
         */
        public Builder premultipliedAlpha(boolean premultipliedAlpha) {
            this.premultipliedAlpha = premultipliedAlpha;
            return this;
        }

        /**
         * Sets the side of the images of a grid; see {@link HeicEncoderOptions#gridTileSize()}.
         *
         * @param gridTileSize the value
         * @return this builder
         */
        public Builder gridTileSize(int gridTileSize) {
            this.gridTileSize = OptionalInt.of(gridTileSize);
            return this;
        }

        /**
         * Splits only very large pictures into an image grid; see {@link HeicEncoderOptions#gridTileSize()}.
         *
         * @return this builder
         */
        public Builder automaticGridTileSize() {
            this.gridTileSize = OptionalInt.empty();
            return this;
        }

        /**
         * Sets the thread count; see {@link HeicEncoderOptions#threads()}.
         *
         * @param threads the value
         * @return this builder
         */
        public Builder threads(int threads) {
            this.threads = threads;
            return this;
        }

        /**
         * Sets the allocation limits; see {@link HeicEncoderOptions#limits()}.
         *
         * @param limits the value
         * @return this builder
         */
        public Builder limits(MediaLimits limits) {
            this.limits = Objects.requireNonNull(limits, "limits");
            return this;
        }

        /**
         * Sets the number of times an animation plays; see {@link HeicEncoderOptions#loopCount()}.
         *
         * @param loopCount the value
         * @return this builder
         */
        public Builder loopCount(int loopCount) {
            this.loopCount = loopCount;
            return this;
        }

        /**
         * Sets whether the frames of an animation after the first predict from other frames; see
         * {@link HeicEncoderOptions#interFrames()}.
         *
         * @param interFrames the value
         * @return this builder
         */
        public Builder interFrames(boolean interFrames) {
            this.interFrames = interFrames;
            return this;
        }

        /**
         * Returns the options holding the settings of this builder.
         *
         * @return the options
         * @throws IllegalArgumentException if the quality or the alpha quality is not in [0, 100], the bit depth
         * is not 8 or 10, the grid image side is not an even number in [64, 5970], the thread count is negative,
         * the loop count is negative, or the color description uses the identity or a YCgCo-R matrix
         */
        public HeicEncoderOptions build() {
            Checks.inRange("quality", quality, 0, 100);
            Checks.inRange("alpha quality", alphaQuality, 0, 100);
            if (bitDepth.isPresent() && bitDepth.getAsInt() != 8 && bitDepth.getAsInt() != 10) {
                throw new IllegalArgumentException("bit depth " + bitDepth.getAsInt() + " is not 8 or 10");
            }
            if (gridTileSize.isPresent()) {
                var side = Checks.inRange("grid image side", gridTileSize.getAsInt(), 64, MAX_GRID_TILE);
                if ((side & 1) != 0) {
                    throw new IllegalArgumentException("grid image side " + side + " is not even");
                }
            }
            if (color.matrix() == MatrixCoefficients.IDENTITY || YcgcoR.applies(color.matrix())) {
                throw new IllegalArgumentException("the " + color.matrix() + " matrix needs 4:4:4 chroma, which the "
                        + "HEIC encoder does not code");
            }
            Checks.nonNegative("thread count", threads);
            Checks.nonNegative("loop count", loopCount);
            return new HeicEncoderOptions(this);
        }
    }
}
