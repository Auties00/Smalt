package com.github.auties00.smalt.internal.tags;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.internal.edit.EditPlan;
import com.github.auties00.smalt.internal.io.ByteArrays;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.metadata.MediaTags;
import com.github.auties00.smalt.metadata.TagWriteOptions;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Edits the comment headers of the Ogg Vorbis, Opus or FLAC streams of every link of a file.
 *
 * <p>In each link (a file that is not chained has one; RFC 3533 section 4), the first logical
 * stream whose identification header is Vorbis, Opus or FLAC is edited; the pages of other streams,
 * and links that have no such stream, pass through. Its header packets after the identification
 * packet (the comment header and, for Vorbis, the setup header; for FLAC every metadata block) are
 * rebuilt and paginated again; the following pages of the stream, up to the end of its link, get
 * their sequence numbers shifted by the change in page count, with fresh CRCs, so audio pages keep
 * their content and granule positions. When the new header pages are exactly as long as the old
 * ones, the file is edited in place; a Vorbis or Opus comment header is padded with zero bytes to
 * its old length when it shrinks, which makes this the common case, and gets the configured padding
 * when it grows.
 *
 * <p>{@link #read()} returns the tags of the first link that has an edited stream. An edit writes
 * exactly the new tags into that link; into every later link it writes that link's own tags with
 * the changes the edit makes to the first one: a field whose values differ takes the new values (or
 * disappears), and the pictures and chapters take the new ones when they differ, while everything
 * the edit leaves alone keeps each link's own values.
 *
 * <p>The links are found by bisection: a link ends at the first page after its headers whose serial
 * number none of its streams has, or that begins a stream. A later link that reuses a serial number
 * of the link before it, which RFC 3533 forbids, is found only when bisection reads one of its
 * beginning-of-stream pages; otherwise its tags are kept. The first {@value #MAX_LINKS} links with
 * an edited stream are edited, and any later link is copied unchanged.
 *
 * @implNote This implementation pads a Vorbis comment header after its framing bit, as mutagen does: libvorbis and
 * every other decoder stop reading the header at the framing bit. Ogg FLAC headers are never padded, so their edits
 * rewrite the file.
 */
final class OggTagFile implements TagFile {
    /**
     * The largest number of pages read while looking for the header packets of a link.
     */
    private static final int MAX_HEADER_PAGES = 4096;

    /**
     * The number of bytes read at the end of a link to find its last granule position.
     */
    private static final int TAIL = 1 << 17;

    /**
     * The largest number of beginning-of-stream pages read at the start of a link.
     */
    private static final int MAX_BEGINNINGS = 64;

    /**
     * The largest number of links edited.
     */
    private static final int MAX_LINKS = 1024;

    /**
     * The byte range below which the search for the end of a link reads the pages in between.
     */
    private static final long BISECTION_STOP = 64 * 1024;

    /**
     * The size of the chunks read while searching for a page.
     */
    private static final int SEARCH_CHUNK = 1 << 16;

    /**
     * The codec of an edited stream.
     */
    private enum Codec {
        /**
         * Vorbis: identification, comment and setup headers.
         */
        VORBIS,

        /**
         * Opus: {@code OpusHead} and {@code OpusTags}.
         */
        OPUS,

        /**
         * FLAC: the mapping header with {@code STREAMINFO}, then one metadata block per packet.
         */
        FLAC
    }

    /**
     * The source.
     */
    private final MediaSource source;

    /**
     * The links with an edited stream, in file order.
     */
    private final List<Link> links;

    /**
     * Holds the edited stream of one link.
     */
    private final class Link {
        /**
         * The codec of the edited stream.
         */
        private final Codec codec;

        /**
         * The serial number of the edited stream.
         */
        private final int serial;

        /**
         * The first page of the edited stream, which holds only the identification packet.
         */
        private final OggPages.Page firstPage;

        /**
         * The identification packet.
         */
        private final byte[] identification;

        /**
         * The header packets after the identification packet, in order.
         */
        private final List<byte[]> headers;

        /**
         * The pages from the end of the first page of the edited stream to the end of its last
         * header page, in file order.
         */
        private final List<OggPages.Page> region;

        /**
         * The end of the last header page of the edited stream.
         */
        private final long regionEnd;

        /**
         * The offset where the link ends.
         */
        private long end;

        /**
         * The duration of the stream, or {@code -1} when unknown.
         */
        private long durationMicros = -1;

        /**
         * Constructs the description of a link's edited stream.
         *
         * @param codec the codec of the edited stream
         * @param firstPage the stream's first page
         * @param identification the identification packet
         * @param headers the header packets after it
         * @param region the pages up to the stream's last header page
         */
        private Link(Codec codec, OggPages.Page firstPage, byte[] identification, List<byte[]> headers,
                     List<OggPages.Page> region) {
            this.codec = codec;
            this.serial = firstPage.serial();
            this.firstPage = firstPage;
            this.identification = identification;
            this.headers = List.copyOf(headers);
            this.region = List.copyOf(region);
            this.regionEnd = region.getLast().end();
        }

        /**
         * {@return the offset of the comment block inside the comment header}
         */
        private int commentOffset() {
            return switch (codec) {
                case VORBIS -> 7;
                case OPUS -> 8;
                case FLAC -> 4;
            };
        }

        /**
         * Returns the tags, pictures and chapters of the edited stream.
         *
         * @return the tags
         */
        private MediaTags read() {
            var comment = headers.getFirst();
            var parsed = VorbisComment.parse(comment, commentOffset(), comment.length - commentOffset());
            var tags = VorbisComment.decode(parsed.fields(), durationMicros);
            if (codec != Codec.FLAC) {
                return tags;
            }
            var pictures = new ArrayList<>(tags.pictures());
            for (var header : headers.subList(1, headers.size())) {
                if (header.length >= 4 && (header[0] & 0x7F) == 6) {
                    var picture = FlacPicture.decode(header, 4, header.length - 4);
                    if (picture != null) {
                        pictures.add(picture);
                    }
                }
            }
            return tags.toBuilder().pictures(pictures).build();
        }
    }

    /**
     * Parses the header pages of every link.
     *
     * @param source the Ogg file; not closed
     * @param limits the resource bounds
     * @throws MediaException.Unsupported if no link has a Vorbis, Opus or FLAC stream, or the last header packet of
     * an edited stream shares a page with audio
     * @throws MediaException.Malformed if the pages are corrupt
     */
    OggTagFile(MediaSource source, MediaLimits limits) {
        this.source = source;
        var found = new ArrayList<Link>();
        var start = 0L;
        var size = source.size();
        while (found.size() < MAX_LINKS) {
            var serials = new HashSet<Integer>();
            var beginningsEnd = beginnings(start, serials);
            var link = parseLink(start, beginningsEnd, limits);
            var searchFrom = link != null ? link.regionEnd : beginningsEnd;
            var next = serials.isEmpty() ? -1 : boundary(searchFrom, size, serials);
            if (link != null) {
                link.end = next < 0 ? size : next;
                link.durationMicros = duration(link);
                found.add(link);
            }
            if (next < 0) {
                break;
            }
            start = next;
        }
        if (found.isEmpty()) {
            throw OggPages.unsupported("no Vorbis, Opus or FLAC stream to tag");
        }
        this.links = List.copyOf(found);
        for (var link : links) {
            validateHeaders(link);
        }
    }

    /**
     * Reads the beginning-of-stream pages that open a link.
     *
     * @param start the offset of the link's first page
     * @param serials receives the serial numbers of the link's streams
     * @return the offset after the last of those pages
     */
    private long beginnings(long start, Set<Integer> serials) {
        var position = start;
        for (var count = 0; count < MAX_BEGINNINGS; count++) {
            var page = OggPages.read(source, position);
            if (page == null || (page.flags() & OggPages.FIRST) == 0) {
                break;
            }
            serials.add(page.serial());
            position = page.end();
        }
        return position;
    }

    /**
     * Parses the header pages of the first Vorbis, Opus or FLAC stream of a link.
     *
     * @param start the offset of the link's first page
     * @param beginningsEnd the offset after the link's beginning-of-stream pages
     * @param limits the resource bounds
     * @return the link, or {@code null} when it has no such stream
     * @throws MediaException.Unsupported if the last header packet shares a page with audio
     * @throws MediaException.Malformed if the pages are corrupt
     */
    private Link parseLink(long start, long beginningsEnd, MediaLimits limits) {
        var position = start;
        OggPages.Page first = null;
        Codec found = null;
        byte[] id = null;
        while (position < beginningsEnd) {
            var page = OggPages.read(source, position);
            var body = TagIo.read(source, page.bodyOffset(), page.end() - page.bodyOffset(), limits);
            var codec = codecOf(body);
            if (codec != null) {
                found = codec;
                first = page;
                id = body;
                break;
            }
            position = page.end();
        }
        if (found == null) {
            return null;
        }
        if (first.lacing().length == 0 || (first.lacing()[first.lacing().length - 1] & 0xFF) == 255
                || packetCount(first.lacing()) != 1) {
            throw new MediaException.Malformed("the Ogg identification header does not fill its own page");
        }
        var serial = first.serial();
        var needed = switch (found) {
            case VORBIS -> 2;
            case OPUS -> 1;
            case FLAC -> {
                var count = ByteArrays.u16be(id, 7);
                yield count == 0 ? Integer.MAX_VALUE : count;
            }
        };
        var packets = new ArrayList<byte[]>();
        var pages = new ArrayList<OggPages.Page>();
        var current = new ByteArrayOutputStream();
        var total = 0L;
        position = first.end();
        var done = false;
        while (!done) {
            if (pages.size() >= MAX_HEADER_PAGES) {
                throw new MediaException.Malformed("Ogg header packets span more than " + MAX_HEADER_PAGES + " pages");
            }
            var page = OggPages.read(source, position);
            if (page == null) {
                throw new MediaException.Malformed("Ogg stream ends inside its header packets at offset " + position);
            }
            pages.add(page);
            position = page.end();
            if (page.serial() != serial) {
                continue;
            }
            var body = TagIo.read(source, page.bodyOffset(), page.end() - page.bodyOffset(), limits);
            var bodyOffset = 0;
            var lacing = page.lacing();
            for (var i = 0; i < lacing.length; i++) {
                var value = lacing[i] & 0xFF;
                current.write(body, bodyOffset, value);
                bodyOffset += value;
                total += value;
                LimitChecks.checkAllocation(limits, total);
                if (value < 255) {
                    var packet = current.toByteArray();
                    current.reset();
                    if (done) {
                        throw OggPages.unsupported("the last Ogg header packet shares its page with audio");
                    }
                    packets.add(packet);
                    if (found == Codec.FLAC && needed == Integer.MAX_VALUE && packet.length > 0
                            && (packet[0] & 0x80) != 0) {
                        done = true;
                    } else if (packets.size() >= needed) {
                        done = true;
                    }
                }
            }
            if (done && current.size() > 0) {
                throw OggPages.unsupported("the last Ogg header packet shares its page with audio");
            }
        }
        while (!pages.isEmpty() && pages.getLast().serial() != serial) {
            pages.removeLast();
        }
        return new Link(found, first, id, packets, pages);
    }

    /**
     * Finds where a link ends: the first page at or after an offset that belongs to no stream of
     * the link or begins a stream.
     *
     * @param low the offset of a page boundary inside the link
     * @param high the end of the file
     * @param serials the serial numbers of the link's streams
     * @return the offset of that page, or {@code -1} when the link runs to the end of the file
     */
    private long boundary(long low, long high, Set<Integer> serials) {
        var foreign = -1L;
        while (high - low > BISECTION_STOP) {
            var middle = low + (high - low) / 2;
            var page = pageAt(middle, high);
            if (page == null) {
                high = middle;
            } else if (serials.contains(page.serial()) && (page.flags() & OggPages.FIRST) == 0) {
                low = page.end();
            } else {
                foreign = page.offset();
                high = page.offset();
            }
        }
        var limit = foreign >= 0 ? foreign + 1 : source.size();
        var position = low;
        while (position < limit) {
            var page = pageAt(position, limit);
            if (page == null) {
                break;
            }
            if (!serials.contains(page.serial()) || (page.flags() & OggPages.FIRST) != 0) {
                return page.offset();
            }
            position = page.end();
        }
        return foreign;
    }

    /**
     * Finds the first complete page with a matching CRC that starts at or after an offset and
     * before a limit.
     *
     * @param from the offset where the search starts
     * @param limit the offset the page must start before
     * @return the page, or {@code null} when there is none
     */
    private OggPages.Page pageAt(long from, long limit) {
        var direct = OggPages.read(source, from);
        if (direct != null && crcMatches(direct)) {
            return direct;
        }
        var position = from;
        while (position < limit) {
            var chunk = TagIo.readAvailable(source, position, (int) Math.min(SEARCH_CHUNK, limit - position + 3));
            if (chunk.length < 4) {
                return null;
            }
            for (var i = 0; i + 4 <= chunk.length && position + i < limit; i++) {
                if (chunk[i] != 'O' || !TagIo.matches(chunk, i, "OggS")) {
                    continue;
                }
                var page = OggPages.read(source, position + i);
                if (page != null && crcMatches(page)) {
                    return page;
                }
            }
            position += Math.max(1, chunk.length - 3);
        }
        return null;
    }

    /**
     * Returns whether a page's stored CRC matches its bytes.
     *
     * @param page a page found in the source
     * @return {@code true} on a match
     */
    private boolean crcMatches(OggPages.Page page) {
        var bytes = new byte[(int) page.length()];
        source.readFully(page.offset(), bytes, 0, bytes.length);
        var stored = ByteArrays.u32le(bytes, 22);
        ByteArrays.put32le(bytes, 22, 0);
        return (OggPages.crc(bytes, 0, bytes.length) & 0xFFFFFFFFL) == stored;
    }

    /**
     * Recognizes the identification packet of a supported codec.
     *
     * @param packet the first packet of a stream
     * @return the codec, or {@code null}
     */
    private static Codec codecOf(byte[] packet) {
        if (packet.length >= 30 && packet[0] == 1 && TagIo.matches(packet, 1, "vorbis")) {
            return Codec.VORBIS;
        }
        if (packet.length >= 19 && TagIo.matches(packet, 0, "OpusHead")) {
            return Codec.OPUS;
        }
        if (packet.length >= 51 && (packet[0] & 0xFF) == 0x7F && TagIo.matches(packet, 1, "FLAC")
                && TagIo.matches(packet, 9, "fLaC")) {
            return Codec.FLAC;
        }
        return null;
    }

    /**
     * Counts the packets that end on a page.
     *
     * @param lacing the lacing values
     * @return the number of values below 255
     */
    private static int packetCount(byte[] lacing) {
        var count = 0;
        for (var value : lacing) {
            if ((value & 0xFF) < 255) {
                count++;
            }
        }
        return count;
    }

    /**
     * Checks that the comment header of a link has the form its codec requires.
     *
     * @param link a link with an edited stream
     * @throws MediaException.Malformed if it does not
     */
    private static void validateHeaders(Link link) {
        var comment = link.headers.getFirst();
        var valid = switch (link.codec) {
            case VORBIS -> comment.length >= 7 && comment[0] == 3 && TagIo.matches(comment, 1, "vorbis");
            case OPUS -> comment.length >= 8 && TagIo.matches(comment, 0, "OpusTags");
            case FLAC -> comment.length >= 4 && (comment[0] & 0x7F) == 4;
        };
        if (!valid) {
            throw new MediaException.Malformed("the second Ogg " + link.codec.name().toLowerCase(Locale.ROOT)
                    + " header is not a comment header");
        }
    }

    /**
     * Computes the duration of a link's edited stream from its last granule position.
     *
     * @param link a link with an edited stream
     * @return the duration in microseconds, or {@code -1} when unknown
     */
    private long duration(Link link) {
        var start = Math.max(link.regionEnd, link.end - TAIL);
        var tail = TagIo.readAvailable(source, start, (int) (link.end - start));
        var granule = -1L;
        for (var i = tail.length - OggPages.HEADER_SIZE; i >= 0; i--) {
            if (tail[i] == 'O' && TagIo.matches(tail, i, "OggS") && tail[i + 4] == 0
                    && (int) ByteArrays.u32le(tail, i + 14) == link.serial) {
                granule = ByteArrays.i64le(tail, i + 6);
                if (granule >= 0) {
                    break;
                }
            }
        }
        if (granule < 0) {
            return -1;
        }
        var identification = link.identification;
        return switch (link.codec) {
            case VORBIS -> {
                var rate = ByteArrays.u32le(identification, 12);
                yield rate > 0 ? granule * 1_000_000L / rate : -1;
            }
            case OPUS -> Math.max(0, granule - ByteArrays.u16le(identification, 10)) * 1_000_000L / 48_000;
            case FLAC -> {
                var rate = (ByteArrays.u24be(identification, 27) >>> 4) & 0xFFFFF;
                yield rate > 0 ? granule * 1_000_000L / rate : -1;
            }
        };
    }

    @Override
    public MediaTags read() {
        return links.getFirst().read();
    }

    @Override
    public EditPlan plan(MediaTags tags, TagWriteOptions options) {
        var base = read();
        var plan = EditPlan.builder();
        var position = 0L;
        for (var index = 0; index < links.size(); index++) {
            var link = links.get(index);
            var linkTags = index == 0 ? tags : carry(base, tags, link.read());
            position = plan(plan, position, link, linkTags, options);
        }
        var size = source.size();
        if (position < size) {
            plan.copy(position, size - position);
        }
        return plan.build();
    }

    /**
     * Returns the tags of a later link after an edit: its own tags with the changes the edit makes
     * to the first link's tags.
     *
     * @param base the tags of the first link
     * @param edited the new tags of the first link
     * @param own the tags of the later link
     * @return the new tags of the later link
     */
    private static MediaTags carry(MediaTags base, MediaTags edited, MediaTags own) {
        var result = own.toBuilder();
        var keys = new HashSet<String>();
        keys.addAll(base.keys());
        keys.addAll(edited.keys());
        for (var key : keys) {
            var values = edited.getAll(key);
            if (values.equals(base.getAll(key))) {
                continue;
            }
            if (values.isEmpty()) {
                result.remove(key);
            } else {
                result.set(key, values.toArray(String[]::new));
            }
        }
        if (!edited.pictures().equals(base.pictures())) {
            result.pictures(edited.pictures());
        }
        if (!edited.chapters().equals(base.chapters())) {
            result.chapters(edited.chapters());
        }
        return result.build();
    }

    /**
     * Adds the pieces of one link to a plan: the bytes before the edited stream's first page, its
     * new header pages, and the rest of the link with the stream's pages renumbered.
     *
     * @param plan the plan being built
     * @param position the offset up to which the plan already covers the original
     * @param link a link with an edited stream
     * @param tags the new tags of the link
     * @param options the write options
     * @return the offset up to which the plan covers the original afterwards, the end of the link
     */
    private long plan(EditPlan.Builder plan, long position, Link link, MediaTags tags, TagWriteOptions options) {
        var headers = link.headers;
        var comment = headers.getFirst();
        var parsed = VorbisComment.parse(comment, link.commentOffset(), comment.length - link.commentOffset());
        var packets = new ArrayList<byte[]>();
        byte[] newIdentification = null;
        switch (link.codec) {
            case VORBIS -> {
                var block = VorbisComment.encode(parsed.vendor(), tags, true);
                var length = 7 + block.length + 1;
                length = length <= comment.length ? comment.length : length + options.padding();
                var packet = new byte[length];
                System.arraycopy(comment, 0, packet, 0, 7);
                System.arraycopy(block, 0, packet, 7, block.length);
                packet[7 + block.length] = 1;
                packets.add(packet);
                packets.addAll(headers.subList(1, headers.size()));
            }
            case OPUS -> packets.add(opusTags(comment, parsed, tags, options));
            case FLAC -> {
                var current = link.read();
                var keepPictures = current.pictures().equals(tags.pictures());
                var block = VorbisComment.encode(parsed.vendor(), tags.toBuilder().pictures(List.of()).build(), false);
                var rest = new ArrayList<byte[]>();
                for (var header : headers.subList(1, headers.size())) {
                    var type = header.length > 0 ? header[0] & 0x7F : -1;
                    if (type == 1 || (type == 6 && !keepPictures) || (type != 6 && type != 3 && !options.preserveUnknown())) {
                        continue;
                    }
                    rest.add(header);
                }
                if (!keepPictures) {
                    for (var picture : tags.pictures()) {
                        var body = FlacPicture.encode(picture);
                        rest.add(flacBlock(6, body));
                    }
                }
                packets.add(flacBlock(4, block));
                packets.addAll(rest);
                for (var index = 0; index < packets.size(); index++) {
                    var packet = packets.get(index).clone();
                    packet[0] = (byte) ((packet[0] & 0x7F) | (index == packets.size() - 1 ? 0x80 : 0));
                    packets.set(index, packet);
                }
                var declared = ByteArrays.u16be(link.identification, 7);
                if (declared != 0 && declared != packets.size()) {
                    newIdentification = link.identification.clone();
                    newIdentification[7] = (byte) (packets.size() >>> 8);
                    newIdentification[8] = (byte) packets.size();
                }
            }
        }
        var oldTargetPages = new ArrayList<OggPages.Page>();
        for (var page : link.region) {
            if (page.serial() == link.serial) {
                oldTargetPages.add(page);
            }
        }
        int[] layout = null;
        if (sameLengths(headers, packets)) {
            layout = new int[oldTargetPages.size()];
            for (var i = 0; i < layout.length; i++) {
                layout[i] = oldTargetPages.get(i).lacing().length;
            }
        }
        var firstPage = link.firstPage;
        var newPages = OggPages.paginate(packets, link.serial, oldTargetPages.getFirst().sequence(), layout);
        if (firstPage.offset() > position) {
            plan.copy(position, firstPage.offset() - position);
        }
        if (newIdentification != null) {
            var page = TagIo.read(source, firstPage.offset(), firstPage.length(), MediaLimits.DEFAULT);
            var bodyOffset = (int) (firstPage.bodyOffset() - firstPage.offset());
            System.arraycopy(newIdentification, 0, page, bodyOffset, newIdentification.length);
            OggPages.seal(page, 0, page.length);
            plan.bytes(page);
        } else {
            plan.copy(firstPage.offset(), firstPage.length());
        }
        position = firstPage.end();
        var inserted = false;
        for (var page : link.region) {
            if (page.offset() > position) {
                plan.copy(position, page.offset() - position);
            }
            if (page.serial() == link.serial) {
                if (!inserted) {
                    for (var bytes : newPages) {
                        plan.bytes(bytes);
                    }
                    inserted = true;
                }
            } else {
                plan.copy(page.offset(), page.length());
            }
            position = page.end();
        }
        var delta = newPages.size() - oldTargetPages.size();
        var rest = link.end - link.regionEnd;
        if (delta == 0) {
            plan.copy(link.regionEnd, rest);
        } else if (rest > 0) {
            plan.transform(new OggPages.Renumber(link.regionEnd, rest, link.serial, delta));
        }
        return link.end;
    }

    /**
     * Returns whether new header packets have exactly the lengths of the old ones, so the old
     * page layout can be reused.
     *
     * @param headers the old header packets
     * @param packets the new header packets
     * @return {@code true} when the lengths match one for one
     */
    private static boolean sameLengths(List<byte[]> headers, List<byte[]> packets) {
        if (packets.size() != headers.size()) {
            return false;
        }
        for (var i = 0; i < packets.size(); i++) {
            if (packets.get(i).length != headers.get(i).length) {
                return false;
            }
        }
        return true;
    }

    /**
     * Builds the {@code OpusTags} packet, keeping binary data the old header carried after the
     * comments and padding the packet to its old length when it shrinks.
     *
     * @param old the old {@code OpusTags} packet
     * @param parsed the parsed old comment block
     * @param tags the new tags
     * @param options the write options
     * @return the packet
     */
    private static byte[] opusTags(byte[] old, VorbisComment.Parsed parsed, MediaTags tags, TagWriteOptions options) {
        var extra = Arrays.copyOfRange(old, Math.min(old.length, parsed.end()), old.length);
        var keepExtra = extra.length > 0 && (extra[0] & 1) != 0;
        var block = VorbisComment.encode(parsed.vendor(), tags, true);
        var length = 8 + block.length + (keepExtra ? extra.length : 0);
        if (!keepExtra) {
            if (length <= old.length) {
                length = old.length;
            } else {
                length += options.padding();
            }
        }
        var packet = new byte[length];
        System.arraycopy(old, 0, packet, 0, 8);
        System.arraycopy(block, 0, packet, 8, block.length);
        if (keepExtra) {
            System.arraycopy(extra, 0, packet, 8 + block.length, extra.length);
        }
        return packet;
    }

    /**
     * Builds a FLAC metadata block packet.
     *
     * @param type the block type
     * @param body the block body
     * @return the packet
     * @throws MediaException.LimitExceeded if the body exceeds a block's 24-bit length
     */
    private static byte[] flacBlock(int type, byte[] body) {
        if (body.length >= 1 << 24) {
            throw new MediaException.LimitExceeded("FLAC metadata block of " + body.length + " bytes is too large");
        }
        var packet = new byte[4 + body.length];
        packet[0] = (byte) type;
        packet[1] = (byte) (body.length >>> 16);
        packet[2] = (byte) (body.length >>> 8);
        packet[3] = (byte) body.length;
        System.arraycopy(body, 0, packet, 4, body.length);
        return packet;
    }
}
