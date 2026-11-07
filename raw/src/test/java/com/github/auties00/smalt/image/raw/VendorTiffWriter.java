package com.github.auties00.smalt.image.raw;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.LongFunction;

/**
 * Builds TIFF/EP-structured vendor raw files for the tests: a first directory with the camera's make and model, an
 * EXIF directory holding a maker note, and the raw image directory as the first directory's SubIFD, followed by the
 * maker note and the strip of sensor data.
 */
public final class VendorTiffWriter {
    public boolean little = true;
    public String make = "TEST";
    public String model = "TEST";
    public int width = 16;
    public int height = 8;
    public int bits = 12;
    public int samplesPerPixel = 1;
    public int compression = 1;
    /** The photometric interpretation of the raw directory, or 0 to leave the tag out. */
    public int photometric = 32803;
    public byte[] cfa = {0, 1, 1, 2};
    public byte[] strip = new byte[0];
    /** Builds the maker note from its absolute offset in the file, or is {@code null} for none. */
    public LongFunction<byte[]> makerNote;
    /** Further entries of the raw image directory. */
    public final List<Entry> rawEntries = new ArrayList<>();

    /**
     * One directory entry.
     *
     * @param tag the tag
     * @param type the TIFF type
     * @param count the value count
     * @param value the value bytes in the file's byte order
     */
    public record Entry(int tag, int type, int count, byte[] value) {
    }

    /** {@return a SHORT entry} */
    public Entry shorts(int tag, int... values) {
        var buffer = ByteBuffer.allocate(2 * values.length).order(order());
        for (var value : values) {
            buffer.putShort((short) value);
        }
        return new Entry(tag, 3, values.length, buffer.array());
    }

    /** {@return a LONG entry} */
    public Entry longs(int tag, long... values) {
        var buffer = ByteBuffer.allocate(4 * values.length).order(order());
        for (var value : values) {
            buffer.putInt((int) value);
        }
        return new Entry(tag, 4, values.length, buffer.array());
    }

    /** {@return an SLONG entry} */
    public Entry slongs(int tag, long... values) {
        var entry = longs(tag, values);
        return new Entry(tag, 9, entry.count(), entry.value());
    }

    /** {@return an UNDEFINED entry} */
    public static Entry undefined(int tag, byte[] value) {
        return new Entry(tag, 7, value.length, value);
    }

    /** {@return an ASCII entry} */
    public static Entry ascii(int tag, String value) {
        var bytes = (value + "\0").getBytes(StandardCharsets.US_ASCII);
        return new Entry(tag, 2, bytes.length, bytes);
    }

    /** {@return the byte order of the file} */
    public ByteOrder order() {
        return little ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN;
    }

    /**
     * Builds a TIFF directory whose values follow it, its offsets relative to a base.
     *
     * @param entries the entries
     * @param start the offset of the directory relative to the base
     * @param order the byte order
     * @return the directory and its values
     */
    public static byte[] directory(List<Entry> entries, long start, ByteOrder order) {
        var sorted = new ArrayList<>(entries);
        sorted.sort(Comparator.comparingInt(Entry::tag));
        var size = 2 + 12 * sorted.size() + 4;
        var values = new ByteArrayOutputStream();
        var buffer = ByteBuffer.allocate(size).order(order);
        buffer.putShort((short) sorted.size());
        for (var entry : sorted) {
            buffer.putShort((short) entry.tag()).putShort((short) entry.type()).putInt(entry.count());
            if (entry.value().length <= 4) {
                var inline = new byte[4];
                System.arraycopy(entry.value(), 0, inline, 0, entry.value().length);
                buffer.put(inline);
            } else {
                buffer.putInt((int) (start + size + values.size()));
                values.writeBytes(entry.value());
                if ((values.size() & 1) != 0) {
                    values.write(0);
                }
            }
        }
        buffer.putInt(0);
        var out = new ByteArrayOutputStream();
        out.writeBytes(buffer.array());
        out.writeBytes(values.toByteArray());
        return out.toByteArray();
    }

    /**
     * Writes the file.
     *
     * @return the file's bytes
     */
    public byte[] write() {
        var order = order();
        // the layout: header, first directory, EXIF directory, raw directory, maker note, strip; offsets are found
        // by building each part once with placeholder offsets, whose sizes do not depend on them
        var first = firstEntries(0, 0);
        var firstSize = directory(first, 8, order).length;
        var exifOffset = 8 + firstSize;
        var exifSize = directory(List.of(undefined(37500, new byte[8])), exifOffset, order).length;
        var rawOffset = exifOffset + exifSize;
        var rawSize = directory(rawEntries(0), rawOffset, order).length;
        var noteOffset = rawOffset + rawSize;
        var note = makerNote == null ? new byte[0] : makerNote.apply(noteOffset);
        var stripOffset = noteOffset + note.length + (note.length & 1);
        var out = new ByteArrayOutputStream();
        out.writeBytes(ByteBuffer.allocate(8).order(order)
                .put((byte) (little ? 'I' : 'M')).put((byte) (little ? 'I' : 'M')).putShort((short) 42).putInt(8)
                .array());
        out.writeBytes(directory(firstEntries(exifOffset, rawOffset), 8, order));
        var exif = new ArrayList<Entry>();
        if (note.length > 0) {
            exif.add(new Entry(37500, 7, note.length, note));
        } else {
            exif.add(longs(34855, 100));
        }
        var exifBytes = directory(exif, exifOffset, order);
        // the maker note's value lies at its own offset, not among the EXIF directory's values
        if (note.length > 0) {
            ByteBuffer.wrap(exifBytes).order(order).putInt(2 + 8, noteOffset);
            exifBytes = java.util.Arrays.copyOf(exifBytes, 2 + 12 + 4);
        }
        out.writeBytes(exifBytes);
        while (out.size() < rawOffset) {
            out.write(0);
        }
        out.writeBytes(directory(rawEntries(stripOffset), rawOffset, order));
        while (out.size() < noteOffset) {
            out.write(0);
        }
        out.writeBytes(note);
        while (out.size() < stripOffset) {
            out.write(0);
        }
        out.writeBytes(strip);
        return out.toByteArray();
    }

    private List<Entry> firstEntries(long exifOffset, long rawOffset) {
        var entries = new ArrayList<Entry>();
        entries.add(longs(254, 1));
        entries.add(ascii(271, make));
        entries.add(ascii(272, model));
        entries.add(longs(330, rawOffset));
        entries.add(longs(34665, exifOffset));
        return entries;
    }

    private List<Entry> rawEntries(long stripOffset) {
        var entries = new ArrayList<Entry>();
        entries.add(longs(254, 0));
        entries.add(longs(256, width));
        entries.add(longs(257, height));
        entries.add(shorts(258, bits));
        entries.add(shorts(259, compression));
        if (photometric != 0) {
            entries.add(shorts(262, photometric));
        }
        entries.add(longs(273, stripOffset));
        entries.add(shorts(277, samplesPerPixel));
        entries.add(longs(278, height));
        entries.add(longs(279, strip.length));
        entries.add(shorts(33421, 2, 2));
        entries.add(new Entry(33422, 1, cfa.length, cfa));
        entries.addAll(rawEntries);
        return entries;
    }
}
