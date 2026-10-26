package com.github.auties00.smalt.internal.tags;

import com.github.auties00.smalt.container.AttachedPicture;
import com.github.auties00.smalt.container.Chapter;
import com.github.auties00.smalt.internal.io.ByteWriter;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.metadata.MediaTags;
import com.github.auties00.smalt.metadata.StandardTag;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Encodes and decodes the Matroska {@code Tags}, {@code Chapters} and {@code Attachments}
 * elements that carry tags, chapters and cover art.
 *
 * <p>Global tags are the {@code Tag} elements whose {@code Targets} name no track, edition,
 * chapter or attachment and whose target type value is absent or 50 (album, movie); a nested
 * {@code SimpleTag} is read under its parent's name and its own, separated by {@code /}
 * ({@code TITLE/SORT_WITH}), and the album tag's {@code REPLAYGAIN_GAIN} and {@code REPLAYGAIN_PEAK}
 * as the album ReplayGain values. A global {@code Tag} of target type value 30 (track) gives the
 * track ReplayGain values; its other {@code SimpleTag} elements, and every other {@code Tag}
 * element, are opaque. Chapters come from the first edition, or the default one.
 * Pictures are the attachments with an image media type; one named {@code cover.*} is the front
 * cover, as the Matroska cover art convention says.
 */
public final class MatroskaTags {
    /**
     * The tag name of the total number of tracks, folded into {@link StandardTag#TRACK}.
     */
    private static final String TOTAL_PARTS = "TOTAL_PARTS";

    /**
     * The target type value of a track.
     */
    private static final long TRACK_TYPE_VALUE = 30;

    /**
     * The deepest {@code SimpleTag} nesting read.
     */
    private static final int MAX_DEPTH = 8;

    private MatroskaTags() {
        throw new AssertionError();
    }

    /**
     * Decodes the global tags of a {@code Tags} element.
     *
     * @param data the bytes
     * @param tags the {@code Tags} element
     * @param global receives the global tag entries
     * @param opaque receives the other {@code Tag} elements, each complete
     */
    public static void decodeTags(byte[] data, Ebml.Element tags, MediaTags.Builder global, List<byte[]> opaque) {
        String total = null;
        var current = MediaTags.builder();
        for (var tag : Ebml.children(data, tags.data(), tags.end())) {
            if (tag.id() != EbmlIds.TAG) {
                continue;
            }
            var children = Ebml.children(data, tag.data(), tag.end());
            var targetsElement = Ebml.find(children, EbmlIds.TARGETS);
            var targets = targets(data, targetsElement);
            var album = targets.global() && targets.typeValue() == Targets.DEFAULT_TYPE_VALUE;
            var track = targets.global() && targets.typeValue() == TRACK_TYPE_VALUE;
            if (!album && !track) {
                opaque.add(Arrays.copyOfRange(data, tag.offset(), tag.end()));
                continue;
            }
            var kept = new ByteWriter(256);
            for (var simple : children) {
                if (simple.id() != EbmlIds.SIMPLE_TAG) {
                    continue;
                }
                var fields = Ebml.children(data, simple.data(), simple.end());
                var name = Ebml.find(fields, EbmlIds.TAG_NAME);
                var key = name != null ? Ebml.string(data, name) : null;
                if (track) {
                    var standard = key == null ? null : levelTag(key, TRACK_TYPE_VALUE);
                    var value = Ebml.find(fields, EbmlIds.TAG_STRING);
                    if (standard == null || value == null) {
                        kept.bytes(Arrays.copyOfRange(data, simple.offset(), simple.end()));
                    } else {
                        add(current, standard.key(), Ebml.string(data, value));
                    }
                    continue;
                }
                if (key != null && key.equalsIgnoreCase(TOTAL_PARTS)) {
                    var value = Ebml.find(fields, EbmlIds.TAG_STRING);
                    if (value != null) {
                        total = Ebml.string(data, value).trim();
                    }
                    continue;
                }
                decodeSimple(data, fields, "", current, 0);
            }
            if (track && kept.size() > 0) {
                var rebuilt = new ByteWriter(kept.size() + 32);
                if (targetsElement != null) {
                    rebuilt.bytes(Arrays.copyOfRange(data, targetsElement.offset(), targetsElement.end()));
                }
                rebuilt.bytes(kept.toByteArray());
                opaque.add(Ebml.element(EbmlIds.TAG, rebuilt.toByteArray()));
            }
        }
        var decoded = current.build();
        for (var entry : decoded.entries()) {
            if (entry.key().equals(StandardTag.TRACK.key()) && total != null && !entry.value().contains("/")) {
                global.add(entry.key(), entry.value().trim() + "/" + total);
                total = null;
            } else {
                global.add(entry.key(), entry.value());
            }
        }
        if (total != null) {
            global.add(TOTAL_PARTS, total);
        }
    }

    /**
     * Decodes one {@code SimpleTag} of the album tag and the tags nested in it.
     *
     * @param data the bytes
     * @param fields the children of the {@code SimpleTag}
     * @param prefix the names of the enclosing tags joined with {@code /}, or empty
     * @param current receives the entries
     * @param depth the nesting of the tag
     */
    private static void decodeSimple(byte[] data, List<Ebml.Element> fields, String prefix, MediaTags.Builder current,
                                     int depth) {
        var name = Ebml.find(fields, EbmlIds.TAG_NAME);
        if (name == null || depth >= MAX_DEPTH) {
            return;
        }
        var own = Ebml.string(data, name);
        if (own.isBlank() || own.indexOf('/') >= 0) {
            return;
        }
        var key = prefix.isEmpty() ? own : prefix + "/" + own;
        var value = Ebml.find(fields, EbmlIds.TAG_STRING);
        if (value != null) {
            var standard = prefix.isEmpty() ? levelTag(own, Targets.DEFAULT_TYPE_VALUE) : null;
            add(current, standard != null ? standard.key() : key, Ebml.string(data, value));
        }
        for (var child : fields) {
            if (child.id() == EbmlIds.SIMPLE_TAG) {
                decodeSimple(data, Ebml.children(data, child.data(), child.end()), key, current, depth + 1);
            }
        }
    }

    /**
     * Returns the tag a {@code SimpleTag} name whose meaning depends on its level denotes.
     *
     * @param name the {@code SimpleTag} name
     * @param typeValue the target type value of the {@code Tag} holding it
     * @return the ReplayGain tag of the track (level 30) or of the album (level 50), or {@code null} for another
     * name
     */
    public static StandardTag levelTag(String name, long typeValue) {
        var album = typeValue >= Targets.DEFAULT_TYPE_VALUE;
        if (name.equalsIgnoreCase("REPLAYGAIN_GAIN")) {
            return album ? StandardTag.REPLAYGAIN_ALBUM_GAIN : StandardTag.REPLAYGAIN_TRACK_GAIN;
        }
        if (name.equalsIgnoreCase("REPLAYGAIN_PEAK")) {
            return album ? StandardTag.REPLAYGAIN_ALBUM_PEAK : StandardTag.REPLAYGAIN_TRACK_PEAK;
        }
        return null;
    }

    /**
     * Adds a non-empty entry, dropping a name no format can store.
     *
     * @param current the target
     * @param key the normalized key or the flattened {@code SimpleTag} name
     * @param text the value
     */
    private static void add(MediaTags.Builder current, String key, String text) {
        if (key.isBlank() || text.isEmpty()) {
            return;
        }
        try {
            current.add(key, text);
        } catch (IllegalArgumentException ignored) {
            // a name no format can store is dropped
        }
    }

    /**
     * Reads the {@code Targets} element of a {@code Tag}.
     *
     * @param data the bytes
     * @param targets the element, or {@code null} when the tag has none
     * @return the targets
     */
    public static Targets targets(byte[] data, Ebml.Element targets) {
        if (targets == null) {
            return new Targets(0, 0, false, Targets.DEFAULT_TYPE_VALUE);
        }
        var trackUid = 0L;
        var trackCount = 0;
        var otherUid = false;
        var typeValue = Targets.DEFAULT_TYPE_VALUE;
        for (var child : Ebml.children(data, targets.data(), targets.end())) {
            switch (child.id()) {
                case EbmlIds.TAG_TRACK_UID -> {
                    var uid = Ebml.unsigned(data, child);
                    if (uid != 0) {
                        trackCount++;
                    }
                    if (trackUid == 0) {
                        trackUid = uid;
                    }
                }
                case EbmlIds.TAG_EDITION_UID, EbmlIds.TAG_CHAPTER_UID, EbmlIds.TAG_ATTACHMENT_UID ->
                        otherUid |= Ebml.unsigned(data, child) != 0;
                case EbmlIds.TARGET_TYPE_VALUE -> typeValue = Ebml.unsigned(data, child);
                default -> {
                }
            }
        }
        return new Targets(trackUid, trackCount, otherUid, typeValue);
    }

    /**
     * Encodes a {@code Tags} element: the opaque tags followed by one global tag and, when the tags
     * hold track ReplayGain values, a global tag of target type value 30 holding them.
     *
     * @param tags the global tags to write
     * @param opaque the opaque {@code Tag} elements to keep
     * @return the element, or an empty array when there is nothing to write
     */
    public static byte[] encodeTags(MediaTags tags, List<byte[]> opaque) {
        if (tags.entries().isEmpty() && opaque.isEmpty()) {
            return new byte[0];
        }
        var out = new ByteWriter(1024);
        for (var tag : opaque) {
            out.bytes(tag);
        }
        var album = new ArrayList<Node>();
        var track = new ArrayList<Node>();
        for (var entry : tags.entries()) {
            var standard = StandardTag.fromName(entry.key());
            var value = entry.value();
            if (standard.isPresent() && (standard.get() == StandardTag.REPLAYGAIN_TRACK_GAIN
                    || standard.get() == StandardTag.REPLAYGAIN_TRACK_PEAK)) {
                track.add(new Node(standard.get().matroskaName(), value));
                continue;
            }
            var name = standard.isPresent() ? standard.get().matroskaName() : entry.key().toUpperCase(Locale.ROOT);
            if (standard.isPresent() && standard.get() == StandardTag.TRACK && value.contains("/")
                    && !tags.contains(TOTAL_PARTS)) {
                var slash = value.indexOf('/');
                place(album, name, value.substring(0, slash).trim());
                var total = value.substring(slash + 1).trim();
                if (!total.isEmpty()) {
                    place(album, TOTAL_PARTS, total);
                }
                continue;
            }
            place(album, name, value);
        }
        if (!album.isEmpty()) {
            var tag = new ByteWriter(1024);
            Ebml.element(tag, EbmlIds.TARGETS, new byte[0]);
            for (var node : album) {
                node.write(tag);
            }
            Ebml.element(out, EbmlIds.TAG, tag.toByteArray());
        }
        if (!track.isEmpty()) {
            var tag = new ByteWriter(256);
            var targets = new ByteWriter(16);
            Ebml.unsigned(targets, EbmlIds.TARGET_TYPE_VALUE, TRACK_TYPE_VALUE);
            Ebml.string(targets, EbmlIds.TARGET_TYPE, "TRACK");
            Ebml.element(tag, EbmlIds.TARGETS, targets.toByteArray());
            for (var node : track) {
                node.write(tag);
            }
            Ebml.element(out, EbmlIds.TAG, tag.toByteArray());
        }
        return Ebml.element(EbmlIds.TAGS, out.toByteArray());
    }

    /**
     * Decodes the {@code SimpleTag} elements of a {@code Tag}, a nested tag named by its parent's name and its own
     * separated by {@code /}.
     *
     * @param data the bytes
     * @param tag the {@code Tag} element
     * @return the entries, in order
     */
    public static List<MediaTags.Entry> decodeSimpleTags(byte[] data, Ebml.Element tag) {
        var current = MediaTags.builder();
        for (var simple : Ebml.children(data, tag.data(), tag.end())) {
            if (simple.id() == EbmlIds.SIMPLE_TAG) {
                decodeSimple(data, Ebml.children(data, simple.data(), simple.end()), "", current, 0);
            }
        }
        return current.build().entries();
    }

    /**
     * Encodes a {@code Tag} element whose {@code Targets} name one track.
     *
     * @param trackUid the {@code TrackUID} of the track
     * @param entries the tags of the track
     * @return the complete element
     */
    public static byte[] encodeTrackTag(long trackUid, List<MediaTags.Entry> entries) {
        var targets = new ByteWriter(16);
        Ebml.unsigned(targets, EbmlIds.TAG_TRACK_UID, trackUid);
        var tag = new ByteWriter(256);
        Ebml.element(tag, EbmlIds.TARGETS, targets.toByteArray());
        tag.bytes(encodeSimpleTags(entries));
        return Ebml.element(EbmlIds.TAG, tag.toByteArray());
    }

    /**
     * Encodes the {@code SimpleTag} elements of a list of entries, nesting a name holding {@code /} in its parent.
     *
     * @param tags the entries
     * @return the concatenated {@code SimpleTag} elements
     */
    static byte[] encodeSimpleTags(List<MediaTags.Entry> tags) {
        var nodes = new ArrayList<Node>();
        for (var entry : tags) {
            var standard = StandardTag.fromName(entry.key());
            place(nodes, standard.isPresent() ? standard.get().matroskaName() : entry.key().toUpperCase(Locale.ROOT),
                    entry.value());
        }
        var out = new ByteWriter(256);
        for (var node : nodes) {
            node.write(out);
        }
        return out.toByteArray();
    }

    /**
     * Places a value in a tree of {@code SimpleTag} elements: a name holding {@code /} in the first tag of its
     * parent's name, created without a value when there is none.
     *
     * @param nodes the tags of one level
     * @param name the name, parent and child names separated by {@code /}
     * @param value the {@code TagString}
     */
    private static void place(List<Node> nodes, String name, String value) {
        var slash = name.indexOf('/');
        if (slash <= 0 || slash == name.length() - 1) {
            for (var node : nodes) {
                if (node.value == null && node.name.equalsIgnoreCase(name)) {
                    node.value = value;
                    return;
                }
            }
            nodes.add(new Node(name, value));
            return;
        }
        var parentName = name.substring(0, slash);
        Node parent = null;
        for (var node : nodes) {
            if (node.name.equalsIgnoreCase(parentName)) {
                parent = node;
                break;
            }
        }
        if (parent == null) {
            parent = new Node(parentName, null);
            nodes.add(parent);
        }
        place(parent.children, name.substring(slash + 1), value);
    }

    /**
     * A {@code SimpleTag} being encoded.
     */
    private static final class Node {
        /**
         * The tag name.
         */
        private final String name;

        /**
         * The value, or {@code null} for a tag that only holds nested tags.
         */
        private String value;

        /**
         * The nested tags.
         */
        private final List<Node> children = new ArrayList<>();

        /**
         * Constructs a tag.
         *
         * @param name the {@code TagName}
         * @param value the value, or {@code null}
         */
        Node(String name, String value) {
            this.name = name;
            this.value = value;
        }

        /**
         * Writes the tag and its nested tags.
         *
         * @param out the destination
         */
        void write(ByteWriter out) {
            var simple = new ByteWriter(32 + (value != null ? value.length() : 0));
            Ebml.string(simple, EbmlIds.TAG_NAME, name);
            Ebml.string(simple, EbmlIds.TAG_LANGUAGE, "und");
            if (value != null) {
                Ebml.string(simple, EbmlIds.TAG_STRING, value);
            }
            for (var child : children) {
                child.write(simple);
            }
            Ebml.element(out, EbmlIds.SIMPLE_TAG, simple.toByteArray());
        }
    }

    /**
     * Decodes the chapters of the first or default edition.
     *
     * @param data the bytes
     * @param chapters the {@code Chapters} element
     * @param durationMicros the segment duration that ends a last chapter without end time, or {@code -1}
     * @return the visible chapters in start order
     */
    public static List<Chapter> decodeChapters(byte[] data, Ebml.Element chapters, long durationMicros) {
        var spans = new ArrayList<>(chapterSpans(data, chapters));
        spans.sort(Comparator.comparingLong(ChapterSpan::startMicros));
        var result = new ArrayList<Chapter>();
        for (var k = 0; k < spans.size(); k++) {
            var span = spans.get(k);
            var end = span.endMicros();
            if (end < 0) {
                end = k + 1 < spans.size() ? spans.get(k + 1).startMicros() : Math.max(span.startMicros(), durationMicros);
            }
            var title = span.title();
            result.add(Chapter.of(span.startMicros(), Math.max(span.startMicros(), end),
                    title == null || title.isEmpty() ? null : title));
        }
        return result;
    }

    /**
     * Returns the visible chapters of the first or default edition, in file order, with the end
     * times as stored.
     *
     * <p>A chapter is visible when it has a start time and is not flagged hidden; its title is its
     * first {@code ChapString}.
     *
     * @param data the bytes
     * @param chapters the {@code Chapters} element
     * @return the chapters
     */
    public static List<ChapterSpan> chapterSpans(byte[] data, Ebml.Element chapters) {
        Ebml.Element edition = null;
        for (var child : Ebml.children(data, chapters.data(), chapters.end())) {
            if (child.id() != EbmlIds.EDITION_ENTRY) {
                continue;
            }
            if (edition == null) {
                edition = child;
            }
            var flag = Ebml.find(Ebml.children(data, child.data(), child.end()), EbmlIds.EDITION_FLAG_DEFAULT);
            if (flag != null && Ebml.unsigned(data, flag) == 1) {
                edition = child;
                break;
            }
        }
        if (edition == null) {
            return List.of();
        }
        var spans = new ArrayList<ChapterSpan>();
        for (var atom : Ebml.children(data, edition.data(), edition.end())) {
            if (atom.id() != EbmlIds.CHAPTER_ATOM) {
                continue;
            }
            var fields = Ebml.children(data, atom.data(), atom.end());
            var hidden = Ebml.find(fields, EbmlIds.CHAPTER_FLAG_HIDDEN);
            var start = Ebml.find(fields, EbmlIds.CHAPTER_TIME_START);
            if (start == null || (hidden != null && Ebml.unsigned(data, hidden) == 1)) {
                continue;
            }
            var end = Ebml.find(fields, EbmlIds.CHAPTER_TIME_END);
            String title = null;
            var display = Ebml.find(fields, EbmlIds.CHAPTER_DISPLAY);
            if (display != null) {
                var string = Ebml.find(Ebml.children(data, display.data(), display.end()), EbmlIds.CHAP_STRING);
                if (string != null) {
                    title = Ebml.string(data, string);
                }
            }
            var startNanos = Ebml.unsigned(data, start);
            var endNanos = end != null ? Ebml.unsigned(data, end) : -1;
            spans.add(new ChapterSpan(startNanos / 1000, endNanos >= startNanos ? endNanos / 1000 : -1, title));
        }
        return spans;
    }

    /**
     * Encodes a {@code Chapters} element with one edition.
     *
     * @param chapters the chapters, in order
     * @return the element, or an empty array without chapters
     */
    public static byte[] encodeChapters(List<Chapter> chapters) {
        if (chapters.isEmpty()) {
            return new byte[0];
        }
        var edition = new ByteWriter(64 + chapters.size() * 64);
        Ebml.unsigned(edition, EbmlIds.EDITION_UID, 1);
        for (var index = 0; index < chapters.size(); index++) {
            var chapter = chapters.get(index);
            var atom = new ByteWriter(64);
            Ebml.unsigned(atom, EbmlIds.CHAPTER_UID, index + 1L);
            Ebml.unsigned(atom, EbmlIds.CHAPTER_TIME_START, chapter.startMicros() * 1000);
            Ebml.unsigned(atom, EbmlIds.CHAPTER_TIME_END, chapter.endMicros() * 1000);
            if (chapter.title().isPresent()) {
                var display = new ByteWriter(32);
                Ebml.string(display, EbmlIds.CHAP_STRING, chapter.title().get());
                Ebml.string(display, EbmlIds.CHAP_LANGUAGE, "und");
                Ebml.element(atom, EbmlIds.CHAPTER_DISPLAY, display.toByteArray());
            }
            Ebml.element(edition, EbmlIds.CHAPTER_ATOM, atom.toByteArray());
        }
        return Ebml.element(EbmlIds.CHAPTERS, Ebml.element(EbmlIds.EDITION_ENTRY, edition.toByteArray()));
    }

    /**
     * Decodes the picture attachments.
     *
     * @param data the bytes
     * @param attachments the {@code Attachments} element
     * @param pictures receives the pictures
     * @param opaque receives the other {@code AttachedFile} elements, each complete, or {@code null} to skip them
     */
    public static void decodeAttachments(byte[] data, Ebml.Element attachments, List<AttachedPicture> pictures,
                                         List<byte[]> opaque) {
        for (var file : Ebml.children(data, attachments.data(), attachments.end())) {
            if (file.id() != EbmlIds.ATTACHED_FILE) {
                continue;
            }
            var fields = Ebml.children(data, file.data(), file.end());
            var mediaType = Ebml.find(fields, EbmlIds.FILE_MEDIA_TYPE);
            var content = Ebml.find(fields, EbmlIds.FILE_DATA);
            var type = mediaType != null ? Ebml.string(data, mediaType).toLowerCase(Locale.ROOT) : "";
            if (content == null || !type.startsWith("image/")) {
                if (opaque != null) {
                    opaque.add(Arrays.copyOfRange(data, file.offset(), file.end()));
                }
                continue;
            }
            var name = Ebml.find(fields, EbmlIds.FILE_NAME);
            var fileName = name != null ? Ebml.string(data, name).toLowerCase(Locale.ROOT) : "";
            var description = Ebml.find(fields, EbmlIds.FILE_DESCRIPTION);
            var kind = fileName.startsWith("cover") || fileName.startsWith("small_cover") ? AttachedPicture.Type.FRONT_COVER
                    : typeOf(fileName);
            pictures.add(AttachedPicture.of(kind, type, description != null ? Ebml.string(data, description) : "",
                    Arrays.copyOfRange(data, content.data(), content.end())));
        }
    }

    /**
     * Recognizes the picture type a file name written by {@link #encodeAttachments} encodes.
     *
     * @param fileName the lower-case file name
     * @return the type, {@link AttachedPicture.Type#OTHER} by default
     */
    private static AttachedPicture.Type typeOf(String fileName) {
        for (var type : AttachedPicture.Type.values()) {
            if (fileName.startsWith(type.name().toLowerCase(Locale.ROOT) + ".")
                    || fileName.startsWith(type.name().toLowerCase(Locale.ROOT) + "_")) {
                return type;
            }
        }
        return AttachedPicture.Type.OTHER;
    }

    /**
     * Encodes an {@code Attachments} element: the opaque attachments followed by the pictures.
     *
     * <p>The first front cover is named {@code cover.<ext>}; other pictures are named after their
     * type and position, such as {@code back_cover_2.png}. File identifiers are derived from the
     * position and content, so encoding the same pictures twice gives the same bytes.
     *
     * @param pictures the pictures to attach
     * @param opaque the opaque {@code AttachedFile} elements to keep
     * @return the element, or an empty array when there is nothing to write
     */
    public static byte[] encodeAttachments(List<AttachedPicture> pictures, List<byte[]> opaque) {
        if (pictures.isEmpty() && opaque.isEmpty()) {
            return new byte[0];
        }
        var out = new ByteWriter(4096);
        for (var file : opaque) {
            out.bytes(file);
        }
        var coverWritten = false;
        for (var index = 0; index < pictures.size(); index++) {
            var picture = pictures.get(index);
            var data = picture.data();
            var extension = TagIo.extension(picture);
            String name;
            if (picture.type() == AttachedPicture.Type.FRONT_COVER && !coverWritten) {
                name = "cover." + extension;
                coverWritten = true;
            } else {
                name = picture.type().name().toLowerCase(Locale.ROOT) + "_" + (index + 1) + "." + extension;
            }
            var mediaType = TagIo.mediaType(picture);
            if (mediaType.isEmpty()) {
                mediaType = picture.format().map(ImageFormat::mimeType).orElse("application/octet-stream");
            }
            var file = new ByteWriter(64 + data.length);
            if (!picture.description().isEmpty()) {
                Ebml.string(file, EbmlIds.FILE_DESCRIPTION, picture.description());
            }
            Ebml.string(file, EbmlIds.FILE_NAME, name);
            Ebml.element(file, EbmlIds.FILE_MEDIA_TYPE, mediaType.getBytes(StandardCharsets.US_ASCII));
            Ebml.element(file, EbmlIds.FILE_DATA, data);
            Ebml.unsigned(file, EbmlIds.FILE_UID, uid(index, data));
            Ebml.element(out, EbmlIds.ATTACHED_FILE, file.toByteArray());
        }
        return Ebml.element(EbmlIds.ATTACHMENTS, out.toByteArray());
    }

    /**
     * Derives a non-zero unique identifier for an attachment.
     *
     * @param index the position of the picture
     * @param data the picture data
     * @return the identifier
     */
    private static long uid(int index, byte[] data) {
        var hash = 0x9E3779B97F4A7C15L * (index + 1) ^ Arrays.hashCode(data);
        hash ^= hash >>> 31;
        hash *= 0xBF58476D1CE4E5B9L;
        hash ^= hash >>> 29;
        return hash == 0 ? index + 1L : hash;
    }

    /**
     * The targets of a {@code Tag} element.
     *
     * @param trackUid the first {@code TagTrackUID}, or 0 when the tag names no track
     * @param trackCount the number of tracks the tag names
     * @param otherUid whether the tag names an edition, a chapter or an attachment
     * @param typeValue the {@code TargetTypeValue}, {@value #DEFAULT_TYPE_VALUE} unless declared
     */
    public record Targets(long trackUid, int trackCount, boolean otherUid, long typeValue) {
        /**
         * The target type value of an album, movie or episode, the default level.
         */
        public static final long DEFAULT_TYPE_VALUE = 50;

        /**
         * {@return whether the tag applies to the whole segment: it names no track, edition, chapter
         * or attachment, a unique identifier of 0 meaning all of them}
         */
        public boolean global() {
            return trackUid == 0 && !otherUid;
        }

        /**
         * {@return whether the tag applies to one track only: it names exactly one track and no edition, chapter or
         * attachment}
         */
        public boolean singleTrack() {
            return trackCount == 1 && !otherUid;
        }
    }

    /**
     * A chapter as a {@code ChapterAtom} stores it.
     *
     * @param startMicros the start, in microseconds
     * @param endMicros the end, in microseconds, or {@code -1} when the atom has no valid end
     * @param title the title, or {@code null}
     */
    public record ChapterSpan(long startMicros, long endMicros, String title) {
    }
}
