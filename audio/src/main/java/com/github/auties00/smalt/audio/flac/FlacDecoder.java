/* Ports libFLAC 1.4 (src/libFLAC/stream_decoder.c, lpc.c). Copyright (C) 2000-2009 Josh Coalson, (C) 2011-2025
   Xiph.Org Foundation; BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.audio.flac;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.audio.AudioDecoder;
import com.github.auties00.smalt.audio.AudioDecoderOptions;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.DoubleAudioBuffer;
import com.github.auties00.smalt.audio.IntegerAudioBuffer;
import com.github.auties00.smalt.internal.header.Crc16;
import com.github.auties00.smalt.internal.header.FlacFrameHeader;
import com.github.auties00.smalt.internal.header.FlacStreamInfo;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;

import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Decodes FLAC frames into planar float samples.
 *
 * <p>Every packet must hold exactly one frame, header to CRC-16, as
 * {@link com.github.auties00.smalt.container.flac.FlacDemuxer}, the Ogg FLAC mapping and the MP4
 * and Matroska demuxers deliver it. The decoder implements the whole
 * format of RFC 9639: constant, verbatim, fixed (orders 0 to 4) and linear-predictive (orders 1
 * to 32) subframes, wasted bits, Rice-coded residuals with 4- and 5-bit parameters, escaped
 * partitions (including zero-width ones) and every partition order, left/side, side/right and
 * mid/side stereo, 1 to 8 channels, 4- to 32-bit samples (including the 33-bit side channel of
 * 32-bit audio), every block size from 1 to 65535 and every sample rate code, including the codes
 * that defer to {@code STREAMINFO}.
 *
 * <p>The track's codec configuration may be the {@code "fLaC"} marker followed by metadata blocks
 * (the first being {@code STREAMINFO}), a bare {@code STREAMINFO} block with its four-byte
 * metadata header, or the 34-byte {@code STREAMINFO} body; it may be absent when every frame
 * header codes its own sample rate and sample size. The first frame must agree with
 * {@code STREAMINFO} on sample rate, channel count and sample size; later frames may change them,
 * and the change is reported through the {@link AudioBuffer#sampleRate()} and
 * {@link AudioBuffer#layout()} of each block. Samples are scaled to floats by
 * {@code 2^-(bitsPerSample - 1)}, so full scale maps to {@code [-1, 1)}; samples of up to 24 bits
 * are represented exactly, and wider samples are rounded to the nearest float. The decoded integers
 * themselves, bit for bit at every sample size, are the blocks of
 * {@link #decodeIntegers(Packet, Consumer)}, and {@link #decodeDoubles(Packet, Consumer)} scales them
 * to doubles exactly.
 *
 * <p>With {@link AudioDecoderOptions#verifyIntegrity()}, the decoder computes the MD5 signature of
 * the decoded samples as {@code STREAMINFO} defines it and checks it when flushed, if
 * {@code STREAMINFO} carries one and the first frame decoded starts the stream: the flush raises
 * {@link MediaException.Malformed} when the signature differs, when a frame is missing or repeated
 * between the first and the last, or when fewer or more samples than {@code STREAMINFO} declares
 * were decoded. Each frame is hashed in its own channel count and sample size, so a stream that
 * changes format is checked too. {@link #md5Verified()} tells whether a check passed.
 *
 * <p>A frame whose header CRC-8 or frame CRC-16 is wrong, whose subframes use a reserved code or
 * run past the frame, or which has the declared format but a block size above the
 * {@code STREAMINFO} maximum raises {@link MediaException.Malformed}. Bytes after a frame's
 * CRC-16 are ignored: when the CRC-16 does not match at the end of the packet, the frame's own end
 * (the byte-aligned end of its subframes plus the CRC) is checked instead, so junk that trails the
 * last frame of a file does not reject it.
 *
 * <p>Instances are not thread-safe.
 *
 * @implNote This implementation decodes residuals in place into the channel's sample array and
 * restores the prediction over them, as libFLAC's decoder does. Linear prediction accumulates in 64
 * bits, exact for every legal stream, and fixed prediction in 32-bit wrapping arithmetic, exact
 * whenever the restored sample fits the sample size; only the 33-bit side channel of 32-bit stereo
 * is decoded into {@code long} samples.
 */
public final class FlacDecoder implements AudioDecoder {
    /**
     * The largest channel count a FLAC frame can code.
     */
    private static final int MAX_CHANNELS = 8;

    /**
     * The largest predictor order of a linear-predictive subframe.
     */
    private static final int MAX_LPC_ORDER = 32;

    /**
     * The kernel performing decorrelation and float conversion.
     */
    private static final FlacKernel KERNEL = FlacKernel.INSTANCE;

    /**
     * The resource bounds the decoder enforces.
     */
    private final MediaLimits limits;

    /**
     * The signature of the decoded samples, or {@code null} when the signature is not checked.
     */
    private final FlacMd5 md5;

    /**
     * The state of the signature check, one of the {@code CHECK_} constants.
     */
    private int check;

    /**
     * The frame number (fixed blocking) or sample number (variable blocking) the next frame must
     * carry for the decoded samples to stay contiguous.
     */
    private long expectedNumber;

    /**
     * Whether the frames checked so far use variable blocking.
     */
    private boolean variableBlocking;

    /**
     * The number of samples per channel added to {@link #md5}.
     */
    private long hashedSamples;

    /**
     * Whether a flush checked the signature and found it equal.
     */
    private boolean verified;

    /**
     * The integer output block, recreated when the format or the sample arrays change, or
     * {@code null} before the first integer decode.
     */
    private IntegerAudioBuffer integers;

    /**
     * The double output block, recreated when the format or the needed capacity changes, or
     * {@code null} before the first double decode.
     */
    private DoubleAudioBuffer doubles;

    /**
     * The sampling rate of the last decoded frame.
     */
    private int lastRate;

    /**
     * The check state before the first frame.
     */
    private static final int CHECK_WAITING = 0;

    /**
     * The check state while the frames decoded start the stream and follow each other.
     */
    private static final int CHECK_CONTIGUOUS = 1;

    /**
     * The check state after a frame was missing or repeated.
     */
    private static final int CHECK_GAP = 2;

    /**
     * The check state of a decode that cannot be checked: no signature, a first frame after the
     * start, or a check already done.
     */
    private static final int CHECK_NONE = 3;

    /**
     * The stream parameters from the codec configuration, or {@code null} when absent.
     */
    private final FlacStreamInfo streamInfo;

    /**
     * The sampling rate reported before the first block.
     */
    private final int initialRate;

    /**
     * The channel layout reported before the first block, or {@code null} when unknown.
     */
    private final ChannelLayout declaredLayout;

    /**
     * The sample size the track declares, 0 when it declares none.
     */
    private final int declaredBits;

    /**
     * The reusable frame header parser.
     */
    private final FlacFrameHeader header;

    /**
     * The subframe bit reader.
     */
    private final FlacBitReader reader;

    /**
     * The quantized linear predictor coefficients of the current subframe.
     */
    private final int[] coefficients;

    /**
     * The integer samples of each channel of the last frame.
     */
    private int[][] samples;

    /**
     * The 33-bit side channel of 32-bit stereo frames.
     */
    private long[] wide;

    /**
     * The output block handed to consumers, recreated when the format changes.
     */
    private AudioBuffer buffer;

    /**
     * Whether a frame has been decoded.
     */
    private boolean started;

    /**
     * The sampling rate of the first decoded block.
     */
    private int firstRate;

    /**
     * The channel layout of the first decoded block.
     */
    private ChannelLayout firstLayout;

    /**
     * The block size of the last decoded frame.
     */
    private int lastBlockSize;

    /**
     * The sample size of the last decoded frame.
     */
    private int lastBitsPerSample;

    /**
     * The channel count of the last decoded frame.
     */
    private int lastChannels;

    /**
     * Creates a decoder for a FLAC track under {@link MediaLimits#DEFAULT}.
     *
     * @param track the track to decode; its codec is {@link Codec#FLAC}
     * @throws IllegalArgumentException if the track's codec is not FLAC
     * @throws MediaException.Malformed if the codec configuration is present but is not a valid
     * {@code STREAMINFO}
     * @throws MediaException.LimitExceeded if {@code STREAMINFO} exceeds {@link MediaLimits#DEFAULT}
     */
    public FlacDecoder(AudioTrack track) {
        this(track, AudioDecoderOptions.defaults());
    }

    /**
     * Constructs a decoder for a FLAC track under the limits of codec-independent options.
     *
     * <p>{@link AudioDecoderOptions#limits()} and {@link AudioDecoderOptions#verifyIntegrity()}
     * apply: the decoder always outputs the stream's own sampling rate, whatever
     * {@link AudioDecoderOptions#preferredSampleRate()} asks for. The channel count of
     * {@code STREAMINFO} and of every frame is checked against
     * {@link MediaLimits#maxChannels()}, and the sample buffers for the largest block against
     * {@link MediaLimits#maxBytes()}, before allocating.
     *
     * @param track the track to decode; its codec is {@link Codec#FLAC}
     * @param options the codec-independent options, of which the limits and the integrity check
     * apply
     * @throws IllegalArgumentException if the track's codec is not FLAC
     * @throws MediaException.Malformed if the codec configuration is present but is not a valid
     * {@code STREAMINFO}
     * @throws MediaException.LimitExceeded if {@code STREAMINFO} exceeds the options' limits
     */
    public FlacDecoder(AudioTrack track, AudioDecoderOptions options) {
        this(track, options.limits(), options.verifyIntegrity());
    }

    /**
     * Creates a decoder for a FLAC track under resource limits.
     *
     * @param track the track to decode
     * @param limits the resource limits
     * @param verify whether to check the {@code STREAMINFO} signature
     */
    private FlacDecoder(AudioTrack track, MediaLimits limits, boolean verify) {
        Objects.requireNonNull(track, "track");
        this.limits = limits;
        if (track.codec() != Codec.FLAC) {
            throw new IllegalArgumentException("not a FLAC track: " + track.codec());
        }
        this.streamInfo = track.codecConfig().map(FlacDecoder::parseConfig).orElse(null);
        if (streamInfo != null) {
            LimitChecks.checkChannels(limits, streamInfo.channels());
        }
        this.initialRate = streamInfo != null ? streamInfo.sampleRate() : Math.max(track.sampleRate(), 0);
        var layout = track.layout().orElse(null);
        if (streamInfo != null && (layout == null || layout.channels() != streamInfo.channels())) {
            layout = ChannelLayout.defaultFor(streamInfo.channels());
        }
        this.declaredLayout = layout;
        this.declaredBits = track.bitsPerSample() >= 4 && track.bitsPerSample() <= 32 ? track.bitsPerSample() : 0;
        this.header = new FlacFrameHeader();
        this.reader = new FlacBitReader();
        this.coefficients = new int[MAX_LPC_ORDER];
        var capacity = streamInfo != null ? streamInfo.maxBlockSize() : 4096;
        LimitChecks.checkAllocation(limits, (long) MAX_CHANNELS * capacity * Integer.BYTES);
        this.samples = new int[MAX_CHANNELS][capacity];
        this.wide = new long[0];
        var checked = verify && streamInfo != null && streamInfo.hasMd5();
        this.md5 = checked ? new FlacMd5(capacity * streamInfo.channels() * ((streamInfo.bitsPerSample() + 7) / 8)) : null;
        this.check = checked ? CHECK_WAITING : CHECK_NONE;
    }

    /**
     * Extracts {@code STREAMINFO} from a codec configuration.
     *
     * @param config the configuration bytes
     * @return the parsed stream parameters
     * @throws MediaException.Malformed if the configuration holds no valid {@code STREAMINFO}
     */
    private static FlacStreamInfo parseConfig(byte[] config) {
        var offset = 0;
        if (config.length >= 4 && config[0] == 'f' && config[1] == 'L' && config[2] == 'a' && config[3] == 'C') {
            offset = 4;
        }
        if (offset == 0 && config.length == FlacStreamInfo.SIZE) {
            return FlacStreamInfo.parse(config, 0);
        }
        if (config.length - offset < 4 + FlacStreamInfo.SIZE) {
            throw new MediaException.Malformed("FLAC codec configuration of " + config.length + " bytes holds no STREAMINFO");
        }
        var type = config[offset] & 0x7F;
        var length = (config[offset + 1] & 0xFF) << 16 | (config[offset + 2] & 0xFF) << 8 | config[offset + 3] & 0xFF;
        if (type != 0 || length != FlacStreamInfo.SIZE) {
            throw new MediaException.Malformed("FLAC codec configuration does not start with STREAMINFO");
        }
        return FlacStreamInfo.parse(config, offset + 4);
    }

    @Override
    public int sampleRate() {
        return started ? firstRate : initialRate;
    }

    @Override
    public ChannelLayout layout() {
        if (started) {
            return firstLayout;
        }
        return declaredLayout != null ? declaredLayout : ChannelLayout.MONO;
    }

    @Override
    public void decode(Packet packet, Consumer<AudioBuffer> output) {
        Objects.requireNonNull(packet, "packet");
        Objects.requireNonNull(output, "output");
        decodePacket(packet);
        var blockSize = lastBlockSize;
        var block = outputBuffer(lastChannels, lastRate, blockSize);
        var scale = Math.scalb(1f, 1 - lastBitsPerSample);
        for (var channel = 0; channel < lastChannels; channel++) {
            KERNEL.toFloat(samples[channel], block.plane(channel), blockSize, scale);
        }
        block.setFrames(blockSize);
        output.accept(block);
    }

    /**
     * {@inheritDoc}
     *
     * <p>A FLAC decoder buffers no samples between packets, so no block is output; the flush only
     * checks the {@code STREAMINFO} signature when requested.
     */
    @Override
    public void flush(Consumer<AudioBuffer> output) {
        Objects.requireNonNull(output, "output");
        checkSignature();
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
     * @return the sample size of {@code STREAMINFO}, or of the track when the codec configuration is
     * absent, or 0 when neither declares one
     */
    @Override
    public int bitsPerSample() {
        if (streamInfo != null) {
            return streamInfo.bitsPerSample();
        }
        return declaredBits;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Every sample fits the frame's sample size: a frame of fewer than 32 bits whose prediction or
     * stereo restoration leaves its range, which no valid stream does, raises
     * {@link MediaException.Malformed}. A 32-bit frame keeps the low 32 bits of such a sample, as
     * its float output does.
     *
     * @throws MediaException.Malformed {@inheritDoc}
     * @throws MediaException.Unsupported {@inheritDoc}
     */
    @Override
    public void decodeIntegers(Packet packet, Consumer<IntegerAudioBuffer> output) {
        Objects.requireNonNull(packet, "packet");
        Objects.requireNonNull(output, "output");
        decodePacket(packet);
        checkRange();
        var block = integers;
        if (block == null || block.channels() != lastChannels || block.sampleRate() != lastRate
                || block.bitsPerSample() != lastBitsPerSample || block.plane(0) != samples[0]) {
            block = new IntegerAudioBuffer(layoutFor(lastChannels), lastRate, lastBitsPerSample,
                    Arrays.copyOf(samples, lastChannels), 0);
            integers = block;
        }
        block.setFrames(lastBlockSize);
        output.accept(block);
    }

    /**
     * {@inheritDoc}
     *
     * <p>A FLAC decoder buffers no samples between packets, so no block is output; the flush only
     * checks the {@code STREAMINFO} signature when requested.
     */
    @Override
    public void flushIntegers(Consumer<IntegerAudioBuffer> output) {
        Objects.requireNonNull(output, "output");
        checkSignature();
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
        var blockSize = lastBlockSize;
        var block = doubles;
        if (block == null || block.channels() != lastChannels || block.sampleRate() != lastRate
                || block.capacity() < blockSize) {
            var capacity = Math.max(blockSize, streamInfo != null ? streamInfo.maxBlockSize() : 0);
            block = new DoubleAudioBuffer(layoutFor(lastChannels), lastRate, capacity);
            doubles = block;
        }
        var scale = Math.scalb(1.0, 1 - lastBitsPerSample);
        for (var channel = 0; channel < lastChannels; channel++) {
            var source = samples[channel];
            var target = block.plane(channel);
            for (var i = 0; i < blockSize; i++) {
                target[i] = source[i] * scale;
            }
        }
        block.setFrames(blockSize);
        output.accept(block);
    }

    /**
     * {@inheritDoc}
     *
     * <p>A FLAC decoder buffers no samples between packets, so no block is output; the flush only
     * checks the {@code STREAMINFO} signature when requested.
     */
    @Override
    public void flushDoubles(Consumer<DoubleAudioBuffer> output) {
        Objects.requireNonNull(output, "output");
        checkSignature();
    }

    /**
     * Returns whether a flush compared the {@code STREAMINFO} MD5 signature with the decoded samples
     * and found them equal.
     *
     * <p>It is {@code false} before the flush, without {@link AudioDecoderOptions#verifyIntegrity()},
     * and when the decode could not be checked: {@code STREAMINFO} absent or without a signature, or
     * a first frame after the start of the stream.
     *
     * @return {@code true} once the signature was checked and matched
     */
    public boolean md5Verified() {
        return verified;
    }

    /**
     * Rejects a frame whose samples do not fit its sample size.
     *
     * @throws MediaException.Malformed if a sample of the last frame is out of range
     */
    private void checkRange() {
        var bits = lastBitsPerSample;
        if (bits == 32) {
            return;
        }
        var min = -(1 << (bits - 1));
        var max = (1 << (bits - 1)) - 1;
        for (var channel = 0; channel < lastChannels; channel++) {
            var plane = samples[channel];
            var low = 0;
            var high = 0;
            for (var i = 0; i < lastBlockSize; i++) {
                low = Math.min(low, plane[i]);
                high = Math.max(high, plane[i]);
            }
            if (low < min || high > max) {
                throw new MediaException.Malformed("FLAC frame decodes to a sample of more than " + bits + " bits");
            }
        }
    }

    /**
     * Decodes one packet into {@link #samples}, wrapping unchecked failures.
     *
     * @param packet the packet to decode, one frame
     * @throws MediaException.Malformed if the frame is corrupt
     */
    private void decodePacket(Packet packet) {
        try {
            decodeFrame(packet.data());
        } catch (MediaException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new MediaException.Malformed("corrupt FLAC frame", exception);
        }
    }

    /**
     * Ends the signature check of a decode that started the stream, as a flush does.
     *
     * @throws MediaException.Malformed if frames are missing, the sample count differs from
     * {@code STREAMINFO}'s, or the signature differs
     */
    private void checkSignature() {
        var state = check;
        if (state == CHECK_WAITING || state == CHECK_NONE) {
            return;
        }
        check = CHECK_NONE;
        if (state == CHECK_GAP) {
            throw new MediaException.Malformed("FLAC frames are missing or repeated, so the MD5 signature cannot match");
        }
        var total = streamInfo.totalSamples();
        if (total != 0 && hashedSamples != total) {
            throw new MediaException.Malformed("FLAC stream of " + total + " samples ends after " + hashedSamples);
        }
        var digest = md5.digest();
        if (!Arrays.equals(digest, streamInfo.md5())) {
            throw new MediaException.Malformed("FLAC MD5 signature mismatch: STREAMINFO "
                    + HexFormat.of().formatHex(streamInfo.md5()) + ", decoded " + HexFormat.of().formatHex(digest));
        }
        verified = true;
    }

    /**
     * Adds the last decoded frame to the signature check, in the frame's own channel count and
     * sample size.
     *
     * @param channels the frame's channel count
     * @param bitsPerSample the frame's sample size
     * @param blockSize the frame's samples per channel
     */
    private void track(int channels, int bitsPerSample, int blockSize) {
        var number = header.number();
        if (check == CHECK_WAITING) {
            if (number != 0) {
                check = CHECK_NONE;
                return;
            }
            check = CHECK_CONTIGUOUS;
            variableBlocking = header.variableBlocking();
        } else if (check == CHECK_CONTIGUOUS
                && (header.variableBlocking() != variableBlocking || number != expectedNumber)) {
            check = CHECK_GAP;
        }
        if (check != CHECK_CONTIGUOUS) {
            return;
        }
        expectedNumber = variableBlocking ? number + blockSize : number + 1;
        md5.update(samples, channels, blockSize, bitsPerSample);
        hashedSamples += blockSize;
    }

    /**
     * Returns the integer samples of the last decoded frame, one array per channel; a test hook
     * for the {@code STREAMINFO} MD5 signature, which is defined over integer samples.
     *
     * <p>Only the first {@link #lastBlockSize()} elements of the first {@link #lastChannels()}
     * arrays are valid.
     *
     * @return the sample arrays, not copies
     */
    int[][] lastSamples() {
        return samples;
    }

    /**
     * Returns the block size of the last decoded frame; a test hook.
     *
     * @return the number of samples per channel
     */
    int lastBlockSize() {
        return lastBlockSize;
    }

    /**
     * Returns the sample size of the last decoded frame; a test hook.
     *
     * @return the size in bits
     */
    int lastBitsPerSample() {
        return lastBitsPerSample;
    }

    /**
     * Returns the channel count of the last decoded frame; a test hook.
     *
     * @return the number of channels
     */
    int lastChannels() {
        return lastChannels;
    }

    /**
     * Decodes one frame into {@link #samples}, recording its format in the {@code last} fields.
     *
     * @param data the frame bytes
     * @throws MediaException.Malformed if the frame is corrupt or disagrees with
     * {@code STREAMINFO}
     * @throws MediaException.LimitExceeded if the frame exceeds the limits
     */
    private void decodeFrame(byte[] data) {
        var length = data.length;
        if (!header.parse(data, 0, length)) {
            throw new MediaException.Malformed("invalid FLAC frame header");
        }
        if (length < header.size() + 2) {
            throw new MediaException.Malformed("FLAC frame of " + length + " bytes is truncated");
        }
        var stored = (data[length - 2] & 0xFF) << 8 | data[length - 1] & 0xFF;
        var crcMatches = Crc16.crc16(0, data, 0, length - 2) == stored;
        var rate = header.sampleRate();
        var bitsPerSample = header.bitsPerSample();
        if (rate == 0 || bitsPerSample == 0) {
            if (streamInfo == null) {
                throw new MediaException.Malformed("FLAC frame defers to an absent STREAMINFO");
            }
            rate = rate == 0 ? streamInfo.sampleRate() : rate;
            bitsPerSample = bitsPerSample == 0 ? streamInfo.bitsPerSample() : bitsPerSample;
        }
        var channels = header.channels();
        var blockSize = header.blockSize();
        LimitChecks.checkChannels(limits, channels);
        if (streamInfo != null) {
            if (!started && (rate != streamInfo.sampleRate() || channels != streamInfo.channels()
                    || bitsPerSample != streamInfo.bitsPerSample())) {
                throw new MediaException.Malformed("first FLAC frame (" + rate + " Hz, " + channels + " channels, "
                        + bitsPerSample + " bits) disagrees with STREAMINFO (" + streamInfo.sampleRate() + " Hz, "
                        + streamInfo.channels() + " channels, " + streamInfo.bitsPerSample() + " bits)");
            }
            if (blockSize > streamInfo.maxBlockSize() && rate == streamInfo.sampleRate()
                    && channels == streamInfo.channels() && bitsPerSample == streamInfo.bitsPerSample()) {
                throw new MediaException.Malformed("FLAC block size " + blockSize + " exceeds the STREAMINFO maximum "
                        + streamInfo.maxBlockSize());
            }
        }
        if (samples[0].length < blockSize) {
            LimitChecks.checkAllocation(limits, (long) MAX_CHANNELS * blockSize * Integer.BYTES);
            samples = new int[MAX_CHANNELS][blockSize];
        }
        reader.reset(data, header.size(), (crcMatches ? length - 2 : length) - header.size());
        try {
            decodeSubframes(channels, blockSize, bitsPerSample);
        } catch (MediaException.Malformed failure) {
            if (crcMatches) {
                throw failure;
            }
            throw new MediaException.Malformed("FLAC frame CRC-16 mismatch", failure);
        }
        if (!crcMatches) {
            // the last frame of a file may be followed by bytes that are not part of it: the frame ends
            // at the byte-aligned end of its subframes, followed by its CRC-16
            var end = header.size() + (int) ((reader.bitPosition() + 7) >>> 3) + 2;
            if (reader.overrun() || end > length
                    || Crc16.crc16(0, data, 0, end - 2) != ((data[end - 2] & 0xFF) << 8 | data[end - 1] & 0xFF)) {
                throw new MediaException.Malformed("FLAC frame CRC-16 mismatch");
            }
        } else if (reader.overrun()) {
            throw new MediaException.Malformed("FLAC subframes run past the end of the frame");
        }
        if (!started) {
            started = true;
            firstRate = rate;
            firstLayout = layoutFor(channels);
        }
        lastBlockSize = blockSize;
        lastBitsPerSample = bitsPerSample;
        lastChannels = channels;
        lastRate = rate;
        if (check < CHECK_GAP) {
            track(channels, bitsPerSample, blockSize);
        }
    }

    /**
     * Returns the layout of the frames of a channel count: the track's when it has that many
     * channels, the default layout otherwise.
     *
     * @param channels the channel count
     * @return the layout
     */
    private ChannelLayout layoutFor(int channels) {
        return declaredLayout != null && declaredLayout.channels() == channels
                ? declaredLayout
                : ChannelLayout.defaultFor(channels);
    }

    /**
     * Decodes the subframes of the current frame into {@link #samples} and undoes the stereo
     * decorrelation.
     *
     * @param channels the channel count
     * @param blockSize the number of samples per channel
     * @param bitsPerSample the sample size
     * @throws MediaException.Malformed if a subframe is invalid
     */
    private void decodeSubframes(int channels, int blockSize, int bitsPerSample) {
        var assignment = header.channelAssignment();
        if (assignment < 8) {
            for (var channel = 0; channel < channels; channel++) {
                decodeSubframe(samples[channel], blockSize, bitsPerSample);
            }
        } else if (bitsPerSample == 32) {
            decodeWideStereo(assignment, blockSize);
        } else {
            var left = samples[0];
            var right = samples[1];
            decodeSubframe(left, blockSize, bitsPerSample + (assignment == 9 ? 1 : 0));
            decodeSubframe(right, blockSize, bitsPerSample + (assignment == 9 ? 0 : 1));
            switch (assignment) {
                case 8 -> KERNEL.leftSide(left, right, blockSize);
                case 9 -> KERNEL.sideRight(left, right, blockSize);
                default -> KERNEL.midSide(left, right, blockSize);
            }
        }
    }

    /**
     * Returns the output block for a frame format, recreating it when the format or the needed
     * capacity changes.
     *
     * @param channels the channel count
     * @param rate the sampling rate
     * @param blockSize the number of samples per channel
     * @return the block
     */
    private AudioBuffer outputBuffer(int channels, int rate, int blockSize) {
        if (buffer == null || buffer.channels() != channels || buffer.sampleRate() != rate || buffer.capacity() < blockSize) {
            var capacity = Math.max(blockSize, streamInfo != null ? streamInfo.maxBlockSize() : 0);
            buffer = new AudioBuffer(layoutFor(channels), rate, capacity);
        }
        return buffer;
    }

    /**
     * Decodes one subframe into a 32-bit sample array.
     *
     * @param out the destination, at least {@code blockSize} long
     * @param blockSize the number of samples
     * @param bitsPerSample the subframe's sample size, side-channel bit included, at most 32
     * @throws MediaException.Malformed if the subframe uses a reserved code or is inconsistent
     */
    private void decodeSubframe(int[] out, int blockSize, int bitsPerSample) {
        var type = subframeType();
        var wasted = wastedBits(bitsPerSample);
        var bits = bitsPerSample - wasted;
        if (type == 0) {
            Arrays.fill(out, 0, blockSize, reader.readSigned(bits));
        } else if (type == 1) {
            reader.readSignedBlock(out, 0, blockSize, bits);
        } else if (type >= 8 && type <= 12) {
            var order = type - 8;
            checkOrder(order, blockSize);
            reader.readSignedBlock(out, 0, order, bits);
            readResidual(out, blockSize, order);
            restoreFixed(out, blockSize, order);
        } else if (type >= 32) {
            var order = type - 31;
            checkOrder(order, blockSize);
            reader.readSignedBlock(out, 0, order, bits);
            var shift = readPredictor(order);
            readResidual(out, blockSize, order);
            FlacLpc.restore(out, blockSize, coefficients, order, shift);
        } else {
            throw new MediaException.Malformed("reserved FLAC subframe type " + type);
        }
        if (wasted > 0) {
            for (var i = 0; i < blockSize; i++) {
                out[i] <<= wasted;
            }
        }
    }

    /**
     * Decodes one subframe of up to 33 bits into a 64-bit sample array.
     *
     * @param out the destination, at least {@code blockSize} long
     * @param residual a scratch array for the residual, at least {@code blockSize} long
     * @param blockSize the number of samples
     * @param bitsPerSample the subframe's sample size, side-channel bit included, at most 33
     * @throws MediaException.Malformed if the subframe uses a reserved code or is inconsistent
     */
    private void decodeWideSubframe(long[] out, int[] residual, int blockSize, int bitsPerSample) {
        var type = subframeType();
        var wasted = wastedBits(bitsPerSample);
        var bits = bitsPerSample - wasted;
        if (type == 0) {
            Arrays.fill(out, 0, blockSize, reader.readSignedLong(bits));
        } else if (type == 1) {
            for (var i = 0; i < blockSize; i++) {
                out[i] = reader.readSignedLong(bits);
            }
        } else if (type >= 8 && type <= 12) {
            var order = type - 8;
            checkOrder(order, blockSize);
            for (var i = 0; i < order; i++) {
                out[i] = reader.readSignedLong(bits);
            }
            readResidual(residual, blockSize, order);
            for (var i = order; i < blockSize; i++) {
                long prediction = switch (order) {
                    case 0 -> 0;
                    case 1 -> out[i - 1];
                    case 2 -> 2 * out[i - 1] - out[i - 2];
                    case 3 -> 3 * out[i - 1] - 3 * out[i - 2] + out[i - 3];
                    default -> 4 * out[i - 1] - 6 * out[i - 2] + 4 * out[i - 3] - out[i - 4];
                };
                out[i] = residual[i] + prediction;
            }
        } else if (type >= 32) {
            var order = type - 31;
            checkOrder(order, blockSize);
            for (var i = 0; i < order; i++) {
                out[i] = reader.readSignedLong(bits);
            }
            var shift = readPredictor(order);
            readResidual(residual, blockSize, order);
            for (var i = order; i < blockSize; i++) {
                var sum = 0L;
                for (var j = 0; j < order; j++) {
                    sum += coefficients[j] * out[i - 1 - j];
                }
                out[i] = residual[i] + (sum >> shift);
            }
        } else {
            throw new MediaException.Malformed("reserved FLAC subframe type " + type);
        }
        if (wasted > 0) {
            for (var i = 0; i < blockSize; i++) {
                out[i] <<= wasted;
            }
        }
    }

    /**
     * Decodes a 32-bit stereo frame whose side channel needs 33 bits.
     *
     * @param assignment the channel assignment code, from 8 to 10
     * @param blockSize the number of samples per channel
     */
    private void decodeWideStereo(int assignment, int blockSize) {
        if (wide.length < blockSize) {
            wide = new long[blockSize];
        }
        var left = samples[0];
        var right = samples[1];
        var side = wide;
        var scratch = samples[2];
        switch (assignment) {
            case 8 -> {
                decodeSubframe(left, blockSize, 32);
                decodeWideSubframe(side, scratch, blockSize, 33);
                for (var i = 0; i < blockSize; i++) {
                    right[i] = (int) (left[i] - side[i]);
                }
            }
            case 9 -> {
                decodeWideSubframe(side, scratch, blockSize, 33);
                decodeSubframe(right, blockSize, 32);
                for (var i = 0; i < blockSize; i++) {
                    left[i] = (int) (side[i] + right[i]);
                }
            }
            default -> {
                decodeSubframe(left, blockSize, 32);
                decodeWideSubframe(side, scratch, blockSize, 33);
                for (var i = 0; i < blockSize; i++) {
                    var s = side[i];
                    var mid = ((long) left[i] << 1) | (s & 1);
                    left[i] = (int) ((mid + s) >> 1);
                    right[i] = (int) ((mid - s) >> 1);
                }
            }
        }
    }

    /**
     * Reads a subframe header's zero padding bit and type code.
     *
     * @return the six-bit type code
     * @throws MediaException.Malformed if the padding bit is set
     */
    private int subframeType() {
        if (reader.read(1) != 0) {
            throw new MediaException.Malformed("FLAC subframe padding bit is set");
        }
        return reader.read(6);
    }

    /**
     * Reads the wasted-bits flag and count of a subframe header.
     *
     * @param bitsPerSample the subframe's sample size before removing wasted bits
     * @return the number of wasted bits, zero when the flag is clear
     * @throws MediaException.Malformed if every bit of the sample would be wasted
     */
    private int wastedBits(int bitsPerSample) {
        if (reader.read(1) == 0) {
            return 0;
        }
        var wasted = reader.readUnary() + 1;
        if (wasted >= bitsPerSample) {
            throw new MediaException.Malformed("FLAC subframe wastes " + wasted + " of " + bitsPerSample + " bits");
        }
        return wasted;
    }

    /**
     * Checks that a predictor order leaves room for the warm-up samples.
     *
     * @param order the predictor order
     * @param blockSize the number of samples
     * @throws MediaException.Malformed if the order exceeds the block size
     */
    private static void checkOrder(int order, int blockSize) {
        if (order > blockSize) {
            throw new MediaException.Malformed("FLAC predictor order " + order + " exceeds the block size " + blockSize);
        }
    }

    /**
     * Reads the precision, shift and coefficients of a linear predictor into
     * {@link #coefficients}.
     *
     * @param order the predictor order
     * @return the right shift applied to the prediction
     * @throws MediaException.Malformed if the precision code is reserved or the shift is negative
     */
    private int readPredictor(int order) {
        var precision = reader.read(4) + 1;
        if (precision == 16) {
            throw new MediaException.Malformed("reserved FLAC coefficient precision");
        }
        var shift = reader.readSigned(5);
        if (shift < 0) {
            throw new MediaException.Malformed("negative FLAC predictor shift " + shift);
        }
        for (var j = 0; j < order; j++) {
            coefficients[j] = reader.readSigned(precision);
        }
        return shift;
    }

    /**
     * Reads the Rice-coded residual of a predicted subframe into {@code out[order, blockSize)}.
     *
     * @param out the destination
     * @param blockSize the number of samples
     * @param order the predictor order, the number of warm-up samples before the residual
     * @throws MediaException.Malformed if the coding method is reserved or the partitioning does
     * not fit the block
     */
    private void readResidual(int[] out, int blockSize, int order) {
        var method = reader.read(2);
        if (method > 1) {
            throw new MediaException.Malformed("reserved FLAC residual coding method " + method);
        }
        var parameterBits = method == 0 ? 4 : 5;
        var escape = method == 0 ? 15 : 31;
        var partitionOrder = reader.read(4);
        var partitionSize = blockSize >> partitionOrder;
        if (partitionSize << partitionOrder != blockSize || partitionSize < order) {
            throw new MediaException.Malformed("FLAC partition order " + partitionOrder + " does not fit block size "
                    + blockSize + " with predictor order " + order);
        }
        var partitions = 1 << partitionOrder;
        var position = order;
        for (var partition = 0; partition < partitions; partition++) {
            var end = (partition + 1) * partitionSize;
            var parameter = reader.read(parameterBits);
            if (parameter == escape) {
                reader.readSignedBlock(out, position, end, reader.read(5));
            } else {
                reader.readRice(out, position, end, parameter);
            }
            position = end;
        }
    }

    /**
     * Restores a fixed-predictor subframe in place: {@code out[order, blockSize)} holds the
     * residual on entry and the samples on return.
     *
     * @param out the warm-up samples followed by the residual
     * @param blockSize the number of samples
     * @param order the predictor order, from 0 to 4
     */
    private static void restoreFixed(int[] out, int blockSize, int order) {
        switch (order) {
            case 1 -> {
                for (var i = 1; i < blockSize; i++) {
                    out[i] += out[i - 1];
                }
            }
            case 2 -> {
                for (var i = 2; i < blockSize; i++) {
                    out[i] += 2 * out[i - 1] - out[i - 2];
                }
            }
            case 3 -> {
                for (var i = 3; i < blockSize; i++) {
                    out[i] += 3 * (out[i - 1] - out[i - 2]) + out[i - 3];
                }
            }
            case 4 -> {
                for (var i = 4; i < blockSize; i++) {
                    out[i] += 4 * (out[i - 1] + out[i - 3]) - 6 * out[i - 2] - out[i - 4];
                }
            }
            default -> {
            }
        }
    }
}
