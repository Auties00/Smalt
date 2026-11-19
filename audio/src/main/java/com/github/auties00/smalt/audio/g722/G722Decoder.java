package com.github.auties00.smalt.audio.g722;

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
 * Decodes ITU-T G.722 sub-band ADPCM ({@link Codec#ADPCM_G722}) packets into 16-bit samples at 16 kHz.
 *
 * <p>The track is one channel. Every byte of a packet decodes to two samples; the decoder state continues from one
 * packet to the next. The track's {@link AudioTrack#bitsPerSample() bitsPerSample} selects the mode: 7 decodes the
 * 56 kbit/s mode and 6 the 48 kbit/s mode, which ignore the lowest one or two bits of each byte (auxiliary data),
 * and any other value the 64 kbit/s mode. The output is at 16 kHz whatever rate the track declares, since RTP
 * declares G.722 at 8 kHz.
 *
 * <p>The sub-band decoders are bit-exact with the digital test sequences of G.722 Appendix II in all three modes.
 * The receive quadrature mirror filter, which the Recommendation leaves free in its rounding and accumulator width,
 * computes its products and sums exactly and truncates them to the Recommendation's 15-bit output, which a 16-bit
 * sample holds shifted left by one bit.
 *
 * <p>The float and double outputs scale the 16-bit samples by {@code 2^-15}. A lost span
 * ({@link #conceal(int, Consumer)}) repeats the last pitch period of the float output, fading to silence, as
 * ITU-T G.711 Appendix I conceals G.711 frames; the ADPCM state continues unchanged after it.
 *
 * <p>Instances are not thread-safe.
 */
public final class G722Decoder implements AudioDecoder {
    /**
     * The output sampling rate.
     */
    static final int SAMPLE_RATE = 16_000;

    private final int mode;
    private final MediaLimits limits;
    private final G722Band low = new G722Band(true);
    private final G722Band high = new G722Band(false);
    private final G722Qmf qmf = new G722Qmf();
    private final Pcm16Output output;

    /**
     * Constructs a decoder for a G.722 track.
     *
     * @param track the track, of one channel
     * @throws IllegalArgumentException if the track's codec is not {@link Codec#ADPCM_G722}
     * @throws MediaException.Unsupported if the track has more than one channel
     */
    public G722Decoder(AudioTrack track) {
        this(track, AudioDecoderOptions.defaults());
    }

    /**
     * Constructs a decoder for a G.722 track under the limits of codec-independent options.
     *
     * <p>Only {@link AudioDecoderOptions#limits()} applies: the decoder always outputs 16 kHz.
     *
     * @param track the track, of one channel
     * @param options the codec-independent options, of which only the limits apply
     * @throws IllegalArgumentException if the track's codec is not {@link Codec#ADPCM_G722}
     * @throws MediaException.Unsupported if the track has more than one channel
     */
    public G722Decoder(AudioTrack track, AudioDecoderOptions options) {
        Objects.requireNonNull(track, "track");
        Objects.requireNonNull(options, "options");
        if (track.codec() != Codec.ADPCM_G722) {
            throw new IllegalArgumentException("not a G.722 track: " + track.codec());
        }
        if (track.layout().map(ChannelLayout::channels).orElse(1) != 1) {
            throw new MediaException.Unsupported("multichannel G.722", "G.722 tracks of "
                    + track.layout().orElseThrow().channels() + " channels are not supported");
        }
        this.mode = switch (track.bitsPerSample()) {
            case 7 -> 2;
            case 6 -> 3;
            default -> 1;
        };
        this.limits = options.limits();
        LimitChecks.checkAllocation(limits, WaveformConcealer.footprint(1, SAMPLE_RATE));
        this.output = new Pcm16Output(ChannelLayout.MONO, SAMPLE_RATE);
    }

    /**
     * {@inheritDoc}
     *
     * @return 16000
     */
    @Override
    public int sampleRate() {
        return SAMPLE_RATE;
    }

    @Override
    public ChannelLayout layout() {
        return ChannelLayout.MONO;
    }

    /**
     * {@inheritDoc}
     *
     * @throws MediaException.LimitExceeded if the packet's samples exceed the decoder's allocation limit
     */
    @Override
    public void decode(Packet packet, Consumer<AudioBuffer> output) {
        Objects.requireNonNull(output, "output");
        this.output.floats(decodePacket(packet), output);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Does nothing: the decoder buffers no samples between packets.
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

    /**
     * {@inheritDoc}
     *
     * @throws MediaException.LimitExceeded if the packet's samples exceed the decoder's allocation limit
     */
    @Override
    public void decodeIntegers(Packet packet, Consumer<IntegerAudioBuffer> output) {
        Objects.requireNonNull(output, "output");
        this.output.integers(decodePacket(packet), output);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Does nothing: the decoder buffers no samples between packets.
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

    /**
     * {@inheritDoc}
     *
     * @throws MediaException.LimitExceeded if the packet's samples exceed the decoder's allocation limit
     */
    @Override
    public void decodeDoubles(Packet packet, Consumer<DoubleAudioBuffer> output) {
        Objects.requireNonNull(output, "output");
        this.output.doubles(decodePacket(packet), output);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Does nothing: the decoder buffers no samples between packets.
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
     * @return {@code samples}, the span being produced exactly
     * @throws IllegalArgumentException if {@code samples} is not positive
     */
    @Override
    public int conceal(int samples, Consumer<AudioBuffer> output) {
        Checks.positive("samples", samples);
        Objects.requireNonNull(output, "output");
        return this.output.conceal(samples, output);
    }

    /**
     * Decodes a packet into the output's integer plane.
     *
     * @param packet the coded bytes and their timing
     * @return the samples decoded
     */
    private int decodePacket(Packet packet) {
        Objects.requireNonNull(packet, "packet");
        var data = packet.data();
        var samples = 2 * data.length;
        LimitChecks.checkAllocation(limits, (long) samples * (Integer.BYTES + Double.BYTES));
        var plane = output.planes(Math.max(samples, 1))[0];
        for (var i = 0; i < data.length; i++) {
            var code = data[i] & 0xFF;
            var rl = low.decodeLow(code & 0x3F, mode);
            var rh = high.decodeHigh(code >>> 6);
            qmf.synthesize(rl, rh);
            plane[2 * i] = qmf.out1 << 1;
            plane[2 * i + 1] = qmf.out2 << 1;
        }
        return samples;
    }
}
