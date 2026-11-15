package com.github.auties00.smalt.internal.tags;

import com.github.auties00.smalt.internal.io.ByteArrays;
import com.github.auties00.smalt.internal.io.ByteWriter;
import com.github.auties00.smalt.metadata.MediaTags;
import com.github.auties00.smalt.metadata.StandardTag;

import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Reads and rebuilds QuickTime keyed metadata: a {@code meta} box whose handler is {@code mdta}, holding a
 * {@code keys} box of key names and an {@code ilst} box whose items are named by the one-based index of their key
 * (Apple QuickTime File Format, Metadata).
 *
 * <p>An item of a key of namespace {@code mdta} whose {@code data} atoms all hold text (UTF-8 or UTF-16), a
 * big-endian signed or unsigned integer or an IEEE 754 float is read as one value per atom, numbers as decimal
 * text. Any other item (binary data, pictures, a key of another namespace, an empty value) is opaque: it is kept
 * while the values of its key are unchanged, and dropped when they change. A rebuilt item keeps its bytes while its
 * values are unchanged; otherwise each value is written in the data type and locale of the item's first atom when it
 * is a number of that type, as UTF-8 text otherwise, and the values of a new key as UTF-8 text.
 *
 * <p>Instances are immutable.
 */
final class Mp4KeyedMetadata {
    /**
     * The handler type of keyed metadata.
     */
    static final String HANDLER = "mdta";

    /**
     * The key namespace of keyed metadata, {@code mdta}.
     */
    private static final int MDTA = 0x6D647461;

    /**
     * The data type of UTF-8 text.
     */
    private static final int UTF8 = 1;

    /**
     * The data type of UTF-16 text.
     */
    private static final int UTF16 = 2;

    /**
     * The data type of a signed big-endian integer of 1, 2, 3, 4 or 8 bytes.
     */
    private static final int SIGNED = 21;

    /**
     * The data type of an unsigned big-endian integer of 1, 2, 3, 4 or 8 bytes.
     */
    private static final int UNSIGNED = 22;

    /**
     * The data type of a big-endian IEEE 754 single.
     */
    private static final int FLOAT32 = 23;

    /**
     * The data type of a big-endian IEEE 754 double.
     */
    private static final int FLOAT64 = 24;

    /**
     * The decimal numbers a float value is written from: what {@link Float#toString} and {@link Double#toString}
     * produce and plain decimals, with no type suffix or hexadecimal form.
     */
    private static final Pattern DECIMAL = Pattern.compile("[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?|NaN"
            + "|[+-]?Infinity");

    /**
     * The keys that name keyed metadata when no file holds them: reverse-DNS names of three or more labels.
     */
    private static final Pattern REVERSE_DNS = Pattern.compile("[a-z][a-z0-9-]*(\\.[A-Za-z0-9_-]+){2,}");

    /**
     * The items, in file order.
     */
    private final List<Item> items;

    /**
     * Constructs keyed metadata.
     *
     * @param items the items in file order
     */
    private Mp4KeyedMetadata(List<Item> items) {
        this.items = List.copyOf(items);
    }

    /**
     * Returns whether a key names keyed metadata when the file holds no item of it.
     *
     * @param key a {@link MediaTags} key
     * @return {@code true} for a reverse-DNS name, such as {@code com.apple.quicktime.make}
     */
    static boolean isReverseDns(String key) {
        return REVERSE_DNS.matcher(key).matches();
    }

    /**
     * Returns whether the handler of a {@code meta} box is {@code mdta}.
     *
     * @param data the bytes holding the box
     * @param meta the box
     * @return {@code true} for keyed metadata
     */
    static boolean isKeyed(byte[] data, Mp4Boxes.Box meta) {
        var hdlr = Mp4Boxes.find(Mp4Boxes.children(data, childrenStart(data, meta), meta.end()), "hdlr");
        return hdlr != null && hdlr.size() >= hdlr.header() + 12 && Mp4Boxes.type(data, hdlr.payload() + 8)
                .equals(HANDLER);
    }

    /**
     * Returns the index of the first child of a {@code meta} box, which is a full box in ISO files and a plain atom
     * in QuickTime files.
     *
     * @param data the bytes holding the box
     * @param meta the box
     * @return the index
     */
    static int childrenStart(byte[] data, Mp4Boxes.Box meta) {
        var payload = meta.payload();
        if (meta.end() - payload >= 8 && Mp4Boxes.type(data, payload + 4).equals("hdlr")) {
            return payload;
        }
        return Math.min(meta.end(), payload + 4);
    }

    /**
     * Parses the keyed metadata of a {@code meta} box.
     *
     * @param data the bytes holding the box
     * @param meta the box, whose handler is {@code mdta}
     * @return the metadata; items naming a key the {@code keys} box lacks are left out
     */
    static Mp4KeyedMetadata parse(byte[] data, Mp4Boxes.Box meta) {
        var children = Mp4Boxes.children(data, childrenStart(data, meta), meta.end());
        var keysBox = Mp4Boxes.find(children, "keys");
        var ilst = Mp4Boxes.find(children, "ilst");
        var names = new ArrayList<String>();
        var namespaces = new ArrayList<Integer>();
        if (keysBox != null && keysBox.end() - keysBox.payload() >= 8) {
            var count = ByteArrays.u32be(data, keysBox.payload() + 4);
            var position = keysBox.payload() + 8;
            for (var index = 0L; index < count && keysBox.end() - position >= 8; index++) {
                var size = ByteArrays.u32be(data, position);
                if (size < 8 || size > keysBox.end() - position) {
                    break;
                }
                namespaces.add(ByteArrays.i32be(data, position + 4));
                names.add(new String(data, position + 8, (int) size - 8, StandardCharsets.UTF_8));
                position += (int) size;
            }
        }
        var items = new ArrayList<Item>();
        if (ilst != null) {
            for (var item : Mp4Boxes.children(data, ilst.payload(), ilst.end())) {
                var index = ByteArrays.u32be(data, item.offset() + 4);
                if (index < 1 || index > names.size()) {
                    continue;
                }
                var key = names.get((int) index - 1);
                var namespace = namespaces.get((int) index - 1);
                var body = Arrays.copyOfRange(data, item.payload(), item.end());
                var decoded = namespace == MDTA ? decode(body, key) : null;
                items.add(new Item(key, namespace, body, decoded == null ? null : decoded.values(),
                        decoded == null ? null : decoded.form()));
            }
        }
        return new Mp4KeyedMetadata(items);
    }

    /**
     * {@return the items, in file order}
     */
    List<Item> items() {
        return items;
    }

    /**
     * Decodes the {@code data} atoms of an item.
     *
     * @param body the children of the item
     * @param key the key of the item
     * @return the values and the form of the first atom, or {@code null} when the item is opaque
     */
    private static Decoded decode(byte[] body, String key) {
        try {
            new MediaTags.Entry(key, "-");
        } catch (IllegalArgumentException exception) {
            return null;
        }
        var values = new ArrayList<String>();
        Form form = null;
        for (var atom : Mp4Boxes.children(body, 0, body.length)) {
            if (!atom.type().equals("data") || atom.end() - atom.payload() < 8) {
                return null;
            }
            var indicator = ByteArrays.u32be(body, atom.payload());
            if (indicator >>> 24 != 0) {
                return null;
            }
            var type = (int) indicator;
            var start = atom.payload() + 8;
            var length = atom.end() - start;
            var value = value(body, start, length, type);
            if (value == null || value.isBlank() || value.indexOf('\0') >= 0) {
                return null;
            }
            if (form == null) {
                form = new Form(type, length, ByteArrays.i32be(body, atom.payload() + 4));
            }
            values.add(value);
        }
        return form == null ? null : new Decoded(values, form);
    }

    /**
     * Decodes one value.
     *
     * @param data the bytes
     * @param start the index of the value
     * @param length the length of the value
     * @param type the well-known data type
     * @return the value as text, or {@code null} for a type or length text cannot hold
     */
    private static String value(byte[] data, int start, int length, int type) {
        return switch (type) {
            case UTF8 -> {
                try {
                    yield StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(data, start, length))
                            .toString();
                } catch (CharacterCodingException exception) {
                    yield null;
                }
            }
            case UTF16 -> (length & 1) != 0 ? null : new String(data, start, length, StandardCharsets.UTF_16BE);
            case SIGNED, UNSIGNED -> length == 1 || length == 2 || length == 3 || length == 4 || length == 8
                    ? integer(data, start, length, type == UNSIGNED) : null;
            case FLOAT32 -> length == 4 ? Float.toString(Float.intBitsToFloat(ByteArrays.i32be(data, start))) : null;
            case FLOAT64 -> length == 8 ? Double.toString(Double.longBitsToDouble(ByteArrays.i64be(data, start)))
                    : null;
            default -> {
                var width = fixedWidth(type);
                yield width > 0 && width == length ? integer(data, start, length, isUnsigned(type)) : null;
            }
        };
    }

    /**
     * Returns the width of a fixed-width integer data type.
     *
     * @param type the data type
     * @return 1, 2, 4 or 8 for the types 65 to 67 and 74 to 78, or {@code 0}
     */
    private static int fixedWidth(int type) {
        return switch (type) {
            case 65, 75 -> 1;
            case 66, 76 -> 2;
            case 67, 77 -> 4;
            case 74, 78 -> 8;
            default -> 0;
        };
    }

    /**
     * Returns whether an integer data type is unsigned.
     *
     * @param type the data type
     * @return {@code true} for {@value #UNSIGNED} and the types 75 to 78
     */
    private static boolean isUnsigned(int type) {
        return type == UNSIGNED || type >= 75 && type <= 78;
    }

    /**
     * Decodes a big-endian integer as decimal text.
     *
     * @param data the bytes
     * @param start the index of the first byte
     * @param length the width, from 1 to 8
     * @param unsigned whether the value is unsigned rather than two's complement
     * @return the text
     */
    private static String integer(byte[] data, int start, int length, boolean unsigned) {
        var value = 0L;
        for (var index = 0; index < length; index++) {
            value = (value << 8) | (data[start + index] & 0xFF);
        }
        if (unsigned) {
            return Long.toUnsignedString(value);
        }
        var unused = 64 - 8 * length;
        return Long.toString(value << unused >> unused);
    }

    /**
     * Rebuilds the {@code meta} box with new values.
     *
     * <p>The decoded items of the keys {@code values} holds are replaced by those values, in place of the first item
     * of the key; the other decoded items are dropped. An opaque item is kept when {@code keepOpaque} is set and the
     * values of its key are those the decoded items held (none for a key only opaque items hold). The keys of
     * {@code values} the box lacks follow. The handler and the other children of the box are kept.
     *
     * @param data the bytes holding the box
     * @param meta the box
     * @param values the values of the keys the box stores, by key in order; keys compared ignoring case
     * @param keepOpaque whether opaque items are kept
     * @return the box unchanged when every item stays as it is, the new box, or an empty array when the box ends
     * without items and other children
     */
    byte[] rebuild(byte[] data, Mp4Boxes.Box meta, Map<String, List<String>> values, boolean keepOpaque) {
        var wanted = new LinkedHashMap<String, Map.Entry<String, List<String>>>();
        for (var entry : values.entrySet()) {
            wanted.put(entry.getKey().toLowerCase(Locale.ROOT), entry);
        }
        var written = new ArrayList<Item>();
        var emitted = new HashSet<String>();
        var changed = false;
        for (var item : items) {
            var lower = StandardTag.normalize(item.key()).toLowerCase(Locale.ROOT);
            var replacement = wanted.get(lower);
            if (!item.isDecoded()) {
                var read = decodedValues(lower);
                var kept = keepOpaque && (read.isEmpty() ? replacement == null
                        : replacement != null && replacement.getValue().equals(read));
                if (kept) {
                    written.add(item);
                } else {
                    changed = true;
                }
                continue;
            }
            if (replacement == null || !emitted.add(lower)) {
                changed = true;
                continue;
            }
            var spelled = StandardTag.normalize(item.key()).equals(replacement.getKey());
            if (spelled && replacement.getValue().equals(item.values()) && count(lower) == 1) {
                written.add(item);
            } else {
                written.add(encode(spelled ? item.key() : replacement.getKey(), replacement.getValue(), item.form()));
                changed = true;
            }
        }
        for (var entry : wanted.entrySet()) {
            if (emitted.add(entry.getKey())) {
                written.add(encode(entry.getValue().getKey(), entry.getValue().getValue(), null));
                changed = true;
            }
        }
        if (!changed) {
            return Arrays.copyOfRange(data, meta.offset(), meta.end());
        }
        var start = childrenStart(data, meta);
        var out = new ByteWriter(meta.size() + 256);
        out.bytes(data, meta.payload(), start - meta.payload());
        var others = false;
        var placed = false;
        for (var child : Mp4Boxes.children(data, start, meta.end())) {
            switch (child.type()) {
                case "keys", "free", "skip" -> {
                }
                case "ilst" -> {
                    if (!placed) {
                        out.bytes(boxes(written));
                        placed = true;
                    }
                }
                case "hdlr" -> out.bytes(data, child.offset(), child.size());
                default -> {
                    out.bytes(data, child.offset(), child.size());
                    others = true;
                }
            }
        }
        if (!placed) {
            out.bytes(boxes(written));
        }
        if (written.isEmpty() && !others) {
            return new byte[0];
        }
        return Mp4Boxes.box("meta", out.toByteArray());
    }

    /**
     * Builds a new {@code meta} box of keyed metadata, a plain QuickTime atom without version and flags as Apple
     * writes it, in ISO files too.
     *
     * @implNote This implementation writes the QuickTime form in ISO files as well because ExifTool reads the keyed
     * metadata of a {@code moov/meta} box only in that form.
     * @param values the values, by key in order
     * @return the box
     */
    static byte[] create(Map<String, List<String>> values) {
        var written = new ArrayList<Item>();
        for (var entry : values.entrySet()) {
            written.add(encode(entry.getKey(), entry.getValue(), null));
        }
        var out = new ByteWriter(256);
        out.bytes(handler());
        out.bytes(boxes(written));
        return Mp4Boxes.box("meta", out.toByteArray());
    }

    /**
     * {@return the {@code hdlr} box of keyed metadata}
     */
    private static byte[] handler() {
        var payload = new byte[25];
        payload[8] = 'm';
        payload[9] = 'd';
        payload[10] = 't';
        payload[11] = 'a';
        return Mp4Boxes.box("hdlr", payload);
    }

    /**
     * Returns the number of decoded items of a key.
     *
     * @param lower the normalized key in lower case
     * @return the count
     */
    private int count(String lower) {
        var count = 0;
        for (var item : items) {
            if (item.isDecoded() && StandardTag.normalize(item.key()).toLowerCase(Locale.ROOT).equals(lower)) {
                count++;
            }
        }
        return count;
    }

    /**
     * Returns the values the decoded items of a key hold.
     *
     * @param lower the normalized key in lower case
     * @return the values in order, empty when no decoded item holds the key
     */
    private List<String> decodedValues(String lower) {
        var values = new ArrayList<String>();
        for (var item : items) {
            if (item.isDecoded() && StandardTag.normalize(item.key()).toLowerCase(Locale.ROOT).equals(lower)) {
                values.addAll(item.values());
            }
        }
        return values;
    }

    /**
     * Encodes the {@code keys} and {@code ilst} boxes of items.
     *
     * @param written the items, in order
     * @return the two boxes
     */
    private static byte[] boxes(List<Item> written) {
        var keys = new ByteWriter(64 + written.size() * 48);
        var ilst = new ByteWriter(64 + written.size() * 48);
        var indexes = new LinkedHashMap<String, Integer>();
        for (var item : written) {
            var identity = item.namespace() + ":" + item.key();
            var index = indexes.get(identity);
            if (index == null) {
                index = indexes.size() + 1;
                indexes.put(identity, index);
                var name = item.key().getBytes(StandardCharsets.UTF_8);
                keys.u32be(8L + name.length);
                keys.u32be(item.namespace() & 0xFFFFFFFFL);
                keys.bytes(name);
            }
            ilst.u32be(8L + item.body().length);
            ilst.u32be(index);
            ilst.bytes(item.body());
        }
        var keysPayload = new ByteWriter(8 + keys.size());
        keysPayload.u32be(0);
        keysPayload.u32be(indexes.size());
        keysPayload.bytes(keys.toByteArray());
        var out = new ByteWriter(keysPayload.size() + ilst.size() + 16);
        Mp4Boxes.box(out, "keys", keysPayload.toByteArray());
        Mp4Boxes.box(out, "ilst", ilst.toByteArray());
        return out.toByteArray();
    }

    /**
     * Encodes the item of one key.
     *
     * @param key the key name the {@code keys} box lists
     * @param values the values, one {@code data} atom each
     * @param form the form of the key's first atom in the file, or {@code null} for UTF-8 text
     * @return the item
     */
    private static Item encode(String key, List<String> values, Form form) {
        var body = new ByteWriter(64);
        for (var value : values) {
            var type = UTF8;
            var payload = (byte[]) null;
            if (form != null) {
                payload = encodeValue(value, form);
                type = payload != null ? form.type() : UTF8;
            }
            if (payload == null) {
                payload = value.getBytes(StandardCharsets.UTF_8);
            }
            var atom = new ByteWriter(8 + payload.length);
            atom.u32be(type);
            atom.u32be(form == null ? 0 : form.locale() & 0xFFFFFFFFL);
            atom.bytes(payload);
            Mp4Boxes.box(body, "data", atom.toByteArray());
        }
        return new Item(key, MDTA, body.toByteArray(), List.copyOf(values), form);
    }

    /**
     * Encodes a value in the form of a key.
     *
     * @param value the value as text
     * @param form the data type and width to write the value in
     * @return the payload, or {@code null} when the value is not of the form's type
     */
    private static byte[] encodeValue(String value, Form form) {
        switch (form.type()) {
            case UTF8 -> {
                return value.getBytes(StandardCharsets.UTF_8);
            }
            case UTF16 -> {
                return value.getBytes(StandardCharsets.UTF_16BE);
            }
            case FLOAT32 -> {
                if (!DECIMAL.matcher(value).matches()) {
                    return null;
                }
                var bytes = new byte[4];
                ByteArrays.put32be(bytes, 0, Float.floatToRawIntBits(Float.parseFloat(value)));
                return bytes;
            }
            case FLOAT64 -> {
                if (!DECIMAL.matcher(value).matches()) {
                    return null;
                }
                var bits = Double.doubleToRawLongBits(Double.parseDouble(value));
                var bytes = new byte[8];
                ByteArrays.put32be(bytes, 0, bits >>> 32);
                ByteArrays.put32be(bytes, 4, bits & 0xFFFFFFFFL);
                return bytes;
            }
            default -> {
                var unsigned = isUnsigned(form.type());
                var width = form.type() == SIGNED || form.type() == UNSIGNED ? form.width() : fixedWidth(form.type());
                long number;
                try {
                    number = unsigned ? Long.parseUnsignedLong(value) : Long.parseLong(value);
                } catch (NumberFormatException exception) {
                    return null;
                }
                var bits = 8 * width;
                if (bits < 64) {
                    var fits = unsigned ? number >>> bits == 0
                            : number >= -1L << (bits - 1) && number <= ~(-1L << (bits - 1));
                    if (!fits) {
                        return null;
                    }
                }
                var bytes = new byte[width];
                for (var index = width - 1; index >= 0; index--) {
                    bytes[index] = (byte) number;
                    number >>>= 8;
                }
                return bytes;
            }
        }
    }

    /**
     * The values and form of a decoded item.
     *
     * @param values the decoded values, one per atom
     * @param form the form of the first atom
     */
    private record Decoded(List<String> values, Form form) {
    }

    /**
     * The encoding of the first {@code data} atom of an item.
     *
     * @param type the well-known data type
     * @param width the length of the value in bytes
     * @param locale the locale field
     */
    record Form(int type, int width, int locale) {
    }

    /**
     * One {@code ilst} item.
     *
     * @param key the key name
     * @param namespace the key namespace
     * @param body the children of the item, its {@code data} atoms
     * @param values the decoded values, or {@code null} for an opaque item
     * @param form the form of the first atom, or {@code null} for an opaque item
     */
    record Item(String key, int namespace, byte[] body, List<String> values, Form form) {
        /**
         * {@return whether the item was decoded}
         */
        boolean isDecoded() {
            return values != null;
        }
    }
}
