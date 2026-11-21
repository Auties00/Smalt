package com.github.auties00.smalt.container.mp4;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.container.AttachedPicture;
import com.github.auties00.smalt.container.internal.Annotations;
import com.github.auties00.smalt.internal.heif.BoxCursor;
import com.github.auties00.smalt.internal.io.FourCc;
import com.github.auties00.smalt.internal.tags.LocationInformation;
import com.github.auties00.smalt.metadata.StandardTag;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Collects the descriptive metadata of a movie: iTunes-style {@code ilst} items, QuickTime keyed
 * metadata ({@code mdta}), QuickTime user data text atoms, and 3GPP asset boxes; the coordinates of a 3GPP
 * location information box ({@code loci}) become the {@code location} tag, in ISO 6709 form, unless a QuickTime
 * {@code xyz} atom gives one.
 *
 * <p>An item that a {@link StandardTag} stores is kept under that tag's {@linkplain StandardTag#key()
 * key}, for example {@code title} for the copyright-sign-prefixed {@code nam} item; the other
 * items are kept under the names {@link #itemName(int)} lists. Keyed metadata keeps its
 * reverse-DNS key and freeform items keep their name. Metadata is descriptive only: a damaged item
 * is skipped rather than failing the whole file.
 */
final class MetadataReader {
    /**
     * The deepest nesting of metadata containers followed.
     */
    private static final int MAX_DEPTH = 4;

    /**
     * The largest number of tags collected from one file.
     */
    private static final int MAX_TAGS = 1024;

    /**
     * The formatter of creation times: ISO 8601 in UTC with six fractional digits, for example
     * {@code 2024-05-01T12:00:00.000000Z}.
     */
    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'").withZone(ZoneOffset.UTC);

    /**
     * The tag key of each item a {@link StandardTag} stores, by item type.
     */
    private static final Map<Integer, String> STANDARD_ITEMS = standardItems();

    /**
     * The seconds between 1904-01-01, the ISO base media epoch, and 1970-01-01.
     */
    private static final long EPOCH_1904 = 2082844800L;

    /**
     * The tags collected so far.
     */
    private final Map<String, String> tags;

    /**
     * The value of the iTunes gapless tag {@code iTunSMPB}, or {@code null}.
     */
    private String gapless;

    /**
     * The cover pictures of {@code covr} items and the Nero chapters of a {@code chpl} box.
     */
    final Annotations annotations = new Annotations();

    /**
     * Whether a {@code chpl} box gave chapters.
     */
    boolean neroChapters;

    /**
     * Constructs a reader that stores tags into a map.
     *
     * @param tags the destination map
     */
    MetadataReader(Map<String, String> tags) {
        this.tags = tags;
    }

    /**
     * Returns the value of the iTunes gapless tag.
     *
     * @return the {@code iTunSMPB} string, or {@code null} when absent
     */
    String gapless() {
        return gapless;
    }

    /**
     * Formats an ISO base media timestamp.
     *
     * @param seconds the seconds since 1904-01-01 UTC
     * @return the ISO 8601 form, or {@code null} for zero or an out-of-range value
     */
    static String formatTime(long seconds) {
        if (seconds <= 0 || seconds > EPOCH_1904 + 253402300799L) {
            return null;
        }
        return TIME_FORMAT.format(Instant.ofEpochSecond(seconds - EPOCH_1904));
    }

    /**
     * Stores a tag unless the limit is reached; later values of the same key replace earlier
     * ones.
     *
     * @param key the tag name
     * @param value the tag value
     */
    void put(String key, String value) {
        if (key == null || value == null || (tags.size() >= MAX_TAGS && !tags.containsKey(key))) {
            return;
        }
        tags.put(key, value);
    }

    /**
     * Reads a {@code udta} box.
     *
     * @param udta the box payload
     */
    void readUserData(BoxCursor udta) {
        for (var box : children(udta)) {
            try {
                var type = box.type();
                if (type == BoxTypes.META) {
                    readMeta(box, 0);
                } else if (type == FourCc.of("chpl")) {
                    readNeroChapters(box);
                } else if (((type >>> 24) & 0xFF) == 0xA9) {
                    readQuickTimeText(type, box);
                } else if (type == FourCc.of("loci")) {
                    var coordinates = LocationInformation.coordinates(box.data(), box.position(),
                            box.position() + box.remaining());
                    if (coordinates != null && !tags.containsKey("location")) {
                        put("location", coordinates);
                    }
                } else {
                    readAsset(type, box);
                }
            } catch (MediaException.Malformed ignored) {
                // a damaged tag does not invalidate the others
            }
        }
    }

    /**
     * Reads a {@code meta} box, which is a full box in ISO files and a plain atom in QuickTime
     * files.
     *
     * @param meta the box payload
     * @param depth the current nesting
     */
    void readMeta(BoxCursor meta, int depth) {
        if (depth > MAX_DEPTH || meta.remaining() < 8) {
            return;
        }
        var data = meta.data();
        var position = meta.position();
        var quickTime = data[position + 4] == 'h' && data[position + 5] == 'd' && data[position + 6] == 'l' && data[position + 7] == 'r';
        if (!quickTime) {
            meta.fullBoxHeader();
        }
        List<String> keys = null;
        BoxCursor items = null;
        for (var box : children(meta)) {
            if (box.type() == BoxTypes.KEYS) {
                keys = readKeys(box);
            } else if (box.type() == BoxTypes.ILST) {
                items = box;
            }
        }
        if (items != null) {
            readItems(items, keys);
        }
    }

    /**
     * Reads a QuickTime {@code keys} atom.
     *
     * @param keys the atom payload
     * @return the keys, indexed from zero
     */
    private static List<String> readKeys(BoxCursor keys) {
        keys.fullBoxHeader();
        var count = keys.u32();
        var result = new ArrayList<String>();
        for (var index = 0L; index < count && keys.remaining() >= 8 && result.size() < MAX_TAGS; index++) {
            var size = keys.u32();
            if (size < 8 || size - 4 > keys.remaining()) {
                break;
            }
            keys.s32();
            result.add(keys.string((int) size - 8));
        }
        return result;
    }

    /**
     * Reads an {@code ilst} item list.
     *
     * @param items the list payload
     * @param keys the keys of keyed metadata, or {@code null} for iTunes items
     */
    private void readItems(BoxCursor items, List<String> keys) {
        for (var item : children(items)) {
            try {
                readItem(item, keys);
            } catch (MediaException.Malformed ignored) {
                // a damaged item does not invalidate the others
            }
        }
    }

    /**
     * Reads one {@code ilst} item.
     *
     * @param item the item box
     * @param keys the keys of keyed metadata, or {@code null}
     */
    private void readItem(BoxCursor item, List<String> keys) {
        if (item.type() == FourCc.of("covr")) {
            readCover(item);
            return;
        }
        String key;
        String mean = null;
        String name = null;
        String value = null;
        for (var child : children(item)) {
            var body = child;
            switch (child.type()) {
                case BoxTypes.MEAN -> {
                    body.fullBoxHeader();
                    mean = body.string(body.remaining());
                }
                case BoxTypes.NAME -> {
                    body.fullBoxHeader();
                    name = body.string(body.remaining());
                }
                case BoxTypes.DATA -> {
                    if (value == null) {
                        value = readData(item.type(), body, keys != null);
                    }
                }
                default -> {
                }
            }
        }
        if (item.type() == BoxTypes.FREEFORM) {
            key = name;
            if ("iTunSMPB".equals(name) && value != null) {
                gapless = value;
            }
            if (mean != null && !mean.equals("com.apple.iTunes") && name != null) {
                key = mean + "." + name;
            }
        } else if (keys != null) {
            var index = item.type();
            key = index >= 1 && index <= keys.size() ? keys.get(index - 1) : null;
        } else {
            key = itemName(item.type());
        }
        put(key, value);
    }

    /**
     * Reads the pictures of a {@code covr} item: every {@code data} box of type 13 (JPEG), 14
     * (PNG), 27 (BMP) or 0 (implicit).
     *
     * @param item the item payload
     */
    private void readCover(BoxCursor item) {
        for (var child : children(item)) {
            if (child.type() != BoxTypes.DATA) {
                continue;
            }
            var body = child;
            if (body.remaining() < 8) {
                continue;
            }
            var type = body.s32() & 0xFFFFFF;
            body.s32();
            var mediaType = switch (type) {
                case 13 -> "image/jpeg";
                case 14 -> "image/png";
                case 27 -> "image/bmp";
                case 0 -> "";
                default -> null;
            };
            if (mediaType != null && body.remaining() > 0) {
                annotations.addPicture(AttachedPicture.of(AttachedPicture.Type.FRONT_COVER, mediaType, "", body.rest()));
            }
        }
    }

    /**
     * Reads a Nero {@code chpl} box: a version, flags, (for version 1) four reserved bytes, a
     * one-byte count, then per chapter a 64-bit start in units of 100 nanoseconds and a
     * length-prefixed UTF-8 title.
     *
     * @param chpl the box payload
     */
    private void readNeroChapters(BoxCursor chpl) {
        if (chpl.remaining() < 5) {
            return;
        }
        var version = chpl.fullBoxHeader();
        if (version != 0) {
            if (chpl.remaining() < 4) {
                return;
            }
            chpl.u32();
        }
        var count = chpl.u8();
        for (var index = 0; index < count && chpl.remaining() >= 9; index++) {
            var start = chpl.u64();
            var length = chpl.u8();
            if (length > chpl.remaining()) {
                return;
            }
            var title = chpl.string(length);
            if (start >= 0) {
                annotations.addChapter(start / 10, -1, title);
                neroChapters = true;
            }
        }
    }

    /**
     * Decodes the value of a {@code data} atom.
     *
     * <p>Type 0 is an integer in an iTunes item, whose atom implies its type, and binary data in keyed metadata;
     * the fixed-width integer types 65 to 67 and 74 to 78 of keyed metadata are read too.
     *
     * @param itemType the type of the enclosing item
     * @param body the atom payload
     * @param keyed whether the item is keyed metadata
     * @return the value as text, or {@code null} when it is binary
     */
    private static String readData(int itemType, BoxCursor body, boolean keyed) {
        var type = body.s32() & 0xFFFFFF;
        body.s32();
        var length = body.remaining();
        if (keyed && type == 0) {
            return null;
        }
        if (type >= 65 && type <= 78) {
            var width = switch (type) {
                case 65, 75 -> 1;
                case 66, 76 -> 2;
                case 67, 77 -> 4;
                case 74, 78 -> 8;
                default -> 0;
            };
            if (width == 0 || width != length) {
                return null;
            }
            var value = body.uN(width);
            if (type >= 75) {
                return Long.toUnsignedString(value);
            }
            var unused = 64 - 8 * width;
            return Long.toString(value << unused >> unused);
        }
        if (!keyed && (itemType == FourCc.of("trkn") || itemType == FourCc.of("disk"))) {
            if (length < 6) {
                return null;
            }
            body.u16();
            var number = body.u16();
            var total = body.u16();
            return total > 0 ? number + "/" + total : Integer.toString(number);
        }
        return switch (type) {
            case 1 -> body.string(length);
            case 2 -> new String(body.bytes(length), StandardCharsets.UTF_16BE);
            case 21, 22, 0 -> switch (length) {
                case 1 -> Integer.toString(type == 21 ? (byte) body.u8() : body.u8());
                case 2 -> Integer.toString(type == 21 ? body.s16() : body.u16());
                case 3 -> {
                    var value = (body.u16() << 8) | body.u8();
                    yield Integer.toString(type == 21 ? value << 8 >> 8 : value);
                }
                case 4 -> type == 21 ? Integer.toString(body.s32()) : Long.toString(body.u32());
                case 8 -> type == 22 ? Long.toUnsignedString(body.u64()) : Long.toString(body.u64());
                default -> null;
            };
            case 23 -> length == 4 ? Float.toString(Float.intBitsToFloat(body.s32())) : null;
            case 24 -> length == 8 ? Double.toString(Double.longBitsToDouble(body.u64())) : null;
            default -> null;
        };
    }

    /**
     * Reads a QuickTime user data text atom, whose name starts with the copyright sign: a list of
     * (length, language, text) records of which the first is kept.
     *
     * @param type the atom type
     * @param body the atom payload
     */
    private void readQuickTimeText(int type, BoxCursor body) {
        if (body.remaining() < 4) {
            return;
        }
        var length = body.u16();
        body.u16();
        if (length > body.remaining()) {
            return;
        }
        put(itemName(type), body.string(length));
    }

    /**
     * Reads a 3GPP asset information box (3GPP TS 26.244 clause 8), whose text follows a full box
     * header and a packed language code.
     *
     * @param type the box type
     * @param body the box payload
     */
    private void readAsset(int type, BoxCursor body) {
        var key = switch (FourCc.printable(type)) {
            case "titl" -> "title";
            case "auth" -> "author";
            case "perf" -> "artist";
            case "dscp" -> "description";
            case "cprt" -> "copyright";
            case "albm" -> "album";
            case "gnre" -> "genre";
            default -> null;
        };
        if (key == null || body.remaining() < 6) {
            return;
        }
        body.fullBoxHeader();
        body.u16();
        var bytes = body.rest();
        var end = bytes.length;
        String text;
        if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFE && (bytes[1] & 0xFF) == 0xFF) {
            while (end >= 4 && bytes[end - 1] == 0 && bytes[end - 2] == 0) {
                end -= 2;
            }
            text = new String(bytes, 2, end - 2, StandardCharsets.UTF_16BE);
        } else {
            while (end > 0 && bytes[end - 1] == 0) {
                end--;
            }
            text = new String(bytes, 0, end, StandardCharsets.UTF_8);
        }
        put(key, text);
    }

    /**
     * Returns the tag name of an iTunes item or QuickTime text atom.
     *
     * <p>An item a {@link StandardTag} stores is named by that tag's key. Of the others,
     * copyright-sign-prefixed atoms are named {@code location} ({@code xyz}), {@code description}
     * ({@code des}), {@code copyright} ({@code cpy}), {@code encoder} ({@code swr}), {@code make}
     * ({@code mak}) and {@code model} ({@code mod}); {@code desc}, {@code ldes}, {@code gnre},
     * {@code tvsh}, {@code tven}, {@code tvnn} and the {@code so*} sort atoms are named
     * {@code description}, {@code synopsis}, {@code genre}, {@code show}, {@code episode_id},
     * {@code network} and {@code sort_*}. Any other atom keeps its four characters.
     *
     * @param type the item type
     * @return the name
     */
    static String itemName(int type) {
        var standard = STANDARD_ITEMS.get(type);
        if (standard != null) {
            return standard;
        }
        var code = FourCc.printable(type & 0x00FFFFFF | 0x3F000000);
        if (((type >>> 24) & 0xFF) == 0xA9) {
            return switch (code.substring(1)) {
                case "xyz" -> "location";
                case "des" -> "description";
                case "cpy" -> "copyright";
                case "swr" -> "encoder";
                case "mak" -> "make";
                case "mod" -> "model";
                default -> "\u00A9" + code.substring(1);
            };
        }
        return switch (FourCc.printable(type)) {
            case "desc" -> "description";
            case "ldes" -> "synopsis";
            case "gnre" -> "genre";
            case "tvsh" -> "show";
            case "tven" -> "episode_id";
            case "tvnn" -> "network";
            case "sonm" -> "sort_name";
            case "soar" -> "sort_artist";
            case "soaa" -> "sort_album_artist";
            case "soal" -> "sort_album";
            case "soco" -> "sort_composer";
            default -> FourCc.printable(type);
        };
    }

    /**
     * Builds the table of the items the standard tags store.
     *
     * @return the tag key of each {@link StandardTag#mp4Atom()}, by item type
     */
    private static Map<Integer, String> standardItems() {
        var items = new HashMap<Integer, String>();
        for (var tag : StandardTag.values()) {
            tag.mp4Atom().ifPresent(atom -> items.put(FourCc.of(atom), tag.key()));
        }
        return Map.copyOf(items);
    }

    /**
     * Returns the child boxes of a metadata container, stopping quietly at the first malformed
     * child.
     *
     * @param payload the container payload
     * @return the children in order
     */
    private static List<BoxCursor> children(BoxCursor payload) {
        var result = new ArrayList<BoxCursor>();
        try {
            while (payload.hasBox() && result.size() < MAX_TAGS) {
                result.add(payload.nextBox());
            }
        } catch (MediaException.Malformed ignored) {
            // trailing garbage after the last intact child is ignored
        }
        return result;
    }
}
