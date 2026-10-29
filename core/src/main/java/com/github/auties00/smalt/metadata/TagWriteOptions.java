package com.github.auties00.smalt.metadata;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.internal.check.Checks;

import java.util.Objects;
import java.util.Optional;

/**
 * Holds the settings of {@link TagEditor#write(java.nio.file.Path, MediaTags, TagWriteOptions)}.
 * Instances are immutable and thread-safe; create them with {@link #builder()} or a preset such as
 * {@link #defaults()}.
 */
public final class TagWriteOptions {
    /**
     * The default number of padding bytes reserved when a tag has to grow.
     */
    public static final int DEFAULT_PADDING = 4096;

    /**
     * The largest padding accepted, in bytes.
     */
    public static final int MAX_PADDING = 1 << 24;

    /**
     * The options of {@link #defaults()}.
     */
    private static final TagWriteOptions DEFAULTS = new TagWriteOptions(null, Id3v1Mode.UPDATE_EXISTING,
            ApeMode.UPDATE_EXISTING, DEFAULT_PADDING, true, true, MediaLimits.DEFAULT);

    /**
     * The ID3v2 version to write, or {@code null} to keep the file's.
     */
    private final Id3Version id3Version;

    private final Id3v1Mode id3v1Mode;
    private final ApeMode apeMode;
    private final int padding;
    private final boolean inPlace;
    private final boolean preserveUnknown;
    private final MediaLimits limits;

    private TagWriteOptions(Id3Version id3Version, Id3v1Mode id3v1Mode, ApeMode apeMode, int padding, boolean inPlace,
                            boolean preserveUnknown, MediaLimits limits) {
        this.id3Version = id3Version;
        this.id3v1Mode = id3v1Mode;
        this.apeMode = apeMode;
        this.padding = padding;
        this.inPlace = inPlace;
        this.preserveUnknown = preserveUnknown;
        this.limits = limits;
    }

    /**
     * Returns the default options: keep the ID3v2 version of the file (2.4 for a new tag), update
     * an existing ID3v1 tag and an existing APE tag, reserve {@value #DEFAULT_PADDING} bytes of
     * padding when a tag grows, edit in place when possible, keep items {@link MediaTags} cannot
     * represent, and enforce
     * {@link MediaLimits#defaults()}.
     *
     * @return the default options
     */
    public static TagWriteOptions defaults() {
        return DEFAULTS;
    }

    /**
     * Returns a builder initialized with {@link #defaults()}.
     *
     * @return the builder
     */
    public static Builder builder() {
        return new Builder(DEFAULTS);
    }

    /**
     * Returns a builder initialized with these options.
     *
     * @return the builder
     */
    public Builder toBuilder() {
        return new Builder(this);
    }

    /**
     * {@return the ID3v2 version written to MP3, ADTS, WAV and AIFF files, or empty to keep the version
     * of the existing tag (2.4 when the file has none, or when the existing tag is version 2.2); empty
     * by default}
     */
    public Optional<Id3Version> id3Version() {
        return Optional.ofNullable(id3Version);
    }

    /**
     * {@return what happens to the ID3v1 tag at the end of an MP3, ADTS, raw AC-3 or FLAC file;
     * {@link Id3v1Mode#UPDATE_EXISTING} by default}
     */
    public Id3v1Mode id3v1Mode() {
        return id3v1Mode;
    }

    /**
     * {@return what happens to the APEv1 or APEv2 tag at the end of an MP3, ADTS, raw AC-3 or FLAC file;
     * {@link ApeMode#UPDATE_EXISTING} by default}
     *
     * <p>The APE tag written is an APEv2 tag with header and footer that mirrors the new tags: every entry whose key
     * APE can store (2 to 255 printable ASCII characters, other than {@code ID3}, {@code TAG}, {@code OggS} and
     * {@code MP+}) under the usual APE key of a standard tag ({@code Title}, {@code Album Artist}, {@code Year},
     * {@code Track}, ...), and the first picture of each
     * {@link com.github.auties00.smalt.container.AttachedPicture.Type} as a {@code "Cover Art (...)"} item.
     * Binary and external-locator items are kept when {@link #preserveUnknown()} is on. No tag is written when there
     * is nothing to store.
     */
    public ApeMode apeMode() {
        return apeMode;
    }

    /**
     * {@return the padding reserved when a tag outgrows its space and the file is rewritten, so that
     * later edits fit in place, in bytes; from 0 to {@link #MAX_PADDING}; {@value #DEFAULT_PADDING} by
     * default}
     *
     * <p>It applies to ID3v2 tags, FLAC metadata, Opus comment headers, MP4 {@code moov} boxes
     * and Matroska headers; an edit that fits the existing space reuses the existing padding
     * instead.
     */
    public int padding() {
        return padding;
    }

    /**
     * {@return whether {@link TagEditor#write(java.nio.file.Path, MediaTags, TagWriteOptions)} may
     * modify the file in place when the new tags fit the existing space; {@code true} by default}
     *
     * <p>An in-place edit writes only the changed bytes and never moves the audio or video data,
     * but a crash in the middle of it can leave the file inconsistent. When this is
     * {@code false} the file is always rewritten to a temporary file that atomically replaces it.
     */
    public boolean inPlace() {
        return inPlace;
    }

    /**
     * {@return whether items that {@link MediaTags} cannot represent are kept; {@code true} by default}
     *
     * <p>These items are binary ID3v2 frames ({@code PRIV}, {@code GEOB}, the {@code POPM} frames after the
     * first, ...), binary and external-locator APE items, FLAC
     * {@code APPLICATION} and {@code CUESHEET} blocks, non-text MP4 atoms (in the movie's and the tracks'
     * {@code ilst}), QuickTime keyed metadata items that hold no text or number (such as artwork) while the values
     * of their key are unchanged, Matroska tags that target several tracks, an edition, a chapter or an attachment,
     * and Matroska attachments that are not pictures. The Matroska tags of one track are
     * {@link MediaTags#tracks() track tags}, kept whatever this option.
     */
    public boolean preserveUnknown() {
        return preserveUnknown;
    }

    /**
     * {@return the resource bounds enforced while reading the existing metadata;
     * {@link MediaLimits#defaults()} by default}
     */
    public MediaLimits limits() {
        return limits;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof TagWriteOptions that && id3Version == that.id3Version && id3v1Mode == that.id3v1Mode
                && apeMode == that.apeMode && padding == that.padding && inPlace == that.inPlace
                && preserveUnknown == that.preserveUnknown && limits.equals(that.limits);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id3Version, id3v1Mode, apeMode, padding, inPlace, preserveUnknown, limits);
    }

    @Override
    public String toString() {
        return "TagWriteOptions[id3Version=" + id3Version + ", id3v1Mode=" + id3v1Mode + ", apeMode=" + apeMode
                + ", padding=" + padding
                + ", inPlace=" + inPlace + ", preserveUnknown=" + preserveUnknown + ", limits=" + limits + "]";
    }

    /**
     * Names an ID3v2 version {@link TagEditor} can write.
     */
    public enum Id3Version {
        /**
         * ID3v2.3, the most widely supported version; text is ISO-8859-1 or UTF-16.
         */
        V2_3,

        /**
         * ID3v2.4; text is UTF-8 and multiple values are separated by NUL characters.
         */
        V2_4
    }

    /**
     * Names what happens to the 128-byte ID3v1 tag at the end of an MP3, ADTS, raw AC-3 or FLAC file.
     */
    public enum Id3v1Mode {
        /**
         * Removes an existing ID3v1 tag.
         */
        REMOVE,

        /**
         * Rewrites an existing ID3v1 tag from the new tags and adds none to a file without one.
         */
        UPDATE_EXISTING,

        /**
         * Writes an ID3v1 tag from the new tags, adding one when the file has none.
         */
        ALWAYS
    }

    /**
     * Names what happens to the APE tag at the end of an MP3, ADTS, raw AC-3 or FLAC file.
     */
    public enum ApeMode {
        /**
         * Removes an existing APE tag.
         */
        REMOVE,

        /**
         * Rewrites an existing APEv1 or APEv2 tag as an APEv2 tag from the new tags and adds none to a file without
         * one.
         */
        UPDATE_EXISTING,

        /**
         * Writes an APEv2 tag from the new tags, adding one when the file has none.
         */
        ALWAYS
    }

    /**
     * Assembles {@link TagWriteOptions} setting by setting.
     *
     * <p>Setters reject {@code null} and store numbers unchecked; {@link #build()} checks the ranges.
     * Instances are not thread-safe.
     */
    public static final class Builder {
        private Id3Version id3Version;
        private Id3v1Mode id3v1Mode;
        private ApeMode apeMode;
        private int padding;
        private boolean inPlace;
        private boolean preserveUnknown;
        private MediaLimits limits;

        private Builder(TagWriteOptions options) {
            this.id3Version = options.id3Version;
            this.id3v1Mode = options.id3v1Mode;
            this.apeMode = options.apeMode;
            this.padding = options.padding;
            this.inPlace = options.inPlace;
            this.preserveUnknown = options.preserveUnknown;
            this.limits = options.limits;
        }

        /**
         * Sets the ID3v2 version; see {@link TagWriteOptions#id3Version()}.
         *
         * @param id3Version the value
         * @return this builder
         */
        public Builder id3Version(Id3Version id3Version) {
            this.id3Version = Objects.requireNonNull(id3Version, "id3Version");
            return this;
        }

        /**
         * Keeps the ID3v2 version of the file; see {@link TagWriteOptions#id3Version()}.
         *
         * @return this builder
         */
        public Builder sourceId3Version() {
            this.id3Version = null;
            return this;
        }

        /**
         * Sets the ID3v1 policy; see {@link TagWriteOptions#id3v1Mode()}.
         *
         * @param id3v1Mode the value
         * @return this builder
         */
        public Builder id3v1Mode(Id3v1Mode id3v1Mode) {
            this.id3v1Mode = Objects.requireNonNull(id3v1Mode, "id3v1Mode");
            return this;
        }

        /**
         * Sets the APE policy; see {@link TagWriteOptions#apeMode()}.
         *
         * @param apeMode the value
         * @return this builder
         */
        public Builder apeMode(ApeMode apeMode) {
            this.apeMode = Objects.requireNonNull(apeMode, "apeMode");
            return this;
        }

        /**
         * Sets the padding; see {@link TagWriteOptions#padding()}.
         *
         * @param padding the value
         * @return this builder
         */
        public Builder padding(int padding) {
            this.padding = padding;
            return this;
        }

        /**
         * Sets whether in-place edits are allowed; see {@link TagWriteOptions#inPlace()}.
         *
         * @param inPlace the value
         * @return this builder
         */
        public Builder inPlace(boolean inPlace) {
            this.inPlace = inPlace;
            return this;
        }

        /**
         * Sets whether unrepresented items are kept; see {@link TagWriteOptions#preserveUnknown()}.
         *
         * @param preserveUnknown the value
         * @return this builder
         */
        public Builder preserveUnknown(boolean preserveUnknown) {
            this.preserveUnknown = preserveUnknown;
            return this;
        }

        /**
         * Sets the resource bounds; see {@link TagWriteOptions#limits()}.
         *
         * @param limits the value
         * @return this builder
         */
        public Builder limits(MediaLimits limits) {
            this.limits = Objects.requireNonNull(limits, "limits");
            return this;
        }

        /**
         * Builds the options.
         *
         * @return the options
         * @throws IllegalArgumentException if the padding is negative or above {@link #MAX_PADDING}
         */
        public TagWriteOptions build() {
            return new TagWriteOptions(id3Version, id3v1Mode, apeMode,
                    Checks.inRange("padding", padding, 0, MAX_PADDING), inPlace, preserveUnknown, limits);
        }
    }
}
