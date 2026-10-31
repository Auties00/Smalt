package com.github.auties00.smalt.metadata;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Names the tags every audio and video container can describe, under one vocabulary shared by
 * {@link MediaTags}, {@link TagEditor} and {@code Media.probe}.
 *
 * <p>Each constant has a {@linkplain #key() normalized key}, a lower-case name with words separated by
 * {@code _} ({@code title}, {@code album_artist}, {@code replaygain_track_gain}, ...), and the native name every
 * tag format stores it under: an ID3v2 frame (or the description of a {@code TXXX} user text frame), a Vorbis
 * comment field, an MP4 {@code ilst} atom (or the name of a free-form {@code ----} item of mean
 * {@code com.apple.iTunes}), a Matroska {@code SimpleTag} name, a RIFF {@code INFO} identifier and an ASF attribute
 * name.
 * {@link #fromName(String)} recognizes the normalized key and the common native spellings of every format, in any
 * case, so that {@code TIT2}, {@code TITLE}, {@code INAM} and {@code title} all name {@link #TITLE}.
 *
 * <p>Values are text:
 * <ul>
 *   <li>{@link #TRACK} and {@link #DISC} hold a number optionally followed by {@code /} and the total
 *       ({@code "3/12"});</li>
 *   <li>{@link #DATE} holds an ISO 8601 date or year ({@code "2024-05-01"}, {@code "2024"});</li>
 *   <li>{@link #COMPILATION} holds {@code "1"} or {@code "0"};</li>
 *   <li>{@link #BPM} holds a whole number of beats per minute (the MP4 {@code tmpo} atom is a 16-bit
 *       integer);</li>
 *   <li>the ReplayGain gains hold decibels ({@code "-6.48 dB"}) and the peaks a linear amplitude
 *       ({@code "0.988553"});</li>
 *   <li>the MusicBrainz identifiers hold the identifier as MusicBrainz prints it (a UUID, or a disc
 *       identifier for {@link #MUSICBRAINZ_DISC_ID});</li>
 *   <li>{@link #RATING} holds a number from 0 to 5, fractions allowed. The ID3v2 {@code POPM} frame stores a
 *       byte from 0 to 255 instead: it is read as five times the byte divided by 255, with at most two
 *       decimals, and written as the nearest byte, so a read and a write keep the byte; the other formats
 *       store the text.</li>
 * </ul>
 *
 * <p>Some formats store a tag in a structure of their own:
 * <ul>
 *   <li>ID3v2 stores {@link #MUSICBRAINZ_RECORDING_ID} in a {@code UFID} frame of owner
 *       {@code http://musicbrainz.org} (or, for an identifier that frame cannot hold, more than 64 characters or
 *       others than printable ASCII, in a {@code TXXX} frame described {@code MusicBrainz Track Id}), a
 *       {@link #RATING} from 0 to 5 in the first {@code POPM} frame (whose e-mail address and play counter a
 *       rewrite keeps; another rating in a {@code TXXX} frame), and every tag without a frame of its own in a
 *       {@code TXXX} frame;</li>
 *   <li>Matroska stores the sort names as a {@code SORT_WITH} tag nested in the tag they sort
 *       ({@link #matroskaName()} names them {@code "TITLE/SORT_WITH"}, the parent and the child separated by
 *       {@code /}), the ReplayGain values of the album as {@code REPLAYGAIN_GAIN} and {@code REPLAYGAIN_PEAK} in
 *       the album tag (target type value 50) and those of the track under the same names in a tag of target
 *       type value 30.</li>
 * </ul>
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
     * The conductor of the performance.
     */
    CONDUCTOR("conductor", "TPE3", "CONDUCTOR", "----:CONDUCTOR", "CONDUCTOR", null),

    /**
     * The author of the lyrics.
     */
    LYRICIST("lyricist", "TEXT", "LYRICIST", "----:LYRICIST", "LYRICIST", null),

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
            "PART_NUMBER", "TRACK NUMBER"),

    /**
     * The disc number, optionally followed by {@code /} and the number of discs.
     */
    DISC("disc", "TPOS", "DISCNUMBER", "disk", "DISC", null, "TPOS", "DISCNUMBER"),

    /**
     * A free-text comment.
     */
    COMMENT("comment", "COMM", "COMMENT", "\u00A9cmt", "COMMENT", "ICMT", "COMM", "ICMT", "ANNO", "COMMENTS"),

    /**
     * A short description of the content.
     */
    DESCRIPTION("description", "TXXX:DESCRIPTION", "DESCRIPTION", "desc", "DESCRIPTION", null),

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
    ENCODER("encoder", "TSSE", "ENCODER", "\u00A9too", "ENCODER", "ISFT", "TSSE", "ISFT", "ENCODING APPLICATION"),

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
    COMPILATION("compilation", "TCMP", "COMPILATION", "cpil", "COMPILATION", null, "TCMP"),

    /**
     * The tempo, in beats per minute.
     */
    BPM("bpm", "TBPM", "BPM", "tmpo", "BPM", null, "TEMPO"),

    /**
     * The International Standard Recording Code of the recording (ISO 3901).
     */
    ISRC("isrc", "TSRC", "ISRC", "----:ISRC", "ISRC", null),

    /**
     * The title as it sorts, such as {@code "Wall, The"}.
     */
    TITLE_SORT("title_sort", "TSOT", "TITLESORT", "sonm", "TITLE/SORT_WITH", null, "TITLE-SORT", "SORT_NAME"),

    /**
     * The artist as it sorts.
     */
    ARTIST_SORT("artist_sort", "TSOP", "ARTISTSORT", "soar", "ARTIST/SORT_WITH", null, "ARTIST-SORT", "SORT_ARTIST"),

    /**
     * The album as it sorts.
     */
    ALBUM_SORT("album_sort", "TSOA", "ALBUMSORT", "soal", "ALBUM/SORT_WITH", null, "ALBUM-SORT", "SORT_ALBUM"),

    /**
     * The album artist as it sorts.
     */
    ALBUM_ARTIST_SORT("album_artist_sort", "TSO2", "ALBUMARTISTSORT", "soaa", "ALBUM_ARTIST/SORT_WITH", null,
            "ALBUM_ARTIST-SORT", "SORT_ALBUM_ARTIST"),

    /**
     * The composer as it sorts.
     */
    COMPOSER_SORT("composer_sort", "TSOC", "COMPOSERSORT", "soco", "COMPOSER/SORT_WITH", null, "COMPOSER-SORT",
            "SORT_COMPOSER"),

    /**
     * The ReplayGain gain of the track, in decibels.
     */
    REPLAYGAIN_TRACK_GAIN("replaygain_track_gain", "TXXX:REPLAYGAIN_TRACK_GAIN", "REPLAYGAIN_TRACK_GAIN",
            "----:REPLAYGAIN_TRACK_GAIN", "REPLAYGAIN_GAIN", null),

    /**
     * The ReplayGain peak amplitude of the track.
     */
    REPLAYGAIN_TRACK_PEAK("replaygain_track_peak", "TXXX:REPLAYGAIN_TRACK_PEAK", "REPLAYGAIN_TRACK_PEAK",
            "----:REPLAYGAIN_TRACK_PEAK", "REPLAYGAIN_PEAK", null),

    /**
     * The ReplayGain gain of the album, in decibels.
     */
    REPLAYGAIN_ALBUM_GAIN("replaygain_album_gain", "TXXX:REPLAYGAIN_ALBUM_GAIN", "REPLAYGAIN_ALBUM_GAIN",
            "----:REPLAYGAIN_ALBUM_GAIN", "REPLAYGAIN_GAIN", null),

    /**
     * The ReplayGain peak amplitude of the album.
     */
    REPLAYGAIN_ALBUM_PEAK("replaygain_album_peak", "TXXX:REPLAYGAIN_ALBUM_PEAK", "REPLAYGAIN_ALBUM_PEAK",
            "----:REPLAYGAIN_ALBUM_PEAK", "REPLAYGAIN_PEAK", null),

    /**
     * The MusicBrainz identifier of the recording.
     */
    MUSICBRAINZ_RECORDING_ID("musicbrainz_recording_id", "UFID", "MUSICBRAINZ_TRACKID",
            "----:MusicBrainz Track Id", "MUSICBRAINZ_TRACKID", null, "MusicBrainz Track Id"),

    /**
     * The MusicBrainz identifier of the track on its release.
     */
    MUSICBRAINZ_RELEASE_TRACK_ID("musicbrainz_release_track_id", "TXXX:MusicBrainz Release Track Id",
            "MUSICBRAINZ_RELEASETRACKID", "----:MusicBrainz Release Track Id", "MUSICBRAINZ_RELEASETRACKID", null),

    /**
     * The MusicBrainz identifier of the release (album).
     */
    MUSICBRAINZ_ALBUM_ID("musicbrainz_album_id", "TXXX:MusicBrainz Album Id", "MUSICBRAINZ_ALBUMID",
            "----:MusicBrainz Album Id", "MUSICBRAINZ_ALBUMID", null),

    /**
     * The MusicBrainz identifier of the artist.
     */
    MUSICBRAINZ_ARTIST_ID("musicbrainz_artist_id", "TXXX:MusicBrainz Artist Id", "MUSICBRAINZ_ARTISTID",
            "----:MusicBrainz Artist Id", "MUSICBRAINZ_ARTISTID", null),

    /**
     * The MusicBrainz identifier of the album artist.
     */
    MUSICBRAINZ_ALBUM_ARTIST_ID("musicbrainz_album_artist_id", "TXXX:MusicBrainz Album Artist Id",
            "MUSICBRAINZ_ALBUMARTISTID", "----:MusicBrainz Album Artist Id", "MUSICBRAINZ_ALBUMARTISTID", null),

    /**
     * The MusicBrainz identifier of the release group.
     */
    MUSICBRAINZ_RELEASE_GROUP_ID("musicbrainz_release_group_id", "TXXX:MusicBrainz Release Group Id",
            "MUSICBRAINZ_RELEASEGROUPID", "----:MusicBrainz Release Group Id", "MUSICBRAINZ_RELEASEGROUPID", null),

    /**
     * The MusicBrainz identifier of the work.
     */
    MUSICBRAINZ_WORK_ID("musicbrainz_work_id", "TXXX:MusicBrainz Work Id", "MUSICBRAINZ_WORKID",
            "----:MusicBrainz Work Id", "MUSICBRAINZ_WORKID", null),

    /**
     * The MusicBrainz disc identifier of the medium.
     */
    MUSICBRAINZ_DISC_ID("musicbrainz_disc_id", "TXXX:MusicBrainz Disc Id", "MUSICBRAINZ_DISCID",
            "----:MusicBrainz Disc Id", "MUSICBRAINZ_DISCID", null),

    /**
     * The rating, a number from 0 to 5.
     */
    RATING("rating", "POPM", "RATING", "----:RATING", "RATING", null);

    /**
     * The prefix of a native ID3v2 name that is a {@code TXXX} description.
     */
    private static final String USER_TEXT = "TXXX:";

    /**
     * The prefix of a native MP4 name that is a free-form item name.
     */
    private static final String FREEFORM = "----:";

    /**
     * The native names that several constants share, which {@link #fromName(String)} does not recognize.
     */
    private static final Set<String> SHARED = Set.of("REPLAYGAIN_GAIN", "REPLAYGAIN_PEAK");

    /**
     * The ASF attribute names of the constants that have one.
     */
    private static final Map<StandardTag, String> ASF_NAMES = asfNames();

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
     * The ID3v2 {@code TXXX} description.
     */
    private final String id3Description;

    /**
     * The Vorbis comment field name.
     */
    private final String vorbisField;

    /**
     * The MP4 {@code ilst} atom, or {@code null}.
     */
    private final String mp4Atom;

    /**
     * The MP4 free-form item name.
     */
    private final String mp4FreeformName;

    /**
     * The Matroska {@code SimpleTag} name.
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
     * @param id3 the ID3v2 frame identifier, {@code "TXXX:"} followed by a description, or {@code null} for a
     * {@code TXXX} frame described by the key
     * @param vorbisField the Vorbis comment field name
     * @param mp4 the MP4 {@code ilst} atom, {@code "----:"} followed by a free-form name, or {@code null} for a
     * free-form item named by the Vorbis field
     * @param matroskaName the Matroska {@code SimpleTag} name
     * @param riffInfo the RIFF {@code INFO} identifier, or {@code null}
     * @param aliases the additional spellings recognized when reading
     */
    StandardTag(String key, String id3, String vorbisField, String mp4, String matroskaName, String riffInfo,
                String... aliases) {
        this.key = key;
        if (id3 != null && id3.startsWith(USER_TEXT)) {
            this.id3Frame = null;
            this.id3Description = id3.substring(USER_TEXT.length());
        } else {
            this.id3Frame = id3;
            this.id3Description = key;
        }
        this.vorbisField = vorbisField;
        if (mp4 != null && mp4.startsWith(FREEFORM)) {
            this.mp4Atom = null;
            this.mp4FreeformName = mp4.substring(FREEFORM.length());
        } else {
            this.mp4Atom = mp4;
            this.mp4FreeformName = vorbisField;
        }
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
            var natives = new String[]{tag.id3Frame, tag.id3Description, tag.vorbisField, tag.mp4Atom,
                    tag.mp4FreeformName, tag.matroskaName, tag.riffInfo};
            for (var native_ : natives) {
                if (native_ != null && !SHARED.contains(native_)) {
                    result.putIfAbsent(native_.toUpperCase(Locale.ROOT), tag);
                }
            }
            for (var alias : tag.aliases) {
                result.putIfAbsent(alias.toUpperCase(Locale.ROOT), tag);
            }
        }
        // the ASF names come last: "Title", "Copyright", "Description" and "Rating" already name other constants
        for (var entry : ASF_NAMES.entrySet()) {
            result.putIfAbsent(entry.getValue().toUpperCase(Locale.ROOT), entry.getKey());
        }
        result.putIfAbsent("WM/TOOLNAME", ENCODER);
        return Map.copyOf(result);
    }

    /**
     * Builds the table of ASF attribute names.
     *
     * @return the names by constant
     */
    private static Map<StandardTag, String> asfNames() {
        var names = new EnumMap<StandardTag, String>(StandardTag.class);
        names.put(TITLE, "Title");
        names.put(ARTIST, "Author");
        names.put(ALBUM, "WM/AlbumTitle");
        names.put(ALBUM_ARTIST, "WM/AlbumArtist");
        names.put(COMPOSER, "WM/Composer");
        names.put(CONDUCTOR, "WM/Conductor");
        names.put(LYRICIST, "WM/Writer");
        names.put(GENRE, "WM/Genre");
        names.put(DATE, "WM/Year");
        names.put(TRACK, "WM/TrackNumber");
        names.put(DISC, "WM/PartOfSet");
        names.put(COMMENT, "Description");
        names.put(DESCRIPTION, "WM/SubTitleDescription");
        names.put(LYRICS, "WM/Lyrics");
        names.put(COPYRIGHT, "Copyright");
        names.put(ENCODER, "WM/EncodingSettings");
        names.put(ENCODED_BY, "WM/EncodedBy");
        names.put(PUBLISHER, "WM/Publisher");
        names.put(LANGUAGE, "WM/Language");
        names.put(GROUPING, "WM/ContentGroupDescription");
        names.put(COMPILATION, "WM/IsCompilation");
        names.put(BPM, "WM/BeatsPerMinute");
        names.put(ISRC, "WM/ISRC");
        names.put(TITLE_SORT, "WM/TitleSortOrder");
        names.put(ARTIST_SORT, "WM/ArtistSortOrder");
        names.put(ALBUM_SORT, "WM/AlbumSortOrder");
        names.put(ALBUM_ARTIST_SORT, "WM/AlbumArtistSortOrder");
        names.put(COMPOSER_SORT, "WM/ComposerSortOrder");
        names.put(MUSICBRAINZ_RECORDING_ID, "MusicBrainz/Track Id");
        names.put(MUSICBRAINZ_RELEASE_TRACK_ID, "MusicBrainz/Release Track Id");
        names.put(MUSICBRAINZ_ALBUM_ID, "MusicBrainz/Album Id");
        names.put(MUSICBRAINZ_ARTIST_ID, "MusicBrainz/Artist Id");
        names.put(MUSICBRAINZ_ALBUM_ARTIST_ID, "MusicBrainz/Album Artist Id");
        names.put(MUSICBRAINZ_RELEASE_GROUP_ID, "MusicBrainz/Release Group Id");
        names.put(MUSICBRAINZ_WORK_ID, "MusicBrainz/Work Id");
        names.put(MUSICBRAINZ_DISC_ID, "MusicBrainz/Disc Id");
        names.put(RATING, "Rating");
        return names;
    }

    /**
     * Returns the normalized key.
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
     * it is written as a {@code TXXX} frame described by {@link #id3Description()}
     */
    public Optional<String> id3Frame() {
        return Optional.ofNullable(id3Frame);
    }

    /**
     * Returns the description of the ID3v2 {@code TXXX} user text frame that stores this tag when
     * {@link #id3Frame()} is empty.
     *
     * @return the description, such as {@code "MusicBrainz Album Id"}; for a tag with a frame of its own, the
     * normalized key
     */
    public String id3Description() {
        return id3Description;
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
     * @return the four-character atom, such as {@code "\u00A9nam"}, or empty when the tag is written as a free-form
     * {@code ----} item named by {@link #mp4FreeformName()}
     */
    public Optional<String> mp4Atom() {
        return Optional.ofNullable(mp4Atom);
    }

    /**
     * Returns the name of the MP4 free-form {@code ----} item, of mean {@code com.apple.iTunes}, that stores this tag
     * when {@link #mp4Atom()} is empty.
     *
     * @return the item name, such as {@code "MusicBrainz Album Id"}; for a tag with an atom of its own, the Vorbis
     * field name
     */
    public String mp4FreeformName() {
        return mp4FreeformName;
    }

    /**
     * Returns the Matroska {@code SimpleTag} name that stores this tag.
     *
     * @return the name, such as {@code "ALBUM_ARTIST"}; a tag nested in another is named by both, separated by
     * {@code /} ({@code "TITLE/SORT_WITH"})
     */
    public String matroskaName() {
        return matroskaName;
    }

    /**
     * Returns the RIFF {@code INFO} identifier that stores this tag in WAV and AVI files.
     *
     * @return the four-character identifier, such as {@code "INAM"}, or empty when {@code INFO} has none
     */
    public Optional<String> riffInfo() {
        return Optional.ofNullable(riffInfo);
    }

    /**
     * Returns the ASF attribute name that stores this tag in WMA, WMV and ASF files: a field of the Content
     * Description Object ({@code Title}, {@code Author}, {@code Copyright}, {@code Description} for
     * {@link #COMMENT}, {@code Rating}) or the name of a descriptor or metadata record ({@code WM/AlbumTitle},
     * {@code MusicBrainz/Album Id}, ...).
     *
     * @return the attribute name; for a tag ASF names no attribute for, the normalized key
     */
    public String asfName() {
        return ASF_NAMES.getOrDefault(this, key);
    }

    /**
     * Returns the constant a tag name denotes.
     *
     * <p>The name is compared in upper case against the normalized key and the native spellings
     * of every format, so {@code "title"}, {@code "TIT2"}, {@code "Title"} and {@code "INAM"} all
     * return {@link #TITLE}. The Matroska names {@code REPLAYGAIN_GAIN} and {@code REPLAYGAIN_PEAK}, whose meaning
     * depends on the level of the tag holding them, denote no constant.
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
