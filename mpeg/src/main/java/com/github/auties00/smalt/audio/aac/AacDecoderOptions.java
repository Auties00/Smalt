package com.github.auties00.smalt.audio.aac;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.internal.check.Checks;

import java.util.Objects;
import java.util.OptionalInt;

/**
 * Holds the settings of an {@link AacDecoder}. Instances are immutable and thread-safe; create them
 * with {@link #builder()} or a preset such as {@link #defaults()}.
 */
public final class AacDecoderOptions {
    /**
     * The options of {@link #defaults()}.
     */
    private static final AacDecoderOptions DEFAULTS = new Builder().build();

    /**
     * The builder's marker of an unset target level.
     */
    private static final int NO_TARGET = Integer.MIN_VALUE;

    private final MediaLimits limits;

    private final boolean spectralBandReplication;

    private final AacCompression compression;

    private final float cutScale;

    private final float boostScale;

    private final int targetLevel;

    private AacDecoderOptions(Builder builder) {
        this.limits = builder.limits;
        this.spectralBandReplication = builder.spectralBandReplication;
        this.compression = builder.compression;
        this.cutScale = builder.cutScale;
        this.boostScale = builder.boostScale;
        this.targetLevel = builder.targetLevel == NO_TARGET ? -1 : builder.targetLevel;
    }

    /**
     * Returns the default options: decoding under {@link MediaLimits#defaults()}, with spectral band
     * replication and parametric stereo, reproducing the full dynamic range at the stream's own
     * level.
     *
     * @return the default options
     */
    public static AacDecoderOptions defaults() {
        return DEFAULTS;
    }

    /**
     * Returns a builder initialized with {@link #defaults()}.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns a builder initialized with these options.
     *
     * @return a new builder
     */
    public Builder toBuilder() {
        return new Builder(this);
    }

    /**
     * {@return the resource bounds the decoder enforces; {@link MediaLimits#defaults()} by default}
     *
     * <p>The decoder checks the channel count that the configuration or a program configuration
     * element declares against {@link MediaLimits#maxChannels()}.
     */
    public MediaLimits limits() {
        return limits;
    }

    /**
     * {@return whether the decoder decodes spectral band replication and parametric stereo;
     * {@code true} by default}
     *
     * <p>When {@code false}, an HE-AAC stream decodes to its Low Complexity core, at the core rate
     * and with the core layout, for a fraction of the work.
     */
    public boolean spectralBandReplication() {
        return spectralBandReplication;
    }

    /**
     * {@return the dynamic range compression applied; {@link AacCompression#NONE} by default}
     *
     * <p>The gains scale the spectrum of each channel before the synthesis filterbank, or the QMF
     * subbands before the QMF synthesis when spectral band replication is decoded, so the output
     * of a stream without compression gains is the same in every mode.
     */
    public AacCompression compression() {
        return compression;
    }

    /**
     * {@return the fraction, from 0 to 1, of the attenuations of the {@code dynamic_range_info}
     * gains that {@link AacCompression#LIGHT} applies; 1 by default}
     */
    public float cutScale() {
        return cutScale;
    }

    /**
     * {@return the fraction, from 0 to 1, of the boosts of the {@code dynamic_range_info} gains
     * that {@link AacCompression#LIGHT} applies; 1 by default}
     */
    public float boostScale() {
        return boostScale;
    }

    /**
     * {@return the output level the decoder normalizes the program to, in quarter dB below full
     * scale from 0 to 127, or empty to keep the stream's level; empty by default}
     *
     * <p>When set, the output is scaled by {@code 2^((programLevel - targetLevel) / 24)}, where
     * {@code programLevel} is the latest program reference level of the stream's
     * {@code dynamic_range_info}, in the same units; until the stream transmits one, it equals the
     * target and the scale is 1. The normalization applies whatever {@link #compression()} is. For
     * example, 124 targets -31 dBFS and 92 targets -23 dBFS.
     */
    public OptionalInt targetLevel() {
        return targetLevel < 0 ? OptionalInt.empty() : OptionalInt.of(targetLevel);
    }

    @Override
    public boolean equals(Object object) {
        return object instanceof AacDecoderOptions that && limits.equals(that.limits)
                && spectralBandReplication == that.spectralBandReplication && compression == that.compression
                && Float.compare(cutScale, that.cutScale) == 0 && Float.compare(boostScale, that.boostScale) == 0
                && targetLevel == that.targetLevel;
    }

    @Override
    public int hashCode() {
        return Objects.hash(limits, spectralBandReplication, compression, cutScale, boostScale, targetLevel);
    }

    @Override
    public String toString() {
        return "AacDecoderOptions[limits=" + limits + ", spectralBandReplication=" + spectralBandReplication
                + ", compression=" + compression + ", cutScale=" + cutScale + ", boostScale=" + boostScale
                + ", targetLevel=" + targetLevel() + "]";
    }

    /**
     * A builder of {@link AacDecoderOptions}, starting from {@link AacDecoderOptions#defaults()}.
     *
     * <p>Setters reject only {@code null}; {@link #build()} checks the ranges. A builder may build
     * repeatedly. Instances are not thread-safe.
     */
    public static final class Builder {
        private MediaLimits limits;

        private boolean spectralBandReplication;

        private AacCompression compression;

        private float cutScale;

        private float boostScale;

        private int targetLevel;

        private Builder() {
            this.limits = MediaLimits.DEFAULT;
            this.spectralBandReplication = true;
            this.compression = AacCompression.NONE;
            this.cutScale = 1;
            this.boostScale = 1;
            this.targetLevel = NO_TARGET;
        }

        private Builder(AacDecoderOptions options) {
            this.limits = options.limits;
            this.spectralBandReplication = options.spectralBandReplication;
            this.compression = options.compression;
            this.cutScale = options.cutScale;
            this.boostScale = options.boostScale;
            this.targetLevel = options.targetLevel < 0 ? NO_TARGET : options.targetLevel;
        }

        /**
         * Sets the resource bounds; see {@link AacDecoderOptions#limits()}.
         *
         * @param limits the value
         * @return this builder
         */
        public Builder limits(MediaLimits limits) {
            this.limits = Objects.requireNonNull(limits, "limits");
            return this;
        }

        /**
         * Sets whether spectral band replication and parametric stereo are decoded; see
         * {@link AacDecoderOptions#spectralBandReplication()}.
         *
         * @param enabled the value
         * @return this builder
         */
        public Builder spectralBandReplication(boolean enabled) {
            this.spectralBandReplication = enabled;
            return this;
        }

        /**
         * Sets the dynamic range compression; see {@link AacDecoderOptions#compression()}.
         *
         * @param compression the value
         * @return this builder
         */
        public Builder compression(AacCompression compression) {
            this.compression = Objects.requireNonNull(compression, "compression");
            return this;
        }

        /**
         * Sets the fraction of the attenuations applied; see {@link AacDecoderOptions#cutScale()}.
         *
         * @param cutScale the value
         * @return this builder
         */
        public Builder cutScale(float cutScale) {
            this.cutScale = cutScale;
            return this;
        }

        /**
         * Sets the fraction of the boosts applied; see {@link AacDecoderOptions#boostScale()}.
         *
         * @param boostScale the value
         * @return this builder
         */
        public Builder boostScale(float boostScale) {
            this.boostScale = boostScale;
            return this;
        }

        /**
         * Sets the level the program is normalized to; see {@link AacDecoderOptions#targetLevel()}.
         *
         * @param targetLevel the value
         * @return this builder
         */
        public Builder targetLevel(int targetLevel) {
            this.targetLevel = targetLevel;
            return this;
        }

        /**
         * Keeps the stream's own level; see {@link AacDecoderOptions#targetLevel()}.
         *
         * @return this builder
         */
        public Builder noTargetLevel() {
            this.targetLevel = NO_TARGET;
            return this;
        }

        /**
         * Returns the options holding the settings of this builder.
         *
         * @return the options
         * @throws IllegalArgumentException if the cut scale or the boost scale is not in [0, 1], or
         * the target level is set outside [0, 127]
         */
        public AacDecoderOptions build() {
            Checks.inRange("cut scale", cutScale, 0, 1);
            Checks.inRange("boost scale", boostScale, 0, 1);
            if (targetLevel != NO_TARGET) {
                Checks.inRange("target level", targetLevel, 0, 127);
            }
            return new AacDecoderOptions(this);
        }
    }
}
