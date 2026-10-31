package com.github.auties00.smalt.image.raw.internal;

import com.github.auties00.smalt.testkit.Skip;
import com.github.auties00.smalt.image.raw.RawSamples;
import com.github.auties00.smalt.testkit.Conformance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the Huffman tables of {@link NikonTables}: their structure and, against Adobe DNG
 * Converter as a black-box oracle, that {@link NikonTableDerivation} re-derives every table from
 * the CC0 witness samples and that the decoder reproduces the values the converter stores. The
 * oracle files are the uncompressed DNG files {@code tools/testdata/nef-oracle.py} writes into
 * {@code <conformance cache>/nef-oracle} ({@code -Dsmalt.test.nefOracle} overrides the directory);
 * the oracle tests are skipped without them.
 */
class NikonTablesTest {
    private static final Path ORACLE = Path.of(System.getProperty("smalt.test.nefOracle",
            Conformance.directory().resolve("nef-oracle").toString()));

    private static Path oracle(RawSamples sample) {
        var name = sample.fileName();
        var path = ORACLE.resolve(name.substring(0, name.length() - 4) + ".dng");
        Skip.unless(Files.isRegularFile(path), "data:nef-oracle", "no oracle file " + path + "; run tools/testdata/nef-oracle.py");
        return path;
    }

    @Nested
    @DisplayName("structure")
    class Structure {
        @ParameterizedTest(name = "table {0}")
        @ValueSource(ints = {0, 1, 2, 3, 4, 5})
        @DisplayName("is a complete canonical prefix code in its shortest completion")
        void complete(int table) {
            var spec = NikonTables.TABLES[table];
            var codes = NikonTableDerivation.codes(spec);
            var kraft = 0.0;
            for (var key : codes.keySet()) {
                kraft += Math.scalb(1.0, -NikonTableDerivation.length(key));
            }
            assertEquals(1.0, kraft);
            var observed = new TreeMap<Long, Integer>();
            codes.forEach((key, value) -> {
                if (value != NikonTables.UNDERIVED) {
                    observed.put(key, value);
                }
            });
            assertArrayEquals(spec, NikonTableDerivation.complete(observed));
        }

        @ParameterizedTest(name = "table {0}")
        @ValueSource(ints = {0, 1, 2, 3, 4, 5})
        @DisplayName("holds each category once, within the bit depth, shifted only after the split row")
        void values(int table) {
            var bits = table < 3 ? 12 : 14;
            var split = table == 1 || table == 4;
            var seen = new HashSet<Integer>();
            for (var value : NikonTableDerivation.codes(NikonTables.TABLES[table]).values()) {
                if (value == NikonTables.UNDERIVED) {
                    continue;
                }
                var category = value & 15;
                var shift = value >>> 4;
                assertTrue(seen.add(category), "category " + category + " twice");
                assertTrue(category <= bits, "category " + category);
                assertTrue(shift == 0 || (split && shift < category), "shift " + shift + " of category " + category);
            }
        }
    }

    @Nested
    @DisplayName("against Adobe DNG Converter")
    class Oracle {
        @Test
        @DisplayName("re-deriving the codes from the witness samples reproduces every table")
        void derivation() {
            var witnesses = List.of(RawSamples.NEF_D5600_LOSSY_12_SPLIT, RawSamples.NEF_Z6II_LOSSY_12_SPLIT,
                    RawSamples.NEF_D300S_LOSSLESS_12, RawSamples.NEF_D7000_LOSSY_14_SPLIT, RawSamples.NEF_D300S_LOSSLESS_14);
            var tables = new ArrayList<Map<Long, Integer>>();
            for (var index = 0; index < 6; index++) {
                tables.add(new TreeMap<>());
            }
            for (var sample : witnesses) {
                var problem = NikonTableDerivation.problem(sample.fetch(), oracle(sample), 0);
                var result = NikonTableDerivation.derive(problem);
                assertNotNull(result, sample + " has no consistent code");
                assertTrue(result.endBit() <= result.totalBit() && result.totalBit() - result.endBit() < 8,
                        sample + " ends at bit " + result.endBit() + " of " + result.totalBit());
                NikonTableDerivation.merge(tables, problem.compressed(), result);
            }
            for (var index = 0; index < 6; index++) {
                assertArrayEquals(NikonTables.TABLES[index], NikonTableDerivation.complete(tables.get(index)), "table " + index);
            }
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(value = RawSamples.class, names = {"NEF_D300S_LOSSLESS_12", "NEF_D300S_LOSSLESS_14", "NEF_D5600_LOSSY_12_SPLIT",
                "NEF_D7000_LOSSLESS_14", "NEF_D7000_LOSSY_12", "NEF_D7000_LOSSY_14_SPLIT", "NEF_D70S_LOSSY_12", "NEF_Z6II_LOSSY_12_SPLIT"})
        @DisplayName("the decoder reproduces the stored values of the sample")
        void decoding(RawSamples sample) {
            var oracle = NikonTableDerivation.oracle(oracle(sample));
            var decoded = NikonTableDerivation.decode(sample.fetch());
            assertArrayEquals(new int[]{0, 0}, NikonTableDerivation.locate(decoded, oracle));
        }
    }
}
