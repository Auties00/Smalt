/* Ports arw (arw.go, readraw.go: readCrawBlock, readCRAW). Copyright (c) 2018 Sjon Kosse; MIT License. Modified for
   Smalt. */
package com.github.auties00.smalt.image.raw.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.internal.task.ParallelBands;

import java.util.Arrays;

/**
 * Parses a Sony ARW file: a TIFF/EP structure whose color filter array directory holds the sensor
 * data, either as 16-bit words or with Sony's lossy "cRAW" compression (TIFF compression 32767 at
 * one byte per photosite).
 *
 * <p>cRAW codes each row in groups of 32 photosites, as two 16-byte blocks: the first holds the 16
 * even photosites of the group, the second the 16 odd ones. A block is a 128-bit little-endian
 * field: an 11-bit maximum, an 11-bit minimum, the 4-bit positions of the maximum and the minimum,
 * and 14 seven-bit deltas for the other photosites in order. Deltas are scaled by the smallest
 * power of two, up to 16, for which 128 steps cover the range between minimum and maximum. The
 * 11-bit values are tone-mapped to 14-bit linear values by a five-segment curve whose steps
 * double at the thresholds tag {@code 0x7010} records.
 *
 * <p>The black level, white level and white balance Sony stores in its encrypted {@code SR2}
 * directory are not read: black is 512 and white 16383 on the 14-bit scale (128 and 4095 for 12-bit
 * uncompressed data), and the as-shot white balance is estimated from the camera's embedded
 * preview (see {@link PreviewBalance}).
 *
 * @implNote This implementation follows Henry Dietz's description of the cRAW format ("Sony ARW2
 * Compression: Artifacts And Credible Repair", Electronic Imaging 2016) and the block
 * layout of the {@code arw} Go package by Sjon Kosse; the tone curve
 * thresholds are read as quarter values on a 12-bit scale indexed by twice the 11-bit
 * value, which makes the curve span the 14-bit range. Sony's lossless compression (TIFF
 * compression 7) goes through the generic lossless JPEG reader.
 */
final class ArwParser {
    /**
     * The TIFF compression code of Sony's raw data.
     */
    private static final int SONY_COMPRESSION = 32767;

    /**
     * Prevents instantiation.
     */
    private ArwParser() {
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
            file.unsupported = "the ARW file holds no raw image";
            return;
        }
        var sensor = new Sensor();
        var width = raw.intValue(TiffSamples.IMAGE_WIDTH, 0);
        var height = raw.intValue(TiffSamples.IMAGE_LENGTH, 0);
        var bits = raw.intValue(TiffSamples.BITS_PER_SAMPLE, 12);
        var compression = raw.intValue(TiffSamples.COMPRESSION, 1);
        if (width <= 0 || height <= 0) {
            throw new MediaException.Malformed("invalid ARW raw image of " + width + "x" + height);
        }
        var counts = raw.longs(TiffSamples.STRIP_BYTE_COUNTS);
        var total = 0L;
        if (counts != null) {
            for (var count : counts) {
                total += count;
            }
        }
        var pixels = (long) width * height;
        var curve = raw.longs(0x7010);
        if (compression == SONY_COMPRESSION && total >= pixels && total < pixels * 3 / 2) {
            var offsets = raw.longs(TiffSamples.STRIP_OFFSETS);
            if (offsets == null || offsets.length == 0) {
                throw new MediaException.Malformed("ARW raw image without data");
            }
            var offset = reader.base() + offsets[0];
            var table = toneCurve(curve);
            sensor.width = width;
            sensor.height = height;
            sensor.bitsPerSample = 14;
            sensor.compression = "Sony cRAW";
            sensor.reader = (plane, limits) -> craw(reader, offset, plane, table, limits);
            sensor.black = new double[]{512};
            sensor.white = new double[]{table[table.length - 1]};
        } else if (total >= pixels * 2) {
            var stored = Math.min(bits, 16);
            try {
                TiffSamples.describe(raw, sensor);
            } catch (MediaException.Unsupported exception) {
                sensor.width = width;
                sensor.height = height;
            }
            sensor.bitsPerSample = stored;
            var blocks = TiffSamples.blocks(raw, width, height);
            var source = reader.source();
            var little = reader.little();
            sensor.compression = "uncompressed";
            sensor.reader = (plane, limits) -> {
                for (var block : blocks) {
                    LimitChecks.checkAllocation(limits, block.length());
                    var data = new byte[(int) Math.min(block.length(), Integer.MAX_VALUE - 16)];
                    source.readFully(block.offset(), data, 0, data.length);
                    var rows = Math.min(block.height(), plane.height - block.y());
                    for (var row = 0; row < rows; row++) {
                        var start = row * plane.width * 2;
                        if (start >= data.length) {
                            break;
                        }
                        Unpack.words(data, start, data.length, little, stored, plane.integers,
                                (block.y() + row) * plane.width, plane.width);
                    }
                }
            };
            var scale = stored >= 14 ? 4 : 1;
            sensor.black = new double[]{128 * scale};
            sensor.white = new double[]{(1 << stored) - 1};
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
            file.unsupported = "the ARW raw image has no CFA pattern";
            return;
        }
        sensor.estimateBalance = true;
        sensor.defaultCrop = centeredCrop(tree.exif, sensor);
        file.sensor = sensor;
    }

    /**
     * Returns the default crop of a Sony file: the image size the EXIF directory records, centered
     * on the sensor image on even coordinates so the color filter array keeps its phase.
     *
     * @param exif the EXIF directory, or {@code null}
     * @param sensor the sensor description
     * @return the crop as x, y, width and height, or {@code null} when the EXIF size is absent or
     * not slightly smaller than the sensor image
     */
    static double[] centeredCrop(Ifd exif, Sensor sensor) {
        if (exif == null) {
            return null;
        }
        var width = exif.intValue(40962, 0);
        var height = exif.intValue(40963, 0);
        if (width <= 0 || height <= 0 || width > sensor.width || height > sensor.height
                || width < sensor.width * 0.9 || height < sensor.height * 0.9) {
            return null;
        }
        var left = ((sensor.width - width) / 2) & ~1;
        var top = ((sensor.height - height) / 2) & ~1;
        return new double[]{left, top, width, height};
    }

    /**
     * Builds the cRAW tone curve, mapping twice an 11-bit value to a 14-bit linear value.
     *
     * @param thresholds the four thresholds of tag {@code 0x7010}, or {@code null} for Sony's
     * usual ones
     * @return the curve, of 4096 entries
     */
    static int[] toneCurve(long[] thresholds) {
        var knots = new int[]{0, 2000, 2600, 3225, 3525, 4095};
        if (thresholds != null && thresholds.length >= 4) {
            var valid = true;
            for (var index = 0; index < 4; index++) {
                var knot = (int) (thresholds[index] >> 2) & 0xFFF;
                valid &= knot > knots[index];
                knots[index + 1] = knot;
            }
            if (!valid || knots[4] >= 4095) {
                knots = new int[]{0, 2000, 2600, 3225, 3525, 4095};
            }
        }
        var curve = new int[4096];
        var value = 0;
        for (var segment = 0; segment < 5; segment++) {
            for (var index = knots[segment] + 1; index <= knots[segment + 1]; index++) {
                value += 1 << segment;
                curve[index] = value;
            }
        }
        for (var index = 0; index < curve.length; index++) {
            curve[index] = Math.min(curve[index], 16383);
        }
        return curve;
    }

    /**
     * Decodes cRAW sensor data.
     *
     * @param reader the TIFF structure
     * @param offset the absolute offset of the data
     * @param plane the plane to fill
     * @param curve the tone curve
     * @param limits the resource bounds
     */
    private static void craw(TiffReader reader, long offset, SensorPlane plane, int[] curve, MediaLimits limits) {
        var width = plane.width;
        LimitChecks.checkAllocation(limits, width);
        var groups = width / 32;
        var bands = ParallelBands.bands(plane.height, (long) width * plane.height);
        ParallelBands.run(bands, band -> {
            var first = ParallelBands.start(band, bands, plane.height);
            var last = ParallelBands.start(band + 1, bands, plane.height);
            var row = new byte[width];
            var values = new int[16];
            var out = plane.integers;
            for (var y = first; y < last; y++) {
                var read = reader.readAvailable(offset + (long) y * width, row, 0, width);
                if (read < width) {
                    Arrays.fill(row, Math.max(read, 0), width, (byte) 0);
                }
                var line = y * width;
                for (var group = 0; group < groups; group++) {
                    var x = group * 32;
                    for (var half = 0; half < 2; half++) {
                        block(row, x + 16 * half, values);
                        for (var index = 0; index < 16; index++) {
                            out[line + x + 2 * index + half] = (short) curve[values[index] << 1];
                        }
                    }
                }
            }
        });
    }

    /**
     * Decodes one 16-byte cRAW block.
     *
     * @param data the bytes
     * @param start the index of the block
     * @param values receives the 16 values, 0 to 2047
     */
    static void block(byte[] data, int start, int[] values) {
        var low = 0L;
        var high = 0L;
        for (var index = 7; index >= 0; index--) {
            low = (low << 8) | (data[start + index] & 0xFF);
            high = (high << 8) | (data[start + 8 + index] & 0xFF);
        }
        var max = (int) (low & 0x7FF);
        var min = (int) ((low >>> 11) & 0x7FF);
        var maxIndex = (int) ((low >>> 22) & 0xF);
        var minIndex = (int) ((low >>> 26) & 0xF);
        var shift = 0;
        while (shift < 4 && (0x80 << shift) <= max - min) {
            shift++;
        }
        var bit = 30;
        for (var index = 0; index < 16; index++) {
            if (index == maxIndex) {
                values[index] = max;
            } else if (index == minIndex) {
                values[index] = min;
            } else {
                long field;
                if (bit + 7 <= 64) {
                    field = low >>> bit;
                } else if (bit >= 64) {
                    field = high >>> (bit - 64);
                } else {
                    field = (low >>> bit) | (high << (64 - bit));
                }
                var delta = (int) (field & 0x7F);
                values[index] = Math.min(min + (delta << shift), 0x7FF);
                bit += 7;
            }
        }
    }
}
