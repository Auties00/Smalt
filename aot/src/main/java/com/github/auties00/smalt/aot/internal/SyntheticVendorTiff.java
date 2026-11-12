package com.github.auties00.smalt.aot.internal;

import com.github.auties00.smalt.aot.internal.TiffParts.Entry;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.jpeg.JpegEncoder;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongFunction;

/**
 * Writes the TIFF-structured camera raw files the training workloads decode, deterministically, with the sensor
 * data derived from a synthetic picture: Nikon NEF, Canon CR2, Pentax PEF, Sony ARW, Olympus ORF, Panasonic RW2,
 * Epson ERF, Mamiya MEF, Samsung SRW, Kodak DCS, Leaf MOS and Hasselblad 3FR files in the codings of their
 * decoders.
 *
 * <p>Each file holds only the directories and maker note records its decoder reads. The coders simulate their
 * decoders, so each {@link RawSample} carries the photosites the decoder must produce, which makes the files a
 * test of the decoders as well as training input.
 */
final class SyntheticVendorTiff {
    /**
     * The TIFF photometric interpretation of a color filter array image.
     */
    private static final int CFA = 32803;

    /**
     * The value of a Huffman table entry whose code is not known; the coders never write it.
     */
    private static final int UNKNOWN = -1;

    /**
     * The Huffman tables of Nikon's NEF compression, in the form of a JPEG {@code DHT} segment, the high four bits
     * of a value the low bits the stream leaves out and the low four bits the difference category: 12-bit lossy,
     * 12-bit lossy after the split row, 12-bit lossless, 14-bit lossy, 14-bit lossy after the split row and 14-bit
     * lossless; a copy of the decoder's tables.
     */
    private static final int[][] NIKON_TABLES = {
            {0, 1, 5, 1, 1, 1, 1, 1, 2, 0, 0, 0, 0, 0, 0, 0,
                    5, 4, 3, 6, 2, 7, 1, 0, 8, 9, UNKNOWN, 10, UNKNOWN},
            {0, 1, 5, 1, 1, 1, 1, 2, 0, 0, 0, 0, 0, 0, 0, 0,
                    0x39, 0x5A, 0x38, 0x27, 0x16, 5, 4, 3, 2, 1, 0, UNKNOWN},
            {0, 1, 4, 2, 3, 1, 2, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                    5, 4, 6, 3, 7, 2, 8, 1, 9, 0, 10, 11, 12},
            {0, 1, 4, 3, 1, 1, 1, 1, 2, 0, 0, 0, 0, 0, 0, 0,
                    5, 6, 4, 7, 8, 3, 9, 2, 1, 0, 10, 11, 12, UNKNOWN},
            {0, 1, 5, 1, 1, 1, 1, 1, 1, 2, 0, 0, 0, 0, 0, 0,
                    8, 0x5C, 0x4B, 0x3A, 0x29, 7, 6, 5, 4, 3, 2, 1, 0, UNKNOWN},
            {0, 1, 4, 2, 2, 3, 1, 2, 0, 0, 0, 0, 0, 0, 0, 0,
                    7, 6, 8, 5, 9, 4, 10, 3, 11, 12, 2, 0, 1, 13, 14}
    };

    /**
     * The number of points of the linearization curve of the lossy NEF file.
     */
    private static final int NIKON_CURVE_POINTS = 257;

    /**
     * The black level of the NEF files, on the 14-bit scale their maker note uses.
     */
    private static final int NIKON_BLACK = 600;

    /**
     * The code lengths of the Pentax Huffman table, by difference category; a prefix code of at most 12 bits.
     */
    private static final int[] PENTAX_LENGTHS = {3, 3, 3, 3, 3, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12};

    /**
     * The code lengths of the Panasonic version 8 Huffman table, by difference category: a complete prefix code.
     */
    private static final int[] PANASONIC_LENGTHS = {3, 2, 2, 3, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 14};

    /**
     * The Sony cRAW tone curve thresholds, on the 14-bit scale: four times the curve's knots.
     */
    private static final int[] SONY_THRESHOLDS = {8000, 10400, 12900, 14100};

    /**
     * The size of a chunk of the Panasonic version 4 and 5 layout.
     */
    private static final int PANASONIC_CHUNK = 0x4000;

    /**
     * The stream bytes a Panasonic chunk stores at its end.
     */
    private static final int PANASONIC_SPLIT = 0x2008;

    /**
     * The level by which Panasonic version 6 values sit above the black level.
     */
    private static final int PANASONIC_PEDESTAL = 15;

    /**
     * Prevents instantiation.
     */
    private SyntheticVendorTiff() {
        throw new AssertionError();
    }

    /**
     * A synthetic raw file and the sensor image its decoder must extract.
     *
     * @param width the width of the extracted image: the default crop or active area
     * @param height the height of the extracted image
     * @param stored the photosites of the extracted image as the decoder reads them, before the black level is
     * subtracted, row by row
     * @param black the black level of each position of the 2x2 color filter array, in raster order from the
     * image's origin
     * @param white the white level
     */
    record RawSample(byte[] file, int width, int height, int[] stored, double[] black, double white) {
    }

    /**
     * The codings of the synthetic NEF files.
     */
    enum Nikon {
        /**
         * 12-bit Huffman-coded lossless data.
         */
        LOSSLESS_12,

        /**
         * 14-bit Huffman-coded lossless data.
         */
        LOSSLESS_14,

        /**
         * 12-bit lossy data of compression version {@code 0x44 0x20}: a sampled linearization curve and, from the
         * middle row on, the coarser table that leaves the low bits of large differences out.
         */
        LOSSY_12,

        /**
         * 14-bit samples in little-endian 16-bit words.
         */
        UNCOMPRESSED
    }

    /**
     * The codings of the synthetic RW2 files, by Panasonic raw format version.
     */
    enum Panasonic {
        /**
         * Version 4: 12-bit data, 14 photosites per 16-byte block in the chunk layout.
         */
        V4(4, 14, 12),

        /**
         * Version 5: nine 14-bit photosites per 16-byte block in the chunk layout.
         */
        V5(5, 9, 14),

        /**
         * Version 6: 14-bit data, eleven photosites per 16-byte block.
         */
        V6(6, 11, 14),

        /**
         * Version 8: two Huffman-coded vertical tiles of 14-bit data.
         */
        V8(8, 2, 14);

        /**
         * The value of tag {@code 0x002D}.
         */
        final int version;

        /**
         * The photosites of a group, which the width of the sensor is a multiple of.
         */
        final int group;

        /**
         * The bits per sample.
         */
        final int bits;

        /**
         * Constructs a coding.
         *
         * @param version the raw format version
         * @param group the photosites of a group
         * @param bits the bits per sample
         */
        Panasonic(int version, int group, int bits) {
            this.version = version;
            this.group = group;
            this.bits = bits;
        }
    }

    /**
     * Writes a NEF file of an RGGB mosaic of a picture, with a maker note of white balance, black levels and
     * compression data.
     *
     * @param picture the picture, whose width and height are even
     * @param kind the coding
     * @return the file and its sensor image
     */
    static RawSample nef(RgbaPicture picture, Nikon kind) {
        var width = picture.width();
        var height = picture.height();
        var bits = kind == Nikon.LOSSLESS_12 || kind == Nikon.LOSSY_12 ? 12 : 14;
        var last = (1 << bits) - 1;
        var black = NIKON_BLACK >> (14 - bits);
        var tiff = new TiffParts(true);
        int[] stored;
        byte[] strip;
        byte[] compression = null;
        double white;
        if (kind == Nikon.UNCOMPRESSED) {
            stored = SyntheticVendorRaw.mosaic(picture, black, last - black);
            strip = words(stored, ByteOrder.LITTLE_ENDIAN);
            white = last;
        } else {
            var lossy = kind == Nikon.LOSSY_12;
            var predictor = 1 << (bits - 1);
            var points = lossy ? nikonPoints() : new int[0];
            var curve = lossy ? nikonCurve(points, bits) : identity(1 << bits);
            var targets = SyntheticVendorRaw.mosaic(picture, lossy ? 0 : black, lossy ? last : last - black);
            var table = (bits == 14 ? 3 : 0) + (lossy ? 0 : 2);
            var split = lossy ? height / 2 : 0;
            var decoded = new int[targets.length];
            strip = nikonStream(targets, width, height, last, predictor, NIKON_TABLES[table],
                    lossy ? NIKON_TABLES[table + 1] : null, split, decoded);
            stored = new int[decoded.length];
            var maximum = 0;
            for (var index = 0; index < decoded.length; index++) {
                stored[index] = curve[decoded[index]];
            }
            for (var value : curve) {
                maximum = Math.max(maximum, value);
            }
            white = maximum;
            var data = ByteBuffer.allocate(lossy ? 564 : 12).order(ByteOrder.LITTLE_ENDIAN);
            data.put((byte) (lossy ? 0x44 : 0x46)).put((byte) (lossy ? 0x20 : 0x30));
            for (var index = 0; index < 4; index++) {
                data.putShort((short) predictor);
            }
            data.putShort((short) points.length);
            for (var point : points) {
                data.putShort((short) point);
            }
            if (lossy) {
                data.putShort(562, (short) split);
            }
            compression = data.array();
        }
        var noteEntries = new ArrayList<Entry>();
        noteEntries.add(tiff.rationals(0x0C, 256, 512, 384, 256, 256));
        noteEntries.add(tiff.shorts(0x3D, NIKON_BLACK, NIKON_BLACK, NIKON_BLACK, NIKON_BLACK));
        if (compression != null) {
            noteEntries.add(TiffParts.undefined(0x96, compression));
        }
        var note = new ByteArrayOutputStream();
        note.writeBytes("Nikon\0".getBytes(StandardCharsets.US_ASCII));
        note.writeBytes(new byte[]{2, 0x10, 0, 0});
        note.writeBytes(tiff.header(42, 8));
        note.writeBytes(tiff.directory(noteEntries, 8, 0));
        var noteBytes = note.toByteArray();
        var compressionCode = kind == Nikon.UNCOMPRESSED ? 1 : 34713;
        var file = tiffEp(tiff, List.of(TiffParts.ascii(271, "NIKON CORPORATION"), TiffParts.ascii(272, "NIKON D7000")),
                List.of(), position -> noteBytes,
                offset -> rawDirectory(tiff, width, height, bits, compressionCode, offset, strip.length),
                strip);
        return sample(file, width, height, stored, 0, 0, width, height, uniform(black), white);
    }

    /**
     * Returns the points of the lossy NEF linearization curve: a quadratic rise to the 12-bit maximum.
     *
     * @return the points
     */
    private static int[] nikonPoints() {
        var points = new int[NIKON_CURVE_POINTS];
        for (var index = 0; index < points.length; index++) {
            var position = Math.min(index, NIKON_CURVE_POINTS - 2) / (double) (NIKON_CURVE_POINTS - 2);
            points[index] = (int) Math.round(4095 * position * position);
        }
        return points;
    }

    /**
     * Expands the points of a version {@code 0x44 0x20} NEF curve to every decoded value, as the decoder does:
     * value {@code i} lies at point {@code i * (n - 2) / (2^bits - 1)}, between whose neighbors the curve is
     * interpolated linearly and rounded half up.
     *
     * @param bits the bits per sample
     * @return the curve, of {@code 2^bits} entries
     */
    private static int[] nikonCurve(int[] points, int bits) {
        var size = 1 << bits;
        var curve = new int[size];
        var entries = points.length;
        var numerator = (long) entries - 2;
        var denominator = (long) size - 1;
        for (var index = 0; index < size; index++) {
            var position = index * numerator;
            var lower = position / denominator;
            if (lower >= entries - 1) {
                curve[index] = points[entries - 1];
                continue;
            }
            var remainder = position % denominator;
            var rise = points[(int) lower + 1] - points[(int) lower];
            curve[index] = points[(int) lower] + (int) Math.floorDiv(2 * rise * remainder + denominator, 2 * denominator);
        }
        return curve;
    }

    /**
     * Codes the photosites of a NEF image as Nikon's Huffman-coded differences: each from the previous photosite
     * of its color in the row, the first two of a row from the first two of the row two above; the running values
     * are clamped to the sample range. A difference no code reaches exactly is coded by the code whose value comes
     * nearest, and the decoded values record what the stream holds.
     *
     * @param targets the photosites to code, row by row
     * @param last the largest sample value
     * @param predictor the initial vertical predictor of every row parity and column
     * @param first the table of the rows before the split row
     * @param second the table from the split row on, or {@code null}
     * @param split the split row, or {@code 0}
     * @param decoded receives the values the stream decodes to
     * @return the stream, padded with zero bytes
     */
    private static byte[] nikonStream(int[] targets, int width, int height, int last, int predictor, int[] first,
                                      int[] second, int split, int[] decoded) {
        var bits = new BitWriter(false);
        var codes = codes(first);
        var vertical = new int[]{predictor, predictor, predictor, predictor};
        for (var row = 0; row < height; row++) {
            if (row == split && row > 0) {
                codes = codes(second);
            }
            var even = 0;
            var odd = 0;
            var parity = (row & 1) * 2;
            for (var column = 0; column < width; column++) {
                var prediction = column < 2 ? vertical[parity + column] : (column & 1) == 0 ? even : odd;
                var target = targets[row * width + column];
                var best = nearestNikon(codes, prediction, target, last);
                var value = Math.clamp(prediction + best[2], 0, last);
                bits.write(best[0], best[1]);
                if (best[4] > 0) {
                    bits.write(best[3], best[4]);
                }
                if (column < 2) {
                    vertical[parity + column] = value;
                    if (column == 0) {
                        even = value;
                    } else {
                        odd = value;
                    }
                } else if ((column & 1) == 0) {
                    even = value;
                } else {
                    odd = value;
                }
                decoded[row * width + column] = value;
            }
        }
        var out = new ByteArrayOutputStream();
        out.writeBytes(bits.bytes());
        out.writeBytes(new byte[16]);
        return out.toByteArray();
    }

    /**
     * Returns the Nikon code whose difference brings a prediction nearest a target.
     *
     * @param codes the codes of the table, by value
     * @param target the target value
     * @param last the largest sample value
     * @return the code, its length, the coded difference, the difference bits and their count
     */
    private static int[] nearestNikon(Map<Integer, int[]> codes, int prediction, int target, int last) {
        var wanted = target - prediction;
        int[] best = null;
        var bestError = Integer.MAX_VALUE;
        for (var entry : codes.entrySet()) {
            var shift = entry.getKey() >>> 4;
            var size = (entry.getKey() & 15) - shift;
            int difference;
            var raw = 0;
            if (size <= 0) {
                difference = 0;
                size = 0;
            } else {
                var half = shift > 0 ? 1 << (shift - 1) : 0;
                var magnitude = Math.abs(wanted);
                var steps = (int) Math.round((magnitude - half) / (double) (1 << shift));
                steps = Math.clamp(steps, 1 << (size - 1), (1 << size) - 1);
                difference = steps * (1 << shift) + half;
                if (wanted < 0) {
                    difference = -difference;
                    raw = -steps + (1 << size) - 1;
                } else {
                    raw = steps;
                }
            }
            var error = Math.abs(Math.clamp(prediction + difference, 0, last) - target);
            var length = entry.getValue()[1];
            if (best == null || error < bestError || (error == bestError && length + size < best[1] + best[4])) {
                best = new int[]{entry.getValue()[0], length, difference, raw, size};
                bestError = error;
            }
        }
        return best;
    }

    /**
     * Writes a CR2 file of an RGGB mosaic of a picture: a 14-bit lossless JPEG stream of two components cut into
     * three vertical slices, and a maker note giving the active area 16 photosites in from the left and right and
     * 8 from the top and bottom, the white balance and the levels.
     *
     * @param picture the picture, whose width is a multiple of 4 and at least 64 and whose height is even
     * @return the file and its sensor image
     */
    static RawSample cr2(RgbaPicture picture) {
        var width = picture.width();
        var height = picture.height();
        var black = 2048;
        var white = 15000;
        var stored = SyntheticVendorRaw.mosaic(picture, black, white - black);
        var sliceWidth = width / 8 * 3;
        var lastWidth = width - 2 * sliceWidth;
        var stream = new int[width * height];
        var at = 0;
        for (var slice = 0; slice < 3; slice++) {
            var left = slice * sliceWidth;
            var columns = slice < 2 ? sliceWidth : lastWidth;
            for (var y = 0; y < height; y++) {
                System.arraycopy(stored, y * width + left, stream, at, columns);
                at += columns;
            }
        }
        var strip = SyntheticImages.losslessScansJpeg(stream, width / 2, height, 2, 14, 1, false,
                new int[][]{{0, 1}});
        var tiff = new TiffParts(true);
        var color = new int[1273];
        color[0] = 9;
        color[63] = 2100;
        color[64] = 1024;
        color[65] = 1024;
        color[66] = 1600;
        for (var index = 0; index < 4; index++) {
            color[715 + index] = black;
        }
        color[720] = white;
        var left = 16;
        var top = 8;
        var noteEntries = List.of(tiff.shorts(0x00E0, 34, width, height, 0, 0, left, top, width - 1 - left,
                height - 1 - top), tiff.shorts(0x4001, color));
        // the maker note is the only value of the EXIF directory, so it starts right after the directory
        LongFunction<byte[]> note = position -> tiff.directory(noteEntries, position, 0);
        var parts = new ArrayList<TiffParts.Part>();
        parts.add(offsets -> {
            var header = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
            header.put(tiff.header(42, offsets[1])).put((byte) 'C').put((byte) 'R').put((byte) 2).put((byte) 0);
            return header.putInt((int) offsets[3]).array();
        });
        parts.add(offsets -> tiff.directory(List.of(TiffParts.ascii(271, "Canon"),
                TiffParts.ascii(272, "Canon EOS 5D Mark III"), tiff.longs(34665, offsets[2])), offsets[1], offsets[3]));
        parts.add(offsets -> exif(tiff, offsets[2], List.of(), note));
        parts.add(offsets -> tiff.directory(List.of(tiff.shorts(259, 6), tiff.longs(273, offsets[4]),
                tiff.longs(279, strip.length), tiff.shorts(0xC640, 2, sliceWidth, lastWidth)), offsets[3], 0));
        parts.add(offsets -> strip);
        return sample(TiffParts.write(parts), width, height, stored, left, top, width - 2 * left, height - 2 * top,
                uniform(black), white);
    }

    /**
     * Writes a PEF file of an RGGB mosaic of a picture, coded with Pentax's Huffman compression under the table
     * its maker note carries.
     *
     * @param picture the picture, whose width and height are even
     * @param bits the bits per sample, 12 or 14
     * @return the file and its sensor image
     */
    static RawSample pef(RgbaPicture picture, int bits) {
        var width = picture.width();
        var height = picture.height();
        var maximum = (1 << bits) - 1;
        var black = bits == 12 ? 128 : 512;
        var stored = SyntheticVendorRaw.mosaic(picture, black, maximum - black);
        var count = bits == 12 ? 13 : 15;
        var lengths = Arrays.copyOf(PENTAX_LENGTHS, count);
        var codes = canonical(lengths);
        var tiff = new TiffParts(true);
        var bitsOut = new BitWriter(false);
        var vertical = new int[4];
        for (var row = 0; row < height; row++) {
            var even = 0;
            var odd = 0;
            var parity = (row & 1) * 2;
            for (var column = 0; column < width; column++) {
                var value = stored[row * width + column];
                var prediction = column < 2 ? vertical[parity + column] : (column & 1) == 0 ? even : odd;
                var difference = value - prediction;
                var category = category(difference);
                bitsOut.write(codes[category], lengths[category]);
                bitsOut.write(magnitude(difference, category), category);
                if (column < 2) {
                    vertical[parity + column] = value;
                    if (column == 0) {
                        even = value;
                    } else {
                        odd = value;
                    }
                } else if ((column & 1) == 0) {
                    even = value;
                } else {
                    odd = value;
                }
            }
        }
        var strip = concat(bitsOut.bytes(), new byte[16]);
        var table = ByteBuffer.allocate(14 + 3 * count).order(ByteOrder.LITTLE_ENDIAN);
        table.position(14);
        for (var index = 0; index < count; index++) {
            table.putShort((short) (codes[index] << (12 - lengths[index])));
        }
        for (var index = 0; index < count; index++) {
            table.put((byte) lengths[index]);
        }
        var noteEntries = List.of(tiff.shorts(0x200, black, black, black, black),
                tiff.shorts(0x201, 9000, 4096, 4096, 6500), TiffParts.undefined(0x220, table.array()));
        // the AOC maker note's directory starts six bytes in and its offsets are relative to the file
        LongFunction<byte[]> note = position -> concat(new byte[]{'A', 'O', 'C', 0, 'I', 'I'},
                tiff.directory(noteEntries, position + 6, 0));
        var file = tiffEp(tiff, List.of(TiffParts.ascii(271, "PENTAX"), TiffParts.ascii(272, "PENTAX K-5")),
                List.of(), note, offset -> rawDirectory(tiff, width, height, bits, 65535, offset, strip.length), strip);
        return sample(file, width, height, stored, 0, 0, width, height, uniform(black), maximum);
    }

    /**
     * Writes an ARW file of an RGGB mosaic of a picture, with the black levels, white balance and tone curve
     * thresholds of the raw directory and an EXIF image size 16 photosites narrower and 8 lower than the sensor.
     *
     * @param picture the picture, whose width is a multiple of 32 and whose height is even
     * @param craw {@code true} for Sony's lossy cRAW coding, {@code false} for 14-bit samples in 16-bit words
     * @return the file and its sensor image
     */
    static RawSample arw(RgbaPicture picture, boolean craw) {
        var width = picture.width();
        var height = picture.height();
        var black = 512;
        var white = 16300;
        var tiff = new TiffParts(true);
        int[] stored;
        byte[] strip;
        if (craw) {
            var curve = sonyCurve();
            var targets = SyntheticVendorRaw.mosaic(picture, 256, 2047 - 256);
            stored = new int[targets.length];
            strip = new byte[width * height];
            var values = new int[16];
            var decoded = new int[16];
            for (var y = 0; y < height; y++) {
                for (var x = 0; x < width; x += 32) {
                    for (var half = 0; half < 2; half++) {
                        for (var index = 0; index < 16; index++) {
                            values[index] = targets[y * width + x + 2 * index + half];
                        }
                        sonyBlock(values, strip, y * width + x + 16 * half, decoded);
                        for (var index = 0; index < 16; index++) {
                            stored[y * width + x + 2 * index + half] = curve[decoded[index] << 1];
                        }
                    }
                }
            }
        } else {
            stored = SyntheticVendorRaw.mosaic(picture, black, white - black);
            strip = words(stored, ByteOrder.LITTLE_ENDIAN);
        }
        var bits = craw ? 8 : 14;
        var compression = craw ? 32767 : 1;
        var file = tiffEp(tiff, List.of(TiffParts.ascii(271, "SONY"), TiffParts.ascii(272, "ILCE-7M3")),
                List.of(tiff.longs(40962, width - 16), tiff.longs(40963, height - 8)), null,
                offset -> {
                    var entries = new ArrayList<>(rawDirectory(tiff, width, height, bits, compression, offset,
                            strip.length));
                    entries.add(tiff.shorts(0x7010, SONY_THRESHOLDS));
                    entries.add(tiff.shorts(0x7310, black, black, black, black));
                    entries.add(tiff.shorts(0x7313, 2400, 1024, 1024, 1700));
                    return entries;
                }, strip);
        return sample(file, width, height, stored, 8, 4, width - 16, height - 8, uniform(black), white);
    }

    /**
     * Builds the Sony cRAW tone curve of {@link #SONY_THRESHOLDS}, mapping twice an 11-bit value to a 14-bit one.
     *
     * @return the curve, of 4096 entries
     */
    private static int[] sonyCurve() {
        var knots = new int[]{0, SONY_THRESHOLDS[0] >> 2, SONY_THRESHOLDS[1] >> 2, SONY_THRESHOLDS[2] >> 2,
                SONY_THRESHOLDS[3] >> 2, 4095};
        var curve = new int[4096];
        var value = 0;
        for (var segment = 0; segment < 5; segment++) {
            for (var index = knots[segment] + 1; index <= knots[segment + 1]; index++) {
                value += 1 << segment;
                curve[index] = Math.min(value, 16383);
            }
        }
        return curve;
    }

    /**
     * Codes 16 values of 11 bits as a cRAW block: the maximum, the minimum, their positions and 7-bit deltas of
     * the others from the minimum, scaled by the smallest power of two up to 16 that covers the range.
     *
     * @param out the strip
     * @param start the index of the block in the strip
     * @param decoded receives the values the block decodes to
     */
    private static void sonyBlock(int[] values, byte[] out, int start, int[] decoded) {
        var maxIndex = 0;
        var minIndex = 0;
        for (var index = 1; index < 16; index++) {
            if (values[index] > values[maxIndex]) {
                maxIndex = index;
            }
            if (values[index] < values[minIndex]) {
                minIndex = index;
            }
        }
        if (maxIndex == minIndex) {
            minIndex = maxIndex == 0 ? 1 : 0;
        }
        var max = values[maxIndex];
        var min = values[minIndex];
        var shift = 0;
        while (shift < 4 && (0x80 << shift) <= max - min) {
            shift++;
        }
        var low = (long) max | (long) min << 11 | (long) maxIndex << 22 | (long) minIndex << 26;
        var high = 0L;
        var bit = 30;
        for (var index = 0; index < 16; index++) {
            if (index == maxIndex) {
                decoded[index] = max;
            } else if (index == minIndex) {
                decoded[index] = min;
            } else {
                var delta = Math.clamp(Math.round((values[index] - min) / (float) (1 << shift)), 0, 127);
                decoded[index] = Math.min(min + (delta << shift), 0x7FF);
                if (bit < 64) {
                    low |= (long) delta << bit;
                }
                if (bit + 7 > 64) {
                    high |= bit >= 64 ? (long) delta << (bit - 64) : (long) delta >>> (64 - bit);
                }
                bit += 7;
            }
        }
        for (var index = 0; index < 8; index++) {
            out[start + index] = (byte) (low >>> (8 * index));
            out[start + 8 + index] = (byte) (high >>> (8 * index));
        }
    }

    /**
     * Writes an ORF file of an RGGB mosaic of a picture, with a maker note whose image processing directory holds
     * the white balance, a color matrix, the black levels and a crop 8 photosites in from the left and right and 4
     * from the top and bottom.
     *
     * @param picture the picture, whose width and height are even
     * @param compressed {@code true} for Olympus' compressed coding, {@code false} for 12-bit samples in 16-bit
     * words
     * @return the file and its sensor image
     */
    static RawSample orf(RgbaPicture picture, boolean compressed) {
        var width = picture.width();
        var height = picture.height();
        var black = 256;
        var white = 4095;
        var stored = SyntheticVendorRaw.mosaic(picture, black, white - black);
        var strip = compressed ? olympusStream(stored, width, height) : words(stored, ByteOrder.LITTLE_ENDIAN);
        var tiff = new TiffParts(true);
        var left = 8;
        var top = 4;
        var processing = List.of(tiff.shorts(0x0100, 480, 400), tiff.shorts(0x0200, 420, -120, -44, -60, 380, -64, 8,
                        -110, 358), tiff.shorts(0x0600, black, black, black, black), tiff.shorts(0x0612, left),
                tiff.shorts(0x0613, top), tiff.shorts(0x0614, width - 2 * left), tiff.shorts(0x0615, height - 2 * top));
        // the maker note's offsets are relative to its start: the header, the directory at 12 pointing to the
        // image processing directory that follows it
        LongFunction<byte[]> note = position -> {
            var head = new byte[]{'O', 'L', 'Y', 'M', 'P', 'U', 'S', 0, 'I', 'I', 3, 0};
            var directorySize = tiff.directory(List.of(tiff.longs(0x2040, 0)), 12, 0).length;
            var directory = tiff.directory(List.of(tiff.longs(0x2040, 12 + directorySize)), 12, 0);
            return concat(head, directory, tiff.directory(processing, 12 + directorySize, 0));
        };
        var parts = new ArrayList<TiffParts.Part>();
        parts.add(offsets -> tiff.header(0x4F52, offsets[1]));
        parts.add(offsets -> tiff.directory(List.of(TiffParts.ascii(271, "OLYMPUS IMAGING CORP."),
                TiffParts.ascii(272, "E-M5"), tiff.longs(256, width), tiff.longs(257, height), tiff.shorts(258, 12),
                tiff.shorts(259, 1), tiff.longs(273, offsets[3]), tiff.longs(279, strip.length),
                tiff.longs(34665, offsets[2])), offsets[1], 0));
        parts.add(offsets -> exif(tiff, offsets[2], List.of(), note));
        parts.add(offsets -> strip);
        return sample(TiffParts.write(parts), width, height, stored, left, top, width - 2 * left, height - 2 * top,
                uniform(black), white);
    }

    /**
     * Codes 12-bit photosites with Olympus' adaptive Rice code: after a seven-byte header, per photosite a sign, two
     * low bits, a unary quotient (escaped at twelve) and the mid bits of the magnitude of its residual from the
     * gradient prediction, less a per-color bias.
     *
     * @param samples the photosites, row by row
     * @return the strip
     */
    private static byte[] olympusStream(int[] samples, int width, int height) {
        var bits = new BitWriter(false);
        bits.write(0, 32);
        bits.write(0x0100, 16);
        bits.write(0, 8);
        var bias = new int[2];
        var m1 = new int[2];
        var m2 = new int[2];
        var m3 = new int[2];
        var out = new short[samples.length];
        for (var y = 0; y < height; y++) {
            var row = y * width;
            for (var c = 0; c < 2; c++) {
                bias[c] = 0;
                m1[c] = 32;
                m2[c] = 32;
                m3[c] = 32;
            }
            for (var x = 0; x < width; x++) {
                var c = x & 1;
                var b = olympusWidth(m1[c], m2[c], m3[c]);
                var prediction = olympusPrediction(out, width, row, x, y);
                var residual = samples[row + x] - prediction;
                var high = Math.floorDiv(residual, 4);
                var low = residual - 4 * high;
                var coded = high + bias[c];
                var sign = coded < 0 ? 1 : 0;
                var magnitude = coded < 0 ? -coded - 1 : coded;
                var q = magnitude >>> b;
                bits.write(sign, 1);
                bits.write(low, 2);
                if (q < 12) {
                    bits.write(1, q + 1);
                } else {
                    if (q >= 1 << (15 - b)) {
                        throw new IllegalStateException("the synthetic ORF residual is too large to code");
                    }
                    bits.write(0, 12);
                    bits.write(q, 15 - b);
                    bits.write(1, 1);
                }
                bits.write(magnitude & ((1 << b) - 1), b);
                out[row + x] = (short) samples[row + x];
                bias[c] = -Math.floorDiv(3 * high - bias[c], 32);
                m3[c] = m2[c];
                m2[c] = m1[c];
                m1[c] = magnitude;
            }
        }
        return concat(bits.bytes(), new byte[16]);
    }

    /**
     * Returns the bits of the mid part of an Olympus code from the magnitudes of the last three coded values of the
     * color, most recent first.
     *
     * @param m1 the magnitude of the previous coded value
     * @param m2 the magnitude of the one before
     * @param m3 the magnitude of the one before that
     * @return the width
     */
    private static int olympusWidth(int m1, int m2, int m3) {
        if (m1 >= 17) {
            return Math.max(4, 32 - Integer.numberOfLeadingZeros(m1) - 2);
        }
        if (m2 >= 17 || m3 >= 17) {
            return 4;
        }
        return Math.max(2, 32 - Integer.numberOfLeadingZeros(m1));
    }

    /**
     * Returns the Olympus prediction of a photosite from the photosites of its color already coded.
     *
     * @param out the photosites coded so far
     * @param row the index of the row's first photosite
     * @param x the column
     * @param y the row
     * @return the prediction
     */
    private static int olympusPrediction(short[] out, int width, int row, int x, int y) {
        if (y < 2) {
            return x < 2 ? 0 : out[row + x - 2] & 0xFFFF;
        }
        var up = out[row - 2 * width + x] & 0xFFFF;
        if (x < 2) {
            return up;
        }
        var left = out[row + x - 2] & 0xFFFF;
        var upLeft = out[row - 2 * width + x - 2] & 0xFFFF;
        var high = Math.max(left, up);
        var low = Math.min(left, up);
        if (upLeft >= high) {
            return low;
        }
        if (upLeft <= low) {
            return high;
        }
        if (Math.max(Math.abs(left - upLeft), Math.abs(up - upLeft)) > 32) {
            return left + up - upLeft;
        }
        return (left + up) >> 1;
    }

    /**
     * Writes an RW2 file of an RGGB mosaic of a picture, with the levels, white balance and a crop 8 photosites in
     * from the left and right and 4 from the top and bottom in its first directory.
     *
     * @param picture the picture, whose height is even and whose width is a multiple of the coding's group (of 4
     * for version 8)
     * @param coding the raw format version
     * @return the file and its sensor image
     */
    static RawSample rw2(RgbaPicture picture, Panasonic coding) {
        var width = picture.width();
        var height = picture.height();
        var maximum = (1 << coding.bits) - 1;
        var black = coding.bits == 12 ? 128 : 512;
        var targets = SyntheticVendorRaw.mosaic(picture, black, maximum - 64 - black);
        var stored = new int[targets.length];
        var tiff = new TiffParts(true);
        var extra = new ArrayList<Entry>();
        byte[] strip;
        LongFunction<List<Entry>> tiles = offset -> List.of();
        switch (coding) {
            case V4 -> strip = panasonicChunks(panasonic4(targets, width, height, stored));
            case V5 -> {
                System.arraycopy(targets, 0, stored, 0, targets.length);
                strip = panasonicChunks(panasonic5(targets, width, height));
            }
            case V6 -> strip = panasonic6(targets, width, height, stored);
            default -> {
                System.arraycopy(targets, 0, stored, 0, targets.length);
                var tileWidth = width / 2;
                var first = panasonic8(targets, width, height, 0, tileWidth);
                var second = panasonic8(targets, width, height, tileWidth, width - tileWidth);
                strip = concat(first, second);
                var codes = canonical(PANASONIC_LENGTHS);
                var table = new int[1 + 2 * PANASONIC_LENGTHS.length];
                table[0] = PANASONIC_LENGTHS.length;
                for (var index = 0; index < PANASONIC_LENGTHS.length; index++) {
                    table[1 + 2 * index] = PANASONIC_LENGTHS[index];
                    table[2 + 2 * index] = codes[index];
                }
                extra.add(TiffParts.undefined(0x40, tiff.u16(table)));
                extra.add(TiffParts.undefined(0x47, tiff.u16(2, tileWidth, width - tileWidth)));
                extra.add(TiffParts.undefined(0x48, tiff.u16(2, height, height)));
                extra.add(TiffParts.undefined(0x45, u16AndU32(tiff, 0, tileWidth)));
                tiles = offset -> List.of(TiffParts.undefined(0x44, u16AndU32(tiff, offset, offset + first.length)),
                        TiffParts.undefined(0x46, u16AndU32(tiff, first.length * 8L, second.length * 8L)));
            }
        }
        var white = coding == Panasonic.V6 ? maximum - PANASONIC_PEDESTAL : maximum;
        if (coding == Panasonic.V6) {
            for (var index = 0; index < stored.length; index++) {
                stored[index] = Math.max(0, stored[index] - PANASONIC_PEDESTAL);
            }
        }
        var left = 8;
        var top = 4;
        var finalTiles = tiles;
        var parts = new ArrayList<TiffParts.Part>();
        parts.add(offsets -> tiff.header(0x55, offsets[1]));
        parts.add(offsets -> {
            var entries = new ArrayList<>(List.of(TiffParts.ascii(271, "Panasonic"), TiffParts.ascii(272, "DC-GX9"),
                    tiff.shorts(0x02, width), tiff.shorts(0x03, height), tiff.shorts(0x04, top),
                    tiff.shorts(0x05, left), tiff.shorts(0x06, height - top), tiff.shorts(0x07, width - left),
                    tiff.shorts(0x09, 1), tiff.shorts(0x0A, coding.bits), tiff.shorts(0x0E, maximum),
                    tiff.shorts(0x1C, black), tiff.shorts(0x1D, black), tiff.shorts(0x1E, black),
                    tiff.shorts(0x24, 520), tiff.shorts(0x25, 256), tiff.shorts(0x26, 410),
                    tiff.shorts(0x2D, coding.version), tiff.longs(0x118, offsets[2])));
            entries.addAll(extra);
            entries.addAll(finalTiles.apply(offsets[2]));
            return tiff.directory(entries, offsets[1], 0);
        });
        parts.add(offsets -> strip);
        return sample(TiffParts.write(parts), width, height, stored, left, top, width - 2 * left, height - 2 * top,
                uniform(black), white);
    }

    /**
     * Writes an ERF file of an RGGB mosaic of a picture: 12-bit samples packed ten to a 16-byte block, and a maker
     * note of black levels and the JPEG preview, stored with a damaged first byte, from which the decoder estimates
     * the white balance.
     *
     * @param picture the picture, whose width is a multiple of 10 and whose height is even
     * @return the file and its sensor image
     */
    static RawSample erf(RgbaPicture picture) {
        var width = picture.width();
        var height = picture.height();
        var black = 64;
        var stored = SyntheticVendorRaw.mosaic(picture, black, 4095 - black);
        var rowBytes = (width + 9) / 10 * 16;
        var strip = new byte[rowBytes * height];
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x += 2) {
                var a = stored[y * width + x];
                var b = stored[y * width + x + 1];
                var at = y * rowBytes + x / 10 * 16 + x % 10 / 2 * 3;
                strip[at] = (byte) (a >> 4);
                strip[at + 1] = (byte) (((a & 0xF) << 4) | (b >> 8));
                strip[at + 2] = (byte) b;
            }
        }
        var tiff = new TiffParts(false);
        var preview = JpegEncoder.encode(downscale(picture, 8));
        preview[0] = (byte) 0xEE;
        // the maker note's directory follows its eight-byte header, its offsets relative to the file
        LongFunction<byte[]> note = position -> concat(new byte[]{'E', 'P', 'S', 'O', 'N', 0, 1, 0},
                tiff.directory(List.of(tiff.longs(0x0401, black, black, black, black),
                        TiffParts.undefined(0x0280, preview)), position + 8, 0));
        var file = tiffEp(tiff, List.of(TiffParts.ascii(271, "SEIKO EPSON CORP."), TiffParts.ascii(272, "R-D1")),
                List.of(), note, offset -> rawDirectory(tiff, width, height, 12, 32769, offset, strip.length), strip);
        return sample(file, width, height, stored, 0, 0, width, height, uniform(black), 4095);
    }

    /**
     * Writes a MEF file of an RGGB mosaic of a picture: 12-bit samples packed most significant bit first, and a
     * maker note whose second directory holds the camera's white balance multipliers and the table a white balance
     * mode selects from.
     *
     * @param picture the picture, whose width and height are even
     * @return the file and its sensor image
     */
    static RawSample mef(RgbaPicture picture) {
        var width = picture.width();
        var height = picture.height();
        var white = 4000;
        var stored = SyntheticVendorRaw.mosaic(picture, 0, white);
        var rowBytes = width * 3 / 2;
        var strip = new byte[rowBytes * height];
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x += 2) {
                var a = stored[y * width + x];
                var b = stored[y * width + x + 1];
                var at = y * rowBytes + x / 2 * 3;
                strip[at] = (byte) (a >> 4);
                strip[at + 1] = (byte) (((a & 0xF) << 4) | (b >> 8));
                strip[at + 2] = (byte) b;
            }
        }
        var tiff = new TiffParts(false);
        var table = ByteBuffer.allocate(136 * 16);
        for (var index = 0; index < 136; index++) {
            table.putInt(200 + index).putInt(300 + 2 * index).putInt(300 + 2 * index).putInt(400 - index);
        }
        var camera = ByteBuffer.allocate(16).putInt(310).putInt(256).putInt(256).putInt(348).array();
        var balance = List.of(tiff.shorts(0xA2, 3), tiff.shorts(0xA3, 2), TiffParts.undefined(0xB2, table.array()),
                TiffParts.undefined(0xB3, camera));
        var name = List.of(TiffParts.ascii(0x81, "Mamiya"));
        // the maker note is a directory linking to the white balance directory as its next one, its offsets
        // relative to the file
        LongFunction<byte[]> note = position -> {
            var firstSize = tiff.directory(name, position, 0).length;
            return concat(tiff.directory(name, position, position + firstSize),
                    tiff.directory(balance, position + firstSize, 0));
        };
        var file = tiffEp(tiff, List.of(TiffParts.ascii(271, "Mamiya-OP Co.,Ltd."), TiffParts.ascii(272, "MAMIYA ZD")),
                List.of(), note, offset -> rawDirectory(tiff, width, height, 12, 1, offset, strip.length), strip);
        return sample(file, width, height, stored, 4, 4, width - 8, height - 8, uniform(0), white);
    }

    /**
     * Writes an SRW file of an RGGB mosaic of a picture, with a maker note of the sensor areas, the levels and the
     * white balance stored plus a key, and the bit order of the packed samples.
     *
     * @param picture the picture, whose width and height are even
     * @param packed {@code true} for 12-bit samples packed least significant bit first, {@code false} for 12-bit
     * samples in little-endian 16-bit words
     * @return the file and its sensor image
     */
    static RawSample srw(RgbaPicture picture, boolean packed) {
        var width = picture.width();
        var height = picture.height();
        var black = 64;
        var white = 3750;
        var stored = SyntheticVendorRaw.mosaic(picture, black, white - black);
        byte[] strip;
        if (packed) {
            strip = new byte[stored.length / 2 * 3];
            for (var pair = 0; pair < stored.length / 2; pair++) {
                var a = stored[2 * pair];
                var b = stored[2 * pair + 1];
                strip[3 * pair] = (byte) a;
                strip[3 * pair + 1] = (byte) ((a >> 8) | ((b & 0xF) << 4));
                strip[3 * pair + 2] = (byte) (b >> 4);
            }
        } else {
            strip = words(stored, ByteOrder.LITTLE_ENDIAN);
        }
        var tiff = new TiffParts(true);
        long[] key = {305, 72, 737, 456, 282, 307, 519, 724, 13, 505, 193};
        long[] balance = {10480, 4096, 4096, 7664};
        var levels = new long[4];
        var multipliers = new long[4];
        for (var index = 0; index < 4; index++) {
            levels[index] = black + key[index];
            multipliers[index] = balance[index] + key[index];
        }
        var left = 8;
        var top = 4;
        // the maker note is a directory at the start of the value, its offsets relative to that start
        LongFunction<byte[]> note = position -> tiff.directory(List.of(tiff.longs(0x0040, 0),
                tiff.longs(0xA010, 0, 0, width, height, left, top, width - left, height - top), tiff.longs(0xA020, key),
                tiff.longs(0xA021, multipliers), tiff.longs(0xA028, levels)), 0, 0);
        var compression = packed ? 32770 : 32769;
        var file = tiffEp(tiff, List.of(TiffParts.ascii(271, "SAMSUNG"), TiffParts.ascii(272, "NX1000")), List.of(),
                note, offset -> {
                    var entries = new ArrayList<Entry>();
                    for (var entry : rawDirectory(tiff, width, height, 12, compression, offset, strip.length)) {
                        if (entry.tag() != 262) {
                            entries.add(entry);
                        }
                    }
                    return entries;
                }, strip);
        return sample(file, width, height, stored, left, top, width - 2 * left, height - 2 * top, uniform(black),
                white);
    }

    /**
     * Writes a Kodak DCS file of a GRBG mosaic of a picture: 12-bit photosites in Kodak's compression 65000 (groups
     * of 256 photosites, their 4-bit difference lengths, then the differences from the photosite two to the left,
     * least significant bit first in big-endian 16-bit words) with the group offset table, a linearization curve,
     * and a Kodak directory of the image area, the column and row black offsets and the white balance.
     *
     * @param picture the picture, whose width and height are even
     * @return the file
     */
    static byte[] kodak(RgbaPicture picture) {
        var width = picture.width();
        var height = picture.height();
        var photosites = SyntheticVendorRaw.mosaic(picture, 100, 3995);
        var groups = new ArrayList<Long>();
        var out = new ByteArrayOutputStream();
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x += 256) {
                if (out.size() > 0) {
                    groups.add((long) out.size());
                }
                var count = Math.min(256, width - x);
                var differences = new int[count];
                var lengths = new int[count];
                for (var index = 0; index < count; index++) {
                    var value = photosites[y * width + x + index];
                    differences[index] = index < 2 ? value : value - photosites[y * width + x + index - 2];
                    lengths[index] = category(differences[index]);
                }
                for (var index = 0; index < count; index += 2) {
                    out.write(lengths[index] | (index + 1 < count ? lengths[index + 1] << 4 : 0));
                }
                var buffer = 0L;
                var bits = 0;
                for (var index = 0; index < count; index++) {
                    buffer |= (long) magnitude(differences[index], lengths[index]) << bits;
                    bits += lengths[index];
                    while (bits >= 16) {
                        out.write((int) (buffer >>> 8) & 0xFF);
                        out.write((int) buffer & 0xFF);
                        buffer >>>= 16;
                        bits -= 16;
                    }
                }
                if (bits > 0) {
                    out.write((int) (buffer >>> 8) & 0xFF);
                    out.write((int) buffer & 0xFF);
                }
                while (out.size() % 4 != 0) {
                    out.write(0);
                }
            }
        }
        var strip = out.toByteArray();
        var tiff = new TiffParts(true);
        var curve = new int[4096];
        for (var index = 0; index < curve.length; index++) {
            curve[index] = (int) Math.round(4095 * StrictMath.pow(index / 4095.0, 1.2));
        }
        var columns = new int[width];
        Arrays.fill(columns, -100);
        var rows = new int[height];
        var groupOffsets = new long[groups.size()];
        for (var index = 0; index < groupOffsets.length; index++) {
            groupOffsets[index] = groups.get(index);
        }
        var kodak = List.of(tiff.shorts(1003, 4), tiff.shorts(1004, 2), tiff.shorts(1005, width - 8),
                tiff.shorts(1006, height - 4), signed(tiff.shorts(1039, columns)), signed(tiff.shorts(1040, rows)),
                tiff.longs(2120, 1900, 1800, 900));
        var parts = new ArrayList<TiffParts.Part>();
        parts.add(offsets -> tiff.header(42, offsets[1]));
        parts.add(offsets -> tiff.directory(List.of(tiff.longs(254, 1), TiffParts.ascii(271, "Kodak"),
                TiffParts.ascii(272, "DCS Pro 14N"), tiff.longs(330, offsets[2]), tiff.longs(33424, offsets[3])),
                offsets[1], 0));
        parts.add(offsets -> {
            var entries = new ArrayList<>(rawDirectory(tiff, width, height, 12, 65000, offsets[4], strip.length));
            entries.removeIf(entry -> entry.tag() == 33422);
            entries.add(TiffParts.bytes(33422, 1, 0, 2, 1));
            entries.add(tiff.shorts(291, curve));
            entries.add(tiff.longs(65001, groupOffsets));
            return tiff.directory(entries, offsets[2], 0);
        });
        parts.add(offsets -> tiff.directory(kodak, offsets[3], 0));
        parts.add(offsets -> strip);
        return TiffParts.write(parts);
    }

    /**
     * Returns an entry with its SHORT values retyped as SSHORT.
     *
     * @param entry the SHORT entry
     * @return the SSHORT entry
     */
    private static Entry signed(Entry entry) {
        return new Entry(entry.tag(), 8, entry.count(), entry.value());
    }

    /**
     * Writes a Leaf MOS file of a 16-bit RGGB mosaic of a picture, one tile of the whole image, with its metadata in
     * Leaf's packets: the mosaic pattern, the neutrals and the rotation.
     *
     * @param picture the picture, whose width and height are even
     * @param compressed {@code true} for a lossless JPEG of two components, as Leaf's compression 99 stores it,
     * {@code false} for big-endian 16-bit words
     * @return the file
     */
    static byte[] leaf(RgbaPicture picture, boolean compressed) {
        var width = picture.width();
        var height = picture.height();
        var photosites = SyntheticVendorRaw.mosaic(picture, 0, 15000);
        byte[] strip;
        if (compressed) {
            strip = leafJpeg(photosites, width / 2, height, 2);
        } else {
            var buffer = ByteBuffer.allocate(2 * photosites.length);
            for (var value : photosites) {
                buffer.putShort((short) value);
            }
            strip = buffer.array();
        }
        var tiff = new TiffParts(false);
        var xmp = ("<x:xmpmeta><rdf:RDF><rdf:Description><tiff:Make>Leaf</tiff:Make><tiff:Model>"
                + "Leaf Aptus 22(LF10043    )/Mamiya 645 AFD</tiff:Model></rdf:Description></rdf:RDF></x:xmpmeta>")
                .getBytes(StandardCharsets.UTF_8);
        var capture = concat(leafPacket("CaptProf_version", leafValue("80005")),
                leafPacket("CaptProf_mosaic_pattern", leafValue("1\n3\n2\n0")));
        var neutral = leafPacket("NeutObj_neutrals", leafValue("3270\n2703\n3270\n2232"));
        var image = leafPacket("ImgProf_rotation_angle", leafValue("0"));
        var packets = concat(leafPacket("camera_profile", concat(leafPacket("CamProf_capture_profile", capture),
                        leafPacket("CamProf_image_profile", concat(leafPacket("ImgProf_shoot_setup",
                                leafPacket("ShootObj_capture_setup", leafPacket("CaptureObj_neutals", neutral))),
                                image)))),
                leafPacket("JPEG_preview_data", new byte[64]));
        var parts = new ArrayList<TiffParts.Part>();
        parts.add(offsets -> tiff.header(42, offsets[1]));
        parts.add(offsets -> tiff.directory(List.of(tiff.longs(254, 0), tiff.longs(256, width),
                tiff.longs(257, height), tiff.shorts(258, 16), tiff.shorts(259, compressed ? 99 : 1),
                tiff.shorts(262, 1), tiff.shorts(277, 1), tiff.shorts(322, width), tiff.shorts(323, height),
                tiff.longs(324, offsets[2]), tiff.longs(325, strip.length),
                new Entry(700, TiffParts.BYTE, xmp.length, xmp),
                TiffParts.undefined(34310, packets)), offsets[1], 0));
        parts.add(offsets -> strip);
        return TiffParts.write(parts);
    }

    /**
     * Returns a Leaf packet: the signature {@code PKTS}, a version, a 40-byte name, the length and the data.
     *
     * @param name the packet name
     * @param data the packet data
     * @return the packet
     */
    private static byte[] leafPacket(String name, byte[] data) {
        var buffer = ByteBuffer.allocate(52 + data.length);
        buffer.put("PKTS".getBytes(StandardCharsets.US_ASCII)).putInt(1);
        buffer.put(Arrays.copyOf(name.getBytes(StandardCharsets.US_ASCII), 40)).putInt(data.length).put(data);
        return buffer.array();
    }

    /**
     * {@return a Phase One IIQ file of a picture: a TIFF header, Phase One's block from byte 8 with its row-coded
     * sensor data, row table, row blacks and two grids, and a directory naming the camera}
     *
     * @param picture the source of the mosaic
     * @param compression the compression: 0, 3, 5 or 8
     */
    static byte[] iiq(RgbaPicture picture, int compression) {
        var width = picture.width();
        var height = picture.height();
        var photosites = SyntheticVendorRaw.mosaic(picture, 1024, 15000);
        var sensor = new java.io.ByteArrayOutputStream();
        var rows = ByteBuffer.allocate(height * 4).order(ByteOrder.LITTLE_ENDIAN);
        var row = new int[width];
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                var value = photosites[y * width + x];
                row[x] = compression == 5 ? (int) (255 * Math.sqrt(value / 65535.0)) : compression == 8 ? value : value / 4;
            }
            rows.putInt(sensor.size());
            if (compression == 0) {
                for (var value : row) {
                    sensor.write(value);
                    sensor.write(value >>> 8);
                }
            } else {
                sensor.writeBytes(iiqRow(row));
            }
        }
        var grid = ByteBuffer.allocate(16 + 2 * 2 * 4 * 4).order(ByteOrder.LITTLE_ENDIAN);
        grid.putShort((short) 0).putShort((short) 0).putShort((short) (2 * width)).putShort((short) (2 * height));
        grid.putShort((short) width).putShort((short) height).putInt(0);
        for (var node = 0; node < 16; node++) {
            grid.putShort((short) (32768 + node * 64));
        }
        var levelGrid = Arrays.copyOf(grid.array(), 16 + 4 * 2);
        var calibration = ByteBuffer.allocate(12 + levelGrid.length + grid.capacity() + 8 + 24).order(ByteOrder.LITTLE_ENDIAN);
        calibration.put("IIII".getBytes(StandardCharsets.US_ASCII)).putInt(1).putInt(12 + levelGrid.length + grid.capacity());
        calibration.put(levelGrid).put(grid.array());
        calibration.putInt(2).putInt(0);
        calibration.putInt(0x410).putInt(levelGrid.length).putInt(12);
        calibration.putInt(0x40B).putInt(grid.capacity()).putInt(12 + levelGrid.length);
        var data = sensor.toByteArray();
        var rowBlack = new byte[height * 2];
        var tables = new byte[][]{rows.array(), rowBlack, calibration.array()};
        var tableTags = new int[]{0x21C, 0x21F, 0x110};
        var scalars = new int[][]{{0x108, width}, {0x109, height}, {0x10A, 0}, {0x10B, 0}, {0x10C, width}, {0x10D, height},
                {0x10E, compression}, {0x21D, 1024}};
        var count = 1 + scalars.length + tables.length;
        var at = 12 + data.length;
        var directory = ByteBuffer.allocate(8 + count * 16).order(ByteOrder.LITTLE_ENDIAN);
        directory.putInt(count).putInt(0).putInt(0x10F).putInt(2).putInt(data.length).putInt(12);
        for (var scalar : scalars) {
            directory.putInt(scalar[0]).putInt(4).putInt(4).putInt(scalar[1]);
        }
        for (var index = 0; index < tables.length; index++) {
            directory.putInt(tableTags[index]).putInt(1).putInt(tables[index].length).putInt(at);
            at += tables[index].length;
        }
        var ifdAt = 8 + at + directory.capacity();
        var make = "Phase One\0".getBytes(StandardCharsets.US_ASCII);
        var ifd = ByteBuffer.allocate(2 + 12 + 4 + make.length).order(ByteOrder.LITTLE_ENDIAN);
        ifd.putShort((short) 1).putShort((short) 271).putShort((short) 2).putInt(make.length).putInt(ifdAt + 18);
        ifd.putInt(0).put(make);
        var head = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN);
        head.put((byte) 'I').put((byte) 'I').putShort((short) 42).putInt(ifdAt);
        head.put("IIIICwaR".getBytes(StandardCharsets.US_ASCII)).putInt(at);
        var file = new java.io.ByteArrayOutputStream();
        file.writeBytes(head.array());
        file.writeBytes(data);
        for (var table : tables) {
            file.writeBytes(table);
        }
        file.writeBytes(directory.array());
        file.writeBytes(ifd.array());
        return file.toByteArray();
    }

    /**
     * {@return a row in Phase One's coding, each group taking the smallest widths that hold its differences and the
     * photosites after the last whole group taking 16 bits each}
     *
     * @param row the row's numbers
     */
    private static byte[] iiqRow(int[] row) {
        var out = new java.io.ByteArrayOutputStream();
        var buffer = 0L;
        var count = 0;
        int[] last = {0, 0}, widths = {-1, -1};
        for (var x = 0; x < row.length; x += 8) {
            if (row.length - x < 8) {
                for (var at = x; at < row.length; at++) {
                    while (count >= 32) {
                        count -= 32;
                        putWord(out, (int) (buffer >>> count));
                    }
                    buffer = buffer << 16 | (row[at] & 0xFFFF);
                    count += 16;
                }
                while (count >= 32) {
                    count -= 32;
                    putWord(out, (int) (buffer >>> count));
                }
                break;
            }
            var next = new int[2];
            for (var lane = 0; lane < 2; lane++) {
                var previous = last[lane];
                var largest = 0;
                for (var index = lane; index < 8; index += 2) {
                    var value = row[Math.min(x + index, row.length - 1)];
                    largest = Math.max(largest, Math.abs(value - previous));
                    previous = value;
                }
                next[lane] = x == 0 || largest >= 4095 ? 16 : Math.max(5, 33 - Integer.numberOfLeadingZeros(largest + 1));
                var code = next[lane] == widths[lane] ? 1 : switch (next[lane]) {
                    case 5 -> 0b000010;
                    case 6 -> 0b0010;
                    case 7 -> 0b011;
                    case 8 -> 0b010;
                    case 9 -> 0b0011;
                    case 10 -> 0b00011;
                    case 11 -> 0b00010;
                    case 12 -> 0b000011;
                    case 13 -> 0b000001;
                    default -> 0;
                };
                var bits = next[lane] == widths[lane] ? 1 : switch (next[lane]) {
                    case 7, 8 -> 3;
                    case 6, 9 -> 4;
                    case 10, 11 -> 5;
                    default -> 6;
                };
                buffer = buffer << bits | code;
                count += bits;
                widths[lane] = next[lane];
            }
            for (var index = 0; index < 8; index++) {
                var lane = index & 1;
                var value = row[Math.min(x + index, row.length - 1)];
                var bits = widths[lane];
                var number = bits == 16 ? value : value - last[lane] + (1 << (bits - 1)) - 1;
                last[lane] = value;
                while (count >= 32) {
                    count -= 32;
                    putWord(out, (int) (buffer >>> count));
                }
                buffer = buffer << bits | (number & ((1L << bits) - 1));
                count += bits;
            }
            while (count >= 32) {
                count -= 32;
                putWord(out, (int) (buffer >>> count));
            }
        }
        if (count > 0) {
            putWord(out, (int) (buffer << (32 - count)));
        }
        return out.toByteArray();
    }

    /**
     * Writes a little-endian word.
     *
     * @param out the stream
     * @param word the 32 bits
     */
    private static void putWord(java.io.ByteArrayOutputStream out, int word) {
        out.write(word);
        out.write(word >>> 8);
        out.write(word >>> 16);
        out.write(word >>> 24);
    }

    /**
     * {@return a Leaf packet value: the text and a terminating zero}
     *
     */
    private static byte[] leafValue(String text) {
        return (text + "\0").getBytes(StandardCharsets.US_ASCII);
    }

    /**
     * Codes interleaved 16-bit samples as a lossless JPEG of predictor 1 with one table of 5-bit codes, whose scan
     * names each component by its frame identifier plus one, as Leaf's streams do.
     *
     * @param samples the samples, interleaved
     * @param width the width of the frame, in pixels of the components
     * @param components the number of components
     * @return the stream
     */
    private static byte[] leafJpeg(int[] samples, int width, int height, int components) {
        var bits = new BitWriter(true);
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                for (var c = 0; c < components; c++) {
                    var index = (y * width + x) * components + c;
                    var predicted = x == 0 ? (y == 0 ? 32768 : samples[index - width * components])
                            : samples[index - components];
                    var difference = (samples[index] - predicted) & 0xFFFF;
                    if (difference > 32768) {
                        difference -= 65536;
                    }
                    var category = difference == 32768 ? 16 : category(difference);
                    bits.write(category, 5);
                    if (category > 0 && category < 16) {
                        bits.write(magnitude(difference, category), category);
                    }
                }
            }
        }
        var out = new ByteArrayOutputStream();
        out.writeBytes(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xC3, 0, (byte) (8 + 3 * components),
                16, (byte) (height >> 8), (byte) height, (byte) (width >> 8), (byte) width, (byte) components});
        for (var c = 0; c < components; c++) {
            out.writeBytes(new byte[]{(byte) c, 0x11, 0});
        }
        out.writeBytes(new byte[]{(byte) 0xFF, (byte) 0xC4, 0, (byte) (2 + 17 + 17), 0});
        var counts = new byte[16];
        counts[4] = 17;
        out.writeBytes(counts);
        for (var category = 0; category <= 16; category++) {
            out.write(category);
        }
        out.writeBytes(new byte[]{(byte) 0xFF, (byte) 0xDA, 0, (byte) (6 + 2 * components), (byte) components});
        for (var c = 0; c < components; c++) {
            out.writeBytes(new byte[]{(byte) (c + 1), 0});
        }
        out.writeBytes(new byte[]{1, 0, 0});
        out.writeBytes(bits.bytes());
        out.writeBytes(new byte[]{(byte) 0xFF, (byte) 0xD9});
        return out.toByteArray();
    }

    /**
     * The code counts by length of the Hasselblad Huffman table.
     */
    private static final int[] HASSELBLAD_COUNTS = {0, 2, 1, 4, 2, 3, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0};

    /**
     * The categories of the Hasselblad Huffman table, in code order.
     */
    private static final int[] HASSELBLAD_CATEGORIES = {6, 7, 5, 4, 8, 9, 10, 3, 11, 1, 2, 12, 0, 13, 14, 15, 16};

    /**
     * Writes a Hasselblad 3FR file of a 16-bit RGGB mosaic of a picture: Hasselblad's predictor-8 lossless JPEG
     * (pairs of Huffman-coded categories, then their extra bits, in 32-bit little-endian words, each photosite
     * predicted from the one two to its left) with the white level and default crop of the raw directory.
     *
     * @param picture the picture, whose width and height are even
     * @return the file
     */
    static byte[] hasselblad(RgbaPicture picture) {
        var width = picture.width();
        var height = picture.height();
        var photosites = SyntheticVendorRaw.mosaic(picture, 2048, 60000);
        var code = new int[17];
        var length = new int[17];
        var next = 0;
        var position = 0;
        for (var bits = 1; bits <= 16; bits++) {
            for (var k = 0; k < HASSELBLAD_COUNTS[bits - 1]; k++) {
                code[HASSELBLAD_CATEGORIES[position]] = next++;
                length[HASSELBLAD_CATEGORIES[position]] = bits;
                position++;
            }
            next <<= 1;
        }
        var differences = new int[photosites.length];
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                var predicted = x < 2 ? 32768 : photosites[y * width + x - 2];
                var difference = (photosites[y * width + x] - predicted) & 0xFFFF;
                differences[y * width + x] = difference > 32768 ? difference - 65536 : difference;
            }
        }
        var bits = new BitWriter(false);
        for (var index = 0; index < differences.length; index += 2) {
            var first = differences[index] == 32768 ? 16 : category(differences[index]);
            var second = differences[index + 1] == 32768 ? 16 : category(differences[index + 1]);
            bits.write(code[first], length[first]);
            bits.write(code[second], length[second]);
            for (var k = 0; k < 2; k++) {
                var difference = differences[index + k];
                var category = k == 0 ? first : second;
                if (category == 16) {
                    bits.write(0xFFFF, 16);
                } else if (category > 0) {
                    bits.write(magnitude(difference, category), category);
                }
            }
        }
        var entropy = bits.bytes();
        entropy = Arrays.copyOf(entropy, (entropy.length + 3) / 4 * 4);
        for (var at = 0; at < entropy.length; at += 4) {
            for (var k = 0; k < 2; k++) {
                var swap = entropy[at + k];
                entropy[at + k] = entropy[at + 3 - k];
                entropy[at + 3 - k] = swap;
            }
        }
        var stream = new ByteArrayOutputStream();
        stream.writeBytes(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xC3, 0, 11, 16,
                (byte) (height >> 8), (byte) height, (byte) (width >> 8), (byte) width, 1, 0, 0x11, 0, (byte) 0xFF});
        stream.writeBytes(new byte[]{(byte) 0xFF, (byte) 0xC4, 0, (byte) (2 + 17 + HASSELBLAD_CATEGORIES.length), 0});
        for (var count : HASSELBLAD_COUNTS) {
            stream.write(count);
        }
        for (var category : HASSELBLAD_CATEGORIES) {
            stream.write(category);
        }
        stream.writeBytes(new byte[]{(byte) 0xFF, (byte) 0xDA, 0, 8, 1, 0, 0, 8, 0, 0});
        stream.writeBytes(entropy);
        stream.writeBytes(new byte[]{(byte) 0xFF, (byte) 0xD9});
        var strip = stream.toByteArray();
        var tiff = new TiffParts(true);
        return tiffEp(tiff, List.of(TiffParts.ascii(271, "Hasselblad"), TiffParts.ascii(272, "Hasselblad H3D")),
                List.of(), null, offset -> {
                    var entries = new ArrayList<>(rawDirectory(tiff, width, height, 16, 7, offset, strip.length));
                    entries.add(tiff.shorts(50717, 65535));
                    entries.add(tiff.shorts(50719, 4, 2));
                    entries.add(tiff.shorts(50720, width - 8, height - 4));
                    return entries;
                }, strip);
    }

    /**
     * Returns a picture reduced by an integer factor, each pixel the top-left pixel of its block.
     *
     * @return the reduced picture
     */
    private static RgbaPicture downscale(RgbaPicture picture, int factor) {
        var width = picture.width() / factor;
        var height = picture.height() / factor;
        var pixels = new byte[width * height * 4];
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                System.arraycopy(picture.pixels(), y * factor * picture.stride() + x * factor * 4, pixels,
                        (y * width + x) * 4, 4);
            }
        }
        return RgbaPicture.of(width, height, width * 4, pixels, false);
    }

    /**
     * Returns a 16-bit count of two values followed by the values as 32-bit integers, in a byte order.
     *
     * @param tiff the writer whose byte order applies
     * @param first the first value
     * @param second the second value
     * @return the bytes
     */
    private static byte[] u16AndU32(TiffParts tiff, long first, long second) {
        return ByteBuffer.allocate(10).order(tiff.order()).putShort((short) 2).putInt((int) first)
                .putInt((int) second).array();
    }

    /**
     * Codes Panasonic version 4 blocks: per 14 photosites, the first two as 12-bit values, then four groups of a
     * 2-bit code and three 8-bit fields, each group's code the one that brings its photosites nearest their targets.
     *
     * @param targets the photosites to code, each at least 16
     * @param width the width, a multiple of 14
     * @param decoded receives the photosites the blocks decode to
     * @return the stream, before the chunk layout
     */
    private static byte[] panasonic4(int[] targets, int width, int height, int[] decoded) {
        var stream = new byte[width / 14 * height * 16];
        var values = new int[14];
        for (var row = 0; row < height; row++) {
            for (var block = 0; block < width / 14; block++) {
                var at = row * width + block * 14;
                var bits = new BlockBits();
                for (var index = 0; index < 2; index++) {
                    var value = Math.clamp(targets[at + index], 16, 4095);
                    bits.write(value >> 4, 8);
                    bits.write(value & 15, 4);
                    values[index] = value;
                }
                for (var group = 0; group < 4; group++) {
                    var bestCode = 0;
                    var bestError = Long.MAX_VALUE;
                    for (var code = 0; code < 4; code++) {
                        var error = 0L;
                        var prior = priors(values, group * 3);
                        for (var k = 0; k < 3; k++) {
                            var index = 2 + group * 3 + k;
                            var field = panasonic4Field(targets[at + index], code, prior[index & 1]);
                            var value = panasonic4Step(field, code, prior[index & 1]);
                            error += Math.abs(value - targets[at + index]);
                            prior[index & 1] = value;
                        }
                        if (error < bestError) {
                            bestError = error;
                            bestCode = code;
                        }
                    }
                    bits.write(bestCode, 2);
                    for (var k = 0; k < 3; k++) {
                        var index = 2 + group * 3 + k;
                        var field = panasonic4Field(targets[at + index], bestCode, values[index - 2]);
                        bits.write(field, 8);
                        values[index] = panasonic4Step(field, bestCode, values[index - 2]);
                    }
                }
                bits.store(stream, (row * (width / 14) + block) * 16);
                System.arraycopy(values, 0, decoded, at, 14);
            }
        }
        return stream;
    }

    /**
     * Returns the version 4 field that brings a photosite nearest its target, never to zero, which marks a missing
     * photosite.
     *
     * @param code the group's code
     * @param previous the photosite two to the left
     * @return the field
     */
    private static int panasonic4Field(int target, int code, int previous) {
        int guess;
        if (code == 3) {
            guess = (target - (previous & 15)) >> 4;
        } else if (previous < 128 << code) {
            guess = (target - (previous & ((1 << code) - 1))) >> code;
        } else {
            guess = ((target - previous) >> code) + 128;
        }
        var best = 0;
        var bestError = Integer.MAX_VALUE;
        for (var candidate = guess - 1; candidate <= guess + 1; candidate++) {
            for (var field : new int[]{Math.clamp(candidate, 0, 255), 0}) {
                var value = panasonic4Step(field, code, previous);
                if (value <= 0 || value > 4095) {
                    continue;
                }
                var error = Math.abs(value - target);
                if (error < bestError) {
                    bestError = error;
                    best = field;
                }
            }
        }
        return best;
    }

    /**
     * Returns a version 4 photosite from its field, its group's code and the photosite two to the left.
     *
     * @param f the field
     * @param previous the photosite two to the left
     * @return the photosite
     */
    private static int panasonic4Step(int f, int code, int previous) {
        if (f == 0) {
            return previous;
        }
        if (code == 3) {
            return (f << 4) | (previous & 15);
        }
        if (previous < 128 << code) {
            return (f << code) | (previous & ((1 << code) - 1));
        }
        return previous + ((f - 128) << code);
    }

    /**
     * Returns the photosites two to the left of the first two photosites of a group, indexed by column parity.
     *
     * @param values the photosites of the block decoded so far
     * @param start the index of the photosite two to the left of the group's first
     * @return the previous photosite of each parity
     */
    private static int[] priors(int[] values, int start) {
        var prior = new int[2];
        prior[start & 1] = values[start];
        prior[(start + 1) & 1] = values[start + 1];
        return prior;
    }

    /**
     * Packs Panasonic version 5 blocks: nine 14-bit photosites per 16-byte little-endian block, the first in the
     * least significant bits.
     *
     * @param samples the photosites
     * @param width the width, a multiple of 9
     * @return the stream, before the chunk layout
     */
    private static byte[] panasonic5(int[] samples, int width, int height) {
        var blocks = width / 9;
        var stream = new byte[blocks * height * 16];
        for (var row = 0; row < height; row++) {
            for (var block = 0; block < blocks; block++) {
                var start = (row * blocks + block) * 16;
                for (var k = 0; k < 9; k++) {
                    var value = samples[row * width + block * 9 + k];
                    for (var bit = 0; bit < 14; bit++) {
                        if ((value >> bit & 1) != 0) {
                            var position = 14 * k + bit;
                            stream[start + position / 8] |= (byte) (1 << (position % 8));
                        }
                    }
                }
            }
        }
        return stream;
    }

    /**
     * Codes Panasonic version 6 blocks: per 11 photosites, the first two as 14-bit values, then three groups of a
     * 2-bit code and three 10-bit fields, each group's code the one that brings its photosites nearest their
     * targets.
     *
     * @param targets the photosites to code, each at least 16
     * @param width the width, a multiple of 11
     * @param decoded receives the photosites the blocks decode to, before the pedestal is subtracted
     * @return the stored blocks
     */
    private static byte[] panasonic6(int[] targets, int width, int height, int[] decoded) {
        var blocks = width / 11;
        var stream = new byte[blocks * height * 16];
        var values = new int[11];
        for (var row = 0; row < height; row++) {
            for (var block = 0; block < blocks; block++) {
                var at = row * width + block * 11;
                var bits = new BlockBits();
                for (var index = 0; index < 2; index++) {
                    values[index] = Math.clamp(targets[at + index], 16, 16383);
                    bits.write(values[index], 14);
                }
                for (var group = 0; group < 3; group++) {
                    var bestCode = 0;
                    var bestError = Long.MAX_VALUE;
                    for (var code = 0; code < 4; code++) {
                        var error = 0L;
                        var prior = priors(values, group * 3);
                        for (var k = 0; k < 3; k++) {
                            var index = 2 + group * 3 + k;
                            var value = panasonic6Step(panasonic6Field(targets[at + index], code, prior[index & 1]),
                                    code, prior[index & 1]);
                            error += Math.abs(value - targets[at + index]);
                            prior[index & 1] = value;
                        }
                        if (error < bestError) {
                            bestError = error;
                            bestCode = code;
                        }
                    }
                    bits.write(bestCode, 2);
                    for (var k = 0; k < 3; k++) {
                        var index = 2 + group * 3 + k;
                        var field = panasonic6Field(targets[at + index], bestCode, values[index - 2]);
                        bits.write(field, 10);
                        values[index] = panasonic6Step(field, bestCode, values[index - 2]);
                    }
                }
                bits.store(stream, (row * blocks + block) * 16);
                System.arraycopy(values, 0, decoded, at, 11);
            }
        }
        return stream;
    }

    /**
     * Returns the version 6 field that brings a photosite nearest its target and above zero.
     *
     * @param code the group's code
     * @param previous the photosite two to the left
     * @return the field
     */
    private static int panasonic6Field(int target, int code, int previous) {
        int guess;
        if (code == 3) {
            guess = target >> 4;
        } else if (previous < 512 << code) {
            guess = target >> code;
        } else {
            guess = ((target - previous) >> code) + 512;
        }
        var best = 0;
        var bestError = Integer.MAX_VALUE;
        for (var candidate = guess - 1; candidate <= guess + 1; candidate++) {
            var field = Math.clamp(candidate, 0, 1023);
            var value = panasonic6Step(field, code, previous);
            if (value <= PANASONIC_PEDESTAL || value > 0xFFFF) {
                continue;
            }
            var error = Math.abs(value - target);
            if (error < bestError) {
                bestError = error;
                best = field;
            }
        }
        if (bestError == Integer.MAX_VALUE) {
            throw new IllegalStateException("the synthetic RW2 photosite cannot be coded");
        }
        return best;
    }

    /**
     * Returns a version 6 photosite from its field, its group's code and the photosite two to the left.
     *
     * @param previous the photosite two to the left
     * @return the photosite
     */
    private static int panasonic6Step(int field, int code, int previous) {
        if (code == 3) {
            return field << 4;
        }
        if (previous < 512 << code) {
            return field << code;
        }
        return previous + ((field - 512) << code);
    }

    /**
     * Codes one Panasonic version 8 tile: two rows at a time in 2x2 units, each photosite a Huffman-coded
     * difference from the photosite two columns to its left, or two rows above at a row pair's start; the bytes
     * are stored with their bit order reversed.
     *
     * @param samples the photosites of the whole sensor
     * @param width the sensor width
     * @param height the sensor height, even
     * @param left the tile's first column
     * @param tileWidth the tile width, even
     * @return the tile's stream
     */
    private static byte[] panasonic8(int[] samples, int width, int height, int left, int tileWidth) {
        var codes = canonical(PANASONIC_LENGTHS);
        var bits = new BitWriter(false);
        for (var row = 0; row < height; row += 2) {
            for (var column = 0; column < tileWidth; column += 2) {
                for (var k = 0; k < 4; k++) {
                    var x = left + column + (k >> 1);
                    var y = row + (k & 1);
                    int prediction;
                    if (column >= 2) {
                        prediction = samples[y * width + x - 2];
                    } else if (row >= 2) {
                        prediction = samples[(y - 2) * width + x];
                    } else {
                        prediction = 0;
                    }
                    var difference = samples[y * width + x] - prediction;
                    var category = category(difference);
                    bits.write(codes[category], PANASONIC_LENGTHS[category]);
                    bits.write(magnitude(difference, category), category);
                }
            }
        }
        var stream = concat(bits.bytes(), new byte[16]);
        for (var index = 0; index < stream.length; index++) {
            stream[index] = (byte) (Integer.reverse(stream[index] & 0xFF) >>> 24);
        }
        return stream;
    }

    /**
     * Lays a stream out in the chunk layout of Panasonic versions 4 and 5: each chunk of {@code 0x4000} bytes stores
     * its first {@code 0x2008} stream bytes last.
     *
     * @return the stored data, whole chunks
     */
    private static byte[] panasonicChunks(byte[] stream) {
        var chunks = (stream.length + PANASONIC_CHUNK - 1) / PANASONIC_CHUNK;
        var out = new byte[chunks * PANASONIC_CHUNK];
        for (var chunk = 0; chunk < chunks; chunk++) {
            var base = chunk * PANASONIC_CHUNK;
            for (var index = 0; index < PANASONIC_CHUNK; index++) {
                var physical = base + (index < PANASONIC_SPLIT ? index + (PANASONIC_CHUNK - PANASONIC_SPLIT)
                        : index - PANASONIC_SPLIT);
                out[physical] = base + index < stream.length ? stream[base + index] : 0;
            }
        }
        return out;
    }

    /**
     * Writes the bits of one 128-bit Panasonic block from its most significant bit down, stored as a
     * little-endian integer. Instances are not thread-safe.
     */
    private static final class BlockBits {
        /**
         * The 64 most significant bits.
         */
        private long high;

        /**
         * The 64 least significant bits.
         */
        private long low;

        /**
         * The number of bits written.
         */
        private int position;

        /**
         * Writes a field.
         *
         * @param value the field, in its low bits
         * @param bits the width of the field
         */
        void write(int value, int bits) {
            for (var k = bits - 1; k >= 0; k--) {
                var bit = (long) (value >> k & 1);
                if (position < 64) {
                    high |= bit << (63 - position);
                } else {
                    low |= bit << (127 - position);
                }
                position++;
            }
        }

        /**
         * Stores the block as a little-endian 128-bit integer.
         *
         * @param out the output
         * @param start the index of the block's first byte
         */
        void store(byte[] out, int start) {
            for (var k = 0; k < 8; k++) {
                out[start + k] = (byte) (low >>> (8 * k));
                out[start + 8 + k] = (byte) (high >>> (8 * k));
            }
        }
    }

    /**
     * Returns a TIFF/EP-structured file: a header, a first directory pointing to the raw image directory as its
     * SubIFD and to an EXIF directory, the EXIF directory with its maker note, the raw image directory and the
     * strip.
     *
     * @param tiff the writer
     * @param first the entries of the first directory besides its pointers
     * @param exif the entries of the EXIF directory besides the maker note
     * @param note builds the maker note from its absolute offset, or {@code null} for none
     * @param raw builds the raw image directory's entries from the strip offset
     * @param strip the sensor data
     * @return the file
     */
    private static byte[] tiffEp(TiffParts tiff, List<Entry> first, List<Entry> exif, LongFunction<byte[]> note,
                                 LongFunction<List<Entry>> raw, byte[] strip) {
        var parts = new ArrayList<TiffParts.Part>();
        parts.add(offsets -> tiff.header(42, offsets[1]));
        parts.add(offsets -> {
            var entries = new ArrayList<>(first);
            entries.add(tiff.longs(330, offsets[3]));
            entries.add(tiff.longs(34665, offsets[2]));
            return tiff.directory(entries, offsets[1], 0);
        });
        parts.add(offsets -> exif(tiff, offsets[2], exif, note));
        parts.add(offsets -> tiff.directory(raw.apply(offsets[4]), offsets[3], 0));
        parts.add(offsets -> strip);
        return TiffParts.write(parts);
    }

    /**
     * Returns an EXIF directory whose first out-of-line value is its maker note, so the note starts right after the
     * directory's entries.
     *
     * @param tiff the writer
     * @param start the offset of the directory
     * @param entries the other entries, whose values fit in four bytes
     * @param note builds the maker note from its absolute offset, or {@code null} for none
     * @return the directory
     */
    private static byte[] exif(TiffParts tiff, long start, List<Entry> entries, LongFunction<byte[]> note) {
        var all = new ArrayList<>(entries);
        if (note == null) {
            return tiff.directory(all, start, 0);
        }
        var notePosition = start + 2 + 12L * (all.size() + 1) + 4;
        all.add(TiffParts.undefined(37500, note.apply(notePosition)));
        return tiff.directory(all, start, 0);
    }

    /**
     * Returns the entries of a strip-based color filter array directory of an RGGB pattern.
     *
     * @param tiff the writer
     * @param bits the bits per sample
     * @param compression the TIFF compression
     * @param offset the strip offset
     * @param length the strip length
     * @return the entries
     */
    private static List<Entry> rawDirectory(TiffParts tiff, int width, int height, int bits, int compression,
                                            long offset, int length) {
        return List.of(tiff.longs(254, 0), tiff.longs(256, width), tiff.longs(257, height), tiff.shorts(258, bits),
                tiff.shorts(259, compression), tiff.shorts(262, CFA), tiff.longs(273, offset), tiff.shorts(277, 1),
                tiff.longs(278, height), tiff.longs(279, length), tiff.shorts(33421, 2, 2),
                TiffParts.bytes(33422, 0, 1, 1, 2));
    }

    /**
     * Returns the sample of a window of a sensor image.
     *
     * @param width the sensor width
     * @param height the sensor height
     * @param stored the sensor's photosites as the decoder reads them
     * @param left the window's first column
     * @param top the window's first row
     * @param windowWidth the window width
     * @param windowHeight the window height
     * @param black the black level of each 2x2 position
     * @param white the white level
     * @return the sample
     */
    private static RawSample sample(byte[] file, int width, int height, int[] stored, int left, int top,
                                    int windowWidth, int windowHeight, double[] black, double white) {
        var window = new int[windowWidth * windowHeight];
        for (var y = 0; y < windowHeight; y++) {
            System.arraycopy(stored, (top + y) * width + left, window, y * windowWidth, windowWidth);
        }
        return new RawSample(file, windowWidth, windowHeight, window, black, white);
    }

    /**
     * {@return the black levels of a 2x2 pattern with one level}
     *
     * @param black the level
     */
    private static double[] uniform(double black) {
        return new double[]{black, black, black, black};
    }

    /**
     * {@return the identity curve of a size}
     *
     * @param size the number of entries
     */
    private static int[] identity(int size) {
        var curve = new int[size];
        for (var index = 0; index < size; index++) {
            curve[index] = index;
        }
        return curve;
    }

    /**
     * Returns the canonical codes of code lengths: codes of one length are consecutive, in index order, and each
     * length's first code follows the last code of the length before.
     *
     * @param lengths the length of each value's code
     * @return the code of each value
     */
    private static int[] canonical(int[] lengths) {
        var codes = new int[lengths.length];
        var code = 0;
        for (var length = 1; length <= 16; length++) {
            for (var index = 0; index < lengths.length; index++) {
                if (lengths[index] == length) {
                    codes[index] = code++;
                }
            }
            code <<= 1;
        }
        return codes;
    }

    /**
     * Returns the canonical codes of a table in the form of a JPEG {@code DHT} segment, by value.
     *
     * @param spec the table
     * @return each known value's code and code length
     */
    private static Map<Integer, int[]> codes(int[] spec) {
        var codes = new HashMap<Integer, int[]>();
        var code = 0;
        var index = 16;
        for (var length = 1; length <= 16; length++) {
            for (var count = 0; count < spec[length - 1]; count++) {
                var value = spec[index++];
                if (value != UNKNOWN) {
                    codes.put(value, new int[]{code, length});
                }
                code++;
            }
            code <<= 1;
        }
        return codes;
    }

    /**
     * {@return the number of bits of a difference's magnitude, as in a JPEG scan}
     *
     * @param value the difference
     */
    private static int category(int value) {
        return 32 - Integer.numberOfLeadingZeros(Math.abs(value));
    }

    /**
     * {@return the bits that code a difference of a category, as in a JPEG scan}
     *
     * @param value the difference
     * @param category its category
     */
    private static int magnitude(int value, int category) {
        return value >= 0 ? value : value + (1 << category) - 1;
    }

    /**
     * Returns 16-bit values as bytes of a byte order.
     *
     * @param order the byte order
     * @return the bytes
     */
    private static byte[] words(int[] values, ByteOrder order) {
        var buffer = ByteBuffer.allocate(values.length * 2).order(order);
        for (var value : values) {
            buffer.putShort((short) value);
        }
        return buffer.array();
    }

    /**
     * {@return byte arrays joined in order}
     *
     * @param parts the arrays
     */
    private static byte[] concat(byte[]... parts) {
        var out = new ByteArrayOutputStream();
        for (var part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }
}
