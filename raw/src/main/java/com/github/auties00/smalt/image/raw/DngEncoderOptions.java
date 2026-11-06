package com.github.auties00.smalt.image.raw;

import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.image.exif.ExifData;
import com.github.auties00.smalt.internal.check.Checks;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;

/**
 * Holds the settings of a {@link DngEncoder}. Instances are immutable and thread-safe; create them with
 * {@link #builder()} or a preset such as {@link #defaults()}.
 */
public final class DngEncoderOptions {
    /**
     * The default tile size, in pixels.
     */
    public static final int DEFAULT_TILE_SIZE = 256;

    /**
     * The default longest side of the JPEG preview, in pixels.
     */
    public static final int DEFAULT_PREVIEW_SIZE = 1024;

    /**
     * The default quality of the JPEG preview.
     */
    public static final int DEFAULT_PREVIEW_QUALITY = 90;

    private static final DngEncoderOptions DEFAULTS = builder().build();

    private final DngCompression compression;
    private final int tileSize;
    private final boolean preview;
    private final int previewSize;
    private final int previewQuality;
    private final String make;
    private final String model;
    private final String uniqueCameraModel;
    private final Orientation orientation;
    private final ExifData exif;
    private final Double baselineExposure;
    private final Double pixelAspectRatio;

    private DngEncoderOptions(Builder builder) {
        this.compression = builder.compression;
        this.tileSize = builder.tileSize;
        this.preview = builder.preview;
        this.previewSize = builder.fullSizePreview ? 0 : builder.previewSize;
        this.previewQuality = builder.previewQuality;
        this.make = builder.make;
        this.model = builder.model;
        this.uniqueCameraModel = builder.uniqueCameraModel;
        this.orientation = builder.orientation;
        this.exif = builder.exif;
        this.baselineExposure = builder.baselineExposure;
        this.pixelAspectRatio = builder.pixelAspectRatio;
    }

    /**
     * Returns the default settings: lossless JPEG tiles of {@value #DEFAULT_TILE_SIZE} pixels, a JPEG preview
     * of {@value #DEFAULT_PREVIEW_SIZE} pixels on its longest side with a thumbnail, and the descriptive
     * metadata of the source.
     *
     * @return the default options
     */
    public static DngEncoderOptions defaults() {
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
        var builder = new Builder();
        builder.compression = compression;
        builder.tileSize = tileSize;
        builder.preview = preview;
        builder.previewSize = previewSize == 0 ? DEFAULT_PREVIEW_SIZE : previewSize;
        builder.fullSizePreview = previewSize == 0;
        builder.previewQuality = previewQuality;
        builder.make = make;
        builder.model = model;
        builder.uniqueCameraModel = uniqueCameraModel;
        builder.orientation = orientation;
        builder.exif = exif;
        builder.baselineExposure = baselineExposure;
        builder.pixelAspectRatio = pixelAspectRatio;
        return builder;
    }

    /**
     * {@return the compression of the raw image data; {@link DngCompression#LOSSLESS_JPEG} by default}
     */
    public DngCompression compression() {
        return compression;
    }

    /**
     * {@return the width and height of the tiles the raw image data is stored in, a multiple of 16 from 16 to
     * 4096; {@value #DEFAULT_TILE_SIZE} by default}
     *
     * <p>Tiles are coded in parallel; smaller tiles parallelize better and compress slightly worse.
     */
    public int tileSize() {
        return tileSize;
    }

    /**
     * {@return whether the file holds a rendered preview: a baseline JPEG of the image, in sRGB, and an
     * uncompressed thumbnail of at most 256 pixels on its longest side; {@code true} by default}
     *
     * <p>Without a preview the raw image is the file's first image.
     */
    public boolean preview() {
        return preview;
    }

    /**
     * {@return the longest side of the JPEG preview in pixels, from 256 to 65535, or empty for a preview of the
     * size of the full rendering; {@value #DEFAULT_PREVIEW_SIZE} by default}
     *
     * <p>A preview is never larger than the full rendering.
     */
    public OptionalInt previewSize() {
        return previewSize == 0 ? OptionalInt.empty() : OptionalInt.of(previewSize);
    }

    /**
     * {@return the quality of the JPEG preview, from 1 to 100, as {@link
     * com.github.auties00.smalt.image.jpeg.JpegEncoderOptions#quality()} defines it; {@value
     * #DEFAULT_PREVIEW_QUALITY} by default}
     */
    public int previewQuality() {
        return previewQuality;
    }

    /**
     * {@return the camera maker, written as the TIFF {@code Make} tag, or empty for the source's maker (none for
     * an image encoded on its own); empty by default}
     */
    public Optional<String> make() {
        return Optional.ofNullable(make);
    }

    /**
     * {@return the camera model, written as the TIFF {@code Model} tag, or empty for the source's model (none
     * for an image encoded on its own); empty by default}
     */
    public Optional<String> model() {
        return Optional.ofNullable(model);
    }

    /**
     * {@return the unique camera model the DNG {@code UniqueCameraModel} tag names, or empty for the source's
     * one: a source DNG file's own, else the maker and model, else {@code "Smalt"}; empty by default}
     */
    public Optional<String> uniqueCameraModel() {
        return Optional.ofNullable(uniqueCameraModel);
    }

    /**
     * {@return the rotation that displays the stored image upright, or empty for the source's orientation
     * ({@link Orientation#NORMAL} for an image encoded on its own); empty by default}
     */
    public Optional<Orientation> orientation() {
        return Optional.ofNullable(orientation);
    }

    /**
     * {@return the EXIF metadata written into the file, or empty for the source's EXIF metadata (none for an
     * image encoded on its own); empty by default}
     *
     * <p>{@link ExifData#EMPTY} writes none. The descriptive entries of its first directory, its Exif,
     * interoperability and GPS directories are written; its orientation and thumbnail are not.
     */
    public Optional<ExifData> exif() {
        return Optional.ofNullable(exif);
    }

    /**
     * {@return the exposure, in stops, that a converter adds to render the image at its intended brightness,
     * written as the DNG {@code BaselineExposure} tag, or empty for the source's (0 for an image encoded on its
     * own); empty by default}
     */
    public OptionalDouble baselineExposure() {
        return baselineExposure == null ? OptionalDouble.empty() : OptionalDouble.of(baselineExposure);
    }

    /**
     * {@return the width of a stored pixel divided by its height, written as the DNG {@code DefaultScale} tag,
     * or empty for the source's scale (square pixels for an image encoded on its own); empty by default}
     */
    public OptionalDouble pixelAspectRatio() {
        return pixelAspectRatio == null ? OptionalDouble.empty() : OptionalDouble.of(pixelAspectRatio);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof DngEncoderOptions that && compression == that.compression
                && tileSize == that.tileSize && preview == that.preview && previewSize == that.previewSize
                && previewQuality == that.previewQuality && Objects.equals(make, that.make)
                && Objects.equals(model, that.model) && Objects.equals(uniqueCameraModel, that.uniqueCameraModel)
                && orientation == that.orientation && Objects.equals(exif, that.exif)
                && Objects.equals(baselineExposure, that.baselineExposure)
                && Objects.equals(pixelAspectRatio, that.pixelAspectRatio);
    }

    @Override
    public int hashCode() {
        return Objects.hash(compression, tileSize, preview, previewSize, previewQuality, make, model,
                uniqueCameraModel, orientation, exif, baselineExposure, pixelAspectRatio);
    }

    @Override
    public String toString() {
        return "DngEncoderOptions[compression=" + compression + ", tileSize=" + tileSize + ", preview=" + preview
                + ", previewSize=" + (previewSize == 0 ? "full" : previewSize) + ", previewQuality=" + previewQuality
                + (make != null ? ", make=" + make : "") + (model != null ? ", model=" + model : "")
                + (uniqueCameraModel != null ? ", uniqueCameraModel=" + uniqueCameraModel : "")
                + (orientation != null ? ", orientation=" + orientation : "")
                + (exif != null ? ", exif=" + exif : "")
                + (baselineExposure != null ? ", baselineExposure=" + baselineExposure : "")
                + (pixelAspectRatio != null ? ", pixelAspectRatio=" + pixelAspectRatio : "")
                + "]";
    }

    /**
     * Builds {@link DngEncoderOptions}.
     *
     * <p>Setters store their value; {@link #build()} checks the ranges. Instances are not thread-safe.
     */
    public static final class Builder {
        private DngCompression compression = DngCompression.LOSSLESS_JPEG;
        private int tileSize = DEFAULT_TILE_SIZE;
        private boolean preview = true;
        private int previewSize = DEFAULT_PREVIEW_SIZE;
        private boolean fullSizePreview;
        private int previewQuality = DEFAULT_PREVIEW_QUALITY;
        private String make;
        private String model;
        private String uniqueCameraModel;
        private Orientation orientation;
        private ExifData exif;
        private Double baselineExposure;
        private Double pixelAspectRatio;

        private Builder() {
        }

        /**
         * Sets the compression; see {@link DngEncoderOptions#compression()}.
         *
         * @param compression the value
         * @return this builder
         */
        public Builder compression(DngCompression compression) {
            this.compression = Objects.requireNonNull(compression, "compression");
            return this;
        }

        /**
         * Sets the tile size; see {@link DngEncoderOptions#tileSize()}.
         *
         * @param tileSize the value
         * @return this builder
         */
        public Builder tileSize(int tileSize) {
            this.tileSize = tileSize;
            return this;
        }

        /**
         * Sets whether the file holds a preview; see {@link DngEncoderOptions#preview()}.
         *
         * @param preview the value
         * @return this builder
         */
        public Builder preview(boolean preview) {
            this.preview = preview;
            return this;
        }

        /**
         * Sets the longest side of the JPEG preview; see {@link DngEncoderOptions#previewSize()}.
         *
         * @param previewSize the value
         * @return this builder
         */
        public Builder previewSize(int previewSize) {
            this.previewSize = previewSize;
            this.fullSizePreview = false;
            return this;
        }

        /**
         * Makes the JPEG preview as large as the full rendering; see {@link DngEncoderOptions#previewSize()}.
         *
         * @return this builder
         */
        public Builder fullSizePreview() {
            this.fullSizePreview = true;
            return this;
        }

        /**
         * Sets the quality of the JPEG preview; see {@link DngEncoderOptions#previewQuality()}.
         *
         * @param previewQuality the value
         * @return this builder
         */
        public Builder previewQuality(int previewQuality) {
            this.previewQuality = previewQuality;
            return this;
        }

        /**
         * Sets the camera maker; see {@link DngEncoderOptions#make()}.
         *
         * @param make the value
         * @return this builder
         */
        public Builder make(String make) {
            this.make = Objects.requireNonNull(make, "make");
            return this;
        }

        /**
         * Takes the camera maker from the source; see {@link DngEncoderOptions#make()}.
         *
         * @return this builder
         */
        public Builder automaticMake() {
            this.make = null;
            return this;
        }

        /**
         * Sets the camera model; see {@link DngEncoderOptions#model()}.
         *
         * @param model the value
         * @return this builder
         */
        public Builder model(String model) {
            this.model = Objects.requireNonNull(model, "model");
            return this;
        }

        /**
         * Takes the camera model from the source; see {@link DngEncoderOptions#model()}.
         *
         * @return this builder
         */
        public Builder automaticModel() {
            this.model = null;
            return this;
        }

        /**
         * Sets the unique camera model; see {@link DngEncoderOptions#uniqueCameraModel()}.
         *
         * @param uniqueCameraModel the value
         * @return this builder
         */
        public Builder uniqueCameraModel(String uniqueCameraModel) {
            this.uniqueCameraModel = Objects.requireNonNull(uniqueCameraModel, "uniqueCameraModel");
            return this;
        }

        /**
         * Takes the unique camera model from the source; see {@link DngEncoderOptions#uniqueCameraModel()}.
         *
         * @return this builder
         */
        public Builder automaticUniqueCameraModel() {
            this.uniqueCameraModel = null;
            return this;
        }

        /**
         * Sets the orientation; see {@link DngEncoderOptions#orientation()}.
         *
         * @param orientation the value
         * @return this builder
         */
        public Builder orientation(Orientation orientation) {
            this.orientation = Objects.requireNonNull(orientation, "orientation");
            return this;
        }

        /**
         * Takes the orientation from the source; see {@link DngEncoderOptions#orientation()}.
         *
         * @return this builder
         */
        public Builder automaticOrientation() {
            this.orientation = null;
            return this;
        }

        /**
         * Sets the EXIF metadata; see {@link DngEncoderOptions#exif()}.
         *
         * @param exif the value
         * @return this builder
         */
        public Builder exif(ExifData exif) {
            this.exif = Objects.requireNonNull(exif, "exif");
            return this;
        }

        /**
         * Writes no EXIF metadata, the same as {@code exif(ExifData.EMPTY)}; see {@link DngEncoderOptions#exif()}.
         *
         * @return this builder
         */
        public Builder noExif() {
            this.exif = ExifData.EMPTY;
            return this;
        }

        /**
         * Takes the EXIF metadata from the source; see {@link DngEncoderOptions#exif()}.
         *
         * @return this builder
         */
        public Builder automaticExif() {
            this.exif = null;
            return this;
        }

        /**
         * Sets the baseline exposure; see {@link DngEncoderOptions#baselineExposure()}.
         *
         * @param baselineExposure the value
         * @return this builder
         */
        public Builder baselineExposure(double baselineExposure) {
            this.baselineExposure = baselineExposure;
            return this;
        }

        /**
         * Takes the baseline exposure from the source; see {@link DngEncoderOptions#baselineExposure()}.
         *
         * @return this builder
         */
        public Builder automaticBaselineExposure() {
            this.baselineExposure = null;
            return this;
        }

        /**
         * Sets the pixel aspect ratio; see {@link DngEncoderOptions#pixelAspectRatio()}.
         *
         * @param pixelAspectRatio the value
         * @return this builder
         */
        public Builder pixelAspectRatio(double pixelAspectRatio) {
            this.pixelAspectRatio = pixelAspectRatio;
            return this;
        }

        /**
         * Takes the pixel aspect ratio from the source; see {@link DngEncoderOptions#pixelAspectRatio()}.
         *
         * @return this builder
         */
        public Builder automaticPixelAspectRatio() {
            this.pixelAspectRatio = null;
            return this;
        }

        /**
         * Returns options holding the settings of this builder.
         *
         * @return the options
         * @throws IllegalArgumentException if the tile size is not a multiple of 16 from 16 to 4096, the preview
         * size is not from 256 to 65535, the preview quality is not from 1 to 100, the baseline exposure is not
         * from -10 to 10 stops, or the pixel aspect ratio is not from 0.1 to 10
         */
        public DngEncoderOptions build() {
            Checks.inRange("tile size", tileSize, 16, 4096);
            if (tileSize % 16 != 0) {
                throw new IllegalArgumentException("tile size " + tileSize + " is not a multiple of 16");
            }
            if (!fullSizePreview) {
                Checks.inRange("preview size", previewSize, 256, 65535);
            }
            Checks.inRange("preview quality", previewQuality, 1, 100);
            if (baselineExposure != null) {
                Checks.inRange("baseline exposure", baselineExposure, -10, 10);
            }
            if (pixelAspectRatio != null) {
                Checks.inRange("pixel aspect ratio", pixelAspectRatio, 0.1, 10);
            }
            return new DngEncoderOptions(this);
        }
    }
}
