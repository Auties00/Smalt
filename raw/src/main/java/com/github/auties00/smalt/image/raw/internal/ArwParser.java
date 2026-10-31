/* Ports arw (arw.go, readraw.go: readCrawBlock, readCRAW). Copyright (c) 2018 Sjon Kosse; MIT License. Modified for
   Smalt. */
package com.github.auties00.smalt.image.raw.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.internal.task.ParallelBands;

import java.util.Arrays;
import java.util.Map;
import java.util.Set;

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
 * <p>Uncompressed data stores one sample in each 16-bit word on the 14-bit scale, whatever bit
 * depth the directory states. The DSLR-A900 instead stores its uncompressed data under compression
 * 32767 at one and a half bytes per photosite: 12-bit samples on the 12-bit scale, packed in pairs
 * into three bytes least significant bits first (the first sample is the first byte and the low
 * half of the second, the second sample the high half of the second byte and the third byte); its
 * untagged black level is a quarter of the 14-bit one (128), its white level 4095 and its image
 * the 6048 by 4032 area 12 photosites from the left and 8 from the top.
 *
 * <p>The levels are those of the raw directory's own tags when it has them:
 * {@code BlackLevel} (tag {@code 0x7310}, four values in red, green, green, blue order) and
 * {@code WB_RGGBLevels} (tag {@code 0x7313}, the as-shot multipliers in the same order); the white
 * level is that of the model, which is the {@code WhiteLevel} tag ({@code 0xC61D}) where the file has
 * one, except on the models whose photosites saturate lower, and 16300 on the models without it.
 * Files without the black level tag take the black level of their model, 512 on the 14-bit scale
 * except 800 on the RX10, RX10 III, RX100 to RX100 V and RX0 compacts; files without the white balance
 * tag, whose white balance is in the encrypted {@code SR2} directory, have it estimated from the
 * camera's embedded preview (see {@link PreviewBalance}).
 *
 * @implNote This implementation follows Henry Dietz's description of the cRAW format ("Sony ARW2
 * Compression: Artifacts And Credible Repair", Electronic Imaging 2016) and the block
 * layout of the {@code arw} Go package by Sjon Kosse; the tone curve
 * thresholds are read as quarter values on a 12-bit scale indexed by twice the 11-bit
 * value, which makes the curve span the 14-bit range. Sony's lossless compression (TIFF
 * compression 7) goes through the generic lossless JPEG reader. The levels of the models
 * without level tags and the white levels were derived from the values Adobe DNG Converter, run as
 * a black box, stores for CC0 sample files of every model of the raw.pixls.us archive, and so was the
 * A900's packing, which reproduces the converter's photosites exactly. The variable-length coding of
 * compression 32767 that the DSLR-A200 to A390 write, at neither one nor one and a half bytes per
 * photosite, is not decoded.
 */
final class ArwParser {
    /**
     * The TIFF compression code of Sony's raw data.
     */
    private static final int SONY_COMPRESSION = 32767;

    /**
     * The black level of the models without the black level tag, on the 14-bit scale.
     */
    private static final int BLACK = 512;

    /**
     * The white level of the models without the white level tag, on the 14-bit scale.
     */
    private static final int WHITE = 16300;

    /**
     * The white level of packed 12-bit data without the white level tag.
     */
    private static final int PACKED_WHITE = 4095;

    /**
     * The models without the black level tag whose black level is 800.
     */
    private static final Set<String> BLACK_800 = Set.of("DSC-RX0", "DSC-RX10", "DSC-RX10M3", "DSC-RX100",
            "DSC-RX100M2", "DSC-RX100M3", "DSC-RX100M4", "DSC-RX100M5");

    /**
     * The models whose photosites saturate below the white level their tag states, or whose white
     * level differs from that of the models without the tag; a key with a bit depth applies to the
     * cRAW files of that depth only.
     */
    private static final Map<String, Integer> WHITE_OF_MODEL = Map.ofEntries(
            Map.entry("DSC-HX95", 16300), Map.entry("DSC-HX99", 16300), Map.entry("DSC-RX0M2", 16300),
            Map.entry("DSC-RX100M5A", 16300), Map.entry("DSC-RX100M6", 16300), Map.entry("DSC-RX100M7", 16300),
            Map.entry("DSC-RX100M7A", 16300), Map.entry("ZV-1", 16300), Map.entry("ZV-1M2", 16300),
            Map.entry("ILCE-7M3", 16300), Map.entry("ILCE-7RM4A/12", 15000), Map.entry("DSC-R1", 16383),
            Map.entry("DSLR-A700", 16383));

    /**
     * The black levels of the models without the black level tag whose photosites' levels differ, in
     * the raster order of their 2x2 color filter array.
     */
    private static final Map<String, double[]> BLACK_OF_MODEL = Map.of("DSC-R1", new double[]{514, 511, 512, 513});

    /**
     * The image areas, as x, y, width and height, of the models whose EXIF image size is the whole
     * sensor image.
     */
    private static final Map<String, double[]> CROP_OF_MODEL = Map.of("DSLR-A900", new double[]{12, 8, 6048, 4032});

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
        var packed = false;
        if (compression == SONY_COMPRESSION && total == pixels) {
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
        } else if (compression == SONY_COMPRESSION && total == pixels * 3 / 2 && width % 2 == 0) {
            var offsets = raw.longs(TiffSamples.STRIP_OFFSETS);
            if (offsets == null || offsets.length == 0) {
                throw new MediaException.Malformed("ARW raw image without data");
            }
            var offset = reader.base() + offsets[0];
            var source = reader.source();
            var row = PackedRows.of(PackedRows.Layout.LSB_FIRST, 12);
            sensor.width = width;
            sensor.height = height;
            sensor.bitsPerSample = 12;
            sensor.compression = "uncompressed";
            sensor.reader = (plane, limits) -> PackedRows.read(source, offset, width * 3 / 2, row, plane, limits);
            packed = true;
        } else if (compression == SONY_COMPRESSION) {
            // TODO: decode the variable-length coding of compression 32767 that the DSLR-A200 to A390 write; its only
            //  public descriptions are GPL and LGPL decoders, so it needs a specification or a clean-room derivation
            file.unsupported = "Sony's variable-length ARW coding at " + total + " bytes for " + pixels
                    + " photosites is not supported";
            return;
        } else if (total >= pixels * 2) {
            try {
                TiffSamples.describe(raw, sensor);
            } catch (MediaException.Unsupported exception) {
                sensor.width = width;
                sensor.height = height;
            }
            sensor.bitsPerSample = 14;
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
                        Unpack.words(data, start, data.length, little, 16, plane.integers,
                                (block.y() + row) * plane.width, plane.width);
                    }
                }
            };
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
        levels(raw, file, sensor, compression == SONY_COMPRESSION && !packed ? bits : 0, packed);
        var crop = CROP_OF_MODEL.get(file.model == null ? "" : file.model.strip());
        sensor.defaultCrop = crop != null && crop[0] + crop[2] <= sensor.width && crop[1] + crop[3] <= sensor.height
                ? crop.clone() : centeredCrop(tree.exif, sensor);
        file.sensor = sensor;
    }

    /**
     * Sets the black and white levels and the white balance of a file.
     *
     * @param raw the raw image directory
     * @param file the description to fill
     * @param sensor the sensor description, its color filter array set
     * @param crawBits the bit depth of cRAW data, or {@code 0} for other data
     * @param packed whether the data is packed 12-bit samples on the 12-bit scale, whose untagged levels are a
     * quarter of the 14-bit ones and whose white level is the largest 12-bit value
     */
    private static void levels(Ifd raw, RawFile file, Sensor sensor, int crawBits, boolean packed) {
        var model = file.model == null ? "" : file.model.strip();
        var tagged = raw.longs(0x7310);
        double[] black = null;
        if (tagged != null && tagged.length >= 4) {
            black = SrwParser.cfaOrder(sensor.cfa, new double[]{tagged[0], tagged[1], tagged[2], tagged[3]});
        } else if (BLACK_OF_MODEL.containsKey(model)) {
            black = BLACK_OF_MODEL.get(model).clone();
        }
        if (black != null) {
            sensor.blackRows = 2;
            sensor.blackColumns = 2;
            sensor.black = black;
        } else {
            var untagged = BLACK_800.contains(model) ? 800 : BLACK;
            sensor.black = new double[]{packed ? untagged >> 2 : untagged};
        }
        var white = WHITE_OF_MODEL.get(model + "/" + crawBits);
        if (white == null) {
            white = WHITE_OF_MODEL.get(model);
        }
        if (white == null) {
            var tag = raw.longValue(50717, 0);
            white = tag > 0 && tag < 1 << 16 ? (int) tag : packed ? PACKED_WHITE : WHITE;
        }
        sensor.white = new double[]{white};
        var balance = raw.longs(0x7313);
        if (balance != null && balance.length >= 4 && balance[0] > 0 && balance[1] > 0 && balance[2] > 0
                && balance[3] > 0) {
            var green = (balance[1] + balance[2]) / 2.0;
            file.color.asShotNeutral = new double[]{green / balance[0], 1, green / balance[3]};
        } else {
            sensor.estimateBalance = true;
        }
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
