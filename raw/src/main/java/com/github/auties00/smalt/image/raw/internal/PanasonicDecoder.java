package com.github.auties00.smalt.image.raw.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.raw.CfaColor;
import com.github.auties00.smalt.image.raw.CfaPattern;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.io.MediaSource;

import java.util.Arrays;
import java.util.List;

/**
 * Decodes the sensor data of Panasonic RW2 files, raw format versions 4, 5 and 6 (tag
 * {@code 0x002D}).
 *
 * <p>The three versions pack photosites row by row into 16-byte blocks, read as 128-bit
 * little-endian integers:
 * <ul>
 * <li>Version 4 (12-bit data) stores 14 photosites per block, read from the most significant bit
 * down, in the chunk layout of version 5. A block holds two 12-bit values (the first two
 * photosites), then four groups of a 2-bit code {@code c} and three 8-bit fields {@code f} for the
 * next twelve. With {@code p} the photosite two to the left, a field of 0 repeats {@code p}; code 3
 * gives {@code (f << 4) | (p & 15)}; otherwise the value is {@code (f << c) | (p & ((1 << c) - 1))}
 * when {@code p} is below {@code 128 << c} and {@code p + ((f - 128) << c)} after. A 12-bit value
 * whose high eight bits are zero marks a missing photosite: only those eight bits are stored, the
 * photosite is zero, and the next photosite of its color in the block is stored as a 12-bit value
 * in place of its field.</li>
 * <li>Version 5 (14-bit data) stores nine 14-bit photosites per block, the first in the least
 * significant bits. The data is laid out in chunks of {@code 0x4000} bytes whose first
 * {@code 0x2008} bytes of the stream are stored last: the stream byte {@code i} of a chunk is at
 * chunk offset {@code i + 0x1FF8} for {@code i < 0x2008} and {@code i - 0x2008} after.</li>
 * <li>Version 6 (14-bit data) stores eleven photosites per block, read from the most significant
 * bit down, with no chunk reordering: two 14-bit values (the first two photosites), then three
 * groups of a 2-bit code {@code c} and three 10-bit fields {@code f} for the next nine. Code 3
 * gives the absolute value {@code f << 4}; otherwise, with the shift {@code c} and {@code p} the
 * value of the photosite two to the left, the value is {@code f << c} when {@code p} is below
 * {@code 512 << c} and {@code p + ((f - 512) << c)} otherwise. Every value is then lowered by 15,
 * so the black level of tag {@code 0x001C} applies unchanged.</li>
 * </ul>
 *
 * <p>Version 8 (12, 14 or 16-bit data, the 2022 and later models) codes vertical tiles, each a
 * Huffman-coded stream ({@link #version8}).
 *
 * <p>Photosites the camera stores as zero are defective: each is replaced by the mean, rounded to
 * nearest, of the non-zero photosites of its color among its four nearest neighbors of that
 * color (the diagonal neighbors for green, the neighbors two rows or columns away otherwise).
 *
 * @implNote This implementation's layouts were checked against Adobe DNG Converter, run as a black
 * box on CC0 sample files, whose stored values it reproduces exactly; the treatment of
 * zero photosites is the converter's. Version 5's packing, version 6's layout, its
 * absolute-value rule and its offset of 15, and version 8's bit order, unit order and
 * prediction were worked out from the converter's values. Version 4's chunk layout, block
 * layout, code rules and missing-photosite markers were derived the same way, without
 * reference to any other decoder (the raw module's test-scope
 * {@code PanasonicDerivation} records and re-runs the derivation, chunk layout included). The
 * converter keeps the photosites of a few columns past the sensor's right border less than this
 * decoder, so a zero photosite on the converter's last column is filled from different
 * neighbors.
 */
final class PanasonicDecoder {
    /**
     * The size of a chunk of the version 4 and 5 layout.
     */
    static final int CHUNK = 0x4000;

    /**
     * The number of stream bytes a chunk stores at its end.
     */
    static final int SPLIT = 0x2008;

    /**
     * The level by which version 6 values sit above the black level of tags {@code 0x001C} to
     * {@code 0x001E}; the decoder lowers them by it.
     */
    static final int PEDESTAL = 15;

    /**
     * Prevents instantiation.
     */
    private PanasonicDecoder() {
        throw new AssertionError();
    }

    /**
     * Returns whether a raw format version is decoded.
     *
     * @param version the value of tag {@code 0x002D}
     * @return whether {@link #decode} reads it
     */
    static boolean supports(int version) {
        return version >= 4 && version <= 6;
    }

    /**
     * Returns how many photosites of a row the stored rows hold for a version: the photosites of
     * the version's groups.
     *
     * @param version the raw format version
     * @return the photosites of a group
     */
    static int group(int version) {
        return switch (version) {
            case 4 -> 14;
            case 5 -> 9;
            default -> 11;
        };
    }

    /**
     * Decodes the sensor data into a plane and repairs its zero photosites.
     *
     * @param data the data, from the first byte of the sensor data to the end of the file
     * @param version the raw format version, 4, 5 or 6
     * @param plane the plane to fill
     * @param cfa the color filter array, to find the neighbors of zero photosites
     */
    static void decode(byte[] data, int version, SensorPlane plane, CfaPattern cfa) {
        if (version == 4) {
            version4(reorder(data), plane);
        } else if (version == 5) {
            version5(reorder(data), plane);
        } else {
            version6(reverseBlocks(data), plane);
        }
        repairZeros(plane, cfa, version == 6 ? PEDESTAL : 0);
    }

    /**
     * Undoes the chunk layout of version 5.
     *
     * @param data the stored data
     * @return the stream, padded with zeros to whole chunks
     */
    private static byte[] reorder(byte[] data) {
        var chunks = (data.length + CHUNK - 1) / CHUNK;
        var out = new byte[chunks * CHUNK];
        for (var chunk = 0; chunk < chunks; chunk++) {
            var base = chunk * CHUNK;
            for (var index = 0; index < CHUNK; index++) {
                var physical = base + (index < SPLIT ? index + (CHUNK - SPLIT) : index - SPLIT);
                out[base + index] = physical < data.length ? data[physical] : 0;
            }
        }
        return out;
    }

    /**
     * Reverses the bytes of each 16-byte block, so the blocks read most significant bit first.
     *
     * @param data the stored data
     * @return the reversed data, padded with zeros to whole blocks
     */
    private static byte[] reverseBlocks(byte[] data) {
        var out = new byte[(data.length + 15) & ~15];
        for (var index = 0; index < data.length; index++) {
            out[(index & ~15) | (15 - (index & 15))] = data[index];
        }
        return out;
    }

    /**
     * Decodes version 4 data.
     *
     * @param stream the stream, chunks reordered
     * @param plane the plane to fill
     */
    private static void version4(byte[] stream, SensorPlane plane) {
        var blocks = plane.width / 14;
        var values = new int[14];
        for (var row = 0; row < plane.height; row++) {
            for (var block = 0; block < blocks; block++) {
                var start = (row * blocks + block) * 16;
                if (start + 16 > stream.length) {
                    return;
                }
                var high = 0L;
                var low = 0L;
                for (var k = 0; k < 8; k++) {
                    high = (high << 8) | (stream[start + 15 - k] & 0xFF);
                    low = (low << 8) | (stream[start + 7 - k] & 0xFF);
                }
                block4(high, low, values);
                var at = row * plane.width + block * 14;
                for (var k = 0; k < 14; k++) {
                    plane.integers[at + k] = (short) Math.min(values[k], 0xFFFF);
                }
            }
        }
    }

    /**
     * Decodes one block of version 4 data.
     *
     * @param high the block's 64 most significant bits
     * @param low the block's 64 least significant bits
     * @param values the 14 photosites to fill, zero where missing
     */
    static void block4(long high, long low, int[] values) {
        var position = 0;
        var absolute = 3;
        var code = 0;
        for (var index = 0; index < 14; index++) {
            if (index >= 2 && (index - 2) % 3 == 0) {
                code = field(high, low, position, 2);
                position += 2;
            }
            var color = 1 << (index & 1);
            if ((absolute & color) != 0) {
                var top = field(high, low, position, 8);
                position += 8;
                if (top == 0) {
                    values[index] = 0;
                } else {
                    values[index] = (top << 4) | field(high, low, position, 4);
                    position += 4;
                    absolute &= ~color;
                }
            } else {
                var f = field(high, low, position, 8);
                position += 8;
                values[index] = step(f, code, values[index - 2]);
            }
        }
    }

    /**
     * Returns a photosite of version 4 data from its field, its group's code and the photosite two
     * to the left.
     *
     * @param f the 8-bit field
     * @param code the 2-bit code of the field's group
     * @param previous the photosite two to the left
     * @return the photosite
     */
    static int step(int f, int code, int previous) {
        if (f == 0) {
            return previous;
        }
        if (code == 3) {
            return (f << 4) | (previous & 15);
        }
        if (previous < 128 << code) {
            return (f << code) | (previous & ((1 << code) - 1));
        }
        return previous + ((f - 128) << code);
    }

    /**
     * Returns a field of a 128-bit block, counting bits from the most significant one.
     *
     * @param high the block's 64 most significant bits
     * @param low the block's 64 least significant bits
     * @param position the index of the field's first bit, from 0
     * @param bits the field width, from 1 to 32
     * @return the field, zero for bits past the block
     */
    static int field(long high, long low, int position, int bits) {
        var value = 0;
        for (var k = 0; k < bits; k++) {
            var bit = position + k;
            var set = bit < 64 ? (high >>> (63 - bit)) & 1 : bit < 128 ? (low >>> (127 - bit)) & 1 : 0;
            value = (value << 1) | (int) set;
        }
        return value;
    }

    /**
     * Decodes version 5 data.
     *
     * @param stream the stream, chunks reordered
     * @param plane the plane to fill
     */
    private static void version5(byte[] stream, SensorPlane plane) {
        var blocks = plane.width / 9;
        for (var row = 0; row < plane.height; row++) {
            for (var block = 0; block < blocks; block++) {
                var start = (row * blocks + block) * 16;
                Unpack.lsbFirst(stream, start, Math.min(start + 16, stream.length), 14, plane.integers,
                        row * plane.width + block * 9, 9);
            }
        }
    }

    /**
     * Decodes version 6 data.
     *
     * @param stream the stream, blocks reversed
     * @param plane the plane to fill
     */
    private static void version6(byte[] stream, SensorPlane plane) {
        var blocks = plane.width / 11;
        var bits = new MsbBits(stream, 0, stream.length);
        var out = plane.integers;
        var previous = new int[2];
        for (var row = 0; row < plane.height; row++) {
            for (var block = 0; block < blocks; block++) {
                var at = row * plane.width + block * 11;
                previous[0] = bits.read(14);
                previous[1] = bits.read(14);
                out[at] = (short) previous[0];
                out[at + 1] = (short) previous[1];
                for (var group = 0; group < 3; group++) {
                    var code = bits.read(2);
                    for (var k = 0; k < 3; k++) {
                        var index = 2 + group * 3 + k;
                        var field = bits.read(10);
                        var prior = previous[index & 1];
                        int value;
                        if (code == 3) {
                            value = field << 4;
                        } else if (prior < 512 << code) {
                            value = field << code;
                        } else {
                            value = prior + ((field - 512) << code);
                        }
                        previous[index & 1] = value;
                        out[at + index] = (short) Math.clamp(value, 0, 0xFFFF);
                    }
                }
                bits.read(4);
            }
        }
    }

    /**
     * Describes one tile of version 8 data.
     *
     * @param offset the absolute offset of the tile's stream
     * @param left the column of the tile's first photosite
     * @param width the tile width
     * @param height the tile height
     */
    record Tile(long offset, int left, int width, int height) {
    }

    /**
     * Decodes version 8 data: tiles side by side, each its own stream.
     *
     * <p>Each byte of a stream is read from its least significant bit up, and each code or field
     * is assembled most significant bit first. The tile is coded two rows at a time, in units of
     * 2x2 photosites from left to right, each unit's photosites in the order upper-left,
     * lower-left, upper-right, lower-right. A photosite is a Huffman code of the table of tag
     * {@code 0x0040} giving the bit length {@code c} of its difference, then {@code c} bits
     * extended as in a JPEG DC table; the difference adds to the prediction, the photosite two
     * columns to the left in the tile, or for the first unit of a row pair the photosite two rows
     * above, and zero for the first unit of the tile.
     *
     * @param source the raw file
     * @param tiles the tiles, left to right
     * @param lookup the Huffman table, from {@link #prefixTable}
     * @param plane the plane to fill
     * @param limits the resource bounds
     */
    static void version8(MediaSource source, List<Tile> tiles, int[] lookup,
                         SensorPlane plane, MediaLimits limits) {
        var out = plane.integers;
        var width = plane.width;
        for (var tile : tiles) {
            var length = (long) tile.width() * tile.height() * 2 + 64;
            LimitChecks.checkAllocation(limits, length);
            var data = new byte[(int) Math.min(length, Integer.MAX_VALUE - 16)];
            var read = 0;
            while (read < data.length) {
                var count = source.read(tile.offset() + read, data, read, data.length - read);
                if (count <= 0) {
                    break;
                }
                read += count;
            }
            for (var index = 0; index < read; index++) {
                data[index] = (byte) (Integer.reverse(data[index] & 0xFF) >>> 24);
            }
            var bits = new MsbBits(data, 0, read);
            for (var row = 0; row < tile.height(); row += 2) {
                for (var column = 0; column < tile.width(); column += 2) {
                    for (var k = 0; k < 4; k++) {
                        var entry = lookup[bits.peek(16)];
                        bits.skip(entry & 0xFF);
                        var size = entry >>> 8;
                        var difference = bits.read(size);
                        if (size > 0 && difference < 1 << (size - 1)) {
                            difference -= (1 << size) - 1;
                        }
                        var x = tile.left() + column + (k >> 1);
                        var y = row + (k & 1);
                        if (x >= width || y >= plane.height) {
                            continue;
                        }
                        int prediction;
                        if (column >= 2) {
                            prediction = out[y * width + x - 2] & 0xFFFF;
                        } else if (row >= 2) {
                            prediction = out[(y - 2) * width + x] & 0xFFFF;
                        } else {
                            prediction = 0;
                        }
                        out[y * width + x] = (short) Math.clamp(prediction + difference, 0, 0xFFFF);
                    }
                }
            }
        }
    }

    /**
     * Builds the lookup table of a prefix code given as explicit codes, canonical or not: for
     * each 16-bit prefix, the value of the code it starts with above the code's length in the low
     * byte. A code that another, shorter code starts is unreachable and left out, as the tables of
     * 12 and 14-bit files list codes for the bit lengths their data never needs.
     *
     * @param codes the code of each value, right-aligned
     * @param lengths the bit length of each code, from 1 to 16
     * @return the table of 65536 entries
     * @throws com.github.auties00.smalt.MediaException.Malformed if a length is invalid or the
     * codes leave a prefix uncovered
     */
    static int[] prefixTable(int[] codes, int[] lengths) {
        var lookup = new int[1 << 16];
        var filled = 0;
        for (var length = 1; length <= 16; length++) {
            for (var value = 0; value < codes.length; value++) {
                if (lengths[value] < 1 || lengths[value] > 16 || codes[value] >>> lengths[value] != 0) {
                    throw new MediaException.Malformed("invalid Huffman code length "
                            + lengths[value]);
                }
                if (lengths[value] != length) {
                    continue;
                }
                var first = codes[value] << (16 - length);
                if (lookup[first] != 0) {
                    continue;
                }
                for (var fill = 0; fill < 1 << (16 - length); fill++) {
                    lookup[first + fill] = (value << 8) | length;
                }
                filled += 1 << (16 - length);
            }
        }
        if (filled != 1 << 16) {
            throw new MediaException.Malformed("incomplete Huffman code");
        }
        return lookup;
    }

    /**
     * Replaces zero photosites with the rounded mean of their non-zero same-color neighbors and
     * lowers every value by an offset.
     *
     * @param plane the decoded plane, modified in place
     * @param cfa the color filter array
     * @param offset the offset subtracted from every value, clamped at zero
     */
    private static void repairZeros(SensorPlane plane, CfaPattern cfa, int offset) {
        var samples = plane.integers;
        var width = plane.width;
        var height = plane.height;
        int[] zeros = null;
        var count = 0;
        for (var index = 0; index < samples.length; index++) {
            if (samples[index] == 0) {
                if (zeros == null || count == zeros.length) {
                    zeros = Arrays.copyOf(zeros == null ? new int[0] : zeros, Math.max(64, count * 2));
                }
                zeros[count++] = index;
            }
        }
        var fixed = new short[count];
        for (var k = 0; k < count; k++) {
            var y = zeros[k] / width;
            var x = zeros[k] % width;
            var green = cfa.color(y % cfa.height(), x % cfa.width()) == CfaColor.GREEN;
            var sum = 0;
            var used = 0;
            for (var n = 0; n < 4; n++) {
                var dx = green ? ((n & 1) == 0 ? -1 : 1) : (n == 0 ? -2 : n == 1 ? 2 : 0);
                var dy = green ? (n < 2 ? -1 : 1) : (n == 2 ? -2 : n == 3 ? 2 : 0);
                var xx = x + dx;
                var yy = y + dy;
                if (xx < 0 || yy < 0 || xx >= width || yy >= height) {
                    continue;
                }
                var neighbor = samples[yy * width + xx] & 0xFFFF;
                if (neighbor != 0) {
                    sum += neighbor;
                    used++;
                }
            }
            fixed[k] = (short) (used == 0 ? 0 : (sum + used / 2) / used);
        }
        for (var k = 0; k < count; k++) {
            samples[zeros[k]] = fixed[k];
        }
        if (offset != 0) {
            for (var index = 0; index < samples.length; index++) {
                samples[index] = (short) Math.max(0, (samples[index] & 0xFFFF) - offset);
            }
        }
    }
}
