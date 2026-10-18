package com.github.auties00.smalt.image.tiff;

import java.io.ByteArrayOutputStream;
import java.util.Random;
import java.util.TreeMap;

/**
 * Builds uncompressed, single-strip TIFF files from packed samples, independently of the encoder
 * under test, so that libtiff's tiffcp can transcode them into every variant.
 */
final class TiffFiles {
    private final int width;
    private final int height;
    private final int samples;
    private final int bits;
    private boolean little = true;
    private int photometric;
    private int format = 1;
    private int[] extra = new int[0];
    private int[] colorMap;
    private byte[] data;
    private boolean planar;
    private final TreeMap<Integer, long[]> shorts = new TreeMap<>();
    private final TreeMap<Integer, long[]> rationals = new TreeMap<>();
    private final TreeMap<Integer, byte[]> undefined = new TreeMap<>();

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

    /** Sets the packed rows, ceil(width * samples * bits / 8) bytes each, in the file's byte order. */
    TiffFiles data(byte[] packed) {
        data = packed;
        return this;
    }

    int rowBytes() {
        return (width * samples * bits + 7) / 8;
    }

    byte[] bytes() {
        var entries = new TreeMap<Integer, byte[]>();
        var types = new TreeMap<Integer, Integer>();
        var counts = new TreeMap<Integer, Integer>();
        put(entries, types, counts, 256, 4, new long[]{width});
        put(entries, types, counts, 257, 4, new long[]{height});
        var bitsValues = new long[samples];
        java.util.Arrays.fill(bitsValues, bits);
        put(entries, types, counts, 258, 3, bitsValues);
        put(entries, types, counts, 259, 3, new long[]{1});
        put(entries, types, counts, 262, 3, new long[]{photometric});
        var planes = planar ? samples : 1;
        var strips = new long[planes];
        var stripCounts = new long[planes];
        for (var i = 0; i < planes; i++) {
            strips[i] = 8 + (long) i * data.length / planes;
            stripCounts[i] = data.length / planes;
        }
        put(entries, types, counts, 273, 4, strips);
        put(entries, types, counts, 277, 3, new long[]{samples});
        put(entries, types, counts, 278, 4, new long[]{height});
        put(entries, types, counts, 279, 4, stripCounts);
        put(entries, types, counts, 284, 3, new long[]{planar ? 2 : 1});
        if (colorMap != null) {
            var values = new long[colorMap.length];
            for (var i = 0; i < values.length; i++) {
                values[i] = colorMap[i];
            }
            put(entries, types, counts, 320, 3, values);
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
        var ifdOffset = 8 + data.length + (data.length & 1);
        var tableSize = 2 + entries.size() * 12 + 4;
        var out = new ByteArrayOutputStream();
        out.write(little ? 'I' : 'M');
        out.write(little ? 'I' : 'M');
        write(out, 42, 2);
        write(out, ifdOffset, 4);
        out.writeBytes(data);
        if ((data.length & 1) != 0) {
            out.write(0);
        }
        write(out, entries.size(), 2);
        var extraOffset = ifdOffset + tableSize;
        var overflow = new ByteArrayOutputStream();
        for (var entry : entries.entrySet()) {
            var tag = entry.getKey();
            var value = entry.getValue();
            write(out, tag, 2);
            write(out, types.get(tag), 2);
            write(out, counts.get(tag), 4);
            if (value.length <= 4) {
                out.writeBytes(value);
                out.writeBytes(new byte[4 - value.length]);
            } else {
                write(out, extraOffset + overflow.size(), 4);
                overflow.writeBytes(value);
                if ((value.length & 1) != 0) {
                    overflow.write(0);
                }
            }
        }
        write(out, 0, 4);
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
            write(out, value, size);
        }
        return out.toByteArray();
    }

    private void write(ByteArrayOutputStream out, long value, int size) {
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
        var rowBytes = (width * samples * bits + 7) / 8;
        var out = new byte[rowBytes * height];
        for (var y = 0; y < height; y++) {
            if (bits % 8 == 0) {
                var bytes = bits / 8;
                for (var i = 0; i < width * samples; i++) {
                    var value = values[y * width * samples + i];
                    for (var b = 0; b < bytes; b++) {
                        var shift = little ? 8 * b : 8 * (bytes - 1 - b);
                        out[y * rowBytes + i * bytes + b] = (byte) (value >>> shift);
                    }
                }
            } else {
                var bit = 0L;
                for (var i = 0; i < width * samples; i++) {
                    var value = values[y * width * samples + i];
                    for (var b = bits - 1; b >= 0; b--) {
                        if (((value >>> b) & 1) != 0) {
                            out[y * rowBytes + (int) (bit >>> 3)] |= (byte) (0x80 >>> (bit & 7));
                        }
                        bit++;
                    }
                }
            }
        }
        return out;
    }
}
