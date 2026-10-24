package com.github.auties00.smalt.internal.tags;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.container.AttachedPicture;
import com.github.auties00.smalt.container.Chapter;
import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.internal.io.ByteArrays;
import com.github.auties00.smalt.internal.io.ByteWriter;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.internal.edit.EditPlan;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.metadata.MediaTags;
import com.github.auties00.smalt.metadata.StandardTag;
import com.github.auties00.smalt.metadata.TagWriteOptions;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.zip.GZIPInputStream;

/**
 * Edits the iTunes metadata of an MP4, M4A or MOV file.
 *
 * <p>Tags live in {@code moov/udta/meta/ilst}: one item per key, each holding {@code data} atoms
 * (UTF-8 text, the binary forms of {@code trkn}, {@code disk}, {@code cpil} and {@code gnre}, or
 * the big-endian integers of {@link #INTEGER_ATOMS} and of any other atom the file stores as a
 * signed or unsigned integer, read and written as decimal text), cover art in {@code covr} and
 * free-form items in {@code ----} atoms named by {@code mean} and {@code name}. Items with another
 * binary payload or a non-Apple {@code mean} are opaque and kept.
 *
 * <p>Chapters are written twice: as a Nero {@code chpl} box in {@code udta}, which stores start
 * times only, and as a QuickTime chapter track ({@link Mp4ChapterTrack}) that every audio and
 * video track references through {@code tref/chap}, whose samples go in an {@code mdat} box of
 * their own placed right before {@code moov}. Reading prefers {@code chpl}, unless the chapter
 * track completes the chapters or titles a full {@code chpl} cut
 * ({@link Mp4ChapterTrack#supersedes(List, List)}). Chapter tracks are
 * kept while the chapters are unchanged and replaced otherwise; the {@code mdat} holding only
 * the samples of a replaced chapter track is reused as free space.
 *
 * <p>The rewrite builds a new {@code moov}. When it fits the old {@code moov} plus the
 * {@code free} boxes that follow it, the remainder becomes a {@code free} box and nothing else
 * moves; when {@code moov} is the last box of the file it is rewritten in place at any size.
 * Otherwise the data after the old {@code moov} shifts, and every absolute offset that points
 * past it is corrected: the chunk offsets ({@code stco}, {@code co64}) inside the new
 * {@code moov}, where a {@code stco} box whose offsets no longer fit 32 bits becomes a
 * {@code co64} box, the explicit base data offsets of movie fragments ({@code tfhd}) and the
 * fragment offsets of the random access index ({@code tfra}).
 *
 * <p>A file whose movie is a compressed movie box ({@code !mov}, ISO/IEC 14496-12 clause 8.20) is read
 * from the decompressed movie, and a rewrite replaces the compressed box with an uncompressed
 * {@code moov}. The offsets of such a file count in the file with its compressed boxes
 * uncompressed, so the explicit offsets of the movie fragments after a rewritten compressed movie
 * move by what the rewrite and the compression together changed; a rewrite that would move the data
 * of compressed movie fragments ({@code !mof}) stating explicit base data offsets is rejected.
 */
final class Mp4TagFile implements TagFile {
    /**
     * The largest number of top-level boxes read.
     */
    private static final int MAX_TOP_LEVEL = 1 << 16;

    /**
     * The first character of the QuickTime and iTunes text atoms, {@code 0xA9}.
     */
    private static final char COPYRIGHT_SIGN = (char) 0xA9;

    /**
     * The {@code mean} of iTunes free-form items.
     */
    private static final String APPLE_MEAN = "com.apple.iTunes";

    /**
     * The data type of UTF-8 text.
     */
    private static final int TYPE_UTF8 = 1;

    /**
     * The data type of UTF-16 text.
     */
    private static final int TYPE_UTF16 = 2;

    /**
     * The data type of JPEG pictures.
     */
    private static final int TYPE_JPEG = 13;

    /**
     * The data type of PNG pictures.
     */
    private static final int TYPE_PNG = 14;

    /**
     * The data type of signed big-endian integers.
     */
    private static final int TYPE_INTEGER = 21;

    /**
     * The data type of unsigned big-endian integers.
     */
    private static final int TYPE_UNSIGNED = 22;

    /**
     * The data type of BMP pictures.
     */
    private static final int TYPE_BMP = 27;

    /**
     * The largest value a 32-bit field holds.
     */
    private static final long U32_MAX = 0xFFFFFFFFL;

    /**
     * The type of a compressed movie box (ISO/IEC 14496-12 clause 8.20).
     */
    private static final String COMPRESSED_MOVIE = "!mov";

    /**
     * The width in bytes of the iTunes atoms that hold a signed integer: tempo ({@code tmpo}),
     * content rating ({@code rtng}), media kind ({@code stik}), gapless playback ({@code pgap}),
     * HD video ({@code hdvd}), TV season and episode ({@code tvsn}, {@code tves}), the store
     * identifiers ({@code plID}, {@code cnID}, {@code atID}, {@code geID}, {@code sfID},
     * {@code cmID}), account kind ({@code akID}) and show movement ({@code shwm}).
     */
    static final Map<String, Integer> INTEGER_ATOMS = Map.ofEntries(Map.entry("tmpo", 2), Map.entry("rtng", 1),
            Map.entry("stik", 1), Map.entry("pgap", 1), Map.entry("hdvd", 1), Map.entry("tvsn", 4),
            Map.entry("tves", 4), Map.entry("plID", 8), Map.entry("cnID", 4), Map.entry("atID", 4),
            Map.entry("geID", 4), Map.entry("sfID", 4), Map.entry("cmID", 4), Map.entry("akID", 1),
            Map.entry("shwm", 1));

    /**
     * The integer atoms that hold a flag, which also take {@code true}, {@code yes}, {@code false} and
     * {@code no}.
     */
    private static final Set<String> FLAG_ATOMS = Set.of("pgap", "shwm");

    /**
     * The source.
     */
    private final MediaSource source;

    /**
     * The resource bounds.
     */
    private final MediaLimits limits;

    /**
     * The top-level boxes as offset, size and header size, in file order.
     */
    private final List<long[]> topLevel;

    /**
     * The types of the top-level boxes, parallel to {@link #topLevel}.
     */
    private final List<String> topLevelTypes;

    /**
     * The index of {@code moov} in {@link #topLevel}.
     */
    private final int moovIndex;

    /**
     * The bytes of {@code moov}, header included.
     */
    private final byte[] moov;

    /**
     * The bytes the compressed movie box saved against the {@code moov} it holds, or {@code 0} when the
     * movie box is not compressed: the offsets of the movie fragments count in the file with that box
     * uncompressed, so they move back by this much when the box is rewritten.
     */
    private final long movieSaved;

    /**
     * The identifiers of the QuickTime chapter text tracks.
     */
    private final Set<Long> chapterTracks;

    /**
     * The decoded tags.
     */
    private final MediaTags tags;

    /**
     * The opaque {@code ilst} items, each a complete box.
     */
    private final List<byte[]> opaqueItems;

    /**
     * The integer form of the atoms the file stores as integers, by atom code.
     */
    private final Map<String, IntegerForm> integerForms;

    /**
     * Parses the top-level boxes and the metadata of {@code moov}.
     *
     * @param source the file to parse; not closed
     * @param limits the resource bounds
     * @throws MediaException.Malformed if the box structure is corrupt or {@code moov} is missing
     */
    Mp4TagFile(MediaSource source, MediaLimits limits) {
        this.source = source;
        this.limits = limits;
        var size = source.size();
        var boxes = new ArrayList<long[]>();
        var types = new ArrayList<String>();
        var position = 0L;
        var moovAt = -1;
        var compressedAt = -1;
        while (size - position >= 8) {
            if (boxes.size() >= MAX_TOP_LEVEL) {
                throw new MediaException.Malformed("more than " + MAX_TOP_LEVEL + " top-level MP4 boxes");
            }
            var header = TagIo.read(source, position, Math.min(16, size - position), limits);
            long boxSize = ByteArrays.u32be(header, 0);
            if (boxSize == 1) {
                if (header.length < 16) {
                    throw new MediaException.Malformed("truncated 64-bit MP4 box size at offset " + position);
                }
                boxSize = ByteArrays.i64be(header, 8);
                if (boxSize < 16) {
                    throw new MediaException.Malformed("invalid 64-bit MP4 box size at offset " + position);
                }
            } else if (boxSize == 0) {
                boxSize = size - position;
            } else if (boxSize < 8) {
                throw new MediaException.Malformed("invalid MP4 box size " + boxSize + " at offset " + position);
            }
            if (boxSize > size - position) {
                boxSize = size - position;
            }
            var type = Mp4Boxes.type(header, 4);
            if (type.equals("moov") && moovAt < 0) {
                moovAt = boxes.size();
            } else if (type.equals(COMPRESSED_MOVIE) && compressedAt < 0) {
                compressedAt = boxes.size();
            }
            boxes.add(new long[]{position, boxSize, ByteArrays.u32be(header, 0) == 1 ? 16 : 8});
            types.add(type);
            position += boxSize;
        }
        if (moovAt < 0 && compressedAt < 0) {
            throw new MediaException.Malformed("MP4 file without a moov box");
        }
        this.topLevel = List.copyOf(boxes);
        this.topLevelTypes = List.copyOf(types);
        this.moovIndex = moovAt >= 0 ? moovAt : compressedAt;
        var moovBox = boxes.get(moovIndex);
        this.moov = moovAt >= 0 ? TagIo.read(source, moovBox[0], moovBox[1], limits) : decompressMovie(moovBox);
        this.movieSaved = moovAt >= 0 ? 0 : moov.length - moovBox[1];
        this.chapterTracks = findChapterTracks();
        var opaque = new ArrayList<byte[]>();
        this.integerForms = new HashMap<>();
        this.tags = decode(opaque);
        this.opaqueItems = List.copyOf(opaque);
    }

    /**
     * Reads the movie of a compressed movie box ({@code !mov}, ISO/IEC 14496-12 clause 8.20), a gzip stream of the
     * payload of a {@code moov} box, as an uncompressed {@code moov} box that replaces it when the tags are
     * written. The chunk offsets of the compressed movie count in the file with the movie box uncompressed, so the
     * offsets past it are moved to where the data lies in the file as it is.
     *
     * @param box the offset, size and header size of the compressed movie box
     * @return the {@code moov} box
     * @throws MediaException.Malformed if the payload is not a gzip stream or a chunk offset is out of range
     * @throws MediaException.LimitExceeded if the movie box is larger than the byte limit
     */
    private byte[] decompressMovie(long[] box) {
        var payload = inflate(TagIo.read(source, box[0] + box[2], box[1] - box[2], limits));
        var wide = payload.length + 8L > U32_MAX;
        var header = wide ? 16 : 8;
        var movie = new byte[header + payload.length];
        if (wide) {
            ByteArrays.put32be(movie, 0, 1);
            ByteArrays.put64be(movie, 8, movie.length);
        } else {
            ByteArrays.put32be(movie, 0, movie.length);
        }
        movie[4] = 'm';
        movie[5] = 'o';
        movie[6] = 'o';
        movie[7] = 'v';
        System.arraycopy(payload, 0, movie, header, payload.length);
        var saved = movie.length - box[1];
        var end = box[0] + movie.length;
        for (var trak : Mp4Boxes.children(movie, header, movie.length)) {
            if (trak.type().equals("trak")) {
                relocateChunks(movie, trak, saved, end);
            }
        }
        return movie;
    }

    /**
     * Decompresses the payload of a compressed box, a gzip stream, within the byte limit.
     *
     * @param compressed the payload of the compressed box
     * @return the payload of the box it replaces
     * @throws MediaException.Malformed if the payload is not a gzip stream
     * @throws MediaException.LimitExceeded if the decompressed payload exceeds the byte limit
     */
    private byte[] inflate(byte[] compressed) {
        var payload = new ByteArrayOutputStream((int) Math.min(compressed.length * 4L, 1 << 20));
        try (var input = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
            var buffer = new byte[8192];
            for (var read = input.read(buffer); read >= 0; read = input.read(buffer)) {
                LimitChecks.checkAllocation(limits, payload.size() + read + 16L);
                payload.write(buffer, 0, read);
            }
        } catch (IOException exception) {
            throw new MediaException.Malformed("the compressed box is not a gzip stream: " + exception.getMessage());
        }
        return payload.toByteArray();
    }

    /**
     * Moves back, in place, the chunk offsets of a track that point at or after an offset.
     *
     * @param movie the {@code moov} box
     * @param trak the {@code trak} box
     * @param shift the distance to move the offsets back by
     * @param threshold the first offset that moves
     * @throws MediaException.Malformed if a moved offset is negative
     */
    private static void relocateChunks(byte[] movie, Mp4Boxes.Box trak, long shift, long threshold) {
        var mdia = Mp4Boxes.find(Mp4Boxes.children(movie, trak.payload(), trak.end()), "mdia");
        var minf = mdia == null ? null : Mp4Boxes.find(Mp4Boxes.children(movie, mdia.payload(), mdia.end()), "minf");
        var stbl = minf == null ? null : Mp4Boxes.find(Mp4Boxes.children(movie, minf.payload(), minf.end()), "stbl");
        if (stbl == null) {
            return;
        }
        for (var table : Mp4Boxes.children(movie, stbl.payload(), stbl.end())) {
            var wide = table.type().equals("co64");
            if (!wide && !table.type().equals("stco") || table.end() - table.payload() < 8) {
                continue;
            }
            var count = ByteArrays.u32be(movie, table.payload() + 4);
            var entry = wide ? 8 : 4;
            var position = table.payload() + 8;
            for (var i = 0L; i < count && table.end() - position >= entry; i++) {
                var offset = wide ? ByteArrays.i64be(movie, position) : ByteArrays.u32be(movie, position);
                if (Long.compareUnsigned(offset, threshold) >= 0) {
                    if (offset - shift < 0 || !wide && offset - shift > U32_MAX) {
                        throw new MediaException.Malformed("chunk offset " + offset + " does not fit the file with the"
                                + " compressed movie box");
                    }
                    if (wide) {
                        ByteArrays.put64be(movie, position, offset - shift);
                    } else {
                        ByteArrays.put32be(movie, position, offset - shift);
                    }
                }
                position += entry;
            }
        }
    }

    /**
     * The encoding of an integer atom.
     *
     * @param type the data type, {@value #TYPE_INTEGER} (signed) or {@value #TYPE_UNSIGNED}, or
     * {@value #TYPE_UTF8} for an atom of {@link #INTEGER_ATOMS} the file also stores as text
     * @param width the width in bytes: 1, 2, 3, 4 or 8
     */
    record IntegerForm(int type, int width) {
    }

    /**
     * {@return the index of the payload of {@code moov}}
     */
    private int moovPayload() {
        return ByteArrays.u32be(moov, 0) == 1 ? 16 : 8;
    }

    /**
     * Decodes the {@code ilst} items, the {@code chpl} chapters and the QuickTime text atoms of
     * {@code udta}.
     *
     * @param opaque receives the opaque items
     * @return the tags
     */
    private MediaTags decode(List<byte[]> opaque) {
        var builder = MediaTags.builder();
        var children = Mp4Boxes.children(moov, moovPayload(), moov.length);
        var udta = Mp4Boxes.find(children, "udta");
        if (udta == null) {
            return builder.chapters(trackChapters()).build();
        }
        var udtaChildren = Mp4Boxes.children(moov, udta.payload(), udta.end());
        var meta = Mp4Boxes.find(udtaChildren, "meta");
        var keys = new HashSet<String>();
        if (meta != null && isItunesMeta(meta)) {
            var ilst = Mp4Boxes.find(Mp4Boxes.children(moov, metaChildren(meta), meta.end()), "ilst");
            if (ilst != null) {
                for (var item : Mp4Boxes.children(moov, ilst.payload(), ilst.end())) {
                    if (!decodeItem(item, builder, keys)) {
                        opaque.add(Arrays.copyOfRange(moov, item.offset(), item.end()));
                    }
                }
            }
        }
        for (var child : udtaChildren) {
            var text = quickTimeText(child);
            if (text != null) {
                var key = keyOf(child.type());
                if (!text.isBlank() && keys.add(StandardTag.normalize(key).toLowerCase(Locale.ROOT))) {
                    builder.add(key, text);
                }
            }
        }
        var chpl = Mp4Boxes.find(udtaChildren, "chpl");
        if (chpl != null) {
            var nero = decodeChapters(chpl);
            if (!chapterTracks.isEmpty() && Mp4ChapterTrack.mayBeCut(nero)) {
                var track = trackChapters();
                if (Mp4ChapterTrack.supersedes(track, nero)) {
                    nero = track;
                }
            }
            builder.chapters(nero);
        } else {
            builder.chapters(trackChapters());
        }
        return builder.build();
    }

    /**
     * Reads the chapters of the QuickTime chapter text tracks through the demuxer.
     *
     * @return the chapters, empty when the file has no chapter track or it cannot be read
     */
    private List<Chapter> trackChapters() {
        if (chapterTracks.isEmpty()) {
            return List.of();
        }
        try (var demuxer = Containers.openDemuxer(source)) {
            return demuxer.chapters();
        } catch (MediaException.Io exception) {
            throw exception;
        } catch (MediaException exception) {
            return List.of();
        }
    }

    /**
     * Returns the duration the demuxer computes, for files whose {@code mvhd} records none
     * (fragmented files).
     *
     * @return the duration in microseconds, or {@code -1} when unknown
     */
    private long demuxedDuration() {
        try (var demuxer = Containers.openDemuxer(source)) {
            return demuxer.durationMicros();
        } catch (MediaException.Io exception) {
            throw exception;
        } catch (MediaException exception) {
            return -1;
        }
    }

    /**
     * Decodes a QuickTime text atom of {@code udta}: a copyright-sign code holding a 16-bit
     * length, a 16-bit language and the text.
     *
     * <p>Such atoms are read as tags and replaced by the {@code ilst} on rewrite.
     *
     * @param child the {@code udta} child
     * @return the text, or {@code null} when the child is not such an atom
     */
    private String quickTimeText(Mp4Boxes.Box child) {
        if (child.type().charAt(0) != COPYRIGHT_SIGN || child.size() < 12) {
            return null;
        }
        var length = ByteArrays.u16be(moov, child.payload());
        if (length > child.end() - child.payload() - 4) {
            return null;
        }
        return new String(moov, child.payload() + 4, length, StandardCharsets.UTF_8);
    }

    /**
     * Returns whether a {@code meta} box is the iTunes ({@code mdir}) kind.
     *
     * @param meta the box
     * @return {@code true} when its handler is {@code mdir} or it has no handler
     */
    private boolean isItunesMeta(Mp4Boxes.Box meta) {
        var hdlr = Mp4Boxes.find(Mp4Boxes.children(moov, metaChildren(meta), meta.end()), "hdlr");
        return hdlr == null || hdlr.size() < 20 || Mp4Boxes.type(moov, hdlr.payload() + 8).equals("mdir");
    }

    /**
     * Returns the index of the first child of a {@code meta} box, which is a full box in MP4 and
     * a plain container in QuickTime.
     *
     * @param meta the box
     * @return the index
     */
    private int metaChildren(Mp4Boxes.Box meta) {
        var payload = meta.payload();
        if (meta.end() - payload >= 8 && Mp4Boxes.type(moov, payload + 4).equals("hdlr")) {
            return payload;
        }
        return Math.min(meta.end(), payload + 4);
    }

    /**
     * Decodes one {@code ilst} item.
     *
     * @param item the item box
     * @param builder receives the decoded values
     * @param keys the lower-case keys decoded so far
     * @return {@code true} when the item was decoded, {@code false} when it is opaque
     */
    private boolean decodeItem(Mp4Boxes.Box item, MediaTags.Builder builder, Set<String> keys) {
        var code = item.type();
        var parts = Mp4Boxes.children(moov, item.payload(), item.end());
        String mean = null;
        String name = null;
        var data = new ArrayList<Mp4Boxes.Box>();
        for (var part : parts) {
            switch (part.type()) {
                case "mean" -> mean = text(part, 4);
                case "name" -> name = text(part, 4);
                case "data" -> {
                    if (part.size() >= 16) {
                        data.add(part);
                    }
                }
                default -> {
                    return false;
                }
            }
        }
        if (data.isEmpty()) {
            return false;
        }
        if (code.equals("covr")) {
            var pictures = new ArrayList<AttachedPicture>();
            for (var part : data) {
                var type = dataType(part);
                var mediaType = switch (type) {
                    case TYPE_JPEG -> "image/jpeg";
                    case TYPE_PNG -> "image/png";
                    case TYPE_BMP -> "image/bmp";
                    case 0 -> "";
                    default -> null;
                };
                if (mediaType == null) {
                    return false;
                }
                var bytes = Arrays.copyOfRange(moov, part.payload() + 8, part.end());
                var picture = AttachedPicture.of(AttachedPicture.Type.FRONT_COVER, mediaType, "", bytes);
                if (mediaType.isEmpty()) {
                    picture = AttachedPicture.of(AttachedPicture.Type.FRONT_COVER,
                            picture.format().map(ImageFormat::mimeType).orElse(""), "", bytes);
                }
                pictures.add(picture);
            }
            for (var picture : pictures) {
                builder.addPicture(picture);
            }
            return true;
        }
        String key;
        if (code.equals("----")) {
            if (name == null || !APPLE_MEAN.equals(mean)) {
                return false;
            }
            key = name;
        } else if (mean != null || name != null) {
            return false;
        } else {
            key = keyOf(code);
        }
        var values = new ArrayList<String>();
        for (var part : data) {
            var value = decodeValue(code, part);
            if (value == null) {
                return false;
            }
            values.add(value);
        }
        var added = false;
        for (var value : values) {
            if (!value.isEmpty()) {
                try {
                    builder.add(key, value);
                    added = true;
                } catch (IllegalArgumentException exception) {
                    return false;
                }
            }
        }
        if (added) {
            keys.add(StandardTag.normalize(key).toLowerCase(Locale.ROOT));
        }
        return true;
    }

    /**
     * Returns the key an atom code is read as.
     *
     * @param code the atom code
     * @return the standard key the atom stores, or the code itself
     */
    private static String keyOf(String code) {
        for (var tag : StandardTag.values()) {
            if (tag.mp4Atom().isPresent() && tag.mp4Atom().get().equals(code)) {
                return tag.key();
            }
        }
        return code;
    }

    /**
     * Returns the data type of a {@code data} atom.
     *
     * @param data the atom
     * @return the 24-bit type indicator
     */
    private int dataType(Mp4Boxes.Box data) {
        return (int) (ByteArrays.u32be(moov, data.payload()) & 0xFFFFFF);
    }

    /**
     * Decodes the value of a {@code data} atom.
     *
     * @param code the item code
     * @param data the atom
     * @return the value as text, or {@code null} when the atom has a form text cannot hold
     */
    private String decodeValue(String code, Mp4Boxes.Box data) {
        var type = dataType(data);
        var start = data.payload() + 8;
        var length = data.end() - start;
        switch (code) {
            case "trkn", "disk" -> {
                if (length < 6) {
                    return null;
                }
                var number = ByteArrays.u16be(moov, start + 2);
                var total = ByteArrays.u16be(moov, start + 4);
                return total > 0 ? number + "/" + total : Integer.toString(number);
            }
            case "gnre" -> {
                if (length < 2) {
                    return null;
                }
                var name = Id3Genres.name(ByteArrays.u16be(moov, start) - 1);
                return name != null ? name : null;
            }
            case "cpil" -> {
                if (length < 1) {
                    return null;
                }
                return moov[start + length - 1] != 0 ? "1" : "0";
            }
            default -> {
            }
        }
        var integer = length == 1 || length == 2 || length == 3 || length == 4 || length == 8;
        var implicit = INTEGER_ATOMS.containsKey(code) && type == 0;
        if (integer && (implicit || type == TYPE_INTEGER || type == TYPE_UNSIGNED) && !code.equals("----")) {
            var form = new IntegerForm(type == TYPE_UNSIGNED ? TYPE_UNSIGNED : TYPE_INTEGER, length);
            integerForms.putIfAbsent(code, form);
            return integerText(start, length, type == TYPE_UNSIGNED);
        }
        if (INTEGER_ATOMS.containsKey(code) && (type == TYPE_UTF8 || type == TYPE_UTF16)) {
            integerForms.put(code, new IntegerForm(TYPE_UTF8, INTEGER_ATOMS.get(code)));
        }
        return switch (type) {
            case TYPE_UTF8 -> new String(moov, start, length, StandardCharsets.UTF_8);
            case TYPE_UTF16 -> new String(moov, start, length & ~1, StandardCharsets.UTF_16BE);
            default -> null;
        };
    }

    /**
     * Decodes a big-endian integer of {@code moov} as decimal text.
     *
     * @param start the index of the first byte
     * @param length the width in bytes, from 1 to 8
     * @param unsigned whether the value is unsigned rather than two's complement
     * @return the decimal text
     */
    private String integerText(int start, int length, boolean unsigned) {
        var value = 0L;
        for (var index = 0; index < length; index++) {
            value = (value << 8) | (moov[start + index] & 0xFF);
        }
        if (unsigned) {
            return Long.toUnsignedString(value);
        }
        var unused = 64 - 8 * length;
        return Long.toString(value << unused >> unused);
    }

    /**
     * Decodes the text of a full box ({@code mean}, {@code name}).
     *
     * @param box the full box holding the text
     * @param skip the bytes to skip after the header
     * @return the text
     */
    private String text(Mp4Boxes.Box box, int skip) {
        var start = Math.min(box.end(), box.payload() + skip);
        return new String(moov, start, box.end() - start, StandardCharsets.UTF_8);
    }

    /**
     * Decodes a Nero chapter list.
     *
     * @param chpl the box
     * @return the chapters, each ending where the next starts and the last at the movie end
     */
    private List<Chapter> decodeChapters(Mp4Boxes.Box chpl) {
        var position = chpl.payload();
        var end = chpl.end();
        if (end - position < 5) {
            return List.of();
        }
        var version = moov[position] & 0xFF;
        position += 4;
        if (version != 0) {
            position += 4;
        }
        if (position >= end) {
            return List.of();
        }
        var count = moov[position++] & 0xFF;
        var starts = new ArrayList<Long>();
        var titles = new ArrayList<String>();
        for (var index = 0; index < count && end - position >= 9; index++) {
            var start = ByteArrays.i64be(moov, position) / 10;
            var length = moov[position + 8] & 0xFF;
            position += 9;
            if (length > end - position) {
                break;
            }
            starts.add(start);
            titles.add(new String(moov, position, length, StandardCharsets.UTF_8));
            position += length;
        }
        var duration = durationMicros();
        if (duration <= 0 && !starts.isEmpty()) {
            duration = demuxedDuration();
        }
        var result = new ArrayList<Chapter>();
        for (var index = 0; index < starts.size(); index++) {
            var start = Math.max(0, starts.get(index));
            var stop = index + 1 < starts.size() ? starts.get(index + 1) : Math.max(start, duration);
            var title = titles.get(index);
            result.add(Chapter.of(start, Math.max(start, stop), title.isEmpty() ? null : title));
        }
        return result;
    }

    /**
     * {@return the movie duration from {@code mvhd}, in microseconds, or {@code -1} when unknown}
     */
    private long durationMicros() {
        var mvhd = Mp4Boxes.find(Mp4Boxes.children(moov, moovPayload(), moov.length), "mvhd");
        if (mvhd == null || mvhd.size() < 32) {
            return -1;
        }
        var position = mvhd.payload();
        var version = moov[position] & 0xFF;
        long timescale;
        long duration;
        if (version == 1) {
            if (mvhd.size() < 44) {
                return -1;
            }
            timescale = ByteArrays.u32be(moov, position + 20);
            duration = ByteArrays.i64be(moov, position + 24);
        } else {
            timescale = ByteArrays.u32be(moov, position + 12);
            duration = ByteArrays.u32be(moov, position + 16);
        }
        if (timescale == 0 || duration < 0 || duration == 0xFFFFFFFFL) {
            return -1;
        }
        return Math.multiplyHigh(duration, 1_000_000L) != 0 ? -1 : duration * 1_000_000L / timescale;
    }

    @Override
    public MediaTags read() {
        return tags;
    }

    @Override
    public EditPlan plan(MediaTags newTags, TagWriteOptions options) {
        var rewrite = new Rewrite(newTags, options);
        var moovBox = topLevel.get(moovIndex);
        var slotStart = moovBox[0];
        var slotEnd = moovBox[0] + moovBox[1];
        if (rewrite.dropping && moovIndex > 0 && reclaimable(moovIndex - 1)) {
            slotStart = topLevel.get(moovIndex - 1)[0];
        }
        var next = moovIndex + 1;
        while (next < topLevel.size() && (isFree(topLevelTypes.get(next)) || rewrite.dropping && reclaimable(next))) {
            slotEnd = topLevel.get(next)[0] + topLevel.get(next)[1];
            next++;
        }
        rewrite.place(slotStart);
        var base = rewrite.build(0, Long.MAX_VALUE);
        var last = next == topLevel.size();
        var fileSize = source.size();
        var slot = slotEnd - slotStart;
        var plan = EditPlan.builder().copy(0, slotStart);
        if (base.length == slot || last || base.length + 8 <= slot) {
            plan.bytes(base);
            if (base.length != slot && !last) {
                plan.bytes(Mp4Boxes.free(slot - base.length));
            }
            if (!last && movieSaved != 0) {
                checkCompressedFragments(next, -movieSaved);
                plan.transform(new Shift(slotEnd, fileSize - slotEnd, -movieSaved, limits));
                return plan.build();
            }
        } else {
            var padded = options.padding() >= 8 ? options.padding() : 0;
            var delta = base.length + padded - slot;
            var shifted = rewrite.build(delta, slotEnd);
            for (var attempt = 0; shifted.length + padded - slot != delta; attempt++) {
                if (attempt > 64) {
                    throw new IllegalStateException("chunk offset promotion did not converge");
                }
                delta = shifted.length + padded - slot;
                shifted = rewrite.build(delta, slotEnd);
            }
            plan.bytes(shifted);
            if (padded > 0) {
                plan.bytes(Mp4Boxes.free(padded));
            }
            if (fileSize > slotEnd) {
                checkCompressedFragments(next, delta - movieSaved);
                plan.transform(new Shift(slotEnd, fileSize - slotEnd, delta - movieSaved, limits));
            }
            return plan.build();
        }
        if (!last) {
            plan.copy(slotEnd, fileSize - slotEnd);
        }
        return plan.build();
    }

    /**
     * Rejects a rewrite that would move the data of compressed movie fragments ({@code !mof}) whose track
     * fragments state an explicit base data offset, which only a decompressed and recompressed fragment
     * could correct.
     *
     * @param from the index of the first top-level box after the rewritten movie box
     * @param delta the distance the offsets of the data after the movie box move by
     * @throws MediaException.Unsupported if such a fragment follows the movie box and {@code delta} is not
     * {@code 0}
     * @throws MediaException.Malformed if a compressed fragment is not a gzip stream
     */
    private void checkCompressedFragments(int from, long delta) {
        if (delta == 0) {
            return;
        }
        for (var index = from; index < topLevel.size(); index++) {
            if (!topLevelTypes.get(index).equals("!mof")) {
                continue;
            }
            var box = topLevel.get(index);
            var fragment = inflate(TagIo.read(source, box[0] + box[2], box[1] - box[2], limits));
            for (var traf : Mp4Boxes.children(fragment, 0, fragment.length)) {
                if (!traf.type().equals("traf")) {
                    continue;
                }
                var tfhd = Mp4Boxes.find(Mp4Boxes.children(fragment, traf.payload(), traf.end()), "tfhd");
                if (tfhd != null && tfhd.end() - tfhd.payload() >= 4
                        && (ByteArrays.u32be(fragment, tfhd.payload()) & 1) != 0) {
                    // TODO: rewrite the explicit base data offsets of compressed movie fragments, recompressing them
                    throw new MediaException.Unsupported("mp4 compressed movie fragments",
                            "writing tags would move the data of a compressed movie fragment with an explicit base"
                                    + " data offset");
                }
            }
        }
    }

    /**
     * Returns whether a top-level box is an {@code mdat} that holds the samples of the chapter
     * tracks and of no other track, so it can be reused when the chapter tracks are replaced.
     *
     * @param index the index of the box in {@link #topLevel}
     * @return {@code true} for such a box
     */
    private boolean reclaimable(int index) {
        if (!topLevelTypes.get(index).equals("mdat")) {
            return false;
        }
        var start = topLevel.get(index)[0];
        var end = start + topLevel.get(index)[1];
        var holdsChapters = false;
        for (var trak : Mp4Boxes.children(moov, moovPayload(), moov.length)) {
            if (!trak.type().equals("trak")) {
                continue;
            }
            var chapter = chapterTracks.contains(trackId(moov, trak));
            for (var offset : chunkOffsets(trak)) {
                var inside = offset >= start && offset < end;
                if (chapter != inside) {
                    return false;
                }
                holdsChapters |= inside;
            }
        }
        return holdsChapters;
    }

    /**
     * Collects the chunk offsets of a track.
     *
     * @param trak the {@code trak} box
     * @return the offsets of every {@code stco} and {@code co64} box of its sample table
     */
    private List<Long> chunkOffsets(Mp4Boxes.Box trak) {
        var result = new ArrayList<Long>();
        var mdia = Mp4Boxes.find(Mp4Boxes.children(moov, trak.payload(), trak.end()), "mdia");
        var minf = mdia == null ? null : Mp4Boxes.find(Mp4Boxes.children(moov, mdia.payload(), mdia.end()), "minf");
        var stbl = minf == null ? null : Mp4Boxes.find(Mp4Boxes.children(moov, minf.payload(), minf.end()), "stbl");
        if (stbl == null) {
            return result;
        }
        for (var table : Mp4Boxes.children(moov, stbl.payload(), stbl.end())) {
            var wide = table.type().equals("co64");
            if (!wide && !table.type().equals("stco") || table.end() - table.payload() < 8) {
                continue;
            }
            var count = ByteArrays.u32be(moov, table.payload() + 4);
            var entry = wide ? 8 : 4;
            var position = table.payload() + 8;
            for (var i = 0L; i < count && table.end() - position >= entry; i++) {
                result.add(wide ? ByteArrays.i64be(moov, position) : ByteArrays.u32be(moov, position));
                position += entry;
            }
        }
        return result;
    }

    /**
     * Returns whether a top-level box type is free space.
     *
     * @param type a four-character box type
     * @return {@code true} for {@code free} and {@code skip}
     */
    private static boolean isFree(String type) {
        return type.equals("free") || type.equals("skip");
    }

    /**
     * Returns the handler type of a track.
     *
     * @param trak the {@code trak} box
     * @return the four-character handler type, or an empty string when the track has no readable
     * {@code hdlr}
     */
    private String handlerType(Mp4Boxes.Box trak) {
        var mdia = Mp4Boxes.find(Mp4Boxes.children(moov, trak.payload(), trak.end()), "mdia");
        var hdlr = mdia == null ? null : Mp4Boxes.find(Mp4Boxes.children(moov, mdia.payload(), mdia.end()), "hdlr");
        return hdlr == null || hdlr.size() < 20 ? "" : Mp4Boxes.type(moov, hdlr.payload() + 8);
    }

    /**
     * Builds the boxes that replace the old {@code moov} for one set of tags: the {@code mdat} of
     * a new chapter track, when there is one, followed by the new {@code moov}.
     */
    private final class Rewrite {
        /**
         * The new {@code udta}.
         */
        private final byte[] udta;

        /**
         * Whether the chapter tracks of the file are removed.
         */
        private final boolean dropping;

        /**
         * The identifiers of the chapter tracks whose {@code trak} and {@code trex} boxes the rewrite removes: those no
         * movie fragment names. A chapter track whose samples are in fragments stays, without the chapter references
         * that made it one, since a track fragment must name a track of the movie (ISO/IEC 14496-12 clause 8.8.7).
         */
        private final Set<Long> removed;

        /**
         * The new chapter track, or {@code null} when none is written.
         */
        private final Mp4ChapterTrack chapterTrack;

        /**
         * The identifier of the new chapter track.
         */
        private final long chapterId;

        /**
         * The identifiers of the tracks that reference the new chapter track.
         */
        private final Set<Long> referencing;

        /**
         * The {@code mdat} holding the samples of the new chapter track, or an empty array.
         */
        private byte[] chapterData = new byte[0];

        /**
         * The file offset of the samples of the new chapter track.
         */
        private long chapterOffset;

        /**
         * Prepares the rewrite.
         *
         * @param newTags the tags
         * @param options the write options
         * @throws MediaException.Unsupported if an integer atom holds a value it cannot store
         */
        Rewrite(MediaTags newTags, TagWriteOptions options) {
            var children = Mp4Boxes.children(moov, moovPayload(), moov.length);
            this.udta = buildUdta(Mp4Boxes.find(children, "udta"), newTags, options);
            var chapters = newTags.chapters();
            var keep = !chapterTracks.isEmpty() && chapters.equals(tags.chapters());
            this.dropping = !chapterTracks.isEmpty() && !keep;
            if (dropping && Mp4Boxes.find(children, "mvex") != null) {
                var fragmented = fragmentTracks();
                var dropped = new HashSet<>(chapterTracks);
                dropped.removeAll(fragmented);
                this.removed = Set.copyOf(dropped);
            } else {
                this.removed = dropping ? Set.copyOf(chapterTracks) : Set.of();
            }
            var largest = 0L;
            var largestAll = 0L;
            var media = new ArrayList<Long>();
            var first = -1L;
            for (var child : children) {
                if (!child.type().equals("trak")) {
                    continue;
                }
                var id = trackId(moov, child);
                largestAll = Math.max(largestAll, id);
                if (removed.contains(id)) {
                    continue;
                }
                largest = Math.max(largest, id);
                if (first < 0) {
                    first = id;
                }
                var handler = handlerType(child);
                if (handler.equals("soun") || handler.equals("vide")) {
                    media.add(id);
                }
            }
            if (media.isEmpty() && first >= 0) {
                media.add(first);
            }
            this.referencing = Set.copyOf(media);
            if (Mp4Boxes.find(children, "mvex") != null) {
                // fragments may still name the identifiers of removed tracks
                var next = nextTrackId(Mp4Boxes.find(children, "mvhd"));
                this.chapterId = Math.max(largestAll + 1, next == U32_MAX ? 0 : next);
            } else {
                this.chapterId = largest + 1;
            }
            if (!chapters.isEmpty() && !keep && !referencing.isEmpty() && chapterId <= U32_MAX) {
                var end = durationMicros();
                if (end <= 0) {
                    end = demuxedDuration();
                }
                this.chapterTrack = Mp4ChapterTrack.of(chapters, end);
            } else {
                this.chapterTrack = null;
            }
        }

        /**
         * Places the new chapter samples at the start of the space the rewrite fills.
         *
         * @param slotStart the file offset where the rewritten boxes start
         */
        void place(long slotStart) {
            if (chapterTrack != null) {
                chapterData = Mp4Boxes.box("mdat", chapterTrack.data());
                chapterOffset = slotStart + 8;
            }
        }

        /**
         * Builds the rewritten boxes, shifting the chunk offsets that point at or after
         * {@code threshold} by {@code delta}.
         *
         * @param delta the shift of the data after the old {@code moov}
         * @param threshold the first old offset that moves
         * @return the chapter {@code mdat}, when there is one, and the new {@code moov}
         */
        byte[] build(long delta, long threshold) {
            var children = Mp4Boxes.children(moov, moovPayload(), moov.length);
            var lastTrak = -1;
            for (var index = 0; index < children.size(); index++) {
                if (children.get(index).type().equals("trak")) {
                    lastTrak = index;
                }
            }
            var out = new ByteWriter(moov.length + chapterData.length + 4096);
            out.bytes(chapterData);
            var body = new ByteWriter(moov.length + 4096);
            var udtaWritten = false;
            var timescale = movieTimescale();
            for (var index = 0; index < children.size(); index++) {
                var child = children.get(index);
                switch (child.type()) {
                    case "udta" -> {
                        body.bytes(udta);
                        udtaWritten = true;
                    }
                    case "trak" -> {
                        var id = trackId(moov, child);
                        if (!removed.contains(id)) {
                            body.bytes(rebuildTrack(child, delta, threshold,
                                    chapterTrack != null && referencing.contains(id) ? chapterId : -1));
                        }
                    }
                    case "mvhd" -> body.bytes(chapterTrack != null ? withNextTrackId(child) : copy(child));
                    case "mvex" -> body.bytes(chapterTrack != null || !removed.isEmpty() ? withChapterExtends(child)
                            : copy(child));
                    default -> body.bytes(moov, child.offset(), child.size());
                }
                if (index == lastTrak && chapterTrack != null) {
                    body.bytes(chapterTrack.trak(chapterId, timescale, chapterOffset,
                            chapterOffset + chapterTrack.dataLength() > U32_MAX));
                }
            }
            if (!udtaWritten && udta.length > 8) {
                body.bytes(udta);
            }
            Mp4Boxes.box(out, "moov", body.toByteArray());
            return out.toByteArray();
        }

        /**
         * {@return a copy of a child of {@code moov}}
         *
         * @param box the child
         */
        private byte[] copy(Mp4Boxes.Box box) {
            return Arrays.copyOfRange(moov, box.offset(), box.end());
        }

        /**
         * Copies {@code mvhd} with a {@code next_track_ID} past the new chapter track.
         *
         * @param mvhd the box
         * @return the box
         */
        private byte[] withNextTrackId(Mp4Boxes.Box mvhd) {
            var box = copy(mvhd);
            var at = mvhd.header() + nextTrackIdIndex(box[mvhd.header()]);
            if (at + 4 <= box.length) {
                var nextId = ByteArrays.u32be(box, at);
                if (nextId != U32_MAX && nextId <= chapterId) {
                    ByteArrays.put32be(box, at, chapterId + 1);
                }
            }
            return box;
        }

        /**
         * Copies {@code mvex} without the track extends boxes of the removed tracks and, when a chapter track is
         * written, with one for it.
         *
         * @param mvex the box
         * @return the box
         */
        private byte[] withChapterExtends(Mp4Boxes.Box mvex) {
            var out = new ByteWriter(mvex.size() + 32);
            for (var child : Mp4Boxes.children(moov, mvex.payload(), mvex.end())) {
                if (child.type().equals("trex") && child.payload() + 8 <= child.end()
                        && removed.contains(ByteArrays.u32be(moov, child.payload() + 4))) {
                    continue;
                }
                out.bytes(moov, child.offset(), child.size());
            }
            if (chapterTrack != null) {
                var trex = new ByteWriter(24);
                trex.u32be(0);
                trex.u32be(chapterId);
                trex.u32be(1);
                trex.u32be(0);
                trex.u32be(0);
                trex.u32be(0);
                Mp4Boxes.box(out, "trex", trex.toByteArray());
            }
            return Mp4Boxes.box("mvex", out.toByteArray());
        }

        /**
         * Rebuilds a track: its chunk offsets shifted, its references to removed chapter tracks
         * dropped and a reference to the new chapter track added.
         *
         * @param trak the {@code trak} box
         * @param delta the shift of the data after the old {@code moov}
         * @param threshold the first old offset that moves
         * @param reference the identifier of the chapter track to reference, or {@code -1}
         * @return the box
         */
        private byte[] rebuildTrack(Mp4Boxes.Box trak, long delta, long threshold, long reference) {
            if (delta == 0 && !dropping && reference < 0) {
                return copy(trak);
            }
            var children = Mp4Boxes.children(moov, trak.payload(), trak.end());
            var hasReferences = Mp4Boxes.find(children, "tref") != null;
            var out = new ByteWriter(trak.size() + 64);
            for (var child : children) {
                switch (child.type()) {
                    case "tref" -> out.bytes(references(child, reference));
                    case "mdia" -> out.bytes(delta != 0
                            ? Mp4Boxes.box("mdia", relocate(moov, child.payload(), child.end(), delta, threshold, 0))
                            : copy(child));
                    default -> out.bytes(moov, child.offset(), child.size());
                }
                if (child.type().equals("tkhd") && !hasReferences && reference >= 0) {
                    out.bytes(Mp4ChapterTrack.trackReference(reference));
                }
            }
            return Mp4Boxes.box("trak", out.toByteArray());
        }

        /**
         * Rebuilds a {@code tref} box: its {@code chap} references are dropped with the chapter
         * tracks or replaced by the reference to the new chapter track.
         *
         * @param tref the box
         * @param reference the identifier of the chapter track to reference, or {@code -1}
         * @return the box, or an empty array when no reference remains
         */
        private byte[] references(Mp4Boxes.Box tref, long reference) {
            var out = new ByteWriter(tref.size() + 12);
            for (var child : Mp4Boxes.children(moov, tref.payload(), tref.end())) {
                if (!child.type().equals("chap") || !dropping && reference < 0) {
                    out.bytes(moov, child.offset(), child.size());
                }
            }
            if (reference >= 0) {
                out.bytes(Mp4ChapterTrack.chapReference(reference));
            }
            return out.size() == 0 ? new byte[0] : Mp4Boxes.box("tref", out.toByteArray());
        }
    }

    /**
     * Returns the index of {@code next_track_ID} in the payload of {@code mvhd}.
     *
     * @param version the first byte of the payload, the box version
     * @return the index
     */
    private static int nextTrackIdIndex(byte version) {
        return (version & 0xFF) == 1 ? 108 : 96;
    }

    /**
     * Reads {@code next_track_ID} from {@code mvhd}.
     *
     * @param mvhd the box, or {@code null}
     * @return the identifier, or {@code 0} when the box is missing or too short
     */
    private long nextTrackId(Mp4Boxes.Box mvhd) {
        if (mvhd == null || mvhd.size() < mvhd.header() + 4) {
            return 0;
        }
        var at = mvhd.payload() + nextTrackIdIndex(moov[mvhd.payload()]);
        return at + 4 <= mvhd.end() ? ByteArrays.u32be(moov, at) : 0;
    }

    /**
     * {@return the movie timescale from {@code mvhd}, or 1000 when it has none}
     */
    private long movieTimescale() {
        var mvhd = Mp4Boxes.find(Mp4Boxes.children(moov, moovPayload(), moov.length), "mvhd");
        if (mvhd == null || mvhd.size() < 32) {
            return 1000;
        }
        var position = mvhd.payload();
        var timescale = ByteArrays.u32be(moov, position + ((moov[position] & 0xFF) == 1 ? 20 : 12));
        return timescale == 0 ? 1000 : timescale;
    }

    /**
     * Returns the identifier of a track from its {@code tkhd}.
     *
     * @param data the bytes holding the track
     * @param trak the {@code trak} box
     * @return the track identifier, or {@code -1} when it has no readable {@code tkhd}
     */
    private static long trackId(byte[] data, Mp4Boxes.Box trak) {
        var tkhd = Mp4Boxes.find(Mp4Boxes.children(data, trak.payload(), trak.end()), "tkhd");
        if (tkhd == null || tkhd.size() < 24) {
            return -1;
        }
        var version = data[tkhd.payload()] & 0xFF;
        var at = tkhd.payload() + (version == 1 ? 20 : 12);
        return at + 4 <= tkhd.end() ? ByteArrays.u32be(data, at) : -1;
    }

    /**
     * Returns the track identifiers the track fragment headers of the top-level movie fragments name.
     *
     * @return the identifiers
     */
    private Set<Long> fragmentTracks() {
        var ids = new HashSet<Long>();
        for (var index = 0; index < topLevel.size(); index++) {
            if (!topLevelTypes.get(index).equals("moof")) {
                continue;
            }
            var moof = TagIo.read(source, topLevel.get(index)[0], topLevel.get(index)[1], limits);
            var header = ByteArrays.u32be(moof, 0) == 1 ? 16 : 8;
            for (var traf : Mp4Boxes.children(moof, header, moof.length)) {
                if (!traf.type().equals("traf")) {
                    continue;
                }
                var tfhd = Mp4Boxes.find(Mp4Boxes.children(moof, traf.payload(), traf.end()), "tfhd");
                if (tfhd != null && tfhd.payload() + 8 <= tfhd.end()) {
                    ids.add(ByteArrays.u32be(moof, tfhd.payload() + 4));
                }
            }
        }
        return ids;
    }

    /**
     * Collects the tracks that other tracks reference as QuickTime chapter tracks
     * ({@code tref/chap}).
     *
     * @return the referenced track identifiers
     */
    private Set<Long> findChapterTracks() {
        var result = new HashSet<Long>();
        for (var trak : Mp4Boxes.children(moov, moovPayload(), moov.length)) {
            if (!trak.type().equals("trak")) {
                continue;
            }
            var tref = Mp4Boxes.find(Mp4Boxes.children(moov, trak.payload(), trak.end()), "tref");
            if (tref == null) {
                continue;
            }
            for (var reference : Mp4Boxes.children(moov, tref.payload(), tref.end())) {
                if (reference.type().equals("chap")) {
                    for (var at = reference.payload(); at + 4 <= reference.end(); at += 4) {
                        result.add(ByteArrays.u32be(moov, at));
                    }
                }
            }
        }
        return result;
    }

    /**
     * Rebuilds the children of a box of a track's media, shifting the chunk offsets of every
     * {@code stco} and {@code co64} box below it; a {@code stco} box with an offset the shift takes
     * past 32 bits becomes a {@code co64} box.
     *
     * @param data the bytes holding the boxes
     * @param start the index of the first child
     * @param end the end of the children
     * @param delta the shift
     * @param threshold the first old offset that moves
     * @param depth the nesting depth
     * @return the rebuilt children
     * @throws MediaException.Malformed if a shifted offset is negative
     */
    static byte[] relocate(byte[] data, int start, int end, long delta, long threshold, int depth) {
        var out = new ByteWriter(end - start + 64);
        for (var child : Mp4Boxes.children(data, start, end)) {
            switch (child.type()) {
                case "minf", "stbl" -> {
                    if (depth < 8) {
                        Mp4Boxes.box(out, child.type(), relocate(data, child.payload(), child.end(), delta, threshold,
                                depth + 1));
                    } else {
                        out.bytes(data, child.offset(), child.size());
                    }
                }
                case "stco", "co64" -> out.bytes(relocateTable(data, child, delta, threshold));
                default -> out.bytes(data, child.offset(), child.size());
            }
        }
        return out.toByteArray();
    }

    /**
     * Shifts the offsets of one {@code stco} or {@code co64} box.
     *
     * @param data the bytes holding the box
     * @param table the box
     * @param delta the shift
     * @param threshold the first old offset that moves
     * @return the shifted box, a {@code co64} box when a {@code stco} offset no longer fits 32 bits
     * @throws MediaException.Malformed if a shifted offset is negative
     */
    private static byte[] relocateTable(byte[] data, Mp4Boxes.Box table, long delta, long threshold) {
        var wide = table.type().equals("co64");
        var position = table.payload() + 4;
        if (table.end() - position < 4) {
            return Arrays.copyOfRange(data, table.offset(), table.end());
        }
        var entry = wide ? 8 : 4;
        var count = (int) Math.min(ByteArrays.u32be(data, position), (table.end() - position - 4) / entry);
        position += 4;
        var offsets = new long[count];
        var promote = false;
        for (var i = 0; i < count; i++) {
            var offset = wide ? ByteArrays.i64be(data, position) : ByteArrays.u32be(data, position);
            if (offset >= threshold) {
                offset += delta;
                if (offset < 0) {
                    throw new MediaException.Malformed("chunk offset shifted before the start of the file");
                }
            }
            offsets[i] = offset;
            promote |= !wide && offset > U32_MAX;
            position += entry;
        }
        var out = new ByteWriter(8 + count * (wide || promote ? 8 : 4));
        out.bytes(data, table.payload(), 4);
        out.u32be(count);
        for (var offset : offsets) {
            if (wide || promote) {
                out.u64be(offset);
            } else {
                out.u32be(offset);
            }
        }
        return Mp4Boxes.box(wide || promote ? "co64" : "stco", out.toByteArray());
    }

    /**
     * Builds the new {@code udta}.
     *
     * @param old the old {@code udta}, or {@code null}
     * @param newTags the tags
     * @param options the write options
     * @return the box
     * @throws MediaException.Unsupported if an integer atom holds a value it cannot store
     */
    private byte[] buildUdta(Mp4Boxes.Box old, MediaTags newTags, TagWriteOptions options) {
        var written = new HashSet<String>();
        var ilst = ilst(newTags, options.preserveUnknown() ? opaqueItems : List.of(), integerForms, written);
        var out = new ByteWriter(ilst.length + 256);
        var metaWritten = false;
        if (old != null) {
            for (var child : Mp4Boxes.children(moov, old.payload(), old.end())) {
                switch (child.type()) {
                    case "meta" -> {
                        if (isItunesMeta(child)) {
                            out.bytes(buildMeta(child, ilst));
                            metaWritten = true;
                        } else {
                            out.bytes(moov, child.offset(), child.size());
                        }
                    }
                    case "chpl" -> {
                    }
                    default -> {
                        if (quickTimeText(child) == null) {
                            out.bytes(moov, child.offset(), child.size());
                        }
                    }
                }
            }
        }
        if (!metaWritten && ilst.length > 8) {
            out.bytes(buildMeta(null, ilst));
        }
        if (!newTags.chapters().isEmpty()) {
            out.bytes(chapterBox(newTags.chapters()));
        }
        return Mp4Boxes.box("udta", out.toByteArray());
    }

    /**
     * Builds a {@code meta} box around an {@code ilst}.
     *
     * @param old the old {@code meta}, whose handler and other children are kept, or {@code null}
     * @param ilst the new {@code ilst}
     * @return the box
     */
    private byte[] buildMeta(Mp4Boxes.Box old, byte[] ilst) {
        var out = new ByteWriter(ilst.length + 64);
        if (old == null) {
            out.u32be(0);
            out.bytes(handler());
            out.bytes(ilst);
            return Mp4Boxes.box("meta", out.toByteArray());
        }
        var childrenStart = metaChildren(old);
        out.bytes(moov, old.payload(), childrenStart - old.payload());
        var hasHandler = false;
        for (var child : Mp4Boxes.children(moov, childrenStart, old.end())) {
            switch (child.type()) {
                case "ilst", "free", "skip" -> {
                }
                case "hdlr" -> {
                    hasHandler = true;
                    out.bytes(moov, child.offset(), child.size());
                }
                default -> out.bytes(moov, child.offset(), child.size());
            }
        }
        if (!hasHandler) {
            out.bytes(handler());
        }
        out.bytes(ilst);
        return Mp4Boxes.box("meta", out.toByteArray());
    }

    /**
     * {@return the {@code hdlr} box of an iTunes {@code meta}}
     */
    static byte[] handler() {
        var payload = new byte[25];
        payload[8] = 'm';
        payload[9] = 'd';
        payload[10] = 'i';
        payload[11] = 'r';
        payload[12] = 'a';
        payload[13] = 'p';
        payload[14] = 'p';
        payload[15] = 'l';
        return Mp4Boxes.box("hdlr", payload);
    }

    /**
     * Builds an {@code ilst}.
     *
     * <p>The atoms of {@code forms} are written in their form, widened to the width of
     * {@link #INTEGER_ATOMS} when the value needs it, the other atoms of {@link #INTEGER_ATOMS} as
     * signed integers of their width. A value that does not fit the form of an atom outside
     * {@link #INTEGER_ATOMS}, or that is not an integer for an atom of {@link #INTEGER_ATOMS} the
     * file stores as text, is written as UTF-8 text.
     *
     * @param newTags the tags
     * @param opaque the opaque items to keep, each a complete box
     * @param forms the integer form of other atoms, by atom code
     * @param written receives the lower-case keys written
     * @return the box
     * @throws MediaException.Unsupported if a value of an atom of {@link #INTEGER_ATOMS} the file
     * does not store as text is not an integer its form holds
     */
    static byte[] ilst(MediaTags newTags, List<byte[]> opaque, Map<String, IntegerForm> forms, Set<String> written) {
        var grouped = new LinkedHashMap<String, List<String>>();
        for (var entry : newTags.entries()) {
            grouped.computeIfAbsent(entry.key(), _ -> new ArrayList<>()).add(entry.value());
        }
        var out = new ByteWriter(4096);
        var codes = new HashSet<String>();
        for (var group : grouped.entrySet()) {
            var key = group.getKey();
            var values = group.getValue();
            var standard = StandardTag.fromName(key);
            Optional<String> atom = standard.isPresent() ? standard.get().mp4Atom() : isAtomCode(key) ? Optional.of(key) : Optional.empty();
            if (atom.isPresent()) {
                var code = atom.get();
                var item = new ByteWriter(64);
                switch (code) {
                    case "trkn", "disk" -> {
                        var parts = values.getFirst().split("/", 2);
                        var number = parseShort(parts[0]);
                        var total = parts.length > 1 ? parseShort(parts[1]) : 0;
                        var payload = new byte[code.equals("trkn") ? 8 : 6];
                        payload[2] = (byte) (number >>> 8);
                        payload[3] = (byte) number;
                        payload[4] = (byte) (total >>> 8);
                        payload[5] = (byte) total;
                        dataAtom(item, 0, payload);
                    }
                    case "cpil" -> dataAtom(item, TYPE_INTEGER, new byte[]{(byte) (isTrue(values.getFirst()) ? 1 : 0)});
                    default -> {
                        var known = INTEGER_ATOMS.get(code);
                        var form = forms.get(code);
                        var textual = form != null && form.type() == TYPE_UTF8;
                        if ((form == null || textual) && known != null) {
                            form = new IntegerForm(TYPE_INTEGER, known);
                        }
                        for (var value : values) {
                            var used = form;
                            var integer = used == null ? null : encodeInteger(code, value, used);
                            if (integer == null && known != null && used.width() < known) {
                                used = new IntegerForm(used.type(), known);
                                integer = encodeInteger(code, value, used);
                            }
                            if (integer == null && known != null && !textual) {
                                throw new MediaException.Unsupported("mp4", "the MP4 " + code + " atom holds "
                                        + (used.type() == TYPE_UNSIGNED ? "an unsigned" : "a signed") + " "
                                        + 8 * used.width() + "-bit integer, not \"" + value + "\"");
                            }
                            if (integer != null) {
                                dataAtom(item, used.type(), integer);
                            } else {
                                dataAtom(item, TYPE_UTF8, value.getBytes(StandardCharsets.UTF_8));
                            }
                        }
                    }
                }
                Mp4Boxes.box(out, code, item.toByteArray());
                codes.add(code);
            } else {
                var name = standard.isPresent() ? standard.get().vorbisField() : key;
                var item = new ByteWriter(64);
                Mp4Boxes.box(item, "mean", concat(new byte[4], APPLE_MEAN.getBytes(StandardCharsets.UTF_8)));
                Mp4Boxes.box(item, "name", concat(new byte[4], name.getBytes(StandardCharsets.UTF_8)));
                for (var value : values) {
                    dataAtom(item, TYPE_UTF8, value.getBytes(StandardCharsets.UTF_8));
                }
                Mp4Boxes.box(out, "----", item.toByteArray());
            }
            written.add(key.toLowerCase(Locale.ROOT));
        }
        if (!newTags.pictures().isEmpty()) {
            var item = new ByteWriter(4096);
            for (var picture : newTags.pictures()) {
                var format = picture.format();
                var type = format.isEmpty() ? 0 : switch (format.get()) {
                    case JPEG -> TYPE_JPEG;
                    case PNG -> TYPE_PNG;
                    case BMP -> TYPE_BMP;
                    default -> 0;
                };
                dataAtom(item, type, picture.data());
            }
            Mp4Boxes.box(out, "covr", item.toByteArray());
            codes.add("covr");
        }
        for (var item : opaque) {
            var code = Mp4Boxes.type(item, 4);
            if (!codes.contains(code) || code.equals("----")) {
                out.bytes(item);
            }
        }
        return Mp4Boxes.box("ilst", out.toByteArray());
    }

    /**
     * Returns whether a free key names an {@code ilst} atom rather than a free-form item.
     *
     * @param key a free-form item key
     * @return {@code true} for four characters starting with a lower-case letter or the copyright sign
     */
    private static boolean isAtomCode(String key) {
        if (key.length() != 4) {
            return false;
        }
        var first = key.charAt(0);
        if (first != COPYRIGHT_SIGN && (first < 'a' || first > 'z')) {
            return false;
        }
        for (var i = 0; i < 4; i++) {
            if (key.charAt(i) > 0xFF) {
                return false;
            }
        }
        return true;
    }

    /**
     * Parses a number for a {@code trkn} or {@code disk} item.
     *
     * @param text the decimal text to parse
     * @return the number clamped to 16 bits, or {@code 0} when not a number
     */
    private static int parseShort(String text) {
        try {
            return Math.max(0, Math.min(0xFFFF, Integer.parseInt(text.trim())));
        } catch (NumberFormatException exception) {
            return 0;
        }
    }

    /**
     * Encodes the value of an integer atom.
     *
     * @param code the atom code
     * @param value the decimal text, or for the flags of {@link #FLAG_ATOMS} also {@code true},
     * {@code yes}, {@code false} or {@code no}
     * @param form the encoding
     * @return the big-endian bytes, or {@code null} when the value is not an integer the form holds
     */
    private static byte[] encodeInteger(String code, String value, IntegerForm form) {
        var text = value.trim().toLowerCase(Locale.ROOT);
        long number;
        if (FLAG_ATOMS.contains(code) && (text.equals("true") || text.equals("yes"))) {
            number = 1;
        } else if (FLAG_ATOMS.contains(code) && (text.equals("false") || text.equals("no"))) {
            number = 0;
        } else {
            try {
                number = form.type() == TYPE_UNSIGNED ? Long.parseUnsignedLong(text) : Long.parseLong(text);
            } catch (NumberFormatException exception) {
                return null;
            }
        }
        var bits = 8 * form.width();
        if (bits < 64) {
            var fits = form.type() == TYPE_UNSIGNED ? number >>> bits == 0
                    : number >= -1L << (bits - 1) && number <= ~(-1L << (bits - 1));
            if (!fits) {
                return null;
            }
        }
        var bytes = new byte[form.width()];
        for (var index = bytes.length - 1; index >= 0; index--) {
            bytes[index] = (byte) number;
            number >>>= 8;
        }
        return bytes;
    }

    /**
     * Returns whether a flag value is true.
     *
     * @param text the flag text, such as {@code "1"} or {@code "true"}
     * @return {@code true} for {@code "1"}, {@code "true"} and {@code "yes"}
     */
    private static boolean isTrue(String text) {
        var value = text.trim().toLowerCase(Locale.ROOT);
        return value.equals("1") || value.equals("true") || value.equals("yes");
    }

    /**
     * Writes a {@code data} atom.
     *
     * @param out the destination
     * @param type the data type
     * @param payload the value
     */
    private static void dataAtom(ByteWriter out, int type, byte[] payload) {
        var body = new byte[8 + payload.length];
        ByteArrays.put32be(body, 0, type);
        System.arraycopy(payload, 0, body, 8, payload.length);
        Mp4Boxes.box(out, "data", body);
    }

    /**
     * Concatenates two arrays.
     *
     * @param first the bytes that come first
     * @param second the bytes appended after {@code first}
     * @return the concatenation
     */
    private static byte[] concat(byte[] first, byte[] second) {
        var result = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }

    /**
     * Builds a Nero chapter list.
     *
     * @param chapters the chapters, at most {@value Mp4ChapterTrack#MAX_NERO_CHAPTERS} of which are kept
     * @return the box
     */
    static byte[] chapterBox(List<Chapter> chapters) {
        var out = new ByteWriter(16 + chapters.size() * 32);
        out.u8(1);
        out.u24be(0);
        out.u32be(0);
        var count = Math.min(Mp4ChapterTrack.MAX_NERO_CHAPTERS, chapters.size());
        out.u8(count);
        for (var index = 0; index < count; index++) {
            var chapter = chapters.get(index);
            out.u64be(chapter.startMicros() * 10);
            var title = truncate(chapter.title().orElse(""));
            out.u8(title.length);
            out.bytes(title);
        }
        return Mp4Boxes.box("chpl", out.toByteArray());
    }

    /**
     * Encodes a chapter title in UTF-8, cut at a character boundary to fit 255 bytes.
     *
     * @param title the chapter title to encode
     * @return the bytes
     */
    private static byte[] truncate(String title) {
        var bytes = title.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= 255) {
            return bytes;
        }
        var end = 255;
        while (end > 0 && (bytes[end] & 0xC0) == 0x80) {
            end--;
        }
        return Arrays.copyOf(bytes, end);
    }

    /**
     * Copies the boxes after the old {@code moov}, correcting the absolute offsets of movie
     * fragments and of the fragment random access index.
     */
    private static final class Shift extends EditPlan.Transform {
        /**
         * The largest fragment box held in memory to be corrected.
         */
        private static final long MAX_FRAGMENT = 64L << 20;

        /**
         * The offset of the first copied byte.
         */
        private final long start;

        /**
         * The number of copied bytes.
         */
        private final long length;

        /**
         * The shift of the copied bytes.
         */
        private final long delta;

        /**
         * The resource bounds.
         */
        private final MediaLimits limits;

        /**
         * Constructs the transformation.
         *
         * @param start the offset of the first copied byte
         * @param length the number of copied bytes
         * @param delta the shift
         * @param limits the resource bounds
         */
        Shift(long start, long length, long delta, MediaLimits limits) {
            this.start = start;
            this.length = length;
            this.delta = delta;
            this.limits = limits;
        }

        @Override
        public long length() {
            return length;
        }

        @Override
        public void writeTo(MediaSource source, MediaSink sink, byte[] buffer) {
            var position = start;
            var end = start + length;
            while (position < end) {
                if (end - position < 8) {
                    EditPlan.copy(source, sink, position, end - position, buffer);
                    return;
                }
                var header = TagIo.read(source, position, Math.min(16, end - position), limits);
                long size = ByteArrays.u32be(header, 0);
                if (size == 1 && header.length == 16) {
                    size = ByteArrays.i64be(header, 8);
                } else if (size == 0) {
                    size = end - position;
                }
                if (size < 8 || size > end - position) {
                    EditPlan.copy(source, sink, position, end - position, buffer);
                    return;
                }
                var type = Mp4Boxes.type(header, 4);
                if ((type.equals("moof") || type.equals("mfra")) && size <= MAX_FRAGMENT) {
                    var box = TagIo.read(source, position, size, limits);
                    if (type.equals("moof")) {
                        patchFragment(box, 8, box.length, 0);
                    } else {
                        patchIndex(box);
                    }
                    sink.write(box, 0, box.length);
                } else {
                    EditPlan.copy(source, sink, position, size, buffer);
                }
                position += size;
            }
        }

        /**
         * Shifts the explicit base data offsets of the {@code tfhd} boxes of a fragment.
         *
         * @param box the fragment bytes
         * @param from the index of the first child
         * @param to the end of the children
         * @param depth the nesting depth
         */
        private void patchFragment(byte[] box, int from, int to, int depth) {
            if (depth > 2) {
                return;
            }
            for (var child : Mp4Boxes.children(box, from, to)) {
                if (child.type().equals("traf")) {
                    patchFragment(box, child.payload(), child.end(), depth + 1);
                } else if (child.type().equals("tfhd") && child.size() >= 24) {
                    var flags = ByteArrays.u32be(box, child.payload()) & 0xFFFFFF;
                    if ((flags & 1) != 0) {
                        var at = child.payload() + 8;
                        ByteArrays.put64be(box, at, ByteArrays.i64be(box, at) + delta);
                    }
                }
            }
        }

        /**
         * Shifts the fragment offsets of the {@code tfra} boxes of a random access index.
         *
         * @param box the {@code mfra} bytes
         */
        private void patchIndex(byte[] box) {
            for (var child : Mp4Boxes.children(box, 8, box.length)) {
                if (!child.type().equals("tfra") || child.size() < 24) {
                    continue;
                }
                var position = child.payload();
                var version = box[position] & 0xFF;
                var sizes = (int) ByteArrays.u32be(box, position + 8);
                var count = ByteArrays.u32be(box, position + 12);
                position += 16;
                var timeSize = version == 1 ? 8 : 4;
                var entry = 2 * timeSize + ((sizes >>> 4) & 3) + 1 + ((sizes >>> 2) & 3) + 1 + (sizes & 3) + 1;
                for (var i = 0L; i < count && child.end() - position >= entry; i++) {
                    var at = position + timeSize;
                    if (version == 1) {
                        ByteArrays.put64be(box, at, ByteArrays.i64be(box, at) + delta);
                    } else {
                        ByteArrays.put32be(box, at, ByteArrays.u32be(box, at) + delta);
                    }
                    position += entry;
                }
            }
        }
    }
}
