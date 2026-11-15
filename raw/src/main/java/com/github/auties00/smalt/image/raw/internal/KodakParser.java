package com.github.auties00.smalt.image.raw.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.raw.CfaPattern;

import java.util.Map;

/**
 * Parses a Kodak raw file: the DCR and TIFF files of the DCS cameras and the KDC files of the EasyShare and P
 * series cameras.
 *
 * <p>A DCS file is a TIFF/EP structure whose raw directory holds the sensor data, uncompressed, lossless JPEG or in
 * Kodak's compression 65000 ({@link KodakDecoder}), and whose first directory points, with tag 33424, at Kodak's
 * directory: tags 1003 to 1006 give the image area's left and top edges, width and height; tags 1039 and 1040 the
 * black level of each column and row, negated; tag 2317 the linearization table, which the raw directory's TIFF
 * gray response curve (tag 291) repeats in the older files; tags 2125 and 2120 the levels of a neutral surface,
 * red, green and blue, the first when tag 1018 is 1, the second otherwise. The white level is the largest value
 * of the linearization table.
 *
 * <p>An EOS DCS file holds its photosites as an untagged 8-bit image; see {@link #eosDcs(TiffTree, RawFile)}.
 *
 * <p>A KDC file of the later cameras has a first directory describing its JPEG preview, which lies at the end of
 * the file, and points, with tag 65024, at a directory of Kodak's tags: 0xFA13 and 0xFA14 give the sensor's width
 * and height, 0xFA15 its filter array as letters, 0xFA31 and 0xFA32 the width and height of the image area,
 * centered in the sensor; 0xFA25 and 0xFA12 hold the white balance multipliers of red, green and blue in 1/65536
 * units, the first under automatic white balance (0xFA10 1), the second otherwise. The sensor data
 * is 12-bit, packed most significant bit first, after the metadata: after the {@code 0xFF} bytes that pad it to
 * a 4096-byte boundary or, without them, right before the JPEG preview. The filter array 0xFA15 states is not the
 * one the photosites show on some models, whose own {@link #FILTERS_OF_MODEL} keeps.
 *
 * @implNote This implementation's layouts, levels, crops and white balances were worked out against Adobe DNG
 * Converter, run as a black box, on CC0 sample files, whose photosites it reproduces exactly. The converter's
 * white level of some models is not in the file, so {@link #WHITE_OF_MODEL} keeps the levels it gives them; the
 * other models take the largest value of their linearization table or bit depth. The rule choosing between two
 * tags of the white balance is the one the samples follow.
 */
final class KodakParser {
    /**
     * The tag of the first directory pointing at the Kodak directory of a DCS file.
     */
    static final int KODAK_IFD = 33424;

    /**
     * The tag of the first directory pointing at the Kodak directory of a KDC file.
     */
    static final int KDC_IFD = 65024;

    /**
     * Kodak's compression of the DCS sensor data.
     */
    static final int COMPRESSION_KODAK = 65000;

    /**
     * The block size the metadata of a KDC file is padded to.
     */
    private static final int PADDING_BLOCK = 4096;

    /**
     * The white levels Adobe DNG Converter gives the models whose level is not the largest value of their
     * linearization table or bit depth.
     */
    private static final Map<String, Integer> WHITE_OF_MODEL = Map.of("Z990", 4000, "P712", 4030, "DCS760C", 3500);

    /**
     * The start of the model names of the EOS DCS cameras.
     */
    private static final String EOS_DCS = "EOSDCS";

    /**
     * The masked columns at each side of an EOS DCS image.
     */
    private static final int EOS_DCS_MASKED = 2;

    /**
     * The tag of the Kodak directory holding the linearization table.
     */
    private static final int LINEARIZATION = 2317;

    /**
     * The filter arrays of the KDC models whose photosites show another one than 0xFA15 states: the arrays Adobe
     * DNG Converter gives them, which the mean levels of the four positions of CC0 samples confirm.
     */
    private static final Map<String, String> FILTERS_OF_MODEL = Map.of("Z981", "GRBG", "Z990", "BGGR");

    /**
     * The sizes in bytes of the TIFF field types, by type.
     */
    private static final int[] TYPE_SIZES = {0, 1, 1, 2, 4, 8, 1, 1, 2, 4, 8, 4, 8, 4, 0, 0, 8, 8, 8};

    /**
     * Prevents instantiation.
     */
    private KodakParser() {
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
        var kdc = tree.first.child(KDC_IFD);
        if (kdc != null && kdc.has(0xFA13) && kdc.has(0xFA14)) {
            easyShare(tree, kdc, file);
            return;
        }
        dcs(tree, file);
    }

    /**
     * Parses a DCS file.
     *
     * @param tree the directories
     * @param file the description to fill
     */
    private static void dcs(TiffTree tree, RawFile file) {
        var raw = TiffEpParser.rawDirectory(tree);
        var kodak = tree.first.child(KODAK_IFD);
        if (kodak != null) {
            neutral(kodak.doubles(kodak.intValue(1018, 0) == 1 ? 2125 : 2120), file);
        }
        if (raw == null) {
            if (file.model != null && file.model.startsWith(EOS_DCS)) {
                eosDcs(tree, file);
                return;
            }
            file.unsupported = "the Kodak file holds no raw image this decoder recognizes";
            return;
        }
        var sensor = new Sensor();
        if (raw.intValue(TiffSamples.COMPRESSION, 1) == COMPRESSION_KODAK) {
            sensor.width = raw.intValue(TiffSamples.IMAGE_WIDTH, 0);
            sensor.height = raw.intValue(TiffSamples.IMAGE_LENGTH, 0);
            sensor.bitsPerSample = raw.intValue(TiffSamples.BITS_PER_SAMPLE, 12);
            if (sensor.width <= 0 || sensor.height <= 0 || sensor.bitsPerSample < 1 || sensor.bitsPerSample > 16
                    || raw.intValue(TiffSamples.SAMPLES_PER_PIXEL, 1) != 1) {
                throw new MediaException.Malformed("a Kodak raw image of " + sensor.width + "x" + sensor.height);
            }
            sensor.compression = "Kodak 65000";
            sensor.reader = KodakDecoder.reader(raw, sensor.width, sensor.height);
        } else {
            try {
                sensor.reader = TiffSamples.describe(raw, sensor);
            } catch (MediaException.Unsupported exception) {
                file.unsupported = exception.getMessage();
                return;
            }
        }
        var counts = raw.longs(TiffSamples.STRIP_BYTE_COUNTS);
        if (raw.intValue(TiffSamples.COMPRESSION, 1) == 1 && counts != null && counts.length == 1
                && counts[0] < ((long) sensor.width * sensor.bitsPerSample + 7) / 8 * sensor.height) {
            // TODO: decode the compressed sensor data the DC120 stores under compression 1, which Adobe DNG Converter
            //  does not read either
            file.unsupported = "Kodak sensor data smaller than its uncompressed size is not supported";
            return;
        }
        sensor.cfa = TiffEpParser.cfa(raw, tree.exif);
        if (sensor.cfa == null) {
            file.unsupported = "the Kodak raw image has no readable CFA pattern";
            return;
        }
        var curve = kodak == null ? null : kodak.longs(LINEARIZATION);
        if (curve == null || curve.length < 2 || curve.length > 1 << sensor.bitsPerSample) {
            curve = raw.longs(291);
        }
        if (curve != null && curve.length >= 2 && curve.length <= 1 << sensor.bitsPerSample && !identity(curve)) {
            var table = new int[curve.length];
            for (var index = 0; index < table.length; index++) {
                table[index] = (int) Math.clamp(curve[index], 0, 65535);
            }
            sensor.linearization = table;
        }
        var white = WHITE_OF_MODEL.get(file.model == null ? "" : file.model);
        sensor.white = new double[]{white != null ? white : sensor.linearization != null
                ? sensor.linearization[sensor.linearization.length - 1] : (1 << sensor.bitsPerSample) - 1};
        if (kodak != null) {
            sensor.blackDeltaH = negated(kodak.doubles(1039), sensor.width);
            sensor.blackDeltaV = negated(kodak.doubles(1040), sensor.height);
            var left = kodak.longValue(1003, -1);
            var top = kodak.longValue(1004, -1);
            var width = kodak.longValue(1005, 0);
            var height = kodak.longValue(1006, 0);
            if (left >= 0 && top >= 0 && width > 0 && height > 0 && left + width <= sensor.width
                    && top + height <= sensor.height) {
                sensor.defaultCrop = new double[]{left, top, width, height};
            }
        }
        if (file.color.asShotNeutral == null) {
            sensor.estimateBalance = true;
        }
        file.sensor = sensor;
    }

    /**
     * Parses an EOS DCS file: an 8-bit grayscale full-resolution child directory holding the photosites of a
     * green, red, blue, green filter array, the first directory's gray response curve (tag 291) as linearization
     * table, and two masked columns at each side, whose mean linearized level is the black level.
     *
     * @param tree the directories
     * @param file the description to fill
     */
    private static void eosDcs(TiffTree tree, RawFile file) {
        Ifd raw = null;
        for (var ifd : tree.directories) {
            if (ifd != tree.first && ifd.intValue(254, 1) == 0 && ifd.intValue(TiffSamples.PHOTOMETRIC, 0) == 1
                    && ifd.intValue(TiffSamples.SAMPLES_PER_PIXEL, 1) == 1
                    && ifd.intValue(TiffSamples.BITS_PER_SAMPLE, 0) == 8
                    && (raw == null || ifd.intValue(TiffSamples.IMAGE_WIDTH, 0) > raw.intValue(TiffSamples.IMAGE_WIDTH, 0))) {
                raw = ifd;
            }
        }
        var curve = tree.first.longs(291);
        if (raw == null || curve == null || curve.length != 256) {
            file.unsupported = "the EOS DCS file holds no raw image this decoder recognizes";
            return;
        }
        var sensor = new Sensor();
        Sensor.Reader samples;
        try {
            samples = TiffSamples.describe(raw, sensor);
        } catch (MediaException.Unsupported exception) {
            file.unsupported = exception.getMessage();
            return;
        }
        if (sensor.width <= 2 * EOS_DCS_MASKED) {
            throw new MediaException.Malformed("an EOS DCS image of " + sensor.width + " columns");
        }
        var table = new int[curve.length];
        for (var index = 0; index < table.length; index++) {
            table[index] = (int) Math.clamp(curve[index], 0, 65535);
        }
        sensor.linearization = table;
        sensor.white = new double[]{table[table.length - 1]};
        sensor.cfa = CfaPattern.bayer("GRBG");
        sensor.activeArea = new int[]{0, EOS_DCS_MASKED, sensor.height, sensor.width - EOS_DCS_MASKED};
        sensor.black = new double[]{0};
        sensor.estimateBalance = true;
        sensor.reader = (plane, limits) -> {
            samples.read(plane, limits);
            var sum = 0L;
            for (var y = 0; y < plane.height; y++) {
                var line = y * plane.width;
                for (var x = 0; x < EOS_DCS_MASKED; x++) {
                    sum += table[plane.integers[line + x] & 0xFF]
                            + table[plane.integers[line + plane.width - 1 - x] & 0xFF];
                }
            }
            sensor.black = new double[]{(double) sum / (2L * EOS_DCS_MASKED * plane.height)};
        };
        file.sensor = sensor;
    }

    /**
     * Returns whether a table maps every value to itself.
     *
     * @param curve the table
     * @return whether the table is the identity
     */
    private static boolean identity(long[] curve) {
        for (var index = 0; index < curve.length; index++) {
            if (curve[index] != index) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns the black level deltas of a Kodak tag, which stores them negated, when it has one per position.
     *
     * @param values the tag's values, or {@code null}
     * @param size the number of columns or rows
     * @return the deltas, or {@code null} when the tag is absent, of another length or all zero
     */
    private static double[] negated(double[] values, int size) {
        if (values == null || values.length != size) {
            return null;
        }
        var deltas = new double[size];
        var any = false;
        for (var index = 0; index < size; index++) {
            deltas[index] = values[index] == 0 ? 0 : -values[index];
            any |= values[index] != 0;
        }
        return any ? deltas : null;
    }

    /**
     * Sets the as-shot neutral from the levels of a neutral surface.
     *
     * @param levels the red, green and blue levels, or {@code null}
     * @param file the description to fill
     */
    private static void neutral(double[] levels, RawFile file) {
        if (levels != null && levels.length >= 3 && levels[0] > 0 && levels[1] > 0 && levels[2] > 0) {
            file.color.asShotNeutral = new double[]{levels[0] / levels[1], 1, levels[2] / levels[1]};
        }
    }

    /**
     * Parses a KDC file of the EasyShare and P series.
     *
     * @param tree the directories
     * @param kdc the Kodak directory
     * @param file the description to fill
     */
    private static void easyShare(TiffTree tree, Ifd kdc, RawFile file) {
        var width = kdc.intValue(0xFA13, 0);
        var height = kdc.intValue(0xFA14, 0);
        if (width <= 0 || height <= 0 || width % 2 != 0) {
            throw new MediaException.Malformed("a Kodak sensor of " + width + "x" + height);
        }
        var name = file.model == null ? "" : model(file.model);
        var letters = FILTERS_OF_MODEL.getOrDefault(name, kdc.string(0xFA15));
        var cfa = letters != null && letters.length() == 4 && letters.chars().allMatch(c -> "RGB".indexOf(c) >= 0)
                && letters.chars().filter(c -> c == 'R').count() == 1
                && letters.chars().filter(c -> c == 'B').count() == 1
                ? CfaPattern.bayer(letters) : null;
        if (cfa == null) {
            file.unsupported = "the Kodak file has no readable CFA pattern";
            return;
        }
        var multipliers = kdc.longs(kdc.intValue(0xFA10, 0) == 1 ? 0xFA25 : 0xFA12);
        if (multipliers != null && multipliers.length >= 3 && multipliers[0] > 0 && multipliers[1] > 0
                && multipliers[2] > 0) {
            file.color.asShotNeutral = new double[]{(double) multipliers[1] / multipliers[0], 1,
                    (double) multipliers[1] / multipliers[2]};
        }
        var rowBytes = width / 2 * 3;
        var reader = tree.first.reader();
        var source = reader.source();
        var offset = sensorData(tree, reader, (long) rowBytes * height);
        if (offset < 0 || source.size() >= 0 && offset + (long) rowBytes * height > source.size()) {
            file.unsupported = "the Kodak file's sensor data of " + width + "x" + height + " photosites is not found";
            return;
        }
        var sensor = new Sensor();
        sensor.width = width;
        sensor.height = height;
        sensor.bitsPerSample = 12;
        sensor.compression = "uncompressed";
        sensor.cfa = cfa;
        var white = WHITE_OF_MODEL.get(name);
        sensor.white = new double[]{white != null ? white : 4095};
        var cropWidth = kdc.intValue(0xFA31, 0);
        var cropHeight = kdc.intValue(0xFA32, 0);
        if (cropWidth > 0 && cropHeight > 0 && cropWidth <= width && cropHeight <= height) {
            sensor.defaultCrop = new double[]{(width - cropWidth) / 2, (height - cropHeight) / 2, cropWidth,
                    cropHeight};
        }
        sensor.estimateBalance = file.color.asShotNeutral == null;
        var row = PackedRows.of(PackedRows.Layout.MSB_FIRST, 12);
        sensor.reader = (plane, limits) -> PackedRows.read(source, offset, rowBytes, row, plane, limits);
        file.sensor = sensor;
    }

    /**
     * Returns the short name of a KDC model, without the maker and the series words around it.
     *
     * @param model the model of the first directory, such as {@code "KODAK P712 ZOOM DIGITAL CAMERA"}
     * @return the name, such as {@code "P712"}
     */
    static String model(String model) {
        var words = model.toUpperCase(java.util.Locale.ROOT).split("\\s+");
        for (var word : words) {
            if (word.length() >= 2 && Character.isLetter(word.charAt(0)) && Character.isDigit(word.charAt(1))) {
                return word;
            }
        }
        return model;
    }

    /**
     * Finds the start of a KDC file's sensor data. When {@code 0xFF} bytes pad the metadata to a 4096-byte
     * boundary, the data starts after them; otherwise it ends where the JPEG preview starts, a few bytes past the
     * last value of the metadata directories.
     *
     * @param tree the directories
     * @param reader the TIFF structure
     * @param size the size of the sensor data in bytes
     * @return the absolute offset, or {@code -1} when the metadata cannot be measured
     */
    static long sensorData(TiffTree tree, TiffReader reader, long size) {
        var end = 0L;
        for (var ifd : tree.directories) {
            end = Math.max(end, extent(ifd, 513));
        }
        if (tree.exif != null) {
            end = Math.max(end, extent(tree.exif, -1));
        }
        var kdc = tree.first.child(KDC_IFD);
        if (kdc != null) {
            end = Math.max(end, extent(kdc, -1));
        }
        if (end <= 0) {
            return -1;
        }
        var padded = padding(reader, end);
        if (padded > end) {
            return padded;
        }
        var preview = tree.first.longValue(513, 0);
        if (preview > 0) {
            var start = reader.base() + preview - size;
            if (start >= end && start - end < PADDING_BLOCK) {
                return start;
            }
        }
        return end;
    }

    /**
     * Skips the {@code 0xFF} bytes that pad the metadata to a 4096-byte boundary.
     *
     * @param reader the TIFF structure
     * @param end the end of the metadata
     * @return the offset after the padding, {@code end} without padding
     */
    private static long padding(TiffReader reader, long end) {
        var block = new byte[PADDING_BLOCK];
        var position = end;
        while (true) {
            var boundary = (position / PADDING_BLOCK + 1) * PADDING_BLOCK;
            var length = (int) (boundary - position);
            if (reader.readAvailable(position, block, 0, length) < length) {
                return position;
            }
            for (var index = 0; index < length; index++) {
                if (block[index] != (byte) 0xFF) {
                    return position;
                }
            }
            position = boundary;
        }
    }

    /**
     * Returns the end of a directory and of its tags' values that lie outside it.
     *
     * @param ifd the directory
     * @param skipped a tag whose value is not counted, or {@code -1}
     * @return the absolute offset after the directory and its values
     */
    private static long extent(Ifd ifd, int skipped) {
        var base = ifd.reader().base();
        var end = base + ifd.offset() + 2 + 12L * ifd.count() + 4;
        for (var index = 0; index < ifd.count(); index++) {
            var tag = ifd.tagAt(index);
            if (tag == skipped) {
                continue;
            }
            var type = ifd.type(tag);
            var size = type > 0 && type < TYPE_SIZES.length ? TYPE_SIZES[type] * ifd.valueCount(tag) : 0;
            if (size > 4) {
                end = Math.max(end, ifd.valuePosition(tag) + size);
            }
        }
        return end;
    }

    /**
     * Returns whether a make names Kodak.
     *
     * @param make the upper-case make of the first directory
     * @return whether the file is a Kodak file
     */
    static boolean matches(String make) {
        return make.startsWith("KODAK") || make.startsWith("EASTMAN KODAK");
    }
}
