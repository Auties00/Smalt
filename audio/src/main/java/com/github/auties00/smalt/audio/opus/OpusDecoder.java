/* Ports libopus 1.5.2 (src/opus_projection_decoder.c, src/mapping_matrix.c). Copyright Google Inc.; BSD 2-Clause
   License. Modified for Smalt. */
package com.github.auties00.smalt.audio.opus;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.audio.AudioDecoder;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.opus.internal.OpusDefines;
import com.github.auties00.smalt.audio.opus.internal.OpusMultistreamDecoder;
import com.github.auties00.smalt.audio.opus.internal.OpusStreamDecoder;
import com.github.auties00.smalt.audio.opus.internal.celt.MathOps;
import com.github.auties00.smalt.internal.check.Checks;
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
 * {@link com.github.auties00.smalt.audio.Speaker#UNKNOWN} positions in coded order. Family 3
 * (RFC 8486 section 3.2) multiplies the decoded channels of its streams by the demixing matrix of the
 * header, giving ambisonic channels of unknown position. The output gain is applied inside the decoder
 * and the first {@code preSkip} samples (counted at 48 kHz and scaled to the output rate, rounding down)
 * are discarded by {@link #decode(Packet, Consumer)}; trimming the end of the stream is the container's
 * job (the Ogg end granule position). A decoder created without a track
 * ({@link #OpusDecoder(OpusDecoderOptions)}) decodes a single mono or stereo stream with no pre-skip and
 * no gain.
 *
 * <p>When the {@linkplain OpusDecoderOptions#channels() options} ask for a channel count other than the
 * stream's, a single mono or stereo stream decodes to it natively and any other stream is mixed down to
 * mono or stereo by fixed matrices: the stereo downmix of RFC 7845 section 5.1.1.5 for the Vorbis order,
 * the ambisonic downmix of RFC 8486 section 4 for families 2 and 3, and the first two channels for
 * channels without positions; a mono output averages the stereo one, except that ambisonics without a
 * non-diegetic pair output the omnidirectional channel. The Vorbis-order stereo downmix is bit-identical
 * to opusfile 0.12's {@code op_read_float_stereo} for every channel count but four.
 *
 * <p>{@link #decode(Packet, Consumer)} accepts every packet form: 1 to 48 frames (codes 0 to 3, with padding and CBR or
 * VBR framing), every mode (SILK-only, hybrid, CELT-only), every bandwidth, every frame size from 2.5 to 60 ms, and
 * mono/stereo switches within a stream. A zero-length packet runs the packet-loss concealment, for the packet's
 * {@link Packet#duration()} when known and for the duration of the previous packet otherwise. The output is not
 * soft-clipped, so samples may slightly exceed {@code [-1, 1]}.
 *
 * <p>The per-packet methods ({@link #decode(byte[], int, int, short[], int, int)},
 * {@link #recover(byte[], int, int, short[], int, int)}, {@link #conceal(short[], int, int)} and
 * their float overloads) write {@code layout().channels()} interleaved channels in the order of
 * {@link #layout()} into a caller-owned array, without pre-skip, for a single stream or a
 * multistream alike. Every call names a size in samples per channel, which means one of two things:
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
 * instead; each stream of a multistream packet is rebuilt or concealed on its own. The 16-bit methods
 * soft-clip every stream before rounding.
 *
 * <p>The decoder runs natively at the {@linkplain OpusDecoderOptions#sampleRate() output rate},
 * which need not match the rate the stream was encoded at. Decoded samples are bit-identical to
 * those of the float build of libopus 1.5.2 without SIMD ({@code opus_decode}, {@code opus_multistream_decode}
 * and {@code opus_projection_decode} with their float variants), reordered to {@link #layout()}, for every
 * span of at most 120 ms. Instances are not thread-safe.
 *
 * @implNote This implementation decodes natively at the requested output rate rather than
 * resampling a 48 kHz decode: at 8, 12 or 16 kHz the SILK layer skips its upsampler and CELT
 * decodes a band-limited, decimated signal. A family 3 stream is demixed with every column of its
 * matrix, as RFC 8486 defines; libopus 1.5.2 ignores the decoded channels past the output channel
 * count and rejects a stream with fewer decoded than output channels, which no libopus encoder writes.
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
     * The number of output channels.
     */
    private final int channels;

    /**
     * The time base of the packet durations.
     */
    private final Rational timeBase;

    /**
     * The multistream decoder doing the work.
     */
    private final OpusMultistreamDecoder decoder;

    /**
     * The decoder of the only elementary stream when it writes the output directly, or {@code null}.
     */
    private final OpusStreamDecoder stream;

    /**
     * The plane each channel of {@link #decoder} is written to: an output plane when
     * {@link #decoded} is {@code null}, otherwise a plane of {@link #decoded}.
     */
    private final int[] route;

    /**
     * The planes {@link #decoder} writes when its channels are mixed before output, or {@code null}.
     */
    private final float[][] decoded;

    /**
     * The demixing coefficients of family 3 as floats, {@code (1 / 32768) * d}, indexed
     * {@code [row][decoded channel]}, or {@code null}.
     */
    private final float[][] demix;

    /**
     * The demixing coefficients of family 3 in Q15, indexed {@code [row][decoded channel]}, or {@code null}.
     */
    private final short[][] demixQ15;

    /**
     * Whether each row of {@link #demix} is computed: every row without a downmix, else only those the
     * downmix reads.
     */
    private final boolean[] demixRows;

    /**
     * The planes in coded order the downmix reads, or {@code null} without a downmix.
     */
    private final float[][] coded;

    /**
     * The downmix coefficients, indexed {@code [output channel][coded channel]}, or {@code null}.
     */
    private final float[][] downmix;

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
     * @throws MediaException.Unsupported if the header uses an unsupported version
     * @throws MediaException.LimitExceeded if the channel count exceeds the default limits
     */
    public OpusDecoder(AudioTrack track) {
        this(track, OpusDecoderOptions.defaults());
    }

    /**
     * Constructs a decoder for a track, decoding at the options' rate and to the options' channel
     * count when one is set.
     *
     * @param track the Opus track; its codec configuration holds the {@code OpusHead}
     * @param options the limits, output rate and output channel count
     * @throws IllegalArgumentException if the track is not an Opus track
     * @throws MediaException.Malformed if the {@code OpusHead} is missing or corrupt
     * @throws MediaException.Unsupported if the header uses an unsupported version
     * @throws MediaException.LimitExceeded if the channel count of the header exceeds the options' limits
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
        this(options, nativeRemix(parsed, options), track.timeBase());
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
        this.timeBase = timeBase;
        var projection = head.mappingFamily() == 3;
        var target = options.channels().orElse(head.channels());
        var mixed = target != head.channels();
        try {
            if (projection) {
                var identity = new int[head.decodedChannels()];
                for (var i = 0; i < identity.length; i++) {
                    identity[i] = i;
                }
                this.decoder = new OpusMultistreamDecoder(sampleRate, identity.length, head.streamCount(),
                        head.coupledCount(), identity);
            } else {
                this.decoder = new OpusMultistreamDecoder(sampleRate, head.channels(), head.streamCount(),
                        head.coupledCount(), head.mapping());
            }
        } catch (IllegalArgumentException exception) {
            throw new MediaException.Malformed("invalid OpusHead channel layout", exception);
        }
        if (head.outputGain() != 0) {
            decoder.setGain(head.outputGain());
        }
        var max = decoder.maxFrameSize();
        this.channels = target;
        this.layout = mixed ? ChannelLayout.defaultFor(target) : head.layout();
        this.downmix = mixed ? OpusDownmix.matrix(head.mappingFamily(), head.channels(), target) : null;
        var direct = !projection && !mixed;
        this.route = new int[projection ? head.decodedChannels() : head.channels()];
        var single = direct && decoder.streamCount() == 1 && head.channels() == head.coupledCount() + 1;
        var mapping = projection ? null : head.mapping();
        for (var i = 0; i < route.length; i++) {
            route[i] = direct ? head.wavePosition(i) : i;
            single &= direct && route[i] == i && mapping[i] == i;
        }
        this.stream = single ? decoder.stream(0) : null;
        this.decoded = direct ? null : new float[route.length][max];
        if (projection) {
            var rows = head.channels();
            var columns = head.decodedChannels();
            var matrix = head.demixing();
            this.demix = new float[rows][columns];
            this.demixQ15 = new short[rows][columns];
            this.demixRows = new boolean[rows];
            for (var r = 0; r < rows; r++) {
                for (var k = 0; k < columns; k++) {
                    var value = matrix[k * rows + r];
                    demixQ15[r][k] = value;
                    demix[r][k] = (1 / 32768.f) * value;
                }
                demixRows[r] = !mixed;
                for (var o = 0; mixed && o < target; o++) {
                    demixRows[r] |= downmix[o][r] != 0;
                }
            }
        } else {
            this.demix = null;
            this.demixQ15 = null;
            this.demixRows = null;
        }
        this.coded = !mixed ? null : projection ? new float[head.channels()][max] : decoded;
        this.buffer = new AudioBuffer(layout, sampleRate, max);
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
     * Returns the header that decodes a single mono or stereo stream natively into the options'
     * channel count.
     *
     * @param head the track's header
     * @param options the decoding options, whose channel count is the target
     * @return a single-stream header with the requested count and the same pre-skip and gain when
     * the track is a single stream that feeds its channels in order and the options ask for another
     * count, otherwise {@code head}, which a downmix brings to the requested count
     */
    private static OpusHead nativeRemix(OpusHead head, OpusDecoderOptions options) {
        var requested = options.channels();
        if (requested.isEmpty() || requested.getAsInt() == head.channels() || head.mappingFamily() == 3) {
            return head;
        }
        var mapping = head.mapping();
        var single = head.streamCount() == 1 && head.channels() <= 2 && head.coupledCount() == head.channels() - 1;
        for (var i = 0; single && i < head.channels(); i++) {
            single = mapping[i] == i;
        }
        if (!single) {
            return head;
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
        var planes = buffer.planes();
        int decoded;
        if (data.length == 0) {
            decoded = decodePlanar(null, 0, 0, planes, concealmentSize(packet), false, false);
        } else {
            decoded = decodePlanar(data, 0, data.length, planes, this.decoder.maxFrameSize(), false, false);
        }
        if (decoded < 0) {
            throw new MediaException.Malformed("invalid Opus packet: " + OpusDefines.strerror(decoded));
        }
        lastDuration = decoded;
        emit(decoded, output);
    }

    /**
     * Hands the samples of {@link #buffer} left after the pre-skip to {@code output}.
     *
     * @param decoded the number of samples per channel in {@link #buffer}
     * @param output receives the block, when samples are left
     * @return the number of samples per channel handed to {@code output}
     */
    private int emit(int decoded, Consumer<AudioBuffer> output) {
        var planes = buffer.planes();
        var start = 0;
        if (skipRemaining > 0) {
            start = (int) Math.min(skipRemaining, decoded);
            skipRemaining -= start;
        }
        var frames = decoded - start;
        if (frames <= 0) {
            return 0;
        }
        if (start > 0) {
            for (var plane : planes) {
                System.arraycopy(plane, start, plane, 0, frames);
            }
        }
        buffer.setFrames(frames);
        output.accept(buffer);
        return frames;
    }

    /**
     * Decodes, conceals or rebuilds at most 120 ms into output planes in layout order, mixing the
     * decoded channels when the stream needs it.
     *
     * @param data the packet buffer, or {@code null} to conceal
     * @param frameSize the capacity, or the number of samples to conceal or rebuild
     * @param fec whether to rebuild the previous packet from this packet's in-band copy
     * @param softClip whether each stream is soft-clipped
     * @return the number of samples per channel, or a negative {@code OPUS_*} error code
     */
    private int decodePlanar(byte[] data, int offset, int length, float[][] out, int frameSize, boolean fec,
                             boolean softClip) {
        var target = decoded != null ? decoded : out;
        var result = decoder.decode(data, offset, length, target, 0, route, frameSize, fec, softClip);
        if (result <= 0 || decoded == null) {
            return result;
        }
        if (demix != null) {
            demix(coded != null ? coded : out, result);
        }
        if (downmix != null) {
            for (var o = 0; o < downmix.length; o++) {
                mixRow(downmix[o], coded, out[o], result);
            }
        }
        return result;
    }

    /**
     * Writes the demixed rows of a family 3 frame: each computed row {@code r} is the sum over the
     * decoded channels {@code k}, in ascending order, of {@code demix[r][k] * decoded[k]}
     * ({@code mapping_matrix_multiply_channel_out_float}).
     */
    private void demix(float[][] out, int frames) {
        for (var r = 0; r < demix.length; r++) {
            if (demixRows[r]) {
                mixRow(demix[r], decoded, out[r], frames);
            }
        }
    }

    /**
     * Writes one matrix row applied to planes, accumulating the terms in ascending input order from
     * zero; zero coefficients are skipped, which leaves the finite sums unchanged.
     */
    private static void mixRow(float[] coefficients, float[][] in, float[] out, int frames) {
        for (var i = 0; i < frames; i++) {
            out[i] = 0;
        }
        for (var k = 0; k < coefficients.length; k++) {
            var c = coefficients[k];
            if (c == 0) {
                continue;
            }
            var x = in[k];
            for (var i = 0; i < frames; i++) {
                out[i] += c * x[i];
            }
        }
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
     * <p>The concealment unit is 2.5 ms; a span longer than 120 ms is concealed in several blocks of at most
     * 120 ms. The samples equal those of zero-length packets of the same durations: the pitch-based and noise-based
     * concealment of libopus 1.5.2 ({@code opus_decode} without a packet). Pre-skip samples not yet dropped are
     * dropped from them as from decoded packets.
     *
     * @throws IllegalArgumentException if {@code samples} is not positive
     * @throws IllegalStateException if the decoder was closed
     */
    @Override
    public int conceal(int samples, Consumer<AudioBuffer> output) {
        Checks.positive("samples", samples);
        Objects.requireNonNull(output, "output");
        checkOpen();
        var step = sampleRate / 400;
        var units = Math.min(Math.max(1, (samples + step / 2) / step), Integer.MAX_VALUE / step);
        var total = units * step;
        var produced = new int[1];
        ConsumerFailure.<AudioBuffer>run("Opus concealment", output, sink -> {
            var largest = decoder.maxFrameSize();
            for (var remaining = total; remaining > 0; ) {
                var span = Math.min(remaining, largest);
                var decoded = decodePlanar(null, 0, 0, buffer.planes(), span, false, false);
                if (decoded < 0) {
                    throw new IllegalStateException("Opus concealment failed: " + OpusDefines.strerror(decoded));
                }
                produced[0] += emit(decoded, sink);
                remaining -= span;
            }
        });
        return produced[0];
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
        checkOpen();
        checkSpan(packet, offset, length, pcm.length, pcmOffset, frameSize);
        int result;
        try {
            result = stream != null ? stream.decode(packet, offset, length, pcm, pcmOffset, frameSize, fec)
                    : decodeInterleaved(packet, offset, length, null, pcm, pcmOffset, frameSize, fec);
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
        checkOpen();
        checkSpan(packet, offset, length, pcm.length, pcmOffset, frameSize);
        int result;
        try {
            result = stream != null
                    ? stream.decodeNative(packet, offset, length, pcm, pcmOffset, frameSize, fec, false, null, false)
                    : decodeInterleaved(packet, offset, length, pcm, null, pcmOffset, frameSize, fec);
        } catch (RuntimeException exception) {
            throw new MediaException.Malformed("corrupt Opus packet", exception);
        }
        return decoded(result, frameSize);
    }

    /**
     * Decodes, conceals or recovers a multistream, or a stream whose channels are mixed, into one of two
     * interleaved arrays; spans longer than 120 ms are produced in pieces of at most 120 ms, the last piece
     * the one rebuilt from the in-band copy.
     *
     * @param floats the float output, or {@code null}
     * @param shorts the 16-bit output, or {@code null}
     * @return the number of samples per channel, or a negative {@code OPUS_*} error code
     */
    private int decodeInterleaved(byte[] packet, int offset, int length, float[] floats, short[] shorts,
                                  int pcmOffset, int frameSize, boolean fec) {
        var max = decoder.maxFrameSize();
        var span = packet == null || length == 0 || fec;
        if (!span) {
            return decodePiece(packet, offset, length, floats, shorts, pcmOffset, Math.min(frameSize, max), false);
        }
        var produced = 0;
        while (produced < frameSize) {
            var remaining = frameSize - produced;
            var piece = remaining > max ? remaining - (remaining - 1) / max * max : remaining;
            var last = produced + piece == frameSize;
            var result = decodePiece(last && fec ? packet : null, offset, last && fec ? length : 0, floats, shorts,
                    pcmOffset + produced * channels, piece, last && fec);
            if (result <= 0) {
                return result;
            }
            produced += result;
        }
        return produced;
    }

    /**
     * Decodes, conceals or recovers at most 120 ms into interleaved output.
     *
     * @return the number of samples per channel, or a negative {@code OPUS_*} error code
     */
    private int decodePiece(byte[] packet, int offset, int length, float[] floats, short[] shorts, int pcmOffset,
                            int frameSize, boolean fec) {
        var planes = buffer.planes();
        if (shorts != null && demix != null && downmix == null) {
            var result = decoder.decode(packet, offset, length, decoded, 0, route, frameSize, fec, true);
            if (result > 0) {
                demixShort(shorts, pcmOffset, result);
            }
            return result;
        }
        var result = decodePlanar(packet, offset, length, planes, frameSize, fec, shorts != null);
        if (result <= 0) {
            return result;
        }
        for (var c = 0; c < channels; c++) {
            var plane = planes[c];
            if (floats != null) {
                for (var i = 0; i < result; i++) {
                    floats[pcmOffset + i * channels + c] = plane[i];
                }
            } else {
                for (var i = 0; i < result; i++) {
                    shorts[pcmOffset + i * channels + c] = MathOps.float2int16(plane[i]);
                }
            }
        }
        return result;
    }

    /**
     * Writes a demixed family 3 frame as interleaved 16-bit samples: every decoded sample is rounded to
     * 16 bits and each output sample accumulates {@code (d * x + 16384) >> 15} over the decoded channels,
     * wrapping at 16 bits ({@code mapping_matrix_multiply_channel_out_short}).
     */
    private void demixShort(short[] pcm, int pcmOffset, int frames) {
        var rows = demixQ15.length;
        for (var i = 0; i < frames * rows; i++) {
            pcm[pcmOffset + i] = 0;
        }
        for (var k = 0; k < decoded.length; k++) {
            var x = decoded[k];
            for (var i = 0; i < frames; i++) {
                int sample = MathOps.float2int16(x[i]);
                var base = pcmOffset + i * rows;
                for (var r = 0; r < rows; r++) {
                    var tmp = demixQ15[r][k] * sample;
                    pcm[base + r] = (short) (pcm[base + r] + ((tmp + 16384) >> 15));
                }
            }
        }
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
     * @throws IllegalStateException if the decoder was closed
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
     * @throws IllegalStateException if the decoder was closed
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
     * {@link #conceal(short[], int, int)} does; the streams of a multistream packet are rebuilt or
     * concealed one by one. {@code nextPacket} itself is not decoded: pass it to
     * {@link #decode(byte[], int, int, short[], int, int)} afterwards.
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
     * @throws IllegalStateException if the decoder was closed
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
     * @throws IllegalStateException if the decoder was closed
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
     * @throws IllegalStateException if the decoder was closed
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
     * @throws IllegalStateException if the decoder was closed
     * @throws IndexOutOfBoundsException if the output span lies outside {@code pcm}
     */
    public int conceal(float[] pcm, int pcmOffset, int frameSize) {
        return decode(null, 0, 0, pcm, pcmOffset, frameSize, false);
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
    private void checkSpan(byte[] packet, int offset, int length, int pcmLength, int pcmOffset, int frameSize) {
        if (frameSize <= 0) {
            throw new IllegalArgumentException("invalid Opus frame size " + frameSize);
        }
        if (packet != null) {
            Objects.checkFromIndexSize(offset, length, packet.length);
        }
        Objects.checkFromIndexSize(pcmOffset, (int) Math.min(Integer.MAX_VALUE, (long) frameSize * channels),
                pcmLength);
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
        return lastDuration;
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
     * <p>The gain applies to every stream before any demixing or downmix.
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
