package com.github.auties00.smalt.image.raw.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.internal.io.ByteArrays;
import com.github.auties00.smalt.internal.limits.LimitChecks;

import java.util.regex.Pattern;

/**
 * Parses a Nikon NEF or NRW file: a TIFF/EP structure whose largest color filter array directory
 * holds the sensor data, uncompressed or with Nikon's compression (TIFF compression 34713), and
 * whose maker note holds the compression parameters, white balance and black level.
 *
 * <p>The maker note (a TIFF structure of its own after the {@code Nikon} signature) provides tag
 * {@code 0x0096}, the compression data: two version bytes, the four initial vertical predictors,
 * the size of the linearization curve and its values, and for some lossy versions the split row;
 * tag {@code 0x000C}, the red and blue white balance multipliers; and tag {@code 0x003D}, the black
 * level of each CFA channel. The compressed data is a single stream of Huffman-coded differences,
 * one per photosite row by row: each photosite is predicted from the previous one of the same
 * color in its row, the first two of a row from the first two of the row two above, and the
 * decoded value indexes the linearization curve. A lossy stream with a split row switches from
 * that row on to a second table whose codes leave the low bits of large differences out.
 *
 * @implNote This implementation follows the structure as the ExifTool tag documentation and Bill
 * Claff's description of NEF compression present it. The Huffman tables of
 * {@link NikonTables}, the meaning of the split row, the clamping of the predictors and
 * the curve of version {@code 0x44 0x40} were worked out from CC0 sample files against
 * the values Adobe DNG Converter stores for them, which this decoder reproduces exactly
 * before linearization. The linear interpolation of the sampled curves matches Adobe's
 * tables within 3 levels for version {@code 0x44 0x40} but runs up to 1% of the white
 * level above them for version {@code 0x44 0x20}. Nikon's high-efficiency (HE and HE*)
 * compression is not supported. The interlaced rows of the {@code E}-series Coolpix
 * models were found by matching the rows of three CC0 files (E5700, E8400, E8800) with
 * those the converter stores, and the place of their white balance by finding the as-shot
 * neutral the converter stores among the maker note's values (E8400, E8800).
 */
final class NefParser {
    /**
     * The TIFF compression code of Nikon's compression.
     */
    private static final int NIKON_COMPRESSION = 34713;

    /**
     * The size of the Coolpix maker note block (tag {@code 0x0014}) that holds the white balance.
     */
    private static final int COOLPIX_BALANCE_SIZE = 2560;

    /**
     * The model names of the Coolpix cameras whose NEF files store interlaced rows.
     */
    private static final Pattern COOLPIX = Pattern.compile("E\\d{4}");

    /**
     * Prevents instantiation.
     */
    private NefParser() {
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
            file.unsupported = "the NEF file holds no raw image";
            return;
        }
        var note = makerNote(tree);
        var compression = raw.intValue(TiffSamples.COMPRESSION, 1);
        var sensor = new Sensor();
        if (compression == NIKON_COMPRESSION) {
            Compressed compressed;
            try {
                compressed = compressed(reader, raw, note);
            } catch (MediaException.Unsupported exception) {
                file.unsupported = exception.getMessage();
                return;
            }
            sensor.width = compressed.width();
            sensor.height = compressed.height();
            sensor.bitsPerSample = compressed.bits();
            sensor.compression = compressed.lossless() ? "Nikon lossless" : "Nikon lossy";
            sensor.reader = (plane, limits) -> decompress(reader, compressed, compressed.curve(), plane, limits);
            var maximum = 0;
            for (var value : compressed.curve()) {
                maximum = Math.max(maximum, value);
            }
            sensor.white = new double[]{maximum};
        } else {
            try {
                sensor.reader = uncompressed(raw, sensor);
                if (interlaced(file)) {
                    // TODO: render the cyan, yellow, green and magenta E5700: it needs color matrices, and its CFA tag
                    //  (YCGM) is one row off the pattern Adobe DNG Converter stores for it (GMYC)
                    sensor.reader = interlaced(sensor.reader);
                }
            } catch (MediaException.Unsupported exception) {
                file.unsupported = exception.getMessage();
                return;
            }
        }
        sensor.cfa = TiffEpParser.cfa(raw, tree.exif);
        if (sensor.cfa == null) {
            file.unsupported = "the NEF raw image has no CFA pattern";
            return;
        }
        if (note != null) {
            levels(note, sensor, file);
        }
        sensor.defaultCrop = previewCrop(file, sensor);
        file.sensor = sensor;
    }

    /**
     * Reads the parameters of a Nikon-compressed image from its directory and the maker note.
     *
     * <p>The compression data (maker note tag {@code 0x0096}) starts with two version bytes:
     * {@code 0x46} for lossless compression, {@code 0x44} for lossy compression. Lossy data of
     * version {@code 0x44 0x20} or {@code 0x44 0x40} gives at offset 562 the split row, from which
     * on the stream switches to the coarser table of its bit depth; zero means no split.
     *
     * @param reader the TIFF structure
     * @param raw the raw image directory
     * @param note the maker note's directory, or {@code null}
     * @return the parameters
     * @throws MediaException.Unsupported if the file has no compression data
     * @throws MediaException.Malformed if the image dimensions or data are invalid
     */
    static Compressed compressed(TiffReader reader, Ifd raw, Ifd note) {
        var width = raw.intValue(TiffSamples.IMAGE_WIDTH, 0);
        var height = raw.intValue(TiffSamples.IMAGE_LENGTH, 0);
        var bits = raw.intValue(TiffSamples.BITS_PER_SAMPLE, 12);
        if (width <= 0 || height <= 0 || (bits != 12 && bits != 14)) {
            throw new MediaException.Malformed("invalid NEF raw image of " + width + "x" + height + "x" + bits);
        }
        var data = note == null ? null : note.bytes(0x96);
        if (data == null || data.length < 12) {
            throw new MediaException.Unsupported("raw", "the NEF file has no compression data");
        }
        var little = note.reader().little();
        var version0 = data[0] & 0xFF;
        var version1 = data[1] & 0xFF;
        var lossless = version0 == 0x46;
        var predictors = new int[4];
        for (var index = 0; index < 4; index++) {
            predictors[index] = ByteArrays.u16(data, 2 + 2 * index, little);
        }
        var curve = curve(data, little, version0, version1, bits);
        var split = 0;
        if (version0 == 0x44 && (version1 == 0x20 || version1 == 0x40) && data.length >= 564) {
            split = ByteArrays.u16(data, 562, little);
        }
        var offsets = raw.longs(TiffSamples.STRIP_OFFSETS);
        var counts = raw.longs(TiffSamples.STRIP_BYTE_COUNTS);
        if (offsets == null || counts == null || offsets.length == 0 || counts.length == 0) {
            throw new MediaException.Malformed("NEF raw image without data");
        }
        return new Compressed(reader.base() + offsets[0], counts[0], width, height, bits, lossless, predictors, split, curve);
    }

    /**
     * Returns the default crop of a Nikon file: the size of its full-size preview, the camera's
     * own output, anchored at the sensor's top-left photosite, which leaves out the padding
     * columns and rows some models store past the image.
     *
     * @param file the description, with its previews
     * @param sensor the sensor description
     * @return the crop as x, y, width and height, or {@code null} when no preview is nearly as
     * large as the sensor image
     */
    static double[] previewCrop(RawFile file, Sensor sensor) {
        RawFile.Preview largest = null;
        for (var preview : file.previews) {
            if (largest == null || (long) preview.width() * preview.height() > (long) largest.width() * largest.height()) {
                largest = preview;
            }
        }
        if (largest == null || largest.width() > sensor.width || largest.height() > sensor.height
                || largest.width() < sensor.width * 0.9 || largest.height() < sensor.height * 0.9) {
            return null;
        }
        return new double[]{0, 0, largest.width(), largest.height()};
    }

    /**
     * Returns whether a file stores its rows interlaced: the Coolpix models named {@code E} and
     * four digits, which write the even rows first and then the odd ones.
     *
     * @param file the description, its model read
     * @return whether the rows are interlaced
     */
    private static boolean interlaced(RawFile file) {
        return file.model != null && COOLPIX.matcher(file.model.strip()).matches();
    }

    /**
     * Wraps a reader of interlaced rows: the first half of the stored rows, rounded up, are the
     * image's even rows and the rest its odd rows, each half top to bottom.
     *
     * @param reader the reader of the rows in stored order
     * @return the reader of the rows in image order
     */
    private static Sensor.Reader interlaced(Sensor.Reader reader) {
        return (plane, limits) -> {
            reader.read(plane, limits);
            var stride = plane.width * plane.samplesPerPixel;
            LimitChecks.checkAllocation(limits, (long) stride * plane.height * 2);
            var stored = plane.integers.clone();
            var even = (plane.height + 1) / 2;
            for (var row = 0; row < plane.height; row++) {
                var source = row < even ? row : row - even;
                var target = row < even ? 2 * source : 2 * source + 1;
                System.arraycopy(stored, row * stride, plane.integers, target * stride, stride);
            }
        };
    }

    /**
     * Describes an uncompressed NEF image: 16-bit words when the strips hold two bytes per
     * photosite, little endian as Nikon writes them, and TIFF bit packing otherwise.
     *
     * @param raw the image directory
     * @param sensor the sensor description to fill
     * @return the reader
     */
    private static Sensor.Reader uncompressed(Ifd raw, Sensor sensor) {
        var reader = TiffSamples.describe(raw, sensor);
        var counts = raw.longs(TiffSamples.STRIP_BYTE_COUNTS);
        var total = 0L;
        if (counts != null) {
            for (var count : counts) {
                total += count;
            }
        }
        if (sensor.bitsPerSample > 8 && sensor.bitsPerSample < 16 && total >= (long) sensor.width * sensor.height * 2) {
            var blocks = TiffSamples.blocks(raw, sensor.width, sensor.height);
            var source = raw.reader().source();
            var bits = sensor.bitsPerSample;
            sensor.compression = "uncompressed";
            return (plane, limits) -> {
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
                        Unpack.words(data, start, data.length, raw.reader().little(), bits, plane.integers, (block.y() + row) * plane.width,
                                plane.width);
                    }
                }
            };
        }
        return reader;
    }

    /**
     * Expands the linearization curve of the compression data to every value the predictor can
     * take.
     *
     * <p>Lossless data (version {@code 0x46}) has no curve. Lossy data of version {@code 0x44 0x20}
     * gives the curve at evenly spaced points of the whole value range, and version
     * {@code 0x44 0x40} at evenly spaced points of its lowest quarter, the values above holding
     * the last point; the curve is interpolated linearly between the points. Other lossy data
     * gives one curve value per decoded value.
     *
     * @param data the compression data
     * @param little whether its integers are little endian
     * @param version0 the first version byte
     * @param version1 the second version byte
     * @param bits the bits per sample
     * @return the curve, of {@code 2^bits} entries
     */
    private static int[] curve(byte[] data, boolean little, int version0, int version1, int bits) {
        var size = 1 << bits;
        var curve = new int[size];
        var entries = ByteArrays.u16(data, 10, little);
        if (version0 == 0x46 || entries < 2 || 12 + 2L * entries > data.length) {
            for (var index = 0; index < size; index++) {
                curve[index] = index;
            }
            return curve;
        }
        var values = new int[entries];
        for (var index = 0; index < entries; index++) {
            values[index] = ByteArrays.u16(data, 12 + 2 * index, little);
        }
        // TODO: find the interpolation Adobe DNG Converter applies to the 0x44 0x20 curves; its tables run up to 1%
        //  below this linear interpolation in the highlights
        var sampled = version0 == 0x44 && (version1 == 0x20 || version1 == 0x40);
        var step = sampled ? (version1 == 0x40 ? size >> 2 : size) / (entries - 1) : 0;
        if (step > 0) {
            for (var index = 0; index < size; index++) {
                var lower = Math.min(index / step, entries - 1);
                var fraction = lower == entries - 1 ? 0 : index % step;
                var upper = Math.min(lower + 1, entries - 1);
                curve[index] = (values[lower] * (step - fraction) + values[upper] * fraction) / step;
            }
        } else {
            for (var index = 0; index < size; index++) {
                curve[index] = values[Math.min(index, entries - 1)];
            }
        }
        return curve;
    }

    /**
     * Decodes Nikon-compressed sensor data.
     *
     * <p>Each photosite is a Huffman code of {@link NikonTables} followed by the bits of its
     * difference. The low four bits of the code's value give the difference category (the bit
     * length of the difference, as in a JPEG DC table) and the high four bits the number of low
     * bits the stream leaves out, which only the tables after the split row use: the stream then
     * holds the category minus that many bits, and the decoder shifts the difference back and
     * adds half of the dropped range away from zero. The running value of every predictor is
     * clamped to the curve's range.
     *
     * @param reader the TIFF structure
     * @param compressed the parameters of the image
     * @param curve the curve mapping decoded values to stored samples
     * @param plane the plane to fill
     * @param limits the resource bounds
     * @throws MediaException.Unsupported if the stream uses a Huffman code whose value the
     * tables do not know
     */
    static void decompress(TiffReader reader, Compressed compressed, int[] curve, SensorPlane plane, MediaLimits limits) {
        var length = compressed.length();
        var offset = compressed.offset();
        LimitChecks.checkAllocation(limits, length);
        var data = reader.bytes(offset, Math.min(length, reader.size() < 0 ? length : reader.size() - offset));
        var bits = new MsbBits(data, 0, data.length);
        var table = (compressed.bits() == 14 ? 3 : 0) + (compressed.lossless() ? 2 : 0);
        var huffman = new MsbBits.Table(NikonTables.TABLES[table]);
        var vertical = compressed.predictors().clone();
        var out = plane.integers;
        var width = plane.width;
        var last = curve.length - 1;
        var split = compressed.lossless() ? 0 : compressed.splitRow();
        for (var row = 0; row < plane.height; row++) {
            if (row == split && row > 0) {
                huffman = new MsbBits.Table(NikonTables.TABLES[table + 1]);
            }
            var even = 0;
            var odd = 0;
            var line = row * width;
            var parity = (row & 1) * 2;
            for (var column = 0; column < width; column++) {
                var code = bits.decode(huffman);
                if (code < 0) {
                    throw new MediaException.Unsupported("raw", "the NEF stream uses a Huffman code no sample file has shown");
                }
                var shift = code >>> 4;
                var size = (code & 15) - shift;
                var difference = bits.read(size);
                if (size > 0 && (difference & (1 << (size - 1))) == 0) {
                    difference -= (1 << size) - 1;
                }
                if (shift > 0 && difference != 0) {
                    difference = difference * (1 << shift) + (difference > 0 ? 1 << (shift - 1) : -(1 << (shift - 1)));
                }
                int value;
                if (column < 2) {
                    value = Math.clamp(vertical[parity + column] + difference, 0, last);
                    vertical[parity + column] = value;
                    if (column == 0) {
                        even = value;
                    } else {
                        odd = value;
                    }
                } else if ((column & 1) == 0) {
                    even = Math.clamp(even + difference, 0, last);
                    value = even;
                } else {
                    odd = Math.clamp(odd + difference, 0, last);
                    value = odd;
                }
                out[line + column] = (short) curve[value];
            }
            if (bits.exhausted()) {
                return;
            }
        }
    }

    /**
     * Holds the parameters of a Nikon-compressed image.
     *
     * @param offset the absolute offset of the data
     * @param length the length of the data
     * @param width the width in photosites, padding columns included
     * @param height the height in photosites
     * @param bits the bits per sample, 12 or 14
     * @param lossless whether the compression is lossless
     * @param predictors the initial vertical predictors, two per row parity
     * @param splitRow the row from which a lossy stream uses its second table, or {@code 0}
     * @param curve the linearization curve, of {@code 2^bits} entries
     */
    record Compressed(long offset, long length, int width, int height, int bits, boolean lossless, int[] predictors,
                      int splitRow, int[] curve) {
    }

    /**
     * Finds the maker note's own TIFF structure.
     *
     * @param tree the directories
     * @return the maker note's first directory, or {@code null}
     */
    static Ifd makerNote(TiffTree tree) {
        if (tree.exif == null) {
            return null;
        }
        var position = tree.exif.valuePosition(TiffTree.MAKER_NOTE);
        if (position < 0) {
            return null;
        }
        var reader = tree.first.reader();
        var head = new byte[10];
        if (reader.readAvailable(position, head, 0, 10) < 10) {
            return null;
        }
        if (head[0] != 'N' || head[1] != 'i' || head[2] != 'k' || head[3] != 'o' || head[4] != 'n' || head[5] != 0) {
            return null;
        }
        try {
            var note = TiffReader.open(reader.source(), position + 10, reader.limits());
            return note.directory(note.firstDirectory());
        } catch (MediaException.Malformed exception) {
            return null;
        }
    }

    /**
     * Reads the white balance and black level of the maker note.
     *
     * <p>The white balance is the red and blue multipliers of tag {@code 0x000C} or, in the
     * Coolpix maker notes that lack it, the big-endian 16-bit red and blue multipliers in 1/256
     * units at bytes 14 and 16 of the 2560-byte tag {@code 0x0014}. The black level of tag
     * {@code 0x003D} is given on a 14-bit scale whatever the depth of the
     * file, so a 12-bit file's levels are divided by four (Adobe DNG Converter stores the same
     * levels for the CC0 samples).
     *
     * @param note the maker note's directory
     * @param sensor the sensor description to fill
     * @param file the description to fill
     */
    private static void levels(Ifd note, Sensor sensor, RawFile file) {
        var balance = note.doubles(0x0C);
        if (balance != null && balance.length >= 2 && balance[0] > 0 && balance[1] > 0) {
            file.color.asShotNeutral = new double[]{1 / balance[0], 1, 1 / balance[1]};
        } else {
            var coolpix = note.bytes(0x14);
            if (coolpix != null && coolpix.length == COOLPIX_BALANCE_SIZE) {
                var red = ByteArrays.u16be(coolpix, 14);
                var blue = ByteArrays.u16be(coolpix, 16);
                if (red > 0 && blue > 0) {
                    file.color.asShotNeutral = new double[]{256.0 / red, 1, 256.0 / blue};
                }
            }
        }
        var black = note.longs(0x3D);
        if (black != null && black.length >= 4) {
            var values = new double[4];
            var scale = Math.scalb(1.0, Math.min(sensor.bitsPerSample, 14) - 14);
            for (var index = 0; index < 4; index++) {
                values[index] = black[index] * scale;
            }
            sensor.blackRows = 2;
            sensor.blackColumns = 2;
            sensor.black = values;
        }
    }
}
