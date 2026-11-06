package com.github.auties00.smalt.container.matroska;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.container.Chapter;
import com.github.auties00.smalt.container.ChapterEdition;
import com.github.auties00.smalt.container.DemuxerOptions;
import com.github.auties00.smalt.container.internal.SeekFilter;
import com.github.auties00.smalt.internal.codec.AudioConfigs;
import com.github.auties00.smalt.internal.tags.MatroskaTags;
import com.github.auties00.smalt.io.DataReference;
import com.github.auties00.smalt.io.DataReferenceResolver;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.PacketFlag;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.Track;
import com.github.auties00.smalt.track.VideoTrack;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Plays the virtual timeline of a Matroska file (RFC 9559 sections 17 and 20.1.3): the parts of segments that the
 * chapters of an ordered edition name, in their order, or the segments of a hard-linked chain one after another.
 *
 * <p>Each part reads its segment from the key frame at or before its start. Video frames that end at or before the
 * start are flagged {@link PacketFlag#DISCARD}, and so are the audio frames before it that the codec needs to rebuild
 * its state (as {@link #prerollCount} counts them); the other frames that end at or before the start are dropped. Video
 * and data frames straddling the start are cut to it, and audio frames straddling it keep their timestamp and carry the
 * samples before it as their discard start. Frames presented at or after the end are dropped, except frames of a track
 * whose frames are stored out of presentation order that a later frame before the end may reference, which are kept and
 * flagged {@link PacketFlag#DISCARD}. The bounds of a part are played times, which for audio lie the codec delay of the
 * segment track (the Opus pre-skip, {@code CodecDelay}) after the block timestamps (RFC 9559), and the priming a
 * decoder drops once, at the start of the timeline, is cut that much earlier, so that every part presents the samples
 * played between its bounds at its place. Timestamps move to the part's place on the timeline; decoding timestamps
 * never decrease, and a discarded video or data frame gets a negative presentation timestamp, which no presented frame
 * has.
 *
 * <p>Instances are not thread-safe.
 */
final class MatroskaTimeline implements AutoCloseable {
    /**
     * The time base of chapter times.
     */
    private static final Rational NANOS = MatroskaTrackEntry.NANOSECONDS;

    /**
     * The deepest chain of linked editions followed.
     */
    private static final int MAX_LINK_DEPTH = 8;

    /**
     * The largest number of segments opened, the file's own included.
     */
    private static final int MAX_SEGMENTS = 64;

    /**
     * The largest number of parts of a timeline.
     */
    private static final int MAX_PARTS = 4096;

    /**
     * The largest number of chapters visited while the parts are collected, linked editions expanded each time they
     * are played, so that editions linking each other's editions many times over cannot make the expansion
     * exponential.
     */
    private static final int MAX_VISITS = 1 << 16;

    /**
     * The largest number of top-level chapters a hard-linked chain reports.
     */
    private static final int MAX_CHAPTERS = 4096;

    /**
     * The distance past the end of a part at which reading its segment stops in any case.
     */
    private static final long MARGIN_NANOS = 2_000_000_000L;

    /**
     * How many times over the segments a timeline may read between seeks.
     */
    private static final long READ_FACTOR = 4;

    /**
     * The least number of payload bytes a timeline may read between seeks.
     */
    private static final long MIN_READ_BUDGET = 4 << 20;

    /**
     * The largest number of frames read at the start of a hard-linked segment for its first key frame.
     */
    private static final int MAX_KEY_SEARCH = 4096;

    /**
     * The number of consecutive frames past the end of a part after which a track whose frames are stored out of
     * presentation order has none left before the end.
     */
    private static final int REORDER_WINDOW = 16;

    /**
     * How long before the start of a part an audio track whose codec needs earlier packets to rebuild its state is
     * read, in microseconds: longer than the pre-roll of any such codec.
     */
    private static final long PREROLL_LEAD_MICROS = 500_000;

    /**
     * The pre-roll an Opus decoder needs to converge after a jump, 80 ms as RFC 7845 section 4.6 recommends, in
     * nanoseconds.
     */
    private static final long OPUS_PREROLL_NANOS = 80_000_000L;

    /**
     * The time base of the Opus pre-skip.
     */
    private static final Rational OPUS_RATE = Rational.timeBaseOf(48_000);

    /**
     * The most audio packets before the start of a part held for its pre-roll.
     */
    private static final int MAX_PREROLL = 64;

    /**
     * The largest {@code main_data_begin} of an MP3 frame, the number of bytes its main data starts before the frame.
     */
    private static final int MP3_MAX_MAIN_DATA_BEGIN = 511;

    /**
     * The most bytes of an MP3 frame that are not main data: the header, the CRC and the side information of an
     * MPEG-1 stereo frame.
     */
    private static final int MP3_FRAME_OVERHEAD = 4 + 2 + 32;

    /**
     * The tracks of the timeline, those of the file's own segment.
     */
    private final List<Track> tracks;

    /**
     * The parts, in playback order.
     */
    private final List<Part> parts;

    /**
     * The segments the parts read, the file's own first.
     */
    private final List<Segment> segments;

    /**
     * The duration of the timeline in nanoseconds, or {@code -1} when unknown.
     */
    private final long durationNanos;

    /**
     * The edition the timeline plays, with its chapters on the timeline.
     */
    private final List<ChapterEdition> editions;

    /**
     * The state of each track, by track identifier.
     */
    private final Map<Integer, TrackState> states;

    /**
     * The packets ready to return.
     */
    private final ArrayDeque<Packet> out;

    /**
     * The index of the part being read, or of the last part read.
     */
    private int partIndex;

    /**
     * The part being read, or {@code null} between parts.
     */
    private Part part;

    /**
     * The number of audio and video tracks of the part being read that may still have frames before its end.
     */
    private int openTracks;

    /**
     * Whether every part was read.
     */
    private boolean finished;

    /**
     * The filter trimming the packets read after a seek that starts in an earlier hard-linked segment, or
     * {@code null}.
     */
    private SeekFilter filter;

    /**
     * The number of frame payload bytes read from the segments, dropped frames included, since the start or the
     * last seek.
     */
    private long readBytes;

    /**
     * The most payload bytes read from the segments between seeks.
     */
    private final long budget;

    /**
     * The largest codec delay of the audio tracks in nanoseconds, how far past the end of the timeline their
     * timestamps run.
     */
    private final long tailNanos;

    /**
     * Constructs a timeline.
     *
     * @param tracks the tracks of the file's own segment
     * @param parts the parts, in playback order
     * @param segments the segments, the file's own first
     * @param durationNanos the duration, or {@code -1}
     * @param editions the edition played
     */
    private MatroskaTimeline(List<Track> tracks, List<Part> parts, List<Segment> segments, long durationNanos,
                             List<ChapterEdition> editions) {
        tracks = primedTracks(tracks, parts.getFirst());
        this.tracks = tracks;
        this.parts = parts;
        this.segments = segments;
        this.durationNanos = durationNanos;
        this.editions = editions;
        this.states = new HashMap<>();
        for (var track : tracks) {
            var state = new TrackState(track);
            state.configSegment = segments.getFirst();
            states.put(track.id(), state);
        }
        this.out = new ArrayDeque<>();
        this.partIndex = -1;
        var size = 0L;
        for (var segment : segments) {
            var length = segment.demuxer.rawSourceSize();
            size = length < 0 || size > Long.MAX_VALUE / 8 - length ? Long.MAX_VALUE / 8 : size + length;
        }
        this.budget = Math.max(MIN_READ_BUDGET, size * READ_FACTOR);
        var tail = 0L;
        for (var track : tracks) {
            tail = Math.max(tail, track.timeBase().rescale(codecDelay(track), NANOS));
        }
        this.tailNanos = tail;
    }

    /**
     * Gives the audio tracks of the timeline, other than Opus, the codec delay of the segment tracks that feed them in
     * the first part, which is the priming their decoders drop from the start of the timeline.
     *
     * <p>An Opus decoder takes its pre-skip from the codec configuration, which the first packet of a linked segment
     * carries, so an Opus track keeps its own.
     *
     * @param tracks the tracks of the file's own segment
     * @param first the first part
     * @return the tracks of the timeline
     */
    private static List<Track> primedTracks(List<Track> tracks, Part first) {
        if (first.whole()) {
            return tracks;
        }
        List<Track> result = null;
        for (var entry : first.segment().trackMap.entrySet()) {
            var source = first.segment().tracks.get(entry.getKey());
            if (!(source instanceof AudioTrack sourceAudio) || source.codec() == Codec.OPUS) {
                continue;
            }
            for (var index = 0; index < tracks.size(); index++) {
                if (tracks.get(index).id() != entry.getValue() || !(tracks.get(index) instanceof AudioTrack audio)
                        || audio.sampleRate() <= 0) {
                    continue;
                }
                var skip = sourceAudio.sampleRate() <= 0 || sourceAudio.skipSamples() <= 0 ? 0
                        : Rational.timeBaseOf(sourceAudio.sampleRate()).rescale(sourceAudio.skipSamples(),
                        Rational.timeBaseOf(audio.sampleRate()));
                if (skip != audio.skipSamples()) {
                    if (result == null) {
                        result = new ArrayList<>(tracks);
                    }
                    result.set(index, audio.toBuilder().skipSamples(skip).build());
                }
            }
        }
        return result == null ? tracks : List.copyOf(result);
    }

    /**
     * A part of the timeline: a span of a segment.
     *
     * @param segment the segment read
     * @param startNanos the start of the span in the segment, in nanoseconds
     * @param endNanos the end of the span, excluded
     * @param offsetNanos the start of the part on the timeline
     * @param whole whether the part is a whole hard-linked segment, whose timestamps are kept
     */
    private record Part(Segment segment, long startNanos, long endNanos, long offsetNanos, boolean whole) {
    }

    /**
     * A segment the timeline reads.
     */
    private static final class Segment {
        /**
         * The demuxer of the segment, presenting it as stored.
         */
        private final MatroskaDemuxer demuxer;

        /**
         * The source of a linked segment, closed with the timeline, or {@code null} for the file's own segment.
         */
        private final MediaSource source;

        /**
         * The {@code SegmentUUID} as hexadecimal digits, or {@code null}.
         */
        private final String uuid;

        /**
         * The tracks of the segment, by identifier.
         */
        private final Map<Integer, Track> tracks;

        /**
         * The timeline track each track of the segment feeds, by identifier.
         */
        private final Map<Integer, Integer> trackMap;

        /**
         * Whether the demuxer is positioned at the start of the segment.
         */
        private boolean atStart;

        /**
         * The presentation time of the first key frame of the segment's first video track, in nanoseconds, for a
         * segment of a hard-linked chain: {@link Long#MIN_VALUE} when it has no video track, {@link Long#MAX_VALUE}
         * when none of its first frames is a key frame.
         */
        private long firstKeyNanos = Long.MIN_VALUE;

        /**
         * Constructs a segment.
         *
         * @param demuxer the demuxer presenting the segment as stored
         * @param source the owned source, or {@code null}
         * @param timelineTracks the tracks of the timeline
         */
        private Segment(MatroskaDemuxer demuxer, MediaSource source, List<Track> timelineTracks) {
            this.demuxer = demuxer;
            this.source = source;
            this.uuid = demuxer.segmentUuid();
            this.tracks = new HashMap<>();
            for (var track : demuxer.rawTracks()) {
                tracks.put(track.id(), track);
            }
            this.trackMap = mapTracks(demuxer.rawTracks(), timelineTracks);
            this.atStart = true;
            if (source != null) {
                demuxer.retainSource();
            }
        }
    }

    /**
     * Maps the tracks of a segment to those of the timeline: a track feeds the timeline track of the same number
     * when it has the same kind and codec, and otherwise the timeline track of its kind and codec at the same rank.
     *
     * @param from the tracks of the segment
     * @param to the tracks of the timeline
     * @return the timeline track identifier of each mapped segment track
     */
    private static Map<Integer, Integer> mapTracks(List<Track> from, List<Track> to) {
        var map = new HashMap<Integer, Integer>();
        var used = new HashSet<Integer>();
        for (var track : from) {
            for (var target : to) {
                if (target.id() == track.id() && compatible(track, target)) {
                    map.put(track.id(), target.id());
                    used.add(target.id());
                }
            }
        }
        for (var track : from) {
            if (map.containsKey(track.id())) {
                continue;
            }
            for (var target : to) {
                if (!used.contains(target.id()) && compatible(track, target)) {
                    map.put(track.id(), target.id());
                    used.add(target.id());
                    break;
                }
            }
        }
        return map;
    }

    /**
     * {@return whether two tracks are of the same kind and codec}
     *
     * @param first a track
     * @param second another track
     */
    private static boolean compatible(Track first, Track second) {
        return first.getClass() == second.getClass() && first.codec() == second.codec();
    }

    /**
     * Collects the segments and parts while a timeline is built.
     */
    private static final class Builder {
        /**
         * The file's own demuxer.
         */
        private final MatroskaDemuxer main;

        /**
         * The resolver of linked segments.
         */
        private final DataReferenceResolver resolver;

        /**
         * The options linked segments are demuxed with.
         */
        private final DemuxerOptions linkedOptions;

        /**
         * The segments opened, by {@code SegmentUUID}; a segment that could not be opened maps to {@code null}.
         */
        private final Map<String, Segment> byUuid;

        /**
         * The segments opened, the file's own first.
         */
        private final List<Segment> segments;

        /**
         * The parts collected.
         */
        private final List<Part> parts;

        /**
         * The end of the parts collected so far on the timeline, in nanoseconds.
         */
        private long offset;

        /**
         * The number of chapters visited so far.
         */
        private int visits;

        /**
         * Constructs a builder.
         *
         * @param main the file's own demuxer
         * @param options the options of the file's demuxer
         */
        private Builder(MatroskaDemuxer main, DemuxerOptions options) {
            this.main = main;
            this.resolver = options.dataReferences();
            this.linkedOptions = options.toBuilder().virtualTimeline(false).noDataReferences().defaultEdition()
                    .build();
            this.byUuid = new HashMap<>();
            this.segments = new ArrayList<>();
            this.parts = new ArrayList<>();
            var own = new Segment(main, null, main.rawTracks());
            segments.add(own);
            if (own.uuid != null) {
                byUuid.put(own.uuid, own);
            }
        }

        /**
         * {@return the file's own segment}
         */
        private Segment own() {
            return segments.getFirst();
        }

        /**
         * Returns the segment of a {@code SegmentUUID}, opening it through the resolver on first use.
         *
         * @param uuid the identifier as hexadecimal digits
         * @param fileName the file name the referencing segment records, or empty
         * @return the segment, or {@code null} when it cannot be opened
         */
        private Segment segment(String uuid, String fileName) {
            if (byUuid.containsKey(uuid)) {
                return byUuid.get(uuid);
            }
            var segment = open(new DataReference(DataReference.Kind.MATROSKA_SEGMENT, uuid, fileName));
            if (segment != null && !uuid.equals(segment.uuid)) {
                close(segment);
                segment = null;
            }
            byUuid.put(uuid, segment);
            if (segment != null) {
                segments.add(segment);
            }
            return segment;
        }

        /**
         * Returns the segment whose {@code PrevUUID} names a segment, opening it through the resolver.
         *
         * @param uuid the identifier of the segment before it, as hexadecimal digits
         * @return the segment, or {@code null} when it cannot be opened
         */
        private Segment next(String uuid) {
            var segment = open(new DataReference(DataReference.Kind.MATROSKA_NEXT_SEGMENT, uuid, ""));
            if (segment == null) {
                return null;
            }
            if (segment.uuid == null || byUuid.containsKey(segment.uuid) || !uuid.equals(segment.demuxer.prevUuid())) {
                close(segment);
                return null;
            }
            byUuid.put(segment.uuid, segment);
            segments.add(segment);
            return segment;
        }

        /**
         * Opens the segment a reference names.
         *
         * @param reference the reference to resolve
         * @return the segment, or {@code null} when the resolver does not supply it, too many segments are open, or
         * it is not a usable Matroska file
         */
        private Segment open(DataReference reference) {
            if (segments.size() >= MAX_SEGMENTS) {
                return null;
            }
            var source = resolver.resolve(reference).orElse(null);
            if (source == null) {
                return null;
            }
            try {
                return new Segment(new MatroskaDemuxer(source, linkedOptions), source, main.rawTracks());
            } catch (MediaException.Malformed | MediaException.Unsupported | MediaException.LimitExceeded exception) {
                source.close();
                return null;
            } catch (RuntimeException | Error exception) {
                source.close();
                throw exception;
            }
        }

        /**
         * Closes a linked segment.
         *
         * @param segment the segment to close
         */
        private static void close(Segment segment) {
            segment.demuxer.close();
            if (segment.source != null) {
                segment.source.close();
            }
        }

        /**
         * Closes every linked segment opened.
         */
        private void closeAll() {
            for (var segment : segments) {
                if (segment.source != null) {
                    close(segment);
                }
            }
        }

        /**
         * Adds a part.
         *
         * @param segment the segment the part reads
         * @param start the start in the segment, in nanoseconds
         * @param end the end, after {@code start}
         */
        private void add(Segment segment, long start, long end) {
            if (parts.size() >= MAX_PARTS || end <= start || end - start > Long.MAX_VALUE - offset) {
                return;
            }
            parts.add(new Part(segment, start, end, offset, false));
            offset += end - start;
        }

        /**
         * Adds the parts of the chapters of an ordered edition and returns the visible chapters on the timeline.
         *
         * <p>The chapters after the {@value #MAX_PARTS}th part, or after the {@value #MAX_VISITS}th chapter visited
         * since the timeline started being built, are left out.
         *
         * @param atoms the chapters, in playback order
         * @param edition the segment holding the edition
         * @param inherited the segment the enclosing chapter plays, or {@code null} when it is missing
         * @param links the linked editions being expanded, as {@code uuid/editionUid}
         * @param depth the number of linked editions being expanded
         * @return the visible chapters
         */
        private List<Chapter> expand(List<MatroskaTags.AtomEntry> atoms, Segment edition, Segment inherited,
                                     Set<String> links, int depth) {
            var chapters = new ArrayList<Chapter>();
            for (var atom : atoms) {
                if (++visits > MAX_VISITS || parts.size() >= MAX_PARTS) {
                    break;
                }
                if (!atom.enabled()) {
                    continue;
                }
                var start = offset;
                var segment = inherited;
                if (atom.segmentUuid() != null) {
                    var uuid = hex(atom.segmentUuid());
                    segment = uuid.equals(edition.uuid) ? edition : segment(uuid, "");
                }
                var children = List.<Chapter>of();
                if (!atom.children().isEmpty()) {
                    children = expand(atom.children(), edition, segment, links, depth);
                } else if (segment != null && atom.segmentEditionUid() != 0 && segment != edition) {
                    linkedEdition(segment, atom.segmentEditionUid(), links, depth);
                } else if (segment != null && atom.startNanos() >= 0) {
                    var end = atom.endNanos() >= 0 ? atom.endNanos() : segment.demuxer.rawDurationNanos();
                    if (end >= 0) {
                        add(segment, atom.startNanos(), end);
                    }
                }
                if (atom.startNanos() < 0) {
                    continue;
                }
                if (atom.hidden()) {
                    // the nested chapters that are not hidden stay visible (RFC 9559 section 20.2.5)
                    chapters.addAll(children);
                } else {
                    chapters.add(new Chapter(start / 1000, offset / 1000, atom.title(), atom.titles(), children));
                }
            }
            return chapters;
        }

        /**
         * Adds the parts of an edition of a linked segment.
         *
         * @param segment the linked segment
         * @param editionUid the {@code EditionUID}
         * @param links the linked editions being expanded
         * @param depth the number of linked editions being expanded
         */
        private void linkedEdition(Segment segment, long editionUid, Set<String> links, int depth) {
            var key = segment.uuid + "/" + Long.toUnsignedString(editionUid);
            if (depth >= MAX_LINK_DEPTH || links.contains(key)) {
                return;
            }
            for (var entry : segment.demuxer.editionEntries()) {
                if (entry.uid() != editionUid) {
                    continue;
                }
                if (entry.ordered()) {
                    links.add(key);
                    expand(entry.atoms(), segment, segment, links, depth + 1);
                    links.remove(key);
                } else {
                    var duration = segment.demuxer.rawDurationNanos();
                    if (duration > 0) {
                        add(segment, 0, duration);
                    }
                }
                return;
            }
        }

        /**
         * Adds the segments of the hard-linked chain of the file's own segment, as whole parts.
         *
         * <p>A segment that names neither a segment before it nor one after it is in no chain.
         *
         * @return {@code true} when the chain holds another segment
         */
        private boolean chain() {
            var own = own();
            if (own.uuid == null || own.demuxer.prevUuid() == null && own.demuxer.nextUuid() == null) {
                return false;
            }
            var chain = new ArrayDeque<Segment>();
            chain.add(own);
            var current = own;
            while (current.demuxer.prevUuid() != null) {
                var previous = segment(current.demuxer.prevUuid(), current.demuxer.prevFilename());
                if (previous == null || chain.contains(previous) || previous.demuxer.nextUuid() != null
                        && !previous.demuxer.nextUuid().equals(current.uuid)) {
                    break;
                }
                chain.addFirst(previous);
                current = previous;
            }
            current = own;
            while (true) {
                var nextUuid = current.demuxer.nextUuid();
                var following = nextUuid != null ? segment(nextUuid, current.demuxer.nextFilename())
                        : next(current.uuid);
                if (following == null || chain.contains(following)) {
                    break;
                }
                chain.addLast(following);
                current = following;
            }
            if (chain.size() < 2) {
                return false;
            }
            // the parts go back in the file's own source, which a source read once must then keep
            own.demuxer.retainSource();
            var known = true;
            for (var segment : chain) {
                var duration = segment.demuxer.rawDurationNanos();
                segment.firstKeyNanos = firstKey(segment);
                parts.add(new Part(segment, Long.MIN_VALUE, Long.MAX_VALUE, offset, true));
                if (duration < 0 || duration > Long.MAX_VALUE - offset) {
                    known = false;
                } else {
                    offset += duration;
                }
            }
            if (!known) {
                offset = -1;
            }
            return true;
        }

        /**
         * Reads the first frames of a segment for the time of its first video key frame, leaving it at its start.
         *
         * @param segment a segment of a hard-linked chain
         * @return the time in nanoseconds, {@link Long#MIN_VALUE} when the segment has no video track that feeds the
         * timeline, or {@link Long#MAX_VALUE} when none of its first {@value #MAX_KEY_SEARCH} frames is a key frame
         */
        private static long firstKey(Segment segment) {
            Track video = null;
            for (var track : segment.demuxer.rawTracks()) {
                if (track instanceof VideoTrack && segment.trackMap.containsKey(track.id())) {
                    video = track;
                    break;
                }
            }
            if (video == null) {
                return Long.MIN_VALUE;
            }
            try {
                for (var count = 0; count < MAX_KEY_SEARCH; count++) {
                    var packet = segment.demuxer.rawRead();
                    if (packet == null) {
                        break;
                    }
                    if (packet.trackId() == video.id() && packet.keyframe() && packet.pts() != Packet.NO_TIMESTAMP) {
                        return video.timeBase().rescale(packet.pts(), NANOS);
                    }
                }
                return Long.MAX_VALUE;
            } finally {
                segment.demuxer.rewind();
                segment.atStart = true;
            }
        }
    }

    /**
     * Builds the timeline a file's demuxer presents.
     *
     * @param main the file's demuxer, presenting its segment as stored
     * @param options the options of the demuxer
     * @return the timeline, or {@code null} when the file is presented as stored: the played edition is not ordered
     * and the segment is not hard-linked to another the resolver supplies, or none of the ordered chapters can be
     * played
     */
    static MatroskaTimeline build(MatroskaDemuxer main, DemuxerOptions options) {
        var entries = main.editionEntries();
        var visible = new ArrayList<MatroskaTags.EditionEntry>();
        for (var entry : entries) {
            if (entry.visible()) {
                visible.add(entry);
            }
        }
        MatroskaTags.EditionEntry played = null;
        var requested = options.edition();
        if (requested.isPresent() && requested.getAsInt() < visible.size()) {
            played = visible.get(requested.getAsInt());
        } else {
            for (var entry : visible) {
                if (entry.defaultEdition()) {
                    played = entry;
                    break;
                }
            }
            if (played == null && !visible.isEmpty()) {
                played = visible.getFirst();
            }
        }
        var builder = new Builder(main, options);
        try {
            if (played != null && played.ordered()) {
                var links = new HashSet<String>();
                if (builder.own().uuid != null && played.uid() != 0) {
                    links.add(builder.own().uuid + "/" + Long.toUnsignedString(played.uid()));
                }
                var chapters = builder.expand(played.atoms(), builder.own(), builder.own(), links, 0);
                if (builder.parts.isEmpty()) {
                    builder.closeAll();
                    return null;
                }
                var edition = new ChapterEdition(chapters, true, false, played.names());
                return new MatroskaTimeline(main.rawTracks(), List.copyOf(builder.parts),
                        List.copyOf(builder.segments), builder.offset,
                        chapters.isEmpty() ? List.of() : List.of(edition));
            }
            if (builder.chain()) {
                return new MatroskaTimeline(main.rawTracks(), List.copyOf(builder.parts),
                        List.copyOf(builder.segments), builder.offset, chainEditions(builder.parts));
            }
            builder.closeAll();
            return null;
        } catch (RuntimeException | Error exception) {
            builder.closeAll();
            throw exception;
        }
    }

    /**
     * Returns the chapters of a hard-linked chain: those of the default edition of every segment, in time order, at
     * most {@value #MAX_CHAPTERS} of them.
     *
     * @param parts the whole parts of the chain
     * @return one edition holding the chapters, or none when no segment has chapters
     */
    private static List<ChapterEdition> chainEditions(List<Part> parts) {
        var chapters = new LinkedHashSet<Chapter>();
        List<Chapter.Title> names = List.of();
        var named = false;
        for (var part : parts) {
            var editions = part.segment().demuxer.rawEditions();
            var index = ChapterEdition.defaultIndex(editions);
            if (index < 0) {
                continue;
            }
            if (!named) {
                names = editions.get(index).names();
                named = true;
            }
            for (var chapter : editions.get(index).chapters()) {
                if (chapters.size() >= MAX_CHAPTERS) {
                    break;
                }
                chapters.add(chapter);
            }
        }
        if (chapters.isEmpty()) {
            return List.of();
        }
        var sorted = new ArrayList<>(chapters);
        sorted.sort(Comparator.comparingLong(Chapter::startMicros));
        return List.of(new ChapterEdition(sorted, true, false, names));
    }

    /**
     * Formats a {@code SegmentUUID}.
     *
     * @param uuid the 16 bytes
     * @return the identifier as lowercase hexadecimal digits
     */
    static String hex(byte[] uuid) {
        return java.util.HexFormat.of().formatHex(uuid);
    }

    /**
     * {@return the duration of the timeline in nanoseconds, or {@code -1} when unknown}
     */
    long durationNanos() {
        return durationNanos;
    }

    /**
     * {@return the edition played, with its chapters on the timeline, or no edition when it has no visible chapter}
     */
    List<ChapterEdition> editions() {
        return editions;
    }

    /**
     * Returns the next packet of the timeline.
     *
     * @return the packet, or {@code null} after the last part
     * @throws MediaException.LimitExceeded if the parts read more than {@value #READ_FACTOR} times the size of the
     * segments, or {@value #MIN_READ_BUDGET} bytes when that is more, since the start or the last seek
     */
    Packet read() {
        if (filter == null) {
            return next();
        }
        while (true) {
            var packet = filter.poll();
            if (packet != null) {
                return packet;
            }
            if (filter.isTransparent()) {
                filter = null;
                return next();
            }
            var next = next();
            if (next == null) {
                filter.finish();
            } else {
                filter.offer(next);
            }
        }
    }

    /**
     * Returns the next packet of the parts, without the seek filter.
     *
     * @return the packet, or {@code null} after the last part
     */
    private Packet next() {
        while (true) {
            var ready = out.poll();
            if (ready != null) {
                return ready;
            }
            if (finished) {
                return null;
            }
            if (part == null) {
                if (!startPart(partIndex + 1)) {
                    finished = true;
                }
                continue;
            }
            var segment = part.segment();
            var before = segment.demuxer.frameBytesRead();
            var packet = segment.demuxer.rawRead();
            segment.atStart = false;
            readBytes += segment.demuxer.frameBytesRead() - before;
            if (packet == null) {
                endPart();
            } else {
                if (readBytes > budget) {
                    throw new MediaException.LimitExceeded("the virtual timeline read more than " + budget
                            + " bytes, " + READ_FACTOR + " times its segments, since the last seek");
                }
                process(packet);
            }
        }
    }

    /**
     * Positions the timeline so that every track restarts at its last key frame at or before a time, or, for a time
     * before the timeline, at its start, from where the packets are those a read from the start returns; a time at or
     * past the end of the timeline and of the audio timestamps, which run the codec delay past it, leaves nothing to
     * read. A seek that fails leaves the timeline where it was.
     *
     * @param micros the time on the timeline, in microseconds
     */
    void seek(long micros) {
        var target = micros <= Long.MIN_VALUE / 1000 ? Long.MIN_VALUE : micros >= Long.MAX_VALUE / 1000
                ? Long.MAX_VALUE : micros * 1000;
        if (target < parts.getFirst().offsetNanos()) {
            // the first part is entered as a read reaches it, with its decode-only frames
            filter = null;
            out.clear();
            part = null;
            readBytes = 0;
            for (var state : states.values()) {
                state.reset(true);
                state.configSegment = segments.getFirst();
            }
            finished = false;
            partIndex = -1;
            return;
        }
        var index = -1;
        for (var candidate = 0; candidate < parts.size(); candidate++) {
            if (parts.get(candidate).offsetNanos() > target && index >= 0) {
                break;
            }
            index = candidate;
        }
        var past = index < 0 || durationNanos >= 0 && target - tailNanos >= durationNanos;
        if (!past) {
            // a hard-linked segment that starts before its first key frame continues the segment before it
            var time = Math.max(target, parts.get(index).offsetNanos());
            var first = index;
            while (index > 0 && parts.get(index).whole() && parts.get(index - 1).whole()
                    && parts.get(index).segment().firstKeyNanos > time) {
                index--;
            }
            var chosen = parts.get(index);
            var source = chosen.whole() ? time : chosen.startNanos() + (time - chosen.offsetNanos());
            if (index < first) {
                source = Math.min(source, parts.get(index + 1).offsetNanos() - 1);
            }
            chosen.segment().demuxer.rawSeek(Math.floorDiv(source, 1000L));
            chosen.segment().atStart = false;
            // the segment is read from its last key frames, and every track restarts within the later segments
            filter = index < first ? new SeekFilter(tracks, micros) : null;
        } else {
            filter = null;
        }
        out.clear();
        part = null;
        readBytes = 0;
        for (var state : states.values()) {
            state.reset(true);
            state.configSegment = segments.getFirst();
        }
        if (past) {
            finished = true;
            partIndex = parts.size();
            return;
        }
        finished = false;
        enterPart(index);
    }

    /**
     * Starts reading a part from its start.
     *
     * @param index the index of the part
     * @return {@code false} when there is no such part
     */
    private boolean startPart(int index) {
        if (index >= parts.size()) {
            return false;
        }
        var next = parts.get(index);
        var segment = next.segment();
        var atBeginning = next.whole() || next.startNanos() <= 0;
        if (!(segment.atStart && atBeginning)) {
            if (next.whole()) {
                segment.demuxer.rewind();
            } else {
                segment.demuxer.rawSeek(Math.floorDiv(next.startNanos(), 1000L), track -> prerollLead(segment,
                        track));
            }
            segment.atStart = atBeginning;
        }
        enterPart(index);
        return true;
    }

    /**
     * Makes a part the one being read, its segment already positioned.
     *
     * @param index the index of the part
     */
    private void enterPart(int index) {
        partIndex = index;
        part = parts.get(index);
        for (var state : states.values()) {
            state.reset(false);
        }
        openTracks = 0;
        var counted = new HashSet<Integer>();
        for (var entry : part.segment().trackMap.entrySet()) {
            var mapped = entry.getValue();
            var state = states.get(mapped);
            if (state == null) {
                continue;
            }
            if (state.audioOrVideo && counted.add(mapped)) {
                openTracks++;
            }
            var source = part.segment().tracks.get(entry.getKey());
            if (source instanceof AudioTrack && !part.whole()) {
                var base = state.track.timeBase();
                state.sourceDelay = codecDelay(source);
                state.shift = state.delay - rescaleDuration(state.sourceDelay, source.timeBase(), base);
                // the decoder drops its priming once, from the start of the timeline
                state.priming = Math.max(0, state.delay - NANOS.rescale(part.offsetNanos(), base));
            }
        }
    }

    /**
     * Ends the part being read, dropping what it holds back.
     */
    private void endPart() {
        for (var state : states.values()) {
            for (var held : state.held) {
                state.remember(held);
            }
            state.held.clear();
            state.dropPreroll();
        }
        part = null;
    }

    /**
     * Applies the part being read to one packet of its segment.
     *
     * <p>What a packet becomes depends only on the part and the packet, except for the codec configuration changes
     * between segments, so that a seek to a time gives the packets a linear read gives from there.
     *
     * @param packet the packet, in the segment's terms
     */
    private void process(Packet packet) {
        var segment = part.segment();
        var mapped = segment.trackMap.get(packet.trackId());
        var source = segment.tracks.get(packet.trackId());
        var state = mapped == null ? null : states.get(mapped);
        if (state == null || source == null) {
            return;
        }
        if (part.whole()) {
            var base = source.timeBase();
            var target = state.track.timeBase();
            emit(state, source, packet, rescale(packet.pts(), base, target), rescaleDuration(packet.duration(), base,
                    target), false, 0, 0);
            return;
        }
        var pts = packet.pts();
        if (pts == Packet.NO_TIMESTAMP) {
            if (state.inRange) {
                emit(state, source, packet, Packet.NO_TIMESTAMP, rescaleDuration(packet.duration(), source.timeBase(),
                        state.track.timeBase()), false, 0, 0);
            } else {
                state.remember(packet);
            }
            return;
        }
        var start = part.startNanos();
        var end = part.endNanos();
        var time = source.timeBase().rescale(pts, NANOS);
        var effective = effectiveDuration(packet, source);
        var length = effective >= 0 ? source.timeBase().rescale(effective, NANOS) : -1;
        var finish = length >= 0 ? time + length : -1;
        if (time - end > MARGIN_NANOS) {
            state.remember(packet);
            endPart();
            return;
        }
        if (source instanceof AudioTrack audio) {
            processAudio(state, audio, packet, effective);
            return;
        }
        if (time >= end) {
            state.inRange = false;
            if (state.done) {
                state.remember(packet);
                return;
            }
            if (!segment.demuxer.reorders(packet.trackId())) {
                state.remember(packet);
                finishTrack(state);
                return;
            }
            state.held.add(packet);
            if (state.held.size() >= REORDER_WINDOW) {
                for (var held : state.held) {
                    state.remember(held);
                }
                state.held.clear();
                finishTrack(state);
            }
            return;
        }
        if (!state.held.isEmpty()) {
            for (var held : state.held) {
                emitDiscarded(state, source, held);
            }
            state.held.clear();
        }
        var base = state.track.timeBase();
        // a frame without timestamp after this one belongs to the part only when this one does
        state.inRange = time >= start || finish > start;
        if (time < start) {
            if (finish > start) {
                emit(state, source, packet, NANOS.rescale(part.offsetNanos(), base),
                        NANOS.rescale(Math.min(finish, end) - start, base), false, 0, 0);
            } else if (source instanceof VideoTrack) {
                emitDiscarded(state, source, packet);
            } else {
                state.remember(packet);
            }
            return;
        }
        var mappedPts = map(pts, source, state);
        if (finish > end) {
            emit(state, source, packet, mappedPts, Math.max(0, NANOS.rescale(part.offsetNanos() + end - start, base)
                    - mappedPts), false, 0, 0);
            return;
        }
        emit(state, source, packet, mappedPts, rescaleDuration(packet.duration(), source.timeBase(), base), false, 0,
                0);
    }

    /**
     * Applies the part being read to one audio packet of its segment that has a timestamp.
     *
     * <p>The part presents the samples played between its start and its end, which lie the codec delay of the
     * segment track after the block timestamps. The samples the decoder still drops as its priming are added before
     * them: for Opus, whose decoder drops its pre-skip from everything it decodes, decode-only frames included, as
     * many samples of the straddling frame as the frames decoded before it leave to drop; for the other codecs,
     * whose priming is dropped from the presented samples, as many samples before the start.
     *
     * @param state the state of the timeline track
     * @param source the segment track
     * @param packet the packet, in the segment's terms
     * @param effective the duration of the packet in the segment track's time base, or
     * {@link Packet#UNKNOWN_DURATION}
     */
    private void processAudio(TrackState state, AudioTrack source, Packet packet, long effective) {
        var sourceBase = source.timeBase();
        var base = state.track.timeBase();
        var pts = packet.pts();
        var opus = source.codec() == Codec.OPUS;
        var played = NANOS.rescale(part.startNanos(), sourceBase) + state.sourceDelay;
        var end = NANOS.rescale(part.endNanos(), sourceBase) + state.sourceDelay;
        var start = opus ? played : played - rescaleDuration(state.priming, base, sourceBase);
        var finish = effective >= 0 ? pts + effective : -1;
        if (pts >= end) {
            state.inRange = false;
            state.remember(packet);
            finishTrack(state);
            return;
        }
        // a frame without timestamp after this one belongs to the part only when this one does
        state.inRange = pts >= start || finish > start;
        if (pts < start) {
            if (finish > start) {
                flushPreroll(state, source);
                var skip = start - pts;
                if (opus) {
                    skip = Math.max(0, skip - rescaleDuration(primingBefore(state, source, packet), base,
                            sourceBase));
                }
                var trimEnd = finish > end ? rescaleDuration(finish - end, sourceBase, base) : 0;
                emit(state, source, packet, map(pts, source, state), Math.max(0, rescaleDuration(effective,
                        sourceBase, base) - trimEnd), false, rescaleDuration(skip, sourceBase, base), trimEnd);
            } else if (needsPreroll(source.codec())) {
                state.keepPreroll(packet);
            } else {
                state.remember(packet);
            }
            return;
        }
        flushPreroll(state, source);
        var mappedPts = map(pts, source, state);
        if (finish > end) {
            var trimEnd = rescaleDuration(finish - end, sourceBase, base);
            emit(state, source, packet, mappedPts, Math.max(0, rescaleDuration(effective, sourceBase, base)
                    - trimEnd), false, 0, trimEnd);
            return;
        }
        emit(state, source, packet, mappedPts, rescaleDuration(packet.duration(), sourceBase, base), false, 0, 0);
    }

    /**
     * Returns the codec delay of an audio track: the samples its decoder drops from the start of the stream, which
     * play before the time its block timestamps give (RFC 9559, {@code CodecDelay}).
     *
     * @param track a track
     * @return the Opus pre-skip of the track's {@code OpusHead}, or the {@link AudioTrack#skipSamples()} of another
     * audio track, in the track's time base; zero for a track that is not audio
     */
    private static long codecDelay(Track track) {
        if (!(track instanceof AudioTrack audio)) {
            return 0;
        }
        if (audio.codec() == Codec.OPUS) {
            return opusPriming(audio.codecConfig().orElse(null), audio.timeBase());
        }
        if (audio.sampleRate() <= 0 || audio.skipSamples() <= 0) {
            return 0;
        }
        return Rational.timeBaseOf(audio.sampleRate()).rescale(audio.skipSamples(), audio.timeBase());
    }

    /**
     * Returns the pre-skip an Opus decoder created from a configuration drops.
     *
     * @param config the {@code OpusHead}, or {@code null}
     * @param base the time base of the result
     * @return the pre-skip in {@code base}, zero when the configuration has none
     */
    private static long opusPriming(byte[] config, Rational base) {
        var preSkip = AudioConfigs.opusPreSkip(config);
        return preSkip <= 0 ? 0 : OPUS_RATE.rescale(preSkip, base);
    }

    /**
     * Returns the priming an Opus decoder still drops when it decodes a packet, counting the decoder that a
     * codec configuration change the packet carries creates.
     *
     * @param state the state of the timeline track
     * @param source the segment track
     * @param packet the packet, in the segment's terms
     * @return the samples, in the time base of the timeline track
     */
    private long primingBefore(TrackState state, Track source, Packet packet) {
        var config = packet.sideData().newCodecConfig().orElse(null);
        if (config == null) {
            config = configChange(state, source, packet);
        }
        if (config != null && !Arrays.equals(config, state.activeConfig)) {
            return opusPriming(config, state.track.timeBase());
        }
        return state.priming;
    }

    /**
     * {@return whether an audio codec needs packets before a jump to rebuild its state: every codec but PCM, FLAC and
     * ALAC, whose packets decode on their own}
     *
     * @param codec the codec of an audio track
     */
    private static boolean needsPreroll(Codec codec) {
        return !codec.isPcm() && codec != Codec.FLAC && codec != Codec.ALAC;
    }

    /**
     * Returns how long before the start of a part a track of its segment is read.
     *
     * @param segment the segment of the part
     * @param track a track of the segment
     * @return {@value #PREROLL_LEAD_MICROS} microseconds for an audio track that feeds the timeline and whose codec
     * needs pre-roll, otherwise zero
     */
    private static long prerollLead(Segment segment, Track track) {
        return track instanceof AudioTrack && needsPreroll(track.codec()) && segment.trackMap.containsKey(track.id())
                ? PREROLL_LEAD_MICROS : 0;
    }

    /**
     * Returns how many of the audio packets before the start of a part a decoder needs to rebuild its state, as an
     * edit of an MP4 track counts them: one MP2 packet; an MP3 frame and the frames before it that its main data may
     * start in; the packets of 80 ms for Opus; three for AAC ELD without spectral band replication, whose window
     * spans four frames, and two for any other AAC, so that its spectral band replication and parametric stereo state
     * is rebuilt too; one for any other codec, whose transform overlaps the packet before.
     *
     * @param track the segment track
     * @param packets the packets before the start, oldest first
     * @return the number of packets, the last ones, at most the number held
     */
    private static int prerollCount(AudioTrack track, ArrayDeque<Packet> packets) {
        if (packets.isEmpty() || !needsPreroll(track.codec())) {
            return 0;
        }
        var count = switch (track.codec()) {
            case MP2 -> 1;
            case MP3 -> {
                var frames = 0;
                var reach = 0L;
                var iterator = packets.descendingIterator();
                while (iterator.hasNext() && reach < MP3_MAX_MAIN_DATA_BEGIN) {
                    var size = iterator.next().data().length;
                    if (frames++ > 0) {
                        reach += Math.max(1, size - MP3_FRAME_OVERHEAD);
                    }
                }
                yield frames;
            }
            case OPUS -> {
                var frames = 0;
                var covered = 0L;
                var iterator = packets.descendingIterator();
                while (iterator.hasNext() && covered < OPUS_PREROLL_NANOS) {
                    var duration = iterator.next().duration();
                    covered += duration >= 0 ? track.timeBase().rescale(duration, NANOS) : OPUS_PREROLL_NANOS;
                    frames++;
                }
                yield frames;
            }
            case AAC -> aacPreroll(track);
            default -> 1;
        };
        return Math.min(count, packets.size());
    }

    /**
     * Returns the AAC pre-roll of {@link #prerollCount}.
     *
     * @param track the AAC track
     * @return three for ELD without spectral band replication, otherwise two
     */
    private static int aacPreroll(AudioTrack track) {
        var config = track.codecConfig().orElse(null);
        if (config == null) {
            return 2;
        }
        try {
            var aac = AudioConfigs.parseAac(config);
            return aac.objectType() == 39 && !aac.sbr() ? 3 : 2;
        } catch (MediaException exception) {
            return 2;
        }
    }

    /**
     * Queues the pre-roll a track holds for the part being read, flagged {@link PacketFlag#DISCARD}, ahead of the
     * first frame the part presents, and drops the other frames it holds.
     *
     * @param state the state of the timeline track
     * @param source the segment track
     */
    private void flushPreroll(TrackState state, AudioTrack source) {
        if (state.preroll.isEmpty()) {
            return;
        }
        var count = prerollCount(source, state.preroll);
        while (state.preroll.size() > count) {
            state.remember(state.preroll.poll());
        }
        for (var packet = state.preroll.poll(); packet != null; packet = state.preroll.poll()) {
            emit(state, source, packet, map(packet.pts(), source, state), rescaleDuration(packet.duration(),
                    source.timeBase(), state.track.timeBase()), true, 0, 0);
        }
    }

    /**
     * Returns the duration of a packet, computed from its size for PCM audio, whose Matroska blocks declare none.
     *
     * @param packet the packet, in the segment's terms
     * @param source its track
     * @return the duration in the track's time base, or {@link Packet#UNKNOWN_DURATION}
     */
    private static long effectiveDuration(Packet packet, Track source) {
        if (packet.duration() >= 0 || !(source instanceof AudioTrack audio) || !audio.codec().isPcm()) {
            return packet.duration();
        }
        var channels = audio.layout().map(layout -> layout.channels()).orElse(0);
        var frame = channels * ((audio.bitsPerSample() + 7) / 8);
        if (frame <= 0 || audio.sampleRate() <= 0) {
            return Packet.UNKNOWN_DURATION;
        }
        return Rational.timeBaseOf(audio.sampleRate()).rescale(packet.data().length / frame, audio.timeBase());
    }

    /**
     * Marks a track of the part being read as having no frame left before its end, and ends the part when no audio
     * or video track has.
     *
     * @param state the track state
     */
    private void finishTrack(TrackState state) {
        if (state.done) {
            return;
        }
        state.done = true;
        if (state.audioOrVideo && --openTracks <= 0) {
            endPart();
        }
    }

    /**
     * Moves a time of a segment track onto the timeline, in the time base of the timeline track.
     *
     * <p>An audio time also moves by the difference between the codec delays of the timeline track and the segment
     * track, so that a sample has the timestamp it would have in the file's own segment.
     *
     * @param value the time, in the segment track's time base
     * @param source the segment track
     * @param state the state of the timeline track
     * @return the time on the timeline
     */
    private long map(long value, Track source, TrackState state) {
        var base = state.track.timeBase();
        if (source.timeBase().equals(base)) {
            return value + NANOS.rescale(part.offsetNanos() - part.startNanos(), base) + state.shift;
        }
        return NANOS.rescale(source.timeBase().rescale(value, NANOS) - part.startNanos() + part.offsetNanos(), base)
                + state.shift;
    }

    /**
     * Converts a timestamp between time bases.
     *
     * @param value the timestamp, or {@link Packet#NO_TIMESTAMP}
     * @param from the source time base
     * @param to the target time base
     * @return the timestamp
     */
    private static long rescale(long value, Rational from, Rational to) {
        return value == Packet.NO_TIMESTAMP || from.equals(to) ? value : from.rescale(value, to);
    }

    /**
     * Converts a duration between time bases.
     *
     * @param value the duration, or {@link Packet#UNKNOWN_DURATION}
     * @param from the source time base
     * @param to the target time base
     * @return the duration
     */
    private static long rescaleDuration(long value, Rational from, Rational to) {
        return value < 0 || from.equals(to) ? value : Math.max(0, from.rescale(value, to));
    }

    /**
     * Returns a video frame needed only to decode others, flagged {@link PacketFlag#DISCARD}.
     *
     * @param state the state of its timeline track
     * @param source the segment track
     * @param packet the packet, in the segment's terms
     */
    private void emitDiscarded(TrackState state, Track source, Packet packet) {
        emit(state, source, packet, Packet.NO_TIMESTAMP, rescaleDuration(packet.duration(), source.timeBase(),
                state.track.timeBase()), true, 0, 0);
    }

    /**
     * Queues a packet of the timeline.
     *
     * <p>The decoding timestamp moves with the presentation timestamp and, within a part, is at least the start of
     * the part, so that it never decreases. A discarded video or data frame gets a negative presentation timestamp,
     * which no presented frame has; a discarded audio frame keeps its own.
     *
     * @param state the state of its timeline track
     * @param source the segment track
     * @param packet the packet, in the segment's terms
     * @param pts the presentation timestamp on the timeline; ignored for a discarded video or data frame
     * @param duration the duration on the timeline
     * @param discard whether the frame is decoded but not presented
     * @param discardStart the output to drop from the start, added to what the packet drops
     * @param discardEnd the output to drop from the end, added to what the packet drops
     */
    private void emit(TrackState state, Track source, Packet packet, long pts, long duration, boolean discard,
                      long discardStart, long discardEnd) {
        var base = state.track.timeBase();
        var whole = part.whole();
        var natural = packet.pts() == Packet.NO_TIMESTAMP ? Packet.NO_TIMESTAMP
                : whole ? rescale(packet.pts(), source.timeBase(), base) : map(packet.pts(), source, state);
        var offset = whole ? 0 : NANOS.rescale(part.offsetNanos(), base);
        var dts = packet.dts();
        if (dts != Packet.NO_TIMESTAMP) {
            dts = dts == packet.pts() ? natural : whole ? rescale(dts, source.timeBase(), base)
                    : map(dts, source, state);
            if (!whole) {
                dts = Math.max(dts, offset);
            }
        }
        if (discard && !(source instanceof AudioTrack)) {
            pts = natural == Packet.NO_TIMESTAMP ? Packet.NO_TIMESTAMP : -1 - Math.abs(natural - offset);
        }
        Set<PacketFlag> flags = packet.flags();
        if (discard && !flags.contains(PacketFlag.DISCARD)) {
            var copy = EnumSet.of(PacketFlag.DISCARD);
            copy.addAll(flags);
            flags = copy;
        }
        var sideData = packet.sideData();
        var own = sideData.newCodecConfig().orElse(null);
        var change = own != null ? null : configChange(state, source, packet);
        if (source instanceof AudioTrack && source.codec() == Codec.OPUS) {
            var config = own != null ? own : change;
            if (config != null && !Arrays.equals(config, state.activeConfig)) {
                state.priming = opusPriming(config, base);
            }
            var decoded = rescaleDuration(packet.duration(), source.timeBase(), base);
            if (decoded >= 0) {
                state.priming = Math.max(0, state.priming - decoded);
            }
        }
        if (own != null) {
            state.activeConfig = own;
        }
        state.configSegment = part.segment();
        state.pendingConfig = null;
        if (discardStart > 0 || discardEnd > 0 || change != null) {
            var builder = sideData.toBuilder();
            if (discardStart > 0 || discardEnd > 0) {
                builder.discard(Math.max(discardStart, sideData.discardStart()),
                        Math.max(discardEnd, sideData.discardEnd()));
            }
            if (change != null) {
                builder.newCodecConfig(change);
                state.activeConfig = change;
            }
            sideData = builder.build();
        }
        out.add(new Packet(state.track.id(), packet.data(), pts, dts, duration, flags, sideData));
    }

    /**
     * Returns the codec configuration a packet that carries none of its own passes on to the decoder of its track:
     * that of a dropped packet not yet passed on, or that of the packet's segment where the decoder last decoded
     * another segment with another configuration.
     *
     * @param state the state of the timeline track
     * @param source the segment track
     * @param packet the packet, in the segment's terms
     * @return the configuration, or {@code null} when it stays the one in use
     */
    private byte[] configChange(TrackState state, Track source, Packet packet) {
        if (packet.sideData().newCodecConfig().isPresent()) {
            return null;
        }
        if (state.pendingConfig != null) {
            return state.pendingConfig;
        }
        if (state.configSegment != part.segment()) {
            var config = source.codecConfig().orElse(null);
            if (config != null && !Arrays.equals(config, state.activeConfig)) {
                return config;
            }
        }
        return null;
    }

    /**
     * {@return the tracks of the timeline}
     */
    List<Track> tracks() {
        return tracks;
    }

    @Override
    public void close() {
        for (var segment : segments) {
            if (segment.source != null) {
                Builder.close(segment);
            }
        }
    }

    /**
     * The reading state of one timeline track.
     */
    private static final class TrackState {
        /**
         * The timeline track.
         */
        private final Track track;

        /**
         * Whether the track is an audio or video track, whose frames end a part.
         */
        private final boolean audioOrVideo;

        /**
         * The codec configuration the decoder of the track uses.
         */
        private byte[] activeConfig;

        /**
         * The codec configuration change of a dropped packet, not yet passed on, or {@code null}.
         */
        private byte[] pendingConfig;

        /**
         * The segment whose frames the decoder of the track decoded last.
         */
        private Segment configSegment;

        /**
         * The codec delay of the timeline track, in its time base.
         */
        private final long delay;

        /**
         * The codec delay of the segment track that feeds the track in the part being read, in that track's time
         * base.
         */
        private long sourceDelay;

        /**
         * The difference between {@link #delay} and {@link #sourceDelay}, in the time base of the timeline track,
         * added to the audio timestamps of the part being read.
         */
        private long shift;

        /**
         * The samples the decoder of the track still drops as its priming, in the time base of the timeline track.
         */
        private long priming;

        /**
         * Whether the track has no frame left before the end of the part being read.
         */
        private boolean done;

        /**
         * Whether the last frame read lay within the part being read.
         */
        private boolean inRange;

        /**
         * The frames past the end of the part held until a later frame shows whether they are referenced.
         */
        private final List<Packet> held;

        /**
         * The last audio frames before the start of the part, oldest first, held until the first frame the part
         * presents, ahead of which those that rebuild the decoder state are returned.
         */
        private final ArrayDeque<Packet> preroll;

        /**
         * Constructs the state of a track.
         *
         * @param track the timeline track
         */
        private TrackState(Track track) {
            this.track = track;
            this.audioOrVideo = track instanceof AudioTrack || track instanceof VideoTrack;
            this.held = new ArrayList<>();
            this.preroll = new ArrayDeque<>();
            this.activeConfig = track.codecConfig().orElse(null);
            this.delay = codecDelay(track);
        }

        /**
         * Resets the state for a new part, or for a seek.
         *
         * @param seek whether the reset follows a seek, after which decoders restart
         */
        private void reset(boolean seek) {
            done = false;
            inRange = false;
            held.clear();
            dropPreroll();
            if (seek) {
                activeConfig = track.codecConfig().orElse(null);
                pendingConfig = null;
            }
        }

        /**
         * Notes the codec configuration change of a dropped packet.
         *
         * @param packet the dropped packet
         */
        private void remember(Packet packet) {
            var config = packet.sideData().newCodecConfig();
            if (config.isPresent()) {
                pendingConfig = config.get();
            }
        }

        /**
         * Holds an audio frame before the start of the part as a candidate for its pre-roll, dropping the oldest
         * frame held past {@value #MAX_PREROLL}.
         *
         * @param packet the frame, in the segment's terms
         */
        private void keepPreroll(Packet packet) {
            if (preroll.size() >= MAX_PREROLL) {
                remember(preroll.poll());
            }
            preroll.add(packet);
        }

        /**
         * Drops the pre-roll candidates held.
         */
        private void dropPreroll() {
            for (var packet = preroll.poll(); packet != null; packet = preroll.poll()) {
                remember(packet);
            }
        }
    }
}
