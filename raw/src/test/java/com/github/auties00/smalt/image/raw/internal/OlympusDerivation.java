package com.github.auties00.smalt.image.raw.internal;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.io.MediaSource;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Re-derives Olympus' ORF compression from sample files and the sensor values a black-box oracle
 * (Adobe DNG Converter, writing uncompressed DNG files) stores for them, without any knowledge of
 * the format beyond the TIFF strip that holds it.
 *
 * <p>Each step searches a family of hypotheses and keeps the members that the data allows; the
 * test {@code OlympusDerivationTest} checks that every step selects the layout and the constants
 * {@link OlympusDecoder} uses, and that the decoder then reproduces the oracle:
 * <ol>
 * <li>{@link #header}: the bytes every strip shares before the first code.</li>
 * <li>{@link #absoluteCodes}: the bit order and the code of a row's first photosite of each
 * color: a bit, the two low bits of the value, a run of zeros ended by a one, and further bits;
 * the run must count the value shifted right by a constant, which pins that constant.</li>
 * <li>{@link #rowStart}: rows restart with the same code, so the second row's first two codes
 * are found in the stream at one position only.</li>
 * <li>{@link #alignments}: on a flat image the codes settle to a fixed length; the low bits of the
 * residual against the photosite two columns to the left are found at a fixed offset of
 * fixed-length codes only for one length, and only in two's complement.</li>
 * <li>{@link #biasRules}: the high part of the residual is coded with a per-color bias that
 * depends on the previous high part and bias; of a family of linear updates only one survives a
 * search in which the width of the mid field is left free.</li>
 * <li>{@link #widthTable}: with the bias fixed, the width of the mid field at every photosite is
 * determined by the stream; tabulated against candidate magnitudes of the previous code, it is a
 * function of the one's complement magnitude of the previous coded value, with a hold after large
 * values, which {@link #widthRules} selects from a family.</li>
 * <li>{@link #escapes}: a zero run reaching twelve escapes to an explicit quotient; its width is
 * selected from a family by decoding whole rows.</li>
 * <li>{@link #predictions} and {@link #edgeRules}: the code parse of a row does not depend on the
 * prediction, so every photosite yields the prediction the encoder used; candidate predictors and
 * the switch between the mean and the gradient are scored against them.</li>
 * </ol>
 */
final class OlympusDerivation {
    /**
     * Prevents instantiation.
     */
    private OlympusDerivation() {
    }

    /**
     * An ORF sample: its strip and the oracle's photosites.
     *
     * @param name   the file name
     * @param strip  the strip's bytes
     * @param oracle the converter's photosites
     */
    record Sample(String name, byte[] strip, SensorPlane oracle) {
        /**
         * Returns an oracle photosite.
         *
         * @param y the row
         * @param x the column
         * @return the photosite
         */
        int at(int y, int x) {
            return oracle.integers[y * oracle.width + x] & 0xFFFF;
        }
    }

    /**
     * Reads a sample: the first strip of the ORF file, and the photosites of the DNG file the
     * converter wrote from it.
     *
     * @param orf the ORF file
     * @param dng the DNG file
     * @return the sample
     */
    static Sample load(Path orf, Path dng) {
        byte[] strip;
        try (var source = MediaSource.of(orf)) {
            var reader = new TiffReader(source, 0, true, MediaLimits.DEFAULT);
            var first = new TiffTree(reader).first;
            var offsets = first.longs(TiffSamples.STRIP_OFFSETS);
            var counts = first.longs(TiffSamples.STRIP_BYTE_COUNTS);
            strip = new byte[(int) counts[0]];
            reader.readAvailable(reader.base() + offsets[0], strip, 0, strip.length);
        }
        return new Sample(orf.getFileName().toString(), strip, NikonTableDerivation.oracle(dng));
    }

    /**
     * Returns a field of a byte array.
     *
     * @param data     the bytes
     * @param position the index of the field's first bit
     * @param bits     the width, at most 32
     * @param msbFirst whether bits are taken from the most significant bit of each byte down
     * @return the field, its first bit the most significant; zero past the end
     */
    static int bits(byte[] data, long position, int bits, boolean msbFirst) {
        var value = 0;
        for (var k = 0; k < bits; k++) {
            var bit = position + k;
            var index = (int) (bit >>> 3);
            var b = index < data.length ? data[index] & 0xFF : 0;
            var shift = msbFirst ? 7 - (int) (bit & 7) : (int) (bit & 7);
            value = (value << 1) | ((b >>> shift) & 1);
        }
        return value;
    }

    /**
     * Returns the number of leading bytes every strip shares.
     *
     * @param samples the samples
     * @return the length of the common prefix
     */
    static int header(List<Sample> samples) {
        var length = 0;
        while (true) {
            var value = samples.getFirst().strip[length];
            for (var sample : samples) {
                if (sample.strip[length] != value) {
                    return length;
                }
            }
            length++;
        }
    }

    /**
     * A candidate code for a row's first photosite of a color: a bit, the two low bits of the
     * value, a run of zero bits ended by a one bit, and {@code mid} further bits; the value is
     * {@code (run << (mid + 2)) | (further << 2) | low}.
     *
     * @param msbFirst whether bits are read from the most significant bit of each byte down
     * @param mid      the number of bits after the run
     */
    record AbsoluteCode(boolean msbFirst, int mid) {
    }

    /**
     * Returns the candidate codes under which the first two photosites of the first rows of every
     * sample decode to the oracle's values, the rows after the first being found by
     * {@link #rowStart} (and skipped where it finds no single position); values whose zero run
     * would exceed eleven bits are left out.
     *
     * @param samples the samples
     * @param header  the bytes before the first code
     * @param rows    the rows checked, from the first
     * @return the codes that hold for every checked photosite, with the number of photosites
     */
    static Map<AbsoluteCode, Integer> absoluteCodes(List<Sample> samples, int header, int rows) {
        var result = new LinkedHashMap<AbsoluteCode, Integer>();
        for (var msbFirst : new boolean[]{true, false}) {
            for (var mid = 0; mid <= 8; mid++) {
                var code = new AbsoluteCode(msbFirst, mid);
                var checked = 0;
                var holds = true;
                for (var sample : samples) {
                    var position = (long) header * 8;
                    for (var y = 0; y < rows && holds; y++) {
                        if (y > 0) {
                            position = rowStart(sample, header, y, code);
                            if (position < 0) {
                                break;
                            }
                        }
                        for (var x = 0; x < 2 && holds; x++) {
                            var value = sample.at(y, x);
                            if (value >> (mid + 2) > 11) {
                                break;
                            }
                            var decoded = absolute(sample.strip, position, code);
                            holds = decoded[0] == value;
                            position = decoded[1];
                            checked++;
                        }
                    }
                    if (!holds) {
                        break;
                    }
                }
                if (holds && checked > 0) {
                    result.put(code, checked);
                }
            }
        }
        return result;
    }

    /**
     * Decodes one photosite with a candidate absolute code.
     *
     * @param data     the strip
     * @param position the bit of the code's first bit
     * @param code     the candidate code
     * @return the value and the bit after the code
     */
    static long[] absolute(byte[] data, long position, AbsoluteCode code) {
        var p = position + 1;
        var low = bits(data, p, 2, code.msbFirst);
        p += 2;
        var run = 0;
        while (run < 64 && bits(data, p, 1, code.msbFirst) == 0) {
            run++;
            p++;
        }
        p++;
        var further = bits(data, p, code.mid, code.msbFirst);
        p += code.mid;
        return new long[]{((long) run << (code.mid + 2)) | ((long) further << 2) | low, p};
    }

    /**
     * Returns the bit at which a row's codes start: the only position where the codes of the
     * row's first two photosites, under a candidate absolute code, are found.
     *
     * @param sample the sample
     * @param header the bytes before the first code
     * @param y      the row, from 1
     * @param code   the candidate absolute code
     * @return the bit, or -1 when the codes are found nowhere or more than once
     */
    static long rowStart(Sample sample, int header, int y, AbsoluteCode code) {
        var pattern = new StringBuilder();
        for (var x = 0; x < 2; x++) {
            var value = sample.at(y, x);
            var run = value >> (code.mid + 2);
            if (run > 11) {
                return -1;
            }
            pattern.append('0').append(binary(value & 3, 2)).append("0".repeat(run)).append('1')
                    .append(binary((value >> 2) & ((1 << code.mid) - 1), code.mid));
        }
        var target = pattern.toString();
        var limit = Math.min((long) sample.strip.length * 8, (long) header * 8 + (long) y * sample.oracle.width * 24L);
        var found = -1L;
        for (var p = (long) header * 8; p + target.length() <= limit; p++) {
            var match = true;
            for (var k = 0; k < target.length() && match; k++) {
                match = bits(sample.strip, p + k, 1, code.msbFirst) == target.charAt(k) - '0';
            }
            if (match) {
                if (found >= 0) {
                    return -1;
                }
                found = p;
            }
        }
        return found;
    }

    /**
     * Returns a value in binary.
     *
     * @param value the value
     * @param bits  the number of digits
     * @return the digits, most significant first
     */
    private static String binary(int value, int bits) {
        var out = new StringBuilder();
        for (var k = bits - 1; k >= 0; k--) {
            out.append((value >> k) & 1);
        }
        return out.toString();
    }

    /**
     * A fixed-length code layout found in the stream.
     *
     * @param length         the code length
     * @param lowOffset      the offset of the two low bits in each code
     * @param twosComplement whether the low bits are those of the residual in two's complement
     *                       rather than of its magnitude
     * @param position       the bit of the first code of the window
     */
    record Alignment(int length, int lowOffset, boolean twosComplement, long position) {
    }

    /**
     * Searches a row for fixed-length codes: every bit position where, for a window of
     * consecutive photosites, each code of a candidate length holds the two low bits of the
     * photosite's residual against the photosite two columns to the left at a candidate offset.
     *
     * @param sample the sample, ideally flat so that the codes settle to one length
     * @param from   the first column of the window
     * @param count  the photosites of the window
     * @param end    the bit at which the row ends
     * @return every layout and position that fits
     */
    static List<Alignment> alignments(Sample sample, int from, int count, long end) {
        var result = new ArrayList<Alignment>();
        for (var length = 3; length <= 9; length++) {
            for (var offset = 0; offset + 2 <= length; offset++) {
                for (var twos : new boolean[]{true, false}) {
                    for (var p = 0L; p + (long) length * count <= end; p++) {
                        var match = true;
                        for (var k = 0; k < count && match; k++) {
                            var x = from + k;
                            var residual = sample.at(0, x) - sample.at(0, x - 2);
                            var low = (twos ? residual : Math.abs(residual)) & 3;
                            match = bits(sample.strip, p + (long) length * k + offset, 2, true) == low;
                        }
                        if (match) {
                            result.add(new Alignment(length, offset, twos, p));
                        }
                    }
                }
            }
        }
        return result;
    }

    /**
     * A parsed code: the sign bit, the two low bits, the run of zeros (or the escaped quotient)
     * and the bit after the code.
     *
     * @param sign  the first bit
     * @param low   the two low bits
     * @param run   the quotient
     * @param mid   the mid field
     * @param next  the bit after the code
     */
    record Code(int sign, int low, int run, int mid, long next) {
        /**
         * Returns the coded value: the magnitude {@code (run << width) | mid}, or its one's
         * complement for a sign of 1.
         *
         * @param width the width of the mid field
         * @return the coded value
         */
        int value(int width) {
            var magnitude = (run << width) | mid;
            return sign == 0 ? magnitude : -magnitude - 1;
        }
    }

    /**
     * An escape: a zero run of {@code zeros} bits is followed by the quotient in
     * {@code base - width} bits and, when {@code terminated}, a one bit.
     *
     * @param zeros      the length of the run that escapes
     * @param base       the bits of the quotient plus the mid width
     * @param terminated whether a one bit follows the quotient
     */
    record Escape(int zeros, int base, boolean terminated) {
        /**
         * The escape of the format.
         */
        static final Escape FORMAT = new Escape(OlympusDecoder.ESCAPE, OlympusDecoder.ESCAPE_BITS, true);
    }

    /**
     * Parses a code.
     *
     * @param data     the strip
     * @param position the bit of the code's first bit
     * @param width    the width of the mid field
     * @param escape   the escape, or {@code null} for none
     * @return the code
     */
    static Code parse(byte[] data, long position, int width, Escape escape) {
        var sign = bits(data, position, 1, true);
        var low = bits(data, position + 1, 2, true);
        var p = position + 3;
        var run = 0;
        var limit = escape == null ? 64 : escape.zeros;
        while (run < limit && bits(data, p, 1, true) == 0) {
            run++;
            p++;
        }
        if (escape != null && run == escape.zeros) {
            var quotientBits = escape.base - width;
            if (quotientBits < 0) {
                return null;
            }
            run = bits(data, p, quotientBits, true);
            p += quotientBits;
            if (escape.terminated) {
                if (bits(data, p, 1, true) != 1) {
                    return null;
                }
                p++;
            }
        } else {
            p++;
        }
        var mid = bits(data, p, width, true);
        return new Code(sign, low, run, mid, p + width);
    }

    /**
     * A candidate bias update: {@code S' = -floor((a * x + c * S + d) / 2^k)}, where {@code x} is
     * the high part {@code H} of the residual, or the residual itself when {@code residual}.
     *
     * @param a        the factor of the high part or residual
     * @param c        the factor of the previous bias
     * @param d        the constant
     * @param k        the shift
     * @param residual whether {@code x} is the residual rather than its high part
     */
    record Bias(int a, int c, int d, int k, boolean residual) {
        /**
         * The update of the format.
         */
        static final Bias FORMAT = new Bias(3, -1, 0, 5, false);

        /**
         * Returns the next bias.
         *
         * @param bias     the bias
         * @param high     the high part of the residual
         * @param residual the residual
         * @return the next bias
         */
        int next(int bias, int high, int residual) {
            var x = this.residual ? residual : high;
            return -Math.floorDiv(a * x + c * bias + d, 1 << k);
        }
    }

    /**
     * Returns the bias updates of a family under which row 0 of every sample, up to a number of
     * photosites, can be parsed with some width of the mid field at every photosite (a beam search
     * over the widths 2 to 9), each photosite decoding to its oracle value with the prediction of
     * the photosite two columns to the left.
     *
     * @param samples    the samples
     * @param photosites the photosites of each row checked
     * @return the surviving updates
     */
    static List<Bias> biasRules(List<Sample> samples, int photosites) {
        var survivors = new ArrayList<Bias>();
        for (var residual : new boolean[]{false, true}) {
            for (var k = 3; k <= 8; k++) {
                for (var a = 1; a <= 8; a++) {
                    for (var c = -4; c <= 4; c++) {
                        for (var d : new int[]{0, 1 << (k - 1)}) {
                            var bias = new Bias(a, c, d, k, residual);
                            var holds = true;
                            for (var sample : samples) {
                                if (beam(sample, bias, photosites) == null) {
                                    holds = false;
                                    break;
                                }
                            }
                            if (holds) {
                                survivors.add(bias);
                            }
                        }
                    }
                }
            }
        }
        return survivors;
    }

    /**
     * A beam state: the bit position and the bias of each color.
     *
     * @param position the bit position
     * @param even     the bias of the even columns
     * @param odd      the bias of the odd columns
     */
    private record State(long position, int even, int odd) {
    }

    /**
     * Parses the start of row 0 with a bias update and a free mid width: every state reachable
     * by some sequence of widths is kept.
     *
     * @param sample     the sample
     * @param bias       the bias update
     * @param photosites the photosites parsed
     * @return for each photosite from column 2, the widths that lead to a state from which the
     *         parse continues to the end, or {@code null} when no sequence of widths fits; the
     *         parse stops early, successfully, at a zero run of twelve bits or more (an escape,
     *         derived separately), and does not start when the row's first code escapes
     */
    static List<int[]> beam(Sample sample, Bias bias, int photosites) {
        var code = new AbsoluteCode(true, 4);
        var position = (long) OlympusDecoder.HEADER * 8;
        for (var x = 0; x < 2; x++) {
            if (sample.at(0, x) >> 6 > 11) {
                return List.of();
            }
            position = absolute(sample.strip, position, code)[1];
        }
        var start = new State(position, bias.next(0, sample.at(0, 0) >> 2, sample.at(0, 0)),
                bias.next(0, sample.at(0, 1) >> 2, sample.at(0, 1)));
        var layers = new ArrayList<Map<State, List<long[]>>>();
        Map<State, List<long[]>> front = new HashMap<>();
        front.put(start, List.of());
        var end = Math.min(photosites, sample.oracle.width);
        for (var x = 2; x < end; x++) {
            var residual = sample.at(0, x) - sample.at(0, x - 2);
            var high = residual >> 2;
            var low = residual & 3;
            var next = new HashMap<State, List<long[]>>();
            var escaped = false;
            for (var state : front.keySet()) {
                var current = (x & 1) == 0 ? state.even : state.odd;
                for (var width = 2; width <= 9; width++) {
                    var parsed = parse(sample.strip, state.position, width, null);
                    if (parsed.run > 11) {
                        escaped = true;
                        continue;
                    }
                    if (parsed.low != low || parsed.value(width) != high + current) {
                        continue;
                    }
                    var updated = bias.next(current, high, residual);
                    var target = (x & 1) == 0 ? new State(parsed.next, updated, state.odd)
                            : new State(parsed.next, state.even, updated);
                    next.computeIfAbsent(target, ignored -> new ArrayList<>())
                            .add(new long[]{state.position, state.even, state.odd, width});
                }
            }
            if (next.isEmpty()) {
                if (escaped) {
                    break;
                }
                return null;
            }
            if (next.size() > 256) {
                var trimmed = new HashMap<State, List<long[]>>();
                next.entrySet().stream().limit(256).forEach(entry -> trimmed.put(entry.getKey(), entry.getValue()));
                next = trimmed;
            }
            layers.add(next);
            front = next;
        }
        var widths = new ArrayList<int[]>();
        var alive = new java.util.HashSet<>(front.keySet());
        for (var index = layers.size() - 1; index >= 0; index--) {
            var options = new java.util.TreeSet<Integer>();
            var previous = new java.util.HashSet<State>();
            for (var entry : layers.get(index).entrySet()) {
                if (!alive.contains(entry.getKey())) {
                    continue;
                }
                for (var edge : entry.getValue()) {
                    options.add((int) edge[3]);
                    previous.add(new State(edge[0], (int) edge[1], (int) edge[2]));
                }
            }
            widths.addFirst(options.stream().mapToInt(Integer::intValue).toArray());
            alive = previous;
        }
        return widths;
    }

    /**
     * Tabulates the width of the mid field, where the stream determines it, against the
     * magnitude of the previous coded value of the color, measured four ways: the one's
     * complement magnitude of the coded value, its absolute value, the one's complement magnitude
     * of the high part of the residual, and of the residual itself.
     *
     * @param samples    the samples
     * @param photosites the photosites of row 0 parsed
     * @return for each measure, the number of photosites whose width differs from the most common
     *         width at the same bit length of the measure
     */
    static Map<String, Integer> widthTable(List<Sample> samples, int photosites) {
        var tables = new LinkedHashMap<String, Map<Integer, Map<Integer, Integer>>>();
        for (var measure : new String[]{"coded one's complement", "coded absolute", "high part", "residual"}) {
            tables.put(measure, new TreeMap<>());
        }
        for (var sample : samples) {
            var widths = beam(sample, Bias.FORMAT, photosites);
            if (widths == null) {
                continue;
            }
            int[] bias = {Bias.FORMAT.next(0, sample.at(0, 0) >> 2, sample.at(0, 0)),
                    Bias.FORMAT.next(0, sample.at(0, 1) >> 2, sample.at(0, 1))};
            var coded = new int[widths.size() + 2];
            var highs = new int[widths.size() + 2];
            var residuals = new int[widths.size() + 2];
            for (var x = 0; x < 2; x++) {
                coded[x] = sample.at(0, x) >> 2;
                highs[x] = coded[x];
                residuals[x] = sample.at(0, x);
            }
            for (var x = 2; x < widths.size() + 2; x++) {
                var residual = sample.at(0, x) - sample.at(0, x - 2);
                var high = residual >> 2;
                coded[x] = high + bias[x & 1];
                highs[x] = high;
                residuals[x] = residual;
                bias[x & 1] = Bias.FORMAT.next(bias[x & 1], high, residual);
                if (x < 6 || widths.get(x - 2).length != 1) {
                    continue;
                }
                var width = widths.get(x - 2)[0];
                add(tables.get("coded one's complement"), magnitude(coded[x - 2]), width);
                add(tables.get("coded absolute"), Math.abs(coded[x - 2]), width);
                add(tables.get("high part"), magnitude(highs[x - 2]), width);
                add(tables.get("residual"), magnitude(residuals[x - 2]), width);
            }
        }
        var conflicts = new LinkedHashMap<String, Integer>();
        tables.forEach((measure, table) -> {
            var count = 0;
            for (var row : table.values()) {
                var total = row.values().stream().mapToInt(Integer::intValue).sum();
                var top = row.values().stream().mapToInt(Integer::intValue).max().orElse(0);
                count += total - top;
            }
            conflicts.put(measure, count);
        });
        return conflicts;
    }

    /**
     * Adds an observation to a table keyed by the bit length of a measure.
     *
     * @param table   the table
     * @param measure the measure
     * @param width   the observed width
     */
    private static void add(Map<Integer, Map<Integer, Integer>> table, int measure, int width) {
        table.computeIfAbsent(32 - Integer.numberOfLeadingZeros(measure), ignored -> new TreeMap<>())
                .merge(width, 1, Integer::sum);
    }

    /**
     * Returns the one's complement magnitude of a value.
     *
     * @param value the value
     * @return the value when non-negative, {@code -value - 1} otherwise
     */
    static int magnitude(int value) {
        return value >= 0 ? value : -value - 1;
    }

    /**
     * A candidate rule for the width of the mid field, from the magnitudes {@code m1},
     * {@code m2} and {@code m3} of the last three coded values of the color: when {@code m1}
     * reaches {@code large}, {@code max(floor, bitlen(m1) - drop)}; otherwise {@code floor} when
     * one of the {@code hold} magnitudes before {@code m1} reaches {@code large}; otherwise
     * {@code max(minimum, bitlen(m1))}.
     *
     * @param large   the magnitude from which a value counts as large
     * @param drop    the bits dropped from the length of a large magnitude
     * @param floor   the width after a large value
     * @param hold    the number of magnitudes before the last that keep the width at
     *                {@code floor}
     * @param minimum the smallest width
     */
    record Width(int large, int drop, int floor, int hold, int minimum) {
        /**
         * The rule of the format.
         */
        static final Width FORMAT = new Width(OlympusDecoder.LARGE, 2, 4, 2, 2);

        /**
         * Returns the width.
         *
         * @param m1 the magnitude of the previous coded value of the color
         * @param m2 the one before
         * @param m3 the one before that
         * @return the width
         */
        int width(int m1, int m2, int m3) {
            var length = 32 - Integer.numberOfLeadingZeros(m1);
            if (m1 >= large) {
                return Math.max(floor, length - drop);
            }
            if ((hold >= 1 && m2 >= large) || (hold >= 2 && m3 >= large)) {
                return floor;
            }
            return Math.max(minimum, length);
        }
    }

    /**
     * Parses whole rows with a bias update, a width rule and an escape, and returns the parse of
     * each photosite: its high part {@code G - S} and low bits; the parse does not depend on the
     * prediction.
     *
     * @param sample the sample
     * @param rows   the rows parsed, from the first
     * @param bias   the bias update
     * @param width  the width rule
     * @param escape the escape
     * @return the high parts and low bits, row by row, {@code [row][column][0 high, 1 low]}, with
     *         {@code null} rows after a failed parse
     */
    static int[][][] parseRows(Sample sample, int rows, Bias bias, Width width, Escape escape) {
        return parse(sample, rows, bias, width, escape, new long[rows]);
    }

    /**
     * Returns the bit after each of the first rows of a sample, parsed with the format's rules.
     *
     * @param sample the sample
     * @param rows   the rows parsed, from the first
     * @return the bit after each row, 0 after a failed parse
     */
    static long[] rowEnds(Sample sample, int rows) {
        var ends = new long[rows];
        parse(sample, rows, Bias.FORMAT, Width.FORMAT, Escape.FORMAT, ends);
        return ends;
    }

    /**
     * Parses whole rows; see {@link #parseRows}.
     *
     * @param sample the sample
     * @param rows   the rows parsed, from the first
     * @param bias   the bias update
     * @param width  the width rule
     * @param escape the escape
     * @param ends   filled with the bit after each row
     * @return the high parts and low bits
     */
    private static int[][][] parse(Sample sample, int rows, Bias bias, Width width, Escape escape, long[] ends) {
        var w = sample.oracle.width;
        var out = new int[rows][][];
        var position = (long) OlympusDecoder.HEADER * 8;
        for (var y = 0; y < rows; y++) {
            var row = new int[w][2];
            int[] s = {0, 0};
            int[][] history = {{32, 32, 32}, {32, 32, 32}};
            for (var x = 0; x < w; x++) {
                var c = x & 1;
                var h = history[c];
                var b = width.width(h[0], h[1], h[2]);
                var parsed = parse(sample.strip, position, b, escape);
                if (parsed == null) {
                    return out;
                }
                var coded = parsed.value(b);
                var high = coded - s[c];
                row[x][0] = high;
                row[x][1] = parsed.low;
                var predicted = y < 2 ? (x < 2 ? 0 : sample.at(y, x - 2)) : -1;
                var residual = predicted >= 0 ? sample.at(y, x) - predicted : 4 * high + parsed.low;
                s[c] = bias.next(s[c], high, residual);
                h[2] = h[1];
                h[1] = h[0];
                h[0] = magnitude(coded);
                position = parsed.next;
            }
            out[y] = row;
            ends[y] = position;
        }
        return out;
    }

    /**
     * Returns the number of photosites of the first two rows of a sample that decode to the
     * oracle's values with a bias update, a width rule and an escape, counted up to the first
     * failure.
     *
     * @param sample the sample
     * @param bias   the bias update
     * @param width  the width rule
     * @param escape the escape
     * @return the photosites decoded before the first mismatch
     */
    static int firstRows(Sample sample, Bias bias, Width width, Escape escape) {
        var parsed = parseRows(sample, 2, bias, width, escape);
        var count = 0;
        for (var y = 0; y < 2; y++) {
            if (parsed[y] == null) {
                return count;
            }
            for (var x = 0; x < sample.oracle.width; x++) {
                var predicted = x < 2 ? 0 : sample.at(y, x - 2);
                if (predicted + 4 * parsed[y][x][0] + parsed[y][x][1] != sample.at(y, x)) {
                    return count;
                }
                count++;
            }
        }
        return count;
    }

    /**
     * Returns the width rules of a family under which the first two rows of every sample decode
     * entirely.
     *
     * @param samples the samples
     * @return the surviving rules
     */
    static List<Width> widthRules(List<Sample> samples) {
        var survivors = new ArrayList<Width>();
        for (var large = 8; large <= 32; large++) {
            for (var drop = 0; drop <= 3; drop++) {
                for (var floor = 2; floor <= 5; floor++) {
                    for (var hold = 0; hold <= 2; hold++) {
                        for (var minimum = 0; minimum <= 3; minimum++) {
                            var width = new Width(large, drop, floor, hold, minimum);
                            var holds = true;
                            for (var sample : samples) {
                                if (firstRows(sample, Bias.FORMAT, width, Escape.FORMAT) != 2 * sample.oracle.width) {
                                    holds = false;
                                    break;
                                }
                            }
                            if (holds) {
                                survivors.add(width);
                            }
                        }
                    }
                }
            }
        }
        return survivors;
    }

    /**
     * Returns the longest zero run of a normal code in the first two rows of every sample, parsed
     * with the format's rules and escape: the escape must start one bit after it.
     *
     * @param samples the samples
     * @return the longest run that ends with a one bit
     */
    static int longestRun(List<Sample> samples) {
        var longest = 0;
        for (var sample : samples) {
            var position = (long) OlympusDecoder.HEADER * 8;
            for (var y = 0; y < 2; y++) {
                int[] s = {0, 0};
                int[][] history = {{32, 32, 32}, {32, 32, 32}};
                for (var x = 0; x < sample.oracle.width; x++) {
                    var c = x & 1;
                    var h = history[c];
                    var b = Width.FORMAT.width(h[0], h[1], h[2]);
                    var parsed = parse(sample.strip, position, b, Escape.FORMAT);
                    var zeros = 0;
                    while (zeros < OlympusDecoder.ESCAPE && bits(sample.strip, position + 3 + zeros, 1, true) == 0) {
                        zeros++;
                    }
                    if (zeros < OlympusDecoder.ESCAPE) {
                        longest = Math.max(longest, zeros);
                    }
                    var coded = parsed.value(b);
                    var high = coded - s[c];
                    var predicted = x < 2 ? 0 : sample.at(y, x - 2);
                    s[c] = Bias.FORMAT.next(s[c], high, sample.at(y, x) - predicted);
                    h[2] = h[1];
                    h[1] = h[0];
                    h[0] = magnitude(coded);
                    position = parsed.next;
                }
            }
        }
        return longest;
    }

    /**
     * Returns the escapes of a family under which the first two rows of every sample decode
     * entirely: a run of 10 to 14 zeros, followed by the quotient in {@code base - width} bits for
     * {@code base} from 11 to 18, with or without a one bit after it.
     *
     * @param samples the samples
     * @return the surviving escapes
     */
    static List<Escape> escapes(List<Sample> samples) {
        var survivors = new ArrayList<Escape>();
        for (var zeros = 10; zeros <= 14; zeros++) {
            for (var base = 11; base <= 18; base++) {
                for (var terminated : new boolean[]{true, false}) {
                    var escape = new Escape(zeros, base, terminated);
                    var holds = true;
                    for (var sample : samples) {
                        if (firstRows(sample, Bias.FORMAT, Width.FORMAT, escape) != 2 * sample.oracle.width) {
                            holds = false;
                            break;
                        }
                    }
                    if (holds) {
                        survivors.add(escape);
                    }
                }
            }
        }
        return survivors;
    }

    /**
     * A photosite of a row from the third on, with the prediction the encoder used, recovered
     * from the stream as {@code v - 4 * H - low}.
     *
     * @param prediction the encoder's prediction
     * @param left       the photosite two columns to the left
     * @param up         the photosite two rows up
     * @param upLeft     the photosite two rows up and two columns to the left
     */
    record Context(int prediction, int left, int up, int upLeft) {
    }

    /**
     * Collects, for rows 2 and on, the encoder's prediction of every photosite from its third
     * column, with its neighbors.
     *
     * @param sample the sample
     * @param rows   the rows parsed, from the first
     * @return the contexts
     */
    static List<Context> contexts(Sample sample, int rows) {
        var parsed = parseRows(sample, rows, Bias.FORMAT, Width.FORMAT, Escape.FORMAT);
        var result = new ArrayList<Context>();
        for (var y = 2; y < rows; y++) {
            if (parsed[y] == null) {
                break;
            }
            for (var x = 2; x < sample.oracle.width; x++) {
                var prediction = sample.at(y, x) - 4 * parsed[y][x][0] - parsed[y][x][1];
                result.add(new Context(prediction, sample.at(y, x - 2), sample.at(y - 2, x), sample.at(y - 2, x - 2)));
            }
        }
        return result;
    }

    /**
     * Scores candidate predictors: the fraction of contexts each reproduces.
     *
     * @param contexts the contexts
     * @return the fraction per candidate
     */
    static Map<String, Double> predictions(List<Context> contexts) {
        var candidates = new LinkedHashMap<String, java.util.function.ToIntFunction<Context>>();
        candidates.put("left", c -> c.left);
        candidates.put("up", c -> c.up);
        candidates.put("mean", c -> (c.left + c.up) >> 1);
        candidates.put("gradient", c -> c.left + c.up - c.upLeft);
        candidates.put("median of left, up and gradient", c -> median(c.left, c.up, c.left + c.up - c.upLeft));
        candidates.put("min or max outside, mean inside", c -> edge(c, Integer.MAX_VALUE));
        candidates.put("min or max outside, gradient inside", c -> edge(c, -1));
        candidates.put("min or max outside, mean or gradient inside", c -> edge(c, OlympusDecoder.EDGE));
        var result = new LinkedHashMap<String, Double>();
        candidates.forEach((name, predictor) -> {
            var hits = contexts.stream().filter(c -> predictor.applyAsInt(c) == c.prediction).count();
            result.put(name, (double) hits / contexts.size());
        });
        return result;
    }

    /**
     * Returns the median of three values.
     *
     * @param a the first value
     * @param b the second value
     * @param c the third value
     * @return the median
     */
    private static int median(int a, int b, int c) {
        return Math.max(Math.min(a, b), Math.min(Math.max(a, b), c));
    }

    /**
     * Returns the edge-switching prediction: the smaller neighbor when the upper-left one is at
     * least both, the larger when it is at most both, and otherwise the gradient when the
     * upper-left neighbor differs from the left or the upper one by more than a threshold and the
     * mean otherwise.
     *
     * @param c         the context
     * @param threshold the threshold
     * @return the prediction
     */
    static int edge(Context c, int threshold) {
        var high = Math.max(c.left, c.up);
        var low = Math.min(c.left, c.up);
        if (c.upLeft >= high) {
            return low;
        }
        if (c.upLeft <= low) {
            return high;
        }
        return Math.max(Math.abs(c.left - c.upLeft), Math.abs(c.up - c.upLeft)) > threshold ? c.left + c.up - c.upLeft
                : (c.left + c.up) >> 1;
    }

    /**
     * Scores the features that could switch between the mean and the gradient when the upper-left
     * neighbor lies between the left and the upper one: for each feature, the best threshold
     * and the fraction of the discriminating contexts it classifies right.
     *
     * @param contexts the contexts
     * @return the best threshold and accuracy per feature
     */
    static Map<String, double[]> edgeRules(List<Context> contexts) {
        var features = new LinkedHashMap<String, java.util.function.ToIntFunction<Context>>();
        features.put("|left - up|", c -> Math.abs(c.left - c.up));
        features.put("max(|left - upLeft|, |up - upLeft|)", c -> Math.max(Math.abs(c.left - c.upLeft), Math.abs(c.up - c.upLeft)));
        features.put("min(|left - upLeft|, |up - upLeft|)", c -> Math.min(Math.abs(c.left - c.upLeft), Math.abs(c.up - c.upLeft)));
        features.put("|left - upLeft|", c -> Math.abs(c.left - c.upLeft));
        features.put("|up - upLeft|", c -> Math.abs(c.up - c.upLeft));
        var cases = new ArrayList<int[]>();
        for (var c : contexts) {
            var high = Math.max(c.left, c.up);
            var low = Math.min(c.left, c.up);
            if (c.upLeft >= high || c.upLeft <= low) {
                continue;
            }
            var mean = (c.left + c.up) >> 1;
            var gradient = c.left + c.up - c.upLeft;
            if (mean == gradient || (c.prediction != mean && c.prediction != gradient)) {
                continue;
            }
            var values = new int[features.size() + 1];
            var index = 0;
            for (var feature : features.values()) {
                values[index++] = feature.applyAsInt(c);
            }
            values[index] = c.prediction == gradient ? 1 : 0;
            cases.add(values);
        }
        var result = new LinkedHashMap<String, double[]>();
        var index = 0;
        for (var name : features.keySet()) {
            var column = index++;
            var thresholds = cases.stream().mapToInt(values -> values[column]).distinct().sorted().toArray();
            var best = new double[]{0, 0};
            for (var threshold : thresholds) {
                var right = cases.stream().filter(values -> (values[column] > threshold) == (values[values.length - 1] == 1)).count();
                var accuracy = (double) right / cases.size();
                if (accuracy > best[1]) {
                    best = new double[]{threshold, accuracy};
                }
            }
            result.put(name, best);
        }
        return result;
    }

    /**
     * Decodes a sample's strip with {@link OlympusDecoder} and returns the number of photosites
     * that differ from the oracle.
     *
     * @param sample the sample
     * @return the number of differing photosites
     */
    static long mismatches(Sample sample) {
        var plane = new SensorPlane(sample.oracle.width, sample.oracle.height, 1, false, MediaLimits.DEFAULT);
        OlympusDecoder.decode(sample.strip, plane);
        var count = 0L;
        for (var index = 0; index < plane.integers.length; index++) {
            if (plane.integers[index] != sample.oracle.integers[index]) {
                count++;
            }
        }
        return count;
    }

    /**
     * Prints the derivation for a list of ORF and DNG file pairs.
     *
     * @param args pairs of paths: an ORF file, then the DNG file the converter wrote from it
     */
    static void main(String[] args) {
        var samples = new ArrayList<Sample>();
        for (var index = 0; index + 1 < args.length; index += 2) {
            samples.add(load(Path.of(args[index]), Path.of(args[index + 1])));
        }
        var header = header(samples);
        System.out.println("header bytes " + header + ": " + Arrays.toString(Arrays.copyOf(samples.getFirst().strip, header)));
        System.out.println("absolute codes " + absoluteCodes(samples, header, 2));
        for (var sample : samples) {
            System.out.println(sample.name + " row 1 at bit " + rowStart(sample, header, 1, new AbsoluteCode(true, 4))
                    + ", row 0 parsed to bit " + rowEnds(sample, 1)[0]);
        }
        var flat = samples.getFirst();
        System.out.println("alignments in " + flat.name + " " + alignments(flat, 1002, 12, rowEnds(flat, 1)[0]));
        System.out.println("bias rules " + biasRules(samples, 400));
        System.out.println("width table conflicts " + widthTable(samples, 1500));
        System.out.println("width rules " + widthRules(samples));
        System.out.println("escapes " + escapes(samples) + ", longest normal run " + longestRun(samples));
        var contexts = new ArrayList<Context>();
        for (var sample : samples) {
            contexts.addAll(contexts(sample, 24));
        }
        System.out.println("predictions " + predictions(contexts));
        edgeRules(contexts).forEach((name, best) -> System.out.println("edge " + name + " " + Arrays.toString(best)));
        for (var sample : samples) {
            System.out.println(sample.name + " mismatches " + mismatches(sample));
        }
    }
}
