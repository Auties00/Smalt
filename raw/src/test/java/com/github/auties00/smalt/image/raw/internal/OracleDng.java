package com.github.auties00.smalt.image.raw.internal;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

/**
 * Reads the raw image of an uncompressed 16-bit DNG file, the kind Adobe DNG Converter writes with {@code -u},
 * without the decoder under test: the stored samples (every sample of a pixel, for the two-sample files of
 * dual-photodiode sensors), the color filter array tags and the opcode list the converter leaves to be applied after
 * demosaicing (its lens correction).
 *
 * @param width the stored width
 * @param height the stored height
 * @param samplesPerPixel the samples of each pixel
 * @param samples the samples, interleaved row by row
 * @param cfaColumns the columns of the filter array repeat
 * @param cfaRows the rows of the filter array repeat
 * @param cfa the filter array colors, row by row
 * @param layout the {@code CFALayout} value
 * @param opcodeList3 the {@code OpcodeList3} bytes, empty when the file has none
 */
record OracleDng(int width, int height, int samplesPerPixel, short[] samples, int cfaColumns, int cfaRows, int[] cfa,
                 int layout, byte[] opcodeList3) {
    /**
     * Reads a file.
     *
     * @param path the file
     * @return its raw image
     */
    static OracleDng read(Path path) {
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(path);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        var data = ByteBuffer.wrap(bytes).order(bytes[0] == 'I' ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);
        var queue = new ArrayDeque<Long>();
        queue.add(data.getInt(4) & 0xFFFFFFFFL);
        while (!queue.isEmpty()) {
            var offset = (int) (long) queue.poll();
            if (offset <= 0 || offset >= bytes.length) {
                continue;
            }
            var tags = directory(data, offset);
            var subs = tags.get(330);
            if (subs != null) {
                for (var sub : subs) {
                    queue.add(sub);
                }
            }
            var next = data.getInt(offset + 2 + 12 * (data.getShort(offset) & 0xFFFF)) & 0xFFFFFFFFL;
            if (next != 0) {
                queue.add(next);
            }
            var photometric = value(tags, 262, 0);
            if (value(tags, 254, 1) != 0 || (photometric != 32803 && photometric != 34892)) {
                continue;
            }
            var width = (int) value(tags, 256, 0);
            var height = (int) value(tags, 257, 0);
            var spp = (int) value(tags, 277, 1);
            if (value(tags, 259, 1) != 1 || value(tags, 258, 0) != 16) {
                throw new IllegalArgumentException("not an uncompressed 16-bit DNG: " + path);
            }
            var samples = new short[width * height * spp];
            if (tags.containsKey(273)) {
                var at = 0;
                var offsets = tags.get(273);
                var counts = tags.get(279);
                for (var strip = 0; strip < offsets.length; strip++) {
                    for (var k = 0; k + 1 < counts[strip] && at < samples.length; k += 2) {
                        samples[at++] = data.getShort((int) offsets[strip] + k);
                    }
                }
            } else {
                var tileWidth = (int) value(tags, 322, 0);
                var tileHeight = (int) value(tags, 323, 0);
                var across = (width + tileWidth - 1) / tileWidth;
                var offsets = tags.get(324);
                for (var tile = 0; tile < offsets.length; tile++) {
                    var tx = tile % across * tileWidth;
                    var ty = tile / across * tileHeight;
                    for (var y = 0; y < tileHeight && ty + y < height; y++) {
                        for (var x = 0; x < tileWidth && tx + x < width; x++) {
                            for (var s = 0; s < spp; s++) {
                                samples[((ty + y) * width + tx + x) * spp + s] =
                                        data.getShort((int) offsets[tile] + ((y * tileWidth + x) * spp + s) * 2);
                            }
                        }
                    }
                }
            }
            // a linear DNG file has no filter array
            var dimensions = tags.getOrDefault(33421, new long[]{0, 0});
            var pattern = tags.getOrDefault(33422, new long[0]);
            var cfa = new int[pattern.length];
            for (var index = 0; index < cfa.length; index++) {
                cfa[index] = (int) pattern[index];
            }
            var list = tags.getOrDefault(51022, new long[0]);
            var opcodes = new byte[list.length];
            for (var index = 0; index < opcodes.length; index++) {
                opcodes[index] = (byte) list[index];
            }
            return new OracleDng(width, height, spp, samples, (int) dimensions[1], (int) dimensions[0], cfa,
                    (int) value(tags, 50711, 1), opcodes);
        }
        throw new IllegalArgumentException("no CFA image in " + path);
    }

    /**
     * {@return the sample {@code s} of the pixel at a position}
     *
     * @param x the column
     * @param y the row
     * @param s the sample
     */
    int sample(int x, int y, int s) {
        return samples[(y * width + x) * samplesPerPixel + s] & 0xFFFF;
    }

    private static long value(Map<Integer, long[]> tags, int tag, long fallback) {
        var values = tags.get(tag);
        return values == null || values.length == 0 ? fallback : values[0];
    }

    private static Map<Integer, long[]> directory(ByteBuffer data, int offset) {
        var tags = new HashMap<Integer, long[]>();
        var count = data.getShort(offset) & 0xFFFF;
        for (var index = 0; index < count; index++) {
            var entry = offset + 2 + 12 * index;
            var tag = data.getShort(entry) & 0xFFFF;
            var type = data.getShort(entry + 2) & 0xFFFF;
            var number = data.getInt(entry + 4);
            var size = switch (type) {
                case 3, 8 -> 2;
                case 4, 9, 13 -> 4;
                case 5, 10, 12, 16 -> 8;
                default -> 1;
            };
            if (number < 0 || (long) number * size > data.capacity()) {
                continue;
            }
            var at = number * size <= 4 ? entry + 8 : data.getInt(entry + 8);
            var values = new long[number];
            for (var k = 0; k < number; k++) {
                values[k] = switch (type) {
                    case 3 -> data.getShort(at + 2 * k) & 0xFFFF;
                    case 8 -> data.getShort(at + 2 * k);
                    case 4, 13 -> data.getInt(at + 4 * k) & 0xFFFFFFFFL;
                    case 9 -> data.getInt(at + 4 * k);
                    case 5, 10, 12, 16 -> data.getLong(at + 8 * k);
                    default -> data.get(at + k) & 0xFF;
                };
            }
            tags.put(tag, values);
        }
        return tags;
    }
}
