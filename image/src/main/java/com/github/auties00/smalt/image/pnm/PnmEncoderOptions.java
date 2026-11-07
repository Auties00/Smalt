package com.github.auties00.smalt.image.pnm;

import com.github.auties00.smalt.internal.check.Checks;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Holds the settings of a {@link PnmEncoder}. Instances are immutable and thread-safe; create them with
 * {@link #builder()} or a preset such as {@link #defaults()}.
 */
public final class PnmEncoderOptions {
    private static final PnmEncoderOptions DEFAULTS = new Builder().build();

    private final PnmVariant variant;

    private final boolean plain;

    private final int maxval;

    private final boolean littleEndian;

    private PnmEncoderOptions(Builder builder) {
        this.variant = builder.variant;
        this.plain = builder.plain;
        this.maxval = builder.maxval;
        this.littleEndian = builder.littleEndian;
    }

    /**
     * Returns options choosing the variant from the picture, writing raw rasters at the picture's depth and
     * little-endian float maps.
     *
     * @return the default options
     */
    public static PnmEncoderOptions defaults() {
        return DEFAULTS;
    }

    /**
     * Returns options writing one variant, with the other settings of {@link #defaults()}.
     *
     * @param variant the variant to write
     * @return the options
     */
    public static PnmEncoderOptions of(PnmVariant variant) {
        return new Builder().variant(variant).build();
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
     * {@return the variant written, or empty to choose it from the picture; empty by default}
     *
     * <p>The automatic choice writes a {@link com.github.auties00.smalt.image.ChannelFloatPicture} as
     * {@link PnmVariant#PFM}, a picture with alpha or a channel picture that is neither gray nor RGB as
     * {@link PnmVariant#PAM}, a gray channel picture as {@link PnmVariant#PGM} and any other picture as
     * {@link PnmVariant#PPM}.
     */
    public Optional<PnmVariant> variant() {
        return Optional.ofNullable(variant);
    }

    /**
     * {@return whether PBM, PGM and PPM rasters are written plain, as decimal text, rather than raw; {@code false}
     * by default}
     *
     * <p>PAM and the float maps have no plain form and ignore the setting.
     */
    public boolean plain() {
        return plain;
    }

    /**
     * {@return the largest sample value of an integer raster, from 1 to 65535, or empty to write 255 for a picture
     * of 8 bits and 65535 for a deeper one; empty by default}
     *
     * <p>A PBM raster ignores it. Samples are scaled to it with rounding.
     */
    public OptionalInt maxval() {
        return maxval == 0 ? OptionalInt.empty() : OptionalInt.of(maxval);
    }

    /**
     * {@return whether float map samples are little-endian, recorded by a negative scale factor; {@code true} by
     * default}
     */
    public boolean littleEndian() {
        return littleEndian;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof PnmEncoderOptions that && variant == that.variant && plain == that.plain
                && maxval == that.maxval && littleEndian == that.littleEndian;
    }

    @Override
    public int hashCode() {
        return Objects.hash(variant, plain, maxval, littleEndian);
    }

    @Override
    public String toString() {
        return "PnmEncoderOptions[variant=" + (variant == null ? "automatic" : variant) + ", plain=" + plain
                + ", maxval=" + (maxval == 0 ? "automatic" : maxval) + ", littleEndian=" + littleEndian + "]";
    }

    /**
     * A builder of {@link PnmEncoderOptions}.
     *
     * <p>Setters reject only {@code null}; {@link #build()} checks the ranges. Instances are not thread-safe.
     */
    public static final class Builder {
        private PnmVariant variant;

        private boolean plain;

        private int maxval;

        private boolean maxvalSet;

        private boolean littleEndian = true;

        private Builder() {
        }

        private Builder(PnmEncoderOptions options) {
            this.variant = options.variant;
            this.plain = options.plain;
            this.maxval = options.maxval;
            this.maxvalSet = options.maxval != 0;
            this.littleEndian = options.littleEndian;
        }

        /**
         * Sets the variant written; see {@link PnmEncoderOptions#variant()}.
         *
         * @param variant the value
         * @return this builder
         */
        public Builder variant(PnmVariant variant) {
            this.variant = Objects.requireNonNull(variant, "variant");
            return this;
        }

        /**
         * Chooses the variant from the picture; see {@link PnmEncoderOptions#variant()}.
         *
         * @return this builder
         */
        public Builder automaticVariant() {
            this.variant = null;
            return this;
        }

        /**
         * Sets whether rasters are written plain; see {@link PnmEncoderOptions#plain()}.
         *
         * @param plain the value
         * @return this builder
         */
        public Builder plain(boolean plain) {
            this.plain = plain;
            return this;
        }

        /**
         * Sets the largest sample value; see {@link PnmEncoderOptions#maxval()}.
         *
         * @param maxval the value
         * @return this builder
         */
        public Builder maxval(int maxval) {
            this.maxval = maxval;
            this.maxvalSet = true;
            return this;
        }

        /**
         * Chooses the largest sample value from the picture's depth; see {@link PnmEncoderOptions#maxval()}.
         *
         * @return this builder
         */
        public Builder automaticMaxval() {
            this.maxval = 0;
            this.maxvalSet = false;
            return this;
        }

        /**
         * Sets whether float map samples are little-endian; see {@link PnmEncoderOptions#littleEndian()}.
         *
         * @param littleEndian the value
         * @return this builder
         */
        public Builder littleEndian(boolean littleEndian) {
            this.littleEndian = littleEndian;
            return this;
        }

        /**
         * Returns the options holding the settings of this builder.
         *
         * @return the options
         * @throws IllegalArgumentException if the largest sample value is set outside 1..65535
         */
        public PnmEncoderOptions build() {
            if (maxvalSet) {
                Checks.inRange("maxval", maxval, 1, 65535);
            }
            return new PnmEncoderOptions(this);
        }
    }
}
