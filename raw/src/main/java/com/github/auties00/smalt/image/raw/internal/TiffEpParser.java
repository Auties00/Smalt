package com.github.auties00.smalt.image.raw.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.raw.CfaColor;
import com.github.auties00.smalt.image.raw.CfaPattern;
import com.github.auties00.smalt.internal.io.ByteArrays;

import java.util.ArrayList;

/**
 * Parses a TIFF/EP-structured raw file of no specific vendor format: the largest directory holding
 * a color filter array or linear raw image, with a standard TIFF compression.
 *
 * <p>The color filter array comes from the TIFF/EP {@code CFARepeatPatternDim} and
 * {@code CFAPattern} tags of the image directory, or else from the EXIF {@code CFAPattern}; the
 * levels from the DNG level tags when a writer included them, otherwise black is zero and white
 * the largest value of the bit depth. Vendor parsers also use this class to locate the image of a
 * file and fill in what they do not override.
 */
final class TiffEpParser {
    /**
     * Prevents instantiation.
     */
    private TiffEpParser() {
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
        var raw = rawDirectory(tree);
        if (raw == null) {
            file.unsupported = "the file holds no raw image this decoder recognizes";
            return;
        }
        var sensor = new Sensor();
        try {
            sensor.reader = TiffSamples.describe(raw, sensor);
        } catch (MediaException.Unsupported exception) {
            file.unsupported = exception.getMessage();
            return;
        }
        if (raw.intValue(TiffSamples.PHOTOMETRIC, 0) == DngParser.PHOTOMETRIC_CFA) {
            sensor.cfa = cfa(raw, tree.exif);
            if (sensor.cfa == null) {
                file.unsupported = "the raw image has no readable CFA pattern";
                return;
            }
        }
        var black = raw.doubles(50714);
        if (black != null && black.length > 0) {
            sensor.black = new double[]{black[0]};
        }
        var white = raw.doubles(50717);
        if (white != null && white.length > 0) {
            sensor.white = new double[]{white[0]};
        }
        file.sensor = sensor;
    }

    /**
     * Finds the largest directory holding a color filter array or linear raw image.
     *
     * @param tree the directories
     * @return the directory, or {@code null}
     */
    static Ifd rawDirectory(TiffTree tree) {
        Ifd best = null;
        var bestPixels = 0L;
        for (var ifd : tree.directories) {
            var photometric = ifd.intValue(TiffSamples.PHOTOMETRIC, 0);
            if (photometric != DngParser.PHOTOMETRIC_CFA && photometric != DngParser.PHOTOMETRIC_LINEAR_RAW) {
                continue;
            }
            var pixels = ifd.longValue(TiffSamples.IMAGE_WIDTH, 0) * ifd.longValue(TiffSamples.IMAGE_LENGTH, 0);
            if (pixels > bestPixels) {
                best = ifd;
                bestPixels = pixels;
            }
        }
        return best;
    }

    /**
     * Reads the color filter array of an image directory, or of the EXIF directory.
     *
     * @param raw the image directory
     * @param exif the EXIF directory, or {@code null}
     * @return the pattern, or {@code null} when neither describes one
     */
    static CfaPattern cfa(Ifd raw, Ifd exif) {
        if (raw.has(33421) && raw.has(33422)) {
            try {
                return DngParser.pattern(raw);
            } catch (MediaException.Malformed exception) {
                return null;
            }
        }
        if (exif == null || !exif.has(41730)) {
            return null;
        }
        var bytes = exif.bytes(41730);
        if (bytes == null || bytes.length < 8) {
            return null;
        }
        var little = exif.reader().little();
        var columns = ByteArrays.u16(bytes, 0, little);
        var rows = ByteArrays.u16(bytes, 2, little);
        if (columns > 16 || rows > 16 || columns * rows == 0 || bytes.length < 4 + columns * rows) {
            columns = ByteArrays.u16(bytes, 0, !little);
            rows = ByteArrays.u16(bytes, 2, !little);
            if (columns > 16 || rows > 16 || columns * rows == 0 || bytes.length < 4 + columns * rows) {
                return null;
            }
        }
        var colors = new ArrayList<CfaColor>();
        for (var index = 0; index < columns * rows; index++) {
            var code = bytes[4 + index] & 0xFF;
            if (code > 6) {
                return null;
            }
            colors.add(CfaColor.of(code));
        }
        return CfaPattern.of(columns, rows, colors);
    }
}
