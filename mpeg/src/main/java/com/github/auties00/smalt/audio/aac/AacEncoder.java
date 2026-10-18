package com.github.auties00.smalt.audio.aac;

import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.audio.AudioEncoder;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.aac.internal.AacKernels;
import com.github.auties00.smalt.audio.aac.internal.AudioSpecificConfig;
import com.github.auties00.smalt.audio.aac.internal.EncoderEngine;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Encodes planar float samples into MPEG-4 AAC Low Complexity packets.
 *
 * <p>Each packet is one {@code raw_data_block} of 1024 samples per channel (without an ADTS
 * header), and {@link #codecConfig()} is the two-byte {@code AudioSpecificConfig} a container
 * stores. Packet timestamps count samples from the first input sample; decoders output
 * {@link #delay()} = 1024 priming samples (the first half of the first transform window) before
 * the first input sample, which the container signals, for example with an MP4 edit list. The
 * encoder buffers input of any length and emits a packet whenever a frame and its 512-sample
 * look-ahead are complete; {@link #flush(Consumer)} pads the input with silence and emits
 * packets until every input sample is covered.
 *
 * <p>Coding tools: window switching between long and eight short windows (grouped) driven by a
 * transient detector, sine and Kaiser-Bessel-derived windows, a psychoacoustic model with
 * spreading, threshold in quiet and pre-echo control, TNS on transients and tonal-in-time
 * spectra, per-band M/S stereo, scalefactors refined against the measured quantization error,
 * sections chosen by dynamic programming, and a bit reservoir in constant bitrate mode. Perceptual
 * noise substitution and intensity stereo are not used. Input samples outside {@code [-1, 1]}
 * are clipped; NaN samples are coded as silence.
 *
 * <p>Instances are not thread-safe.
 */
public final class AacEncoder implements AudioEncoder {
    /**
     * The number of samples per channel of one frame.
     */
    private static final int FRAME = 1024;

    /**
     * The options the encoder was created with.
     */
    private final AacEncoderOptions options;

    /**
     * The encoding engine.
     */
    private final EncoderEngine engine;

    /**
     * The {@code AudioSpecificConfig}.
     */
    private final byte[] audioSpecificConfig;

    /**
     * The number of input samples per channel received.
     */
    private long received;

    /**
     * Whether {@link #flush(Consumer)} ran.
     */
    private boolean flushed;

    /**
     * Constructs an encoder under {@link AacEncoderOptions#defaults()}: 48 kHz stereo at 128 kbit/s,
     * constant bitrate.
     */
    public AacEncoder() {
        this(AacEncoderOptions.defaults());
    }

    /**
     * Constructs an encoder under explicit options.
     *
     * @param options the format, bitrate and bandwidth
     */
    public AacEncoder(AacEncoderOptions options) {
        this.options = Objects.requireNonNull(options, "options");
        var channels = options.layout().channels();
        this.engine = new EncoderEngine(options.sampleRate(), channels, options.bitrate(),
                options.bitrateMode() == AacBitrateMode.VBR, options.effectiveBandwidth(), AacKernels.INSTANCE);
        this.audioSpecificConfig = AudioSpecificConfig.lowComplexity(options.sampleRate(), channels);
    }

    /**
     * {@return the options the encoder was created with}
     */
    public AacEncoderOptions options() {
        return options;
    }

    @Override
    public Codec codec() {
        return Codec.AAC;
    }

    @Override
    public int sampleRate() {
        return options.sampleRate();
    }

    @Override
    public ChannelLayout layout() {
        return options.layout();
    }

    /**
     * {@inheritDoc}
     *
     * @return 1024
     */
    @Override
    public int frameSize() {
        return FRAME;
    }

    /**
     * {@inheritDoc}
     *
     * @implNote This implementation's delay is exactly one frame, 1024 samples: the first
     * packet's window starts one frame before the first input sample.
     */
    @Override
    public int delay() {
        return FRAME;
    }

    /**
     * {@inheritDoc}
     *
     * @return the two-byte {@code AudioSpecificConfig} of AAC Low Complexity
     */
    @Override
    public Optional<byte[]> codecConfig() {
        return Optional.of(audioSpecificConfig.clone());
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalStateException if the encoder was flushed
     */
    @Override
    public void encode(AudioBuffer input, Consumer<Packet> output) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(output, "output");
        if (flushed) {
            throw new IllegalStateException("the encoder was flushed");
        }
        if (input.sampleRate() != options.sampleRate() || !input.layout().equals(options.layout())) {
            throw new IllegalArgumentException("input " + input.sampleRate() + " Hz " + input.layout().speakers()
                    + " does not match the encoder's " + options.sampleRate() + " Hz " + options.layout().speakers());
        }
        var planes = input.planes();
        var offset = 0;
        var remaining = input.frames();
        received += remaining;
        while (remaining > 0) {
            var count = Math.min(remaining, engine.capacity());
            engine.push(planes, offset, count);
            offset += count;
            remaining -= count;
            drain(output);
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>After a flush the encoder accepts no further input.
     */
    @Override
    public void flush(Consumer<Packet> output) {
        Objects.requireNonNull(output, "output");
        if (flushed) {
            return;
        }
        flushed = true;
        if (received == 0) {
            return;
        }
        var needed = (received + FRAME - 1) / FRAME + 1;
        while (engine.frames() < needed) {
            if (!engine.frameReady()) {
                engine.pad(engine.capacity());
            }
            emit(output);
        }
    }

    /**
     * Encodes every complete frame.
     *
     * @param output receives the packets
     */
    private void drain(Consumer<Packet> output) {
        while (engine.frameReady()) {
            emit(output);
        }
    }

    /**
     * Encodes the next frame and hands its packet to {@code output}.
     *
     * @param output receives the packet
     */
    private void emit(Consumer<Packet> output) {
        var timestamp = engine.frames() * FRAME;
        var block = engine.encodeFrame();
        output.accept(new Packet(0, block, timestamp, timestamp, FRAME, true));
    }
}
