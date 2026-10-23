package com.github.auties00.smalt.image.jpeg.internal;

import com.github.auties00.smalt.MediaException;

import java.util.Arrays;

/**
 * Decodes the arithmetic-coded entropy data of sequential and progressive scans (T.81 Annex D and
 * sections F.1.4, F.2.4, G.1.3, G.2) and of lossless scans (H.1.2.3, H.2).
 *
 * <p>The decoder reads the unstuffed segments of {@link EntropySegments}. Past the end of a
 * segment it reads zero bytes, which is the convention of T.81 for arithmetic-coded data that
 * meets a marker. A decoding error (a magnitude category or spectral position out of range) stops
 * the scan up to the next restart marker, leaving the remaining blocks at zero coefficients.
 *
 * @implNote This implementation is a port of libjpeg-turbo's jdarith.c and jaricom.c, including
 * its floating code-register cut point, its compact probability state machine, and its error
 * handling; conditioning statistics are reset at every scan and restart as there.
 */
final class ArithmeticScanDecoder {
    /**
     * The probability estimation state machine of T.81 Table D.2 as quadruples of
     * {@code Qe}, next state after an LPS, next state after an MPS, and the MPS switch flag, plus a
     * final state with a fixed estimate of 0.5 for bypass decisions (T.851 Table 5).
     */
    static final int[] STATE_TABLE = {
            0x5a1d, 1, 1, 1, 0x2586, 14, 2, 0, 0x1114, 16, 3, 0, 0x080b, 18, 4, 0,
            0x03d8, 20, 5, 0, 0x01da, 23, 6, 0, 0x00e5, 25, 7, 0, 0x006f, 28, 8, 0,
            0x0036, 30, 9, 0, 0x001a, 33, 10, 0, 0x000d, 35, 11, 0, 0x0006, 9, 12, 0,
            0x0003, 10, 13, 0, 0x0001, 12, 13, 0, 0x5a7f, 15, 15, 1, 0x3f25, 36, 16, 0,
            0x2cf2, 38, 17, 0, 0x207c, 39, 18, 0, 0x17b9, 40, 19, 0, 0x1182, 42, 20, 0,
            0x0cef, 43, 21, 0, 0x09a1, 45, 22, 0, 0x072f, 46, 23, 0, 0x055c, 48, 24, 0,
            0x0406, 49, 25, 0, 0x0303, 51, 26, 0, 0x0240, 52, 27, 0, 0x01b1, 54, 28, 0,
            0x0144, 56, 29, 0, 0x00f5, 57, 30, 0, 0x00b7, 59, 31, 0, 0x008a, 60, 32, 0,
            0x0068, 62, 33, 0, 0x004e, 63, 34, 0, 0x003b, 32, 35, 0, 0x002c, 33, 9, 0,
            0x5ae1, 37, 37, 1, 0x484c, 64, 38, 0, 0x3a0d, 65, 39, 0, 0x2ef1, 67, 40, 0,
            0x261f, 68, 41, 0, 0x1f33, 69, 42, 0, 0x19a8, 70, 43, 0, 0x1518, 72, 44, 0,
            0x1177, 73, 45, 0, 0x0e74, 74, 46, 0, 0x0bfb, 75, 47, 0, 0x09f8, 77, 48, 0,
            0x0861, 78, 49, 0, 0x0706, 79, 50, 0, 0x05cd, 48, 51, 0, 0x04de, 50, 52, 0,
            0x040f, 50, 53, 0, 0x0363, 51, 54, 0, 0x02d4, 52, 55, 0, 0x025c, 53, 56, 0,
            0x01f8, 54, 57, 0, 0x01a4, 55, 58, 0, 0x0160, 56, 59, 0, 0x0125, 57, 60, 0,
            0x00f6, 58, 61, 0, 0x00cb, 59, 62, 0, 0x00ab, 61, 63, 0, 0x008f, 61, 32, 0,
            0x5b12, 65, 65, 1, 0x4d04, 80, 66, 0, 0x412c, 81, 67, 0, 0x37d8, 82, 68, 0,
            0x2fe8, 83, 69, 0, 0x293c, 84, 70, 0, 0x2379, 86, 71, 0, 0x1edf, 87, 72, 0,
            0x1aa9, 87, 73, 0, 0x174e, 72, 74, 0, 0x1424, 72, 75, 0, 0x119c, 74, 76, 0,
            0x0f6b, 74, 77, 0, 0x0d51, 75, 78, 0, 0x0bb6, 77, 79, 0, 0x0a40, 77, 48, 0,
            0x5832, 80, 81, 1, 0x4d1c, 88, 82, 0, 0x438e, 89, 83, 0, 0x3bdd, 90, 84, 0,
            0x34ee, 91, 85, 0, 0x2eae, 92, 86, 0, 0x299a, 93, 87, 0, 0x2516, 86, 71, 0,
            0x5570, 88, 89, 1, 0x4ca9, 95, 90, 0, 0x44d9, 96, 91, 0, 0x3e22, 97, 92, 0,
            0x3824, 99, 93, 0, 0x32b4, 99, 94, 0, 0x2e17, 93, 86, 0, 0x56a8, 95, 96, 1,
            0x4f46, 101, 97, 0, 0x47e5, 102, 98, 0, 0x41cf, 103, 99, 0, 0x3c3d, 104, 100, 0,
            0x375e, 99, 93, 0, 0x5231, 105, 102, 0, 0x4c0f, 106, 103, 0, 0x4639, 107, 104, 0,
            0x415e, 103, 99, 0, 0x5627, 105, 106, 1, 0x50e7, 108, 107, 0, 0x4b85, 109, 103, 0,
            0x5597, 110, 109, 0, 0x504f, 111, 107, 0, 0x5a10, 110, 111, 1, 0x5522, 112, 109, 0,
            0x59eb, 112, 111, 1, 0x5a1d, 113, 113, 0
    };

    /**
     * The states of {@link #STATE_TABLE} packed as
     * {@code (Qe << 16) | (nextMps << 8) | (switchMps << 7) | nextLps}, the layout of libjpeg's
     * {@code jpeg_aritab}.
     */
    private static final int[] STATES = packStates();

    /**
     * The number of DC conditioning bins per table.
     */
    private static final int DC_BINS = 64;

    /**
     * The number of AC conditioning bins per table.
     */
    private static final int AC_BINS = 256;

    /**
     * The number of conditioning bins per table of a lossless scan (T.81 H.1.2.3.2).
     */
    private static final int LOSSLESS_BINS = 158;

    /**
     * The zigzag to natural order map, padded to 80 entries.
     */
    private static final int[] ZIGZAG = JpegTables.ZIGZAG;

    /**
     * The source of the entropy-coded data.
     */
    private final EntropySegments segments;

    /**
     * The DC conditioning statistics of each table slot.
     */
    private final byte[][] dcStats = new byte[16][DC_BINS];

    /**
     * The AC conditioning statistics of each table slot.
     */
    private final byte[][] acStats = new byte[16][AC_BINS];

    /**
     * The lossless conditioning statistics of each table slot, created on first use.
     */
    private byte[][] losslessStats;

    /**
     * The table slot of each component of the lossless scan being decoded.
     */
    private int[] losslessSlots;

    /**
     * The single statistics bin with a fixed estimate of 0.5.
     */
    private final byte[] fixedBin = new byte[1];

    /**
     * The DC prediction of each scan component.
     */
    private final int[] lastDc = new int[4];

    /**
     * The DC conditioning context of each scan component.
     */
    private final int[] dcContext = new int[4];

    /**
     * Whether the scan belongs to a differential frame, whose DC coefficients are not predicted.
     */
    private boolean differential;

    /**
     * The coefficients of one block in natural order, used in pixel mode.
     */
    private final short[] scratch = new short[64];

    /**
     * The dequantization scratch block used in pixel mode.
     */
    private final int[] block = new int[64];

    /**
     * The unstuffed entropy-coded data.
     */
    private byte[] data;

    /**
     * The index of the next byte to read.
     */
    private int position;

    /**
     * The index one past the last byte of the current segment.
     */
    private int segmentEnd;

    /**
     * The index of the segment whose terminating marker is the next unread marker.
     */
    private int segment;

    /**
     * The restart number expected at the next restart boundary.
     */
    private int nextRestart;

    /**
     * The code register C.
     */
    private long c;

    /**
     * The interval register A.
     */
    private long a;

    /**
     * The bit shift counter; {@code -1} marks a decoding error, {@code -16} a fresh start.
     */
    private int ct;

    /**
     * The DC table slot of each scan component.
     */
    private int[] dcSlots;

    /**
     * The AC table slot of each scan component.
     */
    private int[] acSlots;

    /**
     * The lower DC conditioning bound of each scan component.
     */
    private int[] dcLower;

    /**
     * The upper DC conditioning bound of each scan component.
     */
    private int[] dcUpper;

    /**
     * The AC conditioning threshold of each scan component.
     */
    private int[] acThreshold;

    /**
     * Packs {@link #STATE_TABLE} into {@link #STATES}.
     *
     * @return the packed states
     */
    private static int[] packStates() {
        var states = new int[STATE_TABLE.length / 4];
        for (var i = 0; i < states.length; i++) {
            var qe = STATE_TABLE[4 * i];
            var nextLps = STATE_TABLE[4 * i + 1];
            var nextMps = STATE_TABLE[4 * i + 2];
            var switchMps = STATE_TABLE[4 * i + 3];
            states[i] = (qe << 16) | (nextMps << 8) | (switchMps << 7) | nextLps;
        }
        return states;
    }

    /**
     * Constructs a decoder over the segments of the current scan.
     *
     * @param segments the segment store filled before every scan
     */
    ArithmeticScanDecoder(EntropySegments segments) {
        this.segments = segments;
        fixedBin[0] = 113;
    }

    /**
     * Decodes one scan.
     *
     * @param scan the scan to decode, with its components and spectral band
     * @param dcLower the lower DC conditioning bound of each scan component
     * @param dcUpper the upper DC conditioning bound of each scan component
     * @param acThreshold the AC conditioning threshold of each scan component
     * @param restartInterval the number of MCUs per restart interval, or zero
     * @param target where decoded blocks go
     * @param progressive whether the frame is progressive
     * @return the number of MCUs decoded while data of the scan remained
     */
    long decode(JpegScan scan, int[] dcLower, int[] dcUpper, int[] acThreshold, int restartInterval,
                DecodeTarget target, boolean progressive) {
        var components = scan.components.length;
        this.dcSlots = scan.dcSelectors;
        this.acSlots = scan.acSelectors;
        this.dcLower = dcLower;
        this.dcUpper = dcUpper;
        this.acThreshold = acThreshold;
        this.differential = target.differential;
        var usesDc = !progressive || (scan.ss == 0 && scan.ah == 0);
        var usesAc = !progressive || scan.ss != 0;
        for (var ci = 0; ci < components; ci++) {
            if (usesDc) {
                if (dcSlots[ci] > 15) {
                    throw new MediaException.Malformed("invalid arithmetic DC table " + dcSlots[ci]);
                }
                Arrays.fill(dcStats[dcSlots[ci]], (byte) 0);
            }
            if (usesAc) {
                if (acSlots[ci] > 15) {
                    throw new MediaException.Malformed("invalid arithmetic AC table " + acSlots[ci]);
                }
                Arrays.fill(acStats[acSlots[ci]], (byte) 0);
            }
            lastDc[ci] = 0;
            dcContext[ci] = 0;
        }
        data = segments.data();
        segment = 0;
        startSegment(segments.start(0), segments.end(0));
        nextRestart = 0;
        var pixels = target.pixels() && !progressive;
        int[][] quant = null;
        if (pixels) {
            quant = new int[components][64];
            for (var ci = 0; ci < components; ci++) {
                var zigzag = target.zigzagQuant[scan.components[ci].index];
                for (var k = 0; k < 64; k++) {
                    quant[ci][ZIGZAG[k]] = zigzag[k];
                }
            }
        }
        var left = restartInterval;
        var decoded = 0L;
        for (var mcuY = 0; mcuY < scan.mcusHigh; mcuY++) {
            for (var mcuX = 0; mcuX < scan.mcusWide; mcuX++) {
                if (restartInterval != 0) {
                    if (left == 0) {
                        restart(scan, progressive);
                        left = restartInterval;
                    }
                    left--;
                }
                if (position < segmentEnd && ct != -1) {
                    decoded++;
                }
                if (!progressive) {
                    sequential(scan, mcuX, mcuY, target, quant);
                } else if (scan.ss == 0) {
                    if (scan.ah == 0) {
                        dcFirst(scan, mcuX, mcuY, target);
                    } else {
                        dcRefine(scan, mcuX, mcuY, target);
                    }
                } else if (scan.ah == 0) {
                    acFirst(scan, mcuX, mcuY, target);
                } else {
                    acRefine(scan, mcuX, mcuY, target);
                }
            }
        }
        return decoded;
    }

    /**
     * Starts reading a segment and resets the arithmetic decoder registers.
     *
     * @param start the index of the first byte
     * @param end the index one past the last byte
     */
    private void startSegment(int start, int end) {
        position = start;
        segmentEnd = end;
        c = 0;
        a = 0;
        ct = -16;
    }

    /**
     * Processes a restart boundary: resynchronizes on the next restart marker and resets the
     * statistics, predictions and registers.
     *
     * @param scan the scan being decoded, whose components are reset
     * @param progressive whether the frame is progressive
     */
    private void restart(JpegScan scan, boolean progressive) {
        var next = segments.resync(segment, nextRestart);
        if (next >= 0) {
            segment = next;
            startSegment(segments.start(next), segments.end(next));
        } else {
            startSegment(0, 0);
        }
        nextRestart = (nextRestart + 1) & 7;
        for (var ci = 0; ci < scan.components.length; ci++) {
            if (!progressive || (scan.ss == 0 && scan.ah == 0)) {
                Arrays.fill(dcStats[dcSlots[ci]], (byte) 0);
                lastDc[ci] = 0;
                dcContext[ci] = 0;
            }
            if (!progressive || scan.ss != 0) {
                Arrays.fill(acStats[acSlots[ci]], (byte) 0);
            }
        }
    }

    /**
     * Decodes one binary decision (T.81 D.2).
     *
     * @param stats the statistics array
     * @param index the index of the statistics bin
     * @return the decision, 0 or 1
     */
    private int decision(byte[] stats, int index) {
        while (a < 0x8000L) {
            if (--ct < 0) {
                var value = position < segmentEnd ? data[position] & 0xFF : 0;
                position++;
                c = (c << 8) | value;
                if ((ct += 8) < 0) {
                    if (++ct == 0) {
                        a = 0x8000L;
                    }
                }
            }
            a <<= 1;
        }
        var sv = stats[index] & 0xFF;
        var state = STATES[sv & 0x7F];
        var nextLps = state & 0xFF;
        var nextMps = (state >>> 8) & 0xFF;
        long qe = state >>> 16;
        var temp = a - qe;
        a = temp;
        temp <<= ct;
        if (c >= temp) {
            c -= temp;
            if (a < qe) {
                a = qe;
                stats[index] = (byte) ((sv & 0x80) ^ nextMps);
            } else {
                a = qe;
                stats[index] = (byte) ((sv & 0x80) ^ nextLps);
                sv ^= 0x80;
            }
        } else if (a < 0x8000L) {
            if (a < qe) {
                stats[index] = (byte) ((sv & 0x80) ^ nextLps);
                sv ^= 0x80;
            } else {
                stats[index] = (byte) ((sv & 0x80) ^ nextMps);
            }
        }
        return sv >> 7;
    }

    /**
     * Decodes one DC difference and updates the prediction and context of a scan component.
     *
     * @param ci the scan component index
     * @return {@code false} when a decoding error occurred
     */
    private boolean decodeDc(int ci) {
        if (differential) {
            lastDc[ci] = 0;
        }
        var stats = dcStats[dcSlots[ci]];
        var st = dcContext[ci];
        if (decision(stats, st) == 0) {
            dcContext[ci] = 0;
            return true;
        }
        var sign = decision(stats, st + 1);
        st += 2 + sign;
        var m = decision(stats, st);
        if (m != 0) {
            st = 20;
            while (decision(stats, st) != 0) {
                if ((m <<= 1) == 0x8000) {
                    ct = -1;
                    return false;
                }
                st++;
            }
        }
        if (m < ((1 << dcLower[ci]) >> 1)) {
            dcContext[ci] = 0;
        } else if (m > ((1 << dcUpper[ci]) >> 1)) {
            dcContext[ci] = 12 + sign * 4;
        } else {
            dcContext[ci] = 4 + sign * 4;
        }
        var v = m;
        st += 14;
        while ((m >>= 1) != 0) {
            if (decision(stats, st) != 0) {
                v |= m;
            }
        }
        v += 1;
        if (sign != 0) {
            v = -v;
        }
        lastDc[ci] = (lastDc[ci] + v) & 0xFFFF;
        return true;
    }

    /**
     * Starts decoding a lossless scan whose segments were just loaded, resetting the statistics of
     * its tables.
     *
     * @param slots the conditioning table slot of each scan component, from 0 to 15
     */
    void startLossless(int[] slots) {
        if (losslessStats == null) {
            losslessStats = new byte[16][LOSSLESS_BINS];
        }
        losslessSlots = slots;
        for (var slot : slots) {
            Arrays.fill(losslessStats[slot], (byte) 0);
        }
        data = segments.data();
        segment = 0;
        startSegment(segments.start(0), segments.end(0));
        nextRestart = 0;
    }

    /**
     * Processes a restart boundary of a lossless scan: resynchronizes on the next restart marker
     * and resets the statistics and registers.
     */
    void restartLossless() {
        var next = segments.resync(segment, nextRestart);
        if (next >= 0) {
            segment = next;
            startSegment(segments.start(next), segments.end(next));
        } else {
            startSegment(0, 0);
        }
        nextRestart = (nextRestart + 1) & 7;
        for (var slot : losslessSlots) {
            Arrays.fill(losslessStats[slot], (byte) 0);
        }
    }

    /**
     * Decodes one difference of a lossless scan with the two-dimensional statistical model of
     * T.81 H.1.2.3: the decision tree of the DC differences with the context {@code S0} of the
     * neighboring differences and the magnitude context {@code X1} of the difference above.
     *
     * <p>After a decoding error (a magnitude category beyond 15), every difference up to the next
     * restart boundary decodes as zero.
     *
     * @param slot the conditioning table slot of the component
     * @param s0 the context of the zero decision, {@code L_Context(Da, Db)}, from 0 to 96
     * @param x1 the context of the first magnitude decision, 100 or 129
     * @return the difference, from -32768 to 32768
     */
    int losslessDifference(int slot, int s0, int x1) {
        if (ct == -1) {
            return 0;
        }
        var stats = losslessStats[slot];
        if (decision(stats, s0) == 0) {
            return 0;
        }
        var sign = decision(stats, s0 + 1);
        var st = s0 + 2 + sign;
        var m = decision(stats, st);
        if (m != 0) {
            st = x1;
            while (decision(stats, st) != 0) {
                if ((m <<= 1) == 0x8000) {
                    ct = -1;
                    return 0;
                }
                st++;
            }
        }
        var v = m;
        st += 14;
        while ((m >>= 1) != 0) {
            if (decision(stats, st) != 0) {
                v |= m;
            }
        }
        v += 1;
        return sign != 0 ? -v : v;
    }

    /**
     * Decodes the AC coefficients {@code from..to} of one block (T.81 Figure F.20).
     *
     * @param ci the scan component index
     * @param coef the destination coefficients
     * @param offset the index of the block's first coefficient
     * @param from the first zigzag index
     * @param to the last zigzag index
     * @param al the successive-approximation shift applied to each value
     */
    private void decodeAc(int ci, short[] coef, int offset, int from, int to, int al) {
        var stats = acStats[acSlots[ci]];
        var threshold = acThreshold[ci];
        for (var k = from; k <= to; k++) {
            var st = 3 * (k - 1);
            if (decision(stats, st) != 0) {
                break;
            }
            while (decision(stats, st + 1) == 0) {
                st += 3;
                k++;
                if (k > to) {
                    ct = -1;
                    return;
                }
            }
            var sign = decision(fixedBin, 0);
            st += 2;
            var m = decision(stats, st);
            if (m != 0) {
                if (decision(stats, st) != 0) {
                    m <<= 1;
                    st = k <= threshold ? 189 : 217;
                    while (decision(stats, st) != 0) {
                        if ((m <<= 1) == 0x8000) {
                            ct = -1;
                            return;
                        }
                        st++;
                    }
                }
            }
            var v = m;
            st += 14;
            while ((m >>= 1) != 0) {
                if (decision(stats, st) != 0) {
                    v |= m;
                }
            }
            v += 1;
            if (sign != 0) {
                v = -v;
            }
            coef[offset + ZIGZAG[k]] = (short) (v << al);
        }
    }

    /**
     * Decodes one MCU of a sequential scan.
     *
     * @param scan the scan the MCU belongs to
     * @param mcuX the MCU column
     * @param mcuY the MCU row
     * @param target where decoded blocks go
     * @param quant the natural-order dequantization table of each scan component in pixel mode,
     * or {@code null} in coefficient mode
     */
    private void sequential(JpegScan scan, int mcuX, int mcuY, DecodeTarget target, int[][] quant) {
        var failed = ct == -1;
        for (var b = 0; b < scan.blocksPerMcu; b++) {
            var ci = scan.blockComponent[b];
            var component = scan.components[ci];
            var row = scan.blockRow(mcuY, b);
            var column = scan.blockColumn(mcuX, b);
            short[] coef;
            int offset;
            if (quant != null) {
                coef = scratch;
                offset = 0;
                Arrays.fill(scratch, (short) 0);
            } else {
                coef = target.coefficients[component.index];
                offset = (row * component.paddedBlocksWide + column) * 64;
            }
            if (!failed) {
                if (decodeDc(ci)) {
                    coef[offset] = (short) lastDc[ci];
                    decodeAc(ci, coef, offset, 1, 63, 0);
                }
                failed = ct == -1;
            }
            if (quant != null) {
                var size = 8 >> target.scaleShift;
                var stride = target.strides[component.index];
                JpegReader.transformBlock(scratch, 0, quant[ci], block, target.planes[component.index],
                        row * size * stride + column * size, stride, target.scaleShift);
            }
        }
    }

    /**
     * Decodes one MCU of the first scan of a progressive DC band.
     *
     * @param scan the scan the MCU belongs to
     * @param mcuX the MCU column
     * @param mcuY the MCU row
     * @param target where decoded blocks go
     */
    private void dcFirst(JpegScan scan, int mcuX, int mcuY, DecodeTarget target) {
        if (ct == -1) {
            return;
        }
        for (var b = 0; b < scan.blocksPerMcu; b++) {
            var ci = scan.blockComponent[b];
            var component = scan.components[ci];
            if (!decodeDc(ci)) {
                return;
            }
            var offset = (scan.blockRow(mcuY, b) * component.paddedBlocksWide + scan.blockColumn(mcuX, b)) * 64;
            target.coefficients[component.index][offset] = (short) (lastDc[ci] << scan.al);
        }
    }

    /**
     * Decodes one MCU of a refinement scan of a progressive DC band.
     *
     * @param scan the scan the MCU belongs to
     * @param mcuX the MCU column
     * @param mcuY the MCU row
     * @param target where decoded blocks go
     */
    private void dcRefine(JpegScan scan, int mcuX, int mcuY, DecodeTarget target) {
        var p1 = 1 << scan.al;
        for (var b = 0; b < scan.blocksPerMcu; b++) {
            var ci = scan.blockComponent[b];
            var component = scan.components[ci];
            if (decision(fixedBin, 0) != 0) {
                var offset = (scan.blockRow(mcuY, b) * component.paddedBlocksWide + scan.blockColumn(mcuX, b)) * 64;
                var coef = target.coefficients[component.index];
                coef[offset] = (short) (coef[offset] | p1);
            }
        }
    }

    /**
     * Decodes one block of the first scan of a progressive AC band.
     *
     * @param scan the scan, which has a single component
     * @param mcuX the block column
     * @param mcuY the block row
     * @param target where decoded blocks go
     */
    private void acFirst(JpegScan scan, int mcuX, int mcuY, DecodeTarget target) {
        if (ct == -1) {
            return;
        }
        var component = scan.components[0];
        var offset = (mcuY * component.paddedBlocksWide + mcuX) * 64;
        decodeAc(0, target.coefficients[component.index], offset, scan.ss, scan.se, scan.al);
    }

    /**
     * Decodes one block of a refinement scan of a progressive AC band.
     *
     * @param scan the scan, which has a single component
     * @param mcuX the block column
     * @param mcuY the block row
     * @param target where decoded blocks go
     */
    private void acRefine(JpegScan scan, int mcuX, int mcuY, DecodeTarget target) {
        if (ct == -1) {
            return;
        }
        var component = scan.components[0];
        var coef = target.coefficients[component.index];
        var offset = (mcuY * component.paddedBlocksWide + mcuX) * 64;
        var stats = acStats[acSlots[0]];
        var p1 = 1 << scan.al;
        var m1 = -1 << scan.al;
        var se = scan.se;
        var kex = se;
        for (; kex > 0; kex--) {
            if (coef[offset + ZIGZAG[kex]] != 0) {
                break;
            }
        }
        for (var k = scan.ss; k <= se; k++) {
            var st = 3 * (k - 1);
            if (k > kex && decision(stats, st) != 0) {
                break;
            }
            while (true) {
                var index = offset + ZIGZAG[k];
                var current = coef[index];
                if (current != 0) {
                    if (decision(stats, st + 2) != 0) {
                        coef[index] = (short) (current < 0 ? current + m1 : current + p1);
                    }
                    break;
                }
                if (decision(stats, st + 1) != 0) {
                    coef[index] = (short) (decision(fixedBin, 0) != 0 ? m1 : p1);
                    break;
                }
                st += 3;
                k++;
                if (k > se) {
                    ct = -1;
                    return;
                }
            }
        }
    }
}
