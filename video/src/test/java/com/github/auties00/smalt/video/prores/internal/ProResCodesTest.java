package com.github.auties00.smalt.video.prores.internal;

import com.github.auties00.smalt.video.intra.internal.IntraBitReader;
import com.github.auties00.smalt.video.intra.internal.IntraBitWriter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Checks the Golomb-Rice/exponential-Golomb combination codes of SMPTE RDD 36 clause 7.1.1 against
 * hand-computed codewords and in round trips for every codebook of the tables.
 */
@DisplayName("ProRes entropy codes")
class ProResCodesTest {
    static String encode(int codebook, int symbol) {
        var writer = new IntraBitWriter(16);
        ProResCodes.encode(writer, codebook, symbol);
        var bits = (int) writer.bitCount();
        var bytes = writer.toByteArray();
        var text = new StringBuilder();
        for (var i = 0; i < bits; i++) {
            text.append((bytes[i >> 3] >> (7 - (i & 7))) & 1);
        }
        return text.toString();
    }

    @ParameterizedTest(name = "{0} codes {1} as {2}")
    @CsvSource({
            // EXP_GOLOMB_CODE(0): 0 -> 1, 1 -> 010, 2 -> 011, 3 -> 00100
            "0, 0, 1", "0, 1, 010", "0, 2, 011", "0, 3, 00100",
            // RICE_EXP_COMBO_CODE(1, 2, 3), packed 0x223: Rice order 2 below 8, then exp-Golomb order 3
            "547, 0, 100", "547, 5, 0101", "547, 7, 0111", "547, 8, 001000", "547, 16, 00010000",
            // RICE_EXP_COMBO_CODE(2, 0, 1), packed 0x301: unary below 3, then exp-Golomb order 1
            "769, 0, 1", "769, 1, 01", "769, 2, 001", "769, 3, 00010", "769, 5, 0000100"})
    @DisplayName("produce the codewords of RDD 36 clause 7.1.1.1")
    void codewords(int codebook, int symbol, String codeword) {
        assertEquals(codeword, encode(codebook, symbol));
        assertEquals(codeword.length(), ProResCodes.length(codebook, symbol));
    }

    @Test
    @DisplayName("decode what they encode for every codebook")
    void roundTrip() {
        var codebooks = new ArrayList<Integer>(List.of(ProResTables.FIRST_DC));
        for (var codebook : ProResTables.DC_DIFFERENCE) {
            codebooks.add(codebook);
        }
        for (var codebook : ProResTables.RUN) {
            codebooks.add(codebook);
        }
        for (var codebook : ProResTables.LEVEL) {
            codebooks.add(codebook);
        }
        var symbols = new ArrayList<Integer>();
        for (var symbol = 0; symbol < 3000; symbol++) {
            symbols.add(symbol);
        }
        symbols.addAll(List.of(65535, 1 << 20, (1 << 24) - 1));
        for (int codebook : codebooks) {
            var writer = new IntraBitWriter(64);
            var bits = 0L;
            for (int symbol : symbols) {
                ProResCodes.encode(writer, codebook, symbol);
                bits += ProResCodes.length(codebook, symbol);
            }
            assertEquals(bits, writer.bitCount());
            var bytes = writer.toByteArray();
            var reader = new IntraBitReader();
            reader.reset(bytes, 0, bytes.length);
            for (int symbol : symbols) {
                assertEquals(symbol, ProResCodes.decode(reader, codebook), "codebook " + codebook);
            }
        }
    }

    @Test
    @DisplayName("map signed values to symbols as RDD 36 Table 8")
    void signedMapping() {
        int[][] table = {{0, 0}, {-1, 1}, {1, 2}, {-2, 3}, {2, 4}, {-3, 5}, {3, 6}};
        for (var row : table) {
            assertEquals(row[1], ProResCodes.toSymbol(row[0]));
            assertEquals(row[0], ProResCodes.fromSymbol(row[1]));
        }
    }

    @Test
    @DisplayName("reject prefixes longer than any valid codeword")
    void invalid() {
        var reader = new IntraBitReader();
        reader.reset(new byte[16], 0, 16);
        assertEquals(ProResCodes.INVALID, ProResCodes.decode(reader, ProResTables.FIRST_DC));
    }

    @Test
    @DisplayName("peek the symbol and length that decode consumes, also for prefixes no codeword has")
    void peekEqualsDecode() {
        var codebooks = new ArrayList<Integer>(List.of(ProResTables.FIRST_DC));
        for (var codebook : ProResTables.DC_DIFFERENCE) {
            codebooks.add(codebook);
        }
        for (var codebook : ProResTables.RUN) {
            codebooks.add(codebook);
        }
        for (var codebook : ProResTables.LEVEL) {
            codebooks.add(codebook);
        }
        var random = new java.util.Random(7);
        var reader = new IntraBitReader();
        var bytes = new byte[16];
        for (var round = 0; round < 20_000; round++) {
            random.nextBytes(bytes);
            var zeros = random.nextInt(40);
            for (var bit = 0; bit < zeros; bit++) {
                bytes[bit >> 3] &= (byte) ~(0x80 >> (bit & 7));
            }
            for (var codebook : codebooks) {
                reader.reset(bytes, 0, bytes.length);
                var window = reader.window();
                var packed = ProResCodes.peek(window, codebook);
                var symbol = ProResCodes.decode(reader, codebook);
                if (symbol == ProResCodes.INVALID) {
                    assertEquals(true, packed < 0);
                } else {
                    assertEquals(symbol, (int) (packed >>> 8));
                    assertEquals(reader.position(), (int) (packed & 0xFF));
                }
            }
        }
    }
}
