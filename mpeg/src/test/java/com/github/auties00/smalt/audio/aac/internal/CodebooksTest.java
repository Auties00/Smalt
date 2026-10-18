package com.github.auties00.smalt.audio.aac.internal;

import com.github.auties00.smalt.internal.bits.MsbBitWriter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the Huffman codebooks against the properties ISO/IEC 14496-3 Annex 4.A guarantees and
 * the decoding tables and bit counters against the codeword tables.
 */
class CodebooksTest {
    // the values per codeword and the largest absolute value of each spectrum codebook (Table 4.A.1)
    private static final int[] DIMENSION = {0, 4, 4, 4, 4, 2, 2, 2, 2, 2, 2, 2};
    private static final int[] LARGEST_VALUE = {0, 1, 1, 2, 2, 4, 4, 7, 7, 12, 12, 16};

    static int[] values(int book, int symbol) {
        if (DIMENSION[book] == 4) {
            var offset = Codebooks.isUnsigned(book) ? 0 : 1;
            return new int[]{symbol / 27 - offset, symbol / 9 % 3 - offset, symbol / 3 % 3 - offset, symbol % 3 - offset};
        }
        var lav = LARGEST_VALUE[book];
        var modulo = Codebooks.isUnsigned(book) ? lav + 1 : 2 * lav + 1;
        var offset = Codebooks.isUnsigned(book) ? 0 : lav;
        return new int[]{symbol / modulo - offset, symbol % modulo - offset};
    }

    @Nested
    @DisplayName("tables")
    class Tables {
        @ParameterizedTest(name = "codebook {0}")
        @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11})
        @DisplayName("are complete prefix codes (Kraft sum exactly 1, no codeword prefixes another)")
        void complete(int book) {
            var codes = book == 0 ? HuffmanTables.SCALEFACTOR_CODES : HuffmanTables.SPECTRUM_CODES[book];
            var lengths = book == 0 ? HuffmanTables.SCALEFACTOR_LENGTHS : HuffmanTables.SPECTRUM_LENGTHS[book];
            double kraft = 0;
            for (var length : lengths) {
                kraft += Math.pow(2, -length);
            }
            assertEquals(1.0, kraft, 1e-12);
            for (var a = 0; a < codes.length; a++) {
                for (var b = 0; b < codes.length; b++) {
                    if (a != b && lengths[a] <= lengths[b]) {
                        assertTrue(codes[b] >>> (lengths[b] - lengths[a]) != codes[a], "codeword " + a + " prefixes " + b);
                    }
                }
            }
        }

        @Test
        @DisplayName("have the codebook sizes of the specification")
        void sizes() {
            int[] expected = {0, 81, 81, 81, 81, 81, 81, 64, 64, 169, 169, 289};
            for (var book = 1; book <= 11; book++) {
                assertEquals(expected[book], HuffmanTables.SPECTRUM_CODES[book].length);
            }
            assertEquals(121, HuffmanTables.SCALEFACTOR_CODES.length);
            assertEquals(1, HuffmanTables.SCALEFACTOR_LENGTHS[60], "a zero difference costs one bit");
        }
    }

    @Nested
    @DisplayName("decoding")
    class Decoding {
        @ParameterizedTest(name = "codebook {0}")
        @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11})
        @DisplayName("decodes every codeword of a spectrum codebook to its values and non-zero count")
        void spectrum(int book) {
            var codes = HuffmanTables.SPECTRUM_CODES[book];
            var lengths = HuffmanTables.SPECTRUM_LENGTHS[book];
            var writer = new MsbBitWriter();
            for (var symbol = 0; symbol < codes.length; symbol++) {
                writer.write(codes[symbol], lengths[symbol]);
            }
            var reader = new AacBitReader(writer.toByteArray());
            var table = Codebooks.spectrumTable(book);
            for (var symbol = 0; symbol < codes.length; symbol++) {
                var payload = Codebooks.decode(reader, table) >>> 5;
                var expected = values(book, symbol);
                var nonZero = 0;
                for (var i = 0; i < expected.length; i++) {
                    var field = payload >>> (5 * i) & 31;
                    var value = Codebooks.isUnsigned(book) ? field : field << 27 >> 27;
                    assertEquals(expected[i], value, "symbol " + symbol + " value " + i);
                    if (expected[i] != 0) {
                        nonZero++;
                    }
                }
                assertEquals(nonZero, payload >>> 20, "non-zero count of symbol " + symbol);
            }
        }

        @Test
        @DisplayName("decodes every scalefactor difference")
        void scalefactors() {
            var writer = new MsbBitWriter();
            for (var delta = -60; delta <= 60; delta++) {
                writer.write(HuffmanTables.SCALEFACTOR_CODES[delta + 60], HuffmanTables.SCALEFACTOR_LENGTHS[delta + 60]);
            }
            var reader = new AacBitReader(writer.toByteArray());
            for (var delta = -60; delta <= 60; delta++) {
                assertEquals(delta, Codebooks.decodeScalefactor(reader));
            }
        }
    }

    @Nested
    @DisplayName("spectral coder")
    class Coder {
        @ParameterizedTest(name = "largest magnitude {0}")
        @ValueSource(ints = {0, 1, 2, 4, 7, 12, 16, 100, 8191})
        @DisplayName("counts exactly the bits it writes, for every eligible codebook")
        void countMatchesWrite(int largest) {
            var random = new Random(largest);
            var quant = new int[64];
            var peak = 0;
            for (var i = 0; i < quant.length; i++) {
                var magnitude = random.nextInt(largest + 1);
                if (random.nextInt(4) == 0) {
                    magnitude = 0;
                }
                quant[i] = random.nextBoolean() ? -magnitude : magnitude;
                peak = Math.max(peak, magnitude);
            }
            var costs = new int[SpectralCoder.BOOKS];
            SpectralCoder.countBand(quant, 0, quant.length, peak, costs, 0, true);
            for (var book = 1; book <= 11; book++) {
                if (costs[book] >= SpectralCoder.FORBIDDEN) {
                    assertTrue(book != 11 && LARGEST_VALUE[book] < peak, "codebook " + book + " forbidden");
                    continue;
                }
                var writer = new MsbBitWriter();
                SpectralCoder.writeBand(writer, quant, 0, quant.length, book);
                assertEquals(costs[book], writer.bitCount(), "codebook " + book);
            }
        }

        @Test
        @DisplayName("plans sections no costlier than coding each band alone and writes them decodably")
        void plan() {
            var random = new Random(7);
            var bands = 49;
            var costs = new int[bands * SpectralCoder.BOOKS];
            for (var i = 0; i < costs.length; i++) {
                costs[i] = random.nextInt(8) == 0 ? SpectralCoder.FORBIDDEN : random.nextInt(200);
            }
            for (var band = 0; band < bands; band++) {
                costs[band * SpectralCoder.BOOKS + 11] = 150 + random.nextInt(100);
            }
            var books = new int[bands];
            var total = new SpectralCoder().plan(costs, bands, false, books);
            var alone = 0;
            for (var band = 0; band < bands; band++) {
                var cheapest = Integer.MAX_VALUE;
                for (var book = 0; book < SpectralCoder.BOOKS; book++) {
                    cheapest = Math.min(cheapest, costs[band * SpectralCoder.BOOKS + book]);
                }
                alone += cheapest + SpectralCoder.sectionHeaderBits(1, false);
                assertTrue(costs[band * SpectralCoder.BOOKS + books[band]] < SpectralCoder.FORBIDDEN);
            }
            assertTrue(total <= alone);
            var writer = new MsbBitWriter();
            SpectralCoder.writeSections(writer, books, bands, false);
            var reader = new AacBitReader(writer.toByteArray());
            var band = 0;
            while (band < bands) {
                var book = reader.read(4);
                var length = 0;
                int increment;
                while ((increment = reader.read(5)) == 31) {
                    length += 31;
                }
                length += increment;
                for (var i = 0; i < length; i++) {
                    assertEquals(books[band + i], book);
                }
                band += length;
            }
            assertEquals(bands, band);
        }
    }
}
