package com.github.auties00.smalt.image.raw.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.raw.CfaColor;
import com.github.auties00.smalt.image.raw.CfaPattern;
import com.github.auties00.smalt.internal.io.ByteArrays;
import com.github.auties00.smalt.internal.limits.LimitChecks;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

/**
 * Parses a Fujifilm RAF file: a fixed header pointing to an embedded JPEG preview (which carries
 * the EXIF metadata), a list of tagged records describing the sensor, and a small TIFF structure
 * holding the sensor data.
 *
 * <p>The header holds, as big-endian 32-bit integers at offset 84, the offset and length of the
 * JPEG preview, of the record list and of the sensor block, and at offset 108 the compression (0
 * for uncompressed). The records give the cropped image's origin (tag {@code 0x0110}) and size
 * ({@code 0x0111}) and, for X-Trans sensors, the 6x6 color filter array ({@code 0x0131}, codes
 * 0 for red, 1 for green and 2 for blue, stored from the last photosite to the first). The sensor
 * block's directory points to a directory of private tags: width {@code 0xF001}, height
 * {@code 0xF002}, bits per sample {@code 0xF003}, data offset {@code 0xF007} and length
 * {@code 0xF008} relative to the block, black levels {@code 0xF00A} (one per photosite of the
 * array) and as-shot white balance {@code 0xF00D} (green, red, blue). Uncompressed data is stored
 * as little-endian 16-bit words, or as 12-bit values packed least significant bit first.
 *
 * @implNote This implementation follows the record names of the ExifTool tag documentation and
 * the layout found by examining CC0 sample files; the color filter array of Bayer models,
 * which the files do not state, is taken as {@code RGGB} at the sensor origin. Fujifilm's
 * compressed RAF (lossless and lossy) and the SuperCCD layouts of older models are not
 * supported.
 */
final class RafParser {
    /**
     * Prevents instantiation.
     */
    private RafParser() {
        throw new AssertionError();
    }

    /**
     * Parses the file.
     *
     * @param file the description to fill
     */
    static void parse(RawFile file) {
        var source = file.source;
        var reader = new TiffReader(source, 0, false, file.limits);
        var header = reader.bytes(0, 112);
        var model = new String(header, 28, 32, StandardCharsets.ISO_8859_1);
        var end = model.indexOf('\0');
        file.make = "FUJIFILM";
        file.model = (end >= 0 ? model.substring(0, end) : model).strip();
        var jpegOffset = ByteArrays.u32(header, 84, false);
        var jpegLength = ByteArrays.u32(header, 88, false);
        var recordsOffset = ByteArrays.u32(header, 92, false);
        var recordsLength = ByteArrays.u32(header, 96, false);
        var sensorOffset = ByteArrays.u32(header, 100, false);
        var compression = ByteArrays.u32(header, 108, false);
        exif(file, reader, jpegOffset, jpegLength);
        TiffTree.addPreview(file, reader, jpegOffset, jpegLength);
        int[] crop = null;
        int[] xtrans = null;
        if (recordsLength >= 4 && recordsLength < 1 << 20) {
            var records = reader.bytes(recordsOffset, recordsLength);
            var count = ByteArrays.u32(records, 0, false);
            var position = 4;
            for (var index = 0; index < count && position + 4 <= records.length; index++) {
                var tag = ByteArrays.u16(records, position, false);
                var size = ByteArrays.u16(records, position + 2, false);
                var value = position + 4;
                if (value + size > records.length) {
                    break;
                }
                switch (tag) {
                    case 0x110 -> {
                        if (size >= 4) {
                            crop = crop == null ? new int[4] : crop;
                            crop[0] = ByteArrays.u16(records, value, false);
                            crop[1] = ByteArrays.u16(records, value + 2, false);
                        }
                    }
                    case 0x111 -> {
                        if (size >= 4) {
                            crop = crop == null ? new int[4] : crop;
                            crop[2] = ByteArrays.u16(records, value, false);
                            crop[3] = ByteArrays.u16(records, value + 2, false);
                        }
                    }
                    case 0x131 -> {
                        if (size == 36) {
                            xtrans = new int[36];
                            for (var k = 0; k < 36; k++) {
                                xtrans[k] = records[value + 35 - k] & 0xFF;
                            }
                        }
                    }
                    default -> {
                    }
                }
                position = value + size;
            }
        }
        if (compression != 0) {
            // TODO: decode Fujifilm compressed RAF files (lossless and lossy); not yet derived from Adobe DNG
            // Converter values
            file.unsupported = "compressed RAF files are not supported";
            return;
        }
        sensor(file, reader, sensorOffset, crop, xtrans);
    }

    /**
     * Reads the camera, shot and EXIF block from the preview's EXIF segment.
     *
     * @param file the description to fill
     * @param reader any reader over the file
     * @param offset the offset of the preview
     * @param length the length of the preview
     */
    private static void exif(RawFile file, TiffReader reader, long offset, long length) {
        var tree = TiffTree.ofJpegExif(reader, offset, length);
        if (tree == null) {
            return;
        }
        var make = file.make;
        var model = file.model;
        tree.describe(file);
        file.previews.clear();
        file.make = make;
        file.model = model;
    }

    /**
     * Reads the sensor block.
     *
     * @param file the description to fill
     * @param reader any reader over the file
     * @param offset the offset of the sensor block
     * @param crop the cropped area's top, left, height and width, or {@code null}
     * @param xtrans the X-Trans pattern, or {@code null} for a Bayer sensor
     */
    private static void sensor(RawFile file, TiffReader reader, long offset, int[] crop, int[] xtrans) {
        Ifd block;
        try {
            var tiff = TiffReader.open(reader.source(), offset, reader.limits());
            var first = tiff.directory(tiff.firstDirectory());
            block = first.child(0xF000);
        } catch (MediaException.Malformed exception) {
            block = null;
        }
        if (block == null) {
            file.unsupported = "RAF files without a sensor directory (older models) are not supported";
            return;
        }
        var sensor = new Sensor();
        sensor.width = block.intValue(0xF001, 0);
        sensor.height = block.intValue(0xF002, 0);
        sensor.bitsPerSample = block.intValue(0xF003, 0);
        var dataOffset = offset + block.longValue(0xF007, 0);
        var dataLength = block.longValue(0xF008, 0);
        if (sensor.width <= 0 || sensor.height <= 0 || sensor.bitsPerSample < 8 || sensor.bitsPerSample > 16) {
            throw new MediaException.Malformed("invalid RAF sensor of " + sensor.width + "x" + sensor.height + "x"
                    + sensor.bitsPerSample);
        }
        var pixels = (long) sensor.width * sensor.height;
        var bits = sensor.bitsPerSample;
        var source = reader.source();
        var width = sensor.width;
        if (dataLength >= pixels * 2) {
            sensor.reader = (plane, limits) -> {
                var row = new byte[width * 2];
                for (var y = 0; y < plane.height; y++) {
                    var read = source.read(dataOffset + (long) y * row.length, row, 0, row.length);
                    if (read <= 0) {
                        return;
                    }
                    Unpack.words(row, 0, read, true, bits, plane.integers, y * width, width);
                }
            };
        } else if (bits == 12 && dataLength >= pixels * 3 / 2) {
            sensor.reader = (plane, limits) -> {
                var row = new byte[width * 3 / 2];
                for (var y = 0; y < plane.height; y++) {
                    var read = source.read(dataOffset + (long) y * row.length, row, 0, row.length);
                    if (read <= 0) {
                        return;
                    }
                    Unpack.lsbFirst(row, 0, read, 12, plane.integers, y * width, width);
                }
            };
        } else {
            file.unsupported = "this RAF sensor data layout is not supported";
            return;
        }
        LimitChecks.checkPicture(file.limits, sensor.width, sensor.height);
        sensor.compression = "uncompressed";
        if (xtrans != null) {
            var colors = new ArrayList<CfaColor>();
            for (var code : xtrans) {
                if (code > 2) {
                    throw new MediaException.Malformed("invalid X-Trans color " + code);
                }
                colors.add(CfaColor.of(code));
            }
            sensor.cfa = CfaPattern.of(6, 6, colors);
        } else {
            sensor.cfa = CfaPattern.bayer("RGGB");
        }
        var black = block.doubles(0xF00A);
        var period = sensor.cfa.width();
        if (black != null && black.length == period * period) {
            sensor.blackRows = period;
            sensor.blackColumns = period;
            sensor.black = black;
        } else if (black != null && black.length > 0) {
            sensor.black = new double[]{black[0]};
        }
        sensor.white = new double[]{(1 << bits) - 1};
        if (crop != null && crop[2] > 0 && crop[3] > 0 && crop[0] + crop[2] <= sensor.height
                && crop[1] + crop[3] <= sensor.width) {
            sensor.defaultCrop = new double[]{crop[1], crop[0], crop[3], crop[2]};
        }
        var balance = block.longs(0xF00D);
        if (balance != null && balance.length >= 3 && balance[0] > 0 && balance[1] > 0 && balance[2] > 0) {
            file.color.asShotNeutral = new double[]{(double) balance[0] / balance[1], 1, (double) balance[0] / balance[2]};
        }
        file.sensor = sensor;
    }
}
