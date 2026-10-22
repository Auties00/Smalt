/* Ports Esri LERC 4.2.0 (Lerc1Decode/CntZImage.cpp, Lerc1Decode/BitStuffer.cpp and the Lerc1 parts of Lerc.cpp).
 * Copyright 2015-2026 Esri; Apache License 2.0. Modified for Smalt. */
package com.github.auties00.smalt.image.internal.lerc;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.DataFormatException;

/**
 * Decodes legacy Lerc1 blobs: bands of 32-bit floating-point values, each a {@code CntZImage} of a count part,
 * which says which pixels are valid and which only the first band stores, and a value part, both coded in tiles.
 *
 * <p>Instances are not thread-safe.
 */
final class Lerc1 {
    /** The type string a band starts with. */
    private static final byte[] KEY = "CntZImage ".getBytes(StandardCharsets.US_ASCII);

    /** The version of the format. */
    private static final int VERSION = 11;

    /** The image type of a count and value image. */
    private static final int TYPE_CNT_Z = 8;

    /** The largest width or height. */
    private static final int MAX_SIDE = 40000;

    /** The largest error the decoder accepts. */
    private static final double MAX_Z_ERROR = 1e12;

    /** The bytes of the header of the first band and of a later band, which has no count part. */
    private static final int FIRST_HEADER = 67;
    private static final int LATER_HEADER = 51;

    /** The count and the value of each pixel. */
    private float[] counts = new float[0];
    private float[] values = new float[0];

    /** The image size, from the first band. */
    private int width;
    private int height;

    /** Whether every pixel is valid, so that value tiles hold every pixel. */
    private boolean ignoreMask;

    /** The values of the last bit-stuffed array, as many as the tile needs. */
    private int[] unstuffed = new int[0];

    /** The number of values the last bit-stuffed array holds. */
    private long unstuffedCount;

    /** The expanded validity bits of a run-length-coded count part. */
    private byte[] mask = new byte[0];

    /** The input. */
    private byte[] src;

    /**
     * {@return whether data starts like a Lerc1 blob}
     *
     * @param data the input
     * @param offset the first byte
     * @param length the bytes available
     */
    static boolean matches(byte[] data, int offset, int length) {
        return length >= KEY.length && Arrays.equals(data, offset, offset + KEY.length, KEY, 0, KEY.length);
    }

    /**
     * Describes a blob from its band headers and part sizes, without decoding its tiles.
     *
     * @param data the input
     * @param offset the first byte of the blob
     * @param length the bytes available
     * @return the description, of version 0
     * @throws DataFormatException if the first band is not a valid Lerc1 band
     */
    static LercDecoder.Info info(byte[] data, int offset, int length) throws DataFormatException {
        if (length < FIRST_HEADER) {
            throw new DataFormatException("truncated Lerc1 blob");
        }
        var end = offset + length;
        var columns = 0;
        var rows = 0;
        var bands = 0;
        var masks = 0;
        var at = offset;
        while ((long) (at - offset) + LATER_HEADER < length) {
            var first = bands == 0;
            if (!matches(data, at, end - at) || end - at < 34) {
                if (first) {
                    throw new DataFormatException("not a Lerc1 blob");
                }
                break;
            }
            var version = LercDecoder.intLE(data, at + 10);
            var type = LercDecoder.intLE(data, at + 14);
            var h = LercDecoder.intLE(data, at + 18);
            var w = LercDecoder.intLE(data, at + 22);
            var maxZError = doubleLE(data, at + 26);
            if (version != VERSION || type != TYPE_CNT_Z || h < 0 || w < 0 || h > MAX_SIDE || w > MAX_SIDE
                    || 8L * h * w > Integer.MAX_VALUE || maxZError > MAX_Z_ERROR || (first && (h == 0 || w == 0))) {
                if (first) {
                    throw new DataFormatException("invalid Lerc1 band header");
                }
                break;
            }
            var next = at + 34;
            var countPart = 0;
            var valid = true;
            for (var part = first ? 0 : 1; part < 2 && valid; part++) {
                if (end - next < 16) {
                    valid = false;
                    break;
                }
                var tilesDown = LercDecoder.intLE(data, next);
                var tilesAcross = LercDecoder.intLE(data, next + 4);
                var bytes = LercDecoder.intLE(data, next + 8);
                var maximum = floatLE(data, next + 12);
                if (bytes < 0 || bytes > end - next - 16) {
                    valid = false;
                    break;
                }
                if (part == 0) {
                    countPart = tilesDown == 0 && tilesAcross == 0 && bytes == 0 ? (maximum > 0 ? 1 : 2) : 3;
                }
                next += 16 + bytes;
            }
            if (!valid) {
                if (first) {
                    throw new DataFormatException("truncated Lerc1 band");
                }
                break;
            }
            if (first) {
                columns = w;
                rows = h;
                // a constant count part makes every pixel valid or none; the other kinds may leave some out
                masks = countPart == 1 ? 0 : 1;
            }
            at = next;
            bands++;
        }
        if (bands == 0) {
            throw new DataFormatException("truncated Lerc1 blob");
        }
        return new LercDecoder.Info(0, LercDecoder.FLOAT, 1, columns, rows, bands, masks, at - offset);
    }

    /**
     * Decodes every band of a blob.
     *
     * @param data the input
     * @param offset the first byte of the blob
     * @param length the bytes available
     * @param info the description from {@link #info}
     * @param out receives the values, band by band, pixel by pixel; zero outside the mask
     * @param valid receives the validity of each pixel of each band, 1 or 0
     * @throws DataFormatException if a band is invalid
     */
    void decode(byte[] data, int offset, int length, LercDecoder.Info info, double[] out, byte[] valid)
            throws DataFormatException {
        src = data;
        try {
            var end = offset + length;
            var at = offset;
            var pixels = info.rows() * info.columns();
            for (var band = 0; band < info.bands(); band++) {
                if ((long) (at - offset) + (band == 0 ? FIRST_HEADER : LATER_HEADER) > length) {
                    throw new DataFormatException("truncated Lerc1 blob");
                }
                at = band(at, end, band > 0);
                if (width != info.columns() || height != info.rows()) {
                    throw new DataFormatException("Lerc1 band of another size");
                }
                var base = band * pixels;
                for (var k = 0; k < pixels; k++) {
                    var inside = counts[k] > 0;
                    out[base + k] = inside ? values[k] : 0;
                    valid[base + k] = (byte) (inside ? 1 : 0);
                }
            }
        } finally {
            src = null;
        }
    }

    /**
     * Decodes one band.
     *
     * @param at the band position
     * @param end the end of the input
     * @param onlyValues whether the band has no count part and keeps the counts of the first band
     * @return the position after the band
     * @throws DataFormatException if the band is invalid
     */
    private int band(int at, int end, boolean onlyValues) throws DataFormatException {
        if (!matches(src, at, end - at)) {
            throw new DataFormatException("not a Lerc1 band");
        }
        at += KEY.length;
        if (end - at < 24) {
            throw new DataFormatException("truncated Lerc1 band header");
        }
        var version = LercDecoder.intLE(src, at);
        var type = LercDecoder.intLE(src, at + 4);
        var h = LercDecoder.intLE(src, at + 8);
        var w = LercDecoder.intLE(src, at + 12);
        var maxZError = doubleLE(src, at + 16);
        at += 24;
        if (version != VERSION || type != TYPE_CNT_Z) {
            throw new DataFormatException("invalid Lerc1 band header");
        }
        if (h < 0 || w < 0 || h > MAX_SIDE || w > MAX_SIDE || 8L * h * w > Integer.MAX_VALUE) {
            throw new DataFormatException("invalid Lerc1 image size " + w + "x" + h);
        }
        if (maxZError > MAX_Z_ERROR) {
            throw new DataFormatException("invalid Lerc1 maximum error");
        }
        if (!onlyValues) {
            if (w == 0 || h == 0) {
                throw new DataFormatException("empty Lerc1 image");
            }
            width = w;
            height = h;
            var size = w * h;
            if (counts.length < size) {
                counts = new float[size];
                values = new float[size];
            }
            Arrays.fill(counts, 0, size, 0);
            Arrays.fill(values, 0, size, 0);
        }
        ignoreMask = false;
        for (var part = onlyValues ? 1 : 0; part < 2; part++) {
            var valuePart = part == 1;
            if (end - at < 16) {
                throw new DataFormatException("truncated Lerc1 part header");
            }
            var tilesDown = LercDecoder.intLE(src, at);
            var tilesAcross = LercDecoder.intLE(src, at + 4);
            var bytes = LercDecoder.intLE(src, at + 8);
            var maximum = floatLE(src, at + 12);
            at += 16;
            if (bytes < 0 || bytes > end - at) {
                throw new DataFormatException("truncated Lerc1 part");
            }
            var partEnd = at + bytes;
            if (!valuePart && tilesDown == 0 && tilesAcross == 0) {
                var size = width * height;
                if (bytes == 0) {
                    Arrays.fill(counts, 0, size, maximum);
                    ignoreMask = maximum > 0;
                } else {
                    var maskBytes = (size + 7) >> 3;
                    if (mask.length < maskBytes) {
                        mask = new byte[maskBytes];
                    }
                    LercDecoder.rle(src, at, bytes, mask, maskBytes);
                    for (var k = 0; k < size; k++) {
                        counts[k] = (mask[k >> 3] & (0x80 >> (k & 7))) != 0 ? 1 : 0;
                    }
                }
            } else {
                tiles(at, partEnd, valuePart, maxZError, tilesDown, tilesAcross, maximum);
            }
            at = partEnd;
        }
        return at;
    }

    /**
     * Decodes the tiles of a part: equal tiles, then the remainders of the rows and columns.
     *
     * @param at the first tile
     * @param end the end of the part
     * @param valuePart whether the part holds values rather than counts
     * @param maxZError the largest error of the quantized values
     * @param tilesDown the equal tiles down
     * @param tilesAcross the equal tiles across
     * @param maximum the largest value of the image
     * @throws DataFormatException if a tile is invalid
     */
    private void tiles(int at, int end, boolean valuePart, double maxZError, int tilesDown, int tilesAcross,
                       float maximum) throws DataFormatException {
        if (tilesDown <= 0 || tilesAcross <= 0 || tilesDown > height || tilesAcross > width) {
            throw new DataFormatException("invalid Lerc1 tiling");
        }
        for (var iTile = 0; iTile <= tilesDown; iTile++) {
            var tileHeight = height / tilesDown;
            var i0 = iTile * tileHeight;
            if (iTile == tilesDown) {
                tileHeight = height % tilesDown;
            }
            if (tileHeight == 0) {
                continue;
            }
            for (var jTile = 0; jTile <= tilesAcross; jTile++) {
                var tileWidth = width / tilesAcross;
                var j0 = jTile * tileWidth;
                if (jTile == tilesAcross) {
                    tileWidth = width % tilesAcross;
                }
                if (tileWidth == 0) {
                    continue;
                }
                at = valuePart ? valueTile(at, end, i0, i0 + tileHeight, j0, j0 + tileWidth, maxZError, maximum)
                        : countTile(at, end, i0, i0 + tileHeight, j0, j0 + tileWidth);
            }
        }
    }

    /**
     * Decodes one tile of the count part.
     *
     * @return the position after the tile
     */
    private int countTile(int at, int end, int i0, int i1, int j0, int j1) throws DataFormatException {
        need(at, end, 1);
        var flag = src[at++] & 0xFF;
        if (flag == 2) {
            return at;
        }
        if (flag == 3 || flag == 4) {
            var count = flag == 3 ? -1 : 1;
            for (var i = i0; i < i1; i++) {
                Arrays.fill(counts, i * width + j0, i * width + j1, count);
                Arrays.fill(values, i * width + j0, i * width + j1, 0);
            }
            return at;
        }
        if ((flag & 63) > 4) {
            throw new DataFormatException("invalid Lerc1 count tile");
        }
        if (flag == 0) {
            need(at, end, 4L * (i1 - i0) * (j1 - j0));
            for (var i = i0; i < i1; i++) {
                for (var j = j0; j < j1; j++, at += 4) {
                    counts[i * width + j] = floatLE(src, at);
                }
            }
            return at;
        }
        var offsetBytes = offsetBytes(flag >> 6);
        need(at, end, offsetBytes);
        var offset = readFloat(at, offsetBytes);
        at += offsetBytes;
        var pixels = (i1 - i0) * (j1 - j0);
        at = unstuff(at, end, pixels);
        if (unstuffedCount < pixels) {
            throw new DataFormatException("too few Lerc1 counts in a tile");
        }
        var k = 0;
        for (var i = i0; i < i1; i++) {
            for (var j = j0; j < j1; j++) {
                counts[i * width + j] = offset + (float) (unstuffed[k++] & 0xFFFFFFFFL);
            }
        }
        return at;
    }

    /**
     * Decodes one tile of the value part.
     *
     * @return the position after the tile
     */
    private int valueTile(int at, int end, int i0, int i1, int j0, int j1, double maxZError, float maximum)
            throws DataFormatException {
        need(at, end, 1);
        var flagByte = src[at++] & 0xFF;
        var flag = flagByte & 63;
        if (flag == 2) {
            for (var i = i0; i < i1; i++) {
                for (var j = j0; j < j1; j++) {
                    if (counts[i * width + j] > 0) {
                        values[i * width + j] = 0;
                    }
                }
            }
            return at;
        }
        if (flag > 3) {
            throw new DataFormatException("invalid Lerc1 value tile");
        }
        if (flag == 0) {
            for (var i = i0; i < i1; i++) {
                for (var j = j0; j < j1; j++) {
                    if (counts[i * width + j] > 0) {
                        need(at, end, 4);
                        values[i * width + j] = floatLE(src, at);
                        at += 4;
                    }
                }
            }
            return at;
        }
        var offsetBytes = offsetBytes(flagByte >> 6);
        need(at, end, offsetBytes);
        var offset = readFloat(at, offsetBytes);
        at += offsetBytes;
        if (flag == 3) {
            for (var i = i0; i < i1; i++) {
                for (var j = j0; j < j1; j++) {
                    if (counts[i * width + j] > 0) {
                        values[i * width + j] = offset;
                    }
                }
            }
            return at;
        }
        var needed = 0;
        if (ignoreMask) {
            needed = (i1 - i0) * (j1 - j0);
        } else {
            for (var i = i0; i < i1; i++) {
                for (var j = j0; j < j1; j++) {
                    needed += counts[i * width + j] > 0 ? 1 : 0;
                }
            }
        }
        at = unstuff(at, end, needed);
        if (unstuffedCount < needed) {
            throw new DataFormatException("too few Lerc1 values in a tile");
        }
        var scale = 2 * maxZError;
        var k = 0;
        for (var i = i0; i < i1; i++) {
            for (var j = j0; j < j1; j++) {
                if (ignoreMask || counts[i * width + j] > 0) {
                    var z = (float) (offset + (unstuffed[k++] & 0xFFFFFFFFL) * scale);
                    values[i * width + j] = maximum < z ? maximum : z;
                }
            }
        }
        return at;
    }

    /**
     * Returns the size of a tile offset from the top two bits of its flag.
     *
     * @throws DataFormatException if the bits name no size
     */
    private static int offsetBytes(int bits67) throws DataFormatException {
        return switch (bits67) {
            case 0 -> 4;
            case 1 -> 2;
            case 2 -> 1;
            default -> throw new DataFormatException("invalid Lerc1 number size");
        };
    }

    /**
     * Reads a number stored as a signed byte, a signed little-endian 16-bit integer or a little-endian float.
     */
    private float readFloat(int at, int bytes) {
        return switch (bytes) {
            case 1 -> src[at];
            case 2 -> (short) ((src[at] & 0xFF) | (src[at + 1] & 0xFF) << 8);
            default -> floatLE(src, at);
        };
    }

    /**
     * Reads a bit-stuffed array of unsigned integers: a byte holding the bit count and, in its top two bits,
     * the size of the element count, the count, then the elements packed from the most significant bit of
     * little-endian 32-bit words, the last word cut to the bytes it needs.
     *
     * @param at the array position
     * @param end the end of the part
     * @param needed the elements the caller reads; only these many are unpacked
     * @return the position after the array
     * @throws DataFormatException if the array is invalid or truncated
     */
    private int unstuff(int at, int end, int needed) throws DataFormatException {
        need(at, end, 1);
        var bitsByte = src[at++] & 0xFF;
        var countBytes = offsetBytes(bitsByte >> 6);
        var numBits = bitsByte & 63;
        need(at, end, countBytes);
        long count = switch (countBytes) {
            case 1 -> src[at] & 0xFF;
            case 2 -> (src[at] & 0xFF) | (src[at + 1] & 0xFF) << 8;
            default -> LercDecoder.intLE(src, at) & 0xFFFFFFFFL;
        };
        at += countBytes;
        if (numBits >= 32) {
            throw new DataFormatException("invalid Lerc1 bit count");
        }
        unstuffedCount = count;
        var used = (int) Math.min(count, needed);
        if (unstuffed.length < used) {
            unstuffed = new int[Math.max(used, 64)];
        }
        var totalBits = count * numBits;
        var words = (totalBits + 31) / 32;
        if (words == 0) {
            Arrays.fill(unstuffed, 0, used, 0);
            return at;
        }
        var bytes = (totalBits + 7) / 8;
        need(at, end, bytes);
        var tail = (int) (((totalBits & 31) + 7) >> 3);
        var source = 0L;
        var bitPos = 0;
        for (var i = 0; i < used; i++) {
            var value = word(at, source, words, tail);
            if (32 - bitPos >= numBits) {
                unstuffed[i] = (value << bitPos) >>> (32 - numBits);
                bitPos += numBits;
                if (bitPos == 32) {
                    bitPos = 0;
                    source++;
                }
            } else {
                source++;
                var high = (value << bitPos) >>> (32 - numBits);
                bitPos -= 32 - numBits;
                unstuffed[i] = high | word(at, source, words, tail) >>> (32 - bitPos);
            }
        }
        return (int) (at + bytes);
    }

    /**
     * Returns a word of a bit-stuffed array: little-endian, the last one holding only its first bytes, moved
     * to its top.
     */
    private int word(int at, long index, long words, int tail) {
        var base = (int) (at + 4 * index);
        if (index < words - 1 || tail == 0) {
            return LercDecoder.intLE(src, base);
        }
        var value = 0;
        for (var b = 0; b < tail; b++) {
            value |= (src[base + b] & 0xFF) << (8 * b);
        }
        return value << (8 * (4 - tail));
    }

    private static void need(int at, int end, long count) throws DataFormatException {
        if (end - at < count) {
            throw new DataFormatException("truncated Lerc1 tile");
        }
    }

    private static float floatLE(byte[] data, int at) {
        return Float.intBitsToFloat(LercDecoder.intLE(data, at));
    }

    private static double doubleLE(byte[] data, int at) {
        return Double.longBitsToDouble((LercDecoder.intLE(data, at) & 0xFFFFFFFFL)
                | (long) LercDecoder.intLE(data, at + 4) << 32);
    }
}
