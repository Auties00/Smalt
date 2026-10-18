/* Ports X3F tools (x3f_io.c: x3f_load_camf_decode_type2, camf_decode_type4, x3f_setup_camf_entries;
   x3f_meta.c) at commit e8b48ca. Copyright 2015 Roland and Erik Karlsson; BSD 3-Clause License. Modified for
   Smalt. */
package com.github.auties00.smalt.image.raw.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.internal.io.ByteArrays;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Holds the camera metadata block ({@code CAMF}) of a Sigma X3F file: named matrices, texts and
 * property lists of calibration and capture data.
 *
 * <p>The block's body is obfuscated in one of two ways, chosen by its type: type 2 XORs each byte
 * with a pseudo-random stream seeded by a key in the block header; type 4 codes the body as 12-bit
 * values with the same Huffman difference coding as the TRUE raw planes ({@link X3fDecoder}). The
 * decoded body is a sequence of entries, each {@code "CMb"} plus a kind letter ({@code M} a matrix,
 * {@code T} a text, {@code P} a property list), a version, its length, and the offsets of its name
 * and value. A matrix value holds the element type (0 signed 16-bit, 1 and 2 unsigned 32-bit, 3
 * 32-bit float, 5 unsigned 8-bit, 6 unsigned 16-bit), the number of dimensions, the offset of the
 * elements and each dimension's size.
 *
 * <p>Instances are immutable and thread-safe.
 *
 * @implNote This implementation ports the decoding of the X3F tools library, whose license was
 * checked; the entry names it reads are the ones that library uses. Type 5, which Quattro cameras
 * write, is not decoded because no Quattro raw image is decoded.
 */
final class X3fCamf {
    /**
     * The largest decoded body accepted, in bytes.
     */
    private static final int MAX_BODY = 64 << 20;

    /**
     * The most entries read.
     */
    private static final int MAX_ENTRIES = 1 << 16;

    /**
     * An entry: its kind letter, and the offsets of the entry and of its value in the body.
     *
     * @param kind {@code 'M'}, {@code 'T'} or {@code 'P'}
     * @param start the offset of the entry
     * @param value the offset of the value
     * @param end the offset after the entry
     */
    private record Entry(char kind, int start, int value, int end) {
    }

    /**
     * The decoded body.
     */
    private final byte[] body;

    /**
     * The entries by name; a later entry of the same name replaces an earlier one.
     */
    private final Map<String, Entry> entries;

    /**
     * Constructs a block over a decoded body.
     *
     * @param body the decoded body
     * @param entries the entries by name
     */
    private X3fCamf(byte[] body, Map<String, Entry> entries) {
        this.body = body;
        this.entries = entries;
    }

    /**
     * Decodes a block.
     *
     * @param type the block type, from the section header
     * @param header the four header values that follow the type
     * @param data the bytes after the 28-byte section header
     * @return the block, or {@code null} for a type this decoder does not read
     * @throws MediaException.Malformed if the body is corrupt
     */
    static X3fCamf decode(int type, long[] header, byte[] data) {
        byte[] body;
        switch (type) {
            case 2 -> body = unscramble(data, header[3]);
            case 4 -> body = unpack(data, header);
            default -> {
                return null;
            }
        }
        return new X3fCamf(body, index(body));
    }

    /**
     * Removes the type 2 obfuscation: each byte is XORed with a byte of a pseudo-random stream.
     *
     * @param data the obfuscated body
     * @param seed the key of the block header
     * @return the body
     */
    private static byte[] unscramble(byte[] data, long seed) {
        var body = new byte[data.length];
        var key = seed & 0xFFFFFFFFL;
        for (var index = 0; index < data.length; index++) {
            key = ((key * 1597 + 51749) & 0xFFFFFFFFL) % 244944;
            var scaled = ((key * 301593171L) >> 24) & 0xFFFFFFFFL;
            var mask = (int) (((((key << 8) - scaled) & 0xFFFFFFFFL) >>> 1) + scaled >>> 17);
            body[index] = (byte) (data[index] ^ mask);
        }
        return body;
    }

    /**
     * Decodes a type 4 body: 12-bit values in rows of {@code header[2]} values, at most
     * {@code header[3]} rows, with the TRUE difference coding seeded with {@code header[1]}, packed
     * two values in three bytes, most significant bits first, up to {@code header[0]} bytes.
     *
     * @param data the bytes after the section header: the code table, ending with a zero length,
     * then from offset 32 the coded values
     * @param header the decoded size, the seed, the values per row and the rows
     * @return the body
     * @throws MediaException.Malformed if the sizes are out of range or the coding is corrupt
     */
    private static byte[] unpack(byte[] data, long[] header) {
        var size = header[0];
        var columns = header[2];
        var rows = header[3];
        if (size <= 0 || size > MAX_BODY || columns <= 0 || rows <= 0 || columns * rows > (long) MAX_BODY) {
            throw new MediaException.Malformed("X3F CAMF block of " + size + " bytes in " + rows + " rows of "
                    + columns + " values");
        }
        var lookup = X3fDecoder.trueLookup(data, 0, data.length);
        var body = new byte[(int) size];
        var bits = new X3fDecoder.Bits(data, 32, data.length);
        var seed = (int) header[1];
        var rowStart = new int[]{seed, seed, seed, seed};
        var accumulator = new int[2];
        var out = 0;
        var odd = false;
        for (var row = 0; row < rows; row++) {
            for (var column = 0; column < columns; column++) {
                var difference = bits.difference(lookup);
                var parity = column & 1;
                var value = (column < 2 ? rowStart[(row & 1) * 2 + parity] : accumulator[parity]) + difference;
                accumulator[parity] = value;
                if (column < 2) {
                    rowStart[(row & 1) * 2 + parity] = value;
                }
                if (!odd) {
                    body[out++] = (byte) (value >> 4);
                    if (out >= body.length) {
                        return body;
                    }
                    body[out] = (byte) (value << 4);
                } else {
                    body[out++] |= (byte) ((value >> 8) & 0x0F);
                    if (out >= body.length) {
                        return body;
                    }
                    body[out++] = (byte) value;
                    if (out >= body.length) {
                        return body;
                    }
                }
                odd = !odd;
            }
        }
        return body;
    }

    /**
     * Indexes the entries of a body, stopping at the first bytes that do not start an entry.
     *
     * @param body the decoded body
     * @return the entries by name
     */
    private static Map<String, Entry> index(byte[] body) {
        var entries = new HashMap<String, Entry>();
        var position = 0;
        for (var count = 0; count < MAX_ENTRIES && position + 20 <= body.length; count++) {
            if (body[position] != 'C' || body[position + 1] != 'M' || body[position + 2] != 'b') {
                break;
            }
            var kind = (char) body[position + 3];
            var length = ByteArrays.u32(body, position + 8, true);
            var name = ByteArrays.u32(body, position + 12, true);
            var value = ByteArrays.u32(body, position + 16, true);
            if (length < 20 || length > body.length - position || name >= length || value > length) {
                break;
            }
            var text = cString(body, position + (int) name, position + (int) length);
            entries.put(text, new Entry(kind, position, position + (int) value, position + (int) length));
            position += (int) length;
        }
        return entries;
    }

    /**
     * Reads a NUL-terminated ISO 8859-1 string.
     *
     * @param data the bytes
     * @param start the index of the first character
     * @param end the index past which no character is read
     * @return the string
     */
    private static String cString(byte[] data, int start, int end) {
        var stop = start;
        while (stop < end && stop < data.length && data[stop] != 0) {
            stop++;
        }
        return new String(data, start, Math.max(0, stop - start), StandardCharsets.ISO_8859_1);
    }

    /**
     * Returns the elements of a matrix entry as numbers, row by row.
     *
     * @param name the entry name
     * @param count the number of elements required
     * @return the elements, or {@code null} when the entry is missing, is not a matrix or does not
     * hold exactly {@code count} elements
     */
    double[] numbers(String name, int count) {
        var values = numbers(name);
        return values != null && values.length == count ? values : null;
    }

    /**
     * Returns the elements of a matrix entry as numbers, row by row.
     *
     * @param name the entry name
     * @return the elements, or {@code null} when the entry is missing, is not a matrix or is
     * damaged
     */
    double[] numbers(String name) {
        var entry = entries.get(name);
        if (entry == null || entry.kind != 'M' || entry.value + 12 > entry.end) {
            return null;
        }
        var type = (int) ByteArrays.u32(body, entry.value, true);
        var dimensions = ByteArrays.u32(body, entry.value + 4, true);
        var offset = ByteArrays.u32(body, entry.value + 8, true);
        if (dimensions < 1 || dimensions > 3 || entry.value + 12 + 12 * dimensions > entry.end) {
            return null;
        }
        var count = 1L;
        for (var index = 0; index < dimensions; index++) {
            count *= ByteArrays.u32(body, entry.value + 12 + 12 * (int) index, true);
        }
        var width = switch (type) {
            case 0, 6 -> 2;
            case 1, 2, 3 -> 4;
            case 5 -> 1;
            default -> 0;
        };
        if (width == 0 || count > (entry.end - entry.start) || offset + count * width > entry.end - entry.start) {
            return null;
        }
        var values = new double[(int) count];
        var position = entry.start + (int) offset;
        for (var index = 0; index < values.length; index++) {
            values[index] = switch (type) {
                case 0 -> (short) ByteArrays.u16(body, position, true);
                case 1, 2 -> ByteArrays.u32(body, position, true);
                case 3 -> Float.intBitsToFloat((int) ByteArrays.u32(body, position, true));
                case 5 -> body[position] & 0xFF;
                default -> ByteArrays.u16(body, position, true);
            };
            position += width;
        }
        return values;
    }

    /**
     * Returns a value of a property list entry.
     *
     * @param list the name of the property list entry
     * @param name the property name
     * @return the value, or {@code null} when the list or the property is missing
     */
    String property(String list, String name) {
        var entry = entries.get(list);
        if (entry == null || entry.kind != 'P' || entry.value + 8 > entry.end) {
            return null;
        }
        var count = ByteArrays.u32(body, entry.value, true);
        var base = ByteArrays.u32(body, entry.value + 4, true);
        if (count > (entry.end - entry.value - 8) / 8) {
            return null;
        }
        for (var index = 0; index < count; index++) {
            var nameOffset = base + ByteArrays.u32(body, entry.value + 8 + 8 * index, true);
            var valueOffset = base + ByteArrays.u32(body, entry.value + 12 + 8 * index, true);
            var size = entry.end - entry.start;
            if (nameOffset >= size || valueOffset >= size) {
                continue;
            }
            if (name.equals(cString(body, entry.start + (int) nameOffset, entry.end))) {
                return cString(body, entry.start + (int) valueOffset, entry.end);
            }
        }
        return null;
    }

    /**
     * Returns whether an entry is present.
     *
     * @param name the entry name
     * @return whether the block holds an entry of that name
     */
    boolean has(String name) {
        return entries.containsKey(name);
    }
}
