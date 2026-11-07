package com.github.auties00.smalt.image.raw.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.raw.CfaPattern;
import com.github.auties00.smalt.internal.io.ByteArrays;
import com.github.auties00.smalt.internal.limits.LimitChecks;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Parses a Canon CR3 file: an ISO base media file whose {@code moov} box holds a Canon box
 * ({@code uuid 85c0b687-820f-11e0-8111-f4ce462b6a48}) with the metadata as TIFF structures
 * ({@code CMT1}: the first directory, {@code CMT2}: the EXIF directory, {@code CMT3}: the Canon
 * maker note, {@code CMT4}: the GPS directory), and whose tracks hold a full-size JPEG, a small
 * raw image and the CRX-coded sensor data; a second Canon box
 * ({@code uuid eaf42b5e-1c98-4b88-b9fb-b7dc406e4d16}) holds a {@code PRVW} preview JPEG.
 *
 * <p>The camera, the shot, the EXIF block and the JPEG images are read, and the largest CRX image
 * becomes the sensor data, decoded by {@link CrxDecoder}. Its {@code CRAW} sample entry holds the
 * {@code CMP1} box describing the coding and an {@code IAD1} box giving the image areas: the
 * recommended crop, the masked borders and the area of image data, as inclusive pixel ranges. The
 * color data of the maker note the timed metadata track ({@code CTMD}) carries in its EXIF records
 * gives the white balance and the levels.
 *
 * @implNote This implementation follows the box layout Laurent Clevy's CR3 documentation
 * describes; it reads no code. A dual pixel file's second CRX image of full size (one of the two
 * photodiodes of each pixel) is not exposed.
 */
final class Cr3Parser {
    /**
     * The UUID of the Canon metadata box.
     */
    private static final byte[] CANON = {(byte) 0x85, (byte) 0xC0, (byte) 0xB6, (byte) 0x87, (byte) 0x82, 0x0F, 0x11,
            (byte) 0xE0, (byte) 0x81, 0x11, (byte) 0xF4, (byte) 0xCE, 0x46, 0x2B, 0x6A, 0x48};

    /**
     * The UUID of the Canon preview box.
     */
    private static final byte[] PREVIEW = {(byte) 0xEA, (byte) 0xF4, 0x2B, 0x5E, 0x1C, (byte) 0x98, 0x4B, (byte) 0x88,
            (byte) 0xB9, (byte) 0xFB, (byte) 0xB7, (byte) 0xDC, 0x40, 0x6E, 0x4D, 0x16};

    /**
     * The most boxes visited.
     */
    private static final int MAX_BOXES = 4096;

    /**
     * The deepest level of nested boxes visited.
     */
    private static final int MAX_DEPTH = 8;

    /**
     * The offset of the first child box in a {@code CRAW} sample entry.
     */
    private static final int CRAW_CHILDREN = 0x5A;

    /**
     * The largest {@code CMP1} or {@code IAD1} box read.
     */
    private static final int MAX_DESCRIPTION = 4096;

    /**
     * The most bytes of the timed metadata read.
     */
    private static final int MAX_TIMED = 1 << 20;

    /**
     * The maker note tag holding the camera's color data.
     */
    private static final int COLOR_DATA = 0x4001;

    /**
     * Prevents instantiation.
     */
    private Cr3Parser() {
        throw new AssertionError();
    }

    /**
     * Parses the file.
     *
     * @param file the description to fill
     */
    static void parse(RawFile file) {
        var reader = new TiffReader(file.source, 0, false, file.limits);
        var state = new State(file, reader);
        var size = reader.size() < 0 ? Long.MAX_VALUE / 2 : reader.size();
        walk(state, 0, size, 0);
        if (state.cmt1 != null) {
            var tree = new TiffTree(state.cmt1.reader());
            tree.describe(file);
            file.previews.clear();
            if (state.cmt2 != null) {
                TiffTree.shot(state.cmt2, file);
            }
            file.exif = ExifBlock.build(state.cmt1, state.cmt2, state.cmt4);
        }
        for (var preview : state.previews) {
            TiffTree.addPreview(file, reader, preview.offset(), preview.length());
        }
        // TODO: expose the second full-size image of dual pixel files (one photodiode of each pixel), which decodes
        //  like the main one, once the raw API can return a second sensor image
        Track main = null;
        for (var track : state.raws) {
            if (main == null || (long) track.image().width() * track.image().height()
                    > (long) main.image().width() * main.image().height()) {
                main = track;
            }
        }
        if (main == null || state.unsupportedArea > (long) main.image().width() * main.image().height()) {
            // a smaller image never stands in for a main image that cannot be decoded
            file.unsupported = state.unsupported != null ? state.unsupported : "the CR3 file holds no CRX image";
            return;
        }
        file.sensor = sensor(main, reader, makerNote(state), file);
    }

    /**
     * Returns the maker note directory holding the color data: the one of the timed metadata's
     * records, whose {@code CMT3} copy leaves the color data out.
     *
     * @param state the walk's state
     * @return the directory, or {@code null}
     */
    private static Ifd makerNote(State state) {
        if (state.timedLength <= 0) {
            return state.cmt3;
        }
        var reader = state.reader;
        var length = (int) Math.min(state.timedLength, MAX_TIMED);
        byte[] data;
        try {
            data = reader.bytes(state.timedOffset, length);
        } catch (MediaException.Malformed exception) {
            // a file cut short loses its timed metadata first, at the end of its media data
            return state.cmt3;
        }
        var position = 0;
        while (position + 12 <= length) {
            var size = ByteArrays.u32(data, position, true);
            var type = ByteArrays.u16(data, position + 4, true);
            if (size < 12 || position + size > length) {
                break;
            }
            if (type == 7 || type == 8 || type == 9) {
                var record = position + 12;
                while (record + 8 <= position + size) {
                    var recordSize = ByteArrays.u32(data, record, true);
                    var tag = ByteArrays.u32(data, record + 4, true);
                    if (recordSize < 8 || record + recordSize > position + size) {
                        break;
                    }
                    if (tag == TiffTree.MAKER_NOTE && recordSize > 16) {
                        try {
                            var tiff = TiffReader.open(state.file.source, state.timedOffset + record + 8, state.file.limits);
                            var note = tiff.directory(tiff.firstDirectory());
                            if (note.has(COLOR_DATA)) {
                                return note;
                            }
                        } catch (MediaException.Malformed exception) {
                            // a damaged record is skipped
                        }
                    }
                    record += (int) recordSize;
                }
            }
            position += (int) size;
        }
        return state.cmt3;
    }

    /**
     * Describes the sensor data of a CRX image.
     *
     * @param track the image's track
     * @param reader a reader over the file
     * @param note the maker note directory, or {@code null}
     * @param file the description to fill
     * @return the sensor description
     */
    private static Sensor sensor(Track track, TiffReader reader, Ifd note, RawFile file) {
        var image = track.image();
        LimitChecks.checkPicture(file.limits, image.width(), image.height());
        var sensor = new Sensor();
        sensor.width = image.width();
        sensor.height = image.height();
        sensor.bitsPerSample = image.bits();
        sensor.cfa = CfaPattern.bayer(image.pattern());
        sensor.compression = image.levels() == 0 ? "Canon CRX lossless" : "Canon CRX lossy (C-RAW)";
        areas(track.areas(), sensor);
        var offset = track.offset();
        var length = track.length();
        sensor.reader = (plane, limits) -> {
            LimitChecks.checkAllocation(limits, length);
            var sample = reader.bytes(offset, length);
            CrxDecoder.decode(image, sample, plane, limits);
        };
        if (note != null) {
            Cr2Parser.colorData(note, sensor, file);
        }
        // TODO: correct the lens distortion and chromatic aberration of the PowerShot compacts, which Adobe DNG
        //  Converter writes as a WarpRectilinear opcode list 3; where the file records the correction is not known yet
        return sensor;
    }

    /**
     * Sets the active area and the default crop from an {@code IAD1} box.
     *
     * @param areas the box's 16-bit values: eight header values holding the image size, then the
     * crop, the left and top masked borders and the image data area as inclusive left, top, right
     * and bottom pixels, or {@code null}
     * @param sensor the sensor description to fill
     */
    private static void areas(int[] areas, Sensor sensor) {
        if (areas == null || areas.length < 24) {
            return;
        }
        // some cameras record a crop reaching past the area of image data; the area then extends to it
        var left = Math.min(areas[20], areas[8]);
        var top = Math.min(areas[21], areas[9]);
        var right = Math.min(Math.max(areas[22], areas[10]) + 1, sensor.width);
        var bottom = Math.min(Math.max(areas[23], areas[11]) + 1, sensor.height);
        if (left < 0 || top < 0 || right - left < 16 || bottom - top < 16) {
            return;
        }
        sensor.activeArea = new int[]{top, left, bottom, right};
        var cropLeft = Math.max(areas[8], left);
        var cropTop = Math.max(areas[9], top);
        var cropRight = Math.min(areas[10] + 1, right);
        var cropBottom = Math.min(areas[11] + 1, bottom);
        if (cropRight - cropLeft >= 16 && cropBottom - cropTop >= 16) {
            sensor.defaultCrop = new double[]{cropLeft - left, cropTop - top, cropRight - cropLeft,
                    cropBottom - cropTop};
        }
    }

    /**
     * A JPEG image the walk found.
     *
     * @param offset the absolute offset of the JPEG stream
     * @param length the length of the stream
     */
    private record Jpeg(long offset, long length) {
    }

    /**
     * A CRX image the walk found.
     *
     * @param image the coding parameters
     * @param areas the {@code IAD1} values, or {@code null}
     * @param offset the absolute offset of the sample
     * @param length the length of the sample
     */
    private record Track(CrxDecoder.Image image, int[] areas, long offset, long length) {
    }

    /**
     * Holds what the walk found.
     */
    private static final class State {
        /**
         * The file.
         */
        final RawFile file;

        /**
         * A big-endian reader over the file.
         */
        final TiffReader reader;

        /**
         * The first directory of {@code CMT1}, or {@code null}.
         */
        Ifd cmt1;

        /**
         * The EXIF directory of {@code CMT2}, or {@code null}.
         */
        Ifd cmt2;

        /**
         * The maker note directory of {@code CMT3}, or {@code null}.
         */
        Ifd cmt3;

        /**
         * The GPS directory of {@code CMT4}, or {@code null}.
         */
        Ifd cmt4;

        /**
         * The JPEG images found.
         */
        final List<Jpeg> previews = new ArrayList<>();

        /**
         * The CRX images found.
         */
        final List<Track> raws = new ArrayList<>();

        /**
         * Why the largest of the CRX images whose coding is unsupported cannot be decoded, or
         * {@code null}.
         */
        String unsupported;

        /**
         * The photosites the {@code CMP1} box of {@link #unsupported}'s image declares, or 0.
         */
        long unsupportedArea;

        /**
         * The boxes visited.
         */
        int boxes;

        /**
         * The offset of the first chunk of the track being walked, or {@code -1}.
         */
        long chunk = -1;

        /**
         * The size of the first sample of the track being walked, or {@code -1}.
         */
        long sample = -1;

        /**
         * The {@code CMP1} body of the track being walked, or {@code null}.
         */
        byte[] coding;

        /**
         * The {@code IAD1} values of the track being walked, or {@code null}.
         */
        int[] areas;

        /**
         * Whether the track being walked holds the timed metadata ({@code CTMD}).
         */
        boolean timed;

        /**
         * The offset of the timed metadata sample.
         */
        long timedOffset;

        /**
         * The length of the timed metadata sample, or 0.
         */
        long timedLength;

        /**
         * Constructs the state.
         *
         * @param file the description the walk fills
         * @param reader a big-endian reader over the file
         */
        State(RawFile file, TiffReader reader) {
            this.file = file;
            this.reader = reader;
        }
    }

    /**
     * Walks the boxes of a range.
     *
     * @param state the walk's state
     * @param start the offset of the first box
     * @param end the offset after the range
     * @param depth the nesting depth
     */
    private static void walk(State state, long start, long end, int depth) {
        var reader = state.reader;
        var header = new byte[16];
        var position = start;
        while (position + 8 <= end && state.boxes++ < MAX_BOXES) {
            if (reader.readAvailable(position, header, 0, 16) < 8) {
                return;
            }
            var size = ByteArrays.u32(header, 0, false);
            var type = new String(header, 4, 4, StandardCharsets.ISO_8859_1);
            var body = position + 8;
            if (size == 1) {
                size = ByteArrays.i64be(header, 8);
                body += 8;
            } else if (size == 0) {
                size = end - position;
            }
            if (size < body - position || position + size > end || size < 0) {
                return;
            }
            var boxEnd = position + size;
            switch (type) {
                case "moov", "trak", "mdia", "minf", "stbl" -> {
                    if (depth < MAX_DEPTH) {
                        if (type.equals("trak")) {
                            state.chunk = -1;
                            state.sample = -1;
                            state.coding = null;
                            state.areas = null;
                            state.timed = false;
                        }
                        walk(state, body, boxEnd, depth + 1);
                        if (type.equals("trak")) {
                            track(state);
                        }
                    }
                }
                case "uuid" -> {
                    var uuid = new byte[16];
                    if (reader.readAvailable(body, uuid, 0, 16) == 16 && depth < MAX_DEPTH) {
                        if (Arrays.equals(uuid, CANON)) {
                            walk(state, body + 16, boxEnd, depth + 1);
                        } else if (Arrays.equals(uuid, PREVIEW)) {
                            walk(state, body + 24, boxEnd, depth + 1);
                        }
                    }
                }
                case "CMT1", "CMT2", "CMT3", "CMT4" -> metadata(state, type, body, boxEnd);
                case "PRVW" -> state.previews.add(new Jpeg(body + 16, boxEnd - body - 16));
                case "stsd" -> sampleEntry(state, body, boxEnd);
                case "stsz" -> {
                    var fields = new byte[12];
                    if (reader.readAvailable(body, fields, 0, 12) == 12) {
                        var fixed = ByteArrays.u32(fields, 4, false);
                        var count = ByteArrays.u32(fields, 8, false);
                        if (fixed > 0) {
                            state.sample = fixed;
                        } else if (count > 0 && reader.readAvailable(body + 12, fields, 0, 4) == 4) {
                            state.sample = ByteArrays.u32(fields, 0, false);
                        }
                    }
                }
                case "co64", "stco" -> {
                    var fields = new byte[16];
                    var wide = type.equals("co64");
                    if (reader.readAvailable(body, fields, 0, wide ? 16 : 12) == (wide ? 16 : 12)
                            && ByteArrays.u32(fields, 4, false) > 0) {
                        state.chunk = wide ? ByteArrays.i64be(fields, 8) : ByteArrays.u32(fields, 8, false);
                    }
                }
                default -> {
                }
            }
            position = boxEnd;
        }
    }

    /**
     * Records the track just walked: a CRX image when its sample entry holds a {@code CMP1} box,
     * otherwise a JPEG image.
     *
     * @param state the walk's state
     */
    private static void track(State state) {
        if (state.chunk <= 0 || state.sample <= 0) {
            return;
        }
        if (state.timed) {
            state.timedOffset = state.chunk;
            state.timedLength = state.sample;
            return;
        }
        if (state.coding == null) {
            state.previews.add(new Jpeg(state.chunk, state.sample));
            return;
        }
        try {
            var image = CrxDecoder.image(state.coding);
            if (image.headerLength() > state.sample) {
                throw new MediaException.Malformed("CRX headers longer than the sample");
            }
            state.raws.add(new Track(image, state.areas, state.chunk, state.sample));
        } catch (MediaException.Unsupported exception) {
            var area = Math.min(ByteArrays.u32(state.coding, 8, false), Integer.MAX_VALUE)
                    * Math.min(ByteArrays.u32(state.coding, 12, false), Integer.MAX_VALUE);
            if (state.unsupported == null || area > state.unsupportedArea) {
                state.unsupported = exception.getMessage();
                state.unsupportedArea = area;
            }
        } catch (MediaException.Malformed exception) {
            // a damaged image description leaves the track out
        }
    }

    /**
     * Reads a {@code CRAW} sample entry's {@code CMP1} and {@code IAD1} boxes.
     *
     * @param state the walk's state
     * @param body the offset of the {@code stsd} body
     * @param end the offset after the {@code stsd} box
     */
    private static void sampleEntry(State state, long body, long end) {
        var reader = state.reader;
        var entry = body + 8;
        var head = new byte[8];
        if (reader.readAvailable(entry, head, 0, 8) < 8) {
            return;
        }
        var kind = new String(head, 4, 4, StandardCharsets.ISO_8859_1);
        if (kind.equals("CTMD")) {
            state.timed = true;
            return;
        }
        if (!kind.equals("CRAW")) {
            return;
        }
        var entryEnd = Math.min(end, entry + ByteArrays.u32(head, 0, false));
        var position = entry + CRAW_CHILDREN;
        while (position + 8 <= entryEnd) {
            if (reader.readAvailable(position, head, 0, 8) < 8) {
                return;
            }
            var size = ByteArrays.u32(head, 0, false);
            if (size < 8 || position + size > entryEnd) {
                return;
            }
            var type = new String(head, 4, 4, StandardCharsets.ISO_8859_1);
            if (type.equals("CMP1") && size - 8 <= MAX_DESCRIPTION) {
                state.coding = reader.bytes(position + 8, size - 8);
            } else if (type.equals("CDI1")) {
                areas(state, position + 12, position + size);
            }
            position += size;
        }
    }

    /**
     * Reads the {@code IAD1} box of a {@code CDI1} box.
     *
     * @param state the walk's state
     * @param start the offset of the first child box
     * @param end the offset after the {@code CDI1} box
     */
    private static void areas(State state, long start, long end) {
        var reader = state.reader;
        var head = new byte[8];
        var position = start;
        while (position + 8 <= end) {
            if (reader.readAvailable(position, head, 0, 8) < 8) {
                return;
            }
            var size = ByteArrays.u32(head, 0, false);
            if (size < 8 || position + size > end) {
                return;
            }
            if (new String(head, 4, 4, StandardCharsets.ISO_8859_1).equals("IAD1") && size - 8 <= MAX_DESCRIPTION) {
                var bytes = reader.bytes(position + 8, size - 8);
                var values = new int[bytes.length / 2];
                for (var index = 0; index < values.length; index++) {
                    values[index] = ByteArrays.u16(bytes, 2 * index, false);
                }
                state.areas = values;
                return;
            }
            position += size;
        }
    }

    /**
     * Reads one of the TIFF metadata boxes.
     *
     * @param state the walk's state
     * @param type the box type
     * @param body the offset of the box body
     * @param end the offset after the box
     */
    private static void metadata(State state, String type, long body, long end) {
        if (end - body < 8) {
            return;
        }
        try {
            var tiff = TiffReader.open(state.file.source, body, state.file.limits);
            var first = tiff.directory(tiff.firstDirectory());
            switch (type) {
                case "CMT1" -> state.cmt1 = first;
                case "CMT2" -> state.cmt2 = first;
                case "CMT3" -> state.cmt3 = first;
                default -> state.cmt4 = first;
            }
        } catch (MediaException.Malformed exception) {
            // a damaged metadata box is left out
        }
    }
}
