package com.github.auties00.smalt.audio.g726;

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
 * Decodes ITU-T G.726 ADPCM ({@link Codec#ADPCM_G726}) packets into 16-bit samples.
 *
 * <p>The track is one channel; its {@link AudioTrack#bitsPerSample() bitsPerSample} is the code size, 2, 3, 4 or 5
 * bits (16, 24, 32 and 40 kbit/s at 8 kHz). A packet holds codes packed from the most significant bit of each
 * byte; the decoder state continues from one packet to the next, and the bits after the last whole code of a packet
 * are ignored.
 *
 * <p>The decoder implements the Recommendation's ADPCM decoder with the uniform PCM output of its Annex A: the
 * reconstructed signal is limited to 14 bits and output shifted left by two bits, so a 16-bit sample is a multiple
 * of 4. The reconstruction is bit-exact with the digital test sequences of G.726 Appendix II at every rate.
 *
 * <p>The float and double outputs scale the 16-bit samples by {@code 2^-15}. A lost span
 * ({@link #conceal(int, Consumer)}) repeats the last pitch period of the float output, fading to silence, as
 * ITU-T G.711 Appendix I conceals G.711 frames; the ADPCM state continues unchanged after it.
 *
 * <p>Instances are not thread-safe.
 */
public final class G726Decoder implements AudioDecoder {
    private final int sampleRate;
    private final MediaLimits limits;
    private final G726Codec codec;
    private final Pcm16Output output;

    /**
     * Constructs a decoder for a G.726 track.
     *
     * @param track the track, with its sampling rate and code size
     * @throws IllegalArgumentException if the track's codec is not {@link Codec#ADPCM_G726}
     * @throws MediaException.Malformed if the track lacks its sampling rate or its code size is outside 2 to 5
     * @throws MediaException.Unsupported if the track has more than one channel
     */
    public G726Decoder(AudioTrack track) {
        this(track, AudioDecoderOptions.defaults());
    }

    /**
     * Constructs a decoder for a G.726 track under the limits of codec-independent options.
     *
     * <p>Only {@link AudioDecoderOptions#limits()} applies: the decoder always outputs the track's own rate.
     *
     * @param track the track, with its sampling rate and code size
     * @param options the codec-independent options, of which only the limits apply
     * @throws IllegalArgumentException if the track's codec is not {@link Codec#ADPCM_G726}
     * @throws MediaException.Malformed if the track lacks its sampling rate or its code size is outside 2 to 5
     * @throws MediaException.Unsupported if the track has more than one channel
     */
    public G726Decoder(AudioTrack track, AudioDecoderOptions options) {
        Objects.requireNonNull(track, "track");
        Objects.requireNonNull(options, "options");
        if (track.codec() != Codec.ADPCM_G726) {
            throw new IllegalArgumentException("not a G.726 track: " + track.codec());
        }
        if (track.layout().map(ChannelLayout::channels).orElse(1) != 1) {
            throw new MediaException.Unsupported("multichannel G.726", "G.726 tracks of "
                    + track.layout().orElseThrow().channels() + " channels are not supported");
        }
        var bits = track.bitsPerSample();
        if (bits < 2 || bits > 5) {
            throw new MediaException.Malformed("G.726 code size " + bits);
        }
        this.sampleRate = track.sampleRate();
        if (sampleRate <= 0) {
            throw new MediaException.Malformed("G.726 track without a sampling rate");
        }
        this.limits = options.limits();
        LimitChecks.checkAllocation(limits, WaveformConcealer.footprint(1, sampleRate));
        this.codec = new G726Codec(bits);
        this.output = new Pcm16Output(ChannelLayout.MONO, sampleRate);
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
        var bits = codec.bits;
        var samples = (int) ((long) data.length * 8 / bits);
        LimitChecks.checkAllocation(limits, (long) samples * (Integer.BYTES + Double.BYTES));
        var plane = output.planes(Math.max(samples, 1))[0];
        var mask = (1 << bits) - 1;
        var accumulator = 0;
        var available = 0;
        var position = 0;
        for (var i = 0; i < samples; i++) {
            if (available < bits) {
                accumulator = accumulator << 8 | data[position++] & 0xFF;
                available += 8;
            }
            available -= bits;
            codec.estimate();
            plane[i] = toLinear(codec.update(accumulator >>> available & mask));
        }
        return samples;
    }

    /**
     * Converts a reconstructed signal to a 16-bit sample (LIMO of Annex A, then the 14-bit value scaled to 16 bits).
     *
     * @param sr the reconstructed signal, 16-bit two's complement
     * @return the sample, a multiple of 4
     */
    static int toLinear(int sr) {
        return Math.max(-8192, Math.min(8191, (short) sr)) << 2;
    }
}
