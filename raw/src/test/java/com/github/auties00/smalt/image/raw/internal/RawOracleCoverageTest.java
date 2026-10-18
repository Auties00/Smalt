package com.github.auties00.smalt.image.raw.internal;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Conformance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Checks every raw sample that has an Adobe DNG Converter oracle file in the coverage directory
 * against it, photosite for photosite: the compressed ORF and Panasonic RW2 version 4 samples of
 * {@code tools/testdata/vectors.tsv}, whose oracle files {@code tools/testdata/raw-oracle.py --coverage}
 * writes into {@code <conformance cache>/raw-oracle-coverage} ({@code -Dsmalt.test.rawCoverage}
 * overrides the directory). Each oracle file is matched with the sample of the same name in the
 * conformance cache; without oracle files the test is skipped. The converter may drop rows at the
 * bottom and columns on the right, so the oracle is compared at the top-left corner of the decoded
 * plane. Every photosite inside the image area (the decoder's default crop) must match; in
 * the padding columns right of it the converter fills a few zero photosites from fewer neighbors
 * than the decoder (the neighbors past the columns it keeps, or zeros it has not yet filled), so
 * up to {@value #EDGE_REPAIRS} differences are allowed there.
 */
class RawOracleCoverageTest {
    private static final int EDGE_REPAIRS = 4;

    private static final String NONE = "no oracle files";

    private static final Path ORACLE = Path.of(System.getProperty("smalt.test.rawCoverage",
            Conformance.directory().resolve("raw-oracle-coverage").toString()));

    static Stream<String> samples() throws IOException {
        if (!Files.isDirectory(ORACLE)) {
            return Stream.of(NONE);
        }
        try (var files = Files.list(ORACLE)) {
            List<String> names = files.map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(".dng"))
                    .sorted()
                    .toList();
            return names.isEmpty() ? Stream.of(NONE) : names.stream();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("samples")
    @DisplayName("the decoder reproduces every photosite the converter stores for the sample")
    void sample(String oracleName) {
        assumeTrue(!oracleName.equals(NONE), "no oracle files in " + ORACLE + "; run tools/testdata/raw-oracle.py --coverage");
        var base = oracleName.substring(0, oracleName.length() - 4);
        var raw = Stream.of(".orf", ".rw2", ".rwl")
                .map(extension -> Conformance.directory().resolve(base + extension))
                .filter(Files::isRegularFile)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no sample for " + oracleName));
        var oracle = NikonTableDerivation.oracle(ORACLE.resolve(oracleName));
        SensorPlane decoded;
        double[] crop;
        try (var source = MediaSource.of(raw)) {
            var file = RawParser.parse(source, MediaLimits.DEFAULT);
            assertTrue(file.sensor != null, file.unsupported);
            var sensor = file.sensor;
            crop = sensor.defaultCrop;
            decoded = new SensorPlane(sensor.width, sensor.height, sensor.samplesPerPixel, sensor.floating, MediaLimits.DEFAULT);
            sensor.reader.read(decoded, MediaLimits.DEFAULT);
        }
        assertTrue(oracle.width <= decoded.width && oracle.height <= decoded.height,
                "the converter stores more photosites than the file holds");
        var right = crop == null ? oracle.width : (int) (crop[0] + crop[2]);
        var mismatches = 0L;
        var edge = 0L;
        var first = -1L;
        for (var y = 0; y < oracle.height; y++) {
            for (var x = 0; x < oracle.width; x++) {
                if (oracle.integers[y * oracle.width + x] != decoded.integers[y * decoded.width + x]) {
                    if (x >= right) {
                        edge++;
                        continue;
                    }
                    if (first < 0) {
                        first = (long) y * oracle.width + x;
                    }
                    mismatches++;
                }
            }
        }
        assertEquals(0, mismatches, "photosites differ from the converter's, the first at row "
                + (first < 0 ? -1 : first / oracle.width) + " column " + (first < 0 ? -1 : first % oracle.width));
        assertTrue(edge <= EDGE_REPAIRS, edge + " photosites right of the image area differ from the converter's");
    }
}
