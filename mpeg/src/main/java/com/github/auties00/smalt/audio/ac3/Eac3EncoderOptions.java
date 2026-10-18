package com.github.auties00.smalt.audio.ac3;

import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.Speaker;
import com.github.auties00.smalt.audio.ac3.internal.ChannelMap;
import com.github.auties00.smalt.audio.ac3.internal.EncoderSession;
import com.github.auties00.smalt.internal.check.Checks;

import java.util.Objects;
import java.util.OptionalInt;

/**
 * Holds the settings of an {@link Eac3Encoder}. Instances are immutable and thread-safe; create them
 * with {@link #builder()} or a preset such as {@link #defaults()}.
 */
public final class Eac3EncoderOptions {
    /**
     * The largest frame, in 16-bit words ({@code frmsiz} has 11 bits).
     */
    private static final int MAXIMUM_WORDS = 2048;

    /**
     * The lowest bit rate of any configuration, in bits per second.
     */
    private static final int MINIMUM_BITRATE = 32_000;

    /**
     * The options of {@link #defaults()}.
     */
    private static final Eac3EncoderOptions DEFAULTS = new Builder().build();

    /**
     * The options of {@link #forRealtime()}.
     */
    private static final Eac3EncoderOptions REALTIME = new Builder().blocksPerFrame(1).build();

    private final int sampleRate;

    private final ChannelLayout layout;

    /**
     * The bit rate in bits per second, or 0 for the default of the layout.
     */
    private final int bitrate;

    private final int blocksPerFrame;

    private final int dialogueLevel;

    private final boolean blockSwitching;

    private final boolean channelCoupling;

    private final boolean spectralExtension;

    private final boolean hybridTransform;

    private final boolean preNoiseProcessing;

    private Eac3EncoderOptions(Builder builder) {
        this.sampleRate = builder.sampleRate;
        this.layout = builder.layout;
        this.bitrate = builder.bitrate;
        this.blocksPerFrame = builder.blocksPerFrame;
        this.dialogueLevel = builder.dialogueLevel;
        this.blockSwitching = builder.blockSwitching;
        this.channelCoupling = builder.channelCoupling;
        this.spectralExtension = builder.spectralExtension;
        this.hybridTransform = builder.hybridTransform;
        this.preNoiseProcessing = builder.preNoiseProcessing;
    }

    /**
     * Returns the default options: 48 kHz stereo at 192 kbit/s, six blocks per frame, dialogue
     * level -31 dBFS, block switching, channel coupling and spectral extension on, the adaptive
     * hybrid transform and transient pre-noise processing off.
     *
     * @return the default options
     */
    public static Eac3EncoderOptions defaults() {
        return DEFAULTS;
    }

    /**
     * Returns the default options with one block per frame: 256 samples, about 5.3 ms at 48 kHz,
     * per packet.
     *
     * <p>One-block frames are also analyzed with a faster search than longer frames, for a slightly
     * lower quality: the dB per bit code of the bit allocation is chosen on some frames only, and the
     * exponent strategy search stops early. That keeps their encoding time close to that of
     * six-block frames, whose exponent sets are shared across blocks.
     *
     * @apiNote Short frames lower the latency of a live stream at the cost of coding efficiency:
     * every frame carries its own exponents and side information, so the same bit rate codes
     * fewer mantissa bits than with six blocks per frame.
     *
     * @return the real-time options
     */
    public static Eac3EncoderOptions forRealtime() {
        return REALTIME;
    }

    /**
     * Returns the default options for a sampling rate and a layout, at the bit rate of the layout.
     *
     * <p>32, 44.1, 48, 16, 22.05 and 24 kHz code as they are, any other rate codes at 48 kHz. A
     * layout with the front pair, the center and both the side and the back pairs codes as 7.1
     * (or as 7.0 without LFE), as does a bare count of eight or more channels. Any other layout
     * codes the closest configuration of one independent substream: the front pair, the center,
     * the surround pair (side or back) or the single back center, and the LFE are kept when
     * present, everything else is dropped; a bare channel count of seven codes 5.1. Callers convert
     * their input to the reported {@link #sampleRate()} and {@link #layout()}.
     *
     * @param sampleRate the requested sampling rate in hertz
     * @param layout the requested channel layout
     * @return the options
     */
    public static Eac3EncoderOptions of(int sampleRate, ChannelLayout layout) {
        return new Builder().format(sampleRate, layout).build();
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
     * {@return the sampling rate in hertz: 32000, 44100 or 48000, or the reduced rates 16000, 22050
     * and 24000, which code six blocks per frame; 48000 by default}
     */
    public int sampleRate() {
        return sampleRate;
    }

    /**
     * {@return the channel layout the encoder accepts, in WAVE order; stereo by default}
     *
     * <p>The channel configurations of AC-3, mono to 5.1, code in one independent substream; 7.1
     * and 7.0 code as a 5.1 (or 5.0) downmix in the independent substream and a dependent substream
     * that carries the side and back pairs (see {@link Eac3Encoder}).
     */
    public ChannelLayout layout() {
        return layout;
    }

    /**
     * {@return the explicit bit rate in bits per second, any rate from {@link #minimumBitrate()} to
     * {@link #maximumBitrate()}, or empty to use the rate of the layout (see
     * {@link #effectiveBitrate()}); empty by default}
     *
     * <p>A frame's size is any whole number of 16-bit words up to 2048, so the bit rate is not
     * limited to the AC-3 table. Options that leave the bit rate to the layout and options that set
     * the same rate explicitly are not equal, because the rate of the first follows a later change of
     * the layout, the sampling rate or the frame size.
     */
    public OptionalInt bitrate() {
        return bitrate == 0 ? OptionalInt.empty() : OptionalInt.of(bitrate);
    }

    /**
     * Returns the bit rate the encoder uses: the explicit one, or the rate of the layout, 96 kbit/s
     * per full-bandwidth channel (mono at 96 kbit/s, stereo at 192 kbit/s, 5.1 at 448 kbit/s and 7.1
     * at 768 kbit/s), brought into the range from {@link #minimumBitrate()} to
     * {@link #maximumBitrate()}.
     *
     * @return the bit rate in bits per second
     */
    public int effectiveBitrate() {
        if (bitrate != 0) {
            return bitrate;
        }
        return Math.max(minimumBitrate(), Math.min(maximumBitrate(), ChannelMap.defaultBitrate(layout)));
    }

    /**
     * {@return the number of audio blocks of 256 samples per frame: 1, 2, 3 or 6; 6 by default}
     */
    public int blocksPerFrame() {
        return blocksPerFrame;
    }

    /**
     * {@return the samples per channel of one frame, {@code 256 * blocksPerFrame()}}
     */
    public int frameSize() {
        return 256 * blocksPerFrame;
    }

    /**
     * Returns the lowest bit rate of this rate, layout and frame size: the rate whose frames still
     * hold the side information of a frame of long transforms with its exponents, coupling
     * coordinates and block headers, and at least 32 kbit/s; for 7.1 and 7.0, the lowest rate whose
     * share gives both substreams their lowest rate.
     *
     * @return the bit rate in bits per second
     */
    public int minimumBitrate() {
        var lfe = layout.indexOf(Speaker.LOW_FREQUENCY) >= 0;
        if (ChannelMap.extended(layout)) {
            var core = substreamMinimum(5, lfe);
            var extension = substreamMinimum(4, false);
            var total = Math.max((core * 9L + 4) / 5, (extension * 9L + 3) / 4);
            while (EncoderSession.coreBitrate((int) total) < core
                    || total - EncoderSession.coreBitrate((int) total) < extension) {
                total++;
            }
            return (int) total;
        }
        return substreamMinimum(ChannelMap.fullBandwidthChannels(layout), lfe);
    }

    /**
     * Returns the lowest bit rate of one substream of this rate and frame size.
     *
     * @param fullBandwidth the number of full-bandwidth channels of the substream
     * @param lfe whether the substream codes an LFE channel
     * @return the bit rate in bits per second
     */
    private int substreamMinimum(int fullBandwidth, boolean lfe) {
        var bits = 160 + 95 * fullBandwidth + (lfe ? 20 : 0) + blocksPerFrame * (40 + 6 * fullBandwidth);
        var words = (bits + 15) / 16;
        var samples = 256L * blocksPerFrame;
        return (int) Math.max(MINIMUM_BITRATE, (words * 16L * sampleRate + samples - 1) / samples);
    }

    /**
     * Returns the highest bit rate of this rate and frame size: frames of 2048 words, 1024 kbit/s at
     * 48 kHz with six blocks per frame and 6144 kbit/s with one; for 7.1 and 7.0, the rate whose
     * share fills the frames of the independent substream.
     *
     * @return the bit rate in bits per second
     */
    public int maximumBitrate() {
        var substream = MAXIMUM_WORDS * 16L * sampleRate / (256L * blocksPerFrame);
        if (ChannelMap.extended(layout)) {
            var total = substream * 9 / 5;
            while (EncoderSession.coreBitrate((int) total) > substream) {
                total--;
            }
            return (int) total;
        }
        return (int) substream;
    }

    /**
     * {@return the dialogue level the stream declares ({@code dialnorm}), in dBFS from -31 to -1;
     * -31 by default}
     */
    public int dialogueLevel() {
        return dialogueLevel;
    }

    /**
     * {@return whether the encoder switches a channel to two short transforms on a transient, as
     * {@link Ac3EncoderOptions#blockSwitching()} describes; {@code true} by default}
     */
    public boolean blockSwitching() {
        return blockSwitching;
    }

    /**
     * {@return whether the encoder may use channel coupling, under the rules of
     * {@link Ac3EncoderOptions#channelCoupling()}; {@code true} by default}
     */
    public boolean channelCoupling() {
        return channelCoupling;
    }

    /**
     * {@return whether the encoder may use spectral extension (ETSI TS 102 366 clause E.2.6);
     * {@code true} by default}
     *
     * <p>With spectral extension the channels code their spectrum as waveform up to a begin
     * frequency, and the decoder rebuilds the band above it, up to an end frequency, from a copy of
     * the lower band blended with noise and scaled to the original band energies. The encoder uses
     * it at 32, 44.1 and 48 kHz in a middle range of bit rates per full-bandwidth channel, with the
     * extension starting between about 8 and 12.5 kHz. The bits it frees raise the SNR below the
     * extension; the extended band keeps the energy of the original but not its waveform.
     *
     * @apiNote Turn it off when the decoded waveform above 8 kHz matters more than the accuracy of
     * the band below it, as in measurements that compare waveforms.
     */
    public boolean spectralExtension() {
        return spectralExtension;
    }

    /**
     * {@return whether the encoder may code stationary channels of six-block frames with the
     * adaptive hybrid transform (ETSI TS 102 366 clause E.2.4); {@code false} by default}
     *
     * <p>The transform is a DCT over the six blocks of each coefficient followed by vector and
     * gain-adaptive quantization. A channel uses it in a frame when its exponents are shared by the
     * six blocks, it uses long transforms throughout, and the error the transform adds is
     * outweighed by the bits it saves; such a channel is coded without dither. Frames of fewer than
     * six blocks never use it.
     *
     * @apiNote It is off by default: it raises the SNR of stationary material only a little for
     * more encoding time, and some widely deployed decoders read the vectors of high-efficiency
     * pointer 4 one row off table E.3.4, so the bins coded with them decode wrong there.
     */
    public boolean hybridTransform() {
        return hybridTransform;
    }

    /**
     * {@return whether the encoder may send transient pre-noise processing data (ETSI TS 102 366
     * clause E.2.7); {@code false} by default}
     *
     * <p>The data gives, for a channel with an onset, the location of the transient and a time
     * scaling length, with which the decoder overwrites the quantization noise spread ahead of the
     * transient with earlier audio. The encoder estimates the decoded output from its own
     * quantization error and sends the data when the processing removes at least half of the error
     * energy ahead of the transient. Frames of one or two blocks never carry it.
     *
     * @apiNote It is off by default: it lowers the error ahead of sharp onsets, but only over a few
     * hundred samples per onset, and some decoders ignore the data.
     */
    public boolean preNoiseProcessing() {
        return preNoiseProcessing;
    }

    @Override
    public boolean equals(Object object) {
        return object instanceof Eac3EncoderOptions that
                && sampleRate == that.sampleRate
                && layout.equals(that.layout)
                && bitrate == that.bitrate
                && blocksPerFrame == that.blocksPerFrame
                && dialogueLevel == that.dialogueLevel
                && blockSwitching == that.blockSwitching
                && channelCoupling == that.channelCoupling
                && spectralExtension == that.spectralExtension
                && hybridTransform == that.hybridTransform
                && preNoiseProcessing == that.preNoiseProcessing;
    }

    @Override
    public int hashCode() {
        return Objects.hash(sampleRate, layout, bitrate, blocksPerFrame, dialogueLevel, blockSwitching,
                channelCoupling, spectralExtension, hybridTransform, preNoiseProcessing);
    }

    @Override
    public String toString() {
        return "Eac3EncoderOptions[sampleRate=" + sampleRate + ", layout=" + layout.speakers() + ", bitrate=" + effectiveBitrate()
                + ", blocksPerFrame=" + blocksPerFrame + ", dialogueLevel=" + dialogueLevel + ", blockSwitching="
                + blockSwitching + ", channelCoupling=" + channelCoupling + ", spectralExtension=" + spectralExtension
                + ", hybridTransform=" + hybridTransform + ", preNoiseProcessing=" + preNoiseProcessing + "]";
    }

    /**
     * Returns whether a rate is one of the reduced rates, which code six blocks per frame.
     *
     * @param sampleRate the rate
     * @return whether the rate is 16, 22.05 or 24 kHz
     */
    private static boolean reduced(int sampleRate) {
        return sampleRate == 16_000 || sampleRate == 22_050 || sampleRate == 24_000;
    }

    /**
     * A builder of {@link Eac3EncoderOptions}, starting from {@link Eac3EncoderOptions#defaults()}.
     *
     * <p>Setters reject only {@code null}; {@link #build()} checks the values and how they combine.
     * A builder may build repeatedly. Instances are not thread-safe.
     */
    public static final class Builder {
        private int sampleRate;

        private ChannelLayout layout;

        private int bitrate;

        private boolean explicitBitrate;

        private int blocksPerFrame;

        private int dialogueLevel;

        private boolean blockSwitching;

        private boolean channelCoupling;

        private boolean spectralExtension;

        private boolean hybridTransform;

        private boolean preNoiseProcessing;

        private Builder() {
            this.sampleRate = 48_000;
            this.layout = ChannelLayout.STEREO;
            this.bitrate = 0;
            this.blocksPerFrame = 6;
            this.dialogueLevel = -31;
            this.blockSwitching = true;
            this.channelCoupling = true;
            this.spectralExtension = true;
            this.hybridTransform = false;
            this.preNoiseProcessing = false;
        }

        private Builder(Eac3EncoderOptions options) {
            this.sampleRate = options.sampleRate;
            this.layout = options.layout;
            this.bitrate = options.bitrate;
            this.explicitBitrate = options.bitrate != 0;
            this.blocksPerFrame = options.blocksPerFrame;
            this.dialogueLevel = options.dialogueLevel;
            this.blockSwitching = options.blockSwitching;
            this.channelCoupling = options.channelCoupling;
            this.spectralExtension = options.spectralExtension;
            this.hybridTransform = options.hybridTransform;
            this.preNoiseProcessing = options.preNoiseProcessing;
        }

        /**
         * Sets the sampling rate and the layout, mapped onto the closest the format carries as
         * {@link Eac3EncoderOptions#of(int, ChannelLayout)} maps them; see
         * {@link Eac3EncoderOptions#sampleRate()} and {@link Eac3EncoderOptions#layout()}.
         *
         * @param sampleRate the requested sampling rate in hertz
         * @param layout the requested channel layout
         * @return this builder
         */
        public Builder format(int sampleRate, ChannelLayout layout) {
            this.sampleRate = sampleRate == 32_000 || sampleRate == 44_100 || reduced(sampleRate) ? sampleRate : 48_000;
            this.layout = ChannelMap.codableEnhanced(Objects.requireNonNull(layout, "layout"));
            return this;
        }

        /**
         * Sets an explicit bit rate; see {@link Eac3EncoderOptions#bitrate()}.
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
         * {@link Eac3EncoderOptions#effectiveBitrate()}.
         *
         * @return this builder
         */
        public Builder automaticBitrate() {
            this.bitrate = 0;
            this.explicitBitrate = false;
            return this;
        }

        /**
         * Sets the number of audio blocks per frame; see {@link Eac3EncoderOptions#blocksPerFrame()}.
         *
         * @param blocksPerFrame the value
         * @return this builder
         */
        public Builder blocksPerFrame(int blocksPerFrame) {
            this.blocksPerFrame = blocksPerFrame;
            return this;
        }

        /**
         * Sets the declared dialogue level; see {@link Eac3EncoderOptions#dialogueLevel()}.
         *
         * @param dialogueLevel the value
         * @return this builder
         */
        public Builder dialogueLevel(int dialogueLevel) {
            this.dialogueLevel = dialogueLevel;
            return this;
        }

        /**
         * Sets whether block switching is on; see {@link Eac3EncoderOptions#blockSwitching()}.
         *
         * @param blockSwitching the value
         * @return this builder
         */
        public Builder blockSwitching(boolean blockSwitching) {
            this.blockSwitching = blockSwitching;
            return this;
        }

        /**
         * Sets whether channel coupling is allowed; see {@link Eac3EncoderOptions#channelCoupling()}.
         *
         * @param channelCoupling the value
         * @return this builder
         */
        public Builder channelCoupling(boolean channelCoupling) {
            this.channelCoupling = channelCoupling;
            return this;
        }

        /**
         * Sets whether spectral extension is allowed; see {@link Eac3EncoderOptions#spectralExtension()}.
         *
         * @param spectralExtension the value
         * @return this builder
         */
        public Builder spectralExtension(boolean spectralExtension) {
            this.spectralExtension = spectralExtension;
            return this;
        }

        /**
         * Sets whether the adaptive hybrid transform is allowed; see
         * {@link Eac3EncoderOptions#hybridTransform()}.
         *
         * @param hybridTransform the value
         * @return this builder
         */
        public Builder hybridTransform(boolean hybridTransform) {
            this.hybridTransform = hybridTransform;
            return this;
        }

        /**
         * Sets whether transient pre-noise processing data is allowed; see
         * {@link Eac3EncoderOptions#preNoiseProcessing()}.
         *
         * @param preNoiseProcessing the value
         * @return this builder
         */
        public Builder preNoiseProcessing(boolean preNoiseProcessing) {
            this.preNoiseProcessing = preNoiseProcessing;
            return this;
        }

        /**
         * Returns the options holding the settings of this builder.
         *
         * @return the options
         * @throws IllegalArgumentException if the blocks per frame are not 1, 2, 3 or 6, a reduced
         * sampling rate is combined with fewer than six blocks per frame, the dialogue level is not in
         * [-31, -1], or the bit rate lies outside the range of the format
         */
        public Eac3EncoderOptions build() {
            if (blocksPerFrame != 1 && blocksPerFrame != 2 && blocksPerFrame != 3 && blocksPerFrame != 6) {
                throw new IllegalArgumentException("blocks per frame " + blocksPerFrame + " is not 1, 2, 3 or 6");
            }
            if (reduced(sampleRate) && blocksPerFrame != 6) {
                throw new IllegalArgumentException("sampling rate " + sampleRate + " needs six blocks per frame");
            }
            Checks.inRange("dialogue level", dialogueLevel, -31, -1);
            var options = new Eac3EncoderOptions(this);
            if (explicitBitrate) {
                Checks.inRange("bitrate", bitrate, options.minimumBitrate(), options.maximumBitrate());
            }
            return options;
        }
    }
}
