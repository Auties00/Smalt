package com.github.auties00.smalt.container.matroska;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.container.DemuxerOptions;
import com.github.auties00.smalt.container.AttachedPicture;
import com.github.auties00.smalt.container.Chapter;
import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.container.Demuxer;
import com.github.auties00.smalt.container.internal.AacExtensions;
import com.github.auties00.smalt.container.internal.Annotations;
import com.github.auties00.smalt.container.internal.SeekFilter;
import com.github.auties00.smalt.container.internal.SeekIndex;
import com.github.auties00.smalt.container.internal.SeekTimes;
import com.github.auties00.smalt.internal.io.ForwardOnlySource;
import com.github.auties00.smalt.internal.io.SourceReader;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.internal.subtitle.WebVttSamples;
import com.github.auties00.smalt.internal.tags.EbmlIds;
import com.github.auties00.smalt.internal.tags.MatroskaTags;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.PacketFlag;
import com.github.auties00.smalt.track.PacketSideData;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.Track;
import com.github.auties00.smalt.track.VideoTrack;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Demuxes Matroska and WebM files, per RFC 8794 (EBML) and RFC 9559 (Matroska).
 *
 * <p>The constructor checks the EBML header ({@code DocType} {@code matroska} or {@code webm}),
 * enters the first {@code Segment} (known or unknown size), and reads the {@code Info},
 * {@code Tracks} and {@code Tags} that precede the first {@code Cluster}, following the
 * {@code SeekHead} to the ones stored after the clusters. {@link #read()} then streams the
 * clusters: {@code SimpleBlock}s and {@code BlockGroup}s (whose {@code ReferenceBlock} marks a
 * non-key frame and whose {@code BlockDuration} gives the duration) become one packet per frame,
 * Xiph, EBML and fixed-size lacing are split, and frames after the first of a lace get the block
 * timestamp plus the frame duration ({@code DefaultDuration}, or the {@code BlockDuration} divided
 * among the frames), or no timestamp when neither is known. Opus frames without a declared
 * duration get the duration of their TOC, and a positive {@code DiscardPadding} shortens the
 * duration of the frame it trims and is reported as its {@link PacketSideData#discardEnd()}, a
 * negative one as its {@link PacketSideData#discardStart()}. The {@code SimpleBlock} discardable
 * flag and the invisible flag become {@link PacketFlag#DISCARDABLE} and {@link PacketFlag#DISCARD};
 * {@code BlockAdditions} become side data of the block's frames: an ITU-T T.35 HDR10+ message
 * under {@code BlockAddID} 4 as {@link PacketSideData#hdr10Plus()}, anything else under the
 * {@link PacketSideData#extra()} key {@code "matroska:BlockAdditional:<id>"}. Text subtitle
 * tracks are {@link Codec#SUBRIP} ({@code S_TEXT/UTF8}), {@link Codec#ASS}
 * ({@code S_TEXT/ASS}, {@code S_TEXT/SSA}) and {@link Codec#WEBVTT} ({@code S_TEXT/WEBVTT} and
 * the WebM {@code D_WEBVTT} codecs); WebVTT blocks are returned in the {@link Codec#WEBVTT} packet
 * form, the identifier and settings moved from the block addition or the block's leading lines to
 * the side data. Clusters of unknown
 * size (live WebM)
 * end at the next top-level element. The default edition of {@code Chapters} becomes
 * {@link #chapters()}, and attachments whose media type is an image become
 * {@link #attachedPictures()} (a file named like {@code cover.jpg} as the front cover). Track
 * names and the tags that target one track become {@link #trackMetadata(int)}.
 *
 * <p>Seeking uses the {@code Cues}, read on the first {@link #seek(long)}: the cue points of the
 * first video track (of any track when there is no video) name the cluster holding the last key
 * frame at or before the target. Without usable cues, the clusters are scanned once, reading only
 * element headers, block headers and cluster timestamps, into an index of the clusters and the
 * first key frame of that track in each. From the chosen cluster every track then restarts at its
 * last key frame at or before the target, as {@link SeekFilter} describes. Timestamps keep the
 * precision of {@code TimestampScale} (a millisecond by default).
 *
 * <p>Video and data timestamps are in the segment time base ({@code TimestampScale} nanoseconds, a
 * millisecond by default). Audio tracks use the sample period instead (1/48000 for Opus,
 * 1/{@code SamplingFrequency} otherwise), so that a {@code DiscardPadding} or a
 * {@code DefaultDuration} converts to an exact number of samples; their block timestamps are
 * rescaled to it, keeping the rounding of the stored timestamps. Timestamps are the block
 * timestamps as stored: Matroska stores presentation timestamps only,
 * so the decoding timestamp of codecs that reorder frames (H.264, HEVC, MPEG-4 Part 2) is
 * {@link Packet#NO_TIMESTAMP}, and equals the presentation timestamp for every other track. The
 * {@code CodecDelay} of an audio track is reported as {@link AudioTrack#skipSamples()} rather
 * than subtracted from the timestamps, except for Opus, whose pre-skip already signals it in band.
 * An AAC track whose {@code SamplingFrequency} is the rate of the AAC core while spectral band
 * replication doubles it (HE-AAC without an {@code OutputSamplingFrequency}), or whose parametric
 * stereo turns a mono core into stereo, is described by the format its first frame decodes to
 * through the registered AAC decoder, as players do: the constructor reads ahead to that frame,
 * and {@link #read()} returns the frames read on the way first. The track keeps its time base,
 * and its {@code CodecDelay} skip is rescaled to the decoded rate.
 * Frames of tracks with {@code ContentEncodings} are decoded: header stripping prepends the
 * stripped bytes and zlib inflates, bounded by the allocation limit; encrypted tracks raise
 * {@link MediaException.Unsupported}.
 *
 * <p>Damage inside a cluster (an invalid element header, or an element that overruns its cluster)
 * makes the demuxer resynchronize at the next {@code Cluster} identifier, and junk before the
 * first cluster is skipped up to the next top-level element; a block with an invalid lace is
 * skipped. Every element read into memory is bounded by the {@link MediaLimits}, and
 * master elements are parsed to a bounded depth.
 *
 * <p>A source that reads its input once, front to back (a stream), is told before every element
 * which bytes are no longer needed. The header elements stored after the clusters (tags, chapters,
 * attachments) are read only when they lie within the source's memory budget; {@code Tracks} is
 * always read.
 */
public final class MatroskaDemuxer implements Demuxer {
    /**
     * The {@code SimpleBlock} flag marking a frame no other frame depends on.
     */
    private static final int DISCARDABLE = 0x01;

    /**
     * The block flag marking a frame that is decoded but not shown.
     */
    private static final int INVISIBLE = 0x08;

    /**
     * The size of the buffer of the underlying reader.
     */
    private static final int READ_BUFFER = 256 * 1024;

    /**
     * The time base of Opus TOC durations, which count 48 kHz samples whatever the coded rate.
     */
    private static final Rational OPUS_RATE = Rational.timeBaseOf(48_000);

    /**
     * The largest header element ({@code Info}, {@code Tracks}, {@code Tags}, {@code SeekHead})
     * read into memory.
     */
    private static final int MAX_HEADER_ELEMENT = 64 << 20;

    /**
     * The largest number of frames read ahead to find the first frame of an AAC track whose
     * output format is refined.
     */
    private static final int MAX_PEEKED_FRAMES = 512;

    /**
     * The largest number of tracks accepted.
     */
    private static final int MAX_TRACKS = 1024;

    /**
     * The largest number of top-level elements walked before the first cluster.
     */
    private static final int MAX_HEADER_ELEMENTS = 1 << 16;

    /**
     * The deepest nesting of {@code SimpleTag} elements followed.
     */
    private static final int MAX_TAG_DEPTH = 8;

    /**
     * The number of seconds between 1970-01-01 and 2001-01-01, the Matroska date epoch.
     */
    private static final long EPOCH_2001_SECONDS = 978307200L;

    /**
     * The formatter of creation times: ISO 8601 in UTC with microseconds.
     */
    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'").withZone(ZoneOffset.UTC);

    /**
     * The source being demuxed.
     */
    private final MediaSource source;

    /**
     * The limits applied to every size read from the source.
     */
    private final MediaLimits limits;

    /**
     * The buffered reader positioned at the next element.
     */
    private final SourceReader reader;

    /**
     * The source size sampled at construction, or {@code -1} when unknown.
     */
    private final long sourceSize;

    /**
     * The {@code DocType} of the EBML header.
     */
    private final MatroskaDocType docType;

    /**
     * The public track descriptions, in file order.
     */
    private final List<Track> tracks;

    /**
     * The parsed track entries keyed by track number.
     */
    private final Map<Long, MatroskaTrackEntry> byNumber;

    /**
     * The container-level metadata tags.
     */
    private final Map<String, String> metadata;

    /**
     * The presentation duration in microseconds, or {@code -1}.
     */
    private final long durationMicros;

    /**
     * The segment time base, {@code TimestampScale} nanoseconds, in which block timestamps and
     * {@code BlockDuration} are expressed.
     */
    private final Rational segmentBase;

    /**
     * The absolute offset of the first byte after the segment, or {@link Long#MAX_VALUE} when the
     * segment size is unknown.
     */
    private final long segmentEnd;

    /**
     * Whether the reader is inside a cluster.
     */
    private boolean inCluster;

    /**
     * The absolute end offset of the current cluster, or {@code -1} when its size is unknown.
     */
    private long clusterEnd;

    /**
     * The timestamp of the current cluster.
     */
    private long clusterTimestamp;

    /**
     * The frames read but not yet returned: those of the last block, or, while the constructor
     * looks for the first frame of a track, those of every block read so far.
     */
    private final ArrayList<Packet> queue;

    /**
     * The index of the next frame in {@link #queue}.
     */
    private int queueIndex;

    /**
     * Whether the end of the segment was reached.
     */
    private boolean finished;

    /**
     * The absolute offset of the segment payload, to which {@code SeekHead} and {@code Cues}
     * positions are relative.
     */
    private final long segmentStart;

    /**
     * The absolute offset of the first {@code Cluster}, or {@code -1} when there is none.
     */
    private final long firstCluster;

    /**
     * The absolute offset of the {@code Cues}, or {@code -1} when the headers do not locate them.
     */
    private final long cuesPosition;

    /**
     * The track number cue points and the cluster scan follow: the first video track, or
     * {@code -1} when there is none.
     */
    private final long referenceTrack;

    /**
     * The chapters of the default edition.
     */
    private final List<Chapter> chapters;

    /**
     * The attached pictures.
     */
    private final List<AttachedPicture> pictures;

    /**
     * The tags of each track, by track identifier.
     */
    private final Map<Integer, Map<String, String>> trackTags;

    /**
     * Whether {@link #cueTimes} was loaded.
     */
    private boolean cuesLoaded;

    /**
     * The cue times of the usable cue points, in segment ticks and increasing order, or
     * {@code null} when the file has no usable cues.
     */
    private long[] cueTimes;

    /**
     * The absolute cluster offsets matching {@link #cueTimes}.
     */
    private long[] cuePositions;

    /**
     * The clusters found by scanning, by cluster timestamp: offset, whether the cluster holds a
     * key frame of {@link #referenceTrack} (state {@code 1}), and that key frame's timestamp
     * relative to the cluster (extra).
     */
    private SeekIndex clusterIndex;

    /**
     * The absolute offset where the cluster scan resumes, or {@code -1} once it reached the end.
     */
    private long scanPosition;

    /**
     * The reader the cluster scan and the cue loading use, allocated on first use.
     */
    private SourceReader scanner;

    /**
     * The filter trimming the packets read after a seek, or {@code null}.
     */
    private SeekFilter filter;

    /**
     * Constructs a demuxer over {@code source} with {@link DemuxerOptions#defaults()}.
     *
     * @param source the input, not closed by this demuxer
     * @throws MediaException.Malformed if the file is not a Matroska file or its headers are corrupt
     * @throws MediaException.Unsupported if the {@code DocType} is neither {@code matroska} nor {@code webm}, or a
     * track is encrypted or compressed with an unsupported algorithm
     * @throws MediaException.LimitExceeded if a declared size exceeds the limits
     * @throws MediaException.Io if reading the source fails
     */
    public MatroskaDemuxer(MediaSource source) {
        this(source, DemuxerOptions.defaults());
    }

    /**
     * Constructs a demuxer over {@code source} that enforces the limits of {@code options}.
     *
     * @param source the input, not closed by this demuxer
     * @param options the demuxing options
     * @throws MediaException.Malformed if the file is not a Matroska file or its headers are corrupt
     * @throws MediaException.Unsupported if the {@code DocType} is neither {@code matroska} nor {@code webm}, or a
     * track is encrypted or compressed with an unsupported algorithm
     * @throws MediaException.LimitExceeded if a declared size exceeds the limits
     * @throws MediaException.Io if reading the source fails
     */
    public MatroskaDemuxer(MediaSource source, DemuxerOptions options) {
        this(source, options.limits());
    }

    private MatroskaDemuxer(MediaSource source, MediaLimits limits) {
        this.source = Objects.requireNonNull(source, "source");
        this.limits = Objects.requireNonNull(limits, "limits");
        this.reader = new SourceReader(source, READ_BUFFER);
        this.queue = new ArrayList<>();
        try {
            this.sourceSize = source.size();
            this.docType = readEbmlHeader();
            var segmentSize = enterSegment();
            var segmentStart = reader.position();
            this.segmentEnd = segmentSize == EbmlCursor.UNKNOWN_SIZE ? Long.MAX_VALUE : segmentStart + segmentSize;
            this.segmentStart = segmentStart;
            var header = new SegmentHeader();
            var firstCluster = readSegmentHeader(header);
            for (var id : new int[]{EbmlIds.INFO, EbmlIds.TRACKS, EbmlIds.TAGS, EbmlIds.CHAPTERS, EbmlIds.ATTACHMENTS}) {
                var seek = header.seeks.get((long) id);
                var seen = switch (id) {
                    case EbmlIds.INFO -> header.infoParsed;
                    case EbmlIds.TRACKS -> header.trackEntries != null;
                    case EbmlIds.TAGS -> header.tagsParsed;
                    case EbmlIds.CHAPTERS -> header.chaptersParsed;
                    default -> header.attachmentsParsed;
                };
                if (seek != null && !seen && (id == EbmlIds.TRACKS
                        || ForwardOnlySource.isCheapToReach(source, segmentStart + seek + READ_BUFFER))) {
                    readSeekTarget(header, segmentStart + seek, id);
                }
            }
            var cues = header.seeks.get((long) EbmlIds.CUES);
            this.cuesPosition = header.cuesPosition >= 0 ? header.cuesPosition : cues != null ? segmentStart + cues : -1;
            this.firstCluster = firstCluster;
            this.scanPosition = firstCluster;
            if (header.trackEntries == null) {
                throw new MediaException.Malformed("Matroska segment has no Tracks");
            }
            var timestampScale = header.timestampScale;
            this.segmentBase = new Rational(timestampScale, 1_000_000_000L).reduce();
            this.byNumber = new HashMap<>();
            this.trackTags = new HashMap<>();
            var publicTracks = new ArrayList<Track>();
            var entries = new ArrayList<>(header.trackEntries);
            entries.sort(Comparator.comparing(entry -> !entry.enabled));
            for (var entry : entries) {
                var tagged = header.trackDurations.get(entry.uid);
                if (tagged != null) {
                    entry.taggedDuration = tagged;
                }
                publicTracks.add(entry.toTrack(segmentBase, header.duration, limits));
                byNumber.put(entry.number, entry);
                var tags = new LinkedHashMap<String, String>();
                if (entry.name != null && !entry.name.isEmpty()) {
                    tags.put("title", entry.name);
                }
                var targeted = header.trackTags.get(entry.uid);
                if (targeted != null) {
                    targeted.forEach(tags::putIfAbsent);
                }
                if (!tags.isEmpty()) {
                    trackTags.put((int) entry.number, Collections.unmodifiableMap(tags));
                }
            }
            this.metadata = Collections.unmodifiableMap(header.tags);
            this.durationMicros = header.duration > 0 ? Math.round(header.duration * timestampScale / 1000.0) : -1;
            this.chapters = header.annotations.chapters(durationMicros);
            this.pictures = header.annotations.pictures();
            var reference = -1L;
            for (var index = 0; index < publicTracks.size(); index++) {
                if (publicTracks.get(index) instanceof VideoTrack) {
                    reference = entries.get(index).number;
                    break;
                }
            }
            this.referenceTrack = reference;
            reader.seek(firstCluster >= 0 ? firstCluster : Math.min(segmentEnd, Math.max(sourceSize, 0)));
            this.finished = firstCluster < 0;
            for (var index = 0; index < publicTracks.size(); index++) {
                if (publicTracks.get(index) instanceof AudioTrack audio && AacExtensions.mayRefine(audio)) {
                    publicTracks.set(index, AacExtensions.refine(audio, peekFirstFrame(audio.id())));
                }
            }
            this.tracks = Collections.unmodifiableList(publicTracks);
        } catch (MediaException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new MediaException.Malformed("corrupt Matroska file: " + exception, exception);
        }
    }

    /**
     * Reads ahead to the first frame of a track, keeping every frame read in {@link #queue} for
     * {@link #read()}.
     *
     * @param trackId the track
     * @return the frame's payload, or {@code null} when none is found within {@value #MAX_PEEKED_FRAMES} frames
     */
    private byte[] peekFirstFrame(int trackId) {
        var index = 0;
        while (true) {
            for (; index < queue.size(); index++) {
                if (queue.get(index).trackId() == trackId) {
                    return queue.get(index).data();
                }
            }
            if (finished || queue.size() >= MAX_PEEKED_FRAMES) {
                return null;
            }
            if (!step()) {
                finished = true;
            }
        }
    }

    /**
     * Collects the segment-level elements that precede the first cluster.
     */
    private static final class SegmentHeader {
        /**
         * The positions of the elements the {@code SeekHead} indexes, relative to the segment
         * data, keyed by element identifier.
         */
        private final Map<Long, Long> seeks = new HashMap<>();

        /**
         * Whether an {@code Info} element was read.
         */
        private boolean infoParsed;

        /**
         * The {@code TimestampScale}.
         */
        private long timestampScale = 1_000_000;

        /**
         * The {@code Duration} in timestamp units, or {@code 0}.
         */
        private double duration;

        /**
         * The parsed track entries, or {@code null} when {@code Tracks} was not read yet.
         */
        private List<MatroskaTrackEntry> trackEntries;

        /**
         * Whether a {@code Tags} element was read.
         */
        private boolean tagsParsed;

        /**
         * The metadata tags.
         */
        private final Map<String, String> tags = new LinkedHashMap<>();

        /**
         * The {@code DURATION} tags of tracks in nanoseconds, keyed by track UID.
         */
        private final Map<Long, Long> trackDurations = new HashMap<>();

        /**
         * The tags that target one track, by track UID.
         */
        private final Map<Long, Map<String, String>> trackTags = new HashMap<>();

        /**
         * Whether the {@code Chapters} were read.
         */
        private boolean chaptersParsed;

        /**
         * Whether the {@code Attachments} were read.
         */
        private boolean attachmentsParsed;

        /**
         * The absolute offset of a {@code Cues} met before the first cluster, or {@code -1}.
         */
        private long cuesPosition = -1;

        /**
         * The chapters and pictures collected.
         */
        private final Annotations annotations = new Annotations();
    }

    /**
     * Reads and validates the EBML header.
     *
     * @return the {@code DocType}
     */
    private MatroskaDocType readEbmlHeader() {
        if (reader.atEnd()) {
            throw new MediaException.Malformed("empty input is not a Matroska file");
        }
        var id = readId();
        if (id != EbmlIds.EBML) {
            throw new MediaException.Malformed("not a Matroska file: no EBML header");
        }
        var size = readSize();
        if (size < 0 || size > 4096) {
            throw new MediaException.Malformed("EBML header of size " + size);
        }
        var header = new EbmlCursor(reader.bytes(size, 4096));
        var type = "matroska";
        while (header.hasMore()) {
            var element = header.next();
            var body = element.payload();
            switch ((int) element.id()) {
                case EbmlIds.DOC_TYPE -> type = body.string();
                case EbmlIds.EBML_READ_VERSION -> {
                    if (body.unsigned() > 1) {
                        throw new MediaException.Unsupported("ebml", "EBMLReadVersion above 1");
                    }
                }
                case EbmlIds.EBML_MAX_ID_LENGTH -> {
                    if (body.unsigned() > 4) {
                        throw new MediaException.Unsupported("ebml", "EBMLMaxIDLength above 4");
                    }
                }
                case EbmlIds.EBML_MAX_SIZE_LENGTH -> {
                    if (body.unsigned() > 8) {
                        throw new MediaException.Unsupported("ebml", "EBMLMaxSizeLength above 8");
                    }
                }
                default -> {
                }
            }
        }
        if (!type.equals("matroska") && !type.equals("webm")) {
            throw new MediaException.Unsupported("ebml doctype " + type, "EBML DocType '" + type + "' is not Matroska or WebM");
        }
        return type.equals("webm") ? MatroskaDocType.WEBM : MatroskaDocType.MATROSKA;
    }

    /**
     * Skips the elements between the EBML header and the {@code Segment} and reads the segment
     * header.
     *
     * @return the segment data size, or {@link EbmlCursor#UNKNOWN_SIZE}
     */
    private long enterSegment() {
        for (var skipped = 0; skipped < MAX_HEADER_ELEMENTS; skipped++) {
            if (reader.atEnd()) {
                throw new MediaException.Malformed("Matroska file has no Segment");
            }
            var id = readId();
            var size = readSize();
            if (id == EbmlIds.SEGMENT) {
                return size;
            }
            if (id < 0 || size < 0) {
                throw new MediaException.Malformed("invalid element before the Segment");
            }
            reader.skip(size);
        }
        throw new MediaException.Malformed("Matroska file has no Segment");
    }

    /**
     * Reads the segment-level elements up to the first cluster.
     *
     * @param header the collected header
     * @return the absolute offset of the first cluster, or {@code -1} when the segment has none
     */
    private long readSegmentHeader(SegmentHeader header) {
        for (var count = 0; count < MAX_HEADER_ELEMENTS; count++) {
            var position = reader.position();
            if (position >= segmentEnd || reader.atEnd()) {
                return -1;
            }
            var id = readId();
            var size = readSize();
            if (id < 0 || size == -2) {
                var next = scan(position + 1, true);
                if (next < 0) {
                    return -1;
                }
                reader.seek(next);
                continue;
            }
            if (id == EbmlIds.CLUSTER) {
                return position;
            }
            if (id == EbmlIds.EBML || id == EbmlIds.SEGMENT) {
                return -1;
            }
            if (size == EbmlCursor.UNKNOWN_SIZE) {
                throw new MediaException.Malformed("element 0x" + Long.toHexString(id) + " has an unknown size");
            }
            switch ((int) id) {
                case EbmlIds.SEEK_HEAD, EbmlIds.INFO, EbmlIds.TRACKS, EbmlIds.TAGS, EbmlIds.CHAPTERS ->
                        readHeaderElement(header, (int) id, size);
                case EbmlIds.ATTACHMENTS -> {
                    if (size <= Math.min(limits.maxBytes(), MAX_HEADER_ELEMENT)) {
                        readHeaderElement(header, (int) id, size);
                    } else {
                        reader.skip(size);
                    }
                }
                case EbmlIds.CUES -> {
                    header.cuesPosition = position;
                    reader.skip(size);
                }
                default -> reader.skip(size);
            }
        }
        throw new MediaException.LimitExceeded("more than " + MAX_HEADER_ELEMENTS + " elements before the first Cluster");
    }

    /**
     * Reads the element a {@code SeekHead} entry points to, when it is the expected element.
     *
     * @param header the collected header
     * @param position the absolute offset of the element
     * @param expected the expected element identifier
     */
    private void readSeekTarget(SegmentHeader header, long position, int expected) {
        if (position < 0 || (sourceSize >= 0 && position >= sourceSize)) {
            return;
        }
        try {
            reader.seek(position);
            var id = readId();
            var size = readSize();
            if (id == expected && size >= 0 && size != EbmlCursor.UNKNOWN_SIZE
                    && (expected != EbmlIds.ATTACHMENTS || size <= Math.min(limits.maxBytes(), MAX_HEADER_ELEMENT))) {
                readHeaderElement(header, expected, size);
            }
        } catch (MediaException.Malformed | MediaException.LimitExceeded exception) {
            if (expected == EbmlIds.TRACKS) {
                throw exception;
            }
        }
    }

    /**
     * Reads one header element into memory and parses it.
     *
     * @param header the collected header
     * @param id the element identifier
     * @param size the element data size
     */
    private void readHeaderElement(SegmentHeader header, int id, long size) {
        var bound = Math.min(limits.maxBytes(), MAX_HEADER_ELEMENT);
        var body = new EbmlCursor(reader.bytes(size, bound));
        switch (id) {
            case EbmlIds.SEEK_HEAD -> parseSeekHead(header, body);
            case EbmlIds.INFO -> parseInfo(header, body);
            case EbmlIds.TRACKS -> {
                if (header.trackEntries == null) {
                    header.trackEntries = parseTracks(body);
                }
            }
            case EbmlIds.TAGS -> {
                header.tagsParsed = true;
                try {
                    parseTags(header, body);
                } catch (MediaException.Malformed ignored) {
                    // tags are descriptive; a damaged Tags element leaves the file playable
                }
            }
            case EbmlIds.CHAPTERS -> {
                header.chaptersParsed = true;
                try {
                    parseChapters(header, body);
                } catch (MediaException.Malformed ignored) {
                    // chapters are descriptive; damaged Chapters leave the file playable
                }
            }
            case EbmlIds.ATTACHMENTS -> {
                header.attachmentsParsed = true;
                try {
                    parseAttachments(header, body);
                } catch (MediaException.Malformed ignored) {
                    // attachments are descriptive; damaged Attachments leave the file playable
                }
            }
            default -> {
            }
        }
    }

    /**
     * Parses a {@code SeekHead}.
     *
     * @param header the collected header
     * @param body the element data
     */
    private static void parseSeekHead(SegmentHeader header, EbmlCursor body) {
        while (body.hasMore()) {
            var seek = body.next();
            if (seek.id() != EbmlIds.SEEK) {
                continue;
            }
            var entry = seek.payload();
            var id = -1L;
            var position = -1L;
            while (entry.hasMore()) {
                var element = entry.next();
                if (element.id() == EbmlIds.SEEK_ID) {
                    id = element.payload().unsigned();
                } else if (element.id() == EbmlIds.SEEK_POSITION) {
                    position = element.payload().unsigned();
                }
            }
            if (id >= 0 && position >= 0) {
                header.seeks.putIfAbsent(id, position);
            }
        }
    }

    /**
     * Parses an {@code Info} element.
     *
     * @param header the collected header
     * @param body the element data
     */
    private static void parseInfo(SegmentHeader header, EbmlCursor body) {
        header.infoParsed = true;
        while (body.hasMore()) {
            var element = body.next();
            var value = element.payload();
            switch ((int) element.id()) {
                case EbmlIds.TIMESTAMP_SCALE -> {
                    var scale = value.unsigned();
                    if (scale <= 0 || scale > 1_000_000_000_000L) {
                        throw new MediaException.Malformed("invalid TimestampScale " + scale);
                    }
                    header.timestampScale = scale;
                }
                case EbmlIds.DURATION -> {
                    var duration = value.floating();
                    header.duration = Double.isFinite(duration) && duration > 0 ? duration : 0;
                }
                case EbmlIds.TITLE -> header.tags.put("title", value.string());
                case EbmlIds.DATE_UTC -> {
                    var nanos = value.signed();
                    var seconds = Math.floorDiv(nanos, 1_000_000_000L) + EPOCH_2001_SECONDS;
                    header.tags.put("creation_time", TIME_FORMAT.format(Instant.ofEpochSecond(seconds, Math.floorMod(nanos, 1_000_000_000L))));
                }
                default -> {
                }
            }
        }
    }

    /**
     * Parses a {@code Tracks} element.
     *
     * @param body the element data
     * @return the entries, in file order, without duplicate track numbers
     */
    private List<MatroskaTrackEntry> parseTracks(EbmlCursor body) {
        var entries = new ArrayList<MatroskaTrackEntry>();
        var numbers = new java.util.HashSet<Long>();
        while (body.hasMore()) {
            var element = body.next();
            if (element.id() != EbmlIds.TRACK_ENTRY) {
                continue;
            }
            if (entries.size() >= MAX_TRACKS) {
                throw new MediaException.LimitExceeded("more than " + MAX_TRACKS + " tracks");
            }
            var entry = MatroskaTrackEntry.parse(element.payload(), limits);
            if (entry.number <= 0 || entry.number > Integer.MAX_VALUE) {
                throw new MediaException.Malformed("invalid track number " + entry.number);
            }
            if (numbers.add(entry.number)) {
                entries.add(entry);
            }
        }
        return entries;
    }

    /**
     * Parses a {@code Tags} element: global tags become metadata, and the {@code DURATION} tag of
     * a track becomes its duration.
     *
     * @param header the collected header
     * @param body the element data
     */
    private static void parseTags(SegmentHeader header, EbmlCursor body) {
        while (body.hasMore()) {
            var tag = body.next();
            if (tag.id() != EbmlIds.TAG) {
                continue;
            }
            var content = tag.payload();
            var trackUid = -1L;
            var global = true;
            var simpleTags = new ArrayList<EbmlCursor>();
            while (content.hasMore()) {
                var element = content.next();
                if (element.id() == EbmlIds.TARGETS) {
                    var payload = element.payload();
                    var targets = MatroskaTags.targets(payload.array(), payload.asElement(EbmlIds.TARGETS));
                    trackUid = targets.trackUid() != 0 ? targets.trackUid() : -1;
                    global = targets.global();
                } else if (element.id() == EbmlIds.SIMPLE_TAG) {
                    simpleTags.add(element.payload());
                }
            }
            for (var simpleTag : simpleTags) {
                parseSimpleTag(header, simpleTag, "", global, trackUid, 0);
            }
        }
    }

    /**
     * Parses one {@code SimpleTag} and its nested tags.
     *
     * @param header the collected header
     * @param body the element data
     * @param prefix the names of the enclosing tags joined with {@code '/'}, or empty
     * @param global whether the tag targets the whole segment
     * @param trackUid the targeted track UID, or {@code -1}
     * @param depth the current nesting
     */
    private static void parseSimpleTag(SegmentHeader header, EbmlCursor body, String prefix, boolean global, long trackUid, int depth) {
        if (depth > MAX_TAG_DEPTH) {
            return;
        }
        String name = null;
        String value = null;
        var nested = new ArrayList<EbmlCursor>();
        while (body.hasMore()) {
            var element = body.next();
            if (element.id() == EbmlIds.TAG_NAME) {
                name = element.payload().string();
            } else if (element.id() == EbmlIds.TAG_STRING) {
                value = element.payload().string();
            } else if (element.id() == EbmlIds.SIMPLE_TAG) {
                nested.add(element.payload());
            }
        }
        if (name == null) {
            return;
        }
        var key = prefix.isEmpty() ? name : prefix + "/" + name;
        if (value != null) {
            if (global && header.tags.size() < 1024) {
                header.tags.putIfAbsent(key, value);
            } else if (!global && trackUid >= 0 && name.equalsIgnoreCase("DURATION")) {
                var nanos = parseDuration(value);
                if (nanos >= 0) {
                    header.trackDurations.put(trackUid, nanos);
                }
            }
            if (!global && trackUid >= 0) {
                var tags = header.trackTags.computeIfAbsent(trackUid, _ -> new LinkedHashMap<>());
                if (tags.size() < 1024) {
                    tags.putIfAbsent(key, value);
                }
            }
        }
        for (var child : nested) {
            parseSimpleTag(header, child, key, global, trackUid, depth + 1);
        }
    }

    /**
     * Parses a {@code DURATION} tag value of the form {@code HH:MM:SS.nnnnnnnnn}.
     *
     * @param value the tag value
     * @return the duration in nanoseconds, or {@code -1} when the value is malformed
     */
    private static long parseDuration(String value) {
        var parts = value.trim().split(":");
        if (parts.length != 3) {
            return -1;
        }
        try {
            var hours = Long.parseLong(parts[0]);
            var minutes = Long.parseLong(parts[1]);
            var seconds = new java.math.BigDecimal(parts[2]);
            if (hours < 0 || hours > 1_000_000 || minutes < 0 || minutes > 59 || seconds.signum() < 0) {
                return -1;
            }
            return (hours * 3600 + minutes * 60) * 1_000_000_000L
                    + seconds.movePointRight(9).setScale(0, java.math.RoundingMode.HALF_UP).longValueExact();
        } catch (NumberFormatException | ArithmeticException exception) {
            return -1;
        }
    }

    /**
     * Reads an element identifier at the reader position.
     *
     * @return the identifier, or {@code -1} when the bytes are not a valid identifier
     */
    private long readId() {
        return readId(reader);
    }

    /**
     * Reads an element identifier from a reader.
     *
     * @param reader the reader, positioned at the element ID
     * @return the identifier with its marker bits, or {@code -1} when it is longer than four bytes
     */
    private static long readId(SourceReader reader) {
        var first = reader.u8();
        var length = Integer.numberOfLeadingZeros(first) - 23;
        if (first == 0 || length > 4) {
            return -1;
        }
        long value = first;
        for (var index = 1; index < length; index++) {
            value = (value << 8) | reader.u8();
        }
        return value;
    }

    /**
     * Reads a data size at the reader position.
     *
     * @return the size, {@link EbmlCursor#UNKNOWN_SIZE} for an unknown size, or {@code -2} when the bytes are not a
     * valid size
     */
    private long readSize() {
        return readSize(reader);
    }

    /**
     * Reads an element data size from a reader.
     *
     * @param reader the reader, positioned at the element size
     * @return the size, {@link EbmlCursor#UNKNOWN_SIZE} for the reserved all-ones value, or {@code -2} for an invalid
     * zero lead byte
     */
    private static long readSize(SourceReader reader) {
        var first = reader.u8();
        if (first == 0) {
            return -2;
        }
        var length = Integer.numberOfLeadingZeros(first) - 23;
        long value = first & (0xFF >> length);
        var allOnes = value == (0xFF >> length);
        for (var index = 1; index < length; index++) {
            var next = reader.u8();
            allOnes &= next == 0xFF;
            value = (value << 8) | next;
        }
        return allOnes ? EbmlCursor.UNKNOWN_SIZE : value;
    }

    /**
     * {@return the document type of the file, read from the {@code DocType} element of its EBML header}
     */
    public MatroskaDocType docType() {
        return docType;
    }

    @Override
    public ContainerFormat format() {
        return ContainerFormat.MATROSKA;
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
        return durationMicros;
    }

    @Override
    public Map<String, String> trackMetadata(int trackId) {
        return trackTags.getOrDefault(trackId, Map.of());
    }

    @Override
    public List<Chapter> chapters() {
        return chapters;
    }

    @Override
    public List<AttachedPicture> attachedPictures() {
        return pictures;
    }

    @Override
    public Packet read() {
        try {
            if (filter != null) {
                while (true) {
                    var packet = filter.poll();
                    if (packet != null) {
                        return packet;
                    }
                    if (filter.isTransparent()) {
                        filter = null;
                        return nextPacket();
                    }
                    var next = nextPacket();
                    if (next == null) {
                        filter.finish();
                    } else {
                        filter.offer(next);
                    }
                }
            }
            return nextPacket();
        } catch (MediaException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new MediaException.Malformed("corrupt Matroska file: " + exception, exception);
        }
    }

    /**
     * Returns the next frame in file order, without the seek filter.
     *
     * @return the frame, or {@code null} at the end of the segment
     */
    private Packet nextPacket() {
        while (true) {
            if (queueIndex < queue.size()) {
                var packet = queue.get(queueIndex++);
                var entry = byNumber.get((long) packet.trackId());
                if (entry != null && entry.codec == Codec.WEBVTT) {
                    packet = WebVttSamples.fromMatroska(packet, entry.codecId.startsWith("D_WEBVTT"));
                }
                return packet;
            }
            queue.clear();
            queueIndex = 0;
            if (finished) {
                return null;
            }
            ForwardOnlySource.release(source, reader.position());
            if (!step()) {
                finished = true;
            }
        }
    }

    /**
     * {@inheritDoc}
     *
     * @return {@code true}: the demuxer seeks through the {@code Cues} or, without them, by scanning the clusters
     */
    @Override
    public boolean isSeekable() {
        return true;
    }

    /**
     * {@inheritDoc}
     *
     * <p>The demuxer moves to the cluster that holds the last key frame of the first video track
     * (of any track when there is no video) at or before {@code micros}, found through the
     * {@code Cues} or a scan of the clusters, and every track restarts at its own last key frame
     * at or before {@code micros}.
     */
    @Override
    public void seek(long micros) {
        try {
            var target = SeekTimes.floor(micros, segmentBase);
            var position = firstCluster < 0 ? -1 : locateCluster(target);
            var replacement = new SeekFilter(tracks, micros);
            queue.clear();
            queueIndex = 0;
            inCluster = false;
            clusterEnd = -1;
            clusterTimestamp = 0;
            if (position < 0) {
                finished = true;
                filter = null;
                return;
            }
            reader.seek(position);
            finished = false;
            filter = replacement;
        } catch (MediaException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new MediaException.Malformed("corrupt Matroska file: " + exception, exception);
        }
    }

    /**
     * Finds the cluster a seek starts reading at.
     *
     * @param target the target in segment ticks
     * @return the absolute offset of the cluster
     */
    private long locateCluster(long target) {
        loadCues();
        if (cueTimes != null) {
            var low = 0;
            var high = cueTimes.length - 1;
            var found = -1;
            while (low <= high) {
                var middle = (low + high) >>> 1;
                if (cueTimes[middle] <= target) {
                    found = middle;
                    low = middle + 1;
                } else {
                    high = middle - 1;
                }
            }
            if (found < 0) {
                return firstCluster;
            }
            if (isClusterAt(cuePositions[found])) {
                return cuePositions[found];
            }
            cueTimes = null;
        }
        return scanClusters(target);
    }

    /**
     * Returns whether a {@code Cluster} element starts at an offset.
     *
     * @param position the absolute offset
     * @return {@code true} when the four bytes there are the cluster identifier
     */
    private boolean isClusterAt(long position) {
        if (position < 0 || (sourceSize >= 0 && position + 4 > sourceSize)) {
            return false;
        }
        var bytes = new byte[4];
        if (source.read(position, bytes, 0, 4) < 4) {
            return false;
        }
        return ((bytes[0] & 0xFF) << 24 | (bytes[1] & 0xFF) << 16 | (bytes[2] & 0xFF) << 8 | (bytes[3] & 0xFF))
                == EbmlIds.CLUSTER;
    }

    /**
     * Returns the reader used outside the packet path, creating it on first use.
     *
     * @return the reader
     */
    private SourceReader scanner() {
        if (scanner == null) {
            scanner = new SourceReader(source, 64 * 1024);
        }
        return scanner;
    }

    /**
     * Reads the {@code Cues} once, keeping the cue points of {@link #referenceTrack} (of every
     * track when it has none) in time order.
     */
    private void loadCues() {
        if (cuesLoaded) {
            return;
        }
        cuesLoaded = true;
        if (cuesPosition < 0 || (sourceSize >= 0 && cuesPosition >= sourceSize)) {
            return;
        }
        try {
            var cursor = scanner();
            cursor.seek(cuesPosition);
            var id = readId(cursor);
            var size = readSize(cursor);
            if (id != EbmlIds.CUES || size < 0 || size == EbmlCursor.UNKNOWN_SIZE) {
                return;
            }
            var body = new EbmlCursor(cursor.bytes(size, Math.min(limits.maxBytes(), MAX_HEADER_ELEMENT)));
            var times = new long[64];
            var positions = new long[64];
            var count = 0;
            var referenced = false;
            while (body.hasMore()) {
                var point = body.next();
                if (point.id() != EbmlIds.CUE_POINT) {
                    continue;
                }
                var content = point.payload();
                var time = -1L;
                var position = -1L;
                var matches = false;
                while (content.hasMore()) {
                    var element = content.next();
                    if (element.id() == EbmlIds.CUE_TIME) {
                        time = element.payload().unsigned();
                    } else if (element.id() == EbmlIds.CUE_TRACK_POSITIONS) {
                        var positionsOf = element.payload();
                        var track = -1L;
                        var cluster = -1L;
                        while (positionsOf.hasMore()) {
                            var child = positionsOf.next();
                            if (child.id() == EbmlIds.CUE_TRACK) {
                                track = child.payload().unsigned();
                            } else if (child.id() == EbmlIds.CUE_CLUSTER_POSITION) {
                                cluster = child.payload().unsigned();
                            }
                        }
                        if (cluster >= 0 && (position < 0 || track == referenceTrack)) {
                            position = cluster;
                            matches |= track == referenceTrack;
                        }
                    }
                }
                if (time < 0 || position < 0) {
                    continue;
                }
                if (matches && !referenced) {
                    referenced = true;
                    count = 0;
                }
                if (referenced && !matches) {
                    continue;
                }
                if (count == times.length) {
                    times = java.util.Arrays.copyOf(times, count * 2);
                    positions = java.util.Arrays.copyOf(positions, count * 2);
                }
                times[count] = time;
                positions[count] = segmentStart + position;
                count++;
            }
            if (count == 0) {
                return;
            }
            var order = new Integer[count];
            for (var index = 0; index < count; index++) {
                order[index] = index;
            }
            var sortedTimes = times;
            java.util.Arrays.sort(order, java.util.Comparator.comparingLong(index -> sortedTimes[index]));
            cueTimes = new long[count];
            cuePositions = new long[count];
            for (var index = 0; index < count; index++) {
                cueTimes[index] = times[order[index]];
                cuePositions[index] = positions[order[index]];
            }
        } catch (MediaException.Malformed | MediaException.LimitExceeded ignored) {
            cueTimes = null;
            // a damaged Cues element falls back to scanning the clusters
        }
    }

    /**
     * Finds the cluster to read from by scanning the clusters, extending the cluster index up to
     * the first cluster past the target.
     *
     * @param target the target in segment ticks
     * @return the absolute offset of the cluster holding the last key frame of {@link #referenceTrack} at or before the
     * target, of the last cluster starting at or before it when there is no reference track, or of the first cluster
     */
    private long scanClusters(long target) {
        if (clusterIndex == null) {
            clusterIndex = new SeekIndex();
        }
        while (scanPosition >= 0 && (clusterIndex.size() == 0
                || clusterIndex.timestamp(clusterIndex.size() - 1) <= target)) {
            scanPosition = scanCluster(scanPosition);
        }
        var index = clusterIndex.floor(target);
        if (referenceTrack >= 0) {
            for (var candidate = index; candidate >= 0; candidate--) {
                if (clusterIndex.state(candidate) == 1
                        && clusterIndex.timestamp(candidate) + clusterIndex.extra(candidate) <= target) {
                    return clusterIndex.offset(candidate);
                }
            }
            return firstCluster;
        }
        return index < 0 ? firstCluster : clusterIndex.offset(index);
    }

    /**
     * Indexes the cluster at or after an offset.
     *
     * @param position the absolute offset where the scan resumes
     * @return the offset after the indexed cluster, or {@code -1} at the end of the segment
     */
    private long scanCluster(long position) {
        var cursor = scanner();
        var limit = sourceSize >= 0 ? Math.min(sourceSize, segmentEnd) : segmentEnd;
        for (var skipped = 0; skipped < MAX_HEADER_ELEMENTS; skipped++) {
            if (position + 2 > limit) {
                return -1;
            }
            cursor.seek(position);
            var id = readId(cursor);
            var size = readSize(cursor);
            if (id < 0 || size == -2) {
                position = scan(position + 1, false);
                if (position < 0) {
                    return -1;
                }
                continue;
            }
            if (id == EbmlIds.EBML || id == EbmlIds.SEGMENT) {
                return -1;
            }
            var dataStart = cursor.position();
            if (id != EbmlIds.CLUSTER) {
                if (size == EbmlCursor.UNKNOWN_SIZE || dataStart + size > limit) {
                    position = scan(position + 1, false);
                    if (position < 0) {
                        return -1;
                    }
                } else {
                    position = dataStart + size;
                }
                continue;
            }
            var end = size == EbmlCursor.UNKNOWN_SIZE ? -1 : Math.min(limit, dataStart + size);
            var timestamp = -1L;
            var keyOffset = Integer.MIN_VALUE;
            var child = dataStart;
            while (end < 0 ? child + 2 <= limit : child < end) {
                cursor.seek(child);
                var childId = readId(cursor);
                var childSize = readSize(cursor);
                if (childId < 0 || childSize < 0 || childSize == EbmlCursor.UNKNOWN_SIZE) {
                    break;
                }
                if (end < 0 && EbmlIds.isTopLevel(childId)) {
                    break;
                }
                var body = cursor.position();
                if (body + childSize > limit) {
                    break;
                }
                if (childId == EbmlIds.TIMESTAMP && childSize <= 8) {
                    timestamp = new EbmlCursor(cursor.bytes(childSize, 8)).unsigned();
                } else if (keyOffset == Integer.MIN_VALUE && referenceTrack >= 0
                        && (childId == EbmlIds.SIMPLE_BLOCK || childId == EbmlIds.BLOCK_GROUP)) {
                    keyOffset = keyFrameOffset(cursor, childId, body, childSize);
                }
                child = body + childSize;
            }
            if (timestamp >= 0) {
                var hasKey = keyOffset != Integer.MIN_VALUE;
                if (clusterIndex.size() == 0 || clusterIndex.timestamp(clusterIndex.size() - 1) <= timestamp) {
                    clusterIndex.add(timestamp, position, hasKey ? 1 : 0, hasKey ? keyOffset : 0);
                }
            }
            return end >= 0 ? end : child;
        }
        return -1;
    }

    /**
     * Returns the relative timestamp of a block when it is a key frame of
     * {@link #referenceTrack}.
     *
     * @param cursor the reader
     * @param id the element identifier, {@code SimpleBlock} or {@code BlockGroup}
     * @param body the absolute offset of the element payload
     * @param size the payload size
     * @return the timestamp relative to the cluster, or {@link Integer#MIN_VALUE} when the block is not a key frame of
     * the reference track
     */
    private int keyFrameOffset(SourceReader cursor, long id, long body, long size) {
        if (id == EbmlIds.SIMPLE_BLOCK) {
            return blockKeyOffset(cursor, body, size, true);
        }
        var end = body + size;
        var child = body;
        var blockStart = -1L;
        var blockSize = 0L;
        var referenced = false;
        while (child < end) {
            cursor.seek(child);
            var childId = readId(cursor);
            var childSize = readSize(cursor);
            if (childId < 0 || childSize < 0 || childSize == EbmlCursor.UNKNOWN_SIZE) {
                return Integer.MIN_VALUE;
            }
            var payload = cursor.position();
            if (childId == EbmlIds.BLOCK) {
                blockStart = payload;
                blockSize = childSize;
            } else if (childId == EbmlIds.REFERENCE_BLOCK) {
                referenced = true;
            }
            child = payload + childSize;
        }
        if (blockStart < 0 || referenced) {
            return Integer.MIN_VALUE;
        }
        return blockKeyOffset(cursor, blockStart, blockSize, false);
    }

    /**
     * Reads the header of a block and returns its relative timestamp when it is a key frame of
     * {@link #referenceTrack}.
     *
     * @param cursor the reader
     * @param start the absolute offset of the block
     * @param size the block size
     * @param simple whether the block is a {@code SimpleBlock}, whose flags mark key frames
     * @return the relative timestamp, or {@link Integer#MIN_VALUE}
     */
    private int blockKeyOffset(SourceReader cursor, long start, long size, boolean simple) {
        if (size < 4) {
            return Integer.MIN_VALUE;
        }
        cursor.seek(start);
        var first = cursor.u8();
        var length = Integer.numberOfLeadingZeros(first) - 23;
        if (first == 0 || length > 8 || size < length + 3) {
            return Integer.MIN_VALUE;
        }
        long track = first & (0xFF >> length);
        for (var index = 1; index < length; index++) {
            track = (track << 8) | cursor.u8();
        }
        var relative = (short) cursor.u16be();
        var flags = cursor.u8();
        if (track != referenceTrack || (simple && (flags & 0x80) == 0)) {
            return Integer.MIN_VALUE;
        }
        return relative;
    }

    /**
     * Parses the {@code Chapters} element, keeping the visible chapters of the default edition
     * (of the first edition when none is the default).
     *
     * @param header receives the chapters
     * @param body the element data
     */
    private static void parseChapters(SegmentHeader header, EbmlCursor body) {
        for (var chapter : MatroskaTags.chapterSpans(body.array(), body.asElement(EbmlIds.CHAPTERS))) {
            header.annotations.addChapter(chapter.startMicros(), chapter.endMicros(), chapter.title());
        }
    }

    /**
     * Parses the {@code Attachments} element, keeping the attached images as pictures.
     *
     * @param header receives the pictures
     * @param body the element data
     */
    private static void parseAttachments(SegmentHeader header, EbmlCursor body) {
        var pictures = new ArrayList<AttachedPicture>();
        MatroskaTags.decodeAttachments(body.array(), body.asElement(EbmlIds.ATTACHMENTS), pictures, null);
        pictures.forEach(header.annotations::addPicture);
    }

    /**
     * Reads the next element of the segment or the current cluster, queueing the frames of a
     * block.
     *
     * @return {@code false} at the end of the segment
     */
    private boolean step() {
        var position = reader.position();
        if (inCluster && clusterEnd >= 0 && position >= clusterEnd) {
            inCluster = false;
        }
        if (position >= segmentEnd || reader.atEnd()) {
            return false;
        }
        if (sourceSize >= 0 && sourceSize - position < 2) {
            return false;
        }
        var id = readId();
        if (id < 0) {
            return resync(position);
        }
        var size = readSize();
        if (size == -2) {
            return resync(position);
        }
        var dataStart = reader.position();
        if (!inCluster) {
            if (id == EbmlIds.CLUSTER) {
                inCluster = true;
                clusterEnd = size == EbmlCursor.UNKNOWN_SIZE ? -1 : dataStart + size;
                clusterTimestamp = 0;
                return true;
            }
            if (id == EbmlIds.EBML || id == EbmlIds.SEGMENT) {
                return false;
            }
            if (size == EbmlCursor.UNKNOWN_SIZE || dataStart + size > segmentEnd) {
                return resync(position);
            }
            reader.seek(dataStart + size);
            return true;
        }
        if (clusterEnd < 0 && EbmlIds.isTopLevel(id)) {
            inCluster = false;
            reader.seek(position);
            return true;
        }
        if (size == EbmlCursor.UNKNOWN_SIZE || (clusterEnd >= 0 && dataStart + size > clusterEnd) || dataStart + size > segmentEnd) {
            return resync(position);
        }
        if (sourceSize >= 0 && dataStart + size > sourceSize) {
            throw new MediaException.Malformed("element at offset " + position + " runs past the end of the input");
        }
        switch ((int) id) {
            case EbmlIds.TIMESTAMP -> {
                if (size > 8) {
                    return resync(position);
                }
                clusterTimestamp = new EbmlCursor(reader.bytes(size, 8)).unsigned();
            }
            case EbmlIds.SIMPLE_BLOCK -> readSimpleBlock(dataStart, size);
            case EbmlIds.BLOCK_GROUP -> {
                LimitChecks.checkAllocation(limits, size);
                readBlockGroup(new EbmlCursor(reader.bytes(size, Integer.MAX_VALUE)));
            }
            default -> reader.seek(dataStart + size);
        }
        return true;
    }

    /**
     * Parses a {@code BlockGroup}.
     *
     * @param group the element data
     */
    private void readBlockGroup(EbmlCursor group) {
        byte[] block = null;
        var duration = -1L;
        var referenced = false;
        var padding = 0L;
        var additions = PacketSideData.NONE;
        try {
            while (group.hasMore()) {
                var element = group.next();
                var body = element.payload();
                switch ((int) element.id()) {
                    case EbmlIds.BLOCK -> block = body.bytes();
                    case EbmlIds.BLOCK_DURATION -> duration = body.unsigned();
                    case EbmlIds.REFERENCE_BLOCK -> referenced = true;
                    case EbmlIds.DISCARD_PADDING -> padding = body.signed();
                    case EbmlIds.BLOCK_ADDITIONS -> additions = parseAdditions(body);
                    default -> {
                    }
                }
            }
        } catch (MediaException.Malformed exception) {
            return;
        }
        if (block != null) {
            var queued = queue.size();
            readBlock(block, false, referenced, duration);
            if (queue.size() > queued) {
                if (!additions.isEmpty()) {
                    for (var index = queued; index < queue.size(); index++) {
                        queue.set(index, queue.get(index).withSideData(additions));
                    }
                }
                if (padding > 0) {
                    trimLast(padding);
                } else if (padding < 0) {
                    discardFirst(queued, -padding);
                }
            }
        }
    }

    /**
     * Parses a {@code BlockAdditions} element into packet side data.
     *
     * <p>A {@code BlockAdditional} under {@code BlockAddID} 4 holding an ITU-T T.35 message of the
     * HDR10+ application (country code 0xB5, provider 0x003C, provider-oriented code 0x0001,
     * application identifier 4) becomes {@link PacketSideData#hdr10Plus()}; every other one is
     * stored under {@code "matroska:BlockAdditional:<id>"}.
     *
     * @param additions the element data
     * @return the side data
     */
    private static PacketSideData parseAdditions(EbmlCursor additions) {
        var builder = PacketSideData.builder();
        while (additions.hasMore()) {
            var more = additions.next();
            if ((int) more.id() != EbmlIds.BLOCK_MORE) {
                continue;
            }
            var body = more.payload();
            var id = 1L;
            byte[] payload = null;
            while (body.hasMore()) {
                var element = body.next();
                switch ((int) element.id()) {
                    case EbmlIds.BLOCK_ADD_ID -> id = element.payload().unsigned();
                    case EbmlIds.BLOCK_ADDITIONAL -> payload = element.payload().bytes();
                    default -> {
                    }
                }
            }
            if (payload == null) {
                continue;
            }
            if (id == 4 && isHdr10Plus(payload)) {
                builder.hdr10Plus(payload);
            } else {
                builder.extra("matroska:BlockAdditional:" + id, payload);
            }
        }
        return builder.build();
    }

    /**
     * Returns whether an ITU-T T.35 message carries HDR10+ dynamic metadata (SMPTE ST 2094-40).
     *
     * @param message the message, starting with its country code
     * @return whether the header identifies the HDR10+ application
     */
    private static boolean isHdr10Plus(byte[] message) {
        return message.length >= 6 && (message[0] & 0xFF) == 0xB5 && message[1] == 0 && message[2] == 0x3C
                && message[3] == 0 && message[4] == 1 && message[5] == 4;
    }

    /**
     * Records a negative {@code DiscardPadding} as the discard at the start of the first frame of
     * a block, leaving its duration unchanged.
     *
     * @param index the queue index of the block's first frame
     * @param paddingNanos the padding in nanoseconds; positive
     */
    private void discardFirst(int index, long paddingNanos) {
        var first = queue.get(index);
        var entry = byNumber.get((long) first.trackId());
        if (entry == null) {
            return;
        }
        var padding = MatroskaTrackEntry.NANOSECONDS.rescale(paddingNanos, entry.timeBase);
        if (padding > 0) {
            var sideData = first.sideData().toBuilder().discard(padding, first.sideData().discardEnd()).build();
            queue.set(index, first.withSideData(sideData));
        }
    }

    /**
     * Shortens the duration of the last queued frame by a {@code DiscardPadding}.
     *
     * @param paddingNanos the padding in nanoseconds; positive
     */
    private void trimLast(long paddingNanos) {
        var last = queue.getLast();
        var entry = byNumber.get((long) last.trackId());
        if (last.duration() <= 0 || entry == null) {
            return;
        }
        var padding = MatroskaTrackEntry.NANOSECONDS.rescale(paddingNanos, entry.timeBase);
        var trimmed = Math.max(0, last.duration() - padding);
        var sideData = last.sideData().toBuilder().discard(last.sideData().discardStart(), last.duration() - trimmed)
                .build();
        queue.set(queue.size() - 1, last.withTiming(last.pts(), last.dts(), trimmed).withSideData(sideData));
    }

    /**
     * Splits a {@code SimpleBlock} or {@code Block} into frames and queues one packet per frame.
     *
     * <p>A block for an undeclared track, or whose header or lacing is inconsistent with its
     * size, is skipped.
     *
     * @param block the block bytes
     * @param simple whether the block is a {@code SimpleBlock}, whose flags carry the keyframe bit
     * @param referenced whether the enclosing {@code BlockGroup} has a {@code ReferenceBlock}
     * @param blockDuration the {@code BlockDuration}, or {@code -1}
     */
    private void readBlock(byte[] block, boolean simple, boolean referenced, long blockDuration) {
        var layout = BlockLayout.parse(block);
        if (layout == null) {
            return;
        }
        var entry = byNumber.get(layout.trackNumber());
        if (entry == null) {
            return;
        }
        var keyframe = simple ? (layout.flags() & 0x80) != 0 : !referenced;
        var timestamp = clusterTimestamp + layout.relativeTimestamp();
        var frames = layout.frameCount();
        var encoded = entry.encodesFrames();
        for (var index = 0; index < frames; index++) {
            var offset = layout.frameOffset(index);
            var length = layout.frameLength(index);
            var data = encoded ? entry.decodeFrame(block, offset, length, limits) : java.util.Arrays.copyOfRange(block, offset, offset + length);
            queueFrame(entry, data, timestamp, index, frames, blockDuration, keyframe,
                    simple ? layout.flags() : layout.flags() & INVISIBLE);
        }
    }

    /**
     * Reads a {@code SimpleBlock} whose data starts at the reader position; an unlaced frame of a
     * track without content encodings is read straight into its packet, any other block goes
     * through {@link #readBlock(byte[], boolean, boolean, long)}.
     *
     * @param dataStart the absolute offset of the block data
     * @param size the block size
     */
    private void readSimpleBlock(long dataStart, long size) {
        LimitChecks.checkAllocation(limits, size);
        var first = size >= 4 ? reader.u8() : 0;
        var length = Integer.numberOfLeadingZeros(first) - 23;
        if (first == 0 || size < length + 3) {
            reader.seek(dataStart + size);
            return;
        }
        long track = first & (0xFF >> length);
        for (var index = 1; index < length; index++) {
            track = (track << 8) | reader.u8();
        }
        var relative = (short) reader.u16be();
        var flags = reader.u8();
        var entry = byNumber.get(track);
        if (entry == null) {
            reader.seek(dataStart + size);
            return;
        }
        if (((flags >> 1) & 3) != 0 || entry.encodesFrames()) {
            reader.seek(dataStart);
            readBlock(reader.bytes(size, Integer.MAX_VALUE), true, false, -1);
            return;
        }
        var data = new byte[(int) (size - length - 3)];
        reader.readFully(data, 0, data.length);
        queueFrame(entry, data, clusterTimestamp + relative, 0, 1, -1, (flags & 0x80) != 0, flags);
    }

    /**
     * Queues one frame of a block as a packet.
     *
     * <p>Timestamps and durations are converted from the segment time base to the track's
     * {@link MatroskaTrackEntry#timeBase}. The first frame takes the block timestamp; a later
     * frame of a lace takes the block timestamp plus its offset in the lace, computed from the
     * frame duration: the track's {@code DefaultDuration}, or the {@code BlockDuration} divided
     * among the frames. Without either, later frames get no timestamp, and the duration of an Opus
     * frame comes from its TOC.
     *
     * @param entry the frame's track
     * @param data the frame bytes
     * @param timestamp the block timestamp, in segment time base units
     * @param index the index of the frame in its block
     * @param frames the number of frames in the block
     * @param blockDuration the {@code BlockDuration} in segment time base units, or {@code -1}
     * @param keyframe whether the block is a key frame
     * @param blockFlags the flags byte of a {@code SimpleBlock}, or the invisible bit of a {@code Block}
     */
    private void queueFrame(MatroskaTrackEntry entry, byte[] data, long timestamp, int index, int frames, long blockDuration,
                            boolean keyframe, int blockFlags) {
        var base = entry.timeBase;
        var frameDuration = -1.0;
        if (entry.defaultDuration > 0) {
            frameDuration = entry.defaultDuration * (double) base.denominator() / (1e9 * base.numerator());
        } else if (blockDuration >= 0) {
            frameDuration = blockDuration * ((double) segmentBase.numerator() * base.denominator())
                    / ((double) segmentBase.denominator() * base.numerator()) / frames;
        }
        var start = segmentBase.rescale(timestamp, base);
        long pts;
        if (index == 0) {
            pts = start;
        } else {
            pts = frameDuration >= 0 ? start + Math.round(index * frameDuration) : Packet.NO_TIMESTAMP;
        }
        long duration;
        if (frames == 1 && blockDuration >= 0) {
            duration = segmentBase.rescale(blockDuration, base);
        } else if (frameDuration >= 0) {
            duration = Math.round((index + 1) * frameDuration) - Math.round(index * frameDuration);
        } else if (entry.codec == Codec.OPUS) {
            duration = OPUS_RATE.rescale(opusSamples(data), base);
        } else {
            duration = Packet.UNKNOWN_DURATION;
        }
        var packet = new Packet((int) entry.number, data, pts, entry.reorders() ? Packet.NO_TIMESTAMP : pts,
                duration, keyframe);
        if ((blockFlags & (DISCARDABLE | INVISIBLE)) != 0) {
            var flags = EnumSet.noneOf(PacketFlag.class);
            flags.addAll(packet.flags());
            if ((blockFlags & DISCARDABLE) != 0) {
                flags.add(PacketFlag.DISCARDABLE);
            }
            if ((blockFlags & INVISIBLE) != 0) {
                flags.add(PacketFlag.DISCARD);
            }
            packet = packet.withFlags(flags);
        }
        queue.add(packet);
    }

    /**
     * Returns the number of 48 kHz samples an Opus packet decodes to, from its TOC byte and frame
     * count, per RFC 6716 clause 3.1.
     *
     * @param packet the packet bytes
     * @return the duration in samples, or {@code 0} for an empty or invalid packet
     */
    static int opusSamples(byte[] packet) {
        if (packet.length == 0) {
            return 0;
        }
        var toc = packet[0] & 0xFF;
        var config = toc >> 3;
        int frameSamples;
        if (config < 12) {
            frameSamples = (config & 3) == 3 ? 2880 : 480 << (config & 3);
        } else if (config < 16) {
            frameSamples = (config & 1) == 0 ? 480 : 960;
        } else {
            frameSamples = 120 << (config & 3);
        }
        var frames = switch (toc & 3) {
            case 0 -> 1;
            case 1, 2 -> 2;
            default -> packet.length < 2 ? 0 : packet[1] & 0x3F;
        };
        var samples = frameSamples * frames;
        return samples > 5760 ? 0 : samples;
    }

    /**
     * Searches for the next {@code Cluster} identifier after damage and positions the reader on
     * it.
     *
     * @param damaged the offset where the damaged element started
     * @return {@code true} when a cluster was found, {@code false} at the end of the input
     */
    private boolean resync(long damaged) {
        inCluster = false;
        var found = scan(damaged + 1, false);
        if (found < 0) {
            return false;
        }
        reader.seek(found);
        return true;
    }

    /**
     * Searches the input for the four-byte identifier of a {@code Cluster}, or of any top-level
     * element.
     *
     * @param from the absolute offset where the search starts
     * @param anyTopLevel whether any top-level identifier matches, rather than only {@code Cluster}
     * @return the absolute offset of the identifier, or {@code -1} when the segment has none
     */
    private long scan(long from, boolean anyTopLevel) {
        var buffer = new byte[64 * 1024];
        var position = from;
        var limit = sourceSize >= 0 ? Math.min(sourceSize, segmentEnd) : segmentEnd;
        while (position + 4 <= limit) {
            var length = (int) Math.min(buffer.length, limit - position);
            var read = source.read(position, buffer, 0, length);
            if (read < 4) {
                return -1;
            }
            for (var index = 0; index + 4 <= read; index++) {
                var lead = buffer[index] & 0xFF;
                if (lead < 0x10 || lead > 0x1F) {
                    continue;
                }
                var id = ((long) lead << 24) | ((buffer[index + 1] & 0xFF) << 16) | ((buffer[index + 2] & 0xFF) << 8)
                        | (buffer[index + 3] & 0xFF);
                if (id == EbmlIds.CLUSTER || (anyTopLevel && EbmlIds.isTopLevel(id) && id != EbmlIds.EBML && id != EbmlIds.SEGMENT)) {
                    return position + index;
                }
            }
            position += read - 3;
        }
        return -1;
    }

    /**
     * Describes the header and lacing of a {@code Block} or {@code SimpleBlock}, per RFC 9559
     * clause 10.
     *
     * @param trackNumber the track number
     * @param relativeTimestamp the signed timestamp relative to the cluster
     * @param flags the flags byte
     * @param offsets the offset of each frame in the block
     * @param lengths the length of each frame
     */
    record BlockLayout(long trackNumber, int relativeTimestamp, int flags, int[] offsets, int[] lengths) {
        /**
         * Parses a block header and its lacing.
         *
         * @param block the block bytes
         * @return the layout, or {@code null} when the header or lacing is inconsistent
         */
        static BlockLayout parse(byte[] block) {
            if (block.length < 4) {
                return null;
            }
            var first = block[0] & 0xFF;
            if (first == 0) {
                return null;
            }
            var length = Integer.numberOfLeadingZeros(first) - 23;
            if (block.length < length + 3) {
                return null;
            }
            long track = first & (0xFF >> length);
            for (var index = 1; index < length; index++) {
                track = (track << 8) | (block[index] & 0xFF);
            }
            var position = length;
            var relative = (short) (((block[position] & 0xFF) << 8) | (block[position + 1] & 0xFF));
            var flags = block[position + 2] & 0xFF;
            position += 3;
            var lacing = (flags >> 1) & 3;
            if (lacing == 0) {
                return new BlockLayout(track, relative, flags, new int[]{position}, new int[]{block.length - position});
            }
            if (position >= block.length) {
                return null;
            }
            var count = (block[position++] & 0xFF) + 1;
            var lengths = new int[count];
            var total = 0L;
            switch (lacing) {
                case 1 -> {
                    for (var frame = 0; frame < count - 1; frame++) {
                        var size = 0L;
                        while (true) {
                            if (position >= block.length) {
                                return null;
                            }
                            var value = block[position++] & 0xFF;
                            size += value;
                            if (value != 255) {
                                break;
                            }
                        }
                        if (size > block.length) {
                            return null;
                        }
                        lengths[frame] = (int) size;
                        total += size;
                    }
                }
                case 3 -> {
                    var previous = 0L;
                    for (var frame = 0; frame < count - 1; frame++) {
                        if (position >= block.length) {
                            return null;
                        }
                        var lead = block[position] & 0xFF;
                        if (lead == 0) {
                            return null;
                        }
                        var width = Integer.numberOfLeadingZeros(lead) - 23;
                        if (position + width > block.length) {
                            return null;
                        }
                        long value = lead & (0xFF >> width);
                        for (var index = 1; index < width; index++) {
                            value = (value << 8) | (block[position + index] & 0xFF);
                        }
                        position += width;
                        long size;
                        if (frame == 0) {
                            size = value;
                        } else {
                            size = previous + value - ((1L << (7 * width - 1)) - 1);
                        }
                        if (size < 0 || size > block.length) {
                            return null;
                        }
                        lengths[frame] = (int) size;
                        previous = size;
                        total += size;
                    }
                }
                default -> {
                    var remaining = block.length - position;
                    if (remaining % count != 0) {
                        return null;
                    }
                    java.util.Arrays.fill(lengths, remaining / count);
                    total = (long) remaining / count * (count - 1);
                }
            }
            var last = block.length - position - total;
            if (last < 0) {
                return null;
            }
            lengths[count - 1] = (int) last;
            var offsets = new int[count];
            var offset = position;
            for (var frame = 0; frame < count; frame++) {
                offsets[frame] = offset;
                offset += lengths[frame];
            }
            return new BlockLayout(track, relative, flags, offsets, lengths);
        }

        /**
         * {@return the number of frames, at least one}
         */
        int frameCount() {
            return offsets.length;
        }

        /**
         * Returns the offset of a frame.
         *
         * @param index the frame index
         * @return the offset in the block
         */
        int frameOffset(int index) {
            return offsets[index];
        }

        /**
         * Returns the length of a frame.
         *
         * @param index the frame index
         * @return the length in bytes
         */
        int frameLength(int index) {
            return lengths[index];
        }
    }
}
