package com.github.auties00.smalt.audio.ac3;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.internal.check.Checks;

import java.util.Objects;

/**
 * Holds the settings of an {@link Ac3Decoder}. Instances are immutable and thread-safe; create them
 * with {@link #builder()} or a preset such as {@link #defaults()}.
 */
public final class Ac3DecoderOptions {
    /**
     * The options of {@link #defaults()}.
     */
    private static final Ac3DecoderOptions DEFAULTS = new Builder().build();

    /**
     * The options of {@link #stereo()}.
     */
    private static final Ac3DecoderOptions STEREO = new Builder().downmix(Ac3Downmix.STEREO).build();

    /**
     * The options of {@link #fullRange()}.
     */
    private static final Ac3DecoderOptions FULL_RANGE = new Builder().compression(Ac3Compression.NONE).build();

    private final MediaLimits limits;

    private final Ac3Downmix downmix;

    private final Ac3Compression compression;

    private final float cutScale;

    private final float boostScale;

    private final boolean dialogueNormalization;

    private final int program;

    private final boolean channelExtensions;

    private final boolean crcCheck;

    private Ac3DecoderOptions(Builder builder) {
        this.limits = builder.limits;
        this.downmix = builder.downmix;
        this.compression = builder.compression;
        this.cutScale = builder.cutScale;
        this.boostScale = builder.boostScale;
        this.dialogueNormalization = builder.dialogueNormalization;
        this.program = builder.program;
        this.channelExtensions = builder.channelExtensions;
        this.crcCheck = builder.crcCheck;
    }

    /**
     * Returns the default options, which reproduce the stream as ATSC A/52 asks a decoder to by
     * default: every coded channel, the light dynamic range compression applied in full, dialogue
     * normalization left to the playback chain, program 0, and frames failing their CRC concealed.
     *
     * @return the default options
     */
    public static Ac3DecoderOptions defaults() {
        return DEFAULTS;
    }

    /**
     * Returns options that decode a stereo (Lo/Ro) downmix, with the defaults otherwise.
     *
     * @return the options
     */
    public static Ac3DecoderOptions stereo() {
        return STEREO;
    }

    /**
     * Returns options that reproduce the full dynamic range, without compression, with the defaults
     * otherwise.
     *
     * @return the options
     */
    public static Ac3DecoderOptions fullRange() {
        return FULL_RANGE;
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
     */
    public MediaLimits limits() {
        return limits;
    }

    /**
     * {@return the output channel configuration; {@link Ac3Downmix#NONE}, every coded channel, by
     * default}
     */
    public Ac3Downmix downmix() {
        return downmix;
    }

    /**
     * {@return the dynamic range compression applied; {@link Ac3Compression#LIGHT} by default}
     */
    public Ac3Compression compression() {
        return compression;
    }

    /**
     * {@return the fraction, from 0 to 1, of the gain reductions of the dynamic range words that
     * {@link Ac3Compression#LIGHT} applies; 1 by default}
     */
    public float cutScale() {
        return cutScale;
    }

    /**
     * {@return the fraction, from 0 to 1, of the gain increases of the dynamic range words that
     * {@link Ac3Compression#LIGHT} applies; 1 by default}
     */
    public float boostScale() {
        return boostScale;
    }

    /**
     * {@return whether the decoder scales the output so that dialogue plays at -31 dBFS, using the
     * stream's dialogue normalization value; {@code false} by default}
     */
    public boolean dialogueNormalization() {
        return dialogueNormalization;
    }

    /**
     * {@return the independent substream decoded from a multi-program E-AC-3 stream, from 0 to 7;
     * 0 by default}
     */
    public int program() {
        return program;
    }

    /**
     * {@return whether the dependent substreams of an E-AC-3 program are decoded, giving the
     * channels beyond 5.1; {@code true} by default}
     *
     * <p>Channel extensions are decoded only when {@link #downmix()} is {@link Ac3Downmix#NONE}.
     */
    public boolean channelExtensions() {
        return channelExtensions;
    }

    /**
     * {@return whether frames that fail their CRC are concealed instead of decoded; {@code true} by
     * default}
     */
    public boolean crcCheck() {
        return crcCheck;
    }

    @Override
    public boolean equals(Object object) {
        return object instanceof Ac3DecoderOptions that
                && limits.equals(that.limits)
                && downmix == that.downmix
                && compression == that.compression
                && Float.compare(cutScale, that.cutScale) == 0
                && Float.compare(boostScale, that.boostScale) == 0
                && dialogueNormalization == that.dialogueNormalization
                && program == that.program
                && channelExtensions == that.channelExtensions
                && crcCheck == that.crcCheck;
    }

    @Override
    public int hashCode() {
        return Objects.hash(limits, downmix, compression, cutScale, boostScale, dialogueNormalization, program,
                channelExtensions, crcCheck);
    }

    @Override
    public String toString() {
        return "Ac3DecoderOptions[limits=" + limits + ", downmix=" + downmix + ", compression=" + compression
                + ", cutScale=" + cutScale + ", boostScale=" + boostScale + ", dialogueNormalization="
                + dialogueNormalization + ", program=" + program + ", channelExtensions=" + channelExtensions
                + ", crcCheck=" + crcCheck + "]";
    }

    /**
     * A builder of {@link Ac3DecoderOptions}, starting from {@link Ac3DecoderOptions#defaults()}.
     *
     * <p>Setters reject only {@code null}; {@link #build()} checks the ranges. A builder may build
     * repeatedly. Instances are not thread-safe.
     */
    public static final class Builder {
        private MediaLimits limits;

        private Ac3Downmix downmix;

        private Ac3Compression compression;

        private float cutScale;

        private float boostScale;

        private boolean dialogueNormalization;

        private int program;

        private boolean channelExtensions;

        private boolean crcCheck;

        private Builder() {
            this.limits = MediaLimits.DEFAULT;
            this.downmix = Ac3Downmix.NONE;
            this.compression = Ac3Compression.LIGHT;
            this.cutScale = 1;
            this.boostScale = 1;
            this.dialogueNormalization = false;
            this.program = 0;
            this.channelExtensions = true;
            this.crcCheck = true;
        }

        private Builder(Ac3DecoderOptions options) {
            this.limits = options.limits;
            this.downmix = options.downmix;
            this.compression = options.compression;
            this.cutScale = options.cutScale;
            this.boostScale = options.boostScale;
            this.dialogueNormalization = options.dialogueNormalization;
            this.program = options.program;
            this.channelExtensions = options.channelExtensions;
            this.crcCheck = options.crcCheck;
        }

        /**
         * Sets the resource bounds; see {@link Ac3DecoderOptions#limits()}.
         *
         * @param limits the value
         * @return this builder
         */
        public Builder limits(MediaLimits limits) {
            this.limits = Objects.requireNonNull(limits, "limits");
            return this;
        }

        /**
         * Sets the output channel configuration; see {@link Ac3DecoderOptions#downmix()}.
         *
         * @param downmix the value
         * @return this builder
         */
        public Builder downmix(Ac3Downmix downmix) {
            this.downmix = Objects.requireNonNull(downmix, "downmix");
            return this;
        }

        /**
         * Sets the dynamic range compression; see {@link Ac3DecoderOptions#compression()}.
         *
         * @param compression the value
         * @return this builder
         */
        public Builder compression(Ac3Compression compression) {
            this.compression = Objects.requireNonNull(compression, "compression");
            return this;
        }

        /**
         * Sets the fraction of the gain reductions of the dynamic range words applied; see
         * {@link Ac3DecoderOptions#cutScale()}.
         *
         * @param cutScale the value
         * @return this builder
         */
        public Builder cutScale(float cutScale) {
            this.cutScale = cutScale;
            return this;
        }

        /**
         * Sets the fraction of the gain increases of the dynamic range words applied; see
         * {@link Ac3DecoderOptions#boostScale()}.
         *
         * @param boostScale the value
         * @return this builder
         */
        public Builder boostScale(float boostScale) {
            this.boostScale = boostScale;
            return this;
        }

        /**
         * Sets whether dialogue normalization is applied; see
         * {@link Ac3DecoderOptions#dialogueNormalization()}.
         *
         * @param dialogueNormalization the value
         * @return this builder
         */
        public Builder dialogueNormalization(boolean dialogueNormalization) {
            this.dialogueNormalization = dialogueNormalization;
            return this;
        }

        /**
         * Sets the program decoded; see {@link Ac3DecoderOptions#program()}.
         *
         * @param program the value
         * @return this builder
         */
        public Builder program(int program) {
            this.program = program;
            return this;
        }

        /**
         * Sets whether E-AC-3 dependent substreams are decoded; see
         * {@link Ac3DecoderOptions#channelExtensions()}.
         *
         * @param channelExtensions the value
         * @return this builder
         */
        public Builder channelExtensions(boolean channelExtensions) {
            this.channelExtensions = channelExtensions;
            return this;
        }

        /**
         * Sets whether frames failing their CRC are concealed; see {@link Ac3DecoderOptions#crcCheck()}.
         *
         * @param crcCheck the value
         * @return this builder
         */
        public Builder crcCheck(boolean crcCheck) {
            this.crcCheck = crcCheck;
            return this;
        }

        /**
         * Returns the options holding the settings of this builder.
         *
         * @return the options
         * @throws IllegalArgumentException if the cut scale or the boost scale is not in [0, 1], or the
         * program is not in [0, 7]
         */
        public Ac3DecoderOptions build() {
            Checks.inRange("cut scale", cutScale, 0, 1);
            Checks.inRange("boost scale", boostScale, 0, 1);
            Checks.inRange("program", program, 0, 7);
            return new Ac3DecoderOptions(this);
        }
    }
}
