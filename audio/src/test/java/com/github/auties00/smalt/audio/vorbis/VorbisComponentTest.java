package com.github.auties00.smalt.audio.vorbis;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;

import com.github.auties00.smalt.internal.bits.LsbBitWriter;
import com.github.auties00.smalt.internal.header.XiphLacing;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests of the Vorbis building blocks against the specification's worked examples and hand
 * computed values: codeword assignment and table-driven Huffman decoding (including codewords
 * beyond the direct table, the single-entry errata and end-of-packet), the codebook float format,
 * lattice sizes and value vectors, the floor 1 integer line algorithms and the Xiph lacing split.
 */
class VorbisComponentTest {
    private static final AllocationBudget BUDGET = new AllocationBudget(MediaLimits.DEFAULT);

    /** Computes the codewords the specification assigns, as MSB-first strings. */
    private static String[] codewords(int[] lengths) {
        var codes = new String[lengths.length];
        var marker = new long[33];
        for (var i = 0; i < lengths.length; i++) {
            var length = lengths[i];
            if (length == 0) {
                continue;
            }
            var code = marker[length];
            var text = Long.toBinaryString(code);
            codes[i] = "0".repeat(length - text.length()) + text;
            for (var j = length; j > 0; j--) {
                if ((marker[j] & 1) != 0) {
                    marker[j] = j == 1 ? marker[1] + 1 : marker[j - 1] << 1;
                    break;
                }
                marker[j]++;
            }
            for (var j = length + 1; j < 33; j++) {
                if ((marker[j] >>> 1) != code) {
                    break;
                }
                code = marker[j];
                marker[j] = marker[j - 1] << 1;
            }
        }
        return codes;
    }

    private static void writeCodeword(LsbBitWriter writer, String codeword) {
        for (var c : codeword.toCharArray()) {
            writer.write(c - '0', 1);
        }
    }

    private static Codebook scalar(int... lengths) {
        var bytes = new byte[lengths.length];
        for (var i = 0; i < lengths.length; i++) {
            bytes[i] = (byte) lengths[i];
        }
        return Codebook.build(1, lengths.length, 0, null, bytes, BUDGET);
    }

    @Nested
    @DisplayName("Huffman codebooks")
    class Huffman {
        @Test
        @DisplayName("assigns the codewords of the specification's example (section 3.2.1)")
        void specificationExample() {
            int[] lengths = {2, 4, 4, 4, 4, 2, 3, 3};
            assertArrayEquals(new String[]{"00", "0100", "0101", "0110", "0111", "10", "110", "111"}, codewords(lengths));
            var book = scalar(lengths);
            var writer = new LsbBitWriter();
            int[] sequence = {0, 1, 2, 3, 4, 5, 6, 7, 7, 0, 5, 3};
            for (var entry : sequence) {
                writeCodeword(writer, codewords(lengths)[entry]);
            }
            var reader = new BitReader(writer.toByteArray());
            for (var entry : sequence) {
                assertEquals(entry, book.decode(reader));
            }
        }

        @ParameterizedTest(name = "seed {0}")
        @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8})
        @DisplayName("round-trips random complete codes, including codewords up to 24 bits")
        void randomCodes(int seed) {
            var random = new Random(seed);
            var lengths = VorbisStreamBuilder.randomLengths(random, 2 + random.nextInt(3000));
            var codes = codewords(lengths);
            var book = scalar(lengths);
            var writer = new LsbBitWriter();
            var sequence = new int[4000];
            for (var i = 0; i < sequence.length; i++) {
                sequence[i] = random.nextInt(lengths.length);
                writeCodeword(writer, codes[sequence[i]]);
            }
            var reader = new BitReader(writer.toByteArray());
            for (var i = 0; i < sequence.length; i++) {
                assertEquals(sequence[i], book.decode(reader), "symbol " + i);
            }
            assertTrue(Arrays.stream(lengths).max().getAsInt() > 10 || lengths.length < 20);
        }

        @Test
        @DisplayName("maps sparse entries to their original entry numbers")
        void sparse() {
            int[] lengths = {0, 1, 0, 0, 2, 2, 0};
            var book = scalar(lengths);
            var writer = new LsbBitWriter();
            writeCodeword(writer, "0");
            writeCodeword(writer, "11");
            writeCodeword(writer, "10");
            var reader = new BitReader(writer.toByteArray());
            assertEquals(1, book.decode(reader));
            assertEquals(5, book.decode(reader));
            assertEquals(4, book.decode(reader));
            assertEquals(3, book.usedEntries);
        }

        @Test
        @DisplayName("reads a single-entry book as one bit of either value (errata 20150226)")
        void singleEntry() {
            var book = scalar(0, 0, 1, 0);
            var reader = new BitReader(new byte[]{(byte) 0b10110010});
            for (var i = 0; i < 8; i++) {
                assertEquals(2, book.decode(reader));
            }
            assertEquals(-1, book.decode(reader));
        }

        @Test
        @DisplayName("reports end-of-packet for a codeword crossing the end and for every read after it")
        void endOfPacket() {
            int[] lengths = {1, 2, 3, 3};
            var book = scalar(lengths);
            var writer = new LsbBitWriter();
            writeCodeword(writer, "0");
            writeCodeword(writer, "10");
            writeCodeword(writer, "110");
            writeCodeword(writer, "111");
            var reader = new BitReader(new byte[]{writer.toByteArray()[0]});
            assertEquals(0, book.decode(reader));
            assertEquals(1, book.decode(reader));
            assertEquals(2, book.decode(reader));
            assertEquals(-1, book.decode(reader), "111 crosses the end of the byte");
            assertEquals(-1, book.decode(reader));
        }

        @Test
        @DisplayName("reports end-of-packet for every read of a book without used entries")
        void empty() {
            var book = scalar(0, 0, 0);
            assertEquals(-1, book.decode(new BitReader(new byte[]{0, 0})));
        }

        @Test
        @DisplayName("rejects over- and underspecified lengths")
        void invalid() {
            assertThrows(MediaException.Malformed.class, () -> scalar(1, 1, 2));
            assertThrows(MediaException.Malformed.class, () -> scalar(2, 2, 2));
            assertThrows(MediaException.Malformed.class, () -> scalar(3));
            assertThrows(MediaException.Malformed.class, () -> scalar(32, 32));
        }
    }

    @Nested
    @DisplayName("value lookups")
    class Values {
        @ParameterizedTest(name = "lookup1_values({0}, {1}) = {2}")
        @CsvSource({"16, 2, 4", "17, 2, 4", "15, 2, 3", "1, 5, 1", "8, 3, 2", "26, 3, 2", "27, 3, 3", "0, 1, 0",
                "16777215, 1, 16777215", "16777215, 24, 1", "16777215, 23, 2", "4, 65535, 1", "6561, 8, 3",
                "1000000, 2, 1000", "999999, 2, 999"})
        @DisplayName("computes the largest integer whose power fits the entry count")
        void lookup1(int entries, int dimensions, long expected) {
            assertEquals(expected, Codebook.lookup1Values(entries, dimensions));
        }

        @Test
        @DisplayName("unpacks the codebook float format with its 788 exponent bias")
        void float32() {
            assertEquals(0.0f, Codebook.float32Unpack(0));
            assertEquals(1.0f, Codebook.float32Unpack(788 << 21 | 1));
            assertEquals(-3.0f, Codebook.float32Unpack(0x80000000 | 788 << 21 | 3));
            assertEquals(0.5f, Codebook.float32Unpack(787 << 21 | 1));
            assertEquals((float) Math.scalb(0x1FFFFF, 1023 - 788), Codebook.float32Unpack(0x7FFFFFFF));
            for (var value : new double[]{-1, 0.25, 1.5, -0.0625, 1234.5, 3.0e-5}) {
                assertEquals((float) value, Codebook.float32Unpack(VorbisStreamBuilder.pack(value)), 1e-6 * Math.abs(value));
            }
        }

        @Test
        @DisplayName("expands lattice (type 1) and explicit (type 2) lookups with and without sequence_p")
        void vectors() {
            var lattice = new VorbisStreamBuilder.Book(2, new int[9]);
            Arrays.fill(lattice.lengths, 4);
            lattice.lengths = VorbisStreamBuilder.randomLengths(new Random(1), 9);
            lattice.lookupType = 1;
            lattice.minimum = VorbisStreamBuilder.pack(0.5);
            lattice.delta = VorbisStreamBuilder.pack(1);
            lattice.valueBits = 2;
            lattice.multiplicands = new int[]{0, 1, 2};
            var parsed = parse(lattice);
            // entry 5: multiplicand offsets 5 % 3 = 2 and (5 / 3) % 3 = 1
            assertArrayEquals(new float[]{2.5f, 1.5f}, Arrays.copyOfRange(parsed.values, 10, 12));
            lattice.sequence = true;
            assertArrayEquals(new float[]{2.5f, 4.0f}, Arrays.copyOfRange(parse(lattice).values, 10, 12));
            var explicit = new VorbisStreamBuilder.Book(3, new int[]{1, 2, 2});
            explicit.lookupType = 2;
            explicit.minimum = VorbisStreamBuilder.pack(-1);
            explicit.delta = VorbisStreamBuilder.pack(0.5);
            explicit.valueBits = 3;
            explicit.multiplicands = new int[]{0, 1, 2, 3, 4, 5, 6, 7, 0};
            assertArrayEquals(new float[]{-1, -0.5f, 0, 0.5f, 1, 1.5f, 2, 2.5f, -1}, parse(explicit).values);
            explicit.sequence = true;
            assertArrayEquals(new float[]{-1, -1.5f, -1.5f, 0.5f, 1.5f, 3, 2, 4.5f, 3.5f}, parse(explicit).values);
        }

        private Codebook parse(VorbisStreamBuilder.Book book) {
            var stream = new VorbisStreamBuilder();
            stream.books.add(book);
            var setup = stream.setup();
            var reader = new BitReader(setup, 7, setup.length - 7);
            reader.read(8);
            return Codebook.read(reader, BUDGET);
        }
    }

    @Nested
    @DisplayName("floor 1 integer algorithms")
    class FloorLines {
        @ParameterizedTest(name = "render_point({0}, {1}, {2}, {3}, {4}) = {5}")
        @CsvSource({"0, 0, 10, 5, 3, 1", "0, 10, 10, 0, 3, 7", "0, 0, 128, 255, 127, 253", "16, 40, 48, 40, 20, 40",
                "0, 255, 32768, 0, 1, 255", "0, 0, 32768, 255, 32767, 254"})
        @DisplayName("predicts Y by truncating toward the first point")
        void renderPoint(int x0, int y0, int x1, int y1, int x, int expected) {
            assertEquals(expected, Floor1.renderPoint(x0, y0, x1, y1, x));
        }

        @Test
        @DisplayName("draws lines like the specification's integer algorithm, clipped at n")
        void renderLine() {
            assertArrayEquals(new int[]{0, 0, 1, 1}, line(0, 0, 4, 2, 4));
            assertArrayEquals(new int[]{10, 8, 5, 3}, line(0, 10, 4, 0, 4));
            assertArrayEquals(new int[]{3, 3, 3, 3, 3}, line(0, 3, 5, 3, 5));
            assertArrayEquals(new int[]{0, 16, 33}, line(0, 0, 3, 50, 3));
            assertArrayEquals(new int[]{0, 0, 1}, line(0, 0, 4, 2, 3));
        }

        /** Renders a line over a unit spectrum and maps the multipliers back to table indices. */
        private int[] line(int x0, int y0, int x1, int y1, int n) {
            var spectrum = new float[n];
            Arrays.fill(spectrum, 1);
            Floor1.renderLine(x0, y0, x1, y1, spectrum, n);
            var indices = new int[Math.min(n, x1 - x0)];
            for (var i = 0; i < indices.length; i++) {
                indices[i] = Arrays.binarySearch(Floor1.INVERSE_DB, spectrum[x0 + i]);
            }
            return indices;
        }

        @Test
        @DisplayName("holds the 256 inverse dB steps of section 10.1")
        void table() {
            assertEquals(256, Floor1.INVERSE_DB.length);
            assertEquals(1.0649863e-07f, Floor1.INVERSE_DB[0]);
            assertEquals(1.0f, Floor1.INVERSE_DB[255]);
            for (var i = 1; i < 256; i++) {
                assertEquals(1.0649856, Floor1.INVERSE_DB[i] / (double) Floor1.INVERSE_DB[i - 1], 2e-6);
            }
        }
    }

    @Test
    @DisplayName("splits Xiph lacing with multi-byte sizes")
    void lacing() {
        var first = new byte[600];
        var second = new byte[255];
        var third = new byte[3];
        first[599] = 1;
        second[254] = 2;
        third[2] = 3;
        var packets = VorbisSetup.splitXiphLacing(XiphLacing.lace(first, second, third));
        assertArrayEquals(first, packets[0]);
        assertArrayEquals(second, packets[1]);
        assertArrayEquals(third, packets[2]);
    }
}
