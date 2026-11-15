package com.github.auties00.smalt.image.raw.internal;

import com.github.auties00.smalt.MediaException;

/**
 * Parses a Mamiya MEF file (the ZD): a TIFF/EP structure whose color filter array directory holds
 * uncompressed 12-bit samples, packed most significant bit first, and whose maker note gives the
 * as-shot white balance.
 *
 * <p>The maker note is a TIFF directory at the start of the {@code MakerNote} value, its offsets
 * relative to the file's TIFF header; the directory it links to as its next one holds the white
 * balance. Its tag {@code 0xB3} holds the camera's multipliers in red, green, green and blue order,
 * on a scale where 256 is unity, and tag {@code 0xB2} a table of further multipliers in the same
 * order, of which tags {@code 0xA2} (the white balance mode) and {@code 0xA3} (its fine-tuning)
 * select one: modes 0 to 8 select the middle entry of the mode's group of seven, moved by the
 * fine-tuning, whose low three bits hold a signed magnitude of at most three steps; modes 28 to
 * 100 are color temperatures in hundreds of kelvin, selecting entry {@code 35 + mode} and ignoring
 * the fine-tuning; other modes select the middle entry of mode 0. The as-shot neutral of each
 * color is the product of the green-to-color ratios of the two multipliers, the green being the
 * mean of the two greens; multipliers with a zero entry count as unity. The black level is 0, the
 * white level 4000, the image the sensor area less four photosites on each side.
 *
 * @implNote This implementation derives the packing, the levels, the crop and the white balance
 * rule from a CC BY-NC-SA sample of the raw.pixls.us archive and copies of it whose maker note
 * values were changed, against the values Adobe DNG Converter, run as a black box, stores for them.
 */
final class MefParser {
    /**
     * The white level of the ZD's photosites, which no tag gives.
     */
    static final int WHITE = 4000;

    /**
     * The photosites the camera's image leaves out on each side of the sensor area.
     */
    private static final int BORDER = 4;

    /**
     * The tag of the white balance mode.
     */
    private static final int MODE = 0xA2;

    /**
     * The tag of the white balance fine-tuning.
     */
    private static final int FINE_TUNING = 0xA3;

    /**
     * The tag of the table of white balance multipliers.
     */
    private static final int TABLE = 0xB2;

    /**
     * The tag of the camera's white balance multipliers.
     */
    private static final int MULTIPLIERS = 0xB3;

    /**
     * The white balance modes that select a group of seven entries of the table.
     */
    private static final int GROUPS = 9;

    /**
     * The smallest color temperature mode, in hundreds of kelvin.
     */
    private static final int LOWEST_TEMPERATURE = 28;

    /**
     * The largest color temperature mode, in hundreds of kelvin.
     */
    private static final int HIGHEST_TEMPERATURE = 100;

    /**
     * The table entry of the lowest color temperature mode.
     */
    private static final int TEMPERATURE_ENTRY = 63;

    /**
     * Prevents instantiation.
     */
    private MefParser() {
        throw new AssertionError();
    }

    /**
     * {@return whether a camera make is Mamiya's}
     *
     * @param vendor the upper-case make of the first directory
     */
    static boolean matches(String vendor) {
        return vendor.startsWith("MAMIYA");
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
            file.unsupported = "the MEF file holds no raw image";
            return;
        }
        var sensor = new Sensor();
        try {
            sensor.reader = TiffSamples.describe(raw, sensor);
        } catch (MediaException.Unsupported exception) {
            file.unsupported = exception.getMessage();
            return;
        }
        sensor.cfa = TiffEpParser.cfa(raw, tree.exif);
        if (sensor.cfa == null) {
            file.unsupported = "the MEF raw image has no CFA pattern";
            return;
        }
        sensor.black = new double[]{0};
        sensor.white = new double[]{Math.min(WHITE, (1 << sensor.bitsPerSample) - 1)};
        if (sensor.width > 2 * BORDER && sensor.height > 2 * BORDER) {
            sensor.defaultCrop = new double[]{BORDER, BORDER, sensor.width - 2 * BORDER, sensor.height - 2 * BORDER};
        }
        double[] neutral;
        try {
            var balance = balanceDirectory(tree);
            neutral = balance == null ? null : neutral(balance);
        } catch (MediaException.Malformed exception) {
            neutral = null;
        }
        if (neutral != null) {
            file.color.asShotNeutral = neutral;
        } else {
            sensor.estimateBalance = true;
        }
        file.sensor = sensor;
    }

    /**
     * Finds the maker note's white balance directory: the directory the maker note's own directory
     * links to as its next one.
     *
     * @param tree the directories
     * @return the directory, or {@code null}
     */
    private static Ifd balanceDirectory(TiffTree tree) {
        if (tree.exif == null) {
            return null;
        }
        var position = tree.exif.valuePosition(TiffTree.MAKER_NOTE);
        if (position < 0 || tree.exif.valueCount(TiffTree.MAKER_NOTE) < 6) {
            return null;
        }
        var reader = tree.first.reader();
        try {
            var note = reader.directory(position - reader.base());
            var next = note.next();
            return next <= 0 || next == note.offset() ? null : reader.directory(next);
        } catch (MediaException.Malformed exception) {
            return null;
        }
    }

    /**
     * Computes the as-shot neutral of the white balance directory.
     *
     * @param balance the white balance directory
     * @return the neutral of red, green and blue, or {@code null} when the directory has no table
     * entry for the mode
     */
    static double[] neutral(Ifd balance) {
        var table = balance.bytes(TABLE);
        if (table == null) {
            return null;
        }
        var entry = entry(balance.longValue(MODE, 0), balance.longValue(FINE_TUNING, 0));
        if ((entry + 1) * 16L > table.length) {
            return null;
        }
        var little = balance.reader().little();
        var selected = new long[4];
        for (var index = 0; index < 4; index++) {
            selected[index] = u32(table, entry * 16 + index * 4, little);
        }
        var camera = balance.bytes(MULTIPLIERS);
        var multipliers = new long[]{256, 256, 256, 256};
        if (camera != null && camera.length >= 16) {
            var read = new long[4];
            var valid = true;
            for (var index = 0; index < 4; index++) {
                read[index] = u32(camera, index * 4, little);
                valid &= read[index] > 0;
            }
            if (valid) {
                multipliers = read;
            }
        }
        for (var value : selected) {
            if (value <= 0) {
                return null;
            }
        }
        var cameraGreen = (multipliers[1] + multipliers[2]) / 2.0;
        var tableGreen = (selected[1] + selected[2]) / 2.0;
        return new double[]{cameraGreen / multipliers[0] * (tableGreen / selected[0]), 1,
                cameraGreen / multipliers[3] * (tableGreen / selected[3])};
    }

    /**
     * {@return the table entry a white balance mode and fine-tuning select}
     *
     * @param mode the white balance mode
     * @param fineTuning the fine-tuning
     */
    static int entry(long mode, long fineTuning) {
        if (mode >= LOWEST_TEMPERATURE && mode <= HIGHEST_TEMPERATURE) {
            return (int) (TEMPERATURE_ENTRY + mode - LOWEST_TEMPERATURE);
        }
        var steps = (int) (fineTuning & 3);
        var middle = mode >= 0 && mode < GROUPS ? (int) mode * 7 + 3 : 3;
        return (fineTuning & 4) != 0 ? middle - steps : middle + steps;
    }

    /**
     * {@return the unsigned 32-bit integer at an index}
     *
     * @param data the bytes
     * @param at the index
     * @param little whether the integer is little-endian
     */
    private static long u32(byte[] data, int at, boolean little) {
        long value = 0;
        for (var index = 0; index < 4; index++) {
            var octet = data[at + (little ? 3 - index : index)] & 0xFFL;
            value = (value << 8) | octet;
        }
        return value;
    }
}
