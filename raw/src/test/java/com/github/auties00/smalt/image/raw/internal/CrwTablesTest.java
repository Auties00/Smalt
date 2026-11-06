package com.github.auties00.smalt.image.raw.internal;

import com.github.auties00.smalt.testkit.Skip;
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

/**
 * Checks the Huffman tables of {@link CrwTables}: their structure and, against Adobe DNG Converter
 * as a black-box oracle, that {@link CrwTableDerivation} re-derives from the CC0 samples every code
 * they use, that the decoder reproduces the values the converter stores for each of them, and that
 * it reproduces them for the copies of {@link CrwCodeCopies}, whose streams use every code. The
 * oracle files are the uncompressed DNG files and summaries {@code tools/testdata/raw-oracle.py} writes into
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
        Skip.unless(Files.isRegularFile(path), "data:raw-oracle", "no oracle file " + path + "; run tools/testdata/raw-oracle.py");
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
            assertArrayEquals(spec, NikonTableDerivation.complete(codes));
        }

        @ParameterizedTest(name = "table {0}")
        @ValueSource(ints = {0, 1, 2, 3, 4, 5})
        @DisplayName("holds every DC category or every AC run and category of a 10-bit difference, and nothing else")
        void values(int table) {
            var seen = new HashSet<Integer>();
            for (var value : NikonTableDerivation.codes(CrwTables.TABLES[table]).values()) {
                seen.add(value);
                if (table % 2 == 0) {
                    assertTrue(value >= 0 && value <= 11, "DC category " + value);
                } else {
                    assertTrue(value == CrwTables.ZERO || value == 0 || value == 0xF0
                            || ((value & 15) >= 1 && (value & 15) <= 10), "AC value " + value);
                }
            }
            if (table % 2 == 0) {
                for (var category = 0; category <= 11; category++) {
                    assertTrue(seen.contains(category), "DC category " + category);
                }
            } else {
                for (var run = 0; run < 16; run++) {
                    for (var size = 1; size <= 10; size++) {
                        assertTrue(seen.contains((run << 4) | size), "AC run " + run + " category " + size);
                    }
                }
                assertTrue(seen.contains(0) && seen.contains(0xF0) && seen.contains(CrwTables.ZERO));
            }
        }

        @Test
        @DisplayName("a copy of each sample whose stream uses every code decodes, its region holding the designed values")
        void copies() {
            for (var sample : List.of(RawSamples.CRW_D60, RawSamples.CRW_G1, RawSamples.CRW_10D_3164)) {
                var copy = CrwCodeCopies.copy(sample.fetch());
                var plane = decode(copy);
                var expected = decode(sample.fetch());
                var region = CrwCodeCopies.region(sample.fetch());
                var width = plane.width;
                var shift = CrwCodeCopies.twelve(sample.fetch()) ? 2 : 0;
                for (var index = 0; index < plane.integers.length; index++) {
                    if (index < region[0] * width || index >= region[1] * width) {
                        if (expected.integers[index] != plane.integers[index]) {
                            throw new AssertionError(sample + ": photosite " + index + " outside the region differs");
                        }
                        continue;
                    }
                    var step = Math.abs(((plane.integers[index] & 0xFFFF) >> shift) - 512);
                    if (step != 0 && step != Integer.highestOneBit(step)) {
                        throw new AssertionError(sample + ": photosite " + index + " of the region is 512 +- " + step);
                    }
                }
            }
        }
    }

    @Nested
    @DisplayName("against Adobe DNG Converter")
    class Oracle {
        @Test
        @DisplayName("re-deriving the codes from the samples reproduces the codes the samples use")
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
            // the codes no sample uses come from querying the converter: codeCopies checks them
            var unused = new int[]{2, 36, 2, 113, 1, 3};
            for (var index = 0; index < 2 * CrwTables.SETS; index++) {
                var table = NikonTableDerivation.codes(CrwTables.TABLES[index]);
                var derived = tables.get(index);
                var at = index;
                derived.forEach((key, value) -> assertEquals(table.get(key), value, "table " + at + " code " + key));
                assertEquals(unused[index], table.size() - derived.size(), "table " + index + " codes no sample uses");
            }
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(value = RawSamples.class, names = {"CRW_D60", "CRW_G1", "CRW_10D_3164"})
        @DisplayName("a copy of the sample whose stream uses every code of its table set decodes to the converter's values")
        void codeCopies(RawSamples sample) {
            var name = CrwCodeCopies.name(sample.fileName());
            var path = ORACLE.resolve(name.substring(0, name.length() - 4) + ".photosites");
            Skip.unless(Files.isRegularFile(path), "data:raw-oracle", "no oracle file " + path
                    + "; run tools/testdata/raw-oracle.py");
            var copy = CrwCodeCopies.copy(sample.fetch());
            assertEquals(CrwCodeCopies.DIGESTS.get(name), CrwCodeCopies.sha256(copy), "the copy the converter read");
            var decoded = decode(copy);
            if (CrwCodeCopies.twelve(sample.fetch())) {
                // the converter stores the photosites of a 12-bit file after its last whole group of 256 without
                // their low bits
                var total = decoded.integers.length;
                for (var index = total / 256 * 256; index < total; index++) {
                    decoded.integers[index] = (short) ((decoded.integers[index] & 0xFFFF) >> 2);
                }
            }
            var photosites = VendorOracleTest.Photosites.read(path);
            assertArrayEquals(new int[]{0, 0}, photosites.locate(decoded), "the converter's photosites");
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

    private static SensorPlane decode(Path path) {
        try {
            return decode(Files.readAllBytes(path));
        } catch (java.io.IOException exception) {
            throw new java.io.UncheckedIOException(exception);
        }
    }

    private static SensorPlane decode(byte[] data) {
        try (var source = MediaSource.of(data)) {
            var file = RawParser.parse(source, MediaLimits.DEFAULT);
            assertNotNull(file.sensor, file.unsupported);
            var plane = new SensorPlane(file.sensor.width, file.sensor.height, 1, false, MediaLimits.DEFAULT);
            file.sensor.reader.read(plane, MediaLimits.DEFAULT);
            return plane;
        }
    }
}
