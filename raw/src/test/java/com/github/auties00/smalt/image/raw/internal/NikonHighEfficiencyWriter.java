package com.github.auties00.smalt.image.raw.internal;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Writes Nikon high-efficiency codestreams and NEF files around them for the tests, in the layout of the camera
 * files: the capabilities marker of intoPIX, the 39-byte picture header, the three-byte component table entries, the
 * weights table in packet order, slices of precincts of two component lines, seven-byte packet headers, significance
 * flags outside the packet lengths and bit-plane counts predicted vertically from the band line above (from zero in
 * the first precinct row of a slice). With the default quantization of zero the coefficients are written exactly;
 * a nonzero quantization truncates each band to the position the weights table and the precinct's quantization and
 * refinement give, and codes the magnitudes without their truncated bit-planes.
 */
final class NikonHighEfficiencyWriter {
    /** The width of the color filter array. */
    int width = 64;
    /** The height of the color filter array, a multiple of 4. */
    int height = 16;
    /** The horizontal levels of each component, 0 for an undecomposed one. */
    int[] levels = {5, 5, 0, 5};
    /** The precinct rows of a slice. */
    int sliceRows = 2;
    /** Whether packets carry their bit-plane counts raw instead of predicted. */
    boolean raw;
    /** Whether groups of eight code groups whose counts equal the prediction are flagged insignificant. */
    boolean significance = true;
    /** The coding mode of every band: bit 0 predicts the counts vertically, bit 1 codes significance flags. */
    int mode = 3;
    /** The quantization of every precinct. */
    int quantization;
    /** The refinement of every precinct: bands whose priority, their weights table index, is below it keep a plane. */
    int refinement;
    /** Whether the code groups past the end of a band line carry nonzero magnitudes, with their sign bits. */
    boolean padding;

    /**
     * {@return the truncation position of the band of weights table index {@code weight}}: its gain is 0 and its
     * priority is {@code weight}.
     *
     * @param weight the band's index in the weights table
     */
    int truncation(int weight) {
        var pump = weight < refinement ? 1 : 0;
        return quantization < pump ? 0 : Math.min(quantization - pump, 15);
    }

    private record Band(int component, int x, int y, int width, int lines, int weight) {
        int groups() {
            return (width + 3) / 4;
        }
    }

    private record Part(Band band, int line) {
    }

    private int componentWidth() {
        return width / 2;
    }

    private int componentHeight() {
        return height / 2;
    }

    private static int ceilShift(int value, int shift) {
        return (value + (1 << shift) - 1) >> shift;
    }

    private List<Part[]> packets() {
        var cw = componentWidth();
        var lowHeight = (componentHeight() + 1) / 2;
        var lists = new ArrayList<Part[]>();
        var weight = 0;
        var plain = new Band[4];
        var high = new Band[4][];
        for (var line = 0; line < 2; line++) {
            for (var c = 0; c < 4; c++) {
                var n = levels[c];
                if (n == 0) {
                    if (line == 0) {
                        plain[c] = new Band(c, 0, 0, cw, 2, weight++);
                    }
                    lists.add(new Part[]{new Part(plain[c], line)});
                    continue;
                }
                var half = (cw + 1) / 2;
                if (line == 0) {
                    var parts = new Part[n + 1];
                    parts[0] = new Part(new Band(c, 0, 0, ceilShift(cw, n), 1, weight++), 0);
                    for (var depth = n; depth >= 2; depth--) {
                        var x = ceilShift(cw, depth);
                        parts[n - depth + 1] = new Part(new Band(c, x, 0, ceilShift(cw, depth - 1) - x, 1, weight++), 0);
                    }
                    parts[n] = new Part(new Band(c, half, 0, cw - half, 1, weight++), 0);
                    lists.add(parts);
                    high[c] = new Band[]{new Band(c, 0, lowHeight, half, 1, -1), new Band(c, half, lowHeight, cw - half, 1, -1)};
                } else {
                    lists.add(new Part[]{new Part(new Band(c, 0, lowHeight, high[c][0].width(), 1, weight++), 0),
                            new Part(new Band(c, high[c][1].x(), lowHeight, high[c][1].width(), 1, weight++), 0)});
                }
            }
        }
        return lists;
    }

    /**
     * Writes the codestream of coefficient planes of {@code width / 2} by {@code height / 2} each.
     *
     * @param planes the coefficients of each component, at the places the decoder writes them
     * @return the codestream
     */
    byte[] codestream(int[][] planes) {
        var packets = packets();
        var bands = 0;
        for (var c = 0; c < 4; c++) {
            bands += levels[c] == 0 ? 1 : levels[c] + 3;
        }
        var body = new ByteArrayOutputStream();
        var precinctRows = componentHeight() / 2;
        var slices = (precinctRows + sliceRows - 1) / sliceRows;
        var above = new int[bands][2][];
        for (var s = 0; s < slices; s++) {
            body.write(0xFF);
            body.write(0x20);
            body.write(0);
            body.write(4);
            body.write(s >> 8);
            body.write(s);
            for (var b = 0; b < bands; b++) {
                above[b][0] = null;
                above[b][1] = null;
            }
            var rows = Math.min(sliceRows, precinctRows - s * sliceRows);
            for (var r = 0; r < rows; r++) {
                var row = s * sliceRows + r;
                var current = new int[bands][2][];
                var precinct = new ByteArrayOutputStream();
                for (var packet : packets) {
                    precinct.writeBytes(packet(planes, packet, row, above, current));
                }
                var header = new Bits();
                header.write(precinct.size(), 24);
                header.write(quantization, 8);
                header.write(refinement, 8);
                for (var b = 0; b < bands; b++) {
                    header.write(mode, 2);
                }
                body.writeBytes(header.bytes());
                body.writeBytes(precinct.toByteArray());
                above = current;
            }
        }
        body.write(0xFF);
        body.write(0x11);
        var head = mainHeader(bands);
        var length = head.length + body.size();
        ByteBuffer.wrap(head).putInt(0x26 + 4, length);
        var out = new ByteArrayOutputStream();
        out.writeBytes(head);
        out.writeBytes(body.toByteArray());
        return out.toByteArray();
    }

    private byte[] mainHeader(int bands) {
        var out = ByteBuffer.allocate(0x26 + 2 + 39 + 2 + 14 + 2 + 2 + 2 * bands).order(ByteOrder.BIG_ENDIAN);
        out.putShort((short) 0xFF10);
        out.putShort((short) 0xFF50).putShort((short) 34);
        out.put("CONTACT_INTOPIX_".getBytes(StandardCharsets.US_ASCII));
        out.put(new byte[]{(byte) 0xEF, (byte) 0xC0}).put(new byte[14]);
        out.putShort((short) 0xFF12).putShort((short) 39);
        out.putInt(0).putShort((short) 0).putShort((short) 0);
        out.putShort((short) width).putShort((short) height).putShort((short) 0).putShort((short) sliceRows);
        out.put((byte) 4).put((byte) 4).put((byte) 8).put((byte) 18).put((byte) 0x44).put((byte) 0x10).put((byte) 0x51)
                .put((byte) 0x14);
        out.put(new byte[]{0x50, (byte) 0x88, 0x70, (byte) 0x83, (byte) 0xF0, 0x15, 0x23, (byte) 0xD1, 0x49, (byte) 0xCD,
                0x3F, 0x7F, 0x07});
        out.putShort((short) 0xFF13).putShort((short) 14);
        for (var c = 0; c < 4; c++) {
            out.put((byte) 14).put((byte) 0x11).put((byte) (levels[c] == 0 ? 0 : levels[c] << 4 | 1));
        }
        out.putShort((short) 0xFF14).putShort((short) (2 + 2 * bands));
        for (var b = 0; b < bands; b++) {
            out.put((byte) 0).put((byte) b);
        }
        return Arrays.copyOf(out.array(), out.position());
    }

    private byte[] packet(int[][] planes, Part[] packet, int row, int[][][] above, int[][][] current) {
        var cw = componentWidth();
        var flags = new Bits();
        var counts = new Bits();
        var data = new Bits();
        var signs = new Bits();
        for (var part : packet) {
            var band = part.band();
            var groups = band.groups();
            var gtli = truncation(band.weight());
            var gcli = new int[groups];
            var base = (band.y() + row * band.lines() + part.line()) * cw + band.x();
            for (var g = 0; g < groups; g++) {
                var max = 0;
                for (var i = g * 4; i < Math.min(g * 4 + 4, band.width()); i++) {
                    max = Math.max(max, Math.abs(planes[band.component()][base + i]));
                }
                gcli[g] = Math.max(32 - Integer.numberOfLeadingZeros(max), gtli);
            }
            current[band.weight()][part.line()] = gcli;
            int[] top;
            if (band.lines() == 2 && part.line() == 1) {
                top = current[band.weight()][0];
            } else {
                top = above[band.weight()][band.lines() == 2 ? 1 : 0];
            }
            // the truncation of the line above is that of this band, or zero above the first precinct of a slice
            var topTruncation = top == null ? 0 : gtli;
            if (top == null) {
                top = new int[groups];
            }
            if (raw) {
                for (var g = 0; g < groups; g++) {
                    counts.write(gcli[g], 4);
                }
            } else {
                var vertical = (mode & 1) != 0;
                var flagged = (mode & 2) != 0;
                var predicted = new int[groups];
                for (var g = 0; g < groups; g++) {
                    predicted[g] = vertical ? Math.max(top[g], Math.max(topTruncation, gtli)) : gtli;
                }
                var insignificant = new boolean[(groups + 7) / 8];
                if (flagged) {
                    for (var k = 0; k < insignificant.length; k++) {
                        var all = significance;
                        for (var g = k * 8; g < Math.min(k * 8 + 8, groups); g++) {
                            all &= gcli[g] == predicted[g];
                        }
                        insignificant[k] = all;
                        flags.write(all ? 1 : 0, 1);
                    }
                }
                for (var g = 0; g < groups; g++) {
                    if (insignificant[g / 8]) {
                        continue;
                    }
                    int x;
                    if (!vertical) {
                        x = gcli[g] - gtli;
                    } else {
                        var threshold = Math.max(predicted[g] - gtli, 0);
                        var delta = gcli[g] - predicted[g];
                        if (delta > threshold) {
                            x = delta + threshold;
                        } else if (delta > 0) {
                            x = 2 * delta;
                        } else if (delta < 0) {
                            x = -2 * delta - 1;
                        } else {
                            x = 0;
                        }
                    }
                    counts.write((1L << x) - 1, x);
                    counts.write(0, 1);
                }
            }
            var padded = 0;
            for (var g = 0; g < groups; g++) {
                for (var plane = gcli[g] - 1; plane >= gtli; plane--) {
                    var nibble = 0;
                    for (var i = 0; i < 4; i++) {
                        var x = g * 4 + i;
                        // a padded coefficient takes the group's top bit-plane
                        var magnitude = x < band.width() ? Math.abs(planes[band.component()][base + x])
                                : padding ? 1 << gcli[g] - 1 : 0;
                        nibble = nibble << 1 | (magnitude >> plane) & 1;
                    }
                    data.write(nibble, 4);
                }
                if (padding && gcli[g] > gtli) {
                    padded += Math.max(g * 4 + 4 - band.width(), 0);
                }
            }
            for (var i = 0; i < band.width(); i++) {
                var value = planes[band.component()][base + i];
                if (Math.abs(value) >> gtli != 0) {
                    signs.write(value < 0 ? 1 : 0, 1);
                }
            }
            for (var i = 0; i < padded; i++) {
                signs.write(1, 1);
            }
        }
        var countBytes = counts.bytes();
        var dataBytes = data.bytes();
        var signBytes = signs.bytes();
        var header = new Bits();
        header.write(raw ? 1 : 0, 1);
        header.write(dataBytes.length, 20);
        header.write(countBytes.length, 20);
        header.write(signBytes.length, 15);
        var out = new ByteArrayOutputStream();
        out.writeBytes(header.bytes());
        if (!raw && (mode & 2) != 0) {
            out.writeBytes(flags.bytes());
        }
        out.writeBytes(countBytes);
        out.writeBytes(dataBytes);
        out.writeBytes(signBytes);
        return out.toByteArray();
    }

    /**
     * Wraps a codestream into a NEF file: a little-endian TIFF structure whose first directory names the camera and
     * holds the color filter array image, with a Nikon maker note giving the black levels.
     *
     * @param codestream the codestream
     * @return the file
     */
    byte[] nef(byte[] codestream) {
        var make = "NIKON CORPORATION\0".getBytes(StandardCharsets.US_ASCII);
        var model = "NIKON Z 6_3\0".getBytes(StandardCharsets.US_ASCII);
        var entries = 15;
        var ifd = 8;
        var exif = ifd + 2 + entries * 12 + 4;
        var exifEntries = 1;
        var note = exif + 2 + exifEntries * 12 + 4;
        var noteBytes = makerNote();
        var makeOffset = note + noteBytes.length;
        var modelOffset = makeOffset + make.length;
        var strip = modelOffset + model.length + (modelOffset + model.length & 1);
        var out = ByteBuffer.allocate(strip + codestream.length).order(ByteOrder.LITTLE_ENDIAN);
        out.put((byte) 'I').put((byte) 'I').putShort((short) 42).putInt(ifd);
        out.putShort((short) entries);
        entry(out, 254, 4, 1, 0);
        entry(out, 256, 4, 1, width);
        entry(out, 257, 4, 1, height);
        entry(out, 258, 3, 1, 14);
        entry(out, 259, 3, 1, 34713);
        entry(out, 262, 3, 1, 32803);
        entry(out, 271, 2, make.length, makeOffset);
        entry(out, 272, 2, model.length, modelOffset);
        entry(out, 273, 4, 1, strip);
        entry(out, 277, 3, 1, 1);
        entry(out, 278, 4, 1, height);
        entry(out, 279, 4, 1, codestream.length);
        out.putShort((short) 33421).putShort((short) 3).putInt(2).putShort((short) 2).putShort((short) 2);
        out.putShort((short) 33422).putShort((short) 1).putInt(4).put(new byte[]{0, 1, 1, 2});
        entry(out, 34665, 4, 1, exif);
        out.putInt(0);
        out.putShort((short) exifEntries);
        entry(out, 37500, 7, noteBytes.length, note);
        out.putInt(0);
        out.put(noteBytes);
        out.put(make);
        out.put(model);
        out.position(strip);
        out.put(codestream);
        return out.array();
    }

    // a maker note of one tag, 0x3D, the black level of each filter color (1008 as the cameras write)
    private static byte[] makerNote() {
        var out = ByteBuffer.allocate(10 + 8 + 2 + 12 + 4 + 8).order(ByteOrder.BIG_ENDIAN);
        out.put("Nikon\0".getBytes(StandardCharsets.US_ASCII)).put((byte) 2).put((byte) 0x10).put((byte) 0).put((byte) 0);
        out.put((byte) 'M').put((byte) 'M').putShort((short) 42).putInt(8);
        out.putShort((short) 1);
        out.putShort((short) 0x3D).putShort((short) 3).putInt(4).putInt(8 + 2 + 12 + 4);
        out.putInt(0);
        for (var c = 0; c < 4; c++) {
            out.putShort((short) 1008);
        }
        return out.array();
    }

    private static void entry(ByteBuffer out, int tag, int type, int count, int value) {
        out.putShort((short) tag).putShort((short) type).putInt(count);
        if (type == 3 && count == 1) {
            out.putShort((short) value).putShort((short) 0);
        } else {
            out.putInt(value);
        }
    }

    /**
     * Writes bits most significant first.
     */
    private static final class Bits {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private int current;
        private int count;

        void write(long value, int bits) {
            for (var i = bits - 1; i >= 0; i--) {
                current = current << 1 | (int) (value >>> i) & 1;
                if (++count == 8) {
                    out.write(current);
                    current = 0;
                    count = 0;
                }
            }
        }

        byte[] bytes() {
            var copy = new ByteArrayOutputStream();
            copy.writeBytes(out.toByteArray());
            if (count > 0) {
                copy.write(current << (8 - count));
            }
            return copy.toByteArray();
        }
    }
}
