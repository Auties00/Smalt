package com.github.auties00.smalt.internal.tags;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.internal.edit.EditPlan;
import com.github.auties00.smalt.internal.io.ByteArrays;
import com.github.auties00.smalt.internal.io.ByteWriter;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.metadata.MediaTags;
import com.github.auties00.smalt.metadata.TagWriteOptions;
import com.github.auties00.smalt.metadata.TrackTags;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;

/**
 * Edits the tags of AVI files: the {@code LIST INFO} chunk of the {@code AVI } form and the
 * {@code strn} name of every stream.
 *
 * <p>The tags are the {@code INFO} entries, read as WAV files read them; the track tags are the
 * {@code strn} names, the track identifiers being the positions of the {@code strl} lists from 1.
 * A write rebuilds the header list with the new names and an {@code INFO} list after it holding
 * the entries {@code INFO} can store (the first value of each identifier). When they fit the
 * space between the start of the header list and the first {@code movi} list (the rest becoming a
 * {@code JUNK} chunk), the edit is in place and no byte from the {@code movi} list on changes;
 * otherwise the media moves by the growth plus the configured padding, and every offset that
 * points past the header list is moved with it: the OpenDML super indexes ({@code indx}), the
 * base offsets of the standard indexes ({@code ix##}) they point to, and the {@code idx1} entries
 * when they are absolute. Pictures and chapters cannot be stored.
 */
final class AviTagFile implements TagFile {
    /**
     * The largest header list read.
     */
    private static final int MAX_HEADER = 16 << 20;

    /**
     * The largest number of chunks read in one list.
     */
    private static final int MAX_CHUNKS = 1 << 16;

    /**
     * The filler chunk identifier.
     */
    private static final String JUNK = "JUNK";

    /**
     * The source.
     */
    private final MediaSource source;

    /**
     * The resource bounds.
     */
    private final MediaLimits limits;

    /**
     * The offset of the {@code hdrl} list.
     */
    private final long hdrlStart;

    /**
     * The offset of the first {@code movi} list, where the region the tags occupy ends.
     */
    private final long moviStart;

    /**
     * The end of the {@code AVI } form.
     */
    private final long formEnd;

    /**
     * The children of the {@code hdrl} list, each complete.
     */
    private final List<byte[]> headerChildren;

    /**
     * The other chunks between the header list and the first {@code movi} list, each complete,
     * without the {@code INFO} lists and the {@code JUNK} chunks.
     */
    private final List<byte[]> otherChunks;

    /**
     * The offset and size of the {@code idx1} chunk, or {@code null}.
     */
    private final long[] idx1;

    /**
     * The decoded tags.
     */
    private final MediaTags tags;

    /**
     * Parses the {@code AVI } form.
     *
     * @param source the AVI file; not closed
     * @param limits the resource bounds
     * @throws MediaException.Malformed if the form or its header list is corrupt
     */
    AviTagFile(MediaSource source, MediaLimits limits) {
        this.source = source;
        this.limits = limits;
        var size = source.size();
        var header = TagIo.read(source, 0, 12, limits);
        if (!TagIo.matches(header, 0, "RIFF") || !TagIo.matches(header, 8, "AVI ")) {
            throw new MediaException.Malformed("not a RIFF AVI file");
        }
        this.formEnd = Math.min(size, 8 + ByteArrays.u32le(header, 4));
        long hdrl = -1;
        long movi = -1;
        long[] index = null;
        var info = new ArrayList<MediaTags.Entry>();
        var others = new ArrayList<byte[]>();
        var position = 12L;
        var count = 0;
        while (formEnd - position >= 8) {
            if (++count > MAX_CHUNKS) {
                throw new MediaException.Malformed("more than " + MAX_CHUNKS + " AVI chunks");
            }
            var chunkHeader = TagIo.read(source, position, Math.min(12, formEnd - position), limits);
            var chunkSize = ByteArrays.u32le(chunkHeader, 4);
            var total = Math.min(8 + chunkSize + (chunkSize & 1), formEnd - position);
            var list = TagIo.matches(chunkHeader, 0, "LIST") && chunkHeader.length >= 12;
            if (list && TagIo.matches(chunkHeader, 8, "movi")) {
                movi = position;
                break;
            }
            if (list && TagIo.matches(chunkHeader, 8, "hdrl") && hdrl < 0) {
                hdrl = position;
            } else if (hdrl >= 0 && list && TagIo.matches(chunkHeader, 8, "INFO")) {
                RiffTagFile.decodeInfo(TagIo.read(source, position + 8, total - 8, limits), false, info);
            } else if (hdrl >= 0 && !TagIo.matches(chunkHeader, 0, JUNK)) {
                if (total > MAX_HEADER) {
                    throw new MediaException.Malformed("oversized AVI header chunk");
                }
                others.add(TagIo.read(source, position, total, limits));
            }
            position += total;
        }
        if (hdrl < 0 || movi < 0) {
            throw new MediaException.Malformed("AVI file without a header list before its movi list");
        }
        position = movi;
        while (formEnd - position >= 8) {
            var chunkHeader = TagIo.read(source, position, 8, limits);
            var chunkSize = ByteArrays.u32le(chunkHeader, 4);
            if (TagIo.matches(chunkHeader, 0, "idx1")) {
                index = new long[]{position, Math.min(chunkSize, formEnd - position - 8)};
                break;
            }
            position += 8 + chunkSize + (chunkSize & 1);
        }
        this.hdrlStart = hdrl;
        this.moviStart = movi;
        this.idx1 = index;
        this.otherChunks = List.copyOf(others);
        var hdrlSize = ByteArrays.u32le(TagIo.read(source, hdrl + 4, 4, limits), 0);
        if (hdrlSize < 4 || hdrlSize > MAX_HEADER || hdrl + 8 + hdrlSize > movi) {
            throw new MediaException.Malformed("corrupt AVI header list");
        }
        var hdrlBody = TagIo.read(source, hdrl + 12, hdrlSize - 4, limits);
        var children = new ArrayList<byte[]>();
        var builder = MediaTags.builder();
        var tracks = 0;
        for (var child : chunks(hdrlBody, 0, hdrlBody.length)) {
            var bytes = Arrays.copyOfRange(hdrlBody, child[0], child[1]);
            if (isList(bytes, "INFO")) {
                RiffTagFile.decodeInfo(Arrays.copyOfRange(bytes, 8, bytes.length), false, info);
                continue;
            }
            children.add(bytes);
            if (isList(bytes, "strl")) {
                tracks++;
                var name = streamName(bytes);
                builder.track(name == null ? TrackTags.of(tracks) : TrackTags.of(tracks, name));
            }
        }
        this.headerChildren = List.copyOf(children);
        for (var entry : info) {
            builder.add(entry.key(), entry.value());
        }
        this.tags = builder.build();
    }

    /**
     * Lists the chunks of a list body.
     *
     * @param data the bytes
     * @param start the offset of the first chunk
     * @param end the end of the body
     * @return the start and end offset of each chunk, its pad byte included
     */
    private static List<int[]> chunks(byte[] data, int start, int end) {
        var result = new ArrayList<int[]>();
        var position = start;
        while (end - position >= 8) {
            if (result.size() >= MAX_CHUNKS) {
                throw new MediaException.Malformed("more than " + MAX_CHUNKS + " AVI header chunks");
            }
            var size = ByteArrays.u32le(data, position + 4);
            var total = (int) Math.min(8 + size + (size & 1), end - position);
            result.add(new int[]{position, position + total});
            position += total;
        }
        return result;
    }

    /**
     * Returns whether a chunk is a list of a form.
     *
     * @param chunk the complete chunk
     * @param form the list form
     * @return {@code true} for a {@code LIST} of that form
     */
    private static boolean isList(byte[] chunk, String form) {
        return chunk.length >= 12 && TagIo.matches(chunk, 0, "LIST") && TagIo.matches(chunk, 8, form);
    }

    /**
     * Decodes the {@code strn} name of a stream list.
     *
     * @param strl the complete {@code strl} list
     * @return the name, or {@code null} when the stream has none
     */
    private static String streamName(byte[] strl) {
        for (var child : chunks(strl, 12, strl.length)) {
            if (TagIo.matches(strl, child[0], "strn")) {
                var size = (int) Math.min(ByteArrays.u32le(strl, child[0] + 4), child[1] - child[0] - 8);
                var name = RiffTagFile.text(Arrays.copyOfRange(strl, child[0] + 8, child[0] + 8 + size));
                return name.isEmpty() ? null : name;
            }
        }
        return null;
    }

    @Override
    public MediaTags read() {
        return tags;
    }

    @Override
    public EditPlan plan(MediaTags newTags, TagWriteOptions options) {
        if (!newTags.pictures().isEmpty() || !newTags.chapters().isEmpty()) {
            throw new MediaException.Unsupported("avi", "AVI files store no pictures or chapters");
        }
        var hdrl = rebuildHeader(newTags);
        var region = new ByteWriter(hdrl.length + 1024);
        region.bytes(hdrl);
        var info = RiffTagFile.infoList(newTags.entries(), false, new HashSet<>());
        if (info != null) {
            region.bytes(info);
        }
        for (var chunk : otherChunks) {
            region.bytes(chunk);
        }
        var oldLength = moviStart - hdrlStart;
        var newLength = region.size();
        if (newLength == oldLength || newLength + 8L <= oldLength && (oldLength - newLength) % 2 == 0) {
            if (newLength < oldLength) {
                region.bytes(RiffTagFile.chunk(false, JUNK, new byte[(int) (oldLength - newLength - 8)]));
            }
            return EditPlan.builder().copy(0, hdrlStart).bytes(region.toByteArray())
                    .copy(moviStart, source.size() - moviStart).build();
        }
        var padding = options.padding() >= 8 ? options.padding() & ~1 : 0;
        if (padding > 0) {
            region.bytes(RiffTagFile.chunk(false, JUNK, new byte[padding - 8]));
        }
        var delta = region.size() - oldLength;
        var relocated = relocate(region.toByteArray(), delta);
        return planShifted(relocated, delta);
    }

    /**
     * Rebuilds the header list with the stream names of the new tags and without {@code INFO}
     * lists.
     *
     * @param newTags the tags to write; a track they hold no tags for keeps its name
     * @return the complete {@code hdrl} list
     */
    private byte[] rebuildHeader(MediaTags newTags) {
        var body = new ByteWriter(4096);
        body.fourCc("hdrl");
        var stream = 0;
        for (var child : headerChildren) {
            if (!isList(child, "strl")) {
                body.bytes(child);
                continue;
            }
            stream++;
            var track = newTags.track(stream);
            if (track.isEmpty()) {
                body.bytes(child);
                continue;
            }
            body.bytes(withName(child, track.get().name().orElse(null)));
        }
        return RiffTagFile.chunk(false, "LIST", body.toByteArray());
    }

    /**
     * Rebuilds a stream list with another {@code strn} name.
     *
     * @param strl the complete {@code strl} list
     * @param name the name, or {@code null} to remove it
     * @return the complete list
     */
    private static byte[] withName(byte[] strl, String name) {
        var strn = name == null || name.isEmpty() ? null : RiffTagFile.chunk(false, "strn", nulTerminated(name));
        var children = chunks(strl, 12, strl.length);
        var named = false;
        var insertAfter = -1;
        for (var index = 0; index < children.size(); index++) {
            var start = children.get(index)[0];
            named |= TagIo.matches(strl, start, "strn");
            if (TagIo.matches(strl, start, "strh") || TagIo.matches(strl, start, "strf")
                    || TagIo.matches(strl, start, "strd")) {
                insertAfter = index;
            }
        }
        var body = new ByteWriter(strl.length + 64);
        body.fourCc("strl");
        if (!named && insertAfter < 0 && strn != null) {
            body.bytes(strn);
        }
        for (var index = 0; index < children.size(); index++) {
            var child = children.get(index);
            if (TagIo.matches(strl, child[0], "strn")) {
                if (strn != null) {
                    body.bytes(strn);
                    strn = null;
                }
                continue;
            }
            body.bytes(strl, child[0], child[1] - child[0]);
            if (!named && index == insertAfter && strn != null) {
                body.bytes(strn);
            }
        }
        return RiffTagFile.chunk(false, "LIST", body.toByteArray());
    }

    /**
     * Encodes a name as {@code strn} stores it.
     *
     * @param name the stream name
     * @return the UTF-8 bytes followed by a NUL
     */
    private static byte[] nulTerminated(String name) {
        var bytes = name.getBytes(StandardCharsets.UTF_8);
        return Arrays.copyOf(bytes, bytes.length + 1);
    }

    /**
     * Moves the offsets the OpenDML indexes of a rebuilt header region hold by the growth of the
     * region.
     *
     * @param region the rebuilt region, which starts with the {@code hdrl} list
     * @param delta the number of bytes the media moves
     * @return the region, its {@code indx} offsets moved
     * @throws MediaException.LimitExceeded if a moved offset no longer fits its field
     */
    private byte[] relocate(byte[] region, long delta) {
        var hdrlSize = (int) ByteArrays.u32le(region, 4);
        for (var child : chunks(region, 12, 8 + hdrlSize)) {
            if (!TagIo.matches(region, child[0], "LIST") || !TagIo.matches(region, child[0] + 8, "strl")) {
                continue;
            }
            for (var part : chunks(region, child[0] + 12, child[1])) {
                if (!TagIo.matches(region, part[0], "indx") || part[1] - part[0] < 8 + 24) {
                    continue;
                }
                var body = part[0] + 8;
                var available = Math.min(ByteArrays.u32le(region, part[0] + 4), part[1] - body);
                var longsPerEntry = ByteArrays.u16le(region, body);
                var type = region[body + 3] & 0xFF;
                var entries = ByteArrays.u32le(region, body + 4);
                if (type == 1) {
                    shift64(region, body + 12, delta);
                } else if (type == 0 && longsPerEntry == 4) {
                    var count = Math.min(entries, (available - 24) / 16);
                    for (var entry = 0; entry < count; entry++) {
                        var at = body + 24 + 16 * entry;
                        if (ByteArrays.i64le(region, at) != 0) {
                            shift64(region, at, delta);
                        }
                    }
                }
            }
        }
        return region;
    }

    /**
     * Adds the growth to a 64-bit little-endian offset.
     *
     * @param data the bytes
     * @param offset the index of the offset
     * @param delta the growth
     */
    private static void shift64(byte[] data, int offset, long delta) {
        ByteArrays.put64le(data, offset, ByteArrays.i64le(data, offset) + delta);
    }

    /**
     * Plans the rewrite that moves the media by the growth of the header region.
     *
     * @param region the rebuilt header region, its {@code indx} offsets moved
     * @param delta the number of bytes the media moves
     * @return the plan
     * @throws MediaException.LimitExceeded if the {@code AVI } form or an {@code idx1} offset outgrows 32 bits
     * @throws MediaException.Unsupported if a super index points at something that is not a standard index
     */
    private EditPlan planShifted(byte[] region, long delta) {
        var formSize = formEnd - 8 + delta;
        if (formSize > 0xFFFFFFFFL) {
            throw new MediaException.LimitExceeded("the tagged AVI form exceeds the 4 GiB a RIFF size can hold");
        }
        var patches = new TreeMap<Long, byte[]>();
        for (var target : standardIndexes(region, delta)) {
            var header = TagIo.read(source, target, 8 + 24, limits);
            if (!isStandardIndex(header)) {
                throw new MediaException.Unsupported("avi", "an OpenDML super index points at offset " + target
                        + ", which holds no standard index, so the media cannot be moved");
            }
            var base = Arrays.copyOfRange(header, 8 + 12, 8 + 20);
            ByteArrays.put64le(base, 0, ByteArrays.i64le(base, 0) + delta);
            patches.put(target + 8 + 12, base);
        }
        if (idx1 != null && isAbsolute()) {
            var entries = TagIo.read(source, idx1[0] + 8, idx1[1] - idx1[1] % 16, limits);
            for (var at = 0; at + 16 <= entries.length; at += 16) {
                var moved = ByteArrays.u32le(entries, at + 8) + delta;
                if (moved > 0xFFFFFFFFL) {
                    throw new MediaException.LimitExceeded("an absolute idx1 offset outgrows 32 bits");
                }
                ByteArrays.put32le(entries, at + 8, moved);
            }
            patches.put(idx1[0] + 8, entries);
        }
        var size = new byte[4];
        ByteArrays.put32le(size, 0, formSize);
        var plan = EditPlan.builder().copy(0, 4).bytes(size).copy(8, hdrlStart - 8).bytes(region);
        var position = moviStart;
        for (var patch : patches.entrySet()) {
            if (patch.getKey() < position) {
                throw new MediaException.Malformed("overlapping AVI index chunks");
            }
            plan.copy(position, patch.getKey() - position).bytes(patch.getValue());
            position = patch.getKey() + patch.getValue().length;
        }
        plan.copy(position, source.size() - position);
        return plan.build();
    }

    /**
     * Returns whether a chunk header and index header describe an OpenDML standard index.
     *
     * @param header the chunk header and the first 24 bytes of its payload
     * @return {@code true} for an {@code AVI_INDEX_OF_CHUNKS} chunk named {@code ix##} or {@code ##ix}
     */
    private static boolean isStandardIndex(byte[] header) {
        var named = header[0] == 'i' && header[1] == 'x' || header[2] == 'i' && header[3] == 'x';
        return named && (header[8 + 3] & 0xFF) == 1;
    }

    /**
     * Collects the original offsets of the standard indexes the super indexes of the rebuilt header point to.
     *
     * @param region the rebuilt region, its offsets already moved
     * @param delta the growth subtracted to find the original offsets
     * @return the offsets of the chunk headers of the standard indexes, without duplicates
     */
    private Set<Long> standardIndexes(byte[] region, long delta) {
        var result = new LinkedHashSet<Long>();
        var hdrlSize = (int) ByteArrays.u32le(region, 4);
        for (var child : chunks(region, 12, 8 + hdrlSize)) {
            if (!TagIo.matches(region, child[0], "LIST") || !TagIo.matches(region, child[0] + 8, "strl")) {
                continue;
            }
            for (var part : chunks(region, child[0] + 12, child[1])) {
                if (!TagIo.matches(region, part[0], "indx") || part[1] - part[0] < 8 + 24) {
                    continue;
                }
                var body = part[0] + 8;
                var available = Math.min(ByteArrays.u32le(region, part[0] + 4), part[1] - body);
                var type = region[body + 3] & 0xFF;
                if (type != 0 || ByteArrays.u16le(region, body) != 4) {
                    continue;
                }
                var count = Math.min(ByteArrays.u32le(region, body + 4), (available - 24) / 16);
                for (var entry = 0; entry < count; entry++) {
                    var offset = ByteArrays.i64le(region, body + 24 + 16 * entry);
                    if (offset != 0) {
                        var original = offset - delta;
                        if (original < moviStart || original + 8 + 24 > source.size()) {
                            throw new MediaException.Malformed("an OpenDML super index points outside the media");
                        }
                        result.add(original);
                    }
                }
            }
        }
        return result;
    }

    /**
     * Returns whether the {@code idx1} offsets are absolute file offsets rather than relative to
     * the {@code movi} list: whether the first entry's offset names a chunk of its identifier from
     * the start of the file but not from the {@code movi} list.
     *
     * @return {@code true} for absolute offsets
     */
    private boolean isAbsolute() {
        if (idx1[1] < 16) {
            return false;
        }
        var entry = TagIo.read(source, idx1[0] + 8, 16, limits);
        var offset = ByteArrays.u32le(entry, 8);
        var movi = moviStart + 8;
        if (namesChunk(movi + offset, entry)) {
            return false;
        }
        return namesChunk(offset, entry);
    }

    /**
     * Returns whether a chunk with the identifier of an index entry starts at an offset.
     *
     * @param position the candidate offset
     * @param entry the index entry, its identifier first
     * @return {@code true} when it does
     */
    private boolean namesChunk(long position, byte[] entry) {
        if (position < 0 || position + 8 > source.size()) {
            return false;
        }
        var header = TagIo.read(source, position, 4, limits);
        return Arrays.equals(header, 0, 4, entry, 0, 4);
    }
}
