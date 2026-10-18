package com.github.auties00.smalt.image.jpeg.internal;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Writes JPEG streams from quantized coefficients, for tests: sequential streams with any scan
 * layout and progressive streams with any scan script (a port of IJG libjpeg 6b's jcphuff.c,
 * including end-of-band runs and buffered refinement bits), always with optimal Huffman tables
 * per scan as cjpeg -optimize and -progressive produce them. Options add or remove the markers
 * tests need: JFIF or Adobe segments, 16-bit quantization tables, restart intervals, DNL, missing
 * DHT segments, arbitrary component identifiers and SOF codes.
 */
final class TestJpegWriter {
    /**
     * One component: identifier, sampling factors, quantization table, and its coefficients in the
     * decoder's padded block layout.
     */
    record Component(int id, int h, int v, int tq, short[] coefficients, int paddedWide, int paddedHigh,
                     int blocksWide, int blocksHigh) {
    }

    /**
     * One scan of a script: the components (indices into the component array), the spectral
     * selection and the successive approximation positions.
     */
    record Scan(int[] components, int ss, int se, int ah, int al) {
        static Scan of(int ss, int se, int ah, int al, int... components) {
            return new Scan(components, ss, se, ah, al);
        }
    }

    final int width;
    final int height;
    final Component[] components;
    final int[][] quantTables;
    boolean jfif = true;
    int adobeTransform = -1;
    int restartInterval;
    boolean sixteenBitQuant;
    boolean writeDht = true;
    boolean dnl;
    int sofMarker = -1;
    int precision = 8;

    TestJpegWriter(int width, int height, Component[] components, int[][] quantTables) {
        this.width = width;
        this.height = height;
        this.components = components;
        this.quantTables = quantTables;
    }

    /**
     * Wraps decoded coefficients, assigning table 0 to the first component's quantizers and table
     * 1 to the others (which must share one table).
     */
    static TestJpegWriter of(JpegCoefficients coefficients) {
        var frame = coefficients.frame();
        var components = new Component[frame.components.length];
        var tables = new ArrayList<int[]>();
        for (var i = 0; i < components.length; i++) {
            var c = frame.components[i];
            var quant = coefficients.quantization(i);
            var tq = -1;
            for (var t = 0; t < tables.size(); t++) {
                if (Arrays.equals(tables.get(t), quant)) {
                    tq = t;
                }
            }
            if (tq < 0) {
                tq = tables.size();
                tables.add(quant);
            }
            components[i] = new Component(c.id, c.h, c.v, tq, coefficients.coefficients(i).clone(), c.paddedBlocksWide,
                    c.paddedBlocksHigh, c.blocksWide, c.blocksHigh);
        }
        return new TestJpegWriter(frame.width, frame.height, components, tables.toArray(int[][]::new));
    }

    /**
     * Builds a writer from sample planes at their component resolution, with the forward DCT and
     * the libjpeg quality-scaled tables (table 0 for component 0, table 1 for the others).
     */
    static TestJpegWriter fromPlanes(int width, int height, int[] ids, int[] h, int[] v, byte[][] planes, int quality) {
        var hMax = Arrays.stream(h).max().orElseThrow();
        var vMax = Arrays.stream(v).max().orElseThrow();
        var mcusWide = JpegFrame.ceilDiv(width, hMax * 8);
        var mcusHigh = JpegFrame.ceilDiv(height, vMax * 8);
        var luma = JpegWriter.scaledTable(JpegTables.STD_LUMINANCE_QUANT, quality);
        var chroma = JpegWriter.scaledTable(JpegTables.STD_CHROMINANCE_QUANT, quality);
        var components = new Component[ids.length];
        var output = new int[64];
        for (var c = 0; c < ids.length; c++) {
            var cw = JpegFrame.ceilDiv((long) width * h[c], hMax);
            var ch = JpegFrame.ceilDiv((long) height * v[c], vMax);
            var blocksWide = JpegFrame.ceilDiv(cw, 8);
            var blocksHigh = JpegFrame.ceilDiv(ch, 8);
            var paddedWide = ids.length == 1 ? blocksWide : mcusWide * h[c];
            var paddedHigh = ids.length == 1 ? blocksHigh : mcusHigh * v[c];
            var stride = paddedWide * 8;
            var padded = new byte[stride * paddedHigh * 8];
            for (var y = 0; y < paddedHigh * 8; y++) {
                for (var x = 0; x < stride; x++) {
                    padded[y * stride + x] = planes[c][Math.min(y, ch - 1) * cw + Math.min(x, cw - 1)];
                }
            }
            var divisors = new QuantDivisors(c == 0 ? luma : chroma);
            var coefficients = new short[paddedWide * paddedHigh * 64];
            for (var by = 0; by < paddedHigh; by++) {
                for (var bx = 0; bx < paddedWide; bx++) {
                    new ScalarFdctKernel().transform(padded, by * 8 * stride + bx * 8, stride, divisors, output);
                    for (var k = 0; k < 64; k++) {
                        coefficients[(by * paddedWide + bx) * 64 + k] = (short) output[k];
                    }
                }
            }
            components[c] = new Component(ids[c], h[c], v[c], c == 0 ? 0 : 1, coefficients, paddedWide, paddedHigh,
                    blocksWide, blocksHigh);
        }
        return new TestJpegWriter(width, height, components, ids.length == 1 ? new int[][]{luma} : new int[][]{luma, chroma});
    }

    /**
     * Returns libjpeg's default progression script (jcparam.c jpeg_simple_progression).
     */
    static List<Scan> simpleProgression(int count, boolean ycbcr) {
        var scans = new ArrayList<Scan>();
        var all = new int[count];
        for (var i = 0; i < count; i++) {
            all[i] = i;
        }
        if (count == 3 && ycbcr) {
            scans.add(new Scan(all, 0, 0, 0, 1));
            scans.add(Scan.of(1, 5, 0, 2, 0));
            scans.add(Scan.of(1, 63, 0, 1, 2));
            scans.add(Scan.of(1, 63, 0, 1, 1));
            scans.add(Scan.of(6, 63, 0, 2, 0));
            scans.add(Scan.of(1, 63, 2, 1, 0));
            scans.add(new Scan(all, 0, 0, 1, 0));
            scans.add(Scan.of(1, 63, 1, 0, 2));
            scans.add(Scan.of(1, 63, 1, 0, 1));
            scans.add(Scan.of(1, 63, 1, 0, 0));
        } else {
            scans.add(new Scan(all, 0, 0, 0, 1));
            for (var c = 0; c < count; c++) {
                scans.add(Scan.of(1, 5, 0, 2, c));
            }
            for (var c = 0; c < count; c++) {
                scans.add(Scan.of(6, 63, 0, 2, c));
            }
            for (var c = 0; c < count; c++) {
                scans.add(Scan.of(1, 63, 2, 1, c));
            }
            scans.add(new Scan(all, 0, 0, 1, 0));
            for (var c = 0; c < count; c++) {
                scans.add(Scan.of(1, 63, 1, 0, c));
            }
        }
        return scans;
    }

    /**
     * Writes a sequential stream whose scans cover the given components, in order.
     */
    byte[] sequential(int[]... scans) {
        var list = new ArrayList<Scan>();
        for (var scan : scans) {
            list.add(new Scan(scan, 0, 63, 0, 0));
        }
        return write(list, false);
    }

    /**
     * Writes a progressive stream with the given script.
     */
    byte[] progressive(List<Scan> script) {
        return write(script, true);
    }

    private byte[] write(List<Scan> scans, boolean progressive) {
        var out = new JpegBitWriter(4096);
        out.writeMarker(0xD8);
        if (jfif) {
            out.writeMarker(0xE0);
            out.writeShort(16);
            for (var b : new byte[]{'J', 'F', 'I', 'F', 0, 1, 1, 0, 0, 1, 0, 1, 0, 0}) {
                out.writeByte(b);
            }
        }
        if (adobeTransform >= 0) {
            out.writeMarker(0xEE);
            out.writeShort(14);
            for (var b : new byte[]{'A', 'd', 'o', 'b', 'e', 0, 100, 0, 0, 0, 0}) {
                out.writeByte(b);
            }
            out.writeByte(adobeTransform);
        }
        var sent = new boolean[quantTables.length];
        for (var component : components) {
            if (sent[component.tq]) {
                continue;
            }
            sent[component.tq] = true;
            out.writeMarker(0xDB);
            out.writeShort(sixteenBitQuant ? 131 : 67);
            out.writeByte((sixteenBitQuant ? 0x10 : 0) | component.tq);
            for (var k = 0; k < 64; k++) {
                var value = quantTables[component.tq][JpegTables.ZIGZAG[k]];
                if (sixteenBitQuant) {
                    out.writeShort(value);
                } else {
                    out.writeByte(value);
                }
            }
        }
        out.writeMarker(sofMarker >= 0 ? sofMarker : progressive ? 0xC2 : 0xC0);
        out.writeShort(8 + 3 * components.length);
        out.writeByte(precision);
        out.writeShort(dnl ? 0 : height);
        out.writeShort(width);
        out.writeByte(components.length);
        for (var component : components) {
            out.writeByte(component.id);
            out.writeByte((component.h << 4) | component.v);
            out.writeByte(component.tq);
        }
        var first = true;
        for (var scan : scans) {
            writeScan(out, scan, progressive, first);
            if (first && dnl) {
                out.writeMarker(0xDC);
                out.writeShort(4);
                out.writeShort(height);
            }
            first = false;
        }
        out.writeMarker(0xD9);
        return out.toByteArray();
    }

    private int table(int component) {
        return component == 0 ? 0 : 1;
    }

    private void writeScan(JpegBitWriter out, Scan scan, boolean progressive, boolean first) {
        var dcCounts = new long[2][256];
        var acCounts = new long[2][256];
        var gather = new ScanCoder(this, scan, progressive, null, null, null, dcCounts, acCounts);
        gather.run();
        var dc = new HuffmanEncodingTable[2];
        var ac = new HuffmanEncodingTable[2];
        var usesDc = !progressive || (scan.ss == 0 && scan.ah == 0);
        var usesAc = scan.se != 0;
        var written = new boolean[4];
        for (var c : scan.components) {
            var t = table(c);
            if (usesDc && !written[t]) {
                dc[t] = new HuffmanEncodingTable(HuffmanEncodingTable.optimal(dcCounts[t]));
                if (writeDht) {
                    writeTable(out, t, dc[t].spec);
                }
                written[t] = true;
            }
            if (usesAc && !written[2 + t]) {
                ac[t] = new HuffmanEncodingTable(HuffmanEncodingTable.optimal(acCounts[t]));
                if (writeDht) {
                    writeTable(out, 0x10 | t, ac[t].spec);
                }
                written[2 + t] = true;
            }
        }
        if (first && restartInterval != 0) {
            out.writeMarker(0xDD);
            out.writeShort(4);
            out.writeShort(restartInterval);
        }
        out.writeMarker(0xDA);
        out.writeShort(6 + 2 * scan.components.length);
        out.writeByte(scan.components.length);
        for (var c : scan.components) {
            out.writeByte(components[c].id);
            var dcSelector = !progressive || (scan.ss == 0 && scan.ah == 0) ? table(c) : 0;
            var acSelector = scan.se != 0 ? table(c) : 0;
            out.writeByte((dcSelector << 4) | acSelector);
        }
        out.writeByte(scan.ss);
        out.writeByte(scan.se);
        out.writeByte((scan.ah << 4) | scan.al);
        new ScanCoder(this, scan, progressive, out, dc, ac, null, null).run();
        out.flushBits();
    }

    private static void writeTable(JpegBitWriter out, int index, HuffmanSpec spec) {
        out.writeMarker(0xC4);
        out.writeShort(19 + spec.size());
        out.writeByte(index);
        for (var l = 1; l <= 16; l++) {
            out.writeByte(spec.count(l));
        }
        for (var i = 0; i < spec.size(); i++) {
            out.writeByte(spec.value(i));
        }
    }

    /**
     * Codes one scan, either counting symbols (statistics arrays set) or emitting bits.
     */
    private static final class ScanCoder {
        private static final int MAX_CORR_BITS = 1000;
        private final TestJpegWriter writer;
        private final Scan scan;
        private final boolean progressive;
        private final JpegBitWriter out;
        private final HuffmanEncodingTable[] dc;
        private final HuffmanEncodingTable[] ac;
        private final long[][] dcCounts;
        private final long[][] acCounts;
        private final int[] lastDc = new int[4];
        private final byte[] correction = new byte[MAX_CORR_BITS];
        private int eobRun;
        private int buffered;
        private int acTable;

        ScanCoder(TestJpegWriter writer, Scan scan, boolean progressive, JpegBitWriter out, HuffmanEncodingTable[] dc,
                  HuffmanEncodingTable[] ac, long[][] dcCounts, long[][] acCounts) {
            this.writer = writer;
            this.scan = scan;
            this.progressive = progressive;
            this.out = out;
            this.dc = dc;
            this.ac = ac;
            this.dcCounts = dcCounts;
            this.acCounts = acCounts;
        }

        private boolean gathering() {
            return out == null;
        }

        private void symbol(boolean isDc, int table, int symbol) {
            if (gathering()) {
                (isDc ? dcCounts : acCounts)[table][symbol]++;
            } else {
                var t = (isDc ? dc : ac)[table];
                out.put(t.codes[symbol], t.lengths[symbol]);
            }
        }

        private void bits(int value, int count) {
            if (!gathering() && count > 0) {
                out.put(value & ((1 << count) - 1), count);
            }
        }

        void run() {
            var comps = scan.components;
            var single = comps.length == 1;
            var first = writer.components[comps[0]];
            int mcusWide;
            int mcusHigh;
            if (single) {
                mcusWide = first.blocksWide;
                mcusHigh = first.blocksHigh;
            } else {
                var hMax = 1;
                var vMax = 1;
                for (var c : writer.components) {
                    hMax = Math.max(hMax, c.h);
                    vMax = Math.max(vMax, c.v);
                }
                mcusWide = JpegFrame.ceilDiv(writer.width, hMax * 8);
                mcusHigh = JpegFrame.ceilDiv(writer.height, vMax * 8);
            }
            acTable = writer.table(comps[0]);
            var mcu = 0;
            var restartNumber = 0;
            for (var my = 0; my < mcusHigh; my++) {
                for (var mx = 0; mx < mcusWide; mx++) {
                    if (writer.restartInterval != 0 && mcu != 0 && mcu % writer.restartInterval == 0) {
                        emitEobRun();
                        if (!gathering()) {
                            out.flushBits();
                            out.writeMarker(0xD0 + restartNumber);
                        }
                        restartNumber = (restartNumber + 1) & 7;
                        Arrays.fill(lastDc, 0);
                        eobRun = 0;
                        buffered = 0;
                    }
                    for (var ci = 0; ci < comps.length; ci++) {
                        var component = writer.components[comps[ci]];
                        var rows = single ? 1 : component.v;
                        var columns = single ? 1 : component.h;
                        for (var by = 0; by < rows; by++) {
                            for (var bx = 0; bx < columns; bx++) {
                                var column = single ? mx : mx * component.h + bx;
                                var row = single ? my : my * component.v + by;
                                var base = (row * component.paddedWide + column) * 64;
                                block(component, comps[ci], ci, base);
                            }
                        }
                    }
                    mcu++;
                }
            }
            emitEobRun();
        }

        private void block(Component component, int index, int ci, int base) {
            var coefficients = component.coefficients;
            var table = writer.table(index);
            if (!progressive) {
                sequentialBlock(coefficients, base, table, ci);
                return;
            }
            if (scan.ss == 0) {
                if (scan.ah == 0) {
                    var value = coefficients[base] >> scan.al;
                    var diff = value - lastDc[ci];
                    lastDc[ci] = value;
                    var size = 32 - Integer.numberOfLeadingZeros(Math.abs(diff));
                    symbol(true, table, size);
                    bits(diff < 0 ? diff - 1 : diff, size);
                } else {
                    bits(coefficients[base] >> scan.al, 1);
                }
                return;
            }
            if (scan.ah == 0) {
                acFirst(coefficients, base);
            } else {
                acRefine(coefficients, base);
            }
        }

        private void sequentialBlock(short[] coefficients, int base, int table, int ci) {
            var value = coefficients[base];
            var diff = value - lastDc[ci];
            lastDc[ci] = value;
            var size = 32 - Integer.numberOfLeadingZeros(Math.abs(diff));
            symbol(true, table, size);
            bits(diff < 0 ? diff - 1 : diff, size);
            var run = 0;
            for (var k = 1; k < 64; k++) {
                var coefficient = coefficients[base + JpegTables.ZIGZAG[k]];
                if (coefficient == 0) {
                    run++;
                    continue;
                }
                while (run > 15) {
                    symbol(false, table, 0xF0);
                    run -= 16;
                }
                var magnitude = 32 - Integer.numberOfLeadingZeros(Math.abs(coefficient));
                symbol(false, table, (run << 4) | magnitude);
                bits(coefficient < 0 ? coefficient - 1 : coefficient, magnitude);
                run = 0;
            }
            if (run > 0) {
                symbol(false, table, 0);
            }
        }

        private void emitEobRun() {
            if (eobRun > 0) {
                var size = 31 - Integer.numberOfLeadingZeros(eobRun);
                symbol(false, acTable, size << 4);
                bits(eobRun, size);
                eobRun = 0;
                for (var i = 0; i < buffered; i++) {
                    bits(correction[i], 1);
                }
                buffered = 0;
            }
        }

        private void acFirst(short[] coefficients, int base) {
            var run = 0;
            for (var k = scan.ss; k <= scan.se; k++) {
                int value = coefficients[base + JpegTables.ZIGZAG[k]];
                if (value == 0) {
                    run++;
                    continue;
                }
                int magnitude;
                int bitsValue;
                if (value < 0) {
                    magnitude = (-value) >> scan.al;
                    bitsValue = ~magnitude;
                } else {
                    magnitude = value >> scan.al;
                    bitsValue = magnitude;
                }
                if (magnitude == 0) {
                    run++;
                    continue;
                }
                emitEobRun();
                while (run > 15) {
                    symbol(false, acTable, 0xF0);
                    run -= 16;
                }
                var size = 32 - Integer.numberOfLeadingZeros(magnitude);
                symbol(false, acTable, (run << 4) + size);
                bits(bitsValue, size);
                run = 0;
            }
            if (run > 0) {
                eobRun++;
                if (eobRun == 0x7FFF) {
                    emitEobRun();
                }
            }
        }

        private void acRefine(short[] coefficients, int base) {
            var absolute = new int[64];
            var eob = 0;
            for (var k = scan.ss; k <= scan.se; k++) {
                int value = coefficients[base + JpegTables.ZIGZAG[k]];
                var magnitude = Math.abs(value) >> scan.al;
                absolute[k] = magnitude;
                if (magnitude == 1) {
                    eob = k;
                }
            }
            var run = 0;
            var pending = new byte[64];
            var pendingCount = 0;
            for (var k = scan.ss; k <= scan.se; k++) {
                var magnitude = absolute[k];
                if (magnitude == 0) {
                    run++;
                    continue;
                }
                while (run > 15 && k <= eob) {
                    emitEobRun();
                    symbol(false, acTable, 0xF0);
                    run -= 16;
                    for (var i = 0; i < pendingCount; i++) {
                        bits(pending[i], 1);
                    }
                    pendingCount = 0;
                }
                if (magnitude > 1) {
                    pending[pendingCount++] = (byte) (magnitude & 1);
                    continue;
                }
                emitEobRun();
                symbol(false, acTable, (run << 4) + 1);
                bits(coefficients[base + JpegTables.ZIGZAG[k]] < 0 ? 0 : 1, 1);
                for (var i = 0; i < pendingCount; i++) {
                    bits(pending[i], 1);
                }
                pendingCount = 0;
                run = 0;
            }
            if (run > 0 || pendingCount > 0) {
                eobRun++;
                System.arraycopy(pending, 0, correction, buffered, pendingCount);
                buffered += pendingCount;
                if (eobRun == 0x7FFF || buffered > MAX_CORR_BITS - 64 + 1) {
                    emitEobRun();
                }
            }
        }
    }

    /**
     * Returns a copy of a stream with every segment of the given marker removed.
     */
    static byte[] removeSegments(byte[] jpeg, int marker) {
        var out = new ByteArrayOutputStream();
        out.write(jpeg, 0, 2);
        var at = 2;
        while (at + 4 <= jpeg.length) {
            var code = jpeg[at + 1] & 0xFF;
            if (code == 0xDA) {
                out.write(jpeg, at, jpeg.length - at);
                return out.toByteArray();
            }
            var length = ((jpeg[at + 2] & 0xFF) << 8) | (jpeg[at + 3] & 0xFF);
            if (code != marker) {
                out.write(jpeg, at, 2 + length);
            }
            at += 2 + length;
        }
        out.write(jpeg, at, jpeg.length - at);
        return out.toByteArray();
    }

    /**
     * Returns a copy of a stream with a segment inserted after the segment that ends at SOI plus
     * the given number of segments.
     */
    static byte[] insertSegment(byte[] jpeg, int afterSegments, int marker, byte[] payload) {
        var at = 2;
        for (var i = 0; i < afterSegments; i++) {
            var length = ((jpeg[at + 2] & 0xFF) << 8) | (jpeg[at + 3] & 0xFF);
            at += 2 + length;
        }
        var out = new ByteArrayOutputStream();
        out.write(jpeg, 0, at);
        out.write(0xFF);
        out.write(marker);
        out.write((payload.length + 2) >>> 8);
        out.write(payload.length + 2);
        out.writeBytes(payload);
        out.write(jpeg, at, jpeg.length - at);
        return out.toByteArray();
    }
}
