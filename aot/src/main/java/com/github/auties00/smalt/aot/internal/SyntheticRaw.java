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
 * strip of linear 16-bit sensor data derived from a synthetic picture, either a Bayer mosaic,
 * three-channel linear raw data, a staggered mosaic with a gain table map and an RGB table, or a
 * Bayer mosaic coded as VC-5 as GoPro's GPR files store it, with the color tags a renderer needs.
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
     * The DNG compression code of VC-5 data.
     */
    private static final int VC5_COMPRESSION = 9;

    /**
     * The white level of the VC-5 sensor data, which decodes to 14-bit photosites.
     */
    private static final int VC5_WHITE = 16383;

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
        return write(width, height, 1, samples, false, false);
    }

    /**
     * Writes an uncompressed DNG whose sensor data is a mosaic of a picture in a staggered layout:
     * the two-by-four pattern of a Fujifilm SuperCCD sensor ({@code CFALayout} 2, even columns half a
     * row down), with a {@code ProfileGainTableMap2} and a background {@code RGBTables} table.
     *
     * @param picture the picture, whose width is a multiple of 4 and height even
     * @return the DNG file
     */
    static byte[] staggered(RgbaPicture picture) {
        var width = picture.width();
        var height = picture.height();
        int[] pattern = {0, 1, 2, 1, 2, 1, 0, 1};
        var samples = new short[width * height];
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                var channel = pattern[(y & 1) * 4 + (x & 3)];
                samples[y * width + x] = (short) linear(picture.pixels()[y * picture.stride() + x * 4 + channel]);
            }
        }
        return write(width, height, 1, samples, false, true);
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
        return write(width, height, 3, samples, true, false);
    }

    /**
     * Writes a DNG whose sensor data is the RGGB Bayer mosaic of a picture coded as a VC-5
     * bitstream (DNG compression 9), as GoPro's GPR files store it.
     *
     * @param picture the picture, whose width and height are even
     * @return the DNG file
     */
    static byte[] gpr(RgbaPicture picture) {
        var strip = SyntheticVc5.bitstream(picture, 1);
        return write(picture.width(), picture.height(), 1, strip, VC5_COMPRESSION, 0, VC5_WHITE, false);
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
     * Writes a little-endian DNG with one strip of 16-bit samples, at the black and white levels of
     * the sensor data.
     *
     * @param width the width in pixels
     * @param height the height in pixels
     * @param channels {@code 1} for a Bayer mosaic, {@code 3} for linear raw data
     * @param samples the 16-bit samples, interleaved
     * @param deflate whether the strip is Deflate-compressed
     * @param staggered whether the mosaic is the staggered two-by-four pattern, with the profile tables
     * @return the file
     */
    private static byte[] write(int width, int height, int channels, short[] samples, boolean deflate,
                                boolean staggered) {
        var raw = ByteBuffer.allocate(samples.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (var sample : samples) {
            raw.putShort(sample);
        }
        var strip = deflate ? deflate(raw.array()) : raw.array();
        return write(width, height, channels, strip, deflate ? 8 : 1, BLACK, WHITE, staggered);
    }

    /**
     * Writes a little-endian DNG with one strip of coded 16-bit samples.
     *
     * @param width the width in pixels
     * @param height the height in pixels
     * @param channels {@code 1} for a Bayer mosaic, {@code 3} for linear raw data
     * @param strip the strip's bytes
     * @param compression the strip's TIFF compression
     * @param black the black level
     * @param white the white level
     * @param staggered whether the mosaic is the staggered two-by-four pattern, with the profile tables
     * @return the file
     */
    private static byte[] write(int width, int height, int channels, byte[] strip, int compression, int black,
                                int white, boolean staggered) {
        var tags = new TreeMap<Integer, byte[]>();
        var types = new TreeMap<Integer, Integer>();
        var counts = new TreeMap<Integer, Integer>();
        longs(tags, types, counts, 254, 0);
        longs(tags, types, counts, 256, width);
        longs(tags, types, counts, 257, height);
        shorts(tags, types, counts, 258, channels == 1 ? new int[]{16} : new int[]{16, 16, 16});
        shorts(tags, types, counts, 259, compression);
        shorts(tags, types, counts, 262, channels == 1 ? 32803 : 34892);
        ascii(tags, types, counts, 271, "Smalt");
        ascii(tags, types, counts, 272, "Synthetic");
        longs(tags, types, counts, 273, 0);
        shorts(tags, types, counts, 274, 1);
        shorts(tags, types, counts, 277, channels);
        longs(tags, types, counts, 278, height);
        longs(tags, types, counts, 279, strip.length);
        if (channels == 1 && staggered) {
            shorts(tags, types, counts, 33421, 2, 4);
            put(tags, types, counts, 33422, 1, 8, new byte[]{0, 1, 2, 1, 2, 1, 0, 1});
            shorts(tags, types, counts, 50711, 2);
            rationals(tags, types, counts, 50718, false, 0.5, 1);
            put(tags, types, counts, 52544, 7, 80 + 16, gainTableMap());
            put(tags, types, counts, 52543, 7, 8 + 2 + 5 + 27 * 3, rgbTable());
        } else if (channels == 1) {
            shorts(tags, types, counts, 33421, 2, 2);
            put(tags, types, counts, 33422, 1, 4, new byte[]{0, 1, 1, 2});
        }
        put(tags, types, counts, 50706, 1, 4, new byte[]{1, 4, 0, 0});
        longs(tags, types, counts, 50714, black);
        longs(tags, types, counts, 50717, white);
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
     * Returns a {@code ProfileGainTableMap2} of two by two tables of four 8-bit gains between 0.5 and 2.
     *
     * @return the tag's bytes
     */
    private static byte[] gainTableMap() {
        var b = ByteBuffer.allocate(80 + 16).order(ByteOrder.BIG_ENDIAN);
        b.putInt(2).putInt(2).putDouble(1).putDouble(1).putDouble(0).putDouble(0).putInt(4);
        b.putFloat(0.25f).putFloat(0.5f).putFloat(0.25f).putFloat(0).putFloat(0);
        b.putInt(0).putFloat(1).putFloat(0.5f).putFloat(2);
        for (var index = 0; index < 16; index++) {
            b.put((byte) (64 + index * 11));
        }
        return b.array();
    }

    /**
     * Returns an {@code RGBTables} tag of one background table of three divisions, in sRGB primaries
     * and encoding with gamut extension, that raises the saturation of the colors.
     *
     * @return the tag's bytes
     */
    private static byte[] rgbTable() {
        var b = ByteBuffer.allocate(8 + 2 + 5 + 27 * 3).order(ByteOrder.BIG_ENDIAN);
        b.putInt(1).putInt(0).putShort((short) 0);
        b.put((byte) 3).put((byte) 0).put((byte) 1).put((byte) 0).put((byte) 1);
        for (var r = 0; r < 3; r++) {
            for (var g = 0; g < 3; g++) {
                for (var blue = 0; blue < 3; blue++) {
                    var mean = (r + g + blue) / 3.0;
                    for (var channel : new int[]{r, g, blue}) {
                        b.put((byte) Math.clamp(Math.round((mean + (channel - mean) * 1.2) * 127.5), 0, 255));
                    }
                }
            }
        }
        return b.array();
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
