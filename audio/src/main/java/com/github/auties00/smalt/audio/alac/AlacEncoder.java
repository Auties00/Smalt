/* Ports Apple Lossless Audio Codec (codec/ALACEncoder.cpp). Copyright (c) 2011 Apple Inc.; Apache License 2.0.
   Modified for Smalt. */
package com.github.auties00.smalt.audio.alac;

import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.audio.AudioEncoder;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Encodes audio into Apple Lossless (ALAC) packets, losslessly.
 *
 * <p>The encoder takes integer samples of the configured {@link AlacEncoderOptions#bitsPerSample()
 * sample size} through {@link #encode(int[][], int, int, Consumer)} and
 * {@link #encodeInterleaved(int[], int, int, Consumer)}, or planar floats through
 * {@link #encode(AudioBuffer, Consumer)}, which it quantizes: a float sample is clipped to
 * {@code [-1, 1]}, multiplied by {@code 2^(bitsPerSample - 1)}, rounded to the nearest integer with
 * ties toward positive infinity and clamped to the sample range, and NaN becomes zero. That is the
 * inverse of {@link AlacDecoder}'s scaling, so samples an ALAC decoder produced encode back to the
 * same integers. Input of any length is buffered into frames of {@link AlacEncoderOptions#frameLength()}
 * samples; each frame becomes one {@link Packet}, timestamped in samples from zero, with its duration
 * and the key-frame flag. {@link #flush(Consumer)} encodes the remaining samples as a final, shorter
 * (partial) frame without padding, so the decoded stream has exactly the input's length.
 *
 * <p>A frame holds one element per channel or channel pair, in ALAC's channel order for the channel
 * count (see {@link AlacEncoderOptions#layout()}): mono is one single-channel element, stereo one
 * channel pair, and three to eight channels a center single-channel element followed by pairs and
 * single channels. Each element adapts its stereo matrixing and linear predictor and Golomb codes
 * the residual, with the low bytes of 24- and 32-bit samples sent verbatim; an element that would
 * not shrink is sent uncompressed (escaped). The packets are byte-identical to those Apple's open-source
 * reference encoder writes for the same samples, frame length and fast setting.
 *
 * <p>{@link #codecConfig()} is the 24-byte {@code ALACSpecificConfig}. Before the stream ends it
 * declares the largest packet size and the average bitrate unknown (zero); after
 * {@link #flush(Consumer)} it carries both, with the same length, so a muxer that can rewrite its
 * header ({@link com.github.auties00.smalt.container.Muxer#updateCodecConfig(int, byte[])}) stores
 * the final form. {@link #magicCookie()} is the configuration as Apple's encoder vends it, with the
 * channel layout information for more than two channels.
 *
 * <p>Instances are not thread-safe.
 *
 * @implNote This implementation writes the channel layout tag of {@link #magicCookie()} big-endian, as
 * Apple's magic cookie description specifies, where the reference copies it in the host's byte
 * order; and it computes the average bitrate of the final configuration, which the reference leaves
 * zero.
 */
public final class AlacEncoder implements AudioEncoder {
    /**
     * The size of an {@code ALACSpecificConfig}.
     */
    private static final int CONFIG_SIZE = 24;

    /**
     * The size of the channel layout information of a magic cookie.
     */
    private static final int CHANNEL_LAYOUT_SIZE = 24;

    /**
     * The adaptation rate parameter of every stream, {@code PB0}.
     */
    private static final int PB = 40;

    /**
     * The initial mean parameter of every stream, {@code MB0}.
     */
    private static final int MB = 10;

    /**
     * The largest Golomb parameter of every stream, {@code KB0}.
     */
    private static final int KB = 14;

    /**
     * The longest zero run, {@code MAX_RUN_DEFAULT}.
     */
    private static final int MAX_RUN = 255;

    /**
     * The worst-case bytes per channel sample of an element written before its size is checked:
     * nine prefix bits and 21 raw bits for an escaped value, a 25-bit zero-run code, and two
     * verbatim bytes.
     */
    private static final int WORST_BYTES_PER_SAMPLE = 9;

    /**
     * Room for the element headers of a frame.
     */
    private static final int HEADER_ROOM = 1024;

    /**
     * The element tags of each channel count, one per bitstream channel (pairs take two
     * channels), as the reference's {@code sChannelMaps}.
     */
    private static final int[][] ELEMENTS = {
            {AlacElementEncoder.ID_SCE},
            {AlacElementEncoder.ID_CPE},
            {AlacElementEncoder.ID_SCE, AlacElementEncoder.ID_CPE},
            {AlacElementEncoder.ID_SCE, AlacElementEncoder.ID_CPE, AlacElementEncoder.ID_SCE},
            {AlacElementEncoder.ID_SCE, AlacElementEncoder.ID_CPE, AlacElementEncoder.ID_CPE},
            {AlacElementEncoder.ID_SCE, AlacElementEncoder.ID_CPE, AlacElementEncoder.ID_CPE,
                    AlacElementEncoder.ID_SCE},
            {AlacElementEncoder.ID_SCE, AlacElementEncoder.ID_CPE, AlacElementEncoder.ID_CPE,
                    AlacElementEncoder.ID_SCE, AlacElementEncoder.ID_SCE},
            {AlacElementEncoder.ID_SCE, AlacElementEncoder.ID_CPE, AlacElementEncoder.ID_CPE,
                    AlacElementEncoder.ID_CPE, AlacElementEncoder.ID_SCE}
    };

    /**
     * The Core Audio channel layout tag of each channel count ({@code ALACChannelLayoutTags}).
     */
    private static final int[] LAYOUT_TAGS = {
            100 << 16 | 1, 101 << 16 | 2, 113 << 16 | 3, 116 << 16 | 4,
            120 << 16 | 5, 124 << 16 | 6, 142 << 16 | 7, 127 << 16 | 8
    };

    /**
     * The configuration.
     */
    private final AlacEncoderOptions options;

    /**
     * The number of channels.
     */
    private final int channels;

    /**
     * The sample size.
     */
    private final int bitsPerSample;

    /**
     * The frame length.
     */
    private final int frameLength;

    /**
     * The smallest valid sample.
     */
    private final int minSample;

    /**
     * The largest valid sample.
     */
    private final int maxSample;

    /**
     * The bitstream channel each input channel is coded as.
     */
    private final int[] slotOf;

    /**
     * The samples of the frame being filled, one array per bitstream channel.
     */
    private final int[][] frame;

    /**
     * The element coder, which holds the state carried across frames.
     */
    private final AlacElementEncoder elements;

    /**
     * The packet being written.
     */
    private final AlacBitWriter writer;

    /**
     * The number of samples per channel waiting in {@link #frame}.
     */
    private int pending;

    /**
     * The number of samples per channel written in packets.
     */
    private long samplesWritten;

    /**
     * The number of bytes of packets written.
     */
    private long bytesWritten;

    /**
     * The largest packet written, in bytes.
     */
    private int maxFrameBytes;

    /**
     * Whether {@link #flush(Consumer)} ended the stream.
     */
    private boolean flushed;

    /**
     * Constructs an encoder for 44.1 kHz 16-bit stereo input, the settings of
     * {@link AlacEncoderOptions#defaults()}.
     */
    public AlacEncoder() {
        this(AlacEncoderOptions.defaults());
    }

    /**
     * Constructs an encoder.
     *
     * @param options the input format, sample size, frame length and speed
     */
    public AlacEncoder(AlacEncoderOptions options) {
        this.options = Objects.requireNonNull(options, "options");
        this.channels = options.layout().channels();
        this.bitsPerSample = options.bitsPerSample();
        this.frameLength = options.frameLength();
        this.minSample = bitsPerSample == 32 ? Integer.MIN_VALUE : -(1 << (bitsPerSample - 1));
        this.maxSample = bitsPerSample == 32 ? Integer.MAX_VALUE : (1 << (bitsPerSample - 1)) - 1;
        this.slotOf = slots(options.layout());
        this.frame = new int[channels][frameLength];
        this.elements = new AlacElementEncoder(bitsPerSample, frameLength, channels);
        this.writer = new AlacBitWriter(frameLength * channels * WORST_BYTES_PER_SAMPLE + HEADER_ROOM);
    }

    /**
     * Assigns each input channel its bitstream channel: channels whose speaker belongs to ALAC's
     * arrangement take its position, and the others take the free positions in input order.
     *
     * @param layout the input layout
     * @return the bitstream channel of each input channel
     */
    private static int[] slots(ChannelLayout layout) {
        var count = layout.channels();
        var speakers = AlacDecoder.BITSTREAM_ORDER[count - 1];
        var slotOf = new int[count];
        Arrays.fill(slotOf, -1);
        var taken = new boolean[count];
        for (var slot = 0; slot < count; slot++) {
            var input = layout.indexOf(speakers[slot]);
            if (input >= 0 && slotOf[input] < 0) {
                slotOf[input] = slot;
                taken[slot] = true;
            }
        }
        var free = 0;
        for (var input = 0; input < count; input++) {
            if (slotOf[input] < 0) {
                while (taken[free]) {
                    free++;
                }
                slotOf[input] = free;
                taken[free] = true;
            }
        }
        return slotOf;
    }

    /**
     * {@return the configuration of this encoder}
     */
    public AlacEncoderOptions options() {
        return options;
    }

    @Override
    public Codec codec() {
        return Codec.ALAC;
    }

    @Override
    public int sampleRate() {
        return options.sampleRate();
    }

    @Override
    public ChannelLayout layout() {
        return options.layout();
    }

    /**
     * Returns the frame length, the samples per channel of every packet but the last.
     *
     * @return the frame length
     */
    @Override
    public int frameSize() {
        return frameLength;
    }

    /**
     * Returns {@code 0}: ALAC has no codec delay.
     *
     * @return {@code 0}
     */
    @Override
    public int delay() {
        return 0;
    }

    /**
     * Returns the 24-byte {@code ALACSpecificConfig} of the stream.
     *
     * <p>Before {@link #flush(Consumer)} its largest packet size and average bitrate are zero
     * (unknown); afterwards they are those of the packets written, the average rounded to the
     * nearest bit per second.
     *
     * @return the configuration
     */
    @Override
    public Optional<byte[]> codecConfig() {
        return Optional.of(config());
    }

    /**
     * Returns the magic cookie of the stream: {@link #codecConfig()}, followed for more than two
     * channels by the 24-byte channel layout information ({@code chan}) naming ALAC's layout for the
     * channel count.
     *
     * @return the magic cookie, 24 or 48 bytes
     */
    public byte[] magicCookie() {
        var config = config();
        if (channels <= 2) {
            return config;
        }
        var cookie = Arrays.copyOf(config, CONFIG_SIZE + CHANNEL_LAYOUT_SIZE);
        putInt(cookie, CONFIG_SIZE, CHANNEL_LAYOUT_SIZE);
        putInt(cookie, CONFIG_SIZE + 4, 'c' << 24 | 'h' << 16 | 'a' << 8 | 'n');
        putInt(cookie, CONFIG_SIZE + 12, LAYOUT_TAGS[channels - 1]);
        return cookie;
    }

    /**
     * {@return the number of samples per channel encoded into packets so far, excluding samples still
     * buffered}
     */
    public long samplesEncoded() {
        return samplesWritten;
    }

    /**
     * Quantizes planar float samples to the sample size and buffers them, emitting a packet for
     * every frame completed.
     *
     * @param input the samples, of any length
     * @param output receives zero or more packets
     * @throws IllegalArgumentException if the input rate or layout differs from the encoder's
     * @throws IllegalStateException if the encoder was flushed
     */
    @Override
    public void encode(AudioBuffer input, Consumer<Packet> output) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(output, "output");
        checkOpen();
        if (input.sampleRate() != options.sampleRate() || !input.layout().equals(options.layout())) {
            throw new IllegalArgumentException("input " + input.sampleRate() + " Hz " + input.layout()
                    + " differs from the encoder's " + options.sampleRate() + " Hz " + options.layout());
        }
        var planes = input.planes();
        var total = input.frames();
        var scale = Math.scalb(1.0, bitsPerSample - 1);
        var consumed = 0;
        while (consumed < total) {
            var count = Math.min(total - consumed, frameLength - pending);
            for (var c = 0; c < channels; c++) {
                var plane = planes[c];
                var target = frame[slotOf[c]];
                for (var i = 0; i < count; i++) {
                    var value = Math.round(plane[consumed + i] * scale);
                    target[pending + i] = (int) Math.max(minSample, Math.min(maxSample, value));
                }
            }
            advance(count, output);
            consumed += count;
        }
    }

    /**
     * Buffers planar integer samples, emitting a packet for every frame completed.
     *
     * @param samples one array per input channel
     * @param offset the index of the first sample in every array
     * @param count the number of samples per channel
     * @param output receives zero or more packets
     * @throws NullPointerException if a channel array is {@code null}
     * @throws IllegalArgumentException if the channel count differs from the layout's or a sample
     * is outside the sample size's range
     * @throws IndexOutOfBoundsException if the range exceeds a channel array
     * @throws IllegalStateException if the encoder was flushed
     */
    public void encode(int[][] samples, int offset, int count, Consumer<Packet> output) {
        Objects.requireNonNull(samples, "samples");
        Objects.requireNonNull(output, "output");
        checkOpen();
        if (samples.length != channels) {
            throw new IllegalArgumentException(samples.length + " channel arrays for " + channels + " channels");
        }
        for (var channel : samples) {
            Objects.checkFromIndexSize(offset, count, Objects.requireNonNull(channel, "channel").length);
            checkRange(channel, offset, count);
        }
        var consumed = 0;
        while (consumed < count) {
            var chunk = Math.min(count - consumed, frameLength - pending);
            for (var c = 0; c < channels; c++) {
                System.arraycopy(samples[c], offset + consumed, frame[slotOf[c]], pending, chunk);
            }
            advance(chunk, output);
            consumed += chunk;
        }
    }

    /**
     * Buffers interleaved integer samples, emitting a packet for every frame completed.
     *
     * @param samples the samples, input channel by input channel within each sample frame
     * @param offset the index of the first sample
     * @param count the number of sample frames
     * @param output receives zero or more packets
     * @throws IllegalArgumentException if a sample is outside the sample size's range
     * @throws IndexOutOfBoundsException if the range exceeds the array
     * @throws IllegalStateException if the encoder was flushed
     */
    public void encodeInterleaved(int[] samples, int offset, int count, Consumer<Packet> output) {
        Objects.requireNonNull(samples, "samples");
        Objects.requireNonNull(output, "output");
        checkOpen();
        Objects.checkFromIndexSize(offset, Math.multiplyExact(count, channels), samples.length);
        checkRange(samples, offset, count * channels);
        var consumed = 0;
        while (consumed < count) {
            var chunk = Math.min(count - consumed, frameLength - pending);
            for (var c = 0; c < channels; c++) {
                var target = frame[slotOf[c]];
                var source = offset + consumed * channels + c;
                for (var i = 0; i < chunk; i++, source += channels) {
                    target[pending + i] = samples[source];
                }
            }
            advance(chunk, output);
            consumed += chunk;
        }
    }

    /**
     * Encodes the buffered samples as a final, partial frame without padding and completes the codec
     * configuration; later calls do nothing.
     *
     * @param output receives zero or one packet
     */
    @Override
    public void flush(Consumer<Packet> output) {
        Objects.requireNonNull(output, "output");
        if (flushed) {
            return;
        }
        if (pending > 0) {
            emit(pending, output);
        }
        flushed = true;
    }

    /**
     * Rejects input after {@link #flush(Consumer)}.
     *
     * @throws IllegalStateException if the encoder was flushed
     */
    private void checkOpen() {
        if (flushed) {
            throw new IllegalStateException("the encoder was flushed");
        }
    }

    /**
     * Checks that samples fit the sample size.
     *
     * @param samples the integer samples to check
     * @param offset the index of the first sample
     * @param count the number of samples
     * @throws IllegalArgumentException if a sample is out of range
     */
    private void checkRange(int[] samples, int offset, int count) {
        if (bitsPerSample == 32) {
            return;
        }
        var min = 0;
        var max = 0;
        for (var i = 0; i < count; i++) {
            var value = samples[offset + i];
            min = Math.min(min, value);
            max = Math.max(max, value);
        }
        if (min < minSample || max > maxSample) {
            throw new IllegalArgumentException("sample " + (min < minSample ? min : max) + " does not fit "
                    + bitsPerSample + " bits");
        }
    }

    /**
     * Accounts for samples copied into the frame and encodes it once full.
     *
     * @param count the number of samples per channel added
     * @param output the packet consumer
     */
    private void advance(int count, Consumer<Packet> output) {
        pending += count;
        if (pending == frameLength) {
            emit(frameLength, output);
        }
    }

    /**
     * Encodes the buffered frame and emits its packet ({@code ALACEncoder::Encode}).
     *
     * @param size the number of samples per channel
     * @param output the packet consumer
     */
    private void emit(int size, Consumer<Packet> output) {
        var out = writer;
        out.reset();
        if (channels == 2) {
            out.write(AlacElementEncoder.ID_CPE, 3);
            out.write(0, 4);
            if (options.fast()) {
                elements.encodeStereoFast(out, frame[0], frame[1], 0, size);
            } else {
                elements.encodeStereo(out, frame[0], frame[1], 0, size);
            }
        } else {
            var monoTag = 0;
            var pairTag = 0;
            var channel = 0;
            for (var tag : ELEMENTS[channels - 1]) {
                out.write(tag, 3);
                if (tag == AlacElementEncoder.ID_CPE) {
                    out.write(pairTag++, 4);
                    elements.encodeStereo(out, frame[channel], frame[channel + 1], channel, size);
                    channel += 2;
                } else {
                    out.write(monoTag++, 4);
                    elements.encodeMono(out, frame[channel], channel, size);
                    channel++;
                }
            }
        }
        out.write(AlacElementEncoder.ID_END, 3);
        out.alignToByte();
        var packet = out.toByteArray();
        bytesWritten += packet.length;
        maxFrameBytes = Math.max(maxFrameBytes, packet.length);
        var pts = samplesWritten;
        samplesWritten += size;
        pending = 0;
        output.accept(new Packet(0, packet, pts, pts, size, true));
    }

    /**
     * Builds the {@code ALACSpecificConfig}, final once the stream ended.
     *
     * @return the 24 configuration bytes
     */
    private byte[] config() {
        var config = new byte[CONFIG_SIZE];
        putInt(config, 0, frameLength);
        config[5] = (byte) bitsPerSample;
        config[6] = PB;
        config[7] = MB;
        config[8] = KB;
        config[9] = (byte) channels;
        config[10] = (byte) (MAX_RUN >> 8);
        config[11] = (byte) MAX_RUN;
        if (flushed) {
            putInt(config, 12, maxFrameBytes);
            var average = samplesWritten == 0 ? 0
                    : Math.min(0xFFFF_FFFFL, Math.round(bytesWritten * 8.0 * options.sampleRate() / samplesWritten));
            putInt(config, 16, (int) average);
        }
        putInt(config, 20, options.sampleRate());
        return config;
    }

    /**
     * Writes a big-endian 32-bit value.
     *
     * @param target the destination
     * @param position the index of the first byte
     * @param value the 32 bits to write
     */
    private static void putInt(byte[] target, int position, int value) {
        target[position] = (byte) (value >>> 24);
        target[position + 1] = (byte) (value >>> 16);
        target[position + 2] = (byte) (value >>> 8);
        target[position + 3] = (byte) value;
    }
}
