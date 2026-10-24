package com.github.auties00.smalt.internal.tags;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.container.AttachedPicture;
import com.github.auties00.smalt.container.Chapter;
import com.github.auties00.smalt.internal.io.ByteArrays;
import com.github.auties00.smalt.internal.io.ByteWriter;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.metadata.MediaTags;
import com.github.auties00.smalt.metadata.StandardTag;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Reads and writes ID3v2 tags, versions 2.2 (read only), 2.3 and 2.4.
 *
 * <p>Reading turns the tag into frames held in a canonical form: the per-frame
 * unsynchronization, data length indicator and compression of the stored frame are undone, so a
 * frame can be written back without them. Text, comment, lyrics, URL, picture and chapter frames
 * are then decoded into {@link MediaTags}; every other frame (binary frames such as {@code PRIV},
 * {@code GEOB}, {@code POPM}, comments with a description) is kept as an opaque {@link Frame}
 * that a rewrite can carry over, except a {@code SEEK} frame, whose offset no longer holds once
 * the tag is rewritten ({@link #seekOffset()} reads it).
 *
 * <p>Writing produces a tag without unsynchronization, extended header or footer: text in
 * ISO-8859-1 when it fits and otherwise UTF-16 (version 2.3) or UTF-8 (version 2.4); several
 * values of one frame joined with {@code /} (version 2.3) or separated by NUL (version 2.4).
 *
 * <p>Every size read from the tag is checked against the bytes that hold it, and decompression is
 * bounded by the allocation limit, so hostile tags raise {@link MediaException} only.
 */
public final class Id3v2Tag {
    /**
     * The size of the tag header, in bytes.
     */
    public static final int HEADER_SIZE = 10;

    /**
     * The largest tag body a syncsafe size can declare.
     */
    public static final int MAX_BODY = (1 << 28) - 1;

    /**
     * The largest number of frames decoded from one tag.
     */
    private static final int MAX_FRAMES = 1 << 16;

    /**
     * The canonical status flag: discard the frame when the tag is altered and the frame unknown.
     */
    public static final int TAG_ALTER = 1;

    /**
     * The canonical status flag: discard the frame when the file is altered.
     */
    public static final int FILE_ALTER = 2;

    /**
     * The canonical status flag: the frame is read only.
     */
    public static final int READ_ONLY = 4;

    /**
     * The canonical format flag: the payload starts with a group identifier byte.
     */
    public static final int GROUPING = 8;

    /**
     * The canonical format flag: the payload is encrypted and stored as read.
     */
    public static final int ENCRYPTED = 16;

    /**
     * Matches an ISO 8601 date with optional time, the form version 2.4 timestamps take.
     */
    private static final Pattern TIMESTAMP = Pattern.compile(
            "(\\d{4})(?:-(\\d{2})(?:-(\\d{2})(?:[T ](\\d{2})(?::(\\d{2})(?::(\\d{2}))?)?)?)?)?");

    /**
     * The frames whose version 2.3 values are joined with {@code /}.
     */
    private static final String SLASH = "/";

    /**
     * The owner of the {@code UFID} frame holding the MusicBrainz recording identifier.
     */
    private static final String MUSICBRAINZ = "http://musicbrainz.org";

    /**
     * The {@code TXXX} description that stores a MusicBrainz recording identifier a {@code UFID} frame cannot hold.
     */
    private static final String MUSICBRAINZ_TRACK_ID = "MusicBrainz Track Id";

    /**
     * The tag version (2, 3 or 4).
     */
    private final int major;

    /**
     * The frames, in tag order.
     */
    private final List<Frame> frames;

    /**
     * Whether the version 2.4 extended header flags the tag as an update of an earlier one.
     */
    private final boolean update;

    /**
     * Constructs a tag.
     *
     * @param major the tag version
     * @param frames the frames in tag order, copied
     * @param update whether the tag is flagged as an update
     */
    private Id3v2Tag(int major, List<Frame> frames, boolean update) {
        this.major = major;
        this.frames = List.copyOf(frames);
        this.update = update;
    }

    /**
     * {@return the version of the tag: 2, 3 or 4}
     */
    public int major() {
        return major;
    }

    /**
     * {@return the frames in tag order; unmodifiable}
     */
    public List<Frame> frames() {
        return frames;
    }

    /**
     * {@return whether the extended header of a version 2.4 tag flags it as an update, whose unique frames override
     * those of a tag found earlier in the file}
     */
    public boolean isUpdate() {
        return update;
    }

    /**
     * Returns the offset of the next tag that a {@code SEEK} frame declares.
     *
     * @return the number of bytes from the end of this tag to the start of the next, or {@code -1} when the tag has no
     * well-formed {@code SEEK} frame
     */
    public long seekOffset() {
        for (var frame : frames) {
            if (frame.id().equals("SEEK") && (frame.flags() & ENCRYPTED) == 0) {
                var data = frame.data();
                return data.length >= 4 ? ByteArrays.u32be(data, 0) : -1;
            }
        }
        return -1;
    }

    /**
     * Returns the total size of a tag from its header.
     *
     * @param header the bytes holding a candidate header
     * @param offset the index of its first byte; ten bytes must follow
     * @return the size of the header, body and footer, or {@code -1} when the bytes are not a valid ID3v2 header
     */
    public static long size(byte[] header, int offset) {
        if (offset < 0 || offset + HEADER_SIZE > header.length || header[offset] != 'I' || header[offset + 1] != 'D'
                || header[offset + 2] != '3') {
            return -1;
        }
        var major = header[offset + 3] & 0xFF;
        var minor = header[offset + 4] & 0xFF;
        if (major < 2 || major > 4 || minor == 0xFF) {
            return -1;
        }
        var body = syncsafe(header, offset + 6);
        if (body < 0) {
            return -1;
        }
        var footer = major == 4 && (header[offset + 5] & 0x10) != 0 ? HEADER_SIZE : 0;
        return HEADER_SIZE + (long) body + footer;
    }

    /**
     * Parses a tag.
     *
     * <p>Parsing stops at padding, at a frame identifier that is not four upper-case letters or
     * digits, or at a frame whose size runs past the tag: the frames before it are kept. Frames
     * that cannot be canonicalized (an unknown compression, a decompressed size over the limits)
     * are dropped.
     *
     * @param tag the header and body of the tag
     * @param limits the resource bounds for decompression
     * @return the tag
     * @throws MediaException.Malformed if the header is not an ID3v2 header
     */
    public static Id3v2Tag parse(byte[] tag, MediaLimits limits) {
        return parse(tag, limits, _ -> true);
    }

    /**
     * Parses a tag, keeping only the frames whose identifier {@code keep} accepts.
     *
     * <p>Behaves like {@link #parse(byte[], MediaLimits)}; a rejected frame is skipped without
     * being copied or decompressed.
     *
     * @param tag the header and body of the tag
     * @param limits the resource bounds for decompression
     * @param keep accepts the identifiers, as stored, of the frames to keep
     * @return the tag
     * @throws MediaException.Malformed if the header is not an ID3v2 header
     */
    public static Id3v2Tag parse(byte[] tag, MediaLimits limits, Predicate<String> keep) {
        var total = size(tag, 0);
        if (total < 0) {
            throw new MediaException.Malformed("not an ID3v2 tag header");
        }
        var major = tag[3] & 0xFF;
        var flags = tag[5] & 0xFF;
        var end = (int) Math.min(tag.length, total - (major == 4 && (flags & 0x10) != 0 ? HEADER_SIZE : 0));
        var body = Arrays.copyOfRange(tag, HEADER_SIZE, Math.max(HEADER_SIZE, end));
        var length = body.length;
        var tagUnsync = (flags & 0x80) != 0;
        if (major < 4 && tagUnsync) {
            length = removeUnsynchronization(body, 0, length);
        }
        var offset = 0;
        var update = false;
        if ((flags & 0x40) != 0 && major >= 3) {
            if (length < 4) {
                return new Id3v2Tag(major, List.of(), false);
            }
            var extended = major == 4 ? syncsafe(body, 0) : (int) Math.min(Integer.MAX_VALUE, ByteArrays.u32be(body, 0) + 4);
            if (extended < 0 || extended > length) {
                return new Id3v2Tag(major, List.of(), false);
            }
            update = major == 4 && extended >= 6 && body[4] >= 1 && (body[5] & 0x40) != 0;
            offset = extended;
        }
        var frames = new ArrayList<Frame>();
        readFrames(body, offset, length, major, major == 4 && tagUnsync, limits, keep, frames);
        return new Id3v2Tag(major, frames, update);
    }

    /**
     * Reads the frames of a tag or chapter body into canonical frames.
     *
     * @param body the bytes
     * @param offset the index of the first frame
     * @param end the index of the end of the frames
     * @param major the tag version
     * @param forceUnsync whether every frame is unsynchronized (version 2.4 tag flag)
     * @param limits the resource bounds
     * @param keep accepts the identifiers of the frames to keep
     * @param frames receives the frames
     */
    private static void readFrames(byte[] body, int offset, int end, int major, boolean forceUnsync,
                                   MediaLimits limits, Predicate<String> keep, List<Frame> frames) {
        var headerSize = major == 2 ? 6 : 10;
        while (end - offset >= headerSize && frames.size() < MAX_FRAMES) {
            String id;
            int size;
            var rawFlags = 0;
            if (major == 2) {
                id = new String(body, offset, 3, StandardCharsets.ISO_8859_1);
                size = ByteArrays.u24be(body, offset + 3);
            } else {
                id = new String(body, offset, 4, StandardCharsets.ISO_8859_1);
                size = major == 4 ? syncsafe(body, offset + 4) : (int) Math.min(Integer.MAX_VALUE, ByteArrays.u32be(body, offset + 4));
                if (major == 4 && size < 0) {
                    size = (int) Math.min(Integer.MAX_VALUE, ByteArrays.u32be(body, offset + 4));
                }
                rawFlags = ByteArrays.u16be(body, offset + 8);
            }
            if (!isValidId(id)) {
                return;
            }
            offset += headerSize;
            if (size < 0 || size > end - offset) {
                return;
            }
            if (keep.test(id)) {
                var frame = canonicalize(id, major, rawFlags, body, offset, size, forceUnsync, limits);
                if (frame != null) {
                    frames.add(frame);
                }
            }
            offset += size;
        }
    }

    /**
     * Undoes the storage transformations of one frame.
     *
     * @param id the frame identifier
     * @param major the tag version
     * @param rawFlags the stored frame flags
     * @param body the bytes holding the frame
     * @param offset the index of the frame payload
     * @param size the stored payload size
     * @param forceUnsync whether the tag declares every frame unsynchronized
     * @param limits the resource bounds
     * @return the canonical frame, or {@code null} when it cannot be canonicalized
     */
    private static Frame canonicalize(String id, int major, int rawFlags, byte[] body, int offset, int size,
                                      boolean forceUnsync, MediaLimits limits) {
        var payload = Arrays.copyOfRange(body, offset, offset + size);
        if (major == 2) {
            return new Frame(id, 2, 0, payload);
        }
        int status;
        boolean grouping;
        boolean compressed;
        boolean encrypted;
        boolean unsync = false;
        boolean lengthIndicator = false;
        if (major == 3) {
            status = ((rawFlags & 0x8000) != 0 ? TAG_ALTER : 0) | ((rawFlags & 0x4000) != 0 ? FILE_ALTER : 0)
                    | ((rawFlags & 0x2000) != 0 ? READ_ONLY : 0);
            compressed = (rawFlags & 0x0080) != 0;
            encrypted = (rawFlags & 0x0040) != 0;
            grouping = (rawFlags & 0x0020) != 0;
        } else {
            status = ((rawFlags & 0x4000) != 0 ? TAG_ALTER : 0) | ((rawFlags & 0x2000) != 0 ? FILE_ALTER : 0)
                    | ((rawFlags & 0x1000) != 0 ? READ_ONLY : 0);
            grouping = (rawFlags & 0x0040) != 0;
            compressed = (rawFlags & 0x0008) != 0;
            encrypted = (rawFlags & 0x0004) != 0;
            unsync = (rawFlags & 0x0002) != 0 || forceUnsync;
            lengthIndicator = (rawFlags & 0x0001) != 0;
        }
        if (encrypted) {
            return new Frame(id, major, status | ENCRYPTED | (grouping ? GROUPING : 0), payload, rawFlags);
        }
        var position = 0;
        var group = -1;
        if (major == 3) {
            if (compressed) {
                position += 4;
            }
            if (grouping) {
                if (position >= payload.length) {
                    return null;
                }
                group = payload[position++] & 0xFF;
            }
        } else {
            if (grouping) {
                if (position >= payload.length) {
                    return null;
                }
                group = payload[position++] & 0xFF;
            }
            if (lengthIndicator) {
                position += 4;
            }
        }
        if (position > payload.length) {
            return null;
        }
        var data = position == 0 ? payload : Arrays.copyOfRange(payload, position, payload.length);
        if (unsync) {
            data = Arrays.copyOf(data, removeUnsynchronization(data, 0, data.length));
        }
        if (compressed) {
            data = inflate(data, limits);
            if (data == null) {
                return null;
            }
        }
        if (group >= 0) {
            var withGroup = new byte[data.length + 1];
            withGroup[0] = (byte) group;
            System.arraycopy(data, 0, withGroup, 1, data.length);
            data = withGroup;
        }
        return new Frame(id, major, status | (grouping ? GROUPING : 0), data);
    }

    /**
     * Decompresses a zlib frame payload under the allocation limit.
     *
     * @param data the compressed bytes
     * @param limits the resource bounds
     * @return the decompressed bytes, or {@code null} when they are corrupt
     * @throws MediaException.LimitExceeded if the output exceeds the allocation limit
     */
    private static byte[] inflate(byte[] data, MediaLimits limits) {
        var inflater = new Inflater();
        try {
            inflater.setInput(data);
            var out = new ByteArrayOutputStream(Math.max(64, data.length * 2));
            var buffer = new byte[8192];
            while (!inflater.finished()) {
                var count = inflater.inflate(buffer);
                if (count == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                    return null;
                }
                out.write(buffer, 0, count);
                LimitChecks.checkAllocation(limits, out.size());
            }
            return out.toByteArray();
        } catch (DataFormatException exception) {
            return null;
        } finally {
            inflater.end();
        }
    }

    /**
     * Decodes the frames into tags, pictures and chapters.
     *
     * @return the decoded content
     */
    public Content decode() {
        var entries = new ArrayList<MediaTags.Entry>();
        var pictures = new ArrayList<AttachedPicture>();
        var opaque = new ArrayList<Frame>();
        var chapterFrames = new ArrayList<Frame>();
        var chapters = new ArrayList<Chapter>();
        String year = null;
        String dayMonth = null;
        String time = null;
        String timestamp = null;
        var lyricsSeen = false;
        var ratingSeen = false;
        var dateIndex = -1;
        for (var frame : frames) {
            if ((frame.flags() & ENCRYPTED) != 0) {
                opaque.add(frame);
                continue;
            }
            var id = frame.id().length() == 3 ? upgrade(frame.id()) : frame.id();
            var data = frame.data();
            if (dateIndex < 0 && (id.equals("TYER") || id.equals("TDRC"))) {
                dateIndex = entries.size();
            }
            try {
                switch (id) {
                    case "TYER" -> year = first(text(data));
                    case "TDAT" -> dayMonth = first(text(data));
                    case "TIME" -> time = first(text(data));
                    case "TDRC" -> timestamp = first(text(data));
                    case "TRDA" -> {
                    }
                    case "TCON" -> {
                        for (var value : text(data)) {
                            for (var genre : Id3Genres.expand(value)) {
                                add(entries, StandardTag.GENRE.key(), genre);
                            }
                        }
                    }
                    case "TXXX" -> {
                        var values = text(data);
                        if (!values.isEmpty()) {
                            var key = values.getFirst().isBlank() ? "TXXX" : values.getFirst();
                            for (var value : values.subList(1, values.size())) {
                                add(entries, key, value);
                            }
                        }
                    }
                    case "COMM" -> {
                        var comment = describedText(data);
                        if (comment == null || !comment[0].isEmpty()) {
                            opaque.add(frame);
                        } else {
                            add(entries, StandardTag.COMMENT.key(), comment[1]);
                        }
                    }
                    case "USLT" -> {
                        var lyrics = describedText(data);
                        if (lyrics == null || lyricsSeen) {
                            opaque.add(frame);
                        } else {
                            lyricsSeen = true;
                            add(entries, StandardTag.LYRICS.key(), lyrics[1]);
                        }
                    }
                    case "APIC", "PIC" -> {
                        var picture = decodePicture(data, id.equals("PIC"));
                        if (picture != null) {
                            pictures.add(picture);
                        } else {
                            opaque.add(frame);
                        }
                    }
                    case "CHAP", "CTOC" -> chapterFrames.add(frame);
                    case "SEEK" -> {
                    }
                    case "UFID" -> {
                        var identifier = musicBrainzId(data);
                        if (identifier != null) {
                            add(entries, StandardTag.MUSICBRAINZ_RECORDING_ID.key(), identifier);
                        } else {
                            opaque.add(frame);
                        }
                    }
                    case "POPM" -> {
                        var popularimeter = ratingSeen ? null : Popularimeter.parse(data);
                        if (popularimeter != null) {
                            ratingSeen = true;
                            add(entries, StandardTag.RATING.key(), popularimeter.ratingText());
                        }
                        opaque.add(frame);
                    }
                    default -> {
                        if (id.length() == 4 && id.charAt(0) == 'T') {
                            for (var value : text(data)) {
                                add(entries, id, value);
                            }
                        } else if (id.length() == 4 && id.charAt(0) == 'W' && !id.equals("WXXX")) {
                            var url = latin1Text(data);
                            if (!url.isBlank()) {
                                add(entries, id, url);
                            }
                        } else {
                            opaque.add(frame);
                        }
                    }
                }
            } catch (RuntimeException exception) {
                opaque.add(frame);
            }
        }
        var date = timestamp != null ? timestamp : composeDate(year, dayMonth, time);
        if (date != null && !date.isBlank()) {
            entries.add(Math.max(0, Math.min(dateIndex, entries.size())), new MediaTags.Entry(StandardTag.DATE.key(), date));
        }
        decodeChapters(chapterFrames, chapters);
        return new Content(entries, pictures, chapters, opaque, chapterFrames);
    }

    /**
     * Adds a non-blank value.
     *
     * @param entries the target
     * @param key the tag key
     * @param value the tag value; a blank value is ignored
     */
    private static void add(List<MediaTags.Entry> entries, String key, String value) {
        if (value.isBlank()) {
            return;
        }
        try {
            entries.add(new MediaTags.Entry(key, value));
        } catch (IllegalArgumentException ignored) {
            // a key no format can store, such as one with control characters, is dropped
        }
    }

    /**
     * Returns the first value of a list.
     *
     * @param values the decoded values of a frame
     * @return the first, or {@code null} when empty
     */
    private static String first(List<String> values) {
        return values.isEmpty() ? null : values.getFirst();
    }

    /**
     * Builds an ISO 8601 date from the version 2.3 year, day-month and time frames.
     *
     * @param year the {@code TYER} value, or {@code null}
     * @param dayMonth the {@code TDAT} value ({@code DDMM}), or {@code null}
     * @param time the {@code TIME} value ({@code HHMM}), or {@code null}
     * @return the date, or {@code null} without a year
     */
    private static String composeDate(String year, String dayMonth, String time) {
        if (year == null) {
            return null;
        }
        if (!year.matches("\\d{4}") || dayMonth == null || !dayMonth.matches("\\d{4}")) {
            return year;
        }
        var date = year + "-" + dayMonth.substring(2, 4) + "-" + dayMonth.substring(0, 2);
        if (time != null && time.matches("\\d{4}")) {
            date += "T" + time.substring(0, 2) + ":" + time.substring(2, 4);
        }
        return date;
    }

    /**
     * Decodes the chapter frames into chapters in start order, ending each chapter without an end
     * time at the start of the next.
     *
     * @param frames the {@code CHAP} and {@code CTOC} frames
     * @param chapters receives the chapters
     */
    private void decodeChapters(List<Frame> frames, List<Chapter> chapters) {
        var spans = spans(frames, major);
        for (var k = 0; k < spans.size(); k++) {
            var span = spans.get(k);
            var end = span.endMillis();
            if (end < 0) {
                end = Math.max(k + 1 < spans.size() ? spans.get(k + 1).startMillis() : span.startMillis(), span.startMillis());
            }
            chapters.add(Chapter.of(span.startMillis() * 1000, end * 1000, span.title()));
        }
    }

    /**
     * Returns the chapters of the {@code CHAP} frames in start order, with the end times as
     * stored.
     *
     * <p>A chapter whose element identifier repeats is taken from its first frame; its title is
     * its first {@code TIT2} sub-frame.
     *
     * @return the chapters; unmodifiable
     */
    public List<ChapterSpan> chapterSpans() {
        return spans(frames, major);
    }

    /**
     * Decodes {@code CHAP} frames into chapters in start order.
     *
     * @param frames the frames, of which only {@code CHAP} frames are read
     * @param major the tag version
     * @return the chapters; unmodifiable
     */
    private static List<ChapterSpan> spans(List<Frame> frames, int major) {
        var byId = new LinkedHashMap<String, ChapterSpan>();
        for (var frame : frames) {
            if (!frame.id().equals("CHAP")) {
                continue;
            }
            var data = frame.data();
            var idEnd = terminator(data, 0, data.length, 0);
            var position = idEnd + 1;
            if (data.length - position < 16) {
                continue;
            }
            var elementId = new String(data, 0, idEnd, StandardCharsets.ISO_8859_1);
            if (byId.containsKey(elementId)) {
                continue;
            }
            var start = ByteArrays.u32be(data, position);
            var end = ByteArrays.u32be(data, position + 4);
            var sub = new ArrayList<Frame>();
            readFrames(data, position + 16, data.length, major == 2 ? 3 : major, false, MediaLimits.DEFAULT,
                    "TIT2"::equals, sub);
            String title = null;
            for (var subFrame : sub) {
                title = first(text(subFrame.data()));
                if (title != null) {
                    break;
                }
            }
            byId.put(elementId, new ChapterSpan(start, end == 0xFFFFFFFFL || end < start ? -1 : end, title));
        }
        var spans = new ArrayList<>(byId.values());
        spans.sort(Comparator.comparingLong(ChapterSpan::startMillis));
        return List.copyOf(spans);
    }

    /**
     * Returns the attached pictures of the {@code APIC} and {@code PIC} frames, in tag order,
     * skipping malformed frames.
     *
     * @return the pictures; unmodifiable
     */
    public List<AttachedPicture> pictures() {
        var pictures = new ArrayList<AttachedPicture>();
        for (var frame : frames) {
            var id = frame.id();
            if ((frame.flags() & ENCRYPTED) == 0 && (id.equals("APIC") || id.equals("PIC"))) {
                var picture = decodePicture(frame.data(), id.equals("PIC"));
                if (picture != null) {
                    pictures.add(picture);
                }
            }
        }
        return List.copyOf(pictures);
    }

    /**
     * Returns the text, comment and user-defined text frames keyed by their version 2.3
     * identifier, the form a demuxer reports.
     *
     * <p>A {@code TXXX} frame is keyed {@code TXXX:} followed by its description, and a comment
     * {@code COMM} whatever its description, a {@code UFID} frame of owner {@code http://musicbrainz.org}
     * {@code UFID} with its identifier, and the first {@code POPM} frame {@code POPM} with its rating as
     * {@link StandardTag#RATING} describes it. The values of one frame, and the values of frames
     * with the same key, are joined with {@code "; "}; blank values and encrypted frames are
     * skipped.
     *
     * @param maxFrameBytes the largest frame decoded; larger frames are skipped
     * @return the fields in tag order
     */
    public Map<String, String> fields(int maxFrameBytes) {
        var fields = new LinkedHashMap<String, String>();
        for (var frame : frames) {
            if ((frame.flags() & ENCRYPTED) != 0 || frame.content().length > maxFrameBytes) {
                continue;
            }
            var id = frame.id().length() == 3 ? upgrade(frame.id()) : frame.id();
            var data = frame.data();
            if (id.equals("COMM")) {
                var comment = describedText(data);
                if (comment != null) {
                    putField(fields, id, comment[1]);
                }
            } else if (id.equals("TXXX")) {
                var values = text(data);
                if (values.size() > 1) {
                    putField(fields, "TXXX:" + values.getFirst(), String.join("; ", values.subList(1, values.size())));
                }
            } else if (id.charAt(0) == 'T') {
                putField(fields, id, String.join("; ", text(data)));
            } else if (id.equals("UFID")) {
                var identifier = musicBrainzId(data);
                if (identifier != null) {
                    putField(fields, id, identifier);
                }
            } else if (id.equals("POPM") && !fields.containsKey(id)) {
                var popularimeter = Popularimeter.parse(data);
                if (popularimeter != null) {
                    putField(fields, id, popularimeter.ratingText());
                }
            }
        }
        return fields;
    }

    /**
     * Stores a field value, joining it to an earlier value of the same key with {@code "; "}.
     *
     * @param fields the fields reported so far
     * @param key the field key
     * @param value the value; a blank value is ignored
     */
    private static void putField(Map<String, String> fields, String key, String value) {
        if (!value.isBlank()) {
            fields.merge(key, value, (previous, next) -> previous + "; " + next);
        }
    }

    /**
     * Decodes an attached picture frame.
     *
     * @param data the frame content
     * @param legacy whether it is a version 2.2 {@code PIC} frame
     * @return the picture, or {@code null} when malformed
     */
    private static AttachedPicture decodePicture(byte[] data, boolean legacy) {
        var end = data.length;
        if (end < 2) {
            return null;
        }
        var position = 0;
        var encoding = data[position++] & 0xFF;
        if (encoding > 3) {
            return null;
        }
        String mime;
        if (legacy) {
            if (end - position < 3) {
                return null;
            }
            var format = new String(data, position, 3, StandardCharsets.ISO_8859_1).toUpperCase(Locale.ROOT);
            mime = switch (format) {
                case "JPG" -> "image/jpeg";
                case "PNG" -> "image/png";
                case "GIF" -> "image/gif";
                case "BMP" -> "image/bmp";
                default -> "";
            };
            position += 3;
        } else {
            var mimeEnd = terminator(data, position, end, 0);
            mime = new String(data, position, mimeEnd - position, StandardCharsets.ISO_8859_1);
            position = mimeEnd + 1;
        }
        if (position >= end) {
            return null;
        }
        var type = data[position++] & 0xFF;
        var descriptionEnd = terminator(data, position, end, encoding);
        var description = decode(data, position, descriptionEnd - position, encoding);
        position = descriptionEnd + terminatorSize(encoding);
        if (position > end) {
            return null;
        }
        if (mime.equalsIgnoreCase("image/jpg")) {
            mime = "image/jpeg";
        }
        return AttachedPicture.of(AttachedPicture.Type.of(type), mime, description, Arrays.copyOfRange(data, position, end));
    }

    /**
     * Decodes a text frame's values.
     *
     * @param data the frame content
     * @return the values split at NUL characters, without trailing empty values
     */
    private static List<String> text(byte[] data) {
        if (data.length < 1) {
            return List.of();
        }
        var encoding = data[0] & 0xFF;
        if (encoding > 3) {
            return List.of();
        }
        var text = decode(data, 1, data.length - 1, encoding);
        var values = new ArrayList<String>();
        for (var value : text.split("\u0000", -1)) {
            values.add(value.replace("\uFEFF", ""));
        }
        while (!values.isEmpty() && values.getLast().isEmpty()) {
            values.removeLast();
        }
        return values;
    }

    /**
     * Decodes a frame holding a language, a description and a text ({@code COMM}, {@code USLT}).
     *
     * @param data the frame content
     * @return the description and the text, or {@code null} when malformed
     */
    private static String[] describedText(byte[] data) {
        if (data.length < 4) {
            return null;
        }
        var encoding = data[0] & 0xFF;
        if (encoding > 3) {
            return null;
        }
        var start = 4;
        var descriptionEnd = terminator(data, start, data.length, encoding);
        var description = decode(data, start, descriptionEnd - start, encoding);
        var textStart = descriptionEnd + terminatorSize(encoding);
        var text = textStart <= data.length ? decode(data, textStart, data.length - textStart, encoding) : "";
        var end = text.length();
        while (end > 0 && text.charAt(end - 1) == 0) {
            end--;
        }
        return new String[]{description.replace("\uFEFF", ""), text.substring(0, end).replace("\uFEFF", "")};
    }

    /**
     * Decodes a URL frame, a terminated ISO-8859-1 string.
     *
     * @param data the frame content
     * @return the URL
     */
    private static String latin1Text(byte[] data) {
        return new String(data, 0, terminator(data, 0, data.length, 0), StandardCharsets.ISO_8859_1);
    }

    /**
     * Decodes the identifier of a MusicBrainz {@code UFID} frame.
     *
     * @param data the frame content: a terminated ISO-8859-1 owner and up to 64 identifier bytes
     * @return the identifier, or {@code null} for another owner or a blank identifier
     */
    private static String musicBrainzId(byte[] data) {
        var ownerEnd = terminator(data, 0, data.length, 0);
        if (ownerEnd >= data.length || !new String(data, 0, ownerEnd, StandardCharsets.ISO_8859_1).equals(MUSICBRAINZ)) {
            return null;
        }
        var identifier = new String(data, ownerEnd + 1, data.length - ownerEnd - 1, StandardCharsets.ISO_8859_1).strip();
        var end = identifier.indexOf(0);
        identifier = end >= 0 ? identifier.substring(0, end) : identifier;
        return identifier.isBlank() ? null : identifier;
    }

    /**
     * Encodes the content of a MusicBrainz {@code UFID} frame.
     *
     * @param identifier the identifier, at most 64 printable ASCII characters
     * @return the content
     */
    private static byte[] musicBrainzContent(String identifier) {
        var out = new ByteWriter(64);
        out.bytes(MUSICBRAINZ.getBytes(StandardCharsets.ISO_8859_1));
        out.u8(0);
        var bytes = identifier.getBytes(StandardCharsets.ISO_8859_1);
        out.bytes(bytes);
        return out.toByteArray();
    }

    /**
     * Decodes text in an ID3v2 encoding.
     *
     * @param data the bytes
     * @param start the index of the first byte
     * @param length the number of bytes
     * @param encoding the ID3 text encoding code
     * @return the text, with a leading byte order mark consumed
     */
    private static String decode(byte[] data, int start, int length, int encoding) {
        if (length <= 0) {
            return "";
        }
        return switch (encoding) {
            case 0 -> new String(data, start, length, StandardCharsets.ISO_8859_1);
            case 1 -> {
                if (length >= 2 && (data[start] & 0xFF) == 0xFF && (data[start + 1] & 0xFF) == 0xFE) {
                    yield utf16(data, start + 2, length - 2, StandardCharsets.UTF_16LE);
                }
                if (length >= 2 && (data[start] & 0xFF) == 0xFE && (data[start + 1] & 0xFF) == 0xFF) {
                    yield utf16(data, start + 2, length - 2, StandardCharsets.UTF_16BE);
                }
                yield utf16(data, start, length, StandardCharsets.UTF_16LE);
            }
            case 2 -> utf16(data, start, length, StandardCharsets.UTF_16BE);
            default -> new String(data, start, length, StandardCharsets.UTF_8);
        };
    }

    /**
     * Decodes UTF-16 text, keeping the byte order mark of every NUL-separated value consumable.
     *
     * @param data the bytes
     * @param start the index of the first byte
     * @param length the number of bytes
     * @param charset the byte order
     * @return the text
     */
    private static String utf16(byte[] data, int start, int length, java.nio.charset.Charset charset) {
        var text = new String(data, start, length & ~1, charset);
        if (charset == StandardCharsets.UTF_16LE) {
            text = text.replace("\uFFFE", "");
        }
        return text;
    }

    /**
     * Returns the index of the first string terminator of an encoding.
     *
     * @param data the bytes
     * @param start the index where the string starts
     * @param end the end of the region
     * @param encoding the ID3 text encoding code
     * @return the index of the terminator, or {@code end} when there is none
     */
    private static int terminator(byte[] data, int start, int end, int encoding) {
        if (encoding == 1 || encoding == 2) {
            for (var i = start; i + 1 < end; i += 2) {
                if (data[i] == 0 && data[i + 1] == 0) {
                    return i;
                }
            }
            return end;
        }
        for (var i = start; i < end; i++) {
            if (data[i] == 0) {
                return i;
            }
        }
        return end;
    }

    /**
     * Returns the size of the string terminator of an encoding.
     *
     * @param encoding the ID3 text encoding code
     * @return two for UTF-16, one otherwise
     */
    private static int terminatorSize(int encoding) {
        return encoding == 1 || encoding == 2 ? 2 : 1;
    }

    /**
     * Maps a version 2.2 frame identifier to version 2.3.
     *
     * @param id the three-character identifier
     * @return the four-character identifier, or {@code id} when it has no equivalent
     */
    private static String upgrade(String id) {
        return switch (id) {
            case "TT1" -> "TIT1";
            case "TT2" -> "TIT2";
            case "TT3" -> "TIT3";
            case "TP1" -> "TPE1";
            case "TP2" -> "TPE2";
            case "TP3" -> "TPE3";
            case "TP4" -> "TPE4";
            case "TCM" -> "TCOM";
            case "TXT" -> "TEXT";
            case "TLA" -> "TLAN";
            case "TAL" -> "TALB";
            case "TRK" -> "TRCK";
            case "TPA" -> "TPOS";
            case "TYE" -> "TYER";
            case "TDA" -> "TDAT";
            case "TIM" -> "TIME";
            case "TCO" -> "TCON";
            case "TEN" -> "TENC";
            case "TSS" -> "TSSE";
            case "TCR" -> "TCOP";
            case "TPB" -> "TPUB";
            case "TBP" -> "TBPM";
            case "TRC" -> "TSRC";
            case "TST" -> "TSOT";
            case "TSP" -> "TSOP";
            case "TSA" -> "TSOA";
            case "TS2" -> "TSO2";
            case "TSC" -> "TSOC";
            case "UFI" -> "UFID";
            case "POP" -> "POPM";
            case "TXX" -> "TXXX";
            case "COM" -> "COMM";
            case "ULT" -> "USLT";
            default -> id;
        };
    }

    /**
     * Encodes a tag.
     *
     * <p>The entries become text, comment, lyrics and URL frames in order of first appearance of
     * their key, followed by one {@code APIC} frame per picture, a {@code CTOC} frame and one
     * {@code CHAP} frame per chapter, and the carried-over frames. When {@code exactSize} is
     * positive and the frames fit it, the tag is padded to exactly that size; otherwise
     * {@code padding} zero bytes follow the frames.
     *
     * @param tags the tags to write
     * @param preserved the opaque frames to carry over; frames of another version than {@code major} are converted when
     * possible and dropped otherwise
     * @param chapterFrames the chapter frames to carry over unchanged instead of encoding {@code tags.chapters()}, or
     * {@code null}
     * @param major the version to write, 3 or 4
     * @param exactSize the size to fill, or {@code 0}
     * @param padding the padding when the tag does not fill {@code exactSize}
     * @return the encoded tag, header included
     * @throws MediaException.LimitExceeded if the tag exceeds the 256 MiB an ID3v2 tag can hold
     */
    public static byte[] encode(MediaTags tags, List<Frame> preserved, List<Frame> chapterFrames, int major,
                                long exactSize, int padding) {
        var out = new ByteWriter(4096);
        var grouped = new LinkedHashMap<String, List<String>>();
        for (var entry : tags.entries()) {
            grouped.computeIfAbsent(entry.key(), _ -> new ArrayList<>()).add(entry.value());
        }
        var template = ratingTemplate(preserved);
        for (var group : grouped.entrySet()) {
            writeEntry(out, group.getKey(), group.getValue(), major, template);
        }
        for (var picture : tags.pictures()) {
            writeFrame(out, "APIC", 0, pictureContent(picture, major), major);
        }
        if (chapterFrames != null) {
            for (var frame : chapterFrames) {
                writePreserved(out, frame, major);
            }
        } else if (!tags.chapters().isEmpty()) {
            writeChapters(out, tags.chapters(), major);
        }
        for (var frame : preserved) {
            if ((frame.flags() & TAG_ALTER) == 0 && frame != template) {
                writePreserved(out, frame, major);
            }
        }
        var frames = out.toByteArray();
        long size = frames.length;
        if (exactSize > 0 && HEADER_SIZE + size <= exactSize) {
            size = exactSize - HEADER_SIZE;
        } else {
            size += padding;
        }
        if (size > MAX_BODY) {
            throw new MediaException.LimitExceeded("ID3v2 tag of " + size + " bytes exceeds the 256 MiB a tag can hold");
        }
        var result = new byte[(int) (HEADER_SIZE + size)];
        result[0] = 'I';
        result[1] = 'D';
        result[2] = '3';
        result[3] = (byte) major;
        putSyncsafe(result, 6, (int) size);
        System.arraycopy(frames, 0, result, HEADER_SIZE, frames.length);
        return result;
    }

    /**
     * Returns the size of the frames a set of tags encodes to, without header or padding.
     *
     * @param tags the tags to encode
     * @param preserved the opaque frames to carry over
     * @param chapterFrames the chapter frames to carry over, or {@code null}
     * @param major the version
     * @return the size in bytes
     */
    public static long framesSize(MediaTags tags, List<Frame> preserved, List<Frame> chapterFrames, int major) {
        return encode(tags, preserved, chapterFrames, major, 0, 0).length - HEADER_SIZE;
    }

    /**
     * Writes the frame or frames of one key.
     *
     * @param out the destination
     * @param key the tag key
     * @param values the values of the key, in order
     * @param major the version
     * @param template the {@code POPM} frame whose e-mail address and counter a rating keeps, or {@code null}
     */
    private static void writeEntry(ByteWriter out, String key, List<String> values, int major, Frame template) {
        var standard = StandardTag.fromName(key);
        if (standard.isPresent()) {
            switch (standard.get()) {
                case MUSICBRAINZ_RECORDING_ID -> {
                    var identifier = values.getFirst();
                    if (identifier.length() <= 64 && identifier.chars().allMatch(c -> c > 0x20 && c < 0x7F)) {
                        writeFrame(out, "UFID", 0, musicBrainzContent(identifier), major);
                    } else {
                        writeUserText(out, MUSICBRAINZ_TRACK_ID, values, major);
                    }
                    return;
                }
                case RATING -> {
                    var rating = Popularimeter.ratingByte(values.getFirst());
                    if (rating >= 0) {
                        var kept = template != null ? Popularimeter.parse(template.data()) : null;
                        var popularimeter = kept != null ? new Popularimeter(kept.email(), rating, kept.counter())
                                : new Popularimeter("", rating, new byte[0]);
                        writeFrame(out, "POPM", template != null ? template.flags() & ~GROUPING : 0,
                                popularimeter.content(), major);
                        return;
                    }
                    writeUserText(out, standard.get().id3Description(), values, major);
                    return;
                }
                case COMMENT -> {
                    writeFrame(out, "COMM", 0, describedContent(String.join("\n", values), major), major);
                    return;
                }
                case LYRICS -> {
                    writeFrame(out, "USLT", 0, describedContent(String.join("\n", values), major), major);
                    return;
                }
                case DATE -> {
                    writeDate(out, values.getFirst(), major);
                    return;
                }
                default -> {
                    var frame = standard.get().id3Frame();
                    if (frame.isPresent()) {
                        writeFrame(out, frame.get(), 0, textContent(values, major, true), major);
                    } else {
                        writeUserText(out, standard.get().id3Description(), values, major);
                    }
                    return;
                }
            }
        }
        if (key.length() == 4 && isValidId(key) && key.charAt(0) == 'T' && !key.equals("TXXX")) {
            writeFrame(out, key, 0, textContent(values, major, true), major);
        } else if (key.length() == 4 && isValidId(key) && key.charAt(0) == 'W' && !key.equals("WXXX")) {
            writeFrame(out, key, 0, values.getFirst().getBytes(StandardCharsets.ISO_8859_1), major);
        } else {
            writeUserText(out, key, values, major);
        }
    }

    /**
     * Writes a {@code TXXX} user text frame.
     *
     * @param out the destination
     * @param description the frame description
     * @param values the values of the tag, joined with {@code /} in version 2.3
     * @param major the version
     */
    private static void writeUserText(ByteWriter out, String description, List<String> values, int major) {
        var all = new ArrayList<String>(values.size() + 1);
        all.add(description);
        if (major == 3) {
            all.add(String.join(SLASH, values));
        } else {
            all.addAll(values);
        }
        writeFrame(out, "TXXX", 0, textContent(all, major, false), major);
    }

    /**
     * Returns the first carried-over {@code POPM} frame that decodes, the frame a rating replaces.
     *
     * @param preserved the opaque frames to carry over
     * @return the frame, or {@code null}
     */
    private static Frame ratingTemplate(List<Frame> preserved) {
        for (var frame : preserved) {
            if (frame.id().equals("POPM") && frame.major() != 2 && (frame.flags() & ENCRYPTED) == 0
                    && Popularimeter.parse(frame.data()) != null) {
                return frame;
            }
        }
        return null;
    }

    /**
     * Writes a date: {@code TDRC} in version 2.4, {@code TYER} with {@code TDAT} and
     * {@code TIME} in version 2.3.
     *
     * @param out the destination
     * @param date the ISO 8601 date, possibly with a time
     * @param major the version
     */
    private static void writeDate(ByteWriter out, String date, int major) {
        if (major >= 4) {
            writeFrame(out, "TDRC", 0, textContent(List.of(date), major, true), major);
            return;
        }
        var matcher = TIMESTAMP.matcher(date.trim());
        if (!matcher.matches()) {
            writeFrame(out, "TYER", 0, textContent(List.of(date), major, true), major);
            return;
        }
        writeFrame(out, "TYER", 0, textContent(List.of(matcher.group(1)), major, true), major);
        if (matcher.group(3) != null) {
            writeFrame(out, "TDAT", 0, textContent(List.of(matcher.group(3) + matcher.group(2)), major, true), major);
        }
        if (matcher.group(5) != null) {
            writeFrame(out, "TIME", 0, textContent(List.of(matcher.group(4) + matcher.group(5)), major, true), major);
        }
    }

    /**
     * Writes a table of contents and one frame per chapter.
     *
     * @param out the destination
     * @param chapters the chapters in start order
     * @param major the version
     */
    private static void writeChapters(ByteWriter out, List<Chapter> chapters, int major) {
        var toc = new ByteWriter(64 + 8 * chapters.size());
        toc.bytes("toc".getBytes(StandardCharsets.ISO_8859_1));
        toc.u8(0);
        toc.u8(0x03);
        toc.u8(Math.min(255, chapters.size()));
        for (var i = 0; i < Math.min(255, chapters.size()); i++) {
            toc.bytes(("chp" + i).getBytes(StandardCharsets.ISO_8859_1));
            toc.u8(0);
        }
        writeFrame(out, "CTOC", 0, toc.toByteArray(), major);
        for (var i = 0; i < Math.min(255, chapters.size()); i++) {
            var chapter = chapters.get(i);
            var body = new ByteWriter(64);
            body.bytes(("chp" + i).getBytes(StandardCharsets.ISO_8859_1));
            body.u8(0);
            body.u32be(Math.min(0xFFFFFFFEL, chapter.startMicros() / 1000));
            body.u32be(Math.min(0xFFFFFFFEL, chapter.endMicros() / 1000));
            body.u32be(0xFFFFFFFFL);
            body.u32be(0xFFFFFFFFL);
            if (chapter.title().isPresent()) {
                writeFrame(body, "TIT2", 0, textContent(List.of(chapter.title().get()), major, true), major);
            }
            writeFrame(out, "CHAP", 0, body.toByteArray(), major);
        }
    }

    /**
     * Writes a carried-over frame, converting its flags to the target version.
     *
     * @param out the destination
     * @param frame a frame read from a tag
     * @param major the version
     */
    private static void writePreserved(ByteWriter out, Frame frame, int major) {
        if (frame.major() == 2 || frame.id().length() != 4) {
            return;
        }
        if ((frame.flags() & ENCRYPTED) != 0) {
            if (frame.major() == major) {
                writeRaw(out, frame.id(), frame.rawFlags(), frame.content(), major);
            }
            return;
        }
        writeFrame(out, frame.id(), frame.flags(), frame.content(), major);
    }

    /**
     * Writes one frame from canonical flags.
     *
     * @param out the destination
     * @param id the frame identifier
     * @param flags the canonical flags ({@link #TAG_ALTER}, {@link #FILE_ALTER}, {@link #READ_ONLY}, {@link #GROUPING})
     * @param content the frame content
     * @param major the version
     */
    private static void writeFrame(ByteWriter out, String id, int flags, byte[] content, int major) {
        int raw;
        if (major == 3) {
            raw = ((flags & TAG_ALTER) != 0 ? 0x8000 : 0) | ((flags & FILE_ALTER) != 0 ? 0x4000 : 0)
                    | ((flags & READ_ONLY) != 0 ? 0x2000 : 0) | ((flags & GROUPING) != 0 ? 0x0020 : 0);
        } else {
            raw = ((flags & TAG_ALTER) != 0 ? 0x4000 : 0) | ((flags & FILE_ALTER) != 0 ? 0x2000 : 0)
                    | ((flags & READ_ONLY) != 0 ? 0x1000 : 0) | ((flags & GROUPING) != 0 ? 0x0040 : 0);
        }
        writeRaw(out, id, raw, content, major);
    }

    /**
     * Writes one frame with stored flags.
     *
     * @param out the destination
     * @param id the frame identifier
     * @param flags the stored flags
     * @param content the frame content
     * @param major the version
     * @throws MediaException.LimitExceeded if the frame exceeds what a frame size can hold
     */
    private static void writeRaw(ByteWriter out, String id, int flags, byte[] content, int major) {
        if (content.length > MAX_BODY) {
            throw new MediaException.LimitExceeded("ID3v2 frame " + id + " of " + content.length + " bytes is too large");
        }
        out.bytes(id.getBytes(StandardCharsets.ISO_8859_1));
        if (major == 4) {
            var size = new byte[4];
            putSyncsafe(size, 0, content.length);
            out.bytes(size);
        } else {
            out.u32be(content.length);
        }
        out.u16be(flags);
        out.bytes(content);
    }

    /**
     * Encodes a text frame's content.
     *
     * @param values the values; the first is the description of a {@code TXXX} frame
     * @param major the version
     * @param join whether version 2.3 joins the values into one with {@code /}
     * @return the content
     */
    private static byte[] textContent(List<String> values, int major, boolean join) {
        List<String> parts = major == 3 && join ? List.of(String.join(SLASH, values)) : values;
        var encoding = encodingFor(parts, major);
        var out = new ByteWriter(64);
        out.u8(encoding);
        for (var i = 0; i < parts.size(); i++) {
            if (i > 0) {
                out.zeros(terminatorSize(encoding));
            }
            out.bytes(encode(parts.get(i), encoding));
        }
        return out.toByteArray();
    }

    /**
     * Encodes a comment or lyrics frame's content with an empty description.
     *
     * @param text the comment or lyrics
     * @param major the version
     * @return the content
     */
    private static byte[] describedContent(String text, int major) {
        var encoding = encodingFor(List.of(text), major);
        var out = new ByteWriter(16 + text.length() * 2);
        out.u8(encoding);
        out.bytes("eng".getBytes(StandardCharsets.ISO_8859_1));
        out.bytes(encode("", encoding));
        out.zeros(terminatorSize(encoding));
        out.bytes(encode(text, encoding));
        return out.toByteArray();
    }

    /**
     * Encodes an attached picture frame's content.
     *
     * @param picture the picture to store
     * @param major the version
     * @return the content
     */
    private static byte[] pictureContent(AttachedPicture picture, int major) {
        var encoding = encodingFor(List.of(picture.description()), major);
        var data = picture.data();
        var out = new ByteWriter(64 + data.length);
        out.u8(encoding);
        out.bytes(TagIo.mediaType(picture).getBytes(StandardCharsets.ISO_8859_1));
        out.u8(0);
        out.u8(picture.type().code());
        out.bytes(encode(picture.description(), encoding));
        out.zeros(terminatorSize(encoding));
        out.bytes(data);
        return out.toByteArray();
    }

    /**
     * Chooses the text encoding of a frame.
     *
     * @param values the text the frame holds
     * @param major the version
     * @return ISO-8859-1 (0) when every character fits, otherwise UTF-16 (1) for version 2.3 and UTF-8 (3) for version
     * 2.4
     */
    private static int encodingFor(List<String> values, int major) {
        for (var value : values) {
            for (var i = 0; i < value.length(); i++) {
                if (value.charAt(i) > 0xFF) {
                    return major >= 4 ? 3 : 1;
                }
            }
        }
        return 0;
    }

    /**
     * Encodes text in an ID3v2 encoding, with a byte order mark for UTF-16.
     *
     * @param text the text to encode
     * @param encoding the ID3 text encoding code
     * @return the bytes, without terminator
     */
    private static byte[] encode(String text, int encoding) {
        return switch (encoding) {
            case 0 -> text.getBytes(StandardCharsets.ISO_8859_1);
            case 1 -> {
                var body = text.getBytes(StandardCharsets.UTF_16LE);
                var result = new byte[body.length + 2];
                result[0] = (byte) 0xFF;
                result[1] = (byte) 0xFE;
                System.arraycopy(body, 0, result, 2, body.length);
                yield result;
            }
            case 2 -> text.getBytes(StandardCharsets.UTF_16BE);
            default -> text.getBytes(StandardCharsets.UTF_8);
        };
    }

    /**
     * Reverses unsynchronization in place: every {@code 0xFF 0x00} pair becomes {@code 0xFF}.
     *
     * @param data the bytes
     * @param offset the index of the first byte
     * @param length the number of bytes
     * @return the length after the reversal
     */
    private static int removeUnsynchronization(byte[] data, int offset, int length) {
        var write = offset;
        var end = offset + length;
        for (var read = offset; read < end; read++) {
            var value = data[read];
            data[write++] = value;
            if (value == (byte) 0xFF && read + 1 < end && data[read + 1] == 0) {
                read++;
            }
        }
        return write - offset;
    }

    /**
     * Returns whether a frame identifier consists of upper-case letters and digits.
     *
     * @param id the identifier
     * @return {@code true} when well formed
     */
    private static boolean isValidId(String id) {
        for (var i = 0; i < id.length(); i++) {
            var c = id.charAt(i);
            if ((c < 'A' || c > 'Z') && (c < '0' || c > '9')) {
                return false;
            }
        }
        return !id.isEmpty();
    }

    /**
     * Reads a syncsafe 28-bit integer.
     *
     * @param data the bytes
     * @param offset the index of the first byte
     * @return the value, or {@code -1} when a byte has its high bit set
     */
    private static int syncsafe(byte[] data, int offset) {
        var value = 0;
        for (var i = 0; i < 4; i++) {
            var b = data[offset + i] & 0xFF;
            if (b >= 0x80) {
                return -1;
            }
            value = (value << 7) | b;
        }
        return value;
    }

    /**
     * Writes a syncsafe 28-bit integer.
     *
     * @param data the destination
     * @param offset the index of the first byte
     * @param value the value, below {@code 2^28}
     */
    private static void putSyncsafe(byte[] data, int offset, int value) {
        data[offset] = (byte) ((value >>> 21) & 0x7F);
        data[offset + 1] = (byte) ((value >>> 14) & 0x7F);
        data[offset + 2] = (byte) ((value >>> 7) & 0x7F);
        data[offset + 3] = (byte) (value & 0x7F);
    }

    /**
     * Holds one frame in canonical form.
     *
     * <p>The content holds the group identifier byte first when {@link #GROUPING} is set, then
     * the frame data with unsynchronization and compression undone; an {@link #ENCRYPTED} frame
     * holds its stored payload unchanged, with its stored flags.
     */
    public static final class Frame {
        /**
         * The frame identifier, three characters for version 2.2.
         */
        private final String id;

        /**
         * The version the frame was read from.
         */
        private final int major;

        /**
         * The canonical flags.
         */
        private final int flags;

        /**
         * The canonical content.
         */
        private final byte[] content;

        /**
         * The stored flags, kept for encrypted frames.
         */
        private final int rawFlags;

        /**
         * Constructs a frame.
         *
         * @param id the identifier
         * @param major the version it was read from
         * @param flags the canonical flags
         * @param content the canonical content; not copied
         */
        Frame(String id, int major, int flags, byte[] content) {
            this(id, major, flags, content, 0);
        }

        /**
         * Constructs a frame with stored flags.
         *
         * @param id the identifier
         * @param major the version it was read from
         * @param flags the canonical flags
         * @param content the canonical content; not copied
         * @param rawFlags the stored flags
         */
        Frame(String id, int major, int flags, byte[] content, int rawFlags) {
            this.id = id;
            this.major = major;
            this.flags = flags;
            this.content = content;
            this.rawFlags = rawFlags;
        }

        /**
         * {@return the frame identifier, three characters for version 2.2}
         */
        public String id() {
            return id;
        }

        /**
         * {@return the version the frame was read from: 2, 3 or 4}
         */
        public int major() {
            return major;
        }

        /**
         * {@return the canonical flags}
         */
        public int flags() {
            return flags;
        }

        /**
         * {@return the stored flags of an encrypted frame}
         */
        int rawFlags() {
            return rawFlags;
        }

        /**
         * {@return the canonical content, not copied}
         */
        public byte[] content() {
            return content;
        }

        /**
         * {@return the frame's data without a group identifier byte}
         */
        byte[] data() {
            return (flags & GROUPING) != 0 && content.length > 0 ? Arrays.copyOfRange(content, 1, content.length) : content;
        }
    }

    /**
     * The content of a {@code POPM} popularimeter frame.
     *
     * @param email the e-mail address of the user the rating belongs to, possibly empty
     * @param rating the rating byte, from 0 (unknown) to 255
     * @param counter the play counter bytes, possibly empty
     */
    record Popularimeter(String email, int rating, byte[] counter) {
        /**
         * Parses a frame's content.
         *
         * @param data the content
         * @return the popularimeter, or {@code null} when the e-mail address is unterminated or the rating missing
         */
        static Popularimeter parse(byte[] data) {
            var end = terminator(data, 0, data.length, 0);
            if (end + 1 >= data.length) {
                return null;
            }
            return new Popularimeter(new String(data, 0, end, StandardCharsets.ISO_8859_1), data[end + 1] & 0xFF,
                    Arrays.copyOfRange(data, end + 2, data.length));
        }

        /**
         * {@return the content}
         */
        byte[] content() {
            var out = new ByteWriter(email.length() + 2 + counter.length);
            out.bytes(email.getBytes(StandardCharsets.ISO_8859_1));
            out.u8(0);
            out.u8(rating);
            out.bytes(counter);
            return out.toByteArray();
        }

        /**
         * {@return the rating as {@link StandardTag#RATING} text: five times the byte over 255, at most two
         * decimals}
         */
        String ratingText() {
            return BigDecimal.valueOf(rating * 5L).divide(BigDecimal.valueOf(255), 2, RoundingMode.HALF_UP)
                    .stripTrailingZeros().toPlainString();
        }

        /**
         * Converts {@link StandardTag#RATING} text to the nearest rating byte.
         *
         * @param text the rating, a number from 0 to 5
         * @return the byte, or {@code -1} when the text is not such a number
         */
        static int ratingByte(String text) {
            try {
                var value = new BigDecimal(text.strip());
                if (value.signum() < 0 || value.compareTo(BigDecimal.valueOf(5)) > 0) {
                    return -1;
                }
                return value.multiply(BigDecimal.valueOf(51)).setScale(0, RoundingMode.HALF_UP).intValueExact();
            } catch (NumberFormatException | ArithmeticException exception) {
                return -1;
            }
        }
    }

    /**
     * A chapter as a {@code CHAP} frame stores it.
     *
     * @param startMillis the start, in milliseconds
     * @param endMillis the end, in milliseconds, or {@code -1} when the frame leaves it open
     * @param title the title, or {@code null}
     */
    public record ChapterSpan(long startMillis, long endMillis, String title) {
    }

    /**
     * Holds the decoded content of a tag.
     *
     * @param entries the text entries
     * @param pictures the attached pictures
     * @param chapters the chapters in start order
     * @param opaque the frames without a {@link MediaTags} representation
     * @param chapterFrames the {@code CHAP} and {@code CTOC} frames as read
     */
    public record Content(List<MediaTags.Entry> entries, List<AttachedPicture> pictures, List<Chapter> chapters,
                          List<Frame> opaque, List<Frame> chapterFrames) {
        /**
         * {@return the decoded content as tags}
         */
        public MediaTags tags() {
            var builder = MediaTags.builder();
            for (var entry : entries) {
                builder.add(entry.key(), entry.value());
            }
            return builder.pictures(pictures).chapters(chapters).build();
        }
    }
}
