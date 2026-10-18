package com.github.auties00.smalt.image.ico;

import com.github.auties00.smalt.internal.check.Checks;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

/**
 * Holds the settings of an {@link IcoEncoder}. Instances are immutable and thread-safe; create them
 * with {@link #builder()} or a preset such as {@link #defaults()}.
 */
public final class IcoEncoderOptions {
    /**
     * The options of {@link #defaults()}.
     */
    private static final IcoEncoderOptions DEFAULTS = new Builder().build();

    /**
     * The options of {@link #windowsSizes()}.
     */
    private static final IcoEncoderOptions WINDOWS_SIZES = new Builder().sizes(16, 24, 32, 48, 64, 256).build();

    private final int[] sizes;

    private final Encoding encoding;

    private final IcoEntry.Hotspot hotspot;

    /**
     * Selects how entries are stored.
     */
    public enum Encoding {
        /**
         * PNG for entries 256 pixels wide or tall, a 32-bit bitmap otherwise, the layout Windows
         * itself writes.
         */
        AUTO,

        /**
         * A 32-bit BGRA bitmap with an AND mask, readable by every icon reader.
         */
        BMP,

        /**
         * A PNG file, smaller, readable by Windows Vista and later and by browsers.
         */
        PNG
    }

    private IcoEncoderOptions(Builder builder) {
        this.sizes = builder.sizes.clone();
        this.encoding = builder.encoding;
        this.hotspot = builder.hotspot;
    }

    /**
     * Returns options writing each picture as one icon entry of its own size, stored as
     * {@link Encoding#AUTO} selects.
     *
     * @return the default options
     */
    public static IcoEncoderOptions defaults() {
        return DEFAULTS;
    }

    /**
     * Returns options writing the classic Windows icon set from a single picture: square entries of
     * 16, 24, 32, 48, 64 and 256 pixels.
     *
     * @return the options
     */
    public static IcoEncoderOptions windowsSizes() {
        return WINDOWS_SIZES;
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
     * {@return a copy of the sizes of the square entries made from a single picture, each from 1 to
     * 256 and distinct, or an empty array when each picture is written at its own size; empty by
     * default}
     *
     * <p>Each entry is the picture scaled to that size.
     */
    public int[] sizes() {
        return sizes.clone();
    }

    /**
     * {@return how entries are stored; {@link Encoding#AUTO} by default}
     */
    public Encoding encoding() {
        return encoding;
    }

    /**
     * {@return the cursor hotspot in the coordinates of the encoded picture, scaled with it to each
     * entry size, with non-negative coordinates, or empty for an icon; empty by default}
     */
    public Optional<IcoEntry.Hotspot> hotspot() {
        return Optional.ofNullable(hotspot);
    }

    /**
     * {@return whether the options write a cursor, that is whether a hotspot is set}
     */
    public boolean isCursor() {
        return hotspot != null;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof IcoEncoderOptions that && Arrays.equals(sizes, that.sizes)
                && encoding == that.encoding && Objects.equals(hotspot, that.hotspot);
    }

    @Override
    public int hashCode() {
        return Objects.hash(Arrays.hashCode(sizes), encoding, hotspot);
    }

    @Override
    public String toString() {
        return "IcoEncoderOptions[sizes=" + Arrays.toString(sizes) + ", encoding=" + encoding
                + (hotspot != null ? ", hotspot=" + hotspot.x() + "," + hotspot.y() : "") + "]";
    }

    /**
     * A builder of {@link IcoEncoderOptions}.
     *
     * <p>Setters reject only {@code null}; {@link #build()} checks the ranges. Instances are not
     * thread-safe.
     */
    public static final class Builder {
        private int[] sizes = new int[0];

        private Encoding encoding = Encoding.AUTO;

        private IcoEntry.Hotspot hotspot;

        private Builder() {
        }

        private Builder(IcoEncoderOptions options) {
            this.sizes = options.sizes;
            this.encoding = options.encoding;
            this.hotspot = options.hotspot;
        }

        /**
         * Sets the entry sizes made from a single picture; see {@link IcoEncoderOptions#sizes()}.
         *
         * @param sizes the value, copied; none writes each picture at its own size
         * @return this builder
         */
        public Builder sizes(int... sizes) {
            this.sizes = sizes.clone();
            return this;
        }

        /**
         * Sets how entries are stored; see {@link IcoEncoderOptions#encoding()}.
         *
         * @param encoding the value
         * @return this builder
         */
        public Builder encoding(Encoding encoding) {
            this.encoding = Objects.requireNonNull(encoding, "encoding");
            return this;
        }

        /**
         * Sets the cursor hotspot, making the file a cursor; see {@link IcoEncoderOptions#hotspot()}.
         *
         * @param hotspot the value, or {@code null} for an icon
         * @return this builder
         */
        public Builder hotspot(IcoEntry.Hotspot hotspot) {
            this.hotspot = hotspot;
            return this;
        }

        /**
         * Sets the cursor hotspot from its coordinates, making the file a cursor; see
         * {@link IcoEncoderOptions#hotspot()}.
         *
         * @param x the hotspot column in the encoded picture
         * @param y the hotspot row in the encoded picture
         * @return this builder
         */
        public Builder hotspot(int x, int y) {
            this.hotspot = new IcoEntry.Hotspot(x, y);
            return this;
        }

        /**
         * Returns the options holding the settings of this builder.
         *
         * @return the options
         * @throws IllegalArgumentException if a size is not in [1, 256] or is repeated, or a hotspot
         * coordinate is negative
         */
        public IcoEncoderOptions build() {
            for (var i = 0; i < sizes.length; i++) {
                Checks.inRange("icon size", sizes[i], 1, 256);
                for (var j = 0; j < i; j++) {
                    if (sizes[j] == sizes[i]) {
                        throw new IllegalArgumentException("icon size " + sizes[i] + " is repeated");
                    }
                }
            }
            if (hotspot != null) {
                Checks.nonNegative("hotspot column", hotspot.x());
                Checks.nonNegative("hotspot row", hotspot.y());
            }
            return new IcoEncoderOptions(this);
        }
    }
}
