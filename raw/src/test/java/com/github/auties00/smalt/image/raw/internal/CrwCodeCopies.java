package com.github.auties00.smalt.image.raw.internal;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.io.MediaSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Writes copies of CRW sample files whose compressed streams use every code of the file's table set, the codes no
 * sample file uses among them, so that Adobe DNG Converter's values for the copies check the decoder's tables.
 *
 * <p>A copy keeps the file and its stream's bits, except in a region of whole rows from the middle row on, coded with
 * the first code of each value where no code is chosen, and the difference of the first block after it. There every
 * block is flat at 512 and some blocks carry the
 * code under test: an AC code of run {@code r} and category {@code s} gives the coefficient {@code -2^(s-1)} at index
 * {@code r + 1}, which a coefficient {@code 2^(s-1)} two photosites later takes back, a code that ends the block
 * ends an empty block, a code of {@link CrwTables#ZERO} or a run of 16 zeros is followed by the coefficients -1 and
 * 1, and a DC code of category {@code c} returns the block's first value to zero from {@code 2^(c-1)}, a value the
 * blocks before it raise the chain to; the rest of the region holds empty blocks. Category 11 DC codes are left
 * out: a 10-bit photosite cannot take a difference of 1024, and a block that would take a photosite out of 0 to
 * 1023 is preceded by empty blocks. {@code tools/testdata/raw-oracle.py} builds the same copies ({@code
 * crw_code_copy}) and converts them; both check the copies' SHA-256 ({@link #DIGESTS}).
 */
final class CrwCodeCopies {
    /**
     * The flat value of the region.
     */
    private static final int FLAT = 512;

    /**
     * Prevents instantiation.
     */
    private CrwCodeCopies() {
    }

    /**
     * Writes the copies into a directory.
     *
     * @param args the directory, then the CRW files
     * @throws IOException if a file cannot be read or written
     */
    public static void main(String[] args) throws IOException {
        var directory = Path.of(args[0]);
        Files.createDirectories(directory);
        for (var index = 1; index < args.length; index++) {
            var source = Path.of(args[index]);
            var name = source.getFileName().toString();
            var copy = copy(source);
            Files.write(directory.resolve(name(name)), copy);
            System.out.println(name(name) + " " + sha256(copy));
        }
    }

    /**
     * Returns the name of the copy of a sample file.
     *
     * @param sample the sample's file name
     * @return the copy's file name
     */
    static String name(String sample) {
        return "codes-" + sample.substring(sample.indexOf('-') + 1);
    }

    /**
     * The SHA-256 of the copies the oracle script converted, by copy name.
     */
    static final Map<String, String> DIGESTS = Map.of(
            "codes-canon-powershot-g1-raw-4-3.crw", "b75f1cab12dac178165d615aa37cd8c5b1a4e2c347712a2418eafc8201832b61",
            "codes-canon-eos-d60-raw-3-2.crw", "a730b913631e0486f6980e0827645e673712f4d8acf9b9753c42302b0aaf9bc4",
            "codes-canon-eos-10d-raw-3-2-3164.crw", "b13377268ae4c9d16b1dbd905220d40108bb6ad71941278760259d06e35a4a6e");

    /**
     * Returns the copy of a CRW file.
     *
     * @param path the file
     * @return the copy
     */
    static byte[] copy(Path path) {
        return build(path).data();
    }

    /**
     * Returns the rows of a copy's region, from its first row to the row after its last.
     *
     * @param path the file
     * @return the first row and the row after the last
     */
    static int[] region(Path path) {
        var copy = build(path);
        return new int[]{copy.firstRow(), copy.endRow()};
    }

    /**
     * Returns whether a CRW file holds 12-bit photosites.
     *
     * @param path the file
     * @return whether the file stores the two low bits of each photosite
     */
    static boolean twelve(Path path) {
        try (var source = MediaSource.of(path)) {
            return CrwParser.layout(source, MediaLimits.DEFAULT).lowBits() >= 0;
        }
    }

    /**
     * Returns the SHA-256 of bytes.
     *
     * @param data the bytes
     * @return the digest in lowercase hexadecimal
     */
    static String sha256(byte[] data) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(data));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }

    /**
     * A copy and its region.
     *
     * @param data the copy
     * @param firstRow the region's first row
     * @param endRow the row after the region's last
     */
    private record Copy(byte[] data, int firstRow, int endRow) {
    }

    private static Copy build(Path path) {
        byte[] data;
        try {
            data = Files.readAllBytes(path);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        CrwParser.Layout layout;
        try (var source = MediaSource.of(data)) {
            layout = CrwParser.layout(source, MediaLimits.DEFAULT);
        }
        var width = layout.width();
        var total = width * layout.height();
        if (total % 64 != 0) {
            throw new IllegalArgumentException("the sensor does not hold whole blocks");
        }
        var blocks = total / 64;
        var room = (int) layout.streamLength();
        var stream = Arrays.copyOfRange(data, (int) layout.stream(), (int) layout.stream() + room);
        var bits = new BitReader(Arrays.copyOf(stream, CrwParser.unstuff(stream, stream.length)));
        var dc = codes(CrwTables.TABLES[2 * layout.table()]);
        var ac = codes(CrwTables.TABLES[2 * layout.table() + 1]);
        // the region: whole rows from the middle row on, starting and ending at block boundaries
        var rows = rowsPerBoundary(width);
        var start = (layout.height() / 2 / rows) * rows * width / 64;
        var queue = design(dc, ac);
        var region = new ArrayList<Block>();
        var block = start;
        while (!queue.isEmpty() || (64L * block) % width != 0) {
            var unit = queue.isEmpty() ? List.of(Block.empty()) : queue.getFirst();
            if (fits(unit, block, width)) {
                queue.pollFirst();
            } else {
                unit = List.of(Block.empty());
            }
            region.addAll(unit);
            block += unit.size();
        }
        var end = block;
        if (end >= blocks) {
            throw new IllegalArgumentException("the image is too small for the region");
        }
        // the file's blocks: where each starts and its first value
        var firsts = new int[blocks];
        var starts = new long[blocks + 1];
        var dcEnds = new long[blocks];
        var first = 0;
        for (var b = 0; b <= end; b++) {
            starts[b] = bits.position;
            first += Block.extend(bits, bits.decode(dc));
            firsts[b] = first;
            dcEnds[b] = bits.position;
            for (var index = 1; index < 64; index++) {
                var value = bits.decode(ac);
                if (value == 0) {
                    break;
                }
                if (value == CrwTables.ZERO) {
                    continue;
                }
                index += value >>> 4;
                bits.position += value & 15;
            }
        }
        var writer = new BitWriter();
        bits.copy(writer, 0, starts[start]);
        var previous = firsts[start - 1];
        for (var content : region) {
            content.write(writer, previous, dc, ac);
            previous = content.first;
        }
        var difference = firsts[end] - previous;
        var category = Block.category(difference);
        var code = Block.primary(dc, category);
        writer.write(code.code(), code.length());
        writer.write(Block.field(difference, category), category);
        bits.copy(writer, dcEnds[end], bits.length());
        var copy = data.clone();
        var out = writer.stuffed();
        if (out.length > room) {
            throw new IllegalStateException("the copy's stream is longer than the file's");
        }
        System.arraycopy(out, 0, copy, (int) layout.stream(), out.length);
        return new Copy(copy, (int) (64L * start / width), (int) (64L * end / width));
    }

    // whether a unit of blocks placed at a block keeps the ten high bits from 0 to 1023; the region's rows start at
    // the flat value, and its blocks before the unit leave the chains at it
    private static boolean fits(List<Block> unit, int block, int width) {
        var high = new int[unit.size() * 64];
        for (var position = 0; position < high.length; position++) {
            var absolute = 64L * block + position;
            var column = (int) (absolute % width);
            var predictor = column < 2 || position < 2 ? FLAT : high[position - 2];
            var value = predictor + unit.get(position / 64).values[position % 64];
            if (value < 0 || value > 1023) {
                return false;
            }
            high[position] = value;
        }
        return true;
    }

    /**
     * Reads the bits of a stream with its stuffed bytes removed, most significant first.
     */
    private static final class BitReader {
        private final byte[] data;
        long position;

        BitReader(byte[] data) {
            this.data = data;
        }

        long length() {
            return 8L * data.length;
        }

        int bit(long at) {
            return at < 8L * data.length ? (data[(int) (at >>> 3)] >> (7 - (int) (at & 7))) & 1 : 0;
        }

        int read(int count) {
            var value = 0;
            for (var k = 0; k < count; k++) {
                value = (value << 1) | bit(position++);
            }
            return value;
        }

        int decode(List<Code> codes) {
            var code = 0;
            for (var length = 1; length <= 16; length++) {
                code = (code << 1) | bit(position++);
                for (var candidate : codes) {
                    if (candidate.length() == length && candidate.code() == code) {
                        return candidate.value();
                    }
                }
            }
            throw new IllegalStateException("no code at bit " + position);
        }

        void copy(BitWriter writer, long from, long to) {
            for (var at = from; at < to; at++) {
                writer.write(bit(at), 1);
            }
        }
    }

    // the rows after which a row starts at a block boundary
    private static int rowsPerBoundary(int width) {
        var rows = 1;
        while (rows * width % 64 != 0) {
            rows++;
        }
        return rows;
    }

    /**
     * Returns the codes of a table, in code order.
     *
     * @param spec the table
     * @return the codes
     */
    static List<Code> codes(int[] spec) {
        var codes = new ArrayList<Code>();
        var code = 0;
        var index = 16;
        for (var length = 1; length <= 16; length++) {
            for (var count = 0; count < spec[length - 1]; count++) {
                codes.add(new Code(code, length, spec[index++]));
                code++;
            }
            code <<= 1;
        }
        return codes;
    }

    /**
     * A code of a table.
     *
     * @param code the bits, right-aligned
     * @param length the bit length
     * @param value the code's value
     */
    record Code(int code, int length, int value) {
    }

    // the blocks of the region: one or more for each code
    private static java.util.LinkedList<List<Block>> design(List<Code> dc, List<Code> ac) {
        var blocks = new java.util.LinkedList<List<Block>>();
        for (var code : ac) {
            var value = code.value();
            if (value == 0) {
                var block = Block.empty();
                block.end = code;
                blocks.add(List.of(block));
            } else if (value == CrwTables.ZERO || value == 0xF0) {
                var at = value == 0xF0 ? 17 : 2;
                var block = Block.empty();
                block.forced.put(1, code);
                block.values[at] = -1;
                block.values[at + 2] = 1;
                block.skip = value == 0xF0 ? 16 : 1;
                blocks.add(List.of(block));
            } else {
                var run = value >>> 4;
                var size = value & 15;
                var block = Block.empty();
                block.values[run + 1] = -(1 << (size - 1));
                block.values[run + 3] = 1 << (size - 1);
                block.forced.put(run + 1, code);
                blocks.add(List.of(block));
            }
        }
        for (var code : dc) {
            var category = code.value();
            if (category == 0) {
                var block = Block.empty();
                block.dcCode = code;
                blocks.add(List.of(block));
            } else if (category <= 10) {
                var raise = 1 << (category - 1);
                var lower = Block.empty();
                lower.values[62] = -raise;
                var raised = Block.empty();
                raised.values[0] = raise;
                var back = Block.empty();
                back.dcCode = code;
                blocks.add(List.of(lower, raised, back));
            }
        }
        return blocks;
    }

    /**
     * The 64 coefficients of a block, the first an absolute value, and the codes it must use.
     */
    private static final class Block {
        final int[] values = new int[64];
        final Map<Integer, Code> forced = new TreeMap<>();
        Code dcCode;
        Code end;
        int skip;
        int first;

        static Block empty() {
            return new Block();
        }

        void write(BitWriter writer, int previous, List<Code> dc, List<Code> ac) {
            first = values[0];
            var difference = first - previous;
            var category = category(difference);
            var code = dcCode != null ? dcCode : primary(dc, category);
            if (code.value() != category) {
                throw new IllegalStateException("DC code of category " + code.value() + " for " + difference);
            }
            writer.write(code.code(), code.length());
            writer.write(field(difference, category), category);
            var index = 1;
            var last = 63;
            while (last > 0 && values[last] == 0) {
                last--;
            }
            while (index <= last) {
                var forcedCode = forced.get(index);
                if (forcedCode != null && skip > 0) {
                    writer.write(forcedCode.code(), forcedCode.length());
                    index += skip;
                    continue;
                }
                var run = 0;
                while (values[index + run] == 0) {
                    run++;
                }
                while (run >= 16) {
                    var zrl = primary(ac, 0xF0);
                    writer.write(zrl.code(), zrl.length());
                    run -= 16;
                    index += 16;
                }
                var value = values[index + run];
                var size = category(value);
                var symbol = (run << 4) | size;
                var chosen = forced.get(index + run);
                if (chosen == null || chosen.value() != symbol) {
                    chosen = primary(ac, symbol);
                }
                writer.write(chosen.code(), chosen.length());
                writer.write(field(value, size), size);
                index += run + 1;
            }
            if (index <= 63) {
                var eob = end != null ? end : primary(ac, 0);
                writer.write(eob.code(), eob.length());
            }
        }

        static int extend(BitReader bits, int category) {
            var value = bits.read(category);
            return category == 0 || value >= 1 << (category - 1) ? value : value - (1 << category) + 1;
        }

        static Code primary(List<Code> codes, int value) {
            for (var code : codes) {
                if (code.value() == value) {
                    return code;
                }
            }
            throw new IllegalStateException("no code of value " + value);
        }

        static int category(int value) {
            return value == 0 ? 0 : 32 - Integer.numberOfLeadingZeros(Math.abs(value));
        }

        static int field(int value, int size) {
            return value >= 0 ? value : value + (1 << size) - 1;
        }
    }

    /**
     * Writes bits most significant first and stuffs a zero byte after every {@code FF} byte.
     */
    private static final class BitWriter {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private long buffer;
        private int count;

        void write(int bits, int length) {
            for (var bit = length - 1; bit >= 0; bit--) {
                buffer = (buffer << 1) | ((bits >>> bit) & 1);
                if (++count == 8) {
                    var b = (int) buffer & 0xFF;
                    out.write(b);
                    if (b == 0xFF) {
                        out.write(0);
                    }
                    buffer = 0;
                    count = 0;
                }
            }
        }

        byte[] stuffed() {
            while (count != 0) {
                write(1, 1);
            }
            return out.toByteArray();
        }
    }
}
