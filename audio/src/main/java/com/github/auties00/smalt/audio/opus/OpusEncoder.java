package com.github.auties00.smalt.audio.opus;

import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.audio.AudioEncoder;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.opus.internal.OpusDefines;
import com.github.auties00.smalt.audio.opus.internal.OpusMultistreamEncoder;
import com.github.auties00.smalt.audio.opus.internal.OpusStreamEncoder;
import com.github.auties00.smalt.internal.task.ParallelBands;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Encodes audio into Opus packets (RFC 6716), either buffered into packets of one duration for a
 * container or one packet per call for real-time transmission.
 *
 * <p>Mono and stereo input with the {@link OpusChannelMapping#VORBIS} mapping is coded as one
 * elementary stream (mapping family 0). Any other input, up to 255 channels, is coded as a
 * multistream (RFC 7845 section 5.1.1) whose streams the {@linkplain OpusEncoderOptions#channelMapping()
 * channel mapping} chooses: surround layouts up to 7.1 in the Vorbis order with a separate LFE stream
 * and an analysis of how much the channels mask each other, ambisonics coded channel by channel or
 * mixed by a fixed matrix (projection), or one mono stream per discrete channel. The
 * {@linkplain #codecConfig() identification header} carries the mapping, or for projection the
 * demixing matrix, a decoder needs.
 *
 * <p>The encoder offers two ways to feed it, which share one coding state:
 * <ul>
 * <li>{@link #encode(AudioBuffer, Consumer)} buffers planar float input of any length, in the
 * {@linkplain #layout() input layout}, and codes one packet per {@link #frameSize()} samples (the
 * {@linkplain OpusEncoderOptions#frameDurationMicros() packet duration} of the options); each
 * packet is emitted with {@code keyframe} set, its timestamp in samples of the input rate and its
 * duration. Samples are clipped to {@code [-1, 1]} and NaN is coded as silence.
 * {@link #flush(Consumer)} pads the stream with silence until every input sample has been coded
 * past the encoder look-ahead, so the decoded stream is longer than the input: callers trim it
 * with the container's end position (the Ogg end granule position, pre-skip plus input length at
 * 48 kHz).</li>
 * <li>{@link #encode(short[], int, int, byte[], int, int)} and
 * {@link #encode(float[], int, int, byte[], int, int)} code exactly one frame of 2.5, 5, 10, 20,
 * 40, 60, 80, 100 or 120 ms of 16-bit or float samples, interleaved in the input layout, into one
 * packet in a caller-owned buffer, without buffering, padding or timestamps: the caller decides
 * the duration of every frame. They fail while buffered input waits for a full packet.</li>
 * </ul>
 *
 * <p>The setters ({@link #setBitrate(int)}, {@link #setBitrateMode(OpusBitrateMode)},
 * {@link #setComplexity(int)}, {@link #setInbandFec(boolean)},
 * {@link #setPacketLossPercent(int)}, {@link #setDtx(boolean)} and the others) take effect from
 * the next packet, so a rate controller can retarget the encoder while the stream runs; they do
 * not change {@link #options()}, which keeps the settings the encoder was created with. A
 * multistream encoder applies each setting to every stream and splits its bitrate between them.
 * It may code its streams in parallel on the shared threads
 * {@link com.github.auties00.smalt.MediaRuntime MediaRuntime} describes; the packets do not
 * depend on the thread count.
 *
 * <p>The {@link #codecConfig()} is an {@code OpusHead} (version 1, the channel count, a pre-skip
 * equal to the encoder look-ahead at 48 kHz, the input rate, an output gain of zero or, for
 * projection, the gain of the demixing matrix, and the mapping family with, unless it is 0, the
 * stream counts and the mapping table or, for projection, the demixing matrix). An encoder with the same input
 * and the same sequence of settings produces packets byte-identical to those of the float build of
 * libopus 1.5.2 without SIMD, with one exception. A surround multistream (the {@code VORBIS}
 * mapping with three or more channels) chooses the bandwidth of each packet from the total bitrate,
 * the rate the automatic and maximum bitrates stand for included, then lets a forced bandwidth
 * replace that choice and a maximum bandwidth cap it; its packets are byte-identical to the
 * reference's at an explicit bitrate with neither bandwidth setting. Instances are not thread-safe;
 * a caller that encodes on one thread and retargets on another must serialize the calls.
 */
public final class OpusEncoder implements AudioEncoder {
    /**
     * The largest packet one per-frame encode call of a single-stream encoder emits: one 1275-byte
     * frame plus its table-of-contents byte.
     */
    private static final int MAX_FRAME_PACKET_BYTES = 1276;

    /**
     * The largest packet one stream of the buffering path can emit: six 20 ms frames of 1275 bytes
     * plus framing, and two bytes of self-delimiting length.
     */
    private static final int MAX_STREAM_PACKET = 1275 * 6 + 12 + 2;

    /**
     * The options the encoder was created with.
     */
    private final OpusEncoderOptions options;

    /**
     * The encoder of a single stream, or {@code null} for a multistream.
     */
    private final OpusStreamEncoder encoder;

    /**
     * The encoder of a multistream, or {@code null} for a single stream.
     */
    private final OpusMultistreamEncoder multistream;

    /**
     * The scheduling state of the calls of this encoder, which code multistream streams in parallel.
     */
    private final ParallelBands.Operation operation = new ParallelBands.Operation();

    /**
     * The input layout.
     */
    private final ChannelLayout layout;

    /**
     * The number of input channels.
     */
    private final int channels;

    /**
     * The input channel of each coded channel, or {@code null} when the orders agree.
     */
    private final int[] route;

    /**
     * The number of samples per channel in one buffered packet.
     */
    private final int frameSize;

    /**
     * The encoder look-ahead at the input rate.
     */
    private final int delay;

    /**
     * The serialized {@code OpusHead}.
     */
    private final byte[] head;

    /**
     * The interleaved samples, in coded channel order, of the buffered packet being filled.
     */
    private final float[] fifo;

    /**
     * The number of samples per channel in {@link #fifo}.
     */
    private int fifoFrames;

    /**
     * The packet output buffer of the buffering path.
     */
    private final byte[] packetBuffer;

    /**
     * The float frame of a per-frame call reordered to coded channel order, or {@code null} when
     * the orders agree.
     */
    private final float[] floatFrame;

    /**
     * The 16-bit frame of a per-frame call reordered to coded channel order, or {@code null} when
     * the orders agree.
     */
    private final short[] shortFrame;

    /**
     * The number of input samples per channel received.
     */
    private long inputSamples;

    /**
     * The number of samples per channel coded into emitted packets.
     */
    private long codedSamples;

    /**
     * Whether {@link #flush(Consumer)} was called.
     */
    private boolean flushed;

    /**
     * The complexity last set, 0 to 10.
     */
    private int complexity;

    /**
     * Constructs an encoder with {@link OpusEncoderOptions#defaults()}: 48 kHz stereo music or mixed
     * content at the automatic bitrate.
     */
    public OpusEncoder() {
        this(OpusEncoderOptions.defaults());
    }

    /**
     * Constructs an encoder.
     *
     * @param options the settings
     */
    public OpusEncoder(OpusEncoderOptions options) {
        this.options = Objects.requireNonNull(options, "options");
        this.layout = options.layout();
        this.channels = layout.channels();
        var mapping = options.channelMapping();
        var family = mapping.family(channels);
        var rate = options.sampleRate();
        var application = options.application().code();
        int[] coded = null;
        if (family == 0) {
            this.encoder = new OpusStreamEncoder(rate, channels, application);
            this.multistream = null;
        } else {
            this.encoder = null;
            this.multistream = new OpusMultistreamEncoder(rate, channels, family, application);
            if (family == 1) {
                coded = OpusEncoderOptions.vorbisRouting(layout);
                if (isIdentity(coded)) {
                    coded = null;
                }
            }
        }
        this.route = coded;
        if (options.bitrate().isPresent()) {
            setBitrate(options.bitrate().getAsInt());
        }
        applyBitrateMode(options.bitrateMode());
        setComplexity(options.complexity());
        setSignal(options.signal());
        if (options.maxBandwidth() != OpusBandwidth.FULLBAND) {
            setMaxBandwidth(options.maxBandwidth());
        }
        if (options.inbandFec()) {
            setInbandFec(true);
        }
        if (options.packetLossPercent() != 0) {
            setPacketLossPercent(options.packetLossPercent());
        }
        if (options.dtx()) {
            setDtx(true);
        }
        this.frameSize = options.frameSize();
        this.delay = encoder != null ? encoder.lookahead() : multistream.lookahead();
        var preSkip = delay * 48000 / rate;
        OpusHead header;
        if (encoder != null) {
            header = OpusHead.family0(channels, preSkip, rate);
        } else if (family == 3) {
            header = OpusHead.projection(channels, preSkip, rate, multistream.streamCount(),
                    multistream.coupledStreamCount(), multistream.demixingMatrix(), multistream.demixingGain());
        } else {
            header = OpusHead.multistream(channels, preSkip, rate, family, multistream.streamCount(),
                    multistream.coupledStreamCount(), multistream.mapping());
        }
        this.head = header.toBytes();
        this.fifo = new float[frameSize * channels];
        this.packetBuffer = new byte[encoder != null ? 1275 * 6 + 7 : MAX_STREAM_PACKET * multistream.streamCount()];
        var maxFrame = rate / 25 * 3 * channels;
        this.floatFrame = route != null ? new float[maxFrame] : null;
        this.shortFrame = route != null ? new short[maxFrame] : null;
    }

    /**
     * Returns whether a routing table maps every channel to itself.
     *
     * @param route the input channel of each coded channel
     * @return {@code true} when no reordering is needed
     */
    private static boolean isIdentity(int[] route) {
        for (var i = 0; i < route.length; i++) {
            if (route[i] != i) {
                return false;
            }
        }
        return true;
    }

    /**
     * {@return the largest packet one per-frame encode call of a single-stream encoder writes for a
     * frame of at most 20 ms: 1276 bytes, one 1275-byte frame plus its table-of-contents byte}
     *
     * <p>A longer frame takes at most one byte more than this per 20 ms. A multistream packet holds
     * one such packet per {@linkplain #streamCount() stream} plus at most two bytes of length for
     * each stream but the last.
     */
    public static int maxPacketBytes() {
        return MAX_FRAME_PACKET_BYTES;
    }

    /**
     * {@return the options the encoder was created with}
     *
     * <p>The setters change the live controls, not these options.
     */
    public OpusEncoderOptions options() {
        return options;
    }

    /**
     * {@return the number of elementary streams each packet carries: 1 for mapping family 0,
     * otherwise the stream count of the identification header}
     */
    public int streamCount() {
        return encoder != null ? 1 : multistream.streamCount();
    }

    @Override
    public Codec codec() {
        return Codec.OPUS;
    }

    @Override
    public int sampleRate() {
        return options.sampleRate();
    }

    @Override
    public ChannelLayout layout() {
        return layout;
    }

    /**
     * {@inheritDoc}
     *
     * @return the number of samples per channel in one packet of the
     *         {@linkplain OpusEncoderOptions#frameDurationMicros() configured duration}
     */
    @Override
    public int frameSize() {
        return frameSize;
    }

    /**
     * {@inheritDoc}
     *
     * <p>The delay is the encoder look-ahead: 6.5 ms (2.5 ms plus 4 ms of delay compensation), or
     * 2.5 ms for {@link OpusApplication#RESTRICTED_LOWDELAY}.
     */
    @Override
    public int delay() {
        return delay;
    }

    /**
     * {@inheritDoc}
     *
     * @return the {@code OpusHead} identification header, a fresh copy
     */
    @Override
    public Optional<byte[]> codecConfig() {
        return Optional.of(head.clone());
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalStateException if the encoder was flushed
     */
    @Override
    public void encode(AudioBuffer input, Consumer<Packet> output) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(output, "output");
        if (flushed) {
            throw new IllegalStateException("encoder already flushed");
        }
        if (input.sampleRate() != options.sampleRate() || !input.layout().equals(layout)) {
            throw new IllegalArgumentException("input is " + input.sampleRate() + " Hz " + input.layout()
                    + ", encoder expects " + options.sampleRate() + " Hz " + layout);
        }
        var frames = input.frames();
        var read = 0;
        while (read < frames) {
            var count = Math.min(frames - read, frameSize - fifoFrames);
            for (var c = 0; c < channels; c++) {
                var plane = input.plane(route != null ? route[c] : c);
                var base = fifoFrames * channels + c;
                for (var i = 0; i < count; i++) {
                    fifo[base + i * channels] = clip(plane[read + i]);
                }
            }
            fifoFrames += count;
            read += count;
            inputSamples += count;
            if (fifoFrames == frameSize) {
                emit(output);
            }
        }
    }

    /**
     * Clips a sample to {@code [-1, 1]}, mapping NaN to zero.
     *
     * @param sample the float sample to clip
     * @return the clipped sample
     */
    private static float clip(float sample) {
        if (sample > 1.0f) {
            return 1.0f;
        }
        if (sample < -1.0f) {
            return -1.0f;
        }
        return sample == sample ? sample : 0.0f;
    }

    /**
     * Encodes the full FIFO into one packet and hands it to {@code output}.
     *
     * @param output receives the packet
     */
    private void emit(Consumer<Packet> output) {
        var length = encoder != null ? encoder.encodeFloat(fifo, 0, frameSize, packetBuffer, 0, packetBuffer.length)
                : encodeMultistream(fifo, 0, frameSize, packetBuffer, 0, packetBuffer.length);
        if (length < 0) {
            throw new IllegalStateException("Opus encoding failed: " + OpusDefines.strerror(length));
        }
        var packet = new Packet(0, Arrays.copyOf(packetBuffer, length), codedSamples, codedSamples, frameSize, true);
        codedSamples += frameSize;
        fifoFrames = 0;
        output.accept(packet);
    }

    /**
     * Encodes one frame of float input with the multistream encoder, whose streams may be coded in
     * parallel.
     *
     * @param pcm the interleaved input in coded channel order
     * @param offset the index of the first sample
     * @param frames the number of samples per channel
     * @param packet receives the packet
     * @param packetOffset the index the packet is written at
     * @param maxPacketBytes the space available
     * @return the packet length, or a negative {@code OPUS_*} error code
     */
    private int encodeMultistream(float[] pcm, int offset, int frames, byte[] packet, int packetOffset,
                                  int maxPacketBytes) {
        try (var _ = ParallelBands.operation(operation)) {
            return multistream.encodeFloat(pcm, offset, frames, packet, packetOffset, maxPacketBytes);
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Encodes silence-padded packets until every input sample lies before the end of the coded
     * stream minus the look-ahead, so a decoder reproduces the whole input once it drops the
     * pre-skip. An encoder that received no input emits nothing. Later calls do nothing.
     */
    @Override
    public void flush(Consumer<Packet> output) {
        Objects.requireNonNull(output, "output");
        if (flushed) {
            return;
        }
        flushed = true;
        if (inputSamples == 0) {
            return;
        }
        var target = inputSamples + delay;
        while (codedSamples < target) {
            Arrays.fill(fifo, fifoFrames * channels, fifo.length, 0);
            emit(output);
        }
    }

    /**
     * Encodes one frame of interleaved 16-bit samples into one packet.
     *
     * <p>The effective input depth is at most 16 bits whatever {@link #setLsbDepth(int)} says. A
     * frame the encoder judges silent while discontinuous transmission is on yields a packet of
     * one or two bytes per stream, which a decoder treats as a concealment request. The frame
     * counts as coded input, so packets the buffering methods emit afterwards are timestamped after
     * it.
     *
     * @param pcm the input, interleaved in the order of {@link #layout()}
     * @param offset the index of the first sample of the frame
     * @param frameSize the number of samples per channel: 2.5, 5, 10, 20, 40, 60, 80, 100 or 120 ms
     * at the input rate
     * @param packet receives the packet
     * @param packetOffset the index the packet is written at
     * @param maxPacketBytes the space available at {@code packetOffset}, which the packet never
     * exceeds; {@link #maxPacketBytes()} states how much a packet may need
     * @return the packet length in bytes, at least 1
     * @throws IllegalArgumentException if {@code frameSize} is not a valid Opus duration or
     * {@code maxPacketBytes} is too small for the frame
     * @throws IllegalStateException if buffered input waits for a full packet, the encoder was
     * flushed, or the encoder fails internally
     * @throws IndexOutOfBoundsException if the frame or the packet space lies outside its array
     */
    public int encode(short[] pcm, int offset, int frameSize, byte[] packet, int packetOffset, int maxPacketBytes) {
        Objects.requireNonNull(pcm, "pcm");
        checkFrame(pcm.length, offset, frameSize, packet, packetOffset, maxPacketBytes);
        if (encoder != null) {
            return coded(encoder.encode(pcm, offset, frameSize, packet, packetOffset, maxPacketBytes), frameSize,
                    maxPacketBytes);
        }
        var input = pcm;
        var start = offset;
        if (route != null) {
            for (var i = 0; i < frameSize; i++) {
                for (var c = 0; c < channels; c++) {
                    shortFrame[i * channels + c] = pcm[offset + i * channels + route[c]];
                }
            }
            input = shortFrame;
            start = 0;
        }
        int result;
        try (var _ = ParallelBands.operation(operation)) {
            result = multistream.encode(input, start, frameSize, packet, packetOffset, maxPacketBytes);
        }
        return coded(result, frameSize, maxPacketBytes);
    }

    /**
     * Encodes one frame of interleaved float samples, nominally in {@code [-1, 1]}, into one
     * packet.
     *
     * <p>Samples outside {@code [-1, 1]} are coded as they are, not clipped; a frame holding a
     * NaN, an infinity or an absurdly large value is coded as silence. The frame counts as coded
     * input, so packets the buffering methods emit afterwards are timestamped after it.
     *
     * @param pcm the input, interleaved in the order of {@link #layout()}
     * @param offset the index of the first sample of the frame
     * @param frameSize the number of samples per channel: 2.5, 5, 10, 20, 40, 60, 80, 100 or 120 ms
     * at the input rate
     * @param packet receives the packet
     * @param packetOffset the index the packet is written at
     * @param maxPacketBytes the space available at {@code packetOffset}, which the packet never
     * exceeds; {@link #maxPacketBytes()} states how much a packet may need
     * @return the packet length in bytes, at least 1
     * @throws IllegalArgumentException if {@code frameSize} is not a valid Opus duration or
     * {@code maxPacketBytes} is too small for the frame
     * @throws IllegalStateException if buffered input waits for a full packet, the encoder was
     * flushed, or the encoder fails internally
     * @throws IndexOutOfBoundsException if the frame or the packet space lies outside its array
     */
    public int encode(float[] pcm, int offset, int frameSize, byte[] packet, int packetOffset, int maxPacketBytes) {
        Objects.requireNonNull(pcm, "pcm");
        checkFrame(pcm.length, offset, frameSize, packet, packetOffset, maxPacketBytes);
        if (encoder != null) {
            return coded(encoder.encodeFloat(pcm, offset, frameSize, packet, packetOffset, maxPacketBytes), frameSize,
                    maxPacketBytes);
        }
        var input = pcm;
        var start = offset;
        if (route != null) {
            for (var i = 0; i < frameSize; i++) {
                for (var c = 0; c < channels; c++) {
                    floatFrame[i * channels + c] = pcm[offset + i * channels + route[c]];
                }
            }
            input = floatFrame;
            start = 0;
        }
        return coded(encodeMultistream(input, start, frameSize, packet, packetOffset, maxPacketBytes), frameSize,
                maxPacketBytes);
    }

    /**
     * Checks the state and the bounds of one per-frame encode call before the encoder reads or
     * writes anything.
     *
     * @param pcmLength the length of the input array
     * @param offset the index of the first input sample
     * @param frameSize the number of samples per channel
     * @param packet the output array
     * @param packetOffset the index of the first output byte
     * @param maxPacketBytes the space available
     * @throws NullPointerException if {@code packet} is {@code null}
     * @throws IllegalArgumentException if {@code frameSize} is not a valid Opus duration or
     * {@code maxPacketBytes} is not positive
     * @throws IllegalStateException if buffered input is pending or the encoder was flushed
     * @throws IndexOutOfBoundsException if the frame or the packet space lies outside its array
     */
    private void checkFrame(int pcmLength, int offset, int frameSize, byte[] packet, int packetOffset, int maxPacketBytes) {
        Objects.requireNonNull(packet, "packet");
        if (flushed) {
            throw new IllegalStateException("encoder already flushed");
        }
        if (fifoFrames != 0) {
            throw new IllegalStateException(fifoFrames + " buffered samples wait for a full packet");
        }
        if (!isFrameSize(options.sampleRate(), frameSize)) {
            throw new IllegalArgumentException("invalid Opus frame size " + frameSize + " at " + options.sampleRate() + " Hz");
        }
        if (maxPacketBytes <= 0) {
            throw new IllegalArgumentException("invalid packet capacity " + maxPacketBytes);
        }
        Objects.checkFromIndexSize(offset, frameSize * channels, pcmLength);
        Objects.checkFromIndexSize(packetOffset, maxPacketBytes, packet.length);
    }

    /**
     * Returns whether a frame size is one of the Opus durations at a sampling rate.
     *
     * @param sampleRate the sampling rate in hertz
     * @param frameSize the number of samples per channel
     * @return {@code true} for 2.5, 5, 10, 20, 40, 60, 80, 100 or 120 ms
     */
    private static boolean isFrameSize(int sampleRate, int frameSize) {
        if (frameSize <= 0 || frameSize > sampleRate / 25 * 3) {
            return false;
        }
        var step = sampleRate / 400;
        if (frameSize % step != 0) {
            return false;
        }
        var units = frameSize / step;
        return units == 1 || units == 2 || units == 4 || units == 8 || units % 8 == 0;
    }

    /**
     * Turns the result of one per-frame encode call into a packet length or an exception, and
     * counts the frame as coded input.
     *
     * @param result the packet length or a negative {@code OPUS_*} error code
     * @param frameSize the frame size the call used
     * @param maxPacketBytes the packet capacity the call used, for the message
     * @return {@code result} when it is a length
     * @throws IllegalArgumentException if the frame size or the capacity was rejected
     * @throws IllegalStateException if the encoder failed internally
     */
    private int coded(int result, int frameSize, int maxPacketBytes) {
        if (result > 0) {
            inputSamples += frameSize;
            codedSamples += frameSize;
            return result;
        }
        if (result == OpusDefines.OPUS_BAD_ARG) {
            throw new IllegalArgumentException("invalid Opus frame size " + frameSize + " at " + options.sampleRate() + " Hz");
        }
        if (result == OpusDefines.OPUS_BUFFER_TOO_SMALL) {
            throw new IllegalArgumentException("packet capacity of " + maxPacketBytes + " bytes is too small");
        }
        throw new IllegalStateException("Opus encoding failed: " + OpusDefines.strerror(result));
    }

    /**
     * Sets an explicit target bitrate, the total of all streams.
     *
     * <p>The value is clamped to {@code [500, 300000 * channels]}; a multistream encoder raises it to
     * at least 500 per channel.
     *
     * @param bitsPerSecond the bitrate in bits per second
     * @throws IllegalArgumentException if the value is not positive
     */
    public void setBitrate(int bitsPerSecond) {
        if (bitsPerSecond <= 0) {
            throw new IllegalArgumentException("invalid bitrate " + bitsPerSecond);
        }
        if (encoder != null) {
            encoder.setBitrate(bitsPerSecond);
        } else {
            multistream.setBitrate(bitsPerSecond);
        }
    }

    /**
     * Selects the bitrate the encoder derives from the sampling rate, the channel count and the
     * frame duration.
     */
    public void setAutomaticBitrate() {
        if (encoder != null) {
            encoder.setBitrate(OpusDefines.OPUS_AUTO);
        } else {
            multistream.setBitrate(OpusDefines.OPUS_AUTO);
        }
    }

    /**
     * Selects the largest bitrate the packet size limit allows.
     */
    public void setMaximumBitrate() {
        if (encoder != null) {
            encoder.setBitrate(OpusDefines.OPUS_BITRATE_MAX);
        } else {
            multistream.setBitrate(OpusDefines.OPUS_BITRATE_MAX);
        }
    }

    /**
     * Returns the bitrate the encoder targets.
     *
     * <p>A single-stream encoder returns an explicit bitrate after clamping; the automatic and
     * maximum bitrates resolve to the rate they stand for at the duration of the previous packet,
     * or of a 2.5 ms packet before the first one. A multistream encoder returns the sum of the
     * bitrates it gave its streams for the previous packet, or of their automatic bitrates before
     * the first one.
     *
     * @return the bitrate in bits per second
     */
    public int bitrate() {
        return encoder != null ? encoder.bitrate() : multistream.bitrate();
    }

    /**
     * Sets the bitrate control.
     *
     * @param bitrateMode the bitrate mode
     */
    public void setBitrateMode(OpusBitrateMode bitrateMode) {
        applyBitrateMode(Objects.requireNonNull(bitrateMode, "bitrateMode"));
    }

    /**
     * Applies a bitrate mode to the stream encoders.
     *
     * <p>{@link OpusBitrateMode#CBR} also clears the constraint flag, which has no effect while the
     * bitrate is constant; switching back to a variable mode sets it explicitly.
     *
     * @param bitrateMode the bitrate mode
     */
    private void applyBitrateMode(OpusBitrateMode bitrateMode) {
        var vbr = bitrateMode != OpusBitrateMode.CBR;
        var constrained = bitrateMode == OpusBitrateMode.CONSTRAINED_VBR;
        if (encoder != null) {
            encoder.setVbr(vbr);
            encoder.setVbrConstraint(constrained);
        } else {
            multistream.setVbr(vbr);
            multistream.setVbrConstraint(constrained);
        }
    }

    /**
     * Sets the computational complexity, which trades encoding speed for quality at the same bitrate.
     *
     * @param complexity the complexity, 0 (fastest) to 10 (best)
     * @throws IllegalArgumentException if the value is out of range
     */
    public void setComplexity(int complexity) {
        if (complexity < 0 || complexity > 10) {
            throw new IllegalArgumentException("invalid complexity " + complexity);
        }
        if (encoder != null) {
            encoder.setComplexity(complexity);
        } else {
            multistream.setComplexity(complexity);
        }
        this.complexity = complexity;
    }

    /**
     * {@return the computational complexity last set, from 0 to 10}
     */
    public int complexity() {
        return complexity;
    }

    /**
     * Sets whether packets carry a low-bitrate copy of the previous packet for in-band forward
     * error correction.
     *
     * <p>The copy is coded only in SILK and hybrid packets, and only while the expected loss set
     * by {@link #setPacketLossPercent(int)} is high enough for the bitrate.
     *
     * @param enabled {@code true} to code the copies
     */
    public void setInbandFec(boolean enabled) {
        if (encoder != null) {
            encoder.setInbandFec(enabled ? 1 : 0);
        } else {
            multistream.setInbandFec(enabled ? 1 : 0);
        }
    }

    /**
     * Sets the packet loss the encoder expects.
     *
     * @param percent the expected loss in percent, 0 to 100
     * @throws IllegalArgumentException if the value is out of range
     */
    public void setPacketLossPercent(int percent) {
        if (encoder != null) {
            encoder.setPacketLossPerc(percent);
        } else {
            multistream.setPacketLossPerc(percent);
        }
    }

    /**
     * Sets discontinuous transmission, which codes frames judged silent as one- or two-byte packets.
     *
     * @param enabled {@code true} to enable discontinuous transmission
     */
    public void setDtx(boolean enabled) {
        if (encoder != null) {
            encoder.setDtx(enabled);
        } else {
            multistream.setDtx(enabled);
        }
    }

    /**
     * Sets the content hint.
     *
     * @param signal the content hint
     */
    public void setSignal(OpusSignal signal) {
        var code = Objects.requireNonNull(signal, "signal").code();
        if (encoder != null) {
            encoder.setSignal(code);
        } else {
            multistream.setSignal(code);
        }
    }

    /**
     * Sets the widest audio bandwidth the encoder may choose.
     *
     * @param bandwidth the widest bandwidth; {@link OpusBandwidth#FULLBAND} lifts the restriction
     */
    public void setMaxBandwidth(OpusBandwidth bandwidth) {
        var code = Objects.requireNonNull(bandwidth, "bandwidth").code();
        if (encoder != null) {
            encoder.setMaxBandwidth(code);
        } else {
            multistream.setMaxBandwidth(code);
        }
    }

    /**
     * Forces the audio bandwidth of every packet.
     *
     * @param bandwidth the bandwidth every packet codes
     */
    public void setBandwidth(OpusBandwidth bandwidth) {
        var code = Objects.requireNonNull(bandwidth, "bandwidth").code();
        if (encoder != null) {
            encoder.setBandwidth(code);
        } else {
            multistream.setBandwidth(code);
        }
    }

    /**
     * Lets the encoder choose the audio bandwidth of every packet again, within the widest one
     * allowed.
     */
    public void setAutomaticBandwidth() {
        if (encoder != null) {
            encoder.setBandwidth(OpusDefines.OPUS_AUTO);
        } else {
            multistream.setBandwidth(OpusDefines.OPUS_AUTO);
        }
    }

    /**
     * Forces the number of coded channels of a single-stream encoder.
     *
     * @param channels 1, or 2 when the input is stereo
     * @throws IllegalArgumentException if the value is not 1 and not 2, or exceeds the input channel
     * count
     * @throws IllegalStateException if the encoder codes a multistream, whose streams choose their
     * coded channels themselves
     */
    public void setForcedChannels(int channels) {
        if (channels != 1 && channels != 2) {
            throw new IllegalArgumentException("invalid channel count " + channels);
        }
        if (encoder == null) {
            throw new IllegalStateException("a multistream encoder cannot force its coded channels");
        }
        encoder.setForceChannels(channels);
    }

    /**
     * Lets the encoder choose the number of coded channels per packet again; a multistream encoder
     * always does.
     */
    public void setAutomaticChannels() {
        if (encoder != null) {
            encoder.setForceChannels(OpusDefines.OPUS_AUTO);
        }
    }

    /**
     * Sets the effective bit depth of float input.
     *
     * <p>Lower depths let the encoder treat quieter signals as silence.
     *
     * @param bits the depth, 8 to 24
     * @throws IllegalArgumentException if the value is out of range
     */
    public void setLsbDepth(int bits) {
        if (encoder != null) {
            encoder.setLsbDepth(bits);
        } else {
            multistream.setLsbDepth(bits);
        }
    }

    /**
     * Sets whether every packet is coded without reference to earlier packets.
     *
     * @param disabled {@code true} to make every packet independently decodable, at a quality cost
     */
    public void setPredictionDisabled(boolean disabled) {
        if (encoder != null) {
            encoder.setPredictionDisabled(disabled);
        } else {
            multistream.setPredictionDisabled(disabled);
        }
    }

    /**
     * Sets whether the CELT layer may invert the phase of one channel of a stereo stream.
     *
     * @param disabled {@code true} to disable the inversion
     */
    public void setPhaseInversionDisabled(boolean disabled) {
        if (encoder != null) {
            encoder.setPhaseInversionDisabled(disabled);
        } else {
            multistream.setPhaseInversionDisabled(disabled);
        }
    }

    /**
     * Returns the final state of the range coder after the last packet, which a conformant decoder
     * reproduces; for a multistream, the exclusive or of the final states of its streams.
     *
     * @return the unsigned 32-bit final range
     */
    public int finalRange() {
        return encoder != null ? encoder.finalRange() : multistream.finalRange();
    }

    /**
     * Resets the coding state to that of a new encoder, keeping every setting.
     *
     * <p>The next packet is coded without reference to earlier input. Buffered input that has
     * not filled a packet yet is discarded; packet timestamps continue from the last packet.
     *
     * @throws IllegalStateException if the encoder was flushed
     */
    public void reset() {
        if (flushed) {
            throw new IllegalStateException("encoder already flushed");
        }
        if (encoder != null) {
            encoder.reset();
        } else {
            multistream.reset();
        }
        fifoFrames = 0;
        inputSamples = codedSamples;
    }
}
