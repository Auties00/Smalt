package com.github.auties00.smalt.image.raw.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.raw.CfaPattern;
import com.github.auties00.smalt.internal.io.ByteArrays;
import com.github.auties00.smalt.internal.limits.LimitChecks;

import java.util.Arrays;

/**
 * Parses an Olympus or OM System ORF file: a TIFF structure with its own signature ({@code IIRO},
 * {@code IIRS} or {@code MMOR}) whose first directory holds the sensor data, and whose maker note
 * holds the white balance, black levels, a camera to sRGB matrix and the image crop.
 *
 * <p>Uncompressed data stores 12-bit samples most significant bit first in a stream of
 * little-endian 32-bit words; or, in the files of the compact models that split the data into
 * many strips, most significant bit first in a byte stream that continues across the strips, the
 * image's even rows first and its odd rows after them; ten 12-bit samples, the first in the least significant bits, per
 * 16-byte little-endian block (the high-resolution shots); or 16-bit words, which the oldest models
 * fill with 12-bit samples shifted left by four (or, on the E-10, 10-bit samples shifted left by
 * six). The maker note starts with {@code OLYMPUS\0} and a
 * byte order mark, its offsets relative to its start, or on the oldest models with
 * {@code OLYMP\0} and a version, its offsets relative to the file's TIFF header. It points to the
 * image processing directory (tag {@code 0x2040}) with {@code WB_RBLevels} ({@code 0x0100}, red
 * and blue over 256), {@code ColorMatrix} ({@code 0x0200}, camera to sRGB over 256),
 * {@code BlackLevel2} ({@code 0x0600}) and the crop ({@code 0x0612} to {@code 0x0615}).
 *
 * <p>Olympus' compressed data, which most models write, is decoded by {@link OlympusDecoder}.
 *
 * @implNote This implementation follows the tag names of the ExifTool tag documentation and the
 * layouts found by examining CC0 sample files, checked against the values Adobe DNG
 * Converter, run as a black box, stores for them.
 */
final class OrfParser {
    /**
     * Prevents instantiation.
     */
    private OrfParser() {
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
        var raw = tree.first;
        var processing = subDirectory(tree, 0x2040);
        var settings = subDirectory(tree, 0x2020);
        if (settings != null) {
            var start = settings.longValue(0x0101, 0);
            var length = settings.longValue(0x0102, 0);
            if (start > 0 && length > 0) {
                TiffTree.addPreview(file, reader, settings.reader().base() + start, length);
            }
        }
        var sensor = new Sensor();
        sensor.compression = null;
        sensor.width = raw.intValue(TiffSamples.IMAGE_WIDTH, 0);
        sensor.height = raw.intValue(TiffSamples.IMAGE_LENGTH, 0);
        var bits = raw.intValue(TiffSamples.BITS_PER_SAMPLE, 12);
        if (sensor.width <= 0 || sensor.height <= 0 || bits < 8 || bits > 16) {
            throw new MediaException.Malformed("invalid ORF raw image");
        }
        var offsets = raw.longs(TiffSamples.STRIP_OFFSETS);
        var counts = raw.longs(TiffSamples.STRIP_BYTE_COUNTS);
        if (offsets == null || counts == null || offsets.length == 0 || counts.length == 0) {
            throw new MediaException.Malformed("ORF raw image without data");
        }
        var offset = reader.base() + offsets[0];
        var total = 0L;
        for (var count : counts) {
            total += count;
        }
        var pixels = (long) sensor.width * sensor.height;
        var width = sensor.width;
        var source = reader.source();
        var little = reader.little();
        if (total >= pixels * 2) {
            var shift = bits == 16 ? alignment(reader, offset + total / 2, little) : 0;
            sensor.bitsPerSample = Math.min(bits, 16) - shift;
            var stored = Math.min(bits, 16);
            sensor.reader = (plane, limits) -> {
                var row = new byte[width * 2];
                for (var y = 0; y < plane.height; y++) {
                    var read = source.read(offset + (long) y * row.length, row, 0, row.length);
                    if (read <= 0) {
                        return;
                    }
                    Unpack.words(row, 0, read, little, stored, plane.integers, y * width, width);
                    if (shift != 0) {
                        for (var x = y * width; x < (y + 1) * width; x++) {
                            plane.integers[x] = (short) ((plane.integers[x] & 0xFFFF) >>> shift);
                        }
                    }
                }
            };
        } else if (total == pixels * 8 / 5 && width % 10 == 0) {
            sensor.bitsPerSample = 12;
            sensor.reader = (plane, limits) -> {
                var stride = width / 10 * 16;
                LimitChecks.checkAllocation(limits, stride);
                var row = new byte[stride];
                for (var y = 0; y < plane.height; y++) {
                    var read = source.read(offset + (long) y * stride, row, 0, stride);
                    if (read <= 0) {
                        return;
                    }
                    for (var block = 0; block * 16 < read; block++) {
                        Unpack.lsbFirst(row, block * 16, Math.min(read, block * 16 + 16), 12, plane.integers,
                                y * width + block * 10, 10);
                    }
                }
            };
        } else if (total == pixels * 3 / 2 && width % 8 == 0 && offsets.length > 1) {
            sensor.bitsPerSample = 12;
            var length = total;
            sensor.reader = (plane, limits) -> {
                LimitChecks.checkAllocation(limits, length);
                var data = new byte[(int) Math.min(length, Integer.MAX_VALUE - 16)];
                var filled = 0;
                for (var strip = 0; strip < offsets.length && strip < counts.length && filled < data.length; strip++) {
                    var count = (int) Math.min(counts[strip], data.length - filled);
                    var read = reader.readAvailable(reader.base() + offsets[strip], data, filled, count);
                    filled += read;
                    if (read < count) {
                        break;
                    }
                }
                var stride = width * 3 / 2;
                var half = (plane.height + 1) / 2;
                for (var stored = 0; stored < plane.height && (long) (stored + 1) * stride <= filled; stored++) {
                    var y = stored < half ? 2 * stored : 2 * (stored - half) + 1;
                    Unpack.msbFirst(data, stored * stride, (stored + 1) * stride, 12, plane.integers, y * width, width);
                }
            };
        } else if (total == pixels * 3 / 2 && width % 8 == 0) {
            sensor.bitsPerSample = 12;
            sensor.reader = (plane, limits) -> {
                var stride = width * 3 / 2;
                LimitChecks.checkAllocation(limits, stride);
                var row = new byte[stride];
                for (var y = 0; y < plane.height; y++) {
                    var read = source.read(offset + (long) y * stride, row, 0, stride);
                    if (read <= 0) {
                        return;
                    }
                    Unpack.msbFirstWords(row, 0, read, 12, plane.integers, y * width, width);
                }
            };
        } else {
            sensor.bitsPerSample = 12;
            var length = total;
            sensor.reader = (plane, limits) -> {
                LimitChecks.checkAllocation(limits, length);
                var data = new byte[(int) Math.min(length, Integer.MAX_VALUE - 16)];
                var read = reader.readAvailable(offset, data, 0, data.length);
                OlympusDecoder.decode(read == data.length ? data : Arrays.copyOf(data, read), plane);
            };
        }
        sensor.compression = total < pixels * 3 / 2 ? "Olympus" : "uncompressed";
        sensor.cfa = TiffEpParser.cfa(raw, tree.exif);
        if (sensor.cfa == null) {
            sensor.cfa = CfaPattern.bayer("RGGB");
        }
        sensor.white = new double[]{(1 << sensor.bitsPerSample) - 1};
        if (processing != null) {
            levels(processing, sensor, file);
        }
        file.sensor = sensor;
    }

    /**
     * Returns how far 16-bit uncompressed data is shifted left, as the oldest models write it:
     * 6 for 10-bit samples and 4 for 12-bit samples when the low six or four bits of nearly every
     * non-zero word of a sample of the data are zero (a few words of 12-bit data carry stray low
     * bits, which the samples drop), and 0 otherwise.
     *
     * @param reader the TIFF structure
     * @param position the absolute offset of the sample of the data to check
     * @param little whether the words are little endian
     * @return the shift, 0, 4 or 6
     */
    private static int alignment(TiffReader reader, long position, boolean little) {
        var sample = new byte[1 << 16];
        var read = reader.readAvailable(position & ~1L, sample, 0, sample.length);
        var words = 0;
        var clear4 = 0;
        var clear6 = 0;
        for (var index = 0; index + 1 < read; index += 2) {
            var word = ByteArrays.u16(sample, index, little);
            if (word == 0) {
                continue;
            }
            words++;
            if ((word & 15) == 0) {
                clear4++;
            }
            if ((word & 63) == 0) {
                clear6++;
            }
        }
        if (words == 0) {
            return 0;
        }
        if (clear6 >= words - words / 1000) {
            return 6;
        }
        return clear4 >= words - words / 1000 ? 4 : 0;
    }

    /**
     * Finds a directory the maker note points to, such as the camera settings ({@code 0x2020}) or
     * the image processing ({@code 0x2040}) directory.
     *
     * @param tree the directories
     * @param tag the maker note tag pointing to the directory
     * @return the directory, whose reader's base is the maker note, or {@code null}
     */
    private static Ifd subDirectory(TiffTree tree, int tag) {
        if (tree.exif == null) {
            return null;
        }
        var position = tree.exif.valuePosition(TiffTree.MAKER_NOTE);
        if (position < 0) {
            return null;
        }
        var reader = tree.first.reader();
        var head = new byte[12];
        if (reader.readAvailable(position, head, 0, 12) < 12 || head[0] != 'O' || head[1] != 'L' || head[2] != 'Y'
                || head[3] != 'M' || head[4] != 'P') {
            return null;
        }
        var modern = head[5] == 'U' && head[6] == 'S';
        if (!modern && head[5] != 0) {
            return null;
        }
        try {
            var note = modern ? reader.with(position, head[8] == 'I') : reader;
            var directory = note.directory(modern ? 12 : position + 8 - reader.base());
            var type = directory.type(tag);
            if (type == 0) {
                return null;
            }
            long pointer;
            if (type == 13 || type == 4) {
                pointer = directory.longValue(tag, 0);
            } else {
                pointer = directory.valuePosition(tag) - note.base();
            }
            return pointer > 0 ? note.directory(pointer) : null;
        } catch (MediaException.Malformed exception) {
            return null;
        }
    }

    /**
     * Reads the white balance, color matrix, black levels and crop of the image processing
     * directory.
     *
     * @param processing the directory
     * @param sensor the sensor description to fill
     * @param file the description to fill
     */
    private static void levels(Ifd processing, Sensor sensor, RawFile file) {
        var balance = processing.longs(0x0100);
        if (balance != null && balance.length >= 2 && balance[0] > 0 && balance[1] > 0) {
            file.color.asShotNeutral = new double[]{256.0 / balance[0], 1, 256.0 / balance[1]};
        }
        var matrix = processing.longs(0x0200);
        if (matrix != null && matrix.length >= 9) {
            var values = new double[9];
            for (var index = 0; index < 9; index++) {
                values[index] = (short) matrix[index] / 256.0;
            }
            file.color.cameraToSrgb = values;
        }
        var black = processing.longs(0x0600);
        if (black != null && black.length >= 4) {
            var values = new double[4];
            for (var index = 0; index < 4; index++) {
                values[index] = black[index];
            }
            sensor.blackRows = 2;
            sensor.blackColumns = 2;
            sensor.black = values;
        }
        var left = processing.longValue(0x0612, -1);
        var top = processing.longValue(0x0613, -1);
        var width = processing.longValue(0x0614, 0);
        var height = processing.longValue(0x0615, 0);
        if (left >= 0 && top >= 0 && width > 0 && height > 0 && left + width <= sensor.width && top + height <= sensor.height) {
            sensor.defaultCrop = new double[]{left, top, width, height};
        }
    }
}
