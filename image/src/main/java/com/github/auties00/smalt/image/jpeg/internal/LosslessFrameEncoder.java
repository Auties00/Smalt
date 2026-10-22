/* Ports libjpeg-turbo (jclossls.c, jcdiffct.c, jclhuff.c). Copyright (C) 1999, Ken Murchison; Copyright (C)
   2022-2024, D. R. Commander; IJG License. Modified for Smalt. */
package com.github.auties00.smalt.image.jpeg.internal;

import com.github.auties00.smalt.internal.task.ParallelBands;

import java.util.Arrays;
import java.util.List;

/**
 * Writes a lossless JPEG frame (T.81 Annex H, {@code SOF3} or {@code SOF11}) from a picture.
 *
 * <p>The samples of each component, padded to whole MCUs by repeating the last column and row, are shifted
 * right by each scan's point transform and predicted: the first line of a scan and of each restart interval
 * from the left, its first sample from {@code 2^(P - Pt - 1)}, the first sample of every other line from the
 * sample above, and every other sample with the scan's predictor. The differences, modulo {@code 2^16}, are
 * Huffman coded with tables optimized for each scan, or arithmetic coded with the model of T.81 H.1.2.3 and
 * the default conditioning ({@code L = 0}, {@code U = 1}).
 *
 * @implNote This implementation follows libjpeg-turbo's lossless compressor, so a Huffman-coded frame equals
 * {@code cjpeg -lossless}'s for its color spaces (gray and RGB) and sampling factors (all 1), except that the
 * scan header names each component's own table where libjpeg-turbo writes 0. Arithmetic coding, which
 * libjpeg-turbo does not implement for lossless frames, follows the specification.
 */
final class LosslessFrameEncoder {
    /**
     * The approximate number of operations to prepare one sample.
     */
    private static final long SAMPLE_WORK = 16;

    /**
     * The frame.
     */
    private final FrameLayout layout;

    /**
     * The unsigned samples of each component, padded to whole MCUs of the interleaved scans.
     */
    private final char[][] samples;

    /**
     * The padded width of each component.
     */
    private final int[] paddedWidth;

    /**
     * The padded height of each component.
     */
    private final int[] paddedHeight;

    /**
     * The output.
     */
    private final JpegBitWriter out;

    /**
     * The restart interval in MCUs, or 0.
     */
    private final int restartInterval;

    /**
     * Whether the scans are arithmetic coded.
     */
    private final boolean arithmetic;

    /**
     * Prepares the samples of a frame.
     *
     * @param layout the frame, lossless
     * @param rows the full-resolution rows, or {@code null} when {@code planes} is given
     * @param planes the planes in the frame's subsampling, or {@code null}
     * @param restartInterval the restart interval in MCUs, or 0
     * @param arithmetic whether the scans are arithmetic coded
     */
    LosslessFrameEncoder(FrameLayout layout, SampleRows rows, ComponentBands.Planes planes, int restartInterval,
                         boolean arithmetic) {
        this.layout = layout;
        this.restartInterval = restartInterval;
        this.arithmetic = arithmetic;
        var count = layout.count;
        var mcusWide = layout.mcusWide();
        var mcusHigh = layout.imcuRows;
        this.paddedWidth = new int[count];
        this.paddedHeight = new int[count];
        this.samples = new char[count][];
        long total = 0;
        for (var c = 0; c < count; c++) {
            paddedWidth[c] = Math.max(layout.sampleWidth[c], mcusWide * layout.h[c]);
            paddedHeight[c] = Math.max(layout.sampleHeight[c], mcusHigh * layout.v[c]);
            samples[c] = new char[Math.multiplyExact(paddedWidth[c], paddedHeight[c])];
            total += (long) paddedWidth[c] * paddedHeight[c];
        }
        var minimumStride = new int[count];
        for (var c = 0; c < count; c++) {
            minimumStride[c] = layout.bandWidth(c);
        }
        var bandCount = ParallelBands.bands(layout.imcuRows, total * SAMPLE_WORK);
        ParallelBands.run(bandCount, band -> {
            var bands = new ComponentBands(layout, rows, planes, minimumStride);
            var first = ParallelBands.start(band, bandCount, layout.imcuRows);
            var last = ParallelBands.start(band + 1, bandCount, layout.imcuRows);
            for (var imcu = first; imcu < last; imcu++) {
                bands.fill(imcu);
                for (var c = 0; c < count; c++) {
                    var source = bands.bands[c];
                    var stride = bands.stride[c];
                    var width = layout.sampleWidth[c];
                    var target = samples[c];
                    var padded = paddedWidth[c];
                    for (var r = 0; r < layout.v[c]; r++) {
                        var y = imcu * layout.v[c] + r;
                        if (y >= paddedHeight[c]) {
                            break;
                        }
                        var row = y * padded;
                        for (var x = 0; x < width; x++) {
                            target[row + x] = (char) source[r * stride + x];
                        }
                        Arrays.fill(target, row + width, row + padded, target[row + width - 1]);
                    }
                }
            }
        });
        for (var c = 0; c < count; c++) {
            var padded = paddedWidth[c];
            var filled = layout.imcuRows * layout.v[c];
            for (var y = filled; y < paddedHeight[c]; y++) {
                System.arraycopy(samples[c], (filled - 1) * padded, samples[c], y * padded, padded);
            }
        }
        this.out = new JpegBitWriter((int) Math.min(Integer.MAX_VALUE - 16, total * layout.precision / 8 + 1024));
    }

    /**
     * Writes the file.
     *
     * @param header the file header
     * @param markers the marker segments written after the file header
     * @param scans the scans: components, predictor as {@code ss} and point transform as {@code al}
     * @return the file
     * @throws IllegalArgumentException if the restart interval is not a whole number of MCU rows of a scan
     */
    byte[] write(CoefficientWriter.Header header, List<MarkerSegment> markers, List<CoefficientWriter.Scan> scans) {
        for (var scan : scans) {
            var perRow = scan.components().length == 1 ? layout.sampleWidth[scan.components()[0]] : layout.mcusWide();
            if (restartInterval % perRow != 0) {
                throw new IllegalArgumentException("a lossless JPEG restart interval must be a whole number of MCU "
                        + "rows (" + perRow + " MCUs per row), not " + restartInterval);
            }
        }
        out.writeMarker(0xD8);
        CoefficientWriter.writeFileHeader(out, header, markers);
        out.writeMarker(arithmetic ? 0xCB : 0xC3);
        out.writeShort(8 + 3 * layout.count);
        out.writeByte(layout.precision);
        out.writeShort(layout.height);
        out.writeShort(layout.width);
        out.writeByte(layout.count);
        for (var c = 0; c < layout.count; c++) {
            out.writeByte(layout.ids[c]);
            out.writeByte((layout.h[c] << 4) | layout.v[c]);
            out.writeByte(layout.quantSlots[c]);
        }
        var lastInterval = 0;
        for (var scan : scans) {
            HuffmanEncodingTable[] tables = null;
            if (arithmetic) {
                writeConditioning(scan);
            } else {
                var counts = new long[4][257];
                code(scan, counts, null);
                tables = new HuffmanEncodingTable[4];
                for (var c : scan.components()) {
                    var slot = layout.tableSlots[c];
                    if (tables[slot] == null) {
                        tables[slot] = new HuffmanEncodingTable(HuffmanEncodingTable.optimal(counts[slot]));
                        out.writeDht(slot, tables[slot].spec);
                    }
                }
            }
            if (restartInterval != lastInterval) {
                out.writeDri(restartInterval);
                lastInterval = restartInterval;
            }
            writeScanHeader(scan);
            code(scan, null, tables);
        }
        out.writeMarker(0xD9);
        return out.toByteArray();
    }

    /**
     * Writes the DAC segment of a scan's tables, with the default conditioning {@code L = 0} and {@code U = 1}.
     *
     * @param scan the scan to describe
     */
    private void writeConditioning(CoefficientWriter.Scan scan) {
        var used = new boolean[16];
        var entries = 0;
        for (var c : scan.components()) {
            if (!used[layout.tableSlots[c]]) {
                used[layout.tableSlots[c]] = true;
                entries++;
            }
        }
        out.writeMarker(0xCC);
        out.writeShort(2 + 2 * entries);
        for (var slot = 0; slot < 16; slot++) {
            if (used[slot]) {
                out.writeByte(slot);
                out.writeByte(1 << 4);
            }
        }
    }

    /**
     * Writes the SOS segment of a scan.
     *
     * @param scan the scan to describe
     */
    private void writeScanHeader(CoefficientWriter.Scan scan) {
        out.writeMarker(0xDA);
        out.writeShort(6 + 2 * scan.components().length);
        out.writeByte(scan.components().length);
        for (var c : scan.components()) {
            out.writeByte(layout.ids[c]);
            out.writeByte(layout.tableSlots[c] << 4);
        }
        out.writeByte(scan.ss());
        out.writeByte(0);
        out.writeByte(scan.al());
    }

    /**
     * Counts the Huffman symbols of a scan, or codes it with Huffman tables or arithmetic coding.
     *
     * @param scan the scan to count or code
     * @param counts the symbol counts of each table slot, filled when counting, or {@code null}
     * @param tables the Huffman tables by slot when coding with them, or {@code null} when counting or arithmetic
     * coding
     */
    private void code(CoefficientWriter.Scan scan, long[][] counts, HuffmanEncodingTable[] tables) {
        var members = scan.components();
        var count = members.length;
        var interleaved = count > 1;
        var unitsWide = interleaved ? layout.mcusWide() : layout.sampleWidth[members[0]];
        var unitsHigh = interleaved ? layout.imcuRows : layout.sampleHeight[members[0]];
        var predictor = scan.ss();
        var transform = scan.al();
        var initial = 1 << (layout.precision - transform - 1);
        ArithmeticEncoder coder = null;
        byte[][] stats = null;
        byte[][][] classes = null;
        if (arithmetic) {
            coder = new ArithmeticEncoder(out);
            stats = new byte[16][];
            classes = new byte[count][][];
            for (var k = 0; k < count; k++) {
                var c = members[k];
                stats[layout.tableSlots[c]] = new byte[ArithmeticEncoder.LOSSLESS_BINS];
                var v = interleaved ? layout.v[c] : 1;
                var h = interleaved ? layout.h[c] : 1;
                classes[k] = new byte[v + 1][unitsWide * h];
            }
        }
        var restartsToGo = restartInterval;
        var nextRestart = 0;
        var restartRow = 0;
        for (var unitRow = 0; unitRow < unitsHigh; unitRow++) {
            for (var unit = 0; unit < unitsWide; unit++) {
                if (restartInterval != 0) {
                    if (restartsToGo == 0) {
                        if (counts == null) {
                            if (arithmetic) {
                                coder.finish();
                            } else {
                                out.flushBits();
                            }
                            out.writeMarker(0xD0 + nextRestart);
                            if (arithmetic) {
                                coder.reset();
                                for (var k = 0; k < count; k++) {
                                    ArithmeticEncoder.clear(stats[layout.tableSlots[members[k]]]);
                                }
                            }
                        }
                        restartsToGo = restartInterval;
                        nextRestart = (nextRestart + 1) & 7;
                        restartRow = unitRow;
                    }
                    restartsToGo--;
                }
                for (var k = 0; k < count; k++) {
                    var c = members[k];
                    var h = interleaved ? layout.h[c] : 1;
                    var v = interleaved ? layout.v[c] : 1;
                    var plane = samples[c];
                    var stride = paddedWidth[c];
                    var slot = layout.tableSlots[c];
                    for (var by = 0; by < v; by++) {
                        var y = unitRow * v + by;
                        var firstLine = unitRow == restartRow && by == 0;
                        for (var bx = 0; bx < h; bx++) {
                            var x = unit * h + bx;
                            var sample = plane[y * stride + x] >> transform;
                            int prediction;
                            if (firstLine) {
                                prediction = x == 0 ? initial : plane[y * stride + x - 1] >> transform;
                            } else if (x == 0) {
                                prediction = plane[(y - 1) * stride] >> transform;
                            } else {
                                var a = plane[y * stride + x - 1] >> transform;
                                var b = plane[(y - 1) * stride + x] >> transform;
                                var cc = plane[(y - 1) * stride + x - 1] >> transform;
                                prediction = switch (predictor) {
                                    case 1 -> a;
                                    case 2 -> b;
                                    case 3 -> cc;
                                    case 4 -> a + b - cc;
                                    case 5 -> a + ((b - cc) >> 1);
                                    case 6 -> b + ((a - cc) >> 1);
                                    default -> (a + b) >> 1;
                                };
                            }
                            var difference = sample - prediction;
                            if (arithmetic) {
                                var normalized = ((difference + 32768) & 0xFFFF) - 32768;
                                var rowClasses = classes[k];
                                var left = x == 0 ? 0 : rowClasses[by + 1][x - 1];
                                var up = firstLine ? 0 : rowClasses[by][x];
                                var table = stats[slot];
                                var s0 = 20 * left + 4 * up;
                                if (normalized == 0) {
                                    coder.encode(table, s0, 0);
                                } else {
                                    coder.encode(table, s0, 1);
                                    coder.encodeDcMagnitude(table, s0, up >= 3 ? 129 : 100, normalized);
                                }
                                rowClasses[by + 1][x] = classify(normalized);
                            } else {
                                huffman(difference, slot, counts, tables);
                            }
                        }
                    }
                }
            }
            if (arithmetic) {
                for (var k = 0; k < count; k++) {
                    var rowClasses = classes[k];
                    var v = rowClasses.length - 1;
                    var last = rowClasses[v];
                    rowClasses[v] = rowClasses[0];
                    rowClasses[0] = last;
                }
            }
        }
        if (counts == null) {
            if (arithmetic) {
                coder.finish();
            } else {
                out.flushBits();
            }
        }
    }

    /**
     * Counts or codes one difference with a Huffman table (T.81 H.1.2.2).
     *
     * @param difference the difference, taken modulo {@code 2^16}
     * @param slot the table slot
     * @param counts the symbol counts when counting, or {@code null}
     * @param tables the tables when coding
     */
    private void huffman(int difference, int slot, long[][] counts, HuffmanEncodingTable[] tables) {
        int magnitude;
        int bits;
        if ((difference & 0x8000) != 0) {
            magnitude = (-difference) & 0x7FFF;
            if (magnitude == 0) {
                magnitude = 0x8000;
            }
            bits = ~magnitude;
        } else {
            magnitude = difference & 0x7FFF;
            bits = magnitude;
        }
        var category = 32 - Integer.numberOfLeadingZeros(magnitude);
        if (counts != null) {
            counts[slot][category]++;
            return;
        }
        var table = tables[slot];
        out.put(table.codes[category], table.lengths[category]);
        if (category != 0 && category != 16) {
            out.put(bits & ((1 << category) - 1), category);
        }
    }

    /**
     * Classifies a difference for the conditioning of T.81 H.1.2.3 with {@code L = 0} and {@code U = 1}.
     *
     * @param difference the difference, from -32768 to 32767
     * @return 0 for zero, 1 for small positive, 2 for small negative, 3 for large positive and 4 for large
     * negative
     */
    private static byte classify(int difference) {
        var magnitude = Math.abs(difference);
        if (magnitude == 0) {
            return 0;
        }
        if (magnitude <= 2) {
            return (byte) (difference > 0 ? 1 : 2);
        }
        return (byte) (difference > 0 ? 3 : 4);
    }
}
