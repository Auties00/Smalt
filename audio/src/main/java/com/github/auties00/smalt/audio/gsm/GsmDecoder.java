package com.github.auties00.smalt.audio.gsm;

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
 * Decodes ETSI GSM 06.10 full-rate speech ({@link Codec#GSM} and {@link Codec#GSM_MS}) packets into 16-bit
 * samples.
 *
 * <p>The track is one channel, usually at 8 kHz. A {@link Codec#GSM} packet holds whole 33-byte frames and a
 * {@link Codec#GSM_MS} packet whole 65-byte blocks of two frames, in the layouts the codec constants describe; each
 * frame decodes to 160 samples, and the decoder state continues from one frame to the next. A packet that does not
 * hold whole frames, or a 33-byte frame without the signature {@code 0xD}, raises
 * {@link MediaException.Malformed}.
 *
 * <p>The decoder is the bit-exact RPE-LTP decoder of ETSI EN 300 961, including the optional codec homing of its
 * clause 4: a decoder homing frame returns the decoder to its initial state, and one received in that state decodes
 * to the encoder homing frame. Its output equals the decoder outputs of the EN 300 961 test sequences, with and
 * without homing frames; a 16-bit sample carries the Recommendation's 13 bits at the top, its low three bits zero.
 *
 * <p>The float and double outputs scale the 16-bit samples by {@code 2^-15}. A lost span
 * ({@link #conceal(int, Consumer)}) repeats the last pitch period of the float output, fading to silence, as
 * ITU-T G.711 Appendix I conceals G.711 frames; the decoder state continues unchanged after it.
 *
 * <p>Instances are not thread-safe.
 *
 * @implNote This implementation ports libgsm 1.0.22, whose arithmetic it keeps, adding the codec homing that
 * libgsm leaves out.
 */
public final class GsmDecoder implements AudioDecoder {
    private final boolean microsoft;
    private final int sampleRate;
    private final MediaLimits limits;
    private final Gsm0610 codec = new Gsm0610();
    private final int[] params = new int[Gsm0610.PARAMETERS];
    private final Pcm16Output output;

    /**
     * Constructs a decoder for a GSM 06.10 track.
     *
     * @param track the track, of one channel
     * @throws IllegalArgumentException if the track's codec is not {@link Codec#GSM} or {@link Codec#GSM_MS}
     * @throws MediaException.Unsupported if the track has more than one channel
     */
    public GsmDecoder(AudioTrack track) {
        this(track, AudioDecoderOptions.defaults());
    }

    /**
     * Constructs a decoder for a GSM 06.10 track under the limits of codec-independent options.
     *
     * <p>Only {@link AudioDecoderOptions#limits()} applies: the decoder always outputs the track's own rate, or
     * 8 kHz when the track declares none.
     *
     * @param track the track, of one channel
     * @param options the codec-independent options, of which only the limits apply
     * @throws IllegalArgumentException if the track's codec is not {@link Codec#GSM} or {@link Codec#GSM_MS}
     * @throws MediaException.Unsupported if the track has more than one channel
     */
    public GsmDecoder(AudioTrack track, AudioDecoderOptions options) {
        Objects.requireNonNull(track, "track");
        Objects.requireNonNull(options, "options");
        if (track.codec() != Codec.GSM && track.codec() != Codec.GSM_MS) {
            throw new IllegalArgumentException("not a GSM 06.10 track: " + track.codec());
        }
        if (track.layout().map(ChannelLayout::channels).orElse(1) != 1) {
            throw new MediaException.Unsupported("multichannel GSM 06.10", "GSM 06.10 tracks of "
                    + track.layout().orElseThrow().channels() + " channels are not supported");
        }
        this.microsoft = track.codec() == Codec.GSM_MS;
        this.sampleRate = track.sampleRate() > 0 ? track.sampleRate() : 8000;
        this.limits = options.limits();
        LimitChecks.checkAllocation(limits, WaveformConcealer.footprint(1, sampleRate));
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
     * @throws MediaException.Malformed if the packet does not hold whole frames, or a frame lacks its signature
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
     * @throws MediaException.Malformed if the packet does not hold whole frames, or a frame lacks its signature
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
     * @throws MediaException.Malformed if the packet does not hold whole frames, or a frame lacks its signature
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
        var unit = microsoft ? GsmFrames.BLOCK_BYTES : GsmFrames.FRAME_BYTES;
        if (data.length % unit != 0) {
            throw new MediaException.Malformed("GSM 06.10 packet of " + data.length + " bytes is not whole "
                    + unit + "-byte " + (microsoft ? "blocks" : "frames"));
        }
        var frames = data.length / unit * (microsoft ? 2 : 1);
        var samples = frames * Gsm0610.SAMPLES;
        LimitChecks.checkAllocation(limits, (long) samples * (Integer.BYTES + Double.BYTES));
        var plane = output.planes(Math.max(samples, 1))[0];
        for (var f = 0; f < frames; f++) {
            if (microsoft) {
                GsmFrames.unpackMs(data, f / 2 * unit, (f & 1) != 0, params);
            } else if (!GsmFrames.unpack(data, f * unit, params)) {
                throw new MediaException.Malformed("GSM 06.10 frame without its signature");
            }
            codec.decode(params, plane, f * Gsm0610.SAMPLES);
        }
        return samples;
    }
}
