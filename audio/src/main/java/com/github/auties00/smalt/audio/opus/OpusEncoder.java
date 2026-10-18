package com.github.auties00.smalt.audio.opus;

import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.audio.AudioEncoder;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.opus.internal.OpusDefines;
import com.github.auties00.smalt.audio.opus.internal.OpusStreamEncoder;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Encodes mono or stereo audio into Opus packets (RFC 6716), either buffered into packets of one
 * duration for a container or one packet per call for real-time transmission.
 *
 * <p>The encoder offers two ways to feed it, which share one coding state:
 * <ul>
 * <li>{@link #encode(AudioBuffer, Consumer)} buffers planar float input of any length and codes
 * one packet per {@link #frameSize()} samples (the
 * {@linkplain OpusEncoderOptions#frameDurationMicros() packet duration} of the options); each
 * packet is emitted with {@code keyframe} set, its timestamp in samples of the input rate and its
 * duration. Samples are clipped to {@code [-1, 1]} and NaN is coded as silence.
 * {@link #flush(Consumer)} pads the stream with silence until every input sample has been coded
 * past the encoder look-ahead, so the decoded stream is longer than the input: callers trim it
 * with the container's end position (the Ogg end granule position, pre-skip plus input length at
 * 48 kHz).</li>
 * <li>{@link #encode(short[], int, int, byte[], int, int)} and
 * {@link #encode(float[], int, int, byte[], int, int)} code exactly one frame of 2.5, 5, 10, 20,
 * 40, 60, 80, 100 or 120 ms of interleaved 16-bit or float samples into one packet in a
 * caller-owned buffer, without buffering, padding or timestamps: the caller decides the duration
 * of every frame. They fail while buffered input waits for a full packet.</li>
 * </ul>
 *
 * <p>The setters ({@link #setBitrate(int)}, {@link #setBitrateMode(OpusBitrateMode)},
 * {@link #setComplexity(int)}, {@link #setInbandFec(boolean)},
 * {@link #setPacketLossPercent(int)}, {@link #setDtx(boolean)} and the others) take effect from
 * the next packet, so a rate controller can retarget the encoder while the stream runs; they do
 * not change {@link #options()}, which keeps the settings the encoder was created with.
 *
 * <p>The {@link #codecConfig()} is an {@code OpusHead} (version 1, the channel count, a pre-skip
 * equal to the encoder look-ahead at 48 kHz, the input rate, zero gain, mapping family 0). An
 * encoder with the same input and the same sequence of settings produces packets byte-identical
 * to those of the float build of libopus 1.5.2 without SIMD. Instances are not thread-safe; a
 * caller that encodes on one thread and retargets on another must serialize the calls.
 */
public final class OpusEncoder implements AudioEncoder {
    /**
     * The largest packet one per-frame encode call emits: one 1275-byte frame plus its
     * table-of-contents byte.
     */
    private static final int MAX_FRAME_PACKET_BYTES = 1276;

    /**
     * The largest packet the buffering path can emit: six 20 ms frames of 1275 bytes plus framing.
     */
    private static final int MAX_PACKET = 1275 * 6 + 7;

    /**
     * The options the encoder was created with.
     */
    private final OpusEncoderOptions options;

    /**
     * The stream encoder doing the work.
     */
    private final OpusStreamEncoder encoder;

    /**
     * The input channel layout.
     */
    private final ChannelLayout layout;

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
     * The interleaved samples of the buffered packet being filled.
     */
    private final float[] fifo;

    /**
     * The number of samples per channel in {@link #fifo}.
     */
    private int fifoFrames;

    /**
     * The packet output buffer of the buffering path.
     */
    private final byte[] packetBuffer = new byte[MAX_PACKET];

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
        this.encoder = new OpusStreamEncoder(options.sampleRate(), options.channels(), options.application().code());
        if (options.bitrate().isPresent()) {
            encoder.setBitrate(options.bitrate().getAsInt());
        }
        applyBitrateMode(options.bitrateMode());
        encoder.setComplexity(options.complexity());
        encoder.setSignal(options.signal().code());
        if (options.maxBandwidth() != OpusBandwidth.FULLBAND) {
            encoder.setMaxBandwidth(options.maxBandwidth().code());
        }
        if (options.inbandFec()) {
            encoder.setInbandFec(1);
        }
        if (options.packetLossPercent() != 0) {
            encoder.setPacketLossPerc(options.packetLossPercent());
        }
        if (options.dtx()) {
            encoder.setDtx(true);
        }
        this.complexity = options.complexity();
        this.layout = options.channels() == 1 ? ChannelLayout.MONO : ChannelLayout.STEREO;
        this.frameSize = options.frameSize();
        this.delay = encoder.lookahead();
        this.head = OpusHead.family0(options.channels(), delay * 48000 / options.sampleRate(), options.sampleRate()).toBytes();
        this.fifo = new float[frameSize * options.channels()];
    }

    /**
     * {@return the largest packet one per-frame encode call writes: 1276 bytes, one 1275-byte frame
     * plus its table-of-contents byte}
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
        var channels = options.channels();
        var frames = input.frames();
        var read = 0;
        while (read < frames) {
            var count = Math.min(frames - read, frameSize - fifoFrames);
            for (var c = 0; c < channels; c++) {
                var plane = input.plane(c);
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
        var length = encoder.encodeFloat(fifo, 0, frameSize, packetBuffer, 0, MAX_PACKET);
        if (length < 0) {
            throw new IllegalStateException("Opus encoding failed: " + OpusDefines.strerror(length));
        }
        var packet = new Packet(0, Arrays.copyOf(packetBuffer, length), codedSamples, codedSamples, frameSize, true);
        codedSamples += frameSize;
        fifoFrames = 0;
        output.accept(packet);
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
            Arrays.fill(fifo, fifoFrames * options.channels(), fifo.length, 0);
            emit(output);
        }
    }

    /**
     * Encodes one frame of interleaved 16-bit samples into one packet.
     *
     * <p>The effective input depth is at most 16 bits whatever {@link #setLsbDepth(int)} says. A
     * frame the encoder judges silent while discontinuous transmission is on yields a packet of
     * one or two bytes, which a decoder treats as a concealment request. The frame counts as
     * coded input, so packets the buffering methods emit afterwards are timestamped after it.
     *
     * @param pcm the interleaved input
     * @param offset the index of the first sample of the frame
     * @param frameSize the number of samples per channel: 2.5, 5, 10, 20, 40, 60, 80, 100 or 120 ms
     * at the input rate
     * @param packet receives the packet
     * @param packetOffset the index the packet is written at
     * @param maxPacketBytes the space available at {@code packetOffset}; at most
     * {@link #maxPacketBytes()} bytes are used
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
        return coded(encoder.encode(pcm, offset, frameSize, packet, packetOffset, maxPacketBytes), frameSize, maxPacketBytes);
    }

    /**
     * Encodes one frame of interleaved float samples, nominally in {@code [-1, 1]}, into one
     * packet.
     *
     * <p>Samples outside {@code [-1, 1]} are coded as they are, not clipped; a frame holding a
     * NaN, an infinity or an absurdly large value is coded as silence. The frame counts as coded
     * input, so packets the buffering methods emit afterwards are timestamped after it.
     *
     * @param pcm the interleaved input
     * @param offset the index of the first sample of the frame
     * @param frameSize the number of samples per channel: 2.5, 5, 10, 20, 40, 60, 80, 100 or 120 ms
     * at the input rate
     * @param packet receives the packet
     * @param packetOffset the index the packet is written at
     * @param maxPacketBytes the space available at {@code packetOffset}; at most
     * {@link #maxPacketBytes()} bytes are used
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
        return coded(encoder.encodeFloat(pcm, offset, frameSize, packet, packetOffset, maxPacketBytes), frameSize, maxPacketBytes);
    }

    /**
     * Checks the state and the bounds of one per-frame encode call before the encoder reads or
     * writes anything.
     *
     * @param pcmLength      the length of the input array
     * @param offset         the index of the first input sample
     * @param frameSize      the number of samples per channel
     * @param packet         the output array
     * @param packetOffset   the index of the first output byte
     * @param maxPacketBytes the space available
     * @throws NullPointerException      if {@code packet} is {@code null}
     * @throws IllegalArgumentException  if {@code frameSize} is not a valid Opus duration or
     *                                   {@code maxPacketBytes} is not positive
     * @throws IllegalStateException     if buffered input is pending or the encoder was flushed
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
        Objects.checkFromIndexSize(offset, frameSize * options.channels(), pcmLength);
        Objects.checkFromIndexSize(packetOffset, maxPacketBytes, packet.length);
    }

    /**
     * Returns whether a frame size is one of the Opus durations at a sampling rate.
     *
     * @param sampleRate the sampling rate in hertz
     * @param frameSize  the number of samples per channel
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
     * @param result         the packet length or a negative {@code OPUS_*} error code
     * @param frameSize      the frame size the call used
     * @param maxPacketBytes the packet capacity the call used, for the message
     * @return {@code result} when it is a length
     * @throws IllegalArgumentException if the frame size or the capacity was rejected
     * @throws IllegalStateException    if the encoder failed internally
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
     * Sets an explicit target bitrate.
     *
     * <p>The value is clamped to {@code [500, 300000 * channels]}.
     *
     * @param bitsPerSecond the bitrate in bits per second
     * @throws IllegalArgumentException if the value is not positive
     */
    public void setBitrate(int bitsPerSecond) {
        if (bitsPerSecond <= 0) {
            throw new IllegalArgumentException("invalid bitrate " + bitsPerSecond);
        }
        encoder.setBitrate(bitsPerSecond);
    }

    /**
     * Selects the bitrate the encoder derives from the sampling rate, the channel count and the
     * frame duration.
     */
    public void setAutomaticBitrate() {
        encoder.setBitrate(OpusDefines.OPUS_AUTO);
    }

    /**
     * Selects the largest bitrate the packet size limit allows.
     */
    public void setMaximumBitrate() {
        encoder.setBitrate(OpusDefines.OPUS_BITRATE_MAX);
    }

    /**
     * Returns the bitrate the encoder targets.
     *
     * <p>An explicit bitrate is returned after clamping; the automatic and maximum bitrates
     * resolve to the rate they stand for at the duration of the previous packet, or of a 2.5 ms
     * packet before the first one.
     *
     * @return the bitrate in bits per second
     */
    public int bitrate() {
        return encoder.bitrate();
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
     * Applies a bitrate mode to the stream encoder.
     *
     * <p>{@link OpusBitrateMode#CBR} also clears the constraint flag, which has no effect while the
     * bitrate is constant; switching back to a variable mode sets it explicitly.
     *
     * @param bitrateMode the bitrate mode
     */
    private void applyBitrateMode(OpusBitrateMode bitrateMode) {
        encoder.setVbr(bitrateMode != OpusBitrateMode.CBR);
        encoder.setVbrConstraint(bitrateMode == OpusBitrateMode.CONSTRAINED_VBR);
    }

    /**
     * Sets the computational complexity, which trades encoding speed for quality at the same bitrate.
     *
     * @param complexity the complexity, 0 (fastest) to 10 (best)
     * @throws IllegalArgumentException if the value is out of range
     */
    public void setComplexity(int complexity) {
        encoder.setComplexity(complexity);
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
        encoder.setInbandFec(enabled ? 1 : 0);
    }

    /**
     * Sets the packet loss the encoder expects.
     *
     * @param percent the expected loss in percent, 0 to 100
     * @throws IllegalArgumentException if the value is out of range
     */
    public void setPacketLossPercent(int percent) {
        encoder.setPacketLossPerc(percent);
    }

    /**
     * Sets discontinuous transmission, which codes frames judged silent as one- or two-byte packets.
     *
     * @param enabled {@code true} to enable discontinuous transmission
     */
    public void setDtx(boolean enabled) {
        encoder.setDtx(enabled);
    }

    /**
     * Sets the content hint.
     *
     * @param signal the content hint
     */
    public void setSignal(OpusSignal signal) {
        encoder.setSignal(Objects.requireNonNull(signal, "signal").code());
    }

    /**
     * Sets the widest audio bandwidth the encoder may choose.
     *
     * @param bandwidth the widest bandwidth; {@link OpusBandwidth#FULLBAND} lifts the restriction
     */
    public void setMaxBandwidth(OpusBandwidth bandwidth) {
        encoder.setMaxBandwidth(Objects.requireNonNull(bandwidth, "bandwidth").code());
    }

    /**
     * Forces the audio bandwidth of every packet.
     *
     * @param bandwidth the bandwidth every packet codes
     */
    public void setBandwidth(OpusBandwidth bandwidth) {
        encoder.setBandwidth(Objects.requireNonNull(bandwidth, "bandwidth").code());
    }

    /**
     * Lets the encoder choose the audio bandwidth of every packet again, within the widest one
     * allowed.
     */
    public void setAutomaticBandwidth() {
        encoder.setBandwidth(OpusDefines.OPUS_AUTO);
    }

    /**
     * Forces the number of coded channels.
     *
     * @param channels 1, or 2 when the input is stereo
     * @throws IllegalArgumentException if the value is not 1 and not 2, or exceeds the input channel
     * count
     */
    public void setForcedChannels(int channels) {
        if (channels != 1 && channels != 2) {
            throw new IllegalArgumentException("invalid channel count " + channels);
        }
        encoder.setForceChannels(channels);
    }

    /**
     * Lets the encoder choose the number of coded channels per packet again
     *.
     */
    public void setAutomaticChannels() {
        encoder.setForceChannels(OpusDefines.OPUS_AUTO);
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
        encoder.setLsbDepth(bits);
    }

    /**
     * Sets whether every packet is coded without reference to earlier packets
     *.
     *
     * @param disabled {@code true} to make every packet independently decodable, at a quality cost
     */
    public void setPredictionDisabled(boolean disabled) {
        encoder.setPredictionDisabled(disabled);
    }

    /**
     * Sets whether the CELT layer may invert the phase of one stereo channel
     *.
     *
     * @param disabled {@code true} to disable the inversion
     */
    public void setPhaseInversionDisabled(boolean disabled) {
        encoder.setPhaseInversionDisabled(disabled);
    }

    /**
     * Returns the final state of the range coder after the last packet
     *, which a conformant decoder reproduces.
     *
     * @return the unsigned 32-bit final range
     */
    public int finalRange() {
        return encoder.finalRange();
    }

    /**
     * Resets the coding state to that of a new encoder, keeping every setting
     *.
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
        encoder.reset();
        fifoFrames = 0;
        inputSamples = codedSamples;
    }
}
