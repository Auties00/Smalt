package com.github.auties00.smalt.container.ogg;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.container.internal.Annotations;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.PacketSideData;
import com.github.auties00.smalt.track.Rational;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns the pages of an Ogg physical stream into the packets of its logical streams.
 *
 * <p>Logical streams are numbered in the order their beginning-of-stream pages appear, across
 * chained links, from the first index the parser is given; a link starts whenever a
 * beginning-of-stream page follows a page that is not one, and links are numbered from the first
 * link index the parser is given. A parser may start at the first page of any link, so that it
 * numbers that link's streams as a parser started at the beginning of the file does. Packets are
 * reassembled across pages following libogg's rules: a lost page (a gap in the page sequence
 * numbers) discards the packet in progress, and a continued page whose preceding part was lost has
 * its leading fragment dropped. Header packets feed the stream's
 * {@link OggMapping}; data packets are timestamped from the page granule positions.
 *
 * <p>Data packets are stamped backwards from the granule position of the page they end on: the
 * last packet completed on a page ends at the granule's time and earlier packets precede it by
 * their durations, so timestamps stay correct after a lost page. Packets of a stream's first pages
 * are held until a granule position arrives. The end-of-stream page is stamped forward instead,
 * because its granule position may carry end trimming: its last packet's duration is shortened so
 * it ends at the granule's time, and a stream whose first data page is also its last starts at
 * granule zero. Packets whose mapping gives them no duration are stamped with the time of the
 * granule position of the page they end on, the end-of-stream page included.
 *
 * <p>In scan mode data packets are not copied, except for mappings that observe them (Ogg
 * Skeleton); the parser only records what the demuxer needs to describe the tracks and links:
 * headers, comments, the first-packet anchor and the last granule position of every stream, and
 * where every link starts.
 */
final class OggParser {
    /**
     * The largest number of logical streams accepted in one physical stream.
     */
    private static final int MAX_STREAMS = 1024;

    /**
     * The page reader.
     */
    private final OggPageReader reader;

    /**
     * The resource bounds.
     */
    private final MediaLimits limits;

    /**
     * Whether the parser only scans, without producing packets.
     */
    private final boolean scan;

    /**
     * Every logical stream seen, in beginning-of-stream order.
     */
    final List<Stream> streams = new ArrayList<>();

    /**
     * The stream currently bound to each serial number.
     */
    private final Map<Integer, Stream> active = new HashMap<>();

    /**
     * The completed data packets, in file order.
     */
    final ArrayDeque<Packet> output = new ArrayDeque<>();

    /**
     * Every link seen, in file order.
     */
    final List<Link> links = new ArrayList<>();

    /**
     * The streams by index.
     */
    private final Map<Integer, Stream> byIndex = new HashMap<>();

    /**
     * The index the next stream gets.
     */
    private int nextIndex;

    /**
     * The index of the first link this parser reads.
     */
    final int firstLink;

    /**
     * The links whose numbering is fixed, or {@code null}.
     */
    private Plan plan;

    /**
     * The index the next stream of a link outside {@link #plan} gets.
     */
    private int unplannedIndex;

    /**
     * The number of links outside {@link #plan} seen.
     */
    private int unplannedLinks;

    /**
     * The start time of every link in microseconds, used to offset chained links, or
     * {@code null} before it is known.
     */
    private long[] linkStarts;

    /**
     * The index of the current link.
     */
    int link;

    /**
     * Whether a page that is not a beginning-of-stream page was seen in the current link.
     */
    boolean linkHeadersDone;

    /**
     * Whether the end of the input was reached.
     */
    boolean exhausted;

    /**
     * Constructs a parser reading from the start of a source.
     *
     * @param source the Ogg input
     * @param limits the resource bounds
     * @param scan whether to only scan
     */
    OggParser(MediaSource source, MediaLimits limits, boolean scan) {
        this(source, limits, scan, 0, 0, 0);
    }

    /**
     * Constructs a parser reading from the first page of a link.
     *
     * @param source the Ogg input
     * @param limits the resource bounds
     * @param scan whether to only scan
     * @param start the offset of the link's first beginning-of-stream page, or of bytes before it
     * @param firstIndex the index of the link's first stream
     * @param firstLink the index of the link
     */
    OggParser(MediaSource source, MediaLimits limits, boolean scan, long start, int firstIndex, int firstLink) {
        this.reader = new OggPageReader(source, start);
        this.limits = limits;
        this.scan = scan;
        this.nextIndex = firstIndex;
        this.firstLink = firstLink;
        this.link = firstLink;
    }

    /**
     * Holds the numbering of the links a demuxer described.
     *
     * @param starts the offset of the first page of every link, in link order
     * @param firstIndexes the index of the first stream of every link
     * @param streamCount the number of streams of every described link together
     */
    record Plan(long[] starts, int[] firstIndexes, int streamCount) {
    }

    /**
     * Fixes the numbering of links and streams: a link starting at one of the plan's offsets gets
     * the plan's link index and stream indices, and any other link gets a link index past the
     * plan's links and stream indices from the plan's stream count on, which no described track
     * uses.
     *
     * @param plan the numbering, applied to the links met from now on
     */
    void setPlan(Plan plan) {
        this.plan = plan;
        this.unplannedIndex = Math.max(unplannedIndex, plan.streamCount());
    }

    /**
     * Returns the stream with an index.
     *
     * @param index the stream index
     * @return the stream, or {@code null} when this parser has not seen it
     */
    Stream stream(int index) {
        return byIndex.get(index);
    }

    /**
     * Returns the link with an index.
     *
     * @param index the link index
     * @return the link, or {@code null} when this parser has not seen it
     */
    Link link(int index) {
        for (var candidate : links) {
            if (candidate.index == index) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Returns the offset of the next unread byte.
     *
     * @return the offset
     */
    long position() {
        return reader.position();
    }

    /**
     * Moves the parser to a page boundary, discarding every packet in progress.
     *
     * <p>The next pages are read from {@code position}; each stream forgets its partial packet
     * and the state its mapping carries from packet to packet, and stamps the packets of its next
     * pages back from the first granule position it meets.
     *
     * @param position the offset of a page, or of bytes before one
     */
    void reposition(long position) {
        reader.reset(position);
        output.clear();
        exhausted = false;
        for (var stream : streams) {
            stream.resync();
        }
    }

    /**
     * Starts recording the offsets of the pages this parser accepts, for
     * {@link #recordedPages()}.
     */
    void recordPages() {
        reader.recordPages();
    }

    /**
     * Returns the offsets of the pages accepted since {@link #recordPages()}, which a parser over
     * the same source can trust with {@link #trustPages(long[], int)}.
     *
     * @return the array holding the increasing offsets in its first {@link #recordedCount()}
     *         entries, or {@code null} when not recording
     */
    long[] recordedPages() {
        return reader.recordedPages();
    }

    /**
     * Returns the number of recorded page offsets.
     *
     * @return the count
     */
    int recordedCount() {
        return reader.recordedCount();
    }

    /**
     * Makes this parser skip the CRC of the pages starting at the given offsets.
     *
     * @param offsets the increasing offsets of pages a reader over the same source accepted
     * @param count the number of valid entries in {@code offsets}
     */
    void trustPages(long[] offsets, int count) {
        reader.trustPages(offsets, count);
    }

    /**
     * Sets the start time of every link, which offsets the timestamps of chained links.
     *
     * @param starts the start of each link in microseconds
     */
    void setLinkStarts(long[] starts) {
        this.linkStarts = starts;
        for (var stream : streams) {
            stream.applyOffset();
        }
    }

    /**
     * Reads and processes one page.
     *
     * @return {@code false} at the end of the input
     */
    boolean pump() {
        if (exhausted) {
            return false;
        }
        if (!reader.next()) {
            exhausted = true;
            for (var stream : streams) {
                stream.finish();
            }
            return false;
        }
        var serial = reader.serial;
        var stream = active.get(serial);
        if ((reader.flags & OggPageReader.FLAG_BOS) != 0) {
            if (links.isEmpty() || linkHeadersDone) {
                startLink();
            }
            linkHeadersDone = false;
            if (streams.size() >= MAX_STREAMS) {
                throw new MediaException.Malformed("Ogg file with more than " + MAX_STREAMS + " logical streams");
            }
            stream = new Stream(nextIndex++, serial, links.getLast());
            if (links.getLast().planned) {
                unplannedIndex = Math.max(unplannedIndex, nextIndex);
            } else if (plan != null) {
                unplannedIndex = nextIndex;
            }
            streams.add(stream);
            byIndex.put(stream.index, stream);
            active.put(serial, stream);
            stream.owner.beginningsEnd = reader.position();
        } else {
            linkHeadersDone = true;
            if (stream == null) {
                return true;
            }
        }
        stream.consume();
        return true;
    }

    /**
     * Starts the link whose first beginning-of-stream page is the current page.
     */
    private void startLink() {
        var offset = reader.pageOffset;
        var planned = false;
        if (plan != null) {
            var position = Arrays.binarySearch(plan.starts(), offset);
            if (position >= 0) {
                link = position;
                nextIndex = plan.firstIndexes()[position];
                planned = true;
            } else {
                link = plan.starts().length + unplannedLinks++;
                nextIndex = unplannedIndex;
            }
        } else if (!links.isEmpty()) {
            link++;
        }
        var started = new Link(link, offset);
        started.planned = planned;
        links.add(started);
    }

    /**
     * Holds what the parser learned about one link.
     */
    static final class Link {
        /**
         * The link index.
         */
        final int index;

        /**
         * The offset of the link's first beginning-of-stream page.
         */
        final long start;

        /**
         * The offset of the first page after the link's header pages, where its data pages start.
         */
        long dataStart;

        /**
         * The offset after the link's last beginning-of-stream page.
         */
        long beginningsEnd;

        /**
         * Whether the link's numbering comes from the plan.
         */
        private boolean planned;

        /**
         * The streams of the link, in beginning-of-stream order.
         */
        final List<Stream> streams = new ArrayList<>();

        /**
         * The chapters and pictures of the comment headers of the link's streams.
         */
        final Annotations annotations = new Annotations();

        /**
         * The Ogg Skeleton descriptions of the link's streams, by serial number.
         */
        final Map<Integer, SkeletonMapping.Fisbone> fisbones = new HashMap<>();

        /**
         * Constructs a link.
         *
         * @param index the link index
         * @param start the offset of its first page
         */
        private Link(int index, long start) {
            this.index = index;
            this.start = start;
            this.dataStart = start;
            this.beginningsEnd = start;
        }

        /**
         * Returns the comment tags of every stream of the link, in stream order, a name repeated
         * across streams joining its values with {@code "; "}.
         *
         * @return the tags; modifiable
         */
        Map<String, String> metadata() {
            var merged = new LinkedHashMap<String, String>();
            for (var stream : streams) {
                for (var entry : stream.tags.entrySet()) {
                    merged.merge(entry.getKey(), entry.getValue(), (previous, next) -> previous + "; " + next);
                }
            }
            return merged;
        }

        /**
         * Gives a stream of the link the granule mapping of its Skeleton description.
         *
         * @param stream a stream of the link whose mapping is known
         */
        private void describe(Stream stream) {
            var fisbone = fisbones.get(stream.serial);
            if (fisbone != null && stream.mapping instanceof OpaqueMapping opaque) {
                opaque.describe(fisbone);
            }
        }
    }

    /**
     * Holds the state of one logical stream.
     */
    final class Stream {
        /**
         * The stream index, which is also its track identifier.
         */
        final int index;

        /**
         * The bitstream serial number.
         */
        final int serial;

        /**
         * The index of the link the stream belongs to.
         */
        final int link;

        /**
         * The link the stream belongs to.
         */
        final Link owner;

        /**
         * The tags of the stream's comment header, without the chapter and picture fields.
         */
        final Map<String, String> tags = new LinkedHashMap<>();

        /**
         * The codec mapping, chosen from the first packet.
         */
        OggMapping mapping;

        /**
         * The number of header packets consumed.
         */
        int headers;

        /**
         * Whether every header packet was consumed.
         */
        boolean headersDone;

        /**
         * The bytes of the packet in progress.
         */
        private byte[] partial = new byte[256];

        /**
         * The number of valid bytes in {@link #partial}.
         */
        private int partialLength;

        /**
         * Whether a packet is in progress.
         */
        private boolean partialValid;

        /**
         * The sequence number expected on the next page.
         */
        private int expectedSequence;

        /**
         * Whether a page of this stream was seen.
         */
        private boolean seenPage;

        /**
         * Whether the end-of-stream page was seen.
         */
        boolean ended;

        /**
         * Whether the first data packets were anchored to a granule position.
         */
        boolean anchored;

        /**
         * The timestamp of the first data packet, without link offset, once anchored.
         */
        long firstTimestamp;

        /**
         * The timestamp of the next data packet, without link offset.
         */
        private long nextTimestamp;

        /**
         * The last granule position seen, or {@code -1}.
         */
        long lastGranule = -1;

        /**
         * Whether the parser moved and the stream waits for a granule position to stamp its
         * packets again.
         */
        private boolean resyncing;

        /**
         * The offset added to timestamps, in the stream's time base.
         */
        private long offset;

        /**
         * The data packets completed on the current page, or held until the first granule.
         */
        private final List<byte[]> pending = new ArrayList<>();

        /**
         * The durations of {@link #pending}.
         */
        private long[] pendingDurations = new long[16];

        /**
         * Constructs a stream.
         *
         * @param index the stream index
         * @param serial the serial number
         * @param owner the link
         */
        private Stream(int index, int serial, Link owner) {
            this.index = index;
            this.serial = serial;
            this.link = owner.index;
            this.owner = owner;
            owner.streams.add(this);
        }

        /**
         * Returns the tags that describe the stream: its comment tags, then the message header
         * fields of its Ogg Skeleton description that the comments do not name.
         *
         * @return the tags; modifiable
         */
        Map<String, String> trackTags() {
            var merged = new LinkedHashMap<>(tags);
            var fisbone = owner.fisbones.get(serial);
            if (fisbone != null) {
                for (var entry : fisbone.fields().entrySet()) {
                    merged.putIfAbsent(entry.getKey(), entry.getValue());
                }
            }
            return merged;
        }

        /**
         * Returns the language of the stream's Ogg Skeleton description.
         *
         * @return the {@code Language} field, or {@code null} when there is none
         */
        String skeletonLanguage() {
            var fisbone = owner.fisbones.get(serial);
            if (fisbone == null) {
                return null;
            }
            for (var entry : fisbone.fields().entrySet()) {
                if (entry.getKey().equalsIgnoreCase("Language")) {
                    return entry.getValue();
                }
            }
            return null;
        }

        /**
         * Recomputes the timestamp offset from the link start times.
         *
         * <p>Nothing happens before the header packets are consumed, since the time base of most
         * mappings comes from their headers; {@link #complete(byte[])} applies the offset as the
         * headers end, before the first data packet is emitted.
         */
        private void applyOffset() {
            if (linkStarts == null || link >= linkStarts.length || mapping == null || !headersDone) {
                return;
            }
            offset = Rational.MICROSECONDS.rescale(linkStarts[link], mapping.timeBase());
        }

        /**
         * Reassembles the packets of the current page.
         */
        private void consume() {
            var sequence = reader.sequence;
            var lost = seenPage && sequence != expectedSequence;
            expectedSequence = sequence + 1;
            seenPage = true;
            var continued = (reader.flags & OggPageReader.FLAG_CONTINUED) != 0;
            if (lost || !continued) {
                partialLength = 0;
                partialValid = false;
            }
            var dropLeading = continued && !partialValid;
            var skipData = scan && headersDone && (anchored || !mapping.timestamped()) && !mapping.observesData();
            var body = reader.body;
            var position = reader.bodyOffset;
            var runStart = -1;
            try {
                for (var i = 0; i < reader.segments; i++) {
                    var size = reader.lacing[i];
                    if (dropLeading) {
                        position += size;
                        if (size < 255) {
                            dropLeading = false;
                        }
                        continue;
                    }
                    if (!skipData) {
                        if (runStart < 0) {
                            runStart = position;
                        }
                        reserve(partialLength + position + size - runStart);
                    }
                    partialValid = true;
                    position += size;
                    if (size < 255) {
                        if (!skipData) {
                            byte[] packet;
                            if (partialLength == 0) {
                                packet = Arrays.copyOfRange(body, runStart, position);
                            } else {
                                append(body, runStart, position - runStart);
                                runStart = -1;
                                packet = Arrays.copyOf(partial, partialLength);
                            }
                            complete(packet);
                            runStart = -1;
                        }
                        partialLength = 0;
                        partialValid = false;
                    }
                }
            } catch (RuntimeException exception) {
                // leaves the packet buffer as segment-by-segment appending would have left it
                if (runStart >= 0) {
                    append(body, runStart, position - runStart);
                }
                throw exception;
            }
            if (runStart >= 0) {
                append(body, runStart, position - runStart);
            }
            if ((reader.flags & OggPageReader.FLAG_EOS) != 0) {
                ended = true;
            }
            if (reader.granule != -1) {
                lastGranule = reader.granule;
            }
            afterPage(reader.granule, ended);
        }

        /**
         * Appends bytes of the page body to the packet in progress.
         *
         * @param data the page body array
         * @param offset the index of the first byte
         * @param length the number of bytes, already reserved with {@link #reserve(long)}
         */
        private void append(byte[] data, int offset, int length) {
            System.arraycopy(data, offset, partial, partialLength, length);
            partialLength += length;
        }

        /**
         * Grows the packet buffer to hold {@code needed} bytes, checking the size against the
         * limits.
         *
         * <p>It is called once per lacing segment with the packet size up to the end of that
         * segment, so the buffer grows, and a size is rejected, at exactly the segment where
         * appending segment by segment would grow or reject it.
         *
         * @param needed the packet size up to the end of the current segment
         * @throws MediaException.LimitExceeded if a larger buffer exceeds the limits
         */
        private void reserve(long needed) {
            if (needed > partial.length) {
                LimitChecks.checkAllocation(limits, needed);
                partial = Arrays.copyOf(partial, (int) Math.min(Integer.MAX_VALUE - 16, Math.max(needed, 2L * partial.length)));
            }
        }

        /**
         * Forgets the packet in progress and the mapping's packet-to-packet state, after the
         * parser moved.
         */
        private void resync() {
            partialLength = 0;
            partialValid = false;
            seenPage = false;
            pending.clear();
            ended = false;
            anchored = true;
            resyncing = true;
            if (mapping != null) {
                mapping.reset();
            }
        }

        /**
         * Handles a completed packet.
         *
         * @param packet a packet completed on the current page
         */
        private void complete(byte[] packet) {
            if (!headersDone) {
                if (mapping == null) {
                    mapping = OggMapping.detect(packet);
                    mapping.annotations = owner.annotations;
                    owner.describe(this);
                }
                if (mapping.isHeader(headers, packet)) {
                    mapping.header(headers, packet, limits, tags);
                    headers++;
                    if (headers >= mapping.headerCount()) {
                        headersDone = true;
                        owner.dataStart = Math.max(owner.dataStart, reader.position());
                        applyOffset();
                    }
                    return;
                }
                headersDone = true;
                applyOffset();
            }
            if (mapping.observesData()) {
                mapping.observe(packet);
                if (mapping instanceof SkeletonMapping skeleton) {
                    describeStreams(skeleton);
                }
            }
            if (pending.size() == pendingDurations.length) {
                pendingDurations = Arrays.copyOf(pendingDurations, pendingDurations.length * 2);
            }
            pendingDurations[pending.size()] = mapping.packetDuration(packet);
            pending.add(packet);
        }

        /**
         * Records the {@code fisbone} descriptions a Skeleton packet carried and applies them to
         * the streams of the link they describe.
         *
         * @param skeleton the mapping of this Skeleton stream
         */
        private void describeStreams(SkeletonMapping skeleton) {
            SkeletonMapping.Fisbone fisbone;
            while ((fisbone = skeleton.poll()) != null) {
                if (owner.fisbones.size() >= MAX_STREAMS || owner.fisbones.putIfAbsent(fisbone.serial(), fisbone) != null) {
                    continue;
                }
                for (var described : owner.streams) {
                    if (described.serial == fisbone.serial() && described.mapping != null) {
                        owner.describe(described);
                    }
                }
            }
        }

        /**
         * Timestamps and emits the data packets completed so far.
         *
         * @param granule the page granule position, or {@code -1}
         * @param last whether the page ends the stream
         */
        private void afterPage(long granule, boolean last) {
            if (pending.isEmpty()) {
                return;
            }
            if (!mapping.timestamped()) {
                for (var packet : pending) {
                    emit(packet, Packet.NO_TIMESTAMP, Packet.UNKNOWN_DURATION, 0);
                }
                pending.clear();
                return;
            }
            var count = pending.size();
            var sum = 0L;
            for (var i = 0; i < count; i++) {
                sum += pendingDurations[i];
            }
            long timestamp;
            if (!anchored) {
                if (granule == -1) {
                    return;
                }
                // RFC 7845 section 4: an end-of-stream granule carries end trimming, so a stream whose
                // first data page is also its last starts at granule zero
                timestamp = last && sum > 0 ? mapping.defaultStart() : mapping.granuleToTime(granule) - sum;
                firstTimestamp = timestamp;
                anchored = true;
            } else if (resyncing) {
                if (granule == -1) {
                    return;
                }
                timestamp = mapping.granuleToTime(granule) - sum;
                resyncing = false;
            } else if (granule != -1 && (!last || sum == 0)) {
                timestamp = mapping.granuleToTime(granule) - sum;
            } else {
                timestamp = nextTimestamp;
            }
            nextTimestamp = timestamp;
            var trimmed = 0L;
            if (last && granule != -1) {
                var end = mapping.granuleToTime(granule);
                var lastStart = nextTimestamp + sum - pendingDurations[count - 1];
                if (lastStart + pendingDurations[count - 1] > end) {
                    var kept = Math.max(0, end - lastStart);
                    trimmed = pendingDurations[count - 1] - kept;
                    pendingDurations[count - 1] = kept;
                }
            }
            for (var i = 0; i < count; i++) {
                emit(pending.get(i), nextTimestamp, pendingDurations[i], i == count - 1 ? trimmed : 0);
                nextTimestamp += pendingDurations[i];
            }
            pending.clear();
        }

        /**
         * Emits the packets still held at the end of the input, without timestamps when the
         * stream never reached a granule position.
         */
        private void finish() {
            if (!anchored) {
                for (var i = 0; i < pending.size(); i++) {
                    emit(pending.get(i), Packet.NO_TIMESTAMP, pendingDurations[i], 0);
                }
                pending.clear();
            }
        }

        /**
         * Queues one data packet.
         *
         * @param packet the payload
         * @param timestamp the timestamp without link offset, or {@link Packet#NO_TIMESTAMP}
         * @param duration the duration, end trimming excluded, or {@link Packet#UNKNOWN_DURATION}
         * @param discardEnd the end trimming removed from the duration, reported as {@link PacketSideData#discardEnd()}
         */
        private void emit(byte[] packet, long timestamp, long duration, long discardEnd) {
            if (scan) {
                return;
            }
            var pts = timestamp == Packet.NO_TIMESTAMP ? timestamp : timestamp + offset;
            var emitted = new Packet(index, packet, pts, pts, duration, mapping.isKeyframe(packet));
            if (discardEnd > 0) {
                emitted = emitted.withSideData(PacketSideData.builder().discard(0, discardEnd).build());
            }
            output.add(emitted);
        }
    }
}
