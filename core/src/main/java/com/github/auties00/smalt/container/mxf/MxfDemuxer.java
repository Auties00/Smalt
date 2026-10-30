package com.github.auties00.smalt.container.mxf;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.container.Demuxer;
import com.github.auties00.smalt.container.DemuxerOptions;
import com.github.auties00.smalt.container.Timecode;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ColorPrimaries;
import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.image.MatrixCoefficients;
import com.github.auties00.smalt.image.TransferCharacteristics;
import com.github.auties00.smalt.internal.codec.CodecConfigs;
import com.github.auties00.smalt.internal.codec.NalConfigs;
import com.github.auties00.smalt.internal.codec.VideoConfigs;
import com.github.auties00.smalt.internal.io.ForwardOnlySource;
import com.github.auties00.smalt.internal.io.SourceReader;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.internal.nal.NalUnits;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.DataTrack;
import com.github.auties00.smalt.track.Interlacing;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.Track;
import com.github.auties00.smalt.track.VideoTrack;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Reads Material Exchange Format files ({@code .mxf}, SMPTE ST 377-1): the essence of their file packages, in the
 * generic container (SMPTE ST 379-2), frame or clip wrapped, of any operational pattern whose essence is internal to
 * the file, OP1a and OP-Atom included.
 *
 * <p>The header metadata is taken from the partition whose metadata is final: a closed and complete partition when
 * there is one (the footer of a file whose header was written open), found through the random index pack or the
 * footer partition offset, and otherwise the header partition. A run-in of up to 64 KiB before the header partition
 * is skipped. Every essence track of a file package with a nonzero track number becomes a track, described by its
 * descriptor (the sub-descriptor linked to it for a multiple descriptor):
 * <table class="striped">
 * <caption>Essence</caption>
 * <thead><tr><th scope="col">Essence</th><th scope="col">Codec</th><th scope="col">Packets and configuration</th>
 * </tr></thead>
 * <tbody>
 * <tr><td>VC-3 (SMPTE ST 2019-4)</td><td>{@link Codec#DNXHD}</td><td>one frame each; configuration {@code AVdn} or
 * {@code AVdh} from the frame header</td></tr>
 * <tr><td>ProRes (SMPTE RDD 44)</td><td>{@link Codec#PRORES}</td><td>one frame each; configuration the sample entry
 * type of the profile of the picture coding label ({@code apco} to {@code ap4x}), or of the first frame</td></tr>
 * <tr><td>AVC (SMPTE ST 381-3), AVC-Intra (SMPTE RP 2027) included</td><td>{@link Codec#H264}</td><td>one access unit
 * each, as length-prefixed NAL units; configuration an {@code avcC} record of the parameter sets of the first access
 * unit or, for a headerless AVC-Intra stream, of the parameter sets of its class and raster</td></tr>
 * <tr><td>MPEG-1, MPEG-2 and MPEG-4 Part 2 video (SMPTE ST 381-1)</td><td>{@link Codec#MPEG1_VIDEO},
 * {@link Codec#MPEG2_VIDEO}, {@link Codec#MPEG4_PART2}</td><td>one picture each</td></tr>
 * <tr><td>JPEG 2000 (SMPTE ST 422)</td><td>{@link Codec#JPEG2000}</td><td>one codestream each</td></tr>
 * <tr><td>Broadcast Wave and AES3 audio (SMPTE ST 382)</td><td>{@link Codec#PCM_S16LE}, {@link Codec#PCM_S24LE},
 * {@link Codec#PCM_S32LE}, {@link Codec#PCM_U8}, and the big-endian codecs for AIFF coded sound</td><td>the samples of
 * one edit unit (frame wrapped) or of at most 100 ms (clip wrapped)</td></tr>
 * </tbody>
 * </table>
 * Other picture, sound and data essence becomes a track of {@link Codec#UNKNOWN_VIDEO},
 * {@link Codec#UNKNOWN_AUDIO} or {@link Codec#DATA} whose packets are the essence elements as stored.
 *
 * <p>Picture timestamps count edit units of the track (the time base is the inverse of the edit rate): the display
 * position of each frame, from the temporal offsets of the index table for reordered streams (MPEG-2 without them
 * takes it from the temporal reference of its pictures, AVC from the picture order counts of each IDR period, read
 * ahead), and decoding timestamps the stored position minus the decoding delay of the AVC sub-descriptor or, without
 * one, the reordering delay. Sound timestamps count
 * samples. {@link #metadata()} holds the material package name ({@code title}), the product of the last
 * identification set ({@code company_name}, {@code product_name}, {@code product_version},
 * {@code application_platform}, {@code modification_date}), {@code operational_pattern},
 * {@code material_package_umid} and the user comments of the material package under their own names;
 * {@link #trackMetadata(int)} holds the track names as {@code title}, and {@link #timecode()} the start of the first
 * timecode track of the material package, else of a file or tape package.
 *
 * <p>Seeking uses the index tables and, without them, the positions of the frames read so far, reading on from the
 * last one known. A source read once, front to back, is read in file order without the footer, and cannot seek.
 *
 * <p>Instances are not thread-safe.
 */
public final class MxfDemuxer implements Demuxer {
    /**
     * The largest run-in skipped before the header partition, in bytes.
     */
    private static final int MAX_RUN_IN = 65536;

    /**
     * The largest header metadata read, in bytes.
     */
    private static final long MAX_HEADER = 1L << 26;

    /**
     * The largest number of header metadata sets read.
     */
    private static final int MAX_SETS = 1 << 18;

    /**
     * The largest number of partitions followed.
     */
    private static final int MAX_PARTITIONS = 1 << 20;

    /**
     * The largest element read into one packet, in bytes.
     */
    private static final long MAX_ELEMENT = 1L << 30;

    /**
     * The bytes of the first element read to describe a picture track.
     */
    private static final int PROBE_BYTES = 1 << 20;

    /**
     * The largest number of KLV packets walked to find the first element of a track.
     */
    private static final int MAX_PROBE_PACKETS = 1 << 16;

    /**
     * The source read.
     */
    private final MediaSource source;

    /**
     * The resource bounds.
     */
    private final MediaLimits limits;

    /**
     * The buffered reader over the source.
     */
    private final SourceReader reader;

    /**
     * The file offset of the header partition pack, which every partition offset is relative to.
     */
    private final long runIn;

    /**
     * The partitions known, by file offset.
     */
    private final TreeMap<Long, PartitionInfo> partitions = new TreeMap<>();

    /**
     * The streams of the tracks exposed.
     */
    private final List<MxfStream> streams;

    /**
     * The streams by track number.
     */
    private final Map<Integer, MxfStream> byNumber = new HashMap<>();

    /**
     * The tracks exposed.
     */
    private final List<Track> tracks;

    /**
     * The file-level tags.
     */
    private final Map<String, String> metadata;

    /**
     * The tags of each track, by track id.
     */
    private final Map<Integer, Map<String, String>> trackMetadata;

    /**
     * The timecode of the first frame, or {@code null}.
     */
    private final Timecode timecode;

    /**
     * The duration of the presentation in microseconds, or {@code -1}.
     */
    private final long durationMicros;

    /**
     * The index of each essence container, by body stream id.
     */
    private final Map<Long, MxfIndex> indexes = new HashMap<>();

    /**
     * The file offset where the essence starts.
     */
    private final long essenceStart;

    /**
     * Whether the source is read once, front to back.
     */
    private final boolean forwardOnly;

    /**
     * The key of the packet being read.
     */
    private final byte[] key = new byte[16];

    /**
     * The file offset of the next packet to read.
     */
    private long position;

    /**
     * The packets already split from an element and not returned yet.
     */
    private final ArrayDeque<Packet> pending = new ArrayDeque<>();

    /**
     * The stream whose clip is being read, or {@code null}.
     */
    private MxfStream clip;

    /**
     * The file offset of the next byte of the clip being read.
     */
    private long clipPosition;

    /**
     * The file offset of the end of the clip being read.
     */
    private long clipEnd;

    /**
     * The file offset of the first packet after the clip being read.
     */
    private long afterClip;

    /**
     * The file offset of the key of the element being turned into a packet, or {@code -1} for a slice of a clip.
     */
    private long currentElement = -1;

    /**
     * The value offset of the last packet header read.
     */
    private long valueStart;

    /**
     * The value length of the last packet header read.
     */
    private long valueLength;

    /**
     * Constructs a demuxer over {@code source} with {@link DemuxerOptions#defaults()}.
     *
     * @param source the input, not closed by this demuxer
     * @throws MediaException.Malformed if the file is not a valid MXF file
     * @throws MediaException.LimitExceeded if the header metadata, an index table or a picture exceeds the limits
     * @throws MediaException.Io if reading fails
     */
    public MxfDemuxer(MediaSource source) {
        this(source, DemuxerOptions.defaults());
    }

    /**
     * Constructs a demuxer over {@code source} that enforces the limits of {@code options}.
     *
     * @param source the input, not closed by this demuxer
     * @param options the demuxing options
     * @throws MediaException.Malformed if the file is not a valid MXF file
     * @throws MediaException.LimitExceeded if the header metadata, an index table or a picture exceeds the limits
     * @throws MediaException.Io if reading fails
     */
    public MxfDemuxer(MediaSource source, DemuxerOptions options) {
        this.source = Objects.requireNonNull(source, "source");
        this.limits = Objects.requireNonNull(options, "options").limits();
        this.reader = new SourceReader(source, 64 * 1024);
        this.forwardOnly = ForwardOnlySource.isForwardOnly(source);
        try {
            this.runIn = findHeader();
            var header = partitionAt(runIn);
            if (header == null || header.pack.kind() != 2) {
                throw new MediaException.Malformed("MXF file without a header partition");
            }
            discoverPartitions(header);
            var chosen = header;
            for (var partition : partitions.values()) {
                if (partition.pack.headerByteCount() > 0 && partition.pack.rank() > chosen.pack.rank()
                        || partition.pack.headerByteCount() > 0 && partition.pack.rank() == chosen.pack.rank()
                        && chosen.pack.headerByteCount() == 0) {
                    chosen = partition;
                }
            }
            var sets = headerMetadata(chosen);
            if (sets == null && chosen != header) {
                sets = headerMetadata(header);
            }
            if (sets == null) {
                throw new MediaException.Malformed("MXF file without header metadata");
            }
            var structure = new MxfStructure(sets);
            structure.resolve();
            for (var partition : partitions.values()) {
                readIndexes(partition);
            }
            this.essenceStart = header.essence >= 0 ? header.essence : header.end;
            this.position = essenceStart;
            var built = new ArrayList<MxfStream>();
            var ids = new HashSet<Integer>();
            for (var stream : structure.streams) {
                if (byNumber.containsKey(stream.trackNumber)) {
                    continue;
                }
                describe(stream, structure);
                stream.id = stream.fileTrackId > 0 && ids.add(stream.fileTrackId) ? stream.fileTrackId : -1;
                built.add(stream);
                byNumber.put(stream.trackNumber, stream);
            }
            var next = 1;
            for (var stream : built) {
                if (stream.id < 0) {
                    while (ids.contains(next)) {
                        next++;
                    }
                    stream.id = next;
                    ids.add(next);
                }
            }
            for (var stream : built) {
                stream.track = buildTrack(stream, structure);
                if (!stream.clipWrapped && holdsSeveralUnits(stream)) {
                    stream.clipWrapped = true;
                }
            }
            this.streams = List.copyOf(built);
            var trackList = new ArrayList<Track>();
            var perTrack = new HashMap<Integer, Map<String, String>>();
            var duration = -1L;
            for (var stream : streams) {
                trackList.add(stream.track);
                var tags = structure.trackMetadata.get(stream.trackNumber);
                if (tags != null) {
                    perTrack.put(stream.id, Collections.unmodifiableMap(tags));
                }
                var micros = stream.track.durationMicros();
                if (micros.isPresent()) {
                    duration = Math.max(duration, micros.getAsLong());
                }
            }
            if (duration < 0 && structure.materialRate != null && structure.materialDuration >= 0) {
                duration = new Rational(structure.materialRate.denominator(), structure.materialRate.numerator())
                        .toMicros(structure.materialDuration);
            }
            this.tracks = List.copyOf(trackList);
            this.trackMetadata = perTrack;
            this.metadata = Collections.unmodifiableMap(new LinkedHashMap<>(structure.metadata));
            this.timecode = structure.timecode;
            this.durationMicros = duration;
        } catch (MediaException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new MediaException.Malformed("invalid MXF file", exception);
        }
    }

    /**
     * Recognizes an MXF file: a header partition pack key at the start or after a run-in.
     *
     * @param header the leading bytes of the input
     * @return {@code true} when a header partition pack key occurs in them
     */
    public static boolean matches(byte[] header) {
        Objects.requireNonNull(header, "header");
        return headerOffset(header, header.length) >= 0;
    }

    /**
     * Returns the offset of the first header partition pack key in a byte range.
     *
     * @param data the bytes
     * @param length the number of bytes to search
     * @return the offset, or {@code -1}
     */
    private static int headerOffset(byte[] data, int length) {
        var prefix = MxfLabels.PARTITION_PREFIX;
        for (var at = 0; at + 16 <= Math.min(length, data.length) && at <= MAX_RUN_IN; at++) {
            if (data[at] != 0x06 || data[at + 1] != 0x0E || data[at + 2] != 0x2B || data[at + 3] != 0x34) {
                continue;
            }
            var match = true;
            for (var index = 4; index < prefix.length && match; index++) {
                match = index == 7 || data[at + index] == prefix[index];
            }
            if (match && data[at + 12] == 0x01 && data[at + 13] == 0x02 && data[at + 14] >= 1 && data[at + 14] <= 4) {
                return at;
            }
        }
        return -1;
    }

    /**
     * Finds the header partition pack.
     *
     * @return its file offset
     */
    private long findHeader() {
        var length = (int) Math.min(MAX_RUN_IN + 16L, reader.remaining(MAX_RUN_IN + 16L));
        var leading = new byte[length];
        reader.seek(0);
        reader.readFully(leading, 0, length);
        var offset = headerOffset(leading, length);
        if (offset < 0) {
            throw new MediaException.Malformed("not an MXF file");
        }
        return offset;
    }

    /**
     * A partition: its pack and where its parts start.
     */
    private static final class PartitionInfo {
        /**
         * The partition pack.
         */
        private final MxfPartition pack;

        /**
         * The file offset of the partition pack key.
         */
        private final long offset;

        /**
         * The file offset just past the partition pack.
         */
        private final long end;

        /**
         * The file offset of the primer pack, or {@code -1} when the partition holds no header metadata.
         */
        private long metadata = -1;

        /**
         * The file offset of the first index table segment, or {@code -1}.
         */
        private long index = -1;

        /**
         * The file offset of the first essence packet, or {@code -1} when not found yet.
         */
        private long essence = -1;

        /**
         * Whether the partition's parts were walked.
         */
        private boolean walked;

        PartitionInfo(MxfPartition pack, long offset, long end) {
            this.pack = pack;
            this.offset = offset;
            this.end = end;
        }
    }

    /**
     * Reads a packet header: the key into {@link #key}, {@link #valueStart} and {@link #valueLength}.
     *
     * @param at the file offset of the key
     * @return {@code false} at the end of the input
     * @throws MediaException.Malformed if the length is invalid or the packet is cut by the end of the input
     */
    private boolean header(long at) {
        reader.seek(at);
        if (reader.remaining(17) < 17) {
            return false;
        }
        reader.readFully(key, 0, 16);
        var first = reader.u8();
        long length;
        if (first < 0x80) {
            length = first;
        } else {
            var count = first & 0x7F;
            if (count == 0 || count > 8) {
                throw new MediaException.Malformed("invalid MXF length of " + count + " bytes at offset " + at);
            }
            length = 0;
            for (var index = 0; index < count; index++) {
                length = length << 8 | reader.u8();
            }
            if (length < 0) {
                throw new MediaException.Malformed("MXF length overflows at offset " + at);
            }
        }
        valueStart = reader.position();
        valueLength = length;
        return true;
    }

    /**
     * Reads the partition pack at a file offset.
     *
     * @param at the file offset
     * @return the partition, also recorded, or {@code null} when no partition pack is there
     */
    private PartitionInfo partitionAt(long at) {
        var known = partitions.get(at);
        if (known != null) {
            return known;
        }
        if (!header(at) || !MxfLabels.isPartition(key)) {
            return null;
        }
        if (valueLength > 1 << 20) {
            throw new MediaException.Malformed("MXF partition pack of " + valueLength + " bytes");
        }
        var partitionKey = key.clone();
        var value = reader.bytes(valueLength, 1 << 20);
        if (partitions.size() >= MAX_PARTITIONS) {
            throw new MediaException.LimitExceeded("MXF file of more than " + MAX_PARTITIONS + " partitions");
        }
        var info = new PartitionInfo(MxfPartition.parse(partitionKey, value), at, valueStart + valueLength);
        partitions.put(at, info);
        return info;
    }

    /**
     * Finds the partitions through the random index pack or the chain of previous partitions from the footer.
     *
     * @param header the header partition
     */
    private void discoverPartitions(PartitionInfo header) {
        walk(header);
        var size = source.size();
        if (size >= 0 && (!forwardOnly || ForwardOnlySource.isCheapToReach(source, size)) && readRandomIndex(size)) {
            return;
        }
        var footer = header.pack.footer();
        if (footer <= 0 || forwardOnly && !ForwardOnlySource.isCheapToReach(source, runIn + footer + (1 << 16))) {
            return;
        }
        var at = runIn + footer;
        var count = 0;
        while (at >= runIn && (size < 0 || at < size) && count++ < MAX_PARTITIONS) {
            var partition = partitionAt(at);
            if (partition == null) {
                break;
            }
            walk(partition);
            var previous = runIn + partition.pack.previous();
            if (previous >= at) {
                break;
            }
            at = previous;
        }
    }

    /**
     * Reads the random index pack at the end of the input and the partitions it lists.
     *
     * @param size the input size
     * @return {@code true} when a valid random index pack was found
     */
    private boolean readRandomIndex(long size) {
        if (size - runIn < 20 + 16) {
            return false;
        }
        reader.seek(size - 4);
        var length = reader.u32be();
        if (length < 21 || length > size - runIn || length > 12L * MAX_PARTITIONS + 64) {
            return false;
        }
        var start = size - length;
        if (!header(start) || !MxfLabels.startsWith(key, MxfLabels.RANDOM_INDEX)
                || valueStart + valueLength != size) {
            return false;
        }
        var entries = (valueLength - 4) / 12;
        var offsets = new ArrayList<Long>();
        for (var index = 0; index < entries; index++) {
            reader.seek(valueStart + 12 * index + 4);
            offsets.add(reader.u64be());
        }
        var found = false;
        for (var offset : offsets) {
            if (offset < 0 || runIn + offset >= size) {
                continue;
            }
            var partition = partitionAt(runIn + offset);
            if (partition != null) {
                walk(partition);
                found = true;
            }
        }
        return found;
    }

    /**
     * Walks the packets that follow a partition pack to find its header metadata, its index table segments and its
     * first essence packet.
     *
     * @param partition the partition, walked if it was not yet
     */
    private void walk(PartitionInfo partition) {
        if (partition.walked) {
            return;
        }
        partition.walked = true;
        var at = partition.end;
        var count = 0;
        while (count++ < MAX_PROBE_PACKETS && header(at)) {
            var next = valueStart + valueLength;
            if (MxfLabels.isPartition(key) || MxfLabels.startsWith(key, MxfLabels.RANDOM_INDEX)) {
                return;
            }
            if (MxfLabels.startsWith(key, MxfLabels.PRIMER)) {
                if (partition.metadata < 0) {
                    partition.metadata = at;
                }
            } else if (MxfLabels.startsWith(key, MxfLabels.INDEX_SEGMENT)) {
                if (partition.index < 0) {
                    partition.index = at;
                }
            } else if (!MxfLabels.isFill(key) && !MxfLabels.isSet(key)) {
                partition.essence = at;
                return;
            }
            at = next;
        }
    }

    /**
     * Reads the header metadata sets of a partition.
     *
     * @param partition the partition, walked if it was not yet
     * @return the sets, or {@code null} when the partition holds none
     */
    private Map<MxfSet.Uid, MxfSet> headerMetadata(PartitionInfo partition) {
        walk(partition);
        if (partition.metadata < 0) {
            return null;
        }
        var end = partition.metadata;
        var count = 0;
        while (count++ < MAX_SETS && header(end)) {
            if (!MxfLabels.startsWith(key, MxfLabels.PRIMER) && !MxfLabels.isSet(key) && !MxfLabels.isFill(key)
                    || MxfLabels.startsWith(key, MxfLabels.INDEX_SEGMENT)) {
                break;
            }
            end = valueStart + valueLength;
            if (end - partition.metadata > MAX_HEADER) {
                throw new MediaException.LimitExceeded("MXF header metadata of more than " + MAX_HEADER + " bytes");
            }
        }
        var length = end - partition.metadata;
        LimitChecks.checkAllocation(limits, length);
        reader.seek(partition.metadata);
        var data = reader.bytes(length, MAX_HEADER);
        return MxfSet.parseAll(data, data.length, MAX_SETS);
    }

    /**
     * Reads the index table segments of a partition into the index of their essence container.
     *
     * @param partition the partition, walked if it was not yet
     */
    private void readIndexes(PartitionInfo partition) {
        if (partition.index < 0) {
            return;
        }
        var at = partition.index;
        var count = 0;
        while (count++ < MAX_PROBE_PACKETS && header(at)) {
            if (MxfLabels.startsWith(key, MxfLabels.INDEX_SEGMENT)) {
                if (valueLength > 1 << 28) {
                    throw new MediaException.LimitExceeded("MXF index table segment of " + valueLength + " bytes");
                }
                LimitChecks.checkAllocation(limits, valueLength);
                var segment = MxfSet.parseStatic(key.clone(), reader.bytes(valueLength, 1 << 28));
                var body = segment.u32(0x3F07, partition.pack.bodySid());
                indexes.computeIfAbsent(body, ignored -> new MxfIndex()).add(segment, maxEntries());
            } else if (!MxfLabels.isFill(key)) {
                break;
            }
            at = valueStart + valueLength;
        }
    }

    /**
     * {@return the largest number of edit units an index may hold}
     */
    private long maxEntries() {
        return Math.min(Integer.MAX_VALUE - 16L, Math.max(1L << 16, limits.maxBytes() / 16));
    }

    /**
     * Completes the description of a stream: its codec, and for a picture its parameters from its first element.
     *
     * @param stream the essence track read
     * @param structure the resolved header metadata
     */
    private void describe(MxfStream stream, MxfStructure structure) {
        stream.codec = switch (stream.kind) {
            case 'p' -> pictureCodec(stream);
            case 's' -> soundCodec(stream, structure.soundKind.getOrDefault(stream, MxfLabels.SOUND_DESCRIPTOR));
            default -> Codec.DATA;
        };
        if (stream.kind == 's') {
            var bytes = stream.bits <= 0 ? 0 : (stream.bits + 7) / 8;
            if (stream.blockAlign <= 0 && stream.channels > 0 && bytes > 0) {
                stream.blockAlign = stream.channels * bytes;
            }
            if (stream.codec.isPcm()) {
                if (stream.channels <= 0 || stream.sampleRate <= 0 || stream.blockAlign <= 0
                        || stream.blockAlign % stream.channels != 0) {
                    throw new MediaException.Malformed("MXF sound descriptor of " + stream.channels + " channels, "
                            + stream.blockAlign + " bytes per sample and " + stream.sampleRate + " Hz");
                }
                LimitChecks.checkChannels(limits, stream.channels);
                stream.codec = pcm(stream.blockAlign / stream.channels, stream.codec);
            }
        }
        var index = indexes.get(stream.bodySid);
        if (index != null && index.isReordered() && stream.kind == 'p') {
            stream.display = index.displayPositions();
            var delay = 0L;
            for (var unit = 0; unit < stream.display.length; unit++) {
                delay = Math.max(delay, unit - stream.display[unit]);
            }
            stream.delay = delay;
        } else if (stream.kind == 'p' && stream.codec == Codec.MPEG2_VIDEO && !isIntraCoding(stream.coding)) {
            stream.delay = 1;
        }
        stream.orderCandidate = index == null || index.isEmpty() || index.hasBidirectional();
        if (stream.decodingDelay != 0xFF && stream.kind == 'p') {
            stream.delay = stream.decodingDelay;
            stream.delayKnown = true;
        }
    }

    /**
     * {@return the PCM codec of a sample size, keeping the byte order of the codec found}
     *
     * @param bytes the bytes of one sample of one channel
     * @param codec a PCM codec of the stream's byte order
     */
    private static Codec pcm(int bytes, Codec codec) {
        var bigEndian = codec == Codec.PCM_S16BE || codec == Codec.PCM_S24BE || codec == Codec.PCM_S32BE;
        return switch (bytes) {
            case 1 -> codec == Codec.PCM_S8 ? Codec.PCM_S8 : Codec.PCM_U8;
            case 2 -> bigEndian ? Codec.PCM_S16BE : Codec.PCM_S16LE;
            case 3 -> bigEndian ? Codec.PCM_S24BE : Codec.PCM_S24LE;
            case 4 -> bigEndian ? Codec.PCM_S32BE : Codec.PCM_S32LE;
            default -> throw new MediaException.Unsupported("mxf pcm", "MXF sound of " + 8 * bytes
                    + "-bit samples is not supported");
        };
    }

    /**
     * {@return the codec of a picture stream, from its essence container and picture coding labels}
     *
     * @param stream the essence track read
     */
    private static Codec pictureCodec(MxfStream stream) {
        var coding = stream.coding;
        var family = stream.container != null && MxfLabels.startsWith(stream.container,
                MxfLabels.bytes("060e2b34040101000d01030102")) ? stream.container[13] : -1;
        if (coding != null && MxfLabels.startsWith(coding, MxfLabels.bytes("060e2b3404010100040102"))) {
            var scheme = coding[11];
            if (scheme == 0x02 && coding[12] == 0x71) {
                return Codec.DNXHD;
            }
            if (scheme == 0x02 && coding[12] == 0x03 && coding[13] == 0x06) {
                return Codec.PRORES;
            }
            if (scheme == 0x02 && coding[12] == 0x03 && coding[13] == 0x01) {
                return Codec.JPEG2000;
            }
            if (scheme == 0x02 && coding[12] == 0x01) {
                var variant = coding[13] & 0xFF;
                if (variant >= 0x30 && variant <= 0x3F) {
                    return Codec.H264;
                }
                if (variant >= 0x01 && variant <= 0x09) {
                    return Codec.MPEG2_VIDEO;
                }
                if (variant == 0x10) {
                    return Codec.MPEG1_VIDEO;
                }
                if (variant == 0x20) {
                    return Codec.MPEG4_PART2;
                }
            }
        }
        return switch (family) {
            case 0x11 -> Codec.DNXHD;
            case 0x1C -> Codec.PRORES;
            case 0x10, 0x0F -> Codec.H264;
            case 0x04 -> Codec.MPEG2_VIDEO;
            case 0x0C -> Codec.JPEG2000;
            default -> Codec.UNKNOWN_VIDEO;
        };
    }

    /**
     * {@return the codec family of a sound stream: a PCM codec of its byte order, or {@link Codec#UNKNOWN_AUDIO}}
     *
     * @param stream the essence track read
     * @param descriptorKind the set byte of its descriptor
     */
    private static Codec soundCodec(MxfStream stream, int descriptorKind) {
        var coding = stream.coding;
        var family = stream.container != null && MxfLabels.startsWith(stream.container,
                MxfLabels.bytes("060e2b34040101000d01030102")) ? stream.container[13] : -1;
        if (coding != null && MxfLabels.startsWith(coding, MxfLabels.bytes("060e2b3404010100040202"))) {
            if (coding[11] == 0x01 && coding[12] == 0x7E) {
                return Codec.PCM_S16BE;
            }
            if (coding[11] == 0x01 && (coding[12] == 0x00 || coding[12] == 0x01 || coding[12] == 0x7F)) {
                return Codec.PCM_S16LE;
            }
            if (coding[11] != 0x01) {
                return Codec.UNKNOWN_AUDIO;
            }
        }
        if (family == 0x06 || descriptorKind == MxfLabels.WAVE_DESCRIPTOR
                || descriptorKind == MxfLabels.AES3_DESCRIPTOR || coding == null && family == -1) {
            return Codec.PCM_S16LE;
        }
        return Codec.UNKNOWN_AUDIO;
    }

    /**
     * {@return whether a picture coding label names an intra-only MPEG-2 coding: an I-frame only label, or a D-10
     * (SMPTE ST 356) label of 422P@ML}
     *
     * @param coding the label, or {@code null}
     */
    private static boolean isIntraCoding(byte[] coding) {
        if (coding == null || coding[12] != 0x01) {
            return false;
        }
        var variant = coding[13] & 0xFF;
        var kind = coding[14] & 0xFF;
        return variant == 0x01 ? kind == 0x10
                : variant == 0x02 && kind == 0x01 || variant >= 0x02 && variant <= 0x08 && kind == 0x02;
    }

    /**
     * Builds the track of a stream.
     *
     * @param stream the essence track read
     * @param structure the resolved header metadata
     * @return the track
     */
    private Track buildTrack(MxfStream stream, MxfStructure structure) {
        var editTime = new Rational(stream.editRate.denominator(), stream.editRate.numerator());
        return switch (stream.kind) {
            case 'p' -> videoTrack(stream, structure.pictureDetails.get(stream), editTime);
            case 's' -> {
                if (stream.sampleRate <= 0) {
                    throw new MediaException.Malformed("MXF sound track without a sampling rate");
                }
                var duration = stream.duration < 0 ? -1
                        : editTime.rescale(stream.duration, Rational.timeBaseOf(stream.sampleRate));
                var builder = AudioTrack.builder().id(stream.id).codec(stream.codec)
                        .timeBase(Rational.timeBaseOf(stream.sampleRate)).duration(duration)
                        .sampleRate(stream.sampleRate);
                if (stream.channels > 0) {
                    builder.layout(ChannelLayout.defaultFor(stream.channels));
                }
                if (stream.bits > 0) {
                    builder.bitsPerSample(stream.bits);
                }
                yield builder.build();
            }
            default -> DataTrack.builder().id(stream.id).codec(Codec.DATA).timeBase(editTime)
                    .duration(stream.duration).build();
        };
    }

    /**
     * Builds the track of a picture stream.
     *
     * @param stream the essence track read
     * @param descriptor the picture descriptor, or {@code null}
     * @param editTime the time base, the inverse of the edit rate
     * @return the track
     */
    private VideoTrack videoTrack(MxfStream stream, MxfSet descriptor, Rational editTime) {
        var builder = VideoTrack.builder().id(stream.id).codec(stream.codec).timeBase(editTime)
                .duration(stream.duration).frameRate(stream.editRate);
        var width = stream.width;
        var height = stream.height;
        byte[] config = null;
        var first = stream.codec == Codec.UNKNOWN_VIDEO ? null : firstElement(stream);
        switch (stream.codec) {
            case DNXHD -> {
                config = first == null ? null : fourCc(CodecConfigs.quickTimeVisualType(false, null, first));
                if (first != null && first.length >= 0x2C) {
                    var lines = (first[0x18] & 0xFF) << 8 | first[0x19] & 0xFF;
                    var samples = (first[0x1A] & 0xFF) << 8 | first[0x1B] & 0xFF;
                    var interlaced = (first[5] & 0x02) != 0;
                    if (samples > 0 && lines > 0) {
                        width = samples;
                        height = interlaced ? 2 * lines : lines;
                    }
                }
            }
            case PRORES -> {
                var coding = stream.coding;
                if (coding != null && coding[12] == 0x03 && coding[13] == 0x06 && coding[14] >= 1
                        && coding[14] <= 6) {
                    config = fourCc(new String[]{"apco", "apcs", "apcn", "apch", "ap4h", "ap4x"}[coding[14] - 1]);
                } else if (first != null) {
                    config = fourCc(CodecConfigs.quickTimeVisualType(true, null, first));
                }
                if (first != null && first.length >= 20 && first[4] == 'i' && first[5] == 'c' && first[6] == 'p'
                        && first[7] == 'f') {
                    width = (first[16] & 0xFF) << 8 | first[17] & 0xFF;
                    var frameHeight = (first[18] & 0xFF) << 8 | first[19] & 0xFF;
                    if (frameHeight > 0) {
                        height = frameHeight;
                    }
                }
            }
            case H264 -> {
                var sets = first == null ? null : parameterSets(first, stream);
                if (sets == null) {
                    sets = headerlessSets(stream);
                    stream.headerless = sets;
                }
                if (sets == null) {
                    throw new MediaException.Malformed("MXF AVC track without parameter sets");
                }
                config = NalConfigs.avcConfig(List.of(sets[0]), List.of(sets[1]));
                if (stream.orderCandidate && stream.display == null && !isIntraStream(stream) && !stream.clipWrapped
                        && AvcOrder.of(sets[0]) != null) {
                    stream.orderSps = sets[0];
                }
                var info = VideoConfigs.avcSps(sets[0]);
                if (info != null && info.width() > 0) {
                    width = info.width();
                    height = info.height();
                    if (info.color() != null) {
                        builder.color(info.color());
                    }
                }
            }
            default -> {
            }
        }
        LimitChecks.checkPicture(limits, Math.max(1, width), Math.max(1, height));
        builder.width(width).height(height);
        if (config != null) {
            builder.codecConfig(config);
        }
        if (descriptor != null) {
            var aspect = MxfStructure.rational(descriptor.rational(0x320E));
            if (aspect != null && width > 0 && height > 0) {
                var sar = new Rational(aspect.numerator() * height, aspect.denominator() * width).reduce();
                if (sar.numerator() != sar.denominator()) {
                    builder.sampleAspectRatio(sar);
                }
            }
            var interlacing = interlacing(descriptor, height);
            if (interlacing != null) {
                builder.interlacing(interlacing);
            }
            var color = color(descriptor);
            if (color != null && stream.codec != Codec.H264) {
                builder.color(color);
            }
        }
        return builder.build();
    }

    /**
     * {@return four ASCII characters as bytes}
     *
     * @param code the characters packed big-endian
     */
    private static byte[] fourCc(int code) {
        return new byte[]{(byte) (code >>> 24), (byte) (code >>> 16), (byte) (code >>> 8), (byte) code};
    }

    private static byte[] fourCc(String code) {
        return code.getBytes(StandardCharsets.US_ASCII);
    }

    /**
     * Returns the scan a picture descriptor declares.
     *
     * @param descriptor the file descriptor set
     * @param height the frame height
     * @return the scan, or {@code null} when the descriptor declares none
     */
    private static Interlacing interlacing(MxfSet descriptor, int height) {
        if (!descriptor.has(0x320C)) {
            return null;
        }
        var layout = descriptor.u8(0x320C, 0);
        if (layout == 0 || layout == 4) {
            return Interlacing.PROGRESSIVE;
        }
        if (layout != 1 && layout != 3) {
            return null;
        }
        // Field 1 is the top field of 625 and 1125 line systems and the bottom field of 525 line systems
        var topFirst = height != 480 && height != 486;
        if (descriptor.u8(0x3212, 1) == 2) {
            topFirst = !topFirst;
        }
        return topFirst ? Interlacing.TOP_FIELD_FIRST : Interlacing.BOTTOM_FIELD_FIRST;
    }

    /**
     * Returns the color a picture descriptor declares.
     *
     * @param descriptor the file descriptor set
     * @return the color, or {@code null} when it declares neither transfer, primaries nor coding equations
     */
    private static ColorInfo color(MxfSet descriptor) {
        var transfer = descriptor.label(0x3210);
        var primaries = descriptor.label(0x3219);
        var equations = descriptor.label(0x321A);
        if (transfer == null && primaries == null && equations == null) {
            return null;
        }
        var matrix = MatrixCoefficients.UNSPECIFIED;
        if (equations != null && equations[12] == 0x02) {
            matrix = switch (equations[13]) {
                case 1 -> MatrixCoefficients.BT601;
                case 2 -> MatrixCoefficients.BT709;
                case 3 -> MatrixCoefficients.SMPTE240M;
                case 4 -> MatrixCoefficients.YCGCO;
                case 5 -> MatrixCoefficients.IDENTITY;
                case 6 -> MatrixCoefficients.BT2020_NCL;
                default -> MatrixCoefficients.UNSPECIFIED;
            };
        }
        var trc = TransferCharacteristics.UNSPECIFIED;
        if (transfer != null && transfer[12] == 0x01) {
            trc = switch (transfer[13]) {
                case 1 -> TransferCharacteristics.GAMMA28;
                case 2, 4 -> TransferCharacteristics.BT709;
                case 3 -> TransferCharacteristics.SMPTE240M;
                case 5 -> TransferCharacteristics.BT1361;
                case 6 -> TransferCharacteristics.LINEAR;
                case 8 -> TransferCharacteristics.IEC61966_2_4;
                case 9 -> TransferCharacteristics.BT2020_10;
                case 10 -> TransferCharacteristics.PQ;
                case 11 -> TransferCharacteristics.HLG;
                case 13 -> TransferCharacteristics.SRGB;
                default -> TransferCharacteristics.UNSPECIFIED;
            };
        }
        var gamut = ColorPrimaries.UNSPECIFIED;
        if (primaries != null && primaries[12] == 0x03) {
            gamut = switch (primaries[13]) {
                case 1 -> ColorPrimaries.SMPTE170M;
                case 2 -> ColorPrimaries.BT470BG;
                case 3 -> ColorPrimaries.BT709;
                case 4 -> ColorPrimaries.BT2020;
                case 5 -> ColorPrimaries.SMPTE428;
                case 6 -> ColorPrimaries.SMPTE432;
                case 10 -> ColorPrimaries.SMPTE431;
                default -> ColorPrimaries.UNSPECIFIED;
            };
        }
        var black = descriptor.u32(0x3304, -1);
        var white = descriptor.u32(0x3305, -1);
        var depth = descriptor.u32(0x3301, 8);
        var range = black == 0 && white == (1L << depth) - 1 ? ColorRange.FULL : ColorRange.LIMITED;
        return new ColorInfo(matrix, range, gamut, trc);
    }

    /**
     * Returns the sequence and picture parameter sets of the first access unit of an AVC stream.
     *
     * @param first the leading bytes of the first element
     * @param stream the stream, whose element form is recorded
     * @return the first sequence and picture parameter sets, or {@code null} when the access unit has none
     */
    private static byte[][] parameterSets(byte[] first, MxfStream stream) {
        var units = new ArrayList<byte[]>();
        stream.annexB = NalUnits.looksLikeAnnexB(first) || first.length == 0;
        if (stream.annexB) {
            NalUnits.forEachAnnexB(first, (array, offset, length) -> units.add(MxfSet.slice(array, offset, length)));
        } else {
            try {
                NalUnits.forEachLengthPrefixed(first, 4, (array, offset, length) ->
                        units.add(MxfSet.slice(array, offset, length)));
            } catch (RuntimeException exception) {
                return null;
            }
        }
        byte[] sps = null;
        byte[] pps = null;
        for (var unit : units) {
            var type = unit.length == 0 ? -1 : unit[0] & 0x1F;
            if (type == NalConfigs.AVC_SPS && sps == null) {
                sps = unit;
            } else if (type == NalConfigs.AVC_PPS && pps == null) {
                pps = unit;
            } else if (type == 1 || type == 5) {
                break;
            }
        }
        return sps == null || pps == null ? null : new byte[][]{sps, pps};
    }

    /**
     * Returns the parameter sets of the AVC-Intra class and raster of a headerless stream.
     *
     * @param stream the essence track read
     * @return the sequence and picture parameter sets, or {@code null} when the stream is not AVC-Intra
     */
    private static byte[][] headerlessSets(MxfStream stream) {
        var cls = AvcIntra.classOf(stream.coding);
        if (cls == 0) {
            return null;
        }
        var raster = AvcIntra.rasterOf(stream.coding, stream.height, stream.frameLayout == 1
                || stream.frameLayout == 3, stream.editRate);
        return raster < 0 ? null : AvcIntra.parameterSets(cls, raster);
    }

    /**
     * Reads the leading bytes of the first essence element of a stream.
     *
     * @param stream the essence track read
     * @return up to {@value #PROBE_BYTES} bytes, or {@code null} when the stream has no element in reach
     */
    private byte[] firstElement(MxfStream stream) {
        var at = essenceStart();
        var count = 0;
        while (count++ < MAX_PROBE_PACKETS && header(at)) {
            if (MxfLabels.startsWith(key, MxfLabels.RANDOM_INDEX)) {
                return null;
            }
            if (MxfLabels.isElement(key) && streamOf(key) == stream) {
                var length = (int) Math.min(valueLength, PROBE_BYTES);
                if (forwardOnly && !ForwardOnlySource.isCheapToReach(source, valueStart + length)) {
                    return null;
                }
                reader.seek(valueStart);
                var out = new byte[(int) Math.min(length, reader.remaining(length))];
                reader.readFully(out, 0, out.length);
                return out;
            }
            var next = valueStart + valueLength;
            if (forwardOnly && !ForwardOnlySource.isCheapToReach(source, next + 64)) {
                return null;
            }
            at = next;
        }
        return null;
    }

    /**
     * Returns whether the first element of a stream labeled frame wrapped holds several edit units: sound longer than
     * one and a half edit units, or a picture element of at least twice the constant edit unit size of its index. Such
     * a stream is read as clip wrapped.
     *
     * @param stream the essence track read
     * @return {@code true} when the first element holds several edit units
     */
    private boolean holdsSeveralUnits(MxfStream stream) {
        var at = essenceStart();
        var count = 0;
        while (count++ < MAX_PROBE_PACKETS && header(at)) {
            if (MxfLabels.startsWith(key, MxfLabels.RANDOM_INDEX)) {
                return false;
            }
            if (MxfLabels.isElement(key) && streamOf(key) == stream) {
                if (stream.kind == 's') {
                    var samples = stream.blockAlign == 0 ? 0 : valueLength / stream.blockAlign;
                    var perUnit = (double) stream.sampleRate * stream.editRate.denominator()
                            / stream.editRate.numerator();
                    return perUnit >= 1 && samples > 1.5 * perUnit;
                }
                var index = indexes.get(stream.bodySid);
                var size = index == null || index.entryCount() > 0 ? 0 : index.constantSize(0);
                return stream.kind == 'p' && size > 0 && valueLength >= 2 * size;
            }
            var next = valueStart + valueLength;
            if (forwardOnly && !ForwardOnlySource.isCheapToReach(source, next + 64)) {
                return false;
            }
            at = next;
        }
        return false;
    }

    /**
     * {@return the file offset where the essence of the header partition, or the partition after it, starts}
     */
    private long essenceStart() {
        var header = partitions.firstEntry().getValue();
        return header.essence >= 0 ? header.essence : header.end;
    }

    /**
     * Returns the stream an essence element key belongs to.
     *
     * @param elementKey the key
     * @return the stream, or {@code null}
     */
    private MxfStream streamOf(byte[] elementKey) {
        var number = MxfLabels.trackNumber(elementKey);
        var stream = byNumber.get(number);
        if (stream != null) {
            return stream;
        }
        for (var candidate : byNumber.values()) {
            if ((candidate.trackNumber & 0xFF00FFFF) == (number & 0xFF00FFFF)) {
                return candidate;
            }
        }
        return null;
    }

    @Override
    public ContainerFormat format() {
        return ContainerFormat.MXF;
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
    public Map<String, String> trackMetadata(int trackId) {
        return trackMetadata.getOrDefault(trackId, Map.of());
    }

    @Override
    public Optional<Timecode> timecode() {
        return Optional.ofNullable(timecode);
    }

    @Override
    public long durationMicros() {
        return durationMicros;
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
            while (true) {
                var queued = pending.poll();
                if (queued != null) {
                    return queued;
                }
                if (clip != null) {
                    var packet = nextSlice();
                    if (packet != null) {
                        return packet;
                    }
                    continue;
                }
                if (!header(position)) {
                    return null;
                }
                var at = position;
                var next = valueStart + valueLength;
                var size = source.size();
                if (size >= 0 && next > size) {
                    position = Long.MAX_VALUE;
                    return null;
                }
                if (MxfLabels.startsWith(key, MxfLabels.RANDOM_INDEX)) {
                    position = Long.MAX_VALUE;
                    return null;
                }
                if (MxfLabels.isPartition(key)) {
                    var partition = partitionAt(at);
                    if (partition != null) {
                        walk(partition);
                    }
                    position = next;
                    continue;
                }
                if (!MxfLabels.isElement(key)) {
                    position = next;
                    release(next);
                    continue;
                }
                var stream = streamOf(key);
                if (stream == null) {
                    position = next;
                    release(next);
                    continue;
                }
                if (stream.clipWrapped) {
                    startClip(stream, at, next, stream.startUnit);
                    continue;
                }
                position = next;
                var packet = element(stream, at);
                release(next);
                if (packet != null) {
                    return packet;
                }
            }
        } catch (MediaException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new MediaException.Malformed("invalid MXF essence", exception);
        }
    }

    private void release(long offset) {
        if (forwardOnly) {
            ForwardOnlySource.release(source, offset);
        }
    }

    /**
     * Turns a frame-wrapped element into a packet.
     *
     * @param stream the element's stream
     * @param at the file offset of the element key
     * @return the packet, or {@code null} when the element precedes the edit unit a seek asked for
     */
    private Packet element(MxfStream stream, long at) {
        var unit = stream.nextUnit;
        var sample = stream.nextSample;
        if (valueLength > MAX_ELEMENT) {
            throw new MediaException.LimitExceeded("MXF essence element of " + valueLength + " bytes");
        }
        LimitChecks.checkAllocation(limits, valueLength);
        if (unit < stream.startUnit) {
            stream.learn(at, false, sample);
            stream.nextUnit++;
            if (stream.kind == 's') {
                stream.nextSample += stream.blockAlign == 0 ? 0 : valueLength / stream.blockAlign;
            }
            return null;
        }
        reader.seek(valueStart);
        var data = reader.bytes(valueLength, MAX_ELEMENT);
        currentElement = at;
        var packet = switch (stream.kind) {
            case 'p' -> picture(stream, data, unit);
            case 's' -> {
                var samples = stream.blockAlign == 0 ? 0 : data.length / stream.blockAlign;
                var start = stream.nextSample;
                stream.nextSample += samples;
                yield new Packet(stream.id, data, start, start, samples, true);
            }
            default -> new Packet(stream.id, data, unit, unit, 1, true);
        };
        stream.learn(at, packet.keyframe(), sample);
        stream.nextUnit++;
        return packet;
    }

    /**
     * Turns the bytes of one stored picture into a packet.
     *
     * @param stream the essence track read
     * @param data the element value
     * @param unit the stored edit unit
     * @return the packet
     */
    private Packet picture(MxfStream stream, byte[] data, long unit) {
        var index = indexes.get(stream.bodySid);
        var pts = unit;
        var key = true;
        switch (stream.codec) {
            case H264 -> {
                if (stream.annexB) {
                    data = NalUnits.toLengthPrefixed(data);
                }
                key = isAvcKey(data);
            }
            case MPEG2_VIDEO, MPEG1_VIDEO -> {
                var picture = mpegPicture(data);
                key = picture == null || picture[1] == 1;
                if (picture != null && picture[2] != 0) {
                    stream.groupStart = unit;
                }
                if (stream.display == null && picture != null && !isIntraCoding(stream.coding)) {
                    pts = stream.groupStart + picture[0];
                }
            }
            case MPEG4_PART2 -> key = mpeg4Key(data);
            default -> {
            }
        }
        if (index != null && unit < index.entryCount() && index.offset(unit) >= 0
                && (stream.codec == Codec.H264 || stream.codec == Codec.MPEG2_VIDEO
                || stream.codec == Codec.MPEG1_VIDEO || stream.codec == Codec.MPEG4_PART2)) {
            key |= (index.flags(unit) & MxfIndex.RANDOM_ACCESS) != 0;
        }
        if (stream.display != null && unit < stream.display.length) {
            pts = stream.display[(int) unit];
        } else if (stream.orderSps != null && currentElement >= 0) {
            pts = countedTime(stream, unit, currentElement);
        } else if (stream.display == null && stream.codec == Codec.H264 && !isIntraStream(stream)
                && (index == null || index.isEmpty() || index.hasBidirectional())) {
            pts = Packet.NO_TIMESTAMP;
        }
        var dts = unit - stream.delay;
        return new Packet(stream.id, data, pts, dts, 1, key);
    }

    /**
     * Returns the presentation time of an AVC picture from the order counts of the pictures of its period: the
     * pictures from it to the next IDR picture, read ahead when the picture is not among those already ordered.
     *
     * @param stream the essence track read
     * @param unit the stored edit unit of the picture
     * @param at the file offset of its element key
     * @return the presentation time, in edit units
     */
    private long countedTime(MxfStream stream, long unit, long at) {
        if (unit < stream.windowStart || unit >= stream.windowStart + stream.windowPts.length) {
            orderWindow(stream, unit, at);
        }
        var offset = unit - stream.windowStart;
        return offset >= 0 && offset < stream.windowPts.length ? stream.windowPts[(int) offset] : Packet.NO_TIMESTAMP;
    }

    /**
     * Reads ahead the pictures of a stream from one to the next IDR picture and orders them by order count.
     *
     * @param stream the essence track read
     * @param unit the stored edit unit of the first picture
     * @param at the file offset of its element key
     */
    private void orderWindow(MxfStream stream, long unit, long at) {
        var order = AvcOrder.of(stream.orderSps);
        var counts = new long[64];
        var size = 0;
        var position = at;
        var packets = 0;
        while (size < 1024 && packets++ < MAX_PROBE_PACKETS && header(position)) {
            var next = valueStart + valueLength;
            if (MxfLabels.startsWith(key, MxfLabels.RANDOM_INDEX)) {
                break;
            }
            if (MxfLabels.isElement(key) && streamOf(key) == stream) {
                var length = (int) Math.min(valueLength, 4096);
                if (forwardOnly && !ForwardOnlySource.isCheapToReach(source, valueStart + length)) {
                    break;
                }
                reader.seek(valueStart);
                var head = new byte[(int) Math.min(length, reader.remaining(length))];
                reader.readFully(head, 0, head.length);
                var units = stream.annexB ? NalUnits.toLengthPrefixed(head) : head;
                if (size > 0 && isAvcKeyPrefix(units) && hasIdr(units)) {
                    break;
                }
                var count = order.next(units);
                if (count == Long.MIN_VALUE) {
                    break;
                }
                if (size == counts.length) {
                    counts = Arrays.copyOf(counts, 2 * size);
                }
                counts[size++] = count;
            }
            if (forwardOnly && !ForwardOnlySource.isCheapToReach(source, next + 64)) {
                break;
            }
            position = next;
        }
        var ranks = new Integer[size];
        for (var index = 0; index < size; index++) {
            ranks[index] = index;
        }
        var sorted = counts;
        Arrays.sort(ranks, (first, second) -> Long.compare(sorted[first], sorted[second]));
        var pts = new long[Math.max(1, size)];
        pts[0] = unit;
        var delay = 0L;
        for (var rank = 0; rank < size; rank++) {
            pts[ranks[rank]] = unit + rank;
            delay = Math.max(delay, ranks[rank] - rank);
        }
        if (!stream.delayKnown) {
            stream.delay = delay;
            stream.delayKnown = true;
        }
        stream.windowStart = unit;
        stream.windowPts = pts;
    }

    /**
     * {@return whether the leading length-prefixed units of an access unit hold an IDR slice}
     *
     * @param data the leading bytes
     */
    private static boolean hasIdr(byte[] data) {
        for (var at = 0; at + 5 <= data.length; ) {
            var length = (int) NalUnits.readLength(data, at, 4);
            var type = data[at + 4] & 0x1F;
            if (type == 5) {
                return true;
            }
            if (type == 1 || length < 0 || length > data.length - at - 4) {
                return false;
            }
            at += 4 + length;
        }
        return false;
    }

    /**
     * {@return whether an AVC stream is intra coded: an AVC-Intra or intra profile coding label}
     *
     * @param stream the essence track read
     */
    private static boolean isIntraStream(MxfStream stream) {
        var coding = stream.coding;
        return coding != null && coding[12] == 0x01 && (coding[13] == 0x32 || AvcIntra.classOf(coding) != 0);
    }

    /**
     * {@return whether a length-prefixed access unit holds an IDR picture or a recovery point}
     *
     * @param data the access unit
     */
    private static boolean isAvcKey(byte[] data) {
        var found = new boolean[1];
        try {
            NalUnits.forEachLengthPrefixed(data, 4, (array, offset, length) -> {
                var type = length == 0 ? -1 : array[offset] & 0x1F;
                if (type == 5) {
                    found[0] = true;
                } else if (type == 6 && length > 1 && array[offset + 1] == 6) {
                    found[0] = true;
                }
            });
        } catch (RuntimeException exception) {
            return false;
        }
        return found[0];
    }

    /**
     * Reads the picture header of an MPEG-1 or MPEG-2 picture.
     *
     * @param data the picture, from its start codes on
     * @return the temporal reference, the picture coding type and whether a group of pictures header precedes it
     * (1) or not (0), or {@code null} without a picture header
     */
    private static int[] mpegPicture(byte[] data) {
        var group = 0;
        for (var at = 0; at + 6 < data.length; at++) {
            if (data[at] != 0 || data[at + 1] != 0 || data[at + 2] != 1) {
                continue;
            }
            var code = data[at + 3] & 0xFF;
            if (code == 0xB8) {
                group = 1;
            } else if (code == 0x00) {
                var reference = (data[at + 4] & 0xFF) << 2 | (data[at + 5] & 0xFF) >>> 6;
                var type = (data[at + 5] >>> 3) & 0x07;
                return new int[]{reference, type, group};
            }
        }
        return null;
    }

    /**
     * {@return whether an MPEG-4 Part 2 frame holds an intra coded VOP}
     *
     * @param data the frame
     */
    private static boolean mpeg4Key(byte[] data) {
        for (var at = 0; at + 4 < data.length; at++) {
            if (data[at] == 0 && data[at + 1] == 0 && data[at + 2] == 1 && (data[at + 3] & 0xFF) == 0xB6) {
                return (data[at + 4] & 0xC0) == 0;
            }
        }
        return true;
    }

    /**
     * Starts reading a clip-wrapped element.
     *
     * @param stream the element's stream
     * @param at the file offset of the element key
     * @param next the file offset after the element
     * @param fromUnit the first edit unit to read
     */
    private void startClip(MxfStream stream, long at, long next, long fromUnit) {
        stream.clipStart = valueStart;
        stream.clipLength = valueLength;
        clip = stream;
        clipEnd = next;
        afterClip = next;
        var offset = clipOffset(stream, fromUnit);
        if (offset < 0 || offset > valueLength) {
            offset = valueLength;
        }
        clipPosition = valueStart + offset;
        stream.nextUnit = fromUnit;
        if (stream.kind == 's') {
            stream.nextSample = stream.blockAlign == 0 ? 0 : offset / stream.blockAlign;
        }
        position = next;
    }

    /**
     * Returns the offset in a clip of an edit unit, or of an audio sample for sound.
     *
     * @param stream the essence track read
     * @param unit the edit unit, or for sound the sample
     * @return the offset from the start of the clip value, or {@code -1} when the index does not cover it
     */
    private long clipOffset(MxfStream stream, long unit) {
        if (unit <= 0) {
            return 0;
        }
        if (stream.kind == 's') {
            return unit * stream.blockAlign;
        }
        var index = indexes.get(stream.bodySid);
        if (index == null) {
            return -1;
        }
        var offset = index.offset(unit);
        if (offset < 0) {
            return -1;
        }
        var partition = partitionOf(stream.bodySid, offset);
        return offset - (partition == null ? 0 : partition.pack.bodyOffset());
    }

    /**
     * Returns the next packet of the clip being read.
     *
     * @return the packet, or {@code null} at the end of the clip or for a slice before the requested edit unit
     */
    private Packet nextSlice() {
        var stream = clip;
        if (clipPosition >= clipEnd) {
            clip = null;
            position = afterClip;
            release(afterClip);
            return null;
        }
        long length;
        if (stream.kind == 's') {
            long group;
            if (stream.editRate.numerator() < (long) stream.sampleRate * stream.editRate.denominator()) {
                var unit = unitOfSample(stream, stream.nextSample);
                group = Math.max(1, sampleOfUnit(stream, unit + 1) - stream.nextSample);
            } else {
                group = Math.max(1, Integer.highestOneBit(Math.max(1, stream.sampleRate / 10)));
            }
            length = Math.min(group * stream.blockAlign, clipEnd - clipPosition);
            length -= length % Math.max(1, stream.blockAlign);
            if (length <= 0) {
                clipPosition = clipEnd;
                return null;
            }
        } else {
            var start = clipOffset(stream, stream.nextUnit);
            var end = clipOffset(stream, stream.nextUnit + 1);
            var index = indexes.get(stream.bodySid);
            if (index == null || start < 0) {
                throw new MediaException.Unsupported("mxf clip wrapping", "MXF clip-wrapped " + stream.codec
                        + " essence without an index table cannot be split into frames");
            }
            if (end < 0 || end > stream.clipLength) {
                end = stream.clipLength;
            }
            clipPosition = stream.clipStart + start;
            length = end - start;
            if (length <= 0) {
                clipPosition = clipEnd;
                return null;
            }
        }
        if (length > MAX_ELEMENT) {
            throw new MediaException.LimitExceeded("MXF edit unit of " + length + " bytes");
        }
        LimitChecks.checkAllocation(limits, length);
        reader.seek(clipPosition);
        var data = reader.bytes(length, MAX_ELEMENT);
        clipPosition += length;
        release(clipPosition);
        if (stream.kind == 's') {
            var samples = data.length / stream.blockAlign;
            var start = stream.nextSample;
            stream.nextSample += samples;
            return new Packet(stream.id, data, start, start, samples, true);
        }
        var unit = stream.nextUnit++;
        if (stream.kind == 'p') {
            currentElement = -1;
            return picture(stream, data, unit);
        }
        return new Packet(stream.id, data, unit, unit, 1, true);
    }

    /**
     * Returns the partition holding a stream offset of an essence container.
     *
     * @param bodySid the essence container
     * @param offset the stream offset
     * @return the partition with the largest body offset at or below {@code offset}, or {@code null}
     */
    private PartitionInfo partitionOf(long bodySid, long offset) {
        PartitionInfo best = null;
        for (var partition : partitions.values()) {
            if (partition.pack.bodySid() == bodySid && partition.pack.bodyOffset() <= offset
                    && (best == null || partition.pack.bodyOffset() >= best.pack.bodyOffset())) {
                walk(partition);
                if (partition.essence >= 0) {
                    best = partition;
                }
            }
        }
        return best;
    }

    /**
     * Returns the file offset of a stream offset of a frame-wrapped essence container.
     *
     * @param bodySid the essence container
     * @param offset the stream offset
     * @return the file offset, or {@code -1} when no partition holds it
     */
    private long fileOffset(long bodySid, long offset) {
        var partition = partitionOf(bodySid, offset);
        return partition == null ? -1 : partition.essence + offset - partition.pack.bodyOffset();
    }

    @Override
    public boolean isSeekable() {
        return !forwardOnly;
    }

    /**
     * {@inheritDoc}
     *
     * @throws MediaException.Unsupported {@inheritDoc}
     * @throws MediaException.Malformed {@inheritDoc}
     * @throws MediaException.Io {@inheritDoc}
     */
    @Override
    public void seek(long micros) {
        if (forwardOnly) {
            throw new MediaException.Unsupported("seek", "an MXF file read once, front to back, cannot seek");
        }
        var saved = new long[streams.size() * 4];
        for (var index = 0; index < streams.size(); index++) {
            var stream = streams.get(index);
            saved[4 * index] = stream.nextUnit;
            saved[4 * index + 1] = stream.startUnit;
            saved[4 * index + 2] = stream.nextSample;
            saved[4 * index + 3] = stream.groupStart;
        }
        var savedPosition = position;
        var savedClip = clip;
        var savedClipPosition = clipPosition;
        var savedClipEnd = clipEnd;
        var savedAfterClip = afterClip;
        var savedPending = new ArrayDeque<>(pending);
        try {
            seekTo(Math.max(0, micros));
        } catch (RuntimeException exception) {
            for (var index = 0; index < streams.size(); index++) {
                var stream = streams.get(index);
                stream.nextUnit = saved[4 * index];
                stream.startUnit = saved[4 * index + 1];
                stream.nextSample = saved[4 * index + 2];
                stream.groupStart = saved[4 * index + 3];
            }
            position = savedPosition;
            clip = savedClip;
            clipPosition = savedClipPosition;
            clipEnd = savedClipEnd;
            afterClip = savedAfterClip;
            pending.clear();
            pending.addAll(savedPending);
            if (exception instanceof MediaException media) {
                throw media;
            }
            throw new MediaException.Malformed("invalid MXF index", exception);
        }
    }

    /**
     * Positions every stream at the edit unit holding a time.
     *
     * @param micros the presentation time
     */
    private void seekTo(long micros) {
        pending.clear();
        clip = null;
        if (streams.isEmpty()) {
            position = essenceStart;
            return;
        }
        var lead = streams.getFirst();
        for (var stream : streams) {
            if (stream.kind == 'p') {
                lead = stream;
                break;
            }
        }
        var leadUnit = lead.kind == 'p' ? keyUnit(lead, unitAt(lead, micros)) : unitAt(lead, micros);
        var target = Long.MAX_VALUE;
        for (var stream : streams) {
            long unit;
            if (stream.kind == 's' && stream.clipWrapped) {
                unit = Rational.MICROSECONDS.rescale(micros, Rational.timeBaseOf(stream.sampleRate));
            } else if (stream == lead) {
                unit = leadUnit;
            } else {
                unit = stream.kind == 'p' ? keyUnit(stream, unitAt(stream, micros)) : unitAt(stream, micros);
            }
            stream.startUnit = Math.max(0, unit);
            var past = stream.duration >= 0 && !(stream.kind == 's' && stream.clipWrapped)
                    && stream.startUnit >= stream.duration;
            var offset = past ? -1 : stream.clipWrapped ? clipKeyOffset(stream)
                    : elementOffset(stream, stream.startUnit);
            if (offset >= 0) {
                target = Math.min(target, offset);
            }
        }
        position = target == Long.MAX_VALUE ? endOfInput() : target;
        for (var stream : streams) {
            if (stream.clipWrapped) {
                continue;
            }
            stream.nextUnit = unitAtOrAfter(stream, position);
            stream.nextSample = stream.kind == 's' ? sampleOfUnit(stream, stream.nextUnit) : 0;
        }
    }

    /**
     * {@return the size of the input, or the largest offset when it is unknown}
     */
    private long endOfInput() {
        var size = source.size();
        return size < 0 ? Long.MAX_VALUE : size;
    }

    /**
     * Returns the file offset of the content package of an edit unit of a frame-wrapped stream, from the index or the
     * positions learned while reading.
     *
     * @param stream the essence track read
     * @param unit the edit unit
     * @return the offset, or {@code -1} when the stream ends before it
     */
    private long elementOffset(MxfStream stream, long unit) {
        var index = indexes.get(stream.bodySid);
        if (index != null) {
            var offset = index.offset(unit);
            var file = offset < 0 ? -1 : fileOffset(stream.bodySid, offset);
            if (file >= 0 && header(file) && isEssence(key)) {
                return file;
            }
        }
        learnUpTo(stream, unit);
        return unit < stream.learnedCount ? stream.learned[(int) unit] : -1;
    }

    /**
     * Returns the edit unit of the first element of a frame-wrapped stream at or after a file offset.
     *
     * @param stream the essence track read
     * @param offset the file offset, the start of a content package
     * @return the edit unit
     */
    private long unitAtOrAfter(MxfStream stream, long offset) {
        if (stream.learnedCount > 0 && stream.learned[stream.learnedCount - 1] >= offset) {
            var low = 0;
            var high = stream.learnedCount - 1;
            while (low < high) {
                var mid = (low + high) >>> 1;
                if (stream.learned[mid] >= offset) {
                    high = mid;
                } else {
                    low = mid + 1;
                }
            }
            return low;
        }
        var index = indexes.get(stream.bodySid);
        if (index != null && !index.isEmpty()) {
            var low = 0L;
            var high = Math.max(stream.startUnit, index.entryCount());
            while (low < high) {
                var mid = (low + high) >>> 1;
                var unitOffset = index.offset(mid);
                var file = unitOffset < 0 ? -1 : fileOffset(stream.bodySid, unitOffset);
                if (file < 0 || file >= offset) {
                    high = mid;
                } else {
                    low = mid + 1;
                }
            }
            return low;
        }
        return stream.learnedCount;
    }

    /**
     * Returns the first sample of the element of a frame-wrapped sound stream at an edit unit: the samples of the
     * elements before it, as read, or for elements not read yet the samples of the edit units before it rounded to
     * nearest, the five-frame sequence 1602, 1601, 1602, 1601, 1602 of 48 kHz sound at 29.97 Hz.
     *
     * @param stream the essence track read
     * @param unit the edit unit
     * @return the sample
     */
    private static long sampleOfUnit(MxfStream stream, long unit) {
        if (unit < stream.learnedCount) {
            return stream.learnedSample[(int) unit];
        }
        var rate = stream.editRate;
        var exact = BigInteger.valueOf(unit).multiply(BigInteger.valueOf(stream.sampleRate))
                .multiply(BigInteger.valueOf(rate.denominator()));
        var divisor = BigInteger.valueOf(rate.numerator());
        return exact.shiftLeft(1).add(divisor).divide(divisor.shiftLeft(1)).longValueExact();
    }

    /**
     * {@return the edit unit of a sound stream whose samples hold a sample}
     *
     * @param stream the essence track read
     * @param sample the sample index, from the start of the track
     */
    private static long unitOfSample(MxfStream stream, long sample) {
        var rate = stream.editRate;
        var unit = BigInteger.valueOf(sample).multiply(BigInteger.valueOf(rate.numerator()))
                .divide(BigInteger.valueOf(stream.sampleRate).multiply(BigInteger.valueOf(rate.denominator())))
                .longValue();
        while (unit > 0 && sampleOfUnit(stream, unit) > sample) {
            unit--;
        }
        while (sampleOfUnit(stream, unit + 1) <= sample) {
            unit++;
        }
        return unit;
    }

    /**
     * Returns the edit unit of a stream that holds a time: the stored edit unit displayed at it for a picture stream.
     *
     * @param stream the essence track read
     * @param micros the time
     * @return the edit unit
     */
    private static long unitAt(MxfStream stream, long micros) {
        var rate = stream.editRate;
        var exact = BigInteger.valueOf(micros).multiply(BigInteger.valueOf(rate.numerator()))
                .divide(BigInteger.valueOf(rate.denominator()).multiply(BigInteger.valueOf(1_000_000L)));
        var unit = exact.bitLength() < 62 ? exact.longValue() : Long.MAX_VALUE / 4;
        if (stream.display != null && unit < stream.display.length) {
            for (var stored = 0; stored < stream.display.length; stored++) {
                if (stream.display[stored] == unit) {
                    return stored;
                }
            }
        }
        return unit;
    }

    /**
     * Returns the random access point a picture stream decodes a stored edit unit from.
     *
     * @param stream the essence track read
     * @param unit the stored edit unit
     * @return the stored edit unit of the key frame at or before it
     */
    private long keyUnit(MxfStream stream, long unit) {
        if (isAlwaysKey(stream.codec) || stream.kind != 'p'
                || stream.codec == Codec.H264 && isIntraStream(stream)) {
            return unit;
        }
        var index = indexes.get(stream.bodySid);
        if (index != null && index.entryCount() > 0) {
            if (unit >= index.entryCount()) {
                return unit;
            }
            var target = stream.display == null ? unit : stream.display[(int) unit];
            var best = 0L;
            var bestDisplay = -1L;
            for (var stored = 0; stored < index.entryCount(); stored++) {
                var shown = stream.display == null ? stored : stream.display[stored];
                if (shown <= target && shown > bestDisplay && isKeyEntry(index.flags(stored))) {
                    best = stored;
                    bestDisplay = shown;
                }
            }
            return best;
        }
        learnUpTo(stream, unit);
        if (unit >= stream.learnedCount) {
            return unit;
        }
        var last = unit;
        for (var at = last; at >= 0; at--) {
            if (stream.learnedKey[(int) at]) {
                return at;
            }
        }
        return 0;
    }

    /**
     * {@return whether a key is that of a packet of an essence container: an element or a system item}
     *
     * @param packetKey the key
     */
    private static boolean isEssence(byte[] packetKey) {
        return packetKey[8] == 0x0D && packetKey[9] == 0x01 && packetKey[10] == 0x03 && packetKey[11] == 0x01;
    }

    /**
     * {@return whether index entry flags mark a picture decoding can start from: a random access point, or a picture
     * predicted from no other}
     *
     * @param flags the edit unit flags of an index entry
     */
    private static boolean isKeyEntry(int flags) {
        return (flags & MxfIndex.RANDOM_ACCESS) != 0 || (flags & 0x30) == 0;
    }

    /**
     * {@return whether every frame of a codec is a key frame}
     *
     * @param codec the coding format asked about
     */
    private static boolean isAlwaysKey(Codec codec) {
        return codec == Codec.DNXHD || codec == Codec.PRORES || codec == Codec.JPEG2000;
    }

    /**
     * Reads on from the last element whose position is known until the position of an edit unit is known.
     *
     * @param stream the essence track read
     * @param unit the edit unit
     */
    private void learnUpTo(MxfStream stream, long unit) {
        if (unit < stream.learnedCount) {
            return;
        }
        var savedPosition = position;
        var states = new long[streams.size() * 3];
        for (var index = 0; index < streams.size(); index++) {
            var other = streams.get(index);
            states[3 * index] = other.nextUnit;
            states[3 * index + 1] = other.startUnit;
            states[3 * index + 2] = other.nextSample;
        }
        var at = stream.learnedCount == 0 ? essenceStart : stream.learned[stream.learnedCount - 1];
        var unitCursor = stream.learnedCount == 0 ? 0 : stream.learnedCount - 1L;
        var sampleCursor = stream.learnedCount == 0 ? 0 : stream.learnedSample[stream.learnedCount - 1];
        var count = 0L;
        while (stream.learnedCount <= unit && header(at)) {
            if (MxfLabels.startsWith(key, MxfLabels.RANDOM_INDEX)) {
                break;
            }
            var next = valueStart + valueLength;
            if (MxfLabels.isElement(key) && streamOf(key) == stream) {
                if (unitCursor == stream.learnedCount) {
                    var keyFrame = isAlwaysKey(stream.codec) || stream.kind != 'p' || probeKey(stream);
                    stream.nextUnit = unitCursor;
                    stream.learn(at, keyFrame, sampleCursor);
                }
                if (stream.kind == 's' && stream.blockAlign > 0) {
                    sampleCursor += valueLength / stream.blockAlign;
                }
                unitCursor++;
            }
            at = next;
            if (++count > Integer.MAX_VALUE) {
                break;
            }
        }
        for (var index = 0; index < streams.size(); index++) {
            var other = streams.get(index);
            other.nextUnit = states[3 * index];
            other.startUnit = states[3 * index + 1];
            other.nextSample = states[3 * index + 2];
        }
        position = savedPosition;
    }

    /**
     * {@return whether the element whose header was just read is a random access point}
     *
     * @param stream the element's stream
     */
    private boolean probeKey(MxfStream stream) {
        var length = (int) Math.min(valueLength, 4096);
        reader.seek(valueStart);
        var data = new byte[length];
        reader.readFully(data, 0, length);
        return switch (stream.codec) {
            case H264 -> {
                byte[] units = data;
                if (stream.annexB) {
                    units = NalUnits.toLengthPrefixed(data);
                }
                yield isAvcKeyPrefix(units);
            }
            case MPEG1_VIDEO, MPEG2_VIDEO -> {
                var picture = mpegPicture(data);
                yield picture == null || picture[1] == 1;
            }
            case MPEG4_PART2 -> mpeg4Key(data);
            default -> true;
        };
    }

    /**
     * {@return whether the leading length-prefixed units of an access unit hold an IDR slice or a recovery point,
     * ignoring a last unit cut by the end of the bytes}
     *
     * @param data the leading bytes
     */
    private static boolean isAvcKeyPrefix(byte[] data) {
        for (var at = 0; at + 5 <= data.length; ) {
            var length = (int) NalUnits.readLength(data, at, 4);
            var type = data[at + 4] & 0x1F;
            if (type == 5 || type == 6 && at + 5 < data.length && data[at + 5] == 6) {
                return true;
            }
            if (type == 1) {
                return false;
            }
            if (length < 0 || length > data.length - at - 4) {
                return false;
            }
            at += 4 + length;
        }
        return false;
    }

    /**
     * Returns the file offset of the key of a stream's clip element, finding it when not known yet.
     *
     * @param stream the clip-wrapped stream
     * @return the offset, or {@code -1} when the clip is not found
     */
    private long clipKeyOffset(MxfStream stream) {
        var at = essenceStart;
        var count = 0;
        while (count++ < MAX_PROBE_PACKETS && header(at)) {
            if (MxfLabels.startsWith(key, MxfLabels.RANDOM_INDEX)) {
                return -1;
            }
            if (MxfLabels.isElement(key) && streamOf(key) == stream) {
                return at;
            }
            at = valueStart + valueLength;
        }
        return -1;
    }
}
