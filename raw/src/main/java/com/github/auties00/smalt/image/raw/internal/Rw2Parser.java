package com.github.auties00.smalt.image.raw.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.raw.CfaColor;
import com.github.auties00.smalt.image.raw.CfaPattern;
import com.github.auties00.smalt.internal.io.ByteArrays;
import com.github.auties00.smalt.internal.limits.LimitChecks;

import java.util.ArrayList;
import java.util.Arrays;

/**
 * Parses a Panasonic RW2 (or Leica RWL) file: a TIFF structure with the signature {@code IIU}
 * whose first directory holds Panasonic's raw tags and an embedded full-size JPEG
 * ({@code JpgFromRaw}, tag {@code 0x002E}) that carries the EXIF metadata.
 *
 * <p>The raw tags give the stored width ({@code 0x0002}) and height ({@code 0x0003}), the
 * sensor's image area as top, left, bottom and right ({@code 0x0004} to {@code 0x0007}), the
 * color filter array ({@code 0x0009}: 1 for {@code RGGB}, 2 for {@code GRBG}, 3 for {@code GBRG},
 * 4 for {@code BGGR}), the bits per sample ({@code 0x000A}), the white level ({@code 0x000E}), the
 * red, green and blue black levels ({@code 0x001C} to {@code 0x001E}), the red, green and blue
 * white balance levels ({@code 0x0024} to {@code 0x0026}), the raw format version
 * ({@code 0x002D}) and the offset of the sensor data ({@code 0x0118}). {@link PanasonicDecoder}
 * decodes versions 4, 5, 6 and 8.
 *
 * @implNote This implementation follows the tag names of the ExifTool tag documentation; the
 * levels, the color filter codes and the image area were checked against the values
 * Adobe DNG Converter stores for CC0 sample files, and the version 8 tile tags were
 * worked out from them.
 */
final class Rw2Parser {
    /**
     * Prevents instantiation.
     */
    private Rw2Parser() {
        throw new AssertionError();
    }

    /**
     * Parses the file.
     *
     * @param reader the TIFF structure
     * @param file the description to fill
     */
    static void parse(TiffReader reader, RawFile file) {
        var tree = new TiffTree(reader);
        tree.describe(file);
        var first = tree.first;
        if (file.iso == 0) {
            file.iso = Math.max(0, first.longValue(0x17, 0));
        }
        var jpeg = first.valuePosition(0x2E);
        if (jpeg > 0) {
            var length = first.valueCount(0x2E);
            TiffTree.addPreview(file, reader, jpeg, length);
            var head = new byte[(int) Math.min(length, 1 << 16)];
            var read = reader.readAvailable(jpeg, head, 0, head.length);
            for (var position = 2; position + 10 < read; ) {
                if ((head[position] & 0xFF) != 0xFF) {
                    break;
                }
                var marker = head[position + 1] & 0xFF;
                var size = ByteArrays.u16be(head, position + 2);
                if (marker == 0xE1 && head[position + 4] == 'E' && head[position + 5] == 'x') {
                    try {
                        var exifTree = new TiffTree(TiffReader.open(reader.source(), jpeg + position + 10, reader.limits()));
                        if (exifTree.exif != null) {
                            TiffTree.shot(exifTree.exif, file);
                            file.exif = ExifBlock.build(exifTree.first, exifTree.exif, null);
                        }
                    } catch (MediaException.Malformed exception) {
                        // a damaged EXIF segment leaves the metadata out
                    }
                    break;
                }
                if (marker == 0xDA || size < 2) {
                    break;
                }
                position += 2 + size;
            }
        }
        sensor(reader, first, file);
    }

    /**
     * Describes the sensor data.
     *
     * @param reader the TIFF structure
     * @param first the first directory, holding the raw tags
     * @param file the description to fill
     */
    private static void sensor(TiffReader reader, Ifd first, RawFile file) {
        var version = first.intValue(0x2D, 0);
        if (version == 8) {
            tiled(reader, first, file);
            return;
        }
        if (!PanasonicDecoder.supports(version)) {
            file.unsupported = "Panasonic RW2 raw format version " + version + " is not supported";
            return;
        }
        var sensor = new Sensor();
        sensor.width = first.intValue(0x02, 0);
        sensor.height = first.intValue(0x03, 0);
        sensor.bitsPerSample = first.intValue(0x0A, 14);
        var group = PanasonicDecoder.group(version);
        if (sensor.width <= 0 || sensor.height <= 0 || sensor.width % group != 0 || sensor.bitsPerSample < 8
                || sensor.bitsPerSample > 16) {
            throw new MediaException.Malformed("invalid RW2 sensor of " + sensor.width + "x" + sensor.height);
        }
        LimitChecks.checkPicture(file.limits, sensor.width, sensor.height);
        sensor.cfa = switch (first.intValue(0x09, 1)) {
            case 2 -> CfaPattern.bayer("GRBG");
            case 3 -> CfaPattern.bayer("GBRG");
            case 4 -> CfaPattern.bayer("BGGR");
            default -> CfaPattern.bayer("RGGB");
        };
        var offset = reader.base() + first.longValue(0x118, 0);
        var size = reader.size();
        if (offset <= 0 || (size >= 0 && offset >= size)) {
            throw new MediaException.Malformed("RW2 file without sensor data");
        }
        var cfa = sensor.cfa;
        sensor.compression = switch (version) {
            case 4 -> "Panasonic 12-bit";
            case 5 -> "uncompressed";
            default -> "Panasonic 14-bit";
        };
        sensor.reader = (plane, limits) -> {
            var needed = (long) plane.width / group * plane.height * 16 + PanasonicDecoder.CHUNK;
            var length = size >= 0 ? Math.min(needed, size - offset) : needed;
            LimitChecks.checkAllocation(limits, length);
            var data = new byte[(int) length];
            var read = reader.readAvailable(offset, data, 0, data.length);
            PanasonicDecoder.decode(read == data.length ? data : Arrays.copyOf(data, read), version, plane, cfa);
        };
        var black = new double[4];
        for (var row = 0; row < 2; row++) {
            for (var column = 0; column < 2; column++) {
                var color = cfa.color(row, column);
                var tag = color == CfaColor.RED ? 0x1C : color == CfaColor.BLUE ? 0x1E : 0x1D;
                black[row * 2 + column] = first.longValue(tag, 0);
            }
        }
        sensor.blackRows = 2;
        sensor.blackColumns = 2;
        sensor.black = black;
        var maximum = (1 << sensor.bitsPerSample) - 1;
        var white = (int) Math.min(first.longValue(0x0E, maximum), maximum);
        sensor.white = new double[]{version == 6 ? white - PanasonicDecoder.PEDESTAL : white};
        describe(first, sensor, file);
        file.sensor = sensor;
    }

    /**
     * Fills the default crop and as-shot white balance shared by every version.
     *
     * @param first the first directory
     * @param sensor the sensor description to fill
     * @param file the description to fill
     */
    private static void describe(Ifd first, Sensor sensor, RawFile file) {
        var top = first.intValue(0x04, 0);
        var left = first.intValue(0x05, 0);
        var bottom = first.intValue(0x06, 0);
        var right = first.intValue(0x07, 0);
        if (left >= 0 && top >= 0 && right > left && bottom > top && right <= sensor.width && bottom <= sensor.height) {
            sensor.defaultCrop = new double[]{left, top, right - left, bottom - top};
        }
        var red = first.longValue(0x24, 0);
        var green = first.longValue(0x25, 0);
        var blue = first.longValue(0x26, 0);
        if (red > 0 && green > 0 && blue > 0) {
            file.color.asShotNeutral = new double[]{(double) green / red, 1, (double) green / blue};
        }
    }

    /**
     * Describes version 8 data: tiles whose offsets ({@code 0x0044}), left columns
     * ({@code 0x0045}), coded sizes in bits ({@code 0x0046}), widths ({@code 0x0047}) and heights
     * ({@code 0x0048}) the raw tags list, each as a 16-bit count then the values (32-bit for the
     * first three, 16-bit for the others), with the Huffman table of tag {@code 0x0040}: a 16-bit
     * count, then a 16-bit length and a 16-bit code per value. Tags {@code 0x0039} and
     * {@code 0x003A} (six 32-bit values, the first 16.16 fixed-point gains of 1, the second zero)
     * and {@code 0x0041} (one 16-bit value per Huffman value, zero) describe a tone curve and
     * per-value shifts, which every sample file leaves at the identity.
     *
     * @param reader the TIFF structure
     * @param first the first directory
     * @param file the description to fill
     */
    private static void tiled(TiffReader reader, Ifd first, RawFile file) {
        var little = reader.little();
        var offsets = first.bytes(0x44);
        var lefts = first.bytes(0x45);
        var widths = first.bytes(0x47);
        var heights = first.bytes(0x48);
        var table = first.bytes(0x40);
        if (offsets == null || lefts == null || widths == null || heights == null || table == null || table.length < 2
                || offsets.length < 2) {
            file.unsupported = "the Panasonic RW2 version 8 file lacks its tile tags";
            return;
        }
        if (!identity(first.bytes(0x39), 65536, 4) || !identity(first.bytes(0x3A), 0, 4) || !identity(first.bytes(0x41), 0, 2)) {
            // TODO: apply the tone curve and per-value shifts of tags 0x0039, 0x003A and 0x0041 once a sample uses them
            file.unsupported = "Panasonic RW2 version 8 files with a tone curve or shifts are not supported";
            return;
        }
        var count = ByteArrays.u16(offsets, 0, little);
        if (count < 1 || count > 64 || offsets.length < 2 + 4 * count || lefts.length < 2 + 4 * count
                || widths.length < 2 + 2 * count || heights.length < 2 + 2 * count) {
            throw new MediaException.Malformed("invalid RW2 tile tags");
        }
        var tiles = new ArrayList<PanasonicDecoder.Tile>();
        var width = 0;
        var height = 0;
        for (var index = 0; index < count; index++) {
            var tile = new PanasonicDecoder.Tile(reader.base() + ByteArrays.u32(offsets, 2 + 4 * index, little),
                    (int) ByteArrays.u32(lefts, 2 + 4 * index, little), ByteArrays.u16(widths, 2 + 2 * index, little),
                    ByteArrays.u16(heights, 2 + 2 * index, little));
            if (tile.width() <= 0 || tile.height() <= 0 || tile.left() < 0 || tile.left() > 1 << 16) {
                throw new MediaException.Malformed("invalid RW2 tile");
            }
            tiles.add(tile);
            width = Math.max(width, tile.left() + tile.width());
            height = Math.max(height, tile.height());
        }
        var values = ByteArrays.u16(table, 0, little);
        if (values < 1 || values > 17 || table.length < 2 + 4 * values) {
            throw new MediaException.Malformed("invalid RW2 Huffman table");
        }
        var codes = new int[values];
        var lengths = new int[values];
        for (var index = 0; index < values; index++) {
            lengths[index] = ByteArrays.u16(table, 2 + 4 * index, little);
            if (lengths[index] < 1 || lengths[index] > 16) {
                throw new MediaException.Malformed("invalid RW2 Huffman code length " + lengths[index]);
            }
            codes[index] = ByteArrays.u16(table, 4 + 4 * index, little);
        }
        var lookup = PanasonicDecoder.prefixTable(codes, lengths);
        var sensor = new Sensor();
        sensor.width = width;
        sensor.height = height;
        sensor.bitsPerSample = first.intValue(0x0A, 16);
        if (sensor.bitsPerSample < 8 || sensor.bitsPerSample > 16) {
            throw new MediaException.Malformed("invalid RW2 bit depth " + sensor.bitsPerSample);
        }
        LimitChecks.checkPicture(file.limits, width, height);
        sensor.cfa = switch (first.intValue(0x09, 1)) {
            case 2 -> CfaPattern.bayer("GRBG");
            case 3 -> CfaPattern.bayer("GBRG");
            case 4 -> CfaPattern.bayer("BGGR");
            default -> CfaPattern.bayer("RGGB");
        };
        sensor.compression = "Panasonic Huffman";
        var source = reader.source();
        sensor.reader = (plane, limits) -> PanasonicDecoder.version8(source, tiles, lookup, plane, limits);
        var black = new double[4];
        for (var row = 0; row < 2; row++) {
            for (var column = 0; column < 2; column++) {
                var color = sensor.cfa.color(row, column);
                var tag = color == CfaColor.RED ? 0x1C : color == CfaColor.BLUE ? 0x1E : 0x1D;
                black[row * 2 + column] = first.longValue(tag, 0);
            }
        }
        sensor.blackRows = 2;
        sensor.blackColumns = 2;
        sensor.black = black;
        var maximum = (1 << sensor.bitsPerSample) - 1;
        sensor.white = new double[]{Math.min(first.longValue(0x0E, maximum), maximum)};
        describe(first, sensor, file);
        file.sensor = sensor;
    }

    /**
     * Returns whether a parameter tag of version 8 holds the identity: a 16-bit count, then that
     * many little-endian values all equal to a constant.
     *
     * @param data the tag's bytes, or {@code null} for an absent tag
     * @param constant the value of the identity
     * @param size the bytes of each value, 2 or 4
     * @return whether the tag is absent or holds the identity
     */
    private static boolean identity(byte[] data, long constant, int size) {
        if (data == null) {
            return true;
        }
        if (data.length < 2) {
            return false;
        }
        var count = ByteArrays.u16le(data, 0);
        if (2 + (long) size * count > data.length) {
            return false;
        }
        for (var index = 0; index < count; index++) {
            var value = size == 4 ? ByteArrays.u32(data, 2 + 4 * index, true) : ByteArrays.u16(data, 2 + 2 * index, true);
            if (value != constant) {
                return false;
            }
        }
        return true;
    }
}
