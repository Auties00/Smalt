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
 * @implNote This implementation's photosites, filter array, levels, crop and as-shot neutral were checked
 * against Adobe DNG Converter, run as a black box, on CC0 sample files. The FFF files, which Hasselblad's
 * software writes, decode to the converter's photosites exactly. For the 3FR files, which the camera writes, the
 * converter applies Hasselblad's calibration of the sensor, a gain and offset per color and position that this
 * decoder does not apply, so its photosites and levels are those of the file, proportional to the converter's.
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
    private static final int PROCESSED_WHITE = 62914;

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
        // TODO: apply the per-color, per-position calibration of the sensor that Adobe DNG Converter applies to the
        //  3FR files; where the file holds it is not identified
        DngParser.profile(tree.first, file.color);
        DngParser.shot(tree.first, raw, file.color);
        file.sensor = sensor;
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
