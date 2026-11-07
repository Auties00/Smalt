package com.github.auties00.smalt.video.h264.encoder;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/**
 * An independent model of the NAL hypothetical reference decoder of ITU-T H.264 Annex C, written from the
 * specification for the tests: it parses the HRD parameters of the sequence parameter set and the buffering
 * period and picture timing SEI messages of every access unit, and checks the stream against the coded picture
 * buffer with exact rational arithmetic (C.1.1 to C.1.2 timing, C.3 conformance: no underflow, no overflow, the
 * initial removal delay bounds of each buffering period) and the output timing (C.4: output after removal, in
 * display order at the frame rate).
 *
 * <p>Access units are delimited as 7.4.1.2.3 describes for the streams under test: a primary coded picture starts
 * with a slice whose {@code first_mb_in_slice} is 0, together with the SEI, parameter set, delimiter and prefix NAL
 * units right before it; filler data stays with the access unit it follows. The bits of an access unit are the
 * bytes of its NAL units and their start codes (a length prefix counts as a four-byte start code).
 */
final class HrdVerifier {
    private HrdVerifier() {
        throw new AssertionError();
    }

    /** Signals that a stream breaks the hypothetical reference decoder it signals. */
    static final class Violation extends AssertionError {
        Violation(String message) {
            super(message);
        }
    }

    /** The values of the stream's HRD parameters and the results of the check. */
    record Report(long bitRate, long cpbSize, boolean cbr, int initialDelayLength, int removalDelayLength,
                  int outputDelayLength, boolean picStructPresent, int accessUnits, long totalBits, int fillerUnits,
                  long fillerBytes, List<Long> removalDelays, List<Long> outputDelays, List<Long> initialDelays,
                  Fraction lastRemoval, Fraction maxFullness, Fraction minHeadroom) {
        /** {@return the bits per second the stream carries up to the last removal} */
        double averageRate() {
            return totalBits / lastRemoval.toDouble();
        }
    }

    /** An exact non-negative or negative rational number. */
    record Fraction(BigInteger numerator, BigInteger denominator) implements Comparable<Fraction> {
        static final Fraction ZERO = of(0, 1);

        Fraction {
            if (denominator.signum() < 0) {
                numerator = numerator.negate();
                denominator = denominator.negate();
            }
            var gcd = numerator.gcd(denominator);
            if (gcd.signum() != 0 && !gcd.equals(BigInteger.ONE)) {
                numerator = numerator.divide(gcd);
                denominator = denominator.divide(gcd);
            }
        }

        static Fraction of(long numerator, long denominator) {
            return new Fraction(BigInteger.valueOf(numerator), BigInteger.valueOf(denominator));
        }

        Fraction plus(Fraction other) {
            return new Fraction(numerator.multiply(other.denominator).add(other.numerator.multiply(denominator)),
                    denominator.multiply(other.denominator));
        }

        Fraction minus(Fraction other) {
            return plus(new Fraction(other.numerator.negate(), other.denominator));
        }

        Fraction times(long factor) {
            return new Fraction(numerator.multiply(BigInteger.valueOf(factor)), denominator);
        }

        Fraction times(Fraction other) {
            return new Fraction(numerator.multiply(other.numerator), denominator.multiply(other.denominator));
        }

        BigInteger floor() {
            var parts = numerator.divideAndRemainder(denominator);
            return parts[1].signum() < 0 ? parts[0].subtract(BigInteger.ONE) : parts[0];
        }

        BigInteger ceil() {
            var floor = floor();
            return new Fraction(floor, BigInteger.ONE).equals(this) ? floor : floor.add(BigInteger.ONE);
        }

        double toDouble() {
            return numerator.doubleValue() / denominator.doubleValue();
        }

        static Fraction max(Fraction a, Fraction b) {
            return a.compareTo(b) >= 0 ? a : b;
        }

        static Fraction min(Fraction a, Fraction b) {
            return a.compareTo(b) <= 0 ? a : b;
        }

        @Override
        public int compareTo(Fraction other) {
            return numerator.multiply(other.denominator).compareTo(other.numerator.multiply(denominator));
        }

        @Override
        public String toString() {
            return numerator + "/" + denominator + " (" + toDouble() + ")";
        }
    }

    /** A NAL unit: its escaped bytes, header included, and the bytes of its start code or length prefix. */
    record Nal(byte[] data, int prefix) {
        int type() {
            return data[0] & 0x1F;
        }
    }

    /** Splits an Annex B byte stream into NAL units; each records its start code, zero_byte included. */
    static List<Nal> annexB(byte[] stream) {
        var units = new ArrayList<Nal>();
        var starts = new ArrayList<int[]>();
        for (var i = 0; i + 2 < stream.length; i++) {
            if (stream[i] == 0 && stream[i + 1] == 0 && stream[i + 2] == 1) {
                var prefixStart = i > 0 && stream[i - 1] == 0 ? i - 1 : i;
                starts.add(new int[]{prefixStart, i + 3});
                i += 2;
            }
        }
        for (var k = 0; k < starts.size(); k++) {
            var begin = starts.get(k)[1];
            var end = k + 1 < starts.size() ? starts.get(k + 1)[0] : stream.length;
            while (end > begin && stream[end - 1] == 0 && k + 1 == starts.size()) {
                end--;
            }
            var data = java.util.Arrays.copyOfRange(stream, begin, end);
            units.add(new Nal(data, begin - starts.get(k)[0]));
        }
        return units;
    }

    /** Splits a packet of NAL units with four-byte big-endian lengths, each counted as a four-byte start code. */
    static List<Nal> lengthPrefixed(byte[] packet) {
        var units = new ArrayList<Nal>();
        var offset = 0;
        while (offset + 4 <= packet.length) {
            var length = ((packet[offset] & 0xFF) << 24) | ((packet[offset + 1] & 0xFF) << 16)
                    | ((packet[offset + 2] & 0xFF) << 8) | (packet[offset + 3] & 0xFF);
            units.add(new Nal(java.util.Arrays.copyOfRange(packet, offset + 4, offset + 4 + length), 4));
            offset += 4 + length;
        }
        return units;
    }

    /** Returns the raw byte sequence payload of a NAL unit, without its header and emulation prevention. */
    static byte[] rbsp(byte[] nal) {
        var out = new ByteArrayOutputStream(nal.length);
        var zeros = 0;
        for (var i = 1; i < nal.length; i++) {
            var value = nal[i] & 0xFF;
            if (zeros >= 2 && value == 3) {
                zeros = 0;
                continue;
            }
            out.write(value);
            zeros = value == 0 ? zeros + 1 : 0;
        }
        return out.toByteArray();
    }

    /** A most significant bit first reader with Exp-Golomb codes. */
    static final class Bits {
        private final byte[] data;

        private long position;

        Bits(byte[] data) {
            this.data = data;
        }

        int bit() {
            if (position >= 8L * data.length) {
                throw new Violation("syntax runs past the end of a NAL unit");
            }
            var value = (data[(int) (position >> 3)] >> (7 - (position & 7))) & 1;
            position++;
            return value;
        }

        long bits(int count) {
            var value = 0L;
            for (var i = 0; i < count; i++) {
                value = value << 1 | bit();
            }
            return value;
        }

        long ue() {
            var zeros = 0;
            while (bit() == 0) {
                zeros++;
                if (zeros > 32) {
                    throw new Violation("invalid Exp-Golomb code");
                }
            }
            return (1L << zeros) - 1 + bits(zeros);
        }

        boolean aligned() {
            return (position & 7) == 0;
        }
    }

    /** The parts of a sequence parameter set the model needs. */
    record Sps(long numUnitsInTick, long timeScale, boolean nalHrd, long bitRate, long cpbSize, boolean cbr,
               int initialDelayLength, int removalDelayLength, int outputDelayLength, boolean picStructPresent,
               int maxDecFrameBuffering, boolean frameMbsOnly) {
    }

    static Sps sps(byte[] nal) {
        var bits = new Bits(rbsp(nal));
        var profile = (int) bits.bits(8);
        bits.bits(16);
        bits.ue();
        if (List.of(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135).contains(profile)) {
            var chroma = bits.ue();
            if (chroma == 3) {
                bits.bit();
            }
            bits.ue();
            bits.ue();
            bits.bit();
            if (bits.bit() != 0) {
                throw new Violation("the model does not read scaling matrices");
            }
        }
        bits.ue();
        var pocType = bits.ue();
        if (pocType == 0) {
            bits.ue();
        } else if (pocType == 1) {
            throw new Violation("the model does not read picture order count type 1");
        }
        bits.ue();
        bits.bit();
        bits.ue();
        bits.ue();
        var frameMbsOnly = bits.bit() == 1;
        if (!frameMbsOnly) {
            bits.bit();
        }
        bits.bit();
        if (bits.bit() != 0) {
            bits.ue();
            bits.ue();
            bits.ue();
            bits.ue();
        }
        if (bits.bit() == 0) {
            throw new Violation("no video usability information");
        }
        if (bits.bit() != 0 && bits.bits(8) == 255) {
            bits.bits(32);
        }
        if (bits.bit() != 0) {
            bits.bit();
        }
        if (bits.bit() != 0) {
            bits.bits(4);
            if (bits.bit() != 0) {
                bits.bits(24);
            }
        }
        if (bits.bit() != 0) {
            bits.ue();
            bits.ue();
        }
        if (bits.bit() == 0) {
            throw new Violation("no timing information");
        }
        var numUnitsInTick = bits.bits(32);
        var timeScale = bits.bits(32);
        bits.bit();
        var nalHrd = bits.bit() == 1;
        long bitRate = 0;
        long cpbSize = 0;
        var cbr = false;
        var initialLength = 0;
        var removalLength = 0;
        var outputLength = 0;
        if (nalHrd) {
            var count = bits.ue() + 1;
            var rateScale = (int) bits.bits(4);
            var sizeScale = (int) bits.bits(4);
            for (var i = 0; i < count; i++) {
                var rate = (bits.ue() + 1) << (6 + rateScale);
                var size = (bits.ue() + 1) << (4 + sizeScale);
                var flag = bits.bit() == 1;
                if (i == 0) {
                    bitRate = rate;
                    cpbSize = size;
                    cbr = flag;
                }
            }
            initialLength = (int) bits.bits(5) + 1;
            removalLength = (int) bits.bits(5) + 1;
            outputLength = (int) bits.bits(5) + 1;
            bits.bits(5);
        }
        if (bits.bit() != 0) {
            throw new Violation("the model checks the NAL HRD only");
        }
        if (nalHrd && bits.bit() != 0) {
            throw new Violation("low_delay_hrd_flag is set");
        }
        var picStruct = bits.bit() == 1;
        var maxDecFrameBuffering = -1;
        if (bits.bit() != 0) {
            bits.bit();
            bits.ue();
            bits.ue();
            bits.ue();
            bits.ue();
            bits.ue();
            maxDecFrameBuffering = (int) bits.ue();
        }
        return new Sps(numUnitsInTick, timeScale, nalHrd, bitRate, cpbSize, cbr, initialLength, removalLength,
                outputLength, picStruct, maxDecFrameBuffering, frameMbsOnly);
    }

    /** The timing values and size of one access unit. */
    private static final class Unit {
        long bits;
        boolean bufferingPeriod;
        long initialDelay;
        long initialOffset;
        boolean timing;
        long removalDelay;
        long outputDelay;
        int picStruct = -1;
        long display = -1;
        Fraction removal;
        Fraction start;
        Fraction end;
    }

    /**
     * Checks a stream.
     *
     * @param nals the NAL units of the stream in decoding order
     * @param display the presentation index of the frame of each NAL unit, or -1 when unknown
     * @param spsNal the sequence parameter set NAL unit, or null to take it from the stream
     * @return the report
     * @throws Violation if the stream breaks the decoder
     */
    static Report verify(List<Nal> nals, List<Long> display, byte[] spsNal) {
        Sps sps = spsNal == null ? null : sps(spsNal);
        var units = new ArrayList<Unit>();
        Unit current = null;
        var pending = new ArrayList<Integer>();
        var seenVcl = false;
        var fillerUnits = 0;
        var fillerBytes = 0L;
        for (var i = 0; i < nals.size(); i++) {
            var nal = nals.get(i);
            var type = nal.type();
            if (type == 7 && sps == null) {
                sps = sps(nal.data());
            }
            if (type >= 1 && type <= 5) {
                var firstMb = new Bits(rbsp(nal.data())).ue() == 0;
                if (current == null || seenVcl && firstMb) {
                    current = new Unit();
                    units.add(current);
                    seenVcl = false;
                }
                for (var k : pending) {
                    add(current, nals.get(k), sps, display.get(k));
                }
                pending.clear();
                add(current, nal, sps, display.get(i));
                seenVcl = true;
            } else if (type == 12 || type == 10 || type == 11) {
                if (current == null) {
                    throw new Violation("NAL unit " + i + " of type " + type + " precedes the first picture");
                }
                if (type == 12) {
                    fillerUnits++;
                    fillerBytes += nal.data().length;
                }
                add(current, nal, sps, display.get(i));
            } else if (current == null || seenVcl) {
                pending.add(i);
            } else {
                add(current, nal, sps, display.get(i));
            }
        }
        for (var k : pending) {
            add(current, nals.get(k), sps, display.get(k));
        }
        if (sps == null || !sps.nalHrd()) {
            throw new Violation("the stream signals no NAL HRD");
        }
        return check(units, sps, fillerUnits, fillerBytes);
    }

    private static void add(Unit unit, Nal nal, Sps sps, long display) {
        unit.bits += 8L * (nal.data().length + nal.prefix());
        if (display >= 0) {
            unit.display = display;
        }
        if (nal.type() != 6) {
            return;
        }
        if (sps == null) {
            throw new Violation("an SEI NAL unit precedes the sequence parameter set");
        }
        var payload = rbsp(nal.data());
        var offset = 0;
        while (offset < payload.length && !(offset == payload.length - 1 && (payload[offset] & 0xFF) == 0x80)) {
            var type = 0;
            while ((payload[offset] & 0xFF) == 255) {
                type += 255;
                offset++;
            }
            type += payload[offset++] & 0xFF;
            var size = 0;
            while ((payload[offset] & 0xFF) == 255) {
                size += 255;
                offset++;
            }
            size += payload[offset++] & 0xFF;
            var bits = new Bits(java.util.Arrays.copyOfRange(payload, offset, offset + size));
            if (type == 0) {
                if (unit.bufferingPeriod) {
                    throw new Violation("two buffering period messages in one access unit");
                }
                bits.ue();
                unit.bufferingPeriod = true;
                unit.initialDelay = bits.bits(sps.initialDelayLength());
                unit.initialOffset = bits.bits(sps.initialDelayLength());
            } else if (type == 1) {
                if (unit.timing) {
                    throw new Violation("two picture timing messages in one access unit");
                }
                unit.timing = true;
                unit.removalDelay = bits.bits(sps.removalDelayLength());
                unit.outputDelay = bits.bits(sps.outputDelayLength());
                if (sps.picStructPresent()) {
                    unit.picStruct = (int) bits.bits(4);
                }
            }
            offset += size;
        }
    }

    private static Report check(List<Unit> units, Sps sps, int fillerUnits, long fillerBytes) {
        var rate = sps.bitRate();
        var size = sps.cpbSize();
        var tick = Fraction.of(sps.numUnitsInTick(), sps.timeScale());
        var maxInitial = Fraction.of(90_000 * size, rate);
        Fraction periodRemoval = null;
        Unit periodUnit = null;
        Fraction previousEnd = Fraction.ZERO;
        var total = 0L;
        var removalDelays = new ArrayList<Long>();
        var outputDelays = new ArrayList<Long>();
        var initialDelays = new ArrayList<Long>();
        Fraction minHeadroom = null;
        for (var n = 0; n < units.size(); n++) {
            var unit = units.get(n);
            if (!unit.timing) {
                throw new Violation("access unit " + n + " has no picture timing message");
            }
            if (n == 0 && !unit.bufferingPeriod) {
                throw new Violation("the first access unit has no buffering period message");
            }
            removalDelays.add(unit.removalDelay);
            outputDelays.add(unit.outputDelay);
            if (unit.bufferingPeriod) {
                initialDelays.add(unit.initialDelay);
                if (unit.initialDelay == 0 || Fraction.of(unit.initialDelay, 1).compareTo(maxInitial) > 0) {
                    throw new Violation("access unit " + n + ": initial_cpb_removal_delay " + unit.initialDelay
                            + " outside (0, 90000 * CpbSize / BitRate = " + maxInitial + "]");
                }
            }
            if (n == 0) {
                unit.removal = Fraction.of(unit.initialDelay, 90_000);
                periodRemoval = unit.removal;
                periodUnit = unit;
                unit.start = Fraction.ZERO;
            } else {
                unit.removal = periodRemoval.plus(tick.times(unit.removalDelay));
                if (unit.bufferingPeriod) {
                    periodRemoval = unit.removal;
                    periodUnit = unit;
                }
                if (sps.cbr()) {
                    unit.start = previousEnd;
                } else {
                    var lead = unit.bufferingPeriod ? unit.initialDelay
                            : periodUnit.initialDelay + periodUnit.initialOffset;
                    unit.start = Fraction.max(previousEnd, unit.removal.minus(Fraction.of(lead, 90_000)));
                }
                if (unit.bufferingPeriod) {
                    var gap = unit.removal.minus(previousEnd).times(90_000);
                    var delay = BigInteger.valueOf(unit.initialDelay);
                    if (delay.compareTo(gap.ceil()) > 0 || sps.cbr() && delay.compareTo(gap.floor()) < 0) {
                        throw new Violation("access unit " + n + ": initial_cpb_removal_delay " + unit.initialDelay
                                + " for a gap of " + gap + " clock periods");
                    }
                }
                if (unit.removal.compareTo(units.get(n - 1).removal) <= 0) {
                    throw new Violation("access unit " + n + " is not removed after the previous one");
                }
            }
            unit.end = unit.start.plus(Fraction.of(unit.bits, rate));
            if (unit.end.compareTo(unit.removal) > 0) {
                throw new Violation("access unit " + n + " of " + unit.bits + " bits underflows the buffer: its "
                        + "last bit arrives at " + unit.end + ", after its removal at " + unit.removal);
            }
            var headroom = unit.removal.minus(unit.end);
            minHeadroom = minHeadroom == null ? headroom : Fraction.min(minHeadroom, headroom);
            previousEnd = unit.end;
            total += unit.bits;
        }
        var maxFullness = Fraction.ZERO;
        for (var n = 0; n < units.size(); n++) {
            var removal = units.get(n).removal;
            var fullness = Fraction.ZERO;
            for (var m = n; m < units.size() && units.get(m).start.compareTo(removal) < 0; m++) {
                var arrived = Fraction.min(removal.minus(units.get(m).start).times(rate),
                        Fraction.of(units.get(m).bits, 1));
                fullness = fullness.plus(arrived);
            }
            if (fullness.compareTo(Fraction.of(size, 1)) > 0) {
                throw new Violation("the buffer overflows before access unit " + n + " is removed: " + fullness
                        + " bits in a buffer of " + size);
            }
            maxFullness = Fraction.max(maxFullness, fullness);
        }
        checkOutput(units, sps, tick);
        return new Report(rate, size, sps.cbr(), sps.initialDelayLength(), sps.removalDelayLength(),
                sps.outputDelayLength(), sps.picStructPresent(), units.size(), total, fillerUnits, fillerBytes,
                removalDelays, outputDelays, initialDelays, units.getLast().removal, maxFullness, minHeadroom);
    }

    /**
     * Checks the output timing of the frames of a stream whose access units carry their presentation index: each
     * leaves the decoded picture buffer at or after its removal, in presentation order, one frame period after
     * the previous frame, and no more pictures wait for output than the buffer holds.
     */
    private static void checkOutput(List<Unit> units, Sps sps, Fraction tick) {
        if (units.stream().anyMatch(unit -> unit.display < 0) || !sps.frameMbsOnly()) {
            return;
        }
        var outputs = new java.util.TreeMap<Long, Fraction>();
        for (var n = 0; n < units.size(); n++) {
            var unit = units.get(n);
            var output = unit.removal.plus(tick.times(unit.outputDelay));
            if (outputs.put(unit.display, output) != null) {
                throw new Violation("two access units present frame " + unit.display);
            }
            if (sps.maxDecFrameBuffering() >= 0) {
                var waiting = 0;
                for (var m = 0; m <= n; m++) {
                    var other = units.get(m);
                    if (other.removal.plus(tick.times(other.outputDelay)).compareTo(unit.removal) > 0) {
                        waiting++;
                    }
                }
                if (waiting > sps.maxDecFrameBuffering()) {
                    throw new Violation(waiting + " pictures wait for output when access unit " + n
                            + " is removed, more than max_dec_frame_buffering " + sps.maxDecFrameBuffering());
                }
            }
        }
        Fraction previous = null;
        for (var output : outputs.values()) {
            if (previous != null && !output.minus(previous).equals(tick.times(2))) {
                throw new Violation("frames are output " + output.minus(previous) + " s apart, not one frame period");
            }
            previous = output;
        }
    }
}
