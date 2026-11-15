package com.github.auties00.smalt.audio.g726;

import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.audio.AudioEncoder;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.DoubleAudioBuffer;
import com.github.auties00.smalt.audio.IntegerAudioBuffer;
import com.github.auties00.smalt.internal.pcm16.Pcm16Input;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Encodes one channel of audio into ITU-T G.726 ADPCM ({@link Codec#ADPCM_G726}) packets.
 *
 * <p>Input is quantized to 16 bits (a float or double sample is clipped to {@code [-1, 1]}, multiplied by 32768,
 * rounded to the nearest integer with ties toward positive infinity and clamped, NaN becoming zero; an integer
 * sample is shifted or rounded to 16 bits), and its top 14 bits are the uniform PCM input of the Recommendation's
 * Annex A. The codes are those of the Recommendation's encoder, bit-exact with the digital test sequences of G.726
 * Appendix II, packed from the most significant bit of each byte. Each packet holds
 * {@link G726EncoderOptions#packetSamples()} codes, timestamped in samples from zero, with its duration and the
 * key-frame flag; {@link #flush(Consumer)} codes the remaining samples in a final packet whose last byte is padded
 * with zero bits. The track's code size is {@link G726EncoderOptions#codeSize()}, which a container stores as the
 * bits per sample.
 *
 * <p>Instances are not thread-safe.
 */
public final class G726Encoder implements AudioEncoder {
    private final G726EncoderOptions options;
    private final G726Codec codec;
    private final Pcm16Input input;
    private final Pcm16Input.FrameSink sink;
    private Consumer<Packet> target;
    private long samplesWritten;

    /**
     * Constructs an encoder.
     *
     * @param options the settings
     */
    public G726Encoder(G726EncoderOptions options) {
        this.options = Objects.requireNonNull(options, "options");
        this.codec = new G726Codec(options.codeSize());
        this.input = new Pcm16Input(ChannelLayout.MONO, options.sampleRate(), options.packetSamples());
        this.sink = this::codePacket;
    }

    @Override
    public Codec codec() {
        return Codec.ADPCM_G726;
    }

    @Override
    public int sampleRate() {
        return options.sampleRate();
    }

    @Override
    public ChannelLayout layout() {
        return ChannelLayout.MONO;
    }

    @Override
    public int frameSize() {
        return options.packetSamples();
    }

    /**
     * {@inheritDoc}
     *
     * @return 0: the decoder outputs the first input sample first
     */
    @Override
    public int delay() {
        return 0;
    }

    /**
     * {@inheritDoc}
     *
     * @return the code size, from 2 to 5
     */
    @Override
    public int bitsPerSample() {
        return codec.bits;
    }

    /**
     * {@inheritDoc}
     *
     * @return empty: the code size travels as the track's bits per sample
     */
    @Override
    public Optional<byte[]> codecConfig() {
        return Optional.empty();
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException if the block's sampling rate or layout differs from the encoder's
     */
    @Override
    public void encode(AudioBuffer input, Consumer<Packet> output) {
        this.target = Objects.requireNonNull(output, "output");
        this.input.add(input, sink);
    }

    /**
     * {@inheritDoc}
     *
     * @return {@code true}
     */
    @Override
    public boolean supportsIntegerInput() {
        return true;
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException if the block's sampling rate or layout differs from the encoder's
     */
    @Override
    public void encode(IntegerAudioBuffer input, Consumer<Packet> output) {
        this.target = Objects.requireNonNull(output, "output");
        this.input.add(input, sink);
    }

    /**
     * {@inheritDoc}
     *
     * @return {@code true}
     */
    @Override
    public boolean supportsDoubleInput() {
        return true;
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException if the block's sampling rate or layout differs from the encoder's
     */
    @Override
    public void encode(DoubleAudioBuffer input, Consumer<Packet> output) {
        this.target = Objects.requireNonNull(output, "output");
        this.input.add(input, sink);
    }

    @Override
    public void flush(Consumer<Packet> output) {
        this.target = Objects.requireNonNull(output, "output");
        input.flush(sink);
    }

    /**
     * Codes the samples of one packet and hands it to the current output.
     *
     * @param planes the samples, one plane
     * @param count the samples
     */
    private void codePacket(int[][] planes, int count) {
        var plane = planes[0];
        var bits = codec.bits;
        var data = new byte[(int) (((long) count * bits + 7) / 8)];
        var accumulator = 0;
        var pending = 0;
        var position = 0;
        for (var i = 0; i < count; i++) {
            codec.estimate();
            var code = codec.quantize(plane[i] >> 2);
            codec.update(code);
            accumulator = accumulator << bits | code;
            pending += bits;
            if (pending >= 8) {
                pending -= 8;
                data[position++] = (byte) (accumulator >>> pending);
            }
        }
        if (pending > 0) {
            data[position] = (byte) (accumulator << (8 - pending));
        }
        target.accept(new Packet(0, data, samplesWritten, samplesWritten, count, true));
        samplesWritten += count;
    }
}
