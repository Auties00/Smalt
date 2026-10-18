package com.github.auties00.smalt.audio.aac;

import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.internal.check.Checks;
import com.github.auties00.smalt.internal.header.AacRates;

import java.util.Objects;
import java.util.OptionalInt;

/**
 * Holds the settings of an {@link AacEncoder}. Instances are immutable and thread-safe; create them
 * with {@link #builder()} or a preset such as {@link #defaults()}.
 */
public final class AacEncoderOptions {
    /**
     * The smallest accepted bitrate per coded channel.
     */
    private static final int MIN_CHANNEL_BITRATE = 6000;

    /**
     * The bitrates per full-band channel, in bits per second, at which the automatic bandwidth takes
     * the values of {@link #BANDWIDTHS}; it is interpolated linearly between them.
     */
    private static final int[] BANDWIDTH_RATES = {8000, 12000, 16000, 24000, 32000, 48000, 64000, 80000, 96000,
            112000, 128000};

    /**
     * The automatic bandwidth in hertz at each rate of {@link #BANDWIDTH_RATES}.
     */
    private static final int[] BANDWIDTHS = {3000, 3800, 4500, 8500, 13000, 17500, 18000, 19000, 21500, 23000,
            24000};

    /**
     * The options of {@link #defaults()}.
     */
    private static final AacEncoderOptions DEFAULTS = new Builder().build();

    private final int sampleRate;

    private final ChannelLayout layout;

    private final int bitrate;

    private final AacBitrateMode bitrateMode;

    private final int bandwidth;

    private AacEncoderOptions(Builder builder) {
        this.sampleRate = builder.sampleRate;
        this.layout = builder.layout;
        this.bitrate = builder.bitrate;
        this.bitrateMode = builder.bitrateMode;
        this.bandwidth = builder.bandwidth;
    }

    /**
     * Returns the default options: 48 kHz stereo at 128 kb/s, constant bitrate, with the automatic
     * bandwidth.
     *
     * @return the default options
     */
    public static AacEncoderOptions defaults() {
        return DEFAULTS;
    }

    /**
     * Returns constant bitrate options for a format and a bitrate, with the automatic bandwidth.
     *
     * @param sampleRate the sampling rate in hertz; see {@link #sampleRate()}
     * @param layout the channel layout of the input; see {@link #layout()}
     * @param bitrate the bitrate in bits per second; see {@link #bitrate()}
     * @return the options
     * @throws IllegalArgumentException if the format is not supported or the bitrate does not fit it
     */
    public static AacEncoderOptions of(int sampleRate, ChannelLayout layout, int bitrate) {
        return new Builder().format(sampleRate, layout).bitrate(bitrate).build();
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
     * {@return the sampling rate in hertz, a standard AAC rate from 7350 to 96000; 48000 by default}
     */
    public int sampleRate() {
        return sampleRate;
    }

    /**
     * {@return the channel layout of the input, in WAVE order: {@link ChannelLayout#MONO},
     * {@link ChannelLayout#STEREO} or {@link ChannelLayout#SURROUND_5_1}; stereo by default}
     */
    public ChannelLayout layout() {
        return layout;
    }

    /**
     * {@return the bitrate in bits per second, from 6000 per channel to 6 times the sampling rate
     * per channel (6144 bits per channel and frame); 128000 by default}
     *
     * <p>With {@link AacBitrateMode#CBR} this is the exact long-term average; with
     * {@link AacBitrateMode#VBR} the nominal long-term average.
     */
    public int bitrate() {
        return bitrate;
    }

    /**
     * {@return the bitrate control; {@link AacBitrateMode#CBR} by default}
     */
    public AacBitrateMode bitrateMode() {
        return bitrateMode;
    }

    /**
     * {@return the explicit audio bandwidth in hertz, from 1 to half the sampling rate, or empty to
     * derive it from the bitrate (see {@link #effectiveBandwidth()}); empty by default}
     */
    public OptionalInt bandwidth() {
        return bandwidth == 0 ? OptionalInt.empty() : OptionalInt.of(bandwidth);
    }

    /**
     * Returns the bandwidth the encoder uses: the configured one, or the automatic choice.
     *
     * <p>The automatic bandwidth grows with the bitrate per full-band channel from 3 kHz at
     * 8 kbps through 13 kHz at 32 kbps and 18 kHz at 64 kbps to 24 kHz from 128 kbps, and never
     * exceeds half the sampling rate.
     *
     * @return the bandwidth in hertz
     */
    public int effectiveBandwidth() {
        if (bandwidth != 0) {
            return bandwidth;
        }
        var fullBand = layout.channels() == 6 ? 5 : layout.channels();
        var perChannel = bitrate / (double) fullBand;
        var chosen = BANDWIDTHS[BANDWIDTHS.length - 1];
        if (perChannel <= BANDWIDTH_RATES[0]) {
            chosen = BANDWIDTHS[0];
        } else {
            for (var i = 1; i < BANDWIDTH_RATES.length; i++) {
                if (perChannel <= BANDWIDTH_RATES[i]) {
                    var t = (perChannel - BANDWIDTH_RATES[i - 1]) / (BANDWIDTH_RATES[i] - BANDWIDTH_RATES[i - 1]);
                    chosen = (int) (BANDWIDTHS[i - 1] + t * (BANDWIDTHS[i] - BANDWIDTHS[i - 1]));
                    break;
                }
            }
        }
        return Math.min(chosen, sampleRate / 2);
    }

    @Override
    public boolean equals(Object object) {
        return object instanceof AacEncoderOptions that
                && sampleRate == that.sampleRate
                && layout.equals(that.layout)
                && bitrate == that.bitrate
                && bitrateMode == that.bitrateMode
                && bandwidth == that.bandwidth;
    }

    @Override
    public int hashCode() {
        return Objects.hash(sampleRate, layout, bitrate, bitrateMode, bandwidth);
    }

    @Override
    public String toString() {
        return "AacEncoderOptions[sampleRate=" + sampleRate
                + ", layout=" + layout.speakers()
                + ", bitrate=" + bitrate
                + ", bitrateMode=" + bitrateMode
                + ", bandwidth=" + (bandwidth == 0 ? "auto" : String.valueOf(bandwidth)) + "]";
    }

    /**
     * A builder of {@link AacEncoderOptions}, starting from {@link AacEncoderOptions#defaults()}.
     *
     * <p>Setters reject only {@code null}; {@link #build()} checks the values. A builder may build
     * repeatedly. Instances are not thread-safe.
     */
    public static final class Builder {
        private int sampleRate;

        private ChannelLayout layout;

        private int bitrate;

        private AacBitrateMode bitrateMode;

        private int bandwidth;

        private boolean explicitBandwidth;

        private Builder() {
            this.sampleRate = 48_000;
            this.layout = ChannelLayout.STEREO;
            this.bitrate = 128_000;
            this.bitrateMode = AacBitrateMode.CBR;
            this.bandwidth = 0;
        }

        private Builder(AacEncoderOptions options) {
            this.sampleRate = options.sampleRate;
            this.layout = options.layout;
            this.bitrate = options.bitrate;
            this.bitrateMode = options.bitrateMode;
            this.bandwidth = options.bandwidth;
            this.explicitBandwidth = options.bandwidth != 0;
        }

        /**
         * Sets the input format; see {@link AacEncoderOptions#sampleRate()} and
         * {@link AacEncoderOptions#layout()}.
         *
         * @param sampleRate the value of {@code sampleRate()}
         * @param layout the value of {@code layout()}
         * @return this builder
         */
        public Builder format(int sampleRate, ChannelLayout layout) {
            this.layout = Objects.requireNonNull(layout, "layout");
            this.sampleRate = sampleRate;
            return this;
        }

        /**
         * Sets the bitrate; see {@link AacEncoderOptions#bitrate()}.
         *
         * @param bitrate the value
         * @return this builder
         */
        public Builder bitrate(int bitrate) {
            this.bitrate = bitrate;
            return this;
        }

        /**
         * Sets the bitrate control; see {@link AacEncoderOptions#bitrateMode()}.
         *
         * @param bitrateMode the value
         * @return this builder
         */
        public Builder bitrateMode(AacBitrateMode bitrateMode) {
            this.bitrateMode = Objects.requireNonNull(bitrateMode, "bitrateMode");
            return this;
        }

        /**
         * Sets an explicit audio bandwidth; see {@link AacEncoderOptions#bandwidth()}.
         *
         * @param bandwidth the value
         * @return this builder
         */
        public Builder bandwidth(int bandwidth) {
            this.bandwidth = bandwidth;
            this.explicitBandwidth = true;
            return this;
        }

        /**
         * Selects the bandwidth derived from the bitrate; see
         * {@link AacEncoderOptions#effectiveBandwidth()}.
         *
         * @return this builder
         */
        public Builder automaticBandwidth() {
            this.bandwidth = 0;
            this.explicitBandwidth = false;
            return this;
        }

        /**
         * Returns the options holding the settings of this builder.
         *
         * @return the options
         * @throws IllegalArgumentException if the sampling rate or the layout is not supported, or the
         * bitrate or the bandwidth does not fit the format
         */
        public AacEncoderOptions build() {
            if (AacRates.index(sampleRate) < 0) {
                throw new IllegalArgumentException("sampling rate " + sampleRate + " is not an AAC rate");
            }
            if (!layout.equals(ChannelLayout.MONO) && !layout.equals(ChannelLayout.STEREO)
                    && !layout.equals(ChannelLayout.SURROUND_5_1)) {
                throw new IllegalArgumentException("channel layout " + layout.speakers()
                        + " is not mono, stereo or 5.1");
            }
            var channels = layout.channels();
            Checks.inRange("bitrate", (long) bitrate, (long) MIN_CHANNEL_BITRATE * channels,
                    6L * sampleRate * channels);
            if (explicitBandwidth) {
                Checks.inRange("bandwidth", bandwidth, 1, sampleRate / 2);
            }
            return new AacEncoderOptions(this);
        }
    }
}
