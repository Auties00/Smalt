package com.github.auties00.smalt.audio.pcm;

import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.internal.check.Checks;
import com.github.auties00.smalt.track.Codec;

import java.util.Objects;

/**
 * Holds the settings of a {@link PcmEncoder}. Instances are immutable and thread-safe; create them
 * with {@link #builder()} or a preset such as {@link #defaults()}.
 */
public final class PcmEncoderOptions {
    /**
     * The default number of frames per packet.
     */
    public static final int DEFAULT_FRAMES_PER_PACKET = 1024;

    /**
     * The largest number of frames per packet.
     */
    public static final int MAX_FRAMES_PER_PACKET = 1 << 20;

    private static final PcmEncoderOptions DEFAULTS = new PcmEncoderOptions(Codec.PCM_S16LE, 48_000, ChannelLayout.STEREO,
            DEFAULT_FRAMES_PER_PACKET);

    private final Codec codec;
    private final int sampleRate;
    private final ChannelLayout layout;
    private final int framesPerPacket;

    private PcmEncoderOptions(Codec codec, int sampleRate, ChannelLayout layout, int framesPerPacket) {
        this.codec = codec;
        this.sampleRate = sampleRate;
        this.layout = layout;
        this.framesPerPacket = framesPerPacket;
    }

    /**
     * Returns options producing signed 16-bit little-endian samples from 48 kHz stereo input, in
     * packets of {@value #DEFAULT_FRAMES_PER_PACKET} frames.
     *
     * @return the default options
     */
    public static PcmEncoderOptions defaults() {
        return DEFAULTS;
    }

    /**
     * Returns options producing a sample format from input of a rate and layout, in packets of
     * {@value #DEFAULT_FRAMES_PER_PACKET} frames.
     *
     * @param codec the sample format, a {@code PCM_*} codec
     * @param sampleRate the input rate in hertz, positive
     * @param layout the input layout
     * @return the options
     * @throws IllegalArgumentException if {@code codec} is not a PCM codec or {@code sampleRate}
     * is not positive
     */
    public static PcmEncoderOptions of(Codec codec, int sampleRate, ChannelLayout layout) {
        return builder().codec(codec).format(sampleRate, layout).build();
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
     * {@return the sample format produced, one of the {@code PCM_*} {@link Codec}s;
     * {@link Codec#PCM_S16LE} by default}
     */
    public Codec codec() {
        return codec;
    }

    /**
     * {@return the sampling rate of the input in hertz, positive; 48000 by default}
     */
    public int sampleRate() {
        return sampleRate;
    }

    /**
     * {@return the channel layout of the input; {@link ChannelLayout#STEREO} by default}
     */
    public ChannelLayout layout() {
        return layout;
    }

    /**
     * {@return the number of frames grouped into one packet, from 1 to {@value #MAX_FRAMES_PER_PACKET};
     * {@value #DEFAULT_FRAMES_PER_PACKET} by default}
     *
     * <p>PCM has no codec frames, so the packet size only decides how the samples are split into
     * {@link com.github.auties00.smalt.track.Packet}s; containers that store samples contiguously
     * (WAV, AIFF, raw PCM) produce the same bytes whatever it is.
     */
    public int framesPerPacket() {
        return framesPerPacket;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof PcmEncoderOptions that
                && codec == that.codec
                && sampleRate == that.sampleRate
                && layout.equals(that.layout)
                && framesPerPacket == that.framesPerPacket;
    }

    @Override
    public int hashCode() {
        return Objects.hash(codec, sampleRate, layout, framesPerPacket);
    }

    @Override
    public String toString() {
        return "PcmEncoderOptions[codec=" + codec + ", sampleRate=" + sampleRate + ", layout=" + layout
                + ", framesPerPacket=" + framesPerPacket + "]";
    }

    /**
     * Builds {@link PcmEncoderOptions}.
     *
     * <p>Setters store their value; {@link #build()} checks the ranges. Instances are not
     * thread-safe.
     */
    public static final class Builder {
        private Codec codec;
        private int sampleRate;
        private ChannelLayout layout;
        private int framesPerPacket;

        private Builder(PcmEncoderOptions options) {
            this.codec = options.codec;
            this.sampleRate = options.sampleRate;
            this.layout = options.layout;
            this.framesPerPacket = options.framesPerPacket;
        }

        /**
         * Sets the sample format; see {@link PcmEncoderOptions#codec()}.
         *
         * @param codec the value
         * @return this builder
         */
        public Builder codec(Codec codec) {
            this.codec = Objects.requireNonNull(codec, "codec");
            return this;
        }

        /**
         * Sets the sampling rate of the input; see {@link PcmEncoderOptions#sampleRate()}.
         *
         * @param sampleRate the value
         * @return this builder
         */
        public Builder sampleRate(int sampleRate) {
            this.sampleRate = sampleRate;
            return this;
        }

        /**
         * Sets the channel layout of the input; see {@link PcmEncoderOptions#layout()}.
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
         * {@link PcmEncoderOptions#sampleRate()} and {@link PcmEncoderOptions#layout()}.
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
         * Sets the number of frames per packet; see {@link PcmEncoderOptions#framesPerPacket()}.
         *
         * @param framesPerPacket the value
         * @return this builder
         */
        public Builder framesPerPacket(int framesPerPacket) {
            this.framesPerPacket = framesPerPacket;
            return this;
        }

        /**
         * Returns options holding the settings of this builder.
         *
         * @return the options
         * @throws IllegalArgumentException if the codec is not a PCM codec, the sample rate is not
         * positive or the packet size is outside 1..{@value PcmEncoderOptions#MAX_FRAMES_PER_PACKET}
         */
        public PcmEncoderOptions build() {
            if (!codec.isPcm()) {
                throw new IllegalArgumentException(codec.displayName() + " is not a PCM codec");
            }
            Checks.positive("sample rate", sampleRate);
            Checks.inRange("frames per packet", framesPerPacket, 1, MAX_FRAMES_PER_PACKET);
            return new PcmEncoderOptions(codec, sampleRate, layout, framesPerPacket);
        }
    }
}
