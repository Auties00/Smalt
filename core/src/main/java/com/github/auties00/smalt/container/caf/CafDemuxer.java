package com.github.auties00.smalt.container.caf;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.Speaker;
import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.container.Demuxer;
import com.github.auties00.smalt.container.DemuxerOptions;
import com.github.auties00.smalt.container.internal.AacExtensions;
import com.github.auties00.smalt.internal.codec.AudioConfigs;
import com.github.auties00.smalt.internal.io.ByteArrays;
import com.github.auties00.smalt.internal.io.ForwardOnlySource;
import com.github.auties00.smalt.internal.io.ReadAheadCache;
import com.github.auties00.smalt.internal.io.SourceReader;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.Track;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Reads Apple Core Audio Format files ({@code .caf}), magic {@code caff}, version 1.
 *
 * <p>The {@code desc} chunk, which must come first, gives the sampling rate (rounded to the nearest integer), the
 * format, the bytes and frames of a packet and the channel count. The formats read are:
 * <table class="striped">
 * <caption>Formats</caption>
 * <thead><tr><th scope="col">Format</th><th scope="col">Codec</th><th scope="col">Configuration</th></tr></thead>
 * <tbody>
 * <tr><td>{@code lpcm}</td><td>the PCM codec of its integer or float flag, its little-endian flag and its sample
 * size: 8-bit ({@link Codec#PCM_S8}), 16, 24, 32 and 64-bit integers, 32 and 64-bit floats</td><td>none</td></tr>
 * <tr><td>{@code alaw}, {@code ulaw}</td><td>{@link Codec#PCM_ALAW}, {@link Codec#PCM_MULAW}</td><td>none</td></tr>
 * <tr><td>{@code ima4}</td><td>{@link Codec#ADPCM_IMA_QT}</td><td>none</td></tr>
 * <tr><td>{@code aac }, {@code aach}, {@code aacp}, {@code aacl}, {@code aace}</td><td>{@link Codec#AAC}</td>
 * <td>the {@code AudioSpecificConfig} of the {@code DecoderSpecificInfo} of the elementary stream descriptor of the
 * {@code kuki} chunk, or one built from the object type of the format flags without it; an HE-AAC stream, whose
 * {@code desc} chunk describes the AAC core, is described by the rate and layout of the richest layer its
 * {@code ldsc} chunk lists or, without one, that its first packet decodes to through the registered AAC decoder, as
 * players do, its priming frames rescaled to that rate, while the timestamps keep counting frames of the
 * core</td></tr>
 * <tr><td>{@code alac}</td><td>{@link Codec#ALAC}</td><td>the {@code ALACSpecificConfig} of the {@code kuki}
 * chunk, bare or after {@code frma} and {@code alac} atom headers</td></tr>
 * <tr><td>{@code opus}</td><td>{@link Codec#OPUS}</td><td>the {@code OpusHead} of the {@code kuki} chunk, or one
 * built from the channel count with the priming frames of the packet table as its pre-skip</td></tr>
 * <tr><td>{@code .mp1}, {@code .mp2}, {@code .mp3}</td><td>{@link Codec#MP2}, {@link Codec#MP3}</td>
 * <td>none</td></tr>
 * <tr><td>{@code ac-3}, {@code ec-3}</td><td>{@link Codec#AC3}, {@link Codec#EAC3}</td><td>none</td></tr>
 * </tbody>
 * </table>
 * Any other format raises {@link MediaException.Unsupported} naming it. The {@code chan} chunk gives the channel
 * layout, from its layout tag (the mono, stereo, MPEG, ITU, DVD, Audio Unit, AAC and AC-3 tags, the ambisonic B-format
 * and the Higher Order Ambisonics tags), its bitmap or its channel descriptions; without one, or with one this
 * demuxer cannot read, the track takes {@link ChannelLayout#defaultFor(int)}. The {@code info} chunk becomes
 * {@link #metadata()}, under its own keys (such as {@code title}, {@code artist} and {@code track number}).
 *
 * <p>Packets of a constant size ({@code desc} bytes per packet not zero) are read in groups that last at most 100 ms,
 * at least one; packets of variable size become one packet each, sized and timed by the {@code pakt} chunk, which
 * also gives the frames of each packet when the {@code desc} chunk does not. Timestamps are in frames of the sampling
 * rate of the {@code desc} chunk, starting at zero; the priming frames of the packet table become
 * {@link AudioTrack#skipSamples()} (except for Opus, whose {@code OpusHead} pre-skip holds them) and its valid frames
 * {@link AudioTrack#duration()}, so the last packet keeps its full duration and a reader drops the remainder frames.
 * A {@code data} chunk of size {@code -1} extends to the end of the file. A seek lands on the requested frame for
 * linear PCM, A-law and mu-law, and otherwise on the packet that holds it, so that the packets read after it are those
 * of a read from the start.
 *
 * <p>A source that reads its input once, front to back (a stream), is told after every packet which bytes are no
 * longer needed; the chunks after a {@code data} chunk of known size are read only when they lie within the
 * source's memory budget, so a stream whose packet table follows the data of packets of variable size raises
 * {@link MediaException.Unsupported}.
 *
 * <p>Instances are not thread-safe.
 */
public final class CafDemuxer implements Demuxer {
    /**
     * The size of the blocks the audio data of a source that is not in memory is read in.
     */
    private static final int READ_BLOCK = 64 * 1024;

    /**
     * The largest {@code kuki}, {@code chan} or {@code info} chunk read, in bytes.
     */
    private static final int MAX_SMALL_CHUNK = 1 << 20;

    /**
     * The largest packet of variable size, in bytes.
     */
    private static final int MAX_PACKET = 1 << 24;

    /**
     * The source read.
     */
    private final MediaSource source;

    /**
     * The single audio track.
     */
    private final AudioTrack track;

    /**
     * The track list exposed by {@link #tracks()}.
     */
    private final List<Track> tracks;

    /**
     * The entries of the {@code info} chunk.
     */
    private final Map<String, String> metadata;

    /**
     * The offset of the first byte of audio data.
     */
    private final long dataStart;

    /**
     * The read-ahead window over the audio data.
     */
    private final ReadAheadCache data;

    /**
     * The size of a packet of constant size, or 0 for packets of variable size.
     */
    private final int packetBytes;

    /**
     * The frames of a packet of constant size.
     */
    private final int packetFrames;

    /**
     * The number of packets of constant size per returned packet.
     */
    private final int groupPackets;

    /**
     * The sampling rate of the {@code desc} chunk, in which the timestamps count.
     */
    private final int rate;

    /**
     * The number of packets, or {@link Long#MAX_VALUE} for packets of constant size that extend to an unknown end.
     */
    private final long packetCount;

    /**
     * The offset of every packet of variable size relative to {@link #dataStart}, and the end of the last, or
     * {@code null} for packets of constant size.
     */
    private final long[] offsets;

    /**
     * The first frame of every packet of variable size, and the end of the last, or {@code null} for packets of
     * constant size.
     */
    private final long[] frames;

    /**
     * The index of the next packet.
     */
    private long packetIndex;

    /**
     * Constructs a demuxer over {@code source} with {@link DemuxerOptions#defaults()}.
     *
     * @param source the input, not closed by this demuxer
     * @throws MediaException.Malformed if the file is not a valid CAF file
     * @throws MediaException.Unsupported if the file uses a format this demuxer cannot read, or a stream holds a
     * packet table after the data it describes
     * @throws MediaException.LimitExceeded if the channel count or the packet table exceeds the limits
     * @throws MediaException.Io if reading fails
     */
    public CafDemuxer(MediaSource source) {
        this(source, DemuxerOptions.defaults());
    }

    /**
     * Constructs a demuxer over {@code source} that enforces the limits of {@code options}.
     *
     * @param source the input, not closed by this demuxer
     * @param options the demuxing options
     * @throws MediaException.Malformed if the file is not a valid CAF file
     * @throws MediaException.Unsupported if the file uses a format this demuxer cannot read, or a stream holds a
     * packet table after the data it describes
     * @throws MediaException.LimitExceeded if the channel count or the packet table exceeds the limits
     * @throws MediaException.Io if reading fails
     */
    public CafDemuxer(MediaSource source, DemuxerOptions options) {
        this(source, options.limits());
    }

    private CafDemuxer(MediaSource source, MediaLimits limits) {
        this.source = Objects.requireNonNull(source, "source");
        Objects.requireNonNull(limits, "limits");
        try {
            var header = parse(new SourceReader(source, 4096), limits);
            this.metadata = Collections.unmodifiableMap(header.info);
            this.dataStart = header.dataStart;
            var description = header.description;
            var codec = codec(description);
            var channels = description.channels;
            var rate = description.rate;
            this.rate = rate;
            var priming = header.priming;
            var config = config(codec, description, header.cookie, priming);
            if (codec == Codec.OPUS) {
                priming = 0;
            }
            var size = source.size();
            var dataEnd = header.dataSize < 0 ? size : size < 0 ? header.dataStart + header.dataSize
                    : Math.min(size, header.dataStart + header.dataSize);
            long totalFrames;
            if (description.bytesPerPacket > 0) {
                if (description.framesPerPacket <= 0) {
                    throw new MediaException.Malformed("CAF packets of constant size without a frame count");
                }
                this.packetBytes = (int) Math.min(description.bytesPerPacket, Integer.MAX_VALUE);
                this.packetFrames = (int) Math.min(description.framesPerPacket, Integer.MAX_VALUE);
                if (codec.isPcm() && packetBytes != codec.pcmBitsPerSample().orElseThrow() / 8 * channels) {
                    throw new MediaException.Malformed("CAF linear PCM packets of " + packetBytes + " bytes for "
                            + channels + " channels");
                }
                if (codec == Codec.ADPCM_IMA_QT && (packetBytes != 34L * channels || packetFrames != 64)) {
                    throw new MediaException.Malformed("CAF IMA4 packets of " + packetBytes + " bytes and "
                            + packetFrames + " frames for " + channels + " channels");
                }
                var targetFrames = Integer.highestOneBit(Math.max(1, rate / 10));
                this.groupPackets = Math.max(1, Math.min(targetFrames / packetFrames, (1 << 20) / packetBytes));
                LimitChecks.checkAllocation(limits, (long) groupPackets * packetBytes);
                this.packetCount = dataEnd < 0 ? Long.MAX_VALUE : Math.max(0, dataEnd - dataStart) / packetBytes;
                this.offsets = null;
                this.frames = null;
                totalFrames = packetCount == Long.MAX_VALUE ? -1 : packetCount * packetFrames;
            } else {
                if (header.table == null) {
                    if (header.tableAfterData) {
                        throw new MediaException.Unsupported("caf packet table after data",
                                "a CAF stream whose packet table follows its data cannot be read front to back");
                    }
                    throw new MediaException.Malformed("CAF packets of variable size without a packet table");
                }
                this.packetBytes = 0;
                this.packetFrames = (int) Math.min(description.framesPerPacket, Integer.MAX_VALUE);
                this.groupPackets = 1;
                var count = header.packets;
                this.offsets = new long[(int) count + 1];
                this.frames = new long[(int) count + 1];
                readTable(header.table, count, description.framesPerPacket, dataEnd < 0 ? -1 : dataEnd - dataStart);
                this.packetCount = count;
                totalFrames = frames[(int) count];
            }
            if (header.validFrames >= 0 && totalFrames >= 0) {
                totalFrames = Math.min(totalFrames, header.validFrames + priming);
            }
            var duration = totalFrames < 0 ? -1 : Math.max(0, totalFrames - priming);
            this.data = new ReadAheadCache(source, READ_BLOCK, 0, dataEnd < 0 ? Long.MAX_VALUE : dataEnd);
            var layout = header.chan == null ? null : CafChannelLayouts.read(header.chan, channels);
            var declared = AudioTrack.builder().codec(codec).timeBase(Rational.timeBaseOf(rate))
                    .duration(duration)
                    .codecConfig(config)
                    .sampleRate(rate)
                    .layout(layout != null ? layout : ChannelLayout.defaultFor(channels))
                    .bitsPerSample(bitsPerSample(codec, description))
                    .skipSamples(priming)
                    .build();
            var layered = codec == Codec.AAC && header.formats != null ? layered(declared, header.formats, limits)
                    : null;
            this.track = layered != null ? layered : codec == Codec.AAC && AacExtensions.mayRefine(declared)
                    ? AacExtensions.refine(declared, firstPacket()) : declared;
            this.tracks = List.of(track);
        } catch (MediaException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new MediaException.Malformed("invalid CAF file", exception);
        }
    }

    /**
     * Holds the fields of a {@code desc} chunk.
     *
     * @param rate the sampling rate, rounded
     * @param formatId the format identifier
     * @param flags the format flags
     * @param bytesPerPacket the bytes of a packet, or 0 when they vary
     * @param framesPerPacket the frames of a packet, or 0 when they vary
     * @param channels the channel count
     * @param bitsPerChannel the bits of a sample, or 0 for compressed formats
     */
    private record Description(int rate, int formatId, long flags, long bytesPerPacket, long framesPerPacket,
                               int channels, long bitsPerChannel) {
    }

    /**
     * Collects the values parsed from the chunks.
     */
    private static final class Header {
        /**
         * The {@code desc} chunk.
         */
        private Description description;

        /**
         * The {@code kuki} chunk, or {@code null}.
         */
        private byte[] cookie;

        /**
         * The {@code chan} chunk, or {@code null}.
         */
        private byte[] chan;

        /**
         * The {@code ldsc} chunk, or {@code null}.
         */
        private byte[] formats;

        /**
         * The entries of the {@code info} chunk.
         */
        private final Map<String, String> info = new LinkedHashMap<>();

        /**
         * The packet table entries of the {@code pakt} chunk, or {@code null}.
         */
        private byte[] table;

        /**
         * The number of packets of the packet table.
         */
        private long packets;

        /**
         * The valid frames of the packet table, or {@code -1}.
         */
        private long validFrames = -1;

        /**
         * The priming frames of the packet table.
         */
        private int priming;

        /**
         * Whether a packet table may follow the data where it could not be read.
         */
        private boolean tableAfterData;

        /**
         * The offset of the first byte of audio data, or {@code -1} before the {@code data} chunk.
         */
        private long dataStart = -1;

        /**
         * The size of the audio data, or {@code -1} when it extends to the end of the file.
         */
        private long dataSize = -1;
    }

    /**
     * Walks the chunks of the file.
     *
     * @param reader the reader positioned at offset zero
     * @param limits the resource bounds
     * @return the parsed values
     */
    private Header parse(SourceReader reader, MediaLimits limits) {
        if (reader.fourCc() != 0x63616666) {
            throw new MediaException.Malformed("not a CAF file");
        }
        var version = reader.u16be();
        reader.skip(2);
        if (version != 1) {
            throw new MediaException.Unsupported("caf version " + version, "CAF version " + version
                    + " is not supported");
        }
        var header = new Header();
        var size = source.size();
        while (size >= 0 ? size - reader.position() >= 12 : !reader.atEnd()) {
            var type = reader.fourCc();
            var chunkSize = reader.u64be();
            var body = reader.position();
            if (header.description == null && type != 0x64657363) {
                throw new MediaException.Malformed("CAF file whose first chunk is not desc");
            }
            if (chunkSize < 0 && (type != 0x64617461 || chunkSize != -1)) {
                throw new MediaException.Malformed("CAF chunk of size " + chunkSize);
            }
            switch (type) {
                case 0x64657363 -> {
                    if (header.description != null) {
                        break;
                    }
                    if (chunkSize < 32) {
                        throw new MediaException.Malformed("CAF desc chunk of " + chunkSize + " bytes is too small");
                    }
                    header.description = description(reader, limits);
                }
                case 0x6B756B69 -> header.cookie = small(reader, chunkSize, header.cookie);
                case 0x6368616E -> header.chan = small(reader, chunkSize, header.chan);
                case 0x6C647363 -> header.formats = small(reader, chunkSize, header.formats);
                case 0x696E666F -> {
                    var info = small(reader, chunkSize, null);
                    if (info != null && header.info.isEmpty()) {
                        info(info, header.info);
                    }
                }
                case 0x70616B74 -> {
                    if (header.table != null) {
                        break;
                    }
                    if (chunkSize < 24) {
                        throw new MediaException.Malformed("CAF pakt chunk of " + chunkSize + " bytes is too small");
                    }
                    header.packets = reader.u64be();
                    header.validFrames = reader.u64be();
                    header.priming = (int) reader.u32be();
                    reader.skip(4);
                    if (header.packets < 0 || header.priming < 0 || header.packets >= Integer.MAX_VALUE - 1) {
                        throw new MediaException.Malformed("invalid CAF packet table");
                    }
                    LimitChecks.checkAllocation(limits, 16 * header.packets + chunkSize);
                    header.table = reader.bytes(chunkSize - 24, Math.max(limits.maxBytes(), chunkSize));
                }
                case 0x64617461 -> {
                    if (header.dataStart >= 0) {
                        break;
                    }
                    if (chunkSize >= 0 && chunkSize < 4) {
                        throw new MediaException.Malformed("CAF data chunk of " + chunkSize + " bytes is too small");
                    }
                    header.dataStart = body + 4;
                    header.dataSize = chunkSize < 0 ? -1 : chunkSize - 4;
                    if (chunkSize < 0) {
                        return finish(header);
                    }
                    var next = body + chunkSize;
                    if (size >= 0 && size - next < 12) {
                        return finish(header);
                    }
                    if (!ForwardOnlySource.isCheapToReach(source, next + 12)) {
                        header.tableAfterData = header.table == null;
                        return finish(header);
                    }
                }
                default -> {
                }
            }
            if (size >= 0 && chunkSize > size - body) {
                break;
            }
            reader.seek(body + chunkSize);
        }
        return finish(header);
    }

    /**
     * Checks that the chunks a file needs were found.
     *
     * @param header the parsed values
     * @return {@code header}
     */
    private static Header finish(Header header) {
        if (header.description == null) {
            throw new MediaException.Malformed("CAF file without a desc chunk");
        }
        if (header.dataStart < 0) {
            throw new MediaException.Malformed("CAF file without a data chunk");
        }
        return header;
    }

    /**
     * Reads the fields of a {@code desc} chunk.
     *
     * @param reader the reader positioned at the chunk body
     * @param limits the resource bounds
     * @return the description
     */
    private static Description description(SourceReader reader, MediaLimits limits) {
        var rate = Double.longBitsToDouble(reader.u64be());
        var formatId = reader.fourCc();
        var flags = reader.u32be();
        var bytesPerPacket = reader.u32be();
        var framesPerPacket = reader.u32be();
        var channels = reader.u32be();
        var bits = reader.u32be();
        if (!(rate >= 0.5 && rate <= Integer.MAX_VALUE)) {
            throw new MediaException.Malformed("invalid CAF sample rate " + rate);
        }
        if (channels == 0 || channels > Integer.MAX_VALUE) {
            throw new MediaException.Malformed("invalid CAF channel count " + channels);
        }
        LimitChecks.checkChannels(limits, (int) channels);
        return new Description((int) Math.round(rate), formatId, flags, bytesPerPacket, framesPerPacket,
                (int) channels, bits);
    }

    /**
     * Reads a small chunk whole, keeping the first of its kind.
     *
     * @param reader the reader positioned at the chunk body
     * @param chunkSize the chunk body size
     * @param previous the body of an earlier chunk of the kind, or {@code null}
     * @return the body, {@code previous} when there was one, or {@code null} when the chunk is too large or truncated
     */
    private static byte[] small(SourceReader reader, long chunkSize, byte[] previous) {
        if (previous != null || chunkSize > MAX_SMALL_CHUNK || chunkSize > reader.remaining()) {
            return previous;
        }
        return reader.bytes(chunkSize, MAX_SMALL_CHUNK);
    }

    /**
     * Decodes the strings of an {@code info} chunk: a 32-bit count, then that many pairs of null-terminated UTF-8
     * keys and values.
     *
     * @param info the chunk body
     * @param entries receives the entries, in order
     */
    private static void info(byte[] info, Map<String, String> entries) {
        if (info.length < 4) {
            return;
        }
        var count = (info[0] & 0xFFL) << 24 | (info[1] & 0xFF) << 16 | (info[2] & 0xFF) << 8 | info[3] & 0xFF;
        var offset = 4;
        for (var index = 0; index < count; index++) {
            var keyEnd = terminator(info, offset);
            if (keyEnd < 0) {
                return;
            }
            var valueEnd = terminator(info, keyEnd + 1);
            if (valueEnd < 0) {
                return;
            }
            var key = new String(info, offset, keyEnd - offset, StandardCharsets.UTF_8);
            var value = new String(info, keyEnd + 1, valueEnd - keyEnd - 1, StandardCharsets.UTF_8);
            if (!key.isEmpty() && !value.isEmpty()) {
                entries.merge(key, value, (previous, next) -> previous + "; " + next);
            }
            offset = valueEnd + 1;
        }
    }

    /**
     * Returns the index of the zero byte that ends a string.
     *
     * @param data the bytes
     * @param offset the index of the first byte of the string
     * @return the index of the zero byte, or {@code -1} when the string is not terminated
     */
    private static int terminator(byte[] data, int offset) {
        for (var index = offset; index < data.length; index++) {
            if (data[index] == 0) {
                return index;
            }
        }
        return -1;
    }

    /**
     * Decodes the packet table entries into {@link #offsets} and {@link #frames}.
     *
     * @param table the entries: the byte size of every packet and, when the frames per packet vary, its frame count,
     * as variable-length integers of seven bits per byte, most significant first, the high bit set on every byte but
     * the last
     * @param count the number of packets
     * @param framesPerPacket the frames of a packet, or 0 when the table holds them
     * @param dataSize the size of the audio data, or {@code -1} when unknown
     */
    private void readTable(byte[] table, long count, long framesPerPacket, long dataSize) {
        var position = 0;
        var offset = 0L;
        var frame = 0L;
        for (var packet = 0; packet < count; packet++) {
            offsets[packet] = offset;
            frames[packet] = frame;
            long bytes = 0;
            int read;
            do {
                if (position >= table.length) {
                    throw new MediaException.Malformed("truncated CAF packet table");
                }
                read = table[position++] & 0xFF;
                bytes = bytes << 7 | read & 0x7F;
            } while ((read & 0x80) != 0 && bytes < 1L << 40);
            long packetFrames = framesPerPacket;
            if (framesPerPacket == 0) {
                packetFrames = 0;
                do {
                    if (position >= table.length) {
                        throw new MediaException.Malformed("truncated CAF packet table");
                    }
                    read = table[position++] & 0xFF;
                    packetFrames = packetFrames << 7 | read & 0x7F;
                } while ((read & 0x80) != 0 && packetFrames < 1L << 40);
            }
            if (bytes > MAX_PACKET) {
                throw new MediaException.Malformed("CAF packet of " + bytes + " bytes");
            }
            offset += bytes;
            frame += packetFrames;
        }
        if (dataSize >= 0 && offset > dataSize) {
            throw new MediaException.Malformed("CAF packet table describes " + offset + " bytes of " + dataSize
                    + " bytes of data");
        }
        offsets[(int) count] = offset;
        frames[(int) count] = frame;
    }

    /**
     * Returns the codec of a format.
     *
     * @param description the {@code desc} chunk
     * @return the codec
     * @throws MediaException.Unsupported if the format is not one the class description lists
     */
    private static Codec codec(Description description) {
        var bits = description.bitsPerChannel;
        return switch (description.formatId) {
            case 0x6C70636D -> {
                var floating = (description.flags & 1) != 0;
                var little = (description.flags & 2) != 0;
                yield switch ((int) Math.min(bits, 65) + (floating ? 100 : 0)) {
                    case 8 -> Codec.PCM_S8;
                    case 16 -> little ? Codec.PCM_S16LE : Codec.PCM_S16BE;
                    case 24 -> little ? Codec.PCM_S24LE : Codec.PCM_S24BE;
                    case 32 -> little ? Codec.PCM_S32LE : Codec.PCM_S32BE;
                    case 64 -> little ? Codec.PCM_S64LE : Codec.PCM_S64BE;
                    case 132 -> little ? Codec.PCM_F32LE : Codec.PCM_F32BE;
                    case 164 -> little ? Codec.PCM_F64LE : Codec.PCM_F64BE;
                    default -> throw new MediaException.Unsupported("caf lpcm " + bits + (floating ? "-bit float"
                            : "-bit integer"), "CAF linear PCM of " + bits + "-bit " + (floating ? "floats"
                            : "integers") + " is not supported");
                };
            }
            case 0x616C6177 -> Codec.PCM_ALAW;
            case 0x756C6177 -> Codec.PCM_MULAW;
            case 0x696D6134 -> Codec.ADPCM_IMA_QT;
            case 0x61616320, 0x61616368, 0x61616370, 0x6161636C, 0x61616365 -> Codec.AAC;
            case 0x616C6163 -> Codec.ALAC;
            case 0x6F707573 -> Codec.OPUS;
            case 0x2E6D7031, 0x2E6D7032 -> Codec.MP2;
            case 0x2E6D7033 -> Codec.MP3;
            case 0x61632D33 -> Codec.AC3;
            case 0x65632D33 -> Codec.EAC3;
            default -> {
                var name = new String(new byte[]{(byte) (description.formatId >>> 24),
                        (byte) (description.formatId >>> 16), (byte) (description.formatId >>> 8),
                        (byte) description.formatId}, StandardCharsets.ISO_8859_1);
                throw new MediaException.Unsupported("caf format '" + name + "'", "CAF format '" + name
                        + "' is not supported");
            }
        };
    }

    /**
     * Returns the codec configuration of a stream.
     *
     * @param codec the codec of the track
     * @param description the {@code desc} chunk
     * @param cookie the {@code kuki} chunk, or {@code null}
     * @param priming the priming frames of the packet table
     * @return the configuration, or {@code null} for a codec that has none
     * @throws MediaException.Malformed if the configuration the codec needs is missing or invalid
     */
    private static byte[] config(Codec codec, Description description, byte[] cookie, int priming) {
        return switch (codec) {
            case AAC -> {
                var config = cookie == null ? null : CafCookies.aacConfig(cookie);
                if (config == null) {
                    var objectType = description.flags > 0 && description.flags < 32 ? (int) description.flags : 2;
                    var configuration = description.channels <= 6 ? description.channels
                            : description.channels == 8 ? 7 : 0;
                    if (configuration == 0) {
                        throw new MediaException.Malformed("CAF AAC of " + description.channels
                                + " channels without a magic cookie");
                    }
                    config = AudioConfigs.audioSpecificConfig(objectType, description.rate, configuration, 0, false);
                }
                yield config;
            }
            case ALAC -> {
                if (cookie == null) {
                    throw new MediaException.Malformed("CAF ALAC without a magic cookie");
                }
                yield CafCookies.alacConfig(cookie);
            }
            case OPUS -> {
                if (cookie != null && CafCookies.isOpusHead(cookie)) {
                    yield cookie;
                }
                if (description.channels > 8) {
                    throw new MediaException.Unsupported("caf opus of " + description.channels + " channels",
                            "CAF Opus of more than 8 channels without a magic cookie is not supported");
                }
                yield AudioConfigs.opusHead(description.channels, Math.min(priming, 0xFFFF), description.rate,
                        false);
            }
            default -> null;
        };
    }

    /**
     * Returns the sample or code size a track declares.
     *
     * @param codec the codec of the track
     * @param description the {@code desc} chunk
     * @return the size in bits, or 0 when not applicable
     */
    private static int bitsPerSample(Codec codec, Description description) {
        return switch (codec) {
            case PCM_ALAW, PCM_MULAW -> 8;
            case ADPCM_IMA_QT -> 4;
            default -> codec.isPcm() ? codec.pcmBitsPerSample().orElseThrow() : 0;
        };
    }

    @Override
    public ContainerFormat format() {
        return ContainerFormat.CAF;
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
        return track.duration() < 0 ? -1 : track.timeBase().toMicros(track.duration());
    }

    /**
     * {@inheritDoc}
     *
     * @return {@code true}
     */
    @Override
    public boolean isSeekable() {
        return true;
    }

    @Override
    public void seek(long micros) {
        var frame = micros <= 0 ? 0 : micros >= Long.MAX_VALUE / rate ? Long.MAX_VALUE : micros * rate / 1_000_000;
        if (frames == null) {
            var index = frame / packetFrames;
            // packets of several frames restart on the grid of the groups a linear read returns
            packetIndex = index >= packetCount ? packetCount : packetFrames == 1 ? index : index - index % groupPackets;
            return;
        }
        var count = (int) packetCount;
        var low = 0;
        var high = count + 1;
        while (low < high) {
            var middle = (low + high) >>> 1;
            if (frames[middle] > frame) {
                high = middle;
            } else {
                low = middle + 1;
            }
        }
        packetIndex = Math.max(0, Math.min(count, low - 1));
    }

    /**
     * Returns an AAC track described by the richest layer of an {@code ldsc} chunk: its rate and channel layout, the
     * priming frames rescaled from the rate of the {@code desc} chunk to it.
     *
     * @param declared the track the {@code desc} chunk describes
     * @param formats the body of the {@code ldsc} chunk: entries of a {@code desc} body and a channel layout tag
     * @param limits the resource bounds
     * @return the track, or {@code null} when the first entry is not an AAC layer at least as rich as the core
     */
    private static AudioTrack layered(AudioTrack declared, byte[] formats, MediaLimits limits) {
        if (formats.length < 36) {
            return null;
        }
        var rate = Double.longBitsToDouble(ByteArrays.i64be(formats, 0));
        var formatId = ByteArrays.i32be(formats, 8);
        var channels = ByteArrays.i32be(formats, 24);
        var aac = formatId == 0x61616320 || formatId == 0x61616368 || formatId == 0x61616370;
        var declaredRate = declared.sampleRate();
        var declaredLayout = declared.layout().orElseThrow();
        if (!aac || !(rate >= declaredRate && rate <= 8.0 * declaredRate) || channels < declaredLayout.channels()
                || channels > 2 * declaredLayout.channels()) {
            return null;
        }
        LimitChecks.checkChannels(limits, channels);
        var outputRate = (int) Math.round(rate);
        var chan = new byte[12];
        System.arraycopy(formats, 32, chan, 0, 4);
        var layout = CafChannelLayouts.read(chan, channels);
        if (layout == null || layout.speakers().stream().allMatch(speaker -> speaker == Speaker.UNKNOWN)) {
            layout = channels == declaredLayout.channels() ? declaredLayout : ChannelLayout.defaultFor(channels);
        }
        return declared.toBuilder()
                .sampleRate(outputRate)
                .layout(layout)
                .skipSamples(declared.skipSamples() * outputRate / declaredRate)
                .build();
    }

    /**
     * Reads the payload of the first packet of variable size.
     *
     * @return the payload, or {@code null} when there is no packet or it is truncated
     */
    private byte[] firstPacket() {
        if (frames == null || packetCount == 0) {
            return null;
        }
        var bytes = new byte[(int) (offsets[1] - offsets[0])];
        return data.readUpTo(dataStart + offsets[0], bytes, 0, bytes.length) < bytes.length ? null : bytes;
    }

    /**
     * {@inheritDoc}
     *
     * @throws MediaException.Malformed {@inheritDoc}
     * @throws MediaException.Io {@inheritDoc}
     */
    @Override
    public Packet read() {
        try {
            if (packetIndex >= packetCount) {
                return null;
            }
            if (frames == null) {
                var count = (int) Math.min(groupPackets, packetCount - packetIndex);
                var bytes = new byte[count * packetBytes];
                var done = data.readUpTo(dataStart + packetIndex * packetBytes, bytes, 0, bytes.length);
                if (done < bytes.length) {
                    count = done / packetBytes;
                    if (count == 0) {
                        packetIndex = packetCount;
                        return null;
                    }
                    bytes = Arrays.copyOf(bytes, count * packetBytes);
                }
                var start = packetIndex * packetFrames;
                var packet = new Packet(track.id(), bytes, start, start, (long) count * packetFrames, true);
                packetIndex += count;
                ForwardOnlySource.release(source, dataStart + packetIndex * packetBytes);
                return packet;
            }
            var index = (int) packetIndex;
            var size = (int) (offsets[index + 1] - offsets[index]);
            var bytes = new byte[size];
            if (data.readUpTo(dataStart + offsets[index], bytes, 0, size) < size) {
                packetIndex = packetCount;
                return null;
            }
            var start = frames[index];
            var packet = new Packet(track.id(), bytes, start, start, frames[index + 1] - start, true);
            packetIndex++;
            ForwardOnlySource.release(source, dataStart + offsets[index + 1]);
            return packet;
        } catch (MediaException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new MediaException.Malformed("invalid CAF data", exception);
        }
    }
}
