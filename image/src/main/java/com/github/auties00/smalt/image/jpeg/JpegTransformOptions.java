package com.github.auties00.smalt.image.jpeg;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.image.ops.Region;
import com.github.auties00.smalt.internal.check.Checks;

import java.util.Objects;
import java.util.Optional;

/**
 * Holds the settings of a lossless transformation by {@link JpegTransforms}. Instances are
 * immutable and thread-safe; create them with {@link #builder()} or a preset such as
 * {@link #defaults()}.
 *
 * <p>Nothing here re-encodes pixels: the DCT coefficients of the source are moved, mirrored or
 * dropped, then entropy coded again, so the output decodes to exactly the transformed pixels of the
 * source (except the untransformed edge blocks of an imperfect, untrimmed transform).
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

    private final boolean perfect;

    private final boolean trim;

    private final boolean grayscale;

    private final boolean progressive;

    private final boolean optimizeHuffman;

    private final JpegMarkerCopy markerCopy;

    private final int restartInterval;

    private final boolean resetOrientation;

    private final boolean transformThumbnail;

    private final MediaLimits limits;

    private JpegTransformOptions(Builder builder) {
        this.transform = builder.transform;
        this.crop = builder.crop;
        this.perfect = builder.perfect;
        this.trim = builder.trim;
        this.grayscale = builder.grayscale;
        this.progressive = builder.progressive;
        this.optimizeHuffman = builder.optimizeHuffman;
        this.markerCopy = builder.markerCopy;
        this.restartInterval = builder.restartInterval;
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
     * {@code jpegtran} for the same transform, crop, {@code -copy}, {@code -optimize},
     * {@code -progressive}, {@code -grayscale}, {@code -perfect}, {@code -trim} and
     * {@code -restart} switches.
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
     * amount, always covering the requested region. A region that does not fit the transformed image
     * fails the transformation with an {@link IllegalArgumentException}.
     */
    public Optional<Region> crop() {
        return Optional.ofNullable(crop);
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
     * {@return whether the Huffman tables of a sequential output are optimized for the image rather
     * than taken from T.81 Annex K; {@code true} by default}
     *
     * <p>A progressive or 12-bit output always has optimized tables.
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
     */
    public int restartInterval() {
        return restartInterval;
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
     * with the image (rotated, flipped or made grayscale, not cropped); {@code true} by default}
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
     */
    public MediaLimits limits() {
        return limits;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof JpegTransformOptions that
                && transform == that.transform
                && Objects.equals(crop, that.crop)
                && perfect == that.perfect
                && trim == that.trim
                && grayscale == that.grayscale
                && progressive == that.progressive
                && optimizeHuffman == that.optimizeHuffman
                && markerCopy == that.markerCopy
                && restartInterval == that.restartInterval
                && resetOrientation == that.resetOrientation
                && transformThumbnail == that.transformThumbnail
                && Objects.equals(limits, that.limits);
    }

    @Override
    public int hashCode() {
        return Objects.hash(transform, crop, perfect, trim, grayscale, progressive, optimizeHuffman, markerCopy,
                restartInterval, resetOrientation, transformThumbnail, limits);
    }

    @Override
    public String toString() {
        return "JpegTransformOptions[" + "transform=" + transform + ", "
                + "crop=" + crop + ", "
                + "perfect=" + perfect + ", "
                + "trim=" + trim + ", "
                + "grayscale=" + grayscale + ", "
                + "progressive=" + progressive + ", "
                + "optimizeHuffman=" + optimizeHuffman + ", "
                + "markerCopy=" + markerCopy + ", "
                + "restartInterval=" + restartInterval + ", "
                + "resetOrientation=" + resetOrientation + ", "
                + "transformThumbnail=" + transformThumbnail + ", "
                + "limits=" + limits + "]";
    }

    /**
     * A builder of {@link JpegTransformOptions}.
     *
     * <p>Setters reject only {@code null}; {@link #build()} checks the ranges. Instances are not
     * thread-safe.
     */
    public static final class Builder {
        private Orientation transform = Orientation.NORMAL;

        private Region crop;

        private boolean perfect;

        private boolean trim = true;

        private boolean grayscale;

        private boolean progressive;

        private boolean optimizeHuffman = true;

        private JpegMarkerCopy markerCopy = JpegMarkerCopy.ALL;

        private int restartInterval;

        private boolean resetOrientation;

        private boolean transformThumbnail = true;

        private MediaLimits limits = MediaLimits.DEFAULT;

        private Builder() {
        }

        private Builder(JpegTransformOptions options) {
            this.transform = options.transform;
            this.crop = options.crop;
            this.perfect = options.perfect;
            this.trim = options.trim;
            this.grayscale = options.grayscale;
            this.progressive = options.progressive;
            this.optimizeHuffman = options.optimizeHuffman;
            this.markerCopy = options.markerCopy;
            this.restartInterval = options.restartInterval;
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
         * Sets the number of MCUs between restart markers; see
         * {@link JpegTransformOptions#restartInterval()}.
         *
         * @param restartInterval the value
         * @return this builder
         */
        public Builder restartInterval(int restartInterval) {
            this.restartInterval = restartInterval;
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
         * @throws IllegalArgumentException if the restart interval is not in [0, 65535]
         */
        public JpegTransformOptions build() {
            Checks.inRange("restart interval", restartInterval, 0, 0xFFFF);
            return new JpegTransformOptions(this);
        }
    }
}
