package com.github.auties00.smalt.internal.tags;

import com.github.auties00.smalt.metadata.MediaTags;
import com.github.auties00.smalt.metadata.StandardTag;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads and writes the 128-byte ID3v1 and ID3v1.1 tag found at the end of MP3 files.
 *
 * <p>The tag is {@code "TAG"}, a 30-byte title, artist and album, a 4-byte year, a 30-byte
 * comment whose last two bytes hold a zero and the track number in version 1.1, and a genre
 * code. Text is ISO-8859-1, padded with NUL or space characters.
 */
public final class Id3v1Tag {
    /**
     * The size of the tag, in bytes.
     */
    public static final int SIZE = 128;

    /**
     * The size of the enhanced {@code TAG+} block that may precede the tag, in bytes.
     */
    public static final int ENHANCED_SIZE = 227;
    private Id3v1Tag() {
        throw new AssertionError();
    }

    /**
     * Returns whether bytes hold an ID3v1 tag.
     *
     * @param data the bytes
     * @param offset the index of the candidate's first byte
     * @return {@code true} when {@code "TAG"} starts there and 128 bytes are available
     */
    public static boolean isTag(byte[] data, int offset) {
        return offset >= 0 && offset + SIZE <= data.length && TagIo.matches(data, offset, "TAG");
    }

    /**
     * Decodes a tag.
     *
     * @param data the bytes
     * @param offset the index of the tag's first byte
     * @return the non-empty fields as entries
     */
    public static List<MediaTags.Entry> parse(byte[] data, int offset) {
        var result = new ArrayList<MediaTags.Entry>();
        add(result, StandardTag.TITLE, text(data, offset + 3, 30));
        add(result, StandardTag.ARTIST, text(data, offset + 33, 30));
        add(result, StandardTag.ALBUM, text(data, offset + 63, 30));
        add(result, StandardTag.DATE, text(data, offset + 93, 4));
        if (data[offset + 125] == 0 && data[offset + 126] != 0) {
            add(result, StandardTag.COMMENT, text(data, offset + 97, 28));
            add(result, StandardTag.TRACK, Integer.toString(data[offset + 126] & 0xFF));
        } else {
            add(result, StandardTag.COMMENT, text(data, offset + 97, 30));
        }
        var genre = Id3Genres.name(data[offset + 127] & 0xFF);
        if (genre != null) {
            add(result, StandardTag.GENRE, genre);
        }
        return result;
    }

    /**
     * Decodes a tag into the fields a demuxer reports, keyed by the ID3v2.3 frame identifier of
     * each field: {@code TIT2}, {@code TPE1}, {@code TALB}, {@code TYER}, {@code COMM},
     * {@code TRCK} and {@code TCON}.
     *
     * @param data the bytes
     * @param offset the index of the tag's first byte
     * @return the non-empty fields in tag order
     */
    public static Map<String, String> fields(byte[] data, int offset) {
        var fields = new LinkedHashMap<String, String>();
        for (var entry : parse(data, offset)) {
            var key = switch (StandardTag.fromName(entry.key()).orElseThrow()) {
                case TITLE -> "TIT2";
                case ARTIST -> "TPE1";
                case ALBUM -> "TALB";
                case DATE -> "TYER";
                case COMMENT -> "COMM";
                case TRACK -> "TRCK";
                default -> "TCON";
            };
            fields.put(key, entry.value());
        }
        return fields;
    }

    /**
     * Encodes a version 1.1 tag from the first values of the standard tags.
     *
     * @param tags the tags to encode
     * @return the 128 bytes of the tag
     */
    public static byte[] encode(MediaTags tags) {
        var data = new byte[SIZE];
        data[0] = 'T';
        data[1] = 'A';
        data[2] = 'G';
        put(data, 3, 30, tags.get(StandardTag.TITLE).orElse(""));
        put(data, 33, 30, tags.get(StandardTag.ARTIST).orElse(""));
        put(data, 63, 30, tags.get(StandardTag.ALBUM).orElse(""));
        put(data, 93, 4, tags.get(StandardTag.DATE).orElse(""));
        var track = tags.trackNumber();
        if (track.isPresent() && track.getAsInt() > 0 && track.getAsInt() < 256) {
            put(data, 97, 28, tags.get(StandardTag.COMMENT).orElse(""));
            data[126] = (byte) track.getAsInt();
        } else {
            put(data, 97, 30, tags.get(StandardTag.COMMENT).orElse(""));
        }
        var genre = tags.get(StandardTag.GENRE).map(Id3Genres::code).orElse(-1);
        data[127] = (byte) (genre >= 0 ? genre : 255);
        return data;
    }

    /**
     * Returns whether a set of tags has a value an ID3v1 tag can hold.
     *
     * @param tags the tags to test
     * @return {@code true} when a title, artist, album, date, comment, track or genre is present
     */
    public static boolean hasFields(MediaTags tags) {
        for (var tag : new StandardTag[]{StandardTag.TITLE, StandardTag.ARTIST, StandardTag.ALBUM, StandardTag.DATE,
                StandardTag.COMMENT, StandardTag.TRACK, StandardTag.GENRE}) {
            if (tags.get(tag).isPresent()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Adds a non-blank field.
     *
     * @param result the target
     * @param tag the standard tag the field stores
     * @param value the field text, trimmed; a blank value is ignored
     */
    private static void add(List<MediaTags.Entry> result, StandardTag tag, String value) {
        if (!value.isBlank()) {
            result.add(new MediaTags.Entry(tag.key(), value));
        }
    }

    /**
     * Decodes a fixed-size field.
     *
     * @param data the bytes
     * @param offset the index of the field
     * @param length the size of the field
     * @return the text up to the first NUL, without trailing spaces
     */
    private static String text(byte[] data, int offset, int length) {
        var end = offset;
        while (end < offset + length && data[end] != 0) {
            end++;
        }
        return new String(data, offset, end - offset, StandardCharsets.ISO_8859_1).strip();
    }

    /**
     * Encodes a fixed-size field, truncating it and replacing characters ISO-8859-1 lacks.
     *
     * @param data the destination
     * @param offset the index of the field
     * @param length the size of the field
     * @param value the text
     */
    private static void put(byte[] data, int offset, int length, String value) {
        var bytes = value.getBytes(StandardCharsets.ISO_8859_1);
        System.arraycopy(bytes, 0, data, offset, Math.min(length, bytes.length));
    }
}
