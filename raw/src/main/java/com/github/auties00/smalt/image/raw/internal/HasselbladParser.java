package com.github.auties00.smalt.image.raw.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.raw.CfaPattern;
import com.github.auties00.smalt.internal.limits.LimitChecks;

/**
 * Parses a Hasselblad 3FR file: a TIFF/EP structure whose raw image directory holds the sensor data as
 * lossless JPEG without color filter array tags, and whose directories carry DNG tags: the color matrix and
 * as-shot neutral in the first directory, the white level and the default crop in the raw directory.
 *
 * <p>The sensor data is uncompressed, a standard lossless JPEG stream or Hasselblad's predictor-8 variant, which
 * {@link HasselbladJpeg} reads; the photosites of a raw directory whose white level is 32767 are signed and are
 * stored offset by {@link HasselbladJpeg#SIGNED_OFFSET}, which becomes their black level. The color filter array
 * is red, green, green, blue from the stored image's top-left photosite.
 *
 * <p>The 3FR files of the CMOS cameras carry the camera's calibration of its sensor, a gain per color, which
 * {@link HasselbladCalibration} applies, re-encoding the photosites to new black and white levels. Those of the
 * CCD cameras (H3D, H4D) carry a correction of the charge between vertical neighbors and a flat field, which
 * {@link HasselbladFlatField} applies; those of the CCD camera backs (CFV, CF series) carry a gain per row and the
 * same correction, which {@link HasselbladRowCalibration} applies.
 *
 * @implNote This implementation's photosites, filter array, levels, crop and as-shot neutral were checked
 * against Adobe DNG Converter, run as a black box, on CC0 sample files. The FFF files, which Hasselblad's
 * software writes, and the calibrated 3FR files of the CMOS cameras decode to the converter's photosites
 * exactly. The calibrated H3D file decodes to them exactly, its four defective columns included, as do a CFV
 * and a CF132 file, run as local oracles; the calibrated H4D file differs from them by the converter's offset
 * ramps of up to 4 units, which this implementation lacks.
 */
final class HasselbladParser {
    /**
     * The number of bytes read to find the markers of the sensor data's stream.
     */
    private static final int HEADER_PROBE = 4096;

    /**
     * The name of the predictor-8 compression.
     */
    static final String COMPRESSION = "Hasselblad lossless JPEG";

    /**
     * The white level of the files whose raw directory states a black level, Hasselblad's FFF files among them,
     * in place of the 65535 they state: the level Adobe DNG Converter gives them, 96% of the stated one.
     */
    private static final int PROCESSED_WHITE = HasselbladCalibration.FILE_WHITE;

    /**
     * The white level of the raw directories whose photosites are signed.
     */
    private static final int SIGNED_WHITE = 32767;

    /**
     * Prevents instantiation.
     */
    private HasselbladParser() {
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
            file.unsupported = "the Hasselblad file holds no raw image this decoder recognizes";
            return;
        }
        var sensor = new Sensor();
        try {
            sensor.reader = TiffSamples.describe(raw, sensor);
        } catch (MediaException.Unsupported exception) {
            file.unsupported = exception.getMessage();
            return;
        }
        if (raw.intValue(TiffSamples.COMPRESSION, 1) == 7) {
            var predictorEight = predictorEight(raw, sensor);
            if (predictorEight != null) {
                sensor.reader = predictorEight;
                sensor.compression = COMPRESSION;
            }
        }
        sensor.cfa = raw.has(33421) && raw.has(33422) ? TiffEpParser.cfa(raw, tree.exif) : CfaPattern.bayer("RGGB");
        if (sensor.cfa == null) {
            file.unsupported = "the Hasselblad raw image has no readable CFA pattern";
            return;
        }
        var white = raw.doubles(50717);
        if (white != null && white.length > 0) {
            sensor.white = new double[]{white[0]};
        }
        var black = raw.doubles(50714);
        if (black != null && black.length == 1 && black[0] > 0 && black[0] < 65535) {
            sensor.black = new double[]{black[0]};
            if (sensor.white != null && sensor.white[0] == 65535) {
                sensor.white = new double[]{PROCESSED_WHITE};
            }
        }
        if (signed(raw) && COMPRESSION.equals(sensor.compression)) {
            sensor.black = new double[]{HasselbladJpeg.SIGNED_OFFSET};
            sensor.white = new double[]{SIGNED_WHITE + HasselbladJpeg.SIGNED_OFFSET};
        }
        var origin = raw.doubles(50719);
        var size = raw.doubles(50720);
        if (origin != null && size != null && origin.length >= 2 && size.length >= 2 && origin[0] >= 0 && origin[1] >= 0
                && size[0] > 0 && size[1] > 0 && origin[0] + size[0] <= sensor.width
                && origin[1] + size[1] <= sensor.height) {
            sensor.defaultCrop = new double[]{origin[0], origin[1], size[0], size[1]};
        }
        if (!signed(raw) && sensor.black != null && sensor.black.length == 1 && sensor.white != null
                && sensor.white[0] == PROCESSED_WHITE && HasselbladCalibration.supports(sensor.cfa)) {
            var calibration = HasselbladCalibration.read(tree, sensor.black[0]);
            if (calibration != null) {
                sensor.reader = calibration.apply(sensor.reader, sensor.cfa);
                sensor.black = new double[]{calibration.black};
                sensor.white = new double[]{HasselbladCalibration.WHITE};
            }
        } else if (sensor.white != null && sensor.white.length == 1 && (signed(raw)
                ? sensor.black != null && sensor.black[0] == HasselbladJpeg.SIGNED_OFFSET
                : sensor.black == null && sensor.white[0] == 65535)) {
            calibrateCcd(tree, reader, raw, sensor);
        }
        DngParser.profile(tree.first, file.color);
        DngParser.shot(tree.first, raw, file.color);
        file.sensor = sensor;
    }

    /**
     * Applies the calibration of a CCD camera's 3FR file, when the file records one {@link HasselbladFlatField}
     * or {@link HasselbladRowCalibration} applies, and sets the levels of the calibrated photosites.
     *
     * @param tree the file's directories
     * @param reader the TIFF structure
     * @param raw the raw directory
     * @param sensor the sensor, whose reader, filter array and levels the parser set
     */
    private static void calibrateCcd(TiffTree tree, TiffReader reader, Ifd raw, Sensor sensor) {
        var black = sensor.black == null ? 0 : (int) sensor.black[0];
        var flatField = HasselbladFlatField.read(tree, sensor, signed(raw));
        if (flatField == null) {
            var rows = HasselbladRowCalibration.read(tree, sensor, signed(raw));
            if (rows != null) {
                sensor.reader = rows.apply(sensor.reader, reader, black);
                sensor.black = new double[]{0};
                sensor.white = new double[]{rows.white()};
            }
            return;
        }
        sensor.reader = flatField.apply(sensor.reader, reader, black, sensor.cfa);
        if (flatField.version == HasselbladFlatField.VERSION_H3D) {
            sensor.white = new double[]{HasselbladCalibration.FILE_WHITE};
        } else {
            sensor.black = new double[]{HasselbladCalibration.blackOf(0, HasselbladFlatField.SIGNED_FILE_WHITE)};
            sensor.white = new double[]{HasselbladCalibration.WHITE};
        }
        // TODO: add the seam correction Adobe DNG Converter applies to version 30 (H4D) files, whose upper and
        //  lower sensor halves are read by separate outputs. Measured on uncompressed copies of the H4D sample:
        //  in the calibrated photosites, per color and per left or right half (split at width / 2), the converter
        //  adds round(kTop(x) * (y - top) / (center - top)) to the rows from the crop's top row to the center row
        //  (height / 2) and round(kBottom(x) * (bottom - y) / (center - top)) from there to the crop's bottom row,
        //  whole units added after the calibration's rounding. kTop and kBottom are real numbers that vary slowly
        //  along the row; on a constant image with a step across the center row they are both close to half the
        //  calibrated step (within 0.4 units, steps of 1 to 20 units), so the halves meet. The correction appears
        //  only when the image near the center row is dark: a step over a level of 3000 units (raw, above black)
        //  or less is corrected, over 3500 or more it is not, which is why noise and steps over level 10000 show
        //  none. The estimator is not derived: a change of the rows next to the center row moves k over a span of
        //  about 131 columns around it (linear transitions of that width), but nonlinearly (a 2-unit offset of
        //  400 columns moves k by 0.03 to 1.5 units depending on the color), single photosites do not move it,
        //  and a local change also shifts k elsewhere along the row by up to 0.3 units; on the sample's image, k
        //  (up to 4 units) is far below the medians of the per-column steps across the center row, plain or
        //  corrected for the vertical gradient (tens to hundreds of units)
    }

    /**
     * Returns the reader of a raw directory whose single strip is a predictor-8 lossless JPEG stream.
     *
     * @param raw the raw directory
     * @param sensor the sensor description, whose size the directory set
     * @return the reader, or {@code null} when the strip is another kind of lossless JPEG
     * @throws MediaException.Malformed if the stream's frame differs from the directory's image size
     */
    private static Sensor.Reader predictorEight(Ifd raw, Sensor sensor) {
        var offsets = raw.longs(TiffSamples.STRIP_OFFSETS);
        var counts = raw.longs(TiffSamples.STRIP_BYTE_COUNTS);
        if (offsets == null || counts == null || offsets.length != 1 || counts.length != 1) {
            return null;
        }
        var reader = raw.reader();
        var source = reader.source();
        var offset = reader.base() + offsets[0];
        var head = new byte[HEADER_PROBE];
        var read = reader.readAvailable(offset, head, 0, head.length);
        if (read < 4 || !HasselbladJpeg.matches(head, 0, read)) {
            return null;
        }
        var header = new HasselbladJpeg(head, 0, read);
        if (header.width != sensor.width || header.height != sensor.height) {
            throw new MediaException.Malformed("a Hasselblad JPEG frame of " + header.width + "x" + header.height
                    + " for a raw image of " + sensor.width + "x" + sensor.height);
        }
        var stated = counts[0];
        var signed = signed(raw);
        return (plane, limits) -> {
            var known = source.size();
            var length = known >= 0 ? Math.min(stated, Math.max(0, known - offset)) : stated;
            LimitChecks.checkAllocation(limits, length);
            if (length > Integer.MAX_VALUE - 16 || length < 4) {
                throw new MediaException.Malformed("Hasselblad sensor data of " + length + " bytes");
            }
            var data = new byte[(int) length];
            var available = reader.readAvailable(offset, data, 0, data.length);
            new HasselbladJpeg(data, 0, available).decode(data, available, signed, plane.integers, plane.width,
                    plane.height);
        };
    }

    /**
     * Returns whether a raw directory's photosites are signed numbers, as its white level of 32767 says.
     *
     * @param raw the raw directory
     * @return whether the photosites are signed
     */
    private static boolean signed(Ifd raw) {
        return raw.longValue(50717, 0) == SIGNED_WHITE;
    }
}
