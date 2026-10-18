/* Ports libFLAC 1.4 (src/libFLAC/stream_encoder.c). Copyright (C) 2000-2009 Josh Coalson, (C) 2011-2025 Xiph.Org
   Foundation; BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.audio.flac;

import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.audio.AudioEncoder;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Encodes audio into FLAC frames (RFC 9639), losslessly.
 *
 * <p>The encoder takes integer samples of the configured {@link FlacEncoderOptions#bitsPerSample()
 * sample size} through {@link #encode(int[][], int, int, Consumer)} and
 * {@link #encodeInterleaved(int[], int, int, Consumer)}, or planar floats through
 * {@link #encode(AudioBuffer, Consumer)}, which it quantizes: a float sample is clipped to
 * {@code [-1, 1]}, multiplied by {@code 2^(bitsPerSample - 1)}, rounded to the nearest integer
 * with ties toward positive infinity and clamped to the sample range, and NaN becomes zero. That
 * is the inverse of {@link FlacDecoder}'s scaling, so samples a FLAC decoder produced encode back
 * to the same integers. Input of any length is buffered into blocks of
 * {@link FlacEncoderOptions#blockSize()} samples; each block becomes one frame and one
 * {@link Packet}, timestamped in samples from zero, with its duration and the key-frame flag.
 * {@link #flush(Consumer)} encodes the remaining samples as a final, shorter frame without
 * padding, so the decoded stream has exactly the input's length.
 *
 * <p>Each frame chooses among the stereo decorrelations of
 * {@link FlacEncoderOptions#stereoMode()}, wasted bits, constant and verbatim subframes, fixed
 * predictors of order 0 to 4 and quantized linear predictors up to
 * {@link FlacEncoderOptions#maxLpcOrder()} from the windowed autocorrelation, and a residual in
 * Rice partitions (with escape partitions when allowed). Samples of 32 bits are supported,
 * including the 33-bit side channel of 32-bit stereo.
 *
 * <p>{@link #codecConfig()} is the {@code fLaC} marker followed by the metadata blocks of a native
 * FLAC file: {@code STREAMINFO}, a {@code SEEKTABLE} when seek points are requested and the
 * expected length is known, and {@code PADDING}. Before the stream ends, {@code STREAMINFO}
 * declares the expected length (or an unknown one), unknown frame sizes and no MD5 signature, and
 * the seek table holds placeholder points. After {@link #flush(Consumer)} the configuration is
 * final and has the same length: {@code STREAMINFO} carries the real length, the frame size bounds
 * and the MD5 signature of the samples, and the seek table points at the frames; when the expected
 * length was unknown, the seek table is carved out of the padding. A muxer that can rewrite its
 * header ({@link com.github.auties00.smalt.container.Muxer#updateCodecConfig(int, byte[])}) then
 * stores the final form in place, so a native FLAC file written on a seekable sink is complete;
 * on a stream the initial form stays valid.
 *
 * <p>Instances are not thread-safe.
 *
 * @implNote This implementation makes libFLAC's choices for every frame and runs its
 * data-parallel stages on the Vector API when it is enabled, with output identical to the scalar
 * path. The MD5 signature covers the samples as {@code FLAC__MD5Accumulate} lays them out:
 * interleaved, little-endian, in {@code (bitsPerSample + 7) / 8} bytes each.
 */
public final class FlacEncoder implements AudioEncoder {
    /**
     * The size of a {@code STREAMINFO} body.
     */
    private static final int STREAMINFO_SIZE = 34;

    /**
     * The size of one seek point.
     */
    private static final int SEEK_POINT_SIZE = 18;

    /**
     * The metadata block type of {@code STREAMINFO}.
     */
    private static final int TYPE_STREAMINFO = 0;

    /**
     * The metadata block type of {@code PADDING}.
     */
    private static final int TYPE_PADDING = 1;

    /**
     * The metadata block type of {@code SEEKTABLE}.
     */
    private static final int TYPE_SEEKTABLE = 3;

    /**
     * Stores two bytes as a little-endian {@code short}.
     */
    private static final VarHandle SHORT_LE = MethodHandles.byteArrayViewVarHandle(short[].class, ByteOrder.LITTLE_ENDIAN);

    /**
     * Stores four bytes as a little-endian {@code int}.
     */
    private static final VarHandle INT_LE = MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.LITTLE_ENDIAN);

    /**
     * The sample number of a placeholder seek point.
     */
    private static final long PLACEHOLDER = -1L;

    /**
     * The configuration.
     */
    private final FlacEncoderOptions options;

    /**
     * The number of channels.
     */
    private final int channels;

    /**
     * The sample size.
     */
    private final int bitsPerSample;

    /**
     * The block size.
     */
    private final int blockSize;

    /**
     * The smallest valid sample.
     */
    private final int minSample;

    /**
     * The largest valid sample.
     */
    private final int maxSample;

    /**
     * The frame encoder.
     */
    private final FlacFrameEncoder frames;

    /**
     * The MD5 digest of the samples.
     */
    private final MessageDigest md5;

    /**
     * The bytes of one block laid out for the digest.
     */
    private final byte[] digestBuffer;

    /**
     * The seek point interval in samples, zero without a seek table.
     */
    private final long seekInterval;

    /**
     * The number of seek points the initial header reserves, zero when the seek table is carved
     * from the padding at the end or absent.
     */
    private final int reservedSeekPoints;

    /**
     * The header before the stream ends.
     */
    private final byte[] initialHeader;

    /**
     * The final header, or {@code null} before {@link #flush(Consumer)}.
     */
    private byte[] finalHeader;

    /**
     * The number of samples per channel waiting in the frame encoder's input.
     */
    private int pending;

    /**
     * The number of frames written.
     */
    private long frameCount;

    /**
     * The number of samples per channel written in frames.
     */
    private long samplesWritten;

    /**
     * The number of bytes of frames written.
     */
    private long bytesWritten;

    /**
     * The offset of every frame from the first, in bytes.
     */
    private long[] frameOffsets;

    /**
     * The smallest frame written, in bytes.
     */
    private int minFrameSize;

    /**
     * The largest frame written, in bytes.
     */
    private int maxFrameSize;

    /**
     * Constructs an encoder for 44.1 kHz stereo input coded as 16-bit samples at compression
     * level 5, the settings of {@link FlacEncoderOptions#defaults()}.
     */
    public FlacEncoder() {
        this(FlacEncoderOptions.defaults());
    }

    /**
     * Creates an encoder.
     *
     * @param options the input format, compression settings and metadata layout
     */
    public FlacEncoder(FlacEncoderOptions options) {
        this(options, FlacEncoderKernel.INSTANCE);
    }

    /**
     * Creates an encoder running its data-parallel stages on a given kernel; benchmarks compare
     * the kernels with it.
     *
     * @param options the input format, compression settings and metadata layout
     * @param kernel the kernel running the data-parallel stages
     */
    FlacEncoder(FlacEncoderOptions options, FlacEncoderKernel kernel) {
        this.options = Objects.requireNonNull(options, "options");
        Objects.requireNonNull(kernel, "kernel");
        this.channels = options.layout().channels();
        this.bitsPerSample = options.bitsPerSample();
        this.blockSize = options.blockSize();
        this.minSample = bitsPerSample == 32 ? Integer.MIN_VALUE : -(1 << (bitsPerSample - 1));
        this.maxSample = bitsPerSample == 32 ? Integer.MAX_VALUE : (1 << (bitsPerSample - 1)) - 1;
        this.frames = new FlacFrameEncoder(options, kernel);
        try {
            this.md5 = MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("MD5 is not available", exception);
        }
        this.digestBuffer = new byte[blockSize * channels * ((bitsPerSample + 7) / 8)];
        this.seekInterval = options.seekPointInterval()
                .map(interval -> Math.max(1, Math.round(interval.toNanos() / 1e9 * options.sampleRate())))
                .orElse(0L);
        var expected = options.expectedTotalSamples();
        this.reservedSeekPoints = seekInterval > 0 && expected.isPresent()
                ? (int) Math.min((expected.getAsLong() + seekInterval - 1) / seekInterval, ((1 << 24) - 1) / SEEK_POINT_SIZE)
                : 0;
        this.frameOffsets = new long[64];
        this.minFrameSize = Integer.MAX_VALUE;
        this.initialHeader = header(false);
    }

    /**
     * Returns the configuration of this encoder.
     *
     * @return the options
     */
    public FlacEncoderOptions options() {
        return options;
    }

    @Override
    public Codec codec() {
        return Codec.FLAC;
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
     * Returns the block size, the samples per channel of every frame but the last.
     *
     * @return the block size
     */
    @Override
    public int frameSize() {
        return blockSize;
    }

    /**
     * Returns {@code 0}: FLAC has no codec delay.
     *
     * @return {@code 0}
     */
    @Override
    public int delay() {
        return 0;
    }

    /**
     * Returns the {@code fLaC} marker and the metadata blocks of the stream.
     *
     * <p>Before {@link #flush(Consumer)} this is the initial form, afterwards the final form with
     * the same length, as the class documentation describes.
     *
     * @return the configuration
     */
    @Override
    public Optional<byte[]> codecConfig() {
        return Optional.of((finalHeader != null ? finalHeader : initialHeader).clone());
    }

    /**
     * Returns the number of samples per channel encoded into frames so far.
     *
     * @return the count, excluding samples still buffered
     */
    public long samplesEncoded() {
        return samplesWritten;
    }

    /**
     * Quantizes planar float samples to the sample size and buffers them, emitting a packet for
     * every block completed.
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
            var count = Math.min(total - consumed, blockSize - pending);
            for (var c = 0; c < channels; c++) {
                var plane = planes[c];
                var target = frames.input(c);
                for (var i = 0; i < count; i++) {
                    target[pending + i] = quantize(plane[consumed + i], scale);
                }
            }
            advance(count, output);
            consumed += count;
        }
    }

    /**
     * Buffers planar integer samples, emitting a packet for every block completed.
     *
     * @param samples one array per channel
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
            var chunk = Math.min(count - consumed, blockSize - pending);
            for (var c = 0; c < channels; c++) {
                System.arraycopy(samples[c], offset + consumed, frames.input(c), pending, chunk);
            }
            advance(chunk, output);
            consumed += chunk;
        }
    }

    /**
     * Buffers interleaved integer samples, emitting a packet for every block completed.
     *
     * @param samples the samples, channel by channel within each sample frame
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
            var chunk = Math.min(count - consumed, blockSize - pending);
            for (var c = 0; c < channels; c++) {
                var target = frames.input(c);
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
     * Encodes the buffered samples as a final frame without padding and completes the codec
     * configuration; later calls do nothing.
     *
     * @param output receives zero or one packet
     */
    @Override
    public void flush(Consumer<Packet> output) {
        Objects.requireNonNull(output, "output");
        if (finalHeader != null) {
            return;
        }
        if (pending > 0) {
            emit(pending, output);
        }
        finalHeader = header(true);
    }

    /**
     * Rejects input after {@link #flush(Consumer)}.
     *
     * @throws IllegalStateException if the encoder was flushed
     */
    private void checkOpen() {
        if (finalHeader != null) {
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
     * Quantizes one float sample.
     *
     * @param sample the float sample, nominally in {@code [-1, 1]}
     * @param scale {@code 2^(bitsPerSample - 1)}
     * @return the integer sample
     */
    private int quantize(float sample, double scale) {
        var value = Math.round(sample * scale);
        return (int) Math.max(minSample, Math.min(maxSample, value));
    }

    /**
     * Accounts for samples copied into the frame encoder's input and encodes the block once full.
     *
     * @param count the number of samples per channel added
     * @param output the packet consumer
     */
    private void advance(int count, Consumer<Packet> output) {
        pending += count;
        if (pending == blockSize) {
            emit(blockSize, output);
        }
    }

    /**
     * Encodes the buffered block and emits its packet.
     *
     * @param size the number of samples per channel
     * @param output the packet consumer
     */
    private void emit(int size, Consumer<Packet> output) {
        digest(size);
        var frame = frames.encode(size, frameCount);
        if (frameCount == frameOffsets.length) {
            frameOffsets = Arrays.copyOf(frameOffsets, frameOffsets.length * 2);
        }
        frameOffsets[(int) frameCount] = bytesWritten;
        frameCount++;
        bytesWritten += frame.length;
        minFrameSize = Math.min(minFrameSize, frame.length);
        maxFrameSize = Math.max(maxFrameSize, frame.length);
        var pts = samplesWritten;
        samplesWritten += size;
        pending = 0;
        output.accept(new Packet(0, frame, pts, pts, size, true));
    }

    /**
     * Adds a block to the MD5 signature, interleaved and little-endian.
     *
     * @param size the number of samples per channel
     */
    private void digest(int size) {
        var bytes = (bitsPerSample + 7) / 8;
        var buffer = digestBuffer;
        var stride = channels * bytes;
        for (var c = 0; c < channels; c++) {
            var samples = frames.input(c);
            var position = c * bytes;
            switch (bytes) {
                case 1 -> {
                    for (var i = 0; i < size; i++, position += stride) {
                        buffer[position] = (byte) samples[i];
                    }
                }
                case 2 -> {
                    for (var i = 0; i < size; i++, position += stride) {
                        SHORT_LE.set(buffer, position, (short) samples[i]);
                    }
                }
                case 3 -> {
                    for (var i = 0; i < size; i++, position += stride) {
                        var value = samples[i];
                        buffer[position] = (byte) value;
                        buffer[position + 1] = (byte) (value >> 8);
                        buffer[position + 2] = (byte) (value >> 16);
                    }
                }
                default -> {
                    for (var i = 0; i < size; i++, position += stride) {
                        INT_LE.set(buffer, position, samples[i]);
                    }
                }
            }
        }
        md5.update(buffer, 0, size * stride);
    }

    /**
     * Builds the codec configuration.
     *
     * @param complete whether the stream ended, so that the final form is built
     * @return the {@code fLaC} marker and the metadata blocks
     */
    private byte[] header(boolean complete) {
        var padding = options.padding();
        var paddingBlock = padding > 0;
        var seekPoints = reservedSeekPoints;
        long[] points = null;
        if (complete && seekInterval > 0) {
            if (reservedSeekPoints > 0) {
                points = seekPoints(reservedSeekPoints, 1);
            } else if (paddingBlock && samplesWritten > 0) {
                // The seek table takes the place of the padding, so the header keeps its length:
                // either all of it, or its start with a shorter padding block after it.
                var wanted = (samplesWritten + seekInterval - 1) / seekInterval;
                var besidePadding = padding >= 4 ? (padding - 4) / SEEK_POINT_SIZE : 0;
                var replaces = wanted > besidePadding && padding % SEEK_POINT_SIZE == 0 && padding / SEEK_POINT_SIZE >= wanted;
                var count = (int) (replaces ? wanted : Math.min(wanted, besidePadding));
                if (count > 0) {
                    points = seekPoints(count, (wanted + count - 1) / count);
                    seekPoints = count;
                    if (replaces) {
                        paddingBlock = false;
                    } else {
                        padding -= count * SEEK_POINT_SIZE + 4;
                    }
                }
            }
        }
        var size = 4 + 4 + STREAMINFO_SIZE + (seekPoints > 0 ? 4 + seekPoints * SEEK_POINT_SIZE : 0)
                + (paddingBlock ? 4 + padding : 0);
        var header = new byte[size];
        header[0] = 'f';
        header[1] = 'L';
        header[2] = 'a';
        header[3] = 'C';
        var position = 4;
        position = blockHeader(header, position, TYPE_STREAMINFO, STREAMINFO_SIZE, seekPoints == 0 && !paddingBlock);
        position = streamInfo(header, position, complete);
        if (seekPoints > 0) {
            position = blockHeader(header, position, TYPE_SEEKTABLE, seekPoints * SEEK_POINT_SIZE, !paddingBlock);
            for (var i = 0; i < seekPoints; i++) {
                var sample = points != null ? points[3 * i] : PLACEHOLDER;
                var offset = points != null ? points[3 * i + 1] : 0;
                var samples = points != null ? points[3 * i + 2] : 0;
                position = putLong(header, position, sample, 8);
                position = putLong(header, position, offset, 8);
                position = putLong(header, position, samples, 2);
            }
        }
        if (paddingBlock) {
            blockHeader(header, position, TYPE_PADDING, padding, true);
        }
        return header;
    }

    /**
     * Fills seek points for the template points {@code k * step * interval}.
     *
     * @param count the number of points the seek table holds
     * @param step the number of intervals between template points
     * @return the points as triples (sample, offset, frame samples), unique and ascending, padded
     * with placeholders
     */
    private long[] seekPoints(int count, long step) {
        var points = new long[3 * count];
        var filled = 0;
        var lastFrame = -1L;
        for (var k = 0L; k < count; k++) {
            var target = k * step * seekInterval;
            if (target >= samplesWritten) {
                break;
            }
            var frame = target / blockSize;
            if (frame == lastFrame) {
                continue;
            }
            lastFrame = frame;
            points[3 * filled] = frame * blockSize;
            points[3 * filled + 1] = frameOffsets[(int) frame];
            points[3 * filled + 2] = Math.min(blockSize, samplesWritten - frame * blockSize);
            filled++;
        }
        for (var i = filled; i < count; i++) {
            points[3 * i] = PLACEHOLDER;
        }
        return points;
    }

    /**
     * Writes a {@code STREAMINFO} body.
     *
     * @param header the destination
     * @param position the index of the body
     * @param complete whether to write the final values
     * @return the index after the body
     */
    private int streamInfo(byte[] header, int position, boolean complete) {
        position = putLong(header, position, blockSize, 2);
        position = putLong(header, position, blockSize, 2);
        position = putLong(header, position, complete && frameCount > 0 ? Math.min(minFrameSize, 0xFFFFFF) : 0, 3);
        position = putLong(header, position, complete ? Math.min(maxFrameSize, 0xFFFFFF) : 0, 3);
        var total = complete ? samplesWritten : options.expectedTotalSamples().orElse(0);
        if (total >= 1L << 36) {
            total = 0;
        }
        var packed = (long) options.sampleRate() << 44 | (long) (channels - 1) << 41 | (long) (bitsPerSample - 1) << 36 | total;
        position = putLong(header, position, packed, 8);
        if (complete) {
            System.arraycopy(md5.digest(), 0, header, position, 16);
        }
        return position + 16;
    }

    /**
     * Writes a metadata block header.
     *
     * @param header the destination
     * @param position the index of the block header
     * @param type the block type
     * @param length the body length
     * @param last whether this is the last metadata block
     * @return the index of the body
     */
    private static int blockHeader(byte[] header, int position, int type, int length, boolean last) {
        header[position] = (byte) ((last ? 0x80 : 0) | type);
        return putLong(header, position + 1, length, 3);
    }

    /**
     * Writes a big-endian value.
     *
     * @param target the destination
     * @param position the index of the first byte
     * @param value the value to write
     * @param bytes the number of bytes
     * @return the index after the value
     */
    private static int putLong(byte[] target, int position, long value, int bytes) {
        for (var i = bytes - 1; i >= 0; i--) {
            target[position++] = (byte) (value >>> (8 * i));
        }
        return position;
    }
}
