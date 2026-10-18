package com.github.auties00.smalt.image.raw.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.internal.io.ByteArrays;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Parses a Canon CR3 file: an ISO base media file whose {@code moov} box holds a Canon box
 * ({@code uuid 85c0b687-820f-11e0-8111-f4ce462b6a48}) with the metadata as TIFF structures
 * ({@code CMT1}: the first directory, {@code CMT2}: the EXIF directory, {@code CMT4}: the GPS
 * directory), and whose tracks hold a full-size JPEG, a small raw image and the CRX-coded sensor
 * data; a second Canon box ({@code uuid eaf42b5e-1c98-4b88-b9fb-b7dc406e4d16}) holds a
 * {@code PRVW} preview JPEG.
 *
 * <p>The camera, the shot, the EXIF block and the JPEG images are read; the CRX-coded sensor data
 * is not decoded.
 *
 * @implNote This implementation follows the box layout Laurent Clevy's CR3 documentation
 * describes; it reads no code. The CRX codec is documented there only in outline (the details are
 * deferred to LGPL implementations), so the sensor data is left undecoded.
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
        // TODO: decode the CRX-coded sensor data; deriving it from Adobe DNG Converter values stopped at the index
        //  updates of its run mode
        file.unsupported = "Canon CR3 (CRX) sensor data is not supported";
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
         * The GPS directory of {@code CMT4}, or {@code null}.
         */
        Ifd cmt4;

        /**
         * The JPEG images found.
         */
        final List<Jpeg> previews = new ArrayList<>();

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
                        }
                        walk(state, body, boxEnd, depth + 1);
                        if (type.equals("trak") && state.chunk > 0 && state.sample > 0) {
                            state.previews.add(new Jpeg(state.chunk, state.sample));
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
                case "CMT1", "CMT2", "CMT4" -> metadata(state, type, body, boxEnd);
                case "PRVW" -> state.previews.add(new Jpeg(body + 16, boxEnd - body - 16));
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
                default -> state.cmt4 = first;
            }
        } catch (MediaException.Malformed exception) {
            // a damaged metadata box is left out
        }
    }
}
