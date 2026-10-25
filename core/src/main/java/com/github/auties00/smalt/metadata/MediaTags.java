package com.github.auties00.smalt.metadata;

import com.github.auties00.smalt.container.AttachedPicture;
import com.github.auties00.smalt.container.Attachment;
import com.github.auties00.smalt.container.Chapter;
import com.github.auties00.smalt.container.ChapterEdition;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

/**
 * Holds the descriptive metadata of an audio or video file: text tags, attached pictures such as
 * cover art, other attached files, chapters, and the names, tags and dispositions of its tracks.
 *
 * <p>Tags are an ordered list of {@link Entry key-value entries}; a key may repeat, which is how
 * multi-valued tags (several artists, several genres) are represented. Keys are normalized when
 * an entry is added: a name {@link StandardTag#fromName(String) recognized} by the shared
 * vocabulary becomes its {@link StandardTag#key() normalized key} ({@code "TIT2"},
 * {@code "TITLE"} and {@code "title"} all become {@code "title"}); any other key is kept as given,
 * which is how format-specific tags such as {@code "MOOD"} or {@code "iTunNORM"} stay available.
 * Lookups by key ignore case.
 *
 * <p>The same instance describes the tags of every format {@link TagEditor} reads and writes and
 * the muxers accept: each writer maps the standard keys to the format's native names and stores
 * other keys in the format's free-form slot (ID3v2 {@code TXXX}, a Vorbis comment field, an MP4
 * {@code ----} atom, a Matroska {@code SimpleTag}).
 *
 * <p>Chapters are grouped in {@linkplain #editions() editions}; {@link #chapters()} returns those of the
 * default one, and the writers of formats that store a single edition write those. Only Matroska stores
 * several editions and the {@linkplain #attachments() attachments} that are not pictures.
 *
 * <p>The {@linkplain #tracks() track tags} describe single tracks of a container, each identified by
 * the {@link com.github.auties00.smalt.track.Track#id() identifier} its demuxer reports; {@link TrackTags} lists
 * what each container stores. A file of a format without track metadata has none, and its writers ignore them.
 *
 * <p>Instances are immutable and thread-safe; the {@link Builder} is not.
 *
 * @apiNote Read the tags of a file with {@link TagEditor#read(com.github.auties00.smalt.io.MediaSource)}, change them
 * through {@link #toBuilder()}, and write them back with {@link TagEditor#write(java.nio.file.Path, MediaTags)}.
 */
public final class MediaTags {
    /**
     * An instance with no tag, picture or chapter.
     */
    private static final MediaTags EMPTY = new MediaTags(List.of(), List.of(), List.of(), List.of(), List.of());

    /**
     * The separator {@link #asMap()} joins repeated values with.
     */
    private static final String VALUE_SEPARATOR = "; ";

    /**
     * The tag entries, in order.
     */
    private final List<Entry> entries;

    /**
     * The attached pictures, in order.
     */
    private final List<AttachedPicture> pictures;

    /**
     * The attachments that are not pictures, in order.
     */
    private final List<Attachment> attachments;

    /**
     * The chapter editions, in order.
     */
    private final List<ChapterEdition> editions;

    /**
     * The chapters of the default edition, in start order.
     */
    private final List<Chapter> chapters;

    /**
     * The track tags, in order.
     */
    private final List<TrackTags> tracks;

    /**
     * Constructs an instance.
     *
     * @param entries the tag entries
     * @param pictures the attached pictures
     * @param attachments the attachments that are not pictures
     * @param editions the chapter editions; the top-level chapters of those that are not ordered are sorted by start
     * @param tracks the track tags, one per track identifier
     */
    private MediaTags(List<Entry> entries, List<AttachedPicture> pictures, List<Attachment> attachments,
                      List<ChapterEdition> editions, List<TrackTags> tracks) {
        this.entries = List.copyOf(entries);
        this.pictures = List.copyOf(pictures);
        this.attachments = List.copyOf(attachments);
        var normalized = new ArrayList<ChapterEdition>(editions.size());
        for (var edition : editions) {
            normalized.add(edition.ordered() ? edition : edition.withChapters(sorted(edition.chapters())));
        }
        this.editions = List.copyOf(normalized);
        var index = ChapterEdition.defaultIndex(this.editions);
        this.chapters = index < 0 ? List.of() : sorted(this.editions.get(index).chapters());
        this.tracks = List.copyOf(tracks);
    }

    /**
     * Sorts chapters by start, keeping the order of those that start together.
     *
     * @param chapters the chapters to sort
     * @return the sorted chapters; unmodifiable
     */
    private static List<Chapter> sorted(List<Chapter> chapters) {
        var sorted = new ArrayList<>(chapters);
        sorted.sort((first, second) -> Long.compare(first.startMicros(), second.startMicros()));
        return List.copyOf(sorted);
    }

    /**
     * Returns an instance with no tag, picture or chapter.
     *
     * @return the empty instance
     */
    public static MediaTags empty() {
        return EMPTY;
    }

    /**
     * Returns a new, empty builder.
     *
     * @return the builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns a builder initialized with the contents of this instance.
     *
     * @return the builder
     */
    public Builder toBuilder() {
        var builder = new Builder();
        builder.entries.addAll(entries);
        builder.pictures.addAll(pictures);
        builder.attachments.addAll(attachments);
        builder.editions.addAll(editions);
        builder.tracks.addAll(tracks);
        return builder;
    }

    /**
     * Returns the tag entries in order.
     *
     * @return the entries; unmodifiable
     */
    public List<Entry> entries() {
        return entries;
    }

    /**
     * Returns the first value of a standard tag.
     *
     * @param tag the standard tag to look up
     * @return the value, or empty when the tag is absent
     */
    public Optional<String> get(StandardTag tag) {
        return get(tag.key());
    }

    /**
     * Returns the first value of a tag.
     *
     * @param key the key, normalized like the keys of added entries and compared ignoring case
     * @return the value, or empty when the tag is absent
     */
    public Optional<String> get(String key) {
        var normalized = StandardTag.normalize(key);
        for (var entry : entries) {
            if (entry.key().equalsIgnoreCase(normalized)) {
                return Optional.of(entry.value());
            }
        }
        return Optional.empty();
    }

    /**
     * Returns every value of a standard tag.
     *
     * @param tag the standard tag to look up
     * @return the values in order; unmodifiable, empty when the tag is absent
     */
    public List<String> getAll(StandardTag tag) {
        return getAll(tag.key());
    }

    /**
     * Returns every value of a tag.
     *
     * @param key the key, normalized like the keys of added entries and compared ignoring case
     * @return the values in order; unmodifiable, empty when the tag is absent
     */
    public List<String> getAll(String key) {
        var normalized = StandardTag.normalize(key);
        var result = new ArrayList<String>();
        for (var entry : entries) {
            if (entry.key().equalsIgnoreCase(normalized)) {
                result.add(entry.value());
            }
        }
        return List.copyOf(result);
    }

    /**
     * Returns whether a tag is present.
     *
     * @param key the key, normalized and compared ignoring case
     * @return {@code true} when at least one entry has the key
     */
    public boolean contains(String key) {
        return get(key).isPresent();
    }

    /**
     * Returns the distinct keys in order of first appearance.
     *
     * @return the keys; unmodifiable
     */
    public Set<String> keys() {
        var result = new LinkedHashSet<String>();
        for (var entry : entries) {
            result.add(entry.key());
        }
        return Collections.unmodifiableSet(result);
    }

    /**
     * Returns the tags as a map, the form {@code Media.probe} reports.
     *
     * @return the values keyed by key in order of first appearance, repeated values joined with {@code "; "};
     * unmodifiable
     */
    public Map<String, String> asMap() {
        var result = new LinkedHashMap<String, String>();
        for (var entry : entries) {
            result.merge(entry.key(), entry.value(), (previous, next) -> previous + VALUE_SEPARATOR + next);
        }
        return Collections.unmodifiableMap(result);
    }

    /**
     * Returns the track number of {@link StandardTag#TRACK}.
     *
     * @return the number before any {@code /}, or empty when the tag is absent or not a number
     */
    public OptionalInt trackNumber() {
        return part(StandardTag.TRACK, 0);
    }

    /**
     * Returns the number of tracks recorded after the {@code /} of {@link StandardTag#TRACK}.
     *
     * @return the total, or empty when absent or not a number
     */
    public OptionalInt trackTotal() {
        return part(StandardTag.TRACK, 1);
    }

    /**
     * Returns the disc number of {@link StandardTag#DISC}.
     *
     * @return the number before any {@code /}, or empty when the tag is absent or not a number
     */
    public OptionalInt discNumber() {
        return part(StandardTag.DISC, 0);
    }

    /**
     * Returns the number of discs recorded after the {@code /} of {@link StandardTag#DISC}.
     *
     * @return the total, or empty when absent or not a number
     */
    public OptionalInt discTotal() {
        return part(StandardTag.DISC, 1);
    }

    /**
     * Parses one side of an {@code n/total} value.
     *
     * @param tag {@link StandardTag#TRACK} or {@link StandardTag#DISC}
     * @param side {@code 0} for the number, {@code 1} for the total
     * @return the parsed side, or empty
     */
    private OptionalInt part(StandardTag tag, int side) {
        var value = get(tag);
        if (value.isEmpty()) {
            return OptionalInt.empty();
        }
        var parts = value.get().split("/", 2);
        if (side >= parts.length) {
            return OptionalInt.empty();
        }
        try {
            var number = Integer.parseInt(parts[side].trim());
            return number >= 0 ? OptionalInt.of(number) : OptionalInt.empty();
        } catch (NumberFormatException exception) {
            return OptionalInt.empty();
        }
    }

    /**
     * Returns the attached pictures in order.
     *
     * @return the pictures; unmodifiable
     */
    public List<AttachedPicture> pictures() {
        return pictures;
    }

    /**
     * Returns the attached files that are not pictures, such as fonts.
     *
     * @return the attachments in order; unmodifiable
     */
    public List<Attachment> attachments() {
        return attachments;
    }

    /**
     * Returns the chapters of the {@linkplain ChapterEdition#defaultIndex default edition}, in start order.
     *
     * @return the chapters; unmodifiable, empty without editions
     */
    public List<Chapter> chapters() {
        return chapters;
    }

    /**
     * Returns the chapter editions, in order; the top-level chapters of an edition that is not
     * {@linkplain ChapterEdition#ordered() ordered} are in start order.
     *
     * @return the editions; unmodifiable
     */
    public List<ChapterEdition> editions() {
        return editions;
    }

    /**
     * Returns the tags of the tracks.
     *
     * @return the track tags, at most one per track identifier, in the order they were added; unmodifiable
     */
    public List<TrackTags> tracks() {
        return tracks;
    }

    /**
     * Returns the tags of one track.
     *
     * @param trackId the track identifier
     * @return the track tags, or empty when this instance holds none for the track
     */
    public Optional<TrackTags> track(int trackId) {
        for (var track : tracks) {
            if (track.trackId() == trackId) {
                return Optional.of(track);
            }
        }
        return Optional.empty();
    }

    /**
     * Returns whether this instance holds no tag, picture, attachment, chapter edition or track tags.
     *
     * @return {@code true} when empty
     */
    public boolean isEmpty() {
        return entries.isEmpty() && pictures.isEmpty() && attachments.isEmpty() && editions.isEmpty()
                && tracks.isEmpty();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof MediaTags that && entries.equals(that.entries) && pictures.equals(that.pictures)
                && attachments.equals(that.attachments) && editions.equals(that.editions)
                && tracks.equals(that.tracks);
    }

    @Override
    public int hashCode() {
        return Objects.hash(entries, pictures, attachments, editions, tracks);
    }

    @Override
    public String toString() {
        return "MediaTags[entries=" + entries + ", pictures=" + pictures + ", attachments=" + attachments
                + ", editions=" + editions + ", tracks=" + tracks + "]";
    }

    /**
     * One tag value.
     *
     * <p>The key is not blank and holds no {@code '='} and no control character, so every format
     * can store it; the value holds no NUL character.
     *
     * @param key the normalized key
     * @param value the value, not blank
     */
    public record Entry(String key, String value) {
        /**
         * Validates the entry and normalizes its key.
         *
         * @throws IllegalArgumentException if the key is blank or holds {@code '='} or a control character, or the
         * value holds a NUL character
         */
        public Entry {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(value, "value");
            if (key.isBlank()) {
                throw new IllegalArgumentException("blank tag key");
            }
            for (var i = 0; i < key.length(); i++) {
                var c = key.charAt(i);
                if (c == '=' || c < 0x20 || c == 0x7F) {
                    throw new IllegalArgumentException("tag key \"" + key + "\" holds an invalid character");
                }
            }
            if (value.indexOf('\0') >= 0) {
                throw new IllegalArgumentException("the value of tag \"" + key + "\" holds a NUL character");
            }
            key = StandardTag.normalize(key);
        }
    }

    /**
     * Assembles a {@link MediaTags}.
     *
     * <p>Instances are not thread-safe.
     */
    public static final class Builder {
        /**
         * The entries.
         */
        private final List<Entry> entries = new ArrayList<>();

        /**
         * The pictures.
         */
        private final List<AttachedPicture> pictures = new ArrayList<>();

        /**
         * The attachments.
         */
        private final List<Attachment> attachments = new ArrayList<>();

        /**
         * The chapter editions.
         */
        private final List<ChapterEdition> editions = new ArrayList<>();

        /**
         * The track tags.
         */
        private final List<TrackTags> tracks = new ArrayList<>();

        /**
         * Constructs an empty builder.
         */
        private Builder() {
        }

        /**
         * Replaces every value of a standard tag.
         *
         * <p>With no value the tag is removed. The new values take the position of the first
         * replaced entry, or go last when the tag was absent.
         *
         * @param tag the standard tag to set
         * @param values the new values
         * @return this builder
         * @throws NullPointerException if a value is {@code null}
         * @throws IllegalArgumentException if a value holds a NUL character
         */
        public Builder set(StandardTag tag, String... values) {
            return set(tag.key(), values);
        }

        /**
         * Replaces every value of a tag.
         *
         * <p>With no value the tag is removed. The new values take the position of the first
         * replaced entry, or go last when the tag was absent.
         *
         * @param key the key, normalized and compared ignoring case
         * @param values the new values
         * @return this builder
         * @throws NullPointerException if a value is {@code null}
         * @throws IllegalArgumentException if the key or a value is invalid for an {@link Entry}
         */
        public Builder set(String key, String... values) {
            Objects.requireNonNull(values, "values");
            var created = new ArrayList<Entry>(values.length);
            for (var value : values) {
                created.add(new Entry(key, value));
            }
            var normalized = StandardTag.normalize(key);
            var at = -1;
            for (var index = entries.size() - 1; index >= 0; index--) {
                if (entries.get(index).key().equalsIgnoreCase(normalized)) {
                    entries.remove(index);
                    at = index;
                }
            }
            entries.addAll(at >= 0 ? at : entries.size(), created);
            return this;
        }

        /**
         * Appends one value of a standard tag, keeping its other values.
         *
         * @param tag the standard tag to add a value to
         * @param value the value to append
         * @return this builder
         * @throws IllegalArgumentException if the value holds a NUL character
         */
        public Builder add(StandardTag tag, String value) {
            return add(tag.key(), value);
        }

        /**
         * Appends one value of a tag, keeping its other values.
         *
         * @param key the tag key, a standard tag name or a format-specific key
         * @param value the value to append
         * @return this builder
         * @throws IllegalArgumentException if the key or the value is invalid for an {@link Entry}
         */
        public Builder add(String key, String value) {
            entries.add(new Entry(key, value));
            return this;
        }

        /**
         * Removes every value of a standard tag.
         *
         * @param tag the standard tag to remove
         * @return this builder
         */
        public Builder remove(StandardTag tag) {
            return remove(tag.key());
        }

        /**
         * Removes every value of a tag.
         *
         * @param key the key, normalized and compared ignoring case
         * @return this builder
         */
        public Builder remove(String key) {
            var normalized = StandardTag.normalize(key);
            entries.removeIf(entry -> entry.key().equalsIgnoreCase(normalized));
            return this;
        }

        /**
         * Removes every tag entry, keeping pictures, attachments, chapters and track tags.
         *
         * @return this builder
         */
        public Builder clearEntries() {
            entries.clear();
            return this;
        }

        /**
         * Appends an attached picture.
         *
         * @param picture the picture to append
         * @return this builder
         */
        public Builder addPicture(AttachedPicture picture) {
            pictures.add(Objects.requireNonNull(picture, "picture"));
            return this;
        }

        /**
         * Replaces every attached picture.
         *
         * @param pictures the pictures, in order
         * @return this builder
         * @throws NullPointerException if a picture is {@code null}
         */
        public Builder pictures(List<AttachedPicture> pictures) {
            var copy = List.copyOf(pictures);
            this.pictures.clear();
            this.pictures.addAll(copy);
            return this;
        }

        /**
         * Appends an attachment.
         *
         * @param attachment the attachment to append
         * @return this builder
         */
        public Builder addAttachment(Attachment attachment) {
            attachments.add(Objects.requireNonNull(attachment, "attachment"));
            return this;
        }

        /**
         * Replaces every attachment.
         *
         * @param attachments the attachments, in order
         * @return this builder
         * @throws NullPointerException if an attachment is {@code null}
         */
        public Builder attachments(List<Attachment> attachments) {
            var copy = List.copyOf(attachments);
            this.attachments.clear();
            this.attachments.addAll(copy);
            return this;
        }

        /**
         * Appends a chapter to the {@linkplain ChapterEdition#defaultIndex default edition}, creating an edition
         * when there is none.
         *
         * @param chapter the chapter to append
         * @return this builder
         */
        public Builder addChapter(Chapter chapter) {
            Objects.requireNonNull(chapter, "chapter");
            var index = ChapterEdition.defaultIndex(editions);
            if (index < 0) {
                editions.add(ChapterEdition.of(List.of(chapter)));
            } else {
                var edition = editions.get(index);
                var chapters = new ArrayList<>(edition.chapters());
                chapters.add(chapter);
                editions.set(index, edition.withChapters(chapters));
            }
            return this;
        }

        /**
         * Replaces every chapter edition with one edition holding these chapters, or with none when the list is
         * empty.
         *
         * @param chapters the chapters, in any order
         * @return this builder
         * @throws NullPointerException if a chapter is {@code null}
         */
        public Builder chapters(List<Chapter> chapters) {
            var copy = List.copyOf(chapters);
            this.editions.clear();
            if (!copy.isEmpty()) {
                this.editions.add(ChapterEdition.of(copy));
            }
            return this;
        }

        /**
         * Replaces every chapter edition.
         *
         * @param editions the editions, in order
         * @return this builder
         * @throws NullPointerException if an edition is {@code null}
         */
        public Builder editions(List<ChapterEdition> editions) {
            var copy = List.copyOf(editions);
            this.editions.clear();
            this.editions.addAll(copy);
            return this;
        }

        /**
         * Sets the tags of one track, replacing those this builder holds for the same track identifier.
         *
         * @param track the track tags
         * @return this builder
         */
        public Builder track(TrackTags track) {
            Objects.requireNonNull(track, "track");
            for (var index = 0; index < tracks.size(); index++) {
                if (tracks.get(index).trackId() == track.trackId()) {
                    tracks.set(index, track);
                    return this;
                }
            }
            tracks.add(track);
            return this;
        }

        /**
         * Removes the tags of one track.
         *
         * @param trackId the track identifier
         * @return this builder
         */
        public Builder removeTrack(int trackId) {
            tracks.removeIf(track -> track.trackId() == trackId);
            return this;
        }

        /**
         * Replaces the tags of every track.
         *
         * @param tracks the track tags; a later one replaces an earlier one of the same track identifier
         * @return this builder
         * @throws NullPointerException if an element is {@code null}
         */
        public Builder tracks(List<TrackTags> tracks) {
            var copy = List.copyOf(tracks);
            this.tracks.clear();
            for (var track : copy) {
                track(track);
            }
            return this;
        }

        /**
         * Removes every entry, picture, attachment, chapter edition and track tags.
         *
         * @return this builder
         */
        public Builder clear() {
            entries.clear();
            pictures.clear();
            attachments.clear();
            editions.clear();
            tracks.clear();
            return this;
        }

        /**
         * Builds the instance.
         *
         * @return the tags
         */
        public MediaTags build() {
            if (entries.isEmpty() && pictures.isEmpty() && attachments.isEmpty() && editions.isEmpty()
                    && tracks.isEmpty()) {
                return EMPTY;
            }
            return new MediaTags(entries, pictures, attachments, editions, tracks);
        }
    }
}
