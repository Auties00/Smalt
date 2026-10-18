package com.github.auties00.smalt.image.raw.internal;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.raw.CfaColor;
import com.github.auties00.smalt.image.raw.CfaPattern;
import com.github.auties00.smalt.io.MediaSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Re-derives Panasonic's RW2 raw format version 4, and the chunk layout version 5 shares with it,
 * from sample files and the sensor values a black-box oracle (Adobe DNG Converter, writing
 * uncompressed DNG files) stores for them, without any knowledge of the format beyond the raw tags
 * that give the image size, the version and the offset of the data.
 *
 * <p>Each step searches a family of hypotheses and keeps what the data allows; the test
 * {@code PanasonicDerivationTest} checks that every step selects the layout and the constants
 * {@link PanasonicDecoder} uses:
 * <ol>
 * <li>{@link #photositesPerBlock}: the data has a fixed rate: whole 16-byte blocks of a constant
 * number of photosites per row fill it up to the last chunk.</li>
 * <li>{@link #chunkLayout}: the stream is cut into chunks and each chunk rotated; the chunk size,
 * the rotation and the bit order of a block are searched together, scoring how many blocks start
 * with the oracle's first photosites (two 12-bit values for version 4, one 14-bit value for
 * version 5).</li>
 * <li>{@link #blockLayouts}: the rest of a version 4 block holds groups of a code and fields for the
 * remaining twelve photosites; every split of the bits into groups, codes and fields is scored by
 * how many fields of code 0 give the difference to the photosite two to the left.</li>
 * <li>{@link #codeRules}: for each code, the rules a field could follow (a shifted difference, a
 * shifted absolute value keeping the low bits of the photosite two to the left or not, and the
 * threshold on that photosite between the two) are scored on every field.</li>
 * <li>{@link #zeroFields} and {@link #markerBlocks}: a field of 0 repeats the photosite two to the
 * left, and a 12-bit value whose high eight bits are zero is a missing photosite whose next
 * photosite of the color in the block is stored as a 12-bit value; both are checked against the
 * alternatives.</li>
 * <li>{@link #repairs}: the converter fills missing photosites from neighbors of their color;
 * the candidate neighborhoods and roundings are scored.</li>
 * </ol>
 */
final class PanasonicDerivation {
    /**
     * Prevents instantiation.
     */
    private PanasonicDerivation() {
    }

    /**
     * An RW2 sample: its raw tags, its data and the oracle's photosites.
     *
     * @param name     the file name
     * @param version  the raw format version (tag {@code 0x002D})
     * @param width    the stored width (tag {@code 0x0002})
     * @param height   the stored height (tag {@code 0x0003})
     * @param right    the right edge of the image area (tag {@code 0x0007})
     * @param cfa      the color filter array
     * @param data     the data, from the offset of tag {@code 0x0118} to the end of the file
     * @param oracle   the converter's photosites
     */
    record Sample(String name, int version, int width, int height, int right, CfaPattern cfa, byte[] data,
                  SensorPlane oracle) {
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
     * Reads a sample.
     *
     * @param rw2 the RW2 or RWL file
     * @param dng the DNG file the converter wrote from it
     * @return the sample
     * @throws java.io.IOException if a file cannot be read
     */
    static Sample load(Path rw2, Path dng) throws java.io.IOException {
        int version, width, height, right, cfaCode;
        long offset;
        try (var source = MediaSource.of(rw2)) {
            var reader = new TiffReader(source, 0, true, MediaLimits.DEFAULT);
            var first = new TiffTree(reader).first;
            version = first.intValue(0x2D, 0);
            width = first.intValue(0x02, 0);
            height = first.intValue(0x03, 0);
            right = first.intValue(0x07, width);
            cfaCode = first.intValue(0x09, 1);
            offset = first.longValue(0x118, 0);
        }
        var all = Files.readAllBytes(rw2);
        var data = Arrays.copyOfRange(all, (int) offset, all.length);
        var cfa = switch (cfaCode) {
            case 2 -> CfaPattern.bayer("GRBG");
            case 3 -> CfaPattern.bayer("GBRG");
            case 4 -> CfaPattern.bayer("BGGR");
            default -> CfaPattern.bayer("RGGB");
        };
        return new Sample(rw2.getFileName().toString(), version, width, height, right, cfa, data,
                NikonTableDerivation.oracle(dng));
    }

    /**
     * Returns the number of photosites per 16-byte block for which whole blocks, a row at a time,
     * fill the data up to its last 16384-byte chunk: the only count whose blocks need no more than
     * the data and leave less than a chunk unused.
     *
     * @param sample the sample
     * @return the count, or -1 when none fits
     */
    static int photositesPerBlock(Sample sample) {
        var found = -1;
        for (var per = 1; per <= 64; per++) {
            if (sample.width % per != 0) {
                continue;
            }
            var bytes = (long) sample.width / per * sample.height * 16;
            if (bytes <= sample.data.length && sample.data.length - bytes < 0x4000) {
                if (found >= 0) {
                    return -1;
                }
                found = per;
            }
        }
        return found;
    }

    /**
     * A bit order of a 16-byte block.
     */
    enum Order {
        /**
         * The block is a 128-bit little-endian integer read from its most significant bit down.
         */
        REVERSED_MSB_FIRST,
        /**
         * The bytes are read in order, each from its most significant bit down.
         */
        FORWARD_MSB_FIRST,
        /**
         * The block is a 128-bit little-endian integer read from its least significant bit up,
         * each field's first bit its least significant.
         */
        LSB_FIRST
    }

    /**
     * Returns a field of a block.
     *
     * @param block    the 16 bytes
     * @param order    the bit order
     * @param position the index of the field's first bit
     * @param bits     the width
     * @return the field
     */
    static int field(byte[] block, Order order, int position, int bits) {
        var value = 0;
        for (var k = 0; k < bits; k++) {
            var bit = position + k;
            int b;
            switch (order) {
                case REVERSED_MSB_FIRST -> b = (block[15 - (bit >> 3)] >>> (7 - (bit & 7))) & 1;
                case FORWARD_MSB_FIRST -> b = (block[bit >> 3] >>> (7 - (bit & 7))) & 1;
                default -> {
                    b = (block[bit >> 3] >>> (bit & 7)) & 1;
                    value |= b << k;
                    continue;
                }
            }
            value = (value << 1) | b;
        }
        return value;
    }

    /**
     * Returns the stream's block with an index under a chunk layout: each chunk of {@code chunk}
     * bytes holds the stream bytes rotated, stream byte {@code i} of the chunk at chunk offset
     * {@code (i + rotation) % chunk}.
     *
     * @param data     the stored data
     * @param chunk    the chunk size
     * @param rotation the rotation
     * @param index    the block index
     * @return the 16 bytes
     */
    static byte[] block(byte[] data, int chunk, int rotation, long index) {
        var out = new byte[16];
        for (var k = 0; k < 16; k++) {
            var stream = index * 16 + k;
            var base = stream / chunk * chunk;
            var physical = base + (stream - base + rotation) % chunk;
            out[k] = physical < data.length ? data[(int) physical] : 0;
        }
        return out;
    }

    /**
     * A chunk layout and block bit order with its score.
     *
     * @param chunk    the chunk size
     * @param rotation the rotation, -1 when no rotation puts the first photosites first
     * @param order    the bit order, {@code null} with no rotation
     * @param score    the fraction of checked blocks that start with the oracle's photosites
     */
    record Layout(int chunk, int rotation, Order order, double score) {
    }

    /**
     * Searches the chunk size (powers of two from 1 KiB to 64 KiB), the rotation and the bit
     * order of the blocks, scoring each by the fraction of the blocks of the first chunks whose
     * first photosites equal the oracle's: version 4 blocks start with two 12-bit values, version 5
     * blocks with a 14-bit value; blocks right of the oracle's columns are not checked. Only the
     * rotations that put the image's first photosites at the start of the first block are
     * scored.
     *
     * @param sample the sample
     * @param chunks the chunks of each size checked from the start
     * @return the best layout of each chunk size, best first
     */
    static List<Layout> chunkLayout(Sample sample, int chunks) {
        var per = photositesPerBlock(sample);
        var bits = sample.version == 4 ? 12 : 14;
        var values = sample.version == 4 ? 2 : 1;
        var blocksPerRow = sample.width / per;
        var result = new ArrayList<Layout>();
        for (var size = 1 << 10; size <= 1 << 16; size <<= 1) {
            var blocks = Math.min((long) size / 16 * chunks, (long) blocksPerRow * sample.height);
            Layout best = null;
            for (var order : Order.values()) {
                for (var rotation = 0; rotation < size; rotation++) {
                    var head = block(sample.data, size, rotation, 0);
                    var first = true;
                    for (var v = 0; v < values && first; v++) {
                        first = field(head, order, v * bits, bits) == sample.at(0, v);
                    }
                    if (!first) {
                        continue;
                    }
                    var checked = 0;
                    var hits = 0;
                    for (var index = 0L; index < blocks; index += 7) {
                        var y = (int) (index / blocksPerRow);
                        var x = (int) (index % blocksPerRow) * per;
                        if (x + values > sample.oracle.width || y >= sample.oracle.height) {
                            continue;
                        }
                        var data = block(sample.data, size, rotation, index);
                        var match = true;
                        for (var v = 0; v < values && match; v++) {
                            match = field(data, order, v * bits, bits) == sample.at(y, x + v);
                        }
                        checked++;
                        if (match) {
                            hits++;
                        }
                    }
                    var score = checked == 0 ? 0 : (double) hits / checked;
                    if (best == null || score > best.score) {
                        best = new Layout(size, rotation, order, score);
                    }
                }
            }
            result.add(best != null ? best : new Layout(size, -1, null, 0));
        }
        result.sort((a, b) -> Double.compare(b.score, a.score));
        return result;
    }

    /**
     * Returns the stream of a sample under the format's chunk layout.
     *
     * @param sample the sample
     * @return the stream
     */
    static byte[] stream(Sample sample) {
        var out = new byte[(sample.data.length + PanasonicDecoder.CHUNK - 1) / PanasonicDecoder.CHUNK * PanasonicDecoder.CHUNK];
        for (var index = 0; index < out.length; index++) {
            var base = index / PanasonicDecoder.CHUNK * PanasonicDecoder.CHUNK;
            var physical = base + (index - base + PanasonicDecoder.CHUNK - PanasonicDecoder.SPLIT) % PanasonicDecoder.CHUNK;
            out[index] = physical < sample.data.length ? sample.data[physical] : 0;
        }
        return out;
    }

    /**
     * A split of the 104 bits after a version 4 block's two 12-bit values: groups of a code of
     * {@code codeBits} bits and fields of {@code fieldBits} bits, {@code 12 / groups} fields per
     * group.
     *
     * @param groups    the number of groups
     * @param codeBits  the bits of a code
     * @param fieldBits the bits of a field
     * @param score     the fraction of fields of code 0 whose value less half its range is the
     *                  difference to the photosite two to the left
     */
    record Split(int groups, int codeBits, int fieldBits, double score) {
    }

    /**
     * Scores every split of the bits after the two 12-bit values of the version 4 blocks: blocks
     * whose two values are not plain (their high eight bits are zero) are left out.
     *
     * @param sample the sample
     * @param rows   the rows checked
     * @return the splits, best first
     */
    static List<Split> blockLayouts(Sample sample, int rows) {
        var stream = stream(sample);
        var blocksPerRow = sample.width / 14;
        var result = new ArrayList<Split>();
        for (var groups : new int[]{1, 2, 3, 4, 6, 12}) {
            var per = 12 / groups;
            for (var codeBits = 0; codeBits <= 3; codeBits++) {
                for (var fieldBits = 4; fieldBits <= 10; fieldBits++) {
                    if (24 + groups * (codeBits + per * fieldBits) > 128) {
                        continue;
                    }
                    var checked = 0;
                    var hits = 0;
                    for (var y = 0; y < rows; y++) {
                        for (var k = 0; k < blocksPerRow; k++) {
                            var x0 = 14 * k;
                            if (x0 + 14 > sample.oracle.width) {
                                continue;
                            }
                            var data = Arrays.copyOfRange(stream, (y * blocksPerRow + k) * 16, (y * blocksPerRow + k) * 16 + 16);
                            if (field(data, Order.REVERSED_MSB_FIRST, 0, 8) == 0 || field(data, Order.REVERSED_MSB_FIRST, 12, 8) == 0) {
                                continue;
                            }
                            var position = 24;
                            for (var g = 0; g < groups; g++) {
                                var code = field(data, Order.REVERSED_MSB_FIRST, position, codeBits);
                                position += codeBits;
                                for (var j = 0; j < per; j++) {
                                    var f = field(data, Order.REVERSED_MSB_FIRST, position, fieldBits);
                                    position += fieldBits;
                                    if (code != 0) {
                                        continue;
                                    }
                                    var px = 2 + g * per + j;
                                    checked++;
                                    if (f - (1 << (fieldBits - 1)) == sample.at(y, x0 + px) - sample.at(y, x0 + px - 2)) {
                                        hits++;
                                    }
                                }
                            }
                        }
                    }
                    result.add(new Split(groups, codeBits, fieldBits, checked == 0 ? 0 : (double) hits / checked));
                }
            }
        }
        result.sort((a, b) -> Double.compare(b.score, a.score));
        return result;
    }

    /**
     * A field of a version 4 block in the format's split: its code, value, the photosite two to
     * the left and the photosite itself.
     *
     * @param code     the group's code
     * @param f        the field
     * @param previous the oracle's photosite two to the left
     * @param value    the oracle's photosite
     * @param column   the column
     */
    record Field(int code, int f, int previous, int value, int column) {
    }

    /**
     * Collects the fields of the plain blocks (both 12-bit values plain) of a sample.
     *
     * @param sample the sample
     * @param rows   the rows collected
     * @return the fields
     */
    static List<Field> fields(Sample sample, int rows) {
        var stream = stream(sample);
        var blocksPerRow = sample.width / 14;
        var result = new ArrayList<Field>();
        for (var y = 0; y < Math.min(rows, sample.oracle.height); y++) {
            for (var k = 0; k < blocksPerRow; k++) {
                var x0 = 14 * k;
                if (x0 + 14 > sample.oracle.width) {
                    continue;
                }
                var data = Arrays.copyOfRange(stream, (y * blocksPerRow + k) * 16, (y * blocksPerRow + k) * 16 + 16);
                if (field(data, Order.REVERSED_MSB_FIRST, 0, 8) == 0 || field(data, Order.REVERSED_MSB_FIRST, 12, 8) == 0) {
                    continue;
                }
                var position = 24;
                for (var g = 0; g < 4; g++) {
                    var code = field(data, Order.REVERSED_MSB_FIRST, position, 2);
                    position += 2;
                    for (var j = 0; j < 3; j++) {
                        var px = 2 + 3 * g + j;
                        var f = field(data, Order.REVERSED_MSB_FIRST, position, 8);
                        position += 8;
                        result.add(new Field(code, f, sample.at(y, x0 + px - 2), sample.at(y, x0 + px), x0 + px));
                    }
                }
            }
        }
        return result;
    }

    /**
     * A candidate rule for the fields of a code: below the threshold on the photosite two to the
     * left the field is an absolute value shifted left by {@code absShift} (keeping the low bits
     * of the photosite two to the left when {@code keepLow}); from the threshold on, the
     * photosite two to the left plus the field less 128 shifted left by {@code relShift}.
     *
     * @param threshold the threshold, 0 for always relative and {@link Integer#MAX_VALUE} for always
     *                  absolute
     * @param absShift  the shift of an absolute value
     * @param keepLow   whether an absolute value keeps the low bits of the photosite two to the
     *                  left
     * @param relShift  the shift of a difference
     */
    record Rule(int threshold, int absShift, boolean keepLow, int relShift) {
        /**
         * Applies the rule.
         *
         * @param f        the field
         * @param previous the photosite two to the left
         * @return the photosite
         */
        int apply(int f, int previous) {
            if (previous < threshold) {
                return (f << absShift) | (keepLow ? previous & ((1 << absShift) - 1) : 0);
            }
            return previous + ((f - 128) << relShift);
        }
    }

    /**
     * Returns, for each code, the candidate rules that explain every non-zero field of the code,
     * from a family of thresholds ({@code 0}, {@code 64 << s}, {@code 128 << s}, {@code 256 << s}
     * for {@code s} from 0 to 5, and always absolute), absolute shifts and difference shifts from
     * 0 to 5.
     *
     * @param fields the fields
     * @return the rules per code, the simplest first
     */
    static Map<Integer, List<Rule>> codeRules(List<Field> fields) {
        var result = new LinkedHashMap<Integer, List<Rule>>();
        for (var code = 0; code < 4; code++) {
            var mine = new ArrayList<Field>();
            for (var f : fields) {
                if (f.code == code && f.f != 0) {
                    mine.add(f);
                }
            }
            var thresholds = new ArrayList<Integer>();
            thresholds.add(0);
            thresholds.add(Integer.MAX_VALUE);
            for (var s = 0; s <= 5; s++) {
                thresholds.add(64 << s);
                thresholds.add(128 << s);
                thresholds.add(256 << s);
            }
            var rules = new ArrayList<Rule>();
            for (var threshold : thresholds.stream().distinct().toList()) {
                for (var absShift = 0; absShift <= 5; absShift++) {
                    for (var keepLow : new boolean[]{true, false}) {
                        for (var relShift = 0; relShift <= 5; relShift++) {
                            var rule = new Rule(threshold, absShift, keepLow, relShift);
                            if (!mine.isEmpty() && mine.stream().allMatch(f -> rule.apply(f.f, f.previous) == f.value)) {
                                rules.add(rule);
                            }
                        }
                    }
                }
            }
            result.put(code, rules);
        }
        return result;
    }

    /**
     * Counts the fields of 0 of a sample and those whose photosite equals the photosite two to the
     * left, and the smallest column at which a field of 0 occurs.
     *
     * @param fields the fields
     * @return the number of fields of 0, of those repeating the photosite two to the left, and the
     *         smallest column
     */
    static int[] zeroFields(List<Field> fields) {
        var zeros = 0;
        var repeats = 0;
        var column = Integer.MAX_VALUE;
        for (var f : fields) {
            if (f.f == 0) {
                zeros++;
                if (f.value == f.previous) {
                    repeats++;
                }
                column = Math.min(column, f.column);
            }
        }
        return new int[]{zeros, repeats, column};
    }

    /**
     * Counts the blocks of a sample that hold a 12-bit value whose high eight bits are zero, and
     * how many of them the plain reading and the marker reading ({@link PanasonicDecoder#block4})
     * reproduce, photosites the converter filled (zero in the reading) excepted, over the columns
     * of the image area.
     *
     * @param sample the sample
     * @return the number of such blocks, those the plain reading reproduces, those the marker
     *         reading reproduces
     */
    static int[] markerBlocks(Sample sample) {
        var stream = stream(sample);
        var blocksPerRow = sample.width / 14;
        var counts = new int[3];
        var values = new int[14];
        for (var y = 0; y < sample.oracle.height; y++) {
            for (var k = 0; k < blocksPerRow; k++) {
                var x0 = 14 * k;
                if (x0 + 14 > Math.min(sample.right, sample.oracle.width)) {
                    continue;
                }
                var data = Arrays.copyOfRange(stream, (y * blocksPerRow + k) * 16, (y * blocksPerRow + k) * 16 + 16);
                var first = field(data, Order.REVERSED_MSB_FIRST, 0, 8) == 0;
                var second = field(data, Order.REVERSED_MSB_FIRST, 12, 8) == 0;
                if (!first && !second) {
                    continue;
                }
                counts[0]++;
                var plain = new int[14];
                plain[0] = field(data, Order.REVERSED_MSB_FIRST, 0, 12);
                plain[1] = field(data, Order.REVERSED_MSB_FIRST, 12, 12);
                var position = 24;
                for (var g = 0; g < 4; g++) {
                    var code = field(data, Order.REVERSED_MSB_FIRST, position, 2);
                    position += 2;
                    for (var j = 0; j < 3; j++) {
                        var px = 2 + 3 * g + j;
                        plain[px] = PanasonicDecoder.step(field(data, Order.REVERSED_MSB_FIRST, position, 8), code, plain[px - 2]);
                        position += 8;
                    }
                }
                var high = 0L;
                var low = 0L;
                for (var b = 0; b < 8; b++) {
                    high = (high << 8) | (data[15 - b] & 0xFF);
                    low = (low << 8) | (data[7 - b] & 0xFF);
                }
                PanasonicDecoder.block4(high, low, values);
                var plainMatch = true;
                var markerMatch = true;
                for (var px = 0; px < 14; px++) {
                    var oracle = sample.at(y, x0 + px);
                    plainMatch &= plain[px] == 0 || plain[px] == oracle;
                    markerMatch &= values[px] == 0 || values[px] == oracle;
                }
                if (plainMatch) {
                    counts[1]++;
                }
                if (markerMatch) {
                    counts[2]++;
                }
            }
        }
        return counts;
    }

    /**
     * Scores candidate repairs of the photosites the marker reading leaves at zero: the mean of
     * the non-zero neighbors of the color (diagonal for green and two away otherwise, two away
     * for all, or diagonal for all), rounded to nearest or down; over the image area.
     *
     * @param sample the sample
     * @return the number of zero photosites inside the image area and, per candidate, how many
     *         it reproduces
     */
    static Map<String, Integer> repairs(Sample sample) {
        var plane = new SensorPlane(sample.width, sample.height, 1, false, MediaLimits.DEFAULT);
        var stream = stream(sample);
        var blocksPerRow = sample.width / 14;
        var values = new int[14];
        for (var y = 0; y < sample.height; y++) {
            for (var k = 0; k < blocksPerRow; k++) {
                var at = (y * blocksPerRow + k) * 16;
                if (at + 16 > stream.length) {
                    break;
                }
                var high = 0L;
                var low = 0L;
                for (var b = 0; b < 8; b++) {
                    high = (high << 8) | (stream[at + 15 - b] & 0xFF);
                    low = (low << 8) | (stream[at + 7 - b] & 0xFF);
                }
                PanasonicDecoder.block4(high, low, values);
                for (var px = 0; px < 14; px++) {
                    plane.integers[y * sample.width + 14 * k + px] = (short) values[px];
                }
            }
        }
        var result = new LinkedHashMap<String, Integer>();
        var zeros = 0;
        for (var mode = 0; mode < 6; mode++) {
            var hits = 0;
            for (var y = 0; y < sample.oracle.height; y++) {
                for (var x = 0; x < Math.min(sample.right, sample.oracle.width); x++) {
                    if (plane.integers[y * sample.width + x] != 0) {
                        continue;
                    }
                    if (mode == 0) {
                        zeros++;
                    }
                    var green = sample.cfa.color(y % sample.cfa.height(), x % sample.cfa.width()) == CfaColor.GREEN;
                    var diagonal = mode % 3 == 0 ? green : mode % 3 == 2;
                    var sum = 0;
                    var used = 0;
                    for (var n = 0; n < 4; n++) {
                        var dx = diagonal ? ((n & 1) == 0 ? -1 : 1) : (n == 0 ? -2 : n == 1 ? 2 : 0);
                        var dy = diagonal ? (n < 2 ? -1 : 1) : (n == 2 ? -2 : n == 3 ? 2 : 0);
                        var xx = x + dx;
                        var yy = y + dy;
                        if (xx < 0 || yy < 0 || xx >= sample.width || yy >= sample.height) {
                            continue;
                        }
                        var neighbor = plane.integers[yy * sample.width + xx] & 0xFFFF;
                        if (neighbor != 0) {
                            sum += neighbor;
                            used++;
                        }
                    }
                    var filled = used == 0 ? 0 : mode < 3 ? (sum + used / 2) / used : sum / used;
                    if (filled == sample.at(y, x)) {
                        hits++;
                    }
                }
            }
            var name = (mode % 3 == 0 ? "diagonal for green, two away otherwise" : mode % 3 == 1 ? "two away" : "diagonal")
                    + (mode < 3 ? ", rounded" : ", truncated");
            result.put(name, hits);
        }
        result.put("zero photosites", zeros);
        return result;
    }

    /**
     * Prints the derivation for a list of RW2 and DNG file pairs.
     *
     * @param args pairs of paths: an RW2 file, then the DNG file the converter wrote from it
     * @throws java.io.IOException if a file cannot be read
     */
    static void main(String[] args) throws java.io.IOException {
        for (var index = 0; index + 1 < args.length; index += 2) {
            var sample = load(Path.of(args[index]), Path.of(args[index + 1]));
            System.out.println(sample.name + " version " + sample.version + " photosites per block " + photositesPerBlock(sample));
            var layouts = chunkLayout(sample, 3);
            System.out.println("  chunk layouts " + layouts.subList(0, 3));
            if (sample.version != 4) {
                continue;
            }
            System.out.println("  block layouts " + blockLayouts(sample, 64).subList(0, 4));
            var fields = fields(sample, sample.height);
            codeRules(fields).forEach((code, rules) -> System.out.println("  code " + code + " " + rules));
            System.out.println("  zero fields " + Arrays.toString(zeroFields(fields)) + " right edge " + sample.right);
            System.out.println("  marker blocks " + Arrays.toString(markerBlocks(sample)));
            System.out.println("  repairs " + repairs(sample));
        }
    }
}
