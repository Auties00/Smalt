package com.github.auties00.smalt.audio;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.internal.check.Checks;

import java.util.Objects;
import java.util.OptionalInt;

/**
 * Holds the settings of an audio decoder created by {@link AudioDecoders}. Instances are immutable
 * and thread-safe; create them with {@link #builder()} or a preset such as {@link #defaults()}.
 */
public final class AudioDecoderOptions {
    private static final AudioDecoderOptions DEFAULTS = new AudioDecoderOptions(MediaLimits.DEFAULT, 0);

    private final MediaLimits limits;
    private final int preferredSampleRate;

    private AudioDecoderOptions(MediaLimits limits, int preferredSampleRate) {
        this.limits = limits;
        this.preferredSampleRate = preferredSampleRate;
    }

    /**
     * Returns decoding at the codec's native rate under {@link MediaLimits#DEFAULT}.
     *
     * @return the default options
     */
    public static AudioDecoderOptions defaults() {
        return DEFAULTS;
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
     * {@return the resource bounds the decoder enforces; {@link MediaLimits#DEFAULT} by default}
     */
    public MediaLimits limits() {
        return limits;
    }

    /**
     * {@return the rate in hertz the caller will resample the output to, positive, or empty to decode
     * at the codec's native rate; empty by default}
     *
     * <p>A codec that decodes natively at several rates (only Opus, at 8, 12, 16, 24 or 48 kHz)
     * decodes at the preferred rate when it supports that rate; every other codec ignores the
     * preference and outputs its native rate, so callers check {@link AudioDecoder#sampleRate()}.
     */
    public OptionalInt preferredSampleRate() {
        return preferredSampleRate == 0 ? OptionalInt.empty() : OptionalInt.of(preferredSampleRate);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof AudioDecoderOptions that
                && limits.equals(that.limits)
                && preferredSampleRate == that.preferredSampleRate;
    }

    @Override
    public int hashCode() {
        return Objects.hash(limits, preferredSampleRate);
    }

    @Override
    public String toString() {
        return "AudioDecoderOptions[limits=" + limits + ", preferredSampleRate=" + preferredSampleRate + "]";
    }

    /**
     * Builds {@link AudioDecoderOptions}.
     *
     * <p>Setters store their value; {@link #build()} checks the ranges. Instances are not
     * thread-safe.
     */
    public static final class Builder {
        private MediaLimits limits;
        private OptionalInt preferredSampleRate;

        private Builder(AudioDecoderOptions options) {
            this.limits = options.limits;
            this.preferredSampleRate = options.preferredSampleRate();
        }

        /**
         * Sets the resource bounds; see {@link AudioDecoderOptions#limits()}.
         *
         * @param limits the value
         * @return this builder
         */
        public Builder limits(MediaLimits limits) {
            this.limits = Objects.requireNonNull(limits, "limits");
            return this;
        }

        /**
         * Sets the preferred output rate; see {@link AudioDecoderOptions#preferredSampleRate()}.
         *
         * @param sampleRate the value
         * @return this builder
         */
        public Builder preferredSampleRate(int sampleRate) {
            this.preferredSampleRate = OptionalInt.of(sampleRate);
            return this;
        }

        /**
         * Clears the preferred output rate, so the codec decodes at its native rate; see
         * {@link AudioDecoderOptions#preferredSampleRate()}.
         *
         * @return this builder
         */
        public Builder nativeSampleRate() {
            this.preferredSampleRate = OptionalInt.empty();
            return this;
        }

        /**
         * Returns options holding the settings of this builder.
         *
         * @return the options
         * @throws IllegalArgumentException if the preferred sample rate is not positive
         */
        public AudioDecoderOptions build() {
            var rate = preferredSampleRate.isPresent()
                    ? Checks.positive("preferred sample rate", preferredSampleRate.getAsInt()) : 0;
            return new AudioDecoderOptions(limits, rate);
        }
    }
}
