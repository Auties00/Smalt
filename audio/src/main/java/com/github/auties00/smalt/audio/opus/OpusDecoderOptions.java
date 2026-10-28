package com.github.auties00.smalt.audio.opus;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.internal.check.Checks;

import java.util.Objects;
import java.util.OptionalInt;

/**
 * Holds the settings of an {@link OpusDecoder}. Instances are immutable and thread-safe; create them
 * with {@link #builder()} or a preset such as {@link #defaults()}.
 */
public final class OpusDecoderOptions {
    /**
     * The options of {@link #defaults()}.
     */
    private static final OpusDecoderOptions DEFAULTS = new Builder().build();

    private final MediaLimits limits;

    private final int sampleRate;

    /**
     * The number of output channels, or {@code 0} for the count the source declares.
     */
    private final int channels;

    private OpusDecoderOptions(Builder builder) {
        this.limits = builder.limits;
        this.sampleRate = builder.sampleRate;
        this.channels = builder.channels == null ? 0 : builder.channels;
    }

    /**
     * Returns the options for decoding at 48 kHz into the channel count the source declares, under
     * {@link MediaLimits#DEFAULT}.
     *
     * @return the default options
     */
    public static OpusDecoderOptions defaults() {
        return DEFAULTS;
    }

    /**
     * Returns the options for decoding at a rate into a number of channels under
     * {@link MediaLimits#DEFAULT}, the usual setup of a real-time receiver.
     *
     * @param sampleRate the output sampling rate: 8000, 12000, 16000, 24000 or 48000 Hz
     * @param channels the number of output channels: 1 or 2
     * @return the options
     * @throws IllegalArgumentException if the rate or the channel count is not supported
     */
    public static OpusDecoderOptions of(int sampleRate, int channels) {
        return new Builder().sampleRate(sampleRate).channels(channels).build();
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
     * {@return the resource bounds the channel count of the {@code OpusHead} is checked against;
     * {@link MediaLimits#DEFAULT} by default}
     */
    public MediaLimits limits() {
        return limits;
    }

    /**
     * {@return the output sampling rate: 8000, 12000, 16000, 24000 or 48000 Hz; 48000 by default}
     *
     * <p>Opus decodes natively at each of these rates, whatever rate the stream was encoded at.
     */
    public int sampleRate() {
        return sampleRate;
    }

    /**
     * {@return the number of output channels, 1 or 2, or empty (the default) for the count the
     * track's {@code OpusHead} declares, which is stereo for a decoder created without a track}
     *
     * <p>A single mono or stereo stream decodes to another count natively; any other stream, a
     * multichannel or ambisonic one included, is mixed down to {@link
     * com.github.auties00.smalt.audio.ChannelLayout#MONO MONO} or {@link
     * com.github.auties00.smalt.audio.ChannelLayout#STEREO STEREO} by the matrices the {@link OpusDecoder}
     * class describes.
     */
    public OptionalInt channels() {
        return channels == 0 ? OptionalInt.empty() : OptionalInt.of(channels);
    }

    @Override
    public boolean equals(Object object) {
        return object instanceof OpusDecoderOptions that
                && limits.equals(that.limits)
                && sampleRate == that.sampleRate
                && channels == that.channels;
    }

    @Override
    public int hashCode() {
        return Objects.hash(limits, sampleRate, channels);
    }

    @Override
    public String toString() {
        return "OpusDecoderOptions[limits=" + limits
                + ", sampleRate=" + sampleRate
                + ", channels=" + (channels == 0 ? "source" : String.valueOf(channels)) + "]";
    }

    /**
     * A builder of {@link OpusDecoderOptions}.
     *
     * <p>Setters reject only {@code null}; {@link #build()} checks the ranges. Instances are not
     * thread-safe.
     */
    public static final class Builder {
        private MediaLimits limits;

        private int sampleRate;

        /**
         * The number of output channels, or {@code null} for the count the source declares.
         */
        private Integer channels;

        private Builder() {
            this.limits = MediaLimits.DEFAULT;
            this.sampleRate = 48_000;
        }

        private Builder(OpusDecoderOptions options) {
            this.limits = options.limits;
            this.sampleRate = options.sampleRate;
            this.channels = options.channels == 0 ? null : options.channels;
        }

        /**
         * Sets the resource bounds; see {@link OpusDecoderOptions#limits()}.
         *
         * @param limits the value
         * @return this builder
         */
        public Builder limits(MediaLimits limits) {
            this.limits = Objects.requireNonNull(limits, "limits");
            return this;
        }

        /**
         * Sets the output sampling rate; see {@link OpusDecoderOptions#sampleRate()}.
         *
         * @param sampleRate the value
         * @return this builder
         */
        public Builder sampleRate(int sampleRate) {
            this.sampleRate = sampleRate;
            return this;
        }

        /**
         * Sets the number of output channels; see {@link OpusDecoderOptions#channels()}.
         *
         * @param channels the value
         * @return this builder
         */
        public Builder channels(int channels) {
            this.channels = channels;
            return this;
        }

        /**
         * Selects the channel count the source declares; see {@link OpusDecoderOptions#channels()}.
         *
         * @return this builder
         */
        public Builder sourceChannels() {
            this.channels = null;
            return this;
        }

        /**
         * Returns the options holding the settings of this builder.
         *
         * @return the options
         * @throws IllegalArgumentException if the sampling rate is not 8000, 12000, 16000, 24000 or
         * 48000 Hz, or the channel count is set to a value other than 1 or 2
         */
        public OpusDecoderOptions build() {
            checkSampleRate("output sample rate", sampleRate);
            if (channels != null) {
                Checks.inRange("channel count", channels, 1, 2);
            }
            return new OpusDecoderOptions(this);
        }
    }

    /**
     * Checks that a sampling rate is one of the five rates Opus codes natively.
     *
     * @param name the setting, for the message
     * @param sampleRate the rate in hertz
     * @throws IllegalArgumentException if the rate is not 8000, 12000, 16000, 24000 or 48000 Hz
     */
    static void checkSampleRate(String name, int sampleRate) {
        switch (sampleRate) {
            case 8_000, 12_000, 16_000, 24_000, 48_000 -> {
            }
            default -> throw new IllegalArgumentException(name + " " + sampleRate
                    + " is not one of 8000, 12000, 16000, 24000, 48000");
        }
    }
}
