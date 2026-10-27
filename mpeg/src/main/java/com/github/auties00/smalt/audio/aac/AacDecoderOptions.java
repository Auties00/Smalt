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
     * The builder's marker of an unset target level or layer limit.
     */
    private static final int NO_TARGET = Integer.MIN_VALUE;

    private final MediaLimits limits;

    private final boolean spectralBandReplication;

    private final AacCompression compression;

    private final float cutScale;

    private final float boostScale;

    private final int targetLevel;

    private final AacDrcEffect drcEffect;

    private final int maxLayer;

    private final boolean mpegSurround;

    private AacDecoderOptions(Builder builder) {
        this.limits = builder.limits;
        this.spectralBandReplication = builder.spectralBandReplication;
        this.compression = builder.compression;
        this.cutScale = builder.cutScale;
        this.boostScale = builder.boostScale;
        this.targetLevel = builder.targetLevel == NO_TARGET ? -1 : builder.targetLevel;
        this.drcEffect = builder.drcEffect;
        this.maxLayer = builder.maxLayer == NO_TARGET ? -1 : builder.maxLayer;
        this.mpegSurround = builder.mpegSurround;
    }

    /**
     * Returns the default options: decoding under {@link MediaLimits#defaults()}, with spectral band
     * replication, parametric stereo and MPEG Surround, reproducing the full dynamic range at the stream's own
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
     * and with the core layout, for a fraction of the work. USAC ignores this setting and always decodes its
     * enhanced spectral band replication.
     */
    public boolean spectralBandReplication() {
        return spectralBandReplication;
    }

    /**
     * {@return the dynamic range compression applied; {@link AacCompression#NONE} by default}
     *
     * <p>The gains scale the spectrum of each channel before the synthesis filterbank, or the QMF
     * subbands before the QMF synthesis when spectral band replication is decoded, so the output
     * of a stream without compression gains is the same in every mode. USAC ignores this setting: its
     * dynamic range control is the MPEG-D DRC that {@link #drcEffect()} requests.
     */
    public AacCompression compression() {
        return compression;
    }

    /**
     * {@return the fraction, from 0 to 1, of the attenuations of the {@code dynamic_range_info}
     * gains that {@link AacCompression#LIGHT} applies; 1 by default}
     *
     * <p>For USAC, the fraction scales the attenuations of the MPEG-D DRC gains instead (the
     * {@code compress} parameter of ISO/IEC 23003-4).
     */
    public float cutScale() {
        return cutScale;
    }

    /**
     * {@return the fraction, from 0 to 1, of the boosts of the {@code dynamic_range_info} gains
     * that {@link AacCompression#LIGHT} applies; 1 by default}
     *
     * <p>For USAC, the fraction scales the boosts of the MPEG-D DRC gains instead (the
     * {@code boost} parameter of ISO/IEC 23003-4).
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
     *
     * <p>For USAC, the level is the target loudness of the MPEG-D loudness normalization, in quarter
     * LKFS below full scale (96 targets -24 LKFS), from the {@code loudnessInfoSet()} of the stream;
     * the selection of ISO/IEC 23003-4 picks the DRC set that best keeps the normalized program below
     * full scale, and a peak limiter at -1 dBFS catches the remaining peaks.
     */
    public OptionalInt targetLevel() {
        return targetLevel < 0 ? OptionalInt.empty() : OptionalInt.of(targetLevel);
    }

    /**
     * {@return the dynamic range control effect requested from the MPEG-D DRC metadata of a USAC
     * stream; {@link AacDrcEffect#NONE} by default}
     *
     * <p>A USAC stream with MPEG-D DRC metadata (ISO/IEC 23003-4, carried as ISO/IEC 23003-3
     * specifies) is decoded through the MPEG-D DRC decoder: it selects the DRC set, equalizer and
     * loudness normalization gain that match this request and {@link #targetLevel()}, applies them
     * after the core decoder, and limits the peaks of the output at -1 dBFS. The other object types
     * ignore this setting.
     */
    public AacDrcEffect drcEffect() {
        return drcEffect;
    }

    /**
     * {@return the highest scalability layer decoded from an ER BSAC or scalable AAC stream, or empty
     * to decode every layer the stream codes; empty by default}
     *
     * <p>ER BSAC codes a base layer and up to 63 enhancement layers, each adding bit planes and
     * bandwidth (fine grain scalability, ISO/IEC 14496-3 subclause 4.5.2.6). Scalable AAC (object
     * types 6 and 20, ISO/IEC 14496-3 subclause 4.5.2.2) codes up to 8 layers, each in an elementary
     * stream of its own, and outputs the channels of the highest layer decoded. Layer 0 is the base
     * layer; a limit above the top layer of a stream decodes every layer. A stream that carries its
     * layers in separate elementary streams decodes the layers of the access units it is given, so a
     * limit is the way to decode fewer layers from complete frames. The other object types ignore
     * this setting.
     */
    public OptionalInt maxLayer() {
        return maxLayer < 0 ? OptionalInt.empty() : OptionalInt.of(maxLayer);
    }

    /**
     * {@return whether the decoder upmixes MPEG Surround; {@code true} by default}
     *
     * <p>An AAC stream may carry MPEG Surround side information (ISO/IEC 23003-1) in the fill elements of its
     * downmix, or in a separate elementary stream of object type 30; the decoder then outputs the 5.1 or 7.1
     * upmix instead of the mono, stereo or 5.1 downmix. When {@code false}, the downmix is output and the side
     * information ignored. USAC ignores this setting and always decodes the MPEG Surround 2-1-2 of its stereo
     * elements.
     */
    public boolean mpegSurround() {
        return mpegSurround;
    }

    @Override
    public boolean equals(Object object) {
        return object instanceof AacDecoderOptions that && limits.equals(that.limits)
                && spectralBandReplication == that.spectralBandReplication && compression == that.compression
                && Float.compare(cutScale, that.cutScale) == 0 && Float.compare(boostScale, that.boostScale) == 0
                && targetLevel == that.targetLevel && drcEffect == that.drcEffect && maxLayer == that.maxLayer
                && mpegSurround == that.mpegSurround;
    }

    @Override
    public int hashCode() {
        return Objects.hash(limits, spectralBandReplication, compression, cutScale, boostScale, targetLevel,
                drcEffect, maxLayer, mpegSurround);
    }

    @Override
    public String toString() {
        return "AacDecoderOptions[limits=" + limits + ", spectralBandReplication=" + spectralBandReplication
                + ", compression=" + compression + ", cutScale=" + cutScale + ", boostScale=" + boostScale
                + ", targetLevel=" + targetLevel() + ", drcEffect=" + drcEffect + ", maxLayer=" + maxLayer()
                + ", mpegSurround=" + mpegSurround + "]";
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

        private AacDrcEffect drcEffect;

        private int maxLayer;

        private boolean mpegSurround;

        private Builder() {
            this.limits = MediaLimits.DEFAULT;
            this.spectralBandReplication = true;
            this.compression = AacCompression.NONE;
            this.cutScale = 1;
            this.boostScale = 1;
            this.targetLevel = NO_TARGET;
            this.drcEffect = AacDrcEffect.NONE;
            this.maxLayer = NO_TARGET;
            this.mpegSurround = true;
        }

        private Builder(AacDecoderOptions options) {
            this.limits = options.limits;
            this.spectralBandReplication = options.spectralBandReplication;
            this.compression = options.compression;
            this.cutScale = options.cutScale;
            this.boostScale = options.boostScale;
            this.targetLevel = options.targetLevel < 0 ? NO_TARGET : options.targetLevel;
            this.drcEffect = options.drcEffect;
            this.maxLayer = options.maxLayer < 0 ? NO_TARGET : options.maxLayer;
            this.mpegSurround = options.mpegSurround;
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
         * Sets the requested MPEG-D DRC effect; see {@link AacDecoderOptions#drcEffect()}.
         *
         * @param drcEffect the value
         * @return this builder
         */
        public Builder drcEffect(AacDrcEffect drcEffect) {
            this.drcEffect = Objects.requireNonNull(drcEffect, "drcEffect");
            return this;
        }

        /**
         * Sets the highest ER BSAC or scalable AAC layer to decode; see {@link AacDecoderOptions#maxLayer()}.
         *
         * @param maxLayer the layer, from 0 (the base layer) to 63
         * @return this builder
         */
        public Builder maxLayer(int maxLayer) {
            this.maxLayer = maxLayer;
            return this;
        }

        /**
         * Decodes every layer of an ER BSAC or scalable AAC stream; see {@link AacDecoderOptions#maxLayer()}.
         *
         * @return this builder
         */
        public Builder allLayers() {
            this.maxLayer = NO_TARGET;
            return this;
        }

        /**
         * Sets whether MPEG Surround is upmixed; see {@link AacDecoderOptions#mpegSurround()}.
         *
         * @param enabled the value
         * @return this builder
         */
        public Builder mpegSurround(boolean enabled) {
            this.mpegSurround = enabled;
            return this;
        }

        /**
         * Returns the options holding the settings of this builder.
         *
         * @return the options
         * @throws IllegalArgumentException if the cut scale or the boost scale is not in [0, 1], the
         * target level is set outside [0, 127], or the maximum layer outside [0, 63]
         */
        public AacDecoderOptions build() {
            Checks.inRange("cut scale", cutScale, 0, 1);
            Checks.inRange("boost scale", boostScale, 0, 1);
            if (targetLevel != NO_TARGET) {
                Checks.inRange("target level", targetLevel, 0, 127);
            }
            if (maxLayer != NO_TARGET) {
                Checks.inRange("max layer", maxLayer, 0, 63);
            }
            return new AacDecoderOptions(this);
        }
    }
}
