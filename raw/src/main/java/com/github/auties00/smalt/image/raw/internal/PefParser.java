package com.github.auties00.smalt.image.raw.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.internal.io.ByteArrays;
import com.github.auties00.smalt.internal.limits.LimitChecks;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses a Pentax PEF file: a TIFF/EP structure whose color filter array directory holds the
 * sensor data, uncompressed or with Pentax's Huffman compression (TIFF compression 65535), and
 * whose maker note holds the Huffman table, the black levels and the white balance.
 *
 * <p>The maker note starts with {@code AOC\0} (its directory's offsets relative to the file) or
 * {@code PENTAX \0} (offsets relative to the maker note) followed by a byte order mark. Its tag
 * {@code 0x0220} holds the Huffman table after a 14-byte header: {@code n} 16-bit codes in the
 * maker note's byte order, left-aligned on 12 bits, then their {@code n} bit lengths, with
 * {@code n} 13 for 12-bit data and 15 for 14-bit data; the code of index {@code c} announces a
 * difference of {@code c} bits, extended as in a JPEG DC table. A maker note without the tag
 * implies the 13-code table the K10D, K20D, K200D, K-7, K-x and K-r files embed (also for 14-bit
 * data). The data is one stream of such codes, most significant bit first, one per photosite row
 * by row; each photosite is predicted from the previous one of the same color in its row, the
 * first two of a row from the first two of the row two above, starting from zero, and is clamped
 * to the range of its bit depth before it predicts others. Tag {@code 0x0200} holds the black level of each CFA
 * position and tag {@code 0x0201} the as-shot white balance levels. Uncompressed 12-bit data is
 * packed most significant bit first, also when the older models label it with TIFF compression
 * 32773, or stored in 16-bit words when the strip holds two bytes per photosite. A monochrome
 * sensor stores linear raw data of one sample.
 *
 * @implNote This implementation derives the stream layout from the table the files embed and from
 * decoding CC0 sample files, with tag names from the ExifTool tag documentation; it reads
 * no other implementation. The table layout of the 14-bit and little-endian files and the
 * packing of the files labeled with compression 32773 were checked against Adobe DNG
 * Converter, run as a black box. No CC0 file lacks the table: the implied table and the clamp
 * reproduce the values the converter stores for copies of CC0 files (12-bit files embedding
 * that table and another one, and a 14-bit file) whose maker note tag {@code 0x0220} is
 * renamed.
 */
final class PefParser {
    /**
     * The TIFF compression code of Pentax's raw data.
     */
    private static final int PENTAX_COMPRESSION = 65535;

    /**
     * The TIFF compression code (PackBits) the oldest models give their uncompressed data.
     */
    private static final int PACKBITS_LABEL = 32773;

    /**
     * The codes of the table that decodes files whose maker note carries none, by difference bit
     * length, left-aligned on 12 bits.
     */
    private static final int[] DEFAULT_CODES = {
            0x0F00, 0x0C00, 0x0800, 0x0000, 0x0400, 0x0A00, 0x0E00, 0x0F80, 0x0FC0, 0x0FE0, 0x0FF0, 0x0FF8, 0x0FFC
    };

    /**
     * The bit lengths of {@link #DEFAULT_CODES}.
     */
    private static final int[] DEFAULT_LENGTHS = {5, 3, 3, 2, 2, 3, 4, 6, 7, 8, 9, 10, 10};

    /**
     * The frames of a Pentax pixel-shift file.
     */
    private static final int PIXEL_SHIFT_FRAMES = 4;

    /**
     * Prevents instantiation.
     */
    private PefParser() {
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
            file.unsupported = "the PEF file holds no raw image";
            return;
        }
        var note = makerNote(tree.exif, tree.first.reader());
        var sensor = frame(reader, raw, note, tree.exif, file);
        if (sensor == null) {
            return;
        }
        sensor.defaultCrop = NefParser.previewCrop(file, sensor);
        var frames = pixelShiftFrames(tree, raw);
        if (frames.size() == PIXEL_SHIFT_FRAMES && sensor.cfa != null && sensor.cfa.isBayer()
                && sensor.samplesPerPixel == 1) {
            var parsed = new ArrayList<Sensor>(frames.size());
            parsed.add(sensor);
            for (var index = 1; index < frames.size(); index++) {
                var directory = frames.get(index);
                var exif = directory.child(TiffTree.EXIF_IFD);
                var frame = frame(reader, directory, makerNote(exif, reader), exif, null);
                if (frame == null) {
                    file.sensor = sensor;
                    return;
                }
                frame.defaultCrop = sensor.defaultCrop;
                parsed.add(frame);
            }
            var merged = PixelShift.merge(parsed, PixelShift.PENTAX_DX, PixelShift.PENTAX_DY);
            file.sensor = merged != null ? merged : sensor;
        } else {
            file.sensor = sensor;
        }
    }

    /**
     * Describes the image of one raw directory.
     *
     * @param reader the TIFF structure
     * @param raw the raw image directory
     * @param note the maker note of the directory, or {@code null}
     * @param exif the EXIF directory of the directory, or {@code null}
     * @param file the description to fill with the white balance and the reason a file is not supported,
     * or {@code null} to leave the file as it is
     * @return the sensor description, or {@code null} when the image cannot be decoded
     */
    private static Sensor frame(TiffReader reader, Ifd raw, Ifd note, Ifd exif, RawFile file) {
        var sensor = new Sensor();
        var compression = raw.intValue(TiffSamples.COMPRESSION, 1);
        if (compression == PENTAX_COMPRESSION) {
            sensor.width = raw.intValue(TiffSamples.IMAGE_WIDTH, 0);
            sensor.height = raw.intValue(TiffSamples.IMAGE_LENGTH, 0);
            sensor.bitsPerSample = raw.intValue(TiffSamples.BITS_PER_SAMPLE, 12);
            if (sensor.width <= 0 || sensor.height <= 0 || sensor.bitsPerSample < 8 || sensor.bitsPerSample > 16) {
                throw new MediaException.Malformed("invalid PEF raw image");
            }
            var table = note == null ? null : note.bytes(0x220);
            var spec = table == null || table.length < 39
                    ? Huffman.fromCodes(DEFAULT_CODES, DEFAULT_LENGTHS, 12)
                    : huffman(table, note.reader().little());
            var offsets = raw.longs(TiffSamples.STRIP_OFFSETS);
            var counts = raw.longs(TiffSamples.STRIP_BYTE_COUNTS);
            if (offsets == null || counts == null || offsets.length == 0 || counts.length == 0) {
                throw new MediaException.Malformed("PEF raw image without data");
            }
            var offset = reader.base() + offsets[0];
            var length = counts[0];
            var maximum = (1 << sensor.bitsPerSample) - 1;
            sensor.compression = "Pentax Huffman";
            sensor.reader = (plane, limits) -> decompress(reader, offset, length, spec, maximum, plane, limits);
        } else if (compression == 1 && words(raw)) {
            sensor.width = raw.intValue(TiffSamples.IMAGE_WIDTH, 0);
            sensor.height = raw.intValue(TiffSamples.IMAGE_LENGTH, 0);
            sensor.bitsPerSample = raw.intValue(TiffSamples.BITS_PER_SAMPLE, 12);
            sensor.compression = "uncompressed";
            var offset = reader.base() + raw.longs(TiffSamples.STRIP_OFFSETS)[0];
            var width = sensor.width;
            var little = reader.little();
            var bits = sensor.bitsPerSample;
            sensor.reader = (plane, limits) -> {
                var row = new byte[width * 2];
                for (var y = 0; y < plane.height; y++) {
                    var read = reader.readAvailable(offset + (long) y * row.length, row, 0, row.length);
                    if (read <= 0) {
                        return;
                    }
                    Unpack.words(row, 0, read, little, bits, plane.integers, y * width, width);
                }
            };
        } else if (compression == PACKBITS_LABEL && packed(raw)) {
            sensor.width = raw.intValue(TiffSamples.IMAGE_WIDTH, 0);
            sensor.height = raw.intValue(TiffSamples.IMAGE_LENGTH, 0);
            sensor.bitsPerSample = 12;
            sensor.compression = "uncompressed";
            var offset = reader.base() + raw.longs(TiffSamples.STRIP_OFFSETS)[0];
            var width = sensor.width;
            sensor.reader = (plane, limits) -> {
                var stride = width * 3 / 2;
                LimitChecks.checkAllocation(limits, stride);
                var row = new byte[stride];
                for (var y = 0; y < plane.height; y++) {
                    var read = reader.readAvailable(offset + (long) y * stride, row, 0, stride);
                    if (read <= 0) {
                        return;
                    }
                    Unpack.msbFirst(row, 0, read, 12, plane.integers, y * width, width);
                }
            };
        } else {
            try {
                sensor.reader = TiffSamples.describe(raw, sensor);
            } catch (MediaException.Unsupported exception) {
                unsupported(file, exception.getMessage());
                return null;
            }
        }
        sensor.cfa = TiffEpParser.cfa(raw, exif);
        var monochrome = raw.intValue(TiffSamples.PHOTOMETRIC, 0) == DngParser.PHOTOMETRIC_LINEAR_RAW
                && raw.intValue(TiffSamples.SAMPLES_PER_PIXEL, 1) == 1;
        if (sensor.cfa == null && !monochrome) {
            unsupported(file, "the PEF raw image has no CFA pattern");
            return null;
        }
        if (monochrome) {
            sensor.cfa = null;
        }
        if (note != null) {
            levels(note, sensor, file);
        }
        return sensor;
    }

    /**
     * Records why a file cannot be decoded.
     *
     * @param file the description to fill, or {@code null}
     * @param reason the missing feature
     */
    private static void unsupported(RawFile file, String reason) {
        if (file != null) {
            file.unsupported = reason;
        }
    }

    /**
     * Returns the frames of a pixel-shift file: the raw directory and the other directories of a color
     * filter array image of its size, compression and bit depth, in the order the file stores them.
     *
     * @param tree the directories
     * @param raw the raw image directory
     * @return the directories, the raw directory first
     */
    private static List<Ifd> pixelShiftFrames(TiffTree tree, Ifd raw) {
        var result = new ArrayList<Ifd>();
        result.add(raw);
        for (var ifd : tree.directories) {
            if (ifd != raw && ifd.intValue(TiffSamples.PHOTOMETRIC, 0) == DngParser.PHOTOMETRIC_CFA
                    && ifd.longValue(TiffSamples.IMAGE_WIDTH, 0) == raw.longValue(TiffSamples.IMAGE_WIDTH, -1)
                    && ifd.longValue(TiffSamples.IMAGE_LENGTH, 0) == raw.longValue(TiffSamples.IMAGE_LENGTH, -1)
                    && ifd.intValue(TiffSamples.COMPRESSION, 1) == raw.intValue(TiffSamples.COMPRESSION, 1)
                    && ifd.intValue(TiffSamples.BITS_PER_SAMPLE, 0) == raw.intValue(TiffSamples.BITS_PER_SAMPLE, 0)) {
                result.add(ifd);
            }
        }
        return result;
    }

    /**
     * Returns whether an uncompressed directory of fewer than 16 bits per sample stores its samples
     * in whole 16-bit words: one strip of exactly two bytes per photosite.
     *
     * @param raw the raw image directory
     * @return whether the data is 16-bit words
     */
    private static boolean words(Ifd raw) {
        var width = raw.longValue(TiffSamples.IMAGE_WIDTH, 0);
        var height = raw.longValue(TiffSamples.IMAGE_LENGTH, 0);
        var bits = raw.intValue(TiffSamples.BITS_PER_SAMPLE, 0);
        var offsets = raw.longs(TiffSamples.STRIP_OFFSETS);
        var counts = raw.longs(TiffSamples.STRIP_BYTE_COUNTS);
        return bits > 8 && bits < 16 && width > 0 && height > 0 && offsets != null && offsets.length == 1
                && counts != null && counts.length == 1 && counts[0] == width * height * 2;
    }

    /**
     * Returns whether a directory labeled with compression 32773 holds 12-bit samples packed
     * without compression: one strip of exactly one and a half bytes per photosite.
     *
     * @param raw the raw image directory
     * @return whether the data is packed 12-bit samples
     */
    private static boolean packed(Ifd raw) {
        var width = raw.longValue(TiffSamples.IMAGE_WIDTH, 0);
        var height = raw.longValue(TiffSamples.IMAGE_LENGTH, 0);
        var offsets = raw.longs(TiffSamples.STRIP_OFFSETS);
        var counts = raw.longs(TiffSamples.STRIP_BYTE_COUNTS);
        return raw.intValue(TiffSamples.BITS_PER_SAMPLE, 0) == 12 && width > 0 && height > 0 && width % 2 == 0
                && offsets != null && offsets.length == 1 && counts != null && counts.length == 1
                && counts[0] == width * height * 3 / 2;
    }

    /**
     * Converts the table of tag {@code 0x0220} to sixteen code counts followed by the values.
     *
     * @param table the tag's bytes: a 14-byte header, then the 16-bit codes and their lengths
     * @param little whether the maker note is little endian
     * @return the table in the {@code DHT} form
     * @throws MediaException.Malformed if the codes are not a canonical prefix code
     */
    private static int[] huffman(byte[] table, boolean little) {
        var count = (table.length - 14) % 3 == 0 ? (table.length - 14) / 3 : 13;
        if (count < 13 || count > 16) {
            count = 13;
        }
        var codes = new int[count];
        var lengths = new int[count];
        var start = table.length - 3 * count;
        for (var index = 0; index < count; index++) {
            codes[index] = ByteArrays.u16(table, start + 2 * index, little);
            lengths[index] = table[start + 2 * count + index] & 0xFF;
            if (lengths[index] < 1 || lengths[index] > 12) {
                throw new MediaException.Malformed("invalid PEF Huffman code length " + lengths[index]);
            }
        }
        return Huffman.fromCodes(codes, lengths, 12);
    }

    /**
     * Decodes Pentax-compressed sensor data.
     *
     * @param reader the TIFF structure
     * @param offset the absolute offset of the data
     * @param length the length of the data
     * @param spec the Huffman table
     * @param maximum the largest sample value
     * @param plane the plane to fill
     * @param limits the resource bounds
     */
    private static void decompress(TiffReader reader, long offset, long length, int[] spec, int maximum,
                                   SensorPlane plane, MediaLimits limits) {
        LimitChecks.checkAllocation(limits, length);
        var data = reader.bytes(offset, Math.min(length, reader.size() < 0 ? length : reader.size() - offset));
        var bits = new MsbBits(data, 0, data.length);
        var table = new MsbBits.Table(spec);
        var vertical = new int[4];
        var out = plane.integers;
        var width = plane.width;
        for (var row = 0; row < plane.height; row++) {
            var even = 0;
            var odd = 0;
            var line = row * width;
            var parity = (row & 1) * 2;
            for (var column = 0; column < width; column++) {
                var size = bits.decode(table);
                var difference = bits.read(size);
                if (size > 0 && (difference & (1 << (size - 1))) == 0) {
                    difference -= (1 << size) - 1;
                }
                int value;
                if (column < 2) {
                    value = Math.clamp(vertical[parity + column] + difference, 0, maximum);
                    vertical[parity + column] = value;
                    if (column == 0) {
                        even = value;
                    } else {
                        odd = value;
                    }
                } else if ((column & 1) == 0) {
                    value = Math.clamp(even + difference, 0, maximum);
                    even = value;
                } else {
                    value = Math.clamp(odd + difference, 0, maximum);
                    odd = value;
                }
                out[line + column] = (short) value;
            }
            if (bits.exhausted()) {
                return;
            }
        }
    }

    /**
     * Finds the maker note's directory.
     *
     * @param exif the EXIF directory, or {@code null}
     * @param reader the TIFF structure
     * @return the directory, or {@code null}
     */
    private static Ifd makerNote(Ifd exif, TiffReader reader) {
        if (exif == null) {
            return null;
        }
        var position = exif.valuePosition(TiffTree.MAKER_NOTE);
        if (position < 0) {
            return null;
        }
        var head = new byte[10];
        if (reader.readAvailable(position, head, 0, 10) < 10) {
            return null;
        }
        try {
            if (head[0] == 'A' && head[1] == 'O' && head[2] == 'C' && head[3] == 0) {
                var little = head[4] == 'I';
                return reader.with(reader.base(), little).directory(position + 6 - reader.base());
            }
            if (new String(head, 0, 7, StandardCharsets.ISO_8859_1).equals("PENTAX ")) {
                var little = head[8] == 'I';
                return reader.with(position, little).directory(10);
            }
        } catch (MediaException.Malformed exception) {
            return null;
        }
        return null;
    }

    /**
     * Reads the black levels and white balance of the maker note.
     *
     * @param note the maker note's directory
     * @param sensor the sensor description to fill
     * @param file the description to fill with the white balance, or {@code null}
     */
    private static void levels(Ifd note, Sensor sensor, RawFile file) {
        var black = note.longs(0x200);
        if (black != null && black.length >= 4) {
            var values = new double[4];
            for (var index = 0; index < 4; index++) {
                values[index] = black[index];
            }
            sensor.blackRows = 2;
            sensor.blackColumns = 2;
            sensor.black = values;
        }
        var white = note.longs(0x201);
        if (file != null && white != null && white.length >= 4 && white[0] > 0 && white[1] > 0 && white[2] > 0 && white[3] > 0) {
            var green = (white[1] + white[2]) / 2.0;
            file.color.asShotNeutral = new double[]{green / white[0], 1, green / white[3]};
        }
    }
}
