/* Ports Esri LERC 4.2.0 (Lerc.cpp, Lerc2.cpp, Lerc2.h, BitStuffer2.cpp, Huffman.cpp, Huffman.h, BitMask.cpp, RLE.cpp).
 * Copyright 2015-2026 Esri; Apache License 2.0, with the patent grant of its NOTICE. Modified for Smalt. */
package com.github.auties00.smalt.image.internal.lerc;

import java.util.Arrays;
import java.util.zip.DataFormatException;

/**
 * Decodes LERC (Limited Error Raster Compression) blobs of the Lerc2 format, versions 1 to 6, and of the
 * legacy Lerc1 format.
 *
 * <p>A blob holds one or more bands of {@code rows} by {@code columns} pixels of {@code depth} values
 * each, of one of eight data types, with a validity mask per band; a Lerc1 blob holds 32-bit
 * floating-point values, one per pixel. Decoded values are exactly those Esri's library decodes, stored
 * as {@code double}s that each hold a value of the blob's data type; pixels outside the mask are zero.
 *
 * <p>Instances are not thread-safe.
 */
public final class LercDecoder {
    /** Data type: signed 8-bit integer. */
    public static final int CHAR = 0;
    /** Data type: unsigned 8-bit integer. */
    public static final int BYTE = 1;
    /** Data type: signed 16-bit integer. */
    public static final int SHORT = 2;
    /** Data type: unsigned 16-bit integer. */
    public static final int USHORT = 3;
    /** Data type: signed 32-bit integer. */
    public static final int INT = 4;
    /** Data type: unsigned 32-bit integer. */
    public static final int UINT = 5;
    /** Data type: 32-bit floating point. */
    public static final int FLOAT = 6;
    /** Data type: 64-bit floating point. */
    public static final int DOUBLE = 7;

    private static final byte[] KEY = {'L', 'e', 'r', 'c', '2', ' '};
    private static final int CURRENT_VERSION = 6;

    /**
     * Describes a blob.
     *
     * @param version the Lerc2 version of the first band, or 0 for a Lerc1 blob
     * @param dataType the data type, from {@link #CHAR} to {@link #DOUBLE}
     * @param depth the values per pixel
     * @param columns the pixels across
     * @param rows the pixels down
     * @param bands the number of bands
     * @param masks 0 when every pixel of every band is valid, 1 when the bands share a mask, or the band
     * count when the masks differ; 1 for a Lerc1 blob unless its count part makes every pixel valid without
     * tiles
     * @param blobSize the bytes of all bands
     */
    public record Info(int version, int dataType, int depth, int columns, int rows, int bands, int masks,
                       long blobSize) {
    }

    /** The header of the current band. */
    private final Header header = new Header();

    /** The validity bits of the current band, most significant bit first; kept between bands. */
    private byte[] mask = new byte[0];
    private int maskColumns = -1;
    private int maskRows = -1;

    /** The per-depth value ranges of the current band. */
    private double[] zMin = new double[0];
    private double[] zMax = new double[0];

    /** The decoder of Lerc1 blobs, created on first use. */
    private Lerc1 lerc1;

    /** The reader of bit-packed arrays. */
    private final BitStuffer stuffer = new BitStuffer();

    /** The input position and end. */
    private byte[] src;
    private int pos;
    private int end;

    /**
     * Holds the fields of a Lerc2 band header.
     */
    private static final class Header {
        int version;
        int checksum;
        int rows;
        int columns;
        int depth;
        int validPixels;
        int microBlockSize;
        int blobSize;
        int dataType;
        int blobsMore;
        int passNoData;
        double maxZError;
        double zMin;
        double zMax;
        double noData;
        double noDataOriginal;

        boolean tryHuffmanInt() {
            return version >= 2 && (dataType == BYTE || dataType == CHAR) && maxZError == 0.5;
        }

        boolean tryHuffmanFloat() {
            return version >= 6 && (dataType == FLOAT || dataType == DOUBLE) && maxZError == 0;
        }
    }

    /**
     * Describes a blob from its band headers.
     *
     * @param data the input
     * @param offset the first byte of the blob
     * @param length the bytes available
     * @return the description
     * @throws DataFormatException if the data is not a valid Lerc2 or Lerc1 blob
     */
    public Info info(byte[] data, int offset, int length) throws DataFormatException {
        var at = offset;
        var limit = offset + length;
        var first = new Header();
        if (!readHeader(data, at, limit, first)) {
            if (Lerc1.matches(data, offset, length)) {
                return Lerc1.info(data, offset, length);
            }
            throw new DataFormatException("not a Lerc2 or Lerc1 blob");
        }
        var bandMask = maskBytes(data, at, limit, first);
        var masks = bandMask > 0 || first.validPixels == 0 ? 1 : 0;
        var total = (long) first.blobSize;
        if (total > length) {
            throw new DataFormatException("truncated Lerc2 blob");
        }
        var bands = 1;
        var more = first.version <= 5 || first.blobsMore > 0;
        var next = new Header();
        while (more && total < length && readHeader(data, (int) (offset + total), limit, next)) {
            if (next.depth != first.depth || next.columns != first.columns || next.rows != first.rows
                    || next.dataType != first.dataType) {
                throw new DataFormatException("Lerc2 bands of different shapes");
            }
            var nextMask = maskBytes(data, (int) (offset + total), limit, next);
            more = next.version <= 5 || next.blobsMore > 0;
            if (nextMask > 0 || next.validPixels != first.validPixels) {
                masks = 2;
            }
            if (total + next.blobSize > length) {
                throw new DataFormatException("truncated Lerc2 blob");
            }
            total += next.blobSize;
            bands++;
        }
        return new Info(first.version, first.dataType, first.depth, first.columns, first.rows, bands,
                masks > 1 ? bands : masks, total);
    }

    /**
     * Returns the size of the stored mask of a band, after its header.
     *
     * @param data the input
     * @param at the band position
     * @param limit the end of the input
     * @param header the parsed header
     * @return the mask byte count
     * @throws DataFormatException if the count is missing or negative
     */
    private static int maskBytes(byte[] data, int at, int limit, Header header) throws DataFormatException {
        var position = at + headerSize(header.version);
        if (limit - position < 4) {
            throw new DataFormatException("truncated Lerc2 mask");
        }
        var count = intLE(data, position);
        if (count < 0) {
            throw new DataFormatException("invalid Lerc2 mask size");
        }
        return count;
    }

    /**
     * Decodes every band of a blob.
     *
     * @param data the input
     * @param offset the first byte of the blob
     * @param length the bytes available
     * @param info the description from {@link #info}
     * @param values receives the values, band by band, pixel by pixel, depth by depth; at least
     * {@code bands * rows * columns * depth} long
     * @param valid receives the validity of each pixel of each band, 1 or 0; at least
     * {@code bands * rows * columns} long
     * @throws DataFormatException if a band is invalid
     */
    public void decode(byte[] data, int offset, int length, Info info, double[] values, byte[] valid)
            throws DataFormatException {
        if (info.version() == 0) {
            if (lerc1 == null) {
                lerc1 = new Lerc1();
            }
            lerc1.decode(data, offset, length, info, values, valid);
            return;
        }
        src = data;
        pos = offset;
        end = offset + length;
        maskColumns = -1;
        var pixels = info.rows() * info.columns();
        try {
            for (var band = 0; band < info.bands(); band++) {
                var bandStart = pos;
                band(values, band * pixels * info.depth(), info);
                for (var k = 0; k < pixels; k++) {
                    valid[band * pixels + k] = (byte) ((mask[k >> 3] >> (7 - (k & 7))) & 1);
                }
                if (header.version >= 6 && header.passNoData != 0 && info.depth() > 1) {
                    remapNoData(values, band * pixels * info.depth(), valid, band * pixels, info);
                }
                pos = bandStart + header.blobSize;
            }
        } finally {
            src = null;
            stuffer.src = null;
        }
    }

    /**
     * Decodes one band.
     *
     * @param values the output
     * @param base the first value of the band
     * @param info the description of the blob
     * @throws DataFormatException if the band is invalid
     */
    private void band(double[] values, int base, Info info) throws DataFormatException {
        var start = pos;
        if (!readHeader(src, pos, end, header)) {
            throw new DataFormatException("invalid Lerc2 band header");
        }
        var hd = header;
        if (hd.depth != info.depth() || hd.columns != info.columns() || hd.rows != info.rows()
                || hd.dataType != info.dataType()) {
            throw new DataFormatException("Lerc2 bands of different shapes");
        }
        if (end - start < hd.blobSize) {
            throw new DataFormatException("truncated Lerc2 band");
        }
        if (hd.version >= 3) {
            var skip = KEY.length + 8;
            if (hd.blobSize < skip) {
                throw new DataFormatException("invalid Lerc2 blob size");
            }
            if (fletcher32(src, start + skip, hd.blobSize - skip) != hd.checksum) {
                throw new DataFormatException("Lerc2 checksum mismatch");
            }
        }
        pos = start + headerSize(hd.version);
        readMask();
        var count = hd.rows * hd.columns * hd.depth;
        Arrays.fill(values, base, base + count, 0);
        if (hd.validPixels == 0) {
            return;
        }
        if (hd.zMin == hd.zMax) {
            fillConstant(values, base, false);
            return;
        }
        if (hd.version >= 4) {
            readRanges();
            if (Arrays.equals(zMin, 0, hd.depth, zMax, 0, hd.depth)) {
                fillConstant(values, base, true);
                return;
            }
        }
        need(1);
        var oneSweep = src[pos++];
        if (oneSweep != 0) {
            oneSweep(values, base);
            return;
        }
        if (hd.tryHuffmanInt() || hd.tryHuffmanFloat()) {
            need(1);
            var flag = src[pos++] & 0xFF;
            if (flag > 3 || (flag > 2 && hd.version < 6) || (flag > 1 && hd.version < 4)) {
                throw new DataFormatException("invalid Lerc2 encoding mode");
            }
            if (flag != 0) {
                if (hd.tryHuffmanInt()) {
                    if (flag == 1 || (hd.version >= 4 && flag == 2)) {
                        huffman(values, base, flag == 1);
                        return;
                    }
                    throw new DataFormatException("invalid Lerc2 encoding mode");
                }
                if (flag == 3) {
                    pos = LosslessFloat.decode(src, pos, end, values, base, hd.dataType == DOUBLE,
                            hd.depth == 1 ? hd.columns : hd.depth, hd.depth == 1 ? hd.rows : hd.columns * hd.rows);
                    return;
                }
                throw new DataFormatException("invalid Lerc2 encoding mode");
            }
        }
        tiles(values, base);
    }

    /**
     * Reads the validity mask of the band, keeping the previous band's mask when none is stored.
     *
     * @throws DataFormatException if the mask is invalid
     */
    private void readMask() throws DataFormatException {
        var hd = header;
        var total = hd.rows * hd.columns;
        need(4);
        var stored = intLE(src, pos);
        pos += 4;
        if (stored < 0 || ((hd.validPixels == 0 || hd.validPixels == total) && stored != 0)) {
            throw new DataFormatException("invalid Lerc2 mask");
        }
        var size = (total + 7) >> 3;
        if (maskColumns != hd.columns || maskRows != hd.rows) {
            mask = new byte[size];
            maskColumns = hd.columns;
            maskRows = hd.rows;
        }
        if (hd.validPixels == 0) {
            Arrays.fill(mask, (byte) 0);
        } else if (hd.validPixels == total) {
            Arrays.fill(mask, (byte) 0xFF);
        } else if (stored > 0) {
            need(stored);
            rle(src, pos, end - pos, mask, size);
            pos += stored;
        }
    }

    /**
     * Expands a run-length-coded mask.
     *
     * @param data the input
     * @param at the first byte
     * @param available the bytes available
     * @param out the mask
     * @param size the mask size
     * @throws DataFormatException if the runs are invalid
     */
    static void rle(byte[] data, int at, int available, byte[] out, int size) throws DataFormatException {
        if (available < 2) {
            throw new DataFormatException("truncated Lerc2 mask");
        }
        var remaining = available - 2;
        var index = 0;
        var count = shortLE(data, at);
        at += 2;
        while (count != -32768) {
            var n = count <= 0 ? -count : count;
            var stored = count <= 0 ? 1 : n;
            if (remaining < stored + 2 || index + n > size) {
                throw new DataFormatException("invalid Lerc2 mask runs");
            }
            if (count > 0) {
                System.arraycopy(data, at, out, index, n);
                at += n;
            } else {
                Arrays.fill(out, index, index + n, data[at++]);
            }
            index += n;
            remaining -= stored + 2;
            count = shortLE(data, at);
            at += 2;
        }
    }

    private boolean isValid(int k) {
        return (mask[k >> 3] & (0x80 >> (k & 7))) != 0;
    }

    private int validCount() {
        var total = header.rows * header.columns;
        var count = 0;
        for (var k = 0; k < total; k++) {
            if (isValid(k)) {
                count++;
            }
        }
        return count;
    }

    /**
     * Reads the per-depth value ranges.
     *
     * @throws DataFormatException if they are truncated
     */
    private void readRanges() throws DataFormatException {
        var depth = header.depth;
        if (zMin.length < depth) {
            zMin = new double[depth];
            zMax = new double[depth];
        }
        var size = typeSize(header.dataType);
        need(2L * depth * size);
        for (var i = 0; i < depth; i++) {
            zMin[i] = read(header.dataType);
        }
        for (var i = 0; i < depth; i++) {
            zMax[i] = read(header.dataType);
        }
    }

    /**
     * Fills the valid pixels with the minimum of each depth.
     *
     * @param values the output
     * @param base the first value of the band
     * @param ranges whether to use the per-depth minimums rather than the band minimum
     */
    private void fillConstant(double[] values, int base, boolean ranges) {
        var hd = header;
        var total = hd.rows * hd.columns;
        var depth = hd.depth;
        var constant = cast(hd.dataType, hd.zMin);
        for (var k = 0; k < total; k++) {
            if (isValid(k)) {
                for (var m = 0; m < depth; m++) {
                    values[base + k * depth + m] = ranges && depth > 1 ? cast(hd.dataType, zMin[m]) : constant;
                }
            }
        }
    }

    /**
     * Reads the values of the valid pixels stored without compression.
     *
     * @param values the output
     * @param base the first value of the band
     * @throws DataFormatException if they are truncated
     */
    private void oneSweep(double[] values, int base) throws DataFormatException {
        var hd = header;
        var depth = hd.depth;
        var total = hd.rows * hd.columns;
        need((long) validCount() * depth * typeSize(hd.dataType));
        for (var k = 0; k < total; k++) {
            if (isValid(k)) {
                for (var m = 0; m < depth; m++) {
                    values[base + k * depth + m] = read(hd.dataType);
                }
            }
        }
    }

    /**
     * Decodes the micro blocks of a band.
     *
     * @param values the output
     * @param base the first value of the band
     * @throws DataFormatException if a block is invalid
     */
    private void tiles(double[] values, int base) throws DataFormatException {
        var hd = header;
        var size = hd.microBlockSize;
        var tilesDown = (hd.rows + size - 1) / size;
        var tilesAcross = (hd.columns + size - 1) / size;
        for (var tile = 0; tile < tilesDown; tile++) {
            var i0 = tile * size;
            var i1 = tile == tilesDown - 1 ? hd.rows : i0 + size;
            for (var across = 0; across < tilesAcross; across++) {
                var j0 = across * size;
                var j1 = across == tilesAcross - 1 ? hd.columns : j0 + size;
                for (var depth = 0; depth < hd.depth; depth++) {
                    tile(values, base, i0, i1, j0, j1, depth);
                }
            }
        }
    }

    /**
     * Decodes one depth of one micro block.
     */
    private void tile(double[] values, int base, int i0, int i1, int j0, int j1, int depthIndex)
            throws DataFormatException {
        var hd = header;
        var columns = hd.columns;
        var depth = hd.depth;
        need(1);
        var flag = src[pos++] & 0xFF;
        var diff = hd.version >= 5 && (flag & 4) != 0;
        var pattern = hd.version >= 5 ? 14 : 15;
        if (((flag >> 2) & pattern) != ((j0 >> 3) & pattern)) {
            throw new DataFormatException("Lerc2 block integrity check failed");
        }
        if (diff && depthIndex == 0) {
            throw new DataFormatException("Lerc2 difference coding of the first depth");
        }
        var bits67 = flag >> 6;
        flag &= 3;
        var type = hd.dataType;
        if (flag == 2) {
            for (var i = i0; i < i1; i++) {
                var k = i * columns + j0;
                var m = base + k * depth + depthIndex;
                for (var j = j0; j < j1; j++, k++, m += depth) {
                    if (isValid(k)) {
                        values[m] = diff ? values[m - 1] : 0;
                    }
                }
            }
            return;
        }
        if (flag == 0) {
            if (diff) {
                throw new DataFormatException("Lerc2 raw block with difference coding");
            }
            for (var i = i0; i < i1; i++) {
                var k = i * columns + j0;
                var m = base + k * depth + depthIndex;
                for (var j = j0; j < j1; j++, k++, m += depth) {
                    if (isValid(k)) {
                        need(typeSize(type));
                        values[m] = read(type);
                    }
                }
            }
            return;
        }
        var used = typeUsed(diff && type < FLOAT ? INT : type, bits67);
        if (used < 0) {
            throw new DataFormatException("invalid Lerc2 block data type");
        }
        need(typeSize(used));
        var offset = read(used);
        var max = hd.version >= 4 && depth > 1 ? zMax[depthIndex] : hd.zMax;
        if (flag == 3) {
            for (var i = i0; i < i1; i++) {
                var k = i * columns + j0;
                var m = base + k * depth + depthIndex;
                for (var j = j0; j < j1; j++, k++, m += depth) {
                    if (isValid(k)) {
                        values[m] = diff ? cast(type, Math.min(offset + values[m - 1], max)) : cast(type, offset);
                    }
                }
            }
            return;
        }
        var maxCount = (i1 - i0) * (j1 - j0);
        stuffer.src = src;
        stuffer.pos = pos;
        stuffer.end = end;
        var count = stuffer.decode(maxCount, hd.version);
        pos = stuffer.pos;
        var buffer = stuffer.values();
        var scale = 2 * hd.maxZError;
        var at = 0;
        var all = count == maxCount;
        for (var i = i0; i < i1; i++) {
            var k = i * columns + j0;
            var m = base + k * depth + depthIndex;
            for (var j = j0; j < j1; j++, k++, m += depth) {
                if (all || isValid(k)) {
                    if (at >= count) {
                        throw new DataFormatException("too few Lerc2 block values");
                    }
                    var z = offset + (buffer[at++] & 0xFFFFFFFFL) * scale + (diff ? values[m - 1] : 0);
                    values[m] = cast(type, Math.min(z, max));
                }
            }
        }
    }

    /**
     * Decodes a Huffman-coded 8-bit band.
     *
     * @param values the output
     * @param base the first value of the band
     * @param delta whether the values are differences from their neighbors
     * @throws DataFormatException if the codes are invalid
     */
    private void huffman(double[] values, int base, boolean delta) throws DataFormatException {
        var hd = header;
        var codes = new LercHuffman();
        stuffer.src = src;
        stuffer.pos = pos;
        stuffer.end = end;
        codes.readTable(stuffer, hd.version);
        pos = stuffer.pos;
        codes.build();
        var offset = hd.dataType == CHAR ? 128 : 0;
        var height = hd.rows;
        var width = hd.columns;
        var depth = hd.depth;
        var signed = hd.dataType == CHAR;
        var reader = codes.reader(src, pos, end, false);
        var all = hd.validPixels == width * height;
        if (delta) {
            for (var d = 0; d < depth; d++) {
                var previous = 0;
                for (int k = 0, m = base + d, i = 0; i < height; i++) {
                    for (var j = 0; j < width; j++, k++, m += depth) {
                        if (!all && !isValid(k)) {
                            continue;
                        }
                        var value = wrap(reader.next() - offset, signed);
                        if (j > 0 && (all || isValid(k - 1))) {
                            value = wrap(value + previous, signed);
                        } else if (i > 0 && (all || isValid(k - width))) {
                            value = wrap(value + (int) values[m - width * depth], signed);
                        } else {
                            value = wrap(value + previous, signed);
                        }
                        values[m] = value;
                        previous = value;
                    }
                }
            }
        } else {
            for (int k = 0, m0 = base, i = 0; i < height; i++) {
                for (var j = 0; j < width; j++, k++, m0 += depth) {
                    if (all || isValid(k)) {
                        for (var m = 0; m < depth; m++) {
                            values[m0 + m] = wrap(reader.next() - offset, signed);
                        }
                    }
                }
            }
        }
        var words = (reader.bitPosition() > 0 ? 1 : 0) + 1;
        var length = reader.position() - pos + words * 4;
        need(length);
        pos += length;
    }

    private static int wrap(int value, boolean signed) {
        return signed ? (byte) value : value & 0xFF;
    }

    /**
     * Replaces the temporary no-data value of a multi-depth band by the original one in valid pixels.
     */
    private void remapNoData(double[] values, int base, byte[] valid, int validBase, Info info) {
        var type = header.dataType;
        var temporary = cast(type, header.noData);
        var original = cast(type, header.noDataOriginal);
        if (temporary == original) {
            return;
        }
        var pixels = info.rows() * info.columns();
        var depth = info.depth();
        for (var k = 0; k < pixels; k++) {
            if (valid[validBase + k] != 0) {
                for (var m = 0; m < depth; m++) {
                    var at = base + k * depth + m;
                    if (values[at] == temporary) {
                        values[at] = original;
                    }
                }
            }
        }
    }

    /**
     * Converts a value to a data type as a C cast does for values in range.
     *
     * @param type the data type
     * @param z the value
     * @return the converted value
     */
    static double cast(int type, double z) {
        return switch (type) {
            case CHAR -> (byte) (long) z;
            case BYTE -> (long) z & 0xFF;
            case SHORT -> (short) (long) z;
            case USHORT -> (long) z & 0xFFFF;
            case INT -> (int) (long) z;
            case UINT -> (long) z & 0xFFFFFFFFL;
            case FLOAT -> (float) z;
            default -> z;
        };
    }

    /**
     * Returns the type a block stores its offset in, from the type of the band and the reduction code.
     *
     * @param type the data type
     * @param code the reduction code
     * @return the type, or -1 when invalid
     */
    private static int typeUsed(int type, int code) {
        var used = switch (type) {
            case SHORT, INT -> type - code;
            case USHORT, UINT -> type - 2 * code;
            case FLOAT -> code == 0 ? type : code == 1 ? SHORT : BYTE;
            case DOUBLE -> code == 0 ? type : type - 2 * code + 1;
            default -> type;
        };
        return used >= CHAR && used <= DOUBLE ? used : -1;
    }

    static int typeSize(int type) {
        return switch (type) {
            case CHAR, BYTE -> 1;
            case SHORT, USHORT -> 2;
            case INT, UINT, FLOAT -> 4;
            default -> 8;
        };
    }

    /**
     * Reads one little-endian value of a data type.
     *
     * @param type the data type
     * @return the value
     */
    private double read(int type) {
        var at = pos;
        pos += typeSize(type);
        return switch (type) {
            case CHAR -> src[at];
            case BYTE -> src[at] & 0xFF;
            case SHORT -> (short) ((src[at] & 0xFF) | src[at + 1] << 8);
            case USHORT -> (src[at] & 0xFF) | (src[at + 1] & 0xFF) << 8;
            case INT -> intLE(src, at);
            case UINT -> intLE(src, at) & 0xFFFFFFFFL;
            case FLOAT -> Float.intBitsToFloat(intLE(src, at));
            default -> Double.longBitsToDouble((intLE(src, at) & 0xFFFFFFFFL) | (long) intLE(src, at + 4) << 32);
        };
    }

    private void need(long count) throws DataFormatException {
        if (count < 0 || end - pos < count) {
            throw new DataFormatException("truncated Lerc2 band");
        }
    }

    /**
     * Returns the size of a band header of a version.
     *
     * @param version the Lerc2 version of the blob
     * @return the size in bytes
     */
    private static int headerSize(int version) {
        var ints = 6 + (version >= 4 ? 1 : 0) + (version >= 6 ? 1 : 0);
        var bytes = version >= 6 ? 4 : 0;
        var doubles = 3 + (version >= 6 ? 2 : 0);
        return KEY.length + 4 + (version >= 3 ? 4 : 0) + 4 * ints + bytes + 8 * doubles;
    }

    /**
     * Reads and validates a band header.
     *
     * @param data the input
     * @param at the band position
     * @param limit the end of the input
     * @param hd receives the header
     * @return whether the header is valid
     */
    private static boolean readHeader(byte[] data, int at, int limit, Header hd) {
        if (limit - at < KEY.length + 4 || !Arrays.equals(data, at, at + KEY.length, KEY, 0, KEY.length)) {
            return false;
        }
        var version = intLE(data, at + KEY.length);
        if (version < 0 || version > CURRENT_VERSION || limit - at < headerSize(version)) {
            return false;
        }
        hd.version = version;
        var p = at + KEY.length + 4;
        if (version >= 3) {
            hd.checksum = intLE(data, p);
            p += 4;
        }
        hd.rows = intLE(data, p);
        hd.columns = intLE(data, p + 4);
        p += 8;
        if (version >= 4) {
            hd.depth = intLE(data, p);
            p += 4;
        } else {
            hd.depth = 1;
        }
        hd.validPixels = intLE(data, p);
        hd.microBlockSize = intLE(data, p + 4);
        hd.blobSize = intLE(data, p + 8);
        hd.dataType = intLE(data, p + 12);
        p += 16;
        if (version >= 6) {
            hd.blobsMore = intLE(data, p);
            p += 4;
            hd.passNoData = data[p] & 0xFF;
            p += 4;
        } else {
            hd.blobsMore = 0;
            hd.passNoData = 0;
        }
        hd.maxZError = doubleLE(data, p);
        hd.zMin = doubleLE(data, p + 8);
        hd.zMax = doubleLE(data, p + 16);
        p += 24;
        if (version >= 6) {
            hd.noData = doubleLE(data, p);
            hd.noDataOriginal = doubleLE(data, p + 8);
        } else {
            hd.noData = 0;
            hd.noDataOriginal = 0;
        }
        if (hd.rows <= 0 || hd.columns <= 0 || hd.depth <= 0 || hd.validPixels < 0 || hd.microBlockSize <= 0
                || hd.blobSize <= 0 || hd.dataType < CHAR || hd.dataType > DOUBLE) {
            return false;
        }
        var pixels = (long) hd.rows * hd.columns;
        var bytesPerPixel = (long) typeSize(hd.dataType) * hd.depth;
        return pixels <= Integer.MAX_VALUE && hd.validPixels <= pixels && hd.microBlockSize <= 32
                && bytesPerPixel <= Integer.MAX_VALUE && bytesPerPixel * pixels <= Integer.MAX_VALUE;
    }

    /**
     * Computes the Fletcher-32 checksum of bytes paired as big-endian 16-bit words, as Lerc2 does.
     *
     * @param data the bytes
     * @param offset the first byte
     * @param length the number of bytes
     * @return the checksum
     */
    static int fletcher32(byte[] data, int offset, int length) {
        var sum1 = 0xFFFFL;
        var sum2 = 0xFFFFL;
        var words = length / 2;
        var p = offset;
        while (words > 0) {
            var block = Math.min(words, 359);
            words -= block;
            do {
                sum1 += (data[p++] & 0xFF) << 8;
                sum1 += data[p++] & 0xFF;
                sum2 += sum1;
            } while (--block > 0);
            sum1 = (sum1 & 0xFFFF) + (sum1 >>> 16);
            sum2 = (sum2 & 0xFFFF) + (sum2 >>> 16);
        }
        if ((length & 1) != 0) {
            sum1 += (data[p] & 0xFF) << 8;
            sum2 += sum1;
        }
        sum1 = (sum1 & 0xFFFF) + (sum1 >>> 16);
        sum2 = (sum2 & 0xFFFF) + (sum2 >>> 16);
        return (int) (sum2 << 16 | sum1);
    }

    static int intLE(byte[] data, int at) {
        return (data[at] & 0xFF) | (data[at + 1] & 0xFF) << 8 | (data[at + 2] & 0xFF) << 16 | (data[at + 3] & 0xFF) << 24;
    }

    private static short shortLE(byte[] data, int at) {
        return (short) ((data[at] & 0xFF) | (data[at + 1] & 0xFF) << 8);
    }

    private static double doubleLE(byte[] data, int at) {
        return Double.longBitsToDouble((intLE(data, at) & 0xFFFFFFFFL) | (long) intLE(data, at + 4) << 32);
    }
}
