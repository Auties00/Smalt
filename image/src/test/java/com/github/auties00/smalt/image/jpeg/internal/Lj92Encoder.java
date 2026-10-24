package com.github.auties00.smalt.image.jpeg.internal;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.List;

/**
 * Encodes lossless JPEG (ITU-T T.81 processes 14 and 15) streams for the tests: any predictor, sampling factors,
 * restart interval and point transform, one or several scans, Huffman coding (by default with a fixed table in which
 * every difference category has a 5-bit code) or arithmetic coding.
 */
final class Lj92Encoder {
    private Lj92Encoder() {
    }

    static byte[] encode(int[] samples, int width, int height, int components, int precision, int predictor,
                                int restart, int transform) {
        var out = new ByteArrayOutputStream();
        marker(out, 0xD8);
        marker(out, 0xC3);
        u16(out, 8 + 3 * components);
        out.write(precision);
        u16(out, height);
        u16(out, width);
        out.write(components);
        for (var c = 0; c < components; c++) {
            out.write(c + 1);
            out.write(0x11);
            out.write(0);
        }
        marker(out, 0xC4);
        u16(out, 2 + 17 + 17);
        out.write(0);
        for (var length = 1; length <= 16; length++) {
            out.write(length == 5 ? 17 : 0);
        }
        for (var value = 0; value <= 16; value++) {
            out.write(value);
        }
        if (restart > 0) {
            marker(out, 0xDD);
            u16(out, 4);
            u16(out, restart);
        }
        marker(out, 0xDA);
        u16(out, 6 + 2 * components);
        out.write(components);
        for (var c = 0; c < components; c++) {
            out.write(c + 1);
            out.write(0);
        }
        out.write(predictor);
        out.write(0);
        out.write(transform);
        var bits = new Bits(out);
        var stride = width * components;
        var shifted = new int[samples.length];
        for (var i = 0; i < samples.length; i++) {
            shifted[i] = samples[i] >> transform;
        }
        var initial = 1 << (precision - transform - 1);
        var mcu = 0;
        var restartIndex = 0;
        var restartRow = -1;
        var restartColumn = -1;
        for (var row = 0; row < height; row++) {
            for (var column = 0; column < width; column++) {
                if (restart > 0 && mcu > 0 && mcu % restart == 0) {
                    bits.flush();
                    marker(out, 0xD0 + (restartIndex++ & 7));
                    restartRow = row;
                    restartColumn = column;
                }
                for (var c = 0; c < components; c++) {
                    var index = row * stride + column * components + c;
                    int prediction;
                    var fresh = row == restartRow && column == restartColumn;
                    if ((row == 0 && column == 0) || fresh) {
                        prediction = initial;
                    } else if (row == 0 || (row == restartRow && column > restartColumn)) {
                        prediction = shifted[index - components];
                    } else if (column == 0 || (row == restartRow + 1 && restartRow >= 0 && column == 0)) {
                        prediction = shifted[index - stride];
                    } else {
                        var a = shifted[index - components];
                        var b = shifted[index - stride];
                        var cc = shifted[index - stride - components];
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
                    var difference = (shifted[index] - prediction) & 0xFFFF;
                    if (difference >= 32768) {
                        difference -= 65536;
                    }
                    if (difference == -32768) {
                        bits.write(16, 5);
                        continue;
                    }
                    var magnitude = Math.abs(difference);
                    var category = magnitude == 0 ? 0 : 32 - Integer.numberOfLeadingZeros(magnitude);
                    bits.write(category, 5);
                    if (category > 0) {
                        var value = difference >= 0 ? difference : difference + (1 << category) - 1;
                        bits.write(value, category);
                    }
                }
                mcu++;
            }
        }
        bits.flush();
        marker(out, 0xD9);
        return out.toByteArray();
    }

    // A block-interleaved scan (T.81 H.1.2 with A.2.3 MCUs): each component's plane is extended to whole MCUs by
    // replicating its last column and row, and samples are predicted within their own component. The restart
    // interval counts MCUs and must span whole MCU rows.
    static byte[] encodeSampled(int[][] planes, int width, int height, int[][] factors, int precision, int predictor,
                                int restart, int transform) {
        var components = planes.length;
        var hMax = 1;
        var vMax = 1;
        for (var factor : factors) {
            hMax = Math.max(hMax, factor[0]);
            vMax = Math.max(vMax, factor[1]);
        }
        var out = new ByteArrayOutputStream();
        marker(out, 0xD8);
        marker(out, 0xC3);
        u16(out, 8 + 3 * components);
        out.write(precision);
        u16(out, height);
        u16(out, width);
        out.write(components);
        for (var c = 0; c < components; c++) {
            out.write(c + 1);
            out.write((factors[c][0] << 4) | factors[c][1]);
            out.write(0);
        }
        marker(out, 0xC4);
        u16(out, 2 + 17 + 17);
        out.write(0);
        for (var length = 1; length <= 16; length++) {
            out.write(length == 5 ? 17 : 0);
        }
        for (var value = 0; value <= 16; value++) {
            out.write(value);
        }
        var unitsWide = (width + hMax - 1) / hMax;
        var unitsHigh = (height + vMax - 1) / vMax;
        if (restart > 0) {
            marker(out, 0xDD);
            u16(out, 4);
            u16(out, restart);
        }
        marker(out, 0xDA);
        u16(out, 6 + 2 * components);
        out.write(components);
        for (var c = 0; c < components; c++) {
            out.write(c + 1);
            out.write(0);
        }
        out.write(predictor);
        out.write(0);
        out.write(transform);
        var padded = new int[components][];
        var paddedWidth = new int[components];
        for (var c = 0; c < components; c++) {
            var cw = (int) Math.ceil((double) width * factors[c][0] / hMax);
            var ch = (int) Math.ceil((double) height * factors[c][1] / vMax);
            paddedWidth[c] = unitsWide * factors[c][0];
            var ph = unitsHigh * factors[c][1];
            padded[c] = new int[paddedWidth[c] * ph];
            for (var y = 0; y < ph; y++) {
                for (var x = 0; x < paddedWidth[c]; x++) {
                    padded[c][y * paddedWidth[c] + x] = planes[c][Math.min(y, ch - 1) * cw + Math.min(x, cw - 1)] >> transform;
                }
            }
        }
        var bits = new Bits(out);
        var initial = 1 << (precision - transform - 1);
        var restartIndex = 0;
        var rowsPerRestart = restart / Math.max(1, unitsWide);
        for (var unitRow = 0; unitRow < unitsHigh; unitRow++) {
            var reset = unitRow == 0;
            if (restart > 0 && unitRow > 0 && unitRow % rowsPerRestart == 0) {
                bits.flush();
                marker(out, 0xD0 + (restartIndex++ & 7));
                reset = true;
            }
            for (var unit = 0; unit < unitsWide; unit++) {
                for (var c = 0; c < components; c++) {
                    var stride = paddedWidth[c];
                    var plane = padded[c];
                    for (var by = 0; by < factors[c][1]; by++) {
                        for (var bx = 0; bx < factors[c][0]; bx++) {
                            var x = unit * factors[c][0] + bx;
                            var y = unitRow * factors[c][1] + by;
                            var index = y * stride + x;
                            int prediction;
                            if (reset && by == 0) {
                                prediction = x == 0 ? initial : plane[index - 1];
                            } else if (x == 0) {
                                prediction = plane[index - stride];
                            } else {
                                var a = plane[index - 1];
                                var b = plane[index - stride];
                                var cc = plane[index - stride - 1];
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
                            writeDifference(bits, plane[index] - prediction);
                        }
                    }
                }
            }
        }
        bits.flush();
        marker(out, 0xD9);
        return out.toByteArray();
    }

    // One scan of encodeFrame: the frame indices of its components in scan order, the table selector of each, the
    // predictor, the point transform and the restart interval in MCUs (written in a DRI segment before the scan when
    // it differs from the one in force).
    record ScanSpec(int[] components, int[] slots, int predictor, int transform, int restart) {
        static ScanSpec of(int[] components, int predictor, int transform, int restart) {
            return new ScanSpec(components, new int[components.length], predictor, transform, restart);
        }
    }

    // The tables of encodeFrame: a DHT table (16 code counts, then the values) per slot, null for the slots it does
    // not define, the DAC conditioning byte ((U << 4) | L) per slot, null for none, and the component identifiers.
    record Tables(int[][] huffman, int[] conditioning, int[] ids) {
        static Tables defaults(int components) {
            var table = new int[16 + 17];
            table[4] = 17;
            for (var value = 0; value <= 16; value++) {
                table[16 + value] = value;
            }
            var ids = new int[components];
            for (var c = 0; c < components; c++) {
                ids[c] = c + 1;
            }
            return new Tables(new int[][]{table, table, table, table}, null, ids);
        }
    }

    static int componentWidth(int width, int[][] factors, int c) {
        var hMax = Arrays.stream(factors).mapToInt(f -> f[0]).max().orElseThrow();
        return (width * factors[c][0] + hMax - 1) / hMax;
    }

    static int componentHeight(int height, int[][] factors, int c) {
        var vMax = Arrays.stream(factors).mapToInt(f -> f[1]).max().orElseThrow();
        return (height * factors[c][1] + vMax - 1) / vMax;
    }

    static byte[] encodeFrame(int[][] planes, int width, int height, int[][] factors, int precision, boolean arithmetic,
                              List<ScanSpec> scans) {
        return encodeFrame(planes, width, height, factors, precision, arithmetic, scans, Tables.defaults(planes.length));
    }

    // Encodes a lossless frame (T.81 annex H) as the decoder reads it: each scan interleaves its components in MCUs of
    // h x v samples (A.2.3) or codes one component sample by sample over its own size (A.2.2); within a scan the first
    // line of the scan and of every restart interval, from the interval's first sample on, is predicted from the left,
    // and arithmetic coding conditions on the differences to the left and above (H.1.2.3), both zero at the start of
    // a line and the one above zero on the first line of an interval.
    static byte[] encodeFrame(int[][] planes, int width, int height, int[][] factors, int precision, boolean arithmetic,
                              List<ScanSpec> scans, Tables tables) {
        return encodeFrame(planes, width, height, factors, precision, arithmetic, scans, tables, false);
    }

    // A differential frame of hierarchical coding (SOF7, SOF15; T.81 J.1.3.2) codes its samples, the differences
    // modulo 2^16, without prediction; its scans take predictor 0.
    static byte[] encodeFrame(int[][] planes, int width, int height, int[][] factors, int precision, boolean arithmetic,
                              List<ScanSpec> scans, Tables tables, boolean differential) {
        var components = planes.length;
        var hMax = Arrays.stream(factors).mapToInt(f -> f[0]).max().orElseThrow();
        var vMax = Arrays.stream(factors).mapToInt(f -> f[1]).max().orElseThrow();
        var out = new ByteArrayOutputStream();
        marker(out, 0xD8);
        if (arithmetic && tables.conditioning() != null) {
            var count = 0;
            for (var value : tables.conditioning()) {
                count += value >= 0 ? 1 : 0;
            }
            marker(out, 0xCC);
            u16(out, 2 + 2 * count);
            for (var slot = 0; slot < tables.conditioning().length; slot++) {
                if (tables.conditioning()[slot] >= 0) {
                    out.write(slot);
                    out.write(tables.conditioning()[slot]);
                }
            }
        }
        marker(out, differential ? (arithmetic ? 0xCF : 0xC7) : (arithmetic ? 0xCB : 0xC3));
        u16(out, 8 + 3 * components);
        out.write(precision);
        u16(out, height);
        u16(out, width);
        out.write(components);
        for (var c = 0; c < components; c++) {
            out.write(tables.ids()[c]);
            out.write((factors[c][0] << 4) | factors[c][1]);
            out.write(0);
        }
        HuffmanCode[] codes = null;
        if (!arithmetic) {
            codes = new HuffmanCode[4];
            var length = 2;
            for (var table : tables.huffman()) {
                length += table == null ? 0 : 1 + table.length;
            }
            marker(out, 0xC4);
            u16(out, length);
            for (var slot = 0; slot < 4; slot++) {
                var table = tables.huffman()[slot];
                if (table != null) {
                    out.write(slot);
                    for (var value : table) {
                        out.write(value);
                    }
                    codes[slot] = new HuffmanCode(table);
                }
            }
        }
        var restartInForce = 0;
        for (var scan : scans) {
            if (scan.restart() != restartInForce) {
                marker(out, 0xDD);
                u16(out, 4);
                u16(out, scan.restart());
                restartInForce = scan.restart();
            }
            var order = scan.components();
            var count = order.length;
            marker(out, 0xDA);
            u16(out, 6 + 2 * count);
            out.write(count);
            for (var k = 0; k < count; k++) {
                out.write(tables.ids()[order[k]]);
                out.write(scan.slots()[k] << 4);
            }
            out.write(scan.predictor());
            out.write(0);
            out.write(scan.transform());
            encodeScan(out, planes, width, height, factors, hMax, vMax, precision, arithmetic, scan, tables, codes,
                    differential);
        }
        marker(out, 0xD9);
        return out.toByteArray();
    }

    private static void encodeScan(ByteArrayOutputStream out, int[][] planes, int width, int height, int[][] factors,
                                   int hMax, int vMax, int precision, boolean arithmetic, ScanSpec scan, Tables tables,
                                   HuffmanCode[] codes, boolean differential) {
        var order = scan.components();
        var count = order.length;
        var interleaved = count > 1;
        var unitsWide = interleaved ? (width + hMax - 1) / hMax : componentWidth(width, factors, order[0]);
        var unitsHigh = interleaved ? (height + vMax - 1) / vMax : componentHeight(height, factors, order[0]);
        var transform = scan.transform();
        var hs = new int[count];
        var vs = new int[count];
        var strides = new int[count];
        var padded = new int[count][];
        var classes = new int[count][];
        for (var k = 0; k < count; k++) {
            var c = order[k];
            hs[k] = interleaved ? factors[c][0] : 1;
            vs[k] = interleaved ? factors[c][1] : 1;
            var cw = componentWidth(width, factors, c);
            var ch = componentHeight(height, factors, c);
            strides[k] = unitsWide * hs[k];
            var rows = unitsHigh * vs[k];
            padded[k] = new int[strides[k] * rows];
            classes[k] = new int[strides[k] * rows];
            for (var y = 0; y < rows; y++) {
                for (var x = 0; x < strides[k]; x++) {
                    padded[k][y * strides[k] + x] = planes[c][Math.min(y, ch - 1) * cw + Math.min(x, cw - 1)] >> transform;
                }
            }
        }
        var initial = 1 << (precision - transform - 1);
        var bits = arithmetic ? null : new Bits(out);
        var coder = arithmetic ? new ArithmeticEncoder(out) : null;
        var restartIndex = 0;
        var restartRow = 0;
        var restartUnit = 0;
        var mcu = 0L;
        var predictor = scan.predictor();
        for (var unitRow = 0; unitRow < unitsHigh; unitRow++) {
            for (var unit = 0; unit < unitsWide; unit++) {
                if (scan.restart() > 0 && mcu > 0 && mcu % scan.restart() == 0) {
                    if (arithmetic) {
                        coder.finish();
                        marker(out, 0xD0 + (restartIndex++ & 7));
                        coder.reset();
                    } else {
                        bits.flush();
                        marker(out, 0xD0 + (restartIndex++ & 7));
                    }
                    restartRow = unitRow;
                    restartUnit = unit;
                }
                mcu++;
                for (var k = 0; k < count; k++) {
                    var stride = strides[k];
                    var plane = padded[k];
                    for (var by = 0; by < vs[k]; by++) {
                        for (var bx = 0; bx < hs[k]; bx++) {
                            var x = unit * hs[k] + bx;
                            var y = unitRow * vs[k] + by;
                            var index = y * stride + x;
                            var lineStart = restartUnit * hs[k];
                            var fresh = unitRow == restartRow && by == 0 && x >= lineStart;
                            int prediction;
                            if (differential) {
                                prediction = 0;
                            } else if (fresh) {
                                prediction = x == lineStart ? initial : plane[index - 1];
                            } else if (x == 0) {
                                prediction = plane[index - stride];
                            } else {
                                var a = plane[index - 1];
                                var b = plane[index - stride];
                                var cc = plane[index - stride - 1];
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
                            if (arithmetic) {
                                var left = x == 0 || (fresh && x == lineStart) ? 0 : classes[k][index - 1];
                                var up = fresh ? 0 : classes[k][index - stride];
                                var slot = scan.slots()[k];
                                var conditioning = tables.conditioning() == null || tables.conditioning()[slot] < 0
                                        ? 0x10 : tables.conditioning()[slot];
                                var difference = plane[index] - prediction;
                                if (differential) {
                                    difference = (short) difference;
                                }
                                classes[k][index] = coder.difference(slot, 20 * left + 4 * up, up >= 3 ? 129 : 100,
                                        difference, conditioning & 15, conditioning >> 4);
                            } else {
                                codes[scan.slots()[k]].write(bits, plane[index] - prediction);
                            }
                        }
                    }
                }
            }
        }
        if (arithmetic) {
            coder.finish();
        } else {
            bits.flush();
        }
    }

    // The code of each value of a DHT table, from its code counts (T.81 C.2).
    private static final class HuffmanCode {
        private final int[] codes = new int[17];
        private final int[] lengths = new int[17];

        HuffmanCode(int[] table) {
            var code = 0;
            var index = 16;
            for (var length = 1; length <= 16; length++) {
                for (var k = 0; k < table[length - 1]; k++) {
                    var value = table[index++];
                    codes[value] = code++;
                    lengths[value] = length;
                }
                code <<= 1;
            }
        }

        void write(Bits bits, int raw) {
            var difference = raw & 0xFFFF;
            if (difference >= 32768) {
                difference -= 65536;
            }
            if (difference == -32768) {
                bits.write(codes[16], lengths[16]);
                return;
            }
            var magnitude = Math.abs(difference);
            var category = magnitude == 0 ? 0 : 32 - Integer.numberOfLeadingZeros(magnitude);
            if (lengths[category] == 0) {
                throw new IllegalArgumentException("the table has no code for category " + category);
            }
            bits.write(codes[category], lengths[category]);
            if (category > 0) {
                var value = difference >= 0 ? difference : difference + (1 << category) - 1;
                bits.write(value, category);
            }
        }
    }

    // The binary arithmetic encoder of T.81 annex D with the register conventions and byte output of IJG libjpeg's
    // jcarith.c: the last byte is held back to absorb a carry, and pending 0x00 and 0xFF bytes are counted, so that
    // trailing zero bytes of a segment are never written (D.1.8).
    static final class ArithmeticEncoder {
        private final ByteArrayOutputStream out;
        private final byte[][] stats = new byte[16][158];
        private long c;
        private long a;
        private int ct;
        private int buffer;
        private int stacked;
        private int zeros;

        ArithmeticEncoder(ByteArrayOutputStream out) {
            this.out = out;
            reset();
        }

        void reset() {
            for (var table : stats) {
                Arrays.fill(table, (byte) 0);
            }
            c = 0;
            a = 0x10000L;
            ct = 11;
            buffer = -1;
            stacked = 0;
            zeros = 0;
        }

        // Codes one lossless difference in the decision tree of T.81 F.1.4.1 with the contexts of Table H.3 and
        // returns its conditioning class (0 zero, 1 small positive, 2 small negative, 3 large positive, 4 large
        // negative), classified from the magnitude category as F.1.4.4.1.2 bounds it.
        int difference(int slot, int s0, int x1, int raw, int lower, int upper) {
            var table = stats[slot];
            var v = raw & 0xFFFF;
            if (v >= 32768) {
                v -= 65536;
            }
            if (v == 0) {
                encode(table, s0, 0);
                return 0;
            }
            encode(table, s0, 1);
            int st;
            int category;
            if (v > 0) {
                encode(table, s0 + 1, 0);
                st = s0 + 2;
                category = 1;
            } else {
                v = -v;
                encode(table, s0 + 1, 1);
                st = s0 + 3;
                category = 2;
            }
            var m = 0;
            if ((v -= 1) != 0) {
                encode(table, st, 1);
                m = 1;
                var v2 = v;
                st = x1;
                while ((v2 >>= 1) != 0) {
                    encode(table, st, 1);
                    m <<= 1;
                    st++;
                }
            }
            encode(table, st, 0);
            if (m < ((1 << lower) >> 1)) {
                category = 0;
            } else if (m > ((1 << upper) >> 1)) {
                category += 2;
            }
            st += 14;
            while ((m >>= 1) != 0) {
                encode(table, st, (m & v) != 0 ? 1 : 0);
            }
            return category;
        }

        private void encode(byte[] table, int index, int value) {
            var sv = table[index] & 0xFF;
            var entry = ArithmeticScanDecoder.STATE_TABLE;
            var state = sv & 0x7F;
            long qe = entry[4 * state];
            var nextLps = entry[4 * state + 1] | (entry[4 * state + 3] << 7);
            var nextMps = entry[4 * state + 2];
            a -= qe;
            if (value != (sv >> 7)) {
                if (a >= qe) {
                    c += a;
                    a = qe;
                }
                table[index] = (byte) ((sv & 0x80) ^ nextLps);
            } else {
                if (a >= 0x8000L) {
                    return;
                }
                if (a < qe) {
                    c += a;
                    a = qe;
                }
                table[index] = (byte) ((sv & 0x80) ^ nextMps);
            }
            do {
                a <<= 1;
                c <<= 1;
                if (--ct == 0) {
                    byteOut();
                    c &= 0x7FFFFL;
                    ct += 8;
                }
            } while (a < 0x8000L);
        }

        private void byteOut() {
            var temp = (int) (c >> 19);
            if (temp > 0xFF) {
                if (buffer >= 0) {
                    emitZeros();
                    emit(buffer + 1);
                    if (buffer + 1 == 0xFF) {
                        emit(0);
                    }
                }
                zeros += stacked;
                stacked = 0;
                buffer = temp & 0xFF;
            } else if (temp == 0xFF) {
                stacked++;
            } else {
                flushBuffer();
                buffer = temp & 0xFF;
            }
        }

        private void flushBuffer() {
            if (buffer == 0) {
                zeros++;
            } else if (buffer >= 0) {
                emitZeros();
                emit(buffer);
            }
            if (stacked > 0) {
                emitZeros();
                do {
                    emit(0xFF);
                    emit(0);
                } while (--stacked > 0);
            }
        }

        private void emitZeros() {
            while (zeros > 0) {
                emit(0);
                zeros--;
            }
        }

        private void emit(int value) {
            out.write(value);
        }

        // Terminates the segment (T.81 D.1.8): the code value with the most trailing zero bits in the final interval,
        // then the bytes that are not zero.
        void finish() {
            var temp = (a - 1 + c) & 0xFFFF0000L;
            c = temp < c ? temp + 0x8000L : temp;
            c <<= ct;
            if ((c & 0xF8000000L) != 0) {
                if (buffer >= 0) {
                    emitZeros();
                    emit(buffer + 1);
                    if (buffer + 1 == 0xFF) {
                        emit(0);
                    }
                }
                zeros += stacked;
                stacked = 0;
            } else {
                flushBuffer();
            }
            if ((c & 0x7FFF800L) != 0) {
                emitZeros();
                var first = (int) (c >> 19) & 0xFF;
                emit(first);
                if (first == 0xFF) {
                    emit(0);
                }
                if ((c & 0x7F800L) != 0) {
                    var second = (int) (c >> 11) & 0xFF;
                    emit(second);
                    if (second == 0xFF) {
                        emit(0);
                    }
                }
            }
        }
    }

    private static void writeDifference(Bits bits, int raw) {
        var difference = raw & 0xFFFF;
        if (difference >= 32768) {
            difference -= 65536;
        }
        if (difference == -32768) {
            bits.write(16, 5);
            return;
        }
        var magnitude = Math.abs(difference);
        var category = magnitude == 0 ? 0 : 32 - Integer.numberOfLeadingZeros(magnitude);
        bits.write(category, 5);
        if (category > 0) {
            var value = difference >= 0 ? difference : difference + (1 << category) - 1;
            bits.write(value, category);
        }
    }

    private static void marker(ByteArrayOutputStream out, int marker) {
        out.write(0xFF);
        out.write(marker);
    }

    private static void u16(ByteArrayOutputStream out, int value) {
        out.write(value >> 8);
        out.write(value);
    }

    private static final class Bits {
        private final ByteArrayOutputStream out;
        private long buffer;
        private int count;

        Bits(ByteArrayOutputStream out) {
            this.out = out;
        }

        void write(int value, int bits) {
            buffer = (buffer << bits) | (value & ((1L << bits) - 1));
            count += bits;
            while (count >= 8) {
                var b = (int) (buffer >>> (count - 8)) & 0xFF;
                out.write(b);
                if (b == 0xFF) {
                    out.write(0);
                }
                count -= 8;
            }
        }

        void flush() {
            if (count > 0) {
                write((1 << (8 - count)) - 1, 8 - count);
            }
            buffer = 0;
            count = 0;
        }
    }
}
