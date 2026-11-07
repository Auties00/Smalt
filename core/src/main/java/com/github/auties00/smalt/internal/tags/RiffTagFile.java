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
import com.github.auties00.smalt.metadata.XmpPacket;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Edits the tags of WAV (RIFF, big-endian RIFX, RF64, BW64) and AIFF files.
 *
 * <p>WAV files carry text in a {@code LIST} chunk of form {@code INFO} (one sub-chunk per
 * four-character identifier, {@code INAM}, {@code IART}, ...) and full tags in an {@code id3 }
 * chunk holding an ID3v2 tag; AIFF files carry text in {@code NAME}, {@code AUTH},
 * {@code (c) } and {@code ANNO} chunks and full tags in an {@code ID3 } chunk. The descriptive chunks
 * {@link RiffMetadataChunks} maps (Broadcast Wave, sampler and cue point chunks of WAV, marker, instrument
 * and comment chunks of AIFF) are read into their keys and the chapters, which the ID3v2 chapters
 * take precedence over, and are rewritten from them; their keys go to no other chunk. {@code INFO} text
 * is written in UTF-8, as ffmpeg does; AIFF text chunks are ISO-8859-1, so a value with other
 * characters goes to the ID3v2 chunk only. Reading merges
 * both, the ID3v2 values first. Writing stores the values the text chunks can hold in them and,
 * when the file already had an ID3v2 chunk or the tags need one (pictures, chapters, other keys),
 * every tag in an ID3v2 chunk.
 *
 * <p>The XMP packet (XMP Specification Part 3) is read from a WAV {@code _PMX} chunk or an AIFF {@code APPL} chunk of
 * application signature {@code XMP }, or, failing one, from a {@code PRIV} frame of the ID3v2 chunk. A rewrite keeps
 * the chunk while the packet is unchanged, writes a packet the file kept only in its ID3v2 chunk back there, and
 * any other packet in a {@code _PMX} or {@code APPL} chunk.
 *
 * <p>The new chunks reuse the space of the old ones when they fit (the rest becoming a
 * {@code JUNK} chunk), or replace them at the end of the file when the old metadata chunks were
 * the last chunks; otherwise the file is rewritten with the metadata chunks before the audio
 * chunk, followed by a {@code JUNK} chunk of the configured padding. The audio chunk never
 * changes. A RIFF file the edit takes past the 4 GiB its 32-bit sizes can describe becomes an
 * RF64 file (EBU Tech 3306): the header says {@code RF64}, a {@code ds64} chunk holding the
 * 64-bit RIFF and {@code data} sizes takes the place of a leading {@code JUNK} chunk (the
 * placeholder EBU Tech 3306 recommends) or is inserted first, and the 32-bit sizes it replaces
 * hold {@code 0xFFFFFFFF}. A RIFX or AIFF file cannot grow past 4 GiB.
 */
final class RiffTagFile implements TagFile {
    /**
     * The largest number of chunks read.
     */
    private static final int MAX_CHUNKS = 1 << 16;

    /**
     * The size of the {@code ds64} chunk written when a file becomes RF64: the header, the 64-bit
     * RIFF size, data size and sample count, and an empty table.
     */
    private static final int DS64_TOTAL = 8 + 28;

    /**
     * The prefix of the key of an {@code INFO} identifier that names another standard tag elsewhere.
     */
    private static final String INFO_PREFIX = "INFO:";

    /**
     * The filler chunk identifier.
     */
    private static final String JUNK = "JUNK";

    /**
     * The largest descriptive chunk read.
     */
    private static final int MAX_DESCRIPTIVE = 16 << 20;

    /**
     * The identifier of the WAV chunk holding XMP.
     */
    private static final String WAVE_XMP = "_PMX";

    /**
     * The identifier of the AIFF application chunk.
     */
    private static final String AIFF_APPLICATION = "APPL";

    /**
     * The application signature of the AIFF {@code APPL} chunk holding XMP.
     */
    private static final String AIFF_XMP = "XMP ";

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
     * Whether the file is AIFF rather than WAV.
     */
    private final boolean aiff;

    /**
     * Whether the file is a big-endian RIFX WAV file.
     */
    private final boolean rifx;

    /**
     * Whether chunk sizes are big-endian (AIFF and RIFX).
     */
    private final boolean bigEndian;

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
     * The offsets of the {@code _PMX} and {@code APPL} chunks that hold an XMP packet.
     */
    private final Set<Long> xmpChunks = new HashSet<>();

    /**
     * The XMP packet of the first chunk of {@link #xmpChunks}, or {@code null}.
     */
    private XmpPacket xmp;

    /**
     * The first chunk of {@link #xmpChunks} as stored, or {@code null}.
     */
    private byte[] xmpChunk;

    /**
     * The sampling rate of the audio, in hertz, or 0 when unknown.
     */
    private final double sampleRate;

    /**
     * The block alignment of a WAV file's {@code fmt } chunk, 0 until read.
     */
    private int blockAlign;

    /**
     * The frame count of an AIFF file's {@code COMM} chunk, 0 until read.
     */
    private long frames;

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
        this.rifx = TagIo.matches(header, 0, "RIFX");
        this.bigEndian = aiff || rifx;
        if (!aiff && !rifx && !rf64 && !TagIo.matches(header, 0, "RIFF")) {
            throw new MediaException.Malformed("not a RIFF, RIFX, RF64 or AIFF file");
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
            long chunkSize = ByteArrays.u32(chunkHeader, 4, !bigEndian);
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
        var owned = new ArrayList<MediaTags.Entry>();
        var comments = new ArrayList<MediaTags.Entry>();
        byte[] cue = null;
        byte[] adtl = null;
        byte[] mark = null;
        byte[] inst = null;
        for (var chunk : chunks) {
            var descriptive = descriptive(chunk);
            if (descriptive != null) {
                if (chunk.payloadSize() > MAX_DESCRIPTIVE) {
                    continue;
                }
                var payload = TagIo.read(source, chunk.offset() + 8, chunk.payloadSize(), limits);
                switch (descriptive) {
                    case "bext" -> RiffMetadataChunks.decodeBext(payload, !bigEndian, owned);
                    case "iXML", "axml" -> RiffMetadataChunks.decodeXml(descriptive, payload, owned);
                    case "chna" -> RiffMetadataChunks.decodeChna(payload, !bigEndian, owned);
                    case "smpl" -> RiffMetadataChunks.decodeSmpl(payload, !bigEndian, owned);
                    case "cue " -> cue = cue == null ? payload : cue;
                    case "adtl" -> adtl = adtl == null ? payload : adtl;
                    case "MARK" -> mark = mark == null ? payload : mark;
                    case "INST" -> inst = inst == null ? payload : inst;
                    default -> RiffMetadataChunks.decodeComments(payload, comments);
                }
                continue;
            }
            if (xmpCandidate(chunk) && chunk.payloadSize() <= MAX_DESCRIPTIVE) {
                readXmp(chunk);
            } else if (isId3(chunk) && found == null && chunk.payloadSize() >= Id3v2Tag.HEADER_SIZE) {
                var bytes = TagIo.read(source, chunk.offset() + 8, chunk.payloadSize(), limits);
                if (Id3v2Tag.size(bytes, 0) >= 0) {
                    found = Id3v2Tag.parse(bytes, limits);
                }
            } else if (isInfo(chunk)) {
                decodeInfo(TagIo.read(source, chunk.offset() + 8, chunk.payloadSize(), limits), bigEndian, info);
            } else if (aiff && aiffKey(chunk.id()) != null) {
                var text = text(TagIo.read(source, chunk.offset() + 8, chunk.payloadSize(), limits));
                if (!text.isBlank()) {
                    info.add(new MediaTags.Entry(aiffKey(chunk.id()), text));
                }
            }
        }
        this.id3 = found;
        this.sampleRate = sampleRate();
        var duration = durationMicros(sampleRate);
        var markers = RiffMetadataChunks.decodeMarkers(mark == null ? new byte[0] : mark);
        var loopMarkers = new HashSet<Integer>();
        if (inst != null) {
            RiffMetadataChunks.decodeInst(inst, markers, owned, loopMarkers);
        }
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
        var seen = new HashSet<String>();
        for (var entry : builder.build().getAll(StandardTag.COMMENT)) {
            seen.add(entry);
        }
        for (var comment : comments) {
            if (seen.add(comment.value())) {
                builder.add(comment.key(), comment.value());
            }
        }
        for (var entry : owned) {
            if (!keys.contains(entry.key().toLowerCase(Locale.ROOT))) {
                builder.add(entry.key(), entry.value());
            }
        }
        if (xmp != null) {
            builder.xmp(xmp);
        } else if (found != null && found.decode().xmp() != null) {
            builder.xmp(found.decode().xmp());
        }
        if (found == null || found.decode().chapters().isEmpty()) {
            if (aiff) {
                builder.chapters(RiffMetadataChunks.markerChapters(markers, loopMarkers, sampleRate, duration));
            } else if (cue != null) {
                builder.chapters(RiffMetadataChunks.decodeCue(cue, adtl, !bigEndian, (int) sampleRate, duration));
            }
        }
        this.tags = builder.build();
    }

    /**
     * Returns whether a chunk may hold an XMP packet: a WAV {@code _PMX} chunk, or an AIFF {@code APPL} chunk of
     * signature {@code XMP }.
     *
     * @param chunk a top-level chunk
     * @return {@code true} for such a chunk
     */
    private boolean xmpCandidate(Chunk chunk) {
        if (!aiff) {
            return chunk.id().equals(WAVE_XMP);
        }
        return chunk.id().equals(AIFF_APPLICATION) && chunk.payloadSize() >= 4
                && TagIo.matches(TagIo.readAvailable(source, chunk.offset() + 8, 4), 0, AIFF_XMP);
    }

    /**
     * Parses the XMP packet of a chunk, recording the chunk in {@link #xmpChunks} when it holds one.
     *
     * @param chunk a chunk {@link #xmpCandidate(Chunk)} accepts
     */
    private void readXmp(Chunk chunk) {
        var skip = aiff ? 4 : 0;
        var payload = TagIo.read(source, chunk.offset() + 8 + skip, chunk.payloadSize() - skip, limits);
        XmpPacket packet;
        try {
            packet = XmpPacket.parse(payload);
        } catch (MediaException.Malformed exception) {
            return;
        }
        xmpChunks.add(chunk.offset());
        if (xmp == null) {
            xmp = packet;
            xmpChunk = TagIo.read(source, chunk.offset(), chunk.total(), limits);
        }
    }

    /**
     * Encodes the chunk of an XMP packet.
     *
     * @param aiff whether the chunk is an AIFF {@code APPL} chunk rather than a WAV {@code _PMX} chunk
     * @param bigEndian whether the size is big-endian (AIFF and RIFX)
     * @param packet the packet to store
     * @return the chunk, header and pad byte included
     */
    static byte[] xmpChunk(boolean aiff, boolean bigEndian, XmpPacket packet) {
        var bytes = packet.toBytes();
        if (!aiff) {
            return chunk(bigEndian, WAVE_XMP, bytes);
        }
        var payload = new byte[4 + bytes.length];
        System.arraycopy(AIFF_XMP.getBytes(StandardCharsets.US_ASCII), 0, payload, 0, 4);
        System.arraycopy(bytes, 0, payload, 4, bytes.length);
        return chunk(true, AIFF_APPLICATION, payload);
    }

    /**
     * Returns the descriptive chunk a chunk is, whose contents this editor reads into keys or chapters and
     * rewrites from them.
     *
     * @param chunk a top-level chunk
     * @return {@code bext}, {@code iXML}, {@code axml}, {@code chna}, {@code smpl}, {@code cue } or {@code adtl}
     * (a {@code LIST} of that form) for a WAV file, {@code MARK}, {@code INST} or {@code COMT} for an AIFF file,
     * or {@code null}
     */
    private String descriptive(Chunk chunk) {
        if (aiff) {
            return switch (chunk.id()) {
                case "MARK", "INST", "COMT" -> chunk.id();
                default -> null;
            };
        }
        return switch (chunk.id()) {
            case "bext", "iXML", "axml", "chna", "smpl", "cue " -> chunk.id();
            case "LIST" -> {
                if (chunk.payloadSize() < 4) {
                    yield null;
                }
                var form = TagIo.readAvailable(source, chunk.offset() + 8, 4);
                yield TagIo.matches(form, 0, "adtl") ? "adtl" : null;
            }
            default -> null;
        };
    }

    /**
     * {@return the sampling rate of the {@code fmt } or {@code COMM} chunk, in hertz, or 0 without one}
     */
    private double sampleRate() {
        for (var chunk : chunks) {
            if (!aiff && chunk.id().equals("fmt ") && chunk.payloadSize() >= 16) {
                var format = TagIo.read(source, chunk.offset() + 8, 16, limits);
                blockAlign = ByteArrays.u16(format, 12, !bigEndian);
                return ByteArrays.u32(format, 4, !bigEndian);
            }
            if (aiff && chunk.id().equals("COMM") && chunk.payloadSize() >= 18) {
                var common = TagIo.read(source, chunk.offset() + 8, 18, limits);
                frames = ByteArrays.u32be(common, 2);
                return extended(common, 8);
            }
        }
        return 0;
    }

    /**
     * Returns the duration of the audio.
     *
     * @param rate the sampling rate, in hertz
     * @return the duration in microseconds, or {@code -1} when unknown
     */
    private long durationMicros(double rate) {
        if (rate <= 0) {
            return -1;
        }
        if (aiff) {
            return Math.round(frames * 1e6 / rate);
        }
        for (var chunk : chunks) {
            if (chunk.id().equals("data") && blockAlign > 0) {
                return Math.round(chunk.payloadSize() / blockAlign * 1e6 / rate);
            }
        }
        return -1;
    }

    /**
     * Decodes an 80-bit IEEE 754 extended float, the sampling rate of an AIFF {@code COMM} chunk.
     *
     * @param data the bytes
     * @param offset the index of the sign and exponent
     * @return the value, or 0 when it is not a positive finite number
     */
    private static double extended(byte[] data, int offset) {
        var exponent = ByteArrays.u16be(data, offset) & 0x7FFF;
        var mantissa = ByteArrays.i64be(data, offset + 2);
        if ((data[offset] & 0x80) != 0 || exponent == 0x7FFF || mantissa == 0) {
            return 0;
        }
        return Math.scalb((double) (mantissa >>> 11), exponent - 16383 - 52);
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
     * @return {@code true} for ID3v2, {@code INFO}, AIFF text chunks, the descriptive chunks and the chunks holding
     * XMP
     */
    private boolean isMetadata(Chunk chunk) {
        return isId3(chunk) || isInfo(chunk) || (aiff && aiffKey(chunk.id()) != null) || descriptive(chunk) != null
                || xmpChunks.contains(chunk.offset());
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
     * @param bigEndian whether the sub-chunk sizes are big-endian (RIFX)
     * @param entries receives the entries
     */
    static void decodeInfo(byte[] payload, boolean bigEndian, List<MediaTags.Entry> entries) {
        var position = 4;
        while (payload.length - position >= 8) {
            var id = new String(payload, position, 4, StandardCharsets.ISO_8859_1);
            var size = ByteArrays.u32(payload, position + 4, !bigEndian);
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
                entries.add(new MediaTags.Entry(infoKey(id.strip()), text));
            } catch (IllegalArgumentException ignored) {
                // an identifier with control characters is dropped
            }
        }
    }

    /**
     * Returns the key an {@code INFO} identifier is read under.
     *
     * @param id the identifier
     * @return the identifier, or, for an {@code INFO} identifier that names a standard tag {@code INFO} has no
     * identifier for ({@code ISRC}, the RIFF source, is not the ISRC), {@code "INFO:"} followed by the identifier
     */
    static String infoKey(String id) {
        if (!id.matches("I[A-Z0-9]{3}")) {
            return id;
        }
        var standard = StandardTag.fromName(id);
        return standard.isPresent() && standard.get().riffInfo().isEmpty() ? INFO_PREFIX + id : id;
    }

    /**
     * Decodes chunk text: UTF-8 when valid, ISO-8859-1 otherwise, up to the first NUL.
     *
     * @param bytes the text bytes
     * @return the text
     */
    static String text(byte[] bytes) {
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
        var plain = RiffMetadataChunks.withoutOwned(newTags, aiff);
        var id3Xmp = id3 == null ? null : id3.decode().xmp();
        var xmpInId3 = xmp == null && id3Xmp != null && newTags.xmp().isPresent();
        byte[] xmpBytes = null;
        if (!xmpInId3) {
            if (newTags.xmp().isPresent()) {
                xmpBytes = Objects.equals(newTags.xmp().get(), xmp) ? xmpChunk
                        : xmpChunk(aiff, bigEndian, newTags.xmp().get());
            }
            plain = plain.toBuilder().noXmp().build();
        }
        var allText = encodeText(aiff, bigEndian, plain, textChunks, textKeys);
        var needsId3 = id3 != null || !plain.pictures().isEmpty() || !allText;
        var newChunks = new ArrayList<byte[]>(textChunks);
        if (needsId3 && !MuxerTags.isGlobalEmpty(plain)) {
            var major = options.id3Version().map(version -> version == TagWriteOptions.Id3Version.V2_3 ? 3 : 4)
                    .orElse(id3 != null && id3.major() == 3 ? 3 : 4);
            var preserved = id3 != null && options.preserveUnknown() ? id3.decode().opaque() : List.<Id3v2Tag.Frame>of();
            newChunks.add(chunk(bigEndian, aiff ? "ID3 " : "id3 ", Id3v2Tag.encode(plain, preserved, null,
                    id3 == null ? null : id3.decode().xmpFrame(), major, 0, 0)));
        }
        if (xmpBytes != null) {
            newChunks.add(xmpBytes);
        }
        if (aiff) {
            newChunks.addAll(RiffMetadataChunks.encodeMarkers(newTags.chapters(), newTags, sampleRate));
        } else {
            newChunks.addAll(RiffMetadataChunks.waveChunks(newTags, !bigEndian, (int) sampleRate));
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
     * @param bigEndian whether chunk sizes are big-endian (AIFF and RIFX)
     * @param tags the tags to store
     * @param out receives the chunks, each complete with its header
     * @param stored receives the lower-case keys stored
     * @return {@code true} when every entry fits a text chunk
     */
    static boolean encodeText(boolean aiff, boolean bigEndian, MediaTags tags, List<byte[]> out, Set<String> stored) {
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
        var info = infoList(tags.entries(), bigEndian, stored);
        if (info != null) {
            out.add(info);
        }
        var ids = new HashSet<String>();
        for (var entry : tags.entries()) {
            var id = infoId(entry.key());
            if (id == null || !ids.add(id)) {
                all = false;
            }
        }
        return all;
    }

    /**
     * Returns the {@code INFO} identifier a key is stored under.
     *
     * @param key a {@link MediaTags} key
     * @return the identifier, or {@code null} when {@code INFO} cannot store the key
     */
    static String infoId(String key) {
        var standard = StandardTag.fromName(key);
        if (standard.isPresent()) {
            return standard.get().riffInfo().orElse(null);
        }
        if (key.matches("I[A-Z0-9]{3}")) {
            return key;
        }
        if (key.startsWith(INFO_PREFIX) && key.substring(INFO_PREFIX.length()).matches("I[A-Z0-9]{3}")) {
            return key.substring(INFO_PREFIX.length());
        }
        return null;
    }

    /**
     * Encodes a {@code LIST} chunk of form {@code INFO} holding the entries it can store, the first
     * value of each identifier.
     *
     * @param entries the tag entries, in order
     * @param bigEndian whether the sizes are big-endian (RIFX)
     * @param stored receives the identifiers written
     * @return the chunk, or {@code null} when no entry fits one
     */
    static byte[] infoList(List<MediaTags.Entry> entries, boolean bigEndian, Set<String> stored) {
        var info = new ByteWriter(256);
        info.fourCc("INFO");
        var any = false;
        for (var entry : entries) {
            var id = infoId(entry.key());
            if (id == null || !stored.add(id)) {
                continue;
            }
            var text = entry.value().getBytes(StandardCharsets.UTF_8);
            info.fourCc(id);
            if (bigEndian) {
                info.u32be(text.length + 1L);
            } else {
                info.u32le(text.length + 1L);
            }
            info.bytes(text);
            info.u8(0);
            if (((text.length + 1) & 1) != 0) {
                info.u8(0);
            }
            any = true;
        }
        return any ? chunk(bigEndian, "LIST", info.toByteArray()) : null;
    }

    /**
     * Builds a chunk with its header and pad byte.
     *
     * @param bigEndian whether the size is big-endian (AIFF, RIFX) rather than little-endian (RIFF)
     * @param id the identifier
     * @param payload the chunk payload
     * @return the chunk
     */
    static byte[] chunk(boolean bigEndian, String id, byte[] payload) {
        var out = new ByteWriter(payload.length + 9);
        out.fourCc(id);
        if (bigEndian) {
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
        return chunk(bigEndian, JUNK, new byte[(int) (total - 8)]);
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
        if (!rf64 && tailStart + added - 8 > 0xFFFFFFFFL) {
            return null;
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
        ByteArrays.put32(size, 0, riffSize, !bigEndian);
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
        var promoted = planPromoted(newChunks, kept, padding);
        if (promoted != null) {
            return promoted;
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
                throw new MediaException.LimitExceeded("the tagged file exceeds the 4 GiB a "
                        + (aiff ? "FORM" : "RIFX") + " size can hold");
            }
            ByteArrays.put32(header, 4, riffSize, !bigEndian);
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
     * Plans a rewrite of a RIFF file that turns it into an RF64 file, when the rewrite would exceed
     * the 4 GiB a RIFF size can hold.
     *
     * <p>The output is the {@code RF64} header, a {@code ds64} chunk, the kept chunks without a
     * leading {@code JUNK} chunk (the {@code ds64} placeholder), the metadata chunks and the padding
     * before the audio chunk, whose 32-bit size becomes {@code 0xFFFFFFFF}.
     *
     * @param newChunks the new metadata chunks
     * @param kept the chunks that are not metadata, in file order
     * @param padding the size of the filler chunk after the metadata, or {@code 0}
     * @return the plan, or {@code null} when the file is not a RIFF file or the rewrite fits 4 GiB
     */
    private EditPlan planPromoted(List<byte[]> newChunks, List<Chunk> kept, long padding) {
        if (aiff || rifx || rf64) {
            return null;
        }
        var body = new ArrayList<>(kept);
        if (kept.stream().noneMatch(chunk -> chunk.id().equals("data"))) {
            padding = 0;
        }
        var plainLength = 12L + source.size() - chunksEnd + padding;
        for (var chunk : kept) {
            plainLength += chunk.total();
        }
        for (var bytes : newChunks) {
            plainLength += bytes.length;
        }
        if (plainLength - 8 <= 0xFFFFFFFFL) {
            return null;
        }
        if (!body.isEmpty() && body.getFirst().id().equals(JUNK)) {
            body.removeFirst();
        }
        var length = 12L + DS64_TOTAL + source.size() - chunksEnd + padding;
        Chunk data = null;
        for (var chunk : body) {
            length += chunk.total();
            if (data == null && chunk.id().equals("data")) {
                data = chunk;
            }
        }
        for (var bytes : newChunks) {
            length += bytes.length;
        }
        var header = new ByteWriter(12);
        header.fourCc("RF64");
        header.u32le(0xFFFFFFFFL);
        header.fourCc("WAVE");
        var ds64 = new ByteWriter(DS64_TOTAL);
        ds64.fourCc("ds64");
        ds64.u32le(DS64_TOTAL - 8);
        ds64.u64le(length - 8);
        ds64.u64le(data != null ? data.payloadSize() : 0);
        ds64.u64le(data != null ? sampleCount(data.payloadSize()) : 0);
        ds64.u32le(0);
        var plan = EditPlan.builder();
        plan.bytes(header.toByteArray());
        plan.bytes(ds64.toByteArray());
        var inserted = false;
        for (var chunk : body) {
            if (!inserted && chunk.id().equals("data")) {
                for (var bytes : newChunks) {
                    plan.bytes(bytes);
                }
                if (padding > 0) {
                    plan.bytes(filler(padding));
                }
                inserted = true;
            }
            if (chunk == data) {
                var dataHeader = new ByteWriter(8);
                dataHeader.fourCc("data");
                dataHeader.u32le(0xFFFFFFFFL);
                plan.bytes(dataHeader.toByteArray());
                plan.copy(chunk.offset() + 8, chunk.total() - 8);
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
     * Returns the sample count the {@code ds64} chunk of a promoted file declares: the one of the
     * {@code fact} chunk, else the audio size over the block alignment of the {@code fmt } chunk.
     *
     * @param dataSize the size of the audio chunk
     * @return the number of sample frames, or {@code 0} when unknown
     */
    private long sampleCount(long dataSize) {
        for (var chunk : chunks) {
            if (chunk.id().equals("fact") && chunk.payloadSize() >= 4) {
                return ByteArrays.u32le(TagIo.read(source, chunk.offset() + 8, 4, limits), 0);
            }
        }
        for (var chunk : chunks) {
            if (chunk.id().equals("fmt ") && chunk.payloadSize() >= 14) {
                var blockAlign = ByteArrays.u16le(TagIo.read(source, chunk.offset() + 8, 14, limits), 12);
                return blockAlign > 0 ? dataSize / blockAlign : 0;
            }
        }
        return 0;
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
