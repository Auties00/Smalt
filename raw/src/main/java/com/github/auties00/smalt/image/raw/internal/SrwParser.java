package com.github.auties00.smalt.image.raw.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.raw.CfaColor;
import com.github.auties00.smalt.image.raw.CfaPattern;

import java.util.Map;

/**
 * Parses a Samsung SRW file: a TIFF/EP structure whose color filter array directory holds the
 * sensor data, and whose maker note, a TIFF directory at the start of the EXIF {@code MakerNote}
 * value with offsets relative to that start, holds the levels, the white balance and the image
 * area.
 *
 * <p>The sensor data is uncompressed in TIFF compression 1, 32769 (one sample in each
 * little-endian 16-bit word, whatever the file's byte order, the word kept whole) and 32770 when
 * the strip holds exactly the bits of every photosite (12-bit samples back to back, least
 * significant bit first when the maker note's {@code RawDataByteOrder}, tag {@code 0x0040}, is 0,
 * most significant bit first when it is 1 or, as in the oldest models, absent and the file is big
 * endian).
 *
 * <p>The maker note values of the black level ({@code WB_RGGBLevelsBlack}, tag {@code 0xA028}) and
 * of the as-shot white balance ({@code WB_RGGBLevelsUncorrected}, tag {@code 0xA021}) are stored
 * plus the first four values of {@code EncryptionKey} (tag {@code 0xA020}), in red, green, green,
 * blue order; the white balance levels are the camera's multipliers on a scale of 4096 for green.
 * The image is the valid area of {@code SensorAreas} (tag {@code 0xA010}: the full area as left,
 * top, right and bottom, then the valid one), or the EXIF image size centered on it when that is
 * smaller (the 16:9 shots of the compacts). The white level is that of the camera model
 * (3750 for most 12-bit NX models, 4000 for the NX mini and the 12-bit NX500 files, 16100 for the
 * 14-bit NX1 and NX500 files, 15600 for the EX1 and 3700 for the WB2000), or the largest value of
 * the bit depth.
 *
 * @implNote This implementation follows the tag names of the ExifTool tag documentation; the
 * layouts, the subtraction of the key, the order of the levels and the white levels were found by
 * comparing CC0 sample files with the values Adobe DNG Converter, run as a black box, stores for
 * them. Samsung's compressions of the later NX models (32770 at fewer bits than the photosites,
 * 32772 and 32773) are not decoded.
 */
final class SrwParser {
    /**
     * The TIFF compression code of 16-bit words.
     */
    private static final int WORDS = 32769;

    /**
     * The TIFF compression code of packed samples, or of the first compressed layout.
     */
    private static final int PACKED = 32770;

    /**
     * The white levels of the camera models whose photosites saturate below the bit depth's
     * largest value, on the scale of their 12-bit files.
     */
    private static final Map<String, Integer> WHITE_12 = Map.ofEntries(
            Map.entry("NX5", 3750), Map.entry("NX10", 3750), Map.entry("NX11", 3750), Map.entry("NX20", 3750),
            Map.entry("NX200", 3750), Map.entry("NX210", 3750), Map.entry("NX1000", 3750), Map.entry("NX1100", 3750),
            Map.entry("NX mini", 4000), Map.entry("NX500", 4000));

    /**
     * The white levels of the camera models whose photosites saturate below the bit depth's
     * largest value, on the scale of their 14-bit files.
     */
    private static final Map<String, Integer> WHITE_14 = Map.of("NX1", 16100, "NX500", 16100, "EX1", 15600,
            "WB2000", 3700);

    /**
     * Prevents instantiation.
     */
    private SrwParser() {
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
        var raw = TiffEpParser.rawDirectory(tree);
        if (raw == null) {
            file.unsupported = "the SRW file holds no raw image";
            return;
        }
        var note = makerNote(tree);
        if (note != null) {
            balance(note, file);
        }
        var width = raw.intValue(TiffSamples.IMAGE_WIDTH, 0);
        var height = raw.intValue(TiffSamples.IMAGE_LENGTH, 0);
        var bits = raw.intValue(TiffSamples.BITS_PER_SAMPLE, 12);
        var compression = raw.intValue(TiffSamples.COMPRESSION, 1);
        var sensor = new Sensor();
        if (compression == WORDS || compression == PACKED) {
            if (width <= 0 || height <= 0 || bits < 8 || bits > 16) {
                throw new MediaException.Malformed("invalid SRW raw image of " + width + "x" + height + "x" + bits);
            }
            var offsets = raw.longs(TiffSamples.STRIP_OFFSETS);
            var counts = raw.longs(TiffSamples.STRIP_BYTE_COUNTS);
            if (offsets == null || counts == null || offsets.length == 0 || counts.length == 0) {
                throw new MediaException.Malformed("SRW raw image without data");
            }
            var count = counts[0];
            PackedRows.Row row;
            int rowBytes;
            if (compression == WORDS && count >= (long) width * height * 2) {
                rowBytes = width * 2;
                row = PackedRows.of(PackedRows.Layout.LITTLE_WORDS, 16);
            } else if (compression == PACKED && bits == 12 && width % 2 == 0 && count == (long) width * height * 3 / 2) {
                rowBytes = width * 3 / 2;
                var little = note == null ? reader.little() : note.longValue(0x0040, reader.little() ? 0 : 1) == 0;
                row = PackedRows.of(little ? PackedRows.Layout.LSB_FIRST : PackedRows.Layout.MSB_FIRST, 12);
            } else {
                // TODO: decode Samsung's compression of compression code 32770 at fewer bits than the photosites
                file.unsupported = "SRW compression " + compression + " at " + count + " bytes for " + width + "x"
                        + height + " photosites is not supported";
                return;
            }
            var offset = reader.base() + offsets[0];
            var source = reader.source();
            sensor.width = width;
            sensor.height = height;
            sensor.bitsPerSample = bits;
            sensor.compression = "uncompressed";
            sensor.reader = (plane, limits) -> PackedRows.read(source, offset, rowBytes, row, plane, limits);
        } else {
            try {
                sensor.reader = TiffSamples.describe(raw, sensor);
            } catch (MediaException.Unsupported exception) {
                // TODO: decode Samsung's compressions 32772 and 32773
                file.unsupported = exception.getMessage();
                return;
            }
        }
        sensor.cfa = TiffEpParser.cfa(raw, tree.exif);
        if (sensor.cfa == null) {
            file.unsupported = "the SRW raw image has no CFA pattern";
            return;
        }
        var white = (sensor.bitsPerSample <= 12 ? WHITE_12 : WHITE_14).get(file.model == null ? "" : file.model.strip());
        sensor.white = new double[]{white != null ? white : (1 << sensor.bitsPerSample) - 1};
        if (note != null) {
            levels(note, tree.exif, sensor, file);
        }
        file.sensor = sensor;
    }

    /**
     * Finds the maker note's directory: a TIFF directory at the start of the {@code MakerNote}
     * value, its offsets relative to that start.
     *
     * @param tree the directories
     * @return the directory, or {@code null}
     */
    private static Ifd makerNote(TiffTree tree) {
        if (tree.exif == null) {
            return null;
        }
        var position = tree.exif.valuePosition(TiffTree.MAKER_NOTE);
        if (position < 0 || tree.exif.valueCount(TiffTree.MAKER_NOTE) < 6) {
            return null;
        }
        var reader = tree.first.reader();
        try {
            return reader.with(position, reader.little()).directory(0);
        } catch (MediaException.Malformed exception) {
            return null;
        }
    }

    /**
     * Reads the as-shot white balance of the maker note.
     *
     * @param note the maker note's directory
     * @param file the description to fill
     */
    private static void balance(Ifd note, RawFile file) {
        var key = key(note);
        var balance = note.longs(0xA021);
        if (balance != null && balance.length >= 4) {
            var red = balance[0] - key[0];
            var green = (balance[1] - key[1] + balance[2] - key[2]) / 2.0;
            var blue = balance[3] - key[3];
            if (red > 0 && green > 0 && blue > 0) {
                file.color.asShotNeutral = new double[]{green / red, 1, green / blue};
            }
        }
    }

    /**
     * {@return the first four values of the maker note's key, zero when it has none}
     *
     * @param note the maker note's directory
     */
    private static long[] key(Ifd note) {
        var key = note.longs(0xA020);
        return key == null || key.length < 4 ? new long[4] : key;
    }

    /**
     * Reads the black level and the image area of the maker note.
     *
     * @param note the maker note's directory
     * @param exif the EXIF directory, or {@code null}
     * @param sensor the sensor description, its size and color filter array set
     * @param file the description to fill
     */
    private static void levels(Ifd note, Ifd exif, Sensor sensor, RawFile file) {
        var key = key(note);
        var black = note.longs(0xA028);
        if (black != null && black.length >= 4) {
            var levels = new double[4];
            for (var index = 0; index < 4; index++) {
                levels[index] = (int) black[index] - (int) key[index];
            }
            var pattern = cfaOrder(sensor.cfa, levels);
            if (pattern != null) {
                sensor.blackRows = 2;
                sensor.blackColumns = 2;
                sensor.black = pattern;
            }
        }
        var areas = note.longs(0xA010);
        if (areas != null && areas.length >= 8) {
            var left = areas[4];
            var top = areas[5];
            var right = areas[6];
            var bottom = areas[7];
            if (left >= 0 && top >= 0 && right > left && bottom > top && right <= sensor.width && bottom <= sensor.height) {
                var width = right - left;
                var height = bottom - top;
                var imageWidth = exif == null ? 0 : exif.longValue(40962, 0);
                var imageHeight = exif == null ? 0 : exif.longValue(40963, 0);
                if (imageWidth > 0 && imageHeight > 0 && imageWidth <= width && imageHeight <= height) {
                    left += (width - imageWidth) / 2;
                    top += (height - imageHeight) / 2;
                    width = imageWidth;
                    height = imageHeight;
                }
                sensor.defaultCrop = new double[]{left, top, width, height};
            }
        }
    }

    /**
     * Places levels given in red, green, green, blue order on a 2x2 color filter array: the first
     * green photosite of the array in raster order takes the first green level.
     *
     * @param cfa the color filter array
     * @param levels the red, green, green and blue levels
     * @return the levels of the array's four photosites in raster order, or {@code null} when the
     * array is not a 2x2 Bayer array
     */
    static double[] cfaOrder(CfaPattern cfa, double[] levels) {
        if (cfa == null || cfa.width() != 2 || cfa.height() != 2) {
            return null;
        }
        var pattern = new double[4];
        var greens = 0;
        for (var index = 0; index < 4; index++) {
            var color = cfa.color(index / 2, index % 2);
            if (color == CfaColor.RED) {
                pattern[index] = levels[0];
            } else if (color == CfaColor.GREEN) {
                pattern[index] = levels[1 + Math.min(greens++, 1)];
            } else if (color == CfaColor.BLUE) {
                pattern[index] = levels[3];
            } else {
                return null;
            }
        }
        return greens == 2 ? pattern : null;
    }
}
