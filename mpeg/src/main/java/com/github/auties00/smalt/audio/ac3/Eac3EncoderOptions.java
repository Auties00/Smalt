package com.github.auties00.smalt.audio.ac3;

import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.ac3.internal.ChannelMap;
import com.github.auties00.smalt.audio.ac3.internal.EncoderSession;
import com.github.auties00.smalt.audio.ac3.internal.ProgramPlan;
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

    private final boolean enhancedCoupling;

    private final Ac3Metadata metadata;

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
        this.enhancedCoupling = builder.enhancedCoupling;
        this.metadata = builder.metadata;
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
     * layout keeps its core, the front pair, the center, the surround pair (side, else back) or the
     * single back center, and the LFE, and beyond it every channel E-AC-3 has a custom channel map
     * location for: the back pair beside a side pair, the back center beside a surround pair, the
     * left and right of center pair, the top center, the surround direct pair, the wide pair, the
     * top front pair, the top front center, the top back pair, and the second LFE beside the first;
     * a pair is kept only with both its channels. Everything else is dropped; a layout without front
     * pair or center codes the front pair, and a bare count of eight or more channels codes 7.1, of
     * seven or fewer the default layout of at most six. Callers convert their input to the reported
     * {@link #sampleRate()} and {@link #layout()}.
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
     * <p>The channel configurations of AC-3, mono to 5.1, code in one independent substream; a
     * larger program codes its 5.1 core in the independent substream and its other channels in
     * dependent substreams (see {@link Eac3Encoder} and {@link #of(int, ChannelLayout)}).
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
     * coordinates and block headers, and at least 32 kbit/s; for a program beyond 5.1, the lowest
     * rate whose share gives every substream its lowest rate.
     *
     * @return the bit rate in bits per second
     */
    public int minimumBitrate() {
        var plan = EncoderSession.plan(layout, true);
        var substreams = plan.substreams();
        var total = 0L;
        for (var substream : substreams) {
            var least = substreamMinimum(substream.fullBandwidthChannels(), substream.lfe());
            total = Math.max(total, (least * (long) plan.fullBandwidthChannels() + substream.fullBandwidthChannels() - 1)
                    / substream.fullBandwidthChannels());
        }
        while (!fits(plan, (int) total, true)) {
            total++;
        }
        return (int) total;
    }

    /**
     * Returns whether every substream of a program receives at least its lowest bit rate, or at
     * most the bit rate of frames of {@link #MAXIMUM_WORDS} words.
     *
     * @param plan the substreams
     * @param total the bit rate of the program
     * @param lowest whether to check the lowest bit rate rather than the highest
     * @return whether the share of every substream fits
     */
    private boolean fits(ProgramPlan plan, int total, boolean lowest) {
        var rates = plan.bitrates(total);
        var substreams = plan.substreams();
        for (var index = 0; index < rates.length; index++) {
            var substream = substreams.get(index);
            var fits = lowest ? rates[index] >= substreamMinimum(substream.fullBandwidthChannels(), substream.lfe())
                    : rates[index] <= maximumSubstream();
            if (!fits) {
                return false;
            }
        }
        return true;
    }

    /**
     * {@return the bit rate of a substream whose frames hold {@link #MAXIMUM_WORDS} words}
     */
    private long maximumSubstream() {
        return MAXIMUM_WORDS * 16L * sampleRate / (256L * blocksPerFrame);
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
     * 48 kHz with six blocks per frame and 6144 kbit/s with one; for a program beyond 5.1, the rate
     * whose share fills the frames of the substream with the largest share.
     *
     * @return the bit rate in bits per second
     */
    public int maximumBitrate() {
        var substream = maximumSubstream();
        var plan = EncoderSession.plan(layout, true);
        if (plan.substreams().size() == 1) {
            return (int) substream;
        }
        var total = Long.MAX_VALUE;
        for (var part : plan.substreams()) {
            total = Math.min(total, substream * plan.fullBandwidthChannels() / part.fullBandwidthChannels());
        }
        total = Math.min(total, Integer.MAX_VALUE);
        while (!fits(plan, (int) total, false)) {
            total--;
        }
        return (int) total;
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
     * transient with earlier audio. Decoders apply data for transients from 1280 to 2815 decoded
     * samples after the start of the frame carrying it, mostly in the frames after it, so the encoder
     * codes those frames before it chooses a frame's data and emits each packet that many frames
     * later (see {@link Eac3Encoder}). It estimates the decoded output from its own quantization error
     * and sends the data when the processing removes at least half of the error energy ahead of the
     * transient. Frames of one or two blocks never carry it.
     *
     * @apiNote It is off by default: it lowers the error ahead of sharp onsets, but only over a few
     * hundred samples per onset, delays the packets, and some decoders ignore the data.
     */
    public boolean preNoiseProcessing() {
        return preNoiseProcessing;
    }

    /**
     * {@return whether the encoder codes channel coupling as enhanced coupling (ETSI TS 102 366
     * clause E.2.5) instead of standard coupling; {@code false} by default}
     *
     * <p>Enhanced coupling sends, for every coupled channel and band of the coupling region, an
     * amplitude relative to the coupling channel, on a band structure finer than that of standard
     * coupling. The encoder codes the coupling region standard coupling would cover, without phase
     * angles, which decoders ignore, and only where it uses coupling (see {@link #channelCoupling()});
     * a region whose first sub-band enhanced coupling cannot signal is coded with standard coupling.
     *
     * @apiNote It is off by default: widely deployed decoders, ffmpeg among them, reject every
     * audio block that uses it; Dolby's decoders read it.
     */
    public boolean enhancedCoupling() {
        return enhancedCoupling;
    }

    /**
     * {@return the metadata the frames carry besides the dialogue level; {@link Ac3Metadata#defaults()}
     * by default}
     *
     * <p>The independent substream carries the mixing and informational metadata; the dependent
     * substreams of a program beyond 5.1 carry only the dialogue level and the dynamic range words,
     * which equal those of the independent substream.
     */
    public Ac3Metadata metadata() {
        return metadata;
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
                && preNoiseProcessing == that.preNoiseProcessing
                && enhancedCoupling == that.enhancedCoupling
                && metadata.equals(that.metadata);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sampleRate, layout, bitrate, blocksPerFrame, dialogueLevel, blockSwitching,
                channelCoupling, spectralExtension, hybridTransform, preNoiseProcessing, enhancedCoupling, metadata);
    }

    @Override
    public String toString() {
        return "Eac3EncoderOptions[sampleRate=" + sampleRate + ", layout=" + layout.speakers() + ", bitrate=" + effectiveBitrate()
                + ", blocksPerFrame=" + blocksPerFrame + ", dialogueLevel=" + dialogueLevel + ", blockSwitching="
                + blockSwitching + ", channelCoupling=" + channelCoupling + ", spectralExtension=" + spectralExtension
                + ", hybridTransform=" + hybridTransform + ", preNoiseProcessing=" + preNoiseProcessing
                + ", enhancedCoupling=" + enhancedCoupling + ", metadata=" + metadata + "]";
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

        private boolean enhancedCoupling;

        private Ac3Metadata metadata;

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
            this.enhancedCoupling = false;
            this.metadata = Ac3Metadata.defaults();
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
            this.enhancedCoupling = options.enhancedCoupling;
            this.metadata = options.metadata;
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
         * Sets whether coupling is coded as enhanced coupling; see
         * {@link Eac3EncoderOptions#enhancedCoupling()}.
         *
         * @param enhancedCoupling the value
         * @return this builder
         */
        public Builder enhancedCoupling(boolean enhancedCoupling) {
            this.enhancedCoupling = enhancedCoupling;
            return this;
        }

        /**
         * Sets the metadata; see {@link Eac3EncoderOptions#metadata()}.
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
            Ac3EncoderOptions.checkServiceType(metadata, layout);
            var options = new Eac3EncoderOptions(this);
            if (explicitBitrate) {
                Checks.inRange("bitrate", bitrate, options.minimumBitrate(), options.maximumBitrate());
            }
            return options;
        }
    }
}
