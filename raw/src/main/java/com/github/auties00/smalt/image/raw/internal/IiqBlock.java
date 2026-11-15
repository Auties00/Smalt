package com.github.auties00.smalt.image.raw.internal;

import com.github.auties00.smalt.MediaException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads the directory of Phase One's block, which starts at byte 8 of a file and to which its offsets are relative.
 * {@snippet :
 * 0     "IIII", "CwaR"
 * 8     u32 the directory's offset
 * dir   u32 count, u32 0, then per entry: u32 tag, u32 unit, u32 length, u32 value or offset
 * }
 * The entry of the calibration holds a similar block whose directory has entries of a tag, a length and an offset; its
 * tags repeat.
 *
 * @implNote This implementation's meanings of the tags were derived from CC0 sample files, with Adobe DNG Converter
 * run as a black box on copies whose entries were renamed.
 */
final class IiqBlock {
    /**
     * The offset of the block in a file.
     */
    private static final int BASE = 8;

    /**
     * The largest number of directory entries read.
     */
    private static final int MAX_ENTRIES = 4096;

    /**
     * Tag: the as-shot white balance, as three floats (red, green, blue gains).
     */
    static final int BALANCE = 0x107;

    /**
     * Tags: the sensor's width and height; the image's left and top; its width and height; the compression.
     */
    static final int WIDTH = 0x108, HEIGHT = 0x109, LEFT = 0x10A, TOP = 0x10B, IMAGE_WIDTH = 0x10C, IMAGE_HEIGHT = 0x10D,
            COMPRESSION = 0x10E;

    /**
     * Tag: the sensor data, at the entry's offset and of its length.
     */
    static final int DATA = 0x10F;

    /**
     * Tag: whether the sensor is mounted across the image (1), so the picture is turned a quarter.
     */
    static final int FORMAT = 0x100;

    /**
     * Tag: the calibration block.
     */
    static final int CALIBRATION = 0x110;

    /**
     * Tags: the black level; the row table.
     */
    static final int BLACK = 0x21D, ROWS = 0x21C;

    /**
     * Tag: the black number of each row, for backs with a single output.
     */
    static final int ROW_BLACK_SINGLE = 0x21F;

    /**
     * Tags: the column and row where the outputs meet.
     */
    static final int SPLIT_X = 0x222, SPLIT_Y = 0x224;

    /**
     * Tags: the black numbers of each row (per half) and column (per half).
     */
    static final int ROW_BLACK = 0x223, COLUMN_BLACK = 0x225;

    /**
     * Tag: the row numbers of the newer backs, whose photosites keep their black level.
     */
    static final int KEPT_ROW_BLACK = 0x25A;

    /**
     * Calibration tags: the grids of every photosite and those of the red and blue ones.
     */
    static final int LEVEL_GRID = 0x410, COLOR_GRID = 0x40B;

    /**
     * Calibration tag: the response table.
     */
    static final int RESPONSE = 0x431;

    /**
     * Calibration tags: the gains of four outputs (a version, then four floats per output: the gain above one of the
     * gain stages above 0, then that of stage 0); the polynomial of the two-output backs (four floats); the ratio of
     * those backs' halves' gains (a version, two floats, then the constants above one of the stages above 0 and of
     * stage 0, their slopes, the change per degree and the temperature it is measured from).
     */
    static final int OUTPUT_GAINS = 0x41E, POLYNOMIAL = 0x41A, HALF_GAIN = 0x419;

    /**
     * Tag: the sensor's temperature, a float in degrees.
     */
    static final int TEMPERATURE = 0x210;

    /**
     * Calibration tags: the level tables of the backs of four outputs, the one a gain stage without its own takes, then
     * those of the gain stages 0 to 3 (the levels of each output, then a count).
     */
    static final int LEVEL_TABLE_BASE = 0x41F, LEVEL_TABLE_STAGES = 0x420;

    /**
     * Tags: the sensor's gain stage; whether the level tables apply (1).
     */
    static final int GAIN_STAGE = 0x20B, LEVEL_MODE = 0x21E;

    /**
     * Calibration tag: the defective photosites and columns.
     */
    static final int DEFECTS = 0x400;

    /**
     * An entry of the directory.
     *
     * @param length the entry's length
     * @param value the entry's value or offset
     */
    private record Entry(int length, int value) {
    }

    /**
     * The file.
     */
    private final TiffReader reader;

    /**
     * The entries by tag.
     */
    private final Map<Integer, Entry> entries = new HashMap<>();

    /**
     * Reads the directory.
     *
     * @param reader the file
     * @throws MediaException.Malformed if the directory cannot be read
     */
    IiqBlock(TiffReader reader) {
        this.reader = reader;
        var directory = BASE + Integer.toUnsignedLong(number(reader.bytes(16, 4), 0));
        var count = number(reader.bytes(directory, 8), 0);
        if (count < 0 || count > MAX_ENTRIES) {
            throw new MediaException.Malformed("a Phase One directory of " + count + " entries");
        }
        var table = reader.bytes(directory + 8, count * 16L);
        for (var index = 0; index < count; index++) {
            entries.put(number(table, index * 16), new Entry(number(table, index * 16 + 8), number(table, index * 16 + 12)));
        }
    }

    /**
     * {@return whether the directory has a tag}
     *
     * @param tag the tag number
     */
    boolean has(int tag) {
        return entries.containsKey(tag);
    }

    /**
     * {@return a tag's integer}
     *
     * @param tag the tag number
     * @param fallback the value of a tag the directory lacks
     */
    int value(int tag, int fallback) {
        var entry = entries.get(tag);
        return entry == null ? fallback : entry.value;
    }

    /**
     * {@return a tag's bytes, or {@code null} when the directory lacks it}
     *
     * @param tag the tag number
     */
    byte[] bytes(int tag) {
        var entry = entries.get(tag);
        return entry == null ? null
                : reader.bytes(BASE + Integer.toUnsignedLong(entry.value), Integer.toUnsignedLong(entry.length));
    }

    /**
     * {@return the position of the sensor data in the file}
     */
    long dataOffset() {
        return BASE + Integer.toUnsignedLong(entries.get(DATA).value);
    }

    /**
     * {@return the length of the sensor data}
     */
    long dataLength() {
        return Integer.toUnsignedLong(entries.get(DATA).length);
    }

    /**
     * Reads the entries of a calibration tag.
     *
     * @param tag the tag number
     * @return the entries, in file order
     */
    List<byte[]> calibration(int tag) {
        var found = new ArrayList<byte[]>();
        var entry = entries.get(CALIBRATION);
        if (entry == null) {
            return found;
        }
        var base = BASE + Integer.toUnsignedLong(entry.value);
        var directory = base + Integer.toUnsignedLong(number(reader.bytes(base + 8, 4), 0));
        var count = number(reader.bytes(directory, 4), 0);
        if (count < 0 || count > MAX_ENTRIES) {
            return found;
        }
        var table = reader.bytes(directory + 8, count * 12L);
        for (var index = 0; index < count; index++) {
            if (number(table, index * 12) == tag) {
                found.add(reader.bytes(base + Integer.toUnsignedLong(number(table, index * 12 + 8)),
                        Integer.toUnsignedLong(number(table, index * 12 + 4))));
            }
        }
        return found;
    }

    /**
     * {@return a little-endian number}
     *
     * @param data the bytes
     * @param offset the number's offset
     */
    static int number(byte[] data, int offset) {
        return (data[offset] & 0xFF) | (data[offset + 1] & 0xFF) << 8 | (data[offset + 2] & 0xFF) << 16
                | data[offset + 3] << 24;
    }
}
