package com.github.auties00.smalt.aot.internal;

import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.DataTrack;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes the DVD subpicture units the training workload of the VobSub format reads, deterministically: each unit a
 * run-length coded bitmap of bars in a four-color palette (its top and bottom fields coded apart), with the display
 * control sequences that set its colors, contrasts and area, start it and stop it.
 */
final class SyntheticDvdSub {
    /**
     * The 90 kHz clock of the subpicture timestamps.
     */
    static final Rational TIME_BASE = new Rational(1, 90000);

    /**
     * The width of the video the subpictures are shown on.
     */
    static final int VIDEO_WIDTH = 720;

    /**
     * The height of the video the subpictures are shown on.
     */
    static final int VIDEO_HEIGHT = 480;

    /**
     * The distance between the starts of two subpictures, in 90 kHz ticks.
     */
    static final long PERIOD = 180_000;

    /**
     * The delay of the stop command of each unit, in units of 1024 ticks of the 90 kHz clock (about 1.5 seconds).
     */
    static final int STOP_DELAY = 132;

    /**
     * The colors of the index header's palette the units use, as RGB: black, white, yellow and gray first.
     */
    static final int[] PALETTE = {0x000000, 0xFFFFFF, 0xFFFF00, 0x808080, 0xFF0000, 0x00FF00, 0x0000FF, 0x404040,
            0xC0C0C0, 0x800000, 0x008000, 0x000080, 0x808000, 0x800080, 0x008080, 0xFF00FF};

    /**
     * The palette entries of pixel values 3, 2, 1 and 0: yellow, black, white and black.
     */
    static final int[] COLORS = {2, 0, 1, 0};

    /**
     * The contrasts (alphas, 0 to 15) of pixel values 3, 2, 1 and 0: pixel value 0 is transparent and pixel value 3
     * translucent.
     */
    static final int[] CONTRAST = {12, 15, 15, 0};

    /**
     * Prevents instantiation.
     */
    private SyntheticDvdSub() {
        throw new AssertionError();
    }

    /**
     * Returns the index header of a VobSub file: its size and palette.
     *
     * @return the header, UTF-8
     */
    static byte[] header() {
        var text = new StringBuilder("# VobSub index file, v7 (do not modify this line!)\n");
        text.append("size: ").append(VIDEO_WIDTH).append('x').append(VIDEO_HEIGHT).append('\n');
        text.append("palette: ");
        for (var index = 0; index < PALETTE.length; index++) {
            text.append(index == 0 ? "" : ", ").append(String.format("%06x", PALETTE[index]));
        }
        text.append('\n');
        return text.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Returns a subpicture track.
     *
     * @param id the track identifier
     * @param language the ISO 639-2 language of the track
     * @return the track, with the index header as its configuration
     */
    static DataTrack track(int id, String language) {
        return DataTrack.builder().id(id).codec(Codec.DVD_SUBTITLE).timeBase(TIME_BASE).codecConfig(header())
                .language(language).build();
    }

    /**
     * Returns the pixel value of a subpicture: a transparent border, a moving block of pixel value 3, and rows of
     * pixel values 1 and 2 elsewhere.
     *
     * @param index the subpicture's index
     * @param width the width of the bitmap
     * @param height the height of the bitmap
     * @param x the column
     * @param y the row
     * @return the pixel value, 0 to 3
     */
    static int pixel(int index, int width, int height, int x, int y) {
        if (x < 4 || y < 2 || x >= width - 4 || y >= height - 2) {
            return 0;
        }
        if (Math.abs(x - (index * 37) % width) < 12) {
            return 3;
        }
        return (y / 6) % 2 == 0 ? 1 : 2;
    }

    /**
     * Returns the left column of a subpicture of a width: centered.
     *
     * @param width the width of the bitmap
     * @return the column
     */
    static int left(int width) {
        return (VIDEO_WIDTH - width) / 2;
    }

    /**
     * Returns the top row of a subpicture of a height: near the bottom of the video.
     *
     * @param height the height of the bitmap
     * @return the row
     */
    static int top(int height) {
        return VIDEO_HEIGHT - height - 40;
    }

    /**
     * Returns the units of subpictures shown one after the other, each stopped by its own stop command.
     *
     * @param trackId the identifier of the track of the packets
     * @param count the number of subpictures
     * @param width the width of each bitmap, at most {@link #VIDEO_WIDTH}
     * @param height the height of each bitmap, at most {@link #VIDEO_HEIGHT}
     * @param shift a shift of the subpictures' indices, which varies the bitmaps between tracks
     * @return the packets, in presentation order
     */
    static List<Packet> units(int trackId, int count, int width, int height, int shift) {
        var packets = new ArrayList<Packet>();
        var indices = new byte[width * height];
        for (var index = 0; index < count; index++) {
            for (var y = 0; y < height; y++) {
                for (var x = 0; x < width; x++) {
                    indices[y * width + x] = (byte) pixel(index + shift, width, height, x, y);
                }
            }
            var unit = unit(width, height, indices, left(width), top(height));
            var start = index * PERIOD;
            packets.add(new Packet(trackId, unit, start, start, STOP_DELAY * 1024L, true));
        }
        return packets;
    }

    /**
     * Returns a subpicture unit: the two fields, then a control sequence that sets the colors, contrasts, area and
     * field offsets and starts the display, and one that stops it.
     *
     * @param width the width of the bitmap
     * @param height the height of the bitmap
     * @param indices the pixel values, row by row
     * @param x the left column
     * @param y the top row
     * @return the unit
     */
    private static byte[] unit(int width, int height, byte[] indices, int x, int y) {
        var top = new ByteArrayOutputStream();
        for (var row = 0; row < height; row += 2) {
            line(top, indices, row * width, width);
        }
        var bottom = new ByteArrayOutputStream();
        for (var row = 1; row < height; row += 2) {
            line(bottom, indices, row * width, width);
        }
        var control = 4 + top.size() + bottom.size();
        var commands = new ByteArrayOutputStream();
        commands.write(0x01);
        commands.write(0x03);
        commands.write(COLORS[0] << 4 | COLORS[1]);
        commands.write(COLORS[2] << 4 | COLORS[3]);
        commands.write(0x04);
        commands.write(CONTRAST[0] << 4 | CONTRAST[1]);
        commands.write(CONTRAST[2] << 4 | CONTRAST[3]);
        var right = x + width - 1;
        var lower = y + height - 1;
        commands.writeBytes(new byte[]{0x05, (byte) (x >> 4), (byte) ((x & 0xF) << 4 | right >> 8), (byte) right,
                (byte) (y >> 4), (byte) ((y & 0xF) << 4 | lower >> 8), (byte) lower});
        commands.write(0x06);
        short16(commands, 4);
        short16(commands, 4 + top.size());
        commands.write(0xFF);
        var stop = control + 4 + commands.size();
        var unit = new ByteArrayOutputStream();
        short16(unit, stop + 6);
        short16(unit, control);
        unit.writeBytes(top.toByteArray());
        unit.writeBytes(bottom.toByteArray());
        short16(unit, 0);
        short16(unit, stop);
        unit.writeBytes(commands.toByteArray());
        short16(unit, STOP_DELAY);
        short16(unit, stop);
        unit.writeBytes(new byte[]{0x02, (byte) 0xFF});
        return unit.toByteArray();
    }

    /**
     * Writes the run-length coding of one line, padded to a byte: runs of up to 3, 15, 63 and 255 pixels in one,
     * two, three and four nibbles, and a run to the end of the line in four nibbles.
     *
     * @param out the destination
     * @param indices the pixel values
     * @param offset the index of the line's first pixel
     * @param width the width of the line
     */
    private static void line(ByteArrayOutputStream out, byte[] indices, int offset, int width) {
        var nibbles = new Nibbles(out);
        var x = 0;
        while (x < width) {
            var color = indices[offset + x] & 3;
            var run = 1;
            while (x + run < width && (indices[offset + x + run] & 3) == color && run < 255) {
                run++;
            }
            var value = run << 2 | color;
            if (x + run == width && run > 3) {
                nibbles.put(0);
                nibbles.put(0);
                nibbles.put(0);
                nibbles.put(color);
            } else if (run < 4) {
                nibbles.put(value);
            } else if (run < 16) {
                nibbles.put(value >> 4);
                nibbles.put(value & 0xF);
            } else if (run < 64) {
                nibbles.put(0);
                nibbles.put(value >> 4);
                nibbles.put(value & 0xF);
            } else {
                nibbles.put(0);
                nibbles.put(value >> 8);
                nibbles.put(value >> 4 & 0xF);
                nibbles.put(value & 0xF);
            }
            x += run;
        }
        nibbles.align();
    }

    /**
     * Writes a big-endian 16-bit value.
     *
     * @param out the destination
     * @param value the value, of which the low 16 bits are written
     */
    private static void short16(ByteArrayOutputStream out, int value) {
        out.write(value >> 8);
        out.write(value);
    }

    /**
     * Writes nibbles, high nibble first.
     */
    private static final class Nibbles {
        /**
         * The destination.
         */
        private final ByteArrayOutputStream out;

        /**
         * The high nibble waiting for its low nibble, or -1.
         */
        private int pending = -1;

        /**
         * Constructs a writer.
         *
         * @param out the destination
         */
        Nibbles(ByteArrayOutputStream out) {
            this.out = out;
        }

        /**
         * Writes a nibble.
         *
         * @param nibble the nibble, 0 to 15
         */
        void put(int nibble) {
            if (pending < 0) {
                pending = nibble & 0xF;
            } else {
                out.write(pending << 4 | nibble & 0xF);
                pending = -1;
            }
        }

        /**
         * Pads the nibbles to a byte with a zero nibble.
         */
        void align() {
            if (pending >= 0) {
                put(0);
            }
        }
    }
}
