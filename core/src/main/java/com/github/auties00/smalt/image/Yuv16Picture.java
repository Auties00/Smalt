package com.github.auties00.smalt.image;

import java.util.Arrays;
import java.util.Objects;

/**
 * Holds a picture as planar Y'CbCr samples of 8 to 16 significant bits, stored in {@code short}
 * arrays, with an optional alpha plane.
 *
 * <p>The layout is that of {@link YuvPicture}: the luma plane is {@link #width()} by
 * {@link #height()} samples, the chroma planes are sized by {@link #chromaFormat()} and absent for
 * {@link ChromaFormat#MONOCHROME}, the optional alpha plane has the luma size and straight
 * semantics, and sample {@code (x, y)} of a plane is at index {@code y * stride + x}. Every sample,
 * alpha included, is an unsigned integer of {@link #bitDepth()} bits held in the low bits of its
 * {@code short}: read it as {@code s & 0xFFFF}, and it lies in {@code 0} to
 * {@code (1 << bitDepth()) - 1}. A 10-bit limited-range picture, for example, has black at luma
 * 64 and white at luma 940. Producers must keep samples in range; consumers are not required to
 * check them.
 *
 * <p>A picture is allocated with {@link #allocate} or wraps existing planes through
 * {@link #builder()}. Instances are not thread-safe; the planes are shared, not copied, and
 * threads that share a picture while one of them writes its samples must synchronize externally.
 */
public final class Yuv16Picture implements Picture {
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
     * The number of significant bits of every sample.
     */
    private final int bitDepth;

    /**
     * The mapping from samples to color.
     */
    private final ColorInfo color;

    /**
     * The luma plane.
     */
    private final short[] y;

    /**
     * The Cb plane, or {@code null} for {@link ChromaFormat#MONOCHROME}.
     */
    private final short[] u;

    /**
     * The Cr plane, or {@code null} for {@link ChromaFormat#MONOCHROME}.
     */
    private final short[] v;

    /**
     * The alpha plane, or {@code null} when the picture is opaque.
     */
    private final short[] alpha;

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
     * <p>Luma is filled with {@code 0}, chroma with {@code 1 << (bitDepth - 1)} (neutral gray hue)
     * and the alpha plane, when requested, with {@code (1 << bitDepth) - 1} (opaque).
     *
     * @param width the width in pixels; positive
     * @param height the height in pixels; positive
     * @param format the chroma subsampling layout
     * @param bitDepth the number of significant bits of every sample; 8 to 16
     * @param hasAlpha whether to allocate an alpha plane
     * @param color the mapping from samples to color
     * @return the allocated picture
     * @throws NullPointerException if {@code format} or {@code color} is {@code null}
     * @throws IllegalArgumentException if a dimension is not positive, the bit depth is outside 8
     * to 16, or a plane would exceed the largest Java array
     */
    public static Yuv16Picture allocate(int width, int height, ChromaFormat format, int bitDepth, boolean hasAlpha,
                                        ColorInfo color) {
        Objects.requireNonNull(format, "format");
        Objects.requireNonNull(color, "color");
        checkBitDepth(bitDepth);
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("invalid picture size " + width + "x" + height);
        }
        if ((long) width * height > Integer.MAX_VALUE - 16) {
            throw new IllegalArgumentException("picture size " + width + "x" + height + " is too large");
        }
        var y = new short[width * height];
        short[] u = null;
        short[] v = null;
        var chromaWidth = format.chromaWidth(width);
        if (format.hasChroma()) {
            var chromaSize = chromaWidth * format.chromaHeight(height);
            u = new short[chromaSize];
            v = new short[chromaSize];
            Arrays.fill(u, (short) (1 << (bitDepth - 1)));
            Arrays.fill(v, (short) (1 << (bitDepth - 1)));
        }
        short[] alpha = null;
        if (hasAlpha) {
            alpha = new short[width * height];
            Arrays.fill(alpha, (short) ((1 << bitDepth) - 1));
        }
        return new Yuv16Picture(width, height, format, bitDepth, color, y, width, u, v, chromaWidth, alpha, width);
    }

    /**
     * Returns a builder that wraps existing planes without copying them.
     *
     * <p>The size, the chroma format, the bit depth, the color description and the luma plane
     * must be set; the chroma planes must be set exactly when the format is not
     * {@link ChromaFormat#MONOCHROME}; the alpha plane is optional.
     *
     * @return the builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns a builder initialized with the size, layout, bit depth, color description and planes
     * of this picture, which the built picture shares.
     *
     * @return the builder
     */
    public Builder toBuilder() {
        var builder = new Builder();
        builder.width = width;
        builder.height = height;
        builder.format = format;
        builder.bitDepth = bitDepth;
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
     * @param width       the width in pixels
     * @param height      the height in pixels
     * @param format      the chroma subsampling layout
     * @param bitDepth    the number of significant bits
     * @param color       the mapping from samples to color
     * @param y           the luma plane
     * @param yStride     the luma row stride
     * @param u           the Cb plane, or {@code null}
     * @param v           the Cr plane, or {@code null}
     * @param uvStride    the chroma row stride
     * @param alpha       the alpha plane, or {@code null}
     * @param alphaStride the alpha row stride
     */
    private Yuv16Picture(int width, int height, ChromaFormat format, int bitDepth, ColorInfo color,
                         short[] y, int yStride, short[] u, short[] v, int uvStride,
                         short[] alpha, int alphaStride) {
        this.width = width;
        this.height = height;
        this.format = format;
        this.bitDepth = bitDepth;
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
     * Checks that a bit depth is supported.
     *
     * @param bitDepth the number of significant bits
     * @throws IllegalArgumentException if {@code bitDepth} is outside 8 to 16
     */
    private static void checkBitDepth(int bitDepth) {
        if (bitDepth < 8 || bitDepth > 16) {
            throw new IllegalArgumentException("unsupported bit depth " + bitDepth);
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
     * @return the declared depth, 8 to 16
     */
    @Override
    public int bitDepth() {
        return bitDepth;
    }

    /**
     * {@return the chroma subsampling layout, which sizes the chroma planes}
     */
    public ChromaFormat chromaFormat() {
        return format;
    }

    /**
     * Returns the mapping from samples to color.
     *
     * @return the color description
     */
    public ColorInfo color() {
        return color;
    }

    /**
     * Returns the luma plane, not a copy.
     *
     * @return the luma samples
     */
    public short[] y() {
        return y;
    }

    /**
     * Returns the Cb plane, not a copy.
     *
     * @return the Cb samples, or {@code null} for {@link ChromaFormat#MONOCHROME}
     */
    public short[] u() {
        return u;
    }

    /**
     * Returns the Cr plane, not a copy.
     *
     * @return the Cr samples, or {@code null} for {@link ChromaFormat#MONOCHROME}
     */
    public short[] v() {
        return v;
    }

    /**
     * Returns the alpha plane, not a copy.
     *
     * @return the alpha samples, or {@code null} when the picture is opaque
     */
    public short[] alpha() {
        return alpha;
    }

    /**
     * Returns the luma row stride.
     *
     * @return the stride in samples
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
     * Builds {@link Yuv16Picture} instances that wrap existing planes.
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
         * The bit depth, or {@code 0} until set.
         */
        private int bitDepth;

        /**
         * The color description, or {@code null} until set.
         */
        private ColorInfo color;

        /**
         * The luma plane, or {@code null} until set.
         */
        private short[] y;

        /**
         * The luma row stride.
         */
        private int yStride;

        /**
         * The Cb plane, or {@code null}.
         */
        private short[] u;

        /**
         * The Cr plane, or {@code null}.
         */
        private short[] v;

        /**
         * The chroma row stride.
         */
        private int uvStride;

        /**
         * The alpha plane, or {@code null}.
         */
        private short[] alpha;

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
         * @param width  the width in pixels; positive
         * @param height the height in pixels; positive
         * @return this builder
         */
        public Builder size(int width, int height) {
            this.width = width;
            this.height = height;
            return this;
        }

        /**
         * Sets the chroma subsampling layout; see {@link Yuv16Picture#chromaFormat()}.
         *
         * @param chromaFormat the value
         * @return this builder
         */
        public Builder chromaFormat(ChromaFormat chromaFormat) {
            this.format = Objects.requireNonNull(chromaFormat, "chromaFormat");
            return this;
        }

        /**
         * Sets the number of significant bits of every sample.
         *
         * @param bitDepth the depth; 8 to 16
         * @return this builder
         */
        public Builder bitDepth(int bitDepth) {
            this.bitDepth = bitDepth;
            return this;
        }

        /**
         * Sets the mapping from samples to color.
         *
         * @param color the color description
         * @return this builder
         * @throws NullPointerException if {@code color} is {@code null}
         */
        public Builder color(ColorInfo color) {
            this.color = Objects.requireNonNull(color, "color");
            return this;
        }

        /**
         * Sets the luma plane.
         *
         * @param plane  the samples, shared and not copied
         * @param stride the row stride; at least the width
         * @return this builder
         * @throws NullPointerException if {@code plane} is {@code null}
         */
        public Builder luma(short[] plane, int stride) {
            this.y = Objects.requireNonNull(plane, "plane");
            this.yStride = stride;
            return this;
        }

        /**
         * Sets the two chroma planes.
         *
         * @param u      the Cb samples, shared and not copied, or {@code null} for a monochrome
         *               picture
         * @param v      the Cr samples, shared and not copied, or {@code null} for a monochrome
         *               picture
         * @param stride the row stride of both planes; at least the chroma width
         * @return this builder
         */
        public Builder chroma(short[] u, short[] v, int stride) {
            this.u = u;
            this.v = v;
            this.uvStride = stride;
            return this;
        }

        /**
         * Sets the alpha plane.
         *
         * @param plane  the straight alpha samples of the picture's bit depth, shared and not
         *               copied, or {@code null} for an opaque picture
         * @param stride the row stride; at least the width, ignored without a plane
         * @return this builder
         */
        public Builder alpha(short[] plane, int stride) {
            this.alpha = plane;
            this.alphaStride = stride;
            return this;
        }

        /**
         * Builds the picture.
         *
         * @return the picture, sharing the planes
         * @throws NullPointerException     if the format, the color description, the luma plane
         *                                  or a required chroma plane is not set
         * @throws IllegalArgumentException if a dimension is not positive, the bit depth is outside
         *                                  8 to 16, a stride is too small, a plane is too short,
         *                                  or chroma planes are set for
         *                                  {@link ChromaFormat#MONOCHROME}
         */
        public Yuv16Picture build() {
            Objects.requireNonNull(format, "format");
            Objects.requireNonNull(color, "color");
            Objects.requireNonNull(y, "y");
            checkBitDepth(bitDepth);
            if (width <= 0 || height <= 0) {
                throw new IllegalArgumentException("invalid picture size " + width + "x" + height);
            }
            YuvPicture.checkPlane("luma", y.length, yStride, width, height);
            if (format.hasChroma()) {
                Objects.requireNonNull(u, "u");
                Objects.requireNonNull(v, "v");
                var chromaWidth = format.chromaWidth(width);
                var chromaHeight = format.chromaHeight(height);
                YuvPicture.checkPlane("cb", u.length, uvStride, chromaWidth, chromaHeight);
                YuvPicture.checkPlane("cr", v.length, uvStride, chromaWidth, chromaHeight);
            } else if (u != null || v != null) {
                throw new IllegalArgumentException("a monochrome picture has no chroma planes");
            }
            if (alpha != null) {
                YuvPicture.checkPlane("alpha", alpha.length, alphaStride, width, height);
            }
            return new Yuv16Picture(width, height, format, bitDepth, color, y, yStride, u, v, uvStride, alpha,
                    alphaStride);
        }
    }
}
