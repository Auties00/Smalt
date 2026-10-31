package com.github.auties00.smalt.audio.opus;

import com.github.auties00.smalt.audio.Ambisonics;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.Speaker;
import com.github.auties00.smalt.audio.opus.internal.OpusMultistreamEncoder;
import com.github.auties00.smalt.internal.check.Checks;
import com.github.auties00.smalt.internal.header.VorbisChannelOrder;

import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;

/**
 * Holds the settings of an {@link OpusEncoder}. Instances are immutable and thread-safe; create them
 * with {@link #builder()} or a preset such as {@link #defaults()}.
 */
public final class OpusEncoderOptions {
    /**
     * The smallest bitrate the encoder accepts, in bits per second.
     */
    private static final int MIN_BITRATE = 500;

    /**
     * The largest bitrate per channel the encoder accepts, in bits per second.
     */
    private static final int MAX_CHANNEL_BITRATE = 300_000;

    /**
     * The largest number of channels a stream carries.
     */
    private static final int MAX_CHANNELS = 255;

    /**
     * The options of {@link #defaults()}.
     */
    private static final OpusEncoderOptions DEFAULTS = new Builder().build();

    /**
     * The options of {@link #forRealtime()}.
     */
    private static final OpusEncoderOptions REALTIME = new Builder()
            .bitrateMode(OpusBitrateMode.CONSTRAINED_VBR)
            .complexity(9)
            .build();

    private final int sampleRate;

    private final ChannelLayout layout;

    /**
     * The channel mapping, or {@code null} to choose it from the layout.
     */
    private final OpusChannelMapping channelMapping;

    private final OpusApplication application;

    /**
     * The target bitrate in bits per second, or {@code 0} for the automatic bitrate.
     */
    private final int bitrate;

    private final OpusBitrateMode bitrateMode;

    private final int complexity;

    private final int frameDurationMicros;

    private final OpusSignal signal;

    private final OpusBandwidth maxBandwidth;

    private final boolean inbandFec;

    private final int packetLossPercent;

    private final boolean dtx;

    private final int dredDurationMillis;

    private OpusEncoderOptions(Builder builder, ChannelLayout layout) {
        this.sampleRate = builder.sampleRate;
        this.layout = layout;
        this.channelMapping = builder.channelMapping;
        this.application = builder.application;
        this.bitrate = builder.bitrate == null ? 0 : builder.bitrate;
        this.bitrateMode = builder.bitrateMode;
        this.complexity = builder.complexity;
        this.frameDurationMicros = builder.frameDurationMicros;
        this.signal = builder.signal;
        this.maxBandwidth = builder.maxBandwidth;
        this.inbandFec = builder.inbandFec;
        this.packetLossPercent = builder.packetLossPercent;
        this.dtx = builder.dtx;
        this.dredDurationMillis = builder.dredDurationMillis;
    }

    /**
     * Returns the options for coding music or mixed content into a file: 48 kHz stereo, the
     * {@link OpusApplication#AUDIO} application, the automatic bitrate, unconstrained
     * {@link OpusBitrateMode#VBR}, complexity 10, 20 ms packets, an automatic signal type, no
     * bandwidth restriction, and no forward error correction, expected loss or discontinuous
     * transmission.
     *
     * @return the default options
     */
    public static OpusEncoderOptions defaults() {
        return DEFAULTS;
    }

    /**
     * Returns the options for coding speech into a file: 16 kHz mono, the
     * {@link OpusApplication#VOIP} application, the given bitrate, unconstrained
     * {@link OpusBitrateMode#VBR}, complexity 10, 20 ms packets, an automatic signal type and no
     * bandwidth restriction.
     *
     * @param bitrate the target bitrate in bits per second, between 500 and 300000
     * @return the speech options
     * @throws IllegalArgumentException if {@code bitrate} is out of range
     */
    public static OpusEncoderOptions forSpeech(int bitrate) {
        return new Builder()
                .format(16_000, 1)
                .application(OpusApplication.VOIP)
                .bitrate(bitrate)
                .build();
    }

    /**
     * Returns the options for real-time 48 kHz stereo general audio: the
     * {@link OpusApplication#AUDIO} application, the automatic bitrate,
     * {@link OpusBitrateMode#CONSTRAINED_VBR}, complexity 9, 20 ms packets, an automatic signal
     * type, no bandwidth restriction, and no forward error correction, expected loss or
     * discontinuous transmission.
     *
     * @apiNote Real-time senders start from these settings and retarget the bitrate, the
     * expected loss or the complexity through the {@link OpusEncoder} setters as the network
     * changes. Use {@link #forVoip(int, int)} for a voice call.
     *
     * @return the real-time options
     */
    public static OpusEncoderOptions forRealtime() {
        return REALTIME;
    }

    /**
     * Returns the options for a voice call: the {@link OpusApplication#VOIP} application at the
     * given format, otherwise the same as {@link #forRealtime()}.
     *
     * @param sampleRate the input sampling rate: 8000, 12000, 16000, 24000 or 48000 Hz
     * @param channels the number of input channels, from 1 to 255, in the layout of
     * {@link ChannelLayout#defaultFor(int)}
     * @return the voice call options
     * @throws IllegalArgumentException if the rate or the channel count is not supported
     */
    public static OpusEncoderOptions forVoip(int sampleRate, int channels) {
        return REALTIME.toBuilder()
                .format(sampleRate, channels)
                .application(OpusApplication.VOIP)
                .build();
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
     * {@return the input sampling rate: 8000, 12000, 16000, 24000 or 48000 Hz; 48000 by default}
     */
    public int sampleRate() {
        return sampleRate;
    }

    /**
     * {@return the number of input channels, from 1 to 255; 2 by default}
     */
    public int channels() {
        return layout.channels();
    }

    /**
     * {@return the input channel layout; {@link ChannelLayout#STEREO} by default}
     *
     * <p>The planes of every {@link com.github.auties00.smalt.audio.AudioBuffer} the encoder takes, and
     * the channels of every interleaved frame, are in this order. With the {@link OpusChannelMapping#VORBIS}
     * mapping and three or more channels the speakers decide where each channel goes in the stream;
     * with one or two channels, and with the other mappings, only the order counts: the first of
     * two channels is coded as left.
     */
    public ChannelLayout layout() {
        return layout;
    }

    /**
     * {@return the channel mapping family the stream uses}
     *
     * <p>Unless one is set, the mapping is {@link OpusChannelMapping#AMBISONIC} for a layout with
     * {@linkplain ChannelLayout#ambisonics() ambisonics}, {@link OpusChannelMapping#VORBIS} for one or two
     * channels and for the layouts {@code VORBIS} accepts, and {@link OpusChannelMapping#DISCRETE} otherwise.
     */
    public OpusChannelMapping channelMapping() {
        if (channelMapping != null) {
            return channelMapping;
        }
        if (layout.ambisonics().isPresent()) {
            return OpusChannelMapping.AMBISONIC;
        }
        return vorbisRouting(layout) != null ? OpusChannelMapping.VORBIS : OpusChannelMapping.DISCRETE;
    }

    /**
     * Returns whether a layout is one the ambisonic mappings code: every component of an order in ACN order
     * with SN3D normalization, optionally followed by front left and front right.
     *
     * @param layout the input layout
     * @return {@code true} when a decoder of an ambisonic mapping outputs this layout
     */
    static boolean isOpusAmbisonic(ChannelLayout layout) {
        var ambisonics = layout.ambisonics().orElse(null);
        if (ambisonics == null || ambisonics.normalization() != Ambisonics.Normalization.SN3D
                || !ambisonics.isSequential()) {
            return false;
        }
        return layout.equals(ChannelLayout.ofAmbisonics(ambisonics))
                || layout.equals(ChannelLayout.ofAmbisonics(ambisonics, List.of(Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT)));
    }

    /**
     * Returns the input channel feeding each channel of the Vorbis order, for a layout the
     * {@link OpusChannelMapping#VORBIS} mapping accepts.
     *
     * @param layout the input layout
     * @return a new array whose entry {@code v} is the index in {@code layout} of Vorbis channel
     * {@code v}, or {@code null} when the mapping does not accept the layout
     */
    static int[] vorbisRouting(ChannelLayout layout) {
        var channels = layout.channels();
        if (channels <= 2) {
            return channels == 1 ? new int[]{0} : new int[]{0, 1};
        }
        if (channels > VorbisChannelOrder.MAX_CHANNELS) {
            return null;
        }
        var speakers = layout.speakers();
        var expected = ChannelLayout.defaultFor(channels).speakers();
        var sidesForBacks = channels <= 6 && !speakers.contains(Speaker.BACK_LEFT)
                && !speakers.contains(Speaker.BACK_RIGHT);
        var route = new int[channels];
        var used = new boolean[channels];
        for (var v = 0; v < channels; v++) {
            var speaker = expected.get(VorbisChannelOrder.waveIndex(channels, v));
            if (sidesForBacks && speaker == Speaker.BACK_LEFT) {
                speaker = Speaker.SIDE_LEFT;
            } else if (sidesForBacks && speaker == Speaker.BACK_RIGHT) {
                speaker = Speaker.SIDE_RIGHT;
            }
            var index = speakers.indexOf(speaker);
            if (index < 0 || used[index]) {
                return null;
            }
            used[index] = true;
            route[v] = index;
        }
        return route;
    }

    /**
     * {@return the use case the mode and bandwidth decisions favor; {@link OpusApplication#AUDIO}
     * by default}
     *
     * <p>The application is fixed when the encoder is created.
     */
    public OpusApplication application() {
        return application;
    }

    /**
     * {@return the target bitrate in bits per second, from 500 to {@code 300000 * channels()}, or
     * empty (the default) for the automatic bitrate the encoder derives from the format and the
     * packet duration}
     *
     * <p>The automatic bitrate is about 99 kb/s for 48 kHz stereo in 20 ms packets, and about
     * 308 kb/s for 48 kHz 5.1. The bitrate is the total of all streams; a multistream encoder
     * raises a bitrate below 500 per channel to that.
     */
    public OptionalInt bitrate() {
        return bitrate == 0 ? OptionalInt.empty() : OptionalInt.of(bitrate);
    }

    /**
     * {@return the bitrate control; {@link OpusBitrateMode#VBR} by default}
     */
    public OpusBitrateMode bitrateMode() {
        return bitrateMode;
    }

    /**
     * {@return the computational complexity, from 0 (fastest) to 10 (best); 10 by default}
     */
    public int complexity() {
        return complexity;
    }

    /**
     * {@return the duration of each packet the {@linkplain OpusEncoder#encode(
     * com.github.auties00.smalt.audio.AudioBuffer, java.util.function.Consumer) buffering encode}
     * emits, in microseconds: 2500, 5000, 10000, 20000, 40000, 60000, 80000, 100000 or 120000; 20000
     * by default}
     */
    public int frameDurationMicros() {
        return frameDurationMicros;
    }

    /**
     * {@return the number of samples per channel in one buffered packet,
     * {@code sampleRate() * frameDurationMicros() / 1000000}}
     */
    public int frameSize() {
        return (int) ((long) sampleRate * frameDurationMicros / 1_000_000);
    }

    /**
     * {@return the content hint; {@link OpusSignal#AUTO} by default}
     */
    public OpusSignal signal() {
        return signal;
    }

    /**
     * {@return the widest audio bandwidth the encoder may choose; {@link OpusBandwidth#FULLBAND},
     * the default, means no restriction}
     */
    public OpusBandwidth maxBandwidth() {
        return maxBandwidth;
    }

    /**
     * {@return whether packets carry a low-bitrate copy of the previous packet for in-band forward
     * error correction; {@code false} by default}
     *
     * <p>The copy is coded only in SILK and hybrid packets, and only while the
     * {@linkplain #packetLossPercent() expected loss} is high enough for the bitrate.
     */
    public boolean inbandFec() {
        return inbandFec;
    }

    /**
     * {@return the packet loss the encoder expects, in percent from 0 to 100; 0 by default}
     */
    public int packetLossPercent() {
        return packetLossPercent;
    }

    /**
     * {@return whether discontinuous transmission is on, so that packets judged silent shrink to
     * one or two bytes; {@code false} by default}
     */
    public boolean dtx() {
        return dtx;
    }

    /**
     * {@return how much speech before each packet its Deep REDundancy (DRED) covers, in milliseconds from 0 to
     * 1040 in steps of 10; 0, no DRED, by default}
     *
     * <p>DRED adds to the first non-DTX frame of each packet a padding extension that describes up to this much of
     * the preceding speech with a neural codec at a very low bitrate, so that a receiver that lost packets can
     * rebuild them from a later one ({@link OpusDred}, {@link OpusDecoder#recover(OpusDred, int, float[], int,
     * int)}). The bits come out of the packet's budget, more as the {@linkplain #packetLossPercent() expected loss}
     * rises: with VBR and no expected loss no DRED is sent, and with in-band FEC DRED takes a smaller share. Only a
     * mono or stereo encoder of a single stream codes it; the DRED encoder's networks load when the encoder is
     * created and cost processing time on every frame.
     */
    public int dredDurationMillis() {
        return dredDurationMillis;
    }

    @Override
    public boolean equals(Object object) {
        return object instanceof OpusEncoderOptions that
                && sampleRate == that.sampleRate
                && layout.equals(that.layout)
                && channelMapping == that.channelMapping
                && application == that.application
                && bitrate == that.bitrate
                && bitrateMode == that.bitrateMode
                && complexity == that.complexity
                && frameDurationMicros == that.frameDurationMicros
                && signal == that.signal
                && maxBandwidth == that.maxBandwidth
                && inbandFec == that.inbandFec
                && packetLossPercent == that.packetLossPercent
                && dtx == that.dtx
                && dredDurationMillis == that.dredDurationMillis;
    }

    @Override
    public int hashCode() {
        return Objects.hash(sampleRate, layout, channelMapping, application, bitrate, bitrateMode, complexity, frameDurationMicros,
                signal, maxBandwidth, inbandFec, packetLossPercent, dtx, dredDurationMillis);
    }

    @Override
    public String toString() {
        return "OpusEncoderOptions[sampleRate=" + sampleRate
                + ", layout=" + layout
                + ", channelMapping=" + (channelMapping == null ? "auto" : channelMapping.toString())
                + ", application=" + application
                + ", bitrate=" + (bitrate == 0 ? "auto" : String.valueOf(bitrate))
                + ", bitrateMode=" + bitrateMode
                + ", complexity=" + complexity
                + ", frameDurationMicros=" + frameDurationMicros
                + ", signal=" + signal
                + ", maxBandwidth=" + maxBandwidth
                + ", inbandFec=" + inbandFec
                + ", packetLossPercent=" + packetLossPercent
                + ", dtx=" + dtx
                + ", dredDurationMillis=" + dredDurationMillis + "]";
    }

    /**
     * A builder of {@link OpusEncoderOptions}.
     *
     * <p>Setters reject only {@code null}; {@link #build()} checks the ranges. Instances are not
     * thread-safe.
     */
    public static final class Builder {
        private int sampleRate;

        /**
         * The input layout, or {@code null} when {@link #channels} selects the default layout of a
         * channel count at build time.
         */
        private ChannelLayout layout;

        /**
         * The channel count of {@link #format(int, int)}, used while {@link #layout} is {@code null}.
         */
        private int channels;

        /**
         * The channel mapping, or {@code null} to choose it from the layout.
         */
        private OpusChannelMapping channelMapping;

        private OpusApplication application;

        /**
         * The target bitrate, or {@code null} for the automatic bitrate.
         */
        private Integer bitrate;

        private OpusBitrateMode bitrateMode;

        private int complexity;

        private int frameDurationMicros;

        private OpusSignal signal;

        private OpusBandwidth maxBandwidth;

        private boolean inbandFec;

        private int packetLossPercent;

        private boolean dtx;

        private int dredDurationMillis;

        private Builder() {
            this.sampleRate = 48_000;
            this.layout = ChannelLayout.STEREO;
            this.application = OpusApplication.AUDIO;
            this.bitrateMode = OpusBitrateMode.VBR;
            this.complexity = 10;
            this.frameDurationMicros = 20_000;
            this.signal = OpusSignal.AUTO;
            this.maxBandwidth = OpusBandwidth.FULLBAND;
        }

        private Builder(OpusEncoderOptions options) {
            this.sampleRate = options.sampleRate;
            this.layout = options.layout;
            this.channelMapping = options.channelMapping;
            this.application = options.application;
            this.bitrate = options.bitrate == 0 ? null : options.bitrate;
            this.bitrateMode = options.bitrateMode;
            this.complexity = options.complexity;
            this.frameDurationMicros = options.frameDurationMicros;
            this.signal = options.signal;
            this.maxBandwidth = options.maxBandwidth;
            this.inbandFec = options.inbandFec;
            this.packetLossPercent = options.packetLossPercent;
            this.dtx = options.dtx;
            this.dredDurationMillis = options.dredDurationMillis;
        }

        /**
         * Sets the input format with the layout {@link ChannelLayout#defaultFor(int)} gives the
         * channel count; see {@link OpusEncoderOptions#sampleRate()} and
         * {@link OpusEncoderOptions#layout()}.
         *
         * @param sampleRate the sampling rate
         * @param channels the number of channels
         * @return this builder
         */
        public Builder format(int sampleRate, int channels) {
            this.sampleRate = sampleRate;
            this.layout = null;
            this.channels = channels;
            return this;
        }

        /**
         * Sets the input format; see {@link OpusEncoderOptions#sampleRate()} and
         * {@link OpusEncoderOptions#layout()}.
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
         * Sets the channel mapping family; see {@link OpusEncoderOptions#channelMapping()}.
         *
         * @param channelMapping the value
         * @return this builder
         */
        public Builder channelMapping(OpusChannelMapping channelMapping) {
            this.channelMapping = Objects.requireNonNull(channelMapping, "channelMapping");
            return this;
        }

        /**
         * Lets the encoder choose the channel mapping family from the layout; see
         * {@link OpusEncoderOptions#channelMapping()}.
         *
         * @return this builder
         */
        public Builder automaticChannelMapping() {
            this.channelMapping = null;
            return this;
        }

        /**
         * Sets the tuning of the mode decisions; see {@link OpusEncoderOptions#application()}.
         *
         * @param application the value
         * @return this builder
         */
        public Builder application(OpusApplication application) {
            this.application = Objects.requireNonNull(application, "application");
            return this;
        }

        /**
         * Sets an explicit target bitrate; see {@link OpusEncoderOptions#bitrate()}.
         *
         * @param bitrate the value
         * @return this builder
         */
        public Builder bitrate(int bitrate) {
            this.bitrate = bitrate;
            return this;
        }

        /**
         * Selects the automatic bitrate the encoder derives from the format and the packet duration;
         * see {@link OpusEncoderOptions#bitrate()}.
         *
         * @return this builder
         */
        public Builder automaticBitrate() {
            this.bitrate = null;
            return this;
        }

        /**
         * Sets the bitrate control; see {@link OpusEncoderOptions#bitrateMode()}.
         *
         * @param bitrateMode the value
         * @return this builder
         */
        public Builder bitrateMode(OpusBitrateMode bitrateMode) {
            this.bitrateMode = Objects.requireNonNull(bitrateMode, "bitrateMode");
            return this;
        }

        /**
         * Sets the computational complexity; see {@link OpusEncoderOptions#complexity()}.
         *
         * @param complexity the value
         * @return this builder
         */
        public Builder complexity(int complexity) {
            this.complexity = complexity;
            return this;
        }

        /**
         * Sets the duration of each buffered packet; see
         * {@link OpusEncoderOptions#frameDurationMicros()}.
         *
         * @param frameDurationMicros the value
         * @return this builder
         */
        public Builder frameDurationMicros(int frameDurationMicros) {
            this.frameDurationMicros = frameDurationMicros;
            return this;
        }

        /**
         * Sets the content hint; see {@link OpusEncoderOptions#signal()}.
         *
         * @param signal the value
         * @return this builder
         */
        public Builder signal(OpusSignal signal) {
            this.signal = Objects.requireNonNull(signal, "signal");
            return this;
        }

        /**
         * Sets the widest audio bandwidth the encoder may choose; see
         * {@link OpusEncoderOptions#maxBandwidth()}.
         *
         * @param maxBandwidth the value
         * @return this builder
         */
        public Builder maxBandwidth(OpusBandwidth maxBandwidth) {
            this.maxBandwidth = Objects.requireNonNull(maxBandwidth, "maxBandwidth");
            return this;
        }

        /**
         * Sets whether packets carry in-band forward error correction data; see
         * {@link OpusEncoderOptions#inbandFec()}.
         *
         * @param inbandFec the value
         * @return this builder
         */
        public Builder inbandFec(boolean inbandFec) {
            this.inbandFec = inbandFec;
            return this;
        }

        /**
         * Sets the packet loss the encoder expects; see {@link OpusEncoderOptions#packetLossPercent()}.
         *
         * @param packetLossPercent the value
         * @return this builder
         */
        public Builder packetLossPercent(int packetLossPercent) {
            this.packetLossPercent = packetLossPercent;
            return this;
        }

        /**
         * Sets whether discontinuous transmission is on; see {@link OpusEncoderOptions#dtx()}.
         *
         * @param dtx the value
         * @return this builder
         */
        public Builder dtx(boolean dtx) {
            this.dtx = dtx;
            return this;
        }

        /**
         * Sets how much speech the Deep REDundancy of each packet covers; see
         * {@link OpusEncoderOptions#dredDurationMillis()}.
         *
         * @param dredDurationMillis the value
         * @return this builder
         */
        public Builder dredDurationMillis(int dredDurationMillis) {
            this.dredDurationMillis = dredDurationMillis;
            return this;
        }

        /**
         * Turns Deep REDundancy off; see {@link OpusEncoderOptions#dredDurationMillis()}.
         *
         * @return this builder
         */
        public Builder noDred() {
            this.dredDurationMillis = 0;
            return this;
        }

        /**
         * Returns the options holding the settings of this builder.
         *
         * @return the options
         * @throws IllegalArgumentException if the sampling rate is not 8000, 12000, 16000, 24000 or
         * 48000 Hz, the channel count is outside 1 to 255, the layout has ambisonics other than every
         * component of an order in ACN order with SN3D normalization, optionally followed by front left and
         * right, and the mapping is not {@link OpusChannelMapping#DISCRETE}, the {@link OpusChannelMapping#VORBIS}
         * mapping is set for a layout it does not accept, the {@link OpusChannelMapping#AMBISONIC}
         * mapping is set for a channel count that is not an ambisonic order squared, optionally plus
         * two, the {@link OpusChannelMapping#AMBISONIC_PROJECTION} mapping is set for a channel count
         * that is not the square of an order from 1 to 5, optionally plus two, an explicit bitrate is
         * outside 500 to {@code 300000} per channel, the complexity is outside 0 to 10, the packet
         * duration is not an Opus packet duration, the expected loss is outside 0 to 100, or the DRED duration is
         * not a multiple of 10 from 0 to 1040 or is set for more than two channels
         */
        public OpusEncoderOptions build() {
            OpusDecoderOptions.checkSampleRate("sample rate", sampleRate);
            var resolved = layout;
            if (resolved == null) {
                Checks.inRange("channel count", channels, 1, MAX_CHANNELS);
                resolved = ChannelLayout.defaultFor(channels);
            }
            var count = resolved.channels();
            Checks.inRange("channel count", count, 1, MAX_CHANNELS);
            if (resolved.ambisonics().isPresent() && channelMapping != OpusChannelMapping.DISCRETE
                    && !isOpusAmbisonic(resolved)) {
                throw new IllegalArgumentException("Opus codes ambisonics only as every component of an order in"
                        + " ACN order with SN3D normalization, optionally followed by front left and right, not "
                        + resolved);
            }
            if (channelMapping == OpusChannelMapping.VORBIS && vorbisRouting(resolved) == null) {
                throw new IllegalArgumentException("the Vorbis channel mapping does not code the layout " + resolved);
            }
            if (channelMapping == OpusChannelMapping.AMBISONIC
                    && !OpusMultistreamEncoder.isAmbisonicChannelCount(count)) {
                throw new IllegalArgumentException("the ambisonic channel mapping does not code " + count + " channels");
            }
            if (channelMapping == OpusChannelMapping.AMBISONIC_PROJECTION
                    && OpusMultistreamEncoder.projectionOrder(count) < 0) {
                throw new IllegalArgumentException("the ambisonic projection channel mapping does not code " + count
                        + " channels");
            }
            if (bitrate != null) {
                Checks.inRange("bitrate", bitrate, MIN_BITRATE, MAX_CHANNEL_BITRATE * count);
            }
            Checks.inRange("complexity", complexity, 0, 10);
            switch (frameDurationMicros) {
                case 2500, 5000, 10000, 20000, 40000, 60000, 80000, 100000, 120000 -> {
                }
                default -> throw new IllegalArgumentException("frame duration " + frameDurationMicros
                        + " us is not one of 2500, 5000, 10000, 20000, 40000, 60000, 80000, 100000, 120000");
            }
            Checks.inRange("packet loss percent", packetLossPercent, 0, 100);
            Checks.inRange("DRED duration", dredDurationMillis, 0, 1040);
            if (dredDurationMillis % 10 != 0) {
                throw new IllegalArgumentException("DRED duration " + dredDurationMillis
                        + " ms is not a multiple of 10 ms");
            }
            if (dredDurationMillis > 0 && count > 2) {
                throw new IllegalArgumentException("DRED needs a mono or stereo encoder, not " + count + " channels");
            }
            return new OpusEncoderOptions(this, resolved);
        }
    }
}
