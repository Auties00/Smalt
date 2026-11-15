package com.github.auties00.smalt.image.tiff;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.TreeMap;

/**
 * Builds TIFF files from packed samples, independently of the encoder under test, so that libtiff's tiffcp can
 * transcode them into every variant. A file holds one directory, or several chained with {@link #chain}, each of
 * which may list sub-directories ({@link #sub}); its samples are one uncompressed strip (or one strip per plane),
 * or the strips or tiles given to {@link #chunks}.
 */
final class TiffFiles {
    private final int width;
    private final int height;
    private final int samples;
    private final int bits;
    private int[] sampleBits;
    private boolean little = true;
    private int photometric;
    private int format = 1;
    private int[] extra = new int[0];
    private int[] colorMap;
    private byte[] data;
    private byte[][] chunks;
    private int tileWidth;
    private int tileHeight;
    private int rowsPerStrip;
    private boolean planar;
    private final List<TiffFiles> subs = new ArrayList<>();
    private final TreeMap<Integer, long[]> shorts = new TreeMap<>();
    private final TreeMap<Integer, long[]> longs = new TreeMap<>();
    private final TreeMap<Integer, long[]> rationals = new TreeMap<>();
    private final TreeMap<Integer, byte[]> undefined = new TreeMap<>();
    private final TreeMap<Integer, byte[]> ascii = new TreeMap<>();

    private TiffFiles(int width, int height, int samples, int bits) {
        this.width = width;
        this.height = height;
        this.samples = samples;
        this.bits = bits;
        this.photometric = samples >= 3 ? 2 : 1;
    }

    static TiffFiles of(int width, int height, int samples, int bits) {
        return new TiffFiles(width, height, samples, bits);
    }

    TiffFiles bigEndian() {
        little = false;
        return this;
    }

    TiffFiles photometric(int value) {
        photometric = value;
        return this;
    }

    TiffFiles format(int value) {
        format = value;
        return this;
    }

    /** Sets a BitsPerSample value per sample instead of the uniform one. */
    TiffFiles bits(int... values) {
        sampleBits = values;
        return this;
    }

    TiffFiles extra(int... values) {
        extra = values;
        return this;
    }

    TiffFiles colorMap(int[] values) {
        colorMap = values;
        return this;
    }

    /** Stores the samples in separate planes: the data holds the packed rows of each plane in turn. */
    TiffFiles planar() {
        planar = true;
        return this;
    }

    TiffFiles tag(int tag, long... values) {
        shorts.put(tag, values);
        return this;
    }

    /** Adds a LONG tag. */
    TiffFiles longTag(int tag, long... values) {
        longs.put(tag, values);
        return this;
    }

    /** Adds a RATIONAL (or, with a negative numerator, SRATIONAL) tag from numerator, denominator pairs. */
    TiffFiles rational(int tag, long... pairs) {
        rationals.put(tag, pairs);
        return this;
    }

    /** Adds an UNDEFINED tag holding raw bytes. */
    TiffFiles undefined(int tag, byte[] bytes) {
        undefined.put(tag, bytes);
        return this;
    }

    /** Adds an ASCII tag holding the strings, each followed by a NUL. */
    TiffFiles ascii(int tag, String... values) {
        var out = new ByteArrayOutputStream();
        for (var value : values) {
            out.writeBytes(value.getBytes(StandardCharsets.US_ASCII));
            out.write(0);
        }
        ascii.put(tag, out.toByteArray());
        return this;
    }

    /** Sets the packed rows, ceil(width * samples * bits / 8) bytes each, in the file's byte order. */
    TiffFiles data(byte[] packed) {
        data = packed;
        return this;
    }

    /** Stores the given strips or tiles, plane-major, instead of the data. */
    TiffFiles chunks(byte[]... values) {
        chunks = values;
        return this;
    }

    /** Stores the image in tiles of the given size; the chunks hold them row by row (plane-major). */
    TiffFiles tiles(int tileWidth, int tileHeight) {
        this.tileWidth = tileWidth;
        this.tileHeight = tileHeight;
        return this;
    }

    TiffFiles rowsPerStrip(int rows) {
        rowsPerStrip = rows;
        return this;
    }

    /** Lists a directory in this directory's SubIFDs. */
    TiffFiles sub(TiffFiles child) {
        subs.add(child);
        return this;
    }

    int rowBytes() {
        return (width * samples * bits + 7) / 8;
    }

    byte[] bytes() {
        return chain(this);
    }

    /** Writes the directories as one file, the first one first, each followed by its sub-directories. */
    static byte[] chain(TiffFiles... pages) {
        var little = pages[0].little;
        var order = new ArrayList<TiffFiles>();
        for (var page : pages) {
            order.add(page);
            order.addAll(page.subs);
        }
        var bases = new long[order.size()];
        var ifds = new long[order.size()];
        var base = 8L;
        for (var i = 0; i < order.size(); i++) {
            bases[i] = base;
            ifds[i] = base + order.get(i).dataLength();
            base += order.get(i).block(base, 0, new long[order.get(i).subs.size()]).length;
        }
        var out = new ByteArrayOutputStream();
        out.write(little ? 'I' : 'M');
        out.write(little ? 'I' : 'M');
        write(out, 42, 2, little);
        write(out, ifds[0], 4, little);
        var mainIndex = 0;
        for (var i = 0; i < order.size(); i++) {
            var page = order.get(i);
            var next = 0L;
            var subOffsets = new long[page.subs.size()];
            if (List.of(pages).contains(page)) {
                mainIndex++;
                // the next main page follows this page's sub-directories
                for (var j = i + 1 + page.subs.size(); j < order.size() && mainIndex < pages.length; j++) {
                    if (order.get(j) == pages[mainIndex]) {
                        next = ifds[j];
                        break;
                    }
                }
                for (var s = 0; s < subOffsets.length; s++) {
                    subOffsets[s] = ifds[i + 1 + s];
                }
            }
            out.writeBytes(page.block(bases[i], next, subOffsets));
        }
        return out.toByteArray();
    }

    private byte[][] storedChunks() {
        if (chunks != null) {
            return chunks;
        }
        var planes = planar ? samples : 1;
        var result = new byte[planes][];
        for (var i = 0; i < planes; i++) {
            result[i] = java.util.Arrays.copyOfRange(data, i * data.length / planes, (i + 1) * data.length / planes);
        }
        return result;
    }

    private long dataLength() {
        var length = 0L;
        for (var chunk : storedChunks()) {
            length += chunk.length + (chunk.length & 1);
        }
        return length;
    }

    private byte[] block(long base, long next, long[] subOffsets) {
        var stored = storedChunks();
        var entries = new TreeMap<Integer, byte[]>();
        var types = new TreeMap<Integer, Integer>();
        var counts = new TreeMap<Integer, Integer>();
        put(entries, types, counts, 256, 4, new long[]{width});
        put(entries, types, counts, 257, 4, new long[]{height});
        var bitsValues = new long[samples];
        for (var i = 0; i < samples; i++) {
            bitsValues[i] = sampleBits != null ? sampleBits[i] : bits;
        }
        put(entries, types, counts, 258, 3, bitsValues);
        put(entries, types, counts, 259, 3, new long[]{1});
        put(entries, types, counts, 262, 3, new long[]{photometric});
        var offsets = new long[stored.length];
        var sizes = new long[stored.length];
        var at = base;
        for (var i = 0; i < stored.length; i++) {
            offsets[i] = at;
            sizes[i] = stored[i].length;
            at += stored[i].length + (stored[i].length & 1);
        }
        if (tileWidth > 0) {
            put(entries, types, counts, 322, 4, new long[]{tileWidth});
            put(entries, types, counts, 323, 4, new long[]{tileHeight});
            put(entries, types, counts, 324, 4, offsets);
            put(entries, types, counts, 325, 4, sizes);
        } else {
            put(entries, types, counts, 273, 4, offsets);
            put(entries, types, counts, 278, 4, new long[]{rowsPerStrip > 0 ? rowsPerStrip : height});
            put(entries, types, counts, 279, 4, sizes);
        }
        put(entries, types, counts, 277, 3, new long[]{samples});
        put(entries, types, counts, 284, 3, new long[]{planar ? 2 : 1});
        if (colorMap != null) {
            var values = new long[colorMap.length];
            for (var i = 0; i < values.length; i++) {
                values[i] = colorMap[i];
            }
            put(entries, types, counts, 320, 3, values);
        }
        if (subOffsets.length > 0) {
            put(entries, types, counts, 330, 4, subOffsets);
        }
        if (extra.length > 0) {
            var values = new long[extra.length];
            for (var i = 0; i < values.length; i++) {
                values[i] = extra[i];
            }
            put(entries, types, counts, 338, 3, values);
        }
        if (format != 1) {
            var values = new long[samples];
            java.util.Arrays.fill(values, format);
            put(entries, types, counts, 339, 3, values);
        }
        for (var tag : shorts.entrySet()) {
            put(entries, types, counts, tag.getKey(), 3, tag.getValue());
        }
        for (var tag : longs.entrySet()) {
            put(entries, types, counts, tag.getKey(), 4, tag.getValue());
        }
        for (var tag : rationals.entrySet()) {
            var signed = java.util.Arrays.stream(tag.getValue()).anyMatch(value -> value < 0);
            entries.put(tag.getKey(), bytesOf(4, tag.getValue()));
            types.put(tag.getKey(), signed ? 10 : 5);
            counts.put(tag.getKey(), tag.getValue().length / 2);
        }
        for (var tag : undefined.entrySet()) {
            entries.put(tag.getKey(), tag.getValue());
            types.put(tag.getKey(), 7);
            counts.put(tag.getKey(), tag.getValue().length);
        }
        for (var tag : ascii.entrySet()) {
            entries.put(tag.getKey(), tag.getValue());
            types.put(tag.getKey(), 2);
            counts.put(tag.getKey(), tag.getValue().length);
        }
        var ifdOffset = at;
        var tableSize = 2 + entries.size() * 12 + 4;
        var out = new ByteArrayOutputStream();
        for (var chunk : stored) {
            out.writeBytes(chunk);
            if ((chunk.length & 1) != 0) {
                out.write(0);
            }
        }
        write(out, entries.size(), 2, little);
        var extraOffset = ifdOffset + tableSize;
        var overflow = new ByteArrayOutputStream();
        for (var entry : entries.entrySet()) {
            var tag = entry.getKey();
            var value = entry.getValue();
            write(out, tag, 2, little);
            write(out, types.get(tag), 2, little);
            write(out, counts.get(tag), 4, little);
            if (value.length <= 4) {
                out.writeBytes(value);
                out.writeBytes(new byte[4 - value.length]);
            } else {
                write(out, extraOffset + overflow.size(), 4, little);
                overflow.writeBytes(value);
                if ((value.length & 1) != 0) {
                    overflow.write(0);
                }
            }
        }
        write(out, next, 4, little);
        out.writeBytes(overflow.toByteArray());
        return out.toByteArray();
    }

    private void put(TreeMap<Integer, byte[]> entries, TreeMap<Integer, Integer> types, TreeMap<Integer, Integer> counts,
                     int tag, int type, long[] values) {
        entries.put(tag, bytesOf(type == 3 ? 2 : 4, values));
        types.put(tag, type);
        counts.put(tag, values.length);
    }

    private byte[] bytesOf(int size, long[] values) {
        var out = new ByteArrayOutputStream();
        for (var value : values) {
            write(out, value, size, little);
        }
        return out.toByteArray();
    }

    private static void write(ByteArrayOutputStream out, long value, int size, boolean little) {
        for (var i = 0; i < size; i++) {
            var shift = little ? 8 * i : 8 * (size - 1 - i);
            out.write((int) (value >>> shift));
        }
    }

    /** Builds smooth, noisy, edged sample values in [0, max], so every codec path is exercised. */
    static long[] pattern(int width, int height, int samples, long max, long seed) {
        var random = new Random(seed);
        var values = new long[width * height * samples];
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                for (var s = 0; s < samples; s++) {
                    double base = switch (s % 4) {
                        case 0 -> (double) x / Math.max(1, width - 1);
                        case 1 -> (double) y / Math.max(1, height - 1);
                        case 2 -> ((x / 8 + y / 8) & 1) == 0 ? 0.2 : 0.8;
                        default -> 0.5 + 0.5 * Math.sin((x + y) * 0.3);
                    };
                    var noise = (random.nextDouble() - 0.5) * 0.1;
                    var value = Math.clamp(base + noise, 0.0, 1.0);
                    if (random.nextInt(50) == 0) {
                        value = random.nextBoolean() ? 0 : 1;
                    }
                    values[(y * width + x) * samples + s] = Math.round(value * max);
                }
            }
        }
        return values;
    }

    /** Packs sample values into separate planes, each packed as one-sample rows. */
    static byte[] packPlanar(long[] values, int width, int height, int samples, int bits, boolean little) {
        var out = new java.io.ByteArrayOutputStream();
        for (var s = 0; s < samples; s++) {
            var plane = new long[width * height];
            for (var i = 0; i < plane.length; i++) {
                plane[i] = values[i * samples + s];
            }
            out.writeBytes(pack(plane, width, height, 1, bits, little));
        }
        return out.toByteArray();
    }

    /** Packs sample values MSB-first into rows padded to a byte, multi-byte samples in byte order. */
    static byte[] pack(long[] values, int width, int height, int samples, int bits, boolean little) {
        var widths = new int[samples];
        java.util.Arrays.fill(widths, bits);
        return packMixed(values, width, height, widths, little);
    }

    /**
     * Packs sample values whose sizes differ per sample into rows padded to a byte: whole-byte samples on byte
     * boundaries in byte order, every other sample MSB-first.
     */
    static byte[] packMixed(long[] values, int width, int height, int[] widths, boolean little) {
        var samples = widths.length;
        var pixelBits = 0;
        for (var value : widths) {
            pixelBits += value;
        }
        var rowBytes = (width * pixelBits + 7) / 8;
        var out = new byte[rowBytes * height];
        for (var y = 0; y < height; y++) {
            var bit = (long) y * rowBytes * 8;
            for (var i = 0; i < width * samples; i++) {
                var size = widths[i % samples];
                var value = values[y * width * samples + i];
                if ((bit & 7) == 0 && size % 8 == 0) {
                    var bytes = size / 8;
                    for (var b = 0; b < bytes; b++) {
                        var shift = little ? 8 * b : 8 * (bytes - 1 - b);
                        out[(int) (bit >>> 3) + b] = (byte) (value >>> shift);
                    }
                } else {
                    for (var b = size - 1; b >= 0; b--) {
                        var at = bit + (size - 1 - b);
                        if (((value >>> b) & 1) != 0) {
                            out[(int) (at >>> 3)] |= (byte) (0x80 >>> (at & 7));
                        }
                    }
                }
                bit += size;
            }
        }
        return out;
    }
}
