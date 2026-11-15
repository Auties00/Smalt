package com.github.auties00.smalt.audio.adpcm;

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
 * Encodes audio into IMA ADPCM ({@link Codec#ADPCM_IMA_WAV} and {@link Codec#ADPCM_IMA_QT}) or Microsoft ADPCM
 * ({@link Codec#ADPCM_MS}) packets.
 *
 * <p>Input is quantized to 16 bits (a float or double sample is clipped to {@code [-1, 1]}, multiplied by 32768,
 * rounded to the nearest integer with ties toward positive infinity and clamped, NaN becoming zero; an integer
 * sample is shifted or rounded to 16 bits) and gathered into blocks of {@link #frameSize()} samples per channel.
 * Each block becomes one {@link Packet}, timestamped in samples from zero, with its duration and the key-frame
 * flag; a WAVE packet is one block of {@link AdpcmEncoderOptions#blockBytes()} bytes and an {@code ima4} packet is
 * one frame of 34 bytes per channel. {@link #flush(Consumer)} codes the remaining samples as a final block, as
 * short as the layout allows, padded with the last input sample; its packet's duration counts only the input
 * samples.
 *
 * <p>IMA ADPCM codes every sample with the code nearest to it under the recommendation's quantizer, carrying the
 * step index from block to block. Microsoft ADPCM codes each channel of a block with the coefficient pair and
 * initial delta, among the configuration's pairs and an estimate from the block's first samples, that give the
 * smallest squared error. The output decodes with any conforming decoder; {@link AdpcmDecoder} reproduces the
 * encoder's own reconstruction exactly.
 *
 * <p>{@link #codecConfig()} is, for Microsoft ADPCM, the {@code ADPCMWAVEFORMAT} fields after {@code cbSize}
 * ({@code wSamplesPerBlock}, then the seven standard coefficient pairs), and empty for IMA ADPCM.
 *
 * <p>Instances are not thread-safe.
 */
public final class AdpcmEncoder implements AudioEncoder {
    private final AdpcmEncoderOptions options;
    private final Codec codec;
    private final int channels;
    private final int blockBytes;
    private final int frameSize;
    private final ImaAdpcm ima;
    private final MsAdpcm ms;
    private final Pcm16Input input;
    private final Pcm16Input.FrameSink sink;
    private Consumer<Packet> target;
    private long samplesWritten;

    /**
     * Constructs an encoder.
     *
     * @param options the settings
     */
    public AdpcmEncoder(AdpcmEncoderOptions options) {
        this.options = Objects.requireNonNull(options, "options");
        this.codec = options.codec();
        this.channels = options.layout().channels();
        if (codec == Codec.ADPCM_IMA_QT) {
            this.blockBytes = ImaAdpcm.QT_CHANNEL_BYTES * channels;
            this.frameSize = ImaAdpcm.QT_SAMPLES;
        } else {
            this.blockBytes = options.resolvedBlockBytes();
            this.frameSize = codec == Codec.ADPCM_IMA_WAV ? ImaAdpcm.wavSamples(blockBytes, channels)
                    : MsAdpcm.samples(blockBytes, channels);
        }
        if (codec == Codec.ADPCM_MS) {
            this.ms = new MsAdpcm(MsAdpcm.STANDARD_COEFFICIENTS);
            this.ima = null;
        } else {
            this.ima = new ImaAdpcm(channels);
            this.ms = null;
        }
        this.input = new Pcm16Input(options.layout(), options.sampleRate(), frameSize);
        this.sink = this::codeBlock;
    }

    @Override
    public Codec codec() {
        return codec;
    }

    @Override
    public int sampleRate() {
        return options.sampleRate();
    }

    @Override
    public ChannelLayout layout() {
        return options.layout();
    }

    @Override
    public int frameSize() {
        return frameSize;
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
     * @return 4, the code size
     */
    @Override
    public int bitsPerSample() {
        return 4;
    }

    @Override
    public Optional<byte[]> codecConfig() {
        if (codec != Codec.ADPCM_MS) {
            return Optional.empty();
        }
        var pairs = MsAdpcm.STANDARD_COEFFICIENTS;
        var config = new byte[4 + 2 * pairs.length];
        config[0] = (byte) frameSize;
        config[1] = (byte) (frameSize >> 8);
        config[2] = (byte) (pairs.length / 2);
        for (var i = 0; i < pairs.length; i++) {
            config[4 + 2 * i] = (byte) pairs[i];
            config[5 + 2 * i] = (byte) (pairs[i] >> 8);
        }
        return Optional.of(config);
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
     * Codes one block of samples and hands its packet to the current output.
     *
     * @param planes the samples, one plane per channel, each {@link #frameSize} long
     * @param count the input samples per channel, the frame size except for the final block
     */
    private void codeBlock(int[][] planes, int count) {
        var coded = codedSamples(count);
        for (var plane : planes) {
            var last = plane[count - 1];
            for (var i = count; i < coded; i++) {
                plane[i] = last;
            }
        }
        byte[] data;
        switch (codec) {
            case ADPCM_IMA_QT -> {
                data = new byte[blockBytes];
                ima.encodeQt(planes, data);
            }
            case ADPCM_IMA_WAV -> {
                data = new byte[4 * channels * (1 + (coded - 1) / 8)];
                ima.encodeWav(planes, coded, data);
            }
            default -> {
                data = new byte[7 * channels + (coded - 2) * channels / 2];
                ms.encode(planes, coded, data);
            }
        }
        target.accept(new Packet(0, data, samplesWritten, samplesWritten, count, true));
        samplesWritten += count;
    }

    /**
     * Returns the samples per channel a block coding some input samples holds.
     *
     * @param count the input samples per channel, from 1 to the frame size
     * @return the coded samples, at least {@code count}
     */
    private int codedSamples(int count) {
        if (count == frameSize) {
            return count;
        }
        return switch (codec) {
            case ADPCM_IMA_QT -> ImaAdpcm.QT_SAMPLES;
            case ADPCM_IMA_WAV -> 1 + (count + 6) / 8 * 8;
            default -> {
                var coded = Math.max(2, count);
                yield (coded - 2) * channels % 2 == 0 ? coded : coded + 1;
            }
        };
    }
}
