package com.github.auties00.smalt.image.jp2.internal;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

/**
 * A block coder written for the tests from ISO/IEC 15444-1 annexes C and D and ISO/IEC 15444-2 annex P, independent of
 * the encoder's: the MQ coder of the C.2 flow charts, the three coding passes with the contexts of Tables D.1 to D.4,
 * and the selective arithmetic coding bypass of D.6 starting at the bit-plane the {@code SXcod} field of Table A.11bis
 * selects. It codes every bit-plane of a code-block losslessly with the style flags {@link ComponentCoding#BYPASS},
 * {@link ComponentCoding#RESET}, {@link ComponentCoding#TERMINATE_ALL}, {@link ComponentCoding#VERTICALLY_CAUSAL} and
 * {@link ComponentCoding#SEGMENTATION_SYMBOLS}, and fills the fields of an {@link EncodedBlock} the packet encoder
 * reads.
 */
final class Part2BlockCoder {
    /** The probability estimates of Table C.2: Qe, NMPS, NLPS and SWITCH of each state. */
    private static final int[][] STATES = {
            {0x5601, 1, 1, 1}, {0x3401, 2, 6, 0}, {0x1801, 3, 9, 0}, {0x0AC1, 4, 12, 0}, {0x0521, 5, 29, 0},
            {0x0221, 38, 33, 0}, {0x5601, 7, 6, 1}, {0x5401, 8, 14, 0}, {0x4801, 9, 14, 0}, {0x3801, 10, 14, 0},
            {0x3001, 11, 17, 0}, {0x2401, 12, 18, 0}, {0x1C01, 13, 20, 0}, {0x1601, 29, 21, 0}, {0x5601, 15, 14, 1},
            {0x5401, 16, 14, 0}, {0x5101, 17, 15, 0}, {0x4801, 18, 16, 0}, {0x3801, 19, 17, 0}, {0x3401, 20, 18, 0},
            {0x3001, 21, 19, 0}, {0x2801, 22, 19, 0}, {0x2401, 23, 20, 0}, {0x2201, 24, 21, 0}, {0x1C01, 25, 22, 0},
            {0x1801, 26, 23, 0}, {0x1601, 27, 24, 0}, {0x1401, 28, 25, 0}, {0x1201, 29, 26, 0}, {0x1101, 30, 27, 0},
            {0x0AC1, 31, 28, 0}, {0x09C1, 32, 29, 0}, {0x08A1, 33, 30, 0}, {0x0521, 34, 31, 0}, {0x0441, 35, 32, 0},
            {0x02A1, 36, 33, 0}, {0x0221, 37, 34, 0}, {0x0141, 38, 35, 0}, {0x0111, 39, 36, 0}, {0x0085, 40, 37, 0},
            {0x0049, 41, 38, 0}, {0x0025, 42, 39, 0}, {0x0015, 43, 40, 0}, {0x0009, 44, 41, 0}, {0x0005, 45, 42, 0},
            {0x0001, 45, 43, 0}, {0x5601, 46, 46, 0}};

    /** The contexts: 0 to 8 significance, 9 to 13 sign, 14 to 16 refinement, 17 run-length, 18 uniform. */
    private static final int SIGN = 9;
    private static final int REFINEMENT = 14;
    private static final int RUN = 17;
    private static final int UNIFORM = 18;

    private final int[] index = new int[19];
    private final int[] mps = new int[19];
    private int a;
    private int c;
    private int ct;
    private ByteArrayOutputStream bytes;
    private int pending;
    private boolean started;
    private int rawByte;
    private int rawBits;
    private boolean rawAfterFf;

    private int width;
    private int height;
    private int[] magnitude;
    private boolean[] negative;
    private boolean[] significant;
    private boolean[] visited;
    private boolean[] refined;
    private boolean causal;
    private int orientation;

    /**
     * Codes a code-block of quantization indices, row-major with {@code stride}, starting at {@code offset}, and
     * stores the result in {@code block}: its bit-plane count, passes, bytes, cumulative pass rates and terminations.
     */
    void encode(EncodedBlock block, int[] indices, int offset, int stride, int orientation, int style,
                int bypassPlanes) {
        width = (int) (block.x1 - block.x0);
        height = (int) (block.y1 - block.y0);
        this.orientation = orientation;
        causal = (style & ComponentCoding.VERTICALLY_CAUSAL) != 0;
        magnitude = new int[width * height];
        negative = new boolean[width * height];
        significant = new boolean[width * height];
        visited = new boolean[width * height];
        refined = new boolean[width * height];
        var max = 0;
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                var value = indices[offset + y * stride + x];
                magnitude[y * width + x] = Math.abs(value);
                negative[y * width + x] = value < 0;
                max = Math.max(max, Math.abs(value));
            }
        }
        var planes = 32 - Integer.numberOfLeadingZeros(max);
        block.bitPlanes = planes;
        var passCount = planes == 0 ? 0 : 3 * planes - 2;
        block.ensurePasses(Math.max(passCount, 1));
        var bypass = (style & ComponentCoding.BYPASS) != 0;
        var terminateAll = (style & ComponentCoding.TERMINATE_ALL) != 0;
        var out = new ByteArrayOutputStream();
        resetContexts();
        var segmentRaw = false;
        var open = false;
        for (var pass = 0; pass < passCount; pass++) {
            var type = pass == 0 ? 2 : (pass - 1) % 3;
            var plane = planes - 1 - (pass + 2) / 3;
            var raw = bypass && type < 2 && pass >= 3 * bypassPlanes - 2;
            if (!open) {
                segmentRaw = raw;
                bytes = new ByteArrayOutputStream();
                if (raw) {
                    rawByte = 0;
                    rawBits = 0;
                    rawAfterFf = false;
                } else {
                    initMq();
                }
                open = true;
            }
            switch (type) {
                case 0 -> significancePass(plane, raw);
                case 1 -> refinementPass(plane, raw);
                default -> {
                    cleanupPass(plane);
                    if ((style & ComponentCoding.SEGMENTATION_SYMBOLS) != 0) {
                        code(UNIFORM, 1);
                        code(UNIFORM, 0);
                        code(UNIFORM, 1);
                        code(UNIFORM, 0);
                    }
                }
            }
            if ((style & ComponentCoding.RESET) != 0) {
                resetContexts();
            }
            boolean terminate;
            if (pass == passCount - 1 || terminateAll) {
                terminate = true;
            } else if (bypass) {
                var next = pass + 1;
                var nextType = (next - 1) % 3;
                var nextRaw = nextType < 2 && next >= 3 * bypassPlanes - 2;
                terminate = nextRaw != raw;
            } else {
                terminate = false;
            }
            if (terminate) {
                var segment = segmentRaw ? flushRaw() : flushMq();
                out.writeBytes(segment);
                open = false;
            }
            block.passTerminated[pass] = terminate;
            block.passRate[pass] = out.size() + (open ? bytes.size() : 0);
        }
        block.totalPasses = passCount;
        block.data = out.toByteArray();
    }

    private void resetContexts() {
        Arrays.fill(index, 0);
        Arrays.fill(mps, 0);
        index[0] = 4;
        index[RUN] = 3;
        index[UNIFORM] = 46;
    }

    /** INITENC (C.2.8), with the byte before the codeword taken as 0. */
    private void initMq() {
        a = 0x8000;
        c = 0;
        ct = 12;
        pending = 0;
        started = false;
    }

    /** ENCODE, CODEMPS and CODELPS (C.2.2 to C.2.5). */
    private void code(int context, int bit) {
        var state = STATES[index[context]];
        var qe = state[0];
        if (bit == mps[context]) {
            a -= qe;
            if ((a & 0x8000) == 0) {
                if (a < qe) {
                    a = qe;
                } else {
                    c += qe;
                }
                index[context] = state[1];
                renormalize();
            } else {
                c += qe;
            }
        } else {
            a -= qe;
            if (a < qe) {
                c += qe;
            } else {
                a = qe;
            }
            if (state[3] == 1) {
                mps[context] = 1 - mps[context];
            }
            index[context] = state[2];
            renormalize();
        }
    }

    /** RENORME (C.2.6). */
    private void renormalize() {
        do {
            a <<= 1;
            c <<= 1;
            ct--;
            if (ct == 0) {
                byteOut();
            }
        } while ((a & 0x8000) == 0);
    }

    /** BYTEOUT (C.2.7); {@link #pending} holds the byte B at BP, written once BP moves past it. */
    private void byteOut() {
        if (started && pending == 0xFF) {
            emit();
            pending = c >>> 20;
            c &= 0xFFFFF;
            ct = 7;
        } else if (c < 0x8000000) {
            emit();
            pending = c >>> 19;
            c &= 0x7FFFF;
            ct = 8;
        } else {
            pending++;
            if (pending == 0xFF) {
                c &= 0x7FFFFFF;
                emit();
                pending = c >>> 20;
                c &= 0xFFFFF;
                ct = 7;
            } else {
                emit();
                pending = c >>> 19;
                c &= 0x7FFFF;
                ct = 8;
            }
        }
    }

    /** Writes the byte at BP, except the virtual byte before the codeword. */
    private void emit() {
        if (started) {
            bytes.write(pending);
        }
        started = true;
    }

    /** FLUSH and SETBITS (C.2.9), dropping a final 0xFF. */
    private byte[] flushMq() {
        var temp = c + a;
        c |= 0xFFFF;
        if (c >= temp) {
            c -= 0x8000;
        }
        c <<= ct;
        byteOut();
        c <<= ct;
        byteOut();
        if (started && pending != 0xFF) {
            bytes.write(pending);
        }
        return bytes.toByteArray();
    }

    /** Writes one raw bit of a bypass segment (D.6): a byte following 0xFF carries 7 bits. */
    private void raw(int bit) {
        var capacity = rawAfterFf ? 7 : 8;
        rawByte = (rawByte << 1) | bit;
        rawBits++;
        if (rawBits == capacity) {
            bytes.write(rawByte);
            rawAfterFf = rawByte == 0xFF;
            rawByte = 0;
            rawBits = 0;
        }
    }

    /** Pads a bypass segment to a byte with zero bits; a final 0xFF is dropped, as the decoder reads 1s past the end. */
    private byte[] flushRaw() {
        if (rawBits > 0) {
            var capacity = rawAfterFf ? 7 : 8;
            bytes.write(rawByte << (capacity - rawBits));
        }
        var result = bytes.toByteArray();
        if (result.length > 0 && (result[result.length - 1] & 0xFF) == 0xFF) {
            result = Arrays.copyOf(result, result.length - 1);
        }
        return result;
    }

    private boolean sig(int x, int y, int row0) {
        if (x < 0 || y < 0 || x >= width || y >= height) {
            return false;
        }
        if (causal && y >= row0 + 4) {
            return false;
        }
        return significant[y * width + x];
    }

    /** The significance context of Table D.1. */
    private int significanceContext(int x, int y, int row0) {
        var h = (sig(x - 1, y, row0) ? 1 : 0) + (sig(x + 1, y, row0) ? 1 : 0);
        var v = (sig(x, y - 1, row0) ? 1 : 0) + (sig(x, y + 1, row0) ? 1 : 0);
        var d = (sig(x - 1, y - 1, row0) ? 1 : 0) + (sig(x + 1, y - 1, row0) ? 1 : 0)
                + (sig(x - 1, y + 1, row0) ? 1 : 0) + (sig(x + 1, y + 1, row0) ? 1 : 0);
        if (orientation == 3) {
            var hv = h + v;
            if (d >= 3) {
                return 8;
            }
            if (d == 2) {
                return hv >= 1 ? 7 : 6;
            }
            if (d == 1) {
                return hv >= 2 ? 5 : hv == 1 ? 4 : 3;
            }
            return hv >= 2 ? 2 : hv;
        }
        if (orientation == 1) {
            var t = h;
            h = v;
            v = t;
        }
        if (h == 2) {
            return 8;
        }
        if (h == 1) {
            return v >= 1 ? 7 : d >= 1 ? 6 : 5;
        }
        if (v == 2) {
            return 4;
        }
        if (v == 1) {
            return 3;
        }
        return d >= 2 ? 2 : d;
    }

    private int contribution(int x, int y, int row0) {
        if (!sig(x, y, row0)) {
            return 0;
        }
        return negative[y * width + x] ? -1 : 1;
    }

    /** Codes a sign with the context and XOR bit of Tables D.2 and D.3. */
    private void sign(int x, int y, int row0, boolean raw) {
        var bit = negative[y * width + x] ? 1 : 0;
        if (raw) {
            raw(bit);
            return;
        }
        var h = Integer.signum(contribution(x - 1, y, row0) + contribution(x + 1, y, row0));
        var v = Integer.signum(contribution(x, y - 1, row0) + contribution(x, y + 1, row0));
        var xor = 0;
        if (h < 0 || (h == 0 && v < 0)) {
            xor = 1;
            h = -h;
            v = -v;
        }
        var context = h == 0 ? SIGN + v : SIGN + 3 + v;
        code(context, bit ^ xor);
    }

    private int bit(int i, int plane) {
        return (magnitude[i] >> plane) & 1;
    }

    private void significancePass(int plane, boolean raw) {
        for (var row0 = 0; row0 < height; row0 += 4) {
            for (var x = 0; x < width; x++) {
                for (var y = row0; y < Math.min(row0 + 4, height); y++) {
                    var i = y * width + x;
                    if (significant[i]) {
                        continue;
                    }
                    var context = significanceContext(x, y, row0);
                    if (context == 0) {
                        continue;
                    }
                    var b = bit(i, plane);
                    if (raw) {
                        raw(b);
                    } else {
                        code(context, b);
                    }
                    visited[i] = true;
                    if (b == 1) {
                        sign(x, y, row0, raw);
                        significant[i] = true;
                    }
                }
            }
        }
    }

    private void refinementPass(int plane, boolean raw) {
        for (var row0 = 0; row0 < height; row0 += 4) {
            for (var x = 0; x < width; x++) {
                for (var y = row0; y < Math.min(row0 + 4, height); y++) {
                    var i = y * width + x;
                    if (!significant[i] || visited[i]) {
                        continue;
                    }
                    var b = bit(i, plane);
                    if (raw) {
                        raw(b);
                    } else {
                        int context;
                        if (refined[i]) {
                            context = REFINEMENT + 2;
                        } else {
                            var any = false;
                            for (var dy = -1; dy <= 1 && !any; dy++) {
                                for (var dx = -1; dx <= 1; dx++) {
                                    if ((dx != 0 || dy != 0) && sig(x + dx, y + dy, row0)) {
                                        any = true;
                                        break;
                                    }
                                }
                            }
                            context = any ? REFINEMENT + 1 : REFINEMENT;
                        }
                        code(context, b);
                    }
                    refined[i] = true;
                }
            }
        }
    }

    private void cleanupPass(int plane) {
        for (var row0 = 0; row0 < height; row0 += 4) {
            for (var x = 0; x < width; x++) {
                var y = row0;
                var end = Math.min(row0 + 4, height);
                if (end - row0 == 4) {
                    var run = true;
                    for (var k = row0; k < end && run; k++) {
                        var i = k * width + x;
                        run = !significant[i] && !visited[i] && significanceContext(x, k, row0) == 0;
                    }
                    if (run) {
                        var first = -1;
                        for (var k = row0; k < end; k++) {
                            if (bit(k * width + x, plane) == 1) {
                                first = k;
                                break;
                            }
                        }
                        if (first < 0) {
                            code(RUN, 0);
                            continue;
                        }
                        code(RUN, 1);
                        code(UNIFORM, ((first - row0) >> 1) & 1);
                        code(UNIFORM, (first - row0) & 1);
                        sign(x, first, row0, false);
                        significant[first * width + x] = true;
                        y = first + 1;
                    }
                }
                for (; y < end; y++) {
                    var i = y * width + x;
                    if (significant[i] || visited[i]) {
                        continue;
                    }
                    var b = bit(i, plane);
                    code(significanceContext(x, y, row0), b);
                    if (b == 1) {
                        sign(x, y, row0, false);
                        significant[i] = true;
                    }
                }
            }
        }
        Arrays.fill(visited, false);
    }
}
