package com.github.auties00.smalt.container.ogg;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.container.AttachedPicture;
import com.github.auties00.smalt.container.Chapter;
import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.container.Demuxer;
import com.github.auties00.smalt.container.DemuxerOptions;
import com.github.auties00.smalt.container.internal.Languages;
import com.github.auties00.smalt.container.internal.SeekFilter;
import com.github.auties00.smalt.container.internal.SeekTimes;
import com.github.auties00.smalt.internal.io.ForwardOnlySource;
import com.github.auties00.smalt.internal.io.SegmentBacked;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.DataTrack;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.Track;
import com.github.auties00.smalt.track.VideoTrack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Reads Ogg files (RFC 3533) carrying Opus, Vorbis, FLAC, Speex or Theora streams.
 *
 * <p>Every logical stream becomes a track whose identifier is the stream's index in
 * beginning-of-stream order. Opus (RFC 7845), Vorbis, FLAC, Speex and Theora streams are fully
 * mapped: their header packets become the track's codec configuration (the {@code OpusHead}
 * packet; the three Vorbis or Theora headers Xiph-laced; {@code fLaC} plus the FLAC metadata
 * blocks; the Speex header), their comment headers become {@link #metadata()} and
 * {@link #trackMetadata(int)}, and header packets are never returned by {@link #read()}. An Ogg
 * Skeleton stream (versions 3 and 4) becomes a {@link DataTrack} whose configuration is its
 * {@code fishead} packet; the message header fields of its {@code fisbone} packets (such as
 * {@code Content-Type}, {@code Role} or {@code Name}) join the {@link #trackMetadata(int)} of the
 * stream they describe, their {@code Language} field gives that track's
 * {@linkplain Track#language() language}, and a stream of an unknown codec, otherwise a
 * {@link DataTrack} whose packets are returned untouched and without timestamps, takes its time
 * base and timestamps from the granule rate and shift its {@code fisbone} declares.
 *
 * <p>Timestamps are in units of each track's time base and follow the granule positions: a
 * stream's first packets are anchored backwards from the first granule position, later packets
 * advance by their durations, and the last packet on the end-of-stream page is shortened to end
 * at that page's granule position (end trimming), the samples it loses being reported as its
 * {@link com.github.auties00.smalt.track.PacketSideData#discardEnd()}. For Opus the pre-skip is subtracted, so the
 * first packet of a stream starting at granule zero is stamped {@code -preSkip} and the decoder's
 * own pre-skip removal makes output start at zero; the first Vorbis packet, which decodes to no
 * output, is stamped to end at zero. Theora timestamps count frames from the granule position's
 * key frame number plus the frames since that key frame, and Speex timestamps count samples. A
 * track's {@code skipSamples} is non-zero only when a stream starts before granule zero, and its
 * {@code duration} is the exact number of samples (frames for Theora) a reader keeps after the
 * decoder's own priming removal and {@code skipSamples}.
 *
 * <p>Pages that fail their CRC, are truncated, or are separated by garbage are skipped by
 * searching for the next capture pattern; a lost page drops the packet it carried a piece of.
 * Grouped (multiplexed) streams are interleaved in file order. Chained streams, where a new set
 * of beginning-of-stream pages follows the end of the previous link, are exposed sequentially:
 * each link's streams are separate tracks, returned after the previous link's packets, and their
 * timestamps start where the previous link ended, so the concatenated presentation is
 * continuous. {@link #links()} describes every link, with its own tags, chapters and pictures;
 * {@link #durationMicros()} spans every link, {@link #metadata()} and {@link #attachedPictures()}
 * are those of the first link, and {@link #chapters()} holds the chapters of every link on the
 * presentation timeline.
 *
 * <p>Track durations come from the last granule position of every stream. Construction reads the
 * first link's headers and then the last pages of the file, from its end back to the last page of
 * every timestamped stream (at least the last 64 KiB, or everything after the headers when that is
 * shorter), so the pages in between are read once, by {@link #read()}. A file of up to
 * 64 MiB is scanned completely at construction instead, which discovers every chained link, when
 * those last pages do not settle the description: when one of them begins a stream or belongs to
 * a stream that did not start in the first link, when a stream of the first link is not described
 * and anchored by its first pages, when two of its streams share a serial number, or when the last
 * page of a timestamped stream is not within the last 960 KiB. In a larger file whose last pages
 * belong to a stream that did not start in the first link, the links are found by bisection
 * instead: the end of each link is the first page whose serial number none of the link's streams
 * has, the headers of the next link are read there, and the duration of each link comes from its
 * own last pages. In a larger file, a stream whose last page is not within the last 4 MiB of its
 * link has duration {@code -1}. When the source size is unknown only the first link is described
 * and durations are {@code -1}. A source of at most 256 KiB that
 * is not in memory is read whole at construction, in one pass, and parsed in place. A larger
 * source that is not in memory is read through a cache of the {@value #MAX_CACHED_BYTES} bytes
 * that follow the headers, which keeps whatever construction reads there after the headers (the
 * last pages of a smaller file, the pages of a full scan) until the demux reaches it, so those
 * bytes are read from the source once even when construction scans the file completely.
 *
 * <p>A chain whose later link reuses a serial number of the link before it, which RFC 3533
 * forbids, is recognized when the file is scanned completely; otherwise that link is recognized
 * only when it begins within the pages examined near the end or on a page that bisection reads,
 * and its packets are not returned when it is not recognized.
 *
 * <p>The comment headers of each link also give its chapters ({@code CHAPTERxxx} and
 * {@code CHAPTERxxxNAME} fields, which are then left out of the tags) and pictures
 * ({@code METADATA_BLOCK_PICTURE} fields, and the {@code PICTURE} blocks of Ogg FLAC).
 *
 * <p>A file of known size can seek, chained or not. {@link #seek(long)} picks the link that plays at
 * the target and, for every timestamped stream of that link, bisects the link on the stream's
 * granule positions to the last of its pages whose granule position is at or before the target (for
 * Theora, then to the last page before the key frame that page's granule position names), reads the
 * link's headers again, continues reading at the earliest of those pages, and lets every track of
 * the link restart at its last packet at or before the target, as {@link SeekFilter} describes. The
 * packets of the first page read after a seek are stamped back from that page's granule position, so
 * their timestamps are exact, except for the first Vorbis packet, whose duration depends on the block
 * size of a packet that was not read: it is stamped as if that block were long, never later than its
 * true start. A seek that lands on the first data page of a link reads the link as a linear read does,
 * with the same timestamps. A source of unknown size seeks by reading: a target at or past
 * the end of the packets read so far is reached by reading on and dropping what precedes it, and an
 * earlier target, on a source with random access, by reading again from the start of the file.
 *
 * <p>A source that reads its input once, front to back (a stream), is told after every page which
 * bytes are no longer needed. The last pages that give the duration, and the scan for chained
 * links, are read only when the whole input fits the source's memory budget; otherwise the
 * duration is unknown and only the first link is described. Such a source seeks only forward, by
 * reading, whatever its size.
 *
 * @implNote This implementation relies on RFC 3533 section 4, under which every chained logical
 * bitstream has a serial number unique within the physical bitstream: when the pages near the end
 * carry only first-link serial numbers and none begins a stream, the last link is the first, so no
 * other link lies between the headers and the end, and a link ends at the first page of a serial
 * number it does not have. As in libopusfile (BSD-3), the last pages are found by resynchronizing
 * forward from chunk boundaries taken backwards from the end, the chunks doubling from 64 KiB and
 * stopping at the end of the headers, and link boundaries by bisecting on serial numbers. The pages
 * those readers accept are trusted by the demux, which skips their CRC as it does for the pages a
 * full scan accepted.
 */
public final class OggDemuxer implements Demuxer {
    /**
     * The largest file that is scanned completely at construction when its last pages do not
     * settle the durations.
     */
    private static final long MAX_SCANNED_BYTES = 64L << 20;

    /**
     * The byte range below which a seek or a link search stops bisecting and reads the pages in
     * between.
     */
    private static final long BISECTION_STOP = 64 * 1024;

    /**
     * The largest source that is not in memory and is read whole at construction.
     *
     * @implNote This implementation picks twice the buffer a page reader allocates for a source
     * that is not in memory ({@code 2 * OggPageReader.MAX_PAGE_SIZE}, about 128 KiB): a smaller file
     * then costs no more memory than reading it page by page, and the read of its last pages, which
     * would overlap what the page reader already buffered, disappears.
     */
    private static final int MAX_BUFFERED_BYTES = 256 * 1024;

    /**
     * The size of the window after the headers of a source that is not in memory whose bytes are
     * kept from construction to the demux.
     *
     * @implNote This implementation keeps up to 8 MiB, the budget other demuxers of this module
     * give the packets they keep from a construction-time walk: a file that must be scanned
     * completely and fits it is read from the source once instead of twice, while the common
     * case, whose last pages settle the description, keeps only the blocks of its last pages.
     */
    private static final long MAX_CACHED_BYTES = 8L << 20;

    /**
     * The largest prefix of a link read while collecting its headers.
     */
    private static final long MAX_HEADER_BYTES = 16L << 20;

    /**
     * The size of each backwards step when looking for the last pages of a large file.
     */
    private static final int TAIL_CHUNK = 64 * 1024;

    /**
     * The largest suffix examined when looking for the last pages of a large file.
     */
    private static final long MAX_TAIL_BYTES = 4L << 20;

    /**
     * The largest suffix examined when looking for the last pages of a file that is otherwise
     * scanned completely.
     *
     * @implNote This implementation bounds the search at four chunks that double from
     * {@value #TAIL_CHUNK} bytes (64, 128, 256 and 512 KiB, growing as libopusfile's backward search
     * does), so a file whose stream ends far from the end costs at most four extra reads before it
     * is scanned as a whole; the last page of a stream that runs to the end always starts within
     * the last {@value #TAIL_CHUNK} bytes unless garbage follows it.
     */
    private static final long MAX_SETTLED_TAIL_BYTES = 15L * TAIL_CHUNK;

    /**
     * The resource bounds.
     */
    private final MediaLimits limits;

    /**
     * The parser producing the packets, replaced by a seek.
     */
    private OggParser parser;

    /**
     * The tracks, in stream order.
     */
    private final List<Track> tracks;

    /**
     * The identifiers of {@link #tracks}.
     */
    private final Set<Integer> trackIds;

    /**
     * The time base of every track, by identifier.
     */
    private final Map<Integer, Rational> timeBases;

    /**
     * The tags of every track, by identifier.
     */
    private final Map<Integer, Map<String, String>> trackTags;

    /**
     * The metadata of the first link.
     */
    private final Map<String, String> metadata;

    /**
     * The duration of every link together, or {@code -1}.
     */
    private final long durationMicros;

    /**
     * The source the parser reads, possibly buffered or cached.
     */
    private final MediaSource input;

    /**
     * The size of {@link #input}, or {@code -1} when unknown.
     */
    private final long size;

    /**
     * Whether {@link #input} reads its input once, front to back.
     */
    private final boolean forwardOnly;

    /**
     * The described links, in file order.
     */
    private final List<Span> spans;

    /**
     * The public description of {@link #spans}.
     */
    private final List<OggLink> links;

    /**
     * The start of every link in microseconds.
     */
    private final long[] linkStarts;

    /**
     * The numbering every parser applies to the described links.
     */
    private final OggParser.Plan plan;

    /**
     * The chapters of every link, on the presentation timeline.
     */
    private final List<Chapter> chapters;

    /**
     * The pictures of the first link.
     */
    private final List<AttachedPicture> pictures;

    /**
     * The increasing offsets of pages whose CRC is known to match, or {@code null}.
     */
    private long[] trustedPages;

    /**
     * The number of valid entries in {@link #trustedPages}.
     */
    private int trustedCount;

    /**
     * The filter trimming the packets read after a seek, or {@code null}.
     */
    private SeekFilter filter;

    /**
     * The tracks of the links before the one the last seek by reading targeted, whose packets are
     * dropped.
     */
    private Set<Integer> passedTracks = Set.of();

    /**
     * The latest end, in microseconds, of a timestamped packet the parser produced, or
     * {@link Long#MIN_VALUE}; tracked only for a source that seeks by reading.
     */
    private long producedMicros = Long.MIN_VALUE;

    /**
     * Holds a described link and where it lies in the file.
     *
     * @param link the link, as the parser that discovered it saw it
     * @param end the offset where the link ends, the file size for the last link, or {@code -1}
     *            when the size is unknown
     * @param granules the last granule position of every timestamped stream of the link, by serial
     *                 number, or {@code null} to take the parser's
     * @param startMicros the start of the link on the presentation timeline
     * @param tracks the tracks of the link
     */
    private record Span(OggParser.Link link, long end, Map<Integer, Long> granules, long startMicros,
                        List<Track> tracks) {
        /**
         * {@return a copy with the start time and tracks set}
         *
         * @param startMicros the start of the link
         * @param tracks the tracks of the link
         */
        Span with(long startMicros, List<Track> tracks) {
            return new Span(link, end, granules, startMicros, tracks);
        }
    }

    /**
     * Constructs a demuxer over {@code source} with {@link DemuxerOptions#defaults()}.
     *
     * @param source the input, not closed by this demuxer
     * @throws MediaException.Malformed if no logical stream is found or a header is invalid
     * @throws MediaException.Unsupported if a stream uses an unsupported header version
     * @throws MediaException.LimitExceeded if a stream exceeds the limits
     * @throws MediaException.Io if reading fails
     */
    public OggDemuxer(MediaSource source) {
        this(source, DemuxerOptions.defaults());
    }

    /**
     * Constructs a demuxer over {@code source} that enforces the limits of {@code options}.
     *
     * @param source the input, not closed by this demuxer
     * @param options the demuxing options
     * @throws MediaException.Malformed if no logical stream is found or a header is invalid
     * @throws MediaException.Unsupported if a stream uses an unsupported header version
     * @throws MediaException.LimitExceeded if a stream exceeds the limits
     * @throws MediaException.Io if reading fails
     */
    public OggDemuxer(MediaSource source, DemuxerOptions options) {
        this(source, options.limits());
    }

    private OggDemuxer(MediaSource source, MediaLimits limits) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(limits, "limits");
        this.limits = limits;
        try {
            source = buffered(source);
            CachingSource cache = null;
            if (!SegmentBacked.isInMemory(source) && !ForwardOnlySource.isForwardOnly(source) && source.size() >= 0) {
                cache = new CachingSource(source, Math.min(MAX_CACHED_BYTES, limits.maxBytes()));
                source = cache;
            }
            this.parser = new OggParser(source, limits, false);
            while (!linkReady(parser) && parser.position() < MAX_HEADER_BYTES && parser.pump()) {
                // the headers of the first link are consumed as the pages arrive
            }
            if (parser.streams.isEmpty()) {
                throw new MediaException.Malformed("no Ogg logical stream found");
            }
            if (cache != null) {
                cache.start(parser.position());
            }
            for (var stream : parser.streams) {
                if (stream.link == 0 && stream.mapping != null && !stream.headersDone) {
                    throw new MediaException.Malformed("incomplete headers in Ogg logical stream " + stream.index);
                }
            }
            var size = source.size();
            var firstLink = parser.link(0);
            var found = new ArrayList<Span>();
            var known = size >= 0;
            var reachable = ForwardOnlySource.isCheapToReach(source, size);
            if (size >= 0 && size <= MAX_SCANNED_BYTES && reachable) {
                var tail = settledTail(source, size);
                if (tail != null) {
                    var granules = new HashMap<Integer, Long>();
                    for (var stream : firstLink.streams) {
                        granules.put(stream.serial, tail.granules().getOrDefault(stream.serial, stream.lastGranule));
                    }
                    found.add(new Span(firstLink, size, granules, 0, List.of()));
                    trust(tail.pages(), tail.pageCount());
                } else {
                    var scanner = scan(source, limits);
                    for (var index = 0; index < scanner.links.size(); index++) {
                        var end = index + 1 < scanner.links.size() ? scanner.links.get(index + 1).start : size;
                        found.add(new Span(scanner.links.get(index), end, null, 0, List.of()));
                    }
                    trust(scanner.recordedPages(), scanner.recordedCount());
                }
            } else if (size > MAX_SCANNED_BYTES && reachable) {
                var serials = serials(firstLink);
                var tail = tail(source, size, 0, serials, serials, false);
                if (tail != null) {
                    found.add(new Span(firstLink, size, tail.granules(), 0, List.of()));
                    trust(tail.pages(), tail.pageCount());
                } else {
                    found.addAll(discover(source, size, firstLink));
                }
            } else {
                found.add(new Span(firstLink, -1, null, 0, List.of()));
            }
            var result = new ArrayList<Track>();
            var ids = new HashSet<Integer>();
            var bases = new HashMap<Integer, Rational>();
            var tags = new HashMap<Integer, Map<String, String>>();
            var durations = new long[found.size()];
            var linkTracks = new ArrayList<List<Track>>();
            for (var index = 0; index < found.size(); index++) {
                var span = found.get(index);
                var spanTracks = new ArrayList<Track>();
                var linkDuration = -1L;
                for (var stream : span.link().streams) {
                    if (stream.mapping == null || !stream.headersDone) {
                        continue;
                    }
                    var lastGranule = span.granules() == null ? stream.lastGranule
                            : span.granules().getOrDefault(stream.serial, -1L);
                    var duration = -1L;
                    var skip = 0L;
                    if (known && stream.mapping.timestamped() && stream.anchored && lastGranule != -1) {
                        var end = stream.mapping.granuleToTime(lastGranule);
                        var start = stream.firstTimestamp + stream.mapping.decoderDelay();
                        skip = Math.max(0, -start);
                        duration = end - Math.max(0, start);
                        if (duration < 0) {
                            duration = -1;
                            skip = 0;
                        } else {
                            linkDuration = Math.max(linkDuration, stream.mapping.timeBase().toMicros(end));
                        }
                    }
                    var track = withLanguage(stream.mapping.track(stream.index, duration, skip), stream.skeletonLanguage());
                    spanTracks.add(track);
                    result.add(track);
                    ids.add(stream.index);
                    bases.put(stream.index, track.timeBase());
                    tags.put(stream.index, Collections.unmodifiableMap(stream.trackTags()));
                }
                durations[index] = linkDuration;
                linkTracks.add(spanTracks);
            }
            if (result.isEmpty()) {
                throw new MediaException.Malformed("no complete Ogg logical stream found");
            }
            var starts = new long[found.size()];
            for (var index = 1; index < found.size(); index++) {
                starts[index] = starts[index - 1] + Math.max(0, durations[index - 1]);
            }
            var total = starts[found.size() - 1] + Math.max(0, durations[found.size() - 1]);
            this.durationMicros = known && total > 0 ? total : -1;
            var planStarts = new long[found.size()];
            var planIndexes = new int[found.size()];
            var streamCount = 0;
            var spans = new ArrayList<Span>();
            var described = new ArrayList<OggLink>();
            var allChapters = new ArrayList<Chapter>();
            for (var index = 0; index < found.size(); index++) {
                var span = found.get(index).with(starts[index], List.copyOf(linkTracks.get(index)));
                spans.add(span);
                planStarts[index] = span.link().start;
                planIndexes[index] = span.link().streams.getFirst().index;
                streamCount = Math.max(streamCount, span.link().streams.getLast().index + 1);
                var linkDuration = known ? durations[index] : -1;
                var linkChapters = new ArrayList<Chapter>();
                for (var chapter : span.link().annotations.chapters(linkDuration)) {
                    linkChapters.add(Chapter.of(chapter.startMicros() + starts[index], chapter.endMicros() + starts[index],
                            chapter.title().orElse(null)));
                }
                allChapters.addAll(linkChapters);
                var trackList = new ArrayList<Integer>();
                for (var track : span.tracks()) {
                    trackList.add(track.id());
                }
                described.add(new OggLink(starts[index], linkDuration, trackList, span.link().metadata(), linkChapters,
                        span.link().annotations.pictures()));
            }
            this.plan = new OggParser.Plan(planStarts, planIndexes, streamCount);
            this.linkStarts = starts;
            parser.setPlan(plan);
            parser.setLinkStarts(starts);
            if (trustedPages != null) {
                parser.trustPages(trustedPages, trustedCount);
            }
            this.spans = List.copyOf(spans);
            this.links = List.copyOf(described);
            this.tracks = Collections.unmodifiableList(result);
            this.trackIds = ids;
            this.timeBases = bases;
            this.trackTags = tags;
            this.metadata = links.getFirst().metadata();
            this.chapters = List.copyOf(allChapters);
            this.pictures = links.getFirst().pictures();
            this.input = source;
            this.size = size;
            this.forwardOnly = ForwardOnlySource.isForwardOnly(source);
            if (cache != null) {
                cache.seal();
            }
        } catch (MediaException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new MediaException.Malformed("invalid Ogg file", exception);
        }
    }

    /**
     * Gives a track the language of its Ogg Skeleton description.
     *
     * @param track the track as its mapping describes it
     * @param language the BCP 47 tag of the {@code fisbone} {@code Language} field, or {@code null}
     * @return the track with its language set, or {@code track} itself when there is none
     */
    private static Track withLanguage(Track track, String language) {
        if (language == null) {
            return track;
        }
        var code = Languages.bibliographicCode(language.strip());
        if (code.equals("und")) {
            return track;
        }
        return switch (track) {
            case AudioTrack audio -> audio.toBuilder().language(code).build();
            case VideoTrack video -> video.toBuilder().language(code).build();
            case DataTrack data -> data.toBuilder().language(code).build();
        };
    }

    /**
     * Remembers the pages whose CRC every parser may skip.
     *
     * @param offsets the increasing offsets of pages a reader over the input accepted, or {@code null}
     * @param count the number of valid entries
     */
    private void trust(long[] offsets, int count) {
        this.trustedPages = offsets;
        this.trustedCount = count;
    }

    /**
     * Reads a small source that is not in memory whole, so that it is parsed in place.
     *
     * @param source the file
     * @return an in-memory source over the file's bytes, or {@code source} itself when it is in
     *         memory, larger than {@value #MAX_BUFFERED_BYTES} bytes, or of unknown size
     */
    private static MediaSource buffered(MediaSource source) {
        var size = source.size();
        if (size < 0 || size > MAX_BUFFERED_BYTES || SegmentBacked.isInMemory(source)) {
            return source;
        }
        var bytes = new byte[(int) size];
        var length = ForwardOnlySource.readOnce(source, 0, bytes, 0, bytes.length);
        return MediaSource.of(bytes, 0, length);
    }

    /**
     * Returns whether the streams of the first link a parser reads are all described and anchored.
     *
     * @param reader a parser started at the first page of a link
     * @return {@code true} once pumping more pages is unnecessary to describe the link
     */
    private static boolean linkReady(OggParser reader) {
        if (reader.link > reader.firstLink || reader.exhausted) {
            return true;
        }
        if (!reader.linkHeadersDone) {
            return false;
        }
        for (var stream : reader.streams) {
            if (stream.mapping == null && !stream.ended) {
                return false;
            }
            if (stream.mapping != null && (!stream.headersDone || stream.mapping.timestamped() && !stream.anchored && !stream.ended)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Scans the whole file to discover every link and the last granule position of every stream.
     *
     * <p>The scanner records the pages it accepts, which every parser of the demux then trusts.
     *
     * @param source the file
     * @param limits the resource bounds
     * @return the scanner, whose links are every link of the file
     */
    private static OggParser scan(MediaSource source, MediaLimits limits) {
        var scanner = new OggParser(source, limits, true);
        scanner.recordPages();
        while (scanner.pump()) {
            // pages are only examined for their headers and granule positions
        }
        return scanner;
    }

    /**
     * Finds the links of a large chained file by bisection.
     *
     * <p>Starting from the first link, each link ends at the first page after its headers whose
     * serial number none of its streams has (or that begins a stream); the headers of the next
     * link are read there, until the pages near the end of the file carry only serial numbers of
     * the current link. The last granule positions of each link come from its own last pages.
     *
     * @param source the file
     * @param size the file size
     * @param first the first link, as the demux parser read it
     * @return every link found, in file order
     */
    private List<Span> discover(MediaSource source, long size, OggParser.Link first) {
        var found = new ArrayList<Span>();
        var link = first;
        while (true) {
            var serials = serials(link);
            var wanted = timestamped(link);
            var last = tail(source, size, link.dataStart, serials, wanted, false);
            if (last != null) {
                found.add(new Span(link, size, last.granules(), 0, List.of()));
                return found;
            }
            // the data of an unknown codec starts on its first page, so the link's own beginning-of-stream pages are
            // skipped explicitly
            var boundary = boundary(source, Math.max(link.dataStart, link.beginningsEnd), size, serials);
            if (boundary < 0) {
                found.add(new Span(link, size, Map.of(), 0, List.of()));
                return found;
            }
            var own = tail(source, boundary, link.dataStart, serials, wanted, false);
            found.add(new Span(link, boundary, own == null ? Map.of() : own.granules(), 0, List.of()));
            var nextIndex = link.streams.getLast().index + 1;
            var headers = new OggParser(source, limits, true, boundary, nextIndex, link.index + 1);
            while (!linkReady(headers) && headers.position() - boundary < MAX_HEADER_BYTES && headers.pump()) {
                // the headers of the next link are consumed as the pages arrive
            }
            var next = headers.link(link.index + 1);
            if (next == null || next.streams.isEmpty()) {
                return found;
            }
            for (var stream : next.streams) {
                if (stream.mapping != null && !stream.headersDone) {
                    throw new MediaException.Malformed("incomplete headers in Ogg logical stream " + stream.index);
                }
            }
            link = next;
        }
    }

    /**
     * Finds where a link ends: the first page at or after an offset that belongs to no stream of
     * the link or begins a stream.
     *
     * @param source the file
     * @param low the offset of a page of the link after its beginning-of-stream pages
     * @param high the offset where the search stops
     * @param serials the serial numbers of the link's streams
     * @return the offset of that page, or {@code -1} when every page up to {@code high} belongs to the link
     */
    private static long boundary(MediaSource source, long low, long high, Set<Integer> serials) {
        var foreign = -1L;
        while (high - low > BISECTION_STOP) {
            var middle = low + (high - low) / 2;
            var reader = new OggPageReader(source, middle, high);
            if (!reader.next()) {
                high = middle;
                continue;
            }
            if (serials.contains(reader.serial) && (reader.flags & OggPageReader.FLAG_BOS) == 0) {
                low = reader.position();
            } else {
                foreign = reader.pageOffset;
                high = reader.pageOffset;
            }
        }
        var reader = new OggPageReader(source, low, foreign >= 0 ? foreign + 1 : high);
        while (reader.next()) {
            if (!serials.contains(reader.serial) || (reader.flags & OggPageReader.FLAG_BOS) != 0) {
                return reader.pageOffset;
            }
        }
        return foreign;
    }

    /**
     * Holds what a search of the last pages of a file found.
     *
     * @param granules  the last granule position of every wanted stream found, keyed by serial
     *                  number
     * @param pages     the increasing offsets of the pages the search accepted, whose CRC
     *                  {@link #parser} can then skip, in the first {@code pageCount} entries
     * @param pageCount the number of valid entries in {@code pages}
     */
    private record Tail(Map<Integer, Long> granules, long[] pages, int pageCount) {
    }

    /**
     * Returns the serial numbers of a link's streams.
     *
     * @param link a described link
     * @return the serial numbers
     */
    private static Set<Integer> serials(OggParser.Link link) {
        var serials = new HashSet<Integer>();
        for (var stream : link.streams) {
            serials.add(stream.serial);
        }
        return serials;
    }

    /**
     * Returns the serial numbers of a link's timestamped streams.
     *
     * @param link a described link
     * @return the serial numbers
     */
    private static Set<Integer> timestamped(OggParser.Link link) {
        var serials = new HashSet<Integer>();
        for (var stream : link.streams) {
            if (stream.mapping != null && stream.mapping.timestamped()) {
                serials.add(stream.serial);
            }
        }
        return serials;
    }

    /**
     * Finds the last granule position of every timestamped stream of a file whose only link is
     * the first, without reading the pages between the headers and the end.
     *
     * <p>The result is what a scan of the whole file would record, provided the file follows
     * RFC 3533. It is {@code null}, and the file must be scanned, unless the headers were read
     * without meeting a second link, every stream of the first link is described and anchored (so
     * later pages only move its last granule position), no two streams share a serial number, and
     * the pages from the end back to the last page of every timestamped stream, which must lie
     * within {@value #MAX_SETTLED_TAIL_BYTES} bytes of the end, carry only first-link serial
     * numbers and never begin a stream. The search stops at the end of the headers, where
     * {@link #parser} stopped; a stream that has no page with a granule position after that point
     * keeps the parser's last granule position, which is then final, and so does every stream when
     * the parser already reached the end of the file.
     *
     * @param source the file
     * @param size   the file size
     * @return the last granule positions found after the headers and the pages accepted on the
     *         way, or {@code null} when the file must be scanned
     */
    private Tail settledTail(MediaSource source, long size) {
        if (parser.link != 0) {
            return null;
        }
        if (parser.exhausted) {
            return new Tail(Map.of(), new long[0], 0);
        }
        var serials = new HashSet<Integer>();
        var wanted = new HashSet<Integer>();
        for (var stream : parser.streams) {
            var mapping = stream.mapping;
            if (mapping == null || !stream.headersDone || mapping.timestamped() && !stream.anchored
                    || !serials.add(stream.serial)) {
                return null;
            }
            if (mapping.timestamped()) {
                wanted.add(stream.serial);
            }
        }
        return tail(source, size, parser.position(), serials, wanted, true);
    }

    /**
     * Finds the last granule position of streams from the end of a file or link.
     *
     * <p>The range is examined in chunks taken backwards from its end, each read by a page reader
     * that resynchronizes from the chunk's start and returns the pages starting inside the chunk,
     * until a page was found and every wanted stream has a page with a granule position, the
     * chunks reach {@code floor}, or the search limit is reached. The pages of the last chunk that
     * holds any page are therefore always checked against {@code serials}, even when no stream is
     * wanted. A lenient search uses chunks of {@value #TAIL_CHUNK} bytes and examines at most
     * {@value #MAX_TAIL_BYTES} bytes; a strict one doubles its chunks from {@value #TAIL_CHUNK}
     * bytes and examines at most {@value #MAX_SETTLED_TAIL_BYTES} bytes. The offsets of the
     * accepted pages of at least {@value OggPageReader#MIN_RECORDED_PAGE} bytes are returned too,
     * since their CRC is known to match.
     *
     * @param source  the file
     * @param size    the offset where the range ends
     * @param floor   the offset of a page boundary below which nothing is examined
     * @param serials the serial numbers the pages may carry
     * @param wanted  the serial numbers whose last granule position is wanted
     * @param strict  whether a page that begins a stream, or reaching the search limit before
     *                every wanted stream is found, makes the search fail
     * @return the last granule position of every wanted stream found and the accepted pages, or
     *         {@code null} when a page carries a serial number outside {@code serials} or a strict
     *         search fails
     */
    private static Tail tail(MediaSource source, long size, long floor, Set<Integer> serials, Set<Integer> wanted,
                             boolean strict) {
        var granules = new HashMap<Integer, Long>();
        var chunks = new ArrayList<OggPageReader>();
        var end = size;
        var found = false;
        var chunkSize = (long) TAIL_CHUNK;
        while (end > floor && (!found || granules.size() < wanted.size())) {
            if (size - end >= (strict ? MAX_SETTLED_TAIL_BYTES : MAX_TAIL_BYTES)) {
                if (strict) {
                    return null;
                }
                break;
            }
            var start = Math.max(floor, end - chunkSize);
            if (strict) {
                chunkSize *= 2;
            }
            var reader = new OggPageReader(source, start, end);
            reader.recordPages();
            chunks.add(reader);
            var chunk = new HashMap<Integer, Long>();
            while (reader.next()) {
                if (!serials.contains(reader.serial) || strict && (reader.flags & OggPageReader.FLAG_BOS) != 0) {
                    return null;
                }
                found = true;
                if (reader.granule != -1 && wanted.contains(reader.serial)) {
                    chunk.put(reader.serial, reader.granule);
                }
            }
            for (var entry : chunk.entrySet()) {
                granules.putIfAbsent(entry.getKey(), entry.getValue());
            }
            end = start;
        }
        var count = 0;
        for (var reader : chunks) {
            count += reader.recordedCount();
        }
        var pages = new long[count];
        var filled = 0;
        for (var index = chunks.size() - 1; index >= 0; index--) {
            var reader = chunks.get(index);
            System.arraycopy(reader.recordedPages(), 0, pages, filled, reader.recordedCount());
            filled += reader.recordedCount();
        }
        return new Tail(granules, pages, count);
    }

    @Override
    public ContainerFormat format() {
        return ContainerFormat.OGG;
    }

    @Override
    public List<Track> tracks() {
        return tracks;
    }

    @Override
    public Map<String, String> metadata() {
        return metadata;
    }

    /**
     * {@inheritDoc}
     *
     * <p>The tags of an Ogg track are its stream's comment header, without the chapter and picture
     * fields, followed by the message header fields of its Ogg Skeleton {@code fisbone} that the
     * comments do not name.
     */
    @Override
    public Map<String, String> trackMetadata(int trackId) {
        return trackTags.getOrDefault(trackId, Map.of());
    }

    @Override
    public long durationMicros() {
        return durationMicros;
    }

    @Override
    public List<Chapter> chapters() {
        return chapters;
    }

    @Override
    public List<AttachedPicture> attachedPictures() {
        return pictures;
    }

    /**
     * Returns the links of the file, in file order.
     *
     * <p>A file that is not chained has one link. Only the first link is described when the source
     * size is unknown, or when the source reads its input once and the whole input does not fit its
     * memory budget.
     *
     * @return the links; unmodifiable and never empty
     */
    public List<OggLink> links() {
        return links;
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
            throw new MediaException.Malformed("invalid Ogg page", exception);
        }
    }

    /**
     * Returns the next packet of a described track in file order, without the seek filter.
     *
     * @return the packet, or {@code null} at the end of the input
     */
    private Packet nextPacket() {
        while (true) {
            var packet = parser.output.poll();
            if (packet != null) {
                if (trackIds.contains(packet.trackId())) {
                    if (seeksByReading() && packet.pts() != Packet.NO_TIMESTAMP) {
                        var end = packet.pts() + Math.max(0, packet.duration());
                        producedMicros = Math.max(producedMicros, timeBases.get(packet.trackId()).toMicros(end));
                    }
                    if (!passedTracks.contains(packet.trackId())) {
                        return packet;
                    }
                }
                continue;
            }
            if (!parser.pump() && parser.output.isEmpty()) {
                return null;
            }
            ForwardOnlySource.release(input, parser.position());
        }
    }

    /**
     * Returns whether a seek reads its way to the target instead of bisecting the file.
     *
     * @return {@code true} for a source of unknown size or one that reads its input once
     */
    private boolean seeksByReading() {
        return size < 0 || forwardOnly;
    }

    /**
     * {@inheritDoc}
     *
     * @return {@code true} when a stream is timestamped and the source has random access; a source
     *         that reads its input once seeks only forward and reports {@code false}
     */
    @Override
    public boolean isSeekable() {
        return !forwardOnly && hasTimestamps();
    }

    /**
     * Returns whether a described stream is timestamped.
     *
     * @return {@code true} when a track can be positioned in time
     */
    private boolean hasTimestamps() {
        for (var span : spans) {
            for (var stream : span.link().streams) {
                if (trackIds.contains(stream.index) && stream.mapping.timestamped()) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * {@inheritDoc}
     *
     * <p>On a source that reads its input once, a target at or past the end of every packet read
     * so far is reached by reading on, and a track whose key frame before the target was already
     * read restarts at its next key frame.
     *
     * @throws MediaException.Unsupported if no stream is timestamped, or the source reads its input
     *                                    once and the target precedes the end of the packets read
     */
    @Override
    public void seek(long micros) {
        if (!hasTimestamps()) {
            throw new MediaException.Unsupported("seek", "this Ogg file cannot seek: no stream is timestamped");
        }
        try {
            if (seeksByReading()) {
                seekByReading(micros);
                return;
            }
            var span = spans.get(linkAt(micros));
            var position = span.end();
            var located = false;
            for (var stream : span.link().streams) {
                if (!trackIds.contains(stream.index) || !stream.mapping.timestamped()) {
                    continue;
                }
                var mapping = stream.mapping;
                var target = SeekTimes.floor(micros, mapping.timeBase())
                        - Rational.MICROSECONDS.rescale(span.startMicros(), mapping.timeBase());
                var page = locatePage(span, stream, target);
                if (page[1] != -1) {
                    var keyframe = mapping.keyframeTime(page[1]);
                    if (keyframe < mapping.granuleToTime(page[1])) {
                        page = locatePage(span, stream, keyframe);
                    }
                }
                position = Math.min(position, page[0]);
                located = true;
            }
            if (!located) {
                position = span.link().dataStart;
            }
            var replacement = parserAt(span);
            if (position > span.link().dataStart) {
                replacement.reposition(position);
            }
            parser = replacement;
            filter = new SeekFilter(span.tracks(), micros);
            passedTracks = Set.of();
        } catch (MediaException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new MediaException.Malformed("invalid Ogg page", exception);
        }
    }

    /**
     * Seeks a source of unknown size, or one that reads its input once, by reading.
     *
     * @param micros the target
     * @throws MediaException.Unsupported if the source reads its input once and the target precedes
     *                                    the end of the packets read
     */
    private void seekByReading(long micros) {
        if (micros < producedMicros) {
            if (forwardOnly) {
                throw new MediaException.Unsupported("seek", "an Ogg stream that is read once cannot seek backward");
            }
            parser = newParser(spans.getFirst());
            producedMicros = Long.MIN_VALUE;
        }
        var index = linkAt(micros);
        var kept = new ArrayList<Track>();
        var passed = new HashSet<Integer>();
        for (var link = 0; link < spans.size(); link++) {
            for (var track : spans.get(link).tracks()) {
                if (link < index) {
                    passed.add(track.id());
                } else {
                    kept.add(track);
                }
            }
        }
        filter = new SeekFilter(kept, micros);
        passedTracks = passed;
    }

    /**
     * Returns the link that plays at a time: the last whose start is at or before it, or the first.
     *
     * @param micros the time
     * @return the index of the link in {@link #spans}
     */
    private int linkAt(long micros) {
        var index = 0;
        for (var candidate = 1; candidate < spans.size(); candidate++) {
            if (spans.get(candidate).startMicros() <= micros) {
                index = candidate;
            }
        }
        return index;
    }

    /**
     * Creates a parser that has read the headers of a link and stands at its first data page.
     *
     * @param span the link
     * @return the parser
     */
    private OggParser parserAt(Span span) {
        var created = newParser(span);
        while (created.position() < span.link().dataStart && created.pump()) {
            // the link's header packets are consumed again, which resets every mapping
        }
        created.output.clear();
        return created;
    }

    /**
     * Creates a parser standing at the first page of a link, numbering links and streams as the
     * parser of the construction did.
     *
     * @param span the link
     * @return the parser
     */
    private OggParser newParser(Span span) {
        var link = span.link();
        var created = new OggParser(input, limits, false, link.start, link.streams.getFirst().index, link.index);
        created.setPlan(plan);
        created.setLinkStarts(linkStarts);
        if (trustedPages != null) {
            created.trustPages(trustedPages, trustedCount);
        }
        return created;
    }

    /**
     * Finds the last page of a stream whose granule position maps to a time at or before a target.
     *
     * @param span the link of the stream
     * @param stream a timestamped stream of the link
     * @param target the target in the stream's time base, without the link's offset
     * @return {offset, granule position} of that page, or {offset of the link's first data page,
     *         {@code -1}} when every granule position is later
     */
    private long[] locatePage(Span span, OggParser.Stream stream, long target) {
        var mapping = stream.mapping;
        var start = span.link().dataStart;
        var end = span.end();
        var low = start;
        var high = end;
        while (high - low > BISECTION_STOP) {
            var middle = low + (high - low) / 2;
            var page = granulePage(stream.serial, middle, high, end);
            if (page == null || mapping.granuleToTime(page[1]) > target) {
                high = middle;
            } else {
                low = page[0];
            }
        }
        var best = new long[]{start, -1};
        var reader = new OggPageReader(input, low, end);
        while (reader.next()) {
            if (reader.serial != stream.serial || reader.granule == -1) {
                continue;
            }
            if (mapping.granuleToTime(reader.granule) > target) {
                break;
            }
            best[0] = reader.pageOffset;
            best[1] = reader.granule;
        }
        return best;
    }

    /**
     * Finds the first page of a stream with a granule position that starts at or after an offset
     * and before a limit.
     *
     * @param serial the serial number of the stream
     * @param from  the offset where the search starts
     * @param limit the offset the page must start before
     * @param end the offset where the stream's link ends
     * @return {offset, granule position}, or {@code null} when there is none
     */
    private long[] granulePage(int serial, long from, long limit, long end) {
        var reader = new OggPageReader(input, from, Math.min(end, limit + OggPageReader.MAX_PAGE_SIZE));
        while (reader.next()) {
            if (reader.pageOffset >= limit) {
                return null;
            }
            if (reader.serial == serial && reader.granule != -1) {
                return new long[]{reader.pageOffset, reader.granule};
            }
        }
        return null;
    }
}
