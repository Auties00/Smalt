package com.github.auties00.smalt.image.jpeg;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.image.ops.Region;
import com.github.auties00.smalt.internal.check.Checks;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

/**
 * Holds the settings of a lossless transformation by {@link JpegTransforms}. Instances are
 * immutable and thread-safe; create them with {@link #builder()} or a preset such as
 * {@link #defaults()}.
 *
 * <p>Nothing here re-encodes pixels: the DCT coefficients of the source are moved, mirrored,
 * dropped, cleared or replaced by those of another JPEG image, then entropy coded again, so the
 * output decodes to exactly the transformed pixels of the source (except the untransformed edge
 * blocks of an imperfect, untrimmed transform, the blocks a crop extension or a wipe fills, and the
 * coefficients a drop requantizes).
 */
public final class JpegTransformOptions {
    /**
     * The options of {@link #defaults()}.
     */
    private static final JpegTransformOptions DEFAULTS = new Builder().build();

    /**
     * The options of {@link #jpegtran()}.
     */
    private static final JpegTransformOptions JPEGTRAN = new Builder().trim(false).optimizeHuffman(false)
            .markerCopy(JpegMarkerCopy.COMMENTS).transformThumbnail(false).build();

    private final Orientation transform;

    private final Region crop;

    private final boolean exactCropWidth;

    private final boolean exactCropHeight;

    private final Region wipe;

    private final JpegFill fill;

    private final byte[] dropImage;

    private final int dropX;

    private final int dropY;

    private final boolean requantizeDrop;

    private final boolean perfect;

    private final boolean trim;

    private final boolean grayscale;

    private final boolean progressive;

    private final boolean arithmetic;

    private final boolean optimizeHuffman;

    private final JpegMarkerCopy markerCopy;

    private final int restartInterval;

    private final int restartRows;

    private final boolean resetOrientation;

    private final boolean transformThumbnail;

    private final MediaLimits limits;

    private JpegTransformOptions(Builder builder) {
        this.transform = builder.transform;
        this.crop = builder.crop;
        this.exactCropWidth = builder.exactCropWidth;
        this.exactCropHeight = builder.exactCropHeight;
        this.wipe = builder.wipe;
        this.fill = builder.fill;
        this.dropImage = builder.dropImage;
        this.dropX = builder.dropX;
        this.dropY = builder.dropY;
        this.requantizeDrop = builder.requantizeDrop;
        this.perfect = builder.perfect;
        this.trim = builder.trim;
        this.grayscale = builder.grayscale;
        this.progressive = builder.progressive;
        this.arithmetic = builder.arithmetic;
        this.optimizeHuffman = builder.optimizeHuffman;
        this.markerCopy = builder.markerCopy;
        this.restartInterval = builder.restartInterval;
        this.restartRows = builder.restartRows;
        this.resetOrientation = builder.resetOrientation;
        this.transformThumbnail = builder.transformThumbnail;
        this.limits = builder.limits;
    }

    /**
     * Returns the settings suited to applications: no transform, partial edge MCUs trimmed, every
     * marker segment copied, the EXIF thumbnail transformed with the image, optimized Huffman tables,
     * a sequential output and no restart markers.
     *
     * @return the defaults
     */
    public static JpegTransformOptions defaults() {
        return DEFAULTS;
    }

    /**
     * Returns the settings {@code jpegtran} uses when given no switch: no trimming, only comments
     * copied, the standard Huffman tables of T.81 Annex K, a sequential output, and the EXIF
     * segment copied untouched.
     *
     * <p>With these settings the output is byte-identical to that of libjpeg-turbo's
     * {@code jpegtran} for the same transform, crop (with its {@code f} and {@code r} flags),
     * {@code -wipe}, {@code -drop}, {@code -copy}, {@code -optimize}, {@code -progressive},
     * {@code -arithmetic}, {@code -grayscale}, {@code -perfect}, {@code -trim} and {@code -restart}
     * switches. The flags map to {@link #fill()}, {@link #exactCropWidth()} and
     * {@link #exactCropHeight()}, and {@code -trim} with {@code -drop} to {@link #requantizeDrop()}.
     *
     * @return the jpegtran defaults
     */
    public static JpegTransformOptions jpegtran() {
        return JPEGTRAN;
    }

    /**
     * Returns the {@link #defaults()} with a transform.
     *
     * @param transform the geometric transform, named by the orientation it corrects
     * @return the options
     */
    public static JpegTransformOptions of(Orientation transform) {
        return new Builder().transform(transform).build();
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
     * {@return the geometric transform, named by the orientation it corrects (for example
     * {@link Orientation#ROTATE_90} rotates by 90 degrees clockwise); {@link Orientation#NORMAL}, which
     * moves nothing, by default}
     *
     * <p>A transform moves whole blocks, so it is lossless only for the blocks it can move: when the
     * image size is not a multiple of the MCU size, the partial MCUs at the right or bottom edge stay
     * in place unless {@link #trim()} drops them.
     */
    public Orientation transform() {
        return transform;
    }

    /**
     * {@return the crop region in the coordinates of the transformed image, or empty to keep the
     * whole image; empty by default}
     *
     * <p>Only whole blocks can be kept, so the top-left corner of the region moves up and left to the
     * nearest MCU boundary (8 or 16 pixels, by the chroma subsampling) and the output grows by the same
     * amount, always covering the requested region, unless {@link #exactCropWidth()} or
     * {@link #exactCropHeight()} keeps the requested size.
     *
     * <p>A region wider or taller than the image extends it instead, when no geometric transform is
     * asked: the output then has the region's width (or height), the image sits at the region's
     * {@code x} (or {@code y}) offset rounded down to an MCU boundary, and the added area is filled as
     * {@link #fill()} says. A region of the image's own width or height crops that dimension normally.
     * A region that neither fits the transformed image nor extends it as described fails the
     * transformation with an {@link IllegalArgumentException}: one with an offset beyond the image,
     * one that would push an extended image past its edge, one wider or taller than the 65535 pixels
     * a JPEG frame can record, and any extension under a transform.
     */
    public Optional<Region> crop() {
        return Optional.ofNullable(crop);
    }

    /**
     * {@return whether a crop no wider than the image yields exactly the requested width, starting
     * at the MCU boundary left of the requested corner, rather than growing to cover the requested
     * region; {@code false} by default}
     *
     * <p>This is {@code jpegtran}'s {@code f} flag on the crop width; it has no effect on a width that
     * extends the image.
     */
    public boolean exactCropWidth() {
        return exactCropWidth;
    }

    /**
     * {@return whether a crop no taller than the image yields exactly the requested height,
     * starting at the MCU boundary above the requested corner, rather than growing to cover the
     * requested region; {@code false} by default}
     *
     * <p>This is {@code jpegtran}'s {@code f} flag on the crop height; it has no effect on a height that
     * extends the image.
     */
    public boolean exactCropHeight() {
        return exactCropHeight;
    }

    /**
     * {@return the region of the image to clear, or empty to clear nothing; empty by default}
     *
     * <p>The region grows to the MCU grid so that it covers every requested pixel, and its blocks are
     * filled as {@link #fill()} says, while the rest of the image is kept losslessly. A wipe cannot be
     * combined with a geometric transform, a crop or a drop, and a region that does not fit the image
     * fails the transformation with an {@link IllegalArgumentException}.
     */
    public Optional<Region> wipe() {
        return Optional.ofNullable(wipe);
    }

    /**
     * {@return how the area a crop extension adds or a wipe clears is filled; {@link JpegFill#GRAY}
     * by default}
     */
    public JpegFill fill() {
        return fill;
    }

    /**
     * {@return a copy of the JPEG image dropped into the source, or empty to drop nothing; empty by
     * default}
     *
     * <p>A drop replaces the blocks of the source at {@link #dropX()}, {@link #dropY()} with the
     * coefficients of this image. The offset moves right and down to the next MCU boundary, and only
     * the whole MCUs of the image that then fit are copied, with its partial last MCU column or row
     * when the image reaches the right or bottom edge of the source. The image must be a DCT-coded
     * JPEG of the same precision whose components have the same sampling relative to their frame as
     * those of the source (a gray image drops into the luma of a color one, and a color one into a
     * gray one through its luma); the components it lacks are filled with gray and those it has
     * beyond the source's are ignored. When
     * the quantization tables differ, the output's tables become the greatest common divisors of the
     * two, so that both images keep their exact coefficients, unless {@link #requantizeDrop()} is set.
     * A drop cannot be combined with a geometric transform, a crop or a wipe, and an image that does
     * not fit the source at the offset fails the transformation with an
     * {@link IllegalArgumentException}.
     */
    public Optional<byte[]> dropImage() {
        return Optional.ofNullable(dropImage).map(byte[]::clone);
    }

    /**
     * {@return the horizontal offset of the dropped image in the source, in pixels; 0 by default}
     */
    public int dropX() {
        return dropX;
    }

    /**
     * {@return the vertical offset of the dropped image in the source, in pixels; 0 by default}
     */
    public int dropY() {
        return dropY;
    }

    /**
     * {@return whether the dropped image is requantized to the source's quantization tables, rounding
     * its coefficients, rather than the output adopting tables that keep both images exact;
     * {@code false} by default}
     *
     * <p>This is {@code jpegtran}'s {@code -trim} combined with {@code -drop}.
     */
    public boolean requantizeDrop() {
        return requantizeDrop;
    }

    /**
     * {@return whether a transform that would leave partial edge MCUs in place is rejected instead;
     * {@code false} by default}
     */
    public boolean perfect() {
        return perfect;
    }

    /**
     * {@return whether the partial edge MCUs a transform cannot move are dropped, trimming the
     * output by up to one MCU; {@code true} by default}
     */
    public boolean trim() {
        return trim;
    }

    /**
     * {@return whether the chroma components of a YCbCr image are dropped, keeping only the luma;
     * {@code false} by default}
     */
    public boolean grayscale() {
        return grayscale;
    }

    /**
     * {@return whether the output is a progressive JPEG rather than a sequential one; {@code false}
     * by default}
     */
    public boolean progressive() {
        return progressive;
    }

    /**
     * {@return whether the output is arithmetic coded (T.81 Annex D, {@code SOF9} or {@code SOF10})
     * rather than Huffman coded; {@code false} by default}
     *
     * <p>Arithmetic coding gives a smaller file than optimized Huffman tables, but some decoders do
     * not support it.
     */
    public boolean arithmetic() {
        return arithmetic;
    }

    /**
     * {@return whether the Huffman tables of a sequential output are optimized for the image rather
     * than taken from T.81 Annex K; {@code true} by default}
     *
     * <p>A progressive or 12-bit Huffman-coded output always has optimized tables, and an
     * arithmetic-coded output has none.
     */
    public boolean optimizeHuffman() {
        return optimizeHuffman;
    }

    /**
     * {@return which marker segments of the source are copied; {@link JpegMarkerCopy#ALL} by
     * default}
     */
    public JpegMarkerCopy markerCopy() {
        return markerCopy;
    }

    /**
     * {@return the number of MCUs between restart markers, from 1 to 65535, or 0 for none; 0 by
     * default}
     *
     * <p>At most one of this and {@link #restartRows()} is not zero.
     */
    public int restartInterval() {
        return restartInterval;
    }

    /**
     * {@return the number of MCU rows between restart markers, from 1 to 65535, or 0 when
     * {@link #restartInterval()} applies; 0 by default}
     *
     * <p>Each scan gets a restart interval of this many rows of its own MCUs (a scan of one component
     * has one block per MCU), at most 65535 MCUs, so the interval of a progressive output can differ
     * from scan to scan.
     */
    public int restartRows() {
        return restartRows;
    }

    /**
     * {@return whether the EXIF {@code Orientation} tag of a copied EXIF segment is reset to normal;
     * {@code false} by default}
     */
    public boolean resetOrientation() {
        return resetOrientation;
    }

    /**
     * {@return whether the thumbnail of a copied EXIF segment, JPEG or uncompressed, is transformed
     * with the image (rotated, flipped or made grayscale, not cropped, wiped or dropped into);
     * {@code true} by default}
     *
     * <p>A thumbnail that cannot be transformed (an uncompressed one whose samples are not 8 or 16
     * bits, or are subsampled YCbCr) is removed.
     */
    public boolean transformThumbnail() {
        return transformThumbnail;
    }

    /**
     * {@return the limits on the input size, the image size and the memory used;
     * {@link MediaLimits#DEFAULT} by default}
     *
     * <p>They apply to a dropped image too.
     */
    public MediaLimits limits() {
        return limits;
    }

    /**
     * Compares these options with another object; dropped images are compared by content.
     *
     * @param other the object to compare with
     * @return {@code true} if {@code other} holds the same settings
     */
    @Override
    public boolean equals(Object other) {
        return other instanceof JpegTransformOptions that
                && transform == that.transform
                && Objects.equals(crop, that.crop)
                && exactCropWidth == that.exactCropWidth
                && exactCropHeight == that.exactCropHeight
                && Objects.equals(wipe, that.wipe)
                && fill == that.fill
                && Arrays.equals(dropImage, that.dropImage)
                && dropX == that.dropX
                && dropY == that.dropY
                && requantizeDrop == that.requantizeDrop
                && perfect == that.perfect
                && trim == that.trim
                && grayscale == that.grayscale
                && progressive == that.progressive
                && arithmetic == that.arithmetic
                && optimizeHuffman == that.optimizeHuffman
                && markerCopy == that.markerCopy
                && restartInterval == that.restartInterval
                && restartRows == that.restartRows
                && resetOrientation == that.resetOrientation
                && transformThumbnail == that.transformThumbnail
                && Objects.equals(limits, that.limits);
    }

    @Override
    public int hashCode() {
        return Objects.hash(transform, crop, exactCropWidth, exactCropHeight, wipe, fill, Arrays.hashCode(dropImage),
                dropX, dropY, requantizeDrop, perfect, trim, grayscale, progressive, arithmetic, optimizeHuffman,
                markerCopy, restartInterval, restartRows, resetOrientation, transformThumbnail, limits);
    }

    @Override
    public String toString() {
        return "JpegTransformOptions[" + "transform=" + transform + ", "
                + "crop=" + crop + ", "
                + "exactCropWidth=" + exactCropWidth + ", "
                + "exactCropHeight=" + exactCropHeight + ", "
                + "wipe=" + wipe + ", "
                + "fill=" + fill + ", "
                + "drop=" + (dropImage == null ? "none" : dropImage.length + " bytes at " + dropX + "," + dropY)
                + ", "
                + "requantizeDrop=" + requantizeDrop + ", "
                + "perfect=" + perfect + ", "
                + "trim=" + trim + ", "
                + "grayscale=" + grayscale + ", "
                + "progressive=" + progressive + ", "
                + "arithmetic=" + arithmetic + ", "
                + "optimizeHuffman=" + optimizeHuffman + ", "
                + "markerCopy=" + markerCopy + ", "
                + "restartInterval=" + restartInterval + ", "
                + "restartRows=" + restartRows + ", "
                + "resetOrientation=" + resetOrientation + ", "
                + "transformThumbnail=" + transformThumbnail + ", "
                + "limits=" + limits + "]";
    }

    /**
     * A builder of {@link JpegTransformOptions}.
     *
     * <p>Setters reject only {@code null}; {@link #build()} checks the ranges and the combination of a
     * crop, a wipe, a drop and a geometric transform. Instances are not thread-safe.
     */
    public static final class Builder {
        private Orientation transform = Orientation.NORMAL;

        private Region crop;

        private boolean exactCropWidth;

        private boolean exactCropHeight;

        private Region wipe;

        private JpegFill fill = JpegFill.GRAY;

        private byte[] dropImage;

        private int dropX;

        private int dropY;

        private boolean requantizeDrop;

        private boolean perfect;

        private boolean trim = true;

        private boolean grayscale;

        private boolean progressive;

        private boolean arithmetic;

        private boolean optimizeHuffman = true;

        private JpegMarkerCopy markerCopy = JpegMarkerCopy.ALL;

        private int restartInterval;

        private int restartRows;

        private boolean resetOrientation;

        private boolean transformThumbnail = true;

        private MediaLimits limits = MediaLimits.DEFAULT;

        private Builder() {
        }

        private Builder(JpegTransformOptions options) {
            this.transform = options.transform;
            this.crop = options.crop;
            this.exactCropWidth = options.exactCropWidth;
            this.exactCropHeight = options.exactCropHeight;
            this.wipe = options.wipe;
            this.fill = options.fill;
            this.dropImage = options.dropImage;
            this.dropX = options.dropX;
            this.dropY = options.dropY;
            this.requantizeDrop = options.requantizeDrop;
            this.perfect = options.perfect;
            this.trim = options.trim;
            this.grayscale = options.grayscale;
            this.progressive = options.progressive;
            this.arithmetic = options.arithmetic;
            this.optimizeHuffman = options.optimizeHuffman;
            this.markerCopy = options.markerCopy;
            this.restartInterval = options.restartInterval;
            this.restartRows = options.restartRows;
            this.resetOrientation = options.resetOrientation;
            this.transformThumbnail = options.transformThumbnail;
            this.limits = options.limits;
        }

        /**
         * Sets the geometric transform; see {@link JpegTransformOptions#transform()}.
         *
         * @param transform the value
         * @return this builder
         */
        public Builder transform(Orientation transform) {
            this.transform = Objects.requireNonNull(transform, "transform");
            return this;
        }

        /**
         * Sets the crop region; see {@link JpegTransformOptions#crop()}.
         *
         * @param crop the value
         * @return this builder
         */
        public Builder crop(Region crop) {
            this.crop = Objects.requireNonNull(crop, "crop");
            return this;
        }

        /**
         * Keeps the whole image; see {@link JpegTransformOptions#crop()}.
         *
         * @return this builder
         */
        public Builder noCrop() {
            this.crop = null;
            return this;
        }

        /**
         * Sets whether a crop keeps exactly the requested width; see
         * {@link JpegTransformOptions#exactCropWidth()}.
         *
         * @param exactCropWidth the value
         * @return this builder
         */
        public Builder exactCropWidth(boolean exactCropWidth) {
            this.exactCropWidth = exactCropWidth;
            return this;
        }

        /**
         * Sets whether a crop keeps exactly the requested height; see
         * {@link JpegTransformOptions#exactCropHeight()}.
         *
         * @param exactCropHeight the value
         * @return this builder
         */
        public Builder exactCropHeight(boolean exactCropHeight) {
            this.exactCropHeight = exactCropHeight;
            return this;
        }

        /**
         * Sets the region to clear; see {@link JpegTransformOptions#wipe()}.
         *
         * @param wipe the value
         * @return this builder
         */
        public Builder wipe(Region wipe) {
            this.wipe = Objects.requireNonNull(wipe, "wipe");
            return this;
        }

        /**
         * Clears nothing; see {@link JpegTransformOptions#wipe()}.
         *
         * @return this builder
         */
        public Builder noWipe() {
            this.wipe = null;
            return this;
        }

        /**
         * Sets how added or cleared areas are filled; see {@link JpegTransformOptions#fill()}.
         *
         * @param fill the value
         * @return this builder
         */
        public Builder fill(JpegFill fill) {
            this.fill = Objects.requireNonNull(fill, "fill");
            return this;
        }

        /**
         * Sets the image to drop into the source and its offset; see
         * {@link JpegTransformOptions#dropImage()}.
         *
         * @param image the JPEG file, copied
         * @param x the horizontal offset in pixels, not negative
         * @param y the vertical offset in pixels, not negative
         * @return this builder
         */
        public Builder drop(byte[] image, int x, int y) {
            this.dropImage = Objects.requireNonNull(image, "image").clone();
            this.dropX = x;
            this.dropY = y;
            return this;
        }

        /**
         * Drops nothing; see {@link JpegTransformOptions#dropImage()}.
         *
         * @return this builder
         */
        public Builder noDrop() {
            this.dropImage = null;
            this.dropX = 0;
            this.dropY = 0;
            return this;
        }

        /**
         * Sets whether a dropped image is requantized to the source's tables; see
         * {@link JpegTransformOptions#requantizeDrop()}.
         *
         * @param requantizeDrop the value
         * @return this builder
         */
        public Builder requantizeDrop(boolean requantizeDrop) {
            this.requantizeDrop = requantizeDrop;
            return this;
        }

        /**
         * Sets whether an imperfect transform is rejected; see {@link JpegTransformOptions#perfect()}.
         *
         * @param perfect the value
         * @return this builder
         */
        public Builder perfect(boolean perfect) {
            this.perfect = perfect;
            return this;
        }

        /**
         * Sets whether the partial edge MCUs a transform cannot move are dropped; see
         * {@link JpegTransformOptions#trim()}.
         *
         * @param trim the value
         * @return this builder
         */
        public Builder trim(boolean trim) {
            this.trim = trim;
            return this;
        }

        /**
         * Sets whether the chroma components of a YCbCr image are dropped; see
         * {@link JpegTransformOptions#grayscale()}.
         *
         * @param grayscale the value
         * @return this builder
         */
        public Builder grayscale(boolean grayscale) {
            this.grayscale = grayscale;
            return this;
        }

        /**
         * Sets whether the output is progressive; see {@link JpegTransformOptions#progressive()}.
         *
         * @param progressive the value
         * @return this builder
         */
        public Builder progressive(boolean progressive) {
            this.progressive = progressive;
            return this;
        }

        /**
         * Sets whether the output is arithmetic coded; see {@link JpegTransformOptions#arithmetic()}.
         *
         * @param arithmetic the value
         * @return this builder
         */
        public Builder arithmetic(boolean arithmetic) {
            this.arithmetic = arithmetic;
            return this;
        }

        /**
         * Sets whether the Huffman tables are optimized; see
         * {@link JpegTransformOptions#optimizeHuffman()}.
         *
         * @param optimizeHuffman the value
         * @return this builder
         */
        public Builder optimizeHuffman(boolean optimizeHuffman) {
            this.optimizeHuffman = optimizeHuffman;
            return this;
        }

        /**
         * Sets which marker segments of the source are copied; see
         * {@link JpegTransformOptions#markerCopy()}.
         *
         * @param markerCopy the value
         * @return this builder
         */
        public Builder markerCopy(JpegMarkerCopy markerCopy) {
            this.markerCopy = Objects.requireNonNull(markerCopy, "markerCopy");
            return this;
        }

        /**
         * Sets the number of MCUs between restart markers and resets the restart rows to 0; see
         * {@link JpegTransformOptions#restartInterval()}.
         *
         * @param restartInterval the value
         * @return this builder
         */
        public Builder restartInterval(int restartInterval) {
            this.restartInterval = restartInterval;
            this.restartRows = 0;
            return this;
        }

        /**
         * Sets the number of MCU rows between restart markers; a value other than 0 resets the
         * restart interval to 0, while 0 leaves it as set, as {@code jpegtran -restart 0} does; see
         * {@link JpegTransformOptions#restartRows()}.
         *
         * @param restartRows the value
         * @return this builder
         */
        public Builder restartRows(int restartRows) {
            this.restartRows = restartRows;
            if (restartRows != 0) {
                this.restartInterval = 0;
            }
            return this;
        }

        /**
         * Sets whether the EXIF orientation is reset to normal; see
         * {@link JpegTransformOptions#resetOrientation()}.
         *
         * @param resetOrientation the value
         * @return this builder
         */
        public Builder resetOrientation(boolean resetOrientation) {
            this.resetOrientation = resetOrientation;
            return this;
        }

        /**
         * Sets whether the EXIF thumbnail is transformed too; see
         * {@link JpegTransformOptions#transformThumbnail()}.
         *
         * @param transformThumbnail the value
         * @return this builder
         */
        public Builder transformThumbnail(boolean transformThumbnail) {
            this.transformThumbnail = transformThumbnail;
            return this;
        }

        /**
         * Sets the limits; see {@link JpegTransformOptions#limits()}.
         *
         * @param limits the value
         * @return this builder
         */
        public Builder limits(MediaLimits limits) {
            this.limits = Objects.requireNonNull(limits, "limits");
            return this;
        }

        /**
         * Returns the options holding the settings of this builder.
         *
         * @return the options
         * @throws IllegalArgumentException if the restart interval or the restart rows are not in
         * [0, 65535], a drop offset is negative, more than one of a crop, a wipe and a drop is set, or
         * a wipe or a drop is combined with a geometric transform
         */
        public JpegTransformOptions build() {
            Checks.inRange("restart interval", restartInterval, 0, 0xFFFF);
            Checks.inRange("restart rows", restartRows, 0, 0xFFFF);
            Checks.nonNegative("drop x offset", dropX);
            Checks.nonNegative("drop y offset", dropY);
            var regions = (crop != null ? 1 : 0) + (wipe != null ? 1 : 0) + (dropImage != null ? 1 : 0);
            if (regions > 1) {
                throw new IllegalArgumentException("only one of a crop, a wipe and a drop can be set");
            }
            if ((wipe != null || dropImage != null) && transform != Orientation.NORMAL) {
                throw new IllegalArgumentException("a " + (wipe != null ? "wipe" : "drop")
                        + " cannot be combined with the transform " + transform);
            }
            return new JpegTransformOptions(this);
        }
    }
}
