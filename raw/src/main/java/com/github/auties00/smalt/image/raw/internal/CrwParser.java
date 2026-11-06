package com.github.auties00.smalt.image.raw.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.raw.CfaColor;
import com.github.auties00.smalt.image.raw.CfaPattern;
import com.github.auties00.smalt.internal.io.ByteArrays;
import com.github.auties00.smalt.internal.io.SourceBytes;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.internal.task.ParallelBands;
import com.github.auties00.smalt.io.MediaSource;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Parses a Canon CRW file: a CIFF heap holding the camera's records, the compressed sensor data
 * and the embedded JPEG images.
 *
 * <p>The file starts with the byte order mark, the 32-bit length of the header and
 * {@code "HEAPCCDR"}. A heap ends with the 32-bit size of its value data, which the directory
 * follows: a 16-bit entry count and entries of a 16-bit tag (the record kind in the low 14 bits,
 * the value stored in the entry itself when bits 14 and 15 are {@code 01}), a 32-bit length and
 * a 32-bit offset into the value data. A record whose kind has {@code 0x2800} or {@code 0x3000} in
 * bits 11 to 13 is a heap itself. The records read are:
 * <ul>
 * <li>{@code 0x2005}, the sensor data; {@code 0x2007} and {@code 0x2008}, JPEG previews;
 * <li>{@code 0x1835}, the decoder table: the Huffman table set and the offset of the compressed
 * stream after the low bits;
 * <li>{@code 0x1031}, the sensor geometry: the stored size at 16-bit words 1 and 2, the image
 * edges (left, top, right and bottom, inclusive) at words 5 to 8 and the masked rectangle that
 * gives the black level at words 9 to 12;
 * <li>{@code 0x1030}, whose word 5 is the bits of a sample (10 or 12; files without the record
 * hold 12-bit samples);
 * <li>{@code 0x080A} the maker and model, {@code 0x1810} the rotation, {@code 0x180E} the capture
 * time, {@code 0x102A} the shot settings, {@code 0x102D} the camera settings, {@code 0x1029} the
 * focal length and {@code 0x10A9} the white balance levels.
 * </ul>
 *
 * <p>The sensor data of a 12-bit file starts with the two low bits of every photosite, four to a
 * byte from the least significant bits, in raster order; the compressed stream, at the decoder
 * table's offset after them, holds the ten high bits. The stream is entropy-coded like a JPEG
 * scan, with {@code FF 00} byte stuffing, in blocks of 64 consecutive photosites in raster order
 * (blocks run on across rows): a DC code of {@link CrwTables} and the bits of a difference give
 * the block's first value, the difference added to the previous block's first value; AC codes give
 * the other 63 as a run of zeros and a category, with end-of-block and run-of-16 codes, as in a
 * JPEG AC table. Each value is the photosite's difference from the photosite two columns to its
 * left, the first two of a row from 512.
 *
 * <p>The image is the sensor geometry's image area. The color filters are red, green, green and
 * blue from the sensor's top-left photosite, except on the PowerShot G1 (green, magenta, yellow
 * and cyan). Photosites saturate at 4000 on the EOS D60, 10D and 300D (Digital Rebel, Kiss
 * Digital) and at {@code 2^bits - 1} on other models. The white balance levels are those the
 * shot's white balance setting selects; without them the white balance is estimated from the
 * embedded preview. A rotation of 90, 180 or 270 degrees reads as the EXIF orientations 6, 3 and
 * 8.
 *
 * @implNote This implementation follows the CIFF specification and the record names of the
 * ExifTool documentation; the stream structure, the predictor, the Huffman tables, the low-bit
 * layout, the black and white levels and the meaning of the shot settings' fields were derived
 * clean-room from CC0 sample files against the values Adobe DNG Converter, run as a black box,
 * stores for them, and the codes no sample uses from the values it stores for copies of the
 * samples that use them. Table sets beyond the three of the samples (which the converter refuses
 * too) and files without a decoder table raise {@link MediaException.Unsupported}. The black level
 * is measured on the masked rectangle when the sensor data is read.
 */
final class CrwParser {
    /**
     * The record kind of the sensor data.
     */
    private static final int RAW_DATA = 0x2005;

    /**
     * The record kind of the large JPEG preview.
     */
    private static final int JPG_FROM_RAW = 0x2007;

    /**
     * The record kind of the thumbnail.
     */
    private static final int THUMBNAIL = 0x2008;

    /**
     * The record kind of the image specification.
     */
    private static final int IMAGE_SPEC = 0x1810;

    /**
     * The record kind of the capture time.
     */
    private static final int CAPTURED_TIME = 0x180E;

    /**
     * The record kind of the maker and model.
     */
    private static final int MAKE_MODEL = 0x080A;

    /**
     * The record kind of the sensor geometry.
     */
    private static final int SENSOR_INFO = 0x1031;

    /**
     * The record kind of the decoder table.
     */
    private static final int DECODER_TABLE = 0x1835;

    /**
     * The record kind of the shot settings.
     */
    private static final int SHOT_INFO = 0x102A;

    /**
     * The record kind of the camera settings.
     */
    private static final int CAMERA_SETTINGS = 0x102D;

    /**
     * The record kind of the focal length.
     */
    private static final int FOCAL_LENGTH = 0x1029;

    /**
     * The record kind of the white balance levels.
     */
    private static final int COLOR_BALANCE = 0x10A9;

    /**
     * The record kind of the white sample, which gives the bits of a sample.
     */
    private static final int WHITE_SAMPLE = 0x1030;

    /**
     * The records read, by kind.
     */
    private static final Set<Integer> WANTED = Set.of(RAW_DATA, JPG_FROM_RAW, THUMBNAIL, IMAGE_SPEC, CAPTURED_TIME,
            MAKE_MODEL, SENSOR_INFO, DECODER_TABLE, SHOT_INFO, CAMERA_SETTINGS, FOCAL_LENGTH, COLOR_BALANCE,
            WHITE_SAMPLE);

    /**
     * The deepest heap nesting followed.
     */
    private static final int MAX_DEPTH = 8;

    /**
     * The most directory entries examined in the whole file.
     */
    private static final int MAX_ENTRIES = 8192;

    /**
     * The longest metadata record read.
     */
    private static final int MAX_RECORD = 1 << 16;

    /**
     * The bytes of low bits a band of rows reads at a time.
     */
    private static final int CHUNK = 1 << 16;

    /**
     * The predictor of the first two photosites of a row.
     */
    private static final int ROW_START = 512;

    /**
     * The largest value of the ten high bits the stream codes.
     */
    private static final int HIGH_MAXIMUM = 1023;

    /**
     * The models whose photosites saturate at 4000.
     */
    private static final Set<String> WHITE_4000 = Set.of("Canon EOS D60", "Canon EOS 10D", "Canon EOS 300D DIGITAL",
            "Canon EOS DIGITAL REBEL", "Canon EOS Kiss Digital");

    /**
     * The models with green, magenta, yellow and cyan filters.
     */
    private static final Set<String> FOUR_COLOR = Set.of("Canon PowerShot G1");

    /**
     * The white balance levels of the color balance record for each white balance setting of the
     * shot settings, as an index of a group of four levels, or -1.
     */
    private static final int[] BALANCE_SLOT = {0, 1, 3, 4, 5, 6, 7, -1, 2, 8};

    /**
     * The bits of the stream the fused AC lookup resolves.
     */
    private static final int FAST_BITS = 11;

    /**
     * The flag of a fused AC lookup entry that ends the block.
     */
    private static final int FAST_END = 1 << 21;

    /**
     * The offset added to the difference a fused AC lookup entry holds.
     */
    private static final int FAST_BIAS = 2048;

    /**
     * For each table set and each {@value #FAST_BITS}-bit prefix of the stream, the AC code and
     * difference it starts with when both fit in the prefix: the difference plus
     * {@value #FAST_BIAS} in the low 12 bits, the run of zeros in bits 12 to 15, the bits consumed in
     * bits 16 to 20 and {@link #FAST_END} for the end of the block; {@code 0} otherwise.
     */
    private static final int[][] FAST_AC = new int[CrwTables.SETS][];

    static {
        for (var set = 0; set < CrwTables.SETS; set++) {
            FAST_AC[set] = fastAc(CrwTables.TABLES[2 * set + 1]);
        }
    }

    /**
     * Prevents instantiation.
     */
    private CrwParser() {
        throw new AssertionError();
    }

    /**
     * A record of the heap.
     *
     * @param offset the absolute offset of the value, or -1 when the value is stored in the entry
     * @param length the length of the value
     * @param inline the eight bytes of a value stored in the entry, or {@code null}
     */
    private record Record(long offset, long length, byte[] inline) {
    }

    /**
     * Where the sensor data lies and how it is coded.
     *
     * @param lowBits the absolute offset of the low bits, or -1 for 10-bit samples
     * @param stream the absolute offset of the compressed stream
     * @param streamLength the length of the compressed stream
     * @param width the stored width
     * @param height the stored height
     * @param table the Huffman table set, from 0 to 2
     */
    record Layout(long lowBits, long stream, long streamLength, int width, int height, int table) {
    }

    /**
     * Parses the file.
     *
     * @param file the description to fill
     * @throws MediaException.Malformed if the heap is corrupt or lacks the sensor data or geometry
     */
    static void parse(RawFile file) {
        var records = new HashMap<Integer, Record>();
        var reader = heap(file.source, file.limits, records);
        describe(file, reader, records);
        Layout layout;
        try {
            layout = layout(reader, records);
        } catch (MediaException.Unsupported exception) {
            file.unsupported = exception.getMessage();
            return;
        }
        sensor(file, reader, records, layout);
    }

    /**
     * Locates the sensor data of a file.
     *
     * @param source the CRW file, read but not closed
     * @param limits the resource bounds
     * @return the layout
     * @throws MediaException.Malformed if the heap is corrupt or lacks the sensor data or geometry
     * @throws MediaException.Unsupported if the sensor data is coded in a way this decoder does not
     * read
     */
    static Layout layout(MediaSource source, MediaLimits limits) {
        var records = new HashMap<Integer, Record>();
        return layout(heap(source, limits, records), records);
    }

    /**
     * Checks the header and reads the records of the heap.
     *
     * @param source the CRW file, read but not closed
     * @param limits the resource bounds
     * @param records the records found
     * @return a reader over the file in its byte order
     * @throws MediaException.Malformed if the header or the root heap is corrupt
     */
    private static TiffReader heap(MediaSource source, MediaLimits limits, Map<Integer, Record> records) {
        var head = new byte[26];
        if (SourceBytes.readAvailable(source, 0, head, 0, head.length) < head.length
                || !new String(head, 6, 8, StandardCharsets.ISO_8859_1).equals("HEAPCCDR")) {
            throw new MediaException.Malformed("missing CRW header");
        }
        var little = head[0] == 'I';
        var headerLength = ByteArrays.u32(head, 2, little);
        var end = RawFile.length(source);
        if (headerLength < 14 || headerLength >= end) {
            throw new MediaException.Malformed("CRW header of " + headerLength + " bytes");
        }
        var reader = new TiffReader(source, 0, little, limits);
        walk(reader, headerLength, end, 0, records, new int[]{MAX_ENTRIES});
        return reader;
    }

    /**
     * Reads the directory of a heap, recording the wanted records and following sub-heaps.
     *
     * @param reader any reader over the file, in its byte order
     * @param start the absolute offset of the heap
     * @param end the absolute offset after the heap
     * @param depth the nesting of the heap
     * @param records the records found, the first of each kind kept
     * @param budget the directory entries left to examine, in its only element
     * @throws MediaException.Malformed if the root heap's directory lies outside it
     */
    private static void walk(TiffReader reader, long start, long end, int depth, Map<Integer, Record> records,
                             int[] budget) {
        if (end - start < 6) {
            throw new MediaException.Malformed("CIFF heap of " + (end - start) + " bytes");
        }
        var valueSize = reader.u32(end - 4);
        var directory = start + valueSize;
        if (valueSize > end - start - 6) {
            throw new MediaException.Malformed("CIFF directory outside its heap");
        }
        var count = reader.u16(directory);
        if (count > budget[0] || directory + 2 + 10L * count > end - 4) {
            throw new MediaException.Malformed("CIFF directory of " + count + " entries");
        }
        budget[0] -= count;
        var entries = reader.bytes(directory + 2, 10L * count);
        var little = reader.little();
        for (var index = 0; index < count; index++) {
            var at = index * 10;
            var tag = ByteArrays.u16(entries, at, little);
            var kind = tag & 0x3FFF;
            var location = tag & 0xC000;
            if (location == 0x4000) {
                if (WANTED.contains(kind)) {
                    records.putIfAbsent(kind, new Record(-1, 8, Arrays.copyOfRange(entries, at + 2, at + 10)));
                }
                continue;
            }
            if (location != 0) {
                continue;
            }
            var length = ByteArrays.u32(entries, at + 2, little);
            var offset = ByteArrays.u32(entries, at + 6, little);
            if (offset > valueSize || length > valueSize - offset) {
                if (depth == 0) {
                    throw new MediaException.Malformed("CIFF record outside its heap");
                }
                continue;
            }
            var format = kind & 0x3800;
            if (format == 0x2800 || format == 0x3000) {
                if (depth < MAX_DEPTH) {
                    try {
                        walk(reader, start + offset, start + offset + length, depth + 1, records, budget);
                    } catch (MediaException.Malformed exception) {
                        // a damaged sub-heap leaves its records out
                    }
                }
            } else if (WANTED.contains(kind)) {
                records.putIfAbsent(kind, new Record(start + offset, length, null));
            }
        }
    }

    /**
     * Returns the value of a metadata record.
     *
     * @param reader any reader over the file
     * @param record the record, or {@code null}
     * @return the value, at most {@value #MAX_RECORD} bytes of it, or {@code null}
     */
    private static byte[] value(TiffReader reader, Record record) {
        if (record == null) {
            return null;
        }
        if (record.inline() != null) {
            return record.inline();
        }
        return reader.bytes(record.offset(), Math.min(record.length(), MAX_RECORD));
    }

    /**
     * Returns the 16-bit words of a metadata record.
     *
     * @param reader any reader over the file
     * @param record the record, or {@code null}
     * @return the words, signed, or an empty array
     */
    private static short[] words(TiffReader reader, Record record) {
        var value = value(reader, record);
        if (value == null) {
            return new short[0];
        }
        var words = new short[value.length / 2];
        for (var index = 0; index < words.length; index++) {
            words[index] = (short) ByteArrays.u16(value, index * 2, reader.little());
        }
        return words;
    }

    /**
     * Reads the camera, the shot and the previews.
     *
     * @param file the description to fill
     * @param reader any reader over the file
     * @param records the records of the heap, by kind
     */
    private static void describe(RawFile file, TiffReader reader, Map<Integer, Record> records) {
        var little = reader.little();
        var names = value(reader, records.get(MAKE_MODEL));
        if (names != null) {
            var parts = new String(names, StandardCharsets.ISO_8859_1).split("\0");
            if (parts.length > 0 && !parts[0].isBlank()) {
                file.make = parts[0].trim();
            }
            if (parts.length > 1 && !parts[1].isBlank()) {
                file.model = parts[1].trim();
            }
        }
        var spec = value(reader, records.get(IMAGE_SPEC));
        if (spec != null && spec.length >= 16) {
            file.orientation = switch ((int) ByteArrays.u32(spec, 12, little)) {
                case 90 -> 6;
                case 180 -> 3;
                case 270 -> 8;
                default -> 1;
            };
        }
        var time = value(reader, records.get(CAPTURED_TIME));
        if (time != null && time.length >= 12) {
            var seconds = ByteArrays.u32(time, 0, little);
            if ((ByteArrays.u32(time, 8, little) & 0x8000_0000L) != 0) {
                seconds -= (int) ByteArrays.u32(time, 4, little);
            }
            if (seconds > 0) {
                file.captureTime = LocalDateTime.ofEpochSecond(seconds, 0, ZoneOffset.UTC)
                        .format(DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss", Locale.ROOT));
            }
        }
        var shot = words(reader, records.get(SHOT_INFO));
        if (shot.length > 5) {
            if (shot[2] > 0) {
                file.iso = Math.round(100 * StrictMath.pow(2, (shot[2] - 160 + shot[1]) / 32.0));
            }
            if (shot[4] != Short.MIN_VALUE && shot[4] != 0) {
                file.fNumber = StrictMath.pow(2, shot[4] / 64.0);
            }
            if (shot[5] != Short.MIN_VALUE && shot[5] != 0) {
                file.exposureTime = StrictMath.pow(2, -shot[5] / 32.0);
            }
        }
        var camera = words(reader, records.get(CAMERA_SETTINGS));
        var units = camera.length > 25 && camera[25] > 0 ? camera[25] : 1.0;
        var focal = words(reader, records.get(FOCAL_LENGTH));
        if (focal.length > 1 && focal[1] != 0) {
            file.focalLength = (focal[1] & 0xFFFF) / units;
        }
        if (camera.length > 24 && camera[23] != 0 && camera[24] != 0) {
            var longest = (camera[23] & 0xFFFF) / units;
            var shortest = (camera[24] & 0xFFFF) / units;
            file.lens = shortest == longest ? String.format(Locale.ROOT, "%.1f mm", shortest)
                    : String.format(Locale.ROOT, "%.1f-%.1f mm", shortest, longest);
        }
        for (var kind : List.of(JPG_FROM_RAW, THUMBNAIL)) {
            var record = records.get(kind);
            if (record != null && record.inline() == null) {
                TiffTree.addPreview(file, reader, record.offset(), record.length());
            }
        }
        var balance = words(reader, records.get(COLOR_BALANCE));
        var setting = shot.length > 7 ? shot[7] : 0;
        var slot = setting >= 0 && setting < BALANCE_SLOT.length ? BALANCE_SLOT[setting] : -1;
        if (slot >= 0 && 4 * slot + 5 <= balance.length && (slot < 7 || balance.length >= 41)) {
            var red = balance[4 * slot + 1];
            var green = (balance[4 * slot + 2] + balance[4 * slot + 3]) / 2.0;
            var blue = balance[4 * slot + 4];
            if (red > 0 && green > 0 && blue > 0) {
                file.color.asShotNeutral = new double[]{green / red, 1, green / blue};
            }
        }
    }

    /**
     * Locates the sensor data, or records why it cannot be decoded.
     *
     * @param reader any reader over the file
     * @param records the records of the heap, by kind
     * @return the layout
     * @throws MediaException.Malformed if the sensor data or its geometry is missing or
     * inconsistent
     * @throws MediaException.Unsupported if the sensor data is coded in a way this decoder does not
     * read
     */
    private static Layout layout(TiffReader reader, Map<Integer, Record> records) {
        var raw = records.get(RAW_DATA);
        var info = words(reader, records.get(SENSOR_INFO));
        if (raw == null || raw.inline() != null || info.length < 9) {
            throw new MediaException.Malformed("CRW file without sensor data or sensor geometry");
        }
        var width = info[1] & 0xFFFF;
        var height = info[2] & 0xFFFF;
        if (width < 2 || height < 2) {
            throw new MediaException.Malformed("CRW sensor of " + width + "x" + height);
        }
        var table = value(reader, records.get(DECODER_TABLE));
        if (table == null || table.length < 12) {
            throw new MediaException.Unsupported("crw", "CRW sensor data without a decoder table is not supported");
        }
        var little = reader.little();
        var set = ByteArrays.u32(table, 0, little);
        if (set >= CrwTables.SETS) {
            throw new MediaException.Unsupported("crw", "CRW decoder table " + set + " is not supported");
        }
        var sample = words(reader, records.get(WHITE_SAMPLE));
        var bits = sample.length > 5 ? sample[5] : 12;
        if (bits != 10 && bits != 12) {
            throw new MediaException.Unsupported("crw", "CRW samples of " + bits + " bits are not supported");
        }
        var lowLength = bits == 12 ? ((long) width * height + 3) / 4 : 0;
        var skip = ByteArrays.u32(table, 8, little);
        if (lowLength + skip > raw.length()) {
            throw new MediaException.Malformed("CRW sensor data of " + raw.length() + " bytes for a " + width + "x"
                    + height + " sensor");
        }
        var stream = raw.offset() + lowLength + skip;
        return new Layout(bits == 12 ? raw.offset() : -1, stream, raw.offset() + raw.length() - stream, width, height,
                (int) set);
    }

    /**
     * Describes the sensor image.
     *
     * @param file the description to fill
     * @param reader any reader over the file
     * @param records the records of the heap, by kind
     * @param layout where the sensor data lies
     */
    private static void sensor(RawFile file, TiffReader reader, Map<Integer, Record> records, Layout layout) {
        LimitChecks.checkPicture(file.limits, layout.width(), layout.height());
        var info = words(reader, records.get(SENSOR_INFO));
        var sensor = new Sensor();
        sensor.width = layout.width();
        sensor.height = layout.height();
        sensor.bitsPerSample = layout.lowBits() >= 0 ? 12 : 10;
        sensor.compression = "Canon CRW";
        var left = info[5] & 0xFFFF;
        var top = info[6] & 0xFFFF;
        var right = info[7] & 0xFFFF;
        var bottom = info[8] & 0xFFFF;
        if (right > left && bottom > top && right < sensor.width && bottom < sensor.height) {
            sensor.activeArea = new int[]{top, left, bottom + 1, right + 1};
        }
        // a file without a model name is read as the common RGGB sensor
        var model = file.model == null ? "" : file.model;
        if (FOUR_COLOR.contains(model)) {
            sensor.cfa = CfaPattern.of(2, 2, List.of(CfaColor.GREEN, CfaColor.MAGENTA, CfaColor.YELLOW, CfaColor.CYAN));
            sensor.planeOfColor = new int[]{-1, 0, -1, 2, 1, 3, -1};
            file.color.asShotNeutral = null;
            sensor.estimateBalance = true;
        } else {
            sensor.cfa = CfaPattern.bayer("RGGB");
            sensor.estimateBalance = file.color.asShotNeutral == null;
        }
        sensor.white = new double[]{WHITE_4000.contains(model) ? 4000 : (1 << sensor.bitsPerSample) - 1};
        var mask = info.length > 12
                ? new int[]{info[9] & 0xFFFF, info[10] & 0xFFFF, info[11] & 0xFFFF, info[12] & 0xFFFF} : null;
        var source = reader.source();
        sensor.reader = (plane, limits) -> {
            decompress(source, layout, plane, limits);
            if (layout.lowBits() >= 0) {
                addLowBits(source, layout.lowBits(), plane);
            }
            black(sensor, plane, mask);
        };
        file.sensor = sensor;
    }

    /**
     * Decodes the compressed stream into the ten high bits of every photosite; photosites past
     * the end of a truncated stream are left at zero.
     *
     * @param source the raw file
     * @param layout where the sensor data lies
     * @param plane the plane to fill, of the stored size
     * @param limits the resource bounds
     */
    static void decompress(MediaSource source, Layout layout, SensorPlane plane, MediaLimits limits) {
        LimitChecks.checkAllocation(limits, layout.streamLength());
        if (layout.streamLength() > Integer.MAX_VALUE - 16) {
            throw new MediaException.LimitExceeded("CRW stream of " + layout.streamLength() + " bytes");
        }
        var data = new byte[(int) layout.streamLength()];
        var read = SourceBytes.readAvailable(source, layout.stream(), data, 0, data.length);
        var bits = new MsbBits(data, 0, unstuff(data, read));
        var dc = new MsbBits.Table(CrwTables.TABLES[2 * layout.table()]);
        var ac = new MsbBits.Table(CrwTables.TABLES[2 * layout.table() + 1]);
        var out = plane.integers;
        var width = plane.width;
        var total = out.length;
        var fast = FAST_AC[layout.table()];
        var coefficients = new int[64];
        var first = 0;
        var column = 0;
        for (var start = 0; start < total; start += 64) {
            var category = bits.decode(dc);
            first += extend(bits.read(category), category);
            coefficients[0] = first;
            for (var index = 1; index < 64; index++) {
                var entry = fast[bits.peek(FAST_BITS)];
                int difference;
                if (entry != 0) {
                    bits.skip((entry >>> 16) & 31);
                    if ((entry & FAST_END) != 0) {
                        break;
                    }
                    index += (entry >>> 12) & 15;
                    difference = (entry & 0xFFF) - FAST_BIAS;
                } else {
                    var code = bits.decode(ac);
                    if (code == CrwTables.ZERO) {
                        continue;
                    }
                    if (code <= 0) {
                        break;
                    }
                    index += code >>> 4;
                    var size = code & 15;
                    difference = extend(bits.read(size), size);
                }
                if (index < 64) {
                    coefficients[index] = difference;
                }
            }
            var count = Math.min(64, total - start);
            for (var index = 0; index < count; index++) {
                var position = start + index;
                var predictor = column < 2 ? ROW_START : out[position - 2];
                out[position] = (short) Math.clamp(predictor + coefficients[index], 0, HIGH_MAXIMUM);
                coefficients[index] = 0;
                if (++column == width) {
                    column = 0;
                }
            }
            if (bits.exhausted()) {
                return;
            }
        }
    }

    /**
     * Builds the fused AC lookup of an AC table.
     *
     * @param spec the table, as code counts followed by values
     * @return the lookup, as {@link #FAST_AC} describes it
     */
    private static int[] fastAc(int[] spec) {
        var fast = new int[1 << FAST_BITS];
        var code = 0;
        var index = 16;
        for (var length = 1; length <= 16; length++) {
            for (var count = 0; count < spec[length - 1]; count++, code++) {
                var value = spec[index++];
                if (value == CrwTables.ZERO || length > FAST_BITS) {
                    continue;
                }
                var size = value & 15;
                var total = length + size;
                if (total > FAST_BITS) {
                    continue;
                }
                for (var bits = 0; bits < 1 << size; bits++) {
                    var entry = value == 0 ? FAST_END | (length << 16)
                            : (total << 16) | ((value >>> 4) << 12) | (extend(bits, size) + FAST_BIAS);
                    var prefix = ((code << size) | bits) << (FAST_BITS - total);
                    Arrays.fill(fast, prefix, prefix + (1 << (FAST_BITS - total)), entry);
                }
            }
            code <<= 1;
        }
        return fast;
    }

    /**
     * Removes the stuffed zero bytes after every {@code FF} byte of a stream, in place, and cuts
     * the stream at the first marker.
     *
     * @param data the stream
     * @param length the bytes of the stream
     * @return the bytes left
     */
    static int unstuff(byte[] data, int length) {
        var write = 0;
        for (var read = 0; read < length; read++) {
            var value = data[read];
            if (value == (byte) 0xFF) {
                if (read + 1 < length && data[read + 1] != 0) {
                    return write;
                }
                read++;
            }
            data[write++] = value;
        }
        return write;
    }

    /**
     * Returns the signed difference a category's bits code, as in a JPEG scan.
     *
     * @param value the bits
     * @param category the number of bits, from 0 to 16
     * @return the difference
     */
    private static int extend(int value, int category) {
        return category == 0 || value >= 1 << (category - 1) ? value : value - (1 << category) + 1;
    }

    /**
     * Joins the two low bits of every photosite to the ten high bits a plane holds, bands of rows
     * in parallel.
     *
     * @param source the raw file
     * @param offset the absolute offset of the low bits
     * @param plane the plane, holding the high bits
     */
    private static void addLowBits(MediaSource source, long offset, SensorPlane plane) {
        var out = plane.integers;
        var width = plane.width;
        var height = plane.height;
        var bands = ParallelBands.bands(height, (long) width * height);
        ParallelBands.run(bands, band -> {
            var begin = ParallelBands.start(band, bands, height) * width;
            var finish = ParallelBands.start(band + 1, bands, height) * width;
            var buffer = new byte[CHUNK];
            var lastByte = (finish + 3) >> 2;
            for (var firstByte = begin >> 2; firstByte < lastByte; firstByte += CHUNK) {
                var bytes = Math.min(CHUNK, lastByte - firstByte);
                var read = SourceBytes.readAvailable(source, offset + firstByte, buffer, 0, bytes);
                var stop = Math.min(finish, (firstByte + read) << 2);
                for (var photosite = Math.max(begin, firstByte << 2); photosite < stop; photosite++) {
                    var low = (buffer[(photosite >> 2) - firstByte] >> ((photosite & 3) << 1)) & 3;
                    out[photosite] = (short) ((out[photosite] << 2) | low);
                }
                if (read < bytes) {
                    return;
                }
            }
        });
    }

    /**
     * Sets the black level of each position of the 2x2 color pattern to the mean of the masked
     * rectangle, when the sensor geometry gives one inside the sensor.
     *
     * @param sensor the sensor description
     * @param plane the decoded samples
     * @param mask the left, top, right and bottom edges of the rectangle, inclusive, or
     * {@code null}
     */
    private static void black(Sensor sensor, SensorPlane plane, int[] mask) {
        if (mask == null || mask[2] <= mask[0] || mask[3] <= mask[1] || mask[2] >= plane.width
                || mask[3] >= plane.height) {
            return;
        }
        var sums = new long[4];
        var counts = new long[4];
        var out = plane.integers;
        for (var y = mask[1]; y <= mask[3]; y++) {
            var line = y * plane.width;
            for (var x = mask[0]; x <= mask[2]; x++) {
                var cell = ((y & 1) << 1) | (x & 1);
                sums[cell] += out[line + x] & 0xFFFF;
                counts[cell]++;
            }
        }
        var area = sensor.active();
        var black = new double[4];
        for (var cell = 0; cell < 4; cell++) {
            var source = (((cell >> 1) + area[0]) & 1) << 1 | (((cell & 1) + area[1]) & 1);
            if (counts[source] == 0) {
                return;
            }
            black[cell] = (double) sums[source] / counts[source];
        }
        sensor.blackRows = 2;
        sensor.blackColumns = 2;
        sensor.black = black;
    }
}
