package com.github.auties00.smalt.image.raw.internal;

import com.github.auties00.smalt.MediaException;

/**
 * Parses an Epson ERF file (the R-D1, R-D1s and R-D1x): a TIFF/EP structure whose color filter
 * array directory holds 12-bit samples in TIFF compression 32769, ten samples most significant bit
 * first in the first 15 bytes of each 16-byte block, and whose maker note holds the black levels and
 * a JPEG preview.
 *
 * <p>The maker note starts with {@code EPSON\0}, a version of two bytes and a TIFF directory whose
 * offsets are relative to the file's TIFF header. Its tag {@code 0x0401} holds the black level of
 * each photosite of the 2x2 color filter array in raster order, and tag {@code 0x0280} a JPEG
 * preview whose first byte is stored as another value than {@code 0xFF}. White is 4095; the image
 * is the 3008 by 2000 area centered on the sensor; the as-shot white balance, which no tag gives
 * readably, is estimated from the preview.
 *
 * @implNote This implementation follows the tag names of the ExifTool tag documentation (the maker
 * note shares the layout of Olympus' older one); the packing, the levels and the preview's damaged
 * byte were found by comparing CC0 sample files with the values Adobe DNG Converter, run as a black
 * box, stores for them.
 */
final class ErfParser {
    /**
     * The TIFF compression code of the packed sensor data.
     */
    private static final int PACKED = 32769;

    /**
     * The width of the camera's image.
     */
    private static final int IMAGE_WIDTH = 3008;

    /**
     * The height of the camera's image.
     */
    private static final int IMAGE_HEIGHT = 2000;

    /**
     * Prevents instantiation.
     */
    private ErfParser() {
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
        var note = makerNote(tree);
        if (note != null && note.has(0x0280)) {
            TiffTree.addPreview(file, reader, note.valuePosition(0x0280), note.valueCount(0x0280), true);
        }
        var raw = TiffEpParser.rawDirectory(tree);
        if (raw == null) {
            file.unsupported = "the ERF file holds no raw image";
            return;
        }
        var width = raw.intValue(TiffSamples.IMAGE_WIDTH, 0);
        var height = raw.intValue(TiffSamples.IMAGE_LENGTH, 0);
        var bits = raw.intValue(TiffSamples.BITS_PER_SAMPLE, 12);
        var compression = raw.intValue(TiffSamples.COMPRESSION, 1);
        var sensor = new Sensor();
        if (compression == PACKED) {
            if (width <= 0 || height <= 0 || bits != 12) {
                throw new MediaException.Malformed("invalid ERF raw image of " + width + "x" + height + "x" + bits);
            }
            var offsets = raw.longs(TiffSamples.STRIP_OFFSETS);
            var counts = raw.longs(TiffSamples.STRIP_BYTE_COUNTS);
            if (offsets == null || counts == null || offsets.length == 0 || counts.length == 0) {
                throw new MediaException.Malformed("ERF raw image without data");
            }
            var rowBytes = (width + 9) / 10 * 16;
            if (counts[0] < (long) rowBytes * height) {
                file.unsupported = "ERF data of " + counts[0] + " bytes for " + width + "x" + height
                        + " photosites is not supported";
                return;
            }
            var offset = reader.base() + offsets[0];
            var source = reader.source();
            var row = PackedRows.of(PackedRows.Layout.MSB_FIRST_TEN_IN_SIXTEEN, 12);
            sensor.width = width;
            sensor.height = height;
            sensor.bitsPerSample = 12;
            sensor.reader = (plane, limits) -> PackedRows.read(source, offset, rowBytes, row, plane, limits);
        } else {
            try {
                sensor.reader = TiffSamples.describe(raw, sensor);
            } catch (MediaException.Unsupported exception) {
                file.unsupported = exception.getMessage();
                return;
            }
        }
        sensor.cfa = TiffEpParser.cfa(raw, tree.exif);
        if (sensor.cfa == null) {
            file.unsupported = "the ERF raw image has no CFA pattern";
            return;
        }
        sensor.white = new double[]{(1 << sensor.bitsPerSample) - 1};
        var black = note == null ? null : note.longs(0x0401);
        if (black != null && black.length >= 4 && sensor.cfa.width() == 2 && sensor.cfa.height() == 2) {
            sensor.blackRows = 2;
            sensor.blackColumns = 2;
            sensor.black = new double[]{black[0], black[1], black[2], black[3]};
        }
        if (sensor.width >= IMAGE_WIDTH && sensor.height >= IMAGE_HEIGHT) {
            sensor.defaultCrop = new double[]{(sensor.width - IMAGE_WIDTH) / 2, (sensor.height - IMAGE_HEIGHT) / 2,
                    IMAGE_WIDTH, IMAGE_HEIGHT};
        }
        sensor.estimateBalance = true;
        file.sensor = sensor;
    }

    /**
     * Finds the maker note's directory, its offsets relative to the file's TIFF header.
     *
     * @param tree the directories
     * @return the directory, or {@code null}
     */
    private static Ifd makerNote(TiffTree tree) {
        if (tree.exif == null) {
            return null;
        }
        var position = tree.exif.valuePosition(TiffTree.MAKER_NOTE);
        if (position < 0 || tree.exif.valueCount(TiffTree.MAKER_NOTE) < 10) {
            return null;
        }
        var reader = tree.first.reader();
        var head = new byte[6];
        if (reader.readAvailable(position, head, 0, 6) < 6 || head[0] != 'E' || head[1] != 'P' || head[2] != 'S'
                || head[3] != 'O' || head[4] != 'N' || head[5] != 0) {
            return null;
        }
        try {
            return reader.directory(position + 8 - reader.base());
        } catch (MediaException.Malformed exception) {
            return null;
        }
    }
}
