package com.github.auties00.smalt.image.raw.internal;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.internal.io.SourceBytes;
import com.github.auties00.smalt.io.MediaSource;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Re-derives the Huffman tables of Canon's CRW compression from sample files and the sensor values
 * a black-box oracle (Adobe DNG Converter, writing uncompressed DNG files) stores for them.
 *
 * <p>The model of the stream, which the derivation confirms by reproducing every photosite: the
 * ten high bits of each photosite (the oracle's value shifted right by two in a 12-bit file) are
 * coded as their difference from the photosite two columns to the left (512 for the first two of
 * a row); the differences form blocks of 64 consecutive photosites in raster order, and a block is
 * coded like a JPEG block: a DC code (the category of the difference between the block's first
 * value and the previous block's, then its bits), then AC codes for the other 63 values, each a
 * run of zeros in its high four bits and a category in its low four, with {@code 0x00} ending the
 * block and {@code 0xF0} a run of 16 zeros. With the oracle's values, every code's value and the
 * bits that follow it are known, so the only unknown is which bit string stands for which value.
 * The derivation walks the stream from its first bit: a code that is already known must match the
 * stream; at a value not seen yet it tries every code length that keeps the table prefix-free and
 * whose following bits are the value's difference, and backtracks when a later photosite
 * disagrees.
 *
 * <p>The photosites of a 12-bit file after its last whole group of 256 are left out: the converter
 * stores them without their low bits.
 */
final class CrwTableDerivation {
    /**
     * The search steps a derivation may take before it gives up.
     */
    private static final long BUDGET = 1_000_000_000L;

    /**
     * Prevents instantiation.
     */
    private CrwTableDerivation() {
    }

    /**
     * A CRW stream and the oracle's values for it.
     *
     * @param layout the stream's location and coding
     * @param bits the stream with its stuffed bytes removed, one bit per element
     * @param high the ten high bits of every photosite the oracle holds, row by row
     * @param photosites the photosites the derivation decodes, a multiple of 64
     */
    record Problem(CrwParser.Layout layout, byte[] bits, int[] high, int photosites) {
    }

    /**
     * The codes one file shows.
     *
     * @param dc the DC codes, by key ({@link NikonTableDerivation#key(int, int)})
     * @param ac the AC codes, by key
     * @param endBit the bit at which the decoded stream ended
     * @param totalBit the bits of the stream
     */
    record Result(Map<Long, Integer> dc, Map<Long, Integer> ac, long endBit, long totalBit) {
    }

    /**
     * Reads the stream of a CRW file and the samples of the oracle's DNG file.
     *
     * @param crw the CRW file
     * @param dng the uncompressed DNG file DNG Converter wrote from it
     * @return the problem
     */
    static Problem problem(Path crw, Path dng) {
        CrwParser.Layout layout;
        byte[] data;
        int length;
        try (var source = MediaSource.of(crw)) {
            layout = CrwParser.layout(source, MediaLimits.DEFAULT);
            data = new byte[(int) layout.streamLength()];
            length = CrwParser.unstuff(data, SourceBytes.readAvailable(source, layout.stream(), data, 0, data.length));
        }
        var bits = new byte[length * 8];
        for (var index = 0; index < bits.length; index++) {
            bits[index] = (byte) ((data[index >> 3] >> (7 - (index & 7))) & 1);
        }
        var oracle = NikonTableDerivation.oracle(dng);
        if (oracle.width != layout.width() || oracle.height != layout.height()) {
            throw new IllegalArgumentException("oracle of " + oracle.width + "x" + oracle.height);
        }
        var shift = layout.lowBits() >= 0 ? 2 : 0;
        var high = new int[oracle.integers.length];
        for (var index = 0; index < high.length; index++) {
            high[index] = (oracle.integers[index] & 0xFFFF) >> shift;
        }
        var photosites = shift > 0 ? high.length / 256 * 256 : high.length / 64 * 64;
        return new Problem(layout, bits, high, photosites);
    }

    /**
     * Lists the codes the stream must hold, block by block: per code, the table (0 DC, 1 AC) in bit
     * 40, the value in bits 32 to 39, the number of bits that follow in bits 16 to 20 and those
     * bits in the low 16.
     *
     * @param problem the stream and the oracle
     * @return the codes
     */
    private static long[] tokens(Problem problem) {
        var high = problem.high();
        var width = problem.layout().width();
        var tokens = new long[1 << 16];
        var count = 0;
        var values = new int[64];
        var previous = 0;
        for (var start = 0; start < problem.photosites(); start += 64) {
            for (var index = 0; index < 64; index++) {
                var position = start + index;
                var predictor = position % width < 2 ? 512 : high[position - 2];
                values[index] = high[position] - predictor;
            }
            if (count + 128 > tokens.length) {
                tokens = Arrays.copyOf(tokens, tokens.length * 2);
            }
            var category = category(values[0] - previous);
            tokens[count++] = token(0, category, category, bits(values[0] - previous, category)) | ((long) start << 41);
            previous = values[0];
            var run = 0;
            for (var index = 1; index < 64; index++) {
                if (values[index] == 0) {
                    run++;
                    continue;
                }
                while (run > 15) {
                    tokens[count++] = token(1, 0xF0, 0, 0);
                    run -= 16;
                }
                var size = category(values[index]);
                tokens[count++] = token(1, (run << 4) | size, size, bits(values[index], size));
                run = 0;
            }
            if (run > 0) {
                tokens[count++] = token(1, 0, 0, 0);
            }
        }
        return Arrays.copyOf(tokens, count);
    }

    /**
     * Packs a code.
     *
     * @param table the table, 0 for DC and 1 for AC
     * @param value the code's value
     * @param size the number of bits that follow
     * @param bits those bits
     * @return the packed code
     */
    private static long token(int table, int value, int size, int bits) {
        return ((long) table << 40) | ((long) value << 32) | ((long) size << 16) | bits;
    }

    /**
     * {@return the bit length of a difference's magnitude}
     *
     * @param value the difference
     */
    private static int category(int value) {
        return 32 - Integer.numberOfLeadingZeros(Math.abs(value));
    }

    /**
     * {@return the bits that code a difference of a category, as in a JPEG scan}
     *
     * @param value the difference
     * @param category its category
     */
    private static int bits(int value, int category) {
        return value >= 0 ? value : value + (1 << category) - 1;
    }

    /**
     * Derives the codes a file uses.
     *
     * @param problem the stream and the oracle
     * @return the codes, or {@code null} if no code assignment reproduces the oracle
     */
    static Result derive(Problem problem) {
        var tokens = tokens(problem);
        var bits = problem.bits();
        var length = new int[2][256];
        var code = new int[2][256];
        var choiceToken = new int[1024];
        var choicePosition = new int[1024];
        var choiceLength = new int[1024];
        var choices = 0;
        var token = 0;
        var position = 0;
        var from = 1;
        var steps = 0L;
        var furthest = 0;
        while (token < tokens.length) {
            if (++steps > BUDGET) {
                return null;
            }
            furthest = Math.max(furthest, token);
            var packed = tokens[token];
            var table = (int) (packed >>> 40) & 1;
            var value = (int) (packed >>> 32) & 0xFF;
            var size = (int) (packed >>> 16) & 31;
            var following = (int) packed & 0xFFFF;
            if (length[table][value] != 0 && from == 1) {
                var known = length[table][value];
                if (peek(bits, position, known) == code[table][value] && peek(bits, position + known, size) == following) {
                    position += known + size;
                    token++;
                    continue;
                }
            } else {
                var placed = false;
                for (var candidate = from; candidate <= 16 && !placed; candidate++) {
                    var bitsOfCode = peek(bits, position, candidate);
                    if (conflicts(length[table], code[table], candidate, bitsOfCode)
                            || peek(bits, position + candidate, size) != following) {
                        continue;
                    }
                    length[table][value] = candidate;
                    code[table][value] = bitsOfCode;
                    if (choices == choiceToken.length) {
                        choiceToken = Arrays.copyOf(choiceToken, choices * 2);
                        choicePosition = Arrays.copyOf(choicePosition, choices * 2);
                        choiceLength = Arrays.copyOf(choiceLength, choices * 2);
                    }
                    choiceToken[choices] = token;
                    choicePosition[choices] = position;
                    choiceLength[choices] = candidate;
                    choices++;
                    position += candidate + size;
                    token++;
                    placed = true;
                }
                from = 1;
                if (placed) {
                    continue;
                }
            }
            if (choices == 0) {
                var block = furthest;
                while (block > 0 && (tokens[block] >>> 41) == 0) {
                    block--;
                }
                System.err.println("no code assignment past token " + furthest + ", in the block of photosite "
                        + (tokens[block] >>> 41));
                return null;
            }
            choices--;
            token = choiceToken[choices];
            position = choicePosition[choices];
            var undone = tokens[token];
            length[(int) (undone >>> 40) & 1][(int) (undone >>> 32) & 0xFF] = 0;
            from = choiceLength[choices] + 1;
        }
        return new Result(codes(length[0], code[0]), codes(length[1], code[1]), position, bits.length);
    }

    /**
     * Returns whether a code would break the prefix property of a table.
     *
     * @param lengths the code lengths of the table's values, 0 for none
     * @param codes the codes of the table's values
     * @param size the new code's length
     * @param value the new code
     * @return whether a code of the table is a prefix of the new one or the new one of it
     */
    private static boolean conflicts(int[] lengths, int[] codes, int size, int value) {
        for (var symbol = 0; symbol < 256; symbol++) {
            var other = lengths[symbol];
            if (other == 0) {
                continue;
            }
            var common = Math.min(size, other);
            if (value >>> (size - common) == codes[symbol] >>> (other - common)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Reads bits of the stream, zeros past its end.
     *
     * @param bits the stream, one bit per element
     * @param position the first bit
     * @param count the number of bits, at most 31
     * @return the bits
     */
    private static int peek(byte[] bits, int position, int count) {
        var value = 0;
        for (var index = 0; index < count; index++) {
            value = (value << 1) | (position + index < bits.length ? bits[position + index] : 0);
        }
        return value;
    }

    /**
     * Collects the codes of a table.
     *
     * @param lengths the code length of each value, 0 for none
     * @param codes the code of each value
     * @return the value of each code, by key
     */
    private static Map<Long, Integer> codes(int[] lengths, int[] codes) {
        var result = new TreeMap<Long, Integer>();
        for (var value = 0; value < 256; value++) {
            if (lengths[value] != 0) {
                result.put(NikonTableDerivation.key(codes[value], lengths[value]), value);
            }
        }
        return result;
    }

    /**
     * Adds the codes of a file to the codes of its table set.
     *
     * @param tables the DC and AC codes of each table set, merged so far
     * @param problem the file's stream
     * @param result the file's codes
     * @throws IllegalArgumentException if a code has another value than in an earlier file
     */
    static void merge(List<Map<Long, Integer>> tables, Problem problem, Result result) {
        var set = problem.layout().table();
        merge(tables.get(2 * set), result.dc());
        merge(tables.get(2 * set + 1), result.ac());
    }

    /**
     * Adds codes to a table's codes.
     *
     * @param table the codes merged so far
     * @param codes the codes to add
     * @throws IllegalArgumentException if a code has another value than in the table, or a value
     * another code
     */
    private static void merge(Map<Long, Integer> table, Map<Long, Integer> codes) {
        codes.forEach((key, value) -> {
            var previous = table.putIfAbsent(key, value);
            if (previous != null && !previous.equals(value)) {
                throw new IllegalArgumentException("code " + key + " is " + previous + " and " + value);
            }
        });
        if (table.values().stream().distinct().count() != table.size()) {
            throw new IllegalArgumentException("a value has two codes");
        }
    }
}
