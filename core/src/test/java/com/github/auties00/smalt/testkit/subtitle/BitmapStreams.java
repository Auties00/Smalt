package com.github.auties00.smalt.testkit.subtitle;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.Deflater;

/**
 * Writes bitmap subtitle test streams: Blu-ray presentation graphic stream segments, DVB subtitling segments and
 * DVD subpicture units, from palette indices.
 */
public final class BitmapStreams {
    private BitmapStreams() {
    }

    /**
     * Returns a picture of palette indices computed per pixel.
     *
     * @param width the width
     * @param height the height
     * @param pattern the index of a pixel, from its column and row
     * @return the indices, row by row
     */
    public static byte[] indices(int width, int height, Pattern pattern) {
        var pixels = new byte[width * height];
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                pixels[y * width + x] = (byte) pattern.index(x, y);
            }
        }
        return pixels;
    }

    /**
     * Computes the palette index of a pixel.
     */
    @FunctionalInterface
    public interface Pattern {
        /**
         * Returns the index of a pixel.
         *
         * @param x the column
         * @param y the row
         * @return the index
         */
        int index(int x, int y);
    }

    /**
     * Returns a PGS segment: its type, its length and its body.
     *
     * @param type the segment type
     * @param body the body
     * @return the segment
     */
    public static byte[] pgsSegment(int type, byte[] body) {
        var out = new ByteArrayOutputStream();
        out.write(type);
        out.write(body.length >> 8);
        out.write(body.length);
        out.writeBytes(body);
        return out.toByteArray();
    }

    /**
     * Returns the run-length coding of a PGS object.
     *
     * @param indices the palette indices
     * @param width the width
     * @param height the height
     * @return the coded lines, each ending with {@code 00 00}
     */
    public static byte[] pgsRle(byte[] indices, int width, int height) {
        var out = new ByteArrayOutputStream();
        for (var row = 0; row < height; row++) {
            var x = 0;
            while (x < width) {
                var color = indices[row * width + x] & 0xFF;
                var run = 1;
                while (x + run < width && (indices[row * width + x + run] & 0xFF) == color && run < 16383) {
                    run++;
                }
                if (color != 0 && run <= 2) {
                    for (var index = 0; index < run; index++) {
                        out.write(color);
                    }
                } else if (color == 0) {
                    if (run < 64) {
                        out.writeBytes(new byte[]{0, (byte) run});
                    } else {
                        out.writeBytes(new byte[]{0, (byte) (0x40 | run >> 8), (byte) run});
                    }
                } else if (run < 64) {
                    out.writeBytes(new byte[]{0, (byte) (0x80 | run), (byte) color});
                } else {
                    out.writeBytes(new byte[]{0, (byte) (0xC0 | run >> 8), (byte) run, (byte) color});
                }
                x += run;
            }
            out.writeBytes(new byte[]{0, 0});
        }
        return out.toByteArray();
    }

    /**
     * Returns a PGS presentation composition segment.
     *
     * @param width the composition width
     * @param height the composition height
     * @param number the composition number
     * @param state the composition state: {@code 0x80} epoch start, {@code 0x40} acquisition point, {@code 0} normal
     * @param paletteUpdate whether only the palette changes
     * @param paletteId the palette identifier
     * @param objects the composition objects: object identifier, window, x, y, flags ({@code 0x80} cropped,
     * {@code 0x40} forced) and, when cropped, the cropping x, y, width and height
     * @return the segment
     */
    public static byte[] pcs(int width, int height, int number, int state, boolean paletteUpdate, int paletteId,
                             int[]... objects) {
        var out = new ByteArrayOutputStream();
        writeShort(out, width);
        writeShort(out, height);
        out.write(0x10);
        writeShort(out, number);
        out.write(state);
        out.write(paletteUpdate ? 0x80 : 0);
        out.write(paletteId);
        out.write(objects.length);
        for (var object : objects) {
            writeShort(out, object[0]);
            out.write(object[1]);
            out.write(object[4]);
            writeShort(out, object[2]);
            writeShort(out, object[3]);
            if ((object[4] & 0x80) != 0) {
                for (var index = 5; index < 9; index++) {
                    writeShort(out, object[index]);
                }
            }
        }
        return pgsSegment(0x16, out.toByteArray());
    }

    /**
     * Returns a PGS window definition segment.
     *
     * @param windows the windows: identifier, x, y, width, height
     * @return the segment
     */
    public static byte[] wds(int[]... windows) {
        var out = new ByteArrayOutputStream();
        out.write(windows.length);
        for (var window : windows) {
            out.write(window[0]);
            for (var index = 1; index < 5; index++) {
                writeShort(out, window[index]);
            }
        }
        return pgsSegment(0x17, out.toByteArray());
    }

    /**
     * Returns a PGS palette definition segment.
     *
     * @param id the palette identifier
     * @param version the palette version
     * @param entries the entries: index, Y, Cr, Cb, alpha
     * @return the segment
     */
    public static byte[] pds(int id, int version, int[]... entries) {
        var out = new ByteArrayOutputStream();
        out.write(id);
        out.write(version);
        for (var entry : entries) {
            for (var value : entry) {
                out.write(value);
            }
        }
        return pgsSegment(0x14, out.toByteArray());
    }

    /**
     * Returns the object definition segments of an object, its run-length data cut into fragments.
     *
     * @param id the object identifier
     * @param width the width
     * @param height the height
     * @param indices the palette indices
     * @param fragment the most run-length bytes per segment
     * @return the segments, first to last
     */
    public static List<byte[]> ods(int id, int width, int height, byte[] indices, int fragment) {
        var rle = pgsRle(indices, width, height);
        var data = new ByteArrayOutputStream();
        writeShort(data, width);
        writeShort(data, height);
        data.writeBytes(rle);
        var whole = data.toByteArray();
        var segments = new ArrayList<byte[]>();
        var position = 0;
        while (position < whole.length || segments.isEmpty()) {
            var first = position == 0;
            var take = Math.min(fragment, whole.length - position);
            var last = position + take == whole.length;
            var body = new ByteArrayOutputStream();
            writeShort(body, id);
            body.write(0);
            body.write((first ? 0x80 : 0) | (last ? 0x40 : 0));
            if (first) {
                body.write(whole.length >> 16);
                body.write(whole.length >> 8);
                body.write(whole.length);
            }
            body.write(whole, position, take);
            position += take;
            segments.add(pgsSegment(0x15, body.toByteArray()));
        }
        return segments;
    }

    /**
     * Returns the PGS end segment.
     *
     * @return the segment
     */
    public static byte[] pgsEnd() {
        return pgsSegment(0x80, new byte[0]);
    }

    /**
     * Returns a DVB subtitling segment.
     *
     * @param type the segment type
     * @param page the page identifier
     * @param body the segment data
     * @return the segment
     */
    public static byte[] dvbSegment(int type, int page, byte[] body) {
        var out = new ByteArrayOutputStream();
        out.write(0x0F);
        out.write(type);
        writeShort(out, page);
        writeShort(out, body.length);
        out.writeBytes(body);
        return out.toByteArray();
    }

    /**
     * Returns a DVB page composition segment.
     *
     * @param page the page identifier
     * @param timeout the page time-out in seconds
     * @param state the page state: 0 normal, 1 acquisition point, 2 mode change
     * @param regions the regions: identifier, x, y
     * @return the segment
     */
    public static byte[] dvbPage(int page, int timeout, int state, int[]... regions) {
        var out = new ByteArrayOutputStream();
        out.write(timeout);
        out.write(state << 2);
        for (var region : regions) {
            out.write(region[0]);
            out.write(0xFF);
            writeShort(out, region[1]);
            writeShort(out, region[2]);
        }
        return dvbSegment(0x10, page, out.toByteArray());
    }

    /**
     * Returns a DVB region composition segment.
     *
     * @param page the page identifier
     * @param id the region identifier
     * @param fill whether the region is filled with its background
     * @param width the width
     * @param height the height
     * @param depth the region depth code: 1 for 2 bits, 2 for 4 bits, 3 for 8 bits
     * @param clut the CLUT family
     * @param background the background code of the region depth
     * @param objects the objects: identifier, x, y
     * @return the segment
     */
    public static byte[] dvbRegion(int page, int id, boolean fill, int width, int height, int depth, int clut,
                                   int background, int[]... objects) {
        var out = new ByteArrayOutputStream();
        out.write(id);
        out.write(fill ? 0x08 : 0);
        writeShort(out, width);
        writeShort(out, height);
        out.write(depth << 5 | depth << 2);
        out.write(clut);
        out.write(depth == 3 ? background : 0);
        out.write((depth == 2 ? background : 0) << 4 | (depth == 1 ? background : 0) << 2);
        for (var object : objects) {
            writeShort(out, object[0]);
            out.write(object[1] >> 8 & 0x0F);
            out.write(object[1]);
            out.write(0xF0 | object[2] >> 8 & 0x0F);
            out.write(object[2]);
        }
        return dvbSegment(0x11, page, out.toByteArray());
    }

    /**
     * Returns a DVB CLUT definition segment with full-range entries.
     *
     * @param page the page identifier
     * @param clut the CLUT family
     * @param entries the entries: entry identifier, flags (0x80 2-bit, 0x40 4-bit, 0x20 8-bit, 0x01 full range), Y, Cr,
     * Cb, T; an entry without the full range flag is written with the most significant bits only
     * @return the segment
     */
    public static byte[] dvbClut(int page, int clut, int[]... entries) {
        var out = new ByteArrayOutputStream();
        out.write(clut);
        out.write(0);
        for (var entry : entries) {
            out.write(entry[0]);
            if ((entry[1] & 1) != 0) {
                out.write(entry[1]);
                for (var index = 2; index < 6; index++) {
                    out.write(entry[index]);
                }
            } else {
                out.write(entry[1]);
                var packed = (entry[2] >> 2) << 10 | (entry[3] >> 4) << 6 | (entry[4] >> 4) << 2 | entry[5] >> 6;
                writeShort(out, packed);
            }
        }
        return dvbSegment(0x12, page, out.toByteArray());
    }

    /**
     * Returns a DVB object data segment coded as pixels, the bottom field following the top field.
     *
     * @param page the page identifier
     * @param id the object identifier
     * @param top the pixel-data sub-blocks of the top field
     * @param bottom the pixel-data sub-blocks of the bottom field, empty to repeat the top field
     * @param nonModifying whether entry 1 does not modify the region
     * @return the segment
     */
    public static byte[] dvbObject(int page, int id, byte[] top, byte[] bottom, boolean nonModifying) {
        var out = new ByteArrayOutputStream();
        writeShort(out, id);
        out.write(nonModifying ? 0x02 : 0);
        writeShort(out, top.length);
        writeShort(out, bottom.length);
        out.writeBytes(top);
        out.writeBytes(bottom);
        if ((top.length + bottom.length) % 2 == 0) {
            out.write(0);
        }
        return dvbSegment(0x13, page, out.toByteArray());
    }

    /**
     * Returns a DVB object data segment coded progressively: zlib-compressed scanlines, each with a PNG filter type.
     *
     * @param page the page identifier
     * @param id the object identifier
     * @param indices the 8-bit pixel codes
     * @param width the width
     * @param height the height
     * @param filter the PNG filter type of every row, 0 to 4
     * @return the segment
     */
    public static byte[] dvbProgressive(int page, int id, byte[] indices, int width, int height, int filter) {
        var raw = new byte[(width + 1) * height];
        for (var row = 0; row < height; row++) {
            raw[row * (width + 1)] = (byte) filter;
            for (var column = 0; column < width; column++) {
                var value = indices[row * width + column] & 0xFF;
                var left = column > 0 ? indices[row * width + column - 1] & 0xFF : 0;
                var up = row > 0 ? indices[(row - 1) * width + column] & 0xFF : 0;
                var corner = row > 0 && column > 0 ? indices[(row - 1) * width + column - 1] & 0xFF : 0;
                var predictor = switch (filter) {
                    case 1 -> left;
                    case 2 -> up;
                    case 3 -> (left + up) >> 1;
                    case 4 -> {
                        var p = left + up - corner;
                        var pa = Math.abs(p - left);
                        var pb = Math.abs(p - up);
                        var pc = Math.abs(p - corner);
                        yield pa <= pb && pa <= pc ? left : pb <= pc ? up : corner;
                    }
                    default -> 0;
                };
                raw[row * (width + 1) + 1 + column] = (byte) (value - predictor);
            }
        }
        var deflater = new Deflater();
        deflater.setInput(raw);
        deflater.finish();
        var compressed = new ByteArrayOutputStream();
        var buffer = new byte[4096];
        while (!deflater.finished()) {
            compressed.write(buffer, 0, deflater.deflate(buffer));
        }
        deflater.end();
        var data = compressed.toByteArray();
        var out = new ByteArrayOutputStream();
        writeShort(out, id);
        out.write(2 << 2);
        writeShort(out, width);
        writeShort(out, height);
        writeShort(out, data.length);
        out.writeBytes(data);
        return dvbSegment(0x13, page, out.toByteArray());
    }

    /**
     * Returns a DVB display definition segment.
     *
     * @param page the page identifier
     * @param width the display width
     * @param height the display height
     * @param window the window minimum x, maximum x, minimum y and maximum y, or {@code null}
     * @return the segment
     */
    public static byte[] dvbDisplay(int page, int width, int height, int[] window) {
        var out = new ByteArrayOutputStream();
        out.write(window != null ? 0x08 : 0);
        writeShort(out, width - 1);
        writeShort(out, height - 1);
        if (window != null) {
            for (var value : window) {
                writeShort(out, value);
            }
        }
        return dvbSegment(0x14, page, out.toByteArray());
    }

    /**
     * Returns the DVB end of display set segment.
     *
     * @param page the page identifier
     * @return the segment
     */
    public static byte[] dvbEnd(int page) {
        return dvbSegment(0x80, page, new byte[0]);
    }

    /**
     * Writes DVB pixel-data sub-blocks.
     */
    public static final class DvbPixels {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private long bits;
        private int count;

        /**
         * Starts a code string of a depth.
         *
         * @param depth 2, 4 or 8
         * @return this writer
         */
        public DvbPixels string(int depth) {
            out.write(depth == 2 ? 0x10 : depth == 4 ? 0x11 : 0x12);
            return this;
        }

        /**
         * Appends raw bits of a code string.
         *
         * @param value the bits
         * @param width the number of bits
         * @return this writer
         */
        public DvbPixels bits(int value, int width) {
            bits = bits << width | (value & ((1L << width) - 1));
            count += width;
            while (count >= 8) {
                out.write((int) (bits >> (count - 8)));
                count -= 8;
            }
            return this;
        }

        /**
         * Pads the code string to a byte boundary.
         *
         * @return this writer
         */
        public DvbPixels align() {
            if (count > 0) {
                bits(0, 8 - count);
            }
            return this;
        }

        /**
         * Appends a run of a 2-bit code string, in its shortest coding.
         *
         * @param run the number of pixels, 1 to 284
         * @param code the pixel code
         * @return this writer
         */
        public DvbPixels run2(int run, int code) {
            if (run == 1 && code != 0) {
                return bits(code, 2);
            }
            if (run >= 3 && run <= 10) {
                return bits(0, 2).bits(1, 1).bits(run - 3, 3).bits(code, 2);
            }
            if (run == 1) {
                return bits(0, 2).bits(0, 1).bits(1, 1);
            }
            if (run == 2 && code == 0) {
                return bits(0, 2).bits(0, 1).bits(0, 1).bits(1, 2);
            }
            if (run >= 12 && run <= 27) {
                return bits(0, 2).bits(0, 1).bits(0, 1).bits(2, 2).bits(run - 12, 4).bits(code, 2);
            }
            if (run >= 29) {
                return bits(0, 2).bits(0, 1).bits(0, 1).bits(3, 2).bits(run - 29, 8).bits(code, 2);
            }
            for (var index = 0; index < run; index++) {
                run2(1, code);
            }
            return this;
        }

        /**
         * Ends a 2-bit code string.
         *
         * @return this writer
         */
        public DvbPixels end2() {
            return bits(0, 2).bits(0, 1).bits(0, 1).bits(0, 2).align();
        }

        /**
         * Appends a run of a 4-bit code string, in its shortest coding.
         *
         * @param run the number of pixels, 1 to 280
         * @param code the pixel code
         * @return this writer
         */
        public DvbPixels run4(int run, int code) {
            if (run == 1 && code != 0) {
                return bits(code, 4);
            }
            if (code == 0 && run >= 3 && run <= 9) {
                return bits(0, 4).bits(0, 1).bits(run - 2, 3);
            }
            if (run >= 4 && run <= 7) {
                return bits(0, 4).bits(1, 1).bits(0, 1).bits(run - 4, 2).bits(code, 4);
            }
            if (code == 0 && run <= 2) {
                return bits(0, 4).bits(1, 1).bits(1, 1).bits(run - 1, 2);
            }
            if (run >= 9 && run <= 24) {
                return bits(0, 4).bits(1, 1).bits(1, 1).bits(2, 2).bits(run - 9, 4).bits(code, 4);
            }
            if (run >= 25) {
                return bits(0, 4).bits(1, 1).bits(1, 1).bits(3, 2).bits(run - 25, 8).bits(code, 4);
            }
            for (var index = 0; index < run; index++) {
                run4(1, code);
            }
            return this;
        }

        /**
         * Ends a 4-bit code string.
         *
         * @return this writer
         */
        public DvbPixels end4() {
            return bits(0, 4).bits(0, 1).bits(0, 3).align();
        }

        /**
         * Appends a run of an 8-bit code string, in its shortest coding.
         *
         * @param run the number of pixels, 1 to 127
         * @param code the pixel code
         * @return this writer
         */
        public DvbPixels run8(int run, int code) {
            if (run == 1 && code != 0) {
                return bits(code, 8);
            }
            if (code == 0) {
                return bits(0, 8).bits(0, 1).bits(run, 7);
            }
            if (run >= 3) {
                return bits(0, 8).bits(1, 1).bits(run, 7).bits(code, 8);
            }
            for (var index = 0; index < run; index++) {
                run8(1, code);
            }
            return this;
        }

        /**
         * Ends an 8-bit code string.
         *
         * @return this writer
         */
        public DvbPixels end8() {
            return bits(0, 8).bits(0, 1).bits(0, 7);
        }

        /**
         * Appends a map table.
         *
         * @param type the data type, {@code 0x20}, {@code 0x21} or {@code 0x22}
         * @param entries the table entries
         * @return this writer
         */
        public DvbPixels map(int type, int... entries) {
            out.write(type);
            if (type == 0x20) {
                out.write(entries[0] << 4 | entries[1]);
                out.write(entries[2] << 4 | entries[3]);
            } else {
                for (var entry : entries) {
                    out.write(entry);
                }
            }
            return this;
        }

        /**
         * Appends the end of object line code.
         *
         * @return this writer
         */
        public DvbPixels endLine() {
            out.write(0xF0);
            return this;
        }

        /**
         * {@return the sub-blocks written}
         */
        public byte[] bytes() {
            return out.toByteArray();
        }
    }

    /**
     * Returns a DVD subpicture unit.
     *
     * @param width the picture width
     * @param height the picture height
     * @param indices the 2-bit pixels
     * @param x the left column
     * @param y the top row
     * @param start the delay of the start command, in 1024/90000 s units
     * @param stop the delay of the stop command, or -1 for none
     * @param colors the palette indices of pixel values 3, 2, 1 and 0
     * @param contrast the contrasts of pixel values 3, 2, 1 and 0
     * @param forced whether the start is forced
     * @return the unit
     */
    public static byte[] spu(int width, int height, byte[] indices, int x, int y, int start, int stop, int[] colors,
                             int[] contrast, boolean forced) {
        return spu(width, height, indices, x, y, start, stop, colors, contrast, forced, new byte[0]);
    }

    /**
     * Returns a DVD subpicture unit whose first control sequence also carries extra commands.
     *
     * @param width the picture width
     * @param height the picture height
     * @param indices the 2-bit pixels
     * @param x the left column
     * @param y the top row
     * @param start the delay of the start command, in 1024/90000 s units
     * @param stop the delay of the stop command, or -1 for none
     * @param colors the palette indices of pixel values 3, 2, 1 and 0
     * @param contrast the contrasts of pixel values 3, 2, 1 and 0
     * @param forced whether the start is forced
     * @param commands the commands written after the display area and field offsets, as {@link #colcon} makes them
     * @return the unit
     */
    public static byte[] spu(int width, int height, byte[] indices, int x, int y, int start, int stop, int[] colors,
                             int[] contrast, boolean forced, byte[] commands) {
        var top = new ByteArrayOutputStream();
        for (var row = 0; row < height; row += 2) {
            top.writeBytes(spuLine(indices, row * width, width));
        }
        var bottom = new ByteArrayOutputStream();
        for (var row = 1; row < height; row += 2) {
            bottom.writeBytes(spuLine(indices, row * width, width));
        }
        var data = new ByteArrayOutputStream();
        data.writeBytes(top.toByteArray());
        data.writeBytes(bottom.toByteArray());
        var control = 4 + data.size();
        var first = new ByteArrayOutputStream();
        first.write(forced ? 0x00 : 0x01);
        first.write(0x03);
        first.write(colors[0] << 4 | colors[1]);
        first.write(colors[2] << 4 | colors[3]);
        first.write(0x04);
        first.write(contrast[0] << 4 | contrast[1]);
        first.write(contrast[2] << 4 | contrast[3]);
        var x2 = x + width - 1;
        var y2 = y + height - 1;
        first.writeBytes(new byte[]{0x05, (byte) (x >> 4), (byte) ((x & 0xF) << 4 | x2 >> 8), (byte) x2,
                (byte) (y >> 4), (byte) ((y & 0xF) << 4 | y2 >> 8), (byte) y2});
        first.write(0x06);
        writeShort(first, 4);
        writeShort(first, 4 + top.size());
        first.writeBytes(commands);
        first.write(0xFF);
        var sequences = new ByteArrayOutputStream();
        var second = control + 4 + first.size();
        writeShort(sequences, start);
        writeShort(sequences, stop >= 0 ? second : control);
        sequences.writeBytes(first.toByteArray());
        if (stop >= 0) {
            writeShort(sequences, stop);
            writeShort(sequences, second);
            sequences.writeBytes(new byte[]{0x02, (byte) 0xFF});
        }
        var unit = new ByteArrayOutputStream();
        writeShort(unit, 4 + data.size() + sequences.size());
        writeShort(unit, control);
        unit.writeBytes(data.toByteArray());
        unit.writeBytes(sequences.toByteArray());
        return unit.toByteArray();
    }

    /**
     * Returns a {@code CHG_COLCON} command (code 07) with its size word and end marker.
     *
     * @param bands one array per band of lines: the first and last screen line, then for each area its starting
     * screen column, its colors word and its contrast word (pixel value 3 in the top nibble)
     * @return the command
     */
    public static byte[] colcon(int[]... bands) {
        var body = new ByteArrayOutputStream();
        for (var band : bands) {
            var count = (band.length - 2) / 3;
            writeShort(body, band[0] & 0xFFF);
            writeShort(body, count << 12 | (band[1] & 0xFFF));
            for (var entry = 0; entry < count; entry++) {
                writeShort(body, band[2 + entry * 3]);
                writeShort(body, band[3 + entry * 3]);
                writeShort(body, band[4 + entry * 3]);
            }
        }
        writeShort(body, 0x0FFF);
        writeShort(body, 0xFFFF);
        var command = new ByteArrayOutputStream();
        command.write(0x07);
        writeShort(command, 2 + body.size());
        command.writeBytes(body.toByteArray());
        return command.toByteArray();
    }

    /**
     * Returns the run-length coding of one subpicture line, padded to a byte.
     */
    private static byte[] spuLine(byte[] indices, int offset, int width) {
        var nibbles = new ArrayList<Integer>();
        var x = 0;
        while (x < width) {
            var color = indices[offset + x] & 3;
            var run = 1;
            while (x + run < width && (indices[offset + x + run] & 3) == color && run < 255) {
                run++;
            }
            var value = run << 2 | color;
            if (x + run == width && run > 3 && color == 0) {
                nibbles.addAll(List.of(0, 0, 0, color));
            } else if (run < 4) {
                nibbles.add(value);
            } else if (run < 16) {
                nibbles.addAll(List.of(value >> 4, value & 0xF));
            } else if (run < 64) {
                nibbles.addAll(List.of(0, value >> 4, value & 0xF));
            } else {
                nibbles.addAll(List.of(0, value >> 8, value >> 4 & 0xF, value & 0xF));
            }
            x += run;
        }
        if (nibbles.size() % 2 != 0) {
            nibbles.add(0);
        }
        var bytes = new byte[nibbles.size() / 2];
        for (var index = 0; index < bytes.length; index++) {
            bytes[index] = (byte) (nibbles.get(2 * index) << 4 | nibbles.get(2 * index + 1));
        }
        return bytes;
    }

    private static void writeShort(ByteArrayOutputStream out, int value) {
        out.write(value >> 8);
        out.write(value);
    }
}
