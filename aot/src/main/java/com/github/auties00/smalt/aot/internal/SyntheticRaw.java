package com.github.auties00.smalt.aot.internal;

import com.github.auties00.smalt.image.RgbaPicture;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.Deflater;

/**
 * Writes the DNG files the camera raw training workloads decode, deterministically: a single
 * strip of linear 16-bit sensor data derived from a synthetic picture, either a Bayer mosaic or
 * three-channel linear raw data, with the color tags a renderer needs.
 */
final class SyntheticRaw {
    /**
     * The black level of the sensor data.
     */
    private static final int BLACK = 512;

    /**
     * The white level of the sensor data.
     */
    private static final int WHITE = 60_000;

    /**
     * Prevents instantiation.
     */
    private SyntheticRaw() {
        throw new AssertionError();
    }

    /**
     * Writes an uncompressed DNG whose sensor data is the RGGB Bayer mosaic of a picture.
     *
     * @param picture the picture, whose width and height are even
     * @return the DNG file
     */
    static byte[] bayer(RgbaPicture picture) {
        var width = picture.width();
        var height = picture.height();
        var samples = new short[width * height];
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                var channel = (y & 1) == 0 ? ((x & 1) == 0 ? 0 : 1) : ((x & 1) == 0 ? 1 : 2);
                samples[y * width + x] = (short) linear(picture.pixels()[y * picture.stride() + x * 4 + channel]);
            }
        }
        return write(width, height, 1, samples, false);
    }

    /**
     * Writes a Deflate-compressed DNG whose sensor data is three-channel linear raw data of a
     * picture, which needs no demosaicing.
     *
     * @param picture the sRGB picture
     * @return the DNG file
     */
    static byte[] linearRaw(RgbaPicture picture) {
        var width = picture.width();
        var height = picture.height();
        var samples = new short[width * height * 3];
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                for (var channel = 0; channel < 3; channel++) {
                    samples[(y * width + x) * 3 + channel] = (short) linear(picture.pixels()[y * picture.stride() + x * 4 + channel]);
                }
            }
        }
        return write(width, height, 3, samples, true);
    }

    /**
     * Maps an 8-bit sRGB-like value to a linear sensor value between the black and white levels.
     *
     * @param value the 8-bit gamma-encoded value
     * @return the sensor value
     */
    private static int linear(byte value) {
        var normalized = (value & 0xFF) / 255.0;
        return BLACK + (int) Math.round(normalized * normalized * (WHITE - BLACK) * 0.9);
    }

    /**
     * Writes a little-endian DNG with one strip.
     *
     * @param width the width in pixels
     * @param height the height in pixels
     * @param channels {@code 1} for a Bayer mosaic, {@code 3} for linear raw data
     * @param samples the 16-bit samples, interleaved
     * @param deflate whether the strip is Deflate-compressed
     * @return the file
     */
    private static byte[] write(int width, int height, int channels, short[] samples, boolean deflate) {
        var raw = ByteBuffer.allocate(samples.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (var sample : samples) {
            raw.putShort(sample);
        }
        var strip = deflate ? deflate(raw.array()) : raw.array();
        var tags = new TreeMap<Integer, byte[]>();
        var types = new TreeMap<Integer, Integer>();
        var counts = new TreeMap<Integer, Integer>();
        longs(tags, types, counts, 254, 0);
        longs(tags, types, counts, 256, width);
        longs(tags, types, counts, 257, height);
        shorts(tags, types, counts, 258, channels == 1 ? new int[]{16} : new int[]{16, 16, 16});
        shorts(tags, types, counts, 259, deflate ? 8 : 1);
        shorts(tags, types, counts, 262, channels == 1 ? 32803 : 34892);
        ascii(tags, types, counts, 271, "Smalt");
        ascii(tags, types, counts, 272, "Synthetic");
        longs(tags, types, counts, 273, 0);
        shorts(tags, types, counts, 274, 1);
        shorts(tags, types, counts, 277, channels);
        longs(tags, types, counts, 278, height);
        longs(tags, types, counts, 279, strip.length);
        if (channels == 1) {
            shorts(tags, types, counts, 33421, 2, 2);
            put(tags, types, counts, 33422, 1, 4, new byte[]{0, 1, 1, 2});
        }
        put(tags, types, counts, 50706, 1, 4, new byte[]{1, 4, 0, 0});
        longs(tags, types, counts, 50714, BLACK);
        longs(tags, types, counts, 50717, WHITE);
        rationals(tags, types, counts, 50721, true, 0.8, -0.2, -0.1, -0.4, 1.3, 0.1, -0.1, 0.2, 0.7);
        rationals(tags, types, counts, 50728, false, 0.5, 1, 0.7);
        shorts(tags, types, counts, 50778, 21);
        var directorySize = 2 + 12 * tags.size() + 4;
        var valuesStart = 8 + directorySize;
        var values = new ByteArrayOutputStream();
        var directory = ByteBuffer.allocate(directorySize).order(ByteOrder.LITTLE_ENDIAN);
        directory.putShort((short) tags.size());
        var stripOffsetPosition = -1;
        for (var entry : tags.entrySet()) {
            var tag = entry.getKey();
            var value = entry.getValue();
            directory.putShort((short) (int) tag).putShort((short) (int) types.get(tag)).putInt(counts.get(tag));
            if (tag == 273) {
                stripOffsetPosition = directory.position();
                directory.putInt(0);
            } else if (value.length <= 4) {
                directory.put(java.util.Arrays.copyOf(value, 4));
            } else {
                directory.putInt(valuesStart + values.size());
                values.writeBytes(value);
                if ((values.size() & 1) != 0) {
                    values.write(0);
                }
            }
        }
        directory.putInt(0);
        directory.putInt(stripOffsetPosition, valuesStart + values.size());
        var file = new ByteArrayOutputStream();
        file.writeBytes(new byte[]{'I', 'I', 42, 0, 8, 0, 0, 0});
        file.writeBytes(directory.array());
        file.writeBytes(values.toByteArray());
        file.writeBytes(strip);
        return file.toByteArray();
    }

    /**
     * Adds an entry.
     *
     * @param tags the values by tag
     * @param types the field types by tag
     * @param counts the value counts by tag
     * @param tag the TIFF tag number
     * @param type the field type
     * @param count the value count
     * @param value the little-endian value bytes
     */
    private static void put(Map<Integer, byte[]> tags, Map<Integer, Integer> types, Map<Integer, Integer> counts,
                            int tag, int type, int count, byte[] value) {
        tags.put(tag, value);
        types.put(tag, type);
        counts.put(tag, count);
    }

    /**
     * Adds an entry of 16-bit values.
     *
     * @param tags the values by tag
     * @param types the field types by tag
     * @param counts the value counts by tag
     * @param tag the TIFF tag number
     * @param values the 16-bit values
     */
    private static void shorts(Map<Integer, byte[]> tags, Map<Integer, Integer> types, Map<Integer, Integer> counts,
                               int tag, int... values) {
        var buffer = ByteBuffer.allocate(values.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (var value : values) {
            buffer.putShort((short) value);
        }
        put(tags, types, counts, tag, 3, values.length, buffer.array());
    }

    /**
     * Adds an entry of 32-bit values.
     *
     * @param tags the values by tag
     * @param types the field types by tag
     * @param counts the value counts by tag
     * @param tag the TIFF tag number
     * @param values the 32-bit values
     */
    private static void longs(Map<Integer, byte[]> tags, Map<Integer, Integer> types, Map<Integer, Integer> counts,
                              int tag, int... values) {
        var buffer = ByteBuffer.allocate(values.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (var value : values) {
            buffer.putInt(value);
        }
        put(tags, types, counts, tag, 4, values.length, buffer.array());
    }

    /**
     * Adds an entry of rationals with a denominator of 10000.
     *
     * @param tags the values by tag
     * @param types the field types by tag
     * @param counts the value counts by tag
     * @param tag the TIFF tag number
     * @param signed whether the rationals are signed
     * @param values the numerators and denominators, in pairs
     */
    private static void rationals(Map<Integer, byte[]> tags, Map<Integer, Integer> types, Map<Integer, Integer> counts,
                                  int tag, boolean signed, double... values) {
        var buffer = ByteBuffer.allocate(values.length * 8).order(ByteOrder.LITTLE_ENDIAN);
        for (var value : values) {
            buffer.putInt((int) Math.round(value * 10000)).putInt(10000);
        }
        put(tags, types, counts, tag, signed ? 10 : 5, values.length, buffer.array());
    }

    /**
     * Adds an ASCII entry.
     *
     * @param tags the values by tag
     * @param types the field types by tag
     * @param counts the value counts by tag
     * @param tag the TIFF tag number
     * @param value the text, without its terminating zero
     */
    private static void ascii(Map<Integer, byte[]> tags, Map<Integer, Integer> types, Map<Integer, Integer> counts,
                              int tag, String value) {
        var bytes = (value + "\0").getBytes(StandardCharsets.US_ASCII);
        put(tags, types, counts, tag, 2, bytes.length, bytes);
    }

    /**
     * Compresses bytes with zlib.
     *
     * @param bytes the data to compress
     * @return the compressed bytes
     */
    private static byte[] deflate(byte[] bytes) {
        var deflater = new Deflater();
        deflater.setInput(bytes);
        deflater.finish();
        var out = new ByteArrayOutputStream();
        var buffer = new byte[8192];
        while (!deflater.finished()) {
            out.write(buffer, 0, deflater.deflate(buffer));
        }
        deflater.end();
        return out.toByteArray();
    }
}
