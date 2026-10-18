package com.github.auties00.smalt.audio.opus;

import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.internal.check.Checks;

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

    private final int channels;

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

    private OpusEncoderOptions(Builder builder) {
        this.sampleRate = builder.sampleRate;
        this.channels = builder.channels;
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
     * @param channels the number of input channels: 1 or 2
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
     * {@return the number of input channels, 1 or 2; 2 by default}
     */
    public int channels() {
        return channels;
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
     * <p>The automatic bitrate is about 99 kb/s for 48 kHz stereo in 20 ms packets.
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

    @Override
    public boolean equals(Object object) {
        return object instanceof OpusEncoderOptions that
                && sampleRate == that.sampleRate
                && channels == that.channels
                && application == that.application
                && bitrate == that.bitrate
                && bitrateMode == that.bitrateMode
                && complexity == that.complexity
                && frameDurationMicros == that.frameDurationMicros
                && signal == that.signal
                && maxBandwidth == that.maxBandwidth
                && inbandFec == that.inbandFec
                && packetLossPercent == that.packetLossPercent
                && dtx == that.dtx;
    }

    @Override
    public int hashCode() {
        return Objects.hash(sampleRate, channels, application, bitrate, bitrateMode, complexity, frameDurationMicros,
                signal, maxBandwidth, inbandFec, packetLossPercent, dtx);
    }

    @Override
    public String toString() {
        return "OpusEncoderOptions[sampleRate=" + sampleRate
                + ", channels=" + channels
                + ", application=" + application
                + ", bitrate=" + (bitrate == 0 ? "auto" : String.valueOf(bitrate))
                + ", bitrateMode=" + bitrateMode
                + ", complexity=" + complexity
                + ", frameDurationMicros=" + frameDurationMicros
                + ", signal=" + signal
                + ", maxBandwidth=" + maxBandwidth
                + ", inbandFec=" + inbandFec
                + ", packetLossPercent=" + packetLossPercent
                + ", dtx=" + dtx + "]";
    }

    /**
     * A builder of {@link OpusEncoderOptions}.
     *
     * <p>Setters reject only {@code null}; {@link #build()} checks the ranges. Instances are not
     * thread-safe.
     */
    public static final class Builder {
        private int sampleRate;

        private int channels;

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

        private Builder() {
            this.sampleRate = 48_000;
            this.channels = 2;
            this.application = OpusApplication.AUDIO;
            this.bitrateMode = OpusBitrateMode.VBR;
            this.complexity = 10;
            this.frameDurationMicros = 20_000;
            this.signal = OpusSignal.AUTO;
            this.maxBandwidth = OpusBandwidth.FULLBAND;
        }

        private Builder(OpusEncoderOptions options) {
            this.sampleRate = options.sampleRate;
            this.channels = options.channels;
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
        }

        /**
         * Sets the input format; see {@link OpusEncoderOptions#sampleRate()} and
         * {@link OpusEncoderOptions#channels()}.
         *
         * @param sampleRate the sampling rate
         * @param channels the number of channels
         * @return this builder
         */
        public Builder format(int sampleRate, int channels) {
            this.sampleRate = sampleRate;
            this.channels = channels;
            return this;
        }

        /**
         * Sets the input format from a channel layout; see {@link OpusEncoderOptions#sampleRate()}
         * and {@link OpusEncoderOptions#channels()}.
         *
         * <p>Only the channel count of {@code layout} is kept: a one-channel layout codes mono and a
         * two-channel layout codes stereo, its first channel left and its second right.
         *
         * @param sampleRate the sampling rate
         * @param layout the channel layout
         * @return this builder
         */
        public Builder format(int sampleRate, ChannelLayout layout) {
            return format(sampleRate, layout.channels());
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
         * Returns the options holding the settings of this builder.
         *
         * @return the options
         * @throws IllegalArgumentException if the sampling rate is not 8000, 12000, 16000, 24000 or
         * 48000 Hz, the channel count is not 1 or 2, an explicit bitrate is outside 500 to
         * {@code 300000} per channel, the complexity is outside 0 to 10, the packet duration is not
         * an Opus packet duration or the expected loss is outside 0 to 100
         */
        public OpusEncoderOptions build() {
            OpusDecoderOptions.checkSampleRate("sample rate", sampleRate);
            Checks.inRange("channel count", channels, 1, 2);
            if (bitrate != null) {
                Checks.inRange("bitrate", bitrate, MIN_BITRATE, MAX_CHANNEL_BITRATE * channels);
            }
            Checks.inRange("complexity", complexity, 0, 10);
            switch (frameDurationMicros) {
                case 2500, 5000, 10000, 20000, 40000, 60000, 80000, 100000, 120000 -> {
                }
                default -> throw new IllegalArgumentException("frame duration " + frameDurationMicros
                        + " us is not one of 2500, 5000, 10000, 20000, 40000, 60000, 80000, 100000, 120000");
            }
            Checks.inRange("packet loss percent", packetLossPercent, 0, 100);
            return new OpusEncoderOptions(this);
        }
    }
}
