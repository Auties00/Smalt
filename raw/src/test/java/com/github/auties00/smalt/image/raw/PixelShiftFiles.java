package com.github.auties00.smalt.image.raw;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Writes small synthetic multi-frame raw files: a Pentax pixel-shift PEF file (the first frame in the first
 * directory, the other three in its SubIFDs, as the K-1 and K-3 Mark III write them), a Pentax pixel-shift DNG
 * file (four raw directories of one Bayer pattern) and a Sony ARQ file (a raw SubIFD of four 16-bit samples per
 * pixel, red, green, green and blue, of photometric interpretation 32892).
 *
 * <p>The pixel-shift files hold a random scene of red, green and blue at every pixel, which {@link #scene} returns:
 * frame {@code i} holds at photosite {@code (x, y)} of color {@code c} the scene's value {@code c} at
 * {@code (x - DX[i], y - DY[i])}, clamped to the image, so that the merge reconstructs the scene exactly except in
 * the last row and column.
 */
public final class PixelShiftFiles {
    /** The column offset of each frame of a Pentax pixel-shift file, in file order. */
    public static final int[] DX = {0, 0, 1, 1};

    /** The row offset of each frame of a Pentax pixel-shift file, in file order. */
    public static final int[] DY = {0, 1, 1, 0};

    /** The black level of every synthetic file. */
    public static final int BLACK = 64;

    /** The width of the synthetic images. */
    public final int width;

    /** The height of the synthetic images. */
    public final int height;

    /** The scene: red, green and blue values, row by row. */
    private final int[][] scene;

    /**
     * Creates the files of a random scene.
     *
     * @param width the width, even
     * @param height the height, even
     * @param seed the seed of the scene
     */
    public PixelShiftFiles(int width, int height, long seed) {
        this.width = width;
        this.height = height;
        var random = new Random(seed);
        scene = new int[3][width * height];
        for (var color = 0; color < 3; color++) {
            for (var index = 0; index < scene[color].length; index++) {
                scene[color][index] = BLACK + random.nextInt(16000);
            }
        }
    }

    /**
     * {@return the scene value of a color at a pixel}
     *
     * @param color 0 for red, 1 for green, 2 for blue
     * @param x the column
     * @param y the row
     */
    public int scene(int color, int x, int y) {
        return scene[color][y * width + x];
    }

    /**
     * {@return the photosites of one frame, row by row, in the RGGB pattern}
     *
     * @param frame the frame, 0 to 3
     */
    public int[] frame(int frame) {
        var result = new int[width * height];
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                var color = (y & 1) + (x & 1);
                var sx = Math.clamp(x - DX[frame], 0, width - 1);
                var sy = Math.clamp(y - DY[frame], 0, height - 1);
                result[y * width + x] = scene[color][sy * width + sx];
            }
        }
        return result;
    }

    /**
     * {@return a Pentax pixel-shift PEF file of uncompressed 14-bit frames}
     */
    public byte[] pef() {
        var order = ByteOrder.LITTLE_ENDIAN;
        var strip = width * height * 2;
        var main = new ArrayList<Entry>();
        main.addAll(rawEntries(order, 0, 14));
        main.add(ascii(271, "PENTAX"));
        main.add(ascii(272, "PENTAX K-1"));
        main.add(longs(order, 330, 0, 0, 0));
        var subs = new ArrayList<List<Entry>>();
        for (var frame = 1; frame < 4; frame++) {
            subs.add(rawEntries(order, 0, 14));
        }
        // the sizes do not depend on the offsets, so one pass with placeholders gives the layout
        var mainSize = directory(main, 0, order).length;
        var offsets = new long[4];
        var position = 8L + mainSize;
        var subOffsets = new long[3];
        for (var index = 0; index < 3; index++) {
            subOffsets[index] = position;
            position += directory(subs.get(index), 0, order).length;
        }
        for (var frame = 0; frame < 4; frame++) {
            offsets[frame] = position;
            position += strip;
        }
        main = new ArrayList<>(rawEntries(order, offsets[0], 14));
        main.add(ascii(271, "PENTAX"));
        main.add(ascii(272, "PENTAX K-1"));
        main.add(longs(order, 330, subOffsets));
        var out = header(order);
        out.writeBytes(directory(main, 8, order));
        for (var index = 0; index < 3; index++) {
            out.writeBytes(directory(rawEntries(order, offsets[index + 1], 14), subOffsets[index], order));
        }
        for (var frame = 0; frame < 4; frame++) {
            out.writeBytes(words(frame(frame), order));
        }
        return out.toByteArray();
    }

    /**
     * {@return a Pentax pixel-shift DNG file: a first directory of DNG tags whose SubIFDs are four uncompressed
     * 16-bit raw directories}
     */
    public byte[] dng() {
        var order = ByteOrder.LITTLE_ENDIAN;
        var strip = width * height * 2;
        var subSizes = directory(dngRawEntries(order, 0), 0, order).length;
        var mainSize = directory(dngMain(order, new long[4]), 0, order).length;
        var subOffsets = new long[4];
        var position = 8L + mainSize;
        for (var index = 0; index < 4; index++) {
            subOffsets[index] = position;
            position += subSizes;
        }
        var offsets = new long[4];
        for (var frame = 0; frame < 4; frame++) {
            offsets[frame] = position;
            position += strip;
        }
        var out = header(order);
        out.writeBytes(directory(dngMain(order, subOffsets), 8, order));
        for (var index = 0; index < 4; index++) {
            out.writeBytes(directory(dngRawEntries(order, offsets[index]), subOffsets[index], order));
        }
        for (var frame = 0; frame < 4; frame++) {
            out.writeBytes(words(frame(frame), order));
        }
        return out.toByteArray();
    }

    /**
     * {@return a Sony ARQ file whose four samples per pixel hold the scene's red, its green twice (the second
     * one larger by {@code index % 2}, so the merge rounds) and its blue}
     */
    public byte[] arq() {
        var order = ByteOrder.LITTLE_ENDIAN;
        var samples = new int[width * height * 4];
        for (var index = 0; index < width * height; index++) {
            samples[index * 4] = scene[0][index];
            samples[index * 4 + 1] = scene[1][index];
            samples[index * 4 + 2] = scene[1][index] + index % 2;
            samples[index * 4 + 3] = scene[2][index];
        }
        // the first directory is a 4x4 RGB thumbnail, as the first directory of an ARW file is a preview
        var main = arqThumbnail(order, 0, 0);
        var rawOffset = 8L + directory(main, 0, order).length;
        var stripOffset = rawOffset + directory(arqEntries(order, 0, 0), 0, order).length;
        var thumbnailOffset = stripOffset + samples.length * 2L;
        var out = header(order);
        out.writeBytes(directory(arqThumbnail(order, rawOffset, thumbnailOffset), 8, order));
        out.writeBytes(directory(arqEntries(order, stripOffset, samples.length * 2), rawOffset, order));
        out.writeBytes(words(samples, order));
        for (var index = 0; index < 48; index++) {
            out.write(index * 5);
        }
        return out.toByteArray();
    }

    private static List<Entry> arqThumbnail(ByteOrder order, long rawOffset, long stripOffset) {
        var entries = new ArrayList<Entry>();
        entries.add(longs(order, 254, 1));
        entries.add(longs(order, 256, 4));
        entries.add(longs(order, 257, 4));
        entries.add(shorts(order, 258, 8, 8, 8));
        entries.add(shorts(order, 259, 1));
        entries.add(shorts(order, 262, 2));
        entries.add(ascii(271, "SONY"));
        entries.add(ascii(272, "ILCE-7RM3"));
        entries.add(longs(order, 273, stripOffset));
        entries.add(shorts(order, 277, 3));
        entries.add(longs(order, 278, 4));
        entries.add(longs(order, 279, 48));
        entries.add(longs(order, 330, rawOffset));
        return entries;
    }

    private List<Entry> arqEntries(ByteOrder order, long stripOffset, int stripBytes) {
        var entries = new ArrayList<Entry>();
        entries.add(longs(order, 254, 0));
        entries.add(longs(order, 256, width));
        entries.add(longs(order, 257, height));
        entries.add(shorts(order, 258, 16, 16, 16, 16));
        entries.add(shorts(order, 259, 1));
        entries.add(shorts(order, 262, 32892));
        entries.add(longs(order, 273, stripOffset));
        entries.add(shorts(order, 277, 4));
        entries.add(longs(order, 278, height));
        entries.add(longs(order, 279, stripBytes));
        entries.add(new Entry(50710, 1, 4, new byte[]{0, 1, 1, 2}));
        entries.add(shorts(order, 0x7310, BLACK, BLACK, BLACK, BLACK));
        entries.add(shorts(order, 0x7313, 2400, 1024, 1024, 1700));
        entries.add(longs(order, 50717, 16383));
        return entries;
    }

    private List<Entry> dngMain(ByteOrder order, long[] subOffsets) {
        var entries = new ArrayList<Entry>();
        entries.add(longs(order, 254, 1));
        entries.add(ascii(271, "PENTAX"));
        entries.add(ascii(272, "PENTAX K-70"));
        entries.add(longs(order, 330, subOffsets));
        entries.add(new Entry(50706, 1, 4, new byte[]{1, 4, 0, 0}));
        entries.add(ascii(50708, "PENTAX K-70"));
        entries.add(srationals(order, 50721, 8050, -2470, -1170, -4370, 12350, 2190, -720, 1690, 6740));
        entries.add(shorts(order, 50778, 21));
        entries.add(rationals(order, 50728, 1000, 2100, 1000, 1000, 1000, 1500));
        return entries;
    }

    private List<Entry> dngRawEntries(ByteOrder order, long stripOffset) {
        var entries = new ArrayList<>(rawEntries(order, stripOffset, 16));
        entries.add(shorts(order, 50714, BLACK));
        entries.add(longs(order, 50717, 16383));
        return entries;
    }

    private List<Entry> rawEntries(ByteOrder order, long stripOffset, int bits) {
        var entries = new ArrayList<Entry>();
        entries.add(longs(order, 254, 0));
        entries.add(longs(order, 256, width));
        entries.add(longs(order, 257, height));
        entries.add(shorts(order, 258, bits));
        entries.add(shorts(order, 259, 1));
        entries.add(shorts(order, 262, 32803));
        entries.add(longs(order, 273, stripOffset));
        entries.add(shorts(order, 277, 1));
        entries.add(longs(order, 278, height));
        entries.add(longs(order, 279, (long) width * height * 2));
        entries.add(shorts(order, 33421, 2, 2));
        entries.add(new Entry(33422, 1, 4, new byte[]{0, 1, 1, 2}));
        return entries;
    }

    private record Entry(int tag, int type, int count, byte[] value) {
    }

    private static ByteArrayOutputStream header(ByteOrder order) {
        var out = new ByteArrayOutputStream();
        out.writeBytes(ByteBuffer.allocate(8).order(order).put((byte) 'I').put((byte) 'I').putShort((short) 42)
                .putInt(8).array());
        return out;
    }

    private static byte[] words(int[] values, ByteOrder order) {
        var buffer = ByteBuffer.allocate(values.length * 2).order(order);
        for (var value : values) {
            buffer.putShort((short) value);
        }
        return buffer.array();
    }

    private static Entry shorts(ByteOrder order, int tag, int... values) {
        var buffer = ByteBuffer.allocate(2 * values.length).order(order);
        for (var value : values) {
            buffer.putShort((short) value);
        }
        return new Entry(tag, 3, values.length, buffer.array());
    }

    private static Entry longs(ByteOrder order, int tag, long... values) {
        var buffer = ByteBuffer.allocate(4 * values.length).order(order);
        for (var value : values) {
            buffer.putInt((int) value);
        }
        return new Entry(tag, 4, values.length, buffer.array());
    }

    private static Entry rationals(ByteOrder order, int tag, int... pairs) {
        var entry = longs(order, tag, java.util.Arrays.stream(pairs).asLongStream().toArray());
        return new Entry(tag, 5, pairs.length / 2, entry.value());
    }

    // the color matrix values are in units of 1/10000
    private static Entry srationals(ByteOrder order, int tag, int... values) {
        var buffer = ByteBuffer.allocate(8 * values.length).order(order);
        for (var value : values) {
            buffer.putInt(value).putInt(10000);
        }
        return new Entry(tag, 10, values.length, buffer.array());
    }

    private static Entry ascii(int tag, String value) {
        var bytes = (value + "\0").getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        return new Entry(tag, 2, bytes.length, bytes);
    }

    private static byte[] directory(List<Entry> entries, long start, ByteOrder order) {
        var converted = new ArrayList<VendorTiffWriter.Entry>();
        for (var entry : entries) {
            converted.add(new VendorTiffWriter.Entry(entry.tag(), entry.type(), entry.count(), entry.value()));
        }
        return VendorTiffWriter.directory(converted, start, order);
    }
}
