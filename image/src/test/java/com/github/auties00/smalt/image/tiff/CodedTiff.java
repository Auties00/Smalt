package com.github.auties00.smalt.image.tiff;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;

/**
 * Builds little-endian classic TIFF files whose strips or tiles are given already coded, and reads
 * the coded strips or tiles back out of a file, independently of the decoder under test.
 */
final class CodedTiff {
    private static final Set<Integer> SHORT_TAGS = Set.of(258, 259, 262, 266, 277, 284, 317, 338, 339, 530);

    private final int width;
    private final int height;
    private final int samples;
    private final int bits;
    private final int compression;
    private int photometric;
    private int format = 1;
    private int[] extra = new int[0];
    private int chunkWidth;
    private int chunkHeight;
    private boolean tiled;
    private boolean planar;
    private final TreeMap<Integer, long[]> tags = new TreeMap<>();
    private List<byte[]> chunks = List.of();

    private CodedTiff(int width, int height, int samples, int bits, int compression) {
        this.width = width;
        this.height = height;
        this.samples = samples;
        this.bits = bits;
        this.compression = compression;
        this.photometric = samples >= 3 ? 2 : 1;
        this.chunkWidth = width;
        this.chunkHeight = height;
    }

    static CodedTiff of(int width, int height, int samples, int bits, int compression) {
        return new CodedTiff(width, height, samples, bits, compression);
    }

    CodedTiff photometric(int value) {
        photometric = value;
        return this;
    }

    CodedTiff format(int value) {
        format = value;
        return this;
    }

    CodedTiff extra(int... values) {
        extra = values;
        return this;
    }

    CodedTiff tiles(int tileWidth, int tileHeight) {
        tiled = true;
        chunkWidth = tileWidth;
        chunkHeight = tileHeight;
        return this;
    }

    CodedTiff strips(int rows) {
        tiled = false;
        chunkWidth = width;
        chunkHeight = rows;
        return this;
    }

    CodedTiff planar() {
        planar = true;
        return this;
    }

    CodedTiff tag(int tag, long... values) {
        tags.put(tag, values);
        return this;
    }

    /** Sets the coded strips or tiles, plane-major then row-major. */
    CodedTiff chunks(List<byte[]> coded) {
        chunks = List.copyOf(coded);
        return this;
    }

    int across() {
        return (width + chunkWidth - 1) / chunkWidth;
    }

    int down() {
        return (height + chunkHeight - 1) / chunkHeight;
    }

    byte[] bytes() {
        var data = new ByteArrayOutputStream();
        var offsets = new long[chunks.size()];
        var counts = new long[chunks.size()];
        for (var i = 0; i < chunks.size(); i++) {
            offsets[i] = 8 + data.size();
            counts[i] = chunks.get(i).length;
            data.writeBytes(chunks.get(i));
            if ((data.size() & 1) != 0) {
                data.write(0);
            }
        }
        var entries = new TreeMap<Integer, long[]>();
        entries.put(256, new long[]{width});
        entries.put(257, new long[]{height});
        entries.put(258, filled(bits));
        entries.put(259, new long[]{compression});
        entries.put(262, new long[]{photometric});
        entries.put(277, new long[]{samples});
        entries.put(284, new long[]{planar ? 2 : 1});
        if (tiled) {
            entries.put(322, new long[]{chunkWidth});
            entries.put(323, new long[]{chunkHeight});
            entries.put(324, offsets);
            entries.put(325, counts);
        } else {
            entries.put(273, offsets);
            entries.put(278, new long[]{chunkHeight});
            entries.put(279, counts);
        }
        if (extra.length > 0) {
            var values = new long[extra.length];
            for (var i = 0; i < extra.length; i++) {
                values[i] = extra[i];
            }
            entries.put(338, values);
        }
        if (format != 1) {
            entries.put(339, filled(format));
        }
        entries.putAll(tags);
        var ifdOffset = 8 + data.size();
        var out = new ByteArrayOutputStream();
        out.writeBytes(new byte[]{'I', 'I', 42, 0});
        write(out, ifdOffset, 4);
        out.writeBytes(data.toByteArray());
        write(out, entries.size(), 2);
        var overflowOffset = ifdOffset + 2 + entries.size() * 12 + 4;
        var overflow = new ByteArrayOutputStream();
        for (var entry : entries.entrySet()) {
            var size = SHORT_TAGS.contains(entry.getKey()) ? 2 : 4;
            var value = new ByteArrayOutputStream();
            for (var v : entry.getValue()) {
                write(value, v, size);
            }
            write(out, entry.getKey(), 2);
            write(out, size == 2 ? 3 : 4, 2);
            write(out, entry.getValue().length, 4);
            if (value.size() <= 4) {
                out.writeBytes(value.toByteArray());
                out.writeBytes(new byte[4 - value.size()]);
            } else {
                write(out, overflowOffset + overflow.size(), 4);
                overflow.writeBytes(value.toByteArray());
            }
        }
        write(out, 0, 4);
        out.writeBytes(overflow.toByteArray());
        return out.toByteArray();
    }

    private long[] filled(int value) {
        var values = new long[samples];
        java.util.Arrays.fill(values, value);
        return values;
    }

    private static void write(ByteArrayOutputStream out, long value, int size) {
        for (var i = 0; i < size; i++) {
            out.write((int) (value >>> (8 * i)));
        }
    }

    /** Returns the coded strips or tiles of the first directory of a classic TIFF, in order. */
    static List<byte[]> read(byte[] file) {
        var little = file[0] == 'I';
        var ifd = (int) number(file, 4, 4, little);
        var count = (int) number(file, ifd, 2, little);
        long[] offsets = null;
        long[] counts = null;
        for (var i = 0; i < count; i++) {
            var at = ifd + 2 + i * 12;
            var tag = (int) number(file, at, 2, little);
            var type = (int) number(file, at + 2, 2, little);
            var n = (int) number(file, at + 4, 4, little);
            var size = type == 3 ? 2 : 4;
            var base = n * size <= 4 ? at + 8 : (int) number(file, at + 8, 4, little);
            var values = new long[n];
            for (var k = 0; k < n; k++) {
                values[k] = number(file, base + k * size, size, little);
            }
            if (tag == 273 || tag == 324) {
                offsets = values;
            } else if (tag == 279 || tag == 325) {
                counts = values;
            }
        }
        var result = new ArrayList<byte[]>();
        for (var i = 0; i < offsets.length; i++) {
            result.add(java.util.Arrays.copyOfRange(file, (int) offsets[i], (int) (offsets[i] + counts[i])));
        }
        return result;
    }

    private static long number(byte[] data, int at, int size, boolean little) {
        var value = 0L;
        for (var i = 0; i < size; i++) {
            var b = data[at + (little ? i : size - 1 - i)] & 0xFFL;
            value |= b << (8 * i);
        }
        return value;
    }
}
