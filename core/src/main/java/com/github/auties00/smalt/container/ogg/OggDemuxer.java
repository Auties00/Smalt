package com.github.auties00.smalt.container.ogg;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.container.DemuxerOptions;
import com.github.auties00.smalt.container.AttachedPicture;
import com.github.auties00.smalt.container.Chapter;
import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.container.Demuxer;
import com.github.auties00.smalt.container.internal.SeekFilter;
import com.github.auties00.smalt.container.internal.SeekTimes;
import com.github.auties00.smalt.internal.io.ForwardOnlySource;
import com.github.auties00.smalt.internal.io.SegmentBacked;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Track;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Reads Ogg files (RFC 3533) carrying Opus, Vorbis or FLAC audio.
 *
 * <p>Every logical stream becomes a track whose identifier is the stream's index in
 * beginning-of-stream order. Opus (RFC 7845), Vorbis and FLAC streams are fully mapped: their
 * header packets become the track's codec configuration (the {@code OpusHead} packet; the three
 * Vorbis headers Xiph-laced; {@code fLaC} plus the FLAC metadata blocks) and their comment
 * headers become {@link #metadata()}; header packets are never returned by
 * {@link #read()}. Speex and Theora streams are recognized and reported with
 * {@code UNKNOWN_AUDIO} and {@code UNKNOWN_VIDEO} tracks, and Skeleton or unknown streams with
 * {@link com.github.auties00.smalt.track.DataTrack}s; their packets are returned untouched
 * and without timestamps.
 *
 * <p>Timestamps are in samples of each track's time base and follow the granule positions: a
 * stream's first packets are anchored backwards from the first granule position, later packets
 * advance by their durations, and the last packet on the end-of-stream page is shortened to end
 * at that page's granule position (end trimming), the samples it loses being reported as its
 * {@link com.github.auties00.smalt.track.PacketSideData#discardEnd()}. For Opus the pre-skip is subtracted, so the
 * first packet of a stream starting at granule zero is stamped {@code -preSkip} and the decoder's
 * own pre-skip removal makes output start at zero; the first Vorbis packet, which decodes to no
 * output, is stamped to end at zero. A track's {@code skipSamples} is non-zero only when a stream
 * starts before granule zero, and its {@code duration} is the exact number of samples a reader
 * keeps after the decoder's own priming removal and {@code skipSamples}.
 *
 * <p>Pages that fail their CRC, are truncated, or are separated by garbage are skipped by
 * searching for the next capture pattern; a lost page drops the packet it carried a piece of.
 * Grouped (multiplexed) streams are interleaved in file order. Chained streams, where a new set
 * of beginning-of-stream pages follows the end of the previous link, are exposed sequentially:
 * each link's streams are separate tracks, returned after the previous link's packets, and their
 * timestamps start where the previous link ended, so the concatenated presentation is
 * continuous. {@link #durationMicros()} spans every link.
 *
 * <p>Track durations come from the last granule position of every stream. Construction reads the
 * first link's headers and then the last pages of the file, from its end back to the last page of
 * every timestamped stream (at least the last 64 KiB, or everything after the headers when that is
 * shorter), so the pages in between are read once, by {@link #read()}. A file of up to
 * 64 MiB is scanned completely at construction instead, which discovers every chained link, when
 * those last pages do not settle the description: when one of them begins a stream or belongs to
 * a stream that did not start in the first link, when a stream of the first link is not described
 * and anchored by its first pages, when two of its streams share a serial number, or when the last
 * page of a timestamped stream is not within the last 960 KiB. A larger file is scanned only when
 * a page near the end belongs to a stream that did not start in the first link, and a stream whose
 * last page is not within its last 4 MiB has duration {@code -1}. When the source size is unknown
 * only the first link is described and durations are {@code -1}. A source of at most 256 KiB that
 * is not in memory is read whole at construction, in one pass, and parsed in place. A larger
 * source that is not in memory is read through a cache of the {@value #MAX_CACHED_BYTES} bytes
 * that follow the headers, which keeps whatever construction reads there after the headers (the
 * last pages of a smaller file, the pages of a full scan) until the demux reaches it, so those
 * bytes are read from the source once even when construction scans the file completely.
 *
 * <p>A chain whose later link reuses a serial number of the first, which RFC 3533 forbids, is
 * recognized only when that link begins within the pages examined near the end; otherwise it is
 * read as one link and the later link's packets are not returned.
 *
 * <p>The comment headers of the first link also give {@link #chapters()} ({@code CHAPTERxxx} and
 * {@code CHAPTERxxxNAME} fields, which are then left out of {@link #metadata()}) and
 * {@link #attachedPictures()} ({@code METADATA_BLOCK_PICTURE} fields).
 *
 * <p>A file of known size with a single link can seek. {@link #seek(long)} bisects the file on the
 * granule positions of the first timestamped stream to the last of its pages whose granule
 * position is at or before the target, continues reading there, and lets every track restart at
 * its last packet at or before the target, as {@link SeekFilter} describes. The packets of the
 * first page read after a seek are stamped back from that page's granule position, so their
 * timestamps are exact, except for the first Vorbis packet, whose duration depends on the block
 * size of a packet that was not read.
 *
 * <p>A source that reads its input once, front to back (a stream), is told after every page which
 * bytes are no longer needed. The last pages that give the duration, and the scan for chained
 * links, are read only when the whole input fits the source's memory budget; otherwise the
 * duration is unknown and only the first link is described.
 *
 * @implNote This implementation relies on RFC 3533 section 4, under which every chained logical
 * bitstream has a serial number unique within the physical bitstream: when the pages near the end
 * carry only first-link serial numbers and none begins a stream, the last link is the first, so no
 * other link lies between the headers and the end. The last pages are found the way libopusfile's
 * {@code op_get_prev_page_serial} (BSD-3) finds them, by resynchronizing forward from chunk
 * boundaries taken backwards from the end, the chunks doubling from 64 KiB; the boundary closest
 * to the start is the end of the headers, where a full scan would be at the same point. The pages
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
     * The byte range below which a seek stops bisecting and reads the pages in between.
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
     * The largest prefix read while collecting the headers of the first link.
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
     * The parser producing the packets.
     */
    private final OggParser parser;

    /**
     * The tracks, in stream order.
     */
    private final List<Track> tracks;

    /**
     * The identifiers of {@link #tracks}.
     */
    private final Set<Integer> trackIds;

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
     * The stream whose granule positions locate a seek target, or {@code null} when the file
     * cannot seek.
     */
    private final OggParser.Stream primary;

    /**
     * The chapters of the first link.
     */
    private final List<Chapter> chapters;

    /**
     * The pictures of the first link.
     */
    private final List<AttachedPicture> pictures;

    /**
     * The filter trimming the packets read after a seek, or {@code null}.
     */
    private SeekFilter filter;

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
        try {
            source = buffered(source);
            CachingSource cache = null;
            if (!SegmentBacked.isInMemory(source) && !ForwardOnlySource.isForwardOnly(source) && source.size() >= 0) {
                cache = new CachingSource(source, Math.min(MAX_CACHED_BYTES, limits.maxBytes()));
                source = cache;
            }
            this.parser = new OggParser(source, limits, false);
            while (!firstLinkReady() && parser.position() < MAX_HEADER_BYTES && parser.pump()) {
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
            List<OggParser.Stream> described;
            Tail tail = null;
            var parserGranules = false;
            var reachable = ForwardOnlySource.isCheapToReach(source, size);
            if (size >= 0 && size <= MAX_SCANNED_BYTES && reachable) {
                tail = settledTail(source, size);
                if (tail != null) {
                    described = parser.streams;
                    parserGranules = true;
                } else {
                    described = scan(source, limits);
                }
            } else if (size > MAX_SCANNED_BYTES && reachable) {
                var serials = firstLinkSerials();
                tail = tail(source, size, 0, serials, serials, false);
                if (tail != null) {
                    described = parser.streams.stream().filter(stream -> stream.link == 0).toList();
                } else {
                    described = scan(source, limits);
                }
            } else {
                described = parser.streams.stream().filter(stream -> stream.link == 0).toList();
            }
            var links = 0;
            for (var stream : described) {
                links = Math.max(links, stream.link + 1);
            }
            var linkDurations = new long[links];
            var result = new ArrayList<Track>();
            var ids = new HashSet<Integer>();
            var known = size >= 0;
            for (var stream : described) {
                if (stream.mapping == null || !stream.headersDone) {
                    continue;
                }
                var lastGranule = tail == null ? stream.lastGranule
                        : tail.granules().getOrDefault(stream.serial, parserGranules ? stream.lastGranule : -1L);
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
                        var endMicros = stream.mapping.timeBase().toMicros(end);
                        linkDurations[stream.link] = Math.max(linkDurations[stream.link], endMicros);
                    }
                }
                result.add(stream.mapping.track(stream.index, duration, skip));
                ids.add(stream.index);
            }
            if (result.isEmpty()) {
                throw new MediaException.Malformed("no complete Ogg logical stream found");
            }
            var starts = new long[links];
            for (var i = 1; i < links; i++) {
                starts[i] = starts[i - 1] + linkDurations[i - 1];
            }
            parser.setLinkStarts(starts);
            if (tail != null) {
                parser.trustPages(tail.pages(), tail.pageCount());
            }
            var total = links == 0 ? 0 : starts[links - 1] + linkDurations[links - 1];
            this.durationMicros = known && total > 0 ? total : -1;
            this.tracks = Collections.unmodifiableList(result);
            this.trackIds = ids;
            this.metadata = Collections.unmodifiableMap(parser.metadata);
            this.chapters = parser.annotations.chapters(durationMicros);
            this.pictures = parser.annotations.pictures();
            this.input = source;
            this.size = size;
            OggParser.Stream seekStream = null;
            if (size >= 0 && links <= 1) {
                for (var track : result) {
                    var stream = parser.streams.get(track.id());
                    if (stream.link == 0 && stream.mapping != null && stream.mapping.timestamped()) {
                        seekStream = stream;
                        break;
                    }
                }
            }
            this.primary = seekStream;
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
     * Returns whether the first link's streams are all described and anchored.
     *
     * @return {@code true} once pumping more pages is unnecessary for construction
     */
    private boolean firstLinkReady() {
        if (parser.link > 0 || parser.exhausted) {
            return true;
        }
        if (!parser.linkHeadersDone) {
            return false;
        }
        for (var stream : parser.streams) {
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
     * <p>The pages the scan accepts become trusted by {@link #parser}, which then skips their
     * CRC when it reaches them.
     *
     * @param source the file
     * @param limits the resource bounds
     * @return every stream, in beginning-of-stream order
     */
    private List<OggParser.Stream> scan(MediaSource source, MediaLimits limits) {
        var scanner = new OggParser(source, limits, true);
        scanner.recordPages();
        while (scanner.pump()) {
            // pages are only examined for their headers and granule positions
        }
        parser.trustPages(scanner);
        return scanner.streams;
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
     * Returns the serial numbers of the first link's streams.
     *
     * @return the serial numbers
     */
    private Set<Integer> firstLinkSerials() {
        var serials = new HashSet<Integer>();
        for (var stream : parser.streams) {
            if (stream.link == 0) {
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
     * Finds the last granule position of streams from the end of a file.
     *
     * <p>The file is examined in chunks taken backwards from its end, each read by a page reader
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
     * @param size    the file size
     * @param floor   the offset of a page boundary below which nothing is examined
     * @param serials the serial numbers the pages may carry
     * @param wanted  the serial numbers whose last granule position is wanted
     * @param strict  whether a page that begins a stream, or reaching the search limit before
     *                every wanted stream is found, makes the search fail
     * @return the last granule position of every wanted stream found and the accepted pages, or
     *         {@code null} when a page carries a serial number outside {@code serials} or a strict
     *         search fails
     */
    private Tail tail(MediaSource source, long size, long floor, Set<Integer> serials, Set<Integer> wanted,
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
                    return packet;
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
     * {@inheritDoc}
     *
     * @return {@code true} for a file of known size with a single link and a timestamped stream
     */
    @Override
    public boolean isSeekable() {
        return primary != null;
    }

    /**
     * {@inheritDoc}
     *
     * @throws MediaException.Unsupported if the size of the source is unknown, the file is chained,
     *                                    or no stream is timestamped
     */
    @Override
    public void seek(long micros) {
        // TODO: seek in chained files and in files of unknown size
        if (primary == null) {
            throw new MediaException.Unsupported("seek", "this Ogg file cannot seek: its size is unknown, it is chained, "
                    + "or no stream is timestamped");
        }
        try {
            var target = SeekTimes.floor(micros, primary.mapping.timeBase());
            var position = locatePage(target);
            var replacement = new SeekFilter(tracks, micros);
            parser.reposition(position);
            filter = replacement;
        } catch (MediaException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new MediaException.Malformed("invalid Ogg page", exception);
        }
    }

    /**
     * Finds the page a seek resumes reading at: the last page of {@link #primary} whose granule
     * position maps to a time at or before the target.
     *
     * @param target the target in the time base of {@link #primary}
     * @return the offset of that page, or of the first data page when every granule position is
     *         later
     */
    private long locatePage(long target) {
        var low = parser.dataStart;
        var high = size;
        while (high - low > BISECTION_STOP) {
            var middle = low + (high - low) / 2;
            var page = granulePage(middle, high);
            if (page == null || page[1] > target) {
                high = middle;
            } else {
                low = page[0];
            }
        }
        var best = parser.dataStart;
        var reader = new OggPageReader(input, low, size);
        while (reader.next()) {
            if (reader.serial != primary.serial || reader.granule == -1) {
                continue;
            }
            if (primary.mapping.granuleToTime(reader.granule) > target) {
                break;
            }
            best = reader.pageOffset;
        }
        return best;
    }

    /**
     * Finds the first page of {@link #primary} with a granule position that starts at or after an
     * offset and before a limit.
     *
     * @param from  the offset where the search starts
     * @param limit the offset the page must start before
     * @return {offset, time of the granule position}, or {@code null} when there is none
     */
    private long[] granulePage(long from, long limit) {
        var reader = new OggPageReader(input, from, Math.min(size, limit + OggPageReader.MAX_PAGE_SIZE));
        while (reader.next()) {
            if (reader.pageOffset >= limit) {
                return null;
            }
            if (reader.serial == primary.serial && reader.granule != -1) {
                return new long[]{reader.pageOffset, primary.mapping.granuleToTime(reader.granule)};
            }
        }
        return null;
    }
}
