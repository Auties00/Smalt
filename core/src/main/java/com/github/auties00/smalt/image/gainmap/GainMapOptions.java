package com.github.auties00.smalt.image.gainmap;

import com.github.auties00.smalt.image.ColorPrimaries;
import com.github.auties00.smalt.image.TransferCharacteristics;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * Holds the settings of {@link GainMaps#apply}. Instances are immutable and thread-safe; create them with
 * {@link #builder()} or a preset such as {@link #defaults()}.
 */
public final class GainMapOptions {
    /**
     * The reference white of ITU-R BT.2408, in candelas per square meter.
     */
    public static final double DEFAULT_REFERENCE_WHITE = 203;

    private static final GainMapOptions DEFAULTS = new GainMapOptions(Double.NaN, TransferCharacteristics.PQ, null,
            DEFAULT_REFERENCE_WHITE);

    private final double headroom;
    private final TransferCharacteristics transfer;
    private final ColorPrimaries primaries;
    private final double referenceWhite;

    private GainMapOptions(double headroom, TransferCharacteristics transfer, ColorPrimaries primaries,
                           double referenceWhite) {
        this.headroom = headroom;
        this.transfer = transfer;
        this.primaries = primaries;
        this.referenceWhite = referenceWhite;
    }

    /**
     * Returns the options that render the alternate rendition in full, as PQ, in the primaries of the alternate
     * rendition, with the BT.2408 reference white.
     *
     * @return the default options
     */
    public static GainMapOptions defaults() {
        return DEFAULTS;
    }

    /**
     * Returns the default options with a display headroom.
     *
     * @param headroom the headroom of the display, as a base-2 logarithm
     * @return the options
     * @throws IllegalArgumentException if {@code headroom} is negative or not finite
     */
    public static GainMapOptions ofHeadroom(double headroom) {
        return builder().headroom(headroom).build();
    }

    /**
     * Returns a builder initialized with {@link #defaults()}.
     *
     * @return the builder
     */
    public static Builder builder() {
        return new Builder(DEFAULTS);
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
     * {@return the headroom of the display rendered for, as a base-2 logarithm of its peak luminance relative to
     * the reference white, at least 0; empty by default, which renders the alternate rendition in full}
     *
     * <p>The gain map is applied with the {@linkplain GainMapMetadata#weight(double) weight} of this headroom: a
     * headroom at or below that of the base rendition gives the base rendition, one at or above that of the
     * alternate rendition gives the alternate one.
     */
    public OptionalDouble headroom() {
        return Double.isNaN(headroom) ? OptionalDouble.empty() : OptionalDouble.of(headroom);
    }

    /**
     * {@return the transfer function of the rendered picture, {@link TransferCharacteristics#PQ} or
     * {@link TransferCharacteristics#HLG}; PQ by default}
     *
     * <p>PQ places a linear value of 1 at the {@link #referenceWhite()} and clips at 10000 candelas per square
     * meter. HLG places it at the reference white of the 1000 candelas per square meter reference display, clips
     * at that peak, and removes the BT.2100 system gamma (1.2, on the luminance of the output primaries) before
     * the OETF.
     */
    public TransferCharacteristics transfer() {
        return transfer;
    }

    /**
     * {@return the primaries of the rendered picture; empty by default, which takes the primaries of the
     * alternate rendition, or of the base rendition when the gain map declares none, or BT.2020 when they are
     * not CICP primaries}
     */
    public Optional<ColorPrimaries> primaries() {
        return Optional.ofNullable(primaries);
    }

    /**
     * {@return the luminance of a linear value of 1, the standard-dynamic-range white, in candelas per square
     * meter, from 1 to 10000; {@value #DEFAULT_REFERENCE_WHITE} by default}
     *
     * <p>It also scales the decoding of PQ and HLG renditions, whose linear value is their luminance divided by
     * it.
     */
    public double referenceWhite() {
        return referenceWhite;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof GainMapOptions that && Double.compare(headroom, that.headroom) == 0
                && transfer == that.transfer && primaries == that.primaries
                && Double.compare(referenceWhite, that.referenceWhite) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(headroom, transfer, primaries, referenceWhite);
    }

    @Override
    public String toString() {
        return "GainMapOptions[headroom=" + headroom() + ", transfer=" + transfer + ", primaries=" + primaries()
                + ", referenceWhite=" + referenceWhite + "]";
    }

    /**
     * Builds {@link GainMapOptions}.
     *
     * <p>Setters store their value; {@link #build()} checks the ranges. Instances are not thread-safe.
     */
    public static final class Builder {
        private double headroom;
        private boolean automaticHeadroom;
        private TransferCharacteristics transfer;
        private ColorPrimaries primaries;
        private double referenceWhite;

        private Builder(GainMapOptions options) {
            this.headroom = options.headroom;
            this.automaticHeadroom = Double.isNaN(options.headroom);
            this.transfer = options.transfer;
            this.primaries = options.primaries;
            this.referenceWhite = options.referenceWhite;
        }

        /**
         * Sets the headroom of the display; see {@link GainMapOptions#headroom()}.
         *
         * @param headroom the value
         * @return this builder
         */
        public Builder headroom(double headroom) {
            this.headroom = headroom;
            this.automaticHeadroom = false;
            return this;
        }

        /**
         * Renders the alternate rendition in full; see {@link GainMapOptions#headroom()}.
         *
         * @return this builder
         */
        public Builder automaticHeadroom() {
            this.automaticHeadroom = true;
            return this;
        }

        /**
         * Sets the transfer function of the rendered picture; see {@link GainMapOptions#transfer()}.
         *
         * @param transfer the value
         * @return this builder
         */
        public Builder transfer(TransferCharacteristics transfer) {
            this.transfer = Objects.requireNonNull(transfer, "transfer");
            return this;
        }

        /**
         * Sets the primaries of the rendered picture; see {@link GainMapOptions#primaries()}.
         *
         * @param primaries the value
         * @return this builder
         */
        public Builder primaries(ColorPrimaries primaries) {
            this.primaries = Objects.requireNonNull(primaries, "primaries");
            return this;
        }

        /**
         * Takes the primaries of the alternate rendition; see {@link GainMapOptions#primaries()}.
         *
         * @return this builder
         */
        public Builder automaticPrimaries() {
            this.primaries = null;
            return this;
        }

        /**
         * Sets the luminance of the standard-dynamic-range white; see {@link GainMapOptions#referenceWhite()}.
         *
         * @param referenceWhite the value
         * @return this builder
         */
        public Builder referenceWhite(double referenceWhite) {
            this.referenceWhite = referenceWhite;
            return this;
        }

        /**
         * Builds the options.
         *
         * @return the options
         * @throws IllegalArgumentException if the headroom is negative or not finite, the transfer function is
         * neither PQ nor HLG, the primaries are {@link ColorPrimaries#UNSPECIFIED}, or the reference white is
         * not from 1 to 10000
         */
        public GainMapOptions build() {
            if (!automaticHeadroom && !(headroom >= 0 && headroom < Double.POSITIVE_INFINITY)) {
                throw new IllegalArgumentException("invalid headroom " + headroom);
            }
            if (transfer != TransferCharacteristics.PQ && transfer != TransferCharacteristics.HLG) {
                throw new IllegalArgumentException("gain maps render to PQ or HLG, not " + transfer);
            }
            if (primaries == ColorPrimaries.UNSPECIFIED) {
                throw new IllegalArgumentException("unspecified primaries");
            }
            if (!(referenceWhite >= 1 && referenceWhite <= 10000)) {
                throw new IllegalArgumentException("invalid reference white " + referenceWhite);
            }
            return new GainMapOptions(automaticHeadroom ? Double.NaN : headroom, transfer, primaries, referenceWhite);
        }
    }
}
