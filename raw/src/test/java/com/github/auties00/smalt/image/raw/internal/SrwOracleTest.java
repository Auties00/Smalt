package com.github.auties00.smalt.image.raw.internal;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Conformance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Checks Samsung's compressed SRW codings against the digests of the sensor data Adobe DNG Converter stores, run as a
 * black box by {@code tools/testdata/srw-oracle.py}: for every CC0 sample of the codings and for copies of them with
 * single fields changed, values the cameras never use.
 */
class SrwOracleTest {
    record Case(String name, String sample, String digest, String edits) {
        @Override
        public String toString() {
            return name;
        }
    }

    static List<Case> cases() throws IOException {
        var cases = new ArrayList<Case>();
        try (var stream = SrwOracleTest.class.getResourceAsStream("srw-oracle.tsv")) {
            assertNotNull(stream, "the table of digests");
            var reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.US_ASCII));
            for (var line = reader.readLine(); line != null; line = reader.readLine()) {
                if (!line.startsWith("#") && !line.isBlank()) {
                    var fields = line.split("\t", -1);
                    cases.add(new Case(fields[0], fields[1], fields[2], fields[3]));
                }
            }
        }
        return cases;
    }

    // The offsets are of the bytes of the EXIF image height; the crops are those Adobe DNG Converter 18.0 stores for the
    // files with and without the edit, as tools/testdata/raw-oracle.py reads them.
    @ParameterizedTest(name = "{0}")
    @CsvSource({"rawpixls-samsung-nx-mini-12bit-3-2.srw, 428, 8, 429, 12, 132, 40, 5472, 3648",
            "rawpixls-samsung-nx1-14bit-3-2.srw, 334, 14, 335, 64, 0, 0, 6480, 4320"})
    @DisplayName("the image of the later codings is the converter's whatever height the EXIF states")
    void exifHeight(String sample, int first, int firstValue, int second, int secondValue, double left, double top,
                    double width, double height) {
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(Conformance.fetchListed(sample));
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        bytes[first] = (byte) firstValue;
        bytes[second] = (byte) secondValue;
        try (var source = MediaSource.of(bytes)) {
            var file = RawParser.parse(source, MediaLimits.DEFAULT);
            assertArrayEquals(new double[]{left, top, width, height}, file.sensor.defaultCrop);
        }
    }

    private static String digest(short[] samples) {
        MessageDigest sha;
        try {
            sha = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
        var buffer = ByteBuffer.allocate(samples.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        buffer.asShortBuffer().put(samples);
        return HexFormat.of().formatHex(sha.digest(buffer.array()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    @DisplayName("the decoded photosites hash as the converter's do")
    void photosites(Case test) {
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(Conformance.fetchListed(test.sample()));
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        if (!test.edits().isEmpty()) {
            for (var edit : test.edits().split(",")) {
                var colon = edit.indexOf(':');
                bytes[Integer.parseInt(edit.substring(0, colon))] = (byte) Integer.parseInt(edit.substring(colon + 1), 16);
            }
        }
        try (var source = MediaSource.of(bytes)) {
            var file = RawParser.parse(source, MediaLimits.DEFAULT);
            assertNotNull(file.sensor, file.unsupported);
            var plane = SensorPlane.of(file.sensor, MediaLimits.DEFAULT);
            file.sensor.reader.read(plane, MediaLimits.DEFAULT);
            assertEquals(test.digest(), digest(plane.integers));
        }
    }
}
