package com.github.auties00.smalt.audio;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.audio.process.ChannelMixerOptions;
import com.github.auties00.smalt.internal.check.Checks;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Holds the settings of an {@link AudioStreamDecoder}. Instances are immutable and thread-safe;
 * create them with {@link #builder()} or a preset such as {@link #defaults()}.
 */
public final class AudioStreamDecoderOptions {
    private static final AudioStreamDecoderOptions DEFAULTS =
            new AudioStreamDecoderOptions(0, null, MediaLimits.DEFAULT, SampleFormat.FLOAT, false,
                    ChannelMixerOptions.defaults());

    private final int sampleRate;
    private final ChannelLayout layout;
    private final MediaLimits limits;
    private final SampleFormat sampleFormat;
    private final boolean verifyIntegrity;
    private final ChannelMixerOptions mixing;

    private AudioStreamDecoderOptions(int sampleRate, ChannelLayout layout, MediaLimits limits,
                                      SampleFormat sampleFormat, boolean verifyIntegrity, ChannelMixerOptions mixing) {
        this.sampleRate = sampleRate;
        this.layout = layout;
        this.limits = limits;
        this.sampleFormat = sampleFormat;
        this.verifyIntegrity = verifyIntegrity;
        this.mixing = mixing;
    }

    /**
     * Returns options that keep the codec's rate and layout, with {@link MediaLimits#DEFAULT}.
     *
     * @return the default options
     */
    public static AudioStreamDecoderOptions defaults() {
        return DEFAULTS;
    }

    /**
     * Returns the default options converting to a rate and layout.
     *
     * @param sampleRate the output sampling rate in hertz, positive
     * @param layout the output channel layout
     * @return the options
     * @throws IllegalArgumentException if {@code sampleRate} is not positive
     */
    public static AudioStreamDecoderOptions of(int sampleRate, ChannelLayout layout) {
        return builder().sampleRate(sampleRate).layout(layout).build();
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
     * {@return the output sampling rate in hertz, positive, or empty to keep the codec's; empty
     * by default}
     */
    public OptionalInt sampleRate() {
        return sampleRate == 0 ? OptionalInt.empty() : OptionalInt.of(sampleRate);
    }

    /**
     * {@return the output channel layout, or empty to keep the codec's; empty by default}
     */
    public Optional<ChannelLayout> layout() {
        return Optional.ofNullable(layout);
    }

    /**
     * {@return the resource bounds the codec enforces; {@link MediaLimits#DEFAULT} by default}
     */
    public MediaLimits limits() {
        return limits;
    }

    /**
     * {@return the representation of the output samples; {@link SampleFormat#FLOAT} by default}
     *
     * <p>{@link SampleFormat#INTEGER} and {@link SampleFormat#DOUBLE} output the codec's own rate and
     * layout, so they cannot be combined with a {@link #sampleRate()} or {@link #layout()} that
     * differs from the codec's, and {@code INTEGER} needs a codec with integer output.
     */
    public SampleFormat sampleFormat() {
        return sampleFormat;
    }

    /**
     * {@return whether the codec checks the whole-stream checksum the stream carries; {@code false}
     * by default}
     *
     * @see AudioDecoderOptions#verifyIntegrity()
     */
    public boolean verifyIntegrity() {
        return verifyIntegrity;
    }

    /**
     * {@return how decoded channels are mixed to {@link #layout()}; {@link ChannelMixerOptions#defaults()}
     * by default}
     *
     * <p>Decoded blocks whose layout equals the output layout are not mixed, unless the options hold a
     * caller's matrix, which is then applied to every block (and which needs
     * {@link SampleFormat#FLOAT} output).
     */
    public ChannelMixerOptions mixing() {
        return mixing;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof AudioStreamDecoderOptions that
                && sampleRate == that.sampleRate
                && Objects.equals(layout, that.layout)
                && limits.equals(that.limits)
                && sampleFormat == that.sampleFormat
                && verifyIntegrity == that.verifyIntegrity
                && mixing.equals(that.mixing);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sampleRate, layout, limits, sampleFormat, verifyIntegrity, mixing);
    }

    @Override
    public String toString() {
        return "AudioStreamDecoderOptions[sampleRate=" + sampleRate + ", layout=" + layout + ", limits=" + limits
                + ", sampleFormat=" + sampleFormat + ", verifyIntegrity=" + verifyIntegrity + ", mixing=" + mixing + "]";
    }

    /**
     * Builds {@link AudioStreamDecoderOptions}.
     *
     * <p>Setters store their value; {@link #build()} checks the ranges. Instances are not
     * thread-safe.
     */
    public static final class Builder {
        private OptionalInt sampleRate;
        private ChannelLayout layout;
        private MediaLimits limits;
        private SampleFormat sampleFormat;
        private boolean verifyIntegrity;
        private ChannelMixerOptions mixing;

        private Builder(AudioStreamDecoderOptions options) {
            this.sampleRate = options.sampleRate();
            this.layout = options.layout;
            this.limits = options.limits;
            this.sampleFormat = options.sampleFormat;
            this.verifyIntegrity = options.verifyIntegrity;
            this.mixing = options.mixing;
        }

        /**
         * Sets the output sampling rate; see {@link AudioStreamDecoderOptions#sampleRate()}.
         *
         * @param sampleRate the value
         * @return this builder
         */
        public Builder sampleRate(int sampleRate) {
            this.sampleRate = OptionalInt.of(sampleRate);
            return this;
        }

        /**
         * Keeps the codec's sampling rate; see {@link AudioStreamDecoderOptions#sampleRate()}.
         *
         * @return this builder
         */
        public Builder sourceSampleRate() {
            this.sampleRate = OptionalInt.empty();
            return this;
        }

        /**
         * Sets the output channel layout; see {@link AudioStreamDecoderOptions#layout()}.
         *
         * @param layout the value
         * @return this builder
         */
        public Builder layout(ChannelLayout layout) {
            this.layout = Objects.requireNonNull(layout, "layout");
            return this;
        }

        /**
         * Keeps the codec's channel layout; see {@link AudioStreamDecoderOptions#layout()}.
         *
         * @return this builder
         */
        public Builder sourceLayout() {
            this.layout = null;
            return this;
        }

        /**
         * Sets the resource bounds; see {@link AudioStreamDecoderOptions#limits()}.
         *
         * @param limits the value
         * @return this builder
         */
        public Builder limits(MediaLimits limits) {
            this.limits = Objects.requireNonNull(limits, "limits");
            return this;
        }

        /**
         * Sets the representation of the output samples; see
         * {@link AudioStreamDecoderOptions#sampleFormat()}.
         *
         * @param format the value
         * @return this builder
         */
        public Builder sampleFormat(SampleFormat format) {
            this.sampleFormat = Objects.requireNonNull(format, "format");
            return this;
        }

        /**
         * Sets whether the whole-stream checksum is checked; see
         * {@link AudioStreamDecoderOptions#verifyIntegrity()}.
         *
         * @param verify the value
         * @return this builder
         */
        public Builder verifyIntegrity(boolean verify) {
            this.verifyIntegrity = verify;
            return this;
        }

        /**
         * Sets how decoded channels are mixed; see {@link AudioStreamDecoderOptions#mixing()}.
         *
         * @param mixing the value
         * @return this builder
         */
        public Builder mixing(ChannelMixerOptions mixing) {
            this.mixing = Objects.requireNonNull(mixing, "mixing");
            return this;
        }

        /**
         * Returns options holding the settings of this builder.
         *
         * @return the options
         * @throws IllegalArgumentException if the sample rate is not positive, or the mixing holds a
         * caller's matrix and the sample format is not {@link SampleFormat#FLOAT}
         */
        public AudioStreamDecoderOptions build() {
            var rate = sampleRate.isPresent() ? Checks.positive("sample rate", sampleRate.getAsInt()) : 0;
            if (mixing.matrix().isPresent() && sampleFormat != SampleFormat.FLOAT) {
                throw new IllegalArgumentException("a mix matrix needs FLOAT output, not " + sampleFormat);
            }
            return new AudioStreamDecoderOptions(rate, layout, limits, sampleFormat, verifyIntegrity, mixing);
        }
    }
}
