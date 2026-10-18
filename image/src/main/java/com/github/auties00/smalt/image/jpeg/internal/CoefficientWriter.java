package com.github.auties00.smalt.image.jpeg.internal;

import com.github.auties00.smalt.MediaException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Writes a JPEG file from quantized DCT coefficients: the file header and copied marker segments,
 * the frame header, and one sequential scan or a progressive scan script, Huffman coded with the
 * tables of T.81 Annex K or with tables optimized for the coefficients.
 *
 * @implNote This implementation is a port of the parts of libjpeg-turbo that
 * {@code jpeg_write_coefficients} drives: the marker writer (jcmarker.c), the coefficient
 * controller of jctrans.c (including its dummy blocks at the right and bottom edges of
 * interleaved scans), the sequential Huffman encoder and statistics gatherer (jchuff.c), the
 * progressive Huffman encoder (jcphuff.c), the pass sequence of jcmaster.c (an optimization pass
 * before every scan's output pass, skipped for DC refinement scans) and the scan script of
 * {@code jpeg_simple_progression} (jcparam.c), so its output is byte-identical to
 * {@code jpegtran}'s for the same parameters.
 */
final class CoefficientWriter {
    /**
     * The size of the buffer of correction bits held back by an AC refinement scan.
     */
    private static final int MAX_CORRECTION_BITS = 1000;

    /**
     * The largest magnitude category of an AC coefficient at 8-bit precision.
     */
    private static final int MAX_COEFFICIENT_BITS = 10;

    /**
     * The largest number of blocks in one MCU.
     */
    private static final int MAX_BLOCKS_IN_MCU = 10;

    /**
     * Describes the file header of the output.
     *
     * @param jfif whether a JFIF {@code APP0} segment is written
     * @param jfifMajor the JFIF major version
     * @param jfifMinor the JFIF minor version
     * @param densityUnit the JFIF density unit
     * @param densityX the JFIF horizontal density
     * @param densityY the JFIF vertical density
     * @param adobe whether an Adobe {@code APP14} segment is written
     * @param adobeTransform the Adobe color transform code
     */
    record Header(boolean jfif, int jfifMajor, int jfifMinor, int densityUnit, int densityX, int densityY,
                  boolean adobe, int adobeTransform) {
    }

    /**
     * Describes one output component.
     *
     * @param id the component identifier
     * @param samplingX the horizontal sampling factor
     * @param samplingY the vertical sampling factor
     * @param quantSlot the quantization table slot
     * @param dcTable the DC Huffman table slot
     * @param acTable the AC Huffman table slot
     * @param plane the coefficients
     * @param blocksWide the number of block columns the component covers
     * @param blocksHigh the number of block rows the component covers
     */
    record Component(int id, int samplingX, int samplingY, int quantSlot, int dcTable, int acTable,
                     CoefficientPlane plane, int blocksWide, int blocksHigh) {
    }

    /**
     * Describes one scan.
     *
     * @param components the indexes of the components in the scan
     * @param ss the first coefficient of the spectral band
     * @param se the last coefficient of the spectral band
     * @param ah the previous successive-approximation bit position
     * @param al the successive-approximation bit position
     */
    private record Scan(int[] components, int ss, int se, int ah, int al) {
    }

    /**
     * The image width.
     */
    private final int width;

    /**
     * The image height.
     */
    private final int height;

    /**
     * The components.
     */
    private final Component[] components;

    /**
     * The quantization table of each slot, natural order, {@code null} when unused.
     */
    private final int[][] quantTables;

    /**
     * The largest horizontal sampling factor.
     */
    private final int maxSamplingX;

    /**
     * The largest vertical sampling factor.
     */
    private final int maxSamplingY;

    /**
     * The output.
     */
    private final JpegBitWriter out;

    /**
     * Whether each quantization table slot has been written.
     */
    private final boolean[] quantSent = new boolean[4];

    /**
     * The DC Huffman table of each slot.
     */
    private final HuffmanSpec[] dcSpecs = new HuffmanSpec[4];

    /**
     * The AC Huffman table of each slot.
     */
    private final HuffmanSpec[] acSpecs = new HuffmanSpec[4];

    /**
     * Whether each DC table slot has been written.
     */
    private final boolean[] dcSent = new boolean[4];

    /**
     * Whether each AC table slot has been written.
     */
    private final boolean[] acSent = new boolean[4];

    /**
     * The restart interval last written in a DRI segment.
     */
    private int lastRestartInterval;

    /**
     * Whether the current pass only gathers symbol statistics.
     */
    private boolean gather;

    /**
     * The DC symbol counts of each table slot during a gathering pass.
     */
    private final long[][] dcCounts = new long[4][257];

    /**
     * The AC symbol counts of each table slot during a gathering pass.
     */
    private final long[][] acCounts = new long[4][257];

    /**
     * The DC encoding tables of the current output pass.
     */
    private final HuffmanEncodingTable[] dcTables = new HuffmanEncodingTable[4];

    /**
     * The AC encoding tables of the current output pass.
     */
    private final HuffmanEncodingTable[] acTables = new HuffmanEncodingTable[4];

    /**
     * The DC prediction of each component of the scan.
     */
    private final int[] lastDc = new int[4];

    /**
     * The number of pending end-of-band runs of a progressive AC scan.
     */
    private int eobRun;

    /**
     * The number of correction bits held back with the pending end-of-band run.
     */
    private int bufferedBits;

    /**
     * The correction bits of an AC refinement scan.
     */
    private final byte[] correctionBits = new byte[MAX_CORRECTION_BITS];

    /**
     * The restart interval in MCUs, or {@code 0}.
     */
    private final int restartInterval;

    /**
     * The number of MCUs left before the next restart marker.
     */
    private int restartsToGo;

    /**
     * The number of the next restart marker, from 0 to 7.
     */
    private int nextRestart;

    /**
     * The dummy blocks that pad interleaved MCUs at the right and bottom edges.
     */
    private final short[][] dummies = new short[MAX_BLOCKS_IN_MCU][64];

    /**
     * The coefficient arrays of the blocks of the current MCU.
     */
    private final short[][] mcuData = new short[MAX_BLOCKS_IN_MCU][];

    /**
     * The index of each block of the current MCU in its array.
     */
    private final int[] mcuOffsets = new int[MAX_BLOCKS_IN_MCU];

    /**
     * The scan component of each block of the current MCU.
     */
    private final int[] mcuMembers = new int[MAX_BLOCKS_IN_MCU];

    /**
     * Creates a writer.
     *
     * @param width the image width
     * @param height the image height
     * @param components the components, at most four
     * @param quantTables the natural-order quantization table of each slot the components use
     * @param restartInterval the number of MCUs between restart markers, or {@code 0}
     */
    CoefficientWriter(int width, int height, Component[] components, int[][] quantTables, int restartInterval) {
        this.width = width;
        this.height = height;
        this.components = components;
        this.quantTables = quantTables;
        this.restartInterval = restartInterval;
        var maxX = 1;
        var maxY = 1;
        for (var component : components) {
            maxX = Math.max(maxX, component.samplingX);
            maxY = Math.max(maxY, component.samplingY);
        }
        this.maxSamplingX = maxX;
        this.maxSamplingY = maxY;
        this.out = new JpegBitWriter(1 << 16);
    }

    /**
     * Writes the file.
     *
     * @param header the file header
     * @param markers the marker segments to copy after the file header
     * @param progressive whether to write the progressive scan script instead of one sequential
     * scan
     * @param optimize whether to optimize the Huffman tables; always the case for a progressive
     * file
     * @param ycbcr whether the components are YCbCr, which selects the progressive script
     * @return the file
     * @throws MediaException.Malformed if a coefficient is too large for 8-bit JPEG
     */
    byte[] write(Header header, List<MarkerSegment> markers, boolean progressive, boolean optimize, boolean ycbcr) {
        var optimized = optimize || progressive;
        out.writeMarker(0xD8);
        if (header.jfif()) {
            out.writeMarker(0xE0);
            out.writeShort(16);
            for (var c : "JFIF".toCharArray()) {
                out.writeByte(c);
            }
            out.writeByte(0);
            out.writeByte(header.jfifMajor());
            out.writeByte(header.jfifMinor());
            out.writeByte(header.densityUnit());
            out.writeShort(header.densityX());
            out.writeShort(header.densityY());
            out.writeByte(0);
            out.writeByte(0);
        }
        if (header.adobe()) {
            out.writeMarker(0xEE);
            out.writeShort(14);
            for (var c : "Adobe".toCharArray()) {
                out.writeByte(c);
            }
            out.writeShort(100);
            out.writeShort(0);
            out.writeShort(0);
            out.writeByte(header.adobeTransform());
        }
        for (var marker : markers) {
            var code = marker.marker();
            if (header.jfif() && code == 0xE0 && marker.startsWith("JFIF\0")) {
                continue;
            }
            if (header.adobe() && code == 0xEE && marker.startsWith("Adobe")) {
                continue;
            }
            out.writeMarker(code);
            out.writeShort(marker.data().length + 2);
            out.writeBytes(marker.data());
        }
        if (!optimized) {
            dcSpecs[0] = HuffmanSpec.STD_DC_LUMINANCE;
            dcSpecs[1] = HuffmanSpec.STD_DC_CHROMINANCE;
            acSpecs[0] = HuffmanSpec.STD_AC_LUMINANCE;
            acSpecs[1] = HuffmanSpec.STD_AC_CHROMINANCE;
        }
        var scans = progressive ? progressiveScript(ycbcr) : List.of(sequentialScan());
        for (var index = 0; index < scans.size(); index++) {
            var scan = scans.get(index);
            if (optimized && (scan.ss != 0 || scan.ah == 0)) {
                gatherPass(scan, progressive);
            }
            if (index == 0) {
                writeFrameHeader(progressive);
            }
            writeScanHeader(scan);
            outputPass(scan, progressive);
        }
        out.writeMarker(0xD9);
        return out.toByteArray();
    }

    /**
     * Returns the single scan of a sequential file.
     *
     * @return the scan over every component
     * @throws MediaException.Unsupported if there are more than four components
     */
    private Scan sequentialScan() {
        if (components.length > 4) {
            throw new MediaException.Unsupported("jpeg with " + components.length + " components",
                    "a sequential scan holds at most 4 components");
        }
        var all = new int[components.length];
        for (var i = 0; i < all.length; i++) {
            all[i] = i;
        }
        return new Scan(all, 0, 63, 0, 0);
    }

    /**
     * Returns the progressive scan script of {@code jpeg_simple_progression}.
     *
     * @param ycbcr whether the components are YCbCr
     * @return the scans
     */
    private List<Scan> progressiveScript(boolean ycbcr) {
        var count = components.length;
        var scans = new ArrayList<Scan>();
        if (count == 3 && ycbcr) {
            dcScans(scans, 0, 1);
            scans.add(new Scan(new int[]{0}, 1, 5, 0, 2));
            scans.add(new Scan(new int[]{2}, 1, 63, 0, 1));
            scans.add(new Scan(new int[]{1}, 1, 63, 0, 1));
            scans.add(new Scan(new int[]{0}, 6, 63, 0, 2));
            scans.add(new Scan(new int[]{0}, 1, 63, 2, 1));
            dcScans(scans, 1, 0);
            scans.add(new Scan(new int[]{2}, 1, 63, 1, 0));
            scans.add(new Scan(new int[]{1}, 1, 63, 1, 0));
            scans.add(new Scan(new int[]{0}, 1, 63, 1, 0));
        } else {
            dcScans(scans, 0, 1);
            componentScans(scans, 1, 5, 0, 2);
            componentScans(scans, 6, 63, 0, 2);
            componentScans(scans, 1, 63, 2, 1);
            dcScans(scans, 1, 0);
            componentScans(scans, 1, 63, 1, 0);
        }
        return scans;
    }

    /**
     * Adds one interleaved DC scan, or one per component when there are more than four.
     *
     * @param scans the script
     * @param ah the previous bit position
     * @param al the bit position
     */
    private void dcScans(List<Scan> scans, int ah, int al) {
        if (components.length <= 4) {
            var all = new int[components.length];
            for (var i = 0; i < all.length; i++) {
                all[i] = i;
            }
            scans.add(new Scan(all, 0, 0, ah, al));
        } else {
            componentScans(scans, 0, 0, ah, al);
        }
    }

    /**
     * Adds one scan per component.
     *
     * @param scans the script
     * @param ss the first coefficient
     * @param se the last coefficient
     * @param ah the previous bit position
     * @param al the bit position
     */
    private void componentScans(List<Scan> scans, int ss, int se, int ah, int al) {
        for (var i = 0; i < components.length; i++) {
            scans.add(new Scan(new int[]{i}, ss, se, ah, al));
        }
    }

    /**
     * Writes the DQT segments of the tables the components use and the SOF segment.
     *
     * @param progressive whether the file is progressive
     */
    private void writeFrameHeader(boolean progressive) {
        var wide = false;
        for (var component : components) {
            var slot = component.quantSlot;
            var table = quantTables[slot];
            var precision = 0;
            for (var value : table) {
                if (value > 255) {
                    precision = 1;
                }
            }
            if (!quantSent[slot]) {
                out.writeDqt(slot, table, precision != 0);
                quantSent[slot] = true;
            }
            wide |= precision != 0;
        }
        var baseline = !progressive && !wide;
        for (var component : components) {
            if (component.dcTable > 1 || component.acTable > 1) {
                baseline = false;
            }
        }
        out.writeMarker(progressive ? 0xC2 : baseline ? 0xC0 : 0xC1);
        out.writeShort(3 * components.length + 2 + 5 + 1);
        out.writeByte(8);
        out.writeShort(height);
        out.writeShort(width);
        out.writeByte(components.length);
        for (var component : components) {
            out.writeByte(component.id);
            out.writeByte((component.samplingX << 4) + component.samplingY);
            out.writeByte(component.quantSlot);
        }
    }

    /**
     * Writes the DHT segments a scan needs, a DRI segment when the interval changes, and the SOS
     * segment.
     *
     * @param scan the scan being written
     */
    private void writeScanHeader(Scan scan) {
        for (var index : scan.components) {
            var component = components[index];
            if (scan.ss == 0 && scan.ah == 0) {
                writeHuffmanTable(component.dcTable, false);
            }
            if (scan.se != 0) {
                writeHuffmanTable(component.acTable, true);
            }
        }
        if (restartInterval != lastRestartInterval) {
            out.writeDri(restartInterval);
            lastRestartInterval = restartInterval;
        }
        out.writeMarker(0xDA);
        out.writeShort(2 * scan.components.length + 2 + 1 + 3);
        out.writeByte(scan.components.length);
        for (var index : scan.components) {
            var component = components[index];
            out.writeByte(component.id);
            var dc = scan.ss == 0 && scan.ah == 0 ? component.dcTable : 0;
            var ac = scan.se != 0 ? component.acTable : 0;
            out.writeByte((dc << 4) + ac);
        }
        out.writeByte(scan.ss);
        out.writeByte(scan.se);
        out.writeByte((scan.ah << 4) + scan.al);
    }

    /**
     * Writes a DHT segment unless the table was already written.
     *
     * @param slot the table slot
     * @param ac whether the table is an AC table
     */
    private void writeHuffmanTable(int slot, boolean ac) {
        var sent = ac ? acSent : dcSent;
        if (sent[slot]) {
            return;
        }
        var spec = ac ? acSpecs[slot] : dcSpecs[slot];
        if (spec == null) {
            throw new MediaException.Malformed("no Huffman table in slot " + slot);
        }
        out.writeDht(ac ? slot + 0x10 : slot, spec);
        sent[slot] = true;
    }

    /**
     * Gathers a scan's symbol statistics and replaces the tables it uses with optimal ones.
     *
     * @param scan the scan being written
     * @param progressive whether the file is progressive
     */
    private void gatherPass(Scan scan, boolean progressive) {
        gather = true;
        for (var counts : dcCounts) {
            Arrays.fill(counts, 0);
        }
        for (var counts : acCounts) {
            Arrays.fill(counts, 0);
        }
        encodeScan(scan, progressive);
        var dcDone = new boolean[4];
        var acDone = new boolean[4];
        for (var index : scan.components) {
            var component = components[index];
            if (progressive) {
                if (scan.ss == 0) {
                    if (scan.ah != 0) {
                        continue;
                    }
                    optimize(dcDone, component.dcTable, false);
                } else {
                    optimize(acDone, component.acTable, true);
                }
            } else {
                optimize(dcDone, component.dcTable, false);
                optimize(acDone, component.acTable, true);
            }
        }
        gather = false;
    }

    /**
     * Replaces a table with the optimal one for the gathered counts, once per pass.
     *
     * @param done the slots already replaced in this pass
     * @param slot the table slot
     * @param ac whether the table is an AC table
     */
    private void optimize(boolean[] done, int slot, boolean ac) {
        if (done[slot]) {
            return;
        }
        var spec = HuffmanEncodingTable.optimal(ac ? acCounts[slot] : dcCounts[slot]);
        if (ac) {
            acSpecs[slot] = spec;
            acSent[slot] = false;
        } else {
            dcSpecs[slot] = spec;
            dcSent[slot] = false;
        }
        done[slot] = true;
    }

    /**
     * Writes a scan's entropy-coded data.
     *
     * @param scan the scan being written
     * @param progressive whether the file is progressive
     */
    private void outputPass(Scan scan, boolean progressive) {
        Arrays.fill(dcTables, null);
        Arrays.fill(acTables, null);
        for (var index : scan.components) {
            var component = components[index];
            if (scan.ss == 0 && scan.ah == 0) {
                dcTables[component.dcTable] = new HuffmanEncodingTable(dcSpecs[component.dcTable]);
            }
            if (scan.se != 0) {
                acTables[component.acTable] = new HuffmanEncodingTable(acSpecs[component.acTable]);
            }
        }
        encodeScan(scan, progressive);
    }

    /**
     * Codes or counts every MCU of a scan, then flushes it.
     *
     * @param scan the scan being written
     * @param progressive whether the file is progressive
     */
    private void encodeScan(Scan scan, boolean progressive) {
        Arrays.fill(lastDc, 0);
        eobRun = 0;
        bufferedBits = 0;
        restartsToGo = restartInterval;
        nextRestart = 0;
        var members = scan.components;
        if (members.length == 1) {
            var component = components[members[0]];
            var plane = component.plane;
            for (var row = 0; row < component.blocksHigh; row++) {
                for (var column = 0; column < component.blocksWide; column++) {
                    if (plane.contains(column, row)) {
                        mcuData[0] = plane.data;
                        mcuOffsets[0] = plane.offset(column, row);
                    } else {
                        Arrays.fill(dummies[0], (short) 0);
                        mcuData[0] = dummies[0];
                        mcuOffsets[0] = 0;
                    }
                    mcuMembers[0] = 0;
                    encodeMcu(scan, progressive, 1);
                }
            }
        } else {
            var mcusWide = JpegFrame.ceilDiv(width, maxSamplingX * 8);
            var mcusHigh = JpegFrame.ceilDiv(height, maxSamplingY * 8);
            for (var mcuY = 0; mcuY < mcusHigh; mcuY++) {
                for (var mcuX = 0; mcuX < mcusWide; mcuX++) {
                    var count = 0;
                    for (var member = 0; member < members.length; member++) {
                        var component = components[members[member]];
                        var h = component.samplingX;
                        var v = component.samplingY;
                        var lastColumns = component.blocksWide % h == 0 ? h : component.blocksWide % h;
                        var lastRows = component.blocksHigh % v == 0 ? v : component.blocksHigh % v;
                        var columns = mcuX < mcusWide - 1 ? h : lastColumns;
                        for (var y = 0; y < v; y++) {
                            var x = 0;
                            if (mcuY < mcusHigh - 1 || y < lastRows) {
                                var row = mcuY * v + y;
                                for (; x < columns; x++) {
                                    var column = mcuX * h + x;
                                    if (component.plane.contains(column, row)) {
                                        mcuData[count] = component.plane.data;
                                        mcuOffsets[count] = component.plane.offset(column, row);
                                    } else {
                                        Arrays.fill(dummies[count], (short) 0);
                                        mcuData[count] = dummies[count];
                                        mcuOffsets[count] = 0;
                                    }
                                    mcuMembers[count] = member;
                                    count++;
                                }
                            }
                            for (; x < h; x++) {
                                var dummy = dummies[count];
                                dummy[0] = mcuData[count - 1][mcuOffsets[count - 1]];
                                mcuData[count] = dummy;
                                mcuOffsets[count] = 0;
                                mcuMembers[count] = member;
                                count++;
                            }
                        }
                    }
                    encodeMcu(scan, progressive, count);
                }
            }
        }
        if (progressive) {
            emitEobRun(scan);
        }
        if (!gather) {
            out.flushBits();
        }
    }

    /**
     * Codes or counts one MCU, emitting a restart marker first when one is due.
     *
     * @param scan the scan being written
     * @param progressive whether the file is progressive
     * @param blocks the number of blocks in the MCU
     */
    private void encodeMcu(Scan scan, boolean progressive, int blocks) {
        if (restartInterval != 0) {
            if (restartsToGo == 0) {
                if (progressive) {
                    emitEobRun(scan);
                }
                if (!gather) {
                    out.flushBits();
                    out.writeMarker(0xD0 + nextRestart);
                }
                if (scan.ss == 0) {
                    Arrays.fill(lastDc, 0);
                } else {
                    eobRun = 0;
                    bufferedBits = 0;
                }
            }
        }
        if (!progressive) {
            for (var b = 0; b < blocks; b++) {
                sequentialBlock(mcuData[b], mcuOffsets[b], components[scan.components[mcuMembers[b]]], mcuMembers[b]);
            }
        } else if (scan.ss == 0) {
            if (scan.ah == 0) {
                for (var b = 0; b < blocks; b++) {
                    dcFirst(mcuData[b], mcuOffsets[b], components[scan.components[mcuMembers[b]]], mcuMembers[b],
                            scan.al);
                }
            } else {
                for (var b = 0; b < blocks; b++) {
                    emitBits((mcuData[b][mcuOffsets[b]] >> scan.al) & 1, 1);
                }
            }
        } else if (scan.ah == 0) {
            acFirst(mcuData[0], mcuOffsets[0], scan);
        } else {
            acRefine(mcuData[0], mcuOffsets[0], scan);
        }
        if (restartInterval != 0) {
            if (restartsToGo == 0) {
                restartsToGo = restartInterval;
                nextRestart = (nextRestart + 1) & 7;
            }
            restartsToGo--;
        }
    }

    /**
     * Returns the number of bits of a magnitude.
     *
     * @param magnitude the non-negative value
     * @return {@code 0} for zero, else the position of its highest set bit plus one
     */
    private static int bitLength(int magnitude) {
        return 32 - Integer.numberOfLeadingZeros(magnitude);
    }

    /**
     * Codes or counts one block of a sequential scan.
     *
     * @param data the coefficient array
     * @param base the index of the block's first coefficient
     * @param component the block's component
     * @param member the component's position in the scan
     */
    private void sequentialBlock(short[] data, int base, Component component, int member) {
        var difference = data[base] - lastDc[member];
        lastDc[member] = data[base];
        var magnitude = Math.abs(difference);
        var bits = bitLength(magnitude);
        if (bits > MAX_COEFFICIENT_BITS + 1) {
            throw new MediaException.Malformed("DC difference " + difference + " is too large for 8-bit JPEG");
        }
        emitDc(component.dcTable, bits);
        if (bits != 0) {
            emitBits((difference < 0 ? difference - 1 : difference) & ((1 << bits) - 1), bits);
        }
        var run = 0;
        for (var k = 1; k < 64; k++) {
            int value = data[base + JpegTables.ZIGZAG[k]];
            if (value == 0) {
                run++;
                continue;
            }
            while (run > 15) {
                emitAc(component.acTable, 0xF0);
                run -= 16;
            }
            magnitude = Math.abs(value);
            bits = bitLength(magnitude);
            if (bits > MAX_COEFFICIENT_BITS) {
                throw new MediaException.Malformed("AC coefficient " + value + " is too large for 8-bit JPEG");
            }
            emitAc(component.acTable, (run << 4) + bits);
            emitBits((value < 0 ? value - 1 : value) & ((1 << bits) - 1), bits);
            run = 0;
        }
        if (run > 0) {
            emitAc(component.acTable, 0);
        }
    }

    /**
     * Codes or counts the DC coefficient of one block in a first DC scan.
     *
     * @param data the coefficient array
     * @param base the index of the block's first coefficient
     * @param component the block's component
     * @param member the component's position in the scan
     * @param al the point transform
     */
    private void dcFirst(short[] data, int base, Component component, int member, int al) {
        var shifted = data[base] >> al;
        var difference = shifted - lastDc[member];
        lastDc[member] = shifted;
        var bits = bitLength(Math.abs(difference));
        if (bits > MAX_COEFFICIENT_BITS + 1) {
            throw new MediaException.Malformed("DC difference " + difference + " is too large for 8-bit JPEG");
        }
        emitDc(component.dcTable, bits);
        if (bits != 0) {
            emitBits((difference < 0 ? difference - 1 : difference) & ((1 << bits) - 1), bits);
        }
    }

    /**
     * Codes or counts the AC band of one block in a first AC scan.
     *
     * @param data the coefficient array
     * @param base the index of the block's first coefficient
     * @param scan the scan being written
     */
    private void acFirst(short[] data, int base, Scan scan) {
        var table = components[scan.components[0]].acTable;
        var run = 0;
        var pendingEob = true;
        var trailing = false;
        for (var k = scan.ss; k <= scan.se; k++) {
            int value = data[base + JpegTables.ZIGZAG[k]];
            var magnitude = Math.abs(value) >> scan.al;
            if (magnitude == 0) {
                run++;
                trailing = true;
                continue;
            }
            trailing = false;
            if (pendingEob) {
                emitEobRun(scan);
                pendingEob = false;
            }
            while (run > 15) {
                emitAc(table, 0xF0);
                run -= 16;
            }
            var bits = bitLength(magnitude);
            if (bits > MAX_COEFFICIENT_BITS) {
                throw new MediaException.Malformed("AC coefficient " + value + " is too large for 8-bit JPEG");
            }
            emitAc(table, (run << 4) + bits);
            emitBits((value < 0 ? ~magnitude : magnitude) & ((1 << bits) - 1), bits);
            run = 0;
        }
        if (trailing) {
            eobRun++;
            if (eobRun == 0x7FFF) {
                emitEobRun(scan);
            }
        }
    }

    /**
     * Codes or counts the AC band of one block in an AC refinement scan.
     *
     * @param data the coefficient array
     * @param base the index of the block's first coefficient
     * @param scan the scan being written
     */
    private void acRefine(short[] data, int base, Scan scan) {
        var table = components[scan.components[0]].acTable;
        var length = scan.se - scan.ss + 1;
        var magnitudes = new int[length];
        var end = -1;
        for (var k = 0; k < length; k++) {
            var magnitude = Math.abs((int) data[base + JpegTables.ZIGZAG[scan.ss + k]]) >> scan.al;
            magnitudes[k] = magnitude;
            if (magnitude == 1) {
                end = k;
            }
        }
        var run = 0;
        var held = 0;
        var heldStart = bufferedBits;
        for (var k = 0; k < length; k++) {
            var magnitude = magnitudes[k];
            if (magnitude == 0) {
                run++;
                continue;
            }
            while (run > 15 && k <= end) {
                emitEobRun(scan);
                emitAc(table, 0xF0);
                run -= 16;
                emitBuffered(heldStart, held);
                heldStart = 0;
                held = 0;
            }
            if (magnitude > 1) {
                correctionBits[heldStart + held++] = (byte) (magnitude & 1);
                continue;
            }
            emitEobRun(scan);
            emitAc(table, (run << 4) + 1);
            emitBits(data[base + JpegTables.ZIGZAG[scan.ss + k]] < 0 ? 0 : 1, 1);
            emitBuffered(heldStart, held);
            heldStart = 0;
            held = 0;
            run = 0;
        }
        if (run > 0 || held > 0) {
            eobRun++;
            bufferedBits += held;
            if (eobRun == 0x7FFF || bufferedBits > MAX_CORRECTION_BITS - 64 + 1) {
                emitEobRun(scan);
            }
        }
    }

    /**
     * Codes or counts the pending end-of-band run, then writes the correction bits held with it.
     *
     * @param scan the scan being written
     */
    private void emitEobRun(Scan scan) {
        if (eobRun <= 0) {
            return;
        }
        var bits = bitLength(eobRun) - 1;
        if (bits > 14) {
            throw new MediaException.Malformed("end-of-band run overflow");
        }
        emitAc(components[scan.components[0]].acTable, bits << 4);
        if (bits != 0) {
            emitBits(eobRun & ((1 << bits) - 1), bits);
        }
        eobRun = 0;
        emitBuffered(0, bufferedBits);
        bufferedBits = 0;
    }

    /**
     * Writes held correction bits, unless gathering.
     *
     * @param start the index of the first bit
     * @param count the number of bits
     */
    private void emitBuffered(int start, int count) {
        if (gather) {
            return;
        }
        for (var i = 0; i < count; i++) {
            out.put(correctionBits[start + i], 1);
        }
    }

    /**
     * Codes or counts a DC symbol.
     *
     * @param slot the table slot
     * @param symbol the Huffman symbol
     */
    private void emitDc(int slot, int symbol) {
        if (gather) {
            dcCounts[slot][symbol]++;
            return;
        }
        var table = dcTables[slot];
        var length = table.lengths[symbol];
        if (length == 0) {
            throw new MediaException.Malformed("DC symbol " + symbol + " has no Huffman code");
        }
        out.put(table.codes[symbol], length);
    }

    /**
     * Codes or counts an AC symbol.
     *
     * @param slot the table slot
     * @param symbol the Huffman symbol
     */
    private void emitAc(int slot, int symbol) {
        if (gather) {
            acCounts[slot][symbol]++;
            return;
        }
        var table = acTables[slot];
        var length = table.lengths[symbol];
        if (length == 0) {
            throw new MediaException.Malformed("AC symbol " + symbol + " has no Huffman code");
        }
        out.put(table.codes[symbol], length);
    }

    /**
     * Writes raw bits, unless gathering.
     *
     * @param value the bits, right-aligned, no bit set above {@code length}
     * @param length the number of bits
     */
    private void emitBits(int value, int length) {
        if (!gather) {
            out.put(value, length);
        }
    }
}
