package com.github.auties00.smalt.container.raw;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.track.Codec;

import java.util.Objects;

/**
 * Holds the settings of a {@link PcmDemuxer}: the sample format, rate and channel layout of the headerless samples,
 * which a raw PCM file does not record. Instances are immutable and thread-safe; create them with
 * {@link #builder()} or a preset such as {@link #defaults()} or {@link #of(Codec, int, ChannelLayout)}.
 */
public final class PcmDemuxerOptions {
    /**
     * The number of sample frames per packet of {@link #defaults()}.
     */
    public static final int DEFAULT_PACKET_FRAMES = 1024;

    /**
     * The largest number of sample frames per packet.
     */
    public static final int MAX_PACKET_FRAMES = 1 << 20;

    /**
     * The options of {@link #defaults()}.
     */
    private static final PcmDemuxerOptions DEFAULTS = new PcmDemuxerOptions(Codec.PCM_S16LE, 48_000,
            ChannelLayout.STEREO, DEFAULT_PACKET_FRAMES, MediaLimits.DEFAULT);

    private final Codec codec;

    private final int sampleRate;

    private final ChannelLayout layout;

    private final int packetFrames;

    private final MediaLimits limits;

    private PcmDemuxerOptions(Codec codec, int sampleRate, ChannelLayout layout, int packetFrames,
                              MediaLimits limits) {
        this.codec = codec;
        this.sampleRate = sampleRate;
        this.layout = layout;
        this.packetFrames = packetFrames;
        this.limits = limits;
    }

    /**
     * Returns the options of 16-bit little-endian stereo samples at 48 kHz.
     *
     * @return the default options
     */
    public static PcmDemuxerOptions defaults() {
        return DEFAULTS;
    }

    /**
     * Returns the options of samples of a format, rate and layout, otherwise the defaults.
     *
     * @param codec the sample format, a {@linkplain Codec#isPcm() PCM codec}
     * @param sampleRate the sample rate in hertz, positive
     * @param layout the channel layout
     * @return the options
     * @throws IllegalArgumentException if the codec is not PCM or the rate is not positive
     */
    public static PcmDemuxerOptions of(Codec codec, int sampleRate, ChannelLayout layout) {
        return builder().codec(codec).sampleRate(sampleRate).layout(layout).build();
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
     * {@return the sample format, a PCM codec; {@link Codec#PCM_S16LE} by default}
     */
    public Codec codec() {
        return codec;
    }

    /**
     * {@return the sample rate in hertz, positive; 48000 by default}
     */
    public int sampleRate() {
        return sampleRate;
    }

    /**
     * {@return the channel layout, whose channels the sample frames interleave; {@link ChannelLayout#STEREO} by
     * default}
     */
    public ChannelLayout layout() {
        return layout;
    }

    /**
     * {@return the number of sample frames per packet, from 1 to {@value #MAX_PACKET_FRAMES};
     * {@value #DEFAULT_PACKET_FRAMES} by default}
     */
    public int packetFrames() {
        return packetFrames;
    }

    /**
     * {@return the resource bounds the demuxer enforces; {@link MediaLimits#defaults()} by default}
     */
    public MediaLimits limits() {
        return limits;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof PcmDemuxerOptions that && codec == that.codec && sampleRate == that.sampleRate
                && layout.equals(that.layout) && packetFrames == that.packetFrames && limits.equals(that.limits);
    }

    @Override
    public int hashCode() {
        return Objects.hash(codec, sampleRate, layout, packetFrames, limits);
    }

    @Override
    public String toString() {
        return "PcmDemuxerOptions[codec=" + codec + ", sampleRate=" + sampleRate + ", layout=" + layout
                + ", packetFrames=" + packetFrames + ", limits=" + limits + "]";
    }

    /**
     * Assembles {@link PcmDemuxerOptions} setting by setting.
     *
     * <p>Setters reject {@code null} and validate their argument at once. Instances are not thread-safe.
     */
    public static final class Builder {
        private Codec codec;

        private int sampleRate;

        private ChannelLayout layout;

        private int packetFrames;

        private MediaLimits limits;

        private Builder(PcmDemuxerOptions options) {
            this.codec = options.codec;
            this.sampleRate = options.sampleRate;
            this.layout = options.layout;
            this.packetFrames = options.packetFrames;
            this.limits = options.limits;
        }

        /**
         * Sets the sample format; see {@link PcmDemuxerOptions#codec()}.
         *
         * @param codec the value
         * @return this builder
         * @throws IllegalArgumentException if the codec is not PCM
         */
        public Builder codec(Codec codec) {
            Objects.requireNonNull(codec, "codec");
            if (!codec.isPcm()) {
                throw new IllegalArgumentException(codec.displayName() + " is not a PCM sample format");
            }
            this.codec = codec;
            return this;
        }

        /**
         * Sets the sample rate; see {@link PcmDemuxerOptions#sampleRate()}.
         *
         * @param sampleRate the value
         * @return this builder
         * @throws IllegalArgumentException if the rate is not positive
         */
        public Builder sampleRate(int sampleRate) {
            if (sampleRate <= 0) {
                throw new IllegalArgumentException("invalid sample rate " + sampleRate);
            }
            this.sampleRate = sampleRate;
            return this;
        }

        /**
         * Sets the channel layout; see {@link PcmDemuxerOptions#layout()}.
         *
         * @param layout the value
         * @return this builder
         */
        public Builder layout(ChannelLayout layout) {
            this.layout = Objects.requireNonNull(layout, "layout");
            return this;
        }

        /**
         * Sets the number of sample frames per packet; see {@link PcmDemuxerOptions#packetFrames()}.
         *
         * @param frames the value
         * @return this builder
         * @throws IllegalArgumentException if the count is outside 1 to {@value PcmDemuxerOptions#MAX_PACKET_FRAMES}
         */
        public Builder packetFrames(int frames) {
            if (frames < 1 || frames > MAX_PACKET_FRAMES) {
                throw new IllegalArgumentException("packet frames " + frames + " outside 1 to " + MAX_PACKET_FRAMES);
            }
            this.packetFrames = frames;
            return this;
        }

        /**
         * Sets the resource bounds; see {@link PcmDemuxerOptions#limits()}.
         *
         * @param limits the value
         * @return this builder
         */
        public Builder limits(MediaLimits limits) {
            this.limits = Objects.requireNonNull(limits, "limits");
            return this;
        }

        /**
         * Builds the options.
         *
         * @return the options
         */
        public PcmDemuxerOptions build() {
            return new PcmDemuxerOptions(codec, sampleRate, layout, packetFrames, limits);
        }
    }
}
