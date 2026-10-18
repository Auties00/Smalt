package com.github.auties00.smalt.audio;

import com.github.auties00.smalt.internal.check.Checks;

import java.util.Objects;
import java.util.OptionalInt;

/**
 * Holds the codec-independent settings of an audio encoder created by {@link AudioEncoders}.
 * Instances are immutable and thread-safe; create them with {@link #builder()} or a preset such as
 * {@link #defaults()}.
 *
 * @apiNote These options carry only what every audio codec understands; a codec maps them onto its
 * own configuration, and a caller that needs a codec's other parameters constructs that codec's
 * encoder directly.
 */
public final class AudioEncoderOptions {
    private static final AudioEncoderOptions DEFAULTS = new AudioEncoderOptions(48_000, ChannelLayout.STEREO, 0, false, 0);

    private final int sampleRate;
    private final ChannelLayout layout;
    private final int bitrate;
    private final boolean realtime;
    private final int bitsPerSample;

    private AudioEncoderOptions(int sampleRate, ChannelLayout layout, int bitrate, boolean realtime, int bitsPerSample) {
        this.sampleRate = sampleRate;
        this.layout = layout;
        this.bitrate = bitrate;
        this.realtime = realtime;
        this.bitsPerSample = bitsPerSample;
    }

    /**
     * Returns options for 48 kHz stereo input at the codec's default bitrate, tuned for files.
     *
     * @return the default options
     */
    public static AudioEncoderOptions defaults() {
        return DEFAULTS;
    }

    /**
     * Returns options for input of a rate and layout at the codec's default bitrate, tuned for
     * files.
     *
     * @param sampleRate the input rate in hertz, positive
     * @param layout the input layout
     * @return the options
     * @throws IllegalArgumentException if {@code sampleRate} is not positive
     */
    public static AudioEncoderOptions of(int sampleRate, ChannelLayout layout) {
        return builder().format(sampleRate, layout).build();
    }

    /**
     * Returns options for input of a rate and layout at the codec's default bitrate, tuned for
     * interactive transmission such as a call.
     *
     * @param sampleRate the input rate in hertz, positive
     * @param layout the input layout
     * @return the options
     * @throws IllegalArgumentException if {@code sampleRate} is not positive
     */
    public static AudioEncoderOptions forRealtime(int sampleRate, ChannelLayout layout) {
        return builder().format(sampleRate, layout).realtime(true).build();
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
     * {@return the sampling rate of the input in hertz, positive; 48000 by default}
     *
     * <p>A codec rejects a rate it cannot code with {@link IllegalArgumentException} when the
     * encoder is created.
     */
    public int sampleRate() {
        return sampleRate;
    }

    /**
     * {@return the channel layout of the input; {@link ChannelLayout#STEREO} by default}
     *
     * <p>A codec rejects a layout it cannot code with {@link IllegalArgumentException} when the
     * encoder is created.
     */
    public ChannelLayout layout() {
        return layout;
    }

    /**
     * {@return the target bitrate in bits per second, positive, or empty for the codec's default;
     * empty by default}
     *
     * <p>The target is a request: each codec approximates it, and a codec with a bounded range
     * clamps it into that range. A codec that cannot code the requested target at all rejects it
     * with {@link IllegalArgumentException} when the encoder is created.
     */
    public OptionalInt bitrate() {
        return bitrate == 0 ? OptionalInt.empty() : OptionalInt.of(bitrate);
    }

    /**
     * {@return whether the encoder is tuned for interactive transmission; {@code false} by default}
     */
    public boolean realtime() {
        return realtime;
    }

    /**
     * {@return the sample size a lossless encoder codes in bits, from 1 to 32, or empty for the
     * codec's default; empty by default}
     *
     * <p>Lossy codecs ignore it; FLAC codes samples of this size, so that a 24-bit source stays
     * 24-bit and a 16-bit one is not padded. It is typically the source's sample size.
     */
    public OptionalInt bitsPerSample() {
        return bitsPerSample == 0 ? OptionalInt.empty() : OptionalInt.of(bitsPerSample);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof AudioEncoderOptions that
                && sampleRate == that.sampleRate
                && layout.equals(that.layout)
                && bitrate == that.bitrate
                && realtime == that.realtime
                && bitsPerSample == that.bitsPerSample;
    }

    @Override
    public int hashCode() {
        return Objects.hash(sampleRate, layout, bitrate, realtime, bitsPerSample);
    }

    @Override
    public String toString() {
        return "AudioEncoderOptions[sampleRate=" + sampleRate + ", layout=" + layout + ", bitrate=" + bitrate
                + ", realtime=" + realtime + ", bitsPerSample=" + bitsPerSample + "]";
    }

    /**
     * Builds {@link AudioEncoderOptions}.
     *
     * <p>Setters store their value; {@link #build()} checks the ranges. Instances are not
     * thread-safe.
     */
    public static final class Builder {
        private int sampleRate;
        private ChannelLayout layout;
        private OptionalInt bitrate;
        private boolean realtime;
        private OptionalInt bitsPerSample;

        private Builder(AudioEncoderOptions options) {
            this.sampleRate = options.sampleRate;
            this.layout = options.layout;
            this.bitrate = options.bitrate();
            this.realtime = options.realtime;
            this.bitsPerSample = options.bitsPerSample();
        }

        /**
         * Sets the sampling rate of the input; see {@link AudioEncoderOptions#sampleRate()}.
         *
         * @param sampleRate the value
         * @return this builder
         */
        public Builder sampleRate(int sampleRate) {
            this.sampleRate = sampleRate;
            return this;
        }

        /**
         * Sets the channel layout of the input; see {@link AudioEncoderOptions#layout()}.
         *
         * @param layout the value
         * @return this builder
         */
        public Builder layout(ChannelLayout layout) {
            this.layout = Objects.requireNonNull(layout, "layout");
            return this;
        }

        /**
         * Sets the sampling rate and channel layout of the input; see
         * {@link AudioEncoderOptions#sampleRate()} and {@link AudioEncoderOptions#layout()}.
         *
         * @param sampleRate the sampling rate
         * @param layout the channel layout
         * @return this builder
         */
        public Builder format(int sampleRate, ChannelLayout layout) {
            this.sampleRate = sampleRate;
            this.layout = Objects.requireNonNull(layout, "layout");
            return this;
        }

        /**
         * Sets the target bitrate; see {@link AudioEncoderOptions#bitrate()}.
         *
         * @param bitrate the value
         * @return this builder
         */
        public Builder bitrate(int bitrate) {
            this.bitrate = OptionalInt.of(bitrate);
            return this;
        }

        /**
         * Selects the codec's default bitrate; see {@link AudioEncoderOptions#bitrate()}.
         *
         * @return this builder
         */
        public Builder automaticBitrate() {
            this.bitrate = OptionalInt.empty();
            return this;
        }

        /**
         * Sets whether the encoder is tuned for interactive transmission; see
         * {@link AudioEncoderOptions#realtime()}.
         *
         * @param realtime the value
         * @return this builder
         */
        public Builder realtime(boolean realtime) {
            this.realtime = realtime;
            return this;
        }

        /**
         * Sets the sample size a lossless encoder codes; see {@link AudioEncoderOptions#bitsPerSample()}.
         *
         * @param bitsPerSample the value
         * @return this builder
         */
        public Builder bitsPerSample(int bitsPerSample) {
            this.bitsPerSample = OptionalInt.of(bitsPerSample);
            return this;
        }

        /**
         * Selects the codec's default lossless sample size; see
         * {@link AudioEncoderOptions#bitsPerSample()}.
         *
         * @return this builder
         */
        public Builder automaticBitsPerSample() {
            this.bitsPerSample = OptionalInt.empty();
            return this;
        }

        /**
         * Returns options holding the settings of this builder.
         *
         * @return the options
         * @throws IllegalArgumentException if the sample rate or the bitrate is not positive, or the
         * sample size is outside 1..32
         */
        public AudioEncoderOptions build() {
            Checks.positive("sample rate", sampleRate);
            var checkedBitrate = bitrate.isPresent() ? Checks.positive("bitrate", bitrate.getAsInt()) : 0;
            var checkedSize = bitsPerSample.isPresent()
                    ? Checks.inRange("sample size", bitsPerSample.getAsInt(), 1, 32) : 0;
            return new AudioEncoderOptions(sampleRate, layout, checkedBitrate, realtime, checkedSize);
        }
    }
}
