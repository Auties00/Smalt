package com.github.auties00.smalt.aot.internal;

import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.DataTrack;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes the Blu-ray presentation graphic stream (PGS) display sets the training workload of the bitmap subtitle
 * formats reads, deterministically: each subtitle a run-length coded bitmap of bars in a four-color palette, shown
 * by one display set and cleared by the next.
 */
final class SyntheticPgs {
    /**
     * The 90 kHz clock of PGS timestamps.
     */
    private static final Rational TIME_BASE = new Rational(1, 90000);

    /**
     * The width of the video the subtitles are composed on.
     */
    private static final int VIDEO_WIDTH = 1920;

    /**
     * The height of the video the subtitles are composed on.
     */
    private static final int VIDEO_HEIGHT = 1080;

    /**
     * Prevents instantiation.
     */
    private SyntheticPgs() {
        throw new AssertionError();
    }

    /**
     * Returns the track of the display sets.
     *
     * @return the track
     */
    static DataTrack track() {
        return DataTrack.builder().id(1).codec(Codec.PGS).timeBase(TIME_BASE).build();
    }

    /**
     * Returns the display sets of subtitles shown one after the other: each shows a bitmap and the next one clears
     * it.
     *
     * @param count the number of subtitles
     * @param width the width of each bitmap
     * @param height the height of each bitmap
     * @return the packets, in presentation order
     */
    static List<Packet> displaySets(int count, int width, int height) {
        var packets = new ArrayList<Packet>();
        for (var index = 0; index < count; index++) {
            var start = index * 180_000L;
            packets.add(new Packet(1, show(index, width, height), start, start, 0, true));
            packets.add(new Packet(1, clear(2 * index + 1), start + 135_000, start + 135_000, 0, false));
        }
        return packets;
    }

    /**
     * Returns a display set that starts an epoch and shows a bitmap: a composition, a window, a palette, the object
     * and the end segment.
     *
     * @param index the subtitle's index, which varies its bitmap
     * @param width the width of the bitmap
     * @param height the height of the bitmap
     * @return the segments
     */
    private static byte[] show(int index, int width, int height) {
        var x = (VIDEO_WIDTH - width) / 2;
        var y = VIDEO_HEIGHT - height - 60;
        var out = new ByteArrayOutputStream();
        segment(out, 0x16, bytes(u16(VIDEO_WIDTH), u16(VIDEO_HEIGHT), new byte[]{0x10}, u16(2 * index),
                new byte[]{(byte) 0x80, 0, 0, 1}, u16(0), new byte[]{0, 0}, u16(x), u16(y)));
        segment(out, 0x17, bytes(new byte[]{1, 0}, u16(x), u16(y), u16(width), u16(height)));
        var palette = new ByteArrayOutputStream();
        palette.write(0);
        palette.write(0);
        // the colors: transparent, white, black and a translucent yellow, as Y, Cr, Cb and alpha
        int[][] colors = {{0, 16, 128, 128, 0}, {1, 235, 128, 128, 255}, {2, 16, 128, 128, 255},
                {3, 210, 146, 16, 192}};
        for (var color : colors) {
            for (var value : color) {
                palette.write(value);
            }
        }
        segment(out, 0x14, palette.toByteArray());
        var rle = rle(index, width, height);
        var object = new ByteArrayOutputStream();
        object.writeBytes(u16(0));
        object.write(0);
        object.write(0xC0);
        var length = rle.length + 4;
        object.writeBytes(new byte[]{(byte) (length >> 16), (byte) (length >> 8), (byte) length});
        object.writeBytes(u16(width));
        object.writeBytes(u16(height));
        object.writeBytes(rle);
        segment(out, 0x15, object.toByteArray());
        segment(out, 0x80, new byte[0]);
        return out.toByteArray();
    }

    /**
     * Returns a display set that clears the screen: a composition without objects, an empty window and the end
     * segment.
     *
     * @param number the composition number
     * @return the segments
     */
    private static byte[] clear(int number) {
        var out = new ByteArrayOutputStream();
        segment(out, 0x16, bytes(u16(VIDEO_WIDTH), u16(VIDEO_HEIGHT), new byte[]{0x10}, u16(number),
                new byte[]{0, 0, 0, 0}));
        segment(out, 0x17, new byte[]{0});
        segment(out, 0x80, new byte[0]);
        return out.toByteArray();
    }

    /**
     * Run-length codes a bitmap of horizontal bars, a transparent border and a moving black block, line by line.
     *
     * @param index the subtitle's index, which moves the block
     * @return the coded lines
     */
    static byte[] rle(int index, int width, int height) {
        var out = new ByteArrayOutputStream();
        var line = new int[width];
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                var border = x < 4 || y < 2 || x >= width - 4 || y >= height - 2;
                var block = Math.abs(x - (index * 37) % width) < 12;
                line[x] = border ? 0 : block ? 2 : (y / 6) % 2 == 0 ? 1 : 3;
            }
            for (var x = 0; x < width; ) {
                var color = line[x];
                var run = 1;
                while (x + run < width && line[x + run] == color && run < 16383) {
                    run++;
                }
                if (color != 0 && run < 3) {
                    for (var k = 0; k < run; k++) {
                        out.write(color);
                    }
                } else {
                    out.write(0);
                    var flag = (color != 0 ? 0x80 : 0) | (run > 63 ? 0x40 : 0);
                    if (run > 63) {
                        out.write(flag | run >> 8);
                        out.write(run & 0xFF);
                    } else {
                        out.write(flag | run);
                    }
                    if (color != 0) {
                        out.write(color);
                    }
                }
                x += run;
            }
            out.write(0);
            out.write(0);
        }
        return out.toByteArray();
    }

    /**
     * Appends a segment without the file's header: its type, its 16-bit length and its payload.
     *
     * @param out the output
     * @param type the segment type
     */
    private static void segment(ByteArrayOutputStream out, int type, byte[] payload) {
        out.write(type);
        out.writeBytes(u16(payload.length));
        out.writeBytes(payload);
    }

    /**
     * {@return a 16-bit big-endian value}
     *
     */
    private static byte[] u16(int value) {
        return new byte[]{(byte) (value >> 8), (byte) value};
    }

    /**
     * {@return byte arrays joined in order}
     *
     * @param parts the arrays
     */
    private static byte[] bytes(byte[]... parts) {
        var out = new ByteArrayOutputStream();
        for (var part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }
}
