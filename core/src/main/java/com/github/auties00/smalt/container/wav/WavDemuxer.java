package com.github.auties00.smalt.container.wav;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.container.DemuxerOptions;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.Speaker;
import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.container.Demuxer;
import com.github.auties00.smalt.container.internal.WaveFormat;
import com.github.auties00.smalt.container.mpeg.MpegAudioDemuxer;
import com.github.auties00.smalt.container.mpeg.internal.Id3v2;
import com.github.auties00.smalt.internal.io.ForwardOnlySource;
import com.github.auties00.smalt.internal.io.ReadAheadCache;
import com.github.auties00.smalt.internal.io.SlicedSource;
import com.github.auties00.smalt.internal.io.SourceReader;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.Track;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Reads RIFF WAVE files, including the 64-bit RF64 and BW64 variants and big-endian RIFX.
 *
 * <p>The {@code fmt } chunk may use {@code WAVE_FORMAT_PCM} (8-bit unsigned, 16, 24 and 32-bit
 * signed integers), {@code WAVE_FORMAT_IEEE_FLOAT} (32 and 64-bit), {@code WAVE_FORMAT_ALAW},
 * {@code WAVE_FORMAT_MULAW}, or {@code WAVE_FORMAT_EXTENSIBLE} wrapping any of those; the
 * extensible channel mask becomes the track's {@link ChannelLayout}, one {@link Speaker} per set
 * bit in mask order, with {@link Speaker#UNKNOWN} for channels beyond the mask. The sample
 * container size comes from the block alignment, so 24-bit samples in 32-bit containers decode as
 * left-justified 32-bit samples. A {@code data} chunk holding MPEG audio
 * ({@code WAVE_FORMAT_MPEG} or {@code WAVE_FORMAT_MPEGLAYER3}) is handed to a
 * {@link MpegAudioDemuxer}, whose track, frames and gapless information this demuxer exposes
 * unchanged. Any other format tag raises {@link MediaException.Unsupported} naming the tag.
 *
 * <p>Chunks are walked in order, honoring the pad byte after odd-sized chunks (and tolerating
 * writers that omit it). A {@code data} chunk whose size is {@code 0} or {@code 0xFFFFFFFF}
 * outside RF64 is a streaming header and extends to the end of the file, as does a size larger
 * than the file. RF64/BW64 sizes come from the {@code ds64} chunk. {@code LIST INFO} entries and
 * an embedded {@code id3 } tag become {@link #metadata()}.
 *
 * <p>Packets hold the largest power of two of frames that lasts at most 100 ms (4096 frames at
 * 44.1 and 48 kHz), capped at 1 MiB, with timestamps in samples.
 * A source that is not in memory is read in blocks of {@value #READ_BLOCK} bytes that never
 * extend past the sample data, rather than once per packet.
 *
 * <p>A source that reads its input once, front to back (a stream), is told after every packet which
 * bytes are no longer needed, and the chunks after {@code data} are read only when they lie within
 * the source's memory budget.
 */
public final class WavDemuxer implements Demuxer {
    /**
     * The largest packet, in bytes.
     */
    private static final int MAX_PACKET_BYTES = 1 << 20;

    /**
     * The size of the blocks the sample data of a source that is not in memory is read in.
     *
     * @implNote This implementation reads 256 KiB at a time, 16 packets of 16 KiB at 44.1 and 48 kHz stereo, which
     * turns one positional read per packet into one per 16 packets for a file source at the cost of one copy from the
     * block into each packet.
     */
    private static final int READ_BLOCK = 256 * 1024;

    /**
     * The largest {@code LIST} chunk whose entries are decoded into metadata.
     */
    private static final int MAX_LIST_SIZE = 1 << 20;

    /**
     * The source read.
     */
    private final MediaSource source;

    /**
     * The resource bounds.
     */
    private final MediaLimits limits;

    /**
     * The single audio track.
     */
    private final AudioTrack track;

    /**
     * The demuxer of an MPEG audio stream stored in the {@code data} chunk, or {@code null} for
     * PCM.
     */
    private final MpegAudioDemuxer mpeg;

    /**
     * The track list exposed by {@link #tracks()}.
     */
    private final List<Track> tracks;

    /**
     * The container metadata.
     */
    private final Map<String, String> metadata;

    /**
     * The offset of the first sample byte.
     */
    private final long dataStart;

    /**
     * The offset of the end of the sample data, or {@code -1} when it extends to an unknown end.
     */
    private final long dataEnd;

    /**
     * The size of one interleaved frame in bytes.
     */
    private final int frameSize;

    /**
     * The number of frames per packet.
     */
    private final int packetFrames;

    /**
     * The offset of the next packet.
     */
    private long position;

    /**
     * The read-ahead window over the sample data, or {@code null} for MPEG audio.
     */
    private final ReadAheadCache data;

    /**
     * The index of the first frame of the next packet.
     */
    private long frameIndex;

    /**
     * Whether the end of the sample data was reached.
     */
    private boolean finished;

    /**
     * Constructs a demuxer over {@code source} with {@link DemuxerOptions#defaults()}.
     *
     * @param source the input, not closed by this demuxer
     * @throws MediaException.Malformed if the file is not a valid WAVE file
     * @throws MediaException.Unsupported if the file uses a format tag other than PCM, float or G.711
     * @throws MediaException.LimitExceeded if the channel count exceeds the limits
     * @throws MediaException.Io if reading fails
     */
    public WavDemuxer(MediaSource source) {
        this(source, DemuxerOptions.defaults());
    }

    /**
     * Constructs a demuxer over {@code source} that enforces the limits of {@code options}.
     *
     * @param source the input, not closed by this demuxer
     * @param options the demuxing options
     * @throws MediaException.Malformed if the file is not a valid WAVE file
     * @throws MediaException.Unsupported if the file uses a format tag other than PCM, float or G.711
     * @throws MediaException.LimitExceeded if the channel count exceeds the limits
     * @throws MediaException.Io if reading fails
     */
    public WavDemuxer(MediaSource source, DemuxerOptions options) {
        this(source, options.limits());
    }

    private WavDemuxer(MediaSource source, MediaLimits limits) {
        this.source = Objects.requireNonNull(source, "source");
        this.limits = Objects.requireNonNull(limits, "limits");
        try {
            var header = new Header();
            parse(new SourceReader(source, 4096), header);
            if (header.mpeg) {
                var length = header.dataEnd < 0 ? -1 : header.dataEnd - header.dataStart;
                this.mpeg = new MpegAudioDemuxer(new SlicedSource(source, header.dataStart, length),
                        DemuxerOptions.builder().limits(limits).build());
                for (var entry : mpeg.metadata().entrySet()) {
                    header.metadata.putIfAbsent(entry.getKey(), entry.getValue());
                }
                this.metadata = Collections.unmodifiableMap(header.metadata);
                this.track = (AudioTrack) mpeg.tracks().getFirst();
                this.tracks = List.of(track);
                this.frameSize = 1;
                this.packetFrames = 1;
                this.dataStart = header.dataStart;
                this.dataEnd = header.dataEnd;
                this.data = null;
                return;
            }
            this.mpeg = null;
            this.metadata = Collections.unmodifiableMap(header.metadata);
            this.frameSize = header.blockAlign;
            this.packetFrames = Math.max(1, Math.min(Integer.highestOneBit(Math.max(1, header.sampleRate / 10)),
                    MAX_PACKET_BYTES / frameSize));
            this.dataStart = header.dataStart;
            this.dataEnd = header.dataEnd;
            this.position = dataStart;
            this.data = new ReadAheadCache(source, READ_BLOCK, 0, dataEnd < 0 ? Long.MAX_VALUE : dataEnd);
            var frames = dataEnd < 0 ? -1 : (dataEnd - dataStart) / frameSize;
            this.track = AudioTrack.builder().codec(header.codec).timeBase(Rational.timeBaseOf(header.sampleRate))
                    .duration(frames)
                    .sampleRate(header.sampleRate)
                    .layout(header.layout)
                    .bitsPerSample(header.validBits)
                    .build();
            this.tracks = List.of(track);
        } catch (MediaException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new MediaException.Malformed("invalid WAVE file", exception);
        }
    }

    /**
     * Collects the values parsed from the chunks.
     */
    private static final class Header {
        /**
         * Whether the fields are big-endian (RIFX).
         */
        private boolean bigEndian;

        /**
         * Whether the file is RF64 or BW64.
         */
        private boolean rf64;

        /**
         * The 64-bit size of the {@code data} chunk from {@code ds64}, or {@code -1}.
         */
        private long ds64DataSize = -1;

        /**
         * Whether a {@code fmt } chunk was seen.
         */
        private boolean hasFormat;

        /**
         * Whether the {@code data} chunk holds an MPEG audio stream rather than PCM.
         */
        private boolean mpeg;

        /**
         * The sample coding.
         */
        private Codec codec;

        /**
         * The sampling rate.
         */
        private int sampleRate;

        /**
         * The number of meaningful bits per sample.
         */
        private int validBits;

        /**
         * The size of one frame in bytes.
         */
        private int blockAlign;

        /**
         * The channel layout.
         */
        private ChannelLayout layout;

        /**
         * The offset of the first sample byte, or {@code -1} before the {@code data} chunk.
         */
        private long dataStart = -1;

        /**
         * The offset of the end of the samples, or {@code -1} when unknown.
         */
        private long dataEnd = -1;

        /**
         * The metadata collected so far.
         */
        private final Map<String, String> metadata = new LinkedHashMap<>();
    }

    /**
     * Walks the chunks of the file.
     *
     * @param reader the reader positioned at offset zero
     * @param header receives the parsed values
     */
    private void parse(SourceReader reader, Header header) {
        var riff = reader.fourCc();
        switch (riff) {
            case 0x52494646 -> {
            }
            case 0x52494658 -> header.bigEndian = true;
            case 0x52463634, 0x42573634 -> header.rf64 = true;
            default -> throw new MediaException.Malformed("not a RIFF WAVE file");
        }
        reader.skip(4);
        if (reader.fourCc() != 0x57415645) {
            throw new MediaException.Malformed("RIFF file is not of form WAVE");
        }
        var size = source.size();
        var first = true;
        while (true) {
            var chunkStart = reader.position();
            if (size >= 0 && size - chunkStart < 8) {
                break;
            }
            if (size < 0 && reader.atEnd()) {
                break;
            }
            var id = reader.fourCc();
            var chunkSize = header.bigEndian ? reader.u32be() : reader.u32le();
            var body = reader.position();
            if (header.rf64 && first) {
                if (id != 0x64733634) {
                    throw new MediaException.Malformed("RF64 file without a leading ds64 chunk");
                }
                parseDs64(reader, header, chunkSize);
            }
            first = false;
            switch (id) {
                case 0x666D7420 -> parseFormat(reader, header, chunkSize);
                case 0x64617461 -> {
                    if (header.dataStart >= 0) {
                        break;
                    }
                    header.dataStart = body;
                    var dataSize = chunkSize;
                    if (header.rf64 && chunkSize == 0xFFFFFFFFL) {
                        dataSize = header.ds64DataSize;
                    } else if (!header.rf64 && (chunkSize == 0 || chunkSize == 0xFFFFFFFFL)) {
                        dataSize = -1;
                    }
                    header.dataEnd = dataSize < 0 ? size : size < 0 ? body + dataSize : Math.min(size, body + dataSize);
                    if (dataSize < 0 || size < 0 || header.dataEnd < body + dataSize || size - (body + dataSize) < 8
                            || !ForwardOnlySource.isCheapToReach(source, body + dataSize + 8)) {
                        if (!header.hasFormat) {
                            throw new MediaException.Malformed("WAVE file without a fmt chunk before its data");
                        }
                        return;
                    }
                    chunkSize = dataSize;
                }
                case 0x4C495354 -> parseList(reader, header, chunkSize);
                case 0x69643320, 0x49443320 -> {
                    if (size < 0 || chunkSize <= size - body) {
                        Id3v2.skip(source, body, body + chunkSize, header.metadata);
                    }
                }
                default -> {
                }
            }
            if (size >= 0 && chunkSize > size - body) {
                break;
            }
            var next = body + chunkSize;
            if ((chunkSize & 1) != 0) {
                next = paddedChunkStart(next, size);
            }
            reader.seek(next);
        }
        if (!header.hasFormat) {
            throw new MediaException.Malformed("WAVE file without a fmt chunk");
        }
        if (header.dataStart < 0) {
            throw new MediaException.Malformed("WAVE file without a data chunk");
        }
    }

    /**
     * Returns where the chunk after an odd-sized chunk starts, tolerating a missing pad byte.
     *
     * @param unpadded the offset right after the odd-sized chunk body
     * @param size the source size, or {@code -1} when unknown
     * @return {@code unpadded + 1}, or {@code unpadded} when only that offset holds a plausible chunk identifier
     */
    private long paddedChunkStart(long unpadded, long size) {
        if (size >= 0 && size - unpadded < 9) {
            return unpadded + 1;
        }
        var probe = new byte[5];
        if (source.read(unpadded, probe, 0, 5) < 5) {
            return unpadded + 1;
        }
        var paddedValid = isChunkId(probe, 1);
        var unpaddedValid = isChunkId(probe, 0);
        return !paddedValid && unpaddedValid ? unpadded : unpadded + 1;
    }

    /**
     * Returns whether four bytes look like a chunk identifier (printable ASCII).
     *
     * @param data the bytes
     * @param offset the index of the first byte
     * @return {@code true} when every byte is printable
     */
    private static boolean isChunkId(byte[] data, int offset) {
        for (var i = 0; i < 4; i++) {
            var b = data[offset + i] & 0xFF;
            if (b < 0x20 || b > 0x7E) {
                return false;
            }
        }
        return true;
    }

    /**
     * Parses the RF64 {@code ds64} chunk.
     *
     * @param reader the reader positioned at the chunk body
     * @param header receives the data size
     * @param chunkSize the chunk body size
     */
    private static void parseDs64(SourceReader reader, Header header, long chunkSize) {
        if (chunkSize < 24) {
            throw new MediaException.Malformed("ds64 chunk of " + chunkSize + " bytes is too small");
        }
        reader.skip(8);
        var dataSize = reader.u64le();
        if (dataSize < 0) {
            throw new MediaException.Malformed("ds64 data size is negative");
        }
        header.ds64DataSize = dataSize;
    }

    /**
     * Parses the {@code fmt } chunk.
     *
     * @param reader the reader positioned at the chunk body
     * @param header receives the format
     * @param chunkSize the chunk body size
     */
    private void parseFormat(SourceReader reader, Header header, long chunkSize) {
        if (header.hasFormat) {
            return;
        }
        if (chunkSize < 16) {
            throw new MediaException.Malformed("fmt chunk of " + chunkSize + " bytes is too small");
        }
        var bytes = new byte[(int) Math.min(chunkSize, WaveFormat.EXTENSIBLE_SIZE)];
        reader.readFully(bytes, 0, bytes.length);
        var format = WaveFormat.parse(bytes, bytes.length, header.bigEndian);
        var tag = format.tag();
        var channels = format.channels();
        var sampleRate = format.sampleRate();
        var bits = format.bitsPerSample();
        LimitChecks.checkChannels(limits, channels);
        if (sampleRate == 0 || sampleRate > Integer.MAX_VALUE) {
            throw new MediaException.Malformed("invalid WAVE sample rate " + sampleRate);
        }
        if (tag == WaveFormat.EXTENSIBLE) {
            throw new MediaException.Malformed("WAVE_FORMAT_EXTENSIBLE fmt chunk of " + chunkSize + " bytes");
        }
        if (!format.knownSubFormat()) {
            throw new MediaException.Unsupported("wav subformat", "unsupported WAVE_FORMAT_EXTENSIBLE sub-format GUID");
        }
        if (tag == WaveFormat.MPEG || tag == WaveFormat.MPEG_LAYER3) {
            header.codec = tag == WaveFormat.MPEG ? Codec.MP2 : Codec.MP3;
            header.sampleRate = (int) sampleRate;
            header.layout = format.layout();
            header.mpeg = true;
            header.hasFormat = true;
            return;
        }
        var containerBytes = containerBytes(channels, format.blockAlign(), bits);
        header.codec = codec(tag, containerBytes, header.bigEndian);
        header.sampleRate = (int) sampleRate;
        header.blockAlign = containerBytes * channels;
        header.validBits = header.codec == Codec.PCM_ALAW || header.codec == Codec.PCM_MULAW ? 8 : Math.max(format.validBits(), 1);
        header.layout = format.layout();
        header.hasFormat = true;
        LimitChecks.checkAllocation(limits, Math.min(MAX_PACKET_BYTES, (long) header.blockAlign * Integer.highestOneBit(Math.max(1, header.sampleRate / 10))));
    }

    /**
     * Returns the size of one sample container in bytes.
     *
     * @param channels the channel count
     * @param blockAlign the declared block alignment
     * @param bits the declared bits per sample
     * @return the container size, from the block alignment when it is consistent, otherwise from the bit depth
     */
    private static int containerBytes(int channels, int blockAlign, int bits) {
        var fromBits = (bits + 7) / 8;
        if (blockAlign > 0 && blockAlign % channels == 0) {
            var fromAlign = blockAlign / channels;
            if (fromAlign >= fromBits && (fromAlign <= 4 || fromAlign == 8)) {
                return fromAlign;
            }
        }
        if (fromBits == 0) {
            throw new MediaException.Malformed("WAVE sample size of 0 bits");
        }
        return fromBits;
    }

    /**
     * Maps a format tag and container size to a codec.
     *
     * @param tag the format tag
     * @param containerBytes the sample container size in bytes
     * @param bigEndian whether samples are big-endian (RIFX)
     * @return the codec
     * @throws MediaException.Unsupported if the combination is not decodable
     */
    private static Codec codec(int tag, int containerBytes, boolean bigEndian) {
        return switch (tag) {
            case WaveFormat.PCM -> switch (containerBytes) {
                case 1 -> Codec.PCM_U8;
                case 2 -> bigEndian ? Codec.PCM_S16BE : Codec.PCM_S16LE;
                case 3 -> bigEndian ? Codec.PCM_S24BE : Codec.PCM_S24LE;
                case 4 -> bigEndian ? Codec.PCM_S32BE : Codec.PCM_S32LE;
                default -> throw new MediaException.Unsupported("pcm_s" + containerBytes * 8,
                        containerBytes * 8 + "-bit integer PCM is not supported");
            };
            case WaveFormat.FLOAT -> switch (containerBytes) {
                case 4 -> bigEndian ? Codec.PCM_F32BE : Codec.PCM_F32LE;
                case 8 -> bigEndian ? Codec.PCM_F64BE : Codec.PCM_F64LE;
                default -> throw new MediaException.Unsupported("pcm_f" + containerBytes * 8,
                        containerBytes * 8 + "-bit float PCM is not supported");
            };
            case WaveFormat.ALAW -> requireByte(Codec.PCM_ALAW, containerBytes);
            case WaveFormat.MULAW -> requireByte(Codec.PCM_MULAW, containerBytes);
            default -> throw new MediaException.Unsupported(String.format("wav format 0x%04x", tag),
                    String.format("WAVE format tag 0x%04x is not supported", tag));
        };
    }

    /**
     * Checks that a G.711 stream uses one-byte samples.
     *
     * @param codec the G.711 codec
     * @param containerBytes the sample container size
     * @return {@code codec}
     * @throws MediaException.Malformed if the container is not one byte
     */
    private static Codec requireByte(Codec codec, int containerBytes) {
        if (containerBytes != 1) {
            throw new MediaException.Malformed(codec.displayName() + " with " + containerBytes + "-byte samples");
        }
        return codec;
    }

    /**
     * Parses a {@code LIST} chunk, decoding {@code INFO} entries into metadata.
     *
     * @param reader the reader positioned at the chunk body
     * @param header receives the metadata
     * @param chunkSize the chunk body size
     */
    private void parseList(SourceReader reader, Header header, long chunkSize) {
        if (chunkSize < 4 || chunkSize > MAX_LIST_SIZE || chunkSize > reader.remaining()) {
            return;
        }
        var body = reader.bytes(chunkSize, MAX_LIST_SIZE);
        if (body[0] != 'I' || body[1] != 'N' || body[2] != 'F' || body[3] != 'O') {
            return;
        }
        var offset = 4;
        while (body.length - offset >= 8) {
            var key = new String(body, offset, 4, StandardCharsets.ISO_8859_1);
            var size = header.bigEndian
                    ? (body[offset + 4] & 0xFFL) << 24 | (body[offset + 5] & 0xFF) << 16 | (body[offset + 6] & 0xFF) << 8 | (body[offset + 7] & 0xFF)
                    : (body[offset + 7] & 0xFFL) << 24 | (body[offset + 6] & 0xFF) << 16 | (body[offset + 5] & 0xFF) << 8 | (body[offset + 4] & 0xFF);
            offset += 8;
            if (size > body.length - offset) {
                return;
            }
            var end = offset + (int) size;
            var textEnd = offset;
            while (textEnd < end && body[textEnd] != 0) {
                textEnd++;
            }
            var value = new String(body, offset, textEnd - offset, StandardCharsets.UTF_8).strip();
            if (!value.isEmpty() && isChunkId(body, offset - 8)) {
                header.metadata.merge(key, value, (previous, next) -> previous + "; " + next);
            }
            offset = end + (int) (size & 1);
        }
    }

    @Override
    public ContainerFormat format() {
        return ContainerFormat.WAV;
    }

    @Override
    public List<Track> tracks() {
        return tracks;
    }

    @Override
    public Map<String, String> metadata() {
        return metadata;
    }

    @Override
    public long durationMicros() {
        if (mpeg != null) {
            return mpeg.durationMicros();
        }
        return track.duration() < 0 ? -1 : track.timeBase().toMicros(track.duration());
    }

    /**
     * Returns whether the file holds uncompressed or companded samples, whose frames are at
     * computable offsets; a WAVE file wrapping an MPEG audio stream cannot seek.
     *
     * @return {@code true} unless the file wraps an MPEG audio stream
     */
    @Override
    public boolean isSeekable() {
        return mpeg == null;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Every packet of uncompressed audio is a key frame, so the next packet starts at the
     * sample frame displayed at {@code micros}.
     */
    @Override
    public void seek(long micros) {
        // TODO: seek a WAVE file wrapping MPEG audio through the MPEG frame index
        if (mpeg != null) {
            throw new MediaException.Unsupported("seek", "a WAVE file wrapping MPEG audio cannot seek");
        }
        var frame = frameAt(micros, track.sampleRate());
        var available = dataEnd >= 0 ? (dataEnd - dataStart) / frameSize : (Long.MAX_VALUE - dataStart) / frameSize;
        frame = Math.min(frame, Math.max(0, available));
        frameIndex = frame;
        position = dataStart + frame * frameSize;
        finished = false;
    }

    /**
     * Converts a presentation time to the index of the sample frame displayed at it.
     *
     * @param micros the time in microseconds
     * @param sampleRate the sampling rate in hertz
     * @return the frame index, rounded down, {@code 0} for a negative time
     */
    private static long frameAt(long micros, int sampleRate) {
        if (micros <= 0) {
            return 0;
        }
        return BigInteger.valueOf(micros)
                .multiply(BigInteger.valueOf(sampleRate))
                .divide(BigInteger.valueOf(1_000_000))
                .min(BigInteger.valueOf(Long.MAX_VALUE))
                .longValue();
    }

    @Override
    public Packet read() {
        if (mpeg != null) {
            return mpeg.read();
        }
        try {
            return nextPacket();
        } catch (MediaException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new MediaException.Malformed("invalid WAVE data", exception);
        }
    }

    /**
     * Reads the next packet of whole frames.
     *
     * @return the packet, or {@code null} at the end of the samples
     */
    private Packet nextPacket() {
        if (finished) {
            return null;
        }
        var wanted = (long) packetFrames * frameSize;
        if (dataEnd >= 0) {
            wanted = Math.min(wanted, (dataEnd - position) / frameSize * frameSize);
        }
        if (wanted <= 0) {
            finished = true;
            return null;
        }
        var data = new byte[(int) wanted];
        var done = this.data.readUpTo(position, data, 0, data.length);
        var whole = done / frameSize * frameSize;
        if (whole < data.length) {
            finished = true;
            if (whole == 0) {
                return null;
            }
            data = java.util.Arrays.copyOf(data, whole);
        }
        var frames = whole / frameSize;
        var packet = new Packet(track.id(), data, frameIndex, frameIndex, frames, true);
        position += whole;
        frameIndex += frames;
        ForwardOnlySource.release(source, position);
        return packet;
    }

    /**
     * Releases the demuxer of an embedded MPEG stream; the source is not closed.
     */
    @Override
    public void close() {
        if (mpeg != null) {
            mpeg.close();
        }
    }
}
