/* Ports the Digital Negative (DNG) Specification 1.7.1.0, Adobe. This product includes DNG technology under license
   by Adobe. */
package com.github.auties00.smalt.image.raw.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.raw.CfaColor;
import com.github.auties00.smalt.image.raw.CfaPattern;
import com.github.auties00.smalt.internal.io.ByteArrays;

import java.util.ArrayList;
import java.util.Objects;

/**
 * Parses a DNG file (Adobe DNG specification 1.7.1): the main raw image among the directories, its
 * layout, levels and opcode lists, and the camera profile of the first directory.
 *
 * <p>The main image is the first directory, in the order {@link TiffTree} visits them, whose
 * {@code NewSubFileType} is 0 and whose photometric interpretation is a color filter array
 * (32803) or linear raw (34892). Profile tags are read from the first directory; the raw-image
 * tags from the main image's directory.
 */
final class DngParser {
    /**
     * The photometric interpretation of a color filter array.
     */
    static final int PHOTOMETRIC_CFA = 32803;

    /**
     * The photometric interpretation of linear raw data.
     */
    static final int PHOTOMETRIC_LINEAR_RAW = 34892;

    /**
     * Prevents instantiation.
     */
    private DngParser() {
        throw new AssertionError();
    }

    /**
     * Parses a DNG file.
     *
     * @param reader the TIFF structure
     * @param file the description to fill
     * @throws MediaException.Malformed if the structure is corrupt
     */
    static void parse(TiffReader reader, RawFile file) {
        var tree = new TiffTree(reader);
        var first = tree.first;
        tree.describe(file);
        var version = first.longs(50706);
        if (version != null && version.length == 4) {
            file.dngVersion = version[0] + "." + version[1] + "." + version[2] + "." + version[3];
        }
        var unique = first.string(50708);
        if (file.model == null) {
            file.model = unique;
        }
        Ifd raw = null;
        for (var ifd : tree.directories) {
            var photometric = ifd.intValue(TiffSamples.PHOTOMETRIC, 0);
            if (ifd.longValue(TiffTree.NEW_SUBFILE_TYPE, 0) == 0
                    && (photometric == PHOTOMETRIC_CFA || photometric == PHOTOMETRIC_LINEAR_RAW)) {
                raw = ifd;
                break;
            }
        }
        profile(first, file.color);
        if (raw == null) {
            file.unsupported = "the DNG file holds no raw image";
            return;
        }
        var sensor = new Sensor();
        try {
            sensor.reader = TiffSamples.describe(raw, sensor);
        } catch (MediaException.Unsupported exception) {
            file.unsupported = exception.getMessage();
            return;
        }
        if (raw.intValue(TiffSamples.PHOTOMETRIC, 0) == PHOTOMETRIC_CFA) {
            if (sensor.samplesPerPixel != 1) {
                throw new MediaException.Malformed("CFA image with " + sensor.samplesPerPixel + " samples per pixel");
            }
            var layout = raw.intValue(50711, 1);
            if (layout != 1) {
                file.unsupported = "DNG CFA layout " + layout + " (staggered photosites) is not supported";
                return;
            }
            sensor.cfa = pattern(raw);
            var planes = raw.longs(50710);
            if (planes != null && planes.length >= 3) {
                var mapping = new int[]{-1, -1, -1, -1, -1, -1, -1};
                for (var plane = 0; plane < planes.length; plane++) {
                    if (planes[plane] >= 0 && planes[plane] < mapping.length) {
                        mapping[(int) planes[plane]] = plane;
                    }
                }
                sensor.planeOfColor = mapping;
            }
        }
        levels(raw, sensor);
        file.sensor = sensor;
    }

    /**
     * Reads the color filter array pattern of a directory.
     *
     * @param ifd the directory
     * @return the pattern
     * @throws MediaException.Malformed if the pattern is missing or inconsistent
     */
    static CfaPattern pattern(Ifd ifd) {
        var dims = ifd.longs(33421);
        var colors = ifd.longs(33422);
        if (dims == null || dims.length < 2 || colors == null) {
            throw new MediaException.Malformed("CFA image without a CFA pattern");
        }
        var rows = (int) dims[0];
        var columns = (int) dims[1];
        if (rows < 1 || columns < 1 || rows > 16 || columns > 16 || colors.length < (long) rows * columns) {
            throw new MediaException.Malformed("invalid CFA pattern of " + rows + "x" + columns);
        }
        var list = new ArrayList<CfaColor>();
        for (var index = 0; index < rows * columns; index++) {
            if (colors[index] < 0 || colors[index] > 6) {
                throw new MediaException.Malformed("invalid CFA color " + colors[index]);
            }
            list.add(CfaColor.of((int) colors[index]));
        }
        return CfaPattern.of(columns, rows, list);
    }

    /**
     * Reads the active area, crop, scale, black and white levels, linearization table and opcode
     * lists of the main image.
     *
     * @param raw the main image's directory
     * @param sensor the sensor description to fill
     */
    private static void levels(Ifd raw, Sensor sensor) {
        var spp = sensor.samplesPerPixel;
        var area = raw.longs(50829);
        if (area != null && area.length >= 4) {
            var top = (int) Math.clamp(area[0], 0, sensor.height);
            var left = (int) Math.clamp(area[1], 0, sensor.width);
            var bottom = (int) Math.clamp(area[2], top, sensor.height);
            var right = (int) Math.clamp(area[3], left, sensor.width);
            if (bottom > top && right > left) {
                sensor.activeArea = new int[]{top, left, bottom, right};
                // the DNG pattern starts at the active area's corner, the sensor's at the image's
                if (sensor.cfa != null) {
                    sensor.cfa = sensor.cfa.shifted(Math.floorMod(-top, sensor.cfa.height()),
                            Math.floorMod(-left, sensor.cfa.width()));
                }
            }
        }
        var origin = raw.doubles(50719);
        var size = raw.doubles(50720);
        if (origin != null && size != null && origin.length >= 2 && size.length >= 2) {
            sensor.defaultCrop = new double[]{origin[0], origin[1], size[0], size[1]};
        }
        var scale = raw.doubles(50718);
        if (scale != null && scale.length >= 2 && scale[0] > 0 && scale[1] > 0 && Double.isFinite(scale[0])
                && Double.isFinite(scale[1])) {
            sensor.scaleH = scale[0];
            sensor.scaleV = scale[1];
        }
        var linearization = raw.longs(50712);
        if (linearization != null && linearization.length > 0) {
            if (linearization.length > 65536) {
                throw new MediaException.Malformed("linearization table of " + linearization.length + " entries");
            }
            var table = new int[linearization.length];
            for (var index = 0; index < table.length; index++) {
                table[index] = (int) Math.clamp(linearization[index], 0, 65535);
            }
            sensor.linearization = table;
        }
        var repeat = raw.longs(50713);
        if (repeat != null && repeat.length >= 2 && repeat[0] >= 1 && repeat[1] >= 1 && repeat[0] <= 64 && repeat[1] <= 64) {
            sensor.blackRows = (int) repeat[0];
            sensor.blackColumns = (int) repeat[1];
        }
        var black = raw.doubles(50714);
        if (black != null && black.length > 0) {
            var count = sensor.blackRows * sensor.blackColumns * spp;
            var pattern = new double[count];
            for (var index = 0; index < count; index++) {
                pattern[index] = black[Math.min(index, black.length - 1)];
            }
            if (black.length < count && black.length != 1) {
                sensor.blackRows = 1;
                sensor.blackColumns = 1;
                pattern = new double[spp];
                for (var sample = 0; sample < spp; sample++) {
                    pattern[sample] = black[Math.min(sample, black.length - 1)];
                }
            }
            sensor.black = pattern;
        }
        sensor.blackDeltaH = raw.doubles(50715);
        sensor.blackDeltaV = raw.doubles(50716);
        var white = raw.doubles(50717);
        if (white != null && white.length > 0) {
            var levels = new double[spp];
            for (var sample = 0; sample < spp; sample++) {
                levels[sample] = white[Math.min(sample, white.length - 1)];
            }
            sensor.white = levels;
        } else if (sensor.floating) {
            sensor.white = ColorMath.filled(spp, 1.0);
        }
        sensor.opcodes1 = raw.bytes(51008);
        sensor.opcodes2 = raw.bytes(51009);
        sensor.opcodes3 = raw.bytes(51022);
    }

    /**
     * Reads the camera profile of the first directory.
     *
     * @param first the first directory
     * @param color the color description to fill
     */
    static void profile(Ifd first, ColorCalibration color) {
        int[] matrixTags = {50721, 50722, 52531};
        int[] calibrationTags = {50723, 50724, 52530};
        int[] forwardTags = {50964, 50965, 52532};
        int[] illuminantTags = {50778, 50779, 52529};
        int[] hueSatTags = {50938, 50939, 52537};
        var cameraSignature = first.string(50931);
        var profileSignature = first.string(50932);
        var calibrationMatches = Objects.equals(cameraSignature == null ? "" : cameraSignature,
                profileSignature == null ? "" : profileSignature);
        for (var index = 0; index < 3; index++) {
            color.colorMatrix[index] = matrix(first.doubles(matrixTags[index]));
            color.cameraCalibration[index] = calibrationMatches ? matrix(first.doubles(calibrationTags[index])) : null;
            color.forwardMatrix[index] = matrix(first.doubles(forwardTags[index]));
            color.illuminant[index] = first.intValue(illuminantTags[index], 0);
            if (color.illuminant[index] == 255) {
                color.illuminantXy[index] = illuminantData(first.bytes(52533 + index), first.reader().little());
            }
        }
        if (color.colorMatrix[2] != null && (color.colorMatrix[0] == null || color.colorMatrix[1] == null
                || color.illuminant[2] == 0)) {
            color.colorMatrix[2] = null;
        }
        color.analogBalance = first.doubles(50727);
        color.asShotNeutral = first.doubles(50728);
        color.asShotWhiteXy = first.doubles(50729);
        color.baselineExposure = first.doubleValue(50730, 0) + first.doubleValue(51109, 0);
        var hueSatDims = first.longs(50937);
        if (hueSatDims != null && hueSatDims.length >= 3 && valid(hueSatDims)) {
            var cells = hueSatDims[0] * hueSatDims[1] * Math.max(1, hueSatDims[2]) * 3;
            var dims = new int[]{(int) hueSatDims[0], (int) hueSatDims[1], (int) Math.max(1, hueSatDims[2])};
            for (var index = 0; index < 3; index++) {
                var data = first.doubles(hueSatTags[index]);
                if (data != null && data.length == cells) {
                    color.hueSatMap[index] = floats(data);
                    color.hueSatDims = dims;
                }
            }
            color.hueSatSrgb = first.intValue(51107, 0) == 1;
        }
        var lookDims = first.longs(50981);
        if (lookDims != null && lookDims.length >= 3 && valid(lookDims)) {
            var data = first.doubles(50982);
            var cells = lookDims[0] * lookDims[1] * Math.max(1, lookDims[2]) * 3;
            if (data != null && data.length == cells) {
                color.lookDims = new int[]{(int) lookDims[0], (int) lookDims[1], (int) Math.max(1, lookDims[2])};
                color.lookTable = floats(data);
                color.lookSrgb = first.intValue(51108, 0) == 1;
            }
        }
        var tone = first.doubles(50940);
        if (tone != null && tone.length >= 4 && tone.length % 2 == 0 && tone.length <= 1 << 16) {
            color.toneCurve = floats(tone);
        }
    }

    /**
     * Reads the chromaticity an {@code IlluminantData} tag gives: an x-y chromaticity (data type
     * 0: two rationals) or a spectral power distribution (data type 1: the sample count, the first
     * wavelength and the spacing in nanometers as rationals, then the samples as rationals),
     * integrated against the CIE 1931 standard observer.
     *
     * @param data the tag's bytes, or {@code null}
     * @param little whether the file is little endian
     * @return the chromaticity, or {@code null} when the data is absent or invalid
     */
    static double[] illuminantData(byte[] data, boolean little) {
        if (data == null || data.length < 18) {
            return null;
        }
        var type = ByteArrays.u16(data, 0, little);
        if (type == 0) {
            var x = rational(data, 2, little);
            var y = rational(data, 10, little);
            return x > 0 && y > 0 && x + y < 1 ? new double[]{x, y} : null;
        }
        if (type != 1 || data.length < 22) {
            return null;
        }
        var count = ByteArrays.u32(data, 2, little);
        if (count < 2 || 22 + count * 8 > data.length) {
            return null;
        }
        var minimum = rational(data, 6, little);
        var spacing = rational(data, 14, little);
        if (!(spacing > 0) || !Double.isFinite(minimum)) {
            return null;
        }
        var values = new double[(int) count];
        for (var index = 0; index < values.length; index++) {
            values[index] = rational(data, 22 + 8 * index, little);
        }
        var xy = ColorMath.spectrumXy(values, minimum, spacing);
        return xy[0] > 0 && xy[1] > 0 ? xy : null;
    }

    /**
     * Reads an unsigned rational.
     *
     * @param data the bytes
     * @param offset the offset of the numerator
     * @param little whether the integers are little endian
     * @return the value, or {@code NaN} for a zero denominator
     */
    private static double rational(byte[] data, int offset, boolean little) {
        var denominator = ByteArrays.u32(data, offset + 4, little);
        return denominator == 0 ? Double.NaN : (double) ByteArrays.u32(data, offset, little) / denominator;
    }

    /**
     * Returns whether table dimensions are within reason.
     *
     * @param dims the hue, saturation and value divisions
     * @return whether they describe a table of at most a million cells
     */
    private static boolean valid(long[] dims) {
        return dims[0] >= 1 && dims[1] >= 2 && dims[2] >= 0 && dims[0] <= 360 && dims[1] <= 256 && dims[2] <= 256
                && dims[0] * dims[1] * Math.max(1, dims[2]) <= 1_000_000;
    }

    /**
     * Validates a matrix of 3 or 4 rows or columns by 3.
     *
     * @param values the entries row by row, or {@code null} when the tag is absent
     * @return the values, or {@code null} when absent or not a valid matrix
     */
    private static double[] matrix(double[] values) {
        if (values == null || (values.length != 9 && values.length != 12)) {
            return null;
        }
        for (var value : values) {
            if (!Double.isFinite(value)) {
                return null;
            }
        }
        return values;
    }

    /**
     * Converts numbers to single precision.
     *
     * @param values the numbers
     * @return the converted values
     */
    private static float[] floats(double[] values) {
        var result = new float[values.length];
        for (var index = 0; index < values.length; index++) {
            result[index] = (float) values[index];
        }
        return result;
    }
}
