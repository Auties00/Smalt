package com.github.auties00.smalt.image.raw.internal;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.raw.RawSamples;
import com.github.auties00.smalt.io.MediaSource;
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
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Checks the Huffman tables of {@link CrwTables}: their structure and, against Adobe DNG Converter
 * as a black-box oracle, that {@link CrwTableDerivation} re-derives every table from the CC0
 * samples and that the decoder reproduces the values the converter stores for each of them. The
 * oracle files are the uncompressed DNG files {@code tools/testdata/raw-oracle.py} writes into
 * {@code <conformance cache>/raw-oracle} ({@code -Dsmalt.test.rawOracle} overrides the directory);
 * the oracle tests are skipped without them.
 */
class CrwTablesTest {
    private static final Path ORACLE = Path.of(System.getProperty("smalt.test.rawOracle",
            Conformance.directory().resolve("raw-oracle").toString()));

    private static final List<RawSamples> SAMPLES = List.of(RawSamples.CRW_D30, RawSamples.CRW_D60, RawSamples.CRW_10D,
            RawSamples.CRW_10D_3164, RawSamples.CRW_10D_3165, RawSamples.CRW_300D, RawSamples.CRW_300D_3162,
            RawSamples.CRW_DIGITAL_REBEL, RawSamples.CRW_G1, RawSamples.CRW_G5, RawSamples.CRW_G6);

    private static Path oracle(RawSamples sample) {
        var name = sample.fileName();
        var path = ORACLE.resolve(name.substring(0, name.length() - 4) + ".dng");
        assumeTrue(Files.isRegularFile(path), "no oracle file " + path + "; run tools/testdata/raw-oracle.py");
        return path;
    }

    @Nested
    @DisplayName("structure")
    class Structure {
        @ParameterizedTest(name = "table {0}")
        @ValueSource(ints = {0, 1, 2, 3, 4, 5})
        @DisplayName("is a complete canonical prefix code in its shortest completion")
        void complete(int table) {
            var spec = CrwTables.TABLES[table];
            var codes = NikonTableDerivation.codes(spec);
            var kraft = 0.0;
            for (var key : codes.keySet()) {
                kraft += Math.scalb(1.0, -NikonTableDerivation.length(key));
            }
            assertEquals(1.0, kraft);
            var observed = new TreeMap<Long, Integer>();
            codes.forEach((key, value) -> {
                if (value != CrwTables.UNDERIVED) {
                    observed.put(key, value);
                }
            });
            assertArrayEquals(spec, NikonTableDerivation.complete(observed));
        }

        @ParameterizedTest(name = "table {0}")
        @ValueSource(ints = {0, 1, 2, 3, 4, 5})
        @DisplayName("holds each value once, a DC category or an AC run and category of a 10-bit difference")
        void values(int table) {
            var seen = new HashSet<Integer>();
            for (var value : NikonTableDerivation.codes(CrwTables.TABLES[table]).values()) {
                if (value == CrwTables.UNDERIVED) {
                    continue;
                }
                assertTrue(seen.add(value), "value " + value + " twice");
                if (table % 2 == 0) {
                    assertTrue(value <= 11, "DC category " + value);
                } else {
                    assertTrue((value & 15) <= 10 && (value == 0 || value == 0xF0 || (value & 15) > 0), "AC value " + value);
                }
            }
        }
    }

    @Nested
    @DisplayName("against Adobe DNG Converter")
    class Oracle {
        @Test
        @DisplayName("re-deriving the codes from the samples reproduces every table")
        void derivation() {
            var tables = new ArrayList<Map<Long, Integer>>();
            for (var index = 0; index < 2 * CrwTables.SETS; index++) {
                tables.add(new TreeMap<>());
            }
            for (var sample : SAMPLES) {
                var problem = CrwTableDerivation.problem(sample.fetch(), oracle(sample));
                var result = CrwTableDerivation.derive(problem);
                assertNotNull(result, sample + " has no consistent code");
                CrwTableDerivation.merge(tables, problem, result);
            }
            for (var index = 0; index < 2 * CrwTables.SETS; index++) {
                var spec = NikonTableDerivation.complete(tables.get(index));
                System.out.println("table " + index + ": " + Arrays.toString(spec));
            }
            for (var index = 0; index < 2 * CrwTables.SETS; index++) {
                assertArrayEquals(CrwTables.TABLES[index], NikonTableDerivation.complete(tables.get(index)), "table " + index);
            }
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(value = RawSamples.class, names = {"CRW_D30", "CRW_D60", "CRW_10D", "CRW_10D_3164", "CRW_10D_3165",
                "CRW_300D", "CRW_300D_3162", "CRW_DIGITAL_REBEL", "CRW_G1", "CRW_G5", "CRW_G6"})
        @DisplayName("the decoder reproduces the stored values of the sample")
        void decoding(RawSamples sample) {
            var oracle = NikonTableDerivation.oracle(oracle(sample));
            SensorPlane decoded;
            boolean twelve;
            try (var source = MediaSource.of(sample.fetch())) {
                var file = RawParser.parse(source, MediaLimits.DEFAULT);
                assertNotNull(file.sensor, file.unsupported);
                twelve = file.sensor.bitsPerSample == 12;
                decoded = new SensorPlane(file.sensor.width, file.sensor.height, 1, false, MediaLimits.DEFAULT);
                file.sensor.reader.read(decoded, MediaLimits.DEFAULT);
            }
            assertEquals(oracle.width, decoded.width);
            assertEquals(oracle.height, decoded.height);
            var total = decoded.integers.length;
            // the converter stores the photosites of a 12-bit file after its last whole group of 256
            // without their low bits
            var held = twelve ? total / 256 * 256 : total;
            assertArrayEquals(Arrays.copyOf(oracle.integers, held), Arrays.copyOf(decoded.integers, held));
            for (var index = held; index < total; index++) {
                assertEquals(oracle.integers[index], (short) (decoded.integers[index] >> 2), "photosite " + index);
            }
        }
    }
}
