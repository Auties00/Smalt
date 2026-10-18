package com.github.auties00.smalt.audio.mp3;

import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.internal.check.Checks;

import java.util.Objects;
import java.util.OptionalInt;

/**
 * Holds the settings of an {@link Mp3Encoder}. Instances are immutable and thread-safe; create them
 * with {@link #builder()} or a preset such as {@link #defaults()}.
 */
public final class Mp3EncoderOptions {
    /**
     * The constant bitrate of {@link #defaults()}, in bits per second.
     */
    public static final int DEFAULT_BITRATE = 128_000;

    /**
     * The variable bitrate quality a {@link Builder} starts with.
     */
    public static final int DEFAULT_VBR_QUALITY = 2;

    /**
     * The automatic bandwidth by bitrate per channel: pairs of kilobits per second and hertz,
     * interpolated linearly.
     */
    private static final int[] BANDWIDTH_BY_BITRATE = {
            8, 3000, 12, 4500, 16, 5500, 20, 7000, 24, 8000, 32, 11000, 40, 13500, 48, 15100,
            56, 15600, 64, 17000, 80, 17500, 96, 18600, 112, 19400, 128, 19700, 160, 20500
    };

    /**
     * The automatic bandwidth of each variable bitrate quality, in hertz.
     */
    private static final int[] BANDWIDTH_BY_QUALITY = {
            19500, 19000, 18500, 18000, 17500, 16500, 15500, 14500, 13000, 11000
    };

    /**
     * The options of {@link #defaults()}.
     */
    private static final Mp3EncoderOptions DEFAULTS = new Builder().build();

    private final int sampleRate;

    private final ChannelLayout layout;

    private final Mp3BitrateMode bitrateMode;

    private final int bitrate;

    private final int vbrQuality;

    private final Mp3StereoMode stereoMode;

    /**
     * The lowpass frequency in hertz, or {@code 0} for the automatic choice.
     */
    private final int bandwidth;

    private final boolean crc;

    private final boolean bitReservoir;

    private Mp3EncoderOptions(Builder builder) {
        this.sampleRate = builder.sampleRate;
        this.layout = builder.layout;
        this.bitrateMode = builder.bitrateMode;
        this.bitrate = builder.bitrate;
        this.vbrQuality = builder.vbrQuality;
        this.stereoMode = builder.stereoMode;
        this.bandwidth = builder.bandwidth == null ? 0 : builder.bandwidth;
        this.crc = builder.crc;
        this.bitReservoir = builder.bitReservoir;
    }

    /**
     * Returns 128 kbit/s constant bitrate joint stereo for 44.1 kHz stereo input.
     *
     * @return the default options
     */
    public static Mp3EncoderOptions defaults() {
        return DEFAULTS;
    }

    /**
     * Returns the defaults for an input format.
     *
     * @param sampleRate the sampling rate in hertz, one of the nine Layer III rates
     * @param layout the channel layout, mono or stereo
     * @return the options
     * @throws IllegalArgumentException if the rate or layout is not codable, or 128 kbit/s is not a
     * bitrate of the rate's MPEG version
     */
    public static Mp3EncoderOptions of(int sampleRate, ChannelLayout layout) {
        return new Builder().format(sampleRate, layout).build();
    }

    /**
     * Returns constant bitrate options at a bitrate for 44.1 kHz stereo input.
     *
     * @apiNote Set the bitrate mode with {@link Builder#cbr(int)} to keep another input format.
     *
     * @param bitrate the bitrate in bits per second, an MPEG-1 Layer III bitrate
     * @return the options
     * @throws IllegalArgumentException if the bitrate is not an MPEG-1 bitrate
     */
    public static Mp3EncoderOptions ofConstantBitrate(int bitrate) {
        return new Builder().cbr(bitrate).build();
    }

    /**
     * Returns variable bitrate options at a quality for 44.1 kHz stereo input.
     *
     * @apiNote Set the bitrate mode with {@link Builder#vbr(int)} to keep another input format.
     *
     * @param quality the quality, from 0 (best, largest) to 9 (smallest)
     * @return the options
     * @throws IllegalArgumentException if {@code quality} is out of range
     */
    public static Mp3EncoderOptions ofVariableBitrate(int quality) {
        return new Builder().vbr(quality).build();
    }

    /**
     * Returns average bitrate options for 44.1 kHz stereo input.
     *
     * @apiNote Set the bitrate mode with {@link Builder#abr(int)} to keep another input format.
     *
     * @param bitrate the target average in bits per second, from 32 to 320 kbit/s
     * @return the options
     * @throws IllegalArgumentException if the bitrate is out of range
     */
    public static Mp3EncoderOptions ofAverageBitrate(int bitrate) {
        return new Builder().abr(bitrate).build();
    }

    /**
     * Returns options for interactive streaming: the defaults without the bit reservoir, so every
     * frame decodes on its own and a lost frame never damages the next.
     *
     * @return the options
     */
    public static Mp3EncoderOptions forRealtime() {
        return new Builder().bitReservoir(false).build();
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
     * {@return the input sampling rate in hertz, one of the nine Layer III rates; 44100 by default}
     *
     * <p>The rate selects the MPEG version: 32, 44.1 and 48 kHz are MPEG-1 (1152 samples per frame,
     * 32 to 320 kbit/s), 16, 22.05 and 24 kHz MPEG-2 and 8, 11.025 and 12 kHz MPEG-2.5 (576 samples
     * per frame, 8 to 160 kbit/s).
     */
    public int sampleRate() {
        return sampleRate;
    }

    /**
     * {@return the input channel layout, mono or stereo; {@link ChannelLayout#STEREO} by default}
     */
    public ChannelLayout layout() {
        return layout;
    }

    /**
     * {@return the bitrate control; {@link Mp3BitrateMode#CBR} by default}
     *
     * <table class="striped">
     * <caption>Bitrate modes</caption>
     * <thead><tr><th scope="col">Mode</th><th scope="col">Setting</th><th scope="col">Result</th></tr>
     * </thead>
     * <tbody>
     * <tr><th scope="row">{@link Mp3BitrateMode#CBR}</th><td>{@link #bitrate()}, one of the version's
     * bitrates</td><td>every frame at that bitrate</td></tr>
     * <tr><th scope="row">{@link Mp3BitrateMode#VBR}</th><td>{@link #vbrQuality()}, 0 (best) to 9</td>
     * <td>frames between the version's lowest and highest bitrate</td></tr>
     * <tr><th scope="row">{@link Mp3BitrateMode#ABR}</th><td>{@link #bitrate()}, any rate the version
     * spans</td><td>variable frames averaging about that bitrate</td></tr>
     * </tbody>
     * </table>
     */
    public Mp3BitrateMode bitrateMode() {
        return bitrateMode;
    }

    /**
     * {@return the constant or average bitrate in bits per second, which variable bitrate mode
     * ignores; {@value #DEFAULT_BITRATE} by default}
     */
    public int bitrate() {
        return bitrate;
    }

    /**
     * {@return the variable bitrate quality, from 0 (best) to 9, which the other modes ignore;
     * {@value #DEFAULT_VBR_QUALITY} by default}
     */
    public int vbrQuality() {
        return vbrQuality;
    }

    /**
     * {@return how a stereo stream codes its channels; {@link Mp3StereoMode#JOINT_STEREO} by
     * default}
     */
    public Mp3StereoMode stereoMode() {
        return stereoMode;
    }

    /**
     * {@return the lowpass frequency in hertz, from 1000 to 24000, or empty (the default) for the
     * automatic choice}
     *
     * <p>The automatic frequency grows with the bitrate per channel (or with the quality), from
     * about 3 kHz at 8 kbit/s to 20.5 kHz at 160 kbit/s per channel; {@link #effectiveBandwidth()}
     * returns the frequency applied.
     */
    public OptionalInt bandwidth() {
        return bandwidth == 0 ? OptionalInt.empty() : OptionalInt.of(bandwidth);
    }

    /**
     * Returns the lowpass frequency the encoder applies: {@link #bandwidth()} when set,
     * otherwise the automatic choice for the bitrate or quality; never above 95% of the Nyquist
     * frequency.
     *
     * @return the frequency in hertz
     */
    public int effectiveBandwidth() {
        var nyquist = sampleRate / 2;
        var limit = (int) (nyquist * 0.95);
        if (bandwidth != 0) {
            return Math.min(bandwidth, limit);
        }
        if (bitrateMode == Mp3BitrateMode.VBR) {
            return Math.min(BANDWIDTH_BY_QUALITY[vbrQuality], limit);
        }
        var perChannel = bitrate / 1000.0 / layout.channels();
        var table = BANDWIDTH_BY_BITRATE;
        double hertz = table[table.length - 1];
        if (perChannel <= table[0]) {
            hertz = table[1];
        } else {
            for (var i = 2; i < table.length; i += 2) {
                if (perChannel <= table[i]) {
                    var fraction = (perChannel - table[i - 2]) / (table[i] - table[i - 2]);
                    hertz = table[i - 1] + fraction * (table[i + 1] - table[i - 1]);
                    break;
                }
            }
        }
        return Math.min((int) hertz, limit);
    }

    /**
     * {@return whether every frame carries a CRC-16 over its header and side information;
     * {@code false} by default}
     */
    public boolean crc() {
        return crc;
    }

    /**
     * {@return whether frames may store main data in the unused bytes of earlier frames;
     * {@code true} by default}
     */
    public boolean bitReservoir() {
        return bitReservoir;
    }

    @Override
    public boolean equals(Object object) {
        return object instanceof Mp3EncoderOptions that && sampleRate == that.sampleRate && layout.equals(that.layout)
                && bitrateMode == that.bitrateMode && bitrate == that.bitrate && vbrQuality == that.vbrQuality
                && stereoMode == that.stereoMode && bandwidth == that.bandwidth && crc == that.crc
                && bitReservoir == that.bitReservoir;
    }

    @Override
    public int hashCode() {
        return Objects.hash(sampleRate, layout, bitrateMode, bitrate, vbrQuality, stereoMode, bandwidth, crc, bitReservoir);
    }

    @Override
    public String toString() {
        return "Mp3EncoderOptions[sampleRate=" + sampleRate + ", layout=" + layout + ", bitrateMode=" + bitrateMode
                + ", bitrate=" + bitrate + ", vbrQuality=" + vbrQuality + ", stereoMode=" + stereoMode
                + ", bandwidth=" + (bandwidth == 0 ? "auto" : bandwidth) + ", crc=" + crc + ", bitReservoir=" + bitReservoir + "]";
    }

    /**
     * A builder of {@link Mp3EncoderOptions}.
     *
     * <p>Setters reject only {@code null}; {@link #build()} checks the ranges and that the bitrate
     * fits the bitrate mode and the sampling rate's MPEG version. Instances are not thread-safe.
     */
    public static final class Builder {
        private int sampleRate = 44_100;

        private ChannelLayout layout = ChannelLayout.STEREO;

        private Mp3BitrateMode bitrateMode = Mp3BitrateMode.CBR;

        private int bitrate = DEFAULT_BITRATE;

        private int vbrQuality = DEFAULT_VBR_QUALITY;

        private Mp3StereoMode stereoMode = Mp3StereoMode.JOINT_STEREO;

        /**
         * The lowpass frequency, or {@code null} for the automatic choice.
         */
        private Integer bandwidth;

        private boolean crc;

        private boolean bitReservoir = true;

        private Builder() {
        }

        private Builder(Mp3EncoderOptions options) {
            this.sampleRate = options.sampleRate;
            this.layout = options.layout;
            this.bitrateMode = options.bitrateMode;
            this.bitrate = options.bitrate;
            this.vbrQuality = options.vbrQuality;
            this.stereoMode = options.stereoMode;
            this.bandwidth = options.bandwidth == 0 ? null : options.bandwidth;
            this.crc = options.crc;
            this.bitReservoir = options.bitReservoir;
        }

        /**
         * Sets the input format; see {@link Mp3EncoderOptions#sampleRate()} and
         * {@link Mp3EncoderOptions#layout()}.
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
         * Sets the bitrate control; see {@link Mp3EncoderOptions#bitrateMode()}.
         *
         * @param bitrateMode the value
         * @return this builder
         */
        public Builder bitrateMode(Mp3BitrateMode bitrateMode) {
            this.bitrateMode = Objects.requireNonNull(bitrateMode, "bitrateMode");
            return this;
        }

        /**
         * Sets the constant or average bitrate; see {@link Mp3EncoderOptions#bitrate()}.
         *
         * @param bitrate the value
         * @return this builder
         */
        public Builder bitrate(int bitrate) {
            this.bitrate = bitrate;
            return this;
        }

        /**
         * Sets the variable bitrate quality; see {@link Mp3EncoderOptions#vbrQuality()}.
         *
         * @param quality the value
         * @return this builder
         */
        public Builder vbrQuality(int quality) {
            this.vbrQuality = quality;
            return this;
        }

        /**
         * Selects a constant bitrate; see {@link Mp3EncoderOptions#bitrateMode()} and
         * {@link Mp3EncoderOptions#bitrate()}.
         *
         * <p>Unlike the {@link Mp3EncoderOptions#ofConstantBitrate(int)} preset, this keeps the input format.
         *
         * @param bitrate the bitrate in bits per second
         * @return this builder
         */
        public Builder cbr(int bitrate) {
            this.bitrate = bitrate;
            this.bitrateMode = Mp3BitrateMode.CBR;
            return this;
        }

        /**
         * Selects a variable bitrate at a quality; see {@link Mp3EncoderOptions#bitrateMode()} and
         * {@link Mp3EncoderOptions#vbrQuality()}.
         *
         * <p>Unlike the {@link Mp3EncoderOptions#ofVariableBitrate(int)} preset, this keeps the input format.
         *
         * @param quality the quality, from 0 (best, largest) to 9 (smallest)
         * @return this builder
         */
        public Builder vbr(int quality) {
            this.vbrQuality = quality;
            this.bitrateMode = Mp3BitrateMode.VBR;
            return this;
        }

        /**
         * Selects an average bitrate; see {@link Mp3EncoderOptions#bitrateMode()} and
         * {@link Mp3EncoderOptions#bitrate()}.
         *
         * <p>Unlike the {@link Mp3EncoderOptions#ofAverageBitrate(int)} preset, this keeps the input format.
         *
         * @param bitrate the target average in bits per second
         * @return this builder
         */
        public Builder abr(int bitrate) {
            this.bitrate = bitrate;
            this.bitrateMode = Mp3BitrateMode.ABR;
            return this;
        }

        /**
         * Sets the stereo coding; see {@link Mp3EncoderOptions#stereoMode()}.
         *
         * @param stereoMode the value
         * @return this builder
         */
        public Builder stereoMode(Mp3StereoMode stereoMode) {
            this.stereoMode = Objects.requireNonNull(stereoMode, "stereoMode");
            return this;
        }

        /**
         * Sets an explicit lowpass frequency; see {@link Mp3EncoderOptions#bandwidth()}.
         *
         * @param bandwidth the value
         * @return this builder
         */
        public Builder bandwidth(int bandwidth) {
            this.bandwidth = bandwidth;
            return this;
        }

        /**
         * Selects the automatic lowpass frequency; see {@link Mp3EncoderOptions#bandwidth()}.
         *
         * @return this builder
         */
        public Builder automaticBandwidth() {
            this.bandwidth = null;
            return this;
        }

        /**
         * Sets whether frames carry a CRC; see {@link Mp3EncoderOptions#crc()}.
         *
         * @param crc the value
         * @return this builder
         */
        public Builder crc(boolean crc) {
            this.crc = crc;
            return this;
        }

        /**
         * Sets whether frames may borrow bytes from earlier frames; see
         * {@link Mp3EncoderOptions#bitReservoir()}.
         *
         * @param bitReservoir the value
         * @return this builder
         */
        public Builder bitReservoir(boolean bitReservoir) {
            this.bitReservoir = bitReservoir;
            return this;
        }

        /**
         * Returns the options holding the settings of this builder.
         *
         * @return the options
         * @throws IllegalArgumentException if the sampling rate is not a Layer III rate, the layout is
         * not mono or stereo, the bitrate is not positive, the quality is outside 0 to 9, an explicit
         * lowpass frequency is outside 1000 to 24000 Hz, a constant bitrate is not a bitrate of the
         * sampling rate's MPEG version, or an average bitrate is outside its range
         */
        public Mp3EncoderOptions build() {
            var rateIndex = Mp3EncoderTables.sampleRateIndex(sampleRate);
            if (rateIndex < 0) {
                throw new IllegalArgumentException("sample rate " + sampleRate + " is not one of 8000, 11025, "
                        + "12000, 16000, 22050, 24000, 32000, 44100, 48000");
            }
            Checks.inRange("channel count", layout.channels(), 1, 2);
            Checks.positive("bitrate", bitrate);
            Checks.inRange("VBR quality", vbrQuality, 0, 9);
            if (bandwidth != null) {
                Checks.inRange("bandwidth", bandwidth, 1000, 24000);
            }
            var table = Mp3EncoderTables.BITRATES[rateIndex < 3 ? 0 : 1];
            if (bitrateMode == Mp3BitrateMode.CBR) {
                var valid = false;
                for (var i = 1; i < table.length; i++) {
                    valid |= table[i] * 1000 == bitrate;
                }
                if (!valid) {
                    throw new IllegalArgumentException(bitrate + " bit/s is not an MPEG-"
                            + (rateIndex < 3 ? "1" : rateIndex < 6 ? "2" : "2.5") + " Layer III bitrate at "
                            + sampleRate + " Hz");
                }
            } else if (bitrateMode == Mp3BitrateMode.ABR) {
                Checks.inRange("average bitrate", bitrate, table[1] * 1000, table[14] * 1000);
            }
            return new Mp3EncoderOptions(this);
        }
    }
}
