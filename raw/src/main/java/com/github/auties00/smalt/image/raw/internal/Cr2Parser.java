package com.github.auties00.smalt.image.raw.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.jpeg.internal.LosslessJpeg;
import com.github.auties00.smalt.image.raw.CfaPattern;
import com.github.auties00.smalt.internal.limits.LimitChecks;

/**
 * Parses a Canon CR2 file: a TIFF structure whose fourth directory holds the sensor data as one
 * lossless JPEG stream cut into vertical slices, with the sensor geometry, white balance and
 * levels in the Canon maker note.
 *
 * <p>The lossless JPEG frame declares {@code W} columns of {@code N} interleaved components, so
 * each of its rows carries {@code W * N} samples; the sensor is {@code W * N} photosites wide.
 * The samples fill the sensor slice by slice, each slice top to bottom and each of its rows left to
 * right, the slice widths coming from tag {@code 0xC640} ({@code count}, {@code width},
 * {@code lastWidth}: {@code count} slices of {@code width} then one of {@code lastWidth}). The
 * maker note's {@code SensorInfo} (tag {@code 0x00E0}) gives the image area, its
 * {@code ColorData} (tag {@code 0x4001}) the as-shot white balance levels, the per-channel black
 * level and the white level at offsets that depend on its version.
 *
 * @implNote This implementation follows the CR2 layout as Laurent Clevy's write-up describes it
 * and the maker note tables of the ExifTool tag documentation; it reads no code. The
 * color filter array is the 2x2 Bayer pattern {@code RGGB} at the sensor origin, which
 * holds for every CR2 camera. mRAW and sRAW files (subsampled YCbCr) are converted to
 * linear red, green and blue by {@link CanonSraw}.
 */
final class Cr2Parser {
    /**
     * The tag of the slice layout.
     */
    private static final int SLICES = 0xC640;

    /**
     * The maker note tag of the sensor geometry.
     */
    private static final int SENSOR_INFO = 0x00E0;

    /**
     * The maker note tag of the color data.
     */
    private static final int COLOR_DATA = 0x4001;

    /**
     * Prevents instantiation.
     */
    private Cr2Parser() {
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
        Ifd raw = null;
        for (var ifd : tree.directories) {
            if (ifd.has(SLICES) || (ifd != tree.first && ifd.intValue(TiffSamples.COMPRESSION, 0) == 6
                    && !ifd.has(TiffSamples.IMAGE_WIDTH) && ifd.has(TiffSamples.STRIP_OFFSETS))) {
                raw = ifd;
            }
        }
        if (raw == null) {
            file.unsupported = "the CR2 file holds no raw image";
            return;
        }
        var offsets = raw.longs(TiffSamples.STRIP_OFFSETS);
        var counts = raw.longs(TiffSamples.STRIP_BYTE_COUNTS);
        if (offsets == null || counts == null || offsets.length == 0 || counts.length == 0) {
            throw new MediaException.Malformed("CR2 raw image without data");
        }
        var offset = offsets[0];
        var length = counts[0];
        var head = reader.bytes(offset, Math.min(length, 1 << 16));
        LosslessJpeg probe;
        try {
            probe = new LosslessJpeg(head, 0, head.length);
        } catch (MediaException.Unsupported exception) {
            subsampled(tree, raw, reader, offset, length, head, file);
            return;
        }
        var sensor = new Sensor();
        sensor.width = probe.width() * probe.components();
        sensor.height = probe.height();
        sensor.bitsPerSample = probe.precision();
        sensor.compression = "lossless JPEG";
        sensor.cfa = CfaPattern.bayer("RGGB");
        var slices = raw.longs(SLICES);
        var widths = sliceWidths(slices, sensor.width);
        sensor.reader = (plane, limits) -> read(reader, offset, length, widths, plane, limits);
        makerNote(tree, sensor, file);
        file.sensor = sensor;
    }

    /**
     * Describes the sensor data of an mRAW or sRAW file: linear red, green and blue converted from
     * the subsampled YCbCr stream by {@link CanonSraw}, with the color data's balance levels
     * (index 78, 123 or 128 of {@code ColorData} by version) as the analog balance the camera
     * applied to them. The samples have no black offset, so the white level is the color data's
     * specular white less its black level.
     *
     * @param tree the directories
     * @param raw the raw image directory
     * @param reader the TIFF structure
     * @param offset the offset of the stream
     * @param length the length of the stream
     * @param head the start of the stream
     * @param file the description to fill
     */
    private static void subsampled(TiffTree tree, Ifd raw, TiffReader reader, long offset, long length, byte[] head,
                                   RawFile file) {
        LosslessJpeg probe;
        boolean mraw;
        try {
            probe = new LosslessJpeg(head, 0, head.length, true);
            mraw = CanonSraw.mraw(probe);
        } catch (MediaException.Unsupported exception) {
            file.unsupported = exception.getMessage();
            return;
        }
        var color = colorData(tree);
        var version = color == null ? -1 : (short) color[0];
        var kind = CanonSraw.kind(version);
        if (kind == null) {
            // TODO: find the YCbCr conversion of the mRAW and sRAW files of the color data versions no sample has shown
            file.unsupported = "Canon " + (mraw ? "mRAW" : "sRAW") + " files of color data version " + version
                    + " are not supported";
            return;
        }
        var perUnit = mraw ? 6 : 4;
        var total = (long) (probe.width() / 2) * (probe.height() / (mraw ? 2 : 1)) * perUnit;
        var slices = raw.longs(SLICES);
        var rowSamples = slices != null && slices.length >= 3 && slices[0] >= 0 && slices[0] <= 64 && slices[1] > 0
                && slices[2] > 0 ? slices[0] * slices[1] + slices[2] : (long) probe.width() / 2 * perUnit;
        if (rowSamples % perUnit != 0 || total % rowSamples != 0) {
            throw new MediaException.Malformed("the CR2 slices do not tile the " + (mraw ? "mRAW" : "sRAW") + " data");
        }
        var unitColumns = (int) (rowSamples / perUnit);
        var unitRows = (int) (total / rowSamples);
        var width = unitColumns * 2;
        var height = mraw ? unitRows * 2 : unitRows;
        var widths = sliceWidths(slices, unitColumns * perUnit);
        LimitChecks.checkPicture(file.limits, width, height);
        var sensor = new Sensor();
        sensor.width = width;
        sensor.height = height;
        sensor.samplesPerPixel = 3;
        sensor.bitsPerSample = 16;
        sensor.compression = "lossless JPEG " + (mraw ? "mRAW" : "sRAW");
        var lumaOffset = version == 3 ? 512 : 0;
        sensor.reader = (plane, limits) -> {
            LimitChecks.checkAllocation(limits, length);
            var data = reader.bytes(offset, length);
            var jpeg = new LosslessJpeg(data, 0, data.length, true);
            var layout = new SensorPlane(unitColumns * perUnit, unitRows, 1, false, limits);
            var cursor = new int[]{0, 0, 0, 0};
            jpeg.decodeUnits((row, samples) -> lay(samples, samples.length, widths, cursor, layout));
            CanonSraw.convert(layout.integers, unitColumns, unitRows, mraw, kind, lumaOffset, plane.width, plane.height,
                    plane.integers);
        };
        sensor.white = new double[]{CanonSraw.MAXIMUM, CanonSraw.MAXIMUM, CanonSraw.MAXIMUM};
        if (color != null) {
            var balanceAt = version >= 12 ? 128 : version >= 10 ? 123 : 78;
            if (balanceAt + 4 <= color.length && color[balanceAt] > 0 && color[balanceAt + 1] > 0 && color[balanceAt + 3] > 0) {
                var green = (double) color[balanceAt + 1];
                file.color.analogBalance = new double[]{green / color[balanceAt], 1, green / color[balanceAt + 3]};
            }
            if (color.length > 66 && color[63] > 0 && color[64] > 0 && color[66] > 0) {
                var green = (color[64] + color[65]) / 2.0;
                var analog = file.color.analogBalance != null ? file.color.analogBalance : new double[]{1, 1, 1};
                file.color.asShotNeutral = new double[]{analog[0] * green / color[63], 1, analog[2] * green / color[66]};
            }
            var layoutOf = layout(version, color.length);
            if (layoutOf[2] > 0 && layoutOf[2] < color.length && color[layoutOf[2]] > 0) {
                var black = 0.0;
                if (layoutOf[1] > 0 && layoutOf[1] + 4 <= color.length) {
                    black = (color[layoutOf[1]] + color[layoutOf[1] + 1] + color[layoutOf[1] + 2] + color[layoutOf[1] + 3]) / 4.0;
                }
                var white = Math.clamp(color[layoutOf[2]] - black, CanonSraw.MAXIMUM / 4.0, CanonSraw.MAXIMUM);
                sensor.white = new double[]{white, white, white};
            }
        }
        file.sensor = sensor;
    }

    /**
     * Lays one decoded row of samples out slice by slice.
     *
     * @param samples the decoded row
     * @param count the number of samples
     * @param widths the slice widths
     * @param cursor the slice, row, column and slice origin of the next sample, updated
     * @param plane the plane to fill
     */
    private static void lay(int[] samples, int count, int[] widths, int[] cursor, SensorPlane plane) {
        var out = plane.integers;
        var index = 0;
        while (index < count) {
            var slice = cursor[0];
            if (slice >= widths.length) {
                return;
            }
            var sliceWidth = widths[slice];
            var y = cursor[1];
            var x = cursor[2];
            var run = Math.min(count - index, sliceWidth - x);
            var target = y * plane.width + cursor[3] + x;
            for (var k = 0; k < run; k++) {
                out[target + k] = (short) samples[index + k];
            }
            index += run;
            x += run;
            if (x == sliceWidth) {
                x = 0;
                y++;
                if (y == plane.height) {
                    y = 0;
                    cursor[3] += sliceWidth;
                    cursor[0]++;
                }
            }
            cursor[1] = y;
            cursor[2] = x;
        }
    }

    /**
     * Returns the values of the maker note's {@code ColorData} tag.
     *
     * @param tree the directories
     * @return the values, or {@code null}
     */
    private static long[] colorData(TiffTree tree) {
        if (tree.exif == null) {
            return null;
        }
        var position = tree.exif.valuePosition(TiffTree.MAKER_NOTE);
        if (position < 0) {
            return null;
        }
        try {
            var reader = tree.first.reader();
            var values = reader.directory(position - reader.base()).longs(COLOR_DATA);
            return values != null && values.length >= 70 ? values : null;
        } catch (MediaException.Malformed exception) {
            return null;
        }
    }

    /**
     * Returns the width of every slice.
     *
     * @param slices the slice tag, or {@code null}
     * @param width the sensor width
     * @return the widths, summing to the sensor width
     */
    private static int[] sliceWidths(long[] slices, int width) {
        if (slices == null || slices.length < 3 || slices[0] <= 0 || slices[1] <= 0 || slices[2] <= 0) {
            return new int[]{width};
        }
        var count = slices[0];
        if (count > 64 || count * slices[1] + slices[2] != width) {
            return new int[]{width};
        }
        var widths = new int[(int) count + 1];
        for (var index = 0; index < count; index++) {
            widths[index] = (int) slices[1];
        }
        widths[(int) count] = (int) slices[2];
        return widths;
    }

    /**
     * Decodes the lossless JPEG stream and lays its samples out slice by slice.
     *
     * @param reader the TIFF structure
     * @param offset the offset of the stream
     * @param length the length of the stream
     * @param widths the slice widths
     * @param plane the plane to fill
     * @param limits the resource bounds
     */
    private static void read(TiffReader reader, long offset, long length, int[] widths, SensorPlane plane,
                             MediaLimits limits) {
        LimitChecks.checkAllocation(limits, length);
        var data = reader.bytes(offset, length);
        var jpeg = new LosslessJpeg(data, 0, data.length);
        var cursor = new int[]{0, 0, 0, 0};
        jpeg.decode((row, samples) -> lay(samples, samples.length, widths, cursor, plane));
    }

    /**
     * Reads the sensor geometry, white balance and levels from the Canon maker note.
     *
     * @param tree the directories
     * @param sensor the sensor description to fill
     * @param file the description to fill
     */
    private static void makerNote(TiffTree tree, Sensor sensor, RawFile file) {
        if (tree.exif == null) {
            return;
        }
        var position = tree.exif.valuePosition(TiffTree.MAKER_NOTE);
        if (position < 0) {
            return;
        }
        Ifd note;
        try {
            var reader = tree.first.reader();
            note = reader.directory(position - reader.base());
        } catch (MediaException.Malformed exception) {
            return;
        }
        var info = note.longs(SENSOR_INFO);
        if (info != null && info.length >= 9) {
            var left = (int) info[5];
            var top = (int) info[6];
            var right = (int) info[7];
            var bottom = (int) info[8];
            if (left >= 0 && top >= 0 && right > left && bottom > top && right < sensor.width && bottom < sensor.height) {
                sensor.activeArea = new int[]{top, left, bottom + 1, right + 1};
            }
        }
        colorData(note, sensor, file);
    }

    /**
     * Reads the white balance and the levels from the {@code ColorData} tag of a Canon maker note.
     *
     * @param note the maker note directory
     * @param sensor the sensor description to fill, its active area already set
     * @param file the description to fill
     */
    static void colorData(Ifd note, Sensor sensor, RawFile file) {
        var color = note.longs(COLOR_DATA);
        if (color == null || color.length < 70) {
            return;
        }
        var version = (short) color[0];
        var layout = layout(version, color.length);
        if (layout[0] > 0 && layout[0] + 4 <= color.length) {
            var r = color[layout[0]];
            var g1 = color[layout[0] + 1];
            var g2 = color[layout[0] + 2];
            var b = color[layout[0] + 3];
            if (r > 0 && g1 > 0 && g2 > 0 && b > 0) {
                var g = (g1 + g2) / 2.0;
                file.color.asShotNeutral = new double[]{g / r, 1.0, g / b};
            }
        }
        if (layout[1] > 0 && layout[1] + 4 <= color.length) {
            var black = new double[4];
            var valid = true;
            for (var index = 0; index < 4; index++) {
                black[index] = color[layout[1] + index];
                valid &= black[index] > 0 && black[index] < 1 << 14;
            }
            if (valid) {
                sensor.blackRows = 2;
                sensor.blackColumns = 2;
                sensor.black = shiftedBlack(black, sensor);
            }
        }
        if (layout[2] > 0 && layout[2] < color.length) {
            var white = color[layout[2]];
            if (white > (1L << (sensor.bitsPerSample - 2)) && white < (1L << sensor.bitsPerSample)) {
                sensor.white = new double[]{white};
            }
        }
    }

    /**
     * Rearranges a black level pattern given at the sensor origin so it starts at the active
     * area's origin, as the linearization expects.
     *
     * @param black the 2x2 black levels at the sensor origin
     * @param sensor the sensor description
     * @return the 2x2 black levels at the active area's origin
     */
    private static double[] shiftedBlack(double[] black, Sensor sensor) {
        var area = sensor.active();
        var result = new double[4];
        for (var row = 0; row < 2; row++) {
            for (var column = 0; column < 2; column++) {
                result[row * 2 + column] = black[((row + area[0]) & 1) * 2 + ((column + area[1]) & 1)];
            }
        }
        return result;
    }

    /**
     * Returns where the {@code ColorData} of a version holds the as-shot white balance levels, the
     * per-channel black levels and the specular white level.
     *
     * @param version the {@code ColorDataVersion}
     * @param count the number of values of the tag
     * @return the three indices, {@code 0} where the version has none
     */
    private static int[] layout(int version, int count) {
        return switch (version) {
            case 1 -> new int[]{63, 196, 0};
            case 2, 3, 4, 5, 6, 7, 9 -> new int[]{63, count > 715 ? 715 : 692, count > 715 ? 720 : 697};
            case 10 -> count >= 1300 ? new int[]{63, 504, 509} : new int[]{63, 479, 484};
            case 11 -> new int[]{63, 728, 733};
            case 12, 13, 15 -> new int[]{63, 778, 783};
            case 14 -> new int[]{63, 556, 561};
            case 16, 17, 18, 19 -> new int[]{71, 329, 797};
            case 32, 33 -> new int[]{85, 343, 811};
            case 34, 48 -> new int[]{105, 363, 641};
            case 64, 65, 66 -> new int[]{105, 383, 661};
            default -> count == 582 ? new int[]{25, 0, 0} : count == 653 ? new int[]{34, 0, 0} : new int[]{0, 0, 0};
        };
    }
}
