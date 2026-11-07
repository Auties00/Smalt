package com.github.auties00.smalt.image.raw;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads the directory structure of a classic little- or big-endian TIFF file independently of the decoder, for the
 * DNG encoder's tests: every directory reachable from the first one through the chain, {@code SubIFDs}, the EXIF
 * and GPS pointers, with each entry's type, count and values.
 */
final class DngStructure {
    record Entry(int tag, int type, long count, long valueOffset, byte[] bytes) {
        long[] longs(ByteOrder order) {
            var buffer = ByteBuffer.wrap(bytes).order(order);
            var size = size(type);
            var values = new long[(int) count];
            for (var i = 0; i < values.length; i++) {
                values[i] = switch (type) {
                    case 1, 7 -> buffer.get(i) & 0xFF;
                    case 6 -> buffer.get(i);
                    case 3 -> buffer.getShort(i * 2) & 0xFFFF;
                    case 8 -> buffer.getShort(i * 2);
                    case 4, 13 -> buffer.getInt(i * 4) & 0xFFFFFFFFL;
                    case 9 -> buffer.getInt(i * 4);
                    default -> throw new IllegalStateException("type " + type + " of size " + size);
                };
            }
            return values;
        }

        double[] doubles(ByteOrder order) {
            var buffer = ByteBuffer.wrap(bytes).order(order);
            var values = new double[(int) count];
            for (var i = 0; i < values.length; i++) {
                values[i] = switch (type) {
                    case 5 -> (double) (buffer.getInt(i * 8) & 0xFFFFFFFFL) / (buffer.getInt(i * 8 + 4) & 0xFFFFFFFFL);
                    case 10 -> (double) buffer.getInt(i * 8) / buffer.getInt(i * 8 + 4);
                    case 11 -> buffer.getFloat(i * 4);
                    case 12 -> buffer.getDouble(i * 8);
                    default -> longs(order)[i];
                };
            }
            return values;
        }

        String text() {
            var end = 0;
            while (end < bytes.length && bytes[end] != 0) {
                end++;
            }
            return new String(bytes, 0, end, java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    record Directory(long offset, String path, Map<Integer, Entry> entries) {
        Entry get(int tag) {
            var entry = entries.get(tag);
            if (entry == null) {
                throw new AssertionError("directory " + path + " has no tag " + tag);
            }
            return entry;
        }

        boolean has(int tag) {
            return entries.containsKey(tag);
        }
    }

    final ByteOrder order;
    final byte[] data;
    final List<Directory> directories = new ArrayList<>();

    private DngStructure(byte[] data) {
        this.data = data;
        if (data[0] == 'I' && data[1] == 'I') {
            order = ByteOrder.LITTLE_ENDIAN;
        } else if (data[0] == 'M' && data[1] == 'M') {
            order = ByteOrder.BIG_ENDIAN;
        } else {
            throw new AssertionError("not a TIFF file");
        }
        var buffer = ByteBuffer.wrap(data).order(order);
        if (buffer.getShort(2) != 42) {
            throw new AssertionError("not a classic TIFF file");
        }
        var next = buffer.getInt(4) & 0xFFFFFFFFL;
        var index = 0;
        while (next != 0) {
            next = read(next, "ifd" + index++);
        }
    }

    static DngStructure of(byte[] data) {
        return new DngStructure(data);
    }

    Directory first() {
        return directories.getFirst();
    }

    /** Returns the directory holding the raw image: NewSubFileType 0. */
    Directory raw() {
        for (var directory : directories) {
            if (directory.has(254) && directory.get(254).longs(order)[0] == 0) {
                return directory;
            }
        }
        throw new AssertionError("no raw image directory");
    }

    List<Directory> previews() {
        var result = new ArrayList<Directory>();
        for (var directory : directories) {
            if (directory.has(254) && directory.get(254).longs(order)[0] == 1) {
                result.add(directory);
            }
        }
        return result;
    }

    private long read(long offset, String path) {
        var buffer = ByteBuffer.wrap(data).order(order);
        if (offset % 2 != 0) {
            throw new AssertionError("directory " + path + " at odd offset " + offset);
        }
        var count = buffer.getShort((int) offset) & 0xFFFF;
        var entries = new LinkedHashMap<Integer, Entry>();
        var previous = -1;
        for (var i = 0; i < count; i++) {
            var at = (int) offset + 2 + i * 12;
            var tag = buffer.getShort(at) & 0xFFFF;
            if (tag <= previous) {
                throw new AssertionError("directory " + path + " entries out of order at tag " + tag);
            }
            previous = tag;
            var type = buffer.getShort(at + 2) & 0xFFFF;
            var n = buffer.getInt(at + 4) & 0xFFFFFFFFL;
            var length = n * size(type);
            long valueOffset = at + 8;
            if (length > 4) {
                valueOffset = buffer.getInt(at + 8) & 0xFFFFFFFFL;
                if (valueOffset % 2 != 0) {
                    throw new AssertionError("tag " + tag + " value at odd offset");
                }
            }
            if (valueOffset + length > data.length) {
                throw new AssertionError("tag " + tag + " value past the end of the file");
            }
            var bytes = new byte[(int) length];
            System.arraycopy(data, (int) valueOffset, bytes, 0, bytes.length);
            entries.put(tag, new Entry(tag, type, n, valueOffset, bytes));
        }
        var directory = new Directory(offset, path, entries);
        directories.add(directory);
        for (var pointer : new int[]{330, 34665, 34853}) {
            var entry = entries.get(pointer);
            if (entry != null) {
                var children = entry.longs(order);
                for (var c = 0; c < children.length; c++) {
                    var child = children[c];
                    while (child != 0) {
                        child = read(child, path + "/" + pointer + "[" + c + "]");
                    }
                }
            }
        }
        return buffer.getInt((int) offset + 2 + count * 12) & 0xFFFFFFFFL;
    }

    static int size(int type) {
        return switch (type) {
            case 1, 2, 6, 7 -> 1;
            case 3, 8 -> 2;
            case 4, 9, 11, 13 -> 4;
            case 5, 10, 12 -> 8;
            default -> throw new AssertionError("unknown TIFF type " + type);
        };
    }
}
