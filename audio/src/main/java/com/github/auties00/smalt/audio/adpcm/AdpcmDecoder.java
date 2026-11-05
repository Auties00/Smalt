package com.github.auties00.smalt.audio.adpcm;

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
 * Decodes IMA ADPCM ({@link Codec#ADPCM_IMA_WAV} and {@link Codec#ADPCM_IMA_QT}) and Microsoft ADPCM
 * ({@link Codec#ADPCM_MS}) packets into 16-bit samples.
 *
 * <p>The packet layouts are those the codec constants describe. A WAVE packet is one block, decoded on its own;
 * bytes after the last whole group of codes are ignored. An {@code ima4} packet holds whole frames, and a frame
 * continues the state of the frame before it in the stream as {@link Codec#ADPCM_IMA_QT} describes. A Microsoft
 * ADPCM track takes its predictor coefficients from its configuration, or the seven standard pairs without one.
 *
 * <p>The WAVE layouts decode bit-exactly as Microsoft's ACM codecs ({@code imaadp32.acm} and {@code msadp32.acm})
 * decode them, including their arithmetic on streams whose deltas grow past 16 bits, which wraps in 32 bits. A block
 * shorter
 * than its headers, a step index above 88, a predictor index the coefficient table lacks or an {@code ima4} packet
 * that does not hold whole frames raises {@link MediaException.Malformed}.
 *
 * <p>The integer output is 16-bit; the float and double outputs scale it by {@code 2^-15}. A lost span
 * ({@link #conceal(int, Consumer)}) repeats the last pitch period of the float output, fading to silence, as
 * ITU-T G.711 Appendix I conceals G.711 frames.
 *
 * <p>Instances are not thread-safe.
 */
public final class AdpcmDecoder implements AudioDecoder {
    private final Codec codec;
    private final ChannelLayout layout;
    private final int sampleRate;
    private final MediaLimits limits;
    private final ImaAdpcm ima;
    private final MsAdpcm ms;
    private final Pcm16Output output;

    /**
     * Constructs a decoder for an IMA or Microsoft ADPCM track.
     *
     * @param track the track, with its sampling rate and channel layout
     * @throws IllegalArgumentException if the track's codec is not {@link Codec#ADPCM_IMA_WAV},
     * {@link Codec#ADPCM_IMA_QT} or {@link Codec#ADPCM_MS}
     * @throws MediaException.Malformed if the track lacks its sampling rate or layout, or its Microsoft ADPCM
     * configuration is shorter than the coefficient table it declares
     */
    public AdpcmDecoder(AudioTrack track) {
        this(track, AudioDecoderOptions.defaults());
    }

    /**
     * Constructs a decoder for an IMA or Microsoft ADPCM track under the limits of codec-independent options.
     *
     * <p>Only {@link AudioDecoderOptions#limits()} applies: the decoder always outputs the track's own rate.
     *
     * @param track the track, with its sampling rate and channel layout
     * @param options the codec-independent options, of which only the limits apply
     * @throws IllegalArgumentException if the track's codec is not {@link Codec#ADPCM_IMA_WAV},
     * {@link Codec#ADPCM_IMA_QT} or {@link Codec#ADPCM_MS}
     * @throws MediaException.Malformed if the track lacks its sampling rate or layout, or its Microsoft ADPCM
     * configuration is shorter than the coefficient table it declares
     * @throws MediaException.LimitExceeded if the channel count exceeds the options' limits
     */
    public AdpcmDecoder(AudioTrack track, AudioDecoderOptions options) {
        Objects.requireNonNull(track, "track");
        Objects.requireNonNull(options, "options");
        this.codec = track.codec();
        if (codec != Codec.ADPCM_IMA_WAV && codec != Codec.ADPCM_IMA_QT && codec != Codec.ADPCM_MS) {
            throw new IllegalArgumentException("not an IMA or Microsoft ADPCM track: " + codec);
        }
        this.layout = track.layout().orElseThrow(() -> new MediaException.Malformed(codec.displayName()
                + " track without a channel layout"));
        this.sampleRate = track.sampleRate();
        if (sampleRate <= 0) {
            throw new MediaException.Malformed(codec.displayName() + " track without a sampling rate");
        }
        this.limits = options.limits();
        LimitChecks.checkChannels(limits, layout.channels());
        LimitChecks.checkAllocation(limits, WaveformConcealer.footprint(layout.channels(), sampleRate));
        if (codec == Codec.ADPCM_MS) {
            this.ms = new MsAdpcm(MsAdpcm.coefficients(track.codecConfig().orElse(null)));
            this.ima = null;
        } else {
            this.ima = new ImaAdpcm(layout.channels());
            this.ms = null;
        }
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
     * {@inheritDoc}
     *
     * @throws MediaException.Malformed if the packet is not a valid block or frame of the track's layout
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
     * @throws MediaException.Malformed if the packet is not a valid block or frame of the track's layout
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
     * @throws MediaException.Malformed if the packet is not a valid block or frame of the track's layout
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
     * Decodes a packet into the output's integer planes.
     *
     * @param packet the coded bytes and their timing
     * @return the samples per channel decoded
     */
    private int decodePacket(Packet packet) {
        Objects.requireNonNull(packet, "packet");
        var data = packet.data();
        var channels = layout.channels();
        var samples = switch (codec) {
            case ADPCM_IMA_WAV -> ImaAdpcm.wavSamples(data.length, channels);
            case ADPCM_IMA_QT -> data.length / (ImaAdpcm.QT_CHANNEL_BYTES * channels) * ImaAdpcm.QT_SAMPLES;
            default -> MsAdpcm.samples(data.length, channels);
        };
        LimitChecks.checkAllocation(limits, (long) samples * channels * (Integer.BYTES + Double.BYTES));
        var planes = output.planes(Math.max(samples, 1));
        return switch (codec) {
            case ADPCM_IMA_WAV -> ima.decodeWav(data, planes);
            case ADPCM_IMA_QT -> ima.decodeQt(data, planes);
            default -> ms.decode(data, planes);
        };
    }
}
