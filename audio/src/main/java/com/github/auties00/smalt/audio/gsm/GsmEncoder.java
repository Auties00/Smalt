package com.github.auties00.smalt.audio.gsm;

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
 * Encodes one channel of audio into ETSI GSM 06.10 full-rate speech ({@link Codec#GSM} or {@link Codec#GSM_MS})
 * packets.
 *
 * <p>Input is quantized to 16 bits (a float or double sample is clipped to {@code [-1, 1]}, multiplied by 32768,
 * rounded to the nearest integer with ties toward positive infinity and clamped, NaN becoming zero; an integer
 * sample is shifted or rounded to 16 bits), of which the coder reads the top 13. The parameters are those of the
 * bit-exact RPE-LTP encoder of ETSI EN 300 961, including the optional codec homing of its clause 4 (an encoder
 * homing frame, 160 samples of value 8, returns the encoder to its initial state after it is coded), and equal the
 * encoder outputs of the EN 300 961 test sequences. GSM 06.10 is specified at 8 kHz; the encoder accepts any rate
 * and codes it the same way.
 *
 * <p>A {@link Codec#GSM} packet is one 33-byte frame of 160 samples and a {@link Codec#GSM_MS} packet one 65-byte
 * block of two frames, 320 samples, each timestamped in samples from zero, with its duration and the key-frame flag.
 * {@link #flush(Consumer)} codes the remaining samples in a final packet padded with the last input sample, whose
 * duration counts only the input samples.
 *
 * <p>Instances are not thread-safe.
 *
 * @implNote This implementation ports libgsm 1.0.22, whose arithmetic it keeps, adding the codec homing that
 * libgsm leaves out.
 */
public final class GsmEncoder implements AudioEncoder {
    private final Codec codec;
    private final int sampleRate;
    private final Gsm0610 coder = new Gsm0610();
    private final int[] params = new int[Gsm0610.PARAMETERS];
    private final Pcm16Input input;
    private final Pcm16Input.FrameSink sink;
    private Consumer<Packet> target;
    private long samplesWritten;

    /**
     * Constructs an encoder of 8 kHz mono input.
     *
     * @param codec {@link Codec#GSM} or {@link Codec#GSM_MS}
     * @throws IllegalArgumentException if the codec is neither
     */
    public GsmEncoder(Codec codec) {
        this(codec, 8000);
    }

    /**
     * Constructs an encoder of mono input at a sampling rate.
     *
     * @param codec {@link Codec#GSM} or {@link Codec#GSM_MS}
     * @param sampleRate the sampling rate, in hertz, positive
     * @throws IllegalArgumentException if the codec is neither or the rate is not positive
     */
    public GsmEncoder(Codec codec, int sampleRate) {
        Objects.requireNonNull(codec, "codec");
        if (codec != Codec.GSM && codec != Codec.GSM_MS) {
            throw new IllegalArgumentException("not a GSM 06.10 codec: " + codec);
        }
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("invalid sample rate " + sampleRate);
        }
        this.codec = codec;
        this.sampleRate = sampleRate;
        this.input = new Pcm16Input(ChannelLayout.MONO, sampleRate, frameSize());
        this.sink = this::codePacket;
    }

    @Override
    public Codec codec() {
        return codec;
    }

    @Override
    public int sampleRate() {
        return sampleRate;
    }

    @Override
    public ChannelLayout layout() {
        return ChannelLayout.MONO;
    }

    /**
     * {@inheritDoc}
     *
     * @return 160 for {@link Codec#GSM}, 320 for {@link Codec#GSM_MS}
     */
    @Override
    public int frameSize() {
        return codec == Codec.GSM ? Gsm0610.SAMPLES : 2 * Gsm0610.SAMPLES;
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
     * @return empty: GSM 06.10 has no configuration
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
     * Codes the frames of one packet and hands it to the current output.
     *
     * @param planes the samples, one plane of {@link #frameSize()}
     * @param count the input samples
     */
    private void codePacket(int[][] planes, int count) {
        var plane = planes[0];
        var last = plane[count - 1];
        for (var i = count; i < plane.length; i++) {
            plane[i] = last;
        }
        byte[] data;
        if (codec == Codec.GSM) {
            data = new byte[GsmFrames.FRAME_BYTES];
            coder.encode(plane, 0, params);
            GsmFrames.pack(params, data, 0);
        } else {
            data = new byte[GsmFrames.BLOCK_BYTES];
            coder.encode(plane, 0, params);
            GsmFrames.packMs(params, data, 0, false);
            coder.encode(plane, Gsm0610.SAMPLES, params);
            GsmFrames.packMs(params, data, 0, true);
        }
        target.accept(new Packet(0, data, samplesWritten, samplesWritten, count, true));
        samplesWritten += count;
    }
}
