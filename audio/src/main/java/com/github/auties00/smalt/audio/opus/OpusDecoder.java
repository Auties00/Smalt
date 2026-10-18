package com.github.auties00.smalt.audio.opus;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.audio.AudioDecoder;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.opus.internal.OpusDefines;
import com.github.auties00.smalt.audio.opus.internal.OpusMultistreamDecoder;
import com.github.auties00.smalt.audio.opus.internal.OpusStreamDecoder;
import com.github.auties00.smalt.internal.codec.ConsumerFailure;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Decodes Opus audio (RFC 6716), either a track into planar float samples or, for real-time uses
 * such as the jitter buffer of a voice call, single packets into interleaved samples with loss
 * concealment and forward error correction.
 *
 * <p>A decoder created for a track reads the track's {@link AudioTrack#codecConfig()}, the
 * {@code OpusHead} identification header (RFC 7845 section 5.1): its channel count, pre-skip,
 * output gain and channel mapping. Mapping family 0 (mono or stereo) and family 1 (up to 7.1,
 * Vorbis order) are reordered to the WAVE order of {@link ChannelLayout#defaultFor(int)}; family
 * 255, the ambisonic family 2 and reserved families are decoded as a multistream with
 * {@link com.github.auties00.smalt.audio.Speaker#UNKNOWN} positions. The output gain is applied
 * inside the decoder and the first {@code preSkip} samples (counted at 48 kHz and scaled to the
 * output rate, rounding down) are discarded by {@link #decode(Packet, Consumer)}; trimming the end
 * of the stream is the container's job (the Ogg end granule position). A decoder created without
 * a track ({@link #OpusDecoder(OpusDecoderOptions)}) decodes a single mono or stereo stream with
 * no pre-skip and no gain.
 *
 * <p>{@link #decode(Packet, Consumer)} accepts every packet form: 1 to 48 frames (codes 0 to 3, with padding and CBR or
 * VBR framing), every mode (SILK-only, hybrid, CELT-only), every bandwidth, every frame size from 2.5 to 60 ms, and
 * mono/stereo switches within a stream. A zero-length packet runs the packet-loss concealment, for the packet's
 * {@link Packet#duration()} when known and for the duration of the previous packet otherwise. The output is not
 * soft-clipped, so samples may slightly exceed {@code [-1, 1]}.
 *
 * <p>The per-packet methods ({@link #decode(byte[], int, int, short[], int, int)},
 * {@link #recover(byte[], int, int, short[], int, int)}, {@link #conceal(short[], int, int)} and
 * their float overloads) work on a single-stream decoder (mono or stereo) and write interleaved
 * samples into a caller-owned array, without pre-skip. Every call names a size in samples per
 * channel, which means one of two things:
 * <ul>
 * <li>for {@code decode}, the capacity of the output: the packet is decoded whole and the call
 *     returns its duration, which must not exceed the capacity;</li>
 * <li>for {@code conceal} and {@code recover}, the number of samples to produce, a multiple of
 *     2.5 ms: normally the duration of the packet that was lost.</li>
 * </ul>
 * {@code recover} conceals the part of the span that precedes the last frame of the packet carrying
 * the in-band copy and rebuilds that last frame from the copy, so a lost 60 ms packet is rebuilt by
 * passing the following 60 ms packet with a 60 ms span. When the copy cannot be used (the packet
 * carries no SILK frame, or the span is shorter than one of its frames) the whole span is concealed
 * instead.
 *
 * <p>The decoder runs natively at the {@linkplain OpusDecoderOptions#sampleRate() output rate},
 * which need not match the rate the stream was encoded at. Decoded samples are bit-identical to
 * those of the float build of libopus 1.5.2 without SIMD. Instances are not thread-safe.
 *
 * @implNote This implementation decodes natively at the requested output rate rather than
 * resampling a 48 kHz decode: at 8, 12 or 16 kHz the SILK layer skips its upsampler and CELT
 * decodes a band-limited, decimated signal.
 */
public final class OpusDecoder implements AudioDecoder {
    /**
     * The time base of packet durations for a decoder created without a track: 48 kHz, the Opus
     * timestamp rate (RFC 7845 section 4).
     */
    private static final Rational PACKET_TIME_BASE = Rational.timeBaseOf(48000);

    /**
     * The options the decoder was created with.
     */
    private final OpusDecoderOptions options;

    /**
     * The output sampling rate in hertz.
     */
    private final int sampleRate;

    /**
     * The output channel layout.
     */
    private final ChannelLayout layout;

    /**
     * The time base of the packet durations.
     */
    private final Rational timeBase;

    /**
     * The multistream decoder doing the work.
     */
    private final OpusMultistreamDecoder decoder;

    /**
     * The decoder of the only elementary stream, or {@code null} for a multistream.
     */
    private final OpusStreamDecoder stream;

    /**
     * The output plane of each coded channel.
     */
    private final int[] route;

    /**
     * The reusable output buffer, sized for the longest packet (120 ms).
     */
    private final AudioBuffer buffer;

    /**
     * The number of leading output samples still to discard.
     */
    private long skipRemaining;

    /**
     * The duration of the previous packet in output samples, used to size concealment.
     */
    private int lastDuration;

    /**
     * Whether the decoder was closed.
     */
    private boolean closed;

    /**
     * Constructs a decoder for a track producing 48 kHz output in the track's own layout under
     * {@link com.github.auties00.smalt.MediaLimits#DEFAULT}.
     *
     * @param track the Opus track; its codec configuration holds the {@code OpusHead}
     * @throws IllegalArgumentException if the track is not an Opus track
     * @throws MediaException.Malformed if the {@code OpusHead} is missing or corrupt
     * @throws MediaException.Unsupported if the header uses an unsupported version or mapping family
     * @throws MediaException.LimitExceeded if the channel count exceeds the default limits
     */
    public OpusDecoder(AudioTrack track) {
        this(track, OpusDecoderOptions.defaults());
    }

    /**
     * Constructs a decoder for a track, decoding at the options' rate and, for a mono or stereo
     * track, to the options' channel count when one is set.
     *
     * @param track the Opus track; its codec configuration holds the {@code OpusHead}
     * @param options the limits, output rate and output channel count
     * @throws IllegalArgumentException if the track is not an Opus track
     * @throws MediaException.Malformed if the {@code OpusHead} is missing or corrupt
     * @throws MediaException.Unsupported if the header uses an unsupported version or mapping family,
     * or the options ask a multichannel track for another channel count
     * @throws MediaException.LimitExceeded if the channel count exceeds the options' limits
     */
    public OpusDecoder(AudioTrack track, OpusDecoderOptions options) {
        Objects.requireNonNull(track, "track");
        Objects.requireNonNull(options, "options");
        if (track.codec() != Codec.OPUS) {
            throw new IllegalArgumentException(track.codec() + " is not Opus");
        }
        var parsed = track.codecConfig()
                .map(config -> OpusHead.parse(config, options.limits()))
                .orElseGet(() -> fallbackHead(track));
        this(options, remix(parsed, options), track.timeBase());
    }

    /**
     * Constructs a decoder without a track, for a single mono or stereo stream of packets such as
     * the one a voice call carries.
     *
     * <p>The decoder outputs the options' channel count, or stereo when the options leave it to
     * the stream, at the options' rate; it applies no pre-skip and no gain. Packet durations
     * passed to {@link #decode(Packet, Consumer)} count samples at 48 kHz.
     *
     * @param options the output rate and channel count
     */
    public OpusDecoder(OpusDecoderOptions options) {
        Objects.requireNonNull(options, "options");
        this(options, OpusHead.family0(options.channels().orElse(2), 0, options.sampleRate()), PACKET_TIME_BASE);
    }

    private OpusDecoder(OpusDecoderOptions options, OpusHead head, Rational timeBase) {
        this.options = options;
        this.sampleRate = options.sampleRate();
        this.layout = head.layout();
        this.timeBase = timeBase;
        try {
            this.decoder = new OpusMultistreamDecoder(sampleRate, head.channels(), head.streamCount(),
                    head.coupledCount(), head.mapping());
        } catch (IllegalArgumentException exception) {
            throw new MediaException.Malformed("invalid OpusHead channel layout", exception);
        }
        if (head.outputGain() != 0) {
            decoder.setGain(head.outputGain());
        }
        this.stream = decoder.streamCount() == 1 ? decoder.stream(0) : null;
        this.route = new int[head.channels()];
        for (var i = 0; i < route.length; i++) {
            route[i] = head.wavePosition(i);
        }
        this.buffer = new AudioBuffer(layout, sampleRate, decoder.maxFrameSize());
        this.skipRemaining = (long) head.preSkip() * sampleRate / 48000;
    }

    /**
     * Returns the header to use for a track that carries no {@code OpusHead}.
     *
     * @param track the track whose declared layout and sampling rate the header takes
     * @return a family 0 header for the track's declared mono or stereo layout
     * @throws MediaException.Malformed if the track declares no usable layout
     */
    private static OpusHead fallbackHead(AudioTrack track) {
        var channels = track.layout().map(ChannelLayout::channels).orElse(0);
        if (channels != 1 && channels != 2) {
            throw new MediaException.Malformed("Opus track without an OpusHead");
        }
        return OpusHead.family0(channels, 0, track.sampleRate());
    }

    /**
     * Returns the header that decodes a track into the options' channel count.
     *
     * @param head the track's header
     * @param options the decoding options, whose channel count is the target
     * @return {@code head} when the options keep its channel count, otherwise a single-stream
     * header with the requested count and the same pre-skip and gain
     * @throws MediaException.Unsupported if the track is not a single mono or stereo stream
     */
    private static OpusHead remix(OpusHead head, OpusDecoderOptions options) {
        var requested = options.channels();
        if (requested.isEmpty() || requested.getAsInt() == head.channels()) {
            return head;
        }
        var mapping = head.mapping();
        var single = head.streamCount() == 1 && head.channels() <= 2;
        for (var i = 0; single && i < head.channels(); i++) {
            single = mapping[i] == i;
        }
        if (!single) {
            throw new MediaException.Unsupported("opus channel remix", "cannot decode a " + head.channels()
                    + "-channel multistream Opus track into " + requested.getAsInt() + " channels");
        }
        return OpusHead.family0(requested.getAsInt(), head.preSkip(), head.inputSampleRate(), head.outputGain());
    }

    /**
     * {@return the options the decoder was created with}
     */
    public OpusDecoderOptions options() {
        return options;
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
     * <p>Each packet yields at most one block; the leading pre-skip samples are dropped, so the
     * first packets may yield none.
     *
     * @throws IllegalStateException if the decoder was closed
     */
    @Override
    public void decode(Packet packet, Consumer<AudioBuffer> output) {
        Objects.requireNonNull(packet, "packet");
        Objects.requireNonNull(output, "output");
        checkOpen();
        ConsumerFailure.<AudioBuffer>run("Opus packet", output, sink -> decodePacket(packet, sink));
    }

    /**
     * Decodes one packet of the track into {@link #buffer} and hands the samples left after the pre-skip to
     * {@code output}; the caller translates the runtime failures of the decoder.
     */
    private void decodePacket(Packet packet, Consumer<AudioBuffer> output) {
        var data = packet.data();
        int decoded;
        if (data.length == 0) {
            decoded = decoder.decode(null, 0, 0, buffer.planes(), 0, route, concealmentSize(packet), false);
        } else {
            decoded = decoder.decode(data, 0, data.length, buffer.planes(), 0, route, decoder.maxFrameSize(), false);
        }
        if (decoded < 0) {
            throw new MediaException.Malformed("invalid Opus packet: " + OpusDefines.strerror(decoded));
        }
        lastDuration = decoded;
        var start = 0;
        if (skipRemaining > 0) {
            start = (int) Math.min(skipRemaining, decoded);
            skipRemaining -= start;
        }
        var frames = decoded - start;
        if (frames <= 0) {
            return;
        }
        if (start > 0) {
            for (var plane : buffer.planes()) {
                System.arraycopy(plane, start, plane, 0, frames);
            }
        }
        buffer.setFrames(frames);
        output.accept(buffer);
    }

    /**
     * Returns the number of samples to conceal for a zero-length packet.
     *
     * @param packet the empty packet
     * @return its duration at the output rate when known, else the previous packet's duration,
     *         rounded down to a multiple of 2.5 ms and clamped to 120 ms
     */
    private int concealmentSize(Packet packet) {
        var step = sampleRate / 400;
        long samples = lastDuration;
        if (packet.duration() > 0) {
            try {
                samples = timeBase.rescale(packet.duration(), Rational.timeBaseOf(sampleRate));
            } catch (ArithmeticException exception) {
                samples = decoder.maxFrameSize();
            }
        }
        samples = Math.min(samples, decoder.maxFrameSize());
        samples -= samples % step;
        return (int) Math.max(samples, step);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Opus has no decoder delay beyond the pre-skip, so there is nothing to drain.
     */
    @Override
    public void flush(Consumer<AudioBuffer> output) {
        Objects.requireNonNull(output, "output");
    }

    /**
     * Decodes, conceals or recovers into interleaved 16-bit samples: a {@code null} packet or a zero
     * {@code length} conceals {@code frameSize} samples, and {@code fec} rebuilds the previous packet
     * from this packet's in-band copy.
     */
    private int decode(byte[] packet, int offset, int length, short[] pcm, int pcmOffset, int frameSize, boolean fec) {
        Objects.requireNonNull(pcm, "pcm");
        var stream = singleStream();
        checkSpan(stream, packet, offset, length, pcm.length, pcmOffset, frameSize);
        int result;
        try {
            result = stream.decode(packet, offset, length, pcm, pcmOffset, frameSize, fec);
        } catch (RuntimeException exception) {
            throw new MediaException.Malformed("corrupt Opus packet", exception);
        }
        return decoded(result, frameSize);
    }

    /**
     * Decodes, conceals or recovers into interleaved float samples, as the 16-bit overload does.
     */
    private int decode(byte[] packet, int offset, int length, float[] pcm, int pcmOffset, int frameSize, boolean fec) {
        Objects.requireNonNull(pcm, "pcm");
        var stream = singleStream();
        checkSpan(stream, packet, offset, length, pcm.length, pcmOffset, frameSize);
        int result;
        try {
            result = stream.decodeNative(packet, offset, length, pcm, pcmOffset, frameSize, fec, false, null, false);
        } catch (RuntimeException exception) {
            throw new MediaException.Malformed("corrupt Opus packet", exception);
        }
        return decoded(result, frameSize);
    }

    /**
     * Decodes one packet into interleaved 16-bit samples, soft-clipped into range.
     *
     * <p>The packet is decoded whole; the call returns its duration, which must not exceed
     * {@code maxFrameSize}. The output holds {@code layout().channels()} interleaved channels. A
     * lost packet is concealed with {@link #conceal(short[], int, int)} or rebuilt with
     * {@link #recover(byte[], int, int, short[], int, int)} instead.
     *
     * @param packet the buffer holding the packet
     * @param offset the index of the packet's table-of-contents byte
     * @param length the packet length, at least 1
     * @param pcm receives the interleaved samples; it must hold {@code maxFrameSize} samples per
     * channel from {@code pcmOffset}
     * @param pcmOffset the index of the first output sample
     * @param maxFrameSize the output capacity in samples per channel
     * @return the number of samples per channel written, the packet's duration
     * @throws IllegalArgumentException if {@code length} is zero, or {@code maxFrameSize} is not
     * positive or is shorter than the packet
     * @throws IllegalStateException if the decoder was closed or decodes a multistream
     * @throws IndexOutOfBoundsException if the packet or the output span lies outside its array
     * @throws MediaException.Malformed if the packet is invalid or corrupt
     */
    public int decode(byte[] packet, int offset, int length, short[] pcm, int pcmOffset, int maxFrameSize) {
        checkPacket(packet, offset, length);
        return decode(packet, offset, length, pcm, pcmOffset, maxFrameSize, false);
    }

    /**
     * Decodes one packet into interleaved float samples, without clipping.
     *
     * <p>The packet is decoded whole; the call returns its duration, which must not exceed
     * {@code maxFrameSize}. The output holds {@code layout().channels()} interleaved channels and
     * may slightly exceed {@code [-1, 1]}. A lost packet is concealed with
     * {@link #conceal(float[], int, int)} or rebuilt with
     * {@link #recover(byte[], int, int, float[], int, int)} instead.
     *
     * @param packet the buffer holding the packet
     * @param offset the index of the packet's table-of-contents byte
     * @param length the packet length, at least 1
     * @param pcm receives the interleaved samples; it must hold {@code maxFrameSize} samples per
     * channel from {@code pcmOffset}
     * @param pcmOffset the index of the first output sample
     * @param maxFrameSize the output capacity in samples per channel
     * @return the number of samples per channel written, the packet's duration
     * @throws IllegalArgumentException if {@code length} is zero, or {@code maxFrameSize} is not
     * positive or is shorter than the packet
     * @throws IllegalStateException if the decoder was closed or decodes a multistream
     * @throws IndexOutOfBoundsException if the packet or the output span lies outside its array
     * @throws MediaException.Malformed if the packet is invalid or corrupt
     */
    public int decode(byte[] packet, int offset, int length, float[] pcm, int pcmOffset, int maxFrameSize) {
        checkPacket(packet, offset, length);
        return decode(packet, offset, length, pcm, pcmOffset, maxFrameSize, false);
    }

    /**
     * Rebuilds a lost packet into interleaved 16-bit samples from the forward error correction
     * data of the packet that follows it, soft-clipped into range.
     *
     * <p>The call produces exactly {@code frameSize} samples per channel, normally the duration of
     * the lost packet: the span before the last frame of {@code nextPacket} is concealed and that
     * last frame is rebuilt from the copy, so a lost 60 ms packet is rebuilt by passing the
     * following 60 ms packet with a {@code frameSize} of 60 ms. When the copy cannot be used (the
     * packet carries none, see {@link OpusPackets#hasInbandFec(byte[], int, int)}, or
     * {@code frameSize} is shorter than one of its frames) the whole span is concealed, as
     * {@link #conceal(short[], int, int)} does. {@code nextPacket} itself is not decoded: pass it
     * to {@link #decode(byte[], int, int, short[], int, int)} afterwards.
     *
     * @param nextPacket the buffer holding the packet that follows the lost one
     * @param offset the index of the packet's table-of-contents byte
     * @param length the packet length, at least 1
     * @param pcm receives the interleaved samples; it must hold {@code frameSize} samples per
     * channel from {@code pcmOffset}
     * @param pcmOffset the index of the first output sample
     * @param frameSize the number of samples per channel to produce, a multiple of 2.5 ms
     * @return {@code frameSize}
     * @throws IllegalArgumentException if {@code length} is zero, or {@code frameSize} is not a
     * positive multiple of 2.5 ms
     * @throws IllegalStateException if the decoder was closed or decodes a multistream
     * @throws IndexOutOfBoundsException if the packet or the output span lies outside its array
     * @throws MediaException.Malformed if the packet is invalid or corrupt
     */
    public int recover(byte[] nextPacket, int offset, int length, short[] pcm, int pcmOffset, int frameSize) {
        checkPacket(nextPacket, offset, length);
        return decode(nextPacket, offset, length, pcm, pcmOffset, frameSize, true);
    }

    /**
     * Rebuilds a lost packet into interleaved float samples from the forward error correction
     * data of the packet that follows it, without clipping.
     *
     * <p>The call produces exactly {@code frameSize} samples per channel, as
     * {@link #recover(byte[], int, int, short[], int, int)} does, and may slightly exceed
     * {@code [-1, 1]}. {@code nextPacket} itself is not decoded: pass it to
     * {@link #decode(byte[], int, int, float[], int, int)} afterwards.
     *
     * @param nextPacket the buffer holding the packet that follows the lost one
     * @param offset the index of the packet's table-of-contents byte
     * @param length the packet length, at least 1
     * @param pcm receives the interleaved samples; it must hold {@code frameSize} samples per
     * channel from {@code pcmOffset}
     * @param pcmOffset the index of the first output sample
     * @param frameSize the number of samples per channel to produce, a multiple of 2.5 ms
     * @return {@code frameSize}
     * @throws IllegalArgumentException if {@code length} is zero, or {@code frameSize} is not a
     * positive multiple of 2.5 ms
     * @throws IllegalStateException if the decoder was closed or decodes a multistream
     * @throws IndexOutOfBoundsException if the packet or the output span lies outside its array
     * @throws MediaException.Malformed if the packet is invalid or corrupt
     */
    public int recover(byte[] nextPacket, int offset, int length, float[] pcm, int pcmOffset, int frameSize) {
        checkPacket(nextPacket, offset, length);
        return decode(nextPacket, offset, length, pcm, pcmOffset, frameSize, true);
    }

    /**
     * Checks the packet span of a call that needs a packet.
     *
     * @throws IllegalArgumentException if {@code length} is zero
     * @throws IndexOutOfBoundsException if the packet lies outside {@code packet}
     */
    private static void checkPacket(byte[] packet, int offset, int length) {
        Objects.requireNonNull(packet, "packet");
        Objects.checkFromIndexSize(offset, length, packet.length);
        if (length == 0) {
            throw new IllegalArgumentException("empty Opus packet");
        }
    }

    /**
     * Conceals one lost packet into interleaved 16-bit samples, synthesized from the decoder's
     * history and soft-clipped into range.
     *
     * @param pcm receives the interleaved samples; it must hold {@code frameSize} samples per channel
     * from {@code pcmOffset}
     * @param pcmOffset the index of the first output sample
     * @param frameSize the number of samples per channel to produce, a multiple of 2.5 ms
     * @return {@code frameSize}
     * @throws IllegalArgumentException if {@code frameSize} is not a positive multiple of 2.5 ms
     * @throws IllegalStateException if the decoder was closed or decodes a multistream
     * @throws IndexOutOfBoundsException if the output span lies outside {@code pcm}
     */
    public int conceal(short[] pcm, int pcmOffset, int frameSize) {
        return decode(null, 0, 0, pcm, pcmOffset, frameSize, false);
    }

    /**
     * Conceals one lost packet into interleaved float samples, synthesized from the decoder's
     * history, without clipping.
     *
     * @param pcm receives the interleaved samples; it must hold {@code frameSize} samples per channel
     * from {@code pcmOffset}
     * @param pcmOffset the index of the first output sample
     * @param frameSize the number of samples per channel to produce, a multiple of 2.5 ms
     * @return {@code frameSize}
     * @throws IllegalArgumentException if {@code frameSize} is not a positive multiple of 2.5 ms
     * @throws IllegalStateException if the decoder was closed or decodes a multistream
     * @throws IndexOutOfBoundsException if the output span lies outside {@code pcm}
     */
    public int conceal(float[] pcm, int pcmOffset, int frameSize) {
        return decode(null, 0, 0, pcm, pcmOffset, frameSize, false);
    }

    /**
     * Returns the decoder of the only elementary stream.
     *
     * @return the stream decoder
     * @throws IllegalStateException if the decoder was closed or decodes a multistream
     */
    private OpusStreamDecoder singleStream() {
        checkOpen();
        if (stream == null) {
            throw new IllegalStateException("per-packet decoding needs a mono or stereo stream, not "
                    + decoder.streamCount() + " streams");
        }
        return stream;
    }

    /**
     * Checks that the decoder is open.
     *
     * @throws IllegalStateException if the decoder was closed
     */
    private void checkOpen() {
        if (closed) {
            throw new IllegalStateException("decoder closed");
        }
    }

    /**
     * Checks the bounds of one per-packet decode call before the decoder reads or writes anything.
     *
     * @param packet the packet buffer, or {@code null}
     * @param pcmLength the length of the output array
     * @throws IllegalArgumentException if {@code frameSize} is not positive
     * @throws IndexOutOfBoundsException if the packet or the output span lies outside its array
     */
    private static void checkSpan(OpusStreamDecoder stream, byte[] packet, int offset, int length, int pcmLength,
                                  int pcmOffset, int frameSize) {
        if (frameSize <= 0) {
            throw new IllegalArgumentException("invalid Opus frame size " + frameSize);
        }
        if (packet != null) {
            Objects.checkFromIndexSize(offset, length, packet.length);
        }
        Objects.checkFromIndexSize(pcmOffset, (int) Math.min(Integer.MAX_VALUE, (long) frameSize * stream.channels()), pcmLength);
    }

    /**
     * Turns the result of one per-packet decode call into a sample count or an exception.
     *
     * @param result the sample count or a negative {@code OPUS_*} error code
     * @param frameSize the frame size the call used, for the message
     * @return {@code result} when it is a sample count
     * @throws IllegalArgumentException if the frame size was rejected
     * @throws MediaException.Malformed if the packet was rejected
     */
    private int decoded(int result, int frameSize) {
        if (result >= 0) {
            if (result > 0) {
                lastDuration = result;
            }
            return result;
        }
        if (result == OpusDefines.OPUS_BAD_ARG) {
            throw new IllegalArgumentException("Opus frame size " + frameSize + " is not a multiple of 2.5 ms at "
                    + sampleRate + " Hz");
        }
        if (result == OpusDefines.OPUS_BUFFER_TOO_SMALL) {
            throw new IllegalArgumentException("Opus frame size " + frameSize + " is shorter than the packet");
        }
        throw new MediaException.Malformed("invalid Opus packet: " + OpusDefines.strerror(result));
    }

    /**
     * {@return the number of samples per channel the last decode call produced, 0 before the first
     * call}
     */
    public int lastPacketDuration() {
        return decoder.lastPacketDuration();
    }

    /**
     * Returns the final state of the range decoder after the last packet, which matches the
     * encoder's {@link OpusEncoder#finalRange()} for a conformant stream.
     *
     * @return the unsigned 32-bit final range; for a multistream, the exclusive or of every stream's
     * final range
     */
    public int finalRange() {
        return decoder.finalRange();
    }

    /**
     * {@return the pitch period of the last decoded frame of the first stream, in samples at 48 kHz,
     * or 0 when the last frame carried no pitch}
     */
    public int pitch() {
        return decoder.stream(0).pitch();
    }

    /**
     * Sets the gain applied to the output, replacing the output gain of the {@code OpusHead}.
     *
     * @param gainQ8 the gain in 1/256 dB, between -32768 and 32767
     * @throws IllegalArgumentException if the value is out of range
     */
    public void setGain(int gainQ8) {
        if (gainQ8 < -32768 || gainQ8 > 32767) {
            throw new IllegalArgumentException("gain out of range: " + gainQ8);
        }
        decoder.setGain(gainQ8);
    }

    /**
     * Resets the decoding state to that of a new decoder, keeping the gain.
     *
     * <p>Concealment after a reset produces silence until a packet is decoded. Pre-skip samples
     * already discarded are not discarded again.
     */
    public void reset() {
        decoder.reset();
        lastDuration = 0;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Later decode calls throw {@link IllegalStateException}; closing twice has no effect.
     */
    @Override
    public void close() {
        closed = true;
    }
}
