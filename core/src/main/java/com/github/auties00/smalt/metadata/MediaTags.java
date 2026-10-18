package com.github.auties00.smalt.metadata;

import com.github.auties00.smalt.container.AttachedPicture;
import com.github.auties00.smalt.container.Chapter;

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
 * cover art, and chapters.
 *
 * <p>Tags are an ordered list of {@link Entry key-value entries}; a key may repeat, which is how
 * multi-valued tags (several artists, several genres) are represented. Keys are normalized when
 * an entry is added: a name {@link StandardTag#fromName(String) recognized} by the shared
 * vocabulary becomes its {@link StandardTag#key() normalized key} ({@code "TIT2"},
 * {@code "TITLE"} and {@code "title"} all become {@code "title"}); any other key is kept as given,
 * which is how format-specific tags such as {@code "REPLAYGAIN_TRACK_GAIN"}, {@code "TBPM"} or
 * MusicBrainz identifiers stay available. Lookups by key ignore case.
 *
 * <p>The same instance describes the tags of every format {@link TagEditor} reads and writes and
 * the muxers accept: each writer maps the standard keys to the format's native names and stores
 * other keys in the format's free-form slot (ID3v2 {@code TXXX}, a Vorbis comment field, an MP4
 * {@code ----} atom, a Matroska {@code SimpleTag}).
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
    private static final MediaTags EMPTY = new MediaTags(List.of(), List.of(), List.of());

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
     * The chapters, in start order.
     */
    private final List<Chapter> chapters;

    /**
     * Constructs an instance.
     *
     * @param entries the tag entries
     * @param pictures the attached pictures
     * @param chapters the chapters in start order
     */
    private MediaTags(List<Entry> entries, List<AttachedPicture> pictures, List<Chapter> chapters) {
        this.entries = List.copyOf(entries);
        this.pictures = List.copyOf(pictures);
        var sorted = new ArrayList<>(chapters);
        sorted.sort((first, second) -> Long.compare(first.startMicros(), second.startMicros()));
        this.chapters = List.copyOf(sorted);
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
        builder.chapters.addAll(chapters);
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
     * Returns the chapters in start order.
     *
     * @return the chapters; unmodifiable
     */
    public List<Chapter> chapters() {
        return chapters;
    }

    /**
     * Returns whether this instance holds no tag, picture or chapter.
     *
     * @return {@code true} when empty
     */
    public boolean isEmpty() {
        return entries.isEmpty() && pictures.isEmpty() && chapters.isEmpty();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof MediaTags that && entries.equals(that.entries) && pictures.equals(that.pictures)
                && chapters.equals(that.chapters);
    }

    @Override
    public int hashCode() {
        return Objects.hash(entries, pictures, chapters);
    }

    @Override
    public String toString() {
        return "MediaTags[entries=" + entries + ", pictures=" + pictures + ", chapters=" + chapters + "]";
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
         * The chapters.
         */
        private final List<Chapter> chapters = new ArrayList<>();

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
         * Removes every tag entry, keeping pictures and chapters.
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
         * Appends a chapter.
         *
         * @param chapter the chapter to append
         * @return this builder
         */
        public Builder addChapter(Chapter chapter) {
            chapters.add(Objects.requireNonNull(chapter, "chapter"));
            return this;
        }

        /**
         * Replaces every chapter.
         *
         * @param chapters the chapters, in any order
         * @return this builder
         * @throws NullPointerException if a chapter is {@code null}
         */
        public Builder chapters(List<Chapter> chapters) {
            var copy = List.copyOf(chapters);
            this.chapters.clear();
            this.chapters.addAll(copy);
            return this;
        }

        /**
         * Removes every entry, picture and chapter.
         *
         * @return this builder
         */
        public Builder clear() {
            entries.clear();
            pictures.clear();
            chapters.clear();
            return this;
        }

        /**
         * Builds the instance.
         *
         * @return the tags
         */
        public MediaTags build() {
            if (entries.isEmpty() && pictures.isEmpty() && chapters.isEmpty()) {
                return EMPTY;
            }
            return new MediaTags(entries, pictures, chapters);
        }
    }
}
