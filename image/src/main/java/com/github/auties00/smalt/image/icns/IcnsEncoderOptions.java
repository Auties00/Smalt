package com.github.auties00.smalt.image.icns;

import java.util.Arrays;
import java.util.Objects;

/**
 * Holds the settings of an {@link IcnsEncoder}. Instances are immutable and thread-safe; create them with
 * {@link #builder()} or a preset such as {@link #defaults()}.
 */
public final class IcnsEncoderOptions {
    /**
     * The image sizes an icon family can hold, in pixels.
     */
    static final int[] SIZES = {16, 18, 24, 32, 36, 48, 64, 128, 256, 512, 1024};

    /**
     * The options of {@link #defaults()}.
     */
    private static final IcnsEncoderOptions DEFAULTS = new Builder().build();

    /**
     * The options of {@link #macSizes()}.
     */
    private static final IcnsEncoderOptions MAC_SIZES = new Builder().sizes(16, 32, 64, 128, 256, 512, 1024).build();

    private final int[] sizes;

    private final Encoding encoding;

    /**
     * Selects the element types images are stored in.
     */
    public enum Encoding {
        /**
         * The types macOS writes: ARGB images for 16 and 32 pixels ({@code ic04}, {@code ic05}), a 24-bit image
         * with an 8-bit mask for 48 pixels ({@code ih32}, {@code h8mk}), and PNG images for the other sizes.
         */
        AUTO,

        /**
         * PNG images for every size: {@code icp4}, {@code icp5} and {@code icp6} for 16, 32 and 48 pixels.
         */
        PNG,

        /**
         * The classic 24-bit images with 8-bit masks for the sizes that have them ({@code is32}, {@code il32},
         * {@code ih32}, {@code it32} for 16, 32, 48 and 128 pixels), readable by every ICNS reader, and PNG images
         * for the other sizes.
         */
        RLE
    }

    private IcnsEncoderOptions(Builder builder) {
        this.sizes = builder.sizes.clone();
        this.encoding = builder.encoding;
    }

    /**
     * Returns options writing each picture as one image of its own size, stored as {@link Encoding#AUTO} selects.
     *
     * @return the default options
     */
    public static IcnsEncoderOptions defaults() {
        return DEFAULTS;
    }

    /**
     * Returns options writing the macOS application icon set from a single picture: square images of 16, 32, 64,
     * 128, 256, 512 and 1024 pixels.
     *
     * @return the options
     */
    public static IcnsEncoderOptions macSizes() {
        return MAC_SIZES;
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
     * {@return a copy of the sizes of the square images made from a single picture, each one of 16, 18, 24, 32, 36,
     * 48, 64, 128, 256, 512 and 1024 and distinct, or an empty array when each picture is written at its own size;
     * empty by default}
     *
     * <p>Each image is the picture scaled to fit that size and centered on a transparent background.
     */
    public int[] sizes() {
        return sizes.clone();
    }

    /**
     * {@return the element types images are stored in; {@link Encoding#AUTO} by default}
     */
    public Encoding encoding() {
        return encoding;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof IcnsEncoderOptions that && Arrays.equals(sizes, that.sizes)
                && encoding == that.encoding;
    }

    @Override
    public int hashCode() {
        return Objects.hash(Arrays.hashCode(sizes), encoding);
    }

    @Override
    public String toString() {
        return "IcnsEncoderOptions[sizes=" + Arrays.toString(sizes) + ", encoding=" + encoding + "]";
    }

    /**
     * Returns whether an icon family can hold a square image of a size.
     *
     * @param size the side in pixels
     * @return {@code true} for 16, 18, 24, 32, 36, 48, 64, 128, 256, 512 and 1024
     */
    static boolean supported(int size) {
        return Arrays.binarySearch(SIZES, size) >= 0;
    }

    /**
     * A builder of {@link IcnsEncoderOptions}.
     *
     * <p>Setters reject only {@code null}; {@link #build()} checks the sizes. Instances are not thread-safe.
     */
    public static final class Builder {
        private int[] sizes = new int[0];

        private Encoding encoding = Encoding.AUTO;

        private Builder() {
        }

        private Builder(IcnsEncoderOptions options) {
            this.sizes = options.sizes;
            this.encoding = options.encoding;
        }

        /**
         * Sets the image sizes made from a single picture; see {@link IcnsEncoderOptions#sizes()}.
         *
         * @param sizes the value, copied; none writes each picture at its own size
         * @return this builder
         */
        public Builder sizes(int... sizes) {
            this.sizes = sizes.clone();
            return this;
        }

        /**
         * Sets the element types images are stored in; see {@link IcnsEncoderOptions#encoding()}.
         *
         * @param encoding the value
         * @return this builder
         */
        public Builder encoding(Encoding encoding) {
            this.encoding = Objects.requireNonNull(encoding, "encoding");
            return this;
        }

        /**
         * Returns the options holding the settings of this builder.
         *
         * @return the options
         * @throws IllegalArgumentException if a size is not one an icon family holds or is repeated
         */
        public IcnsEncoderOptions build() {
            for (var i = 0; i < sizes.length; i++) {
                if (!supported(sizes[i])) {
                    throw new IllegalArgumentException("an ICNS file holds no image of " + sizes[i] + " pixels");
                }
                for (var j = 0; j < i; j++) {
                    if (sizes[j] == sizes[i]) {
                        throw new IllegalArgumentException("ICNS size " + sizes[i] + " is repeated");
                    }
                }
            }
            return new IcnsEncoderOptions(this);
        }
    }
}
