package com.github.auties00.smalt.metadata;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Names the tags every audio and video container can describe, under one vocabulary shared by
 * {@link MediaTags}, {@link TagEditor} and {@code Media.probe}.
 *
 * <p>Each constant has a {@linkplain #key() normalized key}, the lower-case name ffprobe prints
 * ({@code title}, {@code album_artist}, ...), and the native name every tag format stores it
 * under: an ID3v2 frame, a Vorbis comment field, an MP4 {@code ilst} atom, a Matroska
 * {@code SimpleTag} name and a RIFF {@code INFO} identifier. {@link #fromName(String)} recognizes
 * the normalized key and the common native spellings of every format, in any case, so that
 * {@code TIT2}, {@code TITLE}, {@code INAM} and {@code title} all name {@link #TITLE}.
 *
 * <p>Values are text. {@link #TRACK} and {@link #DISC} hold a number optionally followed by
 * {@code /} and the total ({@code "3/12"}); {@link #DATE} holds an ISO 8601 date or year
 * ({@code "2024-05-01"}, {@code "2024"}); {@link #COMPILATION} holds {@code "1"} or {@code "0"}.
 *
 * @apiNote New constants may be added in minor releases, so a {@code switch} over this enum needs a default branch.
 */
public enum StandardTag {
    /**
     * The title of the work or track.
     */
    TITLE("title", "TIT2", "TITLE", "\u00A9nam", "TITLE", "INAM", "TIT2", "INAM", "NAME"),

    /**
     * The performing artist.
     */
    ARTIST("artist", "TPE1", "ARTIST", "\u00A9ART", "ARTIST", "IART", "TPE1", "IART", "AUTH", "PERFORMER"),

    /**
     * The album, movie or show the track belongs to.
     */
    ALBUM("album", "TALB", "ALBUM", "\u00A9alb", "ALBUM", "IPRD", "TALB", "IPRD"),

    /**
     * The artist credited for the whole album.
     */
    ALBUM_ARTIST("album_artist", "TPE2", "ALBUMARTIST", "aART", "ALBUM_ARTIST", null, "TPE2", "ALBUMARTIST",
            "ALBUM_ARTIST", "ALBUM ARTIST"),

    /**
     * The composer.
     */
    COMPOSER("composer", "TCOM", "COMPOSER", "\u00A9wrt", "COMPOSER", null, "TCOM"),

    /**
     * The genre, as free text.
     */
    GENRE("genre", "TCON", "GENRE", "\u00A9gen", "GENRE", "IGNR", "TCON", "IGNR"),

    /**
     * The release or recording date, as an ISO 8601 date or a year.
     */
    DATE("date", "TDRC", "DATE", "\u00A9day", "DATE_RELEASED", "ICRD", "TDRC", "TYER", "ICRD", "YEAR",
            "DATE_RELEASED"),

    /**
     * The track number, optionally followed by {@code /} and the number of tracks.
     */
    TRACK("track", "TRCK", "TRACKNUMBER", "trkn", "PART_NUMBER", "ITRK", "TRCK", "ITRK", "IPRT", "TRACKNUMBER",
            "PART_NUMBER"),

    /**
     * The disc number, optionally followed by {@code /} and the number of discs.
     */
    DISC("disc", "TPOS", "DISCNUMBER", "disk", "DISC", null, "TPOS", "DISCNUMBER"),

    /**
     * A free-text comment or description.
     */
    COMMENT("comment", "COMM", "COMMENT", "\u00A9cmt", "COMMENT", "ICMT", "COMM", "ICMT", "ANNO", "DESCRIPTION"),

    /**
     * The unsynchronized lyrics.
     */
    LYRICS("lyrics", "USLT", "LYRICS", "\u00A9lyr", "LYRICS", null, "USLT"),

    /**
     * The copyright notice.
     */
    COPYRIGHT("copyright", "TCOP", "COPYRIGHT", "cprt", "COPYRIGHT", "ICOP", "TCOP", "ICOP", "(C) "),

    /**
     * The software that encoded the file.
     */
    ENCODER("encoder", "TSSE", "ENCODER", "\u00A9too", "ENCODER", "ISFT", "TSSE", "ISFT"),

    /**
     * The person or organization that encoded the file.
     */
    ENCODED_BY("encoded_by", "TENC", "ENCODED_BY", "\u00A9enc", "ENCODED_BY", null, "TENC", "ENCODEDBY",
            "ENCODED-BY"),

    /**
     * The publisher or record label.
     */
    PUBLISHER("publisher", "TPUB", "PUBLISHER", null, "PUBLISHER", null, "TPUB", "LABEL"),

    /**
     * The language of the content, preferably an ISO 639-2 code.
     */
    LANGUAGE("language", "TLAN", "LANGUAGE", null, "LANGUAGE", "ILNG", "TLAN", "ILNG"),

    /**
     * The content group the work belongs to.
     */
    GROUPING("grouping", "TIT1", "GROUPING", "\u00A9grp", "GROUPING", null, "TIT1"),

    /**
     * The time the file was created, as an ISO 8601 date and time.
     */
    CREATION_TIME("creation_time", null, "CREATION_TIME", null, "CREATION_TIME", null),

    /**
     * Whether the album is a compilation of several artists, {@code "1"} or {@code "0"}.
     */
    COMPILATION("compilation", "TCMP", "COMPILATION", "cpil", "COMPILATION", null, "TCMP");

    /**
     * Every constant, keyed by every recognized spelling in upper case.
     */
    private static final Map<String, StandardTag> BY_NAME = byName();

    /**
     * The normalized key.
     */
    private final String key;

    /**
     * The ID3v2 frame identifier, or {@code null}.
     */
    private final String id3Frame;

    /**
     * The Vorbis comment field name, or {@code null}.
     */
    private final String vorbisField;

    /**
     * The MP4 {@code ilst} atom, or {@code null}.
     */
    private final String mp4Atom;

    /**
     * The Matroska {@code SimpleTag} name, or {@code null}.
     */
    private final String matroskaName;

    /**
     * The RIFF {@code INFO} identifier, or {@code null}.
     */
    private final String riffInfo;

    /**
     * The additional spellings recognized when reading.
     */
    private final String[] aliases;

    /**
     * Constructs a constant.
     *
     * @param key the normalized key
     * @param id3Frame the ID3v2 frame identifier, or {@code null}
     * @param vorbisField the Vorbis comment field name, or {@code null}
     * @param mp4Atom the MP4 {@code ilst} atom, or {@code null}
     * @param matroskaName the Matroska {@code SimpleTag} name, or {@code null}
     * @param riffInfo the RIFF {@code INFO} identifier, or {@code null}
     * @param aliases the additional spellings recognized when reading
     */
    StandardTag(String key, String id3Frame, String vorbisField, String mp4Atom, String matroskaName, String riffInfo,
                String... aliases) {
        this.key = key;
        this.id3Frame = id3Frame;
        this.vorbisField = vorbisField;
        this.mp4Atom = mp4Atom;
        this.matroskaName = matroskaName;
        this.riffInfo = riffInfo;
        this.aliases = aliases;
    }

    /**
     * Builds the spelling index.
     *
     * @return the constants keyed by upper-case spelling
     */
    private static Map<String, StandardTag> byName() {
        var result = new HashMap<String, StandardTag>();
        for (var tag : values()) {
            result.putIfAbsent(tag.key.toUpperCase(Locale.ROOT), tag);
            for (var native_ : new String[]{tag.id3Frame, tag.vorbisField, tag.mp4Atom, tag.matroskaName, tag.riffInfo}) {
                if (native_ != null) {
                    result.putIfAbsent(native_.toUpperCase(Locale.ROOT), tag);
                }
            }
            for (var alias : tag.aliases) {
                result.putIfAbsent(alias.toUpperCase(Locale.ROOT), tag);
            }
        }
        return Map.copyOf(result);
    }

    /**
     * Returns the normalized key, the lower-case name ffprobe uses.
     *
     * @return the key, such as {@code "album_artist"}
     */
    public String key() {
        return key;
    }

    /**
     * Returns the ID3v2 frame that stores this tag.
     *
     * @return the four-character frame identifier, such as {@code "TIT2"}, or empty when ID3v2 has no frame for it and
     * it is written as a {@code TXXX} frame
     */
    public Optional<String> id3Frame() {
        return Optional.ofNullable(id3Frame);
    }

    /**
     * Returns the Vorbis comment field that stores this tag.
     *
     * @return the field name, such as {@code "ALBUMARTIST"}
     */
    public String vorbisField() {
        return vorbisField;
    }

    /**
     * Returns the MP4 {@code ilst} atom that stores this tag.
     *
     * @return the four-character atom, such as {@code "\u00A9nam"}, or empty when the tag is written as a freeform
     * {@code ----} atom
     */
    public Optional<String> mp4Atom() {
        return Optional.ofNullable(mp4Atom);
    }

    /**
     * Returns the Matroska {@code SimpleTag} name that stores this tag.
     *
     * @return the name, such as {@code "ALBUM_ARTIST"}
     */
    public String matroskaName() {
        return matroskaName;
    }

    /**
     * Returns the RIFF {@code INFO} identifier that stores this tag in WAV files.
     *
     * @return the four-character identifier, such as {@code "INAM"}, or empty when {@code INFO} has none
     */
    public Optional<String> riffInfo() {
        return Optional.ofNullable(riffInfo);
    }

    /**
     * Returns the constant a tag name denotes.
     *
     * <p>The name is compared in upper case against the normalized key and the native spellings
     * of every format, so {@code "title"}, {@code "TIT2"}, {@code "Title"} and {@code "INAM"} all
     * return {@link #TITLE}.
     *
     * @param name a normalized key or a native field name of any format, compared ignoring case
     * @return the constant, or empty when the name is not part of the vocabulary
     */
    public static Optional<StandardTag> fromName(String name) {
        Objects.requireNonNull(name, "name");
        return Optional.ofNullable(BY_NAME.get(name.toUpperCase(Locale.ROOT)));
    }

    /**
     * Normalizes a tag name: the key of the constant it denotes, or the name unchanged.
     *
     * @param name the tag name to normalize
     * @return the normalized key, or {@code name} when it is not part of the vocabulary
     */
    public static String normalize(String name) {
        var tag = BY_NAME.get(name.toUpperCase(Locale.ROOT));
        return tag != null ? tag.key : name;
    }
}
