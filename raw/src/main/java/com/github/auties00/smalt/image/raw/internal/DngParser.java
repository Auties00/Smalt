/* Ports the Digital Negative (DNG) Specification 1.7.1.0, Adobe. This product includes DNG technology under license
   by Adobe. */
package com.github.auties00.smalt.image.raw.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.raw.CfaColor;
import com.github.auties00.smalt.image.raw.CfaLayout;
import com.github.auties00.smalt.image.raw.CfaPattern;
import com.github.auties00.smalt.internal.io.ByteArrays;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses a DNG file (Adobe DNG specification 1.7.1): the main raw image among the directories, its
 * layout, levels and opcode lists, the enhanced image, the reduced-resolution linear raw previews,
 * the semantic masks, and the camera profiles.
 *
 * <p>The main image is the first directory, in the order {@link TiffTree} visits them, whose
 * {@code NewSubFileType} is 0 and whose photometric interpretation is a color filter array
 * (32803) or linear raw (34892). The primary camera profile and the shot's color tags are read
 * from the first directory, the extra camera profiles from the directories
 * {@code ExtraCameraProfiles} points to; the raw-image tags from the main image's directory.
 *
 * <p>A color filter array image of two samples per pixel holds two readings of each photosite, as
 * Adobe DNG Converter writes the Fujifilm sensors of two images (the specification leaves the
 * meaning of the samples open): with a rectangular layout scaled up by at least
 * {@value #DIAGONAL_SCALE} in both directions, the two samples are the photosites of a diagonal pair
 * and become one staggered image; otherwise they read the same light and {@link DualSamples} merges
 * them before rendering.
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
     * The photometric interpretation of a semantic mask (DNG 1.6).
     */
    static final int PHOTOMETRIC_MASK = 52527;

    /**
     * The subfile type of an enhanced image (DNG 1.5).
     */
    static final int NEW_SUBFILE_ENHANCED = 16;

    /**
     * The subfile type of a semantic mask (DNG 1.6).
     */
    static final int NEW_SUBFILE_SEMANTIC_MASK = 65540;

    /**
     * The most extra camera profiles read.
     */
    private static final int MAX_PROFILES = 64;

    /**
     * The default scale, in both directions, from which the two samples of a rectangular CFA image are the two
     * photosites of a diagonal pair: the converter scales such images by about the square root of 2.
     */
    static final double DIAGONAL_SCALE = 1.3;

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
        file.uniqueCameraModel = unique;
        if (file.model == null) {
            file.model = unique;
        }
        Ifd raw = null;
        Ifd enhanced = null;
        var proxies = new ArrayList<Ifd>();
        for (var ifd : tree.directories) {
            var photometric = ifd.intValue(TiffSamples.PHOTOMETRIC, 0);
            var type = ifd.longValue(TiffTree.NEW_SUBFILE_TYPE, 0);
            if (type == 0 && raw == null && (photometric == PHOTOMETRIC_CFA || photometric == PHOTOMETRIC_LINEAR_RAW)) {
                raw = ifd;
            } else if (type == NEW_SUBFILE_ENHANCED && enhanced == null && photometric == PHOTOMETRIC_LINEAR_RAW) {
                enhanced = ifd;
            } else if (type == 1 && photometric == PHOTOMETRIC_LINEAR_RAW) {
                proxies.add(ifd);
            } else if (type == NEW_SUBFILE_SEMANTIC_MASK && photometric == PHOTOMETRIC_MASK) {
                var mask = SemanticMask.of(ifd);
                if (mask != null) {
                    file.masks.add(mask);
                }
            }
        }
        profile(first, file.color);
        shot(first, raw, file.color);
        extraProfiles(first, file);
        file.asShotProfile = utf8(first, 50934);
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
            if (sensor.samplesPerPixel != 1 && sensor.samplesPerPixel != 2) {
                throw new MediaException.Malformed("CFA image with " + sensor.samplesPerPixel + " samples per pixel");
            }
            var layout = raw.intValue(50711, 1);
            if (layout < 1 || layout > 9) {
                file.unsupported = "DNG CFA layout " + layout + " is not defined";
                return;
            }
            sensor.layout = CfaLayout.of(layout);
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
        if (sensor.samplesPerPixel == 2 && sensor.cfa != null && sensor.layout == CfaLayout.RECTANGULAR
                && sensor.scaleH >= DIAGONAL_SCALE && sensor.scaleV >= DIAGONAL_SCALE) {
            var problem = diagonalPairs(sensor);
            if (problem != null) {
                file.unsupported = problem;
                return;
            }
        }
        var userCrop = raw.doubles(51125);
        sensor.userCrop = userCrop(userCrop != null ? userCrop : first.doubles(51125));
        copyTags(raw, DngTag.RAW, sensor.tags);
        copyTags(first, DngTag.FIRST, file.firstTags);
        copyTags(first, DngTag.PROFILE, file.profileTags);
        file.sensor = sensor;
        if (enhanced != null) {
            enhanced(enhanced, sensor, file);
        }
        for (var proxy : proxies) {
            proxy(proxy, sensor, file);
        }
    }

    /**
     * Turns a CFA image of two samples per pixel whose samples are the two photosites of a diagonal pair (the EXR HR
     * files of Adobe DNG Converter: a rectangular layout scaled up by about the square root of 2 in both directions)
     * into one staggered image of twice the width: sample 1 of each pixel in the even columns and sample 0, half a
     * row down, in the odd ones ({@link CfaLayout#STAGGERED_A}). The reader, the filter array, the active area, the
     * crop, the scale and the black levels are converted; opcode list 1 applies to the pairs before they are
     * interleaved, and opcode list 2 to the linear values of the pairs, two adjacent columns of the staggered image.
     *
     * @param sensor the sensor description, filled from the directory; changed in place
     * @return why the image cannot be converted, or {@code null} when it was
     */
    private static String diagonalPairs(Sensor sensor) {
        if (sensor.white != null && sensor.white[0] != sensor.white[1]) {
            return "two-sample CFA images of diagonal pairs with different white levels are not supported";
        }
        var pairs = new Sensor();
        pairs.width = sensor.width;
        pairs.height = sensor.height;
        pairs.samplesPerPixel = 2;
        pairs.bitsPerSample = sensor.bitsPerSample;
        pairs.wide = sensor.wide;
        pairs.floating = sensor.floating;
        pairs.cfa = sensor.cfa;
        pairs.activeArea = sensor.activeArea;
        var opcodes = sensor.opcodes1;
        var inner = sensor.reader;
        sensor.reader = new Sensor.Reader() {
            @Override
            public void read(SensorPlane plane, MediaLimits limits) {
                read(plane, limits, false);
            }

            @Override
            public void read(SensorPlane plane, MediaLimits limits, boolean corrections) {
                var source = SensorPlane.of(pairs, limits);
                inner.read(source, limits, corrections);
                if (corrections && opcodes != null) {
                    Opcodes.applyToSensor(pairs, source, opcodes);
                }
                var count = pairs.width * pairs.height;
                for (var index = 0; index < count; index++) {
                    for (var sample = 0; sample < 2; sample++) {
                        var from = index * 2 + sample;
                        var to = index * 2 + 1 - sample;
                        if (source.integers != null) {
                            plane.integers[to] = source.integers[from];
                        } else if (source.wide != null) {
                            plane.wide[to] = source.wide[from];
                        } else {
                            plane.floats[to] = source.floats[from];
                        }
                    }
                }
            }
        };
        sensor.opcodes1 = null;
        sensor.pairedOpcodes2 = sensor.opcodes2 != null;
        sensor.width = 2 * pairs.width;
        sensor.samplesPerPixel = 1;
        sensor.cfa = DualSamples.interleaved(pairs.cfa);
        sensor.layout = CfaLayout.STAGGERED_A;
        if (sensor.activeArea != null) {
            var area = sensor.activeArea;
            sensor.activeArea = new int[]{area[0], 2 * area[1], area[2], 2 * area[3]};
        }
        if (sensor.defaultCrop != null) {
            var crop = sensor.defaultCrop;
            sensor.defaultCrop = new double[]{2 * crop[0], crop[1], 2 * crop[2], crop[3]};
        }
        sensor.scaleH /= 2;
        if (sensor.black != null) {
            var columns = sensor.blackColumns;
            var black = new double[sensor.blackRows * columns * 2];
            for (var row = 0; row < sensor.blackRows; row++) {
                for (var column = 0; column < 2 * columns; column++) {
                    black[row * 2 * columns + column] = sensor.black[(row * columns + column / 2) * 2 + 1 - column % 2];
                }
            }
            sensor.blackColumns = 2 * columns;
            sensor.black = black;
        }
        if (sensor.blackDeltaH != null) {
            var delta = new double[2 * sensor.blackDeltaH.length];
            for (var index = 0; index < delta.length; index++) {
                delta[index] = sensor.blackDeltaH[index / 2];
            }
            sensor.blackDeltaH = delta;
        }
        if (sensor.white != null) {
            sensor.white = new double[]{sensor.white[0]};
        }
        return null;
    }

    /**
     * Describes the enhanced image of a DNG file: demosaiced linear reference values covering the
     * raw image's active area, possibly at another resolution. Without its own crop and scale it
     * takes the raw image's, scaled to its size, and, at the raw image's size, the raw image's opcode
     * list 3 when it has none. An enhanced image whose data cannot be described is left out.
     *
     * @param ifd the enhanced image's directory
     * @param raw the raw image
     * @param file the description to fill
     */
    private static void enhanced(Ifd ifd, Sensor raw, RawFile file) {
        var sensor = new Sensor();
        try {
            sensor.reader = TiffSamples.describe(ifd, sensor);
        } catch (MediaException.Unsupported | MediaException.Malformed | MediaException.LimitExceeded _) {
            return;
        }
        levels(ifd, sensor);
        copyTags(ifd, DngTag.RAW, sensor.tags);
        var area = raw.active();
        var own = sensor.active();
        var factorX = (double) (own[3] - own[1]) / (area[3] - area[1]);
        var factorY = (double) (own[2] - own[0]) / (area[2] - area[0]);
        if (sensor.defaultCrop == null && raw.defaultCrop != null) {
            var crop = raw.defaultCrop;
            sensor.defaultCrop = new double[]{crop[0] * factorX, crop[1] * factorY, crop[2] * factorX, crop[3] * factorY};
        }
        if (!ifd.has(50718)) {
            sensor.scaleH = raw.scaleH;
            sensor.scaleV = raw.scaleV;
        }
        if (sensor.opcodes3 == null && Math.abs(factorX - 1) < 1e-9 && Math.abs(factorY - 1) < 1e-9) {
            sensor.opcodes3 = raw.opcodes3;
        }
        var crop = ifd.doubles(51125);
        sensor.userCrop = crop != null ? userCrop(crop) : raw.userCrop;
        file.enhanced = sensor;
    }

    /**
     * Describes a reduced-resolution linear raw preview: an image of the raw image's default crop,
     * whose values divided by its {@code RawToPreviewGain} are linear reference values. A preview
     * whose data cannot be decoded is left out.
     *
     * @param ifd the preview's directory
     * @param raw the raw image
     * @param file the description to fill
     */
    private static void proxy(Ifd ifd, Sensor raw, RawFile file) {
        var sensor = new Sensor();
        try {
            sensor.reader = TiffSamples.describe(ifd, sensor);
        } catch (MediaException.Unsupported | MediaException.Malformed | MediaException.LimitExceeded _) {
            return;
        }
        if (sensor.samplesPerPixel != 3 && sensor.samplesPerPixel != 1) {
            return;
        }
        levels(ifd, sensor);
        sensor.defaultCrop = null;
        sensor.scaleH = 1;
        sensor.scaleV = 1;
        var gain = ifd.doubleValue(51112, 1);
        sensor.gain = gain > 0 && Double.isFinite(gain) ? gain : 1;
        var area = raw.active();
        var width = area[3] - area[1];
        var height = area[2] - area[0];
        var crop = raw.defaultCrop != null ? raw.defaultCrop : new double[]{0, 0, width, height};
        sensor.frame = new double[]{crop[0] / width, crop[1] / height, crop[2] / width, crop[3] / height};
        sensor.userCrop = raw.userCrop;
        file.proxies.add(sensor);
    }

    /**
     * Collects the entries of a directory that a converted DNG file carries over.
     *
     * @param ifd the directory
     * @param tags the tags to collect
     * @param into the list to add the entries to
     */
    private static void copyTags(Ifd ifd, int[] tags, List<DngTag> into) {
        for (var tag : tags) {
            var entry = DngTag.of(ifd, tag);
            if (entry != null) {
                into.add(entry);
            }
        }
    }

    /**
     * Validates a {@code DefaultUserCrop}.
     *
     * @param crop the top, left, bottom and right relative to the default crop, or {@code null}
     * @return the crop, or {@code null} when absent, invalid or the whole default crop
     */
    static double[] userCrop(double[] crop) {
        if (crop == null || crop.length < 4) {
            return null;
        }
        for (var index = 0; index < 4; index++) {
            if (!Double.isFinite(crop[index])) {
                return null;
            }
        }
        if (!(crop[0] >= 0 && crop[0] < crop[2] && crop[2] <= 1 && crop[1] >= 0 && crop[1] < crop[3] && crop[3] <= 1)) {
            return null;
        }
        if (crop[0] == 0 && crop[1] == 0 && crop[2] == 1 && crop[3] == 1) {
            return null;
        }
        return new double[]{crop[0], crop[1], crop[2], crop[3]};
    }

    /**
     * Reads the extra camera profiles of the first directory ({@code ExtraCameraProfiles}); a
     * profile that cannot be read is left out.
     *
     * @param first the first directory
     * @param file the description to fill
     */
    private static void extraProfiles(Ifd first, RawFile file) {
        var offsets = first.longs(50933);
        if (offsets == null) {
            return;
        }
        var reader = first.reader();
        for (var index = 0; index < Math.min(offsets.length, MAX_PROFILES); index++) {
            try {
                var base = reader.base() + offsets[index];
                var profileReader = TiffReader.open(reader.source(), base, reader.limits());
                if (profileReader.u16(base + 2) != 0x4352) {
                    continue;
                }
                var ifd = profileReader.directory(profileReader.firstDirectory());
                var profile = new ColorCalibration();
                profile(ifd, profile);
                file.extraProfiles.add(profile);
            } catch (MediaException.Malformed exception) {
                // a profile that cannot be read is left out
            }
        }
    }

    /**
     * Reads a UTF-8 string tag.
     *
     * @param ifd the directory
     * @param tag the tag number
     * @return the string up to its first NUL, or {@code null} when the tag is absent
     */
    private static String utf8(Ifd ifd, int tag) {
        var data = ifd.bytes(tag);
        if (data == null) {
            return null;
        }
        var end = 0;
        while (end < data.length && data[end] != 0) {
            end++;
        }
        return new String(data, 0, end, StandardCharsets.UTF_8);
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
     * lists of an image.
     *
     * @param raw the image's directory
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
     * Reads the shot's color tags of the first directory, which hold for every camera profile:
     * analog balance, white balance, baseline exposure, camera calibration and its signature, and
     * the gain table map of the raw directory ({@code ProfileGainTableMap}, which the specification
     * also allows in the first directory) when the profile has no {@code ProfileGainTableMap2}.
     *
     * @param first the first directory
     * @param raw the raw image's directory, or {@code null}
     * @param color the color description to fill, its profile already read by {@link #profile}
     */
    static void shot(Ifd first, Ifd raw, ColorCalibration color) {
        int[] calibrationTags = {50723, 50724, 52530};
        color.cameraSignature = first.string(50931);
        for (var index = 0; index < 3; index++) {
            color.calibrationTags[index] = matrix(first.doubles(calibrationTags[index]));
        }
        color.keepCalibration();
        color.analogBalance = first.doubles(50727);
        color.asShotNeutral = first.doubles(50728);
        color.asShotWhiteXy = first.doubles(50729);
        color.baselineExposure = first.doubleValue(50730, 0) + color.exposureOffset;
        // some cameras write the profile hint in the raw directory
        if (raw != null && !first.has(51110) && raw.has(51110)) {
            color.blackRender = raw.intValue(51110, 0) == 1 ? 1 : 0;
        }
        if (color.gainTableMap == null) {
            var map = raw != null ? raw.bytes(52525) : null;
            var data = map != null ? map : first.bytes(52525);
            color.gainTableMap = GainTableMap.parse(data, false);
            color.gainTableMapData = color.gainTableMap != null ? data : null;
            color.gainTableMapSecond = false;
        }
    }

    /**
     * Reads a camera profile: from the first directory (the primary profile) or from a camera
     * profile directory.
     *
     * @param first the directory holding the profile
     * @param color the color description to fill
     */
    static void profile(Ifd first, ColorCalibration color) {
        int[] matrixTags = {50721, 50722, 52531};
        int[] forwardTags = {50964, 50965, 52532};
        int[] illuminantTags = {50778, 50779, 52529};
        int[] hueSatTags = {50938, 50939, 52537};
        color.profileSignature = first.string(50932);
        color.profileName = utf8(first, 50936);
        for (var index = 0; index < 3; index++) {
            color.colorMatrix[index] = matrix(first.doubles(matrixTags[index]));
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
        color.exposureOffset = first.doubleValue(51109, 0);
        color.blackRender = first.intValue(51110, 0) == 1 ? 1 : 0;
        var gains = first.bytes(52544);
        color.gainTableMap = GainTableMap.parse(gains, true);
        color.gainTableMapData = color.gainTableMap != null ? gains : null;
        color.gainTableMapSecond = color.gainTableMap != null;
        var tables = first.bytes(52543);
        color.rgbTables = RgbTables.parse(tables);
        color.rgbTablesData = color.rgbTables != null ? tables : null;
        // TODO: read ProfileDynamicRange (52551) and render high-dynamic-range profiles over their extended
        // range; their tables and curves apply as those of a standard-range profile
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
