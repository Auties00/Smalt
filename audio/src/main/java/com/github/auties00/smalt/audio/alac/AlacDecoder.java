/* Ports Apple Lossless Audio Codec (codec/ALACDecoder.cpp, ag_dec.c, dp_dec.c, matrix_dec.c). Copyright (c) 2011
   Apple Inc.; Apache License 2.0. Modified for Smalt. */
package com.github.auties00.smalt.audio.alac;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.audio.AudioDecoder;
import com.github.auties00.smalt.audio.AudioDecoderOptions;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.DoubleAudioBuffer;
import com.github.auties00.smalt.audio.IntegerAudioBuffer;
import com.github.auties00.smalt.audio.Speaker;
import com.github.auties00.smalt.internal.check.Checks;
import com.github.auties00.smalt.internal.codec.WaveformConcealer;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Decodes Apple Lossless (ALAC) packets into planar float samples.
 *
 * <p>The track's codec configuration is the 24-byte {@code ALACSpecificConfig}, bare or inside the
 * magic cookie forms MP4 ({@code alac} box) and Matroska ({@code A_ALAC}) store; its frame length,
 * sample size, channel count, Rice tuning parameters and sample rate set up the decoder. Every
 * packet holds one frame: a sequence of single-channel, channel-pair and LFE elements, optionally
 * fill and data-stream elements, ended by an end element. The decoder implements the whole
 * bitstream of Apple's reference decoder: the adaptive Golomb residual coding with its zero runs
 * and escapes, the adaptive linear predictor of any order (and the first-order pre-filter mode),
 * the matrixed stereo of channel pairs, the shifted-off low bytes of wide samples, uncompressed
 * (escaped) elements and partial frames. Sample sizes from 4 to 32 bits and one to eight channels
 * are decoded; a channel pair of 32-bit samples that keeps every byte (33-bit side information)
 * raises {@link MediaException.Unsupported}.
 *
 * <p>Channels are reordered from ALAC's bitstream order (center first, Apple's
 * {@code kALACChannelLayoutTag_*} layouts) into the speaker order of {@link Speaker}'s
 * declaration, as WAVE uses: three channels are front left, right and center; four add a back
 * center; five are 5.0 with back surrounds; six are 5.1; seven are 6.1 with a back center; eight
 * are 7.1 with front left and right of center. Samples are scaled to floats by
 * {@code 2^-(bitsPerSample - 1)}, so full scale maps to {@code [-1, 1)}; samples of up to 24 bits
 * are represented exactly, and wider samples are rounded to the nearest float. The decoded integers
 * themselves, bit for bit at every sample size, are the blocks of
 * {@link #decodeIntegers(Packet, Consumer)}, and {@link #decodeDoubles(Packet, Consumer)} scales them
 * to doubles exactly.
 *
 * <p>A packet with a reserved element, a nonzero reserved header field, a frame longer than the
 * configuration's frame length or residuals that run past the packet raises
 * {@link MediaException.Malformed}. Channels a packet does not code are output as silence. An
 * exception thrown by the output consumer reaches the caller unchanged.
 *
 * <p>A lost span ({@link #conceal(int, Consumer)}) is produced exactly, its concealment unit being one sample: it
 * repeats the last pitch period of the decoded signal, from 10 ms on the last two and from 20 ms the last three,
 * fading linearly to silence between 10 and 60 ms, and the blocks that follow it are overlapped with the
 * repetition over 4 to 10 ms, as ITU-T G.711 Appendix I conceals G.711 frames. Concealment continues the float
 * output of {@link #decode(Packet, Consumer)} only: a decoder driven through its integer or double output keeps no
 * history, so it conceals with silence and does not overlap the blocks after the loss.
 *
 * <p>Instances are not thread-safe.
 *
 * @implNote This implementation follows Apple's reference decoder, including its unsigned 32-bit
 * mean tracking, its 16-bit predictor coefficients that wrap on adaptation and its handling of
 * partial frames. It applies the shifted-off low bytes at every sample size, where the reference's
 * 16- and 20-bit outputs drop them (its encoder never shifts those sizes), and decodes sample sizes
 * other than 16, 20, 24 and 32, which the reference leaves unwritten.
 */
public final class AlacDecoder implements AudioDecoder {
    /**
     * The single channel element tag.
     */
    private static final int ID_SCE = 0;

    /**
     * The channel pair element tag.
     */
    private static final int ID_CPE = 1;

    /**
     * The coupling channel element tag.
     */
    private static final int ID_CCE = 2;

    /**
     * The low-frequency effects element tag.
     */
    private static final int ID_LFE = 3;

    /**
     * The data stream element tag.
     */
    private static final int ID_DSE = 4;

    /**
     * The program configuration element tag.
     */
    private static final int ID_PCE = 5;

    /**
     * The fill element tag.
     */
    private static final int ID_FIL = 6;

    /**
     * The size of an {@code ALACSpecificConfig}.
     */
    private static final int CONFIG_SIZE = 24;

    /**
     * The largest channel count of an ALAC stream.
     */
    private static final int MAX_CHANNELS = 8;

    /**
     * The largest predictor order an element codes.
     */
    private static final int MAX_COEFFICIENTS = 32;

    /**
     * The speakers of each channel count in ALAC's bitstream order, after Apple's
     * {@code ALACMagicCookieDescription.txt}; {@link AlacEncoder} codes the same arrangements.
     */
    static final Speaker[][] BITSTREAM_ORDER = {
            {Speaker.FRONT_CENTER},
            {Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT},
            {Speaker.FRONT_CENTER, Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT},
            {Speaker.FRONT_CENTER, Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT, Speaker.BACK_CENTER},
            {Speaker.FRONT_CENTER, Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT, Speaker.BACK_LEFT, Speaker.BACK_RIGHT},
            {Speaker.FRONT_CENTER, Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT, Speaker.BACK_LEFT, Speaker.BACK_RIGHT,
                    Speaker.LOW_FREQUENCY},
            {Speaker.FRONT_CENTER, Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT, Speaker.BACK_LEFT, Speaker.BACK_RIGHT,
                    Speaker.BACK_CENTER, Speaker.LOW_FREQUENCY},
            {Speaker.FRONT_CENTER, Speaker.FRONT_LEFT_OF_CENTER, Speaker.FRONT_RIGHT_OF_CENTER, Speaker.FRONT_LEFT,
                    Speaker.FRONT_RIGHT, Speaker.BACK_LEFT, Speaker.BACK_RIGHT, Speaker.LOW_FREQUENCY}
    };

    /**
     * The resource bounds the decoder enforces.
     */
    private final MediaLimits limits;

    /**
     * The number of samples per channel of a full frame.
     */
    private final int frameLength;

    /**
     * The sample size.
     */
    private final int bitDepth;

    /**
     * The number of channels.
     */
    private final int channels;

    /**
     * The sampling rate.
     */
    private final int sampleRate;

    /**
     * The output layout.
     */
    private final ChannelLayout layout;

    /**
     * The output plane of every bitstream channel.
     */
    private final int[] planeOf;

    /**
     * The residual decoder.
     */
    private final AlacResidual residual;

    /**
     * The bit reader.
     */
    private final AlacBitReader reader;

    /**
     * The decoded residual, then the first-stage predictor output.
     */
    private final int[] predictor;

    /**
     * The predictor output of the first channel of an element.
     */
    private final int[] mixU;

    /**
     * The predictor output of the second channel of a pair.
     */
    private final int[] mixV;

    /**
     * The shifted-off low bits of the first channel of an element.
     */
    private final int[] shiftU;

    /**
     * The shifted-off low bits of the second channel of a pair.
     */
    private final int[] shiftV;

    /**
     * The predictor coefficients of the first channel.
     */
    private final short[] coefficientsU;

    /**
     * The predictor coefficients of the second channel.
     */
    private final short[] coefficientsV;

    /**
     * The integer samples of the last packet, one array per output plane.
     */
    private final int[][] samples;

    /**
     * The output block.
     */
    private final AudioBuffer buffer;

    /**
     * The concealment of lost spans, fed with every float block.
     */
    private final WaveformConcealer concealer;

    /**
     * The integer output block, over {@link #samples}.
     */
    private final IntegerAudioBuffer integers;

    /**
     * The double output block, or {@code null} before the first double decode.
     */
    private DoubleAudioBuffer doubles;

    /**
     * The number of samples per channel of the last packet.
     */
    private int lastFrames;

    /**
     * The number of samples per channel the elements of the current packet code: the frame
     * length unless a partial-frame header overrides it.
     */
    private int elementSamples;

    /**
     * The number of samples per channel of the channels the current packet decoded so far, or
     * {@code -1} before its first element.
     */
    private int decodedSamples;

    /**
     * Creates a decoder for an ALAC track under {@link MediaLimits#DEFAULT}.
     *
     * @param track the track to decode; its codec is {@link Codec#ALAC}
     * @throws IllegalArgumentException if the track's codec is not ALAC
     * @throws MediaException.Malformed if the configuration is missing or invalid
     * @throws MediaException.Unsupported if the configuration's version, sample size or channel
     * count is not supported
     * @throws MediaException.LimitExceeded if the channel count or the frame or concealment buffers exceed
     * {@link MediaLimits#DEFAULT}
     */
    public AlacDecoder(AudioTrack track) {
        this(track, AudioDecoderOptions.defaults());
    }

    /**
     * Constructs a decoder for an ALAC track under the limits of codec-independent options.
     *
     * <p>Only {@link AudioDecoderOptions#limits()} applies: the decoder always outputs the
     * stream's own sampling rate, whatever {@link AudioDecoderOptions#preferredSampleRate()}
     * asks for.
     *
     * @param track the track to decode; its codec is {@link Codec#ALAC}
     * @param options the codec-independent options, of which only the limits apply
     * @throws IllegalArgumentException if the track's codec is not ALAC
     * @throws MediaException.Malformed if the configuration is missing or invalid
     * @throws MediaException.Unsupported if the configuration's version, sample size or channel
     * count is not supported
     * @throws MediaException.LimitExceeded if the channel count or the frame or concealment buffers exceed the
     * options' limits
     */
    public AlacDecoder(AudioTrack track, AudioDecoderOptions options) {
        this(track, options.limits());
    }

    /**
     * Creates a decoder for an ALAC track under resource limits.
     *
     * @param track the track to decode
     * @param limits the resource limits
     */
    private AlacDecoder(AudioTrack track, MediaLimits limits) {
        Objects.requireNonNull(track, "track");
        this.limits = limits;
        if (track.codec() != Codec.ALAC) {
            throw new IllegalArgumentException("not an ALAC track: " + track.codec());
        }
        var cookie = track.codecConfig().orElseThrow(() -> new MediaException.Malformed("ALAC track without ALACSpecificConfig"));
        var config = specificConfig(cookie);
        this.frameLength = (config[0] & 0xFF) << 24 | (config[1] & 0xFF) << 16 | (config[2] & 0xFF) << 8 | config[3] & 0xFF;
        var version = config[4] & 0xFF;
        this.bitDepth = config[5] & 0xFF;
        var pb = config[6] & 0xFF;
        var mb = config[7] & 0xFF;
        var kb = config[8] & 0xFF;
        this.channels = config[9] & 0xFF;
        var rate = (config[20] & 0xFF) << 24 | (config[21] & 0xFF) << 16 | (config[22] & 0xFF) << 8 | config[23] & 0xFF;
        if (version != 0) {
            throw new MediaException.Unsupported("ALAC", "ALAC compatible version " + version);
        }
        if (bitDepth < 4 || bitDepth > 32) {
            throw new MediaException.Unsupported("ALAC", "ALAC sample size " + bitDepth);
        }
        if (channels < 1 || channels > MAX_CHANNELS) {
            throw new MediaException.Unsupported("ALAC", "ALAC stream of " + channels + " channels");
        }
        if (frameLength <= 0) {
            throw new MediaException.Malformed("ALAC frame length " + Integer.toUnsignedString(frameLength));
        }
        if (kb < 1 || kb > 31) {
            throw new MediaException.Malformed("ALAC Rice limit " + kb);
        }
        LimitChecks.checkChannels(limits, channels);
        LimitChecks.checkAllocation(limits, ((long) channels + 7) * frameLength * Integer.BYTES + (long) channels * frameLength * Float.BYTES);
        this.sampleRate = rate > 0 ? rate : Math.max(track.sampleRate(), 0);
        LimitChecks.checkAllocation(limits, WaveformConcealer.footprint(channels, Math.max(sampleRate, 1)));
        var speakers = BITSTREAM_ORDER[channels - 1];
        var sorted = new ArrayList<>(List.of(speakers));
        sorted.sort(Comparator.naturalOrder());
        this.layout = new ChannelLayout(sorted);
        this.planeOf = new int[channels];
        for (var i = 0; i < channels; i++) {
            planeOf[i] = sorted.indexOf(speakers[i]);
        }
        this.residual = new AlacResidual(mb, pb, kb);
        this.reader = new AlacBitReader();
        this.predictor = new int[frameLength];
        this.mixU = new int[frameLength];
        this.mixV = new int[frameLength];
        this.shiftU = new int[frameLength];
        this.shiftV = new int[frameLength];
        this.coefficientsU = new short[MAX_COEFFICIENTS];
        this.coefficientsV = new short[MAX_COEFFICIENTS];
        this.samples = new int[channels][frameLength];
        this.buffer = new AudioBuffer(layout, Math.max(sampleRate, 1), frameLength);
        this.concealer = new WaveformConcealer(layout.channels(), Math.max(sampleRate, 1));
        this.integers = new IntegerAudioBuffer(layout, Math.max(sampleRate, 1), bitDepth, samples, 0);
    }

    /**
     * Finds the {@code ALACSpecificConfig} in a magic cookie.
     *
     * <p>The cookie may wrap the configuration in a {@code frma} atom and an {@code alac} atom
     * header, as older encoders vend it, or prefix it with the four bytes of version and flags of
     * the MP4 {@code alac} box, and may follow it with the 24-byte channel layout information.
     *
     * @param cookie the magic cookie
     * @return the 24 configuration bytes
     * @throws MediaException.Malformed if the cookie holds no configuration
     */
    private static byte[] specificConfig(byte[] cookie) {
        var offset = 0;
        if (cookie.length - offset >= 12 && cookie[offset + 4] == 'f' && cookie[offset + 5] == 'r'
                && cookie[offset + 6] == 'm' && cookie[offset + 7] == 'a') {
            offset += 12;
        }
        if (cookie.length - offset >= 12 && cookie[offset + 4] == 'a' && cookie[offset + 5] == 'l'
                && cookie[offset + 6] == 'a' && cookie[offset + 7] == 'c') {
            offset += 12;
        } else if (offset == 0 && (cookie.length == 28 || cookie.length == 52)) {
            offset = 4;
        }
        if (cookie.length - offset < CONFIG_SIZE) {
            throw new MediaException.Malformed("ALAC magic cookie of " + cookie.length + " bytes holds no ALACSpecificConfig");
        }
        return Arrays.copyOfRange(cookie, offset, offset + CONFIG_SIZE);
    }

    @Override
    public int sampleRate() {
        return sampleRate;
    }

    @Override
    public ChannelLayout layout() {
        return layout;
    }

    @Override
    public void decode(Packet packet, Consumer<AudioBuffer> output) {
        Objects.requireNonNull(packet, "packet");
        Objects.requireNonNull(output, "output");
        decodePacket(packet);
        var scale = Math.scalb(1f, 1 - bitDepth);
        var frames = lastFrames;
        for (var c = 0; c < channels; c++) {
            var source = samples[c];
            var plane = buffer.plane(c);
            for (var i = 0; i < frames; i++) {
                plane[i] = source[i] * scale;
            }
        }
        concealer.recover(buffer.planes(), frames);
        concealer.record(buffer.planes(), frames);
        buffer.setFrames(frames);
        output.accept(buffer);
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
        var chunk = buffer.capacity();
        for (var remaining = samples; remaining > 0; ) {
            var frames = Math.min(remaining, chunk);
            concealer.synthesize(buffer.planes(), 0, frames);
            buffer.setFrames(frames);
            output.accept(buffer);
            remaining -= frames;
        }
        return samples;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Does nothing: an ALAC decoder buffers no samples between packets.
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
     * @return the sample size of the configuration, from 4 to 32
     */
    @Override
    public int bitsPerSample() {
        return bitDepth;
    }

    @Override
    public void decodeIntegers(Packet packet, Consumer<IntegerAudioBuffer> output) {
        Objects.requireNonNull(packet, "packet");
        Objects.requireNonNull(output, "output");
        decodePacket(packet);
        integers.setFrames(lastFrames);
        output.accept(integers);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Does nothing: an ALAC decoder buffers no samples between packets.
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

    @Override
    public void decodeDoubles(Packet packet, Consumer<DoubleAudioBuffer> output) {
        Objects.requireNonNull(packet, "packet");
        Objects.requireNonNull(output, "output");
        decodePacket(packet);
        if (doubles == null) {
            doubles = new DoubleAudioBuffer(layout, Math.max(sampleRate, 1), frameLength);
        }
        var scale = Math.scalb(1.0, 1 - bitDepth);
        var frames = lastFrames;
        for (var c = 0; c < channels; c++) {
            var source = samples[c];
            var plane = doubles.plane(c);
            for (var i = 0; i < frames; i++) {
                plane[i] = source[i] * scale;
            }
        }
        doubles.setFrames(frames);
        output.accept(doubles);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Does nothing: an ALAC decoder buffers no samples between packets.
     */
    @Override
    public void flushDoubles(Consumer<DoubleAudioBuffer> output) {
        Objects.requireNonNull(output, "output");
    }

    /**
     * Decodes one packet into {@link #samples}, wrapping unchecked failures.
     *
     * @param packet the packet to decode, one frame
     * @throws MediaException.Malformed if the packet is corrupt
     * @throws MediaException.Unsupported if it holds a 33-bit channel pair
     */
    private void decodePacket(Packet packet) {
        try {
            decodeFrame(packet.data());
        } catch (MediaException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new MediaException.Malformed("corrupt ALAC packet", exception);
        }
    }

    /**
     * Returns the integer samples of the last decoded packet, one array per output channel; a test
     * hook for checks defined over integer samples.
     *
     * <p>Only the first {@link #lastFrames()} elements are valid.
     *
     * @return the sample arrays, not copies
     */
    int[][] lastSamples() {
        return samples;
    }

    /**
     * Returns the number of samples per channel of the last decoded packet; a test hook.
     *
     * @return the frame count
     */
    int lastFrames() {
        return lastFrames;
    }

    /**
     * Decodes one packet into {@link #samples}.
     *
     * @param data the packet
     * @throws MediaException.Malformed if the packet is corrupt
     * @throws MediaException.Unsupported if it holds a 33-bit channel pair
     */
    private void decodeFrame(byte[] data) {
        var bits = reader;
        bits.reset(data);
        var channelIndex = 0;
        elementSamples = frameLength;
        decodedSamples = -1;
        elements:
        while (true) {
            if (bits.exhausted()) {
                throw new MediaException.Malformed("ALAC packet ends before its end element");
            }
            var tag = bits.read(3);
            switch (tag) {
                case ID_SCE, ID_LFE -> {
                    decodeSingle(channelIndex);
                    channelIndex++;
                }
                case ID_CPE -> {
                    if (channelIndex + 2 > channels) {
                        break elements;
                    }
                    decodePair(channelIndex);
                    channelIndex += 2;
                }
                case ID_DSE -> skipDataStream();
                case ID_FIL -> skipFill();
                case ID_CCE, ID_PCE -> throw new MediaException.Malformed("unsupported ALAC element " + tag);
                default -> {
                    bits.alignToByte();
                    break elements;
                }
            }
            if (bits.overrun()) {
                throw new MediaException.Malformed("ALAC element runs past the end of the packet");
            }
            if (channelIndex >= channels) {
                break;
            }
        }
        if (bits.overrun()) {
            throw new MediaException.Malformed("ALAC element runs past the end of the packet");
        }
        var frames = decodedSamples < 0 ? elementSamples : decodedSamples;
        for (; channelIndex < channels; channelIndex++) {
            Arrays.fill(samples[planeOf[channelIndex]], 0, frames, 0);
        }
        lastFrames = frames;
    }

    /**
     * Reads the common header of a single-channel or pair element, updating
     * {@link #elementSamples} from a partial-frame header.
     *
     * @return the header bits: partial flag, shifted bytes and escape flag
     * @throws MediaException.Malformed if a reserved field is set or the frame is too long or
     * disagrees with the channels already decoded
     */
    private int elementHeader() {
        var bits = reader;
        bits.read(4);
        if (bits.read(12) != 0) {
            throw new MediaException.Malformed("ALAC element with nonzero reserved bits");
        }
        var header = bits.read(4);
        if (((header >> 1) & 3) == 3) {
            throw new MediaException.Malformed("ALAC element with three shifted bytes");
        }
        if ((header & 8) != 0) {
            var count = bits.read(32);
            if (count < 0 || count > frameLength) {
                throw new MediaException.Malformed("ALAC partial frame of " + Integer.toUnsignedString(count)
                        + " samples exceeds the frame length " + frameLength);
            }
            elementSamples = count;
        }
        if (decodedSamples >= 0 && decodedSamples != elementSamples) {
            throw new MediaException.Malformed("ALAC elements of " + decodedSamples + " and " + elementSamples
                    + " samples in one frame");
        }
        decodedSamples = elementSamples;
        return header;
    }

    /**
     * Decodes a single-channel or LFE element.
     *
     * @param channelIndex the bitstream index of the channel
     */
    private void decodeSingle(int channelIndex) {
        var bits = reader;
        var header = elementHeader();
        var count = elementSamples;
        var bytesShifted = (header >> 1) & 3;
        var chanBits = bitDepth - bytesShifted * 8;
        if (chanBits < 1) {
            throw new MediaException.Malformed("ALAC element shifts off " + bytesShifted + " bytes of " + bitDepth + "-bit samples");
        }
        var out = samples[planeOf[channelIndex]];
        if ((header & 1) != 0) {
            for (var i = 0; i < count; i++) {
                out[i] = extend(bits.readSigned(chanBits));
            }
            return;
        }
        bits.read(16);
        var modeU = readPredictorHeader(coefficientsU);
        var numU = modeU & 0x1F;
        var shiftStart = bits.position();
        var shift = bytesShifted * 8;
        if (bytesShifted != 0) {
            bits.skip((long) shift * count);
        }
        decodeChannel(modeU, coefficientsU, numU, mixU, count, chanBits);
        if (bytesShifted != 0) {
            var end = bits.position();
            bits.position(shiftStart);
            for (var i = 0; i < count; i++) {
                shiftU[i] = bits.read(shift);
            }
            bits.position(end);
            for (var i = 0; i < count; i++) {
                out[i] = extend((mixU[i] << shift) | shiftU[i]);
            }
        } else {
            for (var i = 0; i < count; i++) {
                out[i] = extend(mixU[i]);
            }
        }
    }

    /**
     * Decodes a channel pair element.
     *
     * @param channelIndex the bitstream index of the pair's first channel
     */
    private void decodePair(int channelIndex) {
        var bits = reader;
        var header = elementHeader();
        var count = elementSamples;
        var bytesShifted = (header >> 1) & 3;
        var left = samples[planeOf[channelIndex]];
        var right = samples[planeOf[channelIndex + 1]];
        if ((header & 1) != 0) {
            for (var i = 0; i < count; i++) {
                left[i] = extend(bits.readSigned(bitDepth));
                right[i] = extend(bits.readSigned(bitDepth));
            }
            return;
        }
        var chanBits = bitDepth - bytesShifted * 8 + 1;
        if (chanBits > 32) {
            throw new MediaException.Unsupported("ALAC", "ALAC channel pair of 33-bit samples");
        }
        if (chanBits < 2) {
            throw new MediaException.Malformed("ALAC element shifts off " + bytesShifted + " bytes of " + bitDepth + "-bit samples");
        }
        var mixBits = bits.read(8);
        var mixRes = (byte) bits.read(8);
        var modeU = readPredictorHeader(coefficientsU);
        var modeV = readPredictorHeader(coefficientsV);
        var shiftStart = bits.position();
        var shift = bytesShifted * 8;
        if (bytesShifted != 0) {
            bits.skip((long) shift * 2 * count);
        }
        decodeChannel(modeU, coefficientsU, modeU & 0x1F, mixU, count, chanBits);
        decodeChannel(modeV, coefficientsV, modeV & 0x1F, mixV, count, chanBits);
        if (bytesShifted != 0) {
            var end = bits.position();
            bits.position(shiftStart);
            for (var i = 0; i < count; i++) {
                shiftU[i] = bits.read(shift);
                shiftV[i] = bits.read(shift);
            }
            bits.position(end);
        }
        var u = mixU;
        var v = mixV;
        for (var i = 0; i < count; i++) {
            int l;
            int r;
            if (mixRes != 0) {
                l = u[i] + v[i] - ((mixRes * v[i]) >> mixBits);
                r = l - v[i];
            } else {
                l = u[i];
                r = v[i];
            }
            if (bytesShifted != 0) {
                l = (l << shift) | shiftU[i];
                r = (r << shift) | shiftV[i];
            }
            left[i] = extend(l);
            right[i] = extend(r);
        }
    }

    /**
     * Reads the predictor header of one channel: mode, quantization shift, rate factor, order and
     * coefficients.
     *
     * @param coefficients receives the coefficients
     * @return the packed header: mode in bits 16 to 19, quantization shift in bits 12 to 15, rate
     * factor in bits 8 to 10 and order in bits 0 to 4
     */
    private int readPredictorHeader(short[] coefficients) {
        var bits = reader;
        var first = bits.read(8);
        var second = bits.read(8);
        var order = second & 0x1F;
        for (var i = 0; i < order; i++) {
            coefficients[i] = (short) bits.read(16);
        }
        return (first >> 4) << 16 | (first & 0xF) << 12 | (second >> 5) << 8 | order;
    }

    /**
     * Decodes the residual of one channel and runs its predictor.
     *
     * @param header the packed predictor header
     * @param coefficients the predictor coefficients
     * @param order the predictor order
     * @param out receives the predicted samples
     * @param count the number of samples
     * @param chanBits the sample size of the channel's prediction
     */
    private void decodeChannel(int header, short[] coefficients, int order, int[] out, int count, int chanBits) {
        var mode = (header >> 16) & 0xF;
        var denShift = (header >> 12) & 0xF;
        var rateFactor = (header >> 8) & 0x7;
        residual.decode(reader, predictor, count, chanBits, rateFactor);
        if (mode == 0) {
            AlacPredictor.restore(predictor, out, count, coefficients, order, chanBits, denShift);
        } else {
            AlacPredictor.restore(predictor, predictor, count, null, 31, chanBits, 0);
            AlacPredictor.restore(predictor, out, count, coefficients, order, chanBits, denShift);
        }
    }

    /**
     * Sign-extends a decoded sample from the stream's sample size.
     *
     * @param value the sample, its low {@link #bitDepth} bits significant
     * @return the sample in {@code [-2^(bitDepth-1), 2^(bitDepth-1))}
     */
    private int extend(int value) {
        var unused = 32 - bitDepth;
        return (value << unused) >> unused;
    }

    /**
     * Skips a data stream element.
     */
    private void skipDataStream() {
        var bits = reader;
        bits.read(4);
        var align = bits.read(1);
        var count = bits.read(8);
        if (count == 255) {
            count += bits.read(8);
        }
        if (align != 0) {
            bits.alignToByte();
        }
        bits.skip(count * 8L);
    }

    /**
     * Skips a fill element.
     */
    private void skipFill() {
        var bits = reader;
        var count = bits.read(4);
        if (count == 15) {
            count += bits.read(8) - 1;
        }
        bits.skip(count * 8L);
    }
}
