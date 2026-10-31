package com.github.auties00.smalt.audio.aac.internal.ep;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Builds error protected frames ({@code ep_frame}, ISO/IEC 14496-3 subclause 1.8) for the tests: the encoder side of
 * {@link ErrorProtectionDecoder}, written from the specification. Re-encoding the decoded classes of a conformance
 * stream reproduces its frames, which validates it; the tests then use it to build streams with the parts of the tool
 * the conformance suite does not exercise and to damage them where the codes must correct.
 */
public final class EpFrameEncoder {
    static final int[] IN_BAND_RATES = {0, 3, 4, 6, 8, 12, 16, 24};

    static final int[] IN_BAND_CRCS = {0, 6, 8, 10, 12, 14, 16, 32};

    private EpFrameEncoder() {
    }

    /** One coded unit: a class of one frame, or of every concatenated frame. */
    private record Unit(ErrorProtectionConfig.ClassSpec spec, byte[] bits, int length, int rate, int crc,
                        boolean continues) {
    }

    /**
     * Encodes the classes of one EP frame.
     *
     * @param config the configuration
     * @param choice the transmitted pre-defined set
     * @param classes the bits of each output class of each concatenated frame, one bit per byte; absent optional
     * classes are ignored
     * @param rateCodes the in-band {@code class_code_rate} of each output class whose rate is escaped
     * @param crcCodes the in-band {@code class_crc_count} of each output class whose CRC length is escaped
     * @return the frame bytes
     */
    public static byte[] encode(ErrorProtectionConfig config, long choice, byte[][][] classes, int[] rateCodes, int[] crcCodes) {
        var specs = config.classes(config.originalSet(choice));
        var order = new int[specs.length];
        var chained = new boolean[specs.length];
        var positions = config.frameOrder(choice, order, chained);
        var frames = config.concatenatedFrames();
        var units = new ArrayList<Unit>();
        var attributes = new BitList();
        for (var j = 0; j < positions; j++) {
            var c = order[j];
            var spec = specs[c];
            var rate = spec.rateEscape() ? IN_BAND_RATES[rateCodes[c]] : spec.rate();
            var crc = spec.crcEscape() ? IN_BAND_CRCS[crcCodes[c]] : spec.crcLength();
            var length = classes[0][c].length;
            if (spec.lengthEscape() && spec.lengthBits() > 0) {
                attributes.add(length, spec.lengthBits());
            }
            if (spec.rateEscape()) {
                attributes.add(rateCodes[c], 3);
            }
            if (spec.crcEscape()) {
                attributes.add(crcCodes[c], 3);
            }
            if (frames == 1) {
                units.add(new Unit(spec, classes[0][c], length, rate, length == 0 ? 0 : crc, chained[j]));
            } else if (spec.concatenate()) {
                var joined = new byte[length * frames];
                for (var f = 0; f < frames; f++) {
                    System.arraycopy(classes[f][c], 0, joined, f * length, length);
                }
                units.add(new Unit(spec, joined, joined.length, rate, joined.length == 0 ? 0 : crc, chained[j]));
            } else {
                for (var f = 0; f < frames; f++) {
                    units.add(new Unit(spec, classes[f][c], length, rate, length == 0 ? 0 : crc,
                            f < frames - 1 || chained[j]));
                }
            }
        }
        var coded = encodeUnits(units);
        var heads = new ArrayList<Integer>();
        for (var u = 0; u < units.size(); u++) {
            if (coded.get(u) != null) {
                heads.add(u);
            }
        }
        var choiceBits = config.choiceBits();
        var choiceHeader = encodeHeader(config, bits(choice, choiceBits), choiceBits);
        var attributeBits = attributes.size() + (config.bitStuffing() ? 3 : 0);
        var payload = 0;
        for (var u : heads) {
            payload += coded.get(u).length;
        }
        var headerLength = choiceHeader.length + codedHeaderLength(config, attributeBits);
        var stuffing = config.bitStuffing() ? (8 - (headerLength + payload) % 8) % 8 : 0;
        if (config.bitStuffing()) {
            attributes.add(stuffing, 3);
        }
        var attributeHeader = encodeHeader(config, attributes.toArray(), attributeBits);
        byte[] out;
        if (config.interleaveType() == 0) {
            var all = new BitList();
            all.add(choiceHeader);
            all.add(attributeHeader);
            for (var u : heads) {
                all.add(coded.get(u));
            }
            all.add(new byte[stuffing]);
            out = all.toArray();
        } else {
            var y = new BitList();
            for (var u : heads) {
                if (units.get(u).spec().interleaveSwitch() == 3) {
                    y.add(coded.get(u));
                }
            }
            var buffer = y.toArray();
            for (var i = heads.size() - 1; i >= 0; i--) {
                var u = heads.get(i);
                var unit = units.get(u);
                var mode = unit.spec().interleaveSwitch();
                if (mode != 1 && mode != 2) {
                    continue;
                }
                var x = coded.get(u);
                var joined = new byte[x.length + buffer.length];
                if (unit.spec().fecType() == 0) {
                    Interleaver.join(x, 0, x.length, buffer, 0, buffer.length, mode == 1 ? Math.max(1, x.length) : 28,
                            joined, 0);
                } else {
                    var information = groupInformation(units, u);
                    Interleaver.joinBytes(x, 0, information, x.length - information, buffer, 0, buffer.length, joined,
                            0);
                }
                buffer = joined;
            }
            var tail = new BitList();
            tail.add(buffer);
            for (var u : heads) {
                if (units.get(u).spec().interleaveSwitch() == 0) {
                    tail.add(coded.get(u));
                }
            }
            tail.add(new byte[stuffing]);
            buffer = tail.toArray();
            buffer = join(attributeHeader, buffer, width(attributeBits, attributeHeader.length));
            out = join(choiceHeader, buffer, width(choiceBits, choiceHeader.length));
        }
        return pack(out);
    }

    private static byte[] join(byte[] x, byte[] y, int width) {
        var out = new byte[x.length + y.length];
        Interleaver.join(x, 0, x.length, y, 0, y.length, width, out, 0);
        return out;
    }

    private static int width(int bits, int coded) {
        return bits <= 2 ? 3 : bits <= 16 ? coded : 28;
    }

    private static int groupInformation(List<Unit> units, int first) {
        var information = 0;
        for (var u = first; ; u++) {
            information += units.get(u).length() + units.get(u).crc();
            if (!continues(units, u)) {
                return information;
            }
        }
    }

    /** Encodes every unit; the entry of a unit a Reed-Solomon group continues into is null. */
    private static List<byte[]> encodeUnits(List<Unit> units) {
        var out = new ArrayList<byte[]>();
        var periodRates = new int[1 << 16];
        var step = 0;
        var state = 0;
        for (var u = 0; u < units.size(); u++) {
            var unit = units.get(u);
            if (unit.spec().fecType() == 0) {
                var data = withCrc(unit);
                var tail = unit.spec().termination() && unit.length() > 0 ? 4 : 0;
                assign(periodRates, step, data.length + tail, unit.rate());
                var bits = new BitList();
                for (var s = 0; s < data.length + tail; s++, step++) {
                    var next = Srcpc.step(state, s < data.length ? data[s] : -1);
                    bits.add(new byte[]{(byte) ((next >>> 3) & 1)});
                    for (var j = 0; j < 3; j++) {
                        if (Srcpc.sent(periodRates[step >>> 3], j, step)) {
                            bits.add(new byte[]{(byte) ((next >>> (2 - j)) & 1)});
                        }
                    }
                    state = next >>> 4;
                }
                out.add(bits.toArray());
            } else {
                var data = new BitList();
                var first = u;
                while (true) {
                    data.add(withCrc(units.get(u)));
                    if (!continues(units, u)) {
                        break;
                    }
                    u++;
                }
                var information = data.toArray();
                var k = units.get(first).rate();
                var all = new BitList();
                all.add(information);
                if (k > 0) {
                    var symbolsTotal = (information.length + 7) / 8;
                    var part = 255 - 2 * k;
                    for (var start = 0; start < symbolsTotal; start += part) {
                        var count = Math.min(part, symbolsTotal - start);
                        var symbols = new int[count + 2 * k];
                        for (var i = 0; i < count; i++) {
                            for (var b = 0; b < 8; b++) {
                                var position = (start + i) * 8 + b;
                                if (position < information.length && information[position] != 0) {
                                    symbols[i] |= 1 << b;
                                }
                            }
                        }
                        ReedSolomon.encode(symbols, 0, count, k);
                        var parity = new byte[2 * k * 8];
                        for (var i = 0; i < 2 * k; i++) {
                            for (var b = 0; b < 8; b++) {
                                parity[i * 8 + b] = (byte) ((symbols[count + i] >>> b) & 1);
                            }
                        }
                        all.add(parity);
                    }
                }
                out.add(all.toArray());
                for (var g = first + 1; g <= u; g++) {
                    out.add(null);
                }
            }
        }
        return out;
    }

    /** Returns whether the Reed-Solomon code of a unit continues into the next unit. */
    private static boolean continues(List<Unit> units, int u) {
        return units.get(u).spec().fecType() == 2 && units.get(u).continues() && u + 1 < units.size()
                && units.get(u + 1).spec().fecType() != 0;
    }

    private static void assign(int[] periodRates, int from, int span, int rate) {
        for (var period = (from + 7) >>> 3; period * 8 < from + span; period++) {
            periodRates[period] = rate;
        }
    }

    private static byte[] withCrc(Unit unit) {
        var data = Arrays.copyOf(unit.bits(), unit.length() + unit.crc());
        EpCrc.write(data, 0, unit.length(), unit.crc(), data, unit.length());
        return data;
    }

    static int codedHeaderLength(ErrorProtectionConfig config, int bits) {
        if (bits == 0) {
            return 0;
        }
        if (bits <= 2) {
            return 3 * bits;
        }
        if (bits <= 16) {
            return bits + HeaderCode.forBits(bits).parityBits();
        }
        var rate = config.headerProtection() ? config.headerRate() : 8;
        var crc = config.headerProtection() ? config.headerCrcLength() : 4;
        var count = 0;
        for (var t = 0; t < bits + crc + 4; t++) {
            count += Srcpc.width(rate, t);
        }
        return count;
    }

    private static byte[] encodeHeader(ErrorProtectionConfig config, byte[] info, int bits) {
        if (bits == 0) {
            return new byte[0];
        }
        if (bits <= 2) {
            var out = new byte[3 * bits];
            for (var i = 0; i < bits; i++) {
                Arrays.fill(out, 3 * i, 3 * i + 3, info[i]);
            }
            return out;
        }
        if (bits <= 16) {
            var code = HeaderCode.forBits(bits);
            var out = Arrays.copyOf(info, bits + code.parityBits());
            code.encode(info, 0, bits, out, bits);
            return out;
        }
        var rate = config.headerProtection() ? config.headerRate() : 8;
        var crc = config.headerProtection() ? config.headerCrcLength() : 4;
        var data = Arrays.copyOf(info, bits + crc);
        EpCrc.write(data, 0, bits, crc, data, bits);
        var out = new BitList();
        var state = 0;
        for (var t = 0; t < bits + crc + 4; t++) {
            var next = Srcpc.step(state, t < bits + crc ? data[t] : -1);
            out.add(new byte[]{(byte) ((next >>> 3) & 1)});
            for (var j = 0; j < 3; j++) {
                if (Srcpc.sent(rate, j, t)) {
                    out.add(new byte[]{(byte) ((next >>> (2 - j)) & 1)});
                }
            }
            state = next >>> 4;
        }
        return out.toArray();
    }

    static byte[] bits(long value, int count) {
        var out = new byte[count];
        for (var i = 0; i < count; i++) {
            out[i] = (byte) ((value >>> (count - 1 - i)) & 1);
        }
        return out;
    }

    public static byte[] unpack(byte[] data, int bits) {
        var out = new byte[bits];
        for (var i = 0; i < bits; i++) {
            out[i] = (byte) ((data[i >>> 3] >>> (7 - (i & 7))) & 1);
        }
        return out;
    }

    static byte[] pack(byte[] bits) {
        var out = new byte[(bits.length + 7) / 8];
        for (var i = 0; i < bits.length; i++) {
            if (bits[i] != 0) {
                out[i >>> 3] |= (byte) (0x80 >>> (i & 7));
            }
        }
        return out;
    }

    /** A growable list of bits, one per byte. */
    static final class BitList {
        private byte[] bits = new byte[64];
        private int size;

        void add(byte[] values) {
            ensure(size + values.length);
            System.arraycopy(values, 0, bits, size, values.length);
            size += values.length;
        }

        void add(long value, int count) {
            add(EpFrameEncoder.bits(value, count));
        }

        int size() {
            return size;
        }

        byte[] toArray() {
            return Arrays.copyOf(bits, size);
        }

        private void ensure(int capacity) {
            if (bits.length < capacity) {
                bits = Arrays.copyOf(bits, Math.max(capacity, bits.length * 2));
            }
        }
    }
}
