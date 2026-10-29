package com.github.auties00.smalt.internal.tags;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.container.AttachedPicture;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.internal.io.ByteArrays;
import com.github.auties00.smalt.internal.io.ByteWriter;
import com.github.auties00.smalt.metadata.MediaTags;
import com.github.auties00.smalt.metadata.StandardTag;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Reads and writes APEv1 and APEv2 tags, the item lists that end MP3, Monkey's Audio, WavPack and
 * Musepack files.
 *
 * <p>A tag is an optional 32-byte header, the items, and a 32-byte footer. Header and footer both
 * hold the preamble {@code "APETAGEX"}, the version (1000 or 2000), the size of the items and the
 * footer, the item count and the tag flags, all little-endian. An item is the size of its value,
 * its flags (the read-only bit and, in version 2.0, a two-bit type: UTF-8 text, binary, external
 * locator or reserved), an ASCII key of 2 to 255 characters ended by NUL, and the value. A text
 * item holds several values separated by NUL. Version 1.0 has no header and no item types: every
 * item is text.
 *
 * <p>Text items become {@link MediaTags} entries; binary {@code "Cover Art (...)"} items, whose
 * value is a file name, NUL and the image (or the image alone, recognized by its signature),
 * become pictures whose description is the file name without the extension of the image format,
 * and empty when that name is the item key; every other item (binary data,
 * external locators, keys {@link MediaTags.Entry} rejects) is kept as an opaque {@link Item} that
 * a rewrite can carry over. Writing always produces a version 2.0 tag with header and footer.
 * Every size read from the tag is checked against the bytes that hold it, so hostile tags raise
 * {@link MediaException} only.
 */
public final class ApeTag {
    /**
     * The size of the header and of the footer, in bytes.
     */
    public static final int FOOTER_SIZE = 32;

    /**
     * The version number of APEv1 tags.
     */
    public static final int VERSION_1 = 1000;

    /**
     * The version number of APEv2 tags.
     */
    public static final int VERSION_2 = 2000;

    /**
     * The item type of UTF-8 text.
     */
    public static final int TEXT = 0;

    /**
     * The item type of binary data.
     */
    public static final int BINARY = 1;

    /**
     * The item type of an external locator, a UTF-8 link to the value.
     */
    public static final int LOCATOR = 2;

    /**
     * The tag flag set in the header and the footer of a tag that has a header.
     */
    private static final long HAS_HEADER = 0x80000000L;

    /**
     * The tag flag set in the header.
     */
    private static final long IS_HEADER = 0x20000000L;

    /**
     * The read-only bit of the tag and item flags.
     */
    private static final int READ_ONLY = 1;

    /**
     * The largest number of items read.
     */
    private static final int MAX_ITEMS = 1 << 16;

    /**
     * The largest key length, in characters.
     */
    private static final int MAX_KEY = 255;

    /**
     * The keys the specification reserves for the identification of other tag formats.
     */
    private static final Set<String> FORBIDDEN_KEYS = Set.of("ID3", "TAG", "OGGS", "MP+");

    /**
     * The key prefix of cover art items.
     */
    private static final String COVER_PREFIX = "Cover Art (";

    /**
     * The cover art key names, by picture type code.
     */
    private static final List<String> COVER_NAMES = List.of("Other", "Png Icon", "Icon", "Front", "Back", "Leaflet",
            "Media", "Lead Artist", "Artist", "Conductor", "Band", "Composer", "Lyricist", "Recording Location",
            "During Recording", "During Performance", "Video Capture", "Fish", "Illustration", "Band Logotype",
            "Publisher Logotype");

    /**
     * The item keys written for the standard tags.
     */
    private static final Map<StandardTag, String> KEYS = keys();

    /**
     * The tag version.
     */
    private final int version;

    /**
     * Whether the tag flags mark the tag read-only.
     */
    private final boolean readOnly;

    /**
     * The items, in tag order.
     */
    private final List<Item> items;

    /**
     * Constructs a tag.
     *
     * @param version the version number
     * @param readOnly whether the tag is flagged read-only
     * @param items the items, copied
     */
    private ApeTag(int version, boolean readOnly, List<Item> items) {
        this.version = version;
        this.readOnly = readOnly;
        this.items = List.copyOf(items);
    }

    /**
     * Builds the table of standard item keys.
     *
     * @return the keys by standard tag
     */
    private static Map<StandardTag, String> keys() {
        var keys = new EnumMap<StandardTag, String>(StandardTag.class);
        keys.put(StandardTag.TITLE, "Title");
        keys.put(StandardTag.ARTIST, "Artist");
        keys.put(StandardTag.ALBUM, "Album");
        keys.put(StandardTag.ALBUM_ARTIST, "Album Artist");
        keys.put(StandardTag.COMPOSER, "Composer");
        keys.put(StandardTag.GENRE, "Genre");
        keys.put(StandardTag.DATE, "Year");
        keys.put(StandardTag.TRACK, "Track");
        keys.put(StandardTag.DISC, "Disc");
        keys.put(StandardTag.COMMENT, "Comment");
        keys.put(StandardTag.LYRICS, "Lyrics");
        keys.put(StandardTag.COPYRIGHT, "Copyright");
        keys.put(StandardTag.ENCODER, "Encoder");
        keys.put(StandardTag.ENCODED_BY, "Encoded By");
        keys.put(StandardTag.PUBLISHER, "Publisher");
        keys.put(StandardTag.LANGUAGE, "Language");
        keys.put(StandardTag.GROUPING, "Grouping");
        keys.put(StandardTag.CREATION_TIME, "CREATION_TIME");
        keys.put(StandardTag.COMPILATION, "Compilation");
        keys.put(StandardTag.CONDUCTOR, "Conductor");
        keys.put(StandardTag.LYRICIST, "Lyricist");
        for (var tag : StandardTag.values()) {
            keys.putIfAbsent(tag, tag.vorbisField());
        }
        return keys;
    }

    /**
     * Returns whether bytes hold an APE tag header or footer.
     *
     * @param data the bytes
     * @param offset the index of the candidate's first byte
     * @return {@code true} when the preamble starts there and 32 bytes are available
     */
    public static boolean isFooter(byte[] data, int offset) {
        return offset >= 0 && offset + FOOTER_SIZE <= data.length && TagIo.matches(data, offset, "APETAGEX");
    }

    /**
     * Parses a tag that ends with its footer.
     *
     * <p>The items are read from the footer's declared size; the header, when present, is not
     * needed. Parsing stops at the first item whose sizes run past the items or whose key is not
     * NUL-terminated within 255 characters; the items before it are kept.
     *
     * @param tag the bytes of the tag, the footer last
     * @return the tag
     * @throws MediaException.Malformed if the footer is missing or declares a size larger than {@code tag}
     */
    public static ApeTag parse(byte[] tag) {
        var footer = tag.length - FOOTER_SIZE;
        if (!isFooter(tag, footer)) {
            throw new MediaException.Malformed("APE tag footer missing");
        }
        var version = (int) Math.min(Integer.MAX_VALUE, ByteArrays.u32le(tag, footer + 8));
        var size = ByteArrays.u32le(tag, footer + 12);
        var count = ByteArrays.u32le(tag, footer + 16);
        var flags = ByteArrays.u32le(tag, footer + 20);
        if (size < FOOTER_SIZE || size > tag.length) {
            throw new MediaException.Malformed("APE tag size " + size + " does not fit the " + tag.length + " bytes of the tag");
        }
        var legacy = version < VERSION_2;
        var position = (int) (tag.length - size);
        var parsed = new ArrayList<Item>();
        while (parsed.size() < Math.min(count, MAX_ITEMS) && footer - position >= 9) {
            var valueSize = ByteArrays.u32le(tag, position);
            var itemFlags = (int) ByteArrays.u32le(tag, position + 4);
            var keyStart = position + 8;
            var keyEnd = keyStart;
            var keyLimit = Math.min(footer, keyStart + MAX_KEY + 1);
            while (keyEnd < keyLimit && tag[keyEnd] != 0) {
                keyEnd++;
            }
            if (keyEnd >= keyLimit || keyEnd == keyStart) {
                break;
            }
            var valueStart = keyEnd + 1;
            if (valueSize > footer - valueStart) {
                break;
            }
            var key = new String(tag, keyStart, keyEnd - keyStart, StandardCharsets.ISO_8859_1);
            var value = new byte[(int) valueSize];
            System.arraycopy(tag, valueStart, value, 0, value.length);
            parsed.add(new Item(key, legacy ? 0 : itemFlags, value));
            position = valueStart + value.length;
        }
        return new ApeTag(version, (flags & READ_ONLY) != 0, parsed);
    }

    /**
     * {@return the version number: 1000 for APEv1, 2000 for APEv2}
     */
    public int version() {
        return version;
    }

    /**
     * {@return whether the tag flags mark the tag read-only, a hint this class does not enforce}
     */
    public boolean isReadOnly() {
        return readOnly;
    }

    /**
     * {@return the items in tag order; unmodifiable}
     */
    public List<Item> items() {
        return items;
    }

    /**
     * Decodes the items into tags and pictures.
     *
     * @return the decoded content
     */
    public Content decode() {
        var entries = new ArrayList<MediaTags.Entry>();
        var pictures = new ArrayList<AttachedPicture>();
        var opaque = new ArrayList<Item>();
        for (var item : items) {
            switch (item.type()) {
                case TEXT -> {
                    var values = values(item);
                    var key = mediaKey(item.key());
                    if (values == null || !isEntryKey(key)) {
                        opaque.add(item);
                        continue;
                    }
                    for (var value : values) {
                        if (!value.isBlank()) {
                            entries.add(new MediaTags.Entry(key, value));
                        }
                    }
                }
                case BINARY -> {
                    var picture = picture(item);
                    if (picture != null) {
                        pictures.add(picture);
                    } else {
                        opaque.add(item);
                    }
                }
                default -> opaque.add(item);
            }
        }
        return new Content(entries, pictures, opaque);
    }

    /**
     * Returns the text items as the fields a demuxer reports.
     *
     * <p>The keys of standard tags are their {@link StandardTag#key() normalized keys}, other keys
     * are the item keys; the values of one item are joined with {@code "; "}.
     *
     * @return the fields in tag order
     */
    public Map<String, String> fields() {
        var fields = new LinkedHashMap<String, String>();
        for (var entry : decode().entries()) {
            fields.merge(entry.key(), entry.value(), (previous, next) -> previous + "; " + next);
        }
        return fields;
    }

    /**
     * Encodes a version 2.0 tag with header and footer.
     *
     * <p>Each key of {@code tags} becomes one text item holding its values separated by NUL, under
     * the usual item key for a standard tag ({@code Title}, {@code Album Artist}, {@code Year},
     * {@code Track}, ...) and its own spelling otherwise; a key APE cannot store (not 2 to 255
     * printable ASCII characters, or one of {@code ID3}, {@code TAG}, {@code OggS} and
     * {@code MP+}) is left out. The first picture of each type becomes a binary
     * {@code "Cover Art (...)"} item whose file name is its description, or the item key when it
     * has none, followed by the extension of its format ({@code "front.jpg"},
     * {@code "Cover Art (Front).png"}); later pictures of the same type are left out. The preserved
     * items follow. No key repeats: a text or preserved item whose key a cover art item or an
     * earlier item uses is left out.
     *
     * @param tags the tags to write
     * @param preserved the opaque items to carry over
     * @return the encoded tag, or an empty array when there is no item to write
     * @throws MediaException.LimitExceeded if the tag exceeds the 2 GiB a tag can hold
     */
    public static byte[] encode(MediaTags tags, List<Item> preserved) {
        var out = new ByteWriter(4096);
        var used = new HashSet<String>();
        var count = 0;
        var grouped = new LinkedHashMap<String, List<String>>();
        var spelling = new LinkedHashMap<String, String>();
        var covers = new ArrayList<AttachedPicture>();
        var types = EnumSet.noneOf(AttachedPicture.Type.class);
        for (var picture : tags.pictures()) {
            if (picture.type().code() < COVER_NAMES.size() && types.add(picture.type())) {
                covers.add(picture);
                used.add(coverKey(picture.type()).toUpperCase(Locale.ROOT));
            }
        }
        for (var entry : tags.entries()) {
            var lower = entry.key().toLowerCase(Locale.ROOT);
            spelling.putIfAbsent(lower, entry.key());
            grouped.computeIfAbsent(lower, _ -> new ArrayList<>()).add(entry.value());
        }
        for (var group : grouped.entrySet()) {
            var key = itemKey(spelling.get(group.getKey()));
            if (!isItemKey(key) || !used.add(key.toUpperCase(Locale.ROOT))) {
                continue;
            }
            writeItem(out, key, TEXT << 1, String.join("\0", group.getValue()).getBytes(StandardCharsets.UTF_8));
            count++;
        }
        for (var item : preserved) {
            if (isItemKey(item.key()) && used.add(item.key().toUpperCase(Locale.ROOT))) {
                writeItem(out, item.key(), item.flags(), item.value());
                count++;
            }
        }
        for (var picture : covers) {
            var description = picture.description();
            var nul = description.indexOf('\0');
            var base = nul >= 0 ? description.substring(0, nul) : description;
            var name = (base.isEmpty() ? coverKey(picture.type()) : base) + "." + TagIo.extension(picture);
            var file = name.getBytes(StandardCharsets.UTF_8);
            var value = new byte[file.length + 1 + picture.size()];
            System.arraycopy(file, 0, value, 0, file.length);
            System.arraycopy(picture.data(), 0, value, file.length + 1, picture.size());
            writeItem(out, coverKey(picture.type()), BINARY << 1, value);
            count++;
        }
        if (count == 0) {
            return new byte[0];
        }
        var size = (long) out.size() + FOOTER_SIZE;
        if (size + FOOTER_SIZE > Integer.MAX_VALUE - 8) {
            throw new MediaException.LimitExceeded("APE tag of " + size + " bytes exceeds the 2 GiB a tag can hold");
        }
        var result = new ByteWriter((int) size + FOOTER_SIZE);
        writeFooter(result, size, count, HAS_HEADER | IS_HEADER);
        result.bytes(out.buffer(), 0, out.size());
        writeFooter(result, size, count, HAS_HEADER);
        return result.toByteArray();
    }

    /**
     * Writes a header or footer.
     *
     * @param out the destination
     * @param size the size of the items and the footer
     * @param count the item count
     * @param flags the tag flags
     */
    private static void writeFooter(ByteWriter out, long size, int count, long flags) {
        out.bytes("APETAGEX".getBytes(StandardCharsets.US_ASCII));
        out.u32le(VERSION_2);
        out.u32le(size);
        out.u32le(count);
        out.u32le(flags);
        out.zeros(8);
    }

    /**
     * Writes one item.
     *
     * @param out the destination
     * @param key the key, a valid item key
     * @param flags the item flags
     * @param value the item value, written unchanged
     */
    private static void writeItem(ByteWriter out, String key, int flags, byte[] value) {
        out.u32le(value.length);
        out.u32le(flags & 0xFFFFFFFFL);
        out.bytes(key.getBytes(StandardCharsets.US_ASCII));
        out.u8(0);
        out.bytes(value);
    }

    /**
     * Returns the key a text item is read under.
     *
     * @param key the item key
     * @return the normalized key of a standard tag, or the item key
     */
    private static String mediaKey(String key) {
        var upper = key.toUpperCase(Locale.ROOT);
        if (upper.equals("ENCODED BY")) {
            return StandardTag.ENCODED_BY.key();
        }
        return StandardTag.normalize(key);
    }

    /**
     * Returns the item key a tag is written under.
     *
     * @param key the {@link MediaTags} key
     * @return the item key
     */
    private static String itemKey(String key) {
        return StandardTag.fromName(key).map(KEYS::get).orElse(key);
    }

    /**
     * Returns whether a key can name an item.
     *
     * @param key the candidate key
     * @return {@code true} for 2 to 255 printable ASCII characters other than the reserved keys
     */
    static boolean isItemKey(String key) {
        if (key.length() < 2 || key.length() > MAX_KEY || FORBIDDEN_KEYS.contains(key.toUpperCase(Locale.ROOT))) {
            return false;
        }
        for (var i = 0; i < key.length(); i++) {
            var c = key.charAt(i);
            if (c < 0x20 || c > 0x7E) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns whether a key can name a {@link MediaTags.Entry}.
     *
     * @param key the candidate key
     * @return {@code true} when the key is not blank and holds no {@code '='} and no control character
     */
    private static boolean isEntryKey(String key) {
        if (key.isBlank()) {
            return false;
        }
        for (var i = 0; i < key.length(); i++) {
            var c = key.charAt(i);
            if (c == '=' || c < 0x20 || c == 0x7F) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns the key of the cover art item of a picture type.
     *
     * @param type the picture type
     * @return the key, such as {@code "Cover Art (Front)"}
     */
    private static String coverKey(AttachedPicture.Type type) {
        return COVER_PREFIX + COVER_NAMES.get(type.code()) + ")";
    }

    /**
     * Decodes the values of a text item.
     *
     * <p>Version 2.0 text is UTF-8, with malformed sequences replaced; version 1.0 text, which the
     * specification limits to ASCII, is decoded as UTF-8 when valid and as ISO-8859-1 otherwise.
     *
     * @param item a text item
     * @return the values, or {@code null} when the item is empty
     */
    private List<String> values(Item item) {
        if (item.value().length == 0) {
            return null;
        }
        String text;
        if (version >= VERSION_2) {
            text = new String(item.value(), StandardCharsets.UTF_8);
        } else {
            try {
                text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(item.value())).toString();
            } catch (CharacterCodingException exception) {
                text = new String(item.value(), StandardCharsets.ISO_8859_1);
            }
        }
        return List.of(text.split("\0"));
    }

    /**
     * Decodes a cover art item.
     *
     * @param item a binary item
     * @return the picture, or {@code null} when the item is not cover art or holds no image bytes
     */
    private static AttachedPicture picture(Item item) {
        var key = item.key();
        if (!key.regionMatches(true, 0, COVER_PREFIX, 0, COVER_PREFIX.length()) || !key.endsWith(")")) {
            return null;
        }
        var name = key.substring(COVER_PREFIX.length(), key.length() - 1);
        var code = -1;
        for (var index = 0; index < COVER_NAMES.size(); index++) {
            if (COVER_NAMES.get(index).equalsIgnoreCase(name)) {
                code = index;
                break;
            }
        }
        if (code < 0) {
            return null;
        }
        var value = item.value();
        var nul = 0;
        while (nul < value.length && value[nul] != 0) {
            nul++;
        }
        var description = "";
        var start = 0;
        if (nul < value.length && ImageFormat.detect(value, value.length).isEmpty()) {
            description = new String(value, 0, nul, StandardCharsets.UTF_8);
            start = nul + 1;
        }
        if (start >= value.length) {
            return null;
        }
        var data = new byte[value.length - start];
        System.arraycopy(value, start, data, 0, data.length);
        var format = ImageFormat.detect(data, data.length);
        var mediaType = format.map(ImageFormat::mimeType).orElse("");
        var type = AttachedPicture.Type.of(code);
        var dot = description.lastIndexOf('.');
        if (dot >= 0) {
            var extension = description.substring(dot + 1);
            var expected = TagIo.extension(AttachedPicture.of(type, mediaType, "", data));
            if (extension.equalsIgnoreCase(expected)
                    || format.orElse(null) == ImageFormat.JPEG && extension.equalsIgnoreCase("jpeg")) {
                description = description.substring(0, dot);
            }
        }
        if (description.equalsIgnoreCase(coverKey(type))) {
            description = "";
        }
        return AttachedPicture.of(type, mediaType, description, data);
    }

    /**
     * Holds one item as stored.
     *
     * @param key the key, ASCII
     * @param flags the item flags; zero for every item of a version 1.0 tag
     * @param value the value; not copied
     */
    public record Item(String key, int flags, byte[] value) {
        /**
         * {@return the item type: {@link #TEXT}, {@link #BINARY}, {@link #LOCATOR} or 3 (reserved)}
         */
        public int type() {
            return flags >>> 1 & 3;
        }
    }

    /**
     * Holds the decoded content of a tag.
     *
     * @param entries the text entries
     * @param pictures the cover art
     * @param opaque the items without a {@link MediaTags} representation
     */
    public record Content(List<MediaTags.Entry> entries, List<AttachedPicture> pictures, List<Item> opaque) {
    }
}
