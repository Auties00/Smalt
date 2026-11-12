/* Ports SVT-JPEG-XS 0.9.0 (Packing.c, Decoder.c, ParseHeader.c, Dequant.c, EncDec.c). Copyright 2024 Intel
   Corporation; BSD-2-Clause-Patent. Modified for Smalt. */
package com.github.auties00.smalt.image.jxs.internal;

import com.github.auties00.smalt.MediaException;

/**
 * Decodes the precincts of JPEG XS slices (ISO/IEC 21122-1 annex C) into wavelet coefficients.
 *
 * <p>The coefficients are written into one plane per component in which every band sits at its
 * {@link JxsLayout#bandX} and {@link JxsLayout#bandY} position. Each slice is coded independently, so slices may be
 * decoded by different instances in parallel. Instances are not thread-safe.
 */
public final class JxsSliceDecoder {
    private static final int VERTICAL = 1;
    private static final int SIGNIFICANCE = 2;
    private static final int MAX_TRUNCATION = 15;
    private static final int MAX_BIT_PLANES = 31;

    private final JxsLayout layout;
    private final int[][] planes;
    private final byte[] data;
    private final int end;
    private final JxsBitReader reader;
    private final int rawBits;
    private final boolean separateSigns;
    private final boolean uniform;
    private final boolean runZero;
    private final int headerBytes;
    private final int packetHeaderBytes;
    private final int[][] gains;
    private final int[][] priorities;
    /**
     * The coding mode {@code D[p,b]} of each global band of the current precinct, -1 for missing bands.
     */
    private final int[] modes;
    private final int[][] truncation;
    /**
     * The bit-plane counts of the current precinct, {@code [c][b][line * groups + i]}.
     */
    private int[][][] counts;
    /**
     * The bit-plane counts of the precinct above, per column.
     */
    private final int[][][][] above;
    private final int[][][] aboveTruncation;
    /**
     * The insignificance flags of the band line of the current packet, per band.
     */
    private final boolean[][][] insignificant;
    /**
     * The number of sign bits of nonzero padding coefficients of the band line of the current packet, per band.
     */
    private final int[][] paddingSigns;
    private final int[] packetComponent;
    private final int[] packetBand;
    private final int[] packetGlobal;

    /**
     * Constructs a decoder writing into {@code planes}.
     *
     * @param header the main header
     * @param layout the picture layout
     * @param planes one coefficient plane per component, {@code componentWidth * componentHeight} values each
     * @param data the codestream
     * @param end the end of the codestream (exclusive)
     */
    public JxsSliceDecoder(JxsHeader header, JxsLayout layout, int[][] planes, byte[] data, int end) {
        this.layout = layout;
        this.planes = planes;
        this.data = data;
        this.end = end;
        this.reader = new JxsBitReader(data, end);
        this.rawBits = header.rawBits;
        this.separateSigns = header.signHandling == 1;
        this.uniform = header.quantizer == 1;
        this.runZero = header.runMode == 1;
        this.headerBytes = precinctHeaderBytes(layout);
        this.packetHeaderBytes = layout.shortHeaders ? 5 : 7;
        var components = layout.components;
        gains = new int[components][];
        priorities = new int[components][];
        truncation = new int[components][];
        counts = new int[components][][];
        insignificant = new boolean[components][][];
        paddingSigns = new int[components][];
        var columns = layout.precinctColumns;
        above = new int[columns][components][][];
        aboveTruncation = new int[columns][components][];
        for (var c = 0; c < components; c++) {
            var bands = layout.bandCount[c];
            gains[c] = new int[bands];
            priorities[c] = new int[bands];
            truncation[c] = new int[bands];
            counts[c] = new int[bands][];
            insignificant[c] = new boolean[bands][];
            paddingSigns[c] = new int[bands];
            for (var column = 0; column < columns; column++) {
                above[column][c] = new int[bands][];
                aboveTruncation[column][c] = new int[bands];
            }
            for (var b = 0; b < bands; b++) {
                var groups = groups(Math.max(layout.precinctBandWidth[c][b], layout.lastPrecinctBandWidth[c][b]));
                var size = groups * layout.bandLines[c][b];
                counts[c][b] = new int[size];
                insignificant[c][b] = new boolean[(groups + 7) >>> 3];
                for (var column = 0; column < columns; column++) {
                    above[column][c][b] = new int[size];
                }
            }
        }
        var index = 0;
        for (var g = 0; g < layout.globalBands; g++) {
            var c = layout.globalComponent[g];
            if (c >= 0) {
                gains[c][layout.globalBand[g]] = header.gains[index];
                priorities[c][layout.globalBand[g]] = header.priorities[index];
                index++;
            }
        }
        modes = new int[layout.globalBands];
        var widest = 0;
        for (var bands : layout.packetBands) {
            widest = Math.max(widest, bands.length);
        }
        packetComponent = new int[widest];
        packetBand = new int[widest];
        packetGlobal = new int[widest];
    }

    /**
     * Returns the position of every slice header of a codestream, followed by the position of its {@code EOC}
     * marker, without decoding the precincts.
     *
     * @param header the main header
     * @param layout the picture layout
     * @param data the codestream
     * @param start the position of the first slice header
     * @param end the end of the codestream (exclusive)
     * @return the position of each slice header, followed by the position of the {@code EOC} marker
     * @throws MediaException.Malformed if a slice header is missing or a precinct runs past the end
     */
    public static int[] locateSlices(JxsHeader header, JxsLayout layout, byte[] data, int start, int end) {
        var positions = new int[layout.slices + 1];
        var headerBytes = precinctHeaderBytes(layout);
        long position = start;
        for (var slice = 0; slice < layout.slices; slice++) {
            checkSliceHeader(data, (int) position, end, slice);
            positions[slice] = (int) position;
            position += 6;
            var precincts = (long) rowsOf(layout, slice) * layout.precinctColumns;
            for (var p = 0L; p < precincts; p++) {
                if (position + headerBytes > end) {
                    throw new MediaException.Malformed("a JPEG XS precinct header runs past the end of the data");
                }
                var i = (int) position;
                var length = (data[i] & 0xFF) << 16 | (data[i + 1] & 0xFF) << 8 | data[i + 2] & 0xFF;
                position += headerBytes + length;
                if (position > end) {
                    throw new MediaException.Malformed("a JPEG XS precinct runs past the end of the data");
                }
            }
        }
        positions[layout.slices] = (int) position;
        return positions;
    }

    /**
     * Returns the number of precinct rows of slice {@code slice}.
     *
     * @param layout the picture layout
     * @param slice the slice index
     * @return the number of precinct rows of the slice
     */
    static int rowsOf(JxsLayout layout, int slice) {
        return slice < layout.slices - 1 ? layout.rowsPerSlice
                : layout.precinctRows - (layout.slices - 1) * layout.rowsPerSlice;
    }

    /**
     * Decodes the precincts of one slice into the coefficient planes.
     *
     * @param slice the slice index
     * @param position the position of its slice header
     * @throws MediaException.Malformed if the slice is invalid
     */
    public void decodeSlice(int slice, int position) {
        checkSliceHeader(data, position, end, slice);
        var next = position + 6;
        var rows = rowsOf(layout, slice);
        for (var line = 0; line < rows; line++) {
            var row = slice * layout.rowsPerSlice + line;
            for (var column = 0; column < layout.precinctColumns; column++) {
                next = precinct(next, row, column, line == 0);
            }
        }
    }

    /**
     * Returns the truncation position of a band for the quantization and refinement of a precinct.
     *
     * @param gain the gain {@code G[b]} of the band
     * @param priority the priority {@code P[b]} of the band
     * @param quantization the quantization {@code Q[p]} of the precinct
     * @param refinement the refinement {@code R[p]} of the precinct
     * @return the truncation position {@code T[p,b]}
     */
    static int truncation(int gain, int priority, int quantization, int refinement) {
        var value = quantization - gain - (priority < refinement ? 1 : 0);
        return Math.clamp(value, 0, MAX_TRUNCATION);
    }

    private static int precinctHeaderBytes(JxsLayout layout) {
        return 5 + (2 * layout.existingBands + 7) / 8;
    }

    private static int groups(int width) {
        return (width + 3) >>> 2;
    }

    private static void checkSliceHeader(byte[] data, int position, int end, int slice) {
        if (position < 0 || position + 6 > end) {
            throw new MediaException.Malformed("JPEG XS slice " + slice + " is missing");
        }
        if (JxsHeader.u16(data, position) != JxsHeader.SLH || JxsHeader.u16(data, position + 2) != 4
                || JxsHeader.u16(data, position + 4) != slice) {
            throw new MediaException.Malformed("JPEG XS slice " + slice + " has an invalid slice header");
        }
    }

    private int lines(int c, int b, int row) {
        return row == layout.precinctRows - 1 ? layout.lastPrecinctBandLines[c][b] : layout.bandLines[c][b];
    }

    private int width(int c, int b, int column) {
        return column == layout.precinctColumns - 1 ? layout.lastPrecinctBandWidth[c][b]
                : layout.precinctBandWidth[c][b];
    }

    /**
     * Decodes the precinct whose header starts at {@code position} and returns the position after it.
     */
    private int precinct(int position, int row, int column, boolean first) {
        if ((long) position + headerBytes > end) {
            throw new MediaException.Malformed("a JPEG XS precinct header runs past the end of the data");
        }
        reader.seek(position);
        var length = reader.read(24);
        var quantization = reader.read(8);
        var refinement = reader.read(8);
        for (var g = 0; g < layout.globalBands; g++) {
            if (layout.globalComponent[g] < 0) {
                modes[g] = -1;
                continue;
            }
            var mode = reader.read(2);
            if (first && (mode & VERTICAL) != 0) {
                throw new MediaException.Malformed("a JPEG XS precinct in the first row of a slice uses vertical "
                        + "prediction");
            }
            modes[g] = mode;
        }
        var body = position + headerBytes;
        var bodyEnd = (long) body + length;
        if (bodyEnd > end) {
            throw new MediaException.Malformed("a JPEG XS precinct runs past the end of the data");
        }
        for (var c = 0; c < layout.components; c++) {
            for (var b = 0; b < layout.bandCount[c]; b++) {
                truncation[c][b] = truncation(gains[c][b], priorities[c][b], quantization, refinement);
            }
        }
        var cursor = (long) body;
        for (var p = 0; p < layout.packets; p++) {
            cursor = packet(p, cursor, bodyEnd, row, column);
        }
        var columnAbove = above[column];
        above[column] = counts;
        counts = columnAbove;
        var truncationAbove = aboveTruncation[column];
        for (var c = 0; c < layout.components; c++) {
            System.arraycopy(truncation[c], 0, truncationAbove[c], 0, truncation[c].length);
        }
        return (int) bodyEnd;
    }

    /**
     * Decodes packet {@code p} of the current precinct from {@code position} and returns the position after it.
     */
    private long packet(int p, long position, long bodyEnd, int row, int column) {
        var line = layout.packetLine[p];
        var size = 0;
        for (var g : layout.packetBands[p]) {
            var c = layout.globalComponent[g];
            var b = layout.globalBand[g];
            if (line < lines(c, b, row)) {
                packetComponent[size] = c;
                packetBand[size] = b;
                packetGlobal[size] = g;
                size++;
            }
        }
        if (size == 0) {
            return position;
        }
        if (position + packetHeaderBytes > bodyEnd) {
            throw new MediaException.Malformed("a JPEG XS packet header overruns its precinct");
        }
        reader.seek((int) position);
        var raw = reader.bit() == 1;
        int dataLength;
        int countLength;
        int signLength;
        if (layout.shortHeaders) {
            dataLength = reader.read(15);
            countLength = reader.read(13);
            signLength = reader.read(11);
        } else {
            dataLength = reader.read(20);
            countLength = reader.read(20);
            signLength = reader.read(15);
        }
        if (!separateSigns) {
            signLength = 0;
        }
        position += packetHeaderBytes;
        if (raw) {
            position = rawCounts(position, countLength, bodyEnd, line, size, column);
        } else {
            reader.seek((int) position);
            for (var k = 0; k < size; k++) {
                var c = packetComponent[k];
                var b = packetBand[k];
                if ((modes[packetGlobal[k]] & SIGNIFICANCE) != 0) {
                    var flags = insignificant[c][b];
                    var count = (groups(width(c, b, column)) + 7) >>> 3;
                    for (var s = 0; s < count; s++) {
                        flags[s] = reader.bit() == 1;
                    }
                }
            }
            reader.align();
            position = reader.bytePosition();
            position = predictedCounts(position, countLength, bodyEnd, line, size, column);
        }
        position = data(position, dataLength, bodyEnd, line, size, row, column);
        if (separateSigns) {
            position = signs(position, signLength, bodyEnd, line, size, row, column);
        }
        return position;
    }

    private void checkSubPacket(long start, long declaredEnd, long bodyEnd) {
        if (declaredEnd > bodyEnd) {
            throw new MediaException.Malformed("a JPEG XS sub-packet overruns its precinct");
        }
        reader.align();
        if (reader.bytePosition() > declaredEnd) {
            throw new MediaException.Malformed("a JPEG XS sub-packet overruns its declared length");
        }
    }

    private long rawCounts(long position, int length, long bodyEnd, int line, int size, int column) {
        var declaredEnd = position + length;
        if (declaredEnd > bodyEnd) {
            throw new MediaException.Malformed("a JPEG XS sub-packet overruns its precinct");
        }
        reader.seek((int) position);
        for (var k = 0; k < size; k++) {
            var c = packetComponent[k];
            var b = packetBand[k];
            var groups = groups(width(c, b, column));
            var target = counts[c][b];
            var offset = line * groups;
            for (var i = 0; i < groups; i++) {
                target[offset + i] = reader.read(rawBits);
            }
        }
        checkSubPacket(position, declaredEnd, bodyEnd);
        return declaredEnd;
    }

    private long predictedCounts(long position, int length, long bodyEnd, int line, int size, int column) {
        var declaredEnd = position + length;
        if (declaredEnd > bodyEnd) {
            throw new MediaException.Malformed("a JPEG XS sub-packet overruns its precinct");
        }
        for (var k = 0; k < size; k++) {
            var c = packetComponent[k];
            var b = packetBand[k];
            var mode = modes[packetGlobal[k]];
            var groups = groups(width(c, b, column));
            var t = truncation[c][b];
            var target = counts[c][b];
            var offset = line * groups;
            var flags = (mode & SIGNIFICANCE) != 0 ? insignificant[c][b] : null;
            if ((mode & VERTICAL) == 0) {
                for (var i = 0; i < groups; i++) {
                    if (flags != null && flags[i >>> 3]) {
                        target[offset + i] = t;
                        continue;
                    }
                    target[offset + i] = t + unary();
                }
            } else {
                int[] top;
                int topOffset;
                int floor;
                if (line == 0) {
                    top = above[column][c][b];
                    topOffset = (layout.bandLines[c][b] - 1) * groups;
                    floor = Math.max(aboveTruncation[column][c][b], t);
                } else {
                    top = target;
                    topOffset = (line - 1) * groups;
                    floor = t;
                }
                for (var i = 0; i < groups; i++) {
                    var predicted = Math.max(top[topOffset + i], floor);
                    if (flags != null && flags[i >>> 3]) {
                        target[offset + i] = runZero ? t : predicted;
                        continue;
                    }
                    var code = unary();
                    var threshold = Math.max(predicted - t, 0);
                    int delta;
                    if (code > 2 * threshold) {
                        delta = code - threshold;
                    } else if ((code & 1) != 0) {
                        delta = -((code + 1) >> 1);
                    } else {
                        delta = code >> 1;
                    }
                    target[offset + i] = predicted + delta;
                }
            }
        }
        checkSubPacket(position, declaredEnd, bodyEnd);
        return declaredEnd;
    }

    private int unary() {
        var value = reader.unary();
        if (value < 0) {
            throw new MediaException.Malformed("a JPEG XS bit-plane count code is too long");
        }
        return value;
    }

    private long data(long position, int length, long bodyEnd, int line, int size, int row, int column) {
        var declaredEnd = position + length;
        if (declaredEnd > bodyEnd) {
            throw new MediaException.Malformed("a JPEG XS sub-packet overruns its precinct");
        }
        reader.seek((int) position);
        for (var k = 0; k < size; k++) {
            var c = packetComponent[k];
            var b = packetBand[k];
            var width = width(c, b, column);
            var groups = groups(width);
            var t = truncation[c][b];
            var gcli = counts[c][b];
            var offset = line * groups;
            var plane = planes[c];
            var base = base(c, b, row, column, line);
            var padding = 0;
            for (var group = 0; group < groups; group++) {
                var count = gcli[offset + group];
                if (count > MAX_BIT_PLANES) {
                    throw new MediaException.Malformed("a JPEG XS code group has " + count + " bit planes");
                }
                var bitPlanes = count - t;
                if (bitPlanes <= 0) {
                    continue;
                }
                var signs = separateSigns ? 0 : reader.read(4);
                int m0 = 0;
                int m1 = 0;
                int m2 = 0;
                int m3 = 0;
                for (var k2 = 0; k2 < bitPlanes; k2++) {
                    var nibble = reader.read(4);
                    m0 = m0 << 1 | nibble >>> 3;
                    m1 = m1 << 1 | nibble >>> 2 & 1;
                    m2 = m2 << 1 | nibble >>> 1 & 1;
                    m3 = m3 << 1 | nibble & 1;
                }
                var first = group * 4;
                var available = Math.min(4, width - first);
                var index = base + first;
                for (var i = 0; i < 4; i++) {
                    var magnitude = i == 0 ? m0 : i == 1 ? m1 : i == 2 ? m2 : m3;
                    if (i >= available) {
                        if (magnitude != 0) {
                            padding++;
                        }
                        continue;
                    }
                    if (magnitude == 0) {
                        continue;
                    }
                    var value = dequantize(magnitude, count, t);
                    plane[index + i] = (signs & 8 >>> i) != 0 ? -value : value;
                }
            }
            paddingSigns[c][b] = padding;
        }
        checkSubPacket(position, declaredEnd, bodyEnd);
        return declaredEnd;
    }

    /**
     * Returns the reconstructed magnitude of a coefficient of {@code magnitude} in a code group of {@code count} bit
     * planes truncated at {@code t} (annex D).
     */
    private int dequantize(int magnitude, int count, int t) {
        if (t == 0) {
            return magnitude;
        }
        var value = magnitude << t;
        if (!uniform) {
            return value | 1 << (t - 1);
        }
        var scale = count - t + 1;
        long sum = 0;
        for (long v = value & 0xFFFFFFFFL; v > 0; v >>>= scale) {
            sum += v;
        }
        return (int) sum;
    }

    private long signs(long position, int length, long bodyEnd, int line, int size, int row, int column) {
        var declaredEnd = position + length;
        if (declaredEnd > bodyEnd) {
            throw new MediaException.Malformed("a JPEG XS sub-packet overruns its precinct");
        }
        reader.seek((int) position);
        for (var k = 0; k < size; k++) {
            var c = packetComponent[k];
            var b = packetBand[k];
            var width = width(c, b, column);
            var plane = planes[c];
            var base = base(c, b, row, column, line);
            for (var i = 0; i < width; i++) {
                var value = plane[base + i];
                if (value != 0 && reader.bit() == 1) {
                    plane[base + i] = -value;
                }
            }
            var padding = paddingSigns[c][b];
            if (padding > 0) {
                reader.skip(padding);
            }
        }
        checkSubPacket(position, declaredEnd, bodyEnd);
        return declaredEnd;
    }

    private int base(int c, int b, int row, int column, int line) {
        var y = layout.bandY[c][b] + row * layout.bandLines[c][b] + line;
        var x = layout.bandX[c][b] + column * layout.precinctBandWidth[c][b];
        return y * layout.componentWidth[c] + x;
    }
}
