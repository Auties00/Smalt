/* Ports WavPack 5.8.1 (src/unpack_utils.c, src/open_utils.c, cli/wvunpack.c). Copyright (c) 1998 - 2025 David
   Bryant; BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.audio.wavpack;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.audio.AudioDecoder;
import com.github.auties00.smalt.audio.AudioDecoderOptions;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.DoubleAudioBuffer;
import com.github.auties00.smalt.audio.IntegerAudioBuffer;
import com.github.auties00.smalt.audio.Speaker;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Decodes WavPack 4 and 5 audio (stream versions 0x402 to 0x410): lossless, hybrid lossy, and hybrid lossless when
 * the correction data is supplied, integer samples of 1 to 32 bits, 32-bit floating-point samples, and DSD.
 *
 * <p>A packet holds one frame of every channel, in either form {@link Codec#WAVPACK} describes: the complete
 * {@code "wvpk"} blocks of a {@code .wv} file, possibly with blocks without audio whose metadata (the MD5
 * signature) the decoder reads, or the blocks as Matroska ({@code A_WAVPACK4}) stores them, without their headers,
 * when the track's configuration is the two-byte stream version. The correction data of a hybrid
 * stream is read from the packet's side data: the complete correction blocks of the frame under the key
 * {@value #CORRECTION_KEY}, or the Matroska {@code BlockAdditional} of {@code BlockAddID} 1 under
 * {@value #MATROSKA_CORRECTION_KEY}, which holds each correction block's checksum, its size when the frame has
 * several blocks, and its data. Without correction data a hybrid stream decodes lossy.
 *
 * <p>The decoded integers are bit-identical to those of the WavPack 5.8.1 library: integer samples of a stream
 * coded at {@code b} bits per sample (the block's bytes per sample times 8 minus its shift) are output as
 * {@code b}-bit integers ({@link #decodeIntegers(Packet, Consumer)}) and scaled to floats by {@code 2^-(b - 1)};
 * floating-point samples are output unchanged, normalized to a full scale of 1 when the stream declares another
 * one; DSD is output as the 24-bit PCM the library's 8-times decimation produces, at the stream's DSD rate divided
 * by 8. Floating-point streams have no integer output. Channels are in WAVE order, the speakers of the stream's
 * channel mask followed by channels of {@link Speaker#UNKNOWN} position.
 *
 * <p>Every block is checked against its checksums: a block failing them, a corrupt bitstream or invalid metadata
 * raise {@link MediaException.Malformed}. With {@link AudioDecoderOptions#verifyIntegrity()} the flush also
 * compares the MD5 signature a PCM stream carries with the decoded samples, when the decode started at the first
 * frame.
 *
 * <p>Instances are not thread-safe.
 */
public final class WavPackDecoder implements AudioDecoder {
    /**
     * The side data key of the complete correction blocks of a packet.
     */
    public static final String CORRECTION_KEY = "wavpack:correction";

    /**
     * The side data key of the Matroska correction data of a packet.
     */
    public static final String MATROSKA_CORRECTION_KEY = "matroska:BlockAdditional:1";

    private static final int HEADER = 32;

    private final MediaLimits limits;

    private final boolean verify;

    /**
     * The stream version of headerless packets, or 0 for complete blocks.
     */
    private final int headerlessVersion;

    private final WavPackStream.StreamInfo info = new WavPackStream.StreamInfo();

    private final ArrayList<WavPackStream> streams = new ArrayList<>();

    private int sampleRate;

    private ChannelLayout layout;

    private int channels;

    private int bitsPerSample;

    private boolean floating;

    private boolean dsd;

    private boolean started;

    private int[][] planes = new int[0][];

    private int frames;

    private int[] scratch = new int[0];

    private byte[] rebuilt = new byte[0];

    private byte[] rebuiltCorrection = new byte[0];

    private int[] blockStarts = new int[8];

    private int[] correctionStarts = new int[8];

    private WavPackDecimator decimator;

    private AudioBuffer floats;

    private IntegerAudioBuffer integers;

    private DoubleAudioBuffer doubles;

    private MessageDigest md5;

    private boolean md5Valid = true;

    private byte[] md5Bytes = new byte[0];

    /**
     * Constructs a decoder for a WavPack track with the {@link AudioDecoderOptions#defaults()}.
     *
     * @param track the track; its codec must be {@link Codec#WAVPACK}
     * @throws IllegalArgumentException if the track is not a WavPack track
     * @throws MediaException.Malformed if the configuration is not a two-byte stream version
     * @throws MediaException.Unsupported if the configuration names an unsupported stream version
     */
    public WavPackDecoder(AudioTrack track) {
        this(track, AudioDecoderOptions.defaults());
    }

    /**
     * Constructs a decoder for a WavPack track.
     *
     * <p>The options' limits and {@link AudioDecoderOptions#verifyIntegrity()} apply; the decoder outputs the
     * stream's own rate.
     *
     * @param track the track; its codec must be {@link Codec#WAVPACK}
     * @param options the decoding options
     * @throws IllegalArgumentException if the track is not a WavPack track
     * @throws MediaException.Malformed if the configuration is not a two-byte stream version
     * @throws MediaException.Unsupported if the configuration names an unsupported stream version
     */
    public WavPackDecoder(AudioTrack track, AudioDecoderOptions options) {
        Objects.requireNonNull(track, "track");
        Objects.requireNonNull(options, "options");
        if (track.codec() != Codec.WAVPACK) {
            throw new IllegalArgumentException("not a WavPack track: " + track.codec());
        }
        this.limits = options.limits();
        this.verify = options.verifyIntegrity();
        var config = track.codecConfig().filter(bytes -> bytes.length > 0).orElse(null);
        if (config != null) {
            if (config.length != 2) {
                throw new MediaException.Malformed("WavPack configuration of " + config.length + " bytes");
            }
            headerlessVersion = WavPackStream.u16(config, 0);
            if (headerlessVersion < 0x402 || headerlessVersion > 0x410) {
                throw new MediaException.Unsupported("wavpack", "WavPack stream version 0x"
                        + Integer.toHexString(headerlessVersion));
            }
        } else {
            headerlessVersion = 0;
        }
        this.sampleRate = Math.max(track.sampleRate(), 0);
        this.layout = track.layout().orElse(ChannelLayout.STEREO);
        this.channels = layout.channels();
        LimitChecks.checkChannels(limits, channels);
        this.bitsPerSample = track.bitsPerSample() > 0 ? Math.min(track.bitsPerSample(), 32) : 16;
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
        if (frames == 0) {
            return;
        }
        if (floats == null || floats.capacity() < frames || floats.channels() != channels
                || floats.sampleRate() != sampleRate) {
            floats = new AudioBuffer(layout, Math.max(sampleRate, 1), Math.max(frames, 1));
        }
        var scale = Math.scalb(1f, 1 - bitsPerSample);
        for (var c = 0; c < channels; c++) {
            var source = planes[c];
            var plane = floats.plane(c);
            if (floating) {
                for (var i = 0; i < frames; i++) {
                    plane[i] = Math.scalb(Float.intBitsToFloat(source[i]), floatScale);
                }
            } else {
                for (var i = 0; i < frames; i++) {
                    plane[i] = source[i] * scale;
                }
            }
        }
        floats.setFrames(frames);
        output.accept(floats);
    }

    /**
     * {@inheritDoc}
     *
     * <p>With {@link AudioDecoderOptions#verifyIntegrity()}, compares the stream's MD5 signature with the samples
     * decoded since the first frame.
     *
     * @throws MediaException.Malformed if the integrity check fails
     */
    @Override
    public void flush(Consumer<AudioBuffer> output) {
        Objects.requireNonNull(output, "output");
        checkIntegrity();
    }

    /**
     * {@inheritDoc}
     *
     * @return {@code true}; a floating-point stream raises {@link MediaException.Unsupported} on its first
     * integer decode
     */
    @Override
    public boolean supportsIntegerOutput() {
        return true;
    }

    /**
     * {@inheritDoc}
     *
     * @return the coded sample size, 24 for DSD, or before the first packet the track's sample size (16 when it
     * declares none)
     */
    @Override
    public int bitsPerSample() {
        return bitsPerSample;
    }

    /**
     * {@inheritDoc}
     *
     * @throws MediaException.Unsupported if the stream holds floating-point samples
     */
    @Override
    public void decodeIntegers(Packet packet, Consumer<IntegerAudioBuffer> output) {
        Objects.requireNonNull(packet, "packet");
        Objects.requireNonNull(output, "output");
        decodePacket(packet);
        if (floating) {
            throw new MediaException.Unsupported("wavpack", "WavPack floating-point samples have no integer output");
        }
        if (frames == 0) {
            return;
        }
        if (integers == null || integers.capacity() < frames || integers.channels() != channels
                || integers.sampleRate() != sampleRate || integers.bitsPerSample() != bitsPerSample) {
            integers = new IntegerAudioBuffer(layout, Math.max(sampleRate, 1), bitsPerSample, Math.max(frames, 1));
        }
        for (var c = 0; c < channels; c++) {
            System.arraycopy(planes[c], 0, integers.plane(c), 0, frames);
        }
        integers.setFrames(frames);
        output.accept(integers);
    }

    /**
     * {@inheritDoc}
     *
     * <p>With {@link AudioDecoderOptions#verifyIntegrity()}, compares the stream's MD5 signature with the samples
     * decoded since the first frame.
     *
     * @throws MediaException.Malformed if the integrity check fails
     */
    @Override
    public void flushIntegers(Consumer<IntegerAudioBuffer> output) {
        Objects.requireNonNull(output, "output");
        checkIntegrity();
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
        if (frames == 0) {
            return;
        }
        if (doubles == null || doubles.capacity() < frames || doubles.channels() != channels
                || doubles.sampleRate() != sampleRate) {
            doubles = new DoubleAudioBuffer(layout, Math.max(sampleRate, 1), Math.max(frames, 1));
        }
        var scale = Math.scalb(1.0, 1 - bitsPerSample);
        for (var c = 0; c < channels; c++) {
            var source = planes[c];
            var plane = doubles.plane(c);
            if (floating) {
                for (var i = 0; i < frames; i++) {
                    plane[i] = Math.scalb((double) Float.intBitsToFloat(source[i]), floatScale);
                }
            } else {
                for (var i = 0; i < frames; i++) {
                    plane[i] = source[i] * scale;
                }
            }
        }
        doubles.setFrames(frames);
        output.accept(doubles);
    }

    /**
     * {@inheritDoc}
     *
     * <p>With {@link AudioDecoderOptions#verifyIntegrity()}, compares the stream's MD5 signature with the samples
     * decoded since the first frame.
     *
     * @throws MediaException.Malformed if the integrity check fails
     */
    @Override
    public void flushDoubles(Consumer<DoubleAudioBuffer> output) {
        Objects.requireNonNull(output, "output");
        checkIntegrity();
    }

    @Override
    public void close() {
    }

    /**
     * The binary exponent floating-point samples are scaled by to reach a full scale of 1.
     */
    private int floatScale;

    private void checkIntegrity() {
        if (!verify || md5 == null || !md5Valid || info.md5 == null) {
            return;
        }
        var digest = md5.digest();
        md5 = null;
        if (!Arrays.equals(digest, info.md5)) {
            throw new MediaException.Malformed("WavPack MD5 signature mismatch");
        }
    }

    private void decodePacket(Packet packet) {
        try {
            decodeFrame(packet);
        } catch (MediaException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new MediaException.Malformed("corrupt WavPack packet", exception);
        }
    }

    /**
     * Locates the blocks of a packet, rebuilding the headers of headerless packets, and decodes them.
     */
    private void decodeFrame(Packet packet) {
        var data = packet.data();
        byte[] blocks;
        int count;
        if (headerlessVersion != 0) {
            count = rebuildHeaderless(data);
            blocks = rebuilt;
        } else {
            count = locateBlocks(data);
            blocks = data;
        }
        if (count == 0) {
            frames = 0;
            return;
        }
        byte[] correction = null;
        var sideData = packet.sideData();
        var full = sideData.extra(CORRECTION_KEY).orElse(null);
        if (full != null) {
            correction = full;
            locateCorrection(full, blocks, count);
        } else {
            var stripped = sideData.extra(MATROSKA_CORRECTION_KEY).orElse(null);
            if (stripped != null) {
                rebuildCorrection(stripped, blocks, count);
                correction = rebuiltCorrection;
            }
        }
        var samples = 0;
        for (var b = 0; b < count && samples == 0; b++) {
            samples = WavPackStream.i32(blocks, blockStarts[b] + 20);
        }
        if (samples < 0) {
            throw new MediaException.Malformed("WavPack block of " + (samples & 0xFFFFFFFFL) + " samples");
        }
        LimitChecks.checkAllocation(limits, (long) samples * 8 * Math.max(channels, 2));
        if (scratch.length < samples * 2) {
            scratch = new int[samples * 2];
        }
        var offset = 0;
        var first = true;
        for (var b = 0; b < count; b++) {
            while (streams.size() <= b) {
                streams.add(new WavPackStream());
            }
            var stream = streams.get(b);
            var start = blockStarts[b];
            var blockSamples = WavPackStream.i32(blocks, start + 20);
            if (blockSamples != samples && blockSamples != 0) {
                throw new MediaException.Malformed("WavPack blocks of one frame differ in length");
            }
            var correctionStart = correction == null ? -1 : correctionStarts[b];
            stream.init(blocks, start, correctionStart < 0 ? null : correction, correctionStart, info);
            if (stream.blockSamples == 0) {
                continue;
            }
            if (first) {
                configure(stream, count, blocks);
                first = false;
            }
            stream.unpack(scratch);
            if (!stream.lossless()) {
                md5Valid = false;
            }
            var mono = (stream.flags & WavPackStream.MONO_FLAG) != 0;
            var needed = mono ? 1 : 2;
            if (offset >= channels) {
                throw new MediaException.Malformed("WavPack frame holds more channels than its stream");
            }
            for (var i = 0; i < samples; i++) {
                planes[offset][i] = scratch[mono ? i : 2 * i];
            }
            if (!mono && offset + 1 < channels) {
                for (var i = 0; i < samples; i++) {
                    planes[offset + 1][i] = scratch[2 * i + 1];
                }
            }
            offset += Math.min(needed, channels - offset);
        }
        if (first) {
            frames = 0;
            return;
        }
        if (offset != channels) {
            throw new MediaException.Malformed("WavPack frame holds " + offset + " of its " + channels + " channels");
        }
        frames = samples;
        if (dsd) {
            decimator.run(planes, channels, frames);
        } else if (!floating) {
            var shift = streamShift;
            if (shift != 0) {
                for (var c = 0; c < channels; c++) {
                    var plane = planes[c];
                    for (var i = 0; i < frames; i++) {
                        plane[i] >>= shift;
                    }
                }
            }
        }
        if (verify && md5 != null) {
            hash();
        }
    }

    /**
     * The shift of the coded integer samples within their bytes.
     */
    private int streamShift;

    /**
     * The bytes per sample of the stream.
     */
    private int bytesPerSample;

    /**
     * Sets the output format from the first audio block of a frame.
     */
    private void configure(WavPackStream stream, int count, byte[] blocks) {
        var flags = stream.flags;
        var rate = info.sampleRate;
        if (rate == 0) {
            var index = (flags & WavPackStream.SRATE_MASK) >>> WavPackStream.SRATE_LSB;
            rate = index == 15 ? (sampleRate > 0 ? sampleRate : 44100) : WavPackTables.SAMPLE_RATES[index];
        }
        dsd = (flags & WavPackStream.DSD_FLAG) != 0;
        floating = !dsd && (flags & WavPackStream.FLOAT_DATA) != 0;
        if (dsd) {
            rate *= Math.max(stream.dsd.multiplier(), 1);
        }
        var streamChannels = info.channels;
        if (streamChannels == 0) {
            streamChannels = 0;
            for (var b = 0; b < count; b++) {
                if (WavPackStream.i32(blocks, blockStarts[b] + 20) != 0) {
                    var blockFlags = WavPackStream.i32(blocks, blockStarts[b] + 24);
                    streamChannels += (blockFlags & WavPackStream.MONO_FLAG) != 0 ? 1 : 2;
                }
            }
        }
        LimitChecks.checkChannels(limits, streamChannels);
        if (!started || streamChannels != channels) {
            channels = streamChannels;
            layout = layoutOf(streamChannels, info.channels == 0
                    ? (streamChannels == 1 ? 4 : streamChannels == 2 ? 3 : 0) : info.channelMask);
        }
        sampleRate = rate;
        bytesPerSample = (flags & WavPackStream.BYTES_STORED) + 1;
        streamShift = (flags & WavPackStream.SHIFT_MASK) >>> WavPackStream.SHIFT_LSB;
        if (dsd) {
            bitsPerSample = 24;
            if (decimator == null || decimator.channels() != channels) {
                decimator = new WavPackDecimator(channels);
            }
        } else if (floating) {
            bitsPerSample = 32;
            floatScale = 127 - stream.floatNormExp();
        } else {
            bitsPerSample = Math.max(bytesPerSample * 8 - streamShift, 1);
        }
        var samples = stream.blockSamples;
        if (planes.length < channels || planes.length > 0 && planes[0].length < samples) {
            planes = new int[channels][Math.max(samples, 1)];
        }
        if (!started) {
            started = true;
            var index = headerlessVersion != 0 ? 0 : (WavPackStream.i32(blocks, blockStarts[0] + 16) & 0xFFFFFFFFL)
                    | (long) (blocks[blockStarts[0] + 10] & 0xFF) << 32;
            if (verify && index == 0) {
                try {
                    md5 = MessageDigest.getInstance("MD5");
                } catch (NoSuchAlgorithmException exception) {
                    throw new IllegalStateException("MD5 is a required MessageDigest", exception);
                }
            }
        }
    }

    /**
     * Feeds the decoded frame to the MD5 signature, in the byte layout of the stream's original samples.
     */
    private void hash() {
        if (dsd) {
            md5Valid = false;
            return;
        }
        var qmode = info.qmode;
        var bytes = floating ? 4 : bytesPerSample;
        var size = frames * channels * bytes;
        if (md5Bytes.length < size) {
            md5Bytes = new byte[size];
        }
        var bigEndian = (qmode & 1) != 0;
        var unsigned = !floating && ((qmode & 4) != 0 || (bytes == 1 && (qmode & 2) == 0));
        var shift = floating ? 0 : streamShift;
        var at = 0;
        for (var i = 0; i < frames; i++) {
            for (var c = 0; c < channels; c++) {
                var value = floating ? planes[c][i] : planes[c][i] << shift;
                if (unsigned) {
                    value += 1 << (bytes * 8 - 1);
                }
                for (var k = 0; k < bytes; k++) {
                    var b = (byte) (value >> (8 * (bigEndian ? bytes - 1 - k : k)));
                    md5Bytes[at++] = b;
                }
            }
        }
        md5.update(md5Bytes, 0, size);
    }

    private static ChannelLayout layoutOf(int channels, long mask) {
        var speakers = new ArrayList<Speaker>(channels);
        var positions = Speaker.values();
        for (var bit = 0; bit <= Speaker.TOP_BACK_RIGHT.ordinal() && speakers.size() < channels; bit++) {
            if ((mask & (1L << bit)) != 0) {
                speakers.add(positions[bit]);
            }
        }
        if (speakers.isEmpty() && mask == 0) {
            return ChannelLayout.defaultFor(channels);
        }
        while (speakers.size() < channels) {
            speakers.add(Speaker.UNKNOWN);
        }
        return new ChannelLayout(speakers);
    }

    private void ensureBlockCapacity(int count) {
        if (count >= blockStarts.length) {
            blockStarts = Arrays.copyOf(blockStarts, count * 2);
            correctionStarts = Arrays.copyOf(correctionStarts, count * 2);
        }
    }

    /**
     * Finds the complete blocks of a packet.
     *
     * @return the number of blocks
     */
    private int locateBlocks(byte[] data) {
        var count = 0;
        var position = 0;
        while (position < data.length) {
            checkHeader(data, position);
            WavPackStream.verifyBlock(data, position);
            ensureBlockCapacity(count);
            blockStarts[count++] = position;
            position += WavPackStream.i32(data, position + 4) + 8;
        }
        return count;
    }

    private static void checkHeader(byte[] data, int position) {
        if (data.length - position < HEADER || data[position] != 'w' || data[position + 1] != 'v'
                || data[position + 2] != 'p' || data[position + 3] != 'k') {
            throw new MediaException.Malformed("WavPack block header missing");
        }
        var size = WavPackStream.i32(data, position + 4) & 0xFFFFFFFFL;
        if (size < HEADER - 8 || size + 8 > data.length - position || (size & 1) != 0) {
            throw new MediaException.Malformed("WavPack block of size " + size + " runs past its packet");
        }
    }

    /**
     * Matches the complete correction blocks of a packet with its blocks, by position.
     */
    private void locateCorrection(byte[] data, byte[] blocks, int count) {
        var position = 0;
        for (var b = 0; b < count; b++) {
            correctionStarts[b] = -1;
        }
        var b = 0;
        while (position < data.length && b < count) {
            checkHeader(data, position);
            WavPackStream.verifyBlock(data, position);
            var samples = WavPackStream.i32(data, position + 20);
            while (b < count && WavPackStream.i32(blocks, blockStarts[b] + 20) == 0) {
                b++;
            }
            if (b == count) {
                break;
            }
            if (samples != WavPackStream.i32(blocks, blockStarts[b] + 20)) {
                throw new MediaException.Malformed("WavPack correction block does not match its block");
            }
            correctionStarts[b++] = position;
            position += WavPackStream.i32(data, position + 4) + 8;
        }
    }

    /**
     * Rebuilds complete blocks from a headerless (Matroska) packet into {@link #rebuilt}.
     *
     * @return the number of blocks
     */
    private int rebuildHeaderless(byte[] data) {
        if (data.length < 12) {
            throw new MediaException.Malformed("WavPack packet truncated");
        }
        var samples = WavPackStream.i32(data, 0);
        var firstFlags = WavPackStream.i32(data, 4);
        var single = (firstFlags & WavPackStream.INITIAL_BLOCK) != 0 && (firstFlags & WavPackStream.FINAL_BLOCK) != 0;
        var position = 4;
        var out = 0;
        var count = 0;
        var needed = data.length + 64L * HEADER;
        if (rebuilt.length < needed) {
            rebuilt = new byte[(int) Math.min(needed * 2, Integer.MAX_VALUE - 16)];
        }
        while (position < data.length) {
            if (data.length - position < (single ? 8 : 12)) {
                throw new MediaException.Malformed("WavPack block header truncated");
            }
            var flags = WavPackStream.i32(data, position);
            var crc = WavPackStream.i32(data, position + 4);
            position += 8;
            int size;
            if (single) {
                size = data.length - position;
            } else {
                size = WavPackStream.i32(data, position);
                position += 4;
            }
            if (size < 0 || size > data.length - position) {
                throw new MediaException.Malformed("WavPack block of size " + size + " runs past its packet");
            }
            if (rebuilt.length < out + HEADER + size) {
                rebuilt = Arrays.copyOf(rebuilt, (out + HEADER + size) * 2);
            }
            ensureBlockCapacity(count);
            blockStarts[count++] = out;
            writeHeader(rebuilt, out, size, samples, flags, crc);
            System.arraycopy(data, position, rebuilt, out + HEADER, size);
            out += HEADER + size;
            position += size;
            if ((flags & WavPackStream.FINAL_BLOCK) != 0 || single) {
                break;
            }
        }
        return count;
    }

    private void writeHeader(byte[] target, int at, int size, int samples, int flags, int crc) {
        target[at] = 'w';
        target[at + 1] = 'v';
        target[at + 2] = 'p';
        target[at + 3] = 'k';
        putInt(target, at + 4, size + HEADER - 8);
        target[at + 8] = (byte) headerlessVersion;
        target[at + 9] = (byte) (headerlessVersion >> 8);
        target[at + 10] = 0;
        target[at + 11] = 0;
        putInt(target, at + 12, -1);
        putInt(target, at + 16, 0);
        putInt(target, at + 20, samples);
        putInt(target, at + 24, flags);
        putInt(target, at + 28, crc);
    }

    /**
     * Rebuilds complete correction blocks from Matroska correction data into {@link #rebuiltCorrection}.
     */
    private void rebuildCorrection(byte[] data, byte[] blocks, int count) {
        var single = count == 1;
        var needed = data.length + (long) count * HEADER;
        if (rebuiltCorrection.length < needed) {
            rebuiltCorrection = new byte[(int) Math.min(needed * 2, Integer.MAX_VALUE - 16)];
        }
        var position = 0;
        var out = 0;
        for (var b = 0; b < count; b++) {
            correctionStarts[b] = -1;
            var start = blockStarts[b];
            var flags = WavPackStream.i32(blocks, start + 24);
            if (WavPackStream.i32(blocks, start + 20) == 0 || (flags & WavPackStream.HYBRID_FLAG) == 0) {
                continue;
            }
            if (data.length - position < (single ? 4 : 8)) {
                throw new MediaException.Malformed("WavPack correction data truncated");
            }
            var crc = WavPackStream.i32(data, position);
            position += 4;
            int size;
            if (single) {
                size = data.length - position;
            } else {
                size = WavPackStream.i32(data, position);
                position += 4;
            }
            if (size < 0 || size > data.length - position) {
                throw new MediaException.Malformed("WavPack correction block runs past its data");
            }
            System.arraycopy(blocks, start, rebuiltCorrection, out, HEADER);
            putInt(rebuiltCorrection, out + 4, size + HEADER - 8);
            putInt(rebuiltCorrection, out + 28, crc);
            System.arraycopy(data, position, rebuiltCorrection, out + HEADER, size);
            correctionStarts[b] = out;
            out += HEADER + size;
            position += size;
        }
    }

    private static void putInt(byte[] target, int at, int value) {
        target[at] = (byte) value;
        target[at + 1] = (byte) (value >> 8);
        target[at + 2] = (byte) (value >> 16);
        target[at + 3] = (byte) (value >> 24);
    }
}
