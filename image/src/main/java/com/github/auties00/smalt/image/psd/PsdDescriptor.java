package com.github.auties00.smalt.image.psd;

import com.github.auties00.smalt.MediaException;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads the action descriptors of Photoshop tagged blocks into plain values: a descriptor becomes a {@link Map} from
 * key to value, a list a {@link List}, {@code bool} a {@link Boolean}, {@code long} a {@link Long}, {@code doub},
 * {@code UntF} and {@code comp} a {@link Double} or {@link Long}, {@code TEXT} and {@code enum} a {@link String}, and
 * every other type a placeholder that is only skipped.
 *
 * <p>Arguments are not checked beyond the bounds of the data; a structure that runs past them or nests deeper than
 * {@value #MAX_DEPTH} levels throws {@link MediaException.Malformed}.
 */
final class PsdDescriptor {
    /** The deepest nesting of descriptors and lists read. */
    static final int MAX_DEPTH = 32;

    /** The value of the types the reader skips. */
    static final Object SKIPPED = new Object();

    private final byte[] data;
    private final int end;
    private int position;

    private PsdDescriptor(byte[] data, int position, int end) {
        this.data = data;
        this.position = position;
        this.end = end;
    }

    /**
     * Reads a versioned descriptor, a 4-byte descriptor version (16) followed by the descriptor.
     *
     * @param data the bytes holding the descriptor
     * @param position the offset of the version
     * @param end the end of the block
     * @return the descriptor's items
     */
    static Map<String, Object> versioned(byte[] data, int position, int end) {
        var reader = new PsdDescriptor(data, position, end);
        var version = reader.u32();
        if (version != 16) {
            throw new MediaException.Malformed("unknown Photoshop descriptor version " + version);
        }
        return reader.descriptor(0);
    }

    private Map<String, Object> descriptor(int depth) {
        if (depth > MAX_DEPTH) {
            throw new MediaException.Malformed("a Photoshop descriptor nests too deeply");
        }
        unicode();
        key();
        var count = u32();
        var items = new LinkedHashMap<String, Object>();
        for (long index = 0; index < count; index++) {
            var key = key();
            items.put(key, value(type(), depth));
        }
        return items;
    }

    private Object value(String type, int depth) {
        return switch (type) {
            case "Objc", "GlbO" -> descriptor(depth + 1);
            case "VlLs" -> {
                var count = u32();
                var list = new ArrayList<>();
                for (long index = 0; index < count; index++) {
                    if (depth + 1 > MAX_DEPTH) {
                        throw new MediaException.Malformed("a Photoshop descriptor nests too deeply");
                    }
                    list.add(value(type(), depth + 1));
                }
                yield list;
            }
            case "bool" -> {
                need(1);
                yield data[position++] != 0;
            }
            case "long" -> (long) (int) u32();
            case "comp" -> {
                var high = u32();
                yield high << 32 | u32();
            }
            case "doub" -> doub();
            case "UntF" -> {
                skip(4);
                yield doub();
            }
            case "UnFl" -> {
                skip(4);
                skip(8L * u32());
                yield SKIPPED;
            }
            case "TEXT" -> unicode();
            case "enum" -> {
                key();
                yield key();
            }
            case "type", "GlbC" -> {
                unicode();
                key();
                yield SKIPPED;
            }
            case "alis", "tdta", "Pth " -> {
                skip(u32());
                yield SKIPPED;
            }
            case "ObAr" -> {
                skip(4);
                descriptor(depth + 1);
                yield SKIPPED;
            }
            case "obj " -> {
                reference();
                yield SKIPPED;
            }
            default -> throw new MediaException.Malformed("unknown Photoshop descriptor type '" + type + "'");
        };
    }

    private void reference() {
        var count = u32();
        for (long index = 0; index < count; index++) {
            switch (type()) {
                case "prop" -> {
                    unicode();
                    key();
                    key();
                }
                case "Clss" -> {
                    unicode();
                    key();
                }
                case "Enmr" -> {
                    unicode();
                    key();
                    key();
                    key();
                }
                case "rele" -> {
                    unicode();
                    key();
                    skip(4);
                }
                case "Idnt", "indx" -> skip(4);
                case "name" -> unicode();
                default -> throw new MediaException.Malformed("unknown Photoshop reference form");
            }
        }
    }

    /** Reads a key or class id: a length, then that many bytes, or four bytes when the length is zero. */
    private String key() {
        var length = u32();
        var size = length == 0 ? 4 : length;
        need(size);
        var key = new String(data, position, (int) size, StandardCharsets.ISO_8859_1);
        position += (int) size;
        return key;
    }

    private String type() {
        need(4);
        var type = new String(data, position, 4, StandardCharsets.ISO_8859_1);
        position += 4;
        return type;
    }

    private String unicode() {
        var characters = u32();
        need(2 * characters);
        var text = new String(data, position, (int) (2 * characters), StandardCharsets.UTF_16BE);
        position += (int) (2 * characters);
        return text.endsWith("\0") ? text.substring(0, text.length() - 1) : text;
    }

    private double doub() {
        var high = u32();
        return Double.longBitsToDouble(high << 32 | u32());
    }

    private long u32() {
        need(4);
        var value = (data[position] & 0xFFL) << 24 | (data[position + 1] & 0xFFL) << 16
                | (data[position + 2] & 0xFFL) << 8 | (data[position + 3] & 0xFFL);
        position += 4;
        return value;
    }

    private void skip(long count) {
        need(count);
        position += (int) count;
    }

    private void need(long count) {
        if (count < 0 || position + count > end) {
            throw new MediaException.Malformed("a Photoshop descriptor runs past its block");
        }
    }

    /**
     * {@return the boolean item of a descriptor, or {@code fallback} when it is absent or not a boolean}
     *
     * @param items the descriptor
     * @param key the item key
     * @param fallback the value for an absent item
     */
    static boolean bool(Map<String, Object> items, String key, boolean fallback) {
        return items.get(key) instanceof Boolean value ? value : fallback;
    }

    /**
     * {@return the numeric item of a descriptor, or {@code fallback} when it is absent or not a number}
     *
     * @param items the descriptor
     * @param key the item key
     * @param fallback the value for an absent item
     */
    static double number(Map<String, Object> items, String key, double fallback) {
        return items.get(key) instanceof Number value ? value.doubleValue() : fallback;
    }

    /**
     * {@return the descriptor item of a descriptor, or {@code null} when it is absent or not a descriptor}
     *
     * @param items the descriptor
     * @param key the item key
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> object(Map<String, Object> items, String key) {
        return items.get(key) instanceof Map<?, ?> value ? (Map<String, Object>) value : null;
    }
}
