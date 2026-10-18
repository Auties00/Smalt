/* Ports jxl-oxide 0.12.6 (crates/jxl-jbr: the scan writer) and libjxl (lib/jxl/jpeg/dec_jpeg_data_writer.cc).
   Copyright Wonwoo Choi and the jxl-oxide contributors, and the JPEG XL Project Authors; MIT License and
   BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.image.jxl.internal.jpeg;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Writes the entropy-coded data of one JPEG scan from the coefficients of a recompressed JPEG.
 *
 * <p>A scan is sequential, a progressive first scan (DC, or an AC band with end-of-band runs), or
 * a progressive refinement scan (one more bit of DC, or of an AC band with correction bits). The
 * {@code jbrd} box records where the original encoder flushed its end-of-band runs and where it
 * wrote zero runs a minimal encoder would not, so the output repeats the original bits exactly;
 * restart markers and the original padding bits are restored too.
 *
 * <p>An MCU covers the sampling factors of every frame component; a scan of one component codes
 * one block per MCU, laid over that component's own block grid.
 *
 * @implNote This implementation lays the MCU grid of a scan of one subsampled component out as
 * libjxl does: jxl-oxide counts such a scan's blocks on the luma grid and cannot rebuild
 * progressive subsampled JPEG files.
 */
final class JpegScanWriter {
    /**
     * The scan kind of a sequential scan.
     */
    private static final int SEQUENTIAL = 0;

    /**
     * The scan kind of a progressive first scan.
     */
    private static final int FIRST = 1;

    /**
     * The horizontal JPEG sampling factor of each JPEG XL upsampling mode.
     */
    private static final int[] H_SAMPLES = {1, 2, 2, 1};

    /**
     * The vertical JPEG sampling factor of each JPEG XL upsampling mode.
     */
    private static final int[] V_SAMPLES = {1, 2, 1, 2};

    /**
     * The longest end-of-band run before it is flushed.
     */
    private static final int MAX_EOB_RUN = 32767;

    /**
     * The reconstruction.
     */
    private final JpegReconstructor owner;

    /**
     * The scan description.
     */
    private final JbrdHeader.ScanInfo scan;

    /**
     * The scan kind: {@link #SEQUENTIAL}, {@link #FIRST} or refinement.
     */
    private final int kind;

    /**
     * The bits being written.
     */
    private BitWriter bits = new BitWriter();

    /**
     * The last DC value of each scan component.
     */
    private final int[] dcPrediction;

    /**
     * The pending end-of-band run.
     */
    private int eobRun;

    /**
     * The AC table the pending end-of-band run is coded with.
     */
    private HuffmanTable lastAcTable;

    /**
     * The correction bits buffered with the pending end-of-band run.
     */
    private final List<long[]> refinements = new ArrayList<>();

    /**
     * The next restart marker number.
     */
    private int restartMarker;

    /**
     * Creates the writer of a scan.
     *
     * @param owner the reconstruction
     * @param scan the scan description
     * @param kind 0 for sequential, 1 for a progressive first scan, 2 for a refinement scan
     */
    JpegScanWriter(JpegReconstructor owner, JbrdHeader.ScanInfo scan, int kind) {
        this.owner = owner;
        this.scan = scan;
        this.kind = kind;
        this.dcPrediction = new int[scan.componentIndex().length];
    }

    /**
     * Writes the scan data.
     */
    void write() {
        var frameHeader = owner.frameHeader();
        var frame = owner.frame();
        var components = scan.componentIndex();
        var count = components.length;
        var hsamples = new int[count];
        var vsamples = new int[count];
        var frameMaxH = 1;
        var frameMaxV = 1;
        for (var c = 0; c < owner.componentCount(); c++) {
            frameMaxH = Math.max(frameMaxH, H_SAMPLES[owner.upsampling(c)]);
            frameMaxV = Math.max(frameMaxV, V_SAMPLES[owner.upsampling(c)]);
        }
        int maxHShift;
        int maxVShift;
        int w8;
        int h8;
        if (count == 1) {
            var mode = owner.upsampling(components[0]);
            maxHShift = Integer.numberOfTrailingZeros(frameMaxH / H_SAMPLES[mode]);
            maxVShift = Integer.numberOfTrailingZeros(frameMaxV / V_SAMPLES[mode]);
            w8 = (frameHeader.width + (8 << maxHShift) - 1) / (8 << maxHShift);
            h8 = (frameHeader.height + (8 << maxVShift) - 1) / (8 << maxVShift);
            hsamples[0] = 1;
            vsamples[0] = 1;
        } else {
            for (var c = 0; c < count; c++) {
                var mode = owner.upsampling(components[c]);
                hsamples[c] = H_SAMPLES[mode];
                vsamples[c] = V_SAMPLES[mode];
            }
            maxHShift = Integer.numberOfTrailingZeros(frameMaxH);
            maxVShift = Integer.numberOfTrailingZeros(frameMaxV);
            w8 = (frameHeader.width + (8 << maxHShift) - 1) / (8 << maxHShift);
            h8 = (frameHeader.height + (8 << maxVShift) - 1) / (8 << maxVShift);
        }
        var ss = Math.max(scan.ss(), 1);
        var se = scan.se() + 1;
        var al = scan.al();
        if (se > 64 || ss > se) {
            throw JbrdHeader.malformed("invalid spectral selection");
        }
        var zigzag = JpegReconstructor.zigzag();
        var hs = frame.hshifts();
        var vs = frame.vshifts();
        var gd = frameHeader.groupDim();
        var perRow = frameHeader.groupsPerRow();
        var restartInterval = owner.restartInterval();
        var ac = new int[Math.max(se - ss, 0)];
        var blockIndex = 0;
        for (var y8 = 0; y8 < h8; y8++) {
            for (var x8 = 0; x8 < w8; x8++) {
                var mcu = x8 + w8 * y8;
                if (restartInterval != 0 && mcu != 0 && mcu % restartInterval == 0) {
                    restart();
                }
                var px = x8 << (3 + maxHShift);
                var py = y8 << (3 + maxVShift);
                var group = (py / gd) * perRow + px / gd;
                if (px >= frameHeader.width || py >= frameHeader.height || group >= frameHeader.numGroups()) {
                    throw JbrdHeader.malformed("MCU outside the frame");
                }
                var lfGroup = frameHeader.lfGroupOfGroup(group);
                var coefficients = frame.coefficients(group);
                var lfQuant = frame.lfQuant(lfGroup);
                var lfStride = frame.lfQuantStride(lfGroup);
                if (lfQuant == null) {
                    throw JbrdHeader.malformed("missing LF coefficients");
                }
                for (var c = 0; c < count; c++) {
                    var dcTable = owner.dcTable(scan.dcTable()[c]);
                    var acTable = owner.acTable(scan.acTable()[c]);
                    if (lastAcTable == null) {
                        lastAcTable = acTable;
                    }
                    var index = frameHeader.doYCbCr ? components[c] : JpegReconstructor.xybChannel(components[c]);
                    var channel = JpegReconstructor.xybChannel(index);
                    var dcOffset = owner.dcOffset(index);
                    var groupWidthMask = (gd >> hs[channel]) - 1;
                    var groupHeightMask = (gd >> vs[channel]) - 1;
                    var stride = frame.groupStride(group, channel);
                    var plane = coefficients == null ? null : coefficients[channel];
                    var lfPlane = lfQuant[channel];
                    var lfWidth = lfStride[channel];
                    for (var dy8 = 0; dy8 < vsamples[c]; dy8++) {
                        var yDc = y8 * vsamples[c] + dy8;
                        var yAc = (yDc * 8) & groupHeightMask;
                        yDc &= groupHeightMask;
                        for (var dx8 = 0; dx8 < hsamples[c]; dx8++) {
                            var xDc = x8 * hsamples[c] + dx8;
                            var xAc = (xDc * 8) & groupWidthMask;
                            xDc &= groupWidthMask;
                            var dc = 0;
                            if (scan.ss() == 0) {
                                var lfIndex = yDc * lfWidth + xDc;
                                if (xDc >= lfWidth || lfIndex >= lfPlane.length) {
                                    throw JbrdHeader.malformed("block outside the LF image");
                                }
                                var value = (short) lfPlane[lfIndex] - dcOffset;
                                value = Math.clamp(value, Short.MIN_VALUE, Short.MAX_VALUE);
                                dc = Math.clamp(value, -2047, 2047) >> al;
                            }
                            for (var k = ss; k < se; k++) {
                                var position = zigzag[k];
                                var x = xAc + (position & 0xFFFF);
                                var y = yAc + (position >>> 16);
                                var coefficient = 0;
                                if (plane != null) {
                                    var offset = y * stride + x;
                                    if (x >= stride || offset >= plane.length) {
                                        throw JbrdHeader.malformed("block outside the group");
                                    }
                                    coefficient = (short) plane[offset];
                                }
                                ac[k - ss] = (short) (coefficient < 0 ? -((-coefficient) >> al) : coefficient >> al);
                            }
                            var extraZeroRuns = scan.extraZeroRuns().get(blockIndex);
                            if (scan.resetPoints().contains(blockIndex)) {
                                emitEobRun();
                            }
                            var hasDc = scan.ss() == 0;
                            switch (kind) {
                                case SEQUENTIAL -> sequential(c, dcTable, acTable, dc, ac, se - ss, extraZeroRuns);
                                case FIRST -> progressiveFirst(c, dcTable, acTable, hasDc, dc, ac, se - ss,
                                        extraZeroRuns);
                                default -> refinement(acTable, hasDc, dc, ac, se - ss, extraZeroRuns);
                            }
                            blockIndex++;
                        }
                    }
                }
            }
        }
        flush();
    }

    /**
     * Writes a block of a sequential scan.
     *
     * @param component the scan component
     * @param dcTable the DC table
     * @param acTable the AC table
     * @param dc the DC value
     * @param ac the AC values in zig-zag order
     * @param length the number of AC values
     * @param extraZeroRuns the extra zero runs before the end of block, or {@code null}
     */
    private void sequential(int component, HuffmanTable dcTable, HuffmanTable acTable, int dc, int[] ac, int length,
                            Integer extraZeroRuns) {
        writeDc(component, dcTable, dc);
        var last = writeAcRuns(acTable, ac, length, false);
        var zeros = length - last;
        if (extraZeroRuns != null) {
            for (var run = 0; run < extraZeroRuns; run++) {
                writeSymbol(acTable, 0xF0);
            }
            zeros -= extraZeroRuns * 16;
        }
        if (zeros > 0) {
            writeSymbol(acTable, 0);
        }
    }

    /**
     * Writes a block of a progressive first scan.
     *
     * @param component the scan component
     * @param dcTable the DC table
     * @param acTable the AC table
     * @param hasDc whether the scan codes DC
     * @param dc the DC value
     * @param ac the AC values in zig-zag order
     * @param length the number of AC values
     * @param extraZeroRuns the extra zero runs before the end of block, or {@code null}
     */
    private void progressiveFirst(int component, HuffmanTable dcTable, HuffmanTable acTable, boolean hasDc, int dc,
                                  int[] ac, int length, Integer extraZeroRuns) {
        if (hasDc) {
            emitEobRun();
            writeDc(component, dcTable, dc);
        }
        var last = writeAcRuns(acTable, ac, length, true);
        var zeros = length - last;
        if (extraZeroRuns != null) {
            emitEobRun();
            for (var run = 0; run < extraZeroRuns; run++) {
                writeSymbol(acTable, 0xF0);
            }
            zeros -= extraZeroRuns * 16;
        }
        if (eobRun == 0) {
            lastAcTable = acTable;
        }
        if (zeros > 0) {
            eobRun++;
            if (eobRun >= MAX_EOB_RUN) {
                emitEobRun();
            }
        }
    }

    /**
     * Writes a block of a progressive refinement scan.
     *
     * @param acTable the AC table
     * @param hasDc whether the scan refines DC
     * @param dc the DC value, whose lowest bit is the refinement
     * @param ac the AC values in zig-zag order
     * @param length the number of AC values
     * @param extraZeroRuns the extra zero runs before the end of block, or {@code null}
     */
    private void refinement(HuffmanTable acTable, boolean hasDc, int dc, int[] ac, int length, Integer extraZeroRuns) {
        if (hasDc) {
            emitEobRun();
            bits.write(dc, 1);
        }
        var start = 0;
        while (true) {
            var next = -1;
            for (var k = start; k < length; k++) {
                if (ac[k] == 1 || ac[k] == -1) {
                    next = k;
                    break;
                }
            }
            if (next < 0) {
                break;
            }
            emitEobRun();
            var zeroRuns = 0;
            var refinementLength = 0;
            var refinementBits = 0L;
            for (var k = start; k < next; k++) {
                if (ac[k] == 0) {
                    zeroRuns++;
                    if (zeroRuns == 16) {
                        writeSymbol(acTable, 0xF0);
                        bits.write(refinementBits, refinementLength);
                        zeroRuns = 0;
                        refinementLength = 0;
                    }
                } else {
                    refinementBits = (refinementBits << 1) | (ac[k] & 1);
                    refinementLength++;
                }
            }
            writeSymbol(acTable, (zeroRuns << 4) | 1);
            bits.write(ac[next] == 1 ? 1 : 0, 1);
            bits.write(refinementBits, refinementLength);
            start = next + 1;
        }
        var remainingRuns = extraZeroRuns == null ? 0 : extraZeroRuns;
        if (remainingRuns > 0) {
            emitEobRun();
        }
        var zeroRuns = 0;
        var refinementLength = 0;
        var refinementBits = 0L;
        for (var k = start; k < length; k++) {
            if (ac[k] == 0) {
                zeroRuns++;
                if (remainingRuns > 0 && zeroRuns == 16) {
                    writeSymbol(acTable, 0xF0);
                    bits.write(refinementBits, refinementLength);
                    zeroRuns = 0;
                    refinementLength = 0;
                    remainingRuns--;
                }
            } else {
                refinementBits = (refinementBits << 1) | (ac[k] & 1);
                refinementLength++;
            }
        }
        for (; remainingRuns > 0; remainingRuns--) {
            writeSymbol(acTable, 0xF0);
            bits.write(refinementBits, refinementLength);
            zeroRuns = 0;
            refinementLength = 0;
        }
        if (eobRun == 0) {
            lastAcTable = acTable;
        }
        if (zeroRuns > 0 || refinementLength > 0) {
            eobRun++;
            refinements.add(new long[]{refinementBits, refinementLength});
            if (eobRun >= MAX_EOB_RUN) {
                emitEobRun();
            }
        }
    }

    /**
     * Writes a DC difference.
     *
     * @param component the scan component
     * @param table the DC table
     * @param dc the DC value
     */
    private void writeDc(int component, HuffmanTable table, int dc) {
        var diff = (short) (dc - dcPrediction[component]);
        dcPrediction[component] = dc;
        var magnitude = Math.abs(diff);
        var length = 32 - Integer.numberOfLeadingZeros(magnitude);
        var raw = diff < 0 ? -magnitude - 1 : magnitude;
        writeSymbol(table, length);
        bits.write(raw, length);
    }

    /**
     * Writes the non-zero AC values of a block with their zero runs.
     *
     * @param table the AC table
     * @param ac the AC values in zig-zag order
     * @param length the number of AC values
     * @param flushRuns whether each non-zero value first flushes the end-of-band run
     * @return the index after the last non-zero value
     */
    private int writeAcRuns(HuffmanTable table, int[] ac, int length, boolean flushRuns) {
        var start = 0;
        for (var k = 0; k < length; k++) {
            var value = ac[k];
            if (value == 0) {
                continue;
            }
            if (flushRuns) {
                emitEobRun();
            }
            var run = k - start;
            start = k + 1;
            while (run >= 16) {
                writeSymbol(table, 0xF0);
                run -= 16;
            }
            var magnitude = Math.abs(value);
            var size = 32 - Integer.numberOfLeadingZeros(magnitude);
            var raw = value < 0 ? ~magnitude : magnitude;
            writeSymbol(table, (run << 4) | size);
            bits.write(raw, size);
        }
        return start;
    }

    /**
     * Writes the Huffman code of a symbol.
     *
     * @param table the Huffman table of the scan component
     * @param symbol the symbol to code
     */
    private void writeSymbol(HuffmanTable table, int symbol) {
        bits.write(table.code(symbol), table.length(symbol));
    }

    /**
     * Writes the pending end-of-band run and its buffered correction bits.
     */
    private void emitEobRun() {
        if (eobRun == 0) {
            return;
        }
        if (lastAcTable == null) {
            throw JbrdHeader.malformed("end-of-band run without an AC table");
        }
        var bitsCount = 31 - Integer.numberOfLeadingZeros(eobRun);
        writeSymbol(lastAcTable, bitsCount << 4);
        bits.write(eobRun & ((1 << bitsCount) - 1), bitsCount);
        eobRun = 0;
        for (var refinement : refinements) {
            bits.write(refinement[0], (int) refinement[1]);
        }
        refinements.clear();
    }

    /**
     * Pads the entropy-coded segment to a byte and appends it to the output.
     */
    private void flush() {
        emitEobRun();
        var padding = bits.paddingBits();
        if (padding != 0) {
            bits.write(owner.paddingBits(padding), padding);
        }
        owner.emit(bits.finish());
        bits = new BitWriter();
    }

    /**
     * Ends a restart interval: flushes the segment and writes the next restart marker.
     */
    private void restart() {
        Arrays.fill(dcPrediction, 0);
        flush();
        owner.emitRestart(restartMarker);
        restartMarker = (restartMarker + 1) & 7;
    }

    /**
     * Packs bits most significant first into bytes, stuffing a zero byte after every
     * {@code 0xFF}.
     */
    private static final class BitWriter {
        /**
         * Creates an empty instance.
         */
        BitWriter() {
        }

        /**
         * The packed bytes.
         */
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        /**
         * The pending bits, right-aligned.
         */
        private long accumulator;

        /**
         * The number of pending bits, below 8 between writes.
         */
        private int count;

        /**
         * Appends the low bits of a value.
         *
         * @param value the value; bits above {@code length} are ignored
         * @param length the number of bits, 0 to 63
         */
        void write(long value, int length) {
            if (length == 0) {
                return;
            }
            if (length > 32) {
                write(value >>> 32, length - 32);
                write(value & 0xFFFF_FFFFL, 32);
                return;
            }
            accumulator = (accumulator << length) | (value & ((1L << length) - 1));
            count += length;
            while (count >= 8) {
                var b = (int) (accumulator >>> (count - 8)) & 0xFF;
                bytes.write(b);
                if (b == 0xFF) {
                    bytes.write(0);
                }
                count -= 8;
            }
            accumulator &= (1L << count) - 1;
        }

        /**
         * Returns the number of bits that complete the last byte.
         *
         * @return 0 to 7
         */
        int paddingBits() {
            return (8 - count) & 7;
        }

        /**
         * Returns the packed bytes, which must end on a byte boundary.
         *
         * @return the bytes
         */
        byte[] finish() {
            return bytes.toByteArray();
        }
    }
}
