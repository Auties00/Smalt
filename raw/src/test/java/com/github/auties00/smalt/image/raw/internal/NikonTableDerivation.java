package com.github.auties00.smalt.image.raw.internal;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.io.MediaSource;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Re-derives the Huffman tables of Nikon's NEF compression from sample files and the sensor values
 * a black-box oracle (Adobe DNG Converter, writing uncompressed DNG files) stores for them.
 *
 * <p>The stream is a sequence of codes, each followed by the bits of a difference: the code's value
 * gives the difference category (the bit length of the difference, as in a JPEG DC table) in its
 * low four bits and, after a lossy file's split row, a shift in its high four bits (the stream then
 * holds {@code category - shift} bits, and the difference is shifted back and moved half of the
 * dropped range away from zero). The running values are clamped at zero. With the oracle's values,
 * the predictor of every photosite and so its difference are known, so the only unknown is which
 * code stands for which value. The derivation walks the stream from its first bit: a code that is
 * already known must reproduce the oracle's value; at a code not seen yet it tries every length
 * that keeps the code prefix-free and every value consistent with the oracle and the bits that
 * follow, and backtracks when a later photosite disagrees. The search admits a shift only in the
 * tables after the split row; that the codes it finds reproduce every photosite the oracle holds
 * is what confirms this model of the stream. Photosites the oracle does not hold (the padding
 * columns DNG Converter crops) are parsed with the known codes; a bounded number of new codes per
 * run of such photosites (one, then up to three) may be discovered there, decided by the
 * photosites after the run.
 *
 * <p>Only codes that decode at least one photosite the oracle holds count as observed. The observed
 * codes of every file are merged per table and completed into a {@code DHT} segment (code counts by
 * length, then values in code order) by {@link #complete(Map)}: a code that canonical code order
 * requires but no sample uses, and the code space after the last observed code, are filled with
 * {@link NikonTables#UNDERIVED} entries of the shortest possible length.
 */
final class NikonTableDerivation {
    /**
     * The photosites a search may decode, summed over all its walks, before it gives up.
     */
    private static final long BUDGET = 4_000_000_000L;

    /**
     * The bits of the lookup table that resolves codes.
     */
    private static final int PEEK = 16;

    /**
     * Prevents instantiation.
     */
    private NikonTableDerivation() {
    }

    /**
     * The codes one file shows, per table, with where its stream ended.
     *
     * @param before   the codes before the split row, by code (left-aligned on 16 bits) and length
     * @param after    the codes from the split row on, empty without a split row
     * @param endBit   the bit at which the decoded stream ended
     * @param totalBit the bits of the strip
     */
    record Result(Map<Long, Integer> before, Map<Long, Integer> after, long endBit, long totalBit) {
    }

    /**
     * A NEF stream and the oracle's samples for it.
     *
     * @param compressed  the stream parameters
     * @param data        the stream
     * @param oracle      the oracle's stored samples, row by row
     * @param oracleWidth the oracle's width
     * @param oracleX     the NEF column of the oracle's first column
     */
    record Problem(NefParser.Compressed compressed, byte[] data, short[] oracle, int oracleWidth, int oracleX) {
    }

    /**
     * Reads the stream of a NEF file and the samples of the oracle's DNG file.
     *
     * @param nef     the NEF file
     * @param dng     the uncompressed DNG file DNG Converter wrote from it
     * @param oracleX the NEF column of the DNG's first column
     * @return the problem
     */
    static Problem problem(Path nef, Path dng, int oracleX) {
        NefParser.Compressed compressed;
        byte[] data;
        try (var source = MediaSource.of(nef)) {
            var reader = TiffReader.open(source, 0, MediaLimits.DEFAULT);
            var tree = new TiffTree(reader);
            compressed = NefParser.compressed(reader, TiffEpParser.rawDirectory(tree), NefParser.makerNote(tree));
            data = reader.bytes(compressed.offset(), compressed.length());
        }
        var plane = oracle(dng);
        return new Problem(compressed, data, plane.integers, plane.width, oracleX);
    }

    /**
     * Decodes a NEF file with {@link NefParser} into its curve indices, before linearization.
     *
     * @param nef the file
     * @return the decoded values
     */
    static SensorPlane decode(Path nef) {
        try (var source = MediaSource.of(nef)) {
            var reader = TiffReader.open(source, 0, MediaLimits.DEFAULT);
            var tree = new TiffTree(reader);
            var compressed = NefParser.compressed(reader, TiffEpParser.rawDirectory(tree), NefParser.makerNote(tree));
            var identity = new int[compressed.curve().length];
            for (var index = 0; index < identity.length; index++) {
                identity[index] = index;
            }
            var plane = new SensorPlane(compressed.width(), compressed.height(), 1, false, MediaLimits.DEFAULT);
            NefParser.decompress(reader, compressed, identity, plane, MediaLimits.DEFAULT);
            return plane;
        }
    }

    /**
     * Finds where an oracle's samples lie in a decoded plane: the column and row offsets at which
     * every oracle sample equals the decoded one.
     *
     * @param decoded the decoded plane
     * @param oracle  the oracle's samples
     * @return the column and row offsets, or {@code null} when no offset matches
     */
    static int[] locate(SensorPlane decoded, SensorPlane oracle) {
        for (var y = 0; y <= decoded.height - oracle.height; y++) {
            for (var x = 0; x <= decoded.width - oracle.width; x++) {
                if (matches(decoded, oracle, x, y)) {
                    return new int[]{x, y};
                }
            }
        }
        return null;
    }

    /**
     * Returns whether an oracle's samples equal a decoded plane at an offset.
     *
     * @param decoded the decoded plane
     * @param oracle  the oracle's samples
     * @param x       the column offset
     * @param y       the row offset
     * @return whether every sample matches
     */
    private static boolean matches(SensorPlane decoded, SensorPlane oracle, int x, int y) {
        for (var row = 0; row < oracle.height; row++) {
            if (!Arrays.equals(decoded.integers, (row + y) * decoded.width + x, (row + y) * decoded.width + x + oracle.width,
                    oracle.integers, row * oracle.width, (row + 1) * oracle.width)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Reads the stored samples of an uncompressed DNG file, before linearization.
     *
     * @param dng the file
     * @return the samples
     */
    static SensorPlane oracle(Path dng) {
        try (var source = MediaSource.of(dng)) {
            var file = RawParser.parse(source, MediaLimits.DEFAULT);
            var sensor = file.sensor;
            var plane = new SensorPlane(sensor.width, sensor.height, sensor.samplesPerPixel, sensor.floating, MediaLimits.DEFAULT);
            sensor.reader.read(plane, MediaLimits.DEFAULT);
            return plane;
        }
    }

    /**
     * Derives the codes a file uses.
     *
     * @param problem the stream and the oracle
     * @return the codes, or {@code null} if no code assignment reproduces the oracle
     */
    static Result derive(Problem problem) {
        Search search = null;
        for (var perGap = 1; perGap <= 3; perGap++) {
            search = new Search(problem, perGap);
            if (search.run(0, 0, -1, -2, 0)) {
                break;
            }
            System.err.println("  " + perGap + " new code(s) per unknown run: furthest photosite " + search.furthest
                    + ", row " + search.furthest / problem.compressed().width() + " column "
                    + search.furthest % problem.compressed().width());
            search = null;
        }
        if (search == null) {
            return null;
        }
        search.confirm();
        return new Result(search.before.codes(), search.after.codes(), search.end, problem.data().length * 8L);
    }

    /**
     * Completes observed codes into a {@code DHT} segment.
     *
     * <p>Walking the code space in canonical order, every code before the next observed code that
     * canonical order leaves free is filled with an {@link NikonTables#UNDERIVED} entry, at the shortest length
     * at which it fits (the length at which the next observed code's prefix is reached), and the
     * code space after the last observed code is filled at that code's length.
     *
     * @param codes the value of each observed code, keyed by {@link #key(int, int)}
     * @return sixteen code counts by length, then the values in code order
     * @throws IllegalArgumentException if the codes are not a canonical prefix code
     */
    static int[] complete(Map<Long, Integer> codes) {
        var sorted = new ArrayList<>(codes.entrySet());
        sorted.sort(Comparator.comparingLong((Map.Entry<Long, Integer> entry) -> length(entry.getKey()))
                .thenComparingLong(entry -> code(entry.getKey())));
        var counts = new int[16];
        var values = new ArrayList<Integer>();
        var next = 0L;
        var length = 1;
        for (var entry : sorted) {
            var size = length(entry.getKey());
            var code = code(entry.getKey());
            while (length < size) {
                var prefix = code >>> (size - length);
                while (next < prefix) {
                    counts[length - 1]++;
                    values.add(NikonTables.UNDERIVED);
                    next++;
                }
                next <<= 1;
                length++;
            }
            if (code < next) {
                throw new IllegalArgumentException("not a canonical code at " + Long.toBinaryString(code) + "/" + size);
            }
            while (next < code) {
                counts[size - 1]++;
                values.add(NikonTables.UNDERIVED);
                next++;
            }
            counts[size - 1]++;
            values.add(entry.getValue());
            next++;
        }
        while (next < (1L << length)) {
            counts[length - 1]++;
            values.add(NikonTables.UNDERIVED);
            next++;
        }
        var spec = new int[16 + values.size()];
        System.arraycopy(counts, 0, spec, 0, 16);
        for (var index = 0; index < values.size(); index++) {
            spec[16 + index] = values.get(index);
        }
        return spec;
    }

    /**
     * Lists the codes of a {@code DHT} segment.
     *
     * @param spec the counts and values
     * @return the value of each code, keyed by {@link #key(int, int)}
     */
    static Map<Long, Integer> codes(int[] spec) {
        var codes = new TreeMap<Long, Integer>();
        var code = 0;
        var index = 16;
        for (var length = 1; length <= 16; length++) {
            for (var count = 0; count < spec[length - 1]; count++) {
                codes.put(key(code, length), spec[index++]);
                code++;
            }
            code <<= 1;
        }
        return codes;
    }

    /**
     * Returns the key of a code.
     *
     * @param code   the code
     * @param length its bit length
     * @return the key
     */
    static long key(int code, int length) {
        return ((long) length << 32) | code;
    }

    /**
     * Returns the code of a key.
     *
     * @param key the key
     * @return the code
     */
    static long code(long key) {
        return key & 0xFFFFFFFFL;
    }

    /**
     * Returns the length of a key.
     *
     * @param key the key
     * @return the length
     */
    static int length(long key) {
        return (int) (key >>> 32);
    }

    /**
     * Formats codes for a report.
     *
     * @param codes the codes
     * @return one line per code: the bits, the category and the shift
     */
    static String format(Map<Long, Integer> codes) {
        var builder = new StringBuilder();
        var sorted = new ArrayList<>(codes.entrySet());
        sorted.sort(Comparator.comparingLong((Map.Entry<Long, Integer> entry) -> length(entry.getKey()))
                .thenComparingLong(entry -> code(entry.getKey())));
        for (var entry : sorted) {
            var bits = Long.toBinaryString(code(entry.getKey()));
            bits = "0".repeat(length(entry.getKey()) - bits.length()) + bits;
            var value = entry.getValue();
            builder.append(String.format("  %-16s %s%n", bits,
                    value == NikonTables.UNDERIVED ? "underived" : "category " + (value & 15) + " shift " + (value >>> 4)));
        }
        return builder.toString();
    }

    /**
     * Holds the codes found so far for one table.
     */
    private static final class Book {
        /**
         * For each 16-bit prefix, the code length in bits 9 and up and the value in the low 9
         * bits, or {@code 0} when no known code starts the prefix.
         */
        final int[] lookup = new int[1 << PEEK];

        /**
         * Whether each value has a code.
         */
        final boolean[] used = new boolean[256];

        /**
         * Whether each value's code decodes a photosite the oracle holds.
         */
        final boolean[] confirmed = new boolean[256];

        /**
         * Returns whether a code would overlap a known code.
         *
         * @param code   the code
         * @param length its length
         * @return whether it conflicts
         */
        boolean conflicts(int code, int length) {
            var first = code << (PEEK - length);
            var end = (code + 1) << (PEEK - length);
            for (var index = first; index < end; index++) {
                if (lookup[index] != 0) {
                    return true;
                }
            }
            return false;
        }

        /**
         * Assigns or removes a code.
         *
         * @param code   the code
         * @param length its length
         * @param value  the value
         * @param assign whether to assign rather than remove
         */
        void set(int code, int length, int value, boolean assign) {
            var first = code << (PEEK - length);
            var end = (code + 1) << (PEEK - length);
            Arrays.fill(lookup, first, end, assign ? (length << 9) | value : 0);
            used[value] = assign;
        }

        /**
         * Lists the confirmed codes.
         *
         * @return the value of each code, keyed by {@link #key(int, int)}
         */
        Map<Long, Integer> codes() {
            var codes = new TreeMap<Long, Integer>();
            for (var index = 0; index < lookup.length; index++) {
                var entry = lookup[index];
                if (entry != 0 && confirmed[entry & 0x1FF]) {
                    var length = entry >>> 9;
                    codes.put(key(index >>> (PEEK - length), length), entry & 0x1FF);
                }
            }
            return codes;
        }
    }

    /**
     * The backtracking search over one file.
     */
    private static final class Search {
        /**
         * The stream, padded with zero bytes.
         */
        private final byte[] data;

        /**
         * The stream parameters.
         */
        private final NefParser.Compressed compressed;

        /**
         * The oracle's samples.
         */
        private final short[] oracle;

        /**
         * The oracle's width.
         */
        private final int oracleWidth;

        /**
         * The NEF column of the oracle's first column.
         */
        private final int oracleX;

        /**
         * The largest decoded value.
         */
        private final int last;

        /**
         * The codes before the split row.
         */
        final Book before = new Book();

        /**
         * The codes from the split row on.
         */
        final Book after = new Book();

        /**
         * The bit at which the stream ended.
         */
        long end;

        /**
         * The furthest photosite any walk reached, for diagnostics.
         */
        int furthest;

        /**
         * The photosites the walks have decoded, bounded by {@link #BUDGET}.
         */
        private long steps;

        /**
         * The most codes discovered in one run of photosites the oracle does not hold.
         */
        private final int perGap;

        /**
         * Constructs a search.
         *
         * @param problem the problem
         * @param perGap  the most codes discovered in one run of photosites the oracle does not hold
         */
        Search(Problem problem, int perGap) {
            this.perGap = perGap;
            this.compressed = problem.compressed();
            this.data = Arrays.copyOf(problem.data(), problem.data().length + 16);
            this.oracle = problem.oracle();
            this.oracleWidth = problem.oracleWidth();
            this.oracleX = problem.oracleX();
            this.last = (1 << compressed.bits()) - 1;
        }

        /**
         * Returns bits of the stream.
         *
         * @param position the first bit
         * @param count    the number of bits, at most 32
         * @return the bits
         */
        private int bits(long position, int count) {
            if (count == 0) {
                return 0;
            }
            var index = (int) (position >>> 3);
            if (index + 8 > data.length) {
                return 0;
            }
            var window = 0L;
            for (var k = 0; k < 8; k++) {
                window = (window << 8) | (data[index + k] & 0xFF);
            }
            return (int) ((window << (position & 7)) >>> (64 - count));
        }

        /**
         * Returns the oracle's value of a photosite.
         *
         * @param row    the row
         * @param column the NEF column
         * @return the value, or {@code -1} when the oracle does not hold it
         */
        private int value(int row, int column) {
            var x = column - oracleX;
            if (x < 0 || x >= oracleWidth || row >= oracle.length / oracleWidth) {
                return -1;
            }
            return oracle[row * oracleWidth + x] & 0xFFFF;
        }

        /**
         * Returns the oracle's predictor of a photosite.
         *
         * @param row    the row
         * @param column the column
         * @return the predictor, or {@code -1} when unknown
         */
        private int predictor(int row, int column) {
            if (column >= 2) {
                return value(row, column - 2);
            }
            if (row >= 2) {
                return value(row - 2, column);
            }
            return compressed.predictors()[(row & 1) * 2 + column];
        }

        /**
         * Decodes the difference of a code.
         *
         * @param position the bit after the code
         * @param value    the code's value
         * @return the difference
         */
        private int difference(long position, int value) {
            var shift = value >>> 4;
            var size = (value & 15) - shift;
            var difference = bits(position, size);
            if (size > 0 && (difference & (1 << (size - 1))) == 0) {
                difference -= (1 << size) - 1;
            }
            if (shift > 0 && difference != 0) {
                difference = difference * (1 << shift) + (difference > 0 ? 1 << (shift - 1) : -(1 << (shift - 1)));
            }
            return difference;
        }

        /**
         * Decodes the whole stream once more with the codes found and marks the codes that decode
         * a photosite the oracle holds: a code met only at photosites the oracle does not hold is
         * fixed by the alignment of the stream alone, which constrains the length of the code plus
         * its difference bits but not how they split, so it is not reported.
         */
        void confirm() {
            var width = compressed.width();
            var total = width * compressed.height();
            var split = compressed.lossless() ? 0 : compressed.splitRow();
            var position = 0L;
            for (var index = 0; index < total; index++) {
                var row = index / width;
                var column = index - row * width;
                var book = split > 0 && row >= split ? after : before;
                var entry = book.lookup[bits(position, PEEK)];
                var length = entry >>> 9;
                var code = entry & 0x1FF;
                var value = value(row, column);
                if (value >= 0 && predictor(row, column) >= 0) {
                    book.confirmed[code] = true;
                }
                position += length + (code & 15) - (code >>> 4);
            }
        }

        /**
         * Walks the stream from a photosite, discovering codes as needed.
         *
         * @param start        the photosite
         * @param position     its first bit
         * @param lastKnown    the last photosite the oracle holds before {@code start}
         * @param gapCount     the codes discovered in that run
         * @param discoveryGap the last known photosite before the run in which a code was last
         *                     discovered at an unknown photosite
         * @return whether the walk reached the end
         */
        boolean run(int start, long position, int lastKnown, int discoveryGap, int gapCount) {
            var width = compressed.width();
            var total = width * compressed.height();
            var split = compressed.lossless() ? 0 : compressed.splitRow();
            for (var index = start; index < total; index++) {
                var row = index / width;
                var column = index - row * width;
                var book = split > 0 && row >= split ? after : before;
                var value = value(row, column);
                var predictor = value < 0 ? -1 : predictor(row, column);
                var known = value >= 0 && predictor >= 0;
                furthest = Math.max(furthest, index);
                if (++steps > BUDGET) {
                    return false;
                }
                var entry = book.lookup[bits(position, PEEK)];
                if (entry != 0) {
                    var length = entry >>> 9;
                    var code = entry & 0x1FF;
                    if (known && Math.clamp(predictor + difference(position + length, code), 0, last) != value) {
                        return false;
                    }
                    position += length + (code & 15) - (code >>> 4);
                    if (known) {
                        lastKnown = index;
                    }
                    continue;
                }
                if (!known && discoveryGap == lastKnown && gapCount >= perGap) {
                    return false;
                }
                return discover(index, position, known ? index : lastKnown, known ? discoveryGap : lastKnown,
                        known ? gapCount : discoveryGap == lastKnown ? gapCount + 1 : 1, book, known, predictor, value);
            }
            end = position;
            return true;
        }

        /**
         * Tries every new code at a photosite.
         *
         * @param index        the photosite
         * @param position     its first bit
         * @param lastKnown    the last known photosite after this one is decoded
         * @param discoveryGap the discovery gap after this one is decoded
         * @param gapCount     the discoveries in that gap after this one is decoded
         * @param book         the table in use
         * @param known        whether the oracle holds the photosite
         * @param predictor    its predictor
         * @param value        its value
         * @return whether a walk reached the end
         */
        private boolean discover(int index, long position, int lastKnown, int discoveryGap, int gapCount, Book book, boolean known,
                                 int predictor, int value) {
            var maxCategory = compressed.bits();
            var target = value - predictor;
            var clamped = value == 0 || value == last;
            var category = Integer.SIZE - Integer.numberOfLeadingZeros(Math.abs(target));
            var maxShift = book == after ? 15 : 0;
            for (var length = 1; length <= PEEK; length++) {
                var code = bits(position, length);
                if (book.conflicts(code, length)) {
                    continue;
                }
                for (var candidate = 0; candidate <= maxCategory; candidate++) {
                    if (known && !clamped && candidate != category) {
                        continue;
                    }
                    for (var shift = 0; shift <= Math.min(candidate, maxShift); shift++) {
                        var symbol = (shift << 4) | candidate;
                        if (book.used[symbol]) {
                            continue;
                        }
                        if (known && Math.clamp(predictor + difference(position + length, symbol), 0, last) != value) {
                            continue;
                        }
                        book.set(code, length, symbol, true);
                        if (run(index + 1, position + length + candidate - shift, lastKnown, discoveryGap, gapCount)) {
                            return true;
                        }
                        book.set(code, length, symbol, false);
                    }
                }
            }
            return false;
        }
    }

    /**
     * Returns the index in {@link NikonTables#TABLES} of a file's table.
     *
     * @param compressed the stream parameters
     * @param after      whether the table is the one after the split row
     * @return the index
     */
    static int table(NefParser.Compressed compressed, boolean after) {
        return (compressed.bits() == 14 ? 3 : 0) + (compressed.lossless() ? 2 : 0) + (after ? 1 : 0);
    }

    /**
     * Merges the codes of a file into the codes of each table.
     *
     * @param tables     the codes of each table, filled in
     * @param compressed the file's stream parameters
     * @param result     the file's codes
     * @throws IllegalStateException if the file contradicts a code another file showed
     */
    static void merge(List<Map<Long, Integer>> tables, NefParser.Compressed compressed, Result result) {
        merge(tables.get(table(compressed, false)), result.before());
        if (!result.after().isEmpty()) {
            merge(tables.get(table(compressed, true)), result.after());
        }
    }

    /**
     * Merges codes into a table's codes.
     *
     * @param table the table's codes
     * @param codes the new codes
     * @throws IllegalStateException if a code contradicts the table
     */
    private static void merge(Map<Long, Integer> table, Map<Long, Integer> codes) {
        for (var entry : codes.entrySet()) {
            var previous = table.putIfAbsent(entry.getKey(), entry.getValue());
            if (previous != null && !previous.equals(entry.getValue())) {
                throw new IllegalStateException("code " + Long.toBinaryString(code(entry.getKey())) + " has two values");
            }
        }
    }

    /**
     * Prints the codes each file shows and the merged tables. Each argument is a file listing one
     * sample per line: the NEF path, the DNG path and the oracle column offset, separated by tabs.
     *
     * @param arguments the arguments
     * @throws java.io.IOException if a list cannot be read
     */
    static void main(String[] arguments) throws java.io.IOException {
        if (arguments.length > 0 && arguments[0].equals("--verify")) {
            for (var line : java.nio.file.Files.readAllLines(Path.of(arguments[1]))) {
                var fields = line.split("\t");
                String outcome;
                try {
                    var offset = locate(decode(Path.of(fields[0])), oracle(Path.of(fields[1])));
                    outcome = offset == null ? "MISMATCH" : "exact at column " + offset[0] + " row " + offset[1];
                } catch (RuntimeException exception) {
                    outcome = exception.toString();
                }
                System.out.println(Path.of(fields[0]).getFileName() + "	" + outcome);
            }
            return;
        }
        var tables = new ArrayList<Map<Long, Integer>>();
        for (var index = 0; index < 6; index++) {
            tables.add(new TreeMap<>());
        }
        for (var list : arguments) {
            for (var line : java.nio.file.Files.readAllLines(Path.of(list))) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                var fields = line.split("	");
                var started = System.nanoTime();
                var problem = problem(Path.of(fields[0]), Path.of(fields[1]), Integer.parseInt(fields[2]));
                var result = derive(problem);
                var compressed = problem.compressed();
                System.out.println("== " + Path.of(fields[0]).getFileName() + " table " + table(compressed, false)
                        + (compressed.splitRow() > 0 && !compressed.lossless() ? "+" + table(compressed, true) + " split "
                        + compressed.splitRow() : "") + " (" + (System.nanoTime() - started) / 1_000_000 + " ms)");
                if (result == null) {
                    System.out.println("  no consistent code");
                    continue;
                }
                System.out.print(format(result.before()));
                if (!result.after().isEmpty()) {
                    System.out.println(" after the split row:");
                    System.out.print(format(result.after()));
                }
                System.out.println("  end bit " + result.endBit() + " of " + result.totalBit());
                System.out.println("CODES	" + Path.of(fields[0]).getFileName() + "	" + table(compressed, false) + "	"
                        + result.before().keySet() + "	" + table(compressed, true) + "	" + result.after().keySet());
                merge(tables, compressed, result);
            }
        }
        for (var index = 0; index < 6; index++) {
            System.out.println("== table " + index);
            System.out.print(format(tables.get(index)));
            if (!tables.get(index).isEmpty()) {
                System.out.println("  " + Arrays.toString(complete(tables.get(index))));
            }
        }
    }
}
