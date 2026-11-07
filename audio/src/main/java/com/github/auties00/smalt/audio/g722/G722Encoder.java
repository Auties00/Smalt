package com.github.auties00.smalt.audio.g722;

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
 * Encodes one channel of 16 kHz audio into ITU-T G.722 sub-band ADPCM ({@link Codec#ADPCM_G722}) packets at
 * 64 kbit/s.
 *
 * <p>Input is quantized to 16 bits (a float or double sample is clipped to {@code [-1, 1]}, multiplied by 32768,
 * rounded to the nearest integer with ties toward positive infinity and clamped, NaN becoming zero; an integer
 * sample is shifted or rounded to 16 bits), and its top 15 bits are the Recommendation's uniform input. The transmit
 * quadrature mirror filter, which the Recommendation leaves free in its rounding and accumulator width, computes its
 * products and sums exactly and truncates; the sub-band encoders are bit-exact with the digital test sequences of
 * G.722 Appendix II. Every pair of samples becomes one byte, the 2-bit higher sub-band code above the 6-bit lower
 * sub-band code.
 *
 * <p>Each packet holds {@value #PACKET_SAMPLES} samples (20 ms), timestamped in samples from zero, with its
 * duration and the key-frame flag; {@link #flush(Consumer)} codes the remaining samples in a final packet, padded
 * to an even count with the last input sample, whose duration counts only the input samples.
 *
 * <p>Instances are not thread-safe.
 */
public final class G722Encoder implements AudioEncoder {
    /**
     * The samples of every packet but the last.
     */
    public static final int PACKET_SAMPLES = 320;

    private final G722Band low = new G722Band(true);
    private final G722Band high = new G722Band(false);
    private final G722Qmf qmf = new G722Qmf();
    private final Pcm16Input input;
    private final Pcm16Input.FrameSink sink;
    private Consumer<Packet> target;
    private long samplesWritten;

    /**
     * Constructs an encoder of 16 kHz mono input.
     */
    public G722Encoder() {
        this.input = new Pcm16Input(ChannelLayout.MONO, G722Decoder.SAMPLE_RATE, PACKET_SAMPLES);
        this.sink = this::codePacket;
    }

    @Override
    public Codec codec() {
        return Codec.ADPCM_G722;
    }

    /**
     * {@inheritDoc}
     *
     * @return 16000
     */
    @Override
    public int sampleRate() {
        return G722Decoder.SAMPLE_RATE;
    }

    @Override
    public ChannelLayout layout() {
        return ChannelLayout.MONO;
    }

    /**
     * {@inheritDoc}
     *
     * @return {@value #PACKET_SAMPLES}
     */
    @Override
    public int frameSize() {
        return PACKET_SAMPLES;
    }

    /**
     * {@inheritDoc}
     *
     * @return 0: the stream declares no priming samples, although the filters' group delay shifts the decoded
     * signal by a few samples
     */
    @Override
    public int delay() {
        return 0;
    }

    /**
     * {@inheritDoc}
     *
     * @return empty: G.722 has no configuration
     */
    @Override
    public Optional<byte[]> codecConfig() {
        return Optional.empty();
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException if the block is not 16 kHz mono
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
     * @throws IllegalArgumentException if the block is not 16 kHz mono
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
     * @throws IllegalArgumentException if the block is not 16 kHz mono
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
     * @param planes the samples, one plane of {@value #PACKET_SAMPLES}
     * @param count the input samples
     */
    private void codePacket(int[][] planes, int count) {
        var plane = planes[0];
        if ((count & 1) != 0) {
            plane[count] = plane[count - 1];
        }
        var data = new byte[(count + 1) / 2];
        for (var i = 0; i < data.length; i++) {
            qmf.analyze(plane[2 * i] >> 1, plane[2 * i + 1] >> 1);
            var il = low.encodeLow(qmf.xl);
            var ih = high.encodeHigh(qmf.xh);
            data[i] = (byte) (ih << 6 | il);
        }
        target.accept(new Packet(0, data, samplesWritten, samplesWritten, count, true));
        samplesWritten += count;
    }
}
