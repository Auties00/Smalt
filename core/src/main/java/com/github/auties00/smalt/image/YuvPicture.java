package com.github.auties00.smalt.image;

import java.util.Arrays;
import java.util.Objects;

/**
 * Holds a picture as planar 8-bit Y'CbCr samples with an optional alpha plane.
 *
 * <p>The luma plane is {@link #width()} by {@link #height()} samples; the two chroma planes are
 * sized by {@link #chromaFormat()} (see {@link ChromaFormat#chromaWidth(int)}) and absent for
 * {@link ChromaFormat#MONOCHROME}. The optional alpha plane has the luma size and straight
 * (non-premultiplied) semantics. Sample {@code (x, y)} of a plane is at index
 * {@code y * stride + x}. How samples map to color is described by {@link #color()}.
 *
 * <p>A picture is allocated with {@link #allocate} or wraps existing planes through
 * {@link #builder()}; {@link #toBuilder()} derives a picture sharing the planes of another, for
 * example with another color description or without the alpha plane. Instances are not
 * thread-safe; the planes are shared, not copied, and threads that share a picture while one of
 * them writes its samples must synchronize externally.
 */
public final class YuvPicture implements Picture {
    /**
     * The width in pixels.
     */
    private final int width;

    /**
     * The height in pixels.
     */
    private final int height;

    /**
     * The chroma subsampling layout.
     */
    private final ChromaFormat format;

    /**
     * The mapping from samples to color.
     */
    private final ColorInfo color;

    /**
     * The luma plane.
     */
    private final byte[] y;

    /**
     * The Cb plane, or {@code null} for {@link ChromaFormat#MONOCHROME}.
     */
    private final byte[] u;

    /**
     * The Cr plane, or {@code null} for {@link ChromaFormat#MONOCHROME}.
     */
    private final byte[] v;

    /**
     * The alpha plane, or {@code null} when the picture is opaque.
     */
    private final byte[] alpha;

    /**
     * The row stride of the luma plane.
     */
    private final int yStride;

    /**
     * The row stride shared by both chroma planes.
     */
    private final int uvStride;

    /**
     * The row stride of the alpha plane.
     */
    private final int alphaStride;

    /**
     * Allocates a picture with tight strides.
     *
     * <p>Luma is filled with {@code 0} and chroma with {@code 128} (neutral gray hue); the alpha
     * plane, when requested, is filled with {@code 255} (opaque).
     *
     * @param width the width in pixels; positive
     * @param height the height in pixels; positive
     * @param format the chroma subsampling layout
     * @param hasAlpha whether to allocate an alpha plane
     * @param color the mapping from samples to color
     * @return the allocated picture
     * @throws IllegalArgumentException if a dimension is not positive or a plane would exceed the largest Java array
     */
    public static YuvPicture allocate(int width, int height, ChromaFormat format, boolean hasAlpha, ColorInfo color) {
        Objects.requireNonNull(format, "format");
        Objects.requireNonNull(color, "color");
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("invalid picture size " + width + "x" + height);
        }
        if ((long) width * height > Integer.MAX_VALUE - 16) {
            throw new IllegalArgumentException("picture size " + width + "x" + height + " is too large");
        }
        var y = new byte[width * height];
        byte[] u = null;
        byte[] v = null;
        var chromaWidth = format.chromaWidth(width);
        if (format.hasChroma()) {
            var chromaSize = chromaWidth * format.chromaHeight(height);
            u = new byte[chromaSize];
            v = new byte[chromaSize];
            Arrays.fill(u, (byte) 128);
            Arrays.fill(v, (byte) 128);
        }
        byte[] alpha = null;
        if (hasAlpha) {
            alpha = new byte[width * height];
            Arrays.fill(alpha, (byte) 255);
        }
        return new YuvPicture(width, height, format, color, y, width, u, v, chromaWidth, alpha, width);
    }

    /**
     * Returns a builder that wraps existing planes without copying them.
     *
     * <p>The size, the chroma format, the color description and the luma plane must be set; the
     * chroma planes must be set exactly when the format is not {@link ChromaFormat#MONOCHROME};
     * the alpha plane is optional.
     *
     * @return the builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns a builder initialized with the size, layout, color description and planes of this
     * picture, which the built picture shares.
     *
     * @return the builder
     */
    public Builder toBuilder() {
        var builder = new Builder();
        builder.width = width;
        builder.height = height;
        builder.format = format;
        builder.color = color;
        builder.y = y;
        builder.yStride = yStride;
        builder.u = u;
        builder.v = v;
        builder.uvStride = uvStride;
        builder.alpha = alpha;
        builder.alphaStride = alphaStride;
        return builder;
    }

    /**
     * Constructs a picture from validated planes.
     *
     * @param width the width in pixels
     * @param height the height in pixels
     * @param format the chroma subsampling layout
     * @param color the mapping from samples to color
     * @param y the luma plane
     * @param yStride the luma row stride
     * @param u the Cb plane, or {@code null}
     * @param v the Cr plane, or {@code null}
     * @param uvStride the chroma row stride
     * @param alpha the alpha plane, or {@code null}
     * @param alphaStride the alpha row stride
     */
    private YuvPicture(int width, int height, ChromaFormat format, ColorInfo color,
                       byte[] y, int yStride, byte[] u, byte[] v, int uvStride,
                       byte[] alpha, int alphaStride) {
        this.width = width;
        this.height = height;
        this.format = format;
        this.color = color;
        this.y = y;
        this.u = u;
        this.v = v;
        this.alpha = alpha;
        this.yStride = yStride;
        this.uvStride = format.hasChroma() ? uvStride : 0;
        this.alphaStride = alpha != null ? alphaStride : 0;
    }

    /**
     * Checks that a plane array can hold the given sample grid.
     *
     * @param name the plane name used in error messages
     * @param length the plane array length
     * @param stride the row stride
     * @param width the plane width in samples
     * @param height the plane height in samples
     * @throws IllegalArgumentException if the stride is smaller than the width or the array is too short
     */
    static void checkPlane(String name, int length, int stride, int width, int height) {
        if (stride < width) {
            throw new IllegalArgumentException(name + " stride " + stride + " is smaller than " + width);
        }
        if (length < (long) stride * (height - 1) + width) {
            throw new IllegalArgumentException(name + " plane of " + length + " samples is too short");
        }
    }

    @Override
    public int width() {
        return width;
    }

    @Override
    public int height() {
        return height;
    }

    @Override
    public boolean hasAlpha() {
        return alpha != null;
    }

    /**
     * {@inheritDoc}
     *
     * @return {@code 8}
     */
    @Override
    public int bitDepth() {
        return 8;
    }

    /**
     * {@return the chroma subsampling layout, which sizes the chroma planes}
     */
    public ChromaFormat chromaFormat() {
        return format;
    }

    /**
     * {@return the mapping from samples to color}
     */
    public ColorInfo color() {
        return color;
    }

    /**
     * Returns the luma plane, not a copy.
     *
     * @return the luma samples
     */
    public byte[] y() {
        return y;
    }

    /**
     * Returns the Cb plane, not a copy.
     *
     * @return the Cb samples, or {@code null} for {@link ChromaFormat#MONOCHROME}
     */
    public byte[] u() {
        return u;
    }

    /**
     * Returns the Cr plane, not a copy.
     *
     * @return the Cr samples, or {@code null} for {@link ChromaFormat#MONOCHROME}
     */
    public byte[] v() {
        return v;
    }

    /**
     * Returns the alpha plane, not a copy.
     *
     * @return the alpha samples, or {@code null} when the picture is opaque
     */
    public byte[] alpha() {
        return alpha;
    }

    /**
     * {@return the luma row stride, in samples}
     */
    public int yStride() {
        return yStride;
    }

    /**
     * Returns the row stride shared by both chroma planes.
     *
     * @return the stride in samples, or {@code 0} for {@link ChromaFormat#MONOCHROME}
     */
    public int uvStride() {
        return uvStride;
    }

    /**
     * Returns the alpha row stride.
     *
     * @return the stride in samples, or {@code 0} when the picture is opaque
     */
    public int alphaStride() {
        return alphaStride;
    }

    /**
     * Returns the width of each chroma plane.
     *
     * @return the chroma width, or {@code 0} for {@link ChromaFormat#MONOCHROME}
     */
    public int chromaWidth() {
        return format.chromaWidth(width);
    }

    /**
     * Returns the height of each chroma plane.
     *
     * @return the chroma height, or {@code 0} for {@link ChromaFormat#MONOCHROME}
     */
    public int chromaHeight() {
        return format.chromaHeight(height);
    }

    /**
     * Builds {@link YuvPicture} instances that wrap existing planes.
     *
     * <p>Instances are not thread-safe; confine a builder to one thread.
     */
    public static final class Builder {
        /**
         * The width, or {@code 0} until set.
         */
        private int width;

        /**
         * The height, or {@code 0} until set.
         */
        private int height;

        /**
         * The chroma layout, or {@code null} until set.
         */
        private ChromaFormat format;

        /**
         * The color description, or {@code null} until set.
         */
        private ColorInfo color;

        /**
         * The luma plane, or {@code null} until set.
         */
        private byte[] y;

        /**
         * The luma row stride.
         */
        private int yStride;

        /**
         * The Cb plane, or {@code null}.
         */
        private byte[] u;

        /**
         * The Cr plane, or {@code null}.
         */
        private byte[] v;

        /**
         * The chroma row stride.
         */
        private int uvStride;

        /**
         * The alpha plane, or {@code null}.
         */
        private byte[] alpha;

        /**
         * The alpha row stride.
         */
        private int alphaStride;

        /**
         * Constructs an empty builder.
         */
        private Builder() {
        }

        /**
         * Sets the size of the picture.
         *
         * @param width the width in pixels; positive
         * @param height the height in pixels; positive
         * @return this builder
         */
        public Builder size(int width, int height) {
            this.width = width;
            this.height = height;
            return this;
        }

        /**
         * Sets the chroma subsampling layout; see {@link YuvPicture#chromaFormat()}.
         *
         * @param chromaFormat the value
         * @return this builder
         */
        public Builder chromaFormat(ChromaFormat chromaFormat) {
            this.format = Objects.requireNonNull(chromaFormat, "chromaFormat");
            return this;
        }

        /**
         * Sets the mapping from samples to color; see {@link YuvPicture#color()}.
         *
         * @param color the value
         * @return this builder
         */
        public Builder color(ColorInfo color) {
            this.color = Objects.requireNonNull(color, "color");
            return this;
        }

        /**
         * Sets the luma plane.
         *
         * @param plane the samples, shared and not copied
         * @param stride the row stride; at least the width
         * @return this builder
         */
        public Builder luma(byte[] plane, int stride) {
            this.y = Objects.requireNonNull(plane, "plane");
            this.yStride = stride;
            return this;
        }

        /**
         * Sets the two chroma planes.
         *
         * @param u the Cb samples, shared and not copied, or {@code null} for a monochrome picture
         * @param v the Cr samples, shared and not copied, or {@code null} for a monochrome picture
         * @param stride the row stride of both planes; at least the chroma width
         * @return this builder
         */
        public Builder chroma(byte[] u, byte[] v, int stride) {
            this.u = u;
            this.v = v;
            this.uvStride = stride;
            return this;
        }

        /**
         * Sets the alpha plane.
         *
         * @param plane the straight alpha samples, shared and not copied, or {@code null} for an opaque picture
         * @param stride the row stride; at least the width, ignored without a plane
         * @return this builder
         */
        public Builder alpha(byte[] plane, int stride) {
            this.alpha = plane;
            this.alphaStride = stride;
            return this;
        }

        /**
         * Builds the picture.
         *
         * @return the picture, sharing the planes
         * @throws NullPointerException if the format, the color description, the luma plane or a required chroma plane
         * is not set
         * @throws IllegalArgumentException if a dimension is not positive, a stride is too small, a plane is too short,
         * or chroma planes are set for {@link ChromaFormat#MONOCHROME}
         */
        public YuvPicture build() {
            Objects.requireNonNull(format, "format");
            Objects.requireNonNull(color, "color");
            Objects.requireNonNull(y, "y");
            if (width <= 0 || height <= 0) {
                throw new IllegalArgumentException("invalid picture size " + width + "x" + height);
            }
            checkPlane("luma", y.length, yStride, width, height);
            if (format.hasChroma()) {
                Objects.requireNonNull(u, "u");
                Objects.requireNonNull(v, "v");
                var chromaWidth = format.chromaWidth(width);
                var chromaHeight = format.chromaHeight(height);
                checkPlane("cb", u.length, uvStride, chromaWidth, chromaHeight);
                checkPlane("cr", v.length, uvStride, chromaWidth, chromaHeight);
            } else if (u != null || v != null) {
                throw new IllegalArgumentException("a monochrome picture has no chroma planes");
            }
            if (alpha != null) {
                checkPlane("alpha", alpha.length, alphaStride, width, height);
            }
            return new YuvPicture(width, height, format, color, y, yStride, u, v, uvStride, alpha, alphaStride);
        }
    }
}
