package com.github.auties00.smalt.testkit.image;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lays out EXIF blocks for the tests: a header, then each directory followed by its out-of-line values, in the
 * order IFD0, Exif, GPS, IFD1, then the thumbnail, so that every offset is known and an edit can be checked byte
 * by byte.
 */
public final class ExifBlockBuilder {
    public static final int ORIENTATION = 0x0112;
    public static final int EXIF_POINTER = 0x8769;
    public static final int GPS_POINTER = 0x8825;
    public static final int PIXEL_X_DIMENSION = 0xA002;
    public static final int PIXEL_Y_DIMENSION = 0xA003;
    public static final int MAKE = 0x010F;
    public static final int MODEL = 0x0110;
    public static final int X_RESOLUTION = 0x011A;
    public static final int DATE_TIME_ORIGINAL = 0x9003;
    public static final int MAKER_NOTE = 0x927C;
    public static final int GPS_LATITUDE = 0x0002;
    public static final int GPS_LONGITUDE = 0x0004;
    public static final int COMPRESSION = 0x0103;
    public static final int THUMBNAIL_OFFSET = 0x0201;
    public static final int THUMBNAIL_LENGTH = 0x0202;

    /**
     * One entry: its tag, type, count and value bytes (in the block's byte order), or a pointer placeholder.
     */
    public record Entry(int tag, int type, long count, byte[] value, String pointsAt) {
    }

    private final boolean little;
    private final Map<String, List<Entry>> directories = new LinkedHashMap<>();
    private byte[] thumbnail;

    /**
     * The offsets assigned by the last {@link #build()}: of each directory by name, and of each out-of-line
     * value as {@code name:tag}.
     */
    public final Map<String, Integer> offsets = new LinkedHashMap<>();

    public ExifBlockBuilder(boolean little) {
        this.little = little;
        directories.put("ifd0", new ArrayList<>());
    }

    public ExifBlockBuilder add(String directory, int tag, int type, long count, byte[] value) {
        directories.computeIfAbsent(directory, _ -> new ArrayList<>()).add(new Entry(tag, type, count, value, null));
        return this;
    }

    public ExifBlockBuilder ascii(String directory, int tag, String text) {
        var bytes = (text + "\0").getBytes(StandardCharsets.US_ASCII);
        return add(directory, tag, 2, bytes.length, bytes);
    }

    public ExifBlockBuilder shortValue(String directory, int tag, int value) {
        var bytes = new byte[2];
        put(bytes, 0, value, 2);
        return add(directory, tag, 3, 1, bytes);
    }

    public ExifBlockBuilder longValue(String directory, int tag, long value) {
        var bytes = new byte[4];
        put(bytes, 0, value, 4);
        return add(directory, tag, 4, 1, bytes);
    }

    public ExifBlockBuilder rationals(String directory, int tag, long... terms) {
        var bytes = new byte[terms.length * 4];
        for (var i = 0; i < terms.length; i++) {
            put(bytes, i * 4, terms[i], 4);
        }
        return add(directory, tag, 5, terms.length / 2, bytes);
    }

    public ExifBlockBuilder pointer(String from, int tag, String to) {
        directories.computeIfAbsent(from, _ -> new ArrayList<>()).add(new Entry(tag, 4, 1, null, to));
        directories.computeIfAbsent(to, _ -> new ArrayList<>());
        return this;
    }

    public ExifBlockBuilder thumbnail(byte[] jpeg) {
        this.thumbnail = jpeg;
        directories.computeIfAbsent("ifd1", _ -> new ArrayList<>());
        return this;
    }

    /**
     * A block with the usual camera content: make, model, orientation, resolution, Exif directory with the
     * capture time, the picture size and a maker note, a GPS directory with a position, and a JPEG thumbnail.
     */
    public static ExifBlockBuilder camera(boolean little, int orientation) {
        var builder = new ExifBlockBuilder(little).ascii("ifd0", MAKE, "SmaltCam").ascii("ifd0", MODEL, "Model X1");
        if (orientation > 0) {
            builder.shortValue("ifd0", ORIENTATION, orientation);
        }
        builder.rationals("ifd0", X_RESOLUTION, 300, 1).pointer("ifd0", EXIF_POINTER, "exif")
                .pointer("ifd0", GPS_POINTER, "gps")
                .ascii("exif", DATE_TIME_ORIGINAL, "2026:10:25 12:34:56")
                .add("exif", MAKER_NOTE, 7, 40, makerNote())
                .shortValue("exif", PIXEL_X_DIMENSION, 640)
                .shortValue("exif", PIXEL_Y_DIMENSION, 480)
                .add("gps", 0x0000, 1, 4, new byte[]{2, 3, 0, 0})
                .ascii("gps", 0x0001, "N")
                .rationals("gps", GPS_LATITUDE, 45, 1, 27, 1, 3123, 100)
                .ascii("gps", 0x0003, "E")
                .rationals("gps", GPS_LONGITUDE, 9, 1, 11, 1, 4567, 100);
        var jpeg = new byte[200];
        jpeg[0] = (byte) 0xFF;
        jpeg[1] = (byte) 0xD8;
        for (var i = 2; i < 198; i++) {
            jpeg[i] = (byte) (0x40 + i % 37);
        }
        jpeg[198] = (byte) 0xFF;
        jpeg[199] = (byte) 0xD9;
        builder.thumbnail(jpeg);
        return builder;
    }

    /**
     * {@return a maker note whose bytes are all nonzero, so that a test can find it anywhere}
     */
    public static byte[] makerNote() {
        var note = new byte[40];
        var tag = "SMALTNOTE".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(tag, 0, note, 0, tag.length);
        for (var i = tag.length; i < note.length; i++) {
            note[i] = (byte) (0x61 + i % 26);
        }
        return note;
    }

    public byte[] build() {
        offsets.clear();
        var cursor = 8;
        var layout = new LinkedHashMap<String, List<Entry>>();
        for (var directory : directories.entrySet()) {
            var entries = new ArrayList<>(directory.getValue());
            if (directory.getKey().equals("ifd1")) {
                entries.add(0, new Entry(COMPRESSION, 3, 1, shortBytes(6), null));
                entries.add(new Entry(THUMBNAIL_OFFSET, 4, 1, null, "thumbnail"));
                entries.add(new Entry(THUMBNAIL_LENGTH, 4, 1, longBytes(thumbnail.length), null));
            }
            layout.put(directory.getKey(), entries);
        }
        var names = new ArrayList<>(layout.keySet());
        for (var name : names) {
            var entries = layout.get(name);
            offsets.put(name, cursor);
            cursor += 2 + 12 * entries.size() + 4;
            for (var entry : entries) {
                if (entry.value() != null && entry.value().length > 4) {
                    offsets.put(name + ":" + entry.tag(), cursor);
                    cursor += (entry.value().length + 1) & ~1;
                }
            }
        }
        if (thumbnail != null) {
            offsets.put("thumbnail", cursor);
            cursor += thumbnail.length;
        }
        var block = new byte[cursor];
        block[0] = (byte) (little ? 'I' : 'M');
        block[1] = block[0];
        put(block, 2, 42, 2);
        put(block, 4, 8, 4);
        for (var index = 0; index < names.size(); index++) {
            var name = names.get(index);
            var entries = layout.get(name);
            var at = offsets.get(name);
            put(block, at, entries.size(), 2);
            for (var i = 0; i < entries.size(); i++) {
                var entry = entries.get(i);
                var position = at + 2 + 12 * i;
                put(block, position, entry.tag(), 2);
                put(block, position + 2, entry.type(), 2);
                put(block, position + 4, entry.count(), 4);
                if (entry.pointsAt() != null) {
                    put(block, position + 8, offsets.get(entry.pointsAt()), 4);
                } else if (entry.value().length > 4) {
                    var valueAt = offsets.get(name + ":" + entry.tag());
                    put(block, position + 8, valueAt, 4);
                    System.arraycopy(entry.value(), 0, block, valueAt, entry.value().length);
                } else {
                    System.arraycopy(entry.value(), 0, block, position + 8, entry.value().length);
                }
            }
            var next = name.equals("ifd0") && offsets.containsKey("ifd1") ? offsets.get("ifd1") : 0;
            put(block, at + 2 + 12 * entries.size(), next, 4);
        }
        if (thumbnail != null) {
            System.arraycopy(thumbnail, 0, block, offsets.get("thumbnail"), thumbnail.length);
        }
        return block;
    }

    private byte[] shortBytes(int value) {
        var bytes = new byte[2];
        put(bytes, 0, value, 2);
        return bytes;
    }

    private byte[] longBytes(long value) {
        var bytes = new byte[4];
        put(bytes, 0, value, 4);
        return bytes;
    }

    private void put(byte[] data, int at, long value, int size) {
        for (var i = 0; i < size; i++) {
            var shift = little ? 8 * i : 8 * (size - 1 - i);
            data[at + i] = (byte) (value >>> shift);
        }
    }

    public static byte[] concat(byte[]... parts) {
        var out = new ByteArrayOutputStream();
        for (var part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }
}
