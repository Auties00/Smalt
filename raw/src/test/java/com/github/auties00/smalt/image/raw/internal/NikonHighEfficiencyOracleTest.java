package com.github.auties00.smalt.image.raw.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.raw.RawSamples;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Conformance;
import com.github.auties00.smalt.testkit.Skip;
import com.github.auties00.smalt.testkit.Tolerance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the Nikon HE and HE* decoder against Adobe DNG Converter as a black-box oracle, for every CC0 HE and HE*
 * sample of raw.pixls.us (Z 9, Z 8, Z f, Z 5II, Z 50II and Z 6III, every image area the cameras offer). The converter's
 * DNG file of each sample is kept as a {@code .windows} file, which {@code tools/testdata/raw-oracle.py} writes
 * into {@code <conformance cache>/raw-oracle} ({@code -Dsmalt.test.rawOracle} overrides the directory) next to its
 * {@code .levels} file: the decoded photosites of the four corners, the center and the first and last rows must
 * equal the converter's, the sum of every position of the filter repeat over every band of 64 rows must be the
 * converter's, the SHA-256 of every photosite must be that of the converter's, and the size, filter pattern and black
 * levels must be the converter's. The tests are skipped without
 * the oracle files.
 */
class NikonHighEfficiencyOracleTest {
    private static final Path ORACLE = Path.of(System.getProperty("smalt.test.rawOracle",
            Conformance.directory().resolve("raw-oracle").toString()));

    private static final Tolerance TOLERANCE = Tolerance.exact();

    static Stream<RawSamples> samples() {
        return Arrays.stream(RawSamples.values())
                .filter(sample -> sample.name().startsWith("NEF_Z") && sample.name().contains("_HE"));
    }

    /**
     * A window of the converter's photosites.
     */
    private record Window(int left, int top, int width, int height, short[] samples) {
    }

    /**
     * The contents of a {@code .windows} file.
     */
    private record Windows(int width, int height, int band, byte[] digest, long[] sums, Window[] windows) {
    }

    private static Path oracleFile(RawSamples sample, String extension) {
        var name = sample.fileName();
        var file = ORACLE.resolve(name.substring(0, name.lastIndexOf('.')) + extension);
        Skip.unless(Files.isRegularFile(file), "data:raw-oracle", "no oracle file " + file
                + "; run tools/testdata/raw-oracle.py");
        return file;
    }

    private static Windows windows(RawSamples sample) {
        var data = ByteBuffer.wrap(read(oracleFile(sample, ".windows"))).order(ByteOrder.LITTLE_ENDIAN);
        var magic = new byte[8];
        data.get(magic);
        assertEquals("SMALTWIN", new String(magic, java.nio.charset.StandardCharsets.US_ASCII));
        assertEquals(2, data.getInt(), "the version of the .windows file; rerun tools/testdata/raw-oracle.py");
        var width = data.getInt();
        var height = data.getInt();
        var band = data.getInt();
        var count = data.getInt();
        var digest = new byte[32];
        data.get(digest);
        var sums = new long[(height + band - 1) / band * 4];
        for (var i = 0; i < sums.length; i++) {
            sums[i] = data.getLong();
        }
        var windows = new Window[count];
        for (var w = 0; w < count; w++) {
            var left = data.getInt();
            var top = data.getInt();
            var windowWidth = data.getInt();
            var windowHeight = data.getInt();
            var samples = new short[windowWidth * windowHeight];
            data.asShortBuffer().get(samples);
            data.position(data.position() + 2 * samples.length);
            windows[w] = new Window(left, top, windowWidth, windowHeight, samples);
        }
        return new Windows(width, height, band, digest, sums, windows);
    }

    private static Map<String, double[]> levelsOf(RawSamples sample) {
        var values = new HashMap<String, double[]>();
        try {
            for (var line : Files.readAllLines(oracleFile(sample, ".levels"))) {
                if (line.startsWith("#") || line.isBlank()) {
                    continue;
                }
                var fields = line.strip().split(" ");
                if (fields[0].equals("model")) {
                    continue;
                }
                values.put(fields[0], Arrays.stream(fields, 1, fields.length).mapToDouble(Double::parseDouble).toArray());
            }
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        return values;
    }

    private static byte[] read(Path path) {
        try {
            return Files.readAllBytes(path);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static RawFile parse(byte[] bytes) {
        try (var source = MediaSource.of(bytes)) {
            return RawParser.parse(source, MediaLimits.DEFAULT);
        }
    }

    private static SensorPlane decode(RawFile file) {
        var sensor = file.sensor;
        assertTrue(sensor != null, file.unsupported);
        var plane = new SensorPlane(sensor.width, sensor.height, 1, false, MediaLimits.DEFAULT);
        sensor.reader.read(plane, MediaLimits.DEFAULT);
        return plane;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("samples")
    @DisplayName("the decoded photosites match those Adobe DNG Converter stores for the sample")
    void photosites(RawSamples sample) {
        var oracle = windows(sample);
        var file = parse(read(sample.fetch()));
        var sensor = file.sensor;
        assertTrue(sensor != null, file.unsupported);
        assertEquals(sample.name().contains("_HE_STAR") ? "Nikon HE*" : "Nikon HE", sensor.compression);
        var plane = decode(file);
        assertEquals(oracle.width(), plane.width);
        assertEquals(oracle.height(), plane.height);
        var length = 0;
        for (var window : oracle.windows()) {
            length += window.samples().length;
        }
        var expected = new short[length];
        var actual = new short[length];
        var at = 0;
        for (var window : oracle.windows()) {
            System.arraycopy(window.samples(), 0, expected, at, window.samples().length);
            for (var y = 0; y < window.height(); y++) {
                System.arraycopy(plane.integers, (window.top() + y) * plane.width + window.left(), actual,
                        at + y * window.width(), window.width());
            }
            at += window.samples().length;
        }
        TOLERANCE.check(sample.name() + " windows", Tolerance.Difference.of(expected, actual, 14));
        var bands = oracle.sums().length / 4;
        for (var band = 0; band < bands; band++) {
            var top = band * oracle.band();
            var rows = Math.min(oracle.band(), plane.height - top);
            var sums = new long[4];
            for (var y = top; y < top + rows; y++) {
                for (var x = 0; x < plane.width; x++) {
                    sums[(y & 1) * 2 + (x & 1)] += plane.integers[y * plane.width + x] & 0xFFFF;
                }
            }
            for (var position = 0; position < 4; position++) {
                assertEquals(oracle.sums()[band * 4 + position], sums[position], sample.name() + ": the sum of position "
                        + position + " over rows " + top + " to " + (top + rows));
            }
        }
        var words = ByteBuffer.allocate(2 * plane.integers.length).order(ByteOrder.LITTLE_ENDIAN);
        words.asShortBuffer().put(plane.integers);
        assertEquals(HexFormat.of().formatHex(oracle.digest()), HexFormat.of().formatHex(sha256(words.array())),
                sample.name() + ": the SHA-256 of every photosite");
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("samples")
    @DisplayName("the size, color filter pattern and black levels are the converter's")
    void levels(RawSamples sample) {
        var levels = levelsOf(sample);
        var file = parse(read(sample.fetch()));
        var sensor = file.sensor;
        assertTrue(sensor != null, file.unsupported);
        assertArrayEquals(levels.get("size"), new double[]{sensor.width, sensor.height});
        assertArrayEquals(new double[]{2, 2, 0, 1, 1, 2}, levels.get("cfa"));
        assertEquals("RGGB", sensor.cfa.toString());
        var black = levels.get("black");
        for (var i = 2; i < black.length; i++) {
            assertEquals(black[i], sensor.black[0], "black level");
        }
        // the converter writes the camera's specular level; the decoder keeps the 14-bit range the codec reaches
        assertTrue(levels.get("white")[0] <= sensor.white[0], "white level");
    }

    @Test
    @DisplayName("a high-efficiency file cut short anywhere is rejected as malformed, not decoded")
    void truncated() {
        var bytes = read(RawSamples.NEF_Z6III_HE_DX.fetch());
        decode(parse(bytes));
        for (var keep : new double[]{0.999, 0.9, 0.6, 0.35}) {
            var cut = Arrays.copyOf(bytes, (int) (bytes.length * keep));
            RawFile file;
            try {
                file = parse(cut);
            } catch (MediaException.Malformed expected) {
                continue;
            }
            if (file.sensor == null) {
                continue;
            }
            assertThrows(MediaException.Malformed.class, () -> decode(file), "a file cut to " + keep);
        }
    }
}
