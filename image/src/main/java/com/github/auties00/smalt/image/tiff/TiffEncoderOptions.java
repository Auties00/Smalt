package com.github.auties00.smalt.image.tiff;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.color.ColorProfile;
import com.github.auties00.smalt.internal.check.Checks;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Holds the settings of a {@link TiffEncoder}. Instances are immutable and thread-safe; create them
 * with {@link #builder()} or a preset such as {@link #defaults()}.
 */
public final class TiffEncoderOptions {
    /**
     * The options of {@link #defaults()}.
     */
    private static final TiffEncoderOptions DEFAULTS = new Builder().build();

    /**
     * The options of {@link #uncompressed()}.
     */
    private static final TiffEncoderOptions UNCOMPRESSED = new Builder().compression(TiffCompression.NONE)
            .predictor(TiffPredictor.NONE).build();

    /**
     * The options of {@link #deflate()}.
     */
    private static final TiffEncoderOptions DEFLATE = new Builder().compression(TiffCompression.DEFLATE).build();

    private final TiffCompression compression;

    private final TiffPredictor predictor;

    private final int compressionLevel;

    private final Layout layout;

    private final Depth depth;

    /**
     * The rows per strip, or 0 to choose them (and for tiled output).
     */
    private final int rowsPerStrip;

    /**
     * The tile width, or 0 for strips.
     */
    private final int tileWidth;

    /**
     * The tile height, or 0 for strips.
     */
    private final int tileHeight;

    private final boolean bigEndian;

    private final boolean bigTiff;

    private final ColorProfile colorProfile;

    /**
     * The ICC bytes of {@link #colorProfile}, or {@code null}.
     */
    private final byte[] iccBytes;

    /**
     * Selects the samples written for each pixel.
     */
    public enum Layout {
        /**
         * Gray when every pixel is gray and RGB otherwise, with an alpha sample when some pixel
         * is not opaque.
         */
        AUTO,

        /**
         * One gray sample (black-is-zero), computed as the BT.601 luma of RGB pixels.
         */
        GRAY,

        /**
         * One gray sample and an unassociated alpha sample.
         */
        GRAY_ALPHA,

        /**
         * Red, green and blue samples.
         */
        RGB,

        /**
         * Red, green, blue and an unassociated alpha sample.
         */
        RGBA
    }

    /**
     * Selects the bits per sample.
     */
    public enum Depth {
        /**
         * The depth of the picture: 16 bits for 16-bit pictures, 8 bits otherwise.
         */
        AUTO,

        /**
         * 8 bits per sample; 16-bit pictures are reduced with exact rounding.
         */
        EIGHT,

        /**
         * 16 bits per sample; 8-bit pictures are widened exactly ({@code v * 257}).
         */
        SIXTEEN
    }

    private TiffEncoderOptions(Builder builder) {
        this.compression = builder.compression;
        this.predictor = builder.predictor;
        this.compressionLevel = builder.compressionLevel;
        this.layout = builder.layout;
        this.depth = builder.depth;
        this.rowsPerStrip = builder.rowsPerStrip;
        this.tileWidth = builder.tileWidth;
        this.tileHeight = builder.tileHeight;
        this.bigEndian = builder.bigEndian;
        this.bigTiff = builder.bigTiff;
        this.colorProfile = builder.colorProfile;
        this.iccBytes = builder.colorProfile == null ? null : builder.colorProfile.toByteArray();
    }

    /**
     * Returns LZW compression with the horizontal predictor, automatic layout and depth, strips
     * of about 64 KiB, little-endian classic TIFF: lossless, compact and readable by every TIFF
     * reader.
     *
     * @return the default options
     */
    public static TiffEncoderOptions defaults() {
        return DEFAULTS;
    }

    /**
     * Returns the {@link #defaults()} without compression, the fastest to write and read.
     *
     * @return uncompressed options
     */
    public static TiffEncoderOptions uncompressed() {
        return UNCOMPRESSED;
    }

    /**
     * Returns the {@link #defaults()} with Deflate at level 6 instead of LZW, usually smaller.
     *
     * @return Deflate options
     */
    public static TiffEncoderOptions deflate() {
        return DEFLATE;
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
     * {@return the compression scheme: {@link TiffCompression#NONE}, {@link TiffCompression#PACKBITS},
     * {@link TiffCompression#LZW} or {@link TiffCompression#DEFLATE}; {@code LZW} by default}
     *
     * <p>TIFF output is lossless, so the compression decides only the size and the compatibility of
     * the file.
     */
    public TiffCompression compression() {
        return compression;
    }

    /**
     * {@return the predictor, {@link TiffPredictor#NONE} or {@link TiffPredictor#HORIZONTAL},
     * applied only with LZW and Deflate; {@code HORIZONTAL} by default}
     */
    public TiffPredictor predictor() {
        return predictor;
    }

    /**
     * {@return the Deflate compression level, from 1 (fastest) to 9 (smallest); 6 by default}
     */
    public int compressionLevel() {
        return compressionLevel;
    }

    /**
     * {@return the sample layout; {@link Layout#AUTO} by default}
     */
    public Layout layout() {
        return layout;
    }

    /**
     * {@return the sample depth; {@link Depth#AUTO} by default}
     */
    public Depth depth() {
        return depth;
    }

    /**
     * {@return the rows per strip, at least 1, or empty to choose strips of about 64 KiB and for
     * tiled output; empty by default}
     */
    public OptionalInt rowsPerStrip() {
        return rowsPerStrip == 0 ? OptionalInt.empty() : OptionalInt.of(rowsPerStrip);
    }

    /**
     * {@return the tile width, a positive multiple of 16, or empty for output in strips; empty by
     * default}
     */
    public OptionalInt tileWidth() {
        return tileWidth == 0 ? OptionalInt.empty() : OptionalInt.of(tileWidth);
    }

    /**
     * {@return the tile height, a positive multiple of 16, or empty for output in strips; empty by
     * default}
     */
    public OptionalInt tileHeight() {
        return tileHeight == 0 ? OptionalInt.empty() : OptionalInt.of(tileHeight);
    }

    /**
     * {@return whether the file is written big-endian ({@code MM}) rather than little-endian
     * ({@code II}); {@code false} by default}
     */
    public boolean isBigEndian() {
        return bigEndian;
    }

    /**
     * {@return whether the file is written as a BigTIFF, whose 64-bit offsets allow files over
     * 4 GiB; {@code false} by default}
     */
    public boolean isBigTiff() {
        return bigTiff;
    }

    /**
     * {@return the ICC profile embedded in every page, or empty when none is embedded; empty by
     * default}
     *
     * <p>The profile tells readers which color space the pixels are in; it does not convert them.
     * Its ICC bytes are written unchanged. A high-dynamic-range profile, which has no ICC form, is
     * rejected.
     */
    public Optional<ColorProfile> colorProfile() {
        return Optional.ofNullable(colorProfile);
    }

    /**
     * Returns the ICC bytes of the color profile without copying them.
     *
     * @return the bytes, or {@code null}
     */
    byte[] iccProfileBytes() {
        return iccBytes;
    }

    /**
     * Returns the rows per strip.
     *
     * @return the configured rows, {@code 0} when automatic or tiled
     */
    int rawRowsPerStrip() {
        return rowsPerStrip;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof TiffEncoderOptions that
                && compression == that.compression && predictor == that.predictor
                && compressionLevel == that.compressionLevel && layout == that.layout && depth == that.depth
                && rowsPerStrip == that.rowsPerStrip && tileWidth == that.tileWidth && tileHeight == that.tileHeight
                && bigEndian == that.bigEndian && bigTiff == that.bigTiff
                && Objects.equals(colorProfile, that.colorProfile);
    }

    @Override
    public int hashCode() {
        return Objects.hash(compression, predictor, compressionLevel, layout, depth, rowsPerStrip, tileWidth, tileHeight,
                bigEndian, bigTiff, colorProfile);
    }

    @Override
    public String toString() {
        return "TiffEncoderOptions[compression=" + compression + ", predictor=" + predictor
                + ", compressionLevel=" + compressionLevel + ", layout=" + layout + ", depth=" + depth
                + (tileWidth > 0 ? ", tiles=" + tileWidth + "x" + tileHeight : ", rowsPerStrip=" + rowsPerStrip)
                + ", bigEndian=" + bigEndian + ", bigTiff=" + bigTiff
                + (colorProfile == null ? "" : ", colorProfile=" + colorProfile.description().orElse("unnamed"))
                + "]";
    }

    /**
     * A builder of {@link TiffEncoderOptions}.
     *
     * <p>Setters reject only {@code null}; {@link #build()} checks the ranges and the combinations
     * of settings. Strips and tiles exclude each other: setting one drops the other. Instances are
     * not thread-safe.
     */
    public static final class Builder {
        private TiffCompression compression = TiffCompression.LZW;

        private TiffPredictor predictor = TiffPredictor.HORIZONTAL;

        private int compressionLevel = 6;

        private Layout layout = Layout.AUTO;

        private Depth depth = Depth.AUTO;

        private int rowsPerStrip;

        private int tileWidth;

        private int tileHeight;

        private boolean bigEndian;

        private boolean bigTiff;

        private ColorProfile colorProfile;

        /**
         * Whether {@link #strips(int)} was called last of the strip and tile settings, so that
         * {@link #build()} checks the rows it set.
         */
        private boolean stripsSet;

        /**
         * Whether {@link #tiles(int, int)} was called last of the strip and tile settings, so that
         * {@link #build()} checks the size it set.
         */
        private boolean tilesSet;

        private Builder() {
        }

        private Builder(TiffEncoderOptions options) {
            this.compression = options.compression;
            this.predictor = options.predictor;
            this.compressionLevel = options.compressionLevel;
            this.layout = options.layout;
            this.depth = options.depth;
            this.rowsPerStrip = options.rowsPerStrip;
            this.tileWidth = options.tileWidth;
            this.tileHeight = options.tileHeight;
            this.bigEndian = options.bigEndian;
            this.bigTiff = options.bigTiff;
            this.colorProfile = options.colorProfile;
        }

        /**
         * Sets the compression scheme; see {@link TiffEncoderOptions#compression()}.
         *
         * @param compression the value
         * @return this builder
         */
        public Builder compression(TiffCompression compression) {
            this.compression = Objects.requireNonNull(compression, "compression");
            return this;
        }

        /**
         * Sets the predictor; see {@link TiffEncoderOptions#predictor()}.
         *
         * @param predictor the value
         * @return this builder
         */
        public Builder predictor(TiffPredictor predictor) {
            this.predictor = Objects.requireNonNull(predictor, "predictor");
            return this;
        }

        /**
         * Sets the Deflate level; see {@link TiffEncoderOptions#compressionLevel()}.
         *
         * @param compressionLevel the value
         * @return this builder
         */
        public Builder compressionLevel(int compressionLevel) {
            this.compressionLevel = compressionLevel;
            return this;
        }

        /**
         * Sets the sample layout; see {@link TiffEncoderOptions#layout()}.
         *
         * @param layout the value
         * @return this builder
         */
        public Builder layout(Layout layout) {
            this.layout = Objects.requireNonNull(layout, "layout");
            return this;
        }

        /**
         * Sets the sample depth; see {@link TiffEncoderOptions#depth()}.
         *
         * @param depth the value
         * @return this builder
         */
        public Builder depth(Depth depth) {
            this.depth = Objects.requireNonNull(depth, "depth");
            return this;
        }

        /**
         * Writes strips of a fixed number of rows instead of tiles; see
         * {@link TiffEncoderOptions#rowsPerStrip()}.
         *
         * @param rowsPerStrip the value
         * @return this builder
         */
        public Builder strips(int rowsPerStrip) {
            this.rowsPerStrip = rowsPerStrip;
            this.stripsSet = true;
            this.tileWidth = 0;
            this.tileHeight = 0;
            this.tilesSet = false;
            return this;
        }

        /**
         * Writes strips whose row count the encoder chooses, about 64 KiB each, instead of tiles;
         * see {@link TiffEncoderOptions#rowsPerStrip()}.
         *
         * @return this builder
         */
        public Builder automaticStrips() {
            this.rowsPerStrip = 0;
            this.stripsSet = false;
            this.tileWidth = 0;
            this.tileHeight = 0;
            this.tilesSet = false;
            return this;
        }

        /**
         * Writes tiles instead of strips; see {@link TiffEncoderOptions#tileWidth()} and
         * {@link TiffEncoderOptions#tileHeight()}.
         *
         * @param tileWidth the tile width
         * @param tileHeight the tile height
         * @return this builder
         */
        public Builder tiles(int tileWidth, int tileHeight) {
            this.tileWidth = tileWidth;
            this.tileHeight = tileHeight;
            this.tilesSet = true;
            this.rowsPerStrip = 0;
            this.stripsSet = false;
            return this;
        }

        /**
         * Sets the byte order; see {@link TiffEncoderOptions#isBigEndian()}.
         *
         * @param bigEndian the value
         * @return this builder
         */
        public Builder bigEndian(boolean bigEndian) {
            this.bigEndian = bigEndian;
            return this;
        }

        /**
         * Chooses BigTIFF or classic TIFF; see {@link TiffEncoderOptions#isBigTiff()}.
         *
         * @param bigTiff the value
         * @return this builder
         */
        public Builder bigTiff(boolean bigTiff) {
            this.bigTiff = bigTiff;
            return this;
        }

        /**
         * Sets the ICC profile embedded in every page; see {@link TiffEncoderOptions#colorProfile()}.
         *
         * @param colorProfile the value, or {@code null} for none
         * @return this builder
         */
        public Builder colorProfile(ColorProfile colorProfile) {
            this.colorProfile = colorProfile;
            return this;
        }

        /**
         * Returns the options holding the settings of this builder.
         *
         * @return the options
         * @throws IllegalArgumentException if the compression is not {@code NONE}, {@code PACKBITS},
         * {@code LZW} or {@code DEFLATE}, the predictor is {@code FLOATING_POINT}, the compression
         * level is not in [1, 9], the rows per strip are not positive, or a tile dimension is not a
         * positive multiple of 16
         * @throws MediaException.Unsupported if the color profile has no ICC form (a
         * high-dynamic-range profile)
         */
        public TiffEncoderOptions build() {
            switch (compression) {
                case NONE, PACKBITS, LZW, DEFLATE -> {
                }
                default -> throw new IllegalArgumentException("TIFF compression " + compression + " cannot be written");
            }
            if (predictor == TiffPredictor.FLOATING_POINT) {
                throw new IllegalArgumentException("the floating-point predictor needs floating-point samples");
            }
            Checks.inRange("compression level", compressionLevel, 1, 9);
            if (stripsSet) {
                Checks.positive("rows per strip", rowsPerStrip);
            }
            if (tilesSet) {
                Checks.positive("tile width", tileWidth);
                Checks.positive("tile height", tileHeight);
                if (tileWidth % 16 != 0 || tileHeight % 16 != 0) {
                    throw new IllegalArgumentException("tile size " + tileWidth + "x" + tileHeight
                            + " is not a multiple of 16");
                }
            }
            if (colorProfile != null && colorProfile.isHighDynamicRange()) {
                throw new MediaException.Unsupported("icc", "a high-dynamic-range profile has no ICC form");
            }
            return new TiffEncoderOptions(this);
        }
    }
}
