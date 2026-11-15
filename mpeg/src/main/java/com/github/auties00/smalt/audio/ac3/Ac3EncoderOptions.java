package com.github.auties00.smalt.audio.ac3;

import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.ac3.internal.ChannelMap;
import com.github.auties00.smalt.internal.check.Checks;
import com.github.auties00.smalt.internal.codec.Ac3Configs;

import java.util.Objects;
import java.util.OptionalInt;

/**
 * Holds the settings of an {@link Ac3Encoder}. Instances are immutable and thread-safe; create them
 * with {@link #builder()} or a preset such as {@link #defaults()}.
 */
public final class Ac3EncoderOptions {
    /**
     * The options of {@link #defaults()}.
     */
    private static final Ac3EncoderOptions DEFAULTS = new Builder().build();

    /**
     * The options of {@link #forRealtime()}.
     */
    private static final Ac3EncoderOptions REALTIME = new Builder().fastSearch(true).build();

    private final int sampleRate;

    private final ChannelLayout layout;

    /**
     * The bit rate in bits per second, or 0 for the default of the layout.
     */
    private final int bitrate;

    private final int dialogueLevel;

    private final boolean blockSwitching;

    private final boolean channelCoupling;

    private final boolean fastSearch;

    private final Ac3Metadata metadata;

    private Ac3EncoderOptions(Builder builder) {
        this.sampleRate = builder.sampleRate;
        this.layout = builder.layout;
        this.bitrate = builder.bitrate;
        this.dialogueLevel = builder.dialogueLevel;
        this.blockSwitching = builder.blockSwitching;
        this.channelCoupling = builder.channelCoupling;
        this.fastSearch = builder.fastSearch;
        this.metadata = builder.metadata;
    }

    /**
     * Returns the default options: 48 kHz stereo at the bit rate of the layout, dialogue level -31
     * dBFS, block switching and channel coupling on, the full search of every frame, and the
     * metadata of {@link Ac3Metadata#defaults()}.
     *
     * @return the default options
     */
    public static Ac3EncoderOptions defaults() {
        return DEFAULTS;
    }

    /**
     * Returns the default options with the faster search of {@link #fastSearch()}, which encodes
     * faster at a slightly lower quality.
     *
     * @apiNote AC-3 frames hold 1536 samples, 32 ms at 48 kHz, and the encoder output lags its
     * input by one frame plus the 256 samples of {@link Ac3Encoder#delay()}; a live stream that
     * needs shorter packets can use {@link Eac3EncoderOptions#forRealtime()}, whose one-block frames
     * hold 256 samples, where its decoders read E-AC-3.
     *
     * @return the real-time options
     */
    public static Ac3EncoderOptions forRealtime() {
        return REALTIME;
    }

    /**
     * Returns the default options for a sampling rate and a layout, at the bit rate of the layout.
     *
     * <p>A rate other than 32, 44.1 or 48 kHz codes at 48 kHz. A layout AC-3 cannot carry codes the
     * closest configuration: the front pair, the center, the surround pair (side or back) or the
     * single back center, and the LFE are kept when present, everything else is dropped; a bare
     * channel count beyond the six of 5.1 codes 5.1. Callers convert their input to the reported
     * {@link #sampleRate()} and {@link #layout()}.
     *
     * @param sampleRate the requested sampling rate in hertz
     * @param layout the requested channel layout
     * @return the options
     */
    public static Ac3EncoderOptions of(int sampleRate, ChannelLayout layout) {
        return new Builder().format(sampleRate, layout).build();
    }

    /**
     * Returns the AC-3 bit rate closest to a requested one, the lower on a tie.
     *
     * @param bitrate the requested bit rate in bits per second
     * @return a bit rate of A/52 table 5.18
     */
    public static int closestBitrate(int bitrate) {
        var best = Ac3Configs.BITRATES[0] * 1000;
        for (var kbps : Ac3Configs.BITRATES) {
            var candidate = kbps * 1000;
            if (Math.abs((long) candidate - bitrate) < Math.abs((long) best - bitrate)) {
                best = candidate;
            }
        }
        return best;
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
     * {@return the sampling rate in hertz: 32000, 44100 or 48000; 48000 by default}
     */
    public int sampleRate() {
        return sampleRate;
    }

    /**
     * {@return the channel layout the encoder accepts, in WAVE order: one of the configurations of
     * ATSC A/52 table 5.8 (mono, stereo, 3/0, 2/1, 3/1, 2/2 and 3/2, each with or without an LFE
     * channel); stereo by default}
     */
    public ChannelLayout layout() {
        return layout;
    }

    /**
     * {@return the explicit bit rate in bits per second, one of A/52 table 5.18 (32 to 640 kbit/s),
     * or empty to use the rate of the layout (see {@link #effectiveBitrate()}); empty by default}
     *
     * <p>Options that leave the bit rate to the layout and options that set the same rate
     * explicitly are not equal, because a later {@link Builder#format(int, ChannelLayout)} changes
     * the rate of the first but not of the second.
     */
    public OptionalInt bitrate() {
        return bitrate == 0 ? OptionalInt.empty() : OptionalInt.of(bitrate);
    }

    /**
     * Returns the bit rate the encoder uses: the explicit one, or the rate of the layout's
     * full-bandwidth channels, 96 kbit/s for one, 192 for two, 320 for three, 384 for four and 448
     * for five.
     *
     * @return the bit rate in bits per second
     */
    public int effectiveBitrate() {
        return bitrate != 0 ? bitrate : ChannelMap.defaultBitrate(layout);
    }

    /**
     * {@return the dialogue level the stream declares ({@code dialnorm}), in dBFS from -31 to -1;
     * -31 by default}
     */
    public int dialogueLevel() {
        return dialogueLevel;
    }

    /**
     * {@return whether the encoder switches a channel to two short transforms on a transient;
     * {@code true} by default}
     *
     * <p>Short transforms confine the quantization noise of a transient to a quarter of the time a
     * long transform spreads it over, which avoids audible pre-echo; the price is reconstruction
     * error above the coded bandwidth in the switched block.
     */
    public boolean blockSwitching() {
        return blockSwitching;
    }

    /**
     * {@return whether the encoder may use channel coupling (A/52 section 7.4); {@code true} by
     * default}
     *
     * <p>Coupling codes the high band of the full-bandwidth channels once, as their average, and
     * sends per channel only the band energies that restore each channel's spectral envelope. The
     * encoder uses it only at low bit rates per full-bandwidth channel, and only for the channels
     * whose high band the shared coupling channel rebuilds closely; mono never couples.
     *
     * @apiNote Coupling trades waveform accuracy above the coupling frequency for bits below it,
     * which raises the quality of low bit rates; turn it off when the decoded waveform must stay
     * close to the input over the whole band.
     */
    public boolean channelCoupling() {
        return channelCoupling;
    }

    /**
     * {@return whether the encoder analyzes every frame with the faster search; {@code false} by
     * default}
     *
     * <p>The full search costs each frame under every dB per bit code the encoder chooses from and
     * keeps the code of the smaller quantization error. The faster search does so on some frames
     * only and ends the exponent strategy search early; it encodes faster at a slightly lower
     * quality.
     */
    public boolean fastSearch() {
        return fastSearch;
    }

    /**
     * {@return the metadata the frames carry besides the dialogue level; {@link Ac3Metadata#defaults()}
     * by default}
     *
     * <p>AC-3 has no LFE mix level, program scale factors or source sample rate flag, so those
     * fields of the metadata are not written.
     */
    public Ac3Metadata metadata() {
        return metadata;
    }

    @Override
    public boolean equals(Object object) {
        return object instanceof Ac3EncoderOptions that
                && sampleRate == that.sampleRate
                && layout.equals(that.layout)
                && bitrate == that.bitrate
                && dialogueLevel == that.dialogueLevel
                && blockSwitching == that.blockSwitching
                && channelCoupling == that.channelCoupling
                && fastSearch == that.fastSearch
                && metadata.equals(that.metadata);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sampleRate, layout, bitrate, dialogueLevel, blockSwitching, channelCoupling, fastSearch,
                metadata);
    }

    @Override
    public String toString() {
        return "Ac3EncoderOptions[sampleRate=" + sampleRate + ", layout=" + layout.speakers() + ", bitrate=" + effectiveBitrate()
                + ", dialogueLevel=" + dialogueLevel + ", blockSwitching=" + blockSwitching + ", channelCoupling="
                + channelCoupling + ", fastSearch=" + fastSearch + ", metadata=" + metadata + "]";
    }

    /**
     * {@return whether a bit rate is one of A/52 table 5.18}
     *
     * @param bitrate the bit rate in bits per second
     */
    private static boolean isBitrate(int bitrate) {
        for (var kbps : Ac3Configs.BITRATES) {
            if (kbps * 1000 == bitrate) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks that a metadata fits a layout: a voice over is mono, karaoke has two or more
     * full-bandwidth channels.
     *
     * @param metadata the metadata to check
     * @param layout the layout the metadata describes
     * @throws IllegalArgumentException if the type of service does not fit the layout
     */
    static void checkServiceType(Ac3Metadata metadata, ChannelLayout layout) {
        var fullBandwidth = ChannelMap.fullBandwidthChannels(layout);
        if (metadata.serviceType() == Ac3ServiceType.VOICE_OVER && fullBandwidth != 1) {
            throw new IllegalArgumentException("a voice over service is mono");
        }
        if (metadata.serviceType() == Ac3ServiceType.KARAOKE && fullBandwidth < 2) {
            throw new IllegalArgumentException("a karaoke service has two or more full-bandwidth channels");
        }
    }

    /**
     * Maps a sampling rate onto an AC-3 rate.
     *
     * @param sampleRate the requested rate
     * @return 32000, 44100 or 48000
     */
    private static int supportedRate(int sampleRate) {
        return sampleRate == 32_000 || sampleRate == 44_100 ? sampleRate : 48_000;
    }

    /**
     * A builder of {@link Ac3EncoderOptions}, starting from {@link Ac3EncoderOptions#defaults()}.
     *
     * <p>Setters reject only {@code null}; {@link #build()} checks the values. A builder may build
     * repeatedly. Instances are not thread-safe.
     */
    public static final class Builder {
        private int sampleRate;

        private ChannelLayout layout;

        private int bitrate;

        private boolean explicitBitrate;

        private int dialogueLevel;

        private boolean blockSwitching;

        private boolean channelCoupling;

        private boolean fastSearch;

        private Ac3Metadata metadata;

        private Builder() {
            this.sampleRate = 48_000;
            this.layout = ChannelLayout.STEREO;
            this.bitrate = 0;
            this.dialogueLevel = -31;
            this.blockSwitching = true;
            this.channelCoupling = true;
            this.fastSearch = false;
            this.metadata = Ac3Metadata.defaults();
        }

        private Builder(Ac3EncoderOptions options) {
            this.sampleRate = options.sampleRate;
            this.layout = options.layout;
            this.bitrate = options.bitrate;
            this.explicitBitrate = options.bitrate != 0;
            this.dialogueLevel = options.dialogueLevel;
            this.blockSwitching = options.blockSwitching;
            this.channelCoupling = options.channelCoupling;
            this.fastSearch = options.fastSearch;
            this.metadata = options.metadata;
        }

        /**
         * Sets the sampling rate and the layout, mapped onto the closest the format carries as
         * {@link Ac3EncoderOptions#of(int, ChannelLayout)} maps them; see
         * {@link Ac3EncoderOptions#sampleRate()} and {@link Ac3EncoderOptions#layout()}.
         *
         * @param sampleRate the requested sampling rate in hertz
         * @param layout the requested channel layout
         * @return this builder
         */
        public Builder format(int sampleRate, ChannelLayout layout) {
            this.sampleRate = supportedRate(sampleRate);
            this.layout = ChannelMap.codable(Objects.requireNonNull(layout, "layout"));
            return this;
        }

        /**
         * Sets an explicit bit rate; see {@link Ac3EncoderOptions#bitrate()}.
         *
         * @param bitrate the value
         * @return this builder
         */
        public Builder bitrate(int bitrate) {
            this.bitrate = bitrate;
            this.explicitBitrate = true;
            return this;
        }

        /**
         * Lets the encoder choose the bit rate from the layout; see
         * {@link Ac3EncoderOptions#effectiveBitrate()}.
         *
         * @return this builder
         */
        public Builder automaticBitrate() {
            this.bitrate = 0;
            this.explicitBitrate = false;
            return this;
        }

        /**
         * Sets the declared dialogue level; see {@link Ac3EncoderOptions#dialogueLevel()}.
         *
         * @param dialogueLevel the value
         * @return this builder
         */
        public Builder dialogueLevel(int dialogueLevel) {
            this.dialogueLevel = dialogueLevel;
            return this;
        }

        /**
         * Sets whether block switching is on; see {@link Ac3EncoderOptions#blockSwitching()}.
         *
         * @param blockSwitching the value
         * @return this builder
         */
        public Builder blockSwitching(boolean blockSwitching) {
            this.blockSwitching = blockSwitching;
            return this;
        }

        /**
         * Sets whether channel coupling is allowed; see {@link Ac3EncoderOptions#channelCoupling()}.
         *
         * @param channelCoupling the value
         * @return this builder
         */
        public Builder channelCoupling(boolean channelCoupling) {
            this.channelCoupling = channelCoupling;
            return this;
        }

        /**
         * Sets whether every frame takes the faster search; see {@link Ac3EncoderOptions#fastSearch()}.
         *
         * @param fastSearch the value
         * @return this builder
         */
        public Builder fastSearch(boolean fastSearch) {
            this.fastSearch = fastSearch;
            return this;
        }

        /**
         * Sets the metadata; see {@link Ac3EncoderOptions#metadata()}.
         *
         * @param metadata the value
         * @return this builder
         */
        public Builder metadata(Ac3Metadata metadata) {
            this.metadata = Objects.requireNonNull(metadata, "metadata");
            return this;
        }

        /**
         * Returns the options holding the settings of this builder.
         *
         * @return the options
         * @throws IllegalArgumentException if the bit rate is not one of A/52 table 5.18, the
         * dialogue level is not in [-31, -1], or the metadata declares a voice over for a layout that
         * is not mono or karaoke for a mono one
         */
        public Ac3EncoderOptions build() {
            if (explicitBitrate && !isBitrate(bitrate)) {
                throw new IllegalArgumentException("bitrate " + bitrate + " is not an AC-3 bit rate");
            }
            Checks.inRange("dialogue level", dialogueLevel, -31, -1);
            checkServiceType(metadata, layout);
            return new Ac3EncoderOptions(this);
        }
    }
}
