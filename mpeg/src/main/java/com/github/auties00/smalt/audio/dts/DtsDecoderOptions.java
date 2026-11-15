package com.github.auties00.smalt.audio.dts;

import com.github.auties00.smalt.MediaLimits;

import java.util.Objects;

/**
 * Holds the settings of a {@link DtsDecoder}. Instances are immutable and thread-safe; create them with
 * {@link #builder()} or a preset such as {@link #defaults()}.
 */
public final class DtsDecoderOptions {
    /**
     * The options of {@link #defaults()}.
     */
    private static final DtsDecoderOptions DEFAULTS = new Builder().build();

    /**
     * The options of {@link #coreOnly()}.
     */
    private static final DtsDecoderOptions CORE_ONLY = new Builder().extensions(false).build();

    /**
     * The resource limits.
     */
    private final MediaLimits limits;

    /**
     * Whether the extensions are decoded.
     */
    private final boolean extensions;

    /**
     * Whether dialogue normalization is applied.
     */
    private final boolean dialogueNormalization;

    /**
     * Constructs options from a builder.
     *
     * @param builder the builder holding the values
     */
    private DtsDecoderOptions(Builder builder) {
        this.limits = builder.limits;
        this.extensions = builder.extensions;
        this.dialogueNormalization = builder.dialogueNormalization;
    }

    /**
     * {@return the default options: every extension decoded, no dialogue normalization}
     */
    public static DtsDecoderOptions defaults() {
        return DEFAULTS;
    }

    /**
     * {@return options that decode only the core substream's core audio, as a DTS decoder without extensions
     * would}
     */
    public static DtsDecoderOptions coreOnly() {
        return CORE_ONLY;
    }

    /**
     * Returns a builder initialized with the default options.
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
     * {@return the resource bounds the decoder enforces; {@link MediaLimits#DEFAULT} by default}
     */
    public MediaLimits limits() {
        return limits;
    }

    /**
     * {@return whether the extensions are decoded; {@code true} by default}
     *
     * <p>With extensions, the decoder outputs the channels, sample rate and resolution of the richest presentation
     * the stream carries (XCh, XXCH, X96, XBR or XLL lossless); without, it outputs the core audio alone, and a
     * stream without core audio decodes to nothing.
     */
    public boolean extensions() {
        return extensions;
    }

    /**
     * {@return whether the dialogue normalization gain the stream signals is applied to the output; {@code false} by
     * default}
     *
     * <p>The gain, from 0 to -31 dB, is the core header's ({@code DIALNORM} with {@code VERNUM}) when the core is
     * output, and the audio asset's ({@code nuDialNormCode}) when a stream without core is output; a gain other
     * than 0 dB makes lossless output lossy.
     */
    public boolean dialogueNormalization() {
        return dialogueNormalization;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof DtsDecoderOptions that && limits.equals(that.limits)
                && extensions == that.extensions && dialogueNormalization == that.dialogueNormalization;
    }

    @Override
    public int hashCode() {
        return Objects.hash(limits, extensions, dialogueNormalization);
    }

    @Override
    public String toString() {
        return "DtsDecoderOptions[limits=" + limits + ", extensions=" + extensions + ", dialogueNormalization="
                + dialogueNormalization + "]";
    }

    /**
     * Builds {@link DtsDecoderOptions}.
     *
     * <p>Setters reject only {@code null}; every value is valid. A builder may build several options.
     */
    public static final class Builder {
        /**
         * The resource limits.
         */
        private MediaLimits limits;

        /**
         * Whether the extensions are decoded.
         */
        private boolean extensions;

        /**
         * Whether dialogue normalization is applied.
         */
        private boolean dialogueNormalization;

        /**
         * Constructs a builder of the default options.
         */
        private Builder() {
            this.limits = MediaLimits.DEFAULT;
            this.extensions = true;
        }

        /**
         * Constructs a builder holding the values of options.
         *
         * @param options the options whose values the builder starts from
         */
        private Builder(DtsDecoderOptions options) {
            this.limits = options.limits;
            this.extensions = options.extensions;
            this.dialogueNormalization = options.dialogueNormalization;
        }

        /**
         * Sets the resource bounds; see {@link DtsDecoderOptions#limits()}.
         *
         * @param limits the value
         * @return this builder
         */
        public Builder limits(MediaLimits limits) {
            this.limits = Objects.requireNonNull(limits, "limits");
            return this;
        }

        /**
         * Sets whether the extensions are decoded; see {@link DtsDecoderOptions#extensions()}.
         *
         * @param extensions the value
         * @return this builder
         */
        public Builder extensions(boolean extensions) {
            this.extensions = extensions;
            return this;
        }

        /**
         * Sets whether dialogue normalization is applied; see {@link DtsDecoderOptions#dialogueNormalization()}.
         *
         * @param dialogueNormalization the value
         * @return this builder
         */
        public Builder dialogueNormalization(boolean dialogueNormalization) {
            this.dialogueNormalization = dialogueNormalization;
            return this;
        }

        /**
         * Builds the options.
         *
         * @return the options
         */
        public DtsDecoderOptions build() {
            return new DtsDecoderOptions(this);
        }
    }
}
