package com.github.auties00.smalt.internal.tags;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.internal.io.ByteArrays;
import com.github.auties00.smalt.internal.io.ByteWriter;
import com.github.auties00.smalt.internal.edit.EditPlan;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.metadata.MediaTags;
import com.github.auties00.smalt.metadata.StandardTag;
import com.github.auties00.smalt.metadata.TagWriteOptions;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Edits the tags of WAV (RIFF, RF64, BW64) and AIFF files.
 *
 * <p>WAV files carry text in a {@code LIST} chunk of form {@code INFO} (one sub-chunk per
 * four-character identifier, {@code INAM}, {@code IART}, ...) and full tags in an {@code id3 }
 * chunk holding an ID3v2 tag; AIFF files carry text in {@code NAME}, {@code AUTH},
 * {@code (c) } and {@code ANNO} chunks and full tags in an {@code ID3 } chunk. {@code INFO} text
 * is written in UTF-8, as ffmpeg does; AIFF text chunks are ISO-8859-1, so a value with other
 * characters goes to the ID3v2 chunk only. Reading merges
 * both, the ID3v2 values first. Writing stores the values the text chunks can hold in them and,
 * when the file already had an ID3v2 chunk or the tags need one (pictures, chapters, other keys),
 * every tag in an ID3v2 chunk.
 *
 * <p>The new chunks reuse the space of the old ones when they fit (the rest becoming a
 * {@code JUNK} chunk), or replace them at the end of the file when the old metadata chunks were
 * the last chunks; otherwise the file is rewritten with the metadata chunks before the audio
 * chunk, followed by a {@code JUNK} chunk of the configured padding. The audio chunk never
 * changes.
 */
final class RiffTagFile implements TagFile {
    /**
     * The largest number of chunks read.
     */
    private static final int MAX_CHUNKS = 1 << 16;

    /**
     * The filler chunk identifier.
     */
    private static final String JUNK = "JUNK";

    /**
     * The AIFF text chunks and the standard tags they hold.
     */
    private static final String[][] AIFF_TEXT = {{"NAME", "title"}, {"AUTH", "artist"}, {"(c) ", "copyright"},
            {"ANNO", "comment"}};

    /**
     * The source.
     */
    private final MediaSource source;

    /**
     * The resource bounds.
     */
    private final MediaLimits limits;

    /**
     * Whether the file is AIFF (big-endian sizes) rather than WAV.
     */
    private final boolean aiff;

    /**
     * Whether the file is RF64 or BW64, whose RIFF size lives in the {@code ds64} chunk.
     */
    private final boolean rf64;

    /**
     * The chunks after the twelve-byte header, in file order.
     */
    private final List<Chunk> chunks;

    /**
     * The offset where the chunk list ends.
     */
    private final long chunksEnd;

    /**
     * The decoded tags.
     */
    private final MediaTags tags;

    /**
     * The ID3v2 tag of the file, or {@code null}.
     */
    private final Id3v2Tag id3;

    /**
     * Parses the chunk list and the metadata chunks.
     *
     * @param source the WAV or AIFF file; not closed
     * @param limits the resource bounds
     * @throws MediaException.Malformed if the header or the chunk list is corrupt
     */
    RiffTagFile(MediaSource source, MediaLimits limits) {
        this.source = source;
        this.limits = limits;
        var size = source.size();
        var header = TagIo.read(source, 0, 12, limits);
        this.aiff = TagIo.matches(header, 0, "FORM");
        this.rf64 = TagIo.matches(header, 0, "RF64") || TagIo.matches(header, 0, "BW64");
        if (!aiff && !TagIo.matches(header, 0, "RIFF") && !rf64) {
            throw new MediaException.Unsupported("wav", "big-endian RIFX files cannot be tagged");
        }
        var list = new ArrayList<Chunk>();
        var position = 12L;
        long dataSize64 = -1;
        while (size - position >= 8) {
            if (list.size() >= MAX_CHUNKS) {
                throw new MediaException.Malformed("more than " + MAX_CHUNKS + " RIFF chunks");
            }
            var chunkHeader = TagIo.read(source, position, 8, limits);
            var id = new String(chunkHeader, 0, 4, StandardCharsets.ISO_8859_1);
            long chunkSize = aiff ? ByteArrays.u32be(chunkHeader, 4) : ByteArrays.u32le(chunkHeader, 4);
            if (rf64 && id.equals("ds64") && chunkSize >= 24) {
                var ds64 = TagIo.read(source, position + 8, 24, limits);
                dataSize64 = ByteArrays.i64le(ds64, 8);
            }
            if (rf64 && id.equals("data") && chunkSize == 0xFFFFFFFFL && dataSize64 >= 0) {
                chunkSize = dataSize64;
            }
            var total = 8 + chunkSize + (chunkSize & 1);
            if (chunkSize > size - position - 8) {
                list.add(new Chunk(id, position, size - position, size - position - 8));
                position = size;
                break;
            }
            list.add(new Chunk(id, position, Math.min(total, size - position), chunkSize));
            position += Math.min(total, size - position);
        }
        this.chunks = List.copyOf(list);
        this.chunksEnd = position;
        Id3v2Tag found = null;
        var info = new ArrayList<MediaTags.Entry>();
        for (var chunk : chunks) {
            if (isId3(chunk) && found == null && chunk.payloadSize() >= Id3v2Tag.HEADER_SIZE) {
                var bytes = TagIo.read(source, chunk.offset() + 8, chunk.payloadSize(), limits);
                if (Id3v2Tag.size(bytes, 0) >= 0) {
                    found = Id3v2Tag.parse(bytes, limits);
                }
            } else if (isInfo(chunk)) {
                decodeInfo(TagIo.read(source, chunk.offset() + 8, chunk.payloadSize(), limits), info);
            } else if (aiff && aiffKey(chunk.id()) != null) {
                var text = text(TagIo.read(source, chunk.offset() + 8, chunk.payloadSize(), limits));
                if (!text.isBlank()) {
                    info.add(new MediaTags.Entry(aiffKey(chunk.id()), text));
                }
            }
        }
        this.id3 = found;
        var builder = MediaTags.builder();
        var keys = new HashSet<String>();
        if (found != null) {
            var content = found.decode();
            for (var entry : content.entries()) {
                builder.add(entry.key(), entry.value());
                keys.add(entry.key().toLowerCase(Locale.ROOT));
            }
            builder.pictures(content.pictures()).chapters(content.chapters());
        }
        for (var entry : info) {
            if (!keys.contains(entry.key().toLowerCase(Locale.ROOT))) {
                builder.add(entry.key(), entry.value());
            }
        }
        this.tags = builder.build();
    }

    /**
     * Returns whether a chunk holds an ID3v2 tag.
     *
     * @param chunk a top-level chunk
     * @return {@code true} for {@code id3 } and {@code ID3 }
     */
    private static boolean isId3(Chunk chunk) {
        return chunk.id().equals("id3 ") || chunk.id().equals("ID3 ");
    }

    /**
     * Returns whether a chunk is a {@code LIST} of form {@code INFO}.
     *
     * @param chunk a top-level chunk
     * @return {@code true} for an {@code INFO} list
     */
    private boolean isInfo(Chunk chunk) {
        if (aiff || !chunk.id().equals("LIST") || chunk.payloadSize() < 4) {
            return false;
        }
        var form = TagIo.readAvailable(source, chunk.offset() + 8, 4);
        return TagIo.matches(form, 0, "INFO");
    }

    /**
     * Returns whether a chunk is one of the metadata chunks this editor replaces.
     *
     * @param chunk a top-level chunk
     * @return {@code true} for ID3v2, {@code INFO} and AIFF text chunks
     */
    private boolean isMetadata(Chunk chunk) {
        return isId3(chunk) || isInfo(chunk) || (aiff && aiffKey(chunk.id()) != null);
    }

    /**
     * Returns the key an AIFF text chunk holds.
     *
     * @param id the chunk identifier
     * @return the key, or {@code null} for another chunk
     */
    private static String aiffKey(String id) {
        for (var pair : AIFF_TEXT) {
            if (pair[0].equals(id)) {
                return pair[1];
            }
        }
        return null;
    }

    /**
     * Decodes the sub-chunks of an {@code INFO} list.
     *
     * @param payload the list payload, starting with {@code INFO}
     * @param entries receives the entries
     */
    private static void decodeInfo(byte[] payload, List<MediaTags.Entry> entries) {
        var position = 4;
        while (payload.length - position >= 8) {
            var id = new String(payload, position, 4, StandardCharsets.ISO_8859_1);
            var size = ByteArrays.u32le(payload, position + 4);
            position += 8;
            if (size > payload.length - position) {
                return;
            }
            var text = text(java.util.Arrays.copyOfRange(payload, position, position + (int) size));
            position += (int) (size + (size & 1));
            if (text.isBlank()) {
                continue;
            }
            try {
                entries.add(new MediaTags.Entry(id.strip(), text));
            } catch (IllegalArgumentException ignored) {
                // an identifier with control characters is dropped
            }
        }
    }

    /**
     * Decodes chunk text: UTF-8 when valid, ISO-8859-1 otherwise, up to the first NUL.
     *
     * @param bytes the text bytes
     * @return the text
     */
    private static String text(byte[] bytes) {
        var end = 0;
        while (end < bytes.length && bytes[end] != 0) {
            end++;
        }
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, 0, end)).toString().strip();
        } catch (CharacterCodingException exception) {
            return new String(bytes, 0, end, StandardCharsets.ISO_8859_1).strip();
        }
    }

    @Override
    public MediaTags read() {
        return tags;
    }

    @Override
    public EditPlan plan(MediaTags newTags, TagWriteOptions options) {
        var textChunks = new ArrayList<byte[]>();
        var textKeys = new HashSet<String>();
        var allText = encodeText(aiff, newTags, textChunks, textKeys);
        var needsId3 = id3 != null || !newTags.pictures().isEmpty() || !newTags.chapters().isEmpty() || !allText;
        var newChunks = new ArrayList<byte[]>(textChunks);
        if (needsId3 && !newTags.isEmpty()) {
            var major = options.id3Version().map(version -> version == TagWriteOptions.Id3Version.V2_3 ? 3 : 4)
                    .orElse(id3 != null && id3.major() == 3 ? 3 : 4);
            var preserved = id3 != null && options.preserveUnknown() ? id3.decode().opaque() : List.<Id3v2Tag.Frame>of();
            newChunks.add(chunk(aiff, aiff ? "ID3 " : "id3 ", Id3v2Tag.encode(newTags, preserved, null, major, 0, 0)));
        }
        var metadataIndexes = new ArrayList<Integer>();
        for (var i = 0; i < chunks.size(); i++) {
            if (isMetadata(chunks.get(i))) {
                metadataIndexes.add(i);
            }
        }
        var inSlots = planSlots(newChunks, metadataIndexes);
        if (inSlots != null) {
            return inSlots;
        }
        var atEnd = planAtEnd(newChunks, metadataIndexes);
        if (atEnd != null) {
            return atEnd;
        }
        return planRewrite(newChunks, options);
    }

    /**
     * Encodes the text chunks.
     *
     * @param aiff whether the chunks are AIFF text chunks rather than a WAV {@code INFO} list
     * @param tags the tags to store
     * @param out receives the chunks, each complete with its header
     * @param stored receives the lower-case keys stored
     * @return {@code true} when every entry fits a text chunk
     */
    static boolean encodeText(boolean aiff, MediaTags tags, List<byte[]> out, Set<String> stored) {
        var all = true;
        if (aiff) {
            for (var entry : tags.entries()) {
                String id = null;
                for (var pair : AIFF_TEXT) {
                    if (pair[1].equals(entry.key()) && (pair[0].equals("ANNO") || !stored.contains(entry.key()))) {
                        id = pair[0];
                    }
                }
                if (id == null || !StandardCharsets.ISO_8859_1.newEncoder().canEncode(entry.value())) {
                    all = false;
                    continue;
                }
                stored.add(entry.key());
                out.add(chunk(true, id, entry.value().getBytes(StandardCharsets.ISO_8859_1)));
            }
            return all;
        }
        var info = new ByteWriter(256);
        info.fourCc("INFO");
        var any = false;
        for (var entry : tags.entries()) {
            String id = null;
            var standard = StandardTag.fromName(entry.key());
            if (standard.isPresent() && standard.get().riffInfo().isPresent()) {
                id = standard.get().riffInfo().get();
            } else if (standard.isEmpty() && entry.key().matches("I[A-Z0-9]{3}")) {
                id = entry.key();
            }
            if (id == null || !stored.add(id)) {
                all = false;
                continue;
            }
            var text = entry.value().getBytes(StandardCharsets.UTF_8);
            info.fourCc(id);
            info.u32le(text.length + 1L);
            info.bytes(text);
            info.u8(0);
            if (((text.length + 1) & 1) != 0) {
                info.u8(0);
            }
            any = true;
        }
        if (any) {
            out.add(chunk(false, "LIST", info.toByteArray()));
        }
        return all;
    }

    /**
     * Builds a chunk with its header and pad byte.
     *
     * @param aiff whether the size is big-endian (AIFF) rather than little-endian (RIFF)
     * @param id the identifier
     * @param payload the chunk payload
     * @return the chunk
     */
    static byte[] chunk(boolean aiff, String id, byte[] payload) {
        var out = new ByteWriter(payload.length + 9);
        out.fourCc(id);
        if (aiff) {
            out.u32be(payload.length);
        } else {
            out.u32le(payload.length);
        }
        out.bytes(payload);
        if ((payload.length & 1) != 0) {
            out.u8(0);
        }
        return out.toByteArray();
    }

    /**
     * Returns a filler chunk of an exact total size.
     *
     * @param total the size, at least 8 and even
     * @return the chunk
     */
    private byte[] filler(long total) {
        return chunk(aiff, JUNK, new byte[(int) (total - 8)]);
    }

    /**
     * Plans an edit that writes the new chunks into the slots of the old metadata chunks and the
     * filler chunks that follow them.
     *
     * @param newChunks the new metadata chunks
     * @param metadataIndexes the indexes of the old metadata chunks
     * @return the plan, or {@code null} when the chunks do not fit
     */
    private EditPlan planSlots(List<byte[]> newChunks, List<Integer> metadataIndexes) {
        if (metadataIndexes.isEmpty()) {
            return null;
        }
        var slots = new ArrayList<long[]>();
        var used = new boolean[chunks.size()];
        for (var index : metadataIndexes) {
            if (used[index]) {
                continue;
            }
            var start = chunks.get(index).offset();
            var length = chunks.get(index).total();
            used[index] = true;
            var next = index + 1;
            while (next < chunks.size() && !used[next] && (isMetadata(chunks.get(next))
                    || chunks.get(next).id().equals(JUNK))) {
                length += chunks.get(next).total();
                used[next] = true;
                next++;
            }
            slots.add(new long[]{start, length});
        }
        var assigned = new ArrayList<List<byte[]>>();
        for (var ignored : slots) {
            assigned.add(new ArrayList<>());
        }
        var remaining = new long[slots.size()];
        for (var i = 0; i < slots.size(); i++) {
            remaining[i] = slots.get(i)[1];
        }
        for (var chunk : newChunks) {
            var placed = false;
            for (var i = 0; i < slots.size() && !placed; i++) {
                if (remaining[i] == chunk.length || remaining[i] >= chunk.length + 8L) {
                    assigned.get(i).add(chunk);
                    remaining[i] -= chunk.length;
                    placed = true;
                }
            }
            if (!placed) {
                return null;
            }
        }
        var plan = EditPlan.builder();
        var position = 0L;
        for (var i = 0; i < slots.size(); i++) {
            plan.copy(position, slots.get(i)[0] - position);
            for (var chunk : assigned.get(i)) {
                plan.bytes(chunk);
            }
            if (remaining[i] > 0) {
                plan.bytes(filler(remaining[i]));
            }
            position = slots.get(i)[0] + slots.get(i)[1];
        }
        plan.copy(position, source.size() - position);
        return plan.build();
    }

    /**
     * Plans an edit that replaces the metadata chunks ending the file with the new ones, moving
     * no audio.
     *
     * @param newChunks the new metadata chunks
     * @param metadataIndexes the indexes of the old metadata chunks
     * @return the plan, or {@code null} when a metadata chunk precedes a chunk that must stay
     */
    private EditPlan planAtEnd(List<byte[]> newChunks, List<Integer> metadataIndexes) {
        var first = chunks.size();
        for (var i = chunks.size() - 1; i >= 0; i--) {
            if (isMetadata(chunks.get(i)) || chunks.get(i).id().equals(JUNK)) {
                first = i;
            } else {
                break;
            }
        }
        for (var index : metadataIndexes) {
            if (index < first) {
                return null;
            }
        }
        if (first == chunks.size() && metadataIndexes.isEmpty() && newChunks.isEmpty()) {
            return EditPlan.builder().copy(0, source.size()).build();
        }
        var tailStart = first < chunks.size() ? chunks.get(first).offset() : chunksEnd;
        if (first == chunks.size() && chunksEnd != source.size()) {
            return null;
        }
        var added = 0L;
        for (var chunk : newChunks) {
            added += chunk.length;
        }
        var plan = EditPlan.builder();
        appendWithSize(plan, tailStart, tailStart + added);
        for (var chunk : newChunks) {
            plan.bytes(chunk);
        }
        return plan.build();
    }

    /**
     * Copies the file up to an offset, rewriting the container size for a new file length.
     *
     * @param plan receives the copy
     * @param end the offset up to which the original is copied
     * @param newLength the length of the new file
     */
    private void appendWithSize(EditPlan.Builder plan, long end, long newLength) {
        var riffSize = newLength - 8;
        if (rf64) {
            var ds64 = chunks.isEmpty() || !chunks.getFirst().id().equals("ds64") ? null : chunks.getFirst();
            if (ds64 == null || ds64.payloadSize() < 8) {
                throw new MediaException.Malformed("RF64 file without a ds64 chunk");
            }
            plan.copy(0, ds64.offset() + 8);
            var size = new byte[8];
            ByteArrays.put32le(size, 0, riffSize);
            ByteArrays.put32le(size, 4, riffSize >>> 32);
            plan.bytes(size);
            plan.copy(ds64.offset() + 16, end - ds64.offset() - 16);
            return;
        }
        if (riffSize > 0xFFFFFFFFL) {
            throw new MediaException.LimitExceeded("the tagged file exceeds the 4 GiB a RIFF size can hold");
        }
        plan.copy(0, 4);
        var size = new byte[4];
        if (aiff) {
            ByteArrays.put32be(size, 0, riffSize);
        } else {
            ByteArrays.put32le(size, 0, riffSize);
        }
        plan.bytes(size);
        plan.copy(8, end - 8);
    }

    /**
     * Plans a rewrite with the metadata chunks before the audio chunk.
     *
     * @param newChunks the new metadata chunks
     * @param options the write options
     * @return the plan
     */
    private EditPlan planRewrite(List<byte[]> newChunks, TagWriteOptions options) {
        var audio = aiff ? "SSND" : "data";
        var padding = options.padding() >= 8 ? options.padding() & ~1 : 0;
        var kept = new ArrayList<Chunk>();
        for (var chunk : chunks) {
            if (!isMetadata(chunk)) {
                kept.add(chunk);
            }
        }
        var length = 12L;
        var inserted = false;
        for (var chunk : kept) {
            if (!inserted && chunk.id().equals(audio)) {
                for (var bytes : newChunks) {
                    length += bytes.length;
                }
                length += padding;
                inserted = true;
            }
            length += chunk.total();
        }
        if (!inserted) {
            for (var bytes : newChunks) {
                length += bytes.length;
            }
        }
        length += source.size() - chunksEnd;
        var plan = EditPlan.builder();
        var header = TagIo.read(source, 0, 12, limits);
        var riffSize = length - 8;
        if (!rf64) {
            if (riffSize > 0xFFFFFFFFL) {
                throw new MediaException.LimitExceeded("the tagged file exceeds the 4 GiB a RIFF size can hold");
            }
            if (aiff) {
                ByteArrays.put32be(header, 4, riffSize);
            } else {
                ByteArrays.put32le(header, 4, riffSize);
            }
        }
        plan.bytes(header);
        inserted = false;
        for (var chunk : kept) {
            if (!inserted && chunk.id().equals(audio)) {
                for (var bytes : newChunks) {
                    plan.bytes(bytes);
                }
                if (padding > 0) {
                    plan.bytes(filler(padding));
                }
                inserted = true;
            }
            if (rf64 && chunk.id().equals("ds64") && chunk.payloadSize() >= 8) {
                var ds64 = TagIo.read(source, chunk.offset(), chunk.total(), limits);
                ByteArrays.put32le(ds64, 8, riffSize);
                ByteArrays.put32le(ds64, 12, riffSize >>> 32);
                plan.bytes(ds64);
                continue;
            }
            plan.copy(chunk.offset(), chunk.total());
        }
        if (!inserted) {
            for (var bytes : newChunks) {
                plan.bytes(bytes);
            }
        }
        plan.copy(chunksEnd, source.size() - chunksEnd);
        return plan.build();
    }

    /**
     * Locates one chunk.
     *
     * @param id the identifier
     * @param offset the offset of the chunk header
     * @param total the size of header, payload and pad byte present in the file
     * @param payloadSize the payload size
     */
    private record Chunk(String id, long offset, long total, long payloadSize) {
    }
}
