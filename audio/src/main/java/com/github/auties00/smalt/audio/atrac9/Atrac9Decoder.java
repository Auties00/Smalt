/* Ports LibAtrac9 (C/src/libatrac9.c, C/src/decoder.c, CSharp/LibAtrac9/Atrac9Decoder.cs) at commit efca2e3.
   Copyright 2018 Alex Barney; MIT License. Modified for Smalt. */
package com.github.auties00.smalt.audio.atrac9;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.audio.AudioDecoder;
import com.github.auties00.smalt.audio.AudioDecoderOptions;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.DoubleAudioBuffer;
import com.github.auties00.smalt.audio.IntegerAudioBuffer;
import com.github.auties00.smalt.internal.check.Checks;
import com.github.auties00.smalt.internal.codec.WaveformConcealer;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.internal.pcm16.Pcm16Output;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Decodes Sony ATRAC9 audio: every sampling rate from 11.025 to 192 kHz, the mono, dual mono, stereo, 4.0, 5.1 and
 * 7.1 channel configurations, intensity stereo and band extension.
 *
 * <p>The track's configuration is the 4-byte ATRAC9 configuration word, or the 12 bytes that follow the
 * {@code WAVEFORMATEXTENSIBLE} fields of an {@code .at9} format chunk (a version, the word and a reserved field). Each
 * packet holds one or more superframes of the size the configuration gives, and each superframe decodes into one,
 * two, four or eight frames of 64, 128 or 256 samples (the frame size by sampling rate); a packet whose last
 * superframe is shorter decodes it as if zeros followed. The output rate and channel count are the configuration's;
 * the layout is the track's when it has as many channels, and otherwise mono, stereo (for dual mono too), quad, 5.1
 * or 7.1 in the block order of the stream (front, center, low-frequency effects, then the surround pairs).
 *
 * <p>The decoded samples are bit-identical to those of LibAtrac9 (Alex Barney's decoder, reverse engineered from
 * Sony's) at its commit {@code efca2e3}, as {@link #decodeIntegers(Packet, Consumer)} outputs them (16 bits); the
 * float and double outputs scale them by 2^-15. A superframe that codes parameters outside their ranges raises
 * {@link MediaException.Malformed}, as do the parameters for which the reference has no defined result: gradient
 * units past 31, fine precisions past 15, a band extension of fewer than 13 or more than 20 quantization units, and
 * band extension values outside their tables.
 *
 * <p>Lost packets are concealed ({@link #conceal(int, Consumer)}) by repeating the last pitch period of the float
 * output, fading to silence, as ITU-T G.711 Appendix I conceals G.711 frames; the decoder state continues unchanged
 * after the loss, and the float output of the first packet after it is overlapped with the continued repetition.
 *
 * <p>Instances are not thread-safe.
 */
public final class Atrac9Decoder implements AudioDecoder {
    private final Atrac9FrameDecoder frames;

    private final int channels;

    private final int sampleRate;

    private final ChannelLayout layout;

    private final int superframeBytes;

    private final int superframeSamples;

    private final MediaLimits limits;

    /**
     * The 16-bit output, its conversions and the concealment of lost spans.
     */
    private final Pcm16Output output;

    /**
     * Constructs a decoder for an ATRAC9 track with the {@link AudioDecoderOptions#defaults()}.
     *
     * @param track the track; its codec must be {@link Codec#ATRAC9}
     * @throws IllegalArgumentException if the track is not an ATRAC9 track
     * @throws MediaException.Malformed if the track has no valid ATRAC9 configuration
     */
    public Atrac9Decoder(AudioTrack track) {
        this(track, AudioDecoderOptions.defaults());
    }

    /**
     * Constructs a decoder for an ATRAC9 track.
     *
     * <p>The options' limits apply; the decoder outputs the stream's own rate.
     *
     * @param track the track; its codec must be {@link Codec#ATRAC9}
     * @param options the decoding options
     * @throws IllegalArgumentException if the track is not an ATRAC9 track
     * @throws MediaException.Malformed if the track has no valid ATRAC9 configuration
     * @throws MediaException.LimitExceeded if the channel count or the decoder's buffers exceed the limits
     */
    public Atrac9Decoder(AudioTrack track, AudioDecoderOptions options) {
        Objects.requireNonNull(track, "track");
        Objects.requireNonNull(options, "options");
        if (track.codec() != Codec.ATRAC9) {
            throw new IllegalArgumentException("not an ATRAC9 track: " + track.codec());
        }
        var bytes = track.codecConfig()
                .orElseThrow(() -> new MediaException.Malformed("ATRAC9 track without a configuration"));
        var config = switch (bytes.length) {
            case 4 -> Atrac9Config.parse(bytes, 0);
            case 12 -> Atrac9Config.parse(bytes, 4);
            default -> throw new MediaException.Malformed("ATRAC9 configuration of " + bytes.length + " bytes");
        };
        this.limits = options.limits();
        LimitChecks.checkChannels(limits, config.channelCount());
        this.channels = config.channelCount();
        this.sampleRate = config.sampleRate();
        LimitChecks.checkAllocation(limits, WaveformConcealer.footprint(channels, sampleRate));
        this.frames = new Atrac9FrameDecoder(config);
        this.superframeBytes = config.superframeBytes();
        this.superframeSamples = config.superframeSamples();
        this.layout = track.layout().filter(declared -> declared.channels() == channels)
                .orElseGet(() -> ChannelLayout.defaultFor(channels));
        this.output = new Pcm16Output(layout, sampleRate);
    }

    @Override
    public int sampleRate() {
        return sampleRate;
    }

    @Override
    public ChannelLayout layout() {
        return layout;
    }

    /**
     * {@return the samples per channel of a superframe: 64, 128, 256, 512 or 1024 for the common streams, and up to
     * 2048}
     */
    public int superframeSamples() {
        return superframeSamples;
    }

    /**
     * {@return the size of a superframe in bytes, from 1 to 16384}
     */
    public int superframeBytes() {
        return superframeBytes;
    }

    /**
     * Decodes the superframes of a packet into the output's integer planes.
     *
     * @param packet the packet of one or more superframes
     * @return the samples per channel decoded
     * @throws MediaException.Malformed if a superframe codes invalid parameters
     * @throws MediaException.LimitExceeded if the packet's samples exceed the allocation limit
     */
    private int decodePacket(Packet packet) {
        Objects.requireNonNull(packet, "packet");
        var data = packet.data();
        var count = (data.length + superframeBytes - 1) / superframeBytes;
        var samples = count * superframeSamples;
        LimitChecks.checkAllocation(limits, (long) samples * channels * (Integer.BYTES + Double.BYTES));
        var planes = output.planes(Math.max(samples, 1));
        for (var i = 0; i < count; i++) {
            var offset = i * superframeBytes;
            frames.decodeSuperframe(data, offset, Math.min(superframeBytes, data.length - offset), planes,
                    i * superframeSamples);
        }
        return samples;
    }

    @Override
    public void decode(Packet packet, Consumer<AudioBuffer> output) {
        Objects.requireNonNull(output, "output");
        this.output.floats(decodePacket(packet), output);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Does nothing: an ATRAC9 decoder buffers no samples between packets.
     */
    @Override
    public void flush(Consumer<AudioBuffer> output) {
        Objects.requireNonNull(output, "output");
    }

    /**
     * {@inheritDoc}
     *
     * @return {@code true}
     */
    @Override
    public boolean supportsIntegerOutput() {
        return true;
    }

    /**
     * {@inheritDoc}
     *
     * @return 16
     */
    @Override
    public int bitsPerSample() {
        return 16;
    }

    @Override
    public void decodeIntegers(Packet packet, Consumer<IntegerAudioBuffer> output) {
        Objects.requireNonNull(output, "output");
        this.output.integers(decodePacket(packet), output);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Does nothing: an ATRAC9 decoder buffers no samples between packets.
     */
    @Override
    public void flushIntegers(Consumer<IntegerAudioBuffer> output) {
        Objects.requireNonNull(output, "output");
    }

    /**
     * {@inheritDoc}
     *
     * @return {@code true}
     */
    @Override
    public boolean supportsDoubleOutput() {
        return true;
    }

    @Override
    public void decodeDoubles(Packet packet, Consumer<DoubleAudioBuffer> output) {
        Objects.requireNonNull(output, "output");
        this.output.doubles(decodePacket(packet), output);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Does nothing: an ATRAC9 decoder buffers no samples between packets.
     */
    @Override
    public void flushDoubles(Consumer<DoubleAudioBuffer> output) {
        Objects.requireNonNull(output, "output");
    }

    /**
     * {@inheritDoc}
     *
     * @return {@code true}
     */
    @Override
    public boolean supportsConcealment() {
        return true;
    }

    /**
     * {@inheritDoc}
     *
     * <p>The span is produced to the sample: the concealment unit is one sample.
     *
     * @return {@code samples}, the span being produced exactly
     * @throws IllegalArgumentException if {@code samples} is not positive
     */
    @Override
    public int conceal(int samples, Consumer<AudioBuffer> output) {
        Checks.positive("samples", samples);
        Objects.requireNonNull(output, "output");
        return this.output.conceal(samples, output);
    }

    @Override
    public void close() {
    }
}
