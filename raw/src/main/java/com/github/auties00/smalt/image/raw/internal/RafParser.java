package com.github.auties00.smalt.image.raw.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.raw.CfaColor;
import com.github.auties00.smalt.image.raw.CfaLayout;
import com.github.auties00.smalt.image.raw.CfaPattern;
import com.github.auties00.smalt.internal.io.ByteArrays;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.internal.task.ParallelBands;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Parses a Fujifilm RAF file: a fixed header pointing to an embedded JPEG preview (which carries
 * the EXIF metadata), a list of tagged records describing the sensor, and the sensor data.
 *
 * <p>The header holds, as big-endian 32-bit integers at offset 84, the offset and length of the
 * JPEG preview, of the record list and of the sensor data, and at offset 108 the compression (0
 * for uncompressed). The records used are, as big-endian 16-bit pairs: {@code 0x0100} the stored
 * height and width, {@code 0x0110} the top and left of the cropped image, {@code 0x0111} its height
 * and width, {@code 0x0112} the width and height of the camera's output, {@code 0x0120} the top and
 * left of the image area, {@code 0x0121} its height and width, {@code 0x0130} the sensor layout,
 * {@code 0x0131} the 6x6 X-Trans color filter array (codes 0 for red, 1 for green and 2 for blue,
 * stored from the last photosite to the first), {@code 0x2FF0} the as-shot white balance (green,
 * red, green, blue) and {@code 0x4000} the black levels.
 *
 * <p>The sensor data of most models is a small TIFF structure whose directory points to a
 * directory of private tags: width {@code 0xF001}, height {@code 0xF002}, bits per sample
 * {@code 0xF003}, data offset {@code 0xF007} and length {@code 0xF008} relative to the block,
 * black levels {@code 0xF00A} (one per photosite of the array) and as-shot white balance
 * {@code 0xF00D} (green, red, blue). Uncompressed data is stored as little-endian 16-bit words, or
 * as 12-bit values in rows of {@code 0xF005} words of {@code 0xF004} bytes, packed least
 * significant bit first, or, when {@code 0xF006} is 1, most significant bit first in a stream of
 * little-endian 32-bit words; compressed data is decoded by {@link FujiCompressed}. In these files
 * record {@code 0x0130} states the Bayer array: four codes, 8 for red, 9 and 11 for green and 10 for
 * blue, stored from the last photosite of the 2x2 array at the origin of the data to the first (from the first to
 * the last in the files of the FinePix HS10).
 *
 * <p>Older models store the sensor data as bare 16-bit words, little-endian except in a few
 * 12-bit models whose words are big-endian, of the stored size of record {@code 0x0100}. The
 * first byte of record {@code 0x0130} tells the layout: below 8 the photosites of a SuperCCD
 * sensor, which lie on a grid turned by 45 degrees, stored as staggered rows (DNG
 * {@code CFALayout} 2, even columns half a row down) of a 2x4 filter array (red, green, blue,
 * green; blue, green, red, green); from 8 a Bayer array; with its high bit set the words are stored
 * transposed, column by column. The image area of records {@code 0x0120} and {@code 0x0121} is
 * counted in the stored grid, or, for transposed and some untransposed SuperCCD files, in units
 * of the turned grid: its top times 2 is the first column, its left divided by 2 the first row,
 * its height times 2 the width and its width divided by 2 the height. A transposed file displays
 * through a transposition, and an untransposed one counted in turned units through a quarter turn
 * clockwise. The header states, as big-endian 32-bit integers at offsets 112 and 116, how the words are
 * stored: in one piece when the first is the length of the data or more; as vertical stripes of the
 * stored grid, each whole, row by row, one after the other, when the first is the bytes of a stripe's row
 * and the second those of a stripe (the eight outputs of the DBP for GX680's SuperCCD sensor, which need
 * no gains of their own); and, when the first is the bytes of a stored row and the second more, as rows
 * that far apart, the rows of the second image in between (the FinePix F700).
 *
 * <p>The sensors of two images per photosite store a second record list at the header offset 120
 * (its offset and length) and a second sensor block of the same kind and size at the offset 128,
 * read by the same rules at the same place. In SuperCCD SR files (S3Pro, S5Pro) the second image
 * holds the low-sensitivity photodiode of every photosite; in EXR files it holds the other
 * photosite of each pair of same-color photosites. When the output size of record {@code 0x0112}
 * has at least one and a half times the area of the image, the camera uses both photosites of a
 * pair as image pixels (the EXR HR mode), and the two images form one staggered grid of twice the
 * width, one image in the even columns and the other, half a row down, in the odd ones: in the EXR
 * CMOS files of the sensor directory (F550EXR, X10, F900EXR, HS20EXR) the second image's photosite
 * of a pair lies half a pixel right of and below the first image's, so the first image takes the even
 * columns; in the bare sensor words of the SuperCCD EXR models (S200EXR) it lies half a pixel left
 * of and above it, so the second image takes them.
 * Otherwise the pair reads the same light, at another sensitivity or exposure (SuperCCD SR, EXR DR)
 * or at the same one (EXR SN), and the two images become the two samples of each photosite, which
 * {@link DualSamples} merges. The second image is read from uncompressed files only; no compressed
 * file has one.
 *
 * @implNote This implementation follows the record names of the ExifTool tag documentation and
 * the layouts found by examining sample files (CC0, and CC BY-NC-SA for the FinePix F700, E550, E900,
 * S5600, HS10, F600EXR, F900EXR and HS20EXR, an S200EXR file in HR mode and an X10 file in SN mode) against the
 * files Adobe DNG Converter writes for them, the place of the second image of EXR HR files from the converter's
 * demosaiced linear DNG files (through the lens correction it applies to some of them), the order of the HS10's
 * record {@code 0x0130} from the colors of the camera's own JPEG preview, and the stripes of the DBP for
 * GX680, which the converter does not convert, from the continuity of its stored words across them
 * and the camera's own JPEG preview; the color filter array of the older Bayer models, which their
 * files do not state, is taken as {@code RGGB} at the origin of the image area.
 */
final class RafParser {
    /**
     * The colors of the 2x4 filter array of SuperCCD sensors, in their staggered layout.
     */
    private static final List<CfaColor> SUPER_CCD = List.of(CfaColor.RED, CfaColor.GREEN, CfaColor.BLUE,
            CfaColor.GREEN, CfaColor.BLUE, CfaColor.GREEN, CfaColor.RED, CfaColor.GREEN);

    /**
     * The colors of the codes 8 to 11 of record {@code 0x0130} in a sensor block's file.
     */
    private static final List<CfaColor> BAYER_CODES = List.of(CfaColor.RED, CfaColor.GREEN, CfaColor.BLUE,
            CfaColor.GREEN);

    /**
     * The models whose record {@code 0x0130} states the Bayer array from its first photosite to its last.
     */
    private static final Set<String> FORWARD_BAYER_CODES = Set.of("FinePix HS10 HS11");

    /**
     * The 2x2 transform matrices of the EXIF orientations 1 to 8, row by row.
     */
    private static final int[][] ORIENTATIONS = {{1, 0, 0, 1}, {-1, 0, 0, 1}, {-1, 0, 0, -1}, {1, 0, 0, -1},
            {0, 1, 1, 0}, {0, -1, 1, 0}, {0, -1, -1, 0}, {0, 1, -1, 0}};

    /**
     * The packing of uncompressed rows as little-endian 16-bit words.
     */
    private static final int WORDS = 0;

    /**
     * The packing of uncompressed rows as 12-bit values, least significant bit first.
     */
    private static final int LSB_FIRST = 1;

    /**
     * The packing of uncompressed rows as 12-bit values, most significant bit first in little-endian 32-bit words.
     */
    private static final int MSB_FIRST_WORDS = 2;

    /**
     * Prevents instantiation.
     */
    private RafParser() {
        throw new AssertionError();
    }

    /**
     * The second image of a file: its record list and the place of its sensor block.
     *
     * @param records the record values by tag
     * @param offset the offset of the sensor block
     * @param length the length of the sensor block
     */
    private record Second(Map<Integer, byte[]> records, long offset, long length) {
    }

    /**
     * Reads the rows of an uncompressed sensor block stored as a TIFF structure.
     *
     * @param reader any reader over the file
     * @param offset the offset of the first row
     * @param rowLength the bytes of a row
     * @param packing {@link #WORDS}, {@link #LSB_FIRST} or {@link #MSB_FIRST_WORDS}
     * @param bits the bits of each sample
     * @param width the samples of a row
     */
    private record Rows(TiffReader reader, long offset, int rowLength, int packing, int bits, int width) {
        /**
         * Reads one row; samples past the end of the data keep their values.
         *
         * @param y the row
         * @param buffer a buffer of at least {@link #rowLength} bytes
         * @param out the samples
         * @param target the index of the row's first sample in {@code out}
         * @return whether any of the row could be read
         */
        boolean read(int y, byte[] buffer, short[] out, int target) {
            var read = reader.readAvailable(offset + (long) y * rowLength, buffer, 0, rowLength);
            if (read <= 0) {
                return false;
            }
            switch (packing) {
                case WORDS -> Unpack.words(buffer, 0, read, true, bits, out, target, width);
                case LSB_FIRST -> Unpack.lsbFirst(buffer, 0, read, 12, out, target, width);
                default -> Unpack.msbFirstWords(buffer, 0, read, 12, out, target, width);
            }
            return true;
        }
    }

    /**
     * Parses the file.
     *
     * @param file the description to fill
     */
    static void parse(RawFile file) {
        var source = file.source;
        var reader = new TiffReader(source, 0, false, file.limits);
        var header = reader.bytes(0, 160);
        var model = new String(header, 28, 32, StandardCharsets.ISO_8859_1);
        var end = model.indexOf('\0');
        file.make = "FUJIFILM";
        file.model = (end >= 0 ? model.substring(0, end) : model).strip();
        var jpegOffset = ByteArrays.u32(header, 84, false);
        var jpegLength = ByteArrays.u32(header, 88, false);
        var recordsOffset = ByteArrays.u32(header, 92, false);
        var recordsLength = ByteArrays.u32(header, 96, false);
        var sensorOffset = ByteArrays.u32(header, 100, false);
        var sensorLength = ByteArrays.u32(header, 104, false);
        var compression = ByteArrays.u32(header, 108, false);
        exif(file, reader, jpegOffset, jpegLength);
        TiffTree.addPreview(file, reader, jpegOffset, jpegLength);
        var records = records(reader, recordsOffset, recordsLength);
        int[] crop = null;
        var top = pair(records, 0x110);
        var size = pair(records, 0x111);
        if (top != null || size != null) {
            crop = new int[]{top != null ? top[0] : 0, top != null ? top[1] : 0, size != null ? size[0] : 0,
                    size != null ? size[1] : 0};
        }
        int[] xtrans = null;
        var layout = records.get(0x131);
        if (layout != null && layout.length == 36) {
            xtrans = new int[36];
            for (var k = 0; k < 36; k++) {
                xtrans[k] = layout[35 - k] & 0xFF;
            }
        }
        var second = second(reader, header, jpegOffset, jpegLength, sensorOffset);
        var block = sensorDirectory(reader, sensorOffset);
        if (block != null) {
            sensor(file, reader, sensorOffset, block, records, crop, xtrans,
                    bayer(records.get(0x130), FORWARD_BAYER_CODES.contains(file.model)), compression, second);
        } else {
            // shorter headers place the preview where the chunk fields would be
            var fields = jpegLength == 0 || jpegOffset >= 120;
            legacy(file, reader, records, sensorOffset, sensorLength, second,
                    fields ? ByteArrays.u32(header, 112, false) : 0, fields ? ByteArrays.u32(header, 116, false) : 0);
        }
    }

    /**
     * Reads the place of the second image from the header.
     *
     * @param reader any reader over the file
     * @param header the first 160 bytes of the file
     * @param jpegOffset the offset of the JPEG preview, which shorter headers place before the second image's
     * fields
     * @param jpegLength the length of the JPEG preview
     * @param sensorOffset the offset of the first sensor block
     * @return the second image, or {@code null} when the file has none
     */
    private static Second second(TiffReader reader, byte[] header, long jpegOffset, long jpegLength,
                                 long sensorOffset) {
        if (jpegLength > 0 && jpegOffset < 136) {
            return null;
        }
        var offset = ByteArrays.u32(header, 128, false);
        var length = ByteArrays.u32(header, 132, false);
        if (offset == 0 || length == 0 || offset == sensorOffset) {
            return null;
        }
        var records = records(reader, ByteArrays.u32(header, 120, false), ByteArrays.u32(header, 124, false));
        return new Second(records, offset, length);
    }

    /**
     * Reads the record list.
     *
     * @param reader any reader over the file
     * @param offset the offset of the list
     * @param length the length of the list
     * @return the value of each record by tag; empty when the list is missing or implausible
     */
    private static Map<Integer, byte[]> records(TiffReader reader, long offset, long length) {
        var records = new HashMap<Integer, byte[]>();
        if (length < 4 || length >= 1 << 20) {
            return records;
        }
        var bytes = reader.bytes(offset, length);
        var count = ByteArrays.u32(bytes, 0, false);
        var position = 4;
        for (var index = 0; index < count && position + 4 <= bytes.length; index++) {
            var tag = ByteArrays.u16(bytes, position, false);
            var size = ByteArrays.u16(bytes, position + 2, false);
            var value = position + 4;
            if (value + size > bytes.length) {
                break;
            }
            records.putIfAbsent(tag, Arrays.copyOfRange(bytes, value, value + size));
            position = value + size;
        }
        return records;
    }

    /**
     * {@return the two big-endian 16-bit values of a record, or {@code null} when it is missing or too short}
     *
     * @param records the record values by tag
     * @param tag the record tag
     */
    private static int[] pair(Map<Integer, byte[]> records, int tag) {
        var value = records.get(tag);
        if (value == null || value.length < 4) {
            return null;
        }
        return new int[]{ByteArrays.u16(value, 0, false), ByteArrays.u16(value, 2, false)};
    }

    /**
     * {@return whether the camera's output of record {@code 0x0112} has at least one and a half times the area of
     * the image, so that both photosites of each pair are image pixels}
     *
     * @param records the record values by tag
     * @param area the area of the image: the cropped image, or the stored image without a crop
     */
    private static boolean diagonalPairs(Map<Integer, byte[]> records, long area) {
        var output = pair(records, 0x112);
        return output != null && area > 0 && (long) output[0] * output[1] * 2 >= area * 3;
    }

    /**
     * {@return the Bayer pattern of a sensor block from its record {@code 0x0130}, or {@code RGGB} when the record
     * does not state one}
     *
     * @param layout the record {@code 0x0130}, or {@code null}
     * @param forward whether the record lists the photosites from the first to the last, as a few models' records do,
     * rather than from the last to the first
     */
    private static CfaPattern bayer(byte[] layout, boolean forward) {
        if (layout == null || layout.length < 4) {
            return CfaPattern.bayer("RGGB");
        }
        var seen = 0;
        var colors = new ArrayList<CfaColor>();
        for (var index = 0; index < 4; index++) {
            var code = (layout[forward ? index : 3 - index] & 0xFF) - 8;
            if (code < 0 || code > 3 || (seen & 1 << code) != 0) {
                return CfaPattern.bayer("RGGB");
            }
            seen |= 1 << code;
            colors.add(BAYER_CODES.get(code));
        }
        return CfaPattern.of(2, 2, colors);
    }

    /**
     * Reads the camera, shot and EXIF block from the preview's EXIF segment.
     *
     * @param file the description to fill
     * @param reader any reader over the file
     * @param offset the offset of the preview
     * @param length the length of the preview
     */
    private static void exif(RawFile file, TiffReader reader, long offset, long length) {
        var tree = TiffTree.ofJpegExif(reader, offset, length);
        if (tree == null) {
            return;
        }
        var make = file.make;
        var model = file.model;
        tree.describe(file);
        file.previews.clear();
        file.make = make;
        file.model = model;
    }

    /**
     * Finds the private directory of a sensor block stored as a TIFF structure.
     *
     * @param reader any reader over the file
     * @param offset the offset of the sensor block
     * @return the directory, or {@code null} when the block is not a TIFF structure with one
     */
    private static Ifd sensorDirectory(TiffReader reader, long offset) {
        try {
            var tiff = TiffReader.open(reader.source(), offset, reader.limits());
            return tiff.directory(tiff.firstDirectory()).child(0xF000);
        } catch (MediaException.Malformed exception) {
            return null;
        }
    }

    /**
     * Reads a sensor block stored as a TIFF structure.
     *
     * @param file the description to fill
     * @param reader any reader over the file
     * @param offset the offset of the sensor block
     * @param block the private directory of the block
     * @param records the record values by tag
     * @param crop the cropped area's top, left, height and width, or {@code null}
     * @param xtrans the X-Trans pattern, or {@code null} for a Bayer sensor
     * @param bayer the Bayer pattern of a sensor without an X-Trans pattern
     * @param compression the compression of the file header
     * @param second the second image, or {@code null}
     */
    private static void sensor(RawFile file, TiffReader reader, long offset, Ifd block, Map<Integer, byte[]> records,
                               int[] crop, int[] xtrans, CfaPattern bayer, long compression, Second second) {
        var sensor = new Sensor();
        sensor.width = block.intValue(0xF001, 0);
        sensor.height = block.intValue(0xF002, 0);
        sensor.bitsPerSample = block.intValue(0xF003, 0);
        var dataOffset = offset + block.longValue(0xF007, 0);
        var dataLength = block.longValue(0xF008, 0);
        if (sensor.width <= 0 || sensor.height <= 0 || sensor.bitsPerSample < 8 || sensor.bitsPerSample > 16) {
            throw new MediaException.Malformed("invalid RAF sensor of " + sensor.width + "x" + sensor.height + "x"
                    + sensor.bitsPerSample);
        }
        var bits = sensor.bitsPerSample;
        var width = sensor.width;
        var height = sensor.height;
        if (xtrans != null) {
            var colors = new ArrayList<CfaColor>();
            for (var code : xtrans) {
                if (code > 2) {
                    throw new MediaException.Malformed("invalid X-Trans color " + code);
                }
                colors.add(CfaColor.of(code));
            }
            sensor.cfa = CfaPattern.of(6, 6, colors);
        } else {
            sensor.cfa = bayer;
        }
        Rows rows = null;
        if (compression != 0) {
            var header = FujiCompressed.header(reader, dataOffset, dataLength);
            if (header.width() != sensor.width || header.height() != sensor.height || header.bits() != bits
                    || header.xtrans() != (xtrans != null)) {
                throw new MediaException.Malformed("the compressed RAF stream does not match its sensor");
            }
            var codes = xtrans != null ? xtrans : new int[]{bayer.color(0, 0).code(), bayer.color(0, 1).code(),
                    bayer.color(1, 0).code(), bayer.color(1, 1).code()};
            var period = xtrans != null ? 6 : 2;
            sensor.reader = (plane, limits) -> FujiCompressed.decode(reader, header, codes, period, plane, limits);
            sensor.compression = header.lossless() ? "Fujifilm lossless compressed" : "Fujifilm lossy compressed";
        } else {
            rows = rows(reader, dataOffset, dataLength, block, width, height, bits);
            if (rows == null) {
                file.unsupported = "this RAF sensor data packing is not supported";
                return;
            }
            sensor.compression = "uncompressed";
        }
        LimitChecks.checkPicture(file.limits, sensor.width, sensor.height);
        var secondBlock = rows != null && xtrans == null && second != null ? sensorDirectory(reader, second.offset())
                : null;
        Rows secondRows = null;
        if (secondBlock != null && secondBlock.intValue(0xF001, 0) == width && secondBlock.intValue(0xF002, 0) == height
                && secondBlock.intValue(0xF003, 0) == bits) {
            secondRows = rows(reader, second.offset() + secondBlock.longValue(0xF007, 0),
                    secondBlock.longValue(0xF008, 0), secondBlock, width, height, bits);
        }
        var black = black(block, sensor.cfa.width());
        var balance = block.longs(0xF00D);
        if (balance != null && balance.length >= 3 && balance[0] > 0 && balance[1] > 0 && balance[2] > 0) {
            file.color.asShotNeutral = new double[]{(double) balance[0] / balance[1], 1, (double) balance[0] / balance[2]};
        }
        var white = (1 << bits) - 1;
        var fits = crop != null && crop[2] > 0 && crop[3] > 0 && crop[0] + crop[2] <= sensor.height
                && crop[1] + crop[3] <= sensor.width;
        if (secondRows == null) {
            var single = rows;
            if (single != null) {
                sensor.reader = (plane, limits) -> {
                    var row = new byte[single.rowLength()];
                    for (var y = 0; y < plane.height; y++) {
                        if (!single.read(y, row, plane.integers, y * width)) {
                            return;
                        }
                    }
                };
            }
            if (black.length == sensor.cfa.width() * sensor.cfa.width()) {
                sensor.blackRows = sensor.cfa.width();
                sensor.blackColumns = sensor.cfa.width();
            }
            sensor.black = black.length > 0 ? black : null;
            sensor.white = new double[]{white};
            if (fits) {
                sensor.defaultCrop = new double[]{crop[1], crop[0], crop[3], crop[2]};
            }
            file.sensor = sensor;
            return;
        }
        var secondBlack = black(secondBlock, 2);
        var first = rows;
        var other = secondRows;
        sensor.reader = (plane, limits) -> readPair(first, other, width, height, plane);
        var patterned = black.length == 4 && secondBlack.length == 4;
        var cropArea = fits ? (long) crop[2] * crop[3] : (long) width * height;
        if (diagonalPairs(records, cropArea)) {
            sensor.width = 2 * width;
            LimitChecks.checkPicture(file.limits, sensor.width, sensor.height);
            sensor.cfa = DualSamples.interleaved(bayer);
            sensor.layout = CfaLayout.STAGGERED_A;
            sensor.scaleH = Math.sqrt(0.5);
            sensor.scaleV = Math.sqrt(2);
            sensor.blackRows = patterned ? 2 : 1;
            sensor.blackColumns = patterned ? 4 : 2;
            sensor.black = new double[sensor.blackRows * sensor.blackColumns];
            for (var row = 0; row < sensor.blackRows; row++) {
                for (var column = 0; column < sensor.blackColumns; column++) {
                    var levels = column % 2 == 0 ? black : secondBlack;
                    sensor.black[row * sensor.blackColumns + column] = level(levels, patterned
                            ? row * 2 + column / 2 % 2 : 0);
                }
            }
            sensor.white = new double[]{white};
            if (fits) {
                sensor.defaultCrop = new double[]{2.0 * crop[1], crop[0], 2.0 * crop[3], crop[2]};
            }
        } else {
            sensor.samplesPerPixel = 2;
            sensor.blackRows = patterned ? 2 : 1;
            sensor.blackColumns = patterned ? 2 : 1;
            var sites = sensor.blackRows * sensor.blackColumns;
            sensor.black = new double[sites * 2];
            for (var site = 0; site < sites; site++) {
                sensor.black[site * 2] = level(black, patterned ? site : 0);
                sensor.black[site * 2 + 1] = level(secondBlack, patterned ? site : 0);
            }
            sensor.white = new double[]{white, white};
            if (fits) {
                sensor.defaultCrop = new double[]{crop[1], crop[0], crop[3], crop[2]};
            }
        }
        file.sensor = sensor;
    }

    /**
     * {@return the black levels of a sensor block: one per photosite of its {@code period x period} array, a
     * single level, or none}
     *
     * @param block the private directory of the block
     * @param period the size of the block's filter array
     */
    private static double[] black(Ifd block, int period) {
        var black = block.doubles(0xF00A);
        if (black != null && black.length == period * period) {
            return black;
        }
        return black != null && black.length > 0 ? new double[]{black[0]} : new double[0];
    }

    /**
     * {@return one of a block's black levels, the first when it has a single one, 0 when it has none}
     *
     * @param levels the levels of {@link #black(Ifd, int)}
     * @param index the photosite of the array
     */
    private static double level(double[] levels, int index) {
        return levels.length == 0 ? 0 : levels.length == 1 ? levels[0] : levels[index];
    }

    /**
     * Describes the rows of an uncompressed sensor block.
     *
     * @param reader any reader over the file
     * @param dataOffset the offset of the data
     * @param dataLength the length of the data
     * @param block the private directory of the block
     * @param width the samples of a row
     * @param height the rows
     * @param bits the bits of each sample
     * @return the rows, or {@code null} when the packing is not supported
     */
    private static Rows rows(TiffReader reader, long dataOffset, long dataLength, Ifd block, int width, int height,
                             int bits) {
        var pixels = (long) width * height;
        var wordBytes = block.intValue(0xF004, 0);
        var rowWords = block.intValue(0xF005, 0);
        var order = block.intValue(0xF006, 0);
        // 16-bit words; 12-bit data stating a row stride is packed however much its rows are padded
        if (dataLength >= pixels * 2 && (bits != 12 || wordBytes == 0 || rowWords == 0)) {
            return new Rows(reader, dataOffset, width * 2, WORDS, bits, width);
        }
        if (bits != 12 || dataLength < pixels * 3 / 2 || order != 0 && (order != 1 || wordBytes != 4)) {
            return null;
        }
        var stride = (long) wordBytes * rowWords;
        var rowLength = (int) (stride >= (width * 3 + 1) / 2 && stride * height <= dataLength ? stride : width * 3 / 2);
        return new Rows(reader, dataOffset, rowLength, order == 1 ? MSB_FIRST_WORDS : LSB_FIRST, bits, width);
    }

    /**
     * Reads the rows of two sensor blocks of the same size into a plane in which the samples of the two blocks
     * alternate: each row holds, for every column of the blocks, the first block's sample and then the second's.
     *
     * @param first the rows of the first block
     * @param second the rows of the second block
     * @param width the samples of a block's row
     * @param height the rows
     * @param plane the plane to fill, of {@code 2 * width * height} samples
     */
    private static void readPair(Rows first, Rows second, int width, int height, SensorPlane plane) {
        var bands = ParallelBands.bands(height, (long) width * height * 2);
        ParallelBands.run(bands, band -> {
            var last = ParallelBands.start(band + 1, bands, height);
            var bytes = new byte[Math.max(first.rowLength(), second.rowLength())];
            var line = new short[width];
            for (var y = ParallelBands.start(band, bands, height); y < last; y++) {
                for (var sample = 0; sample < 2; sample++) {
                    Arrays.fill(line, (short) 0);
                    if (!(sample == 0 ? first : second).read(y, bytes, line, 0)) {
                        continue;
                    }
                    var target = y * 2 * width + sample;
                    for (var x = 0; x < width; x++) {
                        plane.integers[target + 2 * x] = line[x];
                    }
                }
            }
        });
    }

    /**
     * Reads the bare sensor words of an older model.
     *
     * @param file the description to fill
     * @param reader any reader over the file
     * @param records the record values by tag
     * @param offset the offset of the sensor data
     * @param length the length of the sensor data
     * @param second the second image, or {@code null}
     * @param chunk the header's length of the chunks the words are stored in: a whole row or more, or the row of one
     * stripe; 0 when the header has none
     * @param stride the header's distance between the starts of chunks: the stride of the rows when a chunk is a row,
     * the size of a stripe when it is a stripe's row
     */
    private static void legacy(RawFile file, TiffReader reader, Map<Integer, byte[]> records, long offset, long length,
                               Second second, long chunk, long stride) {
        var stored = pair(records, 0x100);
        var layout = records.get(0x130);
        if (stored == null || layout == null || layout.length < 1 || stored[0] == 0 || stored[1] == 0
                || (long) stored[0] * stored[1] * 2 > length) {
            file.unsupported = "this RAF sensor data layout is not supported";
            return;
        }
        var rows = stored[0];
        var columns = stored[1];
        // vertical stripes of the stored grid, each whole, one after the other; or the rows of the two images in turn
        var rowBytes = 2L * columns;
        var stripes = chunk > 0 && chunk % 2 == 0 && chunk < rowBytes && rowBytes % chunk == 0 && stride == rows * chunk
                ? (int) (rowBytes / chunk) : 1;
        var storedRowStride = chunk == rowBytes && stride > chunk ? stride : rowBytes;
        if (chunk > 0 && chunk < rowBytes && stripes == 1) {
            file.unsupported = "this RAF sensor data layout is not supported";
            return;
        }
        var stripeWidth = columns / stripes;
        var code = layout[0] & 0xFF;
        var transposed = (code & 0x80) != 0;
        var superCcd = (code & 0x7F) < 8;
        // the image area: plane size and the stored row and column of the plane's origin
        var area = area(pair(records, 0x120), pair(records, 0x121), rows, columns, transposed);
        var turned = area[4] == 1;
        var planeWidth = area[0];
        var planeHeight = area[1];
        var firstRow = area[2];
        var firstColumn = area[3];
        LimitChecks.checkPicture(file.limits, planeWidth, planeHeight);
        var bytes = (long) rows * columns * 2;
        var paired = second != null && Arrays.equals(second.records().get(0x100), records.get(0x100))
                && second.records().get(0x130) != null && second.records().get(0x130).length >= 1
                && (second.records().get(0x130)[0] & 0xFF) == code && second.length() >= bytes;
        var bigEndian = bigEndian(reader, offset, bytes);
        // only the 12-bit models store big-endian words
        var bits = bigEndian ? 12 : 14;
        var sensor = new Sensor();
        sensor.width = planeWidth;
        sensor.height = planeHeight;
        sensor.bitsPerSample = bits;
        sensor.compression = "uncompressed";
        var crop = crop(pair(records, 0x110), pair(records, 0x111), pair(records, 0x120), turned, planeWidth,
                planeHeight);
        var diagonal = paired && !superCcd && diagonalPairs(records, crop != null ? (long) (crop[2] * crop[3])
                : (long) planeWidth * planeHeight);
        if (superCcd) {
            // TODO: check the F710 and the F810 against a sample once one is published; they are read by the records
            // of their siblings (F700, E550), and no archive holds a file of them
            sensor.cfa = CfaPattern.of(4, 2, SUPER_CCD);
            sensor.layout = CfaLayout.STAGGERED_A;
            sensor.scaleH = Math.sqrt(0.5);
            sensor.scaleV = Math.sqrt(2);
        } else if (diagonal) {
            sensor.cfa = DualSamples.interleaved(CfaPattern.bayer("RGGB"));
            sensor.layout = CfaLayout.STAGGERED_A;
            sensor.scaleH = Math.sqrt(0.5);
            sensor.scaleV = Math.sqrt(2);
            sensor.width = 2 * planeWidth;
            LimitChecks.checkPicture(file.limits, sensor.width, sensor.height);
        } else {
            sensor.cfa = CfaPattern.bayer("RGGB");
        }
        if (paired && !diagonal) {
            sensor.samplesPerPixel = 2;
        }
        // the second image of SuperCCD EXR HR files lies half a pixel left of and above the first: the even columns
        var offsets = diagonal ? new long[]{second.offset(), offset}
                : paired ? new long[]{offset, second.offset()} : new long[]{offset};
        var step = offsets.length;
        var bands = Math.max(1, Math.min((transposed ? planeWidth : planeHeight) / 64, ParallelBands.THREADS * 2));
        sensor.reader = (plane, limits) -> {
            // each stored row holds one row of the plane, or one column when transposed
            var lines = transposed ? planeWidth : planeHeight;
            var count = transposed ? planeHeight : planeWidth;
            var rowStride = planeWidth * step;
            ParallelBands.run(bands, band -> {
                var from = ParallelBands.start(band, bands, lines);
                var to = ParallelBands.start(band + 1, bands, lines);
                var line = new byte[Math.min(count, stripeWidth) * 2];
                for (var index = from; index < to; index++) {
                    var row = firstRow + index;
                    for (var sample = 0; sample < step; sample++) {
                        // a stored row runs through the stripes of a sensor of several outputs
                        for (var k = 0; k < count; ) {
                            var column = firstColumn + k;
                            var stripe = column / stripeWidth;
                            var run = Math.min(count - k, stripeWidth - column % stripeWidth);
                            var at = offsets[sample] + (stripes > 1 ? ((long) stripe * rows + row) * stripeWidth * 2
                                    : row * storedRowStride) + column % stripeWidth * 2L;
                            var read = reader.readAvailable(at, line, 0, run * 2);
                            for (var i = 0; 2 * i + 1 < read; i++) {
                                var x = transposed ? index : k + i;
                                var y = transposed ? k + i : index;
                                plane.integers[y * rowStride + x * step + sample] = word(line, 2 * i, bigEndian);
                            }
                            k += run;
                        }
                    }
                }
            });
        };
        var black = blackLevel(records);
        if (paired) {
            // two samples per photosite, or the two images in alternate columns
            sensor.blackColumns = diagonal ? 2 : 1;
            sensor.black = diagonal ? new double[]{blackLevel(second.records()), black}
                    : new double[]{black, blackLevel(second.records())};
        } else if (records.get(0x4000) != null && records.get(0x4000).length >= 2) {
            sensor.black = new double[]{black};
        }
        sensor.white = sensor.samplesPerPixel == 2 ? new double[]{(1 << bits) - 1, (1 << bits) - 1}
                : new double[]{(1 << bits) - 1};
        if (crop != null) {
            sensor.defaultCrop = diagonal ? new double[]{2 * crop[0], crop[1], 2 * crop[2], crop[3]} : crop;
        }
        var balance = records.get(0x2FF0);
        if (balance != null && balance.length >= 8) {
            var green = ByteArrays.u16(balance, 0, false);
            var red = ByteArrays.u16(balance, 2, false);
            var blue = ByteArrays.u16(balance, 6, false);
            if (green > 0 && red > 0 && blue > 0) {
                file.color.asShotNeutral = new double[]{(double) green / red, 1, (double) green / blue};
            }
        }
        if (turned) {
            file.orientation = compose(file.orientation, transposed ? 5 : 6);
        }
        file.sensor = sensor;
    }

    /**
     * {@return the black level of record {@code 0x4000}, or 0 when the record is missing or too short}
     *
     * @param records the record values by tag
     */
    private static int blackLevel(Map<Integer, byte[]> records) {
        var black = records.get(0x4000);
        return black != null && black.length >= 2 ? ByteArrays.u16(black, 0, false) : 0;
    }

    /**
     * Places the image area of an older model's sensor words.
     *
     * @param origin the record {@code 0x0120} (top, left), or {@code null}
     * @param size the record {@code 0x0121} (height, width), or {@code null}
     * @param rows the stored rows
     * @param columns the stored columns
     * @param transposed whether the words are stored transposed
     * @return the plane width and height, the stored row and column of its origin, and 1 when the records count
     * in turned units, otherwise 0
     */
    private static int[] area(int[] origin, int[] size, int rows, int columns, boolean transposed) {
        if (origin != null && size != null && size[0] > 0 && size[1] > 0) {
            var top = origin[0];
            var left = origin[1];
            if (!transposed && top + size[0] <= rows && left + size[1] <= columns) {
                return new int[]{size[1], size[0], top, left, 0};
            }
            var width = 2 * size[0];
            var height = size[1] / 2;
            if (transposed && 2 * top + width <= rows && left / 2 + height <= columns) {
                return new int[]{width, height, 2 * top, left / 2, 1};
            }
            if (!transposed && left / 2 + height <= rows && 2 * top + width <= columns) {
                return new int[]{width, height, left / 2, 2 * top, 1};
            }
        }
        return transposed ? new int[]{rows, columns, 0, 0, 1} : new int[]{columns, rows, 0, 0, 0};
    }

    /**
     * Places the default crop of an older model in its plane.
     *
     * @param top the record {@code 0x0110} (top, left), or {@code null}
     * @param size the record {@code 0x0111} (height, width), or {@code null}
     * @param origin the record {@code 0x0120} (top, left), or {@code null}
     * @param turned whether the records count in turned units
     * @param width the plane width
     * @param height the plane height
     * @return the crop as x, y, width and height, or {@code null} when it does not fit the plane
     */
    private static double[] crop(int[] top, int[] size, int[] origin, boolean turned, int width, int height) {
        if (top == null || size == null) {
            return null;
        }
        var originTop = origin != null ? origin[0] : 0;
        var originLeft = origin != null ? origin[1] : 0;
        double x;
        double y;
        double w;
        double h;
        if (turned) {
            x = 2.0 * (top[0] - originTop);
            y = (top[1] - originLeft) / 2.0;
            w = 2.0 * size[0];
            h = size[1] / 2.0;
        } else {
            x = top[1] - originLeft;
            y = top[0] - originTop;
            w = size[1];
            h = size[0];
        }
        if (x < 0 || y < 0 || w <= 0 || h <= 0 || x + w > width || y + h > height) {
            return null;
        }
        return new double[]{x, y, w, h};
    }

    /**
     * Tells the byte order of an older model's sensor words from a stretch in the middle of the data: the order
     * under which fewer words reach 14 bits.
     *
     * @param reader any reader over the file
     * @param offset the offset of the data
     * @param length the length of the data
     * @return whether the words are big-endian
     */
    private static boolean bigEndian(TiffReader reader, long offset, long length) {
        var sample = new byte[(int) Math.min(1 << 16, length) & ~1];
        var read = reader.readAvailable(offset + (length / 2 & ~1L), sample, 0, sample.length);
        var little = 0;
        var big = 0;
        for (var index = 0; index + 1 < read; index += 2) {
            var first = sample[index] & 0xFF;
            var second = sample[index + 1] & 0xFF;
            if ((first << 8 | second) != 0xFFFF) {
                little += (second << 8 | first) >= 1 << 14 ? 1 : 0;
                big += (first << 8 | second) >= 1 << 14 ? 1 : 0;
            }
        }
        return big < little;
    }

    /**
     * {@return a 16-bit word}
     *
     * @param data the bytes
     * @param offset the offset of the word
     * @param bigEndian whether the word is big-endian
     */
    private static short word(byte[] data, int offset, boolean bigEndian) {
        var first = data[offset] & 0xFF;
        var second = data[offset + 1] & 0xFF;
        return (short) (bigEndian ? first << 8 | second : second << 8 | first);
    }

    /**
     * Composes two EXIF orientations.
     *
     * @param after the orientation applied second, from 1 to 8; another value counts as 1
     * @param before the orientation applied first, from 1 to 8
     * @return the orientation of applying {@code before} and then {@code after}
     */
    static int compose(int after, int before) {
        var a = ORIENTATIONS[after >= 1 && after <= 8 ? after - 1 : 0];
        var b = ORIENTATIONS[before - 1];
        var product = new int[]{a[0] * b[0] + a[1] * b[2], a[0] * b[1] + a[1] * b[3], a[2] * b[0] + a[3] * b[2],
                a[2] * b[1] + a[3] * b[3]};
        for (var code = 0; code < ORIENTATIONS.length; code++) {
            if (Arrays.equals(ORIENTATIONS[code], product)) {
                return code + 1;
            }
        }
        throw new AssertionError();
    }
}
