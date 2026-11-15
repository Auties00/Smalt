package com.github.auties00.smalt.image.metadata;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A TIFF and BigTIFF structure reader and builder written for the tests, independent of the
 * library's own directory reader: it lists every directory reachable from the header (the page
 * chain, {@code SubIFDs}, Exif, GPS and interoperability directories), and builds small files
 * with chosen entries.
 */
final class TiffStructure {
    static final int EXIF = 34665;
    static final int GPS = 34853;
    static final int INTEROP = 40965;
    static final int SUB_IFDS = 330;

    private TiffStructure() {
    }

    /** One directory entry; {@code valueOffset} is -1 for an inline value. */
    record Entry(int tag, int type, long count, long fieldPosition, long valueOffset, long length) {
        boolean inline() {
            return valueOffset < 0;
        }
    }

    /** One directory, with the byte after its next-directory pointer. */
    record Directory(long offset, long end, List<Entry> entries, long next) {
        Entry find(int tag) {
            return entries.stream().filter(entry -> entry.tag() == tag).findFirst().orElse(null);
        }
    }

    /** A parsed file: the pages in chain order, and every directory by offset with its role. */
    record Parsed(byte[] data, boolean little, boolean big, List<Directory> pages, Map<Long, Directory> directories,
                  Map<Long, String> roles) {
        Directory ifd0() {
            return pages.getFirst();
        }

        Directory child(Directory parent, int tag) {
            var entry = parent.find(tag);
            return entry == null ? null : directories.get(integers(entry)[0]);
        }

        byte[] value(Entry entry) {
            var at = entry.inline() ? entry.fieldPosition() : entry.valueOffset();
            return Arrays.copyOfRange(data, (int) at, (int) (at + entry.length()));
        }

        long[] integers(Entry entry) {
            var bytes = value(entry);
            var size = size(entry.type());
            var result = new long[(int) entry.count()];
            for (var i = 0; i < result.length; i++) {
                result[i] = read(bytes, i * size, size, little);
            }
            return result;
        }

        /** Returns the strip or tile data of a directory, concatenated. */
        byte[] imageData(Directory directory) {
            var out = new ByteArrayOutputStream();
            for (var pair : new int[][]{{273, 279}, {324, 325}}) {
                var offsets = directory.find(pair[0]);
                var counts = directory.find(pair[1]);
                if (offsets != null && counts != null) {
                    var starts = integers(offsets);
                    var lengths = integers(counts);
                    for (var i = 0; i < starts.length; i++) {
                        out.write(data, (int) starts[i], (int) lengths[i]);
                    }
                }
            }
            return out.toByteArray();
        }
    }

    static int size(int type) {
        return switch (type) {
            case 1, 2, 6, 7 -> 1;
            case 3, 8 -> 2;
            case 4, 9, 11, 13 -> 4;
            case 5, 10, 12, 16, 17, 18 -> 8;
            default -> 0;
        };
    }

    static long read(byte[] data, int at, int size, boolean little) {
        var value = 0L;
        for (var i = 0; i < size; i++) {
            var b = data[at + (little ? size - 1 - i : i)] & 0xFFL;
            value = (value << 8) | b;
        }
        return value;
    }

    static void write(byte[] data, int at, int size, long value, boolean little) {
        for (var i = 0; i < size; i++) {
            data[at + (little ? i : size - 1 - i)] = (byte) (value >>> (8 * i));
        }
    }

    /** Parses a well-formed file, failing on anything out of range. */
    static Parsed parse(byte[] data) {
        var little = data[0] == 'I';
        var version = (int) read(data, 2, 2, little);
        if (version != 42 && version != 43) {
            throw new AssertionError("not a TIFF file");
        }
        var big = version == 43;
        var first = big ? read(data, 8, 8, little) : read(data, 4, 4, little);
        var directories = new LinkedHashMap<Long, Directory>();
        var roles = new LinkedHashMap<Long, String>();
        var pages = new ArrayList<Directory>();
        var offset = first;
        var page = 0;
        while (offset != 0) {
            if (directories.containsKey(offset)) {
                throw new AssertionError("directory loop at " + offset);
            }
            var directory = directory(data, offset, little, big);
            directories.put(offset, directory);
            roles.put(offset, "page " + page);
            pages.add(directory);
            children(data, directory, little, big, directories, roles, "page " + page);
            offset = directory.next();
            page++;
        }
        return new Parsed(data, little, big, pages, directories, roles);
    }

    private static void children(byte[] data, Directory directory, boolean little, boolean big,
                                 Map<Long, Directory> directories, Map<Long, String> roles, String role) {
        for (var entry : directory.entries()) {
            if (entry.tag() != EXIF && entry.tag() != GPS && entry.tag() != INTEROP && entry.tag() != SUB_IFDS) {
                continue;
            }
            var size = size(entry.type());
            var at = entry.inline() ? entry.fieldPosition() : entry.valueOffset();
            for (var i = 0; i < entry.count(); i++) {
                var offset = read(data, (int) (at + i * size), size, little);
                if (offset == 0 || directories.containsKey(offset)) {
                    continue;
                }
                var child = directory(data, offset, little, big);
                directories.put(offset, child);
                var name = role + "/" + entry.tag();
                roles.put(offset, name);
                children(data, child, little, big, directories, roles, name);
            }
        }
    }

    static Directory directory(byte[] data, long offset, boolean little, boolean big) {
        var countSize = big ? 8 : 2;
        var entrySize = big ? 20 : 12;
        var fieldSize = big ? 8 : 4;
        var count = (int) read(data, (int) offset, countSize, little);
        var entries = new ArrayList<Entry>();
        for (var i = 0; i < count; i++) {
            var at = (int) offset + countSize + i * entrySize;
            var tag = (int) read(data, at, 2, little);
            var type = (int) read(data, at + 2, 2, little);
            var values = read(data, at + 4, big ? 8 : 4, little);
            var field = at + (big ? 12 : 8);
            var length = values * size(type);
            var valueOffset = length <= fieldSize ? -1 : read(data, field, fieldSize, little);
            if (valueOffset + length > data.length) {
                throw new AssertionError("tag " + tag + " at " + offset + " points past the end");
            }
            entries.add(new Entry(tag, type, values, field, valueOffset, length));
        }
        var nextAt = (int) offset + countSize + count * entrySize;
        var next = read(data, nextAt, fieldSize, little);
        for (var i = 1; i < entries.size(); i++) {
            if (entries.get(i).tag() <= entries.get(i - 1).tag()) {
                throw new AssertionError("directory at " + offset + " is not sorted by tag");
            }
        }
        return new Directory(offset, nextAt + fieldSize, List.copyOf(entries), next);
    }

    /** An entry to build: a value of a type, or a sub-directory its pointer entry points at. */
    record Field(int tag, int type, byte[] value, List<Field> directory) {
        static Field of(int tag, int type, byte[] value) {
            return new Field(tag, type, value, null);
        }

        static Field ascii(int tag, String text) {
            var bytes = Arrays.copyOf(text.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1), text.length() + 1);
            return of(tag, 2, bytes);
        }

        static Field dir(int tag, List<Field> fields) {
            return new Field(tag, 4, null, fields);
        }
    }

    /** Encodes integers of a type size in a byte order. */
    static byte[] ints(boolean little, int size, long... values) {
        var out = new byte[values.length * size];
        for (var i = 0; i < values.length; i++) {
            write(out, i * size, size, values[i], little);
        }
        return out;
    }

    /**
     * Builds a file of 8x8 8-bit gray pages, one uncompressed strip each, whose IFDs hold the
     * layout entries plus the given ones; the pixels of page {@code p} are {@code (x * 8 + y + p) & 0xFF}.
     */
    static byte[] build(boolean little, boolean big, List<List<Field>> pages) {
        var out = new Builder(little, big);
        out.header();
        var pointerAt = big ? 8 : 4;
        for (var p = 0; p < pages.size(); p++) {
            var pixels = new byte[64];
            for (var i = 0; i < 64; i++) {
                pixels[i] = (byte) (i + p);
            }
            var strip = out.append(pixels);
            var fields = new ArrayList<Field>(List.of(
                    Field.of(256, 3, ints(little, 2, 8)), Field.of(257, 3, ints(little, 2, 8)),
                    Field.of(258, 3, ints(little, 2, 8)), Field.of(259, 3, ints(little, 2, 1)),
                    Field.of(262, 3, ints(little, 2, 1)), Field.of(273, 4, ints(little, 4, strip)),
                    Field.of(277, 3, ints(little, 2, 1)), Field.of(278, 3, ints(little, 2, 8)),
                    Field.of(279, 4, ints(little, 4, 64))));
            fields.addAll(pages.get(p));
            var offset = out.directory(fields);
            out.patch(pointerAt, offset);
            pointerAt = (int) out.nextPointer;
        }
        return out.bytes();
    }

    private static final class Builder {
        final boolean little;
        final boolean big;
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final List<long[]> patches = new ArrayList<>();
        long nextPointer;

        Builder(boolean little, boolean big) {
            this.little = little;
            this.big = big;
        }

        void header() {
            var header = new byte[big ? 16 : 8];
            header[0] = header[1] = (byte) (little ? 'I' : 'M');
            write(header, 2, 2, big ? 43 : 42, little);
            if (big) {
                write(header, 4, 2, 8, little);
            }
            out.writeBytes(header);
        }

        long append(byte[] data) {
            if ((out.size() & 1) != 0) {
                out.write(0);
            }
            var at = out.size();
            out.writeBytes(data);
            return at;
        }

        void patch(long at, long value) {
            patches.add(new long[]{at, value});
        }

        long directory(List<Field> fields) {
            var sorted = new TreeMap<Integer, Field>();
            for (var field : fields) {
                sorted.put(field.tag(), field);
            }
            var fieldSize = big ? 8 : 4;
            var values = new LinkedHashMap<Integer, Long>();
            for (var field : sorted.values()) {
                if (field.directory() != null) {
                    values.put(field.tag(), directory(field.directory()));
                } else if (field.value().length > fieldSize) {
                    values.put(field.tag(), append(field.value()));
                }
            }
            if ((out.size() & 1) != 0) {
                out.write(0);
            }
            var offset = out.size();
            var table = new byte[(big ? 16 : 6) + sorted.size() * (big ? 20 : 12)];
            write(table, 0, big ? 8 : 2, sorted.size(), little);
            var at = big ? 8 : 2;
            for (var field : sorted.values()) {
                write(table, at, 2, field.tag(), little);
                if (field.directory() != null) {
                    write(table, at + 2, 2, big ? 18 : 4, little);
                    write(table, at + 4, big ? 8 : 4, 1, little);
                    write(table, at + (big ? 12 : 8), fieldSize, values.get(field.tag()), little);
                } else {
                    write(table, at + 2, 2, field.type(), little);
                    var count = size(field.type()) == 0 ? 1 : field.value().length / size(field.type());
                    write(table, at + 4, big ? 8 : 4, count, little);
                    if (field.value().length > fieldSize) {
                        write(table, at + (big ? 12 : 8), fieldSize, values.get(field.tag()), little);
                    } else {
                        System.arraycopy(field.value(), 0, table, at + (big ? 12 : 8), field.value().length);
                    }
                }
                at += big ? 20 : 12;
            }
            out.writeBytes(table);
            nextPointer = offset + at;
            return offset;
        }

        byte[] bytes() {
            var data = out.toByteArray();
            for (var patch : patches) {
                write(data, (int) patch[0], big ? 8 : 4, patch[1], little);
            }
            return data;
        }
    }
}
