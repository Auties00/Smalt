/* Ports SVT-JPEG-XS 0.9.0 (Packing.c, Dequant.c, EncDec.c). Copyright 2024 Intel Corporation; BSD-2-Clause-Patent.
   Modified for Smalt.
   Ports the ISO/IEC 21122-5 ed. 3 reference software (packing.c, precinct.c: temporal differential coding).
   Copyright intoPIX SA, Fraunhofer IIS and Canon Inc; Copyright License for Evaluation and Testing (see
   THIRD-PARTY-NOTICES). Modified for Smalt. */
package com.github.auties00.smalt.image.jxs.internal;

import com.github.auties00.smalt.MediaException;

/**
 * Decodes the precincts of JPEG XS slices (ISO/IEC 21122-1 annex C) into wavelet coefficients.
 *
 * <p>The coefficients are written, dequantized and signed, into one plane per component in which every band sits at
 * its {@link JxsLayout#bandX} and {@link JxsLayout#bandY} position. Each slice is coded independently, so slices may
 * be decoded by different instances in parallel. Instances are not thread-safe.
 *
 * <p>A codestream of temporal differential coding predicts coefficients from a frame buffer, a plane per component
 * of the same layout holding the coefficients of the previous frame quantized as each precinct header asks; the
 * decoder reads and updates it precinct by precinct, so the precincts of one frame touch disjoint parts of it.
 */
public final class JxsSliceDecoder {
    private static final int VERTICAL = 1;
    private static final int SIGNIFICANCE = 2;
    private static final int TRUNCATION_MAX = 15;
    private static final int MAX_BITPLANES = 31;

    private final JxsHeader header;
    private final JxsLayout layout;
    private final int[][] planes;
    private final byte[] data;
    private final int end;
    private final JxsBitReader reader;
    private final int[][] gains;
    private final int[][] priorities;
    private final State[] states;
    private final int[] modes;
    private final int[] leftover;
    private final int precinctHeaderBytes;
    private final int[][] refreshGains;
    private final int[][] refreshPriorities;
    private final int[] gainIndex;
    private final int[][] framebuffer;
    private final int[] temporalModes;
    private final byte[][] selection;

    /**
     * The bit-plane counts of one precinct, kept for the vertical prediction of the next precinct row.
     */
    private static final class State {
        final int[][] gtli;
        final byte[][][] gcli;
        final byte[][][] insignificant;

        State(JxsLayout layout) {
            gtli = new int[layout.components][];
            gcli = new byte[layout.components][][];
            insignificant = new byte[layout.components][][];
            for (var c = 0; c < layout.components; c++) {
                var bands = layout.bandCount[c];
                gtli[c] = new int[bands];
                gcli[c] = new byte[bands][];
                insignificant[c] = new byte[bands][];
                for (var b = 0; b < bands; b++) {
                    var width = Math.max(layout.precinctBandWidth[c][b], layout.lastPrecinctBandWidth[c][b]);
                    var groups = (width + 3) >>> 2;
                    gcli[c][b] = new byte[groups * layout.bandLines[c][b]];
                    insignificant[c][b] = new byte[((groups + 7) >>> 3) * layout.bandLines[c][b]];
                }
            }
        }
    }

    /**
     * Constructs a decoder writing into {@code planes}.
     *
     * @param header the main header
     * @param layout the picture layout
     * @param planes one plane per component, {@code componentWidth * componentHeight} samples each
     * @param data the codestream
     * @param end the end of the codestream (exclusive)
     * @param framebuffer the frame buffer of temporal differential coding, one plane per component of the layout of
     * {@code planes}, or {@code null} for a codestream without it
     */
    public JxsSliceDecoder(JxsHeader header, JxsLayout layout, int[][] planes, byte[] data, int end,
                           int[][] framebuffer) {
        this.header = header;
        this.layout = layout;
        this.planes = planes;
        this.data = data;
        this.end = end;
        this.reader = new JxsBitReader(data, end);
        this.gains = new int[layout.components][];
        this.priorities = new int[layout.components][];
        for (var c = 0; c < layout.components; c++) {
            gains[c] = new int[layout.bandCount[c]];
            priorities[c] = new int[layout.bandCount[c]];
        }
        this.refreshGains = new int[layout.components][];
        this.refreshPriorities = new int[layout.components][];
        for (var c = 0; c < layout.components; c++) {
            refreshGains[c] = new int[layout.bandCount[c]];
            refreshPriorities[c] = new int[layout.bandCount[c]];
        }
        this.gainIndex = new int[layout.globalBands];
        var index = 0;
        for (var g = 0; g < layout.globalBands; g++) {
            var c = layout.globalComponent[g];
            if (c < 0) {
                gainIndex[g] = -1;
                continue;
            }
            gainIndex[g] = index;
            gains[c][layout.globalBand[g]] = header.gains[index];
            priorities[c][layout.globalBand[g]] = header.priorities[index];
            if (header.refreshGains != null && index < header.refreshGains.length) {
                refreshGains[c][layout.globalBand[g]] = header.refreshGains[index];
                refreshPriorities[c][layout.globalBand[g]] = header.refreshPriorities[index];
            }
            index++;
        }
        this.framebuffer = header.temporal ? framebuffer : null;
        this.temporalModes = new int[layout.globalBands];
        var maxGroups = 1;
        var span = 4 * header.selectionGroups;
        for (var c = 0; c < layout.components; c++) {
            for (var b = 0; b < layout.bandCount[c]; b++) {
                var width = Math.max(layout.precinctBandWidth[c][b], layout.lastPrecinctBandWidth[c][b]);
                maxGroups = Math.max(maxGroups, (width + span - 1) / span);
            }
        }
        this.selection = header.temporal ? new byte[layout.globalBands * 4][maxGroups] : null;
        this.states = new State[layout.precinctColumns + 1];
        for (var i = 0; i < states.length; i++) {
            states[i] = new State(layout);
        }
        this.modes = new int[layout.globalBands];
        this.leftover = new int[layout.globalBands * 4];
        this.precinctHeaderBytes = precinctHeaderBytes(header, layout);
    }

    /**
     * {@return the length of a precinct header of a codestream}
     *
     * @param header the main header
     * @param layout the picture layout
     */
    private static int precinctHeaderBytes(JxsHeader header, JxsLayout layout) {
        return header.temporal ? 7 + (4 * layout.existingBands + 7) / 8 : 5 + (2 * layout.existingBands + 7) / 8;
    }

    /**
     * Finds the start of every slice by reading the precinct lengths, without decoding them.
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
        var headerBytes = precinctHeaderBytes(header, layout);
        var marker = header.temporal ? JxsHeader.SLI : JxsHeader.SLH;
        var position = start;
        for (var s = 0; s < layout.slices; s++) {
            positions[s] = position;
            if (position + 6 > end || JxsHeader.u16(data, position) != marker) {
                throw new MediaException.Malformed("JPEG XS slice " + s + " has no " + (header.temporal ? "SLI" : "SLH")
                        + " slice header");
            }
            if (JxsHeader.u16(data, position + 2) != 4) {
                throw new MediaException.Malformed("JPEG XS slice " + s + " has an invalid slice header length");
            }
            if (JxsHeader.u16(data, position + 4) != s) {
                throw new MediaException.Malformed("JPEG XS slice " + s + " carries index "
                        + JxsHeader.u16(data, position + 4));
            }
            position += 6;
            var rows = rowsOf(layout, s);
            for (var r = 0; r < rows * layout.precinctColumns; r++) {
                if (position + headerBytes > end) {
                    throw new MediaException.Malformed("JPEG XS slice " + s + " is truncated");
                }
                var length = (data[position] & 0xFF) << 16 | (data[position + 1] & 0xFF) << 8
                        | data[position + 2] & 0xFF;
                position += headerBytes;
                if (length > end - position) {
                    throw new MediaException.Malformed("a JPEG XS precinct of slice " + s + " runs past the end of "
                            + "the codestream");
                }
                position += length;
            }
        }
        positions[layout.slices] = position;
        return positions;
    }

    /**
     * {@return the number of precinct rows of slice {@code slice}}
     */
    static int rowsOf(JxsLayout layout, int slice) {
        var first = slice * layout.rowsPerSlice;
        return Math.min(layout.rowsPerSlice, layout.precinctRows - first);
    }

    /**
     * Decodes one slice.
     *
     * @param slice the slice index
     * @param position the position of its slice header
     * @throws MediaException.Malformed if the slice is invalid
     */
    public void decodeSlice(int slice, int position) {
        var rows = rowsOf(layout, slice);
        var cursor = position + 6;
        for (var line = 0; line < rows; line++) {
            var row = slice * layout.rowsPerSlice + line;
            for (var column = 0; column < layout.precinctColumns; column++) {
                var current = states[layout.precinctColumns];
                var top = line == 0 ? null : states[column];
                cursor = decodePrecinct(row, column, current, top, cursor);
                states[layout.precinctColumns] = states[column];
                states[column] = current;
            }
        }
    }

    private int decodePrecinct(int row, int column, State current, State top, int position) {
        var lastRow = row == layout.precinctRows - 1;
        var lastColumn = column == layout.precinctColumns - 1;
        if (position + precinctHeaderBytes > end) {
            throw new MediaException.Malformed("a JPEG XS precinct header is truncated");
        }
        var length = (data[position] & 0xFF) << 16 | (data[position + 1] & 0xFF) << 8 | data[position + 2] & 0xFF;
        var quantization = data[position + 3] & 0xFF;
        var refinement = data[position + 4] & 0xFF;
        var temporal = framebuffer != null;
        reader.seek(position + (temporal ? 7 : 5));
        for (var g = 0; g < layout.globalBands; g++) {
            if (layout.globalComponent[g] < 0) {
                continue;
            }
            var mode = reader.read(2);
            if (top == null && (mode & VERTICAL) != 0) {
                throw new MediaException.Malformed("the first JPEG XS precinct row of a slice uses vertical "
                        + "prediction");
            }
            modes[g] = mode;
        }
        if (temporal) {
            for (var g = 0; g < layout.globalBands; g++) {
                if (layout.globalComponent[g] < 0) {
                    continue;
                }
                temporalModes[g] = reader.read(2);
                if (temporalModes[g] == 1 && header.refreshGains == null) {
                    throw new MediaException.Malformed("a JPEG XS precinct refreshes a band without a WGR marker");
                }
            }
        }
        var bodyStart = position + precinctHeaderBytes;
        var bodyEnd = bodyStart + length;
        if (length > end - bodyStart) {
            throw new MediaException.Malformed("a JPEG XS precinct runs past the end of the codestream");
        }
        for (var c = 0; c < layout.components; c++) {
            for (var b = 0; b < layout.bandCount[c]; b++) {
                current.gtli[c][b] = truncation(gains[c][b], priorities[c][b], quantization, refinement);
            }
        }
        if (temporal) {
            for (var g = 0; g < layout.globalBands; g++) {
                var c = layout.globalComponent[g];
                if (c >= 0 && temporalModes[g] == 1) {
                    var b = layout.globalBand[g];
                    current.gtli[c][b] = truncation(refreshGains[c][b], refreshPriorities[c][b], quantization,
                            refinement);
                }
            }
        }
        var cursor = bodyStart;
        var headerBytes = layout.shortHeaders ? 5 : 7;
        for (var p = 0; p < layout.packets; p++) {
            var line = layout.packetLine[p];
            var present = false;
            for (var g : layout.packetBands[p]) {
                present |= line < lines(layout.globalComponent[g], layout.globalBand[g], lastRow);
            }
            if (!present) {
                continue;
            }
            if (cursor + headerBytes > bodyEnd) {
                throw new MediaException.Malformed("a JPEG XS packet header runs past its precinct");
            }
            int raw;
            int dataLength;
            int gcliLength;
            int signLength;
            if (layout.shortHeaders) {
                var bits = (long) (data[cursor] & 0xFF) << 32 | (long) (data[cursor + 1] & 0xFF) << 24
                        | (data[cursor + 2] & 0xFF) << 16 | (data[cursor + 3] & 0xFF) << 8 | data[cursor + 4] & 0xFF;
                raw = (int) (bits >>> 39);
                dataLength = (int) (bits >>> 24) & 0x7FFF;
                gcliLength = (int) (bits >>> 11) & 0x1FFF;
                signLength = (int) bits & 0x7FF;
            } else {
                var bits = 0L;
                for (var i = 0; i < 7; i++) {
                    bits = bits << 8 | data[cursor + i] & 0xFF;
                }
                raw = (int) (bits >>> 55);
                dataLength = (int) (bits >>> 35) & 0xFFFFF;
                gcliLength = (int) (bits >>> 15) & 0xFFFFF;
                signLength = (int) bits & 0x7FFF;
            }
            cursor += headerBytes;
            if ((long) dataLength + gcliLength + signLength > bodyEnd - cursor) {
                throw new MediaException.Malformed("a JPEG XS packet runs past its precinct");
            }
            reader.seek(cursor);
            if (temporal) {
                selectionFlags(line, p, lastRow, lastColumn);
                cursor = (int) (reader.bitPosition() >>> 3);
            }
            int gcliStart;
            if (raw != 0) {
                gcliStart = cursor;
                for (var g : layout.packetBands[p]) {
                    var c = layout.globalComponent[g];
                    var b = layout.globalBand[g];
                    if (line < lines(c, b, lastRow)) {
                        var groups = groups(c, b, lastColumn);
                        var gcli = current.gcli[c][b];
                        var offset = line * groups;
                        for (var i = 0; i < groups; i++) {
                            gcli[offset + i] = (byte) reader.read(header.rawBits);
                        }
                    }
                }
            } else {
                for (var g : layout.packetBands[p]) {
                    var c = layout.globalComponent[g];
                    var b = layout.globalBand[g];
                    if (line < lines(c, b, lastRow) && (modes[g] & SIGNIFICANCE) != 0) {
                        var groups = groups(c, b, lastColumn);
                        var count = (groups + 7) >>> 3;
                        var flags = current.insignificant[c][b];
                        var offset = line * count;
                        for (var i = 0; i < count; i++) {
                            flags[offset + i] = (byte) reader.bit();
                        }
                    }
                }
                reader.align();
                gcliStart = (int) (reader.bitPosition() >>> 3);
                for (var g : layout.packetBands[p]) {
                    var c = layout.globalComponent[g];
                    var b = layout.globalBand[g];
                    if (line < lines(c, b, lastRow)) {
                        bitplaneCounts(current, top, c, b, line, modes[g], lastColumn);
                    }
                }
            }
            cursor = endOf(gcliStart, gcliLength, bodyEnd, "bit-plane count");
            reader.seek(cursor);
            for (var g : layout.packetBands[p]) {
                var c = layout.globalComponent[g];
                var b = layout.globalBand[g];
                if (line < lines(c, b, lastRow)) {
                    leftover[g * 4 + line] = coefficients(current, c, b, line, row, column, lastColumn);
                }
            }
            cursor = endOf(cursor, dataLength, bodyEnd, "data");
            if (header.signHandling != 0) {
                reader.seek(cursor);
                for (var g : layout.packetBands[p]) {
                    var c = layout.globalComponent[g];
                    var b = layout.globalBand[g];
                    if (line < lines(c, b, lastRow)) {
                        signs(c, b, line, row, column, lastColumn, leftover[g * 4 + line]);
                    }
                }
                cursor = endOf(cursor, signLength, bodyEnd, "sign");
            }
        }
        if (cursor > bodyEnd) {
            throw new MediaException.Malformed("a JPEG XS precinct overruns its length");
        }
        if (temporal) {
            predict(row, column, lastRow, lastColumn, data[position + 5] & 0xFF, data[position + 6] & 0xFF);
        }
        return bodyEnd;
    }

    /**
     * Reads the temporal selection flags of the band lines of a packet ({@code unpack_tdc_group_switch_flags_}): one
     * bit per selection group of a band whose temporal mode is 3, the mode itself for the groups of any other band,
     * the flags padded to a byte when any was read.
     *
     * @param line the line of the bands the packet holds
     * @param packet the packet index
     * @param lastRow whether the precinct is in the last row
     * @param lastColumn whether the precinct is in the last column
     */
    private void selectionFlags(int line, int packet, boolean lastRow, boolean lastColumn) {
        var read = false;
        var span = 4 * header.selectionGroups;
        for (var g : layout.packetBands[packet]) {
            var c = layout.globalComponent[g];
            var b = layout.globalBand[g];
            if (line >= lines(c, b, lastRow)) {
                continue;
            }
            var flags = selection[g * 4 + line];
            var count = (width(c, b, lastColumn) + span - 1) / span;
            var mode = temporalModes[g];
            if (mode == 3) {
                for (var i = 0; i < count; i++) {
                    flags[i] = (byte) (reader.bit() != 0 ? 3 : 0);
                }
                read = true;
            } else {
                for (var i = 0; i < count; i++) {
                    flags[i] = (byte) mode;
                }
            }
        }
        if (read) {
            reader.align();
        }
    }

    /**
     * Undoes the temporal prediction of a decoded precinct and stores its coefficients, quantized, in the frame buffer
     * ({@code precinct_do_inverse_tdc}).
     *
     * <p>A selection group whose flag is 0 or 1 is intra coded and scaled by {@code Qad - Qbi}; one that the periodic
     * refresh of the {@code TPC} marker selects is scaled by {@code Qad - Qbr}; any other adds the frame buffer to the
     * coefficient scaled by {@code Qad}, where {@code Qad} is the largest of {@code Qbi}, {@code Qbr} and 0. The frame
     * buffer keeps each coefficient with the bit-planes below the truncation of the frame buffer quantization and
     * refinement cleared.
     *
     * @param row the precinct row
     * @param column the precinct column
     * @param lastRow whether the precinct is in the last row
     * @param lastColumn whether the precinct is in the last column
     * @param quantization the frame buffer quantization {@code Qf}
     * @param refinement the frame buffer refinement {@code Rf}
     */
    private void predict(int row, int column, boolean lastRow, boolean lastColumn, int quantization, int refinement) {
        var intra = header.intraShift;
        var refresh = header.refreshShift;
        var shift = Math.max(Math.max(intra, refresh), 0);
        var span = 4 * header.selectionGroups;
        var periods = header.refreshPeriods;
        for (var g = 0; g < layout.globalBands; g++) {
            var c = layout.globalComponent[g];
            if (c < 0) {
                continue;
            }
            var b = layout.globalBand[g];
            var mask = ~((1 << truncation(gains[c][b], priorities[c][b], quantization, refinement)) - 1);
            var band = gainIndex[g];
            var period = periods != null && band < periods.length ? (1 << periods[band]) - 1 : 0;
            var offset = period != 0 ? header.refreshOffsets[band] : 0;
            var plane = planes[c];
            var buffer = framebuffer[c];
            var stride = layout.componentWidth[c];
            var width = width(c, b, lastColumn);
            var x = layout.bandX[c][b] + column * layout.precinctBandWidth[c][b];
            for (var line = 0; line < lines(c, b, lastRow); line++) {
                var flags = selection[g * 4 + line];
                var base = (layout.bandY[c][b] + row * layout.bandLines[c][b] + line) * stride + x;
                for (int start = 0, k = 0; start < width; start += span, k++) {
                    var stop = Math.min(width, start + span);
                    var flag = flags[k];
                    var refreshed = period != 0 && ((k - offset) & 0xFF & period) == 0;
                    for (var i = base + start; i < base + stop; i++) {
                        var value = plane[i];
                        if (flag < 2) {
                            value <<= shift - intra;
                        } else if (refreshed) {
                            value <<= shift - refresh;
                        } else {
                            value = (value << shift) + buffer[i];
                        }
                        plane[i] = value;
                        buffer[i] = value < 0 ? -(-value & mask) : value & mask;
                    }
                }
            }
        }
    }

    /**
     * Ends a sub-packet that started at {@code start} and declares {@code declared} bytes: checks that the reader,
     * aligned to a byte, did not pass them and returns the position after them.
     */
    private int endOf(int start, int declared, int bodyEnd, String what) {
        reader.align();
        var consumed = (reader.bitPosition() >>> 3) - start;
        if (consumed > declared) {
            throw new MediaException.Malformed("a JPEG XS " + what + " sub-packet is longer than its declared "
                    + declared + " bytes");
        }
        if (start + declared > bodyEnd) {
            throw new MediaException.Malformed("a JPEG XS " + what + " sub-packet overruns its precinct");
        }
        return start + declared;
    }

    private int lines(int c, int b, boolean lastRow) {
        return lastRow ? layout.lastPrecinctBandLines[c][b] : layout.bandLines[c][b];
    }

    private int width(int c, int b, boolean lastColumn) {
        return lastColumn ? layout.lastPrecinctBandWidth[c][b] : layout.precinctBandWidth[c][b];
    }

    private int groups(int c, int b, boolean lastColumn) {
        return (width(c, b, lastColumn) + 3) >>> 2;
    }

    /**
     * Computes the truncation position of a band ({@code compute_truncation}).
     */
    static int truncation(int gain, int priority, int quantization, int refinement) {
        var pump = priority < refinement ? 1 : 0;
        if (quantization < gain + pump) {
            return 0;
        }
        return Math.min(quantization - gain - pump, TRUNCATION_MAX);
    }

    private void bitplaneCounts(State current, State top, int c, int b, int line, int mode, boolean lastColumn) {
        var groups = groups(c, b, lastColumn);
        var gtli = current.gtli[c][b];
        var gcli = current.gcli[c][b];
        var offset = line * groups;
        var significance = (mode & SIGNIFICANCE) != 0;
        var flags = current.insignificant[c][b];
        var flagOffset = line * ((groups + 7) >>> 3);
        if ((mode & VERTICAL) == 0) {
            for (var i = 0; i < groups; i++) {
                if (significance && flags[flagOffset + (i >>> 3)] != 0) {
                    gcli[offset + i] = (byte) gtli;
                    continue;
                }
                var x = reader.unary();
                if (x < 0) {
                    throw new MediaException.Malformed("a JPEG XS bit-plane count code is too long");
                }
                gcli[offset + i] = (byte) checked(gtli + x);
            }
            return;
        }
        byte[] above;
        int aboveOffset;
        int gtliTop;
        if (line == 0) {
            above = top.gcli[c][b];
            aboveOffset = (layout.bandLines[c][b] - 1) * groups;
            gtliTop = top.gtli[c][b];
        } else {
            above = gcli;
            aboveOffset = (line - 1) * groups;
            gtliTop = gtli;
        }
        var t = Math.max(gtliTop, gtli);
        var zeroCoefficients = header.runMode != 0;
        for (var i = 0; i < groups; i++) {
            var mTop = Math.max(above[aboveOffset + i], t);
            if (significance && flags[flagOffset + (i >>> 3)] != 0) {
                gcli[offset + i] = (byte) (zeroCoefficients ? gtli : mTop);
                continue;
            }
            var x = reader.unary();
            if (x < 0) {
                throw new MediaException.Malformed("a JPEG XS bit-plane count code is too long");
            }
            var threshold = Math.max(mTop - gtli, 0);
            int delta;
            if (x > 2 * threshold) {
                delta = x - threshold;
            } else if (x > 0) {
                delta = (x & 1) != 0 ? -((x + 1) >> 1) : x >> 1;
            } else {
                delta = 0;
            }
            gcli[offset + i] = (byte) checked(mTop + delta);
        }
    }

    private static int checked(int gcli) {
        if (gcli < 0 || gcli > MAX_BITPLANES) {
            throw new MediaException.Malformed("a JPEG XS code group has " + gcli + " bit-planes");
        }
        return gcli;
    }

    /**
     * Reads the data of one band line, writes the dequantized coefficients and returns the number of nonzero
     * coefficients past the band width in its last code group, whose signs the sign sub-packet still carries.
     */
    private int coefficients(State current, int c, int b, int line, int row, int column, boolean lastColumn) {
        var width = width(c, b, lastColumn);
        var groups = (width + 3) >>> 2;
        var gtli = current.gtli[c][b];
        var gcli = current.gcli[c][b];
        var gcliOffset = line * groups;
        var plane = planes[c];
        var stride = layout.componentWidth[c];
        var y = layout.bandY[c][b] + row * layout.bandLines[c][b] + line;
        var x = layout.bandX[c][b] + column * layout.precinctBandWidth[c][b];
        var base = y * stride + x;
        var signsWithData = header.signHandling == 0;
        var uniform = header.quantizer == 1;
        var padding = 0;
        for (var group = 0; group < groups; group++) {
            var count = group * 4 + 4 <= width ? 4 : width - group * 4;
            var target = base + group * 4;
            var planesCoded = gcli[gcliOffset + group] - gtli;
            if (planesCoded <= 0) {
                for (var i = 0; i < count; i++) {
                    plane[target + i] = 0;
                }
                continue;
            }
            var signs = signsWithData ? reader.read(4) : 0;
            var m0 = 0;
            var m1 = 0;
            var m2 = 0;
            var m3 = 0;
            for (var k = 0; k < planesCoded; k++) {
                var nibble = reader.read(4);
                m0 = m0 << 1 | nibble >>> 3;
                m1 = m1 << 1 | (nibble >>> 2) & 1;
                m2 = m2 << 1 | (nibble >>> 1) & 1;
                m3 = m3 << 1 | nibble & 1;
            }
            var groupGcli = gcli[gcliOffset + group];
            var v0 = dequantize(m0 << gtli, groupGcli, gtli, uniform);
            var v1 = dequantize(m1 << gtli, groupGcli, gtli, uniform);
            var v2 = dequantize(m2 << gtli, groupGcli, gtli, uniform);
            var v3 = dequantize(m3 << gtli, groupGcli, gtli, uniform);
            if (signsWithData) {
                if ((signs & 8) != 0) {
                    v0 = -v0;
                }
                if ((signs & 4) != 0) {
                    v1 = -v1;
                }
                if ((signs & 2) != 0) {
                    v2 = -v2;
                }
                if ((signs & 1) != 0) {
                    v3 = -v3;
                }
            }
            plane[target] = v0;
            if (count > 1) {
                plane[target + 1] = v1;
            } else if (m1 != 0) {
                padding++;
            }
            if (count > 2) {
                plane[target + 2] = v2;
            } else if (m2 != 0) {
                padding++;
            }
            if (count > 3) {
                plane[target + 3] = v3;
            } else if (m3 != 0) {
                padding++;
            }
        }
        return padding;
    }

    /**
     * Reconstructs a coefficient magnitude from its coded bit-planes ({@code inv_quant_deadzone},
     * {@code inv_quant_uniform}).
     */
    static int dequantize(int magnitude, int gcli, int gtli, boolean uniform) {
        if (gtli == 0 || magnitude == 0 || gcli <= gtli) {
            return magnitude;
        }
        if (!uniform) {
            return magnitude | 1 << (gtli - 1);
        }
        var scale = gcli - gtli + 1;
        var value = 0;
        for (var v = magnitude; v > 0; v >>>= scale) {
            value += v;
        }
        return value;
    }

    private void signs(int c, int b, int line, int row, int column, boolean lastColumn, int padding) {
        var width = width(c, b, lastColumn);
        var plane = planes[c];
        var stride = layout.componentWidth[c];
        var y = layout.bandY[c][b] + row * layout.bandLines[c][b] + line;
        var x = layout.bandX[c][b] + column * layout.precinctBandWidth[c][b];
        var base = y * stride + x;
        for (var i = 0; i < width; i++) {
            var v = plane[base + i];
            if (v != 0 && reader.bit() != 0) {
                plane[base + i] = -v;
            }
        }
        if (padding > 0 && (width & 3) != 0) {
            reader.skip(padding);
        }
    }
}
