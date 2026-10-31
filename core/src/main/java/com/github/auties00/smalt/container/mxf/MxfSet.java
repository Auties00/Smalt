package com.github.auties00.smalt.container.mxf;

import com.github.auties00.smalt.MediaException;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * One local set of header metadata: its key and its items by local tag.
 *
 * <p>Items with a dynamic tag (from {@code 0x8000}) that the primer maps to a label this implementation reads are
 * stored under that label's reserved tag ({@link MxfLabels#SUB_DESCRIPTORS_TAG},
 * {@link MxfLabels#AVC_DECODING_DELAY_TAG}); other dynamic items are dropped.
 * Readers of item values return a default when the item is missing or too short, as a reader of optional items does.
 */
final class MxfSet {
    /**
     * The set key.
     */
    private final byte[] key;

    /**
     * The item values by local tag.
     */
    private final Map<Integer, byte[]> items;

    MxfSet(byte[] key, Map<Integer, byte[]> items) {
        this.key = key;
        this.items = items;
    }

    /**
     * Parses the sets of header metadata.
     *
     * @param data the header metadata bytes, from the primer pack on
     * @param length the number of bytes of {@code data} to parse
     * @param maxSets the largest number of sets accepted
     * @return the sets by instance UID, and the sets without one under no key
     * @throws MediaException.Malformed if the primer pack is missing or a set is truncated
     * @throws MediaException.LimitExceeded if there are more than {@code maxSets} sets
     */
    static Map<Uid, MxfSet> parseAll(byte[] data, int length, int maxSets) {
        var position = 0;
        Map<Integer, byte[]> dynamic = Map.of();
        var sets = new HashMap<Uid, MxfSet>();
        var primerSeen = false;
        while (length - position >= 17) {
            var keyStart = position;
            position += 16;
            var berStart = position;
            var first = data[position++] & 0xFF;
            long size;
            if (first < 0x80) {
                size = first;
            } else {
                var count = first & 0x7F;
                if (count == 0 || count > 8 || length - position < count) {
                    throw new MediaException.Malformed("invalid MXF length at header metadata offset " + berStart);
                }
                size = 0;
                for (var index = 0; index < count; index++) {
                    size = size << 8 | data[position++] & 0xFF;
                }
            }
            if (size < 0 || size > length - position) {
                throw new MediaException.Malformed("MXF header metadata item of " + size + " bytes overruns the header");
            }
            var valueStart = position;
            position += (int) size;
            if (MxfLabels.startsWith(slice(data, keyStart, 16), MxfLabels.PRIMER)) {
                dynamic = primer(data, valueStart, (int) size);
                primerSeen = true;
                continue;
            }
            if (!isSetKey(data, keyStart)) {
                continue;
            }
            if (!primerSeen) {
                throw new MediaException.Malformed("MXF header metadata set before the primer pack");
            }
            if (sets.size() >= maxSets) {
                throw new MediaException.LimitExceeded("MXF header metadata holds more than " + maxSets + " sets");
            }
            var items = new HashMap<Integer, byte[]>();
            var at = valueStart;
            var end = valueStart + (int) size;
            while (end - at >= 4) {
                var tag = (data[at] & 0xFF) << 8 | data[at + 1] & 0xFF;
                var itemLength = (data[at + 2] & 0xFF) << 8 | data[at + 3] & 0xFF;
                at += 4;
                if (itemLength > end - at) {
                    throw new MediaException.Malformed("MXF local set item " + Integer.toHexString(tag)
                            + " overruns its set");
                }
                var value = slice(data, at, itemLength);
                at += itemLength;
                if (tag >= 0x8000) {
                    var label = dynamic.get(tag);
                    if (label != null && MxfLabels.matches(label, MxfLabels.SUB_DESCRIPTORS)) {
                        items.putIfAbsent(MxfLabels.SUB_DESCRIPTORS_TAG, value);
                    } else if (label != null && MxfLabels.matches(label, MxfLabels.AVC_DECODING_DELAY)) {
                        items.putIfAbsent(MxfLabels.AVC_DECODING_DELAY_TAG, value);
                    }
                    continue;
                }
                items.putIfAbsent(tag, value);
            }
            var set = new MxfSet(slice(data, keyStart, 16), items);
            var uid = set.uid(0x3C0A);
            sets.putIfAbsent(uid != null ? uid : new Uid(sets.size(), -1L ^ keyStart), set);
        }
        if (!primerSeen) {
            throw new MediaException.Malformed("MXF header metadata without a primer pack");
        }
        return sets;
    }

    /**
     * Returns whether the key at an offset is that of a header metadata set (or of an index table segment, which is a
     * local set too and is read elsewhere).
     */
    private static boolean isSetKey(byte[] data, int offset) {
        return (data[offset] & 0xFF) == 0x06 && data[offset + 1] == 0x0E && data[offset + 2] == 0x2B
                && data[offset + 3] == 0x34 && data[offset + 4] == 0x02 && data[offset + 5] == 0x53
                && !(data[offset + 8] == 0x0D && data[offset + 9] == 0x01 && data[offset + 10] == 0x02);
    }

    /**
     * Reads the dynamic entries of a primer pack.
     *
     * @return the labels of the dynamic local tags
     */
    private static Map<Integer, byte[]> primer(byte[] data, int offset, int length) {
        if (length < 8) {
            throw new MediaException.Malformed("MXF primer pack of " + length + " bytes");
        }
        var count = u32(data, offset);
        var size = u32(data, offset + 4);
        if (size != 18 || count > (length - 8) / 18) {
            throw new MediaException.Malformed("invalid MXF primer pack batch");
        }
        var out = new HashMap<Integer, byte[]>();
        for (var index = 0; index < count; index++) {
            var at = offset + 8 + 18 * index;
            var tag = (data[at] & 0xFF) << 8 | data[at + 1] & 0xFF;
            if (tag >= 0x8000) {
                out.put(tag, slice(data, at + 2, 16));
            }
        }
        return out;
    }

    /**
     * Parses one local set whose items use only static tags, such as an index table segment.
     *
     * @param key the set key
     * @param value the set value
     * @return the set
     * @throws MediaException.Malformed if an item overruns the set
     */
    static MxfSet parseStatic(byte[] key, byte[] value) {
        var items = new HashMap<Integer, byte[]>();
        var at = 0;
        while (value.length - at >= 4) {
            var tag = (value[at] & 0xFF) << 8 | value[at + 1] & 0xFF;
            var itemLength = (value[at + 2] & 0xFF) << 8 | value[at + 3] & 0xFF;
            at += 4;
            if (itemLength > value.length - at) {
                throw new MediaException.Malformed("MXF local set item " + Integer.toHexString(tag)
                        + " overruns its set");
            }
            items.putIfAbsent(tag, slice(value, at, itemLength));
            at += itemLength;
        }
        return new MxfSet(key, items);
    }

    /**
     * {@return the set byte of the key (byte 14), which names the kind of set}
     */
    int kind() {
        return key[14] & 0xFF;
    }

    /**
     * {@return the set key}
     */
    byte[] key() {
        return key;
    }

    /**
     * {@return the value of an item, or {@code null}}
     *
     * @param tag the local tag
     */
    byte[] get(int tag) {
        return items.get(tag);
    }

    /**
     * {@return whether the set holds an item}
     *
     * @param tag the local tag
     */
    boolean has(int tag) {
        return items.containsKey(tag);
    }

    /**
     * {@return an unsigned 8-bit item, or {@code fallback}}
     *
     * @param tag the local tag
     * @param fallback the value of a missing item
     */
    int u8(int tag, int fallback) {
        var value = items.get(tag);
        return value == null || value.length < 1 ? fallback : value[0] & 0xFF;
    }

    /**
     * {@return an unsigned 16-bit item, or {@code fallback}}
     *
     * @param tag the local tag
     * @param fallback the value of a missing item
     */
    int u16(int tag, int fallback) {
        var value = items.get(tag);
        return value == null || value.length < 2 ? fallback : (value[0] & 0xFF) << 8 | value[1] & 0xFF;
    }

    /**
     * {@return an unsigned 32-bit item, or {@code fallback}}
     *
     * @param tag the local tag
     * @param fallback the value of a missing item
     */
    long u32(int tag, long fallback) {
        var value = items.get(tag);
        return value == null || value.length < 4 ? fallback : u32(value, 0);
    }

    /**
     * {@return a signed 64-bit item, or {@code fallback}}
     *
     * @param tag the local tag
     * @param fallback the value of a missing item
     */
    long s64(int tag, long fallback) {
        var value = items.get(tag);
        return value == null || value.length < 8 ? fallback : s64(value, 0);
    }

    /**
     * {@return a rational item as numerator and denominator, or {@code null}}
     *
     * @param tag the local tag
     */
    long[] rational(int tag) {
        var value = items.get(tag);
        if (value == null || value.length < 8) {
            return null;
        }
        return new long[]{(int) u32(value, 0), (int) u32(value, 4)};
    }

    /**
     * {@return a 16-byte label or UID item, or {@code null}}
     *
     * @param tag the local tag
     */
    byte[] label(int tag) {
        var value = items.get(tag);
        return value == null || value.length < 16 ? null : slice(value, 0, 16);
    }

    /**
     * {@return a 16-byte UID item as a map key, or {@code null}}
     *
     * @param tag the local tag
     */
    Uid uid(int tag) {
        var value = items.get(tag);
        return value == null || value.length < 16 ? null : Uid.of(value, 0);
    }

    /**
     * {@return a 32-byte UMID item, or {@code null}}
     *
     * @param tag the local tag
     */
    Umid umid(int tag) {
        var value = items.get(tag);
        return value == null || value.length < 32 ? null : Umid.of(value, 0);
    }

    /**
     * {@return a UTF-16BE string item without its terminating nulls, or {@code null}}
     *
     * @param tag the local tag
     */
    String string(int tag) {
        var value = items.get(tag);
        if (value == null) {
            return null;
        }
        var end = value.length & ~1;
        for (var index = 0; index + 1 < end; index += 2) {
            if (value[index] == 0 && value[index + 1] == 0) {
                end = index;
                break;
            }
        }
        return new String(value, 0, end, StandardCharsets.UTF_16BE);
    }

    /**
     * Returns the 16-byte elements of a batch or array item, such as strong references or labels.
     *
     * @param tag the local tag
     * @return the elements; empty when the item is missing or malformed
     */
    List<byte[]> batch(int tag) {
        var value = items.get(tag);
        if (value == null || value.length < 8) {
            return List.of();
        }
        var count = u32(value, 0);
        var size = u32(value, 4);
        if (size < 16 || count > (value.length - 8) / size) {
            return List.of();
        }
        var out = new ArrayList<byte[]>((int) count);
        for (var index = 0; index < count; index++) {
            out.add(slice(value, 8 + (int) (index * size), 16));
        }
        return out;
    }

    /**
     * {@return the UIDs of a batch or array of strong references, in order}
     *
     * @param tag the local tag
     */
    List<Uid> references(int tag) {
        var batch = batch(tag);
        var out = new ArrayList<Uid>(batch.size());
        for (var element : batch) {
            out.add(Uid.of(element, 0));
        }
        return out;
    }

    static long u32(byte[] data, int offset) {
        return ((data[offset] & 0xFFL) << 24) | (data[offset + 1] & 0xFF) << 16 | (data[offset + 2] & 0xFF) << 8
                | data[offset + 3] & 0xFF;
    }

    static long s64(byte[] data, int offset) {
        var value = 0L;
        for (var index = 0; index < 8; index++) {
            value = value << 8 | data[offset + index] & 0xFF;
        }
        return value;
    }

    static byte[] slice(byte[] data, int offset, int length) {
        var out = new byte[length];
        System.arraycopy(data, offset, out, 0, length);
        return out;
    }

    /**
     * A 16-byte UID or label usable as a map key.
     *
     * @param high the first eight bytes, big-endian
     * @param low the last eight bytes, big-endian
     */
    record Uid(long high, long low) {
        /**
         * {@return the UID of 16 bytes at an offset}
         *
         * @param data the array
         * @param offset the index of the first byte
         */
        static Uid of(byte[] data, int offset) {
            return new Uid(s64(data, offset), s64(data, offset + 8));
        }
    }

    /**
     * A 32-byte basic UMID usable as a map key.
     *
     * @param a bytes 0 to 7
     * @param b bytes 8 to 15
     * @param c bytes 16 to 23
     * @param d bytes 24 to 31
     */
    record Umid(long a, long b, long c, long d) {
        /**
         * {@return the UMID of 32 bytes at an offset}
         *
         * @param data the array
         * @param offset the index of the first byte
         */
        static Umid of(byte[] data, int offset) {
            return new Umid(s64(data, offset), s64(data, offset + 8), s64(data, offset + 16), s64(data, offset + 24));
        }

        /**
         * {@return whether every byte is zero, the UMID of no package}
         */
        boolean isZero() {
            return a == 0 && b == 0 && c == 0 && d == 0;
        }
    }
}
