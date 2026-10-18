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
import java.util.List;

/**
 * Edits the comment header of an Ogg Vorbis, Opus or FLAC stream.
 *
 * <p>The first logical stream whose identification header is Vorbis, Opus or FLAC is edited; the
 * pages of other streams pass through. Its header packets after the identification packet (the
 * comment header and, for Vorbis, the setup header; for FLAC every metadata block) are rebuilt
 * and paginated again; the following pages of the stream get their sequence numbers shifted by
 * the change in page count, with fresh CRCs, so audio pages keep their content and granule
 * positions. When the new header pages are exactly as long as the old ones, the file is edited in
 * place; a Vorbis or Opus comment header is padded with zero bytes to its old length when it
 * shrinks, which makes this the common case, and gets the configured padding when it grows.
 *
 * @implNote This implementation pads a Vorbis comment header after its framing bit, as mutagen does: libvorbis and
 * every other decoder stop reading the header at the framing bit. Ogg FLAC headers are never padded, so their edits
 * rewrite the file.
 *
 * <p>Only the first link of a chained file is edited.
 */
final class OggTagFile implements TagFile {
    /**
     * The largest number of pages read while looking for the header packets.
     */
    private static final int MAX_HEADER_PAGES = 4096;

    /**
     * The number of bytes read at the end of the file to find the last granule position.
     */
    private static final int TAIL = 1 << 17;

    /**
     * The codec of the edited stream.
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
     * The duration of the stream, or {@code -1} when unknown.
     */
    private final long durationMicros;

    /**
     * Parses the header pages.
     *
     * @param source the Ogg file; not closed
     * @param limits the resource bounds
     * @throws MediaException.Unsupported if no stream is Vorbis, Opus or FLAC, or its last header packet shares a page
     * with audio
     * @throws MediaException.Malformed if the pages are corrupt
     */
    OggTagFile(MediaSource source, MediaLimits limits) {
        this.source = source;
        var position = 0L;
        OggPages.Page first = null;
        Codec found = null;
        byte[] id = null;
        var beginnings = 0;
        while (beginnings < 64) {
            var page = OggPages.read(source, position);
            if (page == null || (page.flags() & OggPages.FIRST) == 0) {
                break;
            }
            beginnings++;
            if (found == null) {
                var body = TagIo.read(source, page.bodyOffset(), page.end() - page.bodyOffset(), limits);
                var codec = codecOf(body);
                if (codec != null) {
                    found = codec;
                    first = page;
                    id = body;
                }
            }
            position = page.end();
        }
        if (found == null) {
            throw OggPages.unsupported("no Vorbis, Opus or FLAC stream to tag");
        }
        if (first.lacing().length == 0 || (first.lacing()[first.lacing().length - 1] & 0xFF) == 255
                || packetCount(first.lacing()) != 1) {
            throw new MediaException.Malformed("the Ogg identification header does not fill its own page");
        }
        this.codec = found;
        this.serial = first.serial();
        this.firstPage = first;
        this.identification = id;
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
        this.headers = List.copyOf(packets);
        this.region = List.copyOf(pages);
        this.regionEnd = pages.getLast().end();
        this.durationMicros = duration();
        validateHeaders();
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
     * Checks that the comment header has the form its codec requires.
     *
     * @throws MediaException.Malformed if it does not
     */
    private void validateHeaders() {
        var comment = headers.getFirst();
        var valid = switch (codec) {
            case VORBIS -> comment.length >= 7 && comment[0] == 3 && TagIo.matches(comment, 1, "vorbis");
            case OPUS -> comment.length >= 8 && TagIo.matches(comment, 0, "OpusTags");
            case FLAC -> comment.length >= 4 && (comment[0] & 0x7F) == 4;
        };
        if (!valid) {
            throw new MediaException.Malformed("the second Ogg " + codec.name().toLowerCase(java.util.Locale.ROOT)
                    + " header is not a comment header");
        }
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
     * Computes the duration from the last granule position of the stream.
     *
     * @return the duration in microseconds, or {@code -1} when unknown
     */
    private long duration() {
        var size = source.size();
        var start = Math.max(regionEnd, size - TAIL);
        var tail = TagIo.readAvailable(source, start, (int) (size - start));
        var granule = -1L;
        for (var i = tail.length - OggPages.HEADER_SIZE; i >= 0; i--) {
            if (tail[i] == 'O' && TagIo.matches(tail, i, "OggS") && tail[i + 4] == 0
                    && (int) ByteArrays.u32le(tail, i + 14) == serial) {
                granule = ByteArrays.i64le(tail, i + 6);
                if (granule >= 0) {
                    break;
                }
            }
        }
        if (granule < 0) {
            return -1;
        }
        return switch (codec) {
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

    @Override
    public EditPlan plan(MediaTags tags, TagWriteOptions options) {
        var comment = headers.getFirst();
        var parsed = VorbisComment.parse(comment, commentOffset(), comment.length - commentOffset());
        var packets = new ArrayList<byte[]>();
        byte[] newIdentification = null;
        switch (codec) {
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
            case OPUS -> packets.add(opusTags(parsed, tags, options));
            case FLAC -> {
                var current = read();
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
                var declared = ByteArrays.u16be(identification, 7);
                if (declared != 0 && declared != packets.size()) {
                    newIdentification = identification.clone();
                    newIdentification[7] = (byte) (packets.size() >>> 8);
                    newIdentification[8] = (byte) packets.size();
                }
            }
        }
        var oldTargetPages = new ArrayList<OggPages.Page>();
        for (var page : region) {
            if (page.serial() == serial) {
                oldTargetPages.add(page);
            }
        }
        int[] layout = null;
        if (sameLengths(packets)) {
            layout = new int[oldTargetPages.size()];
            for (var i = 0; i < layout.length; i++) {
                layout[i] = oldTargetPages.get(i).lacing().length;
            }
        }
        var firstSequence = oldTargetPages.getFirst().sequence();
        var newPages = OggPages.paginate(packets, serial, firstSequence, layout);
        var plan = EditPlan.builder();
        plan.copy(0, firstPage.offset());
        if (newIdentification != null) {
            var page = TagIo.read(source, firstPage.offset(), firstPage.length(), MediaLimits.DEFAULT);
            var bodyOffset = (int) (firstPage.bodyOffset() - firstPage.offset());
            System.arraycopy(newIdentification, 0, page, bodyOffset, newIdentification.length);
            OggPages.seal(page, 0, page.length);
            plan.bytes(page);
        } else {
            plan.copy(firstPage.offset(), firstPage.length());
        }
        var position = firstPage.end();
        var inserted = false;
        for (var page : region) {
            if (page.offset() > position) {
                plan.copy(position, page.offset() - position);
            }
            if (page.serial() == serial) {
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
        // TODO: edit the comment headers of the later links of a chained file, which keep their tags today.
        var delta = newPages.size() - oldTargetPages.size();
        var rest = source.size() - regionEnd;
        if (delta == 0) {
            plan.copy(regionEnd, rest);
        } else if (rest > 0) {
            plan.transform(new OggPages.Renumber(regionEnd, rest, serial, delta));
        }
        return plan.build();
    }

    /**
     * Returns whether new header packets have exactly the lengths of the old ones, so the old
     * page layout can be reused.
     *
     * @param packets the new header packets
     * @return {@code true} when the lengths match one for one
     */
    private boolean sameLengths(List<byte[]> packets) {
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
     * @param parsed the parsed old comment block
     * @param tags the new tags
     * @param options the write options
     * @return the packet
     */
    private byte[] opusTags(VorbisComment.Parsed parsed, MediaTags tags, TagWriteOptions options) {
        var old = headers.getFirst();
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
