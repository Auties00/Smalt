package com.github.auties00.smalt.container.packaging;

import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.track.Track;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.regex.Pattern;

/**
 * Describes one rendition of a presentation: a variant (an encode of the content, usually at one size and bit rate
 * among several), an alternate audio rendition (a language, a commentary) or a subtitle rendition.
 *
 * <p>A rendition reads its tracks from a media file ({@link #of(MediaSource)}, {@link #builder(MediaSource)}), which
 * {@link Packager#pack(List, PackageOutput, PackagingOptions)} demuxes, or declares them for the packets an
 * application writes itself ({@link #builder(List)}, for {@link Packager#open(List, PackageOutput, PackagingOptions)}).
 * Of those tracks, the {@link Kind} selects:
 * <ul>
 *   <li>{@link Kind#VARIANT}: the first video track and the first audio track, or only the video track when the
 *       presentation has audio renditions;</li>
 *   <li>{@link Kind#AUDIO}: the track of {@link #trackId()}, or else the first audio track;</li>
 *   <li>{@link Kind#SUBTITLES}: the track of {@link #trackId()}, or else the first text subtitle track
 *       ({@link com.github.auties00.smalt.track.Codec#isSubtitle()}, TTML included).</li>
 * </ul>
 * The name prefixes the files written for the rendition and identifies it in the manifests; without one the packager
 * names renditions {@code r0}, {@code r1} and so on, in the order they are given.
 *
 * <p>Instances are immutable and thread-safe; the source itself is read by the packager.
 */
public final class Rendition {
    /**
     * Enumerates the roles of a rendition in a presentation.
     */
    public enum Kind {
        /**
         * A variant: an HLS variant stream ({@code EXT-X-STREAM-INF}) and DASH video (or, for an audio-only
         * presentation, audio) representations.
         */
        VARIANT,

        /**
         * An alternate audio rendition: a member of an HLS audio group ({@code EXT-X-MEDIA TYPE=AUDIO}) that every
         * variant references, and a DASH audio representation.
         */
        AUDIO,

        /**
         * A subtitle rendition: an HLS subtitle group member ({@code EXT-X-MEDIA TYPE=SUBTITLES}) of WebVTT segments,
         * and a DASH text adaptation set of CMAF text segments ({@code wvtt}, or {@code stpp} for TTML).
         */
        SUBTITLES
    }

    /**
     * The characters a rendition name may hold, so that it is a safe file name prefix.
     */
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    /**
     * The input file, or {@code null} for declared tracks.
     */
    private final MediaSource source;

    /**
     * The declared tracks, or {@code null} for an input file.
     */
    private final List<Track> tracks;

    private final Kind kind;

    /**
     * The name, or {@code null}.
     */
    private final String name;

    /**
     * The selected track identifier, or -1 for the kind's default selection.
     */
    private final int trackId;

    /**
     * The language, or {@code null} for the track's.
     */
    private final String language;

    /**
     * The label, or {@code null} for the default.
     */
    private final String label;

    private final boolean defaultRendition;
    private final boolean forced;

    private Rendition(Builder builder) {
        this.source = builder.source;
        this.tracks = builder.tracks;
        this.kind = builder.kind;
        this.name = builder.name;
        this.trackId = builder.trackId;
        this.language = builder.language;
        this.label = builder.label;
        this.defaultRendition = builder.defaultRendition;
        this.forced = builder.forced;
    }

    /**
     * Returns a variant read from a media file, named by its position.
     *
     * @param source the input, in any container {@link com.github.auties00.smalt.container.Containers} opens; not
     * closed by the packager
     * @return the rendition
     */
    public static Rendition of(MediaSource source) {
        return builder(source).build();
    }

    /**
     * Returns a named variant read from a media file.
     *
     * @param name the name, 1 to 64 letters, digits, underscores or hyphens
     * @param source the input; not closed by the packager
     * @return the rendition
     * @throws IllegalArgumentException if the name holds other characters
     */
    public static Rendition of(String name, MediaSource source) {
        return builder(source).name(name).build();
    }

    /**
     * Returns a named alternate audio rendition of the first audio track of a media file.
     *
     * @param name the name, 1 to 64 letters, digits, underscores or hyphens
     * @param source the input; not closed by the packager
     * @return the rendition
     * @throws IllegalArgumentException if the name holds other characters
     */
    public static Rendition ofAudio(String name, MediaSource source) {
        return builder(source).kind(Kind.AUDIO).name(name).build();
    }

    /**
     * Returns a named subtitle rendition of the first text subtitle track of a media file, which may be a subtitle
     * file such as a {@code .srt} or {@code .vtt}.
     *
     * @param name the name, 1 to 64 letters, digits, underscores or hyphens
     * @param source the input; not closed by the packager
     * @return the rendition
     * @throws IllegalArgumentException if the name holds other characters
     */
    public static Rendition ofSubtitles(String name, MediaSource source) {
        return builder(source).kind(Kind.SUBTITLES).name(name).build();
    }

    /**
     * Returns a builder of a rendition read from a media file: an unnamed variant until set otherwise.
     *
     * @param source the input; not closed by the packager
     * @return the builder
     */
    public static Builder builder(MediaSource source) {
        return new Builder(Objects.requireNonNull(source, "source"), null);
    }

    /**
     * Returns a builder of a rendition whose packets the application writes to a {@link PackageWriter}: an unnamed
     * variant until set otherwise.
     *
     * <p>The packets written for the rendition name these tracks by their identifiers.
     *
     * @param tracks the tracks, at least one, with distinct identifiers; copied
     * @return the builder
     * @throws NullPointerException if a track is {@code null}
     * @throws IllegalArgumentException if {@code tracks} is empty or two tracks share an identifier
     */
    public static Builder builder(List<? extends Track> tracks) {
        var copy = List.<Track>copyOf(tracks);
        if (copy.isEmpty()) {
            throw new IllegalArgumentException("a rendition needs a track");
        }
        for (var index = 0; index < copy.size(); index++) {
            for (var other = index + 1; other < copy.size(); other++) {
                if (copy.get(index).id() == copy.get(other).id()) {
                    throw new IllegalArgumentException("two tracks have the identifier " + copy.get(index).id());
                }
            }
        }
        return new Builder(null, copy);
    }

    /**
     * {@return a builder initialized with this rendition}
     */
    public Builder toBuilder() {
        var builder = new Builder(source, tracks);
        builder.kind = kind;
        builder.name = name;
        builder.trackId = trackId;
        builder.language = language;
        builder.label = label;
        builder.defaultRendition = defaultRendition;
        builder.forced = forced;
        return builder;
    }

    /**
     * {@return the input file, or empty for a rendition of declared tracks}
     */
    public Optional<MediaSource> source() {
        return Optional.ofNullable(source);
    }

    /**
     * {@return the declared tracks, or an empty list for a rendition read from a file}
     */
    public List<Track> tracks() {
        return tracks == null ? List.of() : tracks;
    }

    /**
     * {@return the role of the rendition; {@link Kind#VARIANT} by default}
     */
    public Kind kind() {
        return kind;
    }

    /**
     * {@return the name, or empty when the packager names the rendition by its position}
     */
    public Optional<String> name() {
        return Optional.ofNullable(name);
    }

    /**
     * {@return the identifier of the track an audio or subtitle rendition packages, or empty for the first track of
     * its kind; empty by default}
     */
    public OptionalInt trackId() {
        return trackId < 0 ? OptionalInt.empty() : OptionalInt.of(trackId);
    }

    /**
     * {@return the language the manifests declare for the rendition's audio or subtitles, an RFC 5646 tag or an
     * ISO 639-2 code, or empty for the track's own; empty by default}
     *
     * <p>The manifests declare an RFC 5646 tag: a primary language given as an ISO 639-2 code, such as {@code fre}
     * or {@code fre-CA}, is written as its ISO 639-1 code ({@code fr}, {@code fr-CA}) when it has one.
     */
    public Optional<String> language() {
        return Optional.ofNullable(language);
    }

    /**
     * {@return the human-readable name players show for an audio or subtitle rendition (the HLS {@code NAME}, the
     * DASH {@code Label}), or empty for the rendition's name; empty by default}
     */
    public Optional<String> label() {
        return Optional.ofNullable(label);
    }

    /**
     * {@return whether players choose this audio or subtitle rendition without a user preference: HLS
     * {@code DEFAULT=YES}, DASH role {@code main}; {@code false} by default}
     *
     * <p>An audio group without a default rendition takes its first as the default; a subtitle rendition is shown
     * by default only when marked.
     */
    public boolean isDefault() {
        return defaultRendition;
    }

    /**
     * {@return whether a subtitle rendition holds forced subtitles only (translations of foreign dialogue and
     * signs): HLS {@code FORCED=YES}, DASH role {@code forced-subtitle}; {@code false} by default}
     */
    public boolean isForced() {
        return forced;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Rendition that && Objects.equals(source, that.source)
                && Objects.equals(tracks, that.tracks) && kind == that.kind && Objects.equals(name, that.name)
                && trackId == that.trackId && Objects.equals(language, that.language)
                && Objects.equals(label, that.label) && defaultRendition == that.defaultRendition
                && forced == that.forced;
    }

    @Override
    public int hashCode() {
        return Objects.hash(source, tracks, kind, name, trackId, language, label, defaultRendition, forced);
    }

    @Override
    public String toString() {
        return "Rendition[kind=" + kind + ", name=" + name + (source != null ? ", source=" + source
                : ", tracks=" + tracks.size()) + (trackId >= 0 ? ", trackId=" + trackId : "")
                + (language != null ? ", language=" + language : "") + (label != null ? ", label=" + label : "")
                + (defaultRendition ? ", default" : "") + (forced ? ", forced" : "") + "]";
    }

    /**
     * Assembles a {@link Rendition} setting by setting.
     *
     * <p>Setters reject {@code null} and check the name; {@link #build()} checks the combination of settings.
     * Instances are not thread-safe.
     */
    public static final class Builder {
        private final MediaSource source;
        private final List<Track> tracks;
        private Kind kind = Kind.VARIANT;
        private String name;
        private int trackId = -1;
        private String language;
        private String label;
        private boolean defaultRendition;
        private boolean forced;

        private Builder(MediaSource source, List<Track> tracks) {
            this.source = source;
            this.tracks = tracks;
        }

        /**
         * Sets the role; see {@link Rendition#kind()}.
         *
         * @param kind the value
         * @return this builder
         */
        public Builder kind(Kind kind) {
            this.kind = Objects.requireNonNull(kind, "kind");
            return this;
        }

        /**
         * Sets the name; see {@link Rendition#name()}.
         *
         * @param name the value, 1 to 64 letters, digits, underscores or hyphens
         * @return this builder
         * @throws IllegalArgumentException if the name holds other characters
         */
        public Builder name(String name) {
            Objects.requireNonNull(name, "name");
            if (!NAME.matcher(name).matches()) {
                throw new IllegalArgumentException("rendition name \"" + name
                        + "\" is not 1 to 64 letters, digits, underscores or hyphens");
            }
            this.name = name;
            return this;
        }

        /**
         * Names the rendition by its position, the default; see {@link Rendition#name()}.
         *
         * @return this builder
         */
        public Builder noName() {
            this.name = null;
            return this;
        }

        /**
         * Sets the track an audio or subtitle rendition packages; see {@link Rendition#trackId()}.
         *
         * @param id the value, a track identifier of the input or of the declared tracks
         * @return this builder
         * @throws IllegalArgumentException if {@code id} is negative
         */
        public Builder trackId(int id) {
            if (id < 0) {
                throw new IllegalArgumentException("track identifier " + id);
            }
            this.trackId = id;
            return this;
        }

        /**
         * Packages the first track of the rendition's kind, the default; see {@link Rendition#trackId()}.
         *
         * @return this builder
         */
        public Builder automaticTrack() {
            this.trackId = -1;
            return this;
        }

        /**
         * Sets the language; see {@link Rendition#language()}.
         *
         * @param language the value, letters, digits and hyphens
         * @return this builder
         */
        public Builder language(String language) {
            this.language = Objects.requireNonNull(language, "language");
            return this;
        }

        /**
         * Declares the track's own language, the default; see {@link Rendition#language()}.
         *
         * @return this builder
         */
        public Builder trackLanguage() {
            this.language = null;
            return this;
        }

        /**
         * Sets the label; see {@link Rendition#label()}.
         *
         * @param label the value, not empty, without double quotes or line breaks
         * @return this builder
         */
        public Builder label(String label) {
            this.label = Objects.requireNonNull(label, "label");
            return this;
        }

        /**
         * Labels the rendition with its name, the default; see {@link Rendition#label()}.
         *
         * @return this builder
         */
        public Builder noLabel() {
            this.label = null;
            return this;
        }

        /**
         * Sets whether the rendition is the default of its group; see {@link Rendition#isDefault()}.
         *
         * @param enabled the value
         * @return this builder
         */
        public Builder defaultRendition(boolean enabled) {
            this.defaultRendition = enabled;
            return this;
        }

        /**
         * Sets whether a subtitle rendition holds forced subtitles; see {@link Rendition#isForced()}.
         *
         * @param enabled the value
         * @return this builder
         */
        public Builder forced(boolean enabled) {
            this.forced = enabled;
            return this;
        }

        /**
         * Builds the rendition.
         *
         * @return the rendition
         * @throws IllegalArgumentException if a variant names a track, or is marked default or forced, a forced
         * rendition is not a subtitle rendition, the language is not letters,
         * digits and hyphens, or the label is empty or holds a double quote or a line break
         */
        public Rendition build() {
            if (kind == Kind.VARIANT && (trackId >= 0 || defaultRendition || forced)) {
                throw new IllegalArgumentException("a variant takes no track identifier, default or forced flag");
            }
            if (forced && kind != Kind.SUBTITLES) {
                throw new IllegalArgumentException("only a subtitle rendition can be forced");
            }
            if (language != null && !language.matches("[A-Za-z0-9-]{1,35}")) {
                throw new IllegalArgumentException("language \"" + language + "\" is not a language tag");
            }
            if (label != null && (label.isEmpty() || label.indexOf('"') >= 0 || label.indexOf('\n') >= 0
                    || label.indexOf('\r') >= 0)) {
                throw new IllegalArgumentException("label \"" + label + "\" is empty or holds a quote or a line break");
            }
            return new Rendition(this);
        }
    }
}
