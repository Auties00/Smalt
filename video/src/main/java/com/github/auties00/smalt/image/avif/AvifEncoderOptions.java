package com.github.auties00.smalt.image.avif;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ColorPrimaries;
import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.image.MatrixCoefficients;
import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.image.TransferCharacteristics;
import com.github.auties00.smalt.image.color.ColorProfile;
import com.github.auties00.smalt.internal.check.Checks;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Holds the settings of an {@link AvifEncoder}. Instances are immutable and thread-safe; create
 * them with {@link #builder()} or a preset such as {@link #defaults()}.
 *
 * @apiNote The quality scale is that of libavif's {@code avifenc -q}.
 */
public final class AvifEncoderOptions {
    /**
     * The default quality, libavif's.
     */
    private static final int DEFAULT_QUALITY = 60;

    /**
     * The default speed, libavif's.
     */
    private static final int DEFAULT_SPEED = 6;

    /**
     * The color description of RGB pictures by default: BT.601 matrix, full range, BT.709
     * primaries and the sRGB transfer, as libavif converts RGB.
     */
    private static final ColorInfo DEFAULT_COLOR = new ColorInfo(MatrixCoefficients.BT601, ColorRange.FULL,
            ColorPrimaries.BT709, TransferCharacteristics.SRGB);

    /**
     * The options of {@link #defaults()}.
     */
    private static final AvifEncoderOptions DEFAULTS = new Builder().build();

    private final int quality;

    private final int alphaQuality;

    private final int speed;

    /**
     * The chroma format, or {@code null} for automatic.
     */
    private final ChromaFormat chromaFormat;

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

    private final OptionalInt tileColumnsLog2;

    private final OptionalInt tileRowsLog2;

    private final OptionalInt gridTileSize;

    private final int threads;

    private final MediaLimits limits;

    private final int loopCount;

    private final boolean interFrames;

    private AvifEncoderOptions(Builder builder) {
        this.quality = builder.quality;
        this.alphaQuality = builder.alphaQuality;
        this.speed = builder.speed;
        this.chromaFormat = builder.chromaFormat;
        this.bitDepth = builder.bitDepth;
        this.color = builder.color;
        this.colorProfile = builder.colorProfile;
        this.orientation = builder.orientation;
        this.exif = builder.exif;
        this.xmp = builder.xmp;
        this.premultipliedAlpha = builder.premultipliedAlpha;
        this.tileColumnsLog2 = builder.tileColumnsLog2;
        this.tileRowsLog2 = builder.tileRowsLog2;
        this.gridTileSize = builder.gridTileSize;
        this.threads = builder.threads;
        this.limits = builder.limits;
        this.loopCount = builder.loopCount;
        this.interFrames = builder.interFrames;
    }

    /**
     * Returns lossy coding at libavif's defaults: quality 60, lossless alpha, speed 6, the
     * picture's chroma format and depth (4:2:0 for RGB pictures), BT.601 full-range conversion of
     * RGB pictures with BT.709 primaries and the sRGB transfer, no metadata, automatic tiling.
     *
     * @return the default options
     */
    public static AvifEncoderOptions defaults() {
        return DEFAULTS;
    }

    /**
     * Returns the settings for pictures encoded on demand and served on the web: quality 60 at
     * speed 8, a fast search for compact files.
     *
     * @apiNote At speed 8 the still search skips the loop filter, CDEF and loop restoration
     * searches and the smallest partitions; for files encoded once and served many times, the
     * slower search of {@link #forDistribution()} pays off.
     * @return the options
     */
    public static AvifEncoderOptions forWeb() {
        return new Builder().quality(60).speed(8).build();
    }

    /**
     * Returns the settings for pictures encoded once and served or copied many times: quality 70
     * at speed 4, which searches the deblocking, CDEF and loop restoration filters.
     *
     * @return the options
     */
    public static AvifEncoderOptions forDistribution() {
        return new Builder().quality(70).speed(4).build();
    }

    /**
     * Returns the settings for archiving: quality 90 at speed 2, 4:4:4 at 10 bits per sample, so
     * no chroma is subsampled away and gradients keep their precision; alpha stays lossless.
     *
     * @return the options
     */
    public static AvifEncoderOptions forArchive() {
        return new Builder().quality(90).speed(2).chromaFormat(ChromaFormat.YUV444).bitDepth(10).build();
    }

    /**
     * Returns the {@link #defaults()} with another quality.
     *
     * @param quality the quality, from 0 to 100; see {@link #quality()}
     * @return the options
     * @throws IllegalArgumentException if {@code quality} is not in [0, 100]
     */
    public static AvifEncoderOptions lossy(int quality) {
        return new Builder().quality(quality).build();
    }

    /**
     * Returns lossless coding: quality 100 for color and alpha, and RGB pictures stored as 4:4:4
     * in the identity matrix (GBR), or in full range when the color's matrix is YCgCo-Re or YCgCo-Ro,
     * so every pixel is restored exactly (for YCgCo-Re and YCgCo-Ro, at the automatic bit depth).
     *
     * @return the options
     */
    public static AvifEncoderOptions lossless() {
        return new Builder().quality(100).alphaQuality(100).build();
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
     * {@return the quality, from 0 (smallest) to 100 (lossless); 60 by default}
     *
     * <p>The quality maps to the AV1 quantizer level {@code (100 - quality) * 63 / 100}, rounded
     * to nearest.
     */
    public int quality() {
        return quality;
    }

    /**
     * {@return whether the color is coded losslessly, that is at quality 100}
     */
    public boolean isLossless() {
        return quality == 100;
    }

    /**
     * {@return the quality of the alpha plane of a transparent picture, on the scale of
     * {@link #quality()}; 100 (lossless) by default}
     */
    public int alphaQuality() {
        return alphaQuality;
    }

    /**
     * {@return how hard the encoder searches, from 0 (slowest, smallest) to 10 (fastest); 6 by
     * default}
     */
    public int speed() {
        return speed;
    }

    /**
     * {@return the chroma format of the coded image, or empty for automatic; empty by default}
     *
     * <p>Automatic is the picture's own format for a planar picture (4:4:4 for a 4:4:0 one, a format
     * AV1 cannot code), 4:2:0 for a lossy RGB picture and 4:4:4 for a lossless one.
     */
    public Optional<ChromaFormat> chromaFormat() {
        return Optional.ofNullable(chromaFormat);
    }

    /**
     * {@return the bit depth of the coded image, 8, 10 or 12, or empty for automatic; empty by
     * default}
     *
     * <p>Automatic is 8 for 8-bit pictures, 10 for deeper pictures of at most 10 bits and 12 for
     * the others. RGB pictures converted with the YCgCo-Re or YCgCo-Ro matrix, whose R'G'B' samples
     * are two or one bits shallower than the coded ones, code 8-bit pictures at 10 bits and deeper ones
     * at 12.
     */
    public OptionalInt bitDepth() {
        return bitDepth;
    }

    /**
     * {@return the color description RGB pictures are converted with and the file signals for
     * them; BT.601 full range with BT.709 primaries and the sRGB transfer by default}
     *
     * <p>Planar pictures keep their own description.
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
     * {@return the display orientation the file signals with its rotation and mirroring
     * properties; {@link Orientation#NORMAL} by default}
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
     * {@return whether the color of a transparent picture is stored premultiplied by its alpha;
     * {@code false} by default}
     */
    public boolean premultipliedAlpha() {
        return premultipliedAlpha;
    }

    /**
     * {@return the base-2 logarithm of the AV1 tile columns of each coded image, from 0 to 6, or
     * empty for automatic; empty by default}
     *
     * <p>The encoder raises it as AV1 requires for wide images. Automatic tiling splits an image
     * into up to eight tiles of at least 512 by 512 samples, more columns than rows for landscape
     * images, so a multithreaded encode codes tiles in parallel. It depends on the image size only,
     * so the file does not depend on the thread count. The rows are set together with the columns
     * and are empty exactly when they are.
     */
    public OptionalInt tileColumnsLog2() {
        return tileColumnsLog2;
    }

    /**
     * {@return the base-2 logarithm of the AV1 tile rows of each coded image, from 0 to 6, or empty
     * for automatic; empty by default}
     *
     * <p>The encoder raises it as AV1 requires for large images.
     *
     * @see #tileColumnsLog2()
     */
    public OptionalInt tileRowsLog2() {
        return tileRowsLog2;
    }

    /**
     * {@return the side of the images an image grid splits the picture into, an even number from
     * 64 to 16384, or empty for automatic; empty by default}
     *
     * <p>Automatic splits only pictures wider or taller than 8192 samples, into 4096-sample images.
     */
    public OptionalInt gridTileSize() {
        return gridTileSize;
    }

    /**
     * {@return the thread count, at least 0; 0 by default}
     *
     * <p>0 encodes on the module's shared pool, 1 on the calling thread only, and a larger value
     * caps the threads of the shared pool one picture uses. The file does not depend on it.
     */
    public int threads() {
        return threads;
    }

    /**
     * {@return the allocation limits pictures are checked against; {@link MediaLimits#DEFAULT} by
     * default}
     */
    public MediaLimits limits() {
        return limits;
    }

    /**
     * {@return the number of times an animation plays, at least 0, 0 meaning forever; 0 by
     * default}
     */
    public int loopCount() {
        return loopCount;
    }

    /**
     * {@return whether the frames of an animation after the first predict from earlier and later
     * frames; {@code true} by default}
     *
     * <p>Predicted frames are coded with the good-quality AV1 usage at the options' quality and a
     * speed matching {@link #speed()}; only lossy 8-bit 4:2:0 color is predicted, and alpha always
     * codes key frames. Otherwise every frame is an independent key frame.
     */
    public boolean interFrames() {
        return interFrames;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof AvifEncoderOptions that
                && quality == that.quality
                && alphaQuality == that.alphaQuality
                && speed == that.speed
                && chromaFormat == that.chromaFormat
                && bitDepth.equals(that.bitDepth)
                && color.equals(that.color)
                && Objects.equals(colorProfile, that.colorProfile)
                && orientation == that.orientation
                && Arrays.equals(exif, that.exif)
                && Arrays.equals(xmp, that.xmp)
                && premultipliedAlpha == that.premultipliedAlpha
                && tileColumnsLog2.equals(that.tileColumnsLog2)
                && tileRowsLog2.equals(that.tileRowsLog2)
                && gridTileSize.equals(that.gridTileSize)
                && threads == that.threads
                && limits.equals(that.limits)
                && loopCount == that.loopCount
                && interFrames == that.interFrames;
    }

    @Override
    public int hashCode() {
        return Objects.hash(quality, alphaQuality, speed, chromaFormat, bitDepth, color, colorProfile, orientation,
                Arrays.hashCode(exif), Arrays.hashCode(xmp), premultipliedAlpha, tileColumnsLog2, tileRowsLog2,
                gridTileSize, threads, limits, loopCount, interFrames);
    }

    @Override
    public String toString() {
        return "AvifEncoderOptions[quality=" + quality + ", alphaQuality=" + alphaQuality + ", speed=" + speed
                + ", chromaFormat=" + (chromaFormat == null ? "auto" : chromaFormat)
                + ", bitDepth=" + (bitDepth.isEmpty() ? "auto" : bitDepth.getAsInt()) + ", color=" + color
                + (colorProfile == null ? "" : ", colorProfile=" + colorProfile.description().orElse("unnamed"))
                + ", orientation=" + orientation + (exif == null ? "" : ", exif=" + exif.length + " bytes")
                + (xmp == null ? "" : ", xmp=" + xmp.length + " bytes") + ", premultipliedAlpha=" + premultipliedAlpha
                + ", tiles=" + (tileColumnsLog2.isEmpty() ? "auto"
                : tileColumnsLog2.getAsInt() + "x" + tileRowsLog2.getAsInt())
                + ", gridTileSize=" + (gridTileSize.isEmpty() ? "auto" : gridTileSize.getAsInt()) + ", threads="
                + threads + ", limits=" + limits + ", loopCount=" + loopCount + ", interFrames=" + interFrames + "]";
    }

    /**
     * A builder of {@link AvifEncoderOptions}.
     *
     * <p>Setters reject only {@code null}; {@link #build()} checks the ranges. Instances are not
     * thread-safe.
     */
    public static final class Builder {
        private int quality = DEFAULT_QUALITY;

        private int alphaQuality = 100;

        private int speed = DEFAULT_SPEED;

        private ChromaFormat chromaFormat;

        private OptionalInt bitDepth = OptionalInt.empty();

        private ColorInfo color = DEFAULT_COLOR;

        private ColorProfile colorProfile;

        private Orientation orientation = Orientation.NORMAL;

        private byte[] exif;

        private byte[] xmp;

        private boolean premultipliedAlpha;

        private OptionalInt tileColumnsLog2 = OptionalInt.empty();

        private OptionalInt tileRowsLog2 = OptionalInt.empty();

        private OptionalInt gridTileSize = OptionalInt.empty();

        private int threads;

        private MediaLimits limits = MediaLimits.DEFAULT;

        private int loopCount;

        private boolean interFrames = true;

        private Builder() {
        }

        private Builder(AvifEncoderOptions o) {
            this.quality = o.quality;
            this.alphaQuality = o.alphaQuality;
            this.speed = o.speed;
            this.chromaFormat = o.chromaFormat;
            this.bitDepth = o.bitDepth;
            this.color = o.color;
            this.colorProfile = o.colorProfile;
            this.orientation = o.orientation;
            this.exif = o.exif;
            this.xmp = o.xmp;
            this.premultipliedAlpha = o.premultipliedAlpha;
            this.tileColumnsLog2 = o.tileColumnsLog2;
            this.tileRowsLog2 = o.tileRowsLog2;
            this.gridTileSize = o.gridTileSize;
            this.threads = o.threads;
            this.limits = o.limits;
            this.loopCount = o.loopCount;
            this.interFrames = o.interFrames;
        }

        /**
         * Sets the quality; see {@link AvifEncoderOptions#quality()}.
         *
         * @param quality the value
         * @return this builder
         */
        public Builder quality(int quality) {
            this.quality = quality;
            return this;
        }

        /**
         * Sets the alpha quality; see {@link AvifEncoderOptions#alphaQuality()}.
         *
         * @param alphaQuality the value
         * @return this builder
         */
        public Builder alphaQuality(int alphaQuality) {
            this.alphaQuality = alphaQuality;
            return this;
        }

        /**
         * Sets the speed; see {@link AvifEncoderOptions#speed()}.
         *
         * @param speed the value
         * @return this builder
         */
        public Builder speed(int speed) {
            this.speed = speed;
            return this;
        }

        /**
         * Sets the chroma format; see {@link AvifEncoderOptions#chromaFormat()}.
         *
         * @param chromaFormat the value
         * @return this builder
         */
        public Builder chromaFormat(ChromaFormat chromaFormat) {
            this.chromaFormat = Objects.requireNonNull(chromaFormat, "chromaFormat");
            return this;
        }

        /**
         * Codes in the chroma format chosen from the picture; see
         * {@link AvifEncoderOptions#chromaFormat()}.
         *
         * @return this builder
         */
        public Builder automaticChromaFormat() {
            this.chromaFormat = null;
            return this;
        }

        /**
         * Sets the bit depth; see {@link AvifEncoderOptions#bitDepth()}.
         *
         * @param bitDepth the value
         * @return this builder
         */
        public Builder bitDepth(int bitDepth) {
            this.bitDepth = OptionalInt.of(bitDepth);
            return this;
        }

        /**
         * Codes at the bit depth chosen from the picture; see
         * {@link AvifEncoderOptions#bitDepth()}.
         *
         * @return this builder
         */
        public Builder automaticBitDepth() {
            this.bitDepth = OptionalInt.empty();
            return this;
        }

        /**
         * Sets the color description of RGB pictures; see {@link AvifEncoderOptions#color()}.
         *
         * @param color the value
         * @return this builder
         */
        public Builder color(ColorInfo color) {
            this.color = Objects.requireNonNull(color, "color");
            return this;
        }

        /**
         * Sets the ICC profile; see {@link AvifEncoderOptions#colorProfile()}.
         *
         * @param colorProfile the value
         * @return this builder
         */
        public Builder colorProfile(ColorProfile colorProfile) {
            this.colorProfile = Objects.requireNonNull(colorProfile, "colorProfile");
            return this;
        }

        /**
         * Embeds no ICC profile; see {@link AvifEncoderOptions#colorProfile()}.
         *
         * @return this builder
         */
        public Builder noColorProfile() {
            this.colorProfile = null;
            return this;
        }

        /**
         * Sets the display orientation; see {@link AvifEncoderOptions#orientation()}.
         *
         * @param orientation the value
         * @return this builder
         */
        public Builder orientation(Orientation orientation) {
            this.orientation = Objects.requireNonNull(orientation, "orientation");
            return this;
        }

        /**
         * Sets the Exif metadata; see {@link AvifEncoderOptions#exif()}.
         *
         * @param exif the value, copied
         * @return this builder
         */
        public Builder exif(byte[] exif) {
            this.exif = exif.clone();
            return this;
        }

        /**
         * Embeds no Exif metadata; see {@link AvifEncoderOptions#exif()}.
         *
         * @return this builder
         */
        public Builder noExif() {
            this.exif = null;
            return this;
        }

        /**
         * Sets the XMP metadata; see {@link AvifEncoderOptions#xmp()}.
         *
         * @param xmp the value, copied
         * @return this builder
         */
        public Builder xmp(byte[] xmp) {
            this.xmp = xmp.clone();
            return this;
        }

        /**
         * Embeds no XMP metadata; see {@link AvifEncoderOptions#xmp()}.
         *
         * @return this builder
         */
        public Builder noXmp() {
            this.xmp = null;
            return this;
        }

        /**
         * Embeds neither Exif nor XMP metadata; see {@link AvifEncoderOptions#exif()} and
         * {@link AvifEncoderOptions#xmp()}.
         *
         * @return this builder
         */
        public Builder noMetadata() {
            this.exif = null;
            this.xmp = null;
            return this;
        }

        /**
         * Sets whether color is premultiplied by alpha; see
         * {@link AvifEncoderOptions#premultipliedAlpha()}.
         *
         * @param premultipliedAlpha the value
         * @return this builder
         */
        public Builder premultipliedAlpha(boolean premultipliedAlpha) {
            this.premultipliedAlpha = premultipliedAlpha;
            return this;
        }

        /**
         * Sets the AV1 tiles; see {@link AvifEncoderOptions#tileColumnsLog2()} and
         * {@link AvifEncoderOptions#tileRowsLog2()}.
         *
         * @param columnsLog2 the base-2 logarithm of the tile columns
         * @param rowsLog2 the base-2 logarithm of the tile rows
         * @return this builder
         */
        public Builder tiles(int columnsLog2, int rowsLog2) {
            this.tileColumnsLog2 = OptionalInt.of(columnsLog2);
            this.tileRowsLog2 = OptionalInt.of(rowsLog2);
            return this;
        }

        /**
         * Lets the encoder choose the AV1 tiles from the image size; see
         * {@link AvifEncoderOptions#tileColumnsLog2()}.
         *
         * @return this builder
         */
        public Builder automaticTiles() {
            this.tileColumnsLog2 = OptionalInt.empty();
            this.tileRowsLog2 = OptionalInt.empty();
            return this;
        }

        /**
         * Sets the side of the images of a grid; see {@link AvifEncoderOptions#gridTileSize()}.
         *
         * @param gridTileSize the value
         * @return this builder
         */
        public Builder gridTileSize(int gridTileSize) {
            this.gridTileSize = OptionalInt.of(gridTileSize);
            return this;
        }

        /**
         * Splits only very large pictures into an image grid; see
         * {@link AvifEncoderOptions#gridTileSize()}.
         *
         * @return this builder
         */
        public Builder automaticGridTileSize() {
            this.gridTileSize = OptionalInt.empty();
            return this;
        }

        /**
         * Sets the thread count; see {@link AvifEncoderOptions#threads()}.
         *
         * @param threads the value
         * @return this builder
         */
        public Builder threads(int threads) {
            this.threads = threads;
            return this;
        }

        /**
         * Sets the allocation limits; see {@link AvifEncoderOptions#limits()}.
         *
         * @param limits the value
         * @return this builder
         */
        public Builder limits(MediaLimits limits) {
            this.limits = Objects.requireNonNull(limits, "limits");
            return this;
        }

        /**
         * Sets the number of times an animation plays; see {@link AvifEncoderOptions#loopCount()}.
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
         * {@link AvifEncoderOptions#interFrames()}.
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
         * @throws IllegalArgumentException if the quality or the alpha quality is not in [0, 100],
         * the speed is not in [0, 10], the bit depth is not 8, 10 or 12, a tile logarithm is not in
         * [0, 6], the grid image side is not an even number in [64, 16384], the thread count is
         * negative, the loop count is negative, or the chroma format is 4:4:0, which AV1 cannot code
         */
        public AvifEncoderOptions build() {
            if (chromaFormat == ChromaFormat.YUV440) {
                throw new IllegalArgumentException("AV1 cannot code 4:4:0 chroma");
            }
            Checks.inRange("quality", quality, 0, 100);
            Checks.inRange("alpha quality", alphaQuality, 0, 100);
            Checks.inRange("speed", speed, 0, 10);
            if (bitDepth.isPresent()) {
                var depth = bitDepth.getAsInt();
                if (depth != 8 && depth != 10 && depth != 12) {
                    throw new IllegalArgumentException("bit depth " + depth + " is not 8, 10 or 12");
                }
            }
            if (tileColumnsLog2.isPresent()) {
                Checks.inRange("tile column logarithm", tileColumnsLog2.getAsInt(), 0, 6);
                Checks.inRange("tile row logarithm", tileRowsLog2.getAsInt(), 0, 6);
            }
            if (gridTileSize.isPresent()) {
                var side = Checks.inRange("grid image side", gridTileSize.getAsInt(), 64, 16384);
                if ((side & 1) != 0) {
                    throw new IllegalArgumentException("grid image side " + side + " is not even");
                }
            }
            Checks.nonNegative("thread count", threads);
            Checks.nonNegative("loop count", loopCount);
            return new AvifEncoderOptions(this);
        }
    }
}
