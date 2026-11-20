package com.github.auties00.smalt.image.raw.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.raw.CfaPattern;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.internal.task.ParallelBands;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Parses a Phase One IIQ file, the format of Phase One's digital backs and of Leaf's Credo backs: a little-endian
 * TIFF structure with Phase One's block right after the header (the bytes {@code IIII} and {@code CwaR}), which holds
 * the sensor data and its metadata; {@link IiqBlock} reads it.
 *
 * <p>The TIFF directories give the camera, the capture settings and the EXIF metadata; the block gives the sensor's
 * size, the image inside it (a red photosite at its top-left corner), the as-shot white balance, the sensor data and
 * its calibration. {@link IiqDecoder} decodes the sensor data and {@link IiqCalibration} calibrates it.
 *
 * @implNote This implementation was derived clean-room from CC0 sample files, with Adobe DNG Converter run as a black
 * box on them and on copies with changed block entries or sensor data.
 */
final class IiqParser {
    /**
     * The signature of Phase One's block, at byte 8 of the file.
     */
    private static final byte[] SIGNATURE = {'I', 'I', 'I', 'I', 'C', 'w', 'a', 'R'};

    /**
     * The compressions: uncompressed; IIQ L; tone-coded IIQ S; the IIQ S of the newer backs; 16-bit IIQ L.
     */
    private static final int UNCOMPRESSED = 0, LONG = 3, TONE_CODED = 5, SHORT = 6, LONG_16 = 8;

    /**
     * The kinds of the calibration's defect records: a photosite; a whole column, in two forms. The records of kind 138
     * (a column, a row and a count, outside the image on the binned files that have them) are left alone, as Adobe DNG
     * Converter leaves them even inside the image.
     */
    private static final int DEFECT_POINT = 129, DEFECT_COLUMN = 131, DEFECT_COLUMN_OTHER = 137;

    /**
     * The identifier of DNG's {@code FixBadPixelsList} opcode.
     */
    private static final int FIX_BAD_PIXELS_LIST = 5;

    /**
     * The white level of the calibrated photosites.
     */
    private static final int WHITE = 65535;

    /**
     * The white level of the IQ180's calibrated photosites: its photosites saturate at 65024 before the black level
     * of 1024 is subtracted. Adobe DNG Converter stores them over a black level of 1028, scaled so that 64000 reaches
     * 65535.
     */
    private static final int IQ180_WHITE = 64000;

    /**
     * The scale of the tone curve.
     */
    private static final double TONE_SCALE = 16383.215;

    /**
     * The values of the numbers of tone-coded sensor data: number {@code n} stands for
     * {@code 4 * round(16383.215 * (n / 255)^2)}; the scale reproduces the values Adobe DNG Converter gives every
     * number.
     */
    private static final int[] TONE_CURVE = toneCurve();

    /**
     * Prevents instantiation.
     */
    private IiqParser() {
        throw new AssertionError();
    }

    /**
     * {@return whether the first bytes of a TIFF file carry Phase One's block}
     *
     * @param head the first bytes of the file
     * @param length the number of bytes of {@code head} read from the file
     */
    static boolean matches(byte[] head, int length) {
        if (length < 8 + SIGNATURE.length) {
            return false;
        }
        for (var index = 0; index < SIGNATURE.length; index++) {
            if (head[8 + index] != SIGNATURE[index]) {
                return false;
            }
        }
        return true;
    }

    /**
     * Parses the file.
     *
     * @param reader the TIFF structure
     * @param file the description to fill
     */
    static void parse(TiffReader reader, RawFile file) {
        new TiffTree(reader).describe(file);
        IiqBlock block;
        try {
            block = new IiqBlock(reader);
        } catch (MediaException.Malformed exception) {
            file.unsupported = "the Phase One block cannot be read: " + exception.getMessage();
            return;
        }
        if (!block.has(IiqBlock.WIDTH) || !block.has(IiqBlock.DATA)) {
            file.unsupported = "the sensor data of this Phase One IIQ file is not supported";
            return;
        }
        int width = block.value(IiqBlock.WIDTH, 0), height = block.value(IiqBlock.HEIGHT, 0);
        int left = block.value(IiqBlock.LEFT, 0), top = block.value(IiqBlock.TOP, 0);
        int imageWidth = block.value(IiqBlock.IMAGE_WIDTH, 0), imageHeight = block.value(IiqBlock.IMAGE_HEIGHT, 0);
        if (width <= 0 || height <= 0 || left < 0 || top < 0 || imageWidth <= 0 || imageHeight <= 0
                || (long) left + imageWidth > width || (long) top + imageHeight > height) {
            throw new MediaException.Malformed("a Phase One image of " + imageWidth + "x" + imageHeight + " at " + left
                    + "," + top + " in a sensor of " + width + "x" + height);
        }
        var compression = block.value(IiqBlock.COMPRESSION, -1);
        if (compression != UNCOMPRESSED && compression != LONG && compression != TONE_CODED && compression != LONG_16) {
            // TODO: decode the IIQ S of the newer backs (compression 6), which Adobe DNG Converter does not read. Its
            //  rows hold groups of eight photosites in two lanes of alternate columns, like IIQ L: a row starts with
            //  the 16-bit values 3 and 1 and one bit, then the first group as eight 14-bit values; the group at
            //  floor(left / 8) * 8 and the one at floor((left + image width) / 8) * 8 start again with 14 zero bits,
            //  two one bits and eight 14-bit values, as does any group with a large step; the code of the other
            //  groups is unknown, and it is lossy (fewer bits per photosite than the entropy of the IIQ L residuals
            //  of the same scene, the dark rows included), so without a reader to compare against (Adobe DNG
            //  Converter writes nothing for these files) a reconstruction rule cannot be checked
            file.unsupported = "the sensor data of Phase One IIQ compression " + compression + " is not supported";
            return;
        }
        if (compression != UNCOMPRESSED && !block.has(IiqBlock.ROWS)) {
            throw new MediaException.Malformed("a compressed Phase One file without its row table");
        }
        var sensor = new Sensor();
        sensor.width = width;
        sensor.height = height;
        sensor.bitsPerSample = 16;
        sensor.activeArea = new int[]{top, left, top + imageHeight, left + imageWidth};
        sensor.cfa = CfaPattern.bayer(pattern(top, left));
        sensor.compression = "Phase One IIQ " + compression;
        var keptBlack = keepsBlack(block);
        sensor.black = new double[]{keptBlack ? block.value(IiqBlock.BLACK, 0) : 0};
        var iq180 = file.model != null && file.model.strip().equals("IQ180");
        sensor.white = new double[]{iq180 ? IQ180_WHITE : WHITE};
        sensor.opcodes1 = defects(block, width, height, ((top & 1) << 1) | (left & 1));
        sensor.reader = (plane, limits) -> read(reader, block, plane, limits, compression, keptBlack, width, height,
                left, top, imageWidth, imageHeight);
        file.sensor = sensor;
        if (block.value(IiqBlock.FORMAT, 0) == 1) {
            file.orientation = 6;
        }
        var balance = block.bytes(IiqBlock.BALANCE);
        if (balance != null && balance.length >= 12) {
            var numbers = ByteBuffer.wrap(balance).order(ByteOrder.LITTLE_ENDIAN);
            float red = numbers.getFloat(0), green = numbers.getFloat(4), blue = numbers.getFloat(8);
            if (red > 0 && green > 0 && blue > 0) {
                file.color.asShotNeutral = new double[]{green / (double) red, 1, green / (double) blue};
            }
        }
    }

    /**
     * {@return whether the file's photosites keep their black level when calibrated}
     *
     * @param block the file's Phase One block
     */
    private static boolean keepsBlack(IiqBlock block) {
        return block.has(IiqBlock.KEPT_ROW_BLACK);
    }

    /**
     * {@return the Bayer pattern at the sensor's origin of an image whose top-left photosite is red}
     *
     * @param top the image's first row
     * @param left the image's first column
     */
    private static String pattern(int top, int left) {
        return (top & 1) == 0 ? (left & 1) == 0 ? "RGGB" : "GRBG" : (left & 1) == 0 ? "GBRG" : "BGGR";
    }

    /**
     * Reads and calibrates the sensor data. Only the part of the data the file holds is read; the rows past its end
     * are zeros.
     *
     * @param reader the file
     * @param block the file's Phase One block
     * @param plane the plane to fill
     * @param limits the resource bounds
     * @param compression the IIQ compression number
     * @param keptBlack whether the photosites keep their black level
     * @param width the sensor's width
     * @param height the sensor's height
     * @param left the image's left
     * @param top the image's top
     * @param imageWidth the image's width
     * @param imageHeight the image's height
     */
    private static void read(TiffReader reader, IiqBlock block, SensorPlane plane, MediaLimits limits, int compression,
                             boolean keptBlack, int width, int height, int left, int top, int imageWidth,
                             int imageHeight) {
        var length = block.dataLength();
        if (reader.size() >= 0) {
            length = Math.min(length, Math.max(0, reader.size() - block.dataOffset()));
        }
        LimitChecks.checkAllocation(limits, length);
        if (length > Integer.MAX_VALUE - 16) {
            throw new MediaException.Malformed("Phase One sensor data of " + length + " bytes");
        }
        var data = new byte[(int) length];
        reader.readAvailable(block.dataOffset(), data, 0, data.length);
        long[] rows = null;
        if (compression != UNCOMPRESSED) {
            var table = block.bytes(IiqBlock.ROWS);
            if (table == null || table.length < height * 4L) {
                throw new MediaException.Malformed("a truncated Phase One row table");
            }
            rows = new long[height];
            for (var y = 0; y < height; y++) {
                rows[y] = Integer.toUnsignedLong(IiqBlock.number(table, y * 4));
            }
        }
        var plan = plan(block, keptBlack, width, height, left, top, imageWidth, imageHeight);
        var offsets = rows;
        var multiplier = compression == LONG_16 ? 1 : 4;
        var curve = compression == TONE_CODED ? TONE_CURVE : null;
        var bands = ParallelBands.bands(height, (long) width * height);
        ParallelBands.run(bands, band -> {
            var samples = new int[width];
            var scratch = new IiqCalibration.Scratch(width);
            var end = ParallelBands.start(band + 1, bands, height);
            for (var y = ParallelBands.start(band, bands, height); y < end; y++) {
                if (offsets == null) {
                    uncompressed(data, y, width, samples);
                } else if (offsets[y] < data.length) {
                    IiqDecoder.row(data, (int) offsets[y], width, multiplier, curve, samples, 0);
                } else {
                    java.util.Arrays.fill(samples, 0);
                }
                IiqCalibration.row(plan, samples, plane.integers, y, scratch);
            }
        });
    }

    /**
     * Copies a row of uncompressed sensor data: little-endian 16-bit photosites, row by row.
     *
     * @param data the sensor data
     * @param y the row
     * @param width the sensor's width
     * @param samples the row's photosites
     */
    private static void uncompressed(byte[] data, int y, int width, int[] samples) {
        var at = y * (long) width * 2;
        for (var x = 0; x < width; x++, at += 2) {
            samples[x] = at + 1 < data.length ? (data[(int) at] & 0xFF) | (data[(int) at + 1] & 0xFF) << 8 : 0;
        }
    }

    /**
     * {@return the calibration of the file}
     *
     * @param block the file's Phase One block
     * @param keptBlack whether the photosites keep their black level
     * @param width the sensor's width
     * @param height the sensor's height
     * @param left the image's left
     * @param top the image's top
     * @param imageWidth the image's width
     * @param imageHeight the image's height
     */
    private static IiqCalibration.Plan plan(IiqBlock block, boolean keptBlack, int width, int height, int left, int top,
                                            int imageWidth, int imageHeight) {
        var levelGrids = IiqCalibration.grids();
        var colorGrids = IiqCalibration.grids();
        for (var entry : block.calibration(IiqBlock.LEVEL_GRID)) {
            var grid = IiqCalibration.Grid.parse(entry, 1);
            if (grid != null) {
                levelGrids.add(grid);
            }
        }
        for (var entry : block.calibration(IiqBlock.COLOR_GRID)) {
            var grid = IiqCalibration.Grid.parse(entry, 2);
            if (grid != null) {
                colorGrids.add(grid);
            }
        }
        short[] rowBlack = null, columnBlack = null;
        var pairs = false;
        IiqCalibration.Response response = null;
        double[] polynomial = null, halfGain = null, outputGains = null;
        IiqCalibration.LevelMap levelMap = null;
        int black = 0, split = -1, splitY = -1;
        if (!keptBlack) {
            black = block.value(IiqBlock.BLACK, 0);
            split = block.value(IiqBlock.SPLIT_X, -1);
            splitY = block.value(IiqBlock.SPLIT_Y, -1);
            var rows = block.bytes(IiqBlock.ROW_BLACK);
            if (rows != null && rows.length >= height * 4L) {
                rowBlack = shorts(rows);
                pairs = true;
            } else {
                rows = block.bytes(IiqBlock.ROW_BLACK_SINGLE);
                if (rows != null && rows.length >= height * 2L) {
                    rowBlack = shorts(rows);
                }
            }
            var columns = block.bytes(IiqBlock.COLUMN_BLACK);
            if (columns != null && columns.length >= width * 4L) {
                columnBlack = shorts(columns);
            }
            if (split > 0 && splitY > 0) {
                var entries = block.calibration(IiqBlock.RESPONSE);
                if (!entries.isEmpty()) {
                    response = IiqCalibration.Response.parse(entries.getFirst());
                }
            }
            polynomial = floats(block, IiqBlock.POLYNOMIAL, 4);
            if (polynomial != null && polynomial[1] == 0 && polynomial[2] == 0 && polynomial[3] == 0) {
                polynomial = null;
            }
            var stage = block.value(IiqBlock.GAIN_STAGE, 0);
            var halves = floats(block, IiqBlock.HALF_GAIN, 9);
            if (halves != null && split > 0 && splitY < 0) {
                var column = stage == 0 ? 1 : 0;
                var temperature = block.has(IiqBlock.TEMPERATURE)
                        ? Float.intBitsToFloat(block.value(IiqBlock.TEMPERATURE, 0)) : halves[8];
                halfGain = new double[]{1 + halves[3 + column] + halves[7] * (temperature - halves[8]),
                        halves[5 + column]};
            }
            var outputs = floats(block, IiqBlock.OUTPUT_GAINS, 21);
            if (outputs != null && split > 0 && splitY > 0) {
                var column = stage == 0 ? 1 : 0;
                outputGains = new double[4];
                for (var output = 0; output < 4; output++) {
                    outputGains[output] = 1 + outputs[5 + output * 4 + column];
                }
            }
            if (split > 0 && splitY > 0) {
                levelMap = levelMap(block, stage);
            }
        }
        return new IiqCalibration.Plan(width, height, left, top, left + imageWidth, top + imageHeight, top & 1, left & 1,
                split, splitY, black, rowBlack, pairs, columnBlack, response, polynomial, halfGain, outputGains, levelMap,
                levelGrids, colorGrids);
    }

    /**
     * {@return the level table of the sensor's gain stage, or {@code null} when none applies}
     *
     * <p>The tables apply when the block's level mode is 1. Stages 0 to 3 have a table each, and a stage whose table
     * the file lacks takes the base table; higher stages take none.
     *
     * @param block the file's Phase One block
     * @param stage the sensor's gain stage
     */
    private static IiqCalibration.LevelMap levelMap(IiqBlock block, int stage) {
        if (block.value(IiqBlock.LEVEL_MODE, 0) != 1 || stage < 0 || stage > 3) {
            return null;
        }
        var entries = block.calibration(IiqBlock.LEVEL_TABLE_STAGES + stage);
        if (entries.isEmpty()) {
            entries = block.calibration(IiqBlock.LEVEL_TABLE_BASE);
        }
        return entries.isEmpty() ? null : IiqCalibration.LevelMap.parse(entries.getFirst());
    }

    /**
     * {@return the little-endian floats of a calibration entry, or {@code null} when it has too few}
     *
     * @param block the file's Phase One block
     * @param tag the calibration tag
     * @param count the floats to read
     */
    private static double[] floats(IiqBlock block, int tag, int count) {
        var entries = block.calibration(tag);
        if (entries.isEmpty() || entries.getFirst().length < count * 4) {
            return null;
        }
        var buffer = ByteBuffer.wrap(entries.getFirst()).order(ByteOrder.LITTLE_ENDIAN);
        var numbers = new double[count];
        for (var index = 0; index < count; index++) {
            numbers[index] = buffer.getFloat(index * 4);
        }
        return numbers;
    }

    /**
     * {@return an opcode list repairing the file's defective photosites and columns, or {@code null} when it lists
     * none}
     *
     * @param block the file's Phase One block
     * @param width the sensor's width
     * @param height the sensor's height
     * @param phase the Bayer phase of the sensor's top-left photosite
     */
    private static byte[] defects(IiqBlock block, int width, int height, int phase) {
        var entries = block.calibration(IiqBlock.DEFECTS);
        if (entries.isEmpty() || entries.getFirst().length < 8) {
            return null;
        }
        var records = ByteBuffer.wrap(entries.getFirst()).order(ByteOrder.LITTLE_ENDIAN);
        var count = entries.getFirst().length / 8;
        var points = ByteBuffer.allocate(count * 8);
        var rects = ByteBuffer.allocate(count * 16);
        for (var index = 0; index < count; index++) {
            int column = records.getChar(index * 8), row = records.getChar(index * 8 + 2), kind = records.getChar(index * 8 + 4);
            if (column >= width) {
                continue;
            }
            if (kind == DEFECT_POINT && row < height) {
                points.putInt(row).putInt(column);
            } else if (kind == DEFECT_COLUMN || kind == DEFECT_COLUMN_OTHER) {
                rects.putInt(0).putInt(column).putInt(height).putInt(column + 1);
            }
        }
        var pointCount = points.position() / 8;
        var rectCount = rects.position() / 16;
        if (pointCount == 0 && rectCount == 0) {
            return null;
        }
        var length = 12 + points.position() + rects.position();
        var list = ByteBuffer.allocate(4 + 16 + length);
        list.putInt(1).putInt(FIX_BAD_PIXELS_LIST).putInt(0x01030000).putInt(1).putInt(length);
        list.putInt(phase).putInt(pointCount).putInt(rectCount);
        list.put(points.array(), 0, points.position()).put(rects.array(), 0, rects.position());
        return list.array();
    }

    /**
     * {@return little-endian signed 16-bit numbers}
     *
     * @param data the bytes
     */
    private static short[] shorts(byte[] data) {
        var numbers = new short[data.length / 2];
        ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(numbers);
        return numbers;
    }

    /**
     * {@return the values of tone-coded numbers}
     */
    private static int[] toneCurve() {
        var curve = new int[256];
        for (var number = 0; number < curve.length; number++) {
            var ratio = number / 255.0;
            curve[number] = 4 * (int) Math.floor(TONE_SCALE * ratio * ratio + 0.5);
        }
        return curve;
    }
}
