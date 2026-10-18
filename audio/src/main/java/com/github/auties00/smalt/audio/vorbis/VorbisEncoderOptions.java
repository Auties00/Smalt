package com.github.auties00.smalt.audio.vorbis;

import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.vorbis.internal.CodecSetup;
import com.github.auties00.smalt.internal.check.Checks;

import java.util.Objects;
import java.util.OptionalInt;

/**
 * Holds the settings of a {@link VorbisEncoder}. Instances are immutable and thread-safe; create
 * them with {@link #builder()} or a preset such as {@link #defaults()}.
 */
public final class VorbisEncoderOptions {
    /**
     * The quality of {@link #defaults()}.
     */
    public static final float DEFAULT_QUALITY = 3;

    /**
     * The default options: 44.1 kHz stereo at quality 3. They are built without consulting the
     * encoder settings, which are known to cover them.
     */
    private static final VorbisEncoderOptions DEFAULTS = new VorbisEncoderOptions(new Builder());

    private final int sampleRate;

    private final ChannelLayout layout;

    private final float quality;

    /**
     * The nominal bitrate in bits per second, {@code 0} when the quality applies.
     */
    private final int nominalBitrate;

    private VorbisEncoderOptions(Builder builder) {
        this.sampleRate = builder.sampleRate;
        this.layout = builder.layout;
        this.quality = builder.quality;
        this.nominalBitrate = builder.nominalBitrate == null ? 0 : builder.nominalBitrate;
    }

    /**
     * Returns quality 3 for 44.1 kHz stereo input.
     *
     * @return the default options
     */
    public static VorbisEncoderOptions defaults() {
        return DEFAULTS;
    }

    /**
     * Returns the default quality for an input format.
     *
     * @param sampleRate the sampling rate in hertz
     * @param layout the channel layout, 1 to 255 channels
     * @return the options
     * @throws IllegalArgumentException if the format is not codable
     */
    public static VorbisEncoderOptions of(int sampleRate, ChannelLayout layout) {
        return new Builder().format(sampleRate, layout).build();
    }

    /**
     * Returns options at a quality for 44.1 kHz stereo input.
     *
     * @param quality the quality, from -1 (smallest) to 10 (best)
     * @return the options
     * @throws IllegalArgumentException if {@code quality} is out of range
     */
    public static VorbisEncoderOptions ofQuality(float quality) {
        return new Builder().quality(quality).build();
    }

    /**
     * Returns options approximating a nominal bitrate for 44.1 kHz stereo input.
     *
     * @param bitrate the nominal bitrate in bits per second, from 45 to 500 kbit/s for 44.1 kHz
     * stereo
     * @return the options
     * @throws IllegalArgumentException if no setting reaches the bitrate
     */
    public static VorbisEncoderOptions ofNominalBitrate(int bitrate) {
        return new Builder().nominalBitrate(bitrate).build();
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
     * {@return the input sampling rate in hertz, from 1 to 200000; 44100 by default}
     *
     * <p>Every rate codes at every quality. Settings tuned for the rate exist from 8 to 50 kHz
     * (around 8, 11.025, 16, 22.05, 32, 44.1 and 48 kHz); rates outside that range use generic
     * settings, and a {@linkplain #nominalBitrate() nominal bitrate} can be requested only within
     * it.
     */
    public int sampleRate() {
        return sampleRate;
    }

    /**
     * {@return the input channel layout, 1 to 255 channels; {@link ChannelLayout#STEREO} by
     * default}
     *
     * <p>Stereo is coupled (lossless and lossy square-polar stereo); other channel counts are coded
     * independently.
     */
    public ChannelLayout layout() {
        return layout;
    }

    /**
     * {@return the quality on the {@code oggenc} scale from -1 (smallest) to 10 (best), fractional
     * values included, used when no {@link #nominalBitrate()} is set; {@link #DEFAULT_QUALITY}, 3, by
     * default}
     *
     * <table class="striped">
     * <caption>Typical bitrates for 44.1 kHz stereo music</caption>
     * <thead><tr><th scope="col">Quality</th><th scope="col">Nominal bitrate</th></tr></thead>
     * <tbody>
     * <tr><th scope="row">-1</th><td>45 kbit/s</td></tr>
     * <tr><th scope="row">0</th><td>64 kbit/s</td></tr>
     * <tr><th scope="row">3</th><td>112 kbit/s</td></tr>
     * <tr><th scope="row">5</th><td>160 kbit/s</td></tr>
     * <tr><th scope="row">8</th><td>256 kbit/s</td></tr>
     * <tr><th scope="row">10</th><td>500 kbit/s</td></tr>
     * </tbody>
     * </table>
     */
    public float quality() {
        return quality;
    }

    /**
     * {@return the nominal bitrate the quality is chosen from, in bits per second, or empty (the
     * default) when {@link #quality()} applies}
     *
     * <p>The encoder picks the quality whose typical bitrate matches the nominal bitrate and codes
     * at that quality, so the real bitrate still follows the signal; the stream is not constrained
     * to the nominal bitrate.
     */
    public OptionalInt nominalBitrate() {
        return nominalBitrate == 0 ? OptionalInt.empty() : OptionalInt.of(nominalBitrate);
    }

    @Override
    public boolean equals(Object object) {
        return object instanceof VorbisEncoderOptions that && sampleRate == that.sampleRate
                && layout.equals(that.layout) && Float.compare(quality, that.quality) == 0
                && nominalBitrate == that.nominalBitrate;
    }

    @Override
    public int hashCode() {
        return Objects.hash(sampleRate, layout, quality, nominalBitrate);
    }

    @Override
    public String toString() {
        return "VorbisEncoderOptions[sampleRate=" + sampleRate + ", layout=" + layout + ", "
                + (nominalBitrate == 0 ? "quality=" + quality : "nominalBitrate=" + nominalBitrate) + "]";
    }

    /**
     * A builder of {@link VorbisEncoderOptions}.
     *
     * <p>Setters reject only {@code null}; {@link #build()} checks the ranges and that an encoder
     * setting covers the result. Instances are not thread-safe.
     */
    public static final class Builder {
        private int sampleRate = 44_100;

        private ChannelLayout layout = ChannelLayout.STEREO;

        private float quality = DEFAULT_QUALITY;

        /**
         * The nominal bitrate, or {@code null} when the quality applies.
         */
        private Integer nominalBitrate;

        private Builder() {
        }

        private Builder(VorbisEncoderOptions options) {
            this.sampleRate = options.sampleRate;
            this.layout = options.layout;
            this.quality = options.quality;
            this.nominalBitrate = options.nominalBitrate == 0 ? null : options.nominalBitrate;
        }

        /**
         * Sets the input format; see {@link VorbisEncoderOptions#sampleRate()} and
         * {@link VorbisEncoderOptions#layout()}.
         *
         * @param sampleRate the sampling rate
         * @param layout the channel layout
         * @return this builder
         */
        public Builder format(int sampleRate, ChannelLayout layout) {
            this.layout = Objects.requireNonNull(layout, "layout");
            this.sampleRate = sampleRate;
            return this;
        }

        /**
         * Sets the quality and clears any nominal bitrate; see
         * {@link VorbisEncoderOptions#quality()}.
         *
         * @param quality the value
         * @return this builder
         */
        public Builder quality(float quality) {
            this.quality = quality;
            this.nominalBitrate = null;
            return this;
        }

        /**
         * Sets the nominal bitrate the quality is chosen from; see
         * {@link VorbisEncoderOptions#nominalBitrate()}.
         *
         * @param bitrate the value
         * @return this builder
         */
        public Builder nominalBitrate(int bitrate) {
            this.nominalBitrate = bitrate;
            return this;
        }

        /**
         * Returns the options holding the settings of this builder.
         *
         * @return the options
         * @throws IllegalArgumentException if the sampling rate is outside 1 to 200000 Hz, the layout
         * has more than 255 channels, the quality is outside -1 to 10 or NaN, the nominal bitrate is
         * not positive, or no encoder setting covers the format with the quality or nominal bitrate
         */
        public VorbisEncoderOptions build() {
            Checks.inRange("sample rate", sampleRate, 1, 200_000);
            var channels = Checks.inRange("channel count", layout.channels(), 1, 255);
            Checks.inRange("quality", quality, -1.0, 10.0);
            if (nominalBitrate != null) {
                Checks.positive("nominal bitrate", nominalBitrate);
                if (!CodecSetup.supportsBitrate(channels, sampleRate, nominalBitrate)) {
                    throw new IllegalArgumentException("no Vorbis setting reaches " + nominalBitrate + " bit/s for "
                            + channels + " channels at " + sampleRate + " Hz");
                }
            } else if (!CodecSetup.supportsQuality(channels, sampleRate, quality / 10)) {
                throw new IllegalArgumentException("no Vorbis setting covers quality " + quality + " for " + channels
                        + " channels at " + sampleRate + " Hz");
            }
            return new VorbisEncoderOptions(this);
        }
    }
}
